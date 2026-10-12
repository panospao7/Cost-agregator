# Wave 5 runtime follow-up — October 6, 2026

## Scope and status

October 7 correction: this document is dated history. The reviewed `8454ae...` tree has genuine compile, ten targeted-filter, unit-migration, and 25/25 static PASS evidence; that narrow evidence does not refresh the earlier 62-filter family. Independent source review returned FAIL. The authoritative active remediation is `workflows/active/money-w5-focused-review-20261007-handoff.md`; final implementation review and refreshed validation remain pending.

Local implementation and source review; validation pending on the post-fix fingerprint. No subagents, staging, commit, push, baseline change, or guard-policy change is part of this follow-up. Earlier PASS artifacts remain historical evidence, not proof for this tree.

The previously authorized cancellation baseline reconciliation is retained. Its difference from HEAD is not a new authorization request. The line-ending-only DB ownership policy dirtiness and unrelated Codex/campaign files remain outside the candidate commit.

## Repairs

- ForecastInputAssembler uses the correct explicit currency for its typed aggregates. Its public cumulative-spend and pace helpers require a supported explicit target and reject mixed/unconverted snapshots through SingleCurrencyAnalyticsInput. Tests cover mixed sources and invalid targets without changing the expected available-value assertions.
- Money Radar captures one supported home currency for a computation. Actual spending and income normalize at transaction-date rates; budget limits use PERIOD_END and upcoming bills use FORECAST_DATE. The simulator receives the same explicit target currency. Missing/invalid money causes UNAVAILABLE, not healthy zero or absent risk. Bill amounts and the income comparison use the same currency.
- Radar fixture repairs provide real DEPOSIT rows to the income normalization path instead of stubbing a removed raw sum. A mixed EUR/USD regression checks both the converted spending and the non-EUR simulation target.
- The validation runner publishes JSON using atomic replacement with bounded sharing-violation retries. Reads retry transient publication/sharing failures but persistently missing or invalid records remain fail-closed. Initial worker publication is inside the guarded execution boundary. Wait reconciles a dead owner without requiring another Start, including when its lock is missing. Orphan recovery records INFRA_FAILURE/exit 2, completion marker, end fingerprint and log files, and removes only that run's abandoned JSON temporary files. Missing records and surviving children remain fail-closed.
- Registered guard subprocess output is UTF-8. A legacy Windows console cannot turn a guard verdict into a UnicodeEncodeError. Text-only and binary-buffer consoles have regression coverage; child exit codes and guard configuration remain unchanged.
- Runner protocol tests exercise repeated concurrent JSON reads and a STARTING record whose worker died, including a missing-lock case. A live worker without a lock remains nonterminal; recovery does not invent a successful result. These tests use isolated fixture roots, not the real validation lock or live Gradle processes.
- Runner duplicate detection now requires the same profile and exact resolved command, as well as a terminal PASS, exit zero, completion marker and matching start/end fingerprints. Different test filters and registered guards are independent gates; the global live-run lock remains unchanged.
- Receipt totals, subtotal and tax now use nullable source-currency MoneyAmount values. Persistence projects scalar fields only at its storage boundary; processing rejects unresolved/unsupported money before merchant/category work and propagates cancellation. Quick-save cannot relabel a tax-inclusive parsed total after the user changes currency. Line items retain raw OCR syntax for unresolved review, but their monetary comparisons and sums use the canonical builder.
- Analytics period/category/year models and totals-summary values retain typed money. Period and display-currency invariants reject relabeling. Historical average/status baselines reject missing currency, mixed currencies and partial conversions rather than turning an incomplete total into a complete comparison.

The orphaned temporary files are evidence of interrupted publication. The historical crashes have no stack trace, so a Windows sharing race is a diagnosis supported by the writer shape, not a proven attribution for both incidents. The new tests must verify recovery and atomic visibility independently of that hypothesis.

## Local review disposition

- Tax allocation and persistence already enforce decimal item-share conservation and category-plus-unallocated conservation, with dedicated allocator/persistence regressions. Retain these tests in the closing queue.
- Nullable analytics assertions fail on null rather than substituting zero; unique fixture IDs prevent accidental transaction-count/provenance collisions. These changes strengthen available-state assertions, not waive unavailable-state coverage.
- Production analytics rendering branches on unavailable money. The existing non-null home-currency access is inside its null/blank guard. No newly introduced raw exception message, receipt payload, direct DAO write, schema change or cancellation suppression was found in this follow-up.
- FG-03/FG-06/FG-07/FG-23 in FINAL_CI_GUARD_ACCEPTANCE_GATE.md remain unchanged. No assertion, baseline, allowlist, registry entry or guard finding is relaxed to obtain a PASS.

## Raw-money closure pending verification

Registered guard run vr-20261006-022602-f3f74f04 completed FAIL/1 with 15 findings. Later source migrations reduced the last inspected report to three ReceiptParser declarations (vr-20261006-104815-00f513c3). Both reports precede the current typed receipt changes and are historical inventory, not current-tree evidence. Rerun the immutable guard after the source freezes.

