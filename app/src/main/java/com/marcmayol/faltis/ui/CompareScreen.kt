package com.marcmayol.faltis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marcmayol.faltis.core.Comparison
import com.marcmayol.faltis.core.NeedPriceResult
import com.marcmayol.faltis.core.NeedRef
import com.marcmayol.faltis.core.StoreBasket
import com.marcmayol.faltis.ui.theme.Faltis
import com.marcmayol.faltis.ui.theme.Type

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompareScreen(vm: AppViewModel, onBack: () -> Unit, onTicket: () -> Unit, onStores: () -> Unit) {
    val ui by vm.listUi.collectAsState()
    val settings by vm.settings.collectAsState()
    var detail by remember { mutableStateOf<StoreBasket?>(null) }
    val count = ui.needs.size

    HaloBackground(color = Faltis.Saving, second = Faltis.Blue) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            TopBar("Comparar cesta", "${ui.list?.name ?: "Lista"} · $count artículos", onBack)
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Incluir promos y precios de socio", style = Type.body.copy(color = Faltis.Ink2), modifier = Modifier.weight(1f))
                        Switch(settings?.includePromos == true, vm::setPromos, colors = SwitchDefaults.colors(checkedTrackColor = Faltis.Action))
                    }
                }
                when (val c = ui.comparison) {
                    null -> item { Text("Calculando…", style = Type.secondary) }
                    Comparison.NoStores -> item { EmptyState("Añade tus tiendas", "Para comparar necesitas al menos dos tiendas habituales.") { PrimaryButton("Añadir tiendas", onStores) } }
                    Comparison.EmptyList -> item { EmptyState("Añade artículos para comparar", "La comparación usa los artículos abiertos de tu lista.") }
                    is Comparison.SingleStore -> {
                        item { EmptyState("Solo tienes una tienda", "${c.basket.store.name}: ${euros(c.basket.totalCents)} con ${c.basket.pricedCount} de ${c.basket.comparableCount} con precio. Añade otra para comparar.") { PrimaryButton("Añadir tiendas", onStores) } }
                        untyped(c.untyped)
                    }
                    is Comparison.NotEnoughData -> {
                        item { EmptyState("Aún no hay datos suficientes", "Ninguna pareja de tiendas tiene precio de al menos el 80 % de la lista. Esto es lo que falta:") }
                        items(c.baskets, key = { it.store.id }) { b -> MissingCard(b, onTicket) { detail = b } }
                        untyped(c.untyped)
                    }
                    is Comparison.Ranking -> {
                        item { BestCard(c) { detail = c.best.basket } }
                        item { SectionLabel("Ranking · sobre ${c.commonNeedIds.size} artículos comunes", Modifier.padding(start = 4.dp, top = 6.dp)) }
                        items(c.ranked.drop(1), key = { it.basket.store.id }) { r ->
                            // La barra mide cuanto mas cara es frente a la mejor (la mas cara del ranking = llena).
                            val maxDelta = c.ranked.maxOf { it.deltaCents }.coerceAtLeast(1)
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Faltis.Surface)
                                    .border(1.dp, Faltis.Elevated, RoundedCornerShape(18.dp)).clickable { detail = r.basket }.padding(14.dp),
                                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                ChainChip(r.basket.store.chainId, 34.dp)
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Row {
                                        Text(r.basket.store.name, style = Type.row, modifier = Modifier.weight(1f))
                                        Text(euros(r.commonCents), style = Type.price)
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        GradientBar(0.15f + 0.85f * r.deltaCents.toFloat() / maxDelta, Modifier.weight(1f), 6.dp)
                                        Text(if (r.tiedWithBest) "empate" else signedEuros(r.deltaCents), style = Type.price.copy(fontSize = 12.sp, color = Faltis.Warn))
                                    }
                                    Text("Total de su lista: ${euros(r.basket.totalCents)} · ${r.basket.pricedCount}/${r.basket.comparableCount}", style = Type.secondary.copy(fontSize = 12.sp))
                                }
                            }
                        }
                        if (c.missingData.isNotEmpty()) {
                            item { SectionLabel("Faltan datos", Modifier.padding(start = 4.dp, top = 6.dp)) }
                            items(c.missingData, key = { "m" + it.store.id }) { b -> MissingCard(b, onTicket) { detail = b } }
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
                        Text("${b.pricedCount} de ${b.comparableCount} con precio", style = Type.secondary)
                    }
                    Text(euros(b.totalCents), style = Type.bigNumber.copy(fontSize = 24.sp))
                }
                b.results.forEach { r ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        val priced = (r as? NeedPriceResult.Priced)?.value
                        EqSymbol(priced?.equivalence, 30.dp)
                        Column(Modifier.weight(1f)) {
                            Text(when (r) { is NeedPriceResult.Priced -> r.value.need.text; is NeedPriceResult.Missing -> r.need.text }, style = Type.row.copy(fontSize = 15.sp))
                            Text(
                                priced?.let { "${it.product.name}${if (it.packs > 1) " ×${it.packs}" else ""}${if (it.estimated) " · estimación" else ""}" } ?: "—",
                                style = Type.secondary.copy(fontSize = 12.sp), maxLines = 1,
                            )
                        }
                        PriceColumn(r)
                    }
                }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.untyped(list: List<NeedRef>) {
    if (list.isEmpty()) return
    item {
        SurfaceCard(Modifier.fillMaxWidth(), border = Faltis.Warn.copy(alpha = 0.5f)) {
            SectionLabel("Sin tipo (${list.size})", color = Faltis.Warn)
            Text("Asígnales un tipo desde la lista para poder compararlos: ${list.joinToString { it.text }}", style = Type.secondary, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

@Composable
private fun BestCard(c: Comparison.Ranking, onClick: () -> Unit) {
    val best = c.best
    val second = c.ranked.getOrNull(1)
    GradientCard(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(if (second?.tiedWithBest == true) "Empate" else "Mejor opción", color = Faltis.Saving, modifier = Modifier.weight(1f))
            Text("${best.basket.pricedCount}/${best.basket.comparableCount}" + (best.basket.oldestDays?.let { " · el más antiguo ${it} d" } ?: ""), style = Type.secondary.copy(fontSize = 12.sp))
        }
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ChainChip(best.basket.store.chainId, 44.dp)
            Text(best.basket.store.name, style = Type.title, modifier = Modifier.weight(1f))
            Text(euros(best.commonCents), style = Type.bigNumber)
        }
        if (second != null) {
            Spacer(Modifier.height(14.dp))
            Text(
                "Ahorras ${euros(second.deltaCents)} frente a ${second.basket.store.name}, comparando solo los ${c.commonNeedIds.size} artículos que tienen precio en las dos.",
                style = Type.body.copy(fontSize = 14.sp),
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Faltis.Saving.copy(alpha = 0.10f)).padding(12.dp),
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
        Text(
            "Escanea un ticket", style = Type.row.copy(fontSize = 13.sp, color = Faltis.ActionText),
            modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Faltis.ActionSoft).clickable(onClick = onTicket).padding(horizontal = 12.dp, vertical = 12.dp),
        )
    }
}
