# RP-14 — Retention perimeter completion

> **Status:** CONDITIONAL — source-compatible design is specified below; cutoff policy still needs
> stakeholder/compliance approval.
> **Mode:** strict (privacy, Room DAOs, worker terminal behavior, and audit retention).
> **Depends on:** RP-02 for final worker/write-barrier behavior and RP-12 for the receipt structured-
> data persistence contract.

## Scope and existing worker contract

`RetentionModule` registers `RetentionTarget`s and `DataRetentionWorker` sorts them, selects one
cutoff from `target.name`, checkpoints each target, and calls `purge(cutoff)`. The current worker
cannot express two cutoffs through one ordinary target. It also clears checkpoints after the loop
even when a target failed, retries only transient failures, and can report success after a permanent
failure. This plan fixes those semantics rather than assuming the registry already provides them.

Retention runs with `requiredCapabilities = emptyList()` so cleanup is never gated by the privacy
capability it enforces. Preserve `WorkerExecutionGuard`, restore/write barriers, cancellation
propagation, centralized `WorkerSpecScheduler`, and bounded controlled diagnostics.

## 14a — Explicit retention targets

### P8-001 — Two-stage transaction-event retention

Register two independently reported targets with an explicit numeric order prefix and add both
names to the worker cutoff routing. The prefix is required because the worker sorts target names
lexicographically; it guarantees snapshot nulling runs before row deletion and makes checkpoint
resume deterministic. Alternatively, a single compound target may own both stages, but it must
report separate stage counts/failures and checkpoint between them.

| Target | Cutoff | DAO operation |
| --- | ---: | --- |
| `10_transaction_events.snapshots` | 30 days | Null `beforeSnapshot` and `afterSnapshot` for every older event that still has either value |
| `20_transaction_events.rows` | 365 days | Call the existing `deleteOlderThan` |

Use all transaction events for stage 1, not only deleted-expense events: update/delete/create
snapshots can all carry merchant, notes, or other financial data. Current source has no production
snapshot reader outside the lifecycle writer; repeat that search immediately before implementation.
If a history/debug consumer is added, it must tolerate `null` and show “snapshot expired,” not block
the purge.

The two operations are individually idempotent and produce separate count-only results. Stage 1
success followed by stage 2 failure leaves stage 1 committed, records only the rows target as
failed, and resumes at the failed target under the checkpoint policy below. No schema change is
needed because both snapshot columns are nullable.

Tests cover 29/30/364/365-day boundaries, all event types, idempotent rerun, stage-2 failure after
stage-1 success, and checkpoint resume.

### P8-002 — Purge all disallowed receipt structured fields in SQL

Widen the existing `scanned_receipts.rawOcrText` target's DAO update so it clears the fields defined
as raw/structured-sensitive by the approved RP-12 mode contract, including `rawOcrText`,
`parsedItems`, and `parsedMerchant`. Verify every `parseFailureReason` writer: retain it only if it
is a controlled code; otherwise clear it in the same SQL statement. Set `rawOcrTextPurgedAt` once.

Use a set-based/batched SQL update where practical; do not materialize OCR or item payloads into
Kotlin just to clear them. Price protection, categorization, review, and exports must tolerate the
purged representation established by RP-12. Tests cover all fields, recent rows, already-purged
rows, and idempotency.

### P8-003 — Operation-run and receipt-event retention

Add these explicit targets with approved constants (proposed policy: 90 days), and add each exact
name to the worker's cutoff routing (do not rely on the `else` default):

* `30_operation_runs`: one DAO transaction selects only terminal runs whose `finishedAt` is before the
  cutoff, deletes child `operation_run_events` by run id, then deletes the parent runs. The schema
  declares no FK/cascade, so deletion order is explicit. Never purge `RUNNING` rows. Separately
  delete orphan `operation_run_events` older than the cutoff by `occurredAt` after defining “orphan” as null or no
  matching parent.
* `40_receipt_events`: delete by `occurredAt` before the cutoff. Confirm no compliance/history surface
  requires a longer window before approving 90 days.

