# Legal Paths — Architecture Law

> **Last updated:** 2026-10-24 (app-lock-privacy-mode 1a: NOTIFICATION PRESENTATION, DEEP LINKS and APP LOCK SETTINGS WRITE PATH sections added; DISPATCH reminder and Anomaly Alerting steps corrected). Earlier: 2026-10-13 (schema/savings delta verified against code: DB v151; prior guarded-worker/CI verification retained)
>
> **Purpose:** Define the ONE allowed implementation path for each major operation.  
> **Rule:** Any code that uses a different path is a bug, regardless of whether it "works."  
> **Enforcement:** CI static guard suite (`scripts/ci/run_static_guard_suite.py`, registry in `scripts/ci/guard_registry.py`; policy in `docs/ci/guard-policy.md`), DB ownership policy (`config/guards/db_ownership_policy.yml`), in-repo architecture guard tests (`app/src/test/java/com/yourname/expensetracker/architecture/`), `DeprecationLevel.ERROR`, and contract tests.

---

## Savings Sweep Acceptance

`SavingsGoalsViewModel.acceptSweepRecommendation()` rechecks recommendation currency, rates, and goal snapshots, then delegates to `SavingsSweepAcceptanceCoordinator.accept()`. The coordinator checks `DatabaseWriteBarrier`, validates provenance/snapshots, and commits goal CAS updates, pending-to-accepted plans, and contribution-ledger rows in one Room transaction. Rejection or cancellation rolls back the batch; cancellation propagates.

The durable ledger is the idempotency authority, including after optional plan cleanup. Sweep success must not dual-write authoritative history to DataStore. Validated legacy DataStore events remain read-only on history reads; existing manual contribution recording is unchanged. The ledger is exact-tier backup data. Pre-v150 backups acquire an exact zero-ledger expectation after migration, before staged and live verification.

The goal-independent `savings_sweep_month_acceptance` marker is the monthly consumption authority. The coordinator inserts it in the same Room transaction as the balances, plans and ledger. It has no goal foreign key and survives deletion of all goals. Both ledger and marker are exact-tier backup data. The v150→151 migration conservatively consumes the upgrade/restore month because missing legacy history cannot prove that month was unused; older supported backups gain an exact one-marker expectation after migration. Fresh installs have no migration marker.

Direct writes outside the five exact coordinator callable/DAO contracts in `config/guards/db_ownership_policy.yml` are forbidden. No blanket owner allowance.

## Expense Mutations

```
CREATE expense:
  Any source (UI/notification/receipt/email/bank/import/group)
    → TransactionLifecycleCoordinator.createExpenseStandaloneV2() or
      createExpenseDbOnlyV2() (createExpense()/createExpenseStandalone()/
      createExpenseDbOnly() remain as deprecated delegates to the V2 paths)
    → ExpenseDao.insertAtomic() [ONLY from coordinator]
    → TransactionEvent with LifecycleEventType.CREATED
    → Post-commit side effects via TransactionSideEffectPlanner → TransactionSideEffectDispatcher

FORBIDDEN:
  ❌ ExpenseDao.insert() from any repository directly
  ❌ ExpenseDao.insertAll() outside debug/migration
  ❌ Any expense insert without TransactionEvent (LifecycleEventType.CREATED)

ENFORCEMENT:
  • Every direct ExpenseDao mutation call site must match an entry in
    config/guards/db_ownership_policy.yml (exact class + method + DAO +
    operation contract) or a structural exception — enforced by
    scripts/verify_db_access_boundaries.py (ratchet mode in CI).
  • Direct ExpenseDao mutation methods are annotated @RestrictedExpenseDaoMutation
    (opt-in); ExpenseDaoMutationAccessTest provides hard CI enforcement.
  • Legal writers wrap mutations in canonical write-barrier scopes —
    writeBarrier.checkWritesAllowed(...) at entry and writeBarrier.runWrite(...)
    around database.withTransaction. (The former ExpenseWriteStore facade was
    removed as a dead layer — GR-14u35.)
```

```
UPDATE expense:
  → TransactionLifecycleCoordinator.updateCategory/updateMerchant/updateType/etc.
  → TransactionEvent with LifecycleEventType.UPDATED
  → Post-update side effects via TransactionSideEffectPlanner.planUpdated()
  → Full-row update re-snapshots against current H (TransactionLifecycleCoordinator.updateExpense
    recomputes baseAmount/baseCurrency/exchangeRateUsed; see "SNAPSHOT rounding" under Money / Currency)

FORBIDDEN:
  ❌ ExpenseDao.update() from repositories directly
  ❌ ExpenseDao.updateCategory() outside coordinator
  ❌ Any expense update without TransactionEvent (LifecycleEventType.UPDATED)
```

```
DELETE expense:
  → TransactionLifecycleCoordinator.deleteExpense(id)
  → Loads snapshot INSIDE transaction
  → TransactionEvent with LifecycleEventType.DELETED
  → Post-delete side effects via TransactionSideEffectPlanner.planDeleted()

FORBIDDEN:
  ❌ ExpenseDao.delete() from repositories directly
  ❌ Loading snapshot outside the delete transaction
```

```
DUPLICATE BLOCKING vs OWNERSHIP RESOLUTION (RP-11):
  Two deliberately separate ExpenseDao query families exist (P2-008 11c + RP-11 FIX 2):

  BLOCKING family — collision preflights, intentionally INCLUDES isNotMine rows:
  → existsByMerchantKeyInRangeCurrencyAware / existsByMerchantKeyPrefixInRangeCurrencyAware /
    existsByMerchantInRangeCurrencyAware (EXISTS prechecks behind isDuplicateCurrencyAware())
  → getBlockingCandidateByMerchantKeyInRangeCurrencyAware /
    getBlockingCandidateByMerchantKeyPrefixInRangeCurrencyAware /
    getBlockingCandidateByMerchantInRangeCurrencyAware (full-row candidates, no ownership filter)
  → findBlockingDuplicateIdCurrencyAware() — @Transaction, returns the colliding id or null
  → Matching (all tiers): exact merchantKey → prefix containment (both keys LENGTH >= 8,
    mirroring DuplicateDetectionPolicy.MIN_MERCHANT_KEY_PREFIX_LENGTH) → raw merchant;
    window [date - windowMs, date + windowMs + 1) (DUPLICATE_WINDOW_MS = 5 min),
    amount +/- AMOUNT_TOLERANCE (0.01), currency case-insensitive, UNKNOWN type matches any
  → Why include not-mine: a not-mine row occupying the target identity must abort the
    write; using the resolver here would let the write slip past the preflight and die on
    the raw dedupeKey unique index (unique Index on dedupeKey; insertAtomic
    OnConflictStrategy.IGNORE returns -1 on collision)

  RESOLVER family — ownership-scoped reads, SQL filters isNotMine = 0:
  → getDuplicateCandidateByMerchantKeyInRangeCurrencyAware /
    getDuplicateCandidateByMerchantKeyPrefixInRangeCurrencyAware /
    getDuplicateCandidateByMerchantInRangeCurrencyAware; findDuplicateIdCurrencyAware()
    — the fuzzy resolver must NEVER return a not-mine row; also feeds the
    suggestion/import candidate paths

  COLLISION PREFLIGHT call sites — all five run findBlockingDuplicateIdCurrencyAware()
  inside database.withTransaction (TOCTOU-safe) and abort with a typed
  DuplicateUpdateException when the colliding id differs from the target id:
  → updateExpense (key-fields-changed branch)
  → updateMerchant
  → updateType
  → updateTypeAndTransferDetails (preflight runs whenever the recomputed dedupeKey
    differs from the stored key)
  → bulkUpdateMerchant (preflights EVERY target key inside the transaction BEFORE any
    write; a collision outside the rename set aborts everything → Result.failure with
    the controlled BulkMerchantRenameFailure.MERCHANT_RENAME_DUPLICATE reason — no row
    written, no event, no dispatch; zero matching rows is a successful no-op with no
    event and no dispatch)

  Receipt-side atomic assignment:
  → ReceiptLinkService.linkReceiptToExpense() runs link insert + legacy field +
    warranty/return/item propagation + ReceiptEvent inside one DomainTransactionRunner
    transaction, with a fresh in-transaction receipt read; the AUTO_MATCH path uses the
    atomic compare-and-set ScannedReceiptDao.claimForAutoMatch — a 0-row claim throws
    ReceiptAlreadyClaimedException and rolls the link back; the category assignment
    committed inside the transaction dispatches its side effects only AFTER commit

FORBIDDEN:
  ❌ Using the resolver family (findDuplicateIdCurrencyAware) for mutation collision
     preflights or post-blocking duplicate audit lookups
  ❌ Adding an isNotMine filter to the blocking family
  ❌ Collision checks outside the coordinator transaction (TOCTOU)
```

---

## Receipt Mutations

```
PROCESS receipt (camera/gallery/file/PDF):
  → ReceiptLifecycleCoordinator.processReceiptInput()
  → ReceiptRepository.processReceipt() [OCR/parse + draft insert via
    ReceiptInsertResolver/ReceiptRecordWriter with pre-OCR image-hash dedup;
    coordinator owns lifecycle enrichment + events + side effects]
  → Coordinator owns: insert + metadata + fingerprints + event + side effects

CREATE expense FROM receipt:
  → ReceiptLifecycleCoordinator.createExpenseAndLinkReceipt()
  → database.withTransaction { createExpenseDbOnlyV2() + linkService.link() }
  → Collects post-commit actions and dispatches them after commit
  → Throws on link failure → rollback

LINK/UNLINK receipt:
  → ReceiptLinkService.linkReceiptToExpense() / unlinkReceiptFromExpense()
  → Owns: join table + legacy field + warranty/return/itemCategorization + event

FORBIDDEN:
  ❌ ScannedReceiptDao.insert() outside coordinator/repository
  ❌ ReceiptRepository.linkReceiptToExpense() (deprecated)
  ❌ Direct ScannedReceipt.expenseId update
  ❌ Any receipt mutation without ReceiptEvent
```

```
MATCH receipt (suggest/approve/reject/clear):
  → ReceiptMatchLifecycleService.saveMatchSuggestion() / rejectAllSuggestions()
    / clearMatchForReceipt()
  → Approving (accepting) a suggestion is a LINK: ReceiptLinkService.linkReceiptToExpense()
    (ReceiptMatchLifecycleService has no approveMatchSuggestion method;
    ReceiptRepository.approveMatchSuggestion() is permanently disabled)
  → Each operation: DatabaseWriteBarrier check → withTransaction → ReceiptEvent
  → Events: MATCH_SUGGESTED / MATCH_APPROVED / MATCH_REJECTED / MATCH_CLEARED

AUTO-MATCH receipt (ReceiptMatchingWorker, periodic + manual runOnce):
  → ReceiptMatchLifecycleService writes durable events for every outcome:
      MATCH_ATTEMPTED / MATCH_NOT_FOUND / MATCH_SKIPPED_DOCUMENT_TYPE / AUTO_MATCH_LINK_FAILED
  → Concurrency invariant: per-receipt atomic claim ScannedReceiptDao.claimForAutoMatch
    (conditional UPDATE WHERE matchStatus IN ('UNMATCHED','SUGGESTED')) is the
    load-bearing overlap guard — concurrent periodic+manual runs cannot double-link.
    WorkerLeaseRegistry is a drain/registry mechanism, NOT mutual exclusion per worker.

FORBIDDEN:
  ❌ ReceiptRepository.saveMatchSuggestion() [DeprecationLevel.ERROR]
  ❌ ReceiptRepository.rejectAllSuggestions() [DeprecationLevel.ERROR]
  ❌ ReceiptRepository.clearMatchForReceipt() [DeprecationLevel.ERROR]
  ❌ Any match mutation without ReceiptEvent
  ❌ Relying on WorkerLeaseRegistry for auto-match mutual exclusion
```

```
PERSIST receipt structured data (RP-12 12b P3-007):
  → ReceiptStructuredDataPolicy.persistedItems() — the ONLY persisted
    representation of receipt line items
  → Single RawStorageMode transformer applied BEFORE the receipt row insert:
    STORE_RAW passes the parser JSON through unchanged; STORE_REDACTED
    persists the typed RedactedReceiptItem projection and drops the parsed
    merchant; STORE_METADATA_ONLY / DO_NOT_STORE persist no item JSON and
    no parsed merchant
  → Serialization failure yields null — most restrictive, fail-closed

CLEAN UP uncommitted receipt assets (RP-12 12c P3-008):
  → AssetCleanupCoordinator.cleanupUncommittedAsset()

FORBIDDEN:
  ❌ Persisting receipt item JSON outside ReceiptStructuredDataPolicy
```

```
DEBUG EXPORT receipt data:
  → ReceiptDebugExporter.debugReceipt() / exportParserDebugData()
  → Writes DiagnosticEvent (ALLOWED/DENIED with reason code)
  → Image paths redacted by default (includeImagePath=false)

FORBIDDEN:
  ❌ ReceiptRepository.debugReceipt() [DeprecationLevel.ERROR]
  ❌ ReceiptRepository.exportParserDebugData() [DeprecationLevel.ERROR]
  ❌ Exporting receipts without privacy consent check
  ❌ Including raw image paths without explicit consent
```

---

## Email Receipt Parsing

```
PARSE email receipt (RP-18):
  → EmailReceiptIngestionService.processEmailReceipt() — thin parser/delegate, owns NO mutation
  → DatabaseWriteBarrier check → Semaphore(3) bounded concurrency → EMAIL DiagnosticEvents
  → detectProvider(sender, subject, body): parser canParse() in fixed order amazon →
    uber → apple, then sender-domain fallbacks, then body-domain fallbacks; provider
    "unknown" tries all parsers in the same amazon → uber → apple order (there is NO
    pluggable parser registry — the order is hardcoded in the service)
  → Parser set (data/email/provider/): AmazonReceiptParser / UberReceiptParser /
    AppleReceiptParser, all extending BaseEmailParser (EmailReceiptParser.kt);
    canParse() is sender-gated for all three (subject/body only corroborate)

PARSING CONTRACT (as implemented, RP-18 18-A):
  → Total extraction is line-anchored, specific-labels-first: per-parser specific labels
    (e.g. Amazon "order total" / "grand total") → bare "total" LAST → any line containing
    a word-bounded "total" (never "Subtotal"; deliberately no trailing boundary so the
    "Totals:" recovery spelling also matches)
  → Total number grammar is strict: grouped thousands (optional cents) or a plain
    two-decimal number — bare integers ("Total items: 3") and VAT percentages ("19%")
    never match as totals
  → Unit-price and summary contexts are excluded: "2 x 5,00 €" / "45,90 x 2" /
    "2 @ $5.00" quantity×price forms and aggregate rows ("3 items, total 45,90") never
    become totals or line items
  → Currency resolution precedence: symbols (€ → EUR, £ → GBP, $ → USD, fixed priority)
    → word-bounded 3-letter ISO codes (conflicting codes fail) → per-parser trusted
    domain map (amazon.com→USD, amazon.co.uk→GBP, amazon.de/fr/it/es→EUR;
    apple.com/appstore.com→USD; Uber ships an EMPTY map — "uber.com" is not a currency
    signal). Bare two-letter country tokens are deliberately NOT signals.
    Unresolved → skip; NO default currency is ever guessed
  → Date: extracted date, falling back to receivedAt; amount must be > 0

PARSE OUTCOMES:
  → EmailParseOutcome sealed: Parsed / Skipped(EmailParseSkipReason) — controlled,
    payload-independent constants only: PARSE_FAILED, CURRENCY_UNRESOLVED
  → parse() keeps the stable null-on-failure contract (this is what the ingestion
    service consumes); parseWithOutcome() carries the typed skip reason

LEGAL PATH:
  → Parsed data funnels into ReceiptLifecycleCoordinator.processEmailReceipt() — the
    ONLY mutation path (receipt save + expense creation + linking + events + side
    effects); the service has NO inline fallback and never dispatches side effects
  → Pre-delegation dedup fingerprint: sha256(provider + normalized merchant +
    amount(2dp) + currency + senderDomain + 1-hour date bucket + orderNumber)
  → messageId is passed to the coordinator only as an HMAC-SHA256 prefix hash, never
    plaintext; HMAC failure fails closed (ParseError, no plaintext fallback)

DIAGNOSTICS:
  → Pipeline EMAIL DiagnosticEvents at front_door / provider_detection / parser /
    validation / outcome / dedupe / coordinator / ingestion stages
  → Controlled reason codes: RESTORE_BLOCKED, PARSER_FAILED (null parse — parser-level
    PARSE_FAILED and CURRENCY_UNRESOLVED both surface here), VALIDATION_FAILED,
    DUPLICATE, UNKNOWN_ERROR
  → messageId/sender recorded hashed only

TESTS:
  → app/src/test/java/com/yourname/expensetracker/data/email/provider/
    (EmailReceiptParserTest, AmazonReceiptParserTest, AppleReceiptParserTest,
    UberReceiptParserTest)
  → app/src/test/java/com/yourname/expensetracker/data/email/
    (EmailReceiptIngestionServiceTest, EmailReceiptIngestionServiceTransactionTest)
  → app/src/test/java/com/yourname/expensetracker/scenarios/EmailReceiptPipelineScenarioTest

FORBIDDEN:
  ❌ Email receipt mutation outside ReceiptLifecycleCoordinator.processEmailReceipt()
  ❌ Defaulting or guessing a currency when no trusted signal exists (must skip)
  ❌ Bare two-letter country tokens as currency signals (removed by design)
  ❌ Persisting or logging raw email bodies, senders or plaintext messageIds
```

