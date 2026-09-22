# CA-2026-09-21 - CLUSTER MAP (Stage 2)
Provenance: 2026-09-22; pin 37601232 verified (git rev-parse --short HEAD -> 37601232; git diff --stat 37601232..HEAD -- app config scripts -> empty); direct astra-high session.
Totals: 30 clusters - 13 SYSTEMIC, 16 LOCAL, 0 DEBT-COLLISION, 1 DOCS-ONLY; 89 findings clustered, 0 unclustered, 89 accounted.
Revision 2026-09-22 (human-ratified review fixes): CL-03 block wave corrected to 4 (wave map was authoritative). CL-18 promoted to Wave 1 (same files as CL-17, which depends on it; 2 findings/M). CL-29 "after CL-05" edge removed as over-cautious — no currency coupling in split validation or settlement signs; re-add only with code evidence. Placement decisions CL-05/CL-17/CL-22 APPROVED by human with conditions: CL-05 splits into core-contract PR + consumer-sweep PR; CL-17 defaults to reason-codes/class-name with EventMetadataSanitizer as fallback only; CL-22 runs the full guard suite before merge to enumerate newly-failing violations.

## Wave map
Wave 1: CL-01, CL-17, CL-18, CL-19, CL-29
Wave 2: CL-05, CL-09, CL-15, CL-21, CL-22, CL-23, CL-27
Wave 3: CL-07, CL-08, CL-10, CL-11, CL-25
Wave 4: CL-02, CL-03, CL-04, CL-06, CL-12, CL-13, CL-14, CL-16, CL-20, CL-24, CL-26, CL-28, CL-30

## Clusters

### CL-01 - Notification capture privacy and extraction boundary
- Class: LOCAL
- Severity: P0
- Findings (2): CA-P-01-001, CA-P-01-004
- Root cause: Deferred capture lacks a final consent/blocked-package check, and live filtering receives only bigText instead of the extracted combined body.
- Fix shape: Gate immediately before deferred persistence/forwarding and pass NotificationTextParts.combinedBody to the live filter.
- Placement: NotificationCaptureService / NotificationCaptureGate; one capture-boundary PR.
- Files (union): NotificationCaptureService.kt, NotificationCaptureGate.kt, NotificationIntakeCoordinator.kt, NotificationTextParts.kt, NotificationFilter.kt
- Blast radius: privacy
- Dependencies: none.
- Verification gate: CA-P-01-001 is VERIFIED; no unverified P0/P1 member.
- Review gate: privacy-security-guardian
- Complexity: M
- Suggested wave: 1

### CL-02 - Notification deferred-work identity
- Class: LOCAL
- Severity: P2
- Findings (1): CA-P-01-002
- Root cause: One REPLACE unique-work name lets a later deferred row cancel an earlier row.
- Fix shape: Derive unique names from durable row identity or schedule replacement recovery, retaining idempotent claims.
- Placement: NotificationIntakeRecoveryScheduler and AppStartupCoordinator.
- Files (union): NotificationIntakeCoordinator.kt, NotificationIntakeRecoveryScheduler.kt, AppStartupCoordinator.kt
- Blast radius: worker
- Dependencies: after CL-01.
- Verification gate: none (P2).
- Review gate: architecture-guardian
- Complexity: S
- Suggested wave: 4

### CL-03 - Notification worker retry and terminal transition
- Class: SYSTEMIC
- Severity: P2
- Findings (2): CA-P-01-003, CA-P-01-005
- Root cause: A zero-row backoff claim is reported as success, while post-claim load/decrypt failures bypass the failure transition.
- Fix shape: Map not-due claims to retry and place payload load/decrypt inside the existing guarded failure transition.
- Placement: Recommendation: NotificationIntakeWorker plus WorkerExecutionGuard; rejected alternative: DAO-only patches because both are guarded worker outcome classification. Evidence: app/src/main/java/com/yourname/expensetracker/worker/NotificationIntakeWorker.kt and domain/workers/WorkerExecutionGuard.kt.
- Files (union): NotificationIntakeWorker.kt, NotificationIntakeDao.kt, NotificationTransientPayloadCrypto.kt, WorkerExecutionGuard.kt
- Blast radius: worker
- Dependencies: after CL-02.
- Verification gate: none (P2).
- Review gate: architecture-guardian
- Complexity: M
- Suggested wave: 4 (block corrected 2026-09-22; wave map was authoritative)

