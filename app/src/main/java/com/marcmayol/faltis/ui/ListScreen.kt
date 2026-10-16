package com.marcmayol.faltis.ui

import androidx.compose.material.icons.filled.Info
import com.marcmayol.faltis.data.ListEntity
import com.marcmayol.faltis.data.StoreEntity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marcmayol.faltis.core.Comparison
import com.marcmayol.faltis.core.Freshness
import com.marcmayol.faltis.core.NeedPriceResult
import com.marcmayol.faltis.core.NoPriceReason
import com.marcmayol.faltis.core.PriceSource
import com.marcmayol.faltis.core.PricedNeed
import com.marcmayol.faltis.core.QtyUnit
import com.marcmayol.faltis.data.NeedEntity
import com.marcmayol.faltis.ui.theme.Faltis
import com.marcmayol.faltis.ui.theme.Type

@Composable
fun ListScreen(
    vm: AppViewModel,
    onCompare: () -> Unit,
    onShop: () -> Unit,
    onTicket: () -> Unit,
    onPickProduct: (needId: Long) -> Unit,
    onStores: () -> Unit,
    onCatalog: () -> Unit,
    contentPadding: PaddingValues,
    onBack: (() -> Unit)? = null,
) {
    val ui by vm.listUi.collectAsState()
    val stores by vm.stores.collectAsState()
    val lists by vm.lists.collectAsState()
    var editing by remember { mutableStateOf<NeedEntity?>(null) }
    var newListDialog by remember { mutableStateOf(false) }
    var renameDialog by remember { mutableStateOf(false) }
    var importDialog by remember { mutableStateOf(false) }
    val book by vm.priceBook.collectAsState()
    val add: (String) -> Unit = { t -> vm.addText(t) }
    val context = androidx.compose.ui.platform.LocalContext.current

    HaloBackground {
        Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
            ListHeader(
                onBack = onBack,
                listName = ui.list?.name ?: "Compra",
                lists = lists.map { it.id to it.name },
                chainOfList = emptyMap(),
                onSelect = vm::selectList,
                onNew = { newListDialog = true },
                onRename = { renameDialog = true },
                onShare = {
                    val text = ui.needs.joinToString("\n") { "- ${it.text} ${qtyText(it.qty)} ${it.unit}" }
                    val send = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, "${ui.list?.name ?: "Lista"}\n$text")
                    }
                    context.startActivity(android.content.Intent.createChooser(send, "Compartir lista"))
                },
                onImport = { importDialog = true },
                onTicket = onTicket,
                onRefreshWeb = if (vm.settings.collectAsState().value?.webPrices == true) ({ vm.refreshWebPrices(force = true) }) else null,
            )
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    TotalHero(ui, stores.map { it.id to it.name }, stores.associate { it.id to it.chainId }, vm::selectStore, onCompare, onShop, onStores, vm.webBusy.collectAsState().value)
                    Spacer(Modifier.height(10.dp))
                }
                if (ui.needs.isEmpty()) {
                    item {
                        EmptyState("Tu lista está vacía", "Elige productos en el catálogo de tu súper, o escribe abajo lo que te falta. O toca una sugerencia:") {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("leche", "pan", "huevos", "tomates", "café").forEach { s ->
                                    SecondaryButton(s, { add(s) })
                                }
                            }
                            PrimaryButton("Ver productos del súper", onCatalog, Modifier.fillMaxWidth().padding(top = 12.dp), icon = Icons.Filled.GridView)
                        }
                    }
                }
                // Por super: cada articulo dice donde se compra (el de su producto, o el elegido); lo demas, en cualquiera.
                val myChains = stores.map { it.chainId }.distinct()
                val catOrder: (NeedEntity) -> Int = { n -> vm.repo.taxonomy.byId(n.typeId)?.categoryOrder ?: 999 }
                val bySuper = ui.needs.groupBy { n -> chainOfNeed(n, book) }.entries
                    .sortedWith(compareBy({ (c, _) -> if (c == null) Int.MAX_VALUE else myChains.indexOf(c).let { if (it < 0) 500 else it } }, { (c, _) -> c ?: "" }))
                bySuper.forEach { (chain, needs) ->
                    item(key = "s-${chain ?: "any"}") { SuperHeader(chain, needs.size, Modifier.padding(start = 4.dp, top = 8.dp)) }
                    items(needs.sortedBy(catOrder), key = { it.id }) { n ->
                        NeedRow(n, vm.priceOf(n.id), vm.repo.taxonomy.byId(n.typeId)?.categoryId, onClick = { editing = n }, onDelete = { vm.deleteNeed(n) })
                    }
                }
            }
            Row(
                Modifier.padding(start = 16.dp, end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SquareIconButton(Icons.Filled.GridView, "Añadir desde el catálogo del súper", onCatalog, tint = Faltis.Brand)
                AddBar(onAdd = add, modifier = Modifier.weight(1f))
            }
        }
    }

    editing?.let { n ->
        NeedSheet(n, vm, onDismiss = { editing = null }, onPick = { editing = null; onPickProduct(n.id) })
    }
    if (newListDialog) TextDialog("Nueva lista", "Nombre", "", { newListDialog = false }) { vm.newList(it) }
    if (renameDialog) ui.list?.let { l -> TextDialog("Renombrar lista", "Nombre", l.name, { renameDialog = false }) { vm.renameList(l, it) } }
    if (importDialog) TextDialog("Importar texto", "Tu lista", "", { importDialog = false }, multiline = true, hint = "Una cosa por línea o separadas por comas") { add(it) }
}

