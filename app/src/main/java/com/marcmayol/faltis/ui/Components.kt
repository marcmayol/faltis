package com.marcmayol.faltis.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marcmayol.faltis.core.Chains
import com.marcmayol.faltis.core.EquivalenceLevel
import com.marcmayol.faltis.ui.theme.ArchivoWide
import com.marcmayol.faltis.ui.theme.Faltis
import com.marcmayol.faltis.ui.theme.Type
import java.text.NumberFormat
import java.util.Locale

// ─── Formato (T7) ─────────────────────────────────────────────────────────────
private val es = Locale.forLanguageTag("es-ES")

fun euros(cents: Long, withSymbol: Boolean = true): String {
    val f = NumberFormat.getNumberInstance(es).apply { minimumFractionDigits = 2; maximumFractionDigits = 2 }
    val s = f.format(cents / 100.0)
    return if (withSymbol) "$s €" else s
}

fun signedEuros(cents: Long): String = (if (cents > 0) "+" else if (cents < 0) "−" else "") + euros(kotlin.math.abs(cents))

fun qtyText(q: Double): String {
    val f = NumberFormat.getNumberInstance(es).apply { maximumFractionDigits = 2 }
    return f.format(q)
}

fun ageText(days: Long): String = when (days) {
    0L -> "hoy"
    else -> "${days}d"
}

// ─── Fondo con halo ───────────────────────────────────────────────────────────
@Composable
fun HaloBackground(
    modifier: Modifier = Modifier,
    color: Color = Faltis.Blue,
    second: Color = Faltis.Brand,
    alignTop: Boolean = true,
    ground: Color = Faltis.Ground,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(ground)
            .drawBehind {
                val center = if (alignTop) Offset(size.width * 0.3f, -size.width * 0.1f) else Offset(size.width * 0.5f, size.height * 1.05f)
                drawCircle(
                    brush = Brush.radialGradient(
                        listOf(color.copy(alpha = Faltis.HaloAlpha), second.copy(alpha = Faltis.HaloAlpha / 3f), Color.Transparent),
                        center = center, radius = size.width * 0.85f,
                    ),
                    radius = size.width * 0.85f, center = center,
                )
            },
        content = content,
    )
}

// ─── Etiqueta de seccion en versalitas ────────────────────────────────────────
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, color: Color = Faltis.Ink3) {
    Text(text.uppercase(), style = Type.label.copy(color = color), modifier = modifier)
}

// ─── Sello de procedencia (T1) ────────────────────────────────────────────────
enum class SealStyle { FRESH, NEUTRAL, STALE }

@Composable
fun Seal(text: String, style: SealStyle = SealStyle.NEUTRAL) {
    val color = when (style) {
        SealStyle.FRESH -> Faltis.Saving
        SealStyle.NEUTRAL -> Faltis.Ink2
        SealStyle.STALE -> Faltis.Warn
    }
    val dash = style == SealStyle.STALE
    Box(
        Modifier
            .drawBehind {
                val stroke = Stroke(
                    width = 1.dp.toPx(),
                    pathEffect = if (dash) PathEffect.dashPathEffect(floatArrayOf(6f, 4f)) else null,
                )
                drawRoundRect(
                    color = if (style == SealStyle.NEUTRAL) Faltis.LineStrong else color,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                    style = stroke,
                )
            }
            .padding(horizontal = 6.dp, vertical = 3.dp),
    ) {
        Text(text.lowercase(), style = Type.mono.copy(color = color))
    }
}

// ─── Monograma de cadena (T6) ─────────────────────────────────────────────────
@Composable
fun ChainChip(chainId: String, size: Dp = 30.dp) {
    val chain = Chains.byId(chainId)
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size * 0.3f))
            .background(Faltis.Elevated)
            .border(1.dp, Faltis.LineStrong, RoundedCornerShape(size * 0.3f))
            .semantics { contentDescription = chain.name },
        contentAlignment = Alignment.Center,
    ) {
        Text(chain.monogram, style = Type.label.copy(color = Faltis.Ink, fontSize = (size.value * 0.34f).sp, letterSpacing = 0.5.sp, fontFamily = ArchivoWide))
    }
}

// ─── Simbolo de equivalencia (F-12) ───────────────────────────────────────────
@Composable
fun EqSymbol(level: EquivalenceLevel?, size: Dp = 32.dp) {
    val (sym, color, label) = when (level) {
        EquivalenceLevel.IDENTICAL -> Triple("=", Faltis.ActionText, "Idéntico")
        EquivalenceLevel.EQUIVALENT -> Triple("≈", Faltis.Saving, "Equivalente")
        EquivalenceLevel.SIMILAR -> Triple("~", Faltis.Warn, "Parecido")
        null -> Triple("·", Faltis.Ink2, "Del tipo")
    }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(color.copy(alpha = 0.13f))
            .semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        Text(sym, color = color, fontSize = (size.value * 0.62f).sp, fontWeight = FontWeight.W800)
    }
}

// ─── Tarjetas ─────────────────────────────────────────────────────────────────
@Composable
fun SurfaceCard(
    modifier: Modifier = Modifier,
    radius: Dp = 18.dp,
    border: Color = Faltis.Elevated,
    background: Color = Faltis.Surface,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(RoundedCornerShape(radius))
            .background(background)
            .border(1.dp, border, RoundedCornerShape(radius))
            .padding(14.dp),
        content = content,
    )
}

