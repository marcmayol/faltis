package com.marcmayol.faltis.data

import com.marcmayol.faltis.core.WebProduct
import com.marcmayol.faltis.core.OpenFoodFacts
import com.marcmayol.faltis.core.WebCatalog
import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.room.Room
import androidx.room.withTransaction
import com.marcmayol.faltis.core.AbbreviationLookup
import com.marcmayol.faltis.core.AisleLearner
import com.marcmayol.faltis.core.FreeTextParser
import com.marcmayol.faltis.core.NeedRef
import com.marcmayol.faltis.core.NeedState
import com.marcmayol.faltis.core.ParsedTicket
import com.marcmayol.faltis.core.Packs
import com.marcmayol.faltis.core.SharedPrices
import com.marcmayol.faltis.core.PriceBook
import com.marcmayol.faltis.core.ProductMatcher
import com.marcmayol.faltis.core.PriceKind
import com.marcmayol.faltis.core.PriceObservation
import com.marcmayol.faltis.core.PriceSource
import com.marcmayol.faltis.core.ProductRef
import com.marcmayol.faltis.core.QtyUnit
import com.marcmayol.faltis.core.SampleData
import com.marcmayol.faltis.core.StoreRef
import com.marcmayol.faltis.core.Taxonomy
import com.marcmayol.faltis.core.Text
import com.marcmayol.faltis.core.TicketLine
import com.marcmayol.faltis.core.TicketParser
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToLong

/** Productos web que se guardan por necesidad y cadena: los que mejor casan con el tipo. */
private const val MAX_WEB_PER_NEED = 6

private val Context.dataStore by preferencesDataStore("faltis")

data class Settings(
    val onboardingDone: Boolean = false,
    val activeListId: Long = 0,
    val selectedStoreId: Long = 0,
    val includePromos: Boolean = false,
    val budgetCents: Long = 0,
    val themeMode: com.marcmayol.faltis.ui.theme.ThemeMode = com.marcmayol.faltis.ui.theme.ThemeMode.SYSTEM,
    /** Capa D: precios de las tiendas online. Desactivado de serie: usa internet. */
    val webPrices: Boolean = false,
    val postalCode: String = "",
    /** Zona de precios de cada cadena para [postalCode] (almacen, tienda, region), ya resuelta. */
    val webZones: Map<String, String> = emptyMap(),
    /** Capa C (PLAN §3.3): compartir los precios de los tickets, sin datos personales. Activado de serie. */
    val sharePrices: Boolean = true,
    /** Ya se ha explicado lo que se comparte (sale con el primer ticket). */
    val shareNoticeShown: Boolean = false,
)

@Serializable
private data class Reconciliation(val previous: List<NeedSnapshot>, val created: List<Long>)

@Serializable
private data class NeedSnapshot(val id: Long, val state: String, val purchaseId: Long?, val cartOrder: Int?)

/** Una linea de ticket ya revisada, lista para guardar. */
data class ReviewedLine(val line: TicketLine, val isItem: Boolean = true)

/** Propuesta de cuadre (P-10). */
data class ReconcileProposal(
    val toBuy: List<NeedEntity>,
    val stillPending: List<NeedEntity>,
    val outsideList: List<TicketLine>,
)

/**
 * Webhook de n8n en el VPS que recibe los precios compartidos (servidor/ en el repo).
 * La version de pruebas usa la URL de prueba de n8n, que solo escucha con el editor
 * abierto: asi los tickets de prueba nunca llegan a la base de precios real.
 */
private val SHARE_URL = "https://vps24874.cubepath.net/" +
    (if (com.marcmayol.faltis.BuildConfig.DEBUG) "webhook-test" else "webhook") + "/faltis-precios"
private const val SHARE_DELAY_MILLIS = 60 * 60 * 1000L

class Repository(private val context: Context) {
    val taxonomy: Taxonomy = Taxonomy.bundled()
    val textParser = FreeTextParser(taxonomy)
    val ticketParser = TicketParser(taxonomy)

    private val db = Room.databaseBuilder(context, FaltisDb::class.java, "faltis.db").addMigrations(FaltisDb.MIGRATION_1_2, FaltisDb.MIGRATION_2_3, FaltisDb.MIGRATION_3_4, FaltisDb.MIGRATION_4_5).build()
    val dao = db.dao()
    private val json = Json { ignoreUnknownKeys = true }

