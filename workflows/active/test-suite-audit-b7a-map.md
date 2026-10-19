# B7-A map + decision (scout 2026-10-19; orchestrator decision)

## B7-3 (DO): MoneyBoundaryGuardTest.kt (11 tests, all shell out to scripts/verify_money_boundaries.py) → scripts/test_verify_money_boundaries.py
- Case 1 (missing manifest): add `assert "DB_SOURCE_ROOT_UNDECLARED" in out.err` to test_missing_manifest_fails_closed.
- Case 2 (XXX): already covered (rule not financial_only); optionally add a financial-path row.
- Cases 3-7 → parametrized test_violation_fixture_fires_rule: CurrencyCode("") G-MONEY-11; displayCurrency "N/A" G-MONEY-11 (financial path); raw ExpenseSnapshot G-MONEY-10 (financial path); dashboard Unavailable -> emptyList() G-MONEY-21 (file domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt — filename load-bearing); convertMultiple G-MONEY-17 (financial path). Assert code == 1 and f"[{rule}]" in out.out. Kotlin bodies copied verbatim.
- Cases 8-10 → parametrized test_clean_fixture_passes: MoneyAggregateResult.Unavailable (domain/core/money/Good.kt), NormalizedForecastInput multi-line literal (forecasting/Good.kt, verbatim from Kotlin :203-212), StaleRatePolicy.forBasis (forecasting) → code == 0 and "PASS" in out.out.
- Case 11 (live repo scan) → covered by registered-guard money (ratchet, empty baseline).
- Then delete app/src/test/.../guard/MoneyBoundaryGuardTest.kt; remove docs/ci/CI_GUARDRAILS_BASELINE.md:232 row and recount the :234 "Total" (verify the real count).
## B7-1 (NARROWED)
- MoneyContractTest: KEEP. Python G-MONEY-RAW-02 only matches `.sumOf { it.effectiveAmount }`; the Kotlin test also covers the `+= x.effectiveAmount` form → deleting would lose coverage; extending the Python verifier is a guard change needing user approval → report as an option.
- RecurringDeactivateContractTest: WAIT (scans release-changed RecurringRuleLifecycleCoordinator.kt; its writeBarrier/reminder-suppression substring rules have no surviving scanner; its "cancel" requirement conflicts with RecurringArchitectureGuardTest :523/:711 "delete not cancel") → resolve parity after the merge.
- PrivacyStorageContractTest T3 (`RawContentSanitizer returns empty for DO_NOT_STORE`, :61-70; substring check that can't meaningfully fail, A6:66): DELETE; T1/T2 stay; behavioural proof kept in PrivacyDoNotStoreTest / PrivacyGateEnforcementGoldenTest / BankPrivacyModeArchitectureGuardTest. Class is a closing-gate extra → still runs (3 → 2 tests).
- ARCHITECTURE.md:171 changelog line: not edited (release-changed).
## Validation
targeted-unit-test com.yourname.expensetracker.contracts.PrivacyStorageContractTest,com.yourname.expensetracker.contracts.MoneyContractTest (+ guard.* survivors e.g. DbGuardPolicyFixtureTest sanity); pytest-subset scripts/test_verify_money_boundaries.py; registered-guard money; registered-guard guard_docs_truth (doc edited). reviewer-strict.
