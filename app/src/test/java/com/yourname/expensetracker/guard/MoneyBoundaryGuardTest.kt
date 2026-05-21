package com.yourname.expensetracker.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * CURR-587-07: Guard fixture tests.
 *
 * These tests prove that verify_money_boundaries.py catches known regressions
 * and passes the current source.
 */
class MoneyBoundaryGuardTest {

    private val projectRoot: File = run {
        val cwd = File(System.getProperty("user.dir"))
        // cwd could be app/ or project root — find the one containing scripts/
        if (File(cwd, "scripts/verify_money_boundaries.py").exists()) cwd
        else if (File(cwd.parentFile, "scripts/verify_money_boundaries.py").exists()) cwd.parentFile
        else cwd
    }
    private val guardScript: File = File(projectRoot, "scripts/verify_money_boundaries.py")

    private fun runGuard(fixtureRoot: File): Pair<Int, String> {
        val (pythonExe, pythonArgs) = detectPython()
        val cmd = mutableListOf(pythonExe) + pythonArgs + listOf(guardScript.absolutePath, "--root", fixtureRoot.absolutePath)
        val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val output = proc.inputStream.bufferedReader().readText()
        proc.waitFor()
        return proc.exitValue() to output
    }

    private fun detectPython(): Pair<String, List<String>> {
        return try {
            val proc = ProcessBuilder("py", "-3", "--version").redirectErrorStream(true).start()
            proc.waitFor()
            if (proc.exitValue() == 0) "py" to listOf("-3") else fallbackPython()
        } catch (e: Exception) {
            fallbackPython()
        }
    }

    private fun fallbackPython(): Pair<String, List<String>> {
        return try {
            val proc = ProcessBuilder("python3", "--version").redirectErrorStream(true).start()
            proc.waitFor()
            if (proc.exitValue() == 0) "python3" to emptyList() else "python" to emptyList()
        } catch (e: Exception) {
            "python" to emptyList()
        }
    }

    private fun writeFixture(root: File, relativePath: String, content: String): File {
        val file = File(root, relativePath)
        file.parentFile.mkdirs()
        file.writeText(content)
        return file
    }

    private fun createTempRoot(prefix: String = "guard-fixture"): File {
        return Files.createTempDirectory(prefix).toFile()
    }

    // ── Failing fixtures ──────────────────────────────────────────────────────

    @Test
    fun `guard flags CurrencyCode XXX sentinel`() {
        val root = createTempRoot()
        writeFixture(root, "app/src/main/java/com/yourname/expensetracker/domain/forecasting/Bad.kt", """
            package com.yourname.expensetracker.domain.forecasting
            import com.yourname.expensetracker.domain.core.money.CurrencyCode
            val bad = CurrencyCode("XXX")
        """.trimIndent())
        val (exit, output) = runGuard(root)
        assertFalse("Guard should fail on CurrencyCode(XXX)\n$output", exit == 0)
        assertTrue("Output should mention G-MONEY-11\n$output", output.contains("G-MONEY-11"))
        root.deleteRecursively()
    }

    @Test
    fun `guard flags blank currency CurrencyCode empty`() {
        val root = createTempRoot()
        writeFixture(root, "app/src/main/java/com/yourname/expensetracker/domain/forecasting/Bad.kt", """
            package com.yourname.expensetracker.domain.forecasting
            import com.yourname.expensetracker.domain.core.money.CurrencyCode
            val bad = CurrencyCode("")
        """.trimIndent())
        val (exit, output) = runGuard(root)
        assertFalse("Guard should fail on CurrencyCode('')\n$output", exit == 0)
        assertTrue("Output should mention G-MONEY-11\n$output", output.contains("G-MONEY-11"))
        root.deleteRecursively()
    }

    @Test
    fun `guard flags displayCurrency NA sentinel`() {
        val root = createTempRoot()
        writeFixture(root, "app/src/main/java/com/yourname/expensetracker/domain/forecasting/Bad.kt", """
            package com.yourname.expensetracker.domain.forecasting
            val displayCurrency = "N/A"
        """.trimIndent())
        val (exit, output) = runGuard(root)
        assertFalse("Guard should fail on displayCurrency = N/A\n$output", exit == 0)
        assertTrue("Output should mention G-MONEY-11\n$output", output.contains("G-MONEY-11"))
        root.deleteRecursively()
    }

