package com.budgetme

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth

/** What a widget shows for one category. Weekend-budget categories (Fun) show the current week; others the month. */
data class WidgetData(val title: String, val left: Long, val spent: Long, val budget: Long, val inbox: Int)

suspend fun widgetData(ctx: Context, catId: String?, today: LocalDate = LocalDate.now()): WidgetData? {
    val dao = AppDb.get(ctx).dao()
    funWeekMode = Brain.loadFunWeekMode(ctx)
    val budgeted = dao.categoryList().filter { !it.archived && (it.budgetCents ?: 0) > 0 }
    // no choice yet (or it was archived): the first weekend-budget category, else the first with a budget
    val c = budgeted.firstOrNull { it.id == catId } ?: budgeted.firstOrNull { it.period == "weekend_month" } ?: budgeted.firstOrNull() ?: return null
    val inbox = dao.inboxCount()
    if (c.period == "weekend_month") {
        val fw = funWeekOptions(today).last()  // the week today is in or leads up to
        val m = YearMonth.parse(fw.budgetMonth)
        val txns = dao.monthList(fw.budgetMonth).filter { it.categoryId == c.id }
        val w = weekStatuses(m, c.budgetCents!!, txns, Brain.weekRollover(ctx), today).first { it.week == fw.week }
        return WidgetData("${c.name} · ${monthAbbr(fw.budgetMonth)} Wk ${w.week}", w.budget - w.spent, w.spent, w.budget, inbox)
    }
    val month = YearMonth.from(today).toString()
    val spent = dao.monthList(month).filter { it.categoryId == c.id }.sumOf { it.amountCents }
    return WidgetData("${c.name} · ${monthAbbr(month)}", c.budgetCents!! - spent, spent, c.budgetCents, inbox)
}

class BudgetWidget : AppWidgetProvider() {
    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()  // keep the process alive until the widget is drawn
        Brain.scope.launch { try { Widgets.refreshNow(ctx) } finally { pending.finish() } }
    }
    override fun onDeleted(ctx: Context, ids: IntArray) =
        Brain.prefs(ctx).edit().apply { ids.forEach { remove("widget_cat_$it") } }.apply()
}

object Widgets {
    private var watching = false

    /** Redraw every widget. Cheap: a few queries per widget. */
    fun refresh(ctx: Context) { Brain.scope.launch { refreshNow(ctx) } }

    suspend fun refreshNow(ctx: Context) {
        val app = ctx.applicationContext
        val mgr = AppWidgetManager.getInstance(app)
        for (id in mgr.getAppWidgetIds(ComponentName(app, BudgetWidget::class.java)))
            mgr.updateAppWidget(id, views(app, id, widgetData(app, Brain.prefs(app).getString("widget_cat_$id", null))))
    }

    /** Keep widgets current while the app's process is alive (the alert listener keeps it alive). */
    fun watch(ctx: Context) {
        if (watching) return
        watching = true
        val dao = AppDb.get(ctx).dao()
        Brain.scope.launch { merge(dao.txnTick(), dao.categories()).collect { refresh(ctx) } }
    }

    private fun views(ctx: Context, id: Int, d: WidgetData?) = RemoteViews(ctx.packageName, R.layout.widget_budget).apply {
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        setOnClickPendingIntent(R.id.widget_root, open)
        // tap the category name to pick a different budget (one request code per widget, so each opens its own picker)
        val pick = PendingIntent.getActivity(ctx, id, Intent(ctx, WidgetConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        setOnClickPendingIntent(R.id.w_title, pick)
        if (d == null) {
            setTextViewText(R.id.w_title, "BudgetMe")
            setTextViewText(R.id.w_amount, "—")
            setTextViewText(R.id.w_sub, "Give a category a budget to show it here")
            return@apply
        }
        val over = d.left < 0
        setTextViewText(R.id.w_title, d.title)
        setTextViewText(R.id.w_amount, money(Math.abs(d.left)))
        // a color resource, not a resolved color, so it follows the launcher into dark mode
        setColorStateList(R.id.w_amount, "setTextColor", if (over) R.color.widget_over else R.color.widget_accent)
        setTextViewText(R.id.w_sub, if (over) "over a ${money(d.budget)} budget" else "left of ${money(d.budget)}")
        setProgressBar(R.id.w_bar, 1000, if (d.budget > 0) (d.spent * 1000 / d.budget).toInt().coerceIn(0, 1000) else 0, false)
        setTextViewText(R.id.w_inbox, if (d.inbox == 0) "Inbox clear" else "${d.inbox} to review")
    }
}

/** Long-press > settings on the widget (or adding it): pick which budget it shows. */
class WidgetConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) return finish()
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
        val prefs = Brain.prefs(this)
        setContent {
            BudgetTheme {
                Surface {
                    val cats by remember { AppDb.get(this).dao().categories() }.collectAsState(initial = emptyList())
                    val choices = cats.filter { !it.archived && (it.budgetCents ?: 0) > 0 }
                    var picked by remember { mutableStateOf(prefs.getString("widget_cat_$id", null)) }
                    Column(Modifier.fillMaxSize().systemBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Show on the widget", style = MaterialTheme.typography.headlineSmall)
                        Text("Weekend budgets like Fun show the current week; others show the month.",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (choices.isEmpty()) Text("No category has a budget yet. Set one in Categories.")
                        for (c in choices) Radio("${c.name} · ${money(c.budgetCents!!)}${if (c.period == "weekend_month") " by weekend" else " a month"}",
                            (picked ?: choices.firstOrNull { it.period == "weekend_month" }?.id ?: choices.first().id) == c.id) { picked = c.id }
                        Button(enabled = choices.isNotEmpty(), onClick = {
                            prefs.edit().putString("widget_cat_$id", picked ?: choices.firstOrNull { it.period == "weekend_month" }?.id ?: choices.first().id).apply()
                            Widgets.refresh(this@WidgetConfigActivity)
                            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
                            finish()
                        }) { Text("Done") }
                    }
                }
            }
        }
    }
}
