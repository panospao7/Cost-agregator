## Technical Plan

### Scope
- In: the `HIGH` rows under `### B.7: Export/Backup Pipeline` in `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`, specifically export-row semantic loss (`currency`, `transactionType`, source account), unsafe mixed-currency accounting output, fake PDF generation for `ACCOUNTANT_REPORT_PDF`, and shared paging/policy drift between repository and UI accounting export flows.
- Out: all B.7 `MEDIUM/LOW` rows, all backup/import contract work (`DatabaseBackupRepository*`, `DatabaseOperationResults.kt`, `DebugViewModel.kt` restart semantics), generic CSV currency-column work, `includeReceipts`, raw-money precision cleanup, formula-injection follow-ups, and any Room/entity/schema/migration changes.
- Assumptions / unknowns:
  - `B.4` must be locally committed before B.7 execution starts; this plan is ready now but execution is still Phase-B-gated by the playbook.
  - No approved FX-conversion pipeline is available for accounting exports. The safe fix is validation/per-currency grouping, not silent conversion.
  - The registry still carries a stale truncation narrative from older code (`exportExpenses()` now pages via `fetchAllForExport`). Treat that row as a live-audit/documentation-drift item while still landing one shared paging source so repository/UI export behavior cannot drift again.
  - `MASTER-ISSUE-REGISTRY.md` lists Batch 44 under B.7, but the current Batch 44 verification report does not expose a matching export/backup row. Do not invent Batch 44 documentation edits unless live review finds a real B.7 citation there.

### Files
- create: `app/src/main/java/com/yourname/expensetracker/domain/export/ExpenseExportMapper.kt`
- create: `app/src/main/java/com/yourname/expensetracker/data/repository/DeterministicExpenseExportPager.kt`
- create: `app/src/main/java/com/yourname/expensetracker/domain/export/AccountingExportPolicy.kt`
- create: `app/src/main/java/com/yourname/expensetracker/domain/export/AccountantReportPdfExporter.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/export/ExportTransaction.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/domain/export/AccountingExporters.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/repository/AccountingExportRepository.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/data/repository/ExportDataRepository.kt`
- modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsViewModel.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/domain/export/CsvEscapingTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/data/repository/AccountingExportRepositoryTest.kt`
- modify: `app/src/test/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsViewModelTest.kt`
- modify: `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-33.md`
- modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-39.md`
- create: `docs/reviews/REVIEW-B7-export-backup-pipeline.md`

### 1. Objective & Blast Radius
- **Core issue:** the export lane still loses accounting semantics before formatting, can emit mathematically meaningless mixed-currency artifacts, produces a fake PDF artifact for `ACCOUNTANT_REPORT_PDF`, and still splits overlapping accounting export logic between repository and UI paths. There are no open B.7 `CRITICAL` rows; backup-side open work is medium-only and stays out of scope.
- **Blast radius:**
  - `domain/export/` DTOs, policy helpers, exporter implementations, and PDF generation
  - `data/repository/AccountingExportRepository.kt`
  - `data/repository/ExportDataRepository.kt`
  - `ui/screens/export/ExportOptionsViewModel.kt` for overlapping accounting formats (`xero`, `quickbooks`, `freshbooks`) only
  - export-focused unit tests and documentation closeout files

> [!WARNING]
> - Do **not** touch B.7 medium/low rows in this plan.
> - Do **not** change Room entities, DAO SQL, migrations, schema versions, or column names.
> - Do **not** widen this work into generic CSV/JSON schema changes, `includeReceipts`, or backup import/restart contracts.
> - Do **not** auto-convert currencies or silently relabel mixed-currency output as `EUR`/`€`.
> - Do **not** add a new UI export option for the accountant PDF; fix the repository artifact only.

