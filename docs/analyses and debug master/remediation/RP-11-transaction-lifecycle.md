# RP-11 — Transaction lifecycle semantics (Pipeline 2 engine)

> **Scope class:** universal engine — `TransactionLifecycleCoordinator` is consumed by every pipeline that creates/edits expenses. **Mode:** strict (transaction lifecycle + money).
> **Files:** `TransactionLifecycleCoordinator` (domain/transaction/lifecycle/), `ExpenseDao` (data/database/dao/), `CurrencySettingsRepository` + impl (domain/currency/ + data/), `NotificationProcessingPipeline` (data/repository/), `TransactionsViewModel` (ui/ ⟂), `ExpenseRepository` (data/repository/).
> **PR shape:** 3 PRs — (11a) delete/missing-row semantics = P2-001 + P2-005; (11b) dedupe correctness = P2-002 + P2-003 + P2-004 + P2-006; (11c) correlation + hygiene = P2-007 + P2-008 + P2-010 + NEW-P2-016.
> **Depends on:** RP-01 (catch-pattern fixes in the same file land first).

---

## 11a — Delete & missing-row semantics

### P2-001 — Entity-overload `deleteExpense` reports success for a missing row (HIGH)

**Problem.** `TransactionLifecycleCoordinator.kt:2113-2145`: `getById(expense.id) ?: return@withTransaction` exits only the transaction lambda; flow continues to unconditional `planner.planDeleted` + `runBestEffortAfterCommit` (`:2137-2143`) and `Result.success` (`:2145`). The id-overload (`:2044-2067`) correctly returns failure and skips dispatch. Live caller: `TransactionsViewModel.kt:473` (double-tap / stale list) → UI says "deleted", no DELETED event, side effects fire against a nonexistent id.

**Fix design.** Mirror the id-overload exactly:
```kotlin
val fresh = expenseDao.getById(expense.id)
    ?: return@withTransaction DeleteOutcome.NotFound   // typed local outcome
// ... existing delete + event ...
when (outcome) {
    is Deleted -> { plan + dispatch; Result.success }
    is NotFound -> Result.failure(IllegalArgumentException("Expense not found: ${expense.id}"))
}
```
Keep the exception type/message identical to the id-overload so `ExpenseRepository.deleteExpense`'s `.getOrThrow()` mapping is unchanged.

**What it solves.** No phantom-success deletes, no side effects for missing rows, overload symmetry.

**Guardrails.** The golden contract tests (`TransactionLifecycleCoordinatorDbContractTest:247-291`) pin only the happy path — extend with a missing-row case for **both** overloads asserting identical outcomes. No behavior change for the happy path.

### P2-005 — Silent no-ops + unconditional dispatch across six update paths (MED)

**Problem.** `updateCategory` (`:1043/1069`), `updateLocation` (`:1107`), `updateMerchant` (`:1325/1375`), `updateType` (`:1405/1454`), `updateTransferDetails` (`:1485/1532`) silently `return@withTransaction` on missing rows — four of them then dispatch planner side effects unconditionally — while `updateBusinessExpensePatch` (`:1196-1199`) and `updateOwnershipDbOnlyV2` (`:1732-1739`) return typed `NotFound`. `updateTypeAndTransferDetails` (`:1558-1629`) has **no no-op check at all** (unchanged values rewrite the row, write a spurious UPDATED event, re-run full side effects).

**Fix design.**
1. Introduce a private per-method outcome (`Updated` / `NotFound` / `NoChange`) following the patch/ownership precedent; every update path returns it from the transaction.
2. Gate **all** post-txn work on `is Updated` (plan + dispatch + events). For `NoChange`: return success **without** event/dispatch (idempotent no-op — document in KDoc).
3. Add the missing no-change check to `updateTypeAndTransferDetails` (compare new values against the fresh row like its siblings do).
4. Public API note: these methods currently return `Result<Unit>`-style types — keep signatures; the outcome is internal. If any caller needs to distinguish NoChange later, that's a follow-up.
5. `updateTypeAndTransferDetails` has zero production callers (verified) — fix it anyway for symmetry, note in PR.

