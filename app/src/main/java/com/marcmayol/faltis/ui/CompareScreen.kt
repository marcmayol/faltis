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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marcmayol.faltis.core.BasketSplit
import com.marcmayol.faltis.core.Comparison
import com.marcmayol.faltis.core.ItemOffers
import com.marcmayol.faltis.core.NeedPriceResult
import com.marcmayol.faltis.core.NeedRef
import com.marcmayol.faltis.core.SplitPlan
import com.marcmayol.faltis.core.StoreBasket
import com.marcmayol.faltis.core.StoreRef
import com.marcmayol.faltis.ui.theme.Faltis
import com.marcmayol.faltis.ui.theme.Type
import kotlin.math.roundToInt

/** Las tiendas virtuales de "Todos los súper" tienen id negativo: son la web de la cadena. */
private val StoreRef.isWebOnly get() = id < 0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(vm: AppViewModel, onBack: () -> Unit, onTicket: () -> Unit, onStores: () -> Unit, contentPadding: PaddingValues) {
    val ui by vm.listUi.collectAsState()
    val settings by vm.settings.collectAsState()
    val busy by vm.webBusy.collectAsState()
    var detail by remember { mutableStateOf<StoreBasket?>(null) }
    var everywhere by rememberSaveable { mutableStateOf(false) }
    var byItem by rememberSaveable { mutableStateOf(false) }
    val webOn = settings?.webPrices == true
    val count = ui.needs.size

    // "Todos los súper": se calcula solo mientras se ve y se traen los precios web que falten.
    DisposableEffect(everywhere) {
        vm.everywhere.value = everywhere
        onDispose { vm.everywhere.value = false }
    }
    LaunchedEffect(everywhere, webOn) { if (everywhere && webOn) vm.fetchEverywhere() }
    val all by vm.compareEverywhere.collectAsState()
    val comparison = if (everywhere) all else ui.comparison

    HaloBackground(color = Faltis.Saving, second = Faltis.Blue) {
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            TopBar("Comparar cesta", "${ui.list?.name ?: "Lista"} · ${plural(count, "artículo", "artículos")}", onBack)
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SelectPill("Tus tiendas", !everywhere) { everywhere = false }
                            SelectPill("Todos los súper", everywhere) { everywhere = true }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SelectPill("Por tienda", !byItem) { byItem = false }
                            SelectPill("Por artículo", byItem) { byItem = true }
                        }
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Incluir promos y precios de socio", style = Type.body.copy(color = Faltis.Ink2), modifier = Modifier.weight(1f))
                            Switch(settings?.includePromos == true, vm::setPromos, colors = SwitchDefaults.colors(checkedTrackColor = Faltis.Action))
                        }
                    }
                }
                if (everywhere && !webOn) {
                    item {
                        EmptyState(
                            "Activa los precios web",
                            "Para comparar en todos los supermercados, Faltis lee los precios de sus webs. Actívalo en Ajustes con tu código postal.",
                        ) { PrimaryButton("Ir a Ajustes", onStores) }
                    }
                    return@LazyColumn
                }
                if (busy) item {
                    Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = Faltis.Ink2)
                        Text(if (everywhere) "Buscando tu lista en las webs de los supermercados…" else "Buscando precios en la web…", style = Type.secondary.copy(fontSize = 13.sp))
                    }
                }
                val baskets = when (val c = comparison) {
                    is Comparison.SingleStore -> listOf(c.basket)
                    is Comparison.NotEnoughData -> c.baskets
                    is Comparison.Ranking -> c.ranked.map { it.basket } + c.missingData
                    else -> emptyList()
                }
                if (everywhere || baskets.any { it.usesWebPrices() }) item { WebPriceNotice() }
                when (val c = comparison) {
                    null -> item { Text("Calculando…", style = Type.secondary) }
                    Comparison.NoStores -> item { EmptyState("Añade tus tiendas", "Para comparar necesitas al menos dos tiendas habituales.") { PrimaryButton("Añadir tiendas", onStores) } }
                    Comparison.EmptyList -> item { EmptyState("Añade artículos para comparar", "La comparación usa los artículos abiertos de tu lista.") }
                    is Comparison.SingleStore -> {
                        item { EmptyState("Solo tienes una tienda", "${c.basket.store.name}: ${euros(c.basket.totalCents)} con ${c.basket.pricedCount} de ${c.basket.comparableCount} con precio. Añade otra o compara en todos los súper.") { PrimaryButton("Comparar en todos los súper", { everywhere = true }) } }
                        untyped(c.untyped)
                    }
                    is Comparison.NotEnoughData -> {
                        item {
                            EmptyState(
                                "Aún no hay datos suficientes",
                                if (busy) "Esperando a los precios web…" else "Ninguna pareja de tiendas tiene precio de al menos el 80 % de la lista. Esto es lo que falta:",
                            )
                        }
                        if (byItem) itemRows(BasketSplit.itemOffers(c.baskets), vm)
                        else items(c.baskets, key = { it.store.id }) { b -> MissingCard(b, onTicket) { detail = b } }
                        untyped(c.untyped)
                    }
                    is Comparison.Ranking -> {
                        if (byItem) {
                            item { SectionLabel("Dónde sale más barato cada artículo", Modifier.padding(start = 4.dp, top = 6.dp)) }
                            itemRows(BasketSplit.itemOffers(c.ranked.map { it.basket }), vm)
                        } else {
                            item { BestCard(c, ui.store?.id) { detail = c.best.basket } }
                            BasketSplit.best(c)?.let { plan -> item { SplitCard(plan, c.best) } }
                            item { SectionLabel(if (everywhere) "Otros supermercados · ${c.commonNeedIds.size} artículos comunes" else "Ranking · sobre ${c.commonNeedIds.size} artículos comunes", Modifier.padding(start = 4.dp, top = 6.dp)) }
                            items(c.ranked.drop(1), key = { it.basket.store.id }) { r -> RankRow(r) { detail = r.basket } }
                            if (c.missingData.isNotEmpty()) {
                                item { SectionLabel("Faltan datos", Modifier.padding(start = 4.dp, top = 6.dp)) }
                                items(c.missingData, key = { "m" + it.store.id }) { b -> MissingCard(b, onTicket) { detail = b } }
                            }
                        }
                        untyped(c.untyped)
                    }
                }
            }
        }
    }

    detail?.let { b ->
        ModalBottomSheet(onDismissRequest = { detail = null }, containerColor = Faltis.Surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ChainChip(b.store.chainId, 36.dp)
                    Column(Modifier.weight(1f)) {
                        Text(b.store.name, style = Type.title)
                        Text("${b.pricedCount} de ${b.comparableCount} con precio" + if (b.store.isWebOnly) " · precio de su web" else "", style = Type.secondary)
                    }
                    Text(euros(b.totalCents), style = Type.bigNumber.copy(fontSize = 24.sp))
                }
                b.results.forEach { r ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val priced = (r as? NeedPriceResult.Priced)?.value
                        val need = when (r) { is NeedPriceResult.Priced -> r.value.need; is NeedPriceResult.Missing -> r.need }
                        ProductImage(priced?.product?.imageUrl, vm.repo.taxonomy.byId(need.typeId)?.categoryId, 40.dp)
                        Column(Modifier.weight(1f)) {
                            Text(when (r) { is NeedPriceResult.Priced -> r.value.need.text; is NeedPriceResult.Missing -> r.need.text }, style = Type.row.copy(fontSize = 15.sp))
                            Text(
                                priced?.let { "${it.product.name}${if (it.packs > 1) " ×${it.packs}" else ""}${if (it.estimated) " · estimación" else ""}" } ?: "—",
                                style = Type.secondary.copy(fontSize = 12.sp), maxLines = 2,
                            )
                        }
                        PriceColumn(r)
                    }
                }
            }
        }
    }
}

