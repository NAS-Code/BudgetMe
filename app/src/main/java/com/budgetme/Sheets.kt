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

/**
 * Where a section keeps things, read from its header row's labels (0-based column indexes, A = 0). The sheet's layout
 * can change (an Estimate column was added in Oct 2026), so nothing is assumed by position except A (app id) and
 * B (description). bank = the WellsFargo column (ACTUAL in the Income section).
 */
data class Cols(val date: Int?, val estimate: Int?, val card: Int?, val bank: Int?)

fun colsFrom(header: List<Any?>): Cols {
    fun find(vararg labels: String) = header.indices.firstOrNull { i ->
        val h = str(header[i]).replace(" ", ""); labels.any { h.equals(it.replace(" ", ""), true) } }
    return Cols(find("DATE"), find("ESTIMATE"), find("Credit Card"), find("WellsFargo", "ACTUAL"))
}
fun colLetter(i: Int) = ('A' + i).toString()

/** sum: the rows the section's TOTAL formula adds up (=SUM(G56:G63)); new rows go there so the total counts them. */
data class Section(val tab: String, val month: String, val categoryId: String, val week: Int?, val header: Int, val total: Int,
                   val cols: Cols, val free: List<Int>, val sum: IntRange? = null)
/**
 * onCard: the amount is only in the Credit Card column (not paid off yet). paidCard: it's in both columns (a card charge
 * that was paid off and copied over). Otherwise it's a bank purchase.
 */
data class SheetRow(val section: Section, val row: Int, val description: String, val date: LocalDate?, val cents: Long?,
                    val account: String?, val appId: Long?, val onCard: Boolean = account == CARD_ACCOUNT, val paidCard: Boolean = false)
/** skipped: section titles that matched a category but had no bank column in their header, so they were left alone. */
data class Tab(val title: String, val month: String, val sections: List<Section>, val rows: List<SheetRow>,
               val lineSections: List<LineSection> = emptyList(), val lines: List<SheetLine> = emptyList(),
               val skipped: List<String> = emptyList())

