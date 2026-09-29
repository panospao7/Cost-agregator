package com.yourname.expensetracker.architecture

import org.junit.Assert.*
import org.junit.Test

class WorkerEntryPointProofTest {
    private val call = "val result = executionGuard.runGuardedWithContext(request) { work() }"
    private val bridge = "return result.toWorkerResult()"
    private fun source(body: String, helper: String = "", qualified: Boolean = true): String = """
        class ExampleWorker(private val executionGuard: WorkerExecutionGuard) :
            ${if (qualified) "androidx.work.CoroutineWorker" else "CoroutineWorker"}(context, params) {
            override suspend fun doWork(): Result {
                $body
            }
            $helper
        }
    """.trimIndent()

    @Test
    fun `qualified and imported workers require real entry point execution`() {
        for (qualified in listOf(false, true)) {
            assertTrue(WorkerEntryPointProof.inspect(source("$call\n$bridge", qualified = qualified)).single().guarded)
            assertFalse(WorkerEntryPointProof.inspect(source("return Result.success()", qualified = qualified)).single().guarded)
        }
    }

    @Test
    fun `comments literals helpers local functions and stored lambdas cannot prove execution`() {
        val cases = listOf(
            source("// $call\nreturn Result.success()"),
            source("val note = \"$call\"\nreturn Result.success()"),
            source("return Result.success()", "fun unused() { $call; $bridge }"),
            source("fun unused() { $call; $bridge }\nreturn Result.success()"),
            source("val unused = { $call; result.toWorkerResult() }\nreturn Result.success()"),
            source("if (false) { $call; $bridge }\nreturn Result.success()"),
            source("$call\nreturn Result.success()", "fun unused() { $bridge }"),
            source("return Result.success()\n$call\n$bridge"),
            source("if (ready) { return Result.success() }\n$call\n$bridge"),
            source("if (ready) { return Result.retry() }\n$call\n$bridge"),
            source("${call.replace("executionGuard", "other")}\n$bridge")
        )
        cases.forEachIndexed { index, text ->
            assertFalse("case $index", WorkerEntryPointProof.inspect(text).single().guarded)
        }
    }

    @Test
    fun `cleanup return branch is proved from actual helper body not helper name`() {
        val body = "$call\nreturn if (needsCleanup) { cleanupResult().toWorkerResult() } else { result.toWorkerResult() }"
        val good = "private suspend fun cleanupResult(): WorkerGuardResult<Unit> { return executionGuard.runGuardedWithContext(request) { cleanup() } }"
        val bad = "private suspend fun cleanupResult(): WorkerGuardResult<Unit> { return fabricatedResult }"
        assertTrue(WorkerEntryPointProof.inspect(source(body, good)).single().guarded)
        assertFalse(WorkerEntryPointProof.inspect(source(body, bad)).single().guarded)
    }

    @Test
    fun `input validation may fail before the guard but may not report success`() {
        val body = "if (!ready) { return Result.failure() }\n$call\n$bridge"
        assertTrue(WorkerEntryPointProof.inspect(source(body)).single().guarded)
    }

    @Test
    fun `cleanup helper parameter cannot masquerade as injected guard`() {
        val body = "$call\nreturn if (needsCleanup) { cleanupResult(fake).toWorkerResult() } else { result.toWorkerResult() }"
        val helper = "private suspend fun cleanupResult(executionGuard: FakeGuard): WorkerGuardResult<Unit> { return executionGuard.runGuardedWithContext(request) { cleanup() } }"
        assertFalse(WorkerEntryPointProof.inspect(source(body, helper)).single().guarded)
    }

    @Test
    fun `nested templates and nested comments cannot corrupt worker discovery`() {
        val template = "$" + "{format(\"{\")}"
        val body = "/* outer /* inner { */ tail } */\n$call\nval label = \"$template\"\n$bridge"
        assertTrue(WorkerEntryPointProof.inspect(source(body)).single().guarded)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `unterminated worker is a failed scan`() {
        WorkerEntryPointProof.inspect(source("val note = \"unterminated"))
    }

    @Test
    fun `guard in sibling worker does not authorize another worker`() {
        val text = source("$call\n$bridge") + """
            class OtherWorker : androidx.work.CoroutineWorker(context, params) {
                override suspend fun doWork(): Result { return Result.success() }
            }
        """.trimIndent()
        assertEquals(listOf(true, false), WorkerEntryPointProof.inspect(text).map { it.guarded })
    }
}
