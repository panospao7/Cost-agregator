package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.SplitTemplate
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

private const val FIXED_NOW = 1_710_000_000_000L

/**
 * Unit tests for [SplitTemplateDao.incrementUseCount].
 *
 * Verifies that the exact caller-supplied timestamp is persisted into
 * `updatedAt`, that `useCount` is incremented by exactly 1 per call, and that
 * only the targeted template row is mutated.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SplitTemplateDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var dao: SplitTemplateDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        dao = database.splitTemplateDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    private fun template(
        name: String = "Dinner 60/40",
        totalSplits: Int = 2,
        shares: String = """[{"participantIndex":0,"participantName":"Alice","percentage":60.0}]""",
        isDefault: Boolean = false,
        createdAt: Long = FIXED_NOW,
        updatedAt: Long = FIXED_NOW,
        useCount: Int = 0
    ): SplitTemplate = SplitTemplate(
        name = name,
        totalSplits = totalSplits,
        shares = shares,
        isDefault = isDefault,
        createdAt = createdAt,
        updatedAt = updatedAt,
        useCount = useCount
    )

    @Test
    fun `incrementUseCount increments by one and persists exact supplied timestamp`() = runTest {
        val id = dao.insertTemplate(template())

        // Deliberately non-round timestamp that differs from creation time.
        val incrementTimestamp = FIXED_NOW + 77_000L
        dao.incrementUseCount(id, incrementTimestamp)

        val loaded = dao.getTemplateById(id)
        assertNotNull(loaded)
        assertEquals(1, loaded.useCount)
        assertEquals(incrementTimestamp, loaded.updatedAt)
    }

    @Test
    fun `incrementUseCount accumulates across calls with last timestamp winning`() = runTest {
        val id = dao.insertTemplate(template())

        dao.incrementUseCount(id, FIXED_NOW + 1_000L)
        dao.incrementUseCount(id, FIXED_NOW + 2_000L)

        val loaded = dao.getTemplateById(id)
        assertNotNull(loaded)
        assertEquals(2, loaded.useCount)
        assertEquals(FIXED_NOW + 2_000L, loaded.updatedAt)
    }

    @Test
    fun `incrementUseCount uses caller timestamp even when older than creation`() = runTest {
        val id = dao.insertTemplate(template())

        // Timestamp strictly older than createdAt proves the DAO does not
        // substitute its own clock or default to creation time.
        val olderTimestamp = FIXED_NOW - 10_000L
        dao.incrementUseCount(id, olderTimestamp)

        val loaded = dao.getTemplateById(id)
        assertNotNull(loaded)
        assertEquals(1, loaded.useCount)
        assertEquals(olderTimestamp, loaded.updatedAt)
    }

    @Test
    fun `incrementUseCount only updates the targeted template`() = runTest {
        val firstId = dao.insertTemplate(template(name = "Dinner 60/40"))
        val secondId = dao.insertTemplate(template(name = "Groceries equal", totalSplits = 3))

        dao.incrementUseCount(firstId, FIXED_NOW + 500L)

        val first = dao.getTemplateById(firstId)
        val second = dao.getTemplateById(secondId)
        assertNotNull(first)
        assertNotNull(second)

        assertEquals(1, first.useCount)
        assertEquals(FIXED_NOW + 500L, first.updatedAt)

        assertEquals(0, second.useCount)
        assertEquals(FIXED_NOW, second.updatedAt)
    }
}
