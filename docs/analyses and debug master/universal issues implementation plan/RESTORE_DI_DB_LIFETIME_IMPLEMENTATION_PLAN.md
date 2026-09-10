# Restore / DI / DB Lifetime Implementation Plan

Last updated: 2026-06-15  
Scope: MIT-013, MIT-014, MIT-018, MIT-061, MIT-079  
Goal: prevent stale Room/Hilt singleton DB usage after restore/reset/import by enforcing a hard restart contract first, while keeping a future reopenable DB provider possible.

---

## 1. Executive Decision

Use **hard restart after DB file replacement** as the first production-safe strategy.

### Why hard restart first

The app currently appears to use Hilt singleton `AppDatabase` / DAO dependencies. If restore/reset/import swaps the DB file while old repositories, workers, ViewModels, flows, or app-scope coroutines still hold old Room/DAO references, the app can enter split-brain state.

A full reopenable DB provider is a larger refactor. Hard restart is safer and faster.

### Chosen strategy

After any operation that replaces or invalidates the database file:

- restore,
- destructive reset,
- full import-as-restore,
- backup restore,
- database repair that swaps files,

the app must enter a **Restart Required** state.

In that state:

- old DB-backed UI cannot continue,
- DB reads/writes are blocked except approved internal finalization,
- workers are not rescheduled before restart,
- app-scope jobs are cancelled/suspended,
- the user is forced to restart the app,
- process restart or app relaunch is required before normal usage.

---

## 2. Covered Master Issues

| MIT | Issue | Covered Here |
|---|---|---|
| MIT-013 | Restore-safe DB lifetime strategy | Yes |
| MIT-014 | Maintenance owner/session tokens | Yes |
| MIT-018 | Stop app-scope stale coroutine/database usage after maintenance | Yes |
| MIT-061 | Restore UI must force restart or block stale DB usage | Yes |
| MIT-079 | DI binding matrix release proof | Yes |

Related but not fully owned:

| MIT | Relationship |
|---|---|
| MIT-016 | Workers must drain before restore; worker plan owns full worker guard |
| MIT-030 | Barriers block DB writes during restore; barrier plan owns global enforcement |
| MIT-077 | Read-barrier inventory; barrier plan owns global read policy |
| MIT-012 | Backup verifier/semantic aggregates depend on safe restore lifecycle |
| MIT-028 | Release security binding scan overlaps with DI binding proof |

---

## 3. Affected Pipelines

| Pipeline | Impact |
|---|---|
| P7 | Backup/restore safety |
| P9 | Worker drain and maintenance safety |
| P13 | DB/migration safety after restore |
| P14 | UI must not continue with stale DB |
| P15 | Hilt singleton lifetime |
| P16 | Security/network DI release binding proof |
| P18 | Import-as-restore / import mutation safety |

---

## 4. Current Problem

The reports indicate:

- `AppDatabase` and DAOs are likely Hilt singletons.
- Restore can replace DB file while other objects still hold stale DB/DAO references.
- `DatabaseBackupRepositoryImpl` may refresh only its own DB reference, not the whole app graph.
- UI may dismiss restart-required prompt and continue DB-backed work.
- Workers may be rescheduled before restart.
- App-scope coroutines/flows may survive maintenance.
- Maintenance mode lacks owner/session tokens, allowing overlapping operations to interfere.
- DI release/debug/stub binding safety is not fully proven.

---

## 5. Non-Negotiable Invariants

After this plan:

- [ ] No normal DB read/write may occur after DB file swap until app restart.
- [ ] Restore/reset/import cannot clear another operation’s maintenance state.
- [ ] Only the maintenance session owner can complete/cancel its session.
- [ ] Workers cannot write during restore or after restart-required state.
- [ ] Workers are not rescheduled before restart after DB swap.
- [ ] UI cannot dismiss restart-required state into normal app screens.
- [ ] App-scope coroutines using DB are cancelled or blocked before DB swap.
- [ ] Old ViewModels/repositories/flows cannot perform DB-backed actions after restore.
- [ ] Release DI graph does not include unsafe debug/demo/stub/no-op bindings.
- [ ] Tests prove stale DB references cannot write post-restore.

