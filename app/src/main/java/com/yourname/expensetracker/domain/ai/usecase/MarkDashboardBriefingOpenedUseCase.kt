package com.yourname.expensetracker.domain.ai.usecase

import com.yourname.expensetracker.domain.ai.service.AiEngagementRepository
import kotlinx.coroutines.CancellationException
import javax.inject.Inject

/**
 * The single owner of the only persistent write a deep link may cause (INV-16, spec 5.3).
 *
 * `lastOpenedDashboardBriefingKey` is written only when the already validated [briefingKey] equals the
 * persisted `lastDeliveredDashboardBriefingKey` at the moment this runs (apply time, read here, never
 * cached). Anything else writes nothing: a key that was never delivered cannot suppress a briefing, so
 * a link from another app or a web page cannot change which briefing is shown (closes advisory C4).
 *
 * The caller passes a key that `DeepLinkPolicy` already validated for format. Equality with the stored
 * delivered key is what makes the write safe, so the value written is always one the app itself stored.
 *
 * - A read failure (or a missing delivered key) writes nothing. A read failure is reported as
 *   [Outcome.STATE_UNREADABLE]; a missing or different key as [Outcome.NOT_DELIVERED].
 * - A write failure is also reported as [Outcome.STATE_UNREADABLE] (the engagement store cannot be used).
 * - `CancellationException` always propagates.
 * - No outcome carries the key; callers must not log it (INV-18).
 */
class MarkDashboardBriefingOpenedUseCase @Inject constructor(
    private val aiEngagementRepository: AiEngagementRepository
) {

    /** Closed result set; maps to the `DL_BRIEFING_*` diagnostics (or none for [WRITTEN]). */
    enum class Outcome {
        /** The key equalled the last delivered key; `lastOpened` was set to it. */
        WRITTEN,

        /** The key does not equal the last delivered key (or none was delivered). Nothing written. */
        NOT_DELIVERED,

        /** The engagement store could not be read or written. Nothing (reliably) written. */
        STATE_UNREADABLE
    }

    suspend operator fun invoke(briefingKey: String): Outcome {
        val delivered: String? = try {
            aiEngagementRepository.getLastDeliveredDashboardBriefingKey()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return Outcome.STATE_UNREADABLE
        }

        if (delivered == null || delivered != briefingKey) return Outcome.NOT_DELIVERED

        return try {
            aiEngagementRepository.setLastOpenedDashboardBriefingKey(delivered)
            Outcome.WRITTEN
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            Outcome.STATE_UNREADABLE
        }
    }
}
