

# Receipt Scanner - Complete Implementation

After thoroughly analyzing your codebase, here's the full implementation that integrates seamlessly with your existing architecture.

## 1. Dependencies - `build.gradle (app)`

Add these to your existing dependencies:

```groovy
// ML Kit Text Recognition
implementation 'com.google.mlkit:text-recognition:16.0.1'

// Coil for image loading in Compose
implementation 'io.coil-kt:coil-compose:2.5.0'
```

## 2. AndroidManifest.xml Updates

Add these permissions and the file provider inside your `<manifest>` and `<application>` tags:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <!-- Existing permissions -->
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
    
    <!-- NEW: Camera permission for receipt scanning -->
    <uses-permission android:name="android.permission.CAMERA" />
    <uses-feature android:name="android.hardware.camera" android:required="false" />

    <application
        android:name=".ExpenseTrackerApp"
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.ExpenseTracker">

        <!-- Existing activity, service, receiver ... -->

        <!-- NEW: FileProvider for camera photos -->
        <provider
            android:name="androidx.core.content.FileProvider"
            android:authorities="${applicationId}.fileprovider"
            android:exported="false"
            android:grantUriPermissions="true">
            <meta-data
                android:name="android.support.FILE_PROVIDER_PATHS"
                android:resource="@xml/file_paths" />
        </provider>

    </application>
</manifest>
```

## 3. File Provider Paths - `res/xml/file_paths.xml`

Create `main/res/xml/file_paths.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="receipt_images" path="receipt_images/" />
    <files-path name="receipts" path="receipts/" />
</paths>
```

## 4. Entity - `ScannedReceipt.kt`

```kotlin
package com.yourname.expensetracker.data.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "scanned_receipts",
    foreignKeys = [
        ForeignKey(
            entity = Expense::class,
            parentColumns = ["id"],
            childColumns = ["expenseId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["expenseId"]),
        Index(value = ["createdAt"])
    ]
)
data class ScannedReceipt(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val imagePath: String,
    val rawOcrText: String,
    val parsedTotal: Double?,
    val parsedMerchant: String?,
    val parsedDate: Long?,
    val parsedItems: String?,        // JSON array of line items
    val parsedTaxAmount: Double?,
    val currency: String = "EUR",
    val confidence: Float,
    val expenseId: Long? = null,
    val createdAt: Long = System.currentTimeMillis()
)
```

## 5. DAO - `ScannedReceiptDao.kt`

```kotlin
package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import kotlinx.coroutines.flow.Flow

@Dao
interface ScannedReceiptDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(receipt: ScannedReceipt): Long

    @Update
    suspend fun update(receipt: ScannedReceipt)

    @Delete
    suspend fun delete(receipt: ScannedReceipt)

    @Query("SELECT * FROM scanned_receipts ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<ScannedReceipt>>

    @Query("SELECT * FROM scanned_receipts WHERE id = :id")
    suspend fun getById(id: Long): ScannedReceipt?

    @Query("SELECT * FROM scanned_receipts WHERE expenseId = :expenseId")
    suspend fun getByExpenseId(expenseId: Long): ScannedReceipt?

    @Query("SELECT COUNT(*) FROM scanned_receipts")
    suspend fun getCount(): Int

    @Query("DELETE FROM scanned_receipts")
    suspend fun deleteAll()

    @Query("UPDATE scanned_receipts SET expenseId = :expenseId WHERE id = :receiptId")
    suspend fun linkToExpense(receiptId: Long, expenseId: Long)
}
```

## 6. Updated `AppDatabase.kt`

```kotlin
package com.yourname.expensetracker.data.database

import com.yourname.expensetracker.data.database.entity.*
import com.yourname.expensetracker.data.database.dao.*
import androidx.room.*

@Database(
    entities = [
        RawNotification::class,
        BlockedPackage::class,
        Expense::class,
        Category::class,
        MerchantCategory::class,
        PendingReview::class,
        UserCorrection::class,
        SourceStats::class,
        Budget::class,
        ScannedReceipt::class  // NEW
    ],
    version = 9,  // BUMPED from 8
    exportSchema = false
)
@TypeConverters(com.yourname.expensetracker.data.database.converter.Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun rawNotificationDao(): RawNotificationDao
    abstract fun blockedPackageDao(): BlockedPackageDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun categoryDao(): CategoryDao
    abstract fun merchantCategoryDao(): MerchantCategoryDao
    abstract fun pendingReviewDao(): PendingReviewDao
    abstract fun userCorrectionDao(): UserCorrectionDao
    abstract fun sourceStatsDao(): SourceStatsDao
    abstract fun budgetDao(): BudgetDao
    abstract fun scannedReceiptDao(): ScannedReceiptDao  // NEW

    companion object {
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL(
                    "ALTER TABLE expenses ADD COLUMN paymentMethod TEXT NOT NULL DEFAULT 'UNKNOWN'"
                )
                database.execSQL(
                    "ALTER TABLE expenses ADD COLUMN isManualEntry INTEGER NOT NULL DEFAULT 0"
                )
                database.execSQL(
                    "ALTER TABLE expenses ADD COLUMN notes TEXT DEFAULT NULL"
                )
            }
        }

        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS budgets (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        categoryId INTEGER,
                        amount REAL NOT NULL,
                        period TEXT NOT NULL,
                        startDate INTEGER NOT NULL,
                        isActive INTEGER NOT NULL DEFAULT 1,
                        notifyAtWarning REAL NOT NULL DEFAULT 0.75,
                        notifyAtCritical REAL NOT NULL DEFAULT 0.9,
                        rollover INTEGER NOT NULL DEFAULT 0,
                        createdAt INTEGER NOT NULL,
                        lastWarningNotifiedAt INTEGER,
                        lastCriticalNotifiedAt INTEGER,
                        lastExceededNotifiedAt INTEGER,
                        FOREIGN KEY(categoryId) REFERENCES categories(id) ON DELETE SET NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_budgets_categoryId ON budgets (categoryId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_budgets_isActive ON budgets (isActive)")
            }
        }

        // NEW migration
        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(database: androidx.sqlite.db.SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS scanned_receipts (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        imagePath TEXT NOT NULL,
                        rawOcrText TEXT NOT NULL,
                        parsedTotal REAL,
                        parsedMerchant TEXT,
                        parsedDate INTEGER,
                        parsedItems TEXT,
                        parsedTaxAmount REAL,
                        currency TEXT NOT NULL DEFAULT 'EUR',
                        confidence REAL NOT NULL,
                        expenseId INTEGER,
                        createdAt INTEGER NOT NULL,
                        FOREIGN KEY(expenseId) REFERENCES expenses(id) ON DELETE SET NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS index_scanned_receipts_expenseId ON scanned_receipts (expenseId)")
                database.execSQL("CREATE INDEX IF NOT EXISTS index_scanned_receipts_createdAt ON scanned_receipts (createdAt)")
            }
        }
    }
}
```

## 7. Updated `AppModule.kt`

```kotlin
package com.yourname.expensetracker.di

