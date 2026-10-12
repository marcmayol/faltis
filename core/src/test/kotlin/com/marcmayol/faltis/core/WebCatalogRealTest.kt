package com.marcmayol.faltis.core

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Prueba los lectores con respuestas reales de las webs, guardadas FUERA del repo (no se publican
 * datos de las cadenas). Sin la variable FALTIS_WEB_SAMPLES se salta.
 */
class WebCatalogRealTest {
    private val dir = System.getenv("FALTIS_WEB_SAMPLES")?.let(::File)

    @Test
    fun realResponsesParse() {
        assumeTrue(dir?.isDirectory == true)
        val files = dir!!.listFiles().orEmpty().filter { it.nameWithoutExtension in WebCatalog.chains }
        assumeTrue(files.isNotEmpty())
        for (f in files.sortedBy { it.name }) {
            val ps = WebCatalog.parse(f.nameWithoutExtension, f.readText())
            println("== ${f.name}: ${ps.size} productos, ${ps.count { it.netUnit == null }} sin cantidad")
            ps.take(8).forEach { println("   ${it.priceCents}c ${it.netQty} ${it.netUnit?.code} ${if (it.offer) "OFERTA " else ""}| ${it.name} | ${it.brand}") }
            check(ps.isNotEmpty()) { "${f.name} no da productos" }
        }
    }
}
