package com.marcmayol.faltis.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
    /** Precio de oferta (Lidl solo pone precio a lo que esta en oferta). */
    val offer: Boolean = false,
    /** Foto del producto en la web de la cadena. */
    val imageUrl: String? = null,
)

/**
 * Capa D · precios de la web: lo que cada cadena publica en su tienda online, leido con la misma
 * busqueda que usa su web. Cada cadena tiene su zona de precios (almacen, tienda o region).
 * Varios formatos de respuesta los documenta supermercapy (MIT, ver NOTICE).
 */
object WebCatalog {
    val chains: Set<String> = setOf(
        "mercadona", "aldi", "consum", "dia", "carrefour", "condis",
        "eroski", "caprabo", "lidl", "alcampo", "bonpreu",
        "bonarea", "ahorramas", "gadis", "hiperdino", "bm", "masymas", "froiz",
    )

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

    /** [zone] solo la usa Lidl (su region). */
    fun parse(chainId: String, body: String, zone: String = ""): List<WebProduct> = when (chainId) {
        "mercadona" -> parseMercadona(body)
        "aldi" -> parseAldi(body)
        "consum", "bm", "masymas" -> parseAktios(chainId, body)
        "dia" -> parseDia(body)
        "carrefour" -> parseCarrefour(body)
        "condis" -> parseCondis(body)
        "eroski", "caprabo" -> parseTapestry(chainId, body)
        "lidl" -> parseLidl(body, zone)
        "alcampo", "bonpreu" -> parseOcado(chainId, body)
        "bonarea" -> parseBonarea(body)
        "ahorramas" -> parseAhorramas(body)
        "gadis" -> parseGadis(body)
        "hiperdino" -> parseHiperdino(body)
        "froiz" -> parseFroiz(body)
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
            imageUrl = https(h["thumbnail"].text()),
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
            // La foto "primary" de Scene7; sin tamano sale a ~1500 px, se pide a 300.
            imageUrl = https((h["assets"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it["type"].text() == "primary" }?.get("url").text()?.let { if ('?' in it) it else "$it?wid=300&hei=300" }),
        )
    }

    /** Consum, BM y masymas: la misma plataforma (Aktios TOL). */
    fun parseAktios(chain: String, body: String): List<WebProduct> = aktiosProducts(body).mapNotNull { h ->
        val data = h["productData"].obj() ?: return@mapNotNull null
        if (data["sponsored"].bool() == true) return@mapNotNull null
        val prices = h["priceData"].obj()?.get("prices") as? JsonArray ?: return@mapNotNull null
        val byId = prices.mapNotNull { it as? JsonObject }.associateBy { it["id"].text() }
        val offer = byId["OFFER_PRICE"]
        val value = (offer ?: byId["PRICE"])?.get("value").obj() ?: return@mapNotNull null
        // Consum llama "centAmount" a un importe en euros.
        val euros = value["centAmount"].num() ?: return@mapNotNull null
        val name = data["description"].text() ?: data["name"].text() ?: return@mapNotNull null
        val brand = data["brand"].obj()?.get("name").text()
        val perUnit = h["priceData"].obj()?.get("unitPriceUnitType").text()
        // Consum: su "imageURL" a veces da 404 y las de "media" no. masymas las da a 135 px: se piden a 300.
        val image = ((h["media"] as? JsonArray)?.mapNotNull { it as? JsonObject }?.filter { it["type"].text() == "P" }
            ?.minByOrNull { it["order"].num() ?: 0.0 }?.get("url").text() ?: data["imageURL"].text())
            ?.replace("/135x135/", "/300x300/")
        // BM marca "kg." cuando el precio ya es por kilo.
        if (h["priceData"].obj()?.get("priceUnitType").text()?.lowercase()?.startsWith("kg") == true) {
            return@mapNotNull product(chain, h["code"].text(), name, brand, euros, null, QtyUnit.KG, offer != null, image)
        }
        // productType 2 y 3: al peso; el importe de la pieza es aproximado, vale el precio por kg.
        val bulkUnit = perUnit?.let { unitOf(it) }
        if ((h["productType"].num() ?: 1.0) > 1.0 && bulkUnit != null) {
            val perKg = value["centUnitAmount"].num() ?: return@mapNotNull null
            return@mapNotNull product(chain, h["code"].text(), name, brand, perKg, null, bulkUnit, offer != null, image)
        }
        val net = Packs.fromText(name) ?: fromUnitPrice(euros, value["centUnitAmount"].num(), perUnit)
        product(chain, h["code"].text(), name, brand, euros, net?.first, net?.second, offer != null, image)
    }

