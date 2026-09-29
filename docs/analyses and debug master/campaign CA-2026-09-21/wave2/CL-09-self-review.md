# CL-09 author static self-review

September 27 update: the new eventFailureRollsBackSuggestion failed in the human sweep. The nine-test pre-batch class did not contain that test, so its PASS is not an equivalent identity-regression comparison. The fixture now follows the existing non-copying throwable convention while retaining assertSame and rollback assertions; three additional suspended-failure/cancellation cases are authored. No production transaction change was made in this repair. Rerun and independent gates remain pending. See workflows/active/wave2-validation-repair-20260927.md. The author-inspection record below is the initial September 26 snapshot, not a runtime approval.

VERDICT: PASS (author static inspection only).

This is not independent reviewer/guardian approval, a runtime test result, or cluster closure. No new waiver is assumed. The four source-grounded findings and the exact change fence are in CL-09-spec.md.

## Inspection

- Receipt suggestions require an unresolved, unlinked row. Identical retries are no-ops. The transaction returns false before an event is emitted when the row is absent or the conditional update loses; worker counters follow the Boolean result.
- Recurring activation reads inside its existing Room transaction. Missing/already-active rules and zero-row updates do not generate occurrences, projections, or events. Generation failure retains the existing rollback boundary.
- Merchant backfill keeps the policy-listed ExpenseRepository.updateMerchantKey(Long, String) signature and write barrier. Canonical-key revalidation plus an unchanged-merchant/still-null DAO predicate protects both stale snapshots and a race after the fresh read. A lost update is a benign skip, with fresh bounded batch reads and no false success metric.
- Both warranty claim entry points require an ACTIVE parent. The worker rechecks the claimed row and parent immediately before external notification dispatch; an ineligible parent produces no notification or success count. The DB check and OS notification are not claimed to be atomic.
- No entity/schema, ownership policy, allowlist, baseline, worker guard, tracking setting, or unrelated production path was changed. Existing A1 transaction cancellation protection is preserved, not duplicated.

## Tests authored

29 new tests: receipt lifecycle 6; receipt worker 2; recurring coordinator 5; repository merchant-key CAS 8; merchant worker 2; warranty delivery DAO 3; warranty worker 3. Real Room coverage exercises affected-row predicates, idempotency, stale/deleted rows, post-read transitions, and rollback. Existing UI and instrumented merchant-worker success stubs were adapted for Boolean results.

## Validation

- git diff --check: PASS for the affected app files (whitespace inspection only).
- Compile, unit tests, instrumented tests, static guards: NOT RUN. Human-run policy in stage3-wave2-coder-prompts.md remains in effect.
- Independent review and architecture/privacy guardian gates: PENDING.

Run the following through the blocking wrapper, one at a time, on a quiescent checkout. Use profile targeted-unit-test with each listed filter, then profile compile:

    pwsh scripts/vrun.ps1 -Worktree . -Profile targeted-unit-test -TestFilter '*ReceiptMatchLifecycleServiceTest'

Remaining filters: *ReceiptMatchingWorkerTest, *RecurringRuleLifecycleCoordinatorTest, *ExpenseRepositoryMerchantKeyBackfillTest, *MerchantKeyBackfillWorkerTest, *WarrantyReminderDeliveryDaoTest, *WarrantyExpirationWorkerTest, *ReceiptMatchingViewModelTest, and *ReceiptLinkServiceColumnScopeTest.

    pwsh scripts/vrun.ps1 -Worktree . -Profile compile

Instrumented merchant-worker coverage needs a separate device-enabled validation run; unit-test filters do not supply that evidence. Read durable result.json files before reporting any runtime PASS. Guard hardening and its full-suite report remain the later CL-22 batch.

## Risks / follow-up

- Room SQL generation and all new Kotlin fixtures still require compilation and execution.
- External notification dispatch cannot be made atomic with a parent-state read by these predicates; the change narrows that existing boundary and does not redesign delivery semantics.
- No cluster milestone is marked complete or mergeable on this self-review alone.
