package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadPolicy
import com.yourname.expensetracker.data.privacy.DefaultCloudPayloadRedactor
import com.yourname.expensetracker.data.security.SecureKeyStorage
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.AiTargetType
import com.yourname.expensetracker.domain.ai.model.DedupeCandidateSummary
import com.yourname.expensetracker.domain.ai.model.DedupeJudgeInput
import com.yourname.expensetracker.domain.ai.model.ReviewExplanationInput
import com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicy
import com.yourname.expensetracker.domain.privacy.EffectiveCloudAiPolicyResolver
import com.yourname.expensetracker.domain.privacy.PrivacyAuditLogger
import com.yourname.expensetracker.domain.privacy.PrivacyCapability
import com.yourname.expensetracker.domain.privacy.PrivacyDecision
import com.yourname.expensetracker.domain.privacy.PrivacyGate
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber
import java.io.IOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException

/**
 * Shared cloud error contract (R6 / RG02B-1 / RG02B-4) for CloudDedupeJudgeService and
 * CloudReviewExplanationService: malformed 200 body -> ParseError("PARSER_FAILED"); unexpected exception ->
 * Unknown("UNKNOWN_ERROR"); timeout / SSL / IO throwables never logged and retried per schedule;
 * CancellationException propagates and never logs UNKNOWN_ERROR.
 *
 * One row per service; the per-service differences (sentinel, the bounded code that must appear in the logs
 * for the malformed and unknown paths) are columns. Every log priority is captured.
 */
class CloudAiServiceErrorContractTest {

    private class LogLine(
        val priority: Int,
        val tag: String?,
        val throwable: Throwable?,
        val message: String
    )

    private class Row(
        val name: String,
        val sentinel: String,
        /** Bounded code that must appear in a log line on the malformed-body path (null = only non-empty logs). */
        val malformedLogCode: String?,
        /** Bounded code that must appear in a log line on the unexpected-exception path (null = only non-empty logs). */
        val unknownLogCode: String?,
        val call: suspend (OkHttpClient, RecordingRetryDelay) -> Any?
    )

    private fun dedupeInput() = DedupeJudgeInput(
        subject = DedupeCandidateSummary(
            targetType = AiTargetType.PENDING_REVIEW,
            targetId = 1L,
            merchant = "Lidl",
            amount = 24.5,
            currency = "EUR",
            date = 1_000L,
            sourceLabel = "bank",
            textPreview = "Card purchase at Lidl"
        ),
        candidates = listOf(
            DedupeCandidateSummary(
                targetType = AiTargetType.EXPENSE,
                targetId = 2L,
                merchant = "Lidl",
                amount = 24.5,
                currency = "EUR",
                date = 1_010L,
                sourceLabel = "expense",
                textPreview = "Card purchase at Lidl"
            )
        )
    )

    private fun reviewInput() = ReviewExplanationInput(
        reviewId = 1L,
        merchant = "Test",
        amount = 10.0,
        currency = "EUR",
        suggestedType = "PURCHASE",
        suggestedCategoryId = null,
        confidence = 0.4f,
        matchType = null,
        explanation = null,
        packageName = "pkg",
        notificationTitle = "Title",
        notificationText = "Body"
    )

    private fun dedupeService(client: OkHttpClient, retryDelay: RecordingRetryDelay): CloudDedupeJudgeService {
        val mockKeyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { mockKeyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "fake-api-key"
        return CloudDedupeJudgeService(
            secureKeyStorage = mockKeyStorage,
            client = client,
            aiSettingsRepository = null,
            privacyGate = mockk(relaxed = true),
            cloudPayloadPolicy = DefaultCloudPayloadPolicy(
                EffectiveCloudAiPolicyResolver.failClosedNoAi(),
                DefaultCloudPayloadRedactor()
            ),
            auditLogger = PrivacyAuditLogger.NO_OP,
            retryDelay = retryDelay.delayer
        )
    }

    private fun reviewService(client: OkHttpClient, retryDelay: RecordingRetryDelay): CloudReviewExplanationService {
        val keyStorage = mockk<SecureKeyStorage>(relaxed = true)
        every { keyStorage.getKey(SecureKeyStorage.KEY_GEMINI) } returns "fake-api-key"
        val resolver = mockk<EffectiveCloudAiPolicyResolver>()
        coEvery { resolver.resolve() } returns EffectiveCloudAiPolicy(
            cloudAllowed = true,
            reason = null,
            redactBeforeCloud = false,
            receiptImageUploadAllowed = false,
            bankStatementCloudAllowed = false
        )
        val gate = mockk<PrivacyGate>()
        coEvery { gate.check(any<PrivacyCapability>(), any()) } returns PrivacyDecision.Allowed
        return CloudReviewExplanationService(
            secureKeyStorage = keyStorage,
            client = client,
            aiSettingsRepository = null,
            privacyGate = gate,
            cloudPayloadPolicy = DefaultCloudPayloadPolicy(
                policyResolver = resolver,
                redactor = DefaultCloudPayloadRedactor()
            ),
            effectiveCloudAiPolicyResolver = resolver,
            auditLogger = PrivacyAuditLogger.NO_OP,
            retryDelay = retryDelay.delayer
        )
    }

    private val rows: List<Row> = listOf(
        Row(
            name = "CloudDedupeJudge",
            sentinel = "R6SENTINEL_9f2c",
            malformedLogCode = null,
            unknownLogCode = null,
            call = { client, delay -> dedupeService(client, delay).judge(dedupeInput()) }
        ),
        Row(
            name = "CloudReviewExplanation",
            sentinel = "R6SENTINEL_9f2c",
            malformedLogCode = "PARSER_FAILED",
            unknownLogCode = "UNKNOWN_ERROR",
            call = { client, delay -> reviewService(client, delay).generate(reviewInput()) }
        )
    )

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
        assertTrue("$label: expected at least one bounded log line", logs.isNotEmpty())
        assertTrue(
            "$label: every log line must have no throwable",
            logs.all { it.throwable == null }
        )
        assertTrue(
            "$label: no log line may contain the sentinel ${row.sentinel}",
            logs.none { it.message.contains(row.sentinel) }
        )
    }

