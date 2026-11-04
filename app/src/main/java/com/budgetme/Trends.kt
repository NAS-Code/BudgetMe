package com.budgetme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.time.YearMonth
import java.time.format.DateTimeFormatter

/** Spending per budget month, oldest first. catId null = every category with a budget (what the Month tab totals). */
fun monthlyTotals(txns: List<Txn>, catIds: Set<String>, months: List<YearMonth>): List<Long> {
    val keys = months.map { it.toString() }.toSet()
    val by = txns.filter { it.categoryId in catIds && it.budgetMonth in keys }.groupBy { it.budgetMonth }
    return months.map { m -> by[m.toString()]?.sumOf { it.amountCents } ?: 0L }
}

@Composable
fun TrendsScreen(cats: List<Category>) {
    val ctx = LocalContext.current
    val all by remember { AppDb.get(ctx).dao().filedFlow() }.collectAsState(initial = emptyList())
    val active = cats.filter { !it.archived }
    var pick by rememberSaveable { mutableStateOf<String?>(null) }  // null = all budgeted categories
    var span by rememberSaveable { mutableIntStateOf(12) }
    val now = YearMonth.now()
    val months = (span - 1 downTo 0).map { now.minusMonths(it.toLong()) }
    val chosen = active.filter { if (pick == null) (it.budgetCents ?: 0) > 0 else it.id == pick }
    val totals = remember(all, chosen.map { it.id }, span) { monthlyTotals(all, chosen.map { it.id }.toSet(), months) }
    val budget = chosen.mapNotNull { it.budgetCents }.takeIf { it.size == chosen.size && it.isNotEmpty() }?.sum()
    var sel by remember(span) { mutableIntStateOf(months.lastIndex) }
    val past = totals.dropLast(1).filter { it > 0 }  // the current month is still running

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Trends", style = MaterialTheme.typography.headlineSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(pick == null, { pick = null }, label = { Text("All budgets") })
            for (c in active) FilterChip(pick == c.id, { pick = c.id }, label = { Text(c.name) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((n, label) in listOf(6 to "6 months", 12 to "1 year", 24 to "2 years"))
                FilterChip(span == n, { span = n }, label = { Text(label) })
        }
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text(months[sel].format(DateTimeFormatter.ofPattern("MMMM yyyy")), style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(money(totals[sel]), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                Text(listOfNotNull(budget?.let { "budget ${money(it)}" },
                    past.takeIf { it.isNotEmpty() }?.let { "average ${money(it.sum() / it.size)} a month" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BarChart(totals, budget, sel, Modifier.fillMaxWidth().height(180.dp).padding(top = 16.dp)) { sel = it }
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    months.forEachIndexed { i, m ->
                        val show = span <= 12 || i % 3 == 0 || i == months.lastIndex
                        Text(if (show) m.format(DateTimeFormatter.ofPattern("MMM")).take(if (span <= 6) 3 else 1) else "",
                            Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = if (i == sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (budget != null) Text("Dashed line: budget", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // the table view: every bar's number
        Text("By month", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        for (i in months.indices.reversed()) Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(months[i].format(DateTimeFormatter.ofPattern("MMM yyyy")), Modifier.weight(1f))
            Text(money(totals[i]), fontWeight = FontWeight.Medium)
            if (budget != null) Text(if (totals[i] > budget) "  ${money(totals[i] - budget)} over" else "  ${money(budget - totals[i])} under",
                Modifier.width(120.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** One series of monthly bars on a zero baseline; dashed budget line; tap a bar to select it. */
@Composable
fun BarChart(values: List<Long>, budget: Long?, selected: Int, modifier: Modifier, onSelect: (Int) -> Unit) {
    val bar = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val ref = MaterialTheme.colorScheme.onSurfaceVariant
    val top = maxOf(values.maxOrNull() ?: 0L, budget ?: 0L, 1L).toFloat() * 1.08f
    Canvas(modifier.semantics { contentDescription = "Monthly spending bar chart; the table below lists every month" }
        .pointerInput(values.size) { detectTapGestures { p -> onSelect((p.x / size.width * values.size).toInt().coerceIn(0, values.lastIndex)) } }) {
        val slot = size.width / values.size
        val w = (slot * 0.6f).coerceAtMost(28.dp.toPx())
        drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())  // baseline
        values.forEachIndexed { i, v ->
            val h = v / top * size.height
            if (h <= 0f) return@forEachIndexed
            val x = i * slot + (slot - w) / 2
            val r = minOf(4.dp.toPx(), w / 2, h)
            // rounded top, square at the baseline: the lower corners hang below the baseline and are clipped off
            clipRect(bottom = size.height) {
                drawRoundRect(if (i == selected) bar else dim, Offset(x, size.height - h), Size(w, h + r), CornerRadius(r, r))
            }
        }
        budget?.let { b ->
            val y = size.height - b / top * size.height
            drawLine(ref, Offset(0f, y), Offset(size.width, y), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
        }
    }
}
