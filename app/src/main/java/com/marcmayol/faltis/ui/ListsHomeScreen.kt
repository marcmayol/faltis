package com.marcmayol.faltis.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marcmayol.faltis.data.ListEntity
import com.marcmayol.faltis.data.StoreEntity
import com.marcmayol.faltis.ui.theme.Faltis
import com.marcmayol.faltis.ui.theme.Type

/** Un grupo del inicio: las listas de un super, o las generales ([store] null). */
private data class ListGroup(val key: String, val store: StoreEntity?, val lists: List<ListEntity>)

/**
 * Inicio: tus listas agrupadas por super y plegadas. Al tocar un super se despliegan sus listas;
 * al tocar una lista se entra en ella. Un super sin lista la crea al entrar.
 */
@Composable
fun ListsHomeScreen(vm: AppViewModel, onOpen: () -> Unit, onStores: () -> Unit, contentPadding: PaddingValues) {
    val lists by vm.lists.collectAsState()
    val stores by vm.stores.collectAsState()
    val counts by vm.listCounts.collectAsState()
    val settings by vm.settings.collectAsState()
    var expanded by rememberSaveable { mutableStateOf(listOf<String>()) }
    var newList by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ListEntity?>(null) }
    var deleting by remember { mutableStateOf<ListEntity?>(null) }

    val groups = remember(lists, stores) {
        val byStore = stores.map { st -> ListGroup("s${st.id}", st, lists.filter { it.storeId == st.id }) }
        val general = lists.filter { l -> stores.none { it.id == l.storeId } }
        byStore + listOfNotNull(ListGroup("general", null, general).takeIf { general.isNotEmpty() })
    }
    val open: (ListEntity) -> Unit = { l -> vm.selectList(l.id); onOpen() }

    HaloBackground {
        Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Tus listas", style = Type.screenTitle, modifier = Modifier.weight(1f))
                SquareIconButton(Icons.Filled.Add, "Nueva lista", { newList = true })
            }
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (!com.marcmayol.faltis.BuildConfig.DEBUG) item {
                    val app = androidx.compose.ui.platform.LocalContext.current.applicationContext as com.marcmayol.faltis.FaltisApp
                    androidx.compose.runtime.LaunchedEffect(Unit) { app.actualizador.comprobar(com.marcm.actualizador.Modo.AUTOMATICO) }
                    val estado by app.actualizador.estado.collectAsState()
                    UpdateBanner(estado, { app.actualizador.actualizarAhora() })
                }
                if (stores.isEmpty()) item {
                    EmptyState("Añade tus tiendas", "Cada súper tendrá aquí sus listas, para ver de un vistazo qué te falta en cada uno.") {
                        PrimaryButton("Añadir tiendas", onStores)
                    }
                }
                items(groups, key = { it.key }) { g ->
                    val isOpen = g.key in expanded
                    GroupCard(
                        group = g, counts = counts, open = isOpen, activeId = settings?.activeListId,
                        onHeader = {
                            // Un super sin listas: se crea la suya y se entra directamente.
                            if (g.store != null && g.lists.isEmpty()) vm.openStoreList(g.store, onOpen)
                            else expanded = if (isOpen) expanded - g.key else expanded + g.key
                        },
                        onList = open, onRename = { renaming = it }, onDelete = { deleting = it },
                    )
                }
            }
        }
    }

    if (newList) TextDialog("Nueva lista", "Nombre", "", { newList = false }) { name -> vm.newList(name); onOpen() }
    renaming?.let { l -> TextDialog("Renombrar lista", "Nombre", l.name, { renaming = null }) { vm.renameList(l, it) } }
    deleting?.let { l ->
        val n = counts[l.id] ?: 0
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = Faltis.Surface,
            title = { Text("¿Borrar «${l.name}»?", style = Type.title) },
            text = { Text(if (n == 0) "La lista está vacía." else "Se borrarán también los $n artículos que tiene pendientes. Tus compras y precios no se tocan.", style = Type.body) },
            confirmButton = { TextButton({ vm.deleteList(l); deleting = null }) { Text("Borrar", color = Faltis.Warn) } },
            dismissButton = { TextButton({ deleting = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun GroupCard(
    group: ListGroup,
    counts: Map<Long, Int>,
    open: Boolean,
    activeId: Long?,
    onHeader: () -> Unit,
    onList: (ListEntity) -> Unit,
    onRename: (ListEntity) -> Unit,
    onDelete: (ListEntity) -> Unit,
) {
    val total = group.lists.sumOf { counts[it.id] ?: 0 }
    val shape = RoundedCornerShape(22.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Faltis.Surface).border(1.dp, if (open) Faltis.LineStrong else Faltis.Elevated, shape)) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onHeader).heightIn(min = 76.dp).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (group.store != null) ChainChip(group.store.chainId, 48.dp)
            else Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(Faltis.Elevated), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.List, null, tint = Faltis.Ink2)
            }
            Column(Modifier.weight(1f)) {
                Text(group.store?.name ?: "Listas generales", style = Type.title.copy(fontSize = 19.sp), maxLines = 2)
                Text(
                    if (group.lists.isEmpty()) "Sin lista · toca para empezar una"
                    else "${plural(group.lists.size, "lista", "listas")} · ${plural(total, "producto", "productos")}",
                    style = Type.secondary.copy(fontSize = 14.sp),
                )
            }
            if (group.lists.isEmpty()) Icon(Icons.Filled.Add, null, tint = Faltis.Ink2)
            else {
                val angle by animateFloatAsState(if (open) 180f else 0f, label = "chevron")
                Box(Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).background(Faltis.Elevated), contentAlignment = Alignment.Center) {
                    Icon(Icons.Filled.KeyboardArrowDown, if (open) "Plegar" else "Desplegar", tint = Faltis.Ink2, modifier = Modifier.rotate(angle))
                }
            }
        }
        AnimatedVisibility(open && group.lists.isNotEmpty()) {
            Column(Modifier.padding(start = 14.dp, end = 6.dp, bottom = 8.dp)) {
                group.lists.forEach { l ->
                    Box(Modifier.fillMaxWidth().padding(start = 62.dp).heightIn(min = 1.dp).background(Faltis.Line))
                    ListRow(l, counts[l.id] ?: 0, l.id == activeId, { onList(l) }, { onRename(l) }, { onDelete(l) })
                }
            }
        }
    }
}

@Composable
private fun ListRow(l: ListEntity, count: Int, active: Boolean, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).heightIn(min = 64.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(17.dp)).background(if (active) Faltis.ActionSoft else Faltis.Elevated), contentAlignment = Alignment.Center) {
                Icon(Icons.AutoMirrored.Filled.List, null, tint = if (active) Faltis.ActionText else Faltis.Ink2, modifier = Modifier.size(18.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            Text(l.name, style = Type.row, maxLines = 2)
            Text(if (count == 0) "Vacía" else plural(count, "producto", "productos"), style = Type.secondary.copy(fontSize = 13.sp))
        }
        Box {
            androidx.compose.material3.IconButton({ menu = true }, Modifier.size(48.dp)) { Icon(Icons.Filled.MoreVert, "Opciones de ${l.name}", tint = Faltis.Ink2) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Renombrar") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Borrar", color = Faltis.Warn) }, onClick = { menu = false; onDelete() })
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Faltis.Ink3)
    }
}

fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
