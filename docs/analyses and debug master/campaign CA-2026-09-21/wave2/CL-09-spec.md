# CL-09 Implementation Spec — conditional stale-write protection

Provenance: 2026-09-26; starting HEAD `a4807632`; audit anchor `37601232`. Sources: cluster map, Wave-2 prompts, historical Wave-2 verification, current source/callers/tests, and exact DB ownership policy.

## GATE

All four findings remain confirmed by current-source inspection. Relevant production targets are unchanged from the audit anchor except TransactionLifecycleCoordinator's already-landed A1 cancellation check. Historical independent finding verification remains applicable; this is not a new independent implementation review. Implementation and 29 additional regression tests are authored; author static self-review is recorded separately. Live validation and independent review remain pending.

## Context for the coder

Background snapshots must not overwrite newer receipt decisions, regenerate deleted recurring rules, corrupt a newly edited merchant key, or post a warranty reminder after its parent becomes inactive. Conditional database writes and checked results protect these boundaries.

## Pre-implementation checks

- Scoped production files have no pre-existing working-tree or staged changes.
- Read each current DAO query, lifecycle owner, caller, and affected test harness. Function signatures, not historical line numbers, control the edits.
- Preserve the existing maintenance carve-out: `config/guards/db_ownership_policy.yml` identifies `ExpenseRepository.updateMerchantKey(Long, String)` exactly. Do not add a parameter, broaden that policy, or add an exception.

## Legal path constraints

- Receipt suggestions remain under ReceiptMatchLifecycleService and its barrier/transaction/event boundary (receipt matching, segment 38).
- Recurring activation remains under RecurringRuleLifecycleCoordinator (segment 7).
- Merchant backfill retains the existing explicitly policy-listed repository maintenance method and barrier, not a new DAO writer (startup/background, segment 12).
- Warranty delivery retains its existing worker guard, claim-before-notify ownership, and success-only notification metrics (warranty segment).
- Query predicates and return types only: no entity, schema, migration, or baseline changes.

## Work items

### WI-1 — conditional receipt suggestion (CA-P-03-001, P1, CONFIRMED)

- `ScannedReceiptDao.updateMatchSuggestion`: require UNMATCHED/SUGGESTED and a null expense link. Treat an identical SUGGESTED target/confidence as an idempotent no-op. Preserve column-scoped writes; never restore raw OCR or parsed fields.
- `ReceiptMatchLifecycleService.saveMatchSuggestion`: return Boolean; missing receipt or affected-row count other than one returns false without a MATCH_SUGGESTED event. Insert the event atomically only after the successful update.
- ReceiptMatchingWorker counts a suggestion/updated row only on true. UI callers already reload persisted state and may ignore false; adapt their success mock's return type without unrelated UI changes.
- Tests: real DAO/service coverage for new and changed suggestions, identical retry, rejection, linked/terminal receipt, missing row, and preservation of unrelated columns; worker coverage for a lost CAS and no false update count.

### WI-2 — atomic recurring activation (CA-P-04-005, P1, CONFIRMED)

- Move the rule read inside the existing activation transaction. Missing or already-active rules are no-ops.
- `ManualRecurringExpenseDao.setActiveStatus` returns the affected count and only updates a different active state. Abort activation before generation/projection/events unless exactly one row changed.
- Preserve the existing generation transaction and rollback semantics. Do not add foreign keys or redesign deactivation.
- Tests: in-transaction read, missing/deleted rule, zero-row update, repeated activation without duplicate derived state/events, successful regeneration, and rollback on generation failure.

### WI-3 — merchant backfill CAS without policy expansion (CA-P-09-001, P2, CONFIRMED)

- Keep `ExpenseRepository.updateMerchantKey(Long, String)` parameter types unchanged; return a Boolean result. Re-read the current expense, reject a populated key or a supplied key inconsistent with MerchantKeyGenerator applied to the current merchant.
- Pass that current merchant to the existing DAO operation as its compare value. DAO update requires a still-null key and unchanged merchant and returns the affected count. This protects changes both before the repository read and between read and write.
- Worker metrics increase only for true. A lost CAS is a benign skip followed by a fresh batch read, not a fake update or a persistence failure. Preserve the bounded batch budget and retry behavior for actual failures.
- ExpenseRepository is a necessary adapter dependency omitted from the old file union; its exact ownership signature is explicitly preserved.
- Tests: current/populated/deleted/renamed rows, a merchant changing between read and CAS, successful fill, repeated fill, and worker zero-row/exception outcomes. Update unit and instrumented success stubs for Boolean.

### WI-4 — warranty parent eligibility at claim and dispatch (CA-E-05-008, P2, CONFIRMED)

- Both WarrantyReminderDeliveryDao claim entry points additionally require an existing ACTIVE parent.
- Add a read query verifying that the claimed delivery still belongs to an ACTIVE parent. WarrantyExpirationWorker invokes it immediately before posting. If false, conditionally return its CLAIMED row to FAILED using controlled constant `warranty_parent_not_eligible`; do not post or count a notification.
- Preserve sent-state deduplication, stale-claim recovery, and notification delivery-result semantics. This does not pretend a database read and the external notification call form one atomic transaction.
- Tests: active/inactive/deleted parent, both claim entry points, parent transition after claim, no notification or success metric when eligibility is lost, and eligible delivery behavior.

### WI-5 — adjudicate A1 transaction cancellation (already fixed)

Current TransactionLifecycleCoordinator lines 2318-2325 rethrow CancellationException. `git blame` attributes the added broad-catch check at line 2324 to `3b7cabfe`, merged through CL-05. No duplicate source edit is needed. Startup remains for CL-15 verification.

## Tests and validation

Primary classes: ReceiptMatchLifecycleServiceTest, ReceiptMatchingWorkerTest, RecurringRuleLifecycleCoordinatorTest, MerchantKeyBackfillWorkerTest, ExpenseRepositoryMerchantKeyBackfillTest (new if no suitable existing fixture), WarrantyReminderDeliveryDaoTest, WarrantyExpirationWorkerTest. ReceiptMatchingViewModelTest and instrumented merchant-worker fixtures require signature-compatible stubs. Include real Room CAS tests, not source-marker tests standing in for execution.

Human-run validation policy remains in effect. Prepare serialized targeted-unit-test commands, then compile; instrumented coverage must be labeled separately. No live validation has been run.

## Blast-radius fence

ALLOWED: the ten named production files; the named regression classes; necessary existing fixture/helper adaptations; this cluster's spec/review/execution records.

FORBIDDEN: ownership-policy/baseline edits, new mutation bypasses, transaction coordinator rewrites, OCR/privacy resurrection, schema changes, unrelated ViewModel logging fixes, money-core changes, and deferred suite recovery.

## Review gate

Architecture/Room focus: SQL predicates, checked affected-row counts, transactional reads and derived writes, exact maintenance ownership signature, caller counters, cancellation, and retry idempotency. Label author self-review honestly; independent guardian approval and runtime evidence are still required for closure.

## Privacy and worker constraints

No raw payloads or exception text in new diagnostics. Use a controlled warranty eligibility reason constant. Preserve worker guards, barriers, cancellation propagation, and metrics only for successful mutations/posts.
