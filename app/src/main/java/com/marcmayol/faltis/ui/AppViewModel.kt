package com.marcmayol.faltis.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.marcmayol.faltis.core.BasketComparator
import com.marcmayol.faltis.core.Comparison
import com.marcmayol.faltis.core.NeedPriceResult
import com.marcmayol.faltis.core.NeedState
import com.marcmayol.faltis.core.ParsedTicket
import com.marcmayol.faltis.core.PriceBook
import com.marcmayol.faltis.core.PriceSource
import com.marcmayol.faltis.core.StoreBasket
import com.marcmayol.faltis.core.TicketLine
import com.marcmayol.faltis.data.ListEntity
import com.marcmayol.faltis.data.NeedEntity
import com.marcmayol.faltis.data.ReconcileProposal
import com.marcmayol.faltis.data.Repository
import com.marcmayol.faltis.data.ReviewedLine
import com.marcmayol.faltis.data.Settings
import com.marcmayol.faltis.data.StoreEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

/** Un aviso con "Deshacer" opcional (T2). */
data class UiMessage(val text: String, val undo: (suspend () -> Unit)? = null, val actionLabel: String = "Deshacer")

/** Importacion de varios tickets a la vez: progreso y resumen. */
data class BatchState(
    val total: Int,
    val done: Int = 0,
    val imported: Int = 0,
    val duplicates: Int = 0,
    val unbalanced: Int = 0,
    val failed: Int = 0,
    val finished: Boolean = false,
)

/** Ticket en curso (P-08 a P-10). */
data class TicketDraft(
    val parsed: ParsedTicket,
    val source: PriceSource,
    val chainId: String,
    val storeId: Long?,
    val date: LocalDate,
    val lines: List<ReviewedLine>,
    val proposal: ReconcileProposal? = null,
    val selectedToBuy: Set<Long> = emptySet(),
    val duplicate: Boolean = false,
) {
    val items: List<TicketLine> get() = lines.filter { it.isItem }.map { it.line }
    val linesCents: Long get() = items.sumOf { it.netCents }
    val mismatchCents: Long? get() = parsed.totalCents?.let { linesCents - it }
}

data class ListUi(
    val list: ListEntity?,
    val needs: List<NeedEntity>,
    val store: StoreEntity?,
    val basket: StoreBasket?,
    val comparison: Comparison?,
)

