package com.yourname.expensetracker.domain.receipt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Focused tests for [uniqueTempFileName] — the UUID-based temp-file naming
 * used by [ReceiptOcrService].
 *
 * Verifies that temp names are always non-empty and unique across calls so
 * OCR/PDF cache artifacts never collide (replacing the old
 * `System.nanoTime()`-based names without any G-TIME-01 exception).
 */
class ReceiptOcrTempNameTest {

    @Test
    fun temp_names_are_non_empty_and_well_formed() {
        repeat(20) {
            val name = uniqueTempFileName("temp_ocr", "jpg")
            assertTrue("temp name must not be blank", name.isNotBlank())
            assertTrue("temp name must keep the prefix", name.startsWith("temp_ocr_"))
            assertTrue("temp name must keep the extension", name.endsWith(".jpg"))
            assertFalse(
                "temp name must not be purely the prefix/extension",
                name == "temp_ocr_.jpg"
            )
        }
    }

    @Test
    fun temp_names_are_unique_across_calls() {
        val names = (1..500).map { uniqueTempFileName("temp_pdf", "pdf") }
        assertEquals(
            "every generated temp name must be unique",
            500,
            names.toSet().size
        )
    }

    @Test
    fun unique_temp_file_name_for_camera_jpg_has_prefix_suffix_uuid_and_uniqueness() {
        val names = (1..250).map { uniqueTempFileName("camera", "jpg") }

        assertEquals(
            "every generated temp name must be unique",
            250,
            names.toSet().size
        )
        names.forEach { name ->
            assertTrue("temp name must start with camera_ prefix", name.startsWith("camera_"))
            assertTrue("temp name must end with .jpg suffix", name.endsWith(".jpg"))
            val uuidComponent = name.removePrefix("camera_").removeSuffix(".jpg")
            assertTrue("uuid component must be non-empty", uuidComponent.isNotBlank())
            assertEquals(
                "uuid component must be a valid UUID",
                uuidComponent,
                UUID.fromString(uuidComponent).toString()
            )
        }
    }

    @Test
    fun temp_names_are_unique_across_prefixes() {
        val pdfName = uniqueTempFileName("temp_pdf", "pdf")
        val thumbName = uniqueTempFileName("temp_pdf_thumb", "pdf")
        val ocrName = uniqueTempFileName("temp_ocr", "jpg")
        assertTrue(
            "names for different prefixes must differ",
            setOf(pdfName, thumbName, ocrName).size == 3
        )
    }
}
