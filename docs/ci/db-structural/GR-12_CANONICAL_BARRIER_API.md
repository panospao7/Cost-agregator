# GR-12 Canonical Barrier API Record

Status: recorded from production source at START_SHA `384905f62facb126b91fdccc321abe53c51f9082` (tree `17c20935acb965725f84ff1c478cf7003705ddfe`).

This document is the discovery record required by `docs/guardrails/PR-GR-12_direct_write_barrier_dominance_plan.md`
("Canonical barrier API contract"). The typed code contract lives in
`scripts/db_guard/structural_analysis/barrier_proof.py` and must be kept in sync with
the facts below. This record is documentation only; the code contract is authoritative.

## CanonicalBarrierContract — recorded facts

| Field | Value |
|---|---|
| `contractVersion` | 1 |
| `receiverFqcn` | `com.yourname.expensetracker.data.backup.DatabaseWriteBarrier` |
| `directCheckCallable` | `checkWritesAllowed(operation: String)` (compatibility overload) and `checkWritesAllowed(operation: DatabaseAccessOperation)` |
| `guardedScopeCallable` | `runWrite(operation: DatabaseAccessOperation, block: suspend () -> T): T` |

## Implementation identity

- Receiver file: `app/src/main/java/com/yourname/expensetracker/data/backup/DatabaseWriteBarrier.kt`
- Declared class: `@Singleton class DatabaseWriteBarrier @Inject constructor(restoreMaintenanceMode: RestoreMaintenanceMode)`
- No interface backing; the receiver is the implementation.
- Supporting types (same package, `DatabaseAccessModels.kt` / `RestoreMaintenanceMode.kt`):
  `DatabaseAccessOperation`, `DatabaseAccessType`, `DatabaseAccessBlockedException`, `RestoreMaintenanceMode`.

## Exact method signatures (source-verified)

```kotlin
fun checkWritesAllowed(operation: String)                    // L11 — delegates to typed overload
fun checkWritesAllowed(operation: DatabaseAccessOperation)   // L15 — canonical check
fun writesAllowed(): Boolean                                 // L29 — NOT a barrier (read-only probe)
suspend fun <T> runWrite(operation: DatabaseAccessOperation, block: suspend () -> T): T  // L33
```

## Barrier semantics

- `checkWritesAllowed(operation: DatabaseAccessOperation)`:
  reads `restoreMaintenanceMode.currentMode()`; throws
  `DatabaseAccessBlockedException(accessType = WRITE, operation, mode)` for every mode
  other than `RestoreMaintenanceMode.Mode.NORMAL`. Normal return therefore establishes
  write permission. Invocation is fully synchronous.
- `checkWritesAllowed(operation: String)`:
  compatibility overload; delegates to the typed overload. Both overloads are canonical
  barriers; no other overload of any name is.
- `runWrite(operation, block)`:
  calls `checkWritesAllowed(operation)` before invoking `block` exactly once
  (source lines 37–38), returns `block()`'s result. No launch/async, the lambda is not
  stored, returned, or passed onward. Semantically synchronous inside the coroutine:
  on normal return the check has passed and the block has run exactly once.
  The lambda is `suspend () -> T`; this exact contract (and only this one) is supported
  for `GUARDED_SCOPE` proof; any other callback shape is `UNSUPPORTED`.
- `writesAllowed()` / `RestoreMaintenanceMode.isWritesAllowed()` are never barriers.

## Overload rules

1. Only the two `checkWritesAllowed` overloads and the single `runWrite` signature above
   are barriers; all require the receiver to resolve to the canonical FQCN.
2. Same-name methods on any other receiver type are not barriers.
3. The receiver simple name `writeBarrier` alone is never sufficient.

## Runtime behavior proof (Kotlin test)

- `app/src/test/java/com/yourname/expensetracker/golden/RestoreBlocksAllWritesTest.kt`
  proves: writes allowed in `NORMAL` mode; every non-NORMAL mode
  (`RESTORE_PREPARING`, `BACKUP_EXPORTING`, `RESTORE_COMPLETE_RESTART_REQUIRED`,
  `RESTORE_SWAPPING`, and the full non-NORMAL sweep) throws instead of returning.
  This proves normal return occurs only after the check passes.

## Source-evidence location proving implementation identity

- `app/src/main/java/com/yourname/expensetracker/data/backup/DatabaseWriteBarrier.kt`
  (within the `production-kotlin-all` source scope of `config/guards/production_source_roots.yml`).

## Production usage census at START_SHA

- `checkWritesAllowed`: actively used across repositories/lifecycle coordinators
  (this is the form all 23 `barrierMode: direct` policy rows rely on).
- `runWrite`: declared but has zero production call sites at START_SHA. It remains in
  the contract so a future guarded scope is provable, but no active inventory entry may
  claim `GUARDED_SCOPE` from it today.

## Contract change policy

Any change to these facts (renames, new overloads, changed lambda semantics) requires a
dedicated reviewed diff that updates BOTH this document and
`scripts/db_guard/structural_analysis/barrier_proof.py`, with the contract tests
re-proving normal-return-after-check. No YAML allowlist may redefine the contract.
