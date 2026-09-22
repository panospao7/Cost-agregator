# Cell P-02 Audit — Transaction lifecycle

## Provenance

**Status: PARTIAL.** Three discovery findings: P0=0, P1=1, P2=1, P3=1. Findings are not independently verified. Remaining DAO/provenance coverage is listed at the end; no clean-cell or completion verdict is claimed.

- Campaign: CA-2026-09-21
- Cell: P-02 — Transaction lifecycle
- Mode: AUDIT (static only)
- Auditor: direct astra session
- Audit date: 2026-09-21
- Pinned source commit: 37601232b9778170c57a656a245b199ab6d7d965 (`git rev-parse --short HEAD` = 37601232)
- Production diff check: `git diff --stat 37601232..HEAD -- app config scripts` empty at audit start
- Scope rule: production code read-only; only this report and campaign JOURNAL.md may be written

## Governing scope extracted
- Coverage row: P-02 Transaction lifecycle
- Segments: Core Expense Management; Utilities & Shared Helpers; Recurring Expenses
- Engine rows: MerchantNormalizer; TimeProvider/TimePeriodUtils; TransactionSideEffectDispatcher
- Legal-path headings: Expense Mutations; Lifecycle Events; Money / Currency
- Primary files: TransactionLifecycleCoordinator.kt; ExpenseDao.kt; DatabaseWriteBarrier.kt; TransactionSideEffectDispatcher.kt; domain/provenance/ (source-link + lineage integrity)
- Known/deferred IDs excluded from new findings: FRESH-P2-001..010; NEW-P2-016 (report only if clearly regressed/worse)

## Coverage checklist
- [x] Pin and production-diff reverified before finalization
- [x] Governing spec §2 known-debt/intended-behavior read
- [x] Governing spec §4 defect classes/finding schema/severity read
- [x] COVERAGE_MATRIX.md P-02 row read
- [x] LEGAL_PATHS.md: Expense Mutations, Lifecycle Events, Money / Currency extracted
- [x] CODEBASE_SEGMENTS.md: Segment 9 extracted
- [x] TransactionLifecycleCoordinator end-to-end
- [ ] ExpenseDao mutation/dedupe/event-related paths
- [ ] DatabaseWriteBarrier usage and bypass checks
- [x] TransactionSideEffectDispatcher post-commit behavior
- [ ] domain/provenance source-link + lineage writers and callers
- [ ] Caller traces and existing tests/guards checked
- [x] All 15 defect classes considered (coverage limits in final matrix)

## Findings

Finding records below: CA-P-02-001 (P3, latent backfill); CA-P-02-002 (P1, nested-transaction dispatch regression); CA-P-02-003 (P2, strict-source identity lost on edit).

## Covered files

See the incremental coverage checkpoint below, including ranges and truncation limits.

## Uncovered / limits
- Cell remains incomplete; see final coverage limits and next uncovered items below.

## Initial provenance checkpoint
- Agents invoked: direct astra session only (no delegated agents)
- Validation: none (static-only audit; no builds/tests/guards)

## Incremental coverage checkpoint (continuation)

Status: **PARTIAL — not a completed cell audit.** Source pin rechecked: HEAD=37601232; both committed and working-tree diffs against the pin under app/config/scripts are empty. The earlier tool outputs did contain filesystem/source reads; earlier chat claims that tools were unavailable were incorrect. This report records only observed source evidence.

All production paths below are relative to `app/src/main/java/com/yourname/expensetracker/` unless stated otherwise. Full-file reading budget reached; no more full production-file reads in this pass. Several large combined outputs were truncated; only visible ranges count as covered. The full DAO and all 28 provenance files have NOT been audited end-to-end.

