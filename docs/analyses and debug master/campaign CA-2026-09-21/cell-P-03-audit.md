# Cell P-03 Audit — Receipt, OCR, Statement, Matching

## Provenance
- Campaign: CA-2026-09-21
- Cell: P-03
- Mode: AUDIT (static only)
- Pinned commit: 37601232b9778170c57a656a245b199ab6d7d965 (`git rev-parse --short HEAD` = `37601232`)
- Session: direct astra session
- Date: 2026-09-21
- Production diff check: `git diff --stat 37601232..HEAD -- app config scripts` empty

## Scope and Coverage
- Matrix row: P-03 — Receipt, OCR, statement, matching.
- Legal paths: Receipt Mutations; Bank Statement Mutations; Privacy / Cloud AI; Expense Mutations; Workers / Background Jobs.
- Segment sections: Segment 4 Receipt Scanning (OCR) & Receipt Lifecycle; Segment 5 AI Receipt Item Categorization; Segment 12 Startup & Background Runtime; Segment 14 Bank Integration; Segment 20 AI Platform, Assistant & Follow-Through; Segment 28 Security & API Key Management; Segment 38 Receipt Matching.
- Primary files: `ReceiptLifecycleCoordinator.kt`, `ReceiptRepository.kt`, `ReceiptLinkService.kt`, `ReceiptMatchLifecycleService.kt`, `ReceiptMatchingWorker.kt`, `BankStatementLifecycleProcessor.kt` (shared with P-10, usage traced here).
- Known IDs excluded from new findings: FRESH-P3-001..FRESH-P3-010.
- Coverage log (append as read):
  - [x] governing prompt §2/§4 and known-debt baseline
  - [x] COVERAGE_MATRIX P-03 row
  - [x] LEGAL_PATHS exact sections
  - [x] CODEBASE_SEGMENTS relevant sections
  - [x] primary coordinator/repository/link/match/worker/statement files
  - [x] callers, DAOs, event/side-effect/privacy/barrier boundaries (bounded; expanded coverage remains partial)
  - [x] tests/guards static inspection and final all-15-class pass (no execution)

## Findings

