# PR24 — Final Atomicity / Cancellation / Event-Consistency Polish Plan

Base reviewed commit: `f8c19130457f67b2571d6c3e9d85879570e09fb8`

## Current status

| MIT | Recommended status |
|---|---|
| MIT-031 | Core DONE, global legacy repository-event debt tracked |
| MIT-041 | Conditionally DONE pending visible green CI |
| MIT-034 | PARTIAL |
| MIT-043 | PARTIAL |
| MIT-075 | PARTIAL by design |

## Remaining issues

1. Warranty AI event metadata still persists raw `productName`.
2. Raw `runCatching` allowlist is unstructured.
3. Direct-event guard says “75 days” but enforces a different cutoff.
4. Bank skipped blank-currency rows still persist blank currency / verbose reason.
5. Docs say `PR1–PR22 complete` while latest is `PR23-final`.
6. Latest green CI is not externally visible.
7. Warranty lifecycle state/event writes remain best-effort legacy debt.

Recommended branch:

```bash
git checkout -b atomicity-pr24-final-polish
```

Recommended commits:

1. `PR24-1 — Remove warranty productName from lifecycle metadata`
2. `PR24-2 — Structure raw runCatching allowlist`
3. `PR24-3 — Fix direct-event guard expiry policy wording`
4. `PR24-4 — Sanitize bank blank-currency skipped rows`
5. `PR24-5 — CI verification and docs correction`
6. `PR24-6 — Track warranty repository event debt`

---

## PR24-1 — Remove Warranty `productName` From Event Metadata

### Problem

Warranty descriptions were sanitized, but AI warranty-created metadata still includes raw product name.

Risk:

```kotlin
.put("productName", warranty.productName)
```

Product names can reveal sensitive purchases.

### Implementation

In `WarrantyTrackerRepository.kt`, remove raw product name from:

- `SafeEventMetadata`
- `auditMetadata`
- lifecycle/audit event payloads

Replace with generic metadata:

```kotlin
SafeEventMetadata.builder()
    .put("warrantyDurationMonths", warranty.warrantyDurationMonths)
    .put("warrantyType", warranty.warrantyType.name)
    .put("source", "AI_EXTRACTION")
    .build()
```

If correlation is required, use a hash:

```kotlin
.put("productNameHash", privacyHasher.hash(warranty.productName))
```

Preferred: omit entirely.

### Tests

Add:

- `ai_warranty_created_event_does_not_include_product_name`
- `ai_warranty_created_metadata_omits_product_name`
- `warranty_created_description_is_generic_code`
- `warranty_updated_description_is_generic_code`
- `warranty_event_metadata_has_no_raw_purchase_text`

### Acceptance criteria

- No warranty lifecycle/audit event stores raw product name.
- Warranty descriptions remain generic codes.
- Tests fail if `productName` appears in persisted event metadata.

---

## PR24-2 — Structure Raw `runCatching` Allowlist

### Problem

`RAW_RUN_CATCHING_KNOWN_VIOLATIONS` is still a plain `setOf(...)`.

It lacks:

- owner;
- reason;
- issue;
- expiry;
- category.

MIT-034 remains partial, but this debt should be managed.

### Implementation

Replace:

```kotlin
private val RAW_RUN_CATCHING_KNOWN_VIOLATIONS = setOf(...)
```

with:

```kotlin
data class RawRunCatchingAllowlistEntry(
    val fileName: String,
    val category: String,
    val owner: String,
    val reason: String,
    val issue: String,
    val expires: LocalDate
)
```

Categories:

```text
UI_VIEWMODEL
NETWORK_PROVIDER
LEGACY_REPOSITORY
PURE_NON_SUSPEND
TEST_ONLY
```

Validation:

```kotlin
owner.isNotBlank()
reason.isNotBlank()
issue.isNotBlank()
expires >= today
no duplicate fileName + category
```

Policy:

- core worker/coordinator/repository mutation paths: no raw `runCatching`;
- UI/network entries: time-boxed;
- pure non-suspend usage: documented.

### Tests

Add:

- `raw_runCatching_allowlist_requires_owner_reason_issue_expiry`
- `expired_raw_runCatching_allowlist_fails`
- `duplicate_raw_runCatching_allowlist_fails`
- `core_file_cannot_be_raw_runCatching_allowlisted`
- `runCatchingCancellable_passes`

### Acceptance criteria

- Raw `runCatching` debt is structured and expiring.
- MIT-034 remains honestly partial but better controlled.

---

## PR24-3 — Fix Direct-Event Guard Expiry Policy

### Problem

Guard text says legacy repository entries must expire within 75 days, but code checks a fixed `2026-10-01`, which is closer to ~90 days from July 3, 2026.

### Implementation

Option A, dynamic policy:

```kotlin
val maxExpiry = LocalDate.now().plusDays(75)
assertFalse(entry.expires.isAfter(maxExpiry))
```

Option B, fixed policy:

Change message to:

```text
LEGACY_REPOSITORY entries must expire by 2026-10-01
```

Recommended: Option A.

### Tests

Add:

- `legacy_repository_direct_event_allowlist_cannot_exceed_75_days`
- `writer_implementation_entries_may_be_long_lived`
- `legacy_repository_entry_with_76_day_expiry_fails`

