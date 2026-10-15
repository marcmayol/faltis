package com.marcmayol.faltis.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlin.math.roundToInt

/** Lo que dice la etiqueta de un producto, segun Open Food Facts (ODbL). Valores por 100 g o 100 ml. */
data class NutritionFacts(
    val ean: String,
    val name: String,
    val brand: String?,
    val quantity: String?,
    /** "100 g" o "100 ml". */
    val per: String,
    val kcal: Double?,
    val proteins: Double?,
    val carbs: Double?,
    val sugars: Double?,
    val fat: Double?,
    val saturatedFat: Double?,
    val fiber: Double?,
    val salt: Double?,
    /** Alergenos declarados, ya en español ("Leche", "Gluten"...). */
    val allergens: List<String>,
    /** "Puede contener": trazas. */
    val traces: List<String>,
    val ingredients: String?,
    /** a..e, o null si no tiene. */
    val nutriScore: String?,
    /** 1..4 (grado de procesado NOVA). */
    val nova: Int?,
    val imageUrl: String? = null,
) {
    val hasMacros: Boolean get() = proteins != null && carbs != null && fat != null

    /** Reparto de las kcal entre proteinas, hidratos y grasas (4/4/9 kcal por gramo), en %. Suma 100. */
    fun macroShares(): Triple<Int, Int, Int>? {
        if (!hasMacros) return null
        val p = proteins!! * 4; val c = carbs!! * 4; val f = fat!! * 9
        val total = p + c + f
        if (total <= 0) return null
        val pp = (p / total * 100).roundToInt()
        val cp = (c / total * 100).roundToInt()
        return Triple(pp, cp, 100 - pp - cp)
    }

    /** % de la energia diaria de referencia (2.000 kcal) que aporta la porcion de referencia. */
    fun dailyShare(): Int? = kcal?.let { (it / DAILY_KCAL * 100).roundToInt() }

    companion object {
        const val DAILY_KCAL = 2000.0
    }
}

/** Un candidato de la busqueda: solo lo justo para elegir. */
data class FoodHit(val ean: String, val name: String, val brands: List<String>, val quantity: String?)

/**
 * Open Food Facts: base de datos abierta de alimentos (ODbL). Sin clave; pide un User-Agent
 * que identifique la app. Aqui solo se leen y se interpretan sus respuestas; las peticiones las hace la app.
 */
object OpenFoodFacts {
    const val USER_AGENT = "Faltis (https://marcmayol.com/faltis)"
    const val ATTRIBUTION = "Datos de Open Food Facts (ODbL), los aporta su comunidad: comprueba siempre la etiqueta."
    private const val FIELDS = "code,product_name,product_name_es,brands,quantity,nutriments,nutrition_data_per," +
        "allergens_tags,traces_tags,ingredients_text_es,ingredients_text,nutriscore_grade,nova_group,image_front_small_url,image_front_url"

    fun productUrl(ean: String) = "https://world.openfoodfacts.org/api/v2/product/$ean.json?lc=es&fields=$FIELDS"

    /** Busqueda por nombre en productos vendidos en España; con marca, solo de esa marca. */
    fun searchUrl(name: String, brand: String?): String {
        val words = ProductMatcher.nameWords(ProductMatcher.plain(name)).filter { it.length > 1 && !it[0].isDigit() }
        val brandWords = ProductMatcher.brandWords(brand).toSet()
        val terms = words.filter { it !in brandWords }.take(6).joinToString(" ")
        val q = buildString {
            append(terms)
            if (!brand.isNullOrBlank()) append(" brands:\"").append(brand.trim().replace("\"", "")).append('"')
            append(" countries_tags:\"en:spain\"")
        }
        return "https://search.openfoodfacts.org/search?langs=es&page_size=12&fields=code,product_name,brands,quantity&q=" +
            java.net.URLEncoder.encode(q, "UTF-8")
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** Respuesta de [productUrl]; null si no existe o no trae nada util. */
    fun parseProduct(body: String): NutritionFacts? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val p = root["product"] as? JsonObject ?: return null
        val ean = root["code"].text() ?: p["code"].text() ?: return null
        val n = p["nutriments"] as? JsonObject ?: JsonObject(emptyMap())
        fun nut(key: String): Double? = n["${key}_100g"].num()
        val kcal = nut("energy-kcal") ?: nut("energy")?.let { it / 4.184 }
        val facts = NutritionFacts(
            ean = ean,
            name = p["product_name_es"].text() ?: p["product_name"].text() ?: "",
            brand = p["brands"].text()?.split(',')?.firstOrNull()?.trim(),
            quantity = p["quantity"].text(),
            per = if (p["nutrition_data_per"].text()?.contains("ml") == true) "100 ml" else "100 g",
            kcal = kcal, proteins = nut("proteins"), carbs = nut("carbohydrates"), sugars = nut("sugars"),
            fat = nut("fat"), saturatedFat = nut("saturated-fat"), fiber = nut("fiber"), salt = nut("salt"),
            allergens = labels(p["allergens_tags"]),
            traces = labels(p["traces_tags"]),
            ingredients = (p["ingredients_text_es"].text() ?: p["ingredients_text"].text())?.replace(Regex("\\s+"), " ")?.trim(),
            nutriScore = p["nutriscore_grade"].text()?.lowercase()?.takeIf { it.length == 1 && it[0] in 'a'..'e' },
            nova = (p["nova_group"] as? JsonPrimitive)?.intOrNull?.takeIf { it in 1..4 },
            imageUrl = (p["image_front_url"].text() ?: p["image_front_small_url"].text())?.takeIf { it.startsWith("https://") },
        )
        return facts.takeIf { it.kcal != null || it.hasMacros || it.allergens.isNotEmpty() || it.ingredients != null }
    }