**What it solves.** No audit gaps (missing rows produce no events — previously *also* none, but with phantom success + phantom side effects); no wasted budget/anomaly dispatches; idempotent re-edits stop generating UPDATED events.

**Guardrails.** Crossover: `updateCategory`'s dispatch currently also serves `DefaultExpenseCategoryAssignmentService`-adjacent flows — the gating must not remove dispatches that happen today for **successful** updates (only missing/no-change paths change). Test each path's Updated case for unchanged dispatch counts.

**Tests.** Per path: missing row → NotFound + zero events + zero dispatch; unchanged input → NoChange + zero events; changed → existing behavior (event + dispatch).

---

## 11b — Dedupe correctness

### P2-002 — Conflict resolver ignores the `rawNotificationId` unique index (MED)

**Problem.** `insertAtomic` (IGNORE) returns −1 for a `dedupeKey` **or** `rawNotificationId` conflict (`Expense.kt:36, :44`); `resolveExistingIdAfterInsertConflict` (`:155-186`) checks only `findIdByDedupeKey` then the fuzzy 5-min/±0.01 window. An edited-then-reprocessed notification (dedupeKey drifted) misattributes to an unrelated neighbor or reports bogus `CREATE_INSERT_CONFLICT`; downstream `NotificationProcessingPipeline.kt:1352`'s assert then throws ISE and the raw notification is never marked processed (retry loop).

**Fix design.**
1. In the resolver, check **by source of the conflict**: when the request carries `rawNotificationId`, first `expenseDao.findIdByRawNotificationId(rawNotificationId)` (add the query — index already exists, unique) → hit = true duplicate resolution (`DuplicateSkipped(existingId)` with the *correct* row).
2. Order: rawNotificationId → dedupeKey → (STRICT mode: stop) → fuzzy window. Record which index resolved it in the diagnostic event (`resolutionIndex: RAW_NOTIFICATION_ID | DEDUPE_KEY | FUZZY_WINDOW`) — controlled constants.
3. At the pipeline (`:1340-1352`): replace the `check(...)` assert with explicit handling — if resolution says unresolved, mark the raw row processed with a `CONFLICT_UNRESOLVED` diagnostic (terminal, no retry storm) instead of throwing.

**What it solves.** Correct duplicate attribution for reprocessed notifications; no ISE/retry loops on drift.

**Guardrails.** No change to STRICT-mode semantics (never fuzzy — verified). New DAO read-only query only (index exists; no schema change). **Tests:** edited-notification reprocess → DuplicateSkipped on the right row; unresolved → terminal diagnostic, row processed.

### P2-003 — `bulkUpdateMerchant` can abort the whole rename on key collision (LOW-MED)

**Problem.** `:1964-1968`: per-row `updateMerchantAndKey` with no `findDuplicateIdCurrencyAware` guard (contrast `updateMerchant:1334-1347`, `updateType:1414-1427`) — renaming into a merchant whose (amount, day-bucket, currency, type) already exists hits the dedupeKey unique index → `SQLiteConstraintException` rolls back the **entire** bulk rename.

**Fix design.** Pre-check per row inside the loop (same query the single-row path uses): colliding row (other id) → skip that row + count `skippedDuplicate` in the result (bulk callers already receive per-outcome data — `bulkUpdateCategory` returns affected counts; mirror it) and continue; commit the rest. Event carries `skippedCount`. UI surfaces "N rows skipped (would create duplicates)" — check the bulk-edit screen's existing message handling.

**Guardrails.** Intra-set collisions are impossible (verified — shared old key); only cross-set (target-merchant) collisions hit this. Atomicity choice: per-row skip keeps the user's rename mostly-applied instead of all-failed — document the tradeoff. **Tests:** rename into colliding merchant → non-colliding rows applied, colliding counted; no exception escapes.

