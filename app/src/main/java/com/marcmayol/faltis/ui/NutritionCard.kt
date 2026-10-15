package com.marcmayol.faltis.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marcmayol.faltis.core.NutritionFacts
import com.marcmayol.faltis.core.OpenFoodFacts
import com.marcmayol.faltis.data.NutritionLookup
import com.marcmayol.faltis.ui.theme.Faltis
import com.marcmayol.faltis.ui.theme.Type
import kotlin.math.roundToInt

/** Colores de los macros: los mismos en el anillo y en la leyenda. */
private val ProteinColor @Composable get() = Faltis.Warn
private val CarbColor @Composable get() = Faltis.Blue
private val FatColor @Composable get() = Faltis.Saving

/** null = cargando. */
@Composable
fun NutritionSection(lookup: NutritionLookup?, onScan: () -> Unit, onRetry: () -> Unit) {
    when (lookup) {
        null -> SurfaceCard(Modifier.fillMaxWidth()) {
            SectionLabel("Información nutricional")
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Faltis.Ink2)
                Text("Buscando su ficha…", style = Type.secondary)
            }
        }
        NutritionLookup.Offline -> SurfaceCard(Modifier.fillMaxWidth()) {
            SectionLabel("Información nutricional")
            Text("No hemos podido conectar con Open Food Facts. Prueba otra vez cuando tengas conexión.", style = Type.secondary, modifier = Modifier.padding(vertical = 10.dp))
            SecondaryButton("Reintentar", onRetry, Modifier.fillMaxWidth(), icon = Icons.Filled.Refresh)
        }
        NutritionLookup.NotFound -> SurfaceCard(Modifier.fillMaxWidth()) {
            SectionLabel("Información nutricional")
            Text(
                "No encontramos la ficha de este producto. Escanea el código de barras del envase y la buscamos por él.",
                style = Type.secondary, modifier = Modifier.padding(vertical = 10.dp),
            )
            PrimaryButton("Escanear código de barras", onScan, Modifier.fillMaxWidth(), icon = Icons.Filled.QrCodeScanner)
        }
        is NutritionLookup.Found -> FactsCards(lookup.facts, lookup.approximate, onScan)
    }
}

@Composable
private fun FactsCards(f: NutritionFacts, approximate: Boolean, onScan: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (f.kcal != null || f.hasMacros) SurfaceCard(Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Información nutricional", style = Type.row, modifier = Modifier.weight(1f))
                Text("Por ${f.per}", style = Type.secondary.copy(fontSize = 13.sp))
            }
            // Anillo y leyenda en columna: con letra al 150 % no caben lado a lado en un movil pequeño.
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                MacroRing(f, Modifier.size(116.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val shares = f.macroShares()
                    MacroRow("Proteínas", f.proteins, shares?.first, ProteinColor)
                    MacroRow("Hidratos", f.carbs, shares?.second, CarbColor)
                    MacroRow("Grasas", f.fat, shares?.third, FatColor)
                }
            }
            f.dailyShare()?.let {
                Text(
                    "Aporta el $it % de la energía diaria de referencia (2.000 kcal).",
                    style = Type.secondary.copy(fontSize = 13.sp), modifier = Modifier.padding(top = 12.dp),
                )
            }
            val details = listOfNotNull(
                f.sugars?.let { "de los cuales azúcares" to it }, f.saturatedFat?.let { "grasas saturadas" to it },
                f.fiber?.let { "fibra" to it }, f.salt?.let { "sal" to it },
            )
            if (details.isNotEmpty()) Column(Modifier.padding(top = 10.dp)) {
                details.forEach { (label, g) ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Text(label.replaceFirstChar { it.uppercase() }, style = Type.body.copy(fontSize = 14.sp, color = Faltis.Ink2), modifier = Modifier.weight(1f))
                        Text(grams(g), style = Type.price.copy(fontSize = 14.sp))
                    }
                }
            }
            if (f.nutriScore != null || f.nova != null) Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                f.nutriScore?.let { NutriScore(it) }
                f.nova?.let { Chip("NOVA $it · ${novaText(it)}") }
            }
        }

        SurfaceCard(Modifier.fillMaxWidth(), border = if (f.allergens.isNotEmpty()) Faltis.Warn.copy(alpha = 0.45f) else Faltis.Elevated) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Filled.WarningAmber, null, tint = Faltis.Warn, modifier = Modifier.size(18.dp))
                Text("Alérgenos", style = Type.row)
            }
            if (f.allergens.isEmpty()) Text("No declara alérgenos.", style = Type.secondary, modifier = Modifier.padding(top = 8.dp))
            else FlowRow(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                f.allergens.forEach { AllergenChip(it) }
            }
            if (f.traces.isNotEmpty()) Text(
                "Puede contener: " + f.traces.joinToString(", ") { it.lowercase() } + ".",
                style = Type.body.copy(fontSize = 14.sp, color = Faltis.Ink2), modifier = Modifier.padding(top = 10.dp),
            )
            f.ingredients?.let { Ingredients(it) }
        }

        // De donde sale y, si se encontro por nombre, como corregirla.
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
            if (approximate) Text(
                "Encontrada por el nombre: «${listOfNotNull(f.name.takeIf { it.isNotBlank() }, f.brand, f.quantity).joinToString(" · ")}». " +
                    "Si no es exactamente tu producto, escanea su código de barras.",
                style = Type.secondary.copy(fontSize = 13.sp),
            )
            Text(OpenFoodFacts.ATTRIBUTION, style = Type.secondary.copy(fontSize = 12.sp, color = Faltis.Ink3))
            if (approximate) SecondaryButton("Escanear código de barras", onScan, Modifier.fillMaxWidth(), icon = Icons.Filled.QrCodeScanner)
        }
    }
}