---

## Recurring Rule Mutations

```
CREATE rule:
  → RecurringRuleLifecycleCoordinator.createRule()
  → Atomic: inserts rule + generates 12 months of occurrences + reminders + planned rows
  → DatabaseWriteBarrier check + durable lifecycle event

UPDATE rule:
  → RecurringRuleLifecycleCoordinator.updateRule()
  → Atomic: updates rule + reconciles occurrences in single transaction
    (RP-04 A2 reconcileUpdateInCurrentTransaction; fixed-anchor semantics,
    golden no-double-count)
  → DatabaseWriteBarrier check + durable lifecycle event

ACTIVATE rule:
  → RecurringRuleLifecycleCoordinator.activateRule()
  → Atomic: activates + generates future state in single transaction

DEACTIVATE rule:
  → RecurringRuleLifecycleCoordinator.deactivateRule()
  → Atomic: deactivates + DELETES (not cancels) open PLANNED occurrences + planned rows + suppresses reminders
  → Clean regeneration on reactivation (no CANCELLED rows to skip)

DELETE rule:
  → RecurringRuleLifecycleCoordinator.deleteRule()
  → Atomic: deletes reminders + planned + occurrences + rule + lifecycle event

GENERATE occurrences:
  → RecurringLifecycleCoordinator.generateOccurrences()
  → Uses OccurrenceGenerationOptions (controls reminder creation, windows, past-due allowance)
  → Rejects inactive rules
  → Terminal statuses (PAID, CANCELLED, SKIPPED, MISSED, IGNORED) never auto-downgraded
  → RecurringOccurrenceMaterializer.materializeInCurrentTransaction() for use
    inside existing transactions

LINK expense to occurrence:
  → RecurringLifecycleCoordinator.linkExpenseToOccurrenceDetailed()
  → Returns RecurringExpenseReconcileResult (Linked/Unlinked/Relinked/UpdatedLinkedSnapshot/NoMatch/Skipped/Error)
  → Atomic conditional claim (WHERE status=PLANNED AND linkedExpenseId IS NULL)
  → Fulfills planned + suppresses reminders

LINK expense to a SPECIFIC occurrence (R21 S1):
  → RecurringLifecycleCoordinator.linkExpenseToSpecificOccurrence(occurrenceId, expenseId, source) -> SpecificLinkResult (shares claimLinkSuppressInWrite with the automatic link; bounded `source` code, one occurrence per expense)

MARK-PAID / ALREADY-RECORDED a recurring bill (R21 S2):
  → ManualRecurringExpenseViewModel → MarkRecurringBillPaidUseCase.invoke(ruleId, seenNextDate) / skip(ruleId, seenNextDate)
    (per-rule Mutex; seenNextDate re-validated under the lock; target = ONLY the open occurrence due on the seen day, no stale fallback; an on-day occurrence that is not open only advances the date; IRREGULAR rules have no mark-paid action)
  → MARK-PAID: TransactionLifecycleCoordinator.createExpenseDbOnlyV2() (STRICT_EXTERNAL_ID key `recurring_paid:<occurrenceKey>`, dated on the occurrence due date)
    → RecurringLifecycleCoordinator.linkExpenseToSpecificOccurrence()
    → planned post-commit batch (mutation.postCommitActions) run once via PostCommitActionRunner ONLY for a Created expense, AFTER the link
    → RecurringRuleLifecycleCoordinator.advanceNextDate() (nextDate never decreases)
  → ALREADY-RECORDED: RecurringLifecycleCoordinator.skipOccurrence() → updateOccurrenceStatus(SKIPPED, USER_SKIPPED)
    (one transaction: status SKIPPED + projected planned_expenses row CANCELLED + open reminder deliveries suppressed; no expense created)
    → RecurringRuleLifecycleCoordinator.advanceNextDate()
  Known limitation: legacy rules keep their stored currency (no data repair); if the process dies between expense commit and the side-effect batch, the idempotent retry resolves to DuplicateSkipped and does not re-run the batch. Narrow LINK_REJECTED-after-Created race: if the occurrence stops being open between the target read and the link, the use case returns Failed(MARK_PAID_LINK_REJECTED) and an unlinked RECURRING_GENERATED expense may remain (the user can delete it). Pre-R21 stale PLANNED occurrences (due before the rule's nextDate) are not paid by mark-paid; they remain as overdue planned rows. After the expense commit the link and the post-commit batch run NonCancellable (the remaining crash window is process death only).
  Known limitation (a): if no occurrence exists on the seen day (time-zone change after generation, or a nextDate on a day without a scheduled occurrence) even after one horizon top-up, mark-paid returns AdvancedOnly and creates no expense; the original row stays an overdue PLANNED.
  Known limitation (b): after LINK_REJECTED an unlinked RECURRING_GENERATED expense may remain and the UI shows the generic error. If automatic matching linked that expense to another same-day same-merchant rule, retries keep returning LINK_REJECTED; the user's way out is "Already recorded" (and deleting the extra expense).

COLLAPSE stale due reminder deliveries (R21 S1):
  → BillReminderWorker → RecurringLifecycleCoordinator.collapseStaleDueDeliveries(now) (single transaction, cancelOpenDueDeliveriesByIds; never touches CLAIMED/upcoming; writes one STALE_DUE_DELIVERIES_COLLAPSED summary event with bounded counts when something was cancelled; a collapse failure does not block dispatch)

TOP UP rolling horizon (R21 S1/S3b):
  → RecurringHorizonWorker → RecurringRuleLifecycleCoordinator.topUpHorizon(ruleId, now) (idempotent; bounded 12-month catch-up; excludes already-linked expenses; RULE_HORIZON_TOPPED_UP critical event only when rows inserted)

UNLINK expense from occurrence:
  → RecurringLifecycleCoordinator.unlinkExpenseFromOccurrenceDetailed()
  → Returns RecurringExpenseReconcileResult
  → Reopens PLANNED occurrence status

UPDATE occurrence status:
  → RecurringLifecycleCoordinator.updateOccurrenceStatus(occurrenceId, RecurringOccurrenceStatus, reason)
  → Uses RecurringOccurrenceTransitionPolicy.requireAllowed() for validation
  → Typed RecurringOccurrenceStatus enum (PLANNED, PAID, SKIPPED, MISSED, CANCELLED, IGNORED)
  → Typed RecurringOccurrenceTransitionReason (MATERIALIZER_RESOLUTION, ACTUAL_EXPENSE_LINKED, etc.)

RECONCILE linked expenses after bulk update:
  → RecurringLifecycleCoordinator.reconcileAllLinkedExpensesAfterBulkUpdate()
  → Returns BulkRecurringReconcileResult with per-category counts
  → Triggered by TransactionUpdateKind values: AMOUNT, DATE, CURRENCY, OWNERSHIP, PAYMENT_CORE

DISPATCH reminder:
  → BillReminderWorker → RecurringLifecycleCoordinator.getDispatchableClaimedReminder()
  → Post-claim revalidation: verify occurrence still PLANNED
  → Per reminder, after claim + revalidation and immediately before the post:
    NotificationPresentationResolver.resolve(NotificationKind.BILL, Build.VERSION.SDK_INT)
    (bounded read, fail-closed to DISCREET; CancellationException propagates, nothing is posted)
  → sendNotification(..., decision) builds channel/icon/priority itself, then
    NotificationPresentationApplier.apply(...) sets title/text, PRIVATE visibility, the generic
    public version, the action policy and the BILL kind marker (see NOTIFICATION PRESENTATION)
  → sendNotification() returns NotificationSendResult.Sent/Failed
  → Runtime settings check (enabled/quiet hours via BillReminderSettingsRepository)

FORBIDDEN:
  ❌ ManualRecurringExpenseDao.insert/update/delete outside coordinator
  ❌ RecurringOccurrenceDao.update() outside materializer/coordinator
  ❌ BillReminderManager.markBillPaid() [REMOVED — use MarkRecurringBillPaidUseCase (createExpenseDbOnlyV2 + linkExpenseToSpecificOccurrence + advanceNextDate)]
  ❌ Raw String status in updateOccurrenceStatus() (must use RecurringOccurrenceStatus)
  ❌ Direct DAO for critical lifecycle events (must use RecurringLifecycleEventWriter)
  ❌ Any recurring rule mutation outside RecurringRuleLifecycleCoordinator
  ❌ 0L placeholder occurrenceId in reconcile results
  ❌ Bulk reconciliation using global PAID scan

ENFORCEMENT:
  • RecurringArchitectureGuardTest (app/src/test/.../architecture/) enforces the
    single-writer principal: no direct recurring DAO mutation outside
    RecurringRuleLifecycleCoordinator, no legacy markBillPaid, critical events
    only via RecurringLifecycleEventWriter.
```

---

## Privacy / Cloud AI

```
CLOUD AI call:
  → Check EffectiveCloudAiPolicy via CloudAiPrivacyGate (covers CLOUD_AI_GENERAL,
    DAILY_BRIEFING, RECEIPT_ASSIST, BANK_STATEMENT, etc.)
  → If redactBeforeCloud: apply CloudPayloadPolicy via DefaultCloudPayloadPolicy.prepareText()
    / prepareReceiptAssist() / prepareBankStatementValidation() (no generic prepare())
  → PreparedCloudPayload contract used by all 8 cloud providers
    (data/ai/provider/Cloud*Service)
  → Audit via CompositePrivacyGate final decision

RAW DATA storage:
  → Check RawStorageMode (STORE_RAW / STORE_REDACTED / STORE_METADATA_ONLY / DO_NOT_STORE)
  → RawContentSanitizer applies per-mode sanitization for every source:
    email, notification, bank statement, OCR text
  → Processing uses EPHEMERAL in-memory text; DB stores SANITIZED version per mode
  → DO_NOT_STORE = no raw text persisted, processing still works

EXCHANGE-RATE download (non-AI network call, default OFF):
  → CurrencyManagementViewModel.refreshRates
    → CurrencyRatesRepositoryImpl.refresh
    → repository checks the composite PrivacyGate BEFORE any HTTP call;
      NetworkPrivacyGate decides PrivacyCapability.EXCHANGE_RATE_DOWNLOAD
      (PrivacySettings.exchangeRateDownloadEnabled)
  → Returns CurrencyRateRefreshOutcome (Refreshed / DisabledByPrivacy / PrivacyCheckFailed);
    UI maps codes via CurrencyErrorMessages (no raw codes shown)
  → User switch: Privacy settings > Network

PRIVACY BLOCKED states:
  → PrivacyBlocked sealed interface with typed subclasses:
    CloudAiDisabled, ReceiptImageUploadDisabled, ExternalGeocodingDisabled,
    NotificationCaptureDisabled, RawExportDisabled, DeviceGpsDisabled,
    BackgroundLocationDisabled, BankStatementAiDisabled, EncryptedBackupDisabled,
    OverpassDisabled, DebugDataPersistenceDisabled, ExchangeRateDownloadDisabled, Custom
  → PrivacyDecision.FailClosed: never proceed; blocks execution unconditionally
  → toPrivacyBlocked() maps any denial + capability to a typed PrivacyBlocked
  → 39 call sites across 27 files use blocksExecution() before proceeding
    (blocksExecution() metric; `PrivacyGate.check(...)` itself is invoked from
    51 call sites across 34 production files — different metric, both current)

FORBIDDEN:
  ❌ Cloud HTTP without privacy gate check (must pass through CompositePrivacyGate)
  ❌ Using AiSettings.redactBeforeCloud directly (use EffectiveCloudAiPolicy)
  ❌ Storing raw text when mode is DO_NOT_STORE / METADATA_ONLY
  ❌ Parsing from stored (sanitized) text instead of ephemeral
  ❌ Using raw strings instead of typed PrivacyBlocked for UI states
  ❌ Silently proceeding when PrivacyDecision.FailClosed is returned
  ❌ Gating non-AI network calls by CLOUD_AI_* capabilities
```

---

## Backup / Restore

```
BACKUP:
  → Enter BACKUP_EXPORTING mode (blocks all writes, pauses workers)
  → Checkpoint WAL (TRUNCATE)
  → Delete stale WAL/SHM
  → Copy DB file
  → Exit BACKUP_EXPORTING mode (workers rescheduled)

RESTORE:
  → 11 maintenance modes, persisted via SharedPreferences (commit() not apply()):
    NORMAL / BACKUP_EXPORTING / RESTORE_PREPARING / RESTORE_STAGING /
    RESTORE_SWAPPING / RESTORE_VERIFYING / RESTORE_ROLLING_BACK /
    ASSETS_RESTORING / RESETTING_DATABASE /
    RESTORE_COMPLETE_RESTART_REQUIRED / CRITICAL_RECOVERY_REQUIRED
  → 9-state RestoreJournal (append-only file, atomic temp+rename):
    PREPARING → STAGED → SAFETY_BACKUP_CREATED → SWAPPING →
    VERIFYING → ASSETS_RESTORING → COMPLETE
    (on failure: ROLLING_BACK → FAILED)
  → Delete WAL/SHM before installing restored DB
  → On rollback failure: enter CRITICAL_RECOVERY_REQUIRED (fail-closed, persists across restarts)
  → On startup crash-recovery failure: enter CRITICAL_RECOVERY_REQUIRED (NOT reset on later restarts)
  → On worker reschedule/confirmation failure in RestoreMaintenanceMode resume (startup checked resume, export cleanup): keep NORMAL + worker_resume_pending (restart required, writes denied), retried on the next launch; only a persistence failure goes to CRITICAL_RECOVERY_REQUIRED. Pre-destructive restore/reset aborts (live DB untouched, failure journal written) defer a worker-resume failure to restart-required via exitAfterPreDestructiveAbort (D-23); import aborts still lock CRITICAL via containRestoreOperation/lockCriticalForFailure and cancel-cleanup aborts via finishCancelledRestore
  → Forced restart after success (RESTORE_COMPLETE_RESTART_REQUIRED; auto-reset to NORMAL on next clean start)
  → DatabaseReadBarrier / DatabaseWriteBarrier gate all reads and writes during backup/restore
  → Barrier violations throw DatabaseAccessBlockedException (typed access type,
    operation, and current mode); writes are allowed ONLY in NORMAL mode
  → Reads use explicit DatabaseReadPolicy: NORMAL_APP_READ (NORMAL only),
    EXPORT_OR_BACKUP_SNAPSHOT_READ (NORMAL or BACKUP_EXPORTING),
    RESTORE_INTERNAL_STAGED_DB_READ (always denied through the app singleton)

DATABASE OPEN GATE (R25):
  → The app opens ONLY schema v145 (DatabaseSchemaPolicy.MIGRATION_BASELINE) .. v151
    (APP_DATABASE_SCHEMA_VERSION). Older, newer, or unreadable live DBs are refused by
    DatabaseOpenGate; the live Room instance is built with GatedOpenHelperFactory, which throws
    DatabaseOpenBlockedException(<bounded code>) instead of opening. No destructive fallback:
    the file is never dropped, recreated, or migrated outside the registered chain.
  → GatedOpenHelperFactory also refuses any (re)open of the live DB while a restore swap is in
    progress (RESTORE_SWAPPING latch / persisted mode) with DB_OPEN_REFUSED_DURING_SWAP.
    Staged/fresh instances use the unwrapped AppDatabase.fileBuilder.
  → UnsupportedDatabaseActivity is the only UI for a refused DB. Its single data exception is an
    owner-approved export of the UNTOUCHED file, started by the user through the SAF picker
    (never automatic, never background, never to a non-user-chosen destination).

BACKUP SCHEMA FLOOR (R25-C):
  → Supported backup schema range == app open range: v145..v151. Both restoreCostBackup
    (manifest.databaseVersion, checked right after the manifest is parsed) and importDatabase
    (PRAGMA user_version) reject out-of-range backups BEFORE staging or any swap, via the
    pre-destructive abort path (live DB untouched, failure journal written, maintenance exited,
    temp dir deleted), with BackupSchemaUnsupportedException(code):
      BACKUP_SCHEMA_UNSUPPORTED_LEGACY (< v145), BACKUP_SCHEMA_UNSUPPORTED_NEWER (> v151).

FORBIDDEN:
  ❌ Any DB write outside NORMAL mode (DatabaseWriteBarrier blocks all non-NORMAL modes)
  ❌ Opening the live DB while the open gate blocks it, or during RESTORE_SWAPPING
  ❌ Destructive fallback / auto-reset of an unsupported, newer, or unreadable DB
  ❌ Any raw-file export of a blocked DB other than the user-started SAF export in UnsupportedDatabaseActivity
  ❌ Restoring a backup below v145 or above v151
  ❌ Any DB read during restore stages (DatabaseReadBarrier denies during restore)
  ❌ Using stale Room instance after DB swap (forced restart)
  ❌ Exiting maintenance to NORMAL after failed rollback
  ❌ Raw .db export in release builds
```

