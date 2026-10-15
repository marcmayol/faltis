package com.marcmayol.faltis.data

import com.marcmayol.faltis.core.NutritionFacts
import com.marcmayol.faltis.core.OpenFoodFacts
import com.marcmayol.faltis.core.ProductMatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Lo que se sabe de la etiqueta de un producto. */
sealed interface NutritionLookup {
    /** [approximate]: encontrada por nombre; puede ser otra presentacion del mismo producto. */
    data class Found(val facts: NutritionFacts, val approximate: Boolean) : NutritionLookup
    data object NotFound : NutritionLookup
    /** Sin conexion o Open Food Facts no responde: se puede reintentar. */
    data object Offline : NutritionLookup
}

/** Peticiones a Open Food Facts: solo se manda el codigo de barras o el nombre y la marca del producto. */
class FoodFacts {
    /** La ficha y la respuesta tal cual (para guardarla). */
    class Fetched(val body: String, val facts: NutritionFacts)

    suspend fun byEan(ean: String): Fetched? = withContext(Dispatchers.IO) {
        val body = get(OpenFoodFacts.productUrl(ean))
        OpenFoodFacts.parseProduct(body)?.let { Fetched(body, it) }
    }

    /** Busca por nombre y devuelve la ficha completa del candidato que mas se parece. */
    suspend fun byName(name: String, brand: String?): Fetched? {
        val hits = withContext(Dispatchers.IO) { OpenFoodFacts.parseSearch(get(OpenFoodFacts.searchUrl(name, brand))) }
        return OpenFoodFacts.bestHit(name, brand, hits)?.let { (hit, _) -> byEan(hit.ean) }
    }

    private fun get(url: String): String {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", OpenFoodFacts.USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            // 404 en un codigo que no esta: es "no encontrado", no un fallo de red.
            if (c.responseCode == 404) "{}"
            else if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            else c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    companion object {
        /** Codigo de barras valido como EAN-8 o EAN-13 (como los guarda Open Food Facts), o null. */
        fun validEan(code: String?): String? = ProductMatcher.gtin(code)?.trimStart('0')?.let { if (it.length <= 8) it.padStart(8, '0') else it.padStart(13, '0') }
    }
}