    fun parseDia(body: String): List<WebProduct> = array(body, "search_items").mapNotNull { h ->
        val p = h["prices"].obj() ?: return@mapNotNull null
        val euros = p["price"].num() ?: return@mapNotNull null
        val name = h["display_name"].text() ?: return@mapNotNull null
        val net = Packs.fromText(name) ?: fromUnitPrice(euros, p["price_per_unit"].num(), p["measure_unit"].text())
        product("dia", h["sku_id"].text() ?: h["object_id"].text(), name, h["brand"].text(), euros, net?.first, net?.second, p["is_promo_price"].bool() == true,
            h["image"].text()?.let { if (it.startsWith("/")) "https://www.dia.es$it" else it })
    }

    fun parseCarrefour(body: String): List<WebProduct> = catalog(body).mapNotNull { h ->
        val euros = h["active_price"].num() ?: return@mapNotNull null
        val name = h["display_name"].text()?.trim()?.removeSuffix(".") ?: return@mapNotNull null
        val list = h["list_price"].num()
        val net = if (h["variable_weight"].bool() == true) {
            // Al peso: el factor no vale; se guarda el peso medio de la pieza.
            h["average_weight"].num()?.let { it to QtyUnit.G }
        } else {
            Packs.fromText(name) ?: h["unit_conversion_factor"].num()?.let { f -> unitOf(h["measure_unit"].text() ?: "")?.let { f to it } }
        }
        product("carrefour", h["product_id"].text(), name, h["brand"].text(), euros, net?.first, net?.second, list != null && list > euros + 0.001,
            h["image_path"].obj()?.values?.firstNotNullOfOrNull { it.text() } ?: h["image_for_play_service"].text())
    }

    fun parseCondis(body: String): List<WebProduct> = catalog(body).mapNotNull { h ->
        if (h["state"].text()?.let { it != "ACTIVE" } == true) return@mapNotNull null
        val price = h["price"].obj() ?: return@mapNotNull null
        val euros = price["current"].num() ?: return@mapNotNull null
        val regular = price["regular"].num()
        // Condis escribe los nombres en mayusculas: se dejan como una frase.
        val name = h["description"].text()?.let { d -> if (d == d.uppercase()) d.lowercase().replaceFirstChar { it.uppercase() } else d }
            ?: return@mapNotNull null
        // "pum": "1,26€/Litro". Va primero: el nombre dice cosas como "6 UNIDADES 1000G APROX." (1 kg, no 6).
        val net = h["pum"].text()?.split('/')?.let { parts ->
            fromUnitPrice(euros, parts[0].filter { it.isDigit() || it == ',' || it == '.' }.replace(',', '.').toDoubleOrNull(), parts.getOrNull(1))
        } ?: Packs.fromText(name)
        // "images" trae "/images/catalog/large/704049.jpg", que ya no se sirve: la web usa su CDN con el mismo archivo.
        val file = ((h["images"] as? JsonArray)?.firstOrNull().text() ?: (h["altImages"] as? JsonArray)?.firstOrNull().text())?.substringAfterLast('/')
        product("condis", h["id"].text(), name, h["brand"].text(), euros, net?.first, net?.second, regular != null && regular > euros + 0.001,
            file?.let { "https://cdn.condis.es/fit-in/300x300/es/products/$it" })
    }

