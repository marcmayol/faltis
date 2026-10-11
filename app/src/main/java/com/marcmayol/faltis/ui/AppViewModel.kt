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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

/** Un aviso con "Deshacer" opcional (T2). */
data class UiMessage(val text: String, val undo: (suspend () -> Unit)? = null)

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

@OptIn(ExperimentalCoroutinesApi::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {
    val repo = Repository(app)
    private val scope = viewModelScope

    val settings: StateFlow<Settings?> = repo.settings.stateIn(scope, SharingStarted.Eagerly, null)
    val stores: StateFlow<List<StoreEntity>> = repo.dao.favoriteStores().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val lists: StateFlow<List<ListEntity>> = repo.dao.lists().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val priceBook: StateFlow<PriceBook?> = repo.priceBook.stateIn(scope, SharingStarted.Eagerly, null)
    val purchases = repo.dao.purchases().stateIn(scope, SharingStarted.Eagerly, emptyList())
    val sampleCount = repo.dao.sampleCount().stateIn(scope, SharingStarted.Eagerly, 0)

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

    fun say(text: String, undo: (suspend () -> Unit)? = null) { _messages.tryEmit(UiMessage(text, undo)) }

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
    fun addText(text: String) = run {
        val listId = repo.ensureActiveList()
        val notes = repo.addFromText(listId, text)
        notes.forEach { say(it) }
    }

    fun deleteNeed(n: NeedEntity) = run {
        val removed = repo.deleteNeed(n.id) ?: return@run
        say("«${removed.text}» eliminado") { repo.restoreNeed(removed) }
    }

    fun saveNeed(n: NeedEntity) = run { repo.updateNeed(n) }
    fun selectStore(id: Long) = run { repo.setSelectedStore(id) }
    fun selectList(id: Long) = run { repo.setActiveList(id) }
    fun newList(name: String) = run { repo.createList(name) }
    fun renameList(l: ListEntity, name: String) = run { repo.dao.updateList(l.copy(name = name)) }

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

    fun createStoreForDraft(name: String) = run {
        val d = _draft.value ?: return@run
        val id = repo.addStore(d.chainId, name)
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
    }

    fun clearBatch() { _batch.value = null }

    // ── Compras ──
    fun revert(purchaseId: Long) = run { repo.revertReconcile(purchaseId); say("Cuadre revertido") }
    fun deletePurchase(purchaseId: Long, onDone: () -> Unit) = run { repo.deletePurchase(purchaseId); say("Compra borrada"); onDone() }

    // ── Ajustes ──
    fun setPromos(v: Boolean) = run { repo.setIncludePromos(v) }
    fun setBudget(cents: Long) = run { repo.setBudget(cents) }
    fun setTheme(mode: com.marcmayol.faltis.ui.theme.ThemeMode) = run { repo.setTheme(mode) }
    fun addStore(chain: String, name: String) = run { repo.addStore(chain, name) }
    fun hideStore(s: StoreEntity) = run { repo.hideStore(s) }
    fun moveStore(s: StoreEntity, delta: Int) = run { repo.moveStore(s, delta) }
    fun deleteSample() = run { repo.deleteSample(); repo.ensureActiveList(); say("Datos de ejemplo borrados") }
    fun loadSample() = run { repo.loadSample(); say("Datos de ejemplo cargados") }
    fun deleteAll() = run { repo.deleteAll() }

    fun priceOf(needId: Long): NeedPriceResult? = listUi.value.basket?.results?.firstOrNull {
        when (it) { is NeedPriceResult.Priced -> it.value.need.id == needId; is NeedPriceResult.Missing -> it.need.id == needId }
    }

    fun openCount(): Int = activeNeeds.value.count { it.state != NeedState.BOUGHT.name }
}
