package com.yourname.expensetracker.ui.navigation

import android.content.Intent
import com.yourname.expensetracker.data.backup.MaintenanceSafeDiagnosticSink
import com.yourname.expensetracker.data.backup.RestoreMaintenanceMode
import com.yourname.expensetracker.data.database.entity.Expense
import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.domain.ai.model.AiEngagementState
import com.yourname.expensetracker.domain.ai.service.AiEngagementRepository
import com.yourname.expensetracker.domain.ai.usecase.MarkDashboardBriefingOpenedUseCase
import com.yourname.expensetracker.domain.bank.BankFeatureAvailability
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent
import com.yourname.expensetracker.domain.diagnostics.DiagnosticEventWriter
import com.yourname.expensetracker.domain.diagnostics.NotificationDiagnosticEmitter
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticsEmitter
import com.yourname.expensetracker.domain.util.FakeMonotonicTimeProvider
import com.yourname.expensetracker.domain.util.FakeTimeProvider
import com.yourname.expensetracker.ui.MainViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import timber.log.Timber
import java.io.IOException

/**
 * S10 / AE-21 (INV-18): after the briefing, activity, analytics, map, unknown-host, bank-host and
 * malformed scenarios, none of the three sinks (durable diagnostic rows, `AiRuntimeDiagnostics`, a
 * captured Timber tree) contains the raw URI, host, query key, query value, briefing key or an
 * exception message. The chain is real from [DeepLinkApplier] to the diagnostic writer, so this
 * pins the C4 resolution (the raw `briefingKey` and the raw host used to reach a sink).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class DeepLinkDiagnosticsSentinelTest {

    private class FakeEngagementRepository : AiEngagementRepository {
        var delivered: String? = null
        var readFailure: Throwable? = null
        var opened: String? = null

        override fun engagementState(): Flow<AiEngagementState> = emptyFlow()

        override suspend fun getLastDeliveredDashboardBriefingKey(): String? {
            readFailure?.let { throw it }
            return delivered
        }

        override suspend fun setLastDeliveredDashboardBriefingKey(targetKey: String) {
            delivered = targetKey
        }

        override suspend fun getLastOpenedDashboardBriefingKey(): String? = opened

        override suspend fun setLastOpenedDashboardBriefingKey(targetKey: String) {
            opened = targetKey
        }
    }

    private val monotonic = FakeMonotonicTimeProvider(1_000L)
    private val durableRows = mutableListOf<DiagnosticEvent>()
    private val timberLines = mutableListOf<String>()
    private val timberTree = object : Timber.Tree() {
        override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
            timberLines += "$tag $message ${t?.let { it.javaClass.name + " " + it.message + " " + it.stackTraceToString() } ?: ""}"
        }
    }

    private val engagement = FakeEngagementRepository()
    private val wallClock = FakeTimeProvider(1_716_163_200_000L)
    private val aiRuntimeDiagnostics = AiRuntimeDiagnostics(wallClock)
    private val mainViewModel = mockk<MainViewModel>(relaxed = true)
    private val expenseRepository = mockk<ExpenseRepository>()

    @Before
    fun plant() {
        Timber.plant(timberTree)
    }

    @After
    fun uproot() {
        Timber.uproot(timberTree)
    }

    private fun kotlinx.coroutines.test.TestScope.applier(): DeepLinkApplier {
        val maintenance = mockk<RestoreMaintenanceMode>().also {
            every { it.currentMode() } returns RestoreMaintenanceMode.Mode.NORMAL
        }
        val writer = mockk<DiagnosticEventWriter>().also {
            coEvery { it.emit(any()) } answers { durableRows += firstArg<DiagnosticEvent>() }
        }
        val delegate = NotificationDiagnosticEmitter(
            writer,
            mockk<MaintenanceSafeDiagnosticSink>(relaxed = true),
            maintenance,
            Dispatchers.Unconfined,
            wallClock
        )
        return DeepLinkApplier(
            expenseRepository,
            MarkDashboardBriefingOpenedUseCase(engagement),
            PrivacyDiagnosticsEmitter(delegate, monotonic),
            BankFeatureAvailability.forDebugMode(false),
            aiRuntimeDiagnostics,
            CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        )
    }

    private fun everythingRecorded(): String = buildString {
        durableRows.forEach { event ->
            append(event.toString()).append(' ')
            append(event.reasonCode).append(' ').append(event.stage).append(' ')
            append(event.metadata.toJson()).append(' ')
            append(event.exception).append('\n')
        }
        aiRuntimeDiagnostics.getRecentEvents(1_000).forEach { event ->
            append(event.type).append(' ').append(event.message).append('\n')
        }
        timberLines.forEach { append(it).append('\n') }
    }

    private val sentinels = listOf(
        "dashboard_home",
        "2027-03-12",
        "2027-13-40",
        "briefingKey",
        "expensetracker://",
        "expensetracker:",
        "evilhost",
        "SecretCafe",
        "secretvalue",
        "secretkey",
        "zzsecret",
        "';DROP",
        "kaboom",
        "badbank"
    )

    @Test
    fun `AE-21 no raw link input or exception text reaches any diagnostic or log sink`() = runTest {
        val applier = applier()
        engagement.delivered = "dashboard_home:2027-03-12"
        val expense = mockk<Expense> { every { date } returns 1_700_000_000_000L }
        coEvery { expenseRepository.getExpenseById(77L) } returns expense
        coEvery { expenseRepository.getExpenseById(78L) } returns null
        coEvery { expenseRepository.getExpenseById(79L) } throws IllegalStateException("kaboom")

        // Advance past the 60 s per-code window before each link so every scenario's events
        // reach the durable writer (otherwise the limiter would only count repeats).
        suspend fun open(uri: String, action: String? = Intent.ACTION_VIEW) {
            monotonic.advanceNanos(61_000_000_000L)
            applier.apply(DeepLinkPolicy.evaluate(action, uri), mainViewModel)
        }

        // AE-18: delivered key.
        open("expensetracker://dashboard?briefingKey=dashboard_home:2027-03-12")
        // AE-17: key not delivered.
        open("expensetracker://dashboard?briefingKey=dashboard_home:2027-03-11")
        // AE-19: malformed keys.
        open("expensetracker://dashboard?briefingKey=dashboard_home:2027-13-40")
        open("expensetracker://home?briefingKey=x%27;DROP")
        // AE-20: unreadable engagement store, message must not leak.
        engagement.readFailure = IOException("kaboom")
        open("expensetracker://dashboard?briefingKey=dashboard_home:2027-03-12")
        engagement.readFailure = null
        // Activity: found, not found, blocked read.
        open("expensetracker://activity?expenseId=77")
        open("expensetracker://activity?expenseId=78")
        open("expensetracker://activity?expenseId=79")
        // Unknown and dropped parameters, invalid values.
        open("expensetracker://map?location=SecretCafe&secretkey=secretvalue")
        open("expensetracker://analytics?period=zzsecret")
        open("expensetracker://add?secretkey=secretvalue")
        // Rejections: unknown host, bank host, malformed, non-VIEW.
        open("expensetracker://evilhost?secretkey=secretvalue")
        open("expensetracker://badbank")
        open("expensetracker://bank?secretkey=secretvalue")
        open("expensetracker://home/x?secretkey=secretvalue")
        open("expensetracker://add?secretkey=secretvalue", action = Intent.ACTION_MAIN)

        val recorded = everythingRecorded()
        assertTrue("Expected diagnostics to have been recorded", durableRows.isNotEmpty())
        sentinels.forEach { sentinel ->
            assertFalse("Sink contains '$sentinel':\n$recorded", recorded.contains(sentinel))
        }
        // The safe interaction record is still written, with a constant message.
        assertTrue(aiRuntimeDiagnostics.getRecentEvents(1_000).any { it.message == "dashboard deep link opened" })
    }
}