@Composable
private fun ListHeader(
    onBack: (() -> Unit)?,
    listName: String,
    lists: List<Pair<Long, String>>,
    chainOfList: Map<Long, String>,
    onSelect: (Long) -> Unit,
    onNew: () -> Unit,
    onRename: () -> Unit,
    onShare: () -> Unit,
    onImport: () -> Unit,
    onTicket: () -> Unit,
    onRefreshWeb: (() -> Unit)?,
) {
    var listMenu by remember { mutableStateOf(false) }
    var moreMenu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = if (onBack != null) 16.dp else 20.dp, end = 16.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        onBack?.let { BackButton(it); Spacer(Modifier.width(12.dp)) }
        Column(Modifier.weight(1f)) {
            SectionLabel("Lista")
            Box {
                Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable { listMenu = true }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    // Con boton de volver hay menos sitio: un punto mas pequeño y, si no cabe, puntos suspensivos.
                    Text(
                        listName, style = if (onBack != null) Type.screenTitle.copy(fontSize = 26.sp) else Type.screenTitle,
                        maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(Icons.Filled.ArrowDropDown, "Cambiar de lista")
                }
                DropdownMenu(listMenu, { listMenu = false }) {
                    lists.forEach { (id, name) ->
                        DropdownMenuItem(
                            text = { Text(name) }, onClick = { onSelect(id); listMenu = false },
                            leadingIcon = chainOfList[id]?.let { chain -> { ChainChip(chain, 26.dp) } },
                        )
                    }
                    DropdownMenuItem(text = { Text("Nueva lista…") }, onClick = { onNew(); listMenu = false }, leadingIcon = { Icon(Icons.Filled.Add, null) })
                }
            }
        }
        Box {
            SquareIconButton(Icons.Filled.MoreVert, "Más opciones", { moreMenu = true })
            DropdownMenu(moreMenu, { moreMenu = false }) {
                DropdownMenuItem(text = { Text("Escanear ticket") }, onClick = { moreMenu = false; onTicket() })
                DropdownMenuItem(text = { Text("Renombrar lista") }, onClick = { moreMenu = false; onRename() })
                DropdownMenuItem(text = { Text("Compartir como texto") }, onClick = { moreMenu = false; onShare() })
                DropdownMenuItem(text = { Text("Importar texto") }, onClick = { moreMenu = false; onImport() })
                onRefreshWeb?.let { DropdownMenuItem(text = { Text("Actualizar precios web") }, onClick = { moreMenu = false; it() }) }
            }
        }
    }
}