### 2. The Single Source of Truth
- **Canonical export row contract:** `ExportTransaction` is the only allowed accounting-export DTO. It must carry ownership-adjusted amount, real currency, real transaction type, and the narrowest funding/source-account metadata needed by downstream formatters.
- **Canonical mapping rule:** one shared mapper converts `Expense -> ExportTransaction` using `effectiveAmount`, preserving `currency`, `transactionType`, and a deterministic source-account label derived from `paymentMethod`. No local `Expense.toExportTransaction()` helpers may remain in repository/UI export files.
- **Canonical paging rule:** repository and UI accounting export flows must iterate data through one deterministic paged export source. No bespoke loops with separate page sizes or one-off fetch logic are allowed in overlapping accounting paths.
- **Canonical safety rule:** Xero, QuickBooks, and FreshBooks exports are valid only for export-safe datasets. Mixed-currency datasets and unsupported non-`PURCHASE` rows must fail fast with an actionable error instead of being converted, flattened, or silently dropped.
- **Canonical QuickBooks rule:** `TRNS.ACCNT` represents the funding/source account; `SPL.ACCNT` represents the expense category. They must never both point at the category account.
- **Canonical artifact rule:** `ExportFormat.ACCOUNTANT_REPORT_PDF` must emit actual PDF bytes with a `.pdf` filename. Free-text accountant reporting may group totals by currency, but it may never aggregate different currencies into one hardcoded-euro total.

> [!WARNING]
> - Use `effectiveAmount`, not raw `amount`, for accounting export math.
> - Prefer explicit validation failure over silent filtering whenever a dataset cannot be represented safely by an accounting format.
> - Keep generic CSV/JSON exports unchanged unless a compile-neighbor fix is strictly required.

### 3. File-by-File Execution Checklist (micro-batches)

#### Batch 1 — Canonical accounting export row contract
- Files:
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/export/ExportTransaction.kt`
  - create: `app/src/main/java/com/yourname/expensetracker/domain/export/ExpenseExportMapper.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/data/repository/AccountingExportRepository.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsViewModel.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/domain/export/CsvEscapingTest.kt`
- Checklist:
  - [ ] Extend `ExportTransaction` with `currency`, `transactionType`, and the minimum funding/source-account metadata required for QuickBooks `TRNS` rows.
  - [ ] Create one shared mapper in `ExpenseExportMapper.kt` that uses `effectiveAmount`, preserves actual `currency`/`transactionType`, and derives source-account labels from `paymentMethod`.
  - [ ] Remove duplicate local `Expense.toExportTransaction()` helpers from `AccountingExportRepository.kt` and `ExportOptionsViewModel.kt`; both accounting export paths must call the shared mapper.
  - [ ] Keep generic CSV/JSON export code on raw `Expense` objects for now; do **not** widen into medium-scope header/schema cleanup.
  - [ ] Update `CsvEscapingTest.kt` helper DTO construction only as needed to keep exporter-escaping coverage compiling.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.export.CsvEscapingTest"`
- Complete when:
  - repository and UI accounting call sites compile against one canonical DTO/mapper and no duplicate export-row mapper remains in B.7 files.
- Rollback / stop rule:
  - If the mapper seems to require new database/account entities or public repository API changes, stop and keep the funding-account rule derived from existing `paymentMethod` only.

#### Batch 2 — Shared deterministic paging adoption
- Files:
  - create: `app/src/main/java/com/yourname/expensetracker/data/repository/DeterministicExpenseExportPager.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/data/repository/ExportDataRepository.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/data/repository/AccountingExportRepository.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsViewModel.kt`
- Checklist:
  - [ ] Add one deterministic export pager/service that owns exhaustive paging and the export page-size constant for overlapping accounting export flows.
  - [ ] Move `AccountingExportRepository.kt` off its bespoke paging helper onto the shared pager.
  - [ ] Move `ExportOptionsViewModel.kt` overlapping accounting export iteration onto the same shared pager contract (through `ExportDataRepository`), while leaving preview truncation and file-writing UX intact.
  - [ ] Preserve existing DAO ordering (`date ASC, id ASC, merchant COLLATE NOCASE ASC`) and keep `ExpenseRepository`/DAO signatures backward-compatible.
  - [ ] Do **not** touch generic CSV/JSON behavior beyond the paging internals needed to reuse the canonical reader.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
