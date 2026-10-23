package com.budgetme

import android.content.Context
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToLong

// Two-way sync with the user's budget spreadsheet (Google Sheets), laid out like the original tracker: one tab per
// month ("October 2026"), sections whose header row has DATE in column C, purchase rows down to a TOTAL in column D.
// Columns: A app id (#123), B description, C date, E credit card amount, F bank amount.
// Talks to the Google Sheets API with the user's Google sign-in (GoogleAuth below).

// ponytail: the tracker's two amount columns map to two accounts; make this configurable if someone else's sheet differs
const val CARD_ACCOUNT = "Chase Credit Card"
const val BANK_ACCOUNT = "Wells Fargo"

fun monthTitle(month: String): String = YearMonth.parse(month).let { "${it.month.getDisplayName(TextStyle.FULL, Locale.US)} ${it.year}" }

data class Section(val tab: String, val month: String, val categoryId: String, val week: Int?, val header: Int, val total: Int,
                   val card: Boolean, val free: List<Int>)
data class SheetRow(val section: Section, val row: Int, val description: String, val date: LocalDate?, val cents: Long?,
                    val account: String?, val appId: Long?)
data class Tab(val title: String, val month: String, val sections: List<Section>, val rows: List<SheetRow>)

private fun str(v: Any?): String = when (v) {
    null, JSONObject.NULL -> ""
    is Number -> if (v.toDouble() % 1.0 == 0.0) v.toLong().toString() else v.toString()
    else -> v.toString().trim()
}
private fun num(v: Any?): Double? = (v as? Number)?.toDouble() ?: (v as? String)?.replace(Regex("""[$,\s]"""), "")?.toDoubleOrNull()
private fun date(v: Any?): LocalDate? = when (v) {
    is Number -> LocalDate.of(1899, 12, 30).plusDays(v.toLong())  // spreadsheet serial date
    is String -> runCatching { LocalDate.parse(v.trim().take(10)) }.getOrNull()
    else -> null
}
private val ID = Regex("""^#(\d+)$""")

/** Category for a section title: "Grocery/Food", "MISCELLANEOUS EXPENSES" (name + expenses), "Week 3 Fun" (weekend categories). */
fun sectionFor(title: String, cats: List<Category>): Pair<String, Int?>? {
    val t = title.trim().replace(Regex("""\s+"""), " ")
    for (c in cats.filter { !it.archived }) {
        if (c.period == "weekend_month") Regex("(?i)^week\\s*(\\d)\\s+${Regex.escape(c.name)}$").find(t)?.let { return c.id to it.groupValues[1].toInt() }
        else if (t.equals(c.name, true) || t.equals("${c.name} expenses", true)) return c.id to null
    }
    return null
}

/** values: the tab's cells from A1, row by row (columns A-F). */
fun parseTab(title: String, month: String, values: List<List<Any?>>, cats: List<Category>): Tab {
    val sections = mutableListOf<Section>(); val rows = mutableListOf<SheetRow>()
    var open: Triple<Pair<String, Int?>, Int, Boolean>? = null  // (category, week), header row, has a credit card column
    for ((i, r) in values.withIndex()) {
        val n = i + 1
        if (str(r.getOrNull(2)).equals("DATE", true)) {
            open = sectionFor(str(r.getOrNull(1)), cats)?.let { Triple(it, n, str(r.getOrNull(4)).equals("Credit Card", true)) }
            continue
        }
        if (str(r.getOrNull(3)).equals("TOTAL", true)) {
            open?.let { (cw, header, card) ->
                val body = (header + 1 until n).map { it to values.getOrElse(it - 1) { emptyList() } }
                val free = body.filter { (_, c) -> str(c.getOrNull(0)).isEmpty() && str(c.getOrNull(1)).isEmpty() }.map { it.first }
                val sec = Section(title, month, cw.first, cw.second, header, n, card, free)
                sections += sec
                for ((rn, c) in body) {
                    val id = ID.find(str(c.getOrNull(0)))?.groupValues?.get(1)?.toLongOrNull()
                    val desc = str(c.getOrNull(1))
                    if (desc.isEmpty() && id == null) continue
                    val e = num(c.getOrNull(4))?.takeIf { card && it != 0.0 }; val f = num(c.getOrNull(5))?.takeIf { it != 0.0 }
                    val (cents, account) = when {
                        e != null -> (e * 100).roundToLong() to CARD_ACCOUNT
                        f != null -> (f * 100).roundToLong() to BANK_ACCOUNT
                        else -> null to null
                    }
                    rows += SheetRow(sec, rn, desc, date(c.getOrNull(2)), cents, account, id)
                }
            }
            open = null
        }
    }
    return Tab(title, month, sections, rows)
}