### Coverage checkpoint 1
- Read governing prompt §2/§4, P-03 matrix row, all ten FRESH-P3 baseline entries, and RP-00 decisions D1–D8 plus relevant subsequent D10/D11.
- Extracted legal paths: Receipt Mutations 123–204; Privacy / Cloud AI 364–390; Workers / Background Jobs 471–505; Bank Statement Mutations 785–814; Expense Mutations opening section and receipt assignment contract.
- Fully read `domain/receipt/lifecycle/ReceiptLinkService.kt` (552 lines), `ReceiptMatchLifecycleService.kt` (234), and `data/database/dao/ScannedReceiptDao.kt` (278). Traced column-scoped match writes, AUTO_MATCH CAS, join insertion, category dispatch, unlink propagation, event writes, and retention SQL.
- Read worker main loop 1–215 and tail 216–329: guarded execution, checkpoints, permission-local notification posting, delivery-result metrics, error mapping, manual/periodic overlap.
- Coordinator/repository reads so far are PARTIAL, not yet credited as full-file coverage. Camera draft/save, email create/link, atomic create/link, deletion and batch paths examined; remaining sections will be logged when read.
- Candidate under investigation: suggestion writer can overwrite a newer REJECTED/matched state; SQL only predicates on id. No finding assigned pending debt/test/caller checks.
- Candidate under investigation: live ReviewViewModel bulk receipt deletion calls deprecated repository path. No finding assigned pending known-debt review.
- Existing campaign cross-cell overlap: P-02 already reports nested link category dispatch before outer commit; do not count again here.
## CA-P-03-001 | A stale suggestion can reopen a rejected or already matched receipt
- **Defect class:** 3 (Atomicity / TOCTOU); related 11 (Data integrity).
- **Severity:** P1.
- **Evidence at pin:** `app/src/main/java/com/yourname/expensetracker/service/receiptmatching/ReceiptMatchingWorker.kt`, `doWork`, lines 56–82 and 186–194 reads the eligible list and calculates a match before the write. `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptMatchLifecycleService.kt`, `saveMatchSuggestion`, lines 43–68 rereads existence, but never checks the current match status. `app/src/main/java/com/yourname/expensetracker/data/database/dao/ScannedReceiptDao.kt`, `updateMatchSuggestion`, lines 116–128 unconditionally sets SUGGESTED by id; `getProcessableReceipts`, lines 159–167 includes SUGGESTED. `app/src/main/java/com/yourname/expensetracker/ui/screens/receiptmatching/ReceiptMatchingViewModel.kt`, `rejectSuggestion`, lines 230–241 supplies the competing user write.
- **Impact path:** periodic/manual matching reads receipt R → matcher suspends → user rejects R (REJECTED) → stale suggestion commits SUGGESTED → rejected receipt reappears and becomes eligible for future automatic linking. If manual approval wins during the matcher window, the stale write leaves a real expense link but changes its match status back to SUGGESTED. Atomic event insertion does not make the earlier eligibility decision current.
- **Caller trace:** `ReceiptMatchingWorker.doWork` → `matcher.findBestMatch` → `ReceiptMatchLifecycleService.saveMatchSuggestion` → `ScannedReceiptDao.updateMatchSuggestion`; also `ReceiptMatchingViewModel.runAutoMatching` lines 145–175 and `rerunForReceipt` lines 316–348. The UI's per-receipt busy set does not serialize the background worker.
- **Existing tests/guards:** `ReceiptMatchingWorkerTest.kt` tests claim failure at 240–263 and requires the AUTO_MATCH claim at 432 onward; suggestion test at 537–549 checks invocation only. `ReceiptMatchLifecycleServiceTest.kt` enumerated tests cover diagnostic writers, not this interleaving. `ScannedReceiptClaimTest.kt` targets `claimForAutoMatch`, not `updateMatchSuggestion`. Static DAO ownership policy permits the lifecycle writer but does not impose a status predicate. No tests or guards executed.
- **Cross-cell impact:** P-09/I-01 worker overlap; I-05 matching UI.
- **Old-ID cross-refs:** none for this suggestion-state race. FRESH-P3-004 concerns stale full-row resurrection; the service/link portion uses column-scoped writes now, while a planner remainder survives. The state-eligibility defect here survives the service fix. FRESH-P3-009 concerns the planner's auto-link CAS argument, not suggestions.

