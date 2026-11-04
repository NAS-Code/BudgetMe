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

/**
 * One connected bank in a /sync reply. removed: ids of transactions the bank dropped (a cancelled pending charge, or a
 * pending one that posted). available: checking accounts' available balance as Plaid last read it (counts pending charges).
 */
data class FeedItem(val id: String, val institution: String, val txns: List<FeedTxn>, val cursor: String?, val error: String?,
                    val removed: List<String> = emptyList(), val available: Map<String, Long> = emptyMap())

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
        val removed = o.optJSONArray("removed")?.let { a -> (0 until a.length()).map { a.getString(it) } }.orEmpty()
        val available = o.getJSONArray("accounts").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
            .filter { it.optString("subtype") == "checking" && !it.isNull("available") && it.has("available") }
            .associate { bank to Math.round(it.getDouble("available") * 100) }
        FeedItem(o.getString("id"), bank, txns, o.getString("cursor"), null, removed, available)
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

private val REVERSAL = Regex("""(?i)\breversal\b""")

/**
 * Purchases whose bank charge was cancelled: linked to a transaction the bank removed with nothing posting in its place
 * (a posted charge names the pending one it replaces), or matched by a "REVERSAL" credit (Wells Fargo) of the same
 * amount, account and merchant within 3 days.
 */
fun cancelledCharges(item: FeedItem, txns: List<Txn>): List<Txn> {
    val replaced = item.txns.mapNotNull { it.replaces }.toSet()
    val gone = item.removed.filter { it !in replaced }.toSet()
    val reversals = item.txns.filter { it.cents < 0 && REVERSAL.containsMatchIn(it.raw) }
    return txns.filter { t ->
        t.feedId != null && (t.feedId in gone || reversals.any { r ->
            accountsMatch(r.account, t.account) && -r.cents == t.amountCents && nameFits(t.merchant, r.merchant) &&
                abs(ChronoUnit.DAYS.between(LocalDate.parse(t.date), r.date)) <= 3
        })
    }
}

/** Same merchant, loosely ("Citi Bike" / "CITIBIK*RIDE"). The generic Wells Fargo placeholder names no merchant: anything fits. */
fun nameFits(alert: String, bank: String): Boolean {
    if (alert in PLACEHOLDER_MERCHANTS) return true
    val a = merchantKey(alert); val b = merchantKey(bank)
    return a.isNotEmpty() && b.isNotEmpty() && (a in b || b in a || seqRatio(a, b) >= 0.6)
}

private fun accountsMatch(feed: String, txn: String?) =
    txn == null || txn == feed || (txn == "Venmo" && !feed.contains("Credit"))

// "CHASE CREDIT CRD EPAY ...": a credit card payment leaving the bank account (not a loan or mortgage payment)
private val CARD_PAYMENT_OUT = Regex("""(?i)credit\s*(?:crd|card)\b|\bcard\s*(?:pmt|payment)\b""")

/**
 * Card payments that left the bank account and still need recording (what "Record card payment" does): posted, dated
 * `since` or later (older ones were recorded by hand), not handled before (by id, or as the pending one a posted one
 * replaces), and not a payment the user already recorded by hand: same amount within a week.
 */