internal fun str(v: Any?): String = when (v) {
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
private val LINE_ID = Regex("""^#L(\d+)$""")
private val SUM = Regex("""(?i)^=SUM\(([A-Z]+)(\d+):\1(\d+)\)$""")

/** Category for a section title: "Grocery/Food", "MISCELLANEOUS EXPENSES" (name + expenses), "Week 3 Fun" (weekend categories). */
fun sectionFor(title: String, cats: List<Category>): Pair<String, Int?>? {
    val t = title.trim().replace(Regex("""\s+"""), " ")
    for (c in cats.filter { !it.archived }) {
        if (c.period == "weekend_month") Regex("(?i)^week\\s*(\\d)\\s+${Regex.escape(c.name)}$").find(t)?.let { return c.id to it.groupValues[1].toInt() }
        else if (t.equals(c.name, true) || t.equals("${c.name} expenses", true)) return c.id to null
    }
    return null
}

/**
 * values: the tab's cells from A1, row by row. formulas: the same cells as formulas, to find each TOTAL's SUM range
 * (optional). Reads purchase sections (a category name with a DATE header) and the Income / Monthly Expenses sections
 * (a DESCRIPTION header under an INCOME or MONTHLY EXPENSES title). Columns come from each header row's labels.
 */
fun parseTab(title: String, month: String, values: List<List<Any?>>, cats: List<Category>, formulas: List<List<Any?>> = emptyList()): Tab {
    val sections = mutableListOf<Section>(); val rows = mutableListOf<SheetRow>()
    val lineSections = mutableListOf<LineSection>(); val lines = mutableListOf<SheetLine>(); val skipped = mutableListOf<String>()
    var block: String? = null               // income | fixed | null (variable expenses)
    var open: Triple<Pair<String, Int?>, Int, Cols>? = null  // (category, week), header row, columns
    var openLines: Triple<String, Int, Cols>? = null          // section, header row, columns
    for ((i, r) in values.withIndex()) {
        val n = i + 1
        val b = str(r.getOrNull(1))
        when (b.uppercase()) { "INCOME" -> block = "income"; "MONTHLY EXPENSES" -> block = "fixed"; "VARIABLE EXPENSES" -> block = null }
        val cols = colsFrom(r)
        if (cols.date != null && b.isNotEmpty()) {
            open = null; openLines = null
            if (b.equals("DESCRIPTION", true)) block?.let { if (cols.bank != null) openLines = Triple(it, n, cols) else skipped += b }
            else sectionFor(b, cats)?.let { if (cols.bank != null) open = Triple(it, n, cols) else skipped += b }
            continue
        }
        if (!str(r.getOrNull(3)).equals("TOTAL", true)) continue
        fun sumOf(c: Int?) = c?.let { formulas.getOrNull(i)?.getOrNull(it) }?.let { SUM.find(str(it)) }
            ?.takeIf { it.groupValues[1] == colLetter(c!!) }?.let { it.groupValues[2].toInt()..it.groupValues[3].toInt() }
        fun bodyOf(header: Int) = (header + 1 until n).map { it to values.getOrElse(it - 1) { emptyList() } }
        fun freeIn(body: List<Pair<Int, List<Any?>>>, sum: IntRange?) = body.filter { (rn, c) ->
            str(c.getOrNull(0)).isEmpty() && str(c.getOrNull(1)).isEmpty() && (sum == null || rn in sum) }.map { it.first }
        fun cents(c: List<Any?>, col: Int?) = col?.let { num(c.getOrNull(it)) }?.takeIf { it != 0.0 }?.let { (it * 100).roundToLong() }
        open?.let { (cw, header, cols) ->
            val body = bodyOf(header); val sum = sumOf(cols.bank)
            val sec = Section(title, month, cw.first, cw.second, header, n, cols, freeIn(body, sum), sum)
            sections += sec
            for ((rn, c) in body) {
                val id = ID.find(str(c.getOrNull(0)))?.groupValues?.get(1)?.toLongOrNull()
                val desc = str(c.getOrNull(1))
                if (desc.isEmpty() && id == null) continue
                val card = cents(c, cols.card); val bank = cents(c, cols.bank)
                val date = cols.date?.let { date(c.getOrNull(it)) }
                rows += when {
                    bank != null -> SheetRow(sec, rn, desc, date, bank, if (card != null) CARD_ACCOUNT else BANK_ACCOUNT, id, onCard = false, paidCard = card != null)
                    card != null -> SheetRow(sec, rn, desc, date, card, CARD_ACCOUNT, id, onCard = true)
                    else -> SheetRow(sec, rn, desc, date, null, null, id)
                }
            }
        }
        openLines?.let { (section, header, cols) ->
            val body = bodyOf(header); val sum = sumOf(cols.bank)
            lineSections += LineSection(title, month, section, header, n, freeIn(body, sum), sum, cols)
            for ((rn, c) in body) {
                val name = str(c.getOrNull(1)); val id = LINE_ID.find(str(c.getOrNull(0)))?.groupValues?.get(1)?.toLongOrNull()
                if (name.isEmpty() && id == null) continue
                fun amount(col: Int?) = col?.let { num(c.getOrNull(it)) }?.let { (it * 100).roundToLong() }
                val bank = amount(cols.bank)?.takeIf { it != 0L }; val card = amount(cols.card)?.takeIf { it != 0L }
                lines += SheetLine(title, month, section, rn, name, amount(cols.estimate), bank ?: card ?: amount(cols.bank), id,
                    onCard = bank == null && card != null)
            }
        }
        open = null; openLines = null
    }
    return Tab(title, month, sections, rows, lineSections, lines, skipped)
}

fun describe(merchant: String, note: String?) = if (note.isNullOrBlank()) merchant else "$merchant ($note)"

/** "Venmo (pizza)" -> Venmo + note, the way scripts/extract_history.py splits descriptions. */
fun splitDescription(desc: String): Pair<String, String?> =
    Regex("""^(.*?)\s*\((.*)\)\s*$""").find(desc)?.let { m -> m.groupValues[1].takeIf { it.isNotBlank() }?.let { it to m.groupValues[2] } } ?: (desc to null)

private fun norm(s: String) = s.replace(Regex("""\s+"""), " ").trim().lowercase()

/** What both sides are compared on. Txn.sheetHash holds the value both sides agreed on at the last sync. */
fun rowState(r: SheetRow) = listOf(r.section.month, r.section.categoryId, r.section.week ?: "", r.cents ?: "", norm(r.description),
    r.date ?: YearMonth.parse(r.section.month).atDay(15), if (r.onCard) "card" else "bank").joinToString("|")
fun txnState(t: Txn) = listOf(t.budgetMonth, t.categoryId, t.funWeek ?: "", t.amountCents.takeIf { it != 0L } ?: "",
    norm(describe(t.merchant, t.note)), t.date, if (fromBank(t)) "bank" else "card").joinToString("|")

fun filed(t: Txn) = t.categoryId != null && t.status != "needs_review"

/** Rows without a date are filed on the 15th. ponytail: a real purchase on the 15th looks the same, so it's only ever suggested. */
fun maybeUndated(t: Txn) = t.date.endsWith("-15")

/**
 * Alerts for purchases the sheet already has (typed in by hand): pairs of (alert txn id, sheet txn id) to merge.
 * One to one, like row linking: each sheet purchase absorbs at most one alert, so the same order twice in a day
 * needs two rows. Matches on amount and date (closest first, within 3 days). Alerts without an amount never match.
 * Undated rows never auto-match (the same order twice can't be told apart without a date): see Brain.sheetTwin.
 */
fun absorbPairs(alerts: List<Txn>, sheetTxns: List<Txn>, partnered: Set<Long>): List<Pair<Long, Long>> {
    val free = sheetTxns.filter { it.id !in partnered && !maybeUndated(it) }.toMutableList()
    val out = mutableListOf<Pair<Long, Long>>()
    for (a in alerts.filter { it.amountCents != 0L }.sortedBy { it.date }) {
        val d = LocalDate.parse(a.date)
        val m = free.filter { it.amountCents == a.amountCents && abs(ChronoUnit.DAYS.between(d, LocalDate.parse(it.date))) <= 3 }
            .minByOrNull { abs(ChronoUnit.DAYS.between(d, LocalDate.parse(it.date))) } ?: continue
        free -= m; out += a.id to m.id
    }
    return out
}

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
 * maxId: highest Txn id this database ever issued (ids aren't reused). A row id above it came from another install
 * (new phone, or restored from an older backup), so it's imported instead of read as "deleted in the app".
 * Each side's change is detected against Txn.sheetHash. If the same purchase changed on both sides since the last sync,
 * the sheet wins. ponytail: the Sheets API has no per-row edit times, so "most recent wins" relies on syncing often
 * (seconds after an app change, every 30 min otherwise); per-row stamps would need an Apps Script onEdit trigger.
 */
fun planSync(tabs: List<Tab>, txns: Map<Long, Txn>, syncCats: Set<String>, window: Set<String>, maxId: Long = Long.MAX_VALUE): Plan {
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
        if (t == null && r.appId!! > maxId) { import += r.copy(appId = null); continue }  // never in this database
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

    /** AUTO: background syncs, capped. MANUAL: Sync now. PREVIEW: say what would change, change nothing. */
    enum class Mode { AUTO, MANUAL, PREVIEW }
    const val MAX_AUTO_REWRITES = 15  // a background sync rewriting more existing rows than this stops and asks

    suspend fun sync(ctx: Context, mode: Mode = Mode.AUTO): String = lock.withLock {
        val status = runCatching { syncOnce(ctx, mode) }.getOrElse { e -> "Sync failed: ${e.message ?: e.javaClass.simpleName}" }
        Brain.prefs(ctx).edit().putString("sheet_status", "${java.time.LocalTime.now().withNano(0)} · $status").apply()
        status
    }

    private suspend fun syncOnce(ctx: Context, mode: Mode): String {
        val preview = mode == Mode.PREVIEW
        if (!isOn(ctx)) return "Not connected"
        val dao = AppDb.get(ctx).dao()
        val cats = Brain.categories(ctx).filter { !it.archived }
        val now = YearMonth.now()
        val window = listOf(now.minusMonths(1), now, now.plusMonths(1)).map { it.toString() }
        var absorbed = 0
        repeat(4) {  // a structural change (new tab, inserted rows, merged alerts) means re-reading before writing
            val props = api(ctx, "GET", "?fields=sheets.properties(sheetId,title,index)").getJSONArray("sheets")
            val sheets = (0 until props.length()).map { props.getJSONObject(it).getJSONObject("properties") }
            val titleOf = window.associateWith { m -> sheets.firstOrNull { it.getString("title").trim().equals(monthTitle(m), true) }?.getString("title") }
            val present = window.filter { titleOf[it] != null }
            val ranges = present.joinToString("") { "&ranges=" + enc(q(titleOf[it]!!) + "!A1:J") }
            val got = if (present.isEmpty()) JSONArray() else
                api(ctx, "GET", "/values:batchGet?valueRenderOption=UNFORMATTED_VALUE&dateTimeRenderOption=SERIAL_NUMBER$ranges").getJSONArray("valueRanges")
            val formulas = if (present.isEmpty()) JSONArray() else api(ctx, "GET", "/values:batchGet?valueRenderOption=FORMULA$ranges").getJSONArray("valueRanges")
            val tabs = present.mapIndexed { i, m ->
                parseTab(titleOf[m]!!, m, got.getJSONObject(i).optJSONArray("values")?.rows().orEmpty(), cats,
                    formulas.getJSONObject(i).optJSONArray("values")?.rows().orEmpty())
            }
            val skipped = tabs.flatMap { t -> t.skipped.map { "${t.title}: $it" } }
            val syncCats = tabs.flatMap { it.sections }.map { it.categoryId }.toSet()
            if (syncCats.isEmpty()) return "No month tabs with matching sections for ${window.joinToString { monthTitle(it) }}"
            val ids = tabs.flatMap { it.rows }.mapNotNull { it.appId }
            val txns = (dao.inMonths(window) + dao.byIds(ids)).associateBy { it.id }

            // app purchases waiting for a month tab that doesn't exist yet: copy TEMPLATE first
            val appLines = dao.linesIn(window)
            val pendingMonths = txns.values.filter { filed(it) && it.sheetHash == null && it.source != "history" && it.categoryId in syncCats }
                .map { it.budgetMonth }.toSet() + appLines.filter { it.sheetHash == null && !it.deleted && !isComputed(it) }.map { it.month }
            val missing = window.filter { m -> m >= now.toString() && m in pendingMonths && m !in present }
            if (missing.isNotEmpty() && preview) return "Preview: would copy TEMPLATE to make ${missing.joinToString { monthTitle(it) }}, then sync."
            if (missing.isNotEmpty()) {
                val template = sheets.firstOrNull { it.getString("title").trim().equals("TEMPLATE", true) }
                    ?: throw SyncProblem("No TEMPLATE tab to copy for ${monthTitle(missing[0])}")
                api(ctx, "POST", ":batchUpdate", JSONObject().put("requests", JSONArray(missing.map { m ->
                    JSONObject().put("duplicateSheet", JSONObject().put("sourceSheetId", template.getInt("sheetId"))
                        .put("insertSheetIndex", template.getInt("index") + 1).put("newSheetName", monthTitle(m)))
                })))
                return@repeat
            }

            // alerts for purchases already in the sheet merge into the sheet's copy instead of being written twice
            val alerts = txns.values.filter { it.source == "alert" && it.sheetHash == null }
            val sheetTxns = txns.values.filter { it.sheetHash != null && it.source != "alert" }
            val merges = absorbPairs(alerts, sheetTxns, dao.alertTargets(sheetTxns.map { it.id }).toSet())
            if (merges.isNotEmpty() && !preview) {
                for ((alertId, sheetTxnId) in merges) { dao.repointAlerts(alertId, sheetTxnId); Brain.delete(ctx, alertId, fromSheet = true) }
                absorbed += merges.size
                return@repeat
            }

            val maxId = AppDb.get(ctx).query("SELECT seq FROM sqlite_sequence WHERE name = 'Txn'", null)
                .use { if (it.moveToFirst()) it.getLong(0) else 0L }
            val plan = planSync(tabs, txns, syncCats, window.toSet(), maxId)
            val linePlan = planLines(tabs.flatMap { it.lines }, appLines, tabs.map { it.month }.toSet())
            val gone = plan.delete.size + linePlan.remove.count { !it.deleted }
            val rewrites = plan.write.size + plan.clear.size + linePlan.write.size + linePlan.clear.size
            if (mode == Mode.AUTO && rewrites > MAX_AUTO_REWRITES) throw SyncProblem("This sync would change $rewrites existing rows in the sheet, " +
                "so nothing was written. Check with Preview, then use Sync now.")
            if (gone > MAX_DELETES) throw SyncProblem("$gone purchases or lines disappeared from the sheet at once, so nothing was deleted. Check the sheet, then sync again.")

            // room for new rows: insert inside full sections (before their last row, so the TOTAL formula's range grows)
            val freeBySection = tabs.flatMap { it.sections }.associateWith { it.free.toMutableList() }
            fun sectionOf(t: Txn) = freeBySection.keys.firstOrNull { it.month == t.budgetMonth && it.categoryId == t.categoryId && it.week == t.funWeek }
                ?: freeBySection.keys.firstOrNull { it.month == t.budgetMonth && it.categoryId == t.categoryId }
            val pushTargets = plan.push.mapNotNull { t -> sectionOf(t)?.let { it to t } }
            val freeByLineSection = tabs.flatMap { it.lineSections }.associateWith { it.free.toMutableList() }
            val linePushTargets = linePlan.push.mapNotNull { l -> freeByLineSection.keys.firstOrNull { it.month == l.month && it.section == l.section }?.let { it to l } }
            // (tab, row to insert before, how many): the last row inside the TOTAL's range, so the range grows
            val short = pushTargets.groupBy { it.first }.mapNotNull { (s, ts) -> (ts.size - s.free.size).takeIf { it > 0 }?.let { Triple(s.tab, s.sum?.last ?: (s.total - 1), it) } } +
                linePushTargets.groupBy { it.first }.mapNotNull { (s, ls) -> (ls.size - s.free.size).takeIf { it > 0 }?.let { Triple(s.tab, s.sum?.last ?: (s.total - 1), it) } }
            if (preview) return describePlan(plan, linePlan, pushTargets.size + linePushTargets.size, merges.size, short.sumOf { it.third }, skipped)
            if (short.isNotEmpty()) {
                val sheetIdOf = sheets.associate { it.getString("title") to it.getInt("sheetId") }
                api(ctx, "POST", ":batchUpdate", JSONObject().put("requests", JSONArray(
                    short.sortedByDescending { it.second }.map { (tab, before, n) ->  // bottom-up: earlier inserts don't shift later ones
                        JSONObject().put("insertDimension", JSONObject().put("inheritFromBefore", true).put("range", JSONObject()
                            .put("sheetId", sheetIdOf[tab]).put("dimension", "ROWS").put("startIndex", before - 1).put("endIndex", before - 1 + n)))
                    })))
                return@repeat
            }

            // 1. purchases typed in the sheet get an app id first, so it can be written back
            val imported = plan.import.map { r -> r to Brain.addFromSheet(ctx, txnFromRow(r)) }
            val linesIn = linePlan.fromSheet.map { (r, l) ->
                r to dao.save((l ?: Line(month = r.month, section = r.section, name = r.name, estimateCents = null, actualCents = null, sort = r.row,
                    inSurplus = defaultInSurplus(r.section, r.name, r.estimate)))
                    .copy(name = r.name, estimateCents = r.estimate, actualCents = r.actual, onCard = r.onCard)).let { id -> if (l == null) id else l.id }
            }
            // 2. sheet writes: text and numbers RAW (a description starting with "=" stays text), dates USER_ENTERED
            val raw = JSONArray(); val dated = JSONArray()
            fun put(arr: JSONArray, tab: String, cells: String, vararg v: Any) =
                arr.put(JSONObject().put("range", q(tab) + "!" + cells).put("values", JSONArray().put(JSONArray(v.toList()))))
            fun cell(arr: JSONArray, tab: String, col: Int?, row: Int, v: Any) { if (col != null) put(arr, tab, "${colLetter(col)}$row", v) }
            fun amountCells(tab: String, row: Int, cols: Cols, t: Txn) {
                val amount: Any = if (t.amountCents == 0L) "" else t.amountCents / 100.0
                val unpaid = !fromBank(t) && cols.card != null
                cell(raw, tab, cols.card, row, if (unpaid) amount else "")   // paying off moves it to the bank column
                cell(raw, tab, cols.bank, row, if (unpaid) "" else amount)
            }
            /** A new row: everything. */
            fun pushTxn(s: Section, row: Int, t: Txn) {
                put(raw, s.tab, "A$row:B$row", "#${t.id}", describe(t.merchant, t.note))
                if (!maybeUndated(t)) cell(dated, s.tab, s.cols.date, row, t.date)
                amountCells(s.tab, row, s.cols, t)
            }
            /** An existing row: only the cells that differ. Dates are corrected, never added. */
            fun updateTxn(r: SheetRow, t: Txn) {
                val s = r.section
                if (norm(r.description) != norm(describe(t.merchant, t.note))) put(raw, s.tab, "B${r.row}", describe(t.merchant, t.note))
                if (r.date != null && r.date.toString() != t.date) cell(dated, s.tab, s.cols.date, r.row, t.date)
                val unpaid = !fromBank(t) && s.cols.card != null
                if (r.cents != t.amountCents.takeIf { it != 0L } || r.onCard != unpaid || (r.paidCard && !isCard(t))) amountCells(s.tab, r.row, s.cols, t)
            }
            for (r in plan.clear) {
                put(raw, r.section.tab, "A${r.row}:B${r.row}", "", "")
                cell(raw, r.section.tab, r.section.cols.date, r.row, ""); cell(raw, r.section.tab, r.section.cols.card, r.row, "")
                cell(raw, r.section.tab, r.section.cols.bank, r.row, "")
            }
            for ((r, t) in plan.write) updateTxn(r, t)
            for ((s, t) in pushTargets) pushTxn(s, freeBySection[s]!!.removeAt(0), t)
            for ((r, t) in plan.fromSheet) put(raw, r.section.tab, "A${r.row}", "#${t.id}")
            for ((r, id) in imported) put(raw, r.section.tab, "A${r.row}", "#$id")
            fun money(c: Long?): Any = c?.let { it / 100.0 } ?: ""
            fun putLine(s: LineSection, row: Int, l: Line, nameAndId: Boolean = true) {
                if (nameAndId) put(raw, s.tab, "A$row:B$row", "#L${l.id}", l.name)
                cell(raw, s.tab, s.cols.estimate, row, money(l.estimateCents))
                val onCard = l.onCard && s.cols.card != null
                cell(raw, s.tab, s.cols.card, row, if (onCard) money(l.actualCents) else "")  // paying off moves it, like purchases
                cell(raw, s.tab, s.cols.bank, row, if (onCard) "" else money(l.actualCents))
            }
            val lineSectionOf = tabs.flatMap { it.lineSections }.associateBy { it.tab to it.section }
            for ((r, l) in linePlan.write) lineSectionOf[r.tab to r.section]?.let { putLine(it, r.row, l) }
            for ((s, l) in linePushTargets) putLine(s, freeByLineSection[s]!!.removeAt(0), l)
            for (r in linePlan.clear) lineSectionOf[r.tab to r.section]?.let { s ->
                put(raw, r.tab, "A${r.row}:B${r.row}", "", ""); cell(raw, r.tab, s.cols.estimate, r.row, "")
                cell(raw, r.tab, s.cols.card, r.row, ""); cell(raw, r.tab, s.cols.bank, r.row, "")
            }
            for ((r, id) in linesIn) if (r.lineId != id) put(raw, r.tab, "A${r.row}", "#L$id")
            if (raw.length() > 0) api(ctx, "POST", "/values:batchUpdate", JSONObject().put("valueInputOption", "RAW").put("data", raw))
            if (dated.length() > 0) api(ctx, "POST", "/values:batchUpdate", JSONObject().put("valueInputOption", "USER_ENTERED").put("data", dated))
            // 3. app-side bookkeeping, only after the sheet accepted the writes
            for ((r, t) in plan.fromSheet) {
                val new = txnFromRow(r, t)
                // If the app's copy can't express the row exactly, remember the app's state: the next sync then reads the
                // row again (sheet wins) instead of seeing an app-side change and writing over the sheet.
                Brain.applyFromSheet(ctx, t, new, if (txnState(new) == rowState(r)) rowState(r) else txnState(new))
            }
            for ((r, id) in imported) dao.setSheetHash(id, rowState(r))
            for ((_, t) in plan.write) dao.setSheetHash(t.id, txnState(t))
            for ((_, t) in pushTargets) dao.setSheetHash(t.id, txnState(t))
            for (r in plan.clear) r.appId?.let { id -> dao.txn(id)?.takeIf { !filed(it) || it.categoryId !in syncCats }?.let { dao.setSheetHash(id, null) } }
            for (t in plan.delete) Brain.delete(ctx, t.id, fromSheet = true)
            for ((r, id) in linesIn) dao.setLineHash(id, lineState(r))
            for ((_, l) in linePlan.write) dao.setLineHash(l.id, lineState(l))
            for ((_, l) in linePushTargets) dao.setLineHash(l.id, lineState(l))
            for (l in linePlan.remove) dao.delete(l)
            val n = listOf(plan.import.size to "added from sheet", plan.fromSheet.count { rowState(it.first) != txnState(it.second) } to "updated from sheet",
                plan.delete.size to "deleted from sheet", pushTargets.size to "written to sheet", plan.write.size to "updated in sheet",
                plan.clear.size to "removed from sheet", absorbed to "alerts matched to sheet rows",
                linePlan.fromSheet.count { (r, l) -> l == null || lineState(r) != lineState(l) } to "income/monthly lines from sheet",
                linePlan.write.size + linePushTargets.size to "income/monthly lines to sheet").filter { it.first > 0 }
            val summary = if (n.isEmpty()) "Up to date" else n.joinToString(", ") { "${it.first} ${it.second}" }
            return if (skipped.isEmpty()) summary else "$summary. Skipped (no WellsFargo/ACTUAL column found): ${skipped.joinToString()}"
        }
        throw SyncProblem("The sheet kept changing during sync; try again")
    }

    private fun describePlan(plan: Plan, lines: LinePlan, pushes: Int, merges: Int, inserts: Int, skipped: List<String>): String {
        fun where(r: SheetRow) = "${r.section.tab} row ${r.row}"
        val changedFromSheet = plan.fromSheet.filter { rowState(it.first) != txnState(it.second) }
        val out = mutableListOf("Preview, nothing changed yet.",
            "Sheet → app: ${plan.import.size} new, ${changedFromSheet.size} changed, ${plan.delete.size} deleted purchases; " +
                "${lines.fromSheet.count { (r, l) -> l == null || lineState(r) != lineState(l) }} income/monthly lines.",
            "App → sheet: $pushes new rows, ${plan.write.size} rows rewritten, ${plan.clear.size} rows cleared; " +
                "${lines.write.size} lines rewritten, ${lines.clear.size} lines cleared" + (if (inserts > 0) "; $inserts rows inserted" else "") + ".")
        if (merges > 0) out += "$merges alerts would be matched to sheet rows."
        for ((r, t) in plan.write.take(8)) out += "  rewrite ${where(r)}: ${r.description} → ${describe(t.merchant, t.note)} ${amountText(t.amountCents)}" +
            if (!fromBank(t)) " (on card)" else ""
        for (r in plan.clear.take(5)) out += "  clear ${where(r)}: ${r.description}"
        for ((r, t) in changedFromSheet.take(5)) out += "  app takes ${where(r)}: ${r.description} ${r.cents?.let { amountText(it) } ?: "(no amount)"}"
        if (skipped.isNotEmpty()) out += "Skipped (no WellsFargo/ACTUAL column found): ${skipped.joinToString()}"
        return out.joinToString("\n")
    }

    /** A sheet row as a transaction; keeps the app-only fields of `base` when updating an existing one. */
    fun txnFromRow(r: SheetRow, base: Txn? = null): Txn {
        val (merchant, note) = splitDescription(r.description)
        val date = (r.date ?: YearMonth.parse(r.section.month).atDay(15)).toString()
        return (base ?: Txn(source = "sheet", account = r.account, rawDescriptor = r.description, merchant = merchant,
            amountCents = 0, date = date, budgetMonth = r.section.month, status = "confirmed"))
            .copy(merchant = merchant, note = note, amountCents = r.cents ?: 0, date = date,
                budgetMonth = r.section.month, categoryId = r.section.categoryId, funWeek = r.section.week,
                account = when {
                    r.onCard || r.paidCard -> base?.account?.takeIf { base.let(::isCard) } ?: CARD_ACCOUNT
                    base != null && isCard(base) -> base.account  // moved to the bank column: paid off
                    else -> r.account ?: base?.account
                },
                cardPaid = !r.onCard && (r.paidCard || (base != null && isCard(base))),
                status = if (base?.status == "auto_filed") "auto_filed" else "confirmed")
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