    // INV (R6): malformed 200 body -> bounded ParseError("PARSER_FAILED"), no leaked body text.
    @Test
    fun `malformed response maps to bounded PARSER_FAILED without leaking sentinel`() {
        checkAllRows { row ->
            val client = OkHttpClient.Builder()
                .addInterceptor { chain ->
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("{ ${row.sentinel}".toResponseBody("application/json".toMediaType()))
                        .build()
                }
                .build()

            withPlantedLogs { logs ->
                val result = runBlocking { row.call(client, RecordingRetryDelay()) }

                assertEquals(
                    "${row.name}: result",
                    AiServiceResult.Failure(AiServiceError.ParseError("PARSER_FAILED")),
                    result
                )
                assertTrue(
                    "${row.name}: result must not contain the sentinel",
                    !result.toString().contains(row.sentinel)
                )
                row.malformedLogCode?.let { code ->
                    assertTrue(
                        "${row.name}: expected a bounded $code log line",
                        logs.any { it.message.contains(code) }
                    )
                }
                assertCleanLogs("${row.name}.malformed", row, logs)
            }
        }
    }

    // INV (R6): unexpected exception -> bounded Unknown("UNKNOWN_ERROR"), no leaked exception text.
    @Test
    fun `unexpected exception maps to bounded UNKNOWN_ERROR without leaking sentinel`() {
        checkAllRows { row ->
            val client = OkHttpClient.Builder()
                .addInterceptor { throw IllegalStateException(row.sentinel) }
                .build()

            withPlantedLogs { logs ->
                val result = runBlocking { row.call(client, RecordingRetryDelay()) }

                assertEquals(
                    "${row.name}: result",
                    AiServiceResult.Failure(AiServiceError.Unknown("UNKNOWN_ERROR")),
                    result
                )
                assertTrue(
                    "${row.name}: result must not contain the sentinel",
                    !result.toString().contains(row.sentinel)
                )
                row.unknownLogCode?.let { code ->
                    assertTrue(
                        "${row.name}: expected a bounded $code log line",
                        logs.any { it.message.contains(code) }
                    )
                }
                assertCleanLogs("${row.name}.unknown", row, logs)
            }
        }
    }

    // INV (R6 / B4-K2): timeout/SSL/IO throwables are never logged; only SocketTimeout retries (2 retries).
    @Test
    fun `timeout SSL and IO failures log no throwable and follow the retry schedule`() {
        checkAllRows { row ->
            withPlantedLogs { logs ->
                listOf<Exception>(
                    SocketTimeoutException(row.sentinel),
                    SSLException(row.sentinel),
                    IOException(row.sentinel)
                ).forEach { failure ->
                    val label = "${row.name}.${failure::class.java.simpleName}"
                    val client = OkHttpClient.Builder().addInterceptor { throw failure }.build()
                    val retryDelay = RecordingRetryDelay()

                    val result = runBlocking { row.call(client, retryDelay) }

                    assertTrue("$label: expected Failure but was $result", result is AiServiceResult.Failure)
                    val expectedRetries = if (failure is SocketTimeoutException) 2 else 0
                    try {
                        retryDelay.assertSchedule(expectedRetries = expectedRetries)
                    } catch (e: AssertionError) {
                        // RecordingRetryDelay is shared and label-free; name the row and failure type here.
                        throw AssertionError(
                            "$label: retry schedule (expected $expectedRetries retries) violated: ${e.message}",
                            e
                        )
                    }
                }
                assertCleanLogs("${row.name}.ioTimeout", row, logs)
            }
        }
    }

    // INV (R6): CancellationException propagates and is never logged as UNKNOWN_ERROR.
    @Test
    fun `cancellation exception propagates instead of mapping to Unknown`() {
        checkAllRows { row ->
            val client = OkHttpClient.Builder()
                .addInterceptor { throw CancellationException(row.sentinel) }
                .build()

            withPlantedLogs { logs ->
                assertThrows("${row.name}: CancellationException must propagate", CancellationException::class.java) {
                    runBlocking { row.call(client, RecordingRetryDelay()) }
                }
                assertTrue(
                    "${row.name}: cancellation must not log UNKNOWN_ERROR",
                    logs.none { it.message.contains("UNKNOWN_ERROR") }
                )
            }
        }
    }
}