import android.content.Context
import androidx.room.Room
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.*
import com.yourname.expensetracker.domain.parser.AppParserRegistry
import com.yourname.expensetracker.domain.parser.GenericTransactionParser
import com.yourname.expensetracker.domain.parser.parsers.GoogleWalletParser
import com.yourname.expensetracker.domain.parser.parsers.GreekBankParser
import com.yourname.expensetracker.domain.parser.parsers.RevolutParser
import com.yourname.expensetracker.domain.parser.parsers.SmsParser
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "expense_tracker_db"
        ).addMigrations(
            AppDatabase.MIGRATION_6_7,
            AppDatabase.MIGRATION_7_8,
            AppDatabase.MIGRATION_8_9  // NEW
        )
            .fallbackToDestructiveMigration()
            .setJournalMode(androidx.room.RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
            .build()
    }

    @Provides
    @Singleton
    fun provideRawNotificationDao(database: AppDatabase): RawNotificationDao {
        return database.rawNotificationDao()
    }

    @Provides
    @Singleton
    fun provideBlockedPackageDao(database: AppDatabase): BlockedPackageDao {
        return database.blockedPackageDao()
    }

    @Provides
    @Singleton
    fun provideExpenseDao(database: AppDatabase): ExpenseDao {
        return database.expenseDao()
    }

    @Provides
    @Singleton
    fun provideBudgetDao(database: AppDatabase): BudgetDao {
        return database.budgetDao()
    }

    // NEW
    @Provides
    @Singleton
    fun provideScannedReceiptDao(database: AppDatabase): ScannedReceiptDao {
        return database.scannedReceiptDao()
    }

    @Provides
    @Singleton
    fun provideAppParserRegistry(): AppParserRegistry {
        val appParsers = listOf(
            RevolutParser(),
            GoogleWalletParser(),
            GreekBankParser(),
            SmsParser()
        )
        val fallbackParser = GenericTransactionParser()
        return AppParserRegistry(appParsers, fallbackParser)
    }

    @Provides
    @Singleton
    fun provideCategoryDao(database: AppDatabase): CategoryDao = database.categoryDao()

    @Provides
    @Singleton
    fun provideMerchantCategoryDao(database: AppDatabase): MerchantCategoryDao = database.merchantCategoryDao()

    @Provides
    @Singleton
    fun providePendingReviewDao(database: AppDatabase): PendingReviewDao = database.pendingReviewDao()

    @Provides
    @Singleton
    fun provideUserCorrectionDao(database: AppDatabase): UserCorrectionDao = database.userCorrectionDao()

    @Provides
    @Singleton
    fun provideSourceStatsDao(database: AppDatabase): SourceStatsDao = database.sourceStatsDao()
}
```

## 8. Receipt Parser Engine - `ReceiptParser.kt`

```kotlin
package com.yourname.expensetracker.domain.receipt

