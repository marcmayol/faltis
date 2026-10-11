package com.marcmayol.faltis.core

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

/*
 * Emparejado de productos entre cadenas: "¿es el mismo producto?" y "¿es una alternativa?".
 *
 * Port a Kotlin del matching de supermercapy (https://github.com/jtayped/supermercapy, MIT,
 * (c) 2024 Joel Taylor): src/supermercapy/_match_text.py y match.py. La logica se mantiene
 * para poder validarla con su dataset etiquetado (test/resources/match/evaluation.json).
 * Ver NOTICE.md.
 */

/** Unidad de un tamaño o de un precio de referencia (€/kg, €/l, €/ud…). */
enum class MeasureUnit { KILOGRAM, LITRE, PIECE, DOSE, METRE, SQUARE_METRE }

data class Measure(val amount: Double, val unit: MeasureUnit)

/** Precio de referencia publicado ("1.135/l"); [decimals] = decimales con que se publico. */
data class ReferencePrice(val amount: Double, val unit: MeasureUnit, val decimals: Int)

/** Lo que contiene una ficha: cuantas unidades de venta y cuanto en total. */
data class PackSize(val count: Int?, val total: Measure?, val slack: Double = 0.0)

/** Lo que un trozo de texto dice del tamaño, antes de combinarlo. */
data class SizeText(val count: Int? = null, val total: Measure? = null, val loose: Measure? = null)

/** Una ficha de producto tal como llega de una tienda o de un ticket. */
data class Listing(
    val store: String?,
    val id: String,
    val name: String,
    val brand: String? = null,
    val ean: String? = null,
    val packSizeText: String? = null,
    val price: Double? = null,
    val reference: ReferencePrice? = null,
    val variableWeight: Boolean = false,
)

data class MatchScore(val score: Double, val reasons: List<String>)

data class Features(
    val store: String?,
    val ean: String?,
    val brand: List<String>,
    val owners: Set<String>,
    val words: List<String>,
    val containers: Set<String>,
    val size: PackSize?,
    val reference: ReferencePrice?,
)

object ProductMatcher {
    const val SAME_THRESHOLD = 0.8
    const val ALTERNATIVE_THRESHOLD = 0.6
    private const val CONTRADICTED = 0.5

    // ----------------------------------------------------------------- texto plano

    private val combining = Regex("\\p{M}+")
    private val hyphenated = Regex("(?<=[a-z])-(?=[a-z])")
    private val negationSlash = Regex("\\bs/\\s*")
    private val wordRe = Regex("\\d+(?:[.,]\\d+)?|[a-z]+")

    /** Minusculas y sin tildes; "l·l" pasa a "ll". */
    fun plain(text: String): String {
        val marked = text.replace('®', ' ').replace('™', ' ').replace('©', ' ').replace('&', ' ').replace('’', '\'')
        val folded = Normalizer.normalize(marked.lowercase(Locale.ROOT), Normalizer.Form.NFKD).replace(combining, "")
        return folded.replace("l·l", "ll").replace("·", "")
    }

    private fun words(text: String): List<String> = wordRe.findAll(text).map { it.value.replace(',', '.') }.toList()

    /** Palabras de un nombre, uniendo "gira-sol" y leyendo "s/" como "sin". */
    fun nameWords(text: String): List<String> = words(negationSlash.replace(hyphenated.replace(text, ""), " sin "))

    // ------------------------------------------------------------------------ marca

    private val legalSuffix = Regex("[\\s,]+(?:s\\.?\\s?a\\.?\\s?u?\\.?|s\\.?\\s?l\\.?\\s?u?\\.?)$")

    /** Marcas propias por cadena (ids de Faltis). Caprabo es del grupo Eroski. */
    val storeBrands: Map<String, List<String>> = mapOf(
        "mercadona" to listOf("hacendado", "deliplus", "bosque verde", "compy"),
        "consum" to listOf("consum"),
        "carrefour" to listOf("carrefour", "simpl"),
        "bonarea" to listOf("bonarea"),
        "lidl" to listOf(
            "milbona", "pilos", "solevita", "freeway", "realvalle", "nixe", "chef select", "vita d or", "kania", "alesto",
            "formil", "floralys", "dentalux", "aromata", "baresa", "dulano", "sol mar", "silvercrest", "livarno", "parkside",
            "esmara", "crivit", "lupilu", "deluxe",
        ),
        "aldi" to listOf("milsani", "tandil", "esselt", "biscotto", "sal de plata", "gut bio", "choceur", "moser roth", "lacura", "almare"),
        "dia" to listOf("dia"),
        "eroski" to listOf("eroski", "e basic", "seleqtia", "belle"),
        "caprabo" to listOf("caprabo", "eroski", "e basic", "seleqtia", "belle"),
        "bonpreu" to listOf("bonpreu", "esclat"),
        "alcampo" to listOf("alcampo", "auchan"),
        "ahorramas" to listOf("alipende"),
        "condis" to listOf("condis"),
    )
    private val storeBrandPhrases: List<Pair<List<String>, String>> =
        storeBrands.flatMap { (store, phrases) -> phrases.map { it.split(' ') to store } }

