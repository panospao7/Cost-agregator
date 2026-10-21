package com.yourname.expensetracker.diagnostics

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Source-level regression pins for the DDL-512 and DDL-F876 series.
 * JVM-only — no Android runtime required.
 *
 * Sanitizer behaviour formerly asserted here (DDL-512-07, DDL-F876-15) lives in
 * [EventMetadataSanitizerTest].
 */
class DDL512RegressionTest {

    private fun readMainSource(relativePath: String): String {
        val candidates = listOf(
            File("src/main/java", relativePath),
            File("app/src/main/java", relativePath),
            File(System.getProperty("user.dir") ?: ".", "src/main/java/$relativePath"),
            File(System.getProperty("user.dir") ?: ".", "app/src/main/java/$relativePath")
        )
        val sourceFile = candidates.firstOrNull { it.isFile }
        assertNotNull("Unable to locate main source: $relativePath", sourceFile)
        return sourceFile!!.readText()
    }

    // ── DDL-512-04: OperationRunEvent entity has eventId index ────────────────

    @Test
    fun `DDL-512-04 OperationRunEvent entity declares eventId index`() {
        // Room annotations use binary retention and are unavailable through
        // reliable JVM reflection here. Verify the source consumed by Room.
        val source = readMainSource(
            "com/yourname/expensetracker/data/database/entity/OperationRunEvent.kt"
        )
        val eventIdIndex = Regex(
            """Index\s*\(\s*value\s*=\s*\[\s*"eventId"\s*]"""
        )
        assertTrue(
            "OperationRunEvent must declare Index on eventId column",
            eventIdIndex.containsMatchIn(source)
        )
    }

    // DDL-F876-04 / DDL-C67-01 / DDL-F876-05 (SafeSinkOperationRunHandle terminal-once and
    // cancelled() reason-code policy) are covered against the production class in
    // com.yourname.expensetracker.domain.diagnostics.SafeSinkOperationRunHandleTest.

    // ── DDL-F876-11: Side effects receive create correlationId ────────────────

    @Test
    fun `DDL-F876-11 dispatchOnCreated accepts correlationId parameter`() {
        // Verify TransactionSideEffectDispatcher.dispatchOnCreated has correlationId param (>= 3 params)
        val method = com.yourname.expensetracker.domain.transaction.lifecycle.TransactionSideEffectDispatcher::class.java
            .declaredMethods
            .firstOrNull { it.name == "dispatchOnCreated" }
        assertNotNull("dispatchOnCreated method must exist", method)
        // signature: (expenseId, source, correlationId, causationId) = at least 4 params
        assertTrue("dispatchOnCreated must accept correlationId (>= 3 params)",
            method!!.parameterCount >= 3)
    }

    // ── DDL-512-11: DAO query includes BLOCKED/DROPPED/SIDE_EFFECT_FAILED ──────

    @Test
    fun `DDL-512-11 getRecentFailures query includes BLOCKED outcome`() {
        // Query also has binary retention. Inspect the annotation attached to
        // this exact DAO method instead of expecting runtime reflection.
        val source = readMainSource(
            "com/yourname/expensetracker/data/database/dao/OperationRunEventDao.kt"
        )
        val methodIndex = source.indexOf("suspend fun getRecentFailures")
        assertTrue("getRecentFailures method must exist", methodIndex >= 0)
        val queryStart = source.lastIndexOf("@Query", methodIndex)
        assertTrue("getRecentFailures must have a Query annotation", queryStart >= 0)
        val sql = source.substring(queryStart, methodIndex)
        assertTrue("Query must include BLOCKED", sql.contains("BLOCKED"))
        assertTrue("Query must include DROPPED", sql.contains("DROPPED"))
        assertTrue("Query must include SIDE_EFFECT_FAILED", sql.contains("SIDE_EFFECT_FAILED"))
    }
}
