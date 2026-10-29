package com.budgetme

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/** One notification button: a category, plus the Fun week when the category is budgeted by weekend. */
data class Choice(val categoryId: String, val week: FunWeek? = null) {
    fun encode() = if (week == null) categoryId else "$categoryId@${week.budgetMonth}:${week.week}"
    companion object {
        fun decode(s: String): Choice {
            val (id, w) = s.split('@').let { it[0] to it.getOrNull(1) }
            return Choice(id, w?.split(':')?.let { FunWeek(it[0], it[1].toInt()) })
        }
        fun list(s: String?) = s.orEmpty().split('|').filter { it.isNotEmpty() }.map(::decode)
    }
}

fun amountText(cents: Long) = if (cents == 0L) "Amount pending" else money(cents)
fun money(cents: Long) = (if (cents < 0) "-$" else "$") + "%,.2f".format(Math.abs(cents) / 100.0)
fun monthAbbr(budgetMonth: String) = YearMonth.parse(budgetMonth).month.getDisplayName(TextStyle.SHORT, Locale.US)

/**
 * The predictor lives in memory, rebuilt from the DB on start (and after category edits or an import).
 * ponytail: one global lock around the model; fine for one user tapping one notification at a time.
 */
object Brain {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var model: Predictor? = null
    private var cats: List<Category> = emptyList()

    private suspend fun <T> withModel(ctx: Context, block: (Predictor) -> T): T = lock.withLock {
        block(model ?: build(ctx))
    }

    /** Must hold the lock. */
    private suspend fun build(ctx: Context): Predictor {
        val dao = AppDb.get(ctx).dao()
        cats = dao.categoryList()
        systemPrompt = null
        val active = cats.filter { !it.archived }
        val ids = active.map { it.id }.toSet()
        return Predictor(active.map { it.spec() }).also { p ->
            dao.aliases().forEach { p.aliases[it.descriptorKey] = it.merchantKey }
            for (t in dao.filed()) if (t.categoryId in ids) p.learn(t.merchant, t.amountCents, LocalDate.parse(t.date), t.categoryId!!)
            model = p
        }
    }

    suspend fun reload(ctx: Context) = lock.withLock { model = null; build(ctx) }

    // ---------- settings (SharedPreferences "settings") ----------
    fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
    /** Keys and tokens. A separate file so cloud backup can leave it out (res/xml/backup_rules.xml). */
    fun secrets(ctx: Context) = ctx.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    fun isSetUp(ctx: Context) = prefs(ctx).getBoolean("setup_done", false)
    /** light = file confident purchases silently, collect the rest for review; strict = ask about everything else. */
    fun isLight(ctx: Context) = prefs(ctx).getString("mode", "strict") == "light"
    fun defaultThreshold(light: Boolean) = if (light) 0.80f else 0.95f  // back-tested: ~95-98% / 100% of those correct
    fun threshold(ctx: Context) = prefs(ctx).getFloat("auto_file", defaultThreshold(isLight(ctx))).toDouble()
    /** Bank apps to listen to. Installs from before setup existed listened to these three. */
    fun enabledBanks(ctx: Context): Set<String> =
        prefs(ctx).getStringSet("banks", null) ?: setOf("com.chase.sig.android", "com.wf.wellsfargomobile", "com.venmo")

    /** First run: seed the chosen category set (an asset file) unless categories already exist. */
    suspend fun setup(ctx: Context, template: String, light: Boolean, banks: Set<String>) {
        val dao = AppDb.get(ctx).dao()
        if (dao.categoryList().isEmpty()) dao.saveAll(categoriesFromJson(ctx.assets.open(template).bufferedReader().readText()))
        prefs(ctx).edit().putString("mode", if (light) "light" else "strict").putFloat("auto_file", defaultThreshold(light))
            .putStringSet("banks", banks).putBoolean("setup_done", true).apply()
        reload(ctx)
    }

    // ---------- LLM tier ----------
    private var systemPrompt: String? = null

