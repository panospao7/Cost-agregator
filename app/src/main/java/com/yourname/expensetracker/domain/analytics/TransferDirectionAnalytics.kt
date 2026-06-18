package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.domain.model.DomainTransferDirection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Data class representing insights about transfer direction detection.
 */
data class TransferInsights(
    val totalTransfers: Int = 0,
    val autoDetectedIncoming: Int = 0,
    val autoDetectedOutgoing: Int = 0,
    val unknownDirections: Int = 0,
    val correctDetections: Int = 0,
    val totalDetections: Int = 0,
    val accuracyPercentage: Float = 0f,
    val detectionRate: Float = 0f,
    val topIncomingSources: List<String> = emptyList(),
    val topOutgoingDestinations: List<String> = emptyList()
) {
    /**
     * True if we have enough data to calculate meaningful statistics.
     */
    val hasEnoughData: Boolean
        get() = totalTransfers >= 10

    /**
     * Formatted accuracy string (e.g., "92.5%")
     */
    val formattedAccuracy: String
        get() = String.format("%.1f%%", accuracyPercentage)

    /**
     * Formatted detection rate string.
     */
    val formattedDetectionRate: String
        get() = String.format("%.1f%%", detectionRate)
}

/**
 * Analytics tracker for transfer direction detection.
 * Collects statistics about how well the automatic detection is working.
 */
@Singleton
class TransferDirectionAnalytics @Inject constructor() {

    companion object {
        private const val MAX_TRACKED_TRANSFERS = 10_000
        private const val TRANSFER_PRUNE_BATCH_SIZE = 2_000
        private const val MAX_TRACKED_ENDPOINTS = 1_000
    }

    private val _insights = MutableStateFlow(TransferInsights())
    val insights: StateFlow<TransferInsights> = _insights.asStateFlow()

    private data class TrackedTransferDetection(
        val autoDetectedDirection: DomainTransferDirection,
        val accountName: String?,
        val wasCorrect: Boolean,
        val correctedDirection: DomainTransferDirection? = null
    )

    private val trackedDetectionsByTransferId = ConcurrentHashMap<Long, TrackedTransferDetection>()
    private val frozenIncomingSources = ConcurrentHashMap<String, Int>()
    private val frozenOutgoingDestinations = ConcurrentHashMap<String, Int>()
    private val stateLock = Any()

    private var frozenTotalTransfers: Int = 0
    private var frozenAutoDetectedIncoming: Int = 0
    private var frozenAutoDetectedOutgoing: Int = 0
    private var frozenUnknownDirections: Int = 0
    private var frozenCorrectDetections: Int = 0
    private var frozenTotalDetections: Int = 0

    /**
     * Record a successful auto-detection.
     */
    fun recordAutoDetection(
        direction: DomainTransferDirection,
        accountName: String?,
        wasCorrect: Boolean = true,
        transferId: Long? = null
    ) {
        synchronized(stateLock) {
            if (transferId != null) {
                val existing = trackedDetectionsByTransferId.putIfAbsent(
                    transferId,
                    TrackedTransferDetection(
                        autoDetectedDirection = direction,
                        accountName = accountName,
                        wasCorrect = wasCorrect
                    )
                )
                if (existing != null) {
                    // Idempotency: this transfer has already been recorded.
                    return
                }
                pruneTransferTrackingIfNeeded()
                publishInsights()
                return
            }

            // Untracked record: fold directly into frozen aggregates.
            frozenTotalTransfers += 1
            frozenTotalDetections += 1
            if (direction == DomainTransferDirection.INCOMING) {
                frozenAutoDetectedIncoming += 1
            } else {
                frozenAutoDetectedOutgoing += 1
            }
            if (wasCorrect) {
                frozenCorrectDetections += 1
            }
            accountName?.let { name ->
                when (direction) {
                    DomainTransferDirection.INCOMING -> frozenIncomingSources.merge(name, 1, Int::plus)
                    DomainTransferDirection.OUTGOING -> frozenOutgoingDestinations.merge(name, 1, Int::plus)
                }
            }
            pruneEndpointTrackingIfNeeded()
            publishInsights()
        }
    }

