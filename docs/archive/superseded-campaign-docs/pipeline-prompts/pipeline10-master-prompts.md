# Pipeline 10 Master Prompts — Cost-agregator

Generated: 2026-06-09  
Repository: https://github.com/panospao7/Cost-agregator  
Target commit: `83b798e849b4408b2bf683f52cb2746d37f7af16`  
Pipeline: **P10 — Bank Integration / Bank Imports**

Important context:
- P10 is assumed to cover **Bank Integration / Bank Statement Import / Bank Sync / Bank Transaction Matching**.
- Core architecture segments likely involved:
  - Segment 9 — Core Expense Management
  - Segment 12 — Startup & Background Runtime
  - Segment 14 — Bank Integration
  - Segment 16 — Currency & Exchange
  - Segment 18 — Export & Backup
  - Segment 20 — AI Platform, if bank statement AI parsing exists
  - Segment 28 — Security / Privacy
  - Segment 29 — Debug & Diagnostics
  - Segment 30 — Dependency Injection
  - Segment 38 — Receipt / Expense Matching, if imported bank transactions are matched to expenses.
- Treat pipeline docs and trackers as context, not truth. **Code at the target SHA is the source of truth.**
- The reviewing agent must read the actual P10 consolidated issue doc and extract exact issue IDs from it before making claims.

---

## Prompt A — P10 Master Audit / Debug / Review Prompt

Copy/paste this prompt into the agent:

```text
You are a senior Android/Kotlin, Room, banking-data, financial-integrity, privacy/security, WorkManager, import/idempotency, and architecture-review agent.

## 1. Exact target

Repository URL:
https://github.com/panospao7/Cost-agregator

Exact commit SHA:
83b798e849b4408b2bf683f52cb2746d37f7af16

Pipeline:
P10 — Bank Integration / Bank Imports

Mode:
Review only + issue discovery + validation of already-fixed claims.
Do NOT implement code changes unless explicitly asked later.
You may propose exact fixes and tests, but this run is an audit/debug review.

Checkout command:
git clone https://github.com/panospao7/Cost-agregator.git
cd Cost-agregator
git checkout 83b798e849b4408b2bf683f52cb2746d37f7af16

If the checkout is dirty or not exactly at this SHA, stop and report it.

## 2. Pipeline scope

Audit Pipeline 10 end-to-end:

### Bank connection / provider scope
- bank connection/account lifecycle,
- provider clients,
- Open Banking/Plaid/manual provider abstractions if present,
- token exchange,
- access/refresh token storage,
- connection status,
- consent expiry/revocation,
- account unlink/delete,
- provider pagination,
- provider transaction-id stability,
- provider duplicate/backfill behavior,
- sync cursors,
- webhook handling if present.

### Bank statement import scope
- CSV/OFX/QIF/PDF/manual bank statement import,
- parser registry,
- parser locale/date/decimal handling,
- file validation,
- import run ledger,
- import item ledger,
- duplicate detection,
- raw statement persistence policy,
- AI-assisted parsing if present,
- parser diagnostics/drop reasons,
- import rollback/partial failure behavior.

### Bank transaction staging scope
- bank transaction entities,
- imported transaction status machine,
- pending review,
- matched/ignored/imported/duplicate states,
- item-level idempotency,
- account-level uniqueness,
- transaction hash/key generation,
- merchant normalization,
- amount sign normalization,
- currency normalization,
- timestamp/time-zone/posting-date handling,
- shared/not-mine/transfer direction flags if supported.

### Expense creation / matching scope
- matching bank transactions to existing expenses,
- creating expenses from bank transactions,
- linking/unlinking bank transaction to expense,
- avoiding duplicate money records,
- direct DAO write bypasses,
- transaction lifecycle coordinator use,
- source-link/provenance preservation,
- user review/confirmation flow,
- auto-match confidence thresholds,
- rollback/retry safety.

### Worker/background scope
- scheduled bank sync workers,
- one-shot import workers,
- retry/backoff,
- provider rate-limit handling,
- network constraints,
- privacy/connection gating,
- restore/write barrier,
- worker run logging,
- cancellation propagation,
- idempotency under repeated runs.

### Privacy/security scope
- token encryption,
- token/key export exclusion,
- raw bank statement storage policy,
- cloud AI payload redaction,
- account number/IBAN/card masking,
- logs/diagnostics sanitization,
- backup/export privacy mode,
- user consent and capability gates.

### Cross-pipeline dependencies
- P1/P2/P3 expense lifecycle must not be bypassed by bank-created expenses.
- P4 recurring reconciliation may link actual bank-paid expenses to planned occurrences.
- P5/P6 analytics/budget/cashflow consume bank-created expenses and must receive normalized currency/amounts.
- P7 backup/restore must preserve bank import state while protecting secrets.
- P8 privacy/AI/redaction applies to raw bank text, account identifiers, and cloud parsing.
- P9 workers apply to bank sync/import workers.
- P12 import/export overlaps with statement import/export and accounting output.

Read first:
- `docs/analyses and debug master/PIPELINE_10_CONSOLIDATED_ISSUES.md`
- `docs/analyses and debug master/PIPELINE_ISSUES_MASTER_TRACKER.md`
- `docs/analyses and debug master/UNIVERSAL_ISSUE_TRACKER.md`
- `docs/analyses and debug master/pipelines issues implementantion plan/PIPELINE_10_IMPLEMENTATION_PLAN.md`
- relevant universal implementation-plan docs if referenced by P10.

Before reviewing code, extract all P10 issue IDs from the P10 consolidated issue doc into a checklist. Validate each issue against code and tests at the target SHA.

The master tracker says the methodology was:
Scout → Planner → Coder → Tester → Reviewer → Debugger.

Follow that method:
1. Scout files and flows.
2. Plan review coverage.
3. Inspect code deeply.
4. Inspect tests.
5. Compare with architecture.
6. Debug mismatches.
7. Produce evidence-backed findings.

Shared contracts must be validated before pipeline-local conclusions.

## 3. Architecture docs to read

Always read:
- `docs/architecture/ARCHITECTURE.md`
- `docs/architecture/CODEBASE_SEGMENTS.md`
- `docs/architecture/DEPENDENCY_MAP.md`
- `docs/architecture/LEGAL_PATHS.md`
- `docs/architecture/ENGINE_INTERACTION_MAP.md`
- `docs/architecture/COMPLETE-BACKEND-MAP.md`
- `docs/architecture/BACKEND-MAP-INDEX.md`
- `docs/architecture/CODEBASE_INVENTORY.md`
- `docs/architecture/dao-map.md`
- `docs/architecture/hilt-bindings-map.md`
- `docs/architecture/import-graph.json`

Conditional docs to read:
- UI pipeline:
  - `COMPREHENSIVE_UI_MAP.md`
  - `VIEWMODEL_INJECTION_MAP.md`
  - `route-viewmodel-map.md`
- Privacy/diagnostics:
  - `PRIVACY_UI_ARCHITECTURE.md`
  - `SENSITIVE_DIAGNOSTICS_POLICY.md`
- DB/restore/import/export:
  - `DATABASE_BASELINE_POLICY.md`
  - `DB_WRITE_OWNERSHIP.md`
  - `backup-restore-barrier-contract.md`
  - `expense-mutation-inventory.md`

For P10 specifically, pay special attention to:
- Segment 14 — Bank Integration.
- Segment 9 — Core Expense Management.
- Money/currency legal paths.
- Transaction lifecycle legal paths.
- Privacy/security docs for tokens and raw bank text.
- Worker guard docs if bank sync/import uses WorkManager.
- DB write ownership docs for expense, bank transaction, import run, account, token, and diagnostics tables.

## 4. Build a pipeline file inventory

Do not rely only on this seed list.
Use `rg`, import graph, Hilt map, DAO map, callers/callees, WorkManager schedule paths, and tests to build the real inventory.

### Searches to start inventory
Run:
- `rg -n "Bank|bank|Statement|statement|OpenBanking|Plaid|OFX|QIF|IBAN|account|routing|sync|cursor"`
- `rg -n "BankTransaction|BankAccount|BankConnection|BankStatement|BankImport|StatementImport"`
- `rg -n "BankStatementImportItem|BankStatementImportRun|BankTransactionPersistencePayload"`
- `rg -n "providerTransactionId|transactionId|externalId|syncCursor|consent|accessToken|refreshToken"`
- `rg -n "matchBank|bank.*match|linkBank|unlinkBank|createExpenseFromBank|importBank"`
- `rg -n "suggestFromText|bank.*prompt|PreparedCloudPayload|CloudPayloadRedactor|RawPersistencePolicy"`
- `rg -n "class .*Bank.*Worker|BankSyncWorker|BankImportWorker|CoroutineWorker"`

### Domain / use cases
Inventory all current equivalents of:
- bank account manager,
- bank connection manager,
- bank sync coordinator,
- bank import coordinator,
- bank statement import use case,
- bank transaction matcher,
- bank transaction deduper,
- bank transaction normalizer,
- bank merchant/category suggestion engine,
- bank transaction review coordinator,
- expense creation from bank import,
- bank transaction source-link/provenance writer,
- AI-assisted bank parsing/suggestion classes.

If filenames differ, report actual file paths and tracker/code drift.

### Repositories
Inventory:
- bank account repository,
- bank connection repository,
- bank transaction repository,
- bank statement import repository,
- bank sync repository,
- bank provider repository/client,
- expense repository interactions,
- category/merchant repositories,
- currency repository,
- diagnostics repository,
- privacy settings/gate repositories,
- token/security repositories.

Search:
- `rg -n "class .*Bank.*Repository|interface .*Bank.*Repository|Bank.*Repository"`

### Provider/network/security
Inventory:
- provider clients,
- API DTOs,
- token exchange/refresh,
- webhook handlers if present,
- OkHttp/Retrofit API interfaces,
- request/response mappers,
- encrypted token storage,
- `SecureKeyStorage`,
- `BankTokenCipher` or equivalent,
- any `Authorization`, `Bearer`, `apiKey`, `clientSecret`.

Search:
- `rg -n "Retrofit|OkHttp|Authorization|Bearer|clientSecret|accessToken|refreshToken|KeyStore|Cipher|AES|GCM"`

### Parsers/importers
Inventory:
- CSV bank parser,
- OFX/QIF parser,
- PDF/AI statement parser,
- parser registry,
- date/amount/currency parsing utilities,
- import validators,
- file-size limits,
- MIME checks,
- CSV injection guards,
- decimal/locale handling,
- raw text persistence decisions.

Search:
- `rg -n "CSV|Csv|OFX|QIF|PDF|StatementParser|BankStatementParser|parseStatement|parseBank"`

### DAOs
Inventory all relevant DAOs:
- bank account DAO,
- bank connection DAO,
- bank transaction DAO,
- bank statement import run DAO,
- bank statement import item DAO,
- bank sync state DAO,
- bank merchant/category mapping DAO,
- expense DAO,
- expense source-link/provenance DAO,
- category DAO,
- merchant DAO,
- exchange rate DAO,
- pipeline diagnostic DAO,
- background job run DAO,
- privacy audit DAO.

Search:
- `rg -n "Bank.*Dao|Statement.*Dao|Import.*Dao|Sync.*Dao"`

### Room entities / schema touchpoints
Inventory:
- bank account entity,
- bank connection entity,
- bank transaction entity,
- bank statement import run entity,
- bank statement import item entity,
- bank sync state entity,
- token/credential entity if any,
- mapping/provenance entities,
- `Expense`,
- `ExpenseSourceLink` or equivalent,
- `ExchangeRate`,
- `PipelineDiagnosticEvent`,
- `BackgroundJobRun`,
- `PrivacyAuditEvent`,
- `AppDatabase.kt`,
- all migrations touching bank/import/expense/link tables,
- exported Room schema JSON if present.

Check:
- unique indices,
- foreign keys,
- cascade behavior,
- status columns,
- timestamps,
- nullable raw fields,
- encrypted-token fields,
- migration defaults.

### Workers / scheduling
Inventory:
- bank sync worker,
- statement import worker,
- retry/import cleanup worker,
- any provider refresh worker,
- worker registry/spec entries,
- WorkManager enqueue/cancel calls,
- worker guard/run logging.

Also review infrastructure:
- `WorkerExecutionGuard.kt`
- `WorkerGuardRequest.kt`
- `WorkerRunLogger.kt`
- `WorkerRunContext.kt`
- `WorkerSpec.kt`
- `WorkerSpecScheduler.kt`
- `WorkerRegistry.kt`
- `RetryableWorkerException.kt`
- `WorkerDrainController.kt`

If P10 has no workers, explicitly say so with search evidence.

### Privacy / AI / raw storage
Inventory:
- `PrivacyGate`,
- `PrivacyCapability`,
- bank-related privacy capabilities,
- `BankTransactionPersistencePayload`,
- `RawPersistencePolicyResolver`,
- `RawContentSanitizer`,
- `CloudPayloadPolicy`,
- `CloudPayloadRedactor`,
- `PreparedCloudPayload`,
- `EffectiveCloudAiPolicy`,
- privacy audit logging,
- sensitive diagnostics sanitizer.

### Hilt modules
Review modules that provide/bind:
- bank repositories,
- provider clients,
- network clients,
- token cipher/security storage,
- statement parsers,
- import/matching coordinators,
- DAOs/database,
- currency/money services,
- privacy gate/settings,
- AI services,
- worker dependencies,
- diagnostics,
- dispatchers,
- TimeProvider,
- backup/export barriers.

Search:
- `rg -n "Bank|Statement|Plaid|OpenBanking|Token|Cipher|Parser|Sync" app/src/main/java/com/yourname/expensetracker/di`

### UI / ViewModels
If bank integration reaches UI, include:
- bank connection screen,
- account list screen,
- statement import picker,
- import preview/review screen,
- bank transaction review screen,
- matching/conflict UI,
- settings/consent UI,
- ViewModels,
- routes,
- UI state models,
- denied privacy state UI.

Search:
- `rg -n "Bank|Statement|Import|ConnectBank|BankAccount|BankTransaction|OpenBanking|Plaid" app/src/main/java/com/yourname/expensetracker/ui`

If no UI is involved, explicitly say “UI not reached” with evidence.

### Tests
Search:
- `rg -n "Bank|Statement|OpenBanking|Plaid|OFX|QIF|BankTransaction|BankImport|BankSync|Token|IBAN|Bank.*Privacy|Bank.*Worker" app/src/test app/src/androidTest`

Include:
- parser tests,
- dedupe/idempotency tests,
- matching tests,
- transaction lifecycle tests,
- worker guard tests,
- token encryption tests,
- privacy/redaction tests,
- restore/export tests,
- migration/schema tests,
- UI/ViewModel tests if relevant.

Do not stop at known names. Search the entire repo.

## 5. Code-reading rules

Mandatory:
- Do not trust docs over code.
- If docs and code disagree, report the mismatch.
- Do not review only filenames; open implementation and tests.
- Trace actual runtime flow, not package structure.
- Search direct and indirect callers.
- Include cross-pipeline dependencies.
- Mark uncertainty clearly.
- Verify Hilt-injected runtime path, not just constructor signatures.
- Check whether public methods bypass transaction lifecycle, privacy gate, token encryption, or worker guard.
- Check whether tests assert the real invariant, not just construct classes.
- If tracker says fixed/open/TODO, validate against code at this SHA.
- Treat duplicate money records as P0/P1.
- Treat raw bank credentials/tokens/account numbers in logs/export/diagnostics as P0/P1.
- Treat direct `ExpenseDao` writes from bank import as a lifecycle bypass unless explicitly allowed and documented.
- Treat parser “best effort” failures without item-level ledger/drop reasons as data-integrity risk.
- Treat repeated sync/import as a required idempotency scenario.

Use searches like:
- `rg -n "ExpenseDao|insertExpense|addExpense|TransactionLifecycleCoordinator|createTransaction|sourceLink"`
- `rg -n "providerTransactionId|externalId|unique|IGNORE|REPLACE|ON CONFLICT"`
- `rg -n "amount|currency|effectiveAmount|exchangeRate|RateBasis|MoneyNormalizationEngine"`
- `rg -n "accessToken|refreshToken|clientSecret|apiKey|IBAN|accountNumber|routingNumber"`
- `rg -n "Timber\\.|Log\\.|println|errorMessage|diagnostic|OperationRun|BackgroundJobRun"`
- `rg -n "PrivacyGate|PrivacyCapability|PreparedCloudPayload|RawPersistencePolicy|SafePrivacyMetadata"`
- `rg -n "DatabaseWriteBarrier|writeBarrier|RestoreMaintenanceMode|WorkerExecutionGuard|runGuarded"`
- `rg -n "CancellationException|catch \\(e: Exception\\)|catch \\(t: Throwable\\)"`
- `rg -n "System.currentTimeMillis|Instant.now|LocalDate.now|TimeProvider"`

## 6. Universal contracts to verify

Audit these for P10:

1. Restore/write barrier:
   - every bank import/sync write checks `DatabaseWriteBarrier`,
   - workers are blocked/drained during backup/restore,
   - import cannot mutate live DB during restore,
   - file staging/import temp writes are safe.

2. Worker guard and run logging:
   - bank workers use `WorkerExecutionGuard`,
   - run start/success/skip/retry/failure logged,
   - provider/network errors classified correctly,
   - cancellation propagated,
   - repeated worker run is idempotent.

3. Privacy/redaction/raw-storage policy:
   - tokens encrypted and never logged/exported,
   - account identifiers masked in diagnostics/UI where required,
   - raw statement text follows `RawPersistencePolicy`,
   - cloud AI bank parsing uses `PreparedCloudPayload`,
   - privacy gates fail closed.

4. Money/currency normalization:
   - bank amounts are finite,
   - sign/direction is normalized,
   - currency codes are non-blank/valid,
   - cross-currency expense creation uses MoneyNormalizationEngine or legal equivalent,
   - original amount/currency preserved as provenance where needed,
   - missing/stale rates produce warnings/partial state, not fake precision.

5. Transaction lifecycle ownership:
   - created expenses go through `TransactionLifecycleCoordinator`,
   - direct `ExpenseDao` writes are absent or justified,
   - expense updates/deletes from bank matching are legal,
   - duplicate expense creation prevented.

6. Receipt lifecycle/link ownership:
   - if bank transactions match receipt-created expenses, matching respects receipt lifecycle/source links,
   - receipt links are not overwritten incorrectly.

7. Recurring planned/actual reconciliation:
   - bank-created/linked actual expenses trigger recurring reconciliation if applicable,
   - matching a bank transaction to a planned/recurring bill does not duplicate records.

8. Diagnostics/drop reasons/events:
   - import row failures have durable reasons,
   - provider sync failures have sanitized diagnostics,
   - no raw tokens/account numbers/raw statement lines in diagnostics,
   - diagnostic failures do not abort core work except cancellation.

9. Import/export schema/roundtrip:
   - bank accounts/import runs/import items/matches survive backup/restore if intended,
   - tokens are excluded or encrypted per policy,
   - redacted export excludes raw sensitive fields,
   - import/export cannot reintroduce raw data against privacy settings.

10. DAO conflict handling and timestamps:
   - unique constraints prevent duplicates,
   - `IGNORE`/upsert results are checked,
   - status transitions are conditional,
   - `createdAt`/`updatedAt` valid,
   - import item ledger is idempotent.

## 7. P10-specific invariants to audit

### Provider sync
Check:
- sync cursor updates only after successful item persistence,
- pagination cannot skip or double-apply pages,
- provider transaction IDs are scoped by connection/account/provider,
- deleted/reversed provider transactions are handled,
- consent expiry disables sync and surfaces UI/actionable status,
- account unlink cancels sync and handles local data policy,
- retries do not duplicate imported transactions,
- network rate limits map to retry with backoff,
- token refresh is atomic and encrypted.

### Statement import
Check:
- file is copied/staged before parsing if needed,
- parser failure creates import-run/item failure state,
- partial imports are explicit,
- unsupported columns/formats fail closed with diagnostics,
- locale decimal/date ambiguity is handled,
- duplicate rows within same file are detected,
- repeated import of same file is idempotent,
- raw file/text persistence obeys privacy policy,
- AI parser redacts before cloud.

### Bank transaction status machine
Check:
- legal statuses are explicit,
- pending/reviewed/matched/ignored/duplicate/imported transitions are valid,
- terminal states not downgraded,
- status changes write events/diagnostics if expected,
- impossible states are errors, not silent skips.

### Matching/linking
Check:
- matching uses amount, currency, date window, merchant key, account, transaction direction, and confidence threshold,
- manual link/unlink is transactional,
- auto-match is idempotent,
- one expense cannot be linked to multiple conflicting bank transactions unless many-to-one is explicitly allowed,
- one bank transaction cannot create multiple expense records,
- source-link/provenance is durable,
- unlink reopens review state safely.

### Expense creation
Check:
- no direct `ExpenseDao.insert` from bank path unless legal restore/internal import path,
- bank-created expense uses transaction lifecycle,
- transaction type/income/expense/transfer direction is correct,
- not-mine/shared flags are preserved if available,
- category defaults are safe,
- recurring/planned reconciliation runs through side-effect framework.

### Currency/amount
Check:
- debit/credit sign conventions do not invert expenses/income,
- absolute vs signed amount uses explicit model,
- original currency retained,
- home-currency amount normalized with explicit rate basis,
- refunds/reversals/chargebacks handled,
- zero/NaN/infinite amounts rejected.

### Privacy/security
Check:
- tokens never stored plaintext,
- `toString()`/data classes do not leak tokens,
- tokens not included in backup/export/logs,
- account numbers/IBAN masked,
- raw statement lines gated/redacted,
- AI prompt/response sanitized,
- release logs safe.

### Restore/export
Check:
- restore cannot leave import item linked to missing expense,
- backup verifier includes bank tables or documents exclusion,
- restored sync state does not immediately resync duplicate historical range,
- post-restore worker scheduling safe,
- raw/token data policy respected.

### UI/review
If UI exists, check:
- import preview accurately shows partial/warnings,
- privacy-denied state visible,
- ambiguous matches require confirmation,
- duplicate warnings visible,
- failure/retry state visible,
- user can undo unlink/ignore if supported,
- no unmasked sensitive account data.

## 8. Known P10 issue set to validate

Read `PIPELINE_10_CONSOLIDATED_ISSUES.md` and `PIPELINE_10_IMPLEMENTATION_PLAN.md`.

Do NOT invent final status from this prompt. Extract exact tracker issue IDs and validate every one.

Classify common P10 issue themes if present:
- direct expense DAO writes from bank import,
- duplicate expense creation on repeated sync/import,
- provider transaction ID not scoped by account/provider,
- sync cursor advanced before durable write,
- bank worker missing `WorkerExecutionGuard`,
- token/plaintext credential storage or logging,
- raw statement text stored against privacy policy,
- AI bank parser sends raw prompt/text,
- parser date/amount/currency locale bugs,
- missing import item failure/drop reasons,
- status transition bugs,
- missing backup/restore coverage for bank tables,
- redacted export leaks bank identifiers,
- cancellation swallowed in sync/import loops,
- Hilt binding drift or dead implementation.

For each issue:
- tracker status,
- code status at target SHA,
- evidence,
- final status,
- required tests.

If code is fixed but tracker says open, report tracker drift.
If tracker says fixed but code still violates invariant, report bug/partial.

## 9. Review dimensions

Check:
- correctness,
- financial data integrity,
- duplicate-money prevention,
- atomicity/transactions,
- lifecycle bypasses,
- direct DAO writes,
- restore/export safety,
- privacy fail-closed behavior,
- token security,
- raw PII storage/logging,
- cancellation handling,
- coroutine races,
- WorkManager retry/idempotency,
- provider pagination/cursor safety,
- parser/dedupe/conflict behavior,
- state-machine transitions,
- timestamp/currency defaults,
- schema/migration compatibility,
- Hilt binding correctness,
- UI state consistency if relevant,
- diagnostics coverage,
- test coverage,
- performance risks,
- security/privacy risks.

## 10. Required output format

Produce this exact structure:

# Pipeline 10 Review — Bank Integration / Bank Imports

## 1. Pipeline summary
- What P10 does.
- Main data flow.
- Entry points and exits.
- Mermaid or text data-flow diagram.

## 2. File inventory
Create a table:
| Category | Files reviewed | Why relevant | Notes |

Include:
- entry points,
- provider clients,
- parsers/importers,
- services/coordinators,
- repositories,
- DAOs,
- Room entities,
- workers,
- privacy/security/token files,
- AI redaction files if relevant,
- Hilt modules,
- ViewModels/UI if reached,
- tests,
- diagnostics/event writers,
- migrations/schema touchpoints.

Also list:
- files intentionally skipped and why,
- files discovered but not fully reviewed and why.

## 3. Architecture comparison
- Does code follow `LEGAL_PATHS.md`?
- Does code follow Segment 14 ownership?
- Does bank-created expense flow follow transaction lifecycle ownership?
- Does code follow privacy/security docs?
- Any doc/code drift?
- Any tracker/code drift?
- Any stale TODO or misleading comment?

## 4. Runtime flow / call graph
Include:
- bank connection/token flow,
- provider sync,
- statement import,
- parser-to-import-item flow,
- bank transaction review,
- match/link/unlink,
- create expense from bank transaction,
- worker scheduling/guard,
- privacy/redaction,
- restore/export handling,
- diagnostics/events.

## 5. Issue table
Use columns:
| ID | Severity P0/P1/P2/P3 | Status bug/partial/TODO/fixed/design | File(s) | Evidence | Impact | Reproduction path | Recommended fix | Required tests | Cross-pipeline impact |

Every finding must have concrete evidence:
- file path,
- method name,
- relevant condition,
- why it violates contract.

## 6. Universal contract audit
Subsections:
- restore barrier,
- privacy/redaction/token security,
- lifecycle ownership,
- worker guard/run logging,
- money/currency normalization,
- diagnostics/events,
- import/export/backup,
- DAO conflict/timestamps.

For each, verdict:
- PASS,
- FAIL,
- PARTIAL,
- NOT APPLICABLE,
with evidence.

## 7. P10 issue reconciliation
Create table:
| Tracker issue | Tracker status | Code status at target SHA | Evidence | Final status | Notes |

Include all P10 issues from `PIPELINE_10_CONSOLIDATED_ISSUES.md`.

## 8. Test coverage review
- Existing tests found.
- What each test proves.
- Missing tests.
- Weak tests that do not assert the important invariant.

## 9. Test plan
Include:
- unit tests,
- integration tests,
- regression tests,
- instrumentation/UI tests if needed,
- manual validation scenarios.

## 10. Optional deliverables
Include at least one:
- Mermaid/text data-flow diagram,
- call graph,
- bank import status-machine diagram,
- legal write path table,
- dependency map,
- before/after fix plan,
- commit plan split by safe PRs.

## 11. Final verdict
- GREEN / YELLOW / RED.
- Highest-risk remaining issue.
- Whether P10 is production-safe.
- What must be fixed before GREEN.

## 11. Severity rubric

Use:
- P0: data loss, corruption, privacy leak, broken restore, duplicate money records, irreversible wrong write.
- P1: major wrong behavior, race, lifecycle bypass, missing guard, broken critical flow.
- P2: edge-case bug, poor diagnostics, partial inconsistency, retry/idempotency weakness.
- P3: cleanup, docs drift, TODO, non-critical maintainability.

For P10:
- Duplicate expenses/money records from bank sync/import are P0/P1.
- Plaintext token/credential leak is P0.
- Raw bank statement/account identifiers sent to cloud without gate/redaction is P0/P1.
- Sync cursor advancing before durable write is P1.
- Missing worker guard on writing bank worker is P1.
- Parser locale/date edge cases are P2 unless they cause irreversible wrong writes.

## 12. Completion criteria

The review is not complete until:
- P10 issue doc was read,
- master/universal trackers were read,
- architecture docs were checked,
- all relevant source files were inventoried,
- key callers/callees were traced,
- every bank-created expense path was checked for lifecycle ownership,
- every token/raw bank text path was checked for privacy/security,
- workers were checked for guard/run logging,
- tests were found or missing tests were listed,
- cross-pipeline impacts were identified,
- every finding has evidence and a fix strategy,
- final verdict is justified.
```

