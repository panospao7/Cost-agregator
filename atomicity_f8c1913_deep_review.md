# Deep Review — Atomicity / Cancellation / Event Consistency

Latest reviewed commit:  
https://github.com/panospao7/Cost-agregator/commit/f8c19130457f67b2571d6c3e9d85879570e09fb8

Key previous commits reviewed:
- PR23 parent: https://github.com/panospao7/Cost-agregator/commit/f652218
- PR22 closure attempt: https://github.com/panospao7/Cost-agregator/commit/42e53e15be17303d945fe75c5afb7e22b963eab5
- PR21: https://github.com/panospao7/Cost-agregator/commit/b00241d7928e16373dbedabb039948b1fee9bcd4
- PR20: https://github.com/panospao7/Cost-agregator/commit/c88964efb401733494e66eae8037b68fe921ccfe

Important files checked:
- `BankStatementLifecycleProcessor.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/f8c19130457f67b2571d6c3e9d85879570e09fb8/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt
- `WarrantyTrackerRepository.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/f8c19130457f67b2571d6c3e9d85879570e09fb8/app/src/main/java/com/yourname/expensetracker/data/repository/WarrantyTrackerRepository.kt
- `CancellationSafetyArchitectureGuardTest.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/f8c19130457f67b2571d6c3e9d85879570e09fb8/app/src/test/java/com/yourname/expensetracker/architecture/CancellationSafetyArchitectureGuardTest.kt
- `DirectEventDaoInsertGuardTest.kt`: https://raw.githubusercontent.com/panospao7/Cost-agregator/f8c19130457f67b2571d6c3e9d85879570e09fb8/app/src/test/java/com/yourname/expensetracker/architecture/DirectEventDaoInsertGuardTest.kt
- `MASTER_ISSUE_TRACKER.md`: https://raw.githubusercontent.com/panospao7/Cost-agregator/f8c19130457f67b2571d6c3e9d85879570e09fb8/docs/analyses%20and%20debug%20master/MASTER_ISSUE_TRACKER.md
- GitHub Actions: https://github.com/panospao7/Cost-agregator/actions

Static review only. I did not run Gradle locally.

---

# Executive verdict

Latest `f8c1913` is a **useful PR23-final polish commit**, but it is not enough to call the whole cancellation/atomicity/event-consistency work globally complete.

What is genuinely improved:

- bank NaN / Infinity skipped rows now store `amount = null`;
- non-positive amount skipped rows also store `amount = null`;
- raw `runCatching` guard was added for suspend paths;
- warranty lifecycle descriptions were changed from raw product-name text to generic event codes;
- direct event DAO allowlist now has a `category`;
- legacy repository direct-event entries are categorized and time-boxed.

But remaining issues:

1. **No visible green CI for latest `f8c1913` / `f652218`.**
2. **Warranty product name still persists in AI warranty-created event metadata.**
3. **Warranty state/event writes are still best-effort and can diverge.**
4. **Raw `runCatching` allowlist is unstructured.**
5. **Direct-event guard still permits many production repositories until 2026-08-15.**
6. **Docs say PR1–PR22 complete even though this is PR23-final.**
7. **MIT-034, MIT-043, MIT-075 are still correctly partial.**

Recommended status:

```text
MIT-031: core DONE / global legacy event debt remains
MIT-041: conditionally DONE after visible green CI
MIT-034: PARTIAL
MIT-043: PARTIAL
MIT-075: PARTIAL by design
Overall: GREEN-YELLOW, not full GREEN
```

---

# What PR23 fixed correctly

## 1. Bank NaN / Infinity amount persistence is fixed

Previously, skipped non-finite bank rows could persist:

```kotlin
amount = tx.amount
```

where `tx.amount` was `NaN`, `Infinity`, or `-Infinity`.

Latest code now stores:

```kotlin
amount = null
errorReason = "INVALID_AMOUNT_NON_FINITE: error class=IllegalArgumentException, item=$index"
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/f8c19130457f67b2571d6c3e9d85879570e09fb8/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt

Status: **fixed**.