import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReceiptParser @Inject constructor() {

    data class ParsedReceipt(
        val merchantName: String?,
        val total: Double?,
        val subtotal: Double?,
        val tax: Double?,
        val date: Long?,
        val currency: String,
        val lineItems: List<LineItem>,
        val confidence: Float
    )

    data class LineItem(
        val description: String,
        val quantity: Double?,
        val unitPrice: Double?,
        val totalPrice: Double
    )

    // Total amount patterns (Greek + English receipts)
    private val totalPatterns = listOf(
        // Greek patterns
        Pattern.compile(
            """(?:ΣΥΝΟΛΟ|ΤΕΛΙΚΟ|ΠΛΗΡΩΤΕΟ|ΓΕΝΙΚΟ\s*ΣΥΝΟΛΟ|TOTAL)\s*[:\s]*€?\s*(\d+[.,]\d{2})""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        ),
        // English patterns
        Pattern.compile(
            """(?:TOTAL|GRAND\s*TOTAL|AMOUNT\s*DUE|BALANCE\s*DUE|NET\s*TOTAL)\s*[:\s]*[€$£]?\s*(\d+[.,]\d{2})""",
            Pattern.CASE_INSENSITIVE
        ),
        // Amount with currency symbol at end
        Pattern.compile(
            """(?:TOTAL|ΣΥΝΟΛΟ)\s*[:\s]*(\d+[.,]\d{2})\s*(?:€|EUR)""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        ),
        // Amount at bottom (common format) - standalone € amount
        Pattern.compile(
            """(?:€|EUR)\s*(\d+[.,]\d{2})\s*$""",
            Pattern.MULTILINE
        ),
        // Standalone large amount near end of text
        Pattern.compile(
            """^\s*(\d+[.,]\d{2})\s*€?\s*$""",
            Pattern.MULTILINE
        )
    )

    // Tax patterns
    private val taxPatterns = listOf(
        Pattern.compile(
            """(?:ΦΠΑ|Φ\.?Π\.?Α\.?|VAT|TAX|TVA)\s*[\d%]*\s*[:\s]*€?\s*(\d+[.,]\d{2})""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        ),
        Pattern.compile(
            """(?:TAX|VAT)\s*(?:\d+%?)?\s*[:\s]*[€$£]?\s*(\d+[.,]\d{2})""",
            Pattern.CASE_INSENSITIVE
        )
    )

    // Date patterns
    private val datePatterns = listOf(
        Pattern.compile("""(\d{2})[/\-.](\d{2})[/\-.](\d{4})"""),  // DD/MM/YYYY
        Pattern.compile("""(\d{4})[/\-.](\d{2})[/\-.](\d{2})"""),  // YYYY/MM/DD
        Pattern.compile("""(\d{2})[/\-.](\d{2})[/\-.](\d{2})""")   // DD/MM/YY
    )

    // Line item pattern: "description  price" with at least 2 spaces or tab
    private val lineItemPatterns = listOf(
        // "Item description    12.50" or "Item description    12,50€"
        Pattern.compile(
            """^(.{3,40}?)\s{2,}(\d+[.,]\d{2})\s*€?\s*$""",
            Pattern.MULTILINE
        ),
        // "1 x Item description  12.50"
        Pattern.compile(
            """^(\d+)\s*[xX×]\s*(.{3,35}?)\s{2,}(\d+[.,]\d{2})\s*€?\s*$""",
            Pattern.MULTILINE
        )
    )

    // Subtotal patterns (to distinguish from total)
    private val subtotalPatterns = listOf(
        Pattern.compile(
            """(?:SUBTOTAL|SUB\s*TOTAL|ΥΠΟΣΥΝΟΛΟ|ΥΠΟ\s*ΣΥΝΟΛΟ|ΜΕΡΙΚΟ)\s*[:\s]*€?\s*(\d+[.,]\d{2})""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    )

    // Discount patterns
    private val discountPatterns = listOf(
        Pattern.compile(
            """(?:DISCOUNT|ΕΚΠΤΩΣΗ|SAVINGS?)\s*[:\s]*-?\s*€?\s*(\d+[.,]\d{2})""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    )

    fun parse(ocrText: String): ParsedReceipt {
        val lines = ocrText.lines().map { it.trim() }.filter { it.isNotBlank() }

        // 1. Extract merchant (usually first 1-3 lines)
        val merchant = extractMerchant(lines)

        // 2. Extract total (scan from bottom up — total is usually at the end)
        val total = extractTotal(ocrText)

        // 3. Extract subtotal
        val subtotal = extractSubtotal(ocrText)

        // 4. Extract tax
        val tax = extractTax(ocrText)

        // 5. Extract date
        val date = extractDate(ocrText)

        // 6. Extract line items
        val lineItems = extractLineItems(ocrText)

        // 7. Cross-validate: if we found items but no total, sum them
        val finalTotal = total ?: lineItems.sumOf { it.totalPrice }.takeIf { it > 0 }

        // 8. Calculate subtotal if we have total and tax
        val finalSubtotal = subtotal
            ?: if (finalTotal != null && tax != null) finalTotal - tax else null

        // 9. Confidence based on what we found
        val confidence = calculateConfidence(merchant, finalTotal, date, lineItems, tax)

        return ParsedReceipt(
            merchantName = merchant,
            total = finalTotal,
            subtotal = finalSubtotal,
            tax = tax,
            date = date,
            currency = detectCurrency(ocrText),
            lineItems = lineItems,
            confidence = confidence
        )
    }

    private fun extractMerchant(lines: List<String>): String? {
        // Skip noise patterns commonly found at top of receipts
        val skipPatterns = listOf(
            Regex("""(?i)(ΑΦΜ|ΔΟΥ|ΤΗΛ|TEL|FAX|VAT|RECEIPT|ΑΠΟΔΕΙΞΗ|ΤΙΜΟΛΟΓΙΟ)"""),
            Regex("""(?i)(www\.|http|@|\.com|\.gr)"""),
            Regex("""^\d{5,}$"""),  // Long number (phone, tax ID)
            Regex("""^\d+[/\-.]"""),  // Date-like
            Regex("""^[\d\s.,€$£]+$"""),  // Just numbers/currency
            Regex("""(?i)(ΤΑΜΕΙΟ|CASHIER|REGISTER|ΤΑΜΕΙΑΚΗ)"""),
            Regex("""^\*+$""")  // Just asterisks
        )

        val candidateLines = mutableListOf<String>()

        for (line in lines.take(7)) {
            val cleaned = line.trim()
            if (cleaned.length < 3) continue
            if (skipPatterns.any { it.containsMatchIn(cleaned) }) continue
            candidateLines.add(cleaned)
            if (candidateLines.size >= 2) break  // Usually merchant is 1-2 lines
        }

        return if (candidateLines.isNotEmpty()) {
            candidateLines.joinToString(" ").take(50).trim()
        } else null
    }

    private fun extractTotal(text: String): Double? {
        val allMatches = mutableListOf<Pair<Double, Int>>() // value, position

        for (pattern in totalPatterns) {
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
                val amount = matcher.group(1)?.replace(",", ".")?.toDoubleOrNull()
                if (amount != null && amount > 0 && amount < 50000) {
                    allMatches.add(Pair(amount, matcher.start()))
                }
            }
        }

        if (allMatches.isEmpty()) return null

        // Strategy: prefer matches closer to the bottom of the text
        // If multiple "TOTAL" matches, the LAST one is usually the grand total
        return allMatches
            .sortedByDescending { it.second }  // Bottom of receipt first
            .firstOrNull()?.first
    }

    private fun extractSubtotal(text: String): Double? {
        for (pattern in subtotalPatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                return matcher.group(1)?.replace(",", ".")?.toDoubleOrNull()
            }
        }
        return null
    }

    private fun extractTax(text: String): Double? {
        for (pattern in taxPatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                return matcher.group(1)?.replace(",", ".")?.toDoubleOrNull()
            }
        }
        return null
    }

    private fun extractDate(text: String): Long? {
        for (pattern in datePatterns) {
            val matcher = pattern.matcher(text)
            if (matcher.find()) {
                return try {
                    val groups = (1..matcher.groupCount()).map { matcher.group(it) }
                    val cal = Calendar.getInstance()

                    when {
                        groups[0].length == 4 -> { // YYYY/MM/DD
                            val year = groups[0].toInt()
                            val month = groups[1].toInt()
                            val day = groups[2].toInt()
                            if (month in 1..12 && day in 1..31) {
                                cal.set(year, month - 1, day, 0, 0, 0)
                                cal.timeInMillis
                            } else null
                        }
                        groups[2].length == 4 -> { // DD/MM/YYYY
                            val day = groups[0].toInt()
                            val month = groups[1].toInt()
                            val year = groups[2].toInt()
                            if (month in 1..12 && day in 1..31 && year in 2000..2099) {
                                cal.set(year, month - 1, day, 0, 0, 0)
                                cal.timeInMillis
                            } else null
                        }
                        else -> { // DD/MM/YY
                            val day = groups[0].toInt()
                            val month = groups[1].toInt()
                            val year = 2000 + groups[2].toInt()
                            if (month in 1..12 && day in 1..31) {
                                cal.set(year, month - 1, day, 0, 0, 0)
                                cal.timeInMillis
                            } else null
                        }
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }
        return null
    }

    private fun extractLineItems(text: String): List<LineItem> {
        val items = mutableListOf<LineItem>()

        // Skip lines that look like totals/subtotals
        val skipLinePattern = Regex(
            """(?i)(TOTAL|ΣΥΝΟΛΟ|VAT|ΦΠΑ|CHANGE|ΡΕΣΤΑ|CASH|CARD|VISA|MASTER|SUBTOTAL|ΥΠΟΣΥΝΟΛΟ|ΜΕΤΡΗΤΑ|ΚΑΡΤΑ|ΠΛΗΡΩΜΗ|PAYMENT|DISCOUNT|ΕΚΠΤΩΣΗ)"""
        )

        // Pattern 1: "description   amount"
        val matcher1 = lineItemPatterns[0].matcher(text)
        while (matcher1.find()) {
            val desc = matcher1.group(1)?.trim() ?: continue
            val price = matcher1.group(2)?.replace(",", ".")?.toDoubleOrNull() ?: continue
            if (skipLinePattern.containsMatchIn(desc)) continue
            if (price <= 0 || price > 10000) continue

            items.add(
                LineItem(
                    description = desc,
                    quantity = null,
                    unitPrice = null,
                    totalPrice = price
                )
            )
        }

        // Pattern 2: "qty x description   amount"
        val matcher2 = lineItemPatterns[1].matcher(text)
        while (matcher2.find()) {
            val qty = matcher2.group(1)?.toDoubleOrNull() ?: continue
            val desc = matcher2.group(2)?.trim() ?: continue
            val price = matcher2.group(3)?.replace(",", ".")?.toDoubleOrNull() ?: continue
            if (skipLinePattern.containsMatchIn(desc)) continue
            if (price <= 0 || price > 10000) continue

            items.add(
                LineItem(
                    description = desc,
                    quantity = qty,
                    unitPrice = if (qty > 0) price / qty else null,
                    totalPrice = price
                )
            )
        }

        return items
    }

    private fun detectCurrency(text: String): String {
        return when {
            text.contains("€") || text.contains("EUR", ignoreCase = true) ||
                    text.contains("ΕΥΡΩ", ignoreCase = true) -> "EUR"
            text.contains("$") || text.contains("USD", ignoreCase = true) -> "USD"
            text.contains("£") || text.contains("GBP", ignoreCase = true) -> "GBP"
            else -> "EUR"
        }
    }

    private fun calculateConfidence(
        merchant: String?,
        total: Double?,
        date: Long?,
        items: List<LineItem>,
        tax: Double?
    ): Float {
        var score = 0f
        if (merchant != null) score += 0.15f
        if (total != null) score += 0.40f  // Most important
        if (date != null) score += 0.15f
        if (items.isNotEmpty()) score += 0.15f
        if (tax != null) score += 0.05f

        // Bonus: items sum matches total (cross-validation)
        if (total != null && items.isNotEmpty()) {
            val itemsSum = items.sumOf { it.totalPrice }
            val diff = kotlin.math.abs(total - itemsSum)
            if (diff < total * 0.05) { // Within 5%
                score += 0.10f
            }
        }

        return score.coerceIn(0f, 1f)
    }

    // Utility: serialize line items to JSON
    fun lineItemsToJson(items: List<LineItem>): String {
        val jsonArray = JSONArray()
        for (item in items) {
            val obj = JSONObject().apply {
                put("description", item.description)
                put("totalPrice", item.totalPrice)
                item.quantity?.let { put("quantity", it) }
                item.unitPrice?.let { put("unitPrice", it) }
            }
            jsonArray.put(obj)
        }
        return jsonArray.toString()
    }

    // Utility: deserialize line items from JSON
    fun lineItemsFromJson(json: String?): List<LineItem> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val jsonArray = JSONArray(json)
            (0 until jsonArray.length()).map { i ->
                val obj = jsonArray.getJSONObject(i)
                LineItem(
                    description = obj.getString("description"),
                    totalPrice = obj.getDouble("totalPrice"),
                    quantity = if (obj.has("quantity")) obj.getDouble("quantity") else null,
                    unitPrice = if (obj.has("unitPrice")) obj.getDouble("unitPrice") else null
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
```

## 9. OCR Service - `ReceiptOcrService.kt`

```kotlin
package com.yourname.expensetracker.domain.receipt

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrResult(
    val fullText: String,
    val blocks: List<TextBlock>,
    val savedImagePath: String
)

data class TextBlock(
    val text: String,
    val confidence: Float?
)

@Singleton
class ReceiptOcrService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Process an image URI and return OCR results.
     * Also saves a compressed copy of the image for future reference.
     */
    suspend fun processImage(imageUri: Uri): OcrResult {
        // 1. Load and prepare the image
        val bitmap = loadAndCorrectBitmap(imageUri)
            ?: throw IllegalArgumentException("Could not load image from URI")

        // 2. Save compressed copy
        val savedPath = saveReceiptImage(bitmap)

        // 3. Run ML Kit OCR
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val visionText = recognizeText(inputImage)

        // 4. Extract blocks
        val blocks = visionText.textBlocks.map { block ->
            TextBlock(
                text = block.text,
                confidence = block.lines.firstOrNull()?.confidence
            )
        }

        return OcrResult(
            fullText = visionText.text,
            blocks = blocks,
            savedImagePath = savedPath
        )
    }

    private suspend fun recognizeText(
        image: InputImage
    ): com.google.mlkit.vision.text.Text {
        return suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { text ->
                    continuation.resume(text)
                }
                .addOnFailureListener { e ->
                    continuation.resumeWithException(e)
                }
        }
    }

    /**
     * Load bitmap from URI with EXIF rotation correction
     */
    private fun loadAndCorrectBitmap(uri: Uri): Bitmap? {
        return try {
            val inputStream: InputStream = context.contentResolver.openInputStream(uri)
                ?: return null

            // Decode with size limits to avoid OOM
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeStream(inputStream, null, options)
            inputStream.close()

            // Calculate sample size for images larger than 2048px
            val maxDimension = 2048
            var sampleSize = 1
            while (options.outWidth / sampleSize > maxDimension ||
                options.outHeight / sampleSize > maxDimension
            ) {
                sampleSize *= 2
            }

            // Decode actual bitmap
            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
            }
            val decodedStream = context.contentResolver.openInputStream(uri)
                ?: return null
            val bitmap = BitmapFactory.decodeStream(decodedStream, null, decodeOptions)
            decodedStream.close()

            // Apply EXIF rotation if needed
            bitmap?.let { correctRotation(it, uri) } ?: bitmap
        } catch (e: Exception) {
            null
        }
    }

    private fun correctRotation(bitmap: Bitmap, uri: Uri): Bitmap {
        return try {
            val inputStream = context.contentResolver.openInputStream(uri) ?: return bitmap
            val exif = ExifInterface(inputStream)
            inputStream.close()

            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                else -> return bitmap
            }

            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } catch (e: Exception) {
            bitmap
        }
    }

    /**
     * Save a compressed copy of the receipt image
     */
    private fun saveReceiptImage(bitmap: Bitmap): String {
        val receiptsDir = File(context.filesDir, "receipts")
        if (!receiptsDir.exists()) receiptsDir.mkdirs()

        val fileName = "receipt_${System.currentTimeMillis()}.jpg"
        val file = File(receiptsDir, fileName)

        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        }

        return file.absolutePath
    }

    /**
     * Create a temporary URI for the camera to write to
     */
    fun createTempImageUri(): Uri {
        val cacheDir = File(context.cacheDir, "receipt_images")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        val file = File(cacheDir, "camera_${System.currentTimeMillis()}.jpg")

        return androidx.core.content.FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }

    /**
     * Delete a saved receipt image
     */
    fun deleteImage(path: String) {
        try {
            File(path).delete()
        } catch (_: Exception) {
        }
    }
}
```

## 10. Receipt Repository - `ReceiptRepository.kt`

```kotlin
package com.yourname.expensetracker.data.repository

import android.net.Uri
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.ScannedReceiptDao
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.intelligence.MerchantNormalizer
import com.yourname.expensetracker.domain.receipt.OcrResult
import com.yourname.expensetracker.domain.receipt.ReceiptOcrService
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ReceiptRepository @Inject constructor(
    private val scannedReceiptDao: ScannedReceiptDao,
    private val expenseDao: ExpenseDao,
    private val merchantCategoryDao: MerchantCategoryDao,
    private val ocrService: ReceiptOcrService,
    private val receiptParser: ReceiptParser,
    private val categorizationEngine: CategorizationEngine,
    private val merchantNormalizer: MerchantNormalizer,
    private val budgetMonitor: BudgetMonitor
) {
    val allReceipts: Flow<List<ScannedReceipt>> = scannedReceiptDao.getAllFlow()

    /**
     * Process an image URI: run OCR, parse receipt, save to DB
     */
    suspend fun processReceipt(imageUri: Uri): Pair<ScannedReceipt, ReceiptParser.ParsedReceipt> {
        // 1. Run OCR
        val ocrResult: OcrResult = ocrService.processImage(imageUri)

        // 2. Parse the OCR text
        val parsed = receiptParser.parse(ocrResult.fullText)

        // 3. Normalize merchant if found
        val normalizedMerchant = parsed.merchantName?.let {
            merchantNormalizer.applyUserCorrections(it)
        }

        // 4. Save scanned receipt record
        val receipt = ScannedReceipt(
            imagePath = ocrResult.savedImagePath,
            rawOcrText = ocrResult.fullText,
            parsedTotal = parsed.total,
            parsedMerchant = normalizedMerchant ?: parsed.merchantName,
            parsedDate = parsed.date,
            parsedItems = if (parsed.lineItems.isNotEmpty())
                receiptParser.lineItemsToJson(parsed.lineItems) else null,
            parsedTaxAmount = parsed.tax,
            currency = parsed.currency,
            confidence = parsed.confidence
        )

        val receiptId = scannedReceiptDao.insert(receipt)

        return Pair(receipt.copy(id = receiptId), parsed)
    }

    /**
     * Create an expense from a scanned receipt (after user review/edit)
     */
    suspend fun createExpenseFromReceipt(
        receiptId: Long,
        merchant: String,
        amount: Double,
        currency: String = "EUR",
        categoryId: Long?,
        date: Long = System.currentTimeMillis(),
        paymentMethod: PaymentMethod = PaymentMethod.CARD,
        notes: String? = null
    ): Long {
        // 1. Normalize merchant
        val normalizedMerchant = merchantNormalizer.applyUserCorrections(merchant)

        // 2. Auto-categorize if no category provided
        val finalCategoryId = categoryId ?: categorizationEngine.categorize(normalizedMerchant)

        // 3. Check for duplicates
        val isDuplicate = expenseDao.isDuplicate(
            amount = amount,
            merchant = normalizedMerchant,
            date = date,
            windowMs = 60000 // 1 minute window for manual/scan entries
        )
        if (isDuplicate) return -1L

        // 4. Create expense
        val expense = Expense(
            amount = amount,
            currency = currency,
            merchant = normalizedMerchant,
            transactionType = TransactionType.PURCHASE,
            date = date,
            rawNotificationId = null,
            categoryId = finalCategoryId,
            paymentMethod = paymentMethod,
            isManualEntry = true, // Scanned receipts are treated as manual entries
            notes = notes ?: "Scanned from receipt"
        )

        val expenseId = expenseDao.insert(expense)

        // 5. Link receipt to expense
        if (expenseId > 0) {
            scannedReceiptDao.linkToExpense(receiptId, expenseId)

            // 6. Check budgets
            budgetMonitor.checkBudgets()

            // 7. Learn merchant → category mapping
            if (finalCategoryId != null) {
                val pattern = categorizationEngine.normalize(normalizedMerchant)
                if (pattern.isNotEmpty()) {
                    merchantCategoryDao.insert(
                        MerchantCategory(
                            merchantPattern = pattern,
                            categoryId = finalCategoryId,
                            confidence = 1.0f
                        )
                    )
                }
            }
        }

        return expenseId
    }

    fun createTempPhotoUri(): Uri {
        return ocrService.createTempImageUri()
    }

    suspend fun getReceiptById(id: Long): ScannedReceipt? {
        return scannedReceiptDao.getById(id)
    }

    suspend fun deleteReceipt(receipt: ScannedReceipt) {
        ocrService.deleteImage(receipt.imagePath)
        scannedReceiptDao.delete(receipt)
    }

    suspend fun getReceiptCount(): Int {
        return scannedReceiptDao.getCount()
    }
}
```

## 11. ViewModel - `ReceiptScanViewModel.kt`

```kotlin
package com.yourname.expensetracker.ui.screens.receiptscan

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.ReceiptRepository
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class ScanStep {
    CAPTURE,
    PROCESSING,
    REVIEW,
    DONE,
    ERROR
}

data class ReceiptScanState(
    val step: ScanStep = ScanStep.CAPTURE,
    val imageUri: Uri? = null,
    val tempCameraUri: Uri? = null,
    val parsedReceipt: ReceiptParser.ParsedReceipt? = null,
    val receiptId: Long? = null,
    val rawOcrText: String = "",
    val showRawText: Boolean = false,

    // Editable fields
    val editMerchant: String = "",
    val editAmount: String = "",
    val editDate: Long = System.currentTimeMillis(),
    val selectedCategoryId: Long? = null,
    val paymentMethod: PaymentMethod = PaymentMethod.CARD,
    val notes: String = "",

    // Meta
    val ocrConfidence: Float = 0f,
    val errorMessage: String? = null,
    val isSaving: Boolean = false,
    val saveResult: SaveReceiptResult? = null
)

sealed class SaveReceiptResult {
    object Success : SaveReceiptResult()
    object Duplicate : SaveReceiptResult()
    data class Error(val message: String) : SaveReceiptResult()
}

@HiltViewModel
class ReceiptScanViewModel @Inject constructor(
    private val receiptRepository: ReceiptRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

    private val _state = MutableStateFlow(ReceiptScanState())
    val state: StateFlow<ReceiptScanState> = _state.asStateFlow()

    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Create a URI for camera to write photo to
     */
    fun createTempPhotoUri(): Uri {
        val uri = receiptRepository.createTempPhotoUri()
        _state.update { it.copy(tempCameraUri = uri) }
        return uri
    }

    /**
     * Called after camera successfully captures a photo
     */
    fun processPhoto() {
        val uri = _state.value.tempCameraUri ?: return
        processImageUri(uri)
    }

    /**
     * Called when user selects image from gallery
     */
    fun processGalleryImage(uri: Uri) {
        processImageUri(uri)
    }

    private fun processImageUri(uri: Uri) {
        _state.update {
            it.copy(
                step = ScanStep.PROCESSING,
                imageUri = uri,
                errorMessage = null
            )
        }

        viewModelScope.launch {
            try {
                val (receipt, parsed) = receiptRepository.processReceipt(uri)

                _state.update {
                    it.copy(
                        step = ScanStep.REVIEW,
                        parsedReceipt = parsed,
                        receiptId = receipt.id,
                        rawOcrText = receipt.rawOcrText,
                        editMerchant = parsed.merchantName ?: "",
                        editAmount = parsed.total?.let { total ->
                            String.format("%.2f", total)
                        } ?: "",
                        editDate = parsed.date ?: System.currentTimeMillis(),
                        ocrConfidence = parsed.confidence,
                        selectedCategoryId = null // Will be auto-detected on save
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        step = ScanStep.ERROR,
                        errorMessage = e.message ?: "Failed to process receipt"
                    )
                }
            }
        }
    }

    fun updateMerchant(value: String) {
        _state.update { it.copy(editMerchant = value) }
    }

    fun updateAmount(value: String) {
        val filtered = value.filter { it.isDigit() || it == '.' || it == ',' }
        _state.update { it.copy(editAmount = filtered) }
    }

    fun updateDate(dateMs: Long) {
        _state.update { it.copy(editDate = dateMs) }
    }

    fun selectCategory(categoryId: Long) {
        _state.update { it.copy(selectedCategoryId = categoryId) }
    }

    fun selectPaymentMethod(method: PaymentMethod) {
        _state.update { it.copy(paymentMethod = method) }
    }

    fun updateNotes(value: String) {
        _state.update { it.copy(notes = value) }
    }

    fun toggleRawText() {
        _state.update { it.copy(showRawText = !it.showRawText) }
    }

    fun saveExpense() {
        val currentState = _state.value

        // Validate
        val merchant = currentState.editMerchant.trim()
        if (merchant.isBlank()) {
            _state.update {
                it.copy(errorMessage = "Merchant name is required")
            }
            return
        }

        val amount = currentState.editAmount.replace(",", ".").toDoubleOrNull()
        if (amount == null || amount <= 0) {
            _state.update {
                it.copy(errorMessage = "Enter a valid amount")
            }
            return
        }

        val receiptId = currentState.receiptId ?: return

        _state.update { it.copy(isSaving = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                val result = receiptRepository.createExpenseFromReceipt(
                    receiptId = receiptId,
                    merchant = merchant,
                    amount = amount,
                    currency = "EUR",
                    categoryId = currentState.selectedCategoryId,
                    date = currentState.editDate,
                    paymentMethod = currentState.paymentMethod,
                    notes = currentState.notes.takeIf { it.isNotBlank() }
                )

                if (result == -1L) {
                    _state.update {
                        it.copy(
                            isSaving = false,
                            saveResult = SaveReceiptResult.Duplicate
                        )
                    }
                } else {
                    _state.update {
                        it.copy(
                            isSaving = false,
                            step = ScanStep.DONE,
                            saveResult = SaveReceiptResult.Success
                        )
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isSaving = false,
                        saveResult = SaveReceiptResult.Error(
                            e.message ?: "Unknown error"
                        )
                    )
                }
            }
        }
    }

    fun retry() {
        _state.update {
            ReceiptScanState()  // Reset to initial state
        }
    }

    fun reset() {
        _state.update { ReceiptScanState() }
    }
}
```

## 12. Screen - `ReceiptScanScreen.kt`

```kotlin
package com.yourname.expensetracker.ui.screens.receiptscan

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.domain.receipt.ReceiptParser
import com.yourname.expensetracker.ui.screens.addexpense.CategoryGrid
import com.yourname.expensetracker.ui.screens.addexpense.DateSelector
import com.yourname.expensetracker.ui.screens.addexpense.PaymentMethodChip
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiptScanScreen(
    onDismiss: () -> Unit,
    viewModel: ReceiptScanViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    // Camera launcher
    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success) viewModel.processPhoto()
    }

    // Gallery launcher
    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.processGalleryImage(it) }
    }

    // Camera permission launcher
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val uri = viewModel.createTempPhotoUri()
            cameraLauncher.launch(uri)
        }
    }

    // Handle done step - auto-dismiss
    LaunchedEffect(state.step) {
        if (state.step == ScanStep.DONE) {
            delay(1500)
            onDismiss()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (state.step) {
                            ScanStep.CAPTURE -> "Scan Receipt"
                            ScanStep.PROCESSING -> "Processing..."
                            ScanStep.REVIEW -> "Review & Save"
                            ScanStep.DONE -> "Saved!"
                            ScanStep.ERROR -> "Error"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.reset()
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Close, "Close")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(scrollState)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (state.step) {
                ScanStep.CAPTURE -> CaptureStep(
                    imageUri = state.imageUri,
                    onCameraClick = {
                        val hasCameraPermission = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.CAMERA
                        ) == PackageManager.PERMISSION_GRANTED

                        if (hasCameraPermission) {
                            val uri = viewModel.createTempPhotoUri()
                            cameraLauncher.launch(uri)
                        } else {
                            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    },
                    onGalleryClick = {
                        galleryLauncher.launch("image/*")
                    }
                )

                ScanStep.PROCESSING -> ProcessingStep()

                ScanStep.REVIEW -> ReviewStep(
                    state = state,
                    categories = categories,
                    viewModel = viewModel
                )

                ScanStep.DONE -> DoneStep()

                ScanStep.ERROR -> ErrorStep(
                    errorMessage = state.errorMessage ?: "Unknown error",
                    onRetry = { viewModel.retry() }
                )
            }
        }
    }
}

@Composable
private fun CaptureStep(
    imageUri: Uri?,
    onCameraClick: () -> Unit,
    onGalleryClick: () -> Unit
) {
    Spacer(modifier = Modifier.height(32.dp))

    // Image preview area
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(300.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            if (imageUri != null) {
                AsyncImage(
                    model = imageUri,
                    contentDescription = "Receipt preview",
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Fit
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("🧾", fontSize = 64.sp)
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "Take a photo or select from gallery",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    // Action buttons
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Button(
            onClick = onCameraClick,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("📷 Camera")
        }
        OutlinedButton(
            onClick = onGalleryClick,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("🖼️ Gallery")
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Tips
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "📌 Tips for best results:",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text("• Place receipt on a flat, dark surface", style = MaterialTheme.typography.bodySmall)
            Text("• Ensure good lighting with no shadows", style = MaterialTheme.typography.bodySmall)
            Text("• Capture the entire receipt in frame", style = MaterialTheme.typography.bodySmall)
            Text("• Keep the camera steady", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ProcessingStep() {
    Spacer(modifier = Modifier.height(80.dp))
    CircularProgressIndicator(
        modifier = Modifier.size(64.dp),
        strokeWidth = 4.dp
    )
    Spacer(modifier = Modifier.height(24.dp))
    Text(
        "Scanning receipt...",
        style = MaterialTheme.typography.titleMedium
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "Reading text and extracting details",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReviewStep(
    state: ReceiptScanState,
    categories: List<Category>,
    viewModel: ReceiptScanViewModel
) {
    val parsed = state.parsedReceipt

    // Image preview (small)
    if (state.imageUri != null) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp),
            shape = RoundedCornerShape(12.dp)
        ) {
            AsyncImage(
                model = state.imageUri,
                contentDescription = "Receipt",
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(12.dp)),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(modifier = Modifier.height(12.dp))
    }

    // Confidence indicator
    ConfidenceIndicator(confidence = state.ocrConfidence)

    Spacer(modifier = Modifier.height(16.dp))

    // Merchant
    OutlinedTextField(
        value = state.editMerchant,
        onValueChange = { viewModel.updateMerchant(it) },
        label = { Text("Merchant") },
        placeholder = { Text("Store name") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Amount
    OutlinedTextField(
        value = state.editAmount,
        onValueChange = { viewModel.updateAmount(it) },
        label = { Text("Total Amount") },
        leadingIcon = { Text("€", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        singleLine = true,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Date
    DateSelector(
        dateMs = state.editDate,
        onDateSelected = { viewModel.updateDate(it) }
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Payment Method
    Text(
        "Payment Method",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium
    )
    Spacer(modifier = Modifier.height(4.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PaymentMethodChip(
            label = "💳 Card",
            selected = state.paymentMethod == PaymentMethod.CARD,
            onClick = { viewModel.selectPaymentMethod(PaymentMethod.CARD) },
            modifier = Modifier.weight(1f)
        )
        PaymentMethodChip(
            label = "💵 Cash",
            selected = state.paymentMethod == PaymentMethod.CASH,
            onClick = { viewModel.selectPaymentMethod(PaymentMethod.CASH) },
            modifier = Modifier.weight(1f)
        )
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Category
    Text(
        "Category",
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Medium
    )
    Spacer(modifier = Modifier.height(4.dp))
    CategoryGrid(
        categories = categories,
        selectedId = state.selectedCategoryId,
        onSelect = { viewModel.selectCategory(it) }
    )

    // Line items preview
    if (parsed?.lineItems?.isNotEmpty() == true) {
        Spacer(modifier = Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    "Detected Items (${parsed.lineItems.size})",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelLarge
                )
                Spacer(modifier = Modifier.height(8.dp))

                parsed.lineItems.forEachIndexed { index, item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            item.description,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "€${String.format("%.2f", item.totalPrice)}",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    if (index < parsed.lineItems.size - 1) {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 2.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
                        )
                    }
                }

                // Tax if detected
                parsed.tax?.let { tax ->
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            "Tax/VAT",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            "€${String.format("%.2f", tax)}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        }
    }

    // Notes
    Spacer(modifier = Modifier.height(12.dp))
    OutlinedTextField(
        value = state.notes,
        onValueChange = { viewModel.updateNotes(it) },
        label = { Text("Notes (optional)") },
        modifier = Modifier.fillMaxWidth(),
        minLines = 1,
        maxLines = 3
    )

    // Raw OCR toggle
    Spacer(modifier = Modifier.height(8.dp))
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { viewModel.toggleRawText() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Raw OCR Text",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Icon(
            if (state.showRawText) Icons.Default.KeyboardArrowUp
            else Icons.Default.KeyboardArrowDown,
            contentDescription = "Toggle",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    AnimatedVisibility(visible = state.showRawText) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 4.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            )
        ) {
            Text(
                text = state.rawOcrText.ifBlank { "No text detected" },
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp
            )
        }
    }

    // Error messages
    state.errorMessage?.let { error ->
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                "⚠️ $error",
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    when (state.saveResult) {
        is SaveReceiptResult.Duplicate -> {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "⚠️ A similar transaction already exists",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        is SaveReceiptResult.Error -> {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    "❌ ${(state.saveResult as SaveReceiptResult.Error).message}",
                    modifier = Modifier.padding(12.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
        else -> {}
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Save button
    Button(
        onClick = { viewModel.saveExpense() },
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp),
        enabled = !state.isSaving,
        shape = RoundedCornerShape(12.dp)
    ) {
        if (state.isSaving) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp
            )
        } else {
            Text("💾 Save Expense", fontSize = 16.sp)
        }
    }

    Spacer(modifier = Modifier.height(32.dp))
}

@Composable
private fun ConfidenceIndicator(confidence: Float) {
    val percentage = (confidence * 100).toInt()
    val color = when {
        confidence >= 0.7f -> Color(0xFF4CAF50)
        confidence >= 0.4f -> Color(0xFFFFC107)
        else -> Color(0xFFFF5722)
    }
    val label = when {
        confidence >= 0.7f -> "High confidence"
        confidence >= 0.4f -> "Medium confidence"
        else -> "Low confidence - please verify"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            "$label ($percentage%)",
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun DoneStep() {
    Spacer(modifier = Modifier.height(80.dp))
    Text("✅", fontSize = 72.sp)
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        "Expense saved!",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        "Your receipt has been processed and saved.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

@Composable
private fun ErrorStep(
    errorMessage: String,
    onRetry: () -> Unit
) {
    Spacer(modifier = Modifier.height(80.dp))
    Text("❌", fontSize = 64.sp)
    Spacer(modifier = Modifier.height(16.dp))
    Text(
        "Something went wrong",
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold
    )
    Spacer(modifier = Modifier.height(8.dp))
    Text(
        errorMessage,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error
    )
    Spacer(modifier = Modifier.height(24.dp))
    Button(
        onClick = onRetry,
        shape = RoundedCornerShape(12.dp)
    ) {
        Text("🔄 Try Again")
    }
}
```

## 13. Integration into HomeScreen

Update `HomeScreen.kt` to add the scan receipt button alongside the existing FAB. Replace the `showAddExpense` section with:

```kotlin
// In HomeScreen.kt, add these imports at the top:
// import com.yourname.expensetracker.ui.screens.receiptscan.ReceiptScanScreen

// Then in your HomeScreen composable, add state for receipt scanning:
var showScanReceipt by remember { mutableStateOf(false) }

// Update the FAB section to have two options:
// Replace the existing floatingActionButton with:
floatingActionButton = {
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Scan Receipt Mini FAB
        SmallFloatingActionButton(
            onClick = { showScanReceipt = true },
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer
        ) {
            Text("📷", fontSize = 18.sp)
        }
        // Main Add Expense FAB
        FloatingActionButton(
            onClick = { showAddExpense = true },
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = "Add Expense"
            )
        }
    }
}

// Then after the existing showAddExpense block, add:
if (showScanReceipt) {
    ReceiptScanScreen(
        onDismiss = { showScanReceipt = false }
    )
}
```

You'll need these additional imports in `HomeScreen.kt`:

```kotlin
import androidx.compose.material3.SmallFloatingActionButton
import com.yourname.expensetracker.ui.screens.receiptscan.ReceiptScanScreen
```

## 14. Updated HomeScreen.kt (Full replacement)

For completeness, here's the updated `HomeScreen` composable function (only the parts that change):

```kotlin
// Add to existing imports in HomeScreen.kt:
import com.yourname.expensetracker.ui.screens.receiptscan.ReceiptScanScreen

// Inside HomeScreen composable, add:
var showScanReceipt by remember { mutableStateOf(false) }

// Replace the Scaffold floatingActionButton parameter:
Scaffold(
    floatingActionButton = {
        Column(
            horizontalAlignment = Alignment.End,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            SmallFloatingActionButton(
                onClick = { showScanReceipt = true },
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer
            ) {
                Text("📷", fontSize = 18.sp)
            }
            FloatingActionButton(
                onClick = { showAddExpense = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Expense"
                )
            }
        }
    }
)

// After the showAddExpense block at the bottom of HomeScreen, add:
if (showScanReceipt) {
    ReceiptScanScreen(
        onDismiss = { showScanReceipt = false }
    )
}
```

---

## Summary of all files to create/modify:

### New files (7):
1. `res/xml/file_paths.xml`
2. `data/database/entity/ScannedReceipt.kt`
3. `data/database/dao/ScannedReceiptDao.kt`
4. `domain/receipt/ReceiptParser.kt`
5. `domain/receipt/ReceiptOcrService.kt`
6. `data/repository/ReceiptRepository.kt`
7. `ui/screens/receiptscan/ReceiptScanViewModel.kt`
8. `ui/screens/receiptscan/ReceiptScanScreen.kt`

### Modified files (4):
1. `AndroidManifest.xml` — Camera permission + FileProvider
2. `data/database/AppDatabase.kt` — New entity + migration 8→9 + new DAO
3. `di/AppModule.kt` — New migration + ScannedReceiptDao provider
4. `ui/screens/home/HomeScreen.kt` — Scan receipt button + screen launch

### Build file:
- `build.gradle (app)` — ML Kit + Coil dependencies