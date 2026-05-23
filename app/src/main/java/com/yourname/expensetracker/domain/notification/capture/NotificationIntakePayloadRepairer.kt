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
        val now = timeProvider.now()
        val rows = intakeDao.getLegacyPlaintextTransientRows(100)
        if (rows.isEmpty()) return

        Timber.d("Repairing ${rows.size} legacy plaintext transient rows")
        for (row in rows) {
            try {
                val isTerminal = row.terminalAt != null
                if (isTerminal) {
                    // Terminal rows: just purge visible fields
                    intakeDao.purgeVisiblePayload(row.id, now)
                } else if (row.title != null || row.text != null || row.bigText != null) {
                    // Non-terminal with visible payload: encrypt then null visible fields
                    val payload = NotificationTransientPayload(
                        title = row.title, text = row.text, bigText = row.bigText,
                        subText = row.subText, extrasJson = row.extrasJson
                    )
                    val encrypted = crypto.encrypt(payload)
                    intakeDao.encryptAndClearVisiblePayload(row.id, encrypted.ciphertext, encrypted.nonce, encrypted.version, now)
                }
                // Non-terminal with no payload: nothing to repair
            } catch (e: Exception) {
                Timber.w(e, "Failed to repair legacy row ${row.id}")
            }
        }
    }
}
