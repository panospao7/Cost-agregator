# GR-12 Canonical Barrier API Record

Status: recorded from production source at START_SHA `384905f62facb126b91fdccc321abe53c51f9082` (tree `17c20935acb965725f84ff1c478cf7003705ddfe`).
Contract version 2 (synchronous transparent scopes) added by the GR-14b batch;
the v1 record below is unchanged and still authoritative for the barrier API itself.

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

---

# Contract version 2 — synchronous transparent scopes (GR-14b)

## Recorded facts

| Field | Value |
|---|---|
| `contractVersion` | 2 |
| `receiverFqcn` / checks / scopes | unchanged from v1 (`checkWritesAllowed`, `runWrite`) |
| `transparentScopeWrappers` | `withTransaction`, `runInTransaction`, `withContext` (table below) |

## Wrapper table (source-verified admission conditions)

| Method | Receiver FQCNs (exact) | Required import | Source verification |
|---|---|---|---|
| `withTransaction` | `androidx.room.RoomDatabase`, `com.yourname.expensetracker.data.database.AppDatabase` | — | Room extension `suspend fun <R> RoomDatabase.withTransaction(block: suspend () -> R): R`; external Room stdlib semantics: block runs exactly once, sequentially, inside the transaction, before return. `AppDatabase : RoomDatabase()` is the project's only RoomDatabase subclass. |
| `runInTransaction` | `com.yourname.expensetracker.domain.transaction.DomainTransactionRunner` | — | Project interface; sole implementation `RoomDomainTransactionRunner.runInTransaction` (app/src/main/java/com/yourname/expensetracker/data/database/RoomDomainTransactionRunner.kt) invokes `block(context)` exactly once inside `database.withTransaction { }` and rethrows `CancellationException` unaltered. |
| `withContext` | (receiverless) | `kotlinx.coroutines.withContext` (exact import required) | kotlinx.coroutines stdlib: the block runs to completion before `withContext` returns; sequential in the caller's continuation (thread may differ; the write barrier check is thread-independent state). |

## Proof semantics (the key v2 decision)

A transparent-scope wrapper does NOT check the write barrier itself. The contract
guarantees its lambda executes exactly once, sequentially, before the wrapper
returns — no launch/async, no storage, no escape, no deferred invocation. The
dominance proof therefore composes:

```text
caller's canonical checkWritesAllowed  →  (dominates)  →  wrapper call site
wrapper call site                      →  (wired CFG)  →  every mutation in the lambda body
```

The CFG wires admitted scopes' bodies into the caller's flow (scope entry →
children → continuation). This satisfies GR-12 Section B: exact resolved
invocation (admission step in the proof layer), mutation inside the exact lambda
span, lambda not passed onward (contract source verification above), and
guard-check-precedes-invocation via dominance at the call site — stronger than
the GUARDED_SCOPE lexical shortcut, which relies on the contract only.

Admission conditions (all required, fail closed):
1. statement is a trailing-lambda call of a wrapper method (syntactic candidate,
   tokenizer, opt-in via `transparent_scope_methods`);
2. the receiver resolves to one of the wrapper's exact receiver FQCNs — or, for
   `withContext`, the file's import table contains exactly
   `kotlinx.coroutines.withContext`;
3. the candidate span is in the admitted set passed to `build_callable_cfg`
   (`admit_transparent_scope_candidates`). Non-admitted candidates build as
   disconnected scopes; their mutations stay `UNSUPPORTED`.

`return <wrapper>(...) { ... }` parses as an explicit RETURN wrapping the
TRANSPARENT_SCOPE child (contract-backed; replaces the accidental v1
return-leaf inlining). `return@label` inside an admitted scope is
lambda-local when the label matches the wrapper method at lambda depth 0;
any other label, or a labelled return from inside a nested lambda, fails
closed. `launch`/`async`/`runBlocking` keep failing as before.

## v2 change record

- Batch: GR-14b (`docs/ci/db-mediation/GR-14b.yml`).
- Code: `CanonicalBarrierContract.transparent_scope_wrappers`,
  `TransparentScopeWrapper`, `admit_transparent_scope_candidates`
  (barrier_proof.py); tokenizer TRANSPARENT_SCOPE/LAMBDA_RETURN (opt-in);
  cfg transparent wiring (`admitted_transparent_spans`); proof CLI V2 pipeline.
- Contract tests: `scripts/db_guard/structural_analysis/test_transparent_scopes.py`.