    /** Respuesta de [searchUrl]. */
    fun parseSearch(body: String): List<FoodHit> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val hits = root["hits"] as? JsonArray ?: return emptyList()
        return hits.mapNotNull { h ->
            val o = h as? JsonObject ?: return@mapNotNull null
            val ean = o["code"].text()?.takeIf { it.all(Char::isDigit) && it.length >= 8 } ?: return@mapNotNull null
            val name = o["product_name"].text() ?: return@mapNotNull null
            val brands = when (val b = o["brands"]) {
                is JsonArray -> b.mapNotNull { it.text() }
                else -> b.text()?.split(',').orEmpty()
            }.map { it.trim() }.filter { it.isNotEmpty() }
            FoodHit(ean, name, brands, o["quantity"].text())
        }
    }

    /**
     * El candidato que mas se parece al producto, o null si ninguno se parece lo bastante.
     * Se compara el nombre sin la marca; si el producto tiene marca, el candidato debe compartirla.
     */
    fun bestHit(name: String, brand: String?, hits: List<FoodHit>): Pair<FoodHit, Double>? {
        val brandWords = ProductMatcher.brandWords(brand)
        val mine = ProductMatcher.tokens(ProductMatcher.nameWords(ProductMatcher.plain(name)), brandWords)
        if (mine.isEmpty()) return null
        return hits.mapNotNull { h ->
            if (brandWords.isNotEmpty() && h.brands.none { b -> ProductMatcher.brandWords(b) == brandWords }) return@mapNotNull null
            val theirs = ProductMatcher.tokens(ProductMatcher.nameWords(ProductMatcher.plain(h.name)), h.brands.flatMap { ProductMatcher.brandWords(it) })
            if (theirs.isEmpty()) null else h to ProductMatcher.likeness(mine, theirs)
        }.maxByOrNull { it.second }?.takeIf { it.second >= MIN_LIKENESS }
    }

    /** La marca con la que buscar: la del producto o, si es marca blanca sin marca escrita, la principal de su cadena. */
    fun searchBrand(brand: String?, chainId: String, isPrivateLabel: Boolean): String? =
        brand?.takeIf { it.isNotBlank() } ?: if (isPrivateLabel) ProductMatcher.storeBrands[chainId]?.firstOrNull() else null

    const val MIN_LIKENESS = 0.4

    /** Los 14 alergenos de declaracion obligatoria en la UE, con su etiqueta de Open Food Facts. */
    private val allergenNames = mapOf(
        "en:gluten" to "Gluten", "en:crustaceans" to "Crustáceos", "en:eggs" to "Huevo", "en:fish" to "Pescado",
        "en:peanuts" to "Cacahuete", "en:soybeans" to "Soja", "en:milk" to "Leche", "en:nuts" to "Frutos de cáscara",
        "en:celery" to "Apio", "en:mustard" to "Mostaza", "en:sesame-seeds" to "Sésamo",
        "en:sulphur-dioxide-and-sulphites" to "Sulfitos", "en:lupin" to "Altramuces", "en:molluscs" to "Moluscos",
    )

    private fun labels(e: JsonElement?): List<String> = (e as? JsonArray).orEmpty()
        .mapNotNull { it.text() }
        .map { tag -> allergenNames[tag] ?: tag.substringAfter(':').replace('-', ' ').replaceFirstChar { it.uppercase() } }
        .distinct()

    private fun JsonElement?.text(): String? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.content?.trim()?.takeIf { it.isNotEmpty() }
    private fun JsonElement?.num(): Double? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }?.let { it.doubleOrNull ?: it.content.replace(',', '.').toDoubleOrNull() }
}
