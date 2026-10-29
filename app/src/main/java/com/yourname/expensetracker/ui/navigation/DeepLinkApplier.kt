package com.yourname.expensetracker.ui.navigation

import com.yourname.expensetracker.data.repository.ExpenseRepository
import com.yourname.expensetracker.di.ApplicationScope
import com.yourname.expensetracker.domain.ai.usecase.MarkDashboardBriefingOpenedUseCase
import com.yourname.expensetracker.domain.bank.BankFeatureAvailability
import com.yourname.expensetracker.domain.debug.AiRuntimeDiagnostics
import com.yourname.expensetracker.domain.privacy.applock.HeldLinkRelease
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticCode
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticKey
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticMetadata
import com.yourname.expensetracker.domain.privacy.applock.PrivacyDiagnosticsEmitter
import com.yourname.expensetracker.domain.navigation.deeplink.DeepLinkHost
import com.yourname.expensetracker.domain.navigation.deeplink.DeepLinkRequest
import com.yourname.expensetracker.ui.MainViewModel
import com.yourname.expensetracker.ui.screens.transactions.TransactionFilter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import javax.inject.Inject

/**
 * Applies the outcome of [DeepLinkPolicy] (INV-14, INV-16, INV-18, INV-22; spec 5.2 step 6).
 *
 * This is the only code that acts on a deep link. It never parses: it receives a
 * [DeepLinkPolicyResult] whose accepted request is already validated, so no raw URI, host string,
 * query string or parameter value exists here to leak into a log or a diagnostic (INV-18).
 *
 * ## What it may do
 *
 * - Navigate, only through [MainViewModel]'s existing methods. A rejection falls back to Home, except
 *   a non-VIEW action ([DeepLinkRejection.ACTION]), which is ignored with no navigation.
 * - For `home` / `dashboard` with a validated `briefingKey`: ask [MarkDashboardBriefingOpenedUseCase]
 *   to mark it opened. That is the single persistent write reachable from a link (INV-16).
 * - For `activity` with a valid `expenseId`: a read-only [ExpenseRepository.getExpenseById] lookup
 *   (INV-22). Not found, a blocked read or any other failure degrades to the Transactions list with a
 *   bounded code; [CancellationException] propagates.
 * - Never creates, edits, deletes or prefills data (INV-17). `add` opens an empty add sheet.
 *
 * ## Diagnostics
 *
 * Bounded `DL_*` codes only (host / parameter enums, booleans, counts, exception class name). They are
 * launched on the application scope and never awaited, so a slow writer cannot delay navigation
 * (TECH-8).
 */