This closes the main MIT-041 data-quality issue from the last review.

## 2. Non-positive amount ledger is safer

PR23 also changed non-positive amount skipped rows to:

```kotlin
amount = null
errorReason = "NON_POSITIVE_AMOUNT: error class=IllegalArgumentException, item=$index"
```

Good. This avoids storing bad amount values in the import item ledger.

Status: **fixed**.

## 3. Raw `runCatching` guard exists now

`CancellationSafetyArchitectureGuardTest` now adds:

```kotlin
RAW_RUN_CATCHING_IN_SUSPEND_PATH
```

and scans for:

```kotlin
runCatching {
```

inside suspend function ranges, excluding:

```kotlin
CancellationSafe.runCatchingCancellable
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/f8c19130457f67b2571d6c3e9d85879570e09fb8/app/src/test/java/com/yourname/expensetracker/architecture/CancellationSafetyArchitectureGuardTest.kt

Status: **improved**.

MIT-034 is still partial, but this closes the specific guard weakness I flagged.

## 4. Warranty lifecycle descriptions are mostly sanitized

Latest commit changes descriptions like:

```kotlin
"Warranty created for ${warranty.productName}"
```

to:

```kotlin
"WARRANTY_CREATED"
"WARRANTY_UPDATED"
"WARRANTY_DELETED"
"WARRANTY_AI_EXTRACTION_DISCARDED"
```

Source commit:  
https://github.com/panospao7/Cost-agregator/commit/f8c19130457f67b2571d6c3e9d85879570e09fb8

Status: **partially fixed**.

But metadata still leaks product names. See blocker below.

## 5. Direct event DAO guard has categories

`DirectEventDaoInsertGuardTest` now has:

```kotlin
category
```

with categories such as:

```text
WRITER_IMPLEMENTATION
COORDINATOR
LEGACY_REPOSITORY
SERVICE
```

and validates that category is present.

Status: **improved**.

---

# Remaining blockers / high-impact issues

## BLOCKER 1 — latest CI is still not visibly green

The latest visible GitHub Actions runs still show failures up through `c88964e`. The Actions page I can access does not show a visible green run for:

- `f8c1913`
- `f652218`
- `42e53e1`
- `b00241d`

Actions page:  
https://github.com/panospao7/Cost-agregator/actions

Visible failed run example:

```text
PR20 ... commit c88964e ... Failure
```

Source:  
https://github.com/panospao7/Cost-agregator/actions

This remains a release blocker.

The commit message says:

```text
25/25 architecture guard tests PASS. BUILD SUCCESSFUL.
```

That may be true locally, but final closure should require visible CI or documented full command output for the latest commit.

Required validation:

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

Until then:

```text
MIT-041 should be conditionally done, not release-proven done.
```

---

## BLOCKER 2 — warranty product name still leaks in AI warranty metadata

PR23 changed warranty event descriptions, but `WarrantyTrackerRepository` still writes raw product name into AI warranty-created metadata:

```kotlin
SafeEventMetadata.builder()
    .put("productName", warrantyWithTimestamps.productName)
```

It also builds:

```kotlin
auditMetadata = JSONObject().apply {
    put("productName", warrantyWithTimestamps.productName)
    ...
}
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/f8c19130457f67b2571d6c3e9d85879570e09fb8/app/src/main/java/com/yourname/expensetracker/data/repository/WarrantyTrackerRepository.kt

This means the “product name sanitization” is incomplete.

Why it matters:

- product name can reveal sensitive purchase/medical/personal items;
- event descriptions are sanitized, but persisted metadata is still raw;
- this contradicts the privacy-hardening intent.

Required fix:

Option A, recommended:

```kotlin
metadata = SafeEventMetadata.builder()
    .put("warrantyDurationMonths", warrantyWithTimestamps.warrantyDurationMonths)
    .put("warrantyType", warrantyWithTimestamps.warrantyType.name)
    .build()
```

Remove product name entirely.

Option B:

```kotlin
.putHashed("productName", warrantyWithTimestamps.productName)
```

if correlation is needed.

Also remove unused/raw `auditMetadata` if it is not persisted.

