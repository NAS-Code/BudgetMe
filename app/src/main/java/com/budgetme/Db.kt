package com.budgetme

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// Money is integer cents; refunds are negative. date is ISO yyyy-MM-dd; budgetMonth is yyyy-MM and can
// differ from date's month for Fun (weekend rule).
@Entity(indices = [Index("status"), Index("budgetMonth")])
data class Txn(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val source: String,             // alert | manual | history (feed comes with SimpleFIN)
    val account: String?,
    val rawDescriptor: String,      // exactly what the bank sent
    val merchant: String,           // display name; the user can correct it (teaches an alias)
    val amountCents: Long,
    val date: String,
    val budgetMonth: String,
    val categoryId: String? = null,
    val funWeek: Int? = null,
    val status: String,             // needs_review | auto_filed | confirmed
    val confidence: Double? = null,
    val tier: String? = null,
    val choices: String? = null,    // the buttons offered, Choice.encode()
    val note: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val sheetHash: String? = null,  // state both sides agreed on at the last sheet sync; null = not in the sheet yet
    // Card charges reach the bank account only when the card is paid off (sheet: Credit Card column -> WellsFargo column).
    @ColumnInfo(defaultValue = "0") val cardPaid: Boolean = false,
)

/** A line in a month's Income or Monthly (fixed) section: estimate vs actual, like the sheet's rows. */
@Entity(indices = [Index("month")])
data class Line(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val month: String,              // yyyy-MM
    val section: String,            // income | fixed
    val name: String,
    val estimateCents: Long?,
    val actualCents: Long?,
    val sort: Int,
    val sheetHash: String? = null,  // as for Txn
    val deleted: Boolean = false,   // deleted in the app, waiting for the sync to clear the sheet row
    // The actual is still on the credit card (sheet: Credit Card column), like a purchase: not in the bank balance yet.
    @ColumnInfo(defaultValue = "0") val onCard: Boolean = false,
    // Income that counts toward Total Surplus (the tracker's N47 uses paychecks + Venmo, not refunds or one-offs). App-only.
    @ColumnInfo(defaultValue = "0") val inSurplus: Boolean = false,
)

@Entity
data class Category(
    @PrimaryKey val id: String,     // stable: history links here, so renames keep it
    val name: String,
    val description: String,
    val budgetCents: Long?,
    val period: String,             // month | weekend_month
    val predict: String,            // auto | fallback_only | manual_only
    val keywords: String = "",      // '|' separated
    val requireKeyword: Boolean = false,
    val archived: Boolean = false,
    val sort: Int = 0,
) {
    fun spec() = CategorySpec(id, predict, period == "weekend_month", keywords.split('|').filter { it.isNotBlank() }, requireKeyword)
}

@Entity
data class MerchantAlias(@PrimaryKey val descriptorKey: String, val merchantKey: String)

/** Every notification from a bank app, parsed or not: the raw material for writing parsers. */
@Entity
data class RawAlert(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val txnId: Long?,               // null = the parser didn't recognize it
)

@Dao
interface Dao_ {
    @Query("SELECT * FROM Txn WHERE status = 'needs_review' ORDER BY date DESC, id DESC")
    fun inbox(): Flow<List<Txn>>

    @Query("SELECT id FROM Txn WHERE status = 'needs_review'") suspend fun inboxIds(): List<Long>
    @Query("DELETE FROM Txn WHERE status = 'needs_review'") suspend fun deleteInbox()

    @Query("SELECT COUNT(*) FROM Txn WHERE status IN ('needs_review', 'deposit')")
    suspend fun inboxCount(): Int

    // Deposits from Wells Fargo alerts wait as status 'deposit' until filed into an income line ('deposit_filed').
    @Query("SELECT * FROM Txn WHERE status = 'deposit' ORDER BY date DESC, id DESC") fun deposits(): Flow<List<Txn>>
    @Query("SELECT * FROM Txn WHERE status = 'deposit' AND substr(date, 1, 7) = :month") suspend fun depositsIn(month: String): List<Txn>
    @Query("SELECT * FROM Line WHERE id = :id") suspend fun line(id: Long): Line?