---

# 6. Target Architecture

---

## 6.1 Core Components

Introduce or formalize these components:

### `DatabaseLifecycleController`

Single owner for DB lifecycle states.

Responsibilities:

- normal,
- maintenance entering,
- maintenance active,
- db swap in progress,
- restart required,
- restart completed/new process.

It should expose observable state for UI/workers/barriers.

---

### `MaintenanceSessionManager`

Owns maintenance sessions.

Responsibilities:

- create session token,
- track owner,
- prevent unauthorized clear,
- support nested/overlapping policy,
- expose active maintenance state,
- record sanitized diagnostics.

---

### `DatabaseAccessGate`

Central gate used by read/write barriers.

Responsibilities:

- block reads/writes during maintenance,
- block all normal DB access during restart-required state,
- allow only explicitly approved restore-internal writes.

This plan defines required behavior; MIT-030/MIT-077 implement global usage.

---

### `RestartRequiredController`

Persists and exposes restart-required state.

Responsibilities:

- latch restart-required after DB swap,
- persist enough state so process death before UI display still blocks old session,
- provide restart reason,
- prevent dismissal into app,
- coordinate process exit/relaunch.

---

### `AppScopeJobRegistry`

Tracks long-lived app-scope jobs.

Responsibilities:

- register DB-backed jobs,
- cancel/suspend them before maintenance,
- prevent restart after DB swap until process restart,
- expose diagnostics for jobs that failed to stop.

---

### `WorkerMaintenanceCoordinator`

Coordinates worker behavior during restore.

Responsibilities:

- request worker drain before DB swap,
- block new work while maintenance active,
- prevent worker reschedule when restart-required,
- rely on worker leases/full guard from worker plan.

---

### `DIBindingVerifier`

Static/test proof for release/debug Hilt binding graph.

Responsibilities:

- verify release graph has no unsafe fake/demo/stub/no-op providers,
- verify no long-lived singleton DAO safety violation under hard-restart policy,
- verify network/security/diagnostic bindings are release-safe.

---

## 6.2 Restore State Machine

Recommended state sequence:

```text
NORMAL
  -> MAINTENANCE_REQUESTED
  -> MAINTENANCE_ACTIVE
  -> WORKERS_DRAINED
  -> APP_SCOPE_JOBS_STOPPED
  -> DB_ACCESS_BLOCKED
  -> DB_SWAP_IN_PROGRESS
  -> DB_SWAP_COMMITTED
  -> RESTART_REQUIRED
  -> PROCESS_EXIT_OR_RELAUNCH
  -> NORMAL_IN_NEW_PROCESS
```

Any failure before `DB_SWAP_COMMITTED`:

```text
MAINTENANCE_ACTIVE -> ROLLBACK_OR_ABORT -> NORMAL
```

Any failure after `DB_SWAP_COMMITTED`:

```text
DB_SWAP_COMMITTED -> RESTART_REQUIRED
```

Never return to normal app state after committed DB swap in same process.

---

## 6.3 Hard Restart Contract

### Operations requiring hard restart

- [ ] Full backup restore.
- [ ] DB file swap.
- [ ] destructive reset.
- [ ] import path that replaces/restores DB.
- [ ] migration repair that closes/replaces DB.
- [ ] restore from `.costbackup`.

### Operations not requiring hard restart

Only if they do not replace DB file and are fully barrier/transaction-safe:

- normal expense import into current DB,
- normal CSV/JSON row import,
- backup export,
- read-only verification,
- receipt asset repair without DB swap.

Each exception must be documented.

---

# 7. Implementation Phases

---

## Phase 0 — Inventory and Baseline

### Goal

Identify all stale-reference risks before changing lifecycle.

### Tasks

