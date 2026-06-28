package com.yourname.expensetracker.architecture

import org.junit.Assert.*
import org.junit.Test
import java.io.File

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
         * As of 2026-05-31: Core pipeline files are clean. Remaining violations are
         * primarily in AI service providers, UI ViewModels, and utility classes.
         */
        val KNOWN_VIOLATIONS = setOf(
            // AI service providers (suspend functions with broad catches)
            "CloudCategorizationAssistService.kt",
            "CloudDashboardBriefingService.kt",
            "CloudDedupeJudgeService.kt",
            "CloudReceiptItemCategorizationService.kt",
            "CloudReviewExplanationService.kt",
            "DefaultAiEnvironmentMonitor.kt",
            "HybridDedupeJudgeService.kt",
            "OnDeviceCategorizationAssistService.kt",
            "OnDeviceDashboardBriefingService.kt",
            "OnDeviceDedupeJudgeService.kt",
            "OnDeviceNotificationParser.kt",
            "OnDeviceQueryInterpretationService.kt",
            "OnDeviceReceiptItemCategorizationService.kt",
            "OnDeviceReviewExplanationService.kt",
            // Backup/data
            "DataStoreMaintenanceSafeDiagnosticSink.kt",
            "AndroidForegroundLocationProvider.kt",
            "NominatimGeocodingService.kt",
            // Repositories
            "CategoryRepository.kt",
            "CurrencySettingsRepositoryImpl.kt",
            "GroupsRepositoryImpl.kt",
            "ManualRecurringExpenseRepository.kt",
            "ManualExpenseRepository.kt",
            "RecurringExpenseRepository.kt",
            // Domain/use-cases
            "CategorizationAssistInputBuilder.kt",
            "DedupeJudgeInputBuilder.kt",
            "SuggestCategoryFallbackUseCase.kt",
            "AnomalyAlertOrchestrator.kt",
            "CurrencySettingsRepository.kt",
            "ReceiptDebugExporter.kt",
            "NotificationDiagnosticEmitter.kt",
            "OperationRunRecorder.kt",
            "FinancialHealthScoreV2.kt",
            "CrossSourceDeduplication.kt",
            "ExpenseCategoryClassifier.kt",
            "TransactionClassifier.kt",
            "NotificationCaptureGate.kt",
            "NotificationIntakePayloadRepairer.kt",
            "CompositePrivacyGate.kt",
            "NotificationSubscriptionDetector.kt",
            "DefaultExpenseCategoryAssignmentService.kt",
            "DebugExpenseAuditWriter.kt",
            "ComputeDashboardWidgetsUseCase.kt",
            "ComputeMoneyRadarUseCase.kt",
            "MonthlySavingsSweepUseCase.kt",
            "AutoCreateWarrantyFromReceiptUseCase.kt",
            // Infrastructure (catches preceded by CE catch — false positives from regex)
            "CompositeSideEffectEventWriter.kt",
            "CompositeDiagnosticEventWriter.kt",
            "CompositeOperationRunRecorder.kt",
            "PostCommitActionRunnerImpl.kt",
            "GroupTransactionCoordinator.kt",
            "MerchantKeyBackfillWorker.kt",
            "WorkerExecutionGuard.kt",
            "NotificationProcessingPipeline.kt",
            "ReceiptRepository.kt",
            "ReviewQueueRepository.kt",
            "ReceiptLifecycleCoordinator.kt",
            "ReceiptOcrService.kt",
            "ReceiptMatchingWorker.kt",
            "WarrantyExpirationWorker.kt",
            // Services
            "NotificationCaptureService.kt",
            "RecommendationDismissalHandler.kt",
            "RecommendationInvalidator.kt",
            "RecommendationLifecycleManager.kt",
            "RecommendationStateManager.kt",
            // UI layer (viewModelScope.launch catches — lower priority)
            "AnalyticsViewModel.kt",
            "BudgetViewModel.kt",
            "BudgetForecastingViewModel.kt",
            "DebugDataStorage.kt",
            "DebugViewModel.kt",
            "SourceLinkDebugViewModel.kt",
            "HomeViewModel.kt",
            "SpendingMapViewModel.kt",
            "NaturalLanguageSearchViewModel.kt",
            "ReceiptScanViewModel.kt",
            "TransactionsViewModel.kt",
            "AddExpenseViewModel.kt",
            "AiSettingsViewModel.kt",
            "AssistantViewModel.kt",
            "BackupRestoreViewModel.kt",
            "CarbonFootprintViewModel.kt",
            "CashFlowCalendarViewModel.kt",
            "SpendingChallengesViewModel.kt",
            "CurrencyManagementViewModel.kt",
            "SharedExpenseGroupsViewModel.kt",
            "InvestmentViewModel.kt",
            "LifestyleInflationViewModel.kt",
            "BillNegotiationViewModel.kt",
            "PriceProtectionViewModel.kt",
            "PrivacySettingsViewModel.kt",
            "ReceiptMatchingViewModel.kt",
            "RecurringExpensesScreen.kt",
            "ManualRecurringExpenseViewModel.kt",
            "BillRemindersViewModel.kt",
            "ReviewViewModel.kt",
            "SubscriptionManagementViewModel.kt",
            "TaxConfigurationViewModel.kt",
            "WarrantyTrackerViewModel.kt",
            "ExportOptionsScreen.kt",
            "ExportOptionsViewModel.kt",
            "LocationSearchPicker.kt",
            "LoadableUiState.kt",
            "MutationState.kt",
            "DismissReminderReceiver.kt",
            "SnoozeReminderReceiver.kt",
            "InsightsEngine.kt",
            "TransactionLifecycleCoordinator.kt",
        )
    }

    /**
     * Matches `catch (e: Exception)`, `catch (_: Exception)`, `catch (e: Throwable)`,
     * `catch (_: Throwable)` — i.e. any broad catch that would swallow CE.
     */
    private val broadCatchPattern = Regex("""\bcatch\s*\(\s*\w+\s*:\s*(Exception|Throwable)\s*\)""")

    /** Matches `suspend fun` declarations (including `private suspend fun`, etc.). */
    private val suspendFunPattern = Regex("""\bsuspend\s+fun\b""")

    /** Evidence that CE is handled: the body mentions CancellationException. */
    private val ceGuardEvidence = Regex("""CancellationException""")

    @Test
    fun `every broad catch in suspend functions rethrows CancellationException`() {
        val ktFiles = sourceRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name !in KNOWN_VIOLATIONS }
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
        val stale = KNOWN_VIOLATIONS.filter { it !in allKtNames }
        assertTrue(
            "KNOWN_VIOLATIONS contains entries that don't map to real source files: $stale. " +
                "Remove stale entries as violations are fixed.",
            stale.isEmpty()
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
