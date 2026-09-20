package com.example.sidekick

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AddCard
import androidx.compose.material.icons.filled.AddShoppingCart
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.example.sidekick.ui.theme.SidekickTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs
import kotlin.math.max

// ─── Data Models ─────────────────────────────────────────────────────────────

data class Transaction(
    val id: String = UUID.randomUUID().toString(),
    val description: String,
    val amount: Double,
    val category: String,
    val date: String          // timestamp
)

/** category → spending limit (positive dollar value) */
data class Goal(
    val category: String,
    val limit: Double
)

/** a spending category with a user-chosen emoji */
data class CategoryInfo(
    val name: String,
    val emoji: String
)

/** budget list entry — tracks when it was created / last touched, for "most recent" sorting */
data class BudgetMeta(
    val id: String,
    val name: String,
    val timestamp: Long
)

/** app-wide (not per-budget) rent tracking */
data class RentInfo(
    val monthlyAmount: Double,
    val dueDay: Int,
    val lastPaidMonth: String,   // "yyyy-MM", empty if never paid
    val details: String = ""
)

/** app-wide (not per-budget) debt tracking */
data class DebtItem(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val startingBalance: Double,
    val currentBalance: Double
)

// ─── CSV Helpers ──────────────────────────────────────────────────────────────

object CsvStore {
    private const val BUDGET_INDEX = "budgets_index.csv"
    private const val GLOBAL_RENT_FILE = "global_rent.csv"
    private const val GLOBAL_DEBTS_FILE = "global_debts.csv"

    private fun dir(context: android.content.Context): File =
        File(context.filesDir, "budgets").also { it.mkdirs() }

    private fun indexFile(context: android.content.Context) =
        File(dir(context), BUDGET_INDEX)

    private fun budgetFile(context: android.content.Context, id: String) =
        File(dir(context), "$id.csv")

    private fun goalsFile(context: android.content.Context, id: String) =
        File(dir(context), "${id}_goals.csv")

    private fun categoriesFile(context: android.content.Context, id: String) =
        File(dir(context), "${id}_categories.csv")

    private fun globalRentFile(context: android.content.Context) =
        File(dir(context), GLOBAL_RENT_FILE)

    private fun globalDebtsFile(context: android.content.Context) =
        File(dir(context), GLOBAL_DEBTS_FILE)

    // ── Budget index ──────────────────────────────────────────────────────

    fun loadBudgetList(context: android.content.Context): List<BudgetMeta> {
        val f = indexFile(context)
        if (!f.exists()) return emptyList()
        return f.readLines().drop(1).filter { it.isNotBlank() }.mapNotNull {
            val cols = splitCsvLine(it)
            if (cols.size >= 2) {
                val id        = cols[0].trim()
                val name      = cols[1].trim()
                val timestamp = cols.getOrNull(2)?.trim()?.toLongOrNull() ?: 0L
                BudgetMeta(id = id, name = name, timestamp = timestamp)
            } else null
        }
    }

    fun saveBudgetList(context: android.content.Context, budgets: List<BudgetMeta>) {
        val sb = StringBuilder("id,name,timestamp\n")
        budgets.forEach { b -> sb.append("${b.id},${b.name.escapeCsv()},${b.timestamp}\n") }
        indexFile(context).writeText(sb.toString())
    }

    /** Bumps a budget's timestamp to now (e.g. after adding/removing a transaction) so it sorts as "most recent". */
    fun touchBudget(context: android.content.Context, budgetId: String) {
        val list    = loadBudgetList(context)
        val updated = list.map { if (it.id == budgetId) it.copy(timestamp = System.currentTimeMillis()) else it }
        saveBudgetList(context, updated)
    }

    // ── Transactions ──────────────────────────────────────────────────────

    fun loadTransactions(context: android.content.Context, budgetId: String): List<Transaction> {
        val f = budgetFile(context, budgetId)
        if (!f.exists()) return emptyList()
        return f.readLines().drop(1).filter { it.isNotBlank() }.mapNotNull { parseTxLine(it) }
    }

    fun saveTransactions(
        context: android.content.Context,
        budgetId: String,
        transactions: List<Transaction>
    ) {
        val sb = StringBuilder("id,description,amount,category,date\n")
        transactions.forEach { t ->
            sb.append("${t.id},${t.description.escapeCsv()},${t.amount},${t.category.escapeCsv()},${t.date}\n")
        }
        budgetFile(context, budgetId).writeText(sb.toString())
    }

    fun deleteBudgetFile(context: android.content.Context, budgetId: String) {
        budgetFile(context, budgetId).delete()
        goalsFile(context, budgetId).delete()
        categoriesFile(context, budgetId).delete()
    }

    // ── Goals ─────────────────────────────────────────────────────────────

    fun loadGoals(context: android.content.Context, budgetId: String): List<Goal> {
        val f = goalsFile(context, budgetId)
        if (!f.exists()) return emptyList()
        return f.readLines().drop(1).filter { it.isNotBlank() }.mapNotNull {
            val cols = it.split(",", limit = 2)
            if (cols.size == 2) {
                val limit = cols[1].trim().toDoubleOrNull() ?: return@mapNotNull null
                Goal(category = cols[0].trim(), limit = limit)
            } else null
        }
    }

