package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.UserCorrectionDao
import com.yourname.expensetracker.data.database.entity.UserCorrection
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserCorrectionRepository @Inject constructor(
    private val dao: UserCorrectionDao
) {
    suspend fun insert(correction: UserCorrection): Long =
        dao.insert(correction)

    suspend fun getAll(): List<UserCorrection> =
        dao.getAll()

    suspend fun getCount(): Int =
        dao.getCount()

    suspend fun getByPackage(packageName: String): List<UserCorrection> =
        dao.getByPackage(packageName)

    suspend fun getRejectionCount(packageName: String): Int =
        dao.getRejectionCount(packageName)

    suspend fun getTotalCorrections(packageName: String): Int =
        dao.getTotalCorrections(packageName)

    suspend fun getMostCommonMerchantCorrection(originalMerchant: String): String? =
        dao.getMostCommonMerchantCorrection(originalMerchant)

    suspend fun getMerchantTotalCorrections(merchant: String): Int =
        dao.getMerchantTotalCorrections(merchant)

    suspend fun getMerchantRejectionCount(merchant: String): Int =
        dao.getMerchantRejectionCount(merchant)

    suspend fun getMostCommonCategoryForMerchant(merchant: String): Long? =
        dao.getMostCommonCategoryForMerchant(merchant)

    suspend fun hasPreviousApprovals(merchant: String, packageName: String): Boolean =
        dao.hasPreviousApprovals(merchant, packageName)

    suspend fun deleteAll() =
        dao.deleteAll()
}
