package com.marcmayol.faltis.core

import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * La foto de cada cadena. Trozos recortados de respuestas reales (oct-2026), con los nombres
 * cambiados: solo queda la forma en la que cada web da la foto.
 */
class WebCatalogImageTest {
    private fun image(chain: String, body: String, zone: String = ""): String? =
        WebCatalog.parse(chain, body, zone).first().imageUrl

    @Test
    fun mercadona() {
        val body = """{"hits":[{"id":"1","display_name":"Leche","thumbnail":"https://prod-mercadona.imgix.net/images/abc.jpg?fit=crop&h=300&w=300",
            "price_instructions":{"unit_price":"0.99","unit_size":1.0,"size_format":"l","approx_size":false}}]}"""
        assertEquals("https://prod-mercadona.imgix.net/images/abc.jpg?fit=crop&h=300&w=300", image("mercadona", body))
    }

    @Test
    fun aldiPrimaryAssetAt300() {
        val body = """{"hits":[{"objectID":"8722","name":"Leche entera","salesUnit":"1 l unidad","currentPrice":{"priceValue":0.95},
            "assets":[{"type":"certificate","url":"https://s7g10.scene7.com/is/image/aldinord/01_A_Origen_nacional"},
                      {"type":"primary","url":"https://s7g10.scene7.com/is/image/aldinord/8722_pack_0_0_es_es_leche"}]}]}"""
        assertEquals("https://s7g10.scene7.com/is/image/aldinord/8722_pack_0_0_es_es_leche?wid=300&hei=300", image("aldi", body))
    }

    private fun aktios(media: String, imageUrl: String) = """{"products":[{"code":"7080604","productType":1,
        "productData":{"name":"Leche entera 1 l","brand":{"name":"Marca"},"sponsored":false,"imageURL":"$imageUrl"},
        "media":[$media],
        "priceData":{"prices":[{"id":"PRICE","value":{"centAmount":0.95,"centUnitAmount":0.95}}],"unitPriceUnitType":"1 L"}}]}"""

    @Test
    fun consumPrefersMediaOverImageUrl() {
        // El imageURL de Consum da 404; la de media sirve.
        val body = aktios(
            """{"url":"https://cdn-consum.aktiosdigitalservices.com/tol/consum/media/product/img/300x300/7080604_002.jpg?t=1","order":2,"type":"P"},
               {"url":"https://cdn-consum.aktiosdigitalservices.com/tol/consum/media/product/img/300x300/7080604_001.jpg?t=1","order":1,"type":"P"}""",
            "https://cdn-consum.aktiosdigitalservices.com/tol/consum/media/product/img/300x300/7080604.jpg?t=2",
        )
        assertEquals("https://cdn-consum.aktiosdigitalservices.com/tol/consum/media/product/img/300x300/7080604_001.jpg?t=1", image("consum", body))
    }

    @Test
    fun bmUsesImageUrlWithoutMedia() {
        val body = aktios("", "https://cdn-bm.aktiosdigitalservices.com/tol/bm/media/product/img/300x300/A76411_00.jpg?t=1")
        assertEquals("https://cdn-bm.aktiosdigitalservices.com/tol/bm/media/product/img/300x300/A76411_00.jpg?t=1", image("bm", body))
    }

    @Test
    fun masymasAsksFor300() {
        val body = aktios(
            """{"url":"https://cdn-fornes.aktiosdigitalservices.com/tol/fornes/media/product/img/135x135/08411700011302.jpg?t=1","order":0,"type":"P"}""",
            "https://cdn-fornes.aktiosdigitalservices.com/tol/fornes/media/product/img/135x135/08411700011302.jpg?t=2",
        )
        assertEquals("https://cdn-fornes.aktiosdigitalservices.com/tol/fornes/media/product/img/300x300/08411700011302.jpg?t=1", image("masymas", body))
    }

    @Test
    fun diaRelativePath() {
        val body = """{"search_items":[{"sku_id":"504","display_name":"Leche semidesnatada 6 x 1 l","brand":"Marca",
            "image":"/product_images/504P6/504P6_ISO_0_ES.jpg","prices":{"price":4.98,"price_per_unit":0.83,"measure_unit":"LITRO","is_promo_price":false}}]}"""
        assertEquals("https://www.dia.es/product_images/504P6/504P6_ISO_0_ES.jpg", image("dia", body))
    }

    @Test
    fun carrefourImagePath() {
        val body = """{"catalog":{"content":[{"product_id":"700250","display_name":"Leche entera brik 1 l.","brand":"Marca","active_price":0.9,
            "image_path":{"food":"https://static.carrefour.es/hd_350x_/img_pim_food/700250_00_1.jpg"},
            "image_for_play_service":"https://static.carrefour.es/hd_350x_/img_pim_food/700250_00_1.jpg"}]}}"""
        assertEquals("https://static.carrefour.es/hd_350x_/img_pim_food/700250_00_1.jpg", image("carrefour", body))
    }

    @Test
    fun condisGoesToItsCdn() {
        val body = """{"catalog":{"content":[{"id":"704049","state":"ACTIVE","description":"LECHE ENTERA 1 L","brand":"Marca",
            "price":{"current":0.99,"regular":0.99},"pum":"0,99€/Litro","images":["/images/catalog/large/704049.jpg"],"altImages":["704049.jpg"]}]}}"""
        assertEquals("https://cdn.condis.es/fit-in/300x300/es/products/704049.jpg", image("condis", body))
    }

