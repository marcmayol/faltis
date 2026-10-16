package com.marcmayol.faltis.ui

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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marcmayol.faltis.core.Chains
import com.marcmayol.faltis.core.Packs
import com.marcmayol.faltis.core.PriceBook
import com.marcmayol.faltis.core.PriceKind
import com.marcmayol.faltis.core.PriceSource
import com.marcmayol.faltis.core.ProductRef
import com.marcmayol.faltis.core.Text as T
import com.marcmayol.faltis.core.WebCatalog
import com.marcmayol.faltis.ui.theme.Faltis
import com.marcmayol.faltis.ui.theme.Type
import kotlinx.coroutines.delay

/** Lo que cuesta un producto del catalogo: el ultimo precio visto (ticket o web) y si la web lo tiene en oferta. */
class ShelfPrice(val cents: Long, val unitCents: Long?, val webOffer: Boolean)

fun shelfPrice(book: PriceBook, p: ProductRef): ShelfPrice? {
    val o = book.observationsOf(p.id).maxByOrNull { it.observedAt } ?: return null
    // A granel el precio ya es por kg o l.
    val unit = if (p.netQty == null && p.netUnit != null) o.priceCents else Packs.unitPrice(o.priceCents.toDouble(), p.netQty, p.netUnit)?.toLong()
    return ShelfPrice(o.priceCents, unit, o.source == PriceSource.WEB && o.kind == PriceKind.PROMO)
}

/** Aviso de las ofertas que salen de las webs: no siempre valen en tu tienda. */
const val WEB_OFFER_NOTICE = "«Oferta» es lo que dice la web del súper: puede ser solo de su tienda online o no aplicarse en tu zona."

