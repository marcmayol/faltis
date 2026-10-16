package com.marcmayol.faltis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marcmayol.faltis.core.Chains
import com.marcmayol.faltis.core.Comparison
import com.marcmayol.faltis.core.Equivalence
import com.marcmayol.faltis.core.Freshness
import com.marcmayol.faltis.core.PriceBook
import com.marcmayol.faltis.core.PriceKind
import com.marcmayol.faltis.core.PriceSource
import com.marcmayol.faltis.core.Packs
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import com.marcmayol.faltis.core.ProductRef
import com.marcmayol.faltis.core.Text as T
import com.marcmayol.faltis.ui.theme.Faltis
import com.marcmayol.faltis.ui.theme.Type
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class BestPrice(val product: ProductRef, val storeName: String, val cents: Long, val unitCents: Long?, val seal: String, val style: SealStyle)

private fun bestIn(book: PriceBook, p: ProductRef, stores: List<com.marcmayol.faltis.core.StoreRef>, promos: Boolean): BestPrice? {
    val now = Instant.now()
    return stores.filter { it.chainId == p.chainId }.mapNotNull { s ->
        book.bestObservation(p.id, s, now, promos)?.let { (o, chainLevel) ->
            val age = PriceBook.ageDays(o.observedAt, now)
            val src = sourceLabel(o.source, chainLevel)
            BestPrice(p, s.name, o.priceCents, Packs.unitPrice(o.priceCents.toDouble(), p.netQty, p.netUnit)?.toLong(), "$src · ${ageText(age)}",
                if (age > PriceBook.FRESH_DAYS) SealStyle.STALE else if (o.source == PriceSource.SAMPLE) SealStyle.NEUTRAL else SealStyle.FRESH)
        }
    }.minByOrNull { it.cents }
}

fun formatLabel(p: ProductRef): String = when {
    p.netQty != null && p.netUnit != null -> "${qtyText(p.netQty!!)} ${p.netUnit!!.code}"
    p.netUnit != null -> "a granel (${Packs.unitPriceLabel(p.netUnit)})"
    else -> ""
}