    /**
     * Eroski y Caprabo (la misma plataforma): la busqueda devuelve las fichas en HTML dentro de un JSON.
     * Sin sesion los precios son los de su tienda online (Bilbao / Barcelona).
     */
    fun parseTapestry(chain: String, body: String): List<WebProduct> {
        val html = runCatching { json.parseToJsonElement(body).jsonObject["content"].text() }.getOrNull() ?: return emptyList()
        return html.split(Regex("<div class='[^']*product-item-lineal")).drop(1).mapNotNull { tile ->
            if ("criteoItem" in tile.take(300)) return@mapNotNull null
            val metrics = Regex("product-title-link' data-metrics='([^']+)'").find(tile)?.groupValues?.get(1) ?: return@mapNotNull null
            val item = runCatching {
                json.parseToJsonElement(metrics).jsonObject["ecommerce"]?.jsonObject?.get("items")?.jsonArray?.firstOrNull()?.jsonObject
            }.getOrNull() ?: return@mapNotNull null
            val name = item["item_name"].text() ?: return@mapNotNull null
            // El texto del precio, no el numero del JSON: "1,10" alli sale como 1.1.
            val euros = Regex("price-offer-now'>\\s*([\\d.,]+)").find(tile)?.groupValues?.get(1)?.let(::euro)
                ?: item["price"].num() ?: return@mapNotNull null
            val offer = "price-offer-before" in tile
            // La foto de 200 px; la web la escribe con doble barra ("...es//images/...").
            val image = Regex("src='(https://[^']+)'[^>]*class='product-img'").find(tile)?.groupValues?.get(1)?.replace(Regex("(?<!:)//+"), "/")
            if ("weight-icon" in tile || "al peso" in Text.normalize(name)) {
                return@mapNotNull product(chain, item["item_id"].text(), name, item["item_brand"].text(), euros, null, QtyUnit.KG, offer, image)
            }
            // "pack 4x100 g", "brik 1 litro": el formato va al final, tras la ultima coma.
            val net = Packs.fromText(name.substringAfterLast(", ")) ?: Packs.fromText(name)
            product(chain, item["item_id"].text(), name, item["item_brand"].text(), euros, net?.first, net?.second, offer, image)
        }
    }

    /**
     * Lidl: solo publica precio de lo que esta en oferta esa semana. [region] es la de Lidl
     * ("48" Mallorca…); vacia, la region de precios por defecto.
     */
    fun parseLidl(body: String, region: String = ""): List<WebProduct> = array(body, "items").mapNotNull { item ->
        val d = item["gridbox"].obj()?.get("data").obj() ?: return@mapNotNull null
        val id = d["erpNumber"].text() ?: return@mapNotNull null
        // 10…: bazar. Solo interesa la alimentacion de tienda (11…).
        if (!id.startsWith("11")) return@mapNotNull null
        val prices = d["regionsPrices"].obj() ?: return@mapNotNull null
        val band = if (region.isBlank()) prices.keys.firstOrNull()
        else d["regionsV2"].obj()?.get(region).obj()?.get("regionPriceId").text() ?: return@mapNotNull null
        val bandPrices = prices[band ?: return@mapNotNull null].obj() ?: return@mapNotNull null
        val p = (bandPrices["currentPrice"].obj() ?: bandPrices["currentLidlPlusPrice"].obj())?.get("price").obj() ?: return@mapNotNull null
        val euros = p["price"].num() ?: return@mapNotNull null
        val title = d["title"].text() ?: return@mapNotNull null
        val packaging = p["packaging"].obj()?.get("text").text()
        val net = packaging?.let { Packs.fromText(it) } ?: Packs.fromText(title)
        val brand = d["brand"].obj()?.get("name").text()?.replace("®", "")?.takeIf { it.trim() != "-" }
        product("lidl", id, listOfNotNull(title, packaging).joinToString(" · "), brand, euros, net?.first, net?.second, offer = true, image = d["image"].text())
    }

