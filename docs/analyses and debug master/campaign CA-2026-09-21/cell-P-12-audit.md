# P-12 static audit — CA-2026-09-21

Date: 2026-09-22
Pinned commit: 37601232b9778170c57a656a245b199ab6d7d965
Auditor: direct astra session
Cell: P-12 — Export, import, accounting
Mode: AUDIT; static only; no production edits; no builds or tests.
Status: Static cell discovery finished within the 20-full-file budget; three findings awaiting independent Phase 2 verification. This is not a campaign acceptance gate.

## Provenance and scope
- `git rev-parse --short HEAD`: `37601232`.
- `git diff --stat 37601232..HEAD -- app config scripts`: empty.
- Working tree status: documentation and agent configuration drift only; accepted.
- Read governing spec §2 and §4. All 15 defect classes apply.
- Read COVERAGE_MATRIX P-12 row; LEGAL_PATHS sections Expense Mutations, Backup / Restore, Accounting Export, Money / Currency, Business Reports / Tax, Import; CODEBASE_SEGMENTS segment 18; relevant inventory and engine-row extracts.
- Known debt excluded: FRESH-P12-001..010; P12-P0-01; P12-P1-02..08. D3 explicitly keeps CSV import debug-only; D4 approves dead ImportCoordinator removal. Neither decision is a new defect.
- Source-reading budget: at most 20 full production/test files; larger shared surfaces inspected by scoped ranges. Coverage records below distinguish full reads and extracts.

## Coverage ledger (append-only during discovery)

- FULL 01 ExportDataRepository.kt (214 lines): read barriers at all DB-facing methods, source-link query, filename sanitation, encryption delegation and stale-temp sweep. Snapshot weakness explicitly accepted/tracked; no new finding assigned.
- FULL 02 DeterministicExpenseExportPager.kt (134): keyset advancement, maxRows termination, count separation; snapshot limitation excluded under RP-19 contract/P12-P1-04.
- FULL 03 AccountingExportPolicy.kt (140): all validation methods; 10,000-row sampling is known P12-P1-02, not new.
- FULL 04 CsvCellSanitizer.kt (93): complete CSV/IIF encoding, negative numeric exception, formula prefixes and delimiters inspected.
- FULL 05 ImportCoordinator.kt (97): format detection, result conversion, CSV error propagation; production caller search finds no external consumer (already known FRESH-P12-010/D4 lane).
- FULL 06 CsvExpenseImporter.kt (388): BOM/header/comment handling, RFC reader call, per-row validation, category creation, lifecycle creation/results and exception handling.
- FULL 07 JsonExpenseImporter.kt (202): both schema versions, amount/type/date parsing, category writes, idempotency and cancellation. External production entry NONE-FOUND (only dead ImportCoordinator); latent behavior will not be assigned live severity.
- FULL 08 AccountingExporters.kt (194): every QuickBooks/Xero/FreshBooks output field inspected; text cells use shared sanitizer; numeric amount formatting and revised FX formatting traced.
- FULL 09 Rfc4180CsvReader.kt (84): character-by-character parser, CR/LF, quoted multiline, doubled quotes, EOF malformed record.
- FULL 10 ExpenseExportMapper.kt (99): amount/effective/base amounts, nonfinite handling, source-link metadata and payment-method mapping.
- FULL 11 AccountingExportRepository.kt (236): privacy/read gates, policy, materialization, temp/rename/copy path, PDF call, error handling. No production caller of exportExpenses found; dormant hazards not treated as live bugs.
- Baseline extracts: RP-19-import-export.md; full RP-19-ROUNDTRIP-MATRIX.md normative contract; STILL_OPEN_ISSUES P12 partials; RP-00 D1-D14; RP-21 handoff export/import matches. Unsupported ownership/transfer/business metadata and non-snapshot semantics are recorded intended behavior.

## New findings

