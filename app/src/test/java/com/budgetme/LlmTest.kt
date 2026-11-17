package com.budgetme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

class LlmTest {
    private val starter = categoriesFromJson(File("../config/starter_categories.json").readText())
    private val d = LocalDate.parse("2026-10-06")  // a Tuesday

    @Test fun parsesReplyWithStrayText() {
        val r = parseLlmReply("Sure!\n{\"merchant_type\": \"coffee shop\", \"ranking\": [{\"category\": \"Dining Out\", \"probability\": 0.9}," +
            " {\"category\": \"Groceries\", \"probability\": 0.1}], \"reason\": \"cafe\"}")!!
        assertEquals("coffee shop", r.merchantType)
        assertEquals(0.9, r.probs["Dining Out"]!!, 1e-9)
        assertNull(parseLlmReply("no json here"))
    }

    @Test fun blendFollowsLlmOnEmptyHistory() {
        val p = Predictor(starter.map { it.spec() }).blendLlm(mapOf("dining" to 0.8, "groceries" to 0.15, "miscellaneous" to 0.05), 645, d, "llm")
        assertEquals(listOf("dining", "groceries", "miscellaneous"), p.buttons)
        // no history: prior is 1/7 per category (predictor.py's "tot or 1"), so score = 0.85 * p + 0.15 / 7
        val top = 0.85 * 0.8 + 0.15 / 7
        assertEquals(top / (0.85 * 1.0 + 0.15 * 6 / 7), p.confidence, 1e-9)
    }

    @Test fun promptListsCategoriesAndLearnedExamples() {
        val filed = List(3) { Txn(source = "manual", account = null, rawDescriptor = "", merchant = "Corner Coffee", amountCents = 500,
            date = "2026-10-0${it + 1}", budgetMonth = "2026-10", categoryId = "dining", status = "confirmed") }
        val p = promptInputs(starter, filed)
        val cats = p.getJSONArray("categories").let { a -> (0 until a.length()).map { a.getJSONObject(it) } }
        assertEquals(6, cats.size)
        assertTrue(cats.single { it.getString("name") == "Miscellaneous" }.getBoolean("fallback"))
        val ex = p.getJSONArray("examples").getJSONObject(0)
        assertEquals("Dining Out" to "Corner Coffee", ex.getString("category") to ex.getJSONArray("merchants").getString(0))
        assertEquals(0, promptInputs(starter, emptyList()).getJSONArray("examples").length())  // the backend says "No history yet"
    }

    @Test fun purchaseMatchesTheBackendsLimits() {
        val j = purchaseInput("SQ *" + "X".repeat(200), -500, d, "Chase Credit Card", "")
        assertEquals(120, j.getString("merchant").length)
        assertEquals(-500L, j.getLong("cents")); assertEquals("2026-10-06", j.getString("date"))
        assertTrue(!j.has("note"))  // blank notes aren't sent
    }

    @Test fun starterSetIsUsable() {
        assertEquals(6, starter.count { !it.archived })
        assertEquals("miscellaneous", starter.single { it.predict == "fallback_only" }.id)
        assertEquals(listOf("dining"), Predictor(starter.map { it.spec() }).predict("Joe's Pizza", 1800, d).buttons.take(1))
    }
}