### CL-04 - Review/source-link backfill safety
- Class: LOCAL
- Severity: P3
- Findings (1): CA-P-02-001
- Root cause: The unwired backfill forms a cross-product between notification expenses and pending reviews without an ownership key.
- Fix shape: Keep it unwired and require a validated notification-to-expense identity match before link insertion, or remove the entry point if deferred.
- Placement: SourceLinkBackfillWorker.backfillNotificationLinks.
- Files (union): SourceLinkBackfillWorker.kt, PendingReviewDao.kt, SourceLinkBackfillViewModel.kt
- Blast radius: none
- Dependencies: after CL-01.
- Verification gate: none (P3); coverage-confidence: partial.
- Review gate: architecture-guardian
- Complexity: M
- Suggested wave: 4

### CL-05 - Currency labeling and conversion contract
- Class: SYSTEMIC
- Severity: P2
- Findings (7): CA-P-01-006, CA-P-04-006, CA-P-06-001, CA-E-01-002, CA-E-02-002, CA-E-02-005, CA-E-04-004
- Root cause: Consumers infer home/default currency for ambiguous or foreign amounts, and refresh/selection catalogs diverge from the converter whitelist.
- Fix shape: Establish one supported-currency catalog and explicit source-to-home conversion contract; unknown currency remains typed/unavailable.
- Placement: Recommendation: CurrencyConverter/MultiCurrencyRepository shared path; rejected alternative: per-screen formatting fixes. Evidence: domain/core/money/CurrencyConverter.kt and data/repository/MultiCurrencyRepository.kt.
- Files (union): NotificationMoneySignalDetector.kt, BillReminderManager.kt, BillRemindersScreen.kt, BillRemindersViewModel.kt, BudgetAutopilotEngine.kt, BudgetViewModel.kt, CurrencyRatesRepositoryImpl.kt, CurrencyManagementViewModel.kt, AdvancedAnalyticsEngine.kt, InsightsEngine.kt, RecurringExpenseEngine.kt, TaxEstimator.kt, BusinessExpenseRepository.kt, ExpenseDao.kt
- Blast radius: money-currency
- Dependencies: before CL-14 and CL-28.
- Verification gate: none (P2).
- Review gate: architecture-guardian
- Complexity: L
- Suggested wave: 2

### CL-06 - Documentation and ownership drift
- Class: DOCS-ONLY
- Severity: P3
- Findings (7): CA-P-01-007, CA-P-03-005, CA-E-05-001, CA-E-05-002, CA-I-02-001, CA-I-03-001, CA-I-03-002
- Root cause: Legal-path, fresh-install, and module/inventory docs name retired APIs, wrong owners, or stale counts.
- Fix shape: Update docs to current coordinator/repository/module ownership and current frame/schema facts, without production changes.
- Placement: Owners of LEGAL_PATHS.md and architecture inventory/segments plus RP-10 completion notes.
- Files (union): NotificationTransientPayloadCrypto.kt, ReceiptLifecycleCoordinator.kt, NotificationProcessingPipeline.kt, SubscriptionManagementRepository.kt, SubscriptionManagementViewModel.kt, SubscriptionManagerEngine.kt, AppDatabase.kt, WorkerModule.kt, DiagnosticsModule.kt, RetentionModule.kt
- Blast radius: docs
- Dependencies: after CL-05 and CL-08.
- Verification gate: none (P3).
- Review gate: architecture-guardian
- Complexity: S
- Suggested wave: 4

### CL-07 - Review approval side-effect timing and failure propagation
- Class: SYSTEMIC
- Severity: P1
- Findings (2): CA-P-02-002, CA-E-05-005
- Root cause: Side effects can dispatch before the outer transaction commits, and a planner discards a returned failure before reporting completion.
- Fix shape: Enforce one post-commit boundary and propagate Failure into typed retryable/failed outcomes using the existing runner.
- Placement: Recommendation: PostCommitActionRunner/TransactionSideEffectDispatcher plus ReceiptLinkService; rejected alternative: local launch/status patches. Evidence: ReceiptLinkService.kt and PostCommitActionRunnerImpl.kt.
- Files (union): ReceiptLinkService.kt, ReviewQueueRepository.kt, DefaultExpenseCategoryAssignmentService.kt, ReceiptSideEffectPlanner.kt, AutoCreateWarrantyFromReceiptUseCase.kt, PostCommitActionRunnerImpl.kt
- Blast radius: worker
- Dependencies: after CL-08; before CL-13.
- Verification gate: CA-P-02-002 is UNVERIFIED and must be revalidated before Wave 3.
- Review gate: architecture-guardian
- Complexity: M
- Suggested wave: 3