@Composable
private fun TotalHero(
    ui: ListUi,
    stores: List<Pair<Long, String>>,
    chainOf: Map<Long, String>,
    onSelectStore: (Long) -> Unit,
    onCompare: () -> Unit,
    onShop: () -> Unit,
    onStores: () -> Unit,
    webBusy: Boolean = false,
) {
    GradientCard {
        if (ui.store == null) {
            SectionLabel("Sin tiendas", color = Faltis.Warn)
            Spacer(Modifier.height(6.dp))
            Text("Añade tus tiendas para ver precios y comparar.", style = Type.body)
            Spacer(Modifier.height(12.dp))
            PrimaryButton("Añadir tiendas", onStores, Modifier.fillMaxWidth())
            return@GradientCard
        }
        var storeMenu by remember { mutableStateOf(false) }
        // Etiqueta ENCIMA del selector: en fila se partia con el texto al 150 %.
        SectionLabel("Estimado en", color = Faltis.Ink2)
        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(Faltis.Elevated).clickable(enabled = ui.list?.storeId == null) { storeMenu = true }
                        .heightIn(min = 40.dp).padding(start = 4.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ChainChip(ui.store.chainId, 28.dp)
                    Text(ui.store.name, style = Type.row.copy(fontSize = 14.sp), maxLines = 1)
                    if (ui.list?.storeId == null) Icon(Icons.Filled.ArrowDropDown, "Cambiar de tienda", Modifier.size(18.dp))
                }
                DropdownMenu(storeMenu, { storeMenu = false }) {
                    stores.forEach { (id, name) ->
                        DropdownMenuItem(text = { Text(name) }, leadingIcon = { ChainChip(chainOf.getValue(id), 26.dp) }, onClick = { onSelectStore(id); storeMenu = false })
                    }
                }
            }
        }
        val basket = ui.basket
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                if ((basket?.pricedCount ?: 0) == 0) {
                    // Arranque en frio: sin precios todavia no tiene sentido enseñar "0,00 €".
                    Text("Aún sin precios", style = Type.screenTitle)
                    Text("Escanea el ticket de tu próxima compra y verás aquí lo que te cuesta esta lista.", style = Type.secondary, modifier = Modifier.padding(top = 4.dp))
                    return@Column
                }
                Text(euros(basket?.totalCents ?: 0), style = Type.heroNumber)
                val old = basket?.oldestDays
                Text(
                    "con precio" + (old?.let { " · el más antiguo, hace ${if (it == 0L) "menos de un día" else "$it d"}" } ?: ""),
                    style = Type.secondary, modifier = Modifier.padding(top = 4.dp),
                )
            }
            CoverageRing(basket?.pricedCount ?: 0, basket?.comparableCount ?: 0)
        }
        // La busqueda tarda unos segundos: sin aviso parecia que faltaban precios.
        if (webBusy) Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Faltis.Ink2)
            Text("Buscando precios en la web…", style = Type.secondary.copy(fontSize = 13.sp))
        }
        if (basket?.usesWebPrices() == true) WebPriceNotice(Modifier.padding(top = 10.dp))
        // Mejor alternativa (sobre los articulos comunes) -> Comparar.
        val c = ui.comparison
        val alt = (c as? Comparison.Ranking)?.let { r ->
            val best = r.best
            val mine = r.ranked.firstOrNull { it.basket.store.id == ui.store.id }
            if (mine != null && best.basket.store.id != ui.store.id) best to mine.deltaCents else null
        }
        Spacer(Modifier.height(14.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(if (alt != null) Faltis.Saving.copy(alpha = 0.10f) else Faltis.Elevated.copy(alpha = 0.6f))
                .border(1.dp, if (alt != null) Faltis.Saving.copy(alpha = 0.35f) else Faltis.Line, RoundedCornerShape(14.dp))
                .clickable(onClick = onCompare).heightIn(min = 52.dp).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (alt != null) {
                ChainChip(alt.first.basket.store.chainId, 30.dp)
                Column(Modifier.weight(1f)) {
                    Text("${alt.first.basket.store.name}  −${euros(alt.second)}", style = Type.row.copy(fontSize = 14.sp, color = Faltis.Ink))
                    Text("sobre ${(c as Comparison.Ranking).commonNeedIds.size} artículos comunes", style = Type.secondary.copy(fontSize = 12.sp))
                }
            } else {
                Text("Comparar con tus tiendas", style = Type.row.copy(fontSize = 14.sp), modifier = Modifier.weight(1f))
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = if (alt != null) Faltis.Saving else Faltis.Ink2)
        }
        Spacer(Modifier.height(10.dp))
        PrimaryButton("Ir a comprar", onShop, Modifier.fillMaxWidth(), icon = Icons.Filled.ShoppingCart)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NeedRow(n: NeedEntity, price: NeedPriceResult?, categoryId: String?, onClick: () -> Unit, onDelete: () -> Unit) {
    val state = rememberSwipeToDismissBoxState(confirmValueChange = { v ->
        if (v == SwipeToDismissBoxValue.EndToStart) { onDelete(); true } else false
    })
    SwipeToDismissBox(
        state = state,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Faltis.Warn.copy(alpha = 0.18f)).padding(end = 20.dp), contentAlignment = Alignment.CenterEnd) {
                Icon(Icons.Outlined.Delete, "Borrar", tint = Faltis.Warn)
            }
        },
    ) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Faltis.Surface)
                .border(1.dp, Faltis.Elevated, RoundedCornerShape(16.dp)).clickable(onClick = onClick)
                .heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ProductImage((price as? NeedPriceResult.Priced)?.value?.product?.imageUrl, categoryId, 44.dp)
            Column(Modifier.weight(1f)) {
                Text(n.text, style = Type.row, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text("${qtyText(n.qty)} ${n.unit}", style = Type.secondary.copy(fontSize = 14.sp))
                val restr = n.restrictions.split('|').filter { it.isNotBlank() }
                val pinned = (price as? NeedPriceResult.Priced)?.value?.takeIf { n.pinnedProductId != null }
                // El producto fijado solo se nombra si no es lo mismo que ya pone el articulo.
                val showPinned = n.pinnedProductId != null && pinned?.product?.name?.substringBefore(" · ")?.let { com.marcmayol.faltis.core.Text.normalize(it) != com.marcmayol.faltis.core.Text.normalize(n.text) } != false
                if (restr.isNotEmpty() || showPinned || n.typeId == null) {
                    Row(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        restr.forEach { Chip(it) }
                        if (showPinned) {
                            Icon(Icons.Filled.PushPin, "Producto fijado", tint = Faltis.Brand, modifier = Modifier.size(13.dp))
                            Text(pinned?.product?.name ?: "fijado", style = Type.secondary.copy(fontSize = 12.sp), maxLines = 1)
                        }
                        if (n.typeId == null) Chip("sin tipo", Faltis.Warn, Faltis.Warn.copy(alpha = 0.12f))
                    }
                }
            }
            PriceColumn(price)
        }
    }
}

