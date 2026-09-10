# Cancellation / Atomicity / Event Consistency Implementation Plan

Last updated: 2026-06-16  
Scope: MIT-031, MIT-034, MIT-041, MIT-043  
Goal: no swallowed cancellation, no state/event divergence, no partially committed receipt/review/recurring lifecycle mutations.

---

## 1. Objective

Build a shared correctness layer so that:

- `CancellationException` is never swallowed.
- Coroutine cancellation never becomes false success, false failure, or partial terminal state.
- Important state changes and lifecycle/audit events commit atomically.
- Receipt save/status/link/review writes cannot diverge.
- Bank-statement receipt/review writes cannot partially commit.
- Recurring/reminder state, occurrence state, planned rows, and lifecycle events cannot diverge.
- Query/read-named methods do not secretly mutate DB.
- Event writers are transaction-aware and cannot be called casually from arbitrary code.
- Failed post-commit side effects are durably recorded without corrupting the primary DB transaction.

---

## 2. Master Issues Covered

| MIT | Issue | Covered Here |
|---|---|
| MIT-031 | Make state changes and lifecycle events atomic |
| MIT-034 | Fix cancellation propagation everywhere |
| MIT-041 | Make receipt/OCR/bank-statement review writes atomic |
| MIT-043 | Fix recurring/bill reminder duplicate fulfillment and hidden writes |

Related but not fully owned:

| MIT | Relationship |
|---|---|
| MIT-016/017 | Worker terminal state CAS and worker cancellation integrate here |
| MIT-033 | DB uniqueness supports recurring duplicate-fulfillment prevention |
| MIT-040 | Receipt link ownership depends on atomic receipt transactions |
| MIT-047 | Import coordinator should reuse cancellation/atomicity patterns |
| MIT-075 | Side-effect failure evidence builds on post-commit event policy |

---

## 3. Affected Pipelines

| Pipeline | Impact |
|---|---|
| P1 | Notification repair/recovery cancellation and diagnostics |
| P3 | Receipt/OCR/bank-statement/PendingReview atomicity |
| P4 | Recurring/reminders hidden writes, projection atomicity, state/event consistency |
| P8 | Privacy/retention cancellation handling |
| P9 | Worker cancellation and terminal-state consistency |
| P10 | Bank low-confidence review atomicity |
| P12 | Export/accounting/import cancellation safety |
| P16 | Security/network cancellation and sanitized failure handling |
| P17 | Static guards for CE/event atomicity |
| P18 | Import cancellation and row transaction rollback |

---

## 4. Current Problem Summary

Known issues:

- `runCatching` / broad `catch(Exception)` can swallow `CancellationException`.
- Receipt save can commit before required `PendingReview` insert succeeds.
- Bank-statement receipt insert/status/event/review writes are not fully atomic.
- Manual or low-confidence receipt/review paths can leave inconsistent state.
- Recurring reminders and occurrences can update state without event in same transaction.
- `RecurringPlanProjectionService.projectFromRule()` has full-atomicity risk.
- `getDueReminders()` and reconciliation paths can perform hidden writes.
- Direct lifecycle event DAO inserts bypass transaction-aware writer.
- Snooze/dismiss receivers can use unstructured coroutine scope and broad exception handling.
- Worker terminal/run states can race without compare-and-set.
- Post-commit side-effect failures are not consistently durable.

---

## 5. Architecture Decision

### Decision

Adopt a **transaction-scoped mutation model**:

> Any domain operation that changes important persistent state must execute through an approved coordinator/transaction runner that writes both state and lifecycle event in the same transaction.

Adopt a **cancellation-safe coroutine model**:

> No suspend, worker, receiver, repository, or coordinator path may catch `Exception`/`Throwable` without rethrowing `CancellationException`.

Adopt a **post-commit side-effect model**:

> External side effects do not run inside the DB transaction. They run after commit through an outbox/run-ledger/side-effect dispatcher. Failures are recorded durably and do not corrupt the committed primary mutation.

---

## 6. Non-Negotiable Invariants

After this plan:

