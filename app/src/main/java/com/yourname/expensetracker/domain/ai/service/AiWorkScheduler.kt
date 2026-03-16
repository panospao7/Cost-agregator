package com.yourname.expensetracker.domain.ai.service

/**
 * Abstraction for scheduling and cancelling AI background work.
 *
 * Keeps WorkManager details out of the domain layer so that use cases and
 * ViewModels can depend on this interface without an Android framework import.
 */
interface AiWorkScheduler {
    /**
     * Enqueue a periodic daily briefing generation job.
     * Safe to call repeatedly — implementations must use a keep-existing policy.
     */
    fun scheduleDailyBriefing()

    /** Cancel any pending or running daily briefing jobs. */
    fun cancelDailyBriefing()
}
