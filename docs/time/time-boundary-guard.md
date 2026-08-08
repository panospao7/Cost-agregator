# Time Boundary Guard (G-TIME-01)

## Overview

`scripts/verify_time_boundaries.py` is the canonical static guard that enforces
the direct wall-clock boundary for the ExpenseTracker app (PR-GR-02). It
replaces the old inline Gradle scanner (`checkDirectTimeCalls`), which used
broad source-line substring exemptions (`contains("now()")`,
`contains("now =")`, `contains("TimeProvider(")`) and silently accepted calls
it was supposed to reject.

Production Kotlin under `app/src/main/java` must obtain "now" exclusively
through `TimeProvider` (injected `SystemTimeProvider`). Direct wall-clock reads
outside the exact clock-adapter exceptions are violations.

## Guarded APIs

| API | Meaning |
|-----|---------|
| `System.currentTimeMillis()` | wall-clock epoch millis |
| `System.nanoTime()` | monotonic nanos — **only** authorized for exact elapsed-duration adapters |
| `Date()` | no-arg wall-clock constructor |
| `Calendar.getInstance()` | wall-clock calendar |
| `Instant.now()` | wall-clock instant |
| `LocalDate.now()` | wall-clock local date |
| `LocalDateTime.now()` | wall-clock local date-time |
| `OffsetDateTime.now()` | wall-clock offset date-time |
| `ZonedDateTime.now()` | wall-clock zoned date-time |
| `Clock.systemDefaultZone()` | system-default-zone clock |
| `Clock.systemUTC()` | system-UTC clock |

## Scanner behavior

- Scans production sources only (`app/src/main/java/**/*.kt`).
- Masks comments, string literals, raw strings and character literals before
  matching — an API name in a comment or ordinary string is **not** a
  violation.
- String template expressions (`"${...}"`) are **preserved** and still
  detected, because they are executable code.
- Imports are naturally ignored (the API token sequences require the call form).
- Resolves the enclosing class and method for each finding and emits
  deterministic fingerprints:

  ```text
  G-TIME-01 app/src/main/java/com/.../File.kt:42 SomeClass.someMethod direct wall-clock Instant.now() — derive from TimeProvider.now()
  ```

- `System.nanoTime()` is **not** automatically exempt. It is detected like any
  other API; only an exact, source-verified exception entry authorizes a
  monotonic elapsed-duration adapter. Using `System.nanoTime()` for unique
  names (temp files, IDs) is a violation.

  Unique-name generation must use UUIDs instead of the clock:
  `ReceiptOcrService` temp files and `AssistantViewModel` conversation IDs use
  `UUID.randomUUID()`, so no exception entry is needed for them.

## Exit codes

| Code | Meaning |
|------|---------|
| 0 | pass (no violations), or violations reported without `--fail-on-violation` |
| 1 | violations found **and** `--fail-on-violation` was given |
| 2 | infrastructure error: missing/malformed/unreadable policy or source, empty source tree, parser error, or a **stale exception** entry |

The guard fails closed:

- missing exceptions policy → exit 2;
- malformed / empty / wildcard / missing-field policy entries → exit 2;
- empty or missing source tree → exit 2;
- unreadable source file → exit 2;
- every exception entry must match real, detected source evidence, otherwise
  the entry is stale and the guard exits 2.

There is **no baseline** for time violations and no broad source-line
exemptions.

## Exception policy

The canonical exceptions file is `config/guards/time_boundary_exceptions.yml`.
Exact entries only — no wildcard paths/methods/apis:

```yaml
version: 1
exceptions:
  - path: app/src/main/java/com/yourname/expensetracker/domain/util/SystemTimeProvider.kt
    class: SystemTimeProvider
    method: now
    api: System.currentTimeMillis
    reason: "Canonical platform clock adapter — the single production implementation of TimeProvider"
    owner: "@panospao7"
    linked_issue: "MIT-003"
```

Current exact exceptions (source-verified):