---

## Accounting Export

```
EXPORT expenses:
  → AccountingExportPolicy determines allowed formats (CSV, QIF, IIF)
  → ExportPrivacyGate checks typed capabilities:
       EXPENSE_EXPORT (plain CSV — always allowed)
       EXPENSE_EXPORT_ENCRYPTED (requires encryptedBackupEnabled)
       EXPENSE_EXPORT_REDACTED (always safe — sensitive fields stripped)
       EXPENSE_EXPORT_RAW (requires debugDataPersistenceEnabled)
       DEBUG_RAW_EXPORT (debug build + consent)
       RAW_DATABASE_EXPORT (debug build + consent — release-denied)
  → CsvCellSanitizer neutralizes formula injection (=, +, -, @) for every CSV/IIF cell
  → ExportOptionsViewModel orchestrates gate + export + diagnostics

FORBIDDEN:
  ❌ EXPENSE_EXPORT_RAW without debugDataPersistenceEnabled consent
  ❌ RAWBACKUP_EXPORT for normal expense export (use EXPENSE_EXPORT)
  ❌ Unsanitized CSV cells (must use CsvCellSanitizer.sanitize / sanitizeIif)
  ❌ Encrypted export privacy check bypass
```

---

## Workers / Background Jobs

```
EVERY worker:
  → WorkerExecutionGuard.runGuarded() / runGuardedWithContext()
       [checks write barrier FIRST, then logs run]
  → All 10 production CoroutineWorkers route through the guard, including
       NotificationIntakeWorker (previously allowlisted, now gated) and the
       SnoozeReminderActionWorker / DismissReminderActionWorker pair.
  → WorkerRunLogger records RUNNING → SUCCESS/FAILED/SKIPPED/RETRY
  → Checkpoint before long loops (ensureActive / writeBarrier.checkWritesAllowed)
  → Guard enforces requiresNotificationPermission via NotificationPermissionChecker
       (durable skip: NOTIFICATION_PERMISSION_DENIED)
  → PrivacyRuntimeWorkerPolicy checks per-worker privacy consent

RETRY CONTRACT:
  → To request a WorkManager retry, THROW RetryableWorkerException.
  → Guard catch precedence:
       WorkerTimeoutPolicy (RETRY → WORKER_TIMEOUT / PROPAGATE_CANCELLATION →
         WORKER_CANCELLED + rethrow) → CancellationException (recorded
         CANCELLED, rethrow) → WorkerCheckpointBlockedException (applies
         BlockedPolicy) → RetryableWorkerException (Retry; sanitized
         reasonCode takes precedence — PR12J-1)
         → classifyTransient(message/IOException) (Retry, WORKER_TRANSIENT_ERROR)
         → Failed (PERMANENT, WORKER_UNHANDLED_EXCEPTION).
  → classifyTransient matches only: timeout / interrupted / deadlock /
       SQLITE_BUSY / database is locked (case-insensitive) OR IOException.
  → A plain RuntimeException with a non-transient message is PERMANENT
       (burns the attempt budget) — do NOT use it to signal "retry".

SCHEDULING:
  → WorkerRegistry.scheduleAll() for startup
  → WorkerSpecScheduler.scheduleFromSpec() for periodic (WorkerSpec.existingWorkPolicy)
  → WorkerSpecScheduler.scheduleAtMidnight() for one-shot (WorkerSpec.oneShotPolicy;
       uses actual worker class; CANCELS existing unique work when spec is disabled)
  → A spec version bump always forces REPLACE over either policy.
  → DailyBriefing reschedules next midnight on Success AND incidental Skips
       (fresh-artifact/no-work/privacy-denied/restore-blocked); only an explicit
       spec-disable ("Worker disabled by spec") stops the chain.

FORBIDDEN:
  ❌ WorkManager.enqueue() outside WorkerRegistry/WorkerSpecScheduler
  ❌ runBlocking inside suspend worker code
  ❌ Writing BackgroundJobRun before checking write barrier
  ❌ Throwing a plain RuntimeException to mean "retry" (it is PERMANENT)
  ❌ Bypassing WorkerExecutionGuard in any CoroutineWorker
       (enforced by WorkerGuardArchitectureGuardTest; its allowlist is empty)
```

---

## Money / Currency

```
AGGREGATE financial totals:
  → MoneyAggregate (preserves source buckets, conversion failures, isPartial)
  → MoneyAggregateBuilder.fromBuckets() for per-currency aggregation
  → AnalyticsCurrencyNormalizer for per-row historical conversion
  → MultiCurrencyRepository for safe aggregate APIs

DASHBOARD display:
  → Use MoneyAggregate.displayAmount + isPartial + warningMessage
  → Propagate quality through adapter chain
  → Show warning when isPartial=true

COMPARE (sort / min-max filter / excluded count of an expense list by amount in the home currency H):
  → Rule owner: ComparableHomeValue (domain/core/money) — pure, read-only;
    comparableValue(expense, H) = ComparableValue.Value | None(NoComparableValueReason),
    snapshotValue(expense) for the mixed-currency date header, comparator(H, direction), withinBounds
  → SQL mirror: ExpenseDao.COMPARABLE_HOME_VALUE_E_SQL (ExpenseDao companion, next to
    EFFECTIVE_AMOUNT_E_SQL); exactly two '?' placeholders, both bound to H
  → Parity test: ComparableHomeValueSqlParityTest, with ComparableHomeValueFixture shared by the
    Kotlin owner test; neither mirror changes without the fixture
  → Shared builder: ExpenseRepository.buildExpenseDynamicQueryParts takes a required AmountSemantics
    (RawEffective | HomeComparable(H) | AmountOpsDisabled); HomeComparable emits CV >= ? / CV <= ?,
    (CV IS NULL) ASC amount ordering with a date/id tiebreak, args in textual '?' order; list =
    ExpenseRepository.getExpensesPagedDynamic(..., amountSemantics)
  → Excluded count: ExpenseRepository.countExpensesWithoutHomeValue(filter, home) →
    ExpenseDao.countExpensesWithoutHomeValueDynamic (read-classified @RawQuery); same non-amount WHERE
    plus CV IS NULL, ignores min/max and sort, returns an integer only
  → Assistant queries stay on raw EFFECTIVE_AMOUNT_E_SQL (pinned by ExpenseRepositoryAssistantSqlGoldenTest)
  → H comes from the typed read-time resolution CurrencySettingsRepository.observeReadHomeCurrency()
    (ReadHomeCurrencyState: Loading | Available(code) | Unavailable(ReadHomeFailure)), bound as a
    parameter. TransactionsViewModel prepends Loading and applies a 5 s timeout (Unavailable(TIMEOUT));
    Available(H) → AmountSemantics.HomeComparable(H) (ALL tab) and ComparableHomeValue (time-range tabs
    in memory); Loading/Unavailable → AmountSemantics.AmountOpsDisabled / date order, min/max not
    applied, no count. The ViewModel never uses AmountSemantics.RawEffective (assistant-only).

SNAPSHOT rounding (a converted amount stored in the home currency H; sole consumer = TransactionLifecycleCoordinator create and updateExpense):
  → Rule owner: roundSnapshot(value, home) (domain/core/money/SnapshotRounding.kt) =
    SnapshotRounding.Rounded(amount) | Unavailable(NON_FINITE_OR_NON_POSITIVE | DIGITS_UNAVAILABLE);
    HALF_EVEN on BigDecimal.valueOf(value), H minor-unit digits, a positive value never stores 0
    (one minor unit minimum); pure, no logging
  → Digits: CurrencyFractionDigits.of(code) (domain/core/money) is the single accessor over the JDK
    currency digits (null for unknown or negative); SourceCurrencyAllocation delegates to it
  → Writer (TransactionLifecycleCoordinator only; createExpense and updateExpense share
    convertedSnapshot): a converted create or update stores the rounded amount (roundSnapshot) in
    baseAmount, the home currency in baseCurrency and the UNROUNDED rate in exchangeRateUsed
  → No conversion, or roundSnapshot Unavailable: stores the sentinel 0.0 / "" / 0.0 and emits one
    best-effort DiagnosticReasonCode.MISSING_RATE (metadata operation + reason only; reason =
    MISSING_RATE or SNAPSHOT_ROUNDING_UNAVAILABLE); the update emit happens outside
    database.withTransaction
  → Home currency Failed (HomeCurrencyResolution.Failed): sentinel 0.0 / "" / 0.0 on create and
    one best-effort HOME_CURRENCY_UNAVAILABLE diagnostic (also emitted on update); never a
    fabricated default home
  → Identity (expense currency == H) stores amount / H / 1.0 unchanged (no rounding)
  → Existing rows are never rewritten or migrated; only new converted writes use HALF_EVEN rounding

FORBIDDEN (SNAPSHOT rounding):
  ❌ A second snapshot rounding rule (any other code rounding a stored base/home snapshot amount)
  ❌ A second minor-unit digits accessor in domain/core/money

HOME CURRENCY SWITCH (Currency Management screen):
  → home write = CurrencySettingsRepository.setHomeCurrency only via CurrencyManagementViewModel.confirm()
    after request(code) produced HomeSwitchUi.PendingConfirm (both entry points call request, never write)
  → the warning (PendingConfirm) is shown while H is Loading or Unavailable (ReadHomeCurrencyState from
    observeReadHomeCurrency), never skipped; the only no-op is a request equal to an Available H
    (unsupported codes give UNSUPPORTED_HOME_CURRENCY, no dialog, no write)
  → the dialog opens immediately with static text; the optional predicted count
    (ExpenseRepository.countExpensesWithoutHomeValue(empty filter, candidate H)) is added when it
    arrives and omitted when unreadable or slow; confirm = exactly one setHomeCurrency, then the
    classifier invalidation

FORBIDDEN:
  ❌ Calling CurrencySettingsRepository.setHomeCurrency from the Currency Management screen/ViewModel
     other than in CurrencyManagementViewModel.confirm() (no unconfirmed home-currency write)
  ❌ sumOf { effectiveAmount } across currencies without conversion
  ❌ Raw Double totals in public domain/UI models without currency context
  ❌ Dropping MoneyAggregate.isPartial/warningMessage in adapter mapping
  ❌ Raw-amount fallback when comparing a foreign-currency row without a valid snapshot for H
     (it has no comparable value; it sorts last and is excluded by min/max)
  ❌ Interpolating H (a currency code) into SQL; bind it as a parameter
  ❌ A second Kotlin or SQL implementation of the comparable-home-value rule
  ❌ Amount operations (sort, min/max, count) against a placeholder or guessed home currency
     (the "EUR" stateIn default, an untyped homeCurrency() string, or a Loading/Unavailable state)
  ❌ Expense.normalizedEffectiveAmount in a sort, filter or count (it is the date-header snapshot
     value only and delegates to ComparableHomeValue.snapshotValue)
  ❌ Writing base fields (baseAmount/baseCurrency/exchangeRateUsed) outside TLC (Rescue restore
     excepted: identity/sentinel only, via FinancialRescueCoordinator.rescueSnapshotFields)
  ❌ Substituting a literal currency for an unavailable home currency in any displayed total
```

---

## Diagnostics

```
EVERY pipeline exit must write a durable event:
  → PipelineDiagnosticEvent (notification, receipt, email, worker)
  → TransactionEvent (expense lifecycle — LifecycleEventType)
  → ReceiptEvent (receipt lifecycle)
  → RecurringLifecycleEvent (recurring lifecycle)
  → WarrantyLifecycleEvent (warranty lifecycle)
  → BackgroundJobRun (worker lifecycle)
  → BankStatementImportRun (bank statement lifecycle)
  (GroupLifecycleEvent / InvestmentEvent no longer exist in code — group
   audit events go through TransactionLifecycleEventWriter where required)

Exception messages sanitized via EventMetadataSanitizer.sanitizeExceptionMessage():
  → Digit sequences (12+), IBANs, JWT tokens, Bearer tokens → [REDACTED]
  → File paths → [PATH] (not [REDACTED])
  → Messages truncated to MAX_STRING_LENGTH (256 chars)
  → URLs and email addresses are NOT explicitly matched (may be caught incidentally)

FORBIDDEN:
  ❌ Timber-only logging for pipeline decisions (must also write durable event)
  ❌ Swallowing CancellationException (always rethrow)
  ❌ Logging unsanitized exception messages to durable storage
```

---

## Lifecycle Events

```
CRITICAL event (provenance — OCCURRENCE_PAID, PLANNED_FULFILLED):
  → RecurringLifecycleEventWriter.writeCritical()
  → Always writes, returns event ID
  → Must be called for all state-changing operations

DIAGNOSTIC event (informational — REMINDER_SCHEDULE_SKIPPED, etc.):
  → RecurringLifecycleEventWriter.writeDiagnostic()
  → Best-effort: swallows exceptions
  → Acceptable to lose on transient failure

FORBIDDEN:
  ❌ Writing lifecycle events directly through DAO insert
  ❌ Swallowing writeCritical() failures (must fail the operation)
```

---

## Investment Mutations