### CA-P-12-001 | Debug CSV import omits mandatory provenance, so every otherwise valid row is rejected | class 12 | P1
ID: CA-P-12-001
Title: Debug CSV import omits mandatory provenance, so every otherwise valid row is rejected
Defect class: 12 (Error handling / integration contract); related 14 (mocked lifecycle hides rejection)
Severity: P1 (entire intentional debug import feature unusable; no release-import claim)
Evidence (all at pin):
- `app/src/main/java/com/yourname/expensetracker/ui/screens/debug/DebugScreen.kt`, CSV import callback, 1433-1467: passes only content to `CsvExpenseImporter.importFromContent`.
- `app/src/main/java/com/yourname/expensetracker/util/CsvExpenseImporter.kt`, `parseAndImportRecord`, 225-251: creates/looks up category, sets `source = CSV_IMPORT`, passes only `fileImportRunId`; omits `csvImportBatchId`, `csvRowNumber`, and explicit `sourceLinks`.
- `app/src/main/java/com/yourname/expensetracker/domain/provenance/CreateExpenseSourceLinkRequirements.kt`, `missingRequirements`, 13-14 and 29-32: CSV_IMPORT requires both batch ID and row number unless explicit links exist. A fileImportRunId is not the required pair.
- `app/src/main/java/com/yourname/expensetracker/domain/transaction/CreateExpenseRequest.kt`, defaults, 126-140: fallback defaults to NONE (not legacy backfill).
- `app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt`, `createExpenseMutation`, 410-439; `createExpense`, 824-825: real lifecycle rejects missing provenance before expense insertion.
Impact path: debug file picker -> CSV reader -> optional category insertion -> lifecycle provenance rejection -> imported=0 and errors=N for every otherwise valid CSV row. New categories can remain even though no expenses import. This is failure to create new import provenance, not the documented decision to omit restoration of old source links.
Caller trace: LIVE DEBUG — DebugScreen.kt:1433-1467 -> DebugViewModel.csvExpenseImporter -> CsvExpenseImporter -> TransactionLifecycleCoordinator.createExpenseStandaloneV2. D3 debug-only scope respected.
Existing tests/guards: `CsvExpenseImporterTest` mocks TransactionLifecycleCoordinator; `ExportImportRoundtripTest.kt:94-101` returns Created from a mocked coordinator, so serialization/request tests cannot expose the production rejection. Static DAO ownership guards enforce the coordinator call, not its mandatory request fields. No tests executed.
Cross-cell impact: P-02 lifecycle and provenance contract; P-12 owns the missing caller fields. JSON's default CSV_IMPORT request has analogous missing fields but has NONE-FOUND external production caller; not counted as a separate live finding.
Old-ID cross-refs: none. P12-P0-01/D3 concerns release availability; P12-P1-06/07 and RP-19's unsupported sourceLinks roundtrip concern old-link restoration, not rejection of every debug import.
Disposition: discovery finding; independent Phase 2 verification pending.

### CA-P-12-002 | Changing selected format relabels a previously generated file without regenerating it | class 11 | P2
ID: CA-P-12-002
Title: Changing selected format relabels a previously generated file without regenerating it
Defect class: 11 (Data integrity / serialized format contract)
Severity: P2
Evidence (all at pin):
- `app/src/main/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsViewModel.kt`, `selectFormat`, 136-138: updates only selectedFormat; retains exportSuccess/exportFilePath and preview from the previous export.
- Same file, `generateExport`, 265-267 and 415-423: snapshots format for writing, but the completed result stores no corresponding format field.
- `app/src/main/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsScreen.kt`, format controls/result actions, 175-176, 204-239 and 241-265: an old result stays actionable; Save derives its extension and Share derives its MIME type from the current selection while reading the old exportFilePath. The save callback at 58-74 copies those old bytes unchanged.
Impact path: generate JSON successfully -> select QuickBooks without generating again -> Save proposes expenses_<timestamp>.iif but copies JSON bytes. Generate CSV -> select JSON -> Share sends the existing CSV as application/json. Recipient importers receive a mismatched format and reject or misinterpret it. No concurrent activity is needed; changing selection during an export has the same mismatch.
Caller trace: LIVE RELEASE/DEBUG — MainActivity.kt:858-860 -> ExportOptionsScreen format option -> selectFormat -> existing ExportResultCard Save/Share actions.
Existing tests/guards: `ExportOptionsViewModelTest` selects formats before generation and tests each output; inspected test names/call sites contain no completed-result format-switch case. File/schema escaping tests do not cover save/share metadata. No tests executed.
Cross-cell impact: exported files consumed by external accounting applications; UI-to-export boundary within P-12.
Old-ID cross-refs: none. Not snapshot/count debt (P12-P1-04), and not overlapping export jobs (FRESH-P12-006).
Disposition: discovery finding; independent Phase 2 verification pending.

