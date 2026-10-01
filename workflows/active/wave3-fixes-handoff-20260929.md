# Wave 3 fixes — handoff (started 2026-09-29, base HEAD 0b44cd4d)

Source of findings: `docs/analyses and debug master/campaign CA-2026-09-21/verification-2026-09-22-wave3-wave4.md`.
Recheck at 0b44cd4d: all 8 findings still present.

Status: IMPLEMENTED; CURRENT VALIDATION PENDING. Nothing committed. Validation is run by the human.

## Batch 1 — HISTORICAL VALIDATION (human-run 2026-09-29)

compile `vr-20260929-172327-ef051146` PASS; `*ReceiptDuplicateDetector*` `vr-20260929-173223-eb0dd699` PASS;
`*CsvExpenseImporter*` `vr-20260929-174242-8c51ef19` PASS; `*ReceiptLifecycleCoordinator*` `vr-20260929-174707-44ecfbd2` PASS.

- CL-11 / CA-P-03-004: `ReceiptDuplicateDetector.checkDuplicate` no longer returns TEXT_FINGERPRINT
  when both semantic fingerprints are known and differ; falls through to SEMANTIC/EXTERNAL_ID.
  New test: `ReceiptDuplicateDetectorTest`.
- CL-25 / CA-P-12-001: `CsvExpenseImporter` mints one UUID `csvImportBatchId` per import run and passes
  1-based `csvRowNumber`, satisfying `CreateExpenseSourceLinkRequirements` for CSV_IMPORT.
  New tests in `CsvExpenseImporterTest`. Follow-up (out of scope): `JsonExpenseImporter` same gap.

## Batch 2 (implemented, validation PENDING)

- CL-08 / CA-P-02-003: `TransactionLifecycleCoordinator.dedupeKeyForUpdate` keeps an existing `idem:` key and
  recomputes content keys otherwise; applied at updateExpense, updateMerchant, updateType,
  updateTypeAndTransferDetails, bulkUpdateMerchant (preflight + write). updateTypeAndTransferDetails no-op check
  also treats a type change on an `idem:` row as a change (collision preflight still runs).
  DAO collision prechecks ignore the dedupeKey arg, so fuzzy collision detection is unchanged.
  New tests (6) in `TransactionLifecycleCoordinatorUpdateTest`.
- Validation run 1: compile `vr-20260929-180509-a49d2e0a` PASS; `*TransactionLifecycleCoordinator*`
  `vr-20260929-181033-4263fa07` FAIL — `TransactionLifecycleCoordinatorDbContractTest.createExpense duplicate
  detected and skipped` expected 2 events, got 3. The 3 CL-08 suites all passed.
  DISPOSITION (b), a pre-existing production double-write, not caused by CL-08/CL-11 (neither touches createExpense):
  - A STANDARD/BULK precheck duplicate wrote CREATE_DUPLICATE_SKIPPED in `writeDuplicateEvent`, then returned
    a negative id. That fell through to the `insertedId <= 0` insert-conflict resolver, which wrote a second
    CREATE_DUPLICATE_SKIPPED.
  - Already listed as failing in `docs/testing/generated/TEST_FAILURE_TRACKER.md:154` and
    `docs/ci/DEFERRED_ISSUES_AND_DEBUG_HANDOFF.md:536`.
  - Final repair: emit `CREATE_DUPLICATE_SKIPPED` only after the precheck or conflict resolver has a concrete
    existing ID. An unresolved precheck falls through without an audit, so it produces exactly one resolved
    duplicate event or one `CREATE_INSERT_CONFLICT` event, never two duplicate/conflict audits.
  - New mock tests: `precheck duplicate writes exactly one duplicate-skipped event` and
    `unresolved precheck duplicate does not duplicate audit when conflict resolver resolves` (ConflictResolutionTest).
- Validation run 2 (post-fix) — VALIDATED: compile `vr-20260929-184707-caa9b7b7` PASS;
  `*TransactionLifecycleCoordinator*` `vr-20260929-185538-3d3a79ec` PASS; `*BankApiIntegration*`
  `vr-20260929-190958-b4ef267b` PASS; `*LifecycleFullContractGolden*` `vr-20260929-191526-836df684` PASS.
- The historical runs above do not fingerprint the current mixed worktree. After the final
  duplicate-event and warranty-failure repairs, all targeted tests and static guards must be
  rerun against the current tree before any closure or commit decision.

## Current closure state

- CL-10 / CA-P-03-002, CA-E-05-005, CL-07 / CA-P-02-002, and CA-I-05-001 are implemented;
  current-tree validation and strict review are pending.
- The unresolved-precheck duplicate path now suppresses a second duplicate event when the later
  conflict resolver identifies the existing expense; a focused regression test covers that path.
- Return-window persistence failures now return a controlled retryable failure, cancellation still
  propagates, and retries repair the return-window side effect for an existing warranty.
- The disclosed validator edits are ratified against the current source: the three policy-fixture
  re-pin groups match the authorized policy state, the MockK callback capture uses the project's
  supported matcher-scope API, and `Long?` return-window stubs use `returns null` while the
  `Unit`-returning `updateWarranty` stub remains `returns Unit`.
- The inherited `raw_money_aggregates` guard is explicitly carried as a separate follow-up,
  not attributed to Wave 3. The current evidence is 84 findings across 29 production files:
  5 `G-MONEY-RAW-01`, 55 `G-MONEY-RAW-02`, 6 `G-MONEY-RAW-03`, 4 `G-MONEY-RAW-04`, and
  14 `G-MONEY-RAW-06`. Do not broaden its baseline or allowlist to obtain a green result.
- The raw-money remediation requires a separate money-architecture batch with per-surface
  conversion and rounding tests; it is not safe to fold a 29-file refactor into Wave 3 closure.
- Do not mark Wave 3 complete until current-fingerprint validation, strict review, device gates,
  and the commit decision are complete.