### P2-004 — `skipDeduplication=true` doesn't skip dedupe (LOW)

**Problem.** The flag bypasses only the pre-check (`:507-509`); the key is still generated (`:402`), the unique index applies, and the STANDARD resolver still returns `DuplicateSkipped` (`:646-688`) — contradicting the documented contract ("skip deduplication entirely", `CreateExpenseRequest.kt:65`). The notification auto-accept path (`NotificationProcessingPipeline.kt:1267`) sets it and handles `DuplicateSkipped` gracefully, so impact is a contract mismatch + narrow race.

**Fix design.** Make the contract true: when `skipDeduplication == true`, generate `dedupeKey = "skip:${UUID}"`-style unique key (or null if the schema allows — check column nullability; the unique index tolerates NULLs in SQLite) so no conflict can occur, and skip the resolver entirely. **Guardrail:** dedupeKey is audit/identity infrastructure — a `skip:` prefix must remain greppable and must never collide with real keys; document that skip-flagged expenses opt out of duplicate identity. Update `CreateExpenseRequest` KDoc to describe exactly this. **Tests:** skip-flagged insert twice with identical payloads → both succeed; normal path unchanged.

### P2-006 — Type-unchanged re-key without collision check (LOW, latent)

**Problem.** `updateTypeAndTransferDetails` (`:1561-1581, :1608`): when `existing.transactionType == newType`, the collision check is skipped but the recomputed canonical key is still persisted — legacy stale-key rows can hit the unique index.

**Fix design.** Run the collision check whenever `newDedupeKey != existing.dedupeKey` (string compare) regardless of type change — one condition edit. Zero production callers today (verified) so this is pure hardening; covered by 11a's symmetry tests for this method.

### NEW-P2-005 (tracked partial) — Category-assignment service bypasses planner side effects

**Problem.** `DefaultExpenseCategoryAssignmentService.assignCategoryIfUnset` (`:29-44`) is atomic, barrier-checked, CE-safe, and writes an `EXPENSE_CATEGORY_ASSIGNED` event — but bypasses the coordinator lifecycle: no before/after snapshots, no planner dispatch (a category change normally triggers budget-check/anomaly side effects, `TransactionSideEffectPlanner.kt:66-69`). Single production caller: `ReceiptLinkService.kt:296`.

**Fix design.** Route through the coordinator: replace the service's private transaction with a call to `transactionLifecycleCoordinator.updateCategory(expenseId, category, source = RECEIPT_LINK_ASSIGNMENT, createReview = false …)` — the coordinator's update path (post-11a: NotFound-aware, event-writing, planner-dispatching) provides exactly the missing semantics. Keep the `assignCategoryIfUnset` conditional guard (read current category first; no-op if already set) in front of the coordinator call, and keep the typed outcome mapping for the caller. Delete the service's custom event write (the coordinator's UPDATED/CATEGORY event supersedes it — verify the `ReceiptLinkService` consumer doesn't depend on the `EXPENSE_CATEGORY_ASSIGNED` event type; if any listener does, keep writing it via the coordinator's event writer, not a private transaction).

**Guardrails.** This is a lifecycle-legal-path restoration (the whole point of the coordinator); the planner dispatch introduces budget-recheck cost on receipt-link — measure nothing, it's the same dispatch any manual category edit makes. Land with 11a (same method family). **Tests:** assignment writes coordinator events + dispatches planner batch; unset-guard still skips when a category exists.

---

## 11c — Correlation & hygiene

### P2-007 — Transfer paths drop `correlationId` (LOW)

