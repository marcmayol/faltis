package com.marcmayol.faltis.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "store")
data class StoreEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chainId: String,
    val name: String,
    val isFavorite: Boolean = true,
    val position: Int = 0,
    val isSample: Boolean = false,
)

@Entity(tableName = "product", indices = [Index("chainId"), Index("nameNorm"), Index("ean")])
data class ProductEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val chainId: String,
    val name: String,
    val nameNorm: String,
    val brand: String? = null,
    val isPrivateLabel: Boolean = false,
    val typeId: String? = null,
    val variant: String? = null,
    /** Etiquetas separadas por coma: sin_lactosa, eco, relleno... */
    val tags: String = "",
    val netQty: Double? = null,
    val netUnit: String? = null,
    val ean: String? = null,
    val isSample: Boolean = false,
    val fromTicket: Boolean = false,
)

@Entity(tableName = "price_obs", indices = [Index("productId"), Index("storeId"), Index("purchaseId")])
data class PriceObsEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val productId: Long,
    val storeId: Long?,
    val chainId: String,
    val priceCents: Long,
    val unitPriceCents: Long? = null,
    val kind: String,
    val source: String,
    val observedAtMillis: Long,
    val purchaseId: Long? = null,
)

@Entity(tableName = "shopping_list")
data class ListEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAtMillis: Long,
    val isSample: Boolean = false,
    /** La lista de un super concreto; null si es una lista general. */
    val storeId: Long? = null,
)

@Entity(tableName = "need", indices = [Index("listId")])
data class NeedEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listId: Long,
    val text: String,
    val typeId: String? = null,
    val qty: Double = 1.0,
    val unit: String = "ud",
    /** Restricciones separadas por "|". */
    val restrictions: String = "",
    val pinnedProductId: Long? = null,
    val state: String = "PENDING",
    val position: Int = 0,
    /** Orden en que se metio en el carro (para aprender pasillos). */
    val cartOrder: Int? = null,
    val addedAtMillis: Long,
    val updatedAtMillis: Long,
    val purchaseId: Long? = null,
)

@Entity(tableName = "aisle_order", primaryKeys = ["storeId", "key"])
data class AisleOrderEntity(
    val storeId: Long,
    val key: String,
    val avgPos: Double,
    val samples: Int,
)

@Entity(tableName = "purchase")
data class PurchaseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val storeId: Long?,
    val chainId: String,
    /** Fecha del ticket en dias desde la epoca (LocalDate.toEpochDay). */
    val dateEpochDay: Long,
    val totalReadCents: Long? = null,
    val totalLinesCents: Long? = null,
    val source: String,
    val reconciliationJson: String? = null,
    val createdAtMillis: Long,
    /** Numero de factura simplificada: detecta el mismo ticket importado dos veces. */
    val invoice: String? = null,
    /** Hora del ticket en minutos desde las 0:00. */
    val timeMinutes: Int? = null,
)

@Entity(tableName = "purchase_line", indices = [Index("purchaseId")])
data class PurchaseLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val purchaseId: Long,
    val rawText: String,
    val description: String,
    val qty: Double? = null,
    val weightKg: Double? = null,
    val amountCents: Long? = null,
    val discountCents: Long = 0,
    val kind: String = "REGULAR",
    val typeId: String? = null,
    val productId: Long? = null,
    val confidence: String = "LOW",
)

@Entity(tableName = "abbreviation", primaryKeys = ["chainId", "rawKey"])
data class AbbreviationEntity(
    val chainId: String,
    val rawKey: String,
    val productId: Long? = null,
    val typeId: String? = null,
    val confirmations: Int = 1,
)

/**
 * Cola de lineas de ticket para compartir (PLAN §3.3). [purchaseId] solo vive
 * en el movil, para quitar las lineas si se borra el ticket; nunca se envia.
 */
@Entity(tableName = "share_line", indices = [Index("purchaseId")])
data class ShareLineEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val purchaseId: Long,
    val chainId: String,
    val postalCode: String,
    val text: String,
    val priceCents: Long,
    val unit: String,
    val kind: String,
    /** Dia del ticket en formato ISO (2026-10-12). */
    val day: String,
    val createdAtMillis: Long,
)
