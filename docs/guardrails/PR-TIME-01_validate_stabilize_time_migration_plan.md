# PR-TIME-01 — Validate and stabilize the August 29, 2026 time migration

## Agent mission

Validate the broad direct-clock migration introduced at:

```text
8b45879e445e8750fcb9210bcf44b9a26e6468dd
```

and correct only demonstrated defects.

This PR is not merely “make G-TIME-01 green.” It must prove that replacing direct clock reads preserved required date, timezone, duration, injection, UI, and persistence behavior.

The anchor commit replaced 78 direct-clock findings across 40 files using `TimeProvider`, `java.time`, and `Calendar.Builder` seams. ([github.com](https://github.com/panospao7/Cost-agregator/commit/8b45879e445e8750fcb9210bcf44b9a26e6468dd))

## Why this PR is necessary

The current time guard is strict-zero. It rejects direct `System.currentTimeMillis`, `System.nanoTime`, zero-argument `Date()`, `Calendar.getInstance`, `Instant.now`, local-date/time `now()` APIs, and direct system clocks unless an exact source-verified exception exists. It has no time baseline. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/verify_time_boundaries.py))

The current application already distinguishes:
- `TimeProvider` for logical wall-clock instants; and
- `MonotonicTimeProvider` for elapsed duration measurement. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/app/src/main/java/com/yourname/expensetracker/domain/util/TimeProvider.kt))

However, `FinancialStressForecastEngine` currently uses `TimeProvider.now()` both for logical forecast time and elapsed-duration logging. That must be corrected: elapsed duration belongs to the monotonic seam. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/app/src/main/java/com/yourname/expensetracker/domain/forecasting/FinancialStressForecastEngine.kt))

`TimePeriodUtils` is a high-risk migration surface: it now uses `java.time`, default-zone semantics, half-open ranges, `Calendar.Builder` compatibility helpers, a pre-Gregorian seam, and documented extreme-value behavior. These claims need focused tests, not trust in comments alone. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/app/src/main/java/com/yourname/expensetracker/domain/util/TimePeriodUtils.kt))

---

# Preconditions

Do not start until GATE-00R has a complete run bundle for the actual start SHA.

Required GATE-00R inputs:

```text
target SHA
input hash manifest
time guard stdout/stderr and exit
time guard pytest result
static suite result
compile result, if available
DB direct result
DB ratchet/static-suite result
```

Also require:
- clean checkout;
- actual changed-file inventory from the August 29 migration and current branch;
- no other Gradle owner;
- review of the relevant architecture documents required by `AGENTS.md`;
- explicit acknowledgement that this is strict-mode cross-cutting work.

## Hard stops

Stop and report rather than guessing if:

- the time guard exits `2` and the reason is not understood;
- a behavior change requires choosing a new global timezone policy;
- a source path has no clear owner/test seam;
- a change would require broad time exceptions;
- a change appears to affect Room migrations, DB policy, or baseline semantics;
- a calendar behavior differs from the pre-migration behavior and product intent is unclear;
- Gradle is owned by another agent;
- the fix would be “replace one direct clock call with another direct clock call.”

---

# Scope

## Allowed

- Exact production files identified by the time-migration inventory;
- TimeProvider/MonotonicTimeProvider injection fixes;
- Existing time utility and directly affected callers;
- Kotlin unit tests and focused Compose/ViewModel tests;
- `scripts/verify_time_boundaries.py` and its tests only for a proven guard defect;
- exact time exception metadata only when a genuine unavoidable adapter/migration boundary is proven;
- a concise time-domain decision record;
- GATE-00R final evidence artifacts.

## Forbidden

- DB ownership-policy changes;
- DB baseline changes;
- broad allowlist additions;
- wildcard/path/class-level time exceptions;
- a time baseline;
- changing the app from local-time semantics to UTC without an explicit separate design decision;
- broad source-root/registry/suite rewrites; those belong to GR-10;
- opportunistic refactoring of unaffected clock-free code;
- modifying `SystemTimeProvider` to stop using the platform wall clock;
- direct `System.nanoTime()` in new production code.

---

# Required time-domain contract

Create or update one concise tracked record, for example:

```text
docs/architecture/TIME_DOMAIN_POLICY.md
```