### Coverage checkpoint 2
- Fully read `BankStatementLifecycleProcessor.kt` (1–1061) as a consumer path: OCR delegation, stable candidateId merge, explicit-currency policy, receipt/run/event transaction, per-item review/item/event transaction, final statuses and bounded NonCancellable cancellation finalization. FRESH-P3-005/-006 remain excluded as tracked pending work.
- Fully read `ReceiptInsertResolver.kt`, `ReceiptExpenseLinkDao.kt`, `ReceiptEvent.kt`, `RoomDomainTransactionRunner.kt`, and `DatabaseWriteBarrier.kt`. The transaction runner adds no barrier; the barrier checks current mode only (including `runWrite`). Shared-surface implementation ownership remains I-02.
- UI mutation excerpts read: `ReceiptMatchingViewModel.kt` 145–355. Known-debt scan included RP-12, RP-13, RP-20/21 and WAVE-1 RP-21 feed references. Bulk clear is explicitly listed as GR-08n2 maintenance in `config/guards/db_ownership_policy.yml` 2273–2287, with old P3-0D5-09 / MIT-DB-08N references: do not report that already tracked bypass as a new runtime finding.
## CA-P-03-002 | Local receipt OCR is blocked unless cloud and image-upload consent are enabled
- **Defect class:** 12 (Error handling); related 9 (Privacy capability boundary), 13 (Wiring).
- **Severity:** P1 (core scan unavailable under default privacy settings).
- **Evidence at pin:** `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt`, `processReceiptInput`, lines 331–342 unconditionally calls `requireAllowed(CLOUD_AI_RECEIPT_OCR)` before the repository; lines 730–733 return its exception as failure. `app/src/main/java/com/yourname/expensetracker/domain/privacy/EffectiveCloudAiPolicy.kt`, `requireAllowed`, lines 92–114 and resolver lines 130–149 require both global cloud and receipt-image-upload switches. `app/src/main/java/com/yourname/expensetracker/domain/privacy/PrivacySettings.kt`, lines 5–7 defaults both flags to false. The actual image route is `app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptOcrService.kt`, `processUriWithMime` 261–275 → `processImage` 343–365 → `recognizeText` 746–759 → ML Kit `TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)` at 126–134. This branch performs local image decoding, local file saving, and ML Kit recognition; no cloud OCR provider is selected.
- **Impact path:** default/offline privacy settings → camera/gallery image selection → coordinator throws CLOUD_AI_DISABLED before local OCR → scan fails. Enabling global cloud alone still fails with RECEIPT_IMAGE_UPLOAD_DISABLED until both privacy and AI image flags permit upload. This unnecessarily ties local expense entry to permissions for remote processing.
- **Caller trace:** `app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModel.kt`, scan job lines 284–299 → coordinator; batch import → `ReceiptRepository.processBatch` lines 603–615 uses the same coordinator. No no-cloud fallback exists before the gate. Statement usage differs: `BankStatementLifecycleProcessor.processBankStatement` 185–190 delegates directly to `runStatementOcr`, so this is specifically the camera/batch entry boundary.
- **Existing tests/guards:** `ReceiptLifecycleCoordinatorTest.kt` injects `effectiveCloudAiPolicyResolver = mockk(relaxed = true)` at line 214; scan tests therefore do not exercise the production default-denied policy. Capability-matrix unit tests establish correct CLOUD semantics, not the correctness of gating a LOCAL caller. No tests or guards executed.
- **Cross-cell impact:** P-08 privacy policy consumers; I-05 receipt UI and batch import; I-03 injected OCR implementation.
- **Old-ID cross-refs:** none found in the current registry, RP-12/13/15 searches, RP-00 intended decisions or RP-21 feed. Source comment U-PR5 labels an authoritative cloud gate; it is not evidence that local ML Kit scanning was intentionally disabled by cloud preferences.