- Complete when:
  - one deterministic pager owns overlapping accounting-export reads and repository/UI accounting paths no longer maintain separate paging logic.
- Rollback / stop rule:
  - If injecting the shared pager introduces a context/DI cycle, keep the pager as a pure `ExpenseRepository`-backed helper and route `ExportDataRepository` through it instead of coupling repositories together.

#### Batch 3 — Paging regressions and stale-truncation lock
- Files:
  - modify: `app/src/test/java/com/yourname/expensetracker/data/repository/AccountingExportRepositoryTest.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsViewModelTest.kt`
- Checklist:
  - [ ] Extend repository tests so the shared pager still exhausts multi-page datasets and does not regress back to a capped/legacy read path.
  - [ ] Extend viewmodel tests so multi-page Xero/QuickBooks/FreshBooks output remains complete after the pager refactor.
  - [ ] Lock the stale registry claim down with test evidence: overlapping accounting export flows must both stay exhaustive after this batch.
- Validation:
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.repository.AccountingExportRepositoryTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.screens.export.ExportOptionsViewModelTest"`
- Complete when:
  - the shared-paging contract is covered by focused repository/UI regressions.
- Rollback / stop rule:
  - Do **not** widen repository tests into backup/import contract assertions in this batch.

#### Batch 4 — Shared accounting safety policy + real PDF artifact
- Files:
  - create: `app/src/main/java/com/yourname/expensetracker/domain/export/AccountingExportPolicy.kt`
  - create: `app/src/main/java/com/yourname/expensetracker/domain/export/AccountantReportPdfExporter.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/domain/export/AccountingExporters.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/data/repository/AccountingExportRepository.kt`
  - modify: `app/src/main/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsViewModel.kt`
- Checklist:
  - [ ] Introduce one shared policy/helper that validates accounting-export datasets before formatting.
  - [ ] Xero/QuickBooks/FreshBooks must fail fast on mixed-currency datasets and unsupported non-`PURCHASE` rows; no silent filtering, no FX conversion, no hardcoded euro fallback.
  - [ ] `AccountingExporters.kt`: use source-account metadata for QuickBooks `TRNS` rows while keeping category on `SPL`.
  - [ ] Replace the repository’s text-based accountant report generator with a real PDF exporter that writes `.pdf` artifacts and groups totals/sections by actual currency.
  - [ ] Apply the same shared policy in the UI accounting export path so repository/UI behavior cannot drift.
- Validation:
  - `./gradlew.bat :app:compileDebugKotlin`
- Complete when:
  - unsafe accounting datasets are blocked consistently and `ACCOUNTANT_REPORT_PDF` no longer emits text masquerading as PDF.
- Rollback / stop rule:
  - Do **not** add new export formats to the screen, and do **not** bring exchange-rate conversion into this batch.

#### Batch 5 — Safety-policy regressions and UI failure handling
- Files:
  - modify: `app/src/test/java/com/yourname/expensetracker/data/repository/AccountingExportRepositoryTest.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/ui/screens/export/ExportOptionsViewModelTest.kt`
  - modify: `app/src/test/java/com/yourname/expensetracker/domain/export/CsvEscapingTest.kt`
- Checklist:
  - [ ] Add repository regressions for mixed-currency fast-fail, unsupported-transaction-type fast-fail, QuickBooks `TRNS`/`SPL` account separation, and PDF file signature/extension.
  - [ ] Add viewmodel regressions proving accounting export failures surface actionable UI errors for mixed-currency/unsupported-type datasets while safe single-currency purchase datasets still export successfully.
  - [ ] Extend `CsvEscapingTest.kt` only where the enriched DTO / QuickBooks-account semantics require new expectations; do **not** widen into medium-scope numeric-format work.
