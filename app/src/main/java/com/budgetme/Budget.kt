package com.budgetme

// The bank balance the way the tracker computes it (cell M14): what came in minus everything that has left the bank
// account. Card charges count only once the card is paid off. A month starts from the previous month's balance
// (the sheet's "Extra Leftover" line), so the result is a running balance that should match the checking account.

fun isCard(t: Txn) = t.account?.contains("Credit", ignoreCase = true) == true

/** Money that has left the bank account: bank purchases, and card charges once paid off. */
fun fromBank(t: Txn) = !isCard(t) || t.cardPaid

// ponytail: the tracker's own row names. The carry-over row holds last month's balance; "Monthly Surplus" is a
// projection. The sheet computes both with formulas, so the app shows them read-only and never writes them back.
private val CARRY_NAMES = setOf("extra leftover", "leftover", "carryover", "carry over")
fun isCarry(l: Line) = l.section == "income" && l.name.trim().lowercase() in CARRY_NAMES
/** Last month's surplus, carried in (the sheet: =MAX(0, last month's Total Surplus)); the rest of the balance is the carry row. */
fun isSurplusIn(l: Line) = l.section == "income" && l.name.trim().equals("monthly surplus", true)
fun isComputed(l: Line) = isCarry(l) || isSurplusIn(l)

data class Balance(
    val carryIn: Long,       // last month's closing balance (the sheet's Monthly Surplus + Extra Leftover actuals)
    val income: Long,        // income actuals (without the carry-over)
    val fixed: Long,         // Monthly section actuals paid from the bank
    val spentBank: Long,     // filed purchases paid from the bank (incl. paid-off card charges)
    val unfiledBank: Long,   // bank purchases still in the Inbox: already gone from the account
    val spentCard: Long,     // card charges and card-paid bills not paid off yet, budgeted categories (not in the balance)
    val unfiledDeposits: Long = 0,            // deposits from alerts not filed into an income line yet: already in the account
    val surplusIncome: Long = income,         // income lines marked "counts toward Total Surplus"
    val surplusSpentBank: Long = spentBank,   // spentBank, budgeted categories only (Work Expenses get refunded)
    val prevSurplus: Long? = null,            // last month's Total Surplus, negative if it ate into savings (shown, not added)
) {
    /** How last month's balance splits: its surplus (can be negative here, unlike the sheet's 0) and the money already there. */
    val leftoverIn get() = carryIn - (prevSurplus ?: 0)
    /** The bank balance (the tracker's N14). */
    val total get() = carryIn + income + unfiledDeposits - fixed - spentBank - unfiledBank
    /** The tracker's Total Surplus (N47): marked income minus what left the bank for bills and budgeted categories. */
    val surplus get() = surplusIncome - fixed - surplusSpentBank
    /** Total Surplus, also counting what's still on the card and Inbox purchases: the "don't spend more than I make" check. */
    val surplusWithCard get() = surplus - spentCard - unfiledBank
}

/**
 * filed: purchases budgeted to this month. unfiled: Inbox purchases dated this month. prevBalance: last month's total,
 * used when there's no carry-over line with an amount (months the sheet doesn't have).
 */
fun balance(lines: List<Line>, filed: List<Txn>, unfiled: List<Txn>, prevBalance: Long?, budgeted: Set<String>? = null,
            deposits: List<Txn> = emptyList(), prevSurplus: Long? = null): Balance {
    val live = lines.filter { !it.deleted }
    val carried = live.filter { isComputed(it) && it.actualCents != null }
    val carry = if (carried.isNotEmpty()) carried.sumOf { it.actualCents!! } else prevBalance ?: 0
    val income = live.filter { it.section == "income" && !isComputed(it) }
    fun inBudget(t: Txn) = budgeted == null || t.categoryId in budgeted
    return Balance(carry,
        income.sumOf { it.actualCents ?: 0 },
        live.filter { it.section == "fixed" && !it.onCard }.sumOf { it.actualCents ?: 0 },
        filed.filter(::fromBank).sumOf { it.amountCents },
        unfiled.filter { !isCard(it) }.sumOf { it.amountCents },
        filed.filter { !fromBank(it) && inBudget(it) }.sumOf { it.amountCents } + live.filter { it.onCard }.sumOf { it.actualCents ?: 0 },
        deposits.sumOf { it.amountCents },
        income.filter { it.inSurplus }.sumOf { it.actualCents ?: 0 },
        filed.filter { fromBank(it) && inBudget(it) }.sumOf { it.amountCents },
        prevSurplus)
}

/**
 * Where a label's value lives in a tab, e.g. "Total Surplus" -> "N47": the first filled cell to the right of the label.
 * under: only look below this label, in the same column (the bank "Total" sits under "BANK SUMMARY").
 */
