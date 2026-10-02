package com.budgetme

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.TextBlockParam
import org.json.JSONObject
import java.time.Duration
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

// Port of engine/llm_tier.py: the prompt is generated from the user's categories and history, so editing a
// category in the app changes what the model sees.

const val LLM_MODEL = "claude-haiku-4-5"  // SPEC section 7: fast and cheap, one call per unrecognized merchant

private const val SYSTEM_TEMPLATE = """You categorize one personal purchase at a time for a budgeting app. The user will see your top choices as buttons on a phone notification and tap one, so a good ranking matters more than a single answer.

The categories are the user's own, defined in their words below. Follow their definitions, not general conventions. For example, if they file restaurants under "Fun", do that, even though many budgets would call it "Food".

<categories>
{categories}
</categories>

<how_this_user_files_things>
Merchants this user has filed most often in each category. Use them to calibrate what each category means to this person.
{examples}
</how_this_user_files_things>

How to decide:
1. First work out what kind of business the merchant is. Bank descriptors are often truncated or prefixed by payment processors (SQ *, TST*, SP *, PAYPAL *, etc.). Strip those and use your knowledge of real businesses.
2. Then apply the category definitions, including any timing or amount rules in them (weekday vs. weekend, dollar thresholds).
3. {fallback_rule}
4. If the purchase is a person-to-person payment (Venmo, Zelle, Cash App) and a note is given, the note describes what was bought; categorize by the note.

Respond with only a JSON object, no other text:
{"merchant_type": "<a few words: what this business is>",
 "ranking": [{"category": "<exact category name>", "probability": <0-1>}, ...],
 "reason": "<one short sentence>"}
Rank every category listed above that is eligible. Probabilities should sum to about 1."""

fun buildSystemPrompt(cats: List<Category>, filed: List<Txn>, perCat: Int = 10): String {
    val predictable = cats.filter { !it.archived && it.predict != "manual_only" }
    val lines = predictable.joinToString("\n") {
        "- ${it.name}${if (it.predict == "fallback_only") " (fallback: only when nothing else fits)" else ""}: ${it.description}"
    }
    // per merchant: how often, its usual category and how consistently (insertion order = first seen)
    data class Stat(val n: Int, val top: String, val share: Double, val name: String)
    val ids = predictable.map { it.id }.toSet()
    val stats = filed.filter { it.categoryId in ids }.groupBy { merchantKey(it.merchant) }.values.map { ts ->
        val byCat = ts.groupingBy { it.categoryId!! }.eachCount().entries.sortedByDescending { it.value }
        val name = ts.groupingBy { it.merchant }.eachCount().entries.maxBy { it.value }.key
        Stat(ts.size, byCat[0].key, byCat[0].value / ts.size.toDouble(), name)
    }
    val ex = mutableListOf<String>()
    for (c in predictable) {  // distinctive examples: merchants the user files here almost every time
        val top = stats.filter { it.top == c.id && it.share >= 0.8 && it.n >= 3 }.sortedByDescending { it.n }.take(perCat)
        if (top.isNotEmpty()) ex.add("- ${c.name}: " + top.joinToString(", ") { it.name })
    }
    val split = stats.filter { it.share < 0.8 && it.n >= 8 }.sortedByDescending { it.n }.take(8)
    if (split.isNotEmpty()) ex.add("- Split across categories depending on the occasion: " + split.joinToString(", ") { it.name })
    if (ex.isEmpty()) ex.add("(No history yet. Rely on the category definitions.)")
    val fallback = predictable.firstOrNull { it.predict == "fallback_only" }?.name
    val fbRule = if (fallback != null) "\"$fallback\" is the user's bucket for things that don't fit elsewhere, such as subscriptions, services, " +
        "appointments, and one-off purchases. Rank it first only when no other category plausibly fits, but always include it in the ranking."
    else "Always rank every eligible category."
    return SYSTEM_TEMPLATE.replace("{categories}", lines).replace("{examples}", ex.joinToString("\n")).replace("{fallback_rule}", fbRule)
}

fun buildUserMessage(merchant: String, cents: Long, date: LocalDate, account: String?, note: String?): String = listOfNotNull(
    "Merchant: $merchant",
    if (cents == 0L) "Amount: unknown" else "Amount: $" + "%.2f".format(Math.abs(cents) / 100.0) + if (cents < 0) " (refund)" else "",
    "Date: $date (${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)})",
    account?.let { "Account: $it" },
    note?.let { "Payment note: $it" },
).joinToString("\n")

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
 * Calls Claude directly from the phone with the user's own key (SPEC open decision 1: fine for a personal app;
 * a shared app needs a small backend so users don't bring keys).
 * ponytail: results cached in memory per message, lost on restart; persist them if repeat calls show up in billing.
 */
object Llm {
    private var client: Pair<String, AnthropicClient>? = null
    private val cache = HashMap<String, LlmResult>()

    private fun client(key: String) = client?.takeIf { it.first == key }?.second
        ?: AnthropicOkHttpClient.builder().apiKey(key).timeout(Duration.ofSeconds(15)).maxRetries(1).build().also { client = key to it }

    fun classify(key: String, system: String, user: String): LlmResult? {
        cache[user]?.let { return it }
        val params = MessageCreateParams.builder()
            .model(LLM_MODEL)
            .maxTokens(300L)
            .temperature(0.0)
            .systemOfTextBlockParams(listOf(TextBlockParam.builder().text(system)
                .cacheControl(CacheControlEphemeral.builder().build()).build()))
            .addUserMessage(user)
            .build()
        val reply = client(key).messages().create(params).content()
            .flatMap { b -> b.text().map { listOf(it.text()) }.orElse(emptyList()) }.joinToString("")
        return parseLlmReply(reply)?.also { cache[user] = it }
    }
}
