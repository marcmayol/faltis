package com.marcmayol.faltis.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.math.roundToLong

/** Un producto leido de la tienda online de una cadena: precio orientativo de la cadena, no de tu tienda. */
data class WebProduct(
    val chainId: String,
    val webId: String,
    val name: String,
    val brand: String?,
    val priceCents: Long,
    /** null con [netUnit] = a granel: [priceCents] es por kg o l. */
    val netQty: Double?,
    val netUnit: QtyUnit?,
)

/**
 * Capa D · precios de la web. Solo las cadenas que publican su catalogo entero con precio:
 * Mercadona (por almacen, y en la practica un precio nacional) y Aldi (por region: peninsula,
 * Baleares, Canarias). Lidl queda fuera: su web solo pone precio a lo que esta en oferta.
 * Los formatos de respuesta y los indices los documenta supermercapy (MIT, ver NOTICE).
 */
object WebCatalog {
    val chains: Set<String> = setOf("mercadona", "aldi")

    /** El aviso que acompaña a todo precio web. */
    const val DISCLAIMER = "Los precios web son orientativos: en tu tienda pueden ser distintos por ser franquicia, " +
        "por la región (Baleares, Canarias…), por ofertas locales o por cambios recientes. El precio de tus tickets siempre manda."

    private val json = Json { ignoreUnknownKeys = true }

    fun isPostalCode(text: String): Boolean = Regex("\\d{5}").matches(text) && text.take(2).toInt() in 1..52

    /** Region de precios de Aldi por la provincia del codigo postal. */
    fun aldiRegion(postalCode: String): String = when (postalCode.take(2)) {
        "07" -> "bal"
        "35", "38" -> "can"
        else -> "pen"
    }

    fun parse(chainId: String, body: String): List<WebProduct> = when (chainId) {
        "mercadona" -> parseMercadona(body)
        "aldi" -> parseAldi(body)
        else -> emptyList()
    }

    fun parseMercadona(body: String): List<WebProduct> = hits(body).mapNotNull { h ->
        val p = h["price_instructions"].obj() ?: return@mapNotNull null
        val id = h["id"].text() ?: return@mapNotNull null
        val name = h["display_name"].text() ?: return@mapNotNull null
        val unit = when (p["size_format"].text()?.lowercase()) {
            "kg" -> QtyUnit.KG
            "l" -> QtyUnit.L
            "ud" -> QtyUnit.UD
            else -> null
        }
        val packaging = h["packaging"].text()
        // Fruta y verdura "aprox. 1,1 kg": se paga al peso, asi que vale su precio por kg.
        val bulk = p["approx_size"].bool() == true && unit == QtyUnit.KG
        val euros = (if (bulk) p["bulk_price"] else p["unit_price"]).num() ?: return@mapNotNull null
        WebProduct(
            chainId = "mercadona", webId = id,
            name = listOfNotNull(name, packaging?.takeIf { it.isNotBlank() }?.lowercase()).joinToString(" · "),
            brand = h["brand"].text(),
            priceCents = (euros * 100).roundToLong(),
            netQty = if (bulk) null else p["unit_size"].num(),
            netUnit = unit,
        )
    }

    fun parseAldi(body: String): List<WebProduct> = hits(body).mapNotNull { h ->
        val id = h["objectID"].text() ?: return@mapNotNull null
        val name = h["name"].text() ?: return@mapNotNull null
        // Sin precio en la web (frescos al peso, por ejemplo): no hay nada que guardar.
        val euros = h["currentPrice"].obj()?.get("priceValue").num() ?: return@mapNotNull null
        val salesUnit = h["salesUnit"].text()
        val format = salesUnit?.let { Packs.fromText(it) }
        WebProduct(
            chainId = "aldi", webId = id,
            name = listOfNotNull(name, salesUnit?.removeSuffix(" unidad")).joinToString(" · "),
            brand = h["brandName"].text()?.replace("®", "")?.trim(),
            priceCents = (euros * 100).roundToLong(),
            netQty = format?.first, netUnit = format?.second,
        )
    }

    private fun hits(body: String): List<JsonObject> =
        runCatching { json.parseToJsonElement(body).jsonObject["hits"]?.jsonArray }.getOrNull()
            ?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.prim(): JsonPrimitive? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }
    private fun JsonElement?.text(): String? = prim()?.content?.takeIf { it.isNotBlank() }
    private fun JsonElement?.num(): Double? = prim()?.let { it.doubleOrNull ?: it.content.replace(',', '.').toDoubleOrNull() }
    private fun JsonElement?.bool(): Boolean? = prim()?.booleanOrNull
}
