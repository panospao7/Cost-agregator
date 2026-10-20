# B1R / B1-3 / B1-4 / B1-5 map (scout 2026-10-19 on HEAD 945afffc; user approved "your recommendations")

T = app/src/test/java/com/yourname/expensetracker, M = app/src/main/java/com/yourname/expensetracker.

## B1R-4: baseline tightening (approved)
- In config/baselines/cancellation.json, delete the G-CANCEL-01 CompositeOperationRunRecorder fingerprint (:16) **and** its occurrence_counts key (:64). Both are required, because load_baseline demands equal key sets.
- Keep the G-CANCEL-02 CompositeOperationRunRecorder keys (:40/:88, count 3), since source still has 3 NonCancellable runCatching (:59/:64/:69).
- Nothing to change in scripts/allowlists/cancellation_allowlist.yml or the Kotlin KNOWN_VIOLATIONS (:117 is a different scan, so keep it).
- Hand-edit only. Do not run generate_baselines.py, which would regenerate all 5 baselines; `--update-baseline` is rejected in ci-mode.
- Do not hand-edit docs/ci/GUARD_STATUS.generated.md (evidence-pinned).
- docs/ci/CI_GUARDRAILS_BASELINE.md:25 says "decreases are automatically promoted", which is stale wording. Note it only.

## B1-4: premise is stale, no production change
MerchantKeyBackfillWorker (:70-77, :99-115) and WarrantyExpirationWorker (:85-155) already rethrow CE before their broad catches. The SourceScanning DIRECT_DAO_IN_WORKER allowlist (:45-94) is about DAO usage, not CE, and all 6 entries are still live.

The remaining item is test-only: add CE propagation tests to MerchantKeyBackfillWorkerTest, for CE thrown by updateMerchantKey and by getExpensesWithNullMerchantKey. In both cases CE must propagate, not count as an error and not land in the failed set. Model on WarrantyExpirationWorkerTest:377.

## B1-3: one stale entry
- Delete TransactionContextProvenanceGuardTest allowlist entry `DomainTransactionRunner.kt` (:36-41). The source has no `TransactionContext(` match.
- Optionally add `every allowlist entry still matches the pattern`. The other 5 entries are live.

## B1-5: real clock + 3 runCatching fixes (approved; production, T3 + privacy-security-guardian)
Production fixes:
- NetCashflowBalanceProvider :21-26 → `CancellationSafe.runCatchingCancellable { … }.getOrDefault(0.0)`. This resolves G-CANCEL-02 ×2 (baseline :42/:90).
- SubscriptionManagerEngine :624-625 → runCatchingCancellable plus the same ISE mapping for non-CE failures. This resolves G-CANCEL-02 ×1 (baseline :44/:92).
- DefaultCloudPayloadPolicy :91 (blocking readBytes, not suspend) → `try { readBytes } catch (IOException) { null }`, or runCatchingCancellable().getOrNull(). It must keep the fail-closed null. No baseline entry.

Guard edits:
- CancellationSafetyArchitectureGuardTest :415/:441 → `guardToday()` on the real clock.
- Rewrite the negative controls (:424-432, :451-459) to use synthetic entries.
- Delete the raw allowlist entries :198/:206/:208 once the sites are fixed.

**Open decision (allowlist change).** With the real clock, 12 RAW_RUN_CATCHING_ALLOWLIST entries dated 2026-10-01 have already expired. They are hidden today only by the frozen 2026-09-15 clock. After the 3 fixes, 9 remain: RestoreJournalImporter, AnalyticsRepository, DatabaseBackupRepositoryImpl, ValidateBankStatementTransactionsUseCase, AdvancedAnalyticsEngine, CarbonFootprintCalculator, DiagnosticsRepository, FinancialStressForecastEngine, InvestmentTracker. They need one of these:
- (a) renew to an explicit date such as 2027-03-31;
- (b) fix the production code;
- (c) remove them if they are already stale. Verify each first.

Separately, about 77 KNOWN_VIOLATIONS entries and the RestoreDiagnosticsSink/HistoricalSpendingDistribution entries expire 2026-12-31.

Baseline: the B1-5 removals (NetCashflow, SubscriptionManagerEngine) go in the same baseline pass as B1R-4.

Tests to add:
- NetCashflowBalanceProviderTest (new): CE rethrown; a non-CE failure gives 0.0.
- SubscriptionManagerEngine: CE in homeCurrency is rethrown, not turned into ISE.
- DefaultCloudPayloadPolicy: an unreadable file gives no image and the payload is still prepared.

## Order
1. B3-1b (running).
2. Batch 1: B3b-R2 (ignore baseline 3→0 + docs), B1-3 entry, B1-4 MerchantKey CE tests.
3. B1-5 (production + guard + baseline incl. B1R-4), after the user decides on the 9 expired entries.
4. One static-guards run.
