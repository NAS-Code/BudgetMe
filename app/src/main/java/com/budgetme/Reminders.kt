package com.budgetme

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.work.*
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth

// Monday recap and bill reminders: one daily WorkManager job, each notification sent at most once.

/** An unpaid Monthly bill with a due day: its due date this month. */
data class DueBill(val line: Line, val due: LocalDate)

/** Unpaid bills of a month that have a due day, soonest first. dueDay: day of month by bill name, null = not set. */
fun dueBills(lines: List<Line>, month: YearMonth, dueDay: (String) -> Int?): List<DueBill> =
    lines.filter { it.section == "fixed" && !it.deleted && !isComputed(it) && it.actualCents == null }
        .mapNotNull { l -> dueDay(l.name)?.let { d -> DueBill(l, month.atDay(d.coerceIn(1, month.lengthOfMonth()))) } }
        .sortedBy { it.due }

/** "due today", "due in 3 days", "due Nov 15", "2 days overdue". */
fun dueLabel(due: LocalDate, today: LocalDate): String = when (val n = java.time.temporal.ChronoUnit.DAYS.between(today, due)) {
    0L -> "due today"
    1L -> "due tomorrow"
    in 2..6 -> "due in $n days"
    in Long.MIN_VALUE..-1 -> "${-n} day${if (n == -1L) "" else "s"} overdue"
    else -> "due ${due.format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))}"
}

object Reminders {
    const val BILL_SOON_DAYS = 3

    private fun key(name: String) = "bill_due_" + name.trim().lowercase()
    fun dueDay(ctx: Context, name: String) = Brain.prefs(ctx).getInt(key(name), 0).takeIf { it in 1..31 }
    /** Due days live in prefs, which Compose can't observe; the Upcoming bills card reads this to redraw after an edit. */
    val revision = androidx.compose.runtime.mutableIntStateOf(0)
    fun setDueDay(ctx: Context, name: String, day: Int?) {
        Brain.prefs(ctx).edit().apply { if (day in 1..31) putInt(key(name), day!!) else remove(key(name)) }.apply()
        revision.intValue++
    }
    fun recapOn(ctx: Context) = Brain.prefs(ctx).getBoolean("recap_on", true)
    fun billsOn(ctx: Context) = Brain.prefs(ctx).getBoolean("bill_reminders_on", true)

    /**
     * A one-time job at the next 9am; each run queues the next one (a daily periodic job drifts into the night).
     * KEEP from app opens, APPEND_OR_REPLACE from the job itself so the next run waits for this one to finish.
     */
    fun schedule(ctx: Context, fromWorker: Boolean = false) {
        val wm = WorkManager.getInstance(ctx)
        wm.cancelUniqueWork("reminders-daily")  // the periodic job earlier builds used
        val now = LocalDateTime.now()
        val next = now.toLocalDate().atTime(9, 0).let { if (it.isAfter(now)) it else it.plusDays(1) }
        wm.enqueueUniqueWork("reminders-next", if (fromWorker) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<ReminderWorker>().setInitialDelay(Duration.between(now, next)).build())
    }

    suspend fun run(ctx: Context, today: LocalDate = LocalDate.now()) {
        val prefs = Brain.prefs(ctx)
        // once a week: Monday, or Tuesday if the phone missed Monday; always about the weekend just past
        val monday = today.with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
        if (recapOn(ctx) && today.dayOfWeek <= DayOfWeek.TUESDAY && prefs.getString("recap_week", null) != monday.toString()) {
            sendRecap(ctx, monday)
            prefs.edit().putString("recap_week", monday.toString()).apply()
        }
        if (billsOn(ctx)) {
            // this month and next: a bill due on the 1st gets its warning on the 29th
            val soon = listOf(YearMonth.from(today), YearMonth.from(today).plusMonths(1)).flatMap { m ->
                Brain.startMonthLines(ctx, m.toString())  // a month's lines exist only once something has started it
                dueBills(AppDb.get(ctx).dao().lineList(m.toString()), m) { dueDay(ctx, it) }
            }.filter { !it.due.isBefore(today) && !it.due.isAfter(today.plusDays(BILL_SOON_DAYS.toLong())) }
            for (b in soon) {
                val sent = "bill_sent_${YearMonth.from(b.due)}_" + b.line.name.trim().lowercase()
                if (prefs.getBoolean(sent, false)) continue
                notify(ctx, sent.hashCode(), "${b.line.name} ${dueLabel(b.due, today)}",
                    "Estimate ${b.line.estimateCents?.let { money(it) } ?: "not set"}. Mark it paid in Month > Monthly bills.")
                prefs.edit().putBoolean(sent, true).apply()
            }
        }
    }

