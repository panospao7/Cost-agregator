package com.yourname.expensetracker.data.repository

import com.yourname.expensetracker.data.database.dao.SourceStatsDao
import com.yourname.expensetracker.data.database.entity.SourceStats
import javax.inject.Inject
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import javax.inject.Singleton

@Singleton
class SourceStatsRepository @Inject constructor(
    private val writeBarrier: DatabaseWriteBarrier,
    private val dao: SourceStatsDao
) {
    suspend fun insertIfNotExists(stats: SourceStats) {
        writeBarrier.checkWritesAllowed("SourceStatsRepository.insertIfNotExists")
        dao.insertIfNotExists(stats)
    }

    suspend fun getByPackage(packageName: String): SourceStats? =
        dao.getByPackage(packageName)

    suspend fun getAll(): List<SourceStats> =
        dao.getAll()

    suspend fun incrementTotal(packageName: String, now: Long) {
        writeBarrier.checkWritesAllowed("SourceStatsRepository.incrementTotal")
        dao.incrementTotal(packageName, now)
    }

    suspend fun incrementAccepted(packageName: String) {
        writeBarrier.checkWritesAllowed("SourceStatsRepository.incrementAccepted")
        dao.incrementAccepted(packageName)
    }

    suspend fun incrementRejected(packageName: String) {
        writeBarrier.checkWritesAllowed("SourceStatsRepository.incrementRejected")
        dao.incrementRejected(packageName)
    }

    suspend fun incrementAutoRejected(packageName: String) {
        writeBarrier.checkWritesAllowed("SourceStatsRepository.incrementAutoRejected")
        dao.incrementAutoRejected(packageName)
    }

    suspend fun incrementPending(packageName: String) {
        writeBarrier.checkWritesAllowed("SourceStatsRepository.incrementPending")
        dao.incrementPending(packageName)
    }

    suspend fun incrementDuplicate(packageName: String) {
        writeBarrier.checkWritesAllowed("SourceStatsRepository.incrementDuplicate")
        dao.incrementDuplicate(packageName)
    }

    suspend fun decrementPending(packageName: String) {
        writeBarrier.checkWritesAllowed("SourceStatsRepository.decrementPending")
        dao.decrementPending(packageName)
    }

    suspend fun resetAllPendingCounts() {
        writeBarrier.checkWritesAllowed("SourceStatsRepository.resetAllPendingCounts")
        dao.resetAllPendingCounts()
    }

    suspend fun deleteAll() {
        writeBarrier.checkWritesAllowed("SourceStatsRepository.deleteAll")
        dao.deleteAll()
    }
}
