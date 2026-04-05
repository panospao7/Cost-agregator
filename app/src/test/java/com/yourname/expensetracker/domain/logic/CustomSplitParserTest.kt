package com.yourname.expensetracker.domain.logic

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CustomSplitParserTest {

    @Test
    fun `parseAndValidate rejects malformed partial payload`() {
        val result = CustomSplitParser.parseAndValidate(
            splitsString = "1:30,2",
            splitType = CustomSplitMode.CUSTOM_AMOUNT,
            totalAmount = 100.0,
            groupMemberIds = setOf(1L, 2L)
        )

        assertThat(result).isInstanceOf(CustomSplitParseResult.Invalid::class.java)
    }

    @Test
    fun `parseAndValidate rejects unknown member id`() {
        val result = CustomSplitParser.parseAndValidate(
            splitsString = "1:50,99:50",
            splitType = CustomSplitMode.CUSTOM_AMOUNT,
            totalAmount = 100.0,
            groupMemberIds = setOf(1L, 2L)
        )

        assertThat(result).isInstanceOf(CustomSplitParseResult.Invalid::class.java)
    }

    @Test
    fun `parseAndValidate rejects duplicate member id`() {
        val result = CustomSplitParser.parseAndValidate(
            splitsString = "1:40,1:60",
            splitType = CustomSplitMode.CUSTOM_AMOUNT,
            totalAmount = 100.0,
            groupMemberIds = setOf(1L)
        )

        assertThat(result).isInstanceOf(CustomSplitParseResult.Invalid::class.java)
    }

    @Test
    fun `parseAndValidate requires full member map`() {
        val result = CustomSplitParser.parseAndValidate(
            splitsString = "1:100",
            splitType = CustomSplitMode.CUSTOM_AMOUNT,
            totalAmount = 100.0,
            groupMemberIds = setOf(1L, 2L)
        )

        assertThat(result).isInstanceOf(CustomSplitParseResult.Invalid::class.java)
    }

    @Test
    fun `parseAndValidate validates percentage totals`() {
        val result = CustomSplitParser.parseAndValidate(
            splitsString = "1:60,2:30",
            splitType = CustomSplitMode.CUSTOM_PERCENT,
            totalAmount = 100.0,
            groupMemberIds = setOf(1L, 2L)
        )

        assertThat(result).isInstanceOf(CustomSplitParseResult.Invalid::class.java)
    }

    @Test
    fun `parseAndValidate validates amount totals`() {
        val result = CustomSplitParser.parseAndValidate(
            splitsString = "1:60,2:30",
            splitType = CustomSplitMode.CUSTOM_AMOUNT,
            totalAmount = 100.0,
            groupMemberIds = setOf(1L, 2L)
        )

        assertThat(result).isInstanceOf(CustomSplitParseResult.Invalid::class.java)
    }

    @Test
    fun `parseAndValidate accepts fully valid custom amount map`() {
        val result = CustomSplitParser.parseAndValidate(
            splitsString = "1:55.25,2:44.75",
            splitType = CustomSplitMode.CUSTOM_AMOUNT,
            totalAmount = 100.0,
            groupMemberIds = setOf(1L, 2L)
        )

        assertThat(result).isInstanceOf(CustomSplitParseResult.Valid::class.java)
    }

    @Test
    fun `parseAndValidate rejects non-finite total amount`() {
        val result = CustomSplitParser.parseAndValidate(
            splitsString = "1:50,2:50",
            splitType = CustomSplitMode.CUSTOM_AMOUNT,
            totalAmount = Double.POSITIVE_INFINITY,
            groupMemberIds = setOf(1L, 2L)
        )

        assertThat(result).isInstanceOf(CustomSplitParseResult.Invalid::class.java)
        assertThat((result as CustomSplitParseResult.Invalid).reason).contains("Total amount must be finite")
    }

    @Test
    fun `parseAndValidate rejects NaN split value`() {
        val result = CustomSplitParser.parseAndValidate(
            splitsString = "1:NaN,2:50",
            splitType = CustomSplitMode.CUSTOM_AMOUNT,
            totalAmount = 50.0,
            groupMemberIds = setOf(1L, 2L)
        )

        assertThat(result).isInstanceOf(CustomSplitParseResult.Invalid::class.java)
        assertThat((result as CustomSplitParseResult.Invalid).reason).contains("Non-finite split value")
    }

    @Test
    fun `parseAndValidate rejects Infinity split value`() {
        val result = CustomSplitParser.parseAndValidate(
            splitsString = "1:Infinity,2:50",
            splitType = CustomSplitMode.CUSTOM_AMOUNT,
            totalAmount = 50.0,
            groupMemberIds = setOf(1L, 2L)
        )

        assertThat(result).isInstanceOf(CustomSplitParseResult.Invalid::class.java)
        assertThat((result as CustomSplitParseResult.Invalid).reason).contains("Non-finite split value")
    }

    @Test
    fun `referencesMember detects referenced id in invalid payload`() {
        val parseResult = CustomSplitParser.parseAndValidate(
            splitsString = "1:40,2",
            splitType = CustomSplitMode.CUSTOM_AMOUNT,
            totalAmount = 100.0,
            groupMemberIds = setOf(1L, 2L)
        )

        val referenced = CustomSplitParser.referencesMember(
            splitsString = "1:40,2",
            memberId = 1L,
            parseResult = parseResult
        )

        assertThat(referenced).isTrue()
    }
}