    /** Unknown merchant + API key set: ask Claude, blend with the prior. Null (keep the keyword guess) on any failure. */
    private suspend fun askLlm(ctx: Context, merchant: String, cents: Long, date: LocalDate, account: String?, note: String?): Prediction? {
        val key = secrets(ctx).getString("api_key", null)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val cats = categories(ctx)
        val system = lock.withLock { systemPrompt } ?: buildSystemPrompt(cats, AppDb.get(ctx).dao().filed())
            .also { p -> lock.withLock { systemPrompt = p } }
        val r = runCatching { Llm.classify(key, system, buildUserMessage(merchant, cents, date, account, note)) }.getOrNull() ?: return null
        val idByName = cats.associate { it.name to it.id }
        val probs = r.probs.mapNotNull { (name, pr) -> idByName[name]?.let { it to pr } }.toMap()
        if (probs.isEmpty()) return null
        return withModel(ctx) { it.blendLlm(probs, cents, date, "llm" + (r.merchantType?.let { t -> ": $t" } ?: "")) }
    }
    suspend fun categories(ctx: Context): List<Category> { withModel(ctx) {}; return cats }

    fun label(c: Choice): String {
        val name = cats.firstOrNull { it.id == c.categoryId }?.name ?: c.categoryId
        return if (c.week == null) name else "$name · ${monthAbbr(c.week.budgetMonth)} Wk ${c.week.week}"
    }

    /** Up to 3 buttons. A weekend-budget category with two possible weeks splits into two buttons. */
    private fun choices(p: Prediction): List<Choice> = p.buttons.flatMap { id ->
        val weekend = cats.firstOrNull { it.id == id }?.period == "weekend_month"
        if (weekend) p.funWeeks.orEmpty().map { Choice(id, it) } else listOf(Choice(id))
    }.take(3)


    /** A new purchase from an alert or typed in: predict, then auto-file or ask. */
    suspend fun ingest(ctx: Context, merchant: String, cents: Long, date: LocalDate, account: String?, raw: String,
                       source: String, note: String? = null, autoFile: Boolean = true): Long? {
        val dao = AppDb.get(ctx).dao()
        if (categories(ctx).none { !it.archived }) return null  // not set up yet; the raw alert is kept for later
        var p = withModel(ctx) { it.predict(merchant, cents, date) }
        if (!p.tier.startsWith("history")) askLlm(ctx, merchant, cents, date, account, note)?.let { p = it }
        val ch = choices(p)
        val t = Txn(source = source, account = account, rawDescriptor = raw, merchant = merchant, amountCents = cents,
            date = date.toString(), budgetMonth = date.month(), status = "needs_review", confidence = p.confidence,
            tier = p.tier, choices = ch.joinToString("|") { it.encode() }, note = note)
        val id = dao.insert(t)
        // Never auto-file when the first button is one of two Fun weeks: the week is the question.
        val splitWeek = ch.size > 1 && ch[0].week != null && ch[1].categoryId == ch[0].categoryId
        val th = threshold(ctx)
        if (autoFile && th < 1.0 && p.confidence >= th && ch.isNotEmpty() && !splitWeek) file(ctx, id, ch[0], "auto_filed")
        else if (isLight(ctx)) refreshReview(ctx)
        else notifyAsk(ctx, t.copy(id = id), ch)
        return id
    }

    /** Set (or change) a transaction's category. Keeps the model in sync: unlearn the old tap, learn the new. */
    suspend fun file(ctx: Context, id: Long, c: Choice, status: String = "confirmed", merchant: String? = null, note: String? = null,
             cents: Long? = null) {
        val dao = AppDb.get(ctx).dao()
        val t = dao.txn(id) ?: return
        val date = LocalDate.parse(t.date)
        val newMerchant = merchant?.trim()?.takeIf { it.isNotEmpty() } ?: t.merchant
        val newCents = cents ?: t.amountCents
        val teachAlias = newMerchant != t.merchant && t.merchant !in PLACEHOLDER_MERCHANTS
        withModel(ctx) { m ->
            if (t.categoryId != null && t.status != "needs_review") m.unlearn(t.merchant, t.amountCents, date, t.categoryId)
            if (teachAlias) {  // user corrected the name: that descriptor means this merchant from now on
                m.aliases[merchantKey(t.merchant)] = merchantKey(newMerchant)
            }
            m.learn(newMerchant, newCents, date, c.categoryId)
        }
        if (teachAlias) dao.save(MerchantAlias(merchantKey(t.merchant), merchantKey(newMerchant)))
        val filed = t.copy(merchant = newMerchant, amountCents = newCents, categoryId = c.categoryId, funWeek = c.week?.week,
            budgetMonth = c.week?.budgetMonth ?: date.month(), status = status, note = note ?: t.note)
        dao.update(filed)
        Sheets.request(ctx)
        if (status == "auto_filed" && !isLight(ctx)) notifyFiled(ctx, filed, c) else cancel(ctx, id)
        refreshReview(ctx)
    }

