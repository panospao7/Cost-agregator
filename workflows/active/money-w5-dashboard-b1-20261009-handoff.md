# Wave 5 dashboard Batch 1 - 2026-10-09

Status: PARTIAL, not whole-wave accepted or commit-ready. Resume from this packet and money-w5-remaining-implementation-plan-20261009.md. Original R01-R20 acceptance and previous money handoffs remain in force. The user instructed no further subagents; the active correction agent and remaining reviewer sessions were stopped. Continue locally. No staging/commits, baseline or schema edits, or unrelated resets.

## Files touched

- app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt
- app/src/test/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCasePaceWiringTest.kt
- workflows/active/money-w5-remaining-implementation-plan-20261009.md
- workflows/active/money-w5-candidate-paths-20261008.md
- This handoff.

## Behavior

- computeMonteCarlo forwards captured home currency explicitly and preserves complete owner quality.
- Incomplete owner normalization and partial/excluded synthesized future obligations fail closed before simulation.
- Unresolved home currency, typed money failure, and generic errors return controlled unavailable result/quality instead of actionable zeros or silent omission. Free-form exception/upstream reasons are not forwarded by this method.
- Cancellation is rethrown unchanged; no throwable/message/stack logging. Only genuine simulator null omits this widget.
- Eight new regressions cover USD/GBP, incomplete actuals, incomplete future obligations, controlled unavailable reasons, cancellation identity, generic error privacy, genuine null, and unresolved home currency. Existing pace/savings tests remain.
- No simulator API/default, sampling, financial arithmetic, rate policy, golden value, savings persistence, schema, guard, or baseline changes in this batch.

## Review

Initial independent strict and privacy reviews returned FAIL because the first patch allowed partial input to simulate and a new test expected partial success. This was corrected in the same two-file scope before the user stopped subagent work. The orchestrator inspected the correction locally.

- Corrected-slice local strict self-review: PASS for scope, complete input gates, currency, controlled reasons, privacy, cancellation, and tests.
- Independent strict/privacy re-review of corrected source: NOT RUN. Earlier FAIL verdicts are not replaced by local self-review.
- Whitespace check: PASS for source/tests. Room/migration: not applicable.

## Verified validation before this documentation amendment

Fingerprint: ebb1ef2fd6a1cc0c8cb0e38323e3b13eb1b60335187681aaa2b381c41bb84f78.

- vr-20261009-113814-585018fc: compile PASS/0; physical marker and meaningful log; matching start/end fingerprint; KSP/Kotlin executed; 16 tasks, 2 executed/14 UP-TO-DATE; 8m25s.
- vr-20261009-114832-c8577fab: targeted-unit-test *ComputeDashboardWidgetsUseCasePaceWiringTest*, PASS/0; physical marker/log and matching start/end fingerprint; 15 logged passes; test Kotlin/test execution; 35 tasks, 9 executed/26 UP-TO-DATE; 10m40s.

Commands used powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 with Action Start and compile or targeted-unit-test profile, followed serially by Wait/Status. Configuration cache reused; standard JVM class-sharing warning in test stderr. No direct Gradle or guard execution.

These are historical after this documentation amendment. Do not merge different fingerprints into closure. Final focused queue is pending when this packet is written; subsequent durable records are authoritative. Freeze source/tests/docs before queue: compile, *ComputeDashboardWidgets*, *DashboardWidgetConsistencyTest*, *FinancialRunwayNoBurnTest*, *BlockPartyDstAlignmentTest*, *DashboardContractsAdapterTest*, and registered money/raw_money_aggregates/cancellation guards, serially.

## Remaining gates

- Independent corrected-source review outstanding; no whole-wave PASS. Broad shards, migration, full static suite, freshness, device/PDF/connected evidence remain NOT RUN for this slice.
- Batch 2 simulator/DistributionFit implicit EUR defaults remain; dashboard caller omission is now repaired, not a current finding.
- Remaining savings outcome/provenance/history contracts and acceptance persistence/idempotence, source-boundary verification, PDF/device evidence, documentation/final freeze remain in the plan.
- R13 four newer baseline entries remain unapproved and untouched. This dashboard cancellation repair may resolve additional keys; recompute the final resolved set and obtain exact authorization. Older AdvancedAnalyticsEngine removal is separately historically authorized.
- Preserve mixed-tree unrelated work and all five untracked production money helpers. No commit authority.

Next: inspect final focused records and liveness, retain independent-review limitation, and only then consider Batch 2. Continue locally without subagents.