    /** Alcampo y Bonpreu (plataforma Ocado). */
    fun parseOcado(chain: String, body: String): List<WebProduct> {
        val groups = runCatching { json.parseToJsonElement(body).jsonObject["productGroups"]?.jsonArray }.getOrNull() ?: return emptyList()
        return groups.mapNotNull { it as? JsonObject }
            // "featured": patrocinados que no tienen que ver con la busqueda.
            .filter { it["type"].text() != "featured" }
            .flatMap { g -> (g["decoratedProducts"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject } }
            .distinctBy { it["retailerProductId"].text() }
            .mapNotNull { h ->
                val promo = h["promoPrice"].obj()?.get("amount").num()
                val euros = promo ?: h["price"].obj()?.get("amount").num() ?: return@mapNotNull null
                val name = h["name"].text() ?: return@mapNotNull null
                val image = h["image"].obj()?.get("src").text()
                val unitPrice = h["unitPrice"].obj()
                val perUnit = unitPrice?.get("price").obj()?.get("amount").num()
                val unitName = unitPrice?.get("unitName").text()
                val refUnit = when (unitName) {
                    "PER_LITRE" -> "l"
                    "PER_1KG" -> "kg"
                    "EACH" -> "ud"
                    else -> null
                }
                // Al peso: el precio es el de una pieza tipica; vale el precio por kg.
                if (h["type"].text() == "CATCHWEIGHT" && perUnit != null && refUnit == "kg") {
                    return@mapNotNull product(chain, h["retailerProductId"].text(), name, h["brand"].text(), perUnit, null, QtyUnit.KG, promo != null, image)
                }
                val net = h["packSizeDescription"].text()?.let { Packs.fromText(it) } ?: Packs.fromText(name)
                    ?: fromUnitPrice(euros, perUnit, refUnit)
                product(chain, h["retailerProductId"].text(), name, h["brand"].text(), euros, net?.first, net?.second, promo != null, image)
            }
    }

    /** Region de Lidl por codigo postal. Solo varia en las islas; vacia = la de por defecto. */
    fun lidlRegion(postalCode: String): String = when {
        postalCode.startsWith("077") -> "58"
        postalCode.startsWith("078") -> "59"
        postalCode.startsWith("07") -> "48"
        else -> ""
    }

    private fun euro(text: String): Double? = text.trim().replace(".", "").replace(',', '.').toDoubleOrNull()

    fun parseBonarea(body: String): List<WebProduct> = array(body, "articles").mapNotNull { h ->
        val euros = h["priceToPay"].num() ?: return@mapNotNull null
        val name = h["description"].text() ?: return@mapNotNull null
        val measure = h["measurementUnit"].text()
        // "aprox. 605 g": el importe es el de esa bandeja, con su peso aproximado.
        val net = measure?.let { Packs.fromText(it.replace("aprox.", "")) } ?: Packs.fromText(name)
        product("bonarea", h["identifier"].text(), listOfNotNull(name, measure).joinToString(" · "), null, euros, net?.first, net?.second, (h["discount"].num() ?: 0.0) > 0,
            // El PNG original pasa de 1700 px; su servidor lo reduce con width y height.
            (h["image"] as? JsonArray)?.firstOrNull().text()?.let { "https://images.bonarea.com/$it?width=300&height=300" })
    }

    /** Ahorramas: rejilla HTML de su buscador. */
    fun parseAhorramas(body: String): List<WebProduct> = body.split("<div class=\"product\" data-pid=").drop(1).mapNotNull { tile ->
        val id = Regex("^\"([^\"]+)\"").find(tile)?.groupValues?.get(1)
        val name = Regex("product-name-gtm\"[^>]*>([^<]+)<").find(tile)?.groupValues?.get(1)?.let(::unescape) ?: return@mapNotNull null
        val brand = Regex("data-brand=\"([^\"]*)\"").find(tile)?.groupValues?.get(1)?.let(::unescape)
        val euros = Regex("class=\"value\" content=\"([\\d.]+)\"").find(tile)?.groupValues?.get(1)?.toDoubleOrNull() ?: return@mapNotNull null
        val offer = "strike-through" in tile
        val image = Regex("class=\"tile-image\"\\s+src=\"([^\"]+)\"").find(tile)?.groupValues?.get(1)?.replace("&amp;", "&")
        // El precio por unidad bueno; el de "1.0 Kg" va dentro de un comentario.
        val perUnit = Regex("<span class=\"unit-price-per-unit[^\"]*\">\\s*([\\d.,]+)\\s*&euro;/\\s*([A-Za-z]+)").find(tile.replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), ""))
        if ("data-hasunitweight=\"true\"" in tile) {
            return@mapNotNull product("ahorramas", id, name, brand, euros, null, QtyUnit.KG, offer, image)
        }
        val net = Packs.fromText(name) ?: perUnit?.let { fromUnitPrice(euros, euro(it.groupValues[1]), it.groupValues[2]) }
        product("ahorramas", id, name, brand, euros, net?.first, net?.second, offer, image)
    }

    /** Gadis: los productos van en el __NEXT_DATA__ de la pagina de resultados. */
    fun parseGadis(body: String): List<WebProduct> {
        val data = Regex("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1) ?: return emptyList()
        val root = runCatching { json.parseToJsonElement(data) }.getOrNull() ?: return emptyList()
        fun find(e: JsonElement): JsonObject? = when (e) {
            is JsonObject -> (e["firstProducts"] as? JsonObject) ?: e.values.firstNotNullOfOrNull { find(it) }
            is JsonArray -> e.firstNotNullOfOrNull { find(it) }
            else -> null
        }
        val elements = find(root)?.get("elements") as? JsonArray ?: return emptyList()
        return elements.mapNotNull { it as? JsonObject }.mapNotNull { h ->
            val name = (h["commercial_description"] as? JsonArray)?.mapNotNull { it as? JsonObject }
                ?.firstOrNull { it["language"].text() == "ES" }?.get("value").text() ?: return@mapNotNull null
            val euros = h["price"].num() ?: return@mapNotNull null
            val image = h["image"].obj()?.let { it["image_thumbnails"].text() ?: it["image"].text() }
            val suffix = (h["price_kilo_litre_suffix"] as? JsonArray)?.firstOrNull()?.obj()?.get("value").text()
            if (h["weight"].text() == "P") {
                return@mapNotNull product("gadis", h["product_code"].text(), name, h["brand_description"].text(), euros, null, QtyUnit.KG, false, image)
            }
            val net = Packs.fromText(name) ?: fromUnitPrice(euros, h["price_kilo_litre"].num(), suffix?.substringAfterLast(' '))
            product("gadis", h["product_code"].text(), name, h["brand_description"].text(), euros, net?.first, net?.second, (h["offers"] as? JsonArray)?.isNotEmpty() == true, image)
        }
    }

    /** HiperDino: lista HTML de su buscador. */
    fun parseHiperdino(body: String): List<WebProduct> = body.split("<li class=\"product-list-item").drop(1).mapNotNull { tile ->
        val id = Regex("data-id=\"(\\d+)\"").find(tile)?.groupValues?.get(1)
        val brand = Regex("\"brand\":\\{\"id\":\"[^\"]*\",\"label\":\"([^\"]+)\"").find(tile)?.groupValues?.get(1)
        val name = Regex("description__text name\">\\s*([^<]+?)\\s*<").find(tile)?.groupValues?.get(1)?.let(::unescape) ?: return@mapNotNull null
        val price = Regex("price--final[^>]*>\\s*<div class=\"price__text[^>]*>\\s*([\\d.,]+)\\s*<span>[^<]*</span>\\s*(<span>\\s*/\\s*kg)?", RegexOption.IGNORE_CASE).find(tile) ?: return@mapNotNull null
        val euros = euro(price.groupValues[1]) ?: return@mapNotNull null
        val offer = "price--previous" in tile
        // La foto va en data-src (carga diferida); el src es un "pendiente de foto".
        val image = Regex("data-src=\"(https://cdn\\.hiperdino\\.es/catalog/product/[^\"]+)\"").find(tile)?.groupValues?.get(1)
        if (price.groupValues[2].isNotBlank()) return@mapNotNull product("hiperdino", id, name, brand, euros, null, QtyUnit.KG, offer, image)
        val net = Packs.fromText(name)
        product("hiperdino", id, name, brand, euros, net?.first, net?.second, offer, image)
    }

    fun parseFroiz(body: String): List<WebProduct> = array(body, "products").mapNotNull { h ->
        val euros = h["order_price"].num() ?: h["base_price"].num() ?: return@mapNotNull null
        val name = h["name"].text() ?: return@mapNotNull null
        val base = h["base_price"].num()
        val offer = h["offer"].obj()?.get("price").num() != null || (base != null && base > euros + 0.001)
        val unit = unitOf(h["measurement_unit"].text() ?: "")
        // "/<cuenta>/<id>/desktop?exp=...&sig=..." de Cloudflare Images: la firma caduca en una hora y sin ella tambien se sirve.
        val image = h["image"].text()?.substringBefore('?')?.let { if (it.startsWith("/")) "https://imagedelivery.net$it" else it }
        // Al peso: el precio es por kg.
        if (h["fractional"].bool() == true || h["per_unit"].bool() == true) {
            return@mapNotNull product("froiz", h["id"].text(), name, h["brand_name"].text(), euros, null, unit ?: QtyUnit.KG, offer, image)
        }
        val ratio = h["measurement_unit_ratio"].num()
        val net = Packs.fromText(name) ?: if (ratio != null && unit != null) fromUnitPrice(1.0, 1.0 / ratio, h["measurement_unit"].text()) else null
        product("froiz", h["id"].text(), name, h["brand_name"].text(), euros, net?.first, net?.second, offer, image)
    }

    private fun unescape(s: String): String = s.trim()
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toInt().toChar().toString() }
        .replace("&ordm;", "º").replace("&ordf;", "ª").replace("&deg;", "º")
        .replace("&aacute;", "á").replace("&eacute;", "é").replace("&iacute;", "í").replace("&oacute;", "ó").replace("&uacute;", "ú")
        .replace("&ntilde;", "ñ").replace("&Ntilde;", "Ñ").replace("&uuml;", "ü").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")

    private fun aktiosProducts(body: String): List<JsonObject> {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return emptyList()
        val arr = (root["products"] ?: (root["catalog"] as? JsonObject)?.get("products")) as? JsonArray ?: return emptyList()
        return arr.mapNotNull { it as? JsonObject }
    }

    private fun product(chain: String, id: String?, name: String, brand: String?, euros: Double, qty: Double?, unit: QtyUnit?, offer: Boolean, image: String? = null): WebProduct? {
        if (id == null || euros <= 0) return null
        val b = brand?.trim()?.takeIf { it.isNotBlank() }
        // Sin la marca, "Leche entera 1 l" de tres marcas serian el mismo producto.
        val full = if (b == null || Text.normalize(b) in Text.normalize(name)) name.trim() else "${name.trim()} · $b"
        return WebProduct(chain, id, full, b, (euros * 100).roundToLong(), qty, unit, offer, https(image))
    }

    /** Solo fotos por https; las rutas relativas se completan antes con el host de la cadena. */
    private fun https(url: String?): String? = url?.trim()?.takeIf { it.startsWith("https://") && it.length > 8 }

    /** Unidad de un precio de referencia: "1 L", "KILO", "Litro", "kg", "DOCENA"… */
    internal fun unitOf(text: String): QtyUnit? {
        val t = Text.normalize(text).replace(Regex("[^a-z]"), "")
        return when {
            t.startsWith("kg") || t.startsWith("kilo") -> QtyUnit.KG
            t == "l" || t.startsWith("lit") -> QtyUnit.L
            t == "g" || t.startsWith("gram") -> QtyUnit.G
            t == "ml" -> QtyUnit.ML
            t == "ud" || t == "u" || t.startsWith("unid") || t.startsWith("pieza") || t.startsWith("docena") -> QtyUnit.UD
            else -> null
        }
    }

    /** Cantidad neta a partir del precio y del precio por kg/l/ud: 4,98 € a 0,83 €/l son 6 l. */
    internal fun fromUnitPrice(euros: Double, perUnit: Double?, unitText: String?): Pair<Double, QtyUnit>? {
        if (perUnit == null || perUnit <= 0 || unitText == null) return null
        val unit = unitOf(unitText) ?: return null
        val dozen = if (Text.normalize(unitText).contains("docena")) 12 else 1
        val q = Math.round(euros / perUnit * dozen * 1000) / 1000.0
        if (q <= 0) return null
        return when (unit) {
            QtyUnit.KG -> if (q >= 1) q to QtyUnit.KG else Math.round(q * 1000).toDouble() to QtyUnit.G
            QtyUnit.L -> if (q >= 1) q to QtyUnit.L else Math.round(q * 1000).toDouble() to QtyUnit.ML
            QtyUnit.UD -> Math.round(q).toDouble() to unit
            else -> q to unit
        }
    }

    private fun array(body: String, key: String): List<JsonObject> =
        runCatching { json.parseToJsonElement(body).jsonObject[key]?.jsonArray }.getOrNull()
            ?.mapNotNull { it as? JsonObject }.orEmpty()

    /** Empathy (Carrefour, Condis): {"catalog":{"content":[…]}}. */
    private fun catalog(body: String): List<JsonObject> =
        runCatching { json.parseToJsonElement(body).jsonObject["catalog"]?.jsonObject?.get("content")?.jsonArray }.getOrNull()
            ?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun hits(body: String): List<JsonObject> =
        runCatching { json.parseToJsonElement(body).jsonObject["hits"]?.jsonArray }.getOrNull()
            ?.mapNotNull { it as? JsonObject }.orEmpty()

    private fun JsonElement?.obj(): JsonObject? = this as? JsonObject
    private fun JsonElement?.prim(): JsonPrimitive? = (this as? JsonPrimitive)?.takeUnless { it is JsonNull }
    private fun JsonElement?.text(): String? = prim()?.content?.takeIf { it.isNotBlank() }
    private fun JsonElement?.num(): Double? = prim()?.let { it.doubleOrNull ?: it.content.replace(',', '.').toDoubleOrNull() }
    private fun JsonElement?.bool(): Boolean? = prim()?.booleanOrNull
}