    fun saveGoals(context: android.content.Context, budgetId: String, goals: List<Goal>) {
        val sb = StringBuilder("category,limit\n")
        goals.forEach { g -> sb.append("${g.category.escapeCsv()},${g.limit}\n") }
        goalsFile(context, budgetId).writeText(sb.toString())
    }

    // ── Categories ────────────────────────────────────────────────────────

    fun loadCategories(context: android.content.Context, budgetId: String): List<CategoryInfo> {
        val f = categoriesFile(context, budgetId)
        if (!f.exists()) return DEFAULT_CATEGORY_INFOS
        val lines = f.readLines().drop(1).filter { it.isNotBlank() }
        if (lines.isEmpty()) return DEFAULT_CATEGORY_INFOS
        return lines.mapNotNull {
            val cols = it.split(",", limit = 2)
            val name = cols.getOrNull(0)?.trim() ?: return@mapNotNull null
            val emoji = cols.getOrNull(1)?.trim()?.takeIf { e -> e.isNotEmpty() } ?: categoryEmoji(name)
            CategoryInfo(name = name, emoji = emoji)
        }
    }

    fun saveCategories(context: android.content.Context, budgetId: String, categories: List<CategoryInfo>) {
        val sb = StringBuilder("name,emoji\n")
        categories.forEach { sb.append("${it.name.escapeCsv()},${it.emoji.escapeCsv()}\n") }
        categoriesFile(context, budgetId).writeText(sb.toString())
    }

    // ── Global Rent ───────────────────────────────────────────────────────

    fun loadGlobalRentInfo(context: android.content.Context): RentInfo? {
        val f = globalRentFile(context)
        if (!f.exists()) return null
        val line = f.readLines().drop(1).firstOrNull { it.isNotBlank() } ?: return null
        val cols = splitCsvLine(line)
        if (cols.size < 4) return null
        return try {
            RentInfo(
                monthlyAmount = cols[0].toDouble(),
                dueDay        = cols[1].toInt(),
                lastPaidMonth = cols[2],
                details      = cols[3]
            )
        } catch (e: Exception) { null }
    }

    fun saveGlobalRentInfo(context: android.content.Context, info: RentInfo) {
        val sb = StringBuilder("amount,dueDay,lastPaidMonth,details\n")
        sb.append("${info.monthlyAmount},${info.dueDay},${info.lastPaidMonth},${info.details.escapeCsv()}\n")
        globalRentFile(context).writeText(sb.toString())
    }

    // ── Global Debts ──────────────────────────────────────────────────────

    fun loadGlobalDebts(context: android.content.Context): List<DebtItem> {
        val f = globalDebtsFile(context)
        if (!f.exists()) return emptyList()
        return f.readLines().drop(1).filter { it.isNotBlank() }.mapNotNull {
            val cols = splitCsvLine(it)
            if (cols.size < 4) return@mapNotNull null
            try {
                DebtItem(
                    id               = cols[0],
                    name             = cols[1],
                    startingBalance  = cols[2].toDouble(),
                    currentBalance   = cols[3].toDouble()
                )
            } catch (e: Exception) { null }
        }
    }

    fun saveGlobalDebts(context: android.content.Context, debts: List<DebtItem>) {
        val sb = StringBuilder("id,name,startingBalance,currentBalance\n")
        debts.forEach { d -> sb.append("${d.id},${d.name.escapeCsv()},${d.startingBalance},${d.currentBalance}\n") }
        globalDebtsFile(context).writeText(sb.toString())
    }

    // ── Private helpers ───────────────────────────────────────────────────

    private fun parseTxLine(line: String): Transaction? {
        val cols = splitCsvLine(line)
        if (cols.size < 5) return null
        return try {
            Transaction(
                id          = cols[0],
                description = cols[1],
                amount      = cols[2].toDouble(),
                category    = cols[3],
                date        = cols[4]
            )
        } catch (e: Exception) { null }
    }

    private fun splitCsvLine(line: String): List<String> {
        val result  = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        for (ch in line) {
            when {
                ch == '"'              -> inQuotes = !inQuotes
                ch == ',' && !inQuotes -> { result.add(current.toString()); current.clear() }
                else                   -> current.append(ch)
            }
        }
        result.add(current.toString())
        return result
    }

    private fun String.escapeCsv(): String =
        if (contains(',') || contains('"') || contains('\n'))
            "\"${replace("\"", "\"\"")}\""
        else this
}

// ─── Navigation ───────────────────────────────────────────────────────────────

sealed class Screen {
    object Main : Screen()
    data class BudgetDetail(val budgetId: String) : Screen()
}

// ─── Activity ─────────────────────────────────────────────────────────────────

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SidekickTheme {
                BudgetApp()
            }
        }
    }
}

@Composable
fun BudgetApp() {
    var screen by remember { mutableStateOf<Screen>(Screen.Main) }
    when (val s = screen) {
        is Screen.Main          -> MainScreen(onOpenBudget = { screen = Screen.BudgetDetail(it) })
        is Screen.BudgetDetail  -> BudgetDetailScreen(budgetId = s.budgetId, onBack = { screen = Screen.Main })
    }
}

