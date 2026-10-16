package com.marcmayol.faltis.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileCopy
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import com.marcmayol.faltis.BuildConfig
import com.marcmayol.faltis.core.Chains
import com.marcmayol.faltis.core.Confidence
import com.marcmayol.faltis.core.LineCheck
import com.marcmayol.faltis.core.PriceKind
import com.marcmayol.faltis.core.PriceSource
import com.marcmayol.faltis.core.TicketLine
import com.marcmayol.faltis.data.ReviewedLine
import com.marcmayol.faltis.data.TextSources
import com.marcmayol.faltis.ui.theme.Faltis
import com.marcmayol.faltis.ui.theme.Type
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.roundToLong

// ─── P-08 · Captura ───────────────────────────────────────────────────────────
@Composable
fun TicketCaptureScreen(vm: AppViewModel, storeHint: Long?, onBack: () -> Unit, onReview: () -> Unit, contentPadding: PaddingValues) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var paste by remember { mutableStateOf(false) }
    val chainHint = storeHint?.let { id -> vm.stores.value.firstOrNull { it.id == id }?.chainId }

    fun process(source: PriceSource, extract: suspend () -> String) {
        busy = true; error = null
        scope.launch {
            val text = runCatching { extract() }.getOrElse {
                busy = false
                android.util.Log.w("Faltis", "Lectura de ticket fallida", it)
                error = when (it) {
                    is SecurityException -> "No se ha podido abrir el archivo. Vuelve a compartirlo o elígelo desde aquí."
                    is java.io.IOException -> "No se puede leer este archivo. Si es un PDF, puede estar protegido o dañado."
                    else -> "No se ha podido leer el ticket. Prueba otra vez o elígelo desde aquí."
                }
                return@launch
            }
            vm.startTicket(text, source, chainHint, storeHint,
                onReady = { busy = false; onReview() },
                onEmpty = { busy = false; error = "No se ha reconocido ninguna línea. Prueba con más luz o el ticket entero, o escríbelo a mano." })
        }
    }

    // Ticket compartido desde otra app (Gmail, Archivos, Galeria): se lee nada mas entrar.
    val shared by vm.sharedTicket.collectAsState()
    androidx.compose.runtime.LaunchedEffect(shared) {
        val (uri, mime) = shared ?: return@LaunchedEffect
        vm.sharedTicket.value = null
        if (mime == "application/pdf") process(PriceSource.TICKET_PDF) { TextSources.fromPdf(context, uri) }
        else process(PriceSource.TICKET_PHOTO) { TextSources.fromImage(context, uri) }
    }

    // Varios tickets compartidos a la vez: se importan en lote.
    val sharedBatch by vm.sharedBatch.collectAsState()
    androidx.compose.runtime.LaunchedEffect(sharedBatch) {
        val items = sharedBatch ?: return@LaunchedEffect
        vm.sharedBatch.value = null
        vm.importBatch(items)
    }
    val batch by vm.batch.collectAsState()
    val many = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) vm.importBatch(uris.map { it to (context.contentResolver.getType(it) ?: "") })
    }

    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) process(PriceSource.TICKET_PHOTO) { TextSources.fromImage(context, uri) }
    }
    // Foto con la camara del movil: va a la cache y se borra al leerla (la foto no se guarda).
    val shot = remember { java.io.File(context.cacheDir, "tickets/ticket.jpg") }
    val shotUri = remember { androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fotos", shot.apply { parentFile?.mkdirs() }) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) process(PriceSource.TICKET_PHOTO) { try { TextSources.fromImage(context, shotUri) } finally { shot.delete() } }
    }
    // Faltis declara el permiso de camara (lector de codigos), asi que Android lo exige tambien para abrir la camara del sistema.
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) camera.launch(shotUri)
        else error = "Sin permiso de cámara no se puede hacer la foto. Puedes elegirla de la galería o darlo en los ajustes del móvil."
    }
    val pdf = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) process(PriceSource.TICKET_PDF) { TextSources.fromPdf(context, uri) }
    }

    HaloBackground(color = Faltis.Blue, second = Faltis.Saving) {
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            TopBar("Escanear ticket", "Tus tickets son tus precios", onBack)
            Column(
                Modifier.weight(1f).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (busy) {
                    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = Faltis.Saving)
                        Text("  Leyendo el ticket…", style = Type.body)
                    }
                }
                CaptureOption(Icons.Filled.PhotoCamera, "Hacer foto del ticket", "Con la cámara del móvil. Se lee en el móvil y la foto no se guarda.") {
                    error = null
                    if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) camera.launch(shotUri)
                    else cameraPermission.launch(android.Manifest.permission.CAMERA)
                }
                CaptureOption(Icons.Filled.Image, "Elegir foto del ticket", "De la galería. Se lee en el móvil y la foto no se guarda.") {
                    photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }
                CaptureOption(Icons.Filled.PictureAsPdf, "Elegir PDF", "El ticket digital de Mercadona u otro PDF. Es la lectura más fiable.") {
                    pdf.launch(arrayOf("application/pdf"))
                }
                CaptureOption(Icons.Filled.FileCopy, "Importar varios tickets", "Meses de PDF de golpe: se guardan sus precios y gastos sin tocar tu lista. También puedes compartirlos todos juntos desde Gmail.") {
                    many.launch(arrayOf("application/pdf", "image/*"))
                }
                batch?.let { b -> BatchCard(b, onClose = { vm.clearBatch() }) }
                if (BuildConfig.DEBUG) {
                    CaptureOption(Icons.Filled.ContentPaste, "Pegar texto de ticket", "Solo en la versión de pruebas: para probar sin cámara.") { paste = true }
                    // Lote de PDF sinteticos (src/debug/assets/lote_*.pdf): prueba la importacion en lote.
                    val lote = context.assets.list("")?.filter { it.startsWith("lote_") }.orEmpty().sorted()
                    if (lote.isNotEmpty()) CaptureOption(Icons.Filled.FileCopy, "Importar lote de prueba (${lote.size} PDF)", "Solo en la versión de pruebas: varios tickets de Mercadona de golpe, uno repetido.") {
                        vm.importBatch(lote.map { f ->
                            val tmp = java.io.File(context.cacheDir, f).apply { outputStream().use { o -> context.assets.open(f).use { it.copyTo(o) } } }
                            Uri.fromFile(tmp) to "application/pdf"
                        })
                    }
                    // Tickets sinteticos de src/debug/assets (los mismos que los tests de :core).
                    context.assets.list("")?.filter { it.startsWith("ticket_prueba_") }?.forEach { f ->
                        val isPdf = f.endsWith(".pdf")
                        val name = f.removePrefix("ticket_prueba_").substringBeforeLast('.').replaceFirstChar { it.uppercase() }
                        CaptureOption(Icons.Filled.ContentPaste, "Usar ticket de prueba ($name)", if (isPdf) "PDF real: se lee con PdfBox, igual que uno compartido." else "Texto como el que devuelve el OCR.") {
                            if (isPdf) process(PriceSource.TICKET_PDF) {
                                val tmp = java.io.File(context.cacheDir, f).apply { outputStream().use { o -> context.assets.open(f).use { it.copyTo(o) } } }
                                TextSources.fromPdf(context, Uri.fromFile(tmp))
                            } else process(PriceSource.TICKET_PHOTO) { context.assets.open(f).bufferedReader().use { it.readText() } }
                        }
                    }
                }
                error?.let { SurfaceCard(Modifier.fillMaxWidth(), border = Faltis.Warn) { Text(it, style = Type.body.copy(color = Faltis.Warn)) } }
                SurfaceCard(Modifier.fillMaxWidth()) {
                    SectionLabel("Para que salga bien")
                    Text("• Buena luz y el ticket entero en la foto.\n• Sobre un fondo oscuro y sin arrugas.\n• Si tu súper envía ticket digital, el PDF es lo mejor.", style = Type.secondary.copy(fontSize = 14.sp), modifier = Modifier.padding(top = 6.dp))
                }
            }
        }
    }
    if (paste) TextDialog("Pegar texto de ticket", "Texto del ticket", "", { paste = false }, multiline = true) { t ->
        process(PriceSource.TICKET_PHOTO) { t }
    }
}

