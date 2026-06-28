# Read/Write Barrier + DAO Ownership Implementation Plan

Last updated: 2026-06-15  
Scope: MIT-030, MIT-036, MIT-077, MIT-060  
Goal: no unsafe DB read/write during restore, import, reset, maintenance, or restart-required state.

---

## 1. Objective

Create a hard architectural boundary around database access so that:

- UI/ViewModels cannot directly mutate the database.
- Importers cannot mutate categories/expenses directly.
- Bank disconnect and similar actions go through lifecycle-owned repositories/coordinators.
- DB writes are blocked during restore/reset/import maintenance unless explicitly owned.
- Unsafe DB reads/Room Flows are blocked or safely degraded during restore/reset/import.
- DAO ownership policy is enforced by static guards and CI.
- Allowlist exceptions are rare, documented, owner-approved, and expiring.

This plan turns the current “caller discipline” model into enforceable architecture.

---

## 2. Master Issues Covered

| MIT | Issue | Covered Here |
|---|---|---|
| MIT-030 | Enforce write barrier for all mutating DB paths | Yes |
| MIT-036 | Strengthen DAO ownership policy | Yes |
| MIT-077 | Global read-barrier and restore-read safety | Yes |
| MIT-060 | Ban DAOs from ViewModels and route bank disconnect legally | Yes |

Related but not owned:

| MIT | Relationship |
|---|---|
| MIT-013 | Restore/DI hard-restart policy provides restart-required state |
| MIT-014 | Maintenance owner/session tokens are used by barrier scopes |
| MIT-016 | Worker guard must call barriers before DB access |
| MIT-047 | Import coordinator must use legal barrier-owned path |
| MIT-079 | DI graph must prove DAOs are not unsafe singletons in UI paths |
| MIT-003 | Static guards enforce the rules in CI |

---

## 3. Affected Pipelines

| Pipeline | Why |
|---|---|
| P1 | Notification intake/recovery/repair writes must be barrier-owned |
| P4 | Reminder stale-claim recovery and recurring writes must be gated |
| P7 | Restore/backup depends on read/write blocking |
| P10 | Bank disconnect and bank import/review writes must be owned |
| P12 | Export/accounting reads need read-barrier clarity |
| P13 | DAO constraints/ownership policy |
| P14 | UI/ViewModel action path safety |
| P15 | Hilt/DI singleton lifetime risks |
| P18 | Importers/category creation must not bypass legal owners |

---

## 4. Current Problem

The reports show several recurring problems:

- Write barriers are caller-enforced instead of global.
- Some intake/recovery/repair paths bypass write barriers.
- Some read paths/Room Flows may continue during restore.
- ViewModels can directly inject or call DAOs.
- `BankConnectionsViewModel.disconnect()` directly mutates DB.
- Importers create categories directly through DAO.
- Query-named methods can perform hidden writes.
- DAO allowlist/source/docs are not aligned.
- Some `requires_write_barrier:false` exceptions are too broad.
- Restore/reset/import can race with stale DB reads/writes.

---

## 5. Architecture Decision

### Decision

Use a **central access-gate + ownership model**.

Every DB access must be classified as one of:

1. **Normal read**
2. **Normal write**
3. **Maintenance-safe read**
4. **Restore-internal write**
5. **Snapshot/export read**
6. **Migration/test-only access**
7. **Forbidden direct DAO access**

### Core rule

> DAOs are low-level implementation details. Feature code must use repositories/coordinators/use cases that enforce lifecycle, barrier, privacy, atomicity, and diagnostics.

### Hard restriction

- No mutating DAO access from UI/ViewModels.
- No mutating DAO access from import utility classes.
- No worker DB access without worker guard.
- No restore-internal DB access without maintenance owner token.
- No DB-backed UI Flow that ignores restart-required/maintenance state.

---

## 6. Non-Negotiable Invariants

After this plan:

- [ ] Every DB write passes through `DatabaseWriteBarrier` or equivalent access gate.
- [ ] Every unsafe DB read/Flow passes through `DatabaseReadBarrier` or equivalent access gate.
- [ ] Restart-required state blocks normal DB reads/writes.
- [ ] Maintenance mode blocks normal DB writes.
- [ ] Restore-internal writes require owner/session token.
- [ ] UI/ViewModels do not inject mutating DAOs.
- [ ] Importers cannot directly create categories/expenses.
- [ ] Bank disconnect goes through lifecycle coordinator/repository.
- [ ] Query/read-named methods do not perform hidden writes.
- [ ] All DAO ownership exceptions are documented, owner-approved, expiring, and tested.
- [ ] Static guards fail CI for new violations.