### CA-P-12-003 | A malformed CSV header is silently classified as a successful empty import | class 12 | P2
ID: CA-P-12-003
Title: A malformed CSV header is silently classified as a successful empty import
Defect class: 12 (Error handling); related 15 (RP-19 malformed-record handling regression)
Severity: P2
Evidence (all at pin):
- `app/src/main/java/com/yourname/expensetracker/util/Rfc4180CsvReader.kt`, `parse`, 53-80: a missing closing quote absorbs following physical lines and produces one malformed record at EOF.
- `app/src/main/java/com/yourname/expensetracker/util/CsvExpenseImporter.kt`, `importFromContent`, 108-115: header search skips malformed records; if none remains, returns Success(0,0,0,emptyList()). Error conversion at 136-150 only applies after a valid header was found.
- `app/src/main/java/com/yourname/expensetracker/ui/screens/debug/DebugScreen.kt`, CSV import result handling, 1451-1457: renders the success result with zero imported and zero errors.
Impact path: select a damaged/truncated CSV beginning with `"date,amount,merchant` (no closing quote), optionally followed by otherwise valid physical data lines -> parser marks one malformed record -> importer discards it during header discovery -> user sees successful import with zero errors. A parse error is indistinguishable from an intentionally empty file. This path occurs before the provenance problem in CA-P-12-001 and is independently observable.
Caller trace: LIVE DEBUG — DebugScreen CSV picker -> CsvExpenseImporter.importFromContent -> Rfc4180CsvReader.parse -> Success toast. Dead ImportCoordinator would also mark this result success=true.
Existing tests/guards: `CsvImportRfc4180Test.kt:122-148` covers malformed data rows AFTER a valid header; `CsvExpenseImporterTest.kt:172-178` covers genuinely empty content. Neither exercises a malformed first/header record. No tests executed.
Cross-cell impact: none beyond P-12 import reporting.
Old-ID cross-refs: none. FRESH-P12-001 is the prior multiline row-splitting bug; this new header-error loss is in RP-19's replacement logic (commit 70597318), not a restatement of the original defect.
Disposition: discovery finding; independent Phase 2 verification pending.

## Additional coverage ledger
- FULL 12 ExportOptionsViewModel.kt (987 lines, read sequentially in three ranges): all initialization, input changes, gates, count/validation, temp-file lifecycle, encryption usage, stream/header/footer/legacy writers, preview, cancellation and error paths.
- FULL 13 ExportJobSerializer.kt (62): predecessor cancellation/join, active pointer and cancelActive inspected.
- FULL 14 FxRateFormatter.kt (36): BigDecimal HALF_UP, six-place cap, finite guard. Six-decimal precision is explicit RP-19 contract, not newly reported as a defect.
- FULL 15 AccountantReportPdfExporter.kt (338): purchase filtering, currency buckets, effective totals, pagination, wrapping, finally-close and deductible helper. Only caller is dormant AccountingExportRepository; no live PDF UI caller found.
- FULL 16 CategoryDao.kt (218): import-facing lookup/IGNORE insert and atomic alternative; remainder read for DAO context. Other category operations belong to persistence/core cells.
- FULL 17 ExportPrivacyGate.kt (86): every capability decision, encrypted setting, raw/debug consent and release denial. Ordinary expense export allowed is known P12-P1-05.
- FULL 18 BusinessExpenseReportGenerator.kt (318; truncated 70-85 output re-read separately): purchase filtering, raw totals/warning, mileage calculations, CSV cells, IO and report generation. Production consumer search finds no caller; no runtime tax-report finding asserted.
- FULL 19 DatabaseWriteBarrier.kt (40): NORMAL-only admission and runWrite; call-site gate is not a lease/lock. Broad race concern is existing U-BARRIER-02, not a new finding.
- FULL 20 ExportJobSerializerTest.kt (111): all four tests read; two-job immediate cleanup, sequential jobs and cancellation. No execution.
- Full-file budget reached: 20 distinct production/test files. All further shared-surface/test reads were bounded extracts.
- EXTRACT TransactionLifecycleCoordinator.kt: complete create mutation 328-813; legacy/V2 create boundaries 818-899; validate delegation 2412-2417. Traced validation, provenance, FX, transactional dedupe, insertAtomic, CREATED and SOURCE_LINKED events, return, and post-commit runner. Other mutation methods not audited for P-12.
- EXTRACT CreateExpenseRequest.kt: 108-140 defaults; CreateExpenseSourceLinkRequirements.kt: missingRequirements/source cases; CreateExpenseSourceLinkMapper.kt: 116-143 import payload mapping.
- EXTRACT ExpenseRepository.kt: export keyset/count wrappers 800-809; ExpenseDao.kt: 83-101 and 1385-1463; EntitySourceLinkDao.kt: 40-50. Count and page share [start,end) and isNotMine=0 filters; provenance query is read-only. No export DB mutation/event requirement exists.
- EXTRACT TransactionSideEffectPlanner.kt: planCreated 34-50 (budget, anomaly, merchant stats, recurring match, source-trust learning). PostCommitActionRunnerImpl.kt: 14-70 (started, execute, cancellation propagation, bounded failure code). P-12 callers delegate; shared action implementations remain their owners' scope.
- EXTRACT DebugScreen.kt 1433-1471, DebugViewModel importer injection; ExportOptionsScreen.kt 58-108, 175-265; MainActivity.kt export route 858-860. Traced actual import and export entry points and external file/clipboard/share effects.
- EXTRACT AppStartupCoordinator.kt 623-640: Timber DebugTree is debug-only; release logging leak claims were not inferred from Timber calls alone.
- TEST EXTRACTS: CsvExpenseImporterTest (entry-barrier, valid/invalid rows, category behavior); CsvImportRfc4180Test (multiline/CRLF/quotes/BOM, malformed row, locales); ExportImportRoundtripTest (request capture mocks); CsvExportImportRoundtripTest scenario (DB insert/query, not importer integration); CsvExportImportRoundtripGoldenTest (sanitizer only); ExportOptionsViewModelTest (formats, denial, policy, count mismatch, FX, cancellation, encryption cleanup); CsvCellSanitizerNegativeAmountTest discovered for sanitizer contract.
- GUARD/BASELINE EXTRACTS: db_ownership_policy.yml 7139-7191 authorizes exact importer CategoryDao helper writes (MIT-DB-08P1/P2); these are not called forbidden DAO bypasses. RP-19 commit 70597318 diff/stat inspected for parser and serializer changes. RP-21 feed entries and test-diagnosis handoff contain no newly reported P-12 issue above.
- EXTRACT ExportTransaction.kt 19-55 and SourceLinkExportRef.kt 30-43: effective amount, rate alias and metadata transport. MainActivity.kt 875-876 confirms debug navigation gate.

