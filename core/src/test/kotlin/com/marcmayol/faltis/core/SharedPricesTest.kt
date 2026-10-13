package com.marcmayol.faltis.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class SharedPricesTest {
    private val day = LocalDate.of(2026, 10, 12)
    private fun share(chain: String, cp: String, lines: List<TicketLine>, on: LocalDate = day) = SharedPrices.fromTicket(chain, cp, on, lines, today = day)

    @Test fun `una linea normal se comparte con su precio por unidad`() {
        val r = share("mercadona", "07570", listOf(TicketLine("", "Leche Ent. Hcdo 1L", 2.0, null, 96, 192)))
        assertEquals(listOf(SharedLine("mercadona", "07570", "LECHE ENT. HCDO 1L", 96, "ud", PriceKind.REGULAR, day)), r)
    }

    @Test fun `sin precio unitario lo saca del importe y la cantidad`() {
        val r = share("eroski", "07570", listOf(TicketLine("", "ATUN CLARO P3", 3.0, null, null, 600)))
        assertEquals(200, r.single().priceCents)
    }

    @Test fun `al peso va por kg`() {
        val r = share("lidl", "", listOf(TicketLine("", "PLATANO", 1.0, 1.284, 199, 256)))
        assertEquals("kg", r.single().unit); assertEquals(199, r.single().priceCents); assertEquals("", r.single().postalCode)
    }

    @Test fun `con descuento comparte tambien el precio rebajado`() {
        val l = TicketLine("", "ACEITE OLIVA 1L", 1.0, null, 900, 900, discountCents = 200, discountKind = PriceKind.PROMO)
        val r = share("carrefour", "28001", listOf(l))
        assertEquals(listOf(900L to PriceKind.REGULAR, 700L to PriceKind.PROMO), r.map { it.priceCents to it.kind })
    }

    @Test fun `nada de una tienda sin cadena o de una cadena desconocida`() {
        val l = listOf(TicketLine("", "PAN", 1.0, null, 95, 95))
        assertTrue(share("otra", "07570", l).isEmpty())
        assertTrue(share("inventada", "07570", l).isEmpty())
    }

    @Test fun `un ticket de hace mas de 60 dias o del futuro no se comparte`() {
        val l = listOf(TicketLine("", "PAN BARRA", 1.0, null, 95, 95))
        assertTrue(share("dia", "07570", l, day.minusDays(61)).isEmpty())
        assertTrue(share("dia", "07570", l, day.plusDays(2)).isEmpty())
        assertEquals(1, share("dia", "07570", l, day.minusDays(60)).size)
    }

    @Test fun `un codigo postal mal escrito no se envia`() {
        assertEquals("", share("dia", "7570", listOf(TicketLine("", "PAN BARRA", 1.0, null, 95, 95))).single().postalCode)
    }

    @Test fun `lo que no es un producto o puede identificar a alguien se queda fuera`() {
        listOf(
            "TARJETA ****1234", "VISA 4111 1111", "CLIENTE: JUAN", "ATENDIDO POR MARIA", "NIF 12345678Z",
            "TEL. 971123456", "juan@correo.es", "TOTAL", "CAMBIO", "12", "AB",
            "PRODUCTO CON UN NOMBRE LARGUISIMO QUE NO CABE EN NINGUNA LINEA DE TICKET NORMAL",
        ).forEach { assertNull(SharedPrices.shareableText(it), it) }
        assertEquals("YOGUR NATURAL 4X125G", SharedPrices.shareableText("Yogur natural 4x125g"))
        assertEquals("CAFE MOLIDO", SharedPrices.shareableText("Café molido"))
    }

    @Test fun `precios absurdos no se envian y las lineas repetidas van una vez`() {
        val r = share("mercadona", "07570", listOf(
            TicketLine("", "REGALO", 1.0, null, 0, 0),
            TicketLine("", "TELEVISOR", 1.0, null, 60_000, 60_000),
            TicketLine("", "PAN BARRA", 1.0, null, 95, 95),
            TicketLine("", "PAN BARRA", 1.0, null, 95, 95),
        ))
        assertEquals(listOf("PAN BARRA"), r.map { it.text })
    }

    @Test fun `el cuerpo de la peticion lleva solo los campos que acepta el servidor`() {
        val l = SharedLine("mercadona", "07570", "LECHE ENT. HCDO 1L", 96, "ud", PriceKind.REGULAR, day)
        assertEquals(
            """{"v":1,"lines":[{"chain":"mercadona","cp":"07570","text":"LECHE ENT. HCDO 1L","price":96,"unit":"ud","kind":"REGULAR","day":"2026-10-12"}]}""",
            SharedPrices.payload(listOf(l)),
        )
    }
}