| File | Coverage actually obtained | Checks / observations |
|---|---|---|
| `domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt` | End-to-end, successive excerpts 1–2589; initial truncated create block recovered at 354–503 | Required create event and source links inside Room transaction; duplicate preflight in transaction; update/delete snapshots freshly loaded; typed no-op/not-found branches; post-commit planning; bounded home-currency resolution; cancellation catches; source identities and validation |
| `data/database/dao/ExpenseDao.kt` | 1–230, 300–1059; mutation/query pattern scan across file | IGNORE insert and full-row update semantics; update column families; three-tier currency/type-aware duplicate SQL; ownership filtering split; not full DAO coverage |
| `data/backup/DatabaseWriteBarrier.kt` | 1–40, full | runWrite checks mode once then invokes block; no lease/drain lock. This alone is not promoted as new: call-site enforcement design debt is tracked as U-BARRIER-02 |
| `domain/transaction/lifecycle/TransactionSideEffectDispatcher.kt` | 1–68, full | Compatibility facade plans then runs; no transaction or durable queue added here |
| `domain/transaction/lifecycle/TransactionSideEffectPlanner.kt` | 1–420 requested, visible output partly truncated; method/idempotency-key scan through bulk factories | Category/business changes plan budget/anomaly; full changes may reconcile recurring; merchant count updates explicitly deferred by design; bulk clock captured once. Action failure strings and terminal handling require focused follow-up |
| `domain/sideeffect/PostCommitActionRunner.kt`, `PostCommitActionBatch.kt`, `PostCommitAction.kt` | Full | Interface, in-memory action closures, optional batch-local normalization only |
| `domain/sideeffect/PostCommitActionRunnerImpl.kt` | Full | Executes actions sequentially; action CE rethrows; event-writer catches catch Exception without CE exclusion; failure results counted and processing continues |
| `domain/sideeffect/PostCommitActionRunnerExtensions.kt` | Full | Best-effort wrappers rethrow CE, swallow ordinary failures |
| `domain/sideeffect/PostCommitSideEffectEvidenceService.kt` | Full | Outcome evidence and counters inspected; production wiring not exhaustively checked |
| `domain/sideeffect/SideEffectEventWriter.kt`, `CompositeSideEffectEventWriter.kt`, `DiagnosticSideEffectEventWriter.kt`, `TransactionSideEffectFailureEventWriter.kt` | Full | Composite rethrows CE; diagnostic/failure mirror paths traced one level; downstream transaction event sanitization not yet established |
| `data/backup/RestoreMaintenanceMode.kt` | Full | Modes, preferences, enter/exit, worker pause/reset. No fresh finding asserted from this shared P-07 surface |
| `domain/provenance/SourceLinkWriterImpl.kt` | Full 1–300 | Unique identity existence fast-path + IGNORE; HMAC external fields; barrier directly before insert; legacy and unknown fallback keys |
| `domain/provenance/PendingReviewSourceLinkServiceImpl.kt` | Partial due combined-output truncation | Factory → linkTarget loop; no owned transaction; caller transaction responsibility |
| `domain/provenance/PendingReviewSourceLinkPromoterImpl.kt` | Full; recovered separately after initial truncation | Transforms roles/status; preserves local IDs; drops external IDs/fingerprints and several run/account fields; promotes within caller transaction |
| `domain/provenance/SourceLinkBackfillWorker.kt` | Full | Helper, not CoroutineWorker; entry/per-expense barrier, CE rethrow, direct provenance inserts; suspicious notification cross-product identified for caller/debt checking |
| `domain/provenance/SourceLinkEventMetadataBuilder.kt` | Full | Attempt/created/duplicate/conflict metadata; no claim of blanket privacy safety |
| `domain/provenance/CreateExpenseSourceLinkMapper.kt` | Full | Explicit + legacy payloads; local-ID dedup; external-fingerprint-only maps to UNKNOWN; fallback opt-in |
| `domain/provenance/CreateExpenseSourceLinkRequirements.kt` | Full | Explicit sourceLinks bypass legacy-field requirements; source-specific null checks |
| `domain/provenance/SafeProvenanceMetadata.kt` | Full | Key filter permits custom_ prefix and does not validate values; no production leak asserted without caller evidence |
| `domain/provenance/SourceIdentityKeyFactory.kt`, `SourceLinkEnums.kt`, `SourceLinkPayload.kt` | Full | Identity string formats and data contracts |
| `domain/provenance/ImportSourceLinkPayloadFactory.kt` | Full | Explicit TODO documents unwired richer metadata factory; not reported as new debt |
| `data/database/dao/EntitySourceLinkDao.kt`, `data/database/entity/EntitySourceLink.kt` | Full | Unique (target type,target ID,source key) constraint correctly protects concurrent insert; FRESH-U-005 refutation respected; no FK/delete API, but historical provenance retention intent not yet resolved |
| `data/repository/ReviewQueueRepository.kt` | Selected create/promotion and manual-recovery excerpts | DB-only create, promotion failure abort, SOURCE_LINKED event, receipt linking nested inside approval transaction |
| `data/repository/NotificationProcessingPipeline.kt` | Selected review/duplicate provenance excerpts | Source-link calls within notification handling; not a fresh audit of P-01 |
| `domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt`, `ReceiptLinkService.kt` | Selected email/source-link and link/category dispatch excerpts | Shared P-03 surface; nested transaction dispatch concern remains unpromoted pending full outer-call analysis |
| `data/repository/ExportDataRepository.kt` | 161–230 excerpt | Provenance export read barrier present |
| `scripts/verify_source_provenance_boundaries.py` | Targeted rules/identity/constructor excerpts | Shape-based identity coverage and constructor ownership; does not establish correct relationships at runtime |