    fun brandWords(brand: String?): List<String> {
        if (brand.isNullOrEmpty()) return emptyList()
        val text = legalSuffix.replace(plain(brand).trim(), "")
        return words(text.replace("-", " "))
    }

    /** Primer tramo [inicio, fin) de [words] que deletrea [phrase], tambien escrito junto ("cola cao" = "colacao"). */
    fun find(words: List<String>, phrase: List<String>): Pair<Int, Int>? {
        if (phrase.isEmpty()) return null
        val key = phrase.joinToString("")
        val size = phrase.size
        for (start in words.indices) {
            if (start + size <= words.size && words.subList(start, start + size) == phrase) return start to start + size
            var joined = ""
            for (end in start until words.size) {
                joined += words[end]
                if (joined == key) return start to end + 1
                if (!key.startsWith(joined)) break
            }
        }
        return null
    }

    /** Quita de [words] cada tramo que deletrea una de [phrases], las mas largas primero. */
    fun remove(words: List<String>, phrases: Collection<List<String>>): List<String> {
        val result = words.toMutableList()
        for (phrase in phrases.sortedByDescending { it.size }) {
            while (true) {
                val (from, to) = find(result, phrase) ?: break
                result.subList(from, to).clear()
            }
        }
        return result
    }

    /** Cadenas de las que [brand] es marca propia. */
    fun owners(brand: List<String>): Set<String> =
        storeBrandPhrases.filter { (phrase, _) -> find(brand, phrase) != null }.map { it.second }.toSet()

    /** ¿Es la marca propia de alguna cadena? */
    fun isStoreBrand(brand: String?): Boolean = owners(brandWords(brand)).isNotEmpty()

    // --------------------------------------------------------------------- unidades

    private val vocabulary: Map<String, Measure> = buildMap {
        fun add(m: Measure, vararg ws: String) = ws.forEach { put(it, m) }
        add(Measure(1.0, MeasureUnit.KILOGRAM), "kg", "kgs", "kilo", "kilos", "kilogramo", "kilogramos", "kilogram", "kilograms", "quilo", "quilos", "quilogram", "quilograms")
        add(Measure(0.001, MeasureUnit.KILOGRAM), "g", "gr", "grs", "gramo", "gramos", "gram", "grams")
        add(Measure(1.0, MeasureUnit.LITRE), "l", "lt", "litro", "litros", "litre", "litres", "liter", "liters")
        add(Measure(0.01, MeasureUnit.LITRE), "cl", "centilitro", "centilitros", "centilitre", "centilitres")
        add(Measure(0.001, MeasureUnit.LITRE), "ml", "mililitro", "mililitros", "millilitre", "millilitres")
        add(Measure(1.0, MeasureUnit.PIECE), "u", "ud", "uds", "un", "unidad", "unidades", "unitat", "unitats", "each", "piece", "pieces")
        add(Measure(12.0, MeasureUnit.PIECE), "docena", "docenas", "dotzena", "dotzenes", "dozen")
        add(Measure(1.0, MeasureUnit.DOSE), "dosis", "dosi", "dose", "doses", "lavado", "lavados", "rentat", "rentats", "rentada", "rentades", "wash", "washes")
        add(Measure(1.0, MeasureUnit.METRE), "m", "metro", "metros", "metre", "metres", "meter", "meters")
        add(Measure(1.0, MeasureUnit.SQUARE_METRE), "m2", "m²")
    }
    private val unitText = Regex("(?:€\\s*)?(?:/\\s*)?(?:(\\d+(?:[.,]\\d+)?)\\s*)?([^\\d\\s].*?)\\.*")

    /** "100 g" -> 0,1 kg; "docena" -> 12 piezas. Null si no se entiende. */
    fun measure(text: String): Measure? {
        val clean = text.trim().split(Regex("\\s+")).joinToString(" ").lowercase(Locale.ROOT)
        val m = unitText.matchEntire(clean) ?: return null
        val base = vocabulary[m.groupValues[2]] ?: return null
        val countText = m.groups[1]?.value ?: return base
        val count = countText.replace(',', '.').toDouble()
        if (count <= 0) return null
        return Measure(base.amount * count, base.unit)
    }

