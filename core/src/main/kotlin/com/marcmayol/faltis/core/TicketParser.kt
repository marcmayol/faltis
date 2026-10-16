package com.marcmayol.faltis.core

import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import kotlin.math.roundToLong

enum class Confidence { HIGH, MEDIUM, LOW }

/** Comprobacion aritmetica de una linea: cantidad x precio (o kg x €/kg) = importe. */
enum class LineCheck { OK, BAD, UNKNOWN }

data class TicketLine(
    val raw: String,
    val description: String,
    val qty: Double,
    val weightKg: Double?,
    /** Precio unitario bruto: por unidad, o por kg si va al peso. */
    val unitPriceCents: Long?,
    val amountCents: Long,
    /** Descuento en positivo (se resta del importe). */
    val discountCents: Long = 0,
    val discountKind: PriceKind? = null,
    val typeId: String? = null,
    val productId: Long? = null,
    val confidence: Confidence = Confidence.LOW,
    /** true si el precio unitario venia escrito en el ticket (y no se dedujo del importe). */
    val unitPriceRead: Boolean = false,
) {
    val netCents: Long get() = amountCents - discountCents
    /** Precio unitario neto tras el descuento. */
    val netUnitPriceCents: Long?
        get() = when {
            weightKg != null && weightKg > 0 -> (netCents / weightKg).roundToLong()
            qty > 0 -> (netCents / qty).roundToLong()
            else -> null
        }
    /** Clave del diccionario de abreviaturas. */
    val key: String get() = Text.normalize(description)

    /**
     * ¿Cuadra la linea? Un "1,284 kg x 1,99 = 2,65" que no da el importe delata un error de OCR
     * en ese punto exacto. Tolerancia: 1 cent por el redondeo del ticket.
     */
    val check: LineCheck
        get() {
            val unit = unitPriceCents ?: return LineCheck.UNKNOWN
            val expected = when {
                weightKg != null -> (weightKg * unit).roundToLong()
                unitPriceRead && qty > 0 -> (qty * unit).roundToLong()
                else -> return LineCheck.UNKNOWN
            }
            return if (abs(expected - amountCents) <= 1) LineCheck.OK else LineCheck.BAD
        }
}

data class ParsedTicket(
    val chainId: String?,
    val date: LocalDate?,
    val dateUncertain: Boolean,
    val totalCents: Long?,
    val lines: List<TicketLine>,
    /** Numero de factura simplificada ("2345-011-123456"): identifica el ticket sin ambiguedad. */
    val invoice: String? = null,
    val time: LocalTime? = null,
) {
    val linesCents: Long get() = lines.sumOf { it.netCents }
    /** Diferencia entre la suma de lineas y el total leido (null si no hay total). */
    val mismatchCents: Long? get() = totalCents?.let { linesCents - it }
    val balanced: Boolean get() = mismatchCents?.let { abs(it) <= 5 } ?: false
}

/** Diccionario de abreviaturas aprendidas por cadena. */
fun interface AbbreviationLookup {
    fun find(chainId: String?, key: String): Pair<Long?, String?>?
}

/**
 * F-09 · Texto de un ticket (OCR o PDF) -> lineas con importe, descuentos y asignacion.
 * Solo reglas. Pensado para el texto desordenado que devuelve el OCR.
 * Formato del ticket digital de Mercadona contrastado con mercadona-ticket-analyzer
 * (pezbailarin, MIT) y otros lectores publicos; ver NOTICE.md.
 */
class TicketParser(private val taxonomy: Taxonomy) {

