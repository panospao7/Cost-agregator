package com.yourname.expensetracker.domain.notification.capture

import com.yourname.expensetracker.data.database.dao.NotificationIntakeDao
import com.yourname.expensetracker.data.database.entity.NotificationIntakeEntity
import com.yourname.expensetracker.data.database.entity.NotificationIntakeStatus
import com.yourname.expensetracker.domain.util.TimeProvider
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NotificationIntakePayloadRepairer @Inject constructor(
    private val intakeDao: NotificationIntakeDao,
    private val crypto: NotificationTransientPayloadCrypto,
    private val timeProvider: TimeProvider
) {
    suspend fun repairLegacyPlaintextTransientRows() {
        var totalRepaired = 0
        var batches = 0
        val maxBatches = 100
        val now = timeProvider.now()

        while (batches < maxBatches) {
            val rows = intakeDao.getLegacyPlaintextTransientRows(100)
            if (rows.isEmpty()) break
            batches++

            Timber.d("Repairing ${rows.size} legacy plaintext transient rows (batch $batches)")
            for (row in rows) {
                try {
                    val isTerminal = row.terminalAt != null
                    if (isTerminal) {
                        intakeDao.purgeVisiblePayload(row.id, now)
                        totalRepaired++
                    } else if (row.title != null || row.text != null || row.bigText != null) {
                        val payload = NotificationTransientPayload(
                            title = row.title, text = row.text, bigText = row.bigText,
                            subText = row.subText, extrasJson = row.extrasJson
                        )
                        val encrypted = crypto.encrypt(payload)
                        intakeDao.encryptAndClearVisiblePayload(row.id, encrypted.ciphertext, encrypted.nonce, encrypted.version, now)
                        totalRepaired++
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Failed to repair legacy row ${row.id}")
                }
            }
        }
        if (totalRepaired > 0) Timber.d("Repaired $totalRepaired legacy rows in $batches batches")
    }
}