@Composable
private fun BatchCard(b: BatchState, onClose: () -> Unit) {
    SurfaceCard(Modifier.fillMaxWidth(), border = if (b.finished) Faltis.Saving.copy(alpha = 0.5f) else Faltis.Elevated) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel(if (b.finished) "Importación terminada" else "Importando ${b.done + 1} de ${b.total}…", Modifier.weight(1f), color = if (b.finished) Faltis.Saving else Faltis.Ink2)
            if (b.finished) androidx.compose.material3.IconButton(onClose) { Icon(Icons.Filled.Close, "Cerrar") }
        }
        GradientBar(b.done.toFloat() / b.total.coerceAtLeast(1), Modifier.padding(top = 8.dp))
        val parts = buildList {
            add("${b.imported} ${if (b.imported == 1) "compra guardada" else "compras guardadas"}")
            if (b.duplicates > 0) add("${b.duplicates} ya estaban")
            if (b.failed > 0) add("${b.failed} sin leer")
        }
        Text(parts.joinToString(" · "), style = Type.body, modifier = Modifier.padding(top = 8.dp))
        if (b.unbalanced > 0) Text(
            "${b.unbalanced} no cuadran con su total: revísalas en Gastos.",
            style = Type.secondary.copy(color = Faltis.Warn, fontSize = 13.sp), modifier = Modifier.padding(top = 4.dp),
        )
    }
}