### CL-08 - Expense lifecycle single-writer and identity preservation
- Class: SYSTEMIC
- Severity: P1
- Findings (2): CA-P-02-003, CA-I-05-001
- Root cause: Strict external dedupe identity can be overwritten on edit, while location clearing bypasses the lifecycle coordinator and event path.
- Fix shape: Route location updates through TransactionLifecycleCoordinator.updateLocation and preserve immutable strict-source keys in coordinator updates.
- Placement: Recommendation: TransactionLifecycleCoordinator single write boundary; rejected alternative: repository/UI-specific fixes. Evidence: TransactionLifecycleCoordinator.kt updateExpense/updateLocation and ExpenseDao.kt.
- Files (union): TransactionLifecycleCoordinator.kt, ExpenseDao.kt, ExpenseRepository.kt, TransactionsViewModel.kt
- Blast radius: none
- Dependencies: after CL-09; before CL-06.
- Verification gate: CA-P-02-003 and CA-I-05-001 are UNVERIFIED and must be revalidated before Wave 3.
- Review gate: architecture-guardian
- Complexity: M
- Suggested wave: 3

### CL-09 - Conditional stale-write protection
- Class: SYSTEMIC
- Severity: P1
- Findings (4): CA-P-03-001, CA-P-04-005, CA-P-09-001, CA-E-05-008
- Root cause: Stale snapshots or unconditional updates can clobber newer lifecycle state.
- Fix shape: Use atomic CAS/still-eligible predicates, check affected-row counts, and reconcile only after a successful claim.
- Placement: Recommendation: cross-segment DAO/coordinator sweep; rejected alternative: retry-only fixes. Evidence: ScannedReceiptDao.claimForAutoMatch, RecurringRuleLifecycleCoordinator, MerchantKeyBackfillWorker, WarrantyReminderDeliveryDao.
- Files (union): ReceiptMatchingWorker.kt, ReceiptMatchLifecycleService.kt, ScannedReceiptDao.kt, RecurringRuleLifecycleCoordinator.kt, ManualRecurringExpenseDao.kt, RecurringOccurrence.kt, MerchantKeyBackfillWorker.kt, ExpenseDao.kt, TransactionLifecycleCoordinator.kt, WarrantyExpirationWorker.kt, WarrantyReminderDeliveryDao.kt, WarrantyDao.kt
- Blast radius: worker
- Dependencies: before CL-08 and CL-13.
- Verification gate: CA-P-03-001, CA-P-04-005, and CA-E-05-008 are UNVERIFIED and must be revalidated before Wave 2; CA-P-09-001 is also unverified.
- Review gate: architecture-guardian
- Complexity: L
- Suggested wave: 2

### CL-10 - Receipt OCR consent boundary
- Class: LOCAL
- Severity: P1
- Findings (1): CA-P-03-002
- Root cause: Local ML Kit OCR is blocked by cloud/image-upload consent even though it does not upload the image.
- Fix shape: Separate local OCR capability from cloud/image-upload policy checks.
- Placement: ReceiptLifecycleCoordinator / ReceiptOcrService using EffectiveCloudAiPolicy.
- Files (union): ReceiptLifecycleCoordinator.kt, EffectiveCloudAiPolicy.kt, ReceiptOcrService.kt
- Blast radius: privacy
- Dependencies: after CL-18.
- Verification gate: CA-P-03-002 is UNVERIFIED and must be revalidated before Wave 3.
- Review gate: privacy-security-guardian
- Complexity: M
- Suggested wave: 3

### CL-11 - Receipt duplicate fingerprint specificity
- Class: LOCAL
- Severity: P1
- Findings (1): CA-P-03-004
- Root cause: Text-only fingerprints omit amount/date semantics and collapse distinct purchases.
- Fix shape: Require amount/date plus semantic agreement before accepting a text-fingerprint duplicate.
- Placement: ReceiptDuplicateDetector called by ReceiptLifecycleCoordinator.
- Files (union): ReceiptDuplicateDetector.kt, ReceiptLifecycleCoordinator.kt
- Blast radius: none
- Dependencies: after CL-05.
- Verification gate: CA-P-03-004 is UNVERIFIED and must be revalidated before Wave 3.
- Review gate: architecture-guardian
- Complexity: M
- Suggested wave: 3