---

# 7. Target Architecture

## 7.1 Main Components

### `DatabaseAccessGate`

Central authority for DB access decisions.

Inputs:

- current app DB lifecycle state,
- maintenance session state,
- restart-required state,
- operation type,
- owner token where needed,
- read/write category.

Outputs:

- allowed,
- blocked with typed reason,
- allowed only under restore-internal scope,
- allowed only under snapshot/export scope.

---

### `DatabaseWriteBarrier`

Used before every mutating DB operation.

Responsibilities:

- block writes during restore/reset/import unless operation owns maintenance token,
- block writes during restart-required state,
- expose typed blocked result,
- emit sanitized diagnostics where required,
- avoid throwing raw internal exceptions to UI.

---

### `DatabaseReadBarrier`

Used for unsafe reads and DB-backed Flows.

Responsibilities:

- block or degrade reads during restore/reset/import,
- block normal reads during restart-required state,
- allow explicitly safe snapshot/export reads,
- expose typed `ReadBlocked` or empty/loading state for UI.

---

### `DaoOwnershipPolicy`

Single source of truth for which classes may access which DAOs.

Policy dimensions:

- DAO name,
- allowed owner package/class,
- read-only or mutating,
- barrier required,
- maintenance token required,
- linked MIT issue if exception,
- expiry date for exception.

---

### `Repository/Coordinator Owners`

Approved owners for mutating operations.

Examples:

- `ExpenseRepository` / transaction lifecycle coordinator.
- `ReceiptLinkService`.
- `ImportCoordinator`.
- `CategoryRepository`.
- `BankConnectionLifecycleCoordinator`.
- `RecurringLifecycleCoordinator`.
- `NotificationIntakeCoordinator`.
- `BackupRestoreCoordinator`.

---

## 7.2 Access State Model

Recommended states:

| State | Normal Reads | Normal Writes | Restore Internal Writes | Snapshot Reads |
|---|---:|---:|---:|---:|
| NORMAL | allowed | allowed | denied | allowed if owned |
| MAINTENANCE_REQUESTED | limited | blocked | denied until active token | limited |
| MAINTENANCE_ACTIVE | blocked/limited | blocked | allowed with token | allowed if snapshot owner |
| DB_SWAP_IN_PROGRESS | blocked | blocked | allowed only for restore owner | denied unless temp DB |
| RESTART_REQUIRED | blocked | blocked | denied except final diagnostics | blocked |
| NORMAL_AFTER_RESTART | allowed | allowed | denied | allowed if owned |

---

## 7.3 Read Categories

Not all reads are equal.

### Safe reads

May not need DB read barrier:

- static in-memory config,
- non-DB feature flags,
- restart-required marker from DataStore/file marker,
- maintenance state outside DB.

### Barrier-required reads

Must be gated:

- DAO queries,
- Room Flow exposure,
- dashboard data,
- categories,
- expenses,
- receipts,
- recurring rules,
- bank connections,
- import/export/accounting reads,
- notification intake state.

### Snapshot reads

Allowed only under explicit snapshot/export session:

- backup export,
- accounting export,
- restore verification against temp DB,
- migration tests.

---

## 7.4 Write Categories

### Normal writes

Require write barrier:

- expense create/update/delete,
- category create/update/delete,
- receipt status/link changes,
- recurring/reminder state,
- bank connection mutation,
- bank import/review mutation,
- email/import writes,
- notification intake state,
- operation events.

### Restore-internal writes

Require maintenance owner token:

- restore journal update,
- restore asset path repair,
- restore operation ledger finalization,
- temporary verification metadata if truly needed.

### Forbidden writes

Should not exist outside approved owners:

- DAO writes from ViewModels,
- DAO writes from Compose click handlers,
- DAO writes from import utility classes,
- event insert outside transaction-aware writer,
- hidden writes inside read/query methods.

---

# 8. Implementation Phases

---

## Phase 0 — Inventory Current Access

### Goal

Find every DB read/write path before enforcing rules.

### Tasks

