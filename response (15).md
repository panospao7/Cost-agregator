Here is the focused implementation plan for the remaining issues after latest `42e53e1`.

<atomicity_pr23_remaining_cleanup_plan.md>
# PR23 — Final Verification and Residual Atomicity/Cancellation Cleanup Plan

Base reviewed commit: `42e53e15be17303d945fe75c5afb7e22b963eab5`

## Current recommended status

| MIT | Status |
|---|---|
| MIT-031 | Core DONE / global repository-event debt remains |
| MIT-041 | Nearly DONE, pending PR23 data-quality fix + visible green CI |
| MIT-034 | PARTIAL |
| MIT-043 | PARTIAL |
| MIT-075 | PARTIAL by design |

## Goal

Finish the remaining small but important issues before treating MIT-031/MIT-041 as truly release-safe.

Remaining issues:

1. Bank skipped invalid amount ledger can persist `NaN` / `Infinity`.
2. Bank duplicate/invalid skip audit policy needs explicit tests.
3. Cancellation static guard does not yet ban raw `runCatching`.
4. Direct event DAO legacy repository debt remains accepted until `2026-08-15`.
5. Warranty lifecycle event descriptions may include product names.
6. Latest green CI is not externally visible.
7. Docs should distinguish “core DONE” from “global residual debt.”

Recommended branch:

```bash
git checkout -b atomicity-pr23-final-verification-cleanup
```

Recommended commits:

1. `PR23-1 — Sanitize bank skipped invalid amount ledger`
2. `PR23-2 — Bank skipped/duplicate item audit tests`
3. `PR23-3 — Cancellation guard raw runCatching rule`
4. `PR23-4 — Direct event DAO debt tracking polish`
5. `PR23-5 — Warranty lifecycle metadata privacy polish`
6. `PR23-6 — CI verification and docs correction`

---

# PR23-1 — Sanitize Bank Skipped Invalid Amount Ledger

## Problem

In `BankStatementLifecycleProcessor`, invalid amount branches currently insert skipped `BankStatementImportItem` rows with:

```kotlin
amount = tx.amount
```

even when:

```kotlin
tx.amount.isNaN() || tx.amount.isInfinite()
```

This means the DB ledger can persist `NaN`, `Infinity`, or `-Infinity`.

That contradicts the “validate finite amount before mutation” rule and may break queries, aggregates, or UI/debug rendering.

## Files

- `BankStatementLifecycleProcessor.kt`
- `BankStatementImportItem.kt`
- bank statement tests

## Implementation

### 1. Sanitize non-finite amount

Change invalid amount skipped-row insert from:

```kotlin
amount = tx.amount
```

to:

```kotlin
amount = null
```

or, if you need original raw amount for debugging, store only a safe reason code:

```kotlin
errorReason = "INVALID_AMOUNT_NON_FINITE"
```

Do **not** store the raw non-finite value.

Recommended:

```kotlin
BankStatementImportItem(
    runId = importRunId,
    rowIndex = index,
    status = BankStatementImportItemStatus.SKIPPED,
    amount = null,
    currency = tx.currency?.takeIf { it.isValidCurrencyCode() },
    errorReason = "INVALID_AMOUNT_NON_FINITE",
    errorClass = null
)
```

### 2. Sanitize reason

Avoid:

```text
INVALID_AMOUNT: Amount is NaN or Infinite
```

Prefer structured code:

```text
INVALID_AMOUNT_NON_FINITE
```

If human-readable description is needed, keep it in docs/UI mapping, not DB diagnostic string.

## Tests

Add:

1. `nan_amount_creates_skipped_item_with_null_amount`
2. `positive_infinity_amount_creates_skipped_item_with_null_amount`
3. `negative_infinity_amount_creates_skipped_item_with_null_amount`
4. `non_finite_amount_reason_is_structured_code`
5. `non_finite_amount_does_not_create_receipt_or_pending_review`
6. `non_finite_amount_does_not_create_receipt_lifecycle_event`

