package com.budgetme

import java.text.Normalizer
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

// Kotlin port of engine/predictor.py. Behavior must stay identical: EngineGoldenTest replays the Python
// back-test and compares every button. Change predictor.py first, re-export golden cases, then port.

/** What the predictor needs to know about a category. Comes from the Category table, never hardcoded. */
data class CategorySpec(
    val id: String,
    val predict: String,                    // auto | fallback_only | manual_only
    val weekendBudget: Boolean = false,     // budget.period == weekend_month (Fun week rule)
    val keywords: List<String> = emptyList(),
    val requireKeyword: Boolean = false,    // unknown merchants: only suggest it on a keyword hit
)

data class FunWeek(val budgetMonth: String, val week: Int)  // budgetMonth = "YYYY-MM"

data class Prediction(
    val buttons: List<String>,
    val confidence: Double,                 // unrounded; Python rounds to 2 places
    val tier: String,
    val funWeeks: List<FunWeek>?,           // null unless a weekend-budget category is among the buttons
)

private val GENERIC = setOf("deli", "market", "bodega", "bagel", "bagels", "grocery", "cafe", "restaurant", "inc", "nyc", "the", "pharmacy")

fun merchantKey(name: String): String {
    var s = Normalizer.normalize(name.lowercase(), Normalizer.Form.NFKD).replace(Regex("\\p{M}"), "")
    s = s.replace(Regex("\\(.*?\\)"), " ").replace("&", " and ").replace(Regex("[^a-z0-9 ]"), " ")
    val w = s.split(' ').filter { it.isNotEmpty() }
    val k = (w.filter { it !in GENERIC }.ifEmpty { w }).joinToString("")
    return if (k.length > 4 && k.endsWith("s")) k.dropLast(1) else k
}

private fun weekday(d: LocalDate) = d.dayOfWeek.value - 1  // Python's weekday(): Mon=0 .. Sun=6

fun amountBucket(cents: Long): String {
    val a = Math.abs(cents)
    return if (a < 1500) "lt15" else if (a < 4000) "lt40" else if (a < 10000) "lt100" else "ge100"
}

fun dayType(d: LocalDate) = if (weekday(d) >= 4) "wkend" else "wkday"  // Fri behaves like Sat/Sun in history

// ---------- Fun week rule (SPEC section 6) ----------

/** (saturday, sunday) pairs owned by a month: a weekend belongs to the month its Saturday is in. */
fun weekends(ym: YearMonth): List<Pair<LocalDate, LocalDate>> =
    (1..ym.lengthOfMonth()).map { ym.atDay(it) }.filter { weekday(it) == 5 }.map { it to it.plusDays(1) }

/** One entry = unambiguous, two = offer both (earlier week first). */
fun funWeekOptions(d: LocalDate): List<FunWeek> {
    data class Cand(val wk: FunWeek, val sat: LocalDate, val sun: LocalDate)
    val ym = YearMonth.from(d)
    val cands = listOf(ym.minusMonths(1), ym, ym.plusMonths(1)).flatMap { m ->
        weekends(m).mapIndexed { i, (sat, sun) -> Cand(FunWeek(m.toString(), i + 1), sat, sun) }
    }.sortedBy { it.sat }
    val idx = cands.indexOfFirst { !d.isAfter(it.sun) }  // the week ending on the next weekend (or this one)
    val default = cands[idx]
    val prev = cands.getOrNull(idx - 1)
    if (weekday(d) >= 5 || prev == null) return listOf(default.wk)
    val afterLast = prev.wk.budgetMonth != default.wk.budgetMonth         // past the last weekend of prev's month
    val justAfter = ChronoUnit.DAYS.between(prev.sun, d) <= 3            // Mon-Wed right after a weekend
    return if (afterLast || justAfter) listOf(prev.wk, default.wk) else listOf(default.wk)
}

// ---------- difflib.get_close_matches(key, keys, n=1, cutoff) ----------

/** SequenceMatcher(None, a, b).ratio(). No junk: autojunk only kicks in at 200+ chars, keys are short. */
fun seqRatio(a: String, b: String): Double {
    if (a.isEmpty() && b.isEmpty()) return 1.0
    val b2j = HashMap<Char, MutableList<Int>>()
    b.forEachIndexed { j, c -> b2j.getOrPut(c) { mutableListOf() }.add(j) }
    fun longest(alo: Int, ahi: Int, blo: Int, bhi: Int): IntArray {
        var bi = alo; var bj = blo; var bk = 0
        var j2len = HashMap<Int, Int>()
        for (i in alo until ahi) {
            val next = HashMap<Int, Int>()
            for (j in b2j[a[i]].orEmpty()) {
                if (j < blo) continue
                if (j >= bhi) break
                val k = (j2len[j - 1] ?: 0) + 1
                next[j] = k
                if (k > bk) { bi = i - k + 1; bj = j - k + 1; bk = k }
            }
            j2len = next
        }
        return intArrayOf(bi, bj, bk)
    }
    var matches = 0
    val queue = ArrayDeque(listOf(intArrayOf(0, a.length, 0, b.length)))
    while (queue.isNotEmpty()) {
        val (alo, ahi, blo, bhi) = queue.removeLast()
        val (i, j, k) = longest(alo, ahi, blo, bhi)
        if (k > 0) {
            matches += k
            if (alo < i && blo < j) queue.addLast(intArrayOf(alo, i, blo, j))
            if (i + k < ahi && j + k < bhi) queue.addLast(intArrayOf(i + k, ahi, j + k, bhi))
        }
    }
    return 2.0 * matches / (a.length + b.length)
}

