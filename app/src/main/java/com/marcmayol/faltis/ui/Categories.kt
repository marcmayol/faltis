package com.marcmayol.faltis.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.BakeryDining
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Eco
import androidx.compose.material.icons.filled.Egg
import androidx.compose.material.icons.filled.FreeBreakfast
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.KebabDining
import androidx.compose.material.icons.filled.LocalDrink
import androidx.compose.material.icons.filled.SetMeal
import androidx.compose.material.icons.filled.Soap
import androidx.compose.material.icons.filled.Tapas
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marcmayol.faltis.core.Taxonomy
import com.marcmayol.faltis.ui.theme.Faltis
import com.marcmayol.faltis.ui.theme.Type

/** Una categoria de la taxonomia (id y nombre) en el orden de la taxonomia. */
data class Category(val id: String, val name: String)

fun Taxonomy.categories(): List<Category> =
    types.sortedBy { it.categoryOrder }.map { Category(it.categoryId, it.categoryName) }.distinctBy { it.id }

/** Icono de cada categoria; null = sin categoria. */
fun categoryIcon(categoryId: String?): ImageVector = when (categoryId) {
    "fruta-verdura" -> Icons.Filled.Eco
    "panaderia" -> Icons.Filled.BakeryDining
    "lacteos" -> Icons.Filled.Egg
    "carne" -> Icons.Filled.KebabDining
    "pescado" -> Icons.Filled.SetMeal
    "charcuteria" -> Icons.Filled.Tapas
    "despensa" -> Icons.Filled.Inventory2
    "desayuno" -> Icons.Filled.FreeBreakfast
    "bebidas" -> Icons.Filled.LocalDrink
    "congelados" -> Icons.Filled.AcUnit
    "limpieza" -> Icons.Filled.CleaningServices
    "higiene" -> Icons.Filled.Soap
    else -> Icons.AutoMirrored.Filled.HelpOutline
}

/** Cabecera de seccion con el icono de su categoria. */
@Composable
fun CategoryHeader(categoryId: String?, name: String, modifier: Modifier = Modifier, extra: String? = null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(categoryIcon(categoryId), null, tint = Faltis.Saving, modifier = Modifier.size(16.dp))
        SectionLabel(name + (extra?.let { " · $it" } ?: ""))
    }
}

/**
 * Elegir categoria en una cuadricula de dos columnas con iconos. [selected] null = "Todo".
 * Las baldosas crecen con la letra: con 150 % el nombre baja de linea en vez de cortarse.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategorySheet(categories: List<Category>, selected: String?, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Faltis.Surface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 20.dp).navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Categoría", style = Type.title, modifier = Modifier.padding(start = 4.dp, bottom = 4.dp))
            val tiles = listOf<Pair<String?, String>>(null to "Todo") + categories.map { it.id to it.name }
            tiles.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (id, name) ->
                        CategoryTile(id, name, id == selected, Modifier.weight(1f)) { onPick(id); onDismiss() }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun CategoryTile(id: String?, name: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.clip(shape)
            .background(if (selected) Faltis.ActionSoft else Faltis.Elevated.copy(alpha = 0.55f))
            .border(if (selected) 1.5.dp else 1.dp, if (selected) Faltis.Action else Faltis.Line, shape)
            .clickable(onClick = onClick).heightIn(min = 92.dp).padding(horizontal = 10.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        Icon(if (id == null) Icons.Filled.GridView else categoryIcon(id), null, tint = if (selected) Faltis.ActionText else Faltis.Ink, modifier = Modifier.size(28.dp))
        Text(name, style = Type.row.copy(fontSize = 15.sp, color = Faltis.Ink), textAlign = TextAlign.Center)
    }
}

/** Pastilla que abre la cuadricula y dice que categoria esta puesta. */
@Composable
fun CategoryPill(categories: List<Category>, selected: String?, onClick: () -> Unit) {
    val name = categories.firstOrNull { it.id == selected }?.name ?: "Categoría"
    val on = selected != null
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) Faltis.ActionSoft else Faltis.Elevated)
            .border(1.dp, if (on) Faltis.Action else Faltis.LineStrong, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).heightIn(min = 40.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(if (on) categoryIcon(selected) else Icons.Filled.GridView, null, tint = if (on) Faltis.ActionText else Faltis.Ink2, modifier = Modifier.size(18.dp))
        Text(name, style = Type.row.copy(fontSize = 14.sp), maxLines = 1)
    }
}
