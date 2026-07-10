# Deep Review — Atomicity / Cancellation / Event Consistency

Latest reviewed commit:  
https://github.com/panospao7/Cost-agregator/commit/b00241d7928e16373dbedabb039948b1fee9bcd4

Key previous commits:
- PR20: https://github.com/panospao7/Cost-agregator/commit/c88964efb401733494e66eae8037b68fe921ccfe
- PR19 close attempt: https://github.com/panospao7/Cost-agregator/commit/b1ad7bc079c1d41475c301bbe6850cf75a46ccf6
- PR18 docs: https://github.com/panospao7/Cost-agregator/commit/d1abaebd453ddf35e2a3e963d6988e25c32b775f

Important files checked:
- `TransactionContextProvenanceGuardTest.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b00241d7928e16373dbedabb039948b1fee9bcd4/app/src/test/java/com/yourname/expensetracker/architecture/TransactionContextProvenanceGuardTest.kt
- `TransactionContext.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b00241d7928e16373dbedabb039948b1fee9bcd4/app/src/main/java/com/yourname/expensetracker/domain/transaction/TransactionContext.kt
- `RoomDomainTransactionRunner.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b00241d7928e16373dbedabb039948b1fee9bcd4/app/src/main/java/com/yourname/expensetracker/data/database/RoomDomainTransactionRunner.kt
- `BankStatementLifecycleProcessor.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b00241d7928e16373dbedabb039948b1fee9bcd4/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt
- `RecurringLifecycleCoordinator.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b00241d7928e16373dbedabb039948b1fee9bcd4/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt
- `NotificationProcessingPipeline.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b00241d7928e16373dbedabb039948b1fee9bcd4/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt
- `WarrantyTrackerRepository.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b00241d7928e16373dbedabb039948b1fee9bcd4/app/src/main/java/com/yourname/expensetracker/data/repository/WarrantyTrackerRepository.kt
- `DirectEventDaoInsertGuardTest.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b00241d7928e16373dbedabb039948b1fee9bcd4/app/src/test/java/com/yourname/expensetracker/architecture/DirectEventDaoInsertGuardTest.kt
- `CancellationSafetyArchitectureGuardTest.kt`  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b00241d7928e16373dbedabb039948b1fee9bcd4/app/src/test/java/com/yourname/expensetracker/architecture/CancellationSafetyArchitectureGuardTest.kt
- Actions page: https://github.com/panospao7/Cost-agregator/actions
- Master tracker:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b00241d7928e16373dbedabb039948b1fee9bcd4/docs/analyses%20and%20debug%20master/MASTER_ISSUE_TRACKER.md

Static review only. I did not run Gradle locally.

---

# Executive verdict

`b00241d` is a **real improvement** over `c88964e`.

You fixed several things I asked for:

- 4 manual `TransactionContext(...)` callers were migrated to `DomainTransactionRunner`.
- `TransactionContextProvenanceGuardTest` now blocks manual context construction outside canonical files.
- `DatabaseModule` now provides `DomainTransactionRunner` through `RoomDomainTransactionRunner`.
- recurring stale recovery now runs inside `DomainTransactionRunner`.
- stale recovery event failure now rolls back recovery state.
- bank failure cleanup now rethrows cancellation in non-cancellation cleanup.
- docs now keep MIT-031/MIT-041 as `NEAR-COMPLETE`, while MIT-034/MIT-043/MIT-075 remain partial.

That is the correct status.

However, I still would **not close everything** yet.

The biggest remaining blocker is that there are still raw `runCatching` usages in suspend paths that can swallow `CancellationException`, especially in:

- `NotificationProcessingPipeline`
- `WarrantyTrackerRepository`
- `ReceiptLinkService`

There are also still broad direct-event DAO allowlists and no visible GitHub Actions run for `b00241d`.

Recommended current status:

```text
MIT-031: NEAR-COMPLETE
MIT-041: NEAR-COMPLETE
MIT-034: PARTIAL
MIT-043: PARTIAL
MIT-075: PARTIAL
```

Do not mark all remaining items fully done yet.

---

# What is fixed well

## 1. Manual `TransactionContext` callers were migrated

The commit migrates the previously problematic manual context callers:

- `GroupTransactionCoordinator`
- `NotificationProcessingPipeline`
- `WarrantyTrackerRepository`
- `ReceiptLinkService`

to use:

```kotlin
transactionRunner.runInTransaction { ctx -> ... }
```

This is good. It means most new event writes now receive a context from the transaction runner rather than a manually fabricated context.

Status: **fixed for the listed PR20 manual context callers**.

---

## 2. TransactionContext provenance guard exists