### Coverage checkpoint 3
- Fully read `ReceiptSideEffectPlanner.kt` and `ReceiptTransactionMatcher.kt`: action ordering, per-action keys, storage-mode gates, matching read/score/write windows, currency conversion, time windows, and document/status gating.
- Found a remaining full-row planner suggestion update at 438–445, outside the column-scoped service fix. This is related to FRESH-P3-004; investigating the newly live batch path rather than restating the old finding.
- Fully read `EffectiveCloudAiPolicy.kt` (shared P-08 surface, consumer semantics only). OCR source excerpts 1–281, 343–430, 746–764 establish local image recognition and retry semantics. Remaining PDF/decode implementation is not yet credited.
- Primary coordinator/repository inspection now includes remaining helpers, deprecated APIs, diagnostic writers, email conflict exits, disabled legacy paths, and candidate-read helpers. Full coverage ledger will explicitly distinguish any truncated-output gaps.
## CA-P-03-003 | PDF page-isolation fix drops failed-page evidence and reports incomplete statements as complete
- **Defect class:** 15 (Fix-regression); related 12 (Error handling), 13 (Unconsumed result).
- **Severity:** P1 (silent omission of statement transactions).
- **Evidence at pin:** `app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptOcrService.kt`, `processPdfWithOcr`, lines 662–689 drops failed-page content, 704–720 returns successfully unless every page failed, and reports `pagesProcessed = pagesToProcess` plus `failedPages`. `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt`, `processBankStatement`, lines 186–195 parses only surviving blocks; 433–454 recognizes partial PDFs only when `pagesProcessed < totalPages`; 827–869 computes status only from parsed-item ledger counts; 879–945 emits PROCESSING_COMPLETE and returns success. `OcrResult.failedPages` has zero production consumers (searched all `app/src/main`).
- **Impact path:** scan a three-page statement; page 2 exhausts OCR retries while pages 1/3 succeed → OCR returns pagesProcessed=3, totalPages=3, failedPages=1 → processor neither marks pdfPartial nor writes PDF_PARTIAL → valid surviving transactions produce a COMPLETED import and success UI; page-2 transactions never enter the review queue and have no failed-item ledger rows. Re-import also encounters the existing exact-hash guard, increasing the practical cost of the silent omission (existing retry limitation is FRESH-P3-005, not counted separately).
- **Caller trace:** `app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModel.kt`, `processStatement` 1051–1063 → `ReceiptLifecycleCoordinator.processBankStatement` 767–771 → processor → `ReceiptRepository.runStatementOcr` 685–686 → `ReceiptOcrService.processUri` / PDF OCR. UI displays an unqualified Imported N transactions summary. Camera/PDF route likewise loses failedPages in `ReceiptRepository.ProcessReceiptResult` (162–169; result construction 300–306).
- **Existing tests/guards:** `ReceiptOcrRetryIsolationTest.kt` covers retry/isolation helpers (test inventory inspected); there is no production failedPages consumer and no test reference to failedPages anywhere in `app/src/test`. Helper-level sibling-continuation evidence cannot establish end-to-end partial-import reporting. No tests or guards executed.
- **Cross-cell impact:** P-10 statement import; I-05 review UI; P-12 imported transaction completeness.
- **Old-ID cross-refs:** FRESH-P3-003 fixed the timeout/cancellation problem; this is a newly introduced downstream regression, not a repeat of that problem. `git show ecf68fd0 -- .../ReceiptOcrService.kt` proves the fix introduced partial success plus the failedPages field while consumers stayed unchanged. FRESH-P3-006 is about parsed SKIPPED items counted as failures; here missing pages never produce items at all.
## CA-P-03-004 | Text dedupe strips transaction amounts and treats different purchases as the same receipt
- **Defect class:** 4 (Idempotency / duplicates); related 11 (Data integrity).
- **Severity:** P1 (valid receipt intake suppressed).
- **Evidence at pin:** `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptDuplicateDetector.kt`, `computeTextFingerprint` 178–192 deletes numeric amount patterns and dates before hashing. `checkDuplicate` 112–125 immediately returns TEXT_FINGERPRINT without comparing the candidate's amount, currency, date, or semantic fingerprint. `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt`, `processReceiptInput` 465–480 creates both fingerprints but 541–565 accepts the text-only hit as definitive, deletes the new draft asset and returns the old receipt with inserted=false.
- **Impact path:** scan two legitimate receipts with the same textual template but different amounts (for example `CAFE\nLATTE 3.50\nTOTAL 3.50` and `CAFE\nLATTE 4.50\nTOTAL 4.50`) → both normalize to the same text before SHA-256 → the second receipt is classified as an existing duplicate despite a different total/semantic fingerprint → no new receipt, review or post-save actions; the second image is cleaned up. This is deterministic preprocessing collision, not a cryptographic hash collision. Distinct file bytes do not prevent it because this runs after exact-hash dedupe misses.
- **Caller trace:** camera `ReceiptScanViewModel` 290–299 and batch `ReceiptRepository.processBatch` 603–615 → `processReceiptInput` → detector → `ScannedReceiptDao.getByTextFingerprint` 177–178. The shared email coordinator also accepts detector duplicates at 983–991, but that consumer remains staged under D2 and is not used to raise severity.
- **Existing tests/guards:** targeted test searches found coordinator tests stubbing the detector/duplicate outcome, not a two-real-purchases discriminator test. `ReceiptPreOcrDedupeScenarioTest.kt` concerns exact-image dedupe. Ownership/atomic-insert guards cannot detect a lossy fingerprint's false-positive equality. No tests or guards executed.
- **Cross-cell impact:** E-03 shared parser consumers; P-11 staged email dedupe; I-05 scan/batch UI.
- **Old-ID cross-refs:** none found for this false-positive text normalization in the current registry, fresh findings baseline or remediation files. This is distinct from known missing unique indexes (`P3-CURRENT-008`, duplicate admission) and FRESH-P3-005 (statement resume blocked by exact hash).