It must state the following.

## 1. Logical “now”

```text
TimeProvider.now(): Long epoch milliseconds
```

Use it for:
- timestamps;
- scheduling decisions;
- persistence metadata;
- date/range calculations;
- validation against “current time”;
- UI state derived from the current instant.

Do not use it to measure elapsed execution duration.

## 2. Elapsed duration

```text
MonotonicTimeProvider.nowNanos(): Long
```

Use it for:
- performance logs;
- solver/algorithm budgets;
- timeout accounting where monotonic elapsed time is required;
- duration metrics.

The existing monotonic implementation is Hilt-bound and backed by Kotlin monotonic time; use it rather than adding direct `System.nanoTime()` calls. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/app/src/main/java/com/yourname/expensetracker/di/TimeModule.kt))

## 3. Calendar interpretation

For this PR, preserve the existing app contract:

```text
Persisted values are epoch instants.
Calendar/day/week/month interpretation uses the device/system-default zone.
```

Do not silently convert the whole app to UTC.

Every individual calculation must capture its zone once per operation:

```kotlin
val zone = ZoneId.systemDefault()
```

Then derive all fields/ranges in that operation using that same zone.

## 4. Period boundaries

Maintain the `TimePeriodUtils` half-open interval convention:

```text
[startInclusive, endExclusive)
```

No caller may replace it with an inclusive end-of-day `23:59:59.999` convention.

## 5. Exceptions

The expected checked-in exceptions are exact and source-evidence-backed:
- the canonical `SystemTimeProvider` wall-clock adapter;
- a monotonic solver measurement;
- two Room migration-time initialization seams. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/config/guards/time_boundary_exceptions.yml))

No new exception may be added merely because constructor injection or a test is inconvenient.

---

# Inventory deliverable

Create a reviewed inventory:

```text
docs/ci/time/PR-TIME-01_MIGRATION_REVIEW.yml
```

It must be based on actual current diffs, not the commit message alone.

## Required top-level fields

```yaml
schemaVersion: 1
migrationAnchorSha: 8b45879e445e8750fcb9210bcf44b9a26e6468dd
migrationParentSha: 588623d
startSha: <actual full SHA>
gateEvidenceSha256: <GATE-00R manifest hash>
entries: []
```

## One row for every production Kotlin file changed by the time migration

```yaml
- path: app/src/main/java/...
  category: WALL_NOW | MONOTONIC_DURATION | PURE_INSTANT_DERIVATION | CALENDAR_COMPATIBILITY | UI_STATE | FILE_NAMING | TEST_COMPATIBILITY
  oldClockOrCalendarPattern: ...
  replacementSeam: ...
  semanticContract: ...
  requiredTest: ...
  disposition: VERIFIED | FIX_REQUIRED | BLOCKED
  owner: "@panospao7"
```

Every changed production file must have exactly one reviewed row.

Examples of high-risk categories:
- `FinancialStressForecastEngine`: elapsed duration;
- `TimePeriodUtils`: calendar/range compatibility;
- `SynthesisEngine`: recurrence and month/day grouping;
- `CashFlowCalendarScreen`: month-grid offset and `Date` construction;
- `CloudReceiptAssistService` / `AiOutputValidators`: injected validation reference time;
- `ReceiptAssetStore`: timestamped names;
- diagnostics/debug services: timestamp generation;
- reminder/tax/savings logic: calendar interpretation.

---

# Implementation sequence

## Step 1 — Freeze exact input and reproduce GATE-00R findings

```bash
git status --short
git rev-parse HEAD
git rev-parse HEAD^{tree}

python3 scripts/verify_time_boundaries.py \
  --root . \
  --allowlist config/guards/time_boundary_exceptions.yml \
  --fail-on-violation

python3 -m pytest scripts/test_verify_time_boundaries.py -v --tb=short
```

Copy the exact findings/diagnostics into the migration review artifact.

Do not edit source or exceptions before this inventory exists.

## Step 2 — Audit all changed time paths before coding

Generate:

```bash
git diff --name-status \
  588623d \
  8b45879e445e8750fcb9210bcf44b9a26e6468dd \
  -- app/src/main app/src/test
```

