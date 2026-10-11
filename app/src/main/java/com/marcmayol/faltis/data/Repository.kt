package com.marcmayol.faltis.data

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

private val Context.dataStore by preferencesDataStore("faltis")

data class Settings(
    val onboardingDone: Boolean = false,
    val activeListId: Long = 0,
    val selectedStoreId: Long = 0,
    val includePromos: Boolean = false,
    val budgetCents: Long = 0,
    val themeMode: com.marcmayol.faltis.ui.theme.ThemeMode = com.marcmayol.faltis.ui.theme.ThemeMode.SYSTEM,
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

class Repository(private val context: Context) {
    val taxonomy: Taxonomy = Taxonomy.bundled()
    val textParser = FreeTextParser(taxonomy)
    val ticketParser = TicketParser(taxonomy)

    private val db = Room.databaseBuilder(context, FaltisDb::class.java, "faltis.db").addMigrations(FaltisDb.MIGRATION_1_2).build()
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
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p: Preferences ->
        Settings(
            onboardingDone = p[K.onboarding] ?: false,
            activeListId = p[K.activeList] ?: 0,
            selectedStoreId = p[K.selectedStore] ?: 0,
            includePromos = p[K.promos] ?: false,
            budgetCents = p[K.budget] ?: 0,
            themeMode = p[K.theme]?.let { runCatching { com.marcmayol.faltis.ui.theme.ThemeMode.valueOf(it) }.getOrNull() } ?: com.marcmayol.faltis.ui.theme.ThemeMode.SYSTEM,
        )
    }

    suspend fun setOnboardingDone() = context.dataStore.edit { it[K.onboarding] = true }
    suspend fun setActiveList(id: Long) = context.dataStore.edit { it[K.activeList] = id }
    suspend fun setSelectedStore(id: Long) = context.dataStore.edit { it[K.selectedStore] = id }
    suspend fun setIncludePromos(v: Boolean) = context.dataStore.edit { it[K.promos] = v }
    suspend fun setBudget(cents: Long) = context.dataStore.edit { it[K.budget] = cents }
    suspend fun setTheme(mode: com.marcmayol.faltis.ui.theme.ThemeMode) = context.dataStore.edit { it[K.theme] = mode.name }

    // ── Conversion a modelos de :core ──
    fun ProductEntity.toRef() = ProductRef(
        id, chainId, name, brand, isPrivateLabel, typeId, variant,
        tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet(),
        netQty, QtyUnit.fromCode(netUnit), ean, isSample,
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
        fun now() = System.currentTimeMillis()
        fun formatQty(q: Double): String =
            if (q == q.roundToLong().toDouble()) q.roundToLong().toString() else "%.2f".format(q).trimEnd('0').trimEnd(',', '.')
    }
}
