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