// ─── P-04 · Precios ───────────────────────────────────────────────────────────
@Composable
fun PricesScreen(vm: AppViewModel, onProduct: (Long) -> Unit, onCompare: () -> Unit, contentPadding: PaddingValues, pickForNeed: Long? = null, onPicked: (() -> Unit)? = null, onBack: (() -> Unit)? = null) {
    val book by vm.priceBook.collectAsState()
    val stores by vm.stores.collectAsState()
    val ui by vm.listUi.collectAsState()
    val settings by vm.settings.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    var onlyMine by rememberSaveable { mutableStateOf(true) }
    var onlyFav by rememberSaveable { mutableStateOf(false) }
    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var categorySheet by remember { mutableStateOf(false) }
    val categories = remember { vm.repo.taxonomy.categories() }
    val storeRefs = with(vm.repo) { stores.map { it.toRef() } }
    val myChains = stores.map { it.chainId }.toSet()

    val results = remember(book, query, onlyMine, stores, onlyFav, category) {
        val b = book ?: return@remember emptyList()
        val words = T.words(query)
        b.productsById.values.asSequence()
            .filter { !onlyMine || it.chainId in myChains }
            .filter { !onlyFav || it.isFavorite }
            .filter { category == null || vm.repo.taxonomy.byId(it.typeId)?.categoryId == category }
            .filter { p -> words.isEmpty() || words.all { w -> T.normalize(p.name).contains(w) } }
            // Los favoritos y lo buscado salen aunque no tengan precio en tus tiendas.
            .mapNotNull { p -> bestIn(b, p, storeRefs, settings?.includePromos == true) ?: if (words.isNotEmpty() || onlyFav) BestPrice(p, "", -1, null, "—", SealStyle.NEUTRAL) else null }
            .sortedWith(compareBy({ if (words.isNotEmpty() && T.normalize(it.product.name).startsWith(words.first())) 0 else 1 }, { if (it.cents < 0) 1 else 0 }, { it.product.name }))
            .take(80).toList()
    }

    HaloBackground(color = Faltis.Saving, second = Faltis.Blue) {
        Column(Modifier.fillMaxSize().padding(top = contentPadding.calculateTopPadding())) {
            if (pickForNeed != null) TopBar("Elegir producto", "Se fijará en la necesidad", onBack)
            else Text("Precios", style = Type.screenTitle, modifier = Modifier.padding(start = 20.dp, top = 18.dp, bottom = 8.dp))
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (pickForNeed == null) item {
                    val c = ui.comparison as? Comparison.Ranking
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Faltis.Surface).border(1.dp, Faltis.LineStrong, RoundedCornerShape(18.dp))
                            .clickable(onClick = onCompare).heightIn(min = 60.dp).padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Column(Modifier.weight(1f)) {
                            SectionLabel("Tu lista en tus tiendas", color = Faltis.Saving)
                            Text(
                                c?.let { "Mejor: ${it.best.basket.store.name}" + (it.ranked.getOrNull(1)?.let { s -> " · ahorras ${euros(s.deltaCents)}" } ?: "") }
                                    ?: "Compara tu lista entre tus tiendas",
                                style = Type.row.copy(fontSize = 15.sp),
                            )
                        }
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = Faltis.Saving)
                    }
                }
                item {
                    OutlinedTextField(
                        query, { query = it }, placeholder = { Text("Busca un producto") }, leadingIcon = { Icon(Icons.Filled.Search, null) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp),
                    )
                }
                item {
                    // En varias lineas si no caben (letra grande o pantalla pequeña).
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SelectPill("Mis tiendas", onlyMine) { onlyMine = true }
                        SelectPill("Todas", !onlyMine) { onlyMine = false }
                        SelectPill("Favoritos", onlyFav, if (onlyFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder) { onlyFav = !onlyFav }
                        CategoryPill(categories, category) { categorySheet = true }
                    }
                }
                if (results.isEmpty()) item {
                    when {
                        onlyFav -> EmptyState("Sin favoritos" + (category?.let { " en esta categoría" } ?: ""), "Abre un producto y toca el corazón para guardarlo aquí.")
                        category != null -> EmptyState("Nada en esta categoría", "Prueba con «Todas» las tiendas o con otra categoría.")
                        else -> EmptyState("Aún no hay precios", "Busca o escanea un ticket para empezar a ver precios.")
                    }
                }
                items(results, key = { it.product.id }) { r ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Faltis.Surface).border(1.dp, Faltis.Elevated, RoundedCornerShape(16.dp))
                            .clickable {
                                if (pickForNeed != null) {
                                    vm.run {
                                        vm.repo.dao.need(pickForNeed)?.let { n -> vm.repo.updateNeed(n.copy(pinnedProductId = r.product.id)) }
                                    }
                                    onPicked?.invoke()
                                } else onProduct(r.product.id)
                            }
                            .heightIn(min = 64.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        ProductImage(r.product.imageUrl, vm.repo.taxonomy.byId(r.product.typeId)?.categoryId, 48.dp)
                        Column(Modifier.weight(1f)) {
                            Text(r.product.name, style = Type.row.copy(fontSize = 15.sp), maxLines = 2)
                            Text(
                                listOfNotNull(
                                    Chains.byId(r.product.chainId).name, formatLabel(r.product).takeIf { it.isNotBlank() },
                                    r.unitCents?.let { "${euros(it)}${Packs.unitPriceLabel(r.product.netUnit).removePrefix("€")}" },
                                ).joinToString(" · "),
                                style = Type.secondary.copy(fontSize = 12.sp),
                            )
                        }
                        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            if (r.cents >= 0) { Text(euros(r.cents), style = Type.price); Seal(r.seal, r.style) } else Text("sin precio", style = Type.secondary)
                        }
                        // Desde favoritos, a la lista de un toque.
                        if (pickForNeed == null && onlyFav) SquareIconButton(Icons.Filled.Add, "Añadir a la lista", {
                            vm.run {
                                val listId = vm.repo.ensureActiveList()
                                vm.repo.addPinned(listId, r.product.name, r.product.typeId, r.product.id)
                                vm.say("«${r.product.name}» añadido a la lista")
                            }
                        })
                    }
                }
            }
        }
    }
    if (categorySheet) CategorySheet(categories, category, { category = it }, { categorySheet = false })
}

