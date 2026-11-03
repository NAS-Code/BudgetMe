package com.budgetme

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.*
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import com.plaid.link.OpenPlaidLink
import com.plaid.link.Plaid
import com.plaid.link.configuration.LinkTokenConfiguration
import com.plaid.link.result.LinkSuccess
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit
import kotlin.math.abs

// Bank feed from Plaid, through our backend (backend/worker.js holds Plaid's secret and the access tokens). The phone
// keeps the sync cursors and applies the changes: settle alert purchases to the bank's amount (tips), fill in amount-less
// Wells Fargo alerts, add charges that never sent an alert. Real time still comes from the alerts.

/** A bank feed transaction in the app's terms. cents > 0 = money out. replaces: the pending transaction it settles. */
data class FeedTxn(val id: String, val account: String, val cents: Long, val date: LocalDate, val merchant: String,
                   val raw: String, val pending: Boolean, val category: String?, val replaces: String? = null)

/** One connected bank in a /sync reply. */
data class FeedItem(val id: String, val institution: String, val txns: List<FeedTxn>, val cursor: String?, val error: String?)

/**
 * The backend's /sync reply in the app's terms. Accounts are labeled like the alerts ("Chase Credit Card",
 * "Wells Fargo"); only credit cards and checking are kept (no purchases to catch in savings).
 */
fun parseSync(json: String): List<FeedItem> {
    val items = JSONArray(json)
    return (0 until items.length()).map { items.getJSONObject(it) }.map { o ->
        val bank = o.getString("institution")
        if (o.has("error")) return@map FeedItem(o.getString("id"), bank, emptyList(), null, o.getString("error"))
        val accounts = o.getJSONArray("accounts").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .mapNotNull { a ->
                when {
                    a.getString("type") == "credit" -> a.getString("id") to "$bank Credit Card"
                    a.optString("subtype") == "checking" -> a.getString("id") to bank
                    else -> null
                }
            }.toMap()
        val txns = listOf("added", "modified").flatMap { k -> o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getJSONObject(it) } } }
            .mapNotNull { t ->
                FeedTxn(t.getString("id"), accounts[t.getString("account")] ?: return@mapNotNull null,
                    Math.round(t.getDouble("amount") * 100), LocalDate.parse(t.getString("date")),
                    bankName(t.optString("merchant").takeIf { it.isNotBlank() && it != "null" } ?: t.getString("raw")),
                    t.getString("raw"), t.getBoolean("pending"), t.optString("category").takeIf { it.isNotBlank() && it != "null" },
                    t.optString("replaces").takeIf { it.isNotBlank() && it != "null" })
            }
        FeedItem(o.getString("id"), bank, txns, o.getString("cursor"), null)
    }
}

// Wells Fargo debit descriptions often reach Plaid with no merchant name, e.g.
// "Purchase Authorized On 10/26 <merchant> <city> <ST> S<ref> Card <last4>" or "PURCHASE <merchant> <phone> <ST> CARD<last4>".
private val WF_WRAPPER = Regex("""(?i)^(?:recurring\s+)?(?:purchase|payment)\s+authorized\s+on\s+\d{1,2}/\d{1,2}\s+|^(?-i:PURCHASE)\s+""")  // all-caps PURCHASE only: "Purchase Street Pizza" is a name
private val WF_TAIL = Regex("""(?i)\s+(?:S\d{6,}\b.*|card\s?\d{4}\b.*)$""")
private val ACH_TAIL = Regex("""(?i)\s+(?:web|ppd|ccd)\s+pmts?\b.*$""")

/** A raw bank description rather than a merchant name (Wells Fargo's wrapper or tail, or an ACH tail). */
fun isBankDescription(name: String) = WF_WRAPPER.containsMatchIn(name) || WF_TAIL.containsMatchIn(name) || ACH_TAIL.containsMatchIn(name)