Then compare that historical set with current `HEAD`.

For each changed path determine:
1. Was the old code reading wall time?
2. Is the replacement pure epoch-to-calendar conversion?
3. Does it now require injected time?
4. Does it measure duration rather than logical “now”?
5. Does it depend on zone, locale, DST, Gregorian cutover, leniency, or date mutability?
6. Does it have a focused test?
7. Does the current G-TIME-01 scanner see it correctly?

No bulk mechanical follow-up is allowed. Fix one category at a time.

## Step 3 — Repair wall-clock versus monotonic misuse

### Required `FinancialStressForecastEngine` correction

Current logical forecast calculations should continue to use:

```kotlin
timeProvider.now()
```

Replace elapsed logging logic conceptually with:

```kotlin
val startedAtNanos = monotonicTimeProvider.nowNanos()
...
val elapsedNanos = monotonicTimeProvider.nowNanos() - startedAtNanos
val elapsedMillis = elapsedNanos / 1_000_000L
```

Requirements:
- inject `MonotonicTimeProvider`;
- preserve `TimeProvider` for logical date/range calculations;
- do not call `System.nanoTime()` directly;
- do not add a time exception;
- do not mask negative fake-provider output; a fake must itself preserve the monotonic contract.

### Required tests

Add or extend focused tests proving:
1. forecast logical date inputs come from `FakeTimeProvider`;
2. elapsed-duration measurement comes from a fake monotonic provider;
3. wall-clock movement does not affect elapsed duration;
4. elapsed calculation is deterministic;
5. Hilt compilation still resolves both bindings.

## Step 4 — Eliminate hidden real-clock constructor defaults where practical

Audit every production constructor introduced or modified by the migration for:

```kotlin
= SystemTimeProvider()
```

For each instance, choose and record exactly one disposition:

```text
REMOVE_DEFAULT_AND_UPDATE_CALLERS
EXPLICIT_TEST_SECONDARY_CONSTRUCTOR
REVIEWED_COMPATIBILITY_SEAM
```

Rules:
- an `@Inject` primary constructor should normally require `TimeProvider` explicitly;
- test construction should pass `FakeTimeProvider`, not silently create real system time;
- a compatibility seam must be narrow, documented, and covered by a deterministic test;
- do not add a time-guard exception for this issue because it is an injection/testability concern, not a direct platform-clock finding.

The current `CloudReceiptAssistService` includes a default `SystemTimeProvider()` to preserve a testing constructor; inspect actual call sites before changing it. ([github.com](https://github.com/panospao7/Cost-agregator/commit/8b45879e445e8750fcb9210bcf44b9a26e6468dd))

## Step 5 — Strengthen test fakes

Inspect existing fakes before adding duplicates.

`FakeTimeProvider` already supports fixed epoch time and controlled advancement, but its date helper uses default-zone `Calendar`. Add explicit-zone construction support if needed for deterministic tests, for example:

```kotlin
FakeTimeProvider.forLocalDateTime(
    year = 2024,
    month = 3,
    day = 10,
    hour = 1,
    minute = 30,
    zone = ZoneId.of("America/New_York")
)
```

Add or verify a test-only monotonic fake:

```kotlin
class FakeMonotonicTimeProvider(...) : MonotonicTimeProvider
```

It must:
- return controlled nanoseconds;
- support explicit advance;
- reject or prevent backward movement;
- have no platform clock dependency.

Do not put fakes in production source merely to satisfy tests.

## Step 6 — Build calendar compatibility tests before changing `TimePeriodUtils`

`TimePeriodUtils` currently claims:
- system-default-zone calendar semantics;
- half-open ranges;
- `Calendar.Builder` compatibility;
- a pre-Gregorian seam;
- controlled `Long`-extreme outcomes. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/app/src/main/java/com/yourname/expensetracker/domain/util/TimePeriodUtils.kt))

### Mandatory test matrix

Run every zone-dependent test under a temporarily fixed default zone, restoring the prior zone in `finally`/test rule.

Use at least:

```text
UTC
America/New_York
Europe/Athens
```

Test:

1. ordinary day start/end;
2. spring-forward day;
3. fall-back day;
4. leap day and leap-year month end;
5. Jan 31 month addition into February;
6. week start Monday and exclusive next-Monday end;
7. ISO week/year boundaries around January 1;
8. app-calendar week boundary behavior;
9. month/quarter/year offsets;
10. `daysBetween` across DST;
11. range containment at start, interior, end-minus-one, and exact end;
12. lenient month normalization where public APIs promise it;
13. pre-cutover dates before, at, and after October 15, 1582;
14. documented `Long.MIN_VALUE`/`Long.MAX_VALUE` behavior;
15. deterministic behavior across repeated runs with the same zone.

### Differential characterization requirement

Before claiming legacy compatibility, create a temporary parent-worktree evidence run against `588623d`:

```bash
git worktree add ../cost-aggregator-time-parent 588623d
```

Use a temporary, untracked focused harness to record selected public `TimePeriodUtils` inputs and outputs into:

```text
build/guard-debug/time-01/legacy-oracle.json
```

Then test current code against reviewed vectors.

Rules:
- do not edit/commit the parent worktree;
- do not delete the worktree without explicit approval;
- do not claim “bit-for-bit compatible” if the parent oracle cannot be run;
- if behavior differs, document whether it is:
  - required correction;
  - intended change needing approval;
  - regression that must be fixed.

## Step 7 — Validate converted domain/UI behavior by category

### A. Injected reference-time behavior

Add focused tests for:
- `AiOutputValidators`: exact `0`, exact `now + 365 days`, and one millisecond beyond;
- `CloudReceiptAssistService`: validation uses injected reference time;
- diagnostics/services: records use fake time exactly;
- notification/seeding paths: all generated timestamps are deterministic under a fake clock.

### B. Calendar-derived business behavior

Add focused tests for:
- `BillReminderSettings` quiet-hour windows, including overnight windows;
- `SynthesisEngine` month/day grouping and recurrence matching at month end;
- tax/savings/monthly sweep logic at leap-day, month-end, and DST boundaries;
- any changed parser using a “today/current year” decision.

### C. UI state behavior

For changed composables/screens:
- extract a small pure helper only if necessary;
- test month-grid leading cells, day count, and selected-date conversion;
- preserve the existing local-date/time-of-day contract;
- do not require screenshot tests merely because the implementation is Compose.

### D. File naming/persistence behavior

For timestamped file creation:
- use fake time;
- verify deterministic expected file prefixes;
- preserve collision/UUID behavior;
- do not remove entropy that existed before the migration.

## Step 8 — Test the guard against bypasses

The acceptance contract requires fully-qualified and alias-imported time APIs to be detected. Add adversarial tests before relying on a zero real-tree result. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/FINAL_CI_GUARD_ACCEPTANCE_GATE.md))

Required guard tests:

1. direct `System.currentTimeMillis()`;
2. fully-qualified `java.time.Instant.now()`;
3. alias import, for example `import java.time.Instant as AppInstant`;
4. alias `Calendar.getInstance()` import;
5. `typealias`/wrapper attempt if Kotlin syntax is supported by the scanner;
6. `System.nanoTime()` outside exact exception;
7. direct `Date()` but not `Date(epochMillis)` or `Date(timeProvider.now())`;
8. comments, strings, and imports do not create findings;
9. template expressions still detect real calls;
10. `Calendar.Builder().setInstant(...)` is not treated as a wall-clock read;
11. stale exception exits `2`;
12. malformed/wildcard exception exits `2`;
13. current real tree produces the expected exact exception evidence and no unapproved findings.

If an alias/typealias bypass is found, repair the scanner with:
- positive fixture;
- negative fixture;
- at least three bypass fixtures;
- malformed/infrastructure fixture;
- real-repository integration test.

Do not broaden exceptions to compensate for a scanner blind spot.

## Step 9 — Keep exception policy exact

Default expectation:

```bash
git diff --exit-code -- config/guards/time_boundary_exceptions.yml
```

If and only if a new direct clock read is architecturally unavoidable:
1. prove why Hilt/explicit injection cannot exist at that boundary;
2. add one exact path/class/method/API entry;
3. include reason, owner, and linked issue;
4. prove source evidence exists;
5. add a stale-entry regression test;
6. obtain strict review.

