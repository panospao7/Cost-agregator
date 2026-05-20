I re-ran the static deep review for commit `aeebe3f`. Full report:

<durable_diagnostics_deep_review_aeebe3f.md>
# Durable Diagnostics Deep Review — Commit `aeebe3f`

Commit reviewed: `aeebe3f667cd56bb7c445fc8aaba249ab84dfb26`  
Previous reviewed commit: `c67c2c8236bbc43553cbb9c0c96ca339afe2515a`

Mode: static source review from GitHub. I did **not** execute Gradle, Room migration tests, Android tests, or instrumentation tests.

---

## Executive verdict

This commit fixes several of the latest DDL-C67 items, especially:

- `SafeSinkOperationRunHandle.terminalOnce()` no longer double-CAS-skips terminal events.
- safe operation handle counters are accumulated.
- stale recovery event insert failures now write safe-sink diagnostics.
- `putHashed()` rejects unapproved hash-like keys.
- safe-sink recent failures include severity-only failures.
- reset journal now begins before `RESET_STARTED`.
- legacy import post-swap failure now emits terminal restore events.
- transaction bulk/delete correlation is improved.
- `RestoreJournal.renameTo()` fallback handling was added.

However, the system is **still not fully done**. Main remaining issues are:

1. Restore journal write/rename failures are still mostly swallowed; destructive restore/reset can proceed without a guaranteed durable journal.
2. Restore diagnostics are still not privacy-safe by construction because raw journal files still contain `_sourceBackupPath`, `_stagedDbPath`, etc.
3. Asset restore recovery path is still weakened by storing only `targetName`.
4. Transaction mutation/side-effect correlation is still partial.
5. Safe operation terminal events now emit, but failed/partial terminal metadata still loses the supplied reason/summary.
6. Safe-sink fallback diagnostics for operation event/increment/stale failures are hardcoded to `BACKUP_RESTORE`, even for bank/email/import operations.
7. Tests remain too synthetic; many still test helpers/test doubles rather than production classes.

---

# 1. What is now resolved or mostly resolved

## 1.1 SafeSinkOperationRunHandle terminal emission

Status: **mostly resolved**

Previous critical bug:

```kotlin
terminalOnce()
  -> sets _isTerminal = true
  -> calls event(... isTerminal = true)
  -> event sees terminal already true and returns
```

Now fixed:

```kotlin
terminalOnce()
  -> compareAndSet(false, true)
  -> emitSafeEvent(...)
```

Good:
- `success()`
- `cancelled()`
- `failedFinal()`
- `failedRetryable()`
- `partialSuccess()`

should now emit a terminal safe-sink event.

Remaining:
- `failedFinal(reason, error)` and `partialSuccess(summary)` still drop the supplied reason/summary from metadata.
- counters included are only processed/succeeded/failed/skipped; warnings/errors are not accumulated.

File:
- `CompositeOperationRunRecorder.kt`

---

## 1.2 Bank restore-blocked terminal policy

Status: **mostly resolved**

Bank sync now uses:

```text
WRITE_BARRIER / BLOCKED / non-terminal
CANCELLED / RESTORE_BLOCKED / terminal
```

This is the correct shape.

Remaining:
- because `CompositeOperationRunRecorder.runOperation()` still calls `success()` after the block unconditionally, correctness depends on the handle’s terminal guard. The guard now works, so this is okay but should be regression-tested with the real safe handle.

File:
- `BankApiIntegration.kt`

---

## 1.3 Stale recovery durability

Status: **mostly resolved**

Good:
- stale recovery event gets `eventId`
- stale recovery event insert failure now records a safe-sink diagnostic

Remaining:
- the safe-sink diagnostic hardcodes `pipeline = BACKUP_RESTORE`, even if the stale operation was `BANK_SYNC`, `EMAIL_BATCH_IMPORT`, `EXPENSE_EXPORT`, etc.

File:
- `OperationRunRecorder.kt`

---

## 1.4 Safe handle counters

Status: **partially resolved**

Good:
- safe handle now accumulates:
  - processed
  - succeeded
  - failed
  - skipped
- terminal event includes those counters.

Remaining:
- warnings/errors parameters are ignored.
- increments are not emitted as intermediate safe events; only terminal summary has counts. This is acceptable short-term, but long safe-mode operations can still lose progress if process dies before terminal event.

File:
- `CompositeOperationRunRecorder.kt`

---

## 1.5 `putHashed()` hardening

Status: **mostly resolved**

Good:
- unapproved keys ending with `hash` are redacted at construction time.
- `packageHash` is treated as approved hash key

:warning: The provider stream ended early, so this response may be incomplete.