/** A merchant name from a bank description: drops Wells Fargo's wrapper, reference numbers, card digits, phone numbers. */
fun bankName(raw: String): String {
    var s = raw.trim()
    val wf = WF_WRAPPER.containsMatchIn(s) || WF_TAIL.containsMatchIn(s)
    s = s.replace(WF_WRAPPER, "").replace(WF_TAIL, "").replace(ACH_TAIL, "")
    var words = s.split(Regex("""\s+""")).filter { w -> '@' !in w && !Regex("""\d{7,}""").matches(w) }  // emails, phone/ref numbers
    if (wf && words.size > 1 && Regex("""[A-Za-z]{2}""").matches(words.last())) words = words.dropLast(1)  // the state ("Ny")
    words = words.map { it.replace(Regex("""(?<=[A-Za-z])\d{2,}$"""), "") }  // store numbers glued on ("Crunch112")
    return cleanMerchant(words.joinToString(" ")).ifEmpty { cleanMerchant(raw) }
}

data class FeedPlan(val settle: List<Pair<Txn, FeedTxn>>, val add: List<FeedTxn>)

// Plaid's categories for money moving between your own accounts (card payments are LOAN_PAYMENTS), plus wordings for when
// the category is missing.
private val NOT_SPENDING = setOf("TRANSFER_IN", "TRANSFER_OUT", "LOAN_PAYMENTS", "INCOME", "BANK_FEES_REFUND")
private val CARD_PAYMENT = Regex("""(?i)(credit crd|card payment|autopay|epay|payment thank you|online payment)""")
const val TIP_LIMIT = 1.35  // a settled amount up to 35% over the alert is the same purchase plus a tip

/**
 * Link feed transactions to the app's purchases. Already linked (by id, or by the pending transaction a posted one
 * replaces): settle if the amount changed or the alert had a placeholder merchant. Otherwise match an unlinked purchase:
 * same account (a bank debit can be a Venmo payment), within 3 days, amount equal, within the tip limit, or unknown
 * (amount-less alert); for the last two the merchant has to fit, or any charge in the window would do (a tip once
 * landed on another shop's charge). Exact amount wins, then the nearest day. Unmatched spending dated addFrom or later is added
 * (older history is already in the app or the sheet).
 * ponytail: money in (refunds, deposits) and transfers are left to the alerts; merchant names aren't compared.
 */
fun planFeed(feed: List<FeedTxn>, txns: List<Txn>, addFrom: LocalDate): FeedPlan {
    val byFeed = txns.filter { it.feedId != null }.associateBy { it.feedId }
    val open = txns.filter { it.feedId == null && it.status !in setOf("deposit", "deposit_filed") }.toMutableList()
    val settle = mutableListOf<Pair<Txn, FeedTxn>>(); val add = mutableListOf<FeedTxn>()
    for (f in feed.sortedBy { it.date }) {
        if (f.cents <= 0 || f.category in NOT_SPENDING || CARD_PAYMENT.containsMatchIn(f.raw)) continue
        (byFeed[f.id] ?: f.replaces?.let { byFeed[it] })?.let { t ->
            val changed = t.amountCents != f.cents || t.merchant in PLACEHOLDER_MERCHANTS
            // a link to another merchant's charge (an old bad match) never moves the amount again
            if (changed && t.amountCents != 0L && t.merchant !in PLACEHOLDER_MERCHANTS && !nameFits(t.merchant, f.merchant)) continue
            if (t.feedId != f.id || changed) settle += t to f
            continue
        }
        val m = open.filter { t ->
            accountsMatch(f.account, t.account) && abs(ChronoUnit.DAYS.between(LocalDate.parse(t.date), f.date)) <= 3 &&
                ((t.amountCents == 0L && nameFits(t.merchant, f.merchant)) || t.amountCents == f.cents ||
                    (f.cents > t.amountCents && f.cents <= t.amountCents * TIP_LIMIT && nameFits(t.merchant, f.merchant)))
        }.minWithOrNull(compareBy({ it.amountCents != f.cents }, { abs(ChronoUnit.DAYS.between(LocalDate.parse(it.date), f.date)) }))
        if (m != null) { open -= m; settle += m to f } else if (f.date >= addFrom) add += f
    }
    return FeedPlan(settle, add)
}

/** Same merchant, loosely ("Citi Bike" / "CITIBIK*RIDE"). The generic Wells Fargo placeholder names no merchant: anything fits. */
fun nameFits(alert: String, bank: String): Boolean {
    if (alert in PLACEHOLDER_MERCHANTS) return true
    val a = merchantKey(alert); val b = merchantKey(bank)
    return a.isNotEmpty() && b.isNotEmpty() && (a in b || b in a || seqRatio(a, b) >= 0.6)
}

