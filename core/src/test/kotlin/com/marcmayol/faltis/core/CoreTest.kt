package com.marcmayol.faltis.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

class FreeTextParserTest {
    private val parser = FreeTextParser(Taxonomy.bundled())

    @Test fun `separa varios articulos con cantidad y unidad (F-02 CA1)`() {
        val r = parser.parse("2 leches, pan, 1 kg de tomates")
        assertEquals(3, r.size)
        assertEquals("leche", r[0].typeId); assertEquals(2.0, r[0].qty); assertEquals(QtyUnit.UD, r[0].unit)
        assertEquals("pan/barra", r[1].typeId)
        assertEquals("verdura/tomate", r[2].typeId); assertEquals(1.0, r[2].qty); assertEquals(QtyUnit.KG, r[2].unit)
    }

    @Test fun `media docena de huevos`() {
        val r = parser.parseOne("media docena de huevos")!!
        assertEquals("huevo", r.typeId); assertEquals(6.0, r.qty); assertEquals(QtyUnit.UD, r.unit)
    }

    @Test fun `restriccion y sufijo x2`() {
        val r = parser.parseOne("Leche sin lactosa x2")!!
        assertEquals("leche", r.typeId); assertEquals(2.0, r.qty); assertEquals(listOf("sin lactosa"), r.restrictions)
    }

    @Test fun `plural y catalan`() {
        assertEquals("fruta/aguacate", parser.parseOne("aguacates")!!.typeId)
        assertEquals("verdura/tomate", parser.parseOne("tomàtiga")!!.typeId)
        assertEquals("huevo", parser.parseOne("ous")!!.typeId)
    }

    @Test fun `cantidad pegada a la unidad`() {
        val r = parser.parseOne("500g de café")!!
        assertEquals("desayuno/cafe-molido", r.typeId); assertEquals(500.0, r.qty); assertEquals(QtyUnit.G, r.unit)
    }

    @Test fun `sin tipo conserva el texto`() {
        val r = parser.parseOne("velas de cumpleaños")!!
        assertEquals(null, r.typeId)
    }
}

class PricingTest {
    private val tax = Taxonomy.bundled()
    private val now = Instant.parse("2026-10-10T10:00:00Z")
    private val store = StoreRef(1, "mercadona", "Mercadona Artà", 0)

    private fun product(id: Long, net: Double, unit: QtyUnit = QtyUnit.L) = ProductRef(
        id, "mercadona", "Leche $id", "Hacendado", true, "leche/entera", "entera", emptySet(), net, unit, null, false,
    )
    private fun obs(pid: Long, cents: Long, daysAgo: Long, src: PriceSource = PriceSource.TICKET_PHOTO) =
        PriceObservation(pid, 1, "mercadona", cents, PriceKind.REGULAR, src, now.minus(Duration.ofDays(daysAgo)))
    private fun need(qty: Double) = NeedRef(1, "leche", "leche", qty, QtyUnit.L, emptyList(), null, NeedState.PENDING)

    @Test fun `CA1 minimiza el coste que cubre lo pedido, no el precio por litro`() {
        val book = PriceBook(tax, listOf(product(1, 1.0), product(2, 1.5)), listOf(obs(1, 89, 2), obs(2, 120, 2)))
        val two = book.priceNeed(need(2.0), store, now, false) as NeedPriceResult.Priced
        assertEquals(1L, two.value.product.id); assertEquals(178L, two.value.costCents)
        val three = book.priceNeed(need(3.0), store, now, false) as NeedPriceResult.Priced
        assertEquals(2L, three.value.product.id); assertEquals(240L, three.value.costCents)
    }

    @Test fun `CA2 precio de mas de 90 dias no se usa`() {
        val book = PriceBook(tax, listOf(product(1, 1.0)), listOf(obs(1, 89, 120)))
        val r = book.priceNeed(need(1.0), store, now, false)
        assertTrue(r is NeedPriceResult.Missing && r.reason == NoPriceReason.NO_RECENT_PRICE)
    }

    @Test fun `CA3 un precio de ejemplo nunca gana a uno real`() {
        val book = PriceBook(tax, listOf(product(1, 1.0)), listOf(obs(1, 50, 0, PriceSource.SAMPLE), obs(1, 89, 20)))
        val r = book.priceNeed(need(1.0), store, now, false) as NeedPriceResult.Priced
        assertEquals(89L, r.value.obs.priceCents)
    }

    @Test fun `envases y estimacion`() {
        val p = Packs.plan(Quantity(2.0, QtyUnit.L), 1.5, QtyUnit.L)
        assertEquals(2, p.packs); assertEquals(false, p.estimated)
        val e = Packs.plan(Quantity(2.0, QtyUnit.UD), 1.0, QtyUnit.KG)
        assertEquals(2, e.packs); assertEquals(true, e.estimated)
    }
}

