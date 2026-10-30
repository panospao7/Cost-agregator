package com.yourname.expensetracker.domain.currency

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yourname.expensetracker.domain.core.money.CurrencyCode
import com.yourname.expensetracker.domain.core.money.CurrencyFractionDigits
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * MEC H-4: pins the minor-unit digits of the 28 catalog currencies on the device ICU/JDK
 * (domain-spec section 1.5). The JVM unit tests pin the same values against the host JDK;
 * this class proves the device returns the same numbers through the production accessor
 * ([CurrencyAmountPolicy.fractionDigits], which delegates to [CurrencyFractionDigits.of]).
 *
 * Pure assertions: no Hilt, no network, no permission, no new dependency.
 */
@RunWith(AndroidJUnit4::class)
class CurrencyFractionDigitsDeviceTest {

    /** The 28 catalog codes of domain-spec section 1.5: 0 digits for JPY, KRW, ISK; 2 for the rest (HUF included). */
    private val expectedDigits: Map<String, Int> = mapOf(
        "EUR" to 2, "USD" to 2, "GBP" to 2, "CHF" to 2, "CAD" to 2, "AUD" to 2, "NZD" to 2,
        "SGD" to 2, "CNY" to 2, "HKD" to 2, "DKK" to 2, "PLN" to 2, "RON" to 2, "BRL" to 2,
        "BGN" to 2, "HRK" to 2, "MXN" to 2, "ZAR" to 2, "SEK" to 2, "NOK" to 2, "CZK" to 2,
        "TRY" to 2, "INR" to 2, "RUB" to 2, "HUF" to 2,
        "JPY" to 0, "KRW" to 0, "ISK" to 0
    )

    @Test
    fun tableHasTwentyEightCurrencies() {
        assertEquals(28, expectedDigits.size)
    }

    @Test
    fun deviceDigitsMatchSpecForEveryCatalogCurrency() {
        expectedDigits.forEach { (code, digits) ->
            assertEquals(
                "policy digits for $code",
                digits,
                CurrencyAmountPolicy.fractionDigits(CurrencyCode(code))
            )
            assertEquals(
                "accessor digits for $code",
                digits,
                CurrencyFractionDigits.of(CurrencyCode(code))
            )
        }
    }

    @Test
    fun deviceDigitsDriveTheAmountCheck() {
        // JPY allows no decimals, EUR allows two (trailing zeros do not count).
        assertEquals(
            AmountCheck.PrecisionExceeded(0),
            CurrencyAmountPolicy.check(BigDecimal("1500.5"), CurrencyCode("JPY"))
        )
        assertEquals(AmountCheck.Ok, CurrencyAmountPolicy.check(BigDecimal("1500.00"), CurrencyCode("JPY")))
        assertEquals(
            AmountCheck.PrecisionExceeded(2),
            CurrencyAmountPolicy.check(BigDecimal("1.234"), CurrencyCode("EUR"))
        )
        assertEquals(AmountCheck.Ok, CurrencyAmountPolicy.check(BigDecimal("1.23"), CurrencyCode("HUF")))
    }

    @Test
    fun unknownNonIsoCodeIsUnavailableAndFailsClosed() {
        val unknown = CurrencyCode("ZZZ")
        assertNull(CurrencyAmountPolicy.fractionDigits(unknown))
        assertEquals(AmountCheck.DigitsUnavailable, CurrencyAmountPolicy.check(BigDecimal("1"), unknown))
    }
}