    /** "1.135/l" o "6,20/100 g" -> precio por kg, l, ud… */
    fun referencePrice(text: String?): ReferencePrice? {
        if (text == null) return null
        val slash = text.indexOf('/')
        if (slash < 0) return null
        val amountText = text.substring(0, slash).trim().replace(',', '.')
        val amount = amountText.toDoubleOrNull() ?: return null
        val q = measure(text.substring(slash + 1)) ?: return null
        val decimals = if ('.' in amountText) amountText.substringAfter('.').length else 0
        return ReferencePrice(amount / q.amount, q.unit, decimals)
    }

    // ---------------------------------------------------------------------- tamaños

    private const val NUMBER = "\\d+(?:[.,]\\d+)?"
    private const val COUNT_WORDS = "u|ud|uds|unid|unidad|unidades|unitat|unitats|latas?|llaunes|llauna|" +
        "botellas?|botellines|ampolles|bri(?:c|ck|k)s?|rollos?|rotlles?|sobres?|" +
        "capsulas?|capsules|pastillas?|pastilles|paquets?|bolsitas?|botes|" +
        "piezas?|peces|tarrinas?|tabletas?"
    private val multiRe = Regex("(?<![\\d.,])(\\d+)(?:\\s*\\+\\s*(\\d+))?\\s*[x×]\\s*($NUMBER)\\s*([a-z]+)\\b")
    private val countOfRe = Regex("(?<![\\d.,])(\\d+)(?:\\s*\\+\\s*(\\d+))?\\s*(?:$COUNT_WORDS)\\b\\.?\\s+de\\s+($NUMBER)\\s*([a-z]+)\\b")
    private val countedRe = Regex("(?<![\\d.,])(\\d+)(?:\\s*\\+\\s*(\\d+))?\\s*(?:(?:$COUNT_WORDS)\\b\\.?|per paquet\\b)")
    private val bonusRe = Regex("(?:\\+|\\bmas\\b|\\bmes\\b)\\s*(\\d+)\\s*(?:(?:$COUNT_WORDS)\\b\\.?\\s*)?(?:de\\s+)?(?:gratis|regalo|regal)\\b")
    private val quantityRe = Regex("(?<![\\d.,/])($NUMBER)(?:\\s*(?:\\+|\\bmas\\b|\\bmes\\b)\\s*(\\d+))?\\s*([a-z]+)\\b\\.?(?:\\s*\\+\\s*($NUMBER)\\s*\\3\\b\\.?)?")
    private val halfRe = Regex("(?<![\\d.,/])1/2(?=\\s*[a-z])|\\bmedia(?=\\s+docenas?\\b)")
    private val claimAfterRe = Regex("\\s*(?:de\\s+)?(?:mg|materia grasa|grasas?|proteinas?|fibra|azucares?)\\b")
    private val afterPlusRe = Regex("\\+\\s*(?:(?:proteinas?|fibra|calcio|hierro)\\s+)?$")
    private val packRe = Regex(
        "\\b(?:(?:pack|paq|paquete|paquet|caja|caixa|estoig|lote)(?![a-z])\\.?[\\s-]*" +
            "(?:(?<container>latas?|botellas?|botellines|bri(?:c|ck|k)s?)\\s+)?" +
            "(?:de\\s+)?(\\d+)|p(\\d{1,2}))\\b(?![.,]\\d)",
    )
    private const val IMPLIED_LIMIT = 0.05
    private const val SIZE_SLACK = 0.02

    private data class Span(val start: Int, val end: Int)

    private fun quantity(amount: String, unit: String, extra: String? = null): Measure? {
        val q = measure("${amount.replace(',', '.')} $unit") ?: return null
        if (extra == null) return q
        val each = q.amount / amount.replace(',', '.').toDouble()
        return Measure(q.amount + each * extra.toInt(), q.unit)
    }

    private fun times(count: Int, q: Measure) = Measure(q.amount * count, q.unit)