class ComparatorAndSampleTest {
    private val tax = Taxonomy.bundled()
    private val now = Instant.parse("2026-10-10T10:00:00Z")

    @Test fun `escenario de ejemplo - 3 en el ranking y Carrefour en faltan datos`() {
        val cat = SampleData.build()
        val stores = cat.stores.mapIndexed { i, s -> StoreRef(i.toLong() + 1, s.chainId, s.name, s.position) }
        val obs = cat.observations.map { o ->
            val s = stores[o.storeIndex]
            PriceObservation(o.productTempId, s.id, s.chainId, o.priceCents, o.kind, PriceSource.SAMPLE, now.minus(Duration.ofDays(o.daysAgo)))
        }
        val book = PriceBook(tax, cat.products.map { it.ref }, obs)
        val needs = cat.needs.mapIndexed { i, n -> NeedRef(i.toLong(), n.text, n.typeId, n.qty, n.unit, n.restrictions, null, NeedState.PENDING) }
        val c = BasketComparator(book).compare(needs, stores, now, false)
        assertTrue(c is Comparison.Ranking, "esperaba ranking, fue $c")
        c as Comparison.Ranking
        assertEquals(3, c.ranked.size)
        assertEquals(listOf("carrefour"), c.missingData.map { it.store.chainId })
        assertEquals(13, c.commonNeedIds.size)
        // El ahorro mostrado es la diferencia sobre el conjunto comun.
        val second = c.ranked[1]
        val manual = second.basket.priced.filter { it.need.id in c.commonNeedIds }.sumOf { it.costCents } -
            c.best.basket.priced.filter { it.need.id in c.commonNeedIds }.sumOf { it.costCents }
        assertEquals(manual, second.deltaCents)

        // Repartir: nunca cuesta mas que la mejor tienda sola, y cada articulo va a la mas barata de las dos.
        val split = BasketSplit.best(c, minSaving = 0)!!
        assertEquals(c.best.commonCents - split.totalCents, split.savingCents)
        assertTrue(split.savingCents >= 0)
        assertEquals(c.commonNeedIds.size, split.inFirst.size + split.inSecond.size)
        val byStore = c.ranked.associate { it.basket.store.id to it.basket }
        split.inSecond.forEach { p -> assertTrue(p.costCents < byStore.getValue(split.first.id).priceFor(p.need.id)!!.costCents) }
        assertEquals(null, BasketSplit.best(c, minSaving = Long.MAX_VALUE))

        // Articulo a articulo: ofertas de la mas barata a la mas cara.
        val items = BasketSplit.itemOffers(c.ranked.map { it.basket })
        assertEquals(needs.size, items.size)
        items.forEach { it.offers.zipWithNext().forEach { (x, y) -> assertTrue(x.second.costCents <= y.second.costCents) } }
    }
}

class EquivalenceTest {
    @Test fun `numero o sin distintos y envase distinto no son equivalentes, con su porque`() {
        fun q(id: Long, chain: String, name: String) = ProductRef(id, chain, name, null, true, "leche/entera", "entera", emptySet(), 1.0, QtyUnit.L, null, false)
        val a = q(1, "mercadona", "Leche entera Hacendado brik")
        assertEquals(EquivalenceLevel.EQUIVALENT, Equivalence.level(a, q(2, "eroski", "Leche entera Eroski brik")))
        val sin = Equivalence.explain(a, q(3, "eroski", "Leche entera sin lactosa Eroski"))!!
        assertEquals(EquivalenceLevel.SIMILAR, sin.level); assertTrue(sin.reasons.first().contains("sin lactos"), sin.reasons.toString())
        val botella = Equivalence.explain(a, q(4, "eroski", "Leche entera botella Eroski"))!!
        assertEquals(EquivalenceLevel.SIMILAR, botella.level); assertTrue(botella.reasons.first().startsWith("Envase distinto"), botella.reasons.toString())
        val ok = Equivalence.explain(a, q(5, "carrefour", "Leche entera brik"))!!
        assertTrue("Las dos son marca blanca" in ok.reasons, ok.reasons.toString())
    }

    private fun p(id: Long, chain: String, name: String, priv: Boolean, brand: String?, variant: String?, net: Double, tags: Set<String> = emptySet(), ean: String? = null) =
        ProductRef(id, chain, name, brand, priv, "bolleria/croissant", variant, tags, net, QtyUnit.G, ean, false)

