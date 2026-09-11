# RP-02 - Write-barrier guard and writer ownership

> **Scope:** universal restore/write-barrier enforcement, DAO-writer registration, and the affected notification, provenance, recurring, and retention entry points. **Mode:** strict.
> **Dependencies:** RP-01 changes the cancellation guard and may move source lines; rebase this work on RP-01 rather than duplicating its edits. RP-03 owns backup/restore state-machine behavior. RP-14 owns the retention target perimeter and purge semantics.
> **Ordering:** land the guard and writer ownership changes before RP-14. Coordinate the restore-mode contract with RP-03 before integration validation.

## Confirmed findings

### U-004 - The write-barrier architecture guard is structurally evadable

`WriteBarrierArchitectureGuardTest` currently recognizes only receiver names derived from the DAO interface and a narrow `database.<daoAccessor>()` form. It therefore misses these production writers:

- `NotificationIntakeCoordinator`: `intakeDao.insertOrIgnore` in `capture()` and `captureForRetry()`; no local barrier ownership.
- `SourceLinkWriterImpl`: `sourceLinkDao.insert` in `linkTarget()`; no local barrier ownership.
- `DataRetentionWorker`: local `auditDao = appDatabase.privacyAuditDao()` followed by two audit inserts; the local alias is not covered by the current caller patterns.
- `RecurringOccurrenceMaterializer`: `plannedExpenseDao.fulfillByOccurrenceKey` and direct recurring lifecycle-event inserts; it has no barrier. This is a real violation, not an exemption candidate.

The class-level guard must detect ownership independently of the receiver variable name. Method-level caller matching remains useful as a secondary check, but it must not be the primary proof of protection.

### Direct recurring event writes

`RecurringOccurrenceMaterializer` directly inserts `RecurringLifecycleEvent`. `LEGAL_PATHS.md` requires critical recurring events to go through `RecurringLifecycleEventWriter`, and the recurring architecture guard must continue to enforce that rule. Do not describe the materializer as protected by `DirectEventDaoInsertGuardTest`'s allowlist: that allowlist belongs to a different guard and does not fix write-barrier coverage. Route critical event writes through the writer API that is safe inside the current Room transaction, or make the ownership decision explicit and update the direct-event guard without weakening it.

### Refuted finding: source-link uniqueness

Do not add a schema/index remediation for the earlier source-link TOCTOU claim. `EntitySourceLink` already has a unique index on `(targetEntityType, targetEntityId, sourceIdentityKey)`, and `EntitySourceLinkDao.insert` uses `OnConflictStrategy.IGNORE`. The `exists()` fast path is racy but harmless: the insert conflict returns the existing-result branch and duplicates are prevented at SQLite level. RP-02 still adds barrier ownership to `SourceLinkWriterImpl` and covers its unbarriered callers.

## Implementation

### 1. Upgrade `WriteBarrierArchitectureGuardTest`

Keep the existing DAO registry, including `@Insert`, `@Update`, `@Delete`, mutating `@Query`, and directly write-calling `@Transaction` methods. Replace or augment `buildCallerRegexes` with a deterministic class-level contract:

1. For every production Kotlin file, identify constructor-injected DAO fields using the registered DAO interface names, allowing an optional fully-qualified type and arbitrary field alias. The pattern must recognize declarations such as `private val intakeDao: NotificationIntakeDao` and `private val plannedExpenseDao: com.yourname.expensetracker.data.database.dao.PlannedExpenseDao`.
2. Identify local DAO aliases created from `appDatabase`, `database`, or `db`, without requiring the declaration to be at class scope. Recognize `val auditDao = appDatabase.privacyAuditDao()` and equivalent registered DAO accessors.
3. For each class containing a registered DAO writer field/local or a direct registered DAO accessor write, require either executable barrier ownership or a justified exemption. Ownership evidence must be a reference to `DatabaseWriteBarrier`/`checkWritesAllowed`, `executionGuard`, or a verified checkpoint/guard wrapper in the same class. A caller's presumed upstream check is not sufficient.
4. Change `EXEMPT_CLASSES` from `Set<String>` to `Map<String, String>` (`className -> non-empty reason`). Membership uses `.keys`; stale-entry checks and diagnostics must use the map keys. A bare name, empty reason, or whitespace-only reason fails the test. Keep exemptions minimal and shrinking; do not add the materializer, intake coordinator, source-link writer, or retention worker.
5. Retain the current method-level regexes as an additional check for aliases/accessor forms not captured by the class contract. A class-level match must not make an otherwise unregistered direct DAO write invisible.
6. Keep non-vacuity checks for source files, DAO count, barrier-protected classes, and known protected classes. Add fixtures for injected aliases, local database aliases, direct accessors, barrier ownership, justified exemption, and missing/blank exemption reason.