- [ ] Inventory all DAO interfaces.
- [ ] Classify DAO methods as read, write, mixed, or hidden write.
- [ ] Inventory all direct DAO injections.
- [ ] Inventory all direct DAO method calls.
- [ ] Inventory ViewModel/UI DAO references.
- [ ] Inventory importer DAO references.
- [ ] Inventory worker DAO/repository references.
- [ ] Inventory Room Flows exposed to UI.
- [ ] Inventory methods named as reads that perform writes.
- [ ] Inventory current DB access allowlist.
- [ ] Create `docs/db/DAO_ACCESS_INVENTORY.md`.

### Useful searches

- `Dao`
- `@Dao`
- `@Insert`
- `@Update`
- `@Delete`
- `@Query`
- `@Transaction`
- `Flow<`
- `ViewModel`
- `CategoryDao`
- `BankConnectionDao`
- `RecurringLifecycleEventDao`
- `insertOrIgnore`
- `delete`
- `update`

### Deliverables

- DAO method classification table.
- Direct DAO injection list.
- UI/ViewModel DAO violation list.
- Importer DAO violation list.
- Worker DAO access list.
- Read/Flow exposure list.

### Acceptance Criteria

- [ ] Every DAO method is classified.
- [ ] Every direct DAO injection has an owner or violation.
- [ ] High-risk violations are linked to MIT issues.

---

## Phase 1 — Define DAO Ownership Policy

### Goal

Create one canonical ownership source.

### Tasks

- [ ] Create `docs/db/DAO_OWNERSHIP_POLICY.md`.
- [ ] Create or update machine-readable allowlist.
- [ ] For each DAO, define legal owners.
- [ ] Mark each owner as read-only, write, restore-internal, or test-only.
- [ ] Require barrier type for each owner.
- [ ] Remove broad `requires_write_barrier:false` exceptions.
- [ ] Add owner/reason/expiry to all exceptions.
- [ ] Align docs, source, and static guard allowlist.

### Required policy fields

| Field | Meaning |
|---|---|
| DAO | DAO/interface name |
| Method pattern | all/read/write/specific methods |
| Allowed owner | class/package |
| Access type | read/write/restore-internal/test |
| Barrier required | read/write/both/none |
| Maintenance token required | yes/no |
| Reason | why access is legal |
| Owner | maintainer |
| Expiry | for exceptions |
| Linked issue | MIT ID |

### Acceptance Criteria

- [ ] Policy exists and is machine-checkable.
- [ ] Exceptions have owner, reason, expiry, and MIT link.
- [ ] No undocumented DAO write path remains.

---

## Phase 2 — Implement Central Access Gate Semantics

### Goal

Make read/write barriers use the same lifecycle truth.

### Tasks

- [ ] Connect barriers to DB lifecycle state.
- [ ] Connect barriers to maintenance session manager.
- [ ] Connect barriers to restart-required latch.
- [ ] Define typed block reasons:
  - restore active,
  - import active,
  - reset active,
  - restart required,
  - invalid owner token,
  - read not allowed in current state,
  - write not allowed in current state.
- [ ] Ensure barriers do not expose raw internal details.
- [ ] Add tests for every lifecycle state.

### Typed outcomes

Use typed outcomes rather than raw booleans where possible:

- allowed,
- blocked restore active,
- blocked restart required,
- blocked invalid owner,
- blocked read unsafe,
- blocked write unsafe.

### Acceptance Criteria

- [ ] Access gate behavior is deterministic for every lifecycle state.
- [ ] Tests cover normal, maintenance, DB swap, restart-required, and post-restart.

---

## Phase 3 — Enforce Write Barrier on Mutations

### Goal

Every DB mutation checks the write barrier before touching DAO.

### High-risk paths to fix first

- [ ] Notification intake coordinator.
- [ ] Notification recovery scheduler.
- [ ] Notification repairer.
- [ ] `getDueReminders()` stale-claim recovery.
- [ ] Bank disconnect.
- [ ] Bank import/review writes.
- [ ] Import category creation.
- [ ] CSV/JSON import expense creation.
- [ ] Category import paths.
- [ ] Recurring/reminder status writes.
- [ ] Operation event writes.
- [ ] Receipt status/link writes.

### Tasks

