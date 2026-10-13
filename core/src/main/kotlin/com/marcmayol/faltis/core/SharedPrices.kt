package com.marcmayol.faltis.core

import java.time.LocalDate
import kotlin.math.roundToLong

/**
 * Una linea de ticket lista para compartir (PLAN §3.3). Va suelta: no lleva
 * nada que la ate a un usuario, a un movil ni a las demas lineas del ticket.
 */
data class SharedLine(
    val chainId: String,
    /** Codigo postal del usuario, o "" si no lo ha puesto. */
    val postalCode: String,
    /** Texto de la linea tal como sale en el ticket, ya normalizado. */
    val text: String,
    /** Precio por unidad, o por kg si [unit] es "kg". */
    val priceCents: Long,
    val unit: String,
    val kind: PriceKind,
    /** Dia del ticket, sin hora. */
    val day: LocalDate,
)

object SharedPrices {
    /** Version del formato que se envia; el servidor rechaza las que no conoce. */
    const val FORMAT = 1

    private const val MAX_TEXT = 60
    private const val MAX_CENTS = 50_000L
    /** Lo mismo que acepta el servidor: un ticket viejo ya no dice el precio de hoy. */
    const val MAX_AGE_DAYS = 60L

    /** Lo que puede delatar a alguien si el OCR se cuela fuera de las lineas de producto. */
    private val personal = Regex(
        "\\d{5,}|@|\\b(TARJETA|VISA|MASTERCARD|MAESTRO|CLIENTE|SOCIO|NIF|CIF|DNI|TELF?|TELEFONO|CAJERO|CAJERA|OPERADOR|IBAN|CUENTA|PUNTOS|CUPON|TOTAL|CAMBIO|EFECTIVO|IVA|FACTURA|TICKET)\\b|\\b(ATENDID|ENTREGAD|AUTORIZ)",
    )

    /**
     * Las lineas compartibles de un ticket. Se quedan fuera las de una tienda
     * sin cadena, las de un ticket de hace mas de [MAX_AGE_DAYS], las que no
     * parecen un producto y las de precio absurdo.
     */
    fun fromTicket(
        chainId: String, postalCode: String, day: LocalDate, lines: List<TicketLine>, today: LocalDate = LocalDate.now(),
    ): List<SharedLine> {
        if (chainId !in Chains.all.map { it.id } || chainId == "otra") return emptyList()
        if (day.isAfter(today.plusDays(1)) || day.isBefore(today.minusDays(MAX_AGE_DAYS))) return emptyList()
        val cp = postalCode.trim().takeIf { Regex("\\d{5}").matches(it) } ?: ""
        return lines.flatMap { l ->
            val text = shareableText(l.description) ?: return@flatMap emptyList()
            val byKg = l.weightKg != null && l.weightKg > 0
            val unit = if (byKg) "kg" else "ud"
            val gross = l.unitPriceCents ?: when {
                byKg -> (l.amountCents / l.weightKg!!).roundToLong()
                l.qty > 0 -> (l.amountCents / l.qty).roundToLong()
                else -> l.amountCents
            }
            buildList {
                if (gross in 1..MAX_CENTS) add(SharedLine(chainId, cp, text, gross, unit, PriceKind.REGULAR, day))
                val net = l.netUnitPriceCents
                if (l.discountCents > 0 && l.discountKind != null && net != null && net in 1 until gross) {
                    add(SharedLine(chainId, cp, text, net, unit, l.discountKind, day))
                }
            }
        }.distinct()
    }

    /** Lo que mas lineas lleva una peticion; el servidor no acepta mas de 200. */
    const val MAX_PER_REQUEST = 200

    /**
     * El cuerpo de la peticion al webhook. Ningun campo admite comillas ni barras
     * invertidas (el texto ya va normalizado), asi que no hace falta escapar nada.
     */
    fun payload(lines: List<SharedLine>): String = lines.joinToString(",", "{\"v\":$FORMAT,\"lines\":[", "]}") {
        "{\"chain\":\"${it.chainId}\",\"cp\":\"${it.postalCode}\",\"text\":\"${it.text}\",\"price\":${it.priceCents}," +
            "\"unit\":\"${it.unit}\",\"kind\":\"${it.kind.name}\",\"day\":\"${it.day}\"}"
    }

    /** El texto que se envia, o null si no parece una linea de producto. */
    fun shareableText(description: String): String? {
        if (personal.containsMatchIn(Text.bareUpper(description))) return null
        val t = Text.normalize(description).uppercase()
        if (t.length < 3 || t.length > MAX_TEXT) return null
        if (t.count { it.isLetter() } < 3) return null
        return t
    }
}
