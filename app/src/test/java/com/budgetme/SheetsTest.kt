package com.budgetme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// A made-up October tab in the tracker's layout (fake merchants). Dates are spreadsheet serials: 46301 = 2026-10-06.
class SheetsTest {
    private val cats = categoriesFromJson(File("../config/categories.json").readText()).filter { !it.archived }
    private val tab = parseTab("OCtober 2026", "2026-10", listOf(
        listOf("", "VARIABLE EXPENSES"),
        listOf("", "Week 1 Fun", "DATE", "", "Credit Card", "WellsFargo"),
        listOf("#7", "Corner Bar", 46301, "", 24.5, ""),
        listOf("", "Venmo (pizza)", 46302, "", "", 12.0),
        listOf(),
        listOf("", "", "", "TOTAL", 200, "=SUM(F3:F5)"),
        listOf("", "Grocery/Food", "DATE", "", "Credit Card", "WellsFargo"),
        listOf("", "Subway", "", "", "", ""),  // template placeholder: no amount
        listOf("", "", "", "TOTAL", 500, "=SUM(F8:F8)"),
        listOf("", "MISCELLANEOUS EXPENSES", "DATE", "", "Credit Card", "WellsFargo"),
        listOf(),
        listOf("", "", "", "TOTAL"),
    ), cats)

    private fun txn(id: Long, cat: String?, cents: Long, date: String, merchant: String, hash: String? = null, week: Int? = null,
                    status: String = "confirmed", source: String = "alert") =
        Txn(id = id, source = source, account = CARD_ACCOUNT, rawDescriptor = merchant, merchant = merchant, amountCents = cents,
            date = date, budgetMonth = "2026-10", categoryId = cat, funWeek = week, status = status, sheetHash = hash)

    @Test fun parsesSectionsRowsAndColumns() {
        assertEquals(listOf("fun" to 1, "grocery_food" to null, "miscellaneous" to null), tab.sections.map { it.categoryId to it.week })
        val (bar, venmo, sub) = tab.rows
        assertEquals(7L, bar.appId); assertEquals(2450L, bar.cents); assertEquals(CARD_ACCOUNT, bar.account); assertEquals("2026-10-06", bar.date.toString())
        assertEquals(1200L, venmo.cents); assertEquals(BANK_ACCOUNT, venmo.account)
        assertEquals(null, sub.cents)
        assertEquals(listOf(5), tab.sections[0].free); assertEquals(emptyList<Int>(), tab.sections[1].free); assertEquals(listOf(11), tab.sections[2].free)
        assertEquals("Venmo" to "pizza", splitDescription("Venmo (pizza)"))
    }

    @Test fun reconcilesBothDirections() {
        val bar = tab.rows[0]
        val agreed = rowState(bar)
        val cases = mapOf(
            "in sync" to txn(7, "fun", 2450, "2026-10-06", "Corner Bar", agreed, 1),
            "app recategorized" to txn(7, "miscellaneous", 2450, "2026-10-06", "Corner Bar", agreed),
            "app deleted" to null,
            "sheet edited" to txn(7, "fun", 2000, "2026-10-06", "Corner Bar", agreed.replace("2450", "2000"), 1),
        )
        val p0 = planSync(listOf(tab), mapOf(7L to cases["in sync"]!!), setOf("fun", "grocery_food", "miscellaneous"), setOf("2026-10"))
        assertTrue(p0.write.isEmpty() && p0.clear.isEmpty() && p0.fromSheet.isEmpty())
        assertEquals("Venmo (pizza)", p0.import.single().description)  // typed in the sheet, no id yet

        val p1 = planSync(listOf(tab), mapOf(7L to cases["app recategorized"]!!), setOf("fun", "miscellaneous"), setOf("2026-10"))
        assertEquals(listOf(bar), p1.clear); assertEquals(7L, p1.push.single().id)  // moved to the Misc section

        val p2 = planSync(listOf(tab), emptyMap(), setOf("fun"), setOf("2026-10"), maxId = 7)
        assertEquals(listOf(bar), p2.clear)
        // new phone / older backup: id 7 was never issued here, so the row is imported, not blanked
        val p2b = planSync(listOf(tab), emptyMap(), setOf("fun"), setOf("2026-10"), maxId = 6)
        assertTrue(p2b.clear.isEmpty()); assertEquals(listOf(bar.copy(appId = null)), p2b.import.filter { it.row == bar.row })

        val p3 = planSync(listOf(tab), mapOf(7L to cases["sheet edited"]!!), setOf("fun"), setOf("2026-10"))
        assertEquals(7L, p3.fromSheet.single().second.id)  // the sheet's amount wins
    }