- Validation:
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.export.CsvEscapingTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.repository.AccountingExportRepositoryTest"`
  - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.screens.export.ExportOptionsViewModelTest"`
- Complete when:
  - old unsafe accounting-export behavior is reproducibly blocked by tests.
- Rollback / stop rule:
  - If plain JVM assertions cannot reliably inspect PDF internals, fall back to header/signature + extension assertions and record any environment limitation for the reviewer instead of claiming a stronger guarantee without evidence.

#### Batch 6 — Documentation, review, and registry closeout
- Files:
  - modify: `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`
  - modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-33.md`
  - modify: `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-39.md`
  - create: `docs/reviews/REVIEW-B7-export-backup-pipeline.md`
- Checklist:
  - [ ] Update only the B.7 `HIGH` rows in the registry after reviewer PASS.
  - [ ] For the stale truncation row, use live-code audit evidence: if the current code path was already fixed by A.9, disposition it accurately as doc drift / `[RESOLVED BY A.9]` instead of falsely attributing the original truncation bug to B.7.
  - [ ] Mark the remaining resolved B.7 high bullets under lines 416-422 with `[RESOLVED BY B.7]` once code + tests + review PASS are complete.
  - [ ] Update Batch 33 export rows for mixed-currency accountant math and fake PDF output, plus any cross-component export-path wording that this plan actually resolved.
  - [ ] Update Batch 39 export rows for QuickBooks source-account correctness, `ExportTransaction` currency/type preservation, and shared repository/UI paging-policy convergence.
  - [ ] Leave backup/import medium rows open and untouched.
  - [ ] Do **not** invent Batch 44 edits unless live review discovers a real export/backup row there.
- Validation:
  - reviewer PASS report exists before documentation edits
  - docs updated in playbook order: registry → exact final-verification rows → review artifact
- Complete when:
  - code, tests, registry, exact batch reports, and review report all agree on what was fixed and what remains open.
- Rollback / stop rule:
  - Do **not** mark medium/low B.7 items resolved during closeout.

### 4. Verification Plan
- **Static verification after every batch:**
  - Re-read every modified file.
  - Confirm imports/signatures remain valid.
  - Grep for forbidden leftovers in changed files:
    - duplicate `private fun Expense.toExportTransaction` helpers in export repo/viewmodel files
    - `ACCOUNTANT_REPORT_PDF` still mapped to a `.txt` filename
    - hardcoded `€` totals inside accountant report generation
    - QuickBooks `TRNS`/`SPL` still sharing the same account source
- **Serialized Gradle lane (orchestrator-owned for Phase B):**
  1. `./gradlew.bat :app:compileDebugKotlin`
  2. `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.export.CsvEscapingTest"`
  3. `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.data.repository.AccountingExportRepositoryTest"`
  4. `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.ui.screens.export.ExportOptionsViewModelTest"`
- **Reviewer focus points:**
  - one shared mapper, one shared pager, and one shared accounting-safety policy are used in overlapping accounting export paths
  - `ExportTransaction` no longer strips `currency` / `transactionType`
  - QuickBooks `TRNS` uses source account and `SPL` uses category account
  - mixed-currency / unsupported-type accounting exports fail safely instead of generating misleading files
  - `ACCOUNTANT_REPORT_PDF` produces real PDF output and per-currency reporting without hardcoded euro totals
  - no medium backup/import work slipped into the B.7 implementation

### 5. Documentation & Registry Updates
- **Registry target:** `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md` under `### B.7: Export/Backup Pipeline`.
- **Close only the exact B.7 high rows fixed by this plan:**
  - accountant report mixed-currency raw-amount math / hardcoded `€`
  - `ExportTransaction` missing `currency`
  - `ExportTransaction` missing `transactionType`
  - `ACCOUNTANT_REPORT_PDF` writing text instead of PDF
  - QuickBooks IIF source-account misuse on `TRNS`
  - repository/UI export-path divergence after shared pager/policy convergence