Governing docs extracted/read: campaign spec §2/§4; COVERAGE_MATRIX P-02 row; LEGAL_PATHS Expense Mutations (including blocking/resolver distinction), Lifecycle Events, Money / Currency; CODEBASE_SEGMENTS 9/32/39; registry P2 rows, universal/refuted/deferred rows; RP-00 D1–D14. Large maps were not read wholesale. Some later targeted rg searches returned matching map rows incidentally.

### Unpromoted investigative leads (not findings)
- ExpenseRepository may discard Result.failure from deleteExpense; inspect caller/UI/tests and old debt before assigning ID.
- SourceLinkBackfillWorker notification reconstruction appears to combine each notification expense with every pending review; inspect actual UI entry and debt before assigning ID.
- Side-effect event emission may swallow cancellation; inspect tests, downstream CE propagation, and RP-01 scope before assigning ID.
- Source-link absence on delete / fallback identity weakness / promotion hash loss require intent and reachability checks; no finding assigned merely from an absent FK or a method name.

## Finding CA-P-02-001

- **ID:** CA-P-02-001
- **Title:** Unwired provenance backfill would attach every pending notification review to each notification expense
- **Defect class:** 13 — Wiring / dead code (latent class-11 relationship corruption)
- **Severity:** P3
- **Evidence at pin:** `app/src/main/java/com/yourname/expensetracker/domain/provenance/SourceLinkBackfillWorker.kt`, `backfillNotificationLinks`, lines 215–256: after checking whether any RAW_NOTIFICATION link exists, loads all pending reviews, tests only whether the expense's source contains NOTIFICATION/SMS, and inserts CREATED_FROM / ACTIVE / isPrimary=true links for each review's rawNotificationId. `app/src/main/java/com/yourname/expensetracker/data/database/dao/PendingReviewDao.kt`, `getPendingUncapped`, lines 49–51 selects status=PENDING, not approved rows. `app/src/main/java/com/yourname/expensetracker/ui/screens/settings/SourceLinkBackfillViewModel.kt`, `startBackfill`, lines 35–46 invokes the helper, but production source search finds no caller of startBackfill and no use of this ViewModel elsewhere.
- **Impact path:** If wired, SourceLinkBackfillViewModel.startBackfill → runBackfill → backfillNotificationLinks → EntitySourceLinkDao.insert would claim unrelated notification sources created each historical expense. For two qualifying expenses and two unrelated pending raw notifications, four false source relationships can be inserted. The unique index only prevents repetition of each false relationship; it does not prove ownership. There is **no demonstrated current user-visible effect**, because the UI entry is absent.
- **Caller trace:** NONE-FOUND for a reachable UI/background entry. Intermediate source caller is SourceLinkBackfillViewModel.startBackfill:41; searches of app/src for SourceLinkBackfillViewModel/startBackfill/runBackfill found only that class, this helper and tests.
- **Existing tests/guards:** SourceLinkBackfillWorkerTest checks entry/per-expense barrier, CE propagation and ordinary failure isolation; default pending-review fixture is empty, and no notification-to-expense relationship assertion was found. `scripts/verify_source_provenance_boundaries.py` explicitly permits EntitySourceLink construction in SourceLinkBackfillWorker and checks constructor boundaries, not relationship correctness. WorkerGuardArchitectureGuardTest correctly excludes this non-CoroutineWorker helper; that exclusion is not itself a defect. No validation executed.
- **Cross-cell impact:** I-05 settings wiring; I-02 persisted provenance; P-01 notification/review lineage. Keep this helper unwired until its relationship algorithm is corrected.
- **Old-ID cross-refs:** none for relationship fabrication. U-BARRIER-02 covers the helper's barrier behavior only and is excluded from this finding. Searches of current registry, still-open/fresh ledgers, remediation docs and archived campaign material found no existing relationship-fabrication item.
- **Verification status:** discovery finding only; independent Phase 2 verification not performed.

