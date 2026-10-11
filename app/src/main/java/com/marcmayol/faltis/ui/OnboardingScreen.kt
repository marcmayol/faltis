package com.marcmayol.faltis.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marcmayol.faltis.core.Chains
import com.marcmayol.faltis.core.Text as T
import com.marcmayol.faltis.ui.theme.Faltis
import com.marcmayol.faltis.ui.theme.Type

@Composable
fun OnboardingScreen(vm: AppViewModel, contentPadding: PaddingValues) {
    var step by remember { mutableStateOf(0) }
    val chosen = remember { mutableStateListOf<Pair<String, String>>() }
    var listName by remember { mutableStateOf("Compra") }

    HaloBackground(color = Faltis.Brand, second = Faltis.Saving) {
        Column(Modifier.fillMaxSize().padding(contentPadding).padding(horizontal = 20.dp)) {
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                repeat(3) { i ->
                    Box(Modifier.weight(1f).height(5.dp).clip(CircleShape).background(if (i <= step) Faltis.GradientH else androidx.compose.ui.graphics.SolidColor(Faltis.Elevated)))
                }
                Text("${step + 1}/3", style = Type.label.copy(color = Faltis.Ink2, letterSpacing = 0.sp))
            }
            when (step) {
                0 -> Welcome(onStart = { step = 1 }, onSample = { vm.finishOnboarding(emptyList(), "", sample = true) }, modifier = Modifier.weight(1f))
                1 -> StoresStep(chosen, Modifier.weight(1f))
                else -> ListStep(listName, { listName = it }, Modifier.weight(1f))
            }
            if (step > 0) {
                Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    val label = when {
                        step == 1 && chosen.isEmpty() -> "Omitir por ahora"
                        step == 1 -> "Continuar con ${chosen.size} ${if (chosen.size == 1) "tienda" else "tiendas"}"
                        else -> "Listo"
                    }
                    PrimaryButton(label, {
                        if (step == 1) step = 2 else vm.finishOnboarding(chosen.toList(), listName, sample = false)
                    }, Modifier.fillMaxWidth())
                    TextButton({ step -= 1 }, Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Atrás", style = Type.row.copy(color = Faltis.Ink2)) }
                }
            }
        }
    }
}

@Composable
private fun Welcome(onStart: () -> Unit, onSample: () -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.weight(1f))
        Text("Faltis", style = Type.heroNumber.copy(fontSize = 64.sp, brush = Faltis.GradientH))
        Text("La lista de la compra que sabe lo que de verdad pagas.", style = Type.screenTitle.copy(fontSize = 24.sp))
        Spacer(Modifier.height(4.dp))
        Bullet("≈", "Una lista sin súper: «leche, 2 l». Tú decides dónde comprar al final.")
        Bullet("€", "Tus tickets son tus precios: foto o PDF, y la app aprende lo que pagas de verdad.")
        Bullet("=", "Comparación honesta: solo con lo comparable y diciendo de dónde sale cada precio.")
        Spacer(Modifier.weight(1f))
        PrimaryButton("Empezar", onStart, Modifier.fillMaxWidth())
        SecondaryButton("Probar con datos de ejemplo", onSample, Modifier.fillMaxWidth())
        Text("Los datos de ejemplo son ficticios y se borran desde Ajustes.", style = Type.secondary, modifier = Modifier.padding(bottom = 12.dp))
    }
}

@Composable
private fun Bullet(symbol: String, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Faltis.Elevated), contentAlignment = Alignment.Center) {
            Text(symbol, style = Type.title.copy(color = Faltis.Saving))
        }
        Text(text, style = Type.body.copy(color = Faltis.Ink2))
    }
}

@Composable
private fun StoresStep(chosen: MutableList<Pair<String, String>>, modifier: Modifier) {
    var query by remember { mutableStateOf("") }
    var naming by remember { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxWidth()) {
        Text("¿Dónde haces\nla compra?", style = Type.heroNumber.copy(fontSize = 34.sp, lineHeight = 36.sp), modifier = Modifier.padding(top = 26.dp))
        Text("Solo compararemos estas tiendas. La primera es la que verás por defecto.", style = Type.secondary.copy(fontSize = 15.sp), modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(chosen.size) { i ->
                val (chain, name) = chosen[i]
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Faltis.Surface)
                        .border(1.dp, if (i == 0) Faltis.Action else Faltis.Line, RoundedCornerShape(16.dp)).padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Text("${i + 1}", style = Type.row.copy(color = Faltis.ActionText), modifier = Modifier.width(16.dp))
                    ChainChip(chain, 34.dp)
                    Column(Modifier.weight(1f)) {
                        Text(Chains.byId(chain).name, style = Type.row)
                        Text(name.removePrefix(Chains.byId(chain).name).trim().ifBlank { "Sin lugar" }, style = Type.secondary)
                    }
                    IconButton({ if (i > 0) { val x = chosen.removeAt(i); chosen.add(i - 1, x) } }) { Icon(Icons.Filled.KeyboardArrowUp, "Subir") }
                    IconButton({ if (i < chosen.lastIndex) { val x = chosen.removeAt(i); chosen.add(i + 1, x) } }) { Icon(Icons.Filled.KeyboardArrowDown, "Bajar") }
                    IconButton({ chosen.removeAt(i) }) { Icon(Icons.Filled.Close, "Quitar") }
                }
            }
            item {
                SectionLabel("Añadir otra cadena", Modifier.padding(top = 14.dp, bottom = 6.dp))
                OutlinedTextField(query, { query = it }, placeholder = { Text("Todas las de España") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp))
            }
            val filtered = Chains.all.filter { query.isBlank() || T.normalize(it.name).contains(T.normalize(query)) }
            items(filtered, key = { it.id }) { c ->
                Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ChainChip(c.id, 34.dp)
                    Text(c.name, style = Type.row, modifier = Modifier.weight(1f))
                    IconButton({ naming = c.id }, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(Faltis.Elevated)) {
                        Icon(Icons.Filled.Add, "Añadir ${c.name}", tint = Faltis.Saving)
                    }
                }
            }
        }
    }
    naming?.let { chain ->
        TextDialog("¿Qué ${Chains.byId(chain).name}?", "Pueblo o barrio (p. ej. Artà)", "", { naming = null }) { place ->
            chosen += chain to "${Chains.byId(chain).name} ${place.trim()}".trim()
        }
    }
}

@Composable
private fun ListStep(name: String, onName: (String) -> Unit, modifier: Modifier) {
    Column(modifier.fillMaxWidth().padding(top = 26.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Tu primera lista", style = Type.heroNumber.copy(fontSize = 34.sp, lineHeight = 36.sp))
        Text("Puedes tener varias: la de la semana, la de la fiesta…", style = Type.secondary.copy(fontSize = 15.sp))
        OutlinedTextField(name, onName, label = { Text("Nombre") }, modifier = Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp))
    }
}
