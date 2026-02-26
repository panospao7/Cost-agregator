package com.yourname.expensetracker.domain.analytics

import com.yourname.expensetracker.data.database.entity.TransferDirection
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val incomingSources = mutableMapOf<String, Int>()
    private val outgoingDestinations = mutableMapOf<String, Int>()

    /**
     * Record a successful auto-detection.
     */
    fun recordAutoDetection(
        direction: TransferDirection,
        accountName: String?,
        wasCorrect: Boolean = true
    ) {
        val current = _insights.value
        val newTotal = current.totalTransfers + 1

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

        // Track source/destination
        accountName?.let { name ->
            when (direction) {
                TransferDirection.INCOMING -> {
                    incomingSources[name] = incomingSources.getOrDefault(name, 0) + 1
                }
                TransferDirection.OUTGOING -> {
                    outgoingDestinations[name] = outgoingDestinations.getOrDefault(name, 0) + 1
                }
            }
        }

        val detected = newIncoming + newOutgoing
        val newDetectionRate = if (newTotal > 0) {
            (detected.toFloat() / newTotal) * 100
        } else 0f

        val correctDetections = if (wasCorrect) detected else detected - 1
        val newAccuracy = if (detected > 0) {
            (correctDetections.toFloat() / detected) * 100
        } else 0f

        _insights.value = current.copy(
            totalTransfers = newTotal,
            autoDetectedIncoming = newIncoming,
            autoDetectedOutgoing = newOutgoing,
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

    /**
     * Record a transfer with unknown direction (detection failed).
     */
    fun recordUnknownDirection() {
        val current = _insights.value
        val newTotal = current.totalTransfers + 1
        val newUnknown = current.unknownDirections + 1

        val detected = current.autoDetectedIncoming + current.autoDetectedOutgoing
        val newDetectionRate = if (newTotal > 0) {
            (detected.toFloat() / newTotal) * 100
        } else 0f

        _insights.value = current.copy(
            totalTransfers = newTotal,
            unknownDirections = newUnknown,
            detectionRate = newDetectionRate
        )
    }

    /**
     * Record a user correction (they changed the direction).
     */
    fun recordUserCorrection(fromDirection: TransferDirection?, toDirection: TransferDirection) {
        // This indicates the auto-detection was wrong
        // Adjust accuracy calculation
        val current = _insights.value
        val detected = current.autoDetectedIncoming + current.autoDetectedOutgoing

        if (detected > 0 && fromDirection != null) {
            val correctDetections = (detected * current.accuracyPercentage / 100) - 1
            val newAccuracy = (correctDetections / detected) * 100

            _insights.value = current.copy(
                accuracyPercentage = newAccuracy.coerceAtLeast(0f)
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
