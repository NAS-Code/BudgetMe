package com.budgetme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.YearMonth

// Made-up payers and amounts only (public repo).
class GuideTest {
    private val catalog = guideCatalog(File("../config/guided_setup.json").readText())
    private val typical = mapOf("food" to setOf("mix"), "commute" to setOf("transit"), "weekends" to setOf("some"), "shopping" to setOf("sometimes"))
    private fun needs(answers: Map<String, Set<String>>) = guidedCategories(catalog, answers).map { it.second }

    private val growth = catalog.spendingVsIncome

    @Test fun atTheTypicalLevelEveryCategoryGetsItsBase() {
        val n = needs(typical)
        val b = suggestBudgets(n, n.sumOf { it.baseCents }, growth)
        for (x in n) assertEquals(x.id, x.baseCents / 500 * 500, b[x.id])
    }

    @Test fun moreMoneyGrowsWantsMoreThanNeedsAndLeavesSavings() {
        // a high earner: $20,000 a month in, $15,000 left for spending
        val n = needs(typical); val base = n.associate { it.id to it.baseCents }
        val a = allocate(2_000_000, 400_000, emptyList(), n, growth)
        fun growth(id: String) = a.budgets.getValue(id).toDouble() / base.getValue(id)
        assertTrue("groceries ${a.budgets["groceries"]}", a.budgets.getValue("groceries") in 30_000..60_000)  // not $3,700
        assertTrue(growth("dining") > 2 * growth("groceries") && growth("fun") > growth("dining"))  // wants grow faster than needs
        assertTrue(a.leftover > a.spending / 2)  // most of it is left over for savings, as high earners do (BLS)
        assertTrue(a.budgets.values.all { it % 500 == 0L })
    }

    @Test fun lessMoneyFitsAndCutsWantsBeforeNeeds() {
        val n = needs(typical)
        val tight = n.sumOf { it.baseCents } / 2
        val b = suggestBudgets(n, tight, growth)
        assertTrue(b.values.sum() <= tight)
        assertTrue(b.values.sum() > tight - 500 * n.size)  // uses nearly all of it
        val base = n.associate { it.id to it.baseCents }
        assertTrue(b.getValue("groceries").toDouble() / base.getValue("groceries") > b.getValue("shopping").toDouble() / base.getValue("shopping"))
        assertEquals(n.associate { it.id to 0L }, suggestBudgets(n, 0, growth))
        // even at growth 1 (spending in step with income) and elasticities over 1, the total still fits
        assertTrue(suggestBudgets(n, 10 * n.sumOf { it.baseCents }).values.sum() <= 10 * n.sumOf { it.baseCents })
    }

    @Test fun householdScalesTheBases() {
        val me = needs(typical + ("household" to setOf("me"))).associate { it.id to it.baseCents }
        val two = needs(typical + ("household" to setOf("partner"))).associate { it.id to it.baseCents }
        val family = guidedCategories(catalog, typical + ("household" to setOf("family")))
        assertEquals(Math.round(me.getValue("groceries") * 1.8), two["groceries"])
        assertEquals(Math.round(me.getValue("transportation") * 1.8), two["transportation"])  // "*"
        val fam = family.associate { it.first.id to it.second.baseCents }
        assertEquals(Math.round(me.getValue("groceries") * 2.6), fam["groceries"])
        assertTrue(family.any { it.first.id == "kids" })
    }

    @Test fun billsThenGoalsThenCategories() {
        val goals = listOf(Goal("savings", "S", 600_000, 0, 12))  // $500 a month
        val a = allocate(400_000, 150_000, goals, needs(typical))
        assertEquals(listOf(50_000L), a.goalMonthly); assertEquals(50_000, a.goals); assertEquals(200_000, a.spending); assertEquals(0, a.short)
        assertEquals(a.spending - a.budgets.values.sum(), a.leftover)
    }

    @Test fun goalsThatDontFitAreCutAndTheShortfallReported() {
        val a = allocate(300_000, 250_000, listOf(Goal("savings", "S", 1_200_000, 0, 12)), needs(typical))
        assertEquals(50_000, a.goals); assertEquals(0, a.spending); assertEquals(50_000, a.short)
        assertTrue(a.budgets.values.all { it == 0L })
    }