    @Test fun `la trampa del croissant de MiCesta sale como parecido`() {
        val a = p(1, "mercadona", "Croissant mantequilla", true, "Hacendado", "mantequilla", 50.0)
        val relleno = p(2, "dia", "Mini croissant relleno de cacao", false, "El Molino", "relleno cacao", 55.0, setOf("relleno"))
        val eroski = p(3, "eroski", "Croissant mantequilla", true, "Eroski Basic", "mantequilla", 55.0)
        assertEquals(EquivalenceLevel.SIMILAR, Equivalence.level(a, relleno))
        assertEquals(EquivalenceLevel.EQUIVALENT, Equivalence.level(a, eroski))
        assertEquals(EquivalenceLevel.IDENTICAL, Equivalence.level(a.copy(ean = "1"), eroski.copy(id = 9, ean = "1")))
    }
}

class AisleLearnerTest {
    @Test fun `CA1 el orden tachado se repite`() {
        val learned = AisleLearner.learn(emptyMap(), listOf("a", "b", "c"))
        val ordered = AisleLearner.order(listOf("c", "x", "a", "b"), { it }, { 0 }, learned)
        assertEquals(listOf("a", "b", "c", "x"), ordered)
    }
}

class TicketParserTest {
    private val tax = Taxonomy.bundled()
    private val parser = TicketParser(tax)
    private fun fixture(name: String) = javaClass.getResource("/tickets/$name")!!.readText()

    @Test fun `CA1 Mercadona - todas las lineas, cuadra y fecha`() {
        val t = parser.parse(fixture("mercadona_arta.txt"))
        assertEquals("mercadona", t.chainId)
        assertEquals(LocalDate.of(2026, 10, 8), t.date)
        assertEquals(1150L, t.totalCents)
        assertEquals(6, t.lines.size, t.lines.joinToString("\n") { "${it.description} ${it.amountCents}" })
        assertTrue(t.balanced, "suma ${t.linesCents} vs total ${t.totalCents}")
        val huevos = t.lines.first { it.description.startsWith("HUEVOS") }
        assertEquals(2.0, huevos.qty); assertEquals(235L, huevos.unitPriceCents); assertEquals("huevo", huevos.typeId)
    }

    @Test fun `CA2 descuento - bruto y neto en el articulo anterior`() {
        // Mercadona no hace descuentos en ticket: el caso va con otra cadena.
        val t = parser.parse(fixture("carrefour_promo.txt"))
        assertEquals("carrefour", t.chainId)
        assertTrue(t.balanced, "suma ${t.linesCents} vs total ${t.totalCents}")
        val yogur = t.lines.first { it.description.contains("YOGUR") }
        assertEquals(185L, yogur.amountCents); assertEquals(40L, yogur.discountCents)
        assertEquals(PriceKind.PROMO, yogur.discountKind); assertEquals(145L, yogur.netCents)
    }

    @Test fun `CA3 articulo al peso`() {
        val t = parser.parse(fixture("mercadona_arta.txt"))
        val pl = t.lines.first { it.weightKg != null }
        assertEquals("PLATANO", pl.description); assertEquals(1.284, pl.weightKg); assertEquals(199L, pl.unitPriceCents)
        assertEquals(256L, pl.amountCents); assertEquals("fruta/platano", pl.typeId)
    }

    @Test fun `CA4 abreviatura aprendida da confianza alta`() {
        val lookup = AbbreviationLookup { chain, key -> if (chain == "mercadona" && key == "pan rusti 1/2") 42L to "pan/barra" else null }
        val t = parser.parse(fixture("mercadona_arta.txt"), lookup = lookup)
        val pan = t.lines.first { it.description.startsWith("PAN") }
        assertEquals(Confidence.HIGH, pan.confidence); assertEquals(42L, pan.productId)
    }

    @Test fun `Eroski con errores de OCR y precio de socio`() {
        val t = parser.parse(fixture("eroski_ocr.txt"))
        assertEquals("eroski", t.chainId)
        assertEquals(LocalDate.of(2026, 10, 10), t.date)
        assertEquals(3, t.lines.size, t.lines.joinToString("\n") { "${it.description} ${it.amountCents}" })
        val leche = t.lines[0]
        assertEquals(6.0, leche.qty); assertEquals(79L, leche.unitPriceCents); assertEquals("leche/semidesnatada", leche.typeId)
        val aceite = t.lines[1]
        assertEquals(890L, aceite.amountCents); assertEquals(PriceKind.MEMBER, aceite.discountKind)
        assertNotNull(t.totalCents); assertTrue(t.balanced, "suma ${t.linesCents} vs total ${t.totalCents}")
    }

    private fun lines(t: ParsedTicket) = t.lines.joinToString("\n") { "${it.description} | ${it.qty} | ${it.weightKg} | ${it.amountCents}" }

