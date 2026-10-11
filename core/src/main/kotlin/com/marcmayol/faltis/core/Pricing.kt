package com.marcmayol.faltis.core

import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.roundToLong

/** Antigüedad de un precio (T1). */
enum class Freshness { FRESH, STALE }

/** El precio que se usa para una necesidad en una tienda. */
data class PricedNeed(
    val need: NeedRef,
    val product: ProductRef,
    val obs: PriceObservation,
    val packs: Int,
    val costCents: Long,
    val unitPriceCents: Long?,
    val estimated: Boolean,
    /** null si sale del "mas barato del tipo"; si no, la relacion con el producto fijado. */
    val equivalence: EquivalenceLevel?,
    val fromChainLevel: Boolean,
    val freshness: Freshness,
    val ageDays: Long,
    val surplusRatio: Double,
)

enum class NoPriceReason { NOT_COMPARABLE, NO_PRODUCT, PINNED_NOT_HERE, NO_RECENT_PRICE }

sealed interface NeedPriceResult {
    data class Priced(val value: PricedNeed) : NeedPriceResult
    data class Missing(val need: NeedRef, val reason: NoPriceReason) : NeedPriceResult
}

class PriceBook(
    private val taxonomy: Taxonomy,
    products: Collection<ProductRef>,
    observations: Collection<PriceObservation>,
) {
    val productsById: Map<Long, ProductRef> = products.associateBy { it.id }
    private val productsByChain: Map<String, List<ProductRef>> = products.groupBy { it.chainId }
    private val obsByProduct: Map<Long, List<PriceObservation>> = observations.groupBy { it.productId }

    fun productsOfChain(chainId: String): List<ProductRef> = productsByChain[chainId].orEmpty()
    fun observationsOf(productId: Long): List<PriceObservation> = obsByProduct[productId].orEmpty()

    /**
     * F-05.1-2 · Observacion utilizable de un producto en una tienda: primero las de la tienda,
     * si no las de la cadena sin tienda; edad <= 90 d; REGULAR mas reciente; empate por fuente;
     * una SAMPLE nunca gana a una que no lo es.
     */
    fun bestObservation(
        productId: Long,
        store: StoreRef,
        now: Instant,
        includePromos: Boolean,
    ): Pair<PriceObservation, Boolean>? {
        val all = observationsOf(productId).filter { ageDays(it.observedAt, now) <= MAX_AGE_DAYS }
        val ofStore = all.filter { it.storeId == store.id }
        val (pool, chainLevel) = if (ofStore.isNotEmpty()) ofStore to false
        else all.filter { it.storeId == null && it.chainId == store.chainId } to true
        if (pool.isEmpty()) return null
        val regular = pickLatest(pool.filter { it.kind == PriceKind.REGULAR })
        if (!includePromos) return regular?.let { it to chainLevel }
        val promo = pickLatest(pool.filter { it.kind != PriceKind.REGULAR && ageDays(it.observedAt, now) <= PROMO_MAX_DAYS })
        val chosen = listOfNotNull(regular, promo).minByOrNull { it.priceCents }
        return chosen?.let { it to chainLevel }
    }

    private fun pickLatest(list: List<PriceObservation>): PriceObservation? {
        if (list.isEmpty()) return null
        val real = list.filter { it.source != PriceSource.SAMPLE }
        val pool = real.ifEmpty { list }
        return pool.sortedWith(
            compareByDescending<PriceObservation> { it.observedAt }.thenBy { it.source.ordinal },
        ).first()
    }

    /** ¿El producto cumple todas las restricciones de la necesidad? */
    fun satisfies(product: ProductRef, restrictions: List<String>): Boolean = restrictions.all { r ->
        when (Text.normalize(r)) {
            "sin lactosa" -> "sin_lactosa" in product.tags || product.typeId?.contains("sin-lactosa") == true
            "sin gluten" -> "sin_gluten" in product.tags
            "ecologico" -> "eco" in product.tags
            "marca blanca" -> product.isPrivateLabel
            "sin azucar" -> "sin_azucar" in product.tags
            else -> true
        }
    }

    /** F-05 completa para una necesidad en una tienda. */
    fun priceNeed(need: NeedRef, store: StoreRef, now: Instant, includePromos: Boolean): NeedPriceResult {
        val pinned = need.pinnedProductId?.let { productsById[it] }
        val needType = taxonomy.byId(need.typeId)
        if (pinned == null && needType == null) return NeedPriceResult.Missing(need, NoPriceReason.NOT_COMPARABLE)

        val candidates: List<Pair<ProductRef, EquivalenceLevel?>> = if (pinned != null) {
            if (pinned.chainId == store.chainId) listOf(pinned to EquivalenceLevel.IDENTICAL)
            else productsOfChain(store.chainId)
                .map { it to Equivalence.level(pinned, it) }
                .filter { it.second == EquivalenceLevel.IDENTICAL || it.second == EquivalenceLevel.EQUIVALENT }
                .ifEmpty { return NeedPriceResult.Missing(need, NoPriceReason.PINNED_NOT_HERE) }
        } else {
            productsOfChain(store.chainId).filter { p ->
                val pt = taxonomy.byId(p.typeId)
                pt != null && needType!!.covers(pt) && satisfies(p, need.restrictions)
            }.map { it to null }
        }
        if (candidates.isEmpty()) return NeedPriceResult.Missing(need, NoPriceReason.NO_PRODUCT)

        val needQty = Quantity(need.qty, need.unit)
        val priced = candidates.mapNotNull { (p, eq) ->
            val (obs, chainLevel) = bestObservation(p.id, store, now, includePromos) ?: return@mapNotNull null
            val bulk = p.netQty == null && p.netUnit != null
            val plan: PackPlan
            val cost: Long
            if (bulk && needQty.unit.dimension == p.netUnit!!.dimension) {
                // A granel: el precio es por kg o l.
                val units = needQty.base / p.netUnit.toBase
                cost = (obs.priceCents * units).roundToLong()
                plan = PackPlan(1, needQty.base, false)
            } else {
                plan = Packs.plan(needQty, p.netQty, p.netUnit)
                cost = obs.priceCents * plan.packs
            }
            val unitPrice = Packs.unitPrice(obs.priceCents.toDouble(), p.netQty, p.netUnit)?.roundToLong()
            val age = ageDays(obs.observedAt, now)
            PricedNeed(
                need = need, product = p, obs = obs, packs = plan.packs, costCents = cost,
                unitPriceCents = if (bulk) obs.priceCents else unitPrice, estimated = plan.estimated,
                equivalence = eq, fromChainLevel = chainLevel,
                freshness = if (age > FRESH_DAYS) Freshness.STALE else Freshness.FRESH,
                ageDays = age, surplusRatio = plan.surplusRatio(needQty.base),
            )
        }
        if (priced.isEmpty()) return NeedPriceResult.Missing(need, NoPriceReason.NO_RECENT_PRICE)

        val best = priced.sortedWith(
            compareBy<PricedNeed> { it.costCents }
                .thenBy { it.surplusRatio }
                .thenBy { it.unitPriceCents ?: Long.MAX_VALUE }
                .thenBy { if (it.product.isPrivateLabel) 0 else 1 }
                .thenBy { it.product.id },
        ).first()
        return NeedPriceResult.Priced(best)
    }

    companion object {
        const val FRESH_DAYS = 30L
        const val MAX_AGE_DAYS = 90L
        const val PROMO_MAX_DAYS = 7L

        /** Dias de CALENDARIO en la zona del dispositivo (T10): ayer a las 12:00 es "1 d" aunque no hayan pasado 24 h. */
        var zone: java.time.ZoneId = java.time.ZoneId.systemDefault()

        fun ageDays(at: Instant, now: Instant): Long =
            abs(java.time.temporal.ChronoUnit.DAYS.between(at.atZone(zone).toLocalDate(), now.atZone(zone).toLocalDate()))
    }
}