@OptIn(ExperimentalCoroutinesApi::class, kotlinx.coroutines.FlowPreview::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {
    val repo = Repository(app)
    private val scope = viewModelScope

    val settings: StateFlow<Settings?> = repo.settings.stateIn(scope, SharingStarted.Eagerly, null)
    val stores: StateFlow<List<StoreEntity>> = repo.dao.favoriteStores().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val lists: StateFlow<List<ListEntity>> = repo.dao.lists().stateIn(scope, SharingStarted.Eagerly, emptyList())
    /** Articulos abiertos de cada lista (id de lista -> cuantos). */
    val listCounts: StateFlow<Map<Long, Int>> = repo.dao.listCounts().map { l -> l.associate { it.listId to it.count } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())
    val priceBook: StateFlow<PriceBook?> = repo.priceBook.stateIn(scope, SharingStarted.Eagerly, null)
    val purchases = repo.dao.purchases().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val sampleCount = repo.dao.sampleCount().stateIn(scope, SharingStarted.Eagerly, 0)

    /** Articulos abiertos de todas las listas: el inicio enseña de que supers son. */
    val allOpenNeeds: StateFlow<List<NeedEntity>> = repo.dao.allOpenNeeds().stateIn(scope, SharingStarted.Eagerly, emptyList())

    val activeNeeds: StateFlow<List<NeedEntity>> = repo.settings.map { it.activeListId }
        .flatMapLatest { id -> if (id == 0L) flowOf(emptyList()) else repo.dao.openNeeds(id) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    /** Todo lo que necesita la Lista y Comparar, recalculado cuando cambia algo. */
    val listUi: StateFlow<ListUi> = combine(settings, lists, activeNeeds, stores, priceBook) { s, ls, needs, st, book ->
        val list = ls.firstOrNull { it.id == s?.activeListId }
        val store = st.firstOrNull { it.id == s?.selectedStoreId } ?: st.firstOrNull()
        if (book == null || s == null) return@combine ListUi(list, needs, store, null, null)
        val refs = with(repo) { needs.map { it.toRef() } }
        val storeRefs = with(repo) { st.map { it.toRef() } }
        val comparator = BasketComparator(book)
        val now = Instant.now()
        val basket = store?.let { comparator.basket(refs, with(repo) { it.toRef() }, now, s.includePromos) }
        val comparison = comparator.compare(refs, storeRefs, now, s.includePromos)
        ListUi(list, needs, store, basket, comparison)
    }.stateIn(scope, SharingStarted.Eagerly, ListUi(null, emptyList(), null, null, null))

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)
    val messages = _messages.asSharedFlow()

    /** Ticket compartido desde otra app, pendiente de leer: (uri, tipo MIME). */
    val sharedTicket = MutableStateFlow<Pair<android.net.Uri, String>?>(null)
    /** Varios tickets compartidos a la vez, pendientes de importar. */
    val sharedBatch = MutableStateFlow<List<Pair<android.net.Uri, String>>?>(null)
    private val _batch = MutableStateFlow<BatchState?>(null)
    val batch: StateFlow<BatchState?> = _batch

    private val _draft = MutableStateFlow<TicketDraft?>(null)
    val draft: StateFlow<TicketDraft?> = _draft

    fun say(text: String, actionLabel: String = "Deshacer", undo: (suspend () -> Unit)? = null) { _messages.tryEmit(UiMessage(text, undo, actionLabel)) }

    fun run(block: suspend () -> Unit) { scope.launch { block() } }

    // ── Onboarding ──
    fun finishOnboarding(chosen: List<Pair<String, String>>, listName: String, sample: Boolean) = run {
        if (sample) repo.loadSample()
        else {
            chosen.forEach { (chain, name) -> repo.addStore(chain, name) }
            repo.createList(listName.ifBlank { "Compra" })
        }
        repo.setOnboardingDone()
    }

    // ── Lista ──
    /** Texto libre a la lista activa; con [chainId], marcado para comprarlo en ese super. */
    fun addText(text: String, chainId: String? = null) = run {
        val listId = repo.ensureActiveList()
        val notes = repo.addFromText(listId, text, chainId)
        notes.forEach { say(it) }
    }

    fun deleteNeed(n: NeedEntity) = run {
        val removed = repo.deleteNeed(n.id) ?: return@run
        say("«${removed.text}» eliminado") { repo.restoreNeed(removed) }
    }

    fun saveNeed(n: NeedEntity) = run { repo.updateNeed(n) }

    /** Pasa el articulo a otra lista. */
    fun moveNeed(n: NeedEntity, listId: Long) = run {
        val target = lists.value.firstOrNull { it.id == listId } ?: return@run
        if (target.id == n.listId) return@run
        repo.moveNeed(n, target.id)
        say("«${n.text}» movido a «${target.name}»", "Ver lista") { repo.setActiveList(target.id) }
    }

    /** Catalogo: deja [qty] unidades del producto en la lista activa (0 lo quita). */
    fun setInList(productId: Long, qty: Double) = run { repo.setPinnedQty(repo.ensureActiveList(), productId, qty) }

    /** Super que se esta mirando en Productos; null hasta elegir (entonces, la primera tienda). */
    val catalogChain = MutableStateFlow<String?>(null)

    fun selectStore(id: Long) = run { repo.setSelectedStore(id) }
    fun selectList(id: Long) = run { repo.setActiveList(id) }
    fun newList(name: String) = run { repo.createList(name) }
    fun renameList(l: ListEntity, name: String) = run { repo.dao.updateList(l.copy(name = name)) }
    fun toggleFavorite(p: com.marcmayol.faltis.core.ProductRef) = run {
        repo.setFavorite(p.id, !p.isFavorite)
        say(if (p.isFavorite) "Quitado de favoritos" else "Guardado en favoritos")
    }
    fun deleteList(l: ListEntity) = run {
        repo.deleteList(l.id)
        say("Lista «${l.name}» borrada")
    }
    fun toggleCart(n: NeedEntity) = run { repo.toggleCart(n, n.listId) }

    fun finishWithoutTicket(storeId: Long, onDone: () -> Unit) = run {
        val listId = settings.value?.activeListId ?: return@run
        val pid = repo.finishWithoutTicket(listId, storeId)
        say("Compra guardada sin importe") { repo.deletePurchase(pid) }
        onDone()
    }

    fun learnFromCart(storeId: Long) = run {
        val listId = settings.value?.activeListId ?: return@run
        repo.learnFromCart(listId, storeId)
    }

    // ── Ticket ──
    fun startTicket(text: String, source: PriceSource, chainHint: String?, storeHint: Long?, onReady: () -> Unit, onEmpty: () -> Unit) = run {
        val parsed = repo.parseTicket(text, chainHint)
        if (parsed.lines.isEmpty() && parsed.totalCents == null) { onEmpty(); return@run }
        val chain = parsed.chainId ?: chainHint ?: stores.value.firstOrNull()?.chainId ?: "otra"
        val store = storeHint?.let { id -> stores.value.firstOrNull { it.id == id && it.chainId == chain } }
            ?: stores.value.firstOrNull { it.chainId == chain }
        val date = parsed.date ?: LocalDate.now()
        _draft.value = TicketDraft(
            parsed = parsed, source = source, chainId = chain, storeId = store?.id, date = date,
            lines = parsed.lines.map { ReviewedLine(it) },
            duplicate = repo.findDuplicate(chain, date, parsed.totalCents, parsed.invoice),
        )
        onReady()
    }

    fun updateDraft(transform: (TicketDraft) -> TicketDraft) { _draft.value = _draft.value?.let(transform) }

    fun reassignChain(chainId: String) = updateDraft { d ->
        d.copy(chainId = chainId, storeId = stores.value.firstOrNull { it.chainId == chainId }?.id,
            lines = d.lines.map { ReviewedLine(repo.reassign(it.line, chainId), it.isItem) })
    }

    fun createStoreForDraft() = run {
        val d = _draft.value ?: return@run
        val id = repo.addStore(d.chainId, newStoreName(d.chainId, stores.value.map { it.name }))
        updateDraft { it.copy(storeId = id) }
    }

    fun prepareReconcile(onReady: () -> Unit) = run {
        val d = _draft.value ?: return@run
        val listId = repo.ensureActiveList()
        val p = repo.proposeReconcile(listId, d.items)
        _draft.value = d.copy(proposal = p, selectedToBuy = p.toBuy.map { it.id }.toSet())
        onReady()
    }

    fun saveDraft(onSaved: (Long) -> Unit) = run {
        val d = _draft.value ?: return@run
        val storeId = d.storeId ?: repo.addStore(d.chainId, com.marcmayol.faltis.core.Chains.byId(d.chainId).name)
        val listId = repo.ensureActiveList()
        val toBuy = d.proposal?.let { p -> (p.toBuy + p.stillPending).filter { it.id in d.selectedToBuy } }.orEmpty()
        val pid = repo.saveTicket(storeId, d.chainId, d.date, d.parsed.totalCents, d.source, d.lines, toBuy, listId, d.parsed.invoice, d.parsed.time)
        say("Compra guardada · ${toBuy.size} de la lista marcados") { repo.revertReconcile(pid) }
        askShareNotice()
        // Navegar ANTES de vaciar el borrador: si no, la pantalla de cuadre ve el borrador nulo y hace "volver".
        onSaved(pid)
        kotlinx.coroutines.delay(400)
        _draft.value = null
    }

    fun cancelDraft() { _draft.value = null }

    /** P-09 · Añade una linea vacia (cuando falta una en el cuadre) y devuelve su indice para editarla. */
    fun addEmptyLine(): Int? {
        val d = _draft.value ?: return null
        _draft.value = d.copy(lines = d.lines + ReviewedLine(TicketLine("(añadida a mano)", "", 1.0, null, 0, 0)))
        return d.lines.size
    }

    /**
     * Importa varios tickets de golpe (historico de PDF compartido desde Gmail o Archivos).
     * Cada uno se guarda como compra con sus precios, sin tocar la lista; los repetidos se saltan.
     */
    fun importBatch(items: List<Pair<android.net.Uri, String>>) = run {
        val ctx = getApplication<Application>()
        var st = BatchState(total = items.size)
        _batch.value = st
        val listId = repo.ensureActiveList()
        val storeOf = HashMap<String, Long>()
        for ((uri, mime) in items) {
            val isPdf = mime == "application/pdf" || uri.toString().endsWith(".pdf", ignoreCase = true)
            val ok = runCatching {
                val text = if (isPdf) com.marcmayol.faltis.data.TextSources.fromPdf(ctx, uri) else com.marcmayol.faltis.data.TextSources.fromImage(ctx, uri)
                val parsed = repo.parseTicket(text, null)
                if (parsed.lines.isEmpty()) error("sin lineas")
                val chain = parsed.chainId ?: "otra"
                val date = parsed.date ?: LocalDate.now()
                if (repo.findDuplicate(chain, date, parsed.totalCents, parsed.invoice)) {
                    st = st.copy(duplicates = st.duplicates + 1)
                } else {
                    val storeId = storeOf.getOrPut(chain) {
                        stores.value.firstOrNull { it.chainId == chain }?.id ?: repo.addStore(chain, com.marcmayol.faltis.core.Chains.byId(chain).name)
                    }
                    repo.saveTicket(
                        storeId, chain, date, parsed.totalCents, if (isPdf) PriceSource.TICKET_PDF else PriceSource.TICKET_PHOTO,
                        parsed.lines.map { ReviewedLine(it) }, emptyList(), listId, parsed.invoice, parsed.time,
                    )
                    st = st.copy(imported = st.imported + 1, unbalanced = st.unbalanced + if (parsed.balanced) 0 else 1)
                }
            }.isSuccess
            if (!ok) st = st.copy(failed = st.failed + 1)
            st = st.copy(done = st.done + 1)
            _batch.value = st
        }
        _batch.value = st.copy(finished = true)
        if (st.imported > 0) askShareNotice()
    }

    fun clearBatch() { _batch.value = null }

    // ── Compras ──
    fun revert(purchaseId: Long) = run { repo.revertReconcile(purchaseId); say("Cuadre revertido") }
    fun deletePurchase(purchaseId: Long, onDone: () -> Unit) = run { repo.deletePurchase(purchaseId); say("Compra borrada"); onDone() }

    // ── Ajustes ──
    fun setPromos(v: Boolean) = run { repo.setIncludePromos(v) }
    fun setBudget(cents: Long) = run { repo.setBudget(cents) }
    fun setTheme(mode: com.marcmayol.faltis.ui.theme.ThemeMode) = run { repo.setTheme(mode) }

    // ── Precios compartidos (PLAN §3.3) ──
    /** Explicar lo que se comparte: sale una vez, tras guardar el primer ticket. */
    val shareNotice = MutableStateFlow(false)

    private suspend fun askShareNotice() {
        val s = repo.settings.first()
        if (s.sharePrices && !s.shareNoticeShown) shareNotice.value = true
    }

    fun answerShareNotice(keepSharing: Boolean) = run {
        shareNotice.value = false
        if (keepSharing) repo.setShareNoticeShown() else { repo.setSharePrices(false); say("No se compartirán tus precios") }
    }

    fun setSharePrices(on: Boolean) = run { repo.setSharePrices(on) }

    /** Sin conexion no pasa nada: la cola espera a la proxima vez que se abra la app. */
    fun sendSharedPrices() = run {
        runCatching { repo.sendSharedPrices() }
            .onSuccess { if (it > 0) android.util.Log.i("Faltis", "Precios compartidos: $it") }
            .onFailure { android.util.Log.i("Faltis", "Precios compartidos pendientes: ${it.message}") }
    }

    // ── Precios web ──
    private val webLock = kotlinx.coroutines.sync.Mutex()
    private val _webBusy = MutableStateFlow(false)
    val webBusy: StateFlow<Boolean> = _webBusy

    fun setWebPrices(on: Boolean, postalCode: String? = null) = run {
        repo.setWebPrices(on, postalCode)
        if (on) refreshWebPrices(force = true)
    }

    /** Con [force] (a mano) avisa del resultado; la automatica calla si falla la red. */
    fun refreshWebPrices(force: Boolean = false) = run {
        if (!webLock.tryLock()) return@run
        _webBusy.value = true
        try {
            val open = activeNeeds.value.filter { it.state != NeedState.BOUGHT.name }
            val n = repo.refreshWebPrices(open, force)
            if (force) say(if (n > 0) "Precios web actualizados: $n" else "No hay precios web para esta lista")
        } catch (e: Exception) {
            if (force) say("No se han podido leer los precios web. ¿Hay conexión?")
        } finally {
            _webBusy.value = false
            webLock.unlock()
        }
    }

    /**
     * «Tu lista en otros súper»: ademas de tus tiendas, una tienda virtual por cada cadena con web
     * (precio de la cadena, id negativo). Solo se calcula mientras la pantalla lo pide.
     */
    val everywhere = MutableStateFlow(false)
    val compareEverywhere: StateFlow<Comparison?> = combine(everywhere, settings, activeNeeds, stores, priceBook) { on, s, needs, st, book ->
        if (!on || s == null || book == null) return@combine null
        val mine = with(repo) { st.map { it.toRef() } }
        val myChains = st.map { it.chainId }.toSet()
        val virtual = com.marcmayol.faltis.core.WebCatalog.chains.filter { it !in myChains }.sorted().mapIndexed { i, c ->
            com.marcmayol.faltis.core.StoreRef(-(i + 1L), c, com.marcmayol.faltis.core.Chains.byId(c).name, 1000 + i)
        }
        BasketComparator(book).compare(with(repo) { needs.map { it.toRef() } }, mine + virtual, Instant.now(), s.includePromos)
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), null)

    /** Trae de la web los precios de la lista en todas las cadenas (para [compareEverywhere]). */
    fun fetchEverywhere(force: Boolean = false) = run {
        if (settings.value?.webPrices != true) return@run
        if (!webLock.tryLock()) return@run
        _webBusy.value = true
        try {
            val open = activeNeeds.value.filter { it.state != NeedState.BOUGHT.name }
            repo.refreshWebPrices(open, force, everywhere = true)
        } catch (_: Exception) {
            say("No se han podido leer todos los precios web. ¿Hay conexión?")
        } finally {
            _webBusy.value = false
            webLock.unlock()
        }
    }

    /** «Comparar en otros súper» de un producto. Devuelve -1 si los precios web estan apagados. */
    suspend fun searchEverywhere(p: com.marcmayol.faltis.core.ProductRef): Int {
        _webBusy.value = true
        return try { repo.searchEverywhere(p) } catch (_: Exception) { 0 } finally { _webBusy.value = false }
    }

    init {
        // Productos web guardados con una clasificacion vieja: se corrigen al arrancar.
        scope.launch { repo.reclassifyWebProducts() }
        // Al añadir algo a la lista (o activar la opcion), se traen sus precios web si no son de hoy.
        scope.launch {
            combine(
                repo.settings.map { it.webPrices to it.postalCode },
                activeNeeds.map { needs -> needs.mapNotNull { it.typeId }.toSet() },
            ) { web, types -> web.first && types.isNotEmpty() }
                .debounce(1500)
                .collect { if (it) refreshWebPrices() }
        }
    }
    fun addStore(chain: String) = run { repo.addStore(chain, newStoreName(chain, stores.value.map { it.name })) }
    fun hideStore(s: StoreEntity) = run { repo.hideStore(s) }
    fun renameStore(s: StoreEntity, name: String) = run {
        val n = name.trim()
        if (n.isEmpty() || n == s.name) return@run
        repo.renameStore(s, n)
    }
    fun moveStore(s: StoreEntity, delta: Int) = run { repo.moveStore(s, delta) }
    fun deleteSample() = run { repo.deleteSample(); repo.ensureActiveList(); say("Datos de ejemplo borrados") }
    fun loadSample() = run { repo.loadSample(); say("Datos de ejemplo cargados") }
    fun deleteAll() = run { repo.deleteAll() }

    fun priceOf(needId: Long): NeedPriceResult? = listUi.value.basket?.results?.firstOrNull {
        when (it) { is NeedPriceResult.Priced -> it.value.need.id == needId; is NeedPriceResult.Missing -> it.need.id == needId }
    }

    fun openCount(): Int = activeNeeds.value.count { it.state != NeedState.BOUGHT.name }
}

/** Nombre de una tienda nueva: el de la cadena («Mercadona»); si ya esta, «Mercadona 2», «Mercadona 3»… */
fun newStoreName(chainId: String, taken: Collection<String>): String {
    val base = com.marcmayol.faltis.core.Chains.byId(chainId).name
    return generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { it !in taken }
}