No expiry/permanent/wildcard fields are accepted by the current guard schema. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/config/guards/time_boundary_exceptions.yml))

## Step 10 — Re-run complete validation and GATE-00R

### Python

```bash
python3 -m pytest \
  scripts/test_verify_time_boundaries.py \
  -v --tb=short
```

```bash
python3 scripts/verify_time_boundaries.py \
  --root . \
  --allowlist config/guards/time_boundary_exceptions.yml \
  --fail-on-violation
```

Expected: exit `0`.

### Kotlin/Gradle, sequentially only

Use focused tests first:

```bash
./gradlew :app:compileDebugKotlin \
  --no-daemon --stacktrace --console=plain
```

```bash
./gradlew :app:testDebugUnitTest \
  --tests "*TimePeriodUtilsTest*" \
  --no-daemon --stacktrace --console=plain
```

Run the exact focused tests identified by the inventory, including forecast, synthesis, calendar/UI helper, and changed service tests.

Then:

```bash
./gradlew :app:testDebugUnitTest \
  --no-daemon --stacktrace --console=plain
```

Finally run:

```bash
python3 scripts/ci/run_static_guard_suite.py \
  --output-dir build/guard-debug/time-01/static-suite
```

Run GATE-00R again twice for the final target SHA.

If DB produces a trusted new finding after a valid time source change:
- record it;
- do not change the DB baseline;
- route to GR-08q.

---

# Preservation checks

```bash
git diff --exit-code -- \
  config/baselines \
  config/guards/db_ownership_policy.yml \
  config/guards/db_structural_exceptions.yml \
  config/guards/db_structural_exceptions_expected_methods.yml \
  config/guards/production_source_roots.yml \
  scripts/ci/guard_ratchet.py \
  scripts/ci/run_static_guard_suite.py \
  app/build.gradle.kts
```

Time exceptions should remain unchanged unless an individually proven unavoidable boundary requires one exact entry.

Review all production changes against:

```bash
git diff -- app/src/main
git diff --check
```

Every changed production file must appear in `PR-TIME-01_MIGRATION_REVIEW.yml`.

---

# Definition of done

PR-TIME-01 is complete only when:

- GATE-00R exact-SHA evidence exists before work;
- the migration inventory covers every relevant changed production file;
- direct wall-clock findings are zero;
- time guard infrastructure result is never `2`;
- all allowed exceptions are exact, live, and source-proven;
- elapsed-duration logic uses `MonotonicTimeProvider`;
- logical date/time logic uses `TimeProvider`;
- default-clock compatibility seams are explicitly reviewed;
- calendar behavior is tested across DST, zones, leap dates, month ends, week/year boundaries, and compatibility seams;
- focused and full affected Kotlin tests pass;
- two final GATE-00R runs reproduce the semantic time and DB results;
- no DB baseline/policy weakening occurred;
- no broad exception was added.

## Required completion report

```text
PR: TIME-01
START SHA:
END SHA:
GATE-00R INPUT MANIFEST SHA:
MIGRATION ANCHOR SHA: 8b45879e445e8750fcb9210bcf44b9a26e6468dd
MIGRATION REVIEW ENTRIES:
FILES WITH FIXES:
FILES VERIFIED WITHOUT CODE CHANGE:

TIME GUARD BEFORE:
TIME GUARD AFTER:
TIME EXCEPTION COUNT BEFORE/AFTER:
TIME EXCEPTIONS CHANGED: yes/no

MONOTONIC DURATION FIX:
DEFAULT SYSTEM CLOCK CONSTRUCTORS REVIEWED:
CALENDAR COMPATIBILITY ORACLE RESULT:
DST / ZONE MATRIX RESULT:
TIME PERIOD UTILS RESULT:
FOCUSED KOTLIN TESTS:
FULL UNIT TESTS:
COMPILE DEBUG KOTLIN:
STATIC SUITE TIME RESULT:
DB RESULT AFTER TIME CHANGE:

DB BASELINE CHANGED: no
DB POLICY CHANGED: no
PRODUCTION KOTLIN CHANGED: yes/no
UNEXPECTED DIFFERENCES:
FINAL GATE-00R REPRODUCIBLE: yes/no
NEXT PR:
```