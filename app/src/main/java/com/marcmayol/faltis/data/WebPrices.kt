package com.marcmayol.faltis.data

import com.marcmayol.faltis.BuildConfig
import com.marcmayol.faltis.core.WebCatalog
import com.marcmayol.faltis.core.WebProduct
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Lee precios de las tiendas online (capa D), desde el propio movil y solo si el usuario lo activa.
 * Mercadona y Aldi buscan con Algolia usando la clave de solo busqueda que sus webs publican;
 * los indices y el endpoint del almacen los documenta supermercapy (MIT, ver NOTICE).
 */
class WebPrices {
    private val agent = "Faltis/${BuildConfig.VERSION_NAME} (Android)"

    /** Almacen de Mercadona de un codigo postal (una peticion; se guarda en ajustes). */
    suspend fun mercadonaWarehouse(postalCode: String): String? = withContext(Dispatchers.IO) {
        val c = open("https://tienda.mercadona.es/api/postal-codes/actions/change-pc/", "PUT")
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write("""{"new_postal_code":"$postalCode"}""".toByteArray()) }
        try {
            if (c.responseCode !in 200..299) null
            else c.getHeaderField("X-Customer-Wh")?.trim()?.lowercase()?.takeIf { Regex("[a-z0-9]{2,16}").matches(it) }
        } finally { c.disconnect() }
    }

    /** Busca [query] en el catalogo de [chainId]; [zone] es el almacen de Mercadona o la region de Aldi. */
    suspend fun search(chainId: String, query: String, zone: String, hits: Int = 12): List<WebProduct> = withContext(Dispatchers.IO) {
        val (url, appId, key) = when (chainId) {
            "mercadona" -> Triple("https://7uzjkl1dj0-dsn.algolia.net/1/indexes/products_prod_${zone}_es/query", "7UZJKL1DJ0", "9d8f2e39e90df472b4f2e559a116fe17")
            "aldi" -> Triple("https://l9knu74io7-dsn.algolia.net/1/indexes/an_prd_es_es_${zone}_products2/query", "L9KNU74IO7", "83df5acd172c42ab174afa4583232b5d")
            else -> return@withContext emptyList()
        }
        val c = open(url, "POST")
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-algolia-application-id", appId)
        c.setRequestProperty("x-algolia-api-key", key)
        val params = "query=${URLEncoder.encode(query, "UTF-8")}&hitsPerPage=$hits"
        c.outputStream.use { it.write("""{"params":"$params"}""".toByteArray()) }
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            WebCatalog.parse(chainId, c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    private fun open(url: String, method: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = method != "GET"
            setRequestProperty("User-Agent", agent)
            setRequestProperty("Accept", "application/json")
        }
}