### CL-12 - Reminder delivery state recovery
- Class: LOCAL
- Severity: P2
- Findings (2): CA-P-04-001, CA-P-04-002
- Root cause: SENT blocks snooze/dismiss actions and FAILED_TRANSIENT is never reselected.
- Fix shape: Define reminder transitions so action workers cancel posted notifications and transient failures reselect with bounded retry.
- Placement: RecurringLifecycleCoordinator / BillReminderWorker / RecurringReminderDeliveryDao.
- Files (union): BillReminderWorker.kt, RecurringLifecycleCoordinator.kt, SnoozeReminderActionWorker.kt, RecurringReminderDeliveryDao.kt
- Blast radius: worker
- Dependencies: after CL-09.
- Verification gate: none (P2).
- Review gate: architecture-guardian
- Complexity: M
- Suggested wave: 4

### CL-13 - Recurring rule reconciliation materialization
- Class: LOCAL
- Severity: P2
- Findings (2): CA-P-04-003, CA-P-04-004
- Root cause: Inactive updates still materialize state, and projection receives a clamped date instead of the original anchor.
- Fix shape: Check activity before reconciliation and carry the original anchor through ExpandRequest so keys match.
- Placement: RecurringRuleLifecycleCoordinator / RecurringPlanProjectionService / RecurringOccurrenceExpander.
- Files (union): RecurringRuleLifecycleCoordinator.kt, RecurringPlanProjectionService.kt, SmartBillNegotiationEngine.kt, RecurringLifecycleCoordinator.kt, RecurringOccurrenceExpander.kt
- Blast radius: none
- Dependencies: after CL-09; before CL-06.
- Verification gate: none (P2); CA-P-04-004 is distinct from D7 accepted policy.
- Review gate: architecture-guardian
- Complexity: M
- Suggested wave: 4

### CL-14 - Dashboard window and trend inputs
- Class: LOCAL
- Severity: P2
- Findings (2): CA-P-05-001, CA-P-05-002
- Root cause: Weekly totals use a month-prefiltered slice and newest-first totals invert trend direction.
- Fix shape: Fetch the full week and sort monthly totals chronologically; keep month-scoped cards unchanged.
- Placement: ComputeDashboardWidgetsUseCase and LifestyleInflationDetector input assembly.
- Files (union): ComputeDashboardWidgetsUseCase.kt, HomeScreen.kt, ExpenseDao.kt, LifestyleInflationDetector.kt, LifestyleSavingsPromptUseCase.kt
- Blast radius: ui
- Dependencies: after CL-05.
- Verification gate: none (P2); coverage-confidence: partial.
- Review gate: architecture-guardian
- Complexity: S
- Suggested wave: 4

### CL-15 - Failure-to-unavailable result semantics
- Class: SYSTEMIC
- Severity: P1
- Findings (8): CA-P-03-003, CA-P-05-003, CA-P-05-004, CA-P-06-005, CA-P-07-002, CA-P-07-009, CA-P-12-003, CA-E-01-004
- Root cause: Failures become plausible completion, empty, green, zero, or partial-success results.
- Fix shape: Use typed unavailable/error outcomes, preserve quality metadata, and make required verification queries fail the operation; widgets degrade independently.
- Placement: Recommendation: typed-result contracts at MoneyAggregateBuilder, backup, radar, OCR, and CSV callers; rejected alternative: blanket catch-to-empty. Evidence: MoneyAggregateBuilder.fromBuckets, DatabaseBackupRepositoryImpl, BackupVerifier, ComputeMoneyRadarUseCase, Rfc4180CsvReader.
- Files (union): ReceiptOcrService.kt, BankStatementLifecycleProcessor.kt, ComputeMoneyRadarUseCase.kt, MoneyRadarWidget.kt, ComputeDashboardWidgetsUseCase.kt, HomeViewModel.kt, MonthlySavingsSweepUseCase.kt, FinancialStressForecastEngine.kt, DatabaseBackupRepositoryImpl.kt, DatabaseReadBarrier.kt, BackupVerifier.kt, Rfc4180CsvReader.kt, CsvExpenseImporter.kt, MoneyAggregateBuilder.kt, MoneyAggregate.kt
- Blast radius: none
- Dependencies: after CL-05; before CL-14 and CL-28.
- Verification gate: CA-P-03-003, CA-P-07-002, and CA-P-07-009 are UNVERIFIED P1 findings and must be revalidated before Wave 2; remaining members are unverified P2 claims.
- Review gate: architecture-guardian
- Complexity: L
- Suggested wave: 2

### CL-16 - Budget recommendation safety clamp
- Class: LOCAL
- Severity: P2
- Findings (1): CA-P-06-002
- Root cause: Hierarchy scaling occurs after the 15% safety clamp.
- Fix shape: Apply the final clamp after scaling and test boundary values.
- Placement: BudgetAutopilotEngine.
- Files (union): BudgetAutopilotEngine.kt, BudgetViewModel.kt
- Blast radius: money-currency
- Dependencies: after CL-05.
- Verification gate: none (P2).
- Review gate: architecture-guardian
- Complexity: S
- Suggested wave: 4