/** Heroe con borde degradado de 1,5 dp. */
@Composable
fun GradientCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Faltis.Gradient)
            .padding(1.5.dp),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(23.dp))
                .background(Brush.linearGradient(listOf(Faltis.HeroTop, Faltis.HeroBottom)))
                .padding(18.dp),
            content = content,
        )
    }
}

// ─── Botones ──────────────────────────────────────────────────────────────────
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.heightIn(min = 56.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Faltis.Action, contentColor = Color.White, disabledContainerColor = Faltis.Elevated),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Box(Modifier.size(8.dp)) }
        Text(text, style = Type.row.copy(fontWeight = FontWeight.W700), textAlign = TextAlign.Center)
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, Faltis.LineStrong),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Faltis.Surface, contentColor = Faltis.Ink),
    ) {
        if (icon != null) { Icon(icon, null, Modifier.size(20.dp)); Box(Modifier.size(8.dp)) }
        Text(text, style = Type.row.copy(fontWeight = FontWeight.W700), textAlign = TextAlign.Center)
    }
}

@Composable
fun SquareIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, tint: Color = Faltis.Ink) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Faltis.Surface)
            .border(1.dp, Faltis.Elevated, RoundedCornerShape(14.dp)),
        colors = IconButtonDefaults.iconButtonColors(contentColor = tint),
    ) { Icon(icon, description) }
}

@Composable
fun BackButton(onClick: () -> Unit) = SquareIconButton(Icons.AutoMirrored.Filled.ArrowBack, "Volver", onClick)

// ─── Progreso ─────────────────────────────────────────────────────────────────
@Composable
fun GradientBar(fraction: Float, modifier: Modifier = Modifier, height: Dp = 8.dp, glow: Boolean = false) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(Faltis.Elevated),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(height)
                .clip(CircleShape)
                .background(Faltis.GradientH),
        )
    }
}

@Composable
fun CoverageRing(priced: Int, total: Int, size: Dp = 58.dp) {
    val frac = if (total == 0) 0f else priced.toFloat() / total
    Box(Modifier.size(size).semantics { contentDescription = "$priced de $total con precio" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 6.dp.toPx()
            drawArc(Faltis.Elevated, 0f, 360f, false, style = Stroke(stroke), topLeft = Offset(stroke / 2, stroke / 2),
                size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke))
            drawArc(Brush.sweepGradient(listOf(Faltis.Saving, Faltis.Blue, Faltis.Brand, Faltis.Saving)), -90f, 360f * frac, false,
                style = Stroke(stroke, cap = StrokeCap.Round), topLeft = Offset(stroke / 2, stroke / 2),
                size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke))
        }
        // Tamaño fijo en dp: el anillo no crece con el texto del sistema y la cifra se saldria.
        val scale = androidx.compose.ui.platform.LocalDensity.current.fontScale
        Text("$priced/$total", style = Type.label.copy(color = Faltis.Ink, fontSize = (12f / scale).sp, letterSpacing = 0.sp), maxLines = 1)
    }
}

// ─── Estado vacio ─────────────────────────────────────────────────────────────
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = Type.title, textAlign = TextAlign.Center)
        Text(body, style = Type.secondary.copy(fontSize = 15.sp), textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 320.dp))
        action?.invoke()
    }
}

@Composable
fun Chip(text: String, color: Color = Faltis.Ink2, background: Color = Faltis.Elevated) {
    Text(
        text,
        style = Type.secondary.copy(color = color, fontSize = 11.sp),
        modifier = Modifier.clip(CircleShape).background(background).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

@Composable
fun ConfidenceChip(text: String, color: Color) {
    Text(
        text.uppercase(),
        style = Type.label.copy(color = color, fontSize = 10.sp, letterSpacing = 1.sp),
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 7.dp, vertical = 3.dp),
    )
}

@Composable
fun TopBar(title: String, subtitle: String? = null, onBack: (() -> Unit)?, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (onBack != null) BackButton(onBack)
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.title)
            if (subtitle != null) Text(subtitle, style = Type.secondary)
        }
        actions()
    }
}

@Suppress("unused")
private fun Modifier.nudge(): Modifier = this.offset(0.dp, 0.dp)

// ─── Foto del producto ────────────────────────────────────────────────────────
/**
 * Foto del producto sobre fondo claro (las fotos de catalogo vienen con fondo blanco). Sin foto,
 * o mientras carga o si falla, el icono de su categoria.
 */
@Composable
fun ProductImage(url: String?, categoryId: String?, size: Dp = 48.dp) {
    val shape = RoundedCornerShape(size * 0.22f)
    Box(
        Modifier.size(size).clip(shape).background(if (url != null) Color.White else Faltis.Elevated).border(1.dp, Faltis.Line, shape),
        contentAlignment = Alignment.Center,
    ) {
        var failed by remember(url) { mutableStateOf(url == null) }
        if (failed) Icon(categoryIcon(categoryId), null, tint = Faltis.Ink3, modifier = Modifier.size(size * 0.45f))
        if (url != null) coil.compose.AsyncImage(
            model = url, contentDescription = null, contentScale = androidx.compose.ui.layout.ContentScale.Fit,
            modifier = Modifier.fillMaxSize().padding(size * 0.06f), onError = { failed = true },
        )
    }
}

/** Corazon de favorito (relleno si lo es). */
@Composable
fun FavoriteButton(on: Boolean, onClick: () -> Unit) = SquareIconButton(
    if (on) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
    if (on) "Quitar de favoritos" else "Añadir a favoritos", onClick,
    tint = if (on) Faltis.Brand else Faltis.Ink,
)