## CA-P-03-005 | The receipt legal-path document names a permanently disabled creation API
- **Defect class:** 13 (Wiring / dead code — documented entry point drift).
- **Severity:** P3.
- **Evidence at pin:** `docs/architecture/LEGAL_PATHS.md`, Receipt Mutations, 133–136 instructs callers to use `ReceiptLifecycleCoordinator.createExpenseFromReceipt()` and `createExpense(DEFER)`. `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt`, `createExpenseFromReceipt`, 1519–1539 is `DeprecationLevel.ERROR` and unconditionally returns an error. The implemented entry is `createExpenseAndLinkReceipt`, 1552–1618, using `createExpenseDbOnlyV2` inside the transaction and running its actions afterwards.
- **Impact path:** developer/auditor follows the canonical operation path → reaches a compile-prohibited, permanently disabled API rather than the actual atomic operation. This is documentation drift, not a claim that the live scan UI uses the dead API.
- **Caller trace:** NONE-FOUND for the disabled API in production executable call sites. Actual production caller: `app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModel.kt` line 1241 invokes `createExpenseAndLinkReceipt`.
- **Existing tests/guards:** compiler ERROR deprecation prevents new direct callers, but does not validate Markdown legal paths. No tests or guards executed.
- **Cross-cell impact:** P-02 transaction path; I-04 legal-path guard documentation; I-05 receipt save.
- **Old-ID cross-refs:** none for this specific surviving canonical-document mismatch. S7-F583-001/S7-66F-001 in source identify the implemented atomic path; the old disabled API itself is not being reported as a runtime bug.
## Final coverage ledger

All source paths below are relative to `app/src/main/java/com/yourname/expensetracker/` unless stated otherwise. Twenty distinct source files were read end-to-end, using sequential excerpts for the large files; no further full-file expansion was performed after reaching that cap.