| Path | Class | Method | API |
|------|-------|--------|-----|
| `app/src/main/java/.../domain/util/SystemTimeProvider.kt` | `SystemTimeProvider` | `now` | `System.currentTimeMillis` |
| `app/src/main/java/.../domain/groups/SettlementCalculator.kt` | `SettlementCalculator` | `findMinimalTransferPlan` | `System.nanoTime` (elapsed-duration solver budget) |
| `app/src/main/java/.../data/database/AppDatabase.kt` | `MIGRATION_16_17` | `migrate` | `System.currentTimeMillis` (Room migration 16→17 data seeding during DB open) |
| `app/src/main/java/.../data/database/AppDatabase.kt` | `MIGRATION_41_42` | `migrate` | `System.currentTimeMillis` (Room migration 41→42 data seeding during DB open) |

### AppDatabase migration exceptions — exact reasoning

There are exactly two direct `System.currentTimeMillis()` reads in
`AppDatabase.kt`, and both are genuinely unavoidable Room migration lambdas:

1. `MIGRATION_16_17.migrate` (line 536) seeds `merchant_canonicals`
   `createdAt`/`updatedAt` for existing expense rows.
2. `MIGRATION_41_42.migrate` (line 1352) seeds the default
   `exchange_rates.lastUpdated` for the built-in currency pairs.

Both execute inside Room's `openHelper` during DB open, **before** any Hilt
`TimeProvider` injection exists: `Migration` objects are static singleton
instances registered via `addMigrations(...)` and invoked by Room itself.
There is no way to pass an injected clock into a migration lambda.

To keep the exceptions **exact** (one entry per call site), the two migration
declarations are **named objects** (`object MIGRATION_16_17 : Migration(16, 17)`)
rather than anonymous `val X = object : Migration(...)`. The scanner therefore
attributes each call to its own class (`MIGRATION_16_17.migrate`,
`MIGRATION_41_42.migrate`) instead of a shared `AppDatabase.Companion.migrate`
bucket. There is deliberately **no** broad `AppDatabase.Companion`/`migrate`
exception — a broad entry could mask a future migration lambda that adds an
unreviewed wall-clock read.

`FRESH_INSTALL_CALLBACK` needs **no** exception entry: it only rebuilds tables
from existing rows (CREATE-new, INSERT…SELECT, DROP-old, RENAME) and never
calls any wall-clock API.

To add an exception:

1. Verify the usage is a genuine clock adapter or a monotonic elapsed-duration
   measurement (not a wall-clock read, not unique-name generation).
2. Add an exact entry with `path`, `class`, `method`, `api`, `reason`,
   `owner`, and `linked_issue`. The class/method must match the scanner's
   attribution (`Class.method` from the violation line).
3. Do **not** add file-level or wildcard entries. Do not add entries that do
   not match real source evidence — the guard rejects stale entries with
   exit 2.

## Running locally

```bash
# Report-only mode (still exits 0 when violations exist)
python3 scripts/verify_time_boundaries.py --root .

# Fail-closed mode (CI uses this)
python3 scripts/verify_time_boundaries.py --root . \
  --allowlist config/guards/time_boundary_exceptions.yml \
  --fail-on-violation

# Tests
python -m pytest scripts/test_verify_time_boundaries.py -v
```

## Gradle integration

`:app:checkDirectTimeCalls` is a fail-closed wrapper around the script (it is
wired into `:app:check`):

```bash
./gradlew :app:checkDirectTimeCalls --stacktrace
```

The wrapper validates the required inputs (script + exceptions policy),
performs a Python preflight (`-PpythonExecutable=/path/to/python3` contract),
and converts exit 1 / exit 2 into `GradleException`. Missing script or policy,
or any nonzero result, fails the build.

## CI integration

The guard is registered as a **blocking** guard in
`scripts/ci/guard_registry.py` and runs in the `static-guards` job through
`scripts/ci/run_static_guard_suite.py`:

```text
time_boundaries → python3 scripts/verify_time_boundaries.py --root . \
    --allowlist config/guards/time_boundary_exceptions.yml --fail-on-violation
```

A violation (exit 1) or infrastructure error (exit 2) fails CI.

## Deferred Findings

The following direct-time findings are **not authorized** by any exception or
baseline. They are tracked for remediation in Time Batch T2–T6.

### ReceiptAssetStore — deferred to T2