    /** Undo an auto-file: reverse its learning and ask again. */
    suspend fun undo(ctx: Context, id: Long) {
        val dao = AppDb.get(ctx).dao()
        val t = dao.txn(id) ?: return
        if (t.categoryId != null && t.status != "needs_review")
            withModel(ctx) { it.unlearn(t.merchant, t.amountCents, LocalDate.parse(t.date), t.categoryId) }
        val back = t.copy(categoryId = null, funWeek = null, budgetMonth = t.date.substring(0, 7), status = "needs_review")
        dao.update(back)
        Sheets.request(ctx)
        if (isLight(ctx)) refreshReview(ctx) else notifyAsk(ctx, back, Choice.list(t.choices))
    }

    suspend fun delete(ctx: Context, id: Long, fromSheet: Boolean = false) {
        val dao = AppDb.get(ctx).dao()
        val t = dao.txn(id) ?: return
        if (t.categoryId != null && t.status != "needs_review")
            withModel(ctx) { it.unlearn(t.merchant, t.amountCents, LocalDate.parse(t.date), t.categoryId) }
        dao.delete(t); cancel(ctx, id); refreshReview(ctx)
        if (!fromSheet) Sheets.request(ctx)
    }

    // ---------- sheet sync (Sheets.kt): changes made in the spreadsheet, applied without notifications or aliases ----------

    /** A sheet purchase with the same amount that no alert has claimed yet: maybe this alert's purchase, typed in by hand. */
    suspend fun sheetTwin(ctx: Context, t: Txn): Txn? {
        if (t.source != "alert" || t.sheetHash != null || t.amountCents == 0L) return null
        val d = LocalDate.parse(t.date)
        val months = (funWeekOptions(d).map { it.budgetMonth } + d.month()).distinct()
        return AppDb.get(ctx).dao().unpairedSheetTxns(t.amountCents, months)
            .minByOrNull { Math.abs(java.time.temporal.ChronoUnit.DAYS.between(d, LocalDate.parse(it.date))) }
    }

    /** The user confirmed the alert is a purchase already in the sheet: keep the sheet's copy. */
    suspend fun mergeIntoSheet(ctx: Context, alertId: Long, sheetTxnId: Long) {
        AppDb.get(ctx).dao().repointAlerts(alertId, sheetTxnId)
        delete(ctx, alertId, fromSheet = true)
    }

    /** A purchase typed into the sheet. Returns its id; the sheet hash is set once the id is written back. */
    suspend fun addFromSheet(ctx: Context, t: Txn): Long {
        val id = AppDb.get(ctx).dao().insert(t)
        withModel(ctx) { it.learn(t.merchant, t.amountCents, LocalDate.parse(t.date), t.categoryId!!) }
        return id
    }

    /** The sheet's version of a purchase wins: relearn if anything the model sees changed. */
    suspend fun applyFromSheet(ctx: Context, old: Txn, new: Txn, hash: String) {
        val dao = AppDb.get(ctx).dao()
        if (txnState(old) != txnState(new) || old.status == "needs_review") withModel(ctx) { m ->
            if (filed(old)) m.unlearn(old.merchant, old.amountCents, LocalDate.parse(old.date), old.categoryId!!)
            m.learn(new.merchant, new.amountCents, LocalDate.parse(new.date), new.categoryId!!)
        }
        dao.update(new.copy(sheetHash = hash))
        if (old.status == "needs_review") { cancel(ctx, old.id); refreshReview(ctx) }
    }

    // ---------- bank balance (Budget.kt) ----------

    /** Paying off the card: these charges now count toward the bank balance (sheet: Credit Card -> WellsFargo column). */
    suspend fun setCardPaid(ctx: Context, ids: Collection<Long>, paid: Boolean, lineIds: Collection<Long> = emptyList()) {
        val dao = AppDb.get(ctx).dao()
        if (ids.isNotEmpty()) dao.setCardPaid(ids.toList(), paid)
        if (lineIds.isNotEmpty()) dao.setLinesOnCard(lineIds.toList(), !paid)
        Sheets.request(ctx)
    }

