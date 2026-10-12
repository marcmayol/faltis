package com.marcmayol.faltis.ui

import com.marcmayol.faltis.core.WebCatalog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marcmayol.faltis.BuildConfig
import com.marcmayol.faltis.core.Chains
import com.marcmayol.faltis.ui.theme.Faltis
import com.marcmayol.faltis.ui.theme.Type
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

@Composable
fun SettingsScreen(vm: AppViewModel, contentPadding: PaddingValues) {
    val stores by vm.stores.collectAsState()
    val settings by vm.settings.collectAsState()
    val sampleCount by vm.sampleCount.collectAsState()
    var addChain by remember { mutableStateOf(false) }
    var postalDialog by remember { mutableStateOf(false) }
    var budgetDialog by remember { mutableStateOf(false) }
    var wipe by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<com.marcmayol.faltis.data.StoreEntity?>(null) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) scope.launch {
            val json = vm.repo.exportJson()
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
            vm.say("Datos exportados")
        }
    }

    HaloBackground(color = Faltis.Blue, second = Faltis.Brand) {
        LazyColumn(
            Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding()),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 18.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item { Text("Ajustes", style = Type.screenTitle, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)) }
            item { SectionLabel("Mis tiendas", Modifier.padding(start = 4.dp)) }
            itemsIndexed(stores, key = { _, s -> s.id }) { i, s ->
                // Tocar la tienda cambia su nombre: con dos de la misma cadena hay que saber cual es cual.
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Faltis.Surface)
                        .clickable(onClickLabel = "Cambiar nombre") { renaming = s }.padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ChainChip(s.chainId, 32.dp)
                    Text(s.name, style = Type.row.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    // Solo las flechas que sirven: con letra grande, tres botones partian el nombre.
                    if (i > 0) IconButton({ vm.moveStore(s, -1) }) { Icon(Icons.Filled.KeyboardArrowUp, "Subir") }
                    if (i < stores.lastIndex) IconButton({ vm.moveStore(s, 1) }) { Icon(Icons.Filled.KeyboardArrowDown, "Bajar") }
                    IconButton({ vm.hideStore(s) }) { Icon(Icons.Filled.Close, "Quitar de mis tiendas") }
                }
            }
            item {
                androidx.compose.foundation.layout.Box {
                    SecondaryButton("Añadir tienda", { addChain = true }, Modifier.fillMaxWidth())
                    DropdownMenu(addChain, { addChain = false }) {
                        Chains.all.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, leadingIcon = { ChainChip(c.id, 26.dp) }, onClick = { addChain = false; vm.addStore(c.id) }) }
                    }
                }
            }
            item { SectionLabel("Compra", Modifier.padding(start = 4.dp, top = 10.dp)) }
            item {
                SettingRow("Incluir promos y precios de socio", "Usa el precio con descuento si es de los últimos 7 días.") {
                    Switch(settings?.includePromos == true, vm::setPromos, colors = SwitchDefaults.colors(checkedTrackColor = Faltis.Action))
                }
            }
            item {
                val web = settings?.webPrices == true
                val busy by vm.webBusy.collectAsState()
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Faltis.Surface).padding(bottom = if (web) 12.dp else 0.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SettingRow(
                        "Precios web (Mercadona y Aldi)",
                        "Antes de tu primer ticket, toma los precios de sus tiendas online. Usa internet; tus listas no salen del móvil.",
                    ) {
                        Switch(web, { on -> if (on && !WebCatalog.isPostalCode(settings?.postalCode ?: "")) postalDialog = true else vm.setWebPrices(on) },
                            colors = SwitchDefaults.colors(checkedTrackColor = Faltis.Action))
                    }
                    if (web) {
                        SettingRow("Código postal", (settings?.postalCode ?: "") + if (busy) " · actualizando…" else "", onClick = { postalDialog = true }) {}
                        WebPriceNotice(Modifier.padding(horizontal = 12.dp))
                    }
                }
            }
            item {
                SettingRow("Presupuesto mensual", settings?.budgetCents?.takeIf { it > 0 }?.let { euros(it) } ?: "Sin presupuesto", onClick = { budgetDialog = true }) {}
            }
            item { SectionLabel("Apariencia", Modifier.padding(start = 4.dp, top = 10.dp)) }
            item {
                val mode = settings?.themeMode ?: com.marcmayol.faltis.ui.theme.ThemeMode.SYSTEM
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Faltis.Surface).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("Tema", style = Type.row.copy(fontSize = 15.sp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            com.marcmayol.faltis.ui.theme.ThemeMode.SYSTEM to "Sistema",
                            com.marcmayol.faltis.ui.theme.ThemeMode.LIGHT to "Claro",
                            com.marcmayol.faltis.ui.theme.ThemeMode.DARK to "Oscuro",
                        ).forEach { (m, label) -> SelectPill(label, m == mode) { vm.setTheme(m) } }
                    }
                }
            }
            item { SectionLabel("Datos", Modifier.padding(start = 4.dp, top = 10.dp)) }
            item { SettingRow("Exportar mis datos", "Un archivo JSON con tiendas, listas, precios y compras.", onClick = { export.launch("faltis.json") }) {} }
            if (sampleCount > 0) item { SettingRow("Borrar datos de ejemplo", "$sampleCount productos ficticios y sus precios.", onClick = { vm.deleteSample() }) {} }
            else item { SettingRow("Cargar datos de ejemplo", "Para probar la app con precios ficticios.", onClick = { vm.loadSample() }) {} }
            item { SettingRow("Borrar todo", "Listas, tiendas, precios y compras. No se puede deshacer.", onClick = { wipe = true }) {} }
            item { SectionLabel("Acerca de", Modifier.padding(start = 4.dp, top = 10.dp)) }
            if (!BuildConfig.DEBUG) item {
                UpdatesSection((context.applicationContext as com.marcmayol.faltis.FaltisApp).actualizador)
            }
            item {
                SettingRow(
                    if (BuildConfig.DEBUG) "Faltis ${BuildConfig.VERSION_NAME}" else "Privacidad y créditos",
                    "Nombre provisional. Tus listas, tickets y precios no salen del móvil; internet solo se usa para buscar actualizaciones y, si los activas, para leer los precios web. " +
                        "Sin analítica. Tipografías Archivo e IBM Plex Mono (SIL OFL). Partes de código abierto: ver NOTICE.",
                ) {}
            }
        }
    }
    if (postalDialog) TextDialog(
        "Tu código postal", "Código postal", settings?.postalCode ?: "", { postalDialog = false },
        hint = "Para los precios de tu zona: Aldi cambia en Baleares y Canarias", keyboard = KeyboardType.Number,
    ) { cp ->
        val code = cp.trim()
        if (WebCatalog.isPostalCode(code)) vm.setWebPrices(true, code) else vm.say("«$code» no es un código postal")
    }
    renaming?.let { s ->
        TextDialog(
            "Nombre de la tienda", "Nombre", s.name, { renaming = null },
            hint = "Por ejemplo, el pueblo o el barrio: «${Chains.byId(s.chainId).name} Centro»",
        ) { vm.renameStore(s, it) }
    }
    if (budgetDialog) TextDialog(
        "Presupuesto mensual", "Euros", settings?.budgetCents?.takeIf { it > 0 }?.let { euros(it, false) } ?: "", { budgetDialog = false },
        hint = "0 para quitarlo", keyboard = KeyboardType.Decimal,
    ) { v ->
        vm.setBudget(((v.replace(".", "").replace(',', '.').toDoubleOrNull() ?: 0.0) * 100).roundToLong())
    }
    if (wipe) AlertDialog(
        onDismissRequest = { wipe = false }, containerColor = Faltis.Surface,
        title = { Text("¿Borrar todo?", style = Type.title) },
        text = { Text("Se borrarán tus listas, tiendas, precios y compras. No se puede deshacer.", style = Type.body) },
        confirmButton = { TextButton({ wipe = false; vm.deleteAll() }) { Text("Borrar todo", color = Faltis.Warn) } },
        dismissButton = { TextButton({ wipe = false }) { Text("Cancelar") } },
    )
}

@Composable
private fun SettingRow(title: String, subtitle: String?, onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Faltis.Surface)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).heightIn(min = 60.dp).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.row.copy(fontSize = 15.sp))
            if (subtitle != null) Text(subtitle, style = Type.secondary.copy(fontSize = 13.sp))
        }
        trailing()
    }
}
