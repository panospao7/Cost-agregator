package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.MatchStatus
import com.yourname.expensetracker.data.database.entity.ScannedReceipt
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the actual Room UPDATE, including NULL and stale-row predicates. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class RestoreImagePathDaoContractTest {
    private lateinit var database: AppDatabase
    private lateinit var dao: ScannedReceiptDao

    @Before
    fun setUp() {
        database = AppDatabase.inMemoryBuilder(ApplicationProvider.getApplicationContext()).build()
        dao = database.scannedReceiptDao()
    }

    @After
    fun tearDown() = database.close()

    private suspend fun insert(path: String?): Long = dao.insert(
        ScannedReceipt(
            imagePath = path, rawOcrText = "fixture", parsedTotal = 12.34,
            parsedMerchant = "fixture", parsedDate = 10L, parsedItems = null,
            parsedTaxAmount = null, confidence = 0.8f, createdAt = 10L, updatedAt = 10L
        )
    )

    @Test
    fun captured_null_pointer_is_a_valid_compare_and_set_identity() = runTest {
        val id = insert(null)
        val before = dao.getById(id)!!
        assertEquals(1, dao.updateImagePathIfUnchanged(id, null, "/restored/photo.jpg"))
        assertEquals(before.copy(imagePath = "/restored/photo.jpg"), dao.getById(id))
        assertEquals(0, dao.updateImagePathIfUnchanged(id, null, "/other/photo.jpg"))
    }

    @Test
    fun original_non_null_pointer_changes_once_and_repeat_is_a_noop() = runTest {
        val id = insert("/original/photo.jpg")
        assertEquals(1, dao.updateImagePathIfUnchanged(id, "/original/photo.jpg", "/restored/photo.jpg"))
        val restored = dao.getById(id)
        assertEquals(0, dao.updateImagePathIfUnchanged(id, "/original/photo.jpg", "/other/photo.jpg"))
        assertEquals(restored, dao.getById(id))
    }

    @Test
    fun concurrent_pointer_change_is_not_overwritten() = runTest {
        val id = insert("/original/photo.jpg")
        val snapshot = dao.getById(id)!!
        dao.update(snapshot.copy(imagePath = "/newer/photo.jpg"))
        val current = dao.getById(id)
        assertEquals(0, dao.updateImagePathIfUnchanged(id, snapshot.imagePath, "/restored/photo.jpg"))
        assertEquals(current, dao.getById(id))
    }

    @Test
    fun null_snapshot_cannot_overwrite_a_non_null_pointer_or_the_reverse() = runTest {
        val nonNullId = insert("/original/photo.jpg")
        val nullId = insert(null)
        assertEquals(0, dao.updateImagePathIfUnchanged(nonNullId, null, "/restored/photo.jpg"))
        assertEquals(0, dao.updateImagePathIfUnchanged(nullId, "/original/photo.jpg", "/restored/photo.jpg"))
        assertEquals("/original/photo.jpg", dao.getById(nonNullId)!!.imagePath)
        assertNull(dao.getById(nullId)!!.imagePath)
    }

    @Test
    fun concurrent_privacy_and_matching_updates_survive_path_only_repair() = runTest {
        val id = insert("/original/photo.jpg")
        val snapshot = dao.getById(id)!!
        val updated = snapshot.copy(
            rawOcrText = "", rawOcrTextPurgedAt = 20L, matchStatus = MatchStatus.REJECTED,
            parsedMerchant = "corrected", parsedTotal = 15.0, updatedAt = 20L
        )
        dao.update(updated)
        assertEquals(1, dao.updateImagePathIfUnchanged(id, snapshot.imagePath, "/restored/photo.jpg"))
        assertEquals(updated.copy(imagePath = "/restored/photo.jpg"), dao.getById(id))
    }

    @Test
    fun deletion_after_snapshot_and_missing_row_return_zero() = runTest {
        val id = insert("/original/photo.jpg")
        val snapshot = dao.getById(id)!!
        dao.deleteById(id)
        assertEquals(0, dao.updateImagePathIfUnchanged(id, snapshot.imagePath, "/restored/photo.jpg"))
        assertEquals(0, dao.updateImagePathIfUnchanged(Long.MAX_VALUE, null, "/restored/photo.jpg"))
        assertNull(dao.getById(id))
    }
}
