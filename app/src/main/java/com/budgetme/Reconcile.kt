package com.budgetme

import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs

// "What's different": the app's bank balance against the bank's, explained item by item. Pure (tested in ReconcileTest).
//
// The bank's balance now, minus this month's bank transactions, is what it had when the month started. So the gap splits
// into the starting balance (the bank's against the carry-over the app used) and this month's items: every bank
// transaction matched to the app's purchases, bills, income and card payments, and whatever doesn't match. The items
// always add up to the whole gap; anything left over is listed as not explained.

enum class DiffKind {
    START,        // starting balance: the bank's on the 1st against the app's carry-over
    OTHER_MONTH,  // the same money on both sides, but counted toward a different month than its bank date (no fix needed)
    CARD_TIMING,  // card payments this month against the card charges marked paid this month (paying last month's charges)
    BANK_ONLY,    // in the bank, not in the app
    APP_ONLY,     // in the app, not in the bank
    AMOUNT,       // in both, different amounts
    UNEXPLAINED,  // what's left
}

/**
 * One reason for the gap. cents: how much of (bank - app) it accounts for. feed / txn / line: what it's about, for the fix.
 * betweenMonths: the starting balance, items counted toward another month, and card payments for earlier charges. These
 * move the same money between months and usually cancel out; only their sum says something is off.
 */
data class Diff(val kind: DiffKind, val label: String, val cents: Long, val date: LocalDate? = null,
                val feed: FeedTxn? = null, val txn: Txn? = null, val line: Line? = null) {
    val betweenMonths get() = kind == DiffKind.START || kind == DiffKind.OTHER_MONTH || kind == DiffKind.CARD_TIMING
}

data class Reconciliation(val bankNow: Long, val appNow: Long, val bankStart: Long, val diffs: List<Diff>) {
    val gap get() = bankNow - appNow
    /** Net of the between-months group: 0 when the carry-over agrees with the bank once timing is counted. */
    val betweenMonths get() = diffs.filter { it.betweenMonths }.sumOf { it.cents }
    /** The items to look at: this month's differences, plus the between-months net when it isn't 0. */
    val items get() = diffs.filter { !it.betweenMonths }
}

// "CHASE CREDIT CRD EPAY ...": a credit card payment leaving the bank account
private val CARD_PAY = Regex("""(?i)credit\s*(?:crd|card)\b|\bcard\s*(?:pmt|payment)\b""")
private const val DAYS = 3L  // a purchase and its bank transaction can be this many days apart

/**
 * bankNow: the checking account's available balance. feed: its transactions from a little before the month started to
 * today (cents > 0 = out), pending ones included, like the balance. b: the app's balance for the month. filed: purchases
 * budgeted to the month. unfiled: Inbox purchases dated in the month. deposits: deposits not filed yet. lines: the month's
 * Income and Monthly lines. Bank-account purchases only; card charges count as the card payments that paid them.
 */
