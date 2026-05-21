# PR 10 — Static Guards

## Baseline checked

Current repo state at `6fee004aa141878820db9240d751ea22f20c4a52` shows:

- `app/build.gradle.kts` already has a custom verification task pattern (`verifyRoomSchemaSnapshots`).
- `CreateExpenseRequest.kt` still carries the legacy source-link fields and a TODO saying some are not persisted.
- `ExpenseSource.kt` already has the full source enum set.
- `TransactionLifecycleCoordinator.kt` already documents the need for a future static guard around `SideEffectMode`.
- `TransactionEvent.kt` uses free-form `metadata`, so privacy leaks are possible if builders regress.

PR10 should add **static boundary guards** that prevent future regressions after PR1–PR9.

---

## Goal

Add repo-level checks that fail fast when someone:

- adds a new provenance/source field without mapping it
- adds a new `ExpenseSource` without updating provenance policy
- writes source links or lifecycle events from an unapproved place
- leaks raw IDs or raw text into metadata
- bypasses deferred side-effect rules
- forgets to register/guard workers
- reintroduces legacy TODO debt in guarded paths

This PR is tooling-only. No runtime behavior changes.

---

## Non-goals

- No schema migration
- No new app feature
- No write-path refactor
- No UI work
- No backfill behavior change
- No runtime provenance logic change

---

## Files to add

- `scripts/verify_source_provenance_boundaries.py`
- `scripts/source_boundary_rules.json`
- `scripts/tests/test_verify_source_provenance_boundaries.py`
- `scripts/README.md`

Optional fixtures:
- `scripts/tests/fixtures/*.kt`

---

## Files to modify

- `app/build.gradle.kts`
- `.github/workflows/static-guards.yml`  
  (new, because the repo currently has no workflow folder)

---

## Guard architecture

### Why Python
Use a small Python 3 script because the checks are mostly text/regex-based, the repo has no `buildSrc`, and the guard must run both locally and in CI without extra Kotlin build tooling.

### Script shape
The script should:

1. scan only production source and selected test files
2. strip comments before scanning
3. normalize whitespace
4. apply rule checks from a central config file
5. print line-aware violations with a rule ID and fix hint
6. exit non-zero in strict mode

Recommended output object:
- `ruleId`
- `file`
- `line`
- `message`
- `hint`

### Scope
Scan:
- `app/src/main/java`
- selected `app/src/test/java` files for mapping coverage tests

Ignore:
- `docs/`
- `extracted_codebase/`
- generated schema snapshots
- `build/`
- unrelated analysis artifacts

---

## Central config

Use `scripts/source_boundary_rules.json` so the allowlists stay editable without changing the scanner code.

Suggested config sections:

- approved provenance write owners
- approved `SOURCE_LINKED` event owners
- approved `ExpenseDao.insertAtomic` owners
- approved `createExpense(..., SideEffectMode.DEFER)` callers
- blocked metadata keys
- worker IDs/classes that must be registered
- allowed legacy/no-op `ExpenseSource` cases
- temporary exceptions, if any, while refactoring completes

---

## Rules to enforce

### SG-01 — `CreateExpenseRequest` field mapping completeness
If `CreateExpenseRequest.kt` contains source-link fields, the guard must verify each one is handled by the source-link mapping layer.

Fields to cover:
- `rawNotificationId`
- `pendingReviewId`
- `scannedReceiptId`
- `emailReceiptSourceId`
- `groupId`
- `csvImportBatchId`
- `csvRowNumber`
- `externalFingerprint`

Fail if a new field is added and not mapped.

---

### SG-02 — `ExpenseSource` coverage
Every enum value in `ExpenseSource.kt` must have an explicit provenance policy.

Examples:
- mapped to a source-link payload
- intentionally no-op and documented in config
- intentionally legacy-only and documented

Fail if a new `ExpenseSource` appears without a corresponding policy entry or test coverage.

---

### SG-03 — Import/export source-link wiring
If PR7 is merged, the guard should ensure:
- export code writes `sourceLinks`
- import code reads `sourceLinks`
- legacy fallback handling is still present for old rows

Files to check:
- export mapper/codecs
- import mapper/codecs
- manifest handling

Fail if `sourceLinks` exists in the domain model but is silently ignored by import/export wiring.

---

### SG-04 — Provenance write ownership
Only approved owner files may call critical provenance writes.

Guard these patterns:
- `SourceLinkWriter.link*`
- `EntitySourceLinkDao.insert*`
- `expenseDao.insertAtomic(...)`
- `ReceiptExpenseLinkDao.insert*`
- direct `pendingReviewDao.insert*` in provenance flows
- direct `TransactionEventDao.insert(...)` for provenance events

This prevents ad hoc new writers.

Fail if these calls appear outside the allowlisted owner files.

---

### SG-05 — `SOURCE_LINKED` event ownership
`LifecycleEventType.SOURCE_LINKED` must only be emitted by approved provenance orchestrators.

Allowlisted owners should include the canonical orchestrators from PR2–PR8, such as:
- `TransactionLifecycleCoordinator.kt`
- `PendingReviewSourceLinkPromoter.kt`
- `ReceiptLifecycleCoordinator.kt`
- other approved provenance writer/orchestrator files as needed

Fail if a new arbitrary service writes `SOURCE_LINKED` directly.

---

### SG-06 — Side-effect dispatch contract
Prevent `createExpense(...)` from being used with immediate side effects inside outer transaction-owned flows.