### Refuted lead
ExpenseRepository discarding delete failure is **REFUTED**: ExpenseRepository.kt:417–419 and 437–446 invoke getOrThrow; ExpenseRepositoryTest.kt:216–239 pins missing-row propagation. FRESH-P2-001 is not restated as new.

## Finding CA-P-02-002

- **ID:** CA-P-02-002
- **Title:** RP-11 category side-effect hook runs inside the enclosing review-approval transaction
- **Defect class:** 15 — Fix-regression; mechanism is class 6 — Side-effect timing
- **Severity:** P1
- **Evidence at pin:** `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLinkService.kt`, `linkReceiptToExpense`, lines 188–193, 299–315 and 357–383: the service sets assignedCategoryId during its local transaction and immediately dispatches after its own runner returns. `app/src/main/java/com/yourname/expensetracker/data/database/RoomDomainTransactionRunner.kt`, `runInTransaction`, lines 24–45 simply delegates to the same AppDatabase.withTransaction; it is not an outermost-commit callback. `app/src/main/java/com/yourname/expensetracker/data/repository/ReviewQueueRepository.kt`, `approveReview`, lines 219–222, 306–342 and 360–363 calls linkReceiptToExpense inside an already-open transaction, then still updates review status and inserts user correction before that transaction closes. `app/src/main/java/com/yourname/expensetracker/domain/transaction/DefaultExpenseCategoryAssignmentService.kt`, `dispatchAssignedCategorySideEffects`, lines 59–73 explicitly requires the caller's transaction to have committed, but delegates immediately. `app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt`, `dispatchCategoryAssignmentSideEffects`, lines 2151–2166 plans CATEGORY_ONLY and runs it immediately.
- **Impact path:** Approve a receipt-backed review with no expense category and existing item categorizations → category assignment succeeds → nested Room scope exits while approval transaction stays open → budget/anomaly actions execute on uncommitted data → later updateStatus/userCorrectionDao.insert failure or cancellation rolls back approval. Nontransactional notifications may already have been posted and cannot be rolled back. The alert path is concrete: TransactionSideEffectPlanner.kt:66–68,180–186,211–222 → AnomalyAlertOrchestrator.checkAndAlert → postAnomalyAlert at AnomalyAlertOrchestrator.kt:171–198, conditional on normal anomaly/permission gates. The outer transaction is also held across these side effects, even on success.
- **Caller trace:** `app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModel.kt`, approveReview:406–410 / approveReviewWithEdits:442–466 → ReviewQueueRepository.approveReview → ReceiptLinkService.linkReceiptToExpense → DefaultExpenseCategoryAssignmentService.dispatchAssignedCategorySideEffects → TransactionLifecycleCoordinator.dispatchCategoryAssignmentSideEffects → planner/runner → budget/anomaly. Production DatabaseModule.kt:84–85 binds RoomDomainTransactionRunner.
- **Existing tests/guards:** ReceiptLinkServiceColumnScopeTest.kt:214–267 tests failure of the inner claim before dispatch, with no enclosing transaction. It does not test successful inner linking followed by outer rollback. CategoryAssignmentServiceBarrierTest.kt:183–310 exercises the separated assignment and dispatch contracts; ReviewQueueRepositoryTest.kt:36–51 mocks ReceiptLinkService/coordinator. No observed test covers the real nested chain. LEGAL_PATHS.md Expense Mutations promises post-commit effects; naming/lexical position after a local transaction cannot prove outer commit. No builds/tests/guards executed.
- **Cross-cell impact:** P-03 receipt linking; P-01 review approval; P-06 budget checks; E-02 anomaly alerts; I-02 transaction ownership.
- **Old-ID cross-refs:** NEW-P2-005, **regressed/worse timing behavior**, not a duplicate report of missing dispatch. RP-11's fix adds dispatch, but nesting violates its post-commit guarantee. Diff evidence: `git show cad0b664 -- .../ReceiptLinkService.kt` shows this post-runner dispatch block added in commit cad0b664b18921344fdf252fa33ff8bfc6649643 (ancestor of the pin). FRESH-P3-004's column-scoped receipt mutation fix is not being restated.
- **Verification status:** discovery finding only; independent Phase 2 verification not performed. Static control-flow proof; no claim of an observed runtime notification.