private fun accountsMatch(feed: String, txn: String?) =
    txn == null || txn == feed || (txn == "Venmo" && !feed.contains("Credit"))

// ---------- backend, sign-in, sync ----------

object BankSync {
    private fun s(ctx: Context) = Brain.secrets(ctx)
    fun backend(ctx: Context) = s(ctx).getString("bank_backend", "").orEmpty().trimEnd('/')
    fun setBackend(ctx: Context, url: String) = s(ctx).edit().putString("bank_backend", url.trim()).apply()
    fun status(ctx: Context) = s(ctx).getString("bank_status", "").orEmpty()
    private fun setStatus(ctx: Context, v: String) = s(ctx).edit().putString("bank_status", v).apply()
    /** Connected banks, as last listed by the backend: item id -> institution. */
    fun banks(ctx: Context): Map<String, String> = JSONObject(s(ctx).getString("bank_items", "{}")!!).let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
    private fun setBanks(ctx: Context, m: Map<String, String>) = s(ctx).edit().putString("bank_items", JSONObject(m).toString()).apply()
    private fun cursor(ctx: Context, item: String) = s(ctx).getString("bank_cursor_$item", null)
    /** Purchases before connecting are already in the app or the sheet: only add from the day before. */
    private fun addFrom(ctx: Context, item: String) = LocalDate.parse(s(ctx).getString("bank_since_$item", null) ?: LocalDate.now().minusDays(1).toString())

    // Sign-in for the backend: a Google access token with openid (the backend reads the Google user id from it).
    private val request = AuthorizationRequest.builder().setRequestedScopes(listOf(Scope("openid"), Scope("email"))).build()
    fun authorize(ctx: Context): Task<AuthorizationResult> = Identity.getAuthorizationClient(ctx).authorize(request)
    private fun token(ctx: Context): String? = runCatching { Tasks.await(authorize(ctx), 30, TimeUnit.SECONDS) }.getOrNull()
        ?.takeUnless { it.hasResolution() }?.accessToken

    class Http(val code: Int, msg: String) : Exception(msg)

    /** Off the main thread only. */
    private fun call(ctx: Context, method: String, path: String, body: JSONObject? = null): String {
        val t = token(ctx) ?: throw Http(401, "Sign in with Google in Settings > Bank sync")
        val conn = URL(backend(ctx) + path).openConnection() as HttpURLConnection
        conn.requestMethod = method; conn.connectTimeout = 15_000; conn.readTimeout = 60_000
        conn.setRequestProperty("Authorization", "Bearer $t")
        if (body != null) { conn.doOutput = true; conn.setRequestProperty("Content-Type", "application/json"); conn.outputStream.use { it.write(body.toString().toByteArray()) } }
        try {
            val ok = conn.responseCode in 200..299
            val text = (if (ok) conn.inputStream else conn.errorStream)?.bufferedReader()?.readText().orEmpty()
            if (!ok) throw Http(conn.responseCode, runCatching { JSONObject(text).getString("error") }.getOrDefault("HTTP ${conn.responseCode}"))
            return text
        } finally { conn.disconnect() }
    }

    fun linkToken(ctx: Context): String = JSONObject(call(ctx, "POST", "/link-token")).getString("link_token")

    fun addBank(ctx: Context, publicToken: String, institution: String) {
        val id = JSONObject(call(ctx, "POST", "/items", JSONObject().put("public_token", publicToken).put("institution", institution))).getString("id")
        s(ctx).edit().putString("bank_since_$id", LocalDate.now().minusDays(1).toString()).apply()
        setBanks(ctx, banks(ctx) + (id to institution))
        schedule(ctx); request(ctx)
    }

    fun removeBank(ctx: Context, id: String) {
        call(ctx, "DELETE", "/items/$id")
        setBanks(ctx, banks(ctx) - id)
        s(ctx).edit().remove("bank_cursor_$id").remove("bank_since_$id").apply()
    }