- [ ] Inventory all Hilt providers for `AppDatabase`.
- [ ] Inventory all DAO providers.
- [ ] Inventory repositories holding DAOs in constructor.
- [ ] Inventory ViewModels holding DAOs/repositories with long-lived flows.
- [ ] Inventory workers using DB/DAOs/repositories.
- [ ] Inventory app-scope coroutines and startup jobs.
- [ ] Inventory flows exposed directly from DAOs.
- [ ] Inventory restore/reset/import paths that replace DB file.
- [ ] Inventory current restart-required UI behavior.
- [ ] Inventory debug/release/fake/stub/no-op Hilt bindings.
- [ ] Create `docs/restore/RESTORE_DB_LIFETIME_BASELINE.md`.

### Local search examples

```bash
rg "AppDatabase|@Provides.*Dao|Dao" app/src
rg "@Singleton" app/src/main/java
rg "CoroutineScope|applicationScope|GlobalScope|launch" app/src/main/java
rg "restore|reset|import|swap|database file|close\\(" app/src/main/java
rg "RestartRequired|restart required|ProcessPhoenix|exitProcess" app/src/main/java
rg "Fake|Stub|NoOp|Demo|@StubForDemo" app/src
```

### Acceptance Criteria

- [ ] All DB singleton providers are known.
- [ ] All DB swap paths are known.
- [ ] All app-scope DB jobs are known.
- [ ] All restart-required UI paths are known.

---

## Phase 1 — Formalize DB Lifetime Policy

### Goal

Document and enforce the hard restart decision.

### Tasks

- [ ] Create `docs/restore/DB_LIFETIME_POLICY.md`.
- [ ] Document hard-restart strategy.
- [ ] List operations that require restart.
- [ ] List operations that do not require restart.
- [ ] Document why reopenable DB provider is deferred.
- [ ] Add warning to code comments near restore DB swap.
- [ ] Add tracker links to MIT-013/MIT-061.

### Required policy wording

The policy should say:

> In the current architecture, `AppDatabase` and DAOs may be held by Hilt singletons. Therefore, after any operation that replaces the DB file, the same process is no longer allowed to perform normal DB-backed work. The app must enter restart-required mode and force process restart/relaunch before normal operation resumes.

### Acceptance Criteria

- [ ] No ambiguity around DB file swap behavior.
- [ ] Future developers understand why hot-swap is forbidden for now.

---

## Phase 2 — Maintenance Session Tokens

### Goal

Prevent overlapping maintenance operations from clearing or corrupting each other’s lifecycle state.

### Tasks

- [ ] Implement maintenance session token creation.
- [ ] Store:
  - session ID,
  - owner type,
  - owner operation ID,
  - start timestamp,
  - reason,
  - current phase.
- [ ] Only the owner token can complete/cancel its session.
- [ ] Define overlap policy:
  - reject concurrent restore/reset,
  - allow read-only backup only if safe,
  - block import during restore.
- [ ] Add sanitized diagnostics for rejected maintenance attempts.
- [ ] Add session timeout/stale session recovery policy.
- [ ] Add tests for overlapping sessions.

### Owner examples

```text
BACKUP_RESTORE
DATABASE_RESET
FULL_IMPORT_RESTORE
DATABASE_REPAIR
BACKUP_EXPORT
```

### Token rules

- [ ] Token is unguessable or internal-only.
- [ ] Token must be passed to privileged restore-internal write scope.
- [ ] Token is required to release maintenance.
- [ ] Token cannot release another owner’s session.
- [ ] Expired/stale token recovery is explicit and diagnostic-backed.

### Acceptance Criteria

- [ ] Restore and import cannot clear each other’s maintenance state.
- [ ] Concurrent operations produce deterministic result.
- [ ] Tests cover owner mismatch, double-complete, timeout, and cancellation.

---

## Phase 3 — Restart Required Latch

### Goal

Once DB swap commits, same-process normal DB access is impossible.

### Tasks

- [ ] Add restart-required state.
- [ ] Persist restart-required marker outside the swapped DB if needed.
- [ ] Include reason:
  - restore completed,
  - reset completed,
  - import restore completed,
  - repair completed.
- [ ] Expose state to:
  - UI,
  - access gates,
  - workers,
  - app startup.