```
ADD HOLDING:
  → InvestmentTracker.addHolding(investment)
  → DatabaseWriteBarrier check → validation (symbol/name/quantity/purchasePrice/currency/purchaseDate/currentPrice/purchaseFees)
  → database.withTransaction {
      InvestmentDao.insert(validated)
      InvestmentValueDao.insert(initial snapshot with purchasePrice * quantity)
      InvestmentTransactionDao.insert(type="BUY")
    }
  → Result.success(id) or Result.failure(IllegalArgumentException)

UPDATE PRICE:
  → InvestmentTracker.updatePrice(investmentId, newPrice)
  → DatabaseWriteBarrier check
  → require(newPrice > 0 finite)
  → database.withTransaction {
      InvestmentDao.updatePrice(id, newPrice, timestamp)
      InvestmentValueDao.insert(snapshot with dayChange/dayChangePercent)
    }

FORBIDDEN:
  ❌ InvestmentDao.insert() outside InvestmentTracker
  ❌ InvestmentDao.update() outside InvestmentTracker
  ❌ InvestmentDao.updatePrice() directly (must pass through tracker validation + value history)
  ❌ InvestmentValueDao.insert() outside updatePrice/addHolding transaction
  ❌ InvestmentTransactionDao.insert() outside addHolding transaction
  ❌ InvestmentDao.getTotalPortfolioValue/getTotalUnrealizedGainLoss/getTotalInvestedAmount()
      [all @Deprecated — raw Double may mix currencies; use getPortfolioSummaryAggregate()]
  ❌ InvestmentTracker.getPortfolioSummary() [Deprecated — raw Double; use getPortfolioSummaryAggregate()]
  ❌ Summing investment values across different currencies without MoneyAggregateBuilder
```

---

## Group Mutations

```
CREATE group:
  → SharedExpenseManager.createGroup(name, description, memberNames, defaultCurrency, currentUserName)
  → SharedExpenseDataPort.createGroupWithMembers() → SharedExpenseDataPortAdapter
    (write-barrier checked)
  → GroupTransactionCoordinator.createGroupWithMembersAtomic()
  → Atomic inserts: ExpenseGroupDao.insert + GroupMemberDao.insertAll
    (single-currentUser validation, joinedAt normalization, currentUserGroupKey invariant)
  → NOTE: the planned GroupLifecycleCoordinator wrapper (PR-E15) was never built
    and was removed (GR-14u36, owner decision 2026-09-11); group lifecycle
    operations run through SharedExpenseManager / SharedExpenseDataPortAdapter.

ADD member:
  → SharedExpenseManager.addMember(groupId, name, email)
  → GroupTransactionCoordinator.addMemberToGroup()
  → Validates: group exists + active, no duplicate name, no second currentUser
  → GroupMemberDao.insert within transaction

REMOVE member:
  → SharedExpenseManager.removeMember(member)
  → Validates: member exists, no paid expenses, no split references
  → GroupMemberDao leftAt update via SharedExpenseDataPortAdapter.removeMember()

ADD expense to group (live path, RG04A-2):
  → GroupsRepositoryImpl.addExpenseWithLink() / createSystemExpenseAndLinkToGroup()
    (reached via AddGroupExpenseUseCase)
  → validateActiveMembersAndCustomSplit() (date-aware since R11)
  → GroupTransactionCoordinator.addExpenseWithLink() / createSystemExpenseAndLinkToGroup()
    (the two sections below describe the coordinator steps)

ADD expense to group (standalone — no system link) — DORMANT: no production
caller of this SharedExpenseManager path was found (RG04A-2); do not extend it
without re-establishing a caller and the date-aware validation above:
  → SharedExpenseManager.addExpense(groupId, expenseId, ...)
  → Validates: description/amount, payer is member, split payload valid (SHR-17)
  → SharedExpenseDataPort.addExpense() → GroupTransactionCoordinator.addExpenseToGroup()
  → Validates: group active, payer is active member, custom split format,
    single-currency group policy (E4-005)
  → GroupExpenseDao.insert within transaction (idempotency key PR8)

ADD expense to group (with system expense link):
  → GroupTransactionCoordinator.addExpenseWithLink(groupId, systemExpenseId, ...)
  → GroupExpenseDao.insert with expenseId FK + ownership update via
    TransactionLifecycleCoordinator.updateOwnershipDbOnlyV2() in one transaction
  → PostCommitActionBatch side effects run only after the outer commit

CREATE system expense AND link to group (atomic):
  → GroupTransactionCoordinator.createSystemExpenseAndLinkToGroup()
  → database.withTransaction { TransactionLifecycleCoordinator.createExpenseDbOnlyV2(DEFER side effects) + GroupExpenseDao.insert }
  → Throws on failure → rollback both (GroupExpenseAtomicRollback)

RECORD settlement:
  → REMOVED — no production settlement writer exists; GroupSettlementDao is
    read-only (consumed for display by GroupSettlementBalancePolicy.displayedBalances;
    GroupBalanceCalculator / SettlementCalculator have no production caller)

ARCHIVE / RESTORE / DELETE group:
  → SharedExpenseManager.archiveGroup() / restoreGroup() / deleteGroup()
  → archiveGroup: sets isActive=false (soft delete)
  → deleteGroup → GroupTransactionCoordinator.permanentlyDeleteGroup()
    → deleteGroupAtomic: hard delete with cascade cleanup (GroupExpenseDao +
      GroupMemberDao + ExpenseGroupDao), clears shared-expense flags on linked
      system expenses, writes TransactionLifecycleEvent (BULK_UPDATED)

CALCULATE balances (display):
  → GroupSettlementBalancePolicy.displayedBalances(groupId, groupCurrency, expenses, members, settlements)
  → Read-only, pure. Includes only rows/settlements in the group currency; excludes and counts the rest.
  → Returns availability (AVAILABLE/PARTIAL/UNAVAILABLE), included-only total, balances, excluded counts.
  → Suggested settlement plan only when AVAILABLE.
  GroupBalanceCalculator.calculateMemberBalance / SharedExpenseManager.calculateBalances():
  → no production caller; no new callers; pending owner decision (Inc 3).

FORBIDDEN:
  ❌ ExpenseGroupDao.insert() outside GroupTransactionCoordinator
  ❌ GroupMemberDao.insert()/insertAll() outside GroupTransactionCoordinator
  ❌ GroupExpenseDao.insert() outside GroupTransactionCoordinator
  ❌ GroupSettlementDao.insert() — no legal writer exists today
  ❌ GroupLifecycleEventDao.insert() (DAO is dormant — no production writer;
     group audit events go through TransactionLifecycleEventWriter where required)
  ❌ Reviving the removed GroupLifecycleCoordinator wrapper (GR-14u36)
  ❌ Any group mutation without a barrier-checked entry (SharedExpenseManager
     facade or GroupTransactionCoordinator)
  ❌ Hard-deleting a group without checking for linked system expenses
  ❌ Summing group amounts or calling SplitCalculator.calculateBalances for display outside
     GroupSettlementBalancePolicy

ENFORCEMENT:
  • The domain `GroupTransactionCoordinator` interface is implemented by
    `data/database/GroupTransactionCoordinator.kt`, which executes atomic group/
    member/expense writes inside `DomainTransactionRunner` (Room withTransaction)
    with `DatabaseWriteBarrier` checks, `TransactionLifecycleCoordinator` for
    system-expense creation, and `PostCommitActionRunner` for deferred side effects.
  • Legal writer set enumerated in config/guards/db_ownership_policy.yml;
    verified by scripts/verify_db_access_boundaries.py (ratchet) in CI.
```

---

## Subscription Mutations

```
CREATE / ACCEPT subscription:
  → SubscriptionManagerEngine.validateAndCreate(CreateSubscriptionRequest)
    (candidates accepted via acceptCandidate())
  → DatabaseWriteBarrier check
  → Atomic: inserts subscription + price history + candidate resolution + usage baseline
  → Uses RecurringExpenseRepository + SubscriptionPriceHistoryDao + SubscriptionUsageDao
  → Returns Result<ManualRecurringExpense>

CREATE pending subscription candidate:
  → NotificationProcessingPipeline.detectAndSaveSubscriptionCandidate()
  → DatabaseWriteBarrier check
  → SubscriptionCandidateDao.insert
  → Post-commit candidate persistence; acceptance still uses SubscriptionManagerEngine

RECORD price change:
  → SubscriptionManagerEngine.recordPriceChange(subscriptionId, newPrice, effectiveDate)
  → Atomic: updates subscription.currentPrice + inserts SubscriptionPriceHistory row
  → Returns Result<Unit>

RECORD usage:
  → SubscriptionManagementViewModel.recordUsage()
  → SubscriptionManagementRepository.insertUsage()
  → DatabaseWriteBarrier check
  → SubscriptionUsageDao.insert within the repository transaction scope

ANALYZE subscription health:
  → SubscriptionManagerEngine.analyzeSubscription(subscriptionId)
  → Read-only: computes health score 0-100 (price fairness + usage + renewal risk + market rate)
  → Generates recommendations list

CALCULATE savings:
  → SubscriptionManagerEngine.calculatePotentialSavings()
  → @Deprecated (DeprecationLevel.WARNING) — raw Double; NO MoneyAggregate
    alternative exists yet (REC-19: max savings per subscription, not summed)
  → Use getTotalMonthlySubscriptionCostAggregate() for currency-safe cost totals

FORBIDDEN:
  ❌ SubscriptionPriceHistoryDao.insert() outside recordPriceChange
  ❌ Direct SubscriptionCandidateDao.insert/delete outside the registered pipeline
    candidate writer and SubscriptionManagerEngine acceptance path
  ❌ SubscriptionManagerEngine.getTotalMonthlySubscriptionCost() [Deprecated — raw Double across currencies]
  ❌ SubscriptionManagerEngine.calculatePotentialSavings() [Deprecated WARNING — raw Double; no aggregate alternative exists yet]
  ❌ Direct DAO mutations bypassing engine validation
```

---

## Categorization / Merchant Learning

```
CATEGORIZE expense (auto):
  → CategorizationEngine.categorize(merchant) /
    categorizeWithContext(merchant, amount, timestamp)
  → Read-only: 6-layer cascade (Exact → Canonical → Greeklish → Fuzzy → Semantic → Context)
  → Returns CategorizationResult with MatchType + confidence
  → No persistent side effects

LEARN merchant category (user feedback):
  → CategorizationEngine.learnMerchantCategory(merchantName, categoryId)
  → DatabaseWriteBarrier check
  → MerchantCategoryRepository.insert(merchantName → categoryId)
  → Invalidates all caches

DEBUG categorize:
  → CategorizationEngine.debugCategorize(merchantName, amount)
  → Same 6-layer cascade with full trace logging
  → Returns DebugCategorizationResult (prediction + layerDebugStack + candidateDebugInfo)

FORBIDDEN:
  ❌ MerchantCategoryRepository.insert() outside CategorizationEngine.learnMerchantCategory()
  ❌ MerchantCanonicalizer.canonicalize() used for writes (read-only normalization)
  ❌ Direct cache map mutation (must go through invalidateAllCaches)
  ❌ Skipping layers in the cascade (must respect 6-layer priority order)
```

---

## Bank Statement Mutations

```
PROCESS bank statement (image/PDF):
  → BankStatementLifecycleProcessor.processBankStatement(uri)
    [UI entry: ReceiptLifecycleCoordinator.processBankStatement() delegates here;
     ReceiptRepository.processBankStatement() is deprecated]
  → SHA-256 pre-OCR dedup check against BankStatementImportRunDao
  → OCR execution → transaction parsing
  → AiSettings.AI_BANK_STATEMENT privacy check
  → ValidateBankStatementTransactionsUseCase.validateTransactions()
  → PendingReviewDao.insert for human review
  → Three-layer dedup against ExpenseDao + BankStatementImportItemDao
  → BankStatementImportRunDao.insertRun() ledger entry
  → PendingReviewDao.batchInsert() all validated items
  → Full lifecycle: import is NOT a separate step — everything happens in processBankStatement()

FORBIDDEN:
  ❌ BankStatementImportItemDao.insert outside processor
  ❌ BankStatementImportRunDao.insertRun outside processor
  ❌ Bypassing pre-OCR dedup (creates duplicate import runs)
  ❌ Bypassing AI validation when AiSettings.AI_BANK_STATEMENT is enabled
  ❌ Skipping three-layer dedup (expense-level + item-level + run-level)
```

---

## Split / Template Mutations

```
CREATE split template:
  → EnhancedSplitManager.createTemplate(name, totalSplits, splitType, shares)
  → DatabaseWriteBarrier check → validation
  → SplitTemplateDao.insertTemplate with serialized shares
  → Returns template ID
  → EnhancedSplitManager is also the legal writer for deleteTemplate() /
    setDefaultTemplate() (SplitTemplateDao writes)

ASSIGN split items to participants:
  → REMOVED — EnhancedSplitManager.assignItemsToParticipants() no longer exists;
    SplitItemAssignmentDao mutation methods (deleteAllForExpense /
    insertAssignments) have NO production writer today. Assignment reads remain
    via getAssignmentsForExpense() / getParticipantTotals().

FORBIDDEN:
  ❌ SplitTemplateDao.insertTemplate outside EnhancedSplitManager.createTemplate()
  ❌ Reintroducing SplitItemAssignmentDao mutation callers outside a documented
     transactional writer (currently none exists)
  ❌ Splitting expenses with raw Double (must use Money/BigDecimal precision via Money)
```

---

## Notification Capture

```
CAPTURE notification (system/messaging):
  → NotificationIntakeCoordinator.capture(notificationData, source)
  → Computes dedup fingerprint (packageName + tag + key + hash)
  → Checks RawStorageMode: if DO_NOT_STORE/METADATA_ONLY, encrypts/redacts payload
  → NotificationIntakeDao.insert with dedupeKey + encrypted payload
  → Enqueues NotificationIntakeWorker via WorkManager (enqueueUniqueWork, awaited
    with atomic failure transition on enqueue failure — RP-10 10b)

CAPTURE for retry:
  → NotificationIntakeCoordinator.captureForRetry(notificationData, source)
  → Same flow with 5-second enqueue delay

FORBIDDEN:
  ❌ NotificationIntakeDao.insert outside coordinator
  ❌ Storing raw notification text when RawStorageMode is DO_NOT_STORE
  ❌ Skipping dedup fingerprint computation
  ❌ Direct WorkManager enqueue outside capture flow
```

```
CAPTURE HYGIENE (RP-10 10b/10c):
  → In-memory dedup: NotificationCaptureDeduper.tryStart(key, windowMs) — atomic
    check-and-insert under a lock; timestamps come from MonotonicTimeProvider.nowNanos()
    and are stored in NANOSECONDS (windowMs converted once per call), so the dedupe
    window is immune to wall-clock jumps — a backward jump cannot suppress identical
    re-posts and a forward jump cannot disable the window
  → Deduper hygiene: access-order LinkedHashMap capped at 1000 entries (oldest evicted);
    remove(key) on error/cancellation paths; cleanupExpired(maxAgeMs) on service cycle
  → Transient payload framing: NotificationTransientPayloadCrypto — AES-256/GCM/NoPadding
    (Keystore-backed key, random 12-byte nonce per encryption, 128-bit GCM tag). The
    five payload fields are framed BEFORE encryption: per field a presence byte
    (0 = null / 1 = present) then, when present, a 4-byte big-endian length + UTF-8
    bytes — null vs empty stays distinct and NUL-containing content round-trips exactly
    (replaces the colliding NUL-join framing; the legacy NUL-split parse remains only
    as a decrypt fallback for in-flight old-framed rows; both parses failing throws
    GeneralSecurityException with a fixed, content-free message)
  → Sensitive extras key policy: NotificationCaptureService.SENSITIVE_EXTRAS_KEYS — a
    fixed key set (Android system/media keys, MessagingStyle content keys, and
    financial/personal keys in snake_case and camelCase) filtered case-insensitively
    out of extrasJson; values >= 2000 chars dropped (bitmap guard); serialization
    failure yields "{}"
  → Retry backoff: NotificationIntakeRetryPolicy.backoffFor(attempt) is the single
    ladder (30s → 2m → 10m → 30m → 1h, attempt 1-based, clamped to ladder bounds),
    consumed by BOTH NotificationIntakeWorker per-attempt retry scheduling AND
    NotificationIntakeCoordinator enqueue-failure transitions
    (nextAttemptAt = now + backoffFor(attempts + 1)) — one source, no drift

FORBIDDEN:
  ❌ Reading wall-clock time for dedupe windows (must use MonotonicTimeProvider)
  ❌ Framing transient payloads with delimiter joins (NUL or otherwise)
  ❌ Persisting extras keys matching SENSITIVE_EXTRAS_KEYS
  ❌ Duplicating the backoff ladder locally in the worker or coordinator
```