---

## Prompt B — P10 Fix Implementation + Tests Prompt

Use this after Prompt A produces confirmed findings.

```text
You are a senior Android/Kotlin implementation agent specializing in bank imports, provider sync, Room transactions, financial idempotency, token security, privacy/redaction, WorkManager, and test-driven fixes.

## 1. Exact target

Repository:
https://github.com/panospao7/Cost-agregator

Commit baseline:
83b798e849b4408b2bf683f52cb2746d37f7af16

Pipeline:
P10 — Bank Integration / Bank Imports

Mode:
Fix implementation + test writing + validation.
Only fix confirmed P10 issues.
Do not perform broad refactors.
Preserve architecture contracts and public behavior unless a bug requires change.

## 2. Required reading before editing

Read:
- `docs/analyses and debug master/PIPELINE_10_CONSOLIDATED_ISSUES.md`
- `docs/analyses and debug master/PIPELINE_ISSUES_MASTER_TRACKER.md`
- `docs/analyses and debug master/UNIVERSAL_ISSUE_TRACKER.md`
- `docs/analyses and debug master/pipelines issues implementantion plan/PIPELINE_10_IMPLEMENTATION_PLAN.md`
- `docs/architecture/ARCHITECTURE.md`
- `docs/architecture/CODEBASE_SEGMENTS.md`
- `docs/architecture/DEPENDENCY_MAP.md`
- `docs/architecture/LEGAL_PATHS.md`
- `docs/architecture/ENGINE_INTERACTION_MAP.md`
- `docs/architecture/COMPLETE-BACKEND-MAP.md`
- `docs/architecture/BACKEND-MAP-INDEX.md`
- `docs/architecture/CODEBASE_INVENTORY.md`
- `docs/architecture/dao-map.md`
- `docs/architecture/hilt-bindings-map.md`
- `docs/architecture/import-graph.json`
- `docs/architecture/PRIVACY_UI_ARCHITECTURE.md` if UI/privacy touched.
- `docs/architecture/SENSITIVE_DIAGNOSTICS_POLICY.md` if diagnostics/logging touched.
- DB/restore/export docs if touching backup/import/export.

Do not trust docs over code.
If tracker status differs from code, fix code only if code is actually wrong.
If only docs are stale, report docs drift instead of changing code.

## 3. Implementation constraints

Follow P10 legal paths:
- bank-created expenses must go through `TransactionLifecycleCoordinator` or the documented legal transaction mutation boundary,
- bank match/link/unlink must be transactional and idempotent,
- bank import/sync writes must be restore-barrier guarded,
- bank workers must use `WorkerExecutionGuard` and run logging,
- provider tokens/secrets must be encrypted and never logged/exported,
- raw statement text must go through `RawPersistencePolicyResolver` / sanitizer,
- AI bank parsing must use `PreparedCloudPayload` / `CloudPayloadRedactor`,
- money/currency must be normalized through the legal money engine where required,
- diagnostics must be sanitized.

General rules:
- Keep changes minimal and targeted.
- Add/update tests for every fixed issue.
- Do not introduce schema migration unless explicitly required and approved.
- Do not mask `CancellationException`.
- Do not use `System.currentTimeMillis()` where `TimeProvider` exists.
- Do not raw-sum cross-currency amounts.
- Do not advance sync cursor before durable item write.
- Do not use provider transaction ID without account/provider scoping.
- Do not store tokens in data classes whose `toString()` can leak them.
- Do not create expenses directly through `ExpenseDao`.
- Do not store raw bank text first and purge later when policy says redact/drop.

## 4. Candidate P10 fix areas

Validate first, then fix only if still broken.

### P10-PR1 — Duplicate-money and lifecycle correctness
Candidate issues:
- direct `ExpenseDao` writes from bank import,
- repeated sync/import creates duplicate expenses,
- match/link race creates duplicate links,
- one bank transaction can create multiple expense records,
- source provenance missing.

Implementation intent:
1. Route expense creation through `TransactionLifecycleCoordinator`.
2. Add unique bank source-link/provenance constraints or checked upserts.
3. Make link/unlink transactional.
4. Make auto-match idempotent and confidence-gated.
5. Ensure recurring/planned reconciliation is triggered by legal transaction lifecycle side effects.

Required tests:
- `bank_created_expense_uses_transaction_lifecycle`
- `reimport_same_statement_does_not_create_duplicate_expense`
- `resync_same_provider_transaction_is_idempotent`
- `concurrent_bank_match_cannot_double_link`
- `bank_unlink_reopens_review_state`
- `bank_created_expense_triggers_recurring_reconciliation_if_applicable`

### P10-PR2 — Provider sync and import ledger safety
Candidate issues:
- sync cursor advanced before durable writes,
- provider transaction ID not scoped by account/provider,
- import run/item failure states missing,
- parser partial failure silently skipped,
- cancellation swallowed.

Implementation intent:
1. Persist each page/item durably before cursor advancement.
2. Scope uniqueness by provider + connection/account + provider transaction ID.
3. Add item-level ledger with statuses and drop reasons.
4. Record parser errors as sanitized diagnostics.
5. Rethrow `CancellationException`.
6. Make import retries resume safely.

Required tests:
- `sync_cursor_advances_only_after_page_commit`
- `provider_transaction_id_scoped_by_account`
- `parser_partial_failure_records_item_drop_reason`
- `import_retry_resumes_without_duplicates`
- `sync_rethrows_cancellation`
- `provider_rate_limit_maps_to_retry`

### P10-PR3 — Token security and privacy
Candidate issues:
- plaintext token storage,
- tokens/account identifiers in logs/diagnostics/export,
- raw bank statement stored against policy,
- AI bank parser sends raw prompt/text,
- raw export leaks bank secrets.

Implementation intent:
1. Use `SecureKeyStorage` / token cipher for tokens.
2. Redact/mask account numbers, IBAN, tokens, provider secrets.
3. Route raw text through `RawPersistencePolicyResolver`.
4. Require `PreparedCloudPayload` for cloud bank AI.
5. Exclude/encrypt tokens in backup/export.
6. Add safe metadata builders.

Required tests:
- `bank_tokens_encrypted_at_rest`
- `bank_token_not_in_toString_logs_or_diagnostics`
- `account_identifier_masked_in_ui_and_diagnostics`
- `raw_statement_do_not_store_policy_drops_text`
- `raw_statement_store_redacted_writes_redacted_text`
- `bank_ai_prompt_uses_prepared_cloud_payload`
- `redacted_export_excludes_bank_tokens_and_raw_text`

### P10-PR4 — Currency, sign, parser normalization
Candidate issues:
- debit/credit sign inverted,
- raw amounts not finite,
- currency blank/defaulted incorrectly,
- date/time-zone parsing inconsistent,
- locale decimal parsing wrong,
- refunds/reversals mishandled.

Implementation intent:
1. Introduce/centralize bank amount normalization.
2. Store explicit direction/type.
3. Validate finite amounts and valid currencies.
4. Use explicit date parser/time zone policy.
5. Preserve original amount/currency/provenance.
6. Add parser golden tests.

Required tests:
- `debit_imported_as_expense_outflow`
- `credit_imported_as_income_inflow`
- `refund_or_reversal_not_double_counted`
- `blank_currency_rejected_or_warned`
- `csv_decimal_locale_parsed_correctly`
- `posted_date_uses_explicit_zone_policy`
- `original_amount_currency_preserved`

### P10-PR5 — Worker/restore/export hardening
Candidate issues:
- bank workers missing guard,
- worker retry/idempotency weak,
- writes during restore,
- backup restore leaves stale sync state,
- Hilt binding drift.

Implementation intent:
1. Wrap bank workers with `WorkerExecutionGuard`.
2. Add worker run logging/counters.
3. Add restore barrier checks before writes.
4. Verify backup/export inclusion/exclusion rules.
5. Fix Hilt bindings to intended implementations.

Required tests:
- `bank_worker_blocked_during_restore`
- `bank_worker_logs_success_retry_failure_skip`
- `bank_worker_retry_does_not_duplicate_items`
- `restore_preserves_bank_import_links`
- `backup_excludes_or_encrypts_tokens`
- `hilt_binds_bank_components_to_intended_impls`

## 5. Universal checks before/after every fix

Verify:
- restore/write barrier on every bank write,
- no direct expense DAO lifecycle bypass,
- worker guard/run logging,
- token encryption and no secret logs,
- raw storage policy at write time,
- cloud payload redaction,
- money/currency normalization,
- DAO conflict results checked,
- status transitions conditional,
- timestamps valid,
- no schema drift without migration,
- tests cover real runtime path.

## 6. Required validation commands

Run at minimum:
```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Bank*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Statement*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Import*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Sync*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Token*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Plaid*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*OpenBanking*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Privacy*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Worker*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*TransactionLifecycle*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Currency*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Backup*" --stacktrace
./gradlew :app:check --stacktrace
```

If a command cannot run, report:
- exact command,
- failure reason,
- whether failure is related to P10,
- what still needs manual validation.

## 7. Required output

Produce:

## Summary
- Issues fixed.
- Issues confirmed already fixed.
- Issues deferred/design-only.
- Issues not touched and why.

## Changed files
| File | Change | Issue IDs | Tests |

## Issue reconciliation
| ID | Before | After | Evidence | Tests |

## Test results
- Commands run.
- Pass/fail.
- Relevant logs.

## Remaining risks
- Highest risk.
- Cross-pipeline impacts.
- Any migration/design follow-up.

## Commit plan
Split into safe PRs:
1. duplicate-money/lifecycle correctness,
2. provider sync/import ledger idempotency,
3. token/privacy/redaction safety,
4. currency/parser normalization,
5. worker/restore/export hardening,
6. UI/docs/tracker sync.
```

