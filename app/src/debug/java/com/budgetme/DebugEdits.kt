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
 *   adb shell am broadcast -n com.budgetme/.DebugEdits --es op delete --el id 12 --el expect 1000
 *   adb shell am broadcast -n com.budgetme/.DebugEdits --es op amount --el id 12 --el expect 1200 --el cents 1000
 *   adb shell am broadcast -n com.budgetme/.DebugEdits --es op add --es merchant "Shop" --el cents 250 --es date 2026-11-01 \
 *       --es account "Wells Fargo" --es category transportation
 *   adb shell am broadcast -n com.budgetme/.DebugEdits --es op sync     (a bank sync now, like Settings > Sync now)
 *   adb shell am broadcast -n com.budgetme/.DebugEdits --es op check    (Plaid re-checks the banks, then a sync, like Check the bank now)
 *   adb shell am broadcast -n com.budgetme/.DebugEdits --es op sheet    (a sheet sync now, like Settings > Google Sheet > Sync now)
 *   adb shell am broadcast -n com.budgetme/.DebugEdits --es op diff     (this month's "what's different", one line per item)
 *   adb shell am broadcast -n com.budgetme/.DebugEdits --es op undopay --el key 1700000000000   (a recorded payment's Undo)
 *   adb shell am broadcast -n com.budgetme/.DebugEdits --es op pay --el cents 25000   (Pay card with its pre-ticked charges)
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
            "sync" -> BankSync.sync(ctx)
            "check" -> BankSync.checkBank(ctx)  // like Settings > Check the bank now
            "sheet" -> Sheets.sync(ctx, Sheets.Mode.MANUAL)
            "diff" -> {
                val ym = java.time.YearMonth.now(); val m = ym.toString()
                val (feed, available) = BankSync.monthFeed(ctx, ym)
                val r = reconcile(ym, available ?: return "no balance", feed, Brain.monthBalance(ctx, m) ?: return "no month",
                    dao.monthList(m), dao.unfiledBankList(m), dao.depositsIn(m), dao.lineList(m), LocalDate.now())
                (listOf("bank ${r.bankNow} app ${r.appNow} gap ${r.gap} betweenMonths ${r.betweenMonths} feed ${feed.size}") +
                    r.diffs.map { "${it.kind} ${it.cents} ${it.label}" }).joinToString(" | ")
            }
            "undopay" -> { BankSync.undoCardPayment(ctx, i.getLongExtra("key", -1)); "undone" }
            "pay" -> {
                val cents = i.getLongExtra("cents", -1).takeIf { it > 0 } ?: return "missing cents"
                BankSync.applyPayment(ctx, cents); "recorded $cents"
            }
            else -> "unknown op"
        }
    }
}
