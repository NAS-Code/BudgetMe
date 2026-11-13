package com.budgetme

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.pow

// Guided first-run setup: bank, income, bills, how you spend, goals, then a suggested budget per category.
// The questions and the categories they create live in config/guided_setup.json; this file only does the math and the screens.

// ---------- catalog (config/guided_setup.json) ----------

/** base: a typical monthly amount for one person, in cents. */
data class GuideAdd(val category: String, val say: String, val baseCents: Long, val period: String?)
/** scale: household multipliers per category id ("*" for the rest). */
data class GuideOption(val id: String, val label: String, val adds: List<GuideAdd>, val scale: Map<String, Double> = emptyMap())
data class GuideQuestion(val id: String, val text: String, val multi: Boolean, val options: List<GuideOption>)
data class GuideCat(val id: String, val name: String, val predict: String, val keywords: List<String>, val requireKeyword: Boolean, val elasticity: Double)
/** spendingVsIncome: how fast spending grows with the money available (BLS: about half as fast as income). */
data class GuideCatalog(val bills: List<String>, val household: GuideQuestion?, val categories: List<GuideCat>, val always: List<GuideAdd>,
                        val questions: List<GuideQuestion>, val spendingVsIncome: Double = 1.0, val goals: List<GoalKind> = emptyList())

/**
 * A kind of goal: label, hint under the target field, the description of the budget category it becomes, the label of its name
 * field (if it asks for one), and the spending category it replaces (a trip goal takes over Travel).
 */
data class GoalKind(val id: String, val label: String, val hint: String, val say: String, val nameLabel: String? = null, val absorbs: String? = null)

private fun <T> JSONArray?.map(f: (JSONObject) -> T) = if (this == null) emptyList() else (0 until length()).map { f(getJSONObject(it)) }
private fun JSONArray?.strings() = if (this == null) emptyList() else (0 until length()).map { getString(it) }
private fun add(o: JSONObject) = GuideAdd(o.getString("category"), o.getString("say"), Math.round(o.getDouble("base") * 100), o.optString("period").ifEmpty { null })
private fun question(q: JSONObject, id: String = q.getString("id")) = GuideQuestion(id, q.getString("text"), q.optBoolean("multi"),
    q.getJSONArray("options").map { op ->
        GuideOption(op.getString("id"), op.getString("label"), op.optJSONArray("adds").map(::add),
            op.optJSONObject("scale")?.let { s -> s.keys().asSequence().associateWith { s.getDouble(it) } }.orEmpty())
    })

fun guideCatalog(json: String): GuideCatalog {
    val o = JSONObject(json)
    return GuideCatalog(
        o.optJSONArray("bills").strings(),
        o.optJSONObject("household")?.let { question(it, "household") },
        o.getJSONArray("categories").map { GuideCat(it.getString("id"), it.getString("name"), it.optString("predict", "auto"),
            it.optJSONArray("keywords").strings(), it.optBoolean("require_keyword"), it.optDouble("elasticity", 0.5)) },
        o.optJSONArray("always").map(::add),
        o.getJSONArray("questions").map { question(it) },
        o.optDouble("spending_vs_income", 1.0),
        o.optJSONArray("goals").map { GoalKind(it.getString("id"), it.getString("label"), it.getString("hint"), it.getString("say"),
            it.optString("name_label").ifEmpty { null }, it.optString("absorbs").ifEmpty { null }) })
}

/** A category's starting point for the budget: base (household included) and how fast it grows with the money available. */
data class Need(val id: String, val baseCents: Long, val elasticity: Double)

/**
 * The categories the answers pick (question id -> chosen option ids; the household answer under "household"), in catalog order.
 * A category's description is the sentences of the answers that picked it, so it says how this person uses it (the LLM prompt
 * reads it). Its base is the answers' bases times the household's multiplier.
 */
fun guidedCategories(c: GuideCatalog, answers: Map<String, Set<String>>): List<Pair<Category, Need>> {
    val asked = listOfNotNull(c.household) + c.questions
    val chosen = asked.flatMap { q -> q.options.filter { it.id in answers[q.id].orEmpty() } }
    val scale = c.household?.options?.firstOrNull { it.id in answers["household"].orEmpty() }?.scale.orEmpty()
    val by = (c.always + chosen.flatMap { it.adds }).groupBy { it.category }
    return c.categories.filter { it.id in by }.mapIndexed { i, k ->
        val a = by.getValue(k.id)
        Category(k.id, k.name, a.map { it.say }.distinct().joinToString(" "), null, a.firstNotNullOfOrNull { it.period } ?: "month",
            k.predict, k.keywords.joinToString("|"), k.requireKeyword, sort = i) to
            Need(k.id, Math.round(a.sumOf { it.baseCents } * (scale[k.id] ?: scale["*"] ?: 1.0)), k.elasticity)
    }
}

