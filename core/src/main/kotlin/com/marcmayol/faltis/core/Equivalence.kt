package com.marcmayol.faltis.core

import kotlin.math.abs
import kotlin.math.max

/** F-12 · = Identico, ≈ Equivalente, ~ Parecido. */
enum class EquivalenceLevel(val symbol: String, val label: String) {
    IDENTICAL("=", "Idéntico"),
    EQUIVALENT("≈", "Equivalente"),
    SIMILAR("~", "Parecido"),
}

/** El nivel y por que (se enseña en la ficha de producto). */
data class EquivalenceResult(val level: EquivalenceLevel, val reasons: List<String>)

object Equivalence {
    /** Etiquetas que, presentes en uno solo de los dos, impiden ser equivalentes. */
    private val hardTags = setOf("relleno", "sin_lactosa", "sin_gluten", "eco", "margarina")
    private val tagLabels = mapOf(
        "relleno" to "relleno", "sin_lactosa" to "sin lactosa", "sin_gluten" to "sin gluten",
        "eco" to "ecológico", "margarina" to "con margarina",
    )

    fun level(a: ProductRef, b: ProductRef): EquivalenceLevel? = explain(a, b)?.level

    /**
     * Compara dos productos. Null si no son del mismo tipo (ni uno ancestro del otro).
     * Las reglas de "variante" (numeros, "sin…", envase) vienen del matching de supermercapy:
     * un numero o una negacion distintos casi siempre son otro producto.
     */
    fun explain(a: ProductRef, b: ProductRef): EquivalenceResult? {
        if (a.id == b.id) return EquivalenceResult(EquivalenceLevel.IDENTICAL, listOf("Es el mismo producto"))
        val ea = ProductMatcher.gtin(a.ean) ?: a.ean
        val eb = ProductMatcher.gtin(b.ean) ?: b.ean
        if (ea != null && ea == eb) return EquivalenceResult(EquivalenceLevel.IDENTICAL, listOf("Mismo código de barras"))
        if (a.typeId == null || b.typeId == null) return null
        // Mismo tipo, o uno ancestro del otro ("leche" frente a "leche/entera") cuenta como parecido.
        val sameType = a.typeId == b.typeId
        val related = sameType || a.typeId.startsWith(b.typeId + "/") || b.typeId.startsWith(a.typeId + "/")
        if (!related) return null
        fun similar(why: String) = EquivalenceResult(EquivalenceLevel.SIMILAR, listOf(why))
        if (!sameType) return similar("Uno es más concreto que el otro")
        hardTags.firstOrNull { (it in a.tags) != (it in b.tags) }?.let { return similar("Solo uno es ${tagLabels[it]}") }
        // Solo cuenta si las dos la dicen: una variante sin indicar no es otra variante.
        val va = Text.normalize(a.variant ?: "")
        val vb = Text.normalize(b.variant ?: "")
        if (va.isNotEmpty() && vb.isNotEmpty() && va != vb) return similar("Variante distinta: ${a.variant} frente a ${b.variant}")
        variantWordsDiffer(a, b)?.let { return similar(it) }
        containersDiffer(a, b)?.let { return similar(it) }

        val reasons = mutableListOf<String>()
        when {
            a.isPrivateLabel && b.isPrivateLabel -> reasons += "Las dos son marca blanca"
            !a.isPrivateLabel && !b.isPrivateLabel && a.brand != null && sameBrand(a.brand, b.brand) -> reasons += "Misma marca"
            else -> return similar("Marca distinta")
        }
        if (!formatClose(a, b)) return similar("Formato distinto: ${format(a)} frente a ${format(b)}")
        reasons += when {
            a.netQty == null -> "Mismo formato"
            format(a) == format(b) -> "Mismo formato: ${format(a)}"
            else -> "Formato parecido: ${format(a)} frente a ${format(b)}"
        }
        reasons += "Mismo tipo y variante"
        return EquivalenceResult(EquivalenceLevel.EQUIVALENT, reasons)
    }

    private fun sameBrand(x: String, y: String?): Boolean =
        y != null && ProductMatcher.brandWords(x).joinToString("") == ProductMatcher.brandWords(y).joinToString("")

    private fun nameTokens(p: ProductRef): Set<String> {
        val rest = ProductMatcher.readSize(ProductMatcher.plain(p.name)).second
        val words = ProductMatcher.remove(ProductMatcher.nameWords(rest), listOfNotNull(ProductMatcher.brandWords(p.brand).ifEmpty { null }))
        return ProductMatcher.tokens(words)
    }

    /** "0 %", "sin lactosa", "5 cereales"… en uno solo de los nombres. */
    private fun variantWordsDiffer(a: ProductRef, b: ProductRef): String? {
        val ta = nameTokens(a)
        val tb = nameTokens(b)
        val diff = ((ta - tb) + (tb - ta)).filter { it[0].isDigit() || it.startsWith("sin-") }
        if (diff.isEmpty()) return null
        val shown = diff.first().let { if (it.startsWith("sin-")) "«sin ${it.removePrefix("sin-")}…»" else "«$it»" }
        return "Solo uno dice $shown"
    }

    private fun containersDiffer(a: ProductRef, b: ProductRef): String? {
        val ca = ProductMatcher.containers(ProductMatcher.nameWords(ProductMatcher.plain(a.name)))
        val cb = ProductMatcher.containers(ProductMatcher.nameWords(ProductMatcher.plain(b.name)))
        if (ca.isEmpty() || cb.isEmpty() || ca.intersect(cb).isNotEmpty()) return null
        return "Envase distinto: ${ca.first()} frente a ${cb.first()}"
    }

    private fun format(p: ProductRef): String {
        val q = p.netQty ?: return "sin formato"
        val n = if (q == Math.rint(q)) q.toLong().toString() else q.toString().replace('.', ',')
        return "$n ${p.netUnit?.code ?: ""}".trim()
    }

    /** |a-b| / max(a,b) <= 0,15 en la misma dimension. */
    fun formatClose(a: ProductRef, b: ProductRef): Boolean {
        val qa = a.netQty ?: return a.netQty == b.netQty
        val qb = b.netQty ?: return false
        val ua = a.netUnit ?: return false
        val ub = b.netUnit ?: return false
        if (ua.dimension != ub.dimension) return false
        val ba = qa * ua.toBase
        val bb = qb * ub.toBase
        return abs(ba - bb) / max(ba, bb) <= 0.15 + 1e-9
    }
}