- [ ] Add write barrier dependency to approved owners.
- [ ] Move direct DAO mutations behind approved repositories/coordinators.
- [ ] Split hidden write methods into explicit write methods.
- [ ] Ensure restore-blocked writes emit sanitized durable evidence where needed.
- [ ] Remove direct write calls from UI/importers.
- [ ] Add regression tests for blocked write during restore.
- [ ] Add regression tests for allowed restore-internal write with owner token.

### Acceptance Criteria

- [ ] During maintenance, normal writes are blocked.
- [ ] During restart-required, all normal writes are blocked.
- [ ] Restore-internal writes require valid token.
- [ ] CI guard catches new ungated write path.

---

## Phase 4 — Enforce Read Barrier / Flow Safety

### Goal

Unsafe reads and DB-backed Flows must not continue blindly during restore/reset/import/restart-required.

### Tasks

- [ ] Inventory all Room Flow return paths.
- [ ] Wrap UI-exposed DB flows with lifecycle-aware read gate.
- [ ] Define behavior when read is blocked:
  - show restore in progress,
  - show restart required,
  - show loading/suspended state,
  - cancel collection,
  - return typed `PrivacyOrMaintenanceBlocked` state.
- [ ] Recheck read barrier before accounting category/source reads.
- [ ] Ensure backup/export snapshot reads use explicit snapshot owner.
- [ ] Ensure restore verification reads temp/restored DB only through restore owner.
- [ ] Add tests for active UI flow during restore.
- [ ] Add tests for deep link/notification tap trying to read during restart-required.

### Important rule

Do not just block writes. Unsafe reads can also:

- observe half-restored state,
- keep old Room invalidation trackers alive,
- trigger lazy writes/caches,
- leak stale UI data,
- crash on swapped DB files.

### Acceptance Criteria

- [ ] DB-backed UI flows do not continue normally during restore.
- [ ] Restart-required state blocks normal DB reads.
- [ ] Snapshot reads are explicitly owned and tested.

---

## Phase 5 — Remove DAOs from ViewModels/UI

### Goal

UI must not bypass lifecycle/barrier/legal owners.

### Known violation

- `BankConnectionsViewModel.disconnect()` directly calls DAO.

### Tasks

- [ ] Remove DAO constructor parameters from ViewModels.
- [ ] Remove DAO usage from Compose click handlers.
- [ ] Add `BankConnectionLifecycleCoordinator` or repository method for disconnect.
- [ ] Ensure bank disconnect checks write barrier.
- [ ] Ensure bank disconnect records lifecycle/operation event if required.
- [ ] Ensure UI receives typed success/failure/blocked result.
- [ ] Sanitize UI error messages.
- [ ] Add static guard banning DAO injection in `ui/**` and `*ViewModel`.
- [ ] Add tests for bank disconnect during restore/restart-required.

### Acceptance Criteria

- [ ] No ViewModel has mutating DAO dependency.
- [ ] Bank disconnect goes through legal owner.
- [ ] UI gets typed blocked state instead of raw DB exception.

---

## Phase 6 — Remove Direct DAO Writes from Importers

### Goal

Import utility code cannot mutate categories/expenses outside lifecycle coordinator.

### Current risk

CSV/JSON importers create categories directly through `CategoryDao`, bypassing:

- write barrier,
- category normalization,
- cache invalidation,
- lifecycle operation,
- import row transaction,
- restore blocking.

### Tasks

- [ ] Importers become pure parsers/mappers.
- [ ] Import coordinator owns all DB writes.
- [ ] Category creation goes through `CategoryRepository` or import-safe category owner.
- [ ] Category create + expense row result are in same transaction where required.
- [ ] Failed row cannot leave stray category.
- [ ] Import coordinator checks read/write barrier before mutation.
- [ ] UI cannot call import utilities directly.
- [ ] Add static guard for importer DAO usage.
- [ ] Add tests for import during restore and failed row rollback.

### Acceptance Criteria

- [ ] Importers do not inject or call DAOs.
- [ ] Import mutations are coordinator-owned, barrier-checked, and transactional.

---

## Phase 7 — Worker/Background Access Integration

### Goal

Workers must not bypass barriers through repositories or DAOs.

### Tasks

- [ ] Ensure worker guard checks maintenance/restart-required before DAO read/write.
- [ ] Ensure DB-writing workers call approved repositories/coordinators.
- [ ] Ensure notification worker checks barrier before first DAO read.
- [ ] Ensure bill reminder worker permission/guard path also respects DB barrier.
- [ ] Ensure retention/repair workers do not bypass access gate.
- [ ] Add static guard for worker DB access.
- [ ] Add tests for worker starting during restore/restart-required.