### CL-17 - Bounded diagnostics and UI error leakage
- Class: SYSTEMIC
- Severity: P0
- Findings (11): CA-P-04-007, CA-P-06-003, CA-P-06-004, CA-P-07-006, CA-P-07-008, CA-P08-003, CA-E-01-006, CA-E-02-006, CA-E-04-001, CA-I-05-002, CA-I-05-004
- Root cause: Workers, engines, cloud providers, restore/backup, UI state, and group FX paths expose raw exception text, stack traces, or financial payloads.
- Fix shape: Use controlled reason codes and exception classes; use the existing EventMetadataSanitizer.sanitizeExceptionMessage() only for bounded text; remove merchant/amount/currency/date payloads from logs. Quarantine/delete zero-caller example use cases rather than preserve unsafe contracts.
- Placement: Recommendation: shared diagnostics policy centered on domain/diagnostics/EventMetadataSanitizer.kt:204; rejected alternative: ad hoc per-file scrubbing. Evidence: EventMetadataSanitizer, WorkerRunLogger, DefaultCloudPayloadPolicy, and named provider/engine files.
- Files (union): BillReminderWorker.kt, BudgetForecastingEngine.kt, SynthesisEngine.kt, RestoreJournal.kt, DatabaseBackupRepositoryImpl.kt, CloudDashboardBriefingService.kt, CloudReceiptAssistService.kt, CloudQueryInterpretationService.kt, CurrencyConverter.kt, AppStartupCoordinator.kt, TotalsAggregationEngine.kt, InsightsEngine.kt, TransactionsViewModel.kt, ReceiptScanViewModel.kt, SharedExpenseBudgetOffsetEngine.kt, ExpenseUseCases.kt
- Blast radius: privacy
- Dependencies: before CL-06 and after CL-18.
- Verification gate: CA-E-04-001 and CA-P-07-006 are UNVERIFIED P0 findings and must be revalidated before Wave 1; CA-P-06-003 and CA-P08-003 are UNVERIFIED P1s.
- Review gate: privacy-security-guardian
- Complexity: L
- Suggested wave: 1

### CL-18 - Privacy gate fail-closed and cancellation semantics
- Class: SYSTEMIC
- Severity: P1
- Findings (2): CA-P-07-001, CA-P08-001
- Root cause: Backup export treats FailClosed as non-blocking, while CompositePrivacyGate converts CancellationException into a privacy failure.
- Fix shape: Make FailClosed an unconditional execution block and rethrow cancellation before generic conversion.
- Placement: Recommendation: CompositePrivacyGate plus DatabaseBackupRepositoryImpl boundary; rejected alternative: caller-specific enum comparisons. Evidence: CompositePrivacyGate.kt and DatabaseBackupRepositoryImpl.kt.
- Files (union): CompositePrivacyGate.kt, DatabaseBackupRepositoryImpl.kt, PrivacyDecision.kt
- Blast radius: privacy
- Dependencies: before CL-10 and CL-17.
- Verification gate: CA-P-07-001 and CA-P08-001 are UNVERIFIED P1s and must be revalidated before Wave 2.
- Review gate: privacy-security-guardian
- Complexity: M
- Suggested wave: 1 (promoted 2026-09-22; lands before CL-17 — same files)

### CL-19 - Restore/backup fail-closed state machine
- Class: SYSTEMIC
- Severity: P0
- Findings (5): CA-P-07-003, CA-P-07-004, CA-P-07-005, CA-P-10-001, CA-I-01-001
- Root cause: Failed mode/journal durability, corrupt state, rollback scheduling, and a bank status write can proceed after restore barriers.
- Fix shape: Make mode/journal writes durable-or-fail, reject unknown/corrupt state into CRITICAL_RECOVERY_REQUIRED, block non-normal writes including bank status, and reschedule workers on asset rollback.
- Placement: Recommendation: RestoreMaintenanceMode/RestoreJournal/DatabaseWriteBarrier central contract plus BankConnectionLifecycleCoordinator.persistOutcome caller fix; rejected alternative: guarding only the bank path. Evidence: RestoreMaintenanceMode.writeMode, RestoreJournal.writeJournal, DatabaseWriteBarrier.
- Files (union): RestoreMaintenanceMode.kt, DatabaseWriteBarrier.kt, RestoreJournal.kt, AppStartupCoordinator.kt, DatabaseBackupRepositoryImpl.kt, RestoreJournalImporter.kt, BankConnectionLifecycleCoordinator.kt, BankApiIntegration.kt, BankConnectionDao.kt
- Blast radius: worker
- Dependencies: before CL-18, CL-15, and restore-sensitive workers.
- Verification gate: CA-P-07-003, CA-P-07-004, CA-P-07-005 are UNVERIFIED P0s; CA-P-10-001 is UNVERIFIED P1. Revalidate all before Wave 1.
- Review gate: reviewer-strict
- Complexity: L
- Suggested wave: 1