fun closeMatch(word: String, possibilities: Collection<String>, cutoff: Double): String? {
    var best: String? = null; var bestScore = -1.0
    for (x in possibilities) {
        if (2.0 * minOf(x.length, word.length) / (x.length + word.length) < cutoff) continue  // real_quick_ratio
        val r = seqRatio(x, word)
        // heapq.nlargest on (score, x): ties go to the larger string
        if (r >= cutoff && (r > bestScore || (r == bestScore && x > best!!))) { best = x; bestScore = r }
    }
    return best
}

// ---------- model ----------

/** Not thread-safe; callers go through Brain, which serializes access. */
class Predictor(cats: List<CategorySpec>, private val halfLife: Double = 365.0, private val priorWeight: Double = 2.0) {
    private val auto = cats.filter { it.predict != "manual_only" }.map { it.id }  // order breaks ties
    private val manual = cats.filter { it.predict == "manual_only" }.map { it.id }.toSet()
    private val fallback = cats.firstOrNull { it.predict == "fallback_only" }?.id
    private val weekendCats = cats.filter { it.weekendBudget }.map { it.id }.toSet()
    private val requireKw = cats.filter { it.requireKeyword }.map { it.id }.toSet()
    private val keywords = cats.filter { it.keywords.isNotEmpty() }.associate { it.id to it.keywords }

    private val hist = LinkedHashMap<String, MutableList<Pair<LocalDate, String>>>()  // insertion order matters (ties)
    private val ctx = HashMap<String, LinkedHashMap<String, Double>>()
    val aliases = HashMap<String, String>()  // bank descriptor key -> merchant key (learned from taps)

    fun key(merchant: String) = merchantKey(merchant).let { aliases[it] ?: it }

    fun learn(merchant: String, cents: Long, date: LocalDate, category: String) {
        hist.getOrPut(key(merchant)) { mutableListOf() }.add(date to category)
        val c = ctx.getOrPut(dayType(date) + amountBucket(cents)) { LinkedHashMap() }
        c[category] = (c[category] ?: 0.0) + 1
    }

    /** Reverses learn() exactly (Undo on an auto-filed transaction). */
    fun unlearn(merchant: String, cents: Long, date: LocalDate, category: String) {
        val k = key(merchant)
        hist[k]?.let { h -> h.lastIndexOf(date to category).takeIf { it >= 0 }?.let { h.removeAt(it) }; if (h.isEmpty()) hist.remove(k) }
        ctx[dayType(date) + amountBucket(cents)]?.let { c -> c[category]?.let { c[category] = it - 1 } }
    }

    private fun prior(cents: Long, date: LocalDate): Map<String, Double> {
        val c = ctx[dayType(date) + amountBucket(cents)].orEmpty()
        val tot = c.values.sum().takeIf { it != 0.0 } ?: 1.0
        return auto.associateWith { ((c[it] ?: 0.0) + 1) / (tot + auto.size) }
    }

    private fun lookup(key: String): Pair<String, String>? {
        if (key in hist) return key to "history"
        if (key.length >= 4) {
            closeMatch(key, hist.keys, 0.85)?.let { return it to "history (close match)" }
            hist.keys.filter { it.length >= 5 && (key.startsWith(it) || it.startsWith(key)) }
                .maxByOrNull { hist[it]!!.size }?.let { return it to "history (close match)" }
        }
        return null
    }

    private fun hasWord(text: String, w: String): Boolean {  // re.search(r'\b' + w + r'\b')
        fun isWord(c: Char) = c.isLetterOrDigit() || c == '_'
        var i = text.indexOf(w)
        while (i >= 0) {
            val end = i + w.length
            if ((i == 0 || !isWord(text[i - 1])) && (end == text.length || !isWord(text[end]))) return true
            i = text.indexOf(w, i + 1)
        }
        return false
    }

    fun predict(merchant: String, cents: Long, date: LocalDate, n: Int = 3): Prediction {
        val prior = prior(cents, date)
        val found = lookup(key(merchant))
        val low = merchant.lowercase()
        val kw = keywords.filter { (_, words) -> words.any { hasWord(low, it) } }.keys
        val scores = LinkedHashMap<String, Double>()
        fun add(c: String, v: Double) { scores[c] = (scores[c] ?: 0.0) + v }
        val tier: String
        if (found != null) {
            tier = found.second
            val w = if (tier == "history") 1.0 else 0.5  // a close spelling might be a different place
            for ((d, cat) in hist[found.first]!!) add(cat, w * Math.pow(0.5, maxOf(ChronoUnit.DAYS.between(d, date), 0L) / halfLife))
            val seen = scores.values.sum()
            for (c in auto) add(c, priorWeight * prior[c]!! * (if (seen < 5) 1.0 else 0.5))  // Tue lunch != Sat night
            if (seen < 5) for (c in kw) add(c, 1.0)  // thin history: let the name speak too
        } else {
            for (c in auto) scores[c] = prior[c]!! + (if (c in kw) 1.0 else 0.0)
            for (c in requireKw) if (c !in kw && c in scores) scores[c] = scores[c]!! * 0.1  // don't guess transit blind
            // the fallback category is the "not sure" bucket: always the last of 3 buttons for unknown merchants
            val top2 = auto.filter { it != fallback }.sortedByDescending { scores[it]!! }.take(2)
            if (fallback != null && top2.size == 2) {
                val s2 = scores[top2[1]]!!
                scores[fallback] = minOf(s2 - 1e-6, maxOf(scores[fallback]!!, s2 * 0.99))
            }
            tier = if (kw.isNotEmpty()) "keywords" else "context only (LLM in app)"
        }
        val conf = scores.values.max() / scores.values.sum()
        val ranked = scores.entries.sortedByDescending { it.value }.map { it.key }
            .filter { it !in manual || found != null }.take(n)
        return Prediction(ranked, conf, tier, if (ranked.any { it in weekendCats }) funWeekOptions(date) else null)
    }
}
