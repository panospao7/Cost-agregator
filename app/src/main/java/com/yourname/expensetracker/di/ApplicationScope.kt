package com.yourname.expensetracker.di

import javax.inject.Qualifier

/**
 * Marks a [CoroutineScope] that is tied to the application lifecycle.
 *
 * Use this scope for long-running operations that should survive ViewModel destruction,
 * such as periodic background tasks or application-wide monitoring.
 */
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class ApplicationScope