fun cardPaymentsToRecord(feed: List<FeedTxn>, since: LocalDate, done: Set<String>, manual: List<Pair<LocalDate, Long>>): List<FeedTxn> =
    feed.filter { f ->
        !f.account.contains("Credit") && f.cents > 0 && !f.pending && f.date >= since && CARD_PAYMENT_OUT.containsMatchIn(f.raw) &&
            f.id !in done && (f.replaces == null || f.replaces !in done) &&
            manual.none { (d, c) -> c == f.cents && abs(ChronoUnit.DAYS.between(d, f.date)) <= 7 }
    }.distinctBy { it.id }

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
        var settled = 0; var added = 0; var cancelled = 0; val problems = mutableListOf<String>()
        renameBankDescriptions(ctx)
        for (item in items) {
            item.available[BANK_ACCOUNT]?.let { Brain.setLiveBalance(ctx, it, System.currentTimeMillis()) }
            if (item.error != null) {
                problems += if (item.error == "ITEM_LOGIN_REQUIRED") "${item.institution}: reconnect" else "${item.institution}: ${item.error}"
                continue
            }
            recordCardPayments(ctx, item.txns)
            // first: undo cancelled charges, so a re-charge in this same batch can match the purchase again
            for (t in cancelledCharges(item, dao.txnsSince(LocalDate.now().minusDays(30).toString()))) {
                when {
                    t.source == "feed" -> Brain.delete(ctx, t.id)  // the bank charge was the only sign of it
                    // an alert's purchase was real: back to no amount, so the bank's next charge for it fills it in
                    t.categoryId != null && t.status != "needs_review" ->
                        Brain.file(ctx, t.id, Choice(t.categoryId, t.funWeek?.let { FunWeek(t.budgetMonth, it) }), t.status,
                            cents = 0, budgetMonth = t.budgetMonth)
                    else -> { dao.update(t.copy(amountCents = 0)); Sheets.request(ctx) }
                }
                if (t.source != "feed") dao.setFeedId(t.id, null)
                cancelled++
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
        watchGap(ctx)
        return ("$now · " + problems.joinToString("; ").ifEmpty { "$settled updated, $added added" + if (cancelled > 0) ", $cancelled cancelled" else "" }).also { setStatus(ctx, it) }
    }

    /**
     * After each sync: if BudgetMe and the bank's live balance disagree by the same amount for an hour (pending charges
     * settle within that, and a changing gap means things are still arriving), say so once. Cleared when they match.
     */
    private suspend fun watchGap(ctx: Context) {
        val k = Brain.bankCheck(ctx, LocalDate.now().month()) ?: return
        if (!k.live) return
        val p = Brain.prefs(ctx); val gap = k.bankCents - k.appCents; val now = System.currentTimeMillis()
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        when {
            gap == 0L -> { p.edit().remove("gap_cents").remove("gap_since").remove("gap_told").apply(); nm.cancel(GAP_ID) }
            p.getLong("gap_cents", 0L) != gap -> p.edit().putLong("gap_cents", gap).putLong("gap_since", now).putBoolean("gap_told", false).apply()
            !p.getBoolean("gap_told", false) && now - p.getLong("gap_since", now) >= 3_600_000L -> {
                nm.createNotificationChannel(android.app.NotificationChannel("balance", "Balance doesn't match the bank",
                    android.app.NotificationManager.IMPORTANCE_DEFAULT))
                nm.notify(GAP_ID, android.app.Notification.Builder(ctx, "balance").setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle("BudgetMe is ${money(Math.abs(gap))} off from Wells Fargo")
                    .setContentText("The bank has ${if (gap > 0) "more" else "less"}. Month > Check bank shows what's different.")
                    .setContentIntent(android.app.PendingIntent.getActivity(ctx, GAP_ID, android.content.Intent(ctx, MainActivity::class.java),
                        android.app.PendingIntent.FLAG_IMMUTABLE)).setAutoCancel(true).build())
                p.edit().putBoolean("gap_told", true).apply()
            }
        }
    }
    private const val GAP_ID = 0x6A9

    // ---------- card payments: recorded from the bank feed like the "Record card payment" button ----------

    private fun doneSet(ctx: Context) = s(ctx).getStringSet("card_payments_done", emptySet())!!
    /** Payments recorded by hand: date|cents, so the bank feed doesn't record them again. Last 20 kept. */
    private fun manualPayments(ctx: Context) = s(ctx).getStringSet("card_payments_manual", emptySet())!!
        .mapNotNull { e -> e.split("|").takeIf { it.size == 2 }?.let { LocalDate.parse(it[0]) to it[1].toLong() } }
    fun noteManualPayment(ctx: Context, cents: Long) {
        if (cents <= 0) return
        val list = (manualPayments(ctx) + (LocalDate.now() to cents)).sortedBy { it.first }.takeLast(20)
        s(ctx).edit().putStringSet("card_payments_manual", list.map { "${it.first}|${it.second}" }.toSet()).apply()
    }
    /** Payments before this feature existed were recorded by hand; only later ones are recorded automatically. */
    private fun autoSince(ctx: Context): LocalDate = s(ctx).getString("card_payments_since", null)?.let(LocalDate::parse)
        ?: LocalDate.now().also { s(ctx).edit().putString("card_payments_since", it.toString()).apply() }

    private suspend fun recordCardPayments(ctx: Context, feed: List<FeedTxn>) {
        for (f in cardPaymentsToRecord(feed, autoSince(ctx), doneSet(ctx), manualPayments(ctx))) {
            applyPayment(ctx, f.cents)
            s(ctx).edit().putStringSet("card_payments_done", doneSet(ctx) + f.id + listOfNotNull(f.replaces)).apply()
            noteManualPayment(ctx, f.cents)  // so a late "payment received" alert for it does nothing
        }
    }

    /**
     * Chase's "We've received your $X payment" alert: record it now, the day before Wells Fargo posts it. Noted like a
     * payment recorded by hand, so the bank feed (and a repeated alert) skips it. Alerts from before automatic payments
     * started are ignored, so re-parsing old alerts can't pay the card twice.
     */
    suspend fun recordPaymentFromAlert(ctx: Context, cents: Long, date: LocalDate) {
        if (cents <= 0 || date.isBefore(autoSince(ctx))) return
        if (manualPayments(ctx).any { (d, c) -> c == cents && abs(ChronoUnit.DAYS.between(d, date)) <= 7 }) return
        applyPayment(ctx, cents)
        noteManualPayment(ctx, cents)
    }

    /** What the "Record card payment" button does: oldest charges first, the rest toward the next one; with an Undo notification. */
    internal suspend fun applyPayment(ctx: Context, cents: Long) {
        val dao = AppDb.get(ctx).dao()
        val txns = dao.unpaidCardList(); val lines = dao.unpaidCardLineList()
        val unpaid = cardItems(txns, lines)
        val picked = pickPayment(unpaid, cents)
        val partial = partialPayment(unpaid, picked, cents)
        val txnIds = picked.filter { it.startsWith("t") }.map { it.drop(1).toLong() }
        val lineIds = picked.filter { it.startsWith("l") }.map { it.drop(1).toLong() }
        val part = partial?.let { (item, c) -> item.key.drop(1).toLong() to c }
        // what Undo puts back: each touched purchase's paid part before, and the bills taken off the card
        val touched = txns.filter { it.id in txnIds || it.id == part?.first }
        val undo = JSONObject().put("txns", JSONArray(touched.map { JSONObject().put("id", it.id).put("paidCents", it.cardPaidCents) }))
            .put("lines", JSONArray(lineIds))
        Brain.recordCardPayment(ctx, txnIds, lineIds, part)
        val key = System.currentTimeMillis()
        s(ctx).edit().putString("card_payment_undo_$key", undo.toString()).apply()
        val covered = unpaid.filter { it.key in picked }.sumOf { it.cents } + (partial?.second ?: 0)
        notifyPayment(ctx, key, cents, picked.size + (if (partial != null) 1 else 0), cents - covered)
    }

    private fun notifyPayment(ctx: Context, key: Long, cents: Long, charges: Int, extra: Long) {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel("payments", "Card payments recorded", android.app.NotificationManager.IMPORTANCE_LOW))
        val undo = android.app.PendingIntent.getBroadcast(ctx, key.toInt(),
            android.content.Intent(ctx, ActionReceiver::class.java).setData(android.net.Uri.parse("budgetme://$key/undopay")),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        val text = "$charges card charge${if (charges == 1) "" else "s"} marked paid" +
            if (extra > 0) " · ${money(extra)} more than the card owed" else ""
        nm.notify(key.toInt(), android.app.Notification.Builder(ctx, "payments").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Recorded ${money(cents)} card payment").setContentText(text).setAutoCancel(true)
            .addAction(android.app.Notification.Action.Builder(null, "Undo", undo).build()).build())
    }

    /** Puts the charges back on the card as they were. The payment stays handled: record it by hand if needed. */
    suspend fun undoCardPayment(ctx: Context, key: Long) {
        val raw = s(ctx).getString("card_payment_undo_$key", null) ?: return
        val o = JSONObject(raw); val dao = AppDb.get(ctx).dao()
        val txns = o.getJSONArray("txns")
        for (i in 0 until txns.length()) txns.getJSONObject(i).let { t ->
            val id = t.getLong("id"); val paid = t.getLong("paidCents")
            dao.setCardPaid(listOf(id), false)
            if (paid > 0) dao.setCardPaidCents(id, paid)
        }
        val lines = o.getJSONArray("lines")
        if (lines.length() > 0) dao.setLinesOnCard((0 until lines.length()).map { lines.getLong(it) }, true)
        s(ctx).edit().remove("card_payment_undo_$key").apply()
        ctx.getSystemService(android.app.NotificationManager::class.java).cancel(key.toInt())
        Sheets.request(ctx)
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

    /**
     * For the "what's different" check: every bank transaction from 10 days before the month (to match purchases counted
     * toward it) to today, and the checking account's available balance. Doesn't touch the sync cursors. Blocking: call off
     * the main thread.
     */
    fun monthFeed(ctx: Context, month: java.time.YearMonth): Pair<List<FeedTxn>, Long?> {
        val start = month.atDay(1).minusDays(10); val end = minOf(LocalDate.now(), month.atEndOfMonth())
        val items = parseSync(call(ctx, "POST", "/transactions", JSONObject().put("start", start.toString()).put("end", end.toString())))
        items.firstOrNull { it.error != null }?.let { throw IllegalStateException("${it.institution}: ${it.error}") }
        return items.flatMap { it.txns } to items.firstNotNullOfOrNull { it.available[BANK_ACCOUNT] }
    }
    fun connected(ctx: Context) = banks(ctx).isNotEmpty() && backend(ctx).isNotBlank()

    /** Settings > Check the bank now: Plaid re-checks every connected bank, and a sync a few minutes later picks up what it found. */
    fun checkBank(ctx: Context): String = try {
        val r = JSONObject(call(ctx, "POST", "/refresh"))
        request(ctx, 3)
        "Asked ${r.optInt("refreshed")} of ${r.optInt("of")} banks to check. The app syncs again in about 3 minutes."
    } catch (x: Exception) { "Couldn't ask the bank: ${x.message}" }

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
        // Sync now reads what Plaid already has; this asks Plaid to go back to the bank first (it lags the bank's website)
        OutlinedButton(enabled = banks.isNotEmpty(), onClick = {
            status = "Asking the bank…"; Brain.scope.launch { status = BankSync.checkBank(ctx) }
        }) { Text("Check the bank now") }
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
