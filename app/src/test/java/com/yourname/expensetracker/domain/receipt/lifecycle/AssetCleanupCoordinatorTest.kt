package com.yourname.expensetracker.domain.receipt.lifecycle

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.backup.DatabaseWriteBarrier
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.RoomDomainTransactionRunner
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import com.yourname.expensetracker.domain.util.TimeProvider
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * RP-12 12c (P3-008): asset-ownership cleanup.
 *
 *  - an unreferenced uncommitted asset is deleted exactly once;
 *  - a path referenced by ANY receipt row is never deleted;
 *  - cleanup is best-effort: store failures and blocked writes return false
 *    without throwing;
 *  - blank paths are a no-op.
 *
 * Real in-memory Room + real [RoomDomainTransactionRunner] + the real
 * [ReceiptAssetStore] against a real file — no relaxed stubs in the path.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AssetCleanupCoordinatorTest {

    private lateinit var database: AppDatabase
    private lateinit var context: Context

    private val now = 1_700_000_000_000L
    private val timeProvider: TimeProvider = object : TimeProvider { override fun now() = now }

    private val maintenanceMode = mockk<RestoreMaintenanceMode>()

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        every { maintenanceMode.isWritesAllowed() } returns true
    }

    @After
    fun tearDown() {
        database.close()
    }

    private fun coordinator(assetStore: ReceiptAssetStore = ReceiptAssetStore(context, timeProvider)) =
        AssetCleanupCoordinator(
            scannedReceiptDao = database.scannedReceiptDao(),
            assetStore = assetStore,
            writeBarrier = DatabaseWriteBarrier(maintenanceMode),
            transactionRunner = RoomDomainTransactionRunner(database, timeProvider)
        )

    private fun newAssetFile(): File {
        val dir = File(context.filesDir, "receipts").also { it.mkdirs() }
        return File(dir, "cleanup-${System.nanoTime()}.jpg").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
    }

    private fun receipt(imagePath: String) = ScannedReceipt(
        imagePath = imagePath,
        rawOcrText = "OCR",
        parsedTotal = 1.0,
        parsedMerchant = null,
        parsedDate = now,
        parsedItems = null,
        parsedTaxAmount = null,
        confidence = 0.9f,
        createdAt = now,
        updatedAt = now
    )

    @Test
    fun `unreferenced uncommitted asset is deleted`() = runTest {
        val file = newAssetFile()
        val result = coordinator().cleanupUncommittedAsset(
            path = file.absolutePath, attemptId = "attempt-1", reason = "TEST"
        )
        assertTrue("expected deletion, got false", result)
        assertFalse("file must be gone", file.exists())
    }

    @Test
    fun `path referenced by a receipt row is never deleted`() = runTest {
        val file = newAssetFile()
        database.scannedReceiptDao().insert(receipt(file.absolutePath))

        val result = coordinator().cleanupUncommittedAsset(
            path = file.absolutePath, attemptId = "attempt-1", reason = "TEST"
        )

        assertFalse("referenced asset must survive", result)
        assertTrue("file must still exist", file.exists())
        assertEquals(1, database.scannedReceiptDao().countReferencesToImagePath(file.absolutePath))
    }

    @Test
    fun `asset store failure is best-effort and does not throw`() = runTest {
        val file = newAssetFile()
        val failingStore = mockk<ReceiptAssetStore>()
        every { failingStore.deleteAsset(any()) } throws RuntimeException("disk gone")

        val result = coordinator(failingStore).cleanupUncommittedAsset(
            path = file.absolutePath, attemptId = "attempt-1", reason = "TEST"
        )

        assertFalse(result)
    }

    @Test
    fun `blocked write barrier refuses cleanup without throwing`() = runTest {
        val file = newAssetFile()
        every { maintenanceMode.isWritesAllowed() } returns false
        every { maintenanceMode.currentMode() } returns RestoreMaintenanceMode.Mode.RESTORE_PREPARING

        val result = coordinator().cleanupUncommittedAsset(
            path = file.absolutePath, attemptId = "attempt-1", reason = "TEST"
        )

        assertFalse(result)
        assertTrue(file.exists())
    }

    @Test
    fun `blank path is a no-op`() = runTest {
        assertFalse(coordinator().cleanupUncommittedAsset(null, "a", "TEST"))
        assertFalse(coordinator().cleanupUncommittedAsset("", "a", "TEST"))
    }
}
