package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.SourceStatsDao
import com.yourname.expensetracker.data.database.entity.SourceStats
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourceStatsRepository @Inject constructor(
    private val dao: SourceStatsDao
) {
    suspend fun insertIfNotExists(stats: SourceStats) =
        dao.insertIfNotExists(stats)

    suspend fun getByPackage(packageName: String): SourceStats? =
        dao.getByPackage(packageName)

    suspend fun getAll(): List<SourceStats> =
        dao.getAll()

    suspend fun incrementTotal(packageName: String, now: Long) =
        dao.incrementTotal(packageName, now)

    suspend fun incrementAccepted(packageName: String) =
        dao.incrementAccepted(packageName)

    suspend fun incrementRejected(packageName: String) =
        dao.incrementRejected(packageName)

    suspend fun incrementAutoRejected(packageName: String) =
        dao.incrementAutoRejected(packageName)

    suspend fun incrementPending(packageName: String) =
        dao.incrementPending(packageName)

    suspend fun incrementDuplicate(packageName: String) =
        dao.incrementDuplicate(packageName)

    suspend fun decrementPending(packageName: String) =
        dao.decrementPending(packageName)

    suspend fun resetAllPendingCounts() =
        dao.resetAllPendingCounts()

    suspend fun deleteAll() =
        dao.deleteAll()
}
