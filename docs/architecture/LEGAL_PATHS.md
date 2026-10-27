# Legal Paths — Architecture Law

> **Last updated:** 2026-10-23 loan-installments S2: DB v152 (installment tables exist, no writer) (installment-plan paths added as planned contracts: loan-installments S1; earlier 2026-10-13 schema/savings delta verified against code: DB v151; prior guarded-worker/CI verification retained)
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
USER-PRESENT RATE REQUIREMENT (manual-entry-currency A2; INV-5..9, 19):
  Only a non-null CreateExpenseRequest.userPresentRate (UserPresentRateRequirement(entryZone))
  makes TransactionLifecycleCoordinator apply the eligible-rate rule at the create snapshot step:
    → after validation and provenance, BEFORE dedup and any insert
    → home Failed → refusal HOME_CURRENCY_UNRESOLVED; currency == home → identity snapshot;
      otherwise ONE RateEligibilityEvaluator.evaluate() read decides and supplies the rate:
      Eligible → exchangeRateUsed = Eligible.rate (unrounded), baseAmount = roundSnapshot(amount x rate, home);
      anything else (or rounding/digits unavailable) → CreateExpenseResult.ValidationFailed(errors = [RateRefusalCodes
      code], rateRefusal = RateRefusal) — no expense, no dedup, no insertAtomic, empty post-commit batch
    → a refusal writes only CREATE_ATTEMPTED + one CREATE_VALIDATION_FAILED (bounded code, no dedupe key)
      and one DiagnosticReasonCode.MANUAL_SAVE_BLOCKED_NO_RATE (metadata reason none|stale|unsupported|
      home_unresolved; origin = request.source name); no new LifecycleEventType
    → the flagged branch never uses convertAsOf and never falls through to the unflagged
      sentinel/MISSING_RATE handling; requests with userPresentRate == null are unchanged (any-age conversion)
    → AMOUNT_PRECISION_EXCEEDED (TransactionValidator) is enforced only for flagged requests
  Setters (fixed allow-list, never grows): ManualExpenseRepository, ReceiptScanViewModel, and the
  installment plan coordinator's declare-with-new-expense request; enforced by
  ManualEntryCurrencySetterAllowListGuardTest. No production setter exists until manual-entry-currency B1.

FORBIDDEN:
  ❌ Setting CreateExpenseRequest.userPresentRate outside the three allow-listed files
  ❌ Using isManualEntry or source as the trigger for the eligible-rate rule
  ❌ Re-evaluating or re-reading rates for a flagged snapshot after the evaluator call (no check/use gap)
  ❌ Putting merchant, amount or currency in CREATE_ATTEMPTED / refusal event text or the refusal diagnostic
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
SPENDING INCLUSION (implemented — loan-installments S3; the first caller arrives with the plan coordinator, S6a):
  → TransactionLifecycleCoordinator.setSpendingExclusionDbOnly(expenseId, reason)
    (any sibling setSpendingExclusion* op follows the same rules)
    with reason in { INSTALLMENT_PURCHASE, INSTALLMENT_REVERSED, null }
  → DbOnly op: runs INSIDE the caller's writeBarrier.runWrite { database.withTransaction { … } }
    (DatabaseWriteBarrier.runWrite is re-entrant; Room joins the open transaction),
    returns a MutationResult<SpendingInclusionResult> (Updated / NoOp / NotFound, never throws for these)
    whose post-commit batch the caller dispatches after commit (same contract as createExpenseDbOnlyV2)
  → Writes expenses.spendingExclusionReason (ExpenseDao.updateSpendingExclusionReason, TLC-only) and one
    TransactionEvent with LifecycleEventType.UPDATED and the new TransactionUpdateKind.SPENDING_INCLUSION
    (snapshots, metadata and reason are bounded: row id + reason name only, no amount, merchant or notes;
    the event row keeps the expense dedupeKey like every UPDATED event)
  → The ONLY legal caller is InstallmentPlanLifecycleCoordinator (see "Installment Plan Mutations");
    this op is the ONLY writer of the spendingExclusionReason column
  → Purchase lock (INV-22): while the column is non-null, TLC updateExpense / updateType /
    updateTypeAndTransferDetails / updateOwnershipDbOnlyV2 refuse amount, currency, transactionType and
    money-affecting ownership (isNotMine, shared flag, share percentage, share amount) changes with
    InstallmentLockedExpenseException
    (code EXPENSE_LOCKED_BY_INSTALLMENT_PLAN) before any write or event; merchant, date, category and
    notes and owner/shared-with names stay editable. TLC has no duplicate-merge operation today; any future one must apply the lock
  → updateExpense always keeps the column value read inside its transaction (a stale editor copy cannot
    reset the flag)
  → The spending predicate is never added to the duplicate BLOCKING / RESOLVER families (INV-8)
  → ExpenseDao.insertAtomic() stays the sole expense insert. Plan-created expenses
    (declareWithNewExpense, confirmEntry, recordDueEntries) use createExpenseDbOnlyV2 → insertAtomic()
    with a new bounded ExpenseSource value (never free text) and no notes text