- [ ] `CancellationException` is rethrown everywhere in suspend/worker/receiver paths.
- [ ] `runCatching` is banned or wrapped by a cancellation-safe helper in coroutine paths.
- [ ] State update + lifecycle event insert happen in one DB transaction.
- [ ] Required `PendingReview` insert is atomic with receipt save/status when review is mandatory.
- [ ] Bank-statement receipt/review/status/event writes are atomic.
- [ ] Recurring occurrence/reminder/planned-row state and event writes are atomic.
- [ ] Direct lifecycle event DAO inserts are forbidden outside approved transaction-aware writers.
- [ ] Query/read-named methods do not perform writes.
- [ ] Hidden stale-claim recovery is split into explicit write command.
- [ ] Post-commit side-effect failures are durable and queryable.
- [ ] Static guards prevent recurrence.

---

# 7. Target Components

## 7.1 `CancellationSafe`

Shared helpers:

```text
runSuspendCatchingCancellable
mapFailureRethrowCancellation
catchAndSanitizeRethrowCancellation
```

Rules:

- Rethrow `CancellationException`.
- Sanitize non-cancellation exceptions.
- Never convert cancellation into success/failure result.
- Never swallow cancellation in `onFailure`.

---

## 7.2 `DomainTransactionRunner`

Central wrapper around Room transaction APIs.

Responsibilities:

- open DB transaction,
- provide `TransactionContext`,
- require correlation/operation metadata,
- allow state mutation,
- allow transaction-scoped lifecycle event writing,
- rollback everything on exception/cancellation,
- rethrow cancellation.

---

## 7.3 `TransactionContext`

Passed to approved event/state writers.

Contains:

- transaction ID,
- operation ID,
- correlation ID,
- actor/source,
- timestamp from `TimeProvider`,
- event writer handle,
- write-barrier proof if needed.

---

## 7.4 `TransactionalEventWriter`

Only legal event writer for critical lifecycle events.

Responsibilities:

- write event inside current DB transaction,
- include before/after snapshots when required,
- include idempotency/correlation keys,
- reject writes outside transaction context,
- avoid raw PII in event metadata.

---

## 7.5 `PostCommitSideEffectOutbox`

For side effects that must happen after transaction commit.

Examples:

- scheduling notification,
- refreshing dashboard cache,
- cloud/network call,
- background reschedule,
- analytics update.

Responsibilities:

- record side-effect request transactionally,
- execute after commit,
- record failure durably,
- retry if policy says so.

---

## 7.6 Domain Coordinators

Approved atomic mutation owners:

- `ReceiptReviewCoordinator`
- `BankStatementReceiptCoordinator`
- `RecurringLifecycleCoordinator`
- `BillReminderLifecycleCoordinator`
- `OperationLifecycleCoordinator`
- `WorkerRunLedgerCoordinator`

---

# 8. Implementation Phases

---

## Phase 0 — Inventory Cancellation and Atomicity Risks

### Tasks

- [ ] Inventory all `runCatching`.
- [ ] Inventory all `catch (e: Exception)` and `catch (t: Throwable)`.
- [ ] Inventory all workers/receivers with broad catches.
- [ ] Inventory all lifecycle event DAO inserts.
- [ ] Inventory state update + event insert pairs.
- [ ] Inventory receipt save/status/link/review transactions.
- [ ] Inventory bank-statement review writes.
- [ ] Inventory recurring/reminder state updates.
- [ ] Inventory read-named methods that write.
- [ ] Inventory post-commit side effects.
- [ ] Create `docs/atomicity/CANCELLATION_ATOMICITY_BASELINE.md`.

### Useful searches

```bash
rg "runCatching|catch \\(.*Exception|catch \\(.*Throwable" app/src
rg "CancellationException" app/src
rg "EventDao|LifecycleEventDao|insert.*Event" app/src
rg "withTransaction|runInTransaction|@Transaction" app/src
rg "PendingReview|ScannedReceipt|expenseId" app/src
rg "Recurring|Reminder|Occurrence|getDue|reconcile" app/src
```

### Acceptance Criteria

- [ ] Every risky catch site is classified.
- [ ] Every critical event write path is known.
- [ ] Every hidden write candidate is listed.