    private val money = "-?\\d{1,4}(?:[.,]\\s?\\d{2})"
    private val endPrice = Regex("($money)\\s*(?:€|EUR|E)?\\s*[A-C]?$")
    private val weightRe = Regex("(\\d{1,3}[.,]\\d{3})\\s*KG\\s*[X×*]?\\s*($money)\\s*(?:€|E)?\\s*/\\s*KG\\s+($money)")
    private val qtyTimesRe = Regex("^(.*?)\\s+(\\d{1,3})\\s*[X×*]\\s*($money)\\s+($money)$")
    private val qtyPrefixTwo = Regex("^(\\d{1,3})\\s+(.+?)\\s+($money)\\s+($money)$")
    private val qtyPrefixOne = Regex("^(\\d{1,3})\\s+(.+?)\\s+($money)$")
    private val plain = Regex("^(.+?)\\s+($money)$")
    private val totalRe = Regex("^\\s*TOTAL\\b[^0-9-]*($money)")
    private val dateRe = Regex("\\b(\\d{1,2})[/-](\\d{1,2})[/-](\\d{2,4})\\b")
    private val timeRe = Regex("\\b([01]?\\d|2[0-3]):([0-5]\\d)\\b")
    private val invoiceRe = Regex("FACTURA(?:\\s+SIMPLIFICADA)?\\s*(?:N[O.º°]*)?\\s*:?\\s*([0-9][0-9/-]{5,}[0-9])")
    /** "Importe: 12,89 €" al pie del ticket digital: segundo total por si el OCR pierde el primero. */
    private val importeRe = Regex("^IMPORTE\\s*:?\\s*($money)\\s*(?:€|EUR)?$")

    // Texto "en bloques" (algunos PDF y OCR por columnas): cada dato en su propia linea.
    private val onlyMoney = Regex("^-?\\d{1,4}[.,]\\d{2}\\s*(?:€|EUR)?$")
    private val onlyPerKg = Regex("^$money\\s*(?:€|E)?\\s*/\\s*KG$")
    private val endsKg = Regex("\\d[.,]\\d{3}\\s*KG$")
    private val endsPerKg = Regex("/\\s*KG$")
    private val moneyToken = Regex("(?<![\\d.,])-?\\d{1,4}[.,]\\d{2}(?!\\d)")
    private val leadingQty = Regex("^(\\d{1,3})\\s+\\D")

    // Las palabras clave se buscan sin tildes: "TELÉFONO" = "TELEFONO", "DESCRIPCIÓN" = "DESCRIPCION".
    private val discountWords = Regex("\\b(DTO|DESCUENTO|AHORRO|PROMO|PROMOCION|CUPON|2A UD|2ª UD|2\\? UD|OFERTA)\\b")
    private val memberWords = Regex("\\b(PLUS|CLUB|TARJETA|SOCIO)\\b")
    private val skipWords = Regex(
        "\\b(IVA|I\\.V\\.A|BASE|CUOTA|TARJETA BANCARIA|TARJ|VISA|MASTERCARD|CAMBIO|ENTREGADO|EFECTIVO|SUBTOTAL|" +
            "N\\.?I\\.?F|C\\.?I\\.?F|FACTURA|TELEFONO|TEL|OP:|TPV|AUT|AID|ARC|IMPORTE|DESCRIPCION|P\\. ?UNIT|" +
            "GRACIAS|DEVOLUCION|SE ADMITEN|COPIA|CLIENTE|LEIDA|CONTACTLESS|N\\.C|S\\.A|" +
            // Pie de pago, tambien en catalan (Suma, supermercados de Baleares y Cataluña).
            "A PAGAR|TARGETA|PAGAMENT|CANVI|ENTREGAT|EFECTIU|LINIES)\\b",
    )