- **Audit-first row:**
  - the historical 2000-row truncation bullet must be dispositioned based on live-code audit; if it is already fixed by A.9-era work, document that honestly instead of re-fixing a non-existent live bug.
- **Exact final-verification files to update after review PASS:**
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-33.md`
    - export truncation row only if the doc-drift audit requires clarification
    - mixed-currency accountant report row
    - fake PDF artifact row
    - export cross-component summary row only for the high aspects actually resolved
  - `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-39.md`
    - QuickBooks source-account row
    - `ExportTransaction` currency row
    - missed `transactionType` row
    - cross-component rows for shared export path + preserved currency/type semantics
- **Review artifact:** create/update `docs/reviews/REVIEW-B7-export-backup-pipeline.md` with PASS/FAIL findings and any residual waivers.
- **Playbook note:** update `docs/plans/EXECUTION-PLAYBOOK.md` only if orchestrator designates B.7 as the completed active pipeline at final closeout; do not pre-mark it complete during implementation.

### Implementation Steps
1. Execute Batch 1 to establish the canonical export DTO and shared mapper.
2. Execute Batch 2 to converge repository/UI accounting export reads on one deterministic pager.
3. Execute Batch 3 to lock the shared-paging behavior with focused regressions.
4. Execute Batch 4 to add one shared accounting-safety policy, fix QuickBooks source-account semantics, and replace the fake PDF artifact with a real PDF exporter.
5. Execute Batch 5 to lock mixed-currency, transaction-type, QuickBooks, PDF, and UI failure semantics with targeted tests.
6. Run the serialized verification lane.
7. Run reviewer PASS/FAIL loop; remediate one issue at a time if needed.
8. Execute Batch 6 documentation closeout only after reviewer PASS.

### Risks
- `AccountingExportRepository.kt` and `ExportOptionsViewModel.kt` are hotspots touched by multiple batches; each batch must reread the live file before editing to avoid stomping earlier changes.
- The registry still reflects stale truncation wording from pre-A.9 behavior; documentation must follow live audited code, not historical issue text.
- A poor funding-account mapping could accidentally reintroduce self-canceling QuickBooks rows; keep the mapping narrow and deterministic from existing `paymentMethod` values.
- PDF generation may behave differently across local JVM vs Android-backed test environments; verification must be evidence-based and any limitation explicitly documented.
- Fast-fail validation changes user-visible behavior for unsafe accounting exports; tests must assert that failures are explicit and actionable rather than silent.

### Acceptance Criteria
- [ ] `ExportTransaction` preserves ownership-adjusted amount, currency, transaction type, and source-account/payment metadata for accounting exports.
- [ ] No duplicate local `Expense.toExportTransaction()` helper remains in overlapping B.7 export paths.
- [ ] Repository and UI accounting export flows use one deterministic paged reader contract.
- [ ] Xero/QuickBooks/FreshBooks exports fail safely on mixed-currency or unsupported non-`PURCHASE` datasets instead of emitting misleading files.
- [ ] QuickBooks `TRNS` uses a real funding/source account and `SPL` uses the category account.
- [ ] `ACCOUNTANT_REPORT_PDF` emits a real `.pdf` artifact and reports totals per currency rather than as hardcoded-euro math.
- [ ] Focused export tests cover paging, mixed-currency safety, transaction-type safety, QuickBooks account separation, PDF output, and UI error handling.
- [ ] No backup/import medium work, generic CSV medium work, or Room/schema changes are introduced.
- [ ] `MASTER-ISSUE-REGISTRY.md`, `FINAL-VERIFICATION-BATCH-33.md`, `FINAL-VERIFICATION-BATCH-39.md`, and `REVIEW-B7-export-backup-pipeline.md` are updated in the same closeout.