Required tests:

- `ai_warranty_created_event_does_not_include_product_name`
- `ai_warranty_created_metadata_omits_or_hashes_product_name`
- `warranty_created_description_is_generic_code`

Status: **not fully fixed**.

---

## BLOCKER 3 — warranty state/event writes are still best-effort and can diverge

Warranty writes still do this pattern:

```kotlin
database.withTransaction {
    warrantyDao.insertWarranty(...)
    try {
        warrantyLifecycleEventDao.insert(...)
    } catch (e: Exception) {
        if (e is CancellationException) throw e
        Timber.w(...)
    }
}
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/f8c19130457f67b2571d6c3e9d85879570e09fb8/app/src/main/java/com/yourname/expensetracker/data/repository/WarrantyTrackerRepository.kt

Because the event failure is caught inside the transaction block, the warranty state mutation can commit without the event.

This is acceptable only because `WarrantyTrackerRepository.kt` is categorized as `LEGACY_REPOSITORY` in the direct event DAO allowlist until `2026-08-15`.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/f8c19130457f67b2571d6c3e9d85879570e09fb8/app/src/test/java/com/yourname/expensetracker/architecture/DirectEventDaoInsertGuardTest.kt

So MIT-031 can be described as:

```text
core coordinator-owned paths DONE
legacy repository event debt remains
```

But not:

```text
all state/event atomicity globally done
```

Required follow-up:

- migrate warranty lifecycle writes to `DomainTransactionRunner`;
- if events are critical, do not swallow insert failures;
- if events are diagnostic, move them out of critical lifecycle wording and document as best-effort.

---

## BLOCKER 4 — raw `runCatching` allowlist is unstructured

`CancellationSafetyArchitectureGuardTest` now has:

```kotlin
RAW_RUN_CATCHING_KNOWN_VIOLATIONS = setOf(...)
```

with 12 files.

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/f8c19130457f67b2571d6c3e9d85879570e09fb8/app/src/test/java/com/yourname/expensetracker/architecture/CancellationSafetyArchitectureGuardTest.kt

This is better than no guard, but unlike the main cancellation allowlist, it lacks:

- owner;
- reason;
- issue;
- expiry;
- category.

Required fix for MIT-034 progress:

```kotlin
data class RawRunCatchingAllowlistEntry(
    val fileName: String,
    val owner: String,
    val reason: String,
    val issue: String,
    val expires: LocalDate
)
```

Also add expiry test.

MIT-034 correctly remains **PARTIAL**.

---

## BLOCKER 5 — direct-event allowlist policy says 75 days but enforces Oct 1

The guard test says:

```text
LEGACY_REPOSITORY entries must expire within 75 days
```

but checks:

```kotlin
expires.isAfter(LocalDate.of(2026, 10, 1))
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/f8c19130457f67b2571d6c3e9d85879570e09fb8/app/src/test/java/com/yourname/expensetracker/architecture/DirectEventDaoInsertGuardTest.kt

From July 3, 2026, October 1 is roughly 90 days, not 75.

Current legacy entries expire August 15, so the actual entries are fine. But the guard policy and message are inconsistent.

Fix either:

```kotlin
LocalDate.now().plusDays(75)
```

or change the message to match the fixed cutoff.

---

## BLOCKER 6 — bank skipped invalid currency still persists blank currency

For blank currency, the skipped ledger still stores:

```kotlin
currency = tx.currency
errorReason = "MISSING_CURRENCY: Currency code is blank"
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/f8c19130457f67b2571d6c3e9d85879570e09fb8/app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt

This is less severe than NaN/Infinity amount, but for consistency I would prefer:

```kotlin
currency = null
errorReason = "MISSING_CURRENCY"
```

if schema allows nullable currency.

If currency is non-null by schema, use a sentinel code only if documented.

Severity: **low/medium**.

---

## BLOCKER 7 — docs are stale: says PR1–PR22 complete while latest is PR23-final

`MASTER_ISSUE_TRACKER.md` says:

```text
PR1–PR22 complete
```

while the latest commit is explicitly:

```text
PR23-final
```

Source:  
https://raw.githubusercontent.com/panospao7/Cost-agregator/f8c19130457f67b2571d6c3e9d85879570e09fb8/docs/analyses%20and%20debug%20master/MASTER_ISSUE_TRACKER.md

This should be updated.

Recommended wording:

```text
PR1–PR23 complete.
MIT-031 core DONE with legacy repository direct-event debt tracked until 2026-08-15.
MIT-041 conditionally DONE pending visible CI.
MIT-034/MIT-043/MIT-075 remain PARTIAL.
```

---

# MIT status recommendation

## MIT-031 — State/event atomicity

Latest docs say DONE.

Recommended:

```text
MIT-031: CORE DONE / GLOBAL PARTIAL-DEBT
```

Why:

Fixed:
- `DomainTransactionRunner` adopted in critical coordinator paths.
- transaction context provenance guard exists.
- manual `TransactionContext` construction is blocked by guard.
- direct event DAO guard is structured and categorized.

Still open:
- legacy repositories still directly insert events until `2026-08-15`;
- warranty lifecycle state/event writes can still diverge;
- context-free deprecated writer methods likely still exist, though blocked by deprecation.

So: core closure is defensible, global closure is not.

## MIT-041 — Receipt/bank atomicity

Latest docs say DONE.

Recommended:

```text
MIT-041: CONDITIONALLY DONE
```

Why:

Fixed:
- bank NaN / Infinity amount persistence fixed;
- bank finalization/cancellation cleanup fixed in prior PRs;
- skipped row audit ledger policy exists;
- receipt/bank critical paths much safer.

Still needed:
- visible green CI;
- optionally clean blank currency skipped row;
- add/confirm tests for duplicate/invalid skipped ledger behavior.

## MIT-034 — Cancellation propagation

Status: **PARTIAL**.

Correct.

Remaining:
- 97 allowlist entries;
- raw `runCatching` allowlist is unstructured;
- UI/AI/infra/domain debt remains.

## MIT-043 — Recurring/reminder atomicity

Status: **PARTIAL**.

Correct.

Remaining:
- regeneration best-effort by design;
- duplicate fulfillment depends on MIT-033;
- full projection/duplicate closure not done.

## MIT-075 — Outbox/evidence

Status: **PARTIAL by design**.

Correct.

No durable outbox exists.

---

# Recommended next PR: PR24 final polish

Keep it small.

## PR24 scope

1. Remove or hash `productName` from `AI_WARRANTY_CREATED` metadata.
2. Convert `RAW_RUN_CATCHING_KNOWN_VIOLATIONS` to structured allowlist with owner/reason/issue/expiry.
3. Fix direct event guard “75 days” policy mismatch.
4. Update docs from PR1–PR22 to PR1–PR23.
5. Add visible CI proof for latest commit.
6. Optional: set blank currency skipped bank rows to null / sanitized sentinel.

## Required validation

```bash
./gradlew :app:compileDebugKotlin
./gradlew :app:testDebugUnitTest --tests "*Warranty*"
./gradlew :app:testDebugUnitTest --tests "*BankStatement*"
./gradlew :app:testDebugUnitTest --tests "*Cancellation*"
./gradlew :app:testDebugUnitTest --tests "*Architecture*"
./gradlew :app:testDebugUnitTest
```

---

# Final verdict

Your PR23 work did implement most of the previous suggestions:

- bank non-finite amount fixed;
- raw `runCatching` guard added;
- warranty descriptions sanitized;
- direct event categories added.

But “all implementation plan items CLOSED” is still too strong because:

- latest CI is not visibly green;
- warranty product name still leaks in `AI_WARRANTY_CREATED` metadata;
- warranty state/event writes remain best-effort legacy debt;
- raw `runCatching` allowlist is unstructured;
- tracker docs are stale.

Final recommended status:

```text
MIT-031: core DONE, global legacy debt tracked
MIT-041: conditionally DONE after visible CI
MIT-034: PARTIAL
MIT-043: PARTIAL
MIT-075: PARTIAL
Overall: GREEN-YELLOW
```