    // ── Ajustes ──
    private object K {
        val onboarding = booleanPreferencesKey("onboarding_done")
        val activeList = longPreferencesKey("active_list")
        val selectedStore = longPreferencesKey("selected_store")
        val promos = booleanPreferencesKey("include_promos")
        val budget = longPreferencesKey("budget_cents")
        val theme = androidx.datastore.preferences.core.stringPreferencesKey("theme_mode")
        val webPrices = booleanPreferencesKey("web_prices")
        val postalCode = androidx.datastore.preferences.core.stringPreferencesKey("postal_code")
        val mercadonaWh = androidx.datastore.preferences.core.stringPreferencesKey("mercadona_wh")
        val webZones = androidx.datastore.preferences.core.stringPreferencesKey("web_zones")
        val sharePrices = booleanPreferencesKey("share_prices")
        val shareNotice = booleanPreferencesKey("share_notice_shown")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p: Preferences ->
        Settings(
            onboardingDone = p[K.onboarding] ?: false,
            activeListId = p[K.activeList] ?: 0,
            selectedStoreId = p[K.selectedStore] ?: 0,
            includePromos = p[K.promos] ?: false,
            budgetCents = p[K.budget] ?: 0,
            themeMode = p[K.theme]?.let { runCatching { com.marcmayol.faltis.ui.theme.ThemeMode.valueOf(it) }.getOrNull() } ?: com.marcmayol.faltis.ui.theme.ThemeMode.SYSTEM,
            webPrices = p[K.webPrices] ?: false,
            postalCode = p[K.postalCode] ?: "",
            // El almacen de Mercadona se guardaba aparte hasta la 0.4.
            webZones = decodeZones(p[K.webZones]) + listOfNotNull(p[K.mercadonaWh]?.takeIf { it.isNotBlank() }?.let { "mercadona" to it }),
            sharePrices = p[K.sharePrices] ?: true,
            shareNoticeShown = p[K.shareNotice] ?: false,
        )
    }

    suspend fun setOnboardingDone() = context.dataStore.edit { it[K.onboarding] = true }
    suspend fun setActiveList(id: Long) = context.dataStore.edit { it[K.activeList] = id }
    suspend fun setSelectedStore(id: Long) = context.dataStore.edit { it[K.selectedStore] = id }
    suspend fun setIncludePromos(v: Boolean) = context.dataStore.edit { it[K.promos] = v }
    suspend fun setBudget(cents: Long) = context.dataStore.edit { it[K.budget] = cents }
    suspend fun setTheme(mode: com.marcmayol.faltis.ui.theme.ThemeMode) = context.dataStore.edit { it[K.theme] = mode.name }
    suspend fun setWebPrices(on: Boolean, postalCode: String? = null) = context.dataStore.edit {
        it[K.webPrices] = on
        if (postalCode != null && postalCode != it[K.postalCode]) { it[K.postalCode] = postalCode; it.remove(K.mercadonaWh); it.remove(K.webZones) }
    }

    /** Al desactivarlo se vacia la cola: lo que no se ha enviado ya no se envia. */
    suspend fun setSharePrices(on: Boolean) {
        context.dataStore.edit { it[K.sharePrices] = on; it[K.shareNotice] = true }
        if (!on) dao.clearShareLines()
    }
    suspend fun setShareNoticeShown() = context.dataStore.edit { it[K.shareNotice] = true }

    /**
     * Envia la cola de precios compartidos. Solo salen las lineas con mas de
     * [SHARE_DELAY_MILLIS], para que no lleguen al servidor justo tras la compra;
     * van en orden aleatorio y mezcladas con las de otros tickets si los hay.
     * Devuelve las enviadas; si falla la red, se quedan para la proxima vez.
     */
    suspend fun sendSharedPrices(): Int {
        if (!settings.first().sharePrices) return 0
        var sent = 0
        while (true) {
            val batch = dao.shareLinesReady(now() - SHARE_DELAY_MILLIS, SharedPrices.MAX_PER_REQUEST)
            if (batch.isEmpty()) return sent
            val body = SharedPrices.payload(batch.map {
                com.marcmayol.faltis.core.SharedLine(
                    it.chainId, it.postalCode, it.text, it.priceCents, it.unit, PriceKind.valueOf(it.kind), LocalDate.parse(it.day),
                )
            })
            postShared(body)
            dao.deleteShareLines(batch.map { it.id })
            sent += batch.size
            if (batch.size < SharedPrices.MAX_PER_REQUEST) return sent
        }
    }