- [ ] Ensure state is set immediately after committed DB swap.
- [ ] Ensure failure after DB swap still results in restart required.
- [ ] Ensure marker is cleared only in new process/startup after DB is reopened cleanly.
- [ ] Add tests.

### Storage recommendation

Store restart-required marker outside the app DB, for example:

- DataStore,
- SharedPreferences,
- file marker in app storage.

Reason: the DB itself may be swapped/closed.

### Acceptance Criteria

- [ ] After committed DB swap, normal DB access is blocked.
- [ ] UI sees restart-required state even if restore flow crashes after swap.
- [ ] Marker cannot be cleared by dismissing a dialog.

---

## Phase 4 — Restore Operation Flow Hardening

### Goal

Make DB restore/reset/import lifecycle deterministic.

### Required flow

1. Acquire maintenance session token.
2. Set app to maintenance active.
3. Block new DB writes.
4. Block unsafe DB reads.
5. Drain workers.
6. Cancel/suspend app-scope DB jobs.
7. Flush diagnostics.
8. Close Room database.
9. Perform DB file swap / restore.
10. Restore assets if part of same operation.
11. Verify restored DB enough to decide success.
12. Set restart-required latch.
13. Do not resume normal DB-backed app.
14. Show restart-required UI or trigger restart.
15. Release/mark maintenance as completed only under restart-required state.

### Tasks

- [ ] Update backup restore path to follow this flow.
- [ ] Update reset path to follow this flow.
- [ ] Update import-as-restore path if present.
- [ ] Remove repository-local DB hot-swap as normal-app strategy.
- [ ] Prevent rescheduling workers before restart.
- [ ] Record operation outcome in durable sanitized location.
- [ ] Ensure exceptions after DB swap do not return app to normal state.

### Critical rule

After step 9, there is no path back to normal UI in the same process.

### Acceptance Criteria

- [ ] Restore cannot complete and then let user continue normally.
- [ ] Any post-swap exception still forces restart.
- [ ] Workers are not rescheduled before restart.

---

## Phase 5 — UI Restart Wall

### Goal

Prevent user from continuing into DB-backed screens after restore.

### Tasks

- [ ] Add global restart-required screen/state.
- [ ] Show it above all DB-backed navigation.
- [ ] Disable normal navigation/actions.
- [ ] Remove “dismiss and continue” behavior.
- [ ] Provide only safe actions:
  - restart now,
  - close app,
  - maybe copy sanitized diagnostic ID.
- [ ] If automatic restart is supported, trigger it.
- [ ] If automatic restart is not supported, instruct user clearly to reopen app.
- [ ] Add tests for back button, rotation, process death, deep links, and notification taps.
- [ ] Ensure app startup checks marker before loading normal DB-backed screens.

### Unsafe UI actions while restart required

Must be blocked:

- expense list/dashboard,
- import/export actions,
- backup/restore,
- bank sync/connect/disconnect,
- receipt scan/OCR,
- notification intake views,
- settings that touch DB,
- recurring/reminder actions.

### Acceptance Criteria

- [ ] Restart-required UI cannot be dismissed into normal app.
- [ ] Deep links cannot bypass restart wall.
- [ ] Notification tap cannot bypass restart wall.
- [ ] Back navigation cannot bypass restart wall.

---

## Phase 6 — App-Scope Coroutine and Flow Shutdown

### Goal

Stop long-lived jobs from using stale DB/DAO references during or after restore.

### Tasks

- [ ] Create app-scope job registry.
- [ ] Register DB-backed app-scope jobs.
- [ ] Add maintenance callback:
  - pause/cancel jobs before DB swap,
  - await cancellation with timeout,
  - diagnose jobs that fail to stop.
- [ ] Prevent restart of those jobs while restart-required.
- [ ] Ensure startup restarts them only in new process/normal state.
- [ ] Audit long-lived flows collected in application scope.
- [ ] Add tests with fake DB-backed job that tries to write during restore.

### Job categories

