package com.marcmayol.faltis.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Golden test del port de supermercapy: con su dataset etiquetado (1.343 fichas reales de 14
 * busquedas, 4-oct-2026) el port tiene que medir lo mismo que el original. El dataset contiene
 * datos de las cadenas: vive solo en test/resources, fuera de git (.gitignore), y nunca entra
 * en el APK. Si no esta, estos tests se saltan.
 */
class MatcherGoldenTest {
    private class Counts(var tp: Int = 0, var fp: Int = 0, var fn: Int = 0) {
        val precision get() = tp.toDouble() / (tp + fp)
        val recall get() = tp.toDouble() / (tp + fn)
        override fun toString() = "tp=$tp fp=$fp fn=$fn precision=${"%.3f".format(precision)} recall=${"%.3f".format(recall)}"
    }

    private class Search(val listings: List<Listing>, val same: List<List<String>>, val unsure: List<List<String>>, val kinds: Map<String, String>, val kindsUnsure: Set<String>)

    private fun str(e: kotlinx.serialization.json.JsonElement) = if (e is JsonNull) null else e.jsonPrimitive.content

    private fun searches(split: String): List<Search> {
        val res = javaClass.getResource("/match/evaluation.json")
        // No esta en el repo (datos de las cadenas): sin el fichero, el test se salta.
        org.junit.jupiter.api.Assumptions.assumeTrue(res != null, "Falta match/evaluation.json (ver .gitignore)")
        val doc = Json.parseToJsonElement(res!!.readText()).jsonObject
        val columns = doc["columns"]!!.jsonArray.map { it.jsonPrimitive.content }
        return doc["searches"]!!.jsonArray.map { it.jsonObject }.filter { it["split"]!!.jsonPrimitive.content == split }.map { s ->
            val listings = s["products"]!!.jsonArray.map { row ->
                val f = columns.zip((row as JsonArray).map { str(it) }).toMap()
                Listing(
                    store = f["store"], id = f["id"]!!, name = f["name"]!!, brand = f["brand"], ean = f["ean"],
                    packSizeText = f["pack_size_text"], price = f["price"]?.toDouble(),
                    reference = ProductMatcher.referencePrice(f["reference"]),
                )
            }
            fun groups(key: String) = (s[key] as? JsonArray)?.map { g -> g.jsonArray.map { it.jsonPrimitive.content } }.orEmpty()
            val kinds = (s["kinds"] as? JsonObject)?.flatMap { (name, keys) -> keys.jsonArray.map { it.jsonPrimitive.content to name } }?.toMap().orEmpty()
            val kindsUnsure = (s["kinds_unsure"] as? JsonArray)?.map { it.jsonPrimitive.content }?.toSet().orEmpty()
            Search(listings, groups("same"), groups("unsure"), kinds, kindsUnsure)
        }
    }

    private fun key(l: Listing) = "${l.store}:${l.id}"

    private fun evaluateSame(split: String): Counts {
        val c = Counts()
        for (s in searches(split)) {
            val cluster = s.same.flatMapIndexed { n, g -> g.map { it to n } }.toMap()
            val unsure = s.unsure.flatMap { g -> g.flatMap { a -> g.filter { it != a }.map { setOf(a, it) } } }.toSet()
            fun truth(a: String, b: String): Boolean? {
                if (cluster[a] != null && cluster[a] == cluster[b]) return true
                return if (setOf(a, b) in unsure) null else false
            }
            val read = s.listings.map { it to ProductMatcher.features(it) }
            for (i in read.indices) for (j in i + 1 until read.size) {
                val (a, fa) = read[i]
                val (b, fb) = read[j]
                if (a.store == b.store) continue
                val expected = truth(key(a), key(b)) ?: continue
                val found = ProductMatcher.scoreSame(fa, fb).score
                if (found >= ProductMatcher.SAME_THRESHOLD) { if (expected) c.tp++ else c.fp++ } else if (expected) c.fn++
            }
        }
        return c
    }