---

## Phase 1 — Write Cancellation Policy and Guard

### Tasks

- [ ] Create `docs/atomicity/CANCELLATION_POLICY.md`.
- [ ] Define allowed/forbidden patterns.
- [ ] Implement cancellation-safe helper.
- [ ] Add static guard for unsafe patterns.
- [ ] Add owner/reason/expiry allowlist for rare safe exceptions.
- [ ] Add tests for helper and guard.

### Forbidden by default

- `runCatching` in suspend/worker/receiver paths.
- `catch(Exception)` without CE rethrow.
- `catch(Throwable)` without CE rethrow.
- `onFailure` in coroutine path without CE preservation.
- returning `Result.success()` after cancellation.

### Acceptance Criteria

- [ ] Unsafe cancellation fixture fails CI.
- [ ] Safe CE-rethrow fixture passes.
- [ ] P1/P3/P4/P8/P9/P12/P18 paths are covered.

---

## Phase 2 — Introduce Transaction-Scoped Event Infrastructure

### Tasks

- [ ] Implement `DomainTransactionRunner`.
- [ ] Implement `TransactionContext`.
- [ ] Implement `TransactionalEventWriter`.
- [ ] Require correlation ID / operation ID for critical mutations.
- [ ] Add event idempotency key where needed.
- [ ] Add before/after snapshots for update/delete/link events.
- [ ] Make direct event DAO insertion non-public or guard-blocked.
- [ ] Add rollback tests.

### Critical event domains

- receipts,
- pending review,
- recurring/reminders,
- worker terminal runs,
- operation lifecycle,
- transaction lifecycle where applicable.

### Acceptance Criteria

- [ ] State/event pair rolls back together on exception.
- [ ] Event cannot be inserted outside approved transaction writer.
- [ ] Direct event DAO insert fixture fails CI.

---

## Phase 3 — Receipt / OCR / PendingReview Atomicity

### Tasks

- [ ] Route receipt save/status/review through `ReceiptReviewCoordinator`.
- [ ] If `PendingReview` is required, insert it in same transaction as receipt save/status.
- [ ] Status update + event insert in same transaction.
- [ ] Link/unlink operations integrate with `ReceiptLinkService` transaction context.
- [ ] Low-confidence receipt paths cannot save receipt without review row.
- [ ] Remove unsafe `runCatching` in receipt/OCR lifecycle code.
- [ ] Add fault-injection tests.

### Fault-injection tests

- [ ] exception after receipt insert before review insert rolls back receipt.
- [ ] exception after review insert before event rolls back both.
- [ ] cancellation during transaction rolls back all.
- [ ] nested receipt link transaction rollback does not leak side effects.

### Acceptance Criteria

- [ ] No receipt requiring review exists without `PendingReview`.
- [ ] Receipt status and event cannot diverge.
- [ ] Cancellation leaves no partial receipt/review state.

---

## Phase 4 — Bank-Statement Receipt / Review Atomicity

### Tasks

- [ ] Route bank-statement low-confidence review writes through legal coordinator.
- [ ] Validate finite amount/currency before transaction starts.
- [ ] Insert receipt/import item/review/status/event atomically.
- [ ] Define partial-failure policy:
  - rollback whole statement row, or
  - commit row-level failure with safe diagnostic.
- [ ] Ensure raw merchant/description sanitization happens before transaction.
- [ ] Add rollback tests.

### Acceptance Criteria

- [ ] Bank review row cannot exist without matching receipt/import context.
- [ ] Receipt cannot be saved requiring bank review without review row.
- [ ] Invalid amount/currency cannot create partial review rows.

---

## Phase 5 — Recurring / Reminder Atomicity

### Tasks

- [ ] Create `RecurringLifecycleCoordinator`.
- [ ] Wrap occurrence/reminder status + event in transaction.
- [ ] `projectFromRule()` generates occurrence/reminder/planned rows atomically.
- [ ] Critical recurring events go through transaction-aware writer.
- [ ] Split planned-vs-actual reconciliation into:
  - pure report,
  - explicit write/apply command.