FORBIDDEN:
  ❌ Writing spendingExclusionReason from anywhere except TLC setSpendingExclusion*
  ❌ Calling setSpendingExclusion* from anywhere except the plan coordinator (only the plan coordinator
     calls TLC setSpendingExclusionDbOnly; the TLC's own DAO setter call inside that op is the
     implementation, not a second caller)
  ❌ A second expense insert path for plan-created expenses (insertAtomic() only)
  ❌ Any spending-exclusion change without a TransactionEvent (UPDATED / SPENDING_INCLUSION)
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
  ❌ Adding the spending-inclusion predicate (spendingExclusionReason) to either family
     (INV-8 of loan-installments: a flagged installment purchase row stays a duplicate
     target, so a re-captured purchase notification is still blocked)
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

INSTALLMENT PLAN DATA is NOT cloud payload input (loan-installments INV-31; guard registered in S1,
payload exclusion seam planned for S8a):
  → Plan fields (displayName, lenderLabel, amounts, counts, dates, status), plan repositories, plan DAOs
    and the installment projection port (InstallmentProjectionPort) must not be reachable from any cloud
    payload assembly (CloudPayloadPurpose.*, including DASHBOARD_BRIEFING and the transaction-insight and
    financial-query/assistant inputs)
  → Any cash-flow / forecast / upcoming-item output that feeds a cloud payload is computed WITHOUT the
    installment projection (explicit exclusion at assembly), or that payload stays out of scope
  → Carve-out: the rule covers values read from the plan tables or the projection port. The purchase
    expense's merchant (= a copy of the plan displayName, INV-28) is an ordinary expense field and
    follows the existing cloud/export policy
  → Installment expenses are ordinary expenses and follow the existing cloud/export policy; plan-created
    expenses carry no notes text
  → Enforced by the registered static guard installment_cloud_reachability
    (scripts/verify_installment_cloud_reachability.py), three rule families over the cloud AI payload
    surface (data/ai, domain/ai, domain/privacy, data/privacy, the AI and privacy DI modules, the five
    cloud briefing chain files data/repository/FinancialWeatherRepository.kt,
    domain/forecasting/ForecastInputAssembler.kt, domain/logic/SynthesisEngine.kt,
    domain/logic/NarrativeGenerator.kt and domain/usecase/dashboard/DashboardDataProvider.kt (7 exact-file
    entries in total; S8b-1 / A15: the cloud briefing chain stays installment-free by construction, and a
    Home-only installment-aware weather source must live off-surface in its own file), cloud payload and
    briefing/insight/assistant input-builder files):
      G-INSTALLMENT-CLOUD-01  import of domain.installment, service.installment or
                              ui.screens.installments (wildcard and alias imports included)
      G-INSTALLMENT-CLOUD-02  any other qualified reference to those packages (backticks and
                              whitespace/newlines around "." are normalised away)
      G-INSTALLMENT-CLOUD-03  any identifier containing "installment" (case-insensitive) in executable
                              code, e.g. InstallmentPlan*, installmentPlanDao(), UpcomingItem.Installment
    A surface directory or exact-file entry that matches no scanned file is an infrastructure failure
    (E_INSTALLMENT_CLOUD_SURFACE_ENTRY_UNMATCHED). No allowlist and no baseline. Direct references only:
    transitive reach through a shared upstream model is closed by the S8a exclusion seam and its
    payload test. Known limit: a simple string template "$identifier" (e.g. "$installmentPlans") is
    masked with the string literal, so rule 03 does not see it; ${...} expressions are scanned

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
  ❌ Referencing installment plan types, repositories, DAOs or the projection port from cloud payload
     assembly (INV-31; installment_cloud_reachability)
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