    @Test fun `mismo producto en desarrollo - precision 1,00 y recall 0,73 como el original`() {
        val c = evaluateSame("development")
        assertEquals(1.0, c.precision, 1e-9, c.toString())
        assertEquals(0.73, c.recall, 0.005, c.toString())
    }

    @Test fun `mismo producto en held-out - precision 0,996 y recall 0,64 como el original`() {
        val c = evaluateSame("held_out")
        assertEquals(0.996, c.precision, 0.0005, c.toString())
        assertEquals(0.64, c.recall, 0.005, c.toString())
    }

    @Test fun `alternativas - precision 0,998 y recall 0,61 como el original`() {
        val c = Counts()
        for (s in searches("development")) {
            val read = s.listings.map { it to ProductMatcher.features(it) }
            for ((a, fa) in read) for ((b, fb) in read) {
                if (a === b) continue
                val ka = s.kinds[key(a)] ?: continue
                if (key(b) in s.kindsUnsure) continue
                val expected = s.kinds[key(b)] == ka
                val found = ProductMatcher.scoreAlternative(fa, fb).score
                if (found >= ProductMatcher.ALTERNATIVE_THRESHOLD) { if (expected) c.tp++ else c.fp++ } else if (expected) c.fn++
            }
        }
        assertEquals(0.998, c.precision, 0.0005, c.toString())
        assertEquals(0.61, c.recall, 0.005, c.toString())
    }
}

class MatcherTextTest {
    private fun read(text: String) = ProductMatcher.readSize(ProductMatcher.plain(text)).first

    @Test fun `tamaños de multipacks, recuentos y regalos`() {
        val cases = listOf(
            Triple("Coca Cola pack de 2 botellas de 2 l.", 2, Measure(4.0, MeasureUnit.LITRE)),
            Triple("Leche semidesnatada pack 6 x 1.5 L", 6, Measure(9.0, MeasureUnit.LITRE)),
            Triple("Galletas Maria dorada 0% azúcares añadidos 2x200gr", 2, Measure(0.4, MeasureUnit.KILOGRAM)),
            Triple("Leche semidesnatada paq. 3 u. de 200 ml", 3, Measure(0.6, MeasureUnit.LITRE)),
            Triple("Papel higiénico FOXY SEDA, paquete 4+2 rollos", 6, null),
            Triple("Cerveza Heineken pack 5+1 x 25 cl", 6, Measure(1.5, MeasureUnit.LITRE)),
            Triple("Higiénico Bouquet Color 4 Rollos Más 2 Gratis", 6, null),
            Triple("Estropajos Salvauñas Classic 2u más 1 regalo", 3, null),
            Triple("Kill paff anti-mosquitos recambio 2 uds + 1 aparato gratis", 2, null),
            Triple("Cerveza Rubia Mahou 5* 33CL P16", 16, null),
            Triple("Pasta dental Oral-B 75ml Pack2 Pro Expert", 2, null),
            Triple("Refresco cola Coca-Cola 33cl pack lata 12 zero azúcar", 12, null),
            Triple("Detergente en gel COLON, garrafa 40+5 dosis", null, null),
            Triple("Leche semidesnatada paq. de 6 brics", 6, null),
            Triple("Pack-6", 6, null),
            Triple("6 per paquet", 6, null),
            Triple("Cerveza 0,0 MAHOU, lata 33 cl", null, null),
            Triple("Cerveza Mahou 5 Estrellas", null, null),
        )
        for ((text, count, total) in cases) {
            val r = read(text)
            assertEquals(count, r.count, text)
            if (total == null) assertNull(r.total, text)
            else { assertEquals(total.unit, r.total!!.unit, text); assertEquals(total.amount, r.total.amount, 1e-9, text) }
        }
    }

