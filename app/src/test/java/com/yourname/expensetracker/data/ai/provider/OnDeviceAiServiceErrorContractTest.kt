package com.yourname.expensetracker.data.ai.provider

import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerativeModel
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.model.DashboardBriefingInput
import com.yourname.expensetracker.domain.ai.model.DashboardBudgetWarningInput
import com.yourname.expensetracker.domain.ai.model.DashboardUpcomingItemInput
import com.yourname.expensetracker.domain.ai.model.DedupeCandidateSummary
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeInput
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistInput
import com.yourname.expensetracker.domain.ai.model.ReviewExplanationInput
import com.yourname.expensetracker.domain.model.UiText
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber

/**
 * Shared error contract for the on-device AI services (R6 / R6b): an unexpected exception maps to the
 * bounded Failure(Unknown("UNKNOWN_ERROR")) with no leaked text, CancellationException always propagates,
 * and parse failures never log a throwable or the raw payload.
 *
 * One row per service; per-service differences (sentinel, whether an UNKNOWN_ERROR / PARSER_FAILED log
 * line is required, entry points, parse payloads) are columns. Every log priority is captured.
 * Robolectric: ReceiptAssist's ImagePart decodes via android.graphics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnDeviceAiServiceErrorContractTest {

    private class LogLine(
        val priority: Int,
        val tag: String?,
        val throwable: Throwable?,
        val message: String
    )

    private class EntryPoint(
        val name: String,
        val call: suspend (GenerativeModel) -> Any?
    )

    private class ParseCase(
        val payload: String,
        val expectNull: Boolean
    )

    private class Row(
        val name: String,
        val sentinel: String,
        /** True only where the service logs a bounded UNKNOWN_ERROR line on the unknown-exception path. */
        val requireUnknownLog: Boolean,
        /** True only where a malformed payload reaches the parser catch and logs PARSER_FAILED. */
        val requireParserFailedLog: Boolean,
        val entryPoints: List<EntryPoint>,
        val parse: (String) -> Any?,
        val parseCases: List<ParseCase>
    )

    private fun <T : Any> seeded(service: T, model: GenerativeModel): T {
        // Seeds the production model cache (no production change; avoids Generation.getClient()).
        val field = service::class.java.getDeclaredField("cachedModel")
        field.isAccessible = true
        field.set(service, model)
        return service
    }

    private fun <T> withPlantedLogs(block: (MutableList<LogLine>) -> T): T {
        val logs = mutableListOf<LogLine>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logs += LogLine(priority, tag, t, message)
            }
        }
        Timber.plant(tree)
        try {
            return block(logs)
        } finally {
            Timber.uproot(tree)
        }
    }

    private val reviewInput = ReviewExplanationInput(
        reviewId = 1L,
        merchant = "Wolt",
        amount = 18.5,
        currency = "EUR",
        suggestedType = "PURCHASE",
        suggestedCategoryId = null,
        confidence = 0.42f,
        matchType = "WEAK_MATCH",
        explanation = "merchant text is ambiguous",
        packageName = "com.wolt.app",
        notificationTitle = "Payment notice",
        notificationText = "Your card was charged"
    )

    private val dedupeInput = DedupeJudgeInput(
        subject = DedupeCandidateSummary(
            targetType = AiTargetType.PENDING_REVIEW,
            targetId = 2L,
            merchant = "Lidl",
            amount = 10.0,
            currency = "EUR",
            date = 1_000L,
            sourceLabel = "pkg"
        ),
        candidates = listOf(
            DedupeCandidateSummary(
                targetType = AiTargetType.EXPENSE,
                targetId = 3L,
                merchant = "Lidl",
                amount = 10.0,
                currency = "EUR",
                date = 1_020L,
                sourceLabel = "expense"
            )
        )
    )

    private val dashboardInput = DashboardBriefingInput(
        dateKey = "2026-03-16",
        weatherHeadline = UiText.from("Sunny"),
        weatherSummary = UiText.from("Stable day ahead"),
        discretionaryBudget = 120.0,
        totalCommitted = 80.0,
        totalLikely = 110.0,
        pendingReviewCount = 2,
        currentMonthSpent = 430.0,
        topCategories = listOf("Groceries", "Transport"),
        budgetWarnings = listOf(
            DashboardBudgetWarningInput(
                categoryLabel = UiText.from("Groceries"),
                percentUsed = 92
            )
        ),
        upcomingItems = listOf(
            DashboardUpcomingItemInput(
                description = "Rent",
                amount = 500.0,
                dateMillis = 1_774_928_000_000,
                currencyCode = "EUR"
            )
        )
    )

    private val receiptInput = ReceiptAssistInput(
        receiptId = 7L,
        rawOcrText = "LIDL HELLAS\nTOTAL 12.34\nDATE 2026-03-01",
        imagePath = null,
        imageMimeType = null,
        parsedMerchant = null,
        parsedTotal = null,
        parsedDate = null,
        parsedTaxAmount = null,
        currency = "EUR",
        lineItemsJson = null,
        currentTimeMs = 1_000L
    )

    private val rows: List<Row> = listOf(
        Row(
            name = "OnDeviceReviewExplanation",
            sentinel = "R6SENTINEL_9f2c",
            requireUnknownLog = false,
            requireParserFailedLog = true,
            entryPoints = listOf(
                EntryPoint("generate") { m ->
                    seeded(OnDeviceReviewExplanationService(), m).generate(reviewInput)
                }
            ),
            parse = { OnDeviceReviewExplanationService().parseResponse(it) },
            parseCases = listOf(ParseCase("{ R6SENTINEL_9f2c }", expectNull = true))
        ),
        Row(
            name = "OnDeviceDedupeJudge",
            sentinel = "R6SENTINEL_9f2c",
            requireUnknownLog = false,
            requireParserFailedLog = true,
            entryPoints = listOf(
                EntryPoint("judge") { m ->
                    seeded(OnDeviceDedupeJudgeService(), m).judge(dedupeInput)
                }
            ),
            parse = { OnDeviceDedupeJudgeService().parseResponse(it) },
            parseCases = listOf(ParseCase("{ R6SENTINEL_9f2c }", expectNull = true))
        ),
        Row(
            name = "OnDeviceDashboardBriefing",
            sentinel = "R6BSENTINEL_7d41",
            requireUnknownLog = true,
            // DashboardBriefingResponseParser catches the JSON failure, logs bounded
            // "PARSER_FAILED class=<simpleName>" and returns null.
            requireParserFailedLog = true,
            entryPoints = listOf(
                EntryPoint("generate") { m ->
                    seeded(OnDeviceDashboardBriefingService(), m).generate(dashboardInput)
                }
            ),
            parse = { OnDeviceDashboardBriefingService().parseResponse(it) },
            parseCases = listOf(ParseCase("{ R6BSENTINEL_7d41 }", expectNull = true))
        ),
        Row(
            name = "OnDeviceReceiptAssist",
            sentinel = "R6BSENTINEL_7d41",
            requireUnknownLog = true,
            // By design: CloudJsonParser pre-validates candidates and returns null for syntactically
            // invalid JSON before the parser catch, so no PARSER_FAILED line is expected.
            requireParserFailedLog = false,
            entryPoints = listOf(
                EntryPoint("suggest") { m ->
                    seeded(OnDeviceReceiptAssistService(), m).suggest(receiptInput)
                },
                EntryPoint("suggestFromText") { m ->
                    seeded(OnDeviceReceiptAssistService(), m).suggestFromText("prompt")
                }
            ),
            parse = { OnDeviceReceiptAssistService().parseResponse(it) },
            parseCases = listOf(
                // Malformed value: org.json syntax errors embed the raw input in the exception message.
                ParseCase("{\"R6BSENTINEL_7d41\":}", expectNull = true),
                // json.org rejects duplicate keys with a message naming the key; Android org.json tolerates them.
                ParseCase("{\"R6BSENTINEL_7d41\":1,\"R6BSENTINEL_7d41\":2}", expectNull = false)
            )
        )
    )

    private fun checkAllRows(block: (Row) -> Unit) {
        val failures = mutableListOf<String>()
        for (row in rows) {
            try {
                block(row)
            } catch (e: CancellationException) {
                // Rethrow unchanged; only attach the row failures recorded so far so they are not lost.
                failures.forEach { e.addSuppressed(AssertionError(it)) }
                throw e
            } catch (e: AssertionError) {
                failures += "[${row.name}] ${e.message}"
            } catch (e: Throwable) {
                // Non-assertion failures (e.g. unexpected exceptions) must not abort the remaining rows.
                failures += "[${row.name}] unexpected ${e::class.java.simpleName}: ${e.message}"
            }
        }
        if (failures.isNotEmpty()) {
            throw AssertionError(failures.joinToString(separator = "\n"))
        }
    }

    private fun assertCleanLogs(label: String, row: Row, logs: List<LogLine>) {
        assertTrue(
            "$label: every log line must have no throwable",
            logs.all { it.throwable == null }
        )
        assertTrue(
            "$label: no log line may contain the sentinel ${row.sentinel}",
            logs.none { it.message.contains(row.sentinel) }
        )
    }

    // INV (R6/R6b): unexpected exception -> bounded Failure(Unknown("UNKNOWN_ERROR")), no leaked text.
    @Test
    fun `unknown exception maps to bounded UNKNOWN_ERROR without leaking sentinel`() {
        checkAllRows { row ->
            row.entryPoints.forEach { entry ->
                val label = "${row.name}.${entry.name}"
                val model = mockk<GenerativeModel>()
                coEvery { model.generateContent(any<GenerateContentRequest>()) } throws
                    IllegalStateException(row.sentinel)

                withPlantedLogs { logs ->
                    val result = runBlocking { entry.call(model) }

                    assertEquals(
                        "$label: result",
                        AiServiceResult.Failure(AiServiceError.Unknown("UNKNOWN_ERROR")),
                        result
                    )
                    assertTrue(
                        "$label: result must not contain the sentinel",
                        !result.toString().contains(row.sentinel)
                    )
                    if (row.requireUnknownLog) {
                        assertTrue(
                            "$label: expected a bounded UNKNOWN_ERROR log line",
                            logs.any { it.message.contains("UNKNOWN_ERROR") }
                        )
                    }
                    assertCleanLogs(label, row, logs)
                }
            }
        }
    }

    // INV (R6/R6b): CancellationException is never mapped to Unknown and never logged as UNKNOWN_ERROR.
    @Test
    fun `cancellation exception propagates instead of mapping to Unknown`() {
        checkAllRows { row ->
            row.entryPoints.forEach { entry ->
                val label = "${row.name}.${entry.name}"
                val model = mockk<GenerativeModel>()
                coEvery { model.generateContent(any<GenerateContentRequest>()) } throws
                    CancellationException(row.sentinel)

                withPlantedLogs { logs ->
                    try {
                        assertThrows(label, CancellationException::class.java) {
                            runBlocking { entry.call(model) }
                        }
                    } catch (e: AssertionError) {
                        throw AssertionError("$label: CancellationException was not propagated: ${e.message}")
                    }
                    assertTrue(
                        "$label: cancellation must not log UNKNOWN_ERROR",
                        logs.none { it.message.contains("UNKNOWN_ERROR") }
                    )
                }
            }
        }
    }

    // INV (R6/R6b): parse failures log only bounded codes - no throwable, no raw payload.
    @Test
    fun `parse failure logs no throwable or sentinel`() {
        checkAllRows { row ->
            row.parseCases.forEachIndexed { index, case ->
                val label = "${row.name}.parseResponse#$index"
                withPlantedLogs { logs ->
                    val result = row.parse(case.payload)

                    if (case.expectNull) {
                        assertNull("$label: malformed payload must parse to null", result)
                    }
                    if (row.requireParserFailedLog) {
                        assertTrue(
                            "$label: expected a bounded PARSER_FAILED log line",
                            logs.any { it.message.contains("PARSER_FAILED") }
                        )
                    }
                    assertCleanLogs(label, row, logs)
                }
            }
        }
    }
}
