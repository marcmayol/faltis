package com.marcmayol.faltis.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

class TrendsTest {
    private val tax = Taxonomy.bundled()
    init { PriceBook.zone = ZoneOffset.UTC }

    private fun prod(id: Long, type: String) = ProductRef(id, "mercadona", "P$id", null, true, type, null, emptySet(), 1.0, QtyUnit.UD, null, false)
    private fun obs(pid: Long, cents: Long, date: String, src: PriceSource = PriceSource.TICKET_PDF) =
        PriceObservation(pid, 1, "mercadona", cents, PriceKind.REGULAR, src, Instant.parse("${date}T12:00:00Z"))

    @Test fun `alerta contra la media, con 3 compras y mas margen en fruta`() {
        val leche = prod(1, "leche/entera")
        val platano = prod(2, "fruta/platano")
        val book = PriceBook(tax, listOf(leche, platano), listOf(
            obs(1, 100, "2026-07-01"), obs(1, 100, "2026-08-01"), obs(1, 120, "2026-09-01"), // +20 % sobre la media
            obs(2, 200, "2026-07-01"), obs(2, 200, "2026-08-01"), obs(2, 240, "2026-09-01"), // +20 %: fruta, no avisa
        ))
        val alerts = PriceTrends.alerts(book, tax)
        assertEquals(listOf(1L), alerts.map { it.product.id })
        assertEquals(0.20, alerts[0].pct, 1e-9)
    }

    @Test fun `con dos compras no hay alerta y un ticket repetido el mismo dia no cuenta`() {
        val leche = prod(1, "leche/entera")
        val book = PriceBook(tax, listOf(leche), listOf(obs(1, 100, "2026-08-01"), obs(1, 150, "2026-09-01"), obs(1, 150, "2026-09-01")))
        assertTrue(PriceTrends.alerts(book, tax).isEmpty())
        assertEquals(0.5, PriceTrends.biggestChanges(book).single().pct, 1e-9)
    }

    @Test fun `los datos de ejemplo nunca generan tendencias`() {
        val book = PriceBook(tax, listOf(prod(1, "leche")), listOf(
            obs(1, 100, "2026-07-01", PriceSource.SAMPLE), obs(1, 100, "2026-08-01", PriceSource.SAMPLE), obs(1, 200, "2026-09-01", PriceSource.SAMPLE),
        ))
        assertTrue(PriceTrends.alerts(book, tax).isEmpty())
        assertTrue(PriceTrends.biggestChanges(book).isEmpty())
    }

    @Test fun `inflacion personal de la cesta habitual`() {
        val ps = (1L..3L).map { prod(it, "leche") }
        val book = PriceBook(tax, ps, listOf(
            obs(1, 100, "2026-08-10"), obs(1, 110, "2026-09-10"),
            obs(2, 200, "2026-08-10"), obs(2, 200, "2026-09-12"),
            obs(3, 100, "2026-07-01"), obs(3, 90, "2026-09-20"),
        ))
        val inf = PriceTrends.personalInflation(book, YearMonth.of(2026, 9))!!
        assertEquals(3, inf.products)
        assertEquals(0.0, inf.pct, 1e-9) // 400 -> 400
        assertNull(PriceTrends.personalInflation(book, YearMonth.of(2026, 8)))
    }
}

class TypoTest {
    private val tax = Taxonomy.bundled()

    @Test fun `una errata se tolera en la lista pero no en el ticket`() {
        val m = tax.match("lehce")!!
        assertEquals("leche", m.type.id); assertTrue(m.approximate)
        assertEquals("yogur", tax.match("yougur")?.type?.id)
        assertNull(tax.matchTicketLine("LEHCE"))
    }

    @Test fun `sin falsos positivos en palabras cortas`() {
        assertNull(tax.match("velas de cumpleaños"))
        assertEquals(2, Text.distance("abcd", "acbe")) // trasposicion + cambio
    }
}
