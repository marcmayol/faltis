package com.marcmayol.faltis.ui

import android.app.Activity
import android.view.WindowManager
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marcmayol.faltis.core.AisleLearner
import com.marcmayol.faltis.core.NeedPriceResult
import com.marcmayol.faltis.core.NeedState
import com.marcmayol.faltis.ui.theme.Faltis
import com.marcmayol.faltis.ui.theme.Type

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShopModeScreen(vm: AppViewModel, onBack: () -> Unit, onTicket: (storeId: Long) -> Unit, contentPadding: PaddingValues) {
    val ui by vm.listUi.collectAsState()
    val stores by vm.stores.collectAsState()
    var storeId by remember { mutableLongStateOf(ui.store?.id ?: stores.firstOrNull()?.id ?: 0L) }
    LaunchedEffect(stores) { if (storeId == 0L) storeId = stores.firstOrNull()?.id ?: 0L }
    val store = stores.firstOrNull { it.id == storeId }
    val aisle by remember(storeId) { vm.repo.aisleEntries(storeId) }.collectAsState(initial = emptyMap())
    var finishing by remember { mutableStateOf(false) }
    var storeMenu by remember { mutableStateOf(false) }

    // Pantalla siempre encendida mientras se compra (F-08.1).
    val activity = LocalContext.current as? Activity
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    LaunchedEffect(storeId) { if (storeId != 0L) vm.selectStore(storeId) }

    val tax = vm.repo.taxonomy
    // En este super, lo que es de aqui o de cualquiera; lo marcado para otro super se queda en la lista.
    val book by vm.priceBook.collectAsState()
    val here = ui.needs.filter { n -> chainOfNeed(n, book).let { it == null || it == store?.chainId } }
    val elsewhere = ui.needs.size - here.size
    val pending = here.filter { it.state == NeedState.PENDING.name }
    val inCart = here.filter { it.state == NeedState.IN_CART.name }.sortedBy { it.cartOrder }
    val ordered = AisleLearner.order(pending, { vm.repo.keyFor(it) }, { tax.byId(it.typeId)?.categoryOrder ?: 999 }, aisle)
    val total = ui.needs.size
    val priced: (com.marcmayol.faltis.data.NeedEntity) -> com.marcmayol.faltis.core.PricedNeed? = { n -> (vm.priceOf(n.id) as? NeedPriceResult.Priced)?.value }
    val cartCents = inCart.sumOf { priced(it)?.costCents ?: 0L }
    val estimateCents = ui.needs.sumOf { priced(it)?.costCents ?: 0L }

    HaloBackground(ground = Faltis.ShopGround, alignTop = false) {
        Column(Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 16.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                BackButton(onBack)
                Box(Modifier.weight(1f).padding(start = 10.dp)) {
                    Row(Modifier.clip(RoundedCornerShape(10.dp)).clickable { storeMenu = true }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(store?.name ?: "Elige tienda", style = Type.row, maxLines = 1)
                        Icon(Icons.Filled.ArrowDropDown, "Cambiar de tienda")
                    }
                    DropdownMenu(storeMenu, { storeMenu = false }) {
                        stores.forEach { s -> DropdownMenuItem(text = { Text(s.name) }, onClick = { storeId = s.id; storeMenu = false }) }
                    }
                }
                Text(
                    "Terminar", style = Type.row.copy(fontSize = 14.sp),
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(Faltis.ActionSoft).border(1.dp, Faltis.Action, RoundedCornerShape(14.dp))
                        .clickable(enabled = inCart.isNotEmpty()) { finishing = true }.padding(horizontal = 16.dp, vertical = 13.dp),
                )
            }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.Bottom) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                    Text("${inCart.size}", style = Type.heroNumber.copy(fontSize = 72.sp, lineHeight = 72.sp))
                    Text("/$total", style = Type.heroNumber.copy(fontSize = 30.sp, color = Faltis.Ink3), modifier = Modifier.padding(bottom = 10.dp))
                }
                Column(horizontalAlignment = Alignment.End) {
                    SectionLabel("En el carro")
                    Text(euros(cartCents), style = Type.bigNumber.copy(fontSize = 26.sp))
                    if (estimateCents > 0) Text("de ${euros(estimateCents)} estimados", style = Type.secondary.copy(fontSize = 12.sp))
                }
            }
            GradientBar(if (total == 0) 0f else inCart.size.toFloat() / total, Modifier.padding(top = 10.dp), 8.dp)
            LazyColumn(Modifier.weight(1f).padding(top = 14.dp), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pending.isEmpty() && inCart.isEmpty()) item { EmptyState("Nada pendiente aquí", if (elsewhere > 0) "Lo que queda en la lista es para otros súper." else "Tu lista está vacía.") }
                if (elsewhere > 0 && (pending.isNotEmpty() || inCart.isNotEmpty())) item {
                    Text("${plural(elsewhere, "artículo es", "artículos son")} de otros súper y no sale aquí.", style = Type.secondary.copy(fontSize = 13.sp))
                }
                if (aisle.isNotEmpty()) {
                    if (ordered.isNotEmpty()) item { SectionLabel("Siguiente, en tu orden de pasillos") }
                    items(ordered, key = { it.id }) { n -> BigRow(n, priced(n), vm, done = false, highlight = n == ordered.first()) { vm.toggleCart(n) } }
                } else {
                    // Sin orden aprendido: por categorias, con su icono, en el orden de la tienda tipica.
                    val groups = ordered.groupBy { tax.byId(it.typeId)?.let { t -> Triple(t.categoryOrder, t.categoryName, t.categoryId) } ?: Triple(999, "Sin categoría", null) }
                    groups.toSortedMap(compareBy { it.first }).forEach { (cat, needs) ->
                        item(key = "h" + cat.second) { CategoryHeader(cat.third, cat.second, Modifier.padding(start = 4.dp, top = 6.dp), extra = "${needs.size}") }
                        items(needs, key = { it.id }) { n -> BigRow(n, priced(n), vm, done = false, highlight = n == ordered.first()) { vm.toggleCart(n) } }
                    }
                }
                if (inCart.isNotEmpty()) item { SectionLabel("En el carro · ${inCart.size}", Modifier.padding(top = 8.dp)) }
                items(inCart, key = { "c" + it.id }) { n -> BigRow(n, priced(n), vm, done = true, highlight = false) { vm.toggleCart(n) } }
            }
            // Lo que te acuerdas en el pasillo, sin salir del modo compra.
            AddBar(onAdd = { vm.addText(it) }, modifier = Modifier.padding(bottom = 8.dp), placeholder = "¿Algo más? Añádelo aquí")
        }
    }

    if (finishing) {
        ModalBottomSheet(onDismissRequest = { finishing = false }, containerColor = Faltis.Surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Terminar compra", style = Type.title)
                Text("${inCart.size} en el carro · ${pending.size} siguen pendientes", style = Type.secondary)
                PrimaryButton("Escanear ticket", {
                    finishing = false
                    vm.learnFromCart(storeId)
                    onTicket(storeId)
                }, Modifier.fillMaxWidth())
                SecondaryButton("Terminar sin ticket", {
                    finishing = false
                    vm.finishWithoutTicket(storeId) { onBack() }
                }, Modifier.fillMaxWidth())
                TextButton({ finishing = false }, Modifier.fillMaxWidth()) { Text("Seguir comprando") }
            }
        }
    }
}

