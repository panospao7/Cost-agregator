# CA-2026-09-21 — direct astra cell-audit prompts (no orchestrator)

How to use: open a FRESH codex session (astra, high effort), paste the PREAMBLE plus
ONE cell block. When it finishes, check the report file, run cost_tracker, next cell.
Recommended order: P-02, P-03, P-07, P-08, P-04, P-09, P-01, P-10, P-12, P-11, P-06,
P-05, E-01, E-02, E-03, E-04, E-05, I-02, I-04, I-01, I-03, I-05.
NOTE — byproduct reconciliation: cells P-05, P-06, P-09, E-02, I-01, I-02, I-03, I-05
must ALSO read the "File-to-Cell and Orphan Reconciliation" table at the bottom of
docs/architecture/COVERAGE_MATRIX.md and reconcile every segment named for them there
(build a file inventory for that segment via rg/find; report any file that fits no
cell as an ORPHAN finding). The campaign's all-files guarantee closes through this.

## PREAMBLE (paste with every cell)

You are the direct cell auditor for campaign CA-2026-09-21 (mode: AUDIT, static only).
Governing spec: docs/prompts/master-cross-codebase-audit-campaign-prompt-v3.md — read
§4 (defect classes, finding schema, severity) and §2 (known-debt + intended-behavior
rules) before auditing.

Hard rules:
- Pinned source commit 37601232b9778170c57a656a245b199ab6d7d965. Verify exactly:
  `git rev-parse --short HEAD` returns 37601232 (or an ancestor-compatible tip), AND
  `git diff --stat 37601232..HEAD -- app config scripts` is EMPTY. Docs/ artifacts and
  agent configs may show working-tree drift — that is expected and never a stop
  condition. If production code differs from the pin, STOP and report.
- Read-only toward all production code. No edits outside the campaign folder.
- Audit the cell's files end-to-end against ALL 15 defect classes. Trace the legal
  path from entry to DAO to events to side effects. Known issue IDs listed in the
  cell block are ALREADY TRACKED — never restate them as findings; report only NEW
  issues (or flag a known one as clearly regressed/worse).
- Stance: adversarial reader, not summarizer. For every invariant the architecture
  promises (legal path, barrier, atomicity, post-commit effects, fail-closed privacy),
  hunt for the line that violates it. Report what the code IS, not what the names
  or comments claim. Absence of findings must be earned: cite what you read and
  checked, so "0 findings" is provable effort, not a shortcut.
- Spec before code: first read your cell's row in docs/architecture/COVERAGE_MATRIX.md
  (it names your exact LEGAL_PATHS.md headings and engine rows), then those
  legal-path sections and your segment entry in CODEBASE_SEGMENTS.md. Docs state
  INTENT — the code at the pin is TRUTH; where they diverge, the divergence itself
  is a finding. Do NOT read the large maps (ENGINE_INTERACTION_MAP,
  COMPLETE-BACKEND-MAP, import-graph) wholesale. Traverse the code yourself for
  callers and entry points — docs do not list call sites.
- Context discipline (binding — a prior session died at 243K/258K without writing
  anything): (1) EXTRACT, don't read whole docs — pull only your legal-path
  sections (`rg -n "^## Expense Mutations" -A 60 docs/architecture/LEGAL_PATHS.md`
  style) and only your segment's section of CODEBASE_SEGMENTS.md. (2) Read primary
  coordinator/service/DAO files fully; for package mates, run rg for mutation/IO
  patterns (insert|update|delete|launch|runCatching|withTransaction) and read only
  files with hits — data classes and pure UI need not be read at all. (3) CREATE THE
  REPORT FILE FIRST as a skeleton with provenance + coverage list, and APPEND each
  finding and each covered file as you go — if you die at the context wall, the
  report must already contain everything found so far. (4) Budget: ≤20 full-file
  reads and stop reading at ~150K context — then write the final summary, note
  uncovered areas honestly, and end. (5) If your context is COMPACTED mid-audit:
  treat your own memory of earlier file contents as unreliable — re-read your
  report file's coverage list, continue from the first uncovered item, and re-read
  any file before citing it. Never cite line numbers from post-compaction memory;
  only from a fresh read in the current context.
- Finding format (strict): ID CA-{CELL}-{nnn} | title | defect class 1-15 | severity
  P0-P3 | evidence: file + function + line range AT THE PIN | impact path | caller
  trace (or NONE-FOUND) | existing tests/guards | cross-cell impact | old-ID cross-refs.
- WRITE the full report to:
  "docs/analyses and debug master/campaign CA-2026-09-21/cell-{CELL}-audit.md"
  with a provenance header (date, pinned commit, "direct astra session", cell ID).
- APPEND one line to ".../campaign CA-2026-09-21/JOURNAL.md":
  timestamp | astra-cell-auditor (direct session) | CELL-AUDIT | {CELL} | N findings | report path
