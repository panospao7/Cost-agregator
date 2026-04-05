package com.yourname.expensetracker.domain.analytics

import app.cash.turbine.test
import com.yourname.expensetracker.assertApproxEquals
import com.yourname.expensetracker.data.database.entity.TransferDirection
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
            direction = TransferDirection.INCOMING,
            accountName = "Employer",
            wasCorrect = true,
            transferId = 1L
        )
        analytics.recordAutoDetection(
            direction = TransferDirection.OUTGOING,
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
            direction = TransferDirection.INCOMING,
            accountName = "Salary",
            wasCorrect = true,
            transferId = 11L
        )
        analytics.recordAutoDetection(
            direction = TransferDirection.OUTGOING,
            accountName = "Card",
            wasCorrect = true,
            transferId = 12L
        )

        assertApproxEquals(100.0f, analytics.insights.value.accuracyPercentage, 0.01f)

        analytics.recordUserCorrection(
            transferId = 11L,
            fromDirection = TransferDirection.INCOMING,
            toDirection = TransferDirection.OUTGOING
        )
        assertEquals(1, analytics.insights.value.correctDetections)
        assertApproxEquals(50.0f, analytics.insights.value.accuracyPercentage, 0.01f)

        analytics.recordUserCorrection(
            transferId = 11L,
            fromDirection = TransferDirection.INCOMING,
            toDirection = TransferDirection.OUTGOING
        )
        assertEquals(1, analytics.insights.value.correctDetections)
        assertApproxEquals(50.0f, analytics.insights.value.accuracyPercentage, 0.01f)

        analytics.recordUserCorrection(
            transferId = 11L,
            fromDirection = TransferDirection.OUTGOING,
            toDirection = TransferDirection.INCOMING
        )
        assertEquals(2, analytics.insights.value.correctDetections)
        assertApproxEquals(100.0f, analytics.insights.value.accuracyPercentage, 0.01f)
    }

    @Test
    fun `reset clears state and get report reflects cleared metrics`() = runTest {
        analytics.recordAutoDetection(
            direction = TransferDirection.INCOMING,
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
                direction = TransferDirection.OUTGOING,
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
}