@Composable
private fun BigRow(n: com.marcmayol.faltis.data.NeedEntity, price: com.marcmayol.faltis.core.PricedNeed?, vm: AppViewModel, done: Boolean, highlight: Boolean, onClick: () -> Unit) {
    val name = n.text
    val qty = "${qtyText(n.qty)} ${n.unit}"
    Row(
        Modifier.fillMaxWidth().alpha(if (done) 0.5f else 1f).clip(RoundedCornerShape(20.dp)).background(Faltis.Surface)
            .border(if (highlight) 2.dp else 1.dp, if (highlight) Faltis.Action else Faltis.Elevated, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).heightIn(min = 68.dp).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(10.dp))
                .background(if (done) Faltis.GradientH else androidx.compose.ui.graphics.SolidColor(Color.Transparent))
                .border(if (done) 0.dp else 2.5.dp, if (highlight) Faltis.ActionText else Faltis.LineStrong, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) { if (done) Icon(Icons.Filled.Check, "En el carro", tint = Faltis.ShopGround, modifier = Modifier.size(20.dp)) }
        ProductImage(price?.product?.imageUrl, vm.repo.taxonomy.byId(n.typeId)?.categoryId, 44.dp)
        Column(Modifier.weight(1f)) {
            Text(name, style = Type.row.copy(fontSize = 20.sp, textDecoration = if (done) TextDecoration.LineThrough else null))
            // Que coger: el producto concreto (fijado o el mas barato) para no dudar delante del estante.
            price?.let { Text(it.product.name + if (it.packs > 1) " ×${it.packs}" else "", style = Type.secondary.copy(fontSize = 13.sp), maxLines = 2) }
            // Cuantos: se cambia sin salir del pasillo. Debajo del nombre para que con letra grande no apriete la fila.
            if (!done) QtyStepper(n.qty, n.unit, Modifier.padding(top = 8.dp)) { q -> vm.saveNeed(n.copy(qty = q, updatedAtMillis = System.currentTimeMillis())) }
            else Text(qty, style = Type.secondary.copy(fontSize = 13.sp))
        }
        price?.let { Text(euros(it.costCents), style = Type.price.copy(fontSize = 16.sp)) }
    }
}

/** Paso de los botones segun la unidad: 1 ud, medio kilo o litro, 100 g o ml. */
private fun stepFor(unit: String): Double = when (unit) {
    "kg", "l" -> 0.5
    "g", "ml" -> 100.0
    "cl" -> 25.0
    else -> 1.0
}

/** «– N +» del modo compra. En el minimo, «–» se apaga (para quitarlo, desliza en la lista). */
@Composable
fun QtyStepper(qty: Double, unit: String, modifier: Modifier = Modifier, onChange: (Double) -> Unit) {
    val step = stepFor(unit)
    val canLess = qty - step >= step - 1e-9
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(Faltis.Elevated),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(Icons.Filled.Remove, "Menos", canLess) { onChange(qty - step) }
        // Ancho minimo: que los botones no bailen de una fila a otra.
        Text(
            "${qtyText(qty)} $unit", style = Type.price.copy(fontSize = 16.sp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.widthIn(min = 64.dp).padding(horizontal = 4.dp),
        )
        StepButton(Icons.Filled.Add, "Más", true) { onChange(qty + step) }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, label, tint = if (enabled) Faltis.Saving else Faltis.Ink3.copy(alpha = 0.4f), modifier = Modifier.size(22.dp)) }
}
