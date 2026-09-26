# Test-Diagnosis Report — 2026-09-19

## Handoff Summary

Status:
- PARTIAL / UNKNOWN: source, persisted logs, schemas, and git history were inspected; no execution gates were run.

Changed files:
- This report only: `workflows/active/handoff-test-diagnosis-20260919.md`.
- The diagnosis itself made no production or test changes.

Validation:
- command: none
- result: NOT RUN
- Builds, tests, Gradle, lint, guards, and validation were explicitly not run.

Review/guardian gates:
- Architecture: NOT RUN
- Privacy/security: NOT RUN
- Room/migration: NOT RUN
- Strict review: NOT RUN

## Decision Menu

| Class | Verdict | Evidence | Consequence / recommended action | Risk if wrong |
|---|---|---|---|---|
| MoneyTest (3) | STALE-TEST | `Money.kt:83-93` rounds each division (`eaafa1bb4`); `SplitCalculator.kt:112-120` distributes remainder cents (`50b0e920b`). | Test `SplitCalculator` for allocation conservation. Use numeric comparison instead of scale-sensitive `BigDecimal.equals`; `100.0` and `100.00` are numerically equal. | A caller could still be incorrectly using `Money.divide()` as an allocator. |
| GoldenMasterVerificationTest (4) | STALE-TEST | `TotalsAggregationEngine.kt:79-90` is explicitly PURCHASE-only (`afdf86b2f`). The test adds a €3,000 deposit but expects it in spending totals; divergence tests also lack current currency stubs. | Expect €738.49 purchase spend, not €3,738.49; update daily-average expectation and normalized/home-currency mocks. | Changing production could inflate spending with income. |
| DatabaseBackupRepositoryImplTest (6) | STALE-TEST | Validation rejects invalid schema/indexes at `DatabaseBackupRepositoryImpl.kt:1993-2006` and `2301-2328`. Import sanitizes the public error to `SOURCE_VALIDATION_FAILED` at `1670-1680` (`92a6ebf7`). `e48aed43` documents other failures as legacy file-backed SQLite/Robolectric harness failures. | Assert the controlled failure code, not internal validator text; move file-swap tests to a supported integration harness. | Over-relaxed assertions could hide validator regressions. |
| CrossGroupIntegrationTest (2-3) | STALE-TEST | Current normalized forecast/dashboard APIs came with `9a6afc43`/`87eeacadc`; failures show missing home-currency and `assembleNormalized` stubs. | Update test setup for normalized inputs and current parity contracts. | Test repair could mask conversion failures if normalization is not asserted. |
| AnalyticsPipelineTest (3) | STALE-TEST | Actual pace `2049.6993` matches `991.79 * 31 / 15`; anomaly logic requires sufficient historical merchant data (`InsightsEngine.kt:628-676`). | Expect approximately `2049.70`; provide valid historical outlier fixtures and current DAO stubs. | An analytics formula regression could be hidden. |
| TaxEstimatorTest (1) | STALE-TEST | Cumulative progressive brackets are implemented at `TaxEstimator.kt:190-217` (`cfd26961`); Greece rates are `TaxConfiguration.kt:41-44`. €35,800 taxable income yields €8,156. | Update the expected value and assert bracket decomposition. | Tax estimates could be understated by €56. |
| ComputeMoneyRadarUseCaseTest (1) | STALE-TEST | The large-bill bonus requires `amount > 50%` of income at `ComputeMoneyRadarUseCase.kt:324-330` (`27d85092a`). €120 is not large against €1,000 income; score 36 is correct. | Change expected score 51 to 36, or use a genuinely large bill fixture. | Production scoring could overstate urgency. |
| CalculateFinancialForecastUseCaseTest (1) | REAL-PROD-BUG | `CalculateFinancialForecastUseCase.kt:61-68`, especially line 63, passes `manualRecurringEntities = emptyList()` (`28e47b0c`). `ForecastInputAssembler.kt:217-236` gives manual rules precedence. | User-entered recurring amounts can disappear and be replaced by detected values. Pass the loaded manual entities at `CalculateFinancialForecastUseCase.kt:63`. | Forecasts remain wrong for recurring bills. |
| PrivacyStorageContractTest (1) | STALE-TEST | The test requires syntactic exhaustive `when` blocks at `PrivacyStorageContractTest.kt:16-37`. The active exporter is semantically fail-closed at `ReceiptDebugExporter.kt:53-61`; legacy output is redacted by default at `ReceiptRepository.kt:751-798`. | Test the semantic rule “only `STORE_RAW` permits raw output,” or exempt deprecated wrappers from the syntactic scan. | A future raw-output path could bypass a weaker legacy guard. |
| MigrationRegistrationTest 1-of-5 | NEEDS-HUMAN | `DatabaseMigrations.kt:74` registers only 145→146→147→148. The 119→121 SQL is at `AppDatabase.kt:7628-7671`; schema 121 contains the diagnostic table and indexes. Commits: `c346cf3b`, `5952d386`, `dbfb3170`. | The combined 119→121 path omits `pipeline_diagnostic_events`, `index_pipeline_diagnostic_events_pipeline_stage`, and `index_pipeline_diagnostic_events_timestamp`. The other 59 tables/201 indexes are inherited. Decide whether retired pre-v145 upgrades are supported. | A supported historical upgrade can drop diagnostic data; resurrecting retired migrations conflicts with the v145 baseline. |
| PrivacyCapabilityHandlingPolicyTest (2) + UPR5CompletionTest (1) | REAL-PROD-BUG | `EffectiveCloudAiPolicy.kt:29-33` maps OCR and fails closed for unknown capabilities (`2e6b5f14`). But `CloudAiPrivacyGate.kt:26-38` groups OCR with generic cloud AI and checks only `cloudAllowed`; it ignores receipt-upload permission/redaction. | OCR can be allowed while receipt-image upload is forbidden. Enforce the OCR-specific policy in `CloudAiPrivacyGate.kt:27-38`; separately add OCR as `GATE_HANDLED` in the test map. | Receipt images/OCR may reach cloud processing against user policy. |
| DbGuardPolicyFixtureTest (19) | NEEDS-HUMAN | Logs report ownership `471→407`, structural `62→64`, and tuples `58→60`. Drift includes DAO aliases, CategoryRepository 2→7, unresolved `userCorrectionDao` entries, missing `GroupTransactionCoordinator.createGroupWithMembers`, missing `AiChatRepositoryImpl.createSession`, and `AppDatabase/MIGRATION_16_17`. Intentional dead-writer removals are documented by `42f464f0`, `7815d0d4`, `335758ff`, `7a07d184`, and `0494cac6`. | Do not regenerate. Obtain FG-06/FG-07 human approval; the count decrease is explained by intentional removals, while the structural increase and aliases require policy reconciliation. | Blind regeneration could weaken database-access guards. |
| BankStatementParserTest (15) | REAL-PROD-BUG | Multi-row parsing is intentional at `BankStatementParser.kt:128-204` (`469596cd`). `b35a974f` introduced the parser-priority loop; generic parsing adds at `194-198` without stopping, allowing duplicates. Header detection runs after filtering at `149-154`, causing date drift. | A single row can import twice and use the wrong date. Stop after generic success and preserve header rows for date-column detection. | Duplicate financial transactions and wrong dates can enter the ledger. |
| MigrationRegistrationTest 4-of-5 | STALE-TEST | Active history is only 145→146→147→148 (`DatabaseMigrations.kt:74`, baseline commit `27ca3bd8`). Required active schemas exist. Missing pre-baseline snapshots are `54,55,58,61,62,63,66,97,98,99,118,128,139`; none are missing from the active matrix. `147.json` has a separate UTF-16LE encoding risk (`b422240d`). | Rewrite the test matrix around the v145 baseline; track the v147 encoding issue separately. | Obsolete assertions obscure the supported upgrade path; encoding risk may break schema tooling. |

