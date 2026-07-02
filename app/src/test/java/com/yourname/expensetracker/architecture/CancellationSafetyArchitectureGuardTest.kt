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
            ArchitectureAllowlistEntry("CloudCategorizationAssistService.kt", "CATCH_WITHOUT_CE_RETHROW", "AI", "AI provider with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CloudDashboardBriefingService.kt", "CATCH_WITHOUT_CE_RETHROW", "AI", "AI provider with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CloudDedupeJudgeService.kt", "CATCH_WITHOUT_CE_RETHROW", "AI", "AI provider with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CloudReceiptItemCategorizationService.kt", "CATCH_WITHOUT_CE_RETHROW", "AI", "AI provider with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CloudReviewExplanationService.kt", "CATCH_WITHOUT_CE_RETHROW", "AI", "AI provider with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("DefaultAiEnvironmentMonitor.kt", "CATCH_WITHOUT_CE_RETHROW", "AI", "AI provider with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("HybridDedupeJudgeService.kt", "CATCH_WITHOUT_CE_RETHROW", "AI", "AI provider with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("OnDeviceDashboardBriefingService.kt", "CATCH_WITHOUT_CE_RETHROW", "AI", "AI provider with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("OnDeviceDedupeJudgeService.kt", "CATCH_WITHOUT_CE_RETHROW", "AI", "AI provider with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("OnDeviceNotificationParser.kt", "CATCH_WITHOUT_CE_RETHROW", "AI", "AI provider with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("OnDeviceQueryInterpretationService.kt", "CATCH_WITHOUT_CE_RETHROW", "AI", "AI provider with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("OnDeviceReceiptItemCategorizationService.kt", "CATCH_WITHOUT_CE_RETHROW", "AI", "AI provider with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("OnDeviceReviewExplanationService.kt", "CATCH_WITHOUT_CE_RETHROW", "AI", "AI provider with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            // ── Backup / data infrastructure ──────────────────────────────
            ArchitectureAllowlistEntry("DataStoreMaintenanceSafeDiagnosticSink.kt", "CATCH_WITHOUT_CE_RETHROW", "BackupData", "Backup infrastructure with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("AndroidForegroundLocationProvider.kt", "CATCH_WITHOUT_CE_RETHROW", "BackupData", "Location provider with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("NominatimGeocodingService.kt", "CATCH_WITHOUT_CE_RETHROW", "BackupData", "Geocoding service with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            // ── Repositories ──────────────────────────────────────────────
            ArchitectureAllowlistEntry("CategoryRepository.kt", "CATCH_WITHOUT_CE_RETHROW", "Repository", "Repository with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CurrencySettingsRepositoryImpl.kt", "CATCH_WITHOUT_CE_RETHROW", "Repository", "Repository with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("GroupsRepositoryImpl.kt", "CATCH_WITHOUT_CE_RETHROW", "Repository", "Repository with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ManualRecurringExpenseRepository.kt", "CATCH_WITHOUT_CE_RETHROW", "Repository", "Repository with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ManualExpenseRepository.kt", "CATCH_WITHOUT_CE_RETHROW", "Repository", "Repository with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("RecurringExpenseRepository.kt", "CATCH_WITHOUT_CE_RETHROW", "Repository", "Repository with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            // ── Domain / use-cases ────────────────────────────────────────
            ArchitectureAllowlistEntry("CategorizationAssistInputBuilder.kt", "CATCH_WITHOUT_CE_RETHROW", "Domain", "Domain use-case with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("DedupeJudgeInputBuilder.kt", "CATCH_WITHOUT_CE_RETHROW", "Domain", "Domain use-case with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("SuggestCategoryFallbackUseCase.kt", "CATCH_WITHOUT_CE_RETHROW", "Domain", "Domain use-case with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("AnomalyAlertOrchestrator.kt", "CATCH_WITHOUT_CE_RETHROW", "Domain", "Domain orchestrator with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CurrencySettingsRepository.kt", "CATCH_WITHOUT_CE_RETHROW", "Domain", "Domain interface with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ReceiptDebugExporter.kt", "CATCH_WITHOUT_CE_RETHROW", "Domain", "Domain exporter with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("NotificationDiagnosticEmitter.kt", "CATCH_WITHOUT_CE_RETHROW", "Domain", "Domain diagnostics with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("OperationRunRecorder.kt", "CATCH_WITHOUT_CE_RETHROW", "Domain", "Domain recorder with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("FinancialHealthScoreV2.kt", "CATCH_WITHOUT_CE_RETHROW", "Domain", "Domain calculator with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CrossSourceDeduplication.kt", "CATCH_WITHOUT_CE_RETHROW", "Domain", "Domain dedup with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ExpenseCategoryClassifier.kt", "CATCH_WITHOUT_CE_RETHROW", "Domain", "Domain classifier with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("TransactionClassifier.kt", "CATCH_WITHOUT_CE_RETHROW", "Domain", "Domain classifier with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("NotificationIntakePayloadRepairer.kt", "CATCH_WITHOUT_CE_RETHROW", "Domain", "Domain repairer with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CompositePrivacyGate.kt", "CATCH_WITHOUT_CE_RETHROW", "Domain", "Domain privacy gate with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("NotificationSubscriptionDetector.kt", "CATCH_WITHOUT_CE_RETHROW", "Domain", "Domain detector with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("DefaultExpenseCategoryAssignmentService.kt", "CATCH_WITHOUT_CE_RETHROW", "Domain", "Domain assignment service with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("DebugExpenseAuditWriter.kt", "CATCH_WITHOUT_CE_RETHROW", "Domain", "Domain audit writer with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ComputeDashboardWidgetsUseCase.kt", "CATCH_WITHOUT_CE_RETHROW", "Domain", "Domain use-case with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ComputeMoneyRadarUseCase.kt", "CATCH_WITHOUT_CE_RETHROW", "Domain", "Domain use-case with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("MonthlySavingsSweepUseCase.kt", "CATCH_WITHOUT_CE_RETHROW", "Domain", "Domain use-case with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("AutoCreateWarrantyFromReceiptUseCase.kt", "CATCH_WITHOUT_CE_RETHROW", "Domain", "Domain use-case with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            // ── Infrastructure (false positives from regex — catches preceded by CE catch) ──
            ArchitectureAllowlistEntry("CompositeSideEffectEventWriter.kt", "FALSE_POSITIVE_CE_RETHROW", "Infrastructure", "False positive — broad catch preceded by CE catch", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CompositeDiagnosticEventWriter.kt", "FALSE_POSITIVE_CE_RETHROW", "Infrastructure", "False positive — broad catch preceded by CE catch", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CompositeOperationRunRecorder.kt", "FALSE_POSITIVE_CE_RETHROW", "Infrastructure", "False positive — broad catch preceded by CE catch", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("PostCommitActionRunnerImpl.kt", "FALSE_POSITIVE_CE_RETHROW", "Infrastructure", "False positive — broad catch preceded by CE catch", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("GroupTransactionCoordinator.kt", "FALSE_POSITIVE_CE_RETHROW", "Infrastructure", "False positive — broad catch preceded by CE catch", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("MerchantKeyBackfillWorker.kt", "FALSE_POSITIVE_CE_RETHROW", "Infrastructure", "False positive — broad catch preceded by CE catch", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("WorkerExecutionGuard.kt", "FALSE_POSITIVE_CE_RETHROW", "Infrastructure", "False positive — broad catches with preceding CE siblings", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("NotificationProcessingPipeline.kt", "FALSE_POSITIVE_CE_RETHROW", "Infrastructure", "False positive — broad catch preceded by CE catch", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ReceiptRepository.kt", "FALSE_POSITIVE_CE_RETHROW", "Infrastructure", "False positive — broad catch preceded by CE catch", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ReviewQueueRepository.kt", "FALSE_POSITIVE_CE_RETHROW", "Infrastructure", "False positive — broad catch preceded by CE catch", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ReceiptLifecycleCoordinator.kt", "FALSE_POSITIVE_CE_RETHROW", "Infrastructure", "False positive — broad catches with preceding CE siblings", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ReceiptOcrService.kt", "FALSE_POSITIVE_CE_RETHROW", "Infrastructure", "False positive — broad catch preceded by CE catch", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ReceiptMatchingWorker.kt", "FALSE_POSITIVE_CE_RETHROW", "Infrastructure", "False positive — broad catch preceded by CE catch", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("WarrantyExpirationWorker.kt", "FALSE_POSITIVE_CE_RETHROW", "Infrastructure", "False positive — broad catch preceded by CE catch", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("TransactionLifecycleCoordinator.kt", "FALSE_POSITIVE_CE_RETHROW", "Infrastructure", "False positive — broad catch at L239 preceded by sibling CE catch at L237", "MIT-034", LocalDate.of(2026, 12, 31)),
            // ── Services ──────────────────────────────────────────────────
            ArchitectureAllowlistEntry("NotificationCaptureService.kt", "CATCH_WITHOUT_CE_RETHROW", "Service", "Service with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CloudQueryInterpretationService.kt", "CATCH_WITHOUT_CE_RETHROW", "Service", "Cloud service with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("SmartBillNegotiationEngine.kt", "CATCH_WITHOUT_CE_RETHROW", "Service", "Service with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            // ── Workers ───────────────────────────────────────────────────
            ArchitectureAllowlistEntry("NotificationIntakeWorker.kt", "CATCH_WITHOUT_CE_RETHROW", "Worker", "Worker with broad catches in suspend functions", "MIT-034", LocalDate.of(2026, 12, 31)),
            // ── Infrastructure / sinks ────────────────────────────────────
            ArchitectureAllowlistEntry("FileWorkerTerminalDiagnosticSink.kt", "CATCH_WITHOUT_CE_RETHROW", "Infrastructure", "Diagnostic sink with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            // ── UI layer (viewModelScope.launch catches — lower priority) ──
            ArchitectureAllowlistEntry("AnalyticsViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("BudgetViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("BudgetForecastingViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("DebugDataStorage.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel/debug storage with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("DebugViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("SourceLinkDebugViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("HomeViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("SpendingMapViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("NaturalLanguageSearchViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ReceiptScanViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("TransactionsViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("AddExpenseViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("AiSettingsViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("AssistantViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("BackupRestoreViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CarbonFootprintViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CashFlowCalendarViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("SpendingChallengesViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("CurrencyManagementViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("SharedExpenseGroupsViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("InvestmentViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("LifestyleInflationViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("BillNegotiationViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("PriceProtectionViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("PrivacySettingsViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ReceiptMatchingViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("RecurringExpensesScreen.kt", "LAUNCH_CE_NO_RETHROW", "UI", "Screen composable with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ManualRecurringExpenseViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("BillRemindersViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ReviewViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("SubscriptionManagementViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("TaxConfigurationViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("WarrantyTrackerViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ExportOptionsScreen.kt", "LAUNCH_CE_NO_RETHROW", "UI", "Screen composable with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("ExportOptionsViewModel.kt", "LAUNCH_CE_NO_RETHROW", "UI", "ViewModel with broad catches in viewModelScope", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("LocationSearchPicker.kt", "LAUNCH_CE_NO_RETHROW", "UI", "UI component with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("LoadableUiState.kt", "LAUNCH_CE_NO_RETHROW", "UI", "UI state utility with broad catches", "MIT-034", LocalDate.of(2026, 12, 31)),
            ArchitectureAllowlistEntry("MutationState.kt", "LAUNCH_CE_NO_RETHROW", "UI", "UI state utility with broad catches", "MIT-034", LocalDate.of(2026, 12, 31))
        )
    }

    /**
     * Matches `catch (e: Exception)`, `catch (_: Exception)`, `catch (e: Throwable)`,
     * `catch (_: Throwable)` — i.e. any broad catch that would swallow CE.
     */
    private val broadCatchPattern = Regex("""\bcatch\s*\(\s*\w+\s*:\s*(Exception|Throwable)\s*\)""")

    /** Matches `suspend fun` declarations (including `private suspend fun`, etc.). */
    private val suspendFunPattern = Regex("""\bsuspend\s+fun\b""")

    /** Evidence that CE is handled: the body mentions CancellationException or a known CE-safe helper. */
    private val ceGuardEvidence = Regex("""CancellationException|rethrowIfCancellation""")

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

        for (file in ktFiles) {
            val content = file.readText()
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

                if (!ceGuardEvidence.containsMatchIn(catchBody)) {
                    val lineNum = content.substring(0, catchPos).count { it == '\n' } + 1
                    val relativePath = file.relativeTo(sourceRoot).path
                    violations.add("$relativePath:$lineNum — broad catch without CancellationException guard")
                }
            }
        }

        assertTrue(
            "CANCEL-01 violations: broad catch blocks in suspend functions that do NOT " +
                "rethrow CancellationException:\n${violations.joinToString("\n")}",
            violations.isEmpty()
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
            val validRules = setOf("CATCH_WITHOUT_CE_RETHROW", "FALSE_POSITIVE_CE_RETHROW", "LAUNCH_CE_NO_RETHROW")
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
        }
    }

    @Test
    fun `expired allowlist entries fail`() {
        val today = LocalDate.now()
        val expired = KNOWN_VIOLATIONS.filter { it.expires.isBefore(today) }
        assertTrue(
            "Expired allowlist entries found: ${expired.map { it.fileName }}",
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

    // ── Inline fixture tests ────────────────────────────────────────

    private fun scanForCancellationViolations(sourceText: String): List<String> {
        val violations = mutableListOf<String>()
        val suspendFunRanges = findSuspendFunBodyRanges(sourceText)
        for (match in broadCatchPattern.findAll(sourceText)) {
            val catchPos = match.range.first
            if (suspendFunRanges.none { catchPos in it }) continue
            val catchBody = extractCatchBlockBody(sourceText, match.range.last) ?: continue
            if (!ceGuardEvidence.containsMatchIn(catchBody)) {
                violations.add("CATCH_WITHOUT_CE_RETHROW at ${sourceText.substring(0, catchPos).count { it == '\n' } + 1}")
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

    /** Detects a preceding sibling `catch (e: CancellationException)` clause before a broad catch. */
    private val precedingSiblingCeCatch = Regex("""catch\s*\(\s*\w+\s*:\s*CancellationException\s*\)""")

    @Test
    fun `launch blocks in pipeline-critical files rethrow CancellationException`() {
        val violations = mutableListOf<String>()

        for (fileName in LAUNCH_CRITICAL_FILES) {
            val file = sourceRoot.walkTopDown()
                .filter { it.isFile && it.name == fileName }
                .firstOrNull() ?: continue

            val content = file.readText()
            val launchRanges = findCoroutineLaunchBodyRanges(content)
            if (launchRanges.isEmpty()) continue

            for (match in broadCatchPattern.findAll(content)) {
                val catchPos = match.range.first
                if (launchRanges.none { catchPos in it }) continue

                val catchBody = extractCatchBlockBody(content, match.range.last) ?: continue
                if (!ceGuardEvidence.containsMatchIn(catchBody)) {
                    // Check for a preceding sibling CE catch in the same try block
                    val lookback = content.substring((catchPos - 200).coerceAtLeast(0), catchPos)
                    if (precedingSiblingCeCatch.containsMatchIn(lookback)) continue

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
     * Uses brace-matching from the opening `{` after `suspend fun ...(...): ...`.
     */
    private fun findSuspendFunBodyRanges(source: String): List<IntRange> {
        val ranges = mutableListOf<IntRange>()
        for (match in suspendFunPattern.findAll(source)) {
            // Find the opening brace of the function body
            var i = match.range.last + 1
            var foundEquals = false
            while (i < source.length) {
                val ch = source[i]
                if (ch == '{') break
                if (ch == '=') { foundEquals = true; break }
                i++
            }
            if (i >= source.length || foundEquals) continue // expression body — no catch possible

            // Brace-match to find the end of the function body
            val bodyStart = i
            var depth = 1
            i++
            while (i < source.length && depth > 0) {
                when (source[i]) {
                    '{' -> depth++
                    '}' -> depth--
                }
                i++
            }
            ranges.add(bodyStart until i)
        }
        return ranges
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