// ---------- the math ----------

/** Average monthly income over the next 12 months: paychecks on the schedule (3-paycheck months included) plus irregular income / 12. */
fun monthlyIncome(schedule: PaySchedule?, anchor: LocalDate?, paycheckCents: Long, otherYearCents: Long, from: YearMonth): Long {
    val paydays = if (schedule == null) 0 else (0L until 12).sumOf { paychecksIn(from.plusMonths(it), schedule, anchor ?: from.atDay(1)) }
    return (paydays * paycheckCents + otherYearCents) / 12
}

/** months = 0: no date ("not sure"), the goal gets a share of what's left after bills instead (goalMonthly). */
data class Goal(val kind: String, val name: String, val targetCents: Long, val haveCents: Long, val months: Int) {
    val remainingCents get() = (targetCents - haveCents).coerceAtLeast(0)
    /** What to set aside each month to get there on time, rounded up to the cent. 0 without a date. */
    val monthlyCents get() = if (months <= 0) 0L else (remainingCents + months - 1) / months
}

// ponytail: a fixed share for goals without a date. A setting if people want to choose it.
const val OPEN_GOAL_SHARE = 0.10

/**
 * Each goal's monthly amount: dated goals what gets them there on time; goals without a date split 10% of what's left after
 * bills (whole dollars, never more than they still need).
 */
fun goalMonthly(goals: List<Goal>, afterBillsCents: Long): List<Long> {
    val open = goals.count { it.months <= 0 && it.remainingCents > 0 }
    val each = if (open == 0) 0L else (afterBillsCents.coerceAtLeast(0) * OPEN_GOAL_SHARE / open).toLong() / 100 * 100
    return goals.map { if (it.months > 0) it.monthlyCents else minOf(each, it.remainingCents) }
}

/** Months until a goal is reached at a monthly amount; null if it never is. */
fun monthsToGoal(g: Goal, monthlyCents: Long) = if (monthlyCents <= 0) null else ((g.remainingCents + monthlyCents - 1) / monthlyCents).toInt()

/**
 * Suggested budgets for the money available for spending. At the "typical" level (money = the sum of the bases) every category
 * gets its base. With more money, people spend more but slower than they earn: spending grows by (money / bases) ^ growth
 * (growth = spending_vs_income, about 0.5), and each category by that ^ its elasticity: groceries barely, dining out and fun
 * faster than spending as a whole. The rest is left over for savings. Less money shrinks them the same way, solved so the total
 * fits, so wants give way before needs. Rounded down to $5.
 */
fun suggestBudgets(needs: List<Need>, spendingCents: Long, growth: Double = 1.0): Map<String, Long> {
    val basket = needs.sumOf { it.baseCents }
    if (basket <= 0 || spendingCents <= 0) return needs.associate { it.id to 0L }
    fun at(r: Double) = needs.map { it.baseCents * r.pow(it.elasticity) }
    var r = (spendingCents.toDouble() / basket).pow(growth)
    if (at(r).sum() > spendingCents) {  // r < 1: find the level where the total fits
        var lo = 0.0; var hi = r
        repeat(60) { val m = (lo + hi) / 2; if (at(m).sum() > spendingCents) hi = m else lo = m }
        r = lo
    }
    return needs.zip(at(r)).associate { (n, v) -> n.id to v.toLong() / 500 * 500 }
}

/**
 * goals: what fits of the goals' monthly amounts (goalMonthly, per goal). spending: what's left for categories after bills and
 * goals. leftover: spending the suggestions don't use (suggested as savings). short: how much more the plan needs than comes in.
 */
data class Allocation(val goals: Long, val goalMonthly: List<Long>, val spending: Long, val budgets: Map<String, Long>, val leftover: Long, val short: Long)