    /**
     * Lee el tamaño de un texto plano y devuelve el texto sin el.
     * Multipack ("6 x 1 l", "2 botellas de 2 l") da recuento y total; un recuento solo
     * ("6 latas", "pack 6") y una cantidad sola ("200 g") se guardan aparte.
     */
    fun readSize(input: String): Pair<SizeText, String> {
        var count: Int? = null
        var total: Measure? = null
        var loose: Measure? = null
        val spans = mutableListOf<Span>()
        val text = halfRe.replace(input, "0.5")

        fun claim(m: MatchResult, keep: String? = null): Boolean {
            val start = m.range.first
            val end = m.range.last + 1
            if (spans.any { start < it.end && it.start < end }) return false
            val kept = if (keep != null) m.groups[keep] else null
            if (kept == null) {
                spans += Span(start, end)
            } else {
                spans += Span(start, kept.range.first)
                spans += Span(kept.range.last + 1, end)
            }
            return true
        }

        for (pattern in listOf(multiRe, countOfRe)) {
            for (m in pattern.findAll(text)) {
                val each = quantity(m.groupValues[3], m.groupValues[4]) ?: continue
                if (!claim(m) || total != null) continue
                count = m.groupValues[1].toInt() + (m.groups[2]?.value?.toInt() ?: 0)
                total = times(count, each)
            }
        }
        // Antes que los recuentos, para que el "4 ud" de "mas 4 ud de regalo" cuente como regalo.
        val bonus = bonusRe.findAll(text).filter { claim(it) }.sumOf { it.groupValues[1].toInt() }
        for (m in countedRe.findAll(text)) {
            if (claim(m) && count == null) count = m.groupValues[1].toInt() + (m.groups[2]?.value?.toInt() ?: 0)
        }
        if (count != null && total == null) count += bonus
        for (m in quantityRe.findAll(text)) {
            var q = quantity(m.groupValues[1], m.groupValues[3], m.groups[2]?.value) ?: continue
            if (!claim(m)) continue
            val moreText = m.groups[4]?.value
            if (moreText != null) quantity(moreText, m.groupValues[3])?.let { more -> q = Measure(q.amount + more.amount, q.unit) }
            val extra = m.groups[2]?.value
            // Un regalo nunca es mayor que su envase: "talla 6 +13 kg" es una talla.
            val banded = extra != null && extra.toInt() > m.groupValues[1].replace(',', '.').toDouble()
            val extraOrClaim = banded ||
                afterPlusRe.containsMatchIn(text.substring(0, m.range.first)) ||
                claimAfterRe.matchAt(text, m.range.last + 1) != null
            if (!extraOrClaim) loose = loose ?: q
        }
        for (m in packRe.findAll(text)) {
            // "pack lata 6" deja "lata": una lata sigue siendo distinta de una botella.
            if (claim(m, keep = "container") && count == null) count = (m.groups[2]?.value ?: m.groups[3]!!.value).toInt()
        }
        var remaining = text
        for (s in spans.sortedByDescending { it.start }) {
            remaining = remaining.substring(0, s.start) + " " + remaining.substring(s.end)
        }
        return SizeText(count, total, loose) to remaining
    }

    /** Tamaño que implican el precio y el €/unidad, con su holgura; null si el redondeo lo mueve > 5 %. */
    fun impliedTotal(l: Listing): Pair<Measure, Double>? {
        val ref = l.reference ?: return null
        val price = l.price ?: return null
        if (l.variableWeight || ref.amount <= 0 || price <= 0) return null
        val slack = 5.0 * Math.pow(10.0, (-ref.decimals - 1).toDouble()) / ref.amount
        if (slack > IMPLIED_LIMIT) return null
        return Measure(price / ref.amount, ref.unit) to slack
    }

    /** Combina lo que dicen el nombre, el texto del envase y el precio por unidad. */
    fun packSize(l: Listing, name: SizeText, pack: SizeText): PackSize? {
        var count = pack.count ?: name.count
        var total = pack.total ?: name.total
        var slack = 0.0
        val implied = impliedTotal(l)
        val loose = pack.loose ?: name.loose
        var totalIsLoose = false
        if (total == null && loose != null) {
            total = loose
            totalIsLoose = true
            // "pack 2 botellas 2 l" puede ser 2 l o 4 l: que decida el precio.
            val c = count
            if (c != null && c > 1 && implied != null && close(times(c, loose), implied.first, implied.second)) {
                total = times(c, loose)
                totalIsLoose = false
            }
        }
        if (total == null && implied != null) {
            total = implied.first
            slack = implied.second
            if (total.unit == MeasureUnit.PIECE && count != null) {
                total = Measure(count.toDouble(), MeasureUnit.PIECE)
                slack = 0.0
            }
        }
        if (total != null && total.unit == MeasureUnit.PIECE && count == null) {
            val whole = Math.rint(total.amount)
            if (whole != 0.0 && close(Measure(whole, MeasureUnit.PIECE), total, slack)) count = whole.toInt()
        }
        // "2 l" nombra una botella, asi que seis latas de 33 cl no lo son.
        if (count == null && totalIsLoose && total != null) count = 1
        if (count == null && total == null) return null
        return PackSize(count, total, slack)
    }