## Finding CA-P-02-003

- **ID:** CA-P-02-003
- **Title:** Editing a strict-source expense replaces the external identity used to recognize a replay
- **Defect class:** 4 — Idempotency / duplicates
- **Severity:** P2 (debug/stub bank route; conditional on replaying the same provider identity)
- **Evidence at pin:** `app/src/main/java/com/yourname/expensetracker/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt`, strictExternalDedupeKey:101–109 and createExpenseMutation:574–606 persist `idem:<source>:<external identity>` as dedupeKey; lines 633–659 deliberately skip fuzzy preflight for STRICT_EXTERNAL_ID and rely on the unique index. The same class's updateMerchant:1505–1535 unconditionally replaces this key with generateDedupeKeyWithType(amount,merchant,date,currency,type). updateExpense:1001–1024, updateType:1612–1641, updateTypeAndTransferDetails:1820–1883 and bulkUpdateMerchant:2284–2288 contain the corresponding content-key rewrite. `app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt`, updateMerchantAndKey:343–345 writes the replacement key. `app/src/main/java/com/yourname/expensetracker/data/database/entity/Expense.kt`:35–44 has unique rawNotificationId and dedupeKey indexes; bank-created rows have no rawNotificationId identity to provide a second guard.
- **Impact path:** A bank create persists external identity K → user renames its merchant to a valid distinct name → row now stores a content-derived key rather than K → a later request with the same provider transaction identity K has no unique conflict and no fuzzy preflight → a second expense can be created. Source-link uniqueness is scoped to target expense ID, so provenance links are not an alternate expense identity constraint. The defect is erasure of a previously accepted identity, independent of how well the provider ID itself is scoped.
- **Caller trace:** BankConnectionLifecycleCoordinator.syncConnection → BankApiIntegration.syncTransactions:405–409 → mapTransactionToExpense:659–687 supplies idempotencyKey and STRICT_EXTERNAL_ID → coordinator. Edit path: TransactionsViewModel.updateMerchant:506–516 → ExpenseRepository.updateExpenseMerchant:533–558 → coordinator.updateMerchant. BankApiIntegration is requireStubMode-gated (syncTransactions:210–214); D1 is respected. The mock generator embeds generated dates in IDs (generateMockTransactions:830–853), so ordinary successive UI syncs do not necessarily replay the same identity. This report claims the source contract failure on an identity replay, **not demonstrated release-bank duplicate creation**.
- **Existing tests/guards:** TransactionLifecycleCoordinatorConflictResolutionTest covers strict insert conflicts/no-fuzzy lookup; TransactionLifecycleCoordinatorUpdateTest uses ordinary test-key/stale-key fixtures. Focused scans of these and app/src/test/app/src/androidTest found no create-with-external-key → edit → replay test. Existing unique indexes protect only currently persisted values. Static review only, no tests run.
- **Cross-cell impact:** P-10 bank ingestion owns the reachable strict-source producer; I-05 transaction editor; I-02 identity constraints. This should be verified before real-provider release wiring.
- **Old-ID cross-refs:** none. FRESH-P10-007 covers provider/account identity scope at creation, whereas this finding loses a valid stored identity during editing. FRESH-P2-002 is the rawNotificationId conflict-resolution bug and does not protect bank rows. No matching edit/replay item found in current registry, still-open/fresh ledgers, remediation decisions, or targeted archived-ledger searches.
- **Verification status:** discovery finding only; independent Phase 2 verification and targeted runtime reproduction not performed.

