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
        if (dao.categoryList().isEmpty())
            dao.saveAll(categoriesFromJson(ctx.assets.open("categories.json").bufferedReader().readText()))
        cats = dao.categoryList()
        val active = cats.filter { !it.archived }
        val ids = active.map { it.id }.toSet()
        return Predictor(active.map { it.spec() }).also { p ->
            dao.aliases().forEach { p.aliases[it.descriptorKey] = it.merchantKey }
            for (t in dao.filed()) if (t.categoryId in ids) p.learn(t.merchant, t.amountCents, LocalDate.parse(t.date), t.categoryId!!)
            model = p
        }
    }

    suspend fun reload(ctx: Context) = lock.withLock { model = null; build(ctx) }
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

    private fun threshold(ctx: Context) = ctx.getSharedPreferences("settings", 0).getFloat("auto_file", 0.95f).toDouble()

    /** A new purchase from an alert or typed in: predict, then auto-file or ask. */
    suspend fun ingest(ctx: Context, merchant: String, cents: Long, date: LocalDate, account: String?, raw: String,
                       source: String, note: String? = null): Long {
        val dao = AppDb.get(ctx).dao()
        val p = withModel(ctx) { it.predict(merchant, cents, date) }
        val ch = choices(p)
        val t = Txn(source = source, account = account, rawDescriptor = raw, merchant = merchant, amountCents = cents,
            date = date.toString(), budgetMonth = date.month(), status = "needs_review", confidence = p.confidence,
            tier = p.tier, choices = ch.joinToString("|") { it.encode() }, note = note)
        val id = dao.insert(t)
        // Never auto-file when the first button is one of two Fun weeks: the week is the question.
        val splitWeek = ch.size > 1 && ch[0].week != null && ch[1].categoryId == ch[0].categoryId
        val th = threshold(ctx)
        if (th < 1.0 && p.confidence >= th && ch.isNotEmpty() && !splitWeek) file(ctx, id, ch[0], "auto_filed")
        else notifyAsk(ctx, t.copy(id = id), ch)
        return id
    }

    /** Set (or change) a transaction's category. Keeps the model in sync: unlearn the old tap, learn the new. */
    suspend fun file(ctx: Context, id: Long, c: Choice, status: String = "confirmed", merchant: String? = null, note: String? = null) {
        val dao = AppDb.get(ctx).dao()
        val t = dao.txn(id) ?: return
        val date = LocalDate.parse(t.date)
        val newMerchant = merchant?.trim()?.takeIf { it.isNotEmpty() } ?: t.merchant
        withModel(ctx) { m ->
            if (t.categoryId != null && t.status != "needs_review") m.unlearn(t.merchant, t.amountCents, date, t.categoryId)
            if (newMerchant != t.merchant) {  // user corrected the name: that descriptor means this merchant from now on
                m.aliases[merchantKey(t.merchant)] = merchantKey(newMerchant)
            }
            m.learn(newMerchant, t.amountCents, date, c.categoryId)
        }
        if (newMerchant != t.merchant) dao.save(MerchantAlias(merchantKey(t.merchant), merchantKey(newMerchant)))
        val filed = t.copy(merchant = newMerchant, categoryId = c.categoryId, funWeek = c.week?.week,
            budgetMonth = c.week?.budgetMonth ?: date.month(), status = status, note = note ?: t.note)
        dao.update(filed)
        if (status == "auto_filed") notifyFiled(ctx, filed, c) else cancel(ctx, id)
    }

    /** Undo an auto-file: reverse its learning and ask again. */
    suspend fun undo(ctx: Context, id: Long) {
        val dao = AppDb.get(ctx).dao()
        val t = dao.txn(id) ?: return
        if (t.categoryId != null && t.status != "needs_review")
            withModel(ctx) { it.unlearn(t.merchant, t.amountCents, LocalDate.parse(t.date), t.categoryId) }
        val back = t.copy(categoryId = null, funWeek = null, budgetMonth = t.date.substring(0, 7), status = "needs_review")
        dao.update(back)
        notifyAsk(ctx, back, Choice.list(t.choices))
    }

    suspend fun delete(ctx: Context, id: Long) {
        val dao = AppDb.get(ctx).dao()
        val t = dao.txn(id) ?: return
        if (t.categoryId != null && t.status != "needs_review")
            withModel(ctx) { it.unlearn(t.merchant, t.amountCents, LocalDate.parse(t.date), t.categoryId) }
        dao.delete(t); cancel(ctx, id)
    }

    suspend fun importHistory(ctx: Context, csv: String): Int {
        val dao = AppDb.get(ctx).dao()
        val rows = historyFromCsv(csv, categories(ctx))
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
        if (dao.seenAlert(pkg, text, postedAt - 10 * 60_000) > 0) return  // banks re-post/update the same alert
        val p = parseAlert(pkg, title, text)
        val id = p?.let { ingest(ctx, it.merchant, it.cents, LocalDate.now(), account, "$title | $text", "alert", it.note) }
        dao.insert(RawAlert(pkg = pkg, title = title, text = text, postedAt = postedAt, txnId = id))
    }

    // ---------- notifications ----------

    private fun nm(ctx: Context): NotificationManager {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("ask", "Categorize purchases", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel("filed", "Auto-filed purchases", NotificationManager.IMPORTANCE_LOW))
        return nm
    }

    private fun cancel(ctx: Context, id: Long) = nm(ctx).cancel(id.toInt())

    private fun open(ctx: Context, id: Long) = PendingIntent.getActivity(ctx, id.toInt(),
        Intent(ctx, MainActivity::class.java).putExtra("txn", id).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    // Unique data URI per button so Android doesn't merge the PendingIntents.
    private fun action(ctx: Context, id: Long, what: String) = PendingIntent.getBroadcast(ctx, 0,
        Intent(ctx, ActionReceiver::class.java).setData(Uri.parse("budgetme://$id/$what")),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun base(ctx: Context, t: Txn, channel: String) = Notification.Builder(ctx, channel)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("${money(t.amountCents)} · ${t.merchant}")
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
