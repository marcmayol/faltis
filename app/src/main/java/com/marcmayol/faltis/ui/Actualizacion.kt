package com.marcmayol.faltis.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.marcm.actualizador.Actualizador
import com.marcm.actualizador.EstadoActualizacion
import com.marcm.actualizador.Modo
import com.marcm.actualizador.TipoError
import com.marcmayol.faltis.BuildConfig
import com.marcmayol.faltis.ui.theme.Faltis
import com.marcmayol.faltis.ui.theme.Type
import kotlinx.coroutines.launch

/** Aviso no bloqueante en la Lista: solo aparece si hay version nueva o una en marcha. */
@Composable
fun UpdateBanner(estado: EstadoActualizacion, onUpdate: () -> Unit, modifier: Modifier = Modifier) {
    val visible = estado is EstadoActualizacion.Disponible || estado is EstadoActualizacion.Descargando ||
        estado is EstadoActualizacion.Verificando || estado is EstadoActualizacion.PidiendoPermiso ||
        estado is EstadoActualizacion.Instalando
    AnimatedVisibility(visible, modifier, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Faltis.Surface)
                .border(1.dp, Faltis.Action.copy(alpha = 0.6f), RoundedCornerShape(18.dp)).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (estado) {
                is EstadoActualizacion.Disponible -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text("Faltis ${estado.info.versionName} disponible", style = Type.row.copy(fontSize = 15.sp))
                        if (estado.info.notas.isNotBlank()) Text(estado.info.notas, style = Type.secondary.copy(fontSize = 12.sp), maxLines = 3)
                    }
                    PrimaryButton("Actualizar", onUpdate)
                }
                is EstadoActualizacion.Descargando -> Progress("Descargando la actualización", "${estado.porcentaje} %", estado.porcentaje / 100f)
                EstadoActualizacion.Verificando -> Progress("Comprobando el archivo", "Verificando que la descarga está entera", null)
                EstadoActualizacion.PidiendoPermiso -> Progress("Falta un permiso", "Deja que Faltis instale aplicaciones y volvemos aquí", null)
                EstadoActualizacion.Instalando -> Progress("Instalando", "Faltis se reiniciará al terminar", null)
                else -> Unit
            }
        }
    }
}

@Composable
private fun Progress(title: String, detail: String, fraction: Float?) {
    Text(title, style = Type.row.copy(fontSize = 15.sp))
    Text(detail, style = Type.secondary.copy(fontSize = 12.sp))
    val m = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape)
    if (fraction != null) LinearProgressIndicator(progress = { fraction.coerceIn(0f, 1f) }, color = Faltis.Action, trackColor = Faltis.Elevated, modifier = m)
    else LinearProgressIndicator(color = Faltis.Action, trackColor = Faltis.Elevated, modifier = m)
}

/** Seccion de Ajustes: version instalada, busqueda automatica y comprobacion a mano. */
@Composable
fun UpdatesSection(actualizador: Actualizador) {
    val estado by actualizador.estado.collectAsState()
    var auto by remember { mutableStateOf(actualizador.buscarAutomaticamente) }
    val scope = rememberCoroutineScope()
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Faltis.Surface).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Faltis ${BuildConfig.VERSION_NAME}", style = Type.row.copy(fontSize = 15.sp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Buscar actualizaciones solo", style = Type.body.copy(fontSize = 14.sp))
                Text("Una vez al día. Es lo único que usa internet.", style = Type.secondary.copy(fontSize = 12.sp))
            }
            Switch(auto, { auto = it; actualizador.buscarAutomaticamente = it }, colors = SwitchDefaults.colors(checkedTrackColor = Faltis.Action))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Buscar ahora", { scope.launch { actualizador.comprobar(Modo.MANUAL) } }, Modifier.weight(1f))
            if (estado is EstadoActualizacion.Disponible) PrimaryButton("Actualizar", { actualizador.actualizarAhora() }, Modifier.weight(1f))
        }
        val text = messageOf(estado)
        if (text.isNotEmpty()) Text(text, style = Type.secondary.copy(fontSize = 13.sp, color = if (estado is EstadoActualizacion.Error) Faltis.Warn else Faltis.Ink2))
    }
}

private fun messageOf(estado: EstadoActualizacion): String = when (estado) {
    EstadoActualizacion.Comprobando -> "Comprobando…"
    EstadoActualizacion.AlDia -> "Tienes la última versión"
    is EstadoActualizacion.Disponible -> "Hay una versión nueva: ${estado.info.versionName}"
    is EstadoActualizacion.Descargando -> "Descargando… ${estado.porcentaje} %"
    EstadoActualizacion.Verificando -> "Comprobando el archivo…"
    EstadoActualizacion.PidiendoPermiso -> "Falta el permiso para instalar"
    EstadoActualizacion.Instalando -> "Instalando…"
    is EstadoActualizacion.Error -> when (estado.tipo) {
        TipoError.SIN_RED -> "Sin conexión"
        TipoError.HTTP -> "El servidor no responde"
        TipoError.MANIFIESTO -> "No se pudo leer la información de versiones"
        TipoError.DESCARGA -> "No se pudo descargar la actualización"
        TipoError.HASH -> "El archivo descargado no era válido y se ha borrado"
        TipoError.INSTALACION -> estado.mensaje ?: "No se pudo instalar"
    }
    EstadoActualizacion.Inactivo -> ""
}