- Final chat message ≤20 lines: counts, finding IDs, report path. No builds, no tests.
- If you cannot complete the cell, write what you did cover in the report and say so —
  never narrate a completion that did not happen.

## CELL BLOCKS (one per session)

### P-01 — Notification capture
Files: NotificationCaptureService, NotificationIntakeCoordinator, domain/notification/ (parser registry, dedupe, review queue — 17 files), NotificationIntakeWorker.
Lens: capture gating, dedupe/fingerprint correctness, deferred-intake state machine, raw-payload privacy (transient crypto framing is a known-fixed area — verify, don't re-report). Known: FRESH-P1-001..004; NEW-P1-011 is ACCEPTED DEBT (hash format pinned by migration — never report as a bug).

### P-02 — Transaction lifecycle
Files: domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt, data/database/dao/ExpenseDao.kt, data/backup/DatabaseWriteBarrier.kt (barrier usage), domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt, domain/provenance/ (source-link + lineage integrity — 28 files).
Lens: legal path, atomicity/TOCTOU, dedupe/idempotency, cancellation, post-commit side effects, provenance/source-link integrity. Known: FRESH-P2-001..010, NEW-P2-016.

### P-03 — Receipt, OCR, statement, matching
Files: domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt, data/repository/ReceiptRepository.kt, ReceiptLinkService.kt, ReceiptMatchLifecycleService.kt, service/receiptmatching/ReceiptMatchingWorker.kt, BankStatementLifecycleProcessor (shared with P-10 — usage only).
Lens: draft-first path, atomic create/link, dedupe races, raw-data privacy, worker boundary. Known: FRESH-P3-001..010.

### P-07 — Backup, restore, recovery
Files: data/backup/ (repository, RestoreMaintenanceMode, BackupVerifier, journal/state machine), data/privacy/BackupEncryptionService.kt, DatabaseWriteBarrier.
Lens: fail-closed recovery, resume idempotency, staging cleanup on all failure paths, encryption envelope, worker drain. Known: FRESH-P7-001..011, P7-P1-05 deferred-by-design (do not "fix").

### P-08 — Privacy, retention, cloud AI
Files: PrivacyGate, CloudPayloadPolicy, HybridRouter, DataRetentionWorker, DailyBriefingWorker, cloud providers.
Lens: fail-closed gates, PII at rest/logs, redaction ordering, retention runnable without capability it enforces. Known: FRESH-P8-001..008.

### P-04 — Recurring rules and reminders
Files: RecurringRuleLifecycleCoordinator, RecurringLifecycleEventWriter, occurrence materializer, BillReminderWorker + snooze/dismiss action workers.
Lens: single-writer rule, planned-vs-actual reconciliation, D7 month-end jump is INTENDED BEHAVIOR. Known: FRESH-P4-001..006.

### P-09 — Worker runtime
Files: domain/workers/ (WorkerExecutionGuard, WorkerRegistry, logger, timeout policy), AppStartupCoordinator, DatabaseWriteBarrier interactions.
Lens: guard coverage (incl. NotificationIntakeWorker dynamic path), retry-vs-fail classification, CE propagation, idempotency across retries, notification permission gates posting only. Known: FRESH-P9-001..009.

### P-10 — Bank sync + statement import
Files: BankConnectionLifecycleCoordinator.syncConnection(), BankApiIntegration routes (high/low confidence), PendingReviewDao path — usage of ReceiptLifecycleCoordinator.processBankStatement() (deep audit belongs to P-03).
Lens: token security, cursor/idempotency, confidence routing, D1 debug-gating is INTENDED. Known: FRESH-P10-001..012.

### P-12 — Export, import, accounting
Files: ExportDataRepository, AccountingExportPolicy, CSV/JSON/IIF/PDF exporters, CsvCellSanitizer, ImportCoordinator + importers, BackupEncryptionService (usage only — owner is P-07).
Lens: formula-injection sanitization, snapshot consistency, roundtrip, RFC-4180 edge cases. Known: FRESH-P12-001..010, P12-P0-01, P12-P1-02..08.

### P-11 — Email receipts
Files: EmailReceiptIngestionService, Amazon/Uber/Apple parsers, fingerprints. Unwired-by-design (D2) — audit parsers, wiring status is KNOWN.
Lens: parser correctness (regexes, currency tokens, subtotals), dedupe fingerprints, privacy of raw email. Known: FRESH-P11-001..010.

### P-06 — Budget, forecast, cash flow
Files: budget repository, BudgetAutopilotEngine, BudgetForecastingEngine, MonteCarloSpendingSimulator, FinancialStressForecastEngine, NetCashflowBalanceProvider, SynthesisEngine (usage — owner E-02).
Lens: money math, rounding, partial-data FX basis, autopilot reflection/R8. Known: FRESH-P6-001..015.

### P-05 — Dashboard and synthesis
Files: TotalsAggregationEngine, AnalyticsInputAssembler (usage — owner E-02), dashboard ViewModels, use cases, SynthesisEngine (usage).
Lens: window math, currency-quality gating, percentage completeness. Known: FRESH-P5-001..014.

### E-01 — Currency and time shared surface (OWNER)
Files: domain/core/money/ (MoneyAmount, MoneyAggregate/Builder, MoneyNormalizationEngine, CurrencyConverter, formatter), MultiCurrencyRepository, TimeProvider, PeriodKind/PeriodRange.
Lens: rounding semantics, silent fallback, convertAsOf correctness, timezone/window boundaries. This cell DEEP-AUDITS the shared contract all consumers rely on. No pre-identified issues — everything is new.

### E-02 — Analytics shared surface (OWNER)
Files: AnalyticsInputAssembler, AnalyticsCurrencyNormalizer, TotalsAggregationEngine, DailyBucketEngine, AdvancedAnalyticsEngine, InsightsEngine, BudgetVsActualEngine, SynthesisEngine.
Lens: mixed-currency sums, aggregation completeness, empty-history handling. No pre-identified issues.

### E-03 — Merchant/categorization shared surface (OWNER)
Files: MerchantNormalizer + DAO/entities, CategorizationEngine, HybridExpenseClassifier, SemanticKeywordMatcher, CategoryRepository mutations, TransactionSideEffectPlanner post-commit learning.
Lens: alias/uniqueness handling, silent-ignore inserts, learning write path legality. No pre-identified issues.

### E-04 — Groups, shared expenses, investment, tax
Files: SharedExpenseManager + SharedExpenseDataPortAdapter (domain owners), data/database/GroupTransactionCoordinator.kt, GroupBalanceCalculator, SharedExpenseBudgetOffsetEngine, InvestmentTracker, TaxEstimator, BusinessExpenseReportGenerator. (Note: there is NO GroupLifecycleCoordinator class in this tree — do not go looking for one.)
Lens: member-state correctness (left members!), settlements math, mixed-currency reporting. Known history: E4-NOW-007 archived-groups issue — verify current state. No FRESH IDs pre-identified.

### E-05 — Warranty, subscriptions, offers
Files: WarrantyTrackerRepository, SubscriptionManagerEngine, SmartBillNegotiationEngine, MarketRateProvider, WarrantyTextExtractor, NotificationSubscriptionDetector.
Lens: expiry idempotency, subscription bypass of coordinators (ghost notifications history). No pre-identified issues.

### I-02 — Persistence and schema (OWNER)
Files: AppDatabase.kt, DatabaseMigrations.kt (v145→149 chain), DatabaseSchemaPolicy, entities/DAOs breadth, RoomDomainTransactionRunner, RestrictedExpenseDaoMutation.
Lens: migration ordering/data preservation, index coverage, FK/provenance integrity. ALSO reconcile this cell's file-to-segment inventory (Phase-0 byproduct).

### I-04 — Static guardrails and CI
Files: scripts/ci/, scripts/verify_*, config/guards/, config/baselines/, guard tests.
Lens: guard coverage gaps, evadable patterns (naming bypasses), registry completeness. NOTE: guards run via python — you MAY read scripts but NOT execute repo validation. Also reconcile file inventory (byproduct).

### I-01 — Worker/backup integration
Files: di worker modules, scheduler wiring, BackupEncryptionService bindings, restore resume path integration, WorkerRegistry consumers.
Lens: DI binding completeness, singleton lifetimes, restore-drain wiring. Also reconcile file inventory.

### I-03 — Dependency injection breadth
Files: di/ modules (~37), MainApplication, VIEWMODEL_INJECTION_MAP.md cross-check.
Lens: missing bindings for interfaces, scope errors, dead modules. Also reconcile file inventory.

### I-05 — UI/ViewModel mutation and insight pipelines
Files: the 40 ViewModels (see docs/architecture/route-viewmodel-map.md), ui/navigation/, domain/usecase/ (17 files), mutation coordinators usage, read-side assemblers. PLUS T3-light pass over ui/screens/ (84), ui/components/ (60), ui/util, ui/mappers: only check for direct Repository/DAO misuse from composables, hardcoded sensitive values, and sensitive data in navigation args/logging — do not deep-review layout/visual code.
Lens: ViewModel bypass of coordinators, StateFlow conflation, direct DAO reads for display (legal?), insight pipelines, use-case layer legality. Also reconcile file inventory.

## VERIFIER PASS (after ~7 cells; fresh session, gpt-5.5 if available, else astra)

Paste: "You are the adversarial verifier for campaign CA-2026-09-21. For every finding
in the listed cell reports: try to REFUTE it. Re-read the cited file at the pinned
commit, re-derive the caller trace, check tests/guards, calibrate severity vs the §4
rubric, and confirm it is not known-debt or intended behavior (§2). Verdicts: CONFIRMED
/ REFUTED(reason) / RECALIBRATED(severity) / NEEDS_RUNTIME(test). Append your verdicts
to cell reports or a verification-{date}.md artifact + one JOURNAL line. Reports:
[paste the file paths of the cell reports to verify]."