/** Bills come off the top, then goals (cut to what's left if they don't fit), then suggestBudgets splits the rest. */
fun allocate(incomeCents: Long, billsCents: Long, goals: List<Goal>, needs: List<Need>, growth: Double = 1.0): Allocation {
    val afterBills = (incomeCents - billsCents).coerceAtLeast(0)
    val monthly = goalMonthly(goals, afterBills)
    val goalFit = minOf(monthly.sum(), afterBills)
    val spending = afterBills - goalFit
    val budgets = suggestBudgets(needs, spending, growth)
    return Allocation(goalFit, monthly, spending, budgets, spending - budgets.values.sum(), (billsCents + monthly.sum() - incomeCents).coerceAtLeast(0))
}

/** This month's Income and Monthly lines: Paycheck 1 (Brain.startMonthLines adds the rest for the schedule), other income, bills. */
fun guideLines(month: String, paycheckCents: Long, otherMonthlyCents: Long, bills: List<Pair<String, Long>>): List<Line> {
    // Other income stays out of Total Surplus like other one-offs; counted in, paycheckChanges would take it for a paycheck.
    fun income(name: String, cents: Long, sort: Int, surplus: Boolean) =
        Line(month = month, section = "income", name = name, estimateCents = cents, actualCents = null, sort = sort, inSurplus = surplus)
    return listOfNotNull(income("Paycheck 1", paycheckCents, 0, true).takeIf { paycheckCents > 0 },
        income("Other income", otherMonthlyCents, 50, false).takeIf { otherMonthlyCents > 0 }) +
        bills.filter { it.first.isNotBlank() && it.second > 0 }.mapIndexed { i, (n, c) ->
            Line(month = month, section = "fixed", name = n.trim(), estimateCents = c, actualCents = null, sort = i) }
}

// ---------- pay from the bank ----------

/** What the bank's deposits say about pay. payer/schedule/paycheck are null when no regular paycheck shows up. */
data class IncomeGuess(val payer: String?, val schedule: PaySchedule?, val paycheckCents: Long, val lastPayday: LocalDate?, val paychecks: Int,
                       val otherYearCents: Long, val since: LocalDate)

private val PAYROLL = Regex("""(?i)payroll|direct dep|dir dep|salary|\bpayrl\b|\badp\b|gusto|paychex""")

/**
 * Pay from bank deposits (feed cents < 0 = money in). Paychecks: deposits Plaid calls INCOME or that look like payroll, from
 * the payer that paid most at least twice; same-day deposits from it add up (pay split across accounts). Schedule from the
 * gaps: about a week, two weeks on the same weekday, about twice a month, or monthly. Paycheck = median of the last three.
 * Other income: the rest of the INCOME/payroll deposits, scaled to a year. Transfers and Venmo receipts aren't income here.
 */
fun detectIncome(feed: List<FeedTxn>, today: LocalDate): IncomeGuess? {
    val deposits = feed.filter { it.cents < 0 && !it.pending && !it.account.endsWith("Credit Card") }
    if (deposits.isEmpty()) return null
    val pay = deposits.filter { it.category == "INCOME" || PAYROLL.containsMatchIn(it.raw) }
    val main = pay.groupBy { it.merchant.lowercase() }.values
        .filter { g -> g.map { it.date }.distinct().size >= 2 }.maxByOrNull { g -> g.sumOf { -it.cents } }
    val since = deposits.minOf { it.date }
    val days = ChronoUnit.DAYS.between(since, today).coerceAtLeast(30)
    val other = pay.filter { main == null || it !in main }.sumOf { -it.cents } * 365 / days
    if (main == null) return if (other > 0) IncomeGuess(null, null, 0, null, 0, other, since) else null

    val paydays = main.groupBy { it.date }.mapValues { (_, d) -> d.sumOf { -it.cents } }.toSortedMap()
    val dates = paydays.keys.toList()
    val gaps = dates.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b) }.sorted()
    val gap = gaps[gaps.size / 2]
    val sameDay = dates.count { it.dayOfWeek == dates.last().dayOfWeek } >= dates.size * 0.8
    val schedule = when {
        gap <= 10 -> PaySchedule.WEEKLY
        gap <= 20 -> if (sameDay && gap in 13..15) PaySchedule.BIWEEKLY else PaySchedule.SEMIMONTHLY
        else -> PaySchedule.MONTHLY
    }
    val last3 = paydays.values.toList().takeLast(3).sorted()
    return IncomeGuess(main.last().merchant, schedule, last3[last3.size / 2], dates.last(), dates.size, other, since)
}