### CL-20 - Backup bundle privacy-mode contract
- Class: LOCAL
- Severity: P2
- Findings (1): CA-P-07-007
- Root cause: REDACT_RAW_TEXT metadata claims image inclusion while the bundle empties receipt files.
- Fix shape: Align mode label/flag, manifest, and actual contents under one tested export contract.
- Placement: DatabaseBackupRepositoryImpl / BackupPrivacyMode / CostbackupBundle.
- Files (union): BackupPrivacyMode.kt, DatabaseBackupRepositoryImpl.kt, CostbackupBundle.kt
- Blast radius: privacy
- Dependencies: after CL-18.
- Verification gate: none (P2).
- Review gate: privacy-security-guardian
- Complexity: S
- Suggested wave: 4

### CL-21 - Cloud provider policy and hybrid routing
- Class: SYSTEMIC
- Severity: P1
- Findings (2): CA-P08-002, CA-P08-004
- Root cause: A provider hand-builds payloads outside CloudPayloadPolicy, and HybridRouter has no production caller while services duplicate routing.
- Fix shape: Route providers through CloudPayloadPolicy.prepare* and wire the existing HybridRouter where its contract applies; update docs after callers are real.
- Placement: Recommendation: CloudPayloadPolicy/HybridRouter provider boundary; rejected alternative: another provider-local router/redactor. Evidence: CloudPayloadPolicy.kt, DefaultCloudPayloadPolicy.kt, HybridRouter.kt.
- Files (union): CloudQueryInterpretationService.kt, CloudDashboardBriefingService.kt, CloudReceiptAssistService.kt, HybridRouter.kt, HybridQueryInterpretationService.kt, EffectiveCloudAiPolicy.kt
- Blast radius: privacy
- Dependencies: after CL-18; before CL-06.
- Verification gate: CA-P08-002 is UNVERIFIED P1 and must be revalidated before Wave 2.
- Review gate: privacy-security-guardian
- Complexity: L
- Suggested wave: 2

### CL-22 - Static guard fail-closed enforcement
- Class: SYSTEMIC
- Severity: P2
- Findings (8): CA-I-04-001, CA-I-04-002, CA-I-04-003, CA-I-04-004, CA-I-04-005, CA-I-04-006, CA-I-04-007, CA-P08-005
- Root cause: Guards accept comments/malformed inputs, omit required modules, collapse occurrence counts, or test markers without executed linkage.
- Fix shape: Fail closed on parsing/discovery, bind markers to executed code, compare occurrence counts, and expand canonical test selection while preserving FG-03/FG-06/FG-07.
- Placement: Recommendation: run_static_guard_suite.py and named verifiers/tests; rejected alternative: baseline/allowlist relaxation. Evidence: all named scripts and CloudProviderPreparedPayloadTest exist at pin.
- Files (union): ci.yml, capture_db_guard_evidence.py, verify_cloud_payload_boundaries.py, verify_privacy_boundaries.py, guard_ratchet.py, verify_event_writers.py, run_static_guard_suite.py, verify_worker_boundaries.py, WorkerGuardArchitectureGuardTest.kt, verify_cancellation_boundaries.py, cancellation_allowlist.yml, verify_allowlist_compliance.py, CloudProviderPreparedPayloadTest.kt
- Blast radius: ci-guard
- Dependencies: after CL-21.
- Verification gate: none (P2); coverage-confidence: partial.
- Review gate: reviewer-strict
- Complexity: L
- Suggested wave: 2

### CL-23 - Worker diagnostics and monotonic drain timing
- Class: SYSTEMIC
- Severity: P2
- Findings (2): CA-P-09-002, CA-P-09-003
- Root cause: Terminal diagnostics can lose the actual failure class, and wall-clock jumps distort drain deadlines.
- Fix shape: Derive fallback error class from durable failure and use monotonic elapsed time for deadlines while retaining wall time for timestamps.
- Placement: Recommendation: WorkerRunLogger/WorkerExecutionGuard and MaintenanceOperationRunner; rejected alternative: individual worker callers. Evidence: WorkerRunLogger.toOutcome, WorkerExecutionGuard, WorkerLeaseRegistryImpl, MaintenanceOperationRunner.
- Files (union): WorkerRunLogger.kt, WorkerExecutionGuard.kt, FileWorkerTerminalDiagnosticSink.kt, WorkerLeaseRegistryImpl.kt, SystemTimeProvider.kt, MaintenanceOperationRunner.kt
- Blast radius: worker
- Dependencies: after CL-19.
- Verification gate: none (P2).
- Review gate: architecture-guardian
- Complexity: M
- Suggested wave: 2

