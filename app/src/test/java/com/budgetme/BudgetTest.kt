package com.budgetme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BudgetTest {
    private val cats = categoriesFromJson(File("../config/categories.json").readText()).filter { !it.archived }

    private fun t(id: Long, cents: Long, account: String, paid: Boolean = false, date: String = "2026-10-0${id % 9 + 1}") =
        Txn(id = id, source = "alert", account = account, rawDescriptor = "", merchant = "M$id", amountCents = cents, date = date,
            budgetMonth = "2026-10", categoryId = "fun", status = "confirmed", cardPaid = paid)
    private fun line(id: Long, section: String, name: String, est: Long?, act: Long?, hash: String? = null, deleted: Boolean = false, onCard: Boolean = false) =
        Line(id, "2026-10", section, name, est, act, id.toInt(), hash, deleted, onCard)

    @Test fun balanceIsTheTrackersM14() {
        val lines = listOf(line(1, "income", "Paycheck 1st", 270000, 268000), line(2, "income", "Extra Leftover", null, 50000),
            line(3, "fixed", "Rent", 180000, 180000), line(4, "fixed", "Internet", 5000, null), line(5, "fixed", "Spotify", 1299, 1299, onCard = true))
        val filed = listOf(t(1, 2000, BANK_ACCOUNT), t(2, 3000, CARD_ACCOUNT), t(3, 4000, CARD_ACCOUNT, paid = true))
        val b = balance(lines, filed, unfiled = listOf(t(4, 500, BANK_ACCOUNT), t(5, 900, CARD_ACCOUNT)), prevBalance = 999)
        assertEquals(50000L, b.carryIn)                       // the carry-over line beats the computed previous balance
        assertEquals(50000L + 268000 - 180000 - 2000 - 4000 - 500, b.total)   // unpaid card charge and unfiled card alert excluded
        assertEquals(b.surplus - 3000 - 1299 - 500, b.surplusWithCard)  // card (incl. the bill on card) and Inbox counted here
        assertEquals(999L, balance(lines.filter { it.id != 2L }, filed, emptyList(), 999).carryIn)
    }

    @Test fun paymentPicksOldestFirstOrSkipsOneLargeCharge() {
        val unpaid = cardItems(listOf(t(1, 1000, CARD_ACCOUNT, date = "2026-09-01"), t(2, 50000, CARD_ACCOUNT, date = "2026-09-05"),
            t(3, 2000, CARD_ACCOUNT, date = "2026-09-09"), t(4, 700, CARD_ACCOUNT, date = "2026-10-02")),
            listOf(line(9, "fixed", "Spotify", 1299, 1299, onCard = true)))   // a bill on the card, dated the 1st of its month
        assertEquals(setOf("t1", "t2", "t3"), pickPayment(unpaid, 53000))       // the full statement
        assertEquals(setOf("t1", "t3"), pickPayment(unpaid, 3000))              // paid around the big one
        assertEquals(setOf("t1"), pickPayment(unpaid, 1500))                    // no exact match: oldest while it fits
        assertEquals(setOf("t1", "t2", "t3", "l9"), pickPayment(unpaid, 54299))
    }

    @Test fun linesSyncBothWays() {
        fun row(r: Int, name: String, est: Long?, act: Long?, id: Long? = null) = SheetLine("October 2026", "2026-10", "fixed", r, name, est, act, id)
        val agreed = lineState("Rent", 180000, null, false)
        // app filled in the actual; the sheet didn't change: write it
        planLines(listOf(row(25, "Rent", 180000, null, 1)), listOf(line(1, "fixed", "Rent", 180000, 180000, agreed)), setOf("2026-10")).let {
            assertEquals(1L, it.write.single().second.id)
        }
        // the sheet filled it in: the sheet wins
        planLines(listOf(row(25, "Rent", 180000, 180000, 1)), listOf(line(1, "fixed", "Rent", 180000, null, agreed)), setOf("2026-10")).let {
            assertEquals(1L, it.fromSheet.single().second!!.id)
        }
        // first sync: an app line links to the sheet row with the same name; an unknown row is imported; an app-only line is pushed
        planLines(listOf(row(25, "rent", 180000, null), row(26, "Gym", 10000, null)),
            listOf(line(1, "fixed", "Rent", 180000, null), line(2, "fixed", "Spotify", 1299, null)), setOf("2026-10")).let {
            assertEquals(listOf(1L, null), it.fromSheet.map { p -> p.second?.id })
            assertEquals(listOf(2L), it.push.map { l -> l.id })
        }
        // deleted in the app: clear the row; gone from the sheet: remove the line
        planLines(listOf(row(25, "Rent", 180000, null, 1)), listOf(line(1, "fixed", "Rent", 180000, null, agreed, deleted = true),
            line(2, "fixed", "Gym", 10000, null, "x")), setOf("2026-10")).let {
            assertEquals(listOf(25), it.clear.map { r -> r.row }); assertEquals(setOf(1L, 2L), it.remove.map { l -> l.id }.toSet())
        }
    }

    @Test fun readsIncomeMonthlyAndSumRanges() {
        val values = listOf(
            listOf("", "INCOME"),
            listOf("", "DESCRIPTION", "DATE", "", "ESTIMATE", "ACTUAL"),
            listOf("", "Paycheck 1st", "", "", 2700, 2680.5),
            listOf("", "Extra Leftover", "", "", 0, 500),
            listOf(),
            listOf("", "", "", "TOTAL", 2700, 3180.5),
            listOf("", "MONTHLY EXPENSES"),
            listOf("", "DESCRIPTION", "DATE", "", "ESTIMATE", "WellsFargo"),
            listOf(),
            listOf("#L4", "Rent", "", "", 1800, ""),
            listOf(),
            listOf(),
            listOf("", "", "", "TOTAL"),
            listOf("", "Week 1 Fun", "DATE", "", "Credit Card", "WellsFargo"),
            listOf("", "Corner Bar", "", "", 24.5, ""),
            listOf("", "Arcade", "", "", "", 10),
            listOf(),
            listOf("", "", "", "TOTAL", 200),
        )
        val formulas = List(values.size) { i -> listOf(null, null, null, null, null, when (i + 1) { 13 -> "=SUM(F10:F11)"; 18 -> "=SUM(F15:F17)"; else -> null }) }
        val tab = parseTab("October 2026", "2026-10", values, cats, formulas)
        assertEquals(listOf("income" to "Paycheck 1st", "income" to "Extra Leftover", "fixed" to "Rent"), tab.lines.map { it.section to it.name })
        assertEquals(268050L, tab.lines[0].actual); assertEquals(4L, tab.lines[2].lineId)
        assertEquals(listOf(11), tab.lineSections[1].free)   // rows 9 and 12 are outside the TOTAL's SUM(F10:F11)
        assertEquals(listOf(17), tab.sections.single().free)
        val (bar, arcade) = tab.rows
        assertTrue(bar.onCard && !arcade.onCard)
        // an app card charge the user moved to the bank column comes back as paid off
        val charge = Txn(id = 9, source = "alert", account = CARD_ACCOUNT, rawDescriptor = "", merchant = "Arcade", amountCents = 1000,
            date = "2026-10-15", budgetMonth = "2026-10", categoryId = "fun", funWeek = 1, status = "confirmed")
        assertTrue(Sheets.txnFromRow(arcade, charge).cardPaid)
    }

    @Test fun readsTheOctober2026Layout() {
        // Estimate column added: E Estimate, F Credit Card, G WellsFargo (Income: E ESTIMATE, G ACTUAL)
        val values = listOf(
            listOf("App ID"),
            listOf("", "INCOME"),
            listOf("", "DESCRIPTION", "DATE", "", "ESTIMATE", "", "ACTUAL"),
            listOf("#L1", "Paycheck 1st", "", "", 3100.00, "", 3050.50),
            listOf("", "", "", "TOTAL", 3100.00, "", 3050.50),
            listOf("", "Transportation", "DATE", "", "Estimate", "Credit Card", "WellsFargo"),
            listOf("", "Subway", "", "", 150),                       // a planned amount, not a charge
            listOf("#5", "Citi Bike", "", "", "", 1.47),              // on the card
            listOf("#6", "Citi Bike", 46310, "", "", 2.94, 2.94),     // paid off, copied to WellsFargo
            listOf("", "Train", "", "", "", "", 10.25),               // from Wells Fargo
            listOf(),
            listOf("", "", "", "TOTAL", 150, 4.41, 13.19),
        )
        val formulas = List(values.size) { i -> if (i + 1 == 12) listOf(null, null, null, null, null, "=SUM(F7:F11)", "=SUM(G7:G11)") else emptyList() }
        val tab = parseTab("October 2026", "2026-10", values, cats, formulas)
        assertEquals(305050L, tab.lines.single().actual)
        assertEquals(310000L, tab.lines.single().estimate)
        val (sub, onCard, paid, train) = tab.rows
        assertEquals(null, sub.cents)                                 // the estimate is never read as a purchase
        assertTrue(onCard.onCard && onCard.cents == 147L)
        assertTrue(!paid.onCard && paid.paidCard && paid.cents == 294L)
        assertTrue(!train.onCard && !train.paidCard && train.account == BANK_ACCOUNT)
        assertEquals(Cols(2, 4, 5, 6), tab.sections.single().cols)
        assertEquals(listOf(11), tab.sections.single().free)
        assertTrue(Sheets.txnFromRow(paid).cardPaid)
    }

    @Test fun sectionsWithoutABankColumnAreLeftAlone() {
        val tab = parseTab("October 2026", "2026-10", listOf(listOf("", "Transportation", "DATE", "", "Estimate", "Credit Card", "Bank"),
            listOf("#5", "Citi Bike", "", "", "", 1.47), listOf("", "", "", "TOTAL")), cats)
        assertTrue(tab.sections.isEmpty() && tab.rows.isEmpty())
        assertEquals(listOf("Transportation"), tab.skipped)
    }

    @Test fun monthlyBillsOnTheCardWorkLikePurchases() {
        val values = listOf(
            listOf("", "MONTHLY EXPENSES"),
            listOf("", "DESCRIPTION", "DATE", "", "ESTIMATE", "Credit Card", "WellsFargo"),
            listOf("#L1", "Spotify", "", "", 12.99, 12.99, ""),   // on the card
            listOf("#L2", "Rent", "", "", 1800, "", 1800),         // paid from Wells Fargo
            listOf("", "", "", "TOTAL"),
        )
        val (spotify, rent) = parseTab("October 2026", "2026-10", values, cats).lines
        assertTrue(spotify.onCard && spotify.actual == 1299L)
        assertTrue(!rent.onCard && rent.actual == 180000L)
        // the app paying it off is an app-side change: the row gets rewritten (moved to WellsFargo)
        val l = line(1, "fixed", "Spotify", 1299, 1299, hash = lineState(spotify), onCard = false)
        assertEquals(1L, planLines(listOf(spotify), listOf(l), setOf("2026-10")).write.single().second.id)
    }

    @Test fun totalSurplusIsTheTrackersN47() {
        // N47: paychecks (+ Venmo) minus bills and budgeted categories paid from Wells Fargo; Work Expenses and one-offs left out
        val lines = listOf(line(1, "income", "Paycheck 1st", 310000, 305050).copy(inSurplus = true),
            line(2, "income", "Paycheck 2nd", 300000, 305050).copy(inSurplus = true),
            line(3, "income", "Other", null, 7500), line(4, "income", "Extra Leftover", null, 42000),
            line(5, "fixed", "Rent", 180000, 180000), line(6, "fixed", "Spotify", 1299, 1299, onCard = true))
        val work = t(7, 4000, BANK_ACCOUNT).copy(categoryId = "work_expenses")
        val filed = listOf(t(1, 2000, BANK_ACCOUNT), t(2, 3000, CARD_ACCOUNT), work)
        val b = balance(lines, filed, emptyList(), null, budgeted = setOf("fun", "grocery_food"))
        assertEquals(305050L * 2 - 180000 - 2000, b.surplus)
        assertEquals(b.surplus - 3000 - 1299, b.surplusWithCard)
        assertEquals(42000L + 305050 * 2 + 7500 - 180000 - 2000 - 4000, b.total)   // the bank balance still counts everything
        assertTrue(defaultInSurplus("income", "Paycheck 1st", 310000) && !defaultInSurplus("income", "Other", 0) &&
            !defaultInSurplus("income", "Monthly Surplus", -13116))
    }
}