    fun parse(text: String, chainHint: String? = null, lookup: AbbreviationLookup? = null): ParsedTicket {
        val cleaned = text.lines().map { it.replace('\t', ' ').trim() }.filter { it.isNotEmpty() }
        val (rawLines, upper) = joinBlocks(cleaned, cleaned.map { fixOcr(it.uppercase()) })
        val bare = upper.map { Text.bareUpper(it) }

        val chain = chainHint ?: detectChain(bare)
        val (date, uncertain) = detectDate(upper)
        val invoice = bare.firstNotNullOfOrNull { invoiceRe.find(it)?.groupValues?.get(1) }
        val time = bare.firstOrNull { dateRe.containsMatchIn(it) }?.let { timeRe.find(it) }
            ?.let { runCatching { LocalTime.of(it.groupValues[1].toInt(), it.groupValues[2].toInt()) }.getOrNull() }
        var total: Long? = null
        var secondTotal: Long? = null
        val out = mutableListOf<TicketLine>()
        var pendingDesc: String? = null

        for ((i, line) in upper.withIndex()) {
            val raw = rawLines[i]
            val key = bare[i]
            if (total == null && !key.contains("SUBTOTAL")) {
                val tm = totalRe.find(key)
                if (tm != null) { total = cents(tm.groupValues[1]); continue }
            }
            if (key.startsWith("TOTAL")) continue
            // Tras el total, una linea que lo repite es el pago ("A PAGAR", "Targeta", "Pagament"), no un producto.
            if (total != null && endPrice.find(line)?.let { cents(it.groupValues[1]) } == total) continue
            importeRe.find(key)?.let { if (secondTotal == null) secondTotal = cents(it.groupValues[1]) }

            // Linea de peso: "1,284 KG X 1,99 €/KG 2,56" (sola o con la descripcion delante).
            val wm = weightRe.find(line)
            if (wm != null) {
                val before = line.substring(0, wm.range.first).trim().trimStart { it.isDigit() || it == ' ' }
                val desc = before.ifBlank { pendingDesc ?: "ARTICULO AL PESO" }
                out += TicketLine(
                    raw = if (before.isBlank() && pendingDesc != null) "$pendingDesc\n$raw" else raw,
                    description = desc, qty = 1.0, weightKg = num(wm.groupValues[1]),
                    unitPriceCents = cents(wm.groupValues[2]), amountCents = cents(wm.groupValues[3]),
                    unitPriceRead = true,
                )
                pendingDesc = null
                continue
            }

            val isDiscount = discountWords.containsMatchIn(key) ||
                (endPrice.find(line)?.groupValues?.get(1)?.trim()?.startsWith("-") == true)
            if (isDiscount && out.isNotEmpty()) {
                val m = endPrice.find(line)
                if (m != null) {
                    val value = abs(cents(m.groupValues[1]))
                    val last = out.removeAt(out.lastIndex)
                    val kind = if (memberWords.containsMatchIn(key)) PriceKind.MEMBER else PriceKind.PROMO
                    out += last.copy(raw = last.raw + "\n" + raw, discountCents = last.discountCents + value, discountKind = kind)
                    continue
                }
            }
            if (skipWords.containsMatchIn(key) || dateRe.containsMatchIn(line)) { pendingDesc = null; continue }

            val times = qtyTimesRe.find(line)?.takeIf { m ->
                // "6 X 0,79 4,74" solo si cuadra; si no, el "6X0,33" es parte del nombre ("PACK 6X0,33 L").
                abs((num(m.groupValues[2]) * cents(m.groupValues[3])).roundToLong() - cents(m.groupValues[4])) <= 2
            }
            if (times != null) {
                val m = times
                out += TicketLine(raw, m.groupValues[1].trim(), num(m.groupValues[2]), null, cents(m.groupValues[3]), cents(m.groupValues[4]), unitPriceRead = true)
                pendingDesc = null
                continue
            }
            val two = qtyPrefixTwo.find(line)
            if (two != null) {
                val q = num(two.groupValues[1])
                out += TicketLine(raw, two.groupValues[2].trim(), q, null, cents(two.groupValues[3]), cents(two.groupValues[4]), unitPriceRead = true)
                pendingDesc = null
                continue
            }
            val one = qtyPrefixOne.find(line)
            if (one != null) {
                val q = num(one.groupValues[1])
                val amount = cents(one.groupValues[3])
                out += TicketLine(raw, one.groupValues[2].trim(), q, null, if (q > 0) (amount / q).roundToLong() else amount, amount)
                pendingDesc = null
                continue
            }
            val pl = plain.find(line)
            if (pl != null) {
                val desc = pl.groupValues[1].trim()
                if (desc.any { it.isLetter() } && desc.length >= 2) {
                    val amount = cents(pl.groupValues[2])
                    out += TicketLine(raw, desc, 1.0, null, amount, amount)
                }
                pendingDesc = null
                continue
            }
            // Solo descripcion: cabecera de un articulo al peso ("1 PATATA", o "GALERAS" sin cantidad
            // tras una seccion "PESCADO"; la ultima gana).
            if (line.count { it.isLetter() } >= 3 && !line.contains(":")) {
                pendingDesc = line.replace(Regex("^\\d{1,2}\\s+"), "").trim()
            }
        }

        val assigned = out.filter { it.amountCents != 0L || it.discountCents != 0L }.map { assign(it, chain, lookup) }
        return ParsedTicket(chain, date, uncertain, total ?: secondTotal, assigned, invoice, time)
    }

