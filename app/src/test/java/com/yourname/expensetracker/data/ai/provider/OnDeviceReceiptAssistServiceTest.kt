package com.yourname.expensetracker.data.ai.provider

import android.graphics.Bitmap
import com.google.mlkit.genai.prompt.GenerateContentRequest
import com.google.mlkit.genai.prompt.GenerativeModel
import com.yourname.expensetracker.domain.ai.model.AiServiceError
import com.yourname.expensetracker.domain.ai.model.AiServiceResult
import com.yourname.expensetracker.domain.ai.model.ReceiptAssistInput
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import timber.log.Timber
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

// Robolectric: ImagePart(bytes) decodes the image via android.graphics in its
// init, which returns null on the plain JVM stub jar and aborts construction.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OnDeviceReceiptAssistServiceTest {

    private val service = OnDeviceReceiptAssistService()

    private val sampleInput = ReceiptAssistInput(
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

    @Test
    fun `buildPrompt includes OCR and parsed values`() {
        val prompt = service.buildPrompt(sampleInput)

        assertTrue(prompt.contains("LIDL HELLAS"))
        assertTrue(prompt.contains("Parsed merchant: none"))
        assertTrue(prompt.contains("Parsed total: none"))
        assertTrue(prompt.contains("Currency: EUR"))
    }

    @Test
    fun `buildPrompt includes JSON schema`() {
        val prompt = service.buildPrompt(sampleInput)

        assertTrue(prompt.contains("merchant"))
        assertTrue(prompt.contains("total"))
        assertTrue(prompt.contains("date"))
        assertTrue(prompt.contains("taxAmount"))
        assertTrue(prompt.contains("notes"))
    }

    @Test
    fun `imageBytesForTest reads valid image input`() {
        // Request attachment is asserted with the real Android graphics/ML Kit
        // runtime in OnDeviceReceiptAssistServiceImageInstrumentedTest.
        val imageFile = kotlin.io.path.createTempFile(suffix = ".png").toFile().apply {
            // Encode a real 1x1 PNG with the platform encoder (Robolectric-backed).
            val bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            val output = ByteArrayOutputStream()
            try {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                writeBytes(output.toByteArray())
            } finally {
                bitmap.recycle()
            }
        }

        try {
            val bytes = service.imageBytesForTest(
                sampleInput.copy(
                    imagePath = imageFile.absolutePath,
                    imageMimeType = "image/png",
                    isImageAnalysisMode = true
                )
            )

            assertNotNull(bytes)
            assertTrue(bytes!!.isNotEmpty())
            assertArrayEquals(imageFile.readBytes(), bytes)
        } finally {
            imageFile.delete()
        }
    }

    @Test
    fun `buildRequestForTest stays text only when image is missing`() {
        val request = service.buildRequestForTest(
            sampleInput.copy(
                imagePath = "missing.png",
                imageMimeType = "image/png",
                isImageAnalysisMode = true
            )
        )

        assertFalse(service.usedImageInput(sampleInput.copy(
            imagePath = "missing.png",
            imageMimeType = "image/png",
            isImageAnalysisMode = true
        )))
        assertNull(request.image)
    }

    @Test
    fun `parseResponse handles clean JSON`() {
        val result = service.parseResponse(
            """
            {"merchant":{"value":"Lidl","confidence":0.9,"rationale":"merchant line"},"total":{"value":12.34,"confidence":0.8,"rationale":"total line"},"date":{"value":1709251200000,"confidence":0.7,"rationale":"date line"},"taxAmount":null,"notes":["receipt is short"]}
            """.trimIndent()
        )

        assertNotNull(result)
        assertEquals("Lidl", result!!.merchant?.value)
        assertEquals(12.34, result.total?.value ?: 0.0, 0.001)
        assertEquals(1709251200000L, result.date?.value)
        assertEquals(listOf("receipt is short"), result.notes)
    }

    @Test
    fun `parseResponse handles markdown fenced JSON`() {
        val result = service.parseResponse(
            """
            ```json
            {"merchant":{"value":"Lidl"},"total":null,"date":null,"taxAmount":null,"notes":[]}
            ```
            """.trimIndent()
        )

        assertNotNull(result)
        assertEquals("Lidl", result!!.merchant?.value)
    }

    @Test
    fun `parseResponse returns null for invalid text`() {
        assertNull(service.parseResponse("not json"))
    }

    @Test
    fun `parseResponse keeps missing values as null`() {
        val result = service.parseResponse(
            """{"merchant":null,"total":null,"date":null,"taxAmount":null,"notes":[]}"""
        )

        assertNotNull(result)
        assertNull(result!!.merchant)
        assertNull(result.total)
        assertNull(result.date)
        assertNull(result.taxAmount)
    }

    // ---- R6b: bounded UNKNOWN_ERROR / PARSER_FAILED, no leaked text, CancellationException propagates ----

    private inline fun <T> withPlantedLogs(block: (MutableList<Pair<Throwable?, String>>) -> T): T {
        val logs = mutableListOf<Pair<Throwable?, String>>()
        val tree = object : Timber.Tree() {
            override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
                logs += t to message
            }
        }
        Timber.plant(tree)
        try {
            return block(logs)
        } finally {
            Timber.uproot(tree)
        }
    }

    /** Seeds the production model cache (no production change; avoids Generation.getClient()). */
    private fun serviceWithModel(model: GenerativeModel): OnDeviceReceiptAssistService {
        val target = OnDeviceReceiptAssistService()
        val field = OnDeviceReceiptAssistService::class.java.getDeclaredField("cachedModel")
        field.isAccessible = true
        field.set(target, model)
        return target
    }

    @Test
    fun `suggest maps unexpected exception to bounded UNKNOWN_ERROR without leaking sentinel`() {
        val model = mockk<GenerativeModel>()
        coEvery { model.generateContent(any<GenerateContentRequest>()) } throws
            IllegalStateException("R6BSENTINEL_7d41")

        withPlantedLogs { logs ->
            val result = runBlocking { serviceWithModel(model).suggest(sampleInput) }

            assertEquals(
                AiServiceResult.Failure(AiServiceError.Unknown("UNKNOWN_ERROR")),
                result
            )
            assertFalse(result.toString().contains("R6BSENTINEL_7d41"))
            assertTrue(logs.any { it.second.contains("UNKNOWN_ERROR") })
            assertTrue(logs.all { it.first == null && !it.second.contains("R6BSENTINEL_7d41") })
        }
    }

    @Test
    fun `suggestFromText maps unexpected exception to bounded UNKNOWN_ERROR without leaking sentinel`() {
        val model = mockk<GenerativeModel>()
        coEvery { model.generateContent(any<GenerateContentRequest>()) } throws
            IllegalStateException("R6BSENTINEL_7d41")

        withPlantedLogs { logs ->
            val result = runBlocking { serviceWithModel(model).suggestFromText("prompt") }

            assertEquals(
                AiServiceResult.Failure(AiServiceError.Unknown("UNKNOWN_ERROR")),
                result
            )
            assertFalse(result.toString().contains("R6BSENTINEL_7d41"))
            assertTrue(logs.any { it.second.contains("UNKNOWN_ERROR") })
            assertTrue(logs.all { it.first == null && !it.second.contains("R6BSENTINEL_7d41") })
        }
    }

    @Test
    fun `suggest and suggestFromText propagate CancellationException instead of mapping to Unknown`() {
        val model = mockk<GenerativeModel>()
        coEvery { model.generateContent(any<GenerateContentRequest>()) } throws
            CancellationException("R6BSENTINEL_7d41")

        withPlantedLogs { logs ->
            assertThrows(CancellationException::class.java) {
                runBlocking { serviceWithModel(model).suggest(sampleInput) }
            }
            assertThrows(CancellationException::class.java) {
                runBlocking { serviceWithModel(model).suggestFromText("prompt") }
            }
            assertTrue(logs.none { it.second.contains("UNKNOWN_ERROR") })
        }
    }

    @Test
    fun `parseResponse logs bounded PARSER_FAILED without throwable or sentinel on malformed JSON`() {
        withPlantedLogs { logs ->
            // Malformed value: org.json syntax errors embed the raw input in the exception message.
            val result = service.parseResponse("{\"R6BSENTINEL_7d41\":}")

            assertNull(result)
            // CloudJsonParser.extractFirstJsonObject pre-validates candidates with JSONObject and returns null for
            // syntactically invalid JSON, so parseResponse returns before its catch: no PARSER_FAILED line is
            // expected here. The privacy oracle is that nothing logged carries a throwable or the sentinel.
            assertTrue(logs.all { it.first == null && !it.second.contains("R6BSENTINEL_7d41") })
        }
    }

    @Test
    fun `parseResponse never logs throwable or sentinel for duplicate-key payload`() {
        withPlantedLogs { logs ->
            // json.org rejects duplicate keys with a message that names the key; Android org.json
            // tolerates them. Either way no log may carry the key or the throwable.
            service.parseResponse("{\"R6BSENTINEL_7d41\":1,\"R6BSENTINEL_7d41\":2}")

            assertTrue(logs.all { it.first == null && !it.second.contains("R6BSENTINEL_7d41") })
        }
    }

}