---

## Prompt C — P10 Final Validation / Fixed-Claims Audit Prompt

Use this after fixes land.

```text
You are a senior validation/debugger agent specializing in bank import correctness, financial idempotency, token security, privacy, and background sync safety.

## 1. Exact target

Repository:
https://github.com/panospao7/Cost-agregator

Target:
Use the current working branch/commit provided by the user.
Baseline context commit:
83b798e849b4408b2bf683f52cb2746d37f7af16

Pipeline:
P10 — Bank Integration / Bank Imports

Mode:
Validation of already-fixed claims.
Do not implement new fixes.
Verify whether P10 can be marked GREEN/YELLOW/RED.

## 2. Required reading

Read:
- P10 consolidated issue doc,
- P10 implementation plan,
- master tracker,
- universal tracker,
- all architecture docs listed in Prompt A,
- sensitive diagnostics policy,
- privacy UI architecture if UI touched,
- changed source files,
- changed tests,
- migration/schema files if touched,
- changed Hilt modules,
- changed workers,
- changed UI files if touched,
- changed provider/parser/security files.

Do not trust PR descriptions or comments.
Validate against code and tests.

## 3. Claims to validate

Validate:
- all P10 issues marked fixed,
- all universal fixes that affect P10,
- all newly added tests,
- no new bypasses introduced,
- no new token/raw-bank leak introduced,
- no new duplicate-money path introduced.

Specific P10 claims:
- bank-created expenses go through transaction lifecycle,
- repeated sync/import is idempotent,
- provider transaction IDs are account/provider scoped,
- sync cursor advances only after durable page/item commit,
- import run/item ledger records partial failures,
- parser errors produce sanitized drop reasons,
- link/unlink/match is transactional,
- one bank transaction cannot create multiple expenses,
- one expense cannot be incorrectly linked to conflicting bank items,
- bank-created expenses preserve provenance/source link,
- recurring reconciliation runs where applicable,
- tokens encrypted at rest,
- tokens absent from logs/diagnostics/export,
- account identifiers masked,
- raw statement text obeys raw-storage policy at write time,
- AI bank parser uses prepared/redacted cloud payload,
- money sign/direction/currency normalization correct,
- parser date/locale behavior tested,
- bank workers use guard and run logging,
- bank workers blocked during restore,
- cancellation propagates,
- backup/export handles bank tables and secrets correctly,
- UI displays duplicate/ambiguous/privacy-denied states if touched.

## 4. Required validation steps

1. Build exact file inventory.
2. Trace runtime flows.
3. Compare code to `LEGAL_PATHS.md`.
4. Run targeted tests.
5. Review test assertions for real coverage.
6. Check direct DAO writes.
7. Check transaction lifecycle ownership.
8. Check worker guard/run logging.
9. Check token/security paths.
10. Check raw storage/cloud payload policy.
11. Check money/currency normalization.
12. Check parser edge cases.
13. Check restore/export/migration impact.
14. Check Hilt bindings.
15. Check UI if touched.

## 5. Required output

Produce:

# P10 Fixed-Claims Validation

## 1. Verdict
GREEN / YELLOW / RED

## 2. Claims table
| Claim | Source doc/PR | Validated? | Evidence | Remaining risk |

## 3. Regression search
| Area | Search/check performed | Result |

Include at least:
- direct `ExpenseDao` writes,
- provider transaction uniqueness,
- sync cursor updates,
- token/plaintext strings,
- raw bank text storage,
- cloud AI prompt paths,
- worker guard usage,
- write barrier usage,
- cancellation catches,
- money/currency normalization,
- backup/export inclusion.

## 4. Test validation
| Test | What it proves | Weakness/gap |

## 5. Contract audit
- restore barrier,
- worker guard/run logging,
- privacy/token/redaction,
- lifecycle ownership,
- money/currency,
- diagnostics/events,
- import/export/backup,
- DAO conflicts/timestamps,
- UI state if applicable.

## 6. Remaining issues
| ID | Severity | Status | Evidence | Required next action |

## 7. Production safety
- Is P10 production-safe?
- Highest-risk issue.
- Required fix before GREEN.
```