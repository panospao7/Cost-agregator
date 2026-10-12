# Wave 5 savings Batch 3 — 2026-10-11

Status: **PARTIAL**, not whole-wave accepted or commit-ready. Resume from this
packet and money-w5-remaining-implementation-plan-20261009.md. User requested
local continuation without subagents. No staging, commits, schema, runner,
baseline, or unrelated edits in this batch.

## Files / scope

Production paths under app/src/main/java/com/yourname/expensetracker/:
- domain/savings/SmartSavingsEngine.kt
- new domain/savings/SavingsDecisionQuality.kt
- domain/usecase/savings/MonthlySavingsSweepUseCase.kt
- domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt
- domain/usecase/dashboard/DashboardWidgetRegistry.kt
- ui/screens/savings/SavingsGoalsViewModel.kt
- ui/screens/savings/SavingsGoalsScreen.kt
- ui/screens/home/HomeScreen.kt

Resource: app/src/main/res/values/strings.xml.

Tests under app/src/test/java/com/yourname/expensetracker/:
- domain/savings/SmartSavingsEngineTest.kt
- domain/usecase/savings/MonthlySavingsSweepUseCaseTest.kt
- ui/screens/savings/SavingsGoalsViewModelTest.kt
- domain/usecase/dashboard/ComputeDashboardWidgetsUseCasePaceWiringTest.kt
- ui/screens/home/DashboardWidgetRenderCoverageTest.kt
- Typed sweep fixture alignment only: DashboardContractsAdapterTest,
  FinancialRunwayNoBurnTest, ComputeDashboardWidgetsUseCaseDaysRemainingBoundaryTest,
  ComputeDashboardWidgetsInsightMoMTest, BlockPartyDstAlignmentTest,
  ComputeDashboardWidgetsHistoricalMeanPolicyTest.

These paths contain prior Wave 5 work: review authored hunks, not whole paths as
exclusive ownership. Preserve the mixed tree and all untracked helpers.

## Behavior

- Smart/sweep distinguish available, valid no-recommendation and unavailable.
- Invalid targets, partial/unknown budgets, missing/stale conversions, unavailable
  forecast currency/quality and unexpected failures fail closed with controlled
  reason codes. Cancellation is rethrown unchanged.
- Request-local collection retains stage-specific normalization counts, enum-derived
  warnings, rate bases and known timestamps, including failed stages. No raw warning
  messages, amounts, merchant names or exception payloads enter quality metadata.
  Counts describe observations in overlapping windows, not unique transactions.
- Mixed requested policies remain per-stage sets; singular requested basis is null
  without a single observed policy. Rate-settings version is separate from actual
  normalization timestamp. Existing latest-rate upcoming estimate policy is preserved.
- Null simulator result remains the legitimate no-data contract/conservative
  fallback; explicit unavailable results never take that fallback.
- Production ViewModel/dashboard no longer call deprecated nullable/list adapters;
  typed outcomes retain their metadata. Dashboard unavailable sweep has no monetary
  fields, uses the existing sweep registry ID, and has explicit rendering/coverage.
  Available sweep uses its captured currency, not the current screen label.
- Savings screen renders unavailability, not actionable zero. Removed premature
  smart-savings saved snackbar; durable acceptance feedback still belongs to Batch 8.

## Review

Local strict source/self-review: PASS for this bounded outcome/presentation slice,
subject to pending regression gate. Read Segment 23/35 ownership, money/diagnostic
legal paths and affected code/callers/tests. No lifecycle bypass, DAO writes,
schema/migrations, policy changes or guard weakening.
Independent review/guardian acceptance: NOT RUN per no-subagent directive;
self-review does not replace independent acceptance.

## Validation

Historical preliminary fingerprint:
9119f4cf9e10e9ced2d0b497b79134298821252f7980ec0092e67f7c49a9d375.

- Compile vr-20261011-152144-c86e9afa: PASS/0, marker/log, equal start/end
  fingerprints; 9m25s, 16 tasks (8 executed/8 up-to-date).
- Existing Smart Savings suite vr-20261011-153531-4a251fee: PASS/0, physical
  marker and 9 logged passes; 11m35s, 35 tasks (11 executed/24 up-to-date).
- Two sandbox starts returned E_WORKTREE_FINGERPRINT_UNAVAILABLE before starting
  tests; approved escalated runner succeeded. No runner edits.

These precede added typed-outcome regressions/metadata refinements and do not
validate this changed snapshot.

Final bounded queue **pending when this packet is written**:
compile; filters *SmartSavingsEngineTest*, *MonthlySavingsSweepUseCaseTest*,
*SavingsGoalsViewModelTest*, *ComputeDashboardWidgets*,
*DashboardWidgetRenderCoverageTest*, *DashboardContractsAdapterTest*,
*FinancialRunwayNoBurnTest*, *BlockPartyDstAlignmentTest*.

Only scripts/validation-runner.ps1, serially. Poll existing RUNNING records.
Subsequent build/validation-runs/<id>/result.json, physical marker and logs are
authoritative; require terminal PASS/0 and equal start/end fingerprints.
Stop queue on non-PASS and diagnose persisted logs.

## Follow-up

- Batch 4: legacy savings eligibility/provenance. Same-currency labels alone
  currently still allow LEGACY_DEFAULT smart/sweep recommendations.
- Batch 5: semantic history validation, not just malformed JSON.
- Batches 6–7: source verification and PDF/device evidence.
- Batch 8: acceptance persistence, visible failures and idempotence. Current
  multi-goal acceptance fails closed; balance/history writes are not batch-atomic.
- Existing SpendingThresholdCalculator raw-money/currency policy is outside this
  outcome slice and needs owner review before whole-wave acceptance.
- R13 remains unauthorized: do not edit cancellation baseline. Independent
  acceptance, broader shards/static guards/freshness and connected/device evidence
  remain outstanding. No whole-wave/release/commit-ready claim.

## Bounded validation completion — 2026-10-11

The bounded queue and extra widget consistency/registry check subsequently
passed. Each record below was physically checked: terminal PASS, exit 0,
completion marker, meaningful log and equal start/end fingerprint:
cd024766f67b28e861276c53fce072d49a7ef6c952c1459f02dd171bb63b7d44.

| Runner ID | Profile/filter | Logged passes |
|---|---|---:|
| vr-20261011-162859-a407d31c | compile | n/a |
| vr-20261011-163747-adb7bc40 | *SmartSavingsEngineTest* | 17 |
| vr-20261011-164912-4cb4f39b | *MonthlySavingsSweepUseCaseTest* | 24 |
| vr-20261011-165108-1920bd61 | *SavingsGoalsViewModelTest* | 21 |
| vr-20261011-165258-24fcaa90 | *ComputeDashboardWidgets* | 29 |
| vr-20261011-165447-49853c46 | *DashboardWidgetRenderCoverageTest* | 3 |
| vr-20261011-165637-bb49d74b | *DashboardContractsAdapterTest* | 8 |
| vr-20261011-165827-7667c9bc | *FinancialRunwayNoBurnTest* | 7 |
| vr-20261011-170016-302fac5d | *BlockPartyDstAlignmentTest* | 3 |
| vr-20261011-170317-65d0a7e8 | *DashboardWidget*Test* | 38 |

Nine targeted-unit-test runs; counts overlap (not 150 unique tests).
Compile executed 2 tasks/14 up-to-date in 8m40s. Expanded Smart Savings
executed test compilation and testing, 9 tasks/26 up-to-date in 11m17s;
the following focused runs executed testing, 1 task/34 up-to-date.
All result.json, complete.marker and stdout.log/stderr.log paths are beneath
build/validation-runs/<runner ID>/.

Exact command forms:
- powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile compile
- Same wrapper with -Action Start -Profile targeted-unit-test -TestFilter '<table filter>'.
- Status/Wait polled each existing record; no concurrent Gradle processes.

An initial queue preflight was refused with E_EXTERNAL_GRADLE_ACTIVE because its
parent inspection command text itself contained the wrapper-class search string.
Read-only inspection found no actual competing client; retrying without embedding
that inspection text in the queue succeeded. No guard or runner change/weakening.

No active lock remained after completion. Scoped whitespace check passed.
No application/test bytes changed after these runs. This evidence append and
candidate-manifest update change the overall fingerprint, so the listed evidence
is pre-documentation-append evidence, not a final whole-wave freeze.

Batch 3 implementation and bounded runtime regression gate are satisfied locally;
independent strict/guardian acceptance and whole-wave gates remain pending.
Next implementation batch is Batch 4 (legacy savings provenance), not persistence.