@Composable
fun PriceColumn(price: NeedPriceResult?) {
    Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        when (price) {
            is NeedPriceResult.Priced -> {
                Text(euros(price.value.costCents), style = Type.price)
                Seal(sealText(price.value), sealStyle(price.value))
            }
            is NeedPriceResult.Missing -> {
                Text(
                    when (price.reason) {
                        NoPriceReason.PINNED_NOT_HERE -> "no está aquí"
                        NoPriceReason.NO_RECENT_PRICE -> "sin precio reciente"
                        NoPriceReason.NOT_COMPARABLE -> "no comparable"
                        NoPriceReason.NO_PRODUCT -> "sin precio"
                    },
                    style = Type.secondary.copy(color = Faltis.Ink3),
                )
                Seal("—")
            }
            null -> Text("…", style = Type.secondary)
        }
    }
}

/** De donde sale un precio, igual en todas las pantallas. "cadena": de otra tienda de la misma cadena. */
fun sourceLabel(source: PriceSource, chainLevel: Boolean): String = when {
    source == PriceSource.SAMPLE -> "ejemplo"
    source == PriceSource.WEB -> "web"
    chainLevel -> "cadena"
    else -> when (source) {
        PriceSource.TICKET_PDF -> "pdf"
        PriceSource.TICKET_PHOTO -> "ticket"
        PriceSource.MANUAL -> "manual"
        PriceSource.COMMUNITY -> "comunidad"
        PriceSource.WEB -> "web"
        PriceSource.SAMPLE -> "ejemplo"
    }
}

fun sealText(p: PricedNeed): String = "${sourceLabel(p.obs.source, p.fromChainLevel)} · ${ageText(p.ageDays)}"

fun sealStyle(p: PricedNeed): SealStyle = when {
    p.freshness == Freshness.STALE -> SealStyle.STALE
    p.obs.source == PriceSource.SAMPLE -> SealStyle.NEUTRAL
    else -> SealStyle.FRESH
}