**Problem.** `updateTransferDetails` (`:1473-1479`) and `updateTypeAndTransferDetails` (`:1546-1552`) accept no `correlationId`; their UPDATED events store null and `planUpdated(..., null, ...)` synthesizes a fresh unrelated id (`TransactionSideEffectPlanner.kt:56`) — transfer-edit audit correlation is broken (live via `TransactionsViewModel:567`).

**Fix design.** Add `correlationId: String? = null` parameter to both (default null keeps all call sites compiling), thread it into the event write and `planUpdated` — matching every sibling update path (`:1369`, `:1448` pattern). Update the ViewModel call to pass its existing corrId source (check how siblings obtain it — `CorrelationIds` util).

**Tests.** Transfer edit with corrId → event + side-effect batch share it.

### P2-008 — `isNotMine` filter drift between dedupe paths (LOW)

**Problem.** Blocking dedupe queries (`ExpenseDao.kt:546-650`, composed into `isDuplicateCurrencyAware:780` / `findDuplicateIdCurrencyAware:852`) include not-mine rows; the suggestion query (`:969`) excludes them (`AND isNotMine = 0`) → a not-mine row can *block* creation yet never appears in review candidates.

**Fix design — decision embedded:** re-validation judged the split likely intentional (prevention counts other-party charges; suggestions shouldn't surface them). Make the intent explicit rather than changing behavior: add `AND isNotMine = 0` **only** to `findDuplicateIdCurrencyAware`'s fuzzy resolution variant used for *misattribution* (P2-002's resolver should never point at a not-mine row), keep the blocking `exists...` queries as-is, and document the policy in the DAO KDoc ("blocking counts all rows; resolution/suggestion excludes not-mine"). If product wants not-mine excluded from blocking too, that's a product decision — flag, don't guess.

**Tests.** Resolution never returns a not-mine id; blocking behavior pinned as documented.

### P2-010 — `bulkUpdateMerchant` dispatches on zero rows (LOW)

**Fix design.** Gate `dispatchBulkPostCommitSideEffects` on `affectedCount > 0` (`:~1991`), mirroring `bulkUpdateCategory` (`:1844, :1924`). One-line + comment. **Tests:** zero-row rename → no plan/dispatch.

### NEW-P2-016 — Hot-path `homeCurrency().first()` without timeout (tracked partial)

**Problem.** Create (`:470`) and update (`:868`) call raw `currencySettingsRepository.homeCurrency().first()` (try/catch fallback, no timeout); the purpose-built `resolveHomeCurrency()` (`withTimeoutOrNull(5_000)`, `CurrencySettingsRepository.kt:66-90`) exists unused here. A hung DataStore read stalls every expense write.

**Fix design.** Replace both sites with `resolveHomeCurrency()` mapped to the same fallback constant the catch-blocks use today (behavior identical except the 5s bound). Do **not** attempt the ~49 repo-wide sites — that's a separate mechanical sweep; this PR fixes the lifecycle hot path only (the tracked issue's scope). Note the broader count in the PR for the backlog.

**Guardrails.** Timeout introduces a new failure mode (5s hang → fallback currency + possibly wrong conversion basis) — on timeout also set the existing conversion-failure path (baseAmount clearing per NEW-P2-007) so the row stays consistent; emit the existing diagnostic reason code for unavailable home currency. **Tests:** never-emitting flow fake → create completes within bound, fallback fields consistent, diagnostic recorded.

---

## Validation (when Gradle re-enables)
```
./gradlew :app:testDebugUnitTest --tests "*TransactionLifecycleCoordinator*" --tests "*DuplicateLogicConsistency*" --tests "*NotificationProcessingPipeline*" --tests "*CurrencySettingsRepository*"
```

## Sequencing & risk
- Order: RP-01 → 11a → 11b → 11c. Risk: 11b touches dedupe identity semantics (P2-004 especially) — smallest diffs, golden `TransactionLifecycleFullContractGoldenTest` + consistency tests updated in-PR. All changes engine-internal except the two ViewModel call sites (corrId, bulk message).
