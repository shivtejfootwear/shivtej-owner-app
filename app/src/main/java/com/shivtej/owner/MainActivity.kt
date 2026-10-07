@file:OptIn(ExperimentalMaterial3Api::class)

package com.shivtej.owner

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import com.shivtej.owner.net.ApiError
import com.shivtej.owner.ui.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as App

        // near real-time: while the app is on screen, sync every 15 seconds when online
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    if (app.net.online.value && app.prefs.ownerCode.isNotBlank()) app.sync.kick()
                    delay(15_000)
                }
            }
        }

        setContent {
            val dark = isSystemInDarkTheme()
            MaterialTheme(
                colorScheme = if (dark) darkColorScheme(primary = Color(0xFFEF9A9A), secondary = Color(0xFFFFCDD2))
                else lightColorScheme(primary = Color(0xFFC62828), secondary = Color(0xFF8E0000))
            ) {
                Surface(Modifier.fillMaxSize()) { Root(app) }
            }
        }
    }
}

@Composable
fun Root(app: App) {
    var code by remember { mutableStateOf(app.prefs.ownerCode) }
    var unlocked by remember { mutableStateOf(app.prefs.appPin.isBlank()) }
    when {
        code.isBlank() -> SetupScreen(app) { code = it }
        !unlocked -> PinScreen(app) { unlocked = true }
        else -> Shell(app)
    }
}