    /**
     * Record a transfer with unknown direction (detection failed).
     */
    fun recordUnknownDirection() {
        synchronized(stateLock) {
            frozenTotalTransfers += 1
            frozenUnknownDirections += 1
            publishInsights()
        }
    }

    /**
     * Record a user correction (they changed the direction).
     */
    fun recordUserCorrection(fromDirection: DomainTransferDirection?, toDirection: DomainTransferDirection) {
        // Without a transfer id we cannot guarantee idempotent correction accounting.
        // Keep as compatibility no-op; callers should use the transferId overload.
        if (fromDirection == null || fromDirection == toDirection) return
    }

    /**
     * Record a correction for a specific transfer id.
     * Ensures each transfer impacts accuracy at most once unless corrected back.
     */
    fun recordUserCorrection(
        transferId: Long,
        fromDirection: DomainTransferDirection?,
        toDirection: DomainTransferDirection
    ) {
        synchronized(stateLock) {
            val tracked = trackedDetectionsByTransferId[transferId] ?: return
            if (fromDirection != null && fromDirection == toDirection) return

            val correctedDirection = if (toDirection == tracked.autoDetectedDirection) null else toDirection
            if (tracked.correctedDirection == correctedDirection) {
                return
            }

            trackedDetectionsByTransferId[transferId] = tracked.copy(correctedDirection = correctedDirection)
            publishInsights()
        }
    }

    private fun pruneTransferTrackingIfNeeded() {
        val currentSize = trackedDetectionsByTransferId.size
        if (currentSize <= MAX_TRACKED_TRANSFERS) return

        val toRemoveCount = (currentSize - MAX_TRACKED_TRANSFERS + TRANSFER_PRUNE_BATCH_SIZE)
            .coerceAtMost(currentSize)

        val idsToRemove = trackedDetectionsByTransferId.keys
            .asSequence()
            .take(toRemoveCount)
            .toList()

        idsToRemove.forEach { transferId ->
            val removed = trackedDetectionsByTransferId.remove(transferId) ?: return@forEach
            foldTrackedDetectionIntoFrozenAggregates(removed)
        }

        pruneEndpointTrackingIfNeeded()
    }

    private fun pruneEndpointTrackingIfNeeded() {
        pruneEndpointMap(frozenIncomingSources)
        pruneEndpointMap(frozenOutgoingDestinations)
    }

    private fun pruneEndpointMap(map: ConcurrentHashMap<String, Int>) {
        if (map.size <= MAX_TRACKED_ENDPOINTS) return

        val retainedKeys = map.entries
            .sortedByDescending { it.value }
            .take(MAX_TRACKED_ENDPOINTS)
            .map { it.key }
            .toHashSet()

        map.keys.forEach { key ->
            if (!retainedKeys.contains(key)) {
                map.remove(key)
            }
        }
    }

    /**
     * Reset all analytics data.
     */
    fun reset() {
        synchronized(stateLock) {
            _insights.value = TransferInsights()
            frozenIncomingSources.clear()
            frozenOutgoingDestinations.clear()
            trackedDetectionsByTransferId.clear()
            frozenTotalTransfers = 0
            frozenAutoDetectedIncoming = 0
            frozenAutoDetectedOutgoing = 0
            frozenUnknownDirections = 0
            frozenCorrectDetections = 0
            frozenTotalDetections = 0
        }
    }

    private fun foldTrackedDetectionIntoFrozenAggregates(detection: TrackedTransferDetection) {
        val effectiveDirection = detection.correctedDirection ?: detection.autoDetectedDirection
        val effectiveCorrect = detection.wasCorrect && detection.correctedDirection == null

        frozenTotalTransfers += 1
        frozenTotalDetections += 1
        if (effectiveDirection == DomainTransferDirection.INCOMING) {
            frozenAutoDetectedIncoming += 1
        } else {
            frozenAutoDetectedOutgoing += 1
        }
        if (effectiveCorrect) {
            frozenCorrectDetections += 1
        }

        detection.accountName?.let { name ->
            when (effectiveDirection) {
                DomainTransferDirection.INCOMING -> frozenIncomingSources.merge(name, 1, Int::plus)
                DomainTransferDirection.OUTGOING -> frozenOutgoingDestinations.merge(name, 1, Int::plus)
            }
        }
    }

