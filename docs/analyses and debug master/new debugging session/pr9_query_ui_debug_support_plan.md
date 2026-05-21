# PR 9 — Query / UI / Debug Support

## Assumptions

PR1–PR8 are already merged:

- `entity_source_links` exists.
- Expense creation writes source links.
- Pending-review promotion exists.
- Receipt/email/notification/bank/import/export paths write provenance.
- Backfill worker has seeded legacy provenance.
- Source-link rows are privacy-safe and indexed by:
  - `targetEntityType + targetEntityId`
  - `sourceIdentityKey`
  - `operationRunId`
  - `correlationId`

If those indexes are missing, add them before or inside PR9 with a tiny migration. Otherwise PR9 should be schema-free.

---

# 1. Goal

Add query, UI, and debug support so developers and support/debug users can answer:

```text
Why does this expense exist?
Which notification/review/receipt/email/bank/import source created it?
Which lifecycle events happened for the same correlation ID?
Which source identity maps to which expense?
Was this source linked normally, promoted, duplicated, redacted, or backfilled?
```

PR9 should make provenance observable without changing write behavior.

---

# 2. Non-goals

Do not include:

- new source-link write paths
- source-link schema redesign
- backfill changes
- export/import changes
- raw source artifact viewing
- production analytics upload
- static guard script work; that is PR10
- editing/relinking provenance from the UI

PR9 is read/query/debug only.

---

# 3. Main architecture

Add a read-side repository:

```kotlin
interface SourceProvenanceRepository {
    suspend fun getExpenseProvenance(expenseId: Long): ExpenseProvenance
    suspend fun getTargetProvenance(target: SourceTargetRef): TargetProvenance
    suspend fun findExpensesBySourceIdentity(sourceIdentityKey: String): List<Long>
    suspend fun findProvenanceByCorrelationId(correlationId: String): CorrelatedProvenance
    suspend fun findProvenanceByOperationRunId(operationRunId: Long): OperationRunProvenance
    suspend fun searchSourceLinks(query: SourceLinkSearchQuery): SourceLinkSearchResult
}
```

Implementation should live in the read/query layer, not inside writers.

Recommended package:

```text
app/src/main/java/com/yourname/expensetracker/domain/provenance/query/
```

---

# 4. Files to add

## Repository / DTOs

```text
domain/provenance/query/SourceProvenanceRepository.kt
domain/provenance/query/SourceProvenanceRepositoryImpl.kt
domain/provenance/query/ExpenseProvenance.kt
domain/provenance/query/TargetProvenance.kt
domain/provenance/query/SourceChain.kt
domain/provenance/query/ProvenanceNode.kt
domain/provenance/query/ProvenanceEdge.kt
domain/provenance/query/ProvenanceTimelineItem.kt
domain/provenance/query/CorrelatedProvenance.kt
domain/provenance/query/OperationRunProvenance.kt
domain/provenance/query/SourceLinkSearchQuery.kt
domain/provenance/query/SourceLinkSearchResult.kt
domain/provenance/query/ProvenanceGraphBuilder.kt
domain/provenance/query/ProvenanceMetadataSanitizer.kt
domain/provenance/query/ProvenanceSummaryFormatter.kt
```

## UI / debug

Adjust package names to the app’s existing UI layout:

```text
ui/screens/provenance/ExpenseProvenanceScreen.kt
ui/screens/provenance/ExpenseProvenanceViewModel.kt
ui/screens/provenance/ExpenseProvenanceUiState.kt
ui/screens/provenance/SourceChainCard.kt
ui/screens/provenance/SourceLinkRow.kt
ui/screens/provenance/ProvenanceTimeline.kt
ui/screens/debug/SourceProvenanceDebugScreen.kt
ui/screens/debug/SourceProvenanceDebugViewModel.kt
```

## DI

```text
di/ProvenanceQueryModule.kt
```

or add to the existing repository/module binding file.

---

# 5. Files to modify

```text
data/database/dao/EntitySourceLinkDao.kt
data/database/dao/TransactionEventDao.kt
data/database/dao/ReceiptEventDao.kt
data/database/dao/PipelineDiagnosticEventDao.kt
data/database/dao/OperationRunDao.kt
data/database/dao/OperationRunEventDao.kt
```