    @Test fun goalsWithoutADateShareTenPercentOfWhatsLeftAfterBills() {
        val goals = listOf(Goal("emergency", "E", 500_000, 100_000, 0), Goal("purchase", "Trip", 30_000, 0, 0), Goal("debt", "D", 120_000, 0, 6))
        // $3,000 after bills -> $300 for open goals, $150 each; the $300 trip still needs only $300, the debt $200/mo on its date
        assertEquals(listOf(15_000L, 15_000L, 20_000L), goalMonthly(goals, 300_000))
        assertEquals(27, monthsToGoal(goals[0], 15_000))
        assertNull(monthsToGoal(goals[0], 0))
        assertEquals(listOf(5_000L), goalMonthly(listOf(Goal("purchase", "Gift", 5_000, 0, 0)), 300_000))  // capped at what's left to save
    }

    @Test fun goalsBecomeCategoriesBudgetedAtTheirMonthlyAmount() {
        val goals = listOf(Goal("emergency", "Emergency fund", 504_000, 100_000, 12), Goal("purchase", "New laptop", 180_000, 0, 0))
        val cats = goalCategories(catalog, goals, goalMonthly(goals, 300_000), emptyList())
        assertEquals(listOf("goal_emergency", "goal_purchase"), cats.map { it.id })
        assertEquals(listOf(33_700L, 30_000L), cats.map { it.budgetCents })  // $336.67 rounds up to $337; open goal $300
        assertTrue(cats.all { it.predict == "manual_only" && it.period == "month" && it.description.isNotBlank() })
        assertEquals(listOf(catalog.categories.size, catalog.categories.size + 1), cats.map { it.sort })
        assertTrue(cats.none { c -> catalog.categories.any { it.id == c.id } })
    }

    @Test fun aTripGoalReplacesTravel() {
        val answers = typical + ("regular" to setOf("travel"))
        val picked = guidedCategories(catalog, answers).map { it.first }
        val travel = picked.first { it.id == "travel" }
        val goals = listOf(Goal("trip", "Beach trip", 180_000, 0, 6))
        assertEquals(setOf("travel"), absorbedBy(catalog, goals))
        val trip = goalCategories(catalog, goals, goalMonthly(goals, 300_000), picked).single()
        assertEquals("goal_trip", trip.id); assertEquals(30_000L, trip.budgetCents)
        assertEquals("auto", trip.predict)  // trip purchases get suggested, like Travel's
        assertEquals(travel.keywords, trip.keywords)
        assertTrue(trip.description.endsWith(travel.description))
        assertTrue(absorbedBy(catalog, listOf(Goal("purchase", "Laptop", 100_000, 0, 6))).isEmpty())
    }

    @Test fun goalMonthlyRoundsUp() {
        assertEquals(33334, Goal("savings", "S", 100000, 0, 3).monthlyCents)
        assertEquals(0, Goal("savings", "S", 100000, 150000, 3).monthlyCents)
        assertEquals(0, Goal("savings", "S", 100000, 0, 0).monthlyCents)
    }

    @Test fun incomeAveragesPaydaysAndIrregularIncome() {
        val from = YearMonth.of(2027, 1)
        assertEquals(400000, monthlyIncome(PaySchedule.SEMIMONTHLY, null, 200000, 0, from))
        // every two weeks from Fri Jan 8, 2027: 26 paydays in 2027 -> 26/12 of a check a month
        assertEquals(26 * 150000L / 12, monthlyIncome(PaySchedule.BIWEEKLY, LocalDate.of(2027, 1, 8), 150000, 0, from))
        // from Jan 1 it's a 27-payday year
        assertEquals(27 * 150000L / 12, monthlyIncome(PaySchedule.BIWEEKLY, LocalDate.of(2027, 1, 1), 150000, 0, from))
        assertEquals(100000, monthlyIncome(null, null, 999999, 1200000, from))
    }

    @Test fun answersPickCategoriesAndWriteDescriptions() {
        val cats = guidedCategories(catalog, mapOf("food" to setOf("out"), "weekends" to setOf("big"), "regular" to setOf("pets", "travel"),
            "shopping" to setOf("rarely")))
        assertEquals(listOf("groceries", "dining", "fun", "pets", "travel", "miscellaneous"), cats.map { it.first.id })
        val fun_ = cats.first { it.first.id == "fun" }.first
        assertEquals("weekend_month", fun_.period)
        assertTrue(fun_.description.contains("Weekend"))
        assertTrue(cats.first { it.first.id == "dining" }.first.description.contains("Most everyday meals"))
        assertEquals("fallback_only", cats.last().first.predict)
        assertTrue(cats.all { it.second.baseCents > 0 && it.first.description.isNotBlank() && !it.first.archived })
        assertEquals(listOf("miscellaneous"), guidedCategories(catalog, emptyMap()).map { it.first.id })  // no answers: the catch-all
    }

