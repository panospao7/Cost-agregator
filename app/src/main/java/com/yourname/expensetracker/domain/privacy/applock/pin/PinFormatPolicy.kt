package com.yourname.expensetracker.domain.privacy.applock.pin

/** Why a candidate PIN was rejected, or [VALID]. A closed enum: it never carries any PIN text. */
enum class PinFormatResult {
    VALID,
    TOO_SHORT,
    TOO_LONG,
    NOT_ASCII_DIGITS,
    ALL_SAME,
    SEQUENTIAL_RUN,
    REPEATED_BLOCK
}

/**
 * Pure PIN format rules (domain-spec 6.2 / OD-13, S24 condition A, INV-38).
 *
 * A PIN is [MIN_LENGTH]..[MAX_LENGTH] ASCII digits. The simple-sequence rule rejects:
 * - all digits the same (a block of period 1);
 * - a whole-string run that rises by 1 at every step (`123456`) or falls by 1 at every step (`987654`),
 *   without wrap-around (`890123` is not a run);
 * - a repeated block of period 1..[MAX_BLOCK_PERIOD]: every digit equals the digit [period] positions
 *   before it (`121212`, `123123`, `12312312`), whether or not the length is a multiple of the period.
 *
 * Operates on a [CharArray] and never creates a String. It does not zero the array: the caller owns it.
 */
object PinFormatPolicy {
    const val MIN_LENGTH: Int = 6
    const val MAX_LENGTH: Int = 12
    const val MAX_BLOCK_PERIOD: Int = 3

    fun validate(pin: CharArray): PinFormatResult {
        val n = pin.size
        if (n < MIN_LENGTH) return PinFormatResult.TOO_SHORT
        if (n > MAX_LENGTH) return PinFormatResult.TOO_LONG
        for (c in pin) {
            if (c < '0' || c > '9') return PinFormatResult.NOT_ASCII_DIGITS
        }
        if (isRepeatedBlock(pin, 1)) return PinFormatResult.ALL_SAME
        if (isRun(pin, 1) || isRun(pin, -1)) return PinFormatResult.SEQUENTIAL_RUN
        for (period in 2..MAX_BLOCK_PERIOD) {
            if (isRepeatedBlock(pin, period)) return PinFormatResult.REPEATED_BLOCK
        }
        return PinFormatResult.VALID
    }

    /** True when every digit is exactly [step] away from the previous one (no wrap-around). */
    private fun isRun(pin: CharArray, step: Int): Boolean {
        for (i in 1 until pin.size) {
            if (pin[i].code - pin[i - 1].code != step) return false
        }
        return true
    }

    /** True when every digit from index [period] on equals the digit [period] positions before it. */
    private fun isRepeatedBlock(pin: CharArray, period: Int): Boolean {
        for (i in period until pin.size) {
            if (pin[i] != pin[i - period]) return false
        }
        return true
    }
}