    fun close(a: Measure, b: Measure, slack: Double = 0.0): Boolean {
        if (a.unit != b.unit) return false
        val bigger = max(a.amount, b.amount)
        return abs(a.amount - b.amount) <= bigger * (SIZE_SLACK + slack) + 1e-12
    }

    /** true si coinciden, false si se contradicen, null si no se pueden comparar. */
    fun sameSize(a: PackSize?, b: PackSize?): Boolean? {
        if (a == null || b == null) return null
        if (a.count != null && b.count != null && a.count != b.count) return false
        if (a.total == null || b.total == null || a.total.unit != b.total.unit) {
            // seis sobres y seis sobres coinciden aunque uno diga 96 g
            return if (a.count != null && a.count == b.count && a.count != 1) true else null
        }
        return close(a.total, b.total, a.slack + b.slack)
    }

    // ---------------------------------------------------------------------- palabras

    private val stop = setOf(
        "de", "del", "la", "las", "el", "els", "los", "lo", "les", "en", "con",
        "y", "e", "i", "o", "a", "al", "als", "amb", "per", "para", "por", "d",
        "l", "un", "una", "uns", "unes", "dels", "pel", "pels", "tipo", "x",
        "mas", "gratis", "regalo", "oferta", "promocion", "ahorro", "formato",
        "anadido", "anadidos", "anadida", "anadidas", "afegit", "afegits",
    )
    private val packaging = setOf(
        "botella", "botellas", "botellin", "botellines", "ampolla", "ampolles",
        "lata", "latas", "llauna", "llaunes", "brik", "briks", "brick", "bricks",
        "bric", "brics", "carton", "cartro", "tetrabrik", "bote", "botes", "pot",
        "tarro", "vaso", "got", "cristal", "frasco", "garrafa", "envase", "bolsa",
        "bossa", "ecobolsa", "ecobossa", "caja", "caixa", "estoig", "paquete",
        "paquet", "paquets", "pack", "paq", "lote", "malla", "bandeja", "safata",
        "tarrina", "maleta", "unidad", "unidades", "ud", "uds", "u", "unitat",
        "unitats", "unid", "bocabajo", "dosificador",
    )
    private val containerOf: Map<String, String> = buildMap {
        listOf("lata", "latas", "llauna", "llaunes").forEach { put(it, "lata") }
        listOf("botella", "botellas", "botellin", "botellines", "ampolla", "ampolles").forEach { put(it, "botella") }
        listOf("brik", "briks", "brick", "bricks", "bric", "brics", "tetrabrik", "carton", "cartro").forEach { put(it, "brik") }
        listOf("frasco", "tarro", "pot").forEach { put(it, "tarro") }
        listOf("bocabajo", "dosificador").forEach { put(it, "dosificador") }
    }
    private val negations = setOf("sin", "sense")

