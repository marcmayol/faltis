package com.marcmayol.faltis.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NutritionTest {
    private fun res(name: String) = javaClass.getResourceAsStream("/off/$name")!!.bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Test fun `ficha real de Open Food Facts con alergenos en español`() {
        val f = OpenFoodFacts.parseProduct(res("yogur_danone.json"))!!
        assertEquals("8410500018870", f.ean)
        assertEquals("Danone", f.brand)
        assertEquals(listOf("Leche"), f.allergens)
        assertEquals(listOf("Gluten", "Frutos de cáscara"), f.traces)
        assertEquals("b", f.nutriScore)
        assertEquals(1, f.nova)
        assertNotNull(f.kcal)
        val (p, c, g) = f.macroShares()!!
        assertEquals(100, p + c + g)
    }

    @Test fun `producto que no existe`() {
        assertNull(OpenFoodFacts.parseProduct(res("no_existe.json")))
        assertNull(OpenFoodFacts.parseProduct("no es json"))
    }

    @Test fun `kcal desde kJ y reparto de macros`() {
        val body = """{"code":"1234567890123","product":{"product_name":"Aceite","nutriments":{"energy_100g":3700,"proteins_100g":0,"carbohydrates_100g":0,"fat_100g":100}}}"""
        val f = OpenFoodFacts.parseProduct(body)!!
        assertEquals(884, f.kcal!!.toInt())
        assertEquals(Triple(0, 0, 100), f.macroShares())
        assertEquals(44, f.dailyShare())
    }

    @Test fun `elige la leche entera de Hacendado y no otra`() {
        val hits = OpenFoodFacts.parseSearch(res("busqueda_leche_hacendado.json"))
        assertTrue(hits.size >= 3)
        val (hit, score) = OpenFoodFacts.bestHit("Leche entera Hacendado · brick", "Hacendado", hits)!!
        assertEquals(listOf("Hacendado"), hit.brands)
        assertTrue("entera" in hit.name.lowercase(), hit.name)
        assertTrue(score >= OpenFoodFacts.MIN_LIKENESS)
    }

    @Test fun `con marca distinta no vale`() {
        val hits = listOf(FoodHit("8410500018870", "Yogur natural", listOf("Danone"), null))
        assertNull(OpenFoodFacts.bestHit("Yogur natural", "Hacendado", hits))
        assertNotNull(OpenFoodFacts.bestHit("Yogur natural", null, hits))
    }

    @Test fun `nada parecido, nada`() {
        val hits = listOf(FoodHit("8410500018870", "Colágeno hidrolizado", listOf("Pharma"), null))
        assertNull(OpenFoodFacts.bestHit("Leche entera", null, hits))
    }

    @Test fun `marca de busqueda para marca blanca sin marca escrita`() {
        assertEquals("hacendado", OpenFoodFacts.searchBrand(null, "mercadona", true))
        assertEquals("Pascual", OpenFoodFacts.searchBrand("Pascual", "mercadona", false))
        assertNull(OpenFoodFacts.searchBrand(null, "mercadona", false))
    }

    @Test fun `la url de busqueda quita la marca del nombre y filtra España`() {
        val url = java.net.URLDecoder.decode(OpenFoodFacts.searchUrl("Leche entera Hacendado 1 l", "Hacendado"), "UTF-8")
        assertTrue("q=leche entera brands:\"Hacendado\" countries_tags:\"en:spain\"" in url, url)
    }

    @Test fun `el tomate triturado no es tomate fresco`() {
        val tax = Taxonomy.bundled()
        assertEquals("despensa/tomate-triturado", tax.match("Tomate triturado Hacendado")!!.type.id)
        assertEquals("verdura/tomate", tax.match("Tomate pera")!!.type.id)
    }

    @Test fun `nombres de tienda - el tipo es lo que va delante`() {
        val tax = Taxonomy.bundled()
        fun t(name: String) = tax.matchProductName(name)?.type?.id
        assertEquals("bebida/agua", t("Agua mineral Bezoya pack 6 x 1,5 L"))
        assertNull(t("Bolsa de agua caliente"))
        assertNull(t("Botas de agua LED"))
        assertEquals(tax.match("yogur")!!.type.id, t("Milbona yogur natural"))
        assertEquals("higiene/desmaquillante", t("Agua micelar 400 ml"))
        assertEquals("despensa/tomate-triturado", t("Tomate triturado Hacendado"))
        assertEquals(tax.match("pan")!!.type.id, t("3 Barras de pan"))
        assertEquals(tax.match("leche entera")!!.type.id, t("Leche entera Hacendado · brik"))
    }
}