## Acceptance criteria

- No `NaN` / `Infinity` is persisted in skipped item amount.
- Invalid amount skipped row remains auditable through structured reason code.
- MIT-041 data-quality concern is closed.

---

# PR23-2 — Bank Skipped / Duplicate Item Audit Tests

## Problem

Bank skipped rows before receipt creation are audited by `BankStatementImportItem`, not receipt lifecycle events. That policy is acceptable, but it needs explicit tests.

Cases needing coverage:

- invalid amount;
- invalid currency;
- invalid date if applicable;
- duplicate expense;
- duplicate pending review;
- row processing failure before receipt creation.

## Policy

Document and test:

```text
BankStatementImportItem is the authoritative per-item audit ledger for rows skipped before receipt/review creation.
Receipt lifecycle events begin only after a receipt exists.
```

## Tests

Add:

1. `invalid_currency_creates_skipped_item_ledger`
2. `invalid_currency_reason_is_structured_code`
3. `duplicate_expense_creates_skipped_item_ledger`
4. `duplicate_pending_review_creates_skipped_item_ledger`
5. `duplicate_skip_reason_does_not_include_raw_merchant_or_description`
6. `skipped_item_without_receipt_has_no_receipt_lifecycle_event`
7. `failed_item_records_error_class_only`
8. `failed_item_does_not_store_raw_exception_message`

## Acceptance criteria

- Skipped/duplicate bank rows are auditable.
- No receipt event is expected when no receipt exists.
- Reasons are structured/sanitized.
- MIT-041 closure has test evidence.

---

# PR23-3 — Cancellation Guard Raw `runCatching` Rule

## Problem

Known raw `runCatching` sites in core files were fixed, but the static guard still does not strongly prevent reintroduction.

`CancellationSafetyArchitectureGuardTest` should fail raw `runCatching` in suspend/domain/worker/repository paths.

## Files

- `CancellationSafetyArchitectureGuardTest.kt`
- cancellation fixture resources if present
- `CancellationSafe.kt`

## Implementation

### 1. Add rule

Add rule ID:

```text
RAW_RUN_CATCHING_IN_SUSPEND_PATH
```

Fail source containing:

```kotlin
runCatching {
```

in files classified as:

- Worker;
- Receiver;
- Repository;
- Coordinator;
- Service;
- Pipeline;
- suspend-heavy domain path.

Allow only:

```kotlin
CancellationSafe.runCatchingCancellable { ... }
```

### 2. Avoid false positives

Do not flag:

```kotlin
CancellationSafe.runCatchingCancellable
```

Do not flag tests unless desired.

Optionally allow pure non-suspend utility files through structured allowlist.

### 3. Structured allowlist

If any raw `runCatching` remains, require:

```kotlin
CancellationAllowlistEntry(
    fileName = "...",
    rule = "RAW_RUN_CATCHING_IN_SUSPEND_PATH",
    owner = "...",
    reason = "...",
    issue = "MIT-034",
    expires = LocalDate.parse("2026-08-15")
)
```

### 4. Fixtures

Bad:

```text
RunCatchingInSuspendRepository.kt
RunCatchingInWorker.kt
RunCatchingOnFailureSwallowsCancellation.kt
```

Good:

```text
CancellationSafeRunCatchingCancellable.kt
ExplicitTryCatchRethrowsCancellation.kt
PureNonSuspendRunCatchingAllowlisted.kt
```

## Tests

1. `raw_runCatching_in_suspend_repository_fails`
2. `raw_runCatching_in_worker_fails`
3. `runCatchingCancellable_passes`
4. `expired_runCatching_allowlist_fails`
5. `core_source_has_no_raw_runCatching_violations`

## Acceptance criteria

- Raw `runCatching` cannot return silently in core async paths.
- MIT-034 remains partial only because of known allowlisted debt, not guard weakness.

---

# PR23-4 — Direct Event DAO Debt Tracking Polish

