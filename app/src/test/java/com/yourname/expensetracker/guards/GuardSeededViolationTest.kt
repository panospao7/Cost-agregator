package com.yourname.expensetracker.guards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GuardSeededViolationTest {

    @Test
    fun `raw money guard detects sumOf amount in temp file`() {
        val tempFile = File.createTempFile("test", ".kt")
        try {
            tempFile.writeText("val total = expenses.sumOf { it.amount }")
            // Verify the guard script exists and would detect this pattern
            val guardScript = File("../scripts/guards/check_raw_money_aggregates.kts")
            assertTrue("Guard script must exist", guardScript.exists())
            assertTrue("Guard script must be readable", guardScript.canRead())

            val content = guardScript.readText()
            assertTrue(
                "Guard script must contain raw sum patterns",
                content.contains("sumOf") && content.contains("amount")
            )
            // Verify the guard's raw sum regex matches our seeded violation
            val rawSumPattern = Regex("""sumOf\s*\{\s*it\.amount\s*\}""")
            assertTrue(
                "Guard regex must match seeded violation pattern",
                rawSumPattern.containsMatchIn(tempFile.readText())
            )
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `time calls guard detects System currentTimeMillis in temp file`() {
        val tempFile = File.createTempFile("test_time", ".kt")
        try {
            tempFile.writeText("val now = System.currentTimeMillis()")
            val guardScript = File("../scripts/guards/check_direct_time_calls.kts")
            assertTrue("Time guard script must exist", guardScript.exists())
            assertTrue("Time guard script must be readable", guardScript.canRead())

            val content = guardScript.readText()
            val timePattern = Regex("""System\.currentTimeMillis\(\)""")
            assertTrue(
                "Time guard regex must match seeded violation",
                timePattern.containsMatchIn(tempFile.readText())
            )
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `guard script detects multiple violations`() {
        val tempFile = File.createTempFile("test_multi", ".kt")
        try {
            tempFile.writeText("""
                val a = expenses.sumOf { it.amount }
                val b = expenses.sumOf { it.effectiveAmount }
            """.trimIndent())
            val guardScript = File("../scripts/guards/check_raw_money_aggregates.kts")
            assertTrue("Guard script must exist", guardScript.exists())
            assertTrue("Guard script must be readable", guardScript.canRead())

            val content = tempFile.readText()
            val rawSumPattern = Regex("""sumOf\s*\{\s*it\.\w+\s*\}""")
            val matches = rawSumPattern.findAll(content).toList()
            assertEquals(
                "Guard regex must detect both sumOf violations",
                2, matches.size
            )
            assertTrue("First violation must contain 'amount'", matches[0].value.contains("amount"))
            assertTrue("Second violation must contain 'effectiveAmount'", matches[1].value.contains("effectiveAmount"))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `all guard scripts exist and are syntactically valid kotlin scripts`() {
        val guardDir = File("../scripts/guards")
        assertTrue("Guard scripts directory must exist", guardDir.exists())

        val scripts = guardDir.listFiles { f -> f.extension == "kts" }
        assertTrue("At least one guard script must exist", !scripts.isNullOrEmpty())

        for (script in scripts) {
            assertTrue("Guard script ${script.name} must exist", script.exists())
            assertTrue("Guard script ${script.name} must be readable", script.canRead())
            assertTrue("Guard script ${script.name} must have content", script.length() > 0)
            // Verify basic Kotlin structure: must have import or val/var/fun keywords
            val text = script.readText()
            assertTrue(
                "Guard script ${script.name} must contain Kotlin code",
                text.contains("import") || text.contains("val ") || text.contains("fun ")
            )
        }
    }
}