// ─── P-05 · Ficha ─────────────────────────────────────────────────────────────
@Composable
fun ProductScreen(vm: AppViewModel, productId: Long, onBack: () -> Unit, onCompare: () -> Unit, contentPadding: PaddingValues) {
    val book by vm.priceBook.collectAsState()
    val stores by vm.stores.collectAsState()
    val settings by vm.settings.collectAsState()
    val b = book
    val p = b?.productsById?.get(productId)
    // Ficha de Open Food Facts: se pide al abrir y otra vez al leer un codigo de barras.
    var reload by remember { mutableStateOf(0) }
    var lookup by remember { mutableStateOf<com.marcmayol.faltis.data.NutritionLookup?>(null) }
    androidx.compose.runtime.LaunchedEffect(productId, reload) {
        lookup = null
        lookup = vm.repo.nutrition(productId, force = reload > 0)
    }
    var scanning by remember { mutableStateOf(false) }
    HaloBackground(color = Faltis.Saving, second = Faltis.Blue) {
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                BackButton(onBack)
                Text(p?.ean?.let { "EAN $it" } ?: "", style = Type.mono.copy(color = Faltis.Ink3), modifier = Modifier.weight(1f).padding(start = 12.dp))
                if (p != null) FavoriteButton(p.isFavorite) { vm.toggleFavorite(p) }
            }
            if (b == null || p == null) { EmptyState("Producto no encontrado", ""); return@Column }
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ProductImage(p.imageUrl ?: (lookup as? com.marcmayol.faltis.data.NutritionLookup.Found)?.facts?.imageUrl, p.typeId?.let { vm.repo.taxonomy.byId(it)?.categoryId }, 96.dp)
                        Column(Modifier.weight(1f)) {
                            SectionLabel(vm.repo.taxonomy.byId(p.typeId)?.let { "${it.categoryName} · ${it.name}" } ?: "sin tipo", color = Faltis.Saving)
                            Text(p.name, style = Type.screenTitle.copy(fontSize = 24.sp), modifier = Modifier.padding(top = 6.dp))
                            Text(listOfNotNull(p.brand, formatLabel(p).ifBlank { null }, Chains.byId(p.chainId).name).joinToString(" · "), style = Type.secondary.copy(fontSize = 14.sp))
                            shelfPrice(b, p)?.let { sp ->
                                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(euros(sp.cents), style = Type.price.copy(fontSize = 26.sp))
                                    sp.unitCents?.takeIf { p.netUnit != null }?.let { Text("${euros(it)}${Packs.unitPriceLabel(p.netUnit).removePrefix("€")}", style = Type.secondary.copy(fontSize = 14.sp)) }
                                }
                                if (sp.webOffer) {
                                    OfferBadge(Modifier.padding(top = 6.dp))
                                    Text(WEB_OFFER_NOTICE, style = Type.secondary.copy(fontSize = 12.sp), modifier = Modifier.padding(top = 4.dp))
                                }
                            }
                        }
                    }
                    SecondaryButton("Comparar en otros súper", onCompare, Modifier.fillMaxWidth().padding(top = 14.dp), icon = Icons.Filled.Search)
                }
                item {
                    val now = Instant.now()
                    val promos = settings?.includePromos == true
                    // Una fila por cada tienda tuya: el producto mismo en su cadena y, en las demas,
                    // el equivalente con precio que mas se le parece (y, a igual parecido, el mas barato).
                    val rows = with(vm.repo) { stores.map { it.toRef() } }.map { s ->
                        if (s.chainId == p.chainId) StoreOffer(s, p, null, b.bestObservation(p.id, s, now, promos))
                        else b.productsById.values.asSequence().filter { it.chainId == s.chainId }
                            .mapNotNull { o -> Equivalence.explain(p, o)?.let { eq -> b.bestObservation(o.id, s, now, promos)?.let { StoreOffer(s, o, eq.level, it) } } }
                            .minWithOrNull(compareBy<StoreOffer> { it.level!!.ordinal }.thenBy { it.unitCents ?: it.best!!.first.priceCents })
                            ?: StoreOffer(s, null, null, null)
                    }
                    // Solo se señala el mas barato entre lo comparable: el mismo producto o un equivalente, con precio por unidad.
                    val cheapest = rows.filter { it.best != null && it.level != com.marcmayol.faltis.core.EquivalenceLevel.SIMILAR && it.unitCents != null }
                        .takeIf { it.size > 1 }?.minByOrNull { it.unitCents!! }
                    SurfaceCard(Modifier.fillMaxWidth()) {
                        SectionLabel("En tus tiendas")
                        if (rows.isEmpty()) Text("Aún no has elegido tiendas.", style = Type.secondary, modifier = Modifier.padding(top = 8.dp))
                        rows.forEach { r -> StoreOfferRow(r, r === cheapest, now) }
                    }
                }
                item { NutritionSection(lookup, onScan = { scanning = true }, onRetry = { reload++ }) }
                item {
                    val history = b.observationsOf(p.id).sortedByDescending { it.observedAt }.take(10)
                    SurfaceCard(Modifier.fillMaxWidth()) {
                        SectionLabel("Historial")
                        if (history.isEmpty()) Text("Sin observaciones.", style = Type.secondary)
                        val fmt = DateTimeFormatter.ofPattern("dd/MM/yy")
                        history.forEach { o ->
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                                Text(o.observedAt.atZone(ZoneId.systemDefault()).toLocalDate().format(fmt), style = Type.mono.copy(color = Faltis.Ink2, fontSize = 12.sp), modifier = Modifier.weight(1f))
                                Text(o.kind.name.lowercase().replace("regular", "normal").replace("member", "socio"), style = Type.secondary.copy(fontSize = 12.sp), modifier = Modifier.padding(end = 12.dp))
                                Text(euros(o.priceCents), style = Type.price.copy(fontSize = 14.sp))
                            }
                        }
                    }
                }
                item {
                    val mineChains = stores.map { it.chainId }.toSet()
                    val others = b.productsById.values.filter { it.chainId != p.chainId && it.chainId !in mineChains }
                        .mapNotNull { o -> Equivalence.explain(p, o)?.let { o to it } }
                        .sortedBy { it.second.level.ordinal }.take(12)
                    SectionLabel("En otras cadenas")
                    if (others.isEmpty()) Text("No hemos encontrado equivalentes.", style = Type.secondary, modifier = Modifier.padding(top = 6.dp))
                    others.forEach { (o, eq) ->
                        val lvl = eq.level
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            EqSymbol(lvl)
                            Column(Modifier.weight(1f)) {
                                Text(o.name, style = Type.row.copy(fontSize = 15.sp))
                                Text("${Chains.byId(o.chainId).name} · ${formatLabel(o)} · ${lvl.label.lowercase()}${if (lvl == com.marcmayol.faltis.core.EquivalenceLevel.SIMILAR) ", no suma en totales" else ""}", style = Type.secondary.copy(fontSize = 12.sp))
                                // Por que (F-12): "Las dos son marca blanca · Formato parecido…" o lo que lo impide.
                                Text(eq.reasons.joinToString(" · "), style = Type.secondary.copy(fontSize = 12.sp, color = if (lvl == com.marcmayol.faltis.core.EquivalenceLevel.SIMILAR) Faltis.Warn else Faltis.Saving))
                            }
                        }
                    }
                }
            }
            // Abajo, fijo: cuantos hay en la lista activa, como en el catalogo.
            val needs by vm.activeNeeds.collectAsState()
            val lists by vm.lists.collectAsState()
            val listName = lists.firstOrNull { it.id == settings?.activeListId }?.name ?: "tu lista"
            val qty = needs.firstOrNull { it.pinnedProductId == p.id }?.qty ?: 0.0
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (qty > 0) Text("${qtyText(qty)} en «$listName»", style = Type.secondary.copy(fontSize = 13.sp))
                if (qty > 0) CatalogQty(qty, Modifier.fillMaxWidth().heightIn(min = 52.dp)) { vm.setInList(p.id, it) }
                else PrimaryButton("Añadir a «$listName»", { vm.setInList(p.id, 1.0); vm.say("«${p.name.substringBefore(" · ")}» añadido a «$listName»") }, Modifier.fillMaxWidth(), icon = Icons.Filled.Add)
            }
        }
    }
    if (scanning) BarcodeScannerDialog(
        onCode = { code ->
            scanning = false
            vm.run {
                if (vm.repo.setEan(productId, code)) { vm.say("Código $code guardado"); reload++ } else vm.say("Ese código no es válido")
            }
        },
        onDismiss = { scanning = false },
    )
}