private fun LazyListScope.untyped(list: List<NeedRef>) {
    if (list.isEmpty()) return
    item {
        SurfaceCard(Modifier.fillMaxWidth(), border = Faltis.Warn.copy(alpha = 0.5f)) {
            SectionLabel("Sin tipo (${list.size})", color = Faltis.Warn)
            Text("Asígnales un tipo desde la lista para poder compararlos: ${list.joinToString { it.text }}", style = Type.secondary, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** % que ahorra [cheap] frente a [dear] (positivo = mas barato). */
private fun savingPct(cheap: Long, dear: Long): Int = if (dear <= 0) 0 else ((dear - cheap) * 100.0 / dear).roundToInt()

@Composable
private fun SavingPill(pct: Int) {
    Text(
        "↓ −$pct %", style = Type.row.copy(fontSize = 14.sp, color = Faltis.Saving, fontWeight = FontWeight.W700),
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Faltis.Saving.copy(alpha = 0.12f)).padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/**
 * La mejor opcion. Si tu tienda (la de la lista) esta en el ranking y no es la mejor, tres cifras:
 * lo que cuesta en la tuya, en la mejor y lo que ahorras, sobre los articulos comunes.
 */
@Composable
private fun BestCard(c: Comparison.Ranking, myStoreId: Long?, onClick: () -> Unit) {
    val best = c.best
    val mine = c.ranked.firstOrNull { it.basket.store.id == myStoreId }?.takeIf { it !== best }
    val second = c.ranked.getOrNull(1)
    val versus = mine ?: second
    GradientCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(if (second?.tiedWithBest == true) "Empate" else "Mejor opción", color = Faltis.Saving, modifier = Modifier.weight(1f))
            Text("${best.basket.pricedCount}/${best.basket.comparableCount}" + (best.basket.oldestDays?.let { " · el más antiguo $it d" } ?: ""), style = Type.secondary.copy(fontSize = 12.sp))
        }
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChainChip(best.basket.store.chainId, 44.dp)
            Column(Modifier.weight(1f)) {
                Text(best.basket.store.name, style = Type.title)
                if (best.basket.store.isWebOnly) Text("precio de su web", style = Type.secondary.copy(fontSize = 12.sp))
            }
            versus?.takeIf { it.deltaCents > 0 }?.let { SavingPill(savingPct(best.commonCents, it.commonCents)) }
        }
        if (versus != null && versus.deltaCents > 0) {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Figure("En ${versus.basket.store.name}", euros(versus.commonCents), Modifier.weight(1f))
                Figure("En ${best.basket.store.name}", euros(best.commonCents), Modifier.weight(1f))
                Figure("Ahorras", euros(versus.deltaCents), Modifier.weight(1f), highlight = true)
            }
            Text(
                "Sobre los ${c.commonNeedIds.size} artículos que tienen precio en todas.",
                style = Type.secondary.copy(fontSize = 12.sp), modifier = Modifier.padding(top = 10.dp),
            )
        } else {
            Text(euros(best.commonCents), style = Type.bigNumber, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun Figure(label: String, value: String, modifier: Modifier, highlight: Boolean = false) {
    Column(modifier) {
        Text(label, style = Type.secondary.copy(fontSize = 12.sp), maxLines = 2)
        Text(value, style = Type.price.copy(fontSize = 18.sp, color = if (highlight) Faltis.Saving else Faltis.Ink))
    }
}

/** Repartir la compra entre dos tiendas: que llevar de cada una. */
@Composable
private fun SplitCard(plan: SplitPlan, best: com.marcmayol.faltis.core.RankedStore) {
    var open by rememberSaveable { mutableStateOf(false) }
    SurfaceCard(Modifier.fillMaxWidth().clickable { open = !open }, border = Faltis.Saving.copy(alpha = 0.35f)) {
        SectionLabel("Si vas a dos tiendas", color = Faltis.Saving)
        Text(
            "Comprando cada cosa donde es más barata, entre ${plan.first.name} y ${plan.second.name}, ahorras ${euros(plan.savingCents)} más que yendo solo a ${best.basket.store.name}.",
            style = Type.body.copy(fontSize = 15.sp), modifier = Modifier.padding(top = 6.dp),
        )
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Figure("${plan.inFirst.size} en ${plan.first.name}", euros(plan.inFirst.sumOf { it.costCents }), Modifier.weight(1f))
            Figure("${plan.inSecond.size} en ${plan.second.name}", euros(plan.inSecond.sumOf { it.costCents }), Modifier.weight(1f))
            Figure("Total", euros(plan.totalCents), Modifier.weight(1f), highlight = true)
        }
        if (open) {
            listOf(plan.first to plan.inFirst, plan.second to plan.inSecond).forEach { (store, items) ->
                Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChainChip(store.chainId, 26.dp)
                    Text(store.name, style = Type.row.copy(fontSize = 15.sp))
                }
                items.forEach { p ->
                    Row(Modifier.fillMaxWidth().padding(start = 34.dp, top = 6.dp)) {
                        Text(p.need.text, style = Type.body.copy(fontSize = 14.sp), modifier = Modifier.weight(1f))
                        Text(euros(p.costCents), style = Type.price.copy(fontSize = 14.sp))
                    }
                }
            }
        }
        Text(if (open) "Ocultar" else "Ver qué llevar de cada una", style = Type.row.copy(fontSize = 14.sp, color = Faltis.ActionText), modifier = Modifier.padding(top = 10.dp))
    }
}

@Composable
private fun RankRow(r: com.marcmayol.faltis.core.RankedStore, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Faltis.Surface)
            .border(1.dp, Faltis.Elevated, RoundedCornerShape(18.dp)).clickable(onClick = onClick).heightIn(min = 64.dp).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChainChip(r.basket.store.chainId, 38.dp)
        Column(Modifier.weight(1f)) {
            Text(r.basket.store.name, style = Type.row, maxLines = 2)
            Text(
                "${r.basket.pricedCount} de ${r.basket.comparableCount} con precio" + if (r.basket.store.isWebOnly) " · web" else "",
                style = Type.secondary.copy(fontSize = 12.sp),
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(euros(r.commonCents), style = Type.price)
            Text(if (r.tiedWithBest) "empate" else signedEuros(r.deltaCents), style = Type.price.copy(fontSize = 13.sp, color = if (r.tiedWithBest) Faltis.Saving else Faltis.Warn))
        }
    }
}

/** Una fila por articulo: donde es mas barato, cuanto mas cuesta en la siguiente y el resto. */
private fun LazyListScope.itemRows(items: List<ItemOffers>, vm: AppViewModel) {
    items(items, key = { "i" + it.need.id }) { io ->
        var open by remember { mutableStateOf(false) }
        val best = io.best
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Faltis.Surface)
                .border(1.dp, Faltis.Elevated, RoundedCornerShape(18.dp)).clickable(enabled = io.offers.size > 1) { open = !open }.padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ProductImage(best?.second?.product?.imageUrl, vm.repo.taxonomy.byId(io.need.typeId)?.categoryId, 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(io.need.text, style = Type.row, maxLines = 2)
                    Text(
                        best?.let { "Más barato en ${it.first.name}" } ?: "Sin precio en ninguna",
                        style = Type.secondary.copy(fontSize = 13.sp, color = if (best != null) Faltis.Saving else Faltis.Ink3),
                    )
                    io.gapCents?.takeIf { it > 0 }?.let { gap ->
                        Text("+${euros(gap)} en ${io.offers[1].first.name}", style = Type.secondary.copy(fontSize = 12.sp))
                    }
                }
                best?.let { Text(euros(it.second.costCents), style = Type.price) }
            }
            if (open) io.offers.drop(1).forEach { (store, p) ->
                Row(Modifier.fillMaxWidth().padding(start = 56.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChainChip(store.chainId, 24.dp)
                    Text(store.name, style = Type.body.copy(fontSize = 14.sp), modifier = Modifier.weight(1f), maxLines = 1)
                    Text(euros(p.costCents), style = Type.price.copy(fontSize = 14.sp, color = Faltis.Ink2))
                }
            }
            if (io.offers.size > 1) Text(
                if (open) "Ocultar" else "Ver en ${plural(io.offers.size - 1, "tienda más", "tiendas más")}",
                style = Type.row.copy(fontSize = 13.sp, color = Faltis.ActionText), modifier = Modifier.padding(start = 56.dp, top = 8.dp),
            )
        }
    }
}

@Composable
private fun MissingCard(b: StoreBasket, onTicket: () -> Unit, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).border(1.dp, Faltis.LineStrong, RoundedCornerShape(18.dp)).clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChainChip(b.store.chainId, 34.dp)
        Column(Modifier.weight(1f)) {
            Text(b.store.name, style = Type.row)
            Text("${b.pricedCount} de ${b.comparableCount} con precio", style = Type.secondary.copy(fontSize = 12.sp))
        }
        // Una cadena sin tienda tuya no tiene ticket que escanear.
        if (!b.store.isWebOnly) Text(
            "Escanea un ticket", style = Type.row.copy(fontSize = 13.sp, color = Faltis.ActionText),
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Faltis.ActionSoft).clickable(onClick = onTicket).padding(horizontal = 12.dp, vertical = 12.dp),
        )
    }
}