## All 15 defect classes — assessment

| Class | Evidence checked and outcome |
|---|---|
| 1 Legal path | DebugScreen -> CSV importer -> deprecated createExpense delegate -> standalone V2 -> insertAtomic. No direct expense insertion in importers. Category writes are exact-policy-authorized. Missing required lifecycle provenance is finding -001. Dead facade routing is already tracked, not new. |
| 2 Barrier | ExportDataRepository checks all category/count/page/source-link reads; ViewModel and accounting repository gate entry. Importers check write admission; coordinator gates the expense transaction again. Category entry-only checks and barrier not being a lock are existing U-BARRIER-02 design debt, not claimed fixed or newly reported. |
| 3 Atomicity / TOCTOU | Coordinator's dedupe, insertAtomic, CREATED and source links are inside one transaction. Export pages/count/categories are separate reads: acknowledged RP-19/P12-P1-04 non-snapshot contract. No new atomic-snapshot promise inferred. |
| 4 Idempotency / duplicates | CSV uses STANDARD lifecycle dedupe with unique insert fallback; JSON supplies an idempotencyKey but STANDARD mode does not use strict external identity. JSON has no external caller. CSV's earlier -001 rejection prevents a live duplicate-import claim. |
| 5 Cancellation | Importers rethrow CancellationException; stream checks ensureActive per page, temp cleanup is finally-scoped. Serializer join chain and finalization boundary examined; remaining concerns below are not counted as new independent findings. |
| 6 Side-effect timing | Successful lifecycle creates plan after transaction and run only after commit; per-row import is not a whole-file transaction. Export writes hidden temps before publishing success; Save/Share reuse result path. Result metadata mismatch is -002. |
| 7 Money / currency | Full mapper and format writers inspected: original/effective fields distinguished, finite guards, explicit rate format; accounting sample limit known P12-P1-02. PDF groups by currency. Deliberate ownership lossiness and six-place FX contract excluded. Business report raw mixed sums have no production consumer found; no live money-loss claim. |
| 8 Time correctness | Device-zone date rendering, CSV start-of-day reconstruction, JSON date/timestamp fallback, half-open DAO range, injected filenames/time checked. Timezone/date-only loss is documented P12-CURRENT-025 / P12-NEW-08, not newly reported. No timezone runtime experiments performed. |
| 9 Privacy | CSV/IIF text cells use sanitizer including formula prefixes; JSON escaping covers control chars; raw/export capabilities and fail-closed encryption usage examined. Normal plaintext allowed is known P12-P1-05; source links intentionally exported. Encryption has no UI caller requesting true at the pin; crypto internals belong to P-07. No speculative release Timber leak claimed. |
| 10 Worker hygiene | No P-12 CoroutineWorker on the traced path. Export/import run in UI coroutine scope/IO; restore/cleanup worker internals are P-07/P-09 ownership. No worker pass implied. |
| 11 Data integrity | All format mappings, CSV column order, JSON page commas, PDF grouping and downstream file actions inspected. Save/share metadata can describe the wrong bytes (-002). Provenance omission rejects debug import (-001); malformed header disappears (-003). |
| 12 Error handling | Per-row and aggregate result branches, parse fallback, policy denial, encryption exceptions, malformed CSV reviewed. Findings -001 and -003. Known facade error-detail/wiring debt not restated. |
| 13 Wiring / dead code | Whole production source caller searches: CSV importer reached by DebugScreen; ImportCoordinator, JSON importer chain, AccountingExportRepository and business/PDF report chain lack an external production consumer. Zero-caller defects not elevated to hypothetical runtime severity. Encrypted export branch called only by tests/API at pin; ordinary export is live. |
| 14 Test correctness | Mocked coordinator hides -001; malformed-data tests miss malformed header (-003); format tests generate after selection and miss selection after generation (-002). DB scenario called roundtrip tests insert/query, not actual exporter->importer. Test weaknesses recorded with related findings, not double-counted. |
| 15 Fix regression | Read RP-19 commit 70597318 parser diff and serializer introduction. New malformed-header success in -003 is replacement-path behavior. Other tracked issues, such as snapshot limits and remaining cancellation hazards, are excluded from new count. |