### CL-24 - Email parser currency and total-row correctness
- Class: LOCAL
- Severity: P3
- Findings (2): CA-P-11-001, CA-P-11-002
- Root cause: Conflicting ISO currency signals fall through to trusted-domain defaults, and summary rows can be accepted as totals.
- Fix shape: Reject conflicts as unresolved and apply isSummaryRow exclusion before fallback; keep D2 staged/unwired service intent.
- Placement: EmailReceiptParser and provider parser subclasses.
- Files (union): EmailReceiptParser.kt, AmazonReceiptParser.kt, AppleReceiptParser.kt
- Blast radius: none
- Dependencies: after CL-05.
- Verification gate: none (P3).
- Review gate: architecture-guardian
- Complexity: S
- Suggested wave: 4

### CL-25 - CSV importer provenance validation
- Class: LOCAL
- Severity: P1
- Findings (1): CA-P-12-001
- Root cause: CSV rows lack required batch/row provenance, so the lifecycle rejects imported rows.
- Fix shape: Supply csvImportBatchId and csvRowNumber in each import create request; retain D3 debug-only scope.
- Placement: CsvExpenseImporter / DebugScreen / CreateExpenseSourceLinkRequirements.
- Files (union): CsvExpenseImporter.kt, DebugScreen.kt, CreateExpenseSourceLinkRequirements.kt
- Blast radius: none
- Dependencies: after CL-08; malformed-header failure handling is covered by CL-15.
- Verification gate: CA-P-12-001 is UNVERIFIED P1 and must be revalidated before Wave 3.
- Review gate: architecture-guardian
- Complexity: M
- Suggested wave: 3

### CL-26 - Export result invalidation on format change
- Class: LOCAL
- Severity: P2
- Findings (1): CA-P-12-002
- Root cause: A prior format's bytes remain attached after switching format.
- Fix shape: Clear on format change or store/validate the originating format before Save/Share.
- Placement: ExportOptionsViewModel and ExportOptionsScreen.
- Files (union): ExportOptionsViewModel.kt, ExportOptionsScreen.kt
- Blast radius: ui
- Dependencies: after CL-15.
- Verification gate: none (P2).
- Review gate: architecture-guardian
- Complexity: S
- Suggested wave: 4

### CL-27 - Money rate, key, and aggregate quality
- Class: SYSTEMIC
- Severity: P2
- Findings (3): CA-E-01-001, CA-E-01-003, CA-E-01-005
- Root cause: Provider publication dates are fabricated, month keys vary by locale, and failed source buckets disappear from aggregate counts.
- Fix shape: Persist provider validDate, use locale-independent ASCII keys, and preserve failed buckets/counts in MoneyAggregate quality metadata.
- Placement: Recommendation: CurrencyRatesRepositoryImpl/TimePeriodUtils/MoneyAggregateBuilder shared money core; rejected alternative: consumer-side repairs. Evidence: those exact classes are the existing path.
- Files (union): CurrencyRatesRepositoryImpl.kt, CurrencyConverter.kt, ExchangeRateDao.kt, TimePeriodUtils.kt, MultiCurrencyRepository.kt, BudgetHistorySeriesBuilder.kt, MoneyAggregateBuilder.kt, MoneyNormalizationEngine.kt, MoneyAggregate.kt
- Blast radius: money-currency
- Dependencies: before CL-05 and CL-14.
- Verification gate: none (P2); coverage-confidence: partial.
- Review gate: room-migration-guardian
- Complexity: L
- Suggested wave: 2

