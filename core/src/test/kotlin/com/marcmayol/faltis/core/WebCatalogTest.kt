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

    @Test
    fun aktiosOfferAndBrandInName() {
        val body = """{"catalog":{"products":[
          {"code":"1","productType":1,"productData":{"name":"Leche entera 1 l","description":"Leche entera 1 l","brand":{"name":"Marca "},"sponsored":false},
           "priceData":{"prices":[{"id":"PRICE","value":{"centAmount":1.29,"centUnitAmount":1.29}},{"id":"OFFER_PRICE","value":{"centAmount":0.99,"centUnitAmount":0.99}}],"unitPriceUnitType":"1 L"}},
          {"code":"2","productType":1,"productData":{"name":"Anuncio","sponsored":true},"priceData":{"prices":[{"id":"PRICE","value":{"centAmount":1.0}}]}}
        ]}}"""
        val ps = WebCatalog.parse("bm", body)
        assertEquals(1, ps.size)
        assertEquals("Leche entera 1 l · Marca", ps[0].name)
        assertEquals(99, ps[0].priceCents)
        assertTrue(ps[0].offer)
        assertEquals(1.0 to QtyUnit.L, ps[0].netQty to ps[0].netUnit)
    }

    @Test
    fun diaComputesPackFromUnitPrice() {
        val body = """{"search_items":[{"sku_id":"9","display_name":"Leche semi pack","brand":"Marca","prices":{"price":4.98,"price_per_unit":0.83,"measure_unit":"LITRO","is_promo_price":false}}]}"""
        val p = WebCatalog.parse("dia", body).single()
        assertEquals(6.0 to QtyUnit.L, p.netQty to p.netUnit)
        assertEquals(498, p.priceCents)
    }

    @Test
    fun empathyOfferAndVariableWeight() {
        val body = """{"catalog":{"content":[
          {"product_id":"a","display_name":"Leche entera brik 1 l.","brand":"Marca","active_price":0.9,"list_price":1.1,"unit_conversion_factor":1,"measure_unit":"l"},
          {"product_id":"b","display_name":"Pechuga de pollo","brand":"Marca","active_price":4.5,"list_price":4.5,"variable_weight":true,"average_weight":600,"unit_conversion_factor":0.001,"measure_unit":"kg"}
        ]}}"""
        val ps = WebCatalog.parse("carrefour", body)
        assertTrue(ps[0].offer)
        assertEquals("Leche entera brik 1 l · Marca", ps[0].name)
        assertEquals(600.0 to QtyUnit.G, ps[1].netQty to ps[1].netUnit)
    }

    @Test
    fun tapestryReadsTilesAndSkipsAds() {
        val tile = { id: String, name: String, price: String, extra: String ->
            "<div class='col product-item-lineal $extra'><a class='product-title-link' data-metrics='{\"ecommerce\":{\"items\":[{\"item_id\":\"$id\",\"item_name\":\"$name\",\"item_brand\":\"MARCA\",\"price\":1.1}]}}' href='#'>$name</a><span class='price-offer-now'> $price</span></div>"
        }
        val html = tile("1", "Leche entera MARCA, botella 1,5 litros", "1,10", "") + "<div class='criteoItem product-item-lineal'></div>"
        val body = """{"content":${kotlinx.serialization.json.JsonPrimitive(html)}}"""
        val p = WebCatalog.parse("eroski", body).single()
        assertEquals(110, p.priceCents)
        assertEquals(1.5 to QtyUnit.L, p.netQty to p.netUnit)
    }

    @Test
    fun lidlOnlyGroceriesWithRegionPrice() {
        val body = """{"items":[
          {"gridbox":{"data":{"erpNumber":"110001","title":"Queso","brand":{"name":"-"},"regionsV2":{"48":{"regionPriceId":"1"}},
            "regionsPrices":{"1":{"currentPrice":{"price":{"price":1.99,"oldPrice":2.49,"packaging":{"text":"250 g"}}}}}}}},
          {"gridbox":{"data":{"erpNumber":"100002","title":"Batidora","regionsPrices":{"1":{"currentPrice":{"price":{"price":19.99}}}}}}},
          {"gridbox":{"data":{"erpNumber":"110003","title":"Pan","regionsV2":{"44":{"regionPriceId":"1"}},"regionsPrices":{"1":{"currentPrice":{"price":{"price":0.5}}}}}}}
        ]}"""
        val ps = WebCatalog.parse("lidl", body, "48")
        assertEquals(listOf("Queso · 250 g"), ps.map { it.name })
        assertTrue(ps[0].offer)
        assertNull(ps[0].brand)
        assertEquals("48", WebCatalog.lidlRegion("07570"))
        assertEquals("", WebCatalog.lidlRegion("28001"))
    }

    @Test
    fun ocadoSkipsFeaturedAndUsesPromo() {
        val body = """{"productGroups":[
          {"type":"featured","decoratedProducts":[{"retailerProductId":"x","name":"Capsulas","price":{"amount":"5.00"}}]},
          {"type":"cluster","decoratedProducts":[
            {"retailerProductId":"1","name":"MARCA Leche 6 x 1 l","brand":"MARCA","packSizeDescription":"6000ml","price":{"amount":"5.28"},"promoPrice":{"amount":"4.99"}},
            {"retailerProductId":"2","type":"CATCHWEIGHT","name":"Platano","price":{"amount":"2.10"},"unitPrice":{"price":{"amount":"1.95"},"unitName":"PER_1KG"}}
          ]}
        ]}"""
        val ps = WebCatalog.parse("alcampo", body)
        assertEquals(2, ps.size)
        assertEquals(499, ps[0].priceCents)
        assertTrue(ps[0].offer)
        assertEquals(6.0 to QtyUnit.L, ps[0].netQty to ps[0].netUnit)
        assertEquals(195, ps[1].priceCents)
        assertNull(ps[1].netQty)
    }
}
