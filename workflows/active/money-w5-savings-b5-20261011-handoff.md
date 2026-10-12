# Wave 5 savings Batch 5 (R19) - 2026-10-11

Status: PARTIAL. Implementation, local self-review and bounded validation
passed. Independent acceptance remains NOT RUN.
Continue from this packet, not prior chat.

## Scope / starting state

- Approved intent: workflows/active/money-w5-remaining-implementation-plan-20261009.md,
  Batch 5 only. Prior packet: money-w5-savings-b4-20261011-handoff.md.
- The persisted user amendment prohibits further subagents. Discovery, coding,
  test authorship, strict/architecture/privacy self-review and runner execution
  are local; local review does not replace independent acceptance.
- Branch bug-fixes, HEAD a1c66173219d7d959b0d36859a68176aaf814242.
- Pre-packet mixed tree: 150 tracked changes / 51 untracked files, including prior
  Wave 5 and unrelated work. Preserve all earlier hunks; nothing staged/committed.
- Read relevant segment/inventory ownership, Money/Currency and Diagnostics legal
  paths, engine impact entries, repository, actual callers and affected tests.
- No Room/entity/DAO/migration/schema, baseline/allowlist, guard or runner changes.
  R13 cancellation baseline ratchet remains unauthorized.

## Files touched

- app/src/main/java/com/yourname/expensetracker/data/repository/SavingsContributionHistoryRepository.kt
- app/src/main/java/com/yourname/expensetracker/ui/screens/savings/SavingsGoalsViewModel.kt
- app/src/main/java/com/yourname/expensetracker/ui/screens/savings/SavingsGoalsScreen.kt
- app/src/main/res/values/strings.xml (one history-write warning)
- app/src/test/java/com/yourname/expensetracker/data/repository/SavingsContributionHistoryRepositoryTest.kt
- app/src/test/java/com/yourname/expensetracker/domain/savings/SavingsGamificationEngineTest.kt
- app/src/test/java/com/yourname/expensetracker/ui/screens/savings/SavingsGoalsViewModelTest.kt
- This packet and money-w5-candidate-paths-20261008.md.

SavingsGamificationEngine production handling already maps the controlled
repository exception to unavailable history/streak/awards, so no production edit
there was necessary. Integration tests now use actual persisted semantic failures.

## Behavior

- Read the existing history envelope with strict Gson JsonReader rather than DTO
  hydration/defaulting/coercion. Require an object with a non-null events array,
  non-null object events, and numeric mandatory goalId/amount/timestamp fields.
  Reject duplicate envelope/event fields, trailing JSON, missing mandatory fields
  and incompatible optional-field types before pruning or writing.
- IDs and timestamps must be exact positive Long values (no truncation/overflow).
  Preserve the existing positive epoch-millis contract; no invented future-date
  restriction or arbitrary maximum was added. Individual amounts must be finite
  and positive. No financial weighting, rounding or aggregation change.
- Supplied currency must parse to a supported currency. Absent/null legacy currency
  remains unknown, not corrupt and never defaulted to EUR. Missing/null source is
  compatible legacy data; supplied source must be manual, sweep or legacy.
  Arbitrary/blank/unbounded sources are rejected, not silently sanitized.
- Validate every stored event before retention or MAX_EVENTS can discard it.
  Corrupt/unreadable JSON returns SAVINGS_HISTORY_CORRUPT, reads throw the existing
  controlled exception, writes return false, and stored JSON remains unchanged.
  Missing key and a valid explicit empty events array still mean genuine empty
  history. The DataStore/key/envelope shape and retention limits are unchanged.
- Manual/smart/sweep callers now observe rejected recording. A storage exception
  gets fixed SAVINGS_HISTORY_WRITE_UNAVAILABLE; false gets fixed
  SAVINGS_HISTORY_WRITE_REJECTED. CancellationException is rethrown unchanged.
- Invalid manual amounts are rejected before either balance or history store is
  touched, rather than relying on a later history rejection after a balance write.
- Failure suppresses stale history metrics/recommendations, exposes a fixed UI
  warning that the goal balance may have changed, and disables subsequent
  contribution retries in this ViewModel session. Late goal/sweep recomputation
  cannot restore actionable recommendations over that failure.
- This is NOT an atomic balance/history write and NOT durable idempotence.
  Previously successful Room increments are not reversed. A cancellation,
  concurrent in-flight operation, process restart or crash can still leave
  partial persistence. Batch 8 remains required; no recovery/confirmation flow
  or cross-store transaction was invented.

## Local strict / architecture / privacy review

