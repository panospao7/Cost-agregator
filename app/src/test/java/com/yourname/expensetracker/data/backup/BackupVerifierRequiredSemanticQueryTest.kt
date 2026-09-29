package com.yourname.expensetracker.data.backup

import android.database.MatrixCursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteException
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class BackupVerifierRequiredSemanticQueryTest {
    private val semanticSql =
        "SELECT COUNT(*) FROM receipt_expense_links WHERE expenseId NOT IN (SELECT id FROM expenses)"
    private val expectedCounts = BackupVerifier.allTableNames().associateWith { 0 }

    @Test
    fun completeEmptyDatabasePassesIncludingAllRequiredQueries() {
        val db = completeDatabase()
        try {
            val summary = BackupVerifier.verify(db, expectedCounts)
            assertTrue(summary.errors.toString(), summary.passed)
        } finally {
            db.close()
        }
    }

    @Test
    fun eachMissingRequiredReferenceColumnFailsVerification() {
        for (table in listOf("receipt_expense_links", "recurring_occurrences", "budget_forecasts")) {
            val db = completeDatabase(table)
            try {
                val summary = BackupVerifier.verify(db, expectedCounts)
                assertFalse(summary.passed)
                assertTrue(summary.errors.any {
                    it.startsWith("Required semantic integrity check failed:") && it.contains(table)
                })
                assertEquals(0, summary.totalTablesFailed)
            } finally {
                db.close()
            }
        }
    }

    @Test
    fun queryExceptionFailsWithControlledErrorWithoutPayload() {
        val db = completeDatabase()
        try {
            val proxy = queryProxy(db)
            every { proxy.rawQuery(semanticSql, null) } throws SQLiteException("SECRET /private/account.db")
            val summary = BackupVerifier.verify(proxy, expectedCounts)
            assertFalse(summary.passed)
            assertTrue(summary.errors.any { it.startsWith("Required semantic integrity check failed:") })
            assertFalse(summary.errors.any { it.contains("SECRET") || it.contains("/private/") })
        } finally {
            db.close()
        }
    }

    @Test
    fun emptyNullAndNegativeCountResultsCannotPassAsZero() {
        val db = completeDatabase()
        try {
            for (rows in listOf(emptyList<Int?>(), listOf<Int?>(null), listOf<Int?>(-1))) {
                val cursor = MatrixCursor(arrayOf("count"))
                rows.forEach { cursor.addRow(arrayOf<Any?>(it)) }
                val proxy = queryProxy(db)
                every { proxy.rawQuery(semanticSql, null) } returns cursor
                val summary = BackupVerifier.verify(proxy, expectedCounts)
                assertFalse(summary.passed)
                assertTrue(summary.errors.any { it.startsWith("Required semantic integrity check failed:") })
                assertTrue(cursor.isClosed)
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun requiredQueryCancellationPropagatesUnchanged() {
        val db = completeDatabase()
        try {
            val proxy = queryProxy(db)
            val cancellation = CancellationException("test cancellation")
            every { proxy.rawQuery(semanticSql, null) } throws cancellation
            val actual = assertThrows(CancellationException::class.java) {
                BackupVerifier.verify(proxy, expectedCounts)
            }
            assertSame(cancellation, actual)
        } finally {
            db.close()
        }
    }

    private fun queryProxy(db: SQLiteDatabase): SQLiteDatabase = mockk<SQLiteDatabase>().also { proxy ->
        every { proxy.rawQuery(any<String>(), any()) } answers {
            db.rawQuery(firstArg(), secondArg())
        }
    }

    private fun completeDatabase(missingColumnTable: String? = null): SQLiteDatabase {
        val db = SQLiteDatabase.create(null)
        for (table in BackupVerifier.allTableNames()) {
            val reference = if (table == missingColumnTable) "" else when (table) {
                "receipt_expense_links" -> ", expenseId INTEGER"
                "recurring_occurrences" -> ", sourceId INTEGER"
                "budget_forecasts" -> ", budgetId INTEGER"
                else -> ""
            }
            db.execSQL("CREATE TABLE \"$table\" (id INTEGER PRIMARY KEY$reference)")
        }
        return db
    }
}