    /** One pass over every connected bank. Returns the status line shown in Settings. */
    suspend fun sync(ctx: Context): String {
        if (backend(ctx).isBlank()) return "Backend not set up"
        val dao = AppDb.get(ctx).dao()
        val now = java.time.LocalTime.now().withNano(0)
        val items = try {
            val cursors = JSONObject(banks(ctx).keys.associateWith { cursor(ctx, it) ?: JSONObject.NULL })
            parseSync(call(ctx, "POST", "/sync", JSONObject().put("cursors", cursors)))
        } catch (x: Exception) { return "$now · ${x.message}".also { setStatus(ctx, it) } }
        setBanks(ctx, items.associate { it.id to it.institution })
        var settled = 0; var added = 0; val problems = mutableListOf<String>()
        renameBankDescriptions(ctx)
        for (item in items) {
            if (item.error != null) {
                problems += if (item.error == "ITEM_LOGIN_REQUIRED") "${item.institution}: reconnect" else "${item.institution}: ${item.error}"
                continue
            }
            val since = item.txns.minOfOrNull { it.date } ?: LocalDate.now()
            val plan = planFeed(item.txns, dao.txnsSince(since.minusDays(3).toString()), addFrom(ctx, item.id))
            for ((t, f) in plan.settle) {
                if (t.amountCents != f.cents || t.merchant in PLACEHOLDER_MERCHANTS) {
                    val merchant = if (t.merchant in PLACEHOLDER_MERCHANTS) f.merchant else t.merchant
                    // Filed: refile at the new amount so the model unlearns the old one; otherwise just correct it.
                    if (t.categoryId != null && t.status != "needs_review")
                        Brain.file(ctx, t.id, Choice(t.categoryId, t.funWeek?.let { FunWeek(t.budgetMonth, it) }), t.status,
                            merchant = merchant, cents = f.cents, budgetMonth = t.budgetMonth)
                    else { dao.update(t.copy(amountCents = f.cents, merchant = merchant)); Sheets.request(ctx) }
                    settled++
                }
                dao.setFeedId(t.id, f.id)
            }
            for (f in plan.add) {
                val id = Brain.ingest(ctx, f.merchant, f.cents, f.date, f.account, f.raw, "feed") ?: continue
                dao.setFeedId(id, f.id); added++
            }
            item.cursor?.let { s(ctx).edit().putString("bank_cursor_${item.id}", it).apply() }  // only after applying
        }
        return ("$now · " + problems.joinToString("; ").ifEmpty { "$settled updated, $added added" }).also { setStatus(ctx, it) }
    }

    /**
     * Purchases that got a raw bank description as their name (before bankName existed) get a clean one. Filed ones are
     * refiled under the new name so the model learns it (and the old description becomes an alias); the sheet follows.
     */
    private suspend fun renameBankDescriptions(ctx: Context) {
        val dao = AppDb.get(ctx).dao()
        for (t in dao.txnsSince(LocalDate.now().minusDays(60).toString())) {
            if (t.feedId == null || !isBankDescription(t.merchant)) continue
            val name = bankName(t.merchant)
            if (t.categoryId != null && t.status != "needs_review")
                Brain.file(ctx, t.id, Choice(t.categoryId, t.funWeek?.let { FunWeek(t.budgetMonth, it) }), t.status,
                    merchant = name, budgetMonth = t.budgetMonth)
            else { dao.update(t.copy(merchant = name)); Sheets.request(ctx) }
        }
    }

    /** After a bank alert: ask Plaid to check the banks now, then sync once it has had time. At most every 10 minutes. */
    fun afterAlert(ctx: Context) {
        if (banks(ctx).isEmpty() || backend(ctx).isBlank()) return
        val last = s(ctx).getLong("bank_refresh_at", 0)
        if (System.currentTimeMillis() - last < 10 * 60_000) return
        s(ctx).edit().putLong("bank_refresh_at", System.currentTimeMillis()).apply()
        WorkManager.getInstance(ctx).enqueueUniqueWork("bank-refresh", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<BankRefreshWorker>().setConstraints(network).build())
    }

    fun refreshNow(ctx: Context) { runCatching { call(ctx, "POST", "/refresh") } }

    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun request(ctx: Context, delayMinutes: Long = 0) = WorkManager.getInstance(ctx).enqueueUniqueWork("bank-sync", ExistingWorkPolicy.REPLACE,
        OneTimeWorkRequestBuilder<BankSyncWorker>().setInitialDelay(delayMinutes, TimeUnit.MINUTES).setConstraints(network).build())

