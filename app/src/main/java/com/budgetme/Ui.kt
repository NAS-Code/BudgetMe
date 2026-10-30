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
import androidx.activity.result.IntentSenderRequest
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    override fun onResume() {
        super.onResume()
        Sheets.request(this, 0)  // pick up sheet edits whenever the app is opened
        Sheets.schedule(this)
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
        Brain.scope.launch { Brain.categories(ctx) }  // builds the model off the main thread
    }
    val cats by remember { dao.categories() }.collectAsState(initial = emptyList())
    var setUp by remember { mutableStateOf(Brain.isSetUp(ctx)) }
    if (!setUp) return SetupScreen { setUp = true }

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
                1 -> MonthScreen(cats, onOpen = { openTxn.value = it })
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
    val deposits by remember { dao.deposits() }.collectAsState(initial = emptyList())
    var clearDeposit by remember { mutableStateOf<Txn?>(null) }
    val recent by remember { dao.recent() }.collectAsState(initial = emptyList())
    val history by remember { dao.historyCount() }.collectAsState(initial = -1)
    var adding by remember { mutableStateOf(false) }
    var clearing by remember { mutableStateOf(false) }
    var clearOne by remember { mutableStateOf<Txn?>(null) }
    val names = cats.associate { it.id to it.name }
    var listening by remember { mutableStateOf(true) }
    LifecycleResumeEffectCompat { listening = ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx) }

    Scaffold(floatingActionButton = { FloatingActionButton(onClick = { adding = true }) { Text("+") } }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Off after a restore to a new phone (it's a system setting, not backed up), or if it was never turned on.
            if (!listening) item {
                Card(Modifier.fillMaxWidth().clickable { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                    Text("Notification access is off, so purchases aren't being caught. Tap to turn it on. " +
                        "New phone? See Settings > Switching phones.", Modifier.padding(16.dp))
                }
            }
            if (history == 0) item {
                Card { Text("No history yet. Predictions start from your category keywords and learn from every tap. Already track spending? Import a labeled CSV in Settings.", Modifier.padding(16.dp)) }
            }
            if (deposits.isNotEmpty()) item { Text("Deposits to file (${deposits.size})", style = MaterialTheme.typography.titleMedium) }
            items(deposits, key = { "d${it.id}" }) { t ->
                var month by remember(t.id) { mutableStateOf<String?>(null) }
                LaunchedEffect(t.id) { if (month == null) month = withContext(Dispatchers.IO) { Brain.depositMonth(ctx, t) } }
                val choices by produceState(emptyList<Line>(), t.id, month) {
                    value = month?.let { m -> withContext(Dispatchers.IO) { Brain.startMonthLines(ctx, m); Brain.depositChoices(ctx, t, m) } }.orEmpty()
                }
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Row {
                            Text("${t.merchant} · ${LocalDate.parse(t.date).format(DateTimeFormatter.ofPattern("EEE MMM d"))}", Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            Text("+" + money(t.amountCents), fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                        }
                        month?.let { m -> MonthDropdown(m, YearMonth.parse(t.budgetMonth).let { d -> listOf(d.minusMonths(1), d, d.plusMonths(1)).map { it.toString() } }) { month = it } }
                        Text("Which income line is this?", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (l in choices) AssistChip(onClick = { Brain.scope.launch { Brain.fileDeposit(ctx, t.id, l.id) } },
                                label = { Text(l.name + (l.actualCents?.takeIf { it != 0L }?.let { " (has ${money(it)})" } ?: "")) })
                            AssistChip(onClick = { Brain.scope.launch { Brain.fileDeposit(ctx, t.id, null, month) } }, label = { Text("+ New line") })
                            AssistChip(onClick = { clearDeposit = t }, label = { Text("Clear") })
                        }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Needs review (${inbox.size})", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    if (inbox.isNotEmpty()) TextButton(onClick = { clearing = true }) { Text("Clear all") }
                }
            }
            if (inbox.isEmpty()) item { Text("All caught up.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(inbox, key = { it.id }) { t ->
                Card(Modifier.fillMaxWidth().clickable { onOpen(t.id) }) {
                    Column(Modifier.padding(12.dp)) {
                        TxnLine(t, null)
                        val twin by produceState<Txn?>(null, t.id, inbox.size) { value = Brain.sheetTwin(ctx, t) }
                        twin?.let { tw ->
                            Text("Already in your sheet? \"${describe(tw.merchant, tw.note)}\" · ${names[tw.categoryId] ?: ""} · " +
                                if (maybeUndated(tw)) "${monthAbbr(tw.budgetMonth)}, no date" else LocalDate.parse(tw.date).format(DateTimeFormatter.ofPattern("MMM d")),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            TextButton(onClick = { Brain.scope.launch { Brain.mergeIntoSheet(ctx, t.id, tw.id) } }) { Text("Same purchase") }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            for (c in Choice.list(t.choices))
                                AssistChip(onClick = { Brain.scope.launch { Brain.file(ctx, t.id, c) } }, label = { Text(Brain.label(c)) })
                            AssistChip(onClick = { onOpen(t.id) }, label = { Text("More…") })
                            AssistChip(onClick = { clearOne = t }, label = { Text(if (t.account == "Venmo") "From Venmo balance" else "Clear") })
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
    if (clearing) AlertDialog(onDismissRequest = { clearing = false },
        title = { Text("Clear ${inbox.size} purchases?") },
        text = { Text("They're removed from the Inbox without a category: not added to your sheet, not counted in the Month view, " +
            "and not learned from. Use this when they're already in your sheet. Filed purchases aren't affected.") },
        confirmButton = { TextButton(onClick = { Brain.scope.launch { Brain.clearInbox(ctx) }; clearing = false }) { Text("Clear") } },
        dismissButton = { TextButton(onClick = { clearing = false }) { Text("Cancel") } })
    clearDeposit?.let { t ->
        AlertDialog(onDismissRequest = { clearDeposit = null },
            title = { Text("Clear the ${money(t.amountCents)} deposit?") },
            text = { Text("It's removed without being added to any income line. Use this if it's already recorded.") },
            confirmButton = { TextButton(onClick = { Brain.scope.launch { Brain.delete(ctx, t.id) }; clearDeposit = null }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { clearDeposit = null }) { Text("Cancel") } })
    }
    clearOne?.let { t ->
        AlertDialog(onDismissRequest = { clearOne = null },
            title = { Text("Clear ${amountText(t.amountCents)} · ${t.merchant}?") },
            text = { Text(if (t.account == "Venmo") "Paid from your Venmo balance, not your bank: it's removed and won't be counted in the app or your sheet."
                else "It's removed from the Inbox without a category: not added to your sheet and not learned from. Use this when it's already in your sheet.") },
            confirmButton = { TextButton(onClick = { Brain.scope.launch { Brain.delete(ctx, t.id) }; clearOne = null }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { clearOne = null }) { Text("Cancel") } })
    }
}

@Composable
fun TxnLine(t: Txn, category: String?) {
    Row {
        Text(t.merchant, Modifier.weight(1f), fontWeight = FontWeight.Medium)
        Text(amountText(t.amountCents), fontWeight = FontWeight.Medium)
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
    var amount by remember(txn.id) { mutableStateOf(if (txn.amountCents == 0L) "" else "%.2f".format(txn.amountCents / 100.0)) }
    val placeholder = txn.merchant in PLACEHOLDER_MERCHANTS
    var cat by remember(txn.id) { mutableStateOf(txn.categoryId ?: Choice.list(txn.choices).firstOrNull()?.categoryId) }
    val suggested = remember(txn.id) { funWeekOptions(date) }  // the Fun week rule's choice(s)
    var month by remember(txn.id) { mutableStateOf(txn.budgetMonth) }
    var monthPicked by remember(txn.id) { mutableStateOf(false) }
    val weekend = cats.firstOrNull { it.id == cat }?.period == "weekend_month"
    // Fun with the month not picked by hand: start on the month the weekend rule suggests
    LaunchedEffect(weekend) { if (weekend && !monthPicked && txn.funWeek == null) month = suggested.last().budgetMonth }
    val weeks = remember(month) { weekends(YearMonth.parse(month)).indices.map { FunWeek(month, it + 1) } }
    var week by remember(txn.id, month) { mutableStateOf(
        weeks.firstOrNull { it.week == txn.funWeek && it.budgetMonth == txn.budgetMonth } ?: suggested.lastOrNull { it.budgetMonth == month } ?: weeks.first()) }
    var confirmDelete by remember(txn.id) { mutableStateOf(false) }
    var bill by remember(txn.id, month) { mutableStateOf(BillChoice(month)) }

    AlertDialog(onDismissRequest = onDone,
        title = { Text("${amountText(txn.amountCents)} · ${date.format(DateTimeFormatter.ofPattern("EEE MMM d"))}") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(merchant, { merchant = it }, label = { Text("Merchant") }, singleLine = true,
                    supportingText = { if (placeholder) Text("The bank didn't say where. Type the merchant.") else if (merchant.trim() != txn.merchant) Text("Future \"${txn.merchant}\" alerts will be treated as this merchant") })
                if (txn.rawDescriptor != txn.merchant) Text("Bank: ${txn.rawDescriptor}", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(amount, { amount = it }, label = { Text("Amount ($)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(note, { note = it }, label = { Text("Note") }, singleLine = true)
                if (isCard(txn)) {
                    var paid by remember(txn.id) { mutableStateOf(txn.cardPaid) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(paid, { on -> paid = on; Brain.scope.launch { Brain.setCardPaid(ctx, listOf(txn.id), on) } })
                        Text("Card paid off (counts toward the Wells Fargo balance)")
                    }
                }
                MonthDropdown(month, YearMonth.from(date).let { d -> listOf(d.minusMonths(1), d, d.plusMonths(1)).map { it.toString() } + txn.budgetMonth }) {
                    month = it; monthPicked = true
                }
                Spacer(Modifier.height(8.dp))
                for (c in cats.filter { !it.archived || it.id == txn.categoryId }) Radio(c.name, cat == c.id) { cat = c.id }
                Radio("Monthly bill…", cat == BILL) { cat = BILL }
                if (cat == BILL) BillPicker(txn, bill) { bill = it }
                if (weekend) {
                    Text("Which weekend?", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                    val ym = YearMonth.parse(month)
                    for ((w, days) in weeks.zip(weekends(ym))) Radio("Wk ${w.week} · ${days.first.format(DateTimeFormatter.ofPattern("MMM d"))}–" +
                        days.second.format(DateTimeFormatter.ofPattern("d")) + if (w in suggested) "  (suggested)" else "", week == w) { week = w }
                }
                Text("tier: ${txn.tier ?: "-"} · confidence ${txn.confidence?.let { "%.2f".format(it) } ?: "-"}",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = {
            TextButton(enabled = cat != null && (cat != BILL || bill.ready), onClick = {
                val cents = amount.toDoubleOrNull()?.let { Math.round(it * 100) }
                Brain.scope.launch {
                    if (cat == BILL) Brain.fileAsBill(ctx, txn.id, bill.month, bill.line?.id, bill.newName.trim(), cents)
                    else Brain.file(ctx, txn.id, Choice(cat!!, if (weekend) week else null), merchant = merchant, note = note.ifBlank { null },
                        cents = cents, budgetMonth = month)
                }
                onDone()
            }) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmDelete = true }) { Text("Delete") }
                TextButton(onClick = onDone) { Text("Cancel") }
            }
        })
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        title = { Text("Delete ${amountText(txn.amountCents)} · ${txn.merchant}?") },
        text = { Text(if (filed(txn)) "It's removed from the app, from your sheet (if it's there), and from what the app has learned. This can't be undone."
            else "It's removed from the Inbox. This can't be undone.") },
        confirmButton = { TextButton(onClick = { Brain.scope.launch { Brain.delete(ctx, txn.id) }; confirmDelete = false; onDone() }) { Text("Delete") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } })
}

@Composable
fun Radio(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected, onClick = onClick).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick); Text(label)
    }
}

// ---------- Month (spreadsheet layout) ----------

@Composable
fun MonthScreen(cats: List<Category>, onOpen: (Long) -> Unit) {
    val ctx = LocalContext.current
    var ym by rememberSaveable { mutableStateOf(YearMonth.now().toString()) }
    val dao = remember { AppDb.get(ctx).dao() }
    val txns by remember(ym) { dao.month(ym) }.collectAsState(initial = emptyList())
    val lines by remember(ym) { dao.lines(ym) }.collectAsState(initial = emptyList())
    val unfiled by remember(ym) { dao.unfiledBank(ym) }.collectAsState(initial = emptyList())
    val unpaidTxns by remember { dao.unpaidCard() }.collectAsState(initial = emptyList())
    val unpaidLines by remember { dao.unpaidCardLines() }.collectAsState(initial = emptyList())
    val unpaid = cardItems(unpaidTxns, unpaidLines)
    LaunchedEffect(ym) { Brain.scope.launch { Brain.startMonthLines(ctx, ym) } }
    val bal by produceState<Balance?>(null, ym, txns, lines, unfiled, unpaidTxns) { value = withContext(Dispatchers.IO) { Brain.monthBalance(ctx, ym) } }
    val bankCheck by produceState<Brain.BankCheck?>(null, ym, bal) { value = withContext(Dispatchers.IO) { Brain.bankCheck(ctx, ym) } }
    var paying by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    val m = YearMonth.parse(ym)
    val shown = cats.filter { c -> !c.archived || txns.any { it.categoryId == c.id } }
    var budgetTotal = 0L; var spentTotal = 0L
    val open = remember(ym) { mutableStateListOf<String>() }  // expanded rows
    @Composable fun SectionRow(key: String, label: String, charges: List<Txn>, budget: Long?, bold: Boolean) {
        val isOpen = key in open
        BudgetRow(label, charges.sumOf { it.amountCents }, budget, bold, isOpen) { if (isOpen) open -= key else open += key }
        if (isOpen) Charges(charges, onOpen)
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { ym = m.minusMonths(1).toString() }) { Text("‹") }
            Text(m.format(DateTimeFormatter.ofPattern("MMMM yyyy")), Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = { ym = m.plusMonths(1).toString() }) { Text("›") }
        }
        BalanceCard(bal, unpaid.sumOf { it.cents }, ym == YearMonth.now().toString(), { paying = true }, { checking = true }, bankCheck)
        LinesBlock("Income", "income", ym, lines, bal)
        LinesBlock("Monthly bills", "fixed", ym, lines)
        Text("Spending", fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        for (c in shown) {
            val mine = txns.filter { it.categoryId == c.id }
            val spent = mine.sumOf { it.amountCents }
            c.budgetCents?.let { budgetTotal += it }; spentTotal += spent
            Spacer(Modifier.height(12.dp))
            SectionRow(c.id, c.name, mine, c.budgetCents, bold = true)
            if (c.period == "weekend_month") {
                val wk = weekends(m)
                wk.forEachIndexed { i, (sat, sun) ->
                    val per = c.budgetCents?.let { it / wk.size }  // ponytail: even split across weekends
                    SectionRow("${c.id}#${i + 1}", "  Wk ${i + 1} · ${sat.dayOfMonth}–${sun.dayOfMonth}${if (sun.month != sat.month) " " + sun.month.name.take(3).lowercase() else ""}",
                        mine.filter { it.funWeek == i + 1 }, per, bold = false)
                }
                val unweeked = mine.filter { it.funWeek == null }
                if (unweeked.isNotEmpty()) SectionRow("${c.id}#none", "  No week", unweeked, null, bold = false)
            }
        }
        HorizontalDivider(Modifier.padding(vertical = 16.dp))
        BudgetRow("Variable spending", spentTotal, budgetTotal, bold = true)
        Text("Tap a row to see its charges.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(if (budgetTotal >= spentTotal) "Surplus ${money(budgetTotal - spentTotal)}" else "Over by ${money(spentTotal - budgetTotal)}",
            style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
    }
    if (paying) CardPaymentDialog(unpaid) { paying = false }
    if (checking) bal?.let { BankCheckDialog(it, ym, lines, unfiled, unpaid.sumOf { u -> u.cents }) { checking = false } }
}

@Composable
fun BudgetRow(label: String, spent: Long, budget: Long?, bold: Boolean, expanded: Boolean? = null, onClick: (() -> Unit)? = null) {
    Column((if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 3.dp)) {
        Row {
            Text((expanded?.let { if (it) "▾ " else "▸ " } ?: "") + label, Modifier.weight(1f), fontWeight = if (bold) FontWeight.SemiBold else null)
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

/** The charges behind a Month row, oldest first; tap one to open it. */
@Composable
fun Charges(charges: List<Txn>, onOpen: (Long) -> Unit) {
    if (charges.isEmpty()) Text("No charges yet.", Modifier.padding(start = 16.dp, bottom = 6.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    for (t in charges.sortedWith(compareBy({ it.date }, { it.id }))) Row(
        Modifier.fillMaxWidth().clickable { onOpen(t.id) }.padding(start = 16.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(LocalDate.parse(t.date).format(DateTimeFormatter.ofPattern("MMM d")), Modifier.width(52.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(Modifier.weight(1f)) {
            Text(describe(t.merchant, t.note), style = MaterialTheme.typography.bodyMedium)
            t.account?.let { Text(it + when {
                    !isCard(t) || t.cardPaid -> ""
                    bankPart(t) > 0 -> " · ${money(bankPart(t))} of ${money(t.amountCents)} paid off"
                    else -> " · on card, not paid off"
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(amountText(t.amountCents), style = MaterialTheme.typography.bodyMedium)
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
    val prefs = remember { Brain.prefs(ctx) }
    var threshold by remember { mutableFloatStateOf(Brain.threshold(ctx).toFloat()) }
    var light by remember { mutableStateOf(Brain.isLight(ctx)) }
    var banks by remember { mutableStateOf(Brain.enabledBanks(ctx)) }
    val secrets = remember { Brain.secrets(ctx) }
    var apiKey by remember { mutableStateOf(secrets.getString("api_key", "").orEmpty()) }
    var keyDraft by remember { mutableStateOf<String?>(null) }  // non-null while editing
    var deletingKey by remember { mutableStateOf(false) }
    var sheetLink by remember { mutableStateOf(Sheets.sheetId(ctx)?.let { "https://docs.google.com/spreadsheets/d/$it" }.orEmpty()) }
    var connected by remember { mutableStateOf(prefs.getBoolean("google_connected", false)) }
    var sheetStatus by remember { mutableStateOf(prefs.getString("sheet_status", "").orEmpty()) }
    fun onConnected(ok: Boolean) {
        connected = ok; prefs.edit().putBoolean("google_connected", ok).apply()
        if (ok) { Sheets.schedule(ctx); Brain.scope.launch { sheetStatus = Sheets.sync(ctx, Sheets.Mode.PREVIEW) } }
    }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
        onConnected(runCatching { GoogleAuth.client(ctx).getAuthorizationResultFromIntent(res.data).accessToken != null }.getOrDefault(false))
    }
    fun connect() {
        GoogleAuth.authorize(ctx)
            .addOnSuccessListener { r ->
                if (r.hasResolution()) consent.launch(IntentSenderRequest.Builder(r.pendingIntent!!.intentSender).build()) else onConnected(true)
            }
            .addOnFailureListener { sheetStatus = "Couldn't connect: ${it.message}" }
    }
    var status by remember { mutableStateOf("") }
    var alertStatus by remember { mutableStateOf("") }
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

    val parsed = alerts.count { it.txnId != null }
    val sheetOn = connected && Sheets.idFrom(sheetLink) != null
    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingsSection("Bank alerts", if (listening) "On · ${banks.size} apps" else "Notification access is off") {
            Text(if (listening) "Notification access is on. Alerts from the apps below are read as they arrive."
                 else "Notification access is off, so bank alerts can't be read.")
            OutlinedButton(onClick = { ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) { Text("Notification access settings") }
            BankPicker(banks) { banks = it; prefs.edit().putStringSet("banks", it).apply() }
        }

        SettingsSection("How much it asks",
            (if (light) "Light" else "Strict") + " · " + (if (threshold >= 1f) "auto-file off" else "auto-file at ${"%.2f".format(threshold)}")) {
            ModePicker(light) {
                light = it; threshold = Brain.defaultThreshold(it)
                prefs.edit().putString("mode", if (it) "light" else "strict").putFloat("auto_file", threshold).apply()
                Brain.scope.launch { Brain.refreshReview(ctx) }
            }
            Text("Auto-file", style = MaterialTheme.typography.labelLarge)
            Text(if (threshold >= 1f) "Off: always ask." else "File automatically at confidence ≥ ${"%.2f".format(threshold)} (undo from the Inbox).")
            Slider(threshold, { threshold = Math.round(it * 100) / 100f; prefs.edit().putFloat("auto_file", threshold).apply() }, valueRange = 0.5f..1f, steps = 49)
        }

        var pay by remember { mutableStateOf(Brain.paySchedule(ctx)) }
        var payday by remember { mutableStateOf(Brain.payAnchor(ctx)?.toString().orEmpty()) }
        SettingsSection("Pay schedule", pay?.label ?: "Not set") {
            PayPicker(pay, payday) { s, a ->
                pay = s; payday = a
                Brain.setPaySchedule(ctx, s, runCatching { LocalDate.parse(a.trim()) }.getOrNull())
            }
        }

        SettingsSection("New merchants (Claude)", if (apiKey.isBlank()) "No API key" else "API key saved") {
            Text("With an Anthropic API key, merchants the app has never seen are identified by Claude instead of keywords. " +
                "Only the merchant, amount, date, account and payment note are sent, plus your category descriptions and example merchant names.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            // Locked until Edit, so the saved key can't be changed by a stray tap.
            OutlinedTextField(keyDraft ?: apiKey, { keyDraft = it }, label = { Text("Anthropic API key") }, readOnly = keyDraft == null,
                singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val draft = keyDraft
                if (draft != null) {
                    Button(enabled = draft.isNotBlank(), onClick = {
                        apiKey = draft.trim(); secrets.edit().putString("api_key", apiKey).apply(); keyDraft = null
                    }) { Text("Save") }
                    OutlinedButton(onClick = { keyDraft = null }) { Text("Cancel") }
                } else {
                    OutlinedButton(onClick = { keyDraft = "" }) { Text(if (apiKey.isBlank()) "Add key" else "Edit") }
                    if (apiKey.isNotBlank()) OutlinedButton(onClick = { deletingKey = true }) { Text("Delete") }
                }
            }
        }
        if (deletingKey) AlertDialog(onDismissRequest = { deletingKey = false },
            title = { Text("Delete your API key?") },
            text = { Text("New merchants will go back to keyword guesses until you add a key again.") },
            confirmButton = { TextButton(onClick = {
                apiKey = ""; secrets.edit().remove("api_key").apply(); deletingKey = false
            }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deletingKey = false }) { Text("Cancel") } })

        SettingsSection("Google Sheet", if (sheetOn) "Connected" else "Not connected") {
            Text("Purchases you file are written into your budget sheet's month tab, and edits, additions and deletions you make in " +
                "the sheet come back to the app. Last, this and next month sync; a new month's tab is copied from TEMPLATE. " +
                "Categories without a section in the sheet stay in the app only. Column A holds the app's row ids (#123).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(sheetLink, { v ->
                sheetLink = v
                Sheets.idFrom(v).let { id -> prefs.edit().apply { if (id != null) putString("sheet_id", id) else remove("sheet_id") }.apply() }
            }, label = { Text("Spreadsheet link") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                isError = sheetLink.isNotBlank() && Sheets.idFrom(sheetLink) == null)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { connect() }) { Text(if (connected) "Reconnect Google" else "Connect Google account") }
                OutlinedButton(enabled = sheetOn, onClick = {
                    sheetStatus = "Checking…"; Brain.scope.launch { sheetStatus = Sheets.sync(ctx, Sheets.Mode.PREVIEW) }
                }) { Text("Preview") }
                OutlinedButton(enabled = sheetOn, onClick = {
                    sheetStatus = "Syncing…"; Brain.scope.launch { sheetStatus = Sheets.sync(ctx, Sheets.Mode.MANUAL) }
                }) { Text("Sync now") }
            }
            if (sheetStatus.isNotEmpty()) Text(sheetStatus, style = MaterialTheme.typography.bodySmall)
            prefs.getString("sheet_note", null)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }

        SettingsSection("History", "$history purchases imported") {
            Text("Import replaces them.")
            OutlinedButton(onClick = { pick.launch(arrayOf("text/*", "application/octet-stream")) }) { Text("Import labeled_transactions.csv") }
            if (status.isNotEmpty()) Text(status)
        }

        SettingsSection("Switching phones", "Backup and new-phone checklist") {
            Text("Purchases, categories and settings back up to your Google account about once a day (Android Settings > " +
                "Google > Backup; tap \"Back up now\" on the old phone before you switch). Restore that backup when you set up the " +
                "new phone, then install BudgetMe from the same computer (docs/SWITCHING_PHONES.md) and it comes back with your data. " +
                "Your Google Sheet is a second copy either way.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("On the new phone:", style = MaterialTheme.typography.labelLarge)
            TurnOnChecklist(banks, sheetDone = sheetOn, keyDone = apiKey.isNotBlank())
        }

        SettingsSection("Captured alerts", "${alerts.size} alerts · ${alerts.size - parsed} not parsed") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { Brain.scope.launch { alertStatus = "Parsed ${Brain.reparse(ctx)} more alerts." } }) { Text("Parse again") }
                OutlinedButton(enabled = alerts.isNotEmpty(), onClick = {
                    val dump = alerts.joinToString("\n") { "${it.pkg}\t${if (it.txnId != null) "parsed" else "UNPARSED"}\t${it.title}\t${it.text}" }
                    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("alerts", dump))
                }) { Text("Copy all") }
            }
            if (alertStatus.isNotEmpty()) Text(alertStatus)
            Text("Every notification from the bank apps, raw. Share these to write exact per-bank parsers.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            for (a in alerts) Column {
                Text("${BANKS[a.pkg]?.label ?: a.pkg} · ${if (a.txnId != null) "parsed" else "not parsed"}", style = MaterialTheme.typography.labelMedium)
                Text("${a.title} — ${a.text}", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Settings group: a tappable header with a one-line summary; the controls show when it's open. Starts closed. */
@Composable
fun SettingsSection(title: String, summary: String, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(title) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 10.dp)) {
        Text((if (open) "▾ " else "▸ ") + title, style = MaterialTheme.typography.titleMedium)
        if (!open) Text(summary, Modifier.padding(start = 18.dp), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open) Column(Modifier.padding(start = 18.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
    HorizontalDivider()
}

/** Checklist line: true = done, false = still to do, null = the app can't tell. action: button that opens the right place. */
@Composable
fun Check(done: Boolean?, text: String, action: Pair<String, () -> Unit>? = null) = Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(when (done) { true -> "✓"; false -> "○"; null -> "•" },
        color = if (done == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    Column(Modifier.weight(1f)) {
        Text(text, style = MaterialTheme.typography.bodyMedium)
        if (action != null && done != true) TextButton(onClick = action.second, contentPadding = PaddingValues(0.dp)) { Text(action.first) }
    }
}

/**
 * Everything outside the app that has to be on for it to work. Shown at the end of first-run setup and in
 * Settings > Switching phones. sheetDone / keyDone: null = optional, set up later in Settings.
 */
@Composable
fun TurnOnChecklist(banks: Set<String>, sheetDone: Boolean?, keyDone: Boolean?) {
    val ctx = LocalContext.current
    var listening by remember { mutableStateOf(false) }
    var notifs by remember { mutableStateOf(false) }
    LifecycleResumeEffectCompat {
        listening = ctx.packageName in NotificationManagerCompat.getEnabledListenerPackages(ctx)
        notifs = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }
    Check(notifs, "Notifications allowed, so BudgetMe can ask you about each purchase", "Allow notifications" to {
        ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName))
    })
    Check(listening, "Notification access on, so BudgetMe can read your bank alerts", "Turn on notification access" to {
        ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    })
    // Android won't show another app's settings, so a bank is ticked once a notification from it has actually arrived.
    val heard by remember { AppDb.get(ctx).dao().alertApps() }.collectAsState(initial = emptyList())
    for (pkg in banks) {
        val name = BANKS[pkg]?.label ?: pkg
        val open = ctx.packageManager.getLaunchIntentForPackage(pkg)
        Check(if (pkg in heard) true else null, "$name: purchase alerts on, as push notifications, at the lowest amount" +
            if (pkg in heard) "" else ". Ticks after the first $name alert arrives.",
            open?.let { "Open $name" to { ctx.startActivity(it) } })
    }
    Check(sheetDone, "Optional: Google Sheet linked and Google account connected (Settings > Google Sheet)")
    Check(keyDone, "Optional: Anthropic API key, for merchants the app has never seen (Settings > New merchants)")
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

// ---------- Setup ----------

private fun installed(ctx: Context, pkg: String) = runCatching { ctx.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)

@Composable
fun ModePicker(light: Boolean, onChange: (Boolean) -> Unit) {
    Radio("Light: files purchases it's confident about without asking, and keeps one quiet \"N to review\" reminder for the rest", light) { onChange(true) }
    Radio("Strict: a notification with category buttons for every purchase it isn't sure about", !light) { onChange(false) }
}

@Composable
fun BankPicker(selected: Set<String>, onChange: (Set<String>) -> Unit) {
    val ctx = LocalContext.current
    val apps = remember { BANKS.entries.sortedByDescending { installed(ctx, it.key) }.map { it to installed(ctx, it.key) } }
    for ((e, inst) in apps) Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(e.key in selected, { on -> onChange(if (on) selected + e.key else selected - e.key) })
        Column {
            Text(e.value.label)
            val notes = listOfNotNull(if (!inst) "not installed" else null, if (!e.value.tested) "alert format not tested yet" else null)
            if (notes.isNotEmpty()) Text(notes.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** First run. On an install that already has categories (from before setup existed), only mode and banks are asked. */
@Composable
fun SetupScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val existing by produceState<Boolean?>(null) { value = AppDb.get(ctx).dao().categoryList().isNotEmpty() }
    val hasCategories = existing ?: return
    var template by remember { mutableStateOf("starter_categories.json") }
    var light by remember { mutableStateOf(!hasCategories) }
    var banks by remember { mutableStateOf(if (hasCategories) Brain.enabledBanks(ctx) else BANKS.keys.filter { installed(ctx, it) }.toSet()) }
    var pay by remember { mutableStateOf<PaySchedule?>(null) }
    var payday by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    // Step 2: choices are saved; walk through what has to be turned on outside the app.
    if (saved) return Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp).systemBarsPadding(),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Turn these on", style = MaterialTheme.typography.headlineSmall)
        Text("BudgetMe works from your bank's purchase alerts, so a few switches outside the app need to be on. " +
            "Each one opens the right place; come back here when it's done.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        TurnOnChecklist(banks, sheetDone = null, keyDone = null)
        Button(modifier = Modifier.padding(top = 8.dp), onClick = onDone) { Text("Start using BudgetMe") }
        Text("This list is also in Settings > Switching phones.", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp).systemBarsPadding(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Set up BudgetMe", style = MaterialTheme.typography.headlineSmall)
        Text("Every purchase gets filed into your own categories, as it happens. Everything stays on this phone.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (!hasCategories) {
            Text("Categories", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            Radio("Starter: Groceries, Dining Out, Transportation, Shopping, Fun, Miscellaneous", template == "starter_categories.json") { template = "starter_categories.json" }
            Radio("Weekend budgeter: food and fun split by occasion, with the Fun budget tracked weekend by weekend", template == "categories.json") { template = "categories.json" }
            Text("You can rename, add, merge or archive categories any time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("How much should it ask?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        ModePicker(light) { light = it }
        Text("How often are you paid?", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        PayPicker(pay, payday) { s, a -> pay = s; payday = a }
        Text("Bank apps to listen to", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
        Text("Purchase alerts must be turned on in each bank's own app.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BankPicker(banks) { banks = it }
        Button(enabled = !saving, modifier = Modifier.padding(top = 8.dp), onClick = {
            saving = true
            pay?.let { Brain.setPaySchedule(ctx, it, runCatching { LocalDate.parse(payday.trim()) }.getOrNull()) }
            scope.launch { withContext(Dispatchers.IO) { Brain.setup(ctx, template, light, banks) }; saved = true }
        }) { Text("Next") }
    }
}
