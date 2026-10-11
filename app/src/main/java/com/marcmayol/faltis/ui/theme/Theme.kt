@file:OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)

package com.marcmayol.faltis.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.marcmayol.faltis.R

/** Preferencia de tema guardada en Ajustes. */
enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Todos los colores de la app. Hay una paleta oscura (la del diseño) y una clara. */
data class Palette(
    val isDark: Boolean,
    val ground: Color,
    val shopGround: Color,
    val surface: Color,
    val elevated: Color,
    val line: Color,
    val lineStrong: Color,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    val action: Color,
    val actionSoft: Color,
    val actionText: Color,
    /** Ahorro y "cuadra": cian en oscuro, verde azulado profundo en claro (contraste AA sobre blanco). */
    val saving: Color,
    val brand: Color,
    val brandSoft: Color,
    /** Avisos: ambar en oscuro, naranja tostado en claro. Se distingue del ahorro tambien por luminosidad. */
    val warn: Color,
    val blue: Color,
    val heroTop: Color,
    val heroBottom: Color,
    val navBar: Color,
    val haloAlpha: Float,
    val gradStart: Color,
    val gradMid: Color,
    val gradEnd: Color,
)

val DarkPalette = Palette(
    isDark = true,
    ground = Color(0xFF07080C), shopGround = Color(0xFF05060A), surface = Color(0xFF10131A), elevated = Color(0xFF1C2130),
    line = Color(0xFF242A37), lineStrong = Color(0xFF2A3142),
    ink = Color(0xFFF3F5FA), ink2 = Color(0xFFA3ACBD), ink3 = Color(0xFF7E889C),
    action = Color(0xFF3D63F2), actionSoft = Color(0xFF1C2340), actionText = Color(0xFF8FA8FF),
    saving = Color(0xFF2DE2E6), brand = Color(0xFFB15CFF), brandSoft = Color(0xFFCFA6FF),
    warn = Color(0xFFFFB547), blue = Color(0xFF4F7CFF),
    heroTop = Color(0xFF141A2B), heroBottom = Color(0xFF0C0F17), navBar = Color(0xF0141823),
    haloAlpha = 0.32f,
    gradStart = Color(0xFF2DE2E6), gradMid = Color(0xFF4F7CFF), gradEnd = Color(0xFFB15CFF),
)

/**
 * Tema claro: el mismo sistema, en "tinta". Fondo frio casi blanco, superficies blancas y
 * versiones mas oscuras de los acentos para que el texto pase AA sobre blanco.
 */
val LightPalette = Palette(
    isDark = false,
    ground = Color(0xFFF4F5F9), shopGround = Color(0xFFEEF0F6), surface = Color(0xFFFFFFFF), elevated = Color(0xFFECEEF4),
    line = Color(0xFFE3E6EE), lineStrong = Color(0xFFD0D5E0),
    ink = Color(0xFF14161D), ink2 = Color(0xFF4F5666), ink3 = Color(0xFF656D7E),
    action = Color(0xFF2F52D8), actionSoft = Color(0xFFE4EAFD), actionText = Color(0xFF2846C2),
    saving = Color(0xFF00737A), brand = Color(0xFF7A3CC9), brandSoft = Color(0xFF7A3CC9),
    warn = Color(0xFFA65200), blue = Color(0xFF3D63F2),
    heroTop = Color(0xFFFFFFFF), heroBottom = Color(0xFFF2F4FB), navBar = Color(0xF5FFFFFF),
    haloAlpha = 0.16f,
    gradStart = Color(0xFF00B4BA), gradMid = Color(0xFF3D63F2), gradEnd = Color(0xFF9147E6),
)

/**
 * Acceso a la paleta activa. Es un estado de Compose: al cambiar de tema, todo lo que la lee
 * se recompone (y los lambdas de dibujo leen el valor nuevo).
 */
object Faltis {
    var palette by mutableStateOf(DarkPalette)

    val Ground get() = palette.ground
    val ShopGround get() = palette.shopGround
    val Surface get() = palette.surface
    val Elevated get() = palette.elevated
    val Line get() = palette.line
    val LineStrong get() = palette.lineStrong
    val Ink get() = palette.ink
    val Ink2 get() = palette.ink2
    val Ink3 get() = palette.ink3
    val Action get() = palette.action
    val ActionSoft get() = palette.actionSoft
    val ActionText get() = palette.actionText
    val Saving get() = palette.saving
    val Brand get() = palette.brand
    val BrandSoft get() = palette.brandSoft
    val Warn get() = palette.warn
    val Blue get() = palette.blue
    val HeroTop get() = palette.heroTop
    val HeroBottom get() = palette.heroBottom
    val NavBar get() = palette.navBar
    val HaloAlpha get() = palette.haloAlpha
    val IsDark get() = palette.isDark

    /** El degradado de marca: solo en el heroe, el progreso y bordes destacados, nunca detras de texto. */
    val Gradient get() = Brush.linearGradient(listOf(palette.gradStart, palette.gradMid, palette.gradEnd))
    val GradientH get() = Brush.horizontalGradient(listOf(palette.gradStart, palette.gradMid, palette.gradEnd))
}