`ReceiptAssetStore.kt:47,69` — `System.currentTimeMillis()` used directly for
temp and persisted receipt naming. No exception or baseline authorizes these
calls. They are deferred to **Time Batch T2** for migration to UUID-based or
`TimeProvider`-backed naming.

### Remaining guard-reported findings — pending T2–T6

All other guard-reported direct-time findings outside the exact exception
entries above remain **pending remediation** in batches T2 through T6. None
are authorized. Do not add baselines, blanket exceptions, or source-line
exemptions for these findings.

### T1 scope (already authorized)

T1 scope covers only the following authorized exceptions:

1. `AppDatabase.kt` — two **separate exact entries** for the two Room migration
   lambdas that seed data with `System.currentTimeMillis()` (DB open, before
   Hilt `TimeProvider` injection):
   - `MIGRATION_16_17.migrate` (seeds `merchant_canonicals` timestamps);
   - `MIGRATION_41_42.migrate` (seeds default `exchange_rates.lastUpdated`).
   `FRESH_INSTALL_CALLBACK` performs no direct clock reads and needs no entry.
2. `ReceiptOcrService` — `UUID.randomUUID()` for temp-file naming (not a
   clock call; no exception entry needed).
3. `AssistantViewModel` — `UUID.randomUUID()` for conversation IDs (not a
   clock call; no exception entry needed).

No broad exceptions or baselines extend beyond these.

## Completed Batches

### Time Batch T2B — DatabaseBackupRepositoryImpl (complete)

`DatabaseBackupRepositoryImpl.kt` previously contained six remaining direct
wall-clock reads used for filename / staging / restore naming. Time Batch
**T2B** remediated all six. **No** time exception or baseline was added.

Semantically timestamped, user-visible backup/safety names now derive their
timestamp from the injected `TimeProvider.now()` via
`Instant.ofEpochMilli(...).atZone(ZoneId.systemDefault()).toLocalDateTime()`
(no `LocalDateTime.now()`):

- `exportDatabase` (legacy debug raw DB export) — `expense_tracker_backup_<timestamp>.db` / `.enc`
- `createCostBackup` — output bundle `expense_tracker_backup_<timestamp>_<uuid>.costbackup`;
  `<timestamp>` comes from the same single `TimeProvider.now()` capture passed to
  `CostbackupBundle.create` as `nowEpochMs`
- `createSafetyBackupInternalAssumingMaintenance` — `expense_tracker_backup_SAFETY_<timestamp>.db`

Uniqueness-only names now use `UUID.randomUUID()` (no
`System.currentTimeMillis()`); prefixes, file extensions, cleanup matching,
restore-journal `stagedDbPath`/`extractTempDirPath` persistence, and path
safety are unchanged:

- `exportDatabase` — internal `temp_export_<uuid>.db` sanitize/encrypt copy
- `createCostBackup` — snapshot temp `costbackup_snapshot_<uuid>.db`
- `restoreCostBackup` — staging DB `expense_tracker_db_import_stage_<uuid>`
- `restoreCostBackup` — extract temp dir `costbackup_extract_<uuid>`
- `importDatabase` (legacy debug import) — staging DB `expense_tracker_db_import_stage_<uuid>`

A static source test in `DatabaseBackupRepositoryImplTest` asserts that no
`System.currentTimeMillis()` / `LocalDateTime.now()` remains in the file, and
focused tests assert the timestamped filenames are derived from a deterministic
`FakeTimeProvider` and that staging names use UUID prefixes.

Later Time Batch work (T2 ReceiptAssetStore and all T2–T6 findings listed above)
remains **pending** — it is not marked complete by this batch.

### Time Batch T3C — DefaultAiEnvironmentMonitor TTL cache (complete)

`DefaultAiEnvironmentMonitor` previously used a raw
`System.currentTimeMillis()` call to manage its TTL cache freshness check.
Time Batch **T3C** replaced this with the injected `TimeProvider` so the cache
is now fully testable and consistent with project time semantics.

Boundary semantics:

- `< 1500ms` since last check → **fresh**, cache entry reused.
- `>= 1500ms` since last check → **refresh**, monitor re-evaluates environment.

Deterministic `FakeTimeProvider` tests cover:

- **fresh** — elapsed time below the 1500 ms threshold;
- **exact boundary** — elapsed time exactly at the 1500 ms threshold (triggers refresh);
- **past boundary** — elapsed time well beyond the threshold;
- **advancement** — time moves forward across multiple checks, verifying the cache
  transitions from fresh to stale.

**No** time exception or baseline was added. The class obtains time exclusively
through `TimeProvider` and the existing guard exception list is unchanged.

Later time findings outside T3C scope remain **pending** in subsequent batches.

### Time Batch T4A Tier 2 — Analytics weekday / DST semantics (complete)

T4A replaces the analytics engines' direct `java.util.Calendar` day-of-week
reads with `java.time` / `TimePeriodUtils` while preserving each engine's
documented weekday convention. Tier 1 migrated the production reads; **Tier 2**
adds boundary and DST regression coverage on the real `AdvancedAnalyticsDashboard`
path and documents the weekday mapping each engine uses.

Weekday mapping by engine:

- **`SpendingPersonalityClassifier`** — `calculateWeekendSpendShare` uses
  `TimePeriodUtils.getDayOfWeek` (Calendar style: Sunday=1 … Saturday=7) and
  compares against `Calendar.SATURDAY`/`Calendar.SUNDAY` to preserve the
  original weekend semantics. `calculateNightSpendShare` uses
  `TimePeriodUtils.getHourOfDay` (0–23, system zone) for the `>= 8 PM` night
  threshold. Both helpers are pure and seeded from the transaction timestamp —
  no wall-clock reads, so **no exception entry is needed**.
- **`DayOfWeekAnalyzer`** — `analyze` uses `java.time.DayOfWeek.value`
  (Monday=1 … Sunday=7); subtracting 1 yields the **Monday=0 … Sunday=6**
  `dayIndex` that indexes `DAY_NAMES` (Mon…Sun). Stable Mon→Sun output order
  is unchanged.
- **`AdvancedAnalyticsDashboard`** — `getWeeklyPattern` uses
  `java.time.DayOfWeek.value` directly for the `weeklyPattern` buckets
  (**Monday=1 … Sunday=7**, matching `DayOfWeekSpending.dayOfWeek` and the
  1→Monday/7→Sunday `dayNames` map). `generateInsights` compares
  `SATURDAY`/`SUNDAY` via `java.time.DayOfWeek` to preserve the weekend
  spending insight.

Fixed DST / boundary regression tests added (Tier 2):

- `AdvancedAnalyticsDashboardTest` — Sunday purchases map to `dayOfWeek == 7`
  and Monday to `dayOfWeek == 1` through the real `generateDashboardData` →
  `getWeeklyPattern` path; deposits are excluded from the pattern.
- `AdvancedAnalyticsDashboardTest` — DST spring-forward
  (`America/New_York`, Sunday 2026-03-08): fixed instants around the
  02:00→03:00 transition are asserted to be exactly one real hour apart
  (23-hour day), both pre/post-transition purchases still map to Sunday
  (`dayOfWeek == 7`), the following Monday maps to `dayOfWeek == 1`, and the
  weekend `SPENDING_PATTERN` insight still fires.
- Existing Tier 1 boundary tests remain: `DayOfWeekAnalyzerTest`
  (Sunday/Monday boundary → `dayIndex` 6/0, DST spring-forward) and
  `SpendingPersonalityClassifierTest` day/hour coverage.

**Explicitly still pending (T4B and later):**

- `AdvancedAnalyticsDashboard.getMonthlyTrend` — the complex month cursor
  (A18: replace the `java.util.Calendar` month iteration with
  `java.time.ZonedDateTime` + `ZoneId.systemDefault()`).
- CashFlow / Budget engine Calendar loops (e.g. `AdvancedAnalyticsEngine`
  weekday mapping and weekend classification still read `Calendar` constants).
- `TimePeriodUtils` internal `Calendar`-based strategy migration to
  `java.time`.

This batch is **not** a claim that all T4 time work is complete — the items
above remain pending.

## Related documentation

- `docs/development/TIME_SEMANTICS.md` — time semantics developer rules.
- `docs/ci/guard-policy.md` — fail-closed guard policy.
- `config/guards/time_boundary_exceptions.yml` — the canonical exceptions file.