The guard must report the exact source file, class, DAO, method, and reason for every violation. It must not infer protection from a caller list or from a comment/string-only mention.

### 2. Establish structural ownership at every writer entry point

Use the constructor parameter name `writeBarrier` wherever a `DatabaseWriteBarrier` is injected so source guards can recognize it. Place checks at the owning entry point and preserve the existing typed restore-blocking behavior; do not replace a barrier exception with a generic failure or silently continue.

| Writer / entry point | Required ownership | Required details |
|---|---|---|
| `NotificationIntakeCoordinator.capture()` and `captureForRetry()` | Inject `DatabaseWriteBarrier` as `writeBarrier` | Check immediately before `intakeDao.insertOrIgnore`. Check again after a successful insert and immediately before any enqueue-related durable mutation/operation if that path can write. Preserve the existing `DO_NOT_STORE` behavior and do not use this plan to reintroduce content-bearing deferred capture. The check must be owned by the coordinator, not only by `NotificationCaptureService`. |
| `SourceLinkWriterImpl.linkTarget()` and delegated source-link paths | Inject `DatabaseWriteBarrier` as `writeBarrier` | Check immediately before `sourceLinkDao.insert`. `linkExpense()` and `linkExpenseSourcesFromRequest()` must not create an unguarded alternate path. Preserve caller-owned transaction semantics and the existing unique-index/`IGNORE` result handling. Inventory and test `PendingReviewSourceLinkServiceImpl` and `PendingReviewSourceLinkPromoterImpl`, which currently invoke the writer without their own barrier; either make the writer's check the sole structural owner or add checks to those independent mutation entry points. |
| `RecurringOccurrenceMaterializer.materialize()` | Inject `DatabaseWriteBarrier` as `writeBarrier` | Check at the start of this public write entry before opening the transaction. Never exempt it because current callers check a barrier. |
| `RecurringOccurrenceMaterializer.materializeInCurrentTransaction()` | Same injected `writeBarrier`; structural check required here too | This is independently callable and is a write entry point even when normally invoked inside a coordinator-held transaction. Check at its start, before any occurrence, planned-expense, reminder, or event mutation. Do not rely on `RecurringLifecycleCoordinator` or `RecurringRuleLifecycleCoordinator` caller checks. Keep the check compatible with an already-open Room transaction. |
| Recurring critical event writes | `RecurringLifecycleEventWriter` ownership | Replace direct `lifecycleEventDao.insert` calls for critical state transitions with an in-transaction writer method that does not open a nested transaction and fails the enclosing transaction if the critical event fails. Update `DirectEventDaoInsertGuardTest` and `RecurringArchitectureGuardTest` only to reflect the legal writer, never by adding a broad materializer allowlist. Diagnostic events may retain their documented best-effort semantics, including cancellation-safe handling from RP-01. |
| `DataRetentionWorker` target mutations | Worker plus target-level ownership | Retention remains runnable without a retention capability gate. Before each target purge call, the worker must perform the restore/write-barrier check immediately before the call. Each target implementation/lambda in `RetentionModule` must also check immediately before each DAO SQL mutation when one purge contains multiple mutations; a single check before a multi-statement helper is not sufficient. Do not materialize sensitive rows merely to perform cleanup. |
| `DataRetentionWorker` audit inserts | Worker-owned check per insert | Before each `privacyAuditDao.insert`, call `writeBarrier.checkWritesAllowed("privacy.retention.audit")` immediately before that insert. Inject `DatabaseWriteBarrier` as `writeBarrier`; do not treat `WorkerExecutionGuard`'s earlier entry/checkpoint check as ownership of these later writes. |

For all entries, document the operation string as a controlled constant or stable literal. Checks must remain before the mutation, not only before a preceding read or transaction setup. If a transaction contains several DAO writes, either check before each write or use a verified barrier-aware write facade whose every method checks immediately before its DAO call.

### 3. Preserve restore and worker semantics

- `DatabaseWriteBarrier` remains the source of truth for whether the current maintenance mode permits writes. Preserve its typed `DatabaseAccessBlockedException` and operation/mode context.
- Do not weaken `WorkerExecutionGuard`'s barrier-first ordering, checkpoint behavior, lease behavior, or blocked-policy mapping. A barrier block must follow the existing worker contract (for example, SKIPPED versus RETRY as configured), not be converted into a permanent failure by a new catch.
- Retention cleanup must not declare `requiredCapabilities` for the raw-retention capability it enforces. Privacy settings may determine cutoffs and target selection, but cleanup must still run when the capability is disabled so it can delete data. During restore, no target or audit mutation may proceed.
- Do not solve a race by moving all checks to an upstream coordinator. Structural ownership is required at every class/method that can reach a mutating DAO, including transaction-internal entry points.
- Do not change Room schema, source-link indexes, event retention policy, or unrelated cancellation behavior in RP-02. RP-01 owns cancellation guard correctness; RP-14 owns the retention target set and failure/checkpoint semantics; RP-03 owns restore state transitions.