// ─── Productos: el catalogo de un super ──────────────────────────────────────
@Composable
fun CatalogScreen(vm: AppViewModel, onProduct: (Long) -> Unit, onSettings: () -> Unit, contentPadding: PaddingValues) {
    val book by vm.priceBook.collectAsState()
    val stores by vm.stores.collectAsState()
    val lists by vm.lists.collectAsState()
    val settings by vm.settings.collectAsState()
    val needs by vm.activeNeeds.collectAsState()
    val chosen by vm.catalogChain.collectAsState()
    val chain = chosen ?: stores.firstOrNull()?.chainId ?: "mercadona"
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var onlyFav by rememberSaveable { mutableStateOf(false) }
    var onlyOffers by rememberSaveable { mutableStateOf(false) }
    var grid by rememberSaveable { mutableStateOf(true) }
    var superSheet by remember { mutableStateOf(false) }
    var categorySheet by remember { mutableStateOf(false) }
    var listMenu by remember { mutableStateOf(false) }
    var newList by remember { mutableStateOf(false) }
    val categories = remember { vm.repo.taxonomy.categories() }

    // Lo que trae la web del super para lo buscado (o para la categoria), en su orden.
    var webIds by remember { mutableStateOf<List<Long>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(chain, query, category) {
        webIds = emptyList()
        val q = query.trim()
        val terms = when {
            q.length >= 2 -> listOf(q)
            category != null -> vm.repo.taxonomy.types.filter { it.categoryId == category }.take(6).map { it.name }
            else -> emptyList()
        }
        if (terms.isEmpty()) { busy = false; return@LaunchedEffect }
        delay(450) // que no busque a cada letra
        busy = true
        val found = mutableListOf<Long>()
        for (t in terms) {
            val ids = vm.repo.catalogSearch(chain, t)
            if (ids == null) break
            found += ids
            webIds = found.distinct()
        }
        busy = false
    }

    val webAvailable = settings?.webPrices == true && chain in WebCatalog.chains
    val results = remember(book, chain, query, category, onlyFav, onlyOffers, webIds) {
        val b = book ?: return@remember emptyList()
        val words = T.words(query)
        val inCategory: (ProductRef) -> Boolean = { p -> category == null || vm.repo.taxonomy.byId(p.typeId)?.categoryId == category }
        val local = b.productsById.values.filter { it.chainId == chain && inCategory(it) && (words.isEmpty() || words.all { w -> T.normalize(it.name).contains(w) }) }
        val web = webIds.mapNotNull { b.productsById[it] }.filter(inCategory)
        (web + local.sortedWith(compareBy({ !it.isFavorite }, { it.name }))).distinctBy { it.id }
            .map { it to shelfPrice(b, it) }
            .filter { (p, price) -> (!onlyFav || p.isFavorite) && (!onlyOffers || price?.webOffer == true) }
            .take(120)
    }
    val activeList = lists.firstOrNull { it.id == settings?.activeListId } ?: lists.firstOrNull()
    val qtyOf: (Long) -> Double = { id -> needs.firstOrNull { it.pinnedProductId == id }?.qty ?: 0.0 }

    HaloBackground(color = Faltis.Saving, second = Faltis.Blue) {
        Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
            // Cabecera: el super (se toca para cambiarlo) y el titulo.
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(
                    Modifier.clip(RoundedCornerShape(16.dp)).background(Faltis.Surface).border(1.dp, Faltis.LineStrong, RoundedCornerShape(16.dp))
                        .clickable { superSheet = true }.heightIn(min = 52.dp).padding(start = 6.dp, end = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ChainChip(chain, 40.dp)
                    Icon(Icons.Filled.ArrowDropDown, "Cambiar de supermercado", tint = Faltis.Ink2)
                }
                Column(Modifier.weight(1f)) {
                    Text("Productos", style = Type.screenTitle.copy(fontSize = 28.sp))
                    Text(Chains.byId(chain).name, style = Type.secondary.copy(fontSize = 14.sp))
                }
                // Vista en cuadricula o en lista (el icono es la vista a la que se pasa).
                SquareIconButton(if (grid) Icons.AutoMirrored.Filled.List else Icons.Filled.GridView, if (grid) "Ver en lista" else "Ver en cuadrícula", { grid = !grid })
            }
            // «Añadiendo a: lista · Cambiar»
            Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp)) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { listMenu = true }.heightIn(min = 44.dp).padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(Icons.AutoMirrored.Filled.List, null, tint = Faltis.Ink2, modifier = Modifier.size(20.dp))
                    Text("Añadiendo a: ${activeList?.name ?: "tu lista"}", style = Type.row.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("Cambiar", style = Type.row.copy(fontSize = 15.sp, fontWeight = FontWeight.W700, color = Faltis.Brand))
                }
                DropdownMenu(listMenu, { listMenu = false }) {
                    lists.forEach { l -> DropdownMenuItem(text = { Text(l.name) }, onClick = { vm.selectList(l.id); listMenu = false }) }
                    DropdownMenuItem(text = { Text("Nueva lista…") }, onClick = { listMenu = false; newList = true }, leadingIcon = { Icon(Icons.Filled.Add, null) })
                }
            }
            OutlinedTextField(
                query, { query = it }, placeholder = { Text("Buscar en ${Chains.byId(chain).name}") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = { if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), singleLine = true, shape = RoundedCornerShape(16.dp),
            )
            // Al cambiar lo buscado, se vuelve arriba.
            val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
            LaunchedEffect(chain, query, category, onlyFav, onlyOffers) { gridState.scrollToItem(0) }
            LazyVerticalGrid(
                if (grid) GridCells.Adaptive(150.dp) else GridCells.Fixed(1),
                Modifier.weight(1f), state = gridState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                // Los filtros se van con el scroll: dejan sitio a los productos. En varias lineas si no caben.
                item(span = { GridItemSpan(maxLineSpan) }) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        CategoryPill(categories, category) { categorySheet = true }
                        SelectPill("Favoritos", onlyFav, if (onlyFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder) { onlyFav = !onlyFav }
                        SelectPill("Ofertas", onlyOffers, Icons.Filled.LocalOffer) { onlyOffers = !onlyOffers }
                    }
                }
                if (!webAvailable) item(span = { GridItemSpan(maxLineSpan) }) {
                    if (chain in WebCatalog.chains) SurfaceCard(Modifier.fillMaxWidth()) {
                        Text("Para ver todo el catálogo de ${Chains.byId(chain).name}, activa los precios web en Ajustes con tu código postal. Mientras, aquí salen los productos de tus tickets.", style = Type.secondary.copy(fontSize = 14.sp))
                        SecondaryButton("Ir a Ajustes", onSettings, Modifier.fillMaxWidth().padding(top = 10.dp))
                    } else SurfaceCard(Modifier.fillMaxWidth()) {
                        Text("${Chains.byId(chain).name} no tiene tienda online: aquí salen los productos de tus tickets de ${Chains.byId(chain).name}.", style = Type.secondary.copy(fontSize = 14.sp))
                    }
                }
                if (results.any { it.second?.webOffer == true }) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(WEB_OFFER_NOTICE, style = Type.secondary.copy(fontSize = 12.sp))
                }
                if (results.isEmpty() && !busy) item(span = { GridItemSpan(maxLineSpan) }) {
                    when {
                        onlyFav || onlyOffers -> EmptyState(if (onlyFav) "Sin favoritos aquí" else "Sin ofertas", "Ningún producto coincide con tu búsqueda o tus filtros.") {
                            SecondaryButton("Limpiar filtros", { onlyFav = false; onlyOffers = false; category = null; query = "" })
                        }
                        query.isBlank() && category == null -> EmptyState("Busca o elige una categoría", "Escribe arriba lo que buscas («leche», «yogur»…) o toca «Categoría» para ver los productos de ${Chains.byId(chain).name}.")
                        else -> EmptyState("Sin resultados", "Prueba con otra palabra u otra categoría.")
                    }
                }
                items(results, key = { it.first.id }) { (p, price) ->
                    val qty = qtyOf(p.id)
                    val cat = vm.repo.taxonomy.byId(p.typeId)?.categoryId
                    if (grid) ProductTile(p, price, cat, qty, { onProduct(p.id) }, { vm.toggleFavorite(p) }) { vm.setInList(p.id, it) }
                    else ProductListRow(p, price, cat, qty, { onProduct(p.id) }, { vm.toggleFavorite(p) }) { vm.setInList(p.id, it) }
                }
            }
        }
    }
    if (superSheet) SuperSheet(chain, stores.map { it.chainId }.distinct(), { vm.catalogChain.value = it; superSheet = false }, { superSheet = false })
    if (categorySheet) CategorySheet(categories, category, { category = it }, { categorySheet = false })
    if (newList) TextDialog("Nueva lista", "Nombre", "", { newList = false }) { vm.newList(it) }
}

