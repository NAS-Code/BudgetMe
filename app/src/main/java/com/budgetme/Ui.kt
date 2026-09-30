package com.budgetme

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter

class MainActivity : ComponentActivity() {
    private val openTxn = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        openTxn.value = intent.getLongExtra("txn", -1).takeIf { it > 0 }
        setContent {
            val dark = androidx.compose.foundation.isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)) {
                Surface { App(openTxn) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getLongExtra("txn", -1).takeIf { it > 0 }?.let { openTxn.value = it }
    }
}

private val TABS = listOf("Inbox", "Month", "Categories", "Settings")

@Composable
fun App(openTxn: MutableState<Long?>) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).dao() }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        Brain.scope.launch { Brain.categories(ctx) }  // seeds categories + builds the model off the main thread
    }
    val cats by remember { dao.categories() }.collectAsState(initial = emptyList())

    Scaffold(bottomBar = {
        NavigationBar {
            TABS.forEachIndexed { i, t ->
                NavigationBarItem(selected = tab == i, onClick = { tab = i }, icon = { Text(t.take(1)) }, label = { Text(t) })
            }
        }
    }) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            when (tab) {
                0 -> InboxScreen(cats, onOpen = { openTxn.value = it })
                1 -> MonthScreen(cats)
                2 -> CategoriesScreen(cats)
                3 -> SettingsScreen()
            }
        }
    }
    openTxn.value?.let { id -> TxnDialog(id, cats) { openTxn.value = null } }
}

// ---------- Inbox ----------

@Composable
fun InboxScreen(cats: List<Category>, onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).dao() }
    val inbox by remember { dao.inbox() }.collectAsState(initial = emptyList())
    val recent by remember { dao.recent() }.collectAsState(initial = emptyList())
    val history by remember { dao.historyCount() }.collectAsState(initial = -1)
    var adding by remember { mutableStateOf(false) }
    val names = cats.associate { it.id to it.name }

    Scaffold(floatingActionButton = { FloatingActionButton(onClick = { adding = true }) { Text("+") } }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (history == 0) item {
                Card { Text("No history yet. Import data/labeled_transactions.csv in Settings so predictions start from your 5 years of taps.", Modifier.padding(16.dp)) }
            }
            item { Text("Needs review (${inbox.size})", style = MaterialTheme.typography.titleMedium) }
            if (inbox.isEmpty()) item { Text("All caught up.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(inbox, key = { it.id }) { t ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(t.id) }) {
                    Column(Modifier.padding(12.dp)) {
                        TxnLine(t, null)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (c in Choice.list(t.choices))
                                AssistChip(onClick = { Brain.scope.launch { Brain.file(ctx, t.id, c) } }, label = { Text(Brain.label(c)) })
                            AssistChip(onClick = { onOpen(t.id) }, label = { Text("More…") })
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)); Text("Recently filed", style = MaterialTheme.typography.titleMedium) }
            items(recent, key = { it.id }) { t ->
                Row(Modifier.fillMaxWidth().clickable { onOpen(t.id) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { TxnLine(t, names[t.categoryId]) }
                    if (t.status == "auto_filed") TextButton(onClick = { Brain.scope.launch { Brain.undo(ctx, t.id) } }) { Text("Undo") }
                }
            }
        }
    }
    if (adding) AddDialog { adding = false }
}