fun describe(merchant: String, note: String?) = if (note.isNullOrBlank()) merchant else "$merchant ($note)"

/** "Venmo (pizza)" -> Venmo + note, the way scripts/extract_history.py splits descriptions. */
fun splitDescription(desc: String): Pair<String, String?> =
    Regex("""^(.*?)\s*\((.*)\)\s*$""").find(desc)?.let { m -> m.groupValues[1].takeIf { it.isNotBlank() }?.let { it to m.groupValues[2] } } ?: (desc to null)

private fun norm(s: String) = s.replace(Regex("""\s+"""), " ").trim().lowercase()

/** What both sides are compared on. Txn.sheetHash holds the value both sides agreed on at the last sync. */
fun rowState(r: SheetRow) = listOf(r.section.month, r.section.categoryId, r.section.week ?: "", r.cents ?: "", norm(r.description),
    r.date ?: YearMonth.parse(r.section.month).atDay(15)).joinToString("|")
fun txnState(t: Txn) = listOf(t.budgetMonth, t.categoryId, t.funWeek ?: "", t.amountCents.takeIf { it != 0L } ?: "",
    norm(describe(t.merchant, t.note)), t.date).joinToString("|")

fun filed(t: Txn) = t.categoryId != null && t.status != "needs_review"

data class Plan(
    val clear: List<SheetRow>,                    // blank the row: deleted, un-filed or moved in the app
    val write: List<Pair<SheetRow, Txn>>,         // rewrite the row from the app (app's edit is newer)
    val push: List<Txn>,                          // new in the app: add to its section
    val fromSheet: List<Pair<SheetRow, Txn>>,     // the sheet's version wins, or first link of a matching purchase
    val import: List<SheetRow>,                   // typed in the sheet: new purchase in the app
    val delete: List<Txn>,                        // deleted from the sheet
)

/**
 * tabs: the month tabs read this sync. txns: every app transaction in those months plus any whose id appears in the sheet.
 * syncCats: categories with a section in the sheet (others stay app-only). window: months new app purchases are written to.
 * Each side's change is detected against Txn.sheetHash. If the same purchase changed on both sides since the last sync,
 * the sheet wins. ponytail: the Sheets API has no per-row edit times, so "most recent wins" relies on syncing often
 * (seconds after an app change, every 30 min otherwise); per-row stamps would need an Apps Script onEdit trigger.
 */
fun planSync(tabs: List<Tab>, txns: Map<Long, Txn>, syncCats: Set<String>, window: Set<String>): Plan {
    val clear = mutableListOf<SheetRow>(); val write = mutableListOf<Pair<SheetRow, Txn>>(); val push = mutableListOf<Txn>()
    val fromSheet = mutableListOf<Pair<SheetRow, Txn>>(); val import = mutableListOf<SheetRow>()
    val claimed = mutableSetOf<Long>()
    val rows = tabs.flatMap { it.rows }

    fun appWins(r: SheetRow, t: Txn) = when {
        t.categoryId !in syncCats -> clear += r  // moved to an app-only category
        t.budgetMonth == r.section.month && t.categoryId == r.section.categoryId && t.funWeek == r.section.week -> write += r to t
        else -> { clear += r; push += t }       // different section or month: move it
    }

    for (r in rows.filter { it.appId != null }) {
        val t = txns[r.appId]
        if (t == null || !filed(t)) { clear += r; continue }
        if (!claimed.add(t.id)) { import += r.copy(appId = null); continue }  // a copied row: a new purchase
        val sheetChanged = t.sheetHash == null || rowState(r) != t.sheetHash
        val appChanged = t.sheetHash != null && txnState(t) != t.sheetHash
        when {
            sheetChanged -> fromSheet += r to t
            appChanged -> appWins(r, t)
        }
    }

    // Rows without an id: link them to a matching purchase the app already has (history import, or caught by an alert
    // and also typed in by hand), otherwise import them. Rows without an amount (template placeholders) wait.
    val open = txns.values.filter { filed(it) && it.sheetHash == null && it.id !in claimed }.toMutableList()
    for (r in rows.filter { it.appId == null && it.cents != null }) {
        val near = { t: Txn -> t.budgetMonth == r.section.month && t.amountCents == r.cents &&
            (r.date == null || abs(ChronoUnit.DAYS.between(LocalDate.parse(t.date), r.date)) <= 3) }
        val m = open.firstOrNull { near(it) && it.categoryId == r.section.categoryId } ?: open.firstOrNull(near)
        if (m != null) { open -= m; claimed += m.id; fromSheet += r to m } else import += r
    }

    val fetched = tabs.map { it.month }.toSet()
    val delete = txns.values.filter { filed(it) && it.sheetHash != null && it.id !in claimed && it.budgetMonth in fetched && it.categoryId in syncCats }
    push += open.filter { it.source != "history" && it.categoryId in syncCats && it.budgetMonth in window }
    return Plan(clear, write, push, fromSheet, import, delete)
}