### CL-28 - Analytics aggregation and forecast inputs
- Class: LOCAL
- Severity: P2
- Findings (3): CA-E-02-001, CA-E-02-003, CA-E-02-004
- Root cause: Overall budgets filter null-category spend, warning counts use warning objects instead of affected transactions, and forecast points omit recurring obligations.
- Fix shape: Correct overall-scope aggregation, derive counts from affected transactions, and add recurring occurrences/patterns to projected points.
- Placement: BudgetVsActualEngine, AnalyticsCurrencyNormalizer/AnalyticsInputAssembler, ForecastInputAssembler/SynthesisEngine.
- Files (union): BudgetVsActualEngine.kt, BudgetRepository.kt, AnalyticsViewModel.kt, AnalyticsCurrencyNormalizer.kt, AnalyticsInputAssembler.kt, SynthesisEngine.kt, ForecastInputAssembler.kt
- Blast radius: money-currency
- Dependencies: after CL-05 and CL-27.
- Verification gate: none (P2); coverage-confidence: partial.
- Review gate: architecture-guardian
- Complexity: M
- Suggested wave: 4

### CL-29 - Group split and settlement correctness
- Class: LOCAL
- Severity: P0
- Findings (2): CA-E-04-002, CA-E-04-003
- Root cause: Group input validates departed members and silently coerces unequal splits, while repayment application moves balances away from zero.
- Fix shape: Filter/validate active-member splits, reject mismatched unequal payloads, and correct settlement sign application toward zero.
- Placement: SharedExpenseGroupsViewModel/GroupsRepositoryImpl plus GroupBalanceCalculator; one shared-expense correctness PR covers the same surface.
- Files (union): SharedExpenseGroupsScreen.kt, SharedExpenseGroupsViewModel.kt, GroupsRepositoryImpl.kt, GroupBalanceCalculator.kt
- Blast radius: none
- Dependencies: none (CL-05 edge removed 2026-09-22 as over-cautious; re-add only with code evidence of currency coupling)
- Verification gate: CA-E-04-002 is UNVERIFIED P0 and must be revalidated before Wave 1; CA-E-04-003 is P3 and zero-caller at pin.
- Review gate: architecture-guardian
- Complexity: M
- Suggested wave: 1

### CL-30 - Navigation saved-state financial redaction
- Class: LOCAL
- Severity: P2
- Findings (1): CA-I-05-003
- Root cause: Saved navigation tokens persist amount and currency although expenseId is sufficient for recovery.
- Fix shape: Persist only expenseId and reload on restore; update round-trip tests to reject financial fields.
- Placement: NavigationDestination / NavigationController / DestinationPersistencePolicy.
- Files (union): NavigationDestination.kt, NavigationController.kt, DestinationPersistencePolicy.kt
- Blast radius: privacy
- Dependencies: after CL-17.
- Verification gate: none (P2).
- Review gate: privacy-security-guardian
- Complexity: S
- Suggested wave: 4

## Decision register (human required)
- Debt collisions: none. D1-D8, RP-21, and NEW-P1-011 were screened; no ledger row restates them. Near-misses retained as normal work: CA-P-04-004 (new anchor handoff regression; D7 remains accepted), CA-P-10-001 (new restore-barrier regression on the D1-permitted debug bank surface), CA-P-11-001/002 (parser correctness while D2 staging remains intended), and CA-P-12-001/002/003 (debug-only importer correctness under D3).
- Placement approval 1: CL-05 - CurrencyConverter/MultiCurrencyRepository as the money contract; per-screen fixes rejected.
- Placement approval 2: CL-17 - EventMetadataSanitizer plus controlled reason codes; ad hoc redaction rejected.
- Placement approval 3: CL-22 - canonical CI guard suite/verifiers; baseline/allowlist relaxation rejected.
- Decision-register item count: 3 placement decisions, 0 debt-collision decisions.

## Unclustered findings
none

## Non-ledger observations
none

## Completeness audit
- [x] 89 IDs accounted for: clustered 89 + unclustered 0, 89 accounted.
- [x] Pattern 1 raw exception/stack-trace leakage: CONFIRMED as CL-17.
- [x] Pattern 2 swallowed failure -> fabricated/zero success: CONFIRMED as CL-15.
- [x] Pattern 3 restore/backup fail-closed chain: CONFIRMED as CL-19; CA-P-07-006 is with diagnostics because its fix is sanitization.
- [x] Pattern 4 missing CAS/staleness predicate: CONFIRMED as CL-09.
- [x] Pattern 5 currency labeling/conversion drift: SPLIT between CL-05 and CL-27.
- [x] Pattern 6 guard/CI fail-open: CONFIRMED as CL-22.
- [x] Pattern 7 docs/reality drift: CONFIRMED as CL-06; P08-004 is split into CL-21 runtime routing.
- [x] Pattern 8 lifecycle-coordinator bypass: SPLIT between CL-08 and CL-09.
- [x] Dependency graph is acyclic; edges point to earlier contracts/helpers.
- [x] Every cluster passes the PR test.
- [x] Verification gates are marked on every P0/P1 cluster.




