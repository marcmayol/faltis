package com.marcmayol.faltis.data

import com.marcmayol.faltis.BuildConfig
import com.marcmayol.faltis.core.WebCatalog
import com.marcmayol.faltis.core.WebProduct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.cos
import kotlin.math.hypot

/**
 * Lee precios de las tiendas online (capa D), desde el propio movil y solo si el usuario lo activa.
 * Una busqueda por articulo de la lista, como haria el usuario en la web de la cadena.
 * Los endpoints de varias cadenas los documenta supermercapy (MIT, ver NOTICE).
 */
class WebPrices {
    private val agent = "Faltis/${BuildConfig.VERSION_NAME} (Android)"
    // Alcampo y Bonpreu (Ocado) rechazan cualquier peticion que no diga ser un navegador.
    private val browser = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/129.0 Mobile Safari/537.36"
    private val json = Json { ignoreUnknownKeys = true }

    /** Sesion de Dia ligada a un codigo postal (caduca a la hora sin uso). */
    private var diaSession: Triple<String, String, Long>? = null

    /**
     * Zona de precios de [chainId] para un codigo postal: almacen, tienda o region.
     * Se resuelve una vez y se guarda en ajustes; null si la cadena no tiene precios para esa zona.
     */
    suspend fun zone(chainId: String, postalCode: String): String? = withContext(Dispatchers.IO) {
        when (chainId) {
            "mercadona" -> mercadonaWarehouse(postalCode)
            "aldi" -> WebCatalog.aldiRegion(postalCode)
            "lidl" -> WebCatalog.lidlRegion(postalCode)
            "carrefour" -> carrefourSalePoint(postalCode) ?: DEFAULT
            // Dia: si no reparte en ese codigo postal, los precios por defecto (Madrid).
            "dia" -> if (diaServes(postalCode)) postalCode else DEFAULT
            "hiperdino" -> hiperdinoStore(postalCode)
            "bm" -> aktiosZone("https://www.online.bmsupermercados.es", postalCode)
            "masymas" -> aktiosZone("https://tienda.masymas.com", postalCode)
            "froiz" -> froizStore(postalCode)
            // Precio unico, o fijado a la tienda online de la cadena.
            "consum", "condis", "eroski", "caprabo", "alcampo", "bonpreu", "bonarea", "ahorramas", "gadis" -> DEFAULT
            else -> null
        }
    }

    /** Busca [query] en el catalogo de [chainId]; [zone] es la que dio [zone]. */
    suspend fun search(chainId: String, query: String, zone: String, hits: Int = 12): List<WebProduct> = withContext(Dispatchers.IO) {
        val q = enc(query)
        val body = when (chainId) {
            "mercadona" -> algolia("https://7uzjkl1dj0-dsn.algolia.net/1/indexes/products_prod_${zone}_es/query", "7UZJKL1DJ0", "9d8f2e39e90df472b4f2e559a116fe17", query, hits)
            "aldi" -> algolia("https://l9knu74io7-dsn.algolia.net/1/indexes/an_prd_es_es_${zone}_products2/query", "L9KNU74IO7", "83df5acd172c42ab174afa4583232b5d", query, hits)
            "consum" -> get("https://tienda.consum.es/api/rest/V1.0/catalog/product?q=$q&limit=20&offset=0", "X-TOL-LOCALE" to "es")
            "dia" -> diaSearch(q, zone)
            "carrefour" -> get(
                "https://api.empathy.co/search/v1/query/carrefour/search?query=${enc(query.lowercase())}&lang=es&catalog=food&rows=24&start=0" +
                    (if (zone == DEFAULT) "" else "&store=$zone"),
            )
            "condis" -> get("https://api.empathy.co/search/v1/query/condis/search?lang=es&start=0&rows=24&query=$q&store=718")
            "eroski" -> tapestry("https://supermercado.eroski.es", q)
            "caprabo" -> tapestry("https://www.capraboacasa.com", q)
            "lidl" -> get(
                "https://www.lidl.es/q/api/search?assortment=ES&locale=es_ES&version=2.1.0&q=$q&store=1&fetchsize=60&offset=0",
                accept = "application/mindshift.search+json;version=2",
            )
            "alcampo" -> ocado("https://www.compraonline.alcampo.es", q)
            "bonpreu" -> ocado("https://www.compraonline.bonpreuesclat.cat", q, "Cookie" to "language=es-ES")
            "bonarea" -> bonareaSearch(q)
            "ahorramas" -> get("https://www.ahorramas.com/on/demandware.store/Sites-Ahorramas-Site/es/Search-UpdateGrid?q=$q&start=0&sz=24", "X-Requested-With" to "XMLHttpRequest", accept = "text/html")
            "gadis" -> get("https://www.gadisline.com/pag/resultados-de-busqueda?q=$q", accept = "text/html")
            "hiperdino" -> get("https://www.hiperdino.es/${zone.takeIf { it != DEFAULT } ?: "c9504"}/search/?q=$q", accept = "text/html")
            "bm" -> aktiosSearch("https://www.online.bmsupermercados.es", q, zone)
            "masymas" -> aktiosSearch("https://tienda.masymas.com", q, zone)
            "froiz" -> get("https://servicios.froiz.com/api/products?term=$q&page=1&size=24" + (if (zone == DEFAULT) "" else "&store=$zone"))
            else -> return@withContext emptyList()
        }
        WebCatalog.parse(chainId, body, zone.takeIf { it != DEFAULT } ?: "")
    }

