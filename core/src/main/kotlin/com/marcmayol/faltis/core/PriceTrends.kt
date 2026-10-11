package com.marcmayol.faltis.core

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/** Un precio que ha cambiado: [pct] en tanto por uno (+0,18 = un 18 % mas caro). */
data class PriceChange(
    val product: ProductRef,
    val storeId: Long,
    val pct: Double,
    val nowCents: Long,
    val beforeCents: Long,
    /** Compras en las que se ha visto (dias distintos). */
    val samples: Int,
)

/** Cuanto ha subido tu cesta habitual de un mes al otro. */
data class PersonalInflation(val pct: Double, val products: Int)

/**
 * F-10.5 · Tendencias de precio a partir de los tickets (REGULAR, sin datos de ejemplo).
 * Ideas de mercadona-ticket-analyzer (pezbailarin, MIT): comparar con la media y no con la
 * compra anterior, pedir al menos 3 compras y dar mas margen a la fruta y la verdura.
 */
object PriceTrends {
    const val MIN_PURCHASES = 3
    const val ALERT = 0.15
    /** La fruta y la verdura cambian con la temporada. */
    const val ALERT_SEASONAL = 0.25

    /** Por producto y tienda: un precio por dia (el ultimo), del mas antiguo al mas reciente. */
    private fun series(book: PriceBook, p: ProductRef): Map<Long, List<PriceObservation>> =
        book.observationsOf(p.id)
            .filter { it.kind == PriceKind.REGULAR && it.source != PriceSource.SAMPLE && it.storeId != null }
            .groupBy { it.storeId!! }
            .mapValues { (_, obs) ->
                obs.groupBy { day(it.observedAt) }.values.map { d -> d.maxBy { it.observedAt } }.sortedBy { it.observedAt }
            }

    private fun day(i: Instant): LocalDate = i.atZone(PriceBook.zone).toLocalDate()

    fun isSeasonal(taxonomy: Taxonomy, p: ProductRef): Boolean =
        taxonomy.byId(p.typeId)?.categoryId == "fruta-verdura"

    /** "Ha subido": el ultimo precio supera la media de los anteriores en un 15 % (25 % fruta y verdura). */
    fun alerts(book: PriceBook, taxonomy: Taxonomy): List<PriceChange> =
        book.productsById.values.flatMap { p ->
            series(book, p).mapNotNull { (storeId, s) ->
                if (s.size < MIN_PURCHASES) return@mapNotNull null
                val last = s.last()
                val mean = s.dropLast(1).map { it.priceCents }.average()
                val pct = (last.priceCents - mean) / mean
                val threshold = if (isSeasonal(taxonomy, p)) ALERT_SEASONAL else ALERT
                if (pct > threshold) PriceChange(p, storeId, pct, last.priceCents, Math.round(mean), s.size) else null
            }
        }.sortedByDescending { it.pct }

    /** Lo que mas ha cambiado entre la primera y la ultima compra (subidas positivas, bajadas negativas). */
    fun biggestChanges(book: PriceBook): List<PriceChange> =
        book.productsById.values.flatMap { p ->
            series(book, p).mapNotNull { (storeId, s) ->
                if (s.size < 2) return@mapNotNull null
                val first = s.first()
                val last = s.last()
                if (first.priceCents == last.priceCents) return@mapNotNull null
                PriceChange(p, storeId, (last.priceCents - first.priceCents).toDouble() / first.priceCents, last.priceCents, first.priceCents, s.size)
            }
        }.sortedByDescending { kotlin.math.abs(it.pct) }

    /**
     * Inflacion personal: los productos que compraste en [month] y que ya habias comprado antes
     * (en los 90 dias previos al mes), cuanto cuestan ahora frente a entonces. Indice de cesta
     * fija (suma de precios ahora / suma antes). Null si hay menos de [MIN_PURCHASES] productos.
     */
    fun personalInflation(book: PriceBook, month: YearMonth): PersonalInflation? {
        val start = month.atDay(1)
        val end = month.atEndOfMonth()
        var now = 0L
        var before = 0L
        var n = 0
        for (p in book.productsById.values) {
            for ((_, s) in series(book, p)) {
                val inMonth = s.lastOrNull { day(it.observedAt) in start..end } ?: continue
                val prev = s.lastOrNull { day(it.observedAt) < start && !day(it.observedAt).isBefore(start.minusDays(90)) } ?: continue
                now += inMonth.priceCents
                before += prev.priceCents
                n++
            }
        }
        if (n < MIN_PURCHASES || before == 0L) return null
        return PersonalInflation((now - before).toDouble() / before, n)
    }
}
