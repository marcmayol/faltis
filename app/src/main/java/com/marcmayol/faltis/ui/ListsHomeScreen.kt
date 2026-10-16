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
import androidx.compose.material.icons.filled.GridView
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

/**
 * Inicio: tus listas, una tarjeta por lista. Cada una enseña de que supers son sus productos
 * (el super va en cada articulo, no en la lista). Al tocarla se entra.
 */
@Composable
fun ListsHomeScreen(vm: AppViewModel, onOpen: () -> Unit, onCatalog: () -> Unit, contentPadding: PaddingValues) {
    val lists by vm.lists.collectAsState()
    val needs by vm.allOpenNeeds.collectAsState()
    val book by vm.priceBook.collectAsState()
    val settings by vm.settings.collectAsState()
    var newList by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ListEntity?>(null) }
    var deleting by remember { mutableStateOf<ListEntity?>(null) }
    val byList = remember(needs) { needs.groupBy { it.listId } }
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
                items(lists, key = { it.id }) { l ->
                    val items = byList[l.id].orEmpty()
                    ListCard(
                        l, items.size, items.mapNotNull { chainOfNeed(it, book) }.distinct(), items.any { chainOfNeed(it, book) == null },
                        l.id == settings?.activeListId, { open(l) }, { renaming = l }, { deleting = l },
                    )
                }
                if (lists.isNotEmpty()) item {
                    SecondaryButton("Ver productos del súper", onCatalog, Modifier.fillMaxWidth().padding(top = 4.dp), icon = Icons.Filled.GridView)
                }
            }
        }
    }

    if (newList) TextDialog("Nueva lista", "Nombre", "", { newList = false }) { name -> vm.newList(name); onOpen() }
    renaming?.let { l -> TextDialog("Renombrar lista", "Nombre", l.name, { renaming = null }) { vm.renameList(l, it) } }
    deleting?.let { l ->
        val n = byList[l.id]?.size ?: 0
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
private fun ListCard(
    l: ListEntity,
    count: Int,
    chains: List<String>,
    anywhere: Boolean,
    active: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(22.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Faltis.Surface).border(1.dp, if (active) Faltis.LineStrong else Faltis.Elevated, shape)
            .clickable(onClick = onClick).heightIn(min = 84.dp).padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(14.dp)).background(if (active) Faltis.ActionSoft else Faltis.Elevated), contentAlignment = Alignment.Center) {
            Icon(Icons.AutoMirrored.Filled.List, null, tint = if (active) Faltis.ActionText else Faltis.Ink2)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(l.name, style = Type.title.copy(fontSize = 19.sp), maxLines = 2)
            Text(if (count == 0) "Vacía" else plural(count, "producto", "productos"), style = Type.secondary.copy(fontSize = 14.sp))
            // De que supers son sus productos: los logos, y «cualquiera» si hay sin super.
            if (chains.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                chains.take(5).forEach { ChainChip(it, 26.dp) }
                if (chains.size > 5) Text("+${chains.size - 5}", style = Type.secondary.copy(fontSize = 13.sp))
                if (anywhere) Text("y en cualquiera", style = Type.secondary.copy(fontSize = 13.sp))
            }
        }
        Box {
            androidx.compose.material3.IconButton({ menu = true }, Modifier.size(48.dp)) { Icon(Icons.Filled.MoreVert, "Opciones de ${l.name}", tint = Faltis.Ink2) }
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Renombrar") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Borrar", color = Faltis.Warn) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

fun plural(n: Int, one: String, many: String) = "$n ${if (n == 1) one else many}"
