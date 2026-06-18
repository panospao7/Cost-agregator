package com.yourname.expensetracker.data.database.dao

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.entity.Category
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CategoryDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var categoryDao: CategoryDao

    @Before
    fun setup() {
        database = AppDatabase.inMemoryBuilder(
            ApplicationProvider.getApplicationContext()
        ).build()
        categoryDao = database.categoryDao()
    }

    @After
    fun teardown() {
        database.close()
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
    fun insert_category_then_retrieve_by_id_returns_persisted_category() = runBlocking {
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
    fun query_all_categories_returns_defaults_first_then_custom_categories() = runBlocking {
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
    fun update_category_name_icon_and_color_persists_changes() = runBlocking {
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
    fun duplicate_category_name_is_handled_without_crash() = runBlocking {
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
