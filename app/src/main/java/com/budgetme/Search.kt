package com.budgetme

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/** Date filter for Search. days = how far back; null = all time. */
enum class Since(val label: String, val days: Long?) { MONTH("30 days", 30), QUARTER("3 months", 91), YEAR("1 year", 365), ALL("All time", null) }

/** Search filter: text matches merchant or note (any case); empty category set = all; amounts in cents, inclusive. */
fun searchTxns(txns: List<Txn>, query: String, cats: Set<String>, since: LocalDate?, minCents: Long?, maxCents: Long?): List<Txn> {
    val q = query.trim()
    return txns.filter { t ->
        (q.isEmpty() || t.merchant.contains(q, true) || t.note?.contains(q, true) == true) &&
            (cats.isEmpty() || t.categoryId in cats) &&
            (since == null || t.date >= since.toString()) &&
            (minCents == null || t.amountCents >= minCents) && (maxCents == null || t.amountCents <= maxCents)
    }
}

@Composable
fun SearchScreen(cats: List<Category>, onOpen: (Long) -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val all by remember { AppDb.get(ctx).dao().filedFlow() }.collectAsState(initial = emptyList())
    var query by remember { mutableStateOf("") }
    var picked by remember { mutableStateOf(setOf<String>()) }
    var since by remember { mutableStateOf(Since.YEAR) }
    var min by remember { mutableStateOf("") }
    var max by remember { mutableStateOf("") }
    val names = cats.associate { it.id to it.name }
    val hits = remember(all, query, picked, since, min, max) {
        searchTxns(all, query, picked, since.days?.let { LocalDate.now().minusDays(it) }, parseCents(min), parseCents(max))
    }
    androidx.activity.compose.BackHandler(onBack = onClose)

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            OutlinedTextField(query, { query = it }, Modifier.weight(1f), placeholder = { Text("Merchant or note") }, singleLine = true,
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Clear, "Clear search") } })
        }
        LazyColumn(contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (s in Since.entries) FilterChip(since == s, { since = s }, label = { Text(s.label) })
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (c in cats.filter { !it.archived || it.id in picked })
                        FilterChip(c.id in picked, { picked = if (c.id in picked) picked - c.id else picked + c.id }, label = { Text(c.name) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(min, { min = it }, Modifier.weight(1f), label = { Text("Min $") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    OutlinedTextField(max, { max = it }, Modifier.weight(1f), label = { Text("Max $") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                Text("${hits.size} purchase${if (hits.size == 1) "" else "s"} · ${money(hits.sumOf { it.amountCents })}",
                    Modifier.padding(top = 12.dp, bottom = 4.dp), style = MaterialTheme.typography.titleMedium)
            }
            // ponytail: shows the newest 300; narrow the filters to see older ones
            items(hits.take(300), key = { it.id }) { t ->
                Column(Modifier.fillMaxWidth().clickable { onOpen(t.id) }.padding(vertical = 6.dp)) { TxnLine(t, names[t.categoryId]) }
            }
            if (hits.size > 300) item {
                Text("Showing the newest 300. Narrow the filters to see the rest.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
