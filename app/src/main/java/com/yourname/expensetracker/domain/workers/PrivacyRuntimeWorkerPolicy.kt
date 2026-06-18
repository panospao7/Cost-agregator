package com.yourname.expensetracker.domain.workers

import com.yourname.expensetracker.domain.privacy.PrivacySettings

/**
 * Declarative mapping from privacy toggles to the background workers they gate.
 *
 * P9-P1-11 / PR8: Privacy-setting changes used to drive worker cancel/reschedule
 * from hardcoded worker-name strings inside
 * [com.yourname.expensetracker.data.privacy.PrivacySettingsRepositoryImpl]. That
 * approach had two bugs this policy fixes:
 *
 *  1. Disabling background location cancelled `merchant_key_backfill`, even though
 *     merchant-key generation is **local** (not location-based). Only
 *     `location_backfill` is gated by background location here.
 *  2. The repository only ever cancelled on disable — it never re-scheduled a
 *     worker when a toggle was turned back ON. This policy exposes
 *     [workersToReschedule] so the repository can re-enable workers symmetrically.
 *
 * The mapping is the single source of truth for privacy worker gating. The
 * repository computes which toggles flipped (`true -> false` vs `false -> true`)
 * and asks this policy which workers to cancel or reschedule. Rescheduling is
 * routed back through [WorkerRegistry] so each [WorkerSpec.enabled] flag is still
 * honoured (a disabled spec cancels rather than enqueues).
 *
 * ## Invariants
 *  - Every worker name referenced here exists in [WorkerSpec.DEFAULTS] (enforced
 *    in [init] and re-asserted by `PrivacyRuntimeWorkerPolicyTest`).
 *  - [cancelExemptWorkers] (currently `data_retention`) is never cancelled by a
 *    privacy toggle: retention/cleanup must keep running to purge data that was
 *    already collected before the toggle was turned off.
 */
object PrivacyRuntimeWorkerPolicy {

    /** Privacy capability toggles that gate background workers at runtime. */
    enum class PrivacyToggle {
        /** Cloud AI features (gates cloud-AI workers such as the daily briefing). */
        CLOUD_AI,

        /** Background location backfill (gates the location backfill worker only). */
        BACKGROUND_LOCATION_BACKFILL,

        /** Notification capture (gates notification-derived processing workers). */
        NOTIFICATION_CAPTURE
    }

    /**
     * For each privacy toggle, the set of worker names it gates.
     *
     * Disabling the toggle cancels these workers (minus [cancelExemptWorkers]);
     * re-enabling it reschedules them via [WorkerRegistry].
     *
     * NOTE: `merchant_key_backfill` is intentionally **absent** — merchant-key
     * generation is local and must not be cancelled when background location is
     * disabled.
     */
    private val gatedWorkers: Map<PrivacyToggle, Set<String>> = mapOf(
        PrivacyToggle.CLOUD_AI to setOf("ai_daily_briefing"),
        PrivacyToggle.BACKGROUND_LOCATION_BACKFILL to setOf("location_backfill"),
        PrivacyToggle.NOTIFICATION_CAPTURE to setOf(
            "receipt_matching",
            "warranty_expiration_check",
            "bill_reminder_periodic"
        )
    )

    /**
     * Workers that must never be cancelled by a privacy toggle.
     *
     * `data_retention` purges already-collected raw data; cancelling it would
     * leave the very data the user is trying to stop collecting on disk.
     */
    val cancelExemptWorkers: Set<String> = setOf("data_retention")

    /** Every worker name referenced by this policy (used by tests for validation). */
    val allReferencedWorkerNames: Set<String> =
        gatedWorkers.values.flatten().toSet()

    init {
        // Fail-fast invariant: a typo or stale name here would silently fail to
        // cancel/reschedule a worker. Validate against the spec source of truth.
        val unknown = allReferencedWorkerNames - WorkerSpec.DEFAULTS.keys
        require(unknown.isEmpty()) {
            "PrivacyRuntimeWorkerPolicy references unknown worker names: $unknown"
        }
    }

    /**
     * The workers to cancel for the given set of toggles that flipped `true -> false`.
     *
     * [cancelExemptWorkers] is always excluded — `data_retention` keeps running.
     */
    fun workersToCancel(disabledToggles: Set<PrivacyToggle>): Set<String> =
        disabledToggles.flatMap { gatedWorkers[it].orEmpty() }.toSet() - cancelExemptWorkers

    /**
     * The workers to reschedule for the given set of toggles that flipped `false -> true`.
     *
     * Callers must route these through [WorkerRegistry] so a disabled
     * [WorkerSpec] is still respected (it will cancel rather than enqueue).
     */
    fun workersToReschedule(enabledToggles: Set<PrivacyToggle>): Set<String> =
        enabledToggles.flatMap { gatedWorkers[it].orEmpty() }.toSet() - cancelExemptWorkers

    /** Current on/off state of every privacy toggle for the given settings. */
    private fun toggleStates(settings: PrivacySettings): Map<PrivacyToggle, Boolean> = mapOf(
        PrivacyToggle.CLOUD_AI to settings.cloudAiEnabled,
        PrivacyToggle.BACKGROUND_LOCATION_BACKFILL to settings.backgroundLocationBackfillEnabled,
        PrivacyToggle.NOTIFICATION_CAPTURE to settings.notificationCaptureEnabled
    )

    /** Toggles that went `true -> false` between [old] and [persisted]. */
    fun disabledToggles(old: PrivacySettings, persisted: PrivacySettings): Set<PrivacyToggle> {
        val before = toggleStates(old)
        val after = toggleStates(persisted)
        return PrivacyToggle.values()
            .filter { before[it] == true && after[it] == false }
            .toSet()
    }

    /** Toggles that went `false -> true` between [old] and [persisted]. */
    fun enabledToggles(old: PrivacySettings, persisted: PrivacySettings): Set<PrivacyToggle> {
        val before = toggleStates(old)
        val after = toggleStates(persisted)
        return PrivacyToggle.values()
            .filter { before[it] == false && after[it] == true }
            .toSet()
    }
}
