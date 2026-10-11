package com.marcmayol.faltis.core

import java.time.Duration
import java.time.Instant
import kotlin.math.roundToLong
import kotlin.random.Random

/**
 * Datos de EJEMPLO (F-01): catalogo y precios ficticios, siempre marcados SAMPLE.
 * Deterministas (semilla fija) para que las pruebas y las capturas salgan iguales.
 */
object SampleData {

    data class SampleStore(val chainId: String, val name: String, val position: Int)
    data class SampleProduct(val tempId: Long, val ref: ProductRef)
    data class SampleNeed(val text: String, val typeId: String?, val qty: Double, val unit: QtyUnit, val restrictions: List<String> = emptyList())
    data class SampleObs(val productTempId: Long, val storeIndex: Int, val priceCents: Long, val daysAgo: Long, val kind: PriceKind = PriceKind.REGULAR)
    data class Catalog(
        val stores: List<SampleStore>,
        val products: List<SampleProduct>,
        val observations: List<SampleObs>,
        val listName: String,
        val needs: List<SampleNeed>,
    )

    val stores = listOf(
        SampleStore("mercadona", "Mercadona Artà", 0),
        SampleStore("eroski", "Eroski Manacor", 1),
        SampleStore("hipercentro", "Hiper Centro Artà", 2),
        SampleStore("carrefour", "Carrefour Manacor", 3),
    )

    private val privateLabel = mapOf(
        "mercadona" to "Hacendado", "eroski" to "Eroski Basic", "hipercentro" to "Selección HC", "carrefour" to "Carrefour",
    )

    /** Factor de precio por cadena (Eroski algo mas barata en este escenario ficticio). */
    private val chainFactor = mapOf("mercadona" to 1.0, "eroski" to 0.95, "hipercentro" to 1.03, "carrefour" to 0.99)

    /** tipo, nombre base, variante, neto, unidad, precio base en euros, tags. */
    private data class Item(val typeId: String, val name: String, val variant: String?, val net: Double?, val unit: QtyUnit, val price: Double, val tags: Set<String> = emptySet())

    private val items = listOf(
        Item("leche/entera", "Leche entera", "entera", 1.0, QtyUnit.L, 0.95),
        Item("leche/semidesnatada", "Leche semidesnatada", "semi", 1.0, QtyUnit.L, 0.92),
        Item("leche/sin-lactosa", "Leche sin lactosa", "sin lactosa", 1.0, QtyUnit.L, 1.15, setOf("sin_lactosa")),
        Item("huevo", "Huevos frescos L", "L", 12.0, QtyUnit.UD, 2.45),
        Item("yogur/griego", "Yogur griego", "natural", 4.0, QtyUnit.UD, 1.85),
        Item("mantequilla", "Mantequilla", null, 250.0, QtyUnit.G, 2.35),
        Item("queso/rallado", "Queso rallado", null, 200.0, QtyUnit.G, 1.95),
        Item("verdura/tomate", "Tomate pera", "pera", null, QtyUnit.KG, 1.89),
        Item("fruta/platano", "Plátano de Canarias", null, null, QtyUnit.KG, 2.19),
        Item("fruta/manzana", "Manzana golden", "golden", null, QtyUnit.KG, 1.99),
        Item("verdura/patata", "Patatas", null, 3.0, QtyUnit.KG, 3.49),
        Item("verdura/cebolla", "Cebollas", null, 1.0, QtyUnit.KG, 1.29),
        Item("pan/barra", "Barra de pan", null, 1.0, QtyUnit.UD, 0.60),
        Item("pan/molde", "Pan de molde", null, 460.0, QtyUnit.G, 1.35),
        Item("bolleria/croissant", "Croissant de mantequilla", "mantequilla", 50.0, QtyUnit.G, 0.55),
        Item("desayuno/cafe-molido", "Café molido natural", "natural", 250.0, QtyUnit.G, 2.95),
        Item("desayuno/galletas", "Galletas María", "maria", 800.0, QtyUnit.G, 1.65),
        Item("despensa/aceite-oliva", "Aceite de oliva virgen extra", "virgen extra", 1.0, QtyUnit.L, 8.75),
        Item("despensa/arroz", "Arroz redondo", "redondo", 1.0, QtyUnit.KG, 1.25),
        Item("despensa/pasta-macarron", "Macarrones", null, 500.0, QtyUnit.G, 0.79),
        Item("despensa/tomate-frito", "Tomate frito", null, 400.0, QtyUnit.G, 0.85),
        Item("carne/pollo-pechuga", "Pechuga de pollo fileteada", null, null, QtyUnit.KG, 7.49),
        Item("carne/picada", "Carne picada mixta", null, 500.0, QtyUnit.G, 3.65),
        Item("bebida/agua", "Agua mineral", null, 1.5, QtyUnit.L, 0.35),
        Item("limpieza/papel-higienico", "Papel higiénico doble capa", null, 12.0, QtyUnit.PACK, 4.20),
        Item("limpieza/lavavajillas", "Lavavajillas a mano", null, 750.0, QtyUnit.ML, 1.75),
    )

    /** Productos de marca (mismo EAN en varias cadenas -> Identico). */
    private data class Branded(val typeId: String, val name: String, val brand: String, val ean: String, val net: Double, val unit: QtyUnit, val price: Double, val chains: List<String>)