## Exclusions and remaining uncertainty

- Known issues are not repeated as new findings: all FRESH-P12-001..010 and P12-P0-01/P12-P1-02..08 were reconciled against the registry and RP-19 contract. Multiline parsing, precise-enough rate formatting under the accepted six-decimal policy, temp naming/sweep and error propagation have changed since the old audit; no blanket claim that every old issue is fixed.
- FRESH-P12-006 residual candidate: serializer B can wait for slow cancelled A; C cancels/joins B, so the cancelled waiter can terminate before A. `cancelActive` also clears the only handle before cleanup ends. Unique temps narrow the original corruption mechanism. This is overlapping tracked cancellation debt, with narrow UI reachability, so **not a new finding or asserted verified regression**. Existing tests use immediate cleanup or completed sequential jobs. Phase 3 may reconcile it under the old ID.
- Finalization cancellation/copy-failure cleanup, unchanged category entry-check races, and retained final exports are not promoted as separate findings without resolving overlap with FRESH-P12-006/009 and U-BARRIER-02. No assertion that they are safe.
- Normative D3 debug-only scope, RP-19's lossy ownership/business/source-links contract, default currency behavior and non-snapshot export are accepted as specified. The new -001 concerns creating mandatory provenance for this import operation, not restoring old exported provenance.
- Read scope is bounded: 20 full production/test files, selected shared-service/DAO/UI/test extracts, and scoped caller/pattern searches. MoneyAggregate/Builder, CurrencyConverter and TaxEstimator internals were not re-audited; E-01/E-04 own them. BackupEncryptionService was inspected only through export usage; P-07 owns its implementation. Full restore locking, category merge operations, event persistence/retention, and individual side-effect handlers were not independently audited here.
- Tests/guards were **NOT RUN**. No static guard script, Gradle task, build, runtime experiment or external service was invoked. All findings are static discovery claims and require independent Phase 2 verification.

## Final assessment

Three new findings: **P0: 0; P1: 1; P2: 2; P3: 0**.
- CA-P-12-001 — missing required import provenance (P1).
- CA-P-12-002 — selected format and completed-file metadata diverge (P2).
- CA-P-12-003 — malformed CSV header reported as successful empty import (P2).

Cell discovery finished for the listed P-12 files and call chains within the requested budget. Shared-owner and runtime limitations are explicitly listed above; this does not claim whole-codebase or runtime completeness.

## Closing provenance

- Session: **direct astra session**, cell **P-12**, campaign **CA-2026-09-21**.
- Agents invoked: **none**, as requested for the direct auditor session; discovery and report authored directly. No independent reviewer invoked; Phase 2 remains pending.
- Date: 2026-09-22 (provided session date). Tool UTC timestamp: 2026-09-21 21:27:33 UTC.
- Pin rechecked at end: `37601232b9778170c57a656a245b199ab6d7d965`; both committed comparison `git diff --stat 37601232..HEAD -- app config scripts` and working-tree production diff are empty.
- Files written: this report and one appended JOURNAL.md line only, both within the campaign folder.
