# Legal Paths — Architecture Law

> **Last updated:** 2026-10-31 (merge of feature/app-lock-privacy-mode increment 2 (S24-S28: App PIN Store, S27 UI callers and PIN ordering) into feature/ai-evolution).
> Earlier: 2026-10-30 (merge of feature/manual-entry-currency (MEC 1b + loan S13) into feature/ai-evolution: unions the app-lock S22/S23 DEEP LINKS and the EXCHANGE-RATE history download (MEC 1b) sections).
> Earlier: 2026-10-29 (app-lock-privacy-mode S23 docs: DEEP LINKS 1a/1b with the AE-118 settings-prompt hold; APP LOCK write path status). Earlier: 2026-10-24 (app-lock-privacy-mode 1a: NOTIFICATION PRESENTATION, DEEP LINKS and APP LOCK SETTINGS WRITE PATH sections added; DISPATCH reminder and Anomaly Alerting steps corrected). Earlier: 2026-10-13 (schema/savings delta verified against code: DB v151; prior guarded-worker/CI verification retained)
> Earlier: 2026-10-28 (merge of feature/ai-evolution 4add2466 into feature/manual-entry-currency, NOT validated: unions the loan-installments and app-lock-privacy-mode / ai-fixes sections; DB v152). Earlier: 2026-10-24 (app-lock-privacy-mode 1a: NOTIFICATION PRESENTATION, DEEP LINKS and APP LOCK SETTINGS WRITE PATH sections added; DISPATCH reminder and Anomaly Alerting steps corrected). 2026-10-23 loan-installments S2: DB v152 (installment tables exist, no writer) (installment-plan paths added as planned contracts: loan-installments S1; earlier 2026-10-13 schema/savings delta verified against code: DB v151; prior guarded-worker/CI verification retained)
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

