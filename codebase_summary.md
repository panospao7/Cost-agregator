# ExpenseTracker Full Source Code Extraction

This file contains the complete source code from the `src` directory.

## Table of Contents
1. [main\AndroidManifest.xml](#mainandroidmanifestxml)
2. [main\java\com\yourname\expensetracker\ExpenseTrackerApp.kt](#mainjavacomyournameexpensetrackerexpensetrackerappkt)
3. [main\java\com\yourname\expensetracker\data\database\AppDatabase.kt](#mainjavacomyournameexpensetrackerdatadatabaseappdatabasekt)
4. [main\java\com\yourname\expensetracker\data\database\entity\BlockedPackage.kt](#mainjavacomyournameexpensetrackerdatadatabaseentityblockedpackagekt)
5. [main\java\com\yourname\expensetracker\data\database\entity\Budget.kt](#mainjavacomyournameexpensetrackerdatadatabaseentitybudgetkt)
6. [main\java\com\yourname\expensetracker\data\database\entity\Category.kt](#mainjavacomyournameexpensetrackerdatadatabaseentitycategorykt)
7. [main\java\com\yourname\expensetracker\data\database\entity\Expense.kt](#mainjavacomyournameexpensetrackerdatadatabaseentityexpensekt)
8. [main\java\com\yourname\expensetracker\data\database\entity\MerchantCategory.kt](#mainjavacomyournameexpensetrackerdatadatabaseentitymerchantcategorykt)
9. [main\java\com\yourname\expensetracker\data\database\entity\PendingReview.kt](#mainjavacomyournameexpensetrackerdatadatabaseentitypendingreviewkt)
10. [main\java\com\yourname\expensetracker\data\database\entity\RawNotification.kt](#mainjavacomyournameexpensetrackerdatadatabaseentityrawnotificationkt)
11. [main\java\com\yourname\expensetracker\data\database\entity\ScannedReceipt.kt](#mainjavacomyournameexpensetrackerdatadatabaseentityscannedreceiptkt)
12. [main\java\com\yourname\expensetracker\data\database\entity\SourceStats.kt](#mainjavacomyournameexpensetrackerdatadatabaseentitysourcestatskt)
13. [main\java\com\yourname\expensetracker\data\database\entity\UserCorrection.kt](#mainjavacomyournameexpensetrackerdatadatabaseentityusercorrectionkt)
14. [main\java\com\yourname\expensetracker\data\repository\BudgetRepository.kt](#mainjavacomyournameexpensetrackerdatarepositorybudgetrepositorykt)
15. [main\java\com\yourname\expensetracker\data\repository\CategoryRepository.kt](#mainjavacomyournameexpensetrackerdatarepositorycategoryrepositorykt)
16. [main\java\com\yourname\expensetracker\data\repository\NotificationRepository.kt](#mainjavacomyournameexpensetrackerdatarepositorynotificationrepositorykt)
17. [main\java\com\yourname\expensetracker\data\repository\ReceiptRepository.kt](#mainjavacomyournameexpensetrackerdatarepositoryreceiptrepositorykt)
18. [main\java\com\yourname\expensetracker\domain\analytics\AnalyticsModels.kt](#mainjavacomyournameexpensetrackerdomainanalyticsanalyticsmodelskt)
19. [main\java\com\yourname\expensetracker\domain\analytics\InsightsEngine.kt](#mainjavacomyournameexpensetrackerdomainanalyticsinsightsenginekt)
20. [main\java\com\yourname\expensetracker\domain\budget\BudgetModels.kt](#mainjavacomyournameexpensetrackerdomainbudgetbudgetmodelskt)
21. [main\java\com\yourname\expensetracker\domain\budget\BudgetMonitor.kt](#mainjavacomyournameexpensetrackerdomainbudgetbudgetmonitorkt)
22. [main\java\com\yourname\expensetracker\domain\categorization\CategorizationEngine.kt](#mainjavacomyournameexpensetrackerdomaincategorizationcategorizationenginekt)
23. [main\java\com\yourname\expensetracker\domain\debug\NotificationSeeder.kt](#mainjavacomyournameexpensetrackerdomaindebugnotificationseederkt)
24. [main\java\com\yourname\expensetracker\domain\intelligence\ConfidenceRouter.kt](#mainjavacomyournameexpensetrackerdomainintelligenceconfidencerouterkt)
25. [main\java\com\yourname\expensetracker\domain\intelligence\MerchantNormalizer.kt](#mainjavacomyournameexpensetrackerdomainintelligencemerchantnormalizerkt)
26. [main\java\com\yourname\expensetracker\domain\intelligence\TransactionClassifier.kt](#mainjavacomyournameexpensetrackerdomainintelligencetransactionclassifierkt)
27. [main\java\com\yourname\expensetracker\domain\receipt\ReceiptOcrService.kt](#mainjavacomyournameexpensetrackerdomainreceiptreceiptocrservicekt)
28. [main\java\com\yourname\expensetracker\ui\MainActivity.kt](#mainjavacomyournameexpensetrackeruimainactivitykt)
29. [main\java\com\yourname\expensetracker\ui\MainViewModel.kt](#mainjavacomyournameexpensetrackeruimainviewmodelkt)
30. [main\java\com\yourname\expensetracker\ui\screens\addexpense\AddExpenseSheet.kt](#mainjavacomyournameexpensetrackeruiscreensaddexpenseaddexpensesheetkt)
31. [main\java\com\yourname\expensetracker\ui\screens\addexpense\AddExpenseViewModel.kt](#mainjavacomyournameexpensetrackeruiscreensaddexpenseaddexpenseviewmodelkt)
32. [main\java\com\yourname\expensetracker\ui\screens\analytics\AnalyticsScreen.kt](#mainjavacomyournameexpensetrackeruiscreensanalyticsanalyticsscreenkt)
33. [main\java\com\yourname\expensetracker\ui\screens\analytics\AnalyticsViewModel.kt](#mainjavacomyournameexpensetrackeruiscreensanalyticsanalyticsviewmodelkt)
34. [main\java\com\yourname\expensetracker\ui\screens\budget\BudgetScreen.kt](#mainjavacomyournameexpensetrackeruiscreensbudgetbudgetscreenkt)
35. [main\java\com\yourname\expensetracker\ui\screens\budget\BudgetViewModel.kt](#mainjavacomyournameexpensetrackeruiscreensbudgetbudgetviewmodelkt)
36. [main\java\com\yourname\expensetracker\ui\screens\categories\CategoryScreen.kt](#mainjavacomyournameexpensetrackeruiscreenscategoriescategoryscreenkt)
37. [main\java\com\yourname\expensetracker\ui\screens\categories\CategoryViewModel.kt](#mainjavacomyournameexpensetrackeruiscreenscategoriescategoryviewmodelkt)
38. [main\java\com\yourname\expensetracker\ui\screens\debug\DebugScreen.kt](#mainjavacomyournameexpensetrackeruiscreensdebugdebugscreenkt)
39. [main\java\com\yourname\expensetracker\ui\screens\debug\DebugViewModel.kt](#mainjavacomyournameexpensetrackeruiscreensdebugdebugviewmodelkt)
40. [main\java\com\yourname\expensetracker\ui\screens\home\HomeScreen.kt](#mainjavacomyournameexpensetrackeruiscreenshomehomescreenkt)
41. [main\java\com\yourname\expensetracker\ui\screens\home\HomeViewModel.kt](#mainjavacomyournameexpensetrackeruiscreenshomehomeviewmodelkt)
42. [main\java\com\yourname\expensetracker\ui\screens\receiptscan\ReceiptScanScreen.kt](#mainjavacomyournameexpensetrackeruiscreensreceiptscanreceiptscanscreenkt)
43. [main\java\com\yourname\expensetracker\ui\screens\receiptscan\ReceiptScanViewModel.kt](#mainjavacomyournameexpensetrackeruiscreensreceiptscanreceiptscanviewmodelkt)
44. [main\java\com\yourname\expensetracker\ui\screens\review\ReviewScreen.kt](#mainjavacomyournameexpensetrackeruiscreensreviewreviewscreenkt)
45. [main\java\com\yourname\expensetracker\ui\screens\review\ReviewViewModel.kt](#mainjavacomyournameexpensetrackeruiscreensreviewreviewviewmodelkt)
46. [main\java\com\yourname\expensetracker\ui\screens\transactions\TransactionsScreen.kt](#mainjavacomyournameexpensetrackeruiscreenstransactionstransactionsscreenkt)
47. [main\java\com\yourname\expensetracker\ui\screens\transactions\TransactionsViewModel.kt](#mainjavacomyournameexpensetrackeruiscreenstransactionstransactionsviewmodelkt)
48. [main\java\com\yourname\expensetracker\ui\theme\Theme.kt](#mainjavacomyournameexpensetrackeruithemethemekt)
49. [main\res\drawable\ic_launcher_background.xml](#mainresdrawableic_launcher_backgroundxml)
50. [main\res\drawable\ic_launcher_foreground.xml](#mainresdrawableic_launcher_foregroundxml)
51. [main\res\mipmap-anydpi-v26\ic_launcher.xml](#mainresmipmap-anydpi-v26ic_launcherxml)
52. [main\res\mipmap-anydpi-v26\ic_launcher_round.xml](#mainresmipmap-anydpi-v26ic_launcher_roundxml)
53. [main\res\mipmap\ic_launcher.xml](#mainresmipmapic_launcherxml)
54. [main\res\mipmap\ic_launcher_round.xml](#mainresmipmapic_launcher_roundxml)
55. [main\res\values\strings.xml](#mainresvaluesstringsxml)
56. [main\res\values\themes.xml](#mainresvaluesthemesxml)
57. [main\res\xml\file_paths.xml](#mainresxmlfile_pathsxml)

---

## main\AndroidManifest.xml <a name="mainandroidmanifestxml"></a>
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">
    <!-- Keep service running -->
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
        <activity
            android:name=".ui.MainActivity"
            android:exported="true"
            android:theme="@style/Theme.ExpenseTracker">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
        <!-- THE CRITICAL SERVICE -->
        <service
            android:name=".service.NotificationCaptureService"
            android:exported="true"
            android:foregroundServiceType="dataSync"
            android:permission="android.permission.BIND_NOTIFICATION_LISTENER_SERVICE">
            <intent-filter>
                <action android:name="android.service.notification.NotificationListenerService" />
            </intent-filter>
        </service>
        <receiver
            android:name=".receiver.BootReceiver"
            android:enabled="true"
            android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
                <action android:name="android.intent.action.MY_PACKAGE_REPLACED" />
            </intent-filter>
        </receiver>
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

---

## main\java\com\yourname\expensetracker\ExpenseTrackerApp.kt <a name="mainjavacomyournameexpensetrackerexpensetrackerappkt"></a>
```kotlin
package com.yourname.expensetracker
import android.app.Application
import dagger.hilt.android.HiltAndroidApp
@HiltAndroidApp
class ExpenseTrackerApp : Application()

```

---

## main\java\com\yourname\expensetracker\data\database\AppDatabase.kt <a name="mainjavacomyournameexpensetrackerdatadatabaseappdatabasekt"></a>
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
        ScannedReceipt::class
    ],
    version = 9,
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
    abstract fun scannedReceiptDao(): ScannedReceiptDao
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

---

## main\java\com\yourname\expensetracker\data\database\entity\BlockedPackage.kt <a name="mainjavacomyournameexpensetrackerdatadatabaseentityblockedpackagekt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity
import androidx.room.Entity
import androidx.room.PrimaryKey
@Entity(tableName = "blocked_packages")
data class BlockedPackage(
    @PrimaryKey
    val packageName: String,
    val blockedAt: Long = System.currentTimeMillis()
)

```

---

## main\java\com\yourname\expensetracker\data\database\entity\Budget.kt <a name="mainjavacomyournameexpensetrackerdatadatabaseentitybudgetkt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
enum class BudgetPeriod {
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY
}
@Entity(
    tableName = "budgets",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["categoryId"]),
        Index(value = ["isActive"])
    ]
)
data class Budget(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val categoryId: Long?,              // null = overall budget
    val amount: Double,
    val period: BudgetPeriod,
    val startDate: Long,                // anchor date for period calculation
    val isActive: Boolean = true,
    val notifyAtWarning: Float = 0.75f, // first alert threshold (75%)
    val notifyAtCritical: Float = 0.90f,// second alert threshold (90%)
    val rollover: Boolean = false,      // carry unspent to next period
    val createdAt: Long = System.currentTimeMillis(),
    val lastWarningNotifiedAt: Long? = null,
    val lastCriticalNotifiedAt: Long? = null,
    val lastExceededNotifiedAt: Long? = null
)

```

---

## main\java\com\yourname\expensetracker\data\database\entity\Category.kt <a name="mainjavacomyournameexpensetrackerdatadatabaseentitycategorykt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity
import androidx.room.Entity
import androidx.room.PrimaryKey
@Entity(tableName = "categories")
data class Category(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val icon: String, // Emoji or simple string
    val color: String, // Hex color code
    val isDefault: Boolean = false // If true, cannot be deleted (easily)
)

```

---

## main\java\com\yourname\expensetracker\data\database\entity\Expense.kt <a name="mainjavacomyournameexpensetrackerdatadatabaseentityexpensekt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
@Entity(
    tableName = "expenses",
    foreignKeys = [
        ForeignKey(
            entity = RawNotification::class,
            parentColumns = ["id"],
            childColumns = ["rawNotificationId"],
            onDelete = ForeignKey.SET_NULL
        ),
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["rawNotificationId"]),
        Index(value = ["date"]),
        Index(value = ["categoryId"]),
        Index(value = ["transactionType"]), // New: for type filtering
        Index(value = ["categoryId", "date"]), // New: for category breakdown
        Index(value = ["amount", "merchant", "date"])
    ]
)
data class Expense(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val amount: Double,
    val currency: String = "EUR", // ISO 4217 Code
    val merchant: String, // Extracted merchant name
    val transactionType: TransactionType, // PURCHASE, WITHDRAWAL, etc.
    val date: Long, // Transaction date (best guess)
    val rawNotificationId: Long? = null, // Link to source
    val categoryId: Long? = null, // Link to category
    val createdAt: Long = System.currentTimeMillis(),
    // New fields
    val paymentMethod: PaymentMethod = PaymentMethod.UNKNOWN,
    val isManualEntry: Boolean = false,
    val notes: String? = null
)
enum class TransactionType {
    PURCHASE,
    WITHDRAWAL,
    TRANSFER,
    DEPOSIT,
    UNKNOWN
}
enum class PaymentMethod {
    CARD,
    CASH,
    BANK_TRANSFER,
    UNKNOWN
}

```

---

## main\java\com\yourname\expensetracker\data\database\entity\MerchantCategory.kt <a name="mainjavacomyournameexpensetrackerdatadatabaseentitymerchantcategorykt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
@Entity(
    tableName = "merchant_categories",
    foreignKeys = [
        ForeignKey(
            entity = Category::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["categoryId"])]
)
data class MerchantCategory(
    @PrimaryKey
    val merchantPattern: String, // The normalized merchant name (e.g., "SKLAVENITIS")
    val categoryId: Long,
    val confidence: Float = 1.0f,
    val timesUsed: Int = 1
)

```

---

## main\java\com\yourname\expensetracker\data\database\entity\PendingReview.kt <a name="mainjavacomyournameexpensetrackerdatadatabaseentitypendingreviewkt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
@Entity(
    tableName = "pending_reviews",
    foreignKeys = [
        ForeignKey(
            entity = RawNotification::class,
            parentColumns = ["id"],
            childColumns = ["rawNotificationId"],
            onDelete = ForeignKey.SET_NULL
        )
    ],
    indices = [
        Index(value = ["rawNotificationId"]),
        Index(value = ["status"]),
        Index(value = ["status", "createdAt"]) // New: for Pending order by date
    ]
)
data class PendingReview(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val rawNotificationId: Long,
    val suggestedAmount: Double,
    val suggestedCurrency: String,
    val suggestedMerchant: String,
    val suggestedType: String,          // TransactionType name
    val suggestedCategoryId: Long?,
    val confidence: Float,
    val packageName: String,
    val notificationTitle: String?,
    val notificationText: String?,
    val createdAt: Long = System.currentTimeMillis(),
    val status: String = "PENDING"      // PENDING, APPROVED, REJECTED, MODIFIED
)

```

---

## main\java\com\yourname\expensetracker\data\database\entity\RawNotification.kt <a name="mainjavacomyournameexpensetrackerdatadatabaseentityrawnotificationkt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
@Entity(
    tableName = "raw_notifications",
    indices = [
        Index(value = ["packageName", "timestamp"]),
        Index(value = ["capturedAt"]) // New: for sorting in Debug screen
    ]
)
data class RawNotification(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    // Source app
    val packageName: String,
    val appName: String?,
    // Notification content
    val title: String?,
    val text: String?,
    val bigText: String? = null,          // Expanded notification
    val subText: String? = null,
    // Raw extras as JSON string for debugging
    val extrasJson: String? = null,
    // Metadata
    val timestamp: Long,           // When notification was posted
    val capturedAt: Long,          // When we captured it
    // Processing status
    val isProcessed: Boolean = false,
    val isRelevant: Boolean? = null,  // null = unknown, true = expense, false = ignore
    val parseResult: String? = null    // JSON of parsed data or error message
)

```

---

## main\java\com\yourname\expensetracker\data\database\entity\ScannedReceipt.kt <a name="mainjavacomyournameexpensetrackerdatadatabaseentityscannedreceiptkt"></a>
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

---

## main\java\com\yourname\expensetracker\data\database\entity\SourceStats.kt <a name="mainjavacomyournameexpensetrackerdatadatabaseentitysourcestatskt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity
import androidx.room.Entity
import androidx.room.PrimaryKey
@Entity(tableName = "source_stats")
data class SourceStats(
    @PrimaryKey val packageName: String,
    val totalNotifications: Int = 0,
    val acceptedAsExpense: Int = 0,
    val rejectedByUser: Int = 0,
    val autoRejected: Int = 0,
    val pendingReview: Int = 0,
    val lastSeen: Long = System.currentTimeMillis()
) {
    val trustScore: Float
        get() = if (totalNotifications > 0)
            acceptedAsExpense.toFloat() / totalNotifications
        else 0f
    val isLikelySpam: Boolean
        get() = totalNotifications > 10 && trustScore < 0.05f
}

```

---

## main\java\com\yourname\expensetracker\data\database\entity\UserCorrection.kt <a name="mainjavacomyournameexpensetrackerdatadatabaseentityusercorrectionkt"></a>
```kotlin
package com.yourname.expensetracker.data.database.entity
import androidx.room.Entity
import androidx.room.PrimaryKey
@Entity(tableName = "user_corrections")
data class UserCorrection(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val originalMerchant: String,
    val correctedMerchant: String?,
    val originalAmount: Double,
    val correctedAmount: Double?,
    val originalCategoryId: Long?,
    val correctedCategoryId: Long?,
    val wasRejected: Boolean = false,    // User said "this isn't a transaction"
    val wasApproved: Boolean = false,    // User confirmed it was correct
    val notificationTitle: String?,
    val notificationText: String?,
    val createdAt: Long = System.currentTimeMillis()
)

```

---

## main\java\com\yourname\expensetracker\data\repository\BudgetRepository.kt <a name="mainjavacomyournameexpensetrackerdatarepositorybudgetrepositorykt"></a>
```kotlin
package com.yourname.expensetracker.data.repository
import com.yourname.expensetracker.data.database.dao.BudgetDao
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.budget.BudgetSuggestion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class BudgetRepository @Inject constructor(
    private val budgetDao: BudgetDao,
    private val categoryDao: CategoryDao,
    private val expenseDao: ExpenseDao,
    private val budgetMonitor: BudgetMonitor
) {
    val allBudgets: Flow<List<Budget>> = budgetDao.getAllFlow()
    val activeBudgets: Flow<List<Budget>> = budgetDao.getActiveBudgetsFlow()
    fun getBudgetStatuses(): Flow<List<BudgetStatus>> {
        return combine(
            budgetDao.getActiveBudgetsFlow(),
            categoryDao.getAllFlow()
        ) { budgets, categories ->
            val categoryMap = categories.associateBy { it.id }
            budgets.map { budget ->
                val window = budgetMonitor.calculatePeriodWindow(budget.period, budget.startDate)
                val spent = if (budget.categoryId != null) {
                    expenseDao.getCategorySpentInPeriod(budget.categoryId, window.first, window.second)
                } else {
                    expenseDao.getTotalForPeriod(window.first, window.second)
                }
                val percent = if (budget.amount > 0) (spent / budget.amount).toFloat() else 0f
                val remaining = (budget.amount - spent).coerceAtLeast(0.0)
                val health = when {
                    percent >= 1.0f -> BudgetHealthStatus.EXCEEDED
                    percent >= budget.notifyAtCritical -> BudgetHealthStatus.CRITICAL
                    percent >= budget.notifyAtWarning -> BudgetHealthStatus.WARNING
                    else -> BudgetHealthStatus.ON_TRACK
                }
                BudgetStatus(
                    budget = budget,
                    category = categoryMap[budget.categoryId],
                    spentAmount = spent,
                    remainingAmount = remaining,
                    percentUsed = percent,
                    healthStatus = health,
                    periodStart = window.first,
                    periodEnd = window.second
                )
            }
        }
    }
    suspend fun addBudget(budget: Budget): Long {
        val id = budgetDao.insert(budget)
        budgetMonitor.checkBudgets()
        return id
    }
    suspend fun updateBudget(budget: Budget) {
        budgetDao.update(budget)
        budgetMonitor.checkBudgets()
    }
    suspend fun deleteBudget(budget: Budget) {
        budgetDao.delete(budget)
    }
    suspend fun toggleBudget(id: Long, isActive: Boolean) {
        budgetDao.setActive(id, isActive)
        budgetMonitor.checkBudgets()
    }
    suspend fun deleteAll() {
        budgetDao.deleteAll()
    }
    suspend fun getSuggestions(): List<BudgetSuggestion> {
        val categories = categoryDao.getAllFlow().first()
        val suggestions = mutableListOf<BudgetSuggestion>()
        // Suggest budgets for top-spending categories that don't have one
        val activeBudgets = budgetDao.getActiveBudgets()
        val categoriesWithBudget = activeBudgets.mapNotNull { it.categoryId }.toSet()
        val now = System.currentTimeMillis()
        val threeMonthsAgo = now - (90L * 24 * 60 * 60 * 1000)
        for (category in categories) {
            if (categoriesWithBudget.contains(category.id)) continue
            val spent = expenseDao.getCategorySpentInPeriod(category.id, threeMonthsAgo, now)
            if (spent > 50.0) { // Only suggest for categories with significant spend
                val monthlyAvg = spent / 3.0
                suggestions.add(
                    BudgetSuggestion(
                        categoryId = category.id,
                        categoryName = category.name,
                        categoryIcon = category.icon,
                        suggestedAmount = (monthlyAvg * 1.1).coerceAtLeast(20.0), // 10% buffer
                        basedOnMonths = 3,
                        reason = "Based on your €${"%.0f".format(monthlyAvg)} monthly average spend."
                    )
                )
            }
        }
        return suggestions.sortedByDescending { it.suggestedAmount }.take(3)
    }
}

```

---

## main\java\com\yourname\expensetracker\data\repository\CategoryRepository.kt <a name="mainjavacomyournameexpensetrackerdatarepositorycategoryrepositorykt"></a>
```kotlin
package com.yourname.expensetracker.data.repository
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class CategoryRepository @Inject constructor(
    private val categoryDao: CategoryDao,
    private val merchantCategoryDao: MerchantCategoryDao,
    private val categorizationEngine: CategorizationEngine
) {
    val allCategories: Flow<List<Category>> = categoryDao.getAllFlow()
    suspend fun ensureDefaultCategories() {
        if (categoryDao.getCount() == 0) {
            // Seed Categories
            val defaults = com.yourname.expensetracker.data.provider.MerchantCategoryProvider.categoryBlueprints
            categoryDao.insertAll(defaults)
            // Seed Merchant Dictionary
            // We need to resolve Category IDs first to map names to IDs
            val categories = categoryDao.getAllFlow().first() // Use flow first emission or simple get
            // Actually, let's use a non-flow direct access if possible or collect once
            // Adding a simple getAll helper to DAO would be cleaner, but for now:
            // Map: "Groceries" -> 1, "Transport" -> 2
            val categoryIdMap = categories.associate { it.name to it.id }
            val merchantMap = com.yourname.expensetracker.data.provider.MerchantCategoryProvider.getExpandedMap()
            val merchantEntities = merchantMap.mapNotNull { (merchant, categoryName) ->
               val catId = categoryIdMap[categoryName]
               if (catId != null) {
                   MerchantCategory(merchantPattern = merchant, categoryId = catId)
               } else {
                   null
               }
            }
            if (merchantEntities.isNotEmpty()) {
                // We need a bulk insert for speed
                merchantCategoryDao.insertAll(merchantEntities)
            }
        }
    }
    suspend fun addCategory(name: String, icon: String, color: String) {
        val category = Category(name = name, icon = icon, color = color)
        categoryDao.insert(category)
    }
    suspend fun learnMerchantCategory(merchantName: String, categoryId: Long) {
        val normalized = categorizationEngine.normalize(merchantName)
        val mapping = MerchantCategory(merchantPattern = normalized, categoryId = categoryId)
        merchantCategoryDao.insert(mapping)
    }
}

```

---

## main\java\com\yourname\expensetracker\data\repository\NotificationRepository.kt <a name="mainjavacomyournameexpensetrackerdatarepositorynotificationrepositorykt"></a>
```kotlin
package com.yourname.expensetracker.data.repository
import androidx.room.*
import com.yourname.expensetracker.data.database.dao.*
import com.yourname.expensetracker.data.database.entity.*
import com.yourname.expensetracker.domain.budget.BudgetMonitor
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.intelligence.ConfidenceRouter
import com.yourname.expensetracker.domain.intelligence.MerchantNormalizer
import com.yourname.expensetracker.domain.intelligence.RoutingDecision
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.intelligence.ClassifierStats
import com.yourname.expensetracker.domain.parser.AppParserRegistry
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.*
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class NotificationRepository @Inject constructor(
    private val dao: RawNotificationDao,
    private val blockedPackageDao: BlockedPackageDao,
    private val expenseDao: ExpenseDao,
    private val merchantCategoryDao: MerchantCategoryDao,
    private val pendingReviewDao: PendingReviewDao,
    private val userCorrectionDao: UserCorrectionDao,
    private val sourceStatsDao: SourceStatsDao,
    private val parserRegistry: AppParserRegistry,
    private val categorizationEngine: CategorizationEngine,
    private val confidenceRouter: ConfidenceRouter,
    private val merchantNormalizer: MerchantNormalizer,
    private val classifier: TransactionClassifier,
    private val budgetMonitor: BudgetMonitor // <-- NEW
) {
    private val repositoryScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    // Shared expenses flow to prevent redundant DB queries (shared by multiple ViewModels)
    private val sharedExpenses = expenseDao.getAllFlow()
        .shareIn(
            scope = repositoryScope,
            started = SharingStarted.WhileSubscribed(5000),
            replay = 1
        )
    // === Notification access ===
    fun getAllNotifications(): Flow<List<RawNotification>> = dao.getAllFlow()
    fun getRecentNotifications(limit: Int = 100): Flow<List<RawNotification>> =
        dao.getRecentFlow(limit)
    fun getNotificationsByPackage(packageName: String): Flow<List<RawNotification>> =
        dao.getByPackageFlow(packageName)
    fun getAllPackages(): Flow<List<String>> = dao.getAllPackagesFlow()
    fun getCount(): Flow<Int> = dao.getCountFlow()
    suspend fun save(notification: RawNotification): Long = dao.insert(notification)
    suspend fun exists(packageName: String, timestamp: Long, title: String?, text: String?): Boolean =
        dao.exists(packageName, timestamp, title, text)
    // === Review Queue ===
    fun getPendingReviews(): Flow<List<PendingReview>> = pendingReviewDao.getPendingFlow()
    fun getPendingReviewCount(): Flow<Int> = pendingReviewDao.getPendingCountFlow()
    // === Source Stats ===
    fun getSourceStats(): Flow<List<SourceStats>> = sourceStatsDao.getAllFlow()
    // === Classifier Stats ===
    fun getClassifierStats() = classifier.getStats()
    // === Manual Expense Entry ===
    /**
     * Search merchants from existing expenses for autocomplete
     */
    suspend fun searchMerchants(query: String): List<MerchantSuggestion> {
        if (query.isBlank()) return emptyList()
        return expenseDao.searchMerchants(query)
    }
    /**
     * Get recent distinct merchant names for suggestions
     */
    suspend fun getRecentMerchantNames(): List<String> {
        return expenseDao.getRecentMerchantNames()
    }
    /**
     * Add a manually entered expense
     */
    suspend fun addManualExpense(
        merchant: String,
        amount: Double,
        currency: String = "EUR",
        categoryId: Long?,
        transactionType: TransactionType = TransactionType.PURCHASE,
        paymentMethod: PaymentMethod = PaymentMethod.CASH,
        date: Long = System.currentTimeMillis(),
        notes: String? = null
    ): Long {
        // 1. Normalize merchant name
        val normalizedMerchant = merchantNormalizer.applyUserCorrections(merchant)
        // 2. Auto-categorize if no category provided
        val finalCategoryId = categoryId ?: categorizationEngine.categorize(normalizedMerchant)
        // 3. Dedup check with tighter window for manual entries (1 minute)
        val isDuplicate = expenseDao.isDuplicate(
            amount = amount,
            merchant = normalizedMerchant,
            date = date,
            windowMs = 60000
        )
        if (isDuplicate) return -1L
        // 4. Create expense
        val expense = Expense(
            amount = amount,
            currency = currency,
            merchant = normalizedMerchant,
            transactionType = transactionType,
            date = date,
            rawNotificationId = null,
            categoryId = finalCategoryId,
            paymentMethod = paymentMethod,
            isManualEntry = true,
            notes = notes
        )
        val id = expenseDao.insert(expense)
        // 5. Check budgets
        budgetMonitor.checkBudgets()
        // 6. Learn the merchant→category mapping for future auto-categorization
        if (finalCategoryId != null && id > 0) {
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
        return id
    }
    /**
     * Get category ID for a merchant (for auto-fill in manual entry)
     */
    suspend fun getCategoryForMerchant(merchant: String): Long? {
        return categorizationEngine.categorize(merchant)
    }
    // === Core Processing Pipeline ===
    @Transaction
    suspend fun processAndSave(notification: RawNotification) {
        // Optimized check: return early if already exists
        if (dao.exists(notification.packageName, notification.timestamp, notification.title, notification.text)) return
        // 1. Save raw notification
        val rawId = try {
            dao.insert(notification)
        } catch (e: android.database.sqlite.SQLiteConstraintException) {
            // Race condition: another thread inserted it after our exists() check
            return
        }
        // 2. Ensure source stats exist, then increment total
        confidenceRouter.ensureSourceStats(notification.packageName)
        sourceStatsDao.incrementTotal(notification.packageName)
        // 3. Initialize classifier if needed
        classifier.initialize()
        // 4. Try to parse
        val parsed = parserRegistry.parse(
            title = notification.title,
            text = notification.text,
            bigText = notification.bigText,
            subText = notification.subText,
            packageName = notification.packageName
        )
        if (parsed == null) {
            sourceStatsDao.incrementAutoRejected(notification.packageName)
            dao.markRelevance(rawId, false)
            return
        }
        // 5. Apply merchant normalization & user corrections
        val correctedMerchant = merchantNormalizer.applyUserCorrections(parsed.merchant)
        // 6. Build full notification text for ML classifier
        val fullNotificationText = listOfNotNull(
            notification.title,
            notification.text,
            notification.bigText
        ).joinToString(" ")
        // 7. Route through confidence system (now includes ML)
        val routingResult = confidenceRouter.route(
            parsed = parsed,
            packageName = notification.packageName,
            notificationText = fullNotificationText
        )
        when (routingResult.decision) {
            RoutingDecision.AUTO_ACCEPT -> {
                val isDuplicate = expenseDao.isDuplicate(
                    amount = parsed.amount,
                    merchant = correctedMerchant,
                    date = notification.timestamp
                )
                if (isDuplicate) {
                    dao.markRelevance(rawId, false)
                    return
                }
                val categoryId = categorizationEngine.categorize(correctedMerchant)
                val expense = Expense(
                    amount = parsed.amount,
                    currency = parsed.currency,
                    merchant = correctedMerchant,
                    transactionType = parsed.type,
                    date = notification.timestamp,
                    rawNotificationId = rawId,
                    categoryId = categoryId,
                    paymentMethod = PaymentMethod.CARD,
                    isManualEntry = false
                )
                try {
                    expenseDao.insert(expense)
                    dao.markRelevance(rawId, true)
                    sourceStatsDao.incrementAccepted(notification.packageName)
                    // Check budgets
                    budgetMonitor.checkBudgets()
                    // Train classifier: auto-accepted = positive example
                    classifier.train(fullNotificationText, isTransaction = true)
                } catch (e: android.database.sqlite.SQLiteConstraintException) {
                    // Ignore duplicate expenses
                    dao.markRelevance(rawId, false)
                }
            }
            RoutingDecision.NEEDS_REVIEW -> {
                val suggestedCategoryId = categorizationEngine.categorize(correctedMerchant)
                val review = PendingReview(
                    rawNotificationId = rawId,
                    suggestedAmount = parsed.amount,
                    suggestedCurrency = parsed.currency,
                    suggestedMerchant = correctedMerchant,
                    suggestedType = parsed.type.name,
                    suggestedCategoryId = suggestedCategoryId,
                    confidence = routingResult.adjustedConfidence,
                    packageName = notification.packageName,
                    notificationTitle = notification.title,
                    notificationText = notification.text ?: notification.bigText
                )
                pendingReviewDao.insert(review)
                sourceStatsDao.incrementPending(notification.packageName)
            }
            RoutingDecision.AUTO_REJECT -> {
                dao.markRelevance(rawId, false)
                sourceStatsDao.incrementAutoRejected(notification.packageName)
                // Train classifier: auto-rejected by low confidence = negative example
                classifier.train(fullNotificationText, isTransaction = false)
            }
        }
    }
    // === Review Actions ===
    /**
     * User approves a pending review (possibly with modifications)
     */
    /**
     * User approves a pending review (possibly with modifications)
     */
    @Transaction
    suspend fun approveReview(
        reviewId: Long,
        finalAmount: Double? = null,
        finalMerchant: String? = null,
        finalCategoryId: Long? = null
    ) {
        val review = pendingReviewDao.getById(reviewId) ?: return
        // Race condition check: ensure we are the first to handle this
        // We set status to APPROVED first to lock it. If insertion fails, we're in a bit of a bind,
        // but it's better than double-insertion stats.
        val rowsUpdated = pendingReviewDao.updateStatusIfPending(reviewId, "APPROVED")
        if (rowsUpdated == 0) return
        val amount = finalAmount ?: review.suggestedAmount
        val merchant = finalMerchant ?: review.suggestedMerchant
        val categoryId = finalCategoryId ?: review.suggestedCategoryId
        val type = try {
            TransactionType.valueOf(review.suggestedType)
        } catch (e: Exception) {
            TransactionType.PURCHASE
        }
        val notification = dao.getById(review.rawNotificationId)
        val transactionDate = notification?.timestamp ?: review.createdAt
        // Check for duplicates
        val isDuplicate = expenseDao.isDuplicate(
            amount = amount,
            merchant = merchant,
            date = transactionDate
        )
        if (!isDuplicate) {
            // Create the expense
            val expense = Expense(
                amount = amount,
                currency = review.suggestedCurrency,
                merchant = merchant,
                transactionType = type,
                date = transactionDate,
                rawNotificationId = review.rawNotificationId,
                categoryId = categoryId,
                paymentMethod = PaymentMethod.CARD,
                isManualEntry = false
            )
            try {
                expenseDao.insert(expense)
                // Only if insert succeeds:
                dao.markRelevance(review.rawNotificationId, true)
                sourceStatsDao.incrementAccepted(review.packageName)
                sourceStatsDao.decrementPending(review.packageName)
                // Check budgets
                budgetMonitor.checkBudgets()
            } catch (e: android.database.sqlite.SQLiteConstraintException) {
                // If expense insertion fails (e.g. key constraint even though we checked isDuplicate),
                // we technically approved it but failed to create expense.
                // Revert status? Or just log? 
                // Given we already updated status to APPROVED, we leave it.
            }
        }
        // Record user correction for learning
        val correction = UserCorrection(
            packageName = review.packageName,
            originalMerchant = review.suggestedMerchant,
            correctedMerchant = if (finalMerchant != null && finalMerchant != review.suggestedMerchant)
                finalMerchant else null,
            originalAmount = review.suggestedAmount,
            correctedAmount = if (finalAmount != null && finalAmount != review.suggestedAmount)
                finalAmount else null,
            originalCategoryId = review.suggestedCategoryId,
            correctedCategoryId = if (finalCategoryId != null && finalCategoryId != review.suggestedCategoryId)
                finalCategoryId else null,
            wasRejected = false,
            wasApproved = true,
            notificationTitle = review.notificationTitle,
            notificationText = review.notificationText
        )
        userCorrectionDao.insert(correction)
        // Train classifier: user approved = positive
        val trainingText = listOfNotNull(
            review.notificationTitle,
            review.notificationText
        ).joinToString(" ")
        if (trainingText.isNotBlank()) {
            classifier.train(trainingText, isTransaction = true)
        }
        // Learn merchant → category mapping if category was set
        if (categoryId != null) {
            val pattern = categorizationEngine.normalize(merchant)
            if (pattern.isNotEmpty()) {
                merchantCategoryDao.insert(
                    MerchantCategory(
                        merchantPattern = pattern,
                        categoryId = categoryId,
                        confidence = 1.0f
                    )
                )
            }
        }
    }
    /**
     * User rejects a pending review
     */
    @Transaction
    suspend fun rejectReview(reviewId: Long) {
        val review = pendingReviewDao.getById(reviewId) ?: return
        // Atomic update check
        val rowsUpdated = pendingReviewDao.updateStatusIfPending(reviewId, "REJECTED")
        if (rowsUpdated == 0) return
        dao.markRelevance(review.rawNotificationId, false)
        sourceStatsDao.incrementRejected(review.packageName)
        sourceStatsDao.decrementPending(review.packageName)
        // Record rejection for learning
        val correction = UserCorrection(
            packageName = review.packageName,
            originalMerchant = review.suggestedMerchant,
            correctedMerchant = null,
            originalAmount = review.suggestedAmount,
            correctedAmount = null,
            originalCategoryId = review.suggestedCategoryId,
            correctedCategoryId = null,
            wasRejected = true,
            wasApproved = false,
            notificationTitle = review.notificationTitle,
            notificationText = review.notificationText
        )
        userCorrectionDao.insert(correction)
        // Train classifier: user rejected = negative
        val trainingText = listOfNotNull(
            review.notificationTitle,
            review.notificationText
        ).joinToString(" ")
        if (trainingText.isNotBlank()) {
            classifier.train(trainingText, isTransaction = false)
        }
    }
    // === Classifier Management ===
    suspend fun retrainClassifier() {
        classifier.retrainFromCorrections()
    }
    // === Existing methods (updated) ===
    suspend fun markAsRelevant(id: Long, isRelevant: Boolean) =
        dao.markRelevance(id, isRelevant)
    suspend fun deleteAll() {
        dao.deleteAll()
        expenseDao.deleteAll()
        pendingReviewDao.deleteAll()
        userCorrectionDao.deleteAll()
        merchantCategoryDao.deleteAll()
        blockedPackageDao.deleteAll()
        sourceStatsDao.resetAllPendingCounts()
    }
    suspend fun deleteAllExpenses() = expenseDao.deleteAll()
    suspend fun deleteExpense(expense: Expense) = expenseDao.delete(expense)
    suspend fun updateExpenseCategory(expense: Expense, newCategoryId: Long) {
        expenseDao.updateCategory(expense.id, newCategoryId)
        val pattern = categorizationEngine.normalize(expense.merchant)
        if (pattern.isNotEmpty()) {
            merchantCategoryDao.insert(
                MerchantCategory(
                    merchantPattern = pattern,
                    categoryId = newCategoryId,
                    confidence = 1.0f
                )
            )
        }
        // Also record as a correction for learning
        val correction = UserCorrection(
            packageName = "manual_edit",
            originalMerchant = expense.merchant,
            correctedMerchant = null,
            originalAmount = expense.amount,
            correctedAmount = null,
            originalCategoryId = expense.categoryId,
            correctedCategoryId = newCategoryId,
            wasRejected = false,
            wasApproved = true,
            notificationTitle = null,
            notificationText = null
        )
        userCorrectionDao.insert(correction)
    }
    suspend fun delete(notification: RawNotification) {
        // Check if there's a pending review attached to this notification
        val pendingReview = pendingReviewDao.getByRawId(notification.id)
        if (pendingReview != null && pendingReview.status == "PENDING") {
            sourceStatsDao.decrementPending(notification.packageName)
        }
        dao.delete(notification)
    }
    suspend fun blockPackage(packageName: String) =
        blockedPackageDao.block(BlockedPackage(packageName))
    suspend fun unblockPackage(packageName: String) =
        blockedPackageDao.unblock(packageName)
    suspend fun isPackageBlocked(packageName: String): Boolean =
        blockedPackageDao.isBlocked(packageName)
    fun getBlockedPackages(): Flow<List<BlockedPackage>> =
        blockedPackageDao.getAllFlow()
    fun getTotalSpent(): Flow<Double?> = expenseDao.getTotalSpentFlow()
    fun getAllExpenses(): Flow<List<Expense>> = sharedExpenses
    suspend fun processAndSaveAll(notifications: List<RawNotification>) {
        if (notifications.isEmpty()) return
        // Initialize once for the batch
        classifier.initialize()
        // Process in parallel chunks
        notifications.chunked(20).forEach { chunk ->
            coroutineScope {
                chunk.map { notification -> 
                    async { processAndSave(notification) } 
                }.awaitAll()
            }
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\data\repository\ReceiptRepository.kt <a name="mainjavacomyournameexpensetrackerdatarepositoryreceiptrepositorykt"></a>
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

---

## main\java\com\yourname\expensetracker\domain\analytics\AnalyticsModels.kt <a name="mainjavacomyournameexpensetrackerdomainanalyticsanalyticsmodelskt"></a>
```kotlin
package com.yourname.expensetracker.domain.analytics
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
// === New Insights Models ===
data class MonthPeriod(
    val year: Int,
    val month: Int, // 0-indexed (Calendar.JANUARY = 0)
    val startMs: Long,
    val endMs: Long
) {
    val label: String
        get() {
            val monthNames = arrayOf(
                "Jan", "Feb", "Mar", "Apr", "May", "Jun",
                "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
            )
            return "${monthNames[month]} $year"
        }
    val shortLabel: String
        get() {
            val monthNames = arrayOf(
                "Jan", "Feb", "Mar", "Apr", "May", "Jun",
                "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
            )
            return monthNames[month]
        }
}
data class CategoryInsight(
    val category: Category,
    val currentTotal: Double,
    val currentCount: Int,
    val previousTotal: Double?,
    val previousCount: Int?,
    val averageOverMonths: Double?,
    val monthsOfData: Int,
    val percentageOfTotal: Float,
    val changeFromPrevious: Float?, // percentage change
    val changeFromAverage: Float? // percentage deviation from average
)
data class MerchantInsight(
    val merchant: String,
    val avgAmount: Double,
    val minAmount: Double,
    val maxAmount: Double,
    val totalSpent: Double,
    val transactionCount: Int,
    val isLikelyRecurring: Boolean,
    val stdDeviation: Double? // null if < 3 transactions
)
data class SpendingPace(
    val currentMonthSpent: Double,
    val daysElapsed: Int,
    val daysInMonth: Int,
    val projectedTotal: Double,
    val previousMonthTotal: Double?,
    val averageMonthlyTotal: Double?,
    val pacePercentage: Float, // how far through the month's typical spend
    val paceStatus: PaceStatus
)
enum class PaceStatus {
    UNDER_PACE,    // spending less than typical
    ON_PACE,       // within 10% of typical
    OVER_PACE,     // spending more than typical
    NO_BASELINE    // not enough data
}
data class AnomalyTransaction(
    val expense: Expense,
    val merchantAvg: Double,
    val deviationMultiple: Float, // how many times the average
    val category: Category?
)
data class RecurringExpense(
    val merchant: String,
    val avgAmount: Double,
    val frequency: Int, // transactions total
    val amountVariation: Double, // max - min
    val isStable: Boolean // low variation
)
data class DayOfWeekInsight(
    val dayName: String,
    val dayIndex: Int, // 0=Mon, 6=Sun
    val totalSpent: Double,
    val transactionCount: Int,
    val avgPerTransaction: Double
)
data class MonthlyComparison(
    val currentMonth: MonthPeriod,
    val previousMonth: MonthPeriod?,
    val currentTotal: Double,
    val previousTotal: Double?,
    val changeAmount: Double?,
    val changePercentage: Float?,
    val currentCount: Int,
    val previousCount: Int?
)
data class InsightsSnapshot(
    val generatedAt: Long = System.currentTimeMillis(),
    val currentMonth: MonthPeriod,
    val monthlyComparison: MonthlyComparison,
    val categoryInsights: List<CategoryInsight>,
    val topMerchants: List<MerchantInsight>,
    val spendingPace: SpendingPace,
    val anomalies: List<AnomalyTransaction>,
    val recurringExpenses: List<RecurringExpense>,
    val dayOfWeekPattern: List<DayOfWeekInsight>,
    val largestTransaction: Expense?,
    val averageTransactionSize: Double,
    val medianTransactionSize: Double,
    val totalMonthsOfData: Int
)
// === Legacy / Existing Models ===
data class SpendingPeriod(
    val label: String,
    val startDate: Long,
    val endDate: Long,
    val total: Double,
    val previousTotal: Double?,         // For comparison
    val byCategory: List<CategoryBreakdown>,
    val byMerchant: List<MerchantBreakdown>,
    val dailyTotals: Map<String, Double>,   // "2024-01-15" → 45.60
    val transactionCount: Int
) {
    val changePercent: Float?
        get() = if (previousTotal != null && previousTotal > 0)
            ((total - previousTotal) / previousTotal * 100).toFloat()
        else null
}
data class CategoryBreakdown(
    val category: Category,
    val total: Double,
    val count: Int,
    val percentage: Float           // 0-100
)
data class MerchantBreakdown(
    val name: String,
    val totalSpent: Double,
    val transactionCount: Int,
    val averageTransaction: Double,
    val categoryId: Long?
)
data class SpendingInsight(
    val type: InsightType,
    val icon: String,
    val title: String,
    val description: String,
    val severity: Float             // 0-1, how important/urgent
)
enum class InsightType {
    SPENDING_INCREASE,
    SPENDING_DECREASE,
    UNUSUAL_TRANSACTION,
    RECURRING_DETECTED,
    CATEGORY_TREND,
    BUDGET_WARNING,
    MERCHANT_FREQUENCY,
    DAILY_AVERAGE,
    TOP_MERCHANT,
    STREAK
}
data class RecurringCandidate(
    val merchant: String,
    val amount: Double,
    val intervalDays: Int,
    val occurrences: Int,
    val nextExpectedDate: Long?
)
enum class TimePeriod {
    TODAY, WEEK, MONTH, YEAR, ALL
}

```

---

## main\java\com\yourname\expensetracker\domain\analytics\InsightsEngine.kt <a name="mainjavacomyournameexpensetrackerdomainanalyticsinsightsenginekt"></a>
```kotlin
package com.yourname.expensetracker.domain.analytics
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt
// === Engine ===
@Singleton
class InsightsEngine @Inject constructor(
    private val expenseDao: ExpenseDao
) {
    companion object {
        private val DAY_NAMES = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    }
    suspend fun generateInsights(
        categories: List<Category>,
        allExpenses: List<Expense>
    ): InsightsSnapshot = coroutineScope {
        val now = System.currentTimeMillis()
        val currentMonth = getMonthPeriod(now)
        val previousMonth = getPreviousMonthPeriod(currentMonth)
        val categoryMap = categories.associateBy { it.id }
        // Start all independent queries in parallel
        val monthlyComparisonDeferred = async { buildMonthlyComparison(currentMonth, previousMonth) }
        val categoryInsightsDeferred = async { buildCategoryInsights(currentMonth, previousMonth, categoryMap, allExpenses) }
        val topMerchantsDeferred = async { buildMerchantInsights(allExpenses) }
        val spendingPaceDeferred = async { buildSpendingPace(currentMonth, previousMonth, allExpenses) }
        val anomaliesDeferred = async { findAnomalies(currentMonth, categoryMap) }
        val recurringExpensesDeferred = async { findRecurringExpenses() }
        val threeMonthsAgo = getMonthPeriod(now, -2)
        val dayOfWeekPatternDeferred = async { buildDayOfWeekPattern(threeMonthsAgo.startMs, currentMonth.endMs) }
        val largestTransactionDeferred = async { 
            expenseDao.getLargestExpenseForPeriod(currentMonth.startMs, currentMonth.endMs) 
        }
        // Await all results
        val monthlyComparison = monthlyComparisonDeferred.await()
        val categoryInsights = categoryInsightsDeferred.await()
        val topMerchants = topMerchantsDeferred.await()
        val spendingPace = spendingPaceDeferred.await()
        val anomalies = anomaliesDeferred.await()
        val recurringExpenses = recurringExpensesDeferred.await()
        val dayOfWeekPattern = dayOfWeekPatternDeferred.await()
        val largestTransaction = largestTransactionDeferred.await()
        // Transaction size stats
        val currentMonthPurchases = allExpenses.filter {
            it.transactionType == TransactionType.PURCHASE
                    && it.date >= currentMonth.startMs
                    && it.date < currentMonth.endMs
        }
        val avgTxSize = if (currentMonthPurchases.isNotEmpty())
            currentMonthPurchases.map { it.amount }.average() else 0.0
        val medianTxSize = calculateMedian(currentMonthPurchases.map { it.amount })
        // How many months of data we have
        val totalMonthsOfData = countDistinctMonths(allExpenses)
        InsightsSnapshot(
            currentMonth = currentMonth,
            monthlyComparison = monthlyComparison,
            categoryInsights = categoryInsights,
            topMerchants = topMerchants,
            spendingPace = spendingPace,
            anomalies = anomalies,
            recurringExpenses = recurringExpenses,
            dayOfWeekPattern = dayOfWeekPattern,
            largestTransaction = largestTransaction,
            averageTransactionSize = avgTxSize,
            medianTransactionSize = medianTxSize,
            totalMonthsOfData = totalMonthsOfData
        )
    }
    // === Legacy Compatibility ===
    fun getLegacyInsights(snapshot: InsightsSnapshot): List<SpendingInsight> {
        val insights = mutableListOf<SpendingInsight>()
        // 1. Monthly Comparison (Spending Increase/Decrease)
        val comparison = snapshot.monthlyComparison
        if (comparison.changePercentage != null) {
            if (comparison.changePercentage > 20) {
                insights.add(
                    SpendingInsight(
                        InsightType.SPENDING_INCREASE, "📈",
                        "Spending up ${comparison.changePercentage.toInt()}%",
                        "€${fmt(comparison.currentTotal)} this month vs €${fmt(comparison.previousTotal ?: 0.0)} last month",
                        (comparison.changePercentage / 100).coerceAtMost(1.0f).toFloat()
                    )
                )
            } else if (comparison.changePercentage < -15) {
                insights.add(
                    SpendingInsight(
                        InsightType.SPENDING_DECREASE, "📉",
                        "Good job! Spending down ${(-comparison.changePercentage).toInt()}%",
                        "You saved €${fmt((comparison.previousTotal ?: 0.0) - comparison.currentTotal)} compared to last month",
                        0.3f
                    )
                )
            }
        }
        // 2. Spending Pace
        val pace = snapshot.spendingPace
        if (pace.paceStatus == PaceStatus.OVER_PACE) {
             insights.add(
                SpendingInsight(
                    InsightType.BUDGET_WARNING, "⚠️",
                    "Spending Pace Warning",
                    "You're at ${pace.daysElapsed} days but spent ${pace.pacePercentage.toInt()}% of typical month",
                    0.8f
                )
            )
        }
        // 3. Category Trends
        snapshot.categoryInsights.take(3).forEach { catInsight ->
            if (catInsight.changeFromPrevious != null) {
                 if (catInsight.changeFromPrevious > 40 && catInsight.currentTotal > 50) {
                    insights.add(
                        SpendingInsight(
                            InsightType.CATEGORY_TREND, catInsight.category.icon,
                            "${catInsight.category.name} up ${catInsight.changeFromPrevious.toInt()}%",
                            "€${fmt(catInsight.currentTotal)} vs €${fmt(catInsight.previousTotal ?: 0.0)}",
                            0.7f
                        )
                    )
                 }
            }
        }
        // 4. Recurring
        snapshot.recurringExpenses.take(3).forEach { recurring ->
             insights.add(
                SpendingInsight(
                    InsightType.RECURRING_DETECTED, "🔄",
                    "Recurring: ${recurring.merchant}",
                    "€${fmt(recurring.avgAmount)} ~every ${30.0/recurring.frequency} days", // approx
                    0.5f
                )
            )
        }
        // 5. Largest Transaction
        val largest = snapshot.largestTransaction
        if (largest != null && largest.amount > 50) {
             insights.add(
                SpendingInsight(
                    InsightType.UNUSUAL_TRANSACTION, "⚡",
                    "Largest: ${largest.merchant}",
                    "€${fmt(largest.amount)} on ${formatDate(largest.date)}",
                    0.25f
                )
            )
        }
        return insights.sortedByDescending { it.severity }
    }
    // === Month Period Helpers ===
    fun getMonthPeriod(timeMs: Long, monthOffset: Int = 0): MonthPeriod {
        val cal = Calendar.getInstance()
        cal.timeInMillis = timeMs
        cal.add(Calendar.MONTH, monthOffset)
        cal.set(Calendar.DAY_OF_MONTH, 1)
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val startMs = cal.timeInMillis
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH)
        cal.add(Calendar.MONTH, 1)
        val endMs = cal.timeInMillis
        return MonthPeriod(year, month, startMs, endMs)
    }
    private fun getPreviousMonthPeriod(current: MonthPeriod): MonthPeriod {
        val cal = Calendar.getInstance()
        cal.timeInMillis = current.startMs
        cal.add(Calendar.MONTH, -1)
        return getMonthPeriod(cal.timeInMillis)
    }
    // === Monthly Comparison ===
    private suspend fun buildMonthlyComparison(
        current: MonthPeriod,
        previous: MonthPeriod
    ): MonthlyComparison = coroutineScope {
        val currentTotalDeferred = async { expenseDao.getTotalForPeriod(current.startMs, current.endMs) }
        val currentCountDeferred = async { expenseDao.getCountForPeriod(current.startMs, current.endMs) }
        val previousTotalDeferred = async { expenseDao.getTotalForPeriod(previous.startMs, previous.endMs) }
        val previousCountDeferred = async { expenseDao.getCountForPeriod(previous.startMs, previous.endMs) }
        val currentTotal = currentTotalDeferred.await()
        val currentCount = currentCountDeferred.await()
        val previousTotal = previousTotalDeferred.await()
        val previousCount = previousCountDeferred.await()
        val hasPrevious = previousCount > 0
        val changeAmount = if (hasPrevious) currentTotal - previousTotal else null
        val changePercentage = if (hasPrevious && previousTotal > 0)
            ((currentTotal - previousTotal) / previousTotal * 100).toFloat() else null
        MonthlyComparison(
            currentMonth = current,
            previousMonth = if (hasPrevious) previous else null,
            currentTotal = currentTotal,
            previousTotal = if (hasPrevious) previousTotal else null,
            changeAmount = changeAmount,
            changePercentage = changePercentage,
            currentCount = currentCount,
            previousCount = if (hasPrevious) previousCount else null
        )
    }
    // === Category Insights ===
    private suspend fun buildCategoryInsights(
        current: MonthPeriod,
        previous: MonthPeriod,
        categoryMap: Map<Long, Category>,
        allExpenses: List<Expense>
    ): List<CategoryInsight> = coroutineScope {
        val currentTotalsDeferred = async { expenseDao.getCategoryTotalsForPeriod(current.startMs, current.endMs) }
        val previousTotalsDeferred = async { expenseDao.getCategoryTotalsForPeriod(previous.startMs, previous.endMs) }
        val currentTotals = currentTotalsDeferred.await()
        val previousTotals = previousTotalsDeferred.await()
        val previousMap = previousTotals.associateBy { it.categoryId }
        val currentGrandTotal = currentTotals.sumOf { it.total }
        // Calculate multi-month averages per category
        val monthlyAverages = calculateCategoryMonthlyAverages(allExpenses, current)
        currentTotals.mapNotNull { ct ->
            val category = categoryMap[ct.categoryId] ?: return@mapNotNull null
            val prev = previousMap[ct.categoryId]
            val avgData = monthlyAverages[ct.categoryId]
            val changeFromPrev = if (prev != null && prev.total > 0)
                ((ct.total - prev.total) / prev.total * 100).toFloat() else null
            val changeFromAvg = if (avgData != null && avgData.first > 0)
                ((ct.total - avgData.first) / avgData.first * 100).toFloat() else null
            CategoryInsight(
                category = category,
                currentTotal = ct.total,
                currentCount = ct.txCount,
                previousTotal = prev?.total,
                previousCount = prev?.txCount,
                averageOverMonths = avgData?.first,
                monthsOfData = avgData?.second ?: 0,
                percentageOfTotal = if (currentGrandTotal > 0)
                    (ct.total / currentGrandTotal * 100).toFloat() else 0f,
                changeFromPrevious = changeFromPrev,
                changeFromAverage = changeFromAvg
            )
        }.sortedByDescending { it.currentTotal }
    }
    private fun calculateCategoryMonthlyAverages(
        allExpenses: List<Expense>,
        currentMonth: MonthPeriod
    ): Map<Long, Pair<Double, Int>> {
        // Group purchases by category, then by month, compute average
        val purchases = allExpenses.filter {
            it.transactionType == TransactionType.PURCHASE
                    && it.categoryId != null
                    && it.date < currentMonth.startMs // exclude current month
        }
        // Group by categoryId -> month key -> sum
        val categoryMonthTotals = mutableMapOf<Long, MutableMap<String, Double>>()
        val cal = Calendar.getInstance()
        for (expense in purchases) {
            val catId = expense.categoryId ?: continue
            cal.timeInMillis = expense.date
            val monthKey = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
            categoryMonthTotals
                .getOrPut(catId) { mutableMapOf() }
                .merge(monthKey, expense.amount) { a, b -> a + b }
        }
        // For each category, compute average monthly spend
        return categoryMonthTotals.mapValues { (_, monthMap) ->
            val months = monthMap.size
            val avg = if (months > 0) monthMap.values.sum() / months else 0.0
            Pair(avg, months)
        }
    }
    // === Merchant Insights ===
    private suspend fun buildMerchantInsights(
        allExpenses: List<Expense>
    ): List<MerchantInsight> {
        val stats = expenseDao.getAllMerchantStats()
        // For std deviation, compute from raw data grouped by merchant
        val purchasesByMerchant = allExpenses
            .filter { it.transactionType == TransactionType.PURCHASE }
            .groupBy { it.merchant }
        return stats.map { ms ->
            val amounts = purchasesByMerchant[ms.merchant]?.map { it.amount } ?: emptyList()
            val stdDev = if (amounts.size >= 3) calculateStdDev(amounts) else null
            val isRecurring = ms.txCount >= 2 &&
                    (ms.maxAmount - ms.minAmount) < (ms.avgAmount * 0.15)
            MerchantInsight(
                merchant = ms.merchant,
                avgAmount = ms.avgAmount,
                minAmount = ms.minAmount,
                maxAmount = ms.maxAmount,
                totalSpent = ms.totalAmount,
                transactionCount = ms.txCount,
                isLikelyRecurring = isRecurring,
                stdDeviation = stdDev
            )
        }.sortedByDescending { it.totalSpent }
    }
    // === Spending Pace ===
    private suspend fun buildSpendingPace(
        currentMonth: MonthPeriod,
        previousMonth: MonthPeriod,
        allExpenses: List<Expense>
    ): SpendingPace {
        val now = System.currentTimeMillis()
        val currentSpent = expenseDao.getTotalForPeriod(currentMonth.startMs, currentMonth.endMs)
        val previousTotal = expenseDao.getTotalForPeriod(previousMonth.startMs, previousMonth.endMs)
        val previousCount = expenseDao.getCountForPeriod(previousMonth.startMs, previousMonth.endMs)
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        val dayOfMonth = cal.get(Calendar.DAY_OF_MONTH)
        val daysInMonth = cal.getActualMaximum(Calendar.DAY_OF_MONTH)
        // Calculate average monthly total (excluding current month)
        val avgMonthly = calculateAverageMonthlySpend(allExpenses, currentMonth)
        // Project: if we've spent X in D days, we'll spend X * (totalDays/D) by month end
        val projectedTotal = if (dayOfMonth > 0)
            currentSpent * daysInMonth.toDouble() / dayOfMonth else currentSpent
        // Pace percentage: how much of the baseline have we consumed
        val baseline = avgMonthly ?: if (previousCount > 0) previousTotal else null
        val pacePercentage = if (baseline != null && baseline > 0) {
            val expectedAtThisPoint = baseline * dayOfMonth / daysInMonth
            (currentSpent / expectedAtThisPoint * 100).toFloat()
        } else 0f
        val paceStatus = when {
            baseline == null || baseline == 0.0 -> PaceStatus.NO_BASELINE
            pacePercentage < 90f -> PaceStatus.UNDER_PACE
            pacePercentage > 110f -> PaceStatus.OVER_PACE
            else -> PaceStatus.ON_PACE
        }
        return SpendingPace(
            currentMonthSpent = currentSpent,
            daysElapsed = dayOfMonth,
            daysInMonth = daysInMonth,
            projectedTotal = projectedTotal,
            previousMonthTotal = if (previousCount > 0) previousTotal else null,
            averageMonthlyTotal = avgMonthly,
            pacePercentage = pacePercentage,
            paceStatus = paceStatus
        )
    }
    private fun calculateAverageMonthlySpend(
        allExpenses: List<Expense>,
        currentMonth: MonthPeriod
    ): Double? {
        val purchases = allExpenses.filter {
            it.transactionType == TransactionType.PURCHASE
                    && it.date < currentMonth.startMs
        }
        if (purchases.isEmpty()) return null
        val cal = Calendar.getInstance()
        val monthTotals = mutableMapOf<String, Double>()
        for (p in purchases) {
            cal.timeInMillis = p.date
            val key = "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
            monthTotals.merge(key, p.amount) { a, b -> a + b }
        }
        return if (monthTotals.isNotEmpty()) monthTotals.values.average() else null
    }
    // === Anomaly Detection ===
    private suspend fun findAnomalies(
        currentMonth: MonthPeriod,
        categoryMap: Map<Long, Category>
    ): List<AnomalyTransaction> {
        val merchantStats = expenseDao.getMerchantStats() // only merchants with 2+ tx
        val statsMap = merchantStats.associateBy { it.merchant }
        val anomalies = mutableListOf<AnomalyTransaction>()
        // Check top merchants this month for outliers
        val topMerchants = expenseDao.getTopMerchantsForPeriod(
            currentMonth.startMs, currentMonth.endMs, 100
        )
        for (merchantStat in topMerchants) {
            val historicalStats = statsMap[merchantStat.merchant] ?: continue
            // We need 3+ transactions historically to have a reliable average
            if (historicalStats.txCount < 3) continue
            // If the max amount this month is > 2x the historical average
            if (merchantStat.maxAmount > historicalStats.avgAmount * 2.0) {
                // Find the actual expense (largest for this merchant this month)
                val expense = expenseDao.getLargestExpenseForPeriod(
                    currentMonth.startMs, currentMonth.endMs
                )
                // Filter specifically for this merchant
                if (expense != null && expense.merchant == merchantStat.merchant) {
                     anomalies.add(
                        AnomalyTransaction(
                            expense = expense,
                            merchantAvg = historicalStats.avgAmount,
                            deviationMultiple = (expense.amount / historicalStats.avgAmount).toFloat(),
                            category = expense.categoryId?.let { categoryMap[it] }
                        )
                    )
                }
            }
        }
        return anomalies.sortedByDescending { it.deviationMultiple }.take(5)
    }
    // === Recurring Expenses ===
    private suspend fun findRecurringExpenses(): List<RecurringExpense> {
        val candidates = expenseDao.getRecurringCandidates()
        return candidates.map { ms ->
            RecurringExpense(
                merchant = ms.merchant,
                avgAmount = ms.avgAmount,
                frequency = ms.txCount,
                amountVariation = ms.maxAmount - ms.minAmount,
                isStable = (ms.maxAmount - ms.minAmount) < (ms.avgAmount * 0.05)
            )
        }
    }
    // === Day of Week Pattern ===
    private suspend fun buildDayOfWeekPattern(
        startMs: Long,
        endMs: Long
    ): List<DayOfWeekInsight> {
        val data = expenseDao.getDayOfWeekPattern(startMs, endMs)
        // Fill in missing days with zeros
        val dayMap = data.associateBy { it.dayOfWeek }
        return (0..6).map { dayIndex ->
            val d = dayMap[dayIndex]
            DayOfWeekInsight(
                dayName = DAY_NAMES[dayIndex],
                dayIndex = dayIndex,
                totalSpent = d?.total ?: 0.0,
                transactionCount = d?.txCount ?: 0,
                avgPerTransaction = d?.avgAmount ?: 0.0
            )
        }
    }
    // === Utility Functions ===
    fun buildDailyTotals(expenses: List<Expense>, days: Int): Map<String, Double> {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val result = LinkedHashMap<String, Double>()
        val dateKeyFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        // Initialize all days with 0
        for (i in days - 1 downTo 0) {
            cal.timeInMillis = now
            cal.add(Calendar.DAY_OF_YEAR, -i)
            val key = dateKeyFormat.format(cal.time)
            result[key] = 0.0
        }
        // Fill in actual values
        val purchases = expenses.filter { it.transactionType == TransactionType.PURCHASE }
        for (expense in purchases) {
            val key = dateKeyFormat.format(java.util.Date(expense.date))
            if (result.containsKey(key)) {
                result[key] = (result[key] ?: 0.0) + expense.amount
            }
        }
        return result
    }
    // Make detectRecurring available for ViewModel compatibility if needed
    // But it's better to use the snapshot.
    // We already have findRecurringExpenses internally.
    // Legacy helper for detections from list
    fun detectRecurring(expenses: List<Expense>): List<com.yourname.expensetracker.domain.analytics.RecurringCandidate> {
         val dayMs = 86_400_000L
         val results = mutableListOf<com.yourname.expensetracker.domain.analytics.RecurringCandidate>()
         val byMerchant = expenses
            .filter { it.transactionType == TransactionType.PURCHASE }
            .groupBy { it.merchant.uppercase() }
         for ((merchant, exps) in byMerchant) {
             if (exps.size < 2) continue
             val sorted = exps.sortedBy { it.date }
             // Check if amounts are similar (within 15%)
             val amounts = sorted.map { it.amount }
             val avgAmount = amounts.average()
             val allSimilar = amounts.all { it > 0 && Math.abs(it - avgAmount) / avgAmount < 0.15 }
             if (allSimilar && sorted.size >= 2) {
                 val intervals = sorted.zipWithNext().map { (a, b) ->
                     ((b.date - a.date) / dayMs).toInt()
                 }
                 val avgInterval = intervals.average().toInt()
                 val isRecurring = avgInterval in 5..9 ||
                         avgInterval in 12..16 ||
                         avgInterval in 25..35 ||
                         avgInterval in 350..380
                 if (isRecurring) {
                     val lastDate = sorted.last().date
                     val nextExpected = lastDate + avgInterval * dayMs
                     results.add(
                         com.yourname.expensetracker.domain.analytics.RecurringCandidate(
                             merchant = exps.first().merchant,
                             amount = avgAmount,
                             intervalDays = avgInterval,
                             occurrences = sorted.size,
                             nextExpectedDate = nextExpected
                         )
                     )
                 }
             }
         }
         return results.sortedByDescending { it.occurrences }
    }
    private fun calculateMedian(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }
    private fun calculateStdDev(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val mean = values.average()
        val variance = values.map { (it - mean) * (it - mean) }.average()
        return sqrt(variance)
    }
    private fun countDistinctMonths(expenses: List<Expense>): Int {
        if (expenses.isEmpty()) return 0
        val cal = Calendar.getInstance()
        return expenses.map { expense ->
            cal.timeInMillis = expense.date
            "${cal.get(Calendar.YEAR)}-${cal.get(Calendar.MONTH)}"
        }.distinct().size
    }
    private fun fmt(amount: Double): String = String.format("%.2f", amount)
    private fun formatDate(dateMs: Long): String {
         val format = java.text.SimpleDateFormat("MMM dd", java.util.Locale.getDefault())
         return format.format(java.util.Date(dateMs))
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\budget\BudgetModels.kt <a name="mainjavacomyournameexpensetrackerdomainbudgetbudgetmodelskt"></a>
```kotlin
package com.yourname.expensetracker.domain.budget
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.Category
data class BudgetStatus(
    val budget: Budget,
    val category: Category?,
    val spentAmount: Double,
    val remainingAmount: Double,
    val percentUsed: Float,
    val healthStatus: BudgetHealthStatus,
    val periodStart: Long,
    val periodEnd: Long
)
enum class BudgetHealthStatus {
    ON_TRACK,   // Spent < warning threshold
    WARNING,    // Spent >= warning threshold
    CRITICAL,   // Spent >= critical threshold
    EXCEEDED    // Spent >= 100%
}
data class BudgetSuggestion(
    val categoryId: Long?,
    val categoryName: String,
    val categoryIcon: String,
    val suggestedAmount: Double,
    val basedOnMonths: Int,
    val reason: String
)
enum class BudgetAlertLevel {
    WARNING,
    CRITICAL,
    EXCEEDED
}

```

---

## main\java\com\yourname\expensetracker\domain\budget\BudgetMonitor.kt <a name="mainjavacomyournameexpensetrackerdomainbudgetbudgetmonitorkt"></a>
```kotlin
package com.yourname.expensetracker.domain.budget
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.yourname.expensetracker.data.database.dao.BudgetDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Calendar
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class BudgetMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val budgetDao: BudgetDao,
    private val expenseDao: ExpenseDao,
    private val categoryDao: CategoryDao
) {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    fun checkBudgets() {
        serviceScope.launch {
            val activeBudgets = budgetDao.getActiveBudgets()
            for (budget in activeBudgets) {
                processBudget(budget)
            }
        }
    }
    private suspend fun processBudget(budget: Budget) {
        val window = calculatePeriodWindow(budget.period, budget.startDate)
        val spent = if (budget.categoryId != null) {
            expenseDao.getCategorySpentInPeriod(budget.categoryId, window.first, window.second)
        } else {
            expenseDao.getTotalForPeriod(window.first, window.second)
        }
        if (spent <= 0) return
        val percent = (spent / budget.amount).toFloat()
        val now = System.currentTimeMillis()
        when {
            percent >= 1.0f -> {
                if (shouldNotify(budget.lastExceededNotifiedAt, now)) {
                    sendNotification(budget, spent, "Budget Exceeded!")
                    budgetDao.updateExceededNotification(budget.id, now)
                }
            }
            percent >= budget.notifyAtCritical -> {
                if (shouldNotify(budget.lastCriticalNotifiedAt, now)) {
                    sendNotification(budget, spent, "Critical Budget Warning")
                    budgetDao.updateCriticalNotification(budget.id, now)
                }
            }
            percent >= budget.notifyAtWarning -> {
                if (shouldNotify(budget.lastWarningNotifiedAt, now)) {
                    sendNotification(budget, spent, "Budget Warning")
                    budgetDao.updateWarningNotification(budget.id, now)
                }
            }
        }
    }
    private fun shouldNotify(lastNotified: Long?, now: Long): Boolean {
        if (lastNotified == null) return true
        // Cooldown: only notify once every 12 hours for the same budget level
        val cooldown = 12 * 60 * 60 * 1000L
        return now - lastNotified > cooldown
    }
    private fun sendNotification(budget: Budget, spent: Double, title: String) {
        val percent = (spent / budget.amount * 100).toInt()
        serviceScope.launch {
            val categoryName = if (budget.categoryId != null) {
                categoryDao.getById(budget.categoryId)?.name ?: "Category"
            } else {
                "Overall"
            }
            val content = "You've spent €${"%.2f".format(spent)} ($percent%) of your $categoryName budget."
            val builder = NotificationCompat.Builder(context, "budget_alerts")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
            notificationManager.notify(budget.id.toInt(), builder.build())
        }
    }
    fun calculatePeriodWindow(period: BudgetPeriod, anchorDate: Long): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        val now = System.currentTimeMillis()
        cal.timeInMillis = now
        // Set to start of current day
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return when (period) {
            BudgetPeriod.DAILY -> {
                val start = cal.timeInMillis
                cal.add(Calendar.DAY_OF_YEAR, 1)
                Pair(start, cal.timeInMillis)
            }
            BudgetPeriod.WEEKLY -> {
                // Set to current week's Monday
                cal.firstDayOfWeek = Calendar.MONDAY
                cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                val start = cal.timeInMillis
                cal.add(Calendar.WEEK_OF_YEAR, 1)
                Pair(start, cal.timeInMillis)
            }
            BudgetPeriod.MONTHLY -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                val start = cal.timeInMillis
                cal.add(Calendar.MONTH, 1)
                Pair(start, cal.timeInMillis)
            }
            BudgetPeriod.YEARLY -> {
                cal.set(Calendar.DAY_OF_YEAR, 1)
                val start = cal.timeInMillis
                cal.add(Calendar.YEAR, 1)
                Pair(start, cal.timeInMillis)
            }
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\categorization\CategorizationEngine.kt <a name="mainjavacomyournameexpensetrackerdomaincategorizationcategorizationenginekt"></a>
```kotlin
package com.yourname.expensetracker.domain.categorization
import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.entity.MerchantCategory
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
@Singleton
class CategorizationEngine @Inject constructor(
    private val merchantCategoryDao: MerchantCategoryDao
) {
    private val cacheMutex = Mutex()
    private var cachedMappings: List<MerchantCategory>? = null
    private var lastCacheTime = 0L
    private val CACHE_EXPIRY_MS = 300_000 // 5 minutes
    private val cleanupRegex1 by lazy { Regex("[^A-ZΑ-Ω0-9 &]") }
    private val cleanupRegex2 by lazy { Regex("\\s+") }
    suspend fun categorize(merchant: String): Long? {
        val normalized = normalize(merchant)
        // 1. Exact match
        val exactMatch = merchantCategoryDao.getCategoryForMerchant(normalized)
        if (exactMatch != null) return exactMatch.categoryId
        // 2. Substring match — check if any known merchant pattern is contained in this merchant
        val allMappings = getMappings()
        // Sort by pattern length descending to match longest first
        val sortedMappings = allMappings.sortedByDescending { it.merchantPattern.length }
        val paddedNormalized = " $normalized "
        for (mapping in sortedMappings) {
            if (mapping.merchantPattern.length >= 3) {
                // Check if the pattern exists as a whole word(s) in the merchant name
                // e.g. "UBER" matches "UBER EATS" but "ONE" does not match "PHONE"
                val paddedPattern = " ${mapping.merchantPattern} "
                if (paddedNormalized.contains(paddedPattern)) {
                    return mapping.categoryId
                }
            }
        }
        // 3. Word-level match — split merchant into words and check each
        val words = normalized.split(" ").filter { it.length >= 4 }
        if (words.isNotEmpty()) {
            val mappingsMap = allMappings.associateBy { it.merchantPattern }
            for (word in words) {
                val match = mappingsMap[word]
                if (match != null) return match.categoryId
            }
        }
        return null
    }
    fun normalize(merchant: String): String {
        return merchant.uppercase()
            .replace(cleanupRegex1, "")
            .trim()
            .replace(cleanupRegex2, " ")
    }
    private suspend fun getMappings(): List<MerchantCategory> {
        cacheMutex.withLock {
            val now = System.currentTimeMillis()
            if (cachedMappings == null || now - lastCacheTime > CACHE_EXPIRY_MS) {
                cachedMappings = merchantCategoryDao.getAll()
                lastCacheTime = now
            }
            return cachedMappings!!
        }
    }
    suspend fun invalidateCache() {
        cacheMutex.withLock {
            cachedMappings = null
            lastCacheTime = 0
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\debug\NotificationSeeder.kt <a name="mainjavacomyournameexpensetrackerdomaindebugnotificationseederkt"></a>
```kotlin
package com.yourname.expensetracker.domain.debug
import com.yourname.expensetracker.data.database.entity.RawNotification
import javax.inject.Inject
import kotlin.random.Random
class NotificationSeeder @Inject constructor() {
    private val categories = mapOf(
        "Groceries" to listOf("AB Vassilopoulos", "Sklavenitis", "Lidl", "Masoutis", "My Market"),
        "Transport" to listOf("Uber", "Beat", "OASA", "Shell", "EKO", "Aegean Airlines"),
        "Bills" to listOf("DEI", "EYDAP", "Vodafone", "Cosmote", "Wind"),
        "Entertainment" to listOf("Netflix", "Spotify", "Village Cinemas", "Steam", "PlayStation"),
        "Shopping" to listOf("Amazon", "Skroutz", "Zara", "H&M", "Public", "Plaisio"),
        "Dining" to listOf("Goody's", "Wolt", "E-Food", "Starbucks", "Gregorys")
    )
    private val spamTemplates = listOf(
        "You won 1000 euros! Claim now at link.com",
        "Your OTP code is 123456. Do not share it.",
        "Limited time offer! 50% off on all items.",
        "Missed call from +306912345678",
        "Your package is out for delivery.",
        "Verify your account by clicking here."
    )
    private val unknownSources = listOf(
        "Unknown Sender", "+306900000000", "InfoSMS", "Alert", "Notice"
    )
    fun generate(count: Int): List<RawNotification> {
        val notifications = mutableListOf<RawNotification>()
        val now = System.currentTimeMillis()
        val twoMonthsMs = 60L * 24 * 60 * 60 * 1000
        for (i in 0 until count) {
            val type = Random.nextInt(100)
            val notification = when {
                type < 5 -> generateSpam(now, twoMonthsMs) // 5% Spam
                type < 10 -> generateUnknown(now, twoMonthsMs) // 5% Unknown
                type < 15 -> generateRecurring(i, now) // 5% Recurring candidates
                else -> generateTransaction(now, twoMonthsMs) // 85% Normal Transactions
            }
            notifications.add(notification)
        }
        return notifications
    }
    private fun generateTransaction(now: Long, rangeMs: Long): RawNotification {
        val categoryEntry = categories.entries.random()
        val merchant = categoryEntry.value.random()
        val amount = Random.nextDouble(5.0, 150.0)
        val date = now - Random.nextLong(rangeMs)
        // Randomize source slightly to test normalization
        val sources = listOf("Revolut", "Piraeus", "Eurobank", "Alpha Bank")
        val source = sources.random()
        val text = when (source) {
            "Revolut" -> "Spent €${"%.2f".format(amount)} at $merchant."
            "Piraeus" -> "Agora €${"%.2f".format(amount)} me karta ... sto $merchant"
            else -> "Purchase of €${"%.2f".format(amount)} at $merchant completed."
        }
        return RawNotification(
            packageName = "com.simulation.$source".lowercase(),
            appName = source,
            title = "Transaction Alert",
            text = text,
            timestamp = date,
            capturedAt = System.currentTimeMillis()
        )
    }
    private fun generateRecurring(index: Int, now: Long): RawNotification {
        // Force some recurring patterns (e.g. Netflix every month)
        // We'll generate a "Netflix" charge at a specific day of month relative to 'index' simply to seed *some* recurring data
        // But for mass simulation in one go, we can just sprinkle them randomly in time but with fixed amount
        val merchant = "Netflix"
        val amount = 13.99
        val date = now - Random.nextLong(60L * 24 * 60 * 60 * 1000)
        return RawNotification(
            packageName = "com.simulation.revolut",
            appName = "Revolut",
            title = "Recurring Payment",
            text = "Spent €$amount at $merchant.",
            timestamp = date,
            capturedAt = System.currentTimeMillis()
        )
    }
    private fun generateSpam(now: Long, rangeMs: Long): RawNotification {
        val text = spamTemplates.random()
        val date = now - Random.nextLong(rangeMs)
        return RawNotification(
            packageName = "com.android.mms",
            appName = "Messages",
            title = unknownSources.random(),
            text = text,
            timestamp = date,
            capturedAt = System.currentTimeMillis()
        )
    }
    private fun generateUnknown(now: Long, rangeMs: Long): RawNotification {
        val amount = Random.nextDouble(10.0, 50.0)
        val date = now - Random.nextLong(rangeMs)
        val text = "Payment of €${"%.2f".format(amount)} to Unknown Merchant."
        return RawNotification(
            packageName = "com.unknown.app",
            appName = "Unknown App",
            title = "Payment Notification",
            text = text,
            timestamp = date,
            capturedAt = System.currentTimeMillis()
        )
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\intelligence\ConfidenceRouter.kt <a name="mainjavacomyournameexpensetrackerdomainintelligenceconfidencerouterkt"></a>
```kotlin
package com.yourname.expensetracker.domain.intelligence
import com.yourname.expensetracker.data.database.dao.SourceStatsDao
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import com.yourname.expensetracker.data.database.entity.SourceStats
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton
enum class RoutingDecision {
    AUTO_ACCEPT,    // High confidence → create expense immediately
    NEEDS_REVIEW,   // Medium confidence → add to review queue
    AUTO_REJECT     // Low confidence → silently drop
}
data class RoutingResult(
    val decision: RoutingDecision,
    val adjustedConfidence: Float,
    val reason: String
)
@Singleton
class ConfidenceRouter @Inject constructor(
    private val sourceStatsDao: SourceStatsDao,
    private val userCorrectionDao: UserCorrectionDao,
    private val classifier: TransactionClassifier
) {
    companion object {
        const val AUTO_ACCEPT_THRESHOLD = 0.85f
        const val REVIEW_THRESHOLD = 0.50f
    }
    suspend fun route(
        parsed: ParsedTransaction,
        packageName: String,
        notificationText: String? = null
    ): RoutingResult {
        var adjustedConfidence = parsed.confidence
        val reasons = mutableListOf<String>()
        // 1. ML classifier prediction (if ready)
        if (notificationText != null) {
            val mlPrediction = classifier.predict(notificationText)
            val classifierStats = classifier.getStats()
            if (classifierStats.isReady) {
                // Blend parser confidence with ML prediction
                // Weight: 60% parser, 40% ML (ML gets more weight as it trains more)
                val mlWeight = calculateMlWeight(classifierStats)
                val parserWeight = 1.0f - mlWeight
                adjustedConfidence = parsed.confidence * parserWeight + mlPrediction * mlWeight
                if (mlPrediction < 0.3f) {
                    reasons.add("ML: ${(mlPrediction * 100).toInt()}% likely transaction")
                } else if (mlPrediction > 0.8f) {
                    reasons.add("ML: ${(mlPrediction * 100).toInt()}% confident")
                }
            }
        }
        // 2-5. Adjust based on source trust, merchant history, package history, and previous approvals
        coroutineScope {
            val sourceStatsDeferred = async { sourceStatsDao.getByPackage(packageName) }
            val merchantRejectionRateDeferred = async { getMerchantRejectionRate(parsed.merchant) }
            val packageRejectionRateDeferred = async { getPackageRejectionRate(packageName) }
            val previouslyApprovedDeferred = async { hasPreviousApprovals(parsed.merchant, packageName) }
            // 2. Adjust based on source trust score
            val sourceStats = sourceStatsDeferred.await()
            if (sourceStats != null && sourceStats.totalNotifications > 10) {
                val trustModifier = calculateTrustModifier(sourceStats)
                adjustedConfidence *= trustModifier
                if (trustModifier < 0.9f) {
                    reasons.add("Source trust: ${(sourceStats.trustScore * 100).toInt()}%")
                }
            }
            // 3. Adjust based on user correction history for this merchant
            val merchantRejectionRate = merchantRejectionRateDeferred.await()
            if (merchantRejectionRate > 0.5f) {
                adjustedConfidence *= 0.5f
                reasons.add("Merchant often rejected")
            }
            // 4. Package rejection rate
            val packageRejectionRate = packageRejectionRateDeferred.await()
            if (packageRejectionRate > 0.7f) {
                adjustedConfidence *= 0.3f
                reasons.add("Package mostly rejected")
            }
            // 5. Boost if user has previously approved similar transactions
            val previouslyApproved = previouslyApprovedDeferred.await()
            if (previouslyApproved) {
                adjustedConfidence = (adjustedConfidence * 1.2f).coerceAtMost(1.0f)
                reasons.add("Previously approved merchant")
            }
        }
        // 6. Penalty for Unknown merchant
        if (parsed.merchant.equals("Unknown", ignoreCase = true)) {
            adjustedConfidence *= 0.5f
            reasons.add("Unknown merchant")
        }
        // Clamp
        adjustedConfidence = adjustedConfidence.coerceIn(0f, 1f)
        // Route
        val decision = when {
            adjustedConfidence >= AUTO_ACCEPT_THRESHOLD -> RoutingDecision.AUTO_ACCEPT
            adjustedConfidence >= REVIEW_THRESHOLD -> RoutingDecision.NEEDS_REVIEW
            else -> RoutingDecision.AUTO_REJECT
        }
        val reason = if (reasons.isEmpty()) {
            "Base confidence: ${(parsed.confidence * 100).toInt()}%"
        } else {
            reasons.joinToString("; ")
        }
        return RoutingResult(decision, adjustedConfidence, reason)
    }
    /**
     * ML weight increases with more training data
     */
    private fun calculateMlWeight(stats: ClassifierStats): Float {
        val totalSamples = stats.totalPositive + stats.totalNegative
        return when {
            totalSamples < 20 -> 0f       // Not ready
            totalSamples < 50 -> 0.2f     // Low confidence in ML
            totalSamples < 100 -> 0.3f    // Growing confidence
            totalSamples < 200 -> 0.35f   // Moderate
            else -> 0.4f                   // Maxed out — never fully trust ML alone
        }
    }
    private fun calculateTrustModifier(stats: SourceStats): Float {
        return when {
            stats.isLikelySpam -> 0.2f
            stats.trustScore > 0.8f -> 1.1f
            stats.trustScore > 0.5f -> 1.0f
            stats.trustScore > 0.2f -> 0.8f
            else -> 0.5f
        }
    }
    private suspend fun getMerchantRejectionRate(merchant: String): Float {
        val total = userCorrectionDao.getMerchantTotalCorrections(merchant)
        if (total < 3) return 0f
        val rejections = userCorrectionDao.getMerchantRejectionCount(merchant)
        return rejections.toFloat() / total
    }
    private suspend fun getPackageRejectionRate(packageName: String): Float {
        val total = userCorrectionDao.getTotalCorrections(packageName)
        if (total < 5) return 0f
        val rejections = userCorrectionDao.getRejectionCount(packageName)
        return rejections.toFloat() / total
    }
    private suspend fun hasPreviousApprovals(merchant: String, packageName: String): Boolean {
        return userCorrectionDao.hasPreviousApprovals(merchant, packageName)
    }
    suspend fun ensureSourceStats(packageName: String) {
        val existing = sourceStatsDao.getByPackage(packageName)
        if (existing == null) {
            sourceStatsDao.insertIfNotExists(SourceStats(packageName = packageName))
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\intelligence\MerchantNormalizer.kt <a name="mainjavacomyournameexpensetrackerdomainintelligencemerchantnormalizerkt"></a>
```kotlin
package com.yourname.expensetracker.domain.intelligence
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class MerchantNormalizer @Inject constructor(
    private val userCorrectionDao: UserCorrectionDao
) {
    private val correctionCache = mutableMapOf<String, String>()
    private var lastCacheClear = 0L
    private val CACHE_DURATION = 300_000 // 5 min
    private val cacheMutex = Mutex()
    // Suffixes/noise to strip
    private val noisePatterns by lazy {
        listOf(
            Regex("""\s*#?\d{3,}.*$"""),
            Regex("""\s*\*+\d+.*$"""),
            Regex("""\s+(?:GR|ATH|THES|ATHENS|THESSALONIKI|THESSALONIK).*$"""),
            Regex("""\s+(?:BRANCH|STORE|SHOP|KATAST|ΚΑΤΑΣΤ)\s*\d*$"""),
            Regex("""\s+\d{1,2}/\d{1,2}/?\d{0,4}$"""),
            Regex("""\s+(?:SA|AE|ΑΕ|EPE|ΕΠΕ|IKE|ΙΚΕ|LTD|GMBH|SRL|OE|ΟΕ|EE|ΕΕ)\s*$"""),
            Regex("""\s+(?:CARD|VISA|MASTER|MC|AMEX)\s*\**\d*$"""),
            Regex("""\s*-\s*\d+$"""),  // trailing dash + numbers
            Regex("""\s+\d{4,}$"""),   // trailing long number
        )
    }
    private val cleanupRegex1 by lazy { Regex("[^A-ZΑ-Ω0-9 &]") }
    private val cleanupRegex2 by lazy { Regex("\\s+") }
    // Known merchant aliases (common variations → canonical name)
    private val KNOWN_ALIASES = mapOf(
        "SKLAVENITIS" to "Sklavenitis",
        "ΣΚΛΑΒΕΝΙΤΗΣ" to "Sklavenitis",
        "AB VASILOPOULOS" to "AB Vassilopoulos",
        "AB ΒΑΣΙΛΟΠΟΥΛΟΣ" to "AB Vassilopoulos",
        "LIDL" to "Lidl",
        "STARBUCKS" to "Starbucks",
        "SHELL" to "Shell",
        "BP" to "BP",
        "EFOOD" to "e-food",
        "WOLT" to "Wolt",
        "NETFLIX" to "Netflix",
        "SPOTIFY" to "Spotify",
        "AMAZON" to "Amazon",
        "UBER" to "Uber",
        "BOLT" to "Bolt",
        "COSMOTE" to "Cosmote",
        "VODAFONE" to "Vodafone",
        "WIND" to "Wind",
        "DEH" to "DEH",
        "ΔΕΗ" to "DEH",
        "EYDAP" to "EYDAP",
        "ΕΥΔΑΠ" to "EYDAP",
    )
    fun normalize(merchant: String): String {
        var result = merchant.uppercase().trim()
        // Apply noise removal patterns
        for (pattern in noisePatterns) {
            result = result.replace(pattern, "")
        }
        result = result
            .replace(cleanupRegex1, "")
            .replace(cleanupRegex2, " ")
            .trim()
        return result
    }
    /**
     * Full normalization: strip noise, apply known aliases, apply user corrections
     */
    suspend fun normalizeAndCorrect(merchant: String): String {
        return applyUserCorrections(merchant)
    }
    /**
     * Apply user corrections only (for pipeline use)
     */
    suspend fun applyUserCorrections(merchant: String): String {
        val normalized = normalize(merchant)
        // Check known aliases first
        for ((key, canonical) in KNOWN_ALIASES) {
            if (normalized.contains(key)) {
                return canonical
            }
        }
        val now = System.currentTimeMillis()
        val cached: String? = cacheMutex.withLock {
            if (now - lastCacheClear > CACHE_DURATION) {
                correctionCache.clear()
                lastCacheClear = now
            }
            correctionCache[normalized]
        }
        if (cached != null) return cached
        val corrected = userCorrectionDao.getMostCommonMerchantCorrection(normalized)
        val result = corrected ?: toTitleCase(normalized)
        cacheMutex.withLock {
            correctionCache[normalized] = result
        }
        return result
    }
    /**
     * Jaccard similarity for matching merchant names
     */
    fun similarity(a: String, b: String): Float {
        val na = normalize(a)
        val nb = normalize(b)
        if (na == nb) return 1.0f
        if (na.isEmpty() || nb.isEmpty()) return 0f
        if (na.contains(nb) || nb.contains(na)) return 0.9f
        // Word overlap (Jaccard)
        val wordsA = na.split(" ").toSet()
        val wordsB = nb.split(" ").toSet()
        val intersection = wordsA.intersect(wordsB)
        val union = wordsA.union(wordsB)
        return if (union.isNotEmpty()) intersection.size.toFloat() / union.size else 0f
    }
    /**
     * Levenshtein distance for close matches
     */
    fun levenshteinDistance(a: String, b: String): Int {
        val m = a.length
        val n = b.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j
        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1,      // deletion
                    dp[i][j - 1] + 1,       // insertion
                    dp[i - 1][j - 1] + cost // substitution
                )
            }
        }
        return dp[m][n]
    }
    /**
     * Normalized Levenshtein similarity (0.0 to 1.0)
     */
    fun levenshteinSimilarity(a: String, b: String): Float {
        val na = normalize(a)
        val nb = normalize(b)
        if (na == nb) return 1.0f
        val maxLen = maxOf(na.length, nb.length)
        if (maxLen == 0) return 1.0f
        return 1.0f - levenshteinDistance(na, nb).toFloat() / maxLen
    }
    /**
     * Find best matching merchant name from a list
     */
    fun findBestMatch(merchant: String, candidates: List<String>, threshold: Float = 0.7f): String? {
        var bestMatch: String? = null
        var bestScore = 0f
        for (candidate in candidates) {
            val jaccardScore = similarity(merchant, candidate)
            val levenScore = levenshteinSimilarity(merchant, candidate)
            // Weighted combination
            val score = jaccardScore * 0.4f + levenScore * 0.6f
            if (score > bestScore && score >= threshold) {
                bestScore = score
                bestMatch = candidate
            }
        }
        return bestMatch
    }
    private fun toTitleCase(text: String): String {
        return text.split(" ").joinToString(" ") { word ->
            word.lowercase().replaceFirstChar { it.uppercaseChar() }
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\intelligence\TransactionClassifier.kt <a name="mainjavacomyournameexpensetrackerdomainintelligencetransactionclassifierkt"></a>
```kotlin
// domain/intelligence/TransactionClassifier.kt
package com.yourname.expensetracker.domain.intelligence
import android.content.Context
import android.util.Log
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
/**
 * Lightweight on-device text classifier using Naive Bayes.
 * No TensorFlow needed. Learns from user corrections.
 */
@Singleton
class TransactionClassifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val userCorrectionDao: UserCorrectionDao
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var saveJob: Job? = null
    companion object {
        private const val TAG = "TxClassifier"
        private const val MODEL_FILE = "naive_bayes_model.json"
        private const val MIN_TRAINING_SAMPLES = 20
        private const val LAPLACE_SMOOTHING = 1.0
    }
    private val positiveWordCounts = mutableMapOf<String, Int>()
    private val negativeWordCounts = mutableMapOf<String, Int>()
    private var totalPositive = 0
    private var totalNegative = 0
    private var vocabularySize = 0
    private val positiveBigramCounts = mutableMapOf<String, Int>()
    private val negativeBigramCounts = mutableMapOf<String, Int>()
    private val mutex = Mutex()
    @Volatile
    private var isLoaded = false
    private var lastTrainingCount = 0
    suspend fun initialize() {
        if (isLoaded) return
        mutex.withLock {
            if (isLoaded) return
            if (loadFromDisk()) {
                isLoaded = true
                Log.d(TAG, "Loaded model from disk: +$totalPositive/-$totalNegative samples")
            }
            val correctionCount = userCorrectionDao.getCount()
            if (correctionCount > lastTrainingCount && correctionCount >= MIN_TRAINING_SAMPLES) {
                retrainFromCorrectionsInternal()
            }
            isLoaded = true
        }
    }
    suspend fun predict(text: String): Float {
        if (!isLoaded) initialize()
        if (totalPositive + totalNegative < MIN_TRAINING_SAMPLES) {
            return 0.5f 
        }
        val features = extractFeatures(text)
        return mutex.withLock {
            calculateProbability(features)
        }
    }
    suspend fun train(text: String, isTransaction: Boolean) {
        mutex.withLock {
            val features = extractFeatures(text)
            addTrainingSample(features, isTransaction)
            scheduleSave()
        }
    }
    suspend fun retrainFromCorrections() {
        mutex.withLock {
            retrainFromCorrectionsInternal()
        }
    }
    private suspend fun retrainFromCorrectionsInternal() {
        val corrections = userCorrectionDao.getAll()
        if (corrections.size < MIN_TRAINING_SAMPLES) {
            Log.d(TAG, "Not enough corrections to train: ${corrections.size}/$MIN_TRAINING_SAMPLES")
            return
        }
        positiveWordCounts.clear()
        negativeWordCounts.clear()
        positiveBigramCounts.clear()
        negativeBigramCounts.clear()
        totalPositive = 0
        totalNegative = 0
        for (correction in corrections) {
            val text = buildTrainingText(correction)
            if (text.isNotBlank()) {
                val features = extractFeatures(text)
                if (correction.wasRejected) {
                    addTrainingSample(features, isTransaction = false)
                } else if (correction.wasApproved) {
                    addTrainingSample(features, isTransaction = true)
                }
            }
        }
        vocabularySize = (positiveWordCounts.keys + negativeWordCounts.keys).toSet().size
        lastTrainingCount = corrections.size
        scheduleSave()
        Log.d(TAG, "Retrained from ${corrections.size} corrections: +$totalPositive/-$totalNegative")
    }
    private fun scheduleSave() {
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(2000)
            saveToDisk()
        }
    }
    fun getStats(): ClassifierStats {
        return ClassifierStats(
            totalPositive = totalPositive,
            totalNegative = totalNegative,
            vocabularySize = vocabularySize,
            isReady = totalPositive + totalNegative >= MIN_TRAINING_SAMPLES
        )
    }
    private fun addTrainingSample(features: FeatureSet, isTransaction: Boolean) {
        if (isTransaction) {
            totalPositive++
            features.words.forEach {
                positiveWordCounts[it] = (positiveWordCounts[it] ?: 0) + 1
            }
            features.bigrams.forEach {
                positiveBigramCounts[it] = (positiveBigramCounts[it] ?: 0) + 1
            }
        } else {
            totalNegative++
            features.words.forEach {
                negativeWordCounts[it] = (negativeWordCounts[it] ?: 0) + 1
            }
            features.bigrams.forEach {
                negativeBigramCounts[it] = (negativeBigramCounts[it] ?: 0) + 1
            }
        }
        vocabularySize = (positiveWordCounts.keys + negativeWordCounts.keys).toSet().size
    }
    private fun calculateProbability(features: FeatureSet): Float {
        val total = totalPositive + totalNegative
        if (total == 0) return 0.5f
        var logProbPos = ln(totalPositive.toDouble() / total)
        var logProbNeg = ln(totalNegative.toDouble() / total)
        val vocabSize = vocabularySize.coerceAtLeast(1)
        for (word in features.words) {
            val posCount = (positiveWordCounts[word] ?: 0).toDouble() + LAPLACE_SMOOTHING
            val negCount = (negativeWordCounts[word] ?: 0).toDouble() + LAPLACE_SMOOTHING
            val posDenom = totalPositive.toDouble() + vocabSize * LAPLACE_SMOOTHING
            val negDenom = totalNegative.toDouble() + vocabSize * LAPLACE_SMOOTHING
            logProbPos += ln(posCount / posDenom)
            logProbNeg += ln(negCount / negDenom)
        }
        val bigramWeight = 0.5
        val bigramVocabSize = (positiveBigramCounts.keys + negativeBigramCounts.keys).toSet().size.coerceAtLeast(1)
        for (bigram in features.bigrams) {
            val posCount = (positiveBigramCounts[bigram] ?: 0).toDouble() + LAPLACE_SMOOTHING
            val negCount = (negativeBigramCounts[bigram] ?: 0).toDouble() + LAPLACE_SMOOTHING
            val posDenom = totalPositive.toDouble() + bigramVocabSize * LAPLACE_SMOOTHING
            val negDenom = totalNegative.toDouble() + bigramVocabSize * LAPLACE_SMOOTHING
            logProbPos += bigramWeight * ln(posCount / posDenom)
            logProbNeg += bigramWeight * ln(negCount / negDenom)
        }
        val logOdds = logProbPos - logProbNeg
        val clampedLogOdds = logOdds.coerceIn(-20.0, 20.0)
        return (1.0 / (1.0 + Math.exp(-clampedLogOdds))).toFloat()
    }
    private fun extractFeatures(text: String): FeatureSet {
        val normalized = text.lowercase()
            .replace(Regex("[^a-zα-ωά-ώ0-9€$£ ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        val words = normalized.split(" ")
            .filter { it.length >= 2 }
            .toMutableList()
        if (Regex("""\d+[.,]\d{2}""").containsMatchIn(text)) {
            words.add("__HAS_DECIMAL_AMOUNT__")
        }
        if (Regex("""[€$£]""").containsMatchIn(text)) {
            words.add("__HAS_CURRENCY_SYMBOL__")
        }
        if (Regex("""(?i)(EUR|USD|GBP)""").containsMatchIn(text)) {
            words.add("__HAS_CURRENCY_CODE__")
        }
        if (Regex("""(?i)(paid|payment|purchase|charged|debit)""").containsMatchIn(text)) {
            words.add("__HAS_PAYMENT_KEYWORD__")
        }
        if (Regex("""(?i)(πληρωμ|αγορ|χρέωσ|συναλλαγ)""").containsMatchIn(text)) {
            words.add("__HAS_GREEK_PAYMENT_KEYWORD__")
        }
        if (Regex("""(?i)(offer|discount|promo|sale|free|δωρεάν|προσφορά|έκπτωση)""").containsMatchIn(text)) {
            words.add("__HAS_PROMO_KEYWORD__")
        }
        if (Regex("""(?i)(otp|code|verify|κωδικός)""").containsMatchIn(text)) {
            words.add("__HAS_OTP_KEYWORD__")
        }
        if (Regex("""(?i)(balance|υπόλοιπο)""").containsMatchIn(text)) {
            words.add("__HAS_BALANCE_KEYWORD__")
        }
        val actualWords = normalized.split(" ").filter { it.length >= 2 }
        val bigrams = if (actualWords.size >= 2) {
            actualWords.zipWithNext().map { (a, b) -> "${a}_$b" }
        } else {
            emptyList()
        }
        return FeatureSet(words, bigrams)
    }
    private fun buildTrainingText(
        correction: com.yourname.expensetracker.data.database.entity.UserCorrection
    ): String {
        return listOfNotNull(
            correction.notificationTitle,
            correction.notificationText,
            correction.originalMerchant
        ).joinToString(" ")
    }
    private suspend fun saveToDisk() {
        withContext(Dispatchers.IO) {
            try {
                val json = JSONObject().apply {
                    put("totalPositive", totalPositive)
                    put("totalNegative", totalNegative)
                    put("vocabularySize", vocabularySize)
                    put("lastTrainingCount", lastTrainingCount)
                    put("positiveWords", JSONObject().apply {
                        positiveWordCounts.forEach { (k, v) -> put(k, v) }
                    })
                    put("negativeWords", JSONObject().apply {
                        negativeWordCounts.forEach { (k, v) -> put(k, v) }
                    })
                    put("positiveBigrams", JSONObject().apply {
                        positiveBigramCounts.forEach { (k, v) -> put(k, v) }
                    })
                    put("negativeBigrams", JSONObject().apply {
                        negativeBigramCounts.forEach { (k, v) -> put(k, v) }
                    })
                }
                File(context.filesDir, MODEL_FILE).writeText(json.toString())
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save model", e)
            }
        }
    }
    private fun loadFromDisk(): Boolean {
        return try {
            val file = File(context.filesDir, MODEL_FILE)
            if (!file.exists()) return false
            val json = JSONObject(file.readText())
            totalPositive = json.getInt("totalPositive")
            totalNegative = json.getInt("totalNegative")
            vocabularySize = json.optInt("vocabularySize", 0)
            lastTrainingCount = json.optInt("lastTrainingCount", 0)
            val posWords = json.getJSONObject("positiveWords")
            positiveWordCounts.clear()
            posWords.keys().forEach { key ->
                positiveWordCounts[key] = posWords.getInt(key)
            }
            val negWords = json.getJSONObject("negativeWords")
            negativeWordCounts.clear()
            negWords.keys().forEach { key ->
                negativeWordCounts[key] = negWords.getInt(key)
            }
            json.optJSONObject("positiveBigrams")?.let { posBi ->
                positiveBigramCounts.clear()
                posBi.keys().forEach { key ->
                    positiveBigramCounts[key] = posBi.getInt(key)
                }
            }
            json.optJSONObject("negativeBigrams")?.let { negBi ->
                negativeBigramCounts.clear()
                negBi.keys().forEach { key ->
                    negativeBigramCounts[key] = negBi.getInt(key)
                }
            }
            vocabularySize = (positiveWordCounts.keys + negativeWordCounts.keys).toSet().size
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load model", e)
            false
        }
    }
}
data class FeatureSet(
    val words: List<String>,
    val bigrams: List<String>
)
data class ClassifierStats(
    val totalPositive: Int,
    val totalNegative: Int,
    val vocabularySize: Int,
    val isReady: Boolean
)

```

---

## main\java\com\yourname\expensetracker\domain\receipt\ReceiptOcrService.kt <a name="mainjavacomyournameexpensetrackerdomainreceiptreceiptocrservicekt"></a>
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

---

## main\java\com\yourname\expensetracker\ui\MainActivity.kt <a name="mainjavacomyournameexpensetrackeruimainactivitykt"></a>
```kotlin
package com.yourname.expensetracker.ui
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.ui.screens.analytics.AnalyticsScreen
import com.yourname.expensetracker.ui.screens.categories.CategoryScreen
import com.yourname.expensetracker.ui.screens.debug.DebugScreen
import com.yourname.expensetracker.ui.screens.home.HomeScreen
import com.yourname.expensetracker.ui.screens.review.ReviewScreen
import com.yourname.expensetracker.ui.screens.review.ReviewViewModel
import com.yourname.expensetracker.ui.screens.transactions.TransactionsScreen
import com.yourname.expensetracker.ui.theme.ExpenseTrackerTheme
import dagger.hilt.android.AndroidEntryPoint
import androidx.compose.material.icons.filled.Analytics
import com.yourname.expensetracker.ui.screens.budget.BudgetScreen
import androidx.compose.material.icons.filled.CheckCircle
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ExpenseTrackerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen()
                }
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }
    // Global app state (badges, etc)
    val mainViewModel: MainViewModel = hiltViewModel()
    val pendingCount by mainViewModel.pendingReviewCount.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != 
                PackageManager.PERMISSION_GRANTED) {
                permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Default.Home, contentDescription = "Home") },
                    label = { Text("Home") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Default.ShoppingCart, contentDescription = "Transactions") },
                    label = { Text("Transactions") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { 
                        BadgedBox(
                            badge = {
                                if (pendingCount > 0) {
                                    Badge { Text("$pendingCount") }
                                }
                            }
                        ) {
                            Icon(Icons.Default.Notifications, contentDescription = "Review")
                        }
                    },
                    label = { Text("Review") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.Analytics, contentDescription = "Analytics") },
                    label = { Text("Analytics") }
                )
                NavigationBarItem(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    icon = { Icon(Icons.Default.CheckCircle, contentDescription = "Budget") },
                    label = { Text("Budget") }
                )
                NavigationBarItem(
                    selected = selectedTab == 5,
                    onClick = { selectedTab = 5 },
                    icon = { Icon(Icons.Default.List, contentDescription = "Categories") },
                    label = { Text("Categories") }
                )
                NavigationBarItem(
                    selected = selectedTab == 6,
                    onClick = { selectedTab = 6 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Debug") },
                    label = { Text("Debug") }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier.padding(padding)
        ) {
            when (selectedTab) {
                0 -> HomeScreen()
                1 -> com.yourname.expensetracker.ui.screens.transactions.TransactionsScreen()
                2 -> ReviewScreen()
                3 -> AnalyticsScreen()
                4 -> BudgetScreen()
                5 -> com.yourname.expensetracker.ui.screens.categories.CategoryScreen()
                6 -> DebugScreen()
            }
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\MainViewModel.kt <a name="mainjavacomyournameexpensetrackeruimainviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.repository.NotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
@HiltViewModel
class MainViewModel @Inject constructor(
    private val repository: NotificationRepository
) : ViewModel() {
    val pendingReviewCount: StateFlow<Int> = repository
        .getPendingReviewCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\addexpense\AddExpenseSheet.kt <a name="mainjavacomyournameexpensetrackeruiscreensaddexpenseaddexpensesheetkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.addexpense
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
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
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.dao.MerchantSuggestion
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import java.text.SimpleDateFormat
import java.util.*
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddExpenseSheet(
    onDismiss: () -> Unit,
    viewModel: AddExpenseViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val focusManager = LocalFocusManager.current
    val scrollState = rememberScrollState()
    // Handle save result
    LaunchedEffect(state.saveResult) {
        when (state.saveResult) {
            is SaveResult.Success -> {
                viewModel.reset()
                onDismiss()
            }
            else -> { /* handled in UI */ }
        }
    }
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            // Top bar
            TopAppBar(
                title = { Text("Add Expense", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.reset()
                        onDismiss()
                    }) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
                actions = {
                    Button(
                        onClick = { viewModel.save() },
                        enabled = !state.isSaving,
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        if (state.isSaving) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text("Save")
                        }
                    }
                }
            )
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(modifier = Modifier.height(8.dp))
                // === Merchant Field with Autocomplete ===
                MerchantFieldWithSuggestions(
                    merchant = state.merchant,
                    onMerchantChange = { viewModel.updateMerchant(it) },
                    suggestions = state.suggestions,
                    showSuggestions = state.showSuggestions,
                    onSuggestionSelected = { viewModel.selectSuggestion(it) },
                    onDismissSuggestions = { viewModel.dismissSuggestions() },
                    error = state.merchantError,
                    categories = categories,
                    onNextFocus = { focusManager.moveFocus(FocusDirection.Down) }
                )
                // === Amount Field ===
                OutlinedTextField(
                    value = state.amount,
                    onValueChange = { viewModel.updateAmount(it) },
                    label = { Text("Amount (€)") },
                    placeholder = { Text("0.00") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Decimal,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() }
                    ),
                    isError = state.amountError != null,
                    supportingText = state.amountError?.let { { Text(it) } },
                    leadingIcon = { Text("€", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                    modifier = Modifier.fillMaxWidth()
                )
                // === Payment Method ===
                Text(
                    "Payment Method",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
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
                    PaymentMethodChip(
                        label = "🏦 Transfer",
                        selected = state.paymentMethod == PaymentMethod.BANK_TRANSFER,
                        onClick = { viewModel.selectPaymentMethod(PaymentMethod.BANK_TRANSFER) },
                        modifier = Modifier.weight(1f)
                    )
                }
                // === Category Selector ===
                Text(
                    "Category",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                CategoryGrid(
                    categories = categories,
                    selectedId = state.selectedCategoryId,
                    onSelect = { viewModel.selectCategory(it) }
                )
                // === Date Picker ===
                DateSelector(
                    dateMs = state.date,
                    onDateSelected = { viewModel.updateDate(it) }
                )
                // === Transaction Type (collapsible) ===
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleTransactionType() },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Transaction Type: ${state.transactionType.name.lowercase()
                            .replaceFirstChar { it.uppercase() }}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        if (state.showTransactionType) Icons.Default.KeyboardArrowUp
                        else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Toggle"
                    )
                }
                AnimatedVisibility(visible = state.showTransactionType) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TransactionType.values().filter { it != TransactionType.UNKNOWN }.forEach { type ->
                            FilterChip(
                                selected = state.transactionType == type,
                                onClick = { viewModel.selectTransactionType(type) },
                                label = {
                                    Text(
                                        type.name.lowercase().replaceFirstChar { it.uppercase() },
                                        fontSize = 12.sp
                                    )
                                }
                            )
                        }
                    }
                }
                // === Notes (collapsible) ===
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { viewModel.toggleNotes() },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "Notes",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                    Icon(
                        if (state.showNotes) Icons.Default.KeyboardArrowUp
                        else Icons.Default.KeyboardArrowDown,
                        contentDescription = "Toggle"
                    )
                }
                AnimatedVisibility(visible = state.showNotes) {
                    OutlinedTextField(
                        value = state.notes,
                        onValueChange = { viewModel.updateNotes(it) },
                        label = { Text("Optional notes") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4
                    )
                }
                // === Error Messages ===
                when (val result = state.saveResult) {
                    is SaveResult.Duplicate -> {
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
                    is SaveResult.Error -> {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            ),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                "❌ ${result.message}",
                                modifier = Modifier.padding(12.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                    else -> {}
                }
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}
@Composable
fun MerchantFieldWithSuggestions(
    merchant: String,
    onMerchantChange: (String) -> Unit,
    suggestions: List<MerchantSuggestion>,
    showSuggestions: Boolean,
    onSuggestionSelected: (MerchantSuggestion) -> Unit,
    onDismissSuggestions: () -> Unit,
    error: String?,
    categories: List<Category>,
    onNextFocus: () -> Unit
) {
    val categoryMap = remember(categories) { categories.associateBy { it.id } }
    Column {
        OutlinedTextField(
            value = merchant,
            onValueChange = onMerchantChange,
            label = { Text("Merchant / Place") },
            placeholder = { Text("e.g. Sklavenitis, Starbucks...") },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(onNext = { onNextFocus() }),
            modifier = Modifier.fillMaxWidth()
        )
        // Suggestions dropdown
        AnimatedVisibility(visible = showSuggestions && suggestions.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                shape = RoundedCornerShape(12.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
            ) {
                Column {
                    suggestions.forEach { suggestion ->
                        val category = suggestion.categoryId?.let { categoryMap[it] }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSuggestionSelected(suggestion) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Category icon
                                if (category != null) {
                                    val catColor = remember(category.color) {
                                        try {
                                            Color(android.graphics.Color.parseColor(category.color))
                                        } catch (e: Exception) {
                                            Color.Gray
                                        }
                                    }
                                    Box(
                                        modifier = Modifier
                                            .size(32.dp)
                                            .background(catColor, CircleShape),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(category.icon, fontSize = 16.sp)
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        suggestion.merchant,
                                        fontWeight = FontWeight.Medium,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        buildString {
                                            if (category != null) append(category.name)
                                            if (suggestion.txCount > 0) {
                                                if (isNotEmpty()) append(" · ")
                                                append("${suggestion.txCount} visits")
                                            }
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    "~€${String.format("%.2f", suggestion.avgAmount)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                        if (suggestion != suggestions.last()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }
}
@Composable
fun PaymentMethodChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = if (selected)
            MaterialTheme.colorScheme.primaryContainer
        else
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        tonalElevation = if (selected) 2.dp else 0.dp
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                label,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                fontSize = 14.sp,
                color = if (selected)
                    MaterialTheme.colorScheme.onPrimaryContainer
                else
                    MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
@Composable
fun CategoryGrid(
    categories: List<Category>,
    selectedId: Long?,
    onSelect: (Long) -> Unit
) {
    // Wrapping flow layout using multiple rows
    val chunked = categories.chunked(4)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        chunked.forEach { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                row.forEach { category ->
                    val isSelected = selectedId == category.id
                    val catColor = remember(category.color) {
                        try {
                            Color(android.graphics.Color.parseColor(category.color))
                        } catch (e: Exception) {
                            Color.Gray
                        }
                    }
                    Surface(
                        onClick = { onSelect(category.id) },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) catColor.copy(alpha = 0.2f)
                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                        border = if (isSelected) androidx.compose.foundation.BorderStroke(
                            2.dp, catColor
                        ) else null
                    ) {
                        Column(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 4.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(category.icon, fontSize = 20.sp)
                            Text(
                                category.name,
                                fontSize = 10.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                // Fill remaining space in last row
                repeat(4 - row.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateSelector(
    dateMs: Long,
    onDateSelected: (Long) -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("EEE, dd MMM yyyy, HH:mm", Locale.getDefault()) }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(
        initialSelectedDateMillis = dateMs
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDatePicker = true },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.DateRange,
            contentDescription = "Date",
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                "Date",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                dateFormat.format(Date(dateMs)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { selectedDate ->
                            // Preserve time of day, just change the date
                            val calOld = Calendar.getInstance().apply { timeInMillis = dateMs }
                            val calNew = Calendar.getInstance().apply { timeInMillis = selectedDate }
                            calNew.set(Calendar.HOUR_OF_DAY, calOld.get(Calendar.HOUR_OF_DAY))
                            calNew.set(Calendar.MINUTE, calOld.get(Calendar.MINUTE))
                            calNew.set(Calendar.SECOND, calOld.get(Calendar.SECOND))
                            onDateSelected(calNew.timeInMillis)
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("OK")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("Cancel")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\addexpense\AddExpenseViewModel.kt <a name="mainjavacomyournameexpensetrackeruiscreensaddexpenseaddexpenseviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.addexpense
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.dao.MerchantSuggestion
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PaymentMethod
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject
data class AddExpenseState(
    val merchant: String = "",
    val amount: String = "",
    val selectedCategoryId: Long? = null,
    val paymentMethod: PaymentMethod = PaymentMethod.CASH,
    val transactionType: TransactionType = TransactionType.PURCHASE,
    val date: Long = System.currentTimeMillis(),
    val notes: String = "",
    val showNotes: Boolean = false,
    val showTransactionType: Boolean = false,
    val suggestions: List<MerchantSuggestion> = emptyList(),
    val showSuggestions: Boolean = false,
    val isSaving: Boolean = false,
    val saveResult: SaveResult? = null,
    val merchantError: String? = null,
    val amountError: String? = null
)
sealed class SaveResult {
    object Success : SaveResult()
    object Duplicate : SaveResult()
    data class Error(val message: String) : SaveResult()
}
@HiltViewModel
class AddExpenseViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {
    private val _state = MutableStateFlow(AddExpenseState())
    val state: StateFlow<AddExpenseState> = _state.asStateFlow()
    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private var searchJob: Job? = null
    fun updateMerchant(value: String) {
        _state.update {
            it.copy(
                merchant = value,
                merchantError = null,
                saveResult = null
            )
        }
        // Debounced search
        searchJob?.cancel()
        if (value.length >= 2) {
            searchJob = viewModelScope.launch {
                delay(300)
                val suggestions = repository.searchMerchants(value)
                _state.update {
                    it.copy(
                        suggestions = suggestions,
                        showSuggestions = suggestions.isNotEmpty()
                    )
                }
            }
        } else {
            _state.update { it.copy(suggestions = emptyList(), showSuggestions = false) }
        }
    }
    fun selectSuggestion(suggestion: MerchantSuggestion) {
        _state.update {
            it.copy(
                merchant = suggestion.merchant,
                selectedCategoryId = suggestion.categoryId ?: it.selectedCategoryId,
                amount = if (it.amount.isBlank()) String.format("%.2f", suggestion.avgAmount) else it.amount,
                suggestions = emptyList(),
                showSuggestions = false,
                merchantError = null
            )
        }
    }
    fun dismissSuggestions() {
        _state.update { it.copy(showSuggestions = false) }
    }
    fun updateAmount(value: String) {
        // Only allow valid decimal input
        val filtered = value.filter { it.isDigit() || it == '.' || it == ',' }
        _state.update {
            it.copy(
                amount = filtered,
                amountError = null,
                saveResult = null
            )
        }
    }
    fun selectCategory(categoryId: Long) {
        _state.update { it.copy(selectedCategoryId = categoryId) }
    }
    fun selectPaymentMethod(method: PaymentMethod) {
        _state.update { it.copy(paymentMethod = method) }
    }
    fun selectTransactionType(type: TransactionType) {
        _state.update { it.copy(transactionType = type) }
    }
    fun updateDate(dateMs: Long) {
        _state.update { it.copy(date = dateMs) }
    }
    fun updateNotes(value: String) {
        _state.update { it.copy(notes = value) }
    }
    fun toggleNotes() {
        _state.update { it.copy(showNotes = !it.showNotes) }
    }
    fun toggleTransactionType() {
        _state.update { it.copy(showTransactionType = !it.showTransactionType) }
    }
    fun save() {
        val currentState = _state.value
        // Validate
        val merchantTrimmed = currentState.merchant.trim()
        if (merchantTrimmed.isBlank()) {
            _state.update { it.copy(merchantError = "Merchant name is required") }
            return
        }
        val amountStr = currentState.amount.replace(",", ".")
        val amount = amountStr.toDoubleOrNull()
        if (amount == null || amount <= 0) {
            _state.update { it.copy(amountError = "Enter a valid amount") }
            return
        }
        if (amount > 50000) {
            _state.update { it.copy(amountError = "Amount seems too large") }
            return
        }
        _state.update { it.copy(isSaving = true, saveResult = null) }
        viewModelScope.launch {
            try {
                val result = repository.addManualExpense(
                    merchant = merchantTrimmed,
                    amount = amount,
                    currency = "EUR",
                    categoryId = currentState.selectedCategoryId,
                    transactionType = currentState.transactionType,
                    paymentMethod = currentState.paymentMethod,
                    date = currentState.date,
                    notes = currentState.notes.takeIf { it.isNotBlank() }
                )
                if (result == -1L) {
                    _state.update {
                        it.copy(isSaving = false, saveResult = SaveResult.Duplicate)
                    }
                } else {
                    _state.update {
                        it.copy(isSaving = false, saveResult = SaveResult.Success)
                    }
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isSaving = false,
                        saveResult = SaveResult.Error(e.message ?: "Unknown error")
                    )
                }
            }
        }
    }
    fun reset() {
        _state.value = AddExpenseState()
    }
    fun clearSaveResult() {
        _state.update { it.copy(saveResult = null) }
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\analytics\AnalyticsScreen.kt <a name="mainjavacomyournameexpensetrackeruiscreensanalyticsanalyticsscreenkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.analytics
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.patrykandpatrick.vico.compose.axis.horizontal.rememberBottomAxis
import com.patrykandpatrick.vico.compose.axis.vertical.rememberStartAxis
import com.patrykandpatrick.vico.compose.chart.Chart
import com.patrykandpatrick.vico.compose.chart.column.columnChart
import com.patrykandpatrick.vico.compose.chart.line.lineChart
import com.patrykandpatrick.vico.core.entry.entryModelOf
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.domain.analytics.*
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyticsScreen(
    viewModel: AnalyticsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Analytics", fontWeight = FontWeight.Bold) }
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 1. Total Spent Header
                item { TotalSpentHeader(state) }
                // 2. Period Selector
                item { PeriodSelector(state.selectedPeriod) { viewModel.selectPeriod(it) } }
                // 3. Main Chart
                item { AnalyticsChart(state) }
                // 4. Insights Section
                if (state.insights.isNotEmpty()) {
                    item { SectionHeader("Insights") }
                    item { InsightsRow(state.insights) }
                }
                // 5. Category Breakdown
                if (state.categoryBreakdown.isNotEmpty()) {
                    item { SectionHeader("By Category") }
                    items(state.categoryBreakdown) { CategoryItem(it) }
                }
                // 6. Merchant Breakdown
                if (state.merchantBreakdown.isNotEmpty()) {
                    item { SectionHeader("Top Merchants") }
                    items(state.merchantBreakdown.take(10)) { MerchantItem(it) }
                }
                // 7. Recurring
                if (state.recurring.isNotEmpty()) {
                    item { SectionHeader("Detected Recurring") }
                    items(state.recurring) { RecurringItem(it) }
                }
                item { Spacer(modifier = Modifier.height(32.dp)) }
            }
        }
    }
}
@Composable
fun TotalSpentHeader(state: AnalyticsState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = state.selectedPeriod.name.lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
            Text(
                text = "€${String.format("%.2f", state.currentTotal)}",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            state.changePercent?.let { change ->
                val isIncrease = change > 0
                val color = if (isIncrease) Color(0xFFE57373) else Color(0xFF81C784)
                val arrow = if (isIncrease) "▲" else "▼"
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "$arrow ${String.format("%.1f", Math.abs(change))}%",
                        color = color,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = " vs previous period",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                    )
                }
            }
        }
    }
}
@Composable
fun PeriodSelector(selected: TimePeriod, onSelect: (TimePeriod) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        items(TimePeriod.values()) { period ->
            FilterChip(
                selected = selected == period,
                onClick = { onSelect(period) },
                label = { Text(period.name) }
            )
        }
    }
}
@Composable
fun AnalyticsChart(state: AnalyticsState) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(240.dp),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "Daily Spending",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            if (state.dailyTotals.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No data for this period", color = Color.Gray)
                }
            } else {
                val entries = state.dailyTotals.values.map { it.toFloat() }
                val chartEntryModel = remember(entries) { entryModelOf(*entries.toTypedArray()) }
                Chart(
                    chart = columnChart(),
                    model = chartEntryModel,
                    startAxis = rememberStartAxis(),
                    bottomAxis = rememberBottomAxis(),
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp)
    )
}
@Composable
fun InsightsRow(insights: List<SpendingInsight>) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(insights) { insight ->
            Card(
                modifier = Modifier.width(280.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .background(MaterialTheme.colorScheme.surface, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(insight.icon, fontSize = 20.sp)
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            insight.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            insight.description,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}
@Composable
fun CategoryItem(item: CategoryBreakdown) {
    // Optimize color parsing: remember the color based on the category's hex string
    val categoryColor = remember(item.category.color) {
        try {
            Color(android.graphics.Color.parseColor(item.category.color))
        } catch (e: Exception) {
            Color.Gray
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(categoryColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(item.category.icon)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.category.name, fontWeight = FontWeight.Bold)
            LinearProgressIndicator(
                progress = item.percentage / 100f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = categoryColor,
                trackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text("€${String.format("%.2f", item.total)}", fontWeight = FontWeight.Bold)
            Text("${item.percentage.toInt()}%", style = MaterialTheme.typography.bodySmall)
        }
    }
}
@Composable
fun MerchantItem(item: MerchantBreakdown) {
    ListItem(
        headlineContent = { Text(item.name, fontWeight = FontWeight.Medium) },
        supportingContent = { Text("${item.transactionCount} transactions") },
        trailingContent = { Text("€${String.format("%.2f", item.totalSpent)}", fontWeight = FontWeight.Bold) },
        leadingContent = {
            Surface(
                modifier = Modifier.size(40.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.secondaryContainer
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(item.name.take(1).uppercase(), fontWeight = FontWeight.Bold)
                }
            }
        }
    )
}
@Composable
fun RecurringItem(item: RecurringCandidate) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(item.merchant, fontWeight = FontWeight.Bold)
                Text("~every ${item.intervalDays} days", style = MaterialTheme.typography.bodySmall)
            }
            Text("€${String.format("%.2f", item.amount)}", fontWeight = FontWeight.Bold)
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\analytics\AnalyticsViewModel.kt <a name="mainjavacomyournameexpensetrackeruiscreensanalyticsanalyticsviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.analytics
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.domain.analytics.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject
data class AnalyticsState(
    val selectedPeriod: TimePeriod = TimePeriod.MONTH,
    val currentTotal: Double = 0.0,
    val previousTotal: Double? = null,
    val changePercent: Float? = null,
    val transactionCount: Int = 0,
    val categoryBreakdown: List<CategoryBreakdown> = emptyList(),
    val merchantBreakdown: List<MerchantBreakdown> = emptyList(),
    val dailyTotals: Map<String, Double> = emptyMap(),
    val insights: List<SpendingInsight> = emptyList(),
    val recurring: List<RecurringCandidate> = emptyList(),
    val isLoading: Boolean = true
)
@HiltViewModel
class AnalyticsViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val categoryRepository: CategoryRepository,
    private val insightsEngine: InsightsEngine
) : ViewModel() {
    private val _state = MutableStateFlow(AnalyticsState())
    val state: StateFlow<AnalyticsState> = _state.asStateFlow()
    private val _selectedPeriod = MutableStateFlow(TimePeriod.MONTH)
    init {
        combine(
            repository.getAllExpenses(),
            categoryRepository.allCategories,
            _selectedPeriod
        ) { expenses, categories, period ->
            Triple(expenses, categories, period)
        }
        .debounce(300)
        .onEach { (expenses, categories, period) ->
            _state.update { it.copy(isLoading = true, selectedPeriod = period) }
            computeAnalytics(expenses, categories, period)
        }
        .flowOn(Dispatchers.Default)
        .launchIn(viewModelScope)
    }
    fun selectPeriod(period: TimePeriod) {
        _selectedPeriod.value = period
    }
    private suspend fun computeAnalytics(
        allExpenses: List<Expense>,
        categories: List<Category>,
        period: TimePeriod
    ) {
        val purchases = allExpenses.filter { it.transactionType == TransactionType.PURCHASE }
        val now = System.currentTimeMillis()
        val categoryMap = categories.associateBy { it.id }
        // Calculate date ranges
        val (currentStart, currentEnd) = getPeriodRange(period, now)
        val periodLength = currentEnd - currentStart
        val previousStart = currentStart - periodLength
        val previousEnd = currentStart
        // Current period expenses
        val currentExpenses = purchases.filter { it.date in currentStart..currentEnd }
        val previousExpenses = purchases.filter { it.date in previousStart..previousEnd }
        val currentTotal = currentExpenses.sumOf { it.amount }
        val previousTotal = previousExpenses.sumOf { it.amount }
        val changePercent = if (previousTotal > 0) {
            ((currentTotal - previousTotal) / previousTotal * 100).toFloat()
        } else null
        // Category breakdown
        val categoryBreakdown = currentExpenses
            .groupBy { it.categoryId }
            .mapNotNull { (catId, exps) ->
                val cat = catId?.let { categoryMap[it] } ?: return@mapNotNull null
                CategoryBreakdown(
                    category = cat,
                    total = exps.sumOf { it.amount },
                    count = exps.size,
                    percentage = if (currentTotal > 0)
                        (exps.sumOf { it.amount } / currentTotal * 100).toFloat()
                    else 0f
                )
            }
            .sortedByDescending { it.total }
        // Merchant breakdown
        val merchantBreakdown = currentExpenses
            .groupBy { it.merchant.uppercase() }
            .map { (_, exps) ->
                val total = exps.sumOf { it.amount }
                MerchantBreakdown(
                    name = exps.first().merchant,
                    totalSpent = total,
                    transactionCount = exps.size,
                    averageTransaction = total / exps.size,
                    categoryId = exps.firstOrNull()?.categoryId
                )
            }
            .sortedByDescending { it.totalSpent }
        // Daily totals for chart
        val chartDays = when (period) {
            TimePeriod.TODAY -> 1
            TimePeriod.WEEK -> 7
            TimePeriod.MONTH -> 30
            TimePeriod.YEAR -> 365
            TimePeriod.ALL -> {
                val oldest = purchases.minOfOrNull { it.date } ?: now
                ((now - oldest) / 86_400_000L).toInt().coerceIn(7, 365)
            }
        }
        val dailyTotals = insightsEngine.buildDailyTotals(currentExpenses, chartDays)
        // Insights
        val insightsSnapshot = insightsEngine.generateInsights(categories, allExpenses)
        val insights = insightsEngine.getLegacyInsights(insightsSnapshot)
        // Recurring (use the list from snapshot but mapped to legacy if needed, or just legacy detection)
        // Using duplicate detection logic for now to stay compatible with UI model
        val recurring = insightsEngine.detectRecurring(purchases)
        _state.update {
            it.copy(
                selectedPeriod = period,
                currentTotal = currentTotal,
                previousTotal = if (previousTotal > 0) previousTotal else null,
                changePercent = changePercent,
                transactionCount = currentExpenses.size,
                categoryBreakdown = categoryBreakdown,
                merchantBreakdown = merchantBreakdown,
                dailyTotals = dailyTotals,
                insights = insights,
                recurring = recurring,
                isLoading = false
            )
        }
    }
    private fun getPeriodRange(period: TimePeriod, now: Long): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = now
        return when (period) {
            TimePeriod.TODAY -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                Pair(cal.timeInMillis, now)
            }
            TimePeriod.WEEK -> {
                cal.add(Calendar.DAY_OF_YEAR, -7)
                Pair(cal.timeInMillis, now)
            }
            TimePeriod.MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                Pair(cal.timeInMillis, now)
            }
            TimePeriod.YEAR -> {
                cal.set(Calendar.DAY_OF_YEAR, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                Pair(cal.timeInMillis, now)
            }
            TimePeriod.ALL -> {
                Pair(0L, now)
            }
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\budget\BudgetScreen.kt <a name="mainjavacomyournameexpensetrackeruiscreensbudgetbudgetscreenkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.budget
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.database.entity.BudgetPeriod
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.budget.BudgetSuggestion
import java.text.SimpleDateFormat
import java.util.*
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BudgetScreen(
    viewModel: BudgetViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val categories by viewModel.categories.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingBudget by remember { mutableStateOf<BudgetStatus?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Budgets") },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Default.Add, contentDescription = "Add Budget")
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item { BudgetSummaryCard(uiState.budgets) }
                if (uiState.suggestions.isNotEmpty()) {
                    item { SuggestionsBanner(uiState.suggestions, categories, onAdd = { viewModel.addBudget(it) }) }
                }
                if (uiState.budgets.isEmpty()) {
                    item { EmptyBudgetsState { showAddDialog = true } }
                } else {
                    items(uiState.budgets) { budgetStatus ->
                        BudgetCard(
                            status = budgetStatus,
                            onEdit = { editingBudget = budgetStatus },
                            onToggle = { isActive -> viewModel.toggleBudget(budgetStatus.budget.id, isActive) },
                            onDelete = { viewModel.deleteBudget(it) }
                        )
                    }
                }
                item { Spacer(modifier = Modifier.height(80.dp)) }
            }
        }
        if (showAddDialog) {
            AddEditBudgetDialog(
                onDismiss = { showAddDialog = false },
                onConfirm = { viewModel.addBudget(it) },
                categories = categories
            )
        }
        editingBudget?.let { status ->
            AddEditBudgetDialog(
                initialBudget = status.budget,
                onDismiss = { editingBudget = null },
                onConfirm = { viewModel.updateBudget(it) },
                categories = categories
            )
        }
    }
}
@Composable
fun BudgetSummaryCard(budgets: List<BudgetStatus>) {
    val onTrack = budgets.count { it.healthStatus == BudgetHealthStatus.ON_TRACK }
    val warning = budgets.count { it.healthStatus == BudgetHealthStatus.WARNING || it.healthStatus == BudgetHealthStatus.CRITICAL }
    val exceeded = budgets.count { it.healthStatus == BudgetHealthStatus.EXCEEDED }
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            SummaryItem("On Track", onTrack, Color(0xFF4CAF50))
            SummaryItem("Warning", warning, Color(0xFFFFC107))
            SummaryItem("Exceeded", exceeded, Color(0xFFFF5722))
        }
    }
}
@Composable
fun SummaryItem(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = count.toString(),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
        Text(text = label, style = MaterialTheme.typography.labelMedium)
    }
}
@Composable
fun BudgetCard(
    status: BudgetStatus,
    onEdit: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: (Budget) -> Unit
) {
    val progressColor = when (status.healthStatus) {
        BudgetHealthStatus.ON_TRACK -> Color(0xFF4CAF50)
        BudgetHealthStatus.WARNING -> Color(0xFFFFC107)
        BudgetHealthStatus.CRITICAL -> Color(0xFFFF9800)
        BudgetHealthStatus.EXCEEDED -> Color(0xFFFF5722)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onEdit
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    status.category?.icon ?: "💰",
                    fontSize = 24.sp
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        status.category?.name ?: "Overall Budget",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Text(
                        "${status.budget.period.name.lowercase().capitalize()} • Starts ${formatDate(status.budget.startDate)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = status.budget.isActive,
                    onCheckedChange = onToggle,
                    modifier = Modifier.budgetScale(0.8f)
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "€${"%.2f".format(status.spentAmount)} spent",
                    fontWeight = FontWeight.Medium,
                    fontSize = 14.sp
                )
                Text(
                    "€${"%.2f".format(status.budget.amount)} limit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { status.percentUsed.coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                color = progressColor,
                trackColor = progressColor.copy(alpha = 0.2f)
            )
            if (status.percentUsed > 1f) {
                Text(
                    "€${"%.2f".format(status.spentAmount - status.budget.amount)} over budget",
                    color = Color(0xFFFF5722),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 4.dp)
                )
            } else {
                Text(
                    "€${"%.2f".format(status.remainingAmount)} remaining",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}
@Composable
fun SuggestionsBanner(
    suggestions: List<BudgetSuggestion>,
    categories: List<Category>,
    onAdd: (Budget) -> Unit
) {
    var currentIndex by remember { mutableIntStateOf(0) }
    val suggestion = suggestions.getOrNull(currentIndex) ?: return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Smart Suggestion", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "You spend a lot on ${suggestion.categoryName}. How about a monthly budget of €${"%.0f".format(suggestion.suggestedAmount)}?",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = { if (currentIndex < suggestions.size - 1) currentIndex++ else currentIndex = 0 }) {
                    Text("Skip")
                }
                Button(onClick = {
                    onAdd(Budget(
                        categoryId = suggestion.categoryId,
                        amount = suggestion.suggestedAmount,
                        period = BudgetPeriod.MONTHLY,
                        startDate = System.currentTimeMillis()
                    ))
                }) {
                    Text("Create Budget")
                }
            }
        }
    }
}
@Composable
fun EmptyBudgetsState(onAdd: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(48.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("No budgets set yet", style = MaterialTheme.typography.titleMedium)
        Text(
            "Track your spending by category to save more money.",
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(vertical = 8.dp)
        )
        Button(onClick = onAdd) {
            Text("Set Your First Budget")
        }
    }
}
@Composable
fun AddEditBudgetDialog(
    initialBudget: Budget? = null,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onConfirm: (Budget) -> Unit
) {
    var amount by remember { mutableStateOf(initialBudget?.amount?.toString() ?: "") }
    var selectedCategory by remember { mutableStateOf(initialBudget?.categoryId) }
    var period by remember { mutableStateOf(initialBudget?.period ?: BudgetPeriod.MONTHLY) }
    var rollover by remember { mutableStateOf(initialBudget?.rollover ?: false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialBudget == null) "Create Budget" else "Edit Budget") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Budget Amount (€)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                )
                Text("Category", style = MaterialTheme.typography.labelMedium)
                CategorySelector(
                    categories = categories,
                    selectedId = selectedCategory,
                    onSelect = { selectedCategory = it }
                )
                Text("Period", style = MaterialTheme.typography.labelMedium)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    BudgetPeriod.values().forEach { p ->
                        FilterChip(
                            selected = period == p,
                            onClick = { period = p },
                            label = { Text(p.name.lowercase().capitalize(), fontSize = 12.sp) }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = rollover, onCheckedChange = { rollover = it })
                    Text("Rollover unspent amount", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val amt = amount.toDoubleOrNull() ?: 0.0
                    if (amt > 0) {
                        val budgetToSave = initialBudget?.copy(
                            categoryId = selectedCategory,
                            amount = amt,
                            period = period,
                            rollover = rollover
                        ) ?: Budget(
                            categoryId = selectedCategory,
                            amount = amt,
                            period = period,
                            startDate = System.currentTimeMillis(),
                            rollover = rollover
                        )
                        onConfirm(budgetToSave)
                        onDismiss()
                    }
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategorySelector(
    categories: List<Category>,
    selectedId: Long?,
    onSelect: (Long?) -> Unit
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selectedId == null,
            onClick = { onSelect(null) },
            label = { Text("Overall") }
        )
        categories.forEach { category ->
            FilterChip(
                selected = selectedId == category.id,
                onClick = { onSelect(category.id) },
                label = { Text("${category.icon} ${category.name}") }
            )
        }
    }
}
private fun formatDate(ms: Long): String {
    return SimpleDateFormat("MMM dd", Locale.getDefault()).format(Date(ms))
}
// Extension to avoid repetitive logic
fun String.capitalize() = replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
// Helper for UI scaling using graphicsLayer for better performance
fun Modifier.budgetScale(scale: Float): Modifier = this.then(Modifier.graphicsLayer(scaleX = scale, scaleY = scale))

```

---

## main\java\com\yourname\expensetracker\ui\screens\budget\BudgetViewModel.kt <a name="mainjavacomyournameexpensetrackeruiscreensbudgetbudgetviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.budget
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Budget
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.budget.BudgetSuggestion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
data class BudgetUiState(
    val budgets: List<BudgetStatus> = emptyList(),
    val suggestions: List<BudgetSuggestion> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)
@HiltViewModel
class BudgetViewModel @Inject constructor(
    private val budgetRepository: BudgetRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {
    private val _uiState = MutableStateFlow(BudgetUiState(isLoading = true))
    val uiState: StateFlow<BudgetUiState> = _uiState.asStateFlow()
    val categories = categoryRepository.allCategories.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    init {
        loadData()
    }
    private fun loadData() {
        viewModelScope.launch {
            combine(
                budgetRepository.getBudgetStatuses(),
                flow { emit(budgetRepository.getSuggestions()) }
            ) { statuses, suggestions ->
                BudgetUiState(
                    budgets = statuses,
                    suggestions = suggestions,
                    isLoading = false
                )
            }.catch { e ->
                _uiState.emit(BudgetUiState(error = e.message, isLoading = false))
            }.collect {
                _uiState.emit(it)
            }
        }
    }
    fun addBudget(budget: Budget) {
        viewModelScope.launch {
            budgetRepository.addBudget(budget)
        }
    }
    fun updateBudget(budget: Budget) {
        viewModelScope.launch {
            budgetRepository.updateBudget(budget)
        }
    }
    fun deleteBudget(budget: Budget) {
        viewModelScope.launch {
            budgetRepository.deleteBudget(budget)
        }
    }
    fun toggleBudget(id: Long, isActive: Boolean) {
        viewModelScope.launch {
            budgetRepository.toggleBudget(id, isActive)
        }
    }
    fun refreshSuggestions() {
        viewModelScope.launch {
            val suggestions = budgetRepository.getSuggestions()
            _uiState.update { it.copy(suggestions = suggestions) }
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\categories\CategoryScreen.kt <a name="mainjavacomyournameexpensetrackeruiscreenscategoriescategoryscreenkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.categories
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.Category
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CategoryScreen(
    viewModel: CategoryViewModel = hiltViewModel()
) {
    val categories by viewModel.categories.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Manage Categories") })
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Category")
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(categories) { category ->
                CategoryItem(category)
            }
        }
        if (showAddDialog) {
            AddCategoryDialog(
                onDismiss = { showAddDialog = false },
                onAdd = { name, icon, color ->
                    viewModel.addCategory(name, icon, color)
                    showAddDialog = false
                }
            )
        }
    }
}
@Composable
fun CategoryItem(category: Category) {
    Card(
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val color = remember(category.color) {
                try {
                    Color(android.graphics.Color.parseColor(category.color))
                } catch (e: Exception) {
                    Color.Gray
                }
            }
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(color, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(category.icon, style = MaterialTheme.typography.titleMedium)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Text(category.name, style = MaterialTheme.typography.bodyLarge)
            if (category.isDefault) {
                Spacer(modifier = Modifier.weight(1f))
                Text("Default", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
            }
        }
    }
}
@Composable
fun AddCategoryDialog(
    onDismiss: () -> Unit,
    onAdd: (String, String, String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var icon by remember { mutableStateOf("📦") } // Default icon
    var color by remember { mutableStateOf("#607D8B") } // Default color
    var isNameError by remember { mutableStateOf(false) }
    // Simple list of preset icons/colors could be added here for better UX
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Category") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { 
                        name = it
                        if (it.isNotBlank()) isNameError = false
                    },
                    label = { Text("Name") },
                    isError = isNameError,
                    supportingText = { if (isNameError) Text("Name cannot be empty") },
                    singleLine = true
                )
                // In a real app, use a proper picker. For now, text fields or presets.
                OutlinedTextField(
                    value = icon,
                    onValueChange = { if (it.length <= 2) icon = it }, // Limit to emoji size
                    label = { Text("Icon (Emoji)") },
                    singleLine = true
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { 
                    if (name.isNotBlank()) {
                        onAdd(name, icon, color)
                    } else {
                        isNameError = true
                    }
                }
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\categories\CategoryViewModel.kt <a name="mainjavacomyournameexpensetrackeruiscreenscategoriescategoryviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.categories
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.repository.CategoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
@HiltViewModel
class CategoryViewModel @Inject constructor(
    private val repository: CategoryRepository
) : ViewModel() {
    val categories: StateFlow<List<Category>> = repository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    init {
        // Seed default categories on first run
        viewModelScope.launch {
            repository.ensureDefaultCategories()
        }
    }
    fun addCategory(name: String, icon: String, color: String) {
        viewModelScope.launch {
            repository.addCategory(name, icon, color)
        }
    }
    // Future: delete, edit
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\debug\DebugScreen.kt <a name="mainjavacomyournameexpensetrackeruiscreensdebugdebugscreenkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.debug
import android.content.Intent
import android.provider.Settings
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.data.database.entity.SourceStats
import com.yourname.expensetracker.domain.intelligence.ClassifierStats
import java.text.SimpleDateFormat
import java.util.*
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DebugScreen(
    viewModel: DebugViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val notifications by viewModel.filteredNotifications.collectAsState()
    val count by viewModel.notificationCount.collectAsState()
    val packages by viewModel.packages.collectAsState()
    val selectedFilter by viewModel.selectedPackageFilter.collectAsState()
    var expandedNotificationId by remember { mutableStateOf<Long?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Debug: Notifications ($count)") },
                actions = {
                    IconButton(onClick = { viewModel.clearAll() }) {
                        Icon(Icons.Default.Delete, contentDescription = "Clear all")
                    }
                }
            )
        }
    ) { padding ->
        // Root list for the entire screen to ensure scrolling
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            // 1. Permission Button
            item {
                Button(
                    onClick = {
                        context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text("Open Notification Access Settings")
                }
            }
            // 2. Mass Simulation Section
            item {
                val isSimulating by viewModel.isSimulating.collectAsState()
                var simulationCount by remember { mutableStateOf(50f) }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "🧪 Mass Simulation",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Quantity: ${simulationCount.toInt()}")
                        Slider(
                            value = simulationCount,
                            onValueChange = { simulationCount = it },
                            valueRange = 10f..500f,
                            steps = 9
                        )
                        Button(
                            onClick = { viewModel.simulateMassData(simulationCount.toInt()) },
                            enabled = !isSimulating,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (isSimulating) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(24.dp),
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            } else {
                                Text("Generate ${simulationCount.toInt()} Transactions")
                            }
                        }
                    }
                }
            }
            // 3. Test & Sync Buttons
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Button(
                        onClick = { viewModel.simulateTestNotification() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.tertiary
                        )
                    ) {
                        Text("Simulate Single Purchase (€12.50)")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.triggerManualSync(context) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.secondary
                        )
                    ) {
                        Text("Sync Active Notifications")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.resetExpenses() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Text("Reset All Expenses")
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = { viewModel.resetBudgets() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                        )
                    ) {
                        Text("Reset All Budgets")
                    }
                }
            }
            // 4. ML Stats
            item {
                val classifierStats by viewModel.classifierStats.collectAsState()
                val sourceStatsList by viewModel.sourceStats.collectAsState()
                Spacer(modifier = Modifier.height(16.dp))
                MlStatsSection(
                    classifierStats = classifierStats,
                    sourceStats = sourceStatsList,
                    onRetrain = { viewModel.retrainClassifier() }
                )
            }
            // 5. Filters
            item {
                if (packages.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        item {
                            FilterChip(
                                selected = selectedFilter == null,
                                onClick = { viewModel.setPackageFilter(null) },
                                label = { Text("All") }
                            )
                        }
                        items(packages) { pkg ->
                            FilterChip(
                                selected = selectedFilter == pkg,
                                onClick = { viewModel.setPackageFilter(pkg) },
                                label = { 
                                    Text(
                                        pkg.split(".").lastOrNull() ?: pkg,
                                        maxLines = 1
                                    ) 
                                }
                            )
                        }
                    }
                }
            }
            // 6. Blocked Apps
            item {
                val blockedApps by viewModel.blockedPackages.collectAsState()
                if (blockedApps.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Blocked Apps:",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.error
                    )
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(blockedApps) { blocked ->
                            InputChip(
                                selected = true,
                                onClick = { viewModel.unblockPackage(blocked.packageName) },
                                label = { 
                                    Text(
                                        blocked.packageName.split(".").lastOrNull() ?: blocked.packageName,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error
                                    ) 
                                },
                                trailingIcon = {
                                    Icon(
                                        Icons.Default.Close,
                                        contentDescription = "Unblock",
                                        modifier = Modifier.size(16.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                },
                                colors = InputChipDefaults.inputChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.2f)
                                )
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                }
            }
            // 7. Notification List
            if (notifications.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("No notifications captured yet")
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Make sure notification access is enabled",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            } else {
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "Captured Notifications (${notifications.size})",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outline
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                items(notifications, key = { it.id }) { notification ->
                    Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        NotificationCard(
                            notification = notification,
                            isExpanded = expandedNotificationId == notification.id,
                            onClick = {
                                expandedNotificationId = 
                                    if (expandedNotificationId == notification.id) null 
                                    else notification.id
                            },
                            onMarkRelevant = { viewModel.markAsRelevant(notification.id, true) },
                            onMarkIrrelevant = { viewModel.markAsRelevant(notification.id, false) },
                            onBlockPackage = { viewModel.blockPackage(notification.packageName) }
                        )
                    }
                }
            }
        }
    }
}
@Composable
fun NotificationCard(
    notification: RawNotification,
    isExpanded: Boolean,
    onClick: () -> Unit,
    onMarkRelevant: () -> Unit,
    onMarkIrrelevant: () -> Unit,
    onBlockPackage: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("HH:mm:ss dd/MM", Locale.getDefault()) }
    val relevanceColor = when (notification.isRelevant) {
        true -> Color(0xFF4CAF50).copy(alpha = 0.1f)
        false -> Color(0xFFF44336).copy(alpha = 0.1f)
        null -> Color.Transparent
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .background(relevanceColor)
                .padding(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = notification.appName ?: notification.packageName.split(".").last(),
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = dateFormat.format(Date(notification.capturedAt)),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            // Title
            notification.title?.let {
                Text(
                    text = it,
                    fontWeight = FontWeight.Medium,
                    fontSize = 13.sp,
                    maxLines = if (isExpanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            // Text
            val displayText = notification.bigText ?: notification.text
            displayText?.let {
                Text(
                    text = it,
                    fontSize = 12.sp,
                    maxLines = if (isExpanded) Int.MAX_VALUE else 2,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // Expanded content
            if (isExpanded) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                // Package name
                Text(
                    text = "Package: ${notification.packageName}",
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.outline
                )
                // SubText if present
                notification.subText?.let {
                    Text(
                        text = "SubText: $it",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                // Extras JSON
                notification.extrasJson?.let {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Extras:",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = it,
                        fontSize = 9.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
                // Action buttons
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    AssistChip(
                        onClick = onMarkRelevant,
                        label = { Text("Expense ✓", fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Check, null, Modifier.size(16.dp))
                        }
                    )
                    AssistChip(
                        onClick = onMarkIrrelevant,
                        label = { Text("Ignore ✗", fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(Icons.Default.Close, null, Modifier.size(16.dp))
                        }
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    AssistChip(
                        onClick = onBlockPackage,
                        label = { Text("Block App", fontSize = 11.sp) },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            labelColor = MaterialTheme.colorScheme.onErrorContainer
                        ),
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete, 
                                null, 
                                Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    )
                }
            }
        }
    }
}
@Composable
fun MlStatsSection(
    classifierStats: com.yourname.expensetracker.domain.intelligence.ClassifierStats,
    sourceStats: List<com.yourname.expensetracker.data.database.entity.SourceStats>,
    onRetrain: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f)
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "🧠 ML Classifier",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        "Status: ${if (classifierStats.isReady) "✅ Active" else "⏳ Training"}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Positive samples: ${classifierStats.totalPositive}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Negative samples: ${classifierStats.totalNegative}",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "Vocabulary: ${classifierStats.vocabularySize} words",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                OutlinedButton(
                    onClick = onRetrain,
                    enabled = classifierStats.totalPositive + classifierStats.totalNegative >= 20
                ) {
                    Text("Retrain", fontSize = 12.sp)
                }
            }
            // Source trust scores
            if (sourceStats.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "📊 Source Trust Scores",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                sourceStats.take(5).forEach { stats ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = stats.packageName.split(".").lastOrNull() ?: stats.packageName,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "${stats.acceptedAsExpense}/${stats.totalNotifications}",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        val trustColor = when {
                            stats.trustScore > 0.7f -> Color(0xFF4CAF50)
                            stats.trustScore > 0.3f -> Color(0xFFFFC107)
                            else -> Color(0xFFFF5722)
                        }
                        Text(
                            text = "${(stats.trustScore * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            color = trustColor
                        )
                    }
                }
            }
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\debug\DebugViewModel.kt <a name="mainjavacomyournameexpensetrackeruiscreensdebugdebugviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.debug
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.data.database.entity.SourceStats
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.domain.intelligence.ClassifierStats
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
@HiltViewModel
class DebugViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val budgetRepository: com.yourname.expensetracker.data.repository.BudgetRepository
) : ViewModel() {
    val notifications: StateFlow<List<RawNotification>> = repository
        .getRecentNotifications(200)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val notificationCount: StateFlow<Int> = repository
        .getCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val packages: StateFlow<List<String>> = repository
        .getAllPackages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val blockedPackages: StateFlow<List<com.yourname.expensetracker.data.database.entity.BlockedPackage>> = repository
        .getBlockedPackages()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val totalSpent: StateFlow<Double> = repository
        .getTotalSpent()
        .map { it ?: 0.0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0.0)
    val sourceStats: StateFlow<List<com.yourname.expensetracker.data.database.entity.SourceStats>> = repository
        .getSourceStats()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val _classifierStats = MutableStateFlow(repository.getClassifierStats())
    val classifierStats: StateFlow<com.yourname.expensetracker.domain.intelligence.ClassifierStats> = _classifierStats
    private val _selectedPackageFilter = MutableStateFlow<String?>(null)
    val selectedPackageFilter: StateFlow<String?> = _selectedPackageFilter
    val filteredNotifications: StateFlow<List<RawNotification>> = combine(
        notifications,
        _selectedPackageFilter
    ) { notifs, filter ->
        if (filter == null) notifs
        else notifs.filter { it.packageName == filter }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun setPackageFilter(packageName: String?) {
        _selectedPackageFilter.value = packageName
    }
    fun clearAll() {
        viewModelScope.launch {
            repository.deleteAll()
        }
    }
    fun resetExpenses() {
        viewModelScope.launch {
            repository.deleteAllExpenses()
        }
    }
    fun resetBudgets() {
        viewModelScope.launch {
            budgetRepository.deleteAll()
        }
    }
    fun markAsRelevant(id: Long, isRelevant: Boolean) {
        viewModelScope.launch {
            repository.markAsRelevant(id, isRelevant)
        }
    }
    fun blockPackage(packageName: String) {
        viewModelScope.launch {
            repository.blockPackage(packageName)
            // Also refresh filter if needed, but Flow should handle it
        }
    }
    fun unblockPackage(packageName: String) {
        viewModelScope.launch {
            repository.unblockPackage(packageName)
        }
    }
    fun retrainClassifier() {
        viewModelScope.launch {
            repository.retrainClassifier()
            _classifierStats.value = repository.getClassifierStats()
        }
    }
    @Inject
    lateinit var seeder: com.yourname.expensetracker.domain.debug.NotificationSeeder
    private val _isSimulating = MutableStateFlow(false)
    val isSimulating: StateFlow<Boolean> = _isSimulating
    fun simulateMassData(count: Int) {
        viewModelScope.launch {
            _isSimulating.value = true
            val simulated = seeder.generate(count)
            repository.processAndSaveAll(simulated)
            _isSimulating.value = false
        }
    }
    fun simulateTestNotification() {
        viewModelScope.launch {
            val fakeNotification = RawNotification(
                packageName = "com.test.bank",
                appName = "Test Bank",
                title = "Purchase Alert",
                text = "You paid €12.50 at Amazon",
                timestamp = System.currentTimeMillis(),
                capturedAt = System.currentTimeMillis()
            )
            repository.processAndSave(fakeNotification)
        }
    }
    fun triggerManualSync(context: android.content.Context) {
        val intent = android.content.Intent(context, com.yourname.expensetracker.service.NotificationCaptureService::class.java).apply {
            action = com.yourname.expensetracker.service.NotificationCaptureService.ACTION_REFRESH_NOTIFICATIONS
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\home\HomeScreen.kt <a name="mainjavacomyournameexpensetrackeruiscreenshomehomescreenkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.home
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import com.yourname.expensetracker.ui.screens.receiptscan.ReceiptScanScreen
import java.text.SimpleDateFormat
import java.util.*
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.dashboard.collectAsState()
    var showAddExpense by remember { mutableStateOf(false) }
    var showScanReceipt by remember { mutableStateOf(false) }
    Scaffold(
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
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Total Spent Card
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Total Spent",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        Text(
                            "€${String.format("%.2f", state.totalSpent)}",
                            style = MaterialTheme.typography.displayMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "${state.transactionCount} transactions",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
                        )
                    }
                }
            }
            // Time Period Cards
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    PeriodCard("Today", state.todaySpent, Modifier.weight(1f))
                    PeriodCard("This Week", state.weekSpent, Modifier.weight(1f))
                    PeriodCard("This Month", state.monthSpent, Modifier.weight(1f))
                }
            }
            // Budget Summary Widget
            if (state.budgetStatuses.isNotEmpty()) {
                item {
                    BudgetSummaryWidget(
                        onTrack = state.budgetStatuses.count { it.healthStatus == BudgetHealthStatus.ON_TRACK },
                        warning = state.budgetStatuses.count { it.healthStatus == BudgetHealthStatus.WARNING || it.healthStatus == BudgetHealthStatus.CRITICAL },
                        exceeded = state.budgetStatuses.count { it.healthStatus == BudgetHealthStatus.EXCEEDED },
                        summary = state.budgetSummary
                    )
                }
            }
            // Top Categories
            if (state.topCategories.isNotEmpty()) {
                item {
                    Text(
                        "Top Categories",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(state.topCategories) { catSpending ->
                    CategorySpendingRow(catSpending)
                }
            }
            // Recent Transactions
            if (state.recentExpenses.isNotEmpty()) {
                item {
                    Text(
                        "Recent",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                items(state.recentExpenses) { expense ->
                    RecentExpenseRow(expense)
                }
            }
        }
        if (showAddExpense) {
            com.yourname.expensetracker.ui.screens.addexpense.AddExpenseSheet(
                onDismiss = { showAddExpense = false }
            )
        }
        if (showScanReceipt) {
            ReceiptScanScreen(
                onDismiss = { showScanReceipt = false }
            )
        }
    }
}
@Composable
fun PeriodCard(label: String, amount: Double, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(label, style = MaterialTheme.typography.labelSmall)
            Text(
                "€${String.format("%.2f", amount)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
@Composable
fun CategorySpendingRow(item: CategorySpending) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val categoryColor = remember(item.category.color) {
            try { Color(android.graphics.Color.parseColor(item.category.color)) } 
            catch (e: Exception) { Color.Gray }
        }
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(categoryColor, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(item.category.icon, fontSize = 18.sp)
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(item.category.name, style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(
                progress = { item.percentage / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = categoryColor,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                "€${String.format("%.2f", item.total)}",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                "${String.format("%.0f", item.percentage)}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
@Composable
fun RecentExpenseRow(expense: Expense) {
    val dateFormat = remember { SimpleDateFormat("MMM dd", Locale.getDefault()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(expense.merchant, style = MaterialTheme.typography.bodyMedium)
                if (expense.isManualEntry) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("✏️", fontSize = 12.sp)
                }
            }
            Text(
                dateFormat.format(Date(expense.date)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            "€${String.format("%.2f", expense.amount)}",
            fontWeight = FontWeight.Bold
        )
    }
}
@Composable
fun BudgetSummaryWidget(onTrack: Int, warning: Int, exceeded: Int, summary: String?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Budget Status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(12.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                StatusBadge("On Track", onTrack, Color(0xFF4CAF50))
                StatusBadge("Warning", warning, Color(0xFFFFC107))
                StatusBadge("Exceeded", exceeded, Color(0xFFFF5722))
            }
            if (summary != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (exceeded > 0) Color(0xFFFF5722) else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
@Composable
fun StatusBadge(label: String, count: Int, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(32.dp).clip(CircleShape).background(color.copy(alpha = 0.1f)),
            contentAlignment = Alignment.Center
        ) {
            Text(count.toString(), fontWeight = FontWeight.Bold, color = color)
        }
        Text(label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\home\HomeViewModel.kt <a name="mainjavacomyournameexpensetrackeruiscreenshomehomeviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.home
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import com.yourname.expensetracker.data.repository.BudgetRepository
import com.yourname.expensetracker.domain.budget.BudgetStatus
import com.yourname.expensetracker.domain.budget.BudgetHealthStatus
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import javax.inject.Inject
import java.util.Calendar
data class CategorySpending(
    val category: Category,
    val total: Double,
    val percentage: Float
)
data class DashboardState(
    val totalSpent: Double = 0.0,
    val todaySpent: Double = 0.0,
    val weekSpent: Double = 0.0,
    val monthSpent: Double = 0.0,
    val transactionCount: Int = 0,
    val topCategories: List<CategorySpending> = emptyList(),
    val recentExpenses: List<Expense> = emptyList(),
    val budgetStatuses: List<BudgetStatus> = emptyList(),
    val budgetSummary: String? = null
)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val categoryRepository: CategoryRepository,
    private val budgetRepository: BudgetRepository
) : ViewModel() {
    val dashboard: StateFlow<DashboardState> = combine(
        repository.getAllExpenses(),
        categoryRepository.allCategories,
        budgetRepository.getBudgetStatuses()
    ) { expenses, categories, budgetStatuses ->
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        // Reset to start of today
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val todayStart = cal.timeInMillis
        // Start of week (Monday)
        val tempCal = cal.clone() as Calendar
        tempCal.firstDayOfWeek = Calendar.MONDAY
        tempCal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
        if (tempCal.timeInMillis > todayStart) {
            tempCal.add(Calendar.DAY_OF_YEAR, -7)
        }
        val weekStart = tempCal.timeInMillis
        // Start of month
        cal.set(Calendar.DAY_OF_MONTH, 1)
        val monthStart = cal.timeInMillis
        val purchases = expenses.filter { 
            it.transactionType == com.yourname.expensetracker.data.database.entity.TransactionType.PURCHASE 
        }
        val categoryMap = categories.associateBy { it.id }
        val totalSpent = purchases.sumOf { it.amount }
        val categoryTotals = purchases
            .groupBy { it.categoryId }
            .mapNotNull { (catId, exps) ->
                val cat = catId?.let { categoryMap[it] } ?: return@mapNotNull null
                val catTotal = exps.sumOf { it.amount }
                CategorySpending(
                    category = cat,
                    total = catTotal,
                    percentage = if (totalSpent > 0) (catTotal / totalSpent * 100).toFloat() else 0f
                )
            }
            .sortedByDescending { it.total }
        val topCategories = categoryTotals.take(5)
        val budgetSummary = if (budgetStatuses.isNotEmpty()) {
            val exceeded = budgetStatuses.count { it.healthStatus == BudgetHealthStatus.EXCEEDED }
            if (exceeded > 0) "$exceeded budgets exceeded!" else "All budgets on track."
        } else null
        DashboardState(
            totalSpent = totalSpent,
            todaySpent = purchases.filter { it.date >= todayStart }.sumOf { it.amount },
            weekSpent = purchases.filter { it.date >= weekStart }.sumOf { it.amount },
            monthSpent = purchases.filter { it.date >= monthStart }.sumOf { it.amount },
            transactionCount = purchases.size,
            topCategories = topCategories,
            recentExpenses = purchases.take(5),
            budgetStatuses = budgetStatuses,
            budgetSummary = budgetSummary
        )
    }.debounce(300)
    .flowOn(Dispatchers.Default)
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardState())
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\receiptscan\ReceiptScanScreen.kt <a name="mainjavacomyournameexpensetrackeruiscreensreceiptscanreceiptscanscreenkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.receiptscan
import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import com.yourname.expensetracker.ui.screens.addexpense.CategoryGrid
import com.yourname.expensetracker.ui.screens.addexpense.DateSelector
import com.yourname.expensetracker.ui.screens.addexpense.PaymentMethodChip
import kotlinx.coroutines.delay
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

---

## main\java\com\yourname\expensetracker\ui\screens\receiptscan\ReceiptScanViewModel.kt <a name="mainjavacomyournameexpensetrackeruiscreensreceiptscanreceiptscanviewmodelkt"></a>
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
    data object Success : SaveReceiptResult()
    data object Duplicate : SaveReceiptResult()
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

---

## main\java\com\yourname\expensetracker\ui\screens\review\ReviewScreen.kt <a name="mainjavacomyournameexpensetrackeruiscreensreviewreviewscreenkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.review
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.foundation.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PendingReview
import java.text.SimpleDateFormat
import java.util.*
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReviewScreen(
    viewModel: ReviewViewModel = hiltViewModel()
) {
    val pendingReviews by viewModel.pendingReviews.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val pendingCount by viewModel.pendingCount.collectAsState()
    var editingReview by remember { mutableStateOf<PendingReview?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessage by viewModel.errorMessage.collectAsState()
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Review Queue ($pendingCount)") }
            )
        }
    ) { padding ->
        if (pendingReviews.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("✅", fontSize = 48.sp)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        "All caught up!",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "No transactions need your review",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        "Swipe through to approve or reject",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                }
                items(pendingReviews, key = { it.id }) { review ->
                    ReviewCard(
                        review = review,
                        onApprove = { viewModel.approveReview(review.id) },
                        onReject = { viewModel.rejectReview(review.id) },
                        onEdit = { editingReview = review }
                    )
                }
            }
        }
        // Edit dialog
        if (editingReview != null) {
            EditReviewDialog(
                review = editingReview!!,
                categories = categories,
                onDismiss = { editingReview = null },
                onSave = { amount, merchant, categoryId ->
                    viewModel.approveReviewWithEdits(
                        reviewId = editingReview!!.id,
                        finalAmount = amount,
                        finalMerchant = merchant,
                        finalCategoryId = categoryId
                    )
                    editingReview = null
                }
            )
        }
    }
}
@Composable
fun ReviewCard(
    review: PendingReview,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    onEdit: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }
    val confidenceColor = when {
        review.confidence >= 0.75f -> Color(0xFF4CAF50)
        review.confidence >= 0.60f -> Color(0xFFFFC107)
        else -> Color(0xFFFF5722)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Confidence indicator bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = review.packageName.split(".").lastOrNull() ?: review.packageName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(confidenceColor, RoundedCornerShape(4.dp))
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${(review.confidence * 100).toInt()}% sure",
                        style = MaterialTheme.typography.labelSmall,
                        color = confidenceColor
                    )
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            // Transaction details
            Text(
                text = review.suggestedMerchant,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = dateFormat.format(Date(review.createdAt)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Amount
            Text(
                text = "${review.suggestedCurrency} ${String.format("%.2f", review.suggestedAmount)}",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error
            )
            // Original notification preview
            review.notificationTitle?.let { title ->
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = title,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium
                        )
                        review.notificationText?.let { text ->
                            Text(
                                text = text,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Reject
                OutlinedButton(
                    onClick = onReject,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Icon(Icons.Default.Close, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Reject")
                }
                // Edit
                OutlinedButton(
                    onClick = onEdit,
                    modifier = Modifier.weight(0.5f)
                ) {
                    Icon(Icons.Default.Edit, null, modifier = Modifier.size(18.dp))
                }
                // Approve
                Button(
                    onClick = onApprove,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF4CAF50)
                    )
                ) {
                    Icon(Icons.Default.Check, null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Approve")
                }
            }
        }
    }
}
@Composable
fun EditReviewDialog(
    review: PendingReview,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onSave: (Double?, String?, Long?) -> Unit
) {
    var amount by remember { mutableStateOf(String.format("%.2f", review.suggestedAmount)) }
    var merchant by remember { mutableStateOf(review.suggestedMerchant) }
    var selectedCategoryId by remember { mutableStateOf(review.suggestedCategoryId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Transaction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = merchant,
                    onValueChange = { merchant = it },
                    label = { Text("Merchant") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it },
                    label = { Text("Amount (${review.suggestedCurrency})") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Category",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )
                val scrollState = rememberScrollState()
                Column(
                    modifier = Modifier
                        .heightIn(max = 200.dp)
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    categories.forEach { category ->
                        Surface(
                            onClick = { selectedCategoryId = category.id },
                            shape = RoundedCornerShape(8.dp),
                            color = if (selectedCategoryId == category.id)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(category.icon)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(category.name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val parsedAmount = amount.toDoubleOrNull()
                    val editedAmount = if (parsedAmount != null && kotlin.math.abs(parsedAmount - review.suggestedAmount) > 0.001) parsedAmount else null
                    val editedMerchant = merchant.takeIf { it != review.suggestedMerchant }
                    val editedCategory = selectedCategoryId.takeIf { it != review.suggestedCategoryId }
                    onSave(editedAmount, editedMerchant, editedCategory)
                }
            ) {
                Text("Save & Approve")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\review\ReviewViewModel.kt <a name="mainjavacomyournameexpensetrackeruiscreensreviewreviewviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.review
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {
    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage = _errorMessage.asStateFlow()
    val pendingReviews: StateFlow<List<PendingReview>> = repository
        .getPendingReviews()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val pendingCount: StateFlow<Int> = repository
        .getPendingReviewCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    fun approveReview(reviewId: Long) {
        viewModelScope.launch {
            try {
                repository.approveReview(reviewId)
            } catch (e: Exception) {
                _errorMessage.value = "Failed to approve: ${e.message}"
            }
        }
    }
    fun rejectReview(reviewId: Long) {
        viewModelScope.launch {
            repository.rejectReview(reviewId)
        }
    }
    fun approveReviewWithEdits(
        reviewId: Long,
        finalAmount: Double?,
        finalMerchant: String?,
        finalCategoryId: Long?
    ) {
        viewModelScope.launch {
            try {
                repository.approveReview(
                    reviewId = reviewId,
                    finalAmount = finalAmount,
                    finalMerchant = finalMerchant,
                    finalCategoryId = finalCategoryId
                )
            } catch (e: Exception) {
                _errorMessage.value = "Failed to approve edits: ${e.message}"
            }
        }
    }
    fun clearError() {
        _errorMessage.value = null
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\transactions\TransactionsScreen.kt <a name="mainjavacomyournameexpensetrackeruiscreenstransactionstransactionsscreenkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.transactions
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import java.text.SimpleDateFormat
import java.util.*
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TransactionsScreen(
    viewModel: TransactionsViewModel = hiltViewModel()
) {
    val transactions by viewModel.transactions.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val dateFormat = remember { SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()) }
    var showAddExpense by remember { mutableStateOf(false) }
    var expenseToDelete by remember { mutableStateOf<Expense?>(null) }
    var expenseToCategorize by remember { mutableStateOf<Expense?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transactions") }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddExpense = true },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Default.Edit, contentDescription = "Add Expense")
            }
        }
    ) { padding ->
        if (transactions.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "No transactions found",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Parsed expenses will appear here",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(transactions, key = { it.expense.id }) { item ->
                    TransactionItem(
                        transaction = item,
                        dateStr = dateFormat.format(Date(item.expense.date)),
                        onDelete = { expenseToDelete = item.expense },
                        onEditCategory = { expenseToCategorize = item.expense }
                    )
                }
            }
        }
        // Add Expense Sheet
        if (showAddExpense) {
            com.yourname.expensetracker.ui.screens.addexpense.AddExpenseSheet(
                onDismiss = { showAddExpense = false }
            )
        }
        // ... Existing Dialogs ...
        if (expenseToDelete != null) {
            AlertDialog(
                onDismissRequest = { expenseToDelete = null },
                title = { Text("Delete Transaction") },
                text = { Text("Are you sure you want to delete this transaction from ${expenseToDelete?.merchant}?") },
                confirmButton = {
                    TextButton(
                        onClick = {
                            expenseToDelete?.let { viewModel.deleteExpense(it) }
                            expenseToDelete = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("Delete")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { expenseToDelete = null }) {
                        Text("Cancel")
                    }
                }
            )
        }
        // Category Selection Dialog
        if (expenseToCategorize != null) {
            CategoryPickerDialog(
                categories = categories,
                onDismiss = { expenseToCategorize = null },
                onCategorySelected = { categoryId ->
                    expenseToCategorize?.let { viewModel.updateCategory(it, categoryId) }
                    expenseToCategorize = null
                }
            )
        }
    }
}
@Composable
fun CategoryPickerDialog(
    categories: List<Category>,
    onDismiss: () -> Unit,
    onCategorySelected: (Long) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Category") },
        text = {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(categories) { category ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onCategorySelected(category.id) },
                        shape = MaterialTheme.shapes.medium,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(category.icon, modifier = Modifier.padding(end = 12.dp))
                            Text(category.name)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
@Composable
fun TransactionItem(
    transaction: ExpenseWithCategory,
    dateStr: String,
    onDelete: () -> Unit,
    onEditCategory: () -> Unit
) {
    val expense = transaction.expense
    val category = transaction.category
    // Optimize color parsing: remember the color based on the category's hex string
    val categoryColor = remember(category?.color) {
        try {
            category?.color?.let { Color(android.graphics.Color.parseColor(it)) } ?: Color.Gray
        } catch (e: Exception) {
            Color.Gray
        }
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Category Icon (Clickable to change)
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .background(
                        color = categoryColor,
                        shape = CircleShape
                    )
                    .clickable { onEditCategory() },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = category?.icon ?: "❓",
                    fontSize = 24.sp
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            // Info
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = expense.merchant,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        maxLines = 1,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (expense.isManualEntry) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("✏️", fontSize = 12.sp)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val methodIcon = when(expense.paymentMethod) {
                        com.yourname.expensetracker.data.database.entity.PaymentMethod.CASH -> "💵"
                        com.yourname.expensetracker.data.database.entity.PaymentMethod.BANK_TRANSFER -> "🏦"
                        com.yourname.expensetracker.data.database.entity.PaymentMethod.CARD -> "💳"
                        else -> ""
                    }
                    if (methodIcon.isNotEmpty()) {
                        Text(methodIcon, fontSize = 12.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    Text(
                        text = category?.name ?: "Uncategorized",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { onEditCategory() }
                    )
                }
                Text(
                    text = dateStr,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
            // Amount
            Text(
                text = "${String.format("%.2f", expense.amount)} ${expense.currency}",
                fontWeight = FontWeight.Black,
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 8.dp)
            )
            // Delete Action
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.6f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\screens\transactions\TransactionsViewModel.kt <a name="mainjavacomyournameexpensetrackeruiscreenstransactionstransactionsviewmodelkt"></a>
```kotlin
package com.yourname.expensetracker.ui.screens.transactions
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
data class ExpenseWithCategory(
    val expense: Expense,
    val category: Category?
)
@HiltViewModel
class TransactionsViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {
    val categories: StateFlow<List<Category>> = categoryRepository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val transactions: StateFlow<List<ExpenseWithCategory>> = combine(
        repository.getAllExpenses(),
        categoryRepository.allCategories
    ) { expenses, categories ->
        val categoryMap = categories.associateBy { it.id }
        expenses.map { expense ->
            ExpenseWithCategory(
                expense = expense,
                category = expense.categoryId?.let { categoryMap[it] }
            )
        }
    }.debounce(300)
    .flowOn(Dispatchers.Default)
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )
    fun deleteExpense(expense: Expense) {
        viewModelScope.launch {
            repository.deleteExpense(expense)
        }
    }
    fun updateCategory(expense: Expense, categoryId: Long) {
        viewModelScope.launch {
            repository.updateExpenseCategory(expense, categoryId)
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\ui\theme\Theme.kt <a name="mainjavacomyournameexpensetrackeruithemethemekt"></a>
```kotlin
package com.yourname.expensetracker.ui.theme
import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
private val DarkColorScheme = darkColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF6750A4),
    secondary = androidx.compose.ui.graphics.Color(0xFF625B71),
    tertiary = androidx.compose.ui.graphics.Color(0xFF7D5260)
)
private val LightColorScheme = lightColorScheme(
    primary = androidx.compose.ui.graphics.Color(0xFF6750A4),
    secondary = androidx.compose.ui.graphics.Color(0xFF625B71),
    tertiary = androidx.compose.ui.graphics.Color(0xFF7D5260)
)
@Composable
fun ExpenseTrackerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }
    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}

```

---

## main\res\drawable\ic_launcher_background.xml <a name="mainresdrawableic_launcher_backgroundxml"></a>
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#008577"
        android:pathData="M0,0h108v108h-108z" />
</vector>

```

---

## main\res\drawable\ic_launcher_foreground.xml <a name="mainresdrawableic_launcher_foregroundxml"></a>
```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M79,35h-9.92C68.42,32.49,67.6,30.33,66.6,28.62L74.02,21.2c0.78-0.78,0.78-2.05,0-2.83L69.2,13.55c-0.78-0.78-2.05-0.78-2.83,0L58.95,20.97c-1.71-1-3.87-1.82-6.38-2.48V8.42c0-1.1-0.9-2-2-2h-6.83c-1.1,0-2,0.9-2,2v10.07c-2.51,0.66-4.67,1.48-6.38,2.48L27.63,13.55c-0.78-0.78-2.05-0.78-2.83,0L19.98,18.37c-0.78,0.78-0.78,2.05,0,2.83l7.42,7.42c-1,1.71-1.82,3.87-2.48,6.38H14.85c-1.1,0-2,0.9-2,2v6.83c0,1.1,0.9,2,2,2h10.07c0.66,2.51,1.48,4.67,2.48,6.38L19.98,60.85c-0.78,0.78-0.78,2.05,0,2.83l4.82,4.82c0.78,0.78,2.05,0.78,2.83,0l7.42-7.42c1.71,1,3.87,1.82,6.38,2.48v10.07c0,1.1,0.9,2,2,2h6.83c1.1,0,2-0.9,2-2V73.15c2.51-0.66,4.67-1.48,6.38-2.48l7.42,7.42c0.78,0.78,2.05,0.78,2.83,0l4.82-4.82c0.78-0.78,0.78-2.05,0-2.83l-7.42-7.42c1-1.71,1.82-3.87,2.48-6.38h10.07c1.1,0,2-0.9,2-2V48.17c0-1.1-0.9-2-2-2H79z M54,52.17c-4.42,0-8-3.58-8-8s3.58-8,8-8s8,3.58,8,8S58.42,52.17,54,52.17z" />
</vector>

```

---

## main\res\mipmap-anydpi-v26\ic_launcher.xml <a name="mainresmipmap-anydpi-v26ic_launcherxml"></a>
```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>

```

---

## main\res\mipmap-anydpi-v26\ic_launcher_round.xml <a name="mainresmipmap-anydpi-v26ic_launcher_roundxml"></a>
```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>

```

---

## main\res\mipmap\ic_launcher.xml <a name="mainresmipmapic_launcherxml"></a>
```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>

```

---

## main\res\mipmap\ic_launcher_round.xml <a name="mainresmipmapic_launcher_roundxml"></a>
```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>

```

---

## main\res\values\strings.xml <a name="mainresvaluesstringsxml"></a>
```xml
<resources>
    <string name="app_name">ExpenseTracker</string>
</resources>

```

---

## main\res\values\themes.xml <a name="mainresvaluesthemesxml"></a>
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.ExpenseTracker" parent="Theme.Material3.DayNight.NoActionBar">
    </style>
</resources>

```

---

## main\res\xml\file_paths.xml <a name="mainresxmlfile_pathsxml"></a>
```xml
<?xml version="1.0" encoding="utf-8"?>
<paths>
    <cache-path name="receipt_images" path="receipt_images/" />
    <files-path name="receipts" path="receipts/" />
</paths>

```

---