private val SHORT_DATE = DateTimeFormatter.ofPattern("MMM d")
fun describe(g: IncomeGuess) = listOfNotNull(
    g.schedule?.let { "${g.payer}: ${it.label.lowercase()}, about ${money(g.paycheckCents)} a paycheck (${g.paychecks} since ${g.since.format(SHORT_DATE)})" },
    "No regular paycheck found in your deposits since ${g.since.format(SHORT_DATE)}.".takeIf { g.schedule == null },
    "Other income: about ${money(g.otherYearCents / 12)} a month.".takeIf { g.otherYearCents > 0 },
).joinToString(" ")

// ---------- saving ----------

// ponytail: goals live in prefs as JSON: they're only shown (Settings > Goals). A Room table when progress needs history.
fun saveGoals(ctx: Context, goals: List<Goal>) = Brain.prefs(ctx).edit().putString("goals", JSONArray(goals.map {
    JSONObject().put("kind", it.kind).put("name", it.name).put("target", it.targetCents).put("have", it.haveCents).put("months", it.months)
}).toString()).apply()

fun loadGoals(ctx: Context): List<Goal> = runCatching {
    JSONArray(Brain.prefs(ctx).getString("goals", "[]")).map { Goal(it.getString("kind"), it.getString("name"), it.getLong("target"), it.getLong("have"), it.getInt("months")) }
}.getOrDefault(emptyList())

/** Writes the walkthrough's result: categories (with the budgets as adjusted), this month's lines, goals. */
suspend fun saveGuide(ctx: Context, cats: List<Category>, lines: List<Line>, goals: List<Goal>) {
    val dao = AppDb.get(ctx).dao()
    dao.saveAll(cats)
    for (l in lines) dao.save(l)
    saveGoals(ctx, goals)
}

// ---------- screens ----------

data class BillDraft(val name: String, val amount: String)
/** open: "not sure" about the months. */
data class GoalDraft(val name: String, val target: String, val have: String, val months: String, val open: Boolean = false)

/** Spending categories the chosen goals replace (a trip goal replaces Travel). */
fun absorbedBy(c: GuideCatalog, goals: List<Goal>) = goals.mapNotNull { g -> c.goals.firstOrNull { it.id == g.kind }?.absorbs }.toSet()

/**
 * Each goal as a budget category (after the spending categories), budgeted at its monthly amount rounded up to the dollar.
 * Manual only (moving money to savings isn't a purchase to guess), except a goal that replaces a spending category: it takes
 * that category's keywords so its purchases are suggested, and the description the spending answers wrote for it (picked).
 */
fun goalCategories(c: GuideCatalog, goals: List<Goal>, monthly: List<Long>, picked: List<Category>): List<Category> = goals.mapIndexed { i, g ->
    val kind = c.goals.first { it.id == g.kind }
    val takes = kind.absorbs?.let { id -> c.categories.firstOrNull { it.id == id } }
    Category("goal_${g.kind}", g.name, listOfNotNull(kind.say, picked.firstOrNull { it.id == kind.absorbs }?.description).joinToString(" "),
        (monthly[i] + 99) / 100 * 100, "month", if (takes != null) "auto" else "manual_only",
        takes?.keywords?.joinToString("|").orEmpty(), takes?.requireKeyword ?: false, sort = c.categories.size + i)
}

class GuideState(private val kinds: List<GoalKind>) {
    var pay by mutableStateOf<PaySchedule?>(null)
    var noPay by mutableStateOf(false)
    var payday by mutableStateOf("")
    var paycheck by mutableStateOf("")
    var other by mutableStateOf("")
    var fromBank by mutableStateOf("")   // what the bank's deposits showed, shown on the Income step
    val bills = mutableStateListOf<BillDraft>()
    val answers = mutableStateMapOf<String, Set<String>>()
    val goals = mutableStateMapOf<String, GoalDraft>()
    val budgets = mutableStateMapOf<String, String>()  // category id -> dollars, as typed on the Budget step
    val suggested = mutableStateMapOf<String, Long>()  // what the Budget step suggested (slider ranges)
    var sliders by mutableStateOf(false)
    val removed = mutableStateListOf<String>()   // categories taken off on the Budget step
    val unlocked = mutableStateListOf<String>()  // goal categories the user chose to edit anyway