@Composable
private fun CaptureOption(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Faltis.Surface).border(1.dp, Faltis.Elevated, RoundedCornerShape(18.dp))
            .clickable(onClick = onClick).heightIn(min = 72.dp).padding(16.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).clip(RoundedCornerShape(14.dp)).background(Faltis.ActionSoft), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Faltis.ActionText) }
        Column(Modifier.weight(1f)) {
            Text(title, style = Type.row)
            Text(body, style = Type.secondary.copy(fontSize = 13.sp))
        }
    }
}

// ─── P-09 · Revision ──────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TicketReviewScreen(vm: AppViewModel, onBack: () -> Unit, onNext: () -> Unit, contentPadding: PaddingValues) {
    val draft by vm.draft.collectAsState()
    val stores by vm.stores.collectAsState()
    val d = draft ?: return // Sin borrador (guardado o cancelado): no navegar desde aqui, la animacion de salida aun compone.
    var editing by remember { mutableStateOf<Int?>(null) }
    var chainMenu by remember { mutableStateOf(false) }
    var storeMenu by remember { mutableStateOf(false) }
    val chainStores = stores.filter { it.chainId == d.chainId }
    val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    HaloBackground(color = Faltis.Blue, second = Faltis.Saving) {
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            TopBar("Revisar ticket", "${Chains.byId(d.chainId).name} · ${d.date.format(fmt)}${if (d.parsed.dateUncertain) " (revisa la fecha)" else ""}", { vm.cancelDraft(); onBack() })
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box {
                            SelectPill("Cadena: ${Chains.byId(d.chainId).name}", false) { chainMenu = true }
                            DropdownMenu(chainMenu, { chainMenu = false }) {
                                Chains.all.forEach { c -> DropdownMenuItem(text = { Text(c.name) }, onClick = { vm.reassignChain(c.id); chainMenu = false }) }
                            }
                        }
                        Box {
                            SelectPill(chainStores.firstOrNull { it.id == d.storeId }?.name ?: "Elegir tienda", d.storeId == null) { storeMenu = true }
                            DropdownMenu(storeMenu, { storeMenu = false }) {
                                chainStores.forEach { s -> DropdownMenuItem(text = { Text(s.name) }, onClick = { vm.updateDraft { it.copy(storeId = s.id) }; storeMenu = false }) }
                                DropdownMenuItem(text = { Text("Nueva tienda…") }, onClick = { storeMenu = false; vm.createStoreForDraft() })
                            }
                        }
                    }
                }
                if (d.duplicate) item {
                    SurfaceCard(Modifier.fillMaxWidth(), border = Faltis.Warn) {
                        Text(
                            if (d.parsed.invoice != null) "Este ticket ya está guardado (factura ${d.parsed.invoice})." else "Parece que este ticket ya está guardado (misma cadena, fecha y total).",
                            style = Type.body.copy(color = Faltis.Warn),
                        )
                    }
                }
                val indexed = d.lines.withIndex().toList()
                // Necesitan tu ojo: sin tipo, o con una cuenta que no cuadra (cantidad x precio != importe).
                val low = indexed.filter { it.value.isItem && (it.value.line.confidence == Confidence.LOW || it.value.line.check == LineCheck.BAD) }
                val rest = indexed - low.toSet()
                if (low.isNotEmpty()) {
                    item { SectionLabel("Necesitan tu ojo · ${low.size}", color = Faltis.Warn, modifier = Modifier.padding(top = 6.dp)) }
                    items(low, key = { "l" + it.index }) { (i, rl) -> LineCard(rl, vm.repo.taxonomy.byId(rl.line.typeId)?.name, attention = true) { editing = i } }
                }
                item { SectionLabel("Leídas · ${rest.size}", Modifier.padding(top = 6.dp)) }
                items(rest, key = { "r" + it.index }) { (i, rl) -> LineCard(rl, vm.repo.taxonomy.byId(rl.line.typeId)?.name, attention = false) { editing = i } }
            }
            // Cuadre fijo abajo: se recalcula en vivo con cada edicion.
            val balanced = d.mismatchCents?.let { abs(it) <= 5 } == true
            Column(
                Modifier.fillMaxWidth().background(Faltis.Surface).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TotalsCell("Suma de líneas", euros(d.linesCents), Modifier.weight(1f))
                    TotalsCell("Total del ticket", d.parsed.totalCents?.let { euros(it) } ?: "—", Modifier.weight(1f))
                    TotalsCell(
                        "Diferencia",
                        when { d.mismatchCents == null -> "—"; balanced -> "Cuadra"; else -> (if (d.mismatchCents!! > 0) "+" else "−") + euros(abs(d.mismatchCents!!)) },
                        Modifier.weight(1f), color = if (balanced) Faltis.Saving else if (d.mismatchCents == null) Faltis.Ink2 else Faltis.Warn,
                        icon = balanced,
                    )
                }
                if (!balanced && d.mismatchCents != null) {
                    Text(
                        if (d.mismatchCents!! < 0) "Faltan ${euros(abs(d.mismatchCents!!))}: ¿falta una línea o se ha leído mal un importe?"
                        else "Sobran ${euros(d.mismatchCents!!)}: ¿hay un descuento sin leer o una línea que no es un artículo?",
                        style = Type.secondary.copy(color = Faltis.Warn, fontSize = 13.sp),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SecondaryButton("Añadir línea", { editing = vm.addEmptyLine() }, Modifier.weight(1f))
                        SecondaryButton("Seguir así", { vm.prepareReconcile(onNext) }, Modifier.weight(1f))
                    }
                } else {
                    PrimaryButton(
                        "Siguiente: cuadrar con la lista", { vm.prepareReconcile(onNext) },
                        Modifier.fillMaxWidth(), enabled = d.items.isNotEmpty(),
                    )
                }
            }
        }
    }

    editing?.let { i -> LineEditor(vm, d.lines[i], d.chainId, onDismiss = { editing = null }) { updated -> vm.updateDraft { dd -> dd.copy(lines = dd.lines.toMutableList().also { it[i] = updated }) } } }
}