## Problem

`DirectEventDaoInsertGuardTest` is now structured, but it still allows legacy production repositories until `2026-08-15`.

This may be acceptable as residual debt, but docs and guard should make it impossible to forget.

## Tasks

### 1. Add category to allowlist

Extend:

```kotlin
data class DirectEventDaoAllowlistEntry(
    val fileName: String,
    val rule: String,
    val category: String,
    val owner: String,
    val reason: String,
    val issue: String,
    val expires: LocalDate
)
```

Categories:

```text
WRITER_IMPLEMENTATION
COORDINATOR
LEGACY_REPOSITORY
MIGRATION
TEST
```

### 2. Enforce expiry policy

Rules:

- `WRITER_IMPLEMENTATION`: can be long-lived.
- `COORDINATOR`: allowed if transaction-scoped.
- `LEGACY_REPOSITORY`: max 45 days.
- `MIGRATION`: expiry required.
- `TEST`: test source only.

Test:

```kotlin
legacy_repository_direct_event_allowlist_cannot_exceed_45_days
```

### 3. Create migration checklist

For each legacy repository entry, add doc/table:

| File | Event type | Replacement owner | Expiry |
|---|---|---|---|
| `ReceiptRepository.kt` | receipt event | ReceiptLifecycleCoordinator | 2026-08-15 |
| `ReviewQueueRepository.kt` | review event | ReviewLifecycleCoordinator | 2026-08-15 |
| `ExpenseRepository.kt` | transaction event | TransactionLifecycleCoordinator | 2026-08-15 |
| etc. |

## Acceptance criteria

- Legacy repository direct-event debt is explicit and time-boxed.
- MIT-031 can be described as “core coordinator paths done, repository debt tracked.”

---

# PR23-5 — Warranty Lifecycle Metadata Privacy Polish

## Problem

Warranty lifecycle event descriptions may include product names:

```kotlin
"Warranty created for ${warranty.productName}"
```

Product names can be sensitive purchase data.

Warranty events are currently accepted as non-critical/best-effort, but their metadata should still be privacy-safe.

## Files

- `WarrantyTrackerRepository.kt`
- warranty lifecycle event model/DAO
- warranty tests

## Implementation options

### Option A — remove product names from event descriptions

Use generic descriptions:

```text
WARRANTY_CREATED
WARRANTY_UPDATED
WARRANTY_DELETED
WARRANTY_CLAIMED
WARRANTY_REJECTED
```

Metadata:

```kotlin
warrantyId
eventCode
source
```

No product name.

### Option B — hash product names

If product correlation is needed:

```kotlin
productNameHash = privacyHash(warranty.productName)
```

Do not store raw name.

Recommended: Option A.

## Tests

1. `warranty_created_event_does_not_include_product_name`
2. `warranty_updated_event_does_not_include_product_name`
3. `warranty_claim_event_does_not_include_product_name`
4. `warranty_event_failure_rethrows_cancellation`
5. `warranty_event_failure_logs_sanitized_class_only`

## Acceptance criteria

- Warranty lifecycle events do not persist raw product names.
- Warranty remains outside MIT-031 critical scope unless later migrated.

---

# PR23-6 — CI Verification and Docs Correction

## Problem

Latest docs close MIT-031/MIT-041, but no visible green CI for latest commit was available during review.

## Tasks