    fun anchor() = runCatching { LocalDate.parse(payday.trim()) }.getOrNull()
    fun income() = monthlyIncome(pay, anchor(), parseCents(paycheck) ?: 0L, parseCents(other) ?: 0L, YearMonth.now())
    fun billList() = bills.map { it.name to (parseCents(it.amount) ?: 0L) }
    fun billsTotal() = billList().sumOf { it.second }
    fun goalList() = kinds.mapNotNull { k ->
        val d = goals[k.id] ?: return@mapNotNull null
        Goal(k.id, d.name.ifBlank { k.label }, parseCents(d.target) ?: 0L, parseCents(d.have) ?: 0L, if (d.open) 0 else d.months.trim().toIntOrNull() ?: 0)
    }.filter { it.targetCents > 0 }
    /** The spending categories the answers picked, minus any a goal replaces. */
    fun spending(c: GuideCatalog) = absorbedBy(c, goalList()).let { taken -> guidedCategories(c, answers).filter { it.first.id !in taken } }
    fun allocation(c: GuideCatalog) = allocate(income(), billsTotal(), goalList(), spending(c).map { it.second }, c.spendingVsIncome)
    /** Goals as saved: one without a date gets the months its monthly share takes. */
    fun savedGoals(c: GuideCatalog): List<Goal> {
        val gl = goalList(); val m = allocation(c).goalMonthly
        return gl.mapIndexed { i, g -> if (g.months > 0) g else g.copy(months = monthsToGoal(g, m[i]) ?: 0) }
    }
    fun apply(guess: IncomeGuess) {
        fromBank = describe(guess)
        guess.schedule?.let { pay = it; noPay = false; paycheck = (guess.paycheckCents / 100).toString(); payday = guess.lastPayday.toString() }
        if (guess.otherYearCents > 0) other = (guess.otherYearCents / 100).toString()
    }
    /** Fills the Budget step with fresh suggestions (each time it's reached, so changed answers show). */
    fun suggest(c: GuideCatalog) {
        budgets.clear(); suggested.clear(); unlocked.clear()
        allocation(c).budgets.forEach { (k, v) -> suggested[k] = v; budgets[k] = if (v > 0) (v / 100).toString() else "" }
        for (gc in goalCats(c)) { suggested[gc.id] = gc.budgetCents ?: 0; budgets[gc.id] = ((gc.budgetCents ?: 0) / 100).toString() }
    }
    fun goalCats(c: GuideCatalog) = goalCategories(c, goalList(), allocation(c).goalMonthly, guidedCategories(c, answers).map { it.first })
    /** What gets saved: the spending categories not removed (or replaced by a goal), then the goals, with the budgets as typed. */
    fun categories(c: GuideCatalog) = (spending(c).map { it.first }.filter { it.id !in removed } + goalCats(c))
        .map { it.copy(budgetCents = parseCents(budgets[it.id].orEmpty())?.takeIf { v -> v > 0 }) }
}

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun StepHead(title: String, intro: String) {
    Text(title, style = MaterialTheme.typography.headlineSmall)
    Text(intro, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun MoneyField(value: String, label: String, modifier: Modifier = Modifier, supporting: String? = null, onChange: (String) -> Unit) =
    OutlinedTextField(value, onChange, modifier, label = { Text(label) }, prefix = { Text("$") }, singleLine = true,
        isError = value.isNotBlank() && parseCents(value) == null, supportingText = supporting?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))

@Composable
private fun NumberRow(label: String, cents: Long, bold: Boolean = false) = Row {
    Text(label, Modifier.weight(1f), fontWeight = if (bold) FontWeight.SemiBold else null)
    Text(money(cents), fontWeight = if (bold) FontWeight.SemiBold else null)
}

/** Checkbox with its label; the whole row toggles (like Radio). */
@Composable
fun CheckRow(label: String, checked: Boolean, style: TextStyle = LocalTextStyle.current, onChange: (Boolean) -> Unit) =
    Row(Modifier.fillMaxWidth().toggleable(checked, onValueChange = onChange).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, null); Spacer(Modifier.width(12.dp)); Text(label, style = style)
    }

/** Six months of deposits, fetched only when a bank is linked during setup; banks added later in Settings get the usual 30 days. */
const val SETUP_HISTORY_DAYS = 180