- startup sync,
- currency/rate refresh,
- retention,
- notification repair,
- recurring/reminder scheduling,
- bank sync,
- backup/export/import monitoring,
- analytics cache refresh,
- dashboard precomputation.

### Acceptance Criteria

- [ ] DB-backed app-scope jobs stop before DB swap.
- [ ] Jobs cannot restart until new process normal state.
- [ ] Failed cancellation is visible in diagnostics.

---

## Phase 7 — Worker Coordination

### Goal

Workers cannot race restore or run after DB swap in stale process.

### Tasks

- [ ] Before DB swap, request worker drain.
- [ ] Wait for active worker leases to finish or cancel.
- [ ] Block new worker starts while maintenance active.
- [ ] Ensure worker guard checks restart-required state before DB access.
- [ ] Do not reschedule periodic/one-shot workers before restart.
- [ ] If WorkManager itself restarts work, worker must exit/retry safely without DB write.
- [ ] Add tests for active worker during restore.
- [ ] Add tests for worker starting after restart-required latch.

### Dependencies

This phase depends on worker plan for:

- lease registry fix,
- full worker guard,
- run ledger.

### Acceptance Criteria

- [ ] Active workers cannot write during DB swap.
- [ ] Worker started in restart-required state does not access stale DB.
- [ ] Workers are rescheduled only after clean new-process startup.

---

## Phase 8 — DB Access Gate Integration

### Goal

Even if stale references exist, normal DB access should fail closed during restart-required state.

### Tasks

- [ ] Ensure write barrier checks restart-required state.
- [ ] Ensure read barrier checks restart-required state for unsafe reads.
- [ ] Ensure restore-internal writes require maintenance token.
- [ ] Ensure ViewModel/repository actions check gate or go through gated owner.
- [ ] Add tests for stale repository attempting write after restart-required.
- [ ] Add tests for stale flow/read attempting access after restart-required.

### Important limitation

A barrier cannot protect code paths that bypass it. Therefore this phase must be paired with DAO ownership/static guard work.

### Acceptance Criteria

- [ ] Gated stale DB access fails closed.
- [ ] Restore-internal access requires correct token.
- [ ] Owner-token mismatch blocks internal write.

---

## Phase 9 — DI Binding Matrix Release Proof

### Goal

Prove the release Hilt graph is safe under hard-restart architecture.

### Tasks

- [ ] Generate or inspect Hilt binding graph for debug and release.
- [ ] List every binding that provides:
  - `AppDatabase`,
  - DAOs,
  - repositories holding DAOs,
  - workers,
  - network clients,
  - cloud providers,
  - security providers,
  - diagnostics writers,
  - currency/rate providers,
  - app-scope tasks.
- [ ] Verify release graph has no unsafe fake/demo/stub/no-op binding.
- [ ] Verify debug-only modules are not installed in release.
- [ ] Verify demo bank API is release-disabled or unreachable.
- [ ] Verify diagnostics writers are sanitized.
- [ ] Verify cloud/network providers are privacy-gated.
- [ ] Verify currency/rate providers do not hold stale DB state after restore, or are stopped by app-scope registry.
- [ ] Add static/CI guard for release binding risks.

### Deliverable

Create:

```text
docs/di/DI_BINDING_MATRIX.md
```

Columns:

```md
| Binding | Scope | Debug impl | Release impl | Holds DB/DAO? | Restore behavior | Release safe? | Owner |
```

### Acceptance Criteria

- [ ] Release DI graph is documented.
- [ ] No unsafe fake/demo/stub/no-op release binding exists.
- [ ] Long-lived DB-holding bindings are safe only because hard restart blocks same-process reuse.
- [ ] Future reopenable DB migration path is documented.

---

# 8. Future Option: Reopenable DB Provider

This plan does not implement it now, but design must not block it.

## Future target

Replace direct singleton DB/DAO usage with:

- current DB provider,
- per-operation DAO retrieval,
- atomic DB swap,
- invalidated old flows,
- restart not required.

## Do not do now unless necessary

