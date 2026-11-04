package com.budgetme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth

// Replays of real situations (Nov 2026) with made-up merchants and amounts (public repo). Every case checks the rule the
// screen depends on: the listed differences add up to the whole gap, so nothing is hidden.
class ReconcileTest {
    private val nov = YearMonth.of(2026, 11)
    private val today = LocalDate.of(2026, 11, 10)

    private fun line(id: Long, section: String, name: String, actual: Long?, onCard: Boolean = false) =
        Line(id, "2026-11", section, name, null, actual, id.toInt(), null, false, onCard)
    private fun buy(id: Long, cents: Long, date: String, account: String = BANK_ACCOUNT, merchant: String = "Maple Cafe",
                    feedId: String? = null, paid: Boolean = false, month: String = "2026-11") =
        Txn(id = id, source = "alert", account = account, rawDescriptor = merchant, merchant = merchant, amountCents = cents,
            date = date, budgetMonth = month, categoryId = "fun", status = "confirmed", feedId = feedId, cardPaid = paid)
    private fun bank(id: String, cents: Long, date: String, raw: String = "MAPLE CAFE", pending: Boolean = false) =
        FeedTxn(id, BANK_ACCOUNT, cents, LocalDate.parse(date), cleanMerchant(raw), raw, pending, null)

    private val carry = line(1, "income", "Extra Leftover", 100000)
    private val pay = line(2, "income", "Paycheck 1st", 200000)
    private val rent = line(3, "fixed", "Rent", 150000)
    private val usual = listOf(bank("p1", -200000, "2026-11-01", "ACME CORP PAYROLL"), bank("r1", 150000, "2026-11-02", "OAK PROPERTY MGMT"))

    /** The bank's balance now, from its balance on the 1st and the month's transactions. */
    private fun now(start: Long, feed: List<FeedTxn>) = start - feed.filter { !it.date.isBefore(nov.atDay(1)) }.sumOf { it.cents }

    private fun run(start: Long, feed: List<FeedTxn>, filed: List<Txn>, lines: List<Line> = listOf(carry, pay, rent),
                    unfiled: List<Txn> = emptyList()): Reconciliation {
        val b = balance(lines, filed, unfiled, null)
        val r = reconcile(nov, now(start, feed), feed, b, filed, unfiled, emptyList(), lines, today)
        assertEquals("differences add up to the gap", r.gap, r.diffs.sumOf { it.cents })
        assertTrue("nothing unexplained: ${r.diffs}", r.diffs.none { it.kind == DiffKind.UNEXPLAINED })
        return r
    }

    @Test fun matchingMonthHasNothingToShow() {
        val feed = usual + bank("f1", 2000, "2026-11-03") + bank("c1", 3000, "2026-11-05", "CHASE CREDIT CRD EPAY 261105")
        val r = run(100000, feed, listOf(buy(10, 2000, "2026-11-03", feedId = "f1"),
            buy(11, 3000, "2026-11-04", account = CARD_ACCOUNT, merchant = "Birch Books", paid = true)))
        assertEquals(0L, r.gap); assertEquals(emptyList<Diff>(), r.items); assertEquals(0L, r.betweenMonths)
    }

    @Test fun cancelledAndRechargedPurchase() {  // the bank cancelled a charge and billed it again as two smaller ones
        val feed = usual + bank("s1", 6000, "2026-11-04", "PINE OUTFITTERS") + bank("s2", 9000, "2026-11-04", "PINE OUTFITTERS")
        val stale = buy(10, 15000, "2026-11-04", merchant = "Pine Outfitters", feedId = "gone")
        val r = run(100000, feed, listOf(stale))
        assertEquals(0L, r.gap)  // same total, so the balance is right even though the pieces differ
        assertEquals(setOf(DiffKind.APP_ONLY, DiffKind.BANK_ONLY), r.items.map { it.kind }.toSet())
        // with the fix in place (the purchase follows one charge, the other is added) everything matches
        val fixed = run(100000, feed, listOf(stale.copy(amountCents = 6000, feedId = "s1"), buy(11, 9000, "2026-11-04", merchant = "Pine Outfitters", feedId = "s2")))
        assertEquals(emptyList<Diff>(), fixed.items)
    }