## Final coverage assessment — all 15 defect classes

| Class | Checks performed / result within inspected scope |
|---|---|
| 1 Legal path | Traced repository delete/merchant editing, review DB-only creation, source-link writer, receipt category port and DI runner binding. Category side-effect entry is coordinator-owned, but its caller uses the wrong transaction boundary (-002). Not an exhaustive call-site census of all ExpenseDao methods. |
| 2 Barriers | Read barrier and maintenance-mode implementations, entry/runWrite placements, provenance insert guard. Deferred broad call-site/TOCTOU enforcement remains U-BARRIER-02/FRESH-U-004 context; no duplicate new finding asserted. Cancellation/restore interleavings not runtime-tested. |
| 3 Atomicity / TOCTOU | Expense insert + CREATED + source links are in the same transaction; update/delete snapshots loaded inside transaction. Identified nested Room transaction boundary underlying -002. Source-link exists/insert race is protected by composite UNIQUE/IGNORE (FRESH-U-005 refuted). Full-row update stale-caller overwrite remains unassessed. |
| 4 Idempotency | Currency/type/time-window SQL and unique identities inspected; strict-source identity erasure recorded as -003. Did not re-report preflight-skip semantics (FRESH-P2-004), ownership filtering intent (FRESH-P2-008), or account-scope identity debt (FRESH-P10-007). |
| 5 Cancellation | Coordinator bestEffortEvent/conversion/update/delete catches and wrappers rethrow CE. Runner event-emission catches deserve follow-up; guard has a PostCommitActionRunnerImpl.kt allowance labeled FALSE_POSITIVE_CE_RETHROW / MIT-034 at CancellationSafetyArchitectureGuardTest.kt:126. Not promoted as new without resolving the existing exemption/debt. |
| 6 Side-effect timing | Direct create returns a batch to DB-only callers; standalone execution follows the local transaction. Followed receipt review nesting and recorded -002. In-memory batch keys alone are not evidence of exactly-once execution; missing durable outbox is not newly asserted as a bug without intended-behavior reconciliation. |
| 7 Money / currency | Create/update use convertAsOf(expense.date), preserve CE, resolveHomeCurrency and sentinel behavior. DAO duplicate checks compare currency and amount tolerance; no cross-currency aggregate pass completed over the DAO's unread half. NEW-P2-016 is excluded as tracked. |
| 8 Time | Coordinator uses TimeProvider; dedupe query half-open bounds computed as date±window with end+1; bulk planner captures one now value. No clock-overflow/timezone proof across all DAO consumers; no clean-cell claim. |
| 9 Privacy | Source writer hashes external fields and uses key-filtered metadata; inspected raw lifecycle snapshots/reasons and side-effect failure mirrors. Downstream sanitization, retention and custom_ metadata call sites are incomplete; no new privacy finding promoted from names/comments alone. |
| 10 Worker hygiene | SourceLinkBackfillWorker is a Singleton helper, not CoroutineWorker. Verified missing UI caller; correctly do not demand WorkManager registration/WorkerExecutionGuard for it. Entry/per-expense barrier and CE behavior read, tests inspected statically. |
| 11 Data integrity | Source-link composite key prevents duplicate rows but cannot prove source ownership; latent cross-product in -001. Promotion drops hash/run fields and generic fallbacks remain investigation leads; consumer/intent proof incomplete. Polymorphic source links lack FK/delete APIs, but historical ledger retention intent was not established, so absence alone is not a finding. |
| 12 Error handling | Delete failure propagation refuted as a candidate; getOrThrow is present. Source writer failures roll back coordinator creation. Source-link result/exception handling across every factory/caller remains incomplete. |
| 13 Wiring | Traced dispatcher facade, helper/VM backfill caller, provenance DI and Room runner binding. Backfill UI is absent (-001); ImportSourceLinkPayloadFactory's unwired richer metadata is explicitly documented and excluded as existing deferred work. |
| 14 Test correctness | Inspected targeted test bodies for missing-row propagation and inner-link rollback, plus focused scans of conflict/update/backfill/runner tests. Inner rollback test does not exercise nested successful link followed by outer rollback; no test run or whole-suite correctness verdict. |
| 15 Fix regression | Read cad0b664's actual ReceiptLinkService diff and current nested caller; -002 is a newly introduced consequence of NEW-P2-005 remediation. Did not diff all remediation waves; other regression attribution remains incomplete. |

