package com.marcmayol.faltis.core

import java.time.Instant

data class StoreBasket(
    val store: StoreRef,
    val results: List<NeedPriceResult>,
    val comparableCount: Int,
) {
    val priced: List<PricedNeed> = results.filterIsInstance<NeedPriceResult.Priced>().map { it.value }
    val pricedCount: Int get() = priced.size
    val coverage: Double get() = if (comparableCount == 0) 0.0 else pricedCount.toDouble() / comparableCount
    val totalCents: Long get() = priced.sumOf { it.costCents }
    val oldestDays: Long? get() = priced.maxOfOrNull { it.ageDays }
    fun priceFor(needId: Long): PricedNeed? = priced.firstOrNull { it.need.id == needId }
}

data class RankedStore(
    val basket: StoreBasket,
    /** Coste sobre el conjunto comun C. */
    val commonCents: Long,
    /** Diferencia frente a la mejor sobre C (0 para la mejor). */
    val deltaCents: Long,
    val tiedWithBest: Boolean,
)

sealed interface Comparison {
    data object NoStores : Comparison
    data object EmptyList : Comparison
    data class SingleStore(val basket: StoreBasket, val untyped: List<NeedRef>) : Comparison
    data class NotEnoughData(val baskets: List<StoreBasket>, val untyped: List<NeedRef>) : Comparison
    data class Ranking(
        val ranked: List<RankedStore>,
        val missingData: List<StoreBasket>,
        val commonNeedIds: Set<Long>,
        val untyped: List<NeedRef>,
    ) : Comparison {
        val best: RankedStore get() = ranked.first()
    }
}

/** F-06 · Comparacion justa: el ranking se hace sobre las necesidades con precio en TODAS. */
class BasketComparator(private val book: PriceBook) {

    fun basket(needs: List<NeedRef>, store: StoreRef, now: Instant, includePromos: Boolean): StoreBasket {
        val open = needs.filter { it.isOpen }
        val results = open.map { book.priceNeed(it, store, now, includePromos) }
        val comparable = results.count { !(it is NeedPriceResult.Missing && it.reason == NoPriceReason.NOT_COMPARABLE) }
        return StoreBasket(store, results, comparable)
    }

    fun compare(needs: List<NeedRef>, stores: List<StoreRef>, now: Instant, includePromos: Boolean): Comparison {
        if (stores.isEmpty()) return Comparison.NoStores
        val open = needs.filter { it.isOpen }
        if (open.isEmpty()) return Comparison.EmptyList
        val baskets = stores.sortedBy { it.position }.map { basket(open, it, now, includePromos) }
        val untyped = baskets.first().results.filterIsInstance<NeedPriceResult.Missing>()
            .filter { it.reason == NoPriceReason.NOT_COMPARABLE }.map { it.need }
        if (baskets.size == 1) return Comparison.SingleStore(baskets.first(), untyped)

        val eligible = baskets.filter { it.comparableCount > 0 && it.coverage >= MIN_COVERAGE - 1e-9 }
        val missing = baskets - eligible.toSet()
        if (eligible.size < 2) return Comparison.NotEnoughData(baskets, untyped)

        val common = eligible.map { b -> b.priced.map { it.need.id }.toSet() }.reduce { a, b -> a intersect b }
        if (common.isEmpty()) return Comparison.NotEnoughData(baskets, untyped)

        val withCommon = eligible.map { b -> b to b.priced.filter { it.need.id in common }.sumOf { it.costCents } }
        val sorted = withCommon.sortedWith(compareBy({ it.second }, { it.first.store.position }))
        val bestCents = sorted.first().second
        val ranked = sorted.map { (b, c) ->
            RankedStore(b, c, c - bestCents, tiedWithBest = c - bestCents < 1)
        }
        return Comparison.Ranking(ranked, missing, common, untyped)
    }

    companion object {
        const val MIN_COVERAGE = 0.8
    }
}

/** F-08.3 · Orden de pasillos aprendido al terminar la compra. */
object AisleLearner {
    data class Entry(val avgPos: Double, val samples: Int)

    /** [checkedKeys] en el orden en que se tacharon. Devuelve las entradas actualizadas. */
    fun learn(previous: Map<String, Entry>, checkedKeys: List<String>): Map<String, Entry> {
        val n = checkedKeys.size
        val updated = previous.toMutableMap()
        checkedKeys.forEachIndexed { index, key ->
            val p = if (n <= 1) 0.0 else index.toDouble() / (n - 1)
            val old = previous[key]
            updated[key] = if (old == null) Entry(p, 1) else {
                val alpha = 1.0 / minOf(old.samples + 1, 5)
                Entry(old.avgPos + alpha * (p - old.avgPos), old.samples + 1)
            }
        }
        return updated
    }

    /** Ordena: primero con dato (por avgPos), luego el resto por categoria y posicion. */
    fun <T> order(items: List<T>, key: (T) -> String, categoryOrder: (T) -> Int, entries: Map<String, Entry>): List<T> {
        val (known, unknown) = items.partition { entries.containsKey(key(it)) }
        return known.sortedBy { entries.getValue(key(it)).avgPos } + unknown.sortedBy { categoryOrder(it) }
    }
}