VERDICT: PASS (self-review only).
Issues: None blocking in this slice.
- Scoped git status/diff and surrounding production callers inspected.
- Initial DTO/tree approach could discard duplicate fields; replaced before
  validation with strict streaming checks and targeted duplicate-field coverage.
- Existing legal repository write path retained; no DAO bypass or schema effects.
- No raw financial payload/source, file path, exception message, throwable or
  stack trace enters diagnostics or UI failure reason fields. Only controlled
  constants are exposed; corrupt persisted bytes are retained rather than logged.
- Sources verified against every production recordContribution call (manual/sweep)
  and legacy compatibility tests. Positive timestamp contract matches existing
  owner validation and TimePeriodUtils supported epoch range.
- Scoped git diff --check passed before packet creation.
- Independent strict/architecture/privacy guardians: NOT RUN, not waived.

## Required bounded validation (NOT RUN at packet creation)

Only scripts/validation-runner.ps1, serialized:
1. compile
2. targeted-unit-test: *SavingsContributionHistoryRepositoryTest*
3. targeted-unit-test: *SavingsGamificationEngineTest*
4. targeted-unit-test: *SavingsGoalsViewModelTest*

Coverage authored:
- Null/incompatible envelopes and events; mandatory fields; typed scalar values,
  numeric overflow/truncation/nonfinite values; controlled sources and currencies.
- All-event validation before stale/event-limit pruning, no-overwrite on reads
  and writes, corruption surviving repository restart, real empty history.
- Legacy currency-less history stays readable with unavailable money.
- Real semantic corruption suppresses streak and money/completion achievements.
- Manual/smart/sweep recording false, safe storage failure, subsequent retry
  suppression, late recomputation, and cancellation classification.

Preflight: no active runner lock or competing Gradle client found. PASS requires
terminal result.json PASS/0, physical complete.marker, meaningful log and equal
start/end fingerprints. Prior Batch 4 evidence is historical after these edits.
Poll RUNNING records; do not start another live validation process.

## Risks / next steps

- Source validation intentionally rejects arbitrary old source labels; no repair
  or silent migration is authorized. Legacy omitted source/currency stays valid.
- Unknown fields outside the known envelope/event contract are skipped as before;
  required financial field types/values and duplicates are verified.
- Atomicity, durable idempotence and honest recovery after partial writes remain
  Batch 8. The session retry block is not a substitute for those requirements.
- No storage-layer schema change or full-suite/device claim. Broader shards,
  static guards, independent acceptance and final freshness remain outstanding.
- Next approved batch: Batch 6, verify original source-boundary/review contracts.
- Preserve unauthorized R13 baseline and unrelated changes; no staging/commit.

## Bounded validation completion

Physically inspected each result.json, complete.marker and meaningful stdout
log tail. All records: terminal PASS, exit 0, no logged test failures, equal
start/end fingerprint
faf632e5ca0641a596bc0f33fe6385659d242dfa1c2cce624110f0944a08bb0f.
The read-only runner fingerprint function returned the same current hash before
this evidence append. No application/test bytes changed after these gates.

| Runner ID | Profile/filter | Logged test passes |
|---|---|---:|
| vr-20261011-182910-c9ee90b5 | compile | n/a |
| vr-20261011-183758-9329fc82 | *SavingsContributionHistoryRepositoryTest* | 12 |
| vr-20261011-184931-a3105bba | *SavingsGamificationEngineTest* | 25 |
| vr-20261011-185106-437ff267 | *SavingsGoalsViewModelTest* | 32 |

69 tests across three focused nonoverlapping filters. Compile executed 8 tasks
with 8 up-to-date in 8m40s. Repository run compiled test sources and executed
testing (11 tasks executed, 24 up-to-date, 11m27s); the next two filters each
executed testing (1 executed task, 34 up-to-date), not a cached assumed pass.

Commands:
- powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile compile
- Same wrapper with -Profile targeted-unit-test -TestFilter '<table filter>'.
- Wait polled each existing RunId. The queue was serialized; no concurrent live
  Gradle/test/guard invocation or direct Gradle invocation.

Result/log/marker paths: build/validation-runs/<runner ID>/{result.json,
stdout.log,stderr.log,complete.marker}. No active runner lock remained.
Final pre-append mixed tree: 150 tracked changed paths, 52 untracked paths.
Nothing staged or committed; unrelated hunks and baselines preserved.

This status/evidence documentation update changes the overall fingerprint.
The recorded gates are pre-documentation-append evidence, not a final whole-wave
freeze. Independent strict/guardian acceptance, broader shards/guards and
device evidence remain pending. Batch 8 still owns durable atomicity/idempotence
and recovery; next approved implementation is Batch 6 source-boundary verification.
