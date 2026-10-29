package com.budgetme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

// Month tab additions: the bank balance (the tracker's M14), Income and Monthly lines, paying off the card,
// and checking the balance against the real account.

private fun parseCents(s: String): Long? = s.replace(Regex("""[$,\s]"""), "").takeIf { it.isNotEmpty() }?.toDoubleOrNull()?.let { Math.round(it * 100) }
private fun centsField(c: Long?) = c?.let { "%.2f".format(it / 100.0) }.orEmpty()

@Composable
private fun Line2(label: String, value: String, bold: Boolean = false, muted: Boolean = false) = Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
    val color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Text(label, Modifier.weight(1f), fontWeight = if (bold) FontWeight.SemiBold else null, color = color)
    Text(value, fontWeight = if (bold) FontWeight.SemiBold else null, color = color)
}

private fun signed(c: Long) = (if (c < 0) "−" else "+") + money(Math.abs(c))

@Composable
fun BalanceCard(b: Balance?, unpaidCard: Long, isCurrent: Boolean, onPay: () -> Unit, onCheck: () -> Unit, bank: Brain.BankCheck? = null) {
    Card(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text("Wells Fargo balance", style = MaterialTheme.typography.labelLarge)
            Text(b?.let { money(it.total) } ?: "—", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
            bank?.let { k ->
                val diff = k.bankCents - k.appCents
                Text("Wells Fargo says ${money(k.bankCents)} at the end of ${k.asOf.format(DateTimeFormatter.ofPattern("MMM d"))}: " +
                    if (diff == 0L) "matches the app." else "the app is off by ${money(Math.abs(diff))} (bank has ${if (diff > 0) "more" else "less"}).",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (diff == 0L) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            }
            if (b != null) {
                Spacer(Modifier.height(6.dp))
                Line2("Carried over from last month", money(b.carryIn), muted = true)
                Line2("Income", "+" + money(b.income), muted = true)
                Line2("Monthly bills", "−" + money(b.fixed), muted = true)
                Line2("Spent from Wells Fargo", "−" + money(b.spentBank), muted = true)
                if (b.unfiledDeposits != 0L) Line2("Deposits not filed yet", "+" + money(b.unfiledDeposits), muted = true)
                if (b.unfiledBank != 0L) Line2("In the Inbox, not categorized yet", "−" + money(b.unfiledBank), muted = true)
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Line2("Total Surplus", signed(b.surplus), bold = true)
                Line2("Including card and Inbox", signed(b.surplusWithCard), bold = true)
                Text("Surplus: income marked for it, minus bills and spending paid from Wells Fargo (categories without a budget, " +
                    "like Work Expenses, left out), as in your sheet. The second line also subtracts what's still on the card and " +
                    "uncategorized purchases.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Line2("On the credit card, not paid off", money(unpaidCard))
            if (isCurrent && b != null) Line2("Balance after paying off the card", money(b.total - unpaidCard), muted = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = onPay, enabled = unpaidCard != 0L) { Text("Record card payment") }
                OutlinedButton(onClick = onCheck, enabled = b != null) { Text("Check against bank") }
            }
        }
    }
}

/** Income or Monthly lines for a month: estimate vs actual, tap to edit. */
@Composable
fun LinesBlock(title: String, section: String, month: String, lines: List<Line>) {
    var editing by remember { mutableStateOf<Line?>(null) }
    var open by remember(month) { mutableStateOf(false) }
    val mine = lines.filter { it.section == section }
    Column(Modifier.padding(top = 12.dp)) {
        Row(Modifier.clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Text((if (open) "▾ " else "▸ ") + title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Text("${money(mine.filter { !isComputed(it) }.sumOf { it.actualCents ?: 0 })} / ${money(mine.filter { !isComputed(it) }.sumOf { it.estimateCents ?: 0 })}",
                fontWeight = FontWeight.SemiBold)
        }
        Text("actual / estimate", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (open) for (l in mine) Row(Modifier.fillMaxWidth().clickable { editing = l }.padding(start = 8.dp, top = 4.dp, bottom = 4.dp)) {
            Text(l.name + if (isCarry(l)) " (last month's balance)" else if (isComputed(l)) " (projection)" else "", Modifier.weight(1f))
            Text(when {
                isCarry(l) -> l.actualCents?.let { money(it) } ?: "—"
                isComputed(l) -> l.estimateCents?.let { money(it) } ?: "—"
                else -> "${l.actualCents?.let { money(it) + if (l.onCard) " on card" else "" } ?: "—"} / ${l.estimateCents?.let { money(it) } ?: "—"}"
            }, color = if (l.actualCents == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
        }
        if (open) TextButton(onClick = { editing = Line(month = month, section = section, name = "", estimateCents = null, actualCents = null, sort = 1000 + mine.size) }) {
            Text(if (section == "income") "+ Add income" else "+ Add bill")
        }
    }
    editing?.let { LineDialog(it) { editing = null } }
}

@Composable
fun LineDialog(line: Line, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val isNew = line.id == 0L
    var name by remember { mutableStateOf(line.name) }
    var estimate by remember { mutableStateOf(centsField(line.estimateCents)) }
    var actual by remember { mutableStateOf(centsField(line.actualCents)) }
    var onCard by remember { mutableStateOf(line.onCard) }
    var inSurplus by remember { mutableStateOf(if (line.id == 0L) line.section == "income" else line.inSurplus) }
    var confirmDelete by remember { mutableStateOf(false) }
    val carry = isComputed(line)
    AlertDialog(onDismissRequest = onDone,
        title = { Text(if (isNew) (if (line.section == "income") "New income" else "New bill") else line.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isCarry(line)) Text("Last month's closing balance. It comes from the sheet (or the app's own total for last month), so it isn't edited here.")
                else if (carry) Text("Calculated by a formula in your sheet, so it isn't edited here.")
                else {
                    OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                    OutlinedTextField(estimate, { estimate = it }, label = { Text("Estimate ($)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(actual, { actual = it }, label = { Text("Actual ($, blank until it's paid)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    if (line.section == "fixed") Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(onCard, { onCard = it }); Text("Charged to the credit card, not paid off yet")
                    }
                    if (line.section == "income") Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(inSurplus, { inSurplus = it }); Text("Counts toward Total Surplus")
                    }
                }
            }
        },
        confirmButton = {
            if (!carry) TextButton(enabled = name.isNotBlank(), onClick = {
                Brain.scope.launch { Brain.saveLine(ctx, line.copy(name = name.trim(), estimateCents = parseCents(estimate), actualCents = parseCents(actual), onCard = onCard, inSurplus = inSurplus)) }
                onDone()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (!isNew && !carry) TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
                TextButton(onClick = onDone) { Text(if (carry) "Close" else "Cancel") }
            }
        })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${line.name}?") },
        text = { Text("It's removed from this month in the app and from your sheet.") },
        confirmButton = { TextButton(onClick = { Brain.scope.launch { Brain.deleteLine(ctx, line) }; confirmDelete = false; onDone() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

/** Enter the payment; the oldest charges that add up to it are ticked (Budget.pickPayment); adjust, then confirm. */
@Composable
fun CardPaymentDialog(unpaid: List<CardItem>, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var amount by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    val cents = parseCents(amount)
    LaunchedEffect(cents) { if (cents != null) picked = pickPayment(unpaid, cents) }
    val sum = unpaid.filter { it.key in picked }.sumOf { it.cents }
    AlertDialog(onDismissRequest = onDone, title = { Text("Record card payment") },
        text = {
            Column {
                OutlinedTextField(amount, { amount = it }, label = { Text("Amount paid ($)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                Text("Oldest charges that add up to the payment are ticked. Leave a charge unticked to keep it on the card.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 6.dp))
                val all = picked.size == unpaid.size
                TextButton(onClick = { picked = if (all) emptySet() else unpaid.map { it.key }.toSet() }) {
                    Text(if (all) "Clear all" else "Select all (${money(unpaid.sumOf { it.cents })})")
                }
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(unpaid, key = { it.key }) { t ->
                        Row(Modifier.fillMaxWidth().clickable { picked = if (t.key in picked) picked - t.key else picked + t.key },
                            verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(t.key in picked, { on -> picked = if (on) picked + t.key else picked - t.key })
                            Text(if (t.key.startsWith("l")) monthAbbr(t.date.take(7)) + " bill" else LocalDate.parse(t.date).format(DateTimeFormatter.ofPattern("MMM d")),
                                Modifier.width(64.dp), style = MaterialTheme.typography.bodySmall)
                            Text(t.label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            Text(amountText(t.cents), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Line2("Ticked (${picked.size})", money(sum), bold = true)
                if (cents != null && cents != sum) Text("${money(Math.abs(cents - sum))} ${if (cents > sum) "more paid than ticked" else "more ticked than paid"}",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(enabled = picked.isNotEmpty(), onClick = {
                val txnIds = picked.filter { it.startsWith("t") }.map { it.drop(1).toLong() }
                val lineIds = picked.filter { it.startsWith("l") }.map { it.drop(1).toLong() }
                Brain.scope.launch { Brain.setCardPaid(ctx, txnIds, true, lineIds) }; onDone()
            }) {
                Text("Mark ${picked.size} paid")
            }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } })
}

/** Type the real balance; show the difference and the usual reasons for one. */
@Composable
fun BankCheckDialog(b: Balance, month: String, lines: List<Line>, unfiled: List<Txn>, unpaidCard: Long, onDone: () -> Unit) {
    var bank by remember { mutableStateOf("") }
    val actual = parseCents(bank)
    val pendingAmounts = unfiled.count { it.amountCents == 0L }
    val billsWaiting = lines.filter { it.section == "fixed" && it.actualCents == null && (it.estimateCents ?: 0) != 0L }
    val incomeWaiting = lines.filter { it.section == "income" && !isComputed(it) && it.actualCents == null && (it.estimateCents ?: 0) != 0L }
    AlertDialog(onDismissRequest = onDone, title = { Text("Check against Wells Fargo") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(bank, { bank = it }, label = { Text("Balance in the Wells Fargo app ($)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
                Line2("App's balance for ${monthAbbr(month)}", money(b.total))
                if (actual != null) {
                    val diff = actual - b.total
                    if (diff == 0L) Text("They match.", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                    else {
                        Text("Off by ${money(Math.abs(diff))}: the bank has ${if (diff > 0) "more" else "less"} than the app.",
                            fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
                        Text("Things to look at:", style = MaterialTheme.typography.labelLarge)
                        if (pendingAmounts > 0) Text("• $pendingAmounts Inbox purchase(s) with no amount yet (Wells Fargo online purchases)")
                        if (billsWaiting.isNotEmpty()) Text("• Bills with no actual yet: ${billsWaiting.joinToString { it.name }} " +
                            "(${money(billsWaiting.sumOf { it.estimateCents ?: 0 })} estimated)")
                        if (incomeWaiting.isNotEmpty()) Text("• Income with no actual yet: ${incomeWaiting.joinToString { it.name }}")
                        if (diff < 0 && unpaidCard != 0L) Text("• A card payment not recorded yet? ${money(unpaidCard)} is still on the card.")
                        Text("• Tips: a restaurant charge can settle higher than its alert said.")
                        Text("• Purchases not in the app at all (cash withdrawals, checks, transfers).")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDone) { Text("Close") } })
}