@Composable
private fun TotalsCell(label: String, value: String, modifier: Modifier, color: androidx.compose.ui.graphics.Color = Faltis.Ink, icon: Boolean = false) {
    Column(modifier) {
        Text(label, style = Type.secondary.copy(fontSize = 11.sp), maxLines = 1)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = Type.price.copy(fontSize = 16.sp, color = color), maxLines = 1)
            if (icon) Icon(Icons.Filled.Check, null, tint = Faltis.Saving, modifier = Modifier.padding(start = 4.dp).size(16.dp))
        }
    }
}

@Composable
private fun LineCard(rl: ReviewedLine, typeName: String?, attention: Boolean, onClick: () -> Unit) {
    val l = rl.line
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (attention) Faltis.Warn.copy(alpha = 0.06f) else Faltis.Surface)
            .border(1.dp, if (attention) Faltis.Warn else Faltis.Elevated, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        l.raw.lines().forEach { Text(it.trim(), style = Type.mono.copy(color = Faltis.Ink3, fontSize = 11.5.sp), maxLines = 1) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val name = when {
                !rl.isItem -> "No es un artículo"
                l.description.isBlank() -> "Línea nueva: toca para rellenarla"
                l.typeId != null -> l.description.lowercase().replaceFirstChar { it.uppercase() }
                else -> "¿${l.description.lowercase()}? Toca para elegir el tipo"
            }
            Text(name, style = Type.row.copy(fontSize = 15.sp, color = if (rl.isItem) Faltis.Ink else Faltis.Ink3), modifier = Modifier.weight(1f))
            if (rl.isItem) {
                Text(euros(l.netCents), style = Type.price.copy(fontSize = 15.sp))
                if (l.discountKind != null) ConfidenceChip(if (l.discountKind == PriceKind.MEMBER) "socio" else "promo", Faltis.BrandSoft)
                ConfidenceChip(
                    when (l.confidence) { Confidence.HIGH -> "alta"; Confidence.MEDIUM -> "media"; Confidence.LOW -> "baja" },
                    when (l.confidence) { Confidence.HIGH -> Faltis.Saving; Confidence.MEDIUM -> Faltis.ActionText; Confidence.LOW -> Faltis.Warn },
                )
            }
        }
        if (rl.isItem && typeName != null) Text("→ $typeName", style = Type.secondary.copy(fontSize = 12.sp, color = Faltis.Saving))
        val math = if (l.weightKg != null) "${qtyText(l.weightKg!!)} kg × ${euros(l.unitPriceCents ?: 0)}/kg"
        else if (l.qty > 1) "${qtyText(l.qty)} × ${euros(l.unitPriceCents ?: 0)}" else null
        if (math != null) {
            // Comprobacion de la cuenta: si no da el importe, el OCR se ha equivocado justo aqui.
            val check = l.check
            Text(
                math + when (check) { LineCheck.OK -> "  ✓"; LineCheck.BAD -> "  ≠ ${euros(l.amountCents)}: revisa esta cuenta"; LineCheck.UNKNOWN -> "" },
                style = Type.secondary.copy(fontSize = 12.sp, color = if (check == LineCheck.BAD) Faltis.Warn else Faltis.Ink2),
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LineEditor(vm: AppViewModel, rl: ReviewedLine, chainId: String, onDismiss: () -> Unit, onSave: (ReviewedLine) -> Unit) {
    var desc by remember { mutableStateOf(rl.line.description) }
    var amount by remember { mutableStateOf(euros(rl.line.amountCents, withSymbol = false)) }
    var qty by remember { mutableStateOf(qtyText(rl.line.qty)) }
    var typeId by remember { mutableStateOf(rl.line.typeId) }
    var isItem by remember { mutableStateOf(rl.isItem) }
    var query by remember { mutableStateOf("") }
    val tax = vm.repo.taxonomy
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Faltis.Surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Editar línea", style = Type.title)
            Text(rl.line.raw, style = Type.mono.copy(color = Faltis.Ink3))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Es un artículo", style = Type.body, modifier = Modifier.weight(1f))
                Switch(isItem, { isItem = it })
            }
            OutlinedTextField(desc, { desc = it }, label = { Text("Descripción") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(qty, { qty = it }, label = { Text("Cantidad") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                OutlinedTextField(amount, { amount = it }, label = { Text("Importe €") }, modifier = Modifier.weight(1f), singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            SectionLabel("Tipo")
            Text(tax.byId(typeId)?.name ?: "Sin tipo", style = Type.body.copy(color = if (typeId == null) Faltis.Warn else Faltis.Ink))
            OutlinedTextField(query, { query = it }, label = { Text("Buscar tipo") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
            if (query.length >= 2) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    tax.types.filter { com.marcmayol.faltis.core.Text.normalize(it.name).contains(com.marcmayol.faltis.core.Text.normalize(query)) }.take(8)
                        .forEach { t -> SelectPill(t.name, t.id == typeId) { typeId = t.id; query = "" } }
                }
            }
            PrimaryButton("Guardar", {
                val cents = (amount.replace(" ", "").replace(',', '.').toDoubleOrNull()?.times(100))?.roundToLong() ?: rl.line.amountCents
                val q = qty.replace(',', '.').toDoubleOrNull() ?: rl.line.qty
                val changedType = typeId != rl.line.typeId
                val newLine: TicketLine = rl.line.copy(
                    description = desc, amountCents = cents, qty = q,
                    unitPriceCents = if (rl.line.weightKg != null) rl.line.unitPriceCents else if (q > 0) (cents / q).roundToLong() else cents,
                    // A mano ya no hay cuenta que comprobar, salvo en las lineas al peso.
                    unitPriceRead = rl.line.weightKg != null,
                    typeId = typeId, productId = if (changedType) null else rl.line.productId,
                    confidence = if (typeId != null) Confidence.HIGH else Confidence.LOW,
                )
                onSave(ReviewedLine(newLine, isItem)); onDismiss()
            }, Modifier.fillMaxWidth())
        }
    }
}

// ─── P-10 · Cuadre ────────────────────────────────────────────────────────────
@Composable
fun ReconcileScreen(vm: AppViewModel, onBack: () -> Unit, onSaved: () -> Unit, contentPadding: PaddingValues) {
    val draft by vm.draft.collectAsState()
    val d = draft ?: return // Sin borrador (guardado o cancelado): no navegar desde aqui, la animacion de salida aun compone.
    val p = d.proposal ?: return // Sin borrador (guardado o cancelado): no navegar desde aqui, la animacion de salida aun compone.
    HaloBackground(color = Faltis.Saving, second = Faltis.Brand) {
        Column(Modifier.fillMaxSize().padding(contentPadding)) {
            TopBar("Cuadrar con la lista", "Lo marcado pasa a comprado. Se puede revertir.", onBack)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                item { SectionLabel("Se marcarán como compradas · ${p.toBuy.size}", color = Faltis.Saving) }
                if (p.toBuy.isEmpty()) item { Text("Ninguna línea coincide con tu lista.", style = Type.secondary) }
                items(p.toBuy, key = { "b" + it.id }) { n -> CheckRow(n.text, n.id in d.selectedToBuy) { on -> vm.updateDraft { it.copy(selectedToBuy = if (on) it.selectedToBuy + n.id else it.selectedToBuy - n.id) } } }
                item { Spacer(Modifier.height(8.dp)); SectionLabel("Siguen pendientes · ${p.stillPending.size}") }
                items(p.stillPending, key = { "p" + it.id }) { n -> CheckRow(n.text, n.id in d.selectedToBuy) { on -> vm.updateDraft { it.copy(selectedToBuy = if (on) it.selectedToBuy + n.id else it.selectedToBuy - n.id) } } }
                item { Spacer(Modifier.height(8.dp)); SectionLabel("Fuera de la lista · ${p.outsideList.size}") }
                items(p.outsideList.size) { i ->
                    val l = p.outsideList[i]
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(l.description.lowercase().replaceFirstChar { it.uppercase() }, style = Type.body, modifier = Modifier.weight(1f))
                        Text(euros(l.netCents), style = Type.price.copy(fontSize = 14.sp))
                    }
                }
            }
            PrimaryButton("Guardar compra · ${euros(d.linesCents)}", { vm.saveDraft { onSaved() } }, Modifier.fillMaxWidth().padding(16.dp))
        }
    }
}

@Composable
private fun CheckRow(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Faltis.Surface).clickable { onChange(!checked) }.heightIn(min = 52.dp).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked, onChange, colors = CheckboxDefaults.colors(checkedColor = Faltis.Action))
        Text(text, style = Type.row.copy(fontSize = 15.sp))
    }
}

