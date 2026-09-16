package com.yourname.expensetracker.architecture

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.LocalDate

/**
 * U-PR1 — Architecture guard: CancellationException safety.
 *
 * Contract CANCEL-01: Every `catch` block in a `suspend` function that catches
 * `Exception` (or any supertype of CancellationException) MUST contain a
 * reference to `CancellationException` in its body — either rethrowing it
 * directly or delegating to a helper that does.
 *
 * This test scans all production `.kt` source files and fails if any suspend
 * function contains a broad catch without the CE guard.
 *
 * Pre-existing violations outside U-PR1 scope are tracked in [KNOWN_VIOLATIONS]
 * and will be addressed in follow-up PRs. The allowlist MUST NOT grow — only shrink.
 */
class CancellationSafetyArchitectureGuardTest {

    /**
     * Structured allowlist entry requiring owner, reason, issue tracking,
     * and expiry date — ensuring no allowlisted file is left unaccounted for.
     */
    data class ArchitectureAllowlistEntry(
        val fileName: String,
        val rule: String,
        val category: String = "UNCATEGORIZED",
        val owner: String,
        val reason: String,
        val issue: String,
        val expires: LocalDate
    )

    /**
     * Structured allowlist for raw runCatching in suspend paths (PR23, MIT-034).
     * Each entry requires owner, reason, issue tracking, and expiry.
     * This list must only shrink — never grow.
     */
    private data class RawRunCatchingAllowlistEntry(
        val fileName: String,
        val category: String,
        val owner: String,
        val reason: String,
        val issue: String,
        val expires: LocalDate
    )

    private val sourceRoot: File by lazy { resolveSourceRoot() }

