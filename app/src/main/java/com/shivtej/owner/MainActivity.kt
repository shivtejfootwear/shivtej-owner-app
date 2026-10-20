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
import com.shivtej.owner.util.AppLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {
    // the one-day auto lock: remember when the app leaves the screen, and lock when it comes back after a day
    override fun onStop() {
        super.onStop()
        AppLock.onAppStopped((application as App).prefs)
    }

    override fun onStart() {
        super.onStart()
        AppLock.onAppStarted((application as App).prefs)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as App

        App.dbRefusal?.let { msg ->
            // nothing is opened, changed or deleted: the data stays on the phone as it is
            setContent {
                MaterialTheme(colorScheme = lightColorScheme(primary = Color(0xFFC62828))) {
                    Surface(Modifier.fillMaxSize()) {
                        androidx.compose.foundation.layout.Column(Modifier.padding(20.dp)) {
                            androidx.compose.material3.Text("Shivtej Owner App can not start safely", fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                            androidx.compose.material3.Text(msg, modifier = Modifier.padding(top = 12.dp))
                        }
                    }
                }
            }
            return
        }

        // lock at start when the PIN is set and the app was away for a day (checked once per app start, not on rotation)
        AppLock.checkAtStart(app.prefs)

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
            val systemDark = isSystemInDarkTheme()
            val themeVersion by ThemeBus.version.collectAsState()
            // the scheme follows the theme chosen in Settings (system / light / dark / red & white) and its accent colour
            val scheme = remember(themeVersion, systemDark) { buildScheme(app.prefs, systemDark) }
            MaterialTheme(colorScheme = scheme) {
                Surface(Modifier.fillMaxSize()) { Root(app) }
            }
        }
    }
}

@Composable
fun Root(app: App) {
    var code by remember { mutableStateOf(app.prefs.ownerCode) }
    when {
        code.isBlank() -> SetupScreen(app) { code = it }
        AppLock.locked.value -> PinScreen(app) { AppLock.locked.value = false }
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
    var forgot by remember { mutableStateOf(false) }
    var ownerCode by remember { mutableStateOf("") }
    var resetError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
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

        // forgotten PIN: the owner code (checked on the server) removes the PIN; a new one is set in Settings
        Spacer(Modifier.height(8.dp))
        if (!forgot) {
            TextButton(onClick = { forgot = true }) { Text("PIN भूल गए?") }
        } else {
            Spacer(Modifier.height(8.dp))
            Text("Enter the owner code to remove the PIN. You can set a new PIN in Settings afterwards.", fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = ownerCode, onValueChange = { ownerCode = it; resetError = null },
                label = { Text("Owner code") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth()
            )
            resetError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp)) }
            Spacer(Modifier.height(8.dp))
            Button(
                enabled = ownerCode.length >= 4 && !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        val r = app.sync.verifyCode(ownerCode.trim())
                        busy = false
                        if (r.isSuccess) {
                            app.prefs.appPin = ""
                            onOk()
                        } else {
                            val e = r.exceptionOrNull()
                            resetError = if (e is ApiError && e.isAuth) "Wrong owner code."
                                else "Could not check the owner code. It needs internet."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(48.dp)
            ) { Text(if (busy) "Checking..." else "Remove PIN") }
        }
    }
}

// ------------------------------------------------------------------ shell: drawer + top bar
private data class DrawerItem(val label: String, val icon: ImageVector, val route: Route, val color: Color, val badge: String = "")
private data class DrawerSection(val title: String, val items: List<DrawerItem>)

private val DrawerRed = Color(0xFFD91E18)
private val DrawerOrange = Color(0xFFEF6C00)
private val DrawerBlue = Color(0xFF1E63C8)
private val DrawerTeal = Color(0xFF00897B)
private val DrawerPurple = Color(0xFF7B3FA0)
private val DrawerIndigo = Color(0xFF3949AB)
private val DrawerSlate = Color(0xFF546E7A)