    @Query("SELECT * FROM Txn WHERE status != 'needs_review' AND source != 'history' AND categoryId IS NOT NULL ORDER BY date DESC, id DESC LIMIT 50")
    fun recent(): Flow<List<Txn>>

    @Query("SELECT * FROM Txn WHERE budgetMonth = :month AND categoryId IS NOT NULL")
    fun month(month: String): Flow<List<Txn>>

    @Query("SELECT * FROM Txn WHERE categoryId IS NOT NULL AND status != 'needs_review' ORDER BY date, id")
    suspend fun filed(): List<Txn>

    @Query("SELECT * FROM Txn WHERE id = :id")
    suspend fun txn(id: Long): Txn?

    @Query("SELECT DISTINCT budgetMonth FROM Txn WHERE sheetHash IS NOT NULL") suspend fun linkedMonths(): List<String>
    @Query("SELECT * FROM Txn WHERE budgetMonth IN (:months)") suspend fun inMonths(months: List<String>): List<Txn>
    @Query("SELECT * FROM Txn WHERE id IN (:ids)") suspend fun byIds(ids: List<Long>): List<Txn>
    @Query("UPDATE Txn SET sheetHash = :hash WHERE id = :id") suspend fun setSheetHash(id: Long, hash: String?)

    @Insert suspend fun insert(t: Txn): Long
    @Update suspend fun update(t: Txn)
    @Delete suspend fun delete(t: Txn)
    @Insert suspend fun insertAll(t: List<Txn>)

    @Query("DELETE FROM Txn WHERE source = 'history' AND sheetHash IS NULL")
    suspend fun deleteHistory()

    @Query("SELECT COUNT(*) FROM Txn WHERE source = 'history'")
    fun historyCount(): Flow<Int>

    @Query("SELECT * FROM Category ORDER BY sort, name")
    fun categories(): Flow<List<Category>>

    @Query("SELECT * FROM Category ORDER BY sort, name")
    suspend fun categoryList(): List<Category>

    @Upsert suspend fun save(c: Category)
    @Upsert suspend fun saveAll(c: List<Category>)

    @Query("UPDATE Txn SET categoryId = :to WHERE categoryId = :from")
    suspend fun moveCategory(from: String, to: String)

    @Query("SELECT * FROM MerchantAlias") suspend fun aliases(): List<MerchantAlias>
    @Upsert suspend fun save(a: MerchantAlias)

    @Query("SELECT * FROM Line WHERE month = :month AND deleted = 0 ORDER BY section, sort, id") fun lines(month: String): Flow<List<Line>>
    @Query("SELECT * FROM Line WHERE month = :month AND deleted = 0") suspend fun lineList(month: String): List<Line>
    @Query("SELECT * FROM Line WHERE month IN (:months)") suspend fun linesIn(months: List<String>): List<Line>
    @Query("SELECT * FROM Txn WHERE budgetMonth = :month AND categoryId IS NOT NULL AND status != 'needs_review'") suspend fun monthList(month: String): List<Txn>
    @Query("SELECT * FROM Txn WHERE status = 'needs_review' AND substr(date, 1, 7) = :month AND (account IS NULL OR account NOT LIKE '%Credit%')")
    suspend fun unfiledBankList(month: String): List<Txn>
    @Upsert suspend fun save(l: Line): Long
    @Delete suspend fun delete(l: Line)
    @Query("UPDATE Line SET sheetHash = :hash WHERE id = :id") suspend fun setLineHash(id: Long, hash: String?)
    @Query("SELECT * FROM Line WHERE onCard = 1 AND deleted = 0 AND actualCents IS NOT NULL ORDER BY month, sort") fun unpaidCardLines(): Flow<List<Line>>
    @Query("UPDATE Line SET onCard = :onCard WHERE id IN (:ids)") suspend fun setLinesOnCard(ids: List<Long>, onCard: Boolean)

