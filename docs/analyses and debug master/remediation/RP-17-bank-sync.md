# RP-17 — Bank sync correctness and safe product boundary

> **Mode:** strict (money, privacy, lifecycle, Room). **Status:** FAIL AS DRAFTED — this replacement may start only after stated decisions are approved.

## Non-negotiable rules

Expense mutations use the transaction lifecycle. Never infer transfer direction from `abs(amount)`, sign, or description. Never weaken TransactionValidator for bank/redacted data. Outcomes and diagnostics use typed controlled codes, not strings. Read-then-insert is not duplicate atomicity. All Room work includes version, migration, snapshots, and migration tests.

## Decision gates

RP-00 must record D1 release/provider behavior, the database identity contract for bank pending reviews, and whether sync status is terminal-only plus ViewModel in-flight state or gains durable running/attempt fields through a migration.

## 17-A — Central availability and release gating

Create one BankFeatureAvailability policy and apply it to FeatureConfig/menu, MainActivity/navigation, deep-link parsing, persisted destination restoration, and public coordinator `initiateConnection`, `completeConnection`, and `syncConnection`. Unavailable release behavior returns typed `FeatureUnavailable`; it cannot silently retry, render empty, or enter integration. Current deep links have no bank route: test rejection and future-proof the contract.

Tests: release menu/route/direct API blocked; deep-link rejected; restored destination replaced/rejected; D1-permitted debug/provider mode works.

## 17-B — Typed result and status boundary

Replace flat SyncResult/string parsing with sealed `BankSyncOutcome`: `Success`, `Partial`, `ReauthRequired`, `Blocked`, `RetryableFailure`, `PermanentFailure`, each carrying counts and controlled codes. Cancellation propagates unless an existing operation contract deliberately models it. Every barrier/token/reauth branch assigns outcome.

Coordinator persists/maps outcomes one-to-one and returns them; VM maps codes to resources/reauth UI. Define partial/failed and last-sync semantics; do not claim persisted RUNNING without the approved migration.

Tests: integration→coordinator→VM mapping table, all outcome branches, cancellation, transitions, operation-run recovery and statement-run recovery separately.

## 17-C — Transfer/refund/amount contract

Provider mapping must supply `TransferDirection` independently and an approved privacy-safe account reference. Missing metadata skips/quarantines before lifecycle with `TRANSFER_METADATA_MISSING`. Do not use a REDACTED placeholder; validator stays strict. Refund/reversal/cashback requires typed provider semantics or skips with `REFUND_UNSUPPORTED`; zero amounts skip with `INVALID_AMOUNT` before mapping.

Tests: missing direction/reference, valid direction with privacy-safe reference under redaction, debit/credit ambiguity, refund/reversal, ordinary purchase/deposit/withdrawal, and zero amount.

## 17-D — Atomic low-confidence reviews and privacy

Add a privacy-safe stable bank-review identity based on provider transaction identity and approved connection/account scope, a unique index, and atomic DAO insert-if-absent. A mutex may optimize but cannot replace the constraint. Add migration/snapshot tests. Apply complete bank RawPersistencePolicy to pending review fields and events; no forbidden title/description/raw payload storage.

Current bankId uniqueness makes two same-bank connections unrepresentable; document account scope/provider namespace before asserting that scenario. Tests: two concurrent syncs for one connection yield one review; conflict/migration behavior; redaction modes.

## 17-E — Token races and stale recovery

Use `updateTokenIfConnected` SQL (`WHERE id = :id AND isConnected = 1`) returning affected rows. Check the write barrier immediately before it; zero rows become typed disconnected/blocked outcome. Do not claim a version/CAS field without approved schema.

Disconnect follows a defined barrier/transaction order, clears credentials, and deletes only reviews for the stable connection identity. Startup independently recovers stale operation runs and stale statement-import runs. Keystore invalidation/transient errors map to controlled outcomes and cancellation propagates.

Tests: refresh/disconnect race; barrier/zero-row update; review cleanup; Keystore taxonomy; each recovery family.

## Deferred provider work and gates

Cursor persistence and outer-batch atomicity are deferred pending a real provider cursor/identity contract; document remaining idempotency. Order: decisions → 17-A → 17-B → 17-C → 17-D → 17-E. Require strict bank/lifecycle/privacy reviews and Room review for schema batches. RP-17 is not approved until all gates and targeted tests pass.