    /** Catalan y grafias alternativas -> una palabra en castellano, antes del stem. */
    val synonyms: Map<String, String> = mapOf(
        "llet" to "leche", "oli" to "aceite", "olis" to "aceite", "cacau" to "cacao",
        "avellanes" to "avellanas", "galeta" to "galleta", "galetes" to "galletas",
        "tonyina" to "atun", "cervesa" to "cerveza", "cerveses" to "cervezas",
        "paper" to "papel", "higienic" to "higienico", "detergent" to "detergente",
        "rentaplats" to "lavavajillas", "rentavaixelles" to "lavavajillas",
        "verge" to "virgen", "aigua" to "agua", "suc" to "zumo", "taronja" to "naranja",
        "poma" to "manzana", "formatge" to "queso", "pernil" to "jamon",
        "iogurt" to "yogur", "iogurts" to "yogures", "ou" to "huevo", "ous" to "huevos",
        "arros" to "arroz", "sucre" to "azucar", "sucres" to "azucares",
        "farina" to "harina", "pa" to "pan", "mantega" to "mantequilla",
        "xocolata" to "chocolate", "torrada" to "tostada", "torrat" to "tostado",
        "sencera" to "entera", "calci" to "calcio", "suau" to "suave", "humit" to "humedo",
        "rotlle" to "rollo", "rotlles" to "rollos", "megarotlle" to "megarollo",
        "capes" to "capas", "pols" to "polvo", "liquid" to "liquido", "blanc" to "blanco",
        "negre" to "negro", "afegit" to "anadido", "afegits" to "anadidos",
        "llimona" to "limon", "maduixa" to "fresa", "pollastre" to "pollo",
        "porc" to "cerdo", "vedella" to "ternera", "tomaquet" to "tomate",
        "patates" to "patatas", "olives" to "aceitunas", "cereals" to "cereales",
        "melmelada" to "mermelada", "mel" to "miel", "batut" to "batido",
        "refresc" to "refresco", "gasosa" to "gaseosa", "vi" to "vino", "sabo" to "jabon",
        "suavitzant" to "suavizante", "lleixiu" to "lejia", "cuina" to "cocina",
        "tovallons" to "servilletas", "mocadors" to "panuelos", "peces" to "piezas",
        "uno" to "1", "dos" to "2", "tres" to "3", "cuatro" to "4", "quatre" to "4",
        "cinco" to "5", "cinc" to "5", "seis" to "6", "sis" to "6",
        "cero" to "0", "zero" to "0", "maionesa" to "mayonesa", "civada" to "avena",
        "ensucrat" to "azucarado", "ensucrada" to "azucarada", "grec" to "griego",
        "casolana" to "casera", "casola" to "casero", "desnatat" to "desnatado",
        "pressec" to "melocoton", "nabius" to "arandanos", "tofona" to "trufa",
        "rodo" to "redondo", "rodona" to "redonda", "blat" to "trigo", "pit" to "pechuga",
        "cigro" to "garbanzo", "cigrons" to "garbanzos", "molt" to "molido",
        "cuit" to "cocido", "cuits" to "cocidos", "ceba" to "cebolla", "cebes" to "cebollas",
        "spaghetti" to "espagueti", "spagueti" to "espagueti", "spaguetti" to "espagueti",
        "spaguettis" to "espaguetis", "spaghettis" to "espaguetis",
    )
    private const val SUGAR = "azucar"
    private const val ZERO = "0"
    private const val FUZZY_LENGTH = 5
    private const val FUZZY_RATIO = 0.85
    private const val DESCRIPTOR_COST = 0.15
    private const val WORD_COST = 0.7
    private const val VARIANT_COST = 1.0
    private val descriptorWords = listOf(
        "refresco", "bebida", "original", "soluble", "instantaneo", "polvo",
        "cacao", "untar", "papel", "producto", "nuevo", "edicion", "limitada",
        "receta", "estilo", "sabor",
    )

    fun containers(words: Iterable<String>): Set<String> = words.mapNotNull { containerOf[it] }.toSet()

    private val stemCache = HashMap<String, String>()

    /** Raiz tosca que comparten plural y genero. Solo para comparar, no para mostrar. */
    fun stem(input: String): String = stemCache.getOrPut(input) {
        var word = synonyms[input] ?: input
        if (word.isEmpty() || !word.all { it.isLetter() }) return@getOrPut word
        if (word.length > 4 && word.endsWith("es") && word[word.length - 3] !in "aeiou") word = word.dropLast(2)
        else if (word.length > 3 && word.endsWith("s")) word = word.dropLast(1)
        if (word.length > 4 && word.last() in "aeo") word = word.dropLast(1)
        word
    }

    private val descriptors: Set<String> = descriptorWords.map { stem(it) }.toSet()

    /**
     * Los tokens que dicen que es un producto: sin vacias, envases ni unidades, ni lo que este en [drop].
     * La negacion se pega a su palabra ("sin lactosa" -> "sin-lactos") y "zero", "0% azucar" y
     * "sin azucar" se leen "0".
     */
    fun tokens(words: Iterable<String>, drop: Iterable<String> = emptyList()): Set<String> {
        val dropped = drop.map { stem(it) }.toSet()
        val result = linkedSetOf<String>()
        var negate = false
        var previous = ""
        for (word in words) {
            if (word in negations) { negate = true; continue }
            if (word in stop || word in packaging) continue
            var token = stem(word)
            if (token in dropped) continue
            if (token == SUGAR && (negate || previous == ZERO)) token = ZERO
            else if (negate) token = "sin-$token"
            negate = false
            previous = token
            result += token
        }
        return result
    }

    /** Lo que cuesta una palabra que solo tiene uno de los dos nombres. */
    fun cost(token: String): Double {
        if (token[0].isDigit() || token.startsWith("sin-")) return VARIANT_COST
        if (token in descriptors || (token.length >= FUZZY_LENGTH && descriptors.any { ratio(token, it) >= FUZZY_RATIO })) return DESCRIPTOR_COST
        return WORD_COST
    }