/** Precio por kg/l/ud en texto: «7,90 €/kg». */
private fun unitText(p: ProductRef, price: ShelfPrice?): String? =
    price?.unitCents?.takeIf { p.netUnit != null }?.let { "${euros(it)}${Packs.unitPriceLabel(p.netUnit).removePrefix("€")}" }

@Composable
private fun ProductTile(p: ProductRef, price: ShelfPrice?, categoryId: String?, qty: Double, onOpen: () -> Unit, onFav: () -> Unit, onQty: (Double) -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(Faltis.Surface)
            .border(if (qty > 0) 2.dp else 1.dp, if (qty > 0) Faltis.Saving else Faltis.Elevated, shape)
            .clickable(onClick = onOpen).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box {
            ShelfImage(p.imageUrl, categoryId, Modifier.fillMaxWidth().aspectRatio(1f))
            FavHeart(p.isFavorite, onFav, Modifier.align(Alignment.TopEnd))
            if (price?.webOffer == true) OfferBadge(Modifier.align(Alignment.TopStart).padding(6.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(price?.let { euros(it.cents) } ?: "sin precio", style = if (price != null) Type.price.copy(fontSize = 18.sp) else Type.secondary)
        Text(p.name.substringBefore(" · "), style = Type.row.copy(fontSize = 15.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(listOfNotNull(formatLabel(p).ifBlank { null }, unitText(p, price)).joinToString(" · "), style = Type.secondary.copy(fontSize = 12.sp), maxLines = 2)
        Spacer(Modifier.height(4.dp))
        CatalogQty(qty, Modifier.fillMaxWidth(), onQty)
    }
}

@Composable
private fun ProductListRow(p: ProductRef, price: ShelfPrice?, categoryId: String?, qty: Double, onOpen: () -> Unit, onFav: () -> Unit, onQty: (Double) -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Faltis.Surface)
            .border(if (qty > 0) 2.dp else 1.dp, if (qty > 0) Faltis.Saving else Faltis.Elevated, shape)
            .clickable(onClick = onOpen).padding(10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(112.dp)) {
            ShelfImage(p.imageUrl, categoryId, Modifier.fillMaxSize())
            FavHeart(p.isFavorite, onFav, Modifier.align(Alignment.TopEnd))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(p.name.substringBefore(" · "), style = Type.row.copy(fontSize = 16.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(formatLabel(p).ifBlank { null }, unitText(p, price)).joinToString(" · "), style = Type.secondary.copy(fontSize = 13.sp))
            if (price?.webOffer == true) OfferBadge()
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(price?.let { euros(it.cents) } ?: "sin precio", style = if (price != null) Type.price.copy(fontSize = 19.sp) else Type.secondary, modifier = Modifier.weight(1f))
            }
            CatalogQty(qty, Modifier.fillMaxWidth(), onQty)
        }
    }
}

/** Foto del producto sobre blanco, como en la tienda; sin foto, el icono de su categoria. */
@Composable
fun ShelfImage(url: String?, categoryId: String?, modifier: Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Box(modifier.clip(shape).background(if (url != null) Color.White else Faltis.Elevated), contentAlignment = Alignment.Center) {
        var failed by remember(url) { mutableStateOf(url == null) }
        if (failed) Icon(categoryIcon(categoryId), null, tint = Faltis.Ink3, modifier = Modifier.size(40.dp))
        if (url != null) coil.compose.AsyncImage(
            model = url, contentDescription = null, contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(8.dp), onError = { failed = true },
        )
    }
}

@Composable
private fun FavHeart(on: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick, modifier.size(48.dp)) {
        Box(Modifier.size(32.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.85f)), contentAlignment = Alignment.Center) {
            Icon(if (on) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder, if (on) "Quitar de favoritos" else "Guardar en favoritos", tint = if (on) Color(0xFFE5484D) else Color(0xFF555555), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
fun OfferBadge(modifier: Modifier = Modifier) {
    Row(
        modifier.clip(RoundedCornerShape(8.dp)).background(Faltis.Saving).padding(horizontal = 7.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(Icons.Filled.LocalOffer, null, tint = Color.White, modifier = Modifier.size(12.dp))
        Text("Oferta web", style = Type.label.copy(color = Color.White, fontSize = 11.sp, letterSpacing = 0.sp))
    }
}

/** «+ Añadir» o «– N +»: lo que hay de este producto en la lista. A cero, sale de la lista. */
@Composable
fun CatalogQty(qty: Double, modifier: Modifier = Modifier, onChange: (Double) -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    if (qty <= 0) {
        Row(
            modifier.clip(shape).background(Faltis.Elevated).clickable { onChange(1.0) }.heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
        ) {
            Icon(Icons.Filled.Add, null, tint = Faltis.Ink, modifier = Modifier.size(20.dp))
            Text(" Añadir", style = Type.row.copy(fontSize = 15.sp))
        }
        return
    }
    Row(modifier.clip(shape).background(Faltis.Saving), verticalAlignment = Alignment.CenterVertically) {
        IconButton({ onChange(qty - 1) }, Modifier.size(44.dp)) { Icon(Icons.Filled.Remove, "Uno menos", tint = Color.White) }
        Text(qtyText(qty), style = Type.price.copy(fontSize = 16.sp, color = Color.White), textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.weight(1f).widthIn(min = 24.dp))
        IconButton({ onChange(qty + 1) }, Modifier.size(44.dp)) { Icon(Icons.Filled.Add, "Uno más", tint = Color.White) }
    }
}

/** «Elige supermercado»: tus tiendas arriba y todas las cadenas en rejilla. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SuperSheet(current: String, mine: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet, containerColor = Faltis.Surface) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Elige supermercado", style = Type.title)
            if (mine.isNotEmpty()) {
                SectionLabel("Tus tiendas")
                SuperGrid(mine, current, onPick)
            }
            SectionLabel("Más supermercados")
            SuperGrid(Chains.all.map { it.id }.filter { it !in mine }.sortedBy { if (it in WebCatalog.chains) 0 else 1 }, current, onPick)
            Text("Los que no tienen tienda online solo enseñan los productos de tus tickets.", style = Type.secondary.copy(fontSize = 12.sp))
        }
    }
}

@Composable
private fun SuperGrid(chains: List<String>, current: String, onPick: (String) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 2) {
        chains.forEach { c ->
            val sel = c == current
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(if (sel) Faltis.ActionSoft else Faltis.Elevated)
                    .border(if (sel) 2.dp else 1.dp, if (sel) Faltis.Action else Faltis.Line, RoundedCornerShape(14.dp))
                    .clickable { onPick(c) }.heightIn(min = 56.dp).padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ChainChip(c, 32.dp)
                Text(Chains.byId(c).name, style = Type.row.copy(fontSize = 14.sp), maxLines = 2)
            }
        }
        if (chains.size % 2 == 1) Spacer(Modifier.weight(1f))
    }
}