    @Test
    fun `guard flags raw ExpenseSnapshot in synthesis`() {
        val root = createTempRoot()
        writeFixture(root, "app/src/main/java/com/yourname/expensetracker/domain/forecasting/Bad.kt", """
            package com.yourname.expensetracker.domain.forecasting
            import com.yourname.expensetracker.domain.model.ExpenseSnapshot
            fun bad(snapshot: ExpenseSnapshot) {
                val snap = ExpenseSnapshot(effectiveAmount = snapshot.effectiveAmount)
            }
        """.trimIndent())
        val (exit, output) = runGuard(root)
        assertFalse("Guard should fail on raw ExpenseSnapshot\n$output", exit == 0)
        assertTrue("Output should mention G-MONEY-10\n$output", output.contains("G-MONEY-10"))
        root.deleteRecursively()
    }

    @Test
    fun `guard flags emptyList fallback in dashboard`() {
        val root = createTempRoot()
        writeFixture(root, "app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt", """
            package com.yourname.expensetracker.domain.usecase.dashboard
            fun bad() {
                when (val result = DashboardNormalizedInputResult.Unavailable("", 0, 0)) {
                    is DashboardNormalizedInputResult.Unavailable -> emptyList()
                }
            }
        """.trimIndent())
        val (exit, output) = runGuard(root)
        assertFalse("Guard should fail on Unavailable -> emptyList()\n$output", exit == 0)
        assertTrue("Output should mention G-MONEY-21\n$output", output.contains("G-MONEY-21"))
        root.deleteRecursively()
    }

    @Test
    fun `guard flags convertMultiple in new aggregate code`() {
        val root = createTempRoot()
        writeFixture(root, "app/src/main/java/com/yourname/expensetracker/domain/forecasting/Bad.kt", """
            package com.yourname.expensetracker.domain.forecasting
            suspend fun bad(converter: com.yourname.expensetracker.domain.currency.CurrencyConverter) {
                converter.convertMultiple(listOf(1.0 to "USD"), "EUR")
            }
        """.trimIndent())
        val (exit, output) = runGuard(root)
        assertFalse("Guard should fail on convertMultiple\n$output", exit == 0)
        assertTrue("Output should mention G-MONEY-17\n$output", output.contains("G-MONEY-17"))
        root.deleteRecursively()
    }

    // ── Passing fixtures ──────────────────────────────────────────────────────

    @Test
    fun `guard passes MoneyAggregateResult Unavailable`() {
        val root = createTempRoot()
        writeFixture(root, "app/src/main/java/com/yourname/expensetracker/domain/core/money/Good.kt", """
            package com.yourname.expensetracker.domain.core.money
            val result = MoneyAggregateResult.Unavailable("no currency", RateBasis.TRANSACTION_DATE)
        """.trimIndent())
        val (exit, output) = runGuard(root)
        assertTrue("Guard should pass on MoneyAggregateResult.Unavailable\n$output", exit == 0)
        root.deleteRecursively()
    }

    @Test
    fun `guard passes NormalizedForecastInput`() {
        val root = createTempRoot()
        writeFixture(root, "app/src/main/java/com/yourname/expensetracker/domain/forecasting/Good.kt", """
            package com.yourname.expensetracker.domain.forecasting
            val input = NormalizedForecastInput(
                homeCurrency = CurrencyCode("EUR"),
                normalizedExpenses = emptyList(),
                pastSumDaily = emptyList(),
                recurringPatterns = emptyList(),
                plannedExpenses = emptyList(),
                savingsGoals = emptyList(),
                budgetStatuses = emptyList(),
                spendingPace = SpendingPace(0.0, 0, 0, 0.0, null, null, 100f, com.yourname.expensetracker.domain.analytics.PaceStatus.NO_BASELINE, "EUR")
            )
        """.trimIndent())
        val (exit, output) = runGuard(root)
        assertTrue("Guard should pass on NormalizedForecastInput\n$output", exit == 0)
        root.deleteRecursively()
    }

    @Test
    fun `guard passes StaleRatePolicy forBasis`() {
        val root = createTempRoot()
        writeFixture(root, "app/src/main/java/com/yourname/expensetracker/domain/forecasting/Good.kt", """
            package com.yourname.expensetracker.domain.forecasting
            import com.yourname.expensetracker.domain.core.money.StaleRatePolicy
            import com.yourname.expensetracker.domain.core.money.RateBasis
            val policy = StaleRatePolicy.forBasis(RateBasis.LATEST_AVAILABLE)
        """.trimIndent())
        val (exit, output) = runGuard(root)
        assertTrue("Guard should pass on StaleRatePolicy.forBasis\n$output", exit == 0)
        root.deleteRecursively()
    }

    // ── Current source pass ───────────────────────────────────────────────────

    @Test
    fun `guard passes current source`() {
        val realRoot = projectRoot
        val (exit, output) = runGuard(realRoot)
        assertTrue("Guard should pass current source\n$output", exit == 0)
    }
}