    /** Parecido de dos conjuntos de tokens: 1/(1 + coste de lo que difiere). */
    fun likeness(a: Set<String>, b: Set<String>): Double {
        val pending = (b - a).sorted().toMutableList()
        var total = 0.0
        for (token in (a - b).sorted()) {
            val candidates = pending.filter { it.length >= FUZZY_LENGTH }
            if (token.length >= FUZZY_LENGTH && candidates.isNotEmpty()) {
                val best = candidates.map { ratio(token, it) to it }
                    .maxWith(compareBy<Pair<Double, String>> { it.first }.thenBy { it.second })
                if (best.first >= FUZZY_RATIO) {
                    pending.remove(best.second)
                    total += (1 - best.first) * (cost(token) + cost(best.second))
                    continue
                }
            }
            total += cost(token)
        }
        total += pending.sumOf { cost(it) }
        return 1 / (1 + total)
    }

    /** Ratcliff/Obershelp, igual que difflib.SequenceMatcher(None, a, b).ratio(). */
    fun ratio(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val b2j = HashMap<Char, MutableList<Int>>()
        b.forEachIndexed { j, c -> b2j.getOrPut(c) { mutableListOf() } += j }
        fun longest(alo: Int, ahi: Int, blo: Int, bhi: Int): Triple<Int, Int, Int> {
            var besti = alo; var bestj = blo; var bestsize = 0
            var j2len = HashMap<Int, Int>()
            for (i in alo until ahi) {
                val newj2len = HashMap<Int, Int>()
                for (j in b2j[a[i]].orEmpty()) {
                    if (j < blo) continue
                    if (j >= bhi) break
                    val k = (j2len[j - 1] ?: 0) + 1
                    newj2len[j] = k
                    if (k > bestsize) { besti = i - k + 1; bestj = j - k + 1; bestsize = k }
                }
                j2len = newj2len
            }
            return Triple(besti, bestj, bestsize)
        }
        var matches = 0
        val queue = ArrayDeque(listOf(intArrayOf(0, a.length, 0, b.length)))
        while (queue.isNotEmpty()) {
            val (alo, ahi, blo, bhi) = queue.removeFirst()
            val (i, j, k) = longest(alo, ahi, blo, bhi)
            if (k > 0) {
                matches += k
                if (alo < i && blo < j) queue += intArrayOf(alo, i, blo, j)
                if (i + k < ahi && j + k < bhi) queue += intArrayOf(i + k, ahi, j + k, bhi)
            }
        }
        return 2.0 * matches / (a.length + b.length)
    }

    // --------------------------------------------------------------------------- EAN

    /**
     * El codigo de barras como 14 digitos, o null si no identifica un producto: digito de
     * control malo, o los prefijos de peso variable que cada tienda pone a lo suyo
     * ("2" con 13 digitos, "0" o "2" con 8).
     */
    fun gtin(value: String?): String? {
        val digits = value?.trim() ?: return null
        if (digits.isEmpty() || !digits.all { it in '0'..'9' } || digits.length !in 8..14) return null
        if ((digits.length == 13 && digits[0] == '2') || (digits.length == 8 && digits[0] in "02")) return null
        val padded = digits.padStart(14, '0')
        val weighted = padded.dropLast(1).mapIndexed { i, c -> (c - '0') * (if (i % 2 == 0) 3 else 1) }.sum()
        if ((10 - weighted % 10) % 10 != padded.last() - '0') return null
        return padded
    }

    // ----------------------------------------------------------------------- señales

    fun features(l: Listing): Features {
        val (nameSize, nameRest) = readSize(plain(l.name))
        val (packSizeText, packRest) = readSize(plain(l.packSizeText ?: ""))
        val words = nameWords(nameRest)
        val brand = brandWords(l.brand)
        return Features(
            store = l.store?.let { plain(it).trim() },
            ean = gtin(l.ean),
            brand = brand,
            owners = owners(brand),
            words = words,
            containers = containers(words + nameWords(packRest)),
            size = packSize(l, nameSize, packSizeText),
            reference = l.reference,
        )
    }

    private class Brands(
        val verdict: Boolean?,
        val stripLeft: List<List<String>> = emptyList(),
        val stripRight: List<List<String>> = emptyList(),
        val shared: List<String> = emptyList(),
    )