## Decide These First

1. **Migration 119→121:** decide whether pre-v145 upgrades are supported; if yes, the omitted diagnostic table/indexes are data-loss relevant.
2. **OCR privacy:** fix the fail-open `CloudAiPrivacyGate` path before a privacy release.
3. **Money:** confirm all allocation paths use `SplitCalculator`, then correct scale-sensitive tests.
4. **DB guard fixture:** obtain explicit FG-06/FG-07 approval before baseline regeneration.
5. **Bank parser:** prevent duplicate imports and date drift.
6. **Financial forecast:** restore manual recurring rules to the assembled forecast input.

## Remaining Risks

- These conclusions are source/history/log diagnoses, not independently validated runtime results.
- No production fix or test update was applied in this report.
- Pre-existing worktree changes were intentionally left untouched.

## rp-27 test-repair checkpoint - 2026-09-26

The decision menu above is historical source-analysis context, not the current failure inventory. Resume the test-repair lane from `docs/testing/generated/UNIT-TESTS-FAILURES-2026-09-26.md`, especially `Final remaining-inventory repairs - 2026-09-26` and the following consolidated commands; `docs/testing/generated/TRIAGE-2026-09-test-recovery.md` mirrors the checkpoint.

- Inspected HEAD: `2544298d`. Completed-suite baseline: `vr-20260926-181440-25aa269d`, 7,130 completed / 75 failed / 295 skipped; terminal FAIL, no timeout. No newer validation was run by this repair work.
- Three follow-ups have candidate repairs for 71 of those 75 cases in 32 classes, all NOT RUN. Latest pass: 15 test files / 27 baseline cases, plus non-vacuity and scanner/dedup controls. This is not a forecast of four runtime failures.
- Four cases remain: AM/AMAZON parser row loss; currency-prefix grouped amount rejection; duplicate lifecycle event double-write; ownership policy count 406-to-412 requiring explicit FG-06/FG-07 reconciliation (six RP-14 entries identified in `fea8cdfe`).
- Production Kotlin was unchanged during the latest pass. Earlier production/worktree edits remain; no commit was made. No tests were deleted/ignored and no baselines/allowlists were expanded.
- User owns validation; no subagents, builds/tests/guards or commits. Use the documented serialized runner commands, no -Raw, no overlapping validation. Independent strict/privacy/guardian review and all fresh validation remain pending.

## rp-27 sweep checkpoint and latest-rate fixture repair - 2026-09-26

Supersedes the earlier all-NOT-RUN status: durable results show compile plus seven classes PASS (17 original failing cases covered), then MultiCurrencyAnalyticsTest FAIL (3 passed / 1 failed) in `vr-20260926-200442-9dbc1093`. Remaining 29 filters were not executed in that sweep; privacy-denial-specific classes are still queued.

The failed test stubbed getRate while real latest-basis conversion called getLatestRateForPair. That single test now uses strict current-port stubs plus an independent EUR 140 and single JPY MISSING_RATE control. Its exact JPY-only repository message was not weakened. This new correction is NOT RUN; no production edit, coder validation, subagent or commit.

Resume from `docs/testing/generated/UNIT-TESTS-FAILURES-2026-09-26.md`, section `MultiCurrency latest-lookup correction and sweep resume - 2026-09-26` and the consolidated commands immediately after it. The block retries filter 8, then original filters 9-37 (30 serial class invocations total). Preserve stop-on-fail, no -Raw, no overlapping validation and the separate four production/policy blockers. Full-suite baseline remains the 75 failed events from `vr-20260926-181440-25aa269d` until a newer completed suite is inspected.
