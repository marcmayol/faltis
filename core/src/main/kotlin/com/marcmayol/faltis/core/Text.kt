package com.marcmayol.faltis.core

import java.text.Normalizer
import java.util.Locale

object Text {
    private val diacritics = Regex("\\p{InCombiningDiacriticalMarks}+")

    private val joinedHyphen = Regex("(?<=[a-z])-(?=[a-z])")
    private val negationSlash = Regex("(?<![a-z0-9])s/\\s*")

    /**
     * Minusculas, sin tildes ni signos, espacios simples. La ñ se conserva como n.
     * "col·lecció" -> "colleccio" (la ela geminada no parte la palabra), "gira-sol" -> "girasol",
     * y "s/" de las etiquetas se lee "sin" ("s/lactosa" -> "sin lactosa").
     */
    fun normalize(s: String): String {
        val noAccents = Normalizer.normalize(s.lowercase(Locale.ROOT), Normalizer.Form.NFD).replace(diacritics, "")
        val joined = noAccents.replace("l·l", "ll").replace("·", "")
            .replace(joinedHyphen, "").replace(negationSlash, " sin ")
        return joined.replace(Regex("[^a-z0-9.,/ ]"), " ").replace(Regex("\\s+"), " ").trim()
    }

    /** Sin tildes y en mayusculas: para buscar palabras clave en tickets ("TELÉFONO" = "TELEFONO"). */
    fun bareUpper(s: String): String =
        Normalizer.normalize(s.uppercase(Locale.ROOT), Normalizer.Form.NFD).replace(diacritics, "")

    fun words(s: String): List<String> = normalize(s).split(' ').filter { it.isNotBlank() }

    /** Plural espanol a singular con reglas sencillas; las excepciones van en [exceptions]. */
    private val exceptions = mapOf(
        "pies" to "pie", "maices" to "maiz", "nueces" to "nuez", "peces" to "pez",
        "luces" to "luz", "arroces" to "arroz", "lapices" to "lapiz", "cafes" to "cafe",
        "tes" to "te", "kiwis" to "kiwi", "yogures" to "yogur", "yogurs" to "yogur",
        "panes" to "pan", "melones" to "melon", "limones" to "limon", "jamones" to "jamon",
        "salmones" to "salmon", "champinones" to "champinon", "calabacines" to "calabacin",
        "macarrones" to "macarron", "botes" to "bote", "leches" to "leche",
    )

    fun singular(word: String): String {
        exceptions[word]?.let { return it }
        if (word.length <= 3) return word
        return when {
            word.endsWith("ces") -> word.dropLast(3) + "z"
            word.endsWith("iones") -> word.dropLast(2)
            word.endsWith("ones") -> word.dropLast(2)
            word.endsWith("es") && word.dropLast(2).lastOrNull() in setOf('l', 'r', 'n', 'd', 'j') -> word.dropLast(2)
            word.endsWith("s") && !word.endsWith("ss") -> word.dropLast(1)
            else -> word
        }
    }

    /**
     * Damerau-Levenshtein acotada: letras que cambiar, poner, quitar o intercambiar ("lehce" -> "leche" = 1).
     * Deja de contar al pasar de [cap]. Idea y codigo de Busqueda.kt de EasyCompra (MIT); ver NOTICE.md.
     */
    fun distance(a: String, b: String, cap: Int = Int.MAX_VALUE): Int {
        if (kotlin.math.abs(a.length - b.length) > cap) return cap + 1
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) {
            var rowMin = Int.MAX_VALUE
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                var v = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost)
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) v = minOf(v, d[i - 2][j - 2] + 1)
                d[i][j] = v
                if (v < rowMin) rowMin = v
            }
            if (rowMin > cap) return cap + 1
        }
        return d[a.length][b.length]
    }

    /** Erratas que se toleran segun la longitud: ninguna con 3 letras o menos, 1 hasta 5, 2 a partir de 6. */
    fun typoAllowance(word: String): Int = when {
        word.length <= 3 -> 0
        word.length <= 5 -> 1
        else -> 2
    }
}
