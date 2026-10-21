package com.yourname.expensetracker.domain.notification.capture

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.NotificationIntakeDao
import com.yourname.expensetracker.data.database.entity.NotificationIntakeEntity
import com.yourname.expensetracker.data.database.entity.NotificationIntakeStatus
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * F-R28-b / F-R28-d: the legacy payload purge updates must not touch an already purged row
 * (the original rawPayloadPurgedAt / updatedAt stay), and the repairer must count a repair
 * only when an update actually changed a row. REAL in-memory Room, synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class NotificationIntakePayloadRepairerPurgedRowTest {

    private lateinit var database: AppDatabase
    private lateinit var intakeDao: NotificationIntakeDao
    private lateinit var queryExecutor: ExecutorService
    private lateinit var transactionExecutor: ExecutorService
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val timeProvider = FakeTimeProvider(NOW)
    private val logs = mutableListOf<String>()
    private val tree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            logs += message
        }
    }

    @Before
    fun setup() {
        queryExecutor = Executors.newSingleThreadExecutor(daemonFactory("repairer-room-query"))
        transactionExecutor = Executors.newSingleThreadExecutor(daemonFactory("repairer-room-tx"))
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .setQueryExecutor(queryExecutor)
            .setTransactionExecutor(transactionExecutor)
            .build()
        intakeDao = database.notificationIntakeDao()
        Timber.plant(tree)
    }

    @After
    fun tearDown() {
        Timber.uproot(tree)
        database.close()
        queryExecutor.shutdownNow()
        transactionExecutor.shutdownNow()
    }

    private fun daemonFactory(name: String) = java.util.concurrent.ThreadFactory { runnable ->
        Thread(runnable, name).apply { isDaemon = true }
    }

    private fun repairer(dao: NotificationIntakeDao = intakeDao): NotificationIntakePayloadRepairer {
        val mode = mockk<RestoreMaintenanceMode>(relaxed = true)
        every { mode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        every { mode.isWritesAllowed() } returns true
        return NotificationIntakePayloadRepairer(
            intakeDao = dao,
            crypto = mockk<NotificationTransientPayloadCrypto>(relaxed = true),
            timeProvider = timeProvider,
            writeBarrier = DatabaseWriteBarrier(mode)
        )
    }

    private fun legacyTerminalRow(
        fingerprint: String,
        rawPayloadPurgedAt: Long? = null,
        updatedAt: Long = NOW
    ) = NotificationIntakeEntity(
        packageName = "com.example.bank",
        appName = "App",
        notificationKeyHash = "key-hash",
        postTime = NOW,
        capturedAt = NOW,
        source = "listener",
        correlationId = "corr-$fingerprint",
        dedupeFingerprint = fingerprint,
        contentHash = null,
        title = if (rawPayloadPurgedAt == null) "synthetic title" else null,
        text = if (rawPayloadPurgedAt == null) "synthetic text" else null,
        bigText = null,
        subText = null,
        extrasJson = null,
        rawStorageMode = "STORE_RAW",
        payloadMode = "TRANSIENT",
        rawPayloadPurgedAt = rawPayloadPurgedAt,
        status = NotificationIntakeStatus.PROCESSED.name,
        terminalAt = NOW - 1_000L,
        createdAt = NOW - 10_000L,
        updatedAt = updatedAt
    )

    private suspend fun insert(row: NotificationIntakeEntity): Long {
        val id = intakeDao.insertOrIgnore(row)
        assertTrue("row must insert", id > 0L)
        return id
    }

    @Test(timeout = 60_000)
    fun `purgeVisiblePayload does not update an already purged row`() = runBlocking {
        val id = insert(legacyTerminalRow("fp-purged", rawPayloadPurgedAt = FIRST_PURGE, updatedAt = FIRST_PURGE))

        val changed = intakeDao.purgeVisiblePayload(id, NOW)

        assertEquals("purged row must not be updated again", 0, changed)
        val row = intakeDao.getById(id)!!
        assertEquals(FIRST_PURGE, row.rawPayloadPurgedAt)
        assertEquals(FIRST_PURGE, row.updatedAt)
    }

    @Test(timeout = 60_000)
    fun `purgeRawPayload does not update an already purged row`() = runBlocking {
        val id = insert(legacyTerminalRow("fp-purged-raw", rawPayloadPurgedAt = FIRST_PURGE, updatedAt = FIRST_PURGE))

        val changed = intakeDao.purgeRawPayload(id, NOW)

        assertEquals(0, changed)
        assertEquals(FIRST_PURGE, intakeDao.getById(id)!!.rawPayloadPurgedAt)
    }

    @Test(timeout = 60_000)
    fun `encryptAndClearVisiblePayload does not write ciphertext to a purged row`() = runBlocking {
        val id = insert(legacyTerminalRow("fp-purged-enc", rawPayloadPurgedAt = FIRST_PURGE, updatedAt = FIRST_PURGE))

        val changed = intakeDao.encryptAndClearVisiblePayload(id, "cipher", "nonce", 1, NOW)

        assertEquals(0, changed)
        val row = intakeDao.getById(id)!!
        assertNull(row.transientPayloadCiphertext)
        assertEquals(FIRST_PURGE, row.updatedAt)
    }

    @Test(timeout = 60_000)
    fun `purgeVisiblePayload still purges an unpurged row exactly once`() = runBlocking {
        val id = insert(legacyTerminalRow("fp-fresh"))

        assertEquals(1, intakeDao.purgeVisiblePayload(id, NOW))
        assertEquals(0, intakeDao.purgeVisiblePayload(id, NOW + 5_000L))

        val row = intakeDao.getById(id)!!
        assertNull(row.title)
        assertNull(row.text)
        assertEquals(NOW, row.rawPayloadPurgedAt)
    }

    @Test(timeout = 60_000)
    fun `repairer purges an unpurged terminal row and counts one repair`() = runBlocking {
        val id = insert(legacyTerminalRow("fp-repair"))

        repairer().repairLegacyPlaintextTransientRows()

        val row = intakeDao.getById(id)!!
        assertNull(row.title)
        assertEquals(NOW, row.rawPayloadPurgedAt)
        assertTrue(logs.any { it.startsWith("Repaired 1 legacy rows") })
    }

    @Test(timeout = 60_000)
    fun `repairer does not count a repair when the purge update changed no row`() = runBlocking {
        // Race: the repairer read the row as unpurged, but it was purged before the update ran.
        val id = insert(legacyTerminalRow("fp-race", rawPayloadPurgedAt = FIRST_PURGE, updatedAt = FIRST_PURGE))
        val staleView = legacyTerminalRow("fp-race").copy(id = id)
        val dao = mockk<NotificationIntakeDao>(relaxed = true)
        var served = false
        coEvery { dao.getLegacyPlaintextTransientRows(any()) } answers {
            if (served) emptyList() else { served = true; listOf(staleView) }
        }
        coEvery { dao.purgeVisiblePayload(any(), any()) } coAnswers {
            intakeDao.purgeVisiblePayload(firstArg(), secondArg())
        }

        repairer(dao).repairLegacyPlaintextTransientRows()

        val row = intakeDao.getById(id)!!
        assertEquals("original purge timestamp must survive", FIRST_PURGE, row.rawPayloadPurgedAt)
        assertEquals(FIRST_PURGE, row.updatedAt)
        assertFalse("no repair may be counted", logs.any { it.startsWith("Repaired ") })
    }

    private companion object {
        const val NOW = 1_716_163_200_000L
        const val FIRST_PURGE = 1_716_000_000_000L
    }
}
