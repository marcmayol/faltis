package com.marcmayol.faltis.core

/** Una necesidad recien escrita, antes de guardarla. */
data class ParsedNeed(
    val text: String,
    val typeId: String?,
    val qty: Double,
    val unit: QtyUnit,
    val restrictions: List<String>,
)

/**
 * F-03 · "2 leches, pan, 1 kg de tomates" -> tres necesidades.
 * Solo reglas: sin IA, sin red.
 */
class FreeTextParser(private val taxonomy: Taxonomy) {

    private val numberWords = mapOf(
        "un" to 1.0, "una" to 1.0, "uno" to 1.0, "dos" to 2.0, "tres" to 3.0, "cuatro" to 4.0,
        "cinco" to 5.0, "seis" to 6.0, "siete" to 7.0, "ocho" to 8.0, "nueve" to 9.0, "diez" to 10.0,
        "doce" to 12.0, "medio" to 0.5, "media" to 0.5,
    )

    private val unitWords = mapOf(
        "kg" to QtyUnit.KG, "kilo" to QtyUnit.KG, "kilos" to QtyUnit.KG, "kgs" to QtyUnit.KG,
        "g" to QtyUnit.G, "gr" to QtyUnit.G, "gramos" to QtyUnit.G, "gramo" to QtyUnit.G,
        "l" to QtyUnit.L, "litro" to QtyUnit.L, "litros" to QtyUnit.L, "lt" to QtyUnit.L,
        "ml" to QtyUnit.ML, "cl" to QtyUnit.CL, "centilitros" to QtyUnit.CL,
        "dosis" to QtyUnit.DOSIS, "lavados" to QtyUnit.DOSIS, "lavado" to QtyUnit.DOSIS,
        "metros" to QtyUnit.M, "metro" to QtyUnit.M,
        "pack" to QtyUnit.PACK, "packs" to QtyUnit.PACK, "paquete" to QtyUnit.PACK, "paquetes" to QtyUnit.PACK,
        "bote" to QtyUnit.UD, "botes" to QtyUnit.UD, "lata" to QtyUnit.UD, "latas" to QtyUnit.UD,
        "ud" to QtyUnit.UD, "uds" to QtyUnit.UD, "unidad" to QtyUnit.UD, "unidades" to QtyUnit.UD,
        "botella" to QtyUnit.UD, "botellas" to QtyUnit.UD, "bolsa" to QtyUnit.UD, "bolsas" to QtyUnit.UD,
    )

    /** Restricciones reconocidas; se quitan del texto antes de buscar el tipo. */
    val knownRestrictions = linkedMapOf(
        "sin lactosa" to "sin lactosa",
        "sin gluten" to "sin gluten",
        "ecologico" to "ecológico",
        "ecologica" to "ecológico",
        "bio" to "ecológico",
        "marca blanca" to "marca blanca",
        "sin azucar" to "sin azúcar",
    )

    fun parse(input: String): List<ParsedNeed> =
        split(input).mapNotNull { parseOne(it) }

    private fun split(input: String): List<String> {
        val parts = input.split(Regex("[,;\\n]+"))
        // " y " separa articulos ("pan y leche"), salvo dentro de frases conocidas.
        return parts.flatMap { it.split(Regex("\\s+y\\s+")) }.map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun parseOne(raw: String): ParsedNeed? {
        var words = raw.trim().split(Regex("\\s+")).filter { it.isNotBlank() }.toMutableList()
        if (words.isEmpty()) return null
        var qty = 1.0
        var unit: QtyUnit? = null
        var dozenFactor = 1.0

        // Sufijo "x2" o "2x" al final.
        Regex("^[x×](\\d+(?:[.,]\\d+)?)$", RegexOption.IGNORE_CASE).find(words.last())?.let {
            qty = it.groupValues[1].replace(',', '.').toDouble(); words.removeAt(words.lastIndex)
        }

        // Cantidad al principio: "2", "1,5", "dos", "medio", "media docena".
        if (words.isNotEmpty()) {
            val first = Text.normalize(words[0])
            val glued = Regex("^(\\d+(?:[.,]\\d+)?)(kg|g|gr|l|ml|cl)$").find(first)
            val number = first.replace(',', '.').toDoubleOrNull()
            when {
                glued != null -> {
                    qty = glued.groupValues[1].replace(',', '.').toDouble()
                    unit = unitWords[glued.groupValues[2]]
                    words.removeAt(0)
                }
                number != null -> { qty = number; words.removeAt(0) }
                numberWords.containsKey(first) -> { qty = numberWords.getValue(first); words.removeAt(0) }
            }
        }

        // "docena(s)" -> x12 unidades.
        if (words.isNotEmpty() && Text.normalize(words[0]).startsWith("docena")) {
            dozenFactor = 12.0; words.removeAt(0); unit = QtyUnit.UD
        }

        // Unidad tras el numero: "kg", "litros", "paquetes"...
        if (unit == null && words.isNotEmpty()) {
            unitWords[Text.normalize(words[0])]?.let { u ->
                unit = u
                words.removeAt(0)
            }
        }
        // Conector "de": "1 kg de tomates".
        if (words.isNotEmpty() && Text.normalize(words[0]) == "de") words.removeAt(0)

        var rest = words.joinToString(" ").trim()
        if (rest.isEmpty()) return null

        val restrictions = mutableListOf<String>()
        var forType = " " + Text.normalize(rest) + " "
        for ((key, label) in knownRestrictions) {
            if (forType.contains(" $key ")) {
                if (label !in restrictions) restrictions += label
                forType = forType.replace(" $key ", " ")
            }
        }

        val match = taxonomy.match(forType)
        val finalQty = qty * dozenFactor
        val finalUnit = unit ?: QtyUnit.UD
        val display = displayText(rest, match)
        return ParsedNeed(display, match?.type?.id, finalQty, finalUnit, restrictions)
    }

    /** Texto que se ve en la lista: en singular si se reconocio el tipo ("leches" -> "Leche"). */
    private fun displayText(rest: String, match: TypeMatch?): String {
        val cleaned = rest.trim()
        val singularized = cleaned.split(Regex("\\s+")).joinToString(" ") { w ->
            val n = Text.normalize(w)
            val s = Text.singular(n)
            if (s != n && match != null) {
                // Conserva tildes del original quitando solo la terminacion plural.
                val cut = n.length - s.length
                if (w.length > cut && !s.endsWith("z")) w.dropLast(cut) else s
            } else w
        }
        return singularized.replaceFirstChar { it.uppercase() }
    }
}
