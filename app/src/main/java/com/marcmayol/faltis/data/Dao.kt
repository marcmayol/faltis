package com.marcmayol.faltis.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FaltisDao {
    // ── Tiendas ──
    @Query("SELECT * FROM store WHERE isFavorite = 1 ORDER BY position, id")
    fun favoriteStores(): Flow<List<StoreEntity>>

    @Query("SELECT * FROM store ORDER BY position, id")
    fun allStores(): Flow<List<StoreEntity>>

    @Query("SELECT * FROM store WHERE isFavorite = 1 ORDER BY position, id")
    suspend fun favoriteStoresNow(): List<StoreEntity>

    @Insert suspend fun insertStore(s: StoreEntity): Long
    @Update suspend fun updateStore(s: StoreEntity)
    @Query("SELECT * FROM store WHERE id = :id") suspend fun store(id: Long): StoreEntity?

    // ── Catalogo ──
    @Query("SELECT * FROM product") fun allProducts(): Flow<List<ProductEntity>>
    @Query("SELECT * FROM product WHERE id = :id") suspend fun product(id: Long): ProductEntity?
    @Insert suspend fun insertProduct(p: ProductEntity): Long
    @Insert suspend fun insertProducts(p: List<ProductEntity>): List<Long>
    @Update suspend fun updateProduct(p: ProductEntity)
    /** Producto ya traido de la web (ni de ticket ni de ejemplo). */
    @Query("SELECT * FROM product WHERE chainId = :chainId AND nameNorm = :nameNorm AND isSample = 0 AND fromTicket = 0 LIMIT 1")
    suspend fun webProduct(chainId: String, nameNorm: String): ProductEntity?
    @Query("DELETE FROM price_obs WHERE productId = :productId AND source = 'WEB'") suspend fun deleteWebObs(productId: Long)
    /** Ultima vez que se trajeron de la web precios de ese tipo (o uno mas concreto) en esa cadena. */
    @Query(
        "SELECT MAX(o.observedAtMillis) FROM price_obs o JOIN product p ON p.id = o.productId " +
            "WHERE o.source = 'WEB' AND o.chainId = :chainId AND (p.typeId = :typeId OR p.typeId LIKE :typeId || '/%')",
    )
    suspend fun lastWebPrice(chainId: String, typeId: String): Long?

    @Query("SELECT * FROM price_obs") fun allObs(): Flow<List<PriceObsEntity>>
    @Insert suspend fun insertObs(o: PriceObsEntity): Long
    @Insert suspend fun insertObsAll(o: List<PriceObsEntity>)
    @Query("SELECT * FROM price_obs WHERE productId = :productId ORDER BY observedAtMillis DESC")
    fun obsOf(productId: Long): Flow<List<PriceObsEntity>>

    // ── Listas y necesidades ──
    @Query("SELECT * FROM shopping_list ORDER BY id") fun lists(): Flow<List<ListEntity>>
    @Query("SELECT * FROM shopping_list ORDER BY id") suspend fun listsNow(): List<ListEntity>
    @Insert suspend fun insertList(l: ListEntity): Long
    @Update suspend fun updateList(l: ListEntity)
    @Query("DELETE FROM shopping_list WHERE id = :id") suspend fun deleteList(id: Long)

    @Query("SELECT * FROM need WHERE listId = :listId AND state != 'BOUGHT' ORDER BY position, id")
    fun openNeeds(listId: Long): Flow<List<NeedEntity>>
    @Query("SELECT * FROM need WHERE listId = :listId AND state != 'BOUGHT' ORDER BY position, id")
    suspend fun openNeedsNow(listId: Long): List<NeedEntity>
    @Query("SELECT * FROM need WHERE id = :id") suspend fun need(id: Long): NeedEntity?
    @Insert suspend fun insertNeed(n: NeedEntity): Long
    @Insert suspend fun insertNeeds(n: List<NeedEntity>)
    @Update suspend fun updateNeed(n: NeedEntity)
    @Update suspend fun updateNeeds(n: List<NeedEntity>)
    @Query("DELETE FROM need WHERE id = :id") suspend fun deleteNeed(id: Long)
    @Query("SELECT COALESCE(MAX(position), 0) FROM need WHERE listId = :listId") suspend fun maxPosition(listId: Long): Int

    // ── Pasillos ──
    @Query("SELECT * FROM aisle_order WHERE storeId = :storeId") fun aisle(storeId: Long): Flow<List<AisleOrderEntity>>
    @Query("SELECT * FROM aisle_order WHERE storeId = :storeId") suspend fun aisleNow(storeId: Long): List<AisleOrderEntity>
    @Upsert suspend fun upsertAisle(a: List<AisleOrderEntity>)

    // ── Compras ──
    @Query("SELECT * FROM purchase ORDER BY dateEpochDay DESC, id DESC") fun purchases(): Flow<List<PurchaseEntity>>
    @Query("SELECT * FROM purchase WHERE id = :id") suspend fun purchase(id: Long): PurchaseEntity?
    @Query("SELECT * FROM purchase WHERE id = :id") fun purchaseFlow(id: Long): Flow<PurchaseEntity?>
    @Insert suspend fun insertPurchase(p: PurchaseEntity): Long
    @Update suspend fun updatePurchase(p: PurchaseEntity)
    @Query("DELETE FROM purchase WHERE id = :id") suspend fun deletePurchase(id: Long)
    @Query("SELECT * FROM purchase WHERE chainId = :chainId AND dateEpochDay = :day AND totalReadCents = :total LIMIT 1")
    suspend fun findDuplicate(chainId: String, day: Long, total: Long): PurchaseEntity?
    @Query("SELECT * FROM purchase WHERE chainId = :chainId AND invoice = :invoice LIMIT 1")
    suspend fun findByInvoice(chainId: String, invoice: String): PurchaseEntity?

    @Query("SELECT * FROM purchase_line WHERE purchaseId = :id ORDER BY id") fun lines(id: Long): Flow<List<PurchaseLineEntity>>
    @Insert suspend fun insertLines(l: List<PurchaseLineEntity>)
    @Query("DELETE FROM purchase_line WHERE purchaseId = :id") suspend fun deleteLines(id: Long)
    @Query("DELETE FROM price_obs WHERE purchaseId = :id") suspend fun deleteObsOfPurchase(id: Long)
    @Query("UPDATE need SET state = 'PENDING', purchaseId = NULL, cartOrder = NULL WHERE purchaseId = :id")
    suspend fun reopenNeedsOf(id: Long)

    // ── Abreviaturas ──
    @Query("SELECT * FROM abbreviation") suspend fun abbreviations(): List<AbbreviationEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertAbbreviation(a: AbbreviationEntity)

    // ── Datos de ejemplo y borrado ──
    @Query("DELETE FROM price_obs WHERE productId IN (SELECT id FROM product WHERE isSample = 1)") suspend fun deleteSampleObs()
    @Query("DELETE FROM product WHERE isSample = 1") suspend fun deleteSampleProducts()
    @Query("DELETE FROM need WHERE listId IN (SELECT id FROM shopping_list WHERE isSample = 1)") suspend fun deleteSampleNeeds()
    @Query("DELETE FROM shopping_list WHERE isSample = 1") suspend fun deleteSampleLists()
    @Query("DELETE FROM store WHERE isSample = 1 AND id NOT IN (SELECT storeId FROM purchase WHERE storeId IS NOT NULL)") suspend fun deleteSampleStores()
    @Query("SELECT COUNT(*) FROM product WHERE isSample = 1") fun sampleCount(): Flow<Int>

    // Cola de precios compartidos
    @Insert suspend fun insertShareLines(lines: List<ShareLineEntity>)
    @Query("SELECT * FROM share_line WHERE createdAtMillis <= :before ORDER BY RANDOM() LIMIT :limit") suspend fun shareLinesReady(before: Long, limit: Int): List<ShareLineEntity>
    @Query("DELETE FROM share_line WHERE id IN (:ids)") suspend fun deleteShareLines(ids: List<Long>)
    @Query("DELETE FROM share_line WHERE purchaseId = :purchaseId") suspend fun deleteShareLinesOf(purchaseId: Long)
    @Query("DELETE FROM share_line") suspend fun clearShareLines()
    @Query("SELECT COUNT(*) FROM share_line") fun shareLineCount(): Flow<Int>
}

