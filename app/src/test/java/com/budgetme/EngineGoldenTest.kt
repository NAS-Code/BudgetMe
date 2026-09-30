package com.budgetme

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

// Golden cases come from engine/export_golden.py. The back-test log holds real merchants, so it lives in the
// gitignored data/ folder; these tests skip (not fail) on a checkout without it.
class EngineGoldenTest {
    private val root = File("..")
    private val cats = categoriesFromJson(File(root, "config/categories.json").readText())
    private val nameToId = cats.associate { it.name to it.id }
    private val idToName = cats.associate { it.id to it.name }
    private fun predictor() = Predictor(cats.filter { !it.archived }.map { it.spec() })

    private fun fw(w: List<FunWeek>?) = w.orEmpty().joinToString(" ") { "${it.budgetMonth}:${it.week}" }

    @Test fun funWeeksMatchPython() {
        val rows = csvRecords(javaClass.getResource("/fun_weeks.csv")!!.readText())
        assertEquals(4383, rows.size)
        for (r in rows) assertEquals(r["date"], r["options"], fw(funWeekOptions(LocalDate.parse(r["date"]))))
    }

    @Test fun specEdgeCases() {  // SPEC section 6
        assertEquals("2026-10:5", fw(funWeekOptions(LocalDate.parse("2026-10-31"))))
        assertEquals("2026-10:5", fw(funWeekOptions(LocalDate.parse("2026-11-01"))))
    }

    @Test fun merchantKeys() {
        assertEquals("bluebottlecoffee", merchantKey("Blue Bottle Coffee"))
        assertEquals("corner", merchantKey("Corner Deli"))
        assertEquals("cafe", merchantKey("Café"))
        assertEquals("barnesandnoble", merchantKey("Barnes & Noble (Union Sq)"))
    }

    @Test fun backtestMatchesPython() {
        val f = File(root, "data/golden/backtest_events.csv")
        assumeTrue("no golden log; run engine/export_golden.py", f.exists())
        val p = predictor()
        var n = 0; var top1 = 0
        for (r in csvRecords(f.readText())) {
            val date = LocalDate.parse(r["date"]); val cents = dollarsToCents(r["amount"]!!); val cat = nameToId[r["category"]]!!
            if (r["op"] == "predict") {
                val got = p.predict(r["merchant"]!!, cents, date)
                val where = "#$n ${r["merchant"]} ${r["date"]}"
                assertEquals(where, r["buttons"], got.buttons.joinToString("|") { idToName[it]!! })
                assertEquals(where, r["tier"], got.tier)
                assertEquals(where, r["confidence"]!!.toDouble(), BigDecimal(got.confidence).setScale(2, RoundingMode.HALF_EVEN).toDouble(), 0.0)
                assertEquals(where, r["fun_weeks"], fw(got.funWeeks))
                n++; if (got.buttons[0] == cat) top1++
            }
            p.learn(r["merchant"]!!, cents, date, cat)
        }
        assertEquals(540, n)
        assertEquals(0.785, top1 / n.toDouble(), 0.0005)
    }

    @Test fun seedMatchesBacktestTraining() {
        val f = File(root, "data/golden/backtest_events.csv"); val src = File(root, "data/labeled_transactions.csv")
        assumeTrue(f.exists() && src.exists())
        val golden = csvRecords(f.readText()).filter { it["op"] == "learn" }.map { Triple(it["merchant"], it["date"], nameToId[it["category"]]) }
        val seeded = historyFromCsv(src.readText(), cats).filter { it.budgetMonth < "2026-04" }.map { Triple(it.merchant, it.date, it.categoryId) }
        assertEquals(golden, seeded)
    }

    @Test fun unlearnReversesLearn() {
        val p = predictor(); val d = LocalDate.parse("2026-09-30")
        p.learn("Foodtown", 2000, d, "grocery_food")
        val before = p.predict("Foodtown", 2000, d)
        p.learn("Foodtown", 2000, d, "fun"); p.unlearn("Foodtown", 2000, d, "fun")
        assertEquals(before, p.predict("Foodtown", 2000, d))
    }
}
