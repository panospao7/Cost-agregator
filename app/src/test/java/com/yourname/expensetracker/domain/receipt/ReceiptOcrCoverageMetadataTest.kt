package com.yourname.expensetracker.domain.receipt

import com.yourname.expensetracker.domain.diagnostics.EventMetadataSanitizer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReceiptOcrCoverageMetadataTest {
    private fun partialMetadata(): JSONObject =
        JSONObject(ReceiptOcrCoverage(2, 3, 1).toSavedMetadata().toJson())

    @Test
    fun coverageEnvelopeSurvivesSanitizationWithoutOpeningOcrKeys() {
        val sanitizer = EventMetadataSanitizer()
        val coverage = ReceiptOcrCoverage(2, 3, 1)
        val fields = JSONObject(coverage.toSavedMetadata().toJson())
        val values = fields.keys().asSequence().associateWith { fields.get(it) }
        val sanitized = sanitizer.sanitizeMap(values)
        val sanitizedAgain = sanitizer.sanitizeMap(sanitized)

        assertEquals(1, sanitizedAgain["pageCoverageVersion"])
        assertEquals(coverage, ReceiptOcrCoverage.fromSavedMetadata(JSONObject(sanitizedAgain).toString()))
        assertEquals("[REDACTED]", sanitizer.sanitizeValue("rawOcrText", "private fixture"))
        assertEquals("[REDACTED]", sanitizer.sanitizeValue("ocrCoverageVersion", 1))
    }

    @Test
    fun partialCoverageRoundTripsWithTypedBoundedFields() {
        val coverage = ReceiptOcrCoverage(2, 3, 1)
        val fields = JSONObject(coverage.toSavedMetadata().toJson())
        assertEquals(
            setOf("pageCoverageVersion", "partial", "reasonCode", "pagesProcessed", "totalPages", "failedPages"),
            fields.keys().asSequence().toSet()
        )
        assertEquals(1, fields.getInt("pageCoverageVersion"))
        assertTrue(fields.get("partial") is Boolean)
        assertTrue(fields.get("pagesProcessed") is Int)
        assertEquals("PDF_PARTIAL", fields.getString("reasonCode"))
        assertEquals(coverage, ReceiptOcrCoverage.fromSavedMetadata(fields.toString()))
    }

    @Test
    fun cappedCoverageRemainsPartial() {
        val coverage = ReceiptOcrCoverage(2, 4, 0)
        val restored = ReceiptOcrCoverage.fromSavedMetadata(coverage.toSavedMetadata().toJson())
        assertEquals(coverage, restored)
        assertTrue(restored!!.isPartial)
    }

    @Test
    fun completePdfCoverageRemainsExplicitlyComplete() {
        val coverage = ReceiptOcrCoverage(3, 3, 0)
        val fields = JSONObject(coverage.toSavedMetadata().toJson())
        assertFalse(fields.has("partial"))
        assertFalse(fields.has("reasonCode"))
        val restored = ReceiptOcrCoverage.fromSavedMetadata(fields.toString())
        assertEquals(coverage, restored)
        assertFalse(restored!!.isPartial)
    }

    @Test
    fun imageCoverageHasExplicitVersionWithoutInventedCounts() {
        val coverage = ReceiptOcrCoverage()
        val fields = JSONObject(coverage.toSavedMetadata().toJson())
        assertEquals(setOf("pageCoverageVersion"), fields.keys().asSequence().toSet())
        assertEquals(coverage, ReceiptOcrCoverage.fromSavedMetadata(fields.toString()))
    }

    @Test
    fun legacyAttemptedCountsPreservePositivePartialEvidence() {
        val coverage = ReceiptOcrCoverage(3, 3, 1)
        val fields = JSONObject(coverage.toSavedMetadata().toJson()).apply {
            remove("pageCoverageVersion")
        }
        val restored = ReceiptOcrCoverage.fromSavedMetadata(fields.toString())
        assertEquals(coverage, restored)
        assertTrue(restored!!.isPartial)
    }

    @Test
    fun legacyFailureCountWithoutTotalsPreservesPartialEvidence() {
        val fields = JSONObject()
            .put("partial", true)
            .put("reasonCode", "PDF_PARTIAL")
            .put("failedPages", 1)
        assertEquals(ReceiptOcrCoverage(failedPages = 1), ReceiptOcrCoverage.fromSavedMetadata(fields.toString()))
    }

    @Test
    fun absentOrUnversionedEmptyMetadataRemainsUnknown() {
        val values = listOf<String?>(
            null, "", " ", "{}",
            JSONObject().put("txOperationId", "receipt.save_with_review").toString()
        )
        values.forEach { assertNull(ReceiptOcrCoverage.fromSavedMetadata(it)) }
    }

    @Test
    fun legacyCompleteCountsAreNotProofOfCompleteCoverage() {
        val fields = JSONObject(ReceiptOcrCoverage(3, 3, 0).toSavedMetadata().toJson()).apply {
            remove("pageCoverageVersion")
        }
        assertNull(ReceiptOcrCoverage.fromSavedMetadata(fields.toString()))
    }

    @Test
    fun unsupportedVersionsRemainUnknown() {
        listOf(0, 2, "1", true, JSONObject.NULL).forEach { version ->
            val fields = partialMetadata().put("pageCoverageVersion", version)
            assertNull("Unsupported version: $version", ReceiptOcrCoverage.fromSavedMetadata(fields.toString()))
        }
    }

    @Test
    fun nonIntegerNegativeAndOverflowingCountsRemainUnknown() {
        val invalidValues = listOf(-1, Int.MAX_VALUE.toLong() + 1L, 1.5, "1", true, JSONObject.NULL)
        listOf("pagesProcessed", "totalPages", "failedPages").forEach { key ->
            invalidValues.forEach { value ->
                val fields = partialMetadata().put(key, value)
                assertNull("Invalid page count for $key: $value", ReceiptOcrCoverage.fromSavedMetadata(fields.toString()))
            }
        }
    }

    @Test
    fun impossibleTotalsRemainUnknown() {
        listOf(
            ReceiptOcrCoverage(0, 0, 0),
            ReceiptOcrCoverage(4, 3, 0),
            ReceiptOcrCoverage(2, 3, 4)
        ).forEach { coverage ->
            assertNull(ReceiptOcrCoverage.fromSavedMetadata(coverage.toSavedMetadata().toJson()))
        }
    }

    @Test
    fun contradictoryMarkersRemainUnknown() {
        val complete = ReceiptOcrCoverage(3, 3, 0).toSavedMetadata().toJson()
        val values = listOf(
            partialMetadata().apply { remove("partial") },
            partialMetadata().put("partial", false),
            partialMetadata().put("partial", "true"),
            partialMetadata().apply { remove("reasonCode") },
            partialMetadata().put("reasonCode", "UNKNOWN"),
            JSONObject(complete).put("reasonCode", "PDF_PARTIAL"),
            JSONObject(complete).put("partial", true).put("reasonCode", "PDF_PARTIAL"),
            JSONObject().put("pageCoverageVersion", 1).put("pagesProcessed", 2).put("totalPages", 3),
            JSONObject().put("partial", true).put("reasonCode", "PDF_PARTIAL")
        )
        values.forEach { assertNull(ReceiptOcrCoverage.fromSavedMetadata(it.toString())) }
    }

    @Test
    fun nonObjectsMalformedAndTrailingContentRemainUnknown() {
        val valid = ReceiptOcrCoverage().toSavedMetadata().toJson()
        listOf("[]", "null", "7", "{", valid + " trailing", valid + "{}").forEach {
            assertNull(ReceiptOcrCoverage.fromSavedMetadata(it))
        }
    }

    @Test
    fun readBoundaryAllowsLimitAndRejectsOneMoreCharacter() {
        val encoded = ReceiptOcrCoverage().toSavedMetadata().toJson()
        val atLimit = encoded + " ".repeat(ReceiptOcrCoverage.MAX_SAVED_METADATA_CHARS - encoded.length)
        assertEquals(ReceiptOcrCoverage.MAX_SAVED_METADATA_CHARS, atLimit.length)
        assertEquals(ReceiptOcrCoverage(), ReceiptOcrCoverage.fromSavedMetadata(atLimit))
        assertNull(ReceiptOcrCoverage.fromSavedMetadata(atLimit + " "))
    }

    @Test
    fun maximumIntegerPageCountDoesNotOverflow() {
        val coverage = ReceiptOcrCoverage(Int.MAX_VALUE, Int.MAX_VALUE, 0)
        assertEquals(coverage, ReceiptOcrCoverage.fromSavedMetadata(coverage.toSavedMetadata().toJson()))
    }

    @Test
    fun unknownMetadataIsNotTransportedOutOfDecoder() {
        val fields = partialMetadata()
            .put("txCorrelationId", "fixture-correlation")
            .put("rawOcrText", "must-not-be-forwarded")
            .put("unknownField", "must-not-be-forwarded")
        val restored = ReceiptOcrCoverage.fromSavedMetadata(fields.toString())
        assertEquals(ReceiptOcrCoverage(2, 3, 1), restored)
        val forwarded = restored!!.toSavedMetadata().toJson()
        assertFalse(forwarded.contains("must-not-be-forwarded"))
        assertFalse(forwarded.contains("rawOcrText"))
        assertFalse(forwarded.contains("unknownField"))
        assertFalse(forwarded.contains("txCorrelationId"))
    }
}