- [ ] Prevent duplicate fulfillment by using DB constraint from MIT-033 and mapping conflict atomically.
- [ ] Add conflict handling:
  - if linked actual already used, rollback and create review/diagnostic as policy says.
- [ ] Add tests.

### Tests

- [ ] exception during projection rolls back all generated rows.
- [ ] occurrence state update without event is impossible.
- [ ] reminder state update without event is impossible.
- [ ] duplicate linked actual conflict maps to typed result.
- [ ] cancellation during projection rolls back.

### Acceptance Criteria

- [ ] Recurring lifecycle state and events cannot diverge.
- [ ] Projection does not leave partial occurrences/reminders/planned rows.
- [ ] Query methods do not mutate.

---

## Phase 6 — Hidden Write Cleanup

### Known examples

- `getDueReminders()` stale-claim recovery.
- `reconcilePlannedVsActual()` hidden write/generate path.
- Repair/recovery methods hidden under read-like APIs.

### Tasks

- [ ] Rename write methods to command names.
- [ ] Split pure query from recovery/write path.
- [ ] Require write barrier + transaction runner for write path.
- [ ] Add static guard for writes inside methods named `get`, `load`, `observe`, `find`, `query`, `calculate`, `report`.
- [ ] Add tests proving query methods do not change DB.

### Acceptance Criteria

- [ ] Read-named methods are side-effect free.
- [ ] Recovery writes are explicit, gated, transactional, and evented.

---

## Phase 7 — Worker / Operation Terminal Consistency Integration

### Tasks

- [ ] Use atomic terminal compare-and-set for worker run ledger.
- [ ] State + terminal event in one transaction where DB-backed.
- [ ] Do not write success/failure terminal after cancellation.
- [ ] Post-cancellation diagnostic must be bounded and safe.
- [ ] Integrate with worker plan rather than duplicate worker logic.
- [ ] Add terminal race tests.

### Acceptance Criteria

- [ ] Worker/operation terminal states are single-write.
- [ ] Cancellation does not produce false success/failure.

---

## Phase 8 — Post-Commit Side-Effect Failure Evidence

### Tasks

- [ ] Define which side effects are post-commit.
- [ ] Add side-effect outbox or ledger rows.
- [ ] Record post-commit failure with sanitized reason.
- [ ] Ensure side-effect failure does not roll back committed primary mutation.
- [ ] Define retry/no-retry policy.
- [ ] Add tests for failed post-commit actions.

### Examples

- reminder notification scheduling,
- dashboard/budget refresh,
- source-link side effect,
- audit/diagnostic side effect,
- worker reschedule.

### Acceptance Criteria

- [ ] Failed side effect is visible and queryable.
- [ ] Primary transaction remains consistent.

---

## Phase 9 — Repair Existing Inconsistent States

### Tasks

- [ ] Query for receipts requiring review but missing `PendingReview`.
- [ ] Query for receipt status/event mismatches.
- [ ] Query for recurring state/event mismatches.
- [ ] Query for duplicate linked actuals.
- [ ] Query for partial projection rows.
- [ ] Add repair migration or startup repair coordinator where safe.
- [ ] Unsafe repairs create `PendingReview` / diagnostic entry.
- [ ] Add tests using inconsistent legacy fixtures.

### Acceptance Criteria

- [ ] Existing inconsistent data is either repaired or surfaced safely.
- [ ] Repair itself is transactional and cancellation-safe.

---

## Phase 10 — Static Guards and CI

### Required guards

- [ ] cancellation guard,
- [ ] event writer guard,
- [ ] direct lifecycle event DAO insert guard,
- [ ] receipt status/review direct mutation guard,
- [ ] recurring event direct insert guard,
- [ ] hidden write in read-named method guard,
- [ ] transaction-required mutation guard.

### Guard failures

- direct `RecurringLifecycleEventDao.insert()`,
- direct receipt status/event split writes,
- direct `PendingReviewDao.insert()` outside approved coordinator if required review,
- `runCatching` in suspend path,
- `catch(Exception)` without CE rethrow,
- write DAO call in `get*` / `observe*` / `calculate*`.

### Acceptance Criteria