    @Test
    fun eroskiAndCapraboTileImage() {
        for ((chain, host) in listOf("eroski" to "https://supermercado.eroski.es", "caprabo" to "https://www.capraboacasa.com")) {
            val html = "<div class='col product-item-lineal'><div class='product-image'><a href='#'>" +
                "<img loading='lazy' data-bigImage='$host//images/18672295_x.jpg' src='$host//images/18672295.jpg' alt='Leche' title='Leche' class='product-img'/></a></div>" +
                "<a class='product-title-link' data-metrics='{\"ecommerce\":{\"items\":[{\"item_id\":\"18672295\",\"item_name\":\"Leche entera MARCA, brik 1 litro\",\"item_brand\":\"MARCA\",\"price\":0.95}]}}' href='#'>Leche</a>" +
                "<span class='price-offer-now'> 0,95</span></div>"
            assertEquals("$host/images/18672295.jpg", image(chain, """{"content":${JsonPrimitive(html)}}"""))
        }
    }

    @Test
    fun lidlImageAndNoPlainHttp() {
        val item = { img: String ->
            """{"items":[{"gridbox":{"data":{"erpNumber":"110001","title":"Queso","image":"$img",
                "regionsPrices":{"1":{"currentPrice":{"price":{"price":1.99,"packaging":{"text":"250 g"}}}}}}}}]}"""
        }
        assertEquals("https://www.lidl.es/assets/gcp992244bbba37470782a778f45af41cc1.png", image("lidl", item("https://www.lidl.es/assets/gcp992244bbba37470782a778f45af41cc1.png")))
        assertNull(image("lidl", item("http://www.lidl.es/assets/gcp1.png")))
    }

    @Test
    fun alcampoAndBonpreuImageSrc() {
        for ((chain, host) in listOf("alcampo" to "https://www.compraonline.alcampo.es", "bonpreu" to "https://www.compraonline.bonpreuesclat.cat")) {
            val body = """{"productGroups":[{"type":"cluster","decoratedProducts":[{"retailerProductId":"1","name":"MARCA Leche 6 x 1 l","brand":"MARCA",
                "packSizeDescription":"6000ml","price":{"amount":"5.28"},
                "image":{"src":"$host/images-v3/37ea0506/1aec1514/300x300.jpg","description":"Leche"}}]}]}"""
            assertEquals("$host/images-v3/37ea0506/1aec1514/300x300.jpg", image(chain, body))
        }
    }

    @Test
    fun bonareaFileNameAt300() {
        val body = """{"articles":[{"identifier":"13*0005","description":"Leche desnatada paq. de 6 brics","priceToPay":5.22,
            "measurementUnit":"6 l","image":["P13_0005_1.png"]}]}"""
        assertEquals("https://images.bonarea.com/P13_0005_1.png?width=300&height=300", image("bonarea", body))
    }

    @Test
    fun ahorramasTileImage() {
        val body = """<div class="product" data-pid="070864"><div class="image-container"><a href="#">
            <img class="tile-image"
                src="https://www.ahorramas.com/dw/image/v2/BFNH_PRD/on/demandware.static/-/Sites-ahorramas-master/default/dwb05c7aee/Assets/070864_C1C1/large/0/0/7/c/abc_070864_C1C1.png?sw=260&amp;sh=260&amp;sm=fit"
                alt="Leche entera 1L" itemprop="image" loading="lazy" /></a></div>
            <a class="link product-name-gtm" href="#">Leche entera 1L</a>
            <span class="value" content="0.95">0,95 &euro;</span></div>"""
        assertEquals(
            "https://www.ahorramas.com/dw/image/v2/BFNH_PRD/on/demandware.static/-/Sites-ahorramas-master/default/dwb05c7aee/Assets/070864_C1C1/large/0/0/7/c/abc_070864_C1C1.png?sw=260&sh=260&sm=fit",
            image("ahorramas", body),
        )
    }

    @Test
    fun gadisThumbnail() {
        val data = """{"props":{"pageProps":{"firstProducts":{"elements":[{"product_code":"123","price":0.95,"brand_description":"Marca",
            "commercial_description":[{"language":"ES","value":"Leche entera 1 l"}],
            "image":{"image":"https://storage.gadisline.com/catalog/dfb35a3e","image_thumbnails":"https://storage.gadisline.com/catalog/e3817eb5","thumbnail_id":"e3817eb5"}}]}}}}"""
        val body = """<html><script id="__NEXT_DATA__" type="application/json">$data</script></html>"""
        assertEquals("https://storage.gadisline.com/catalog/e3817eb5", image("gadis", body))
    }

    @Test
    fun hiperdinoLazyImageNotPlaceholder() {
        val body = """<ul><li class="product-list-item" data-id="3820247">
            <img alt="Leche entera 1 l"
                 src="https://cdn.hiperdino.es/catalog/product/placeholder/default/Pendiente-foto_1.jpg"
                 data-src="https://cdn.hiperdino.es/catalog/product/200x200/000000000003820247_1.jpg?update=1752656461" class="image--wrapper lazy">
            <span class="description__text name"> Leche entera 1 l </span>
            <div class="price price--final"><div class="price__text">0,95<span>€</span></div></div></li></ul>"""
        assertEquals("https://cdn.hiperdino.es/catalog/product/200x200/000000000003820247_1.jpg?update=1752656461", image("hiperdino", body))
    }

    @Test
    fun froizCloudflareWithoutSignature() {
        val body = """{"products":[{"id":101,"name":"Leche entera 1 l","brand_name":"Marca","order_price":0.95,"base_price":0.95,
            "measurement_unit":"l","measurement_unit_ratio":1,"image_id":"2bf2a8a0",
            "image":"\/laxGYDNZyT04iZVpzPzryw\/2bf2a8a0\/desktop?exp=1792168327&sig=fe74e9d4"}]}"""
        assertEquals("https://imagedelivery.net/laxGYDNZyT04iZVpzPzryw/2bf2a8a0/desktop", image("froiz", body))
    }
}
