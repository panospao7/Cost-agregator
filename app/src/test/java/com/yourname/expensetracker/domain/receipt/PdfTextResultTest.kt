package com.yourname.expensetracker.domain.receipt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the statement text path assembly (no PDFBox). All values are invented. */
class PdfTextResultTest {

    private fun page(number: Int, filler: Int = 40): String =
        "Line $number first row " + "x".repeat(filler) + "\nLine $number second row"

    private fun pages(count: Int, filler: Int = 40): List<String> = (1..count).map { page(it, filler) }

    private fun build(
        pages: List<String>,
        totalPages: Int = pages.size,
        pageCap: Int = STATEMENT_TEXT_PAGE_CAP,
        charCap: Int = STATEMENT_TEXT_CHAR_CAP,
        failedPages: Int = 0
    ) = buildPdfTextResult(pages, totalPages, pageCap, charCap, failedPages)

    @Test
    fun `caps are the documented statement limits`() {
        assertEquals(200, STATEMENT_TEXT_PAGE_CAP)
        assertEquals(2_000_000, STATEMENT_TEXT_CHAR_CAP)
    }

    @Test
    fun `page cap keeps 200 of 205 pages and marks the result partial`() {
        val result = build(pages(205), totalPages = 205)!!
        assertEquals(200, result.pdfTextPages!!.size)
        assertEquals(200, result.pagesProcessed)
        assertEquals(205, result.totalPages)
        assertTrue(result.isPartial)
        assertTrue(result.fullText.contains("Line 200 first row"))
        assertFalse(result.fullText.contains("Line 201 first row"))
    }

    @Test
    fun `char cap stops at the last page that fits and marks the result partial`() {
        val each = page(1).length
        val result = build(pages(10), totalPages = 10, charCap = each * 3 + 5)!!
        assertEquals(3, result.pdfTextPages!!.size)
        assertEquals(3, result.pagesProcessed)
        assertEquals(10, result.totalPages)
        assertTrue(result.isPartial)
        assertFalse(result.fullText.contains("Line 4 first row"))
    }

    @Test
    fun `everything under both caps is read and not partial`() {
        val result = build(pages(48), totalPages = 48)!!
        assertEquals(48, result.pdfTextPages!!.size)
        assertEquals(48, result.pagesProcessed)
        assertEquals(48, result.totalPages)
        assertNull(result.failedPages)
        assertFalse(result.isPartial)
    }

    @Test
    fun `exactly at the page cap is not partial`() {
        val result = build(pages(200), totalPages = 200)!!
        assertEquals(200, result.pagesProcessed)
        assertFalse(result.isPartial)
    }

    @Test
    fun `blank page between text pages keeps its index and counts as processed`() {
        val input = listOf(page(1), "", page(3))
        val result = build(input)!!
        val perPage = result.pdfTextPages!!
        assertEquals(3, perPage.size)
        assertTrue(perPage[1].all { it.isBlank() })
        assertTrue(perPage[2].first().startsWith("Line 3 first row"))
        assertEquals(3, result.pagesProcessed)
        assertFalse(result.isPartial)
    }

    @Test
    fun `lines are split per page and blocks skip blank lines`() {
        val result = build(listOf(page(1), page(2)))!!
        val perPage = result.pdfTextPages!!
        assertEquals(2, perPage.size)
        assertEquals(2, perPage[0].size)
        assertEquals("Line 1 second row", perPage[0][1])
        assertEquals("Line 2 second row", perPage[1][1])
        assertEquals(4, result.blocks.size)
        assertEquals("Line 2 second row", result.blocks.last().text)
        assertEquals("", result.savedImagePath)
    }

    @Test
    fun `page text with Windows line endings does not leave carriage returns`() {
        val text = "Row one " + "y".repeat(60) + "\r\nRow two " + "y".repeat(60)
        val lines = build(listOf(text))!!.pdfTextPages!!.single()
        assertEquals(2, lines.size)
        assertTrue(lines.none { it.contains('\r') })
    }

    @Test
    fun `text of 100 chars or fewer returns null so the caller falls back to OCR`() {
        assertNull(build(listOf("x".repeat(100))))
        assertNull(build(listOf("   ", "", "short")))
        assertNull(build(emptyList(), totalPages = 0))
        assertNotNull(build(listOf("x".repeat(101))))
    }

    @Test
    fun `200 image-only pages extracted as newlines return null`() {
        assertNull(build(List(200) { "\n" }, totalPages = 200))
    }

    @Test
    fun `150 empty pages return null`() {
        assertNull(build(List(150) { "" }, totalPages = 150))
    }

    @Test
    fun `scanned statement with only a page footer per page returns null`() {
        val footers = (1..60).map { "Page $it of 60\n" }
        assertNull(build(footers, totalPages = 60))
    }

    @Test
    fun `real text statement still returns a result`() {
        val result = build(pages(8), totalPages = 8)
        assertNotNull(result)
        assertEquals(8, result!!.pdfTextPages!!.size)
    }

    @Test
    fun `text only on pages 6 and later returns null like the 5-page path`() {
        val input = List(5) { "" } + pages(10)
        assertNull(build(input, totalPages = 15))
    }

    @Test
    fun `failed pages are not counted as processed and make the result partial`() {
        val input = listOf(page(1), "", page(3))
        val result = build(input, totalPages = 3, failedPages = 1)!!
        assertEquals(2, result.pagesProcessed)
        assertEquals(3, result.totalPages)
        assertEquals(1, result.failedPages)
        assertTrue(result.isPartial)
        assertEquals(3, result.pdfTextPages!!.size)
    }

    @Test
    fun `a first page larger than the char cap yields null`() {
        assertNull(build(listOf(page(1, filler = 500)), charCap = 100))
    }

    @Test
    fun `explicit smaller page cap is honoured`() {
        val result = build(pages(10), totalPages = 10, pageCap = 5)!!
        assertEquals(5, result.pdfTextPages!!.size)
        assertEquals(5, result.pagesProcessed)
        assertTrue(result.isPartial)
    }

    @Test
    fun `existing positional OcrResult construction leaves pdfTextPages null`() {
        val ocr = OcrResult("text", emptyList(), "/path")
        assertNull(ocr.pdfTextPages)
        assertFalse(ocr.isPartial)
    }
}
