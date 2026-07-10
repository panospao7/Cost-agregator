# PR22+ Remaining Atomicity / Cancellation / Event Consistency Plan

Base reviewed commit: `b00241d7928e16373dbedabb039948b1fee9bcd4`

Current recommended status:

| MIT | Status |
|---|---|
| MIT-031 | NEAR-COMPLETE |
| MIT-041 | NEAR-COMPLETE, pending green CI |
| MIT-034 | PARTIAL |
| MIT-043 | PARTIAL |
| MIT-075 | PARTIAL by design |

Main remaining issues:

1. Raw `runCatching` still exists in important suspend/domain paths.
2. Cancellation guard does not strongly ban raw `runCatching`.
3. Direct event DAO allowlist remains broad.
4. `TransactionContext` provenance guard is too narrow.
5. Warranty lifecycle event writes remain best-effort/direct.
6. Recurring regeneration remains best-effort and Timber-only on skipped windows.
7. Latest CI green is not externally visible.
8. Docs have small stale counts/status wording.

Recommended branch:

```bash
git checkout -b atomicity-pr22-final-guard-and-cancellation-polish
```

---

# PR22-1 — Remove Raw `runCatching` From Core Suspend Paths

## Goal

Prevent `CancellationException` from being swallowed in remaining core/domain paths.

## Target files

- `NotificationProcessingPipeline.kt`
- `ReceiptLinkService.kt`
- `WarrantyTrackerRepository.kt`

## Tasks

### 1. Fix `NotificationProcessingPipeline`

Replace raw `runCatching { transactionRunner.runInTransaction { ... } }.onFailure { ... }`.

Use explicit cancellation-safe structure:

- call transaction/event write normally;
- catch `CancellationException` and rethrow;
- catch non-cancellation `Exception` and log/diagnose safely.

If the audit event is critical to state transition, make state + event one `DomainTransactionRunner` transaction.

If the audit event is diagnostic only, use a diagnostic writer and document it as non-critical.

### 2. Fix `ReceiptLinkService`

Replace raw `runCatching` around category propagation.

Policy decision:

- If category propagation is required for receipt-link correctness: include it in the link transaction.
- If best-effort post-link enrichment: keep it outside core transaction but rethrow cancellation and record durable diagnostic on failure.

Minimum required:

- no raw `runCatching`;
- `CancellationException` rethrows;
- non-cancellation failure does not store raw message.

### 3. Fix or classify `WarrantyTrackerRepository`

It contains many best-effort lifecycle event writes using raw `runCatching`.

Choose one:

#### Option A — Critical warranty lifecycle events

Wrap warranty state + event in `DomainTransactionRunner`.

#### Option B — Diagnostic/best-effort events

Keep best-effort, but:

- replace raw `runCatching`;
- rethrow `CancellationException`;
- sanitize event metadata;
- document warranty events as non-critical and outside MIT-031 closure.

Recommended: use Option A for create/update/delete/claim/reject lifecycle events.

## Tests

Add/update:

- `notification_pipeline_cancellation_is_rethrown`
- `notification_pipeline_audit_failure_does_not_swallow_cancellation`
- `receipt_link_category_propagation_cancellation_is_rethrown`
- `warranty_event_write_cancellation_is_rethrown`
- `warranty_event_failure_does_not_store_raw_message`

## Acceptance criteria

- No raw `runCatching` remains in these files.
- Cancellation cannot become false success.
- Non-critical failures are sanitized and diagnosed.

---

# PR22-2 — Strengthen Cancellation Static Guard

## Goal

Make MIT-034 enforcement credible for core/background paths.

## Tasks

### 1. Add raw `runCatching` rule

Fail raw `runCatching` in:

- suspend functions;
- workers;
- receivers;
- repositories;
- coordinators;
- lifecycle services;
- pipelines.

Allow only cancellation-safe helper or tightly allowlisted pure non-suspend code.

### 2. Remove false-positive allowlist for fixed core files

Remove from cancellation allowlist after fixes:

- `NotificationProcessingPipeline.kt`
- `ReceiptLinkService.kt`
- any fixed warranty methods/files if applicable.

### 3. Split allowlist categories

Categorize remaining entries:

- `CORE_WORKER`
- `CORE_COORDINATOR`
- `REPOSITORY_MUTATION`
- `RECEIVER`
- `NETWORK_PROVIDER`
- `UI_VIEWMODEL`
- `TEST_ONLY`

Core/background mutation categories should trend to zero.

### 4. Add expiry policy

- Core/background allowlists: not allowed, or max 30 days.
- Repository mutation allowlists: max 45 days.
- UI ViewModel allowlists: separate MIT-034-UI if not fixed.

## Tests

Bad fixtures:

- raw `runCatching` in suspend path;
- raw `runCatching` with `onFailure`;
- broad `catch(Exception)` without CE rethrow;
- expired cancellation allowlist.

Good fixtures:

- explicit CE rethrow;
- cancellation-safe helper;
- pure non-suspend allowlisted case.

## Acceptance criteria

- Raw `runCatching` in core suspend paths fails CI.
- No core worker/coordinator/repository mutation file is casually allowlisted.
- MIT-034 remains PARTIAL only because of explicitly scoped non-core debt.

---

# PR22-3 — Harden TransactionContext Provenance Guard

## Problem

Current guard pattern is too narrow. It catches only constructor calls shaped like `TransactionContext(correlationId = ...)`.

It may miss:

- positional constructor calls;
- constructor calls with another parameter first;
- multiline variations.

## Tasks

### 1. Broaden pattern

Scan for any production usage of:

```text
TransactionContext(
```

after stripping comments/strings.

Allowed only in:

- `RoomDomainTransactionRunner.kt`
- `DomainTransactionRunner.kt`
- `TransactionContext.kt`
- approved test fakes
- explicitly allowlisted repair/migration utilities.

### 2. Strengthen comment/string stripping

Ensure guard ignores:

- line comments;
- block comments;
- string literals;
- triple-quoted strings.

### 3. Validate allowlist

Keep structured allowlist:

- owner;
- reason;
- issue;
- expiry;
- duplicate detection.

## Tests

- positional manual constructor fixture fails;
- constructor with `occurredAt` first fails;
- commented constructor does not count;
- string constructor does not count;
- transaction runner constructor passes.

## Acceptance criteria

- Production code cannot forge `TransactionContext`.
- `writer.write(ctx, event)` strongly implies runner-provided context.

---

# PR22-4 — Direct Event DAO Allowlist Burn-Down

## Problem

Direct event guard is structured now, but still broad. Legacy repositories remain allowed.

## Tasks

### 1. Split direct event rules

Use distinct rules:

- direct receipt lifecycle event insert;
- direct recurring lifecycle event insert;
- direct transaction lifecycle event insert;
- direct pending review insert;
- direct diagnostic event insert.

### 2. Shorten repository expiries

For legacy repository entries:

- max 30–45 days;
- owner required;
- issue required;
- explicit migration target required.

### 3. Remove duplicates

Remove duplicate `RecurringLifecycleEventWriter.kt` entries.

Add duplicate-entry test.

### 4. Migrate easiest repositories

Prioritize moving direct event writes out of:

- `ReceiptRepository.kt`
- `ReviewQueueRepository.kt`
- `ExpenseRepository.kt`
- `NotificationRepository.kt`

Use event writer/coordinator APIs.

## Tests

- repository direct insert fixture fails;
- approved writer direct insert passes;
- expired allowlist fails;
- duplicate allowlist fails;
- repository allowlist expiry beyond policy fails.

## Acceptance criteria

- MIT-031 can eventually close with only short-lived, owned exceptions.
- Direct event writes are not normalized in repositories.

---

# PR22-5 — Bank Item Audit and CI Closure

## Goal

Make MIT-041 closeable after visible CI.

## Tasks

### 1. Add bank skipped/failed item tests

Policy:

- `BankStatementImportItem` is authoritative audit ledger for rows skipped before receipt creation.
- Receipt lifecycle events begin only after a receipt exists.

Tests:

- invalid amount creates skipped item ledger;
- invalid currency creates skipped item ledger;
- skipped item reason code is sanitized;
- skipped item does not create receipt lifecycle event;
- failed item records error class only.

### 2. Confirm bank cancellation/failure cleanup

Tests:

- cancellation cleanup timeout rethrows original CE;
- non-cancellation cleanup receiving CE rethrows CE;
- failure after receipt insert finalizes run + receipt event atomically.

### 3. Get green CI