    /** The recap for the weekend before `monday`, now (Settings > Reminders > Send a sample recap, and the Monday job). */
    suspend fun sendRecap(ctx: Context, monday: LocalDate = LocalDate.now().with(java.time.temporal.TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))) =
        recap(ctx, monday)?.let { (title, text) -> notify(ctx, 0x5EC0, title, text) }

    /**
     * today = the Monday after the weekend. Weekend budgets: each week the weekend touched (both halves when a weekend
     * crosses into a new month), then this week's budget. Other budgets: the month so far against an even pace.
     */
    suspend fun recap(ctx: Context, today: LocalDate): Pair<String, String>? {
        val dao = AppDb.get(ctx).dao()
        funWeekMode = Brain.loadFunWeekMode(ctx)
        val budgeted = dao.categoryList().filter { !it.archived && (it.budgetCents ?: 0) > 0 }
        if (budgeted.isEmpty()) return null
        val weekend = today.minusDays(2) to today.minusDays(1)
        val parts = mutableListOf<String>()
        val title = "Weekend recap · ${dayRange(weekend)}"
        fun line(spent: Long, budget: Long) =
            "${money(spent)} of ${money(budget)}" + if (spent > budget) " (${money(spent - budget)} over)" else " (${money(budget - spent)} left)"
        for (c in budgeted.filter { it.period == "weekend_month" }) {
            suspend fun status(fw: FunWeek) = weekStatuses(YearMonth.parse(fw.budgetMonth), c.budgetCents!!,
                dao.monthList(fw.budgetMonth).filter { it.categoryId == c.id }, Brain.weekRollover(ctx), today).first { it.week == fw.week }
            val past = listOf(weekend.first, weekend.second).map { funWeekOptions(it).last() }.distinct()
            for (fw in past) parts += "${c.name}, ${monthAbbr(fw.budgetMonth)} Wk ${fw.week}: " + status(fw).let { line(it.spent, it.budget) }
            val now = funWeekOptions(today).last()  // the week leading up to the next weekend
            if (now !in past) status(now).let { w ->
                parts += "${c.name} this week (${dayRange(w.days)}): " +
                    if (w.spent > w.budget) "${money(w.spent - w.budget)} over" else "${money(w.budget - w.spent)} left of ${money(w.budget)}"
            }
        }
        val month = YearMonth.from(today)
        val monthTxns = dao.monthList(month.toString())
        val pace = today.dayOfMonth.toDouble() / month.lengthOfMonth()
        for (c in budgeted.filter { it.period != "weekend_month" }) {
            val spent = monthTxns.filter { it.categoryId == c.id }.sumOf { it.amountCents }
            val status = when {
                spent > c.budgetCents!! -> "over"
                spent > c.budgetCents * pace -> "ahead of pace"
                else -> "on pace"
            }
            parts += "${c.name}: ${money(spent)} of ${money(c.budgetCents)}, $status"
        }
        return title to parts.joinToString("\n")
    }

    private fun notify(ctx: Context, id: Int, title: String, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("reminders", "Weekly recap and bill reminders", NotificationManager.IMPORTANCE_DEFAULT))
        nm.notify(id, Notification.Builder(ctx, "reminders").setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title).setContentText(text.lineSequence().first()).setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true).build())
    }
}

class ReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        try { Reminders.run(applicationContext) } finally { Reminders.schedule(applicationContext, fromWorker = true) }
        return Result.success()
    }
}
