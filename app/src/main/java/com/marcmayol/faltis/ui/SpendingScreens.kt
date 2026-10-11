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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marcmayol.faltis.core.Chains
import com.marcmayol.faltis.core.PriceSource
import com.marcmayol.faltis.core.PriceTrends
import com.marcmayol.faltis.ui.theme.Faltis
import com.marcmayol.faltis.ui.theme.Type
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val es = Locale.forLanguageTag("es-ES")

data class PriceRise(val productName: String, val storeName: String, val pct: Double, val cents: Long, val detail: String)

// ─── P-11 · Gastos ────────────────────────────────────────────────────────────
@Composable
fun SpendingScreen(vm: AppViewModel, onTicket: () -> Unit, onPurchase: (Long) -> Unit, contentPadding: PaddingValues) {
    val purchases by vm.purchases.collectAsState()
    val settings by vm.settings.collectAsState()
    val book by vm.priceBook.collectAsState()
    val stores by vm.repo.dao.allStores().collectAsState(initial = emptyList())
    var month by remember { mutableStateOf(YearMonth.now()) }

    val inMonth = purchases.filter { YearMonth.from(LocalDate.ofEpochDay(it.dateEpochDay)) == month }
    val withAmount = inMonth.filter { it.totalLinesCents != null }
    val total = withAmount.sumOf { it.totalReadCents ?: it.totalLinesCents ?: 0 }
    val prevTotal = purchases.filter { YearMonth.from(LocalDate.ofEpochDay(it.dateEpochDay)) == month.minusMonths(1) && it.totalLinesCents != null }
        .sumOf { it.totalReadCents ?: it.totalLinesCents ?: 0 }
    val byChain = withAmount.groupBy { it.chainId }.mapValues { (_, l) -> l.sumOf { it.totalReadCents ?: it.totalLinesCents ?: 0 } }.toList().sortedByDescending { it.second }
    val budget = settings?.budgetCents ?: 0

    // F-10.5 · "Ha subido": el ultimo precio supera en un 15 % la media de tus compras anteriores
    // (25 % en fruta y verdura), con al menos 3 compras. Sin datos de ejemplo.
    val tax = vm.repo.taxonomy
    val rises = remember(book, stores) {
        val b = book ?: return@remember emptyList<PriceRise>()
        PriceTrends.alerts(b, tax).take(5).map { c ->
            PriceRise(c.product.name, stores.firstOrNull { it.id == c.storeId }?.name ?: "", c.pct, c.nowCents,
                "media de ${c.samples - 1} compras: ${euros(c.beforeCents)}")
        }
    }
    // Lo que mas ha cambiado desde la primera compra (subidas y bajadas).
    val changes = remember(book, stores) {
        val b = book ?: return@remember emptyList<PriceRise>()
        PriceTrends.biggestChanges(b).take(6).map { c ->
            PriceRise(c.product.name, stores.firstOrNull { it.id == c.storeId }?.name ?: "", c.pct, c.nowCents, "antes ${euros(c.beforeCents)}")
        }
    }
    // Inflacion personal: lo que cuesta este mes lo que ya comprabas antes.
    val inflation = remember(book, month) { book?.let { PriceTrends.personalInflation(it, month) } }

    HaloBackground(color = Faltis.Brand, second = Faltis.Blue) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                SquareIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Mes anterior", { month = month.minusMonths(1) })
                Text(
                    month.month.getDisplayName(TextStyle.FULL_STANDALONE, es).replaceFirstChar { it.uppercase() } + " " + month.year,
                    style = Type.title, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                )
                SquareIconButton(Icons.AutoMirrored.Filled.KeyboardArrowRight, "Mes siguiente", { if (month < YearMonth.now()) month = month.plusMonths(1) },
                    Modifier.alpha(if (month < YearMonth.now()) 1f else 0.4f))
            }
            LazyColumn(
                Modifier.weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 84.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        SectionLabel("Gastado este mes")
                        Text(euros(total), style = Type.heroNumber.copy(fontSize = 56.sp))
                        if (prevTotal > 0) {
                            val pct = (total - prevTotal) * 100.0 / prevTotal
                            Text(
                                "${if (pct <= 0) "−" else "+"}${"%.0f".format(kotlin.math.abs(pct))} % que el mes anterior",
                                style = Type.row.copy(fontSize = 14.sp, color = if (pct <= 0) Faltis.Saving else Faltis.Warn),
                            )
                        }
                        if (budget > 0) {
                            val frac = total.toFloat() / budget
                            Row(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                                Text("Presupuesto", style = Type.secondary.copy(fontSize = 12.sp), modifier = Modifier.weight(1f))
                                Text("${(frac * 100).toInt()} % de ${euros(budget)}", style = Type.secondary.copy(fontSize = 12.sp, color = if (frac >= 0.8f) Faltis.Warn else Faltis.Ink2))
                            }
                            GradientBar(frac, Modifier.padding(top = 6.dp))
                            if (frac >= 1f) Text("Has superado el presupuesto del mes.", style = Type.secondary.copy(color = Faltis.Warn), modifier = Modifier.padding(top = 6.dp))
                            else if (frac >= 0.8f) Text("Llevas más del 80 % del presupuesto.", style = Type.secondary.copy(color = Faltis.Warn), modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
                if (inMonth.isEmpty()) {
                    item { EmptyState("Escanea tu primer ticket", "Con cada ticket sabrás cuánto gastas, dónde y qué ha subido.") }
                } else {
                    if (byChain.isNotEmpty()) item {
                        SurfaceCard(Modifier.fillMaxWidth()) {
                            SectionLabel("Por cadena")
                            val max = byChain.maxOf { it.second }.coerceAtLeast(1)
                            byChain.forEach { (chain, cents) ->
                                Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(Chains.byId(chain).name, style = Type.row.copy(fontSize = 13.sp), modifier = Modifier.width(96.dp), maxLines = 1)
                                    GradientBar(cents.toFloat() / max, Modifier.weight(1f), 10.dp)
                                    Text(euros(cents), style = Type.price.copy(fontSize = 13.sp))
                                }
                            }
                        }
                    }
                }
                inflation?.let { inf ->
                    item {
                        SurfaceCard(Modifier.fillMaxWidth()) {
                            SectionLabel("Tu inflación")
                            Row(verticalAlignment = Alignment.Bottom) {
                                Text(
                                    (if (inf.pct > 0.0005) "+" else if (inf.pct < -0.0005) "−" else "") + "%.1f".format(es, kotlin.math.abs(inf.pct * 100)) + " %",
                                    style = Type.bigNumber.copy(fontSize = 28.sp, color = if (inf.pct > 0.0005) Faltis.Warn else Faltis.Saving),
                                )
                                Text("  este mes", style = Type.secondary, modifier = Modifier.padding(bottom = 4.dp))
                            }
                            Text(
                                "Lo que cuestan ahora ${inf.products} productos que ya comprabas, frente a su precio anterior. Solo con tus tickets.",
                                style = Type.secondary.copy(fontSize = 13.sp), modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
                if (rises.isNotEmpty()) item { ChangeCard("Ha subido", Faltis.Warn, rises) }
                if (changes.isNotEmpty()) item { ChangeCard("Lo que más ha cambiado", Faltis.Ink2, changes) }
                if (inMonth.isNotEmpty()) {
                    item { SectionLabel("Compras del mes") }
                    items(inMonth, key = { it.id }) { p ->
                        val store = stores.firstOrNull { it.id == p.storeId }
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Faltis.Surface).border(1.dp, Faltis.Elevated, RoundedCornerShape(16.dp))
                                .clickable { onPurchase(p.id) }.heightIn(min = 60.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            ChainChip(p.chainId, 34.dp)
                            Column(Modifier.weight(1f)) {
                                Text(store?.name ?: Chains.byId(p.chainId).name, style = Type.row.copy(fontSize = 15.sp))
                                Text(LocalDate.ofEpochDay(p.dateEpochDay).format(DateTimeFormatter.ofPattern("EEEE d", es)), style = Type.secondary.copy(fontSize = 12.sp))
                            }
                            Text(p.totalLinesCents?.let { euros(p.totalReadCents ?: it) } ?: "Sin importe", style = if (p.totalLinesCents != null) Type.price else Type.secondary.copy(color = Faltis.Warn))
                        }
                    }
                }
            }
        }
        Box(Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, bottom = contentPadding.calculateBottomPadding() + 10.dp), contentAlignment = Alignment.BottomCenter) {
            PrimaryButton("Escanear ticket", onTicket, Modifier.fillMaxWidth(), icon = Icons.Filled.ReceiptLong)
        }
    }
}

