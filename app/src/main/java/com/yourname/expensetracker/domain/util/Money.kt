package com.yourname.expensetracker.domain.util

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * HIGH FIX (HIGH-2): Money value class for precise financial calculations.
 * 
 * Replaces Double arithmetic which has rounding errors (e.g., 0.1 + 0.2 != 0.3).
 * Uses BigDecimal with proper scale and rounding for monetary calculations.
 * 
 * Benefits:
 * - Precision: No floating point rounding errors
 * - Safety: Type-safe money operations
 * - Consistency: Uniform rounding across the app
 * - Correctness: Financial calculations accurate to cents
 * 
 * Example:
 * ```kotlin
 * // Before (wrong):
 * val split = 100.0 / 3  // 33.333333333...
 * 
 * // After (correct):
 * val split = Money(100.0).divide(3)  // 33.33
 * ```
 */
@JvmInline
value class Money(val amount: BigDecimal) {
    
    companion object {
        private const val DEFAULT_SCALE = 2
        private val DEFAULT_ROUNDING = RoundingMode.HALF_UP
        
        val ZERO = Money(BigDecimal.ZERO)
        
        fun fromDouble(value: Double): Money {
            return Money(BigDecimal(value.toString()))
        }
        
        fun fromString(value: String): Money {
            return Money(BigDecimal(value))
        }
        
        fun cents(cents: Long): Money {
            return Money(BigDecimal(cents).movePointLeft(2))
        }
    }
    
    init {
        require(amount.scale() >= 0) { "Money amount must have non-negative scale" }
    }
    
    operator fun plus(other: Money): Money {
        return Money(amount.add(other.amount))
    }
    
    operator fun minus(other: Money): Money {
        return Money(amount.subtract(other.amount))
    }
    
    operator fun times(multiplier: Money): Money {
        return Money(amount.multiply(multiplier.amount))
    }
    
    operator fun times(multiplier: BigDecimal): Money {
        return Money(amount.multiply(multiplier))
    }
    
    operator fun times(multiplier: Double): Money {
        return Money(amount.multiply(BigDecimal(multiplier.toString())))
    }
    
    operator fun times(multiplier: Int): Money {
        return Money(amount.multiply(BigDecimal(multiplier)))
    }
    
    /**
     * Divide money by divisor with proper rounding.
     * CRITICAL: Always use this for split calculations to avoid precision errors.
     */
    fun divide(divisor: Int): Money {
        return Money(amount.divide(BigDecimal(divisor), DEFAULT_SCALE, DEFAULT_ROUNDING))
    }
    
    fun divide(divisor: BigDecimal): Money {
        return Money(amount.divide(divisor, DEFAULT_SCALE, DEFAULT_ROUNDING))
    }
    
    fun divide(divisor: Double): Money {
        return Money(amount.divide(BigDecimal(divisor.toString()), DEFAULT_SCALE, DEFAULT_ROUNDING))
    }
    
    /**
     * Calculate percentage of this amount.
     * Example: Money(100.0).percentage(15.0) = 15.0 (15%)
     */
    fun percentage(percent: Double): Money {
        return Money(amount.multiply(BigDecimal(percent.toString()))
            .divide(BigDecimal(100), DEFAULT_SCALE, DEFAULT_ROUNDING))
    }
    
    /**
     * Get absolute value.
     */
    fun abs(): Money {
        return Money(amount.abs())
    }
    
    /**
     * Negate the amount.
     */
    fun negate(): Money {
        return Money(amount.negate())
    }
    
    /**
     * Check if amount is zero.
     */
    fun isZero(): Boolean = amount.compareTo(BigDecimal.ZERO) == 0
    
    /**
     * Check if amount is positive.
     */
    fun isPositive(): Boolean = amount.compareTo(BigDecimal.ZERO) > 0
    
    /**
     * Check if amount is negative.
     */
    fun isNegative(): Boolean = amount.compareTo(BigDecimal.ZERO) < 0
    
    /**
     * Convert to Double for display (not for calculations).
     */
    fun toDouble(): Double = amount.toDouble()
    
    /**
     * Format as currency string.
     */
    fun format(): String = String.format("%.2f", amount)
    
    override fun toString(): String = format()
}

/**
 * Extension functions for easy conversion.
 */
fun Double.toMoney(): Money = Money.fromDouble(this)
fun String.toMoney(): Money = Money.fromString(this)
fun BigDecimal.toMoney(): Money = Money(this)

/**
 * Sum a collection of Money amounts.
 */
fun Iterable<Money>.sum(): Money {
    return reduceOrNull { acc, money -> acc + money } ?: Money.ZERO
}

/**
 * Average of Money amounts.
 */
fun Iterable<Money>.averageMoney(): Money {
    val sum = sum()
    val count = count()
    return if (count > 0) sum.divide(count) else Money.ZERO
}
