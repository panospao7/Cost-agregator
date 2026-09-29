# Wave-2 closure review — 2026-09-29

Status: BLOCKED (commit not made). Author: Claude Code session (single agent). Not an independent review of its own repairs.

## Validated state
- HEAD a4807632, worktree fingerprint 5df333f2af86 (uniform start==end across all runs).
- Packet `build/validation-runs/w2-final-20260929-172558-e37bb6c3/`: compile + 20 targeted filters, 21/21 PASS, 288 test cases.
  Deviation (human, disclosed): `-AllowOverlap -OverlapReasonCode HUMAN_REQUEST`.
- known_good_state row 7 stamp refreshed as last write before static-guards.
- static-guards vr-20260929-145829-a43efe80: FAIL, 24/25, infra_errors 0. Sole blocker: raw_money_aggregates (84 violations).
- Post-validation delta: this file only (docs-only).

## Gate blockers
1. raw_money_aggregates FAIL. All 84 violations inherited; the 6 in Wave-2-touched files
   (FinancialStressForecastEngine 449/520/844, ComputeDashboardWidgetsUseCase 234/816, ComputeMoneyRadarUseCase 291)
   are byte-identical to HEAD. Still a failing gate under FG-03; needs human disposition (fix or waiver). No baseline was grown.
2. db_ownership_policy.yml no-broadening (FG-06/07), HEAD 412 -> 423 rows (35 identities added, 25 removed).
   - Human-approved this session: 4 rows (RP-03 x2, RP-17 x2), incl. new owner->DAO grants
     AppStartupCoordinator -> ScannedReceiptDao.updateImagePathIfUnchanged and BankSyncStartupRecovery -> markStaleFailed.
   - No approval record found: new owner->DAO grant
     BankConnectionLifecycleCoordinator.disconnectConnection -> PendingReviewDao.deleteByBankConnectionScope (MIT-DB-08P2).
     `wave2-db-source-evidence-adjudication-20260927.md:58` requires a separate exact review;
     `wave2-db-ownership-reconciliation-20260927-handoff.md` is IMPLEMENTED_UNVERIFIED, approvals pending.
   - No approval record found: ~20 NEW-OP narrow grants on existing owner+DAO pairs (CAS/markX/clearX ops in
     NotificationIntake, ReceiptLinkService, ReceiptMatchLifecycleService, RecurringRuleLifecycleCoordinator,
     BankConnectionLifecycleCoordinator, BankApiIntegration, SubscriptionManagementRepository, WarrantyExpirationWorker).
   - Same-op moves and removed generic rows are narrowing, not broadening.
3. Independent strict/guardian review of this session's repairs: not done (author cannot self-approve).
4. Device/androidTest gates: not run.

## Repairs made this session (uncommitted)
- CE rethrow before broad catches: CloudWarrantyExtractionService, OnDeviceQueryInterpretationService,
  DataStoreMaintenanceSafeDiagnosticSink (x2), AccountingExportRepository, WorkerSpecScheduler (x2).
- RestoreMaintenanceMode: MonotonicTimeProvider injection; awaitCancellable propagates CancellationException via continuation.cancel.
- cancellation_allowlist.yml: 24 exact-symbol entries (file-wide conversion, approved); cancellation.json rebased with occurrence_counts (FG-06 approved "option 1"), 5 stale fingerprints dropped.
- event_writers.json format rebaseline; known_good_state repinned to 21 (approved); db_guard/scanner.py listOf shadow fix.
- JOURNAL.md line 125 appended correction of line 121 (lines 1-124 unchanged).
- Known untouched risk: AccountingExportRepository surfaces e.message in ExportResult.errorMessage (pre-existing privacy debt).

## Commit manifest (draft, nothing staged)
236 dirty paths. Wave-2 candidates: app/src/main (69), app/src/test (55), app/src/androidTest (1),
scripts/** guards+tests, config/guards, config/baselines, scripts/allowlists, campaign wave2 docs + JOURNAL.md,
workflows/active wave-2 records.
Exclude as unrelated unless the human says otherwise: `.codex/agents/orchestrator.toml`, `.codex/agents/explorer-lite.toml`,
`docs/prompts/master-cross-codebase-audit-campaign-prompt-v3.md`, `docs/architecture/COVERAGE_MATRIX.md`,
`docs/analyses and debug master/CL-29-wave1-implementation-report.md` (Wave-1).
Ambiguous, needs human call: `.github/workflows/ci.yml` (adds required `evidence_base_ref` dispatch input + fetch-depth 0 for guard evidence).

## Decisions required before commit
a. Approve or reject the unrecorded policy grants in item 2 (FG-06/07).
b. raw_money: fix, or record an explicit human waiver for this commit (result stays 24/25, not GREEN).
c. Include/exclude ci.yml.
d. Confirm commit proceeds without independent review and device gates (recorded as pending).

## Human decisions (2026-09-29)
- a: APPROVED (FG-06/07) — all item-2 policy grants incl. deleteByBankConnectionScope and the NEW-OP narrow grants.
- c: ci.yml INCLUDED.
- b: human asked to fix raw_money. Scope finding: 84 sites across 32 production files (sumOf effectiveAmount/normalizedAmount/amount/price,
  `total: Double` fields/params, `var total = 0.0`). Guard has never been green since GR-00 (88 inherited then).
  Per-site semantics differ (some sum raw-currency Expense.effectiveAmount, others already-normalized snapshots), so the fix is a
  CRITICAL-blast-radius money sweep, not a mechanical edit.
- b (final): human WAIVER for this commit — raw_money_aggregates stays FAIL (84 inherited, 24/25, NOT GREEN).
  Follow-up: separate batched money campaign, starting with sites summing raw multi-currency Expense.effectiveAmount.
- d: human approved committing with independent review and device gates still PENDING.
- Commit excludes: .codex/agents/orchestrator.toml, .codex/agents/explorer-lite.toml,
  docs/prompts/master-cross-codebase-audit-campaign-prompt-v3.md, docs/architecture/COVERAGE_MATRIX.md,
  docs/analyses and debug master/CL-29-wave1-implementation-report.md.
