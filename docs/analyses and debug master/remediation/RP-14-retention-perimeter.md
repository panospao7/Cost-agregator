# RP-14 — Retention perimeter completion (Pipeline 8 + cross-pipeline tables)

> **Scope class:** universal-adjacent — the retention perimeter spans pipelines (P2's event table, P3's receipt fields, P8's diagnostics). **Mode:** strict (privacy).
> **Files:** `di/RetentionModule.kt`, `DataRetentionWorker` (data/privacy/), `TransactionEventDao` + `TransactionEvent` (data/database/), `ScannedReceiptDao` + `ScannedReceipt` (data/database/), `OperationRunDao`/`OperationRunEventDao`/`ReceiptEventDao` + entities (data/database/), `AiArtifactDao` + entity (data/database/), `PrivacyAuditDao` (data/database/), `RawStoragePolicyAuditTest` (test).
> **PR shape:** 2 PRs — (14a) purge targets = P8-001, 002, 003, 007; (14b) purge semantics = P8-004 + REVAL-3 test repair.
> **Depends on:** RP-02 (the worker's audit-write barrier check lands there).

---

## Design principle (applies to every item)

Every new target follows the **existing target pattern** in `di/RetentionModule.kt:33-228`: a `RetentionTarget(name, cutoffPolicy, dao method, audit event)` wired into the worker's sorted, checkpointed, per-target-isolated loop (`DataRetentionWorker.kt:100-235`). **No raw payloads in audit events** — counts and controlled reason codes only. Retention must never be gated on the capability it enforces (AGENTS).

## 14a — New purge targets

### P8-001 — Deleted-expense PII persists forever in `transaction_events` snapshots (HIGH)

**Problem.** `TransactionEvent.beforeSnapshot/afterSnapshot` (`TransactionEvent.kt:79-80`) serialize the **full expense incl. merchant and free-text notes** (`expenseToSnapshot`, `TransactionLifecycleCoordinator.kt:2163-2184`) and by design survive expense deletion; `TransactionEventDao.deleteOlderThan` (`:36`) has **zero callers** and no retention target exists → deleted-expense PII at rest indefinitely; ships in every `.costbackup` (TIER_1_EXACT, `BackupVerifier.kt:94`).

**Fix design.**
1. Add a retention target `transaction_events_snapshot_purge` with a **two-stage** policy (preserves the audit trail while removing PII):
   - Stage 1 (short, e.g. 30 days — align with `background_job_runs.errorMessage`'s 30-day precedent): `UPDATE transaction_events SET beforeSnapshot = NULL, afterSnapshot = NULL WHERE createdAt < :cutoff AND (beforeSnapshot IS NOT NULL OR afterSnapshot IS NOT NULL)` — new DAO method `nullSnapshotsOlderThan(beforeMs): Int`.
   - Stage 2 (long, e.g. 365 days): `deleteOlderThan(beforeMs)` (existing, now called) — event *rows* (type, reason, ids, timestamps) remain auditable for a year, then go.
2. Audit events per stage: `RETENTION_PURGED` with `target=transaction_events_snapshots`, `rowsAffected` (counts only).
3. Snapshot-nulling is irreversible-by-design — the 30-day window keeps recent snapshot support (undo/audit inspection windows) without indefinite PII.

**What it solves.** Deleted-expense merchant/notes survive at most 30 days in snapshots, rows at most a year.

**Guardrails.**
- The events table is the lifecycle audit trail — **verify no UI/feature reads snapshots older than 30 days** (grep `beforeSnapshot`/`afterSnapshot` readers: the coordinator writes them; check any history/diff screen). If an expense-history view renders old snapshots, cap its window or show "snapshot expired" — flag in PR if found.
- Cutoff values: introduce them as named constants in the target definition (same style as existing targets); 30d/365d are recommendations — confirm with stakeholder in PR description.
- No schema change (columns already nullable).

**Tests.** Target unit test in the `RetentionTargetPurgeTest` family: snapshots nulled at 30d, rows deleted at 365d, both audited with counts; recent snapshots untouched.

### P8-002 — OCR purge leaves `parsedItems`/`parsedMerchant` intact (MED-HIGH)

**Problem.** `updateRawOcrTextPurged` (`ScannedReceiptDao.kt:155-164`) nulls only `rawOcrText`; `parsedItems` (`:71`), `parsedMerchant` (`:54`), `parseFailureReason` (`:92`) persist past the user's retention window — and policy itself classifies parsedItems as raw content (`ReceiptPersistencePayload.kt:27-29`), with default mode STORE_RAW making this live.

**Fix design.**
1. Extend the purge statement: `SET rawOcrText = '', parsedItems = NULL, parsedMerchant = NULL, rawOcrTextPurgedAt = :now` (keep `parseFailureReason` — verified short controlled diagnostic code; if it can carry text, null it too — check its writers).
2. Reuse the same target/cutoff as the existing `scanned_receipts.rawOcrText` target (same row set — no new target row; widen the DAO method + update the target's audit reason to mention fields).
3. Coordinate with RP-12/P3-007 (write-time gating) and RP-03/P7-011-style anonymizer: the **export** anonymizer already gets parsedItems-nulling from RP-12 — this change covers **at-rest** retention.

**Guardrails.** Item-categorization features that read `parsedItems` must tolerate null after retention — they already do for DO_NOT_STORE-mode receipts (same field null at write). Purged-vs-never-present distinction: `rawOcrTextPurgedAt` timestamp already marks it. **Tests:** purge fixture nulls all three fields, timestamps set, idempotent re-run (already-purged rows skipped by the existing `purgedAt IS NULL` filter pattern).

### P8-003 — `operation_runs`/`operation_run_events`/`receipt_events` never pruned (MED)

**Problem.** Zero delete queries and zero retention targets on all three tables (`OperationRunDao`, `OperationRunEventDao`, `ReceiptEventDao`); content is sanitized at write (verified) so this is unbounded growth + backup bloat, inconsistent with `background_job_runs`' 30-day redaction.

**Fix design.** Three targets, single policy (90-day hard delete — diagnostics, not audit-critical; keeps recent-run debugging):
- `operation_runs_purge`: delete runs (and their events — verify event rows key to run id and delete children first in one method) older than 90d.
- `receipt_events_purge`: delete older than 90d.
Audit counts per target. **Guardrail:** `OperationRunRecorder`'s in-flight runs are never old — cutoff makes races impossible; delete order (events then runs) inside the single DAO method keeps FK integrity if any exists.

**Tests.** Purge fixture per table; running (recent) rows untouched.

### P8-007 — `privacy_audit_events` unbounded (LOW)

**Fix design.** Target `privacy_audit_purge`: delete older than **180 days** (audit value decays; the table's own write rate is low — only purge events/failures). Same pattern. **Guardrail:** privacy audits are the accountability ledger — 180d is a recommendation; expose as constant; if compliance needs longer, one constant change. **Tests:** purge fixture.

## 14b — Purge semantics & test repair

### P8-004 — `expiresAt IS NULL` AI artifacts are purge-immune (MED, latent)

**Problem.** `AiArtifactDao.deleteExpired` (`:70-71`) requires `expiresAt IS NOT NULL`; entity default is NULL (`AiArtifactEntity.kt:62`). All 7 current writers set TTL via the single `upsert` gateway — latent until a new writer forgets.

**Fix design.**
1. Age-based backstop in the same query: `WHERE (expiresAt IS NOT NULL AND expiresAt < :now) OR (expiresAt IS NULL AND updatedAt < :now - 30d)` (30d = max legitimate TTL in current writers — verify the longest TTL used; use its ceiling).
2. Belt: make the repository gateway (`AiArtifactRepositoryImpl.upsert`) require a TTL — parameter without default, or assert-non-null with `IllegalArgumentException("ai_artifact_ttl_required")` — turning the latent trap into a loud dev-time failure.

**Tests:** NULL-expiresAt row aged past backstop → purged; gateway rejects TTL-less upsert.

### REVAL-3 — `RawStoragePolicyAuditTest` retention coverage is tautological (test repair)

**Problem.** `retention_registry_covers_all_sensitive_targets` (`:211-226`) checks its own hardcoded 4-target set — never queries the real registry (10 targets) — and would not catch any missing purge target (exactly the class P8-001/002/003/007 represent).

**Fix design.** Rewrite the test against the **real** `RetentionRegistry`/module target list: assert (a) the sensitive-table set derived from entity scanning (tables with text/json/blob columns of the sensitive families) is a subset of registered targets, or minimally (b) a maintained explicit list of required targets (all current + the four new ones) matches the registry exactly — fail on both missing and unexpected. Option (b) is deterministic and simple; prefer it, with the list in one place next to the module.

**Guardrails.** This is a CI gate repair — it will fail until 14a lands; ship in the same PR series (14a then 14b, or single PR).

---

## Validation (when Gradle re-enables)
```
./gradlew :app:testDebugUnitTest --tests "*Retention*" --tests "*DataRetentionWorker*" --tests "*RawStoragePolicyAudit*" --tests "*PrivacyStorage*"
```

## Sequencing & risk
- After RP-02. Risk: low-medium — additive targets + one test repair; the only judgment calls are cutoff constants (30/90/180/365d), each called out for stakeholder confirmation in the PR description.