@Composable
fun Shell(app: App) {
    val stack = remember { mutableStateListOf<Route>(Route.Dashboard) }
    val nav = remember { Nav(stack) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val route = stack.last()

    val sections = listOf(
        DrawerSection("Sales", listOf(
            DrawerItem("Dashboard", Icons.Filled.Dashboard, Route.Dashboard, DrawerRed),
            DrawerItem("POS Billing", Icons.Filled.PointOfSale, Route.Pos, DrawerRed),
            DrawerItem("Online Orders", Icons.Filled.ShoppingCart, Route.Orders("all"), DrawerRed),
            DrawerItem("Shipping", Icons.Filled.LocalShipping, Route.Orders("shipping"), DrawerRed),
            DrawerItem("Returns & Exchanges", Icons.Filled.Replay, Route.Returns, DrawerRed)
        )),
        DrawerSection("Stock", listOf(
            DrawerItem("Products", Icons.Filled.Inventory2, Route.Products(), DrawerOrange),
            DrawerItem("Add Item", Icons.Filled.AddCircle, Route.ProductEdit(null), DrawerOrange),
            DrawerItem("Stock / Inventory", Icons.Filled.Warehouse, Route.Stock(), DrawerOrange),
            DrawerItem("Suppliers & Purchases", Icons.Filled.Storefront, Route.Suppliers, DrawerOrange),
            DrawerItem("Supplier order list", Icons.Filled.Storefront, Route.SupplierOrders, DrawerOrange)
        )),
        DrawerSection("Customers", listOf(
            DrawerItem("Offline customers", Icons.Filled.People, Route.Customers, DrawerBlue),
            DrawerItem("Online customers", Icons.Filled.Language, Route.CustomersOnline, DrawerTeal),
            DrawerItem("Customer numbers", Icons.Filled.ContactPhone, Route.CustomerNumbers, DrawerBlue),
            DrawerItem("Size requests", Icons.Filled.Notifications, Route.SizeRequests, DrawerRed)
        )),
        DrawerSection("Team", listOf(
            DrawerItem("Staff", Icons.Filled.Group, Route.Staff, DrawerPurple)
        )),
        DrawerSection("Reports & Settings", listOf(
            DrawerItem("Reports", Icons.Filled.BarChart, Route.Reports, DrawerTeal),
            DrawerItem("Printer", Icons.Filled.Print, Route.Printer, DrawerIndigo),
            DrawerItem("Shop & Bill Settings", Icons.Filled.Receipt, Route.ShopSettings, DrawerTeal),
            DrawerItem("AI Assistant", Icons.Filled.SmartToy, Route.Assistant, DrawerIndigo),
            DrawerItem("Tasks & Reminders", Icons.Filled.Checklist, Route.Tasks, DrawerSlate),
            DrawerItem("Change log", Icons.Filled.Info, Route.ChangeLog, DrawerSlate),
            DrawerItem("Settings", Icons.Filled.Settings, Route.Settings, DrawerSlate)
        ))
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
        Route.Customers -> "Offline customers"
        Route.CustomersOnline -> "Online customers"
        Route.ChangeLog -> "Change log"
        Route.SupplierOrders -> "Supplier order list"
        Route.SizeRequests -> "Size requests"
        Route.CustomerNew -> "Add customer"
        is Route.CustomerDetail -> "Customer"
        Route.Staff -> "Staff & Sales"
        is Route.StaffDetail -> "Staff history"
        Route.Suppliers -> "Suppliers & Purchases"
        is Route.SupplierDetail -> "Supplier"
        is Route.PurchaseForm -> if (route.billId == null) "New purchase bill" else "Edit purchase bill"
        Route.Printer -> "Bluetooth Printer"
        Route.ShopSettings -> "Shop & Bill Settings"
        Route.BillDesign -> "Bill Design"
        is Route.ReturnForm -> "Return / Exchange"
        Route.Returns -> "Returns & Exchanges"
        Route.Reports -> "Reports"
        Route.Assistant -> "AI Assistant"
        Route.Settings -> "Settings"
        Route.ThemeSettings -> "Theme & Wallpaper"
        Route.Tasks -> "Tasks & Reminders"
        Route.Dues -> "Customer dues"
        Route.AdminPortal -> "Admin Portal"
        Route.CustomerNumbers -> "Customer numbers"
        Route.DeletedBills -> "Deleted bills"
        Route.Catalog -> "Catalog"
        Route.StaffBills -> "Staff Bills"
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = !nav.canGoBack,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text("Shivtej Footwear", fontSize = 22.sp, fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(20.dp))
                    StorageCard(app, Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
                    sections.forEach { section ->
                        Text(section.title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 28.dp, top = 14.dp, bottom = 2.dp))
                        section.items.forEach { item ->
                            NavigationDrawerItem(
                                label = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(item.label, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                        if (item.badge.isNotEmpty()) Text(item.badge, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            modifier = Modifier.background(item.color, RoundedCornerShape(50))
                                                .padding(horizontal = 6.dp, vertical = 2.dp))
                                    }
                                },
                                icon = {
                                    Box(Modifier.size(36.dp).background(item.color, RoundedCornerShape(10.dp)),
                                        contentAlignment = Alignment.Center) {
                                        Icon(item.icon, null, tint = Color.White, modifier = Modifier.size(20.dp))
                                    }
                                },
                                selected = false,
                                onClick = { nav.top(item.route); scope.launch { drawer.close() } },
                                modifier = Modifier.padding(horizontal = 12.dp),
                                colors = NavigationDrawerItemDefaults.colors(unselectedContainerColor = Color.Transparent)
                            )
                        }
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
                WallpaperLayer(app, isDashboard = route == Route.Dashboard)
                when (route) {
                    Route.Dashboard -> DashboardScreen(app, nav)
                    is Route.Products -> ProductsScreen(app, nav, route.filter)
                    is Route.ProductEdit -> ProductEditScreen(app, nav, route.id)
                    is Route.Stock -> StockScreen(app, route.tab, route.filter)
                    Route.Pos -> PosScreen(app, nav)
                    is Route.Receipt -> ReceiptScreen(app, nav, route.billId)
                    is Route.Orders -> OrdersScreen(app, nav, route.mode)
                    is Route.OrderDetail -> OrderDetailScreen(app, nav, route.id)
                    Route.Customers -> CustomersScreen(app, nav, fixedKind = "shop")
                    Route.CustomersOnline -> OnlineCustomerNumbersScreen(app, nav)
                    Route.ChangeLog -> ChangeLogScreen(app)
                    Route.SupplierOrders -> SupplierOrderScreen(app)
                    Route.SizeRequests -> SizeRequestsScreen(app)
                    Route.CustomerNew -> CustomersScreen(app, nav, openAdd = true)
                    is Route.CustomerDetail -> CustomerDetailScreen(app, nav, route.mobile)
                    Route.Staff -> StaffScreen(app, nav)
                    is Route.StaffDetail -> StaffDetailScreen(app, nav, route.id)
                    Route.Suppliers -> SuppliersScreen(app, nav)
                    is Route.SupplierDetail -> SupplierDetailScreen(app, nav, route.id)
                    is Route.PurchaseForm -> PurchaseFormScreen(app, nav, route.supplierId, route.billId)
                    Route.Printer -> PrinterScreen(app)
                    Route.ShopSettings -> ShopSettingsScreen(app, nav)
                    Route.BillDesign -> BillDesignScreen(app, nav)
                    is Route.ReturnForm -> ReturnFormScreen(app, nav, route.refType, route.refId)
                    Route.Returns -> ReturnsScreen(app, nav)
                    Route.Reports -> ReportsScreen(app, nav)
                    Route.Assistant -> AssistantScreen(app)
                    Route.Settings -> SettingsScreen(app) { nav.go(it) }
                    Route.ThemeSettings -> ThemeSettingsScreen(app)
                    Route.Tasks -> TasksScreen(app)
                    Route.Dues -> CustomersScreen(app, nav, initialStatus = "owes")
                    Route.AdminPortal -> AdminPortalScreen(app, nav)
                    Route.CustomerNumbers -> CustomerNumbersScreen(app)
                    Route.DeletedBills -> DeletedBillsScreen(app, nav)
                    Route.Catalog -> CatalogScreen(app)
                    Route.StaffBills -> StaffBillsScreen(app, nav)
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