### Acceptance Criteria

- [ ] Worker cannot read/write stale DB during restore.
- [ ] Worker cannot bypass barrier via direct DAO injection.

---

## Phase 8 — Hidden Write and Read-Named Method Cleanup

### Goal

Methods that look like queries must not mutate DB unexpectedly.

### Known examples

- `getDueReminders()` stale-claim recovery writes.
- `reconcilePlannedVsActual()` hidden generate/write path.
- accounting reads that may depend on stale category/source access.
- recovery/repair methods hidden inside intake paths.

### Tasks

- [ ] Identify all read-named methods with writes.
- [ ] Split into:
  - pure read method,
  - explicit repair/recovery write method.
- [ ] Add write barrier to explicit write method.
- [ ] Rename methods to reveal mutation.
- [ ] Add static guard for write DAO calls inside read/query-named methods where feasible.
- [ ] Add tests proving pure reads do not write.

### Acceptance Criteria

- [ ] Query/read methods are side-effect free unless explicitly documented and gated.
- [ ] Hidden writes fail guard or test.

---

## Phase 9 — Static Guard and CI Enforcement

### Goal

Make violations impossible to merge.

### Guards required

- [ ] DAO access boundary guard.
- [ ] UI/ViewModel DAO guard.
- [ ] Import lifecycle/DAO guard.
- [ ] Worker DB access guard.
- [ ] Hidden write in read method guard.
- [ ] Restore-internal scope/token guard.
- [ ] DAO allowlist owner/reason/expiry guard.

### Guard must fail on

- DAO injection into `ui/**`.
- DAO constructor param in `*ViewModel`.
- DAO write call outside allowed owner.
- Importer class calling DAO.
- Worker class injecting DAO without full guard.
- `requires_write_barrier:false` without owner/reason/expiry.
- Restore-internal write scope without maintenance token.
- Expired allowlist entry.

### Acceptance Criteria

- [ ] Bad fixtures fail CI.
- [ ] Existing allowlist is minimal and expiring.
- [ ] New direct DAO write cannot merge.

---

# 9. Testing Strategy

## 9.1 Unit Tests

### Access gate

- [ ] normal state allows normal read/write.
- [ ] maintenance active blocks normal write.
- [ ] maintenance active blocks unsafe read.
- [ ] restore owner token allows approved restore-internal write.
- [ ] invalid token blocks restore-internal write.
- [ ] restart-required blocks reads/writes.
- [ ] snapshot owner allows approved export read.

### DAO ownership

- [ ] allowed owner can call DAO through repository/coordinator.
- [ ] disallowed owner blocked by static guard.
- [ ] expired allowlist fails.

---

## 9.2 Integration Tests

- [ ] notification intake write blocked during restore.
- [ ] notification repair write blocked during restore unless approved.
- [ ] bill reminder stale-claim recovery blocked unless explicit write method.
- [ ] bank disconnect blocked during restore.
- [ ] import category creation blocked during restore.
- [ ] failed import row leaves no category.
- [ ] accounting/export read behavior during restore is defined.
- [ ] UI flow collection during restore shows blocked state.
- [ ] old ViewModel action after restore-required is blocked.

---

## 9.3 UI Tests

- [ ] Bank disconnect button during restore shows maintenance-blocked state.
- [ ] Import button during restore is disabled or returns blocked state.
- [ ] Dashboard/expense list does not read stale DB during restart-required.
- [ ] Deep link cannot trigger DB read during restart-required.
- [ ] Snackbar/error messages are sanitized.

---

## 9.4 Static Guard Tests

Every guard must have:

- [ ] positive fixture.
- [ ] negative fixture.
- [ ] allowlisted fixture.
- [ ] expired allowlist fixture.
- [ ] owner/reason/expiry validation.

---

# 10. Rollout PR Plan

## PR 1 — DAO Access Inventory and Policy

Includes:

- `DAO_ACCESS_INVENTORY.md`.
- `DAO_OWNERSHIP_POLICY.md`.
- current allowlist cleanup plan.
- classification of read/write/mixed DAO methods.

Acceptance:

- [ ] Every DAO method classified.
- [ ] Every direct DAO injection has owner or violation.

---

## PR 2 — Access Gate Semantics

Includes:

- central lifecycle-aware access gate.
- typed block reasons.
- read/write barrier integration with maintenance/restart-required state.
- unit tests.

Acceptance:

- [ ] Access decisions are deterministic across lifecycle states.

---

## PR 3 — Write Barrier High-Risk Paths

Includes:

- notification intake/recovery/repair barrier checks.
- recurring/reminder hidden write split.
- bank disconnect barrier path.
- import category/expense write barrier scaffold.

Acceptance:

- [ ] Known high-risk writes are blocked during restore.
- [ ] Restore-internal writes require owner token.

---

## PR 4 — Read Barrier / Flow Safety

Includes:

- Room Flow/read inventory enforcement.
- UI-facing flow wrappers.
- accounting/export read-barrier fixes.
- snapshot read policy.

Acceptance:

- [ ] Unsafe reads do not continue during restore/restart-required.

---

## PR 5 — UI/ViewModel DAO Removal

Includes:

- remove bank DAO from ViewModel.
- route disconnect through coordinator/repository.
- ban ViewModel DAO injection guard.
- tests.

Acceptance:

- [ ] No mutating DAO in ViewModel/UI.
- [ ] Bank disconnect is legal and barrier-owned.

---

## PR 6 — Importer DAO Removal

Includes:

- importers become pure parsers/mappers.
- category creation through legal owner.
- import coordinator owns mutations.
- importer DAO guard.

Acceptance:

- [ ] Import cannot bypass barrier or category owner.

---

## PR 7 — Hidden Write Cleanup

Includes:

- rename/split read-named write methods.
- recurring/reminder stale recovery explicit write method.
- reconcile pure report vs write path split.
- tests.

Acceptance:

- [ ] Pure query methods do not write.

---

## PR 8 — Worker Access Integration

Includes:

- worker guard checks barrier before DB access.
- notification worker read-before-barrier fix.
- worker DB access static guard.
- tests.

Acceptance:

- [ ] Workers cannot access DB during restore/restart-required.

---

## PR 9 — Static Guard CI Enforcement

Includes:

- DAO boundary guard.
- UI DAO guard.
- import DAO guard.
- restore-internal token guard.
- allowlist expiry validation.
- fixtures/tests.

Acceptance:

- [ ] New violations fail CI.
- [ ] All exceptions are documented and expiring.

---

## PR 10 — Final Regression Suite and Tracker Update

Includes:

- end-to-end restore/import/reset DB access tests.
- stale ViewModel/repository action tests.
- docs updates.
- tracker closing SHAs.

Acceptance:

- [ ] MIT-030, MIT-036, MIT-077, MIT-060 closure criteria met.

---

# 11. Edge Cases

## Restore active while dashboard Flow is collecting

Expected:

- Flow emits maintenance/restart-blocked UI state or stops safely.
- No stale DB read continues after restart-required.

---

## Import starts during restore

Expected:

- rejected before parser creates categories/expenses.
- diagnostic is sanitized.
- no partial import rows/categories.

---

## Bank disconnect during restart-required

Expected:

- blocked.
- no DAO call.
- UI shows restart-required state.

---

## Backup export during maintenance

Expected:

- allowed only if snapshot/export session is explicitly approved.
- otherwise blocked.
- reads are from stable snapshot, not half-restored DB.

---

## Restore-internal repair code

Expected:

- requires maintenance owner token.
- cannot be called by normal repositories/UI.
- access is logged/diagnosed.

---

## Existing stale repository holds DAO

Expected:

- direct DAO cannot be fully stopped by barrier if repository bypasses it.
- therefore static guard and owner refactor are mandatory.
- after refactor, stale repository action hits access gate and blocks.

---

# 12. Documentation Requirements

Create/update:

```text
docs/db/DAO_ACCESS_INVENTORY.md
docs/db/DAO_OWNERSHIP_POLICY.md
docs/db/READ_WRITE_BARRIER_POLICY.md
docs/db/READ_BARRIER_FLOW_POLICY.md
docs/db/RESTORE_INTERNAL_SCOPE_POLICY.md
docs/testing/DB_ACCESS_BARRIER_TESTS.md
```

Update:

```text
docs/MASTER_ISSUE_TRACKER.md
docs/MASTER_ISSUE_TRACKER_SUPPLEMENT_2.md
```