@Composable
fun TxnLine(t: Txn, category: String?) {
    Row {
        Text(t.merchant, Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Text(money(t.amountCents), fontWeight = FontWeight.Medium)
    }
    val date = LocalDate.parse(t.date).format(DateTimeFormatter.ofPattern("EEE MMM d"))
    val bits = listOfNotNull(date, t.account, category?.let { c -> if (t.funWeek != null) "$c · ${monthAbbr(t.budgetMonth)} Wk ${t.funWeek}" else c },
        if (t.status == "auto_filed") "auto" else null, t.note)
    Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun AddDialog(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var merchant by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var date by remember { mutableStateOf(LocalDate.now().toString()) }
    val cents = amount.toDoubleOrNull()?.let { Math.round(it * 100) }
    val d = runCatching { LocalDate.parse(date) }.getOrNull()
    AlertDialog(onDismissRequest = onDone, title = { Text("Add purchase") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(merchant, { merchant = it }, label = { Text("Merchant") }, singleLine = true)
                OutlinedTextField(amount, { amount = it }, label = { Text("Amount ($)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(date, { date = it }, label = { Text("Date (yyyy-mm-dd)") }, singleLine = true, isError = d == null)
            }
        },
        confirmButton = {
            TextButton(enabled = merchant.isNotBlank() && cents != null && d != null, onClick = {
                Brain.scope.launch { Brain.ingest(ctx, merchant.trim(), cents!!, d!!, null, merchant.trim(), "manual") }
                onDone()
            }) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } })
}

/** Full category list, Fun week, merchant name correction, note. */
@Composable
fun TxnDialog(id: Long, cats: List<Category>, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val t by produceState<Txn?>(null, id) { value = AppDb.get(ctx).dao().txn(id) }
    val txn = t ?: return
    val date = LocalDate.parse(txn.date)
    var merchant by remember(txn.id) { mutableStateOf(txn.merchant) }
    var note by remember(txn.id) { mutableStateOf(txn.note.orEmpty()) }
    var cat by remember(txn.id) { mutableStateOf(txn.categoryId ?: Choice.list(txn.choices).firstOrNull()?.categoryId) }
    val weeks = remember(txn.id) { funWeekOptions(date) }
    var week by remember(txn.id) { mutableStateOf(weeks.firstOrNull { it.week == txn.funWeek && it.budgetMonth == txn.budgetMonth } ?: weeks.last()) }
    val weekend = cats.firstOrNull { it.id == cat }?.period == "weekend_month"

    AlertDialog(onDismissRequest = onDone,
        title = { Text("${money(txn.amountCents)} · ${date.format(DateTimeFormatter.ofPattern("EEE MMM d"))}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(merchant, { merchant = it }, label = { Text("Merchant") }, singleLine = true,
                    supportingText = { if (merchant.trim() != txn.merchant) Text("Future \"${txn.merchant}\" alerts will be treated as this merchant") })
                if (txn.rawDescriptor != txn.merchant) Text("Bank: ${txn.rawDescriptor}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(note, { note = it }, label = { Text("Note") }, singleLine = true)
                Spacer(Modifier.height(8.dp))
                for (c in cats.filter { !it.archived || it.id == txn.categoryId }) Radio(c.name, cat == c.id) { cat = c.id }
                if (weekend) {
                    Text("Which weekend?", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    for (w in weeks) Radio("${monthAbbr(w.budgetMonth)} Wk ${w.week}", week == w) { week = w }
                }
                Text("tier: ${txn.tier ?: "-"} · confidence ${txn.confidence?.let { "%.2f".format(it) } ?: "-"}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = cat != null, onClick = {
                Brain.scope.launch { Brain.file(ctx, txn.id, Choice(cat!!, if (weekend) week else null), merchant = merchant, note = note.ifBlank { null }) }
                onDone()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { Brain.scope.launch { Brain.delete(ctx, txn.id) }; onDone() }) { Text("Delete") }
                TextButton(onClick = onDone) { Text("Cancel") }
            }
        })
}

@Composable
fun Radio(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, onClick = onClick).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick); Text(label)
    }
}

// ---------- Month (spreadsheet layout) ----------

@Composable
fun MonthScreen(cats: List<Category>) {
    val ctx = LocalContext.current
    var ym by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    val txns by remember(ym) { AppDb.get(ctx).dao().month(ym) }.collectAsState(initial = emptyList())
    val m = YearMonth.parse(ym)
    val shown = cats.filter { c -> !c.archived || txns.any { it.categoryId == c.id } }
    var budgetTotal = 0L; var spentTotal = 0L

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { ym = m.minusMonths(1).toString() }) { Text("‹") }
            Text(m.format(DateTimeFormatter.ofPattern("MMMM yyyy")), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { ym = m.plusMonths(1).toString() }) { Text("›") }
        }
        for (c in shown) {
            val mine = txns.filter { it.categoryId == c.id }
            val spent = mine.sumOf { it.amountCents }
            c.budgetCents?.let { budgetTotal += it }; spentTotal += spent
            Spacer(Modifier.height(12.dp))
            BudgetRow(c.name, spent, c.budgetCents, bold = true)
            if (c.period == "weekend_month") {
                val wk = weekends(m)
                wk.forEachIndexed { i, (sat, sun) ->
                    val per = c.budgetCents?.let { it / wk.size }  // ponytail: even split across weekends
                    BudgetRow("  Wk ${i + 1} · ${sat.dayOfMonth}–${sun.dayOfMonth}${if (sun.month != sat.month) " " + sun.month.name.take(3).lowercase() else ""}",
                        mine.filter { it.funWeek == i + 1 }.sumOf { it.amountCents }, per, bold = false)
                }
                val unweeked = mine.filter { it.funWeek == null }.sumOf { it.amountCents }
                if (unweeked != 0L) BudgetRow("  No week", unweeked, null, bold = false)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        BudgetRow("Variable spending", spentTotal, budgetTotal, bold = true)
        Text(if (budgetTotal >= spentTotal) "Surplus ${money(budgetTotal - spentTotal)}" else "Over by ${money(spentTotal - budgetTotal)}",
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text("Income and fixed bills arrive with the SimpleFIN sync (roadmap step 5-6).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 16.dp))
    }
}

@Composable
fun BudgetRow(label: String, spent: Long, budget: Long?, bold: Boolean) {
    Column(Modifier.padding(vertical = 3.dp)) {
        Row {
            Text(label, Modifier.weight(1f), fontWeight = if (bold) FontWeight.SemiBold else null)
            Text(if (budget != null) "${money(spent)} / ${money(budget)}" else money(spent), fontWeight = if (bold) FontWeight.SemiBold else null)
        }
        if (budget != null && budget > 0) {
            val over = spent > budget
            LinearProgressIndicator(progress = { (spent.toFloat() / budget).coerceIn(0f, 1f) }, Modifier.fillMaxWidth().padding(top = 2.dp),
                color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            Text(if (over) "${money(spent - budget)} over" else "${money(budget - spent)} left",
                style = MaterialTheme.typography.bodySmall, color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---------- Categories ----------

@Composable
fun CategoriesScreen(cats: List<Category>) {
    var editing by remember { mutableStateOf<Category?>(null) }
    Scaffold(floatingActionButton = {
        FloatingActionButton(onClick = { editing = Category("", "", "", null, "month", "auto", sort = cats.size) }) { Text("+") }
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(cats, key = { it.id }) { c ->
                Card(Modifier.fillMaxWidth().clickable { editing = c }) {
                    Column(Modifier.padding(12.dp)) {
                        Row {
                            Text(c.name + if (c.archived) " (archived)" else "", Modifier.weight(1f), fontWeight = FontWeight.Medium,
                                color = if (c.archived) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                            Text(c.budgetCents?.let { money(it) + if (c.period == "weekend_month") " / mo by weekend" else " / mo" } ?: "")
                        }
                        Text(c.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                }
            }
        }
    }
    editing?.let { CategoryDialog(it, cats) { editing = null } }
}

private fun slug(name: String, taken: Set<String>): String {
    val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "category" }
    return generateSequence(1) { it + 1 }.map { if (it == 1) base else "${base}_$it" }.first { it !in taken }
}

@Composable
fun CategoryDialog(c: Category, all: List<Category>, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val isNew = c.id.isEmpty()
    var name by remember { mutableStateOf(c.name) }
    var desc by remember { mutableStateOf(c.description) }
    var budget by remember { mutableStateOf(c.budgetCents?.let { "%.2f".format(it / 100.0) } ?: "") }
    var period by remember { mutableStateOf(c.period) }
    var predict by remember { mutableStateOf(c.predict) }
    var keywords by remember { mutableStateOf(c.keywords.replace("|", ", ")) }
    var requireKw by remember { mutableStateOf(c.requireKeyword) }
    var archived by remember { mutableStateOf(c.archived) }
    var mergeInto by remember { mutableStateOf<Category?>(null) }

    AlertDialog(onDismissRequest = onDone, title = { Text(if (isNew) "New category" else "Edit category") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                OutlinedTextField(desc, { desc = it }, label = { Text("Description (in your words; the LLM reads this)") }, minLines = 2)
                OutlinedTextField(budget, { budget = it }, label = { Text("Monthly budget ($, blank = none)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                Text("Budget period", style = MaterialTheme.typography.labelLarge)
                Radio("Calendar month", period == "month") { period = "month" }
                Radio("By weekend (Fun week rule)", period == "weekend_month") { period = "weekend_month" }
                Text("Prediction", style = MaterialTheme.typography.labelLarge)
                Radio("Auto: can be suggested", predict == "auto") { predict = "auto" }
                Radio("Fallback: the \"not sure\" button", predict == "fallback_only") { predict = "fallback_only" }
                Radio("Manual: only once a merchant's history shows it", predict == "manual_only") { predict = "manual_only" }
                OutlinedTextField(keywords, { keywords = it }, label = { Text("Keywords (comma separated)") })
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(requireKw, { requireKw = it }); Text("Only suggest for new merchants on a keyword match")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(archived, { archived = it }); Spacer(Modifier.width(8.dp)); Text("Archived (hidden from buttons, history kept)")
                }
                if (!isNew) {
                    Text("Merge into…", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (o in all.filter { it.id != c.id && !it.archived }) AssistChip(onClick = { mergeInto = o }, label = { Text(o.name) })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = {
                val saved = c.copy(
                    id = if (isNew) slug(name, all.map { it.id }.toSet()) else c.id,
                    name = name.trim(), description = desc.trim(),
                    budgetCents = budget.toDoubleOrNull()?.let { Math.round(it * 100) }, period = period, predict = predict,
                    keywords = keywords.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.joinToString("|"),
                    requireKeyword = requireKw, archived = archived)
                Brain.scope.launch { AppDb.get(ctx).dao().save(saved); Brain.reload(ctx) }
                onDone()
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDone) { Text("Cancel") } })

    mergeInto?.let { target ->
        AlertDialog(onDismissRequest = { mergeInto = null }, title = { Text("Merge ${c.name} into ${target.name}?") },
            text = { Text("Every transaction filed as ${c.name} moves to ${target.name}, and ${c.name} is archived. This can't be undone.") },
            confirmButton = { TextButton(onClick = { Brain.scope.launch { Brain.merge(ctx, c, target.id) }; mergeInto = null; onDone() }) { Text("Merge") } },
            dismissButton = { TextButton(onClick = { mergeInto = null }) { Text("Cancel") } })
    }
}

// ---------- Settings ----------

@Composable
fun SettingsScreen() {
    val ctx = LocalContext.current
    val dao = remember { AppDb.get(ctx).dao() }
    val prefs = remember { ctx.getSharedPreferences("settings", Context.MODE_PRIVATE) }
    var threshold by remember { mutableFloatStateOf(prefs.getFloat("auto_file", 0.95f)) }
    var status by remember { mutableStateOf("") }
    val history by remember { dao.historyCount() }.collectAsState(initial = 0)
    val alerts by remember { dao.alerts() }.collectAsState(initial = emptyList())
    var listening by remember { mutableStateOf(false) }
    LifecycleResumeEffectCompat { listening = ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx) }

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) Brain.scope.launch {
            status = runCatching {
                val text = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().readText()
                "Imported ${Brain.importHistory(ctx, text)} purchases."
            }.getOrElse { "Import failed: ${it.message}" }
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Bank alerts", style = MaterialTheme.typography.titleMedium)
        Text(if (listening) "Notification access is on. Chase, Wells Fargo and Venmo alerts are read as they arrive."
             else "Notification access is off, so bank alerts can't be read.")
        OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Notification access settings") }

        HorizontalDivider()
        Text("History", style = MaterialTheme.typography.titleMedium)
        Text("$history purchases imported. Import replaces them.")
        OutlinedButton(onClick = { pick.launch(arrayOf("text/*", "application/octet-stream")) }) { Text("Import labeled_transactions.csv") }
        if (status.isNotEmpty()) Text(status)

        HorizontalDivider()
        Text("Auto-file", style = MaterialTheme.typography.titleMedium)
        Text(if (threshold >= 1f) "Off: always ask." else "File automatically at confidence ≥ ${"%.2f".format(threshold)} (with Undo).")
        Slider(threshold, { threshold = Math.round(it * 100) / 100f; prefs.edit().putFloat("auto_file", threshold).apply() }, valueRange = 0.8f..1f, steps = 19)

        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Captured alerts (${alerts.size})", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            TextButton(enabled = alerts.isNotEmpty(), onClick = {
                val dump = alerts.joinToString("\n") { "${it.pkg}\t${if (it.txnId != null) "parsed" else "UNPARSED"}\t${it.title}\t${it.text}" }
                ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("alerts", dump))
            }) { Text("Copy all") }
        }
        Text("Every notification from the bank apps, raw. Share these to write exact per-bank parsers.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        for (a in alerts) Column {
            Text("${BANKS[a.pkg] ?: a.pkg} · ${if (a.txnId != null) "parsed" else "not parsed"}", style = MaterialTheme.typography.labelMedium)
            Text("${a.title} — ${a.text}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Re-check something whenever the screen resumes (e.g. coming back from system settings). */
@Composable
fun LifecycleResumeEffectCompat(block: () -> Unit) {
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) block() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
}