    /** Every 15 minutes, Android's minimum for background work (it may run later while the phone is idle). */
    fun schedule(ctx: Context) = WorkManager.getInstance(ctx).enqueueUniquePeriodicWork("bank-sync-periodic",
        ExistingPeriodicWorkPolicy.KEEP, PeriodicWorkRequestBuilder<BankSyncWorker>(15, TimeUnit.MINUTES).setConstraints(network).build())
}

class BankSyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result { BankSync.sync(applicationContext); return Result.success() }
}

/** Plaid's refresh is asynchronous: the new transactions are usually ready a few minutes later. */
class BankRefreshWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result { BankSync.refreshNow(applicationContext); BankSync.request(applicationContext, 5); return Result.success() }
}

// ---------- UI ----------

@Composable
fun BankSettings() {
    val ctx = LocalContext.current
    var backend by remember { mutableStateOf(BankSync.backend(ctx)) }
    var banks by remember { mutableStateOf(BankSync.banks(ctx)) }
    var status by remember { mutableStateOf(BankSync.status(ctx)) }
    var removing by remember { mutableStateOf<Pair<String, String>?>(null) }

    val link = rememberLauncherForActivityResult(OpenPlaidLink()) { result ->
        if (result is LinkSuccess) {
            status = "Connecting…"
            Brain.scope.launch {
                status = runCatching {
                    BankSync.addBank(ctx, result.publicToken, result.metadata.institution?.name ?: "Bank")
                    banks = BankSync.banks(ctx); "Connected; syncing…"
                }.getOrElse { "Couldn't connect: ${it.message}" }
            }
        }
    }
    fun openLink() {
        status = "Opening Plaid…"
        Brain.scope.launch {
            runCatching { BankSync.linkToken(ctx) }
                .onSuccess { t -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                    link.launch(Plaid.createPlaidLinkSession(ctx, LinkTokenConfiguration.Builder().token(t).build()))
                    status = ""
                } }
                .onFailure { status = "Couldn't start: ${it.message}" }
        }
    }
    // Google consent for the backend's sign-in, once; then Plaid Link.
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        if (runCatching { Identity.getAuthorizationClient(ctx).getAuthorizationResultFromIntent(res.data).accessToken != null }.getOrDefault(false)) openLink()
        else status = "Google sign-in was cancelled"
    }
    fun connect() = BankSync.authorize(ctx)
        .addOnSuccessListener { r -> if (r.hasResolution()) consent.launch(IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build()) else openLink() }
        .addOnFailureListener { status = "Couldn't sign in: ${it.message}" }

    SettingsSection("Bank sync (Plaid)", if (banks.isEmpty()) "Not connected" else banks.values.joinToString()) {
        Text("Every 15 minutes, and a few minutes after each bank alert, the app reads your bank's transactions: alert purchases " +
            "get the final amount (tips), Wells Fargo alerts without an amount are filled in, and charges that sent no alert land in " +
            "the Inbox. Read-only; your transactions stay on this phone.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(backend, { backend = it; BankSync.setBackend(ctx, it) }, label = { Text("Backend URL") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        for ((id, name) in banks) Row {
            Text(name, Modifier.weight(1f).padding(top = 12.dp))
            TextButton(onClick = { removing = id to name }) { Text("Remove") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = backend.isNotBlank(), onClick = { connect() }) { Text("Connect a bank") }
            OutlinedButton(enabled = banks.isNotEmpty(), onClick = {
                status = "Syncing…"; Brain.scope.launch { status = BankSync.sync(ctx) }
            }) { Text("Sync now") }
        }
        if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
    }

    removing?.let { (id, name) ->
        AlertDialog(onDismissRequest = { removing = null },
            title = { Text("Remove $name?") },
            text = { Text("The app stops reading this bank and Plaid forgets the connection. Purchases already in the app stay.") },
            confirmButton = { TextButton(onClick = {
                removing = null
                Brain.scope.launch {
                    status = runCatching { BankSync.removeBank(ctx, id); banks = BankSync.banks(ctx); "Removed $name" }
                        .getOrElse { "Couldn't remove: ${it.message}" }
                }
            }) { Text("Remove") } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } })
    }
}