Only add read methods.

Possible UI integration files:

```text
ui/screens/expense/ExpenseDetailScreen.kt
ui/navigation/AppNavigation.kt
ui/screens/settings/DeveloperOptionsScreen.kt
```

Do not modify writer services except maybe to expose existing DTOs.

---

# 6. DAO query additions

## `EntitySourceLinkDao`

Add:

```kotlin
@Query("""
    SELECT * FROM entity_source_links
    WHERE targetEntityType = :targetType
      AND targetEntityId = :targetId
    ORDER BY isPrimary DESC, createdAt ASC, id ASC
""")
suspend fun getForTarget(targetType: String, targetId: Long): List<EntitySourceLink>
```

If not already present.

Add batch query:

```kotlin
@Query("""
    SELECT * FROM entity_source_links
    WHERE targetEntityType = :targetType
      AND targetEntityId IN (:targetIds)
    ORDER BY targetEntityId ASC, isPrimary DESC, createdAt ASC, id ASC
""")
suspend fun getForTargets(targetType: String, targetIds: List<Long>): List<EntitySourceLink>
```

Add source lookup:

```kotlin
@Query("""
    SELECT * FROM entity_source_links
    WHERE sourceIdentityKey = :sourceIdentityKey
    ORDER BY createdAt DESC, id DESC
""")
suspend fun getBySourceIdentityKey(sourceIdentityKey: String): List<EntitySourceLink>
```

Add correlation lookup:

```kotlin
@Query("""
    SELECT * FROM entity_source_links
    WHERE correlationId = :correlationId
    ORDER BY createdAt ASC, id ASC
""")
suspend fun getByCorrelationId(correlationId: String): List<EntitySourceLink>
```

Add operation-run lookup:

```kotlin
@Query("""
    SELECT * FROM entity_source_links
    WHERE operationRunId = :operationRunId
    ORDER BY createdAt ASC, id ASC
""")
suspend fun getByOperationRunId(operationRunId: Long): List<EntitySourceLink>
```

Add source-entity reverse lookup:

```kotlin
@Query("""
    SELECT * FROM entity_source_links
    WHERE sourceEntityType = :sourceEntityType
      AND sourceEntityLocalId = :sourceEntityLocalId
    ORDER BY createdAt ASC, id ASC
""")
suspend fun getBySourceEntity(
    sourceEntityType: String,
    sourceEntityLocalId: Long
): List<EntitySourceLink>
```

Add search with limits. Avoid unbounded debug queries:

```kotlin
@Query("""
    SELECT * FROM entity_source_links
    WHERE (:targetType IS NULL OR targetEntityType = :targetType)
      AND (:sourceType IS NULL OR sourceType = :sourceType)
      AND (:sourceEntityType IS NULL OR sourceEntityType = :sourceEntityType)
      AND (:linkStatus IS NULL OR linkStatus = :linkStatus)
      AND (:linkRole IS NULL OR linkRole = :linkRole)
    ORDER BY createdAt DESC, id DESC
    LIMIT :limit OFFSET :offset
""")
suspend fun search(...)
```

---

# 7. Graph-building rules

`ProvenanceGraphBuilder` should build a safe, bounded graph.

## 7.1 Start point: expense

For:

```text
target = EXPENSE, id = expenseId
```

load all source links.

Each source link becomes an edge:

```text
source node -> expense node
```

## 7.2 Follow upstream chains

Follow known local source nodes:

### Pending review

If expense has:

```text
sourceEntityType = PENDING_REVIEW
sourceEntityLocalId = reviewId
```

then load:

```text
target = PENDING_REVIEW, id = reviewId
```

This shows:

```text
RAW_NOTIFICATION -> PENDING_REVIEW -> EXPENSE
SCANNED_RECEIPT -> PENDING_REVIEW -> EXPENSE
BANK_TRANSACTION -> PENDING_REVIEW -> EXPENSE
```

### Scanned receipt

If expense has:

```text
sourceEntityType = SCANNED_RECEIPT
sourceEntityLocalId = receiptId
```

then load:

```text
target = SCANNED_RECEIPT, id = receiptId
```

This shows:

```text
EMAIL_RECEIPT_SOURCE -> SCANNED_RECEIPT -> EXPENSE
```

### Operation run

If a link has:

```text
operationRunId != null
```

load operation-run summary and operation events.

### Bank sync run

If source entity type is `BANK_SYNC_RUN`, load operation/run info if available.

## 7.3 Bounded recursion

Use strict limits:

```text
maxDepth = 4
maxNodes = 100
maxEdges = 200
```

If truncated, return:

```kotlin
truncated = true
truncationReason = "MAX_DEPTH" / "MAX_NODES"
```

This prevents debug screens from accidentally doing huge graph walks.

---

# 8. DTO shape

## `ExpenseProvenance`

```kotlin
data class ExpenseProvenance(
    val expenseId: Long,
    val primarySummary: String?,
    val directLinks: List<ProvenanceLinkSummary>,
    val sourceChain: SourceChain,
    val timeline: List<ProvenanceTimelineItem>,
    val warnings: List<String>,
    val privacyRedactions: List<String>
)
```

## `SourceChain`

```kotlin
data class SourceChain(
    val nodes: List<ProvenanceNode>,
    val edges: List<ProvenanceEdge>,
    val truncated: Boolean = false,
    val truncationReason: String? = null
)
```

## `ProvenanceNode`

```kotlin
data class ProvenanceNode(
    val id: String,
    val entityType: String,
    val localId: Long?,
    val displayName: String,
    val badge: String?,
    val metadata: Map<String, Any?> = emptyMap()
)
```

Examples:

```text
EXPENSE #123
PENDING_REVIEW #77
RAW_NOTIFICATION #45
SCANNED_RECEIPT #88
EMAIL_RECEIPT_SOURCE #12
BANK_TRANSACTION provider=demo hash=abc123…
CSV_IMPORT_ROW batch=b1 row=42
```

## `ProvenanceEdge`

```kotlin
data class ProvenanceEdge(
    val id: Long,
    val fromNodeId: String,
    val toNodeId: String,
    val role: String,
    val status: String,
    val isPrimary: Boolean,
    val confidence: Float?,
    val createdAt: Long,
    val correlationId: String?,
    val safeMetadata: Map<String, Any?>
)
```

## `ProvenanceTimelineItem`

Merge relevant events:

```text
EntitySourceLink creation
TransactionEvent
ReceiptEvent
PipelineDiagnosticEvent
OperationRunEvent
```

Each item should include:

```text
time
type
source
summary
correlationId
safeMetadata
```

---

# 9. Timeline query rules

For an expense, gather:

1. source links for expense
2. transaction events for expense
3. receipt events for linked receipt IDs
4. operation events for operationRunIds
5. diagnostics with matching correlation IDs

Sort by:

```text
occurredAt/createdAt ASC, type priority, id ASC
```

Type priority suggestion:

```text
DIAGNOSTIC_RECEIVED
DIAGNOSTIC_ATTEMPTED
TRANSACTION_CREATED
SOURCE_LINKED
RECEIPT_LINKED
DUPLICATE_SKIPPED
OPERATION_EVENT
```

If timestamps are missing or equal, stable-sort by ID.

---

# 10. Correlation debug support

Implement:

```kotlin
suspend fun findProvenanceByCorrelationId(correlationId: String): CorrelatedProvenance
```

It should return:

```text
source links with correlationId
transaction events with correlationId
receipt events with correlationId
diagnostic events with correlationId
operation events with correlationId
target expenses
target pending reviews
target receipts
```

Use this in the debug screen to answer:

```text
What happened during this import/sync/review run?
```

---

# 11. Source identity search support

Implement:

```kotlin
suspend fun findExpensesBySourceIdentity(sourceIdentityKey: String): List<Long>
```

Behavior:

- return direct expense targets
- if target is `PENDING_REVIEW`, find promoted/approved expense links where source is that pending review
- if target is `SCANNED_RECEIPT`, find expense links from that scanned receipt
- include duplicate-linked expenses if status is `DUPLICATE`

Result should include a reason:

```kotlin
data class ExpenseSourceIdentityMatch(
    val expenseId: Long,
    val matchType: MatchType,
    val linkStatus: String,
    val linkRole: String
)
```

Examples:

```text
DIRECT_EXPENSE_SOURCE
VIA_PENDING_REVIEW
VIA_SCANNED_RECEIPT
DUPLICATE_MATCH
LEGACY_PARTIAL
```

---

# 12. Metadata sanitizer

Add `ProvenanceMetadataSanitizer`.

Even though PR1–PR8 should already enforce safe writes, PR9 UI must be defensive.

## Blocked keys

```text
rawText
rawBody
body
emailBody
emailSubjectRaw
emailSenderRaw
notificationTitle
notificationText
bankDescription
bankReference
ocrText
accessToken
refreshToken
password
secret
prompt
fullPath
iban
accountNumber
cardNumber
providerTransactionId
messageId
```

## Allowed display keys

```text
providerId
accountIdHash
externalIdHash
externalFingerprintHash
operationRunId
importBatchId
importRowNumber
parserVersion
confidence
linkType
dedupeReason
bookingDate
valueDate
transactionStatus
sourceArtifactPolicy
metadataSchemaVersion
promotedFromPendingReviewId
promotedFromSourceLinkId
```

If metadata contains unknown keys:

- display only if value is scalar and key passes safety check
- otherwise replace with:

```text
[redacted]
```

---

# 13. UI support

## 13.1 Expense detail integration

Add a provenance section or action:

```text
Source / Provenance
```

Compact view:

```text
Created from: Receipt scan
Chain: Email receipt → Scanned receipt → Expense
Status: Active
```

Actions:

```text
View source chain
Copy correlation ID
Copy source identity key
```

Copying should be limited to safe IDs/hashes.

## 13.2 Dedicated provenance screen

`ExpenseProvenanceScreen` should show:

1. top summary card
2. source-chain cards
3. direct source links table
4. event timeline
5. warnings/redactions

Do not render raw metadata JSON by default. Render sanitized key/value rows.

## 13.3 Debug search screen

`SourceProvenanceDebugScreen` should support search by:

```text
expenseId
sourceIdentityKey
correlationId
operationRunId
sourceType
sourceEntityType
linkStatus
```

Results should be paginated/limited.

Only expose this screen:

```text
debug builds OR developer mode enabled
```

If the app already has a developer options screen, add entry there.

---

# 14. ViewModel behavior

Use:

```kotlin
sealed interface ExpenseProvenanceUiState {
    data object Loading
    data class Loaded(val provenance: ExpenseProvenance)
    data class Error(val message: String)
}
```

Rules:

- call repository on IO dispatcher
- never block main thread
- show redaction warnings
- handle missing source links gracefully:

```text
"No provenance found. This may be a legacy row that predates source-link backfill."
```

---

# 15. Performance rules

- Use batch queries.
- Do not recursively query without limits.
- Do not load raw source payload tables unless explicitly needed and safe.
- Limit debug search default to 50 rows.
- Timeline cap default: 200 events.
- If capped, show warning.

---

# 16. Tests

## Repository tests

```text
expense_provenance_returns_notification_chain
expense_provenance_returns_email_receipt_chain
expense_provenance_returns_bank_sync_chain
expense_provenance_returns_csv_import_chain
expense_provenance_returns_legacy_partial_chain
expense_provenance_includes_duplicate_source_link
expense_provenance_includes_transaction_events
expense_provenance_includes_receipt_events
expense_provenance_includes_operation_events
expense_provenance_is_stably_ordered
source_chain_truncates_at_max_depth
source_chain_truncates_at_max_nodes
```

## Search tests

```text
find_expenses_by_source_identity_direct
find_expenses_by_source_identity_via_pending_review
find_expenses_by_source_identity_via_scanned_receipt
find_expenses_by_source_identity_duplicate_match
find_provenance_by_correlation_id_returns_links_and_events
find_provenance_by_operation_run_id_returns_bank_or_import_links
search_source_links_filters_by_status_role_and_type
```

## Privacy tests