- Do not refactor every repository to fetch DAOs dynamically in this plan.
- Do not allow live DB hot-swap halfway.
- Do not mix hard restart and partial reopenable behavior.

## Compatibility requirement

Any new abstraction should allow later migration to reopenable provider.

---

# 9. Testing Strategy

---

## 9.1 Unit Tests

### Maintenance sessions

- [ ] owner can complete own session,
- [ ] owner mismatch cannot complete,
- [ ] concurrent restore/import conflict,
- [ ] nested/overlap behavior deterministic,
- [ ] stale session recovery requires diagnostic.

### Restart required

- [ ] DB swap sets latch,
- [ ] latch persists outside DB,
- [ ] latch blocks normal access,
- [ ] latch survives process death before UI display,
- [ ] latch only clears on clean new startup.

### Access gates

- [ ] write blocked during maintenance,
- [ ] read blocked during restart-required if unsafe,
- [ ] restore-internal write requires token,
- [ ] stale repository write fails closed.

### App-scope jobs

- [ ] job cancels before DB swap,
- [ ] job that refuses cancellation produces diagnostic,
- [ ] job cannot restart while restart-required.

---

## 9.2 Integration Tests

- [ ] Full restore flow reaches restart-required.
- [ ] Restore failure before DB swap returns safely to normal.
- [ ] Restore failure after DB swap remains restart-required.
- [ ] Reset flow reaches restart-required.
- [ ] Import-as-restore flow reaches restart-required, if supported.
- [ ] Active worker during restore is drained/cancelled.
- [ ] Worker starting during restart-required exits safely.
- [ ] Old ViewModel action after restore is blocked.
- [ ] Repository-local DB refresh does not create split-brain behavior.

---

## 9.3 UI Tests

- [ ] Restart-required screen appears after restore.
- [ ] Back button cannot enter normal app.
- [ ] Dialog dismissal cannot enter normal app.
- [ ] Deep link cannot bypass restart wall.
- [ ] Notification tap cannot bypass restart wall.
- [ ] Rotation/config change keeps restart wall.
- [ ] Process death/reopen behaves correctly.
- [ ] Restart button exits/relaunches as expected.

---

## 9.4 DI/Release Tests

- [ ] Release graph contains expected DB provider only.
- [ ] No debug/demo/stub/no-op binding in release.
- [ ] Demo bank implementation unreachable in release.
- [ ] Cloud providers are privacy-gated.
- [ ] Diagnostics providers are sanitized.
- [ ] Network/security providers are release-safe.

---

## 9.5 Static Guards

Needed guards:

- [ ] no direct DAO injection into UI/ViewModels,
- [ ] no DB-writing worker without full guard,
- [ ] no restore DB file swap without restart-required latch,
- [ ] no maintenance completion without owner token,
- [ ] no worker reschedule in restart-required state,
- [ ] no release fake/demo/stub binding,
- [ ] no app-scope DB job unregistered with lifecycle controller.

---

# 10. Rollout PR Plan

---

## PR 1 — Baseline Inventory and Hard-Restart Policy

### Includes

- DB/DI/worker/UI restore lifetime inventory.
- `DB_LIFETIME_POLICY.md`.
- Decision: hard restart first.
- List of restart-required operations.

### Acceptance

- [ ] All DB swap paths identified.
- [ ] Policy is explicit.
- [ ] No behavior change required yet.

---

## PR 2 — Maintenance Session Tokens

### Includes

- `MaintenanceSessionManager`.
- owner tokens.
- overlap policy.
- stale session recovery.
- tests.

### Acceptance

- [ ] Concurrent restore/import/reset cannot clear each other.
- [ ] Owner mismatch tests pass.

---

## PR 3 — Restart Required Latch

### Includes

- persistent restart-required marker.
- controller/state exposure.
- DB access gate integration scaffold.
- tests.

### Acceptance

- [ ] DB swap can set restart-required.
- [ ] Marker survives process death.
- [ ] Marker cannot be dismissed.

---

## PR 4 — Restore Flow Hardening

### Includes