// ─── Reusable confirmation dialog ─────────────────────────────────────────────

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "Delete",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text(title, fontWeight = FontWeight.Bold) },
        text             = { Text(message) },
        confirmButton    = {
            TextButton(onClick = onConfirm) {
                Text(confirmText, color = Color(0xFFC62828), fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

// ─── Main Screen (top-level tabs: Budgets / Rent / Debt) ──────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(onOpenBudget: (String) -> Unit) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }

    var budgetMeta      by remember { mutableStateOf(CsvStore.loadBudgetList(context)) }
    var showNewBudget   by remember { mutableStateOf(false) }
    var newName         by remember { mutableStateOf("") }
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    var rentInfo by remember { mutableStateOf(CsvStore.loadGlobalRentInfo(context)) }
    var debts    by remember { mutableStateOf(CsvStore.loadGlobalDebts(context)) }

    val titles = listOf("My Budgets", "Rent", "Debt")

    Scaffold(
        topBar = {
            TopAppBar(
                title  = { Text(titles[selectedTab], fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        },
        floatingActionButton = {
            if (selectedTab == 0) {
                FloatingActionButton(onClick = { showNewBudget = true }) {
                    Icon(Icons.Default.Add, contentDescription = "New budget")
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Budgets") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Rent") })
                Tab(selected = selectedTab == 2, onClick = { selectedTab = 2 }, text = { Text("Debt") })
            }

            Box(Modifier.fillMaxSize()) {
                when (selectedTab) {
                    0 -> BudgetListContent(
                        budgetMeta      = budgetMeta,
                        onOpen          = onOpenBudget,
                        onDeleteRequest = { pendingDeleteId = it }
                    )
                    1 -> RentTrackerTab(
                        rentInfo   = rentInfo,
                        onSave     = { updated -> CsvStore.saveGlobalRentInfo(context, updated); rentInfo = updated },
                        onMarkPaid = { updated -> CsvStore.saveGlobalRentInfo(context, updated); rentInfo = updated }
                    )
                    2 -> DebtTrackerTab(
                        debts       = debts,
                        onSaveDebts = { updated -> CsvStore.saveGlobalDebts(context, updated); debts = updated }
                    )
                }
            }
        }

        if (showNewBudget) {
            AlertDialog(
                onDismissRequest = { showNewBudget = false; newName = "" },
                title            = { Text("New Budget") },
                text             = {
                    OutlinedTextField(
                        value         = newName,
                        onValueChange = { newName = it },
                        label         = { Text("Budget name") },
                        singleLine    = true
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        if (newName.isNotBlank()) {
                            val id      = UUID.randomUUID().toString()
                            val updated = budgetMeta + BudgetMeta(id = id, name = newName.trim(), timestamp = System.currentTimeMillis())
                            CsvStore.saveBudgetList(context, updated)
                            CsvStore.saveTransactions(context, id, emptyList())
                            CsvStore.saveCategories(context, id, DEFAULT_CATEGORY_INFOS)
                            CsvStore.saveGoals(context, id, DEFAULT_CATEGORIES.map { cat ->
                                Goal(category = cat, limit = SUGGESTED_LIMITS[cat] ?: 100.0)
                            })
                            budgetMeta = updated; newName = ""; showNewBudget = false
                        }
                    }) { Text("Create") }
                },
                dismissButton = { TextButton(onClick = { showNewBudget = false; newName = "" }) { Text("Cancel") } }
            )
        }

        pendingDeleteId?.let { id ->
            val name = budgetMeta.firstOrNull { it.id == id }?.name ?: "this budget"
            ConfirmDialog(
                title     = "Delete Budget?",
                message   = "This will permanently delete \"$name\" and all of its transactions, goals, and categories. This can't be undone.",
                onConfirm = {
                    CsvStore.deleteBudgetFile(context, id)
                    val updated = budgetMeta.filter { it.id != id }
                    CsvStore.saveBudgetList(context, updated)
                    budgetMeta = updated
                    pendingDeleteId = null
                },
                onDismiss = { pendingDeleteId = null }
            )
        }
    }
}

@Composable
fun BudgetListContent(
    budgetMeta: List<BudgetMeta>,
    onOpen: (String) -> Unit,
    onDeleteRequest: (String) -> Unit
) {
    val context = LocalContext.current
    val sortedBudgets = budgetMeta.sortedByDescending { it.timestamp }

    if (sortedBudgets.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No budgets yet.\nTap + to create one.", color = Color.Gray, fontSize = 16.sp)
        }
    } else {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(sortedBudgets, key = { _, meta -> meta.id }) { index, meta ->
                val txs     = remember(meta.id) { CsvStore.loadTransactions(context, meta.id) }
                val balance = txs.sumOf { it.amount }
                BudgetCard(
                    name      = meta.name,
                    balance   = balance,
                    featured  = index == 0,
                    onClick   = { onOpen(meta.id) },
                    onDelete  = { onDeleteRequest(meta.id) }
                )
            }
        }
    }
}