| # | Fully read file | Checks performed |
|---|---|---|
| 1 | `domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt` (1645 lines) | Entry validation, draft outcomes, every duplicate exit, policy resolution, receipt/review/event transaction, email source conflicts, deletion and atomic create/link, post-commit dispatch, cancellation/error exits, deprecated APIs. |
| 2 | `data/repository/ReceiptRepository.kt` (931) | Draft-only OCR/parse, raw sanitizer, failure/manual records, old writers, batch isolation and counters, statement delegation, disabled debug/match APIs, candidate queries. |
| 3 | `domain/receipt/lifecycle/ReceiptLinkService.kt` (552) | Join/legacy coherence, fresh receipt read, claim rollback, warranty/return/item propagation, category dispatch, unlink no-op and remaining-link handling. |
| 4 | `domain/receipt/lifecycle/ReceiptMatchLifecycleService.kt` (234) | All three mutation methods and every diagnostics-only event writer; state eligibility, atomicity, safe-code handling. |
| 5 | `service/receiptmatching/ReceiptMatchingWorker.kt` (329) | Guard request, checkpoints, every match outcome, local posting permission, delivery metrics, diagnostics isolation, scheduling/overlap. |
| 6 | `domain/receipt/lifecycle/BankStatementLifecycleProcessor.kt` (1061) | Consumer/usage audit: OCR/AI validation inputs, candidate ownership merge, currency unknown handling, import/run/item/review/event writes, dedupe placement, finalization and cancellation. |
| 7 | `data/database/dao/ScannedReceiptDao.kt` (278) | All SQL mutations/reads, claim predicate, suggestion predicate, fingerprint reads, retention purges. |
| 8 | `data/database/RoomDomainTransactionRunner.kt` | Actual Room transaction scope; no implicit write barrier. |
| 9 | `data/repository/ReceiptInsertResolver.kt` | IGNORE insert outcomes, conflict resolution ordering, typed result branches. |
| 10 | `data/database/dao/ReceiptExpenseLinkDao.kt` | IGNORE join insertion, unlink row count and receipt-wide deletion. |
| 11 | `data/database/entity/ReceiptEvent.kt` | Audit survival without receipt FK, indexed fields and payload columns. |
| 12 | `data/backup/DatabaseWriteBarrier.kt` | Check-only current-mode behavior; runWrite is also a check followed by execution, not a lock. |
| 13 | `domain/receipt/lifecycle/ReceiptSideEffectPlanner.kt` | All action factories/executors, ephemeral/restricted-mode handling, keys, matching, duplicate skips and full-row suggestion update. |
| 14 | `domain/receiptmatching/ReceiptTransactionMatcher.kt` | Candidate filters, weights/thresholds, conversion use, positive original amount and ownership filtering, date windows. |
| 15 | `domain/privacy/EffectiveCloudAiPolicy.kt` | Consumer gate mapping, default-denied implications, resolver inputs and image consent combination. |
| 16 | `domain/receipt/ReceiptOcrService.kt` (929) | Image/PDF local execution, file sizing and IO, decode/render resource cleanup, retry/isolation, failed-page fields, recognizer lifetime. |
| 17 | `domain/receipt/lifecycle/AssetCleanupCoordinator.kt` | Attempt claims, reference checks, cleanup outcomes/barrier/cancellation handling. |
| 18 | `domain/receipt/lifecycle/ReceiptLifecycleEventWriter.kt` | Context timestamp, transaction metadata, direct insert and error sanitizer boundary. |
| 19 | `data/database/dao/ReceiptEventDao.kt` | Event insert/read and retention-delete SQL. |
| 20 | `domain/receipt/lifecycle/ReceiptDuplicateDetector.kt` | All match precedence/early returns and fingerprint generation. |

Additional scoped reads/searches: `ReceiptScanViewModel.kt` 275–401 and creation caller 1241; `ReceiptMatchingViewModel.kt` 145–355; `ReviewViewModel.kt` batch/statement/bulk-clear call sites; `ReviewScreen.kt` clear action; `ScannedReceipt.kt` identity/indices/fields; receipt-link, expense, pending-review and warranty FK declarations; statement run/item DAO method SQL and item uniqueness; privacy settings/defaults and repository load-state excerpts. Tests examined by method inventory plus targeted bodies: `ReceiptLifecycleCoordinatorTest`, `ReceiptLinkServiceColumnScopeTest`, `ReceiptMatchLifecycleServiceTest`, `ReceiptMatchingWorkerTest`, `ReceiptSideEffectPlannerClaimTest`, `ReceiptOcrRetryIsolationTest`. Test paths are under `app/src/test/java/com/yourname/expensetracker/`.

Architecture scope: the P-03 matrix row; requested five LEGAL_PATHS headings; segment 4/5/14/20/28/38 entries and relevant worker entries; only named engine rows and WorkerExecutionGuard chain; receipt entity/DAO inventory excerpts. No whole engine map, backend map or import graph was loaded.

## All fifteen defect classes — disposition