```text
provenance_metadata_sanitizer_blocks_raw_notification_text
provenance_metadata_sanitizer_blocks_email_body
provenance_metadata_sanitizer_blocks_bank_description
provenance_metadata_sanitizer_blocks_tokens
expense_provenance_ui_does_not_render_raw_metadata_json
debug_search_does_not_display_raw_sensitive_values
```

## ViewModel tests

```text
expense_provenance_viewmodel_loads_success
expense_provenance_viewmodel_handles_missing_links
expense_provenance_viewmodel_surfaces_redaction_warning
debug_viewmodel_searches_by_correlation_id
debug_viewmodel_searches_by_source_identity_key
```

## UI tests, if Compose tests exist

```text
expense_provenance_screen_shows_chain_summary
expense_provenance_screen_shows_timeline
source_debug_screen_search_displays_safe_results
source_debug_screen_redacts_sensitive_metadata
```

---

# 17. Implementation order

## Step 1 — Add read DAO methods

Add missing source-link/event/diagnostic read queries.

Run DAO compile/tests before building repository.

## Step 2 — Add DTOs

Create DTOs for:

```text
ExpenseProvenance
TargetProvenance
SourceChain
ProvenanceNode
ProvenanceEdge
TimelineItem
SearchResult
```

Keep DTOs UI-safe.

## Step 3 — Add metadata sanitizer

Defensive redaction first, before exposing any UI.

Add privacy tests immediately.

## Step 4 — Implement `ProvenanceGraphBuilder`

Start with direct expense links.

Then add upstream traversal:

```text
PENDING_REVIEW
SCANNED_RECEIPT
OPERATION_RUN
```

Add depth/node caps.

## Step 5 — Implement repository

Build:

```text
getExpenseProvenance
findExpensesBySourceIdentity
findProvenanceByCorrelationId
findProvenanceByOperationRunId
searchSourceLinks
```

Add in-memory DB integration tests.

## Step 6 — Add ViewModels

Add:

```text
ExpenseProvenanceViewModel
SourceProvenanceDebugViewModel
```

Use repository only. No DAO calls from UI.

## Step 7 — Add screens

Add compact expense-detail entry and full provenance screen.

Add debug search screen behind developer/debug gate.

## Step 8 — Add timeline rendering

Merge transaction/source/receipt/operation/diagnostic events.

Keep capped and sanitized.

## Step 9 — Polish / empty states

Handle:

```text
no links
legacy partial links
redacted links
duplicates
truncated chains
missing source artifacts
```

## Step 10 — Final regression

Run source-link PR tests and UI tests to ensure no write behavior changed.

---

# 18. Acceptance criteria

PR9 is done when:

```text
1. A repository can return complete provenance for an expense.

2. Source chains work for:
   - notification -> review -> expense
   - email -> receipt -> expense
   - bank sync -> expense
   - CSV/JSON import -> expense
   - legacy partial backfill

3. Developers can search by:
   - expenseId
   - sourceIdentityKey
   - correlationId
   - operationRunId

4. Expense detail has a safe provenance view.

5. Debug screen is gated behind debug/developer mode.

6. Timeline combines source links and lifecycle/diagnostic events.

7. UI and repository never expose raw notification/email/OCR/bank sensitive text.

8. Query paths are bounded and performant.

9. PR9 introduces no new write-side behavior.
```

---

# 19. Sources / context

- Latest referenced baseline:  
  https://github.com/panospao7/Cost-agregator/commit/6fee004aa141878820db9240d751ea22f20c4a52

- Global source-links plan:  
  `docs/analyses and debug master/new debugging session/global_source_links_provenance_plan.md`

- Relevant existing entities / concepts:
  - `EntitySourceLink`
  - `TransactionEvent`
  - `ReceiptEvent`
  - `PipelineDiagnosticEvent`
  - `OperationRun`
  - `OperationRunEvent`
  - `PendingReview`
  - `ScannedReceipt`
  - `EmailReceiptSource`

- PR dependency chain:
  - PR1 schema/writer
  - PR2 coordinator integration
  - PR3 pending-review promotion
  - PR4 receipt/email integration
  - PR5 notification integration
  - PR6 bank source model
  - PR7 import/export
  - PR8 backfill worker