    private fun brands(a: Features, b: Features): Brands {
        if (a.brand.isNotEmpty() && b.brand.isNotEmpty()) return bothBrands(a, b)
        if (a.brand.isEmpty() && b.brand.isEmpty()) return Brands(null)
        val (branded, other) = if (a.brand.isNotEmpty()) a to b else b to a
        val strip = listOf(branded.brand)
        if (find(other.words, branded.brand) != null) return Brands(true, strip, strip, branded.brand)
        // una marca blanca nunca esta en la estanteria de otra cadena
        if (branded.owners.isNotEmpty() && other.store != null && other.store !in branded.owners) return Brands(false, strip, strip)
        return Brands(null, strip, strip)
    }

    private fun bothBrands(a: Features, b: Features): Brands {
        val x = a.brand
        val y = b.brand
        if (x.joinToString("") == y.joinToString("")) return Brands(true, listOf(x), listOf(y), x)
        val (short, long) = if (x.size <= y.size) x to y else y to x
        val stripLeft: List<List<String>>
        val stripRight: List<List<String>>
        if (long.take(short.size) == short) {
            // "coca cola zero" amplia "coca cola": lo de mas nombra una variante y se compara
            stripLeft = listOf(short); stripRight = listOf(short)
        } else if (long.takeLast(short.size) == short) {
            // "central lechera asturiana" acaba en "asturiana": lo de mas es el fabricante
            stripLeft = listOf(x, short); stripRight = listOf(y, short)
        } else {
            return Brands(false, listOf(x), listOf(y))
        }
        // "carrefour bio" no es "bio": una marca blanca solo se amplia a si misma
        if (a.owners != b.owners) return Brands(false, stripLeft, stripRight)
        return Brands(true, stripLeft, stripRight, short)
    }

    private fun verdict(signal: String, v: Boolean?): String = when (v) {
        null -> "$signal unknown"
        true -> signal
        false -> "$signal differs"
    }

    /** Redondeo como round(x, 2) de Python sobre el valor binario exacto. */
    private fun round2(x: Double): Double = BigDecimal(x).setScale(2, RoundingMode.HALF_EVEN).toDouble()

    /** ¿Mismo producto (misma marca, producto y envase)? Umbral [SAME_THRESHOLD]. */
    fun scoreSame(a: Features, b: Features): MatchScore {
        if (a.ean != null && a.ean == b.ean) return MatchScore(1.0, listOf("ean"))
        val br = brands(a, b)
        val name = likeness(tokens(remove(a.words, br.stripLeft), br.shared), tokens(remove(b.words, br.stripRight), br.shared))
        val size = sameSize(a.size, b.size)
        var score = name / 2 + (if (br.verdict == true) 0.25 else 0.0) + (if (size == true) 0.25 else 0.0)
        val reasons = mutableListOf<String>()
        var contradicted = br.verdict == false || size == false
        if (a.ean != null && b.ean != null) { reasons += "ean differs"; contradicted = true }
        reasons += verdict("brand", br.verdict)
        reasons += verdict("size", size)
        reasons += "name ${"%.2f".format(Locale.ROOT, name)}"
        if (a.containers.isNotEmpty() && b.containers.isNotEmpty() && a.containers.intersect(b.containers).isEmpty()) {
            reasons += "container differs"; contradicted = true
        }
        if (contradicted) score *= CONTRADICTED
        return MatchScore(round2(score), reasons)
    }

    /** ¿Alternativa comparable (mismo tipo de producto, cualquier marca)? Umbral [ALTERNATIVE_THRESHOLD]. */
    fun scoreAlternative(a: Features, b: Features): MatchScore {
        val brandList = listOf(a.brand, b.brand).filter { it.isNotEmpty() }
        val at = tokens(remove(a.words, brandList))
        val bt = tokens(remove(b.words, brandList))
        val reasons = mutableListOf<String>()
        var kind: Double
        if (at.isNotEmpty() && bt.isNotEmpty()) {
            kind = likeness(at, bt); reasons += "kind ${"%.2f".format(Locale.ROOT, kind)}"
        } else {
            kind = 0.0; reasons += "kind unknown"
        }
        if (a.reference != null && b.reference != null) {
            if (a.reference.unit == b.reference.unit) reasons += "unit ${a.reference.unit}"
            else { reasons += "unit differs"; kind *= CONTRADICTED }
        }
        if (b.owners.isNotEmpty()) reasons += "store brand"
        return MatchScore(round2(kind), reasons)
    }

    fun scoreSame(a: Listing, b: Listing): MatchScore = scoreSame(features(a), features(b))
}
