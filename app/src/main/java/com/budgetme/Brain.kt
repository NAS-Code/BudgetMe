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
        val key = prefs(ctx).getString("api_key", null)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
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
                       source: String, note: String? = null): Long? {
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
        if (th < 1.0 && p.confidence >= th && ch.isNotEmpty() && !splitWeek) file(ctx, id, ch[0], "auto_filed")
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
        val id = ingestAlert(ctx, pkg, account, title, text, postedAt)
        dao.insert(RawAlert(pkg = pkg, title = title, text = text, postedAt = postedAt, txnId = id))
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
            val id = ingestAlert(ctx, a.pkg, BANKS[a.pkg]?.label ?: continue, a.title, a.text, a.postedAt) ?: continue
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
                }
            } finally { pending.finish() }
        }
    }
}
