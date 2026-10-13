package com.marcmayol.faltis

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime

import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.marcmayol.faltis.ui.AppViewModel
import com.marcmayol.faltis.ui.CompareScreen
import com.marcmayol.faltis.ui.ListScreen
import com.marcmayol.faltis.ui.OnboardingScreen
import com.marcmayol.faltis.ui.PricesScreen
import com.marcmayol.faltis.ui.ProductScreen
import com.marcmayol.faltis.ui.PurchaseDetailScreen
import com.marcmayol.faltis.ui.ReconcileScreen
import com.marcmayol.faltis.ui.SettingsScreen
import com.marcmayol.faltis.ui.ShopModeScreen
import com.marcmayol.faltis.ui.SpendingScreen
import com.marcmayol.faltis.ui.TicketCaptureScreen
import com.marcmayol.faltis.ui.TicketReviewScreen
import com.marcmayol.faltis.ui.theme.Faltis
import com.marcmayol.faltis.ui.theme.FaltisTheme
import com.marcmayol.faltis.ui.theme.Type
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val actualizador get() = (application as FaltisApp).actualizador

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleShare(intent)
        // Auto-actualizacion (DracApps) solo en la version publicada: la de pruebas es otro paquete.
        // Aqui y no en la Application, como en DracApps: WorkManager aun no existe en los tests.
        if (!BuildConfig.DEBUG) actualizador.programarPeriodica()
        setContent {
            val settings by vm.settings.collectAsState()
            FaltisTheme(settings?.themeMode ?: com.marcmayol.faltis.ui.theme.ThemeMode.SYSTEM) { Root(vm) }
        }
    }

    override fun onResume() {
        super.onResume()
        // Vuelta de la pantalla de "instalar apps desconocidas": reanuda la actualizacion.
        if (!BuildConfig.DEBUG) actualizador.onPermisoQuizaConcedido()
        vm.sendSharedPrices()
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
    }

    /** Tickets compartidos desde otra app: uno (PDF del ticket digital o foto) o varios de golpe. */
    private fun handleShare(intent: android.content.Intent?) {
        when (intent?.action) {
            android.content.Intent.ACTION_SEND -> {
                val uri = if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM)
                if (uri != null) vm.sharedTicket.value = uri to (intent.type ?: "")
            }
            android.content.Intent.ACTION_SEND_MULTIPLE -> {
                val uris: List<android.net.Uri> = (if (android.os.Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM, android.net.Uri::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableArrayListExtra(android.content.Intent.EXTRA_STREAM)).orEmpty()
                if (uris.size == 1) vm.sharedTicket.value = uris[0] to (intent.type ?: "")
                else if (uris.isNotEmpty()) vm.sharedBatch.value = uris.map { it to (contentResolver.getType(it) ?: intent.type ?: "") }
            }
        }
    }
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab("list", "Lista", Icons.AutoMirrored.Filled.List),
    Tab("prices", "Precios", Icons.Filled.LocalOffer),
    Tab("spending", "Gastos", Icons.Filled.BarChart),
    Tab("settings", "Ajustes", Icons.Filled.Tune),
)

@Composable
private fun Root(vm: AppViewModel) {
    val settings by vm.settings.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    LaunchedEffect(Unit) {
        vm.messages.collect { m ->
            scope.launch {
                val r = snackbar.showSnackbar(m.text, actionLabel = if (m.undo != null) m.actionLabel else null, duration = SnackbarDuration.Short)
                if (r == SnackbarResult.ActionPerformed) m.undo?.let { vm.run { it() } }
            }
        }
    }
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val s = settings
    val showBar = s?.onboardingDone == true && tabs.any { it.route == route }

    // Alto de la barra de abajo: el Scaffold ya levanta el aviso por encima de ella, y con el teclado
    // abierto (que la tapa) no hay que sumarla otra vez al alto del teclado.
    var barBottom by remember { mutableStateOf(0.dp) }
    val imeBottom = WindowInsets.ime.asPaddingValues().calculateBottomPadding()

    // Un solo Scaffold para toda la app: sus PaddingValues llevan la barra de estado, la de
    // navegacion y la barra flotante (medida, no a ojo) a cada pantalla.
    Scaffold(
        containerColor = Faltis.Ground,
        contentColor = Faltis.Ink,
        // Por encima de la barra para escribir de la lista, de los botones de abajo y del teclado.
        snackbarHost = { SnackbarHost(snackbar, Modifier.padding(bottom = 64.dp + (imeBottom - barBottom).coerceAtLeast(0.dp))) },
        bottomBar = { if (showBar) FloatingBar(route) { nav.goTab(it) } },
    ) { padding ->
        SideEffect { barBottom = padding.calculateBottomPadding() }
        // Consumido para que imePadding no vuelva a sumar la barra de navegacion al abrir el teclado.
        Box(Modifier.fillMaxSize().consumeWindowInsets(padding)) {
            when {
                s == null -> Unit
                !s.onboardingDone -> OnboardingScreen(vm, padding)
                else -> MainNav(vm, nav, route, padding)
            }
        }
    }
    val shareNotice by vm.shareNotice.collectAsState()
    if (shareNotice) com.marcmayol.faltis.ui.ShareNoticeDialog(vm::answerShareNotice)
}

