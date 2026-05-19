package com.yourname.expensetracker.domain.privacy

import javax.inject.Inject
import javax.inject.Singleton

/**
 * PRIV-441-12: Injectable registry of all sensitive data retention targets.
 *
 * All [RetentionTarget] implementations are registered via Hilt multibinding
 * and injected here. [DataRetentionWorker] uses this registry instead of an
 * inline list, making the set of targets centrally auditable and extensible.
 */
@Singleton
class RetentionRegistry @Inject constructor(
    private val targets: Set<@JvmSuppressWildcards RetentionTarget>
) {
    fun allTargets(): Set<RetentionTarget> = targets
}