- restore/reset/import-as-restore flow uses session token.
- set restart-required after DB swap.
- remove normal same-process DB hot-swap behavior.
- prevent worker reschedule before restart.
- tests.

### Acceptance

- [ ] No post-swap path returns to normal state.
- [ ] Failure after swap still restart-required.

---

## PR 5 — UI Restart Wall

### Includes

- global restart-required UI.
- navigation blocking.
- deep-link/notification/back-button protection.
- UI tests.

### Acceptance

- [ ] User cannot continue with stale DB after restore.

---

## PR 6 — App-Scope Job Shutdown

### Includes

- app-scope job registry.
- maintenance cancellation hooks.
- restart-required no-restart rule.
- tests.

### Acceptance

- [ ] DB-backed app-scope jobs stop before swap.
- [ ] Jobs cannot resume before restart.

---

## PR 7 — Worker Coordination Integration

### Includes

- worker drain call before restore.
- worker restart-required checks.
- no worker reschedule before restart.
- integration tests.

### Acceptance

- [ ] Active/stale workers cannot write during or after restore.

---

## PR 8 — DI Binding Matrix and Release Guard

### Includes

- `DI_BINDING_MATRIX.md`.
- release/debug binding audit.
- static guard for fake/demo/stub/no-op release binding.
- release graph tests where feasible.

### Acceptance

- [ ] Release DI graph is documented and safe.
- [ ] Unsafe release binding fails CI.

---

## PR 9 — Final Stale Reference Regression Suite

### Includes

- old repository/ViewModel/worker stale reference tests.
- restore-after-worker-active tests.
- restart wall end-to-end test.
- tracker updates.

### Acceptance

- [ ] MIT-013, MIT-014, MIT-018, MIT-061, MIT-079 can close.

---

# 11. Handling Edge Cases

---

## Restore fails before DB swap

Expected:

- rollback/abort,
- release maintenance using owner token,
- return to normal,
- show sanitized failure.

Test:

- [ ] exception during preflight verification.

---

## Restore fails during DB swap

Expected:

- do not return to normal blindly,
- if DB may be inconsistent, restart-required or safe error state,
- preserve diagnostic outside DB.

Test:

- [ ] exception during file replace.

---

## Restore succeeds but asset restore fails

Policy decision required:

Option A:

- DB restored,
- asset restore failure recorded,
- restart required,
- app repairs assets after restart.

Option B:

- full restore considered failed,
- rollback DB and assets if possible,
- return normal only if DB swap rolled back.

Recommended:

- If DB file was committed, force restart-required.

---

## User kills app during restore

Expected:

- on next startup, detect maintenance/restart marker,
- recover to safe state,
- either resume/repair or require restart/safe recovery screen.

Test:

- [ ] simulated process death during maintenance.

---

## Worker starts from WorkManager after DB swap but before UI restart

Expected:

- worker guard sees restart-required,
- no DB access,
- returns retry/failure according to policy,
- emits sanitized diagnostic.

---

## Deep link opens app during restart-required

Expected:

- route to restart wall,
- preserve deep link only after restart if safe,
- no DB read before wall.

---

# 12. Documentation Requirements

Create/update:

```text
docs/restore/DB_LIFETIME_POLICY.md
docs/restore/RESTORE_DB_LIFETIME_BASELINE.md
docs/restore/MAINTENANCE_SESSION_POLICY.md
docs/restore/RESTART_REQUIRED_UI_POLICY.md
docs/di/DI_BINDING_MATRIX.md
docs/testing/RESTORE_STALE_DB_REGRESSION_TESTS.md
```

Update:

```text
docs/MASTER_ISSUE_TRACKER.md
docs/MASTER_ISSUE_TRACKER_SUPPLEMENT_2.md
```

Each closed issue should list:

- closing commit SHA,
- tests added,
- guard added,
- remaining limitations,
- future reopenable-provider notes.

---

# 13. Metrics to Track