---

## Anomaly Alerting

```
CHECK AND ALERT:
  → AnomalyAlertOrchestrator.checkAndAlert(expense)
  → Guard: skips non-PURCHASE and isNotMine expenses
  → In-flight dedup via inFlightExpenseIds set
  → AnomalyDetector checks amount vs 90-day category history
  → Cooldown check: 24h per merchant, 12h per category
  → Dedup check against recent alerts (same expenseId)
  → Severity filter: only HIGH severity triggers notification
  → NotificationService.postAnomalyAlertForKind(ANOMALY, ...) if all checks pass
    (kind-aware path only; see NOTIFICATION PRESENTATION)
  → AnomalyAlertDao.insert for audit trail

FORBIDDEN:
  ❌ Bypassing cooldown window for alert creation
  ❌ Sending notifications for LOW/MEDIUM severity alerts
  ❌ AnomalyAlertDao.insert outside orchestrator
  ❌ Skipping dedup check against recent alerts
  ❌ Skipping in-flight dedup check (inFlightExpenseIds)
```

---

## Bill Negotiation

```
ANALYZE negotiation opportunities:
  → SmartBillNegotiationEngine.analyzeNegotiationOpportunities()
  → Read-only: detects service type (MOBILE/INTERNET/STREAMING/INSURANCE/ENERGY/etc.)
  → Queries MarketRateProvider for comparable rates
  → Calculates negotiation power score + savings potential
  → Generates negotiation scripts + retention offers
  → Returns List<NegotiationOpportunity>

RECORD outcome:
  → SmartBillNegotiationEngine.recordNegotiationOutcome(subscriptionId, outcome, newPrice, savings, notes)
  → DatabaseWriteBarrier check → validation (oldAmount, currency, newPrice)
  → Atomic within database.withTransaction:
      NegotiationOutcomeDao.insert(outcomeEntity)
      If SUCCESS/PARTIAL + valid newPrice: priceHistoryDao.insert() + recurringExpenseRepository.update()
  → Returns Result<Unit>
  → Negotiation history via getNegotiationHistory()

FORBIDDEN:
  ❌ MarketRateProvider queries for non-eligible service types
  ❌ SubscriptionPriceHistoryDao.insert outside recordNegotiationOutcome
  ❌ Skipping negotiation power validation before generating offers
  ❌ NegotiationOutcomeDao.insert() outside recordNegotiationOutcome
  ❌ Skipping input validation (oldAmount, currency, newPrice)
```

---

## Warranty Auto-Create

```
AUTO-CREATE warranty from receipt:
  → AutoCreateWarrantyFromReceiptUseCase.execute(receiptId, ocrText)
  → WarrantyTextExtractor extracts warranty terms via regex patterns
  → Confidence threshold: high ≥ 70%, medium ≥ 40%, low < 40%
  → Checks existing warranties on same receipt (dedup)
  → High confidence → auto-create Warranty record
  → Low/medium confidence → creates WarrantyReviewDraft for user approval
  → Half-open (exclusive) end-date semantics
  → PrivacyGate.check() before accessing receipt data

FORBIDDEN:
  ❌ Creating warranty without confidence assessment
  ❌ Auto-creating warranty below 70% confidence threshold
  ❌ Creating duplicate warranties for same receipt
  ❌ Accessing receipt data without PrivacyGate check
  ❌ Using inclusive end-date semantics for warranty expiry
```

---

## Location Resolution

```
RESOLVE location for merchant/expense:
  → LocationResolver.resolve(merchantName, addressHint, lat/lng hint)
  → Priority cascade:
      1. User correction override → return immediately
      2. LocationCache → return cached result
      3. GPS bias → Nominatim with GPS coordinates
      4. Name-only → Nominatim with merchant name
      5. Overpass POIs → query nearby points of interest
      6. Unresolved → return null with UNRESOLVED status
  → Privacy gates: DeviceGpsDisabled, ExternalGeocodingDisabled, OverpassDisabled
  → Haversine distance + Null Island filter (0,0)
  → Merchant cluster affinity for grouped location suggestions
  → Cache write on successful resolution

FORBIDDEN:
  ❌ Skipping privacy gate checks for GPS/geocoding/Overpass
  ❌ Using location resolution when ExternalGeocodingDisabled is active
  ❌ Returning Null Island (0,0) coordinates without filtering
  ❌ Cache write without successful resolution
  ❌ Bypassing user correction priority
```

---

## Business Reports / Tax

```
GENERATE business expense report:
  → BusinessExpenseReportGenerator.generateReport(year, project/category filters)
  → Read-only: aggregates expenses by category/project
  → Includes mileage deduction reports from business mileage log
  → Identifies missing receipts for audit trail
  → Enforces purchase-only filtering at boundary (excludes transfers)
  → Returns BusinessExpenseReport (text + CSV ready)

ESTIMATE taxes:
  → TaxEstimator.estimateTaxes(fiscalYear, income, deductions)
  → Configurable tax rates + progressive brackets
  → VAT calculations per jurisdiction
  → MoneyAggregate for multi-currency income/deductions
  → [DEFERRED_DESIGN] — placeholder implementation, rates are configurable not hardcoded

GENERATE CSV export:
  → BusinessExpenseReportGenerator.generateCSVExport(report)
  → CsvCellSanitizer.sanitize() on all cell values (formula injection guard)
  → Returns CSV string for file write

FORBIDDEN:
  ❌ Including non-purchase transactions in business report
  ❌ CsvCellSanitizer bypass for any CSV export cell
  ❌ TaxEstimator with hardcoded tax rates (must use configuration)
  ❌ Single-currency assumption for multi-currency business expenses
```

---

## Analytics

```
CATEGORY analytics:
  → AdvancedAnalyticsEngine.getCategoryAnalytics(normalizedInput)
  → Requires NormalizedAnalyticsInput (from AnalyticsInputAssembler.build())
  → Returns: spending totals, trends, sparklines, percentiles, velocity, category comparisons
  → [Deprecated self-fetching overload exists at DeprecationLevel.WARNING]

MERCHANT analytics:
  → AdvancedAnalyticsEngine.getMerchantAnalytics(normalizedInput)
  → Requires NormalizedAnalyticsInput
  → Returns: visit frequency, loyalty score, price trends, consistency, streaks, day-of-week distribution
  → [Deprecated self-fetching overload exists at DeprecationLevel.ERROR]

SPENDING patterns:
  → AdvancedAnalyticsEngine.getSpendingPatterns(normalizedInput)
  → Requires NormalizedAnalyticsInput
  → Returns: day-of-week, time-of-day, detected patterns
  → [Deprecated self-fetching overload exists at DeprecationLevel.WARNING]

STATISTICAL insights:
  → AdvancedAnalyticsEngine.getStatisticalInsights(normalizedInput)
  → Requires NormalizedAnalyticsInput
  → Returns: histogram, percentiles, volatility, coefficient of variation
  → [Deprecated self-fetching overload exists at DeprecationLevel.WARNING]

ASSEMBLE analytics input:
  → AnalyticsInputAssembler.build(filters)
  → Fetches expenses, filters (spending-only, exclude-not-mine)
  → Normalizes via AnalyticsCurrencyNormalizer
  → Categorizes included/excluded expenses
  → Computes data quality metrics (confidence penalty/multiplier)
  → Returns NormalizedAnalyticsInput (self-contained)

FINANCIAL health score:
  → FinancialHealthCalculator.calculateHealthScores(expenses, budgetStatuses, pendingReviews, todayStreak, weekStreak, monthStreak, noSpendStreak)
  → Combines: budget health (max 25) + spending control (max 25) + cleanliness (max 10) + bonus (max 10)
  → Composite: Today 20% + Week 30% + Month 50% → score 0-100
  → Returns HealthScoreResult

FORBIDDEN:
  ❌ AdvancedAnalyticsEngine.getMerchantAnalytics() with self-fetching [DeprecationLevel.ERROR]
  ❌ AdvancedAnalyticsEngine.getCategoryAnalytics/getSpendingPatterns/getStatisticalInsights with self-fetching
  ❌ Bypassing AnalyticsInputAssembler for analytics computations
  ❌ Using raw Double totals across different currencies in analytics output
  ❌ Dropping data quality metrics (confidence penalty/multiplier) in adapter mapping
```

---

## Import

```
IMPORT expenses from file/content:
  → ImportCoordinator.importFromContent(content, sourceFormat)
  → Detects format: CSV_LEGACY / CSV_FULL / JSON_V1 / JSON_V2 / UNKNOWN
  → Delegates to CsvExpenseImporter or JsonExpenseImporter
  → Each importer: parses → validates → calls TransactionLifecycleCoordinator.createExpense() per row
    (also inserts missing categories via CategoryDao)
  → Returns ImportResult(success, importedCount, skippedCount, errorCount, errors, expenseIds)

FORBIDDEN:
  ❌ CsvExpenseImporter/JsonExpenseImporter used outside ImportCoordinator
  ❌ Importing without format detection
  ❌ Bypassing TransactionLifecycleCoordinator for imported expense creation
  ❌ Skipping validation errors (must report in ImportResult)
```

---

## Financial Rescue Path

```
RESCUE database (last resort — bypasses migration chain):
  → FinancialRescueCoordinator.runRescueIfNeeded()
  → Guard: RescueConfig.ENABLE_FINANCIAL_RESCUE must be true [compile-time toggle, default false]
  → Guard: rescue_completed.txt marker check (one-shot; returns ALREADY_DONE if present)
  → Guard: DB file existence check (returns SKIPPED/NO_DB if no file)
  → Context: the Room migration baseline is v145 (see DatabaseSchemaPolicy:
    MIGRATION_BASELINE = 145, UNSUPPORTED_VERSIONS = 1..<145); databases below
    the baseline are intentionally NOT migrated — this rescue path is their
    only supported upgrade route.

  STEP 1 — Read user version:
    → Raw SQLiteDatabase.openDatabase(READ_ONLY) on old DB
    → Read db.version (Room schema version)

  STEP 2 — Snapshot financial tables:
    → Raw SELECT * on 6 tables (categories, expenses, expense_groups, group_members, group_expenses, split_item_assignments)
    → Dynamic column mapping via PRAGMA table_info (handles schema drift)
    → Gracefully skips missing tables
    → Returns FinancialRescueSnapshot

  STEP 3 — Write JSON safety net:
    → Serializes snapshot to {filesDir}/rescue_snapshot.json

  STEP 4 — Backup DB files:
    → Copies *.db / *.db-wal / *.db-shm / *.db-journal → {filesDir}/db_backups/*.rescue_backup

  STEP 5 — Move aside old DB:
    → Renames *.db → *.legacy.<timestamp> (removes from Room's view)
    → Room creates fresh database on next access

  STEP 6 — Create fresh Room DB + import:
    → AppDatabase.fileBuilder(context).build() (empty tables, latest schema)
    → BEGIN TRANSACTION:
        importCategories: INSERT OR REPLACE (sanitized name/icon/color)
        importExpenses: INSERT OR REPLACE (FK validated, nulls inapplicable columns)
        importExpenseGroups: INSERT OR REPLACE
        importGroupMembers: INSERT OR REPLACE (dedup by groupId+name, single currentUser)
        importGroupExpenses: INSERT OR REPLACE (FK validated against valid groups/members/expenses)
        importSplitItemAssignments: INSERT OR REPLACE (FK validated against valid expenses)
    → COMMIT

  STEP 7 — Mark done:
    → Write rescue_completed.txt with timestamp

  ON FAILURE (any exception):
    → Rollback transaction (fresh DB stays clean)
    → Restore moved-aside files → original names
    → Return FAILURE(error)

FORBIDDEN:
  ❌ RescueConfig.ENABLE_FINANCIAL_RESCUE = true in production builds (compile-time default false)
  ❌ Running rescue when rescue_completed.txt already exists
  ❌ Skipping backup before moving DB files
  ❌ Skipping JSON snapshot before destructive operations
  ❌ Importing without FK validation (orphaned rows produce data corruption)
  ❌ Using Room migrations instead of raw SQLite for rescue path (by design)
  ❌ Manual invocation outside RescueActivity
  ❌ Leaving ENABLE_FINANCIAL_RESCUE = true after rescue completes
  ❌ Any rescue operation without rollback capability
```

---

## Category Assignment

```
ASSIGN default category to expense:
  → DefaultExpenseCategoryAssignmentService.assignCategoryIfUnset(expenseId, categoryId, source, correlationId)
      [implements ExpenseCategoryAssignmentPort; returns CategoryAssignmentOutcome:
       Assigned / SkippedAlreadySet / SkippedExpenseMissing / SkippedCategoryMissing / Failed]
  → DatabaseWriteBarrier check
  → Guard: skips if expense already has a category set
  → Guard: skips (SkippedCategoryMissing) if the category row does not exist (CategoryDao.getById, read only)
  → database.withTransaction {
      ExpenseDao.updateCategory(expenseId, categoryId)
      TransactionEventDao.insert with eventType "EXPENSE_CATEGORY_ASSIGNED" + correlationId
    }
  → CancellationException rethrown; other failures mapped to CategoryAssignmentOutcome.Failed

FORBIDDEN:
  ❌ ExpenseDao.updateCategory() outside DefaultExpenseCategoryAssignmentService / TransactionLifecycleCoordinator
  ❌ Skipping TransactionEvent write during category assignment
  ❌ Assigning category without checking if category already set
```

---

## Budget Forecasting

```
GENERATE spending forecast:
  → BudgetForecastingEngine.generateForecastResult(budget, forecastPeriodDays)
    [UI caller: BudgetForecastingViewModel]
  → Reads historical expense data via ExpenseDao + ExpenseRepository
  → Normalizes via AnalyticsCurrencyNormalizer
  → Computes projected spending using time-series patterns
  → insertForecast() → BudgetForecastDao.insertWithDeactivation() persists result
    (write-barrier checked; UNIQUE-conflict maps to DuplicateInSameInstant)

FORBIDDEN:
  ❌ BudgetForecastDao insert/update outside BudgetForecastingEngine.insertForecast()
  ❌ Forecasting without historical expense normalization
  ❌ Persisting forecasts without AnalyticsCurrencyNormalizer normalization
```

---

## Shared Expense Management (Groups — Domain Facade)

```
CREATE shared group expense:
  → SharedExpenseManager.createGroup(name, description, memberNames, defaultCurrency)
  → SharedExpenseDataPort.createGroupWithMembers() → SharedExpenseDataPortAdapter
    → GroupTransactionCoordinator.createGroupWithMembersAtomic() (multi-table atomic write)

ADD shared expense:
  → SharedExpenseManager.addExpense(groupId, expenseId, ...)
  → SplitCalculator computes member shares (myShareAmount recompute, SHR-12)
  → SharedExpenseDataPort.addExpense() → GroupTransactionCoordinator
    (addExpenseToGroup / addExpenseWithLink — multi-table atomic write)

REMOVE shared expense member:
  → SharedExpenseManager.removeMember(member)
  → SharedExpenseDataPort.removeMember()

FORBIDDEN:
  ❌ SharedExpenseDataPort mutations outside SharedExpenseManager
     (this facade is now the PRIMARY group path — see Group Mutations;
      GroupLifecycleCoordinator no longer exists)
```

---

## Recurring Plan Projection

```
PROJECT future occurrences (read-only):
  → RecurringLifecycleCoordinator.projectOccurrences(ruleId, startDate, endDate)
  → Returns transient (unsaved) occurrences for the window; inactive rules
    project nothing
  → Deliberately NO write-barrier check (read-only projection)
  → Consumers: CashFlowCalculator, FinancialStressForecastEngine,
    ForecastInputAssembler

PERSIST planned rows for projected occurrences:
  → RecurringPlanProjectionService.projectFromOccurrencesInCurrentTransaction()
  → PlannedExpenseDao.insertPlannedExpense() for PLANNED occurrences without one
    (deduped by sourceOccurrenceKey)
  → ONLY called from inside RecurringRuleLifecycleCoordinator rule-mutation
    transactions (projectFromRule was deleted — RP-04 P4-006 dead-code disposition)

FORBIDDEN:
  ❌ PlannedExpenseDao.insertPlannedExpense() outside
     RecurringPlanProjectionService inside a rule-mutation transaction
  ❌ Projecting occurrences without validating rule is active
  ❌ Duplicate planned rows (must check sourceOccurrenceKey first)
```