### 1. Run full verification

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:verifyRoomSchemaSnapshots
./gradlew :app:verifyDbAccessBoundaries
```

Preferred:

```bash
./gradlew :app:check
```

### 2. Run targeted verification

```bash
./gradlew :app:testDebugUnitTest --tests "*BankStatement*"
./gradlew :app:testDebugUnitTest --tests "*Cancellation*"
./gradlew :app:testDebugUnitTest --tests "*Architecture*"
./gradlew :app:testDebugUnitTest --tests "*NotificationProcessing*"
./gradlew :app:testDebugUnitTest --tests "*ReceiptLink*"
./gradlew :app:testDebugUnitTest --tests "*Warranty*"
```

### 3. Update docs with evidence

Docs should include:

```text
Verified commit:
Commands run:
Result:
Known excluded tests:
Owner:
Expiry:
```

### 4. Status wording

Recommended:

```text
MIT-031: CORE DONE — critical coordinator-owned state/event paths transaction-scoped. Legacy repository direct-event debt tracked until 2026-08-15.
MIT-041: DONE after PR23 invalid amount ledger fix + green CI.
MIT-034: PARTIAL — cancellation allowlist remains.
MIT-043: PARTIAL — regeneration best-effort and MIT-033 uniqueness dependency.
MIT-075: PARTIAL — no durable outbox by design.
```

## Acceptance criteria

- Latest commit has visible green CI or documented local command output.
- Docs do not overclaim global closure beyond accepted residual debt.

---

# PR24 — MIT-034 Burn-Down

## Goal

Move MIT-034 from PARTIAL to scoped DONE or full DONE.

## Tasks

1. Eliminate raw `runCatching` in all production suspend paths.
2. Reduce cancellation allowlist from current count to:
   - zero core worker/coordinator/repository mutation entries;
   - UI-only entries split to `MIT-034-UI`, if needed.
3. Add guard that fails:
   - raw `runCatching`;
   - broad catch without CE rethrow;
   - `Throwable` catch without CE rethrow;
   - `onFailure` swallowing cancellation.

## Acceptance criteria

- No core/background cancellation allowlists remain.
- MIT-034 closure scope is honest.

---

# PR25 — MIT-043 Final Recurring Decision

## Current partial reasons

- regeneration is best-effort by design;
- duplicate fulfillment depends on MIT-033;
- DB uniqueness not fully owned here.

## Option A — keep partial

Document:

```text
MIT-043 remains PARTIAL until MIT-033 lands and regeneration policy is finalized.
```

## Option B — close

Required:

1. MIT-033 uniqueness merged.
2. Duplicate actual-link conflict tests pass.
3. Regeneration either:
   - all-or-nothing; or
   - durable diagnostics for every skipped window and product acceptance.
4. Projection rollback tests pass.

---

# PR26 — MIT-075 Outbox Decision

## Current state

No durable outbox. Evidence logger only.

## Option A — keep partial

Docs:

```text
Side-effect evidence is diagnostic-only; no guaranteed replay.
```

## Option B — implement outbox

Add:

```text
post_commit_side_effects
```

with status, attempts, nextAttemptAt, errorCode, errorClass, payloadJson.

Add dispatcher worker and retry/dead-letter tests.

---

# Final Closure Checklist

## MIT-031

Can be closed as **core DONE** when:

- manual `TransactionContext` construction is blocked;
- critical coordinator paths use `DomainTransactionRunner`;
- context-free writer calls are blocked;
- direct repository-event debt is structured and expiring;
- CI green.

## MIT-041

Can be closed when:

- NaN/Infinity skipped amount rows store `null`;
- skipped/duplicate item audit policy is tested;
- bank cancellation/failure cleanup tests pass;
- CI green.

## MIT-034

Can close only when:

- no core cancellation allowlists remain;
- raw `runCatching` guard exists;
- broad catch fixtures fail.

## MIT-043

Can close only when:

- duplicate fulfillment uniqueness/conflict policy is complete;
- regeneration policy is atomic or durably diagnosed;
- projection rollback tests pass.

## MIT-075

Can close only with:

- real outbox; or
- re-scoped issue definition that accepts diagnostic-only evidence.

---

# Minimal PR23 Patch

If time is short:

1. Store `amount = null` for bank NaN/Infinity skipped rows.
2. Add tests for NaN/Infinity skipped rows.
3. Add raw `runCatching` static guard.
4. Add visible CI verification.
5. Update docs: MIT-031 core done, MIT-041 done only after PR23 + green CI.
</atomicity_pr23_remaining_plan.md>