INSTALLMENT PLAN TABLES (planned — loan-installments S2/S2b; tables installment_plans and
installment_entries do not exist yet):
  → Included in the encrypted backup and verified by BackupVerifier at TIER_1_EXACT (financial records),
    with semantic orphan checks reported as fixed strings with counts only: entries without a plan;
    entries whose linkedExpenseId has no expense; plans whose purchaseExpenseId has no expense;
    expenses flagged INSTALLMENT_PURCHASE that no plan references; expenses flagged
    INSTALLMENT_REVERSED that no REVERSED entry references
  → Fail-closed default: every BackupPrivacyMode with redactsRawText = true (REDACT_RAW_TEXT,
    REDACT_RAW_TEXT_EXCLUDE_IMAGES, ANONYMIZED_EXPORT) blanks plan displayName and lenderLabel. Owner
    confirms at the S2b stop-and-ask. ExportAnonymizer (data/privacy, on the cloud-guard surface) must
    reference plan tables by SQL table/column strings, not plan entity types (installment_cloud_reachability)
  → Plan and entry rows are never retention-worker targets (RetentionPolicyContract registers no target)
  → Restore: plan writes are blocked by the restore barrier like every write; after restore the plan
    coordinator runs reconcile() BEFORE the first recordDueEntries. Restore replay of plan rows goes
    through the existing restore path only (single writer, INV-25)
  → Erase-all / reset / FinancialRescue allow-list paths must cover the plan tables

FORBIDDEN:
  ❌ Any DB write outside NORMAL mode (DatabaseWriteBarrier blocks all non-NORMAL modes)
  ❌ Plan/entry table writes during restore other than the existing restore path
  ❌ Running recordDueEntries after a restore before reconcile() has run
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
  → All 12 production CoroutineWorkers (9 WorkerSpec.DEFAULTS + notification_intake + 2 action workers)
       route through the guard, including
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