private fun scheme(p: Palette) = if (p.isDark) darkColorScheme(
    primary = p.action, onPrimary = Color.White, primaryContainer = p.actionSoft, onPrimaryContainer = p.ink,
    secondary = p.saving, onSecondary = p.ground, tertiary = p.brand,
    background = p.ground, onBackground = p.ink, surface = p.surface, onSurface = p.ink,
    surfaceVariant = p.elevated, onSurfaceVariant = p.ink2,
    surfaceContainer = p.surface, surfaceContainerHigh = p.elevated, surfaceContainerHighest = p.elevated, surfaceContainerLow = p.surface,
    outline = p.lineStrong, outlineVariant = p.line, error = p.warn, onError = p.ground,
) else lightColorScheme(
    primary = p.action, onPrimary = Color.White, primaryContainer = p.actionSoft, onPrimaryContainer = p.ink,
    secondary = p.saving, onSecondary = Color.White, tertiary = p.brand,
    background = p.ground, onBackground = p.ink, surface = p.surface, onSurface = p.ink,
    surfaceVariant = p.elevated, onSurfaceVariant = p.ink2,
    surfaceContainer = p.surface, surfaceContainerHigh = p.surface, surfaceContainerHighest = p.elevated, surfaceContainerLow = p.surface,
    outline = p.lineStrong, outlineVariant = p.line, error = p.warn, onError = Color.White,
)

// ─── Tipografia: Archivo variable (cifras tabulares) + IBM Plex Mono ─────────
private fun archivo(weight: Int, width: Float) = Font(
    R.font.archivo_variable,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight), FontVariation.width(width)),
    weight = FontWeight(weight),
)

val Archivo = FontFamily(archivo(400, 100f), archivo(500, 100f), archivo(600, 100f), archivo(700, 100f), archivo(800, 100f))
/** Ancho expandido para los numeros y titulares grandes: el toque "futuro". */
val ArchivoWide = FontFamily(archivo(800, 116f))
val ArchivoWider = FontFamily(archivo(800, 122f))
val PlexMono = FontFamily(Font(R.font.plex_mono_regular, FontWeight.Normal), Font(R.font.plex_mono_medium, FontWeight.Medium))

const val TNUM = "tnum"

/** Estilos. Los que llevan color son getters para seguir al tema activo. */
object Type {
    val heroNumber = TextStyle(fontFamily = ArchivoWider, fontWeight = FontWeight.W800, fontSize = 52.sp, lineHeight = 54.sp, letterSpacing = (-1.2).sp, fontFeatureSettings = TNUM)
    val bigNumber = TextStyle(fontFamily = ArchivoWide, fontWeight = FontWeight.W800, fontSize = 34.sp, lineHeight = 38.sp, letterSpacing = (-0.6).sp, fontFeatureSettings = TNUM)
    val screenTitle = TextStyle(fontFamily = ArchivoWide, fontWeight = FontWeight.W800, fontSize = 26.sp, lineHeight = 30.sp, letterSpacing = (-0.2).sp)
    val title = TextStyle(fontFamily = ArchivoWide, fontWeight = FontWeight.W800, fontSize = 20.sp, lineHeight = 24.sp)
    val row = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.W600, fontSize = 16.sp, lineHeight = 21.sp)
    val body = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.W400, fontSize = 15.sp, lineHeight = 21.sp)
    val secondary get() = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.W400, fontSize = 13.sp, lineHeight = 18.sp, color = Faltis.Ink2)
    val price = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.W700, fontSize = 16.sp, lineHeight = 20.sp, fontFeatureSettings = TNUM)
    val label get() = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.W700, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 1.5.sp, color = Faltis.Ink3)
    val mono = TextStyle(fontFamily = PlexMono, fontWeight = FontWeight.Normal, fontSize = 11.sp, lineHeight = 14.sp)
}

private fun typo() = Typography(
    headlineMedium = Type.screenTitle,
    titleLarge = Type.title,
    titleMedium = Type.row,
    bodyLarge = Type.body,
    bodyMedium = Type.body.copy(fontSize = 14.sp),
    bodySmall = Type.secondary,
    labelLarge = TextStyle(fontFamily = Archivo, fontWeight = FontWeight.W700, fontSize = 15.sp),
    labelMedium = Type.label,
)

@Composable
fun FaltisTheme(mode: ThemeMode = ThemeMode.SYSTEM, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val palette = if (dark) DarkPalette else LightPalette
    // Se asigna durante la composicion para que la primera pantalla ya salga con el tema correcto.
    if (Faltis.palette !== palette) Faltis.palette = palette

    // Iconos de la barra de estado y de navegacion: oscuros sobre el tema claro.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
            window.decorView.setBackgroundColor(palette.ground.toArgb())
        }
    }

    MaterialTheme(colorScheme = scheme(palette), typography = typo()) {
        // Sin esto, el texto sin color explicito hereda el negro por defecto (invisible sobre el fondo oscuro).
        CompositionLocalProvider(LocalContentColor provides palette.ink, content = content)
    }
}