class DeepLinkApplier @Inject constructor(
    private val expenseRepository: ExpenseRepository,
    private val markDashboardBriefingOpened: MarkDashboardBriefingOpenedUseCase,
    private val diagnostics: PrivacyDiagnosticsEmitter,
    private val bankFeatureAvailability: BankFeatureAvailability,
    private val aiRuntimeDiagnostics: AiRuntimeDiagnostics,
    @ApplicationScope private val diagnosticsScope: CoroutineScope
) {

    /**
     * Applies [result] immediately: the caller (the app-lock router, S21-b) has decided that the app is open and
     * nothing is pending, so `afterUnlock` is false and a dropped parameter is reported now. A link that had to wait
     * goes through [onHeld], [onRejectedWhileClosed], [applyReleased] and [applyExpired] instead.
     * Rethrows [CancellationException]; any other failure is mapped to a bounded code.
     */
    suspend fun apply(result: DeepLinkPolicyResult, mainViewModel: MainViewModel) {
        when (result) {
            DeepLinkPolicyResult.NotADeepLink -> Unit
            is DeepLinkPolicyResult.Rejected -> {
                reportRejection(result)
                // Never a silent no-op for a link that was meant for the app; only a non-VIEW action is ignored.
                if (result.fallsBackToHome) mainViewModel.navigateTo(NavigationDestination.Home)
            }
            is DeepLinkPolicyResult.Accepted ->
                applyAccepted(result, mainViewModel, afterUnlock = false, reportDrops = true)
        }
    }

    /**
     * S21-b (PRV-16, D-S21-4): an accepted link the controller holds. Only the dropped-parameter codes are reported,
     * at receipt, because the policy result (and so the drops) exists only now. Nothing is looked up, written or
     * navigated: the briefing write, the expense lookup and every navigation wait for the release.
     */
    fun onHeld(result: DeepLinkPolicyResult.Accepted) {
        reportDroppedParameters(result.request.host, result.drops)
    }

    /**
     * S21-b (D-S21-3): a rejected link that arrives while the app is closed. Only the bounded `DL_REJECTED_*` code is
     * reported; there is no navigation (a Home jump would drop the app behind the lock surface) and a link already
     * held is left alone.
     */
    fun onRejectedWhileClosed(result: DeepLinkPolicyResult.Rejected) {
        reportRejection(result)
    }

    /**
     * S21-b: applies a link the controller released ([HeldLinkRelease.Apply]). Its dropped parameters were reported
     * when it was held, so they are not reported again. The state-dependent work (the briefing write, the lookup) runs
     * only here, after the release (INV-16, INV-30).
     */
    suspend fun applyReleased(release: HeldLinkRelease.Apply, mainViewModel: MainViewModel) {
        applyAccepted(
            DeepLinkPolicyResult.Accepted(release.request),
            mainViewModel,
            afterUnlock = release.afterUnlock,
            reportDrops = false
        )
    }

    /**
     * S21-b (AE-60): a held link that expired. Opens Home and records nothing: the controller already recorded
     * `DL_HELD_EXPIRED`.
     */
    fun applyExpired(mainViewModel: MainViewModel) {
        mainViewModel.navigateTo(NavigationDestination.Home)
    }

    private fun reportRejection(result: DeepLinkPolicyResult.Rejected) {
        when (result.reason) {
            DeepLinkRejection.ACTION -> report(PrivacyDiagnosticCode.DL_REJECTED_ACTION)
            DeepLinkRejection.MALFORMED -> report(PrivacyDiagnosticCode.DL_REJECTED_MALFORMED)
            DeepLinkRejection.UNKNOWN_HOST -> report(PrivacyDiagnosticCode.DL_REJECTED_UNKNOWN_HOST)
            DeepLinkRejection.BANK_HOST -> report(PrivacyDiagnosticCode.DL_REJECTED_BANK_HOST, PrivacyDiagnosticMetadata.builder().apply {
                put(PrivacyDiagnosticKey.BANK_SYNC_AVAILABLE, bankFeatureAvailability.isBankSyncAvailable)
            }.build())
        }
    }

    private suspend fun applyAccepted(
        result: DeepLinkPolicyResult.Accepted,
        mainViewModel: MainViewModel,
        afterUnlock: Boolean,
        reportDrops: Boolean
    ) {
        val request = result.request
        if (reportDrops) reportDroppedParameters(request.host, result.drops)

        // Loan S13-a (C1): `requiresConfirmation` is the single source of truth for confirm-vs-navigate.
        if (request.host.requiresConfirmation) {
            mainViewModel.requestDeepLinkConfirmation(request.toNavigationDestination())
        } else when (request.host) {
            DeepLinkHost.HOME, DeepLinkHost.DASHBOARD -> {
                mainViewModel.navigateTo(request.toNavigationDestination())
                request.briefingKey?.let { markBriefingOpened(it) }
                // Constant message only: never the key, host or any query value (INV-18).
                aiRuntimeDiagnostics.recordInteraction(
                    type = "phase4_open",
                    message = "dashboard deep link opened"
                )
            }
            DeepLinkHost.ACTIVITY -> applyActivity(request, mainViewModel)
            DeepLinkHost.ADD -> mainViewModel.triggerAddExpense()
            DeepLinkHost.REVIEW,
            DeepLinkHost.PLAN,
            DeepLinkHost.ANALYTICS,
            DeepLinkHost.MAP -> mainViewModel.navigateTo(request.toNavigationDestination())
            // Unreachable by design (handled by the requiresConfirmation branch above); kept exhaustive and
            // behaviour-identical. The destination waits for the user's confirmation, which MainScreen shows
            // inside the app-lock shell; a held link reaches this point only after the release.
            DeepLinkHost.INSTALLMENTS,
            DeepLinkHost.INSTALLMENT_PLAN ->
                mainViewModel.requestDeepLinkConfirmation(request.toNavigationDestination())
        }

        // DL_APPLIED for confirm hosts records acceptance of the request, not the user's confirmation (guardian S13-a F2, accepted).
        report(PrivacyDiagnosticCode.DL_APPLIED, PrivacyDiagnosticMetadata.builder().apply {
            put(PrivacyDiagnosticKey.HOST, request.host)
            put(PrivacyDiagnosticKey.AFTER_UNLOCK, afterUnlock)
        }.build())
    }

    private fun reportDroppedParameters(host: DeepLinkHost, drops: DeepLinkDrops) {
        if (drops.unknownCount > 0) {
            report(PrivacyDiagnosticCode.DL_PARAM_UNKNOWN_DROPPED, PrivacyDiagnosticMetadata.builder().apply {
                put(PrivacyDiagnosticKey.HOST, host)
                put(PrivacyDiagnosticKey.COUNT, drops.unknownCount)
            }.build())
        }
        for (parameter in drops.invalid) {
            report(PrivacyDiagnosticCode.DL_PARAM_INVALID_DROPPED, PrivacyDiagnosticMetadata.builder().apply {
                put(PrivacyDiagnosticKey.HOST, host)
                put(PrivacyDiagnosticKey.PARAMETER, parameter)
            }.build())
        }
    }

    private suspend fun markBriefingOpened(briefingKey: String) {
        when (markDashboardBriefingOpened(briefingKey)) {
            MarkDashboardBriefingOpenedUseCase.Outcome.WRITTEN -> Unit
            MarkDashboardBriefingOpenedUseCase.Outcome.NOT_DELIVERED ->
                report(PrivacyDiagnosticCode.DL_BRIEFING_KEY_NOT_DELIVERED)
            MarkDashboardBriefingOpenedUseCase.Outcome.STATE_UNREADABLE ->
                report(PrivacyDiagnosticCode.DL_BRIEFING_STATE_UNREADABLE)
        }
    }

    private suspend fun applyActivity(request: DeepLinkRequest, mainViewModel: MainViewModel) {
        val expenseId = request.expenseId
        if (expenseId == null) {
            // No valid id (already reported as dropped by the policy): no lookup (AE-23).
            mainViewModel.navigateTo(request.toNavigationDestination())
            return
        }

        val expense = try {
            expenseRepository.getExpenseById(expenseId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            report(PrivacyDiagnosticCode.DL_TARGET_READ_BLOCKED, PrivacyDiagnosticMetadata.builder().apply { exceptionClass(e) }.build())
            mainViewModel.navigateTo(request.toNavigationDestination())
            return
        }

        if (expense == null) {
            report(PrivacyDiagnosticCode.DL_TARGET_NOT_FOUND)
            mainViewModel.navigateTo(request.toNavigationDestination())
            return
        }

        // G-TIME-01: pure derivation from expense.date (java.time, system default timezone;
        // same day boundaries the Calendar produced).
        val zone = ZoneId.systemDefault()
        val expenseDate = Instant.ofEpochMilli(expense.date).atZone(zone).toLocalDate()
        val startOfDay = expenseDate.atStartOfDay(zone).toInstant().toEpochMilli()
        val endOfNextDayStart = expenseDate.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
        mainViewModel.navigateToTransactions(
            TransactionFilter(dateRange = startOfDay to endOfNextDayStart)
        )
    }

    /** Fire and forget: navigation never waits for a diagnostic (TECH-8). */
    private fun report(
        code: PrivacyDiagnosticCode,
        metadata: PrivacyDiagnosticMetadata = PrivacyDiagnosticMetadata.EMPTY
    ) {
        diagnosticsScope.launch { diagnostics.emit(code, metadata) }
    }
}