---

## Spending Challenges

```
READ active challenges + progress:
  → SpendingChallengeManager.getActiveChallengesSnapshot()
  → SpendingChallengeManager.getChallengeProgress(challenge)
  → Read-only; NOTE: progress spend is currently computed via deprecated raw
    Double ExpenseDao SUM aggregates (getTotalSpentBetween / getCategorySpentInPeriod
    — @Deprecated "Use MultiCurrencyRepository"; ownership-filtered (isNotMine = 0)
    but not currency-safe — marked for MultiCurrencyRepository migration)
  → Returns progress percentage against challenge target

CREATE challenge:
  → SpendingChallengeManager.createChallenge(...)

DEACTIVATE expired challenges:
  → SpendingChallengeRepository.deactivateChallenges(challengeIds, updatedAt)
       [write-barrier check inside repository, then SpendingChallengeDao.deactivateChallenges]

FORBIDDEN:
  ❌ SpendingChallengeRepository.deactivateChallenges() without a write-barrier check
  ❌ Adding new raw Double SUM aggregation paths for challenge progress
     (must migrate to MultiCurrencyRepository — ownership- and currency-safe)
```

---

## Notification Presentation (app-lock-privacy-mode, increment 1a)

```
SCOPE:
  Every app-built notification of the six kinds BILL, ANOMALY, BUDGET, WARRANTY, BRIEFING,
  RECEIPT_MATCH (domain/notification/presentation/NotificationKind, closed registry, INV-12).
  The capture foreground-service notification (NotificationCaptureService) is NOT a kind: it keeps
  its constant generic text and never goes through the policy.

POST an app notification (one path):
  → caller (BudgetMonitor, AnomalyAlertOrchestrator, DeliverProactiveBriefingNotificationUseCase,
    WarrantyExpirationWorker, ReceiptMatchingWorker) passes its own kind to a kind-aware method:
    NotificationService.sendBudgetAlertForKind / postBudgetAlertForKind /
    sendAiBriefingReadyForKind / sendAnomalyAlertForKind / postAnomalyAlertForKind
    (there is no kind-less method; the legacy send*/post* forms were removed in S9a, INV-12)
  → AndroidNotificationService.postForKind (areNotificationsEnabled check first, so a disabled app
    never reads the settings)
  → NotificationPresentationResolver.resolve(kind, Build.VERSION.SDK_INT)   [@Singleton]
      one bounded read of AppLockSettingsRepository.getLoadState() (withTimeoutOrNull, T_read 500 ms,
      no runBlocking); unreadable / timeout / unexpected exception → DISCREET (fail-closed) with a
      bounded NOTIF_POLICY_SETTINGS_UNREADABLE(cause) or NOTIF_POLICY_FALLBACK_EXCEPTION(exception
      class) diagnostic launched on @ApplicationScope and never awaited;
      CancellationException is rethrown before the fallback catch
  → NotificationContentPolicy.decide(kind, loadState, sdkInt)   [pure domain object; the single
    owner of mode FULL/DISCREET, expanded-style permission and the action policy
    UNCHANGED / AUTH_REQUIRED / NONE; BILL actions are AUTH_REQUIRED on API 31+, UNCHANGED on API 30
    and below in 1a]
  → NotificationPresentationApplier.apply(builder, decision, channelId, smallIcon, title, text, actions)
      [@Singleton, data/service; the ONE place a decision is applied to a builder]
      PRIVATE visibility always (never PUBLIC); generic public version always attached (no actions,
      no style, no marker, no content intent); FULL shows caller text, DISCREET shows only the
      kind's resource constants (NotificationPresentationTexts) and never reads the detailed text;
      BigText only in FULL for BRIEFING/ANOMALY; actions per policy; the hidden kind marker is
      stamped on the PRIVATE version only (NotificationKindMarker, extras key
      com.yourname.expensetracker.extra.NOTIFICATION_KIND, value = NotificationKind.stableToken)
  → NotificationManager.notify(id, notification)
      SecurityException → DeliveryResult.PERMISSION_DENIED (bounded Log.w: kind token + class only);
      every other exception, including CancellationException, propagates to the caller

BUILDER OWNERS (exactly two; both call the applier, neither sets title/text/style/actions/marker itself):
  1. AndroidNotificationService — postForKind (live posts) and rebuildDiscreet (re-post sweep)
  2. BillReminderWorker — resolves the BILL decision inside the guard lambda per reminder, after
     claim and revalidation, then applies it in sendNotification (still no content intent)

RE-POST sweep (turning discreet on, see APP LOCK SETTINGS WRITE PATH):
  → NotificationService.repostActiveAsDiscreet() → AndroidNotificationService
  → ONE NotificationManager.activeNotifications query (SecurityException → RepostResult(0, 1) +
    NOTIF_REPOST_FAILED(EXCEPTION_CLASS))
  → per notification, inside one try (CancellationException rethrown first; any other Exception
    counts failed++, Log.w class name only, NOTIF_REPOST_FAILED(EXCEPTION_CLASS), the sweep goes on):
      • skip Notification.FLAG_GROUP_SUMMARY (system ranker_group summary; the app never posts
        summaries; neither reposted nor failed)
      • NotificationKindClassifier.classify(channelId, notificationId, rawMarker)
          1. marker first — only when present, a known stableToken, AND allowed on that channel
             (budget_alerts: BUDGET/WARRANTY/RECEIPT_MATCH; bill_reminders: BILL; anomaly_alerts:
             ANOMALY; ai_briefings: BRIEFING)
          2. else channel / id-range fallback for marker-less (pre-1a) notifications:
             bill_reminders = BILL, anomaly_alerts = ANOMALY, ai_briefings = BRIEFING;
             budget_alerts id 10000..19999 = WARRANTY, 20000..29999 = RECEIPT_MATCH, other = BUDGET
          3. any other channel (including the capture channel) → null → skipped
      • BILL: NotificationPresentationResolver.resolve(BILL, sdkInt) (bounded T_read; actionsSuppressed =
        actionPolicy != UNCHANGED; D5 NOTIF_ACTIONS_SUPPRESSED_LOCK(SDK_INT); NOTIF_POLICY_SETTINGS_UNREADABLE
        when the store is unreadable) → NotificationContentPolicy.decideDiscreet(kind, sdkInt, actionsSuppressed);
        other kinds: actionsSuppressed = false, no read (S18-ii-b)
      • fresh builder → NotificationPresentationApplier.apply (stamps the kind marker again);
        from the original it reuses only id, tag, channel, content intent, action PendingIntents
        (BILL only, mapped by position onto resource-titled templates) and the post time;
        setOnlyAlertOnce(true); copies no text, extra, style, group, sort key or large icon
  → PACING: before EVERY re-post (including the first) SweepPacer.pause(SWEEP_REPOST_SPACING_MS = 300)
    runs, so the platform's per-app post rate limit does not silently drop sweep updates. The pause
    is cancellable (CancellationException propagates). Skipped notifications (group summary, null kind,
    failed classify) are not paused. The window is N x 300 ms (about 15 s at 50 notifications).
  → NOTIF_REPOSTED_DISCREET(COUNT) when at least one was re-posted
  Accepted residuals (spec 4.6):
    • a notification dismissed between the query and its re-post (up to N x 300 ms) comes back as a
      new post and can alert on a HIGH-importance channel; the content is discreet
    • a post by other code within about 300 ms of a sweep update, including BillReminderWorker's
      direct post and back-to-back budget alerts, can still make the platform drop that update
      silently, leaving FULL content
    • reposted / NOTIF_REPOSTED_DISCREET count accepted notify() calls, not displayed updates
    • a FULL decision resolved just before the setting commit but posted after the sweep's single query
      stays FULL (millisecond window; postForKind / BillReminderWorker)
    • process death mid-sweep leaves the remaining notifications FULL until they are replaced
  G1b carry-forward (binding, plan "Orchestrator carry-forwards from the G1a reviews"): post-sweep
  verification via a content-free presentation-mode token (D / F) next to NotificationKindMarker.EXTRA_KEY,
  a settle of at least 500 ms, one paced re-post of every sweep-kind notification whose token is NOT "D"
  (an absent token counts: pre-1a / marker-less originals and the residuals above), one COUNT-only
  diagnostic (NOTIF_REPOST_UNCONFIRMED); the token stays off the public version; it never blocks cleanup.
  Triggers: per-field discreet write (UpdateDiscreetNotificationsUseCase) and a committed review-save
  (AppLockPrivacySettingsViewModel, before value "was off"); both go through DiscreetRepostTrigger.

FORBIDDEN:
  ❌ Any kind-less notification method on NotificationService (INV-12)
  ❌ A third builder owner, or setting title/text/style/actions/visibility/public version/marker
     outside NotificationPresentationApplier
  ❌ Choosing FULL/DISCREET or the action policy anywhere but NotificationContentPolicy
  ❌ VISIBILITY_PUBLIC on an app notification
  ❌ Stamping the kind marker anywhere but the applier, or putting it on the public version
  ❌ Putting merchant/amount/product/OCR text in a log, diagnostic or the marker (diagnostics carry
     bounded codes, kind tokens, counts and exception class names only)
  ❌ A new non-service importer of BillReminderWorker from NotificationKindClassifier: the classifier
     may import ONLY the const-inlined `internal const val CHANNEL_ID` (S8 architecture ruling;
     follow-up: move the BILL channel/shape/action templates into one data/service holder)
  ❌ Running the sweep on a failed or rejected write, or when discreet turns off (see below)
  ❌ Calling DiscreetRepostTrigger without NonCancellable (a committed ON must not miss its sweep)
  ❌ Re-posting in the sweep without the SweepPacer pause, or a non-cancellable pause
  ❌ Swallowing CancellationException anywhere in resolve / post / sweep
```

---

## Deep Links (app-lock-privacy-mode, increment 1a)

```
ENTRY (the only one):
  → MainActivity.handleIntent(intent)
  → DeepLinkPolicy.evaluate(intent.action, intent.dataString)   [ui/navigation, object in
    DeepLinkParser.kt; pure, no IO, never throws, never logs; the ONLY parser of expensetracker://]
  → NotADeepLink → handleIntent returns false (not a link at all)
  → otherwise lifecycleScope.launch { DeepLinkApplier.apply(result, mainViewModel) }   (true = the
    intent data is consumed one-shot, even for a rejected link)

EVERY expensetracker: URI is untrusted, internal notification taps included (F9). The manifest is
not the security boundary: the exported launcher activity accepts any URI, so validation here is.

POLICY (spec 5):
  • action must be ACTION_VIEW, else Rejected(ACTION) → ignored, no navigation
  • raw data string > 2048 chars → Rejected(MALFORMED) before parsing
  • not hierarchical, any '#', non-ASCII before the query, userinfo, port, or a path other than
    empty or "/" → Rejected(MALFORMED)
  • host: exact-case closed set DeepLinkHost = home, dashboard, activity, review, plan, add,
    analytics, map; bank hosts → Rejected(BANK_HOST); any other host → Rejected(UNKNOWN_HOST)
  • one allowed parameter per host, everything else dropped and counted:
      home/dashboard: briefingKey = "dashboard_home:" + real yyyy-MM-dd (<= 32 chars)
      activity: expenseId = 1..Long.MAX_VALUE (1..19 ASCII digits)
      analytics: period from TimePeriodTokens (shared allowlist)
      map: location, trimmed, 1..100 chars, no control/format characters
      review/plan/add: none (no link creates or prefills data, INV-17)
    duplicate or invalid values are dropped to the host default
  • result carries DeepLinkRequest (host + validated fields; toString names the host only) — no raw
    URI, host string or parameter value exists after this point

APPLIER (ui/navigation/DeepLinkApplier, the only code that acts on a link):
  • Rejected(ACTION) → no navigation; every other rejection → Home
  • navigates only through MainViewModel's existing methods
  • home/dashboard with a validated briefingKey → MarkDashboardBriefingOpenedUseCase: writes
    lastOpenedDashboardBriefingKey ONLY when the key equals the persisted last delivered key (read
    at apply time) — the single persistent write reachable from a link (INV-16, closes advisory C4)
  • activity with a valid expenseId → read-only ExpenseRepository.getExpenseById; not found,
    read failure or no id degrades to Transactions with a bounded code; CancellationException propagates
  • add → opens an empty add sheet (never prefilled)
  • diagnostics: bounded DL_* codes (host / parameter enums, booleans, counts, exception class)
    launched on @ApplicationScope, never awaited; no raw URI, host string, briefingKey or parameter
    value in any log, diagnostic or AiRuntimeDiagnostics message (INV-18)
  • 1a has no lock, so links apply immediately (afterUnlock = false); the held-link gate belongs
    to increment 1b

MANIFEST (INV-20, OD-3a):
  • MainActivity VIEW filter: action VIEW + category DEFAULT + 8 <data> entries for the scheme
    expensetracker (hosts above). NO BROWSABLE category: links from web browsers no longer open
    the app. Other installed apps can still send VIEW; DeepLinkPolicy is the boundary.
  • Notification taps use an explicit MainActivity component + ACTION_VIEW + FLAG_IMMUTABLE
    (built only in AndroidNotificationService; BillReminderWorker builds no content intent)

FORBIDDEN:
  ❌ Parsing, matching or logging an expensetracker: URI anywhere outside DeepLinkPolicy
  ❌ Acting on a link anywhere but DeepLinkApplier
  ❌ A link that creates, edits, deletes or prefills data (INV-17)
  ❌ A persistent write from a link other than MarkDashboardBriefingOpenedUseCase (equals-last-delivered)
  ❌ Re-adding android.intent.category.BROWSABLE to the expensetracker filter
  ❌ Raw URI / host / briefingKey / parameter values in logs or diagnostics
  ❌ Swallowing CancellationException in the applier
```

---

## App Lock Settings Write Path (app-lock-privacy-mode, increment 1a)