fun reconcile(month: YearMonth, bankNow: Long, feed: List<FeedTxn>, b: Balance, filed: List<Txn>, unfiled: List<Txn>,
              deposits: List<Txn>, lines: List<Line>, today: LocalDate): Reconciliation {
    val first = month.atDay(1)
    val diffs = mutableListOf<Diff>()
    val bankFeed = feed.filter { !it.account.contains("Credit") }
    val inMonth = bankFeed.filter { !it.date.isBefore(first) }
    val bankStart = bankNow + inMonth.sumOf { it.cents }  // undo this month's transactions (out is +cents)
    val unused = bankFeed.toMutableList()
    fun take(f: FeedTxn) = unused.remove(f)

    // --- purchases paid straight from the bank (alerts, the bank feed, Venmo from the bank, typed in) ---
    val direct = (filed + unfiled).filter { !isCard(it) && bankPart(it) != 0L }
    // an item counted in this month whose bank date is last month: the bank's starting balance already has it
    for (t in direct.sortedBy { it.feedId == null }) {
        val cents = bankPart(t)
        val f = unused.firstOrNull { it.id == t.feedId }
            ?: unused.filter { it.cents == cents && within(it.date, t.date) && (it.account == t.account || t.account == "Venmo" || t.account == null) }
                .minByOrNull { (if (nameFits(t.merchant, it.merchant)) 0 else 10) + abs(ChronoUnit.DAYS.between(it.date, LocalDate.parse(t.date))) }
        if (f == null) {
            if (LocalDate.parse(t.date).isBefore(today.minusDays(DAYS)))
                diffs += Diff(DiffKind.APP_ONLY, "${t.merchant} isn't in the bank", cents, LocalDate.parse(t.date), txn = t)
            else diffs += Diff(DiffKind.APP_ONLY, "${t.merchant} hasn't reached the bank yet", cents, LocalDate.parse(t.date), txn = t)
            continue
        }
        take(f)
        if (f.cents != cents) diffs += Diff(DiffKind.AMOUNT, "${t.merchant}: the bank says ${money(f.cents)}, BudgetMe ${money(cents)}",
            cents - f.cents, f.date, feed = f, txn = t)
        if (f.date.isBefore(first))
            diffs += Diff(DiffKind.OTHER_MONTH, "${t.merchant} (${f.date.format(SHORT)}) counts toward ${monthAbbr(month.toString())}", f.cents, f.date, f, t)
    }

    // --- card payments against card charges marked paid this month ---
    val cardPaid = filed.filter(::isCard).sumOf(::bankPart)
    val payments = unused.filter { it.cents > 0 && CARD_PAY.containsMatchIn(it.raw) && !it.date.isBefore(first) }
    payments.forEach(::take)
    val paidOut = payments.sumOf { it.cents }
    if (paidOut != cardPaid) diffs += Diff(DiffKind.CARD_TIMING,
        "Card payments this month ${money(paidOut)}, card charges marked paid in ${monthAbbr(month.toString())} ${money(cardPaid)}",
        cardPaid - paidOut)

    // --- Monthly bills and income (no dates: a bank transaction with the amount, this month first; one in the days before
    // the 1st is a bill or paycheck counted toward this month, like an end-of-month paycheck) ---
    for (l in lines.filter { !it.deleted && !isComputed(it) && it.actualCents != null && it.actualCents != 0L }) {
        if (l.section == "fixed" && l.onCard) continue
        val out = l.section == "fixed"
        val cents = if (out) l.actualCents!! else -l.actualCents!!
        val f = unused.filter { it.cents == cents }.minByOrNull { (if (it.date.isBefore(first)) 10 else 0) + if (nameFits(l.name, it.merchant)) 0 else 1 }
        when {
            f == null -> diffs += Diff(DiffKind.APP_ONLY, "${l.name} (${if (out) "bill" else "income"}) isn't in the bank this month", cents, line = l)
            f.date.isBefore(first) -> { take(f); diffs += Diff(DiffKind.OTHER_MONTH, "${l.name} (${f.date.format(SHORT)}) counts toward ${monthAbbr(month.toString())}", cents, f.date, f, line = l) }
            else -> take(f)
        }
    }
    for (d in deposits) {
        val f = unused.filter { it.cents == -d.amountCents && within(it.date, d.date) }.minByOrNull { abs(ChronoUnit.DAYS.between(it.date, LocalDate.parse(d.date))) }
        if (f != null) take(f) else diffs += Diff(DiffKind.APP_ONLY, "${d.merchant} deposit isn't in the bank", -d.amountCents, LocalDate.parse(d.date), txn = d)
    }

    // a charge and its REVERSAL both still pending: they cancel, and neither is in the app (the app followed the re-charge)
    for (r in unused.filter { it.cents < 0 && REVERSED.containsMatchIn(it.raw) }.toList()) {
        val c = unused.firstOrNull { it.cents == -r.cents && it.date == r.date && nameFits(it.merchant, r.merchant.replace(REVERSED, "").trim()) }
        if (c != null) { take(c); take(r) }
    }

    // --- bank transactions nothing in the app accounts for; a bill or income line still waiting is the likely home ---
    val waiting = lines.filter { !it.deleted && !isComputed(it) && it.actualCents == null && !it.onCard }.toMutableList()
    for (f in unused.filter { !it.date.isBefore(first) }.sortedBy { it.date }) {
        val home = waiting.filter { it.section == if (f.cents > 0) "fixed" else "income" }.firstOrNull { l ->
            nameFits(l.name, f.merchant) || l.estimateCents?.let { e -> e != 0L && abs(abs(f.cents) - e) * 10 <= e } == true
        }?.also { waiting -= it }
        diffs += Diff(DiffKind.BANK_ONLY, (if (f.cents > 0) "${f.merchant}: ${money(f.cents)} out" else "${f.merchant}: ${money(-f.cents)} in") +
            (if (f.pending) " (pending)" else "") + (home?.let { " · probably ${it.name}" } ?: ""), -f.cents, f.date, feed = f, line = home)
    }

    diffs += Diff(DiffKind.START, "Starting balance for ${monthAbbr(month.toString())}: the bank had ${money(bankStart)}, " +
        "BudgetMe carried over ${money(b.carryIn)}", bankStart - b.carryIn)
    val rest = (bankNow - b.total) - diffs.sumOf { it.cents }
    if (rest != 0L) diffs += Diff(DiffKind.UNEXPLAINED, "Not explained by the items above", rest)
    return Reconciliation(bankNow, b.total, bankStart, diffs.filter { it.cents != 0L || it.kind == DiffKind.APP_ONLY })
}

private val SHORT = java.time.format.DateTimeFormatter.ofPattern("MMM d")
private val REVERSED = Regex("""(?i)\breversal\b""")
private fun within(a: LocalDate, b: String) = abs(ChronoUnit.DAYS.between(a, LocalDate.parse(b))) <= DAYS
