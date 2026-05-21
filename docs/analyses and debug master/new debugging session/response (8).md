Yes — many issues are **universal**, so I would **not** fix strictly pipeline-by-pipeline from 1 → 12.

Best approach:

1. Fix **cross-cutting foundation issues first**.
2. Then fix each pipeline’s local bugs.
3. Avoid implementing the same pattern differently in every pipeline.

## Universal issues to fix once

### 1. Write/read/restore barrier
Affects Pipelines **1,2,3,4,6,7,9,10,11,12**.

Create one rule:

```text
No DB write unless DatabaseWriteBarrier allows it.
No export/read during restore unless DatabaseReadBarrier allows it.
No direct DAO mutation outside approved lifecycle/coordinator paths.
```

This should be fixed globally, not per pipeline.

---

### 2. Durable diagnostics / lifecycle events
Affects almost every pipeline.

You repeatedly have:

```text
operation failed/skipped/dropped
but no durable event explains why
```

Create common event/diagnostic contracts for:

```text
RECEIVED
ATTEMPTED
DROPPED
SKIPPED
DUPLICATE
VALIDATION_FAILED
PRIVACY_BLOCKED
RESTORE_BLOCKED
SIDE_EFFECT_FAILED
COMPLETED
```

Then each pipeline can use them.

---

### 3. Privacy / raw-storage / redaction
Affects Pipelines **1,3,7,8,10,11,12**.

Do this once:

```text
RawStorageMode applies to every persisted table, not only primary raw rows.
Cloud payloads must use EffectiveCloudAiPolicy.
Debug/export/backup must respect the same privacy policy.
```

This is universal and should be foundation work.

---

### 4. Currency normalization / MoneyAggregate
Affects Pipelines **5,6,10,12**, indirectly **2/3/11**.

Do not fix currency separately in dashboards, budgets, exports, forecasts.

Create one canonical rule:

```text
Financial aggregates must declare rate basis:
HISTORICAL_TRANSACTION_DATE
PERIOD_END
LATEST_AVAILABLE
```

And never fallback to raw foreign amount silently.

---

### 5. Source links / provenance
Affects Pipelines **1,2,3,10,11,12**.

You need one source-link model:

```text
expenseId
sourceType
sourceEntityId
providerId
externalFingerprintHash
metadataJson
```

This solves review, receipt, email, notification, bank, import/export traceability.

---

### 6. Side-effect dispatch contract
Affects Pipelines **2,3,4,6,9,11**.

Universal rule:

```text
DB transaction commits first.
Side effects dispatch once.
Nested coordinator calls must defer side effects to the outer owner.
```

This prevents double dispatch and rollback-observer bugs.

---

### 7. Worker execution contract
Affects Pipelines **4,7,8,9**, indirectly backup/restore.

Fix globally:

```text
WorkerExecutionGuard
WorkerRunLogger
worker drain during restore/backup
cancellation finalization
typed skip reasons
stale RUNNING recovery
```

---

## Recommended order

Do this before pipeline-local fixes:

1. **Write/read barrier + DAO mutation guard**
2. **Privacy/raw-storage/cloud redaction contract**
3. **Diagnostics/lifecycle event outcome model**
4. **Side-effect post-commit contract**
5. **Currency/MoneyAggregate canonical contract**
6. **Source-link/provenance model**
7. **Worker guard/drain/recovery**
8. Then go pipeline by pipeline.

So: **do not fix purely pipeline-by-pipeline**. Fix these universal foundations first, otherwise you will duplicate work and re-break earlier pipelines.