/** First step: connect a bank (optional) and read the last 6 months of deposits to fill in pay. */
@Composable
fun BankStep(g: GuideState) {
    val ctx = LocalContext.current
    var backend by remember { mutableStateOf(BankSync.backend(ctx)) }
    val askBackend = remember { backend.isBlank() }
    var banks by remember { mutableStateOf(BankSync.banks(ctx)) }
    var status by remember { mutableStateOf("") }
    var reading by remember { mutableStateOf(false) }
    fun read() {
        reading = true; status = "Reading your deposits…"
        Brain.scope.launch {
            status = runCatching {
                val guess = detectIncome(withContext(Dispatchers.IO) { BankSync.history(ctx, SETUP_HISTORY_DAYS.toLong()) }, LocalDate.now())
                if (guess == null) "No deposits found yet. Plaid may still be loading your history: try again in a minute, or skip and type it in."
                else { g.apply(guess); g.fromBank + " It's filled in on the next step; check it there." }
            }.getOrElse {
                if ("PRODUCT_NOT_READY" in it.message.orEmpty()) "Your bank is still sending its history to Plaid. Try again in a minute."
                else "Couldn't read your bank: ${it.message}"
            }
            reading = false
        }
    }
    val connect = rememberBankConnect({ status = it }, SETUP_HISTORY_DAYS) { banks = BankSync.banks(ctx); read() }

    StepHead("Connect your bank", "BudgetMe reads your bank's transactions (read-only, through Plaid) to catch purchases that send no alert, " +
        "and to fill in your pay for this setup. Optional: you can skip this and type it in.")
    if (askBackend) OutlinedTextField(backend, { backend = it; BankSync.setBackend(ctx, it) }, label = { Text("Backend URL") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    for (name in banks.values) Text("✓ $name connected", color = MaterialTheme.colorScheme.primary)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (banks.isEmpty()) Button(enabled = backend.isNotBlank(), onClick = connect) { Text("Connect a bank") }
        else {
            OutlinedButton(onClick = connect) { Text("Add another") }
            Button(enabled = !reading, onClick = ::read) { Text("Read my pay") }
        }
    }
    if (status.isNotEmpty()) Text(status)
}

@Composable
fun IncomeStep(g: GuideState, c: GuideCatalog) {
    StepHead("Money coming in", "What lands in your account after taxes. This is what there is to budget.")
    c.household?.let { q ->
        Text(q.text, style = MaterialTheme.typography.titleMedium)
        for (o in q.options) Radio(o.label, o.id in g.answers[q.id].orEmpty()) { g.answers[q.id] = setOf(o.id) }
        if (g.answers[q.id].orEmpty().any { it != q.options.first().id })
            Hint("Budgeting together: put one paycheck below and everyone else's take-home pay for the year under other income.")
    }
    if (g.fromBank.isNotEmpty()) Text("From your bank: ${g.fromBank}", color = MaterialTheme.colorScheme.primary)
    Text("How often are you paid?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    PayPicker(g.pay, g.payday, more = { Radio("No regular paycheck", g.noPay) { g.noPay = true; g.pay = null } }) { s, a ->
        g.pay = s; g.payday = a; g.noPay = false
    }
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text("How much do you take home?", style = MaterialTheme.typography.titleMedium)
    if (g.pay != null) MoneyField(g.paycheck, "Take-home pay per paycheck", Modifier.fillMaxWidth(), "After taxes and deductions: what reaches your account.") { g.paycheck = it }
    MoneyField(g.other, "Other income in the last 12 months", Modifier.fillMaxWidth(),
        "Side work, freelance, tips, bonuses. Averaged over the year; blank if none.") { g.other = it }
    val inc = g.income()
    if (inc > 0) Text("About ${money(inc)} a month on average", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
}

@Composable
fun BillsStep(g: GuideState, c: GuideCatalog) {
    LaunchedEffect(Unit) { if (g.bills.isEmpty()) g.bills.addAll(c.bills.map { BillDraft(it, "") }) }
    StepHead("Monthly bills", "Costs that are about the same every month. They come off the top and become your Monthly bills. Leave a bill blank to skip it.")
    g.bills.forEachIndexed { i, b ->
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(b.name, { g.bills[i] = b.copy(name = it) }, Modifier.weight(1f), label = { Text("Bill") }, singleLine = true)
            MoneyField(b.amount, "Per month", Modifier.width(130.dp)) { g.bills[i] = b.copy(amount = it) }
        }
    }
    TextButton(onClick = { g.bills += BillDraft("", "") }) { Text("+ Add a bill") }
    NumberRow("Bills a month", g.billsTotal(), bold = true)
}

@Composable
fun SpendingStep(g: GuideState, c: GuideCatalog) {
    StepHead("How you spend", "Your answers pick your categories and describe them the way you use them, which is how BudgetMe guesses where each purchase goes.")
    for (q in c.questions) {
        Text(q.text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        val picked = g.answers[q.id].orEmpty()
        for (o in q.options) {
            if (q.multi) CheckRow(o.label, o.id in picked) { on -> g.answers[q.id] = if (on) picked + o.id else picked - o.id }
            else Radio(o.label, o.id in picked) { g.answers[q.id] = setOf(o.id) }
        }
    }
    Hint("Your categories: " + guidedCategories(c, g.answers).joinToString(", ") { it.first.name } + ". You can rename, add or merge them any time.")
}

@Composable
fun GoalsStep(g: GuideState, c: GuideCatalog) {
    StepHead("Goals", "What should this budget make room for? Each goal sets aside a monthly amount: what's still needed divided by the months you want to reach it in. Each goal also becomes its own budget category. Skip any that don't apply.")
    val gl = g.goalList(); val monthly = g.allocation(c).goalMonthly
    for (kind in c.goals) {
        val (k, label, hint) = kind
        val d = g.goals[k]
        CheckRow(label, d != null, MaterialTheme.typography.titleMedium) { on ->
            if (on) g.goals[k] = GoalDraft("", if (k == "emergency" && g.billsTotal() > 0) (g.billsTotal() * 3 / 100).toString() else "", "", "12")
            else g.goals.remove(k)
        }
        if (d != null) Column(Modifier.padding(start = 40.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            kind.nameLabel?.let { OutlinedTextField(d.name, { v -> g.goals[k] = d.copy(name = v) }, Modifier.fillMaxWidth(), label = { Text(it) }, singleLine = true) }
            if (kind.absorbs != null && guidedCategories(c, g.answers).any { it.first.id == kind.absorbs })
                Hint("Replaces your ${c.categories.first { it.id == kind.absorbs }.name} category: purchases for it go here.")
            MoneyField(d.target, if (k == "debt") "Balance" else "Target", Modifier.fillMaxWidth(),
                if (k == "emergency" && g.billsTotal() > 0) "$hint. Filled in: 3 months of your bills." else hint) { g.goals[k] = d.copy(target = it) }
            if (k != "debt") MoneyField(d.have, "Already saved", Modifier.fillMaxWidth()) { g.goals[k] = d.copy(have = it) }
            if (!d.open) OutlinedTextField(d.months, { g.goals[k] = d.copy(months = it) }, Modifier.fillMaxWidth(),
                label = { Text(if (k == "debt") "Months to pay it off" else "Months to reach this goal") }, suffix = { Text("months") }, singleLine = true,
                supportingText = { Text("How soon you want to get there, e.g. 12 for a year from now.") },
                isError = d.months.isNotBlank() && (d.months.trim().toIntOrNull() ?: 0) <= 0, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            CheckRow("No deadline: set aside what fits", d.open) { g.goals[k] = d.copy(open = it) }
            val i = gl.indexOfFirst { it.kind == k }
            if (i >= 0 && monthly[i] > 0) Text("${money(monthly[i])} a month" +
                (if (d.open) monthsToGoal(gl[i], monthly[i])?.let { ", there in about $it months" } ?: "" else ""))
            else if (i >= 0 && d.open) Hint("Nothing left after bills to set aside yet.")
        }
    }
    if (g.goals.values.any { it.open }) Hint("Goals without a date share ${(OPEN_GOAL_SHARE * 100).toInt()}% of what's left after bills.")
}

@Composable
fun BudgetStep(g: GuideState, c: GuideCatalog) {
    val a = g.allocation(c)
    val goalNeed = a.goalMonthly.sum()
    var confirmEdit by remember { mutableStateOf<Category?>(null) }
    StepHead("Your budget", "A starting point from your income, bills and goals, sized to how you said you spend and to how US households " +
        "at your income spend (BLS 2024 survey). Change any amount, or remove a category you won't use.")
    NumberRow("Coming in", g.income())
    NumberRow("Bills", -g.billsTotal())
    NumberRow("For goals and spending", a.spending + a.goals, bold = true)
    if (a.short > 0) Text(if (goalNeed > 0) "Your bills and goals need ${money(a.short)} a month more than comes in. Give a goal more months, or lower it." +
        (if (a.goals < goalNeed) " For now goals get ${money(a.goals)} of the ${money(goalNeed)} they need." else "")
        else "Your bills are ${money(a.short)} a month more than comes in.", color = MaterialTheme.colorScheme.error)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        Text("Adjust with sliders", Modifier.weight(1f)); Switch(g.sliders, { g.sliders = it })
    }
    val goalIds = g.goalCats(c).map { it.id }.toSet()
    val cats = g.categories(c)
    if (goalIds.isNotEmpty()) {
        Text("Goals", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        for (cat in cats.filter { it.id in goalIds }) BudgetRow(g, cat, a, goal = true) { confirmEdit = cat }
        Text("Spending", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    }
    for (cat in cats.filter { it.id !in goalIds }) BudgetRow(g, cat, a, goal = false) {}
    val gone = g.spending(c).map { it.first }.filter { it.id in g.removed }
    if (gone.isNotEmpty()) {
        Hint("Removed. Tap to add back:")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (cat in gone) AssistChip(onClick = { g.removed -= cat.id }, label = { Text("+ ${cat.name}") })
        }
    }
    val left = a.spending + a.goals - cats.sumOf { it.budgetCents ?: 0 }
    Text(if (left >= 0) "Left over: ${money(left)} a month. Anything you don't assign is a good start on savings."
        else "Over what's available by ${money(-left)}",
        color = if (left >= 0) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))

    confirmEdit?.let { cat ->
        val goal = g.goalList().firstOrNull { "goal_${it.kind}" == cat.id }
        AlertDialog(onDismissRequest = { confirmEdit = null }, title = { Text("Change ${cat.name}?") },
            text = { Text("${money(cat.budgetCents ?: 0)} a month comes from what you entered on the Goals page" +
                (goal?.let { " (${money(it.targetCents)}" + (if (it.months > 0) " in ${it.months} months)" else ", no deadline)") } ?: "") +
                ". Changing it here doesn't change the goal, so reaching it may take longer or shorter. To change the goal itself, go Back to Goals.") },
            confirmButton = { TextButton(onClick = { g.unlocked += cat.id; confirmEdit = null }) { Text("Edit anyway") } },
            dismissButton = { TextButton(onClick = { confirmEdit = null }) { Text("Keep it") } })
    }
}

/** One category on the Budget step. A goal's amount is locked until onUnlock's "are you sure" is accepted. */
@Composable
private fun BudgetRow(g: GuideState, cat: Category, a: Allocation, goal: Boolean, onUnlock: () -> Unit) = Column(Modifier.padding(top = 6.dp)) {
    val locked = goal && cat.id !in g.unlocked
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(cat.name, fontWeight = FontWeight.Medium)
            if (cat.period == "weekend_month") Hint("Split across the month's weekends")
            if (goal) Hint("From your Goals")
        }
        if (locked) Column(horizontalAlignment = Alignment.End) {
            Text(money(cat.budgetCents ?: 0), fontWeight = FontWeight.Medium)
            TextButton(onClick = onUnlock, contentPadding = PaddingValues(0.dp)) { Text("Edit") }
        } else MoneyField(g.budgets[cat.id].orEmpty(), "Per month", Modifier.width(130.dp)) { g.budgets[cat.id] = it }
        // goals come off on the Goals step; the catch-all stays (every purchase needs somewhere to go)
        if (!goal && cat.predict != "fallback_only")
            IconButton(onClick = { g.removed += cat.id }) { Icon(Icons.Default.Close, "Remove ${cat.name}") }
    }
    if (g.sliders && !locked) {
        // up to 3x the suggestion (at least a quarter of the money for spending), in $5 steps
        val max = maxOf(3 * (g.suggested[cat.id] ?: 0L), a.spending / 4, 10000L) / 100
        val v = (cat.budgetCents ?: 0L) / 100
        Slider(v.coerceAtMost(max).toFloat(), { g.budgets[cat.id] = (Math.round(it / 5) * 5).toString() }, valueRange = 0f..max.toFloat())
    } else if (!goal) Hint(cat.description)
}

/** Settings > Goals: what the walkthrough saved. */
@Composable
fun GoalsSummary(goals: List<Goal>) {
    if (goals.isEmpty()) Hint("No goals set.")
    for (gl in goals) Column {
        Text(gl.name, fontWeight = FontWeight.Medium)
        Hint("${money(gl.targetCents)}" + (if (gl.haveCents > 0) ", ${money(gl.haveCents)} saved" else "") +
            if (gl.months > 0) " · ${money(gl.monthlyCents)} a month for ${gl.months} months" else " · no date")
    }
}

@Composable
fun rememberGuideCatalog(): GuideCatalog {
    val ctx = LocalContext.current
    return remember { guideCatalog(ctx.assets.open("guided_setup.json").bufferedReader().readText()) }
}