fun cellRightOf(grid: List<List<Any?>>, label: String, under: String? = null): String? {
    fun text(v: Any?) = (v as? String)?.trim().orEmpty()
    var start = 0; var col: Int? = null
    if (under != null) {
        val r = grid.indexOfFirst { row -> row.any { text(it).equals(under, true) } }.takeIf { it >= 0 } ?: return null
        start = r + 1; col = grid[r].indexOfFirst { text(it).equals(under, true) }
    }
    for (r in start until grid.size) {
        val c = grid[r].indices.firstOrNull { (col == null || it == col) && text(grid[r][it]).equals(label, true) } ?: continue
        val v = (c + 1 until grid[r].size).firstOrNull { grid[r][it] != null && grid[r][it].toString().isNotBlank() } ?: return null
        return colLetter(v) + (r + 1)
    }
    return null
}

/**
 * The carry-over formulas for a new month tab, as in the tracker (July 2026): Monthly Surplus = last month's Total
 * Surplus (0 if it was negative), Extra Leftover = last month's closing balance minus that. Null if the labels aren't found.
 */
fun carryOverFormulas(prevTitle: String, prevGrid: List<List<Any?>>, surplusCell: String): Pair<String, String>? {
    val balance = cellRightOf(prevGrid, "Total", under = "BANK SUMMARY") ?: return null
    val surplus = cellRightOf(prevGrid, "Total Surplus") ?: return null
    val tab = "'" + prevTitle.replace("'", "''") + "'"
    return "=MAX(0, $tab!$surplus)" to "=$tab!$balance - $surplusCell"
}

/**
 * Income lines to offer for a deposit, likeliest first. A paycheck goes to an empty line with an estimate, closest
 * estimate first; anything else to a catch-all line ("Other"), then lines without an estimate, in sheet order.
 */
fun depositGuess(lines: List<Line>, cents: Long, kind: String): List<Line> {
    val income = lines.filter { it.section == "income" && !it.deleted && !isComputed(it) }
    fun empty(l: Line) = l.actualCents == null || l.actualCents == 0L
    fun estimated(l: Line) = (l.estimateCents ?: 0) != 0L
    return if (kind == "paycheck") income.sortedWith(compareBy({ if (empty(it) && estimated(it)) 0 else 1 },
        { Math.abs((it.estimateCents ?: 0) - cents) }, { it.sort }))
    // ponytail: "other" by name is a guess at the catch-all row; a wrong guess is one extra tap
    else income.sortedWith(compareBy({ if (it.name.contains("other", true)) 0 else if (!estimated(it)) 1 else 2 }, { it.sort }))
}

/** New income lines count toward Total Surplus when they have an estimate (paychecks), not one-offs. */
fun defaultInSurplus(section: String, name: String, estimate: Long?) =
    section == "income" && estimate != null && estimate != 0L && !isComputed(Line(month = "", section = section, name = name, estimateCents = null, actualCents = null, sort = 0))

/** Anything still on the credit card: a purchase ("t12") or a Monthly bill ("l7"). Bills sort as the 1st of their month. */
data class CardItem(val key: String, val date: String, val label: String, val cents: Long)
fun cardItems(txns: List<Txn>, lines: List<Line>) = (txns.map { CardItem("t${it.id}", it.date, it.merchant, it.amountCents) } +
    lines.map { CardItem("l${it.id}", "${it.month}-01", it.name, it.actualCents ?: 0) }).sortedWith(compareBy({ it.date }, { it.key }))

/**
 * Which unpaid card charges a payment covers. A statement payment covers everything up to the statement, so oldest
 * first; failing an exact match, oldest first skipping one charge (paying around a single large one); failing that,
 * oldest first while it fits. The user adjusts the ticks before confirming.
 */
fun pickPayment(unpaid: List<CardItem>, cents: Long): Set<String> {
    val s = unpaid.sortedWith(compareBy({ it.date }, { it.key }))
    var sum = 0L
    for ((i, t) in s.withIndex()) { sum += t.cents; if (sum == cents) return s.take(i + 1).map { it.key }.toSet() }
    for (skip in s.indices) {
        var acc = 0L
        for ((i, t) in s.withIndex()) {
            if (i == skip) continue
            acc += t.cents
            if (acc == cents) return s.take(i + 1).filterIndexed { j, _ -> j != skip }.map { it.key }.toSet()
            if (acc > cents) break
        }
    }
    sum = 0L
    return s.takeWhile { sum += it.cents; sum <= cents }.map { it.key }.toSet()
}

// ---------- Income / Monthly lines <-> sheet ----------

/** A row of the sheet's Income or Monthly Expenses section. Column A holds the app's id as #L12 once linked. */
data class SheetLine(val tab: String, val month: String, val section: String, val row: Int, val name: String,
                     val estimate: Long?, val actual: Long?, val lineId: Long?, val onCard: Boolean = false)
data class LineSection(val tab: String, val month: String, val section: String, val header: Int, val total: Int, val free: List<Int>,
                       val sum: IntRange? = null, val cols: Cols = Cols(null, 4, null, 5))

private fun normName(s: String) = s.replace(Regex("""\s+"""), " ").trim().lowercase()
fun lineState(name: String, estimate: Long?, actual: Long?, onCard: Boolean) =
    "${normName(name)}|${estimate ?: ""}|${actual ?: ""}|${if (onCard && actual != null) "card" else "bank"}"