    /**
     * Une lo que llega partido en lineas: "1 LECHE ENTERA" / "0,89", o "0,450 kg" / "9,80 €/kg" / "4,41".
     * Devuelve las lineas originales y las normalizadas, alineadas.
     */
    private fun joinBlocks(raw: List<String>, upper: List<String>): Pair<List<String>, List<String>> {
        val r = mutableListOf<String>()
        val u = mutableListOf<String>()
        for (i in upper.indices) {
            val cur = upper[i]
            val prev = u.lastOrNull()
            val join = prev != null && when {
                onlyPerKg.matches(cur) -> endsKg.containsMatchIn(prev)
                onlyMoney.matches(cur) -> when {
                    endsPerKg.containsMatchIn(prev) -> true
                    !prev.any { it.isLetter() } || (prev.contains(':') && !prev.endsWith(":")) -> false
                    dateRe.containsMatchIn(prev) -> false
                    else -> {
                        val need = leadingQty.find(prev)?.groupValues?.get(1)?.toIntOrNull()?.let { if (it >= 2) 2 else 1 } ?: 1
                        moneyToken.findAll(prev).count() < need
                    }
                }
                else -> false
            }
            if (join) {
                r[r.lastIndex] = r.last() + " " + raw[i]
                u[u.lastIndex] = prev + " " + cur
            } else {
                r += raw[i]
                u += cur
            }
        }
        return r to u
    }

    fun assign(line: TicketLine, chainId: String?, lookup: AbbreviationLookup?): TicketLine {
        lookup?.find(chainId, line.key)?.let { (productId, typeId) ->
            return line.copy(productId = productId, typeId = typeId ?: line.typeId, confidence = Confidence.HIGH)
        }
        val m = taxonomy.matchTicketLine(line.description)
        return if (m != null) line.copy(typeId = m.type.id, confidence = Confidence.MEDIUM)
        else line.copy(confidence = Confidence.LOW)
    }

    fun detectChain(lines: List<String>): String? {
        val head = lines.take(12).joinToString(" ")
        val all = lines.joinToString(" ")
        for (src in listOf(head, all)) {
            for ((kw, id) in Chains.ticketKeywords) {
                if (Regex("\\b${Regex.escape(kw)}\\b").containsMatchIn(src)) return id
            }
        }
        return null
    }

    private fun detectDate(lines: List<String>): Pair<LocalDate?, Boolean> {
        for (l in lines) {
            val m = dateRe.find(l) ?: continue
            val d = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            var y = m.groupValues[3].toInt()
            if (y < 100) y += 2000
            val date = runCatching { LocalDate.of(y, mo, d) }.getOrNull() ?: continue
            return date to false
        }
        return null to true
    }

    /** Errores tipicos del OCR dentro de numeros: O->0, l/I->1, espacio tras la coma. */
    private fun fixOcr(s: String): String =
        s.replace(Regex("(?<=\\d)[O](?=\\d)|(?<=[.,])O|O(?=[.,]\\d)|(?<=[.,]\\d)O"), "0")
            .replace(Regex("(?<=\\d[.,])\\s(?=\\d{2}\\b)"), "")
            .replace(Regex("(?<=\\d)[lI](?=\\d)"), "1")

    private fun num(s: String): Double = s.replace(" ", "").replace(',', '.').toDouble()
    private fun cents(s: String): Long = (num(s) * 100).roundToLong()
}