    private val branded = listOf(
        Branded("leche/entera", "Leche entera Pascual", "Pascual", "8410179000013", 1.0, QtyUnit.L, 1.29, listOf("mercadona", "eroski", "hipercentro", "carrefour")),
        Branded("desayuno/cafe-molido", "Café molido natural Saimaza", "Saimaza", "8711000300016", 250.0, QtyUnit.G, 3.79, listOf("eroski", "hipercentro", "carrefour")),
        Branded("desayuno/cacao", "Cola Cao original", "Cola Cao", "8410014311307", 760.0, QtyUnit.G, 5.45, listOf("mercadona", "eroski", "hipercentro", "carrefour")),
        Branded("bolleria/croissant", "Mini croissant relleno de cacao", "El Molino", "8410000000999", 55.0, QtyUnit.G, 0.25, listOf("eroski")),
    )

    /** Tipos que NO vende cada cadena en el ejemplo, para que la cobertura sea 100/93/87/53 %. */
    private val missingByChain = mapOf(
        "mercadona" to emptySet(),
        "eroski" to setOf("verdura/tomate"),
        "hipercentro" to setOf("verdura/tomate", "desayuno/galletas"),
        "carrefour" to setOf(
            "verdura/tomate", "desayuno/galletas", "fruta/platano", "yogur/griego", "pan/barra",
            "carne/pollo-pechuga", "limpieza/papel-higienico",
        ),
    )

    val needs = listOf(
        SampleNeed("Leche entera", "leche/entera", 3.0, QtyUnit.L),
        SampleNeed("Huevos", "huevo", 12.0, QtyUnit.UD),
        SampleNeed("Tomate", "verdura/tomate", 1.0, QtyUnit.KG),
        SampleNeed("Plátano", "fruta/platano", 1.0, QtyUnit.KG),
        SampleNeed("Pan de barra", "pan/barra", 2.0, QtyUnit.UD),
        SampleNeed("Yogur griego", "yogur/griego", 4.0, QtyUnit.UD),
        SampleNeed("Café molido", "desayuno/cafe-molido", 1.0, QtyUnit.UD),
        SampleNeed("Aceite de oliva", "despensa/aceite-oliva", 1.0, QtyUnit.L),
        SampleNeed("Pechuga de pollo", "carne/pollo-pechuga", 1.0, QtyUnit.KG),
        SampleNeed("Arroz", "despensa/arroz", 1.0, QtyUnit.KG),
        SampleNeed("Macarrones", "despensa/pasta-macarron", 500.0, QtyUnit.G),
        SampleNeed("Galletas", "desayuno/galletas", 1.0, QtyUnit.UD),
        SampleNeed("Papel higiénico", "limpieza/papel-higienico", 1.0, QtyUnit.PACK),
        SampleNeed("Agua", "bebida/agua", 6.0, QtyUnit.L),
        SampleNeed("Queso rallado", "queso/rallado", 1.0, QtyUnit.UD),
    )

    fun build(seed: Int = 7): Catalog {
        val rnd = Random(seed)
        val products = mutableListOf<SampleProduct>()
        val obs = mutableListOf<SampleObs>()
        var id = 1L
        stores.forEachIndexed { storeIndex, s ->
            val brand = privateLabel.getValue(s.chainId)
            val factor = chainFactor.getValue(s.chainId)
            for (it in items) {
                if (it.typeId in missingByChain.getValue(s.chainId)) continue
                val ref = ProductRef(
                    id = id, chainId = s.chainId, name = "${it.name} $brand", brand = brand, isPrivateLabel = true,
                    typeId = it.typeId, variant = it.variant, tags = it.tags, netQty = it.net,
                    netUnit = if (it.net == null) it.unit else it.unit, ean = null, isSample = true,
                )
                products += SampleProduct(id, ref)
                val noise = 0.92 + rnd.nextDouble() * 0.16
                val price = (it.price * factor * noise * 100).roundToLong()
                // Hiper Centro lleva precios mas viejos para que salga el sello de desfasado.
                val age = if (s.chainId == "hipercentro" && rnd.nextInt(4) == 0) 35L + rnd.nextInt(10) else 1L + rnd.nextInt(12)
                obs += SampleObs(id, storeIndex, price, age)
                id++
            }
            for (b in branded) {
                if (s.chainId !in b.chains) continue
                val ref = ProductRef(
                    id = id, chainId = s.chainId, name = b.name, brand = b.brand, isPrivateLabel = false,
                    typeId = b.typeId, variant = if (b.brand == "El Molino") "relleno cacao" else null,
                    tags = if (b.brand == "El Molino") setOf("relleno") else emptySet(),
                    netQty = b.net, netUnit = b.unit, ean = b.ean, isSample = true,
                )
                products += SampleProduct(id, ref)
                val price = (b.price * (0.97 + rnd.nextDouble() * 0.08) * 100).roundToLong()
                obs += SampleObs(id, storeIndex, price, 2L + rnd.nextInt(10))
                id++
            }
        }
        return Catalog(stores, products, obs, "Compra (ejemplo)", needs)
    }

    fun instantDaysAgo(now: Instant, days: Long): Instant = now.minus(Duration.ofDays(days))
}
