package com.budgetme

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Debug builds only. Data fixes sent from the computer, applied through the app's own code paths (sheet sync, model,
 * notifications), because swapping the database file isn't safe while the notification listener keeps the app running.
 * Edits and deletes must state the purchase's current amount (expect); a mismatch changes nothing.
 *
 *   adb shell am broadcast -n com.budgetme/.DebugEdits --es op delete --el id 12 --el expect 1244
 *   adb shell am broadcast -n com.budgetme/.DebugEdits --es op amount --el id 12 --el expect 1502 --el cents 1244
 *   adb shell am broadcast -n com.budgetme/.DebugEdits --es op add --es merchant "Shop" --el cents 118 --es date 2026-11-01 \
 *       --es account "Wells Fargo" --es category transportation
 * Results: adb logcat -s BudgetMeDebug
 */
class DebugEdits : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        val pending = goAsync()
        Brain.scope.launch {
            val result = runCatching { apply(app, intent) }.getOrElse { "error: ${it.message}" }
            Log.i("BudgetMeDebug", "${intent.getStringExtra("op")}: $result")
            pending.finish()
        }
    }

    private suspend fun apply(ctx: Context, i: Intent): String {
        val dao = AppDb.get(ctx).dao()
        return when (i.getStringExtra("op")) {
            "delete", "amount" -> {
                val id = i.getLongExtra("id", -1)
                val t = dao.txn(id) ?: return "no purchase #$id"
                val expect = i.getLongExtra("expect", Long.MIN_VALUE)
                if (t.amountCents != expect) return "#$id is ${t.amountCents}, expected $expect: unchanged"
                if (i.getStringExtra("op") == "delete") { Brain.delete(ctx, id); "deleted #$id" }
                else {
                    val cents = i.getLongExtra("cents", -1).takeIf { it >= 0 } ?: return "missing cents"
                    val cat = t.categoryId ?: return "#$id isn't filed: unchanged"
                    Brain.file(ctx, id, Choice(cat, t.funWeek?.let { FunWeek(t.budgetMonth, it) }), t.status, cents = cents, budgetMonth = t.budgetMonth)
                    "#$id ${t.amountCents} -> $cents"
                }
            }
            "add" -> {
                val merchant = i.getStringExtra("merchant") ?: return "missing merchant"
                val cents = i.getLongExtra("cents", -1).takeIf { it > 0 } ?: return "missing cents"
                val date = LocalDate.parse(i.getStringExtra("date") ?: return "missing date")
                val cat = i.getStringExtra("category") ?: return "missing category"
                if (Brain.categories(ctx).none { it.id == cat }) return "no category $cat"
                val id = Brain.ingest(ctx, merchant, cents, date, i.getStringExtra("account"), merchant, "manual", autoFile = false)
                    ?: return "not added (app not set up)"
                Brain.file(ctx, id, Choice(cat), "confirmed")
                "added #$id"
            }
            else -> "unknown op"
        }
    }
}