    private fun resolveSourceRoot(): File {
        val candidates = listOf(
            File("src/main/java"),
            File("app/src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "src/main/java"),
            File(System.getProperty("user.dir") ?: ".", "app/src/main/java")
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: error("Could not locate production source root. user.dir=${System.getProperty("user.dir")}")
    }

    private companion object {
        /**
         * Files with pre-existing violations outside U-PR1 scope.
         * These MUST be fixed in follow-up PRs — this list must only shrink, never grow.
         *
         * As of 2026-07-02: Converted to structured allowlist (PR12a).
         * Each entry requires owner, reason, issue tracking, and expiry date.
         * This list must only shrink — never grow.
         */
        val KNOWN_VIOLATIONS = listOf(
            // ── AI service providers ──────────────────────────────────────
            ArchitectureAllowlistEntry("CloudCategorizationAssistService.kt", "CATCH_WITHOUT_CE_RETHROW", category = "AI", owner = "AI", "AI provider with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CloudDashboardBriefingService.kt", "CATCH_WITHOUT_CE_RETHROW", category = "AI", owner = "AI", "AI provider with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CloudDedupeJudgeService.kt", "CATCH_WITHOUT_CE_RETHROW", category = "AI", owner = "AI", "AI provider with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CloudReceiptItemCategorizationService.kt", "CATCH_WITHOUT_CE_RETHROW", category = "AI", owner = "AI", "AI provider with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CloudReviewExplanationService.kt", "CATCH_WITHOUT_CE_RETHROW", category = "AI", owner = "AI", "AI provider with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("DefaultAiEnvironmentMonitor.kt", "CATCH_WITHOUT_CE_RETHROW", category = "AI", owner = "AI", "AI provider with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("HybridDedupeJudgeService.kt", "CATCH_WITHOUT_CE_RETHROW", category = "AI", owner = "AI", "AI provider with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("OnDeviceDashboardBriefingService.kt", "CATCH_WITHOUT_CE_RETHROW", category = "AI", owner = "AI", "AI provider with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("OnDeviceDedupeJudgeService.kt", "CATCH_WITHOUT_CE_RETHROW", category = "AI", owner = "AI", "AI provider with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("OnDeviceNotificationParser.kt", "CATCH_WITHOUT_CE_RETHROW", category = "AI", owner = "AI", "AI provider with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("OnDeviceQueryInterpretationService.kt", "CATCH_WITHOUT_CE_RETHROW", category = "AI", owner = "AI", "AI provider with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("OnDeviceReceiptItemCategorizationService.kt", "CATCH_WITHOUT_CE_RETHROW", category = "AI", owner = "AI", "AI provider with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("OnDeviceReviewExplanationService.kt", "CATCH_WITHOUT_CE_RETHROW", category = "AI", owner = "AI", "AI provider with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            // ── Backup / data infrastructure ──────────────────────────────
            ArchitectureAllowlistEntry("DataStoreMaintenanceSafeDiagnosticSink.kt", "CATCH_WITHOUT_CE_RETHROW", category = "BACKUP_DATA", owner = "BackupData", "Backup infrastructure with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("AndroidForegroundLocationProvider.kt", "CATCH_WITHOUT_CE_RETHROW", category = "BACKUP_DATA", owner = "BackupData", "Location provider with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("NominatimGeocodingService.kt", "CATCH_WITHOUT_CE_RETHROW", category = "BACKUP_DATA", owner = "BackupData", "Geocoding service with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            // ── Repositories ──────────────────────────────────────────────
            ArchitectureAllowlistEntry("CategoryRepository.kt", "CATCH_WITHOUT_CE_RETHROW", category = "REPOSITORY", owner = "Repository", "Repository with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CurrencySettingsRepositoryImpl.kt", "CATCH_WITHOUT_CE_RETHROW", category = "REPOSITORY", owner = "Repository", "Repository with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("GroupsRepositoryImpl.kt", "CATCH_WITHOUT_CE_RETHROW", category = "REPOSITORY", owner = "Repository", "Repository with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ManualRecurringExpenseRepository.kt", "CATCH_WITHOUT_CE_RETHROW", category = "REPOSITORY", owner = "Repository", "Repository with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ManualExpenseRepository.kt", "CATCH_WITHOUT_CE_RETHROW", category = "REPOSITORY", owner = "Repository", "Repository with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("RecurringExpenseRepository.kt", "CATCH_WITHOUT_CE_RETHROW", category = "REPOSITORY", owner = "Repository", "Repository with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            // ── Domain / use-cases ────────────────────────────────────────
            ArchitectureAllowlistEntry("CategorizationAssistInputBuilder.kt", "CATCH_WITHOUT_CE_RETHROW", category = "DOMAIN", owner = "Domain", "Domain use-case with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("DedupeJudgeInputBuilder.kt", "CATCH_WITHOUT_CE_RETHROW", category = "DOMAIN", owner = "Domain", "Domain use-case with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("SuggestCategoryFallbackUseCase.kt", "CATCH_WITHOUT_CE_RETHROW", category = "DOMAIN", owner = "Domain", "Domain use-case with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("AnomalyAlertOrchestrator.kt", "CATCH_WITHOUT_CE_RETHROW", category = "DOMAIN", owner = "Domain", "Domain orchestrator with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CurrencySettingsRepository.kt", "CATCH_WITHOUT_CE_RETHROW", category = "DOMAIN", owner = "Domain", "Domain interface with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ReceiptDebugExporter.kt", "CATCH_WITHOUT_CE_RETHROW", category = "DOMAIN", owner = "Domain", "Domain exporter with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("NotificationDiagnosticEmitter.kt", "CATCH_WITHOUT_CE_RETHROW", category = "DOMAIN", owner = "Domain", "Domain diagnostics with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("OperationRunRecorder.kt", "CATCH_WITHOUT_CE_RETHROW", category = "DOMAIN", owner = "Domain", "Domain recorder with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("FinancialHealthScoreV2.kt", "CATCH_WITHOUT_CE_RETHROW", category = "DOMAIN", owner = "Domain", "Domain calculator with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CrossSourceDeduplication.kt", "CATCH_WITHOUT_CE_RETHROW", category = "DOMAIN", owner = "Domain", "Domain dedup with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ExpenseCategoryClassifier.kt", "CATCH_WITHOUT_CE_RETHROW", category = "DOMAIN", owner = "Domain", "Domain classifier with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("TransactionClassifier.kt", "CATCH_WITHOUT_CE_RETHROW", category = "DOMAIN", owner = "Domain", "Domain classifier with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("NotificationIntakePayloadRepairer.kt", "CATCH_WITHOUT_CE_RETHROW", category = "DOMAIN", owner = "Domain", "Domain repairer with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CompositePrivacyGate.kt", "CATCH_WITHOUT_CE_RETHROW", category = "DOMAIN", owner = "Domain", "Domain privacy gate with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("NotificationSubscriptionDetector.kt", "CATCH_WITHOUT_CE_RETHROW", category = "DOMAIN", owner = "Domain", "Domain detector with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("DefaultExpenseCategoryAssignmentService.kt", "CATCH_WITHOUT_CE_RETHROW", category = "DOMAIN", owner = "Domain", "Domain assignment service with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("DebugExpenseAuditWriter.kt", "CATCH_WITHOUT_CE_RETHROW", category = "DOMAIN", owner = "Domain", "Domain audit writer with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ComputeDashboardWidgetsUseCase.kt", "CATCH_WITHOUT_CE_RETHROW", category = "DOMAIN", owner = "Domain", "Domain use-case with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ComputeMoneyRadarUseCase.kt", "CATCH_WITHOUT_CE_RETHROW", category = "DOMAIN", owner = "Domain", "Domain use-case with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("MonthlySavingsSweepUseCase.kt", "CATCH_WITHOUT_CE_RETHROW", category = "DOMAIN", owner = "Domain", "Domain use-case with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("AutoCreateWarrantyFromReceiptUseCase.kt", "CATCH_WITHOUT_CE_RETHROW", category = "DOMAIN", owner = "Domain", "Domain use-case with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            // ── Infrastructure (false positives from regex — catches preceded by CE catch) ──
            ArchitectureAllowlistEntry("CompositeSideEffectEventWriter.kt", "FALSE_POSITIVE_CE_RETHROW", category = "INFRASTRUCTURE", owner = "Infrastructure", "False positive — broad catch preceded by CE catch", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CompositeDiagnosticEventWriter.kt", "FALSE_POSITIVE_CE_RETHROW", category = "INFRASTRUCTURE", owner = "Infrastructure", "False positive — broad catch preceded by CE catch", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CompositeOperationRunRecorder.kt", "FALSE_POSITIVE_CE_RETHROW", category = "INFRASTRUCTURE", owner = "Infrastructure", "False positive — broad catch preceded by CE catch", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("PostCommitActionRunnerImpl.kt", "FALSE_POSITIVE_CE_RETHROW", category = "INFRASTRUCTURE", owner = "Infrastructure", "False positive — broad catch preceded by CE catch", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("BankStatementLifecycleProcessor.kt", "FALSE_POSITIVE_CE_RETHROW", category = "INFRASTRUCTURE", owner = "Infrastructure", "False positive — PR20-1 inner cleanup catches inside CancellationException handler (lines 860, 908, 928). Outer CE catch at line 850.", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("GroupTransactionCoordinator.kt", "FALSE_POSITIVE_CE_RETHROW", category = "INFRASTRUCTURE", owner = "Infrastructure", "False positive — broad catch preceded by CE catch", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("MerchantKeyBackfillWorker.kt", "FALSE_POSITIVE_CE_RETHROW", category = "INFRASTRUCTURE", owner = "Infrastructure", "False positive — broad catch preceded by CE catch", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ReceiptRepository.kt", "FALSE_POSITIVE_CE_RETHROW", category = "INFRASTRUCTURE", owner = "Infrastructure", "False positive — broad catch preceded by CE catch", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ReviewQueueRepository.kt", "FALSE_POSITIVE_CE_RETHROW", category = "INFRASTRUCTURE", owner = "Infrastructure", "False positive — broad catch preceded by CE catch", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ReceiptOcrService.kt", "FALSE_POSITIVE_CE_RETHROW", category = "INFRASTRUCTURE", owner = "Infrastructure", "False positive — broad catch preceded by CE catch", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ReceiptMatchingWorker.kt", "FALSE_POSITIVE_CE_RETHROW", category = "INFRASTRUCTURE", owner = "Infrastructure", "False positive — broad catch preceded by CE catch", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("WarrantyExpirationWorker.kt", "FALSE_POSITIVE_CE_RETHROW", category = "INFRASTRUCTURE", owner = "Infrastructure", "False positive — broad catch preceded by CE catch", "MIT-034", LocalDate.of(2026, 12, 31)),
            // U-001 (RP-01) fixed the coordinator's conversion/event-write sites;
            // the file-level exclusion is removed so the (now executable-evidence)
            // guard enforces it directly. Re-add ONLY with owner/reason/issue/expiry
            // if a regression appears.
            // ── Services ──────────────────────────────────────────────────
            ArchitectureAllowlistEntry("NotificationCaptureService.kt", "CATCH_WITHOUT_CE_RETHROW", category = "SERVICE", owner = "Service", "Service with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CloudQueryInterpretationService.kt", "CATCH_WITHOUT_CE_RETHROW", category = "SERVICE", owner = "Service", "Cloud service with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("SmartBillNegotiationEngine.kt", "CATCH_WITHOUT_CE_RETHROW", category = "SERVICE", owner = "Service", "Service with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            // ── Workers ───────────────────────────────────────────────────
            // ── Infrastructure / sinks ────────────────────────────────────
            ArchitectureAllowlistEntry("FileWorkerTerminalDiagnosticSink.kt", "CATCH_WITHOUT_CE_RETHROW", category = "INFRASTRUCTURE", owner = "Infrastructure", "Diagnostic sink with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            // ── UI layer (viewModelScope.launch catches — lower priority) ──
            ArchitectureAllowlistEntry("AnalyticsViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("BudgetViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("BudgetForecastingViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("DebugViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("SourceLinkDebugViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("HomeViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("SpendingMapViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("NaturalLanguageSearchViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ReceiptScanViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("TransactionsViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("AddExpenseViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("AiSettingsViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("AssistantViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("BackupRestoreViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CarbonFootprintViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CashFlowCalendarViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("SpendingChallengesViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CurrencyManagementViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("SharedExpenseGroupsViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("InvestmentViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("LifestyleInflationViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("BillNegotiationViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("PriceProtectionViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("PrivacySettingsViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ReceiptMatchingViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("RecurringExpensesScreen.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "Screen composable with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ManualRecurringExpenseViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("BillRemindersViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ReviewViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("SubscriptionManagementViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("TaxConfigurationViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("WarrantyTrackerViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ExportOptionsScreen.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "Screen composable with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ExportOptionsViewModel.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("LocationSearchPicker.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "UI component with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("LoadableUiState.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "UI state utility with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("MutationState.kt", "LAUNCH_CE_NO_RETHROW", category = "UI", owner = "UI", "UI state utility with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            // ── TEMPORARY (RP-01 U-002 exposure): executable-evidence scanning
            // surfaced these broad catches; each CE fix is owned by the named
            // remediation plan and the entry MUST be removed when that plan
            // lands. Do not renew without a named owner. ──
            ArchitectureAllowlistEntry("NotificationProcessingPipeline.kt", "CATCH_WITHOUT_CE_RETHROW", category = "REPOSITORY", owner = "RP-10", "Broad catch exposed by executable-evidence guard; CE fix owned by RP-10", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ReceiptLinkService.kt", "CATCH_WITHOUT_CE_RETHROW", category = "DOMAIN", owner = "RP-12", "Broad catch exposed by executable-evidence guard; CE fix owned by RP-12", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ReceiptSideEffectPlanner.kt", "CATCH_WITHOUT_CE_RETHROW", category = "DOMAIN", owner = "RP-12", "Broad catch exposed by executable-evidence guard; CE fix owned by RP-12", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CsvExpenseImporter.kt", "CATCH_WITHOUT_CE_RETHROW", category = "DOMAIN", owner = "RP-19", "Broad catch exposed by executable-evidence guard; CE fix owned by RP-19", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("JsonExpenseImporter.kt", "CATCH_WITHOUT_CE_RETHROW", category = "DOMAIN", owner = "RP-19", "Broad + raw runCatching exposed by executable-evidence guard; CE fix owned by RP-19", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("SideEffectDiagnosticRecorder.kt", "CATCH_WITHOUT_CE_RETHROW", category = "DOMAIN", owner = "UNASSIGNED", "Broad catch exposed by executable-evidence guard; triage owner required", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("PostCommitSideEffectEvidenceService.kt", "CATCH_WITHOUT_CE_RETHROW", category = "SERVICE", owner = "UNASSIGNED", "Broad catch exposed by executable-evidence guard; triage owner required", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ConfidenceRouter.kt", "CATCH_WITHOUT_CE_RETHROW", category = "DOMAIN", owner = "UNASSIGNED", "Broad catch exposed by executable-evidence guard; triage owner required", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("LocationResolver.kt", "CATCH_WITHOUT_CE_RETHROW", category = "DOMAIN", owner = "UNASSIGNED", "Broad catch exposed by executable-evidence guard; triage owner required", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CarbonFootprintCalculator.kt", "CATCH_WITHOUT_CE_RETHROW", category = "DOMAIN", owner = "UNASSIGNED", "Broad catch exposed by executable-evidence guard; triage owner required", "MIT-034", LocalDate.of(2026, 12, 31))
        )

        val RAW_RUN_CATCHING_ALLOWLIST = listOf(
            RawRunCatchingAllowlistEntry("RestoreJournalImporter.kt", "LEGACY_REPOSITORY", "Backup", "Non-critical backup restore utility", "MIT-034", LocalDate.of(2026, 10, 1)),
            RawRunCatchingAllowlistEntry("DefaultCloudPayloadPolicy.kt", "NETWORK_PROVIDER", "Privacy", "Non-critical cloud payload formatting", "MIT-034", LocalDate.of(2026, 10, 1)),
            RawRunCatchingAllowlistEntry("AnalyticsRepository.kt", "LEGACY_REPOSITORY", "Analytics", "Non-critical analytics repository", "MIT-034", LocalDate.of(2026, 10, 1)),
            RawRunCatchingAllowlistEntry("DatabaseBackupRepositoryImpl.kt", "LEGACY_REPOSITORY", "Backup", "Non-critical backup repository", "MIT-034", LocalDate.of(2026, 10, 1)),
            RawRunCatchingAllowlistEntry("ValidateBankStatementTransactionsUseCase.kt", "LEGACY_REPOSITORY", "AI", "Non-critical bank validation use case", "MIT-034", LocalDate.of(2026, 10, 1)),
            RawRunCatchingAllowlistEntry("AdvancedAnalyticsEngine.kt", "LEGACY_REPOSITORY", "Analytics", "Non-critical analytics engine", "MIT-034", LocalDate.of(2026, 10, 1)),
            RawRunCatchingAllowlistEntry("CarbonFootprintCalculator.kt", "LEGACY_REPOSITORY", "Carbon", "Non-critical carbon footprint calculator", "MIT-034", LocalDate.of(2026, 10, 1)),
            RawRunCatchingAllowlistEntry("DiagnosticsRepository.kt", "LEGACY_REPOSITORY", "Diagnostics", "Non-critical diagnostics repository", "MIT-034", LocalDate.of(2026, 10, 1)),
            RawRunCatchingAllowlistEntry("FinancialStressForecastEngine.kt", "LEGACY_REPOSITORY", "Forecasting", "Non-critical financial stress engine", "MIT-034", LocalDate.of(2026, 10, 1)),
            RawRunCatchingAllowlistEntry("NetCashflowBalanceProvider.kt", "LEGACY_REPOSITORY", "Forecasting", "Non-critical cashflow provider", "MIT-034", LocalDate.of(2026, 10, 1)),
            RawRunCatchingAllowlistEntry("InvestmentTracker.kt", "LEGACY_REPOSITORY", "Investment", "Non-critical investment tracker", "MIT-034", LocalDate.of(2026, 10, 1)),
            RawRunCatchingAllowlistEntry("SubscriptionManagerEngine.kt", "LEGACY_REPOSITORY", "Subscription", "Non-critical subscription engine", "MIT-034", LocalDate.of(2026, 10, 1)),
            // ── TEMPORARY (RP-01 U-002 exposure): raw runCatching surfaced by the
            // un-excluded coordinator scan path and executable-evidence rework;
            // CE fixes are owned by the named plans. Must shrink. ──
            RawRunCatchingAllowlistEntry("RestoreDiagnosticsSink.kt", "BACKUP_DATA", "RP-03", "Raw runCatching in restore diagnostics sink; CE fix owned by RP-03", "MIT-034", LocalDate.of(2026, 12, 31)),
            RawRunCatchingAllowlistEntry("HistoricalSpendingDistribution.kt", "DOMAIN", "RP-06", "Raw runCatching in forecasting distribution; CE fix owned by RP-06", "MIT-034", LocalDate.of(2026, 12, 31)),
        )
    }

    /**
     * Matches `catch (e: Exception)`, `catch (_: Exception)`, `catch (e: Throwable)`,
     * `catch (_: Throwable)` — i.e. any broad catch that would swallow CE.
     * Group 1 captures the caught variable name for evidence checks.
     */
    private val broadCatchPattern = Regex("""\bcatch\s*\(\s*(\w+)\s*:\s*(Exception|Throwable)\s*\)""")

    /** Matches `suspend fun` declarations (including `private suspend fun`, etc.). */
    private val suspendFunPattern = Regex("""\bsuspend\s+fun\b""")

    /**
     * U-002: executable CE-guard evidence only — a comment, string literal,
     * import, or bare `CancellationException` token is NOT evidence. Callers
     * MUST pass sanitizer output as [catchBody].
     *
     * Accepted forms (per RP-01 U-002):
     *  1. a call to the verified `rethrowIfCancellation` helper;
     *  2. the type-check-then-throw idiom: `if (e is CancellationException) throw e`;
     *  3. unconditional rethrow of the caught throwable: `throw e`;
     *  4. `ensureActive()` actually in the catch path.
     */
    private fun hasExecutableCeGuard(caughtVar: String, catchBody: String): Boolean {
        if (Regex("""\brethrowIfCancellation\s*\(""").containsMatchIn(catchBody)) return true
        if (Regex("""\bis\s+[\w.]*CancellationException\s*\)\s*throw\b""").containsMatchIn(catchBody)) return true
        if (Regex("""\bthrow\s+${Regex.escape(caughtVar)}\b""").containsMatchIn(catchBody)) return true
        if (Regex("""\bensureActive\s*\(\s*\)""").containsMatchIn(catchBody)) return true
        return false
    }

    @Test
    fun `every broad catch in suspend functions rethrows CancellationException`() {
        val allowlistFileNames = KNOWN_VIOLATIONS.map { it.fileName }.toSet()
        val ktFiles = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name !in allowlistFileNames }
            .toList()

        assertTrue(
            "Architecture guard scanned ZERO .kt files in ${sourceRoot.absolutePath}. " +
                "Source root resolution is broken — this test would pass vacuously.",
            ktFiles.isNotEmpty()
        )

        val violations = mutableListOf<String>()
        val runCatchingViolations = mutableListOf<String>()

        // PR23: also check for raw runCatching in suspend paths
        val rawRunCatchingPattern = Regex("""(?<!CancellationSafe\.)\brunCatching\s*\{""")

        for (file in ktFiles) {
            // U-002: scan sanitized text — comment/string mentions can never
            // count as evidence or fabricate a violation. Newlines are
            // preserved, so line numbers in violations stay accurate.
            val content = SourceTextSanitizer.stripCommentsAndStringBodies(file.readText())
            // Only inspect files that contain at least one suspend fun
            if (!suspendFunPattern.containsMatchIn(content)) continue

            val lines = content.lines()
            val suspendFunRanges = findSuspendFunBodyRanges(content)

            for (match in broadCatchPattern.findAll(content)) {
                val catchPos = match.range.first
                // Check if this catch is inside a suspend function body
                if (suspendFunRanges.none { catchPos in it }) continue

                // Find the catch block body (from opening { to matching })
                val catchBody = extractCatchBlockBody(content, match.range.last)
                    ?: continue

                // A preceding sibling `catch (x: CancellationException) { ... }`
                // in the same try means the broad catch can never receive CE.
                val siblingWindow = content.substring((catchPos - 200).coerceAtLeast(0), catchPos)

                if (!hasExecutableCeGuard(match.groupValues[1], catchBody) &&
                    !precedingSiblingCeCatch.containsMatchIn(siblingWindow)
                ) {
                    val lineNum = content.substring(0, catchPos).count { it == '\n' } + 1
                    val relativePath = file.relativeTo(sourceRoot).path
                    violations.add("$relativePath:$lineNum — broad catch without CancellationException guard")
                }
            }

            // PR23: also scan for raw runCatching in suspend paths
            for (match in rawRunCatchingPattern.findAll(content)) {
                val matchPos = match.range.first
                if (suspendFunRanges.any { matchPos in it }) {
                    val lineNum = content.substring(0, matchPos).count { it == '\n' } + 1
                    val relativePath = file.relativeTo(sourceRoot).path
                    runCatchingViolations.add(
                        "$relativePath:$lineNum — raw runCatching in suspend function"
                    )
                }
            }
        }

        val rawRunCatchingAllowlistNames = RAW_RUN_CATCHING_ALLOWLIST.map { it.fileName }.toSet()

        val runCatchingFiltered = runCatchingViolations.filter { v ->
            val pathPart = v.substringBefore(" — ").substringBeforeLast(":")
            val fileName = pathPart.substringAfterLast("\\").substringAfterLast("/")
            fileName !in rawRunCatchingAllowlistNames
        }

        val allViolations = violations + runCatchingFiltered
        assertTrue(
            "CANCEL-01 violations: broad catch blocks in suspend functions that do NOT " +
                "rethrow CancellationException, plus raw runCatching in suspend paths:\n${allViolations.joinToString("\n")}",
            allViolations.isEmpty()
        )
    }

    @Test
    fun `source scan is non-empty - guard is not vacuous`() {
        val suspendFiles = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { suspendFunPattern.containsMatchIn(it.readText()) }
            .count()

        assertTrue(
            "Expected at least 10 files with suspend functions; found $suspendFiles. " +
                "Source root may be wrong: ${sourceRoot.absolutePath}",
            suspendFiles >= 10
        )
    }

    @Test
    fun `allowlist entries map to real source files`() {
        val allKtNames = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .map { it.name }
            .toSet()
        val stale = KNOWN_VIOLATIONS.filter { it.fileName !in allKtNames }.map { it.fileName }
        assertTrue(
            "KNOWN_VIOLATIONS contains entries that don't map to real source files: $stale. " +
                "Remove stale entries as violations are fixed.",
            stale.isEmpty()
        )
    }

    // ── Structured allowlist validation ───────────────────────────────

    @Test
    fun `structured allowlist requires owner reason issue expiry`() {
        for (entry in KNOWN_VIOLATIONS) {
            assertTrue(
                "Allowlist entry ${entry.fileName} missing owner",
                entry.owner.isNotBlank()
            )
            assertTrue(
                "Allowlist entry ${entry.fileName} missing reason",
                entry.reason.isNotBlank()
            )
            assertTrue(
                "Allowlist entry ${entry.fileName} missing rule",
                entry.rule.isNotBlank()
            )
            val validRules = setOf("CATCH_WITHOUT_CE_RETHROW", "FALSE_POSITIVE_CE_RETHROW", "LAUNCH_CE_NO_RETHROW", "RAW_RUN_CATCHING_IN_SUSPEND_PATH")
            assertTrue(
                "Allowlist entry ${entry.fileName} has invalid rule '${entry.rule}'. Must be one of: $validRules",
                entry.rule in validRules
            )
            assertTrue(
                "Allowlist entry ${entry.fileName} missing issue",
                entry.issue.isNotBlank()
            )
            assertNotNull(
                "Allowlist entry ${entry.fileName} missing expiry",
                entry.expires
            )
            assertTrue(
                "Allowlist entry ${entry.fileName} missing non-blank category",
                entry.category.isNotBlank()
            )
        }
    }

    /** U-003: single injectable clock seam for expiry checks — keeps tests deterministic. */
    private fun guardToday(): java.time.LocalDate = java.time.LocalDate.now()

    @Test
    fun `expired allowlist entries fail`() {
        val today = guardToday()
        val expired = KNOWN_VIOLATIONS.filter { it.expires.isBefore(today) }
        assertTrue(
            "Expired allowlist entries found: ${expired.map { it.fileName }}",
            expired.isEmpty()
        )
    }

    /**
     * U-003: the raw `runCatching` allowlist previously asserted only that
     * expiry was non-null — the entries could silently become permanent.
     * The same enforced expiry as KNOWN_VIOLATIONS now applies.
     */
    @Test
    fun `expired raw runCatching allowlist entries fail`() {
        val today = guardToday()
        val expired = RAW_RUN_CATCHING_ALLOWLIST.filter { it.expires.isBefore(today) }
        assertTrue(
            "Expired raw runCatching allowlist entries found: ${expired.map { it.fileName }} — " +
                "renew with owner/reason or fix the underlying files",
            expired.isEmpty()
        )
    }

    @Test
    fun `no duplicate allowlist entries`() {
        val dupes = KNOWN_VIOLATIONS.groupBy { it.fileName }.filter { it.value.size > 1 }
        assertTrue(
            "Duplicate allowlist entries found: ${dupes.keys}. Each file must appear exactly once.",
            dupes.isEmpty()
        )
    }

    @Test
    fun `cancellation allowlist burn-down targets`() {
        // MIT-034 burn-down: core categories must be empty.
        val forbiddenCoreCategories = setOf("WORKER", "COORDINATOR", "RECEIVER")
        val coreViolations = KNOWN_VIOLATIONS.filter { it.category in forbiddenCoreCategories }

        val softTargetCategories = setOf("REPOSITORY", "DOMAIN")
        val softTargets = KNOWN_VIOLATIONS.filter { it.category in softTargetCategories }

        // Hard requirement: no core files in allowlist
        assertTrue(
            "MIT-034 burn-down: core categories (WORKER, COORDINATOR, RECEIVER) must be empty. " +
                "Found: ${coreViolations.map { "${it.fileName} (${it.category})" }}",
            coreViolations.isEmpty()
        )

        // Soft target: repositories and domain files should be under count or short-expiry
        val softTargetsWithLongExpiry = softTargets.filter {
            it.expires.isAfter(LocalDate.of(2026, 10, 1))
        }
        val uiCount = KNOWN_VIOLATIONS.count { it.category == "UI" }

        // Document the current state for burn-down tracking
        println("MIT-034 burn-down state:")
        println("  Total allowlist: ${KNOWN_VIOLATIONS.size}")
        println("  UI: $uiCount")
        println("  AI: ${KNOWN_VIOLATIONS.count { it.category == "AI" }}")
        println("  INFRASTRUCTURE: ${KNOWN_VIOLATIONS.count { it.category == "INFRASTRUCTURE" }}")
        println("  DOMAIN: ${KNOWN_VIOLATIONS.count { it.category == "DOMAIN" }}")
        println("  REPOSITORY: ${KNOWN_VIOLATIONS.count { it.category == "REPOSITORY" }}")
        println("  WORKER: ${KNOWN_VIOLATIONS.count { it.category == "WORKER" }}")
        println("  SERVICE: ${KNOWN_VIOLATIONS.count { it.category == "SERVICE" }}")
        println("  BACKUP_DATA: ${KNOWN_VIOLATIONS.count { it.category == "BACKUP_DATA" }}")
        println("  Soft targets with long expiry: ${softTargetsWithLongExpiry.size}")
    }

    @Test
    fun `raw runCatching allowlist requires owner reason issue expiry`() {
        for (entry in RAW_RUN_CATCHING_ALLOWLIST) {
            assertTrue("${entry.fileName}: missing owner", entry.owner.isNotBlank())
            assertTrue("${entry.fileName}: missing reason", entry.reason.isNotBlank())
            assertTrue("${entry.fileName}: missing issue", entry.issue.isNotBlank())
            assertNotNull("${entry.fileName}: missing expiry", entry.expires)
        }
    }

    @Test
    fun `no duplicate raw runCatching allowlist entries`() {
        val dupes = RAW_RUN_CATCHING_ALLOWLIST.groupBy { it.fileName }.filter { it.value.size > 1 }
        assertTrue("Duplicate entries: ${dupes.keys}", dupes.isEmpty())
    }

    @Test
    fun `raw runCatching in suspend paths is detected`() {
        val badSource = """
            package test
            class BadRepo {
                suspend fun fetchData() {
                    val result = runCatching {
                        Thread.sleep(100)
                    }.getOrElse {
                        println("Failed")
                    }
                }
            }
        """.trimIndent()

        val runCatchingPattern = Regex("""\brunCatching\s*\{""")
        val matches = runCatchingPattern.findAll(badSource).count()

        assertTrue(
            "RAW_RUN_CATCHING: raw runCatching in suspend function must be detected. Found $matches occurrences.",
            matches > 0
        )
    }

    @Test
    fun `CancellationSafe runCatchingCancellable passes detection`() {
        val goodSource = """
            package test
            import com.yourname.expensetracker.domain.util.CancellationSafe
            class GoodRepo {
                suspend fun fetchData() {
                    val result = CancellationSafe.runCatchingCancellable {
                        Thread.sleep(100)
                    }.getOrElse {
                        println("Safe")
                    }
                }
            }
        """.trimIndent()

        // CancellationSafe.runCatchingCancellable should NOT be flagged as raw runCatching
        val rawPattern = Regex("""(?<!CancellationSafe\.)\brunCatching\s*\{""")
        val badMatches = rawPattern.findAll(goodSource).count()

        assertTrue(
            "CancellationSafe.runCatchingCancellable should NOT be detected as raw runCatching, got $badMatches matches",
            badMatches == 0
        )
    }

    // ── Inline fixture tests ────────────────────────────────────────

    private fun scanForCancellationViolations(sourceText: String): List<String> {
        val violations = mutableListOf<String>()
        val sanitized = SourceTextSanitizer.stripCommentsAndStringBodies(sourceText)
        val suspendFunRanges = findSuspendFunBodyRanges(sanitized)
        for (match in broadCatchPattern.findAll(sanitized)) {
            val catchPos = match.range.first
            if (suspendFunRanges.none { catchPos in it }) continue
            val catchBody = extractCatchBlockBody(sanitized, match.range.last) ?: continue
            val siblingWindow = sanitized.substring((catchPos - 200).coerceAtLeast(0), catchPos)
            if (!hasExecutableCeGuard(match.groupValues[1], catchBody) &&
                !precedingSiblingCeCatch.containsMatchIn(siblingWindow)
            ) {
                violations.add("CATCH_WITHOUT_CE_RETHROW at ${sanitized.substring(0, catchPos).count { it == '\n' } + 1}")
            }
        }
        return violations
    }

    @Test
    fun `negative fixture broad catch without CE rethrow is detected`() {
        val badSource = """
            package test
            import kotlinx.coroutines.CancellationException
            class BadService {
                suspend fun doWork() {
                    try {
                        Thread.sleep(100)
                    } catch (e: Exception) {
                        // Oops — no CE rethrow!
                        println("Failed")
                    }
                }
            }
        """.trimIndent()
        val violations = scanForCancellationViolations(badSource)
        assertTrue(
            "Negative fixture: broad catch without CE rethrow must be detected",
            violations.isNotEmpty()
        )
    }

    @Test
    fun `positive fixture broad catch with CE rethrow passes`() {
        val goodSource = """
            package test
            import kotlinx.coroutines.CancellationException
            class GoodService {
                suspend fun doWork() {
                    try {
                        Thread.sleep(100)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        println("Failed safely")
                    }
                }
            }
        """.trimIndent()
        val violations = scanForCancellationViolations(goodSource)
        assertTrue(
            "Positive fixture: broad catch with CE rethrow must pass (got ${violations.size} violations)",
            violations.isEmpty()
        )
    }

    @Test
    fun `positive fixture catch with rethrowIfCancellation helper passes`() {
        val goodSource = """
            package test
            import com.yourname.expensetracker.domain.util.CancellationSafe
            class GoodService {
                suspend fun doWork() {
                    try {
                        Thread.sleep(100)
                    } catch (e: Exception) {
                        CancellationSafe.rethrowIfCancellation(e)
                        println("Failed safely")
                    }
                }
            }
        """.trimIndent()
        val violations = scanForCancellationViolations(goodSource)
        assertTrue(
            "Positive fixture: catch with rethrowIfCancellation must pass (got ${violations.size} violations)",
            violations.isEmpty()
        )
    }

    @Test
    fun `negative fixture - default parameter suspend fun body is scanned and raw runCatching found`() {
        val badSource = """
            package test
            class BadRepo {
                suspend fun fetchData(limit: Int = 10, label: String = "x") {
                    val result = runCatching {
                        Thread.sleep(100)
                    }.getOrNull()
                }
            }
        """.trimIndent()

        // U-002: the balanced-paren scanner must produce a body range despite
        // the default parameters' `=` signs (the old scanner returned none).
        val ranges = findSuspendFunBodyRanges(badSource)
        assertTrue(
            "Default-parameter suspend fun must yield a scannable body range, got ${ranges.size}",
            ranges.isNotEmpty()
        )
        val runCatchingPos = badSource.indexOf("runCatching")
        assertTrue(
            "raw runCatching must sit inside the scanned body range",
            ranges.any { runCatchingPos in it }
        )
    }

    @Test
    fun `positive fixture - default parameter suspend fun with executable CE rethrow passes`() {
        val goodSource = """
            package test
            import kotlinx.coroutines.CancellationException
            class GoodRepo {
                suspend fun fetchData(limit: Int = 10, label: String = "x") {
                    try {
                        Thread.sleep(100)
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        println("Failed safely")
                    }
                }
            }
        """.trimIndent()

        val violations = scanForCancellationViolations(goodSource)
        assertTrue(
            "Default-param suspend fun with executable CE rethrow must pass (got ${violations.size})",
            violations.isEmpty()
        )
    }

    @Test
    fun `negative fixture - comment-only CE mention is not evidence`() {
        val badSource = """
            package test
            class BadService {
                suspend fun doWork() {
                    try {
                        Thread.sleep(100)
                    } catch (e: Exception) {
                        // CancellationException is rethrown elsewhere; this is fine (sic)
                        println("Failed")
                    }
                }
            }
        """.trimIndent()

        val violations = scanForCancellationViolations(badSource)
        assertTrue(
            "Comment-only CancellationException mention must NOT count as CE evidence",
            violations.isNotEmpty()
        )
    }

    @Test
    fun `negative fixture catch Throwable without CE rethrow is detected`() {
        val badSource = """
            package test
            class BadService {
                suspend fun doWork() {
                    try {
                        Thread.sleep(100)
                    } catch (e: Throwable) {
                        // Catches everything without CE rethrow!
                        println("Failed")
                    }
                }
            }
        """.trimIndent()
        val violations = scanForCancellationViolations(badSource)
        assertTrue(
            "Negative fixture: catch Throwable without CE rethrow must be detected",
            violations.isNotEmpty()
        )
    }

    /** Matches coroutine launch patterns: `scope.launch {`, `launch {`, `async {`. */
    private val coroutineLaunchPattern = Regex("""\b(launch|async)\s*(\([^)]*\))?\s*\{""")

    /**
     * Pipeline-critical files that must NOT have unguarded catches in launch blocks.
     * ViewModel/UI files are excluded (viewModelScope auto-cancels).
     */
    private val LAUNCH_CRITICAL_FILES = setOf(
        "BudgetMonitor.kt",
        "TransactionLifecycleCoordinator.kt",
        "ReceiptLifecycleCoordinator.kt",
        "NotificationCaptureService.kt",
        "WorkerExecutionGuard.kt"
    )

    /** Detects a preceding sibling `catch (e: CancellationException)` clause before a broad catch (FQN-safe). */
    private val precedingSiblingCeCatch = Regex("""catch\s*\(\s*\w+\s*:\s*[\w.]*CancellationException\s*\)""")

    @Test
    fun `launch blocks in pipeline-critical files rethrow CancellationException`() {
        val violations = mutableListOf<String>()

        for (fileName in LAUNCH_CRITICAL_FILES) {
            val file = sourceRoot.walkTopDown()
                .filter { it.isFile && it.name == fileName }
                .firstOrNull() ?: continue

            val content = SourceTextSanitizer.stripCommentsAndStringBodies(file.readText())
            val launchRanges = findCoroutineLaunchBodyRanges(content)
            if (launchRanges.isEmpty()) continue

            for (match in broadCatchPattern.findAll(content)) {
                val catchPos = match.range.first
                if (launchRanges.none { catchPos in it }) continue

                val catchBody = extractCatchBlockBody(content, match.range.last) ?: continue
                val siblingWindow = content.substring((catchPos - 200).coerceAtLeast(0), catchPos)
                if (!hasExecutableCeGuard(match.groupValues[1], catchBody) &&
                    !precedingSiblingCeCatch.containsMatchIn(siblingWindow)
                ) {
                    val lineNum = content.substring(0, catchPos).count { it == '\n' } + 1
                    violations.add("$fileName:$lineNum — broad catch in launch/async without CE guard")
                }
            }
        }

        assertTrue(
            "CANCEL-01 launch-block violations:\n${violations.joinToString("\n")}",
            violations.isEmpty()
        )
    }

    /**
     * Returns character ranges of coroutine launch/async block bodies.
     */
    private fun findCoroutineLaunchBodyRanges(source: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        for (match in coroutineLaunchPattern.findAll(source)) {
            val bracePos = source.indexOf('{', match.range.last - 1)
            if (bracePos < 0) continue
            var depth = 1
            var i = bracePos + 1
            while (i < source.length && depth > 0) {
                when (source[i]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                i++
            }
            ranges.add(bracePos until i)
        }
        return ranges
    }

    /**
     * Returns character ranges of suspend function bodies in the source.
     *
     * U-002: the parameter list is consumed with a balanced-parenthesis scan
     * that skips string/char literals, so a default parameter's `=` can no
     * longer truncate the signature and hide the body from this scanner.
     * Expression bodies (body after `=`) still yield no range — a `catch`
     * cannot appear there.
     */
    private fun findSuspendFunBodyRanges(source: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        for (match in suspendFunPattern.findAll(source)) {
            var i = match.range.last + 1
            // Skip modifiers/name to the parameter-list opening paren.
            while (i < source.length && source[i] != '(' && source[i] != '{' && source[i] != '=') i++
            if (i >= source.length || source[i] != '(') continue
            var depth = 0
            while (i < source.length) {
                val ch = source[i]
                when {
                    ch == '"' -> i = skipStringLiteral(source, i) - 1
                    ch == '\'' -> i = skipCharLiteral(source, i) - 1
                    ch == '(' -> depth++
                    ch == ')' -> { depth--; if (depth == 0) break }
                }
                i++
            }
            if (i >= source.length) continue
            // After the parameter list: optional return type, then the body.
            var j = i + 1
            while (j < source.length && source[j] != '{' && source[j] != '=') j++
            if (j >= source.length || source[j] == '=') continue // expression body
            var bodyDepth = 1
            i = j + 1
            while (i < source.length && bodyDepth > 0) {
                when {
                    source[i] == '"' -> i = skipStringLiteral(source, i) - 1
                    source[i] == '\'' -> i = skipCharLiteral(source, i) - 1
                    source[i] == '{' -> bodyDepth++
                    source[i] == '}' -> bodyDepth--
                }
                i++
            }
            ranges.add(j until i)
        }
        return ranges
    }

    /** Returns the index just past the string literal starting at [start], honoring escapes and triple quotes. */
    private fun skipStringLiteral(source: String, start: Int): Int {
        if (source.startsWith("\"\"\"", start)) {
            val end = source.indexOf("\"\"\"", start + 3)
            return if (end < 0) source.length else end + 3
        }
        var i = start + 1
        while (i < source.length) {
            when {
                source[i] == '\\' -> i++
                source[i] == '"' -> return i + 1
            }
            i++
        }
        return source.length
    }

    /** Returns the index just past the char literal starting at [start], honoring escapes. */
    private fun skipCharLiteral(source: String, start: Int): Int {
        var i = start + 1
        while (i < source.length) {
            when {
                source[i] == '\\' -> i++
                source[i] == '\'' -> return i + 1
            }
            i++
        }
        return source.length
    }

    /**
     * Extracts the body of a catch block starting after the `)` of `catch (e: Exception)`.
     * Returns the text between the opening `{` and its matching `}`.
     */
    private fun extractCatchBlockBody(source: String, afterParenPos: Int): String? {
        var i = afterParenPos + 1
        // Skip to opening brace
        while (i < source.length && source[i] != '{') i++
        if (i >= source.length) return null
        val bodyStart = i + 1
        var depth = 1
        i++
        while (i < source.length && depth > 0) {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
            }
            i++
        }
        return source.substring(bodyStart, (i - 1).coerceAtLeast(bodyStart))
    }
}
