package com.marcmayol.faltis.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Respuestas inventadas con la forma de las de verdad (no se guardan datos de las cadenas). */
class WebCatalogTest {
    private val mercadona = """
        {"hits":[
          {"id":"1001","display_name":"Leche entera Marca","packaging":"Brik","brand":"Marca",
           "price_instructions":{"unit_price":"0.99","unit_size":1.0,"size_format":"l","bulk_price":"0.99","approx_size":false}},
          {"id":"1002","display_name":"Leche entera Marca","packaging":"Pack-6","brand":"Marca",
           "price_instructions":{"unit_price":"5.94","unit_size":6.0,"size_format":"l","bulk_price":"0.99","approx_size":false}},
          {"id":"1003","display_name":"Tomates pera","packaging":null,"brand":null,
           "price_instructions":{"unit_price":"2.55","unit_size":1.11,"size_format":"kg","bulk_price":"2.30","approx_size":true}},
          {"id":"1004","display_name":"Huevos","packaging":"Paquete",
           "price_instructions":{"unit_price":"3.05","unit_size":12.0,"size_format":"ud","approx_size":false}},
          {"id":"1005","display_name":"Sin precio","price_instructions":{}}
        ]}
    """.trimIndent()

    private val aldi = """
        {"hits":[
          {"objectID":"2001","name":"Leche entera","brandName":"LACTEA®","salesUnit":"1 l unidad","currentPrice":{"priceValue":0.95}},
          {"objectID":"2002","name":"Leche evaporada","brandName":"LACTEA®","salesUnit":"340 g unidad","currentPrice":{"priceValue":0.92}},
          {"objectID":"2003","name":"Tomate","brandName":null,"salesUnit":null,"currentPrice":null}
        ]}
    """.trimIndent()

    @Test
    fun mercadonaFormatsAndBulk() {
        val ps = WebCatalog.parseMercadona(mercadona)
        assertEquals(4, ps.size)
        val brik = ps[0]
        assertEquals("Leche entera Marca · brik", brik.name)
        assertEquals(99, brik.priceCents)
        assertEquals(1.0, brik.netQty)
        assertEquals(QtyUnit.L, brik.netUnit)
        assertEquals(594, ps[1].priceCents)
        assertEquals(6.0, ps[1].netQty)
        // Fruta "aprox.": a granel, precio por kg.
        val tomate = ps[2]
        assertNull(tomate.netQty)
        assertEquals(QtyUnit.KG, tomate.netUnit)
        assertEquals(230, tomate.priceCents)
        assertEquals(12.0, ps[3].netQty)
        assertEquals(QtyUnit.UD, ps[3].netUnit)
    }

    @Test
    fun aldiSkipsUnpricedAndReadsSalesUnit() {
        val ps = WebCatalog.parseAldi(aldi)
        assertEquals(2, ps.size)
        assertEquals("Leche entera · 1 l", ps[0].name)
        assertEquals("LACTEA", ps[0].brand)
        assertEquals(95, ps[0].priceCents)
        assertEquals(1.0 to QtyUnit.L, ps[0].netQty to ps[0].netUnit)
        assertEquals(340.0 to QtyUnit.G, ps[1].netQty to ps[1].netUnit)
    }

    @Test
    fun brokenBodiesGiveNothing() {
        assertTrue(WebCatalog.parseMercadona("").isEmpty())
        assertTrue(WebCatalog.parseAldi("{\"message\":\"Invalid API key\"}").isEmpty())
    }

    @Test
    fun postalCodesAndRegions() {
        assertTrue(WebCatalog.isPostalCode("07570"))
        assertFalse(WebCatalog.isPostalCode("7570"))
        assertFalse(WebCatalog.isPostalCode("99000"))
        assertEquals("bal", WebCatalog.aldiRegion("07570"))
        assertEquals("can", WebCatalog.aldiRegion("38001"))
        assertEquals("pen", WebCatalog.aldiRegion("28001"))
    }
}