@Composable
fun BudgetCard(
    name: String,
    balance: Double,
    featured: Boolean = false,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val balanceColor = if (balance >= 0) Color(0xFF2E7D32) else Color(0xFFC62828)
    Card(
        modifier  = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape     = RoundedCornerShape(if (featured) 16.dp else 12.dp),
        elevation = CardDefaults.cardElevation(if (featured) 4.dp else 2.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(if (featured) 20.dp else 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    fontWeight = FontWeight.Bold,
                    fontSize   = if (featured) 22.sp else 16.sp
                )
                Spacer(Modifier.height(if (featured) 6.dp else 4.dp))
                Text(
                    "Balance: ${formatMoney(balance)}",
                    color      = balanceColor,
                    fontSize   = if (featured) 17.sp else 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = Color(0xFFB71C1C),
                    modifier = Modifier.size(if (featured) 26.dp else 24.dp)
                )
            }
        }
    }
}

// ─── Rent Tracker Tab (global) ─────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RentTrackerTab(rentInfo: RentInfo?, onSave: (RentInfo) -> Unit, onMarkPaid: (RentInfo) -> Unit) {
    var showSetup by remember { mutableStateOf(rentInfo == null) }
    val currentMonth = remember {
        val cal = Calendar.getInstance()
        cal.add(Calendar.MONTH, 1)
        SimpleDateFormat("yyyy-MM", Locale.getDefault()).format(cal.time)
    }


    if (rentInfo == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("🏠", fontSize = 40.sp)
                Spacer(Modifier.height(8.dp))
                Text("No rent set up yet.", color = Color.Gray)
                Spacer(Modifier.height(12.dp))
                Button(onClick = { showSetup = true }) { Text("Set Up Rent") }
            }
        }
    } else {
        val isPaid = rentInfo.lastPaidMonth == currentMonth
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Card(shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(2.dp)) {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🏠", fontSize = 22.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("Monthly Rent", fontWeight = FontWeight.Bold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        TextButton(onClick = { showSetup = true }) { Text("Edit") }
                    }
                    Text(formatMoney(rentInfo.monthlyAmount), fontSize = 28.sp, fontWeight = FontWeight.Bold)
                    Text("Due on day ${rentInfo.dueDay} of each month", fontSize = 13.sp, color = Color.Gray)
                    if (rentInfo.details.isNotBlank()) {
                        Text("Details: ${rentInfo.details}", fontSize = 13.sp, color = Color.Gray)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(10.dp).clip(CircleShape)
                                .background(if (isPaid) Color(0xFF2E7D32) else Color(0xFFC62828))
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (isPaid) "Paid for $currentMonth" else "Not yet paid for $currentMonth",
                            fontWeight = FontWeight.Medium,
                            color = if (isPaid) Color(0xFF2E7D32) else Color(0xFFC62828)
                        )
                    }
                    if (!isPaid) {
                        Button(
                            onClick = { onMarkPaid(rentInfo.copy(lastPaidMonth = currentMonth)) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Mark Paid", fontWeight = FontWeight.Bold) }
                    }
                }
            }
        }
    }

    if (showSetup) {
        RentSetupDialog(
            existing  = rentInfo,
            onDismiss = { showSetup = false },
            onSave    = { updated -> onSave(updated); showSetup = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RentSetupDialog(existing: RentInfo?, onDismiss: () -> Unit, onSave: (RentInfo) -> Unit) {
    var amountStr by remember { mutableStateOf(existing?.monthlyAmount?.let { "%.2f".format(it) } ?: "") }
    var dueDayStr by remember { mutableStateOf(existing?.dueDay?.toString() ?: "1") }
    var details  by remember { mutableStateOf(existing?.details ?: "") }
    val amount = amountStr.toDoubleOrNull()
    val dueDay = dueDayStr.toIntOrNull()
    val canSave = amount != null && amount > 0 && dueDay != null && dueDay in 1..31

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(6.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Set Up Rent", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                OutlinedTextField(
                    value = amountStr,
                    onValueChange = { amountStr = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Monthly amount") },
                    prefix = { Text("$") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = dueDayStr,
                    onValueChange = { dueDayStr = it.filter { c -> c.isDigit() }.take(2) },
                    label = { Text("Due day of month (1-31)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = details,
                    onValueChange = { details = it },
                    label = { Text("Details (optional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = canSave,
                        onClick = {
                            onSave(RentInfo(
                                monthlyAmount = amount ?: return@Button,
                                dueDay        = dueDay ?: return@Button,
                                lastPaidMonth = existing?.lastPaidMonth ?: "",
                                details      = details.trim()
                            ))
                        }
                    ) { Text("Save", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

// ─── Debt Tracker Tab (global) ─────────────────────────────────────────────────

@Composable
fun DebtTrackerTab(debts: List<DebtItem>, onSaveDebts: (List<DebtItem>) -> Unit) {
    var showAdd by remember { mutableStateOf(false) }
    var payingDebt by remember { mutableStateOf<DebtItem?>(null) }
    var pendingDelete by remember { mutableStateOf<DebtItem?>(null) }
    val totalRemaining = debts.sumOf { it.currentBalance }

    Box(Modifier.fillMaxSize()) {
        if (debts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No debts tracked.\nTap + to add one.", color = Color.Gray)
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(debts, key = { it.id }) { debt ->
                        val paidOff = debt.startingBalance - debt.currentBalance
                        val fraction = if (debt.startingBalance > 0) (paidOff / debt.startingBalance).toFloat().coerceIn(0f, 1f) else 0f
                        Card(shape = RoundedCornerShape(12.dp), elevation = CardDefaults.cardElevation(2.dp)) {
                            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("\uD83C\uDFE6", fontSize = 16.sp)
                                    Spacer(Modifier.width(6.dp))
                                    Text(debt.name, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                    IconButton(onClick = { pendingDelete = debt }, modifier = Modifier.size(24.dp)) {
                                        Icon(Icons.Default.Delete, contentDescription = "Remove debt", tint = Color(0xFFB71C1C), modifier = Modifier.size(18.dp))
                                    }
                                }
                                LinearProgressIndicator(
                                    progress = { fraction },
                                    modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                    color = Color(0xFF7A8BE3),
                                    trackColor = Color(0xFF7A8BE3).copy(alpha = 0.15f)
                                )
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("${formatMoney(debt.currentBalance)} left of ${formatMoney(debt.startingBalance)}", fontSize = 14.sp, color = Color.Black)
                                    TextButton(onClick = { payingDebt = debt }, enabled = debt.currentBalance > 0) { Text("Record Payment") }
                                }
                            }
                        }
                    }
                }
            }
        }

        FloatingActionButton(
            onClick = { showAdd = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 16.dp, end = 16.dp)
        ) { Icon(Icons.Default.Add, contentDescription = "Add debt") }
    }

    if (showAdd) {
        AddDebtDialog(
            onDismiss = { showAdd = false },
            onAdd = { name, balance ->
                onSaveDebts(debts + DebtItem(name = name, startingBalance = balance, currentBalance = balance))
                showAdd = false
            }
        )
    }

    payingDebt?.let { debt ->
        RecordPaymentDialog(
            debt = debt,
            onDismiss = { payingDebt = null },
            onRecord = { paymentAmount ->
                val newBalance = (debt.currentBalance - paymentAmount).coerceAtLeast(0.0)
                onSaveDebts(debts.map { if (it.id == debt.id) it.copy(currentBalance = newBalance) else it })
                payingDebt = null
            }
        )
    }

    pendingDelete?.let { debt ->
        ConfirmDialog(
            title     = "Remove Debt?",
            message   = "Remove \"${debt.name}\" from your debt tracker? This can't be undone.",
            onConfirm = {
                onSaveDebts(debts.filter { it.id != debt.id })
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddDebtDialog(onDismiss: () -> Unit, onAdd: (name: String, balance: Double) -> Unit) {
    var name by remember { mutableStateOf("") }
    var balanceStr by remember { mutableStateOf("") }
    val balance = balanceStr.toDoubleOrNull()
    val canAdd = name.isNotBlank() && balance != null && balance > 0

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(6.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Add Debt", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Name (e.g. Credit Card)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = balanceStr,
                    onValueChange = { balanceStr = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Current balance") },
                    prefix = { Text("$") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = canAdd, onClick = { onAdd(name.trim(), balance ?: return@Button) }) {
                        Text("Add", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordPaymentDialog(debt: DebtItem, onDismiss: () -> Unit, onRecord: (Double) -> Unit) {
    var amountStr by remember { mutableStateOf("") }
    val amount = amountStr.toDoubleOrNull()
    val canRecord = amount != null && amount > 0 && amount <= debt.currentBalance

    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(16.dp), elevation = CardDefaults.cardElevation(6.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Record Payment: ${debt.name}", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text("Balance: ${formatMoney(debt.currentBalance)}", fontSize = 13.sp, color = Color.Gray)
                OutlinedTextField(
                    value = amountStr,
                    onValueChange = { amountStr = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Payment amount") },
                    prefix = { Text("$") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = canRecord, onClick = { onRecord(amount ?: return@Button) }) {
                        Text("Record", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ─── Budget Detail Screen (tabbed: Goals / Transactions) ─────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BudgetDetailScreen(budgetId: String, onBack: () -> Unit) {
    val context = LocalContext.current

    val budgetName = remember {
        CsvStore.loadBudgetList(context).firstOrNull { it.id == budgetId }?.name ?: "Budget"
    }

    var transactions by remember { mutableStateOf(CsvStore.loadTransactions(context, budgetId)) }
    var goals        by remember { mutableStateOf(CsvStore.loadGoals(context, budgetId)) }
    var categories   by remember { mutableStateOf(CsvStore.loadCategories(context, budgetId)) }
    var selectedTab  by remember { mutableIntStateOf(0) }
    var showAddSheet by remember { mutableStateOf(false) }

    val income   = transactions.filter { it.amount > 0 }.sumOf { it.amount }
    val expenses = transactions.filter { it.amount < 0 }.sumOf { it.amount }
    val balance  = transactions.sumOf { it.amount }

    BackHandler { onBack() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(budgetName, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        },
        floatingActionButton = {
            if(selectedTab == 1) {
                FloatingActionButton(onClick = { showAddSheet = true }) {
                    Icon(Icons.Default.AddShoppingCart, contentDescription = "Add transaction")
                }
            }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            SummaryBanner(income = income, expenses = expenses, balance = balance)

            PrimaryTabRow(selectedTabIndex = selectedTab) {
                Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Goals") })
                Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Transactions") })
            }

            when (selectedTab) {
                1 -> TransactionsTab(
                    transactions = transactions,
                    categories   = categories,
                    onDelete     = { id ->
                        val updated = transactions.filter { it.id != id }
                        CsvStore.saveTransactions(context, budgetId, updated)
                        CsvStore.touchBudget(context, budgetId)
                        transactions = updated
                    }
                )
                0 -> GoalsTab(
                    transactions = transactions,
                    goals        = goals,
                    categories   = categories,
                    onSaveGoals  = { updated ->
                        CsvStore.saveGoals(context, budgetId, updated)
                        goals = updated
                    },
                    onSaveCategories = { updated ->
                        CsvStore.saveCategories(context, budgetId, updated)
                        categories = updated
                    }
                )
            }
        }
    }

    if (showAddSheet) {
        AddTransactionSheet(
            categories = categories,
            onDismiss = { showAddSheet = false },
            onAdd     = { tx ->
                val updated = transactions + tx
                CsvStore.saveTransactions(context, budgetId, updated)
                CsvStore.touchBudget(context, budgetId)
                transactions = updated
                showAddSheet = false
            }
        )
    }
}

// ─── Transactions Tab ─────────────────────────────────────────────────────────

@Composable
fun TransactionsTab(transactions: List<Transaction>, categories: List<CategoryInfo>, onDelete: (String) -> Unit) {
    var pendingDelete by remember { mutableStateOf<Transaction?>(null) }

    if (transactions.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No transactions yet.", color = Color.Gray)
        }
    } else {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
            contentPadding = PaddingValues(bottom = 80.dp)
        ) {
            items(transactions.sortedByDescending { it.date }, key = { it.id }) { tx ->
                val emoji = categories.firstOrNull { it.name == tx.category }?.emoji ?: categoryEmoji(tx.category)
                TransactionItem(tx = tx, emoji = emoji, onDelete = { pendingDelete = tx })
            }
        }
    }

    pendingDelete?.let { tx ->
        ConfirmDialog(
            title     = "Delete Transaction?",
            message   = "Remove \"${tx.description}\" (${formatMoney(tx.amount)})? This can't be undone.",
            onConfirm = {
                onDelete(tx.id)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }
}

// ─── Goals Tab ────────────────────────────────────────────────────────────────

val DEFAULT_CATEGORIES = listOf("Groceries", "Transport", "Fun", "Utilities", "Debt", "Health", "Other")

val DEFAULT_CATEGORY_INFOS = DEFAULT_CATEGORIES.map { CategoryInfo(name = it, emoji = categoryEmoji(it)) }

val SUGGESTED_LIMITS = mapOf(
    "Groceries" to 175.0,
    "Transport" to 150.0,
    "Fun"       to 100.0,
    "Utilities" to 100.0,
    "Debt"      to 800.0,
    "Health"    to 25.0,
    "Other"     to 100.0
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GoalsTab(
    transactions: List<Transaction>,
    goals: List<Goal>,
    categories: List<CategoryInfo>,
    onSaveGoals: (List<Goal>) -> Unit,
    onSaveCategories: (List<CategoryInfo>) -> Unit
) {
    var showAddCategory  by remember { mutableStateOf(false) }
    var editingGoal       by remember { mutableStateOf<Goal?>(null) }
    var pendingDeleteGoal by remember { mutableStateOf<Goal?>(null) }

    // Actual spending per category this period
    val spendMap = transactions
        .filter { it.amount < 0 }
        .groupBy { it.category }
        .mapValues { (_, txs) -> txs.sumOf { abs(it.amount) } }

    // Income vs. what's been allocated across goals (uses each goal's target limit, not current spend)
    val income     = transactions.filter { it.amount > 0 }.sumOf { it.amount }
    val totalGoals = goals.sumOf { it.limit }
    val remaining  = income - totalGoals

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                if (goals.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("No categories yet.\nTap + to add one.", color = Color.Gray, fontSize = 14.sp)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.fillMaxSize().padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 80.dp)
                    ) {
                        items(goals, key = { it.category }) { goal ->
                            val cat = goal.category
                            val emoji = categories.firstOrNull { it.name == cat }?.emoji ?: categoryEmoji(cat)
                            val limit = goal.limit
                            val spent = spendMap[cat] ?: 0.0
                            val fraction = if (limit > 0) (spent / limit).toFloat().coerceIn(0f, 1f) else 0f
                            val overBudget = spent > limit
                            val barColor = Color(0xFF7A8BE3)

                            Card(
                                shape = RoundedCornerShape(8.dp),
                                elevation = CardDefaults.cardElevation(1.dp),
                                modifier = Modifier.clickable { editingGoal = goal }
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(emoji, fontSize = 16.sp)
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            cat,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.sp,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1
                                        )
                                        IconButton(
                                            onClick = { pendingDeleteGoal = goal },
                                            modifier = Modifier.size(20.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "Remove category", tint = Color(0xFFB71C1C), modifier = Modifier.size(16.dp))
                                        }
                                    }
                                    LinearProgressIndicator(
                                        progress = { fraction },
                                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                                        color = barColor,
                                        trackColor = barColor.copy(alpha = 0.15f)
                                    )
                                    Text(
                                        "${formatMoney(spent)} / ${formatMoney(limit)}",
                                        fontSize = 11.sp,
                                        color = if (overBudget) Color(0xFFC62828) else barColor,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                    }
                }
            }

            GoalsSummaryBar(income = income, totalGoals = totalGoals, remaining = remaining)
        }

        FloatingActionButton(
            onClick = { showAddCategory = true },
            modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = 88.dp, end = 16.dp)
        ) { Icon(Icons.Default.Add, contentDescription = "Add category") }
    }

    if (showAddCategory) {
        AddCategoryDialog(
            existingCategories = categories.map { it.name },
            onDismiss = { showAddCategory = false },
            onAdd = { name, emoji, limit ->
                val trimmed = name.trim()
                if (categories.none { it.name.equals(trimmed, ignoreCase = true) }) {
                    onSaveCategories(categories + CategoryInfo(name = trimmed, emoji = emoji))
                }
                onSaveGoals(goals + Goal(category = trimmed, limit = limit))
                showAddCategory = false
            }
        )
    }

    editingGoal?.let { goal ->
        EditGoalDialog(
            goal = goal,
            onDismiss = { editingGoal = null },
            onSave = { newLimit ->
                onSaveGoals(goals.map { if (it.category == goal.category) it.copy(limit = newLimit) else it })
                editingGoal = null
            }
        )
    }

    pendingDeleteGoal?.let { goal ->
        ConfirmDialog(
            title     = "Remove Category?",
            message   = "This will remove \"${goal.category}\" and its budget goal. Existing transactions in this category will keep their label but won't count toward any goal.",
            onConfirm = {
                onSaveGoals(goals.filter { it.category != goal.category })
                onSaveCategories(categories.filter { it.name != goal.category })
                pendingDeleteGoal = null
            },
            onDismiss = { pendingDeleteGoal = null }
        )
    }
}

@Composable
fun GoalsSummaryBar(income: Double, totalGoals: Double, remaining: Double) {
    val remainingColor = if (remaining >= 0) Color(0xFF2E7D32) else Color(0xFFC62828)
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        shape  = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Income − Goals", fontSize = 12.sp, color = Color.Gray)
                Text(
                    "${formatMoney(income)} − ${formatMoney(totalGoals)}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Text(
                formatMoney(remaining),
                color = remainingColor,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditGoalDialog(
    goal: Goal,
    onDismiss: () -> Unit,
    onSave: (Double) -> Unit
) {
    var limitStr by remember { mutableStateOf("%.2f".format(goal.limit)) }
    val limit = limitStr.toDoubleOrNull()
    val canSave = limit != null && limit > 0

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier.fillMaxWidth().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(categoryEmoji(goal.category), fontSize = 18.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("Edit ${goal.category}", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                }

                OutlinedTextField(
                    value = limitStr,
                    onValueChange = { limitStr = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Limit") },
                    prefix = { Text("$") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = canSave,
                        onClick = { onSave(limit ?: return@Button) }
                    ) { Text("Save", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

val EMOJI_CHOICES = listOf(
    "🖼️", "🧪", "🎯", "💍", "🏦", "🗿", "🧭", "📦",
    "🍔", "☕", "🎮", "📚", "✈️", "🎬", "🐾", "🎁",
    "💇", "🧾", "🏋️", "🎵", "👶", "🚙", "🛠️", "📱"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddCategoryDialog(
    existingCategories: List<String>,
    onDismiss: () -> Unit,
    onAdd: (name: String, emoji: String, limit: Double) -> Unit
) {
    var name     by remember { mutableStateOf("") }
    var limitStr by remember { mutableStateOf("") }
    var emoji    by remember { mutableStateOf(EMOJI_CHOICES.first()) }

    val trimmedName = name.trim()
    val isDuplicate = trimmedName.isNotEmpty() && existingCategories.any { it.equals(trimmedName, ignoreCase = true) }
    val limit = limitStr.toDoubleOrNull()
    val canAdd = trimmedName.isNotEmpty() && !isDuplicate && limit != null && limit > 0

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Add Category", fontWeight = FontWeight.Bold, fontSize = 18.sp)

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Category name") },
                    singleLine = true,
                    isError = isDuplicate,
                    supportingText = { if (isDuplicate) Text("That category already exists") },
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = limitStr,
                    onValueChange = { limitStr = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Limit") },
                    prefix = { Text("$") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Emoji", fontSize = 12.sp, color = Color.Gray)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    EMOJI_CHOICES.chunked(6).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { choice ->
                                val selected = choice == emoji
                                Box(
                                    modifier = Modifier
                                        .size(36.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                            else Color.Transparent
                                        )
                                        .clickable { emoji = choice },
                                    contentAlignment = Alignment.Center
                                ) { Text(choice, fontSize = 18.sp) }
                            }
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        enabled = canAdd,
                        onClick = { onAdd(trimmedName, emoji, limit ?: return@Button) }
                    ) { Text("Add", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

// ─── Summary Banner ───────────────────────────────────────────────────────────

@Composable
fun SummaryBanner(income: Double, expenses: Double, balance: Double) {
    val balanceColor = if (balance >= 0) Color(0xFF2E7D32) else Color(0xFFC62828)
    Card(
        Modifier.fillMaxWidth().padding(12.dp),
        shape  = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            SummaryItem("Income",   formatMoney(income),   Color(0xFF2E7D32))
            VerticalDivider()
            SummaryItem("Expenses", formatMoney(expenses), Color(0xFFC62828))
            VerticalDivider()
            SummaryItem("Balance",  formatMoney(balance),  balanceColor, bold = true)
        }
    }
}

@Composable
fun SummaryItem(label: String, value: String, color: Color, bold: Boolean = false) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 12.sp, color = Color.Gray)
        Spacer(Modifier.height(4.dp))
        Text(
            value,
            color      = color,
            fontSize   = if (bold) 18.sp else 15.sp,
            fontWeight = if (bold) FontWeight.Bold else FontWeight.SemiBold
        )
    }
}

@Composable
fun VerticalDivider() {
    Box(Modifier.width(1.dp).height(40.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)))
}

// ─── Transaction Item ─────────────────────────────────────────────────────────

@Composable
fun TransactionItem(tx: Transaction, emoji: String, onDelete: () -> Unit) {
    val amountColor = if (tx.amount >= 0) Color(0xFF2E7D32) else Color(0xFFC62828)
    val amountStr   = if (tx.amount >= 0) "+${formatMoney(tx.amount)}" else formatMoney(tx.amount)

    Card(
        modifier  = Modifier.fillMaxWidth(),
        shape     = RoundedCornerShape(10.dp),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(36.dp).clip(CircleShape).background(categoryColor(tx.category).copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) { Text(emoji, fontSize = 16.sp) }

            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(tx.description, fontWeight = FontWeight.Medium, fontSize = 14.sp)
                Text("${tx.category}  •  ${tx.date}", fontSize = 12.sp, color = Color.Gray)
            }

            Text(amountStr, color = amountColor, fontWeight = FontWeight.Bold, fontSize = 15.sp)

            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Gray, modifier = Modifier.size(18.dp))
            }
        }
    }
}

// ─── Add Transaction Sheet ────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionSheet(categories: List<CategoryInfo>, onDismiss: () -> Unit, onAdd: (Transaction) -> Unit) {
    var description      by remember { mutableStateOf("") }
    var amountStr        by remember { mutableStateOf("") }
    var category         by remember { mutableStateOf(categories.firstOrNull()?.name ?: "Other") }
    var isExpense        by remember { mutableStateOf(true) }
    var categoryExpanded by remember { mutableStateOf(false) }

    val today = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = RoundedCornerShape(16.dp),
            elevation = CardDefaults.cardElevation(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                Text("Add Transaction", fontWeight = FontWeight.Bold, fontSize = 18.sp)

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = isExpense,  onClick = { isExpense = true  }, label = { Text("Expense") }, modifier = Modifier.weight(1f))
                    FilterChip(selected = !isExpense, onClick = { isExpense = false }, label = { Text("Income")  }, modifier = Modifier.weight(1f))
                }
                if (isExpense) {
                    ExposedDropdownMenuBox(expanded = categoryExpanded, onExpandedChange = { categoryExpanded = it }) {
                        OutlinedTextField(
                            value         = category,
                            onValueChange = {},
                            readOnly      = true,
                            label         = { Text("Category") },
                            trailingIcon  = { ExposedDropdownMenuDefaults.TrailingIcon(categoryExpanded) },
                            modifier      = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = categoryExpanded, onDismissRequest = { categoryExpanded = false }) {
                            categories.forEach { info ->
                                DropdownMenuItem(
                                    text    = { Text("${info.emoji}  ${info.name}") },
                                    onClick = { category = info.name; categoryExpanded = false }
                                )
                            }
                        }
                    }
                }
                OutlinedTextField(
                    value         = description,
                    onValueChange = { description = it },
                    label         = { Text("Description") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value           = amountStr,
                    onValueChange   = { amountStr = it.filter { c -> c.isDigit() || c == '.' } },
                    label           = { Text("Amount") },
                    singleLine      = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier        = Modifier.fillMaxWidth(),
                    leadingIcon     = { Text("$", Modifier.padding(start = 8.dp)) }
                )

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick  = {
                            val amount = amountStr.toDoubleOrNull() ?: return@Button
                            if (description.isBlank()) return@Button
                            onAdd(Transaction(
                                description = description.trim(),
                                amount      = if (isExpense) -amount else amount,
                                category    = if (isExpense) category else "Income",
                                date        = today
                            ))
                        }
                    ) { Text("Add", fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

// ─── Utilities ────────────────────────────────────────────────────────────────

fun formatMoney(amount: Double): String =
    "$%.2f".format(abs(amount)).let { if (amount < 0) "-$it" else it }

//("Groceries", "Gas", "Fun", "Utilities", "Debt", "Health", "Rent", "Other")
fun categoryColor(cat: String): Color = when (cat) {
    "Groceries"     -> Color(0xFFFF7043)
    "Transport"     -> Color(0xFF42A5F5)
    "Fun"           -> Color(0xFF7E57C2)
    "Debt"          -> Color(0xFFAABB7F)
    "Utilities"     -> Color(0xFFEC407A)
    "Health"        -> Color(0xFF26A69A)
    "Income"        -> Color(0xFF66BB6A)
    else            -> Color(0xFF78909C)
}

fun categoryEmoji(cat: String): String = when (cat) {
    "Groceries"     -> "🛒"
    "Transport"     -> "🚗"
    "Fun"           -> "🎉"
    "Debt"          -> "💳"
    "Utilities"     -> "💡"
    "Health"        -> "💊"
    "Income"        -> "💵"
    else            -> "📦"
}