    @Test fun `Mercadona real - cabecera con tildes, secciones, PARKING y pie de tarjeta`() {
        val t = parser.parse(fixture("mercadona_real.txt"))
        assertEquals("mercadona", t.chainId)
        assertEquals(5, t.lines.size, lines(t))
        assertEquals(1289L, t.totalCents)
        assertTrue(t.balanced, lines(t))
        assertEquals(listOf("PAPEL HIGIÉNICO 4 CA", "LECHE ENTERA", "PATATA", "GALERAS", "PARKING"), t.lines.map { it.description })
        val galeras = t.lines.first { it.description == "GALERAS" }
        assertEquals(0.450, galeras.weightKg); assertEquals(980L, galeras.unitPriceCents); assertEquals(441L, galeras.amountCents)
        assertEquals(null, t.lines.first { it.description == "PARKING" }.typeId)
        assertEquals(LineCheck.OK, t.lines.first { it.description == "LECHE ENTERA" }.check)
    }

    @Test fun `numero de factura y hora`() {
        val t = parser.parse(fixture("mercadona_real.txt"))
        assertEquals("1234-011-123456", t.invoice)
        assertEquals(java.time.LocalTime.of(18, 32), t.time)
    }

    @Test fun `Importe del pie como segundo total si el OCR pierde el TOTAL`() {
        val text = fixture("mercadona_real.txt").lines().filterNot { it.startsWith("TOTAL") }.joinToString("\n")
        assertEquals(1289L, parser.parse(text).totalCents)
    }

    @Test fun `texto en bloques - importe y peso en lineas sueltas`() {
        val t = parser.parse(fixture("mercadona_bloques.txt"))
        assertEquals(3, t.lines.size, lines(t))
        assertEquals(1000L, t.totalCents)
        assertTrue(t.balanced, lines(t))
        val huevos = t.lines.first { it.description.startsWith("HUEVOS") }
        assertEquals(2.0, huevos.qty); assertEquals(235L, huevos.unitPriceCents); assertEquals(470L, huevos.amountCents)
        val galeras = t.lines.first { it.description == "GALERAS" }
        assertEquals(0.450, galeras.weightKg); assertEquals(441L, galeras.amountCents)
    }

    @Test fun `descripciones con cifras dentro`() {
        val t = parser.parse(fixture("mercadona_cifras.txt"))
        assertEquals(6, t.lines.size, lines(t))
        assertTrue(t.balanced, lines(t))
        assertEquals(listOf("6 HUEVOS", "12 HUEVOS", "PACK 4X500 COLA", "C 0,0 TOSTADA", "PAN 30% INTEGRAL", "CERVEZA 6X0,33"), t.lines.map { it.description })
        assertEquals(2.0, t.lines[1].qty)
        assertEquals(1.0, t.lines[5].qty, "el 6X0,33 no es una cantidad: 6 x 0,33 no da 3,10")
    }

    @Test fun `comprobacion por linea delata un error de OCR`() {
        val ok = TicketLine("", "PLATANO", 1.0, 1.284, 199, 256, unitPriceRead = true)
        val bad = ok.copy(amountCents = 265)
        assertEquals(LineCheck.OK, ok.check)
        assertEquals(LineCheck.BAD, bad.check)
        assertEquals(LineCheck.UNKNOWN, TicketLine("", "PAN", 1.0, null, 95, 95).check)
    }
}

class TextAndUnitsTest {
    @Test fun `normalize - ela geminada, s barra y guiones`() {
        assertEquals("colleccio", Text.normalize("Col·lecció"))
        assertEquals("leche sin lactosa", Text.normalize("Leche s/lactosa"))
        assertEquals("aceite girasol", Text.normalize("Aceite gira-sol"))
    }

    @Test fun `formato del envase leido del nombre`() {
        assertEquals(6.0 to QtyUnit.L, Packs.fromText("LECHE SEMI PACK 6 X 1L"))
        assertEquals(4.0 to QtyUnit.UD, Packs.fromText("YOGUR GRIEGO 4U"))
        assertEquals(330.0 to QtyUnit.ML, Packs.fromText("Cerveza lata 33 cl"))
        assertEquals(5.28 to QtyUnit.L, Packs.fromText("Cerveza 33CL P16"))
        assertEquals(500.0 to QtyUnit.G, Packs.fromText("Café molido 500 g"))
        assertEquals(40.0 to QtyUnit.DOSIS, Packs.fromText("Detergente 40 lavados"))
        assertEquals(null, Packs.fromText("PAN RUSTICO"))
    }

    @Test fun `33 cl se guardan como cl y son 330 ml`() {
        val r = FreeTextParser(Taxonomy.bundled()).parseOne("33 cl de cerveza")!!
        assertEquals(QtyUnit.CL, r.unit); assertEquals(330.0, r.qty * r.unit.toBase)
    }
}