Run:

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:verifyRoomSchemaSnapshots
./gradlew :app:verifyDbAccessBoundaries
```

## Acceptance criteria

- Latest GitHub Actions run is green, or failures are quarantined with owner/expiry.
- MIT-041 can move from NEAR-COMPLETE to DONE only after this.

---

# PR22-6 — Recurring Best-Effort Regeneration Diagnostics

## Problem

MIT-043 is partial because regeneration is best-effort and skipped windows are only logged.

## Tasks

If keeping best-effort design:

- record durable diagnostic for each skipped regeneration window;
- include reason code and error class only;
- no raw message;
- keep MIT-043 partial or redefine scope.

If making all-or-nothing:

- remove per-window catch;
- event/state failure aborts entire regeneration transaction;
- add rollback tests.

Recommended short-term: durable diagnostics + keep MIT-043 partial.

## Tests

- skipped regeneration window records durable diagnostic;
- diagnostic is sanitized;
- cancellation during regeneration rethrows;
- best-effort policy documented.

---

# PR22-7 — Docs and Tracker Correction

## Tasks

### Before PR22 is complete

Keep:

```text
MIT-031: NEAR-COMPLETE
MIT-041: NEAR-COMPLETE
MIT-034: PARTIAL
MIT-043: PARTIAL
MIT-075: PARTIAL
```

### After PR22

Close only if criteria are met:

#### MIT-031 can close if:

- manual `TransactionContext` construction blocked;
- direct event allowlist is structured, short-lived, and narrow;
- context-free writer calls blocked;
- CI green.

#### MIT-041 can close if:

- bank cleanup tests pass;
- skipped item audit tests pass;
- CI green.

MIT-034 stays partial unless allowlist is burned down.

MIT-043 stays partial unless regeneration/uniqueness decisions are resolved.

MIT-075 stays partial unless durable outbox is implemented.

### Fix stale doc counts

Update provenance guard count to match actual allowlist.

Document exact CI commands and latest passing commit.

---

# PR23 — MIT-034 Full Burn-Down

## Goal

Close or accurately scope MIT-034.

## Tasks

- eliminate all core worker/coordinator/repository mutation allowlists;
- split UI ViewModel cancellation into `MIT-034-UI` if needed;
- fix remaining raw `runCatching`;
- enforce broad-catch CE rethrow.

## Acceptance criteria

- no core/background cancellation allowlists;
- raw `runCatching` fails CI;
- global or scoped MIT-034 closure is honest.

---

# PR24 — MIT-043 Final Decision

## Options

### Keep partial

Document dependencies:

- MIT-033 uniqueness;
- best-effort regeneration;
- durable diagnostics instead of full atomicity.

### Close

Required:

- MIT-033 uniqueness merged;
- duplicate linked actual conflict tests pass;
- regeneration either all-or-nothing or diagnostically complete;
- stale recovery policy finalized;
- projection rollback tests pass.

---

# PR25 — MIT-075 Outbox Decision

## Current status

Evidence logger exists, no durable outbox.

## Options

### Keep partial

Document:

```text
Side-effect evidence is diagnostic-only. No guaranteed retry/replay.
```

### Implement outbox

Add durable side-effect table, dispatcher worker, retry/dead-letter policy, tests.

---

# Final Validation

Run before final closure:

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:verifyRoomSchemaSnapshots
./gradlew :app:verifyDbAccessBoundaries
```

Targeted:

```bash
./gradlew :app:testDebugUnitTest --tests "*Cancellation*"
./gradlew :app:testDebugUnitTest --tests "*Architecture*"
./gradlew :app:testDebugUnitTest --tests "*NotificationProcessing*"
./gradlew :app:testDebugUnitTest --tests "*ReceiptLink*"
./gradlew :app:testDebugUnitTest --tests "*Warranty*"
./gradlew :app:testDebugUnitTest --tests "*BankStatement*"
./gradlew :app:testDebugUnitTest --tests "*Recurring*"
```

---

# Minimal Next Patch

If you want the shortest safe next step:

1. Replace raw `runCatching` in `NotificationProcessingPipeline`.
2. Replace raw `runCatching` in `ReceiptLinkService`.
3. Add raw `runCatching` static guard.
4. Broaden `TransactionContext` provenance guard to any constructor call.
5. Add bank skipped-item ledger tests.
6. Keep MIT statuses unchanged until visible green CI.