```
STORE:
  AppLockSettingsRepository (domain/privacy/applock; impl data/privacy/applock, own DataStore,
  device-local, never Room, never in .costbackup) is the ONLY reader and writer of the app-lock /
  privacy settings (INV-1). Consumers observe committed state only (INV-46): no optimistic value.
  Unreadable states never render or evaluate as Off (INV-2): the effective discreet value is
  fail-closed (on) via EffectivePrivacyValues.

READ for a notification: NotificationPresentationResolver (see NOTIFICATION PRESENTATION).
READ for the UI: AppLockPrivacySettingsViewModel ← AppLockSettingsRepository.observeLoadState().
READ + RECOVERY for the app-lock state (S18 ii-c, INV-47, INV-51; DORMANT, no production caller): AppLockController
  is the only reader that drives lock state. Cold start: resolve() (T1-T6, T6b). While the load state is
  transient-unreadable (IO_ERROR, TIMEOUT) it re-reads on its own scope at each APP_FOREGROUNDED (never awaited by
  the foreground evaluation) and on requestSettingsReread() (ZERO production callers; the settings retry binds it
  later). The result goes through the PRIVATE, sequence-checked onSettingsReread, never through onLateSettingsResolved:
  a stale sequence, an unreadable result, or a settings write committed since the read was issued (D6, write
  counter bumped by the committed-write entries; F3: the open-origin entry bumps the counter BEFORE its token
  check, so a committed write with no matching token still drops older re-reads) is dropped. F5: a newer
  unreadable re-read result (past the sequence, D6 and transient checks) clears a kept snapshot while the state
  is LOCKED / AUTHENTICATING; in every other state it changes nothing. T28 from UNLOCKED / INERT: lock off → DISABLED, lock on
  and lockActive → UNLOCKED, else INERT(reason); no auth, never locks, no write. T29 in LOCKED / AUTHENTICATING: the
  snapshot is kept and offered to T28 only after an UNLOCK success or after an open-origin prompt that ends in an
  open state; LOCKED is never relaxed (only T6b may). The one T28 point (applyRereadLocked) emits
  LOCK_SETTINGS_RECOVERED (D9, owner-approvals item 51a; PrivacyDiagnosticsEmitter entry, CAUSE key only): the
  PRIOR load state's transient cause (IO_ERROR or TIMEOUT) is captured before the view is overwritten, claimed once
  per process per (code, cause) in claimedCodeCauses, and recorded after the monitor is released. A new INERT
  diagnostic can coincide, so up to two diagnostics are recorded per T28. The CAUSE value limit (IO_ERROR or TIMEOUT
  only) is enforced by the controller; emission is fire-and-forget (INV-37).
  The one lock notice (INV-51) is derived by the pure LockNoticeDerivation (INERT > REVIEW_SETTINGS from the load
  state, persistent causes only > biometric, always false in Increment 1; none in RESOLVING / LOCKED /
  AUTHENTICATING(UNLOCK); the origin's notice during ENABLE / CONFIRM_CHANGE, AMD-22) and published as
  AppLockController.notice, version-stamped with the state, after the monitor is released (R2), and republished on
  a load-state-only change.

WRITE 1 — per-field discreet toggle (1a; the only WIRED per-field writer. WRITE 3 (lock flag) and
WRITE 4 (auto-lock timeout) are DORMANT per-field writers that nothing may call yet):
  → AppLockPrivacySection (UI) → AppLockPrivacySettingsViewModel.setDiscreetNotifications
     (ignored unless Ready and idle, INV-44)
  → appScope.launch (@ApplicationScope, IO) { withContext(NonCancellable) {
        AppLockSettingsWriteSerializer.withExclusiveWrite { ... } } }   (domain-owned, shared with WRITE 3-5)
  → UpdateDiscreetNotificationsUseCase(enabled)
      1. before = effective discreet from getLoadState() (trusted only for Loaded / FirstRunDefault;
         Unreadable counts as was-off)
      2. AppLockSettingsRepository.setDiscreetNotifications(enabled)
         (Rejected(UNREADABLE) when the store is unreadable; Failed(DISCREET) when the commit fails)
      3. on Committed ONLY → DiscreetRepostTrigger.onCommittedWrite(before, after)
            sweeps only on the false → true edge: NotificationService.repostActiveAsDiscreet()
            (see NOTIFICATION PRESENTATION); off never re-posts; on → on is not an edge;
            SWEEP_FAILED is returned only when the sweep itself throws (non-cancellation): recorded as
            NOTIF_REPOST_FAILED(EXCEPTION_CLASS); the committed write is NOT failed; CancellationException
            rethrown. Per-notification failures, and a SecurityException on the active-notification query,
            come back as RepostResult.failed with their own NOTIF_REPOST_FAILED and the trigger returns SWEPT
      4. Failed → the use case records LOCK_SETTING_PERSIST_FAILED(FIELD=DISCREET) (the repository
         leaves diagnostics to its caller); Rejected records nothing and sweeps nothing
  → the committed value reaches the UI only through the store observer; Failed and Rejected show the
    SAVE_FAILED notice (owner decision 2026-10-24)

WRITE 2 — review-save (1a; replaces a CORRUPTED / INVALID_VALUE store):
  → AppLockPrivacySettingsViewModel.saveReview (review card; the only write accepted in that state)
  → same scope + NonCancellable + AppLockSettingsWriteSerializer wrapper as WRITE 1
  → AppLockSettingsRepository.reviewSave(AppLockPrivacySettings(discreet = choice))
     (one atomic whole-record write, marker NORMAL; the only write that clears the sentinel, only on success)
  → 1a has no lock, so no authentication applies (INV-45, 1a note); 1b moves authorisation into
    AppLockController
  → diagnostics are owned by the ViewModel in 1a (INTERIM, retired at S18):
      Committed → LOCK_SETTINGS_REVIEW_SAVED; Failed / Rejected / unexpected exception →
      LOCK_SETTING_PERSIST_FAILED(FIELD=REVIEW_SAVE); every emit is wrapped so a diagnostics failure
      cannot turn a committed save into a reported failure
  → on Committed → DiscreetRepostTrigger.onCommittedWrite(false, after), inside the same serializer /
    NonCancellable block; Failed, Rejected or thrown saves never reach it
  → CONDITIONAL since S18 ii-d (F4(a), D8): reviewSave writes ONLY if, INSIDE the same store edit, the stored
    state is persistent-unreadable (CORRUPTED or INVALID_VALUE). Loaded, a first run or a transient state gives
    Rejected(NOT_UNDER_REVIEW) and nothing is written, so a stale 1a card can never overwrite a committed
    record (the ViewModel shows SAVE_FAILED).

WRITE 2b — review-save (1b, S18 ii-d; the controller-owned path, DORMANT: no production caller, the ViewModel
still uses the 1a path above until S18-ii-h):
  OWNER: AppLockSettingsOperations.requestReviewSave(record) (the only production caller of
  AppLockController.startReviewSaveAttempt / onReviewSavedWithoutPrompt; the `.reviewSave(` callers are pinned
  to exactly {AppLockPrivacySettingsViewModel, AppLockSettingsOperations}).
  → ReviewSaveAuthorisation.classify(record): weakening = lock off, or a timeout longer than IMMEDIATE
    (forced discreet and secure do not count)
  → applyRunning check, review-open check (persistent-unreadable only), fresh capability read, then ONE of:
      (a) weakening from UNLOCKED: CHANGE prompt (AppLockController.startReviewSaveAttempt; INV-54), the
          one-shot T33 token carries the record, the apply runs in the shared AppLockSettingsWriteSerializer
          under NonCancellable with the F7 re-read (settleCancelledWrite)
      (b) D3: no authentication available in INERT → the direct write, no prompt. The D3 decision is made
          outside the serializer, so it is re-proved INSIDE it before the write (INV-45): the state must still
          be INERT and a fresh capability must be neither AVAILABLE nor CHECK_FAILED, else NOT_IN_ORIGIN with
          nothing written
      (c) direct (not a weakening): the direct write, no prompt; a lock-on record re-requires a fresh
          AVAILABLE capability inside the serializer, else REJECTED_CAPABILITY with nothing written
  → AppLockSettingsRepository.reviewSave(record): the CONDITIONAL write (D8) above, never a per-field write
  → AppLockController.onOpenOriginSettingApplied (prompt path) / onReviewSavedWithoutPrompt (direct path):
    the T27 entries; a commit moves the state only from UNLOCKED / INERT with no open attempt (fail closed
    otherwise) and replaces the settings view with the committed record. Guard (ISSUE-1, fail closed): on the
    DIRECT path a weakening record (ReviewSaveAuthorisation.isWeakening) moves the state only when it is INERT;
    from UNLOCKED it never moves on the caller's word (the commit is still taken: sequence bump, view, diagnostic).
    The PROMPT path is token-bound and unaffected. A non-commit (Failed, Rejected(UNREADABLE), throw; and on the
    PROMPT path also Rejected(NOT_UNDER_REVIEW)) records LOCK_SETTING_PERSIST_FAILED(REVIEW_SAVE) once per
    (code, field) and moves nothing;
    LOCK_SETTINGS_REVIEW_SAVED is owned by the controller (once per process).
    Asymmetry between the two paths (D8): on the DIRECT path a Rejected(NOT_UNDER_REVIEW) returns
    NOT_IN_REVIEW with no record and no controller call; only the PROMPT path records
    PERSIST_FAILED(REVIEW_SAVE), via onOpenOriginSettingApplied(id, false)
  → on Committed → DiscreetRepostTrigger.onCommittedWrite(false, effective after), inside the serializer; the
    completion (onOpenOriginApplyCompleted) runs in a finally

WRITE 3 — lock flag (S18 part (i-a), DORMANT: implemented and tested, no production caller):
  OWNER: AppLockSettingsOperations (domain/privacy/applock, @Singleton, @ApplicationScope). The only
  production caller of AppLockController.startOpenOriginAttempt / onOpenOriginApplyCompleted /
  onEnableRefusedAtRequest / onOpenOriginSettingApplied (source-scan pinned).
  → requestEnable() / requestDisable() (no ViewModel, Activity, Compose or Android type)
      1. T26 (enable only): capability read; anything but AVAILABLE (a failed read = CHECK_FAILED) →
         onEnableRefusedAtRequest, REFUSED_UNAVAILABLE, no prompt, nothing written
      2. ENABLE / DISABLE prompt (AppLockController.startOpenOriginAttempt; INV-54 one prompt in flight)
      3. T33 one-shot token: on a matching success the controller closes the attempt, returns to the
         origin state and hands the attempt id to the operations' handler, which only starts the apply
         (ATOMIC launch on the application scope); the token is consumed by exactly that id
      4. withContext(NonCancellable) { enable: effective discreet pre-read (Unreadable / failed read =
         not discreet); AppLockSettingsRepository.setAppLockEnabled(enabled) (one atomic per-field edit) }
      5. AppLockController.onOpenOriginSettingApplied(attemptId, committed): re-checks the origin state
         under the monitor and moves DISABLED → UNLOCKED (T17) or UNLOCKED → DISABLED (T18); a write that
         did not commit moves nothing and records LOCK_SETTING_PERSIST_FAILED(APP_LOCK_ENABLED)
      6. enable and committed ONLY → DiscreetRepostTrigger.onCommittedWrite(before, after); disable
         never re-posts
      7. completion: onOpenOriginApplyCompleted(attemptId) in a finally (also on failure or
         cancellation, which is rethrown), after the state move; releases the T33 gate
      T19 BRANCH (S18-ii-e): requestDisable() while the state is INERT takes NO prompt (nothing can authenticate):
         a. applyRunning is claimed atomically (compareAndSet; ALREADY_IN_FLIGHT if taken) and released on every
            path that does not launch the apply;
         b. AppLockController.beginInertDisable(): INERT(SETTINGS_UNREADABLE_NO_AUTH) or any unreadable /
            unresolved load state -> REVIEW_REQUIRED, nothing written (INV-44: the review-save is the way); not
            INERT or an attempt open -> NOT_IN_ORIGIN; else an id-bound one-shot token (own id sequence);
         c. the capability is read FRESH; AVAILABLE or CHECK_FAILED (a failed read) -> NOT_IN_ORIGIN, nothing
            written (D3: the no-auth path exists only when the device cannot authenticate);
         d. ATOMIC launch on the application scope, STARTED; the apply runs inside the shared
            AppLockSettingsWriteSerializer under NonCancellable and RE-PROVES D3 there (state still INERT, FRESH
            capability still neither AVAILABLE nor CHECK_FAILED, store readable) before the single
            setAppLockEnabled(false) call (F7 re-read after a cancelled write; the cancellation is rethrown);
         e. AppLockController.onInertDisableApplied(id, committed): id-bound, bumps writeSequence on a commit
            (F2, D6), moves INERT -> DISABLED ONLY after Committed and only if the state is still INERT with no
            attempt open (a moved state is never moved; view and LOCK_DISABLED follow the commit); a failed
            write stays INERT and records LOCK_SETTING_PERSIST_FAILED(APP_LOCK_ENABLED) once per process; if the
            device regains authentication between the in-serializer re-proof and the NonCancellable commit, the
            store keeps the user's lock-off and the state is NOT moved; the view follows the commit, so an
            UNLOCKED session stays open with the lock off and a LOCKED state stays LOCKED until a real unlock
            (accepted residual, same as D1);
         f. no DiscreetRepostTrigger call (disable never re-posts, INV-13); no T33 gate and no held link are
            involved (no attempt); applyRunning is cleared in the apply's finally.
         Still DORMANT: the same request entry (requestDisable) and the same source-scan pins; beginInertDisable and
         onInertDisableApplied have exactly one production caller, AppLockSettingsOperations.kt.
  DORMANT / NOT LEGAL TO WIRE until ALL of these BLOCKING items are closed:
    F3: CLOSED by S18-ii-b (adopted resolver form, actionPolicy != UNCHANGED; see the plan amendment
        "S18-ii-b accepted deviation"; the API 26/30 device re-run stays with G1b);
    F4: CLOSED: serializer part (S18-ii-a) and F4(a) review-save in-edit guard (D8) (S18-ii-d: reviewSave is
        conditional inside the store edit, Rejected(NOT_UNDER_REVIEW)) (the domain-owned serializer is shared
        with the ViewModel and covers the WRITE 3, WRITE 4, WRITE 5 and WRITE 2b paths);
    F7: CLOSED by S18 ii-a (a store re-read after a cancelled NonCancellable write on WRITE 3, both WRITE 4
        paths and WRITE 5; loaded + equal = committed path, otherwise not committed: no move, failure recorded
        once, no trigger; the CancellationException is rethrown and the completion finally still runs);
    finding 8: CLOSED by S18-ii-f (setAppLockEnabled, setAutoLockTimeout, setAutoLockTimeoutIfNotWeakening,
        setDiscreetNotificationsIfNotForced and setSecureScreenIfNotForced are abstract; their fail-closed
        default bodies are gone and every fake overrides them, failing closed unless the test needs behaviour);
    INV-45: ViewModel routing (S18-ii-h, merges with S20). The controller-side review authorisation is
        CLOSED by S18-ii-d (WRITE 2b: classify, prompt for a weakening, D3, conditional write); what remains is
        routing the review card through AppLockSettingsOperations.requestReviewSave, with the record starting
        from F for every 1b field (the timeout included: IMMEDIATE). Today the 1a review card still writes
        lock=false with no authentication (AppLockPrivacySettingsViewModel.kt:195/:200);
    WRITE 5 routing (S18 i-b2): the 1a ViewModel's discreet and secure-screen writes are routed through
        WRITE 5 (requestDiscreetChange / requestSecureScreenChange), so the 1a setDiscreetNotifications
        path (no FORCED check) can never write a forced value while the lock is on.
        EXIT CRITERION (S18 i-b2 final gate ISSUE-4): before ANY wiring, either (a) retire
        UpdateDiscreetNotificationsUseCase -> setDiscreetNotifications (and flip the F3 pin to zero
        production call sites), or (b) make setDiscreetNotifications forced-aware. Routing the settings
        ViewModel alone is insufficient (the use case would remain a reachable unforced write path);
  Source-scan tests allow zero callers of AppLockSettingsOperations, requestEnable, requestDisable,
  requestTimeoutChange, requestDiscreetChange and requestSecureScreenChange outside
  AppLockSettingsOperations.kt, exactly one call site of setAppLockEnabled, of setAutoLockTimeout, of
  setAutoLockTimeoutIfNotWeakening, of setDiscreetNotificationsIfNotForced and of
  setSecureScreenIfNotForced (that file), exactly one of the unforced 1a setDiscreetNotifications
  (UpdateDiscreetNotificationsUseCase) and exactly one of reviewSave (the 1a ViewModel); lifting them
  needs the items above. S18-ii-e adds beginInertDisable and onInertDisableApplied to the controller-entry
  pins (exactly one production caller each: AppLockSettingsOperations.kt).

WRITE 4 — auto-lock timeout (S18 part (i-b1), DORMANT: implemented and tested, no production caller):
  OWNER: AppLockSettingsOperations (same class as WRITE 3). Entry: suspend requestTimeoutChange(timeout)
  (no ViewModel, Activity, Compose or Android type).
      1. applyRunning check, then the pre-read of the committed settings; an unreadable store or a
         throwing pre-read = FAILED, nothing written, no prompt, LOCK_SETTING_PERSIST_FAILED(AUTO_LOCK_TIMEOUT)
      2. equal or shorter timeout, or the lock is off (NO prompt): inside the shared serializer across the pre-read
         and the write (applyRunning re-checked inside it), withContext(NonCancellable) {
         AppLockSettingsRepository.setAutoLockTimeoutIfNotWeakening(timeout) } - ONE store edit that
         re-checks "lock on and timeout longer than stored" and writes nothing then
         (Rejected(WOULD_WEAKEN) = NOT_IN_ORIGIN, no view change, no record; Rejected(UNREADABLE) or
         Failed = FAILED + record). Committed → AppLockController.onTimeoutWrittenWithoutPrompt(timeout, true),
         which takes the timeout into the settings view unless that would lengthen a lock-on view
         (fail closed)
      3. longer timeout with the lock on: only from UNLOCKED (INERT = NOT_IN_ORIGIN = the spec's
         Rejected(AUTH_UNAVAILABLE), TECH-13, S18 i-b2: no write, no prompt, no state change);
         CONFIRM_CHANGE prompt with the CHANGE copy (AppLockController.startTimeoutChangeAttempt;
         INV-54 one prompt in flight); nothing is written until it succeeds
      4. the T33 hand-off as WRITE 3: ATOMIC apply on the application scope, withContext(NonCancellable) {
         AppLockSettingsRepository.setAutoLockTimeout(timeout) } (UNconditional: the user authenticated for
         that exact value), then onOpenOriginSettingApplied(attemptId, committed) (applies the token's
         timeout; a failed write records LOCK_SETTING_PERSIST_FAILED(AUTO_LOCK_TIMEOUT)), then
         onOpenOriginApplyCompleted in a finally; the state never moves (UNLOCKED stays)
  DORMANT / NOT LEGAL TO WIRE until the same BLOCKING list as WRITE 3 is closed (F3, F4, F7, finding 8,
  INV-45), with the WRITE 4 scope stated there.

WRITE 5 — discreet and secure screen, forced-aware (S18 part (i-b2), DORMANT: implemented and tested, no
production caller):
  OWNER: AppLockSettingsOperations (same class as WRITE 3 and 4). Entries: suspend requestDiscreetChange(enabled)
  and suspend requestSecureScreenChange(enabled). Under INV-48 / OD-2 (a) both values are FORCED while the lock
  is on (also in INERT), so there is no weakening that needs a prompt: they are refused, not authenticated.
      1. applyRunning check (ALREADY_IN_FLIGHT), then the shared AppLockSettingsWriteSerializer (the same one as WRITE 1-4) held across
         [discreet only: the effective-discreet pre-read], the write and the trigger, applyRunning re-checked inside
      2. withContext(NonCancellable) { AppLockSettingsRepository.setDiscreetNotificationsIfNotForced(enabled) or
         setSecureScreenIfNotForced(enabled) } - ONE store edit: Unreadable = Rejected(UNREADABLE) (FAILED +
         LOCK_SETTING_PERSIST_FAILED(DISCREET | SECURE_SCREEN)); stored lock on = Rejected(FORCED)
         (REJECTED_FORCED: nothing written, no prompt, no record); else the write (lock off is not a weakening)
      3. Committed → AppLockController.onPlainSettingWritten(field, value, true) (settings view only, the state never
         changes); Failed / throw / UNREADABLE → onPlainSettingWritten(field, value, false) (record once per
         (code, field))
      4. discreet only, Committed: DiscreetRepostTrigger.onCommittedWrite(before, after effective) (INV-13, INV-46)
      5. the enable / disable apply of WRITE 3 holds the SAME serializer across its pre-read, write, state move and
         trigger (privacy review C1), so a plain discreet write cannot commit between the apply's pre-read and
         its lock write (a stale "was discreet" would skip the sweep)
  The 1a UpdateDiscreetNotificationsUseCase / ViewModel path (setDiscreetNotifications, no FORCED check) is NOT
  replaced here. DORMANT / NOT LEGAL TO WIRE until the WRITE 3 BLOCKING list is closed (F3, F4, F7, finding 8,
  INV-45, and the WRITE 5 routing item listed there) AND the settings ViewModel's discreet and secure-screen
  writes are routed through WRITE 5 (so a forced value can never be written by the 1a path while the lock is on).
  EXIT CRITERION (ISSUE-4): routing the ViewModel alone is insufficient. Before wiring, either retire
  UpdateDiscreetNotificationsUseCase -> setDiscreetNotifications (and flip the F3 pin to zero production call
  sites), or make setDiscreetNotifications forced-aware.

SERIALISATION (domain-owned since S18 ii-a, F4 serializer part CLOSED (S18-ii-a); F4(a) review-save in-edit
guard (D8) CLOSED (S18-ii-d)): AppLockSettingsWriteSerializer (@Singleton,
domain/privacy/applock, a process-wide non-reentrant Mutex, withExclusiveWrite) so a write started by an
earlier ViewModel instance still excludes the next one's before-read / write / sweep. ONE instance is shared
by the ViewModel's WRITE 1 and WRITE 2 wrappers and by every AppLockSettingsOperations path (WRITE 3 lock
apply, WRITE 4 timeout apply and direct timeout write, WRITE 5 plain writes): the pre-read, the durable
write, the controller entry and the trigger are one critical section, and no prompt is held inside it.
Nothing called inside it may re-enter it (the ViewModel wrappers never call Operations; Operations never
calls the ViewModel). The former Operations-private directWriteMutex is gone. The store-level D8 guard and
the controller-side review-save diagnostics ownership (WRITE 2b) landed in S18 ii-d.

FORBIDDEN:
  ❌ Writing the settings store anywhere but AppLockSettingsRepositoryImpl
  ❌ A per-field write while the store is Unreadable (INV-44); only reviewSave clears it
  ❌ Running the re-post sweep before the write is committed, or on a failed / rejected write (INV-46, AE-105)
  ❌ Re-posting when discreet turns off, or on an on → on write
  ❌ Failing or rolling back a committed write because the sweep failed
  ❌ A settings write outside the serializer, or launched in viewModelScope (a committed ON would then
     miss its sweep when the screen is left)
  ❌ Optimistic UI state not derived from the committed load state
  ❌ A second diagnostics owner for review-save in 1a (the ViewModel is the interim owner until S18)
  ❌ Calling DiscreetRepostTrigger without NonCancellable and the serializer (review-save included)
  ❌ Raw settings text, paths or stack traces in diagnostics (bounded codes + FIELD enum only)
```

## App Lock Prompt Path and Lifecycle Signals (app-lock-privacy-mode, increment 1b, S20-a/b/c)

```
LIVE from S20-c: MainActivity now installs the binder, attaches the prompt host, applies and binds the secure window
and hosts MainScreen inside the app-lock shell. The lock is still not user-enableable (S22 adds the switch), so for
every user with a readable lock-off store the controller resolves to DISABLED and the shell renders the open branch;
the fail-closed paths (T5, T6b, RP-11) can show LOCKED or INERT. S20-c is release-safe only
together with S20-d and S20-e (one atomic S20 candidate, D1).

MAINSCREEN IS COMPOSED ONLY FROM THE GATE (S20-c, INV-26):
  MainActivity.setContent → ExpenseTrackerTheme → ProvideNavigationController → Surface
    → AppLockShell(controller, mainViewModel.operationalState, mainViewModel::navigateTo) { MainScreen(...) }
    → AppLockGate (source overload, no gate effects) → the content slot, only in an open state.
  RESOLVING/LOCKED/AUTHENTICATING(UNLOCK) never compose MainScreen. There is exactly one production `MainScreen(`
  call and it sits inside the AppLockShell content lambda (MainScreenCallSiteTest). The shell sits INSIDE
  ProvideNavigationController so navigation state survives a lock; the content's rememberSaveable state is kept by a
  SaveableStateHolder (T14). No Dialog, AlertDialog or Popup in setContent outside the shell (CF-5, FLAG_SECURE
  inheritance).
  Shell rules: the gate reads controller.state / controller.notice directly (ControllerGateSource, CF-1); Unlock is a
  tap calling onUnlockTapped() only (INV-34); the notice's Security settings action sends a constant
  ACTION_SECURITY_SETTINGS intent with no extras straight from the tap (ActivityNotFoundException → one bounded Timber
  reason, no PrivacyDiagnosticCode; CF-3); the app-lock-settings and review actions re-check
  MainViewModel.operationalState.value at tap time and then navigate to NavigationDestination.PrivacySettings;
  inAppActionsAllowed comes from the operational state (INV-52); the status line is derived only from the published
  AppLockState sequence (an UNLOCK prompt that returns to LOCKED → "try again"; cleared outside LOCKED and on a new
  episode; CF-4).
  MainActivity.onCreate order: super.onCreate → secureWindow.applyInitial(window) → DB gate → binder.install(application)
  → promptHost.attach(this) → secureWindow.bind(this) → enableEdgeToEdge → handleIntent → setContent. handleIntent and
  onNewIntent are unchanged (the deep-link gate is S21). The binder is installed in MainActivity.onCreate (MainApplication
  is not edited): started Activities are counted by IDENTITY, so an Activity started before install (a rescue or
  database-gate Activity) is never counted and its later stop is ignored; it can neither cancel a counted start nor
  cause a missed background (S20-c review #1).
  Back while the gate is closed (RESOLVING, LOCKED, AUTHENTICATING(UNLOCK)) moves the task to the back (AppLockShell
  BackHandler, S19 decision 4).
  POST_NOTIFICATIONS: the R24 auto-prompt effect starts with NotificationPermissionPromptOnce.tryClaim(), so the
  decision runs at most once per process however often the shell re-enters MainScreen; NotificationPromptPolicy and
  NotificationPromptStore still decide whether it shows.
  FLAG_SECURE: the single writer (SecureWindowController) is now live; a source scan pins that FLAG_SECURE and window
  flag writes appear in no other production file (MainScreenCallSiteTest).
  The binder owns resolve() and the INV-25 lifecycle signals; MainActivity calls neither the controller's lifecycle API nor
  resolve() itself.

PROMPT PATH (the only path to the system prompt):
  AppLockController (the only caller; on its @AppLockMainDispatcher, outside its monitor)
  → DeviceAuthenticator port, bound @Singleton to AppLockAuthenticatorRelay (di/AppLockModule)
  → the CURRENT registered adapter, held WEAKLY: BiometricPromptDeviceAuthenticator (ui/applock, one per Activity,
     created by AppLockPromptHost.attach in onCreate)
  → androidx.biometric BiometricPrompt (the only library entry point; AppLockBiometricSourceScanTest allowlist)
  Outcome path back: adapter → relay-owned callback (bound to the open-attempt record and the holding
  registration) → the controller's callback. Forwarded at most once, only while the record is open, the id matches
  and that registration holds the attempt; anything else is dropped. The relay records the open attempt BEFORE it
  calls the adapter (an outcome may be re-entrant). The AuthPromptCopy is passed through unchanged.
  RELAY RULES (handoff S16 binding (a)-(e)):
    (a) weak holder, cleared at the host's ON_DESTROY; (b) no adapter → HOST_UNAVAILABLE and no callback;
    (c) cancel(id) goes to the adapter that launched or adopted id; if that adapter is gone the relay itself delivers
        Failed(SYSTEM_CANCELED), once, synchronously; (d) host loss only from the registration holding the attempt,
        only when !isChangingConfigurations, exactly once (record removed first); (e) reachable only through the DI
        binding and AppLockPromptHost (never workers, receivers or services; pinned by the relay source scan).

HOST LOSS (T35, PROMPT_HOST_LOST) and ROTATION:
  Activity ON_DESTROY → AppLockPromptHost → relay.unregister(registration, isChangingConfigurations)
    - !isChangingConfigurations and this registration holds an open attempt: the relay removes the record and returns
      its id once → AppLockController.onPromptHostLost(id) (closes the attempt first, then asks the adapter to cancel;
      the relay finds no record and ignores that cancel)
    - isChangingConfigurations: no report. The record is kept ORPHANED; the next AppLockPromptHost.attach →
      relay.register hands the open attempt (id and callback) to the new adapter through
      BiometricPromptDeviceAuthenticator.adopt (only when idle and not awaiting a terminal; at most one outcome, stale
      terminals dropped by reference). Adoption is immediate; the controller's watchdog is never the rotation path. A
      refused adoption closes the attempt with the relay's own Failed(SYSTEM_CANCELED).
    - a newer host created before the older one is destroyed (CLEAR_TASK) never adopts a record whose holder is alive.
    - orphan close (no watchdog): orphaning posts ONE check to the main looper (injectable "post to main" seam,
      default Handler(Looper.getMainLooper()).post). A relaunch adopts inside one main-thread message, so a real
      adoption always wins and the check then does nothing; a record still orphaned when the check runs (the
      recreated instance never attached) is closed once with the relay's own Failed(SYSTEM_CANCELED). Each
      orphaning has its own sequence number, so a stale check never closes a later orphaning.
  Activity ON_START → AppLockPromptHost → relay.activate(registration): the started host re-asserts itself as the
    relay's CURRENT registration (a second instance, for example the exported MainActivity VIEW launch, must not
    strand the visible host's prompt). activate never adopts and never touches the open record.

FOREGROUND / BACKGROUND SIGNALS (INV-25) — AppLockLifecycleBinder is the SOLE source:
  Application.registerActivityLifecycleCallbacks (installed once per process, idempotent; S20-c calls it from
  MainActivity.onCreate, MainApplication is not edited) keeps an exact started-activity count:
    0 → 1 → AppLockController.onAppForegrounded()
    1 → 0 and !isChangingConfigurations → AppLockController.onAppBackgrounded()
    a configuration-change stop and its matching start are NOT reported (AE-41); the shade and split-screen never
    stop the Activity. Every real stop is reported, including stops caused by the app's own prompts (no
    suppression while a prompt is in flight; the controller decides, T32-T34).
    an UNPAIRED configuration-change stop (the relaunched instance is destroyed without ever starting, for example it
    finishes in onCreate) is reported at onActivityDestroyed: count == 0, a pending suppression and
    !isChangingConfigurations → onAppBackgrounded() once; the next start is then a foreground (fail-closed).
  The binder also launches AppLockController.resolve() on @ApplicationScope at install and on every start while the
  state is Resolving (CancellationException rethrown; other exceptions logged by class name only).

FLAG_SECURE (INV-29, INV-53; S20-b adds the class, EFFECTIVE only from the S20-c wiring):
  SecureWindowController is the SINGLE writer of FLAG_SECURE on the app window. Nothing else may add or clear it.
  MainActivity.onCreate → applyInitial(window)  (first statement after super.onCreate, synchronous, before setContent)
  MainActivity.onCreate → bind(activity)        (Activity lifecycleScope; ends at ON_DESTROY; once per Activity instance,
                                                  never from onNewIntent)
  flag = EffectivePrivacyValues.effectiveSecureScreen(committed load state, controller.state is Resolving)
    inputs: AppLockSettingsRepository.observeLoadState() (COMMITTED only, INV-46) and the Resolving flag. NOTHING else.
    AUTHENTICATING of any purpose is never an input: ENABLE keeps the flag off while the committed lock is off and
    secure-screen is off, and turns it on at the first emission after the commit (AE-113); CONFIRM_CHANGE keeps it on,
    a weakening clears it only after its write commits, and a persist failure leaves it on (AE-114).
    cleared ONLY by a computed false from a real emission; before the first emission, with a null or absent state, and
    while the load-state stream is down it stays set (fail-closed). When the stream ends (the repository's terminal
    Unreadable(IO_ERROR) then completion, an error, or a clean completion) a null state is emitted (set) and the stream
    is re-subscribed with a bounded backoff (1 s doubling, capped at 30 s; INV-47, AE-97), so the flag can clear again
    after recovery. distinctUntilChanged: no redundant window writes.
  NotificationPermissionPromptOnce.tryClaim() (in-memory, once per process, AE-39) caps the R24 auto-prompt; R24's
    NotificationPromptPolicy and NotificationPromptStore remain the deciders.

FORBIDDEN:
  ❌ A second FLAG_SECURE writer, or any AppLockState other than Resolving (AUTHENTICATING included) as a flag input
  ❌ Clearing FLAG_SECURE on an unknown, null, absent or failed state
  ❌ A second source of foreground/background signals (ProcessLifecycleOwner, onResume/onPause, a ViewModel)
  ❌ Calling the relay, the adapter or the controller's prompt API from a worker, receiver or service
  ❌ Registering anything other than the prompt host's adapter with the relay; a singleton or static Activity reference
  ❌ Relying on the controller's watchdog to recover a rotation
  ❌ Reporting PROMPT_HOST_LOST for a configuration change, or more than once per attempt
```