@Composable
private fun MainNav(vm: AppViewModel, nav: NavHostController, route: String?, padding: PaddingValues) {
    val shared by vm.sharedTicket.collectAsState()
    val sharedBatch by vm.sharedBatch.collectAsState()
    LaunchedEffect(shared, sharedBatch) { if ((shared != null || sharedBatch != null) && route?.startsWith("ticket") != true) nav.navigate("ticket/0") }

    NavHost(nav, startDestination = "list") {
        composable("list") {
            ListScreen(
                vm, onCompare = { nav.navigate("compare") }, onShop = { nav.navigate("shop") },
                onTicket = { nav.navigate("ticket/0") }, onPickProduct = { nav.navigate("pick/$it") },
                onStores = { nav.goTab("settings") }, contentPadding = padding,
            )
        }
        composable("prices") { PricesScreen(vm, onProduct = { nav.navigate("product/$it") }, onCompare = { nav.navigate("compare") }, contentPadding = padding) }
        composable("spending") { SpendingScreen(vm, onTicket = { nav.navigate("ticket/0") }, onPurchase = { nav.navigate("purchase/$it") }, contentPadding = padding) }
        composable("settings") { SettingsScreen(vm, padding) }
        composable("compare") { CompareScreen(vm, onBack = { nav.popBackStack() }, onTicket = { nav.navigate("ticket/0") }, onStores = { nav.goTab("settings") }, contentPadding = padding) }
        composable("shop") { ShopModeScreen(vm, onBack = { nav.popBackStack() }, onTicket = { id -> nav.navigate("ticket/$id") }, contentPadding = padding) }
        composable("ticket/{store}") { e ->
            val store = e.arguments?.getString("store")?.toLongOrNull()?.takeIf { it > 0 }
            TicketCaptureScreen(vm, store, onBack = { nav.popBackStack() }, onReview = { nav.navigate("review") }, contentPadding = padding)
        }
        composable("review") { TicketReviewScreen(vm, onBack = { nav.popBackStack() }, onNext = { nav.navigate("reconcile") }, contentPadding = padding) }
        composable("reconcile") {
            ReconcileScreen(vm, onBack = { nav.popBackStack() }, onSaved = {
                // Vuelve a Gastos quitando captura, revision y cuadre de la pila.
                if (!nav.popBackStack("spending", inclusive = false)) {
                    nav.navigate("spending") { popUpTo("list"); launchSingleTop = true }
                }
            }, contentPadding = padding)
        }
        composable("purchase/{id}") { e -> PurchaseDetailScreen(vm, e.arguments?.getString("id")!!.toLong(), onBack = { nav.popBackStack() }, contentPadding = padding) }
        composable("product/{id}") { e -> ProductScreen(vm, e.arguments?.getString("id")!!.toLong(), onBack = { nav.popBackStack() }, contentPadding = padding) }
        composable("pick/{need}") { e ->
            val need = e.arguments?.getString("need")!!.toLong()
            PricesScreen(vm, onProduct = {}, onCompare = {}, contentPadding = padding, pickForNeed = need,
                onPicked = { nav.popBackStack() }, onBack = { nav.popBackStack() })
        }
    }
}

private fun NavHostController.goTab(route: String) = navigate(route) {
    popUpTo("list") { saveState = true }
    launchSingleTop = true
    restoreState = true
}

@Composable
private fun FloatingBar(current: String?, onSelect: (String) -> Unit) {
    Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 12.dp), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Faltis.NavBar)
                .border(1.dp, Faltis.Line, RoundedCornerShape(24.dp)).padding(6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            tabs.forEach { t ->
                val selected = t.route == current
                Column(
                    Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(if (selected) Faltis.ActionSoft else Color.Transparent)
                        .clickable { onSelect(t.route) }.heightIn(min = 52.dp).padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                ) {
                    Icon(t.icon, null, tint = if (selected) Faltis.ActionText else Faltis.Ink3, modifier = Modifier.size(22.dp))
                    Text(t.label, style = Type.label.copy(color = if (selected) Faltis.Ink else Faltis.Ink3, fontSize = 11.sp, letterSpacing = 0.sp))
                }
            }
        }
    }
}