fun lineState(l: Line) = lineState(l.name, l.estimateCents, l.actualCents, l.onCard)
fun lineState(r: SheetLine) = lineState(r.name, r.estimate, r.actual, r.onCard)

data class LinePlan(
    val write: List<Pair<SheetLine, Line>>,     // app's edit: rewrite name, estimate, actual
    val push: List<Line>,                       // new in the app: add to its section
    val clear: List<SheetLine>,                 // deleted in the app
    val fromSheet: List<Pair<SheetLine, Line?>>,// sheet wins (null = new line typed in the sheet)
    val remove: List<Line>,                     // gone from the sheet, or app deletions now cleared
)

/** Same rules as purchases (planSync): ids in column A, first link by name, sheet wins when both changed. */
fun planLines(rows: List<SheetLine>, lines: List<Line>, fetched: Set<String>): LinePlan {
    val write = mutableListOf<Pair<SheetLine, Line>>(); val push = mutableListOf<Line>(); val clear = mutableListOf<SheetLine>()
    val fromSheet = mutableListOf<Pair<SheetLine, Line?>>(); val remove = mutableListOf<Line>()
    val byId = lines.associateBy { it.id }; val seen = mutableSetOf<Long>()
    for (r in rows.filter { it.lineId != null }) {
        val l = byId[r.lineId]
        // l == null: not from this install (lines are soft-deleted, so it can't be an app deletion): take it as a new line.
        // Not seen.add: a copied row, also a new line.
        if (l == null || l.deleted || !seen.add(l.id)) { if (l?.deleted == true) clear += r else fromSheet += r.copy(lineId = null) to null; continue }
        val sheetChanged = l.sheetHash == null || lineState(r) != l.sheetHash
        val appChanged = l.sheetHash != null && lineState(l) != l.sheetHash
        when {
            sheetChanged -> fromSheet += r to l
            appChanged && !isComputed(l) -> write += r to l
        }
    }
    val open = lines.filter { !it.deleted && it.sheetHash == null && it.id !in seen }.toMutableList()
    for (r in rows.filter { it.lineId == null }) {
        val m = open.firstOrNull { it.month == r.month && it.section == r.section && normName(it.name) == normName(r.name) }
        if (m != null) { open -= m; seen += m.id }
        fromSheet += r to m
    }
    for (l in lines.filter { it.id !in seen && it.month in fetched }) when {
        l.deleted || l.sheetHash != null -> remove += l   // cleared above, or deleted in the sheet
        !isComputed(l) -> push += l
    }
    return LinePlan(write, push, clear, fromSheet, remove)
}

// ---------- pay schedule ----------

/** How often paychecks come: decides how many "Paycheck N" income lines a month gets. */
enum class PaySchedule(val label: String) { MONTHLY("Monthly"), SEMIMONTHLY("Twice a month"), BIWEEKLY("Every two weeks"), WEEKLY("Every week") }

/** Paydays in a month. Every-two-weeks / weekly count from a known payday (3 or 5 in some months); without one, 2 / 4. */
fun paychecksIn(month: java.time.YearMonth, schedule: PaySchedule, anchor: java.time.LocalDate?): Int = when (schedule) {
    PaySchedule.MONTHLY -> 1
    PaySchedule.SEMIMONTHLY -> 2
    PaySchedule.BIWEEKLY, PaySchedule.WEEKLY -> {
        val step = if (schedule == PaySchedule.BIWEEKLY) 14L else 7L
        if (anchor == null) (if (schedule == PaySchedule.BIWEEKLY) 2 else 4)
        else (1..month.lengthOfMonth()).count { Math.floorMod(java.time.temporal.ChronoUnit.DAYS.between(anchor, month.atDay(it)), step) == 0L }
    }
}

private val PAYCHECK = Regex("""(?i)^paycheck (\d+)$""")
fun paycheckNo(l: Line) = if (l.section == "income") PAYCHECK.find(l.name.trim())?.groupValues?.get(1)?.toInt() else null

/**
 * Paycheck lines to add and to remove so a month has `count` paychecks. Any income line that counts toward Total Surplus
 * and has an estimate is a paycheck, whatever it's called (e.g. "Employer 1st"); new ones are named "Paycheck N". Extra
 * lines go only if they're named "Paycheck N" and nothing was paid into them.
 */
fun paycheckChanges(lines: List<Line>, month: String, count: Int): Pair<List<Line>, List<Line>> {
    val live = lines.filter { !it.deleted && it.section == "income" && !isComputed(it) }
    val pay = live.filter { paycheckNo(it) != null || (it.inSurplus && (it.estimateCents ?: 0) != 0L) }.sortedBy { it.sort }
    val last = pay.lastOrNull()?.sort ?: (live.minOfOrNull { it.sort } ?: 0)
    val add = (pay.size + 1..count).map { k ->
        Line(month = month, section = "income", name = "Paycheck $k", estimateCents = pay.firstOrNull()?.estimateCents, actualCents = null,
            sort = last + (k - pay.size), inSurplus = true)
    }
    val remove = pay.filter { (paycheckNo(it) ?: 0) > count && (it.actualCents ?: 0L) == 0L }
    return add to remove
}