    @Test fun everyAnswerPointsAtACatalogCategory() {
        val known = catalog.categories.map { it.id }.toSet()
        for (q in listOfNotNull(catalog.household) + catalog.questions) for (o in q.options) for (a in o.adds) assertTrue("${q.id}/${o.id}", a.category in known)
        for (o in catalog.household!!.options) for (k in o.scale.keys) assertTrue(k == "*" || k in known)
    }

    @Test fun linesForTheMonth() {
        val l = guideLines("2027-01", 200000, 30000, listOf("Rent" to 150000L, "Phone" to 0L, " " to 5000L))
        assertEquals(listOf("Paycheck 1", "Other income", "Rent"), l.map { it.name })
        assertEquals(listOf(true, false), l.filter { it.section == "income" }.map { it.inSurplus })
        // a twice-a-month schedule gets Paycheck 2 with the same estimate; Other income isn't mistaken for a paycheck
        val (add, remove) = paycheckChanges(l, "2027-01", 2)
        assertEquals(listOf("Paycheck 2" to 200000L), add.map { it.name to it.estimateCents }); assertTrue(remove.isEmpty())
    }

    // ---------- pay from bank deposits ----------

    private var n = 0
    private fun dep(date: LocalDate, cents: Long, raw: String, category: String? = "INCOME", account: String = "Test Bank") =
        FeedTxn("f${n++}", account, -cents, date, cleanMerchant(raw), raw, false, category)
    private val today = LocalDate.of(2027, 3, 31)

    @Test fun findsABiweeklyPaycheckSplitAcrossAccounts() {
        val start = LocalDate.of(2026, 7, 10)  // a Friday
        val feed = (0 until 19).flatMap { i ->
            val d = start.plusWeeks(2L * i)
            listOf(dep(d, 180_000, "ACME CORP PAYROLL PPD"), dep(d, 20_000, "ACME CORP PAYROLL PPD", account = "Test Bank Savings"))
        } + dep(LocalDate.of(2026, 9, 3), 5_000, "VENMO CASHOUT", category = "TRANSFER_IN") +
            dep(LocalDate.of(2026, 11, 20), 120_000, "FREELANCE CLIENT LLC") +
            FeedTxn("buy", "Test Bank", 4_500, LocalDate.of(2026, 8, 1), "Maple Cafe", "MAPLE CAFE", false, "FOOD_AND_DRINK")
        val g = detectIncome(feed, today)!!
        assertEquals(PaySchedule.BIWEEKLY, g.schedule)
        assertEquals(200_000, g.paycheckCents)  // both accounts' parts
        assertEquals(start.plusWeeks(36), g.lastPayday)
        assertEquals(19, g.paychecks)
        assertEquals(120_000L * 365 / 264, g.otherYearCents)  // the freelance deposit, scaled to a year; not the Venmo cash-out
    }

    @Test fun tellsTwiceAMonthFromEveryTwoWeeks() {
        val days = (0 until 8).flatMap { m -> val ym = YearMonth.of(2026, 8).plusMonths(m.toLong()); listOf(ym.atDay(1), ym.atDay(15)) }
        val g = detectIncome(days.map { dep(it, 150_000, "Big Employer Dir Dep", category = null) }, today)!!
        assertEquals(PaySchedule.SEMIMONTHLY, g.schedule)
        assertEquals(150_000, g.paycheckCents)
    }

    @Test fun monthlyWeeklyAndNoPaycheck() {
        val monthly = (0 until 6).map { dep(LocalDate.of(2026, 10, 30).plusMonths(it.toLong()), 400_000, "STATE UNIV SALARY") }
        assertEquals(PaySchedule.MONTHLY, detectIncome(monthly, today)!!.schedule)
        val weekly = (0 until 10).map { dep(LocalDate.of(2027, 1, 7).plusWeeks(it.toLong()), 60_000, "DINER PAYROLL") }
        assertEquals(PaySchedule.WEEKLY, detectIncome(weekly, today)!!.schedule)
        // only transfers: no pay found
        assertNull(detectIncome(listOf(dep(today, 10_000, "ONLINE TRANSFER FROM SAVINGS", category = "TRANSFER_IN")), today))
        assertNull(detectIncome(emptyList(), today))
    }
}
