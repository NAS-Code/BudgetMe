package com.budgetme

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate

// New merchants: Claude's guess, through the backend (backend/categorize.js holds the API key and the prompt template,
// the same one as engine/llm_tier.py). The phone sends its categories, example merchants and the purchase as data, so
// editing a category in the app changes what the model sees. Model and effort are set on the backend (Haiku 5.5, low).

/**
 * What the backend's prompt is built from: the categories Claude may pick (their names and the user's descriptions), and
 * merchants the user files in one category almost every time, or splits by occasion.
 */
fun promptInputs(cats: List<Category>, filed: List<Txn>, perCat: Int = 10): JSONObject {
    val predictable = cats.filter { !it.archived && it.predict != "manual_only" }
    // per merchant: how often, its usual category and how consistently (insertion order = first seen)
    data class Stat(val n: Int, val top: String, val share: Double, val name: String)
    val ids = predictable.map { it.id }.toSet()
    val stats = filed.filter { it.categoryId in ids }.groupBy { merchantKey(it.merchant) }.values.map { ts ->
        val byCat = ts.groupingBy { it.categoryId!! }.eachCount().entries.sortedByDescending { it.value }
        val name = ts.groupingBy { it.merchant }.eachCount().entries.maxBy { it.value }.key
        Stat(ts.size, byCat[0].key, byCat[0].value / ts.size.toDouble(), name)
    }
    val examples = JSONArray()
    for (c in predictable) {  // distinctive examples: merchants the user files here almost every time
        val top = stats.filter { it.top == c.id && it.share >= 0.8 && it.n >= 3 }.sortedByDescending { it.n }.take(perCat)
        if (top.isNotEmpty()) examples.put(JSONObject().put("category", c.name).put("merchants", JSONArray(top.map { it.name.take(60) })))
    }
    val split = stats.filter { it.share < 0.8 && it.n >= 8 }.sortedByDescending { it.n }.take(8)
    return JSONObject()
        .put("categories", JSONArray(predictable.take(30).map {
            JSONObject().put("name", it.name.take(60)).put("description", it.description.take(800).ifBlank { it.name })
                .put("fallback", it.predict == "fallback_only")
        }))
        .put("examples", examples)
        .put("split", JSONArray(split.map { it.name.take(60) }))
}

/** The purchase as the backend expects it (limits match backend/categorize.js). */
fun purchaseInput(merchant: String, cents: Long, date: LocalDate, account: String?, note: String?): JSONObject = JSONObject()
    .put("merchant", merchant.take(120)).put("cents", cents).put("date", date.toString())
    .apply { account?.let { put("account", it.take(60)) }; note?.takeIf { it.isNotBlank() }?.let { put("note", it.take(200)) } }

data class LlmResult(val probs: Map<String, Double>, val merchantType: String?)  // probs keyed by category name

/** Pulls the JSON object out of the reply; tolerant of stray text around it. */
fun parseLlmReply(text: String): LlmResult? {
    val s = text.indexOf('{'); val e = text.lastIndexOf('}')
    if (s < 0 || e <= s) return null
    return runCatching {
        val o = JSONObject(text.substring(s, e + 1))
        val r = o.optJSONArray("ranking")
        val probs = (0 until (r?.length() ?: 0)).associate { i -> r!!.getJSONObject(i).let { it.getString("category") to it.optDouble("probability", 0.0) } }
        LlmResult(probs, o.optString("merchant_type").ifBlank { null })
    }.getOrNull()
}

/**
 * Asks the backend (signed in with Google, like the bank sync). Null on any failure: the keyword guess stays.
 * ponytail: results cached in memory per purchase, lost on restart; persist them if repeat calls show up in billing.
 */
object Llm {
    private val cache = HashMap<String, LlmResult>()

    /** Off the main thread only. */
    fun classify(ctx: Context, prompt: JSONObject, purchase: JSONObject): LlmResult? {
        val key = purchase.toString()
        cache[key]?.let { return it }
        val reply = BankSync.call(ctx, "POST", "/categorize", JSONObject().put("prompt", prompt).put("purchase", purchase), readTimeoutMs = 25_000)
        return parseLlmReply(reply)?.also { cache[key] = it }
    }
}
