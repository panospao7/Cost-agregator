package com.yourname.expensetracker.data.database

import org.junit.Assert.assertTrue
import org.junit.Test

class MigrationRegistrationTest {

    @Test
    fun `ALL_MIGRATIONS includes migration 117 to 119`() {
        val migrations = AppDatabase.ALL_MIGRATIONS
        assertTrue(
            "Migration 117→118 must be registered in ALL_MIGRATIONS",
            migrations.any { it.startVersion == 117 && it.endVersion == 118 }
        )
        assertTrue(
            "Migration 118→119 must be registered in ALL_MIGRATIONS",
            migrations.any { it.startVersion == 118 && it.endVersion == 119 }
        )
    }
}