@Composable
private fun ChangeCard(title: String, color: androidx.compose.ui.graphics.Color, rows: List<PriceRise>) {
    SurfaceCard(Modifier.fillMaxWidth()) {
        SectionLabel(title, color = color)
        rows.forEach { r ->
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.productName, style = Type.body.copy(fontSize = 14.sp))
                    Text("${r.storeName} · ${r.detail}", style = Type.secondary.copy(fontSize = 12.sp))
                }
                Text(
                    (if (r.pct > 0) "+" else "−") + "%.0f".format(kotlin.math.abs(r.pct * 100)) + " %",
                    style = Type.price.copy(fontSize = 14.sp, color = if (r.pct > 0) Faltis.Warn else Faltis.Saving), modifier = Modifier.padding(end = 10.dp),
                )
                Text(euros(r.cents), style = Type.price.copy(fontSize = 14.sp))
            }
        }
    }
}

// ─── P-12 · Detalle de compra ─────────────────────────────────────────────────
@Composable
fun PurchaseDetailScreen(vm: AppViewModel, purchaseId: Long, onBack: () -> Unit) {
    val p by remember(purchaseId) { vm.repo.dao.purchaseFlow(purchaseId) }.collectAsState(initial = null)
    val lines by remember(purchaseId) { vm.repo.dao.lines(purchaseId) }.collectAsState(initial = emptyList())
    val stores by vm.repo.dao.allStores().collectAsState(initial = emptyList())
    val purchase = p
    HaloBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            if (purchase == null) { TopBar("Compra", null, onBack); return@Column }
            val store = stores.firstOrNull { it.id == purchase.storeId }
            TopBar(store?.name ?: Chains.byId(purchase.chainId).name, LocalDate.ofEpochDay(purchase.dateEpochDay).format(DateTimeFormatter.ofPattern("d 'de' MMMM yyyy", es)), onBack)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    GradientCard {
                        SectionLabel("Total")
                        Text(purchase.totalLinesCents?.let { euros(purchase.totalReadCents ?: it) } ?: "Sin importe", style = Type.heroNumber.copy(fontSize = 44.sp))
                        Text("${lines.size} líneas · ${if (purchase.source == "NONE") "sin ticket" else if (purchase.source == PriceSource.TICKET_PDF.name) "ticket PDF" else "foto de ticket"}", style = Type.secondary)
                    }
                }
                items(lines, key = { it.id }) { l ->
                    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Faltis.Surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(l.description.lowercase().replaceFirstChar { it.uppercase() }, style = Type.body.copy(fontSize = 14.sp))
                            Text(vm.repo.taxonomy.byId(l.typeId)?.name ?: "sin tipo", style = Type.secondary.copy(fontSize = 12.sp))
                        }
                        Text(l.amountCents?.let { euros(it - l.discountCents) } ?: "—", style = Type.price.copy(fontSize = 14.sp))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Revertir cuadre", { vm.revert(purchaseId) }, Modifier.weight(1f))
                SecondaryButton("Borrar compra", { vm.deletePurchase(purchaseId, onBack) }, Modifier.weight(1f))
            }
        }
    }
}

@Suppress("unused") private fun epoch(i: Instant) = i.atZone(ZoneId.systemDefault())