`TransactionContextProvenanceGuardTest` scans production source for manual `TransactionContext(` construction and fails outside an allowlist.

It also validates:

- owner;
- reason;
- issue;
- expiry;
- duplicate entries.

The allowlist is now small and mostly canonical:

- `RoomDomainTransactionRunner.kt`
- `DomainTransactionRunner.kt`
- `TransactionContext.kt`
- deprecated writer implementations
- side-effect failure writer

Status: **good guardrail**.

Remaining caveat: the regex only matches one constructor shape:

```kotlin
TransactionContext(\s*correlationId
```

So it can miss constructor calls where `correlationId` is not first or is passed positionally. More below.

---

## 3. `TransactionContext` has an internal constructor/token

`TransactionContext` now has an internal constructor and an internal `TransactionToken`.

This is better than a fully public data class.

Status: **improved**.

Caveat: Kotlin `internal` is module-wide. Since much of the app is in the same Gradle module, real enforcement still depends on the source guard.

---

## 4. Recurring stale recovery is now transactional and evented

`recoverStaleClaimedDeliveries()` now:

- checks write barrier;
- uses `transactionRunner.runInTransaction`;
- updates stale reminder deliveries;
- writes `STALE_DELIVERIES_RECOVERED` event in the same transaction;
- lets event failure rollback state.

Status: **fixed** for stale recovery.

This is a meaningful MIT-043 improvement.

---

## 5. Bank statement cleanup is better

From PR20 + latest:

- cancellation cleanup no longer masks the original `CancellationException`;
- non-cancellation failure cleanup rethrows cancellation if cancellation happens during cleanup;
- failure after receipt creation finalizes import run + writes `PROCESSING_FAILED` event atomically.

Status: **mostly fixed** for MIT-041 core bank path.

---

## 6. Docs are more honest

The master tracker now says:

```text
MIT-031: NEAR-COMPLETE
MIT-041: NEAR-COMPLETE
MIT-034: PARTIAL
MIT-043: PARTIAL
MIT-075: PARTIAL
```

That is much better than closing everything prematurely.

Status: **fixed**.

---

# Remaining blockers / high-impact issues

## BLOCKER 1 — latest CI is not externally verified

The commit message says:

```text
32/34 tests PASS (2 pre-existing)
BUILD SUCCESSFUL
```

But the public Actions page I can see does **not** show a run for `b00241d`. The latest visible run is still `c88964e`, and it is failing.

Actions page:  
https://github.com/panospao7/Cost-agregator/actions

This means I cannot independently verify green CI for the latest commit.

Also, “32/34 tests PASS” means there are still 2 failing tests in the referenced set, even if they are pre-existing.

Required before any full closure:

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
./gradlew :app:verifyRoomSchemaSnapshots
./gradlew :app:verifyDbAccessBoundaries
```

Then get a visible green Actions run or document exactly which failures are quarantined with owner/expiry.

---

## BLOCKER 2 — raw `runCatching` still swallows cancellation in `NotificationProcessingPipeline`

In `NotificationProcessingPipeline`, there is still:

```kotlin
runCatching {
    transactionRunner.runInTransaction { ... }
}.onFailure { error ->
    Timber.w(error, ...)
}
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/b00241d7928e16373dbedabb039948b1fee9bcd4/app/src/main/java/com/yourname/expensetracker/data/repository/NotificationProcessingPipeline.kt

This is inside a suspend/domain path.

If `transactionRunner.runInTransaction` throws `CancellationException`, raw `runCatching` captures it. Then `onFailure` logs it and the code continues.

That can turn cancellation into apparent success.

This directly violates MIT-034.

Required fix:

```kotlin
try {
    transactionRunner.runInTransaction(...) { ctx ->
        transactionLifecycleEventWriter.write(ctx, event)
    }
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Timber.w(e, "Failed to write AI_AUTO_ACCEPT audit event")
}
```

or:

```kotlin
CancellationSafe.runCatchingCancellable {
    transactionRunner.runInTransaction(...) { ... }
}.onFailure { ... }
```

But be careful: `onFailure` must never receive CE.

This one should be fixed before claiming “core worker/coordinator paths clean,” because notification processing is a core pipeline.

---

## BLOCKER 3 — `WarrantyTrackerRepository` still has many raw `runCatching` event writes

`WarrantyTrackerRepository` still has multiple patterns like:

```kotlin
runCatching {
    database.warrantyLifecycleEventDao().insert(...)
}.onFailure { ... }
```

Examples include warranty create, update, delete, claim, reject, expiration, and AI extraction discard events.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/b00241d7928e16373dbedabb039948b1fee9bcd4/app/src/main/java/com/yourname/expensetracker/data/repository/WarrantyTrackerRepository.kt