Check for:
- `createExpense(` callsites
- `SideEffectMode.IMMEDIATE`
- `withTransaction { ... }` in the same file

Rules:
- inside caller-managed transactions, require `createExpenseDbOnly()` or explicit `SideEffectMode.DEFER`
- forbid new transactional callers from using immediate mode by accident

This is the static version of the coordinator’s side-effect contract.

---

### SG-07 — Metadata privacy guard
Scan the metadata-builder files and event-summary builders for blocked raw keys and raw identifiers.

Blocked examples:
- `rawText`
- `rawBody`
- `emailBody`
- `emailSubjectRaw`
- `emailSenderRaw`
- `notificationTitle`
- `notificationText`
- `bankDescription`
- `bankReference`
- `ocrText`
- `providerTransactionId`
- `messageId`
- `accountNumber`
- `cardNumber`
- `iban`
- `accessToken`
- `refreshToken`
- `password`
- `secret`
- `prompt`

Allow only hashed/safe variants such as:
- `externalIdHash`
- `externalFingerprintHash`
- `accountIdHash`
- `providerId`
- `operationRunId`
- `importBatchId`
- `importRowNumber`
- `confidence`
- `correlationId`
- `transactionStatus`

Fail if a metadata builder uses raw values or blocked keys.

---

### SG-08 — Worker registration and guard coverage
Ensure any new worker is:
- present in `WorkerSpec`
- present in `WorkerRegistry`
- wrapped by `WorkerExecutionGuard`
- scheduled on startup/restore where appropriate

Special case:
- `SourceLinkBackfillWorker` must be registered and guarded

Fail if a worker exists but is not wired into the registry/spec.

---

### SG-09 — Barrier/restore boundary hygiene
For write-owner files, require explicit barrier-aware ownership.

The guard should fail on new provenance write paths that:
- mutate core tables
- do not belong to the approved owner set
- or are clearly outside the restore/write-barrier boundary model

This is intentionally conservative: new write locations must be explicit and reviewed.

---

### SG-10 — Legacy TODO sentinel cleanup
Fail if guarded production code still contains obsolete sentinel TODOs like:
- “accepted but not persisted”
- “static guard in future release”
- other provenance/ownership debt markers that should have been removed by PR10

Keep this narrow so unrelated TODOs do not spam the build.

---

## CI / Gradle integration

### `app/build.gradle.kts`
Add:

- `tasks.register("verifySourceProvenanceBoundaries")`
- `tasks.named("check") { dependsOn("verifySourceProvenanceBoundaries") }`

Keep `verifyRoomSchemaSnapshots` as-is; PR10 should sit beside it.

### CI workflow
Add `.github/workflows/static-guards.yml` that runs:
- checkout
- JDK setup
- Python setup
- `./gradlew :app:check`
or at minimum:
- `./gradlew :app:verifyRoomSchemaSnapshots :app:verifySourceProvenanceBoundaries`

This makes the guard part of every PR.

---

## Test plan

### Script unit tests
Use `unittest` with fixtures to cover:
- unmapped request field detection
- enum coverage detection
- provenance owner allowlist enforcement
- `SOURCE_LINKED` owner enforcement
- side-effect mode misuse detection
- metadata privacy violations
- worker registration failures
- legacy TODO sentinel failures

### Suggested test names
- `flags_unmapped_source_request_field`
- `flags_new_expense_source_without_policy`
- `flags_source_link_event_outside_owner`
- `flags_raw_metadata_key_in_builder`
- `flags_create_expense_immediate_inside_with_transaction`
- `flags_unregistered_worker`
- `flags_direct_expense_insert_outside_owner`

### Smoke test
Run the script against the real repo tree and verify:
- it produces no false positives on docs/generated files
- it catches an intentionally injected violation

---

## Implementation order

1. Add `scripts/source_boundary_rules.json`
2. Implement the Python scanner with comment stripping
3. Add fixture-based unit tests
4. Wire the Gradle task in `app/build.gradle.kts`
5. Add CI workflow
6. Run in report-only mode once to see current violations
7. Remove temporary exceptions
8. Flip CI to hard-fail

---

## Transitional rollout

Because the repo is mid-refactor, the guard should support a temporary `report` mode or explicit allowlist entries for known legacy callsites.

Recommended approach:
- start with a small allowlist file
- burn it down as PR1–PR9 land
- end PR10 with strict CI enforcement and no legacy exceptions

---

## Acceptance criteria

PR10 is done when:

- a new source field cannot be added without mapping coverage
- a new `ExpenseSource` cannot be introduced without policy coverage
- provenance writes cannot appear from random services
- raw IDs/text cannot leak into metadata builders
- worker registration drift is caught
- `createExpense(..., IMMEDIATE)` cannot silently sneak into transactional callers
- the guard runs automatically in CI
- the guard is fast, deterministic, and easy to read when it fails

---

## Sources checked

- Latest commit:  
  https://github.com/panospao7/Cost-agregator/commit/6fee004aa141878820db9240d751ea22f20c4a52

- `app/build.gradle.kts`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/build.gradle.kts

- `CreateExpenseRequest.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseRequest.kt

- `ExpenseSource.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/domain/transaction/ExpenseSource.kt

- `TransactionLifecycleCoordinator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt

- `TransactionEvent.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/data/database/entity/TransactionEvent.kt

- `NotificationProcessingPipeline.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/6fee004aa141878820db9240d751ea22f20c4a52/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt