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

    /**
     * Enqueue one immediate (no initial delay) briefing run, using the daily briefing's
     * constraints, under the same unique work name as the daily chain with REPLACE so the
     * pending midnight request is superseded. The worker re-arms the midnight chain when the
     * run completes, and still passes through the worker execution guard and consent gates.
     */
    fun enqueueBriefingNow()

    /** Cancel any pending or running daily briefing jobs. */
    fun cancelDailyBriefing()
}