    @Test fun `reclamos, regalos y medias en una cantidad`() {
        val cases = listOf(
            "MOSTAZA PRIMA ORIGINAL PET +35G 300 G" to Measure(0.3, MeasureUnit.KILOGRAM),
            "Cacahuete en polvo desgrasado +Proteínas 14 g 70% reducido en grasa" to null,
            "Dodot etapas T/6 +13kg" to null,
            "HELLMANN'S Mayonesa frasco 440 +10 ml." to Measure(0.45, MeasureUnit.LITRE),
            "Galleta digestive zero sin azúcares Gullón 300g + 100g" to Measure(0.4, MeasureUnit.KILOGRAM),
            "Galleta Digestive sin Azúcar + 100g Gratis" to null,
            "Gofre con Chocolate 120 Más 20 g" to Measure(0.14, MeasureUnit.KILOGRAM),
            "Natillas con chocolate +Proteínas 10 g 1,3 g MG" to null,
            "Huevo Campero 1/2 Docena" to Measure(6.0, MeasureUnit.PIECE),
            "Huevos frescos media docena" to Measure(6.0, MeasureUnit.PIECE),
        )
        for ((text, loose) in cases) {
            val r = read(text)
            if (loose == null) assertNull(r.loose, text)
            else { assertEquals(loose.unit, r.loose!!.unit, text); assertEquals(loose.amount, r.loose.amount, 1e-9, text) }
        }
    }

    @Test fun `el precio decide si 2 botellas 2 l son 2 o 4 litros`() {
        val plus = ProductMatcher.features(Listing(null, "1", "Refresc COCA-COLA, pack 2 unitats 4 l", price = 4.84, reference = ProductMatcher.referencePrice("1.21/l")))
        assertEquals(2, plus.size!!.count); assertEquals(4.0, plus.size.total!!.amount, 1e-9)
        val carr = ProductMatcher.features(Listing(null, "2", "Coca Cola zero pack 2 botellas 2 l.", price = 4.10, reference = ProductMatcher.referencePrice("1.025/l")))
        assertEquals(4.0, carr.size!!.total!!.amount, 1e-9)
    }

    @Test fun `codigos de barras que no nombran un producto`() {
        assertEquals("05449000009067", ProductMatcher.gtin("5449000009067"))
        assertEquals("00000054491472", ProductMatcher.gtin("54491472"))
        assertNull(ProductMatcher.gtin("5449000009068")) // digito de control malo
        assertNull(ProductMatcher.gtin("2200470000000")) // peso variable de la tienda
        assertNull(ProductMatcher.gtin("20758837"))
        assertNull(ProductMatcher.gtin("11120161717X"))
        assertNull(ProductMatcher.gtin("1234"))
        assertNull(ProductMatcher.gtin(null))
    }

    @Test fun `tokens con negaciones y parecido por diferencias`() {
        val words = listOf("leche", "semidesnatada", "sin", "lactosa", "brik", "de", "llet")
        assertEquals(setOf("lech", "semidesnatad", "sin-lactos"), ProductMatcher.tokens(words))
        assertEquals(setOf("semidesnatad", "sin-lactos"), ProductMatcher.tokens(words, drop = listOf("llet")))
        assertEquals(1.0, ProductMatcher.likeness(emptySet(), emptySet()))
        assertEquals(1 / 1.15, ProductMatcher.likeness(setOf("refresc"), emptySet()), 1e-9)
        assertEquals(1 / 1.15, ProductMatcher.likeness(setOf("intantane"), emptySet()), 1e-9)
        assertTrue(ProductMatcher.likeness(setOf("semidesntad"), setOf("semidesnatad")) > 0.9)
        assertEquals(0.5, ProductMatcher.likeness(setOf("0"), emptySet()))
        assertTrue(abs(ProductMatcher.ratio("abcd", "bcde") - 0.75) < 1e-9)
    }

    @Test fun `marcas blancas`() {
        assertTrue(ProductMatcher.isStoreBrand("Hacendado"))
        assertTrue(ProductMatcher.isStoreBrand("CARREFOUR BIO"))
        assertEquals(setOf("eroski", "caprabo"), ProductMatcher.owners(ProductMatcher.brandWords("Eroski Basic")))
        assertTrue(!ProductMatcher.isStoreBrand("Pascual"))
    }
}