// ---------- running a sync ----------

/** Anything the user has to fix (sheet link, access, missing TEMPLATE). The message is shown in Settings. */
class SyncProblem(message: String) : Exception(message)

/** Google sign-in for the Sheets API. Registered once by the developer (OAuth client for this package + signing key). */
object GoogleAuth {
    // ponytail: full spreadsheets access, because an existing sheet can't be opened with the narrower drive.file scope
    // on Android (no native picker grants it). Sharing the app widely means app-created sheets + drive.file instead.
    const val SCOPE = "https://www.googleapis.com/auth/spreadsheets"
    private val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope(SCOPE))).build()

    fun client(ctx: Context) = Identity.getAuthorizationClient(ctx)
    /** For the Settings button: may need the consent screen (result.hasResolution()). */
    fun authorize(ctx: Context): Task<AuthorizationResult> = client(ctx).authorize(request)

    /** Off the main thread only. Null if the user hasn't granted access (or revoked it). */
    fun token(ctx: Context): String? = runCatching { Tasks.await(authorize(ctx), 30, TimeUnit.SECONDS) }.getOrNull()
        ?.takeUnless { it.hasResolution() }?.accessToken
}

object Sheets {
    private val lock = Mutex()
    const val MAX_DELETES = 10  // more rows than this vanishing at once looks like a mistake, not edits

    /** The spreadsheet id, from a pasted link (docs.google.com/spreadsheets/d/<id>/...) or the bare id. */
    fun idFrom(link: String): String? = Regex("""/d/([A-Za-z0-9_-]{20,})""").find(link)?.groupValues?.get(1)
        ?: link.trim().takeIf { Regex("""[A-Za-z0-9_-]{20,}""").matches(it) }
    fun sheetId(ctx: Context) = Brain.prefs(ctx).getString("sheet_id", null)
    fun isOn(ctx: Context) = sheetId(ctx) != null && Brain.prefs(ctx).getBoolean("google_connected", false)