/** Lo que cuesta un producto (o su equivalente) en una de tus tiendas. */
private class StoreOffer(
    val store: com.marcmayol.faltis.core.StoreRef,
    val product: ProductRef?,
    /** null: es el mismo producto. */
    val level: com.marcmayol.faltis.core.EquivalenceLevel?,
    val best: Pair<com.marcmayol.faltis.core.PriceObservation, Boolean>?,
) {
    /** €/kg, €/l o €/ud en centimos; a granel el precio ya es por kg o l. */
    val unitCents: Long? = if (product == null || best == null) null
        else if (product.netQty == null && product.netUnit != null) best.first.priceCents
        else Packs.unitPrice(best.first.priceCents.toDouble(), product.netQty, product.netUnit)?.toLong()
}

@Composable
private fun StoreOfferRow(r: StoreOffer, cheapest: Boolean, now: Instant) {
    Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ChainChip(r.store.chainId, 30.dp)
        Column(Modifier.weight(1f)) {
            Text(r.store.name, style = Type.row.copy(fontSize = 15.sp), maxLines = 2, overflow = TextOverflow.Ellipsis)
            val p = r.product
            when {
                p == null -> Text("Sin equivalente con precio", style = Type.secondary.copy(fontSize = 12.sp))
                r.level != null -> Text(
                    "${r.level.symbol} ${p.name}" + (if (r.level == com.marcmayol.faltis.core.EquivalenceLevel.SIMILAR) " · parecido" else ""),
                    style = Type.secondary.copy(fontSize = 12.sp, color = if (r.level == com.marcmayol.faltis.core.EquivalenceLevel.SIMILAR) Faltis.Warn else Faltis.Ink2),
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                )
            }
            if (r.best?.first?.kind == PriceKind.MEMBER) Text("precio de socio", style = Type.secondary.copy(color = Faltis.Warn, fontSize = 12.sp))
            if (cheapest) Text("El más barato", style = Type.secondary.copy(color = Faltis.Saving, fontSize = 12.sp, fontWeight = FontWeight.W700))
        }
        val best = r.best
        if (best != null && r.product != null) {
            val age = PriceBook.ageDays(best.first.observedAt, now)
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(euros(best.first.priceCents), style = Type.price.copy(fontSize = 18.sp))
                r.unitCents?.takeIf { r.product.netQty != null }?.let {
                    Text("${euros(it)}${Packs.unitPriceLabel(r.product.netUnit).removePrefix("€")}", style = Type.secondary.copy(fontSize = 12.sp))
                }
                Seal(
                    "${sourceLabel(best.first.source, best.second)} · ${ageText(age)}",
                    if (age > PriceBook.FRESH_DAYS) SealStyle.STALE else if (best.first.source == PriceSource.SAMPLE) SealStyle.NEUTRAL else SealStyle.FRESH,
                )
            }
        } else if (r.product != null || r.level == null) Text("sin precio", style = Type.secondary)
    }
}

@Suppress("unused") private val f = Freshness.FRESH