    suspend fun saveLine(ctx: Context, l: Line) { AppDb.get(ctx).dao().save(l); Sheets.request(ctx) }

    /** Lines already in the sheet stay as a marker until the sync has cleared their row. */
    suspend fun deleteLine(ctx: Context, l: Line) {
        val dao = AppDb.get(ctx).dao()
        if (l.sheetHash == null) dao.delete(l) else dao.save(l.copy(deleted = true))
        Sheets.request(ctx)
    }

    /** A new month starts with last month's Income and Monthly lines: same names and estimates, no actuals yet. */
    suspend fun startMonthLines(ctx: Context, month: String) {
        val dao = AppDb.get(ctx).dao()
        if (dao.lineList(month).isNotEmpty() || month < YearMonth.now().toString()) return
        val prev = dao.lineList(YearMonth.parse(month).minusMonths(1).toString())
        for (l in prev) dao.save(l.copy(id = 0, month = month, actualCents = null, sheetHash = null, onCard = false,
            estimateCents = if (isComputed(l)) null else l.estimateCents))
    }

    /** The month's closing balance (M14), following the carry-over back as far as the app has data. Null = no data. */
    suspend fun monthBalance(ctx: Context, month: String, depth: Int = 0): Balance? {
        val dao = AppDb.get(ctx).dao()
        val lines = dao.lineList(month); val filed = dao.monthList(month)
        if (lines.isEmpty() && filed.isEmpty()) return null
        val needPrev = lines.none { isCarry(it) && it.actualCents != null }
        val prev = if (needPrev && depth < 24) monthBalance(ctx, YearMonth.parse(month).minusMonths(1).toString(), depth + 1)?.total else null
        val budgeted = categories(ctx).filter { it.budgetCents != null }.map { it.id }.toSet()
        return balance(lines, filed, dao.unfiledBankList(month), prev, budgeted, dao.depositsIn(month))
    }

    /** Drop everything still waiting for a category (never filed, so nothing to unlearn or remove from the sheet). */
    suspend fun clearInbox(ctx: Context) {
        val dao = AppDb.get(ctx).dao()
        for (id in dao.inboxIds()) cancel(ctx, id)
        dao.deleteInbox(); refreshReview(ctx)
    }

    suspend fun importHistory(ctx: Context, csv: String): Int {
        val dao = AppDb.get(ctx).dao()
        // months already linked to the sheet come from the sheet; re-importing them would orphan their row ids
        val linked = dao.linkedMonths().toSet()
        val rows = historyFromCsv(csv, categories(ctx)).filter { it.budgetMonth !in linked }
        dao.deleteHistory(); dao.insertAll(rows); reload(ctx)
        return rows.size
    }

    /** Merge: move all history to the target, archive the source. */
    suspend fun merge(ctx: Context, from: Category, to: String) {
        val dao = AppDb.get(ctx).dao()
        dao.moveCategory(from.id, to); dao.save(from.copy(archived = true)); reload(ctx)
    }

    suspend fun onAlert(ctx: Context, pkg: String, account: String, title: String, text: String, postedAt: Long) {
        val dao = AppDb.get(ctx).dao()
        if (pkg !in enabledBanks(ctx) || dao.seenAlert(pkg, text, postedAt - 10 * 60_000) > 0) return  // banks re-post/update the same alert
        val id = handleAlert(ctx, pkg, account, title, text, postedAt)
        dao.insert(RawAlert(pkg = pkg, title = title, text = text, postedAt = postedAt, txnId = id))
    }

