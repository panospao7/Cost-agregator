package com.yourname.expensetracker.data.database.dao

import androidx.test.core.app.ApplicationProvider
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Category
import com.yourname.expensetracker.testfixtures.database.AppDatabaseTestFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Room-backed tests for [CategoryDao].
 *
 * `concurrent_get_or_insert_by_name_returns_one_category_without_supplemental_index`
 * deliberately stays on `runBlocking` (not `runTest`): it fans out 24 real
 * `Dispatchers.IO` workers guarded by `withTimeout(30_000L)`. Under `runTest`
 * that `withTimeout` runs on the virtual-time test scheduler, which skips
 * delays while the test body is suspended on `awaitAll`, so the 30 s timeout
 * would fire immediately instead of bounding real wall-clock time. `runBlocking`
 * keeps the timeout real and the concurrency identical to the original
 * instrumented test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CategoryDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var categoryDao: CategoryDao

    @Before
    fun setup() {
        database = AppDatabaseTestFactory.create(ApplicationProvider.getApplicationContext())
        categoryDao = database.categoryDao()
    }

    @After
    fun teardown() {
        if (::database.isInitialized) database.close()
    }

    private fun makeCategory(
        name: String,
        icon: String,
        color: String,
        isDefault: Boolean = false
    ) = Category(
        name = name,
        icon = icon,
        color = color,
        isDefault = isDefault
    )

    @Test
    fun insert_category_then_retrieve_by_id_returns_persisted_category() = runTest {
        val id = categoryDao.insert(
            makeCategory(name = "Groceries", icon = "🛒", color = "#4CAF50")
        )

        val stored = categoryDao.getById(id)

        assertTrue(id > 0)
        assertNotNull(stored)
        assertEquals(id, stored!!.id)
        assertEquals("Groceries", stored.name)
    }

    @Test
    fun query_all_categories_returns_defaults_first_then_custom_categories() = runTest {
        categoryDao.insert(makeCategory(name = "Food", icon = "🍔", color = "#E53935", isDefault = true))
        categoryDao.insert(makeCategory(name = "Transport", icon = "🚌", color = "#1E88E5", isDefault = true))
        categoryDao.insert(makeCategory(name = "Pets", icon = "🐾", color = "#8E24AA", isDefault = false))

        val all = categoryDao.getAll()

        assertEquals(3, all.size)
        assertTrue(all[0].isDefault)
        assertTrue(all[1].isDefault)
        assertTrue(!all[2].isDefault)
        assertEquals(listOf("Food", "Transport", "Pets"), all.map { it.name })
    }

    @Test
    fun update_category_name_icon_and_color_persists_changes() = runTest {
        val id = categoryDao.insert(
            makeCategory(name = "Bills", icon = "💳", color = "#546E7A")
        )

        val existing = categoryDao.getById(id)!!
        categoryDao.update(
            existing.copy(
                name = "Utilities",
                icon = "💡",
                color = "#FFB300"
            )
        )

        val updated = categoryDao.getById(id)
        assertNotNull(updated)
        assertEquals("Utilities", updated!!.name)
        assertEquals("💡", updated.icon)
        assertEquals("#FFB300", updated.color)
    }

    @Test
    fun get_or_insert_by_name_reuses_case_insensitive_category_without_overwriting_it() = runTest {
        val first = categoryDao.getOrInsertByNameNoCase(
            makeCategory(name = "Travel", icon = "T", color = "#3949AB")
        )
        val second = categoryDao.getOrInsertByNameNoCase(
            makeCategory(name = "travel", icon = "t", color = "#00897B")
        )

        assertTrue(first.id > 0)
        assertEquals(first, second)
        assertEquals(1, categoryDao.getCount())
        assertEquals(first, categoryDao.getByName("TRAVEL"))
    }

    @Test
    fun concurrent_get_or_insert_by_name_returns_one_category_without_supplemental_index() = runBlocking {
        database.openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'index' AND name = 'index_categories_name_nocase'"
        ).use { cursor ->
            assertTrue("fresh Room schema must not have the supplemental index", !cursor.moveToFirst())
        }
        val start = CompletableDeferred<Unit>()
        val saved = withTimeout(30_000L) {
            val requests = (0 until 24).map { index ->
                async(Dispatchers.IO) {
                    start.await()
                    categoryDao.getOrInsertByNameNoCase(
                        makeCategory(
                            name = if (index % 2 == 0) "Travel" else "travel",
                            icon = "T",
                            color = "#3949AB"
                        )
                    )
                }
            }
            start.complete(Unit)
            requests.awaitAll()
        }

        assertTrue(saved.all { it.id > 0 })
        assertEquals(1, saved.map { it.id }.distinct().size)
        assertEquals(1, categoryDao.getCount())
        assertEquals(1, categoryDao.existsByName("TRAVEL"))
    }

    @Test
    fun duplicate_category_name_is_handled_without_crash() = runTest {
        val firstId = categoryDao.insert(
            makeCategory(name = "Travel", icon = "✈️", color = "#3949AB")
        )
        val secondId = categoryDao.insert(
            makeCategory(name = "Travel", icon = "🚆", color = "#00897B")
        )

        val all = categoryDao.getAll()
        val byName = categoryDao.getByName("Travel")

        assertTrue(firstId > 0)
        assertTrue(secondId > 0)
        assertEquals(2, all.count { it.name == "Travel" })
        assertNotNull(byName)
        assertEquals("Travel", byName!!.name)
    }
}