    private fun publishInsights() {
        val trackedIncomingSources = HashMap<String, Int>()
        val trackedOutgoingDestinations = HashMap<String, Int>()
        var trackedIncoming = 0
        var trackedOutgoing = 0
        var trackedDetections = 0
        var trackedCorrect = 0

        trackedDetectionsByTransferId.values.forEach { detection ->
            val effectiveDirection = detection.correctedDirection ?: detection.autoDetectedDirection
            val effectiveCorrect = detection.wasCorrect && detection.correctedDirection == null

            trackedDetections += 1
            if (effectiveDirection == DomainTransferDirection.INCOMING) {
                trackedIncoming += 1
            } else {
                trackedOutgoing += 1
            }
            if (effectiveCorrect) {
                trackedCorrect += 1
            }

            detection.accountName?.let { endpoint ->
                when (effectiveDirection) {
                    DomainTransferDirection.INCOMING -> trackedIncomingSources.merge(endpoint, 1, Int::plus)
                    DomainTransferDirection.OUTGOING -> trackedOutgoingDestinations.merge(endpoint, 1, Int::plus)
                }
            }
        }

        val mergedIncomingSources = mergeEndpointCounts(frozenIncomingSources, trackedIncomingSources)
        val mergedOutgoingDestinations = mergeEndpointCounts(frozenOutgoingDestinations, trackedOutgoingDestinations)

        val totalTransfers = frozenTotalTransfers + trackedDetections
        val autoDetectedIncoming = frozenAutoDetectedIncoming + trackedIncoming
        val autoDetectedOutgoing = frozenAutoDetectedOutgoing + trackedOutgoing
        val totalDetections = frozenTotalDetections + trackedDetections
        val correctDetections = frozenCorrectDetections + trackedCorrect

        val detectionRate = if (totalTransfers > 0) {
            (totalDetections.toFloat() / totalTransfers.toFloat()) * 100f
        } else {
            0f
        }
        val accuracy = if (totalDetections > 0) {
            (correctDetections.toFloat() / totalDetections.toFloat()) * 100f
        } else {
            0f
        }

        _insights.value = TransferInsights(
            totalTransfers = totalTransfers,
            autoDetectedIncoming = autoDetectedIncoming,
            autoDetectedOutgoing = autoDetectedOutgoing,
            unknownDirections = frozenUnknownDirections,
            correctDetections = correctDetections,
            totalDetections = totalDetections,
            accuracyPercentage = accuracy,
            detectionRate = detectionRate,
            topIncomingSources = mergedIncomingSources.entries
                .sortedByDescending { it.value }
                .take(5)
                .map { it.key },
            topOutgoingDestinations = mergedOutgoingDestinations.entries
                .sortedByDescending { it.value }
                .take(5)
                .map { it.key }
        )
    }

    private fun mergeEndpointCounts(
        frozenCounts: Map<String, Int>,
        trackedCounts: Map<String, Int>
    ): Map<String, Int> {
        val merged = HashMap<String, Int>(frozenCounts.size + trackedCounts.size)
        frozenCounts.forEach { (key, value) -> merged[key] = value }
        trackedCounts.forEach { (key, value) -> merged.merge(key, value, Int::plus) }
        return merged
    }

    /**
     * Get a summary report for debugging.
     */
    fun getReport(): String {
        synchronized(stateLock) {
            val i = _insights.value
            return buildString {
                appendLine("=== Transfer Direction Analytics ===")
                appendLine("Total Transfers: ${i.totalTransfers}")
                appendLine("Detection Rate: ${i.formattedDetectionRate}")
                appendLine("Accuracy: ${i.formattedAccuracy}")
                appendLine("Auto-detected Incoming: ${i.autoDetectedIncoming}")
                appendLine("Auto-detected Outgoing: ${i.autoDetectedOutgoing}")
                appendLine("Unknown Directions: ${i.unknownDirections}")
                appendLine()
                appendLine("Top Incoming Sources:")
                i.topIncomingSources.forEach { appendLine("  - $it") }
                appendLine()
                appendLine("Top Outgoing Destinations:")
                i.topOutgoingDestinations.forEach { appendLine("  - $it") }
            }
        }
    }
}
