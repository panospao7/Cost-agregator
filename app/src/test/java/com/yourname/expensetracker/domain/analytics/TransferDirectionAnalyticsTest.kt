package com.yourname.expensetracker.domain.analytics

import app.cash.turbine.test
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.domain.model.DomainTransferDirection
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferDirectionAnalyticsTest {

    private val analytics = TransferDirectionAnalytics()

    @Test
    fun `record auto detection updates counters rates and top endpoints`() = runTest {
        analytics.recordAutoDetection(
            direction = DomainTransferDirection.INCOMING,
            accountName = "Employer",
            wasCorrect = true,
            transferId = 1L
        )
        analytics.recordAutoDetection(
            direction = DomainTransferDirection.OUTGOING,
            accountName = "Landlord",
            wasCorrect = false,
            transferId = 2L
        )
        analytics.recordUnknownDirection()

        val insights = analytics.insights.value

        assertEquals(3, insights.totalTransfers)
        assertEquals(1, insights.autoDetectedIncoming)
        assertEquals(1, insights.autoDetectedOutgoing)
        assertEquals(1, insights.unknownDirections)
        assertEquals(2, insights.totalDetections)
        assertEquals(1, insights.correctDetections)
        assertApproxEquals(66.67f, insights.detectionRate, 0.01f)
        assertApproxEquals(50.0f, insights.accuracyPercentage, 0.01f)
        assertEquals(listOf("Employer"), insights.topIncomingSources)
        assertEquals(listOf("Landlord"), insights.topOutgoingDestinations)
    }

    @Test
    fun `record user correction adjusts accuracy idempotently and supports reverting`() = runTest {
        analytics.recordAutoDetection(
            direction = DomainTransferDirection.INCOMING,
            accountName = "Salary",
            wasCorrect = true,
            transferId = 11L
        )
        analytics.recordAutoDetection(
            direction = DomainTransferDirection.OUTGOING,
            accountName = "Card",
            wasCorrect = true,
            transferId = 12L
        )

        assertApproxEquals(100.0f, analytics.insights.value.accuracyPercentage, 0.01f)
        assertEquals(1, analytics.insights.value.autoDetectedIncoming)
        assertEquals(1, analytics.insights.value.autoDetectedOutgoing)
        assertEquals(listOf("Salary"), analytics.insights.value.topIncomingSources)
        assertEquals(listOf("Card"), analytics.insights.value.topOutgoingDestinations)

        analytics.recordUserCorrection(
            transferId = 11L,
            fromDirection = DomainTransferDirection.INCOMING,
            toDirection = DomainTransferDirection.OUTGOING
        )
        assertEquals(1, analytics.insights.value.correctDetections)
        assertApproxEquals(50.0f, analytics.insights.value.accuracyPercentage, 0.01f)
        assertEquals(0, analytics.insights.value.autoDetectedIncoming)
        assertEquals(2, analytics.insights.value.autoDetectedOutgoing)
        assertTrue(analytics.insights.value.topIncomingSources.isEmpty())
        assertEquals(2, analytics.insights.value.topOutgoingDestinations.size)
        assertTrue(analytics.insights.value.topOutgoingDestinations.contains("Card"))
        assertTrue(analytics.insights.value.topOutgoingDestinations.contains("Salary"))

        analytics.recordUserCorrection(
            transferId = 11L,
            fromDirection = DomainTransferDirection.INCOMING,
            toDirection = DomainTransferDirection.OUTGOING
        )
        assertEquals(1, analytics.insights.value.correctDetections)
        assertApproxEquals(50.0f, analytics.insights.value.accuracyPercentage, 0.01f)

        analytics.recordUserCorrection(
            transferId = 11L,
            fromDirection = DomainTransferDirection.OUTGOING,
            toDirection = DomainTransferDirection.INCOMING
        )
        assertEquals(2, analytics.insights.value.correctDetections)
        assertApproxEquals(100.0f, analytics.insights.value.accuracyPercentage, 0.01f)
        assertEquals(1, analytics.insights.value.autoDetectedIncoming)
        assertEquals(1, analytics.insights.value.autoDetectedOutgoing)
        assertEquals(listOf("Salary"), analytics.insights.value.topIncomingSources)
        assertEquals(listOf("Card"), analytics.insights.value.topOutgoingDestinations)
    }

    @Test
    fun `reset clears state and get report reflects cleared metrics`() = runTest {
        analytics.recordAutoDetection(
            direction = DomainTransferDirection.INCOMING,
            accountName = "Salary",
            wasCorrect = true,
            transferId = 1L
        )

        analytics.reset()

        val insights = analytics.insights.value
        val report = analytics.getReport()

        assertEquals(0, insights.totalTransfers)
        assertEquals(0, insights.totalDetections)
        assertEquals(0, insights.correctDetections)
        assertTrue(insights.topIncomingSources.isEmpty())
        assertTrue(insights.topOutgoingDestinations.isEmpty())
        assertTrue(report.contains("Total Transfers: 0"))
        assertTrue(report.contains("Detection Rate: 0.0%"))
        assertTrue(report.contains("Accuracy: 0.0%"))
    }

    @Test
    fun `insights stateflow emits initial and updated values`() = runTest {
        analytics.insights.test {
            val initial = awaitItem()
            assertEquals(0, initial.totalTransfers)
            assertApproxEquals(0.0f, initial.detectionRate, 0.01f)

            analytics.recordAutoDetection(
                direction = DomainTransferDirection.OUTGOING,
                accountName = "Bank A",
                wasCorrect = true,
                transferId = 99L
            )

            val updated = awaitItem()
            assertEquals(1, updated.totalTransfers)
            assertEquals(1, updated.autoDetectedOutgoing)
            assertEquals(1, updated.correctDetections)
            assertApproxEquals(100.0f, updated.detectionRate, 0.01f)
            assertApproxEquals(100.0f, updated.accuracyPercentage, 0.01f)
            assertFalse(updated.hasEnoughData)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `prune folds excess tracked transfers and corrections still work after cap`() = runTest {
        val maxTrackedTransfers = getPrivateIntConstant("MAX_TRACKED_TRANSFERS")
        val pruneBatchSize = getPrivateIntConstant("TRANSFER_PRUNE_BATCH_SIZE")
        val totalRecords = maxTrackedTransfers + pruneBatchSize + 25

        for (id in 1L..totalRecords.toLong()) {
            analytics.recordAutoDetection(
                direction = DomainTransferDirection.INCOMING,
                accountName = if (id % 3L == 0L) "Payroll" else "Wallet",
                wasCorrect = true,
                transferId = id
            )
        }

        val trackedIdsAfterPrune = getTrackedTransferIds()
        val frozenTransfersAfterPrune = getPrivateIntField("frozenTotalTransfers")
        val baselineInsights = analytics.insights.value

        assertTrue(trackedIdsAfterPrune.size <= maxTrackedTransfers)
        assertTrue(frozenTransfersAfterPrune > 0)
        assertEquals(totalRecords, baselineInsights.totalTransfers)
        assertEquals(totalRecords, baselineInsights.totalDetections)
        assertEquals(totalRecords, baselineInsights.correctDetections)
        assertEquals(totalRecords, baselineInsights.autoDetectedIncoming)
        assertEquals(0, baselineInsights.autoDetectedOutgoing)
        assertTrue(baselineInsights.topIncomingSources.contains("Wallet"))

        val firstTrackedId = trackedIdsAfterPrune.first()
        val secondTrackedId = trackedIdsAfterPrune.drop(1).first()

        analytics.recordUserCorrection(
            transferId = firstTrackedId,
            fromDirection = DomainTransferDirection.INCOMING,
            toDirection = DomainTransferDirection.OUTGOING
        )
        analytics.recordUserCorrection(
            transferId = secondTrackedId,
            fromDirection = DomainTransferDirection.INCOMING,
            toDirection = DomainTransferDirection.OUTGOING
        )

        val afterTwoCorrections = analytics.insights.value
        assertEquals(totalRecords, afterTwoCorrections.totalTransfers)
        assertEquals(totalRecords, afterTwoCorrections.totalDetections)
        assertEquals(totalRecords - 2, afterTwoCorrections.correctDetections)
        assertEquals(totalRecords - 2, afterTwoCorrections.autoDetectedIncoming)
        assertEquals(2, afterTwoCorrections.autoDetectedOutgoing)
        assertTrue(afterTwoCorrections.topOutgoingDestinations.isNotEmpty())

        analytics.recordUserCorrection(
            transferId = firstTrackedId,
            fromDirection = DomainTransferDirection.OUTGOING,
            toDirection = DomainTransferDirection.INCOMING
        )

        val afterRevert = analytics.insights.value
        assertEquals(totalRecords - 1, afterRevert.correctDetections)
        assertEquals(totalRecords - 1, afterRevert.autoDetectedIncoming)
        assertEquals(1, afterRevert.autoDetectedOutgoing)
        assertTrue(afterRevert.topIncomingSources.isNotEmpty())
    }

    private fun getPrivateIntConstant(name: String): Int {
        val field = TransferDirectionAnalytics::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.getInt(null)
    }

    @Suppress("UNCHECKED_CAST")
    private fun getTrackedTransferIds(): List<Long> {
        val field = TransferDirectionAnalytics::class.java.getDeclaredField("trackedDetectionsByTransferId")
        field.isAccessible = true
        val map = field.get(analytics) as java.util.concurrent.ConcurrentHashMap<Long, *>
        return map.keys.toList()
    }

    private fun getPrivateIntField(name: String): Int {
        val field = TransferDirectionAnalytics::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.getInt(analytics)
    }
}
