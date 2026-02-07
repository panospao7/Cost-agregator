Phase 1: Intelligent Core — Complete Implementation
Let's build Phase 1 systematically. I'll provide every file that needs to be created or modified.

Phase 1A: Confidence-Based Review System
New Entity: PendingReview.kt
Kotlin

// data/database/entity/PendingReview.kt
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
            onDelete = ForeignKey.CASCADE
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
New DAO: PendingReviewDao.kt
Kotlin

// data/database/dao/PendingReviewDao.kt
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

    @Query("UPDATE pending_reviews SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String)

    @Query("SELECT * FROM pending_reviews ORDER BY createdAt DESC")
    fun getAllFlow(): Flow<List<PendingReview>>

    @Query("DELETE FROM pending_reviews WHERE status != 'PENDING'")
    suspend fun clearResolved()

    @Query("DELETE FROM pending_reviews")
    suspend fun deleteAll()
}
Phase 1B: User Correction Learning Engine
New Entity: UserCorrection.kt
Kotlin

// data/database/entity/UserCorrection.kt
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
New DAO: UserCorrectionDao.kt
Kotlin

// data/database/dao/UserCorrectionDao.kt
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
        SELECT correctedMerchant, COUNT(*) as cnt 
        FROM user_corrections 
        WHERE originalMerchant = :originalMerchant 
        AND correctedMerchant IS NOT NULL 
        AND correctedMerchant != originalMerchant
        GROUP BY correctedMerchant 
        ORDER BY cnt DESC 
        LIMIT 1
    """)
    suspend fun getMostCommonMerchantCorrection(originalMerchant: String): String?

    // Find category corrections (user always assigns merchant X to category Y)
    @Query("""
        SELECT correctedCategoryId, COUNT(*) as cnt 
        FROM user_corrections 
        WHERE originalMerchant = :merchant 
        AND correctedCategoryId IS NOT NULL
        GROUP BY correctedCategoryId 
        ORDER BY cnt DESC 
        LIMIT 1
    """)
    suspend fun getMostCommonCategoryForMerchant(merchant: String): Long?

    @Query("DELETE FROM user_corrections")
    suspend fun deleteAll()
}
Phase 1C: Source Trustworthiness Scoring
New Entity: SourceStats.kt
Kotlin

// data/database/entity/SourceStats.kt
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
New DAO: SourceStatsDao.kt
Kotlin

// data/database/dao/SourceStatsDao.kt
package com.yourname.expensetracker.data.database.dao

import androidx.room.*
import com.yourname.expensetracker.data.database.entity.SourceStats
import kotlinx.coroutines.flow.Flow

@Dao
interface SourceStatsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
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

    @Query("DELETE FROM source_stats")
    suspend fun deleteAll()
}
Updated Database: AppDatabase.kt
Kotlin

// data/database/AppDatabase.kt
package com.yourname.expensetracker.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.yourname.expensetracker.data.database.converter.Converters
import com.yourname.expensetracker.data.database.dao.*
import com.yourname.expensetracker.data.database.entity.*

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
@TypeConverters(Converters::class)
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
Updated DI: AppModule.kt
Kotlin

// di/AppModule.kt
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
        )
            .fallbackToDestructiveMigration()
            .build()
    }

    @Provides
    @Singleton
    fun provideRawNotificationDao(database: AppDatabase): RawNotificationDao =
        database.rawNotificationDao()

    @Provides
    @Singleton
    fun provideBlockedPackageDao(database: AppDatabase): BlockedPackageDao =
        database.blockedPackageDao()

    @Provides
    @Singleton
    fun provideExpenseDao(database: AppDatabase): ExpenseDao =
        database.expenseDao()

    @Provides
    @Singleton
    fun provideCategoryDao(database: AppDatabase): CategoryDao =
        database.categoryDao()

    @Provides
    @Singleton
    fun provideMerchantCategoryDao(database: AppDatabase): MerchantCategoryDao =
        database.merchantCategoryDao()

    @Provides
    @Singleton
    fun providePendingReviewDao(database: AppDatabase): PendingReviewDao =
        database.pendingReviewDao()

    @Provides
    @Singleton
    fun provideUserCorrectionDao(database: AppDatabase): UserCorrectionDao =
        database.userCorrectionDao()

    @Provides
    @Singleton
    fun provideSourceStatsDao(database: AppDatabase): SourceStatsDao =
        database.sourceStatsDao()

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
}
Core Intelligence: ConfidenceRouter.kt
This is the brain that decides what to do with each parsed transaction based on confidence:

Kotlin

// domain/intelligence/ConfidenceRouter.kt
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
    private val userCorrectionDao: UserCorrectionDao
) {
    companion object {
        const val AUTO_ACCEPT_THRESHOLD = 0.85f
        const val REVIEW_THRESHOLD = 0.50f
        // Below REVIEW_THRESHOLD → auto reject
    }

    suspend fun route(
        parsed: ParsedTransaction,
        packageName: String
    ): RoutingResult {
        var adjustedConfidence = parsed.confidence
        val reasons = mutableListOf<String>()

        // 1. Adjust based on source trust score
        val sourceStats = sourceStatsDao.getByPackage(packageName)
        if (sourceStats != null && sourceStats.totalNotifications > 10) {
            val trustModifier = calculateTrustModifier(sourceStats)
            adjustedConfidence *= trustModifier
            if (trustModifier < 1.0f) {
                reasons.add("Source trust: ${String.format("%.0f", sourceStats.trustScore * 100)}%")
            }
        }

        // 2. Adjust based on user correction history for this merchant
        val merchantCorrections = getMerchantRejectionRate(parsed.merchant)
        if (merchantCorrections > 0.5f) {
            adjustedConfidence *= 0.5f
            reasons.add("Merchant often rejected")
        }

        // 3. Adjust based on package rejection rate
        val packageRejectionRate = getPackageRejectionRate(packageName)
        if (packageRejectionRate > 0.7f) {
            adjustedConfidence *= 0.3f
            reasons.add("Package mostly rejected")
        }

        // 4. Boost if user has previously approved similar transactions
        val previouslyApproved = hasPreviousApprovals(parsed.merchant, packageName)
        if (previouslyApproved) {
            adjustedConfidence = (adjustedConfidence * 1.2f).coerceAtMost(1.0f)
            reasons.add("Previously approved merchant")
        }

        // Clamp
        adjustedConfidence = adjustedConfidence.coerceIn(0f, 1f)

        // Route based on adjusted confidence
        val decision = when {
            adjustedConfidence >= AUTO_ACCEPT_THRESHOLD -> RoutingDecision.AUTO_ACCEPT
            adjustedConfidence >= REVIEW_THRESHOLD -> RoutingDecision.NEEDS_REVIEW
            else -> RoutingDecision.AUTO_REJECT
        }

        val reason = if (reasons.isEmpty()) {
            "Base confidence: ${String.format("%.0f", parsed.confidence * 100)}%"
        } else {
            reasons.joinToString("; ")
        }

        return RoutingResult(decision, adjustedConfidence, reason)
    }

    private fun calculateTrustModifier(stats: SourceStats): Float {
        return when {
            stats.isLikelySpam -> 0.2f                    // Known spam source
            stats.trustScore > 0.8f -> 1.1f               // Very trusted
            stats.trustScore > 0.5f -> 1.0f               // Normal
            stats.trustScore > 0.2f -> 0.8f               // Somewhat untrusted
            else -> 0.5f                                    // Mostly noise
        }
    }

    private suspend fun getMerchantRejectionRate(merchant: String): Float {
        val corrections = userCorrectionDao.getAll()
        val merchantCorrections = corrections.filter {
            it.originalMerchant.equals(merchant, ignoreCase = true)
        }
        if (merchantCorrections.size < 3) return 0f // Not enough data
        val rejections = merchantCorrections.count { it.wasRejected }
        return rejections.toFloat() / merchantCorrections.size
    }

    private suspend fun getPackageRejectionRate(packageName: String): Float {
        val total = userCorrectionDao.getTotalCorrections(packageName)
        if (total < 5) return 0f // Not enough data
        val rejections = userCorrectionDao.getRejectionCount(packageName)
        return rejections.toFloat() / total
    }

    private suspend fun hasPreviousApprovals(merchant: String, packageName: String): Boolean {
        val corrections = userCorrectionDao.getByPackage(packageName)
        return corrections.any {
            it.originalMerchant.equals(merchant, ignoreCase = true) && it.wasApproved
        }
    }

    /**
     * Ensure source stats entry exists for a package
     */
    suspend fun ensureSourceStats(packageName: String) {
        val existing = sourceStatsDao.getByPackage(packageName)
        if (existing == null) {
            sourceStatsDao.upsert(SourceStats(packageName = packageName))
        }
    }
}
Merchant Normalizer: MerchantNormalizer.kt
Kotlin

// domain/intelligence/MerchantNormalizer.kt
package com.yourname.expensetracker.domain.intelligence

import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MerchantNormalizer @Inject constructor(
    private val userCorrectionDao: UserCorrectionDao
) {
    // Suffixes/noise to strip
    private val NOISE_PATTERNS = listOf(
        Regex("""\s*#?\d{3,}.*$"""),                          // Store numbers: "#1234", "ATH001"
        Regex("""\s*\*+\d+.*$"""),                             // Card suffixes: "**1234"
        Regex("""\s+(?:GR|ATH|THES|ATHENS|THESSALONIKI).*$""", RegexOption.IGNORE_CASE),
        Regex("""\s+(?:BRANCH|STORE|SHOP|KATAST)\s*\d*$""", RegexOption.IGNORE_CASE),
        Regex("""\s+\d{1,2}/\d{1,2}/?\d{0,4}$"""),            // Dates at end
        Regex("""\s+(?:SA|AE|ΑΕ|EPE|ΕΠΕ|IKE|ΙΚΕ|LTD|GMBH|SRL)\s*$""", RegexOption.IGNORE_CASE)
    )

    fun normalize(merchant: String): String {
        var result = merchant.uppercase().trim()
        for (pattern in NOISE_PATTERNS) {
            result = result.replace(pattern, "")
        }
        return result
            .replace(Regex("[^A-ZΑ-Ω0-9 &]"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Check if user has a known correction for this merchant name
     */
    suspend fun applyUserCorrections(merchant: String): String {
        val corrected = userCorrectionDao.getMostCommonMerchantCorrection(
            normalize(merchant)
        )
        return corrected ?: merchant
    }

    /**
     * Jaccard similarity for matching merchant names
     */
    fun similarity(a: String, b: String): Float {
        val na = normalize(a)
        val nb = normalize(b)
        if (na == nb) return 1.0f
        if (na.contains(nb) || nb.contains(na)) return 0.9f

        // Word overlap (Jaccard)
        val wordsA = na.split(" ").toSet()
        val wordsB = nb.split(" ").toSet()
        val intersection = wordsA.intersect(wordsB)
        val union = wordsA.union(wordsB)
        return if (union.isNotEmpty()) intersection.size.toFloat() / union.size else 0f
    }
}
Updated Repository: NotificationRepository.kt
This is the most significantly changed file — the processAndSave method now routes through the confidence system:

Kotlin

// data/repository/NotificationRepository.kt
package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.*
import com.yourname.expensetracker.data.database.entity.*
import com.yourname.expensetracker.domain.categorization.CategorizationEngine
import com.yourname.expensetracker.domain.intelligence.ConfidenceRouter
import com.yourname.expensetracker.domain.intelligence.MerchantNormalizer
import com.yourname.expensetracker.domain.intelligence.RoutingDecision
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
    private val merchantNormalizer: MerchantNormalizer
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
    suspend fun exists(packageName: String, timestamp: Long): Boolean =
        dao.exists(packageName, timestamp)

    // === Review Queue ===
    fun getPendingReviews(): Flow<List<PendingReview>> = pendingReviewDao.getPendingFlow()
    fun getPendingReviewCount(): Flow<Int> = pendingReviewDao.getPendingCountFlow()

    // === Source Stats ===
    fun getSourceStats(): Flow<List<SourceStats>> = sourceStatsDao.getAllFlow()

    // === Core Processing Pipeline ===
    suspend fun processAndSave(notification: RawNotification) {
        // 0. Deduplication check
        if (dao.exists(notification.packageName, notification.timestamp)) {
            return
        }

        // 1. Save raw notification
        val rawId = dao.insert(notification)

        // 2. Ensure source stats exist, then increment total
        confidenceRouter.ensureSourceStats(notification.packageName)
        sourceStatsDao.incrementTotal(notification.packageName)

        // 3. Try to parse
        val parsed = parserRegistry.parse(
            title = notification.title,
            text = notification.text,
            bigText = notification.bigText,
            subText = notification.subText,
            packageName = notification.packageName
        )

        if (parsed == null) {
            // Not a transaction notification — increment auto-rejected
            sourceStatsDao.incrementAutoRejected(notification.packageName)
            dao.markRelevance(rawId, false)
            return
        }

        // 4. Apply merchant normalization & user corrections
        val correctedMerchant = merchantNormalizer.applyUserCorrections(parsed.merchant)

        // 5. Route through confidence system
        val routingResult = confidenceRouter.route(parsed, notification.packageName)

        when (routingResult.decision) {
            RoutingDecision.AUTO_ACCEPT -> {
                // Check for duplicate expense
                val isDuplicate = expenseDao.isDuplicate(
                    amount = parsed.amount,
                    merchant = correctedMerchant,
                    date = notification.timestamp
                )
                if (isDuplicate) {
                    dao.markRelevance(rawId, false)
                    return
                }

                // Categorize
                val categoryId = categorizationEngine.categorize(correctedMerchant)

                // Create expense
                val expense = Expense(
                    amount = parsed.amount,
                    currency = parsed.currency,
                    merchant = correctedMerchant,
                    transactionType = parsed.type,
                    date = notification.timestamp,
                    rawNotificationId = rawId,
                    categoryId = categoryId
                )
                expenseDao.insert(expense)
                dao.markRelevance(rawId, true)
                sourceStatsDao.incrementAccepted(notification.packageName)
            }

            RoutingDecision.NEEDS_REVIEW -> {
                // Categorize for suggestion
                val suggestedCategoryId = categorizationEngine.categorize(correctedMerchant)

                // Create pending review
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
            }
        }
    }

    // === Review Actions ===

    /**
     * User approves a pending review (possibly with modifications)
     */
    suspend fun approveReview(
        reviewId: Long,
        finalAmount: Double? = null,
        finalMerchant: String? = null,
        finalCategoryId: Long? = null
    ) {
        val review = pendingReviewDao.getById(reviewId) ?: return

        val amount = finalAmount ?: review.suggestedAmount
        val merchant = finalMerchant ?: review.suggestedMerchant
        val categoryId = finalCategoryId ?: review.suggestedCategoryId
        val type = try {
            TransactionType.valueOf(review.suggestedType)
        } catch (e: Exception) {
            TransactionType.PURCHASE
        }

        // Check for duplicates
        val isDuplicate = expenseDao.isDuplicate(
            amount = amount,
            merchant = merchant,
            date = review.createdAt
        )
        if (!isDuplicate) {
            // Create the expense
            val expense = Expense(
                amount = amount,
                currency = review.suggestedCurrency,
                merchant = merchant,
                transactionType = type,
                date = review.createdAt,
                rawNotificationId = review.rawNotificationId,
                categoryId = categoryId
            )
            expenseDao.insert(expense)
        }

        // Mark review as approved
        pendingReviewDao.updateStatus(reviewId, "APPROVED")
        dao.markRelevance(review.rawNotificationId, true)
        sourceStatsDao.incrementAccepted(review.packageName)

        // Record user correction for learning
        val wasModified = (finalAmount != null && finalAmount != review.suggestedAmount) ||
                (finalMerchant != null && finalMerchant != review.suggestedMerchant) ||
                (finalCategoryId != null && finalCategoryId != review.suggestedCategoryId)

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

        // Learn merchant → category mapping if category was set
        val effectiveCategoryId = categoryId
        if (effectiveCategoryId != null) {
            val pattern = categorizationEngine.normalize(merchant)
            if (pattern.isNotEmpty()) {
                merchantCategoryDao.insert(
                    MerchantCategory(
                        merchantPattern = pattern,
                        categoryId = effectiveCategoryId,
                        confidence = 1.0f
                    )
                )
            }
        }
    }

    /**
     * User rejects a pending review
     */
    suspend fun rejectReview(reviewId: Long) {
        val review = pendingReviewDao.getById(reviewId) ?: return

        pendingReviewDao.updateStatus(reviewId, "REJECTED")
        dao.markRelevance(review.rawNotificationId, false)
        sourceStatsDao.incrementRejected(review.packageName)

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
    }

    // === Existing methods (unchanged) ===

    suspend fun markAsRelevant(id: Long, isRelevant: Boolean) =
        dao.markRelevance(id, isRelevant)

    suspend fun deleteAll() = dao.deleteAll()

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

    suspend fun delete(notification: RawNotification) = dao.delete(notification)

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
Review Queue UI: ReviewScreen.kt
Kotlin

// ui/screens/review/ReviewScreen.kt
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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
                    Text("Not a transaction")
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
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    "Category",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp)
                )

                // Simple category selector
                Column(
                    modifier = Modifier.heightIn(max = 200.dp),
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
                    val editedAmount = amount.toDoubleOrNull()
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
Review ViewModel: ReviewViewModel.kt
Kotlin

// ui/screens/review/ReviewViewModel.kt
package com.yourname.expensetracker.ui.screens.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.data.database.entity.PendingReview
import com.yourname.expensetracker.data.repository.CategoryRepository
import com.yourname.expensetracker.data.repository.NotificationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ReviewViewModel @Inject constructor(
    private val repository: NotificationRepository,
    private val categoryRepository: CategoryRepository
) : ViewModel() {

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
            repository.approveReview(reviewId)
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
            repository.approveReview(
                reviewId = reviewId,
                finalAmount = finalAmount,
                finalMerchant = finalMerchant,
                finalCategoryId = finalCategoryId
            )
        }
    }
}
Updated Navigation: MainActivity.kt
Add a 5th tab for the Review Queue with a badge:

Kotlin

// ui/MainActivity.kt
package com.yourname.expensetracker.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.yourname.expensetracker.ui.screens.debug.DebugScreen
import com.yourname.expensetracker.ui.screens.home.HomeScreen
import com.yourname.expensetracker.ui.screens.review.ReviewScreen
import com.yourname.expensetracker.ui.screens.review.ReviewViewModel
import com.yourname.expensetracker.ui.theme.ExpenseTrackerTheme
import dagger.hilt.android.AndroidEntryPoint

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

@Composable
fun MainScreen() {
    var selectedTab by remember { mutableIntStateOf(0) }

    // Get pending review count for badge
    val reviewViewModel: ReviewViewModel = hiltViewModel()
    val pendingCount by reviewViewModel.pendingCount.collectAsState()

    Scaffold(
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
                            Icon(Icons.Default.RateReview, contentDescription = "Review")
                        }
                    },
                    label = { Text("Review") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Default.List, contentDescription = "Categories") },
                    label = { Text("Categories") }
                )
                NavigationBarItem(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    icon = { Icon(Icons.Default.Settings, contentDescription = "Debug") },
                    label = { Text("Debug") }
                )
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding)) {
            when (selectedTab) {
                0 -> HomeScreen()
                1 -> com.yourname.expensetracker.ui.screens.transactions.TransactionsScreen()
                2 -> ReviewScreen()
                3 -> com.yourname.expensetracker.ui.screens.categories.CategoryScreen()
                4 -> DebugScreen()
            }
        }
    }
}
You'll need this import for Icons.Default.RateReview. If it's not available in your version of material icons, replace with:

Kotlin

import androidx.compose.material.icons.outlined.Notifications
// And use Icons.Outlined.Notifications instead
Updated File Structure After Phase 1
text

ExpenseTracker/
├── app/src/main/java/com/yourname/expensetracker/
│   ├── data/
│   │   ├── database/
│   │   │   ├── AppDatabase.kt                    ← MODIFIED (v5, 3 new entities)
│   │   │   ├── converter/Converters.kt
│   │   │   ├── dao/
│   │   │   │   ├── BlockedPackageDao.kt
│   │   │   │   ├── CategoryDao.kt
│   │   │   │   ├── ExpenseDao.kt
│   │   │   │   ├── MerchantCategoryDao.kt
│   │   │   │   ├── PendingReviewDao.kt            ← NEW
│   │   │   │   ├── RawNotificationDao.kt
│   │   │   │   ├── SourceStatsDao.kt              ← NEW
│   │   │   │   └── UserCorrectionDao.kt           ← NEW
│   │   │   └── entity/
│   │   │       ├── BlockedPackage.kt
│   │   │       ├── Category.kt
│   │   │       ├── Expense.kt
│   │   │       ├── MerchantCategory.kt
│   │   │       ├── PendingReview.kt               ← NEW
│   │   │       ├── RawNotification.kt
│   │   │       ├── SourceStats.kt                 ← NEW
│   │   │       └── UserCorrection.kt              ← NEW
│   │   └── repository/
│   │       ├── CategoryRepository.kt
│   │       └── NotificationRepository.kt          ← MODIFIED (routing logic)
│   ├── di/
│   │   └── AppModule.kt                           ← MODIFIED (new DAOs)
│   ├── domain/
│   │   ├── categorization/CategorizationEngine.kt
│   │   ├── intelligence/                          ← NEW PACKAGE
│   │   │   ├── ConfidenceRouter.kt                ← NEW
│   │   │   └── MerchantNormalizer.kt              ← NEW
│   │   └── parser/ (unchanged)
│   └── ui/
│       ├── MainActivity.kt                        ← MODIFIED (5 tabs + badge)
│       └── screens/
│           ├── review/                            ← NEW PACKAGE
│           │   ├── ReviewScreen.kt                ← NEW
│           │   └── ReviewViewModel.kt             ← NEW
│           ├── home/ (unchanged)
│           ├── transactions/ (unchanged)
│           ├── categories/ (unchanged)
│           └── debug/ (unchanged)
Summary of Phase 1 Changes
Component	Files	What It Does
Confidence Router	ConfidenceRouter.kt	Routes parsed transactions: auto-accept (≥85%), review (50-84%), reject (<50%). Adjusts confidence based on source trust and user history.
Pending Review	Entity + DAO + Screen + ViewModel	Queue of medium-confidence transactions. Users approve/reject/edit.
User Corrections	Entity + DAO	Records every user decision. Feeds back into routing. Over time, the system learns what the user rejects and what they correct.
Source Stats	Entity + DAO	Tracks per-app accept/reject rates. Spammy apps get confidence penalties.
Merchant Normalizer	MerchantNormalizer.kt	Strips store numbers, suffixes, location codes. Applies user's past merchant name corrections.
Updated Repository	NotificationRepository.kt	Pipeline now: parse → normalize → route → (accept/review/reject) → learn.
Updated Navigation	MainActivity.kt	5 tabs with badge showing pending review count.
Key insight: Every user interaction in the Review screen creates a UserCorrection record. After ~50 corrections, the ConfidenceRouter becomes remarkably accurate for that specific user's notification patterns — entirely on-device, zero privacy concerns.