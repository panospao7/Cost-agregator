# GR-13 evidence freeze (Slice S1)

Status: PARTIAL — evidence snapshot recorded; no mediation code changed. This
is a docs-only freeze. All mediation work remains **SHADOW-ONLY**.

## START_SHA choice

- Inventory `startSha`: `9d145697ae6d77da9d782ee6e529a876a723920d`
  (recorded in `GR-13_MEDIATION_INVENTORY.yml` and `GR-12.yml`, branch
  `gr-00-local`).
- Worktree HEAD at freeze time: `338eb3c5f937da3e596600e9285cc0e7b1856f35`
  (`docs(db): GR-12 Step 8 control-plane agreement and docs-truth sync`).

### Drift assessment: 9d145697 → 338eb3c5

The three commits between the two SHAs are GR-12 Step 7/8 work
(`185afc5c` feat Step 7, `ee4799f7` test receipt coverage, `338eb3c5` docs
Step 8). Per the inventory header, the GR-12 Step 7/8 working set touches
only `scripts/` and `docs/`; `app/src/main` and `config/` inputs are
byte-identical across the drift. The inventory's `startSha` therefore
remains valid for GR-13 correlation purposes; HEAD is recorded here for
traceability, not as a replacement start point.

## Input file hashes at freeze time

Content SHA-256 (recomputed at HEAD `338eb3c5`, all three match the values
pinned in `GR-13_MEDIATION_INVENTORY.yml` and `GR-12.yml` exactly — no
drift in policy/baseline/source-roots inputs):

| Input | SHA-256 | Matches pinned value |
|---|---|---|
| `config/guards/db_ownership_policy.yml` | `859643ec13e6c17f9b6ce8d054e708d06cbb135701a5721009a7e78216438c42` | yes |
| `config/baselines/db_access_v2.json` | `44421412c890b60d17db9a4157c497cf44ec836753d195eeb95783ff8ccebfec` | yes |
| `config/guards/production_source_roots.yml` | `02ec3e28c9451cecb31ca90dc8f665837b7857d979c20b3a400f898f8d1c2c95` | yes |

Git blob SHAs (SHA-1, `git hash-object`, for change-detection only — not
interchangeable with the SHA-256 content hashes above):

| Input | blob SHA-1 |
|---|---|
| `config/guards/db_ownership_policy.yml` | `1b7fb4707f68a07f81a55ae6a524d0324fa7be93` |
| `config/baselines/db_access_v2.json` | `83675093dd1c6953e566291d1159e1fae332c403` |
| `config/guards/production_source_roots.yml` | `271d01e22d86190776b1125b16fa5c32eba10a75` |

## Inventory correlation state

- `policyHelperWorkerRows: 449` — all rows `currentProofStatus: PENDING`
  (sampled rows verified; no row has been flipped to PROVEN by this slice).
- `d4ResolvedObservationsTotal: 561`.
- `incomingCallSiteCount: null` on all rows pending GR-13 Step 4 (reverse
  call-site index), per the documented deviation in the inventory header.

### Uncorrelated rows (2)

Two active-policy DB mutations observed in source that do not correlate to
any of the 449 policy rows:

1. `DataRetentionWorker.doWork` → `privacyAuditDao.insert`
   (`app/src/main/java/com/yourname/expensetracker/data/privacy/DataRetentionWorker.kt`,
   dao acquired at line ~183, `PrivacyAuditEvent` inserts at ~196+).
   Privacy-audit bookkeeping written by the retention worker itself.
2. `WorkerRunLoggerImpl.start` → `backgroundJobRunDao.insert`
   (`app/src/main/java/com/yourname/expensetracker/domain/workers/WorkerRunLogger.kt`,
   `start(...)` at line ~92, `dao.insert(BackgroundJobRun(...))` at ~102).
   Run-log start row written inside the guard's own `startRunSafely` path.

Both are guard/audit infrastructure writes, not feature mutations. They
must be dispositioned explicitly (baseline exception, policy row, or
documented infra exemption) before GR-13 can close; they are recorded here
so the gap is visible and cannot be silently dropped.

## Registry vs source worker gap

- `WorkerRegistry.entries` registers **7** workers:
  `location_backfill`, `merchant_key_backfill`, `warranty_expiration_check`,
  `data_retention`, `bill_reminder_periodic`, `receipt_matching`,
  `ai_daily_briefing`.
- Source contains **10** worker classes that call the canonical guard
  (`runGuardedWithContext`): the 7 registered workers plus
  `NotificationIntakeWorker`, `SnoozeReminderActionWorker`,
  `DismissReminderActionWorker` (event/action-triggered, not startup-
  scheduled — plausibly correct, but the gap must be explicitly justified,
  not assumed).
- Additionally `SourceLinkBackfillWorker` exists in source and calls
  neither `runGuarded` nor `runGuardedWithContext` — it is outside the
  guard surface entirely and needs its own disposition.

Gap disposition is owed before GR-13 completion; this freeze only records it.

## SHADOW-ONLY statement

**All GR-13 mediation work is SHADOW-ONLY at this freeze.** No production
code path has been changed, no mutation has been re-rooted, no policy row
has been flipped to PROVEN, and no enforcement gate has been tightened or
relaxed by this slice. The inventory remains a REVIEW LEDGER, not
authorization. Any future activation step requires its own manifest,
review, and guardian gate.

## Contract re-verification

Re-verified from source at HEAD `338eb3c5` (not from memory or name
matching):

- Receiver FQCN: `com.yourname.expensetracker.domain.workers.WorkerExecutionGuard`
  (`@Singleton class WorkerExecutionGuard @Inject constructor(...)`,
  concrete class, no interface backing) — matches
  `GR-13_WORKER_GUARD_CONTRACT.md`.
- Exact scope methods (2, no other overload):
  1. `suspend fun <T> runGuarded(request: WorkerGuardRequest, block: suspend () -> T): WorkerGuardResult<T>` (line ~98)
  2. `suspend fun <T> runGuardedWithContext(request: WorkerGuardRequest, block: suspend (WorkerRunContext) -> T): WorkerGuardResult<T>` (line ~276)
- Both take `WorkerGuardRequest` as the first parameter with the scope
  lambda trailing, per the contract record.

## Validation

- command: none (docs-only slice; Gradle not run per instruction)
- result: NOT RUN
- notes: hash recomputation and source reads were performed via shell
  (`git rev-parse`, `git hash-object`, `Get-FileHash`) and direct file
  reads; no build or test execution.