@Database(
    entities = [
        StoreEntity::class, ProductEntity::class, PriceObsEntity::class, ListEntity::class, NeedEntity::class,
        AisleOrderEntity::class, PurchaseEntity::class, PurchaseLineEntity::class, AbbreviationEntity::class,
        ShareLineEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class FaltisDb : RoomDatabase() {
    abstract fun dao(): FaltisDao

    companion object {
        /** v2: numero de factura y hora del ticket. */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE purchase ADD COLUMN invoice TEXT")
                db.execSQL("ALTER TABLE purchase ADD COLUMN timeMinutes INTEGER")
            }
        }

        /** v3: listas de un super concreto. */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE shopping_list ADD COLUMN storeId INTEGER")
            }
        }

        /** v4: cola de precios compartidos. */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `share_line` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `purchaseId` INTEGER NOT NULL, " +
                        "`chainId` TEXT NOT NULL, `postalCode` TEXT NOT NULL, `text` TEXT NOT NULL, `priceCents` INTEGER NOT NULL, `unit` TEXT NOT NULL, " +
                        "`kind` TEXT NOT NULL, `day` TEXT NOT NULL, `createdAtMillis` INTEGER NOT NULL)",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_share_line_purchaseId` ON `share_line` (`purchaseId`)")
            }
        }
    }
}
