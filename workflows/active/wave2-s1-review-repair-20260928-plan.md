# Wave-2 S1 review repair — bounded execution plan

Date: 2026-09-28. Status: IN PROGRESS; implementation and execution are not yet verified.

Authority: the user's request to repair the findings in wave2-recovery-s1-strict-technical-review-20260928.md. One agent; no delegation. Preserve all existing changes and historical reports. Human-owned, globally serialized validation; no builds/tests/guards executed by the implementation agent. No staging, commits or history operations.

## Scope and design

1. S1-REV-01: restore independent OCR coverage on every existing-receipt duplicate return, including PARSE_FAILED. Keep the stored failure status, receipt ID, single save event, cancellation and no-duplicate-side-effect invariants.
2. Use the existing receipt event owner and a read-only, bounded latest-RECEIPT_SAVED metadata projection. Do not load the whole audit history or add a Room column/migration. Update the one direct ReceiptEventDao fake when its interface grows.
3. Persist a versioned, controlled coverage envelope for new saves, including complete/image cases, through the existing context-aware writer. Continue accepting the existing positive PDF_PARTIAL envelope. Reject malformed, unsupported, oversized or inconsistent evidence rather than coercing it to complete.
4. Retention is not changed. Missing/purged/unreadable evidence for a parse-failed duplicate is explicitly unverified and reaches a bounded UI warning; it is not fabricated complete coverage or a guessed failed-page count. Cancellation still propagates.
5. Carry both known partiality and unverified coverage to scan review and duplicate UI. Clear both for a fresh complete scan. Preserve existing partial-page wording and add separate uncertainty wording.
6. S1-REV-02: retain all 19 stress cases, wire named lifecycle/link/parser collaborators, initialize the category subscription and currency fixtures, share the explicit test scheduler and cancel owned scopes on teardown. Replace obsolete repository expectations with actual coordinator expectations. Assert AI application is pending until successful save rather than pretending draft application persists it immediately.
7. Add real-Room combined parse-failure/duplicate, alternate duplicate-route, retention, metadata-read and cancellation regressions; bounded metadata codec/DAO tests; and UI-state regressions. No assertion weakening, skip substitution, filter removal or guard-policy change.

## Intended paths

Production: ReceiptOcrCoverage.kt, ReceiptEventDao.kt, ReceiptLifecycleCoordinator.kt, ReceiptScanViewModel.kt, ReceiptScanScreen.kt, values/strings.xml.
Tests: ReceiptOcrCoverageMetadataTest.kt (new), ReceiptEventDaoTest.kt, ReceiptPartialOcrPipelineTest.kt, ReceiptScanViewModelTest.kt, ReceiptScanViewModelStressTest.kt, LegacyDataConsistencyCheckerTest.kt (only the new read-interface implementation).
New plan/handoff documents under workflows/active. No existing campaign record, review, ownership policy, baseline, allowlist, RawQuery pin or generated output is to be modified.

## Gates and stop conditions

- Inspect/read back each bounded patch and the final diff, including affected callers and all retained stress assertions.
- Stop on unexpected tree drift, active validation, schema/ownership ambiguity or unrelated changes.
- Author-side strict inspection is not independent review or guardian approval.
- Publish exact source hashes and the changed full-tree fingerprint, with compile plus the original ten S1 filters and added codec/DAO/fake filters serialized through scripts/vrun.ps1. Preserve per-run XML and actual case/skip counts. Validation remains NOT RUN until the human executes it.
- S2 remains gated on reviewed and execution-verified S1. S3 policy/advisory work, S4 remaining evidence and S5 accumulated reviews/device gates are separate; this repair does not silently close them.
- FINAL_CI_GUARD_ACCEPTANCE_GATE.md FG-03, FG-06, FG-07 and FG-23 remain applicable.

## Intake

Branch bug-fixes, HEAD a48076322e57f3d312f34cf6a71139efc4fcc48b; index empty. Intake 2026-09-28T19:05:46.2481375+03:00: 132 tracked dirty + 84 untracked paths, fingerprint 6b6a4d524cac25288fc35ea4e0bc89367b617577c6c30ae5756962ee39f54499. Byte-identical to the completed review snapshot before this new plan.

