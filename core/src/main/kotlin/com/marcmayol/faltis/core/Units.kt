package com.marcmayol.faltis.core

import kotlin.math.ceil

/** Unidades que entiende la app. Las de masa y volumen se reducen a g y ml. */
enum class QtyUnit(val code: String, val dimension: Dimension, val toBase: Double) {
    UD("ud", Dimension.COUNT, 1.0),
    PACK("pack", Dimension.COUNT, 1.0),
    G("g", Dimension.MASS, 1.0),
    KG("kg", Dimension.MASS, 1000.0),
    ML("ml", Dimension.VOLUME, 1.0),
    CL("cl", Dimension.VOLUME, 10.0),
    L("l", Dimension.VOLUME, 1000.0),
    /** Detergentes y lavavajillas: el precio de referencia es por lavado o dosis. */
    DOSIS("dosis", Dimension.DOSE, 1.0),
    /** Papel de aluminio, film… */
    M("m", Dimension.LENGTH, 1.0);

    companion object {
        fun fromCode(code: String?): QtyUnit? = entries.firstOrNull { it.code == code?.lowercase() }
    }
}

enum class Dimension { COUNT, MASS, VOLUME, DOSE, LENGTH }

data class Quantity(val amount: Double, val unit: QtyUnit) {
    val base: Double get() = amount * unit.toBase
    fun compatibleWith(other: QtyUnit): Boolean = unit.dimension == other.dimension
}

/** Cuantos envases hacen falta y cuanto sobra. */
data class PackPlan(
    val packs: Int,
    /** Cantidad total comprada en unidades base (g, ml o ud). */
    val boughtBase: Double,
    /** true cuando las unidades no casan (piden "2 ud" y el producto es "1 kg"): se cuenta 1 envase por unidad. */
    val estimated: Boolean,
) {
    fun surplusRatio(neededBase: Double): Double =
        if (neededBase <= 0) 0.0 else (boughtBase - neededBase) / neededBase
}

object Packs {
    /**
     * Envases necesarios para cubrir [needed] con un producto de [netQty] [netUnit].
     * Regla F-07: ceil(cantidad pedida / cantidad neta) si las unidades son compatibles;
     * si no, un envase por unidad pedida (redondeada hacia arriba) y se marca como estimacion.
     */
    fun plan(needed: Quantity, netQty: Double?, netUnit: QtyUnit?): PackPlan {
        if (netQty == null || netUnit == null || netQty <= 0.0) {
            val n = ceil(needed.amount).toInt().coerceAtLeast(1)
            return PackPlan(n, n.toDouble(), estimated = needed.unit.dimension != Dimension.COUNT)
        }
        val netBase = netQty * netUnit.toBase
        if (needed.unit.dimension == netUnit.dimension) {
            val n = ceil(needed.base / netBase - 1e-9).toInt().coerceAtLeast(1)
            return PackPlan(n, n * netBase, estimated = false)
        }
        // Piden unidades sueltas de algo que se vende a peso o volumen (o al reves).
        val n = ceil(needed.amount - 1e-9).toInt().coerceAtLeast(1)
        return PackPlan(n, n * netBase, estimated = true)
    }

    /** Precio por unidad de referencia: €/kg, €/l o €/ud. Null si no se conoce el formato. */
    fun unitPrice(price: Double, netQty: Double?, netUnit: QtyUnit?): Double? {
        if (netQty == null || netUnit == null || netQty <= 0.0) return null
        val base = netQty * netUnit.toBase
        return when (netUnit.dimension) {
            Dimension.MASS -> price / (base / 1000.0)
            Dimension.VOLUME -> price / (base / 1000.0)
            Dimension.COUNT, Dimension.DOSE, Dimension.LENGTH -> price / base
        }
    }

    fun unitPriceLabel(netUnit: QtyUnit?): String = when (netUnit?.dimension) {
        Dimension.MASS -> "€/kg"
        Dimension.VOLUME -> "€/l"
        Dimension.COUNT -> "€/ud"
        Dimension.DOSE -> "€/dosis"
        Dimension.LENGTH -> "€/m"
        null -> ""
    }

    /**
     * F-07 · Formato de un envase leido de su nombre ("LECHE SEMI 6X1L", "YOGUR GRIEGO 4U",
     * "PAPEL HIGIENICO 12 ROLLOS", "COLA 33CL P16"). Usa el lector de tamaños de [ProductMatcher].
     * Devuelve la cantidad neta total del envase en g, ml, ud, dosis o m; null si el nombre no la dice.
     */
    fun fromText(text: String): Pair<Double, QtyUnit>? {
        val size = ProductMatcher.readSize(ProductMatcher.plain(text)).first
        val m = size.total ?: size.loose
        if (m != null) {
            val each = if (size.total == null && size.count != null && size.count > 1 && m.unit != MeasureUnit.PIECE) size.count else 1
            val amount = m.amount * each
            return when (m.unit) {
                MeasureUnit.KILOGRAM -> if (amount >= 1.0) amount to QtyUnit.KG else amount * 1000 to QtyUnit.G
                MeasureUnit.LITRE -> if (amount >= 1.0) amount to QtyUnit.L else amount * 1000 to QtyUnit.ML
                MeasureUnit.PIECE -> amount to QtyUnit.UD
                MeasureUnit.DOSE -> amount to QtyUnit.DOSIS
                MeasureUnit.METRE -> amount to QtyUnit.M
                MeasureUnit.SQUARE_METRE -> null
            }?.let { (q, u) -> Math.round(q * 1000) / 1000.0 to u }
        }
        return size.count?.takeIf { it > 1 }?.let { it.toDouble() to QtyUnit.UD }
    }
}