    private suspend fun postShared(body: String) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val c = (java.net.URL(SHARE_URL).openConnection() as java.net.HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 15_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            c.outputStream.use { it.write(body.toByteArray()) }
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            // El webhook contesta {"ok":false} si el formato no le vale: no se reintenta, se descarta.
            c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    private suspend fun saveZone(chain: String, zone: String) = context.dataStore.edit {
        it[K.webZones] = encodeZones(decodeZones(it[K.webZones]) + (chain to zone))
    }

    private fun decodeZones(raw: String?): Map<String, String> =
        raw.orEmpty().split(';').mapNotNull { e -> e.split('=', limit = 2).takeIf { it.size == 2 && it[1].isNotBlank() }?.let { it[0] to it[1] } }.toMap()

    private fun encodeZones(m: Map<String, String>) = m.entries.joinToString(";") { "${it.key}=${it.value}" }

    // ── Conversion a modelos de :core ──
    fun ProductEntity.toRef() = ProductRef(
        id, chainId, name, brand, isPrivateLabel, typeId, variant,
        tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        netQty, QtyUnit.fromCode(netUnit), ean, isSample, imageUrl, isFavorite,
    )

    fun PriceObsEntity.toObs() = PriceObservation(
        productId, storeId, chainId, priceCents, PriceKind.valueOf(kind), PriceSource.valueOf(source),
        Instant.ofEpochMilli(observedAtMillis),
    )

    fun StoreEntity.toRef() = StoreRef(id, chainId, name, position)

    fun NeedEntity.toRef() = NeedRef(
        id, text, typeId, qty, QtyUnit.fromCode(unit) ?: QtyUnit.UD,
        restrictions.split('|').filter { it.isNotBlank() }, pinnedProductId, NeedState.valueOf(state),
    )

    /** Libro de precios reactivo: se reconstruye cuando cambia el catalogo o los precios. */
    val priceBook: Flow<PriceBook> = combine(dao.allProducts(), dao.allObs()) { products, obs ->
        PriceBook(taxonomy, products.map { it.toRef() }, obs.map { it.toObs() })
    }

    // ── Listas ──
    suspend fun ensureActiveList(): Long {
        val s = settings.first()
        val lists = dao.listsNow()
        if (lists.any { it.id == s.activeListId }) return s.activeListId
        val id = lists.firstOrNull()?.id ?: dao.insertList(ListEntity(name = "Compra", createdAtMillis = now()))
        setActiveList(id)
        return id
    }

    suspend fun createList(name: String): Long {
        val id = dao.insertList(ListEntity(name = name.ifBlank { "Lista" }, createdAtMillis = now()))
        setActiveList(id)
        return id
    }

    /** La lista de ese super; si no tiene, la crea con su nombre (sin cambiar la lista activa). */
    suspend fun storeList(store: StoreEntity): ListEntity =
        dao.listsNow().firstOrNull { it.storeId == store.id }
            ?: ListEntity(name = store.name, createdAtMillis = now(), storeId = store.id).let { it.copy(id = dao.insertList(it)) }

    /** F-02.3-4 · Añade desde texto libre fusionando con las abiertas compatibles. Devuelve avisos de fusion. */
    suspend fun addFromText(listId: Long, input: String): List<String> {
        val parsed = textParser.parse(input)
        val notes = mutableListOf<String>()
        var pos = dao.maxPosition(listId)
        for (p in parsed) {
            val open = dao.openNeedsNow(listId)
            val restr = p.restrictions.joinToString("|")
            val match = open.firstOrNull { n ->
                val sameKey = if (p.typeId != null) n.typeId == p.typeId else Text.normalize(n.text) == Text.normalize(p.text)
                val nUnit = QtyUnit.fromCode(n.unit) ?: QtyUnit.UD
                sameKey && n.restrictions == restr && n.pinnedProductId == null && nUnit.dimension == p.unit.dimension
            }
            if (match != null) {
                val nUnit = QtyUnit.fromCode(match.unit) ?: QtyUnit.UD
                val added = p.qty * p.unit.toBase / nUnit.toBase
                val newQty = match.qty + added
                dao.updateNeed(match.copy(qty = newQty, updatedAtMillis = now()))
                notes += "${match.text}: ahora ${formatQty(newQty)} ${nUnit.code}"
            } else {
                dao.insertNeed(
                    NeedEntity(
                        listId = listId, text = p.text, typeId = p.typeId, qty = p.qty, unit = p.unit.code,
                        restrictions = restr, position = ++pos, addedAtMillis = now(), updatedAtMillis = now(),
                    ),
                )
            }
        }
        return notes
    }

    /** Añade una necesidad con el producto ya fijado (desde la ficha). */
    suspend fun addPinned(listId: Long, name: String, typeId: String?, productId: Long) {
        dao.insertNeed(
            NeedEntity(
                listId = listId, text = name, typeId = typeId, pinnedProductId = productId,
                position = dao.maxPosition(listId) + 1, addedAtMillis = now(), updatedAtMillis = now(),
            ),
        )
    }

    suspend fun updateNeed(n: NeedEntity) = dao.updateNeed(n.copy(updatedAtMillis = now()))

    /** Pasa la necesidad al final de otra lista. */
    suspend fun moveNeed(n: NeedEntity, listId: Long) =
        dao.updateNeed(n.copy(listId = listId, position = dao.maxPosition(listId) + 1, updatedAtMillis = now()))
    suspend fun deleteNeed(id: Long): NeedEntity? { val n = dao.need(id); dao.deleteNeed(id); return n }
    suspend fun restoreNeed(n: NeedEntity) = dao.insertNeed(n)

    /** F-08.2 · Toque en el modo tienda. */
    suspend fun toggleCart(need: NeedEntity, listId: Long) {
        if (need.state == NeedState.IN_CART.name) {
            dao.updateNeed(need.copy(state = NeedState.PENDING.name, cartOrder = null, updatedAtMillis = now()))
        } else {
            val next = (dao.openNeedsNow(listId).mapNotNull { it.cartOrder }.maxOrNull() ?: 0) + 1
            dao.updateNeed(need.copy(state = NeedState.IN_CART.name, cartOrder = next, updatedAtMillis = now()))
        }
    }

    private fun aisleKey(n: NeedEntity) = n.typeId ?: Text.normalize(n.text)

    /** F-08.3 · Aprende el orden de pasillos con lo que hay en el carro. */
    private suspend fun learnAisles(storeId: Long, inCart: List<NeedEntity>) {
        val ordered = inCart.sortedBy { it.cartOrder ?: Int.MAX_VALUE }.map { aisleKey(it) }.distinct()
        if (ordered.isEmpty()) return
        val prev = dao.aisleNow(storeId).associate { it.key to AisleLearner.Entry(it.avgPos, it.samples) }
        val updated = AisleLearner.learn(prev, ordered)
        dao.upsertAisle(updated.map { (k, e) -> AisleOrderEntity(storeId, k, e.avgPos, e.samples) })
    }

    fun aisleEntries(storeId: Long): Flow<Map<String, AisleLearner.Entry>> =
        dao.aisle(storeId).map { l -> l.associate { it.key to AisleLearner.Entry(it.avgPos, it.samples) } }

    fun keyFor(n: NeedEntity) = aisleKey(n)

    /** F-08.4 · Terminar sin ticket: compra sin importe. */
    suspend fun finishWithoutTicket(listId: Long, storeId: Long): Long = db.withTransaction {
        val store = dao.store(storeId) ?: error("Tienda desconocida")
        val inCart = dao.openNeedsNow(listId).filter { it.state == NeedState.IN_CART.name }
        learnAisles(storeId, inCart)
        val pid = dao.insertPurchase(
            PurchaseEntity(
                storeId = storeId, chainId = store.chainId, dateEpochDay = LocalDate.now().toEpochDay(),
                source = "NONE", createdAtMillis = now(),
            ),
        )
        dao.insertLines(inCart.map { PurchaseLineEntity(purchaseId = pid, rawText = it.text, description = it.text, qty = it.qty, typeId = it.typeId) })
        dao.updateNeeds(inCart.map { it.copy(state = NeedState.BOUGHT.name, purchaseId = pid, updatedAtMillis = now()) })
        pid
    }

    /** Aprende pasillos antes de pasar al ticket (el cuadre los marcara como comprados). */
    suspend fun learnFromCart(listId: Long, storeId: Long) {
        learnAisles(storeId, dao.openNeedsNow(listId).filter { it.state == NeedState.IN_CART.name })
    }

    // ── Tickets ──
    suspend fun parseTicket(text: String, chainHint: String?): ParsedTicket {
        val abbr = dao.abbreviations()
        val lookup = AbbreviationLookup { chain, key ->
            abbr.firstOrNull { it.chainId == chain && it.rawKey == key }?.let { it.productId to it.typeId }
        }
        return ticketParser.parse(text, chainHint, lookup)
    }

    fun reassign(line: TicketLine, chainId: String?): TicketLine = ticketParser.assign(line, chainId, null)

    /** F-09 · Propuesta de cuadre: las necesidades abiertas cuyo tipo cubre el de alguna linea. */
    suspend fun proposeReconcile(listId: Long, lines: List<TicketLine>): ReconcileProposal {
        val open = dao.openNeedsNow(listId)
        val used = mutableSetOf<Int>()
        val toBuy = mutableListOf<NeedEntity>()
        for (n in open) {
            val nt = taxonomy.byId(n.typeId)
            val idx = lines.indices.firstOrNull { i ->
                i !in used && run {
                    val l = lines[i]
                    (n.pinnedProductId != null && n.pinnedProductId == l.productId) ||
                        (nt != null && taxonomy.byId(l.typeId)?.let { nt.covers(it) } == true) ||
                        (nt == null && Text.normalize(l.description).contains(Text.normalize(n.text)))
                }
            }
            if (idx != null) { used += idx; toBuy += n }
        }
        val pending = open - toBuy.toSet()
        return ReconcileProposal(toBuy, pending, lines.filterIndexed { i, _ -> i !in used })
    }

    /** Mismo ticket ya guardado: por numero de factura si lo hay; si no, por cadena, fecha y total. */
    suspend fun findDuplicate(chainId: String, date: LocalDate, totalCents: Long?, invoice: String? = null): Boolean {
        if (invoice != null) return dao.findByInvoice(chainId, invoice) != null
        return totalCents != null && dao.findDuplicate(chainId, date.toEpochDay(), totalCents) != null
    }

    /** F-09 · Guarda la compra en una sola transaccion: lineas, productos de ticket, observaciones y cuadre. */
    suspend fun saveTicket(
        storeId: Long,
        chainId: String,
        date: LocalDate,
        totalReadCents: Long?,
        source: PriceSource,
        lines: List<ReviewedLine>,
        needsToBuy: List<NeedEntity>,
        listId: Long,
        invoice: String? = null,
        time: java.time.LocalTime? = null,
    ): Long = db.withTransaction {
        // Una linea añadida a mano y dejada vacia no es nada.
        val items = lines.filter { it.isItem }.map { it.line }.filter { it.description.isNotBlank() || it.amountCents != 0L }
        val linesTotal = items.sumOf { it.netCents }
        val pid = dao.insertPurchase(
            PurchaseEntity(
                storeId = storeId, chainId = chainId, dateEpochDay = date.toEpochDay(), totalReadCents = totalReadCents,
                totalLinesCents = linesTotal, source = source.name, createdAtMillis = now(),
                invoice = invoice, timeMinutes = time?.let { it.hour * 60 + it.minute },
            ),
        )
        val observedAt = date.atTime(12, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val created = mutableListOf<Long>()
        val lineEntities = mutableListOf<PurchaseLineEntity>()
        for (l in items) {
            var productId = l.productId
            if (productId == null && l.typeId != null) {
                val t = taxonomy.byId(l.typeId)
                // Formato leido del nombre ("LECHE SEMI 6X1L", "COLA 33CL P16"); al peso, precio por kg a granel.
                val format = if (l.weightKg != null) null else Packs.fromText(l.description)
                productId = dao.insertProduct(
                    ProductEntity(
                        chainId = chainId, name = l.description.lowercase().replaceFirstChar { it.uppercase() },
                        nameNorm = Text.normalize(l.description), typeId = l.typeId, fromTicket = true,
                        netQty = format?.first,
                        netUnit = if (l.weightKg != null) QtyUnit.KG.code else format?.second?.code,
                        isPrivateLabel = ProductMatcher.owners(ProductMatcher.nameWords(ProductMatcher.plain(l.description))).isNotEmpty(),
                        variant = t?.name,
                    ),
                )
            }
            if (productId != null) {
                val unitGross = l.unitPriceCents ?: l.amountCents
                dao.insertObs(
                    PriceObsEntity(
                        productId = productId, storeId = storeId, chainId = chainId, priceCents = unitGross,
                        kind = PriceKind.REGULAR.name, source = source.name, observedAtMillis = observedAt, purchaseId = pid,
                    ),
                )
                if (l.discountCents > 0 && l.discountKind != null) {
                    dao.insertObs(
                        PriceObsEntity(
                            productId = productId, storeId = storeId, chainId = chainId,
                            priceCents = l.netUnitPriceCents ?: (l.netCents),
                            kind = l.discountKind!!.name, source = source.name, observedAtMillis = observedAt, purchaseId = pid,
                        ),
                    )
                }
                if (l.typeId != null || l.productId != null) {
                    dao.upsertAbbreviation(AbbreviationEntity(chainId, l.key, productId, l.typeId))
                }
            }
            lineEntities += PurchaseLineEntity(
                purchaseId = pid, rawText = l.raw, description = l.description, qty = l.qty, weightKg = l.weightKg,
                amountCents = l.amountCents, discountCents = l.discountCents, kind = (l.discountKind ?: PriceKind.REGULAR).name,
                typeId = l.typeId, productId = productId, confidence = l.confidence.name,
            )
        }
        dao.insertLines(lineEntities)
        // Precios para compartir (PLAN §3.3): la cola solo guarda lo que se enviaria.
        val s = settings.first()
        val shared = if (s.sharePrices) SharedPrices.fromTicket(chainId, s.postalCode, date, items) else emptyList()
        if (shared.isNotEmpty()) dao.insertShareLines(shared.map {
            ShareLineEntity(
                purchaseId = pid, chainId = it.chainId, postalCode = it.postalCode, text = it.text, priceCents = it.priceCents,
                unit = it.unit, kind = it.kind.name, day = it.day.toString(), createdAtMillis = now(),
            )
        })
        // Cuadre con la lista, guardando el estado anterior para poder revertirlo.
        val snapshot = needsToBuy.map { NeedSnapshot(it.id, it.state, it.purchaseId, it.cartOrder) }
        dao.updateNeeds(needsToBuy.map { it.copy(state = NeedState.BOUGHT.name, purchaseId = pid, updatedAtMillis = now()) })
        val rec = json.encodeToString(Reconciliation.serializer(), Reconciliation(snapshot, created))
        dao.updatePurchase(dao.purchase(pid)!!.copy(reconciliationJson = rec))
        pid
    }

    /** Revertir el cuadre de una compra: las necesidades vuelven a como estaban. */
    suspend fun revertReconcile(purchaseId: Long) = db.withTransaction {
        val p = dao.purchase(purchaseId) ?: return@withTransaction
        val rec = p.reconciliationJson?.let { json.decodeFromString(Reconciliation.serializer(), it) }
        if (rec == null) { dao.reopenNeedsOf(purchaseId); return@withTransaction }
        for (s in rec.previous) {
            val n = dao.need(s.id) ?: continue
            dao.updateNeed(n.copy(state = s.state, purchaseId = s.purchaseId, cartOrder = s.cartOrder, updatedAtMillis = now()))
        }
        dao.updatePurchase(p.copy(reconciliationJson = null))
    }

    suspend fun deletePurchase(purchaseId: Long) = db.withTransaction {
        dao.reopenNeedsOf(purchaseId)
        dao.deleteObsOfPurchase(purchaseId)
        dao.deleteLines(purchaseId)
        dao.deleteShareLinesOf(purchaseId)
        dao.deletePurchase(purchaseId)
    }

    // ── Tiendas ──
    suspend fun addStore(chainId: String, name: String): Long {
        val pos = (dao.favoriteStoresNow().maxOfOrNull { it.position } ?: -1) + 1
        val id = dao.insertStore(StoreEntity(chainId = chainId, name = name, position = pos))
        if (settings.first().selectedStoreId == 0L) setSelectedStore(id)
        return id
    }

    /** Quitar de "mis tiendas": nunca se borra si tiene historial (F-15). */
    suspend fun hideStore(s: StoreEntity) = dao.updateStore(s.copy(isFavorite = false))

    /** Cambia el nombre de la tienda y el de su lista, si la lista aun se llama como ella. */
    suspend fun renameStore(s: StoreEntity, name: String) {
        dao.updateStore(s.copy(name = name))
        dao.listsNow().firstOrNull { it.storeId == s.id && it.name == s.name }?.let { dao.updateList(it.copy(name = name)) }
    }

    suspend fun moveStore(s: StoreEntity, delta: Int) {
        val list = dao.favoriteStoresNow().toMutableList()
        val i = list.indexOfFirst { it.id == s.id }
        val j = i + delta
        if (i < 0 || j !in list.indices) return
        val a = list[i]; val b = list[j]
        dao.updateStore(a.copy(position = b.position)); dao.updateStore(b.copy(position = a.position))
        if (a.position == b.position) dao.updateStore(a.copy(position = b.position + delta))
    }

    // ── Datos de ejemplo ──
    suspend fun loadSample() = db.withTransaction {
        val cat = SampleData.build()
        val now = Instant.now()
        val storeIds = cat.stores.map { s ->
            dao.insertStore(StoreEntity(chainId = s.chainId, name = s.name, position = s.position, isSample = true))
        }
        val idMap = HashMap<Long, Long>()
        for (sp in cat.products) {
            val r = sp.ref
            idMap[sp.tempId] = dao.insertProduct(
                ProductEntity(
                    chainId = r.chainId, name = r.name, nameNorm = Text.normalize(r.name), brand = r.brand,
                    isPrivateLabel = r.isPrivateLabel, typeId = r.typeId, variant = r.variant, tags = r.tags.joinToString(","),
                    netQty = r.netQty, netUnit = r.netUnit?.code, ean = r.ean, isSample = true,
                ),
            )
        }
        dao.insertObsAll(
            cat.observations.map { o ->
                val storeId = storeIds[o.storeIndex]
                PriceObsEntity(
                    productId = idMap.getValue(o.productTempId), storeId = storeId, chainId = cat.stores[o.storeIndex].chainId,
                    priceCents = o.priceCents, kind = o.kind.name, source = PriceSource.SAMPLE.name,
                    observedAtMillis = SampleData.instantDaysAgo(now, o.daysAgo).toEpochMilli(),
                )
            },
        )
        val listId = dao.insertList(ListEntity(name = cat.listName, createdAtMillis = now(), isSample = true))
        dao.insertNeeds(
            cat.needs.mapIndexed { i, n ->
                NeedEntity(
                    listId = listId, text = n.text, typeId = n.typeId, qty = n.qty, unit = n.unit.code,
                    restrictions = n.restrictions.joinToString("|"), position = i, addedAtMillis = now(), updatedAtMillis = now(),
                )
            },
        )
        setActiveList(listId)
        setSelectedStore(storeIds.first())
    }

    // ── Precios web (capa D) ──
    private val web = WebPrices()

    /**
     * Trae de la web los precios de [needs] en las cadenas de "mis tiendas" que los publican.
     * Se guardan como precio de la CADENA (sin tienda): el de un ticket de tu tienda siempre gana.
     * Sin [force], se salta lo traido hace menos de un dia. Devuelve cuantos precios ha guardado.
     */
    suspend fun refreshWebPrices(needs: List<NeedEntity>, force: Boolean = false, everywhere: Boolean = false): Int {
        val s = settings.first()
        if (!s.webPrices || !WebCatalog.isPostalCode(s.postalCode)) return 0
        val chains = if (everywhere) WebCatalog.chains.toList()
            else dao.favoriteStoresNow().map { it.chainId }.distinct().filter { it in WebCatalog.chains }
        val typed = needs.mapNotNull { n -> taxonomy.byId(n.typeId)?.let { n.text to it } }.distinctBy { it.second.id }
        return fetchWeb(s, chains, typed, force)
    }

    /**
     * «Comparar en otros súper» de un producto: lo busca en la web de todas las cadenas.
     * Devuelve cuantos precios ha guardado; -1 si los precios web estan apagados.
     */
    suspend fun searchEverywhere(product: ProductRef, force: Boolean = false): Int {
        val s = settings.first()
        if (!s.webPrices || !WebCatalog.isPostalCode(s.postalCode)) return -1
        val type = taxonomy.byId(product.typeId) ?: return 0
        // Se busca por lo que es, sin marca ni envase: "Croissant de mantequilla", no "… Hacendado · pieza".
        val brandWords = ProductMatcher.brandWords(product.brand).toSet()
        val query = product.name.substringBefore(" · ").split(' ').filter { Text.normalize(it) !in brandWords && it.isNotBlank() }
            .joinToString(" ").ifBlank { type.name }
        return fetchWeb(s, WebCatalog.chains.toList(), listOf(query to type), force)
    }

    /** Busca cada texto en la web de cada cadena (todas a la vez) y guarda lo que sea de su tipo. */
    private suspend fun fetchWeb(s: Settings, chains: List<String>, items: List<Pair<String, com.marcmayol.faltis.core.ProductType>>, force: Boolean): Int {
        if (chains.isEmpty() || items.isEmpty()) return 0
        reclassifyWebProducts()
        val dayAgo = now() - 24 * 3600_000L
        // Cada cadena por su lado y a la vez: si una web falla o tarda, las demas siguen.
        return coroutineScope {
            chains.map { chain ->
                async {
                    // Si la web de una cadena falla, se deja esa cadena para la proxima vez.
                    var saved = 0
                    runCatching {
                        val zone = s.webZones[chain]
                            ?: web.zone(chain, s.postalCode)?.also { saveZone(chain, it) }
                            ?: return@runCatching
                        var searches = 0
                        for ((text, type) in items) {
                            if (!force && (dao.lastWebPrice(chain, type.id) ?: 0) > dayAgo) continue
                            // Algunas webs cortan si se buscan muchas cosas seguidas: el resto, en la proxima.
                            if (searches++ >= WebPrices.maxSearches(chain)) break
                            val found = web.search(chain, text, zone)
                                .mapNotNull { p -> taxonomy.matchProductName(p.name)?.type?.takeIf { type.covers(it) }?.let { p to it } }
                                .take(MAX_WEB_PER_NEED)
                            db.withTransaction { found.forEach { (p, t) -> saveWebPrice(p, t.id); saved++ } }
                        }
                    }
                    saved
                }
            }.awaitAll().sum()
        }
    }

    private var reclassified = false

    /**
     * Si la taxonomia cambia (p. ej. "tomate triturado" pasa a ser despensa), los productos web ya
     * guardados con el tipo viejo se corrigen una vez por arranque, para que no sigan sumando donde no tocan.
     */
    suspend fun reclassifyWebProducts() {
        if (reclassified) return
        reclassified = true
        dao.allProducts().first().filter { !it.isSample && !it.fromTicket && it.typeId != null }.forEach { p ->
            // Sin tipo deja de compararse: era una "bolsa de agua" guardada como agua.
            val t = taxonomy.matchProductName(p.name)?.type?.id
            if (t != p.typeId) dao.updateProduct(p.copy(typeId = t, variant = t?.let { taxonomy.byId(it)?.name }))
        }
    }

    private suspend fun saveWebPrice(p: WebProduct, typeId: String) {
        val norm = Text.normalize(p.name)
        val words = ProductMatcher.nameWords(ProductMatcher.plain(p.name))
        val tags = listOfNotNull(
            "sin_lactosa".takeIf { "sin lactosa" in norm }, "sin_gluten".takeIf { "sin gluten" in norm },
            "sin_azucar".takeIf { "sin azucar" in norm }, "eco".takeIf { "ecologic" in norm || Regex("bio").containsMatchIn(norm) },
        ).joinToString(",")
        val entity = ProductEntity(
            chainId = p.chainId, name = p.name, nameNorm = norm, brand = p.brand, typeId = typeId, tags = tags,
            isPrivateLabel = ProductMatcher.isStoreBrand(p.brand) || ProductMatcher.owners(words).isNotEmpty(),
            netQty = p.netQty, netUnit = p.netUnit?.code, variant = taxonomy.byId(typeId)?.name,
        )
        val existing = dao.webProduct(p.chainId, norm)
        val id = if (existing == null) dao.insertProduct(entity.copy(imageUrl = p.imageUrl))
            else existing.id.also { dao.updateProduct(entity.copy(id = it, ean = existing.ean, isFavorite = existing.isFavorite, imageUrl = p.imageUrl ?: existing.imageUrl)) }
        dao.deleteWebObs(id)
        dao.insertObs(
            PriceObsEntity(
                productId = id, storeId = null, chainId = p.chainId, priceCents = p.priceCents,
                kind = (if (p.offer) PriceKind.PROMO else PriceKind.REGULAR).name, source = PriceSource.WEB.name, observedAtMillis = now(),
            ),
        )
    }

    // ── Fichas de Open Food Facts ──
    private val foodFacts = FoodFacts()

    /**
     * La ficha del producto: la guardada si es reciente; si no, por su codigo de barras o, sin el,
     * por nombre y marca. Sin conexion devuelve la guardada aunque sea vieja.
     */
    suspend fun nutrition(productId: Long, force: Boolean = false): NutritionLookup {
        val p = dao.product(productId) ?: return NutritionLookup.NotFound
        val ean = FoodFacts.validEan(p.ean)
        val cached = dao.nutrition(productId)
        val cachedLookup = cached?.let { c -> c.body?.let { b -> OpenFoodFacts.parseProduct(b)?.let { NutritionLookup.Found(it, c.approximate) } } ?: NutritionLookup.NotFound }
        val maxAge = if (cached?.body != null) 30L * DAY_MS else 7L * DAY_MS
        if (!force && cached != null && cached.ean == ean && now() - cached.fetchedAtMillis < maxAge) return cachedLookup!!
        val result = runCatching {
            if (ean != null) foodFacts.byEan(ean)?.let { it to false }
            else foodFacts.byName(p.name.substringBefore(" · "), OpenFoodFacts.searchBrand(p.brand, p.chainId, p.isPrivateLabel))?.let { it to true }
        }
        if (result.isFailure) return cachedLookup ?: NutritionLookup.Offline
        val found = result.getOrNull()
        dao.upsertNutrition(NutritionEntity(productId, ean, found?.first?.body, found?.second ?: false, now()))
        found?.first?.facts?.imageUrl?.let { dao.setImageIfMissing(productId, it) }
        return found?.let { NutritionLookup.Found(it.first.facts, it.second) } ?: NutritionLookup.NotFound
    }

    suspend fun setFavorite(productId: Long, on: Boolean) = dao.setFavorite(productId, on)

    /** Guarda el codigo de barras leido y busca su ficha. false si el codigo no es valido. */
    suspend fun setEan(productId: Long, code: String): Boolean {
        val ean = FoodFacts.validEan(code) ?: return false
        val p = dao.product(productId) ?: return false
        dao.updateProduct(p.copy(ean = ean))
        dao.deleteNutrition(productId)
        return true
    }

    /** Borra una lista y lo que tiene pendiente; si era la activa, pasa a otra. */
    suspend fun deleteList(id: Long) {
        db.withTransaction {
            dao.deleteOpenNeedsOf(id)
            dao.deleteList(id)
        }
        if (settings.first().activeListId == id) dao.listsNow().firstOrNull()?.let { setActiveList(it.id) }
    }

    suspend fun deleteSample() = db.withTransaction {
        dao.deleteSampleObs(); dao.deleteSampleProducts(); dao.deleteSampleNeeds(); dao.deleteSampleLists(); dao.deleteSampleStores()
    }

    suspend fun deleteAll() {
        db.clearAllTables()
        context.dataStore.edit { it.clear() }
    }

    /** F-15 · Exportar todo a JSON. */
    suspend fun exportJson(): String {
        val sb = StringBuilder("{\n")
        fun <T> section(name: String, items: List<T>, last: Boolean = false) {
            sb.append("  \"").append(name).append("\": [\n")
            items.forEachIndexed { i, it -> sb.append("    ").append(jsonString(it.toString())).append(if (i < items.lastIndex) ",\n" else "\n") }
            sb.append("  ]").append(if (last) "\n" else ",\n")
        }
        section("stores", dao.allStores().first())
        section("lists", dao.listsNow())
        section("products", dao.allProducts().first())
        section("prices", dao.allObs().first())
        section("purchases", dao.purchases().first(), last = true)
        return sb.append("}\n").toString()
    }

    private fun jsonString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        private const val DAY_MS = 24 * 3600_000L
        fun now() = System.currentTimeMillis()
        fun formatQty(q: Double): String =
            if (q == q.roundToLong().toDouble()) q.roundToLong().toString() else "%.2f".format(q).trimEnd('0').trimEnd(',', '.')
    }
}
