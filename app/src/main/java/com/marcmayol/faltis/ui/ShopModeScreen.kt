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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
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
    val pending = ui.needs.filter { it.state == NeedState.PENDING.name }
    val inCart = ui.needs.filter { it.state == NeedState.IN_CART.name }.sortedBy { it.cartOrder }
    val ordered = AisleLearner.order(pending, { vm.repo.keyFor(it) }, { tax.byId(it.typeId)?.categoryOrder ?: 999 }, aisle)
    val total = ui.needs.size
    val cartCents = inCart.sumOf { n -> (vm.priceOf(n.id) as? NeedPriceResult.Priced)?.value?.costCents ?: 0L }

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
                }
            }
            GradientBar(if (total == 0) 0f else inCart.size.toFloat() / total, Modifier.padding(top = 10.dp), 8.dp)
            LazyColumn(Modifier.weight(1f).padding(top = 14.dp), contentPadding = PaddingValues(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pending.isEmpty() && inCart.isEmpty()) item { EmptyState("Nada pendiente", "Tu lista está vacía.") }
                if (ordered.isNotEmpty()) item { SectionLabel(if (aisle.isEmpty()) "Pendientes · por categoría" else "Siguiente, en tu orden de pasillos") }
                items(ordered, key = { it.id }) { n -> BigRow(n.text, "${qtyText(n.qty)} ${n.unit}", done = false, highlight = n == ordered.first()) { vm.toggleCart(n) } }
                if (inCart.isNotEmpty()) item { SectionLabel("En el carro · ${inCart.size}", Modifier.padding(top = 8.dp)) }
                items(inCart, key = { "c" + it.id }) { n -> BigRow(n.text, "${qtyText(n.qty)} ${n.unit}", done = true, highlight = false) { vm.toggleCart(n) } }
            }
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
private fun BigRow(name: String, qty: String, done: Boolean, highlight: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().alpha(if (done) 0.5f else 1f).clip(RoundedCornerShape(20.dp)).background(Faltis.Surface)
            .border(if (highlight) 2.dp else 1.dp, if (highlight) Faltis.Action else Faltis.Elevated, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick).heightIn(min = 68.dp).padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(
            Modifier.size(30.dp).clip(RoundedCornerShape(10.dp))
                .background(if (done) Faltis.GradientH else androidx.compose.ui.graphics.SolidColor(Color.Transparent))
                .border(if (done) 0.dp else 2.5.dp, if (highlight) Faltis.ActionText else Faltis.LineStrong, RoundedCornerShape(10.dp)),
            contentAlignment = Alignment.Center,
        ) { if (done) Icon(Icons.Filled.Check, "En el carro", tint = Faltis.ShopGround, modifier = Modifier.size(20.dp)) }
        Text(name, style = Type.row.copy(fontSize = 20.sp, textDecoration = if (done) TextDecoration.LineThrough else null), modifier = Modifier.weight(1f))
        Text(qty, style = Type.price.copy(color = Faltis.Ink2, fontSize = 16.sp))
    }
}
