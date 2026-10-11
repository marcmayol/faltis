package com.marcmayol.faltis.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class TaxonomyFile(val version: Int, val categories: List<CategoryDto>)

@Serializable
data class CategoryDto(val id: String, val name: String, val types: List<TypeDto>)

@Serializable
data class TypeDto(
    val id: String,
    val name: String,
    val unit: String,
    val syn: List<String> = emptyList(),
    val abbr: List<String> = emptyList(),
)

/** Un tipo de la taxonomia. Su [id] es una ruta: "leche/entera" es hijo de "leche". */
data class ProductType(
    val id: String,
    val name: String,
    val categoryId: String,
    val categoryName: String,
    val categoryOrder: Int,
    val baseUnit: QtyUnit,
    val synonyms: List<String>,
    val abbreviations: List<String>,
) {
    /** true si [other] es este tipo o uno de sus descendientes ("leche" cubre "leche/entera"). */
    fun covers(other: ProductType): Boolean = other.id == id || other.id.startsWith("$id/")
}

/** [approximate] = encontrado tolerando una errata ("lehce" -> leche). */
data class TypeMatch(val type: ProductType, val score: Int, val approximate: Boolean = false)

class Taxonomy(val types: List<ProductType>) {
    private val byId = types.associateBy { it.id }

    fun byId(id: String?): ProductType? = id?.let { byId[it] }

    /** Normaliza y pasa cada palabra a singular para comparar frases. */
    private fun canon(s: String): String = Text.words(s).joinToString(" ") { Text.singular(it) }

    private val synonymIndex: List<Pair<String, ProductType>> = types.flatMap { t ->
        (t.synonyms + t.name).map { canon(it) to t }
    }.filter { it.first.isNotBlank() }.sortedByDescending { it.first.length }

    private val abbrIndex: List<Pair<String, ProductType>> = types.flatMap { t ->
        t.abbreviations.map { Text.normalize(it) to t }
    }.filter { it.first.isNotBlank() }.sortedByDescending { it.first.length }

    /**
     * Busca el tipo mas especifico cuyo sinonimo aparece en [text] como frase completa.
     * Gana el sinonimo mas largo (mas palabras = mas especifico).
     */
    fun match(text: String, tolerateTypos: Boolean = true): TypeMatch? {
        val c = " " + canon(text) + " "
        for ((syn, type) in synonymIndex) {
            if (c.contains(" $syn ")) {
                val score = if (c.trim() == syn) 100 else 60 + syn.length.coerceAtMost(30)
                return TypeMatch(type, score)
            }
        }
        if (!tolerateTypos) return null
        // Nada exacto: se admite una errata por palabra contra los sinonimos de una sola palabra.
        var best: Pair<Int, ProductType>? = null
        for (word in c.trim().split(' ')) {
            val cap = Text.typoAllowance(word)
            if (cap == 0) continue
            for ((syn, type) in singleWordSynonyms) {
                if (Text.typoAllowance(syn) == 0) continue
                val d = Text.distance(word, syn, cap)
                if (d <= cap && (best == null || d < best.first)) best = d to type
            }
        }
        return best?.let { (d, type) -> TypeMatch(type, 50 - 10 * d, approximate = true) }
    }

    private val singleWordSynonyms = synonymIndex.filter { ' ' !in it.first }

    /** Para lineas de ticket: primero abreviaturas de la cadena, luego sinonimos. */
    fun matchTicketLine(raw: String): TypeMatch? {
        val n = " " + Text.normalize(raw) + " "
        for ((abbr, type) in abbrIndex) {
            if (n.contains(" $abbr")) return TypeMatch(type, 70 + abbr.length.coerceAtMost(20))
        }
        return match(raw, tolerateTypos = false)?.let { it.copy(score = it.score - 20) }
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): Taxonomy {
            val file = json.decodeFromString(TaxonomyFile.serializer(), text)
            val types = file.categories.flatMapIndexed { order, cat ->
                cat.types.map { t ->
                    ProductType(
                        id = t.id, name = t.name, categoryId = cat.id, categoryName = cat.name,
                        categoryOrder = order, baseUnit = QtyUnit.fromCode(t.unit) ?: QtyUnit.UD,
                        synonyms = t.syn, abbreviations = t.abbr,
                    )
                }
            }
            return Taxonomy(types)
        }

        /** La taxonomia incluida en el modulo (recurso taxonomia.json). */
        fun bundled(): Taxonomy {
            val stream = Taxonomy::class.java.getResourceAsStream("/taxonomia.json")
                ?: error("Falta taxonomia.json en los recursos")
            return parse(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        }
    }
}