    /** Card charges not paid off yet, any month (history is settled). */
    @Query("SELECT * FROM Txn WHERE account LIKE '%Credit%' AND cardPaid = 0 AND source != 'history' ORDER BY date, id")
    fun unpaidCard(): Flow<List<Txn>>
    @Query("UPDATE Txn SET cardPaid = :paid WHERE id IN (:ids)") suspend fun setCardPaid(ids: List<Long>, paid: Boolean)
    /** Bank (not card) purchases still waiting for a category: the money has left the account, so they count. */
    @Query("SELECT * FROM Txn WHERE status = 'needs_review' AND substr(date, 1, 7) = :month AND (account IS NULL OR account NOT LIKE '%Credit%')")
    fun unfiledBank(month: String): Flow<List<Txn>>

    @Insert suspend fun insert(a: RawAlert): Long
    @Query("SELECT * FROM RawAlert ORDER BY id DESC LIMIT 100") fun alerts(): Flow<List<RawAlert>>
    @Query("SELECT * FROM RawAlert WHERE txnId IS NULL ORDER BY id") suspend fun unparsedAlerts(): List<RawAlert>
    @Query("SELECT DISTINCT txnId FROM RawAlert WHERE txnId IN (:ids)") suspend fun alertTargets(ids: List<Long>): List<Long>
    @Query("UPDATE RawAlert SET txnId = :to WHERE txnId = :from") suspend fun repointAlerts(from: Long, to: Long)
    @Query("SELECT * FROM Txn WHERE sheetHash IS NOT NULL AND source != 'alert' AND amountCents = :cents AND budgetMonth IN (:months) " +
        "AND id NOT IN (SELECT txnId FROM RawAlert WHERE txnId IS NOT NULL) ORDER BY date")
    suspend fun unpairedSheetTxns(cents: Long, months: List<String>): List<Txn>
    @Update suspend fun update(a: RawAlert)
    @Query("SELECT COUNT(*) FROM RawAlert WHERE pkg = :pkg AND text = :text AND postedAt > :since")
    suspend fun seenAlert(pkg: String, text: String, since: Long): Int
}

@Database(entities = [Txn::class, Category::class, MerchantAlias::class, RawAlert::class, Line::class], version = 5, exportSchema = false)
abstract class AppDb : RoomDatabase() {
    abstract fun dao(): Dao_

    companion object {
        @Volatile private var instance: AppDb? = null
        fun get(ctx: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(ctx.applicationContext, AppDb::class.java, "budget.db")
                .addMigrations(object : Migration(1, 2) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE `Txn` ADD COLUMN `sheetHash` TEXT")
                    }
                }, object : Migration(2, 3) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE `Txn` ADD COLUMN `cardPaid` INTEGER NOT NULL DEFAULT 0")
                        db.execSQL("CREATE TABLE IF NOT EXISTS `Line` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `month` TEXT NOT NULL, " +
                            "`section` TEXT NOT NULL, `name` TEXT NOT NULL, `estimateCents` INTEGER, `actualCents` INTEGER, `sort` INTEGER NOT NULL, " +
                            "`sheetHash` TEXT, `deleted` INTEGER NOT NULL)")
                        db.execSQL("CREATE INDEX IF NOT EXISTS `index_Line_month` ON `Line` (`month`)")
                    }
                }, object : Migration(3, 4) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE `Line` ADD COLUMN `onCard` INTEGER NOT NULL DEFAULT 0")
                    }
                }, object : Migration(4, 5) {
                    override fun migrate(db: SupportSQLiteDatabase) {
                        db.execSQL("ALTER TABLE `Line` ADD COLUMN `inSurplus` INTEGER NOT NULL DEFAULT 0")
                        db.execSQL("UPDATE `Line` SET `inSurplus` = 1 WHERE `section` = 'income' AND `estimateCents` IS NOT NULL AND `estimateCents` != 0 " +
                            "AND lower(trim(`name`)) NOT IN ('extra leftover', 'leftover', 'carryover', 'carry over', 'monthly surplus')")
                    }
                }).build().also { instance = it }
        }
    }
}