    /** Purchase, deposit or balance. Returns the txn id, -1 for a balance alert, null if not recognized. */
    private suspend fun handleAlert(ctx: Context, pkg: String, account: String, title: String, text: String, postedAt: Long): Long? {
        val date = java.time.Instant.ofEpochMilli(postedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        venmoBalance(pkg, text)?.let { prefs(ctx).edit().putLong("venmo_balance_cents", it).putLong("venmo_balance_at", postedAt).apply(); return -1 }
        if (pkg == "com.venmo") parseAlert(pkg, title, text)?.let { p ->
            val known = prefs(ctx).takeIf { it.contains("venmo_balance_cents") }?.getLong("venmo_balance_cents", 0)
            if (venmoFromBalance(known, p.cents)) {  // paid from the Venmo balance: not tracked anywhere
                prefs(ctx).edit().putLong("venmo_balance_cents", known!! - p.cents).apply()
                return -2
            }
            // probably from the bank, but not certain: ask, never auto-file
            return ingest(ctx, p.merchant, p.cents, date, account, text.trim(), "alert", p.note, autoFile = false)
        }
        return when (val b = parseBankAlert(pkg, text)) {
            is BalanceAlert -> { setBankBalance(ctx, b.cents, postedAt); -1 }
            is DepositAlert -> addDeposit(ctx, b, date, account, text.trim())
            null -> ingestAlert(ctx, pkg, account, title, text, postedAt)
        }
    }

    // ---------- money in: deposits and the daily balance ----------

    private fun setBankBalance(ctx: Context, cents: Long, at: Long) {
        val p = prefs(ctx)
        if (at > p.getLong("bank_balance_at", 0)) p.edit().putLong("bank_balance_cents", cents).putLong("bank_balance_at", at).apply()
    }

    data class BankCheck(val bankCents: Long, val asOf: LocalDate, val appCents: Long)

    /**
     * The latest ending-balance alert against the app's balance at the same moment (end of the day before the alert):
     * this month's balance minus bank activity dated after that day. ponytail: bills and income lines have no dates,
     * so one entered today shows as a difference until tomorrow's alert.
     */
    suspend fun bankCheck(ctx: Context, month: String): BankCheck? {
        val p = prefs(ctx); val at = p.getLong("bank_balance_at", 0).takeIf { it > 0 } ?: return null
        val asOf = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalDate().minusDays(1)
        if (asOf.month() != month) return null
        val dao = AppDb.get(ctx).dao()
        val b = monthBalance(ctx, month) ?: return null
        val later = { t: Txn -> t.date > asOf.toString() }
        val outLater = dao.monthList(month).filter { fromBank(it) && later(it) }.sumOf { it.amountCents } +
            dao.unfiledBankList(month).filter(later).sumOf { it.amountCents }
        val inLater = dao.depositsIn(month).filter(later).sumOf { it.amountCents }
        return BankCheck(p.getLong("bank_balance_cents", 0), asOf, b.total + outLater - inLater)
    }

    private suspend fun addDeposit(ctx: Context, d: DepositAlert, date: LocalDate, account: String, raw: String): Long {
        val name = when (d.kind) { "paycheck" -> "Paycheck"; "zelle" -> "Zelle"; else -> "Deposit" }
        val t = Txn(source = "alert", account = account, rawDescriptor = raw, merchant = name, amountCents = d.cents,
            date = date.toString(), budgetMonth = date.month(), status = "deposit", tier = d.kind)
        val id = AppDb.get(ctx).dao().insert(t)
        if (isLight(ctx)) refreshReview(ctx) else notifyDeposit(ctx, t.copy(id = id))
        return id
    }

    suspend fun depositChoices(ctx: Context, t: Txn): List<Line> =
        depositGuess(AppDb.get(ctx).dao().lineList(t.budgetMonth), t.amountCents, t.tier ?: "deposit")

    /** File a deposit into an income line: its actual goes up by the deposit (unless it already shows exactly this amount). */
    suspend fun fileDeposit(ctx: Context, id: Long, lineId: Long?) {
        val dao = AppDb.get(ctx).dao()
        val t = dao.txn(id)?.takeIf { it.status == "deposit" } ?: return
        val line = lineId?.let { dao.line(it) }
            ?: Line(month = t.budgetMonth, section = "income", name = t.merchant, estimateCents = null, actualCents = null, sort = 1000)
        if (line.actualCents != t.amountCents) dao.save(line.copy(actualCents = (line.actualCents ?: 0) + t.amountCents))
        dao.update(t.copy(status = "deposit_filed", note = line.name))
        cancel(ctx, id); refreshReview(ctx); Sheets.request(ctx)
    }

    private suspend fun notifyDeposit(ctx: Context, t: Txn) {
        val b = Notification.Builder(ctx, "ask").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${money(t.amountCents)} deposited").setContentText("${t.account ?: ""} · which income is this?")
            .setContentIntent(open(ctx, t.id)).setAutoCancel(true)
        for (l in depositChoices(ctx, t).take(3)) b.addAction(Notification.Action.Builder(null, l.name, action(ctx, t.id, "deposit/${l.id}")).build())
        nm(ctx).notify(t.id.toInt(), b.build())
    }

    private suspend fun ingestAlert(ctx: Context, pkg: String, account: String, title: String, text: String, postedAt: Long): Long? {
        val p = parseAlert(pkg, title, text) ?: return null
        val date = java.time.Instant.ofEpochMilli(postedAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return ingest(ctx, p.merchant, p.cents, date, account, text.trim(), "alert", p.note)
    }

    /** Run the current parser over alerts it didn't recognize before (after a parser update). */
    suspend fun reparse(ctx: Context): Int {
        val dao = AppDb.get(ctx).dao()
        var n = 0
        for (a in dao.unparsedAlerts()) {
            val id = handleAlert(ctx, a.pkg, BANKS[a.pkg]?.label ?: continue, a.title, a.text, a.postedAt) ?: continue
            dao.update(a.copy(txnId = id)); n++
        }
        return n
    }

    // ---------- notifications ----------

    private fun nm(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ask", "Categorize purchases", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel("filed", "Auto-filed purchases", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("review", "Purchases to review (light mode)", NotificationManager.IMPORTANCE_LOW))
        return nm
    }

    private fun cancel(ctx: Context, id: Long) = nm(ctx).cancel(id.toInt())

    private const val REVIEW_ID = Int.MAX_VALUE

    /** Light mode: one quiet notification, "N purchases to review", instead of one per purchase. */
    suspend fun refreshReview(ctx: Context) {
        val n = AppDb.get(ctx).dao().inboxCount()
        if (!isLight(ctx) || n == 0) return nm(ctx).cancel(REVIEW_ID)
        nm(ctx).notify(REVIEW_ID, Notification.Builder(ctx, "review").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$n purchase${if (n == 1) "" else "s"} to review").setContentText("Tap to categorize")
            .setContentIntent(PendingIntent.getActivity(ctx, REVIEW_ID, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setOnlyAlertOnce(true).build())
    }

    private fun open(ctx: Context, id: Long) = PendingIntent.getActivity(ctx, id.toInt(),
        Intent(ctx, MainActivity::class.java).putExtra("txn", id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    // Unique data URI per button so Android doesn't merge the PendingIntents.
    private fun action(ctx: Context, id: Long, what: String) = PendingIntent.getBroadcast(ctx, 0,
        Intent(ctx, ActionReceiver::class.java).setData(Uri.parse("budgetme://$id/$what")),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun base(ctx: Context, t: Txn, channel: String) = Notification.Builder(ctx, channel)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("${amountText(t.amountCents)} · ${t.merchant}")
        .setContentIntent(open(ctx, t.id))
        .setAutoCancel(true)

    private fun notifyAsk(ctx: Context, t: Txn, ch: List<Choice>) {
        val b = base(ctx, t, "ask").setContentText(listOfNotNull(t.account, t.note).joinToString(" · ").ifEmpty { "Tap to choose a category" })
        for (c in ch) b.addAction(Notification.Action.Builder(null, label(c), action(ctx, t.id, "file/" + Uri.encode(c.encode()))).build())
        nm(ctx).notify(t.id.toInt(), b.build())
    }

    private fun notifyFiled(ctx: Context, t: Txn, c: Choice) {
        val b = base(ctx, t, "filed").setContentText("Filed as ${label(c)}")
            .addAction(Notification.Action.Builder(null, "Undo", action(ctx, t.id, "undo")).build())
        nm(ctx).notify(t.id.toInt(), b.build())
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val seg = intent.data?.pathSegments.orEmpty()
        val id = intent.data?.host?.toLongOrNull() ?: return
        val pending = goAsync()  // keep the receiver alive while we write to the database
        Brain.scope.launch {
            try {
                when (seg.firstOrNull()) {
                    "file" -> Brain.file(ctx, id, Choice.decode(seg[1]))
                    "undo" -> Brain.undo(ctx, id)
                    "deposit" -> Brain.fileDeposit(ctx, id, seg[1].toLongOrNull())
                }
            } finally { pending.finish() }
        }
    }
}