Problems:

1. raw `runCatching` can swallow cancellation;
2. event failure is best-effort, so state/event can diverge;
3. raw product names appear in event descriptions/log messages;
4. repository directly writes lifecycle events.

This may be outside MIT-031/041 core receipt/bank scope, but it is still a remaining cancellation/event-consistency debt.

At minimum:

- replace raw `runCatching` with cancellation-safe helper;
- decide whether warranty lifecycle events are critical or diagnostic;
- if critical, transaction-wrap state + event;
- if diagnostic, record as such and keep MIT scope honest.

---

## BLOCKER 4 — `ReceiptLinkService` still uses raw `runCatching`

`ReceiptLinkService` still has:

```kotlin
runCatching { ... }.onFailure { ... }
```

around category propagation after receipt link.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/b00241d7928e16373dbedabb039948b1fee9bcd4/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt

This is probably a post-link best-effort side effect, not the core link transaction. But it is still in a suspend domain service and can swallow `CancellationException`.

Required fix:

```kotlin
try {
    ...
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Timber.w(e, ...)
}
```

or use `CancellationSafe.runCatchingCancellable`.

---

## BLOCKER 5 — cancellation guard allowlist hides real issues

`CancellationSafetyArchitectureGuardTest` still allowlists `NotificationProcessingPipeline.kt` as a “false positive,” but the file has a real raw `runCatching` that can catch CE.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/b00241d7928e16373dbedabb039948b1fee9bcd4/app/src/test/java/com/yourname/expensetracker/architecture/CancellationSafetyArchitectureGuardTest.kt

This shows the cancellation guard is still too coarse.

It appears to focus on broad `catch(Exception)` patterns, but it does not reliably catch raw `runCatching` in suspend paths.

Required guard additions:

- fail raw `runCatching` in production suspend/domain/worker/repository paths;
- allow only `CancellationSafe.runCatchingCancellable`;
- remove `NotificationProcessingPipeline.kt` from false-positive allowlist after fixing it;
- no core pipeline file should be cancellation-allowlisted.

MIT-034 correctly remains **PARTIAL**.

---

## BLOCKER 6 — Direct event DAO allowlist is structured but still too broad

`DirectEventDaoInsertGuardTest` now has structured entries, which is good.

But the allowlist is still broad and includes many legacy repositories/coordinators:

- `ExpenseRepository.kt`
- `ReceiptRepository.kt`
- `ReviewQueueRepository.kt`
- `NotificationRepository.kt`
- `RecurringExpenseRepository.kt`
- `ManualRecurringExpenseRepository.kt`
- `WarrantyTrackerRepository.kt`
- `BankApiIntegration.kt`

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/b00241d7928e16373dbedabb039948b1fee9bcd4/app/src/test/java/com/yourname/expensetracker/architecture/DirectEventDaoInsertGuardTest.kt

All or most expiries are `2026-12-31`, which is too far for “near-complete” architecture closure.

Recommended:

- split “writers/coordinators” from “legacy repositories”;
- give legacy repository entries short expiry, e.g. 30–45 days;
- create one follow-up per repository migration;
- add a test that no `REPOSITORY` category entry can expire beyond 45 days.

Until then, MIT-031 should remain **NEAR-COMPLETE**, not DONE.

---

## BLOCKER 7 — TransactionContext provenance guard is useful but bypassable

The provenance guard uses this pattern:

```kotlin
TransactionContext\(\s*correlationId
```

It will miss:

```kotlin
TransactionContext(
    occurredAt = now,
    correlationId = cid
)
```

or positional construction:

```kotlin
TransactionContext(cid, null, ...)
```

or constructor calls hidden in strings/comments depending on scanner behavior.

Required hardening:

Use a broader pattern:

```regex
\bTransactionContext\s*\(
```

Then strip comments/strings or allow KDoc false positives carefully.

The allowlist can handle legitimate type definitions.

---

## BLOCKER 8 — deprecated context-free writer APIs still exist

`TransactionLifecycleEventWriter` still contains deprecated context-free `write(event)` with `DeprecationLevel.ERROR`, and the implementation suppresses deprecation internally.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/b00241d7928e16373dbedabb039948b1fee9bcd4/app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleEventWriter.kt

This is acceptable temporarily, but final enforcement should remove those methods or add static guard coverage for any production use.

---

## BLOCKER 9 — recurring reminder regeneration is still best-effort

`regenerateReminderDeliveriesForOccurrence()` still catches per-window event/state failures, logs with Timber, and continues.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/b00241d7928e16373dbedabb039948b1fee9bcd4/app/src/main/java/com/yourname/expensetracker/domain/recurring/lifecycle/RecurringLifecycleCoordinator.kt

