# Wave 5 forecast-quality continuation - 2026-10-09

Status: **PARTIAL, not commit-ready**. This packet supersedes the forecasting continuation notes only; the supplied R01-R20 acceptance requirements and other Wave 5 handoffs remain in force. No subagents, staging, commits, baseline or policy edits in this continuation.

## Verified disk preflight

- Branch `bug-fixes`; HEAD `a1c66173219d7d959b0d36859a68176aaf814242`.
- Arrival runner-algorithm fingerprint: `9ffb760747a1ad98aefd2f8533f2fa89bafa67ca4e5c5d4c0dd3d4c038bd38ad`; 139 tracked changed paths, 42 untracked. These are arrival counts, not a final candidate count.
- No active lock or Gradle/validation process at arrival. Full durable-record enumeration found one old `STARTING` record: `vr-20261005-170759-59bedca5`. Its recorded worker PID 18644 was absent. Runner `-Action Status -RunId vr-20261005-170759-59bedca5` performed documented orphan recovery to `INFRA_FAILURE`, exit 2, `E_ORPHANED_RUN`, with marker. It is not PASS evidence. No runner code repair was needed.
- Physically inspected result metadata and stdout/stderr tails plus completion markers for the four forecast filters below. Compile result/marker existence also checked. All five have terminal PASS/0 and matching arrival start/end fingerprints:
  - compile `vr-20261009-050924-eb670bb8`;
  - `*HistoricalSpendingDistributionBoundaryTest*`: `vr-20261009-051255-da817748`;
  - `*MonteCarloSpendingSimulatorTest*`: `vr-20261009-051834-e1b15f38`;
  - `*MonteCarloSpendingSimulatorGoldenTest*`: `vr-20261009-052034-b16134df`;
  - `*ForecastSynthesisGoldenTest*`: `vr-20261009-052157-3ce7c122`.
- These are now historical because the source/test/document bytes changed. Do not combine them with another fingerprint into current-tree closure.

## Forecast-only slice of suggested Batch 1

Files touched in this continuation:

- `app/src/main/java/com/yourname/expensetracker/domain/forecasting/HistoricalSpendingDistribution.kt`;
- `app/src/main/java/com/yourname/expensetracker/domain/forecasting/MonteCarloSpendingSimulator.kt`;
- `app/src/main/java/com/yourname/expensetracker/domain/forecasting/MonteCarloResult.kt`;
- `app/src/test/java/com/yourname/expensetracker/domain/forecasting/HistoricalSpendingDistributionBoundaryTest.kt`;
- `app/src/test/java/com/yourname/expensetracker/domain/forecasting/MonteCarloSpendingSimulatorTest.kt`;
- `workflows/active/money-w5-candidate-paths-20261008.md`;
- this handoff.

Behavior:

- End-of-month `Unavailable` now throws the controlled `MoneyDisplayUnavailableException`, just like mid-month. It cannot become a deterministic under-budget forecast. Genuine `NoFit` continues to allow the existing deterministic/degraded result.
- Successful fits carry `HistoricalMoneyQuality`: full normalization input/included/excluded counts, requested/actual rate basis, warnings and latest rate-update timestamp. Counts describe the normalization window, not the statistically qualifying weeks. All three simulation branches retain this metadata separately from statistical confidence.
- Complete-input-only policy is preserved: stale history is **unavailable**, not a successful fit. Its conversion quality is ESTIMATED, not falsely PARTIAL with zero exclusions; warnings/counts/timestamp are preserved at the typed distribution boundary. Actual exclusions retain PARTIAL/all-excluded UNAVAILABLE quality.
- No arithmetic, sampling, trimming, seeded golden values, FX policy, Room schema, write ownership or persistence semantics changed. Deprecated nullable distribution adapters remain compatibility-only; source search found no production call outside their declarations. Simulator defaults remain an explicit follow-up, not declared fixed.
- New regressions cover identity versus EUR/USD/GBP source provenance, successful metadata, stale/partial metadata, end-month unavailable reasons, exception identity on cancellation, genuine no-fit arithmetic/probability, and metadata in stochastic/degraded/end-month paths. Existing golden assertions are unchanged.

## Review and validation at source freeze

- Narrow in-session source self-review: PASS for this delta (unavailable vs no-fit, provenance, unchanged arithmetic, cancellation, tests, no sensitive diagnostics or storage change). This is **not** an independent review or a PASS for the mixed Wave 5 tree.
- Architecture/money context: `CODEBASE_SEGMENTS.md` Segment 1; `CODEBASE_INVENTORY.md`; `LEGAL_PATHS.md`; `ENGINE_INTERACTION_MAP.md`; M-0 inventory F16/M-3a2. No lifecycle writer or schema gate added by this read-only forecasting change.
- Targeted execution is pending at the time this source-freeze packet is authored. It must use `scripts/validation-runner.ps1` serially: compile, then the four exact forecast filters listed above. Durable `build/validation-runs/vr-*/result.json`, `complete.marker` and logs are authoritative for subsequent outcomes. Use runner List/Status to discover the new run IDs and compare against the fingerprint recomputed with `Get-WorktreeFingerprint`'s algorithm. Do not edit this packet mid-run.
- Broad shards, migration, money/raw-money/cancellation guards, static suite, freshness procedure, connected/PDF gates and independent review are **not refreshed by this slice**. No Wave 5 completion claim.

## Caller-contract findings left for next slice

- `ComputeDashboardWidgetsUseCase.computeMonteCarlo()` still omits display currency, collapses failure to null and logs a throwable. `simulate()` and fit/compatibility DTO defaults still allow implicit EUR. Fix explicit-currency and unavailable presentation together with real-caller regression tests; do not claim Batch 1 or R15/R17 fully closed.
- Smart savings catches unavailable money as an empty recommendation list; sweep retains the controlled reason but not rich historical metadata. The simulator exception boundary still projects the rich unavailable distribution down to its reason. No-fit metadata/presentation and downstream quality adapters need a separate contract slice.
- R14 legacy goal gaps, R16 one-allocation persistence/idempotency, R19 semantic history corruption and R18/source verification remain open per the supplied handoff; earlier optimistic handoff wording is not new evidence or accepted limitation.

## R13 exact human-authorization prerequisite

Persisted run `vr-20261009-043313-5546468e` is terminal FAIL/1 on `07094b76...`; log reports baseline 117/current 113, NEW 0/RESOLVED 4. Complete keys recovered from its stdout and confirmed present in `config/baselines/cancellation.json`:

1. `G-CANCEL-01 app/src/main/java/com/yourname/expensetracker/domain/health/FinancialHealthScoreV2.kt`
2. `G-CANCEL-01 app/src/main/java/com/yourname/expensetracker/domain/usecase/savings/MonthlySavingsSweepUseCase.kt`
3. `G-CANCEL-02 app/src/main/java/com/yourname/expensetracker/domain/forecasting/HistoricalSpendingDistribution.kt`
4. `G-CANCEL-02 app/src/main/java/com/yourname/expensetracker/domain/usecase/savings/MonthlySavingsSweepUseCase.kt`

Each baseline `counts` value is 1. Explicit human-owner approval for these exact entries is still required before removal from fingerprints/counts. No authorization is inferred from continuation. The older authorized AdvancedAnalyticsEngine 118-to-117 removal is separate. Preserve correct cancellation fixes. See `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` FG-03/FG-06/FG-07/FG-23; no guard waiver.

## Resume

1. Check locks, liveness and newly persisted results; poll any RUNNING record, never restart it.
2. Confirm the targeted queue and narrow review before considering this slice validated. Failed/unknown/orphan/stale evidence is never PASS.
3. Implement the documented caller-contract slice next; then proceed one savings/source batch at a time as in the supplied handoff.
4. Reconcile the complete candidate manifest by path/hunk before the eventual full source freeze. Preserve all unrelated work and the five untracked production money helpers. Do not stage, commit or alter the cancellation baseline without the required human instructions.