### Acceptance criteria

- Guard message and enforcement match.
- Legacy repository event debt stays time-boxed.

---

## PR24-4 — Sanitize Bank Blank-Currency Skipped Rows

### Problem

For blank currency, bank skipped rows may store:

```kotlin
currency = tx.currency
errorReason = "MISSING_CURRENCY: Currency code is blank"
```

This is less severe than NaN/Infinity, but still inconsistent with structured diagnostics.

### Implementation

In `BankStatementLifecycleProcessor.kt`, change missing/blank currency skipped row to:

```kotlin
currency = null
errorReason = "MISSING_CURRENCY"
```

If schema requires non-null currency, use a documented sentinel:

```kotlin
currency = "UNK"
errorReason = "MISSING_CURRENCY"
```

Prefer `null` if allowed.

Also sanitize invalid currency:

```kotlin
currency = null
errorReason = "INVALID_CURRENCY_CODE"
```

### Tests

Add:

- `blank_currency_creates_skipped_item_with_null_currency`
- `invalid_currency_creates_skipped_item_with_null_currency`
- `blank_currency_reason_is_structured_code`
- `invalid_currency_does_not_create_receipt_or_review`
- `invalid_currency_does_not_create_receipt_lifecycle_event`

### Acceptance criteria

- No blank/invalid currency is persisted as-is in skipped ledger rows.
- Reason strings are structured codes.

---

## PR24-5 — CI Verification and Docs Correction

### Problem

Latest public CI is not visibly green for the latest commits, and docs are stale:

```text
PR1–PR22 complete
```

while latest is PR23-final.

### Implementation

Run:

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

Targeted:

```bash
./gradlew :app:testDebugUnitTest --tests "*Warranty*"
./gradlew :app:testDebugUnitTest --tests "*BankStatement*"
./gradlew :app:testDebugUnitTest --tests "*Cancellation*"
./gradlew :app:testDebugUnitTest --tests "*Architecture*"
```

Update docs:

```text
PR1–PR24 complete.
MIT-031: Core DONE; legacy repository direct-event debt tracked until 2026-08-15.
MIT-041: DONE after PR24 bank ledger fix and green CI.
MIT-034: PARTIAL.
MIT-043: PARTIAL.
MIT-075: PARTIAL by design.
```

Include:

```text
Verified commit:
Commands run:
Result:
Known excluded tests:
Owner:
Expiry:
```

### Acceptance criteria

- Latest commit has visible green CI or documented full local output.
- Docs match actual latest PR number and status.

---

## PR24-6 — Track Warranty Repository Event Debt

### Problem

Warranty event descriptions and metadata can be sanitized, but warranty state/event writes remain best-effort and direct repository-owned.

This means global MIT-031 is not fully clean.

### Implementation

Keep `WarrantyTrackerRepository.kt` in direct-event allowlist as:

```kotlin
category = "LEGACY_REPOSITORY"
owner = "..."
reason = "Warranty lifecycle events are non-critical best-effort until migrated to WarrantyLifecycleCoordinator"
issue = "MIT-031-WARRANTY"
expires = LocalDate.parse("2026-08-15")
```

Add follow-up tracker item:

```text
MIT-031-WARRANTY — migrate warranty lifecycle state/event writes to DomainTransactionRunner or formally classify as diagnostic-only.
```

### Future migration path

Create:

```kotlin
WarrantyLifecycleCoordinator
```

Use:

```kotlin
transactionRunner.runInTransaction("warranty.create") { ctx ->
    warrantyDao.insert(...)
    warrantyLifecycleEventWriter.write(ctx, WARRANTY_CREATED)
}
```

Critical events should rollback with state. Diagnostic events should use diagnostic writer, not lifecycle event DAO.

### Acceptance criteria

- Warranty debt is explicit and time-boxed.
- MIT-031 docs do not imply warranty repository event atomicity is complete.

---

# Final status after PR24

## MIT-031

Can be documented as:

```text
CORE DONE.
Critical coordinator-owned state/event paths are transaction-scoped.
Legacy repository direct-event debt is structured, owned, and expiring.
```

Do not call “global fully done” until repository debt is migrated.

## MIT-041

Can be documented as:

```text
DONE.
Bank receipt/review/finalization paths are atomic.
Invalid/skipped item ledger rows are sanitized.
CI green on latest commit.
```

## MIT-034

Remain:

```text
PARTIAL.
Raw runCatching guard exists, but allowlist remains.
```

## MIT-043

Remain:

```text
PARTIAL.
Recurring regeneration is best-effort and duplicate fulfillment depends on MIT-033.
```

## MIT-075

Remain:

```text
PARTIAL.
Evidence logger only; no durable outbox.
```

---

# Minimal PR24 patch

If time is short, do only:

1. Remove `productName` from warranty AI event metadata.
2. Structure `RAW_RUN_CATCHING_KNOWN_VIOLATIONS`.
3. Fix direct-event 75-day expiry mismatch.
4. Update docs from PR1–PR22 to PR1–PR24.
5. Get visible green CI.

Those are the highest-value remaining fixes.