package com.yourname.expensetracker.domain.export

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * RP-19 (19-C): FX-rate serializer contract — up to six decimals, rounded
 * HALF_UP, never scientific notation, period decimal separator.
 */
class FxRateFormatterTest {

    @Test
    fun `rate keeps up to six decimals`() {
        assertThat(FxRateFormatter.formatRate(0.9123456)).isEqualTo("0.912346")
        assertThat(FxRateFormatter.formatRate(0.915)).isEqualTo("0.915")
        assertThat(FxRateFormatter.formatRate(1.5)).isEqualTo("1.5")
    }

    @Test
    fun `rate is rounded HALF_UP at the sixth decimal`() {
        assertThat(FxRateFormatter.formatRate(0.1234565)).isEqualTo("0.123457")
        assertThat(FxRateFormatter.formatRate(0.1234564)).isEqualTo("0.123456")
    }

    @Test
    fun `rates never use scientific notation`() {
        // Double.toString for these values is "1.0E-7" / "1.23456789E7".
        assertThat(FxRateFormatter.formatRate(0.0000001)).isEqualTo("0")
        assertThat(FxRateFormatter.formatRate(12345678.9)).isEqualTo("12345678.9")
        assertThat(FxRateFormatter.formatRate(0.000001)).isEqualTo("0.000001")
        assertThat(FxRateFormatter.formatRate(0.0000004)).isEqualTo("0")
    }

    @Test
    fun `identity rate serializes without trailing zeros`() {
        assertThat(FxRateFormatter.formatRate(1.0)).isEqualTo("1")
        assertThat(FxRateFormatter.formatRate(0.0)).isEqualTo("0")
    }

    @Test
    fun `negative rate keeps sign without scientific notation`() {
        assertThat(FxRateFormatter.formatRate(-0.9123456)).isEqualTo("-0.912346")
    }

    @Test
    fun `non-finite rates are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { FxRateFormatter.formatRate(Double.NaN) }
        assertThrows(IllegalArgumentException::class.java) { FxRateFormatter.formatRate(Double.POSITIVE_INFINITY) }
        assertThrows(IllegalArgumentException::class.java) { FxRateFormatter.formatRate(Double.NEGATIVE_INFINITY) }
    }
}
