package com.yourname.expensetracker.data.database.dao

import com.yourname.expensetracker.data.database.query.SpendingInclusionSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * refund-netting S2a, INV-9 (totals use the outer constant) as a source scan, plus pins of the
 * constant texts (spec 1.4) and of the intentionally untouched sites.
 *
 * - No SUM / AVG / MIN / MAX / TOTAL anywhere in production code wraps the inner
 *   `EFFECTIVE_AMOUNT_SQL` / `EFFECTIVE_AMOUNT_E_SQL`. The one documented exception is the
 *   `searchMerchants` CASE, which deliberately keeps the inner amount for a flagged row.
 * - The two ORDER BY sites of the largest-expense lookups keep the inner constant on purpose.
 * - The scanner is self-tested on synthetic SQL, and every scan is non-vacuous (fails closed).
 */
class RefundNettingDaoAggregateStaticTest {

    private val daoPath = "com/yourname/expensetracker/data/database/dao/ExpenseDao.kt"

    private val mainRoot: File by lazy {
        val userDir = System.getProperty("user.dir") ?: "."
        listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File(userDir, "src/main/java"),
            File(userDir, "app/src/main/java")
        ).firstOrNull { File(it, daoPath).isFile }
            ?: error("Could not locate the production source root. user.dir=$userDir")
    }

    private fun codeOnly(text: String): String =
        text.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ")
            .lines()
            .joinToString("\n") { it.replace(Regex("//.*$"), "") }

    private fun normalised(text: String): String = text.trim().replace(Regex("\\s+"), " ")

    private val daoCode: String by lazy { codeOnly(File(mainRoot, daoPath).readText(Charsets.UTF_8)) }

    private val allMainFiles: List<File> by lazy {
        mainRoot.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private val aggregateCall = Regex("\\b(SUM|AVG|MIN|MAX|TOTAL)\\s*\\(", RegexOption.IGNORE_CASE)
    private val innerConstant = Regex("\\bEFFECTIVE_AMOUNT(_E)?_SQL\\b")
    private val outerConstant = Regex("SpendingInclusionSql\\.EFFECTIVE_AMOUNT(_E)?\\b")

    /** One aggregate call: function name (upper case) and the balanced argument text. */
    private data class Aggregate(val function: String, val argument: String)

    private fun aggregatesIn(code: String): List<Aggregate> =
        aggregateCall.findAll(code).map { match ->
            var depth = 1
            var i = match.range.last + 1
            val start = i
            while (i < code.length && depth > 0) {
                when (code[i]) {
                    '(' -> depth++
                    ')' -> depth--
                }
                i++
            }
            Aggregate(match.groupValues[1].uppercase(), code.substring(start, (i - 1).coerceAtLeast(start)))
        }.toList()

    private val documentedException = normalised(
        "CASE WHEN spendingExclusionReason IS NULL THEN \${SpendingInclusionSql.EFFECTIVE_AMOUNT} " +
            "ELSE \${EFFECTIVE_AMOUNT_SQL} END"
    )

    // ── Scanner self-test (failing boundary) ─────────────────────

    @Test
    fun `INV9 the scanner flags an inner constant under every aggregate form and passes the outer one`() {
        val violations = listOf(
            "SELECT SUM(\${EFFECTIVE_AMOUNT_SQL}) FROM expenses",
            "SELECT SUM(\${ExpenseDao.EFFECTIVE_AMOUNT_E_SQL}) FROM expenses e",
            "SELECT COALESCE(SUM(\${EFFECTIVE_AMOUNT_SQL}), 0.0) FROM expenses",
            "SELECT SUM(COALESCE(\${EFFECTIVE_AMOUNT_SQL}, 0)) FROM expenses",
            "SELECT AVG(\${EFFECTIVE_AMOUNT_SQL}) FROM expenses",
            "SELECT MIN(\${EFFECTIVE_AMOUNT_SQL}), max( \${EFFECTIVE_AMOUNT_E_SQL} ) FROM expenses",
            "SELECT total(\${EFFECTIVE_AMOUNT_SQL}) FROM expenses"
        )
        violations.forEach { sql ->
            assertTrue(
                "scanner must flag: $sql",
                aggregatesIn(sql).any { innerConstant.containsMatchIn(it.argument) }
            )
        }
        listOf(
            "SELECT SUM(\${SpendingInclusionSql.EFFECTIVE_AMOUNT}) FROM expenses",
            "SELECT AVG(\${SpendingInclusionSql.EFFECTIVE_AMOUNT_E}) FROM expenses e",
            "SELECT SUM(COALESCE(\${SpendingInclusionSql.EFFECTIVE_AMOUNT}, 0)) FROM expenses",
            "SELECT MIN(date), MAX(date) FROM expenses ORDER BY \${EFFECTIVE_AMOUNT_SQL}"
        ).forEach { sql ->
            assertTrue(
                "scanner must pass: $sql",
                aggregatesIn(sql).none { innerConstant.containsMatchIn(it.argument) }
            )
        }
        // The outer and inner name patterns do not overlap.
        assertTrue(outerConstant.containsMatchIn("\${SpendingInclusionSql.EFFECTIVE_AMOUNT}"))
        assertTrue(outerConstant.containsMatchIn("\${SpendingInclusionSql.EFFECTIVE_AMOUNT_E}"))
        assertTrue(!outerConstant.containsMatchIn("\${SpendingInclusionSql.EFFECTIVE_AMOUNT_PRE_REFUND_NETTING}"))
        assertTrue(!innerConstant.containsMatchIn("\${SpendingInclusionSql.EFFECTIVE_AMOUNT}"))
    }

    // ── INV-9: no aggregate over an inner constant ───────────────

    @Test
    fun `INV9 no aggregate in production code wraps the inner effective amount except the searchMerchants CASE`() {
        // Non-vacuity: the scan really covered the production tree and the DAO.
        assertTrue("scan saw only ${allMainFiles.size} files", allMainFiles.size > 100)
        assertTrue(allMainFiles.any { it.name == "ExpenseDao.kt" })
        assertTrue(allMainFiles.any { it.name == "SpendingInclusionSql.kt" })

        val offenders = mutableListOf<String>()
        var exceptions = 0
        allMainFiles.forEach { file ->
            aggregatesIn(codeOnly(file.readText(Charsets.UTF_8))).forEach { aggregate ->
                if (innerConstant.containsMatchIn(aggregate.argument)) {
                    if (file.name == "ExpenseDao.kt" && aggregate.function == "AVG" &&
                        normalised(aggregate.argument) == documentedException
                    ) {
                        exceptions++
                    } else {
                        offenders += "${file.name}: ${aggregate.function}(${normalised(aggregate.argument).take(80)})"
                    }
                }
            }
        }

        assertTrue("aggregates over an inner constant: $offenders", offenders.isEmpty())
        assertEquals("the searchMerchants CASE is the single documented exception", 1, exceptions)
    }

    @Test
    fun `INV9 the DAO totals are really switched to the outer constant`() {
        val aggregates = aggregatesIn(daoCode).filter { outerConstant.containsMatchIn(it.argument) }
        fun count(function: String) = aggregates.count { it.function == function }

        // Impact map: 40 SUM sites at the base commit (plus the new ones), AVG/MIN/MAX of the
        // merchant, recurring and day-of-week statistics.
        assertTrue("SUM sites over the outer constant: ${count("SUM")}", count("SUM") >= 40)
        assertTrue("AVG sites: ${count("AVG")}", count("AVG") >= 5)
        assertTrue("MIN sites: ${count("MIN")}", count("MIN") >= 4)
        assertTrue("MAX sites: ${count("MAX")}", count("MAX") >= 4)
        // The aliased constant is used by the one aliased total.
        assertTrue(aggregates.any { it.function == "SUM" && it.argument.contains("EFFECTIVE_AMOUNT_E") })
        // The two deposit totals and the monthly deposits are among the switched sites.
        val depositTotals = Regex(
            "SUM\\(\\$\\{SpendingInclusionSql\\.EFFECTIVE_AMOUNT\\}\\)[^\"]*?transactionType = 'DEPOSIT'",
            RegexOption.DOT_MATCHES_ALL
        ).findAll(daoCode).count()
        assertTrue("deposit totals over the outer constant: $depositTotals", depositTotals >= 3)
    }

    @Test
    fun `INV9 the percentile feed selects the outer constant`() {
        val functionIndex = daoCode.indexOf("suspend fun getAmountsForPercentileCalc")
        assertTrue(functionIndex > 0)
        val query = daoCode.substring(daoCode.lastIndexOf("@Query", functionIndex), functionIndex)

        assertTrue(query, outerConstant.containsMatchIn(query))
        assertTrue(query, !innerConstant.containsMatchIn(query))
        assertTrue(query, query.contains("ORDER BY 1 ASC"))
    }

    @Test
    fun `INV9 the two largest-expense ORDER BY sites keep the inner constant on purpose`() {
        val pattern = Regex(
            Regex.escape("ORDER BY \${EFFECTIVE_AMOUNT_SQL} DESC") +
                "\\s+LIMIT 1\\s+\"\"\"\\)\\s+suspend fun (\\w+)"
        )
        val functions = pattern.findAll(daoCode).map { it.groupValues[1] }.toList()

        assertEquals(listOf("getLargestExpenseForPeriod", "getLargestExpenseForMerchant"), functions)
        // And these are the only remaining uses of the inner constants as a bare ORDER BY key.
        assertEquals(2, Regex("ORDER BY\\s+\\$\\{EFFECTIVE_AMOUNT(_E)?_SQL\\}").findAll(daoCode).count())
    }

    // ── Constant texts (spec 1.4) ────────────────────────────────

    private val innerText =
        "CASE WHEN isNotMine = 1 THEN 0.0 " +
            "WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL THEN myShareAmount " +
            "WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL THEN amount * mySharePercentage / 100.0 " +
            "ELSE amount END"

    private val innerTextE =
        "CASE WHEN e.isNotMine = 1 THEN 0.0 " +
            "WHEN e.isSharedExpense = 1 AND e.myShareAmount IS NOT NULL THEN e.myShareAmount " +
            "WHEN e.isSharedExpense = 1 AND e.mySharePercentage IS NOT NULL THEN e.amount * e.mySharePercentage / 100.0 " +
            "ELSE e.amount END"

    @Test
    fun `INV11 the inner effective amount constants stay gross and unchanged`() {
        assertEquals(innerText, ExpenseDao.EFFECTIVE_AMOUNT_SQL)
        assertEquals(innerTextE, ExpenseDao.EFFECTIVE_AMOUNT_E_SQL)
        assertTrue(!ExpenseDao.EFFECTIVE_AMOUNT_SQL.contains("refundNettedAmount"))
        assertTrue(!ExpenseDao.EFFECTIVE_AMOUNT_E_SQL.contains("refundNettedAmount"))
    }

    @Test
    fun `INV6 the outer constants have exactly the spec branches in the spec order`() {
        assertEquals(
            "CASE WHEN spendingExclusionReason IS NOT NULL THEN 0.0 " +
                "WHEN refundNettedAmount <= 0.0 THEN ($innerText) " +
                "WHEN isNotMine = 1 THEN 0.0 " +
                "WHEN refundNettedAmount >= amount THEN 0.0 " +
                "WHEN isSharedExpense = 1 AND myShareAmount IS NOT NULL " +
                "THEN myShareAmount * (amount - refundNettedAmount) / amount " +
                "WHEN isSharedExpense = 1 AND mySharePercentage IS NOT NULL " +
                "THEN (amount - refundNettedAmount) * mySharePercentage / 100.0 " +
                "ELSE amount - refundNettedAmount END",
            SpendingInclusionSql.EFFECTIVE_AMOUNT
        )
        assertEquals(
            "CASE WHEN e.spendingExclusionReason IS NOT NULL THEN 0.0 " +
                "WHEN e.refundNettedAmount <= 0.0 THEN ($innerTextE) " +
                "WHEN e.isNotMine = 1 THEN 0.0 " +
                "WHEN e.refundNettedAmount >= e.amount THEN 0.0 " +
                "WHEN e.isSharedExpense = 1 AND e.myShareAmount IS NOT NULL " +
                "THEN e.myShareAmount * (e.amount - e.refundNettedAmount) / e.amount " +
                "WHEN e.isSharedExpense = 1 AND e.mySharePercentage IS NOT NULL " +
                "THEN (e.amount - e.refundNettedAmount) * e.mySharePercentage / 100.0 " +
                "ELSE e.amount - e.refundNettedAmount END",
            SpendingInclusionSql.EFFECTIVE_AMOUNT_E
        )
    }

    @Test
    fun `INV10 the frozen pre-netting constant is the text earlier builds hashed and is used by no query`() {
        assertEquals(
            "CASE WHEN spendingExclusionReason IS NOT NULL THEN 0.0 ELSE ($innerText) END",
            SpendingInclusionSql.EFFECTIVE_AMOUNT_PRE_REFUND_NETTING
        )
        val users = allMainFiles
            .filter { it.readText(Charsets.UTF_8).contains("EFFECTIVE_AMOUNT_PRE_REFUND_NETTING") }
            .map { it.name }
            .sorted()
        assertEquals(listOf("SemanticAggregates.kt", "SpendingInclusionSql.kt"), users)
    }
}
