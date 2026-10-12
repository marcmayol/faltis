package com.marcmayol.faltis.core

import java.time.Instant

/** Modelos puros que comparten el motor y la app (la app los construye desde Room). */

enum class PriceKind { REGULAR, PROMO, MEMBER }

/** Orden de fiabilidad: la primera gana en empate de fecha (F-05.2). */
enum class PriceSource { TICKET_PDF, TICKET_PHOTO, MANUAL, COMMUNITY, WEB, SAMPLE }

enum class NeedState { PENDING, IN_CART, BOUGHT }

data class Chain(val id: String, val name: String, val monogram: String)

data class StoreRef(val id: Long, val chainId: String, val name: String, val position: Int)

data class ProductRef(
    val id: Long,
    val chainId: String,
    val name: String,
    val brand: String?,
    val isPrivateLabel: Boolean,
    val typeId: String?,
    val variant: String?,
    val tags: Set<String>,
    val netQty: Double?,
    val netUnit: QtyUnit?,
    val ean: String?,
    val isSample: Boolean,
)

data class PriceObservation(
    val productId: Long,
    val storeId: Long?,
    val chainId: String,
    val priceCents: Long,
    val kind: PriceKind,
    val source: PriceSource,
    val observedAt: Instant,
)

data class NeedRef(
    val id: Long,
    val text: String,
    val typeId: String?,
    val qty: Double,
    val unit: QtyUnit,
    val restrictions: List<String>,
    val pinnedProductId: Long?,
    val state: NeedState,
) {
    val isOpen: Boolean get() = state != NeedState.BOUGHT
}

object Chains {
    /** Cadenas de España (lista estatica). El monograma sustituye al logo (T6). */
    val all: List<Chain> = listOf(
        Chain("mercadona", "Mercadona", "ME"),
        Chain("carrefour", "Carrefour", "CA"),
        Chain("alcampo", "Alcampo", "AL"),
        Chain("eroski", "Eroski", "ER"),
        Chain("lidl", "Lidl", "LI"),
        Chain("dia", "Dia", "DI"),
        Chain("aldi", "Aldi", "AD"),
        Chain("consum", "Consum", "CO"),
        Chain("hipercentro", "Hiper Centro", "HC"),
        Chain("agromart", "Agromart", "AG"),
        Chain("spar", "Spar", "SP"),
        Chain("suma", "Suma", "SU"),
        Chain("ifa", "IFA", "IF"),
        Chain("ahorramas", "Ahorramas", "AH"),
        Chain("elcorteingles", "El Corte Inglés / Hipercor", "EC"),
        Chain("bm", "BM Supermercados", "BM"),
        Chain("caprabo", "Caprabo", "CP"),
        Chain("condis", "Condis", "CD"),
        Chain("bonpreu", "Bonpreu i Esclat", "BE"),
        Chain("gadis", "Gadis", "GA"),
        Chain("froiz", "Froiz", "FR"),
        Chain("masymas", "Masymas", "MM"),
        Chain("hiperdino", "HiperDino", "HD"),
        Chain("coviran", "Covirán", "CV"),
        Chain("familycash", "Family Cash", "FC"),
        Chain("bonarea", "bonÀrea", "BA"),
        Chain("alimerka", "Alimerka", "AK"),
        Chain("supeco", "Supeco", "SC"),
        Chain("vidal", "Vidal", "VI"),
        Chain("sorli", "Sorli", "SO"),
        Chain("eljamon", "El Jamón", "EJ"),
        Chain("otra", "Otra tienda", "OT"),
    )
    private val byId = all.associateBy { it.id }
    fun byId(id: String): Chain = byId[id] ?: Chain(id, id, id.take(2).uppercase())

    /** Palabras de cabecera de ticket para detectar la cadena (F-09.1). */
    val ticketKeywords: List<Pair<String, String>> = listOf(
        "MERCADONA" to "mercadona", "ALCAMPO" to "alcampo", "CARREFOUR" to "carrefour",
        "EROSKI" to "eroski", "HIPER CENTRO" to "hipercentro", "HIPERCENTRO" to "hipercentro",
        "AGROMART" to "agromart", "SPAR" to "spar", "SUMA" to "suma", "IFA" to "ifa",
        "LIDL" to "lidl", "DIA RETAIL" to "dia", "SUPERMERCADOS DIA" to "dia", "ALDI" to "aldi",
        "CONSUM" to "consum", "AHORRAMAS" to "ahorramas", "CAPRABO" to "caprabo", "CONDIS" to "condis",
        "BONPREU" to "bonpreu", "GADIS" to "gadis", "FROIZ" to "froiz", "BM SUPERMERCADOS" to "bm",
    )
}