@Composable
private fun MacroRing(f: NutritionFacts, modifier: Modifier) {
    val shares = f.macroShares()
    val p = ProteinColor; val c = CarbColor; val g = FatColor; val track = Faltis.Elevated
    Box(modifier.semantics { contentDescription = "${f.kcal?.roundToInt() ?: "—"} kilocalorías por ${f.per}" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = 12.dp.toPx()
            val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            if (shares != null) {
                var start = -90f
                // Huecos de 3° entre tramos (solo entre los que tienen algo).
                val parts = listOf(shares.first to p, shares.second to c, shares.third to g).filter { it.first > 0 }
                val gap = if (parts.size > 1) 3f else 0f
                parts.forEach { (pct, color) ->
                    val sweep = pct * 3.6f - gap
                    drawArc(color, start, sweep.coerceAtLeast(1f), false, Offset(inset, inset), arc, style = Stroke(stroke))
                    start += pct * 3.6f
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(f.kcal?.roundToInt()?.toString() ?: "—", style = Type.bigNumber.copy(fontSize = 26.sp))
            Text("kcal", style = Type.secondary.copy(fontSize = 12.sp))
        }
    }
}

@Composable
private fun MacroRow(label: String, grams: Double?, pct: Int?, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        Column(Modifier.weight(1f)) {
            Text(label, style = Type.row.copy(fontSize = 15.sp))
            pct?.let { Text("$it % de las kcal", style = Type.secondary.copy(fontSize = 12.sp)) }
        }
        Text(grams?.let { grams(it) } ?: "—", style = Type.price.copy(fontSize = 15.sp))
    }
}

@Composable
private fun AllergenChip(name: String) {
    Text(
        name, style = Type.row.copy(fontSize = 14.sp, color = Faltis.Warn),
        modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(Faltis.Warn.copy(alpha = 0.12f))
            .border(1.dp, Faltis.Warn.copy(alpha = 0.35f), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

@Composable
private fun Ingredients(text: String) {
    var open by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(top = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { open = !open }.heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Ingredientes", style = Type.row.copy(fontSize = 15.sp), modifier = Modifier.weight(1f))
            Text(if (open) "Ocultar" else "Ver", style = Type.row.copy(fontSize = 14.sp, color = Faltis.ActionText))
        }
        if (open) Text(text, style = Type.body.copy(fontSize = 14.sp, color = Faltis.Ink2))
    }
}

/** Nutri-Score: letra en su color oficial (A verde oscuro … E rojo). */
@Composable
private fun NutriScore(grade: String) {
    val color = when (grade) {
        "a" -> Color(0xFF038141); "b" -> Color(0xFF85BB2F); "c" -> Color(0xFFE5A800); "d" -> Color(0xFFEE8100); else -> Color(0xFFE63E11)
    }
    Row(
        Modifier.clip(RoundedCornerShape(10.dp)).background(Faltis.Elevated).padding(start = 10.dp, end = 4.dp, top = 4.dp, bottom = 4.dp)
            .semantics { contentDescription = "Nutri-Score ${grade.uppercase()}" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Nutri-Score", style = Type.secondary.copy(fontSize = 12.sp, color = Faltis.Ink2))
        Text(
            grade.uppercase(), style = Type.row.copy(fontSize = 15.sp, color = Color.White, fontWeight = FontWeight.W800), textAlign = TextAlign.Center,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(color).padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
}

private fun novaText(n: Int) = when (n) { 1 -> "sin procesar"; 2 -> "ingrediente culinario"; 3 -> "procesado"; else -> "ultraprocesado" }

private fun grams(g: Double): String = when {
    g == 0.0 -> "0 g"
    g < 0.1 -> "<0,1 g"
    g < 10 -> "%.1f g".format(java.util.Locale.forLanguageTag("es-ES"), g).replace(",0 g", " g")
    else -> "${g.roundToInt()} g"
}