## Additional covered excerpts / finding support

- `data/repository/ExpenseRepository.kt`: 403–460, 533–558, both delete overloads and merchant coordinator route.
- `ui/screens/transactions/TransactionsViewModel.kt`: 463–501 and updateMerchant caller scan at 506–516; delete success/error reporting.
- `domain/bank/BankApiIntegration.kt`: 360–426, 631–688, 830–867; grep of sync entry/stub guard; request identity mapping and demo reachability limitations.
- `domain/bank/BankConnectionLifecycleCoordinator.kt`: syncTransactions invocation located at 155; no full coordinator audit.
- `ui/screens/settings/SourceLinkBackfillViewModel.kt`: 30–49; app/src caller search.
- `domain/provenance/SourceLinkBackfillWorker.kt`: 208–260 re-read with line numbers for -001.
- `data/database/dao/PendingReviewDao.kt`: 49–51 query contract via line-numbered search.
- `domain/receipt/lifecycle/ReceiptLinkService.kt`: 160–217, 292–319, 351–389; actual cad0b664 diff reviewed.
- `data/repository/ReviewQueueRepository.kt`: 203–229, 300–352, 356–379; outer transaction and later fallible writes.
- `domain/transaction/DefaultExpenseCategoryAssignmentService.kt`: 59–79; explicit caller-commit precondition and immediate delegation.
- `data/database/RoomDomainTransactionRunner.kt`: 24–45; Room nesting implementation.
- `domain/transaction/lifecycle/TransactionSideEffectPlanner.kt`: 59–69, 176–186, 211–226 re-read for -002.
- `domain/alerts/AnomalyAlertOrchestrator.kt`: 168–205; persisted alert then notification side effect.
- `data/database/entity/Expense.kt`: 23–54; identity constraints.
- Tests (under `app/src/test/java/com/yourname/expensetracker/`): `data/repository/ExpenseRepositoryTest.kt`:200–239; `domain/receipt/lifecycle/ReceiptLinkServiceColumnScopeTest.kt`:209–267; focused symbol/assertion scans of `domain/provenance/SourceLinkBackfillWorkerTest.kt`, `domain/transaction/lifecycle/TransactionLifecycleCoordinator{Update,ConflictResolution}Test.kt`, `domain/sideeffect/PostCommitActionRunnerTest.kt`, `domain/transaction/category/CategoryAssignmentServiceBarrierTest.kt`, `data/repository/ReviewQueueRepositoryTest.kt`.
- Guards: `architecture/CancellationSafetyArchitectureGuardTest.kt`:126 allowance and expiry-related searches; WorkerGuardArchitectureGuardTest helper exclusion; provenance boundary rule excerpts. Guard execution: NONE.

## Known-debt / intended-behavior disposition