The follow-up now includes these formerly deferred boundaries; their tests and strict local review are required before closure:

| Owner / follow-up | Files | Boundary |
| --- | --- | ---: |
| M-2 typed analytics model migration (Segment 8) | AdvancedAnalyticsModels, AnalyticsModels, DailyBucketEngine, TotalsAggregationEngine | Typed results and currency-safe comparisons |
| Forecast typed internal result migration (Segment 1) | FinancialStressForecastEngine, HistoricalSpendingDistribution | Typed forecast/distribution totals |
| M-1 parsed money DTO migration (Segment 4) | ReceiptParser and receipt consumers | Typed resolved source money; unresolved review evidence |
| Contribution-history source-currency contract (Segment 35 / M-3) | SavingsGamificationEngine, SavingsContributionHistoryRepository | Event currency captured; unknown legacy provenance fails closed |
| Typed dashboard/display boundary (Segments 8/9) | ComputeDashboardWidgetsUseCase, TotalsDashboardCard | Typed display inputs and unavailable rendering |

Do not rename compatibility projections to evade the scan. New savings events record currency; historical events without currency must not be labeled with today's home currency. The typed contribution path preserves that unresolved state.

Wave 5 money-debt closure therefore remains conditional. A 24/25 suite is FAIL overall, not an all-green result or full Wave 5 completion.

The October 6 closing queue on fingerprint 0ad5f95b completed compile, 59 targeted profiles, migration-tests, and registered money/raw-money/cancellation guards with terminal PASS, markers and no fingerprint drift. The full static suite vr-20261006-171416-9e0ebeca completed FAIL/1 at 24/25: raw money was zero, cancellation flat at 117, DB access clean and guard_tests 4744 passed/28 skipped/0 failed. The sole violation was known-good-state: a new trailing comma in CurrentPeriodSummary made its signature unsupported by the existing exact matcher (22 advisories versus the approved 21). The source signature now omits that comma; no parser, policy, baseline or expectation pin was changed. Final post-correction validation is pending.

Local strict review after the repairs found no remaining concrete money/currency, lifecycle, privacy or cancellation defect in the reviewed Wave 5 boundary. This is a single-agent local review, not an independent reviewer sign-off. Preserve the separate commit-selection and deferred device/release gates.

Post-signature-correction fingerprint c1662e4f completed compile, 62 targeted filters, migration-tests and money/raw-money/cancellation/known-good-state gates. Static suite vr-20261006-185538-962f96c9 completed PASS/0 at 25/25 (4744 pytest passes, 28 skips, zero failures). No raw-money violation remains. During the sweep, a Status poll hit E_INVALID_LOCK after its terminal worker removed the lock between existence check and read; the durable savings test record was independently PASS with marker and matching fingerprints. Resolve-ActiveLock now rechecks absence after a null read, while a still-present malformed lock remains E_INVALID_LOCK. Both outcomes have isolated regression fixtures. Final runner-only post-fix validation remains pending; earlier application evidence is explicitly tied to c1662e4f.

## Validation and commit boundary

The first closing queue halted at ReceiptProcessingPipelineTest: two merchant/categorization happy-path fixtures had no currency evidence and still expected an available total. They now contain explicit EUR evidence without changing their amount/category assertions; a separate pipeline case proves absent currency stays unresolved even with USD home currency. This is a fixture-contract repair, not restoration of currency guessing.

The fixture repair exposed a separate pre-existing Greek merchant defect: keyword replacement consumed a newline through its whitespace boundary and merged the merchant header with TOTAL_KEY. Normalization now uses horizontal boundaries with multiline anchors; focused parser and pipeline regressions retain the merchant and monetary assertions. Temporary reflection probes were removed.

Freeze source/tests/docs before the runner queue. First compile and the radar/forecast filters; then the M-1/M-2 foundation, tax, receipt, analytics, forecast, savings, cashflow, golden/consistency and architecture filters; migration-tests; registered cancellation and raw-money guards; freshness stamp; full static suite (including the new Python regressions).

Only terminal records with exit code, completion marker and matching start/end/current fingerprints count. A guard violation remains a violation; a timeout, orphan or missing output is unknown/failure, never PASS. Durable records may resolve wrapper bookkeeping disagreements but cannot waive a nonterminal or stale result.

Use scripts/validation-runner.ps1 exclusively for compilation/tests/guards. The freshness writer is scripts/ci/test_result_freshness.py with --suite-name testDebugUnitTest; stamp is not a runner profile.

Candidate follow-up files are the reviewed forecast/radar/cashflow production and focused test changes, validation runner, registered-guard adapter/tests, runner protocol tests and this handoff. Keep unrelated existing modifications untouched and out of any eventual commit. Device gates are not satisfied by unit tests or migration-tests (the latter selects unit Migration tests, not connected Android tests).
