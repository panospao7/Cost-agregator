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
     * Safe to call repeatedly — implementations use the spec's KEEP policy (a version bump
     * forces REPLACE), so an already-armed midnight request is not disturbed. The worker's own
     * midnight self-reschedule overrides the policy with APPEND_OR_REPLACE so the chain is
     * always re-armed behind the currently running request.
     */
    fun scheduleDailyBriefing()

    /** Cancel any pending or running daily briefing jobs. */
    fun cancelDailyBriefing()
}