Each closed issue should include:

- closing commit SHA,
- affected classes,
- tests added,
- guards added,
- remaining allowlist entries.

---

# 13. Metrics to Track

| Metric | Target |
|---|---|
| Direct DAO injections in UI/ViewModels | 0 |
| Importer DAO writes | 0 |
| DB writes without write barrier | 0 |
| Unsafe DB reads without read barrier | 0 or justified |
| Hidden write read-named methods | 0 |
| `requires_write_barrier:false` entries | minimal and expiring |
| Restore-internal writes without token | 0 |
| Expired allowlist entries | 0 |
| DAO boundary guard failures on main | 0 |

---

# 14. Risks and Mitigations

## Risk: Too many existing violations

Mitigation:

- fix S0 paths first,
- allowlist only temporary S1/S2 paths,
- owner/reason/expiry required,
- burn down allowlist.

## Risk: Read barrier creates UI churn

Mitigation:

- use typed maintenance/restart UI states,
- block at navigation/root where possible,
- avoid per-screen duplicated handling.

## Risk: Static guard false positives

Mitigation:

- fixture tests,
- precise allowlist,
- staged fail-on-violation for low-risk rules,
- immediate fail for UI mutating DAO and restore-internal token violations.

## Risk: Barriers cannot protect direct DAO calls

Mitigation:

- DAO ownership refactor is mandatory,
- static guard prevents new bypasses,
- Hilt/DI graph proof supports enforcement.

## Risk: Snapshot/export reads conflict with restore

Mitigation:

- explicit snapshot owner/session,
- no snapshot reads during DB swap unless using temp/stable copied DB,
- tests for write attempts during snapshot.

---

# 15. Definition of Done by MIT

## MIT-030 can close when

- [ ] Every known mutating DB path has write barrier.
- [ ] Restore/reset/import maintenance blocks normal writes.
- [ ] Restore-internal writes require owner token.
- [ ] High-risk paths have regression tests.
- [ ] Static guard rejects ungated mutating DAO access.

## MIT-036 can close when

- [ ] DAO ownership policy exists and is machine-checkable.
- [ ] Source, docs, and allowlist agree.
- [ ] Mutating DAOs are reachable only from approved lifecycle owners.
- [ ] Importer/UI direct mutating DAO access is removed.
- [ ] Allowlist entries are minimal, owned, and expiring.

## MIT-077 can close when

- [ ] Room Flow/read entry points are inventoried.
- [ ] Unsafe DB reads are gated or safely degraded during restore.
- [ ] Restart-required blocks normal DB reads.
- [ ] Snapshot reads have explicit owner/session.
- [ ] UI flow restore/restart tests pass.

## MIT-060 can close when

- [ ] No DAO is injected into ViewModels for mutation.
- [ ] Bank disconnect is routed through lifecycle coordinator/repository.
- [ ] Write barrier and operation lifecycle are enforced.
- [ ] UI receives typed sanitized blocked/error state.
- [ ] UI DAO static guard is blocking in CI.

---

# 16. Final Completion Checklist

This plan is complete when:

- [ ] DAO access inventory exists.
- [ ] DAO ownership policy exists.
- [ ] Central access gate is implemented.
- [ ] Write barrier covers all mutating paths.
- [ ] Read barrier covers unsafe reads/Flows.
- [ ] Restore-internal scope requires maintenance token.
- [ ] UI/ViewModels have no direct mutating DAO access.
- [ ] Importers have no direct DAO writes.
- [ ] Bank disconnect uses legal owner.
- [ ] Hidden write methods are split/renamed.
- [ ] Workers cannot bypass DB access gate.
- [ ] Static guards enforce DAO ownership and barriers.
- [ ] Allowlist policy is enforced.
- [ ] Restore/import/reset regression tests pass.
- [ ] Master tracker is updated with closing SHAs.

---

# 17. Recommended First Action

Start with:

```text
PR 1 — DAO Access Inventory and Ownership Policy
```

Then:

```text
PR 2 — Access Gate Semantics
PR 3 — Write Barrier High-Risk Paths
PR 4 — Read Barrier / Flow Safety
PR 5 — UI/ViewModel DAO Removal
```

Do not rely on barriers alone.  
The real fix is **barriers + legal ownership + static guards**.