You already mark MIT-043 partial by design, which is correct.

If you want MIT-043 DONE, choose:

- all-or-nothing regeneration; or
- durable diagnostic for every skipped window plus accepted product policy.

Right now it is still best-effort with non-durable Timber logging.

---

## BLOCKER 10 — docs still have small inconsistencies

The master tracker says:

```text
TransactionContext provenance guard (10 allowlist entries, 4 on 45-day expiry)
```

but the current guard appears to have 6 canonical allowlist entries after removing the 4 manual caller entries.

Source:
- Tracker:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b00241d7928e16373dbedabb039948b1fee9bcd4/docs/analyses%20and%20debug%20master/MASTER_ISSUE_TRACKER.md
- Guard:  
  https://raw.githubusercontent.com/panospao7/Cost-agregator/b00241d7928e16373dbedabb039948b1fee9bcd4/app/src/test/java/com/yourname/expensetracker/architecture/TransactionContextProvenanceGuardTest.kt

Update docs to match actual guard state.

---

# MIT status

## MIT-031 — state/event atomicity

Status: **NEAR-COMPLETE, not DONE**.

Good:
- DomainTransactionRunner is wired.
- manual context callers were migrated.
- provenance guard exists.
- direct event guard is structured.
- recurring stale recovery now event-rolls-back.

Still open:
- direct event allowlist is broad;
- repository direct event writers remain;
- context-free writer methods still exist;
- provenance guard pattern is narrow;
- warranty lifecycle events still use direct best-effort writes.

## MIT-041 — receipt/bank atomicity

Status: **NEAR-COMPLETE, not DONE until CI green**.

Good:
- bank runId issue fixed earlier;
- success/failure finalization transaction fixed;
- cancellation no longer masks original CE;
- unexpected failure after receipt creation has run+event finalization;
- skipped item ledger policy appears documented.

Still open:
- no visible green CI for latest commit;
- 32/34 test note suggests not fully clean;
- Notification pipeline / receipt-link cancellation issues still affect nearby receipt pipelines.

## MIT-034 — cancellation propagation

Status: **PARTIAL**.

Still open:
- raw `runCatching` in NotificationProcessingPipeline;
- raw `runCatching` in WarrantyTrackerRepository;
- raw `runCatching` in ReceiptLinkService;
- large allowlist remains;
- guard does not sufficiently ban raw runCatching.

## MIT-043 — recurring/reminder atomicity

Status: **PARTIAL**.

Good:
- stale recovery now transactional/evented.
- hidden write method split.

Still open:
- regeneration best-effort by design;
- DB uniqueness depends on MIT-033;
- recurring projection TODO remains.

## MIT-075 — side effects

Status: **PARTIAL by design**.

No durable outbox exists. Evidence logger only.

---

# Recommended next PR: PR22-small

Do not do another huge rewrite. Make one targeted correctness cleanup.

## PR22 scope

1. Replace raw `runCatching` in `NotificationProcessingPipeline` with CE-safe handling.
2. Replace raw `runCatching` in `ReceiptLinkService`.
3. Either fix or explicitly classify `WarrantyTrackerRepository` lifecycle event writes.
4. Add static guard for raw `runCatching` in suspend/domain/worker/repository paths.
5. Remove `NotificationProcessingPipeline.kt` from cancellation false-positive allowlist.
6. Strengthen TransactionContext provenance regex to `\bTransactionContext\s*\(`.
7. Shorten legacy repository direct-event allowlist expiries.
8. Fix master tracker provenance allowlist count.
9. Get visible green CI for latest commit.

## Required validation

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest --tests "*Cancellation*"
./gradlew :app:testDebugUnitTest --tests "*Architecture*"
./gradlew :app:testDebugUnitTest --tests "*NotificationProcessing*"
./gradlew :app:testDebugUnitTest --tests "*ReceiptLink*"
./gradlew :app:testDebugUnitTest
```

---

# Final verdict

`b00241d` is good progress. It fixes the main PR20 manual-context weakness and improves recurring stale recovery.

But the branch is **not “all remaining items closed”** because:

- core notification path still has raw `runCatching` swallowing cancellation;
- direct event DAO allowlist remains broad;
- CI green is not visible for latest commit;
- warranty lifecycle writes remain best-effort and cancellation-risky;
- MIT-043/MIT-075 are intentionally partial.

Final recommendation:

```text
Keep MIT-031 and MIT-041 as NEAR-COMPLETE.
Keep MIT-034, MIT-043, MIT-075 as PARTIAL.
Do PR22-small before any more closure claims.
```