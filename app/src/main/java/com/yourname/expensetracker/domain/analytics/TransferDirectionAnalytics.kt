package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.entity.TransferDirection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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

    private val _insights = MutableStateFlow(TransferInsights())
    val insights: StateFlow<TransferInsights> = _insights.asStateFlow()

    private val incomingSources = ConcurrentHashMap<String, Int>()
    private val outgoingDestinations = ConcurrentHashMap<String, Int>()
    private val autoDetectedDirectionByTransferId = ConcurrentHashMap<Long, TransferDirection>()
    private val correctionAppliedByTransferId = ConcurrentHashMap<Long, Boolean>()

    /**
     * Record a successful auto-detection.
     */
    fun recordAutoDetection(
        direction: TransferDirection,
        accountName: String?,
        wasCorrect: Boolean = true,
        transferId: Long? = null
    ) {
        transferId?.let { id ->
            autoDetectedDirectionByTransferId[id] = direction
            correctionAppliedByTransferId.remove(id)
        }

        // Track source/destination
        accountName?.let { name ->
            when (direction) {
                TransferDirection.INCOMING -> {
                    incomingSources.merge(name, 1, Int::plus)
                }
                TransferDirection.OUTGOING -> {
                    outgoingDestinations.merge(name, 1, Int::plus)
                }
            }
        }

        _insights.update { current ->
            val newTotalTransfers = current.totalTransfers + 1
            val newIncoming = if (direction == TransferDirection.INCOMING) {
                current.autoDetectedIncoming + 1
            } else {
                current.autoDetectedIncoming
            }
            val newOutgoing = if (direction == TransferDirection.OUTGOING) {
                current.autoDetectedOutgoing + 1
            } else {
                current.autoDetectedOutgoing
            }
            val newTotalDetections = current.totalDetections + 1
            val newCorrectDetections = if (wasCorrect) {
                current.correctDetections + 1
            } else {
                current.correctDetections
            }

            val newDetectionRate = if (newTotalTransfers > 0) {
                (newTotalDetections.toFloat() / newTotalTransfers) * 100f
            } else {
                0f
            }
            val newAccuracy = if (newTotalDetections > 0) {
                (newCorrectDetections.toFloat() / newTotalDetections) * 100f
            } else {
                0f
            }

            current.copy(
                totalTransfers = newTotalTransfers,
                autoDetectedIncoming = newIncoming,
                autoDetectedOutgoing = newOutgoing,
                totalDetections = newTotalDetections,
                correctDetections = newCorrectDetections,
                detectionRate = newDetectionRate,
                accuracyPercentage = newAccuracy,
                topIncomingSources = incomingSources.entries
                    .sortedByDescending { it.value }
                    .take(5)
                    .map { it.key },
                topOutgoingDestinations = outgoingDestinations.entries
                    .sortedByDescending { it.value }
                    .take(5)
                    .map { it.key }
            )
        }
    }

    /**
     * Record a transfer with unknown direction (detection failed).
     */
    fun recordUnknownDirection() {
        _insights.update { current ->
            val newTotal = current.totalTransfers + 1
            val newUnknown = current.unknownDirections + 1
            val newDetectionRate = if (newTotal > 0) {
                (current.totalDetections.toFloat() / newTotal) * 100f
            } else {
                0f
            }

            current.copy(
                totalTransfers = newTotal,
                unknownDirections = newUnknown,
                detectionRate = newDetectionRate
            )
        }
    }

    /**
     * Record a user correction (they changed the direction).
     */
    fun recordUserCorrection(fromDirection: TransferDirection?, toDirection: TransferDirection) {
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
        fromDirection: TransferDirection?,
        toDirection: TransferDirection
    ) {
        val autoDetected = autoDetectedDirectionByTransferId[transferId] ?: return
        val shouldCountAsIncorrect = autoDetected != toDirection
        val alreadyApplied = correctionAppliedByTransferId[transferId] == true

        when {
            shouldCountAsIncorrect && !alreadyApplied -> {
                adjustCorrectDetections(delta = -1)
                correctionAppliedByTransferId[transferId] = true
            }
            !shouldCountAsIncorrect && alreadyApplied -> {
                adjustCorrectDetections(delta = 1)
                correctionAppliedByTransferId.remove(transferId)
            }
        }
    }

    private fun adjustCorrectDetections(delta: Int) {
        _insights.update { current ->
            if (current.totalDetections <= 0) {
                return@update current
            }

            val correctedCount = (current.correctDetections + delta)
                .coerceIn(0, current.totalDetections)
            if (correctedCount == current.correctDetections) {
                return@update current
            }

            val newAccuracy = (correctedCount.toFloat() / current.totalDetections.toFloat()) * 100f

            current.copy(
                correctDetections = correctedCount,
                accuracyPercentage = newAccuracy
            )
        }
    }

    /**
     * Reset all analytics data.
     */
    fun reset() {
        _insights.value = TransferInsights()
        incomingSources.clear()
        outgoingDestinations.clear()
        autoDetectedDirectionByTransferId.clear()
        correctionAppliedByTransferId.clear()
    }

    /**
     * Get a summary report for debugging.
     */
    fun getReport(): String {
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