```
INSTALLMENT DUE RECORDING (coordinator entry point implemented — loan-installments S7a-1; worker implemented —
S7a-2; overview S10 caller still planned):
  → InstallmentDueRecordingWorker (CoroutineWorker, work name "installment_due_recording", daily periodic, no
    network, local-data only and not privacy-gated) with its own WorkerRegistry entry, routed through
    WorkerExecutionGuard.runGuardedWithContext() like every worker (the production worker count above is now 12
    work names: 9 WorkerSpec.DEFAULTS + notification_intake + 2 action workers). It has no startup hook, no
    AppStartupCoordinator edit and no persisted marker (D-19); it is scheduled only through WorkerRegistry.
  → worker (S7a-2) or foreground catch-up trigger (overview, S10; pending)
    → InstallmentPlanLifecycleCoordinator.recordDueEntries(today, zone) (implemented, S7a-1); the worker makes this
      ONE call, then dispatches Done.postCommit batches through PostCommitActionRunner (each batch in its own
      try/catch, cancellation rethrown, failure logged by class name only), and maps the report to a bounded Result:
      stoppedByBarrier → retry; any per-entry failure → retry (INSTALLMENT_DUE_PARTIAL_FAILURE); a
      DatabaseAccessBlockedException at reconcile start → retry (INSTALLMENT_DUE_BARRIER_CLOSED; a barrier that
      closes mid-reconcile counts as plansFailed, and the run then becomes stoppedByBarrier at the first entry, or
      succeeds if nothing is due); a non-Done
      result → bounded failure; run-level read exceptions are left to the guard; deferred and refused are success
    → reconcile() first (its batches are returned in Done.postCommit for the CALLER to dispatch after commit),
      then one transaction per entry (writeBarrier.runWrite + database.withTransaction),
      expense created via TransactionLifecycleCoordinator.createExpenseDbOnlyV2 (STRICT_EXTERNAL_ID)
      and the entry compare-and-set to PAID in the same transaction
    → the candidate-charge deferral (INV-13) reads InstallmentCandidateChargeProbe (read-only boolean port) inside
      that transaction; a blank plan name refuses before any TLC call (INV-34)
    → a closed write barrier (DatabaseAccessBlockedException) stops the run without throwing: the report carries
      stoppedByBarrier = true and the result is still Done(report, all batches so far), so the batches of entries
      already committed are never dropped; the rest are picked up by the next run (the caller retries; owner 39(a))
    → every entry claim (auto-record, confirm, link) latches autoRecordConsumed, so a deleted payment is never
      re-created by auto-record (owner 39(b))
  → Cancellation propagates (never swallowed); retries use RetryableWorkerException
  → Reminders for due installments reuse the existing BillReminderManager / BillReminderWorker
    (existing enabled / quiet-hours settings, delivery dedupe key per entry); no new reminder system
    and no new reminder worker (planned S7b)

FORBIDDEN:
  ❌ Any worker, ViewModel or reminder code writing plan/entry tables or creating plan expenses
     other than through the plan coordinator
  ❌ A second worker or scheduler path that records due installments
  ❌ Plan field values (name, lender, amount, date) in worker logs, run logs or reason codes
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

ELIGIBLE RATE for user-present save (manual-entry-currency A1; read-only, no production consumer until A2):
  → RateEligibilityEvaluator (domain/currency): calendar date in the injected entry zone, age <= 7 days
    (inclusive), older leg for via-EUR; RateEligibility = Home | Eligible | Stale | None | Unsupported;
    reads rates only through CurrencyConverter.convertOutcome(TRANSACTION_DATE); no wall clock, no
    network, no logging
  → Selectable currencies / proposal resolution: CurrencySelectionPolicy (domain/currency)
  → Advisory preview: UserPresentRatePreview (domain/currency), converted amount =
    roundSnapshot(parsed x Eligible.rate, home)

AMOUNT digits/cap for user-entered amounts → CurrencyAmountPolicy (domain/currency):
  → fractionDigits delegates to CurrencyFractionDigits.of (null = unavailable, fail closed);
    cap = static per-currency table; check(parsed, code) never rounds

FORBIDDEN (ELIGIBLE RATE / AMOUNT):
  ❌ Re-implementing rate-age/date eligibility outside RateEligibilityEvaluator
  ❌ A second digits accessor or amount rounding in CurrencyAmountPolicy or its callers

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

INSTALLMENT PLAN diagnostics (planned — loan-installments, with the plan coordinator in S6a; INV-31):
  → Plan expense changes are audited by the expense TransactionEvents (CREATED / UPDATED with
    TransactionUpdateKind.SPENDING_INCLUSION); no separate plan event table exists in v1
  → Plan coordinator diagnostics, refusals, reconcile results and worker logs carry bounded reason codes
    (refusal codes, AUTO_RECORD_DEFERRED_*, reconcile rule IDs R1..R6) and integer counts ONLY
  → Never plan displayName, lenderLabel, amounts, dates, schedule, merchant or IDs in free-text messages,
    logs, reason-code payloads or cloud payloads

FORBIDDEN:
  ❌ Timber-only logging for pipeline decisions (must also write durable event)
  ❌ Swallowing CancellationException (always rethrow)
  ❌ Logging unsanitized exception messages to durable storage
  ❌ Plan field values in any diagnostic, log or reason-code payload (INV-31)
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

```
EXPENSE LIFECYCLE → INSTALLMENT PLAN reaction (implemented — loan-installments S6c-1, 2026-10-26):
  → TransactionSideEffectPlanner.planUpdated() / planDeleted() append the post-commit actions
    `installment_expense_changed` (planUpdated, last) and `installment_expense_deleted` (planDeleted),
    category SideEffectCategory.INSTALLMENT_PLAN
  → TLC's PostCommitActionRunner (PostCommitActionRunnerImpl; the same runner TransactionSideEffectDispatcher
    uses) runs them AFTER commit → InstallmentPlanLifecycleCoordinator.onExpenseChanged / onExpenseDeleted
    (unlink, reopen or detach, per the plan's link rules). The coordinator decides applicability
    (a no-plan expense changes 0 rows)
  → `installment_expense_changed` is planned for the update kinds FULL, TYPE, CURRENCY, TRANSFER_DETAILS and
    PAYMENT_CORE; `installment_expense_deleted` is planned for every single-expense delete via
    TLC.deleteExpense / the dispatcher. SPENDING_INCLUSION never reacts
    (loop breaker for the coordinator's own flag writes); CATEGORY_ONLY, LOCATION_ONLY, BUSINESS_FLAGS_ONLY,
    MERCHANT, AMOUNT, DATE and OWNERSHIP do not react (reconcile is the backstop; reconcile-at-recordDueEntries:
    coordinator part implemented in S7a-1 (D-18 option (b)); the worker call site (S7a-2) is still pending)
  → Bulk updates (planBulkUpdated, incl. TYPE/CURRENCY bulk changes) and deleteAll/wipe paths plan no installment
    reaction; reconcile is the backstop, which today runs only before a .costbackup export (see the S7a note below)
  → Each Done.postCommit batch the reaction returns is dispatched through the same runner (re-entrant,
    stateless); batches are dispatched BEFORE the report is judged
  → In every reacting update batch recurring_reconcile runs before installment_expense_changed, so an expense
    unlinked by rule R4 is re-offered to recurring matching only on its next edit
  → The plan reconcile() (rules R1..R6) backs this up at the start of every recordDueEntries run (coordinator part
    implemented in S7a-1, D-18 option (b); the worker call site, S7a-2, is still pending; see "after restore" below).
    Until the S7a-2 worker or the S10 overview calls recordDueEntries, the only production reconcile() caller is
    InstallmentPreBackupReconciler; restored or orphaned data is not repaired until the next .costbackup export or
    until that worker runs. A lost in-memory post-commit batch (for example budget re-check actions)
    is NOT recovered by reconcile; the next budget trigger is the backstop
  → The reaction never deletes or edits the expense; expense removal is always the standard
    TransactionLifecycleCoordinator.deleteExpense path chosen by the user

BACKUP pre-reconcile (implemented — loan-installments S6c-4, INV-30):
  → DatabaseBackupRepositoryImpl.runCostBackupExport calls InstallmentPreBackupReconciler.reconcileBeforeBackup()
    after the privacy gate and BEFORE maintenanceOperationRunner.enterAndDrain (mode still NORMAL, not inside
    a runWrite); it runs coordinator.reconcile() and dispatches every Done.postCommit batch
  → NotReconciled (result not Done, plansFailed / orphanFlagsFailed > 0, or any non-cancellation exception,
    a closed write barrier included) ABORTS the export before any snapshot: Result.failure with a bounded
    message (constant message plus the enum name), run event INSTALLMENT_PRE_RECONCILE, run.failedFinal(VALIDATION_FAILED)
  → Reconciled means only "no failure was reported"; BackupVerifier stays the authority and still fails
    closed on an orphan introduced after the reconcile (a racing write; a retry succeeds)
  → Not reconciled: createSafetyBackup, createSafetyBackupInternalAssumingMaintenance (restore callers) and the
    debug raw export; restore never reconciles

RECURRING LINK-POLICY consultation (implemented — loan-installments S6c-2 / S6c-2b, INV-5, INV-4):
  → RecurringInstallmentEligibility (domain/recurring, one private decision over
    InstallmentLinkRelations + ExpenseScheduleLinkPolicy.mayLinkToRecurringOccurrence) is the single gate
  → Coordinator link paths: RecurringLifecycleCoordinator.isExpenseEligibleForRecurring (layer 1 unchanged,
    layer 2 delegated) gates the auto-link (claimLinkSuppressInWrite), the manual link
    (linkExpenseToSpecificOccurrence, refusal = ExpenseNotEligible) and reconcileExpenseLinkAfterUpdate
  → Generation path: OccurrenceConflictResolver.resolve reads the excluded id set ONCE per call (batch form)
    and skips excluded expenses; this covers generateOccurrences, projectOccurrences, createRule, activateRule,
    topUpHorizon and updateRule. An excluded expense leaves the occurrence PLANNED and unlinked
  → Reads go through the InstallmentEntryLinkReader port (domain/installment/lifecycle), implemented READ-ONLY by
    InstallmentEntryLinkReaderImpl (data/repository) over InstallmentPlanDao; injected as dagger.Lazy
  → No recurring result or reason value was added (existing refusal outcomes are reused)

RECURRING RECONCILE INV-5 repair (implemented — S6c-2):
  → reconcileExpenseLinkAfterUpdate: when an expense linked to a recurring occurrence became installment-linked
    or a plan purchase, the recurring link is removed with the existing Unlinked outcome (never re-linked); this
    acts only as a repair on corrupt or restored data

INV-29 PATTERN DETECTOR exclusion (implemented — loan-installments S6c-3):
  → RecurringExpenseEngine.detectPatterns(snapshots, excludedMerchantKeys) is the SINGLE entry point: it drops
    installment-linked and plan-purchase expense ids (one batch read per call via
    RecurringInstallmentEligibility.excludedSnapshotIds) and then calls the pure detectPatternsFromSnapshots.
    getPatternsFromSnapshots, getPatterns() and MergedRecurringPatternsProvider all route through it
  → Pinned by RecurringDetectorSingleEntryPointScanTest (no production code outside the engine references
    detectPatternsFromSnapshots)
  → Known gap: the snapshot form cannot see the INSTALLMENT_PURCHASE / INSTALLMENT_REVERSED flag (an orphan flag
    without a plan row is not excluded; harmless because effectiveAmount = 0; reconcile R1 clears it)
  → The subscription-candidate detector (NotificationSubscriptionDetector) is OUTSIDE INV-29 (follow-up)

AFTER RESTORE (decision D-18, option (b); coordinator part implemented in S7a-1, worker call site S7a-2 pending):
  → There is NO startup hook. recordDueEntries runs reconcile at its start on EVERY run and returns the batches
    in Done.postCommit; the caller (S7a-2 worker, S10 overview) dispatches them after commit. A restored store
    is then repaired on the first due-recording run, once a caller exists.
  → Until a caller exists, the only production reconcile() caller is InstallmentPreBackupReconciler; restored or
    orphaned data is not repaired until the next .costbackup export or until the S7a-2 worker lands.
  → InstallmentRestoreReconcileTest pins the restore half of AE-12: a .costbackup round trip with nothing to
    repair (non-redacted and redacted) keeps table counts, expense and plan rows and month totals equal;
    reconcile() on the restored DB reports all zero and changes no row. The S7a-2 worker call site is not tested yet.

FORBIDDEN:
  ❌ Plan code reacting to expense changes by writing expenses or calling deleteExpense
  ❌ Reconcile (or any plan op) running inside restore maintenance or a startup hook
     (restore half: enforced at runtime by the write barrier; startup half: policy only, unguarded)
  ❌ A second recurring/detector eligibility rule next to RecurringInstallmentEligibility, or calling
     detectPatternsFromSnapshots from anywhere except RecurringExpenseEngine.detectPatterns
  ❌ Reacting to expense update/delete from anywhere except the TransactionSideEffectPlanner /
     Dispatcher hook
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
  → NotificationService.send() if all checks pass
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

SIBLING PROJECTION (planned — loan-installments S8b; OQ-25):
  → Installment schedule entries are NOT recurring occurrences. They are never written to
    recurring_occurrences, manual_recurring_expenses or planned_expenses, and the RECURRING_RULE
    projection contract above is unchanged
  → Future SCHEDULED installment entries are exposed read-only through InstallmentProjectionPort
    (InstallmentUpcomingProjection), a sibling of this service, merged at the readers' own
    upcoming/forecast points (see ENGINE_INTERACTION_MAP.md)
  → The projection has no write-barrier check (read-only) and is excluded from every cloud payload
    assembly (see "Privacy / Cloud AI")
```

---

## Installment Plan Mutations

> **Planned contract (loan-installments).** Plan code lands in slices S2-S10 of
> `workflows/active/loan-installments-plan.md`; the plan tables, coordinator and TLC op named below do not exist
> yet. This section is the single legal path those slices implement and the architecture tests and guards enforce.
> What exists today: the cloud reachability guard `installment_cloud_reachability` (S1).

```
OWNERS (one each):
  Plan and entry state, ALL plan/entry writes → InstallmentPlanLifecycleCoordinator (planned, S6a)
  Schedule generation and date arithmetic     → InstallmentScheduleCalculator (pure, no I/O)
  Expense rows (create, update, spending flag)→ TransactionLifecycleCoordinator → ExpenseDao.insertAtomic()
                                                 / TLC update ops
  Overview, detail, history, projections      → InstallmentPlanReadModel / InstallmentProjectionPort (read-only)

EVERY plan coordinator write:
  → writeBarrier.checkWritesAllowed(...) on entry
  → writeBarrier.runWrite { database.withTransaction { … } }
  → expense creation / flag changes ONLY through TLC DbOnly ops inside that same transaction
    (createExpenseDbOnlyV2, setSpendingExclusionDbOnly); post-commit batches dispatched after commit
  → compare-and-set on entry state; bounded refusal codes; no free text

OPERATIONS (all on InstallmentPlanLifecycleCoordinator):
  declareOnExpense            → [txn: load purchase, validate, insert plan + entries,
                                 TLC setSpendingExclusionDbOnly(purchase, INSTALLMENT_PURCHASE)] → post-commit batch
  declareWithNewExpense       → [txn: TLC createExpenseDbOnlyV2 (standard dedupe) + the declare steps]
                                 → post-commit batches
  createStandalone            → [txn: validate category, insert plan + entries]
  paid-before-tracking count  → the first k regular entries are inserted as PAID_BEFORE_TRACKING in the
                                 same transaction (declare / createStandalone)
  markPaidBeforeTracking / revertPaidBeforeTracking (proposed names)
                              → [txn: compare-and-set per entry]; no TLC call, no expense touched
  confirmEntry                → [txn: TLC createExpenseDbOnlyV2 (STRICT_EXTERNAL_ID) + entry → PAID] → post-commit batch
  linkExpense                 → [txn: policy check, compare-and-set]
  unlinkExpense               → [txn: entry → SCHEDULED, recordGeneration + 1]; the expense is untouched
  skip / unskip / adjust / edit / extend / shorten
                              → coordinator ops (plan-internal; no expense writes)
  stop / refund / pay off / delete
                              → coordinator ops; refund also calls TLC setSpendingExclusionDbOnly(…,
                                 INSTALLMENT_REVERSED) per paid expense in the same txn; delete calls
                                 setSpendingExclusionDbOnly(purchase, null)
  recordDueEntries(today, zone)
                              → implemented in S7a-1 (coordinator); callers are the S7a-2 worker and the S10
                                 overview, both still pending (see "Workers / Background Jobs"); one txn per entry;
                                 reconcile runs first and its batches are returned for the caller to dispatch;
                                 candidate deferral via InstallmentCandidateChargeProbe (read-only)
  onExpenseChanged / onExpenseDeleted (implemented, S6c-1)
                              → TLC PostCommitActionRunner actions `installment_expense_changed` /
                                 `installment_expense_deleted` planned by TransactionSideEffectPlanner
                                 (see "Lifecycle Events")
  reconcile()                 → at the start of each recordDueEntries run (coordinator part implemented in S7a-1,
                                 D-18 option (b); worker call site S7a-2 pending; this is also the "after restore"
                                 path: no startup hook); also immediately before a .costbackup export
                                 enters maintenance (InstallmentPreBackupReconciler, S6c-4) (rules R1..R6)
                                 + orphan-flag clear (second halves of R1/R3): an expense flagged
                                 INSTALLMENT_PURCHASE / INSTALLMENT_REVERSED that no plan purchase and no entry
                                 link references; one writeTxn per orphan, references and flag re-checked inside
                                 it, then TLC setSpendingExclusionDbOnly(id, null); counted in the report

UI CALLER (S10b-1, dormant behind InstallmentsFeature.ENABLED):
  InstallmentEntryActionsViewModel (ui/screens/installments/actions)
                              → calls exactly one coordinator op per user action: confirmEntry (Record),
                                 linkExpense (Link), skip, unskip, adjustEntry (Adjust), and for the row Undo
                                 (ux-flows 3.6) unskip / unlinkExpense / adjustEntry with the previous values;
                                 never a DAO, repository write or TLC call (INV-24, INV-25)
                              → POST-COMMIT DISPATCH: the ViewModel hands the batches of a `Done` result to
                                 InstallmentPostCommitDispatcher AFTER the coordinator returns (never inside the
                                 transaction); see "POST-COMMIT DISPATCH OWNER" below
                              → residual risk: if the activity finishes between commit and dispatch the batch is
                                 dropped (same as the worker); the committed state is correct, only the
                                 side effects (budget / notification refresh) are skipped until the next trigger

POST-COMMIT DISPATCH OWNER (S10b-1 F3; single owner, no per-caller copy):
  InstallmentPostCommitDispatcher (domain/installment/lifecycle, @Singleton, wraps PostCommitActionRunner)
                              → dispatch(batches) for the `Done.postCommit` of a coordinator result, called AFTER the
                                 coordinator returns, by exactly these callers: InstallmentDueRecordingWorker (S7a-2),
                                 InstallmentPreBackupReconciler (S6c-4, via dagger.Lazy) and
                                 InstallmentEntryActionsViewModel (S10b-1). S10b-2 / S11 / S12a callers use it too
                              → semantics: skip a batch without actions; run in order; isolate a non-cancellation
                                 failure (logged by class simple name only, never changes the committed result);
                                 CancellationException is rethrown and stops the remaining batches
                              ❌ a private `dispatchBatches` loop in a worker, reconciler, view model or use case
                              → Enforced by InstallmentPlanArchitectureGuardTest POST_COMMIT_RUNNER_OUTSIDE_DISPATCHER (domain/installment, service/installment, ui/screens/installments; the dispatcher's exact path is the only exemption)

READS (plan coordinator, S6a):
  expense / category / recurring-link facts → InstallmentExpenseFactsReader (domain port) → 
                                 InstallmentExpenseFactsReaderImpl (data/repository, READ-ONLY: ExpenseDao.getById,
                                 ExpenseDao.getSpendingExclusionFlagRows, RecurringOccurrenceDao.getByLinkedExpenseId,
                                 CategoryDao.getById), called inside the coordinator transaction.
                                 Plan code never references the expense or recurring stores directly; the port
                                 has no write method. Spending type = PURCHASE only.

READS (overview, detail, history, suggestions, projections; S6a part 2b, all read-only):
  screens / view models      → InstallmentPlanReadRepository (domain) → InstallmentPlanReadRepositoryImpl
                                 (data/repository): InstallmentPlanDao read methods (incl. observeAllPlans /
                                 observeAllEntries) + InstallmentExpenseFactsReader (findExpenseMoney,
                                 findExpenseMoneyBetween, findExpenseFacts; READ-ONLY impl also reads
                                 ExpenseDao.getExpensesBetweenUncapped for the range) → pure
                                 InstallmentPlanReadModel / InstallmentPlanMath. Suggestions = INV-13 candidate
                                 rule with mode-aware windows: AUTO_RECORD [due-3d, due], CONFIRM
                                 [due-5d, due+10d]. InstallmentCandidateRule.autoRecordWindowStartEpochDay /
                                 autoRecordWindowEndEpochDay are the single source the S7a auto-record step reuses.
                                 The relations of a link candidate come from one pure builder
                                 (InstallmentLinkRelations.forExistingExpense), shared with the coordinator link op.
  cash flow / forecast / reminders (S7b, S8b) → InstallmentProjectionPort (domain) →
                                 InstallmentProjectionPortImpl (data/repository): InstallmentPlanDao reads only;
                                 pure InstallmentProjectionRule = SCHEDULED entries of ACTIVE plans, due >= today
                                 (INV-15). No ExpenseDao access, no writes.

FORBIDDEN:
  ❌ Plan/entry DAO insert, update or delete outside InstallmentPlanLifecycleCoordinator
     (restore replay goes through the existing restore path only)
  ❌ Any ExpenseDao call from plan-feature code (domain/installment, service/installment,
     ui/screens/installments) — INV-24
  ❌ Plan code calling TransactionLifecycleCoordinator.deleteExpense, or any plan operation lowering the
     expense row count — INV-23 (expenses are unlinked, never deleted, by plan actions)
  ❌ Writing installment schedules into recurring_occurrences, manual_recurring_expenses or planned_expenses
  ❌ Setting spendingExclusionReason from anywhere except TLC setSpendingExclusion*, or calling that op from
     anywhere except the plan coordinator (only the plan coordinator calls TLC
     setSpendingExclusionDbOnly; the TLC's own DAO setter call inside that op is the implementation,
     not a second caller)
  ❌ Adding the spending predicate to the dedupe BLOCKING / RESOLVER query families (INV-8)
  ❌ Plan repositories, DAOs or the projection port reachable from cloud payload assembly (INV-31)
  ❌ Plan field values in diagnostics, logs or reason-code payloads (INV-31)

ENFORCEMENT:
  • Registered static guard installment_cloud_reachability (S1; rules G-INSTALLMENT-CLOUD-01..03) for the
    cloud reachability rule. It passes vacuously until plan code exists
  • In-repo architecture guard test InstallmentPlanArchitectureGuardTest (S1; same style as
    RecurringArchitectureGuardTest): plan DAO writes only in the plan coordinator; no ExpenseDao
    reference and no deleteExpense call from plan code; no writes to the recurring tables from plan code;
    only the plan coordinator calls TLC setSpendingExclusionDbOnly; the TLC's own DAO setter call inside
    that op is the implementation, not a second caller
  • config/guards/db_ownership_policy.yml entries for the plan DAOs and the new TLC op (S2/S3)
  • Registered static guard spending_predicate_usage (S4d; scripts/verify_spending_predicate_usage.py,
    rules G-SPENDING-PREDICATE-01..03; no allowlist, no baseline): every aggregate @Query over the
    expenses table in a production data/database/dao file references a qualified
    SpendingInclusionSql.{SPENDING_PREDICATE, SPENDING_PREDICATE_E, EFFECTIVE_AMOUNT, EFFECTIVE_AMOUNT_E}
    or carries a "// spending-exempt: <REASON>" line comment above the @Query (REASON in
    DEDUPE_BLOCKING, DEDUPE_RESOLVER, NON_SPENDING_INCOME, ROW_COUNT, NON_MONEY, SUGGESTION,
    ASSET_VALUE); a marked query must not reference SpendingInclusionSql, and a DEDUPE_* marked query
    must never reference it (INV-8). An empty DAO surface exits 2. "--report" lists every classified query
  • Spending-predicate parity tests (S3/S4)
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