    @Test fun linksInsteadOfDuplicatingAndDetectsSheetDeletes() {
        val caught = txn(20, "fun", 1200, "2026-10-08", "Venmo")       // caught by an alert, also typed into the sheet
        val newOne = txn(21, "grocery_food", 899, "2026-10-09", "Foodtown")
        val oldHistory = txn(22, "grocery_food", 500, "2026-10-01", "Deli", source = "history")
        val goneFromSheet = txn(23, "fun", 3000, "2026-10-03", "Arcade", hash = "x", week = 1)
        val p = planSync(listOf(tab), listOf(caught, newOne, oldHistory, goneFromSheet).associateBy { it.id },
            setOf("fun", "grocery_food", "miscellaneous"), setOf("2026-10"))
        assertEquals(20L, p.fromSheet.single { it.first.appId == null }.second.id)
        assertTrue(p.import.isEmpty())
        assertEquals(listOf(21L), p.push.map { it.id })           // history is never written back
        assertEquals(listOf(23L), p.delete.map { it.id })
        assertTrue(Sheets.idFrom("https://docs.google.com/spreadsheets/d/AbCdEfGhIjKlMnOpQrStUvWxYz0123/edit#gid=1") == "AbCdEfGhIjKlMnOpQrStUvWxYz0123")
    }

    @Test fun sameOrderTwiceInOneDayStaysTwoPurchases() {
        fun rows(n: Int) = List(n) { listOf("", "Corner Burger", 46316, "", "", 14.5) }  // 2026-10-21
        fun sheet(n: Int) = parseTab("October 2026", "2026-10", listOf(listOf("", "Grocery/Food", "DATE", "", "Credit Card", "WellsFargo")) +
            rows(n) + listOf(listOf(), listOf("", "", "", "TOTAL")), cats)
        fun alerts(n: Int) = (1..n).associate { it.toLong() to txn(it.toLong(), "grocery_food", 1450, "2026-10-21", "Corner Burger") }
        val g = setOf("grocery_food"); val w = setOf("2026-10")
        planSync(listOf(sheet(2)), alerts(2), g, w).let { assertEquals(2, it.fromSheet.map { p -> p.second.id }.toSet().size); assertTrue(it.import.isEmpty() && it.push.isEmpty()) }
        planSync(listOf(sheet(2)), alerts(1), g, w).let { assertEquals(1, it.fromSheet.size); assertEquals(1, it.import.size) }
        planSync(listOf(sheet(1)), alerts(2), g, w).let { assertEquals(1, it.fromSheet.size); assertEquals(1, it.push.size) }
    }

    @Test fun alertsMergeOneToOneIntoSheetPurchases() {
        fun t(id: Long, cents: Long, date: String) = txn(id, "grocery_food", cents, date, "Corner Burger")
        val sheet = listOf(t(1, 1450, "2026-10-21"), t(2, 1450, "2026-10-21"), t(3, 900, "2026-10-05"))
        // two identical orders typed in, three alerts: two merge, the third (a real third order) stays
        assertEquals(listOf(10L to 1L, 11L to 2L), absorbPairs(listOf(t(10, 1450, "2026-10-21"), t(11, 1450, "2026-10-21"), t(12, 1450, "2026-10-21")), sheet, emptySet()))
        // a sheet purchase that already has its alert doesn't take another; amount-less alerts never match
        assertEquals(emptyList<Pair<Long, Long>>(), absorbPairs(listOf(t(13, 900, "2026-10-06"), t(14, 0, "2026-10-05")), sheet, setOf(3L)))
        assertEquals(listOf(13L to 3L), absorbPairs(listOf(t(13, 900, "2026-10-06")), sheet, emptySet()))
    }

    @Test fun undatedSheetRowsAreNeverMatchedAutomatically() {
        val undated = txn(1, "grocery_food", 550, "2026-10-15", "Corner Cafe")
        assertEquals(emptyList<Pair<Long, Long>>(), absorbPairs(listOf(txn(10, "grocery_food", 550, "2026-10-14", "Corner Cafe")), listOf(undated), emptySet()))
    }
}