No FRESH-P2-001..010 or NEW-P2-016 item is restated as a fresh standalone finding. NEW-P2-005 is referenced only for the demonstrably different regression -002. FRESH-U-005's unique-index refutation stands. D1 bank debug/stub gating is respected, D2 email staged wiring and D3 debug import are not defects. Import provenance round-trip omissions are documented in RP-19's matrix and not newly reported. Merchant count-on-update deferral is not reported. Metadata/snapshot retention, pending-review nonfatal provenance handling and barrier design debt were not promoted without a new distinct claim.

## Uncovered areas and process limitations

This is **not an end-to-end completion of P-02**. The earlier continuation expanded into small helper files and exceeded the requested 20-full-file ceiling; that was a process error. This final continuation stopped full-file reads and used only focused evidence/caller/test/debt excerpts. Report persistence also lagged during the earlier pass; the coverage checkpoint and findings above now preserve the observed work. Neither limitation is concealed by a clean-cell verdict.

First remaining coverage item: **ExpenseDao.kt**, recover unread ranges 231–299 and 1060–2732 (including blocking query bodies, remaining mutation APIs, aggregates and their callers). The initial whole-file pattern scan was not a substitute for reading these bodies. Then complete TransactionSideEffectPlanner.kt's truncated middle/bulk factories and provenance factories/callers. If resuming in a compacted/new context, re-read this report, then freshly read each source excerpt before citing it.

Provenance folder was enumerated as 28 files. Not substantively read in this pass: BankSourceEventMetadataBuilder.kt, BankSourceLinkPayloadFactory.kt, NotificationSourceLinkPayloadFactory.kt, PendingReviewSourcePayloadFactory.kt, ReceiptSourceLinkPayloadFactory.kt. PendingReviewSourceLinkServiceImpl.kt's combined output was truncated; recover its complete mutation/result loop. SourceLinkQueryService.kt had only method/query hits, not a complete read/barrier/caller audit. Declaration-only files (DuplicateSourceLinkPolicy.kt, PendingReviewPromotionResult.kt, PendingReviewSourceContext.kt, PendingReviewSourceLinkPromoter.kt, PendingReviewSourceLinkResult.kt, PendingReviewSourceLinkService.kt, SourceLinkFallbackPolicy.kt, SourceLinkWriteException.kt, SourceLinkWriter.kt, SourceLinkWriteResult.kt) were inventoried or referenced, not independently fully inspected; pure data/interface declarations need not consume the remaining full-read budget but cannot be claimed audited.

Additional limits: exact engine-row sections and Segment 7 intent were not extracted; no exhaustive inventory-to-cell reconciliation; no full ExpenseDao direct-writer census; no complete source-link consumer/retention audit; no all-wave diff audit; no independent verifier. Cancellation event-emitter behavior, lost promotion identity fields, unknown fallback identity keys, strict replay reproduction, and receipt outer-rollback reproduction are explicit follow-ups. Earlier failures of `nl`/one malformed rg path were tooling errors only; subsequent PowerShell line-numbered excerpts and correct paths supplied cited evidence. No validation was attempted.

## Final summary

- Findings: **3 discovery findings** — CA-P-02-001 P3; CA-P-02-002 P1; CA-P-02-003 P2.
- P0: 0; P1: 1; P2: 1; P3: 1.
- Status: **PARTIAL**, pending remaining coverage and independent Phase 2 verification.
- No production, test, configuration, baseline or agent files modified by this audit. Only campaign report/journal writes.
- Builds/tests/guards: **NOT RUN**, as required for AUDIT static-only mode.

## Final provenance
- Auditor: **direct astra session**, astra-cell-auditor (direct session); cell **P-02**; campaign **CA-2026-09-21**.
- Agents invoked: none; direct audit explicitly requested. No separate verifier or reviewer verdict is claimed.
- Timestamp: **2026-09-21T18:55:25Z**.
- Pinned source: **37601232b9778170c57a656a245b199ab6d7d965**.
- Final HEAD short: **37601232**.
- Final `git diff --stat 37601232..HEAD -- app config scripts`: **EMPTY**.
- Additional final `git diff --stat 37601232 -- app config scripts`: **EMPTY** (production working tree also matches).
- Outcome: **PARTIAL; 3 discovery findings; no builds/tests/guards run; independent verification pending**.