    /** Almacen de Mercadona de un codigo postal (una peticion). */
    private fun mercadonaWarehouse(postalCode: String): String? {
        val c = open("https://tienda.mercadona.es/api/postal-codes/actions/change-pc/", "PUT")
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write("""{"new_postal_code":"$postalCode"}""".toByteArray()) }
        return try {
            if (c.responseCode !in 200..299) null
            else c.getHeaderField("X-Customer-Wh")?.trim()?.lowercase()?.takeIf { Regex("[a-z0-9]{2,16}").matches(it) }
        } finally { c.disconnect() }
    }

    private fun algolia(url: String, appId: String, key: String, query: String, hits: Int): String {
        val params = "query=${enc(query)}&hitsPerPage=$hits"
        return post(url, """{"params":"$params"}""", "x-algolia-application-id" to appId, "x-algolia-api-key" to key)
    }

    /**
     * Carrefour pone precio por punto de venta "drive". Se elige el del mismo codigo postal o, si no,
     * el mas cercano a la tienda Carrefour mas proxima. Sin ninguno, precios por defecto.
     */
    private fun carrefourSalePoint(postalCode: String): String? {
        val drives = runCatching {
            json.parseToJsonElement(get("https://www.carrefour.es/cloud-api/salepoints/v1/drives")).jsonObject["groups"]!!.jsonArray
                .flatMap { g -> g.jsonObject["sale_points"]!!.jsonArray.map { it.jsonObject } }
        }.getOrNull() ?: return null
        fun JsonObject.s(k: String) = this[k]?.jsonPrimitive?.content?.trim()
        drives.firstOrNull { it.s("postal_code") == postalCode }?.s("sale_point_id")?.let { return it }
        val near = runCatching {
            json.parseToJsonElement(get("https://www.carrefour.es/cloud-api/salepoints/v1/stores-location/$postalCode")).jsonObject["stores"]!!
                .jsonArray.first().jsonObject.s("position")!!.split(',').map { it.trim().toDouble() }
        }.getOrNull() ?: return null
        val (lat, lon) = near[0] to near[1]
        return drives.mapNotNull { d ->
            val dl = d.s("latitude")?.toDoubleOrNull() ?: return@mapNotNull null
            val dn = d.s("longitude")?.toDoubleOrNull() ?: return@mapNotNull null
            d to hypot(dl - lat, (dn - lon) * cos(Math.toRadians(lat)))
        }.minByOrNull { it.second }?.takeIf { it.second < 0.5 }?.first?.s("sale_point_id")
    }

    private fun diaServes(postalCode: String): Boolean {
        val c = open("https://www.dia.es/api/v1/common-aggregator/check-service?postal_code=$postalCode", "GET")
        return try { c.responseCode == 200 } finally { c.disconnect() }
    }

    /** Dia liga el codigo postal a una cookie de sesion: se fija una vez y se reutiliza. */
    private fun diaSearch(q: String, zone: String): String {
        val url = "https://www.dia.es/api/v1/search-back/search/reduced?q=$q&page=1&page_size=30"
        if (zone == DEFAULT) return get(url)
        val now = System.currentTimeMillis()
        val session = diaSession?.takeIf { it.first == zone && now - it.third < 45 * 60_000 }?.second ?: run {
            val c = open("https://www.dia.es/api/v1/common-aggregator/check-service?postal_code=$zone", "GET")
            val cookies = try { c.responseCode; cookieHeader(c) } finally { c.disconnect() }
            val put = open("https://www.dia.es/api/v1/common-aggregator/save-shipping-address?new_postal_code=$zone", "PUT")
            put.setRequestProperty("Cookie", cookies)
            put.setFixedLengthStreamingMode(0)
            try { put.outputStream.close(); if (put.responseCode !in 200..299) error("Dia: HTTP ${put.responseCode}") } finally { put.disconnect() }
            cookies
        }
        diaSession = Triple(zone, session, now)
        return get(url, "Cookie" to session)
    }

    /** bonArea responde en catalan si la busqueda no lleva la sesion de la pagina en castellano. */
    private var bonareaSession: Pair<String, Long>? = null

    private fun bonareaSearch(q: String): String {
        val now = System.currentTimeMillis()
        val cookies = bonareaSession?.takeIf { now - it.second < 15 * 60_000 }?.first ?: run {
            val c = open("https://www.bonarea-online.com/es", "GET")
            try { c.responseCode; cookieHeader(c) } finally { c.disconnect() }
        }
        bonareaSession = cookies to now
        val c = open("https://www.bonarea-online.com/es/shop/search", "POST")
        c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        c.setRequestProperty("Cookie", cookies)
        c.outputStream.use { it.write("strQuery=$q".toByteArray()) }
        return read(c)
    }

    /** HiperDino (Canarias): la tienda va en la ruta; se pide la del codigo postal. */
    private fun hiperdinoStore(postalCode: String): String {
        if (!postalCode.startsWith("35") && !postalCode.startsWith("38")) return DEFAULT
        return runCatching {
            val home = open("https://www.hiperdino.es/c9504/", "GET").apply { setRequestProperty("Accept", "text/html") }
            val (html, cookies) = try { home.inputStream.bufferedReader().use { it.readText() } to cookieHeader(home) } finally { home.disconnect() }
            val key = Regex("form_key/([A-Za-z0-9]+)/").find(html)!!.groupValues[1]
            val c = open("https://www.hiperdino.es/c9504/hiperdino_freenavigation/delivery/postcodeAjax/", "POST")
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            c.setRequestProperty("Cookie", cookies)
            c.outputStream.use { it.write("postcode=$postalCode&form_key=$key".toByteArray()) }
            // {"redirectUrl":"https:\/\/www.hiperdino.es\/c9510\/"}
            Regex("(c\\d{3,})").find(read(c).substringAfter("redirectUrl"))!!.groupValues[1]
        }.getOrDefault(DEFAULT)
    }

    /** BM y masymas: zona de reparto del codigo postal, que cambia el surtido y el precio. */
    private fun aktiosZone(host: String, postalCode: String): String = runCatching {
        val area = json.parseToJsonElement(get("$host/api/rest/V1.0/shipping/area?shippingMethod=D&zipCode=$postalCode"))
            .jsonArray.first().jsonObject["shippingAreas"]!!.jsonArray.first().jsonObject
        "${area["zone"]!!.jsonObject["id"]!!.jsonPrimitive.content}:${area["shippingZoneId"]!!.jsonPrimitive.content}"
    }.getOrDefault(DEFAULT)

    private fun aktiosSearch(host: String, q: String, zone: String): String {
        val headers = mutableListOf("X-TOL-LOCALE" to "es", "X-TOL-CHANNEL" to "1")
        if (zone != DEFAULT) zone.split(':').let { z -> headers += "X-TOL-ZONE" to z[0]; headers += "X-TOL-SHIPPING-ZONE" to z.getOrElse(1) { z[0] } }
        return get("$host/api/rest/V1.0/catalog/searcher/products?q=$q&limit=24&offset=0&orderById=13", *headers.toTypedArray())
    }

    private fun froizStore(postalCode: String): String = runCatching {
        val o = json.parseToJsonElement(get("https://servicios.froiz.com/api/stores/postalcode/$postalCode")).jsonObject
        if (o["hasCoverage"]?.jsonPrimitive?.content != "true") DEFAULT
        else "${o["codEnt"]!!.jsonPrimitive.content}_${o["codSubent"]!!.jsonPrimitive.content}"
    }.getOrDefault(DEFAULT)

    private fun cookieHeader(c: HttpURLConnection): String =
        c.headerFields.filterKeys { it.equals("Set-Cookie", ignoreCase = true) }.values.flatten()
            .joinToString("; ") { it.substringBefore(';') }

    /** Eroski y Caprabo: el trozo de pagina con las fichas, envuelto en JSON. */
    private fun tapestry(host: String, q: String): String {
        val body = get("$host/es/search/results:loadpage?q=$q&t:zoneid=productListZone&pageNumber=0", "X-Requested-With" to "XMLHttpRequest")
        // Su cortafuegos a veces responde 200 con un captcha: se deja la cadena para otro momento.
        if ("recaptcha" in body.take(4000)) error("captcha")
        return body
    }

    private fun ocado(host: String, q: String, vararg headers: Pair<String, String>): String {
        val c = open("$host/api/webproductpagews/v6/product-pages/search?q=$q&tag=web&maxPageSize=30&maxProductsToDecorate=30", "GET", browser)
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        // Limite de su cortafuegos: 202 sin cuerpo. No se insiste.
        return read(c).also { if (it.isBlank()) error("limite") }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun get(url: String, vararg headers: Pair<String, String>, accept: String = "application/json"): String =
        read(open(url, "GET").apply { setRequestProperty("Accept", accept); headers.forEach { (k, v) -> setRequestProperty(k, v) } })

    private fun post(url: String, body: String, vararg headers: Pair<String, String>): String {
        val c = open(url, "POST")
        c.setRequestProperty("Content-Type", "application/json")
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        c.outputStream.use { it.write(body.toByteArray()) }
        return read(c)
    }

    private fun read(c: HttpURLConnection): String = try {
        if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
        c.inputStream.bufferedReader().use { it.readText() }
    } finally { c.disconnect() }

    private fun open(url: String, method: String, userAgent: String = agent): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = method == "POST" || method == "PUT"
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "application/json")
        }

    companion object {
        /** Zona unica o la de por defecto de la cadena. */
        const val DEFAULT = "default"

        /** Busquedas como mucho por actualizacion: el cortafuegos de Ocado corta a las 7-8 cada media hora. */
        fun maxSearches(chainId: String): Int = if (chainId == "alcampo" || chainId == "bonpreu") 6 else Int.MAX_VALUE
    }
}