| Metric | Target |
|---|---|
| DB swap paths without restart latch | 0 |
| Maintenance release without owner token | 0 |
| UI bypasses of restart wall | 0 |
| DB-backed app-scope jobs unregistered | 0 |
| Workers rescheduled before restart after restore | 0 |
| Release fake/demo/stub bindings | 0 |
| Stale DB write regression tests | passing |
| Restart-required deep-link bypass tests | passing |

---

# 14. Risks and Mitigations

## Risk: Hard restart feels harsh to users

Mitigation:

- show clear message,
- explain restore completed and restart is required for safety,
- offer one-tap restart if technically possible.

## Risk: Process restart is unreliable on Android

Mitigation:

- support both automatic relaunch and manual close/reopen instructions,
- persistent marker prevents unsafe continuation.

## Risk: Existing code bypasses barriers

Mitigation:

- pair with DAO ownership/static guard plan,
- block UI with global restart wall,
- worker guard checks restart-required.

## Risk: App-scope jobs are hard to inventory

Mitigation:

- add registry,
- static search,
- fail CI for unregistered application-scope DB jobs where possible.

## Risk: Maintenance session gets stuck

Mitigation:

- token diagnostics,
- stale session recovery policy,
- safe startup recovery screen.

## Risk: Future reopenable DB refactor conflicts

Mitigation:

- keep lifecycle controller abstraction generic,
- document hard restart as current policy,
- avoid repository-local partial DB hot-swap.

---

# 15. Definition of Done by MIT

## MIT-013 can close when

- [ ] Hard restart or reopenable provider decision is documented.
- [ ] Hard restart is implemented for DB file swap operations.
- [ ] Same-process normal DB access is blocked after swap.
- [ ] Tests prove stale repository/ViewModel/worker cannot write post-restore.
- [ ] Repository-local DB hot-swap no longer creates split-brain behavior.

## MIT-014 can close when

- [ ] Maintenance sessions have owner tokens.
- [ ] Only owner can release session.
- [ ] Concurrent maintenance operations are deterministic.
- [ ] Tests cover owner mismatch and overlapping operations.

## MIT-018 can close when

- [ ] App-scope DB jobs are inventoried.
- [ ] DB-backed jobs stop before DB swap.
- [ ] Jobs cannot restart in restart-required state.
- [ ] Tests prove stale app-scope job cannot write.

## MIT-061 can close when

- [ ] Restart-required UI cannot be dismissed into normal app.
- [ ] Deep links, notification taps, and back navigation cannot bypass it.
- [ ] Worker reschedule before restart is blocked.
- [ ] UI tests pass.

## MIT-079 can close when

- [ ] DI binding matrix exists.
- [ ] Release graph has no unsafe fake/demo/stub/no-op binding.
- [ ] Long-lived DB/network/security bindings are restore-safe under hard-restart contract.
- [ ] CI/static guard covers release binding regressions.

---

# 16. Final Completion Checklist

This plan is complete when:

- [ ] Restore DB lifetime baseline is documented.
- [ ] Hard restart policy is documented.
- [ ] Maintenance owner tokens are implemented.
- [ ] Restart-required latch is persistent and authoritative.
- [ ] Restore/reset/import-as-restore flows set restart-required after DB swap.
- [ ] No post-swap path resumes normal UI.
- [ ] UI restart wall blocks all bypasses.
- [ ] App-scope DB jobs stop before restore.
- [ ] Workers are drained and not rescheduled before restart.
- [ ] Worker started in restart-required state cannot access DB.
- [ ] DB access gate blocks stale normal reads/writes.
- [ ] DI binding matrix is complete.
- [ ] Release binding guard is in CI.
- [ ] Stale DB regression tests pass.
- [ ] Master tracker is updated with closing SHAs.

---

# 17. Recommended First Action

Start with:

```text
PR 1 — Baseline Inventory and Hard-Restart Policy
```

Then:

```text
PR 2 — Maintenance Session Tokens
PR 3 — Restart Required Latch
PR 4 — Restore Flow Hardening
PR 5 — UI Restart Wall
```

Do not attempt a reopenable database provider until the hard-restart safety contract is implemented and tested.