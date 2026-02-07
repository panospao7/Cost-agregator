# ExpenseTracker Full Source Code Extraction

This file contains the complete source code from the `src` directory.

## Table of Contents
1. [main\AndroidManifest.xml](#mainandroidmanifestxml)
2. [main\java\com\yourname\expensetracker\ExpenseTrackerApp.kt](#mainjavacomyournameexpensetrackerexpensetrackerappkt)
3. [main\java\com\yourname\expensetracker\data\database\AppDatabase.kt](#mainjavacomyournameexpensetrackerdatadatabaseappdatabasekt)
4. [main\java\com\yourname\expensetracker\data\database\converter\Converters.kt](#mainjavacomyournameexpensetrackerdatadatabaseconverterconverterskt)
5. [main\java\com\yourname\expensetracker\data\database\dao\BlockedPackageDao.kt](#mainjavacomyournameexpensetrackerdatadatabasedaoblockedpackagedaokt)
6. [main\java\com\yourname\expensetracker\data\database\dao\CategoryDao.kt](#mainjavacomyournameexpensetrackerdatadatabasedaocategorydaokt)
7. [main\java\com\yourname\expensetracker\data\database\dao\ExpenseDao.kt](#mainjavacomyournameexpensetrackerdatadatabasedaoexpensedaokt)
8. [main\java\com\yourname\expensetracker\data\database\dao\MerchantCategoryDao.kt](#mainjavacomyournameexpensetrackerdatadatabasedaomerchantcategorydaokt)
9. [main\java\com\yourname\expensetracker\data\database\dao\PendingReviewDao.kt](#mainjavacomyournameexpensetrackerdatadatabasedaopendingreviewdaokt)
10. [main\java\com\yourname\expensetracker\data\database\dao\RawNotificationDao.kt](#mainjavacomyournameexpensetrackerdatadatabasedaorawnotificationdaokt)
11. [main\java\com\yourname\expensetracker\data\database\dao\SourceStatsDao.kt](#mainjavacomyournameexpensetrackerdatadatabasedaosourcestatsdaokt)
12. [main\java\com\yourname\expensetracker\data\database\dao\UserCorrectionDao.kt](#mainjavacomyournameexpensetrackerdatadatabasedaousercorrectiondaokt)
13. [main\java\com\yourname\expensetracker\data\database\entity\BlockedPackage.kt](#mainjavacomyournameexpensetrackerdatadatabaseentityblockedpackagekt)
14. [main\java\com\yourname\expensetracker\data\database\entity\Category.kt](#mainjavacomyournameexpensetrackerdatadatabaseentitycategorykt)
15. [main\java\com\yourname\expensetracker\data\database\entity\Expense.kt](#mainjavacomyournameexpensetrackerdatadatabaseentityexpensekt)
16. [main\java\com\yourname\expensetracker\data\database\entity\MerchantCategory.kt](#mainjavacomyournameexpensetrackerdatadatabaseentitymerchantcategorykt)
17. [main\java\com\yourname\expensetracker\data\database\entity\PendingReview.kt](#mainjavacomyournameexpensetrackerdatadatabaseentitypendingreviewkt)
18. [main\java\com\yourname\expensetracker\data\database\entity\RawNotification.kt](#mainjavacomyournameexpensetrackerdatadatabaseentityrawnotificationkt)
19. [main\java\com\yourname\expensetracker\data\database\entity\SourceStats.kt](#mainjavacomyournameexpensetrackerdatadatabaseentitysourcestatskt)
20. [main\java\com\yourname\expensetracker\data\database\entity\UserCorrection.kt](#mainjavacomyournameexpensetrackerdatadatabaseentityusercorrectionkt)
21. [main\java\com\yourname\expensetracker\data\repository\CategoryRepository.kt](#mainjavacomyournameexpensetrackerdatarepositorycategoryrepositorykt)
22. [main\java\com\yourname\expensetracker\data\repository\NotificationRepository.kt](#mainjavacomyournameexpensetrackerdatarepositorynotificationrepositorykt)
23. [main\java\com\yourname\expensetracker\di\AppModule.kt](#mainjavacomyournameexpensetrackerdiappmodulekt)
24. [main\java\com\yourname\expensetracker\domain\analytics\AnalyticsModels.kt](#mainjavacomyournameexpensetrackerdomainanalyticsanalyticsmodelskt)
25. [main\java\com\yourname\expensetracker\domain\analytics\InsightsEngine.kt](#mainjavacomyournameexpensetrackerdomainanalyticsinsightsenginekt)
26. [main\java\com\yourname\expensetracker\domain\categorization\CategorizationEngine.kt](#mainjavacomyournameexpensetrackerdomaincategorizationcategorizationenginekt)
27. [main\java\com\yourname\expensetracker\domain\intelligence\ConfidenceRouter.kt](#mainjavacomyournameexpensetrackerdomainintelligenceconfidencerouterkt)
28. [main\java\com\yourname\expensetracker\domain\intelligence\MerchantNormalizer.kt](#mainjavacomyournameexpensetrackerdomainintelligencemerchantnormalizerkt)
29. [main\java\com\yourname\expensetracker\domain\intelligence\TransactionClassifier.kt](#mainjavacomyournameexpensetrackerdomainintelligencetransactionclassifierkt)
30. [main\java\com\yourname\expensetracker\domain\parser\AppParserRegistry.kt](#mainjavacomyournameexpensetrackerdomainparserappparserregistrykt)
31. [main\java\com\yourname\expensetracker\domain\parser\GenericTransactionParser.kt](#mainjavacomyournameexpensetrackerdomainparsergenerictransactionparserkt)
32. [main\java\com\yourname\expensetracker\domain\parser\parsers\GoogleWalletParser.kt](#mainjavacomyournameexpensetrackerdomainparserparsersgooglewalletparserkt)
33. [main\java\com\yourname\expensetracker\domain\parser\parsers\GreekBankParser.kt](#mainjavacomyournameexpensetrackerdomainparserparsersgreekbankparserkt)
34. [main\java\com\yourname\expensetracker\domain\parser\parsers\RevolutParser.kt](#mainjavacomyournameexpensetrackerdomainparserparsersrevolutparserkt)
35. [main\java\com\yourname\expensetracker\domain\parser\parsers\SmsParser.kt](#mainjavacomyournameexpensetrackerdomainparserparserssmsparserkt)
36. [main\java\com\yourname\expensetracker\receiver\BootReceiver.kt](#mainjavacomyournameexpensetrackerreceiverbootreceiverkt)
37. [main\java\com\yourname\expensetracker\service\NotificationCaptureService.kt](#mainjavacomyournameexpensetrackerservicenotificationcaptureservicekt)
38. [main\java\com\yourname\expensetracker\ui\MainActivity.kt](#mainjavacomyournameexpensetrackeruimainactivitykt)
39. [main\java\com\yourname\expensetracker\ui\screens\analytics\AnalyticsScreen.kt](#mainjavacomyournameexpensetrackeruiscreensanalyticsanalyticsscreenkt)
40. [main\java\com\yourname\expensetracker\ui\screens\analytics\AnalyticsViewModel.kt](#mainjavacomyournameexpensetrackeruiscreensanalyticsanalyticsviewmodelkt)
41. [main\java\com\yourname\expensetracker\ui\screens\categories\CategoryScreen.kt](#mainjavacomyournameexpensetrackeruiscreenscategoriescategoryscreenkt)
42. [main\java\com\yourname\expensetracker\ui\screens\categories\CategoryViewModel.kt](#mainjavacomyournameexpensetrackeruiscreenscategoriescategoryviewmodelkt)
43. [main\java\com\yourname\expensetracker\ui\screens\debug\DebugScreen.kt](#mainjavacomyournameexpensetrackeruiscreensdebugdebugscreenkt)
44. [main\java\com\yourname\expensetracker\ui\screens\debug\DebugViewModel.kt](#mainjavacomyournameexpensetrackeruiscreensdebugdebugviewmodelkt)
45. [main\java\com\yourname\expensetracker\ui\screens\home\HomeScreen.kt](#mainjavacomyournameexpensetrackeruiscreenshomehomescreenkt)
46. [main\java\com\yourname\expensetracker\ui\screens\home\HomeViewModel.kt](#mainjavacomyournameexpensetrackeruiscreenshomehomeviewmodelkt)
47. [main\java\com\yourname\expensetracker\ui\screens\review\ReviewScreen.kt](#mainjavacomyournameexpensetrackeruiscreensreviewreviewscreenkt)
48. [main\java\com\yourname\expensetracker\ui\screens\review\ReviewViewModel.kt](#mainjavacomyournameexpensetrackeruiscreensreviewreviewviewmodelkt)
49. [main\java\com\yourname\expensetracker\ui\screens\transactions\TransactionsScreen.kt](#mainjavacomyournameexpensetrackeruiscreenstransactionstransactionsscreenkt)
50. [main\java\com\yourname\expensetracker\ui\screens\transactions\TransactionsViewModel.kt](#mainjavacomyournameexpensetrackeruiscreenstransactionstransactionsviewmodelkt)
51. [main\java\com\yourname\expensetracker\ui\theme\Theme.kt](#mainjavacomyournameexpensetrackeruithemethemekt)
52. [main\res\drawable\ic_launcher_background.xml](#mainresdrawableic_launcher_backgroundxml)
53. [main\res\drawable\ic_launcher_foreground.xml](#mainresdrawableic_launcher_foregroundxml)
54. [main\res\mipmap-anydpi-v26\ic_launcher.xml](#mainresmipmap-anydpi-v26ic_launcherxml)
55. [main\res\mipmap-anydpi-v26\ic_launcher_round.xml](#mainresmipmap-anydpi-v26ic_launcher_roundxml)
56. [main\res\mipmap\ic_launcher.xml](#mainresmipmapic_launcherxml)
57. [main\res\mipmap\ic_launcher_round.xml](#mainresmipmapic_launcher_roundxml)
58. [main\res\values\strings.xml](#mainresvaluesstringsxml)
59. [main\res\values\themes.xml](#mainresvaluesthemesxml)
60. [test\java\com\yourname\expensetracker\domain\parser\AppParserRegistryTest.kt](#testjavacomyournameexpensetrackerdomainparserappparserregistrytestkt)

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
        SourceStats::class
    ],
    version = 5,
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
}

```

---

## main\java\com\yourname\expensetracker\data\database\converter\Converters.kt <a name="mainjavacomyournameexpensetrackerdatadatabaseconverterconverterskt"></a>
```kotlin
package com.yourname.expensetracker.data.database.converter
import androidx.room.TypeConverter
import com.yourname.expensetracker.data.database.entity.TransactionType
class Converters {
    @TypeConverter
    fun fromTransactionType(value: TransactionType): String {
        return value.name
    }
    @TypeConverter
    fun toTransactionType(value: String): TransactionType {
        return try {
            TransactionType.valueOf(value)
        } catch (e: IllegalArgumentException) {
            TransactionType.UNKNOWN
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\data\database\dao\BlockedPackageDao.kt <a name="mainjavacomyournameexpensetrackerdatadatabasedaoblockedpackagedaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao
import androidx.room.*
import com.yourname.expensetracker.data.database.entity.BlockedPackage
import kotlinx.coroutines.flow.Flow
@Dao
interface BlockedPackageDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun block(blockedPackage: BlockedPackage)
    @Delete
    suspend fun unblock(blockedPackage: BlockedPackage)
    @Query("DELETE FROM blocked_packages WHERE packageName = :packageName")
    suspend fun unblock(packageName: String)
    @Query("SELECT * FROM blocked_packages")
    fun getAllFlow(): Flow<List<BlockedPackage>>
    @Query("SELECT packageName FROM blocked_packages")
    fun getAllPackageNamesFlow(): Flow<List<String>>
    @Query("SELECT EXISTS(SELECT 1 FROM blocked_packages WHERE packageName = :packageName)")
    suspend fun isBlocked(packageName: String): Boolean
    @Query("DELETE FROM blocked_packages")
    suspend fun deleteAll()
}

```

---

## main\java\com\yourname\expensetracker\data\database\dao\CategoryDao.kt <a name="mainjavacomyournameexpensetrackerdatadatabasedaocategorydaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.yourname.expensetracker.data.database.entity.Category
import kotlinx.coroutines.flow.Flow
@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY isDefault DESC, name ASC")
    fun getAllFlow(): Flow<List<Category>>
    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): Category?
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: Category): Long
    @Update
    suspend fun update(category: Category)
    @Delete
    suspend fun delete(category: Category)
    @Query("SELECT COUNT(*) FROM categories")
    suspend fun getCount(): Int
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(categories: List<Category>)
    @Query("SELECT * FROM categories ORDER BY isDefault DESC, name ASC")
    suspend fun getAll(): List<Category>
}

```

---

## main\java\com\yourname\expensetracker\data\database\dao\ExpenseDao.kt <a name="mainjavacomyournameexpensetrackerdatadatabasedaoexpensedaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.Expense
import kotlinx.coroutines.flow.Flow
@Dao
interface ExpenseDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(expense: Expense): Long
    @Query("SELECT * FROM expenses ORDER BY date DESC")
    fun getAllFlow(): Flow<List<Expense>>
    @Query("SELECT * FROM expenses ORDER BY date DESC")
    suspend fun getAll(): List<Expense>
    @Query("SELECT SUM(amount) FROM expenses WHERE transactionType = 'PURCHASE'")
    fun getTotalSpentFlow(): Flow<Double?>
    @Query("DELETE FROM expenses")
    suspend fun deleteAll()
    @Delete
    suspend fun delete(expense: Expense)
    @Query("UPDATE expenses SET categoryId = :categoryId WHERE id = :expenseId")
    suspend fun updateCategory(expenseId: Long, categoryId: Long)
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM expenses 
            WHERE ABS(amount - :amount) < 0.001 
            AND merchant = :merchant 
            AND ABS(date - :date) <= :windowMs
        )
    """)
    suspend fun isDuplicate(amount: Double, merchant: String, date: Long, windowMs: Long = 300000): Boolean
    // === Analytics Queries ===
    @Query("SELECT * FROM expenses WHERE date >= :startDate AND date <= :endDate ORDER BY date DESC")
    suspend fun getExpensesBetween(startDate: Long, endDate: Long): List<Expense>
    @Query("SELECT * FROM expenses WHERE date >= :startDate AND date <= :endDate ORDER BY date DESC")
    fun getExpensesBetweenFlow(startDate: Long, endDate: Long): Flow<List<Expense>>
    @Query("""
        SELECT SUM(amount) FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startDate AND date <= :endDate
    """)
    suspend fun getTotalSpentBetween(startDate: Long, endDate: Long): Double?
    @Query("""
        SELECT merchant, SUM(amount) as total, COUNT(*) as cnt 
        FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startDate AND date <= :endDate
        GROUP BY UPPER(merchant)
        ORDER BY total DESC
    """)
    suspend fun getMerchantTotalsBetween(startDate: Long, endDate: Long): List<MerchantTotal>
    @Query("""
        SELECT categoryId, SUM(amount) as total, COUNT(*) as cnt 
        FROM expenses 
        WHERE transactionType = 'PURCHASE' 
        AND date >= :startDate AND date <= :endDate
        AND categoryId IS NOT NULL
        GROUP BY categoryId
        ORDER BY total DESC
    """)
    suspend fun getCategoryTotalsBetween(startDate: Long, endDate: Long): List<CategoryTotal>
    @Query("SELECT COUNT(*) FROM expenses WHERE transactionType = 'PURCHASE'")
    suspend fun getPurchaseCount(): Int
    @Query("SELECT MIN(date) FROM expenses")
    suspend fun getOldestExpenseDate(): Long?
}
data class MerchantTotal(
    val merchant: String,
    val total: Double,
    val cnt: Int
)
data class CategoryTotal(
    val categoryId: Long,
    val total: Double,
    val cnt: Int
)

```

---

## main\java\com\yourname\expensetracker\data\database\dao\MerchantCategoryDao.kt <a name="mainjavacomyournameexpensetrackerdatadatabasedaomerchantcategorydaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.yourname.expensetracker.data.database.entity.MerchantCategory
@Dao
interface MerchantCategoryDao {
    @Query("SELECT * FROM merchant_categories WHERE merchantPattern = :merchantPattern")
    suspend fun getCategoryForMerchant(merchantPattern: String): MerchantCategory?
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(merchantCategory: MerchantCategory)
    @Query("SELECT * FROM merchant_categories")
    suspend fun getAll(): List<MerchantCategory>
    @Query("DELETE FROM merchant_categories")
    suspend fun deleteAll()
}

```

---

## main\java\com\yourname\expensetracker\data\database\dao\PendingReviewDao.kt <a name="mainjavacomyournameexpensetrackerdatadatabasedaopendingreviewdaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao
import androidx.room.*
import com.yourname.expensetracker.data.database.entity.PendingReview
import kotlinx.coroutines.flow.Flow
@Dao
interface PendingReviewDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(review: PendingReview): Long
    @Update
    suspend fun update(review: PendingReview)
    @Delete
    suspend fun delete(review: PendingReview)
    @Query("SELECT * FROM pending_reviews WHERE status = 'PENDING' ORDER BY createdAt DESC")
    fun getPendingFlow(): Flow<List<PendingReview>>
    @Query("SELECT * FROM pending_reviews WHERE status = 'PENDING' ORDER BY createdAt DESC")
    suspend fun getPending(): List<PendingReview>
    @Query("SELECT COUNT(*) FROM pending_reviews WHERE status = 'PENDING'")
    fun getPendingCountFlow(): Flow<Int>
    @Query("SELECT * FROM pending_reviews WHERE id = :id")
    suspend fun getById(id: Long): PendingReview?
    @Query("SELECT * FROM pending_reviews WHERE rawNotificationId = :rawId")
    suspend fun getByRawId(rawId: Long): PendingReview?
    @Query("UPDATE pending_reviews SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)
    @Query("UPDATE pending_reviews SET status = :status WHERE id = :id AND status = 'PENDING'")
    suspend fun updateStatusIfPending(id: Long, status: String): Int
    @Query("SELECT * FROM pending_reviews ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<PendingReview>>
    @Query("DELETE FROM pending_reviews WHERE status != 'PENDING'")
    suspend fun clearResolved()
    @Query("DELETE FROM pending_reviews")
    suspend fun deleteAll()
}

```

---

## main\java\com\yourname\expensetracker\data\database\dao\RawNotificationDao.kt <a name="mainjavacomyournameexpensetrackerdatadatabasedaorawnotificationdaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao
import androidx.room.*
import com.yourname.expensetracker.data.database.entity.RawNotification
import kotlinx.coroutines.flow.Flow
@Dao
interface RawNotificationDao {
    @Insert
    suspend fun insert(notification: RawNotification): Long
    @Query("SELECT * FROM raw_notifications WHERE id = :id")
    suspend fun getById(id: Long): RawNotification?
    @Query("SELECT * FROM raw_notifications ORDER BY capturedAt DESC")
    fun getAllFlow(): Flow<List<RawNotification>>
    @Query("SELECT * FROM raw_notifications ORDER BY capturedAt DESC LIMIT :limit")
    fun getRecentFlow(limit: Int): Flow<List<RawNotification>>
    @Query("SELECT * FROM raw_notifications WHERE packageName = :packageName ORDER BY capturedAt DESC")
    fun getByPackageFlow(packageName: String): Flow<List<RawNotification>>
    @Query("SELECT DISTINCT packageName FROM raw_notifications ORDER BY packageName")
    fun getAllPackagesFlow(): Flow<List<String>>
    @Query("SELECT COUNT(*) FROM raw_notifications")
    fun getCountFlow(): Flow<Int>
    @Query("DELETE FROM raw_notifications")
    suspend fun deleteAll()
    @Delete
    suspend fun delete(notification: RawNotification)
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM raw_notifications 
            WHERE packageName = :packageName 
            AND timestamp = :timestamp 
            AND (title = :title OR (:title IS NULL AND title IS NULL))
            AND (text = :text OR (:text IS NULL AND text IS NULL))
        )
    """)
    suspend fun exists(packageName: String, timestamp: Long, title: String?, text: String?): Boolean
    @Query("UPDATE raw_notifications SET isRelevant = :isRelevant WHERE id = :id")
    suspend fun markRelevance(id: Long, isRelevant: Boolean)
}

```

---

## main\java\com\yourname\expensetracker\data\database\dao\SourceStatsDao.kt <a name="mainjavacomyournameexpensetrackerdatadatabasedaosourcestatsdaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao
import androidx.room.*
import com.yourname.expensetracker.data.database.entity.SourceStats
import kotlinx.coroutines.flow.Flow
@Dao
interface SourceStatsDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsert(stats: SourceStats)
    @Query("SELECT * FROM source_stats WHERE packageName = :packageName")
    suspend fun getByPackage(packageName: String): SourceStats?
    @Query("SELECT * FROM source_stats ORDER BY totalNotifications DESC")
    fun getAllFlow(): Flow<List<SourceStats>>
    @Query("SELECT * FROM source_stats ORDER BY totalNotifications DESC")
    suspend fun getAll(): List<SourceStats>
    @Query("""
        UPDATE source_stats 
        SET totalNotifications = totalNotifications + 1, 
            lastSeen = :now 
        WHERE packageName = :packageName
    """)
    suspend fun incrementTotal(packageName: String, now: Long = System.currentTimeMillis())
    @Query("""
        UPDATE source_stats 
        SET acceptedAsExpense = acceptedAsExpense + 1 
        WHERE packageName = :packageName
    """)
    suspend fun incrementAccepted(packageName: String)
    @Query("""
        UPDATE source_stats 
        SET rejectedByUser = rejectedByUser + 1 
        WHERE packageName = :packageName
    """)
    suspend fun incrementRejected(packageName: String)
    @Query("""
        UPDATE source_stats 
        SET autoRejected = autoRejected + 1 
        WHERE packageName = :packageName
    """)
    suspend fun incrementAutoRejected(packageName: String)
    @Query("""
        UPDATE source_stats 
        SET pendingReview = pendingReview + 1 
        WHERE packageName = :packageName
    """)
    suspend fun incrementPending(packageName: String)
    @Query("""
        UPDATE source_stats 
        SET pendingReview = MAX(0, pendingReview - 1) 
        WHERE packageName = :packageName
    """)
    suspend fun decrementPending(packageName: String)
    @Query("UPDATE source_stats SET pendingReview = 0")
    suspend fun resetAllPendingCounts()
    @Query("DELETE FROM source_stats")
    suspend fun deleteAll()
}

```

---

## main\java\com\yourname\expensetracker\data\database\dao\UserCorrectionDao.kt <a name="mainjavacomyournameexpensetrackerdatadatabasedaousercorrectiondaokt"></a>
```kotlin
package com.yourname.expensetracker.data.database.dao
import androidx.room.*
import com.yourname.expensetracker.data.database.entity.UserCorrection
import kotlinx.coroutines.flow.Flow
@Dao
interface UserCorrectionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(correction: UserCorrection): Long
    @Query("SELECT * FROM user_corrections ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<UserCorrection>>
    @Query("SELECT * FROM user_corrections ORDER BY createdAt DESC")
    suspend fun getAll(): List<UserCorrection>
    @Query("SELECT COUNT(*) FROM user_corrections")
    suspend fun getCount(): Int
    // Get all corrections for a specific package (to learn its patterns)
    @Query("SELECT * FROM user_corrections WHERE packageName = :packageName")
    suspend fun getByPackage(packageName: String): List<UserCorrection>
    // Get rejection rate for a package
    @Query("""
        SELECT COUNT(*) FROM user_corrections 
        WHERE packageName = :packageName AND wasRejected = 1
    """)
    suspend fun getRejectionCount(packageName: String): Int
    @Query("""
        SELECT COUNT(*) FROM user_corrections 
        WHERE packageName = :packageName
    """)
    suspend fun getTotalCorrections(packageName: String): Int
    // Find merchant name corrections (user always renames X to Y)
    @Query("""
        SELECT correctedMerchant 
        FROM user_corrections 
        WHERE originalMerchant = :originalMerchant 
        AND correctedMerchant IS NOT NULL 
        AND correctedMerchant != originalMerchant
        GROUP BY correctedMerchant 
        ORDER BY COUNT(*) DESC 
        LIMIT 1
    """)
    suspend fun getMostCommonMerchantCorrection(originalMerchant: String): String?
    @Query("""
        SELECT COUNT(*) FROM user_corrections 
        WHERE originalMerchant = :merchant
    """)
    suspend fun getMerchantTotalCorrections(merchant: String): Int
    @Query("""
        SELECT COUNT(*) FROM user_corrections 
        WHERE originalMerchant = :merchant AND wasRejected = 1
    """)
    suspend fun getMerchantRejectionCount(merchant: String): Int
    @Query("""
        SELECT correctedCategoryId 
        FROM user_corrections 
        WHERE originalMerchant = :merchant 
        AND correctedCategoryId IS NOT NULL
        GROUP BY correctedCategoryId 
        ORDER BY COUNT(*) DESC 
        LIMIT 1
    """)
    suspend fun getMostCommonCategoryForMerchant(merchant: String): Long?
    @Query("""
        SELECT EXISTS(
            SELECT 1 FROM user_corrections 
            WHERE packageName = :packageName 
            AND originalMerchant = :merchant 
            AND wasApproved = 1
        )
    """)
    suspend fun hasPreviousApprovals(merchant: String, packageName: String): Boolean
    @Query("DELETE FROM user_corrections")
    suspend fun deleteAll()
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
    val createdAt: Long = System.currentTimeMillis()
)
enum class TransactionType {
    PURCHASE,
    WITHDRAWAL,
    TRANSFER,
    DEPOSIT,
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
        Index(value = ["status"])
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
        Index(value = ["packageName", "timestamp"])    
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
                merchantEntities.forEach { merchantCategoryDao.insert(it) }
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
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.intelligence.ConfidenceRouter
import com.yourname.expensetracker.domain.intelligence.MerchantNormalizer
import com.yourname.expensetracker.domain.intelligence.RoutingDecision
import com.yourname.expensetracker.domain.intelligence.TransactionClassifier
import com.yourname.expensetracker.domain.intelligence.ClassifierStats
import com.yourname.expensetracker.domain.parser.AppParserRegistry
import kotlinx.coroutines.flow.Flow
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
    private val classifier: TransactionClassifier
) {
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
    // === Core Processing Pipeline ===
    @Transaction
    suspend fun processAndSave(notification: RawNotification) {
        // 0. Deduplication check
        if (dao.exists(notification.packageName, notification.timestamp, notification.title, notification.text)) {
            return
        }
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
                    categoryId = categoryId
                )
                try {
                    expenseDao.insert(expense)
                    dao.markRelevance(rawId, true)
                    sourceStatsDao.incrementAccepted(notification.packageName)
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
                categoryId = categoryId
            )
            try {
                expenseDao.insert(expense)
                // Only if insert succeeds:
                dao.markRelevance(review.rawNotificationId, true)
                sourceStatsDao.incrementAccepted(review.packageName)
                sourceStatsDao.decrementPending(review.packageName)
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
    fun getAllExpenses(): Flow<List<Expense>> = expenseDao.getAllFlow()
}

```

---

## main\java\com\yourname\expensetracker\di\AppModule.kt <a name="mainjavacomyournameexpensetrackerdiappmodulekt"></a>
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
        ).fallbackToDestructiveMigration()
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

---

## main\java\com\yourname\expensetracker\domain\analytics\AnalyticsModels.kt <a name="mainjavacomyournameexpensetrackerdomainanalyticsanalyticsmodelskt"></a>
```kotlin
package com.yourname.expensetracker.domain.analytics
import com.yourname.expensetracker.data.database.entity.Category
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
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.database.entity.TransactionType
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class InsightsEngine @Inject constructor(
    private val expenseDao: ExpenseDao,
    private val categoryDao: CategoryDao
) {
    private val dayMs = 86_400_000L
    private val dateKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    suspend fun generateInsights(): List<SpendingInsight> {
        val all = expenseDao.getAll()
        val purchases = all.filter { it.transactionType == TransactionType.PURCHASE }
        if (purchases.isEmpty()) return emptyList()
        val categories = categoryDao.getAll()
        val insights = mutableListOf<SpendingInsight>()
        val now = System.currentTimeMillis()
        val today = purchases.filter { now - it.date < dayMs }
        val thisWeek = purchases.filter { now - it.date < 7 * dayMs }
        val lastWeek = purchases.filter { it.date in (now - 14 * dayMs)..(now - 7 * dayMs) }
        val thisMonth = purchases.filter { now - it.date < 30 * dayMs }
        val lastMonth = purchases.filter { it.date in (now - 60 * dayMs)..(now - 30 * dayMs) }
        // 1. Week-over-week comparison
        insights.addAll(generateWeekComparison(thisWeek, lastWeek))
        // 2. Category trends
        insights.addAll(generateCategoryTrends(thisMonth, lastMonth, categories))
        // 3. Recurring payment detection
        insights.addAll(generateRecurringInsights(purchases))
        // 4. Biggest transaction today
        insights.addAll(generateTodayInsights(today))
        // 5. Daily average
        insights.addAll(generateAverageInsights(thisMonth, now))
        // 6. Top merchant this month
        insights.addAll(generateTopMerchantInsights(thisMonth))
        // 7. Spending streak
        insights.addAll(generateStreakInsights(purchases, now))
        return insights.sortedByDescending { it.severity }
    }
    private fun generateWeekComparison(
        thisWeek: List<Expense>,
        lastWeek: List<Expense>
    ): List<SpendingInsight> {
        val insights = mutableListOf<SpendingInsight>()
        val thisTotal = thisWeek.sumOf { it.amount }
        val lastTotal = lastWeek.sumOf { it.amount }
        if (lastTotal > 0) {
            val change = ((thisTotal - lastTotal) / lastTotal * 100)
            if (change > 20) {
                insights.add(
                    SpendingInsight(
                        InsightType.SPENDING_INCREASE, "📈",
                        "Spending up ${change.toInt()}%",
                        "€${fmt(thisTotal)} this week vs €${fmt(lastTotal)} last week",
                        (change / 100).coerceAtMost(1.0).toFloat()
                    )
                )
            } else if (change < -15) {
                insights.add(
                    SpendingInsight(
                        InsightType.SPENDING_DECREASE, "📉",
                        "Great job! Spending down ${(-change).toInt()}%",
                        "You saved €${fmt(lastTotal - thisTotal)} compared to last week",
                        0.3f
                    )
                )
            }
        }
        return insights
    }
    private fun generateCategoryTrends(
        thisMonth: List<Expense>,
        lastMonth: List<Expense>,
        categories: List<Category>
    ): List<SpendingInsight> {
        val insights = mutableListOf<SpendingInsight>()
        val categoryMap = categories.associateBy { it.id }
        val thisMonthByCategory = thisMonth.groupBy { it.categoryId }
        val lastMonthByCategory = lastMonth.groupBy { it.categoryId }
        for ((catId, exps) in thisMonthByCategory) {
            if (catId == null) continue
            val cat = categoryMap[catId] ?: continue
            val thisTotal = exps.sumOf { it.amount }
            val lastTotal = lastMonthByCategory[catId]?.sumOf { it.amount } ?: 0.0
            if (lastTotal > 0 && thisTotal > lastTotal * 1.4 && thisTotal > 20) {
                val change = ((thisTotal - lastTotal) / lastTotal * 100).toInt()
                insights.add(
                    SpendingInsight(
                        InsightType.CATEGORY_TREND, cat.icon,
                        "${cat.name} spending up ${change}%",
                        "€${fmt(thisTotal)} this month vs €${fmt(lastTotal)} last month",
                        0.7f
                    )
                )
            } else if (lastTotal > 0 && thisTotal < lastTotal * 0.6 && lastTotal > 20) {
                val change = ((lastTotal - thisTotal) / lastTotal * 100).toInt()
                insights.add(
                    SpendingInsight(
                        InsightType.CATEGORY_TREND, cat.icon,
                        "${cat.name} down ${change}%! 🎉",
                        "€${fmt(thisTotal)} vs €${fmt(lastTotal)} last month",
                        0.4f
                    )
                )
            }
        }
        return insights
    }
    private fun generateRecurringInsights(purchases: List<Expense>): List<SpendingInsight> {
        val insights = mutableListOf<SpendingInsight>()
        val recurring = detectRecurring(purchases)
        for (candidate in recurring.take(3)) {
            val nextStr = candidate.nextExpectedDate?.let { next ->
                val daysUntil = ((next - System.currentTimeMillis()) / dayMs).toInt()
                when {
                    daysUntil < 0 -> "may be overdue"
                    daysUntil == 0 -> "expected today"
                    daysUntil == 1 -> "expected tomorrow"
                    daysUntil <= 7 -> "expected in $daysUntil days"
                    else -> "~every ${candidate.intervalDays} days"
                }
            } ?: "~every ${candidate.intervalDays} days"
            insights.add(
                SpendingInsight(
                    InsightType.RECURRING_DETECTED, "🔄",
                    "Recurring: ${candidate.merchant}",
                    "€${fmt(candidate.amount)} $nextStr",
                    0.5f
                )
            )
        }
        return insights
    }
    private fun generateTodayInsights(today: List<Expense>): List<SpendingInsight> {
        val insights = mutableListOf<SpendingInsight>()
        if (today.isNotEmpty()) {
            val totalToday = today.sumOf { it.amount }
            insights.add(
                SpendingInsight(
                    InsightType.DAILY_AVERAGE, "💰",
                    "Today: €${fmt(totalToday)}",
                    "${today.size} transaction${if (today.size > 1) "s" else ""}",
                    0.2f
                )
            )
            val biggest = today.maxByOrNull { it.amount }
            if (biggest != null && biggest.amount > 15 && today.size > 1) {
                insights.add(
                    SpendingInsight(
                        InsightType.UNUSUAL_TRANSACTION, "⚡",
                        "Biggest today: ${biggest.merchant}",
                        "€${fmt(biggest.amount)}",
                        0.25f
                    )
                )
            }
        }
        return insights
    }
    private fun generateAverageInsights(
        thisMonth: List<Expense>,
        now: Long
    ): List<SpendingInsight> {
        val insights = mutableListOf<SpendingInsight>()
        if (thisMonth.size >= 3) {
            val days = ((now - thisMonth.minOf { it.date }) / dayMs).coerceAtLeast(1)
            val dailyAvg = thisMonth.sumOf { it.amount } / days
            insights.add(
                SpendingInsight(
                    InsightType.DAILY_AVERAGE, "📊",
                    "Daily average: €${fmt(dailyAvg)}",
                    "Based on the last $days days",
                    0.3f
                )
            )
        }
        return insights
    }
    private fun generateTopMerchantInsights(thisMonth: List<Expense>): List<SpendingInsight> {
        val insights = mutableListOf<SpendingInsight>()
        val byMerchant = thisMonth.groupBy { it.merchant.uppercase() }
        val topMerchant = byMerchant.maxByOrNull { it.value.sumOf { e -> e.amount } }
        if (topMerchant != null && topMerchant.value.size >= 2) {
            val total = topMerchant.value.sumOf { it.amount }
            val count = topMerchant.value.size
            insights.add(
                SpendingInsight(
                    InsightType.TOP_MERCHANT, "🏪",
                    "Top merchant: ${topMerchant.key}",
                    "${count}x transactions, €${fmt(total)} total this month",
                    0.35f
                )
            )
        }
        return insights
    }
    private fun generateStreakInsights(
        purchases: List<Expense>,
        now: Long
    ): List<SpendingInsight> {
        val insights = mutableListOf<SpendingInsight>()
        // Check for no-spend streak
        val sortedDates = purchases.map { dateKeyFormat.format(Date(it.date)) }.toSet()
        val cal = Calendar.getInstance()
        var noSpendDays = 0
        // Count backwards from yesterday
        cal.timeInMillis = now
        cal.add(Calendar.DAY_OF_YEAR, -1)
        while (noSpendDays < 30) {
            val dateKey = dateKeyFormat.format(cal.time)
            if (sortedDates.contains(dateKey)) break
            noSpendDays++
            cal.add(Calendar.DAY_OF_YEAR, -1)
        }
        if (noSpendDays >= 2) {
            insights.add(
                SpendingInsight(
                    InsightType.STREAK, "🔥",
                    "$noSpendDays day no-spend streak!",
                    "Keep it up! Your wallet thanks you.",
                    0.4f
                )
            )
        }
        return insights
    }
    fun detectRecurring(expenses: List<Expense>): List<RecurringCandidate> {
        val results = mutableListOf<RecurringCandidate>()
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
                // Filter for monthly (25-35), biweekly (12-16), weekly (5-9), yearly (350-380)
                val isRecurring = avgInterval in 5..9 ||
                        avgInterval in 12..16 ||
                        avgInterval in 25..35 ||
                        avgInterval in 350..380
                if (isRecurring) {
                    val lastDate = sorted.last().date
                    val nextExpected = lastDate + avgInterval * dayMs
                    results.add(
                        RecurringCandidate(
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
    fun buildDailyTotals(expenses: List<Expense>, days: Int): Map<String, Double> {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance()
        val result = LinkedHashMap<String, Double>()
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
            val key = dateKeyFormat.format(Date(expense.date))
            if (result.containsKey(key)) {
                result[key] = (result[key] ?: 0.0) + expense.amount
            }
        }
        return result
    }
    private fun fmt(amount: Double): String = String.format("%.2f", amount)
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
        for (word in words) {
            val wordMatch = merchantCategoryDao.getCategoryForMerchant(word)
            if (wordMatch != null) return wordMatch.categoryId
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

## main\java\com\yourname\expensetracker\domain\intelligence\ConfidenceRouter.kt <a name="mainjavacomyournameexpensetrackerdomainintelligenceconfidencerouterkt"></a>
```kotlin
package com.yourname.expensetracker.domain.intelligence
import com.yourname.expensetracker.data.database.dao.SourceStatsDao
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import com.yourname.expensetracker.data.database.entity.SourceStats
import com.yourname.expensetracker.domain.parser.ParsedTransaction
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
        // 2. Adjust based on source trust score
        val sourceStats = sourceStatsDao.getByPackage(packageName)
        if (sourceStats != null && sourceStats.totalNotifications > 10) {
            val trustModifier = calculateTrustModifier(sourceStats)
            adjustedConfidence *= trustModifier
            if (trustModifier < 0.9f) {
                reasons.add("Source trust: ${(sourceStats.trustScore * 100).toInt()}%")
            }
        }
        // 3. Adjust based on user correction history for this merchant
        val merchantRejectionRate = getMerchantRejectionRate(parsed.merchant)
        if (merchantRejectionRate > 0.5f) {
            adjustedConfidence *= 0.5f
            reasons.add("Merchant often rejected")
        }
        // 4. Package rejection rate
        val packageRejectionRate = getPackageRejectionRate(packageName)
        if (packageRejectionRate > 0.7f) {
            adjustedConfidence *= 0.3f
            reasons.add("Package mostly rejected")
        }
        // 5. Boost if user has previously approved similar transactions
        val previouslyApproved = hasPreviousApprovals(parsed.merchant, packageName)
        if (previouslyApproved) {
            adjustedConfidence = (adjustedConfidence * 1.2f).coerceAtMost(1.0f)
            reasons.add("Previously approved merchant")
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
            sourceStatsDao.upsert(SourceStats(packageName = packageName))
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\intelligence\MerchantNormalizer.kt <a name="mainjavacomyournameexpensetrackerdomainintelligencemerchantnormalizerkt"></a>
```kotlin
package com.yourname.expensetracker.domain.intelligence
import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import javax.inject.Inject
import javax.inject.Singleton
@Singleton
class MerchantNormalizer @Inject constructor(
    private val userCorrectionDao: UserCorrectionDao
) {
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
        val stripped = normalize(merchant)
        // Check known aliases
        for ((key, canonical) in KNOWN_ALIASES) {
            if (stripped.contains(key)) {
                return canonical
            }
        }
        // Check user corrections
        val userCorrection = userCorrectionDao.getMostCommonMerchantCorrection(stripped)
        if (userCorrection != null) {
            return userCorrection
        }
        // Return cleaned version with proper casing
        return toTitleCase(stripped)
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
        val corrected = userCorrectionDao.getMostCommonMerchantCorrection(normalized)
        return corrected ?: toTitleCase(normalized)
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

## main\java\com\yourname\expensetracker\domain\parser\AppParserRegistry.kt <a name="mainjavacomyournameexpensetrackerdomainparserappparserregistrykt"></a>
```kotlin
package com.yourname.expensetracker.domain.parser
import com.yourname.expensetracker.data.database.entity.TransactionType
/**
 * Result from an app-specific parser. Higher confidence = more certain it's a real transaction.
 */
data class ParsedTransaction(
    val amount: Double,
    val currency: String,
    val merchant: String,
    val type: TransactionType,
    val confidence: Float // 0.0 to 1.0
)
/**
 * Interface for app-specific notification parsers.
 */
interface AppNotificationParser {
    /** Package names this parser handles */
    val supportedPackages: Set<String>
    /**
     * Try to parse. Return null if notification is NOT a transaction.
     * Should be strict — only return a result when confident.
     */
    fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction?
}
/**
 * Registry that routes notifications to the right parser.
 */
class AppParserRegistry(
    private val appParsers: List<AppNotificationParser>,
    private val fallbackParser: GenericTransactionParser
) {
    fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction? {
        // 1. Try app-specific parser first
        val specificParser = appParsers.find { packageName in it.supportedPackages }
        if (specificParser != null) {
            return specificParser.parse(title, text, bigText, subText, packageName)
        }
        // 2. Fallback to generic parser with HIGH threshold
        return fallbackParser.parse(title, text, bigText, subText, packageName)
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\parser\GenericTransactionParser.kt <a name="mainjavacomyournameexpensetrackerdomainparsergenerictransactionparserkt"></a>
```kotlin
package com.yourname.expensetracker.domain.parser
import com.yourname.expensetracker.data.database.entity.TransactionType
import java.util.regex.Pattern
/**
 * Fallback parser for unknown apps. VERY strict — requires both
 * a strong transaction signal AND a plausible amount pattern.
 * Returns results with lower confidence.
 */
class GenericTransactionParser {
    // Strong signals that this is a REAL transaction notification
    private val strongTransactionSignals by lazy {
        listOf(
            // English patterns that strongly indicate actual transactions
            Pattern.compile("""(?:you\s+)?paid\s+[€$£]?\s*\d""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""payment\s+(?:of\s+)?[€$£]\s*\d""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""charged?\s+[€$£]?\s*\d""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""(?:debit|deducted)\s+[€$£]?\s*\d""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""transaction\s+(?:of\s+)?[€$£]\s*\d""", Pattern.CASE_INSENSITIVE),
            // Greek patterns
            Pattern.compile("""(?:πληρω|χρεω|αγορ[αά])\w*\s+\d+[.,]\d{2}\s*(?:€|EUR)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""(?:€|EUR)\s*\d+[.,]\d{2}\s*(?:στ[οη]|at)\s""", Pattern.CASE_INSENSITIVE),
            // Greeklish patterns
            Pattern.compile("""(?:pliromi|xreosi|hreosi|agora)\w*\s+\d+[.,]\d{2}\s*(?:€|EUR)""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""(?:€|EUR)\s*\d+[.,]\d{2}\s*(?:sto|se|stin?)\s""", Pattern.CASE_INSENSITIVE),
        )
    }
    // NEGATIVE signals — if present, this is NOT a transaction
    // Using Regex to enforce word boundaries for English words to avoid "Coffee" matching "offer"
    private val negativeSignalsPattern by lazy {
        Pattern.compile(
            """\b(offer|discount|save\s+up\s+to|earn|free|up\s+to|starting\s+from|balance|otp|verification|code|unsubscribe|opt\s+out|sale|%\s+off|promo|your\s+order|tracking|shipped|delivered|reminder|rate\s+us|review|survey)\b|""" +
            """(προσφορά|έκπτωση|εξοικονομ|κέρδισε|δωρεάν|έως|από|υπόλοιπο|κωδικός|υπενθύμιση)""",
            Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
        )
    }
    private val amountPattern by lazy {
        Pattern.compile(
            """([€$£])\s*(\d+(?:[.,]\d{2})?)|(\d+(?:[.,]\d{2})?)\s*([€$£]|EUR|USD|GBP)""",
            Pattern.CASE_INSENSITIVE
        )
    }
    private val MERCHANT_PREFIXES = listOf(" at ", " to ", " σε ", " στον ", " στην ", " στο ", " για ", " sto ", " ston ", " stin ", " se ")
    fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction? {
        val fullText = listOfNotNull(title, text, bigText).joinToString(" ")
        val lowerFull = fullText.lowercase()
        // 1. Check negative signals first
        if (negativeSignalsPattern.matcher(lowerFull).find()) return null
        // 2. Require at least one STRONG transaction signal
        val hasStrongSignal = strongTransactionSignals.any { it.matcher(lowerFull).find() }
        if (!hasStrongSignal) return null
        // 3. Extract amount
        val amountResult = extractAmount(fullText) ?: return null
        // 4. Sanity check amount
        if (amountResult.first < 0.10 || amountResult.first > 25000) return null
        // 5. Extract merchant
        val merchant = extractMerchant(fullText, title)
        return ParsedTransaction(
            amount = amountResult.first,
            currency = amountResult.second,
            merchant = merchant,
            type = TransactionType.PURCHASE,
            confidence = 0.60f // Lower confidence for generic parser
        )
    }
    private fun extractAmount(text: String): Pair<Double, String>? {
        val matcher = amountPattern.matcher(text)
        if (matcher.find()) {
            val currency = matcher.group(1) ?: matcher.group(4) ?: "€"
            val amountStr = (matcher.group(2) ?: matcher.group(3))?.replace(",", ".") ?: return null
            val amount = amountStr.toDoubleOrNull() ?: return null
            return Pair(amount, normalizeCurrency(currency))
        }
        return null
    }
    private fun extractMerchant(text: String, title: String?): String {
        val normalized = text.replace('\u00A0', ' ')
        for (prefix in MERCHANT_PREFIXES) {
            val index = normalized.indexOf(prefix, ignoreCase = true)
            if (index != -1) {
                val after = normalized.substring(index + prefix.length).trim()
                return cleanMerchant(after)
            }
        }
        // Fallback to title if it's not a generic keyword
        if (!title.isNullOrBlank() && !isGenericTitle(title.lowercase())) {
            return cleanMerchant(title)
        }
        return "Unknown"
    }
    private fun isGenericTitle(title: String): Boolean {
        val genericWords = listOf("payment", "purchase", "transaction", "alert", "notification",
            "πληρωμή", "αγορά", "συναλλαγή", "ειδοποίηση")
        return genericWords.any { title.contains(it) }
    }
    private fun cleanMerchant(raw: String): String {
        val stopWords = listOf("confirmed", "successful", "completed", "declined",
            "ολοκληρώθηκε", "επιτυχής", ".", "!", "with card", "με κάρτα")
        var candidate = raw
        for (stop in stopWords) {
            val idx = candidate.indexOf(stop, ignoreCase = true)
            if (idx != -1) candidate = candidate.substring(0, idx)
        }
        return candidate.trim().take(40).trim()
    }
    private fun normalizeCurrency(raw: String): String {
        return when (raw.uppercase().trim()) {
            "€", "EUR", "ΕΥΡΩ" -> "EUR"
            "$", "USD" -> "USD"
            "£", "GBP" -> "GBP"
            else -> "EUR"
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\parser\parsers\GoogleWalletParser.kt <a name="mainjavacomyournameexpensetrackerdomainparserparsersgooglewalletparserkt"></a>
```kotlin
package com.yourname.expensetracker.domain.parser.parsers
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.AppNotificationParser
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import java.util.regex.Pattern
class GoogleWalletParser : AppNotificationParser {
    override val supportedPackages = setOf(
        "com.google.android.apps.walletnfcrel",
        "com.google.android.apps.nbu.paisa.user"
    )
    private val amountPattern by lazy {
        Pattern.compile(
            """([€$£])\s*(\d+[.,]\d{2})|(\d+[.,]\d{2})\s*([€$£]|EUR|USD|GBP)""",
            Pattern.CASE_INSENSITIVE
        )
    }
    private val atPattern by lazy {
        Pattern.compile("""(?:at|to)\s+([A-Za-zΑ-Ωα-ω0-9\s&'.,-]+)""", Pattern.CASE_INSENSITIVE)
    }
    // Things that are NOT transactions
    private val REJECT_PATTERNS = listOf(
        "add a card", "set up", "tap to pay", "loyalty", "offer",
        "reward", "cashback available", "nearby", "suggest"
    )
    override fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction? {
        val fullText = listOfNotNull(title, text, bigText).joinToString(" ")
        val lowerFull = fullText.lowercase()
        if (REJECT_PATTERNS.any { lowerFull.contains(it) }) return null
        // Extract amount from anywhere in the notification
        val amount = extractAmount(fullText) ?: return null
        // Extract merchant: usually the title IS the merchant, or text contains "at MERCHANT"
        val merchant = extractMerchant(title, text, bigText)
        return ParsedTransaction(
            amount = amount.first,
            currency = amount.second,
            merchant = merchant,
            type = TransactionType.PURCHASE,
            confidence = 0.90f
        )
    }
    private fun extractAmount(text: String): Pair<Double, String>? {
        val matcher = amountPattern.matcher(text)
        if (matcher.find()) {
            val prefixCurrency = matcher.group(1) ?: matcher.group(4)
            val amountStr = (matcher.group(2) ?: matcher.group(3))?.replace(",", ".") ?: return null
            val amount = amountStr.toDoubleOrNull() ?: return null
            // Filter unrealistic amounts
            if (amount < 0.01 || amount > 50000) return null
            return Pair(amount, normalizeCurrency(prefixCurrency))
        }
        return null
    }
    private fun extractMerchant(title: String?, text: String?, bigText: String?): String {
        // Check for "at MERCHANT" pattern in text
        val combinedText = listOfNotNull(text, bigText).joinToString(" ")
        val atMatcher = atPattern.matcher(combinedText)
        if (atMatcher.find()) {
            return cleanMerchant(atMatcher.group(1) ?: "Unknown")
        }
        // Title might be the merchant if it doesn't contain amount/payment keywords
        if (!title.isNullOrBlank()) {
            val lowerTitle = title.lowercase()
            val isAmount = amountPattern.matcher(title).find()
            val isKeyword = listOf("payment", "purchase", "paid", "transaction", "google wallet", "wallet").any { lowerTitle.contains(it) }
            if (!isAmount && !isKeyword) {
                return cleanMerchant(title)
            }
        }
        return "Unknown"
    }
    private fun cleanMerchant(raw: String): String {
        return raw.trim()
            .replace(Regex("""[•·\-]\s*(Mastercard|Visa|Amex|card).*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s*\*{2,}\d+.*$"""), "") // Remove "••1234"
            .trim()
            .take(40)
            .trim()
    }
    private fun normalizeCurrency(raw: String?): String {
        return when (raw?.uppercase()?.trim()) {
            "€", "EUR" -> "EUR"
            "$", "USD" -> "USD"
            "£", "GBP" -> "GBP"
            else -> "EUR"
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\parser\parsers\GreekBankParser.kt <a name="mainjavacomyournameexpensetrackerdomainparserparsersgreekbankparserkt"></a>
```kotlin
package com.yourname.expensetracker.domain.parser.parsers
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.AppNotificationParser
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import java.util.regex.Pattern
/**
 * Parser for Greek banking apps (NBG, Alpha, Eurobank, Piraeus).
 * These typically send very structured SMS-like notifications.
 */
class GreekBankParser : AppNotificationParser {
    override val supportedPackages = setOf(
        "gr.nbg.mobilebanking",
        "gr.alpha.mobile",
        "com.eurobank.mobile",
        "com.winbank.mobile"
    )
    private val PURCHASE_PATTERNS = listOf(
        // "Αγορά 12,50 EUR στο MERCHANT"
        Pattern.compile(
            """(?:αγορ[άα]|χρ[έε]ωσ|συναλλαγ[ήη]|πληρωμ[ήη]|payment|purchase)\s+(\d+[.,]\d{2})\s*(EUR|€|USD|GBP)?\s*(?:στ[οη]ν?|at|-)?\s*(.+?)(?:\s*(?:με|with)\s*κ[άα]ρτ|$)""",
            Pattern.CASE_INSENSITIVE
        ),
        // "€12.50 at MERCHANT" or "12,50€ MERCHANT"
        Pattern.compile(
            """([€$£])\s*(\d+[.,]\d{2})\s*(?:at|στ[οη]ν?|-)\s+(.+?)(?:\s*(?:με|with)|$)""",
            Pattern.CASE_INSENSITIVE
        ),
        // "MERCHANT 12,50 EUR"
        Pattern.compile(
            """(?:χρ[έε]ωσ[ηη]?\s*κ[άα]ρτ[αά]ς?\s*\*?\d*:?\s*)(\d+[.,]\d{2})\s*(EUR|€)?\s*[-–]\s*(.+)""",
            Pattern.CASE_INSENSITIVE
        )
    )
    // Patterns to REJECT
    private val REJECT_PATTERNS = listOf(
        "υπόλοιπο", "balance", "otp", "κωδικός", "code",
        "ενεργοποί", "activate", "εγκρίθηκε η αίτηση",
        "προσφορά", "offer", "έκπτωση", "discount",
        "ενημέρωση", "update", "reminder"
    )
    override fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction? {
        val fullText = listOfNotNull(title, text, bigText).joinToString(" ")
        val lowerFull = fullText.lowercase()
        // Quick reject
        if (REJECT_PATTERNS.any { lowerFull.contains(it) }) return null
        for (pattern in PURCHASE_PATTERNS) {
            val matcher = pattern.matcher(fullText)
            if (matcher.find()) {
                // Groups vary by pattern but we try to extract amount and merchant
                return tryExtract(matcher, fullText)
            }
        }
        return null
    }
    private fun tryExtract(matcher: java.util.regex.Matcher, fullText: String): ParsedTransaction? {
        // Try to find the amount (could be in group 1 or 2)
        var amountStr: String? = null
        var currency = "EUR"
        var merchant = "Unknown"
        for (i in 1..matcher.groupCount()) {
            val group = matcher.group(i) ?: continue
            if (group.matches(Regex("""\d+[.,]\d{2}"""))) {
                amountStr = group
            } else if (group.matches(Regex("""[€$£]|EUR|USD|GBP""", RegexOption.IGNORE_CASE))) {
                currency = normalizeCurrency(group)
            } else if (group.length > 2) {
                merchant = cleanMerchant(group)
            }
        }
        val amount = amountStr?.replace(",", ".")?.toDoubleOrNull() ?: return null
        if (amount < 0.01 || amount > 50000) return null
        return ParsedTransaction(
            amount = amount,
            currency = currency,
            merchant = merchant,
            type = TransactionType.PURCHASE,
            confidence = 0.92f
        )
    }
    private fun cleanMerchant(raw: String): String {
        return raw.trim()
            .replace(Regex("""(?:στις|on)\s*\d{1,2}/\d{1,2}.*$"""), "")
            .replace(Regex("""\s*\*{2,}\d+.*$"""), "")
            .trim()
            .take(40)
            .trim()
    }
    private fun normalizeCurrency(raw: String?): String {
        return when (raw?.uppercase()?.trim()) {
            "€", "EUR" -> "EUR"
            "$", "USD" -> "USD"
            "£", "GBP" -> "GBP"
            else -> "EUR"
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\parser\parsers\RevolutParser.kt <a name="mainjavacomyournameexpensetrackerdomainparserparsersrevolutparserkt"></a>
```kotlin
package com.yourname.expensetracker.domain.parser.parsers
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.AppNotificationParser
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import java.util.regex.Pattern
class RevolutParser : AppNotificationParser {
    override val supportedPackages = setOf("com.revolut.revolut")
    // Revolut notifications typically look like:
    // Title: "Paid €12.50" or "💳 €12.50 at SKLAVENITIS"
    // Text: "💳 €12.50 at SKLAVENITIS" or "You paid €5.00 to John"
    // Also: "Received €100.00 from John"
    // Also: "ATM withdrawal: €50.00"
    // Ignore: "Your exchange rate...", "Weekly report", "Special offer"
    private val PAID_PATTERN = Pattern.compile(
        """(?:paid|sent|💳)\s*([€$£]|EUR|USD|GBP)?\s*(\d+[.,]\d{2})\s*(?:at|to)\s+(.+)""",
        Pattern.CASE_INSENSITIVE
    )
    private val RECEIVED_PATTERN = Pattern.compile(
        """received\s*([€$£]|EUR|USD|GBP)?\s*(\d+[.,]\d{2})\s*from\s+(.+)""",
        Pattern.CASE_INSENSITIVE
    )
    private val ATM_PATTERN = Pattern.compile(
        """(?:atm|withdrawal)[:\s]*([€$£]|EUR|USD|GBP)?\s*(\d+[.,]\d{2})""",
        Pattern.CASE_INSENSITIVE
    )
    // Patterns to REJECT (not transactions)
    private val REJECT_PATTERNS = listOf(
        "exchange rate", "weekly report", "special offer", "cashback",
        "refer a friend", "upgrade", "verify", "security", "pin",
        "top-up reminder", "price alert", "savings vault"
    )
    override fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction? {
        val fullText = listOfNotNull(title, text, bigText).joinToString(" ")
        val lowerFull = fullText.lowercase()
        // Quick reject
        if (REJECT_PATTERNS.any { lowerFull.contains(it) }) return null
        // Try paid/purchase pattern
        val paidMatcher = PAID_PATTERN.matcher(fullText)
        if (paidMatcher.find()) {
            val currency = normalizeCurrency(paidMatcher.group(1))
            val amount = paidMatcher.group(2)?.replace(",", ".")?.toDoubleOrNull() ?: return null
            val merchant = cleanMerchant(paidMatcher.group(3) ?: "Unknown")
            return ParsedTransaction(amount, currency, merchant, TransactionType.PURCHASE, 0.95f)
        }
        // Try received pattern
        val receivedMatcher = RECEIVED_PATTERN.matcher(fullText)
        if (receivedMatcher.find()) {
            val currency = normalizeCurrency(receivedMatcher.group(1))
            val amount = receivedMatcher.group(2)?.replace(",", ".")?.toDoubleOrNull() ?: return null
            val merchant = cleanMerchant(receivedMatcher.group(3) ?: "Unknown")
            return ParsedTransaction(amount, currency, merchant, TransactionType.DEPOSIT, 0.90f)
        }
        // Try ATM pattern
        val atmMatcher = ATM_PATTERN.matcher(fullText)
        if (atmMatcher.find()) {
            val currency = normalizeCurrency(atmMatcher.group(1))
            val amount = atmMatcher.group(2)?.replace(",", ".")?.toDoubleOrNull() ?: return null
            return ParsedTransaction(amount, currency, "ATM", TransactionType.WITHDRAWAL, 0.95f)
        }
        return null
    }
    private fun cleanMerchant(raw: String): String {
        return raw.trim()
            .replace(Regex("[.!]$"), "")
            .take(40)
            .trim()
    }
    private fun normalizeCurrency(raw: String?): String {
        return when (raw?.uppercase()?.trim()) {
            "€", "EUR" -> "EUR"
            "$", "USD" -> "USD"
            "£", "GBP" -> "GBP"
            else -> "EUR"
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\domain\parser\parsers\SmsParser.kt <a name="mainjavacomyournameexpensetrackerdomainparserparserssmsparserkt"></a>
```kotlin
package com.yourname.expensetracker.domain.parser.parsers
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.AppNotificationParser
import com.yourname.expensetracker.domain.parser.ParsedTransaction
import java.util.regex.Pattern
/**
 * Handles SMS from banking apps (Google Messages, Samsung Messages, etc.)
 * These are forwarded notifications from SMS — needs very careful filtering
 * because messaging apps send ALL messages.
 */
class SmsParser : AppNotificationParser {
    override val supportedPackages = setOf(
        "com.google.android.apps.messaging",
        "com.samsung.android.messaging",
        "com.android.mms"
    )
    // Known bank SMS sender IDs
    private val BANK_SENDERS = setOf(
        "nbg", "alpha", "eurobank", "piraeus", "winbank",
        "revolut", "paypal", "visa", "mastercard",
        "ethniki", "εθνική", "αλφα", "πειραιώς"
    )
    private val amountPattern by lazy {
        Pattern.compile(
            """(\d+[.,]\d{2})\s*(EUR|€|USD|\$|GBP|£)|(EUR|€|USD|\$|GBP|£)\s*(\d+[.,]\d{2})""",
            Pattern.CASE_INSENSITIVE
        )
    }
    private val TRANSACTION_KEYWORDS = listOf(
        "αγορ", "πληρωμ", "χρέωσ", "συναλλαγ",
        "purchase", "payment", "charged", "debit",
        "agora", "pliromi", "plirwmi", "hreosi", "xreosi", "synallagi"
    )
    override fun parse(
        title: String?,
        text: String?,
        bigText: String?,
        subText: String?,
        packageName: String
    ): ParsedTransaction? {
        // For SMS, the title is usually the sender
        val sender = title?.lowercase() ?: return null
        val body = listOfNotNull(text, bigText).joinToString(" ")
        val lowerBody = body.lowercase()
        // Only process if sender looks like a bank
        val isBankSms = BANK_SENDERS.any { sender.contains(it) }
        if (!isBankSms) return null
        // Must contain transaction keywords
        val hasKeyword = TRANSACTION_KEYWORDS.any { lowerBody.contains(it) }
        if (!hasKeyword) return null
        // Extract amount
        val matcher = amountPattern.matcher(body)
        if (!matcher.find()) return null
        val amountStr = (matcher.group(1) ?: matcher.group(4))?.replace(",", ".") ?: return null
        val amount = amountStr.toDoubleOrNull() ?: return null
        val currency = normalizeCurrency(matcher.group(2) ?: matcher.group(3))
        if (amount < 0.10 || amount > 50000) return null
        // Try to extract merchant from the SMS body
        val merchant = extractMerchantFromSms(body)
        return ParsedTransaction(
            amount = amount,
            currency = currency,
            merchant = merchant,
            type = TransactionType.PURCHASE,
            confidence = 0.85f
        )
    }
    private val merchantPatterns by lazy {
        listOf(
            Pattern.compile("""(?:στ[οη]ν?|at|sto|stin?|ston?|se|sta)\s+([A-Za-zΑ-Ωα-ω0-9\s&'.,-]{2,30})""", Pattern.CASE_INSENSITIVE),
            Pattern.compile("""-\s+([A-Za-zΑ-Ωα-ω0-9\s&'.,-]{2,30})""", Pattern.CASE_INSENSITIVE)
        )
    }
    private fun extractMerchantFromSms(body: String): String {
        for (p in merchantPatterns) {
            val m = p.matcher(body)
            if (m.find()) return m.group(1)?.trim()?.take(30) ?: "Unknown"
        }
        return "Unknown"
    }
    private fun normalizeCurrency(raw: String?): String {
        return when (raw?.uppercase()?.trim()) {
            "€", "EUR" -> "EUR"
            "$", "USD" -> "USD"
            "£", "GBP" -> "GBP"
            else -> "EUR"
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\receiver\BootReceiver.kt <a name="mainjavacomyournameexpensetrackerreceiverbootreceiverkt"></a>
```kotlin
package com.yourname.expensetracker.receiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.yourname.expensetracker.service.NotificationCaptureService
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            // We can't start the service directly from background on Android 8+
            // But we can try to request a rebind if the component is enabled.
            // However, NotificationListenerService is special. The system binds to it.
            // This receiver mainly serves to ensure our process is woken up.
            // On some aggressive OSes, starting a foreground service or just 'being' alive
            // helps the system re-bind the listener.
            // For now, we'll just log/noop, as the critical piece is
            // android:enabled="true" in manifest and user toggle.
            // Extending this: we could schedule a WorkManager job here.
            Log.d("BootReceiver", "Boot completed - Service should be restarted by system or user interaction.")
        }
    }
}

```

---

## main\java\com\yourname\expensetracker\service\NotificationCaptureService.kt <a name="mainjavacomyournameexpensetrackerservicenotificationcaptureservicekt"></a>
```kotlin
package com.yourname.expensetracker.service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.core.app.NotificationCompat
import com.yourname.expensetracker.data.database.entity.RawNotification
import com.yourname.expensetracker.data.repository.NotificationRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
@AndroidEntryPoint
class NotificationCaptureService : NotificationListenerService() {
    @Inject
    lateinit var repository: NotificationRepository
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(serviceJob + Dispatchers.IO)
    // Thread-safe, bounded deduplication cache
    private val processedNotifications = ConcurrentHashMap<String, Long>()
    private var processCount = 0
    companion object {
        private const val TAG = "NotificationCapture"
        const val ACTION_REFRESH_NOTIFICATIONS = "com.yourname.expensetracker.REFRESH_NOTIFICATIONS"
        private const val FOREGROUND_ID = 1001
        private const val CHANNEL_ID = "expense_tracker_service"
        private const val DEDUP_WINDOW_MS = 5000L
        private const val CACHE_CLEANUP_THRESHOLD = 50
        private const val CACHE_MAX_AGE_MS = 60_000L
        // Packages filtering logic...
        private val MONITORED_PACKAGES = setOf(
            "com.revolut.revolut",
            "com.google.android.apps.walletnfcrel",
            "com.google.android.apps.nbu.paisa.user", // Google Pay (old/new variants)
            "gr.nbg.mobilebanking", // National Bank of Greece
            "com.eurobank.mobile",
            "gr.alpha.mobile",
            "com.winbank.mobile", // Piraeus
            "com.viber.voip",
            "com.google.android.gm", // Gmail
            "com.android.mms", // SMS (generic)
            "com.google.android.apps.messaging", // Google Messages
            "com.samsung.android.messaging" // Samsung Messages
        )
        private val IGNORED_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "com.whatsapp",
            "com.facebook.orca",
            "com.instagram.android"
        )
    }
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Expense Tracking",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Monitors transactions in background"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundWithNotification()
        if (intent?.action == ACTION_REFRESH_NOTIFICATIONS) {
            refreshActiveNotifications()
        }
        return super.onStartCommand(intent, flags, startId)
    }
    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.d(TAG, "NotificationListener connected! Starting foreground service.")
        startForegroundWithNotification()
    }
    private fun startForegroundWithNotification() {
        try {
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Expense Tracker Active")
                .setContentText("Monitoring your transactions")
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setSilent(true)
                .build()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    startForeground(FOREGROUND_ID, notification, 
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start foreground with type DATA_SYNC, fallback to generic", e)
                    startForeground(FOREGROUND_ID, notification)
                }
            } else {
                startForeground(FOREGROUND_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: Failed to start foreground service", e)
        }
    }
    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.w(TAG, "NotificationListener disconnected - attempting rebind")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            requestRebind(ComponentName(this, NotificationCaptureService::class.java))
        }
    }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val packageName = sbn.packageName
        if (!shouldCapture(packageName)) return
        // Extract notification data ONCE for deduplication logic
        val extras = sbn.notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
        // Better deduplication using notification key + content
        // sbn.key is unique to the notification slot
        // contentHash ensures we catch updates to the same notification if content differs
        val contentHash = (title.orEmpty() + text.orEmpty() + bigText.orEmpty()).hashCode()
        val dedupeKey = "${sbn.key}:$contentHash"
        val now = System.currentTimeMillis()
        val lastProcessed = processedNotifications[dedupeKey]
        if (lastProcessed != null && (now - lastProcessed) < DEDUP_WINDOW_MS) {
            // Already processed this exact content recently
            return
        }
        // Update cache
        processedNotifications[dedupeKey] = now
        cleanupCacheIfNeeded()
        serviceScope.launch {
            processNotification(sbn, packageName, title, text, bigText, extras)
        }
    }
    private fun cleanupCacheIfNeeded() {
        processCount++
        if (processCount >= CACHE_CLEANUP_THRESHOLD) {
            processCount = 0
            val now = System.currentTimeMillis()
            processedNotifications.entries.removeIf { 
                now - it.value > CACHE_MAX_AGE_MS 
            }
        }
    }
    private suspend fun processNotification(
        sbn: StatusBarNotification,
        packageName: String,
        title: String?,
        text: String?,
        bigText: String?,
        extras: android.os.Bundle
    ) {
        if (repository.isPackageBlocked(packageName)) {
            Log.d(TAG, "Ignoring blocked package: $packageName")
            return
        }
        // Extract additional useful data for banking apps (sometimes hidden here)
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        val infoText = extras.getCharSequence(Notification.EXTRA_INFO_TEXT)?.toString()
        val summaryText = extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()
        // Combine text for robust parsing - some apps put the real info in odd places
        val effectiveBigText = bigText ?: infoText ?: summaryText
        val extrasJson = try {
            buildExtrasJson(extras)
        } catch (e: Exception) {
            "{\"error\": \"${e.message}\"}"
        }
        val appName = try {
            val appInfo = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(appInfo).toString()
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
        val rawNotification = RawNotification(
            packageName = packageName,
            appName = appName,
            title = title,
            text = text,
            bigText = effectiveBigText,
            subText = subText,
            extrasJson = extrasJson,
            timestamp = sbn.postTime,
            capturedAt = System.currentTimeMillis()
        )
        try {
            repository.processAndSave(rawNotification)
            Log.d(TAG, "Processed: $packageName | Title: $title")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process notification", e)
        }
    }
    private fun refreshActiveNotifications() {
        Log.d(TAG, "Manual refresh triggered")
        try {
            val activeNotifications = activeNotifications
            activeNotifications.forEach { sbn ->
                onNotificationPosted(sbn)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error refreshing active notifications", e)
        }
    }
    private fun shouldCapture(packageName: String): Boolean {
        return MONITORED_PACKAGES.contains(packageName)
    }
    private fun buildExtrasJson(extras: android.os.Bundle): String {
        return try {
            val json = org.json.JSONObject()
            val sensitiveKeys = setOf(
                "android.largeIcon", "android.picture", "android.icon",
                "android.wearable.EXTENSIONS", "android.people.list",
                "account_number", "card_number", "card_last_four", "balance"
            )
            for (key in extras.keySet()) {
                if (sensitiveKeys.any { key.equals(it, ignoreCase = true) }) continue
                val value = extras.get(key)
                if (value != null) {
                    val valueStr = value.toString()
                    // Basic sanity: skip extremely large strings that are likely bitmaps
                    if (valueStr.length < 2000) {
                        json.put(key, valueStr)
                    }
                }
            }
            json.toString()
        } catch (e: Exception) {
            "{}"
        }
    }
    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
        serviceJob.cancel() // Stop all active coroutines
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
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
    // Get pending review count for badge
    val reviewViewModel: ReviewViewModel = hiltViewModel()
    val pendingCount by reviewViewModel.pendingCount.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessage by reviewViewModel.errorMessage.collectAsState()
    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            reviewViewModel.clearError()
        }
    }
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
                    icon = { Icon(Icons.Default.List, contentDescription = "Categories") },
                    label = { Text("Categories") }
                )
                NavigationBarItem(
                    selected = selectedTab == 5,
                    onClick = { selectedTab = 5 },
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
                4 -> com.yourname.expensetracker.ui.screens.categories.CategoryScreen()
                5 -> DebugScreen()
            }
        }
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
                val chartEntryModel = entryModelOf(*entries.toTypedArray())
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Color(android.graphics.Color.parseColor(item.category.color)), CircleShape),
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
                color = Color(android.graphics.Color.parseColor(item.category.color)),
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
    init {
        // Observe expenses and categories, recompute on change
        viewModelScope.launch {
            combine(
                repository.getAllExpenses(),
                categoryRepository.allCategories
            ) { expenses, categories ->
                Pair(expenses, categories)
            }.collect { (expenses, categories) ->
                computeAnalytics(expenses, categories, _state.value.selectedPeriod)
            }
        }
    }
    fun selectPeriod(period: TimePeriod) {
        viewModelScope.launch {
            _state.update { it.copy(selectedPeriod = period, isLoading = true) }
            // Recompute
            val expenses = repository.getAllExpenses().first()
            val categories = categoryRepository.allCategories.first()
            computeAnalytics(expenses, categories, period)
        }
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
        val insights = insightsEngine.generateInsights()
        // Recurring
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
            val color = try {
                Color(android.graphics.Color.parseColor(category.color))
            } catch (e: Exception) {
                Color.Gray
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Permission check button
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
            // Test Button
            Button(
                onClick = { viewModel.simulateTestNotification() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiary
                )
            ) {
                Text("Simulate Purchase (€12.50)")
            }
            // Sync Button
            Button(
                onClick = { viewModel.triggerManualSync(context) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Text("Sync Active Notifications")
            }
            // Reset Expenses Button
            Button(
                onClick = { viewModel.resetExpenses() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Reset All Expenses")
            }
            Spacer(modifier = Modifier.height(8.dp))
            // ML Stats
            val classifierStats by viewModel.classifierStats.collectAsState()
            val sourceStatsList by viewModel.sourceStats.collectAsState()
            MlStatsSection(
                classifierStats = classifierStats,
                sourceStats = sourceStatsList,
                onRetrain = { viewModel.retrainClassifier() }
            )
            Spacer(modifier = Modifier.height(8.dp))
            // Package filter chips
            if (packages.isNotEmpty()) {
                LazyRow(
                    modifier = Modifier.padding(horizontal = 16.dp),
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
                Spacer(modifier = Modifier.height(8.dp))
            }
            // Blocked Apps Section
            val blockedApps by viewModel.blockedPackages.collectAsState()
            if (blockedApps.isNotEmpty()) {
                Text(
                    text = "Blocked Apps:",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.error
                )
                LazyRow(
                    modifier = Modifier.padding(horizontal = 16.dp),
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
                Spacer(modifier = Modifier.height(8.dp))
            }
            // Notification list
            if (notifications.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
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
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(notifications, key = { it.id }) { notification ->
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
    private val repository: NotificationRepository
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
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import java.text.SimpleDateFormat
import java.util.*
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel()
) {
    val state by viewModel.dashboard.collectAsState()
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
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
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(
                    try { Color(android.graphics.Color.parseColor(item.category.color)) } 
                    catch (e: Exception) { Color.Gray },
                    CircleShape
                ),
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
                color = try { Color(android.graphics.Color.parseColor(item.category.color)) }
                catch (e: Exception) { MaterialTheme.colorScheme.primary },
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
fun RecentExpenseRow(expense: com.yourname.expensetracker.data.database.entity.Expense) {
    val dateFormat = remember { SimpleDateFormat("MMM dd", Locale.getDefault()) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(expense.merchant, style = MaterialTheme.typography.bodyMedium)
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
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
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
    val recentExpenses: List<Expense> = emptyList()
)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {
    val dashboard: StateFlow<DashboardState> = combine(
        repository.getAllExpenses(),
        categoryRepository.allCategories
    ) { expenses, categories ->
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
        DashboardState(
            totalSpent = totalSpent,
            todaySpent = purchases.filter { it.date >= todayStart }.sumOf { it.amount },
            weekSpent = purchases.filter { it.date >= weekStart }.sumOf { it.amount },
            monthSpent = purchases.filter { it.date >= monthStart }.sumOf { it.amount },
            transactionCount = purchases.size,
            topCategories = topCategories,
            recentExpenses = purchases.take(5)
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DashboardState())
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
    Scaffold(
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
    var expenseToDelete by remember { mutableStateOf<Expense?>(null) }
    var expenseToCategorize by remember { mutableStateOf<Expense?>(null) }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Transactions") }
            )
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
        // Deletion Confirmation Dialog
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
                        color = category?.color?.let { Color(android.graphics.Color.parseColor(it)) } ?: Color.Gray,
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
                Text(
                    text = expense.merchant,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    maxLines = 1
                )
                Text(
                    text = category?.name ?: "Uncategorized",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.clickable { onEditCategory() }
                )
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
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
    }.stateIn(
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

## test\java\com\yourname\expensetracker\domain\parser\AppParserRegistryTest.kt <a name="testjavacomyournameexpensetrackerdomainparserappparserregistrytestkt"></a>
```kotlin
package com.yourname.expensetracker.domain.parser
import com.yourname.expensetracker.data.database.entity.TransactionType
import com.yourname.expensetracker.domain.parser.parsers.GoogleWalletParser
import com.yourname.expensetracker.domain.parser.parsers.GreekBankParser
import com.yourname.expensetracker.domain.parser.parsers.RevolutParser
import com.yourname.expensetracker.domain.parser.parsers.SmsParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
class AppParserRegistryTest {
    private val registry = AppParserRegistry(
        appParsers = listOf(
            RevolutParser(),
            GoogleWalletParser(),
            GreekBankParser(),
            SmsParser()
        ),
        fallbackParser = GenericTransactionParser()
    )
    @Test
    fun `test Revolut parsing`() {
        val result = registry.parse(
            title = "💳 €12.50 at SKLAVENITIS",
            text = "You paid €12.50 at SKLAVENITIS",
            bigText = null,
            subText = null,
            packageName = "com.revolut.revolut"
        )
        assertNotNull(result)
        assertEquals(12.50, result?.amount!!, 0.01)
        assertEquals("SKLAVENITIS", result.merchant)
        assertEquals(TransactionType.PURCHASE, result.type)
    }
    @Test
    fun `test Greek Bank parsing (NBG)`() {
        val result = registry.parse(
            title = "Πληρωμή",
            text = "Πληρώσατε €6,30 σε PIZZA HOOD",
            bigText = null,
            subText = null,
            packageName = "gr.nbg.mobilebanking"
        )
        assertNotNull(result)
        assertEquals(6.30, result?.amount!!, 0.01)
        assertEquals("PIZZA HOOD", result.merchant)
    }
    @Test
    fun `test Google Wallet parsing`() {
        val result = registry.parse(
            title = "COFFEE ISLAND",
            text = "€4.20 with Mastercard ••1234",
            bigText = null,
            subText = null,
            packageName = "com.google.android.apps.walletnfcrel"
        )
        assertNotNull(result)
        assertEquals(4.20, result?.amount!!, 0.01)
        assertEquals("COFFEE ISLAND", result.merchant)
    }
    @Test
    fun `test SMS Bank parsing`() {
        val result = registry.parse(
            title = "NBG",
            text = "AGORA 15,00 EUR STO KATASTIMA στις 07/02",
            bigText = null,
            subText = null,
            packageName = "com.google.android.apps.messaging"
        )
        assertNotNull(result)
        assertEquals(15.00, result?.amount!!, 0.01)
        assertEquals("KATASTIMA", result.merchant)
    }
    @Test
    fun `test generic fallback parsing`() {
        val result = registry.parse(
            title = "Transaction Alert",
            text = "You paid 50.00 EUR at Netflix",
            bigText = null,
            subText = null,
            packageName = "com.unknown.app"
        )
        assertNotNull(result)
        assertEquals(50.00, result?.amount!!, 0.01)
        assertEquals("Netflix", result.merchant)
    }
    @Test
    fun `test noise rejection (OTP)`() {
        val result = registry.parse(
            title = "Bank OTP",
            text = "Your verification code is 123456 for payment of 10.00",
            bigText = null,
            subText = null,
            packageName = "com.bank.app"
        )
        assertNull("Should reject OTP even if it contains 'payment' and numbers", result)
    }
}

```

---

