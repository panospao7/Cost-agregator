package com.yourname.expensetracker.data.repository

import android.content.Context
import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import com.yourname.expensetracker.data.database.AppDatabase
import com.yourname.expensetracker.data.database.dao.BudgetDao
import com.yourname.expensetracker.data.database.dao.CategoryDao
import com.yourname.expensetracker.data.database.dao.ExpenseDao
import com.yourname.expensetracker.data.database.dao.MerchantCategoryDao
import com.yourname.expensetracker.data.database.dao.PendingReviewDao
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DatabaseBackupRepositoryImplTest {

    private val context = mockk<Context>(relaxed = true)
    private val database = mockk<AppDatabase>(relaxed = true)
    private val openHelper = mockk<SupportSQLiteOpenHelper>(relaxed = true)
    private val supportDb = mockk<SupportSQLiteDatabase>(relaxed = true)

    // Mock all DAOs with relaxed=true
    private val expenseDao = mockk<ExpenseDao>(relaxed = true)
    private val categoryDao = mockk<CategoryDao>(relaxed = true)
    private val merchantCategoryDao = mockk<MerchantCategoryDao>(relaxed = true)
    private val pendingReviewDao = mockk<PendingReviewDao>(relaxed = true)
    private val budgetDao = mockk<BudgetDao>(relaxed = true)

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: DatabaseBackupRepositoryImpl

    private lateinit var tempDir: File
    private lateinit var dbFile: File

    @Before
    fun setUp() {
        tempDir = createTempDir(prefix = "db-backup-repo-test-")
        dbFile = File(tempDir, "expense_tracker_db")

        every { context.filesDir } returns tempDir
        every { context.getDatabasePath(any()) } answers { File(tempDir, firstArg<String>()) }

        every { database.openHelper } returns openHelper
        every { openHelper.writableDatabase } returns supportDb
        every { supportDb.query("PRAGMA wal_checkpoint(FULL)") } answers { checkpointCursor(busyCode = 0) }

        every { database.expenseDao() } returns expenseDao
        every { database.categoryDao() } returns categoryDao
        every { database.merchantCategoryDao() } returns merchantCategoryDao
        every { database.pendingReviewDao() } returns pendingReviewDao
        every { database.budgetDao() } returns budgetDao

        coEvery { expenseDao.getTotalCount() } returns 0
        coEvery { categoryDao.getCount() } returns 0
        coEvery { merchantCategoryDao.getCount() } returns 0
        coEvery { pendingReviewDao.getPendingCount() } returns 0
        coEvery { budgetDao.getCount() } returns 0

        repository = DatabaseBackupRepositoryImpl(context, database, testDispatcher)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `backup creates file successfully`() = runTest(testDispatcher) {
        createSqliteDatabase(
            file = dbFile,
            expenseCount = 2,
            categoryCount = 1,
            merchantCount = 1,
            pendingCount = 1,
            budgetCount = 1
        )

        val result = repository.exportDatabase()

        assertTrue(result.isSuccess)
        val backupFile = result.getOrNull()
        assertTrue(backupFile != null)
        assertTrue(backupFile!!.exists())
        assertTrue(backupFile.length() > 0L)
        verify(atLeast = 1) { supportDb.query("PRAGMA wal_checkpoint(FULL)") }
    }

    @Test
    fun `restore from backup works`() = runTest(testDispatcher) {
        createSqliteDatabase(
            file = dbFile,
            expenseCount = 1,
            categoryCount = 1,
            merchantCount = 1,
            pendingCount = 1,
            budgetCount = 1
        )
        val sourceBackup = File(tempDir, "source_backup.db")
        createSqliteDatabase(
            file = sourceBackup,
            expenseCount = 3,
            categoryCount = 2,
            merchantCount = 4,
            pendingCount = 1,
            budgetCount = 2
        )

        coEvery { expenseDao.getTotalCount() } returns 3
        coEvery { categoryDao.getCount() } returns 2
        coEvery { merchantCategoryDao.getCount() } returns 4
        coEvery { pendingReviewDao.getPendingCount() } returns 1
        coEvery { budgetDao.getCount() } returns 2

        val result = repository.importDatabase(sourceBackup)

        assertTrue(result.isSuccess)
        assertEquals(3, countRows(dbFile, "expenses"))
        assertEquals(2, countRows(dbFile, "categories"))
        assertEquals(4, countRows(dbFile, "merchant_categories"))
    }

    @Test
    fun `rollback safety if restore fails original db preserved`() = runTest(testDispatcher) {
        createSqliteDatabase(
            file = dbFile,
            expenseCount = 1,
            categoryCount = 1,
            merchantCount = 1,
            pendingCount = 1,
            budgetCount = 1
        )
        val sourceBackup = File(tempDir, "source_backup_for_failure.db")
        createSqliteDatabase(
            file = sourceBackup,
            expenseCount = 5,
            categoryCount = 2,
            merchantCount = 2,
            pendingCount = 1,
            budgetCount = 1
        )

        // 1st call: checkpoint during safety backup -> success
        // 2nd call: reopen after file swap -> fail and trigger rollback
        // 3rd call: reopen during rollback restore -> success
        every { openHelper.writableDatabase } returns supportDb andThenThrows RuntimeException("forced reopen failure") andThen supportDb

        val result = repository.importDatabase(sourceBackup)

        assertTrue(result.isFailure)
        assertEquals(1, countRows(dbFile, "expenses"))
        assertEquals(1, countRows(dbFile, "categories"))
        assertTrue(
            result.exceptionOrNull()?.message?.contains("restored from safety backup") == true
        )
    }

    @Test
    fun `wal checkpoint helper works`() = runTest(testDispatcher) {
        createSqliteDatabase(
            file = dbFile,
            expenseCount = 1,
            categoryCount = 1,
            merchantCount = 1,
            pendingCount = 1,
            budgetCount = 1
        )

        val result = repository.createSafetyBackup()

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()?.exists() == true)
        verify(atLeast = 1) { supportDb.query("PRAGMA wal_checkpoint(FULL)") }
    }

    private fun checkpointCursor(busyCode: Int): MatrixCursor {
        return MatrixCursor(arrayOf("busy")).apply {
            addRow(arrayOf(busyCode))
        }
    }

    private fun createSqliteDatabase(
        file: File,
        schemaVersion: Int = 70,
        expenseCount: Int,
        categoryCount: Int,
        merchantCount: Int,
        pendingCount: Int,
        budgetCount: Int
    ) {
        file.parentFile?.mkdirs()
        val db = SQLiteDatabase.openOrCreateDatabase(file, null)
        try {
            db.execSQL("PRAGMA user_version = $schemaVersion")
            db.execSQL("CREATE TABLE IF NOT EXISTS expenses (id INTEGER PRIMARY KEY AUTOINCREMENT, amount REAL)")
            db.execSQL("CREATE TABLE IF NOT EXISTS categories (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)")
            db.execSQL("CREATE TABLE IF NOT EXISTS merchant_categories (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT)")
            db.execSQL("CREATE TABLE IF NOT EXISTS pending_reviews (id INTEGER PRIMARY KEY AUTOINCREMENT, status TEXT)")
            db.execSQL("CREATE TABLE IF NOT EXISTS budgets (id INTEGER PRIMARY KEY AUTOINCREMENT, amount REAL)")

            repeat(expenseCount) { db.execSQL("INSERT INTO expenses(amount) VALUES (${it + 1}.0)") }
            repeat(categoryCount) { db.execSQL("INSERT INTO categories(name) VALUES ('cat_$it')") }
            repeat(merchantCount) { db.execSQL("INSERT INTO merchant_categories(name) VALUES ('merchant_$it')") }
            repeat(pendingCount) { db.execSQL("INSERT INTO pending_reviews(status) VALUES ('PENDING')") }
            repeat(budgetCount) { db.execSQL("INSERT INTO budgets(amount) VALUES (${it + 10}.0)") }
        } finally {
            db.close()
        }
    }

    private fun countRows(file: File, table: String): Int {
        val db = SQLiteDatabase.openDatabase(file.absolutePath, null, SQLiteDatabase.OPEN_READONLY)
        return try {
            db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getInt(0) else 0
            }
        } finally {
            db.close()
        }
    }
}