@Composable
fun AddBar(onAdd: (String) -> Unit, modifier: Modifier = Modifier, placeholder: String = "Añadir: leche, pan, 2 kg de naranjas…") {
    var text by rememberSaveable { mutableStateOf("") }
    Row(
        modifier.fillMaxWidth().imePadding().clip(RoundedCornerShape(18.dp)).background(Faltis.Surface)
            .border(1.dp, Faltis.LineStrong, RoundedCornerShape(18.dp)).padding(start = 4.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = text, onValueChange = { text = it },
            placeholder = { Text(placeholder, style = Type.body.copy(color = Faltis.Ink3), maxLines = 1) },
            modifier = Modifier.weight(1f),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) { onAdd(text); text = "" } }),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
                focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
                cursorColor = Faltis.Saving,
            ),
        )
        IconButton(
            onClick = { if (text.isNotBlank()) { onAdd(text); text = "" } },
            modifier = Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(if (text.isBlank()) Faltis.Elevated else Faltis.Action),
        ) { Icon(Icons.Filled.Add, "Añadir", tint = if (text.isBlank()) Faltis.Ink3 else Color.White) }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NeedSheet(n: NeedEntity, vm: AppViewModel, onDismiss: () -> Unit, onPick: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf(n.text) }
    var qty by remember { mutableStateOf(n.qty) }
    var unit by remember { mutableStateOf(n.unit) }
    var typeId by remember { mutableStateOf(n.typeId) }
    var restrictions by remember { mutableStateOf(n.restrictions.split('|').filter { it.isNotBlank() }.toSet()) }
    var pinned by remember { mutableStateOf(n.pinnedProductId) }
    var chain by remember { mutableStateOf(n.chainId) }
    var typeQuery by remember { mutableStateOf("") }
    val book by vm.priceBook.collectAsState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Faltis.Surface) {
        // Desplazable: con todo lo que lleva, «Guardar» se quedaba fuera de la pantalla.
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            OutlinedTextField(text, { text = it }, label = { Text("Qué necesitas") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            // ¿Donde lo compras? Con un producto fijado manda su super; elegir otro lo suelta.
            val lists by vm.lists.collectAsState()
            val stores by vm.stores.collectAsState()
            val pinnedChain = pinned?.let { book?.productsById?.get(it)?.chainId }
            val shownChain = pinnedChain ?: chain
            SectionLabel("¿Dónde lo compras?")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SelectPill("En cualquiera", shownChain == null) { chain = null; pinned = null }
                (stores.map { it.chainId } + listOfNotNull(shownChain)).distinct().forEach { c ->
                    SelectPill(com.marcmayol.faltis.core.Chains.byId(c).name, shownChain == c) {
                        if (c != pinnedChain) pinned = null
                        chain = c
                    }
                }
            }
            val listTargets = lists.filter { it.id != n.listId }
            if (listTargets.isNotEmpty()) {
                SectionLabel("Mover a otra lista")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listTargets.forEach { l -> SelectPill(l.name, false) { vm.moveNeed(n, l.id); onDismiss() } }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SquareIconButton(Icons.Filled.Remove, "Menos", { qty = (qty - 1).coerceAtLeast(0.25) })
                Text(qtyText(qty), style = Type.bigNumber.copy(fontSize = 28.sp), modifier = Modifier.width(72.dp))
                SquareIconButton(Icons.Filled.Add, "Más", { qty += 1 })
                Spacer(Modifier.weight(1f))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QtyUnit.entries.forEach { u ->
                    SelectPill(u.code, unit == u.code) { unit = u.code }
                }
            }
            SectionLabel("Tipo")
            val currentType = vm.repo.taxonomy.byId(typeId)
            Text(currentType?.let { "${it.name} · ${it.categoryName}" } ?: "Sin tipo: no se podrá comparar", style = Type.body.copy(color = if (currentType == null) Faltis.Warn else Faltis.Ink))
            OutlinedTextField(typeQuery, { typeQuery = it }, label = { Text("Buscar tipo") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (typeQuery.length >= 2) {
                val matches = vm.repo.taxonomy.types.filter { com.marcmayol.faltis.core.Text.normalize(it.name).contains(com.marcmayol.faltis.core.Text.normalize(typeQuery)) }.take(6)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    matches.forEach { t -> SelectPill(t.name, t.id == typeId) { typeId = t.id; typeQuery = "" } }
                }
            }
            SectionLabel("Restricciones")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("sin lactosa", "sin gluten", "ecológico", "marca blanca", "sin azúcar").forEach { r ->
                    SelectPill(r, r in restrictions) { restrictions = if (r in restrictions) restrictions - r else restrictions + r }
                }
            }
            SectionLabel("Producto")
            val pinnedName = pinned?.let { book?.productsById?.get(it)?.name }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(pinnedName ?: "Cualquiera (el más barato)", style = Type.body, modifier = Modifier.weight(1f))
                if (pinned != null) TextButton({ pinned = null }) { Text("Quitar") }
                TextButton(onPick) { Text("Elegir") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Eliminar", { vm.deleteNeed(n); onDismiss() }, Modifier.weight(1f))
                PrimaryButton("Guardar", {
                    vm.saveNeed(n.copy(text = text.ifBlank { n.text }, qty = qty, unit = unit, typeId = typeId, restrictions = restrictions.joinToString("|"), pinnedProductId = pinned, chainId = pinned?.let { book?.productsById?.get(it)?.chainId } ?: chain))
                    onDismiss()
                }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun SelectPill(text: String, selected: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(if (selected) Faltis.ActionSoft else Faltis.Elevated)
            .border(1.dp, if (selected) Faltis.Action else Faltis.LineStrong, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).heightIn(min = 40.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        icon?.let { Icon(it, null, tint = if (selected) Faltis.Brand else Faltis.Ink2, modifier = Modifier.size(18.dp)) }
        Text(text, style = Type.row.copy(fontSize = 14.sp, fontWeight = if (selected) FontWeight.W700 else FontWeight.W500))
    }
}

@Composable
fun TextDialog(
    title: String, label: String, initial: String, onDismiss: () -> Unit, multiline: Boolean = false,
    // Lo que explica el campo va debajo: en la etiqueta, con letra grande, salta de linea y pisa el borde.
    hint: String? = null, keyboard: KeyboardType = KeyboardType.Text, onOk: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Faltis.Surface,
        title = { Text(title, style = Type.title) },
        text = {
            OutlinedTextField(
                value, { value = it }, label = { Text(label, maxLines = 1) }, singleLine = !multiline,
                minLines = if (multiline) 4 else 1, modifier = Modifier.fillMaxWidth(),
                supportingText = hint?.let { { Text(it) } },
                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
            )
        },
        confirmButton = { TextButton({ onOk(value); onDismiss() }, enabled = value.isNotBlank()) { Text("Aceptar") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } },
    )
}

/** ¿Algun precio de la cesta sale de la web de la cadena? */
fun com.marcmayol.faltis.core.StoreBasket.usesWebPrices(): Boolean =
    results.any { it is NeedPriceResult.Priced && it.value.obs.source == PriceSource.WEB }

/** El aviso de los precios web: pueden no ser los de tu tienda. */
@Composable
fun WebPriceNotice(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Faltis.Warn.copy(alpha = 0.10f))
            .border(1.dp, Faltis.Warn.copy(alpha = 0.30f), RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Filled.Info, null, tint = Faltis.Warn, modifier = Modifier.size(18.dp).padding(top = 1.dp))
        Text(com.marcmayol.faltis.core.WebCatalog.DISCLAIMER, style = Type.secondary.copy(fontSize = 12.sp, lineHeight = 16.sp))
    }
}

/** Super de un articulo: el de su producto fijado o el elegido; null, en cualquiera. */
fun chainOfNeed(n: NeedEntity, book: com.marcmayol.faltis.core.PriceBook?): String? =
    n.pinnedProductId?.let { book?.productsById?.get(it)?.chainId } ?: n.chainId

@Composable
fun SuperHeader(chainId: String?, count: Int, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (chainId != null) ChainChip(chainId, 28.dp)
        else Box(Modifier.size(28.dp).clip(RoundedCornerShape(8.dp)).background(Faltis.Elevated), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.ShoppingCart, null, tint = Faltis.Ink2, modifier = Modifier.size(16.dp))
        }
        Text(chainId?.let { com.marcmayol.faltis.core.Chains.byId(it).name } ?: "En cualquier súper", style = Type.title.copy(fontSize = 17.sp), modifier = Modifier.weight(1f))
        Text("$count", style = Type.secondary)
    }
}