| Class | Adversarial check and outcome |
|---|---|
| 1 Legal path | Followed scan/batch and matching UI to coordinators/services and DAOs. Disabled creation APIs have no active caller; actual create/link is transactional. Bulk clear is tracked/authorized maintenance in GR-08n2, excluded. Canonical-document contradiction is CA-P-03-005. |
| 2 Barrier | Inspected actual check-only barrier and transaction runner, explicit method checks, worker guard/checkpoints and statement recovery branches. Checks are not locks; no proof of restore-wide exclusion is claimed. Shared restore/drain correctness remains I-02/P-07, not assigned a speculative P0 here. |
| 3 Atomicity / TOCTOU | Receipt/event/review inserts and link/claim/event rollbacks traced. The suggestion eligibility window yields CA-P-03-001. Statement duplicate decisions are outside later write transactions despite the P3-BLOCKER-H2 comment; tracked-marker follow-up below, not claimed safe. |
| 4 Idempotency | Compared application prechecks, insert IGNORE resolver, actual receipt index declarations and match claim. CA-P-03-004 proves false-positive text equality. Missing receipt unique indexes already carry P3-CURRENT-008 and are not new. |
| 5 Cancellation | Inspected every primary catch, batch supervisor/await behavior, bounded statement cancellation cleanup, OCR timeout-before-CE handling and cleanup paths. FRESH-P3-003 timeout handling changed as intended; consumer regression is CA-P-03-003. No general cancellation-safety certification. |
| 6 Side-effect timing | Save coordinator runs receipt batches after its transaction, create/link collects transaction actions; link category dispatch nested inside an outer coordinator transaction is already CA-P-02-002 and excluded. Planner action keys do not alone prove durable exactly-once execution. |
| 7 Money / currency | Statement candidateId ownership and typed currency-unknown routing inspected at consumer; matching uses conversion and original positive purchase amounts, excludes not-mine. Cross-currency provider correctness remains E-01. No new arithmetic finding promoted without that proof. |
| 8 Time | TimestampPolicy/TimeProvider call sites, statement date limits and matching windows examined; duplicate semantic fingerprint uses local start-of-day plus locale-sensitive formatting. Locale/timezone counterexample coverage remains limited; no new time finding asserted. |
| 9 Privacy | Raw/structured sanitization, ephemeral data use, service column-scoped updates, planner persistence, event payloads and defaults examined. Local-vs-cloud boundary mistake is CA-P-03-002. FRESH-P3-004/-007/-008 are not restated as new. No claim that all raw-data/logging paths are clean. |
| 10 Worker hygiene | Guarded entry, notification-independent core work, permission-local try/catch, actual DELIVERED-only metric and CAS argument checked. Mutable suggestions remain exposed (CA-P-03-001). Guard implementation is a P-09/I-01 shared surface; not fully re-audited. |
| 11 Data integrity | Join/legacy/derived propagation and durable event survival traced; false receipt suppression (004), reopened matching state (001), and missing-page import omission (003) documented. No schema migration executed or schema-version completeness asserted. |
| 12 Error handling | Typed duplicate versus success/failure branches, run finalization, item errors, no-transactions path, local-OCR gate and partial-PDF success checked. Findings 002/003; known statement cancel/skip behavior 005/006 excluded. |
| 13 Wiring / dead code | Production call-site searches distinguish scan/batch from staged email; failedPages has no consumer (003); disabled documented API drift (005). D2's staged email is intentional, not a missing-wiring finding. |
| 14 Test correctness | Read actual assertions for column-scoped suggestion and isolation helpers; coordinator mocks the effective-cloud resolver; worker claim tests do not cover stale suggestions. Gaps are recorded under the affected findings, not inflated into duplicate standalone findings. Existing RP-21 mock/Room-hang debt is excluded. |
| 15 Fix-regression | Inspected wave diffs for 7c825df6, b25dc3d8, 9c8d3f4a and ecf68fd0. OCR partial-success field introduced by ecf68fd0 lacks downstream handling (003). Remaining old planner full-row write is disclosed below, not silently treated as fixed. |