PREFILL AIDS (not a write path):
  • Clipboard amount prefill (ClipboardAmountParser → AddExpenseViewModel.setInitialValuesIfBlank),
    merchant suggestion (AddExpenseViewModel merchant suggestion search, read-only) and
    nl-quick-add Fill (AddExpenseViewModel.fill) only write in-memory Add-Expense form state
    (amount, merchant, date, category, payment). They perform no DB or preference write.
  • The only persisted effect is the unchanged manual Save above:
    AddExpenseViewModel.save() → ManualExpenseRepository.addManualExpense() →
    TransactionLifecycleCoordinator.createExpenseDbOnlyV2() → ExpenseDao.insertAtomic(),
    source = MANUAL_ENTRY.
  • With installments ON (InstallmentsFeature.ENABLED = true on this unreleased branch, owner-approvals 91(b); S13) AddExpenseViewModel routes the
    save to ManualExpenseRepository.buildInstallmentPurchaseRequest →
    InstallmentPlanLifecycleCoordinator.declareWithNewExpense (INV-27 atomic: purchase, plan and entries) and
    never to addManualExpense. The coordinator sets userPresentRate; the builder passes null.
  • Fill must not call categorize*, MerchantNormalizer or any repository write
    (see Categorization / Merchant Learning, read-only learned category lookup).
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
  ManualEntryCurrencySetterAllowListGuardTest. Setter #2 (ReceiptScanViewModel) is active since MEC C1
  (see "CREATE expense FROM receipt"). Setter #1 is active (manual-entry-currency B1):
  ManualExpenseRepository.addManualExpense(..., entryZone) always sets
  UserPresentRateRequirement(entryZone); entryZone is captured once by
  AddExpenseViewModel.onSheetOpened() (plain VM field, never state/persisted/logged).
  AddExpenseViewModel validates the unrounded amount with CurrencyAmountPolicy.check (no rounding on save);
  the repository maps a flagged ValidationFailed to a bounded code (rateRefusal code, else
  AMOUNT_PRECISION_EXCEEDED / AMOUNT_DIGITS_UNAVAILABLE, else the joined message). The ViewModel maps
  by equality: RECURRING_FOREIGN_CURRENCY_ERROR_CODE -> mec_repeat_home_only,
  RATE_NOT_ELIGIBLE_HOME_UNRESOLVED -> mec_block_home_unresolved, AMOUNT_PRECISION_EXCEEDED -> the
  precision text; every other code (AMOUNT_DIGITS_UNAVAILABLE, RATE_NOT_ELIGIBLE_NONE / _STALE /
  _UNSUPPORTED, anything else) -> the generic save-failed text.

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
  → Receipt review (ReceiptScanViewModel.saveExpenseInternal, setter #2, MEC C1) sets
    UserPresentRateRequirement(entryZone) on the CreateExpenseRequest; entryZone is the review zone captured
    when the step becomes REVIEW (plain VM field, falls back to the device zone at request build).
    A flagged ValidationFailed carrying a rateRefusal returns Result.failure(RateNotEligibleException(reason,
    latestRateDate)) (message = RateRefusalCodes constant, no cause, no payload) with an empty post-commit
    batch: no link, no receipt event, no side effects. Other validation failures keep IllegalArgumentException.
    The email/batch receipt path (processEmailReceipt) is unflagged and unchanged.

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
    (data/ai/provider/Cloud*Service). CloudWarrantyExtractionService remains one of the 8 but has
    no production caller (retired from the warranty path by ai-fixes B1a; only di/AiModule.kt may
    reference it, enforced by SourceScanningArchitectureGuardTest)
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
    domain/logic/NarrativeGenerator.kt and domain/usecase/dashboard/DashboardDataProvider.kt, and the
    production adapter data/repository/DashboardContractsAdapter.kt (owner-approvals 59a) (8 exact-file
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

On-device-only capabilities (NOTIFICATION_PARSE, REVIEW_PRIORITIZATION, SEMANTIC_DEDUPE,
TRANSACTION_INSIGHT):
  → AiPolicyImpl.canUseCloudFor is false for every settings combination
  → the router reaches CLOUD only via DefaultAiCapabilityRouter.canUseCloud →
    AiPolicy.canUseCloudFor, which is false for these, so it never returns CLOUD for them
    (additionally, none is in CLOUD_FIRST_CAPABILITIES)
  → TRANSACTION_INSIGHT has no PrivacyCapability / CloudPayloadPurpose / EffectiveCloudAiPolicy
    row (ai-fixes B2a)

Transaction insight (GenerateTransactionInsightUseCase, ai-fixes B2d):
  → on-device only: gated by aiEnabled AND transactionInsightEnabled AND allowOnDeviceAi,
    then a foreground-only short-circuit (AppForegroundState) BEFORE any router or AICore call,
    then router.decide(TRANSACTION_INSIGHT); a CLOUD decision is rejected (ROUTE_REJECTED) with
    zero provider calls; input is unredacted because nothing leaves the device
  → uses OnDeviceDashboardBriefingService only; it must not reference HybridDashboardBriefingService or
    CloudDashboardBriefingService (SourceScanningArchitectureGuardTest)
  → returns TransactionInsightOutcome (Generated | Skipped(reason)); never written to ai_artifacts:
    the artifact is an in-memory hand-off to DashboardFollowThroughEngine only; its summaryText may
    become recommendation text persisted via the existing RecommendationRepository.saveAll path
    (7-day expiresAt, INV-20); recommendations are generated for every outcome when aiEnabled by the
    existing caller path (ManualExpenseRepository / NotificationProcessingPipeline)
  → diagnostics: bounded reason code only (recordInteraction("transaction_insight", reason.name))

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

EXCHANGE-RATE history download (MEC 1b; same legal path as above, a second method on the same chain;
no new path, no expense write; the setting is On, user-started):
  → AddExpenseViewModel.onDownloadRateHistory / ReceiptScanViewModel.onDownloadRateHistory
    (user tap only; offered when RateRemedyPolicy = DOWNLOAD_HISTORY; ONE successful download per
    entry session; REFRESH_LATEST is offered first, D-1b-1)
    → CurrencyRatesRepository.downloadRecentHistory (CurrencyRatesRepositoryImpl)
    → NetworkPrivacyGate check (EXCHANGE_RATE_DOWNLOAD) BEFORE any HTTP call;
      constant ECB 90-day URL on www.ecb.europa.eu; no redirects; bounded, XXE-safe parse
    → CurrencyConverter.storeRateHistory
    → ExchangeRateStoreAdapter.insertOrUpdateAll (ALL-OR-NOTHING, one transaction)
    → ExchangeRateDao
  → Returns CurrencyRateHistoryOutcome (Stored / DisabledByPrivacy / PrivacyCheckFailed /
    AlreadyInProgress / Failed(RateHistoryFailureReason)); Cancelled is set by the VM on a user Cancel,
    the repository rethrows CancellationException and never returns it
  FORBIDDEN:
    ❌ Fetching rate history at preview, save, refusal, start-up or from a worker
    ❌ Any rate host or file other than the two ECB constants
    ❌ Storing part of a history payload
    ❌ lastUpdated-keyed deletion of exchange_rates (deleteOldRates/cleanupOldRates) from production code
    ❌ Logging URL, dates, rates, currency codes, counts, home currency or exception messages

PRIVACY BLOCKED states:
  → PrivacyBlocked sealed interface with typed subclasses:
    CloudAiDisabled, ReceiptImageUploadDisabled, ExternalGeocodingDisabled,
    NotificationCaptureDisabled, RawExportDisabled, DeviceGpsDisabled,
    BackgroundLocationDisabled, BankStatementAiDisabled, EncryptedBackupDisabled,
    OverpassDisabled, DebugDataPersistenceDisabled, ExchangeRateDownloadDisabled, Custom
  → PrivacyDecision.FailClosed: never proceed; blocks execution unconditionally
  → toPrivacyBlocked() maps any denial + capability to a typed PrivacyBlocked
  → 38 call sites across 26 files use blocksExecution() before proceeding
    (blocksExecution() metric; `PrivacyGate.check(...)` itself is invoked from
    50 call sites across 33 production files — different metric; both reduced by one by
    ai-fixes B1a removing the AutoCreateWarrantyFromReceiptUseCase cloud gate)

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
  ❌ ASSISTANT/SYSTEM turns or app-computed answers in any query-interpretation prompt
     (model history = prior USER queries, shaped only by FinancialQueryInterpretationInputBuilder)
  ❌ Assistant query interpretation reading PrivacySettings.redactBeforeCloud directly or OR-ing
     redaction flags outside EffectiveCloudAiPolicyResolver
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
  → APP LOCK GATE ON THAT EXPORT (app-lock S21-c, #10; owner decision 73a): at the export tap, BEFORE the
    SAF picker opens, the activity reads the committed lock and the device-auth capability through the one
    narrow read-only Hilt @EntryPoint in ui/unsupported/UnsupportedDatabaseExportAuth.kt (the lock settings
    reader and the capability port only; no AppDatabase, no Dao, nothing Room-backed), bounded at 2 s. Device
    auth is required when (lock ON, or the store is unreadable / the read failed / timed out) AND capability
    is AVAILABLE or CHECK_FAILED, or no answer (provider threw or entry point unresolved). For UNSUPPORTED_API, NO_CREDENTIAL, HW_UNAVAILABLE and UNKNOWN the lock is
    INERT (the main app opens without a prompt in those states), so the export is not gated. The prompt is a
    per-Activity BiometricPromptDeviceAuthenticator with the constant EXPORT copy (D-S22-6;
    AuthPromptCopy.EXPORT, applock_prompt_title_export; the controller never selects it). Cancel, failure or a
    prompt that cannot launch exports nothing and shows one constant message; onStop cancels an open prompt and
    denies. F6-i: the prompt or the picker starts only at lifecycle >= STARTED and not finishing; onStop cancels
    an in-flight gate read and resets CHECKING, and a gate generation token (incremented per tap and per onStop)
    makes a stale gate job a no-op, so a superseded read never changes the state of a newer tap. F6-ii: a
    watchdog at EXPORT_AUTH_PROMPT_TIMEOUT_MILLIS (5 min, parity with PROMPT_WATCHDOG_MILLIS) calls
    cancel(attemptId) and then AUTH_DENIED; any outcome, onStop and a re-arm disarm it; it never opens the
    picker. No diagnostic code is added and nothing platform-supplied is kept. The activity never binds AppLockController and the lock gate in MainActivity
    is unchanged.

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
  ❌ That export without device auth while the lock is on or unreadable and the device can authenticate (S21-c)
  ❌ A second @EntryPoint lookup, or any Room-backed binding, reachable from the UnsupportedDatabaseActivity surface
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
       (durable skip: NOTIFICATION_PERMISSION_DENIED) for workers whose whole purpose is
       a notification
  → Optional-notification pattern (PR12K-3; ReceiptMatchingWorker, DailyBriefingWorker,
       WarrantyExpirationWorker): requiresNotificationPermission = false, and the worker
       checks notificationPermissionChecker.areNotificationsEnabled() locally immediately
       before posting. Core work (receipt matching, briefing generation + persistence,
       warranty expiry reconciliation via reconcileExpiredItems) never depends on
       notification permission. When denied: no post, no notification count, run still
       SUCCESS. DailyBriefingWorker and WarrantyExpirationWorker additionally record a
       bounded NOTIFICATION_PERMISSION_DENIED skip diagnostic (DailyBriefingWorker only when
       DeliverProactiveBriefingNotificationUseCase reports that outcome, i.e. after its
       settings, dedupe and artifact checks, or when an attempted post was refused by a
       SecurityException).
       (ai-fixes INV-24, INV-27, INV-29; enforced by SourceScanningArchitectureGuardTest
       notification_posting_workers_require_permission_flag)
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

HOME INSTALLMENT-AWARE WEATHER read path (implemented — loan-installments S8b-3, A15 option A, INV-15/16/29; read-only):
  → Cloud / briefing path (UNCHANGED, installment-free by construction): FinancialWeatherRepository →
    ForecastInputAssembler.assemble → SynthesisEngine.synthesize → NarrativeGenerator.generate →
    DashboardDataProvider.getProcessedDataFlow. These five files are fenced by installment_cloud_reachability
  → Home path: HomeViewModel (the ONLY consumer of HomeInstallmentWeatherSource; enforced by
    InstallmentPlanArchitectureGuardTest HOME_INSTALLMENT_WEATHER_OUTSIDE_HOME over all of app/src/main, owner-approvals
    59b: only ui/screens/home/HomeViewModel.kt and the source's own file may reference it) runs
    ComputeDashboardWidgetsUseCase.compute() on getProcessedDataFlow output, then, ONLY when the seam
    HomeViewModel.installmentsEnabled (default InstallmentsFeature.ENABLED = true on this unreleased branch, owner-approvals 91(b)) is on and the source emits
    non-null, HomeWeatherMerge.replaceWeather REPLACES the single FinancialWeatherWidget (never a second widget;
    upcomingItems of the baseline widget are kept). Nothing is merged in ProcessedDashboardData,
    DashboardDataProvider, DashboardData or FinancialWeather, and no FinancialWeather field is added
  → HomeInstallmentWeatherSource (data/repository, OFF the cloud surface) is the one merge source. It reads the same
    inputs as FinancialWeatherRepository plus InstallmentProjectionPort (31-day window via
    InstallmentUpcomingProjection), RecurringInstallmentEligibility and InstallmentPlanReadRepository.observeChanges
    (PE-C: the invalidation trigger only; it re-emits on plan and entry row changes without loading an overview, so the
    pipeline is not re-run through observeOverview). A blank plan name falls back to the localized string
    installment_plan_fallback_label, resolved per computation. Port empty → null → Home keeps the baseline
  → INV-29 (single owner RecurringInstallmentEligibility.excludedSnapshotIds): while at least one entry is projected,
    installment-linked and plan-purchase history dated BEFORE the current month (home time zone) is removed from the
    aware view's snapshots (global-while-projected, owner-approvals 53a; before-the-month only, 60b). Current-month
    actual installment payments stay in month-to-date, so an installment is counted once, never zero times. The aware
    view REPLACES the baseline view; the two are never summed. Trade-off: the pace ratio compares a current month that
    includes installments against a baseline without them
  → INV-15/16: each projected entry is a MUST planned input in plan currency fed to the UNMODIFIED assemble
    (its planned-expense normalisation converts at LATEST_AVAILABLE or omits the entry and marks the forecast partial,
    never 0). SynthesisEngine counts MUST items of the current month as committed. The installment rows are carried
    separately (HomeViewModel.installmentRows, plan currency, never summed across currencies)
  → Plan projections are never an UpcomingItem variant. The Home Monte Carlo (FinancialStressForecastEngine) is
    installment-aware since S8b-2 (Home-only; supersedes the Q2 "Monte Carlo stays installment-free" wording, see the
    plan S8b amendment note). Runway and health score stay on the installment-free baseline in v1
    (owner-approvals 53 Q2)

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
  ❌ Installment projections or exclusions inside FinancialWeatherRepository / ForecastInputAssembler.assemble /
     SynthesisEngine / NarrativeGenerator / DashboardDataProvider, or any second Home weather merge point
     (HomeInstallmentWeatherSource is merged only in HomeViewModel after compute())
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
  → 6-layer cascade (Exact → Canonical → Greeklish → Fuzzy → Semantic → Context)
  → Returns CategorizationResult with MatchType + confidence
  → NOT write-free: the cascade calls MerchantNormalizer.normalize(), which even with
    autoCreate = false can INSERT or UPDATE a merchant_aliases row on a fuzzy match >= 0.95
    (MerchantNormalizer.kt:98-103 → MerchantNormalizationRepository.linkAliasToCanonical →
    DAO insertAlias / occurrenceCount update) and logs the raw name (Timber.w, :102). A
    merchant_canonicals row is created only when autoCreate = true (createNewMerchant() /
    repository.insertCanonical(), :111-114). Any caller
    that must be write-free (for example the quick-add Fill) must NOT call categorize*,
    CategorizationEngine.normalize(), MerchantCategoryRepository.getCategoryForMerchant()
    (it calls normalize()) or MerchantNormalizer.

READ-ONLY learned category lookup (nl-quick-add C2, no persistent side effects):
  → QuickAddLearnedCategoryLookup.lookup(merchant, pickerCategories)  [domain/quickadd, suspend]
  → key = CategorizationEngine.normalizedCanonicalNameForMerchant(merchant)
    (pure: MerchantCanonicalizer.canonicalize + GreeklishNormalizer.normalize; no I/O, no logging)
  → MerchantCategoryRepository.getCategoryByNormalizedCanonical(key)  (pure DAO read; deterministic
    order: confidence DESC, timesUsed DESC, merchantPattern ASC)
  → the row is used only if its categoryId is in the picker list; else Absent
  → a non-cancellation exception becomes a failure result (the caller logs the bounded code
    QUICK_ADD_CATEGORY_LOOKUP_FAILED, no throwable); CancellationException is rethrown
  → Result feeds the Add-Expense form only (AddExpenseViewModel.fill); persisted solely by the
    normal Save path (EXPENSE CREATE above), never by the lookup

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
  ❌ QuickAddLearnedCategoryLookup (domain/quickadd) calling categorize*/categorizeWithContext/
     debugCategorize, CategorizationEngine.normalize(), getCategoryForMerchant(), MerchantNormalizer,
     traceDecision, or any write (insert/learnPattern/learnMerchantCategory), or logging merchant/key
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

TEXT-PDF ROUTING (statement-import, debug-only feature):
  → ReceiptOcrService.processStatementUri() reads every text page (<= 200 pages / 2M chars) and
    exposes OcrResult.pdfTextPages; non-text PDFs and images keep the OCR path (5-page cap)
  → processor routes pdfTextPages through domain/receipt/statement/TextStatementParser
    (Revolut, NBG account, Eurobank card, Eurobank transfers layouts). A recognised layout that
    fails fails the run cleanly; an unknown layout uses BankStatementParser with requireDate = true
  → parser output is authoritative for amount, currency, date, type, direction and counterparty
    label on recognised layouts; AI may only offer a scrubbed merchant on non-TRANSFER rows and
    never drops a row. AI validation is batched (40 candidates per call, at most 5 calls = 200
    candidates per run; later candidates stay unvalidated, never dropped). TRANSFER rows on a
    recognised layout are not AI candidates; the AI text is whitespace-collapsed, scrubbed, then cut
    to 4,000 chars at a token boundary
  → unreadable/undated/cancelled/unsettled/over-ceiling (100,000) rows become STATUS_SKIPPED
    ledger items with bounded reason codes; run status COMPLETED_WITH_SKIPS (FAILED only if an
    item failed or every row was skipped)
  → transfers: TRANSFER with direction and label; IBANs are never stored (label is
    "<BANK> ****last4"); TRANSFER rows skip merchant learning
  → pending-duplicate check excludes reviews created by the same run

FORBIDDEN:
  ❌ Dating a row without a date as "today" on the text path (skip it as NO_DATE)
  ❌ Persisting an IBAN, a card number, or a digit run of 12+ digits (9+ on transfer labels) in any merchant/label/account field
  ❌ Letting AI output override parser money on a recognised layout
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
  → ReceiptSideEffectPlanner (Policy A: ephemeral raw OCR only) →
    AutoCreateWarrantyFromReceiptUseCase.execute(receiptId, ephemeralOcrText)
  → WarrantyTextExtractor extracts warranty terms via regex patterns (local only)
  → Confidence bands (independent of cloud/privacy settings):
      c ≥ 0.70          → Warranty with status=ACTIVE, needsReview=false
      0.40 ≤ c < 0.70   → Warranty with status=PENDING_REVIEW, needsReview=true,
                          extractionSource="ocr" (no separate draft entity)
      c < 0.40          → nothing persisted
  → Checks existing warranties on same receipt (dedup; AlreadyExists only upserts the return window)
  → Half-open (exclusive) end-date semantics
  → Receipt OCR text reaches the use case only as ephemeral raw text via
    ReceiptSideEffectPlanner Policy A. There is no fallback to persisted or sanitized text; when
    the ephemeral text is unavailable the side effect is Skipped (SIDE_EFFECT_SKIPPED_PRIVACY) and
    the use case is not invoked. Receipt rows are read only through ReceiptRepository.
    No PrivacyGate capability applies to local regex extraction. Any future cloud or model step
    requires PrivacyGate.check(CLOUD_AI_*) before data leaves the device.
  → WarrantyTrackerRepository is the sole warranty writer (addWarrantyIgnoreConflicts → WarrantyDao)

RETURN WINDOW (receipt-driven):
  → WarrantyTrackerRepository.upsertReturnWindowForReceipt(receiptId, warranty?)
  → writeBarrier.checkWritesAllowed → structured receipt fields via ReceiptRepository
    (parsedMerchant, parsedDate/createdAt, expenseId, sourceType, documentType, processingStatus)
  → returnDays = merchant default table (Amazon 30, Best Buy 15, Apple 14, else 30);
    returnConditions / returnPolicyUrl are never produced (null on insert)
  → insert, or update preserving status, returnedAt, refundAmount, createdAt and existing
    conditions / policy URL

CONFIRM / EXPIRE (review lifecycle):
  → WarrantyTrackerRepository.confirmWarranty(warrantyId): barrier check first → one transaction:
    fresh read by id → NotFound | AlreadyConfirmed (no write, no event) | needsReview=false,
    status = EXPIRED if already EXPIRED or warrantyEndDate < now, else ACTIVE → CONFIRMED
    lifecycle event (fail-closed in transaction). Never moves EXPIRED → ACTIVE.
  → WarrantyTrackerRepository.reconcileExpiredItems(now): barrier check first → sole writer of any
    transition into EXPIRED: status ACTIVE/PENDING_REVIEW with warrantyEndDate < now → EXPIRED
    (needsReview unchanged; expired pending = "expired unconfirmed"); one batch EXPIRED event with
    counts only; idempotent
  → rejectAutoDetectedWarranty (existing): deletes warranty + return window atomically
  → Review state for UI is derived only by WarrantyReviewState.of(warranty, now)
  → Reminders consider status='ACTIVE' only (PENDING_REVIEW / EXPIRED never get reminders)

FORBIDDEN:
  ❌ Warranty promotion out of review (needsReview=false / status ACTIVE / PENDING_REVIEW→ACTIVE) by any path other than WarrantyTrackerRepository (confirmWarranty, or promoteReviewDraft which applies a content-only edit to a fresh in-transaction read and then runs confirmWarranty in the same transaction) (F-AI-1; AutoCreateWarrantyFromReceiptUseCase calls promoteReviewDraft only; WarrantyPromotionBypassGuardTest enforces that no function outside WarrantyTrackerRepository.kt pairs an updateWarranty call with a promotion write; DAO writes are covered by the db_access ownership guards)
  ❌ Creating warranty without confidence assessment
  ❌ Auto-creating an ACTIVE warranty below 70% confidence threshold
  ❌ Creating duplicate warranties for same receipt
  ❌ Using inclusive end-date semantics for warranty expiry
  ❌ Cloud/AI extraction in the warranty or return-window path
  ❌ Reminders for non-ACTIVE warranties
  ❌ Reading persisted rawOcrText for warranty or return-window derivation
  ❌ ViewModel-built warranty status/needsReview changes (use confirm/reject/claim)
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
                                 (caller: AddExpenseViewModel, loan S11a, via
                                 ManualExpenseRepository.buildInstallmentPurchaseRequest; enabled by S13 on this unreleased branch)
  createStandalone            → [txn: validate category, insert plan + entries]
  OD-14 currency rule (PE-A)  → InstallmentSplitCurrencyRule.equalSplitAllowed is the single definition: an
                                 EQUAL_SPLIT purchase plan must be in the purchase currency. declareOnExpense and
                                 declareWithNewExpense (pure-check block, before the TLC create) and the currency
                                 change of editDetails (after CURRENCY_LOCKED) refuse CURRENCY_MISMATCH; the declare
                                 sheet enables the equal-split chip through the same function. FIXED_PAYMENT may
                                 cross currencies. No new owner, no new write path.
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
                                 candidate deferral via InstallmentCandidateChargeProbe (read-only);
                                 an entry due before the plan's declaredOn never auto-records (rule
                                 declaredOn <= due <= today, isAutoRecordApplicable); reconcile() never creates
                                 an expense (PE-A proof test)
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

UI CALLER (S10b-1, behind InstallmentsFeature.ENABLED = true on this unreleased branch, owner-approvals 91(b)):
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
                              → Enforced by InstallmentPlanArchitectureGuardTest POST_COMMIT_RUNNER_OUTSIDE_DISPATCHER (domain/installment, service/installment, ui/screens/installments; the dispatcher's exact path is the only exemption). Since owner-approvals 59c the rule also covers the public post-commit wrappers PostCommitSideEffectEvidenceService (runWithEvidence / runBestEffortWithEvidence), TransactionSideEffectDispatcher and ReceiptSideEffectDispatcher: none of them may be referenced from those three prefixes, so the dispatcher stays the single owner (known limit: a typealias declared outside the prefixes; installment code outside the three prefixes such as di/InstallmentModule.kt and data/repository/Installment*Impl.kt)

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

---

## Assistant / Financial Query

```
SUBMIT assistant query (no expense/receipt/recurring writes; the only writes are the
chat rows via AiChatRepository.appendMessage, which checks the write barrier):
  → AssistantViewModel.submitQuery
      snapshots prior chat rows BEFORE persisting the current user turn
      (only when AiSettings.storeConversationHistory is on AND a session id exists;
      otherwise empty)
  → InterpretFinancialQueryUseCase (passes the raw prior rows through unchanged;
    falls back to local interpretation when no provider answers)
  → FinancialQueryInterpretationInputBuilder (SOLE constructor of
    FinancialQueryInterpretationInput):
      priorUserQueries = USER rows only, last
      AppConfig.Ai.MAX_QUERY_HISTORY_USER_TURNS_FOR_MODEL, oldest first,
      each sanitized, blanks dropped; no ASSISTANT/SYSTEM text, no payloadJson
      redaction = AiPolicy.canUseCloudFor(QUERY_INTERPRETATION)
                  && EffectiveCloudAiPolicyResolver.resolve().redactBeforeCloud
  → HybridRouter / providers
      on-device: OnDeviceQueryInterpretationService (renders "- USER: <text>" lines)
      cloud: CloudQueryInterpretationService → CloudAiPrivacyGate →
             DefaultCloudPayloadPolicy.prepareText → audit
  → ExecuteFinancialQueryUseCase (read-only)

Clarification completion (structured clarification option tap; in-memory pending state only):
  → AssistantViewModel.onClarificationOptionSelected(itemId, optionIndex)
      consume the live pending clarification FIRST, then execute
  → CompleteFinancialQueryClarificationUseCase (pure slot fill; PERIOD via AssistantPeriodResolver)
  → ExecuteFinancialQueryUseCase (read-only; no re-interpretation)
  retryLast on a completed turn re-executes the completed intent the same way.

Assistant period ranges and comparison cue only via AssistantPeriodResolver.

FORBIDDEN:
  ❌ Constructing FinancialQueryInterpretationInput outside FinancialQueryInterpretationInputBuilder
  ❌ ASSISTANT/SYSTEM turns, app-computed answers or message payloadJson in any
     query-interpretation prompt (on-device or cloud)
  ❌ AssistantViewModel (or any UI layer) shaping, filtering or redacting model history
  ❌ Assistant query interpretation reading PrivacySettings.redactBeforeCloud directly or OR-ing
     redaction flags outside EffectiveCloudAiPolicyResolver
  ❌ Re-interpreting a structured clarification tap (calling InterpretFinancialQueryUseCase with the
     option label)
  ❌ Calling providers or the network on clarification completion
  ❌ Persisting pending clarification state (it is in-memory only; restored transcripts never regain it)
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
     and `sendInstallmentNotification` (S7b, D-M1 A; no actions, no content intent; resolved
     inside the claim→post try)
  RE-COUNT (loan S13-fix, 2026-10-30): INV-12 defines a composition site as a place that composes a
  notification and declares exactly one kind (domain-spec INV-12 / AMD-17 / 4.1). There are now SEVEN
  sites over the unchanged SIX-kind registry: the six spec sites plus the installment reminder
  (`BillReminderWorker.sendInstallmentNotification`, :277/:308). Both BillReminderWorker sites (bill
  reminders :158 and installment reminders :277) declare BILL; the installment site uses the BILL
  presentation with no actions and no content intent. The posting paths (`AndroidNotificationService`
  postForKind :205/:218 and the action-policy check :326-327) are builder owners, not extra sites.

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
        when the store is unreadable; N1: D5 is also emitted for action-less installment posts on API <= 30,
        ACCEPTED as bounded diagnostic noise, reason code only) → NotificationContentPolicy.decideDiscreet(kind, sdkInt, actionsSuppressed);
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
  → then verifySweepOnce() (G1b-0, implemented 2026-10-29; see "G1b-0 post-sweep verification" below)
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
  G1b-0 post-sweep verification (IMPLEMENTED 2026-10-29; closes the G1a carry-forward):
    • NotificationPresentationApplier stamps a content-free mode token "D" (discreet) / "F" (full) in the
      PRIVATE extras next to NotificationKindMarker.EXTRA_KEY; never on the public version
    • AndroidNotificationService.repostActiveAsDiscreet then runs verifySweepOnce(): settle
      SWEEP_VERIFY_SETTLE_MS = 750 ms (paid on every sweep, accepted latency; it never changes
      RepostResult or the SWEPT outcome) → re-read activeNotifications ONCE → re-post ONCE (paced 300 ms)
      every sweep-kind, non-summary notification whose token is NOT "D" (an absent token counts: pre-1a /
      marker-less originals and the residuals above) via rebuildDiscreet → ONE
      NOTIF_REPOST_UNCONFIRMED(COUNT) via PrivacyDiagnosticsEmitter (COUNT only, no ids/text)
    • never loops, never blocks cleanup, CancellationException propagates; verify re-posts and failures are
      NOT counted in RepostResult (a failure emits NOTIF_REPOST_FAILED(EXCEPTION_CLASS))
    • residuals: the verify pass does not re-check the effective discreet value, so a legitimate FULL post
      within about 750 ms + N x 300 ms of turning discreet off can be re-posted discreet (fail-closed); a
      notification dismissed between the read and its paced notify can be resurrected (same accepted race as
      the first sweep; setOnlyAlertOnce(true))
  Triggers: per-field discreet write (AppLockSettingsOperations.requestDiscreetChange, WRITE 5) and a committed review-save
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

## Deep Links (app-lock-privacy-mode, increments 1a / 1b)

Increment 1a is the policy, applier and manifest below; increment 1b (S21) is the router with the held-link gate. Both
ship together with the lock (S20 never ships without S22; G1b before release).

```
ENTRY (the only one):
  → MainActivity.handleIntent(intent)
  → DeepLinkPolicy.evaluate(intent.action, intent.dataString)   [ui/navigation, object in
    DeepLinkParser.kt; pure, no IO, never throws, never logs; the ONLY parser of expensetracker://]
  → NotADeepLink → handleIntent returns false (not a link at all)
  → otherwise AppLockDeepLinkRouter.route(result, lifecycleScope, mainViewModel)   (S21-b; true = the
    intent data is consumed one-shot, even for a rejected link)

ROUTER (S21-b, PRV-16; ui/applock/AppLockDeepLinkRouter, unscoped, one per Activity; the ONLY production
caller of AppLockController.onLockQuery / releaseHeldLink / releaseOpportunity):
  • route(Accepted) → controller.onLockQuery(request):
      APPLY_NOW → scope.launch { DeepLinkApplier.apply }  (afterUnlock = false, drops reported)
      HOLD → DeepLinkApplier.onHeld: the dropped-parameter codes only (D-S21-4); no lookup, no
             briefing write, no navigation. The controller holds the link in memory (never persisted;
             process death drops it; newest wins; T_held = 5 min).
  • route(Rejected) → open state (DISABLED, UNLOCKED, INERT) → DeepLinkApplier.apply (Home, or ignored
    for a non-VIEW action); otherwise DeepLinkApplier.onRejectedWhileClosed: the bounded DL_REJECTED_*
    code only, no navigation, the held slot untouched (D-S21-3)
  • startReleasing(owner, mainViewModel) (once, MainActivity.onCreate after the DB gate and the secure-window
    bind, before handleIntent and setContent; never from onNewIntent): while STARTED, collects
    controller.state combined with controller.releaseOpportunity (the post-T33 signal). On each emission
    with an open state it POSTS launch(Dispatchers.Main) (never Main.immediate, never Unconfined, never
    inline under the publication) which calls controller.releaseHeldLink():
      Apply → DeepLinkApplier.applyReleased (afterUnlock from the release; drops not re-reported; the
              lookup and the briefing write happen here)
      Expired → DeepLinkApplier.applyExpired: Home only (the controller recorded DL_HELD_EXPIRED)
      None → nothing (locked, resolving, prompt pending, T33 gate open, or backgrounded: the link stays held)
  • AE-118 settings-prompt hold (1b): a link that arrives, or is held, while an app-lock settings prompt is open
      (ENABLE / DISABLE / CHANGE: lock switch, timeout change, review-save) is HELD: releaseHeldLink returns None
      while the prompt is pending and while the T33 gate is open, so no navigation and no lookup happens under the
      prompt, and the settings screen stays composed (INV-53). The prompt ending (success, cancel, error or host
      loss, T35) does not release the link by itself: the controller bumps releaseOpportunity (S21-a, after
      onOpenOriginApplyCompleted closes the T33 gate with a link held) and the router's collector posts
      releaseHeldLink() on Main. A link held across PROMPT_HOST_LOST is applied once, whether it arrived before or
      after the host loss. T_held (5 min) still applies; an expired link goes Home only. A finishing host (not a
      configuration change) cancels the system prompt silently (no outcome): the adapter drops its in-flight sink,
      cancels the library prompt and delivers no callback; the loss itself is reported once by the prompt host.
  • Accepted residuals: R-S21-1 a lookup that began while open can finish after a re-lock (the navigation is
    buffered and nothing is composed, and its bounded DL_APPLIED / DL_BRIEFING_* outcome may be recorded
    after the re-lock); R-S21-2 a rotation during a released-link lookup loses that apply.
  • Ordering note (not a privacy issue, the app is unlocked): while the app is open but the T33 gate is still
    open, a rejected link goes Home immediately and an older held link still applies after the gate closes
    (reversed order).

EVERY expensetracker: URI is untrusted, internal notification taps included (F9). The manifest is
not the security boundary: the exported launcher activity accepts any URI, so validation here is.

POLICY (spec 5):
  • action must be ACTION_VIEW, else Rejected(ACTION) → ignored, no navigation
  • raw data string > 2048 chars → Rejected(MALFORMED) before parsing
  • not hierarchical, any '#', non-ASCII before the query, userinfo, port, or a path other than
    empty or "/" → Rejected(MALFORMED)
  • host: exact-case closed set DeepLinkHost = home, dashboard, activity, review, plan, add,
    analytics, map, installments, installment_plan (10 hosts; owner-approvals 91(a)); bank hosts →
    Rejected(BANK_HOST); any other host → Rejected(UNKNOWN_HOST). Loan S13-a: `installments` and
    `installment_plan` are accepted only when evaluate(..., installmentsEnabled = InstallmentsFeature.ENABLED)
    is true; while false both are Rejected(UNKNOWN_HOST) exactly like any unknown host and land on Home.
  • one allowed parameter per host, everything else dropped and counted:
      home/dashboard: briefingKey = "dashboard_home:" + real yyyy-MM-dd (<= 32 chars)
      activity: expenseId = 1..Long.MAX_VALUE (1..19 ASCII digits)
      analytics: period from TimePeriodTokens (shared allowlist)
      map: location, trimmed, 1..100 chars, no control/format characters
      review/plan/add: none (no link creates or prefills data, INV-17)
      installments: none
      installment_plan: id = 1..Long.MAX_VALUE (1..19 ASCII digits); a missing, duplicate or invalid id
        rejects the whole link (Rejected(MALFORMED), no new rejection value)
    duplicate or invalid values are dropped to the host default (installment_plan excepted, above)
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
  • installments / installment_plan (loan S13-a, C1) → NEVER navigates directly: the applier calls
    MainViewModel.requestDeepLinkConfirmation(destination); MainScreen (inside AppLockShell) shows a
    confirm dialog (no plan name or amount: the link is untrusted); Confirm → confirmPendingDeepLink
    (navigateTo, then clear), Cancel/dismiss → dismissPendingDeepLink (no navigation). A held link reaches
    applyAccepted only through applyReleased, so the confirmation state is set only after unlock; the router
    is unchanged. An internal tap (Transactions installment chip) navigates directly, no confirmation.
  • diagnostics: bounded DL_* codes (host / parameter enums, booleans, counts, exception class)
    launched on @ApplicationScope, never awaited; no raw URI, host string, briefingKey or parameter
    value in any log, diagnostic or AiRuntimeDiagnostics message (INV-18)
  • apply (APPLY_NOW) uses afterUnlock = false; a released link uses the controller's afterUnlock
    (S21-b: the held-link gate is the router above, no longer "increment 1b")

MANIFEST (INV-20, OD-3a):
  • MainActivity VIEW filter: action VIEW + category DEFAULT + 10 <data> entries for the scheme
    expensetracker (hosts above; installments and installment_plan added by loan S13-a, inert while the
    policy flag is off). NO BROWSABLE category: links from web browsers no longer open
    the app. Other installed apps can still send VIEW; DeepLinkPolicy is the boundary.
  • Notification taps use an explicit MainActivity component + ACTION_VIEW + FLAG_IMMUTABLE
    (built only in AndroidNotificationService; BillReminderWorker builds no content intent)

FORBIDDEN:
  ❌ Parsing, matching or logging an expensetracker: URI anywhere outside DeepLinkPolicy
  ❌ Acting on a link anywhere but DeepLinkApplier
  ❌ Applying a link without the router's controller query (MainActivity calls only router.route and
     router.startReleasing; it never touches the controller's link entries)
  ❌ Calling the held-link release inline, from Main.immediate / Unconfined, or off the main thread
  ❌ Persisting a held link, or reporting a held link's lookup / briefing write before its release
  ❌ A link that creates, edits, deletes or prefills data (INV-17)
  ❌ A persistent write from a link other than MarkDashboardBriefingOpenedUseCase (equals-last-delivered)
  ❌ Re-adding android.intent.category.BROWSABLE to the expensetracker filter
  ❌ Raw URI / host / briefingKey / parameter values in logs or diagnostics
  ❌ Swallowing CancellationException in the applier
```

---

## App Lock Settings Write Path (app-lock-privacy-mode, increments 1a / 1b)

S23 status (docs, 2026-10-29): WRITE 3 (lock enable/disable, incl. T19), WRITE 4 (timeout), WRITE 5 (discreet and
secure screen) and WRITE 2b (the FULL review record, D-S22-7) are all LIVE through the 1a settings ViewModel; nothing
is dormant. Cancelled, failed, refused and not-launched outcomes of the lock switch, timeout and review-save follow
ux-flows 4.3: silent outcomes show nothing, and a refusal shows only a bounded, own-string notice (never library
text). The device-mode disable dialog does not exist (D-S22-2): turning the lock off is one system prompt (or none in
INERT, T19). Release gate: S20 never ships without S22; G1b before release.

```
STORE:
  AppLockSettingsRepository (domain/privacy/applock; impl data/privacy/applock, own DataStore,
  device-local, never Room, never in .costbackup) is the ONLY reader and writer of the app-lock /
  privacy settings (INV-1). Consumers observe committed state only (INV-46): no optimistic value.
  Unreadable states never render or evaluate as Off (INV-2): the effective discreet value is
  fail-closed (on) via EffectivePrivacyValues.

READ for a notification: NotificationPresentationResolver (see NOTIFICATION PRESENTATION).
READ for the UI: AppLockPrivacySettingsViewModel ← AppLockSettingsRepository.observeLoadState().
  S22-a.1 (owner item 81b, O-1) adds exactly two read-only reads of the controller for the lock row: its `state`
  StateFlow (the lock row is Waiting ONLY while the state is Authenticating, B-3; the committed appLockEnabled alone
  decides the switch value, INV-46) and its `openOriginEnd` StateFlow (the inline lock notices; the value at
  construction is a baseline and never an event, B-2; a notice follows the end's prompt, B-1: LOCK_ON only for an
  ENABLE end, LOCK_OFF only for a DISABLE end incl. T19's negative attemptId, a CHANGE end never produces a lock
  notice). The VM also reads DeviceAuthCapabilityProvider.current() as a DISPLAY HINT only (INV-23): it picks the
  unavailable explanation (OS / NOT_SECURE / NOW); the hint may suppress an enable tap (fail-closed) and never
  authorizes a request: the Operations' own T26 capability read decides. The ViewModel's controller-member set is pinned to EXACTLY {requestSettingsReread, state, openOriginEnd}.
READ for the unsupported-database export gate (app-lock S21-c, #10): UnsupportedDatabaseExportAuth calls
  AppLockSettingsRepository.getLoadState() once per export tap, bounded at 2 s (a timeout, a throw or an unreadable
  store all count as unreadable, never as off). Read-only: no write, no controller, no observeLoadState.
  F6: the in-flight read is abandoned on onStop (cancelled, state reset, generation token invalidates a stale
  job) and the prompt or picker starts only at lifecycle >= STARTED and not finishing.
READ + RECOVERY for the app-lock state (S18 ii-c, INV-47, INV-51; requestSettingsReread has ONE production caller since S20-e, the 1a ViewModel retry): AppLockController
  is the only reader that drives lock state. Cold start: resolve() (T1-T6, T6b). While the load state is
  transient-unreadable it re-reads on its own scope at each APP_FOREGROUNDED (never awaited by the foreground
  evaluation) and on requestSettingsReread() (its ONE production caller, the 1a ViewModel retry). "Transient
  unreadable" is an Unreadable view with cause IO_ERROR or TIMEOUT, and also (S21-a, 61c-2) the controller's own
  RESOLVE_TIMEOUT fallback: no loaded view yet (loadState == null) and resolvedByTimeout. Every re-read is
  bounded at RESOLVE_TIMEOUT_MILLIS (2 s); a re-read that outlives the bound changes nothing (no view, no state
  change; the next foreground re-reads). The result goes through the PRIVATE, sequence-checked onSettingsReread, never through onLateSettingsResolved:
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

WRITE 1 — per-field discreet toggle (1a; since S20-d (S18-ii-g, D4 option (a)) it is WRITE 5 reached from the
ViewModel: the unforced UpdateDiscreetNotificationsUseCase and AppLockSettingsRepository.setDiscreetNotifications
are RETIRED, and the F3 pin counts zero production call sites of `.setDiscreetNotifications(`. WRITE 3 (lock flag)
is wired by S22-a.1 under the S22 plan amendment and owner-approvals 81b (O-1); WRITE 4 (auto-lock timeout) and
requestSecureScreenChange are wired by S22-b.1 from the same ViewModel):
  → AppLockPrivacySection (UI) → AppLockPrivacySettingsViewModel.setDiscreetNotifications
     (ignored unless Ready, idle and not forced, INV-44, INV-48)
  → appScope.launch (@ApplicationScope, IO) { withContext(NonCancellable) {
        handlePlain(AppLockSettingsOperations.requestDiscreetChange(enabled)) } }
     (NO outer AppLockSettingsWriteSerializer: the operations take it inside, and the lock is non-reentrant)
  → AppLockSettingsOperations.requestDiscreetChange(enabled)  (= WRITE 5, below)
      1. before = effective discreet from getLoadState() (trusted only for Loaded / FirstRunDefault;
         Unreadable counts as was-off)
      2. AppLockSettingsRepository.setDiscreetNotificationsIfNotForced(enabled)
         (Rejected(UNREADABLE) when the store is unreadable; Rejected(FORCED) while the lock is on;
         Failed(DISCREET) when the commit fails)
      3. on Committed ONLY → DiscreetRepostTrigger.onCommittedWrite(before, after)
            sweeps only on the false → true edge: NotificationService.repostActiveAsDiscreet()
            (see NOTIFICATION PRESENTATION); off never re-posts; on → on is not an edge;
            SWEEP_FAILED is returned only when the sweep itself throws (non-cancellation): recorded as
            NOTIF_REPOST_FAILED(EXCEPTION_CLASS); the committed write is NOT failed; CancellationException
            rethrown. Per-notification failures, and a SecurityException on the active-notification query,
            come back as RepostResult.failed with their own NOTIF_REPOST_FAILED and the trigger returns SWEPT
      4. Failed or UNREADABLE → the operations report it to the controller, which records
         LOCK_SETTING_PERSIST_FAILED(FIELD=DISCREET) once per (code, field); REJECTED_FORCED records nothing and
         sweeps nothing
  → the committed value reaches the UI only through the store observer. The ViewModel maps PlainSettingResult
    exhaustively (no else): COMMITTED does nothing; FAILED, REJECTED_FORCED and ALREADY_IN_FLIGHT show the
    SAVE_FAILED notice (owner decision 2026-10-24) and re-read. An exception from the operations (non-cancellation)
    still shows SAVE_FAILED and records PERSIST_FAILED(DISCREET) in the ViewModel (the one retained VM diagnostic)

WRITE 2 — review-save (1a; replaces a CORRUPTED / INVALID_VALUE store). SINGLE PATH since S20-e (S18-ii-h, D1):
  → AppLockPrivacySettingsViewModel.saveReview (review card; the only write accepted in that state)
  → @ApplicationScope + withContext(NonCancellable) { AppLockSettingsOperations.requestReviewSave(record) }
    with NO outer serializer (the operations take the shared AppLockSettingsWriteSerializer inside; the mutex is
    non-reentrant). The VM no longer calls AppLockSettingsRepository.reviewSave and holds no serializer or trigger.
  → the record is the FULL review record since S22-b.1 (owner-approvals item 55, D-S22-7; it replaces the D2 record
    {lock off, IMMEDIATE, discreet = the choice, secure ON}): {lock = the lock choice, timeout = the timeout choice,
    discreet = the stored choice, secure = the stored choice}. The card starts at the fail-closed record F: the lock
    defaults to ON, the timeout to IMMEDIATE, discreet and secure to ON. The lock choice is OFF-ONLY while the
    controller is INERT (INV-45: never offered On; the effective choice is false whatever was chosen). While the lock
    choice is On, discreet and secure are forced (read-only, shown On) and the timeout row is shown; while Off, they
    are editable and the timeout row is hidden. "On" is not an enable flow: no explainer, no prompt of its own. A
    lock-on record whose fresh capability is not AVAILABLE is REJECTED_CAPABILITY (INV-45), shown as SAVE_FAILED with a
    re-read, and the card stays so the person can choose Off. Weakening (ReviewSaveAuthorisation: lock off, or a
    timeout longer than IMMEDIATE) from UNLOCKED confirms through the CHANGE prompt (WRITE 2b); from INERT under D3 it
    needs none. The NeedsReview state gets its lock choice only through this card (no separate lock row)
  → the rest of the path is WRITE 2b below. Diagnostics (LOCK_SETTINGS_REVIEW_SAVED,
    LOCK_SETTING_PERSIST_FAILED(REVIEW_SAVE)) and the post-commit sweep are owned by the controller and the
    operations (D7), once per (code, field) per process; the ViewModel emits nothing for a review-save
  → the ViewModel maps ReviewSaveResult exhaustively (no else): COMMITTED resets the choice, keeps the card busy
    until the observer reports and shows REVIEW_SAVED; STARTED shows no notice at once and arms a VM-private
    pending-review-save flag (owner-approvals 64): the confirmed save commits later and reaches the UI only through
    the store observer (INV-46), and when the observer moves from the review category to a readable state while the
    flag is armed it shows REVIEW_SAVED exactly once and consumes the flag (no controller or Operations API added).
    The flag is replaced by every later review-save result except ALREADY_IN_FLIGHT (the confirmed apply may still
    be writing), and cleared by retry() or by the
    observer leaving the review category for loading/transient; a readable state without the flag shows no notice.
    FAILED shows SAVE_FAILED;
    REJECTED_CAPABILITY, NOT_IN_REVIEW, NOT_IN_ORIGIN and ALREADY_IN_FLIGHT show SAVE_FAILED and re-read.
    An exception from the operations (non-cancellation) shows SAVE_FAILED and records nothing in the VM
  → retry() (and the foreground refresh while transient-unreadable) also calls AppLockController.requestSettingsReread()
    (its ONE production caller, the 1a ViewModel; pinned)
  → CONDITIONAL since S18 ii-d (F4(a), D8): reviewSave writes ONLY if, INSIDE the same store edit, the stored
    state is persistent-unreadable (CORRUPTED or INVALID_VALUE). Loaded, a first run or a transient state gives
    Rejected(NOT_UNDER_REVIEW) and nothing is written, so a stale 1a card can never overwrite a committed
    record (the operations answer NOT_IN_REVIEW and the ViewModel shows SAVE_FAILED).
  → `.reviewSave(` has exactly ONE production caller: AppLockSettingsOperations.kt (pinned)

WRITE 2b — review-save (1b, S18 ii-d; the controller-owned path, LIVE since S20-e: its one caller is the 1a
ViewModel above):
  OWNER: AppLockSettingsOperations.requestReviewSave(record) (the only production caller of
  AppLockController.startReviewSaveAttempt / onReviewSavedWithoutPrompt; the `.reviewSave(` caller is pinned
  to exactly {AppLockSettingsOperations}).
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

WRITE 3 — lock flag (S18 part (i-a); requestEnable / requestDisable are LIVE since S22-a.1, their ONE production
caller is the 1a settings ViewModel; requestTimeoutChange and requestSecureScreenChange are LIVE since S22-b.1, from
the same ViewModel, so the Operations have exactly two production caller files: AppLockSettingsOperations.kt and
AppLockPrivacySettingsViewModel.kt):
  CALLERS (S22-a.1): AppLockPrivacySection (UI) → the stored-state switch row
     - Off → tap opens the AppLockEnableDialog explainer (UI state only, nothing requested); Continue →
       AppLockPrivacySettingsViewModel.onEnableConfirmed → AppLockSettingsOperations.requestEnable(), called DIRECTLY
       on Main (not suspend; the controller starts the prompt on its own main dispatcher). Ignored unless the lock
       row is Off.
     - On or paused-On (INERT) → tap → AppLockPrivacySettingsViewModel.onTurnOffRequested →
       AppLockSettingsOperations.requestDisable() directly on Main. NO confirm dialog in device mode (ux 2.2c; the
       system prompt is the confirmation; INERT takes the T19 no-auth branch).
     - No outer serializer, no appScope launch, no NonCancellable in the ViewModel for these two calls: both are
       non-suspend, neither writes on the caller's thread; the apply is launched and serialised inside the
       Operations.
     - SettingsRequestResult is mapped exhaustively (no else): STARTED → nothing (the row follows the controller
       state); REFUSED_UNAVAILABLE → re-read the capability hint, Unavailable variant (a hint that reads AVAILABLE
       shows the enable-failed notice for an enable and the save-failed notice for a disable); ALREADY_IN_FLIGHT, NOT_IN_ORIGIN, REVIEW_REQUIRED → re-subscribe the
       observer. An exception (non-cancellation) shows ENABLE_FAILED (enable) or SAVE_FAILED (disable).
     - The outcome is shown only from the read-only end signal, exhaustively over OpenOriginEndKind and
       OpenOriginPrompt (no else): APPLIED → LOCK_ON (ENABLE) / LOCK_OFF (DISABLE) / nothing (CHANGE);
       NOT_COMMITTED → SAVE_FAILED; CANCELLED → LOCK_NOT_TURNED_ON (ENABLE only); LOCKED_OUT → TOO_MANY_ATTEMPTS;
       ERROR → ENABLE_FAILED (ENABLE only); NOT_AVAILABLE → capability re-read; HOST_LOST, BACKGROUNDED → nothing.
       The ViewModel never emits LOCK_* or any diagnostic for these (D7, F-5) and adds no diagnostic code.
     - No optimistic switch: the switch value is the committed appLockEnabled; a tap never moves it.
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
         LIVE since S22-a.1 (S22 plan amendment, owner-approvals 81b, O-1): the same request entry (requestDisable,
         incl. this T19 branch) is called by the 1a ViewModel (onTurnOffRequested) under the same source-scan pins;
         beginInertDisable and onInertDisableApplied have exactly one production caller, AppLockSettingsOperations.kt.
  WRITE 3 (requestEnable / requestDisable, incl. the T19 branch) is WIRED by S22-a.1; WRITE 4 (requestTimeoutChange)
  and requestSecureScreenChange are WIRED by S22-b.1. The former DORMANT / NOT LEGAL TO WIRE list is CLOSED: no
  Operations request is dormant any more. The BLOCKING items below were the conditions of those wirings and are all
  met or closed:
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
    INV-45: ViewModel routing (S18-ii-h): CLOSED by S20-e (owner D1). The controller-side review authorisation
        was closed by S18-ii-d (WRITE 2b); the review card now routes through
        AppLockSettingsOperations.requestReviewSave, so a weakening review-save authenticates once (CHANGE
        prompt) or, under D3, needs none. The lock enable/disable UI is wired by S22-a.1 and the real
        timeout/secure-screen/lock choices by S22-b.1: the D2 record (lock off, IMMEDIATE, discreet = the choice,
        secure screen on) is REPLACED by the FULL review record (WRITE 2), which is what item 55 required (D-S22-7).
        Release condition (owner item 55a): no release ships S20 without S22; S20 and S22 land as one atomic
        candidate;
    WRITE 5 routing (S18 i-b2): CLOSED by S20-d (S18-ii-g). The 1a ViewModel's discreet write is routed through
        WRITE 5 (requestDiscreetChange), so a forced value can never be written while the lock is on.
        requestSecureScreenChange (the secure-screen row, AE-103) is routed through the same path by S22-b.1.
        EXIT CRITERION (S18 i-b2 final gate ISSUE-4): MET by option (a) (owner item 51a): the use case
        UpdateDiscreetNotificationsUseCase and the unforced setDiscreetNotifications (interface and Impl) are
        retired, with their tests, and the F3 pin counts ZERO production call sites;
  Source-scan tests (S22-b.1 rewrite, a strengthening) pin the caller set of AppLockSettingsOperations and its
  request entries (requestEnable, requestDisable, requestTimeoutChange, requestDiscreetChange,
  requestSecureScreenChange, requestReviewSave) to EXACTLY two production files: AppLockSettingsOperations.kt and
  AppLockPrivacySettingsViewModel.kt. The ViewModel's exempt tokens are exactly AppLockSettingsOperations,
  requestDiscreetChange, requestReviewSave (S20-e), requestEnable and requestDisable (S22-a.1), requestTimeoutChange
  and requestSecureScreenChange (S22-b.1), i.e. the full token list; each exempt token must be present in that file,
  and the section and dialog files (AppLockPrivacySection.kt, AppLockEnableDialog.kt, AutoLockTimeoutDialog.kt) may
  not mention AppLockController, AppLockSettingsOperations, DeviceAuthenticator or the authenticator relay at all.
  The controller-entry pin allows the ViewModel exactly the tokens requestSettingsReread (its retry) and openOriginEnd
  (the end signal getter, S22-a.1, O-1), and the controller-member pin on the ViewModel is EXACTLY
  {requestSettingsReread, state, openOriginEnd}. They also allow
  exactly one call site of setAppLockEnabled, of setAutoLockTimeout, of
  setAutoLockTimeoutIfNotWeakening, of setDiscreetNotificationsIfNotForced and of
  setSecureScreenIfNotForced (that file), ZERO of the retired unforced setDiscreetNotifications, and exactly
  ONE of reviewSave (that file, since S20-e); lifting them needs the items above. S18-ii-e adds beginInertDisable and onInertDisableApplied to the controller-entry
  pins (exactly one production caller each: AppLockSettingsOperations.kt).

WRITE 4 — auto-lock timeout (S18 part (i-b1); LIVE since S22-b.1, its ONE production caller is the 1a settings ViewModel):
  CALLERS (S22-b.1): AppLockPrivacySection (UI) → the timeout row (present only while the COMMITTED lock is on; it
     shows the committed timeout only, AE-116, no optimistic value) → AutoLockTimeoutDialog (stateless radio picker;
     no request of its own) → AppLockPrivacySettingsViewModel.onTimeoutSelected (ignored unless Ready, idle, the row
     present, no prompt in flight and the value differs) → appScope.launch { withContext(NonCancellable) {
     handleTimeoutResult(AppLockSettingsOperations.requestTimeoutChange(timeout)) } } with NO outer serializer.
     TECH-13 (UI hint, D-S22-5): while the controller is INERT the picker disables every option longer than the
     committed timeout and shows applock_timeout_paused_note; the ViewModel also ignores such a choice. The controller
     enforces it (NOT_IN_ORIGIN, nothing written). TimeoutChangeResult is mapped exhaustively (no else): COMMITTED and
     STARTED do nothing (the row follows the committed value and the controller state; the end signal gives the
     notice, a cancelled CHANGE prompt shows none); FAILED, ALREADY_IN_FLIGHT and NOT_IN_ORIGIN show SAVE_FAILED and
     re-read. An exception (non-cancellation) shows SAVE_FAILED; the ViewModel emits no LOCK_* and no diagnostic.
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
  WIRED by S22-b.1; the BLOCKING list under WRITE 3 (F3, F4, F7, finding 8, INV-45) is closed for it.

WRITE 5 — discreet and secure screen, forced-aware (S18 part (i-b2); requestDiscreetChange has had ONE production
caller since S20-d, the 1a ViewModel (WRITE 1); requestSecureScreenChange is LIVE since S22-b.1, ONE production
caller, the same ViewModel: AppLockPrivacySection secure-screen row → AppLockPrivacySettingsViewModel.setSecureScreen
(ignored unless Ready, idle and not forced) → appScope.launch { withContext(NonCancellable) {
handlePlain(requestSecureScreenChange(enabled)) } }; REJECTED_FORCED (AE-103) shows SAVE_FAILED and re-reads; the
row is read-only while the committed lock is on, and the ViewModel emits no diagnostic for it):
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
  The 1a UpdateDiscreetNotificationsUseCase / setDiscreetNotifications path (no FORCED check) was RETIRED in S20-d
  (S18-ii-g, owner D4 option (a), item 51a), and the ViewModel's discreet toggle is routed here, so no unforced
  discreet write path remains (F3 pin: zero call sites of `.setDiscreetNotifications(`). WRITE 3 is wired by
  S22-a.1; WRITE 4 and requestSecureScreenChange are wired by S22-b.1 (INV-45's ViewModel routing, S18-ii-h, is
  CLOSED by S20-e).

SERIALISATION (domain-owned since S18 ii-a, F4 serializer part CLOSED (S18-ii-a); F4(a) review-save in-edit
guard (D8) CLOSED (S18-ii-d)): AppLockSettingsWriteSerializer (@Singleton,
domain/privacy/applock, a process-wide non-reentrant Mutex, withExclusiveWrite) so a write started by an
earlier ViewModel instance still excludes the next one's before-read / write / sweep. ONE instance is shared
by every AppLockSettingsOperations path (WRITE 2b review-save, WRITE 3 lock
apply, WRITE 4 timeout apply and direct timeout write, WRITE 5 plain writes). WRITE 1 reaches it only inside
WRITE 5 (S20-d) and WRITE 2 only inside WRITE 2b (S20-e): the ViewModel holds no serializer and must NOT wrap a
call to the operations, the mutex is non-reentrant. The pre-read, the durable
write, the controller entry and the trigger are one critical section, and no prompt is held inside it.
Nothing called inside it may re-enter it (the ViewModel holds no serializer; Operations never
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
  → promptHost.attach(this) → secureWindow.bind(this) → deepLinkRouter.startReleasing(this, mainViewModel) →
  enableEdgeToEdge → handleIntent → setContent. handleIntent routes only through AppLockDeepLinkRouter (Deep Links,
  ROUTER); onNewIntent unchanged. The binder is installed in MainActivity.onCreate (MainApplication
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

---

## App PIN Store (app-lock-privacy-mode, Segment 28, S24 store, S25 controller paths)

```
S25: the store's only production caller is AppLockController (below). S27 made it user-reachable (App PIN settings
screen and the PIN lock surface, "S27 UI callers" below). Fingerprint ENABLE is deferred to S26b: no path sets
biometricEnabled yet, so fingerprint unlock is dormant and hidden.

OWNER: AppPinStore (domain/privacy/applock/pin; impl data/privacy/applock/pin/AppPinStoreImpl, @Singleton) is the
SOLE owner of the PIN verifier record, the shared attempt state and the PIN flags (biometricEnabled, the biometric
invalidated notice). Own DataStore `app_lock_pin_store` (@AppPinDataStore, noBackupFilesDir, no corruption handler:
unreadable = present-but-unreadable, never absent, never auto-wiped; never in .costbackup, never touched by restore).
Every entry point is serialised by one Mutex.

VERIFY (the ONLY verify path): AppPinStore.verify(CharArray)
  → verifyLocked: read the record → attempt state (unreadable → repaired, AMD-11) → delay evaluation
  → get-only Keystore HMAC key → WRITE-AHEAD increment of the shared counter (INV-39) → PBKDF2 + HMAC compare
  → correct: reset the counter (a failed reset still unlocks), best-effort rehash.
  A matches()/HMAC failure after the write-ahead best-effort restores the previous attempt state, then returns
  VerifierUnreadable(KEYSTORE_ERROR). A failed write-ahead increment returns Unavailable (no evaluation, no unlock).
SETUP / CHANGE: setup refuses (FAILED) while a verifier exists or the store is unreadable, before touching the key;
  a failure after key creation deletes the alias only if a re-read (NonCancellable) finds no verifier (G24-1).
DELETE (INV-49): AppPinStore.deleteAll in order: 1. one awaited clear() of the PIN store (the only decisive step;
  a failure returns FAILED with the settings untouched); 2. delete the HMAC alias, best effort; 3. the
  biometric key (S26), best effort. Idempotent.
SETTING: setLockMode(LockMode) is an AppLockSettingsRepositoryImpl PER-FIELD write (no whole-record rewrite);
  authorisation is the caller's concern.

CONTROLLER PATHS (S25, AppLockController, the ONLY caller; PIN steps are in-app steps inside UNLOCKED, NOT prompts):
  UNLOCK: submitUnlockPin → AppPinStore.verify → Correct → unlockByPin (T23, same effects as a device-auth success);
    Wrong/Delayed/Unavailable/VerifierUnreadable stay LOCKED; a store exception maps to Unavailable.
  SETUP: requestPinSetup (T31 CONFIRM_CHANGE device prompt) → its matching success opens the SETUP step → enablePin:
    re-checks the step inside the settings-write serialiser, writes lockMode=APP_PIN FIRST, then AppPinStore.setup;
    a failure reverts lockMode=DEVICE_AUTH (also on cancellation, unless the verifier is already PRESENT).
  CHANGE: beginPinChange → changePin → AppPinStore.changePin (store-atomic verify and replace; no settings write).
  REMOVE: beginPinRemoval → disablePin: verify the current PIN, then in the serialiser (step re-checked) deleteAll
    FIRST, then lockMode=DEVICE_AUTH (capability AVAILABLE) or RemovedLockOffRequired (capability not AVAILABLE,
    AMD-4/T30; capability read outside the monitor and the serialiser). The controller never writes the lock flag:
    PIN removal with lock-off goes AppLockSettingsOperations.removePin → controller.disablePin → Operations lock-off
    write (setAppLockEnabled(false), serialiser, F7 settle) → controller.onPinRemovalLockOffApplied → DISABLED.
  RECOVERY: retryPinRecovery re-reads the store (and restarts the observation) only while the recovery view offers it.
  FINGERPRINT: requestFingerprintUnlock() (user tap) opens a PIN_FINGERPRINT UNLOCK attempt; only a crypto-verified BIOMETRIC_STRONG success unlocks (S26, D-I2-7 amended).
  reviewSave keeps the STORED lockMode (an absent or invalid stored token falls back to LockMode.DEFAULT).

S27 UI CALLERS (the only ones; PIN steps are in-app steps, never BiometricPrompt attempts):
  ORDERING: setup = CONFIRM_CHANGE device auth (the one fresh authentication) → single "I understand..." acknowledgement
    (UQ-4; no export-first requirement) → PIN entry/confirm → enablePin (lockMode=APP_PIN written BEFORE the verifier,
    revert on failure). Change = beginPinChange → changePin (no prompt, no settings write). Removal = beginPinRemoval →
    AppLockSettingsOperations.removePin (verify; deleteAll in INV-49 order; lockMode=DEVICE_AUTH BEFORE any lock-off
    write; lock-off only when the device cannot authenticate; the removal dialog has a no-screen-lock variant).
  SETTINGS: ui/screens/privacysettings/applockpin/AppPinSettingsViewModel (activity-scoped; onScreenLeft clears held
    entries) → controller + AppLockSettingsOperations.removePin. Route NavigationDestination.AppPinSettings, save
    token "app_pin_settings". The App PIN row sits in AppLockPrivacySection (hidden when the lock is off, disabled when
    paused/INERT). "Back up now" only navigates to Backup & Restore (AE-104).
  UNLOCK: AppLockGate APP_PIN branch → ui/applock/pin/PinLockSurface (no auto prompt) → submitUnlockPin. The fingerprint
    button calls requestFingerprintUnlock only when the biometric key is enabled (dormant until S26b). The forgotten-PIN
    help offers no in-app reset: clear app data or restore an exported backup (INV-42).
  PIN MATERIAL (INV-38): ui/applock/pin/PinPad + PinEntryBuffer hold a CharArray (not saveable, zeroed, cleared on
    ON_STOP); no String TextField; nothing PIN-related is logged.
  D2D / BACKUP (OD-19 Option A, no manifest change): device-to-device transfer moves the settings but never the
    verifier or the Keystore keys; the new device resolves to DEVICE_AUTH or INERT (AMD-4), fail closed, no stranding.

❌ No other code reads or writes the PIN verifier, the attempt state or the PIN flags (no other DataStore).
❌ No other code uses the `applock_pin_hmac_v1` Keystore alias or PBKDF2 for a PIN.
❌ No second verify path: no evaluation without the write-ahead increment, no PIN as String, never logged.
❌ No setLockMode via a whole-record write (reviewSave carries the stored mode through, INV-45).
```

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