    private fun q(title: String) = "'" + title.replace("'", "''") + "'"
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun api(ctx: Context, method: String, path: String, body: JSONObject? = null): JSONObject {
        val token = GoogleAuth.token(ctx) ?: throw SyncProblem("Google access is off. Settings > Google Sheet > Connect.")
        val conn = URL("https://sheets.googleapis.com/v4/spreadsheets/${sheetId(ctx)}$path").openConnection() as HttpURLConnection
        conn.requestMethod = method; conn.connectTimeout = 15_000; conn.readTimeout = 60_000
        conn.setRequestProperty("Authorization", "Bearer $token")
        if (body != null) {
            conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
        }
        val ok = conn.responseCode < 400
        val text = (if (ok) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
        if (!ok) throw SyncProblem("Google Sheets: " + (runCatching { JSONObject(text).getJSONObject("error").getString("message") }.getOrNull()
            ?: "HTTP ${conn.responseCode}"))
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    private fun JSONArray.rows(): List<List<Any?>> = (0 until length()).map { i -> getJSONArray(i).let { r -> (0 until r.length()).map { r.get(it) } } }

    suspend fun sync(ctx: Context): String = lock.withLock {
        val status = runCatching { syncOnce(ctx) }.getOrElse { e -> "Sync failed: ${e.message ?: e.javaClass.simpleName}" }
        Brain.prefs(ctx).edit().putString("sheet_status", "${java.time.LocalTime.now().withNano(0)} · $status").apply()
        status
    }

    private suspend fun syncOnce(ctx: Context): String {
        if (!isOn(ctx)) return "Not connected"
        val dao = AppDb.get(ctx).dao()
        val cats = Brain.categories(ctx).filter { !it.archived }
        val now = YearMonth.now()
        val window = listOf(now.minusMonths(1), now, now.plusMonths(1)).map { it.toString() }
        repeat(4) {  // a structural change (new tab, inserted rows) means re-reading before writing
            val props = api(ctx, "GET", "?fields=sheets.properties(sheetId,title,index)").getJSONArray("sheets")
            val sheets = (0 until props.length()).map { props.getJSONObject(it).getJSONObject("properties") }
            val titleOf = window.associateWith { m -> sheets.firstOrNull { it.getString("title").trim().equals(monthTitle(m), true) }?.getString("title") }
            val present = window.filter { titleOf[it] != null }
            val ranges = present.joinToString("") { "&ranges=" + enc(q(titleOf[it]!!) + "!A1:F") }
            val got = if (present.isEmpty()) JSONArray() else
                api(ctx, "GET", "/values:batchGet?valueRenderOption=UNFORMATTED_VALUE&dateTimeRenderOption=SERIAL_NUMBER$ranges").getJSONArray("valueRanges")
            val tabs = present.mapIndexed { i, m -> parseTab(titleOf[m]!!, m, got.getJSONObject(i).optJSONArray("values")?.rows().orEmpty(), cats) }
            val syncCats = tabs.flatMap { it.sections }.map { it.categoryId }.toSet()
            if (syncCats.isEmpty()) return "No month tabs with matching sections for ${window.joinToString { monthTitle(it) }}"
            val ids = tabs.flatMap { it.rows }.mapNotNull { it.appId }
            val txns = (dao.inMonths(window) + dao.byIds(ids)).associateBy { it.id }

            // app purchases waiting for a month tab that doesn't exist yet: copy TEMPLATE first
            val pendingMonths = txns.values.filter { filed(it) && it.sheetHash == null && it.source != "history" && it.categoryId in syncCats }
                .map { it.budgetMonth }.toSet()
            val missing = window.filter { m -> m >= now.toString() && m in pendingMonths && m !in present }
            if (missing.isNotEmpty()) {
                val template = sheets.firstOrNull { it.getString("title").trim().equals("TEMPLATE", true) }
                    ?: throw SyncProblem("No TEMPLATE tab to copy for ${monthTitle(missing[0])}")
                api(ctx, "POST", ":batchUpdate", JSONObject().put("requests", JSONArray(missing.map { m ->
                    JSONObject().put("duplicateSheet", JSONObject().put("sourceSheetId", template.getInt("sheetId"))
                        .put("insertSheetIndex", template.getInt("index") + 1).put("newSheetName", monthTitle(m)))
                })))
                return@repeat
            }

            val plan = planSync(tabs, txns, syncCats, window.toSet())
            if (plan.delete.size > MAX_DELETES) throw SyncProblem("${plan.delete.size} purchases disappeared from the sheet at once, so nothing was deleted. Check the sheet, then sync again.")

            // room for new rows: insert inside full sections (before their last row, so the TOTAL formula's range grows)
            val freeBySection = tabs.flatMap { it.sections }.associateWith { it.free.toMutableList() }
            fun sectionOf(t: Txn) = freeBySection.keys.firstOrNull { it.month == t.budgetMonth && it.categoryId == t.categoryId && it.week == t.funWeek }
                ?: freeBySection.keys.firstOrNull { it.month == t.budgetMonth && it.categoryId == t.categoryId }
            val pushTargets = plan.push.mapNotNull { t -> sectionOf(t)?.let { it to t } }
            val short = pushTargets.groupBy { it.first }.mapValues { (s, ts) -> ts.size - s.free.size }.filterValues { it > 0 }
            if (short.isNotEmpty()) {
                val sheetIdOf = sheets.associate { it.getString("title") to it.getInt("sheetId") }
                api(ctx, "POST", ":batchUpdate", JSONObject().put("requests", JSONArray(
                    short.entries.sortedByDescending { it.key.total }.map { (s, n) ->  // bottom-up: earlier inserts don't shift later ones
                        JSONObject().put("insertDimension", JSONObject().put("inheritFromBefore", true).put("range", JSONObject()
                            .put("sheetId", sheetIdOf[s.tab]).put("dimension", "ROWS").put("startIndex", s.total - 2).put("endIndex", s.total - 2 + n)))
                    })))
                return@repeat
            }

            // 1. purchases typed in the sheet get an app id first, so it can be written back
            val imported = plan.import.map { r -> r to Brain.addFromSheet(ctx, txnFromRow(r)) }
            // 2. sheet writes: text and numbers RAW (a description starting with "=" stays text), dates USER_ENTERED
            val raw = JSONArray(); val dated = JSONArray()
            fun put(arr: JSONArray, tab: String, cells: String, vararg v: Any) =
                arr.put(JSONObject().put("range", q(tab) + "!" + cells).put("values", JSONArray().put(JSONArray(v.toList()))))
            fun putTxn(tab: String, row: Int, t: Txn, card: Boolean) {
                val amount: Any = if (t.amountCents == 0L) "" else t.amountCents / 100.0
                val onCard = card && t.account?.contains("Credit", true) == true
                put(raw, tab, "A$row:B$row", "#${t.id}", describe(t.merchant, t.note))
                put(dated, tab, "C$row", t.date)
                put(raw, tab, "E$row:F$row", if (onCard) amount else "", if (onCard) "" else amount)
            }
            for (r in plan.clear) { put(raw, r.section.tab, "A${r.row}:C${r.row}", "", "", ""); put(raw, r.section.tab, "E${r.row}:F${r.row}", "", "") }
            for ((r, t) in plan.write) putTxn(r.section.tab, r.row, t, r.section.card)
            for ((s, t) in pushTargets) putTxn(s.tab, freeBySection[s]!!.removeAt(0), t, s.card)
            for ((r, t) in plan.fromSheet) put(raw, r.section.tab, "A${r.row}", "#${t.id}")
            for ((r, id) in imported) put(raw, r.section.tab, "A${r.row}", "#$id")
            if (raw.length() > 0) api(ctx, "POST", "/values:batchUpdate", JSONObject().put("valueInputOption", "RAW").put("data", raw))
            if (dated.length() > 0) api(ctx, "POST", "/values:batchUpdate", JSONObject().put("valueInputOption", "USER_ENTERED").put("data", dated))
            // 3. app-side bookkeeping, only after the sheet accepted the writes
            for ((r, t) in plan.fromSheet) Brain.applyFromSheet(ctx, t, txnFromRow(r, t), rowState(r))
            for ((r, id) in imported) dao.setSheetHash(id, rowState(r))
            for ((_, t) in plan.write) dao.setSheetHash(t.id, txnState(t))
            for ((_, t) in pushTargets) dao.setSheetHash(t.id, txnState(t))
            for (r in plan.clear) r.appId?.let { id -> dao.txn(id)?.takeIf { !filed(it) || it.categoryId !in syncCats }?.let { dao.setSheetHash(id, null) } }
            for (t in plan.delete) Brain.delete(ctx, t.id, fromSheet = true)
            val n = listOf(plan.import.size to "added from sheet", plan.fromSheet.count { rowState(it.first) != txnState(it.second) } to "updated from sheet",
                plan.delete.size to "deleted from sheet", pushTargets.size to "written to sheet", plan.write.size to "updated in sheet",
                plan.clear.size to "removed from sheet").filter { it.first > 0 }
            return if (n.isEmpty()) "Up to date" else n.joinToString(", ") { "${it.first} ${it.second}" }
        }
        throw SyncProblem("The sheet kept changing during sync; try again")
    }

    /** A sheet row as a transaction; keeps the app-only fields of `base` when updating an existing one. */
    fun txnFromRow(r: SheetRow, base: Txn? = null): Txn {
        val (merchant, note) = splitDescription(r.description)
        val date = (r.date ?: YearMonth.parse(r.section.month).atDay(15)).toString()
        return (base ?: Txn(source = "sheet", account = r.account, rawDescriptor = r.description, merchant = merchant,
            amountCents = 0, date = date, budgetMonth = r.section.month, status = "confirmed"))
            .copy(merchant = merchant, note = note, amountCents = r.cents ?: base?.amountCents ?: 0, date = date,
                budgetMonth = r.section.month, categoryId = r.section.categoryId, funWeek = r.section.week,
                account = r.account ?: base?.account, status = if (base?.status == "auto_filed") "auto_filed" else "confirmed")
    }

    // ---------- scheduling ----------

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** Soon after an app-side change; repeated requests collapse into one. */
    fun request(ctx: Context, delaySeconds: Long = 10) {
        if (!isOn(ctx)) return
        WorkManager.getInstance(ctx).enqueueUniqueWork("sheet-sync", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>().setInitialDelay(delaySeconds, TimeUnit.SECONDS).setConstraints(network).build())
    }

    /** Picks up edits made in the sheet while the app is closed. */
    fun schedule(ctx: Context) {
        if (!isOn(ctx)) return
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("sheet-sync-periodic", ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(30, TimeUnit.MINUTES).setConstraints(network).build())
    }
}

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result { Sheets.sync(applicationContext); return Result.success() }
}