## Known debt, counter-evidence and limits

- FRESH-P3-001: camera/batch planning and coordinator dispatch exist now; not re-reported.
- FRESH-P3-002/-010: processor merges by validated candidateId and uses explicit source-currency/unknown policy; validator internals were not fully audited. No blanket closure claim.
- FRESH-P3-003: retry and sibling isolation are implemented; only the newly introduced incomplete-PDF reporting defect is counted.
- FRESH-P3-004: **service/link paths** are column-scoped, but `ReceiptSideEffectPlanner.processMatchResult` 434–445 still writes a captured full row. This is a known-ID remainder; the RP-12 completeness claim must not be accepted from the service test alone. A stale planner write can also affect the state race in CA-P-03-001. No separate new finding counted for the old full-row issue.
- FRESH-P3-005/-006: exact-hash blocks cancelled-statement retry and skipped ledger rows still make FAILED. RP-13 explicitly leaves these pending; excluded.
- FRESH-P3-007: coordinator structured-data shaping and planner mode checks are present. Downstream categorization/provider persistence was not fully re-audited; no privacy closure claim.
- FRESH-P3-008: typed saved-path handoff/reference-counted cleanup exist, but the ordinary coordinator exception catch does not invoke cleanup and OCR cancellation can occur before the coordinator receives the path. These are old asset-cleanup scope, excluded from new IDs; no full closure claim.
- FRESH-P3-009: worker and planner AUTO_MATCH pass requireUnmatchedClaim=true. UI AUTO_MATCH calls shown in the coverage excerpts do not, so do not infer every caller shares the worker's CAS contract. No duplicate of the tracked auto-match issue promoted.
- P3-CURRENT-008 missing fingerprint uniqueness, P3-0D5-09 / MIT-DB-08N bulk deletion, P3-BLOCKER-H2 statement duplicate-window concerns, and CA-P-02-002 nested dispatch are recorded as exclusions/cross-cell follow-up, not counted again.
- The twentieth full-file read completed the bounded source budget. All six explicitly named P-03 primary files were read; this is **partial campaign-cell coverage beyond those primaries**. Not fully audited: pure ReceiptParser/BankStatementParser algorithms; statement AI validator/providers; HybridRouter/CloudPayloadPolicy internals; ReceiptAssetStore/path permissions and input validator internals; downstream warranty/price-protection/item-categorization implementations; full PendingReview/Expense DAO logic and schema/migration snapshots; durable post-commit runner/replay internals; full UI screens and test suites. Those limits prevent an exhaustive cell-completion or no-other-defects claim.
- Some broad search output was truncated; only visible output and subsequent focused reads were used as evidence. Search misses for guessed filenames were corrected using file discovery; they are not counted as coverage.
- No build, test, lint or guard was run. Findings are static discovery candidates, not independent Phase-2 verification results.

## Close-out

- Findings: **5** — **4 P1**, **1 P3** (CA-P-03-001 through CA-P-03-005).
- Status: primary-file pass finished; expanded cell coverage **PARTIAL**, with explicit limits above. Independent adversarial verification pending.
- Production code/config/scripts changed: none. Only this report and one appended journal record were written by this session.
- Pin rechecked: full HEAD equals `37601232b9778170c57a656a245b199ab6d7d965`; `git diff --stat 37601232 -- app config scripts` empty, including working-tree source state.

## Closing provenance

- Date: 2026-09-21.
- Actor/session: astra-cell-auditor — **direct astra session**, as explicitly requested by the cell preamble.
- Agents invoked: none; direct-session audit, no delegated or independent review claimed.
- Pinned commit: `37601232b9778170c57a656a245b199ab6d7d965`.
- Cell: P-03; mode AUDIT, static only.
- Closed at: 2026-09-21T19:14:43Z

