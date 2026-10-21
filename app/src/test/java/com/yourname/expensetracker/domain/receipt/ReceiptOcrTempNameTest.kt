package com.yourname.expensetracker.domain.receipt

import org.junit.Assert.assertEquals
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

    private data class NameRow(val prefix: String, val extension: String, val calls: Int)

    private val nameRows = listOf(
        NameRow("temp_ocr", "jpg", 20),
        NameRow("camera", "jpg", 250),
        NameRow("temp_pdf", "pdf", 500),
        NameRow("temp_pdf_thumb", "pdf", 20)
    )

    @Test
    fun temp_names_have_prefix_valid_uuid_suffix_and_are_unique_per_prefix() {
        assertEquals("nameRows size guard", 4, nameRows.size)
        for (row in nameRows) {
            val label = "[${row.prefix}.${row.extension}]"
            val names = (1..row.calls).map { uniqueTempFileName(row.prefix, row.extension) }

            assertEquals("$label every generated temp name must be unique", row.calls, names.toSet().size)
            names.forEach { name ->
                assertTrue("$label temp name must not be blank", name.isNotBlank())
                assertTrue("$label temp name must keep the ${row.prefix}_ prefix", name.startsWith("${row.prefix}_"))
                assertTrue("$label temp name must keep the .${row.extension} suffix", name.endsWith(".${row.extension}"))
                val uuidComponent = name.removePrefix("${row.prefix}_").removeSuffix(".${row.extension}")
                assertTrue("$label uuid component must be non-empty", uuidComponent.isNotBlank())
                assertEquals(
                    "$label uuid component must be a valid UUID",
                    uuidComponent,
                    UUID.fromString(uuidComponent).toString()
                )
            }
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
