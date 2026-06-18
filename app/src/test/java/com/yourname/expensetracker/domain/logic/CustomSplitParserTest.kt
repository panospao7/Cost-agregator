package com.yourname.expensetracker.domain.logic

import com.yourname.expensetracker.assertApproxEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomSplitParserTest {

    private val members = setOf(1L, 2L, 3L)

    @Test
    fun `parseAndValidate rejects equal mode because no custom payload is required`() {
        val result = CustomSplitParser.parseAndValidate(
            splitsString = "1:50,2:50",
            splitType = CustomSplitMode.EQUAL,
            totalAmount = 100.0,
            groupMemberIds = setOf(1L, 2L)
        )

        assertTrue(result is CustomSplitParseResult.Invalid)
    }

    @Test
    fun `parseAndValidate accepts custom amount split that sums exactly to total`() {
        val result = CustomSplitParser.parseAndValidate(
            splitsString = "1:50.00,2:30.00,3:20.00",
            splitType = CustomSplitMode.CUSTOM_AMOUNT,
            totalAmount = 100.0,
            groupMemberIds = members
        )

        assertTrue(result is CustomSplitParseResult.Valid)
        result as CustomSplitParseResult.Valid
        assertApproxEquals(100.0, result.splits.values.sum(), 0.001)
    }

    @Test
    fun `parseAndValidate accepts custom amount split at AMOUNT_TOLERANCE boundary 0_01`() {
        val result = CustomSplitParser.parseAndValidate(
            splitsString = "1:60.00,2:20.00,3:19.99",
            splitType = CustomSplitMode.CUSTOM_AMOUNT,
            totalAmount = 100.0,
            groupMemberIds = members
        )

        assertTrue(result is CustomSplitParseResult.Invalid)
    }

    @Test
    fun `parseAndValidate rejects custom amount split beyond AMOUNT_TOLERANCE`() {
        val result = CustomSplitParser.parseAndValidate(
            splitsString = "1:60.00,2:20.00,3:19.98",
            splitType = CustomSplitMode.CUSTOM_AMOUNT,
            totalAmount = 100.0,
            groupMemberIds = members
        )

        assertTrue(result is CustomSplitParseResult.Invalid)
    }

    @Test
    fun `parseAndValidate accepts custom percent split at PERCENT_TOLERANCE boundary 0_1`() {
        val result = CustomSplitParser.parseAndValidate(
            splitsString = "1:33.3,2:33.3,3:33.3",
            splitType = CustomSplitMode.CUSTOM_PERCENT,
            totalAmount = 100.0,
            groupMemberIds = members
        )

        assertTrue(result is CustomSplitParseResult.Invalid)
        result as CustomSplitParseResult.Invalid
        assertApproxEquals(99.9, result.parsedSplits.values.sum(), 0.001)
    }

    @Test
    fun `parseAndValidate rejects custom percent split beyond PERCENT_TOLERANCE`() {
        val result = CustomSplitParser.parseAndValidate(
            splitsString = "1:33.3,2:33.3,3:33.29",
            splitType = CustomSplitMode.CUSTOM_PERCENT,
            totalAmount = 100.0,
            groupMemberIds = members
        )

        assertTrue(result is CustomSplitParseResult.Invalid)
    }

    @Test
    fun `parseAndValidate accepts unequal split when sums match total`() {
        val result = CustomSplitParser.parseAndValidate(
            splitsString = "1:70.0,2:20.0,3:10.0",
            splitType = CustomSplitMode.UNEQUAL,
            totalAmount = 100.0,
            groupMemberIds = members
        )

        assertTrue(result is CustomSplitParseResult.Valid)
    }

    @Test
    fun `parseAndValidate rejects split with unknown member duplicate and negative values`() {
        val unknownMember = CustomSplitParser.parseAndValidate(
            splitsString = "1:50,2:30,99:20",
            splitType = CustomSplitMode.CUSTOM_AMOUNT,
            totalAmount = 100.0,
            groupMemberIds = members
        )
        val duplicateMember = CustomSplitParser.parseAndValidate(
            splitsString = "1:50,1:30,3:20",
            splitType = CustomSplitMode.CUSTOM_AMOUNT,
            totalAmount = 100.0,
            groupMemberIds = members
        )
        val negativeValue = CustomSplitParser.parseAndValidate(
            splitsString = "1:120,2:-10,3:-10",
            splitType = CustomSplitMode.CUSTOM_AMOUNT,
            totalAmount = 100.0,
            groupMemberIds = members
        )

        assertTrue(unknownMember is CustomSplitParseResult.Invalid)
        assertTrue(duplicateMember is CustomSplitParseResult.Invalid)
        assertTrue(negativeValue is CustomSplitParseResult.Invalid)
    }

    @Test
    fun `parseAndValidate rejects non finite totals and split values`() {
        val nonFiniteTotal = CustomSplitParser.parseAndValidate(
            splitsString = "1:50,2:30,3:20",
            splitType = CustomSplitMode.CUSTOM_AMOUNT,
            totalAmount = Double.POSITIVE_INFINITY,
            groupMemberIds = members
        )
        val nonFiniteValue = CustomSplitParser.parseAndValidate(
            splitsString = "1:Infinity,2:30,3:20",
            splitType = CustomSplitMode.CUSTOM_AMOUNT,
            totalAmount = 100.0,
            groupMemberIds = members
        )

        assertTrue(nonFiniteTotal is CustomSplitParseResult.Invalid)
        assertTrue(nonFiniteValue is CustomSplitParseResult.Invalid)
    }

    @Test
    fun `referencesMember uses parsed valid result first`() {
        val valid = CustomSplitParser.parseAndValidate(
            splitsString = "1:50,2:30,3:20",
            splitType = CustomSplitMode.CUSTOM_AMOUNT,
            totalAmount = 100.0,
            groupMemberIds = members
        )

        val contains1 = CustomSplitParser.referencesMember("1:50,2:30,3:20", 1L, valid)
        val contains9 = CustomSplitParser.referencesMember("1:50,2:30,3:20", 9L, valid)

        assertTrue(contains1)
        assertFalse(contains9)
    }

    @Test
    fun `referencesMember uses parsed invalid partial result when available`() {
        val invalidPartial = CustomSplitParser.parseAndValidate(
            splitsString = "1:50,2",
            splitType = CustomSplitMode.CUSTOM_AMOUNT,
            totalAmount = 100.0,
            groupMemberIds = setOf(1L, 2L)
        )

        val contains1 = CustomSplitParser.referencesMember("1:50,2", 1L, invalidPartial)
        val contains2 = CustomSplitParser.referencesMember("1:50,2", 2L, invalidPartial)

        assertTrue(contains1)
        assertFalse(contains2)
    }

    @Test
    fun `referencesMember falls back to raw token matching when no parse result provided`() {
        val contains2 = CustomSplitParser.referencesMember("1:50, 2:50", 2L, null)
        val contains9 = CustomSplitParser.referencesMember("1:50, 2:50", 9L, null)

        assertTrue(contains2)
        assertFalse(contains9)
    }
}