The compound operation-run target returns separate child/parent/orphan counts through typed result
metadata or controlled diagnostics, while `rowsPurged` remains the total. The database transaction
prevents child-success/parent-failure partial state. Tests cover old terminal runs with events,
recent terminal runs, old running runs, orphan events, and rollback on failure.

### P8-007 — Bound privacy-audit retention

Add `PrivacyAuditDao.deleteOlderThan(timestamp)` and a `50_privacy_audit_events` target (proposed policy:
180 days). Purge events remain count-only and safe. Because this table is itself the accountability
ledger, the exact cutoff requires compliance/product approval and must be one named constant.
Tests cover the cutoff boundary and recent rows.

## 14b — Artifact TTL and worker terminal semantics

### P8-004 — Null-TTL artifacts need a verified backstop

`AiArtifactEntity` has both nullable `expiresAt` and non-null `updatedAt`; current writers found by
source search pass an expiry, with known TTL constants up to 30 days. Before coding, enumerate all
entity/repository writers again and determine the maximum legitimate TTL. Then extend the DAO query
to delete either expired rows or null-expiry rows whose `updatedAt` is older than that approved
maximum/backstop. Pass both `now` and `nullExpiryCutoff` explicitly; do not perform timestamp
arithmetic in SQL with an unchecked constant.

Strengthen the repository gateway to require a non-null expiry for durable artifacts, while keeping
the DAO backstop for legacy/direct rows. If a legitimate non-expiring artifact exists, model it with
an explicit retention class rather than `null`. Tests cover normal TTL, null old/recent rows, the
maximum boundary, and every writer/gateway.

### Worker failure, checkpoint, and audit contract

After attempting all targets:

* any transient target failure returns worker retry through the existing guarded retry contract;
* any permanent target failure returns worker failure/typed final failure, never success;
* completed target checkpoints remain; failed targets are not marked complete;
* checkpoints are cleared only when every registered target succeeds;
* on retry/resume, successful earlier targets are skipped and the first incomplete target resumes;
* cancellation always propagates and does not clear checkpoints.

Do not build retry classification from arbitrary exception message substrings. Use controlled DAO/
SQLite/IO exception classes or a typed `RetentionPurgeFailure` and store only failure code, class,
target, and counts. Audit writes must occur after cleanup and be best-effort/barrier-safe; an audit
write failure cannot roll back or prevent deletion. Replace free-form reason strings with controlled
reason codes plus bounded numeric context.

Tests cover transient failure, permanent failure, audit-write failure, cancellation, crash/resume,
and the stage-1/stage-2 partial case.

## 14c — Registry coverage guard

Replace the tautological `RawStoragePolicyAuditTest` set with an independent registry assertion.
Define required sensitive targets in a policy descriptor owned by the privacy contract (not in the
Hilt module's construction list). The test instantiates the real registry and asserts every policy
descriptor appears exactly once, every registered target has a unique name and explicit cutoff
route, and no descriptor is missing. Do not derive both sides from the same implementation list.

Required names include all current targets plus `10_transaction_events.snapshots`,
`20_transaction_events.rows`, `30_operation_runs`, `40_receipt_events`, and
`50_privacy_audit_events`. A static/entity audit may supplement
this list but does not replace explicit ownership decisions for sensitive columns.

## Room and validation requirements

The DAO additions and updates above do not change Room schema. If implementation adds a retention
column, index, FK, or artifact retention class, increment the database version, add a non-destructive
migration, update schema snapshots, and run migration tests.

Targeted tests (when approved; not run while editing this plan):

```text
*Retention*
*DataRetentionWorker*
*RawStoragePolicyAudit*
*PrivacyStorage*
*TransactionEventDao*
*OperationRun*
*Migration*
```

Strict review must verify worker guarding, target/cutoff routing, SQL payload clearing, terminal
failure mapping, checkpoint behavior, privacy-safe diagnostics, and any Room migration. Sequence
the implementation as target/DAO contracts, worker failure/checkpoint behavior, then the registry
guard; do not mark the plan complete until all retention tests and reviewer/guardian gates pass.
