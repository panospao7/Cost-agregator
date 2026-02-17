package com.yourname.expensetracker.domain.util

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Production implementation of TimeProvider.
 * Delegates to System.currentTimeMillis().
 */
@Singleton
class SystemTimeProvider @Inject constructor() : TimeProvider {
    override fun now(): Long = System.currentTimeMillis()
}