## Tests

Add or update targeted tests without relying on Gradle execution in this planning change:

- `WriteBarrierArchitectureGuardTest`: aliased injected DAO without ownership fails; aliased injected DAO with executable ownership passes; local `appDatabase.<dao>()` writer without ownership fails; direct accessor writer is detected; justified exemption passes; missing/blank exemption reason fails; stale exemption fails; materializer is not exempt; every detected writer has a structural owner.
- `RecurringArchitectureGuardTest`: arbitrary aliases of recurring rule DAOs are detected, including `subscriptionDao`; no direct recurring rule mutation is allowed outside the legal coordinator set; no direct critical recurring event DAO insert is allowed outside the event-writer boundary.
- `DirectEventDaoInsertGuardTest`: materializer direct critical-event inserts fail; the approved event-writer implementation remains the only DAO boundary as defined by the existing policy. Do not add an exemption merely to make the test green.
- Writer barrier tests: restore blocks notification intake before insert; restore blocks source-link insert; restore blocks materializer's public and current-transaction entry points; typed blocked outcomes are preserved and no DAO mutation occurs.
- Retention tests: each target mutation has a pre-mutation barrier check; each audit insert has its own pre-insert check; a restore flip between targets prevents the next mutation; audit-write blocking follows the existing worker policy; cleanup still executes when the raw-retention capability is disabled.
- Keep existing source-link concurrency tests and assert the unique-index/`IGNORE` behavior rather than introducing a false duplicate-row expectation.

Tests must use fakes/spies that record check order and DAO calls. A test that only verifies one barrier check at worker entry is insufficient.

## Stop conditions

Stop implementation and re-evaluate with the owning plan if:

- a writer cannot determine whether its check is inside a Room transaction without changing `DatabaseWriteBarrier` or `AppDatabase` semantics;
- routing materializer events through `RecurringLifecycleEventWriter` would open a nested transaction or break atomic rollback;
- a proposed exemption increases the map without a concrete owner, reason, issue ID, and expiry, or would exempt a class solely because current callers are guarded;
- retention target code cannot place a check before each SQL mutation without changing the `RetentionTarget` contract; coordinate that contract with RP-14 rather than accepting one coarse check;
- a barrier block is caught and mapped differently from the established `DatabaseAccessBlockedException`/`WorkerExecutionGuard` contract;
- a change would require a Room migration, index change, or source-link dedupe redesign (not RP-02 scope).

## Sequencing and completion gates

1. RP-01 lands first for cancellation-guard/source movement; RP-02 rebases and keeps cancellation fixes out of this diff.
2. Implement and test the class-level guard and justified exemption map.
3. Remediate intake, source-link, materializer, direct-event, and retention ownership. Resolve all guard failures without a permanent allowlist expansion.
4. RP-03 verifies that backup/restore mode transitions and typed blocked outcomes still match the barrier checks. Any RP-03 changes to barrier semantics must be applied consistently to these writers.
5. RP-14 adds/changes retention targets only after RP-02's per-mutation barrier contract is available; RP-14 must not add a capability gate to cleanup. Re-run the ownership inventory after its target additions.
6. Completion requires architecture guards, writer restore tests, retention ordering tests, and source-link regression tests to pass. No schema migration is expected.

## Validation (when Gradle is re-enabled)

Run sequentially with output captured; do not run Gradle as part of this documentation rewrite:

```text
./gradlew :app:testDebugUnitTest --tests "*WriteBarrierArchitectureGuard*" --tests "*RecurringArchitectureGuard*" --tests "*DirectEventDaoInsertGuard*" --console=plain
./gradlew :app:testDebugUnitTest --tests "*RestoreBlocksAllWrites*" --tests "*NotificationIntakeCoordinator*" --tests "*SourceLinkWriter*" --tests "*RecurringOccurrenceMaterializer*" --console=plain
./gradlew :app:testDebugUnitTest --tests "*DataRetentionWorker*" --tests "*Retention*" --console=plain
./gradlew :app:compileDebugKotlin --console=plain
```

Completion is blocked by any guard failure, an unexplained new exemption, a retention mutation without an immediately preceding check, a direct critical event DAO write, or a mismatch between RP-03 restore semantics and writer tests.