// ------------------------------------------------------------------ owner code setup
@Composable
fun SetupScreen(app: App, onDone: (String) -> Unit) {
    var code by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var offlineChoice by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(
        Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.Center
    ) {
        Text("Shivtej Footwear", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text("Owner App", fontSize = 18.sp)
        Spacer(Modifier.height(24.dp))
        Text("Enter your secret owner code (the one you set in the Supabase SQL). It is saved only on this phone.")
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = code, onValueChange = { code = it; error = null; offlineChoice = false },
            label = { Text("Owner code") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth()
        )
        error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
        Spacer(Modifier.height(16.dp))
        Button(
            enabled = code.length >= 4 && !busy,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            onClick = {
                busy = true
                scope.launch {
                    val r = app.sync.verifyCode(code.trim())
                    busy = false
                    val e = r.exceptionOrNull()
                    when {
                        e == null -> { app.prefs.ownerCode = code.trim(); app.sync.kick(); onDone(code.trim()) }
                        e is ApiError && e.isAuth -> error = "Wrong owner code."
                        e is ApiError && e.code == "PGRST202" -> error = "Supabase setup SQL has not been run yet."
                        e is ApiError -> error = e.message
                        else -> { error = "Could not reach the server."; offlineChoice = true }
                    }
                }
            }
        ) { Text(if (busy) "Checking..." else "Connect") }
        if (offlineChoice) {
            TextButton(onClick = { app.prefs.ownerCode = code.trim(); onDone(code.trim()) }) {
                Text("Save code and verify later (offline)")
            }
        }
    }
}

@Composable
fun PinScreen(app: App, onOk: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
        Text("Enter PIN", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = pin, onValueChange = { pin = it.filter(Char::isDigit).take(8); bad = false },
            singleLine = true, label = { Text("PIN") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            visualTransformation = PasswordVisualTransformation(),
            isError = bad, modifier = Modifier.fillMaxWidth()
        )
        if (bad) Text("Wrong PIN", color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Button(
            onClick = { if (pin == app.prefs.appPin) onOk() else bad = true },
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) { Text("Unlock") }
    }
}

// ------------------------------------------------------------------ shell: drawer + top bar
private data class DrawerItem(val label: String, val icon: ImageVector, val route: Route)

@Composable
fun Shell(app: App) {
    val stack = remember { mutableStateListOf<Route>(Route.Dashboard) }
    val nav = remember { Nav(stack) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val route = stack.last()

    val items = listOf(
        DrawerItem("Dashboard", Icons.Filled.Dashboard, Route.Dashboard),
        DrawerItem("Products", Icons.Filled.Inventory2, Route.Products()),
        DrawerItem("Add Item", Icons.Filled.AddCircle, Route.ProductEdit(null)),
        DrawerItem("Stock / Inventory", Icons.Filled.Warehouse, Route.Stock()),
        DrawerItem("POS Billing", Icons.Filled.PointOfSale, Route.Pos),
        DrawerItem("Online Orders", Icons.Filled.ShoppingCart, Route.Orders("all")),
        DrawerItem("Shipping", Icons.Filled.LocalShipping, Route.Orders("shipping")),
        DrawerItem("Returns & Exchanges", Icons.Filled.Replay, Route.Returns),
        DrawerItem("Customers", Icons.Filled.People, Route.Customers),
        DrawerItem("Reports", Icons.Filled.BarChart, Route.Reports),
        DrawerItem("AI Assistant", Icons.Filled.SmartToy, Route.Assistant),
        DrawerItem("Settings", Icons.Filled.Settings, Route.Settings)
    )

    BackHandler(enabled = drawer.isOpen || nav.canGoBack) {
        if (drawer.isOpen) scope.launch { drawer.close() } else nav.back()
    }

    val title = when (route) {
        Route.Dashboard -> "Dashboard"
        is Route.Products -> "Products"
        is Route.ProductEdit -> if (route.id == null) "Add Item" else "Edit Product"
        is Route.Stock -> "Stock"
        Route.Pos -> "POS Billing"
        is Route.Receipt -> "Receipt"
        is Route.Orders -> if (route.mode == "shipping") "Shipping" else "Online Orders"
        is Route.OrderDetail -> "Order"
        Route.Customers -> "Customers"
        is Route.ReturnForm -> "Return / Exchange"
        Route.Returns -> "Returns & Exchanges"
        Route.Reports -> "Reports"
        Route.Assistant -> "AI Assistant"
        Route.Settings -> "Settings"
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = !nav.canGoBack,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Shivtej Footwear", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(20.dp))
                    items.forEach { item ->
                        NavigationDrawerItem(
                            label = { Text(item.label) },
                            icon = { Icon(item.icon, null) },
                            selected = false,
                            onClick = { nav.top(item.route); scope.launch { drawer.close() } },
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        if (nav.canGoBack) IconButton(onClick = { nav.back() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        } else IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Filled.Menu, "Menu")
                        }
                    },
                    actions = {
                        StatusChip(app)
                        IconButton(onClick = { app.sync.kick(); toast(ctx, "Syncing...") }) {
                            Icon(Icons.Filled.Sync, "Sync")
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        titleContentColor = MaterialTheme.colorScheme.onPrimary,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimary,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (route) {
                    Route.Dashboard -> DashboardScreen(app, nav)
                    is Route.Products -> ProductsScreen(app, nav, route.filter)
                    is Route.ProductEdit -> ProductEditScreen(app, nav, route.id)
                    is Route.Stock -> StockScreen(app, route.tab, route.filter)
                    Route.Pos -> PosScreen(app, nav)
                    is Route.Receipt -> ReceiptScreen(app, nav, route.billId)
                    is Route.Orders -> OrdersScreen(app, nav, route.mode)
                    is Route.OrderDetail -> OrderDetailScreen(app, nav, route.id)
                    Route.Customers -> CustomersScreen(app)
                    is Route.ReturnForm -> ReturnFormScreen(app, nav, route.refType, route.refId)
                    Route.Returns -> ReturnsScreen(app, nav)
                    Route.Reports -> ReportsScreen(app, nav)
                    Route.Assistant -> AssistantScreen(app)
                    Route.Settings -> SettingsScreen(app)
                }
            }
        }
    }
}

@Composable
fun StatusChip(app: App) {
    val online by app.net.online.collectAsStateWithLifecycle()
    val pending by app.repo.pendingOps.collectAsStateWithLifecycle(0)
    val st by app.sync.state.collectAsStateWithLifecycle()
    val label = when {
        !online -> "OFFLINE"
        st.running -> "SYNCING"
        else -> "ONLINE"
    }
    val color = if (online) Color(0xFF66BB6A) else Color(0xFFFFB74D)
    Row(
        Modifier.padding(end = 4.dp).background(Color.White.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).background(color, RoundedCornerShape(4.dp)))
        Spacer(Modifier.width(6.dp))
        Text(
            label + if (pending > 0) " · $pending" else "",
            color = MaterialTheme.colorScheme.onPrimary, fontSize = 11.sp, fontWeight = FontWeight.Bold
        )
    }
}
