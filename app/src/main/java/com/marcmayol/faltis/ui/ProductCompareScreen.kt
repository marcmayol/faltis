package com.marcmayol.faltis.ui

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marcmayol.faltis.core.Chains
import com.marcmayol.faltis.core.Equivalence
import com.marcmayol.faltis.core.EquivalenceLevel
import com.marcmayol.faltis.core.Packs
import com.marcmayol.faltis.core.PriceBook
import com.marcmayol.faltis.core.ProductRef
import com.marcmayol.faltis.core.StoreRef
import com.marcmayol.faltis.ui.theme.Faltis
import com.marcmayol.faltis.ui.theme.Type
import java.time.Instant
import kotlin.math.roundToInt

/** Un producto con su mejor precio (el de tu tienda de esa cadena o, si no, el de la web de la cadena). */
private class Offer(val product: ProductRef, val cents: Long, val unitCents: Long?, val level: EquivalenceLevel?)

private fun offerOf(book: PriceBook, p: ProductRef, mine: List<StoreRef>, promos: Boolean, level: EquivalenceLevel?): Offer? {
    val now = Instant.now()
    val stores = mine.filter { it.chainId == p.chainId }.ifEmpty { listOf(StoreRef(-1, p.chainId, Chains.byId(p.chainId).name, 0)) }
    val cents = stores.mapNotNull { book.bestObservation(p.id, it, now, promos)?.first?.priceCents }.minOrNull() ?: return null
    // A granel el precio ya es por kg o l.
    val unit = if (p.netQty == null && p.netUnit != null) cents else Packs.unitPrice(cents.toDouble(), p.netQty, p.netUnit)?.toLong()
    return Offer(p, cents, unit, level)
}

/** «En otros supermercados»: el producto de referencia y sus parecidos en todas las cadenas, por €/kg. */
@Composable
fun ProductCompareScreen(vm: AppViewModel, productId: Long, onBack: () -> Unit, onProduct: (Long) -> Unit, onSettings: () -> Unit, contentPadding: PaddingValues) {
    val book by vm.priceBook.collectAsState()
    val stores by vm.stores.collectAsState()
    val settings by vm.settings.collectAsState()
    val busy by vm.webBusy.collectAsState()
    var webOff by remember { mutableStateOf(false) }
    val b = book
    val p = b?.productsById?.get(productId)
    LaunchedEffect(productId, p != null) {
        if (p != null) webOff = vm.searchEverywhere(p) < 0
    }
    val mine = with(vm.repo) { stores.map { it.toRef() } }
    val promos = settings?.includePromos == true
    val ref = remember(b, p, stores, promos) { if (b == null || p == null) null else offerOf(b, p, mine, promos, EquivalenceLevel.IDENTICAL) }
    val others = remember(b, p, stores, promos) {
        if (b == null || p == null) emptyList()
        else b.productsById.values.asSequence().filter { it.id != p.id }
            .mapNotNull { o -> Equivalence.explain(p, o)?.let { eq -> offerOf(b, o, mine, promos, eq.level) } }
            .sortedWith(compareBy<Offer> { it.unitCents ?: Long.MAX_VALUE }.thenBy { it.cents })
            .toList()
    }

    HaloBackground(color = Faltis.Saving, second = Faltis.Blue) {
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            TopBar("En otros supermercados", p?.name, onBack)
            if (b == null || p == null) { EmptyState("Producto no encontrado", ""); return@Column }
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item { SectionLabel("Comparando con", Modifier.padding(start = 4.dp)) }
                item {
                    SurfaceCard(Modifier.fillMaxWidth(), border = Faltis.LineStrong) {
                        OfferRow(ref ?: Offer(p, -1, null, EquivalenceLevel.IDENTICAL), vm, null, reference = true)
                    }
                }
                if (webOff) item {
                    EmptyState("Activa los precios web", "Para buscarlo en todos los supermercados, Faltis lee los precios de sus webs. Actívalo en Ajustes con tu código postal.") {
                        PrimaryButton("Ir a Ajustes", onSettings)
                    }
                }
                if (busy) item {
                    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Faltis.Ink2)
                        Text("Buscándolo en las webs de los supermercados…", style = Type.secondary.copy(fontSize = 13.sp))
                    }
                }
                item {
                    val chains = others.map { it.product.chainId }.distinct().size
                    SectionLabel(
                        if (others.isEmpty()) (if (busy) "Buscando parecidos…" else "Sin parecidos con precio")
                        else "${plural(others.size, "parecido", "parecidos")} en ${plural(chains, "supermercado", "supermercados")} · por precio por kilo o litro",
                        Modifier.padding(start = 4.dp, top = 8.dp),
                    )
                }
                if (others.isNotEmpty()) item { WebPriceNotice() }
                items(others, key = { it.product.id }) { o ->
                    Box(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Faltis.Surface)
                            .border(1.dp, Faltis.Elevated, RoundedCornerShape(18.dp)).clickable { onProduct(o.product.id) }.padding(14.dp),
                    ) { OfferRow(o, vm, ref?.unitCents, reference = false) }
                }
            }
        }
    }
}

@Composable
private fun OfferRow(o: Offer, vm: AppViewModel, refUnit: Long?, reference: Boolean) {
    val p = o.product
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        // Foto con el monograma de la cadena en la esquina.
        Box {
            ProductImage(p.imageUrl, vm.repo.taxonomy.byId(p.typeId)?.categoryId, 64.dp)
            Box(Modifier.align(Alignment.BottomEnd).offset(6.dp, 6.dp)) { ChainChip(p.chainId, 26.dp) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(p.name, style = Type.row.copy(fontSize = 15.sp), maxLines = 3)
            Text(listOfNotNull(Chains.byId(p.chainId).name, formatLabel(p).ifBlank { null }).joinToString(" · "), style = Type.secondary.copy(fontSize = 12.sp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (o.cents >= 0) euros(o.cents) else "sin precio", style = Type.price.copy(fontSize = 17.sp))
                o.unitCents?.takeIf { p.netUnit != null }?.let {
                    Text("${euros(it)}${Packs.unitPriceLabel(p.netUnit).removePrefix("€")}", style = Type.secondary.copy(fontSize = 12.sp))
                }
            }
            if (o.level == EquivalenceLevel.SIMILAR) Text("parecido, no idéntico", style = Type.secondary.copy(fontSize = 12.sp, color = Faltis.Warn))
        }
        when {
            reference -> Chip("Referencia")
            refUnit != null && o.unitCents != null && refUnit > 0 -> {
                val pct = ((o.unitCents - refUnit) * 100.0 / refUnit).roundToInt()
                val cheaper = pct < 0
                Text(
                    if (pct == 0) "igual" else if (cheaper) "↓ −${-pct} %" else "↑ +$pct %",
                    style = Type.row.copy(fontSize = 14.sp, fontWeight = FontWeight.W700, color = if (cheaper) Faltis.Saving else if (pct == 0) Faltis.Ink2 else Faltis.Warn),
                    modifier = Modifier.clip(RoundedCornerShape(10.dp))
                        .background((if (cheaper) Faltis.Saving else Faltis.Warn).copy(alpha = if (pct == 0) 0f else 0.12f)).heightIn(min = 30.dp).padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }
    }
}