- [ ] New violations fail CI.
- [ ] Allowlist entries require owner/reason/expiry/MIT.

---

# 9. Testing Strategy

## 9.1 Cancellation Tests

- [ ] CE inside repository is rethrown.
- [ ] CE inside worker is rethrown.
- [ ] CE inside receiver is rethrown.
- [ ] CE inside receipt save rolls back.
- [ ] CE inside recurring projection rolls back.
- [ ] CE does not emit success/failure terminal state.

## 9.2 Atomicity Tests

- [ ] State insert then event failure rolls back state.
- [ ] Event insert then state failure rolls back event.
- [ ] Required review failure rolls back receipt.
- [ ] Recurring projection failure rolls back all rows.
- [ ] Worker terminal double-write race writes once.

## 9.3 Fault-Injection Tests

Add test-only fault points:

- after state insert,
- after review insert,
- after event insert,
- before commit,
- after commit before side-effect,
- during side-effect,
- during cancellation.

## 9.4 Concurrency Tests

- [ ] duplicate recurring linked actual conflict.
- [ ] concurrent reminder claim/update.
- [ ] concurrent receipt review attempts.
- [ ] terminal state race.

## 9.5 Static Guard Tests

Each guard needs:

- positive fixture,
- negative fixture,
- allowlisted fixture,
- expired allowlist fixture.

---

# 10. Rollout PR Plan

## PR 1 — Baseline and Policies

Includes:

- cancellation/atomicity inventory,
- cancellation policy,
- event consistency policy.

Acceptance:

- [ ] Risk inventory exists.
- [ ] Forbidden patterns are documented.

---

## PR 2 — Cancellation Helper and Guard

Includes:

- cancellation-safe helpers,
- static cancellation guard,
- initial high-risk fixes.

Acceptance:

- [ ] Unsafe CE swallowing fails CI or is explicitly allowlisted.

---

## PR 3 — Transactional Event Infrastructure

Includes:

- transaction runner,
- transaction context,
- event writer,
- direct event DAO guard.

Acceptance:

- [ ] State/event rollback tests pass.

---

## PR 4 — Receipt/PendingReview Atomicity

Includes:

- receipt review coordinator,
- receipt status/event transaction,
- required review atomic insert,
- tests.

Acceptance:

- [ ] No receipt requiring review can commit without review row.

---

## PR 5 — Bank-Statement Review Atomicity

Includes:

- bank low-confidence review coordinator path,
- validation before transaction,
- safe partial-failure policy,
- tests.

Acceptance:

- [ ] Bank receipt/review/status/event cannot partially commit.

---

## PR 6 — Recurring Lifecycle Atomicity

Includes:

- recurring lifecycle coordinator,
- projection transaction,
- reminder/occurrence state+event transaction,
- tests.

Acceptance:

- [ ] Recurring state/event/projection atomicity proven.

---

## PR 7 — Hidden Write Cleanup

Includes:

- split query/write methods,
- pure reconciliation report,
- explicit stale-claim recovery command,
- hidden write guard.

Acceptance:

- [ ] Read-named methods do not mutate DB.

---

## PR 8 — Post-Commit Side-Effect Evidence

Includes:

- side-effect outbox/ledger,
- failure recording,
- retry/no-retry policy,
- tests.

Acceptance:

- [ ] Failed side effects are durable without corrupting primary state.

---

## PR 9 — Legacy Inconsistency Repair

Includes:

- diagnostic queries,
- repair/backfill logic,
- tests with inconsistent fixtures.

Acceptance:

- [ ] Known existing inconsistent states are repaired or surfaced.

---

## PR 10 — Final Static Guards and Tracker Closure

Includes:

- all guards blocking in CI,
- docs updated,
- master tracker closing SHAs.

Acceptance:

- [ ] MIT-031, MIT-034, MIT-041, MIT-043 closure criteria met.

---

# 11. Edge Cases

## Cancellation after state insert before event

Expected:

- transaction rolls back,
- CE rethrown,
- no terminal success/failure.

## Event write fails after state update

Expected:

- transaction rolls back state update,
- sanitized error/diagnostic outside transaction if needed.

## Post-commit side effect fails

Expected:

- primary state/event remains committed,
- side-effect failure recorded,
- retry/no-retry policy applied.

## Recurring actual already linked elsewhere

Expected:

- DB constraint rejects,
- coordinator maps to typed duplicate/conflict,
- event/review/diagnostic follows policy atomically.

## Bank statement row invalid

Expected:

- validation rejects before mutation,
- no receipt/review row created,
- sanitized row-level diagnostic.

---

# 12. Documentation Requirements

Create/update:

```text
docs/atomicity/CANCELLATION_POLICY.md
docs/atomicity/CANCELLATION_ATOMICITY_BASELINE.md
docs/atomicity/TRANSACTIONAL_EVENT_POLICY.md
docs/atomicity/POST_COMMIT_SIDE_EFFECT_POLICY.md
docs/receipts/RECEIPT_REVIEW_ATOMICITY_POLICY.md
docs/recurring/RECURRING_LIFECYCLE_ATOMICITY_POLICY.md
docs/testing/ATOMICITY_FAULT_INJECTION_TESTS.md
```

Update:

```text
docs/MASTER_ISSUE_TRACKER.md
docs/MASTER_ISSUE_TRACKER_SUPPLEMENT_2.md
```

---

# 13. Metrics

| Metric | Target |
|---|---|
| Unsafe `runCatching` in suspend paths | 0 |
| `catch(Exception)` without CE rethrow | 0 |
| Direct critical event DAO inserts | 0 |
| State/event split writes | 0 |
| Receipts requiring review without review row | 0 |
| Recurring state/event mismatches | 0 |
| Hidden writes in read-named methods | 0 |
| Post-commit side-effect failures without ledger | 0 |
| Expired atomicity allowlists | 0 |

---

# 14. Definition of Done by MIT

## MIT-031 can close when

- [ ] Critical state changes and lifecycle events are transactional.
- [ ] Direct event DAO inserts are blocked.
- [ ] State/event rollback tests pass.
- [ ] Worker/operation terminal state consistency is integrated.

## MIT-034 can close when

- [ ] Unsafe cancellation patterns are removed or guarded.
- [ ] CE is rethrown in suspend/worker/receiver paths.
- [ ] Static guard is blocking.
- [ ] Cancellation regression tests pass.

## MIT-041 can close when

- [ ] Receipt save/status/review writes are atomic.
- [ ] Bank-statement receipt/review writes are atomic.
- [ ] Required `PendingReview` cannot be missing after receipt commit.
- [ ] Low-confidence review failures are not swallowed.

## MIT-043 can close when

- [ ] Recurring/reminder state/event writes are atomic.
- [ ] Projection generation is atomic.
- [ ] Hidden writes are split into explicit commands.
- [ ] Duplicate linked actual conflict is handled safely.
- [ ] Privacy-safe reminder content policy integrates with lifecycle path.

---

# 15. Final Completion Checklist

This plan is complete when:

- [ ] Cancellation baseline inventory exists.
- [ ] Cancellation policy exists.
- [ ] Cancellation-safe helper exists.
- [ ] Cancellation static guard is blocking.
- [ ] Transaction runner/event writer exist.
- [ ] Direct event DAO inserts are blocked.
- [ ] Receipt/PendingReview atomicity is fixed.
- [ ] Bank-statement review atomicity is fixed.
- [ ] Recurring lifecycle atomicity is fixed.
- [ ] Hidden writes are removed or made explicit.
- [ ] Post-commit side-effect failures are durable.
- [ ] Legacy inconsistent states are repaired or surfaced.
- [ ] Fault-injection tests pass.
- [ ] Master tracker is updated with closing SHAs.

---

# 16. Recommended First Action

Start with:

```text
PR 1 — Cancellation / Atomicity Baseline and Policy
```

Then:

```text
PR 2 — Cancellation Helper and Static Guard
PR 3 — Transactional Event Infrastructure
PR 4 — Receipt/PendingReview Atomicity
PR 5 — Recurring Lifecycle Atomicity
```

Do not fix receipt or recurring paths with one-off local transactions first. Build the shared transaction/event/cancellation primitives, then migrate domain paths onto them.