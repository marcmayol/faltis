package com.marcmayol.faltis.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** F-09 · De donde sale el texto del ticket. Todo en el dispositivo, sin red. */
object TextSources {

    /** OCR de una imagen con ML Kit (modelo latino incluido en el APK). Reconstruye las filas por posicion. */
    suspend fun fromImage(context: Context, uri: Uri): String = withContext(Dispatchers.Default) {
        val image = InputImage.fromFilePath(context, uri)
        ocr(image)
    }

    private suspend fun ocr(image: InputImage): String = suspendCancellableCoroutine { cont ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(image)
            .addOnSuccessListener { result ->
                // Agrupa las lineas por altura para que "DESCRIPCION ... IMPORTE" quede en la misma fila.
                val lines = result.textBlocks.flatMap { it.lines }.mapNotNull { l -> l.boundingBox?.let { it to l.text } }
                val rows = mutableListOf<MutableList<Pair<android.graphics.Rect, String>>>()
                for (item in lines.sortedBy { it.first.centerY() }) {
                    val row = rows.lastOrNull()
                    val h = item.first.height().coerceAtLeast(1)
                    if (row != null && kotlin.math.abs(row.first().first.centerY() - item.first.centerY()) < h * 0.6) row += item
                    else rows += mutableListOf(item)
                }
                val text = rows.joinToString("\n") { r -> r.sortedBy { it.first.left }.joinToString("  ") { it.second } }
                recognizer.close()
                cont.resume(text)
            }
            .addOnFailureListener { e -> recognizer.close(); cont.resumeWithException(e) }
    }

    /** Texto de un PDF (ticket digital). Si no trae texto, se renderiza la primera pagina y se pasa por OCR. */
    suspend fun fromPdf(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        PDFBoxResourceLoader.init(context.applicationContext)
        val text = context.contentResolver.openInputStream(uri)?.use { input ->
            PDDocument.load(input).use { doc -> PDFTextStripper().apply { sortByPosition = true }.getText(doc) }
        }.orEmpty()
        if (text.count { it.isLetterOrDigit() } > 20) return@withContext text
        // Sin texto: render + OCR.
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return@withContext text
        pfd.use { fd -> renderAndOcr(fd) }
    }

    private suspend fun renderAndOcr(fd: ParcelFileDescriptor): String {
        val renderer = PdfRenderer(fd)
        return renderer.use { r ->
            val page = r.openPage(0)
            val scale = 2
            val bmp = Bitmap.createBitmap(page.width * scale, page.height * scale, Bitmap.Config.ARGB_8888)
            bmp.eraseColor(android.graphics.Color.WHITE)
            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            page.close()
            ocr(InputImage.fromBitmap(bmp, 0))
        }
    }
}