    @Test fun cardPaymentForLastMonthsChargesIsTiming() {
        // $300.00 paid on Nov 4: $120.00 of October charges (counted in October) and $180.00 of November ones
        val feed = usual + bank("c1", 30000, "2026-11-04", "CHASE CREDIT CRD EPAY 261103", pending = true)
        val novCharges = buy(10, 18000, "2026-11-01", account = CARD_ACCOUNT, merchant = "Birch Books", paid = true)
        // the bank still had the $120 on Nov 1; BudgetMe's October already spent it, so the carry-over is $120 lower
        val r = run(100000 + 12000, feed, listOf(novCharges))
        assertEquals(0L, r.gap); assertEquals(0L, r.betweenMonths); assertEquals(emptyList<Diff>(), r.items)
        assertTrue(r.diffs.any { it.kind == DiffKind.CARD_TIMING && it.cents == -12000L })
        // the carry-over not caught up yet (it was today's bug): the between-months net shows exactly that
        val stale = run(100000 + 12000, feed, listOf(novCharges), lines = listOf(carry.copy(actualCents = 100000 + 12000), pay, rent))
        assertEquals(-12000L, stale.gap); assertEquals(-12000L, stale.betweenMonths)
    }

    @Test fun transferTheAppDoesntTrack() {
        val feed = usual + bank("t1", 15000, "2026-11-06", "ONLINE TRANSFER TO SAVINGS XXXXXX0000")
        val r = run(100000, feed, emptyList())
        assertEquals(-15000L, r.gap)
        val d = r.items.single(); assertEquals(DiffKind.BANK_ONLY, d.kind); assertEquals("t1", d.feed?.id)
    }

    @Test fun purchaseCountedTowardTheNextMonth() {  // a Saturday Oct 31 Fun purchase filed under November's Week 1
        val feed = usual + bank("o1", 4139, "2026-10-31", "HAZEL BAR")
        val r = run(100000 - 4139, feed, listOf(buy(10, 4139, "2026-10-31", merchant = "Hazel Bar")))
        assertEquals(0L, r.gap); assertEquals(0L, r.betweenMonths); assertEquals(emptyList<Diff>(), r.items)
        assertTrue(r.diffs.any { it.kind == DiffKind.OTHER_MONTH })
    }

    @Test fun purchaseNotInTheBankYet() {
        val r = run(100000, usual, listOf(buy(10, 1200, "2026-11-09", merchant = "Elm Grocery")))
        val d = r.items.single()
        assertEquals(DiffKind.APP_ONLY, d.kind); assertEquals(1200L, d.cents); assertTrue(d.label.contains("hasn't reached"))
    }

    @Test fun billAndIncomeMatchTheBankByAmount() {
        val missingRent = run(100000, listOf(usual[0]), emptyList())  // rent typed in as paid, the bank hasn't taken it
        assertEquals(rent, missingRent.items.single().line)
        val noPaycheckLine = run(100000, usual, emptyList(), lines = listOf(carry, rent))
        assertEquals(200000L, noPaycheckLine.items.single().cents)  // $2,000 in the bank, no income line for it: the bank has more
    }

    @Test fun bankPaymentSuggestsTheBillItPays() {
        val gym = Line(4, "2026-11", "fixed", "Gym", 9700, null, 4, null, false, false)       // estimate, not marked paid yet
        val feed = usual + bank("g1", 9717, "2026-11-02", "FIT CLUB 112")                     // within 10% of the estimate
        val r = run(100000, feed, emptyList(), lines = listOf(carry, pay, rent, gym))
        assertEquals(gym, r.items.single().line)
    }

    @Test fun endOfMonthPaycheckCountedTowardThisMonth() {  // paid Oct 30, filed as November's paycheck
        val early = listOf(bank("p1", -200000, "2026-10-30", "ACME CORP PAYROLL"), usual[1])
        // the bank's Nov 1 balance already had the paycheck; BudgetMe's carry-over didn't (it counts it in November)
        val r = run(100000 + 200000, early, emptyList())
        assertEquals(0L, r.gap); assertEquals(0L, r.betweenMonths); assertEquals(emptyList<Diff>(), r.items)
        assertTrue(r.diffs.any { it.kind == DiffKind.OTHER_MONTH && it.line == pay })
    }

    @Test fun pendingChargeAndItsReversalCancel() {  // Plaid still lists the cancelled charge and its reversal as pending
        val feed = usual + bank("x1", 15000, "2026-11-04", "PINE OUTFITTERS 5550100 NY", pending = true) +
            bank("x2", -15000, "2026-11-04", "REVERSAL PINE OUTFITTERS 5550100 NY", pending = true)
        val r = run(100000, feed, emptyList())
        assertEquals(0L, r.gap); assertEquals(emptyList<Diff>(), r.items)
    }
}
