"""GR-08a seed-mechanism tests for the v1 -> v2 DB-policy migration CLI.

Covers the reviewed-seed-rows contract added for GR-08a:

* seed files load through the ordinary v2 loader (full validation,
  within-document duplicate rejection) -- including the REAL tracked seed
  file ``docs/ci/db-findings/GR-08a-seed.yml``;
* seed/legacy duplicate mutation keys fail closed;
* seeded candidates merge deterministically and stay crosswalk-verifiable
  against the accounting artifact (``seedRecords``);
* seedless accounting artifacts stay byte-identical (no ``seedRecords``);
* the promotion gate's accounting key union includes seed keys;
* NEAR-MISS protection: the GR-08a rows authorize EXACTLY their callable
  identity + DAO + operation -- wrong overload, wrong owner, wrong DAO, and
  wrong operation stay unauthorized (exact-match, no wildcards).

GR-08b (MIT-DB-08B) extends the same contract to the three remaining
NotificationProcessingPipeline.kt callables:

* the tracked ``GR-08b-seed.yml`` loads with exactly its thirteen rows
  (the 11 findings-derived rows plus 2 closure rows for
  ``pendingReviewDao.upsertByRawNotificationId`` in processInternal and
  handleNeedsReviewInTransaction -- real writer mutations the findings
  scanner never reported and that only the GR-08a alias-bridge-fixed
  evidence verifier surfaces);
* the combined generation input ``GR-08-seeds.yml`` (the CLI accepts a
  SINGLE --seed-rows value) stays the exact concatenation of the two
  reviewed batch seed files -- a dropped GR-08a row fails closed here
  instead of silently re-unauthorizing batch-1 mutations at promotion;
* NEAR-MISS protection over the GR-08b rows (wrong overload / owner /
  DAO / operation stay unauthorized).

GR-08c (MIT-DB-08C) extends the same contract to the
RecurringLifecycleCoordinator.kt domain-lifecycle callables.  The 28 file
findings collapse to 26 UNIQUE fingerprints (> the 25-fingerprint batch
cap), so the batch was SPLIT by callable groups:

* ``GR-08c1-seed.yml`` -- the occurrence/expense-link lifecycle group
  (linkExpenseToOccurrence, reconcileExpenseLinkAfterUpdate,
  unlinkExpenseFromOccurrenceDetailed, updateOccurrenceStatus;
  10 findings / 10 unique fingerprints; ZERO closure rows -- the blind-spot
  sweep found every mutating DAO call in the file is an abstract
  Room-annotated method already covered by a finding);
* ``GR-08c2-seed.yml`` -- the reminder-delivery lifecycle group
  (regenerateReminderDeliveriesForOccurrence,
  recoverStaleClaimedDeliveries, claimReminderDelivery,
  cancelClaimedReminderDelivery, markReminderSent, markReminderFailed,
  dismissReminderDelivery, snoozeReminderDelivery;
  18 findings / 16 unique fingerprints);
* the combined generation input ``GR-08-seeds.yml`` stays the exact
  concatenation of the FOUR reviewed batch seed files (5 + 13 + 10 + 16 =
  44 rows) -- a dropped earlier-batch row fails closed here instead of
  silently re-unauthorizing that batch's mutations at promotion;
* NEAR-MISS protection over the GR-08c1/c2 rows (wrong overload / owner /
  DAO / operation stay unauthorized).

GR-08d (MIT-DB-08D) extends the same contract to the
ReviewQueueRepository.kt repository-layer callables:

* ``GR-08d-seed.yml`` -- 22 rows: the 19 findings-derived rows (27
  findings / 19 unique fingerprints, within the 25-fingerprint batch cap so
  NO split was required) PLUS 3 closure rows for body-carrying
  @Transaction PendingReviewDao convenience methods the findings scanner
  never reported (``upsertByRawNotificationId`` in markAsRelevant,
  ``bulkUpdateCategoryByMerchant`` in updatePendingReviewCategoryBulk,
  ``bulkRenameMerchant`` in updatePendingReviewMerchantBulk -- the GR-08b
  blind-spot pattern);
* the combined generation input ``GR-08-seeds.yml`` stays the exact
  concatenation of the FIVE reviewed batch seed files (5 + 13 + 10 + 16 +
  22 = 66 rows) -- a dropped earlier-batch row fails closed here instead of
  silently re-unauthorizing that batch's mutations at promotion;
* NEAR-MISS protection over the GR-08d rows, including the closure rows
  (wrong overload / owner / DAO / operation stay unauthorized).

GR-08e (MIT-DB-08E) extends the same contract to the two repository-layer
files NotificationRepository.kt and WarrantyTrackerRepository.kt.  The
combined batch carries 46 findings / 46 unique fingerprints > the
25-fingerprint batch cap, so the batch was SPLIT per the GR-08c precedent:

* ``GR-08e1-seed.yml`` -- NotificationRepository.kt: 23 rows (23 findings /
  23 unique fingerprints; ZERO closure rows -- every mutating DAO call in
  the file is an abstract Room-annotated method);
* ``GR-08e2-seed.yml`` -- WarrantyTrackerRepository.kt: 23 rows (23
  findings / 23 unique fingerprints; ZERO closure rows -- WarrantyDao,
  ReturnWindowDao and WarrantyLifecycleEventDao are fully abstract);
* the combined generation input ``GR-08-seeds.yml`` stays the exact
  concatenation of the SEVEN reviewed batch seed files
  (5 + 13 + 10 + 16 + 22 + 23 + 23 = 112 rows) -- a dropped earlier-batch
  row fails closed here instead of silently re-unauthorizing that batch's
  mutations at promotion;
* NEAR-MISS protection over the GR-08e1/e2 rows, including the
  accessor-normalized rows (the GR-08e source change replaced the
  database-chained ``database.xxxDao()`` receivers with injected
  constructor properties because no chain-form spelling can pass both the
  scanner gate and the v2 evidence verifier; the seed rows spell the
  normalized ``transactionEventDao`` / ``warrantyLifecycleEventDao``
  accessors, and a wrong accessor spelling -- including the historical
  chain text -- stays unauthorized).

GR-08f (MIT-DB-08F) extends the same contract to the
RecurringRuleLifecycleCoordinator.kt domain-lifecycle callables -- the MOST
authoritative writer layer (recurring rule mutations MUST go through it per
docs/architecture/LEGAL_PATHS.md):

* ``GR-08f-seed.yml`` -- 21 rows: the 21 findings-derived rows (21
  findings / 21 unique fingerprints, within the 25-fingerprint batch cap so
  NO split was required); ZERO closure rows -- the blind-spot sweep found
  every mutating DAO call in the file is an abstract Room-annotated method
  already covered by a finding (all five mutated DAOs are fully abstract
  interfaces; the sixth accessor, expenseDao, is called only read-only via
  getExpensesBetween);
* the combined generation input ``GR-08-seeds.yml`` stays the exact
  concatenation of the EIGHT reviewed batch seed files
  (5 + 13 + 10 + 16 + 22 + 23 + 23 + 21 = 133 rows) -- a dropped
  earlier-batch row fails closed here instead of silently re-unauthorizing
  that batch's mutations at promotion;
* NEAR-MISS protection over the GR-08f rows (wrong overload / owner /
  DAO / operation stay unauthorized).

GR-08g (MIT-DB-08G) extends the same contract to the
BankStatementLifecycleProcessor.kt receipt-lifecycle callables -- the
AUTHORITATIVE writer layer for bank statement imports (receipt mutations go
through the receipt lifecycle services per docs/architecture/LEGAL_PATHS.md,
"Bank Statement Mutations": everything happens in
processBankStatement(); BankStatementImportItemDao.insert /
BankStatementImportRunDao.insertRun outside the processor are FORBIDDEN):

* ``GR-08g-seed.yml`` -- 7 rows: the 20 findings collapse to 7 UNIQUE
  fingerprints (all sites live in the single mutating callable
  processBankStatement(android.net.Uri): bankStatementImportItemDao.insert
  x8, bankStatementImportRunDao.finalize x6,
  bankStatementImportRunDao.attachReceipt x2, and one site each for
  bankStatementImportRunDao.insert, bankStatementImportRunDao.updatePdfPartial,
  pendingReviewDao.insert, scannedReceiptDao.update), within the
  25-fingerprint batch cap so NO split was required; ZERO closure rows --
  the blind-spot sweep found every mutating DAO call in the file is an
  abstract Room-annotated method already covered by a finding
  (BankStatementImportRunDao, BankStatementImportItemDao and
  ScannedReceiptDao are fully abstract interfaces; the two body-carrying
  @Transaction convenience methods REACHED from the file --
  ExpenseDao.findDuplicateIdCurrencyAware and
  PendingReviewDao.getPendingDuplicateCandidateInRangeTypeAware -- are
  strictly read-only composites, and PendingReviewDao's MUTATING
  convenience methods are not called from this file);
* the combined generation input ``GR-08-seeds.yml`` stays the exact
  concatenation of the NINE reviewed batch seed files
  (5 + 13 + 10 + 16 + 22 + 23 + 23 + 21 + 7 = 140 rows) -- a dropped
  earlier-batch row fails closed here instead of silently re-unauthorizing
  that batch's mutations at promotion;
* NEAR-MISS protection over the GR-08g rows (wrong overload / owner /
  DAO / operation stay unauthorized).

GR-08h (MIT-DB-08H) extends the same contract to the
ReceiptMatchLifecycleService.kt receipt-lifecycle callables -- the
AUTHORITATIVE writer for match mutations (docs/architecture/LEGAL_PATHS.md,
"MATCH receipt (suggest/approve/reject/clear)":
ReceiptMatchLifecycleService.saveMatchSuggestion() /
approveMatchSuggestion() / rejectAllSuggestions() / clearMatchForReceipt();
"Each operation: DatabaseWriteBarrier check -> withTransaction ->
ReceiptEvent"; FORBIDDEN: ReceiptRepository.saveMatchSuggestion()
[DeprecationLevel.ERROR] and "Any match mutation without ReceiptEvent"):

* ``GR-08h-seed.yml`` -- 13 rows: the 13 findings collapse to 13 UNIQUE
  fingerprints (each per-call-site finding is its own tuple: the four
  match-mutation callables each carry exactly one scannedReceiptDao.update
  + one receiptEventDao.insert site, and the five P9-P1-08/PR12L-3
  diagnostics writers each carry exactly one receiptEventDao.insert site),
  within the 25-fingerprint batch cap so NO split was required; ZERO
  closure rows -- the blind-spot sweep found every mutating DAO call in the
  file is an abstract Room-annotated method already covered by a finding
  (ReceiptEventDao is a fully abstract interface with exactly two methods
  and ScannedReceiptDao is likewise fully abstract -- NEITHER carries a
  body-carrying @Transaction convenience method at all; the only other
  DAO-accessor calls are the nine read-only scannedReceiptDao.getById
  lookups, and the database.withTransaction calls are the
  androidx.room.withTransaction extension, not DAO accessors);
* the combined generation input ``GR-08-seeds.yml`` stays the exact
  concatenation of the TEN reviewed batch seed files
  (5 + 13 + 10 + 16 + 22 + 23 + 23 + 21 + 7 + 13 = 153 rows) -- a dropped
  earlier-batch row fails closed here instead of silently re-unauthorizing
  that batch's mutations at promotion;
* NEAR-MISS protection over the GR-08h rows (wrong overload / owner /
  DAO / operation stay unauthorized).

GR-08i (MIT-DB-08I) extends the same contract to THREE files -- the
data/database GroupTransactionCoordinator.kt coordinator, the domain
RecurringOccurrenceMaterializer.kt recurring lifecycle writer, and the
worker NotificationIntakeWorker.kt.  The combined batch carries 44 findings
/ 27 unique fingerprints > the 25-fingerprint batch cap, so the batch was
SPLIT per the GR-08c/GR-08e precedent, one part per file:

* ``GR-08i1-seed.yml`` -- GroupTransactionCoordinator.kt: 14 rows (14
  findings / 14 unique fingerprints; ZERO closure rows -- every mutating DAO
  call in the file is an abstract Room-annotated method; the two
  body-carrying @Transaction convenience methods in the touched DAOs,
  ExpenseGroupDao.insertGroupWithMembers and GroupMemberDao.setCurrentUser,
  are NOT called from this file; the plan prose's 15th GTC site,
  addExpenseToGroupAtomic's groupExpenseDao.insert, is ALREADY authorized by
  the active policy's legacy MIT-003 direct row, which is why the trusted
  report carries 14 findings and the rescan delta is 291 -> 247, not 246);
* ``GR-08i2-seed.yml`` -- RecurringOccurrenceMaterializer.kt: 6 rows (15
  findings / 6 unique fingerprints; ZERO closure rows -- all four touched
  DAOs are fully abstract interfaces with ZERO @Transaction methods);
* ``GR-08i3-seed.yml`` -- NotificationIntakeWorker.kt: 7 rows (15 findings /
  7 unique fingerprints; ZERO closure rows -- NotificationIntakeDao is a
  fully abstract interface; worker-guard verification performed in source
  before EXACT_POLICY: doWork's entire mutating body runs inside
  WorkerExecutionGuard.runGuardedWithContext, runPrivacyCleanupGuarded wraps
  its own body in a second guard with requiredCapabilities = emptyList() so
  privacy cleanup can always run, and purgePayloadBestEffort is invoked only
  from inside the doWork guard lambda; barrierMode is `workerMediated` for
  every GR-08i3 row, the established mode of the active policy's worker
  rows);
* the combined generation input ``GR-08-seeds.yml`` stays the exact
  concatenation of the THIRTEEN reviewed batch seed files
  (5 + 13 + 10 + 16 + 22 + 23 + 23 + 21 + 7 + 13 + 14 + 6 + 7 = 180 rows) --
  a dropped earlier-batch row fails closed here instead of silently
  re-unauthorizing that batch's mutations at promotion;
* NEAR-MISS protection over the GR-08i1/i2/i3 rows (wrong overload / owner /
  DAO / operation stay unauthorized).

GR-08j (MIT-DB-08J) extends the same contract to FOUR top-density files --
the data/database AppDatabase.kt named-object Room migrations (structural
path, NOT seed rows), the domain ReceiptLinkService.kt receipt-lifecycle
service, the data/store ExpenseWriteStore.kt write facade, and the data
repository SourceStatsRepository.kt.  The combined batch carries 47 findings
/ 34 unique fingerprints > the 25-fingerprint batch cap, so the batch was
SPLIT per the GR-08c/GR-08e/GR-08i precedent into two file groups:

* ``GR-08j1-seed.yml`` -- ReceiptLinkService.kt: 11 rows (12 findings / 11
  unique fingerprints; the two unlinkReceiptFromExpense scannedReceiptDao.update
  sites share one fingerprint; ZERO closure rows -- ReceiptExpenseLinkDao,
  ScannedReceiptDao, WarrantyDao, ReturnWindowDao and
  ReceiptItemCategorizationDao carry ZERO body-carrying @Transaction
  convenience methods, and the file's only ExpenseDao call is the read-only
  getById).  LEGAL_PATHS.md "LINK/UNLINK receipt" names this service the
  authoritative legal path.  The AppDatabase.kt half of GR-08j1 (14
  DB_FORBIDDEN_STRUCTURAL_OPERATION findings on the named-object migrations
  MIGRATION_16_17 / MIGRATION_41_42) is NOT seed-authorized: those findings
  carry no DAO identity, so they are resolved by TWO exact structural
  exception tuples (class MIGRATION_16_17 / MIGRATION_41_42, method_pattern
  migrate, operation execSQL) added to db_structural_exceptions.yml + its
  canonical manifest + the pinned immutable contracts (62 -> 64).  The
  adjudication: legitimate DB-infrastructure writers -- execSQL is the only
  way to perform a Room schema migration, and the in-migration data
  backfill/seeding must stay atomic with the DDL; CODE_FIX was rejected;
* ``GR-08j2-seed.yml`` -- ExpenseWriteStore.kt (11 rows / 11 findings / 11
  unique fingerprints) + SourceStatsRepository.kt (10 rows / 10 findings /
  10 unique fingerprints); ZERO closure rows -- both files are single-
  statement barrier-checked delegates with fully abstract DAOs;
* the combined generation input ``GR-08-seeds.yml`` stays the exact
  concatenation of the FIFTEEN reviewed batch seed files
  (5 + 13 + 10 + 16 + 22 + 23 + 23 + 21 + 7 + 13 + 14 + 6 + 7 + 11 + 21
  = 212 rows) -- a dropped earlier-batch row fails closed here instead of
  silently re-unauthorizing that batch's mutations at promotion;
* NEAR-MISS protection over the GR-08j1/j2 rows (wrong overload / owner /
  DAO / operation stay unauthorized).

GR-08k (MIT-DB-08K) extends the same contract to FOUR files -- the
di/RetentionModule.kt privacy retention-cleanup path, the data/repository
RecommendationRepository.kt, the domain/groups GroupLifecycleCoordinator.kt
authoritative coordinator, and the domain/split EnhancedSplitManager.kt
authoritative split/template manager.  The combined batch carries 37 findings
/ 37 unique fingerprints > the 25-fingerprint batch cap, so the batch was
SPLIT per the GR-08c/GR-08e/GR-08i/GR-08j precedent into two file groups:

* ``GR-08k1-seed.yml`` -- RetentionModule.kt (10 rows / 10 findings / 10
  unique fingerprints) + RecommendationRepository.kt (9 rows / 9 findings /
  9 unique fingerprints); ZERO closure rows -- every mutating DAO call is an
  abstract Room-annotated method already covered by a finding (all ten
  RetentionModule DAOs and RecommendationDao carry ZERO body-carrying
  @Transaction convenience methods reachable from these files;
  PendingReviewDao's mutating conveniences are NOT called from
  RetentionModule).  RetentionModule is the canonical privacy
  retention-cleanup path (DataRetentionWorker -> RetentionRegistry ->
  RetentionTarget.purge); RecommendationRepository is a repository-layer
  legal writer and the SOLE writer of RecommendationDao.  The GR-08k1
  source change normalizes RetentionModule's accessors per the GR-08e
  precedent: the START findings spelled 7 chain-form
  ``appDatabase.xxxDao()`` receivers and 3 method-local aliases all named
  ``dao`` behind THREE different DAOs -- no chain-form spelling can pass
  both the scanner gate and the v2 evidence verifier, and the colliding
  aliases resolve last-write-wins to one identity with three FQCNs
  (DB_V2_POLICY_DAO_AMBIGUOUS); each target now uses a distinct DAO-named
  local and the seed rows spell those normalized accessors;
* ``GR-08k2-seed.yml`` -- GroupLifecycleCoordinator.kt (9 rows / 9 findings /
  9 unique fingerprints) + EnhancedSplitManager.kt (9 rows / 9 findings /
  9 unique fingerprints); ZERO closure rows -- GroupMemberDao's mutating
  setCurrentUser convenience is NOT called from the coordinator, and the
  split DAOs are fully abstract.  LEGAL_PATHS.md names the coordinator the
  authoritative group writer (GroupSettlementDao.insert outside it is
  FORBIDDEN) and EnhancedSplitManager the authoritative split/template
  writer (SplitTemplateDao.insertTemplate outside it is FORBIDDEN);
* the combined generation input ``GR-08-seeds.yml`` stays the exact
  concatenation of the SEVENTEEN reviewed batch seed files
  (5 + 13 + 10 + 16 + 22 + 23 + 23 + 21 + 7 + 13 + 14 + 6 + 7 + 11 + 21
  + 19 + 18 = 249 rows) -- a dropped earlier-batch row fails closed here
  instead of silently re-unauthorizing that batch's mutations at promotion;
* NEAR-MISS protection over the GR-08k1/k2 rows (wrong overload / owner /
  DAO / operation stay unauthorized).

GR-08l (MIT-DB-08L) extends the same contract to FIVE top-density files --
the data/repository ExpenseRepository.kt (repository maintenance/debug
facade, EXPENSE_DAO_MUTATION_ALLOWLIST) and
MerchantNormalizationRepository.kt (sole MerchantNormalizationDao writer),
the data/repository SavingsGoalRepository.kt (sole SavingsGoalDao writer),
the domain/transaction/lifecycle TransactionLifecycleCoordinator.kt (THE
expense lifecycle authority per docs/architecture/LEGAL_PATHS.md), and the
data/repository SubscriptionManagementRepository.kt (repository legal
writer for the subscription management surface).  The combined batch
carries 39 findings / 39 unique fingerprints > the 25-fingerprint batch
cap, so the batch was SPLIT per the GR-08c/GR-08e/GR-08i/GR-08j/GR-08k
precedent into two file groups:

* ``GR-08l1-seed.yml`` -- ExpenseRepository.kt (8 rows / 8 findings / 8
  unique fingerprints; restoreDebugSnapshot carries TWO distinct ExpenseDao
  operations) + MerchantNormalizationRepository.kt (8 rows / 8 findings /
  8 unique fingerprints; insertAlias carries TWO distinct
  MerchantNormalizationDao operations) + THREE closure rows (the GR-08b/
  GR-08d blind-spot pattern: ExpenseRepository.updateExpenseMerchant ->
  pendingReviewDao.bulkRenameMerchant,
  MerchantNormalizationRepository.insertAlias ->
  dao.incrementAliasOccurrence, and
  MerchantNormalizationRepository.linkAliasToCanonical ->
  dao.linkAliasToCanonical -- body-carrying @Transaction DAO convenience
  methods the findings scanner never reported) + THREE residual closure
  rows (the GR-08l post-promotion rescan: the three findings popularly
  labeled "ExpenseRepository" actually live in BusinessExpenseRepository.kt
  -- addMileage -> mileageDao.insert, ManualRecurringExpenseRepository.kt
  -- writeLifecycleEvent -> lifecycleEventDao.insert (4-param overload),
  and RecurringExpenseRepository.kt -- writeLifecycleEvent ->
  lifecycleEventDao.insert (6-param overload); ExpenseRepository.kt itself
  carries none of these callables and stays at 0, and the rows spell the
  TRUE paths because an ExpenseRepository.kt row could never match the v2
  fingerprints) = 22 rows.  ZERO chain-form receivers and ZERO accessor
  normalization needed;
* ``GR-08l2-seed.yml`` -- SavingsGoalRepository.kt (8 rows / 8 findings /
  8 unique fingerprints; the deprecated entity-typed aliases are distinct
  callables) + TransactionLifecycleCoordinator.kt (8 rows / 8 findings /
  8 unique fingerprints; the two bulkUpdateCategory overloads and two
  deleteExpense overloads are distinct callables, each with two DAO
  operations) + SubscriptionManagementRepository.kt (7 rows / 7 findings /
  7 unique fingerprints) = 23 rows; ZERO closure rows -- SavingsGoalDao,
  UserCorrectionDao, TransactionEventDao, ManualRecurringExpenseDao,
  SubscriptionPriceHistoryDao, SubscriptionUsageDao and
  SubscriptionCandidateDao are fully abstract, and the coordinator's
  body-carrying @Transaction ExpenseDao composites are strictly read-only;
* the combined generation input ``GR-08-seeds.yml`` stays the exact
  concatenation of the NINETEEN reviewed batch seed files
  (5 + 13 + 10 + 16 + 22 + 23 + 23 + 21 + 7 + 13 + 14 + 6 + 7 + 11 + 21
  + 19 + 18 + 22 + 23 = 294 rows) -- a dropped earlier-batch row fails
  closed here instead of silently re-unauthorizing that batch's mutations
  at promotion;
* NEAR-MISS protection over the GR-08l1/l2 rows (wrong overload / owner /
  DAO / operation stay unauthorized).

GR-08n (MIT-DB-08N) extends the same contract to SIX files -- the
data/repository AiArtifactRepositoryImpl.kt (AI artifact writer),
BudgetRepository.kt (budget maintenance/notification writer) and
MerchantLocationRepository.kt (merchant-location cache/correction writer),
plus data/repository ReceiptRepository.kt (receipt maintenance writer),
domain/investment/InvestmentTracker.kt (THE investment writer per
docs/architecture/LEGAL_PATHS.md "Investment Mutations") and
domain/subscription/SubscriptionManagerEngine.kt (THE subscription ENGINE
per LEGAL_PATHS.md "Subscription Mutations").  The combined batch carries
29 findings / 29 unique fingerprints > the 25-fingerprint batch cap, so the
batch was SPLIT per the GR-08c/GR-08e/GR-08i/GR-08j/GR-08k/GR-08l/GR-08m
precedent into two file groups:

* ``GR-08n1-seed.yml`` -- AiArtifactRepositoryImpl.kt (5 rows / 5 findings /
  5 unique fingerprints) + BudgetRepository.kt (6 rows / 5 findings /
  5 unique fingerprints) + MerchantLocationRepository.kt (5 rows / 4
  findings / 4 unique fingerprints) = 16 rows; TWO closure rows (the
  GR-08b/GR-08d/GR-08l1 blind-spot pattern: BudgetRepository.
  restoreDebugSnapshot -> budgetDao.replaceAllAndEnforceActiveScopes and
  MerchantLocationRepository.saveCorrection -> dao.upsertLocation --
  body-carrying @Transaction DAO convenience methods the findings scanner
  never reported);
* ``GR-08n2-seed.yml`` -- ReceiptRepository.kt (5 rows / 5 findings /
  5 unique fingerprints) + InvestmentTracker.kt (5 rows / 5 findings /
  5 unique fingerprints) + SubscriptionManagerEngine.kt (5 rows / 5
  findings / 5 unique fingerprints) = 15 rows; ZERO closure rows -- all
  eight touched DAOs are fully abstract interfaces with ZERO @Transaction
  methods;
* the combined generation input ``GR-08-seeds.yml`` stays the exact
  concatenation of the TWENTY-THREE reviewed batch seed files
  (5 + 13 + 10 + 16 + 22 + 23 + 23 + 21 + 7 + 13 + 14 + 6 + 7 + 11 + 21
  + 19 + 18 + 22 + 23 + 16 + 12 + 16 + 15 = 353 rows) -- a dropped
  earlier-batch row fails closed here instead of silently re-unauthorizing
  that batch's mutations at promotion;
* NEAR-MISS protection over the GR-08n1/n2 rows (wrong overload / owner /
  DAO / operation stay unauthorized).

GR-08o (MIT-DB-08O) extends the same contract to SEVEN files -- the domain
receipt/lifecycle ReceiptSideEffectPlanner.kt (post-commit matcher
side-effect writer), data/repository CategoryRepository.kt (category
seeding/maintenance writer), ReceiptItemCategorizationRepository.kt
(receipt item categorization writer) and SharedExpenseDataPortAdapter.kt
(the SharedExpenseDataPort adapter), domain/bank/BankApiIntegration.kt
(stub-mode-gated bank API integration), domain/health/
FinancialHealthScoreV2.kt (sole HealthScoreHistoryDao writer) and
domain/provenance/SourceLinkBackfillWorker.kt (PR8 provenance backfill
runner -- NOT a WorkManager worker).  The combined batch carries 22
findings / 21 unique fingerprints (the two processMatchResult
receiptEventDao.insert sites share one fingerprint) <= the 25-fingerprint
batch cap, so NO split was required:

* ``GR-08o-seed.yml`` -- 24 rows: the 21 findings-derived rows (22
  findings / 21 unique fingerprints) PLUS 1 closure row for the
  body-carrying @Transaction CategoryDao convenience method the findings
  scanner never reported (``ensureDefaultCategories ->
  categoryDao.seedDefaultsIfEmpty`` -- the B4 atomic count-check +
  insertAll seeding convenience; the GR-08b/GR-08d/GR-08l1/GR-08n1
  blind-spot pattern) PLUS 2 residual closure rows (the GR-08o
  post-promotion rescan: the two findings popularly labeled
  "CategoryRepository" actually live in MerchantCategoryRepository.kt --
  deleteAll/dao/deleteAll and insert/dao/insert; CategoryRepository.kt
  itself carries neither callable and stays at 0 -- the GR-08l1
  suffix-substring grouping-artifact precedent);
* the combined generation input ``GR-08-seeds.yml`` stays the exact
  concatenation of the TWENTY-FOUR reviewed batch seed files
  (5 + 13 + 10 + 16 + 22 + 23 + 23 + 21 + 7 + 13 + 14 + 6 + 7 + 11 + 21
  + 19 + 18 + 22 + 23 + 16 + 12 + 16 + 15 + 24 = 377 rows) -- a dropped
  earlier-batch row fails closed here instead of silently re-unauthorizing
  that batch's mutations at promotion;
* NEAR-MISS protection over the GR-08o rows (wrong overload / owner /
  DAO / operation stay unauthorized).

GR-08p1 (MIT-DB-08P1) extends the same contract to TWELVE files -- the
data/privacy DataRetentionWorker.kt (privacy retention CoroutineWorker),
data/repository SpendingChallengeRepository.kt and
UserCorrectionRepository.kt (repository-layer writers),
domain/negotiation/SmartBillNegotiationEngine.kt (negotiation engine; sole
NegotiationOutcomeDao/SubscriptionPriceHistoryDao writer),
domain/notification/capture NotificationIntakeCoordinator.kt and
NotificationIntakePayloadRepairer.kt (capture-side intake + privacy
repair writers), domain/recurring/RecurringPlanProjectionService.kt
(recurring projection bridge), domain/recurring/lifecycle/
RecurringLifecycleEventWriter.kt (THE sanctioned recurring lifecycle event
writer -- its own writes ARE the audit trail),
domain/transaction/DefaultExpenseCategoryAssignmentService.kt
(ExpenseCategoryAssignmentPort implementation),
domain/transaction/lifecycle/DebugExpenseAuditWriter.kt (debug-only
aggregate audit writer), domain/workers/WorkerRunLogger.kt (worker
run-diagnostics ledger) and util/JsonExpenseImporter.kt (JSON import
path).  Each of the TWELVE files carries exactly 2 findings; the two
DataRetentionWorker auditDao.insert sites share ONE fingerprint, so the
combined batch carries 24 findings / 23 unique fingerprints <= the
25-fingerprint batch cap and NO split was required:

* ``GR-08p1-seed.yml`` -- 23 rows: the 23 findings-derived rows (24
  findings / 23 unique fingerprints).  ZERO closure rows -- no
  body-carrying @Transaction DAO convenience method is invoked from any
  batch callable.  The batch's blind-spot story is the
  CONSTRUCTOR/LOCAL-ALIAS spelling gap: the legacy MIT-003 rows for
  DataRetentionWorker.doWork (accessor ``privacyAuditDao``) and
  WorkerRunLoggerImpl.start (accessor ``backgroundJobRunDao``) spell the
  DERIVED Room accessor identities while the scanner reports the SOURCE
  property/local alias spellings (``auditDao``, ``dao``), so those rows
  spell the source aliases; Handle.terminal is a NEW row.  The
  SmartBillNegotiationEngine chain-form site
  (``database.negotiationOutcomeDao().insert``) was NORMALIZED to the
  injected ``negotiationOutcomeDao`` constructor property (the GR-08e
  accessor-normalization rule) and its row spells the normalized
  accessor.  The DataRetentionWorker.doWork and WorkerRunLogger
  (start + Handle.terminal) rows use ``workerMediated``; the rest use
  ``helper``;
* the combined generation input ``GR-08-seeds.yml`` stays the exact
  concatenation of the TWENTY-FIVE reviewed batch seed files
  (5 + 13 + 10 + 16 + 22 + 23 + 23 + 21 + 7 + 13 + 14 + 6 + 7 + 11 + 21
  + 19 + 18 + 22 + 23 + 16 + 12 + 16 + 15 + 24 + 23 = 400 rows) -- a
  dropped earlier-batch row fails closed here instead of silently
  re-unauthorizing that batch's mutations at promotion;
* NEAR-MISS protection over the GR-08p1 rows (wrong overload / owner /
  DAO / operation stay unauthorized).

GR-08p2 (MIT-DB-08P2) extends the same contract to the FINAL fifteen files
-- one finding each: the data/database ExpenseGroupDao.kt DAO-default-method
special case (body-carrying @Transaction convenience whose flagged mutation
is the cross-DAO ``memberDao.insertAll`` behind an explicit DAO-typed method
parameter), data/privacy PrivacyAuditLoggerImpl.kt (THE sanctioned privacy
audit logger), data/repository DatabaseBackupRepositoryImpl.kt (backup/
restore path; the RestoreInternalWriteScope restore-mode gate; the accessor
is the method-local alias ``dao``), data/repository GroupsRepositoryImpl.kt
(barrier-checked soft delete), data/repository ReceiptInsertResolver.kt
(P3-BLOCKER-11 centralized receipt-insert conflict resolver),
domain/bank BankConnectionLifecycleCoordinator.kt (the bank-connection
lifecycle coordinator), domain/budget BudgetForecastingEngine.kt
(barrier-checked accuracy write), domain/diagnostics DiagnosticEventWriter.kt
(THE sanctioned pipeline-diagnostics event writer), domain/groups
SettlementCalculator.kt (domain calculator persistence entry point behind a
caller-supplied DAO parameter), domain/notification/capture
NotificationIntakeRecoveryScheduler.kt (intake recovery scheduler),
domain/provenance SourceLinkWriterImpl.kt (THE sanctioned provenance
source-link writer), domain/receipt/lifecycle ReceiptLifecycleEventWriter.kt
and domain/transaction/lifecycle TransactionLifecycleEventWriter.kt (THE
sanctioned receipt/transaction lifecycle event writers -- the event-writer
layer the architecture mandates), service/debug LegacyDataMigrationService.kt
(legacy one-time migration; expenses route through
TransactionLifecycleCoordinator.createExpense) and util/CsvExpenseImporter.kt
(CSV import path; same get-or-create category pattern as the GR-08p1
JsonExpenseImporter rows).  Each of the FIFTEEN files carries exactly 1
finding and each finding is its own distinct (callable, daoAccessor, daoFqcn,
operation) tuple, so the combined batch carries 15 findings / 15 unique
fingerprints <= the 25-fingerprint batch cap and NO split was required:

* ``GR-08p2-seed.yml`` -- 15 rows: the 15 findings-derived rows.  ZERO
  closure rows -- no body-carrying @Transaction DAO convenience method is
  invoked from any batch callable (BudgetForecastDao's insertWithDeactivation
  is invoked only from BudgetForecastingEngine.insertForecast, a callable
  with NO finding whose deactivate-then-insert mutations are self-DAO calls
  inside the DAO's own @Transaction default body and are never flagged).
  ZERO chain-form receivers and ZERO accessor normalization needed (the only
  Room accessor call, DatabaseBackupRepositoryImpl's
  ``val dao = db.scannedReceiptDao()``, is an assignment to a method-local,
  and the row spells that source alias).  Every row uses ``helper``;
* the combined generation input ``GR-08-seeds.yml`` stays the exact
  concatenation of the TWENTY-SIX reviewed batch seed files
  (5 + 13 + 10 + 16 + 22 + 23 + 23 + 21 + 7 + 13 + 14 + 6 + 7 + 11 + 21
  + 19 + 18 + 22 + 23 + 16 + 12 + 16 + 15 + 24 + 23 + 15 = 415 rows) -- a
  dropped earlier-batch row fails closed here instead of silently
  re-unauthorizing that batch's mutations at promotion;
* NEAR-MISS protection over the GR-08p2 rows (wrong overload / owner /
  DAO / operation stay unauthorized).

Authored coverage; execution pending in this environment.
"""

from __future__ import annotations

import sys
from pathlib import Path

import yaml

# ``policy_v2_candidate`` uses in-package relative imports, so everything
# must be imported as ``scripts...`` with the worktree root on ``sys.path``.
_ROOT = Path(__file__).resolve().parents[1]
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from scripts.ci.promote_db_policy_v2 import (  # noqa: E402
    _collect_accounting_mutation_keys,
)
from scripts.db_guard.policy_model import (  # noqa: E402
    BarrierMode,
    CallableKind,
    PolicyEntry,
    match_mutation,
)
from scripts.db_guard.policy_v2_candidate import (  # noqa: E402
    ResolvedRow,
    build_accounting_artifact,
    seed_record_from_entry,
)
from scripts.db_guard.policy_v2_loader import build_policy_entry  # noqa: E402
from scripts.migrate_db_policy_signatures import (  # noqa: E402
    _candidate_document,
    _load_seed_entries,
    _reject_seed_duplicates,
    _verify_candidate_accounting_pair,
)

SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08a-seed.yml"

PIPELINE_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "NotificationProcessingPipeline.kt"
)
PIPELINE_FQCN = (
    "com.yourname.expensetracker.data.repository.NotificationProcessingPipeline"
)
RAW_NOTIFICATION = (
    "com.yourname.expensetracker.data.database.entity.RawNotification"
)
PRE_DB_CONTEXT = PIPELINE_FQCN + ".PreDbContext"
DEFERRED_DIAG = PIPELINE_FQCN + ".DeferredSourceLinkDiagnostic"
AUTO_ACCEPT_PARAMS = (
    RAW_NOTIFICATION,
    "Long",
    PRE_DB_CONTEXT,
    "Long",
    "String?",
    "MutableList<" + DEFERRED_DIAG + ">",
)


def _seed_row(method, dao_accessor, dao_fqcn, operation, params):
    """One exact GR-08a-shaped v2 seed row mapping."""
    return {
        "path": PIPELINE_KT,
        "ownerFqcn": PIPELINE_FQCN,
        "kind": "function",
        "method": method,
        "receiver": None,
        "parameterTypes": list(params),
        "daoAccessor": dao_accessor,
        "daoFqcn": dao_fqcn,
        "operation": operation,
        "barrierMode": "helper",
        "reason": "GR-08a EXACT_POLICY test row",
        "owner": "@panospao7",
        "linkedIssue": "MIT-DB-08A",
    }


def _gr08a_seed_rows():
    """The five exact GR-08a rows (mirroring the tracked seed file)."""
    return [
        _seed_row(
            "detectAndSaveSubscriptionCandidate",
            "subscriptionCandidateDao",
            "com.yourname.expensetracker.data.database.dao.SubscriptionCandidateDao",
            "insert",
            ("String", "Long?"),
        ),
        _seed_row(
            "handleAutoAcceptInTransaction",
            "dao",
            "com.yourname.expensetracker.data.database.dao.RawNotificationDao",
            "markProcessed",
            AUTO_ACCEPT_PARAMS,
        ),
        _seed_row(
            "handleAutoAcceptInTransaction",
            "dao",
            "com.yourname.expensetracker.data.database.dao.RawNotificationDao",
            "markRelevance",
            AUTO_ACCEPT_PARAMS,
        ),
        _seed_row(
            "handleAutoAcceptInTransaction",
            "sourceStatsDao",
            "com.yourname.expensetracker.data.database.dao.SourceStatsDao",
            "incrementTotalAndAccepted",
            AUTO_ACCEPT_PARAMS,
        ),
        _seed_row(
            "handleAutoAcceptInTransaction",
            "sourceStatsDao",
            "com.yourname.expensetracker.data.database.dao.SourceStatsDao",
            "incrementTotalAndDuplicate",
            AUTO_ACCEPT_PARAMS,
        ),
    ]


def _write_seed_doc(tmp_path: Path, rows, name="seeds.yml") -> Path:
    seed_path = tmp_path / name
    seed_path.write_text(
        yaml.safe_dump(
            {"schemaVersion": 2, "entries": rows},
            sort_keys=False,
            allow_unicode=False,
        ),
        encoding="utf-8",
    )
    return seed_path


def _legacy_entry():
    """One schema-valid legacy-resolved-shaped v2 entry (synthetic)."""
    return PolicyEntry(
        path="app/src/main/java/com/example/Repository.kt",
        owner_fqcn="com.example.Repository",
        kind=CallableKind.FUNCTION,
        method="save",
        receiver=None,
        parameter_types=("Int",),
        dao_accessor="expenseDao",
        dao_fqcn="com.example.ExpenseDao",
        operation="insert",
        barrier_mode=BarrierMode.HELPER,
        reason="controlled migration reason",
        owner="expense-owners",
        linked_issue="ISSUE-100",
    )


def _legacy_result():
    """A minimal resolved MigrationResult-shaped stand-in."""

    row = ResolvedRow(0, _legacy_entry())

    class _Result:
        resolved = (row,)
        unresolved = ()
        input_count = 1
        emission_indices = ()

    return _Result()


def _accounting_for(result, candidate_entries, seed_entries=()):
    return build_accounting_artifact(
        result,
        candidate_entries,
        source_policy_path="config/guards/db_ownership_policy.legacy.yml",
        source_policy_sha256="a" * 64,
        source_tree_sha="b" * 64,
        candidate_sha256=None,
        source_mutations=(),
        seed_entries=seed_entries,
    )


# ── (1) Seed loading through the ordinary v2 loader ──────────────────────────


def test_real_tracked_seed_file_loads_with_exactly_five_rows():
    entries = _load_seed_entries(SEED_FILE)
    assert len(entries) == 5
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted(
        ["detectAndSaveSubscriptionCandidate"]
        + ["handleAutoAcceptInTransaction"] * 4
    )
    for entry in entries:
        assert entry.path == PIPELINE_KT
        assert entry.owner_fqcn == PIPELINE_FQCN
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08A"


def test_seed_document_with_duplicate_keys_fails_closed(tmp_path):
    rows = _gr08a_seed_rows()
    rows.append(rows[0])
    seed_path = _write_seed_doc(tmp_path, rows)
    try:
        _load_seed_entries(seed_path)
    except Exception as exc:
        assert "seed rows file is not a valid v2 policy document" in str(exc)
    else:
        raise AssertionError("duplicate seed keys must fail closed")


def test_malformed_seed_document_fails_closed(tmp_path):
    seed_path = tmp_path / "bad.yml"
    seed_path.write_text(
        "schemaVersion: 2\nentries:\n- path: only-a-path\n", encoding="utf-8"
    )
    try:
        _load_seed_entries(seed_path)
    except Exception as exc:
        assert "seed rows file is not a valid v2 policy document" in str(exc)
    else:
        raise AssertionError("malformed seed document must fail closed")


# ── (2) Seed/legacy duplicate rejection ──────────────────────────────────────


def test_seed_colliding_with_legacy_key_fails_closed(tmp_path):
    result = _legacy_result()
    legacy_entry = result.resolved[0].entry
    colliding_row = {
        "path": legacy_entry.path,
        "ownerFqcn": legacy_entry.owner_fqcn,
        "kind": legacy_entry.kind.value,
        "method": legacy_entry.method,
        "receiver": legacy_entry.receiver,
        "parameterTypes": list(legacy_entry.parameter_types),
        "daoAccessor": legacy_entry.dao_accessor,
        "daoFqcn": legacy_entry.dao_fqcn,
        "operation": legacy_entry.operation,
        "barrierMode": legacy_entry.barrier_mode.value,
        "reason": "seed shadowing a legacy row",
        "owner": "@panospao7",
        "linkedIssue": "MIT-DB-08A",
    }
    seed_entries = _load_seed_entries(
        _write_seed_doc(tmp_path, [colliding_row])
    )
    try:
        _reject_seed_duplicates(result, seed_entries)
    except Exception as exc:
        assert "duplicate a legacy-resolved candidate mutation key" in str(exc)
    else:
        raise AssertionError("seed/legacy key collision must fail closed")


def test_disjoint_seed_keys_pass_duplicate_rejection(tmp_path):
    seed_entries = _load_seed_entries(
        _write_seed_doc(tmp_path, _gr08a_seed_rows())
    )
    _reject_seed_duplicates(_legacy_result(), seed_entries)  # must not raise


# ── (3) Candidate merge + accounting crosswalk ───────────────────────────────


def test_seeded_candidate_document_merges_and_sorts_deterministically(tmp_path):
    seed_entries = _load_seed_entries(
        _write_seed_doc(tmp_path, _gr08a_seed_rows())
    )
    result = _legacy_result()
    document = _candidate_document(result, seed_entries)
    assert document["schemaVersion"] == 2
    entries = document["entries"]
    assert len(entries) == 1 + len(seed_entries)
    keys = [
        (
            item["path"],
            item["ownerFqcn"],
            item["method"],
            item["daoAccessor"],
            item["operation"],
        )
        for item in entries
    ]
    assert keys == sorted(keys)
    # Every seed row is present verbatim.
    seeded = [
        (item["method"], item["daoAccessor"], item["operation"])
        for item in entries
    ]
    for entry in seed_entries:
        assert (entry.method, entry.dao_accessor, entry.operation) in seeded


def test_accounting_artifact_carries_seed_records_and_crosswalks(tmp_path):
    seed_entries = _load_seed_entries(
        _write_seed_doc(tmp_path, _gr08a_seed_rows())
    )
    result = _legacy_result()
    legacy_entry = result.resolved[0].entry
    artifact = _accounting_for(result, [legacy_entry], seed_entries=seed_entries)
    payload = artifact.to_dict()
    assert len(payload["seedRecords"]) == len(seed_entries)
    seed_keys = {record["key"] for record in payload["seedRecords"]}
    assert seed_keys == {
        entry.mutation_key().canonical_key() for entry in seed_entries
    }
    record_keys = {
        key for record in payload["records"] for key in record["mutationKeys"]
    }
    assert seed_keys.isdisjoint(record_keys)


def test_seedless_accounting_artifact_stays_byte_identical():
    result = _legacy_result()
    legacy_entry = result.resolved[0].entry
    payload = _accounting_for(result, [legacy_entry]).to_dict()
    assert "seedRecords" not in payload


def test_duplicate_seed_keys_rejected_by_accounting_builder(tmp_path):
    seed_entries = _load_seed_entries(
        _write_seed_doc(tmp_path, _gr08a_seed_rows())
    )
    result = _legacy_result()
    legacy_entry = result.resolved[0].entry
    try:
        _accounting_for(
            result,
            [legacy_entry],
            seed_entries=seed_entries + [seed_entries[0]],
        )
    except ValueError as exc:
        assert "duplicate mutation keys" in str(exc)
    else:
        raise AssertionError("duplicate seed keys must be rejected")


def test_seed_key_colliding_with_legacy_record_rejected_by_accounting(tmp_path):
    result = _legacy_result()
    legacy_entry = result.resolved[0].entry
    colliding_seed = PolicyEntry(
        path=legacy_entry.path,
        owner_fqcn=legacy_entry.owner_fqcn,
        kind=legacy_entry.kind,
        method=legacy_entry.method,
        receiver=legacy_entry.receiver,
        parameter_types=legacy_entry.parameter_types,
        dao_accessor=legacy_entry.dao_accessor,
        dao_fqcn=legacy_entry.dao_fqcn,
        operation=legacy_entry.operation,
        barrier_mode=BarrierMode.HELPER,
        reason="seed shadowing a legacy row",
        owner="@panospao7",
        linked_issue="MIT-DB-08A",
    )
    try:
        _accounting_for(result, [legacy_entry], seed_entries=[colliding_seed])
    except ValueError as exc:
        assert "duplicates a legacy record key" in str(exc)
    else:
        raise AssertionError("seed/legacy key collision must be rejected")


def test_rendered_seeded_candidate_verifies_against_accounting_pair(tmp_path):
    seed_entries = _load_seed_entries(
        _write_seed_doc(tmp_path, _gr08a_seed_rows())
    )
    result = _legacy_result()
    legacy_entry = result.resolved[0].entry
    document = _candidate_document(result, seed_entries)
    candidate_text = yaml.safe_dump(
        document, sort_keys=False, allow_unicode=False
    ).replace("\r\n", "\n")
    # GR-08i repair: _accounting_for returns the AccountingArtifact object;
    # the pair verifier consumes its dict payload (same .to_dict() shape the
    # seed-records crosswalk test above uses).  The item assignment below
    # needs the dict, not the frozen artifact.
    artifact_payload = _accounting_for(
        result, [legacy_entry], seed_entries=seed_entries
    ).to_dict()
    artifact_payload["candidateSha256"] = None
    # Must not raise: the rendered candidate's FULL key set (legacy + seeds)
    # equals the accounting records' keys union the seedRecords keys.
    _verify_candidate_accounting_pair(candidate_text, artifact_payload)


def test_rendered_candidate_missing_seed_key_fails_pair_verification(tmp_path):
    seed_entries = _load_seed_entries(
        _write_seed_doc(tmp_path, _gr08a_seed_rows())
    )
    result = _legacy_result()
    legacy_entry = result.resolved[0].entry
    document = _candidate_document(result, seed_entries)
    candidate_text = yaml.safe_dump(
        document, sort_keys=False, allow_unicode=False
    ).replace("\r\n", "\n")
    # GR-08i repair: same .to_dict() shape as the sibling pair-verification
    # test above (the frozen artifact is not item-assignable).
    artifact_payload = _accounting_for(result, [legacy_entry]).to_dict()
    artifact_payload["candidateSha256"] = None
    try:
        _verify_candidate_accounting_pair(candidate_text, artifact_payload)
    except Exception as exc:
        assert "candidate and accounting artifacts disagree" in str(exc)
    else:
        raise AssertionError(
            "candidate keys absent from accounting must fail pair verification"
        )


# ── (4) Promotion gate: accounting key union includes seed keys ──────────────


def test_promotion_gate_unions_seed_record_keys():
    document = {
        "records": [{"index": 0, "mutationKeys": ["legacy|key"]}],
        "inputCount": 1,
        "seedRecords": [
            {"key": "seed|key|a"},
            {"key": "seed|key|b"},
        ],
    }
    union, input_count, problem = _collect_accounting_mutation_keys(document)
    assert problem is None
    assert input_count == 1
    assert union == {"legacy|key", "seed|key|a", "seed|key|b"}


def test_promotion_gate_malformed_seed_records_fail_closed():
    document = {
        "records": [{"index": 0, "mutationKeys": ["legacy|key"]}],
        "inputCount": 1,
        "seedRecords": ["not-a-mapping"],
    }
    union, input_count, problem = _collect_accounting_mutation_keys(document)
    assert union is None and input_count is None
    assert problem == "DB_PROMOTE_ACCOUNTING_MALFORMED"


def test_promotion_gate_without_seed_section_unchanged():
    document = {
        "records": [{"index": 0, "mutationKeys": ["legacy|key"]}],
        "inputCount": 1,
    }
    union, input_count, problem = _collect_accounting_mutation_keys(document)
    assert problem is None
    assert union == {"legacy|key"}


# ── (5) NEAR-MISS protection over the GR-08a rows ────────────────────────────
#
# The scanner authorizes a finding only when EVERY identity field matches
# exactly (``match_mutation``, no wildcards).  Each test mutates exactly one
# field of a real GR-08a row and asserts the mutation stays unauthorized.


def _gr08a_policy_entries(tmp_path):
    rows = _gr08a_seed_rows()
    entries = []
    for position, row in enumerate(rows):
        entry, errors = build_policy_entry(row, position)
        assert entry is not None and not errors, (
            "GR-08a fixture row must be schema-valid: %s" % (errors,)
        )
        entries.append(entry)
    return entries


def _assert_exact_match(tmp_path, **overrides):
    """The exact GR-08a auto-accept identity matches; mutants never do."""
    entries = _gr08a_policy_entries(tmp_path)
    target = [
        entry
        for entry in entries
        if entry.method == "handleAutoAcceptInTransaction"
        and entry.dao_accessor == "dao"
        and entry.operation == "markProcessed"
    ][0]
    kwargs = dict(
        path=target.path,
        owner_fqcn=target.owner_fqcn,
        kind=target.kind,
        method=target.method,
        receiver=target.receiver,
        parameter_types=target.parameter_types,
        dao_accessor=target.dao_accessor,
        dao_fqcn=target.dao_fqcn,
        operation=target.operation,
    )
    kwargs.update(overrides)
    return match_mutation(target, **kwargs)


def test_exact_identity_matches(tmp_path):
    assert _assert_exact_match(tmp_path) is True


def test_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    entries = _gr08a_policy_entries(tmp_path)
    target = entries[1]  # handleAutoAcceptInTransaction / dao / markProcessed
    wrong_overload = target.parameter_types[:-1] + ("String?",)
    assert (
        match_mutation(
            target,
            path=target.path,
            owner_fqcn=target.owner_fqcn,
            kind=target.kind,
            method=target.method,
            receiver=target.receiver,
            parameter_types=wrong_overload,
            dao_accessor=target.dao_accessor,
            dao_fqcn=target.dao_fqcn,
            operation=target.operation,
        )
        is False
    )


def test_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    assert (
        _assert_exact_match(tmp_path, owner_fqcn="com.example.OtherPipeline")
        is False
    )


def test_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    assert (
        _assert_exact_match(
            tmp_path,
            dao_accessor="sourceStatsDao",
            dao_fqcn=(
                "com.yourname.expensetracker.data.database.dao.SourceStatsDao"
            ),
        )
        is False
    )


def test_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    assert _assert_exact_match(tmp_path, operation="markRelevance") is False


def test_near_miss_wrong_path_stays_unauthorized(tmp_path):
    assert (
        _assert_exact_match(
            tmp_path, path="app/src/main/java/com/example/Copy.kt"
        )
        is False
    )


def test_seed_record_from_entry_round_trips_key(tmp_path):
    entries = _gr08a_policy_entries(tmp_path)
    for entry in entries:
        record = seed_record_from_entry(entry)
        assert record.key == entry.mutation_key().canonical_key()
        assert record.path == entry.path
        assert record.barrier_mode == entry.barrier_mode.value
        assert record.linked_issue == entry.linked_issue


# ── (6) GR-08b rows: tracked seed files + NEAR-MISS protection ────────────────
#
# GR-08b authorizes the three remaining NotificationProcessingPipeline.kt
# callables (processInternal, handleNeedsReviewInTransaction,
# insertRawNotificationIfNotDuplicate; 30 findings / 11 unique fingerprints).
# The migration CLI accepts a SINGLE --seed-rows value, so the generation run
# consumes the COMBINED document GR-08-seeds.yml; these tests pin that the
# combined document stays the exact concatenation of the two reviewed batch
# seed files, and that the GR-08b rows authorize EXACTLY their callable
# identity + DAO + operation (wrong overload, wrong owner, wrong DAO, and
# wrong operation stay unauthorized).

GR08B_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08b-seed.yml"
COMBINED_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08-seeds.yml"

RAW_NOTIFICATION_DAO = (
    "com.yourname.expensetracker.data.database.dao.RawNotificationDao"
)
SOURCE_STATS_DAO = (
    "com.yourname.expensetracker.data.database.dao.SourceStatsDao"
)
PENDING_REVIEW_DAO = (
    "com.yourname.expensetracker.data.database.dao.PendingReviewDao"
)
PERSISTENCE_CONTEXT = (
    "com.yourname.expensetracker.domain.notification."
    "NotificationPersistenceContext?"
)
PROCESS_INTERNAL_PARAMS = (
    RAW_NOTIFICATION,
    RAW_NOTIFICATION,
    "Boolean",
    "String?",
    PERSISTENCE_CONTEXT,
)
NEEDS_REVIEW_PARAMS = AUTO_ACCEPT_PARAMS
INSERT_RAW_PARAMS = (RAW_NOTIFICATION, RAW_NOTIFICATION)


def _gr08b_seed_row(method, dao_accessor, dao_fqcn, operation, params):
    """One exact GR-08b-shaped v2 seed row mapping."""
    return {
        "path": PIPELINE_KT,
        "ownerFqcn": PIPELINE_FQCN,
        "kind": "function",
        "method": method,
        "receiver": None,
        "parameterTypes": list(params),
        "daoAccessor": dao_accessor,
        "daoFqcn": dao_fqcn,
        "operation": operation,
        "barrierMode": "helper",
        "reason": "GR-08b EXACT_POLICY test row",
        "owner": "@panospao7",
        "linkedIssue": "MIT-DB-08B",
    }


def _gr08b_seed_rows():
    """The thirteen exact GR-08b rows (mirroring the tracked seed file).

    Eleven findings-derived rows plus the two GR-08a-alias-bridge closure
    rows (``pendingReviewDao.upsertByRawNotificationId`` in processInternal
    and handleNeedsReviewInTransaction).
    """
    rows = []
    for operation in ("markProcessed", "markRelevance"):
        rows.append(
            _gr08b_seed_row(
                "processInternal",
                "dao",
                RAW_NOTIFICATION_DAO,
                operation,
                PROCESS_INTERNAL_PARAMS,
            )
        )
    for operation in (
        "incrementTotalAndAutoRejected",
        "incrementTotalAndDuplicate",
        "incrementTotalAndPending",
        "insertIfNotExists",
    ):
        rows.append(
            _gr08b_seed_row(
                "processInternal",
                "sourceStatsDao",
                SOURCE_STATS_DAO,
                operation,
                PROCESS_INTERNAL_PARAMS,
            )
        )
    rows.append(
        _gr08b_seed_row(
            "processInternal",
            "pendingReviewDao",
            PENDING_REVIEW_DAO,
            "upsertByRawNotificationId",
            PROCESS_INTERNAL_PARAMS,
        )
    )
    for operation in ("markProcessed", "markRelevance"):
        rows.append(
            _gr08b_seed_row(
                "handleNeedsReviewInTransaction",
                "dao",
                RAW_NOTIFICATION_DAO,
                operation,
                NEEDS_REVIEW_PARAMS,
            )
        )
    for operation in ("incrementTotalAndDuplicate", "incrementTotalAndPending"):
        rows.append(
            _gr08b_seed_row(
                "handleNeedsReviewInTransaction",
                "sourceStatsDao",
                SOURCE_STATS_DAO,
                operation,
                NEEDS_REVIEW_PARAMS,
            )
        )
    rows.append(
        _gr08b_seed_row(
            "handleNeedsReviewInTransaction",
            "pendingReviewDao",
            PENDING_REVIEW_DAO,
            "upsertByRawNotificationId",
            NEEDS_REVIEW_PARAMS,
        )
    )
    rows.append(
        _gr08b_seed_row(
            "insertRawNotificationIfNotDuplicate",
            "dao",
            RAW_NOTIFICATION_DAO,
            "insertOrIgnore",
            INSERT_RAW_PARAMS,
        )
    )
    return rows


def _entry_fields(entry):
    """Field-exact identity of a loaded seed entry (verbatim comparison)."""
    return (
        entry.path,
        entry.owner_fqcn,
        entry.kind,
        entry.method,
        entry.receiver,
        tuple(entry.parameter_types),
        entry.dao_accessor,
        entry.dao_fqcn,
        entry.operation,
        entry.barrier_mode,
        entry.reason,
        entry.owner,
        entry.linked_issue,
    )


def test_real_tracked_gr08b_seed_file_loads_with_exactly_thirteen_rows():
    entries = _load_seed_entries(GR08B_SEED_FILE)
    assert len(entries) == 13
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted(
        ["processInternal"] * 7
        + ["handleNeedsReviewInTransaction"] * 5
        + ["insertRawNotificationIfNotDuplicate"]
    )
    for entry in entries:
        assert entry.path == PIPELINE_KT
        assert entry.owner_fqcn == PIPELINE_FQCN
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08B"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)
    # The closure rows: pendingReviewDao.upsertByRawNotificationId in both
    # multi-mutation callables (GR-08a alias-bridge evidence closure).
    closure = sorted(
        (entry.method, entry.dao_accessor, entry.operation)
        for entry in entries
        if entry.dao_accessor == "pendingReviewDao"
    )
    assert closure == [
        ("handleNeedsReviewInTransaction", "pendingReviewDao",
         "upsertByRawNotificationId"),
        ("processInternal", "pendingReviewDao",
         "upsertByRawNotificationId"),
    ]


# ── (7) GR-08c1/c2 rows: tracked seed files + concatenation + NEAR-MISS ───────
#
# GR-08c authorizes the RecurringLifecycleCoordinator.kt domain-lifecycle
# callables (28 findings / 26 unique fingerprints > the 25-fingerprint
# batch cap, hence the GR-08c1/c2 split by callable groups).  The migration
# CLI accepts a SINGLE --seed-rows value, so every generation run consumes
# the COMBINED document GR-08-seeds.yml; these tests pin that the combined
# document stays the exact concatenation of the FOUR reviewed batch seed
# files, and that the GR-08c1/c2 rows authorize EXACTLY their callable
# identity + DAO + operation (wrong overload, wrong owner, wrong DAO, and
# wrong operation stay unauthorized).

GR08C1_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08c1-seed.yml"
GR08C2_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08c2-seed.yml"

COORDINATOR_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/recurring/"
    "lifecycle/RecurringLifecycleCoordinator.kt"
)
COORDINATOR_FQCN = (
    "com.yourname.expensetracker.domain.recurring.lifecycle."
    "RecurringLifecycleCoordinator"
)
OCCURRENCE_DAO = (
    "com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao"
)
REMINDER_DELIVERY_DAO = (
    "com.yourname.expensetracker.data.database.dao."
    "RecurringReminderDeliveryDao"
)
PLANNED_EXPENSE_DAO = (
    "com.yourname.expensetracker.data.database.dao.PlannedExpenseDao"
)
LIFECYCLE_EVENT_DAO = (
    "com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao"
)
OCCURRENCE_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.RecurringOccurrence"
)
OCCURRENCE_STATUS = (
    "com.yourname.expensetracker.domain.recurring.lifecycle."
    "RecurringOccurrenceStatus"
)
TRANSITION_REASON = (
    "com.yourname.expensetracker.domain.recurring.lifecycle."
    "RecurringOccurrenceTransitionReason"
)
LINK_PARAMS = ("Long",)
RECONCILE_PARAMS = ("Long", "String")
REGENERATE_PARAMS = (OCCURRENCE_ENTITY, "Long", "List<String>")
RECOVER_PARAMS = ("String", "String")
CLAIM_PARAMS = ("Long",)
MARK_SENT_PARAMS = ("Long", "Int")
DISMISS_PARAMS = ("Long",)
SNOOZE_PARAMS = ("Long", "Long")


def _gr08c_seed_row(method, dao_accessor, dao_fqcn, operation, params):
    """One exact GR-08c-shaped v2 seed row mapping."""
    return {
        "path": COORDINATOR_KT,
        "ownerFqcn": COORDINATOR_FQCN,
        "kind": "function",
        "method": method,
        "receiver": None,
        "parameterTypes": list(params),
        "daoAccessor": dao_accessor,
        "daoFqcn": dao_fqcn,
        "operation": operation,
        "barrierMode": "helper",
        "reason": "GR-08c EXACT_POLICY test row",
        "owner": "@panospao7",
        "linkedIssue": "MIT-DB-08C",
    }


def _gr08c1_seed_rows():
    """The ten exact GR-08c1 rows (mirroring the tracked seed file).

    The occurrence/expense-link lifecycle group; ZERO closure rows (the
    blind-spot sweep found every mutating DAO call in the file is an
    abstract Room-annotated method already covered by a finding).
    """
    rows = []
    for accessor, dao, operation in (
        ("occurrenceDao", OCCURRENCE_DAO, "claimForExpense"),
        ("plannedExpenseDao", PLANNED_EXPENSE_DAO, "linkToActualExpense"),
        (
            "reminderDeliveryDao",
            REMINDER_DELIVERY_DAO,
            "suppressOpenDeliveriesForOccurrence",
        ),
    ):
        rows.append(
            _gr08c_seed_row(
                "linkExpenseToOccurrence", accessor, dao, operation,
                LINK_PARAMS,
            )
        )
    for accessor, dao, operation in (
        (
            "occurrenceDao",
            OCCURRENCE_DAO,
            "updateLinkedPaymentSnapshot",
        ),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08c_seed_row(
                "reconcileExpenseLinkAfterUpdate", accessor, dao, operation,
                RECONCILE_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("occurrenceDao", OCCURRENCE_DAO, "update"),
        ("plannedExpenseDao", PLANNED_EXPENSE_DAO, "unlinkActualExpense"),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08c_seed_row(
                "unlinkExpenseFromOccurrenceDetailed", accessor, dao,
                operation, RECONCILE_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("occurrenceDao", OCCURRENCE_DAO, "updateStatus"),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08c_seed_row(
                "updateOccurrenceStatus", accessor, dao, operation,
                ("Long", OCCURRENCE_STATUS, TRANSITION_REASON),
            )
        )
    return rows


def _gr08c2_seed_rows():
    """The sixteen exact GR-08c2 rows (mirroring the tracked seed file).

    The reminder-delivery lifecycle group; ZERO closure rows.
    """
    rows = []
    for accessor, dao, operation in (
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
        (
            "reminderDeliveryDao",
            REMINDER_DELIVERY_DAO,
            "reopenDeliveryForOccurrenceWindow",
        ),
        ("reminderDeliveryDao", REMINDER_DELIVERY_DAO, "insert"),
    ):
        rows.append(
            _gr08c_seed_row(
                "regenerateReminderDeliveriesForOccurrence", accessor, dao,
                operation, REGENERATE_PARAMS,
            )
        )
    for accessor, dao, operation in (
        (
            "reminderDeliveryDao",
            REMINDER_DELIVERY_DAO,
            "recoverStaleClaimedDeliveries",
        ),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08c_seed_row(
                "recoverStaleClaimedDeliveries", accessor, dao, operation,
                RECOVER_PARAMS,
            )
        )
    rows.append(
        _gr08c_seed_row(
            "claimReminderDelivery", "reminderDeliveryDao",
            REMINDER_DELIVERY_DAO, "claimDelivery", CLAIM_PARAMS,
        )
    )
    for accessor, dao, operation in (
        (
            "reminderDeliveryDao",
            REMINDER_DELIVERY_DAO,
            "cancelClaimedDelivery",
        ),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08c_seed_row(
                "cancelClaimedReminderDelivery", accessor, dao, operation,
                RECONCILE_PARAMS,
            )
        )
    for accessor, dao, operation in (
        (
            "reminderDeliveryDao",
            REMINDER_DELIVERY_DAO,
            "markSentFromClaimed",
        ),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08c_seed_row(
                "markReminderSent", accessor, dao, operation,
                MARK_SENT_PARAMS,
            )
        )
    for accessor, dao, operation in (
        (
            "reminderDeliveryDao",
            REMINDER_DELIVERY_DAO,
            "markFailedFromClaimed",
        ),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08c_seed_row(
                "markReminderFailed", accessor, dao, operation,
                RECONCILE_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("reminderDeliveryDao", REMINDER_DELIVERY_DAO, "update"),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08c_seed_row(
                "dismissReminderDelivery", accessor, dao, operation,
                DISMISS_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("reminderDeliveryDao", REMINDER_DELIVERY_DAO, "update"),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08c_seed_row(
                "snoozeReminderDelivery", accessor, dao, operation,
                SNOOZE_PARAMS,
            )
        )
    return rows


def test_real_tracked_gr08c1_seed_file_loads_with_exactly_ten_rows():
    entries = _load_seed_entries(GR08C1_SEED_FILE)
    assert len(entries) == 10
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted(
        ["linkExpenseToOccurrence"] * 3
        + ["reconcileExpenseLinkAfterUpdate"] * 2
        + ["unlinkExpenseFromOccurrenceDetailed"] * 3
        + ["updateOccurrenceStatus"] * 2
    )
    for entry in entries:
        assert entry.path == COORDINATOR_KT
        assert entry.owner_fqcn == COORDINATOR_FQCN
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08C"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)


def test_real_tracked_gr08c2_seed_file_loads_with_exactly_sixteen_rows():
    entries = _load_seed_entries(GR08C2_SEED_FILE)
    assert len(entries) == 16
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted(
        ["regenerateReminderDeliveriesForOccurrence"] * 3
        + ["recoverStaleClaimedDeliveries"] * 2
        + ["claimReminderDelivery"]
        + ["cancelClaimedReminderDelivery"] * 2
        + ["markReminderSent"] * 2
        + ["markReminderFailed"] * 2
        + ["dismissReminderDelivery"] * 2
        + ["snoozeReminderDelivery"] * 2
    )
    for entry in entries:
        assert entry.path == COORDINATOR_KT
        assert entry.owner_fqcn == COORDINATOR_FQCN
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08C"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)


# ── (8) GR-08d rows: tracked seed file + concatenation + NEAR-MISS ────────────
#
# GR-08d authorizes the ReviewQueueRepository.kt repository-layer callables
# (27 findings / 19 unique fingerprints, within the 25-fingerprint batch cap
# so NO split was required).  The migration CLI accepts a SINGLE --seed-rows
# value, so every generation run consumes the COMBINED document
# GR-08-seeds.yml; these tests pin that the combined document stays the
# exact concatenation of the FIVE reviewed batch seed files, and that the
# GR-08d rows authorize EXACTLY their callable identity + DAO + operation
# (wrong overload, wrong owner, wrong DAO, and wrong operation stay
# unauthorized), including the 3 closure rows for body-carrying
# @Transaction PendingReviewDao convenience methods.

GR08D_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08d-seed.yml"

REVIEW_QUEUE_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "ReviewQueueRepository.kt"
)
REVIEW_QUEUE_FQCN = (
    "com.yourname.expensetracker.data.repository.ReviewQueueRepository"
)
PENDING_REVIEW_DAO_GR08D = (
    "com.yourname.expensetracker.data.database.dao.PendingReviewDao"
)
RAW_NOTIFICATION_DAO_GR08D = (
    "com.yourname.expensetracker.data.database.dao.RawNotificationDao"
)
SOURCE_STATS_DAO_GR08D = (
    "com.yourname.expensetracker.data.database.dao.SourceStatsDao"
)
TRANSACTION_EVENT_DAO = (
    "com.yourname.expensetracker.data.database.dao.TransactionEventDao"
)
USER_CORRECTION_DAO = (
    "com.yourname.expensetracker.data.database.dao.UserCorrectionDao"
)
TRANSACTION_TYPE = (
    "com.yourname.expensetracker.data.database.entity.TransactionType?"
)
TRANSFER_DIRECTION = (
    "com.yourname.expensetracker.data.database.entity.TransferDirection?"
)
APPROVE_PARAMS = (
    "Long",
    "Double?",
    "String?",
    "String?",
    "Long?",
    "Long?",
    TRANSACTION_TYPE,
    TRANSFER_DIRECTION,
    "String?",
    "Boolean",
    "Double?",
    "Double?",
    "String?",
    "String?",
)
MARK_RELEVANT_PARAMS = ("Long", "Boolean")
REJECT_PARAMS = ("Long",)
RECOVER_PARAMS_GR08D: tuple = ()
CATEGORY_BULK_PARAMS = ("String", "Long")
MERCHANT_BULK_PARAMS = ("String", "String")


def _gr08d_seed_row(method, dao_accessor, dao_fqcn, operation, params):
    """One exact GR-08d-shaped v2 seed row mapping."""
    return {
        "path": REVIEW_QUEUE_KT,
        "ownerFqcn": REVIEW_QUEUE_FQCN,
        "kind": "function",
        "method": method,
        "receiver": None,
        "parameterTypes": list(params),
        "daoAccessor": dao_accessor,
        "daoFqcn": dao_fqcn,
        "operation": operation,
        "barrierMode": "helper",
        "reason": "GR-08d EXACT_POLICY test row",
        "owner": "@panospao7",
        "linkedIssue": "MIT-DB-08D",
    }


def _gr08d_seed_rows():
    """The twenty-two exact GR-08d rows (mirroring the tracked seed file).

    Nineteen findings-derived rows plus the 3 closure rows for
    body-carrying @Transaction PendingReviewDao convenience methods
    (upsertByRawNotificationId, bulkUpdateCategoryByMerchant,
    bulkRenameMerchant).
    """
    rows = []
    for accessor, dao, operation in (
        ("pendingReviewDao", PENDING_REVIEW_DAO_GR08D, "transitionStatus"),
        ("pendingReviewDao", PENDING_REVIEW_DAO_GR08D, "updateStatus"),
        ("rawNotificationDao", RAW_NOTIFICATION_DAO_GR08D, "markRelevance"),
        ("sourceStatsDao", SOURCE_STATS_DAO_GR08D, "incrementAccepted"),
        ("sourceStatsDao", SOURCE_STATS_DAO_GR08D, "decrementPending"),
        ("sourceStatsDao", SOURCE_STATS_DAO_GR08D, "incrementDuplicate"),
        ("transactionEventDao", TRANSACTION_EVENT_DAO, "insert"),
        ("userCorrectionDao", USER_CORRECTION_DAO, "insert"),
    ):
        rows.append(
            _gr08d_seed_row(
                "approveReview", accessor, dao, operation, APPROVE_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("pendingReviewDao", PENDING_REVIEW_DAO_GR08D, "transitionStatus"),
        ("rawNotificationDao", RAW_NOTIFICATION_DAO_GR08D, "markRelevance"),
        ("sourceStatsDao", SOURCE_STATS_DAO_GR08D, "incrementRejected"),
        ("sourceStatsDao", SOURCE_STATS_DAO_GR08D, "decrementPending"),
        ("userCorrectionDao", USER_CORRECTION_DAO, "insert"),
    ):
        rows.append(
            _gr08d_seed_row(
                "rejectReview", accessor, dao, operation, REJECT_PARAMS,
            )
        )
    rows.append(
        _gr08d_seed_row(
            "recoverStuckReviews",
            "pendingReviewDao",
            PENDING_REVIEW_DAO_GR08D,
            "recoverStuckProcessing",
            RECOVER_PARAMS_GR08D,
        )
    )
    for accessor, dao, operation in (
        ("rawNotificationDao", RAW_NOTIFICATION_DAO_GR08D, "markRelevance"),
        ("sourceStatsDao", SOURCE_STATS_DAO_GR08D, "incrementAccepted"),
        ("sourceStatsDao", SOURCE_STATS_DAO_GR08D, "incrementDuplicate"),
        ("sourceStatsDao", SOURCE_STATS_DAO_GR08D, "incrementPending"),
        ("userCorrectionDao", USER_CORRECTION_DAO, "insert"),
        # Closure row: body-carrying @Transaction convenience method.
        (
            "pendingReviewDao",
            PENDING_REVIEW_DAO_GR08D,
            "upsertByRawNotificationId",
        ),
    ):
        rows.append(
            _gr08d_seed_row(
                "markAsRelevant", accessor, dao, operation,
                MARK_RELEVANT_PARAMS,
            )
        )
    # Closure rows: body-carrying @Transaction convenience methods.
    rows.append(
        _gr08d_seed_row(
            "updatePendingReviewCategoryBulk",
            "pendingReviewDao",
            PENDING_REVIEW_DAO_GR08D,
            "bulkUpdateCategoryByMerchant",
            CATEGORY_BULK_PARAMS,
        )
    )
    rows.append(
        _gr08d_seed_row(
            "updatePendingReviewMerchantBulk",
            "pendingReviewDao",
            PENDING_REVIEW_DAO_GR08D,
            "bulkRenameMerchant",
            MERCHANT_BULK_PARAMS,
        )
    )
    return rows


def test_real_tracked_gr08d_seed_file_loads_with_exactly_twenty_two_rows():
    entries = _load_seed_entries(GR08D_SEED_FILE)
    assert len(entries) == 22
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted(
        ["approveReview"] * 8
        + ["rejectReview"] * 5
        + ["recoverStuckReviews"]
        + ["markAsRelevant"] * 6
        + ["updatePendingReviewCategoryBulk"]
        + ["updatePendingReviewMerchantBulk"]
    )
    for entry in entries:
        assert entry.path == REVIEW_QUEUE_KT
        assert entry.owner_fqcn == REVIEW_QUEUE_FQCN
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08D"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)
    # The closure rows: the three body-carrying @Transaction
    # PendingReviewDao convenience methods (GR-08b blind-spot pattern).
    closure = sorted(
        (entry.method, entry.dao_accessor, entry.operation)
        for entry in entries
        if entry.dao_accessor == "pendingReviewDao"
        and entry.operation
        in (
            "upsertByRawNotificationId",
            "bulkUpdateCategoryByMerchant",
            "bulkRenameMerchant",
        )
    )
    assert closure == [
        ("markAsRelevant", "pendingReviewDao",
         "upsertByRawNotificationId"),
        ("updatePendingReviewCategoryBulk", "pendingReviewDao",
         "bulkUpdateCategoryByMerchant"),
        ("updatePendingReviewMerchantBulk", "pendingReviewDao",
         "bulkRenameMerchant"),
    ]


# NOTE (GR-08i): the GR-08d-era five-file (66 rows), GR-08g-era nine-file
# (140 rows) and GR-08h-era ten-file (153 rows) concatenation tests were
# REMOVED here, completing the documented supersession chain (each new
# concatenation test replaces its predecessor -- the GR-08d batch removed
# the four-file test, GR-08g removed the eight-file test, but GR-08e and
# GR-08h left their predecessors in place, where they kept failing against
# the grown combined document).  The thirteen-file test below is the strict
# superset: it pins ALL THIRTEEN reviewed batch seed files at 180 rows with
# field-exact equality, so removing the stale predecessors weakens nothing.


def _gr08c_policy_entries(tmp_path, rows):
    entries = []
    for position, row in enumerate(rows):
        entry, errors = build_policy_entry(row, position)
        assert entry is not None and not errors, (
            "GR-08c fixture row must be schema-valid: %s" % (errors,)
        )
        entries.append(entry)
    return entries


def _assert_gr08c_exact_match(tmp_path, rows, select_method, select_accessor,
                              select_operation, **overrides):
    """The exact GR-08c row identity matches; mutants never do.

    Target selection is fixed by ``(select_method, select_accessor,
    select_operation)``; ``overrides`` perturb exactly one identity field of
    the match query for the near-miss assertions.
    """
    entries = _gr08c_policy_entries(tmp_path, rows)
    target = [
        entry
        for entry in entries
        if entry.method == select_method
        and entry.dao_accessor == select_accessor
        and entry.operation == select_operation
    ][0]
    kwargs = dict(
        path=target.path,
        owner_fqcn=target.owner_fqcn,
        kind=target.kind,
        method=target.method,
        receiver=target.receiver,
        parameter_types=target.parameter_types,
        dao_accessor=target.dao_accessor,
        dao_fqcn=target.dao_fqcn,
        operation=target.operation,
    )
    kwargs.update(overrides)
    return match_mutation(target, **kwargs)


def test_gr08c1_exact_identity_matches(tmp_path):
    rows = _gr08c1_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "linkExpenseToOccurrence", "occurrenceDao",
            "claimForExpense",
        )
        is True
    )


def test_gr08c1_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08c1_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "linkExpenseToOccurrence", "occurrenceDao",
            "claimForExpense", parameter_types=("Long", "Long"),
        )
        is False
    )


def test_gr08c1_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08c1_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "linkExpenseToOccurrence", "occurrenceDao",
            "claimForExpense", owner_fqcn="com.example.OtherCoordinator",
        )
        is False
    )


def test_gr08c1_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08c1_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "linkExpenseToOccurrence", "occurrenceDao",
            "claimForExpense",
            dao_accessor="reminderDeliveryDao",
            dao_fqcn=REMINDER_DELIVERY_DAO,
        )
        is False
    )


def test_gr08c1_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08c1_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "linkExpenseToOccurrence", "occurrenceDao",
            "claimForExpense", operation="update",
        )
        is False
    )


def test_gr08c1_update_status_row_near_misses_stay_unauthorized(tmp_path):
    """The typed-status rows are exact too: sibling shapes never match."""
    rows = _gr08c1_seed_rows()
    base_kwargs = dict(
        select_method="updateOccurrenceStatus",
        select_accessor="occurrenceDao",
        select_operation="updateStatus",
    )
    assert _assert_gr08c_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong overload: the two-parameter legacy status shape.
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows,
            **dict(base_kwargs, parameter_types=("Long", "String")),
        )
        is False
    )
    # Wrong operation: the plain Room update spelling.
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, **dict(base_kwargs, operation="update")
        )
        is False
    )
    # Wrong DAO: the lifecycle-event accessor behind the same callable.
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows,
            **dict(
                base_kwargs,
                dao_accessor="lifecycleEventDao",
                dao_fqcn=LIFECYCLE_EVENT_DAO,
            ),
        )
        is False
    )


def test_gr08c2_exact_identity_matches(tmp_path):
    rows = _gr08c2_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "claimReminderDelivery", "reminderDeliveryDao",
            "claimDelivery",
        )
        is True
    )


def test_gr08c2_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08c2_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "claimReminderDelivery", "reminderDeliveryDao",
            "claimDelivery", parameter_types=("Long", "Long"),
        )
        is False
    )


def test_gr08c2_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08c2_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "claimReminderDelivery", "reminderDeliveryDao",
            "claimDelivery", owner_fqcn="com.example.OtherCoordinator",
        )
        is False
    )


def test_gr08c2_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08c2_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "claimReminderDelivery", "reminderDeliveryDao",
            "claimDelivery",
            dao_accessor="lifecycleEventDao",
            dao_fqcn=LIFECYCLE_EVENT_DAO,
        )
        is False
    )


def test_gr08c2_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08c2_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "claimReminderDelivery", "reminderDeliveryDao",
            "claimDelivery", operation="update",
        )
        is False
    )


def test_gr08c2_regenerate_rows_near_misses_stay_unauthorized(tmp_path):
    """The regenerate rows are exact too: sibling shapes never match.

    The three lifecycleEventDao.insert call sites share ONE fingerprint, so
    the seed carries exactly one row for them; a wrong parameter shape (the
    reconcile callable's (Long, String)) or a wrong DAO behind the same
    accessor spelling stays unauthorized.
    """
    rows = _gr08c2_seed_rows()
    base_kwargs = dict(
        select_method="regenerateReminderDeliveriesForOccurrence",
        select_accessor="lifecycleEventDao",
        select_operation="insert",
    )
    assert _assert_gr08c_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong overload: the two-parameter reconcile shape.
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows,
            **dict(base_kwargs, parameter_types=("Long", "String")),
        )
        is False
    )
    # Wrong DAO identity behind the accessor spelling.
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows,
            **dict(
                base_kwargs,
                dao_accessor="reminderDeliveryDao",
                dao_fqcn=REMINDER_DELIVERY_DAO,
            ),
        )
        is False
    )
    # Wrong callable: the sibling recover-stale insert never matches the
    # regenerate identity.
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, **dict(base_kwargs, method="recoverStaleClaimedDeliveries")
        )
        is False
    )


def _gr08b_policy_entries(tmp_path):
    rows = _gr08b_seed_rows()
    entries = []
    for position, row in enumerate(rows):
        entry, errors = build_policy_entry(row, position)
        assert entry is not None and not errors, (
            "GR-08b fixture row must be schema-valid: %s" % (errors,)
        )
        entries.append(entry)
    return entries


def _assert_gr08b_exact_match(tmp_path, **overrides):
    """The exact GR-08b processInternal identity matches; mutants never do."""
    entries = _gr08b_policy_entries(tmp_path)
    target = [
        entry
        for entry in entries
        if entry.method == "processInternal"
        and entry.dao_accessor == "dao"
        and entry.operation == "markProcessed"
    ][0]
    kwargs = dict(
        path=target.path,
        owner_fqcn=target.owner_fqcn,
        kind=target.kind,
        method=target.method,
        receiver=target.receiver,
        parameter_types=target.parameter_types,
        dao_accessor=target.dao_accessor,
        dao_fqcn=target.dao_fqcn,
        operation=target.operation,
    )
    kwargs.update(overrides)
    return match_mutation(target, **kwargs)


def test_gr08b_exact_identity_matches(tmp_path):
    assert _assert_gr08b_exact_match(tmp_path) is True


def test_gr08b_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    entries = _gr08b_policy_entries(tmp_path)
    target = entries[0]  # processInternal / dao / markProcessed
    wrong_overload = target.parameter_types[:-1] + ("String?",)
    assert (
        match_mutation(
            target,
            path=target.path,
            owner_fqcn=target.owner_fqcn,
            kind=target.kind,
            method=target.method,
            receiver=target.receiver,
            parameter_types=wrong_overload,
            dao_accessor=target.dao_accessor,
            dao_fqcn=target.dao_fqcn,
            operation=target.operation,
        )
        is False
    )


def test_gr08b_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    assert (
        _assert_gr08b_exact_match(
            tmp_path, owner_fqcn="com.example.OtherPipeline"
        )
        is False
    )


def test_gr08b_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    assert (
        _assert_gr08b_exact_match(
            tmp_path,
            dao_accessor="sourceStatsDao",
            dao_fqcn=SOURCE_STATS_DAO,
        )
        is False
    )


def test_gr08b_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    assert (
        _assert_gr08b_exact_match(tmp_path, operation="markRelevance")
        is False
    )


def test_gr08b_insert_row_near_misses_stay_unauthorized(tmp_path):
    """The insertOrIgnore row is exact too: sibling shapes never match."""
    entries = _gr08b_policy_entries(tmp_path)
    target = [
        entry
        for entry in entries
        if entry.method == "insertRawNotificationIfNotDuplicate"
    ][0]
    base = dict(
        path=target.path,
        owner_fqcn=target.owner_fqcn,
        kind=target.kind,
        method=target.method,
        receiver=target.receiver,
        parameter_types=target.parameter_types,
        dao_accessor=target.dao_accessor,
        dao_fqcn=target.dao_fqcn,
        operation=target.operation,
    )
    assert match_mutation(target, **base) is True
    # Wrong overload: the single-parameter legacy insert shape.
    assert (
        match_mutation(
            target, **dict(base, parameter_types=(RAW_NOTIFICATION,))
        )
        is False
    )
    # Wrong operation: the plain Room insert spelling.
    assert match_mutation(target, **dict(base, operation="insert")) is False
    # Wrong DAO: the stats accessor.
    assert (
        match_mutation(
            target,
            **dict(
                base,
                dao_accessor="sourceStatsDao",
                dao_fqcn=SOURCE_STATS_DAO,
            ),
        )
        is False
    )


def test_gr08b_closure_row_near_misses_stay_unauthorized(tmp_path):
    """The pendingReviewDao closure rows are exact too: mutants never match.

    The closure rows authorize EXACTLY
    ``pendingReviewDao.upsertByRawNotificationId`` on their own callable
    identity; a wrong operation, a wrong DAO identity behind the same
    accessor spelling, or the sibling callable's shape stays unauthorized.
    """
    entries = _gr08b_policy_entries(tmp_path)
    target = [
        entry
        for entry in entries
        if entry.method == "processInternal"
        and entry.dao_accessor == "pendingReviewDao"
    ][0]
    base = dict(
        path=target.path,
        owner_fqcn=target.owner_fqcn,
        kind=target.kind,
        method=target.method,
        receiver=target.receiver,
        parameter_types=target.parameter_types,
        dao_accessor=target.dao_accessor,
        dao_fqcn=target.dao_fqcn,
        operation=target.operation,
    )
    assert match_mutation(target, **base) is True
    # Wrong operation: the plain Room insert spelling.
    assert match_mutation(target, **dict(base, operation="insert")) is False
    # Wrong DAO identity behind the accessor spelling.
    assert (
        match_mutation(
            target, **dict(base, dao_fqcn=RAW_NOTIFICATION_DAO)
        )
        is False
    )
    # Wrong callable: the sibling needs-review closure row never matches
    # the processInternal identity.
    assert (
        match_mutation(
            target, **dict(base, method="handleNeedsReviewInTransaction")
        )
        is False
    )


# ── (9) GR-08d NEAR-MISS protection ───────────────────────────────────────────
#
# The GR-08d rows authorize EXACTLY their callable identity + DAO +
# operation.  Each test mutates exactly one identity field of a real GR-08d
# row and asserts the mutation stays unauthorized.  The closure rows get the
# same exactness treatment (GR-08b closure precedent).


def test_gr08d_exact_identity_matches(tmp_path):
    rows = _gr08d_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "approveReview", "pendingReviewDao",
            "transitionStatus",
        )
        is True
    )


def test_gr08d_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08d_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "approveReview", "pendingReviewDao",
            "transitionStatus", parameter_types=("Long",),
        )
        is False
    )


def test_gr08d_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08d_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "approveReview", "pendingReviewDao",
            "transitionStatus",
            owner_fqcn="com.example.OtherRepository",
        )
        is False
    )


def test_gr08d_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08d_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "approveReview", "pendingReviewDao",
            "transitionStatus",
            dao_accessor="rawNotificationDao",
            dao_fqcn=RAW_NOTIFICATION_DAO_GR08D,
        )
        is False
    )


def test_gr08d_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08d_seed_rows()
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, "approveReview", "pendingReviewDao",
            "transitionStatus", operation="updateStatus",
        )
        is False
    )


def test_gr08d_recover_stuck_row_near_misses_stay_unauthorized(tmp_path):
    """The zero-parameter recovery row is exact too: siblings never match."""
    rows = _gr08d_seed_rows()
    base_kwargs = dict(
        select_method="recoverStuckReviews",
        select_accessor="pendingReviewDao",
        select_operation="recoverStuckProcessing",
    )
    assert _assert_gr08c_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong overload: a synthetic single-parameter shape.
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, **dict(base_kwargs, parameter_types=("Long",))
        )
        is False
    )
    # Wrong operation: the plain status-update spelling behind the same DAO.
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, **dict(base_kwargs, operation="updateStatus")
        )
        is False
    )
    # Wrong callable: the sibling rejectReview transition row never matches
    # the recovery identity.
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, **dict(base_kwargs, method="rejectReview")
        )
        is False
    )


def test_gr08d_closure_rows_near_misses_stay_unauthorized(tmp_path):
    """The three closure rows are exact too: mutants never match.

    The closure rows authorize EXACTLY the body-carrying @Transaction
    PendingReviewDao convenience methods on their own callable identities;
    a wrong operation (the plain Room insert/update spellings behind the
    convenience bodies), a wrong DAO identity behind the same accessor
    spelling, a wrong overload, or a sibling callable's shape stays
    unauthorized.
    """
    rows = _gr08d_seed_rows()

    # (1) markAsRelevant / pendingReviewDao / upsertByRawNotificationId.
    upsert_kwargs = dict(
        select_method="markAsRelevant",
        select_accessor="pendingReviewDao",
        select_operation="upsertByRawNotificationId",
    )
    assert _assert_gr08c_exact_match(tmp_path, rows, **upsert_kwargs) is True
    # Wrong operation: the plain Room insert behind the convenience body.
    assert (
        _assert_gr08c_exact_match(
            tmp_path, rows, **dict(upsert_kwargs, operation="insert")
        )
        is False
    )
    # Wrong DAO identity behind the accessor spelling.
    assert (
        _assert_gr08c_exact_match(
            tmp_path,
            rows,
            **dict(
                upsert_kwargs,
                dao_accessor="rawNotificationDao",
                dao_fqcn=RAW_NOTIFICATION_DAO_GR08D,
            ),
        )
        is False
    )
    # Wrong overload: the rejectReview (Long) shape.
    assert (
        _assert_gr08c_exact_match(
            tmp_path,
            rows,
            **dict(upsert_kwargs, parameter_types=("Long",)),
        )
        is False
    )

    # (2) updatePendingReviewCategoryBulk / bulkUpdateCategoryByMerchant.
    category_kwargs = dict(
        select_method="updatePendingReviewCategoryBulk",
        select_accessor="pendingReviewDao",
        select_operation="bulkUpdateCategoryByMerchant",
    )
    assert (
        _assert_gr08c_exact_match(tmp_path, rows, **category_kwargs) is True
    )
    # Wrong operation: the abstract by-key update behind the convenience
    # body never matches the convenience-method identity.
    assert (
        _assert_gr08c_exact_match(
            tmp_path,
            rows,
            **dict(
                category_kwargs,
                operation="bulkUpdateCategoryByMerchantKey",
            ),
        )
        is False
    )
    # Wrong overload: the merchant-rename (String, String) shape.
    assert (
        _assert_gr08c_exact_match(
            tmp_path,
            rows,
            **dict(category_kwargs, parameter_types=("String", "String")),
        )
        is False
    )
    # Wrong callable: the sibling bulk-rename closure row never matches.
    assert (
        _assert_gr08c_exact_match(
            tmp_path,
            rows,
            **dict(
                category_kwargs, method="updatePendingReviewMerchantBulk"
            ),
        )
        is False
    )

    # (3) updatePendingReviewMerchantBulk / bulkRenameMerchant.
    merchant_kwargs = dict(
        select_method="updatePendingReviewMerchantBulk",
        select_accessor="pendingReviewDao",
        select_operation="bulkRenameMerchant",
    )
    assert (
        _assert_gr08c_exact_match(tmp_path, rows, **merchant_kwargs) is True
    )
    # Wrong operation: the abstract by-key rename behind the convenience
    # body never matches the convenience-method identity.
    assert (
        _assert_gr08c_exact_match(
            tmp_path,
            rows,
            **dict(merchant_kwargs, operation="bulkRenameMerchantByKey"),
        )
        is False
    )
    # Wrong overload: the category-bulk (String, Long) shape.
    assert (
        _assert_gr08c_exact_match(
            tmp_path,
            rows,
            **dict(merchant_kwargs, parameter_types=("String", "Long")),
        )
        is False
    )
    # Wrong owner: a copied repository class never matches.
    assert (
        _assert_gr08c_exact_match(
            tmp_path,
            rows,
            **dict(merchant_kwargs, owner_fqcn="com.example.CopyRepository"),
        )
        is False
    )


# ── (10) GR-08e1/e2 rows: tracked seed files + concatenation + NEAR-MISS ──────
#
# GR-08e authorizes the two repository-layer files NotificationRepository.kt
# and WarrantyTrackerRepository.kt (46 findings / 46 unique fingerprints >
# the 25-fingerprint batch cap, hence the GR-08e1/e2 split).  The migration
# CLI accepts a SINGLE --seed-rows value, so every generation run consumes
# the COMBINED document GR-08-seeds.yml; these tests pin that the combined
# document stays the exact concatenation of the SEVEN reviewed batch seed
# files, and that the GR-08e1/e2 rows authorize EXACTLY their callable
# identity + DAO + operation (wrong overload, wrong owner, wrong DAO, and
# wrong operation stay unauthorized).  The accessor-normalized rows (the
# GR-08e source change replaced the database-chained receivers with injected
# constructor properties) get explicit near-miss coverage: the historical
# chain-text accessor spelling stays unauthorized.

GR08E1_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08e1-seed.yml"
GR08E2_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08e2-seed.yml"

NOTIFICATION_REPO_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "NotificationRepository.kt"
)
NOTIFICATION_REPO_FQCN = (
    "com.yourname.expensetracker.data.repository.NotificationRepository"
)
WARRANTY_REPO_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "WarrantyTrackerRepository.kt"
)
WARRANTY_REPO_FQCN = (
    "com.yourname.expensetracker.data.repository.WarrantyTrackerRepository"
)
BLOCKED_PACKAGE_DAO = (
    "com.yourname.expensetracker.data.database.dao.BlockedPackageDao"
)
EXPENSE_DAO_GR08E = (
    "com.yourname.expensetracker.data.database.dao.ExpenseDao"
)
WARRANTY_DAO = "com.yourname.expensetracker.data.database.dao.WarrantyDao"
RETURN_WINDOW_DAO = (
    "com.yourname.expensetracker.data.database.dao.ReturnWindowDao"
)
WARRANTY_LIFECYCLE_EVENT_DAO = (
    "com.yourname.expensetracker.data.database.dao.WarrantyLifecycleEventDao"
)
RAW_NOTIFICATION_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.RawNotification"
)
DEBUG_SNAPSHOT = NOTIFICATION_REPO_FQCN + ".DebugNotificationsSnapshot"
SOURCE_STATS_LIST = (
    "List<com.yourname.expensetracker.data.database.entity.SourceStats>"
)
WARRANTY_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.Warranty"
)
RETURN_WINDOW_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.ReturnWindow"
)
SCANNED_RECEIPT_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.ScannedReceipt"
)
WARRANTY_EXTRACTION_RESULT = (
    "com.yourname.expensetracker.domain.ai.model.WarrantyExtractionResult"
)
DELETE_ALL_NOTIFICATIONS_PARAMS = ("Long",)
DELETE_ALL_PARAMS: tuple = ()
RESTORE_SNAPSHOT_PARAMS = (DEBUG_SNAPSHOT,)
RESTORE_STATS_PARAMS = (SOURCE_STATS_LIST,)
WARRANTY_PARAMS = (WARRANTY_ENTITY,)
RETURN_WINDOW_PARAMS = (RETURN_WINDOW_ENTITY,)
MARK_AS_RETURNED_PARAMS = ("Long", "Double?", "String?")
UPSERT_RETURN_WINDOW_PARAMS = ("Long", WARRANTY_ENTITY + "?")
TO_WARRANTY_ENTITY_PARAMS = (SCANNED_RECEIPT_ENTITY,)


def _gr08e_seed_row(path, owner_fqcn, method, dao_accessor, dao_fqcn,
                    operation, params):
    """One exact GR-08e-shaped v2 seed row mapping."""
    return {
        "path": path,
        "ownerFqcn": owner_fqcn,
        "kind": "function",
        "method": method,
        "receiver": None,
        "parameterTypes": list(params),
        "daoAccessor": dao_accessor,
        "daoFqcn": dao_fqcn,
        "operation": operation,
        "barrierMode": "helper",
        "reason": "GR-08e EXACT_POLICY test row",
        "owner": "@panospao7",
        "linkedIssue": "MIT-DB-08E",
    }


def _gr08e1_seed_rows():
    """The twenty-three exact GR-08e1 rows (mirroring the tracked seed file).

    NotificationRepository.kt; ZERO closure rows.  The
    deleteAllNotifications/transactionEventDao row spells the NORMALIZED
    accessor (the GR-08e source change replaced the database-chained
    receiver with an injected constructor property).
    """
    rows = []
    rows.append(
        _gr08e_seed_row(
            NOTIFICATION_REPO_KT, NOTIFICATION_REPO_FQCN, "save", "dao",
            RAW_NOTIFICATION_DAO, "insert", (RAW_NOTIFICATION_ENTITY,),
        )
    )
    rows.append(
        _gr08e_seed_row(
            NOTIFICATION_REPO_KT, NOTIFICATION_REPO_FQCN, "blockPackage",
            "blockedPackageDao", BLOCKED_PACKAGE_DAO, "block", ("String",),
        )
    )
    rows.append(
        _gr08e_seed_row(
            NOTIFICATION_REPO_KT, NOTIFICATION_REPO_FQCN, "unblockPackage",
            "blockedPackageDao", BLOCKED_PACKAGE_DAO, "unblock", ("String",),
        )
    )
    for accessor, dao, operation in (
        ("sourceStatsDao", SOURCE_STATS_DAO, "decrementPending"),
        ("pendingReviewDao", PENDING_REVIEW_DAO, "deleteByRawId"),
        ("dao", RAW_NOTIFICATION_DAO, "delete"),
    ):
        rows.append(
            _gr08e_seed_row(
                NOTIFICATION_REPO_KT, NOTIFICATION_REPO_FQCN, "delete",
                accessor, dao, operation, (RAW_NOTIFICATION_ENTITY,),
            )
        )
    for accessor, dao, operation in (
        ("transactionEventDao", TRANSACTION_EVENT_DAO, "insert"),
        ("dao", RAW_NOTIFICATION_DAO, "deleteAll"),
        ("pendingReviewDao", PENDING_REVIEW_DAO, "deleteAll"),
        ("userCorrectionDao", USER_CORRECTION_DAO, "deleteAll"),
        ("sourceStatsDao", SOURCE_STATS_DAO, "resetAllPendingCounts"),
    ):
        rows.append(
            _gr08e_seed_row(
                NOTIFICATION_REPO_KT, NOTIFICATION_REPO_FQCN,
                "deleteAllNotifications", accessor, dao, operation,
                DELETE_ALL_NOTIFICATIONS_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("dao", RAW_NOTIFICATION_DAO, "deleteAll"),
        ("expenseDao", EXPENSE_DAO_GR08E, "deleteAll"),
        ("pendingReviewDao", PENDING_REVIEW_DAO, "deleteAll"),
        ("userCorrectionDao", USER_CORRECTION_DAO, "deleteAll"),
        ("sourceStatsDao", SOURCE_STATS_DAO, "resetAllPendingCounts"),
    ):
        rows.append(
            _gr08e_seed_row(
                NOTIFICATION_REPO_KT, NOTIFICATION_REPO_FQCN, "deleteAll",
                accessor, dao, operation, DELETE_ALL_PARAMS,
            )
        )
    rows.append(
        _gr08e_seed_row(
            NOTIFICATION_REPO_KT, NOTIFICATION_REPO_FQCN, "resetSourceStats",
            "sourceStatsDao", SOURCE_STATS_DAO, "deleteAll",
            DELETE_ALL_PARAMS,
        )
    )
    for accessor, dao, operation in (
        ("dao", RAW_NOTIFICATION_DAO, "deleteAll"),
        ("sourceStatsDao", SOURCE_STATS_DAO, "deleteAll"),
        ("dao", RAW_NOTIFICATION_DAO, "insertAll"),
        ("sourceStatsDao", SOURCE_STATS_DAO, "insertAll"),
    ):
        rows.append(
            _gr08e_seed_row(
                NOTIFICATION_REPO_KT, NOTIFICATION_REPO_FQCN,
                "restoreDebugSnapshot", accessor, dao, operation,
                RESTORE_SNAPSHOT_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("sourceStatsDao", SOURCE_STATS_DAO, "deleteAll"),
        ("sourceStatsDao", SOURCE_STATS_DAO, "insertAll"),
    ):
        rows.append(
            _gr08e_seed_row(
                NOTIFICATION_REPO_KT, NOTIFICATION_REPO_FQCN,
                "restoreSourceStatsSnapshot", accessor, dao, operation,
                RESTORE_STATS_PARAMS,
            )
        )
    return rows


def _gr08e2_seed_rows():
    """The twenty-three exact GR-08e2 rows (mirroring the tracked seed file).

    WarrantyTrackerRepository.kt; ZERO closure rows.  The eight
    warrantyLifecycleEventDao rows spell the NORMALIZED accessor (the
    GR-08e source change replaced the database-chained receivers with an
    injected constructor property).
    """
    rows = []
    for accessor, dao, operation in (
        ("warrantyDao", WARRANTY_DAO, "insertWarranty"),
        ("warrantyLifecycleEventDao", WARRANTY_LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08e_seed_row(
                WARRANTY_REPO_KT, WARRANTY_REPO_FQCN, "addWarranty",
                accessor, dao, operation, WARRANTY_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("warrantyDao", WARRANTY_DAO, "insertWarrantyIgnore"),
        ("warrantyLifecycleEventDao", WARRANTY_LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08e_seed_row(
                WARRANTY_REPO_KT, WARRANTY_REPO_FQCN,
                "addWarrantyIgnoreConflicts", accessor, dao, operation,
                WARRANTY_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("warrantyDao", WARRANTY_DAO, "updateWarranty"),
        ("warrantyLifecycleEventDao", WARRANTY_LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08e_seed_row(
                WARRANTY_REPO_KT, WARRANTY_REPO_FQCN, "updateWarranty",
                accessor, dao, operation, WARRANTY_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("warrantyDao", WARRANTY_DAO, "deleteWarranty"),
        ("warrantyLifecycleEventDao", WARRANTY_LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08e_seed_row(
                WARRANTY_REPO_KT, WARRANTY_REPO_FQCN, "deleteWarranty",
                accessor, dao, operation, WARRANTY_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("returnWindowDao", RETURN_WINDOW_DAO, "deleteReturnWindow"),
        ("warrantyDao", WARRANTY_DAO, "deleteWarranty"),
        ("warrantyLifecycleEventDao", WARRANTY_LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08e_seed_row(
                WARRANTY_REPO_KT, WARRANTY_REPO_FQCN,
                "rejectAutoDetectedWarranty", accessor, dao, operation,
                WARRANTY_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("warrantyDao", WARRANTY_DAO, "updateWarrantyStatus"),
        ("warrantyLifecycleEventDao", WARRANTY_LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08e_seed_row(
                WARRANTY_REPO_KT, WARRANTY_REPO_FQCN, "markWarrantyAsClaimed",
                accessor, dao, operation, ("Long",),
            )
        )
    for accessor, dao, operation in (
        ("warrantyDao", WARRANTY_DAO, "markExpiredWarranties"),
        ("returnWindowDao", RETURN_WINDOW_DAO, "markExpiredReturnWindows"),
        ("warrantyLifecycleEventDao", WARRANTY_LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08e_seed_row(
                WARRANTY_REPO_KT, WARRANTY_REPO_FQCN, "reconcileExpiredItems",
                accessor, dao, operation, ("Long",),
            )
        )
    rows.append(
        _gr08e_seed_row(
            WARRANTY_REPO_KT, WARRANTY_REPO_FQCN, "toWarrantyEntityOrNull",
            "warrantyLifecycleEventDao", WARRANTY_LIFECYCLE_EVENT_DAO,
            "insert", TO_WARRANTY_ENTITY_PARAMS,
        )
    )
    # Fix the receiver on the extension-function row (the generic row helper
    # leaves it None; the tracked seed file carries the extension receiver).
    rows[-1]["receiver"] = WARRANTY_EXTRACTION_RESULT
    rows.append(
        _gr08e_seed_row(
            WARRANTY_REPO_KT, WARRANTY_REPO_FQCN, "addReturnWindow",
            "returnWindowDao", RETURN_WINDOW_DAO, "insertReturnWindow",
            RETURN_WINDOW_PARAMS,
        )
    )
    rows.append(
        _gr08e_seed_row(
            WARRANTY_REPO_KT, WARRANTY_REPO_FQCN, "updateReturnWindow",
            "returnWindowDao", RETURN_WINDOW_DAO, "updateReturnWindow",
            RETURN_WINDOW_PARAMS,
        )
    )
    rows.append(
        _gr08e_seed_row(
            WARRANTY_REPO_KT, WARRANTY_REPO_FQCN, "deleteReturnWindow",
            "returnWindowDao", RETURN_WINDOW_DAO, "deleteReturnWindow",
            RETURN_WINDOW_PARAMS,
        )
    )
    rows.append(
        _gr08e_seed_row(
            WARRANTY_REPO_KT, WARRANTY_REPO_FQCN, "markAsReturned",
            "returnWindowDao", RETURN_WINDOW_DAO, "updateReturnWindow",
            MARK_AS_RETURNED_PARAMS,
        )
    )
    for operation in ("insertReturnWindow", "updateReturnWindow"):
        rows.append(
            _gr08e_seed_row(
                WARRANTY_REPO_KT, WARRANTY_REPO_FQCN,
                "upsertReturnWindowForReceipt", "returnWindowDao",
                RETURN_WINDOW_DAO, operation, UPSERT_RETURN_WINDOW_PARAMS,
            )
        )
    return rows


def test_real_tracked_gr08e1_seed_file_loads_with_exactly_twenty_three_rows():
    entries = _load_seed_entries(GR08E1_SEED_FILE)
    assert len(entries) == 23
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted(
        ["save"]
        + ["blockPackage", "unblockPackage"]
        + ["delete"] * 3
        + ["deleteAllNotifications"] * 5
        + ["deleteAll"] * 5
        + ["resetSourceStats"]
        + ["restoreDebugSnapshot"] * 4
        + ["restoreSourceStatsSnapshot"] * 2
    )
    for entry in entries:
        assert entry.path == NOTIFICATION_REPO_KT
        assert entry.owner_fqcn == NOTIFICATION_REPO_FQCN
        assert entry.kind is CallableKind.FUNCTION
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08E"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)
    # ZERO closure rows: every accessor is a plain constructor property or
    # the normalized transactionEventDao property; no convenience methods.
    assert all(
        entry.dao_accessor
        in {
            "dao",
            "blockedPackageDao",
            "expenseDao",
            "pendingReviewDao",
            "userCorrectionDao",
            "sourceStatsDao",
            "transactionEventDao",
        }
        for entry in entries
    )
    # The normalized accessor row: exactly one, on deleteAllNotifications.
    normalized = [
        (entry.method, entry.dao_accessor, entry.operation)
        for entry in entries
        if entry.dao_accessor == "transactionEventDao"
    ]
    assert normalized == [
        ("deleteAllNotifications", "transactionEventDao", "insert")
    ]


def test_real_tracked_gr08e2_seed_file_loads_with_exactly_twenty_three_rows():
    entries = _load_seed_entries(GR08E2_SEED_FILE)
    assert len(entries) == 23
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted(
        ["addWarranty"] * 2
        + ["addWarrantyIgnoreConflicts"] * 2
        + ["updateWarranty"] * 2
        + ["deleteWarranty"] * 2
        + ["rejectAutoDetectedWarranty"] * 3
        + ["markWarrantyAsClaimed"] * 2
        + ["reconcileExpiredItems"] * 3
        + ["toWarrantyEntityOrNull"]
        + ["addReturnWindow", "updateReturnWindow", "deleteReturnWindow"]
        + ["markAsReturned"]
        + ["upsertReturnWindowForReceipt"] * 2
    )
    for entry in entries:
        assert entry.path == WARRANTY_REPO_KT
        assert entry.owner_fqcn == WARRANTY_REPO_FQCN
        assert entry.kind is CallableKind.FUNCTION
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08E"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)
    # ZERO closure rows: every accessor is a plain constructor property or
    # the normalized warrantyLifecycleEventDao property.
    assert all(
        entry.dao_accessor
        in {"warrantyDao", "returnWindowDao", "warrantyLifecycleEventDao"}
        for entry in entries
    )
    # The normalized accessor rows: exactly eight, all lifecycle inserts.
    normalized = sorted(
        (entry.method, entry.operation)
        for entry in entries
        if entry.dao_accessor == "warrantyLifecycleEventDao"
    )
    assert normalized == sorted([
        ("addWarranty", "insert"),
        ("addWarrantyIgnoreConflicts", "insert"),
        ("updateWarranty", "insert"),
        ("deleteWarranty", "insert"),
        ("rejectAutoDetectedWarranty", "insert"),
        ("markWarrantyAsClaimed", "insert"),
        ("reconcileExpiredItems", "insert"),
        ("toWarrantyEntityOrNull", "insert"),
    ])
    # The extension-function row carries its receiver identity.
    extension = [
        entry for entry in entries if entry.method == "toWarrantyEntityOrNull"
    ]
    assert len(extension) == 1
    assert extension[0].receiver == WARRANTY_EXTRACTION_RESULT


def _gr08e_policy_entries(tmp_path, rows):
    entries = []
    for position, row in enumerate(rows):
        entry, errors = build_policy_entry(row, position)
        assert entry is not None and not errors, (
            "GR-08e fixture row must be schema-valid: %s" % (errors,)
        )
        entries.append(entry)
    return entries


def _assert_gr08e_exact_match(tmp_path, rows, select_method, select_accessor,
                              select_operation, **overrides):
    """The exact GR-08e row identity matches; mutants never do.

    Target selection is fixed by ``(select_method, select_accessor,
    select_operation)``; ``overrides`` perturb exactly one identity field of
    the match query for the near-miss assertions.
    """
    entries = _gr08e_policy_entries(tmp_path, rows)
    target = [
        entry
        for entry in entries
        if entry.method == select_method
        and entry.dao_accessor == select_accessor
        and entry.operation == select_operation
    ][0]
    kwargs = dict(
        path=target.path,
        owner_fqcn=target.owner_fqcn,
        kind=target.kind,
        method=target.method,
        receiver=target.receiver,
        parameter_types=target.parameter_types,
        dao_accessor=target.dao_accessor,
        dao_fqcn=target.dao_fqcn,
        operation=target.operation,
    )
    kwargs.update(overrides)
    return match_mutation(target, **kwargs)


def test_gr08e1_exact_identity_matches(tmp_path):
    rows = _gr08e1_seed_rows()
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, "save", "dao", "insert"
        )
        is True
    )


def test_gr08e1_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08e1_seed_rows()
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, "save", "dao", "insert",
            parameter_types=("String",),
        )
        is False
    )


def test_gr08e1_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08e1_seed_rows()
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, "save", "dao", "insert",
            owner_fqcn="com.example.OtherRepository",
        )
        is False
    )


def test_gr08e1_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08e1_seed_rows()
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, "save", "dao", "insert",
            dao_accessor="sourceStatsDao",
            dao_fqcn=SOURCE_STATS_DAO,
        )
        is False
    )


def test_gr08e1_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08e1_seed_rows()
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, "save", "dao", "insert", operation="insertAll"
        )
        is False
    )


def test_gr08e1_normalized_accessor_row_near_misses_stay_unauthorized(tmp_path):
    """The accessor-normalized audit row is exact too: mutants never match.

    The GR-08e source change replaced the database-chained
    ``database.transactionEventDao()`` receiver with the injected
    ``transactionEventDao`` constructor property.  The seed row spells the
    NORMALIZED accessor; the historical chain text, a wrong DAO identity
    behind the normalized spelling, a wrong overload, and the sibling
    deleteAll identity all stay unauthorized.
    """
    rows = _gr08e1_seed_rows()
    base_kwargs = dict(
        select_method="deleteAllNotifications",
        select_accessor="transactionEventDao",
        select_operation="insert",
    )
    assert _assert_gr08e_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong accessor: the historical database-chained spelling.
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows,
            **dict(base_kwargs, dao_accessor="database.transactionEventDao()")
        )
        is False
    )
    # Wrong DAO identity behind the normalized spelling.
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows,
            **dict(base_kwargs, dao_fqcn=SOURCE_STATS_DAO)
        )
        is False
    )
    # Wrong overload: the deprecated zero-parameter deleteAll shape.
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, **dict(base_kwargs, parameter_types=())
        )
        is False
    )
    # Wrong callable: the sibling deleteAll identity never matches.
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, **dict(base_kwargs, method="deleteAll")
        )
        is False
    )


def test_gr08e2_exact_identity_matches(tmp_path):
    rows = _gr08e2_seed_rows()
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, "addWarranty", "warrantyDao", "insertWarranty"
        )
        is True
    )


def test_gr08e2_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08e2_seed_rows()
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, "addWarranty", "warrantyDao", "insertWarranty",
            parameter_types=(RETURN_WINDOW_ENTITY,),
        )
        is False
    )


def test_gr08e2_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08e2_seed_rows()
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, "addWarranty", "warrantyDao", "insertWarranty",
            owner_fqcn="com.example.OtherRepository",
        )
        is False
    )


def test_gr08e2_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08e2_seed_rows()
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, "addWarranty", "warrantyDao", "insertWarranty",
            dao_accessor="returnWindowDao",
            dao_fqcn=RETURN_WINDOW_DAO,
        )
        is False
    )


def test_gr08e2_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08e2_seed_rows()
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, "addWarranty", "warrantyDao", "insertWarranty",
            operation="insertWarrantyIgnore",
        )
        is False
    )


def test_gr08e2_normalized_accessor_rows_near_misses_stay_unauthorized(tmp_path):
    """The accessor-normalized lifecycle rows are exact too: mutants never
    match.

    The GR-08e source change replaced the database-chained
    ``database.warrantyLifecycleEventDao()`` receivers with the injected
    ``warrantyLifecycleEventDao`` constructor property.  The seed rows spell
    the NORMALIZED accessor; the historical chain text, a wrong DAO identity
    behind the normalized spelling, a wrong operation, and the sibling
    callable's shape all stay unauthorized.
    """
    rows = _gr08e2_seed_rows()
    base_kwargs = dict(
        select_method="addWarranty",
        select_accessor="warrantyLifecycleEventDao",
        select_operation="insert",
    )
    assert _assert_gr08e_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong accessor: the historical database-chained spelling.
    assert (
        _assert_gr08e_exact_match(
            tmp_path,
            rows,
            **dict(
                base_kwargs,
                dao_accessor="database.warrantyLifecycleEventDao()",
            ),
        )
        is False
    )
    # Wrong DAO identity behind the normalized spelling.
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, **dict(base_kwargs, dao_fqcn=WARRANTY_DAO)
        )
        is False
    )
    # Wrong operation: the plain Room insert spelling behind the sibling DAO.
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, **dict(base_kwargs, operation="insertWarranty")
        )
        is False
    )
    # Wrong callable: the sibling addWarrantyIgnoreConflicts lifecycle row
    # never matches the addWarranty identity.
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows,
            **dict(base_kwargs, method="addWarrantyIgnoreConflicts")
        )
        is False
    )


def test_gr08e2_extension_row_near_misses_stay_unauthorized(tmp_path):
    """The extension-function row pins its receiver identity too."""
    rows = _gr08e2_seed_rows()
    base_kwargs = dict(
        select_method="toWarrantyEntityOrNull",
        select_accessor="warrantyLifecycleEventDao",
        select_operation="insert",
    )
    assert _assert_gr08e_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong receiver: a bare function shape never matches the extension.
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows, **dict(base_kwargs, receiver=None)
        )
        is False
    )
    # Wrong overload: the addWarranty entity shape.
    assert (
        _assert_gr08e_exact_match(
            tmp_path, rows,
            **dict(base_kwargs, parameter_types=(WARRANTY_ENTITY,))
        )
        is False
    )


# ── (11) GR-08f rows: tracked seed file + concatenation + NEAR-MISS ───────────
#
# GR-08f authorizes the RecurringRuleLifecycleCoordinator.kt
# domain-lifecycle callables (21 findings / 21 unique fingerprints, within
# the 25-fingerprint batch cap so NO split was required).  The migration
# CLI accepts a SINGLE --seed-rows value, so every generation run consumes
# the COMBINED document GR-08-seeds.yml; these tests pin that the combined
# document stays the exact concatenation of the EIGHT reviewed batch seed
# files, and that the GR-08f rows authorize EXACTLY their callable identity
# + DAO + operation (wrong overload, wrong owner, wrong DAO, and wrong
# operation stay unauthorized).

GR08F_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08f-seed.yml"

RULE_COORDINATOR_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/recurring/"
    "lifecycle/RecurringRuleLifecycleCoordinator.kt"
)
RULE_COORDINATOR_FQCN = (
    "com.yourname.expensetracker.domain.recurring.lifecycle."
    "RecurringRuleLifecycleCoordinator"
)
MANUAL_RECURRING_EXPENSE_DAO = (
    "com.yourname.expensetracker.data.database.dao."
    "ManualRecurringExpenseDao"
)
MANUAL_RECURRING_EXPENSE_ENTITY = (
    "com.yourname.expensetracker.data.database.entity."
    "ManualRecurringExpense"
)
RULE_ID_PARAMS = ("Long",)
ADVANCE_PARAMS = ("Long", "Long")
RULE_ENTITY_PARAMS = (MANUAL_RECURRING_EXPENSE_ENTITY,)


def _gr08f_seed_row(method, dao_accessor, dao_fqcn, operation, params):
    """One exact GR-08f-shaped v2 seed row mapping."""
    return {
        "path": RULE_COORDINATOR_KT,
        "ownerFqcn": RULE_COORDINATOR_FQCN,
        "kind": "function",
        "method": method,
        "receiver": None,
        "parameterTypes": list(params),
        "daoAccessor": dao_accessor,
        "daoFqcn": dao_fqcn,
        "operation": operation,
        "barrierMode": "helper",
        "reason": "GR-08f EXACT_POLICY test row",
        "owner": "@panospao7",
        "linkedIssue": "MIT-DB-08F",
    }


def _gr08f_seed_rows():
    """The twenty-one exact GR-08f rows (mirroring the tracked seed file).

    ZERO closure rows: the blind-spot sweep found every mutating DAO call
    in the file is an abstract Room-annotated method already covered by a
    finding (all five mutated DAOs are fully abstract interfaces; the
    sixth accessor, expenseDao, is called only read-only via
    getExpensesBetween).
    """
    rows = []
    for accessor, dao, operation in (
        ("manualRecurringExpenseDao", MANUAL_RECURRING_EXPENSE_DAO,
         "setActiveStatus"),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08f_seed_row(
                "activateRule", accessor, dao, operation, RULE_ID_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("manualRecurringExpenseDao", MANUAL_RECURRING_EXPENSE_DAO,
         "updateNextDate"),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08f_seed_row(
                "advanceNextDate", accessor, dao, operation, ADVANCE_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("manualRecurringExpenseDao", MANUAL_RECURRING_EXPENSE_DAO,
         "insert"),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08f_seed_row(
                "createRule", accessor, dao, operation, RULE_ENTITY_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("manualRecurringExpenseDao", MANUAL_RECURRING_EXPENSE_DAO,
         "setActiveStatus"),
        ("reminderDeliveryDao", REMINDER_DELIVERY_DAO,
         "deleteByOccurrenceIds"),
        ("occurrenceDao", OCCURRENCE_DAO, "deleteOpenPlannedBySource"),
        ("plannedExpenseDao", PLANNED_EXPENSE_DAO,
         "deleteOpenPlannedByRecurringRuleId"),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08f_seed_row(
                "deactivateRule", accessor, dao, operation, RULE_ID_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("reminderDeliveryDao", REMINDER_DELIVERY_DAO,
         "deleteByOccurrenceIds"),
        ("plannedExpenseDao", PLANNED_EXPENSE_DAO,
         "deleteByRecurringRuleId"),
        ("occurrenceDao", OCCURRENCE_DAO, "deleteBySource"),
        ("manualRecurringExpenseDao", MANUAL_RECURRING_EXPENSE_DAO,
         "deleteById"),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08f_seed_row(
                "deleteRule", accessor, dao, operation, RULE_ID_PARAMS,
            )
        )
    for accessor, dao, operation in (
        ("reminderDeliveryDao", REMINDER_DELIVERY_DAO,
         "deleteByOccurrenceIds"),
        ("occurrenceDao", OCCURRENCE_DAO, "deleteOpenPlannedBySource"),
        ("plannedExpenseDao", PLANNED_EXPENSE_DAO,
         "deleteOpenPlannedByRecurringRuleId"),
        ("manualRecurringExpenseDao", MANUAL_RECURRING_EXPENSE_DAO,
         "update"),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
    ):
        rows.append(
            _gr08f_seed_row(
                "updateRule", accessor, dao, operation, RULE_ENTITY_PARAMS,
            )
        )
    return rows


def test_real_tracked_gr08f_seed_file_loads_with_exactly_twenty_one_rows():
    entries = _load_seed_entries(GR08F_SEED_FILE)
    assert len(entries) == 21
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted(
        ["activateRule"] * 2
        + ["advanceNextDate"] * 2
        + ["createRule"] * 2
        + ["deactivateRule"] * 5
        + ["deleteRule"] * 5
        + ["updateRule"] * 5
    )
    for entry in entries:
        assert entry.path == RULE_COORDINATOR_KT
        assert entry.owner_fqcn == RULE_COORDINATOR_FQCN
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08F"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)
    # ZERO closure rows: every accessor is a plain constructor property and
    # every operation is the exact abstract Room method the scanner
    # reported -- no convenience-method rows exist in this batch.
    assert all(
        entry.dao_accessor
        in {
            "manualRecurringExpenseDao",
            "occurrenceDao",
            "reminderDeliveryDao",
            "plannedExpenseDao",
            "lifecycleEventDao",
        }
        for entry in entries
    )
    # The lifecycle-event provenance rows: exactly six, one per callable.
    provenance = sorted(
        entry.method
        for entry in entries
        if entry.dao_accessor == "lifecycleEventDao"
    )
    assert provenance == [
        "activateRule",
        "advanceNextDate",
        "createRule",
        "deactivateRule",
        "deleteRule",
        "updateRule",
    ]


def _gr08f_policy_entries(tmp_path, rows):
    entries = []
    for position, row in enumerate(rows):
        entry, errors = build_policy_entry(row, position)
        assert entry is not None and not errors, (
            "GR-08f fixture row must be schema-valid: %s" % (errors,)
        )
        entries.append(entry)
    return entries


def _assert_gr08f_exact_match(tmp_path, rows, select_method, select_accessor,
                              select_operation, **overrides):
    """The exact GR-08f row identity matches; mutants never do.

    Target selection is fixed by ``(select_method, select_accessor,
    select_operation)``; ``overrides`` perturb exactly one identity field of
    the match query for the near-miss assertions.
    """
    entries = _gr08f_policy_entries(tmp_path, rows)
    target = [
        entry
        for entry in entries
        if entry.method == select_method
        and entry.dao_accessor == select_accessor
        and entry.operation == select_operation
    ][0]
    kwargs = dict(
        path=target.path,
        owner_fqcn=target.owner_fqcn,
        kind=target.kind,
        method=target.method,
        receiver=target.receiver,
        parameter_types=target.parameter_types,
        dao_accessor=target.dao_accessor,
        dao_fqcn=target.dao_fqcn,
        operation=target.operation,
    )
    kwargs.update(overrides)
    return match_mutation(target, **kwargs)


def test_gr08f_exact_identity_matches(tmp_path):
    rows = _gr08f_seed_rows()
    assert (
        _assert_gr08f_exact_match(
            tmp_path, rows, "deactivateRule", "manualRecurringExpenseDao",
            "setActiveStatus",
        )
        is True
    )


def test_gr08f_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08f_seed_rows()
    assert (
        _assert_gr08f_exact_match(
            tmp_path, rows, "deactivateRule", "manualRecurringExpenseDao",
            "setActiveStatus", parameter_types=("Long", "Boolean"),
        )
        is False
    )


def test_gr08f_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08f_seed_rows()
    assert (
        _assert_gr08f_exact_match(
            tmp_path, rows, "deactivateRule", "manualRecurringExpenseDao",
            "setActiveStatus",
            owner_fqcn="com.example.OtherCoordinator",
        )
        is False
    )


def test_gr08f_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08f_seed_rows()
    assert (
        _assert_gr08f_exact_match(
            tmp_path, rows, "deactivateRule", "manualRecurringExpenseDao",
            "setActiveStatus",
            dao_accessor="occurrenceDao",
            dao_fqcn=OCCURRENCE_DAO,
        )
        is False
    )


def test_gr08f_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08f_seed_rows()
    assert (
        _assert_gr08f_exact_match(
            tmp_path, rows, "deactivateRule", "manualRecurringExpenseDao",
            "setActiveStatus", operation="update",
        )
        is False
    )


def test_gr08f_advance_row_near_misses_stay_unauthorized(tmp_path):
    """The two-parameter advance row is exact too: siblings never match."""
    rows = _gr08f_seed_rows()
    base_kwargs = dict(
        select_method="advanceNextDate",
        select_accessor="manualRecurringExpenseDao",
        select_operation="updateNextDate",
    )
    assert _assert_gr08f_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong overload: the single-parameter rule-id shape.
    assert (
        _assert_gr08f_exact_match(
            tmp_path, rows, **dict(base_kwargs, parameter_types=("Long",))
        )
        is False
    )
    # Wrong operation: the plain Room update spelling behind the same DAO.
    assert (
        _assert_gr08f_exact_match(
            tmp_path, rows, **dict(base_kwargs, operation="update")
        )
        is False
    )
    # Wrong callable: the sibling activateRule status row never matches the
    # advance identity.
    assert (
        _assert_gr08f_exact_match(
            tmp_path, rows, **dict(base_kwargs, method="activateRule")
        )
        is False
    )


def test_gr08f_entity_rows_near_misses_stay_unauthorized(tmp_path):
    """The ManualRecurringExpense-entity rows are exact too.

    createRule/insert and updateRule/update share the entity parameter
    shape but differ in callable + operation; a swapped operation, a
    swapped callable, or the Long rule-id overload stays unauthorized.
    """
    rows = _gr08f_seed_rows()
    base_kwargs = dict(
        select_method="createRule",
        select_accessor="manualRecurringExpenseDao",
        select_operation="insert",
    )
    assert _assert_gr08f_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong operation: the plain entity-update spelling.
    assert (
        _assert_gr08f_exact_match(
            tmp_path, rows, **dict(base_kwargs, operation="update")
        )
        is False
    )
    # Wrong callable: the sibling updateRule entity row never matches the
    # createRule identity.
    assert (
        _assert_gr08f_exact_match(
            tmp_path, rows, **dict(base_kwargs, method="updateRule")
        )
        is False
    )
    # Wrong overload: the Long rule-id shape.
    assert (
        _assert_gr08f_exact_match(
            tmp_path, rows, **dict(base_kwargs, parameter_types=("Long",))
        )
        is False
    )
    # Wrong owner: a copied coordinator class never matches.
    assert (
        _assert_gr08f_exact_match(
            tmp_path,
            rows,
            **dict(base_kwargs, owner_fqcn="com.example.CopyCoordinator"),
        )
        is False
    )


def test_gr08f_provenance_rows_near_misses_stay_unauthorized(tmp_path):
    """The lifecycleEventDao provenance rows are exact per callable.

    All six callables write the same lifecycleEventDao.insert operation;
    each row authorizes EXACTLY its own callable identity, so a sibling
    callable's shape (e.g. deleteRule vs deactivateRule) stays
    unauthorized.
    """
    rows = _gr08f_seed_rows()
    base_kwargs = dict(
        select_method="deleteRule",
        select_accessor="lifecycleEventDao",
        select_operation="insert",
    )
    assert _assert_gr08f_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong callable: the sibling deactivateRule provenance row never
    # matches the deleteRule identity.
    assert (
        _assert_gr08f_exact_match(
            tmp_path, rows, **dict(base_kwargs, method="deactivateRule")
        )
        is False
    )
    # Wrong DAO identity behind the accessor spelling.
    assert (
        _assert_gr08f_exact_match(
            tmp_path,
            rows,
            **dict(base_kwargs, dao_fqcn=OCCURRENCE_DAO),
        )
        is False
    )
    # Wrong overload: the (Long, Long) advance shape.
    assert (
        _assert_gr08f_exact_match(
            tmp_path, rows,
            **dict(base_kwargs, parameter_types=("Long", "Long"))
        )
        is False
    )


# ── (12) GR-08g rows: tracked seed file + concatenation + NEAR-MISS ───────────
#
# GR-08g authorizes the BankStatementLifecycleProcessor.kt receipt-lifecycle
# callable (20 findings / 7 unique fingerprints, within the 25-fingerprint
# batch cap so NO split was required).  The migration CLI accepts a SINGLE
# --seed-rows value, so every generation run consumes the COMBINED document
# GR-08-seeds.yml; these tests pin that the combined document stays the
# exact concatenation of the NINE reviewed batch seed files, and that the
# GR-08g rows authorize EXACTLY their callable identity + DAO + operation
# (wrong overload, wrong owner, wrong DAO, and wrong operation stay
# unauthorized).

GR08G_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08g-seed.yml"

BANK_STATEMENT_PROCESSOR_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/receipt/"
    "lifecycle/BankStatementLifecycleProcessor.kt"
)
BANK_STATEMENT_PROCESSOR_FQCN = (
    "com.yourname.expensetracker.domain.receipt.lifecycle."
    "BankStatementLifecycleProcessor"
)
BANK_STATEMENT_IMPORT_RUN_DAO = (
    "com.yourname.expensetracker.data.database.dao."
    "BankStatementImportRunDao"
)
BANK_STATEMENT_IMPORT_ITEM_DAO = (
    "com.yourname.expensetracker.data.database.dao."
    "BankStatementImportItemDao"
)
SCANNED_RECEIPT_DAO = (
    "com.yourname.expensetracker.data.database.dao.ScannedReceiptDao"
)
STATEMENT_URI_PARAMS = ("android.net.Uri",)


def _gr08g_seed_row(dao_accessor, dao_fqcn, operation):
    """One exact GR-08g-shaped v2 seed row mapping."""
    return {
        "path": BANK_STATEMENT_PROCESSOR_KT,
        "ownerFqcn": BANK_STATEMENT_PROCESSOR_FQCN,
        "kind": "function",
        "method": "processBankStatement",
        "receiver": None,
        "parameterTypes": list(STATEMENT_URI_PARAMS),
        "daoAccessor": dao_accessor,
        "daoFqcn": dao_fqcn,
        "operation": operation,
        "barrierMode": "helper",
        "reason": "GR-08g EXACT_POLICY test row",
        "owner": "@panospao7",
        "linkedIssue": "MIT-DB-08G",
    }


def _gr08g_seed_rows():
    """The seven exact GR-08g rows (mirroring the tracked seed file).

    ZERO closure rows: the blind-spot sweep found every mutating DAO call
    in the file is an abstract Room-annotated method already covered by a
    finding (BankStatementImportRunDao, BankStatementImportItemDao and
    ScannedReceiptDao are fully abstract interfaces; the two body-carrying
    @Transaction convenience methods REACHED from the file --
    ExpenseDao.findDuplicateIdCurrencyAware and
    PendingReviewDao.getPendingDuplicateCandidateInRangeTypeAware -- are
    strictly read-only composites, and PendingReviewDao's MUTATING
    convenience methods are not called from this file).
    """
    return [
        _gr08g_seed_row(
            "bankStatementImportRunDao", BANK_STATEMENT_IMPORT_RUN_DAO,
            "insert",
        ),
        _gr08g_seed_row(
            "bankStatementImportRunDao", BANK_STATEMENT_IMPORT_RUN_DAO,
            "attachReceipt",
        ),
        _gr08g_seed_row(
            "bankStatementImportRunDao", BANK_STATEMENT_IMPORT_RUN_DAO,
            "finalize",
        ),
        _gr08g_seed_row(
            "bankStatementImportRunDao", BANK_STATEMENT_IMPORT_RUN_DAO,
            "updatePdfPartial",
        ),
        _gr08g_seed_row(
            "bankStatementImportItemDao", BANK_STATEMENT_IMPORT_ITEM_DAO,
            "insert",
        ),
        _gr08g_seed_row(
            "pendingReviewDao", PENDING_REVIEW_DAO_GR08D, "insert",
        ),
        _gr08g_seed_row(
            "scannedReceiptDao", SCANNED_RECEIPT_DAO, "update",
        ),
    ]


def test_real_tracked_gr08g_seed_file_loads_with_exactly_seven_rows():
    entries = _load_seed_entries(GR08G_SEED_FILE)
    assert len(entries) == 7
    methods = sorted(entry.method for entry in entries)
    assert methods == ["processBankStatement"] * 7
    for entry in entries:
        assert entry.path == BANK_STATEMENT_PROCESSOR_KT
        assert entry.owner_fqcn == BANK_STATEMENT_PROCESSOR_FQCN
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.parameter_types == STATEMENT_URI_PARAMS
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08G"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)
    # ZERO closure rows: every accessor is a plain constructor property and
    # every operation is the exact abstract Room method the scanner
    # reported -- no convenience-method rows exist in this batch.
    assert all(
        entry.dao_accessor
        in {
            "bankStatementImportRunDao",
            "bankStatementImportItemDao",
            "pendingReviewDao",
            "scannedReceiptDao",
        }
        for entry in entries
    )
    # The run-ledger rows: exactly four distinct operations behind
    # bankStatementImportRunDao.
    run_operations = sorted(
        entry.operation
        for entry in entries
        if entry.dao_accessor == "bankStatementImportRunDao"
    )
    assert run_operations == [
        "attachReceipt",
        "finalize",
        "insert",
        "updatePdfPartial",
    ]


def _gr08g_policy_entries(tmp_path, rows):
    entries = []
    for position, row in enumerate(rows):
        entry, errors = build_policy_entry(row, position)
        assert entry is not None and not errors, (
            "GR-08g fixture row must be schema-valid: %s" % (errors,)
        )
        entries.append(entry)
    return entries


def _assert_gr08g_exact_match(tmp_path, rows, select_accessor,
                              select_operation, **overrides):
    """The exact GR-08g row identity matches; mutants never do.

    Target selection is fixed by ``(select_accessor, select_operation)``
    (every row shares the processBankStatement callable identity);
    ``overrides`` perturb exactly one identity field of the match query for
    the near-miss assertions.
    """
    entries = _gr08g_policy_entries(tmp_path, rows)
    target = [
        entry
        for entry in entries
        if entry.dao_accessor == select_accessor
        and entry.operation == select_operation
    ][0]
    kwargs = dict(
        path=target.path,
        owner_fqcn=target.owner_fqcn,
        kind=target.kind,
        method=target.method,
        receiver=target.receiver,
        parameter_types=target.parameter_types,
        dao_accessor=target.dao_accessor,
        dao_fqcn=target.dao_fqcn,
        operation=target.operation,
    )
    kwargs.update(overrides)
    return match_mutation(target, **kwargs)


def test_gr08g_exact_identity_matches(tmp_path):
    rows = _gr08g_seed_rows()
    assert (
        _assert_gr08g_exact_match(
            tmp_path, rows, "bankStatementImportRunDao", "finalize",
        )
        is True
    )


def test_gr08g_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08g_seed_rows()
    assert (
        _assert_gr08g_exact_match(
            tmp_path, rows, "bankStatementImportRunDao", "finalize",
            parameter_types=("android.net.Uri", "Long"),
        )
        is False
    )


def test_gr08g_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08g_seed_rows()
    assert (
        _assert_gr08g_exact_match(
            tmp_path, rows, "bankStatementImportRunDao", "finalize",
            owner_fqcn="com.example.OtherProcessor",
        )
        is False
    )


def test_gr08g_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08g_seed_rows()
    assert (
        _assert_gr08g_exact_match(
            tmp_path, rows, "bankStatementImportRunDao", "finalize",
            dao_accessor="bankStatementImportItemDao",
            dao_fqcn=BANK_STATEMENT_IMPORT_ITEM_DAO,
        )
        is False
    )


def test_gr08g_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08g_seed_rows()
    assert (
        _assert_gr08g_exact_match(
            tmp_path, rows, "bankStatementImportRunDao", "finalize",
            operation="markStaleFailed",
        )
        is False
    )


def test_gr08g_run_ledger_rows_near_misses_stay_unauthorized(tmp_path):
    """The four bankStatementImportRunDao rows are exact per operation.

    All four rows share the processBankStatement callable identity and the
    run-ledger DAO; a swapped operation, a swapped accessor, or a wrong
    callable name stays unauthorized.
    """
    rows = _gr08g_seed_rows()
    base_kwargs = dict(
        select_accessor="bankStatementImportRunDao",
        select_operation="insert",
    )
    assert _assert_gr08g_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong operation: the sibling attachReceipt spelling behind the same
    # DAO.
    assert (
        _assert_gr08g_exact_match(
            tmp_path, rows, **dict(base_kwargs, operation="attachReceipt")
        )
        is False
    )
    # Wrong accessor: the per-item ledger DAO never matches the run-ledger
    # identity.
    assert (
        _assert_gr08g_exact_match(
            tmp_path,
            rows,
            **dict(
                base_kwargs,
                dao_accessor="bankStatementImportItemDao",
                dao_fqcn=BANK_STATEMENT_IMPORT_ITEM_DAO,
            ),
        )
        is False
    )
    # Wrong callable: a copied processor class never matches.
    assert (
        _assert_gr08g_exact_match(
            tmp_path,
            rows,
            **dict(base_kwargs, method="processStatementCopy"),
        )
        is False
    )


def test_gr08g_receipt_status_row_near_misses_stay_unauthorized(tmp_path):
    """The scannedReceiptDao.update row is exact too.

    The receipt status transition shares the processBankStatement callable
    identity with the six ledger/review rows; a swapped DAO, a swapped
    operation (e.g. the abstract insert the processor never calls), or a
    wrong overload stays unauthorized.
    """
    rows = _gr08g_seed_rows()
    base_kwargs = dict(
        select_accessor="scannedReceiptDao",
        select_operation="update",
    )
    assert _assert_gr08g_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong operation: the ScannedReceiptDao.insert spelling (the processor
    # delegates receipt creation to ReceiptRecordWriter, it never calls
    # scannedReceiptDao.insert directly).
    assert (
        _assert_gr08g_exact_match(
            tmp_path, rows, **dict(base_kwargs, operation="insert")
        )
        is False
    )
    # Wrong DAO identity behind the accessor spelling.
    assert (
        _assert_gr08g_exact_match(
            tmp_path,
            rows,
            **dict(base_kwargs, dao_fqcn=BANK_STATEMENT_IMPORT_RUN_DAO),
        )
        is False
    )
    # Wrong overload: a two-parameter shape.
    assert (
        _assert_gr08g_exact_match(
            tmp_path,
            rows,
            **dict(base_kwargs, parameter_types=("android.net.Uri", "Long")),
        )
        is False
    )


# ── (13) GR-08h rows: tracked seed file + concatenation + NEAR-MISS ───────────
#
# GR-08h authorizes the ReceiptMatchLifecycleService.kt receipt-lifecycle
# callables (13 findings / 13 unique fingerprints, within the 25-fingerprint
# batch cap so NO split was required).  The migration CLI accepts a SINGLE
# --seed-rows value, so every generation run consumes the COMBINED document
# GR-08-seeds.yml; these tests pin that the combined document stays the
# exact concatenation of the TEN reviewed batch seed files, and that the
# GR-08h rows authorize EXACTLY their callable identity + DAO + operation
# (wrong overload, wrong owner, wrong DAO, and wrong operation stay
# unauthorized).

GR08H_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08h-seed.yml"

RECEIPT_MATCH_LIFECYCLE_SERVICE_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/receipt/"
    "lifecycle/ReceiptMatchLifecycleService.kt"
)
RECEIPT_MATCH_LIFECYCLE_SERVICE_FQCN = (
    "com.yourname.expensetracker.domain.receipt.lifecycle."
    "ReceiptMatchLifecycleService"
)
RECEIPT_EVENT_DAO_GR08H = (
    "com.yourname.expensetracker.data.database.dao.ReceiptEventDao"
)
# SCANNED_RECEIPT_DAO is already defined by the GR-08g section above.

SAVE_MATCH_SUGGESTION_PARAMS = ("Long", "Long", "Double")
SINGLE_RECEIPT_ID_PARAMS = ("Long",)
RECORD_MATCH_ATTEMPTED_PARAMS = ("Long", "Int")
RECORD_MATCH_SKIPPED_PARAMS = ("Long", "String?")
RECORD_AUTO_MATCH_LINK_FAILED_PARAMS = ("Long", "Long?", "String?", "String?")
RECORD_NOTIFICATION_SUPPRESSED_PARAMS = ("Long", "Long?", "String", "String?")


def _gr08h_seed_row(method, parameter_types, dao_accessor, dao_fqcn, operation):
    """One exact GR-08h-shaped v2 seed row mapping."""
    return {
        "path": RECEIPT_MATCH_LIFECYCLE_SERVICE_KT,
        "ownerFqcn": RECEIPT_MATCH_LIFECYCLE_SERVICE_FQCN,
        "kind": "function",
        "method": method,
        "receiver": None,
        "parameterTypes": list(parameter_types),
        "daoAccessor": dao_accessor,
        "daoFqcn": dao_fqcn,
        "operation": operation,
        "barrierMode": "helper",
        "reason": "GR-08h EXACT_POLICY test row",
        "owner": "@panospao7",
        "linkedIssue": "MIT-DB-08H",
    }


def _gr08h_seed_rows():
    """The thirteen exact GR-08h rows (mirroring the tracked seed file).

    ZERO closure rows: the blind-spot sweep found every mutating DAO call
    in the file is an abstract Room-annotated method already covered by a
    finding (ReceiptEventDao is a fully abstract interface with exactly two
    methods and ScannedReceiptDao is likewise fully abstract -- NEITHER
    carries a body-carrying @Transaction convenience method at all; the
    only other DAO-accessor calls are the nine read-only
    scannedReceiptDao.getById lookups, and the database.withTransaction
    calls are the androidx.room.withTransaction extension, not DAO
    accessors).
    """
    return [
        # Match-state transitions (scannedReceiptDao.update, 4 callables).
        _gr08h_seed_row(
            "saveMatchSuggestion", SAVE_MATCH_SUGGESTION_PARAMS,
            "scannedReceiptDao", SCANNED_RECEIPT_DAO, "update",
        ),
        _gr08h_seed_row(
            "approveMatchSuggestion", SINGLE_RECEIPT_ID_PARAMS,
            "scannedReceiptDao", SCANNED_RECEIPT_DAO, "update",
        ),
        _gr08h_seed_row(
            "rejectAllSuggestions", SINGLE_RECEIPT_ID_PARAMS,
            "scannedReceiptDao", SCANNED_RECEIPT_DAO, "update",
        ),
        _gr08h_seed_row(
            "clearMatchForReceipt", SINGLE_RECEIPT_ID_PARAMS,
            "scannedReceiptDao", SCANNED_RECEIPT_DAO, "update",
        ),
        # Lifecycle events (receiptEventDao.insert, 9 callables).
        _gr08h_seed_row(
            "saveMatchSuggestion", SAVE_MATCH_SUGGESTION_PARAMS,
            "receiptEventDao", RECEIPT_EVENT_DAO_GR08H, "insert",
        ),
        _gr08h_seed_row(
            "approveMatchSuggestion", SINGLE_RECEIPT_ID_PARAMS,
            "receiptEventDao", RECEIPT_EVENT_DAO_GR08H, "insert",
        ),
        _gr08h_seed_row(
            "rejectAllSuggestions", SINGLE_RECEIPT_ID_PARAMS,
            "receiptEventDao", RECEIPT_EVENT_DAO_GR08H, "insert",
        ),
        _gr08h_seed_row(
            "clearMatchForReceipt", SINGLE_RECEIPT_ID_PARAMS,
            "receiptEventDao", RECEIPT_EVENT_DAO_GR08H, "insert",
        ),
        _gr08h_seed_row(
            "recordMatchAttempted", RECORD_MATCH_ATTEMPTED_PARAMS,
            "receiptEventDao", RECEIPT_EVENT_DAO_GR08H, "insert",
        ),
        _gr08h_seed_row(
            "recordMatchNotFound", SINGLE_RECEIPT_ID_PARAMS,
            "receiptEventDao", RECEIPT_EVENT_DAO_GR08H, "insert",
        ),
        _gr08h_seed_row(
            "recordMatchSkippedDocumentType", RECORD_MATCH_SKIPPED_PARAMS,
            "receiptEventDao", RECEIPT_EVENT_DAO_GR08H, "insert",
        ),
        _gr08h_seed_row(
            "recordAutoMatchLinkFailed",
            RECORD_AUTO_MATCH_LINK_FAILED_PARAMS,
            "receiptEventDao", RECEIPT_EVENT_DAO_GR08H, "insert",
        ),
        _gr08h_seed_row(
            "recordNotificationSuppressed",
            RECORD_NOTIFICATION_SUPPRESSED_PARAMS,
            "receiptEventDao", RECEIPT_EVENT_DAO_GR08H, "insert",
        ),
    ]


def test_real_tracked_gr08h_seed_file_loads_with_exactly_thirteen_rows():
    entries = _load_seed_entries(GR08H_SEED_FILE)
    assert len(entries) == 13
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted([
        "saveMatchSuggestion",
        "saveMatchSuggestion",
        "approveMatchSuggestion",
        "approveMatchSuggestion",
        "rejectAllSuggestions",
        "rejectAllSuggestions",
        "clearMatchForReceipt",
        "clearMatchForReceipt",
        "recordMatchAttempted",
        "recordMatchNotFound",
        "recordMatchSkippedDocumentType",
        "recordAutoMatchLinkFailed",
        "recordNotificationSuppressed",
    ])
    for entry in entries:
        assert entry.path == RECEIPT_MATCH_LIFECYCLE_SERVICE_KT
        assert entry.owner_fqcn == RECEIPT_MATCH_LIFECYCLE_SERVICE_FQCN
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08H"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)
    # ZERO closure rows: every accessor is a plain constructor property and
    # every operation is the exact abstract Room method the scanner
    # reported -- no convenience-method rows exist in this batch.
    assert all(
        entry.dao_accessor in {"scannedReceiptDao", "receiptEventDao"}
        for entry in entries
    )
    # The match-state rows: exactly four scannedReceiptDao.update rows, one
    # per match-mutation callable.
    update_methods = sorted(
        entry.method
        for entry in entries
        if entry.dao_accessor == "scannedReceiptDao"
    )
    assert update_methods == [
        "approveMatchSuggestion",
        "clearMatchForReceipt",
        "rejectAllSuggestions",
        "saveMatchSuggestion",
    ]
    # The event rows: exactly nine receiptEventDao.insert rows, one per
    # event-writing callable.
    insert_methods = sorted(
        entry.method
        for entry in entries
        if entry.dao_accessor == "receiptEventDao"
    )
    assert insert_methods == [
        "approveMatchSuggestion",
        "clearMatchForReceipt",
        "recordAutoMatchLinkFailed",
        "recordMatchAttempted",
        "recordMatchNotFound",
        "recordMatchSkippedDocumentType",
        "recordNotificationSuppressed",
        "rejectAllSuggestions",
        "saveMatchSuggestion",
    ]


def _gr08h_policy_entries(tmp_path, rows):
    entries = []
    for position, row in enumerate(rows):
        entry, errors = build_policy_entry(row, position)
        assert entry is not None and not errors, (
            "GR-08h fixture row must be schema-valid: %s" % (errors,)
        )
        entries.append(entry)
    return entries


def _assert_gr08h_exact_match(tmp_path, rows, select_method, select_accessor,
                              select_operation, **overrides):
    """The exact GR-08h row identity matches; mutants never do.

    Target selection is fixed by ``(select_method, select_accessor,
    select_operation)`` (unlike GR-08g, the batch spans NINE callables, so
    the callable name is part of the selection key); ``overrides`` perturb
    exactly one identity field of the match query for the near-miss
    assertions.
    """
    entries = _gr08h_policy_entries(tmp_path, rows)
    target = [
        entry
        for entry in entries
        if entry.method == select_method
        and entry.dao_accessor == select_accessor
        and entry.operation == select_operation
    ][0]
    kwargs = dict(
        path=target.path,
        owner_fqcn=target.owner_fqcn,
        kind=target.kind,
        method=target.method,
        receiver=target.receiver,
        parameter_types=target.parameter_types,
        dao_accessor=target.dao_accessor,
        dao_fqcn=target.dao_fqcn,
        operation=target.operation,
    )
    kwargs.update(overrides)
    return match_mutation(target, **kwargs)


def test_gr08h_exact_identity_matches(tmp_path):
    rows = _gr08h_seed_rows()
    assert (
        _assert_gr08h_exact_match(
            tmp_path, rows, "saveMatchSuggestion", "scannedReceiptDao",
            "update",
        )
        is True
    )
    assert (
        _assert_gr08h_exact_match(
            tmp_path, rows, "recordNotificationSuppressed",
            "receiptEventDao", "insert",
        )
        is True
    )


def test_gr08h_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08h_seed_rows()
    assert (
        _assert_gr08h_exact_match(
            tmp_path, rows, "saveMatchSuggestion", "scannedReceiptDao",
            "update", parameter_types=("Long", "Long"),
        )
        is False
    )


def test_gr08h_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08h_seed_rows()
    assert (
        _assert_gr08h_exact_match(
            tmp_path, rows, "saveMatchSuggestion", "scannedReceiptDao",
            "update",
            owner_fqcn="com.example.OtherMatchService",
        )
        is False
    )


def test_gr08h_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08h_seed_rows()
    assert (
        _assert_gr08h_exact_match(
            tmp_path, rows, "saveMatchSuggestion", "scannedReceiptDao",
            "update",
            dao_accessor="receiptEventDao",
            dao_fqcn=RECEIPT_EVENT_DAO_GR08H,
        )
        is False
    )


def test_gr08h_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08h_seed_rows()
    assert (
        _assert_gr08h_exact_match(
            tmp_path, rows, "saveMatchSuggestion", "scannedReceiptDao",
            "update", operation="insert",
        )
        is False
    )


def test_gr08h_match_state_rows_near_misses_stay_unauthorized(tmp_path):
    """The four scannedReceiptDao.update rows are exact per callable.

    All four rows share the scannedReceiptDao.update DAO identity but
    differ in callable identity (and saveMatchSuggestion differs in
    overload too); a swapped callable, a swapped overload, or the plain
    insert spelling stays unauthorized.
    """
    rows = _gr08h_seed_rows()
    base_kwargs = dict(
        select_method="approveMatchSuggestion",
        select_accessor="scannedReceiptDao",
        select_operation="update",
    )
    assert _assert_gr08h_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong callable: the sibling rejectAllSuggestions row never matches
    # the approveMatchSuggestion identity.
    assert (
        _assert_gr08h_exact_match(
            tmp_path, rows, **dict(base_kwargs, method="rejectAllSuggestions")
        )
        is False
    )
    # Wrong overload: the three-parameter saveMatchSuggestion shape.
    assert (
        _assert_gr08h_exact_match(
            tmp_path,
            rows,
            **dict(base_kwargs, parameter_types=("Long", "Long", "Double")),
        )
        is False
    )
    # Wrong operation: the ReceiptEventDao.insert spelling behind the other
    # accessor.
    assert (
        _assert_gr08h_exact_match(
            tmp_path,
            rows,
            **dict(
                base_kwargs,
                dao_accessor="receiptEventDao",
                dao_fqcn=RECEIPT_EVENT_DAO_GR08H,
                operation="insert",
            ),
        )
        is False
    )


def test_gr08h_diagnostics_rows_near_misses_stay_unauthorized(tmp_path):
    """The five diagnostics receiptEventDao.insert rows are exact per
    callable AND per overload.

    recordMatchAttempted / recordMatchSkippedDocumentType /
    recordAutoMatchLinkFailed / recordNotificationSuppressed all write the
    same receiptEventDao.insert operation; each row authorizes EXACTLY its
    own callable identity + parameter shape, so a sibling callable's shape
    or a perturbed overload stays unauthorized.
    """
    rows = _gr08h_seed_rows()
    base_kwargs = dict(
        select_method="recordMatchAttempted",
        select_accessor="receiptEventDao",
        select_operation="insert",
    )
    assert _assert_gr08h_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong callable: the sibling recordMatchNotFound row never matches the
    # recordMatchAttempted identity.
    assert (
        _assert_gr08h_exact_match(
            tmp_path, rows, **dict(base_kwargs, method="recordMatchNotFound")
        )
        is False
    )
    # Wrong overload: the single-parameter recordMatchNotFound shape.
    assert (
        _assert_gr08h_exact_match(
            tmp_path, rows, **dict(base_kwargs, parameter_types=("Long",))
        )
        is False
    )
    # Wrong overload: the four-parameter recordAutoMatchLinkFailed shape.
    assert (
        _assert_gr08h_exact_match(
            tmp_path,
            rows,
            **dict(
                base_kwargs,
                parameter_types=("Long", "Long?", "String?", "String?"),
            ),
        )
        is False
    )
    # Wrong DAO identity behind the accessor spelling.
    assert (
        _assert_gr08h_exact_match(
            tmp_path,
            rows,
            **dict(base_kwargs, dao_fqcn=SCANNED_RECEIPT_DAO),
        )
        is False
    )


# ── (14) GR-08i1/i2/i3 rows: tracked seed files + concatenation + NEAR-MISS ───
#
# GR-08i authorizes THREE files (GroupTransactionCoordinator.kt 14 findings /
# 14 unique fingerprints, RecurringOccurrenceMaterializer.kt 15 findings /
# 6 unique fingerprints, NotificationIntakeWorker.kt 15 findings / 7 unique
# fingerprints).  The combined batch carries 27 unique fingerprints > the
# 25-fingerprint batch cap, so the batch was SPLIT one part per file per the
# GR-08c/GR-08e precedent.  The migration CLI accepts a SINGLE --seed-rows
# value, so every generation run consumes the COMBINED document
# GR-08-seeds.yml; these tests pin that the combined document stays the
# exact concatenation of the THIRTEEN reviewed batch seed files, and that
# the GR-08i rows authorize EXACTLY their callable identity + DAO +
# operation (wrong overload, wrong owner, wrong DAO, and wrong operation
# stay unauthorized).

GR08I1_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08i1-seed.yml"
GR08I2_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08i2-seed.yml"
GR08I3_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08i3-seed.yml"

GROUP_TX_COORDINATOR_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/database/"
    "GroupTransactionCoordinator.kt"
)
GROUP_TX_COORDINATOR_FQCN = (
    "com.yourname.expensetracker.data.database.GroupTransactionCoordinator"
)
EXPENSE_GROUP_DAO = (
    "com.yourname.expensetracker.data.database.dao.ExpenseGroupDao"
)
GROUP_MEMBER_DAO = (
    "com.yourname.expensetracker.data.database.dao.GroupMemberDao"
)
# GROUP_EXPENSE_DAO_FQCN: GroupExpenseDao is already referenced by the
# GR-08g section via BANK_STATEMENT_IMPORT_* constants; spell it explicitly
# here for the GR-08i1 rows.
GROUP_EXPENSE_DAO_FQCN = (
    "com.yourname.expensetracker.data.database.dao.GroupExpenseDao"
)
EXPENSE_DAO_GR08I = "com.yourname.expensetracker.data.database.dao.ExpenseDao"
EXPENSE_GROUP_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.ExpenseGroup"
)
GROUP_MEMBER_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.GroupMember"
)
SPLIT_TYPE_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.SplitType"
)
TRANSACTION_TYPE_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.TransactionType"
)
GROUP_MEMBER_LIST = "List<" + GROUP_MEMBER_ENTITY + ">"
ON_INSIDE_TX_MEMBER = "(Long) -> Unit"
ON_INSIDE_TX_UNIT = "() -> Unit"

CREATE_GROUP_WITH_MEMBERS_PARAMS = (
    "String",
    "String?",
    "String",
    GROUP_MEMBER_LIST,
    ON_INSIDE_TX_MEMBER,
)
ADD_MEMBER_TO_GROUP_PARAMS = (
    "Long",
    "String",
    "String?",
    "Boolean",
    ON_INSIDE_TX_MEMBER,
)
ADD_EXPENSE_TO_GROUP_PARAMS = (
    "Long",
    "String",
    "Double",
    "Long",
    "String?",
    SPLIT_TYPE_ENTITY,
    "String?",
    "Long",
    "String?",
    ON_INSIDE_TX_MEMBER,
)
ADD_EXPENSE_WITH_LINK_PARAMS = (
    "Long",
    "Long",
    "String",
    "Double",
    "Long",
    "String?",
    SPLIT_TYPE_ENTITY,
    "String?",
    "Long",
    "String?",
)
CREATE_SYSTEM_EXPENSE_LINK_PARAMS = (
    "Long",
    "String",
    "Double",
    "Long",
    "String",
    SPLIT_TYPE_ENTITY,
    "String?",
    "Long",
    TRANSACTION_TYPE_ENTITY,
    "String?",
    "String?",
)
CREATE_GROUP_ATOMIC_PARAMS = (EXPENSE_GROUP_ENTITY, GROUP_MEMBER_LIST)
GROUP_ID_ONLY_PARAMS = ("Long",)
GROUP_ID_WITH_CALLBACK_PARAMS = ("Long", ON_INSIDE_TX_UNIT)

MATERIALIZER_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/recurring/"
    "lifecycle/RecurringOccurrenceMaterializer.kt"
)
MATERIALIZER_FQCN = (
    "com.yourname.expensetracker.domain.recurring.lifecycle."
    "RecurringOccurrenceMaterializer"
)
RECURRING_OCCURRENCE_DAO = (
    "com.yourname.expensetracker.data.database.dao.RecurringOccurrenceDao"
)
# LIFECYCLE_EVENT_DAO / PLANNED_EXPENSE_DAO / REMINDER_DELIVERY_DAO are
# already defined by the GR-08c section above.
RESOLVED_OCCURRENCE_LIST = (
    "List<com.yourname.expensetracker.domain.recurring."
    "OccurrenceConflictResolver.ResolvedOccurrence>"
)
MATERIALIZATION_OPTIONS = (
    MATERIALIZER_FQCN + ".MaterializationOptions"
)
MATERIALIZE_PARAMS = (RESOLVED_OCCURRENCE_LIST, MATERIALIZATION_OPTIONS)

INTAKE_WORKER_KT = (
    "app/src/main/java/com/yourname/expensetracker/worker/"
    "NotificationIntakeWorker.kt"
)
INTAKE_WORKER_FQCN = (
    "com.yourname.expensetracker.worker.NotificationIntakeWorker"
)
NOTIFICATION_INTAKE_DAO = (
    "com.yourname.expensetracker.data.database.dao.NotificationIntakeDao"
)
WORKER_RUN_CONTEXT_NULLABLE = (
    "com.yourname.expensetracker.domain.workers.WorkerRunContext?"
)
DO_WORK_PARAMS: tuple = ()
PURGE_PAYLOAD_PARAMS = ("Long", "Long", WORKER_RUN_CONTEXT_NULLABLE)
INTAKE_ID_PARAMS = ("Long",)


def _gr08i_seed_row(path, owner_fqcn, method, parameter_types, dao_accessor,
                    dao_fqcn, operation, barrier_mode):
    """One exact GR-08i-shaped v2 seed row mapping."""
    return {
        "path": path,
        "ownerFqcn": owner_fqcn,
        "kind": "function",
        "method": method,
        "receiver": None,
        "parameterTypes": list(parameter_types),
        "daoAccessor": dao_accessor,
        "daoFqcn": dao_fqcn,
        "operation": operation,
        "barrierMode": barrier_mode,
        "reason": "GR-08i EXACT_POLICY test row",
        "owner": "@panospao7",
        "linkedIssue": "MIT-DB-08I",
    }


def _gr08i1_seed_rows():
    """The fourteen exact GR-08i1 rows (mirroring the tracked seed file).

    GroupTransactionCoordinator.kt; ZERO closure rows: every mutating DAO
    call in the file is an abstract Room-annotated method already covered by
    a finding (the two body-carrying @Transaction convenience methods in the
    touched DAOs -- ExpenseGroupDao.insertGroupWithMembers and
    GroupMemberDao.setCurrentUser -- are NOT called from this file; the
    plan prose's 15th site, addExpenseToGroupAtomic's groupExpenseDao.insert,
    is already authorized by the active policy's legacy MIT-003 direct row).
    """
    rows = []
    for accessor, dao, operation in (
        ("groupDao", EXPENSE_GROUP_DAO, "insert"),
        ("memberDao", GROUP_MEMBER_DAO, "insertAll"),
    ):
        rows.append(
            _gr08i_seed_row(
                GROUP_TX_COORDINATOR_KT, GROUP_TX_COORDINATOR_FQCN,
                "createGroupWithMembers", CREATE_GROUP_WITH_MEMBERS_PARAMS,
                accessor, dao, operation, "helper",
            )
        )
    rows.append(
        _gr08i_seed_row(
            GROUP_TX_COORDINATOR_KT, GROUP_TX_COORDINATOR_FQCN,
            "addMemberToGroup", ADD_MEMBER_TO_GROUP_PARAMS,
            "memberDao", GROUP_MEMBER_DAO, "insert", "helper",
        )
    )
    for method, params in (
        ("addExpenseToGroup", ADD_EXPENSE_TO_GROUP_PARAMS),
        ("addExpenseWithLink", ADD_EXPENSE_WITH_LINK_PARAMS),
        (
            "createSystemExpenseAndLinkToGroup",
            CREATE_SYSTEM_EXPENSE_LINK_PARAMS,
        ),
    ):
        rows.append(
            _gr08i_seed_row(
                GROUP_TX_COORDINATOR_KT, GROUP_TX_COORDINATOR_FQCN,
                method, params,
                "groupExpenseDao", GROUP_EXPENSE_DAO_FQCN, "insert", "helper",
            )
        )
    for method, params in (
        ("deleteGroup", GROUP_ID_ONLY_PARAMS),
        ("archiveGroup", GROUP_ID_WITH_CALLBACK_PARAMS),
    ):
        rows.append(
            _gr08i_seed_row(
                GROUP_TX_COORDINATOR_KT, GROUP_TX_COORDINATOR_FQCN,
                method, params,
                "groupDao", EXPENSE_GROUP_DAO, "archiveGroup", "helper",
            )
        )
    for accessor, dao, operation in (
        ("groupDao", EXPENSE_GROUP_DAO, "insert"),
        ("memberDao", GROUP_MEMBER_DAO, "insertAll"),
    ):
        rows.append(
            _gr08i_seed_row(
                GROUP_TX_COORDINATOR_KT, GROUP_TX_COORDINATOR_FQCN,
                "createGroupWithMembersAtomic", CREATE_GROUP_ATOMIC_PARAMS,
                accessor, dao, operation, "helper",
            )
        )
    for accessor, dao, operation in (
        ("groupExpenseDao", GROUP_EXPENSE_DAO_FQCN, "deleteAllForGroup"),
        ("memberDao", GROUP_MEMBER_DAO, "deleteAllForGroup"),
        ("groupDao", EXPENSE_GROUP_DAO, "delete"),
        ("expenseDao", EXPENSE_DAO_GR08I, "clearSharedExpenseFlags"),
    ):
        rows.append(
            _gr08i_seed_row(
                GROUP_TX_COORDINATOR_KT, GROUP_TX_COORDINATOR_FQCN,
                "deleteGroupAtomic", GROUP_ID_WITH_CALLBACK_PARAMS,
                accessor, dao, operation, "helper",
            )
        )
    return rows


def _gr08i2_seed_rows():
    """The six exact GR-08i2 rows (mirroring the tracked seed file).

    RecurringOccurrenceMaterializer.kt; ZERO closure rows: all four touched
    DAOs are fully abstract interfaces with ZERO @Transaction methods.  All
    15 findings collapse onto these 6 fingerprints inside the single
    mutating callable materializeInCurrentTransaction.
    """
    rows = []
    for accessor, dao, operation in (
        ("occurrenceDao", RECURRING_OCCURRENCE_DAO, "insert"),
        ("occurrenceDao", RECURRING_OCCURRENCE_DAO, "update"),
        ("lifecycleEventDao", LIFECYCLE_EVENT_DAO, "insert"),
        ("plannedExpenseDao", PLANNED_EXPENSE_DAO, "fulfillByOccurrenceKey"),
        ("reminderDeliveryDao", REMINDER_DELIVERY_DAO,
         "suppressByOccurrenceId"),
        ("reminderDeliveryDao", REMINDER_DELIVERY_DAO, "insert"),
    ):
        rows.append(
            _gr08i_seed_row(
                MATERIALIZER_KT, MATERIALIZER_FQCN,
                "materializeInCurrentTransaction", MATERIALIZE_PARAMS,
                accessor, dao, operation, "helper",
            )
        )
    return rows


def _gr08i3_seed_rows():
    """The seven exact GR-08i3 rows (mirroring the tracked seed file).

    NotificationIntakeWorker.kt; ZERO closure rows: NotificationIntakeDao is
    a fully abstract interface.  Worker-guard verification was performed in
    source before EXACT_POLICY disposition (doWork's entire mutating body
    runs inside WorkerExecutionGuard.runGuardedWithContext;
    runPrivacyCleanupGuarded wraps its own body in a second guard with
    requiredCapabilities = emptyList(); purgePayloadBestEffort is invoked
    only from inside the doWork guard lambda), so every row is
    `workerMediated` -- the established mode of the active policy's worker
    rows.
    """
    rows = []
    for operation in (
        "claimForProcessing",
        "markFinalFailure",
        "markPrivacyDeniedAndPurgeAllPayload",
        "markRetryableFailure",
        "markTerminal",
    ):
        rows.append(
            _gr08i_seed_row(
                INTAKE_WORKER_KT, INTAKE_WORKER_FQCN,
                "doWork", DO_WORK_PARAMS,
                "intakeDao", NOTIFICATION_INTAKE_DAO, operation,
                "workerMediated",
            )
        )
    rows.append(
        _gr08i_seed_row(
            INTAKE_WORKER_KT, INTAKE_WORKER_FQCN,
            "purgePayloadBestEffort", PURGE_PAYLOAD_PARAMS,
            "intakeDao", NOTIFICATION_INTAKE_DAO, "purgeAllPayload",
            "workerMediated",
        )
    )
    rows.append(
        _gr08i_seed_row(
            INTAKE_WORKER_KT, INTAKE_WORKER_FQCN,
            "runPrivacyCleanupGuarded", INTAKE_ID_PARAMS,
            "intakeDao", NOTIFICATION_INTAKE_DAO,
            "markPrivacyDeniedAndPurgeAllPayload", "workerMediated",
        )
    )
    return rows


def test_real_tracked_gr08i1_seed_file_loads_with_exactly_fourteen_rows():
    entries = _load_seed_entries(GR08I1_SEED_FILE)
    assert len(entries) == 14
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted(
        ["createGroupWithMembers"] * 2
        + ["addMemberToGroup"]
        + ["addExpenseToGroup", "addExpenseWithLink",
           "createSystemExpenseAndLinkToGroup"]
        + ["deleteGroup", "archiveGroup"]
        + ["createGroupWithMembersAtomic"] * 2
        + ["deleteGroupAtomic"] * 4
    )
    for entry in entries:
        assert entry.path == GROUP_TX_COORDINATOR_KT
        assert entry.owner_fqcn == GROUP_TX_COORDINATOR_FQCN
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08I"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)
    # ZERO closure rows: every accessor is a plain constructor property and
    # every operation is the exact abstract Room method the scanner
    # reported -- no convenience-method rows exist in this batch.
    assert all(
        entry.dao_accessor
        in {"groupDao", "memberDao", "groupExpenseDao", "expenseDao"}
        for entry in entries
    )
    # The hard-delete cascade rows: exactly four deleteGroupAtomic rows.
    cascade = sorted(
        (entry.dao_accessor, entry.operation)
        for entry in entries if entry.method == "deleteGroupAtomic"
    )
    assert cascade == sorted([
        ("groupExpenseDao", "deleteAllForGroup"),
        ("memberDao", "deleteAllForGroup"),
        ("groupDao", "delete"),
        ("expenseDao", "clearSharedExpenseFlags"),
    ])


def test_real_tracked_gr08i2_seed_file_loads_with_exactly_six_rows():
    entries = _load_seed_entries(GR08I2_SEED_FILE)
    assert len(entries) == 6
    methods = sorted(entry.method for entry in entries)
    assert methods == ["materializeInCurrentTransaction"] * 6
    for entry in entries:
        assert entry.path == MATERIALIZER_KT
        assert entry.owner_fqcn == MATERIALIZER_FQCN
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.parameter_types == MATERIALIZE_PARAMS
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08I"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)
    # ZERO closure rows: every accessor is a plain constructor property and
    # every operation is the exact abstract Room method the scanner
    # reported -- no convenience-method rows exist in this batch.
    assert all(
        entry.dao_accessor
        in {
            "occurrenceDao",
            "lifecycleEventDao",
            "plannedExpenseDao",
            "reminderDeliveryDao",
        }
        for entry in entries
    )
    # The reminder-delivery rows: exactly two distinct operations behind
    # reminderDeliveryDao.
    delivery_operations = sorted(
        entry.operation
        for entry in entries
        if entry.dao_accessor == "reminderDeliveryDao"
    )
    assert delivery_operations == ["insert", "suppressByOccurrenceId"]


def test_real_tracked_gr08i3_seed_file_loads_with_exactly_seven_rows():
    entries = _load_seed_entries(GR08I3_SEED_FILE)
    assert len(entries) == 7
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted(
        ["doWork"] * 5
        + ["purgePayloadBestEffort", "runPrivacyCleanupGuarded"]
    )
    for entry in entries:
        assert entry.path == INTAKE_WORKER_KT
        assert entry.owner_fqcn == INTAKE_WORKER_FQCN
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.barrier_mode is BarrierMode.WORKER_MEDIATED
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08I"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)
    # ZERO closure rows: the single accessor is the plain intakeDao
    # constructor property and every operation is the exact abstract Room
    # method the scanner reported.
    assert all(entry.dao_accessor == "intakeDao" for entry in entries)
    # The doWork rows: exactly five distinct operations behind intakeDao.
    do_work_operations = sorted(
        entry.operation
        for entry in entries
        if entry.method == "doWork"
    )
    assert do_work_operations == sorted([
        "claimForProcessing",
        "markFinalFailure",
        "markPrivacyDeniedAndPurgeAllPayload",
        "markRetryableFailure",
        "markTerminal",
    ])
    # The privacy-denied purge appears on BOTH callables (doWork mid-run
    # recheck + runPrivacyCleanupGuarded) as distinct tuples.
    privacy_purge = sorted(
        entry.method
        for entry in entries
        if entry.operation == "markPrivacyDeniedAndPurgeAllPayload"
    )
    assert privacy_purge == ["doWork", "runPrivacyCleanupGuarded"]


# NOTE (GR-08j): the GR-08i-era thirteen-file (180 rows) concatenation test
# was REPLACED here, completing the documented supersession chain (each new
# concatenation test replaces its predecessor).  The fifteen-file test below
# is the strict superset: it pins ALL FIFTEEN reviewed batch seed files at
# 212 rows with field-exact equality, so removing the stale predecessor
# weakens nothing.  The GR-08j1 AppDatabase.kt structural half is NOT part
# of the seed concatenation (no DAO identity -> structural exception tuples,
# not seed rows); it is pinned by the structural manifest contract tests in
# scripts/test_verify_db_access_boundaries.py.


def _gr08i_policy_entries(tmp_path, rows):
    entries = []
    for position, row in enumerate(rows):
        entry, errors = build_policy_entry(row, position)
        assert entry is not None and not errors, (
            "GR-08i fixture row must be schema-valid: %s" % (errors,)
        )
        entries.append(entry)
    return entries


def _assert_gr08i_exact_match(tmp_path, rows, select_method, select_accessor,
                              select_operation, **overrides):
    """The exact GR-08i row identity matches; mutants never do.

    Target selection is fixed by ``(select_method, select_accessor,
    select_operation)``; ``overrides`` perturb exactly one identity field of
    the match query for the near-miss assertions.
    """
    entries = _gr08i_policy_entries(tmp_path, rows)
    target = [
        entry
        for entry in entries
        if entry.method == select_method
        and entry.dao_accessor == select_accessor
        and entry.operation == select_operation
    ][0]
    kwargs = dict(
        path=target.path,
        owner_fqcn=target.owner_fqcn,
        kind=target.kind,
        method=target.method,
        receiver=target.receiver,
        parameter_types=target.parameter_types,
        dao_accessor=target.dao_accessor,
        dao_fqcn=target.dao_fqcn,
        operation=target.operation,
    )
    kwargs.update(overrides)
    return match_mutation(target, **kwargs)


def test_gr08i1_exact_identity_matches(tmp_path):
    rows = _gr08i1_seed_rows()
    assert (
        _assert_gr08i_exact_match(
            tmp_path, rows, "createGroupWithMembers", "groupDao", "insert"
        )
        is True
    )
    assert (
        _assert_gr08i_exact_match(
            tmp_path, rows, "deleteGroupAtomic", "expenseDao",
            "clearSharedExpenseFlags",
        )
        is True
    )


def test_gr08i1_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08i1_seed_rows()
    assert (
        _assert_gr08i_exact_match(
            tmp_path, rows, "deleteGroup", "groupDao", "archiveGroup",
            parameter_types=("Long", "() -> Unit"),
        )
        is False
    )


def test_gr08i1_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08i1_seed_rows()
    assert (
        _assert_gr08i_exact_match(
            tmp_path, rows, "addMemberToGroup", "memberDao", "insert",
            owner_fqcn="com.example.OtherCoordinator",
        )
        is False
    )


def test_gr08i1_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08i1_seed_rows()
    assert (
        _assert_gr08i_exact_match(
            tmp_path, rows, "addMemberToGroup", "memberDao", "insert",
            dao_accessor="groupDao",
            dao_fqcn=EXPENSE_GROUP_DAO,
        )
        is False
    )


def test_gr08i1_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08i1_seed_rows()
    assert (
        _assert_gr08i_exact_match(
            tmp_path, rows, "deleteGroup", "groupDao", "archiveGroup",
            operation="delete",
        )
        is False
    )


def test_gr08i1_cascade_rows_near_misses_stay_unauthorized(tmp_path):
    """The four deleteGroupAtomic cascade rows are exact per accessor.

    All four rows share the deleteGroupAtomic callable identity and the
    (Long, () -> Unit) parameter shape but differ in DAO identity; a swapped
    accessor, a swapped operation, or the sibling archiveGroup callable's
    shape stays unauthorized.
    """
    rows = _gr08i1_seed_rows()
    base_kwargs = dict(
        select_method="deleteGroupAtomic",
        select_accessor="groupDao",
        select_operation="delete",
    )
    assert _assert_gr08i_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong operation: the sibling archiveGroup spelling behind the same
    # DAO.
    assert (
        _assert_gr08i_exact_match(
            tmp_path, rows, **dict(base_kwargs, operation="archiveGroup")
        )
        is False
    )
    # Wrong accessor: the member cascade row never matches the group-row
    # delete identity.
    assert (
        _assert_gr08i_exact_match(
            tmp_path,
            rows,
            **dict(
                base_kwargs,
                dao_accessor="memberDao",
                dao_fqcn=GROUP_MEMBER_DAO,
            ),
        )
        is False
    )
    # Wrong callable: the sibling deleteGroup soft-archive row never
    # matches the hard-delete identity.
    assert (
        _assert_gr08i_exact_match(
            tmp_path, rows, **dict(base_kwargs, method="deleteGroup")
        )
        is False
    )
    # Wrong overload: the bare (Long) deleteGroup shape.
    assert (
        _assert_gr08i_exact_match(
            tmp_path,
            rows,
            **dict(base_kwargs, parameter_types=("Long",)),
        )
        is False
    )


def test_gr08i2_exact_identity_matches(tmp_path):
    rows = _gr08i2_seed_rows()
    assert (
        _assert_gr08i_exact_match(
            tmp_path, rows, "materializeInCurrentTransaction",
            "occurrenceDao", "insert",
        )
        is True
    )


def test_gr08i2_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08i2_seed_rows()
    assert (
        _assert_gr08i_exact_match(
            tmp_path, rows, "materializeInCurrentTransaction",
            "occurrenceDao", "insert",
            parameter_types=(RESOLVED_OCCURRENCE_LIST,),
        )
        is False
    )


def test_gr08i2_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08i2_seed_rows()
    assert (
        _assert_gr08i_exact_match(
            tmp_path, rows, "materializeInCurrentTransaction",
            "occurrenceDao", "insert",
            owner_fqcn="com.example.OtherMaterializer",
        )
        is False
    )


def test_gr08i2_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08i2_seed_rows()
    assert (
        _assert_gr08i_exact_match(
            tmp_path, rows, "materializeInCurrentTransaction",
            "occurrenceDao", "insert",
            dao_accessor="reminderDeliveryDao",
            dao_fqcn=REMINDER_DELIVERY_DAO,
        )
        is False
    )


def test_gr08i2_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08i2_seed_rows()
    assert (
        _assert_gr08i_exact_match(
            tmp_path, rows, "materializeInCurrentTransaction",
            "occurrenceDao", "insert", operation="update",
        )
        is False
    )


def test_gr08i2_update_row_near_misses_stay_unauthorized(tmp_path):
    """The occurrenceDao.update row is exact too: siblings never match.

    The downgrade-protected update shares the materializeInCurrentTransaction
    callable identity with the insert row; a swapped operation, a swapped
    accessor, or the plain insert spelling behind the same DAO stays
    unauthorized.
    """
    rows = _gr08i2_seed_rows()
    base_kwargs = dict(
        select_method="materializeInCurrentTransaction",
        select_accessor="occurrenceDao",
        select_operation="update",
    )
    assert _assert_gr08i_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong operation: the insert spelling behind the same DAO.
    assert (
        _assert_gr08i_exact_match(
            tmp_path, rows, **dict(base_kwargs, operation="insert")
        )
        is False
    )
    # Wrong accessor: the lifecycle-event provenance row never matches the
    # occurrence-update identity.
    assert (
        _assert_gr08i_exact_match(
            tmp_path,
            rows,
            **dict(
                base_kwargs,
                dao_accessor="lifecycleEventDao",
                dao_fqcn=LIFECYCLE_EVENT_DAO,
            ),
        )
        is False
    )
    # Wrong callable: a copied materializer class never matches.
    assert (
        _assert_gr08i_exact_match(
            tmp_path, rows, **dict(base_kwargs, method="materialize")
        )
        is False
    )


def test_gr08i3_exact_identity_matches(tmp_path):
    rows = _gr08i3_seed_rows()
    assert (
        _assert_gr08i_exact_match(
            tmp_path, rows, "doWork", "intakeDao", "claimForProcessing"
        )
        is True
    )
    assert (
        _assert_gr08i_exact_match(
            tmp_path, rows, "runPrivacyCleanupGuarded", "intakeDao",
            "markPrivacyDeniedAndPurgeAllPayload",
        )
        is True
    )


def test_gr08i3_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08i3_seed_rows()
    assert (
        _assert_gr08i_exact_match(
            tmp_path, rows, "doWork", "intakeDao", "claimForProcessing",
            parameter_types=("Long",),
        )
        is False
    )


def test_gr08i3_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08i3_seed_rows()
    assert (
        _assert_gr08i_exact_match(
            tmp_path, rows, "doWork", "intakeDao", "claimForProcessing",
            owner_fqcn="com.example.OtherWorker",
        )
        is False
    )


def test_gr08i3_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08i3_seed_rows()
    assert (
        _assert_gr08i_exact_match(
            tmp_path, rows, "doWork", "intakeDao", "claimForProcessing",
            dao_accessor="repository",
            dao_fqcn=RAW_NOTIFICATION_DAO,
        )
        is False
    )


def test_gr08i3_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08i3_seed_rows()
    assert (
        _assert_gr08i_exact_match(
            tmp_path, rows, "doWork", "intakeDao", "claimForProcessing",
            operation="markTerminal",
        )
        is False
    )


def test_gr08i3_terminal_rows_near_misses_stay_unauthorized(tmp_path):
    """The markTerminal row is exact too: siblings never match.

    The five doWork rows share the zero-parameter doWork callable identity
    and the intakeDao accessor but differ in operation; a swapped operation,
    a swapped callable, or the plain insert spelling stays unauthorized.
    """
    rows = _gr08i3_seed_rows()
    base_kwargs = dict(
        select_method="doWork",
        select_accessor="intakeDao",
        select_operation="markTerminal",
    )
    assert _assert_gr08i_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong operation: the sibling markRetryableFailure spelling behind the
    # same DAO and callable.
    assert (
        _assert_gr08i_exact_match(
            tmp_path, rows,
            **dict(base_kwargs, operation="markRetryableFailure")
        )
        is False
    )
    # Wrong callable: the runPrivacyCleanupGuarded privacy-purge row never
    # matches the doWork terminal identity.
    assert (
        _assert_gr08i_exact_match(
            tmp_path, rows,
            **dict(base_kwargs, method="runPrivacyCleanupGuarded")
        )
        is False
    )
    # Wrong overload: the purgePayloadBestEffort three-parameter shape.
    assert (
        _assert_gr08i_exact_match(
            tmp_path,
            rows,
            **dict(base_kwargs, parameter_types=PURGE_PAYLOAD_PARAMS),
        )
        is False
    )


def test_gr08i3_purge_row_near_misses_stay_unauthorized(tmp_path):
    """The purgePayloadBestEffort row is exact too.

    The best-effort purge shares the intakeDao accessor with the doWork
    rows but differs in callable identity AND parameter shape; a swapped
    callable, a swapped operation, or the zero-parameter doWork shape stays
    unauthorized.
    """
    rows = _gr08i3_seed_rows()
    base_kwargs = dict(
        select_method="purgePayloadBestEffort",
        select_accessor="intakeDao",
        select_operation="purgeAllPayload",
    )
    assert _assert_gr08i_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong operation: the sibling markTerminal spelling behind the same
    # DAO.
    assert (
        _assert_gr08i_exact_match(
            tmp_path, rows, **dict(base_kwargs, operation="markTerminal")
        )
        is False
    )
    # Wrong callable: the doWork rows never match the purge identity.
    assert (
        _assert_gr08i_exact_match(
            tmp_path, rows, **dict(base_kwargs, method="doWork")
        )
        is False
    )
    # Wrong overload: the zero-parameter doWork shape.
    assert (
        _assert_gr08i_exact_match(
            tmp_path, rows, **dict(base_kwargs, parameter_types=())
        )
        is False
    )
    # Wrong owner: a copied worker class never matches.
    assert (
        _assert_gr08i_exact_match(
            tmp_path,
            rows,
            **dict(base_kwargs, owner_fqcn="com.example.CopyWorker"),
        )
        is False
    )


# ── (15) GR-08j1/j2 rows: tracked seed files + concatenation + NEAR-MISS ──────
#
# GR-08j authorizes FOUR top-density files.  The combined batch carries 47
# findings / 34 unique fingerprints > the 25-fingerprint batch cap, so the
# batch was SPLIT into two file groups per the GR-08c/GR-08e/GR-08i
# precedent:
#
# * GR-08j1 -- ReceiptLinkService.kt (12 findings / 11 unique fingerprints;
#   the two unlinkReceiptFromExpense scannedReceiptDao.update sites share one
#   fingerprint).  The AppDatabase.kt half of GR-08j1 (14
#   DB_FORBIDDEN_STRUCTURAL_OPERATION findings on the named-object migrations
#   MIGRATION_16_17 / MIGRATION_41_42) is NOT seed-authorized: those findings
#   carry no DAO identity, so they are resolved by TWO exact structural
#   exception tuples (class MIGRATION_16_17 / MIGRATION_41_42, method_pattern
#   migrate, operation execSQL) pinned by the structural manifest contract
#   tests in scripts/test_verify_db_access_boundaries.py (62 -> 64).
# * GR-08j2 -- ExpenseWriteStore.kt (11 findings / 11 unique fingerprints) +
#   SourceStatsRepository.kt (10 findings / 10 unique fingerprints).
#
# The migration CLI accepts a SINGLE --seed-rows value, so every generation
# run consumes the COMBINED document GR-08-seeds.yml; these tests pin that
# the combined document stays the exact concatenation of the FIFTEEN
# reviewed batch seed files, and that the GR-08j rows authorize EXACTLY
# their callable identity + DAO + operation (wrong overload, wrong owner,
# wrong DAO, and wrong operation stay unauthorized).

GR08J1_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08j1-seed.yml"
GR08J2_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08j2-seed.yml"

RECEIPT_LINK_SERVICE_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/receipt/"
    "lifecycle/ReceiptLinkService.kt"
)
RECEIPT_LINK_SERVICE_FQCN = (
    "com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptLinkService"
)
RECEIPT_EXPENSE_LINK_DAO = (
    "com.yourname.expensetracker.data.database.dao.ReceiptExpenseLinkDao"
)
# SCANNED_RECEIPT_DAO / WARRANTY_DAO / RETURN_WINDOW_DAO are already defined
# by the GR-08g / GR-08e sections above.
RECEIPT_ITEM_CATEGORIZATION_DAO = (
    "com.yourname.expensetracker.data.database.dao.ReceiptItemCategorizationDao"
)
MATCH_STATUS_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.MatchStatus?"
)
LINK_RECEIPT_PARAMS = (
    "Long",
    "Long",
    "String",
    "String",
    "String?",
    "Float?",
    "Boolean",
    MATCH_STATUS_ENTITY,
    "Boolean",
    "Boolean",
)
UNLINK_RECEIPT_PARAMS = ("Long", "Long")

EXPENSE_WRITE_STORE_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/store/"
    "ExpenseWriteStore.kt"
)
EXPENSE_WRITE_STORE_FQCN = (
    "com.yourname.expensetracker.data.store.ExpenseWriteStore"
)
# EXPENSE_DAO_GR08I is already defined by the GR-08i section above.
EXPENSE_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.Expense"
)
EXPENSE_ENTITY_LIST = "List<" + EXPENSE_ENTITY + ">"

SOURCE_STATS_REPOSITORY_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "SourceStatsRepository.kt"
)
SOURCE_STATS_REPOSITORY_FQCN = (
    "com.yourname.expensetracker.data.repository.SourceStatsRepository"
)
SOURCE_STATS_DAO_GR08J = (
    "com.yourname.expensetracker.data.database.dao.SourceStatsDao"
)
SOURCE_STATS_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.SourceStats"
)
PACKAGE_ONLY_PARAMS = ("String",)
PACKAGE_AND_NOW_PARAMS = ("String", "Long")
NO_PARAMS_GR08J: tuple = ()


def _gr08j_seed_row(path, owner_fqcn, method, parameter_types, dao_accessor,
                    dao_fqcn, operation):
    """One exact GR-08j-shaped v2 seed row mapping."""
    return {
        "path": path,
        "ownerFqcn": owner_fqcn,
        "kind": "function",
        "method": method,
        "receiver": None,
        "parameterTypes": list(parameter_types),
        "daoAccessor": dao_accessor,
        "daoFqcn": dao_fqcn,
        "operation": operation,
        "barrierMode": "helper",
        "reason": "GR-08j EXACT_POLICY test row",
        "owner": "@panospao7",
        "linkedIssue": "MIT-DB-08J",
    }


def _gr08j1_seed_rows():
    """The eleven exact GR-08j1 rows (mirroring the tracked seed file).

    ReceiptLinkService.kt; ZERO closure rows: every mutating DAO call in the
    file is an abstract Room-annotated method already covered by a finding
    (ReceiptExpenseLinkDao, ScannedReceiptDao, WarrantyDao, ReturnWindowDao
    and ReceiptItemCategorizationDao carry ZERO body-carrying @Transaction
    convenience methods; the file's only ExpenseDao call is the read-only
    getById).  The two unlinkReceiptFromExpense scannedReceiptDao.update
    sites share one fingerprint.
    """
    rows = []
    for accessor, dao, operation in (
        ("receiptExpenseLinkDao", RECEIPT_EXPENSE_LINK_DAO, "insert"),
        ("scannedReceiptDao", SCANNED_RECEIPT_DAO, "claimForAutoMatch"),
        ("scannedReceiptDao", SCANNED_RECEIPT_DAO, "update"),
        ("warrantyDao", WARRANTY_DAO, "updateExpenseIdByReceiptId"),
        ("returnWindowDao", RETURN_WINDOW_DAO, "updateExpenseIdByReceiptId"),
        (
            "receiptItemCategorizationDao",
            RECEIPT_ITEM_CATEGORIZATION_DAO,
            "linkToExpense",
        ),
    ):
        rows.append(
            _gr08j_seed_row(
                RECEIPT_LINK_SERVICE_KT, RECEIPT_LINK_SERVICE_FQCN,
                "linkReceiptToExpense", LINK_RECEIPT_PARAMS,
                accessor, dao, operation,
            )
        )
    for accessor, dao, operation in (
        ("receiptExpenseLinkDao", RECEIPT_EXPENSE_LINK_DAO, "unlink"),
        ("scannedReceiptDao", SCANNED_RECEIPT_DAO, "update"),
        ("warrantyDao", WARRANTY_DAO, "updateExpenseIdByReceiptId"),
        ("returnWindowDao", RETURN_WINDOW_DAO, "updateExpenseIdByReceiptId"),
        (
            "receiptItemCategorizationDao",
            RECEIPT_ITEM_CATEGORIZATION_DAO,
            "clearExpenseId",
        ),
    ):
        rows.append(
            _gr08j_seed_row(
                RECEIPT_LINK_SERVICE_KT, RECEIPT_LINK_SERVICE_FQCN,
                "unlinkReceiptFromExpense", UNLINK_RECEIPT_PARAMS,
                accessor, dao, operation,
            )
        )
    return rows


def _gr08j2_seed_rows():
    """The twenty-one exact GR-08j2 rows (mirroring the tracked seed file).

    ExpenseWriteStore.kt (11 rows) + SourceStatsRepository.kt (10 rows);
    ZERO closure rows: both files are single-statement barrier-checked
    delegates with fully abstract DAOs and no constructor aliases.
    """
    rows = []
    for method, params, operation in (
        ("insert", (EXPENSE_ENTITY,), "insert"),
        ("insertAll", (EXPENSE_ENTITY_LIST,), "insertAll"),
        ("update", (EXPENSE_ENTITY,), "update"),
        ("delete", (EXPENSE_ENTITY,), "delete"),
        ("updateCategory", ("Long", "Long"), "updateCategory"),
        ("updateCategoryNullable", ("Long", "Long?"), "updateCategoryNullable"),
        ("updateMerchantKey", ("Long", "String"), "updateMerchantKey"),
        ("incrementBackfillAttempts", ("Long",), "incrementBackfillAttempts"),
        (
            "conditionallySetLocation",
            ("Long", "Double", "Double", "String", "String?", "String?"),
            "conditionallySetLocation",
        ),
        ("updateMerchant", ("Long", "String"), "updateMerchant"),
        ("deleteAll", NO_PARAMS_GR08J, "deleteAll"),
    ):
        rows.append(
            _gr08j_seed_row(
                EXPENSE_WRITE_STORE_KT, EXPENSE_WRITE_STORE_FQCN,
                method, params, "expenseDao", EXPENSE_DAO_GR08I, operation,
            )
        )
    for method, params, operation in (
        ("insertIfNotExists", (SOURCE_STATS_ENTITY,), "insertIfNotExists"),
        ("incrementTotal", PACKAGE_AND_NOW_PARAMS, "incrementTotal"),
        ("incrementAccepted", PACKAGE_ONLY_PARAMS, "incrementAccepted"),
        ("incrementRejected", PACKAGE_ONLY_PARAMS, "incrementRejected"),
        ("incrementAutoRejected", PACKAGE_ONLY_PARAMS, "incrementAutoRejected"),
        ("incrementPending", PACKAGE_ONLY_PARAMS, "incrementPending"),
        ("incrementDuplicate", PACKAGE_ONLY_PARAMS, "incrementDuplicate"),
        ("decrementPending", PACKAGE_ONLY_PARAMS, "decrementPending"),
        ("resetAllPendingCounts", NO_PARAMS_GR08J, "resetAllPendingCounts"),
        ("deleteAll", NO_PARAMS_GR08J, "deleteAll"),
    ):
        rows.append(
            _gr08j_seed_row(
                SOURCE_STATS_REPOSITORY_KT, SOURCE_STATS_REPOSITORY_FQCN,
                method, params, "dao", SOURCE_STATS_DAO_GR08J, operation,
            )
        )
    return rows


def test_real_tracked_gr08j1_seed_file_loads_with_exactly_eleven_rows():
    entries = _load_seed_entries(GR08J1_SEED_FILE)
    assert len(entries) == 11
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted(
        ["linkReceiptToExpense"] * 6 + ["unlinkReceiptFromExpense"] * 5
    )
    for entry in entries:
        assert entry.path == RECEIPT_LINK_SERVICE_KT
        assert entry.owner_fqcn == RECEIPT_LINK_SERVICE_FQCN
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08J"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)
    # ZERO closure rows: every accessor is a plain constructor property and
    # every operation is the exact abstract Room method the scanner
    # reported -- no convenience-method rows exist in this batch.
    assert all(
        entry.dao_accessor
        in {
            "receiptExpenseLinkDao",
            "scannedReceiptDao",
            "warrantyDao",
            "returnWindowDao",
            "receiptItemCategorizationDao",
        }
        for entry in entries
    )
    # The scannedReceiptDao rows: exactly three distinct tuples -- the link
    # claim (claimForAutoMatch), the link legacy update, and the shared
    # unlink reconciliation update (2 call sites, 1 fingerprint).
    scanned = sorted(
        (entry.method, entry.operation)
        for entry in entries if entry.dao_accessor == "scannedReceiptDao"
    )
    assert scanned == sorted([
        ("linkReceiptToExpense", "claimForAutoMatch"),
        ("linkReceiptToExpense", "update"),
        ("unlinkReceiptFromExpense", "update"),
    ])


def test_real_tracked_gr08j2_seed_file_loads_with_exactly_twenty_one_rows():
    entries = _load_seed_entries(GR08J2_SEED_FILE)
    assert len(entries) == 21
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted(
        ["insert", "insertAll", "update", "delete", "updateCategory",
         "updateCategoryNullable", "updateMerchantKey",
         "incrementBackfillAttempts", "conditionallySetLocation",
         "updateMerchant", "deleteAll"]
        + ["insertIfNotExists", "incrementTotal", "incrementAccepted",
           "incrementRejected", "incrementAutoRejected", "incrementPending",
           "incrementDuplicate", "decrementPending", "resetAllPendingCounts",
           "deleteAll"]
    )
    store_entries = [e for e in entries if e.path == EXPENSE_WRITE_STORE_KT]
    stats_entries = [
        e for e in entries if e.path == SOURCE_STATS_REPOSITORY_KT
    ]
    assert len(store_entries) == 11
    assert len(stats_entries) == 10
    for entry in store_entries:
        assert entry.owner_fqcn == EXPENSE_WRITE_STORE_FQCN
        assert entry.dao_accessor == "expenseDao"
        assert entry.dao_fqcn == EXPENSE_DAO_GR08I
    for entry in stats_entries:
        assert entry.owner_fqcn == SOURCE_STATS_REPOSITORY_FQCN
        assert entry.dao_accessor == "dao"
        assert entry.dao_fqcn == SOURCE_STATS_DAO_GR08J
    for entry in entries:
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08J"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)


def _gr08j_policy_entries(tmp_path, rows):
    entries = []
    for position, row in enumerate(rows):
        entry, errors = build_policy_entry(row, position)
        assert entry is not None and not errors, (
            "GR-08j fixture row must be schema-valid: %s" % (errors,)
        )
        entries.append(entry)
    return entries


def _assert_gr08j_exact_match(tmp_path, rows, select_method, select_accessor,
                              select_operation, **overrides):
    """The exact GR-08j row identity matches; mutants never do.

    Target selection is fixed by ``(select_method, select_accessor,
    select_operation)``; ``overrides`` perturb exactly one identity field of
    the match query for the near-miss assertions.
    """
    entries = _gr08j_policy_entries(tmp_path, rows)
    target = [
        entry
        for entry in entries
        if entry.method == select_method
        and entry.dao_accessor == select_accessor
        and entry.operation == select_operation
    ][0]
    kwargs = dict(
        path=target.path,
        owner_fqcn=target.owner_fqcn,
        kind=target.kind,
        method=target.method,
        receiver=target.receiver,
        parameter_types=target.parameter_types,
        dao_accessor=target.dao_accessor,
        dao_fqcn=target.dao_fqcn,
        operation=target.operation,
    )
    kwargs.update(overrides)
    return match_mutation(target, **kwargs)


def test_gr08j1_exact_identity_matches(tmp_path):
    rows = _gr08j1_seed_rows()
    assert (
        _assert_gr08j_exact_match(
            tmp_path, rows, "linkReceiptToExpense", "receiptExpenseLinkDao",
            "insert",
        )
        is True
    )
    assert (
        _assert_gr08j_exact_match(
            tmp_path, rows, "unlinkReceiptFromExpense", "scannedReceiptDao",
            "update",
        )
        is True
    )


def test_gr08j1_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08j1_seed_rows()
    assert (
        _assert_gr08j_exact_match(
            tmp_path, rows, "unlinkReceiptFromExpense", "scannedReceiptDao",
            "update", parameter_types=("Long",),
        )
        is False
    )


def test_gr08j1_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08j1_seed_rows()
    assert (
        _assert_gr08j_exact_match(
            tmp_path, rows, "linkReceiptToExpense", "receiptExpenseLinkDao",
            "insert", owner_fqcn="com.example.OtherLinkService",
        )
        is False
    )


def test_gr08j1_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08j1_seed_rows()
    assert (
        _assert_gr08j_exact_match(
            tmp_path, rows, "linkReceiptToExpense", "receiptExpenseLinkDao",
            "insert",
            dao_accessor="scannedReceiptDao",
            dao_fqcn=SCANNED_RECEIPT_DAO,
        )
        is False
    )


def test_gr08j1_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08j1_seed_rows()
    assert (
        _assert_gr08j_exact_match(
            tmp_path, rows, "linkReceiptToExpense", "receiptExpenseLinkDao",
            "insert", operation="unlink",
        )
        is False
    )


def test_gr08j1_claim_row_near_misses_stay_unauthorized(tmp_path):
    """The claimForAutoMatch row is exact too: siblings never match.

    The atomic claim shares the linkReceiptToExpense callable identity and
    the scannedReceiptDao accessor with the legacy-field update row; a
    swapped operation, a swapped callable, or the unlink callable's
    two-parameter shape stays unauthorized.
    """
    rows = _gr08j1_seed_rows()
    base_kwargs = dict(
        select_method="linkReceiptToExpense",
        select_accessor="scannedReceiptDao",
        select_operation="claimForAutoMatch",
    )
    assert _assert_gr08j_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong operation: the sibling legacy-field update spelling behind the
    # same DAO and callable.
    assert (
        _assert_gr08j_exact_match(
            tmp_path, rows, **dict(base_kwargs, operation="update")
        )
        is False
    )
    # Wrong callable: the unlink reconciliation row never matches the link
    # claim identity.
    assert (
        _assert_gr08j_exact_match(
            tmp_path, rows,
            **dict(base_kwargs, method="unlinkReceiptFromExpense")
        )
        is False
    )
    # Wrong overload: the unlink (Long, Long) shape.
    assert (
        _assert_gr08j_exact_match(
            tmp_path, rows,
            **dict(base_kwargs, parameter_types=UNLINK_RECEIPT_PARAMS)
        )
        is False
    )


def test_gr08j1_propagation_rows_near_misses_stay_unauthorized(tmp_path):
    """The warranty/return/itemCategorization rows are exact per callable.

    linkReceiptToExpense and unlinkReceiptFromExpense both write
    warrantyDao.updateExpenseIdByReceiptId and
    returnWindowDao.updateExpenseIdByReceiptId; each row authorizes EXACTLY
    its own callable identity, so the sibling callable's shape (the
    ten-parameter link shape behind the unlink row, or vice versa) stays
    unauthorized.
    """
    rows = _gr08j1_seed_rows()
    base_kwargs = dict(
        select_method="unlinkReceiptFromExpense",
        select_accessor="warrantyDao",
        select_operation="updateExpenseIdByReceiptId",
    )
    assert _assert_gr08j_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong callable: the link propagation row never matches the unlink
    # identity.
    assert (
        _assert_gr08j_exact_match(
            tmp_path, rows, **dict(base_kwargs, method="linkReceiptToExpense")
        )
        is False
    )
    # Wrong accessor: the return-window row never matches the warranty
    # identity.
    assert (
        _assert_gr08j_exact_match(
            tmp_path,
            rows,
            **dict(
                base_kwargs,
                dao_accessor="returnWindowDao",
                dao_fqcn=RETURN_WINDOW_DAO,
            ),
        )
        is False
    )
    # Wrong operation: the plain Room update spelling behind the same DAO.
    assert (
        _assert_gr08j_exact_match(
            tmp_path, rows, **dict(base_kwargs, operation="update")
        )
        is False
    )


def test_gr08j2_exact_identity_matches(tmp_path):
    rows = _gr08j2_seed_rows()
    assert (
        _assert_gr08j_exact_match(
            tmp_path, rows, "insert", "expenseDao", "insert"
        )
        is True
    )
    assert (
        _assert_gr08j_exact_match(
            tmp_path, rows, "insertIfNotExists", "dao", "insertIfNotExists"
        )
        is True
    )


def test_gr08j2_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08j2_seed_rows()
    assert (
        _assert_gr08j_exact_match(
            tmp_path, rows, "insert", "expenseDao", "insert",
            parameter_types=(EXPENSE_ENTITY_LIST,),
        )
        is False
    )


def test_gr08j2_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08j2_seed_rows()
    assert (
        _assert_gr08j_exact_match(
            tmp_path, rows, "insert", "expenseDao", "insert",
            owner_fqcn="com.example.OtherStore",
        )
        is False
    )


def test_gr08j2_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08j2_seed_rows()
    assert (
        _assert_gr08j_exact_match(
            tmp_path, rows, "insert", "expenseDao", "insert",
            dao_accessor="dao",
            dao_fqcn=SOURCE_STATS_DAO_GR08J,
        )
        is False
    )


def test_gr08j2_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08j2_seed_rows()
    assert (
        _assert_gr08j_exact_match(
            tmp_path, rows, "insert", "expenseDao", "insert",
            operation="insertAll",
        )
        is False
    )


def test_gr08j2_delete_all_rows_near_misses_stay_unauthorized(tmp_path):
    """The two zero-parameter deleteAll rows are exact per file identity.

    ExpenseWriteStore.deleteAll (expenseDao) and
    SourceStatsRepository.deleteAll (dao) share the empty parameter shape
    but differ in path, owner, accessor, and DAO identity; a swapped file,
    a swapped accessor, or the resetAllPendingCounts sibling operation
    stays unauthorized.
    """
    rows = _gr08j2_seed_rows()
    base_kwargs = dict(
        select_method="deleteAll",
        select_accessor="expenseDao",
        select_operation="deleteAll",
    )
    assert _assert_gr08j_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong file/owner: the SourceStatsRepository deleteAll row never
    # matches the ExpenseWriteStore identity.
    assert (
        _assert_gr08j_exact_match(
            tmp_path,
            rows,
            **dict(base_kwargs, owner_fqcn=SOURCE_STATS_REPOSITORY_FQCN),
        )
        is False
    )
    # Wrong accessor/DAO: the stats dao spelling never matches the
    # expenseDao identity.
    assert (
        _assert_gr08j_exact_match(
            tmp_path,
            rows,
            **dict(base_kwargs, dao_accessor="dao",
                   dao_fqcn=SOURCE_STATS_DAO_GR08J),
        )
        is False
    )
    # Wrong operation: the resetAllPendingCounts sibling behind the stats
    # DAO.
    assert (
        _assert_gr08j_exact_match(
            tmp_path, rows, **dict(base_kwargs, operation="resetAllPendingCounts")
        )
        is False
    )


def test_gr08j2_stats_counter_rows_near_misses_stay_unauthorized(tmp_path):
    """The per-source counter rows are exact per operation AND overload.

    incrementAccepted / incrementRejected / incrementAutoRejected /
    incrementPending / incrementDuplicate / decrementPending all share the
    (String) shape behind the stats dao; each row authorizes EXACTLY its
    own operation, and incrementTotal's (String, Long) overload never
    matches a (String) row.
    """
    rows = _gr08j2_seed_rows()
    base_kwargs = dict(
        select_method="incrementAccepted",
        select_accessor="dao",
        select_operation="incrementAccepted",
    )
    assert _assert_gr08j_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong operation: the sibling incrementPending spelling behind the same
    # DAO and callable shape.
    assert (
        _assert_gr08j_exact_match(
            tmp_path, rows, **dict(base_kwargs, operation="incrementPending")
        )
        is False
    )
    # Wrong overload: the incrementTotal (String, Long) shape.
    assert (
        _assert_gr08j_exact_match(
            tmp_path, rows,
            **dict(base_kwargs, parameter_types=PACKAGE_AND_NOW_PARAMS)
        )
        is False
    )
    # Wrong callable: a copied repository class never matches.
    assert (
        _assert_gr08j_exact_match(
            tmp_path,
            rows,
            **dict(base_kwargs, owner_fqcn="com.example.CopyRepository"),
        )
        is False
    )


# ── GR-08k (MIT-DB-08K): RetentionModule.kt + RecommendationRepository.kt ────
# (GR-08k1) and GroupLifecycleCoordinator.kt + EnhancedSplitManager.kt
# (GR-08k2).  The combined batch carries 37 findings / 37 unique fingerprints
# > the 25-fingerprint batch cap, so it was SPLIT into two file groups; the
# generation run consumes the COMBINED document GR-08-seeds.yml; these tests
# pin that the combined document stays the exact concatenation of the
# SEVENTEEN reviewed batch seed files, and that the GR-08k rows authorize
# EXACTLY their callable identity + DAO + operation (wrong overload, wrong
# owner, wrong DAO, and wrong operation stay unauthorized).

GR08K1_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08k1-seed.yml"
GR08K2_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08k2-seed.yml"

RETENTION_MODULE_KT = (
    "app/src/main/java/com/yourname/expensetracker/di/RetentionModule.kt"
)
RETENTION_MODULE_FQCN = "com.yourname.expensetracker.di.RetentionModule"
APP_DATABASE_TYPE = (
    "com.yourname.expensetracker.data.database.AppDatabase"
)
TIME_PROVIDER_TYPE = "com.yourname.expensetracker.domain.util.TimeProvider"
PROVIDE_RETENTION_PARAMS = (APP_DATABASE_TYPE, TIME_PROVIDER_TYPE)
RAW_NOTIFICATION_DAO = (
    "com.yourname.expensetracker.data.database.dao.RawNotificationDao"
)
AI_ARTIFACT_DAO = "com.yourname.expensetracker.data.database.dao.AiArtifactDao"
AI_CHAT_MESSAGE_DAO = (
    "com.yourname.expensetracker.data.database.dao.AiChatMessageDao"
)
EMAIL_RECEIPT_DAO = (
    "com.yourname.expensetracker.data.database.dao.EmailReceiptDao"
)
NOTIFICATION_INTAKE_DAO_GR08K = (
    "com.yourname.expensetracker.data.database.dao.NotificationIntakeDao"
)
PIPELINE_DIAGNOSTIC_EVENT_DAO = (
    "com.yourname.expensetracker.data.database.dao.PipelineDiagnosticEventDao"
)
PENDING_REVIEW_DAO_GR08K = (
    "com.yourname.expensetracker.data.database.dao.PendingReviewDao"
)
BACKGROUND_JOB_RUN_DAO = (
    "com.yourname.expensetracker.data.database.dao.BackgroundJobRunDao"
)
BANK_STATEMENT_IMPORT_ITEM_DAO = (
    "com.yourname.expensetracker.data.database.dao.BankStatementImportItemDao"
)

RECOMMENDATION_REPOSITORY_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "RecommendationRepository.kt"
)
RECOMMENDATION_REPOSITORY_FQCN = (
    "com.yourname.expensetracker.data.repository.RecommendationRepository"
)
RECOMMENDATION_DAO = (
    "com.yourname.expensetracker.data.database.dao.RecommendationDao"
)
DASHBOARD_RECOMMENDATION = (
    "com.yourname.expensetracker.domain.model.recommendation."
    "DashboardFollowThroughRecommendation"
)
DASHBOARD_RECOMMENDATION_LIST = "List<" + DASHBOARD_RECOMMENDATION + ">"

GROUP_LIFECYCLE_COORDINATOR_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/groups/"
    "GroupLifecycleCoordinator.kt"
)
GROUP_LIFECYCLE_COORDINATOR_FQCN = (
    "com.yourname.expensetracker.domain.groups.GroupLifecycleCoordinator"
)
GROUP_LIFECYCLE_EVENT_DAO = (
    "com.yourname.expensetracker.data.database.dao.GroupLifecycleEventDao"
)
GROUP_SETTLEMENT_DAO = (
    "com.yourname.expensetracker.data.database.dao.GroupSettlementDao"
)
GROUP_MEMBER_DAO_GR08K = (
    "com.yourname.expensetracker.data.database.dao.GroupMemberDao"
)
GROUP_MEMBER_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.GroupMember"
)
GROUP_MEMBER_ENTITY_LIST = "List<" + GROUP_MEMBER_ENTITY + ">"
SPLIT_TYPE_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.SplitType"
)

ENHANCED_SPLIT_MANAGER_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/split/"
    "EnhancedSplitManager.kt"
)
ENHANCED_SPLIT_MANAGER_FQCN = (
    "com.yourname.expensetracker.domain.split.EnhancedSplitManager"
)
SPLIT_TEMPLATE_DAO = (
    "com.yourname.expensetracker.data.database.dao.SplitTemplateDao"
)
SPLIT_ITEM_ASSIGNMENT_DAO = (
    "com.yourname.expensetracker.data.database.dao.SplitItemAssignmentDao"
)
SPLIT_TEMPLATE_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.SplitTemplate"
)
SPLIT_TEMPLATE_SPLIT_TYPE = SPLIT_TEMPLATE_ENTITY + ".SplitType"
SPLIT_SHARE_LIST = (
    "List<com.yourname.expensetracker.data.database.entity.SplitShare>"
)
ITEM_ASSIGNMENT_LIST = (
    "List<com.yourname.expensetracker.domain.split."
    "EnhancedSplitManager.ItemAssignment>"
)


def _gr08k_seed_row(path, owner_fqcn, method, parameter_types, dao_accessor,
                    dao_fqcn, operation):
    """One exact GR-08k-shaped v2 seed row mapping."""
    return {
        "path": path,
        "ownerFqcn": owner_fqcn,
        "kind": "function",
        "method": method,
        "receiver": None,
        "parameterTypes": list(parameter_types),
        "daoAccessor": dao_accessor,
        "daoFqcn": dao_fqcn,
        "operation": operation,
        "barrierMode": "helper",
        "reason": "GR-08k EXACT_POLICY test row",
        "owner": "@panospao7",
        "linkedIssue": "MIT-DB-08K",
    }


def _gr08k1_seed_rows():
    """The nineteen exact GR-08k1 rows (mirroring the tracked seed file).

    RetentionModule.kt (10 rows) + RecommendationRepository.kt (9 rows);
    ZERO closure rows: every mutating DAO call is an abstract Room-annotated
    method already covered by a finding.  The RetentionModule rows spell the
    GR-08k1-normalized DAO-named accessors (the START findings carried 7
    chain-form appDatabase.xxxDao() receivers and 3 colliding `dao` aliases).
    """
    rows = []
    for accessor, dao, operation in (
        ("rawNotificationDao", RAW_NOTIFICATION_DAO, "updateRawContentPurged"),
        ("scannedReceiptDao", SCANNED_RECEIPT_DAO, "updateRawOcrTextPurged"),
        ("aiArtifactDao", AI_ARTIFACT_DAO, "deleteExpired"),
        ("aiChatMessageDao", AI_CHAT_MESSAGE_DAO, "deleteOlderThan"),
        (
            "emailReceiptDao",
            EMAIL_RECEIPT_DAO,
            "redactSensitiveFieldsOlderThan",
        ),
        (
            "notificationIntakeDao",
            NOTIFICATION_INTAKE_DAO_GR08K,
            "purgeRawPayload",
        ),
        (
            "pipelineDiagnosticEventDao",
            PIPELINE_DIAGNOSTIC_EVENT_DAO,
            "deleteOlderThan",
        ),
        (
            "pendingReviewDao",
            PENDING_REVIEW_DAO_GR08K,
            "redactNotificationTextOlderThan",
        ),
        (
            "backgroundJobRunDao",
            BACKGROUND_JOB_RUN_DAO,
            "redactErrorMessagesOlderThan",
        ),
        (
            "bankStatementImportItemDao",
            BANK_STATEMENT_IMPORT_ITEM_DAO,
            "redactMerchantOlderThan",
        ),
    ):
        rows.append(
            _gr08k_seed_row(
                RETENTION_MODULE_KT, RETENTION_MODULE_FQCN,
                "provideRetentionTargets", PROVIDE_RETENTION_PARAMS,
                accessor, dao, operation,
            )
        )
    for method, params, operation in (
        ("save", (DASHBOARD_RECOMMENDATION,), "insert"),
        ("saveAll", (DASHBOARD_RECOMMENDATION_LIST,), "insertAll"),
        (
            "saveAll",
            (DASHBOARD_RECOMMENDATION_LIST,),
            "archiveActiveOverflow",
        ),
        ("dismiss", ("String",), "archive"),
        ("expireOld", ("String", "Long"), "expireOld"),
        ("expireAll", ("String", "Long"), "expireOld"),
        ("expireAll", ("String", "Long"), "expireAllActiveByUser"),
        ("clearForUser", ("String",), "clearByUser"),
        ("cleanupExpired", NO_PARAMS_GR08J, "deleteExpired"),
    ):
        rows.append(
            _gr08k_seed_row(
                RECOMMENDATION_REPOSITORY_KT, RECOMMENDATION_REPOSITORY_FQCN,
                method, params, "dao", RECOMMENDATION_DAO, operation,
            )
        )
    return rows


def _gr08k2_seed_rows():
    """The eighteen exact GR-08k2 rows (mirroring the tracked seed file).

    GroupLifecycleCoordinator.kt (9 rows) + EnhancedSplitManager.kt (9 rows);
    ZERO closure rows: GroupMemberDao's mutating setCurrentUser convenience
    is NOT called from the coordinator and the split DAOs are fully
    abstract.
    """
    rows = []
    for method, params, accessor, dao, operation in (
        (
            "createGroup",
            ("String", "String?", "String", GROUP_MEMBER_ENTITY_LIST),
            "lifecycleEventDao", GROUP_LIFECYCLE_EVENT_DAO, "insert",
        ),
        (
            "addMember",
            ("Long", "String", "String?", "Boolean"),
            "lifecycleEventDao", GROUP_LIFECYCLE_EVENT_DAO, "insert",
        ),
        (
            "removeMember", ("Long", "Long"),
            "memberDao", GROUP_MEMBER_DAO_GR08K, "update",
        ),
        (
            "removeMember", ("Long", "Long"),
            "lifecycleEventDao", GROUP_LIFECYCLE_EVENT_DAO, "insert",
        ),
        (
            "addExpense",
            (
                "Long", "String", "Double", "Long", "String?",
                SPLIT_TYPE_ENTITY, "String?", "Long",
            ),
            "lifecycleEventDao", GROUP_LIFECYCLE_EVENT_DAO, "insert",
        ),
        (
            "archiveGroup", ("Long",),
            "lifecycleEventDao", GROUP_LIFECYCLE_EVENT_DAO, "insert",
        ),
        (
            "deleteGroupPermanently", ("Long", "Boolean"),
            "lifecycleEventDao", GROUP_LIFECYCLE_EVENT_DAO, "insert",
        ),
        (
            "recordSettlement",
            ("Long", "Long", "Long", "Double", "String", "String?", "Long?"),
            "settlementDao", GROUP_SETTLEMENT_DAO, "insert",
        ),
        (
            "emitLifecycleEvent",
            ("Long", "String", "Long", "Long"),
            "lifecycleEventDao", GROUP_LIFECYCLE_EVENT_DAO, "insert",
        ),
    ):
        rows.append(
            _gr08k_seed_row(
                GROUP_LIFECYCLE_COORDINATOR_KT,
                GROUP_LIFECYCLE_COORDINATOR_FQCN,
                method, params, accessor, dao, operation,
            )
        )
    for method, params, operation in (
        ("createTemplate",
         ("String", "Int", SPLIT_TEMPLATE_SPLIT_TYPE, SPLIT_SHARE_LIST),
         "insertTemplate"),
        ("updateTemplate", (SPLIT_TEMPLATE_ENTITY,), "updateTemplate"),
        ("deleteTemplate", (SPLIT_TEMPLATE_ENTITY,), "deleteTemplate"),
        ("setDefaultTemplate", ("Long",), "clearDefaultTemplate"),
        ("setDefaultTemplate", ("Long",), "setDefaultTemplate"),
        ("useTemplate", ("Long",), "incrementUseCount"),
    ):
        rows.append(
            _gr08k_seed_row(
                ENHANCED_SPLIT_MANAGER_KT, ENHANCED_SPLIT_MANAGER_FQCN,
                method, params, "splitTemplateDao", SPLIT_TEMPLATE_DAO,
                operation,
            )
        )
    for method, params, operation in (
        ("assignItemsToParticipants",
         ("Long", ITEM_ASSIGNMENT_LIST), "deleteAllForExpense"),
        ("assignItemsToParticipants",
         ("Long", ITEM_ASSIGNMENT_LIST), "insertAssignments"),
        ("markAssignmentAsPaid", ("Long",), "markAsPaid"),
    ):
        rows.append(
            _gr08k_seed_row(
                ENHANCED_SPLIT_MANAGER_KT, ENHANCED_SPLIT_MANAGER_FQCN,
                method, params, "splitItemAssignmentDao",
                SPLIT_ITEM_ASSIGNMENT_DAO, operation,
            )
        )
    return rows


def test_real_tracked_gr08k1_seed_file_loads_with_exactly_nineteen_rows():
    entries = _load_seed_entries(GR08K1_SEED_FILE)
    assert len(entries) == 19
    retention = [e for e in entries if e.path == RETENTION_MODULE_KT]
    repository = [e for e in entries if e.path == RECOMMENDATION_REPOSITORY_KT]
    assert len(retention) == 10
    assert len(repository) == 9
    for entry in retention:
        assert entry.owner_fqcn == RETENTION_MODULE_FQCN
        assert entry.method == "provideRetentionTargets"
        assert tuple(entry.parameter_types) == PROVIDE_RETENTION_PARAMS
        # The normalized accessor spellings: the historical chain text
        # (appDatabase.aiArtifactDao()) and the colliding `dao` alias stay
        # unauthorized.
        assert entry.dao_accessor != "dao"
        assert "(" not in entry.dao_accessor
    for entry in repository:
        assert entry.owner_fqcn == RECOMMENDATION_REPOSITORY_FQCN
        assert entry.dao_accessor == "dao"
        assert entry.dao_fqcn == RECOMMENDATION_DAO
    for entry in entries:
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08K"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)


def test_real_tracked_gr08k2_seed_file_loads_with_exactly_eighteen_rows():
    entries = _load_seed_entries(GR08K2_SEED_FILE)
    assert len(entries) == 18
    coordinator = [e for e in entries if e.path == GROUP_LIFECYCLE_COORDINATOR_KT]
    manager = [e for e in entries if e.path == ENHANCED_SPLIT_MANAGER_KT]
    assert len(coordinator) == 9
    assert len(manager) == 9
    for entry in coordinator:
        assert entry.owner_fqcn == GROUP_LIFECYCLE_COORDINATOR_FQCN
        assert entry.dao_accessor in {
            "lifecycleEventDao", "settlementDao", "memberDao",
        }
    for entry in manager:
        assert entry.owner_fqcn == ENHANCED_SPLIT_MANAGER_FQCN
        assert entry.dao_accessor in {"splitTemplateDao",
                                      "splitItemAssignmentDao"}
    for entry in entries:
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08K"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)


def _gr08k_policy_entries(tmp_path, rows):
    entries = []
    for position, row in enumerate(rows):
        entry, errors = build_policy_entry(row, position)
        assert entry is not None and not errors, (
            "GR-08k fixture row must be schema-valid: %s" % (errors,)
        )
        entries.append(entry)
    return entries


def _assert_gr08k_exact_match(tmp_path, rows, select_method, select_accessor,
                              select_operation, **overrides):
    """The exact GR-08k row identity matches; mutants never do.

    Target selection is fixed by ``(select_method, select_accessor,
    select_operation)``; ``overrides`` perturb exactly one identity field of
    the match query for the near-miss assertions.
    """
    entries = _gr08k_policy_entries(tmp_path, rows)
    target = [
        entry
        for entry in entries
        if entry.method == select_method
        and entry.dao_accessor == select_accessor
        and entry.operation == select_operation
    ][0]
    kwargs = dict(
        path=target.path,
        owner_fqcn=target.owner_fqcn,
        kind=target.kind,
        method=target.method,
        receiver=target.receiver,
        parameter_types=target.parameter_types,
        dao_accessor=target.dao_accessor,
        dao_fqcn=target.dao_fqcn,
        operation=target.operation,
    )
    kwargs.update(overrides)
    return match_mutation(target, **kwargs)


def test_gr08k1_exact_identity_matches(tmp_path):
    rows = _gr08k1_seed_rows()
    assert (
        _assert_gr08k_exact_match(
            tmp_path, rows, "provideRetentionTargets", "rawNotificationDao",
            "updateRawContentPurged",
        )
        is True
    )
    assert (
        _assert_gr08k_exact_match(
            tmp_path, rows, "saveAll", "dao", "archiveActiveOverflow"
        )
        is True
    )


def test_gr08k1_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08k1_seed_rows()
    assert (
        _assert_gr08k_exact_match(
            tmp_path, rows, "save", "dao", "insert",
            parameter_types=(DASHBOARD_RECOMMENDATION_LIST,),
        )
        is False
    )


def test_gr08k1_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08k1_seed_rows()
    assert (
        _assert_gr08k_exact_match(
            tmp_path, rows, "provideRetentionTargets", "rawNotificationDao",
            "updateRawContentPurged",
            owner_fqcn="com.example.OtherRetentionModule",
        )
        is False
    )


def test_gr08k1_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08k1_seed_rows()
    assert (
        _assert_gr08k_exact_match(
            tmp_path, rows, "provideRetentionTargets", "aiArtifactDao",
            "deleteExpired",
            dao_accessor="aiChatMessageDao",
            dao_fqcn=AI_CHAT_MESSAGE_DAO,
        )
        is False
    )


def test_gr08k1_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08k1_seed_rows()
    assert (
        _assert_gr08k_exact_match(
            tmp_path, rows, "expireAll", "dao", "expireOld",
            operation="expireAllActiveByUser",
        )
        is False
    )


def test_gr08k1_normalized_accessors_reject_historical_spellings(tmp_path):
    """The GR-08k1 normalization is load-bearing: old spellings never match.

    The seed rows spell the normalized DAO-named accessors; the historical
    chain-form receiver text (``appDatabase.aiArtifactDao()``) and the
    colliding ``dao`` alias stay unauthorized for the same callable +
    operation, and the expireOld row behind the expireAll alias never
    matches the expireOld callable's identity (and vice versa).
    """
    rows = _gr08k1_seed_rows()
    base_kwargs = dict(
        select_method="provideRetentionTargets",
        select_accessor="aiArtifactDao",
        select_operation="deleteExpired",
    )
    assert _assert_gr08k_exact_match(tmp_path, rows, **base_kwargs) is True
    # Historical chain-form accessor spelling.
    assert (
        _assert_gr08k_exact_match(
            tmp_path, rows,
            **dict(base_kwargs, dao_accessor="appDatabase.aiArtifactDao()"),
        )
        is False
    )
    # Colliding alias spelling.
    assert (
        _assert_gr08k_exact_match(
            tmp_path, rows, **dict(base_kwargs, dao_accessor="dao")
        )
        is False
    )
    # The alias row (expireAll/expireOld) never matches the expireOld
    # callable's identity.
    assert (
        _assert_gr08k_exact_match(
            tmp_path, rows, "expireOld", "dao", "expireOld",
            method="expireAll",
        )
        is False
    )


def test_gr08k2_exact_identity_matches(tmp_path):
    rows = _gr08k2_seed_rows()
    assert (
        _assert_gr08k_exact_match(
            tmp_path, rows, "recordSettlement", "settlementDao", "insert"
        )
        is True
    )
    assert (
        _assert_gr08k_exact_match(
            tmp_path, rows, "assignItemsToParticipants",
            "splitItemAssignmentDao", "insertAssignments",
        )
        is True
    )


def test_gr08k2_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08k2_seed_rows()
    assert (
        _assert_gr08k_exact_match(
            tmp_path, rows, "removeMember", "memberDao", "update",
            parameter_types=("Long",),
        )
        is False
    )


def test_gr08k2_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08k2_seed_rows()
    assert (
        _assert_gr08k_exact_match(
            tmp_path, rows, "recordSettlement", "settlementDao", "insert",
            owner_fqcn="com.example.OtherGroupCoordinator",
        )
        is False
    )


def test_gr08k2_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08k2_seed_rows()
    assert (
        _assert_gr08k_exact_match(
            tmp_path, rows, "createTemplate", "splitTemplateDao",
            "insertTemplate",
            dao_accessor="splitItemAssignmentDao",
            dao_fqcn=SPLIT_ITEM_ASSIGNMENT_DAO,
        )
        is False
    )


def test_gr08k2_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08k2_seed_rows()
    assert (
        _assert_gr08k_exact_match(
            tmp_path, rows, "setDefaultTemplate", "splitTemplateDao",
            "clearDefaultTemplate",
            operation="setDefaultTemplate",
        )
        is False
    )


def test_gr08k2_event_rows_near_misses_stay_unauthorized(tmp_path):
    """The lifecycle-event insert rows are exact per callable identity.

    Seven callables write lifecycleEventDao.insert; each row authorizes
    EXACTLY its own callable identity, so a swapped callable (the private
    emitLifecycleEvent helper vs the public createGroup entrypoint) or the
    removeMember memberDao.update sibling behind the same (Long, Long) shape
    stays unauthorized.
    """
    rows = _gr08k2_seed_rows()
    base_kwargs = dict(
        select_method="removeMember",
        select_accessor="lifecycleEventDao",
        select_operation="insert",
    )
    assert _assert_gr08k_exact_match(tmp_path, rows, **base_kwargs) is True
    # Wrong callable: the private helper's identity never matches the public
    # removeMember entrypoint.
    assert (
        _assert_gr08k_exact_match(
            tmp_path, rows,
            **dict(base_kwargs, method="emitLifecycleEvent")
        )
        is False
    )
    # Wrong accessor/DAO: the memberDao.update sibling behind the same
    # (Long, Long) parameter shape.
    assert (
        _assert_gr08k_exact_match(
            tmp_path,
            rows,
            **dict(
                base_kwargs,
                dao_accessor="memberDao",
                dao_fqcn=GROUP_MEMBER_DAO_GR08K,
                operation="update",
            ),
        )
        is False
    )
    # Wrong operation: the insert spelling behind the memberDao identity.
    assert (
        _assert_gr08k_exact_match(
            tmp_path,
            rows,
            **dict(
                base_kwargs,
                dao_accessor="memberDao",
                dao_fqcn=GROUP_MEMBER_DAO_GR08K,
            ),
        )
        is False
    )


# ── GR-08l (MIT-DB-08L): ExpenseRepository.kt + MerchantNormalizationRepo ────
# (GR-08l1) and SavingsGoalRepository.kt + TransactionLifecycleCoordinator.kt
# + SubscriptionManagementRepository.kt (GR-08l2).  The combined batch
# carries 39 findings / 39 unique fingerprints > the 25-fingerprint batch
# cap, so it was SPLIT into two file groups; the generation run consumes the
# COMBINED document GR-08-seeds.yml; these tests pin that the combined
# document stays the exact concatenation of the NINETEEN reviewed batch seed
# files, and that the GR-08l rows authorize EXACTLY their callable identity
# + DAO + operation (wrong overload, wrong owner, wrong DAO, and wrong
# operation stay unauthorized).

GR08L1_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08l1-seed.yml"
GR08L2_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08l2-seed.yml"

EXPENSE_REPOSITORY_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "ExpenseRepository.kt"
)
EXPENSE_REPOSITORY_FQCN = (
    "com.yourname.expensetracker.data.repository.ExpenseRepository"
)
EXPENSE_DAO = "com.yourname.expensetracker.data.database.dao.ExpenseDao"
USER_CORRECTION_DAO = (
    "com.yourname.expensetracker.data.database.dao.UserCorrectionDao"
)
PENDING_REVIEW_DAO_GR08L = (
    "com.yourname.expensetracker.data.database.dao.PendingReviewDao"
)
DEBUG_SNAPSHOT_TYPE = (
    "com.yourname.expensetracker.data.repository."
    "ExpenseRepository.DebugExpenseSnapshot"
)
EXPENSE_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.Expense"
)

MERCHANT_NORMALIZATION_REPOSITORY_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "MerchantNormalizationRepository.kt"
)
MERCHANT_NORMALIZATION_REPOSITORY_FQCN = (
    "com.yourname.expensetracker.data.repository."
    "MerchantNormalizationRepository"
)
MERCHANT_NORMALIZATION_DAO = (
    "com.yourname.expensetracker.data.database.dao.MerchantNormalizationDao"
)
MERCHANT_CANONICAL = (
    "com.yourname.expensetracker.data.database.entity.MerchantCanonical"
)
MERCHANT_ALIAS = (
    "com.yourname.expensetracker.data.database.entity.MerchantAlias"
)

# GR-08l1 residual closure files (GR-08l post-promotion rescan): the three
# findings popularly labeled "ExpenseRepository" live in THESE files --
# ExpenseRepository.kt itself carries none of the callables and stays at 0.
BUSINESS_EXPENSE_REPOSITORY_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "BusinessExpenseRepository.kt"
)
BUSINESS_EXPENSE_REPOSITORY_FQCN = (
    "com.yourname.expensetracker.data.repository.BusinessExpenseRepository"
)
MILEAGE_TRACKING_DAO = (
    "com.yourname.expensetracker.data.database.dao.MileageTrackingDao"
)
MILEAGE_TRACKING = (
    "com.yourname.expensetracker.data.database.entity.MileageTracking"
)
MANUAL_RECURRING_EXPENSE_REPOSITORY_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "ManualRecurringExpenseRepository.kt"
)
MANUAL_RECURRING_EXPENSE_REPOSITORY_FQCN = (
    "com.yourname.expensetracker.data.repository."
    "ManualRecurringExpenseRepository"
)
RECURRING_EXPENSE_REPOSITORY_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "RecurringExpenseRepository.kt"
)
RECURRING_EXPENSE_REPOSITORY_FQCN = (
    "com.yourname.expensetracker.data.repository.RecurringExpenseRepository"
)
RECURRING_LIFECYCLE_EVENT_DAO_GR08L = (
    "com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao"
)

SAVINGS_GOAL_REPOSITORY_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "SavingsGoalRepository.kt"
)
SAVINGS_GOAL_REPOSITORY_FQCN = (
    "com.yourname.expensetracker.data.repository.SavingsGoalRepository"
)
SAVINGS_GOAL_DAO = (
    "com.yourname.expensetracker.data.database.dao.SavingsGoalDao"
)
DOMAIN_SAVINGS_GOAL = (
    "com.yourname.expensetracker.domain.model.SavingsGoal"
)
ENTITY_SAVINGS_GOAL = (
    "com.yourname.expensetracker.data.database.entity.SavingsGoal"
)

TRANSACTION_LIFECYCLE_COORDINATOR_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/transaction/"
    "lifecycle/TransactionLifecycleCoordinator.kt"
)
TRANSACTION_LIFECYCLE_COORDINATOR_FQCN = (
    "com.yourname.expensetracker.domain.transaction.lifecycle."
    "TransactionLifecycleCoordinator"
)
TRANSACTION_EVENT_DAO = (
    "com.yourname.expensetracker.data.database.dao.TransactionEventDao"
)

SUBSCRIPTION_MANAGEMENT_REPOSITORY_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "SubscriptionManagementRepository.kt"
)
SUBSCRIPTION_MANAGEMENT_REPOSITORY_FQCN = (
    "com.yourname.expensetracker.data.repository."
    "SubscriptionManagementRepository"
)
MANUAL_RECURRING_EXPENSE_DAO = (
    "com.yourname.expensetracker.data.database.dao.ManualRecurringExpenseDao"
)
SUBSCRIPTION_PRICE_HISTORY_DAO = (
    "com.yourname.expensetracker.data.database.dao."
    "SubscriptionPriceHistoryDao"
)
SUBSCRIPTION_USAGE_DAO = (
    "com.yourname.expensetracker.data.database.dao.SubscriptionUsageDao"
)
SUBSCRIPTION_CANDIDATE_DAO = (
    "com.yourname.expensetracker.data.database.dao.SubscriptionCandidateDao"
)
MANUAL_RECURRING_EXPENSE = (
    "com.yourname.expensetracker.data.database.entity.ManualRecurringExpense"
)
SUBSCRIPTION_PRICE_HISTORY = (
    "com.yourname.expensetracker.data.database.entity."
    "SubscriptionPriceHistory"
)
SUBSCRIPTION_USAGE = (
    "com.yourname.expensetracker.data.database.entity.SubscriptionUsage"
)


def _gr08l_seed_row(path, owner_fqcn, method, parameter_types, dao_accessor,
                    dao_fqcn, operation):
    """One exact GR-08l-shaped v2 seed row mapping."""
    return {
        "path": path,
        "ownerFqcn": owner_fqcn,
        "kind": "function",
        "method": method,
        "receiver": None,
        "parameterTypes": list(parameter_types),
        "daoAccessor": dao_accessor,
        "daoFqcn": dao_fqcn,
        "operation": operation,
        "barrierMode": "helper",
        "reason": "GR-08l EXACT_POLICY test row",
        "owner": "@panospao7",
        "linkedIssue": "MIT-DB-08L",
    }


def _gr08l1_seed_rows():
    """The twenty-two exact GR-08l1 rows (mirroring the tracked seed file).

    ExpenseRepository.kt (8 findings-derived rows) +
    MerchantNormalizationRepository.kt (8 findings-derived rows) + THREE
    closure rows (updateExpenseMerchant -> pendingReviewDao.bulkRenameMerchant,
    insertAlias -> dao.incrementAliasOccurrence, linkAliasToCanonical ->
    dao.linkAliasToCanonical -- body-carrying @Transaction DAO convenience
    methods the findings scanner never reported) + THREE residual closure
    rows (the GR-08l post-promotion rescan: addMileage -> mileageDao.insert,
    and the two writeLifecycleEvent -> lifecycleEventDao.insert overloads --
    true paths in BusinessExpenseRepository.kt /
    ManualRecurringExpenseRepository.kt / RecurringExpenseRepository.kt,
    NOT ExpenseRepository.kt).
    """
    rows = []
    rows.append(
        _gr08l_seed_row(
            EXPENSE_REPOSITORY_KT, EXPENSE_REPOSITORY_FQCN,
            "updateExpenseCategoryBulk", ("String", "Long"),
            "userCorrectionDao", USER_CORRECTION_DAO, "insert",
        )
    )
    rows.append(
        _gr08l_seed_row(
            EXPENSE_REPOSITORY_KT, EXPENSE_REPOSITORY_FQCN,
            "deleteAllExpenses", (), "expenseDao", EXPENSE_DAO, "deleteAll",
        )
    )
    for operation in ("deleteAll", "insertAll"):
        rows.append(
            _gr08l_seed_row(
                EXPENSE_REPOSITORY_KT, EXPENSE_REPOSITORY_FQCN,
                "restoreDebugSnapshot", (DEBUG_SNAPSHOT_TYPE,),
                "expenseDao", EXPENSE_DAO, operation,
            )
        )
    rows.append(
        _gr08l_seed_row(
            EXPENSE_REPOSITORY_KT, EXPENSE_REPOSITORY_FQCN,
            "incrementBackfillAttempts", ("Long",),
            "expenseDao", EXPENSE_DAO, "incrementBackfillAttempts",
        )
    )
    rows.append(
        _gr08l_seed_row(
            EXPENSE_REPOSITORY_KT, EXPENSE_REPOSITORY_FQCN,
            "conditionallySetLocation",
            ("Long", "Double", "Double", "String", "String?", "String?"),
            "expenseDao", EXPENSE_DAO, "conditionallySetLocation",
        )
    )
    rows.append(
        _gr08l_seed_row(
            EXPENSE_REPOSITORY_KT, EXPENSE_REPOSITORY_FQCN,
            "clearExpenseLocation", ("Long",),
            "expenseDao", EXPENSE_DAO, "clearLocation",
        )
    )
    rows.append(
        _gr08l_seed_row(
            EXPENSE_REPOSITORY_KT, EXPENSE_REPOSITORY_FQCN,
            "updateMerchantKey", ("Long", "String"),
            "expenseDao", EXPENSE_DAO, "updateMerchantKey",
        )
    )
    # Closure row: the cross-table pending-review bulk rename.
    rows.append(
        _gr08l_seed_row(
            EXPENSE_REPOSITORY_KT, EXPENSE_REPOSITORY_FQCN,
            "updateExpenseMerchant", (EXPENSE_ENTITY, "String", "Boolean"),
            "pendingReviewDao", PENDING_REVIEW_DAO_GR08L,
            "bulkRenameMerchant",
        )
    )
    rows.append(
        _gr08l_seed_row(
            MERCHANT_NORMALIZATION_REPOSITORY_KT,
            MERCHANT_NORMALIZATION_REPOSITORY_FQCN,
            "insertCanonical", (MERCHANT_CANONICAL,),
            "dao", MERCHANT_NORMALIZATION_DAO, "insertCanonical",
        )
    )
    rows.append(
        _gr08l_seed_row(
            MERCHANT_NORMALIZATION_REPOSITORY_KT,
            MERCHANT_NORMALIZATION_REPOSITORY_FQCN,
            "updateCanonical", (MERCHANT_CANONICAL,),
            "dao", MERCHANT_NORMALIZATION_DAO, "updateCanonical",
        )
    )
    rows.append(
        _gr08l_seed_row(
            MERCHANT_NORMALIZATION_REPOSITORY_KT,
            MERCHANT_NORMALIZATION_REPOSITORY_FQCN,
            "updateCanonicalCategory", ("Long", "Long?"),
            "dao", MERCHANT_NORMALIZATION_DAO, "updateCanonicalCategory",
        )
    )
    rows.append(
        _gr08l_seed_row(
            MERCHANT_NORMALIZATION_REPOSITORY_KT,
            MERCHANT_NORMALIZATION_REPOSITORY_FQCN,
            "incrementMerchantStats", ("Long", "Double", "Long"),
            "dao", MERCHANT_NORMALIZATION_DAO, "incrementMerchantStats",
        )
    )
    rows.append(
        _gr08l_seed_row(
            MERCHANT_NORMALIZATION_REPOSITORY_KT,
            MERCHANT_NORMALIZATION_REPOSITORY_FQCN,
            "insertAlias", (MERCHANT_ALIAS,),
            "dao", MERCHANT_NORMALIZATION_DAO, "insertAlias",
        )
    )
    rows.append(
        _gr08l_seed_row(
            MERCHANT_NORMALIZATION_REPOSITORY_KT,
            MERCHANT_NORMALIZATION_REPOSITORY_FQCN,
            "insertAlias", (MERCHANT_ALIAS,),
            "dao", MERCHANT_NORMALIZATION_DAO, "updateAlias",
        )
    )
    rows.append(
        _gr08l_seed_row(
            MERCHANT_NORMALIZATION_REPOSITORY_KT,
            MERCHANT_NORMALIZATION_REPOSITORY_FQCN,
            "updateAlias", (MERCHANT_ALIAS,),
            "dao", MERCHANT_NORMALIZATION_DAO, "updateAlias",
        )
    )
    rows.append(
        _gr08l_seed_row(
            MERCHANT_NORMALIZATION_REPOSITORY_KT,
            MERCHANT_NORMALIZATION_REPOSITORY_FQCN,
            "deleteUnusedAliasesOlderThan", ("Long",),
            "dao", MERCHANT_NORMALIZATION_DAO,
            "deleteUnusedAliasesOlderThan",
        )
    )
    # Closure rows: the E3-001 normalizedKey fallback and the atomic
    # link-or-conflict composite.
    rows.append(
        _gr08l_seed_row(
            MERCHANT_NORMALIZATION_REPOSITORY_KT,
            MERCHANT_NORMALIZATION_REPOSITORY_FQCN,
            "insertAlias", (MERCHANT_ALIAS,),
            "dao", MERCHANT_NORMALIZATION_DAO, "incrementAliasOccurrence",
        )
    )
    rows.append(
        _gr08l_seed_row(
            MERCHANT_NORMALIZATION_REPOSITORY_KT,
            MERCHANT_NORMALIZATION_REPOSITORY_FQCN,
            "linkAliasToCanonical",
            ("String", "String", "Long", "Boolean", "Long"),
            "dao", MERCHANT_NORMALIZATION_DAO, "linkAliasToCanonical",
        )
    )
    # Residual closure rows (GR-08l post-promotion rescan): the three
    # findings popularly labeled "ExpenseRepository" live in these three
    # files; ExpenseRepository.kt stays at 0.
    rows.append(
        _gr08l_seed_row(
            BUSINESS_EXPENSE_REPOSITORY_KT, BUSINESS_EXPENSE_REPOSITORY_FQCN,
            "addMileage", (MILEAGE_TRACKING,),
            "mileageDao", MILEAGE_TRACKING_DAO, "insert",
        )
    )
    rows.append(
        _gr08l_seed_row(
            MANUAL_RECURRING_EXPENSE_REPOSITORY_KT,
            MANUAL_RECURRING_EXPENSE_REPOSITORY_FQCN,
            "writeLifecycleEvent", ("Long", "String", "Long", "String?"),
            "lifecycleEventDao", RECURRING_LIFECYCLE_EVENT_DAO_GR08L,
            "insert",
        )
    )
    rows.append(
        _gr08l_seed_row(
            RECURRING_EXPENSE_REPOSITORY_KT, RECURRING_EXPENSE_REPOSITORY_FQCN,
            "writeLifecycleEvent",
            ("Long", "String", "Long", "String?", "String?", "String?"),
            "lifecycleEventDao", RECURRING_LIFECYCLE_EVENT_DAO_GR08L,
            "insert",
        )
    )
    return rows


def _gr08l2_seed_rows():
    """The twenty-three exact GR-08l2 rows (mirroring the tracked seed file).

    SavingsGoalRepository.kt (8 rows) + TransactionLifecycleCoordinator.kt
    (8 rows) + SubscriptionManagementRepository.kt (7 rows); ZERO closure
    rows -- all seven touched DAOs are fully abstract interfaces.
    """
    rows = []
    rows.append(
        _gr08l_seed_row(
            SAVINGS_GOAL_REPOSITORY_KT, SAVINGS_GOAL_REPOSITORY_FQCN,
            "createSavingsGoal", (DOMAIN_SAVINGS_GOAL,),
            "savingsGoalDao", SAVINGS_GOAL_DAO, "insertGoal",
        )
    )
    rows.append(
        _gr08l_seed_row(
            SAVINGS_GOAL_REPOSITORY_KT, SAVINGS_GOAL_REPOSITORY_FQCN,
            "deleteSavingsGoal", (DOMAIN_SAVINGS_GOAL,),
            "savingsGoalDao", SAVINGS_GOAL_DAO, "deleteGoal",
        )
    )
    rows.append(
        _gr08l_seed_row(
            SAVINGS_GOAL_REPOSITORY_KT, SAVINGS_GOAL_REPOSITORY_FQCN,
            "updateSavingsGoalAmount", ("Long", "Double"),
            "savingsGoalDao", SAVINGS_GOAL_DAO, "updateGoalAmount",
        )
    )
    rows.append(
        _gr08l_seed_row(
            SAVINGS_GOAL_REPOSITORY_KT, SAVINGS_GOAL_REPOSITORY_FQCN,
            "incrementSavingsGoalAmount", ("Long", "Double"),
            "savingsGoalDao", SAVINGS_GOAL_DAO, "addToGoalAmount",
        )
    )
    rows.append(
        _gr08l_seed_row(
            SAVINGS_GOAL_REPOSITORY_KT, SAVINGS_GOAL_REPOSITORY_FQCN,
            "addGoal", (ENTITY_SAVINGS_GOAL,),
            "savingsGoalDao", SAVINGS_GOAL_DAO, "insertGoal",
        )
    )
    rows.append(
        _gr08l_seed_row(
            SAVINGS_GOAL_REPOSITORY_KT, SAVINGS_GOAL_REPOSITORY_FQCN,
            "deleteGoal", (ENTITY_SAVINGS_GOAL,),
            "savingsGoalDao", SAVINGS_GOAL_DAO, "deleteGoal",
        )
    )
    rows.append(
        _gr08l_seed_row(
            SAVINGS_GOAL_REPOSITORY_KT, SAVINGS_GOAL_REPOSITORY_FQCN,
            "updateGoalAmount", ("Long", "Double"),
            "savingsGoalDao", SAVINGS_GOAL_DAO, "updateGoalAmount",
        )
    )
    rows.append(
        _gr08l_seed_row(
            SAVINGS_GOAL_REPOSITORY_KT, SAVINGS_GOAL_REPOSITORY_FQCN,
            "addToGoalAmount", ("Long", "Double"),
            "savingsGoalDao", SAVINGS_GOAL_DAO, "addToGoalAmount",
        )
    )
    rows.append(
        _gr08l_seed_row(
            TRANSACTION_LIFECYCLE_COORDINATOR_KT,
            TRANSACTION_LIFECYCLE_COORDINATOR_FQCN,
            "bulkUpdateCategory",
            ("String", "Long", "String", "String?", "String?"),
            "expenseDao", EXPENSE_DAO, "updateCategoryForMerchant",
        )
    )
    rows.append(
        _gr08l_seed_row(
            TRANSACTION_LIFECYCLE_COORDINATOR_KT,
            TRANSACTION_LIFECYCLE_COORDINATOR_FQCN,
            "bulkUpdateCategory",
            ("String", "Long", "String", "String?", "String?"),
            "transactionEventDao", TRANSACTION_EVENT_DAO, "insert",
        )
    )
    rows.append(
        _gr08l_seed_row(
            TRANSACTION_LIFECYCLE_COORDINATOR_KT,
            TRANSACTION_LIFECYCLE_COORDINATOR_FQCN,
            "bulkUpdateCategory", ("Long", "Long", "String"),
            "expenseDao", EXPENSE_DAO, "updateCategoryForCategory",
        )
    )
    rows.append(
        _gr08l_seed_row(
            TRANSACTION_LIFECYCLE_COORDINATOR_KT,
            TRANSACTION_LIFECYCLE_COORDINATOR_FQCN,
            "bulkUpdateCategory", ("Long", "Long", "String"),
            "transactionEventDao", TRANSACTION_EVENT_DAO, "insert",
        )
    )
    rows.append(
        _gr08l_seed_row(
            TRANSACTION_LIFECYCLE_COORDINATOR_KT,
            TRANSACTION_LIFECYCLE_COORDINATOR_FQCN,
            "deleteExpense",
            ("Long", "String", "String?", "String?", "String?"),
            "transactionEventDao", TRANSACTION_EVENT_DAO, "insert",
        )
    )
    rows.append(
        _gr08l_seed_row(
            TRANSACTION_LIFECYCLE_COORDINATOR_KT,
            TRANSACTION_LIFECYCLE_COORDINATOR_FQCN,
            "deleteExpense",
            ("Long", "String", "String?", "String?", "String?"),
            "expenseDao", EXPENSE_DAO, "delete",
        )
    )
    rows.append(
        _gr08l_seed_row(
            TRANSACTION_LIFECYCLE_COORDINATOR_KT,
            TRANSACTION_LIFECYCLE_COORDINATOR_FQCN,
            "deleteExpense",
            (EXPENSE_ENTITY, "String", "String?", "String?", "String?"),
            "transactionEventDao", TRANSACTION_EVENT_DAO, "insert",
        )
    )
    rows.append(
        _gr08l_seed_row(
            TRANSACTION_LIFECYCLE_COORDINATOR_KT,
            TRANSACTION_LIFECYCLE_COORDINATOR_FQCN,
            "deleteExpense",
            (EXPENSE_ENTITY, "String", "String?", "String?", "String?"),
            "expenseDao", EXPENSE_DAO, "delete",
        )
    )
    rows.append(
        _gr08l_seed_row(
            SUBSCRIPTION_MANAGEMENT_REPOSITORY_KT,
            SUBSCRIPTION_MANAGEMENT_REPOSITORY_FQCN,
            "insertUsage", (SUBSCRIPTION_USAGE,),
            "usageDao", SUBSCRIPTION_USAGE_DAO, "insert",
        )
    )
    rows.append(
        _gr08l_seed_row(
            SUBSCRIPTION_MANAGEMENT_REPOSITORY_KT,
            SUBSCRIPTION_MANAGEMENT_REPOSITORY_FQCN,
            "updateSubscription", (MANUAL_RECURRING_EXPENSE,),
            "subscriptionDao", MANUAL_RECURRING_EXPENSE_DAO, "update",
        )
    )
    rows.append(
        _gr08l_seed_row(
            SUBSCRIPTION_MANAGEMENT_REPOSITORY_KT,
            SUBSCRIPTION_MANAGEMENT_REPOSITORY_FQCN,
            "deleteSubscriptionById", ("Long",),
            "subscriptionDao", MANUAL_RECURRING_EXPENSE_DAO, "deleteById",
        )
    )
    rows.append(
        _gr08l_seed_row(
            SUBSCRIPTION_MANAGEMENT_REPOSITORY_KT,
            SUBSCRIPTION_MANAGEMENT_REPOSITORY_FQCN,
            "insertSubscription", (MANUAL_RECURRING_EXPENSE,),
            "subscriptionDao", MANUAL_RECURRING_EXPENSE_DAO, "insert",
        )
    )
    rows.append(
        _gr08l_seed_row(
            SUBSCRIPTION_MANAGEMENT_REPOSITORY_KT,
            SUBSCRIPTION_MANAGEMENT_REPOSITORY_FQCN,
            "insertPriceHistory", (SUBSCRIPTION_PRICE_HISTORY,),
            "priceHistoryDao", SUBSCRIPTION_PRICE_HISTORY_DAO, "insert",
        )
    )
    rows.append(
        _gr08l_seed_row(
            SUBSCRIPTION_MANAGEMENT_REPOSITORY_KT,
            SUBSCRIPTION_MANAGEMENT_REPOSITORY_FQCN,
            "markCandidateAsConverted", ("Long", "Long", "Long"),
            "candidateDao", SUBSCRIPTION_CANDIDATE_DAO, "markAsConverted",
        )
    )
    rows.append(
        _gr08l_seed_row(
            SUBSCRIPTION_MANAGEMENT_REPOSITORY_KT,
            SUBSCRIPTION_MANAGEMENT_REPOSITORY_FQCN,
            "markCandidateAsRejected", ("Long", "Long"),
            "candidateDao", SUBSCRIPTION_CANDIDATE_DAO, "markAsRejected",
        )
    )
    return rows


def test_real_tracked_gr08l1_seed_file_loads_with_exactly_twenty_two_rows():
    entries = _load_seed_entries(GR08L1_SEED_FILE)
    assert len(entries) == 22
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted(
        ["updateExpenseCategoryBulk", "deleteAllExpenses"]
        + ["restoreDebugSnapshot"] * 2
        + ["incrementBackfillAttempts", "conditionallySetLocation",
           "clearExpenseLocation", "updateMerchantKey",
           "updateExpenseMerchant"]
        + ["insertCanonical", "updateCanonical", "updateCanonicalCategory",
           "incrementMerchantStats"]
        + ["insertAlias"] * 3
        + ["updateAlias", "deleteUnusedAliasesOlderThan",
           "linkAliasToCanonical"]
        + ["addMileage"]
        + ["writeLifecycleEvent"] * 2
    )
    for entry in entries:
        assert entry.path in (EXPENSE_REPOSITORY_KT,
                              MERCHANT_NORMALIZATION_REPOSITORY_KT,
                              BUSINESS_EXPENSE_REPOSITORY_KT,
                              MANUAL_RECURRING_EXPENSE_REPOSITORY_KT,
                              RECURRING_EXPENSE_REPOSITORY_KT)
        assert entry.owner_fqcn in (
            EXPENSE_REPOSITORY_FQCN,
            MERCHANT_NORMALIZATION_REPOSITORY_FQCN,
            BUSINESS_EXPENSE_REPOSITORY_FQCN,
            MANUAL_RECURRING_EXPENSE_REPOSITORY_FQCN,
            RECURRING_EXPENSE_REPOSITORY_FQCN,
        )
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08L"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)
    # The six closure rows: the three GR-08b/GR-08d blind-spot rows plus the
    # three GR-08l post-promotion residual rows (the two writeLifecycleEvent
    # overloads share the (method, accessor, operation) triple but are
    # distinct callables on distinct paths with distinct parameter lists).
    closure = sorted(
        (entry.method, entry.dao_accessor, entry.operation)
        for entry in entries
        if "CLOSURE" in entry.reason
    )
    assert closure == [
        ("addMileage", "mileageDao", "insert"),
        ("insertAlias", "dao", "incrementAliasOccurrence"),
        ("linkAliasToCanonical", "dao", "linkAliasToCanonical"),
        ("updateExpenseMerchant", "pendingReviewDao", "bulkRenameMerchant"),
        ("writeLifecycleEvent", "lifecycleEventDao", "insert"),
        ("writeLifecycleEvent", "lifecycleEventDao", "insert"),
    ]
    # The residual rows spell the TRUE paths: no closure row may claim the
    # ExpenseRepository.kt path for the residual callables (an
    # ExpenseRepository.kt row could never match the v2 fingerprints).
    residual_paths = sorted(
        entry.path
        for entry in entries
        if entry.method in ("addMileage", "writeLifecycleEvent")
    )
    assert residual_paths == [
        BUSINESS_EXPENSE_REPOSITORY_KT,
        MANUAL_RECURRING_EXPENSE_REPOSITORY_KT,
        RECURRING_EXPENSE_REPOSITORY_KT,
    ]


def test_real_tracked_gr08l2_seed_file_loads_with_exactly_twenty_three_rows():
    entries = _load_seed_entries(GR08L2_SEED_FILE)
    assert len(entries) == 23
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted(
        ["createSavingsGoal", "deleteSavingsGoal", "updateSavingsGoalAmount",
         "incrementSavingsGoalAmount", "addGoal", "deleteGoal",
         "updateGoalAmount", "addToGoalAmount"]
        + ["bulkUpdateCategory"] * 4
        + ["deleteExpense"] * 4
        + ["insertUsage", "updateSubscription", "deleteSubscriptionById",
           "insertSubscription", "insertPriceHistory",
           "markCandidateAsConverted", "markCandidateAsRejected"]
    )
    for entry in entries:
        assert entry.path in (
            SAVINGS_GOAL_REPOSITORY_KT,
            TRANSACTION_LIFECYCLE_COORDINATOR_KT,
            SUBSCRIPTION_MANAGEMENT_REPOSITORY_KT,
        )
        assert entry.owner_fqcn in (
            SAVINGS_GOAL_REPOSITORY_FQCN,
            TRANSACTION_LIFECYCLE_COORDINATOR_FQCN,
            SUBSCRIPTION_MANAGEMENT_REPOSITORY_FQCN,
        )
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08L"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)


def test_combined_seed_file_concatenates_all_twenty_six_batch_seed_files():
    """Drift guard: generation input == GR-08a + GR-08b + GR-08c1 + GR-08c2
    + GR-08d + GR-08e1 + GR-08e2 + GR-08f + GR-08g + GR-08h + GR-08i1
    + GR-08i2 + GR-08i3 + GR-08j1 + GR-08j2 + GR-08k1 + GR-08k2 + GR-08l1
    + GR-08l2 + GR-08m1 + GR-08m2 + GR-08n1 + GR-08n2 + GR-08o + GR-08p1
    + GR-08p2.

    Supersedes the GR-08p1-era twenty-five-file concatenation test (which
    pinned the combined document at 400 rows): the GR-08p2 batch -- the
    FINAL GR-08 triage batch -- extends the combined generation input to
    415 rows (15 GR-08p2 rows = 15 findings-derived; each of the fifteen
    files carries exactly 1 finding and each finding is its own distinct
    tuple), and the drift guard must cover ALL TWENTY-SIX reviewed batch
    seed files.  The combined document is what
    --seed-rows actually consumes; if it ever drifts from the twenty-six
    reviewed batch seed files (a dropped earlier-batch row would silently
    re-unauthorize that batch's mutations at promotion time), this fails
    closed.
    """
    combined = _load_seed_entries(COMBINED_SEED_FILE)
    gr08a = _load_seed_entries(SEED_FILE)
    gr08b = _load_seed_entries(GR08B_SEED_FILE)
    gr08c1 = _load_seed_entries(GR08C1_SEED_FILE)
    gr08c2 = _load_seed_entries(GR08C2_SEED_FILE)
    gr08d = _load_seed_entries(GR08D_SEED_FILE)
    gr08e1 = _load_seed_entries(GR08E1_SEED_FILE)
    gr08e2 = _load_seed_entries(GR08E2_SEED_FILE)
    gr08f = _load_seed_entries(GR08F_SEED_FILE)
    gr08g = _load_seed_entries(GR08G_SEED_FILE)
    gr08h = _load_seed_entries(GR08H_SEED_FILE)
    gr08i1 = _load_seed_entries(GR08I1_SEED_FILE)
    gr08i2 = _load_seed_entries(GR08I2_SEED_FILE)
    gr08i3 = _load_seed_entries(GR08I3_SEED_FILE)
    gr08j1 = _load_seed_entries(GR08J1_SEED_FILE)
    gr08j2 = _load_seed_entries(GR08J2_SEED_FILE)
    gr08k1 = _load_seed_entries(GR08K1_SEED_FILE)
    gr08k2 = _load_seed_entries(GR08K2_SEED_FILE)
    gr08l1 = _load_seed_entries(GR08L1_SEED_FILE)
    gr08l2 = _load_seed_entries(GR08L2_SEED_FILE)
    gr08m1 = _load_seed_entries(GR08M1_SEED_FILE)
    gr08m2 = _load_seed_entries(GR08M2_SEED_FILE)
    gr08n1 = _load_seed_entries(GR08N1_SEED_FILE)
    gr08n2 = _load_seed_entries(GR08N2_SEED_FILE)
    gr08o = _load_seed_entries(GR08O_SEED_FILE)
    gr08p1 = _load_seed_entries(GR08P1_SEED_FILE)
    gr08p2 = _load_seed_entries(GR08P2_SEED_FILE)
    assert len(gr08a) == 5
    assert len(gr08b) == 13
    assert len(gr08c1) == 10
    assert len(gr08c2) == 16
    assert len(gr08d) == 22
    assert len(gr08e1) == 23
    assert len(gr08e2) == 23
    assert len(gr08f) == 21
    assert len(gr08g) == 7
    assert len(gr08h) == 13
    assert len(gr08i1) == 14
    assert len(gr08i2) == 6
    assert len(gr08i3) == 7
    assert len(gr08j1) == 11
    assert len(gr08j2) == 21
    assert len(gr08k1) == 19
    assert len(gr08k2) == 18
    assert len(gr08l1) == 22
    assert len(gr08l2) == 23
    assert len(gr08m1) == 16
    assert len(gr08m2) == 12
    assert len(gr08n1) == 16
    assert len(gr08n2) == 15
    assert len(gr08o) == 24
    assert len(gr08p1) == 23
    assert len(gr08p2) == 15
    assert len(combined) == 415
    combined_fields = sorted(_entry_fields(entry) for entry in combined)
    batch_fields = sorted(
        _entry_fields(entry)
        for entry in list(gr08a) + list(gr08b) + list(gr08c1) + list(gr08c2)
        + list(gr08d) + list(gr08e1) + list(gr08e2) + list(gr08f)
        + list(gr08g) + list(gr08h) + list(gr08i1) + list(gr08i2)
        + list(gr08i3) + list(gr08j1) + list(gr08j2) + list(gr08k1)
        + list(gr08k2) + list(gr08l1) + list(gr08l2) + list(gr08m1)
        + list(gr08m2) + list(gr08n1) + list(gr08n2) + list(gr08o)
        + list(gr08p1) + list(gr08p2)
    )
    assert combined_fields == batch_fields
    keys = [entry.mutation_key().canonical_key() for entry in combined]
    assert len(set(keys)) == len(keys)


def _gr08l_policy_entries(tmp_path, rows):
    entries = []
    for position, row in enumerate(rows):
        entry, errors = build_policy_entry(row, position)
        assert entry is not None and not errors, (
            "GR-08l fixture row must be schema-valid: %s" % (errors,)
        )
        entries.append(entry)
    return entries


def _assert_gr08l_exact_match(tmp_path, rows, select_method, select_accessor,
                              select_operation, select_parameters=None,
                              **overrides):
    """The exact GR-08l row identity matches; mutants never do.

    Target selection is fixed by ``(select_method, select_accessor,
    select_operation)`` -- optionally narrowed by ``select_parameters`` when
    several rows share the same (method, accessor, operation) triple (the
    GR-08l overload/alias rows); ``overrides`` perturb exactly one identity
    field of the match query for the near-miss assertions.
    """
    entries = _gr08l_policy_entries(tmp_path, rows)
    candidates = [
        entry
        for entry in entries
        if entry.method == select_method
        and entry.dao_accessor == select_accessor
        and entry.operation == select_operation
    ]
    if select_parameters is not None:
        candidates = [
            entry
            for entry in candidates
            if tuple(entry.parameter_types) == tuple(select_parameters)
        ]
    target = candidates[0]
    kwargs = dict(
        path=target.path,
        owner_fqcn=target.owner_fqcn,
        kind=target.kind,
        method=target.method,
        receiver=target.receiver,
        parameter_types=target.parameter_types,
        dao_accessor=target.dao_accessor,
        dao_fqcn=target.dao_fqcn,
        operation=target.operation,
    )
    kwargs.update(overrides)
    return match_mutation(target, **kwargs)


def test_gr08l1_exact_identity_matches(tmp_path):
    rows = _gr08l1_seed_rows()
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "deleteAllExpenses", "expenseDao", "deleteAll"
        )
        is True
    )
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "insertAlias", "dao", "updateAlias"
        )
        is True
    )


def test_gr08l1_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08l1_seed_rows()
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "updateExpenseCategoryBulk", "userCorrectionDao",
            "insert",
            parameter_types=("String",),
        )
        is False
    )


def test_gr08l1_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08l1_seed_rows()
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "deleteAllExpenses", "expenseDao", "deleteAll",
            owner_fqcn="com.example.OtherExpenseRepository",
        )
        is False
    )


def test_gr08l1_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08l1_seed_rows()
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "insertCanonical", "dao", "insertCanonical",
            dao_accessor="expenseDao",
            dao_fqcn=EXPENSE_DAO,
        )
        is False
    )


def test_gr08l1_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08l1_seed_rows()
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "clearExpenseLocation", "expenseDao",
            "clearLocation",
            operation="conditionallySetLocation",
        )
        is False
    )


def test_gr08l1_multi_operation_rows_near_misses_stay_unauthorized(tmp_path):
    """The two-operations-per-callable rows are exact per operation.

    restoreDebugSnapshot carries BOTH deleteAll and insertAll on ExpenseDao
    and insertAlias carries BOTH insertAlias and updateAlias on
    MerchantNormalizationDao; each row authorizes EXACTLY its own
    (callable, operation) pair, so a swapped operation or the closure-row
    convenience spellings behind the same callable stay unauthorized.
    """
    rows = _gr08l1_seed_rows()
    # restoreDebugSnapshot: the insertAll row never matches the deleteAll
    # identity (and vice versa).
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "restoreDebugSnapshot", "expenseDao",
            "deleteAll",
            operation="insertAll",
        )
        is False
    )
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "restoreDebugSnapshot", "expenseDao",
            "insertAll",
            operation="deleteAll",
        )
        is False
    )
    # insertAlias: the updateAlias row never matches the insertAlias
    # operation identity.
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "insertAlias", "dao", "insertAlias",
            operation="updateAlias",
        )
        is False
    )
    # insertAlias: the closure-row convenience spellings behind the same
    # callable stay unauthorized for the findings-derived identities.
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "insertAlias", "dao", "insertAlias",
            operation="incrementAliasOccurrence",
        )
        is False
    )
    # The closure row itself matches its own convenience identity.
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "insertAlias", "dao",
            "incrementAliasOccurrence",
        )
        is True
    )
    # The pendingReviewDao closure row never matches the findings-derived
    # updateExpenseMerchant identity (no such l1 row exists for the
    # PendingReviewDao accessor under a different operation).
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "updateExpenseMerchant", "pendingReviewDao",
            "bulkRenameMerchant",
            operation="bulkRenameMerchantByKey",
        )
        is False
    )


def test_gr08l1_residual_closure_rows_exact_and_near_misses(tmp_path):
    """The three residual closure rows are exact per callable identity.

    The GR-08l post-promotion rescan left THREE residual findings popularly
    labeled "ExpenseRepository" -- in fact BusinessExpenseRepository.kt
    (addMileage/mileageDao/insert), ManualRecurringExpenseRepository.kt
    (writeLifecycleEvent/lifecycleEventDao/insert, 4-param overload) and
    RecurringExpenseRepository.kt (writeLifecycleEvent/lifecycleEventDao/
    insert, 6-param overload); ExpenseRepository.kt carries none of these
    callables and stays at 0.  Each row authorizes EXACTLY its own (path,
    callable, DAO, operation) identity: the misattributed
    ExpenseRepository.kt path, a wrong overload, wrong owner, wrong DAO,
    and wrong operation all stay unauthorized.
    """
    rows = _gr08l1_seed_rows()
    manual_params = ("Long", "String", "Long", "String?")
    recurring_params = (
        "Long", "String", "Long", "String?", "String?", "String?",
    )
    # addMileage: exact match...
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "addMileage", "mileageDao", "insert"
        )
        is True
    )
    # ...and the misattributed ExpenseRepository.kt path stays unauthorized.
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "addMileage", "mileageDao", "insert",
            path=EXPENSE_REPOSITORY_KT,
        )
        is False
    )
    # addMileage: wrong DAO and wrong operation stay unauthorized.
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "addMileage", "mileageDao", "insert",
            dao_accessor="expenseDao",
            dao_fqcn=EXPENSE_DAO,
        )
        is False
    )
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "addMileage", "mileageDao", "insert",
            operation="update",
        )
        is False
    )
    # writeLifecycleEvent (Manual 4-param overload): exact match...
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "writeLifecycleEvent", "lifecycleEventDao",
            "insert",
            select_parameters=manual_params,
        )
        is True
    )
    # ...the Recurring 6-param overload identity never matches it...
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "writeLifecycleEvent", "lifecycleEventDao",
            "insert",
            select_parameters=manual_params,
            parameter_types=recurring_params,
        )
        is False
    )
    # ...and the misattributed ExpenseRepository.kt path stays unauthorized.
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "writeLifecycleEvent", "lifecycleEventDao",
            "insert",
            select_parameters=manual_params,
            path=EXPENSE_REPOSITORY_KT,
        )
        is False
    )
    # writeLifecycleEvent (Recurring 6-param overload): exact match, and the
    # Manual 4-param identity never matches it.
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "writeLifecycleEvent", "lifecycleEventDao",
            "insert",
            select_parameters=recurring_params,
        )
        is True
    )
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "writeLifecycleEvent", "lifecycleEventDao",
            "insert",
            select_parameters=recurring_params,
            parameter_types=manual_params,
        )
        is False
    )
    # writeLifecycleEvent: wrong owner stays unauthorized.
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "writeLifecycleEvent", "lifecycleEventDao",
            "insert",
            select_parameters=recurring_params,
            owner_fqcn="com.example.OtherRecurringRepository",
        )
        is False
    )


def test_gr08l2_exact_identity_matches(tmp_path):
    rows = _gr08l2_seed_rows()
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "deleteExpense", "expenseDao", "delete"
        )
        is True
    )
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "markCandidateAsRejected", "candidateDao",
            "markAsRejected"
        )
        is True
    )


def test_gr08l2_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08l2_seed_rows()
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "deleteExpense", "expenseDao", "delete",
            parameter_types=("Long",),
        )
        is False
    )


def test_gr08l2_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08l2_seed_rows()
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "bulkUpdateCategory", "expenseDao",
            "updateCategoryForMerchant",
            owner_fqcn="com.example.OtherTransactionCoordinator",
        )
        is False
    )


def test_gr08l2_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08l2_seed_rows()
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "insertSubscription", "subscriptionDao",
            "insert",
            dao_accessor="usageDao",
            dao_fqcn=SUBSCRIPTION_USAGE_DAO,
        )
        is False
    )


def test_gr08l2_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08l2_seed_rows()
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "markCandidateAsConverted", "candidateDao",
            "markAsConverted",
            operation="markAsRejected",
        )
        is False
    )


def test_gr08l2_overload_and_alias_rows_near_misses_stay_unauthorized(tmp_path):
    """The overload/alias rows are exact per callable identity.

    The two bulkUpdateCategory overloads and two deleteExpense overloads
    carry the same DAO operations across different callable identities, and
    the four deprecated SavingsGoal aliases duplicate the domain-typed
    operations behind different callables; each row authorizes EXACTLY its
    own callable identity, so a swapped overload or alias stays
    unauthorized.
    """
    rows = _gr08l2_seed_rows()
    by_id_params = ("Long", "String", "String?", "String?", "String?")
    by_entity_params = (
        EXPENSE_ENTITY, "String", "String?", "String?", "String?",
    )
    # deleteExpense by-id row: exact match, and the by-entity observed
    # identity never matches it.
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "deleteExpense", "expenseDao", "delete",
            select_parameters=by_id_params,
        )
        is True
    )
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "deleteExpense", "expenseDao", "delete",
            select_parameters=by_id_params,
            parameter_types=by_entity_params,
        )
        is False
    )
    # deleteExpense by-entity row: exact match, and the by-id observed
    # identity never matches it.
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "deleteExpense", "expenseDao", "delete",
            select_parameters=by_entity_params,
        )
        is True
    )
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "deleteExpense", "expenseDao", "delete",
            select_parameters=by_entity_params,
            parameter_types=by_id_params,
        )
        is False
    )
    # bulkUpdateCategory: the merchant overload's event-insert row and the
    # category overload's event-insert row are distinct callable
    # identities; neither matches the other's observed identity.
    merchant_params = ("String", "Long", "String", "String?", "String?")
    category_params = ("Long", "Long", "String")
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "bulkUpdateCategory", "transactionEventDao",
            "insert",
            select_parameters=merchant_params,
        )
        is True
    )
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "bulkUpdateCategory", "transactionEventDao",
            "insert",
            select_parameters=merchant_params,
            parameter_types=category_params,
        )
        is False
    )
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "bulkUpdateCategory", "transactionEventDao",
            "insert",
            select_parameters=category_params,
        )
        is True
    )
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "bulkUpdateCategory", "transactionEventDao",
            "insert",
            select_parameters=category_params,
            parameter_types=merchant_params,
        )
        is False
    )
    # SavingsGoal aliases: the deprecated addGoal row never matches the
    # domain-typed createSavingsGoal identity (same DAO operation, different
    # callable + parameter type).
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "createSavingsGoal", "savingsGoalDao",
            "insertGoal",
            parameter_types=(ENTITY_SAVINGS_GOAL,),
        )
        is False
    )
    assert (
        _assert_gr08l_exact_match(
            tmp_path, rows, "addGoal", "savingsGoalDao", "insertGoal",
            parameter_types=(DOMAIN_SAVINGS_GOAL,),
        )
        is False
    )


# ── GR-08m (MIT-DB-08M): AiChatRepositoryImpl.kt + OperationRunRecorder.kt ───
# + RestoreJournalImporter.kt (GR-08m1) and ReceiptLifecycleCoordinator.kt
# + WarrantyExpirationWorker.kt (GR-08m2).  The combined batch carries
# 29 findings / 28 unique fingerprints > the 25-fingerprint batch cap, so it
# was SPLIT into two file groups; the generation run consumes the COMBINED
# document GR-08-seeds.yml; these tests pin that the combined document stays
# the exact concatenation of the TWENTY-ONE reviewed batch seed files, and
# that the GR-08m rows authorize EXACTLY their callable identity + DAO +
# operation (wrong overload, wrong owner, wrong DAO, and wrong operation
# stay unauthorized).

GR08M1_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08m1-seed.yml"
GR08M2_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08m2-seed.yml"

AI_CHAT_REPOSITORY_IMPL_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "AiChatRepositoryImpl.kt"
)
AI_CHAT_REPOSITORY_IMPL_FQCN = (
    "com.yourname.expensetracker.data.repository.AiChatRepositoryImpl"
)
AI_CHAT_SESSION_DAO = (
    "com.yourname.expensetracker.data.database.dao.AiChatSessionDao"
)
AI_CHAT_MESSAGE_DAO = (
    "com.yourname.expensetracker.data.database.dao.AiChatMessageDao"
)
ASSISTANT_MESSAGE_ROLE = (
    "com.yourname.expensetracker.domain.ai.model.AssistantMessageRole"
)
ASSISTANT_MESSAGE_KIND = (
    "com.yourname.expensetracker.domain.ai.model.AssistantMessageKind"
)

OPERATION_RUN_RECORDER_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/diagnostics/"
    "OperationRunRecorder.kt"
)
ROOM_OPERATION_RUN_RECORDER_FQCN = (
    "com.yourname.expensetracker.domain.diagnostics.RoomOperationRunRecorder"
)
ROOM_OPERATION_RUN_RECORDER_HANDLE_FQCN = (
    "com.yourname.expensetracker.domain.diagnostics."
    "RoomOperationRunRecorder.Handle"
)
OPERATION_RUN_DAO = (
    "com.yourname.expensetracker.data.database.dao.OperationRunDao"
)
OPERATION_RUN_EVENT_DAO = (
    "com.yourname.expensetracker.data.database.dao.OperationRunEventDao"
)
SAFE_EVENT_METADATA = (
    "com.yourname.expensetracker.domain.diagnostics.SafeEventMetadata"
)
EVENT_OUTCOME = "com.yourname.expensetracker.domain.diagnostics.EventOutcome"
DIAGNOSTIC_REASON_CODE = (
    "com.yourname.expensetracker.domain.diagnostics.DiagnosticReasonCode?"
)
EVENT_SEVERITY = (
    "com.yourname.expensetracker.domain.diagnostics.EventSeverity"
)

RESTORE_JOURNAL_IMPORTER_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/backup/"
    "RestoreJournalImporter.kt"
)
RESTORE_JOURNAL_IMPORTER_FQCN = (
    "com.yourname.expensetracker.data.backup.RestoreJournalImporter"
)

RECEIPT_LIFECYCLE_COORDINATOR_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/"
    "ReceiptLifecycleCoordinator.kt"
)
RECEIPT_LIFECYCLE_COORDINATOR_FQCN = (
    "com.yourname.expensetracker.domain.receipt.lifecycle."
    "ReceiptLifecycleCoordinator"
)
PENDING_REVIEW_DAO_GR08M = (
    "com.yourname.expensetracker.data.database.dao.PendingReviewDao"
)
SCANNED_RECEIPT_DAO_GR08M = (
    "com.yourname.expensetracker.data.database.dao.ScannedReceiptDao"
)
EMAIL_RECEIPT_DAO_GR08M = (
    "com.yourname.expensetracker.data.database.dao.EmailReceiptDao"
)
ANDROID_URI = "android.net.Uri"
RECEIPT_PROCESSING_OPTIONS = (
    "com.yourname.expensetracker.domain.receipt.lifecycle."
    "ReceiptLifecycleCoordinator.ReceiptProcessingOptions"
)
EMAIL_RECEIPT_DATA = (
    "com.yourname.expensetracker.domain.receipt.EmailReceiptData"
)

WARRANTY_EXPIRATION_WORKER_KT = (
    "app/src/main/java/com/yourname/expensetracker/service/warranty/"
    "WarrantyExpirationWorker.kt"
)
WARRANTY_EXPIRATION_WORKER_FQCN = (
    "com.yourname.expensetracker.service.warranty.WarrantyExpirationWorker"
)
WARRANTY_REMINDER_DELIVERY_DAO = (
    "com.yourname.expensetracker.data.database.dao."
    "WarrantyReminderDeliveryDao"
)
WARRANTY_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.Warranty"
)


def _gr08m_seed_row(path, owner_fqcn, method, parameter_types, dao_accessor,
                    dao_fqcn, operation, barrier_mode="helper"):
    """One exact GR-08m-shaped v2 seed row mapping."""
    return {
        "path": path,
        "ownerFqcn": owner_fqcn,
        "kind": "function",
        "method": method,
        "receiver": None,
        "parameterTypes": list(parameter_types),
        "daoAccessor": dao_accessor,
        "daoFqcn": dao_fqcn,
        "operation": operation,
        "barrierMode": barrier_mode,
        "reason": "GR-08m EXACT_POLICY test row",
        "owner": "@panospao7",
        "linkedIssue": "MIT-DB-08M",
    }


def _gr08m1_seed_rows():
    """The sixteen exact GR-08m1 rows (mirroring the tracked seed file).

    AiChatRepositoryImpl.kt (6 rows) + OperationRunRecorder.kt (6 rows) +
    RestoreJournalImporter.kt (4 rows -- the two
    importLastSuccessJournalIfPresent operationRunDao.insert call sites
    share ONE fingerprint, so 5 findings collapse to 4 rows); ZERO closure
    rows -- every touched DAO is a fully abstract interface.
    """
    append_params = (
        "Long", ASSISTANT_MESSAGE_ROLE, ASSISTANT_MESSAGE_KIND,
        "String", "String?",
    )
    rows = []
    rows.append(
        _gr08m_seed_row(
            AI_CHAT_REPOSITORY_IMPL_KT, AI_CHAT_REPOSITORY_IMPL_FQCN,
            "createSession", ("String?",),
            "sessionDao", AI_CHAT_SESSION_DAO, "insert",
        )
    )
    rows.append(
        _gr08m_seed_row(
            AI_CHAT_REPOSITORY_IMPL_KT, AI_CHAT_REPOSITORY_IMPL_FQCN,
            "appendMessage", append_params,
            "messageDao", AI_CHAT_MESSAGE_DAO, "insert",
        )
    )
    rows.append(
        _gr08m_seed_row(
            AI_CHAT_REPOSITORY_IMPL_KT, AI_CHAT_REPOSITORY_IMPL_FQCN,
            "appendMessage", append_params,
            "sessionDao", AI_CHAT_SESSION_DAO, "updateLastTouched",
        )
    )
    rows.append(
        _gr08m_seed_row(
            AI_CHAT_REPOSITORY_IMPL_KT, AI_CHAT_REPOSITORY_IMPL_FQCN,
            "clearSession", ("Long",),
            "sessionDao", AI_CHAT_SESSION_DAO, "deleteById",
        )
    )
    rows.append(
        _gr08m_seed_row(
            AI_CHAT_REPOSITORY_IMPL_KT, AI_CHAT_REPOSITORY_IMPL_FQCN,
            "clearAllHistory", (),
            "sessionDao", AI_CHAT_SESSION_DAO, "deleteAll",
        )
    )
    rows.append(
        _gr08m_seed_row(
            AI_CHAT_REPOSITORY_IMPL_KT, AI_CHAT_REPOSITORY_IMPL_FQCN,
            "purgeOldMessages", ("Long",),
            "messageDao", AI_CHAT_MESSAGE_DAO, "deleteOlderThan",
        )
    )
    rows.append(
        _gr08m_seed_row(
            OPERATION_RUN_RECORDER_KT, ROOM_OPERATION_RUN_RECORDER_FQCN,
            "start", ("String", "String?", SAFE_EVENT_METADATA,),
            "runDao", OPERATION_RUN_DAO, "insert",
        )
    )
    rows.append(
        _gr08m_seed_row(
            OPERATION_RUN_RECORDER_KT, ROOM_OPERATION_RUN_RECORDER_FQCN,
            "recoverStaleRunningOperationRuns", ("Long",),
            "runDao", OPERATION_RUN_DAO, "finalizeIfRunning",
        )
    )
    rows.append(
        _gr08m_seed_row(
            OPERATION_RUN_RECORDER_KT, ROOM_OPERATION_RUN_RECORDER_FQCN,
            "recoverStaleRunningOperationRuns", ("Long",),
            "eventDao", OPERATION_RUN_EVENT_DAO, "insert",
        )
    )
    rows.append(
        _gr08m_seed_row(
            OPERATION_RUN_RECORDER_KT, ROOM_OPERATION_RUN_RECORDER_HANDLE_FQCN,
            "event",
            (
                "String", EVENT_OUTCOME, DIAGNOSTIC_REASON_CODE,
                EVENT_SEVERITY, SAFE_EVENT_METADATA, "String?", "Long?",
                "Throwable?", "Boolean",
            ),
            "eventDao", OPERATION_RUN_EVENT_DAO, "insert",
        )
    )
    rows.append(
        _gr08m_seed_row(
            OPERATION_RUN_RECORDER_KT, ROOM_OPERATION_RUN_RECORDER_HANDLE_FQCN,
            "finalizeNonCancellable", ("String", "String?", "Throwable?",),
            "runDao", OPERATION_RUN_DAO, "finalizeIfRunning",
        )
    )
    rows.append(
        _gr08m_seed_row(
            OPERATION_RUN_RECORDER_KT, ROOM_OPERATION_RUN_RECORDER_HANDLE_FQCN,
            "increment", ("Int", "Int", "Int", "Int", "Int", "Int",),
            "runDao", OPERATION_RUN_DAO, "incrementCounters",
        )
    )
    rows.append(
        _gr08m_seed_row(
            RESTORE_JOURNAL_IMPORTER_KT, RESTORE_JOURNAL_IMPORTER_FQCN,
            "importLastSuccessJournalIfPresent", (),
            "operationRunDao", OPERATION_RUN_DAO, "insert",
        )
    )
    rows.append(
        _gr08m_seed_row(
            RESTORE_JOURNAL_IMPORTER_KT, RESTORE_JOURNAL_IMPORTER_FQCN,
            "importLastSuccessJournalIfPresent", (),
            "operationRunEventDao", OPERATION_RUN_EVENT_DAO, "insert",
        )
    )
    rows.append(
        _gr08m_seed_row(
            RESTORE_JOURNAL_IMPORTER_KT, RESTORE_JOURNAL_IMPORTER_FQCN,
            "importLastFailureJournalIfPresent", (),
            "operationRunDao", OPERATION_RUN_DAO, "insert",
        )
    )
    rows.append(
        _gr08m_seed_row(
            RESTORE_JOURNAL_IMPORTER_KT, RESTORE_JOURNAL_IMPORTER_FQCN,
            "importLastFailureJournalIfPresent", (),
            "operationRunEventDao", OPERATION_RUN_EVENT_DAO, "insert",
        )
    )
    return rows


def _gr08m2_seed_rows():
    """The twelve exact GR-08m2 rows (mirroring the tracked seed file).

    ReceiptLifecycleCoordinator.kt (6 rows, barrierMode helper) +
    WarrantyExpirationWorker.kt (6 rows, barrierMode workerMediated);
    ZERO closure rows -- every touched DAO method invoked by the batch
    callables is an abstract Room-annotated method.
    """
    process_input_params = (ANDROID_URI, RECEIPT_PROCESSING_OPTIONS)
    email_params = (EMAIL_RECEIPT_DATA, "String", "String", "String",
                    "String", "String", "String", "String?")
    deliver_params = (WARRANTY_ENTITY, "Int", "Long", "String", "String")
    rows = []
    rows.append(
        _gr08m_seed_row(
            RECEIPT_LIFECYCLE_COORDINATOR_KT,
            RECEIPT_LIFECYCLE_COORDINATOR_FQCN,
            "processReceiptInput", process_input_params,
            "pendingReviewDao", PENDING_REVIEW_DAO_GR08M,
            "deleteByScannedReceiptId",
        )
    )
    rows.append(
        _gr08m_seed_row(
            RECEIPT_LIFECYCLE_COORDINATOR_KT,
            RECEIPT_LIFECYCLE_COORDINATOR_FQCN,
            "processReceiptInput", process_input_params,
            "scannedReceiptDao", SCANNED_RECEIPT_DAO_GR08M, "delete",
        )
    )
    rows.append(
        _gr08m_seed_row(
            RECEIPT_LIFECYCLE_COORDINATOR_KT,
            RECEIPT_LIFECYCLE_COORDINATOR_FQCN,
            "processReceiptInput", process_input_params,
            "scannedReceiptDao", SCANNED_RECEIPT_DAO_GR08M, "update",
        )
    )
    rows.append(
        _gr08m_seed_row(
            RECEIPT_LIFECYCLE_COORDINATOR_KT,
            RECEIPT_LIFECYCLE_COORDINATOR_FQCN,
            "processReceiptInput", process_input_params,
            "pendingReviewDao", PENDING_REVIEW_DAO_GR08M, "insert",
        )
    )
    rows.append(
        _gr08m_seed_row(
            RECEIPT_LIFECYCLE_COORDINATOR_KT,
            RECEIPT_LIFECYCLE_COORDINATOR_FQCN,
            "processEmailReceipt", email_params,
            "emailReceiptDao", EMAIL_RECEIPT_DAO_GR08M, "insertOrIgnore",
        )
    )
    rows.append(
        _gr08m_seed_row(
            RECEIPT_LIFECYCLE_COORDINATOR_KT,
            RECEIPT_LIFECYCLE_COORDINATOR_FQCN,
            "processEmailReceipt", email_params,
            "pendingReviewDao", PENDING_REVIEW_DAO_GR08M, "insert",
        )
    )
    rows.append(
        _gr08m_seed_row(
            WARRANTY_EXPIRATION_WORKER_KT, WARRANTY_EXPIRATION_WORKER_FQCN,
            "doWork", (),
            "deliveryDao", WARRANTY_REMINDER_DELIVERY_DAO,
            "recoverStaleClaimed", barrier_mode="workerMediated",
        )
    )
    rows.append(
        _gr08m_seed_row(
            WARRANTY_EXPIRATION_WORKER_KT, WARRANTY_EXPIRATION_WORKER_FQCN,
            "doWork", (),
            "deliveryDao", WARRANTY_REMINDER_DELIVERY_DAO,
            "deleteOlderThan", barrier_mode="workerMediated",
        )
    )
    rows.append(
        _gr08m_seed_row(
            WARRANTY_EXPIRATION_WORKER_KT, WARRANTY_EXPIRATION_WORKER_FQCN,
            "deliverReminder", deliver_params,
            "deliveryDao", WARRANTY_REMINDER_DELIVERY_DAO, "insertOrIgnore",
            barrier_mode="workerMediated",
        )
    )
    rows.append(
        _gr08m_seed_row(
            WARRANTY_EXPIRATION_WORKER_KT, WARRANTY_EXPIRATION_WORKER_FQCN,
            "deliverReminder", deliver_params,
            "deliveryDao", WARRANTY_REMINDER_DELIVERY_DAO, "claim",
            barrier_mode="workerMediated",
        )
    )
    rows.append(
        _gr08m_seed_row(
            WARRANTY_EXPIRATION_WORKER_KT, WARRANTY_EXPIRATION_WORKER_FQCN,
            "deliverReminder", deliver_params,
            "deliveryDao", WARRANTY_REMINDER_DELIVERY_DAO,
            "markSentFromClaimed", barrier_mode="workerMediated",
        )
    )
    rows.append(
        _gr08m_seed_row(
            WARRANTY_EXPIRATION_WORKER_KT, WARRANTY_EXPIRATION_WORKER_FQCN,
            "deliverReminder", deliver_params,
            "deliveryDao", WARRANTY_REMINDER_DELIVERY_DAO, "markFailed",
            barrier_mode="workerMediated",
        )
    )
    return rows


def test_real_tracked_gr08m1_seed_file_loads_with_exactly_sixteen_rows():
    entries = _load_seed_entries(GR08M1_SEED_FILE)
    assert len(entries) == 16
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted(
        ["createSession"]
        + ["appendMessage"] * 2
        + ["clearSession", "clearAllHistory", "purgeOldMessages"]
        + ["start"]
        + ["recoverStaleRunningOperationRuns"] * 2
        + ["event", "finalizeNonCancellable", "increment"]
        + ["importLastSuccessJournalIfPresent"] * 2
        + ["importLastFailureJournalIfPresent"] * 2
    )
    for entry in entries:
        assert entry.path in (AI_CHAT_REPOSITORY_IMPL_KT,
                              OPERATION_RUN_RECORDER_KT,
                              RESTORE_JOURNAL_IMPORTER_KT)
        assert entry.owner_fqcn in (
            AI_CHAT_REPOSITORY_IMPL_FQCN,
            ROOM_OPERATION_RUN_RECORDER_FQCN,
            ROOM_OPERATION_RUN_RECORDER_HANDLE_FQCN,
            RESTORE_JOURNAL_IMPORTER_FQCN,
        )
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08M"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)
    # The Handle rows must spell the NESTED owner FQCN -- a row claiming the
    # outer RoomOperationRunRecorder owner for a Handle callable could never
    # match the v2 fingerprints.
    handle_rows = [
        entry for entry in entries
        if entry.method in ("event", "finalizeNonCancellable", "increment")
    ]
    assert sorted(entry.method for entry in handle_rows) == [
        "event", "finalizeNonCancellable", "increment",
    ]
    for entry in handle_rows:
        assert entry.owner_fqcn == ROOM_OPERATION_RUN_RECORDER_HANDLE_FQCN
    # ZERO closure rows for this part (every touched DAO is fully abstract).
    assert not any("CLOSURE" in entry.reason for entry in entries)


def test_real_tracked_gr08m2_seed_file_loads_with_exactly_twelve_rows():
    entries = _load_seed_entries(GR08M2_SEED_FILE)
    assert len(entries) == 12
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted(
        ["processReceiptInput"] * 4
        + ["processEmailReceipt"] * 2
        + ["doWork"] * 2
        + ["deliverReminder"] * 4
    )
    for entry in entries:
        assert entry.path in (RECEIPT_LIFECYCLE_COORDINATOR_KT,
                              WARRANTY_EXPIRATION_WORKER_KT)
        assert entry.owner_fqcn in (RECEIPT_LIFECYCLE_COORDINATOR_FQCN,
                                    WARRANTY_EXPIRATION_WORKER_FQCN)
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08M"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)
    # Barrier-mode split: the receipt-authority rows are helper (the write
    # barrier runs at the callable entrypoints); the worker rows are
    # workerMediated (WorkerExecutionGuard.runGuardedWithContext verified in
    # the doWork body -- the GR-08i3 worker-row convention).
    coordinator_rows = [
        entry for entry in entries
        if entry.path == RECEIPT_LIFECYCLE_COORDINATOR_KT
    ]
    worker_rows = [
        entry for entry in entries if entry.path == WARRANTY_EXPIRATION_WORKER_KT
    ]
    assert len(coordinator_rows) == 6
    assert len(worker_rows) == 6
    for entry in coordinator_rows:
        assert entry.barrier_mode is BarrierMode.HELPER
    for entry in worker_rows:
        assert entry.barrier_mode is BarrierMode.WORKER_MEDIATED
    # ZERO closure rows for this part (no body-carrying @Transaction
    # convenience method is invoked from any batch callable).
    assert not any("CLOSURE" in entry.reason for entry in entries)


def _gr08m_policy_entries(tmp_path, rows):
    entries = []
    for position, row in enumerate(rows):
        entry, errors = build_policy_entry(row, position)
        assert entry is not None and not errors, (
            "GR-08m fixture row must be schema-valid: %s" % (errors,)
        )
        entries.append(entry)
    return entries


def _assert_gr08m_exact_match(tmp_path, rows, select_method, select_accessor,
                              select_operation, select_parameters=None,
                              **overrides):
    """The exact GR-08m row identity matches; mutants never do.

    Target selection is fixed by ``(select_method, select_accessor,
    select_operation)`` -- optionally narrowed by ``select_parameters`` when
    several rows share the same (method, accessor, operation) triple (the
    GR-08m appendMessage / recoverStaleRunningOperationRuns /
    importLast*JournalIfPresent rows); ``overrides`` perturb exactly one
    identity field of the match query for the near-miss assertions.
    """
    entries = _gr08m_policy_entries(tmp_path, rows)
    candidates = [
        entry
        for entry in entries
        if entry.method == select_method
        and entry.dao_accessor == select_accessor
        and entry.operation == select_operation
    ]
    if select_parameters is not None:
        candidates = [
            entry
            for entry in candidates
            if tuple(entry.parameter_types) == tuple(select_parameters)
        ]
    target = candidates[0]
    kwargs = dict(
        path=target.path,
        owner_fqcn=target.owner_fqcn,
        kind=target.kind,
        method=target.method,
        receiver=target.receiver,
        parameter_types=target.parameter_types,
        dao_accessor=target.dao_accessor,
        dao_fqcn=target.dao_fqcn,
        operation=target.operation,
    )
    kwargs.update(overrides)
    return match_mutation(target, **kwargs)


def test_gr08m1_exact_identity_matches(tmp_path):
    rows = _gr08m1_seed_rows()
    assert (
        _assert_gr08m_exact_match(
            tmp_path, rows, "createSession", "sessionDao", "insert"
        )
        is True
    )
    assert (
        _assert_gr08m_exact_match(
            tmp_path, rows, "increment", "runDao", "incrementCounters"
        )
        is True
    )
    assert (
        _assert_gr08m_exact_match(
            tmp_path, rows, "importLastFailureJournalIfPresent",
            "operationRunEventDao", "insert",
        )
        is True
    )


def test_gr08m1_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08m1_seed_rows()
    assert (
        _assert_gr08m_exact_match(
            tmp_path, rows, "purgeOldMessages", "messageDao",
            "deleteOlderThan",
            parameter_types=("String",),
        )
        is False
    )


def test_gr08m1_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08m1_seed_rows()
    # The Handle rows must not match under the OUTER recorder owner.
    assert (
        _assert_gr08m_exact_match(
            tmp_path, rows, "event", "eventDao", "insert",
            owner_fqcn=ROOM_OPERATION_RUN_RECORDER_FQCN,
        )
        is False
    )
    assert (
        _assert_gr08m_exact_match(
            tmp_path, rows, "start", "runDao", "insert",
            owner_fqcn="com.example.OtherRunRecorder",
        )
        is False
    )


def test_gr08m1_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08m1_seed_rows()
    assert (
        _assert_gr08m_exact_match(
            tmp_path, rows, "clearAllHistory", "sessionDao", "deleteAll",
            dao_accessor="messageDao",
            dao_fqcn=AI_CHAT_MESSAGE_DAO,
        )
        is False
    )


def test_gr08m1_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08m1_seed_rows()
    assert (
        _assert_gr08m_exact_match(
            tmp_path, rows, "recoverStaleRunningOperationRuns", "runDao",
            "finalizeIfRunning",
            operation="incrementCounters",
        )
        is False
    )


def test_gr08m1_shared_fingerprint_and_multi_row_callables_near_misses(tmp_path):
    """The shared-fingerprint and two-row callables are exact per identity.

    importLastSuccessJournalIfPresent carries the SAME operationRunDao.insert
    fingerprint at TWO call sites (one seed row covers both findings), and
    appendMessage / recoverStaleRunningOperationRuns each carry TWO rows on
    different DAO accessors; each row authorizes EXACTLY its own (callable,
    accessor, operation) identity, so a swapped accessor or operation stays
    unauthorized.
    """
    rows = _gr08m1_seed_rows()
    # importLastSuccessJournalIfPresent: the runDao row never matches the
    # eventDao identity (and vice versa).
    assert (
        _assert_gr08m_exact_match(
            tmp_path, rows, "importLastSuccessJournalIfPresent",
            "operationRunDao", "insert",
            dao_accessor="operationRunEventDao",
            dao_fqcn=OPERATION_RUN_EVENT_DAO,
        )
        is False
    )
    assert (
        _assert_gr08m_exact_match(
            tmp_path, rows, "importLastSuccessJournalIfPresent",
            "operationRunEventDao", "insert",
            dao_accessor="operationRunDao",
            dao_fqcn=OPERATION_RUN_DAO,
        )
        is False
    )
    # appendMessage: the messageDao insert row never matches the sessionDao
    # updateLastTouched identity.
    assert (
        _assert_gr08m_exact_match(
            tmp_path, rows, "appendMessage", "messageDao", "insert",
            dao_accessor="sessionDao",
            dao_fqcn=AI_CHAT_SESSION_DAO,
        )
        is False
    )
    # recoverStaleRunningOperationRuns: the runDao finalizeIfRunning row
    # never matches the eventDao insert identity.
    assert (
        _assert_gr08m_exact_match(
            tmp_path, rows, "recoverStaleRunningOperationRuns", "runDao",
            "finalizeIfRunning",
            dao_accessor="eventDao",
            dao_fqcn=OPERATION_RUN_EVENT_DAO,
        )
        is False
    )
    # The success-path run row never matches the failure-path callable
    # identity (same DAO + operation, different method).
    assert (
        _assert_gr08m_exact_match(
            tmp_path, rows, "importLastSuccessJournalIfPresent",
            "operationRunDao", "insert",
            method="importLastFailureJournalIfPresent",
        )
        is False
    )


def test_gr08m2_exact_identity_matches(tmp_path):
    rows = _gr08m2_seed_rows()
    assert (
        _assert_gr08m_exact_match(
            tmp_path, rows, "processReceiptInput", "scannedReceiptDao",
            "delete"
        )
        is True
    )
    assert (
        _assert_gr08m_exact_match(
            tmp_path, rows, "deliverReminder", "deliveryDao", "claim"
        )
        is True
    )


def test_gr08m2_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08m2_seed_rows()
    assert (
        _assert_gr08m_exact_match(
            tmp_path, rows, "deliverReminder", "deliveryDao", "claim",
            parameter_types=("Long", "Int", "Long"),
        )
        is False
    )


def test_gr08m2_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08m2_seed_rows()
    assert (
        _assert_gr08m_exact_match(
            tmp_path, rows, "processEmailReceipt", "emailReceiptDao",
            "insertOrIgnore",
            owner_fqcn="com.example.OtherReceiptCoordinator",
        )
        is False
    )


def test_gr08m2_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08m2_seed_rows()
    assert (
        _assert_gr08m_exact_match(
            tmp_path, rows, "processReceiptInput", "pendingReviewDao",
            "insert",
            dao_accessor="scannedReceiptDao",
            dao_fqcn=SCANNED_RECEIPT_DAO_GR08M,
        )
        is False
    )


def test_gr08m2_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08m2_seed_rows()
    assert (
        _assert_gr08m_exact_match(
            tmp_path, rows, "deliverReminder", "deliveryDao",
            "markSentFromClaimed",
            operation="markFailed",
        )
        is False
    )


def test_gr08m2_multi_row_callables_near_misses_stay_unauthorized(tmp_path):
    """The multi-row callables are exact per (accessor, operation) identity.

    processReceiptInput carries FOUR rows (pendingReviewDao
    deleteByScannedReceiptId / insert, scannedReceiptDao delete / update)
    and processEmailReceipt carries TWO rows (emailReceiptDao insertOrIgnore,
    pendingReviewDao insert); each row authorizes EXACTLY its own identity,
    so a swapped operation or accessor stays unauthorized.
    """
    rows = _gr08m2_seed_rows()
    # processReceiptInput: the pendingReviewDao insert row never matches the
    # deleteByScannedReceiptId identity.
    assert (
        _assert_gr08m_exact_match(
            tmp_path, rows, "processReceiptInput", "pendingReviewDao",
            "insert",
            operation="deleteByScannedReceiptId",
        )
        is False
    )
    # processReceiptInput: the scannedReceiptDao delete row never matches
    # the update identity.
    assert (
        _assert_gr08m_exact_match(
            tmp_path, rows, "processReceiptInput", "scannedReceiptDao",
            "delete",
            operation="update",
        )
        is False
    )
    # processEmailReceipt: the emailReceiptDao row never matches the
    # pendingReviewDao identity behind the same callable.
    assert (
        _assert_gr08m_exact_match(
            tmp_path, rows, "processEmailReceipt", "emailReceiptDao",
            "insertOrIgnore",
            dao_accessor="pendingReviewDao",
            dao_fqcn=PENDING_REVIEW_DAO_GR08M,
        )
        is False
    )
    # doWork: the recoverStaleClaimed row never matches the deleteOlderThan
    # identity behind the same callable.
    assert (
        _assert_gr08m_exact_match(
            tmp_path, rows, "doWork", "deliveryDao", "recoverStaleClaimed",
            operation="deleteOlderThan",
        )
        is False
    )


# ── GR-08n (MIT-DB-08N): AiArtifactRepositoryImpl.kt + BudgetRepository.kt ───
# + MerchantLocationRepository.kt (GR-08n1) and ReceiptRepository.kt +
# InvestmentTracker.kt + SubscriptionManagerEngine.kt (GR-08n2).  The
# combined batch carries 29 findings / 29 unique fingerprints > the
# 25-fingerprint batch cap, so it was SPLIT into two file groups; the
# generation run consumes the COMBINED document GR-08-seeds.yml; these tests
# pin that the combined document stays the exact concatenation of the
# TWENTY-THREE reviewed batch seed files, and that the GR-08n rows authorize
# EXACTLY their callable identity + DAO + operation (wrong overload, wrong
# owner, wrong DAO, and wrong operation stay unauthorized).

GR08N1_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08n1-seed.yml"
GR08N2_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08n2-seed.yml"

AI_ARTIFACT_REPOSITORY_IMPL_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "AiArtifactRepositoryImpl.kt"
)
AI_ARTIFACT_REPOSITORY_IMPL_FQCN = (
    "com.yourname.expensetracker.data.repository.AiArtifactRepositoryImpl"
)
AI_ARTIFACT_DAO = "com.yourname.expensetracker.data.database.dao.AiArtifactDao"
AI_ARTIFACT_RECORD = "com.yourname.expensetracker.domain.dto.AiArtifactRecord"

BUDGET_REPOSITORY_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "BudgetRepository.kt"
)
BUDGET_REPOSITORY_FQCN = (
    "com.yourname.expensetracker.data.repository.BudgetRepository"
)
BUDGET_DAO_GR08N = "com.yourname.expensetracker.data.database.dao.BudgetDao"
DEBUG_BUDGET_SNAPSHOT = (
    "com.yourname.expensetracker.data.repository.BudgetRepository."
    "DebugBudgetSnapshot"
)

MERCHANT_LOCATION_REPOSITORY_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "MerchantLocationRepository.kt"
)
MERCHANT_LOCATION_REPOSITORY_FQCN = (
    "com.yourname.expensetracker.data.repository.MerchantLocationRepository"
)
MERCHANT_LOCATION_DAO = (
    "com.yourname.expensetracker.data.database.dao.MerchantLocationDao"
)
MERCHANT_LOCATION_CORRECTION = (
    "com.yourname.expensetracker.data.database.entity."
    "MerchantLocationCorrection"
)

RECEIPT_REPOSITORY_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "ReceiptRepository.kt"
)
RECEIPT_REPOSITORY_FQCN = (
    "com.yourname.expensetracker.data.repository.ReceiptRepository"
)
SCANNED_RECEIPT_DAO_GR08N = (
    "com.yourname.expensetracker.data.database.dao.ScannedReceiptDao"
)
RECEIPT_EVENT_DAO_GR08N = (
    "com.yourname.expensetracker.data.database.dao.ReceiptEventDao"
)
SCANNED_RECEIPT_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.ScannedReceipt"
)
CATEGORIZATION_STATUS = (
    "com.yourname.expensetracker.data.database.entity.CategorizationStatus"
)

INVESTMENT_TRACKER_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/investment/"
    "InvestmentTracker.kt"
)
INVESTMENT_TRACKER_FQCN = (
    "com.yourname.expensetracker.domain.investment.InvestmentTracker"
)
INVESTMENT_DAO = "com.yourname.expensetracker.data.database.dao.InvestmentDao"
INVESTMENT_VALUE_DAO = (
    "com.yourname.expensetracker.data.database.dao.InvestmentValueDao"
)
INVESTMENT_TRANSACTION_DAO = (
    "com.yourname.expensetracker.data.database.dao.InvestmentTransactionDao"
)
INVESTMENT_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.Investment"
)

SUBSCRIPTION_MANAGER_ENGINE_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/subscription/"
    "SubscriptionManagerEngine.kt"
)
SUBSCRIPTION_MANAGER_ENGINE_FQCN = (
    "com.yourname.expensetracker.domain.subscription.SubscriptionManagerEngine"
)
SUBSCRIPTION_PRICE_HISTORY_DAO = (
    "com.yourname.expensetracker.data.database.dao."
    "SubscriptionPriceHistoryDao"
)
SUBSCRIPTION_USAGE_DAO = (
    "com.yourname.expensetracker.data.database.dao.SubscriptionUsageDao"
)
SUBSCRIPTION_CANDIDATE_DAO = (
    "com.yourname.expensetracker.data.database.dao.SubscriptionCandidateDao"
)
CREATE_SUBSCRIPTION_REQUEST = (
    "com.yourname.expensetracker.domain.subscription.CreateSubscriptionRequest"
)
SUBSCRIPTION_CANDIDATE_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.SubscriptionCandidate"
)
RECURRENCE_FREQUENCY = (
    "com.yourname.expensetracker.domain.model.RecurrenceFrequency"
)


def _gr08n_seed_row(path, owner_fqcn, method, parameter_types, dao_accessor,
                    dao_fqcn, operation, barrier_mode="helper"):
    """One exact GR-08n-shaped v2 seed row mapping."""
    return {
        "path": path,
        "ownerFqcn": owner_fqcn,
        "kind": "function",
        "method": method,
        "receiver": None,
        "parameterTypes": list(parameter_types),
        "daoAccessor": dao_accessor,
        "daoFqcn": dao_fqcn,
        "operation": operation,
        "barrierMode": barrier_mode,
        "reason": "GR-08n EXACT_POLICY test row",
        "owner": "@panospao7",
        "linkedIssue": "MIT-DB-08N",
    }


def _gr08n1_seed_rows():
    """The sixteen exact GR-08n1 rows (mirroring the tracked seed file).

    AiArtifactRepositoryImpl.kt (5 rows) + BudgetRepository.kt (6 rows --
    5 findings-derived plus the restoreDebugSnapshot
    replaceAllAndEnforceActiveScopes CLOSURE row) +
    MerchantLocationRepository.kt (5 rows -- 4 findings-derived plus the
    saveCorrection upsertLocation CLOSURE row).
    """
    rows = []
    rows.append(
        _gr08n_seed_row(
            AI_ARTIFACT_REPOSITORY_IMPL_KT, AI_ARTIFACT_REPOSITORY_IMPL_FQCN,
            "upsert", (AI_ARTIFACT_RECORD,),
            "dao", AI_ARTIFACT_DAO, "upsert",
        )
    )
    for method in ("markDismissed", "markApplied", "deleteExpired"):
        rows.append(
            _gr08n_seed_row(
                AI_ARTIFACT_REPOSITORY_IMPL_KT,
                AI_ARTIFACT_REPOSITORY_IMPL_FQCN,
                method, ("Long",),
                "dao", AI_ARTIFACT_DAO, method,
            )
        )
    rows.append(
        _gr08n_seed_row(
            AI_ARTIFACT_REPOSITORY_IMPL_KT, AI_ARTIFACT_REPOSITORY_IMPL_FQCN,
            "deleteByTargetKey", ("String",),
            "dao", AI_ARTIFACT_DAO, "deleteByTargetKey",
        )
    )
    rows.append(
        _gr08n_seed_row(
            BUDGET_REPOSITORY_KT, BUDGET_REPOSITORY_FQCN,
            "deleteAll", (),
            "budgetDao", BUDGET_DAO_GR08N, "deleteAll",
        )
    )
    for operation in ("deleteAll", "replaceAllAndEnforceActiveScopes"):
        rows.append(
            _gr08n_seed_row(
                BUDGET_REPOSITORY_KT, BUDGET_REPOSITORY_FQCN,
                "restoreDebugSnapshot", (DEBUG_BUDGET_SNAPSHOT,),
                "budgetDao", BUDGET_DAO_GR08N, operation,
            )
        )
    for operation in ("updateExceededNotification", "updateCriticalNotification",
                      "updateWarningNotification"):
        rows.append(
            _gr08n_seed_row(
                BUDGET_REPOSITORY_KT, BUDGET_REPOSITORY_FQCN,
                operation, ("Long", "Long"),
                "budgetDao", BUDGET_DAO_GR08N, operation,
            )
        )
    rows.append(
        _gr08n_seed_row(
            MERCHANT_LOCATION_REPOSITORY_KT,
            MERCHANT_LOCATION_REPOSITORY_FQCN,
            "getCachedLocation", ("String",),
            "dao", MERCHANT_LOCATION_DAO, "incrementHitCount",
        )
    )
    rows.append(
        _gr08n_seed_row(
            MERCHANT_LOCATION_REPOSITORY_KT,
            MERCHANT_LOCATION_REPOSITORY_FQCN,
            "getCachedLocationForArea", ("String", "String"),
            "dao", MERCHANT_LOCATION_DAO, "incrementHitCountForArea",
        )
    )
    for operation in ("upsertCorrection", "upsertLocation"):
        rows.append(
            _gr08n_seed_row(
                MERCHANT_LOCATION_REPOSITORY_KT,
                MERCHANT_LOCATION_REPOSITORY_FQCN,
                "saveCorrection", (MERCHANT_LOCATION_CORRECTION,),
                "dao", MERCHANT_LOCATION_DAO, operation,
            )
        )
    rows.append(
        _gr08n_seed_row(
            MERCHANT_LOCATION_REPOSITORY_KT,
            MERCHANT_LOCATION_REPOSITORY_FQCN,
            "evictStaleCache", (),
            "dao", MERCHANT_LOCATION_DAO, "deleteStaleEntries",
        )
    )
    return rows


def _gr08n2_seed_rows():
    """The fifteen exact GR-08n2 rows (mirroring the tracked seed file).

    ReceiptRepository.kt (5 rows) + InvestmentTracker.kt (5 rows) +
    SubscriptionManagerEngine.kt (5 rows); ZERO closure rows -- all eight
    touched DAOs are fully abstract interfaces.
    """
    accept_params = (
        SUBSCRIPTION_CANDIDATE_ENTITY, RECURRENCE_FREQUENCY, "Long",
    )
    rows = []
    rows.append(
        _gr08n_seed_row(
            RECEIPT_REPOSITORY_KT, RECEIPT_REPOSITORY_FQCN,
            "updateCategorizationStatus", ("Long", CATEGORIZATION_STATUS),
            "scannedReceiptDao", SCANNED_RECEIPT_DAO_GR08N,
            "updateCategorizationStatus",
        )
    )
    rows.append(
        _gr08n_seed_row(
            RECEIPT_REPOSITORY_KT, RECEIPT_REPOSITORY_FQCN,
            "deleteReceipt", (SCANNED_RECEIPT_ENTITY,),
            "scannedReceiptDao", SCANNED_RECEIPT_DAO_GR08N, "delete",
        )
    )
    rows.append(
        _gr08n_seed_row(
            RECEIPT_REPOSITORY_KT, RECEIPT_REPOSITORY_FQCN,
            "clearAllScannedReceipts", (),
            "scannedReceiptDao", SCANNED_RECEIPT_DAO_GR08N, "deleteAll",
        )
    )
    rows.append(
        _gr08n_seed_row(
            RECEIPT_REPOSITORY_KT, RECEIPT_REPOSITORY_FQCN,
            "clearMatchForReceipt", ("Long",),
            "scannedReceiptDao", SCANNED_RECEIPT_DAO_GR08N, "update",
        )
    )
    rows.append(
        _gr08n_seed_row(
            RECEIPT_REPOSITORY_KT, RECEIPT_REPOSITORY_FQCN,
            "writeReceiptEvent",
            ("Long", "String", "Long", "String", "String", "String",
             "String", "String?"),
            "receiptEventDao", RECEIPT_EVENT_DAO_GR08N, "insert",
        )
    )
    for accessor, dao, operation in (
        ("investmentDao", INVESTMENT_DAO, "insert"),
        ("investmentValueDao", INVESTMENT_VALUE_DAO, "insert"),
        ("investmentTransactionDao", INVESTMENT_TRANSACTION_DAO, "insert"),
    ):
        rows.append(
            _gr08n_seed_row(
                INVESTMENT_TRACKER_KT, INVESTMENT_TRACKER_FQCN,
                "addHolding", (INVESTMENT_ENTITY,),
                accessor, dao, operation,
            )
        )
    rows.append(
        _gr08n_seed_row(
            INVESTMENT_TRACKER_KT, INVESTMENT_TRACKER_FQCN,
            "updatePrice", ("Long", "Double"),
            "investmentDao", INVESTMENT_DAO, "updatePrice",
        )
    )
    rows.append(
        _gr08n_seed_row(
            INVESTMENT_TRACKER_KT, INVESTMENT_TRACKER_FQCN,
            "updatePrice", ("Long", "Double"),
            "investmentValueDao", INVESTMENT_VALUE_DAO, "insert",
        )
    )
    rows.append(
        _gr08n_seed_row(
            SUBSCRIPTION_MANAGER_ENGINE_KT, SUBSCRIPTION_MANAGER_ENGINE_FQCN,
            "validateAndCreate", (CREATE_SUBSCRIPTION_REQUEST,),
            "priceHistoryDao", SUBSCRIPTION_PRICE_HISTORY_DAO, "insert",
        )
    )
    for accessor, dao, operation in (
        ("priceHistoryDao", SUBSCRIPTION_PRICE_HISTORY_DAO, "insert"),
        ("candidateDao", SUBSCRIPTION_CANDIDATE_DAO, "markAsConverted"),
    ):
        rows.append(
            _gr08n_seed_row(
                SUBSCRIPTION_MANAGER_ENGINE_KT,
                SUBSCRIPTION_MANAGER_ENGINE_FQCN,
                "acceptCandidate", accept_params,
                accessor, dao, operation,
            )
        )
    rows.append(
        _gr08n_seed_row(
            SUBSCRIPTION_MANAGER_ENGINE_KT, SUBSCRIPTION_MANAGER_ENGINE_FQCN,
            "recordUsage", ("Long", "Int?", "String?"),
            "usageDao", SUBSCRIPTION_USAGE_DAO, "insert",
        )
    )
    rows.append(
        _gr08n_seed_row(
            SUBSCRIPTION_MANAGER_ENGINE_KT, SUBSCRIPTION_MANAGER_ENGINE_FQCN,
            "recordPriceChange", ("Long", "Double", "String?"),
            "priceHistoryDao", SUBSCRIPTION_PRICE_HISTORY_DAO, "insert",
        )
    )
    return rows


def test_real_tracked_gr08n1_seed_file_loads_with_exactly_sixteen_rows():
    entries = _load_seed_entries(GR08N1_SEED_FILE)
    assert len(entries) == 16
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted(
        ["upsert", "markDismissed", "markApplied", "deleteExpired",
         "deleteByTargetKey"]
        + ["deleteAll"]
        + ["restoreDebugSnapshot"] * 2
        + ["updateExceededNotification", "updateCriticalNotification",
           "updateWarningNotification"]
        + ["getCachedLocation", "getCachedLocationForArea"]
        + ["saveCorrection"] * 2
        + ["evictStaleCache"]
    )
    for entry in entries:
        assert entry.path in (AI_ARTIFACT_REPOSITORY_IMPL_KT,
                              BUDGET_REPOSITORY_KT,
                              MERCHANT_LOCATION_REPOSITORY_KT)
        assert entry.owner_fqcn in (AI_ARTIFACT_REPOSITORY_IMPL_FQCN,
                                    BUDGET_REPOSITORY_FQCN,
                                    MERCHANT_LOCATION_REPOSITORY_FQCN)
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08N"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)
    # The two BudgetRepository deleteAll rows live in DIFFERENT callables
    # (deleteAll() and restoreDebugSnapshot()), so they are distinct keys.
    delete_all = [
        entry for entry in entries
        if entry.dao_accessor == "budgetDao" and entry.operation == "deleteAll"
    ]
    assert sorted(entry.method for entry in delete_all) == [
        "deleteAll", "restoreDebugSnapshot",
    ]
    # The TWO closure rows: the body-carrying @Transaction convenience
    # methods the findings scanner never reported (GR-08b/GR-08d/GR-08l1
    # blind-spot pattern).
    closure = sorted(
        (entry.method, entry.dao_accessor, entry.operation)
        for entry in entries if "CLOSURE" in entry.reason
    )
    assert closure == [
        ("restoreDebugSnapshot", "budgetDao",
         "replaceAllAndEnforceActiveScopes"),
        ("saveCorrection", "dao", "upsertLocation"),
    ]


def test_real_tracked_gr08n2_seed_file_loads_with_exactly_fifteen_rows():
    entries = _load_seed_entries(GR08N2_SEED_FILE)
    assert len(entries) == 15
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted(
        ["updateCategorizationStatus", "deleteReceipt",
         "clearAllScannedReceipts", "clearMatchForReceipt",
         "writeReceiptEvent"]
        + ["addHolding"] * 3
        + ["updatePrice"] * 2
        + ["validateAndCreate"]
        + ["acceptCandidate"] * 2
        + ["recordUsage", "recordPriceChange"]
    )
    for entry in entries:
        assert entry.path in (RECEIPT_REPOSITORY_KT,
                              INVESTMENT_TRACKER_KT,
                              SUBSCRIPTION_MANAGER_ENGINE_KT)
        assert entry.owner_fqcn in (RECEIPT_REPOSITORY_FQCN,
                                    INVESTMENT_TRACKER_FQCN,
                                    SUBSCRIPTION_MANAGER_ENGINE_FQCN)
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08N"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)
    # The three addHolding rows hit THREE different DAOs and the three
    # priceHistoryDao.insert rows live in THREE different engine callables,
    # so every finding is its own fingerprint/key.
    add_holding = sorted(
        entry.dao_fqcn for entry in entries if entry.method == "addHolding"
    )
    assert add_holding == [
        INVESTMENT_DAO, INVESTMENT_TRANSACTION_DAO, INVESTMENT_VALUE_DAO,
    ]
    price_history_inserts = sorted(
        entry.method for entry in entries
        if entry.dao_accessor == "priceHistoryDao"
        and entry.operation == "insert"
    )
    assert price_history_inserts == [
        "acceptCandidate", "recordPriceChange", "validateAndCreate",
    ]
    # ZERO closure rows for this part (all eight touched DAOs are fully
    # abstract interfaces).
    assert not any("CLOSURE" in entry.reason for entry in entries)


def _gr08n_policy_entries(tmp_path, rows):
    entries = []
    for position, row in enumerate(rows):
        entry, errors = build_policy_entry(row, position)
        assert entry is not None and not errors, (
            "GR-08n fixture row must be schema-valid: %s" % (errors,)
        )
        entries.append(entry)
    return entries


def _assert_gr08n_exact_match(tmp_path, rows, select_method, select_accessor,
                              select_operation, select_parameters=None,
                              **overrides):
    """The exact GR-08n row identity matches; mutants never do.

    Target selection is fixed by ``(select_method, select_accessor,
    select_operation)`` -- optionally narrowed by ``select_parameters`` when
    several rows share the same (method, accessor, operation) triple (the
    GR-08n addHolding / updatePrice / acceptCandidate rows); ``overrides``
    perturb exactly one identity field of the match query for the near-miss
    assertions.
    """
    entries = _gr08n_policy_entries(tmp_path, rows)
    candidates = [
        entry
        for entry in entries
        if entry.method == select_method
        and entry.dao_accessor == select_accessor
        and entry.operation == select_operation
    ]
    if select_parameters is not None:
        candidates = [
            entry
            for entry in candidates
            if tuple(entry.parameter_types) == tuple(select_parameters)
        ]
    target = candidates[0]
    kwargs = dict(
        path=target.path,
        owner_fqcn=target.owner_fqcn,
        kind=target.kind,
        method=target.method,
        receiver=target.receiver,
        parameter_types=target.parameter_types,
        dao_accessor=target.dao_accessor,
        dao_fqcn=target.dao_fqcn,
        operation=target.operation,
    )
    kwargs.update(overrides)
    return match_mutation(target, **kwargs)


def test_gr08n1_exact_identity_matches(tmp_path):
    rows = _gr08n1_seed_rows()
    assert (
        _assert_gr08n_exact_match(
            tmp_path, rows, "upsert", "dao", "upsert"
        )
        is True
    )
    assert (
        _assert_gr08n_exact_match(
            tmp_path, rows, "deleteAll", "budgetDao", "deleteAll",
            select_parameters=(),
        )
        is True
    )
    assert (
        _assert_gr08n_exact_match(
            tmp_path, rows, "evictStaleCache", "dao", "deleteStaleEntries",
            select_parameters=(),
        )
        is True
    )


def test_gr08n1_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08n1_seed_rows()
    assert (
        _assert_gr08n_exact_match(
            tmp_path, rows, "updateExceededNotification", "budgetDao",
            "updateExceededNotification",
            parameter_types=("Long", "String"),
        )
        is False
    )


def test_gr08n1_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08n1_seed_rows()
    assert (
        _assert_gr08n_exact_match(
            tmp_path, rows, "upsert", "dao", "upsert",
            owner_fqcn="com.example.OtherArtifactRepository",
        )
        is False
    )
    assert (
        _assert_gr08n_exact_match(
            tmp_path, rows, "getCachedLocation", "dao", "incrementHitCount",
            owner_fqcn="com.example.OtherLocationRepository",
        )
        is False
    )


def test_gr08n1_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08n1_seed_rows()
    assert (
        _assert_gr08n_exact_match(
            tmp_path, rows, "deleteAll", "budgetDao", "deleteAll",
            select_parameters=(),
            dao_accessor="dao",
            dao_fqcn=AI_ARTIFACT_DAO,
        )
        is False
    )


def test_gr08n1_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08n1_seed_rows()
    assert (
        _assert_gr08n_exact_match(
            tmp_path, rows, "markDismissed", "dao", "markDismissed",
            operation="markApplied",
        )
        is False
    )
    assert (
        _assert_gr08n_exact_match(
            tmp_path, rows, "saveCorrection", "dao", "upsertCorrection",
            operation="upsertLocation",
        )
        is False
    )


def test_gr08n1_shared_operation_distinct_callables_near_misses(tmp_path):
    """The two deleteAll rows are exact per callable identity.

    deleteAll() and restoreDebugSnapshot() both carry a budgetDao deleteAll
    row; each row authorizes EXACTLY its own callable, so a swapped method
    or parameter shape stays unauthorized.
    """
    rows = _gr08n1_seed_rows()
    # The deleteAll() row never matches the restoreDebugSnapshot identity.
    assert (
        _assert_gr08n_exact_match(
            tmp_path, rows, "deleteAll", "budgetDao", "deleteAll",
            select_parameters=(),
            method="restoreDebugSnapshot",
        )
        is False
    )
    # The restoreDebugSnapshot row never matches the parameterless
    # deleteAll() query shape (the query's parameter_types override makes
    # the match query claim the deleteAll() identity).
    assert (
        _assert_gr08n_exact_match(
            tmp_path, rows, "restoreDebugSnapshot", "budgetDao", "deleteAll",
            parameter_types=(),
        )
        is False
    )


def test_gr08n2_exact_identity_matches(tmp_path):
    rows = _gr08n2_seed_rows()
    assert (
        _assert_gr08n_exact_match(
            tmp_path, rows, "addHolding", "investmentDao", "insert"
        )
        is True
    )
    assert (
        _assert_gr08n_exact_match(
            tmp_path, rows, "acceptCandidate", "candidateDao",
            "markAsConverted"
        )
        is True
    )
    assert (
        _assert_gr08n_exact_match(
            tmp_path, rows, "writeReceiptEvent", "receiptEventDao", "insert"
        )
        is True
    )


def test_gr08n2_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08n2_seed_rows()
    assert (
        _assert_gr08n_exact_match(
            tmp_path, rows, "updatePrice", "investmentDao", "updatePrice",
            parameter_types=("Long", "Float"),
        )
        is False
    )


def test_gr08n2_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08n2_seed_rows()
    assert (
        _assert_gr08n_exact_match(
            tmp_path, rows, "recordUsage", "usageDao", "insert",
            owner_fqcn="com.example.OtherSubscriptionEngine",
        )
        is False
    )
    assert (
        _assert_gr08n_exact_match(
            tmp_path, rows, "clearMatchForReceipt", "scannedReceiptDao",
            "update",
            owner_fqcn="com.yourname.expensetracker.domain.receipt.lifecycle."
                       "ReceiptMatchLifecycleService",
        )
        is False
    )


def test_gr08n2_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08n2_seed_rows()
    assert (
        _assert_gr08n_exact_match(
            tmp_path, rows, "addHolding", "investmentValueDao", "insert",
            dao_accessor="investmentDao",
            dao_fqcn=INVESTMENT_DAO,
        )
        is False
    )


def test_gr08n2_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08n2_seed_rows()
    assert (
        _assert_gr08n_exact_match(
            tmp_path, rows, "updatePrice", "investmentDao", "updatePrice",
            operation="insert",
        )
        is False
    )
    assert (
        _assert_gr08n_exact_match(
            tmp_path, rows, "acceptCandidate", "candidateDao",
            "markAsConverted",
            operation="insert",
        )
        is False
    )


def test_gr08n2_multi_row_callables_near_misses_stay_unauthorized(tmp_path):
    """The multi-row callables are exact per (accessor, operation) identity.

    addHolding carries THREE rows (investmentDao / investmentValueDao /
    investmentTransactionDao inserts), updatePrice carries TWO rows
    (investmentDao updatePrice, investmentValueDao insert) and
    acceptCandidate carries TWO rows (priceHistoryDao insert,
    candidateDao markAsConverted); each row authorizes EXACTLY its own
    identity, so a swapped operation or accessor stays unauthorized.
    """
    rows = _gr08n2_seed_rows()
    # addHolding: the investmentValueDao row never matches the
    # investmentTransactionDao identity.
    assert (
        _assert_gr08n_exact_match(
            tmp_path, rows, "addHolding", "investmentValueDao", "insert",
            dao_accessor="investmentTransactionDao",
            dao_fqcn=INVESTMENT_TRANSACTION_DAO,
        )
        is False
    )
    # updatePrice: the investmentDao updatePrice row never matches the
    # investmentValueDao insert identity behind the same callable.
    assert (
        _assert_gr08n_exact_match(
            tmp_path, rows, "updatePrice", "investmentDao", "updatePrice",
            dao_accessor="investmentValueDao",
            dao_fqcn=INVESTMENT_VALUE_DAO,
            operation="insert",
        )
        is False
    )
    # acceptCandidate: the candidateDao row never matches the
    # priceHistoryDao identity behind the same callable.
    assert (
        _assert_gr08n_exact_match(
            tmp_path, rows, "acceptCandidate", "candidateDao",
            "markAsConverted",
            dao_accessor="priceHistoryDao",
            dao_fqcn=SUBSCRIPTION_PRICE_HISTORY_DAO,
            operation="insert",
        )
        is False
    )
    # validateAndCreate: the priceHistoryDao row never matches the
    # acceptCandidate identity behind the same accessor + operation.
    assert (
        _assert_gr08n_exact_match(
            tmp_path, rows, "validateAndCreate", "priceHistoryDao", "insert",
            method="acceptCandidate",
        )
        is False
    )


# ── GR-08o (MIT-DB-08O): ReceiptSideEffectPlanner.kt + CategoryRepository.kt ─
# + ReceiptItemCategorizationRepository.kt + SharedExpenseDataPortAdapter.kt +
# BankApiIntegration.kt + FinancialHealthScoreV2.kt +
# SourceLinkBackfillWorker.kt.  The combined batch carries 22 findings /
# 21 unique fingerprints (the two processMatchResult receiptEventDao.insert
# sites share one fingerprint) <= the 25-fingerprint batch cap, so NO split
# was required; the generation run consumes the COMBINED document
# GR-08-seeds.yml; these tests pin that the combined document stays the
# exact concatenation of the TWENTY-FOUR reviewed batch seed files, and that
# the GR-08o rows authorize EXACTLY their callable identity + DAO +
# operation (wrong overload, wrong owner, wrong DAO, and wrong operation
# stay unauthorized).  The GR-08o post-promotion rescan left TWO residual
# findings popularly labeled "CategoryRepository" -- actually
# MerchantCategoryRepository.kt (deleteAll/dao/deleteAll and
# insert/dao/insert; CategoryRepository.kt carries neither callable and
# stays at 0) -- closed by 2 residual closure rows (24 rows total; the
# GR-08l1 suffix-substring grouping-artifact precedent).

GR08O_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08o-seed.yml"

RECEIPT_SIDE_EFFECT_PLANNER_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/"
    "ReceiptSideEffectPlanner.kt"
)
RECEIPT_SIDE_EFFECT_PLANNER_FQCN = (
    "com.yourname.expensetracker.domain.receipt.lifecycle."
    "ReceiptSideEffectPlanner"
)
SCANNED_RECEIPT_DAO_GR08O = (
    "com.yourname.expensetracker.data.database.dao.ScannedReceiptDao"
)
RECEIPT_EVENT_DAO_GR08O = (
    "com.yourname.expensetracker.data.database.dao.ReceiptEventDao"
)
MATCH_RESULT = (
    "com.yourname.expensetracker.domain.receiptmatching.MatchResult"
)
SCANNED_RECEIPT_ENTITY_GR08O = (
    "com.yourname.expensetracker.data.database.entity.ScannedReceipt"
)

CATEGORY_REPOSITORY_KT_GR08O = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "CategoryRepository.kt"
)
CATEGORY_REPOSITORY_FQCN_GR08O = (
    "com.yourname.expensetracker.data.repository.CategoryRepository"
)
CATEGORY_DAO_GR08O = "com.yourname.expensetracker.data.database.dao.CategoryDao"
MERCHANT_CATEGORY_DAO_GR08O = (
    "com.yourname.expensetracker.data.database.dao.MerchantCategoryDao"
)

# GR-08o residual closure file (GR-08o post-promotion rescan): the two
# findings popularly labeled "CategoryRepository" live in THIS file --
# CategoryRepository.kt carries neither callable and stays at 0.
MERCHANT_CATEGORY_REPOSITORY_KT_GR08O = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "MerchantCategoryRepository.kt"
)
MERCHANT_CATEGORY_REPOSITORY_FQCN_GR08O = (
    "com.yourname.expensetracker.data.repository.MerchantCategoryRepository"
)
MERCHANT_CATEGORY_ENTITY_GR08O = (
    "com.yourname.expensetracker.data.database.entity.MerchantCategory"
)

RECEIPT_ITEM_CATEGORIZATION_REPOSITORY_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "ReceiptItemCategorizationRepository.kt"
)
RECEIPT_ITEM_CATEGORIZATION_REPOSITORY_FQCN = (
    "com.yourname.expensetracker.data.repository."
    "ReceiptItemCategorizationRepository"
)
RECEIPT_ITEM_CATEGORIZATION_DAO = (
    "com.yourname.expensetracker.data.database.dao."
    "ReceiptItemCategorizationDao"
)
RECEIPT_ITEM_CATEGORIZATION_RESULT = (
    "com.yourname.expensetracker.domain.ai.model."
    "ReceiptItemCategorizationResult"
)

SHARED_EXPENSE_DATA_PORT_ADAPTER_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "SharedExpenseDataPortAdapter.kt"
)
SHARED_EXPENSE_DATA_PORT_ADAPTER_FQCN = (
    "com.yourname.expensetracker.data.repository.SharedExpenseDataPortAdapter"
)
GROUP_MEMBER_DAO_GR08O = (
    "com.yourname.expensetracker.data.database.dao.GroupMemberDao"
)
EXPENSE_GROUP_DAO_GR08O = (
    "com.yourname.expensetracker.data.database.dao.ExpenseGroupDao"
)
SHARED_EXPENSE_MEMBER = (
    "com.yourname.expensetracker.domain.groups.SharedExpenseMember"
)

BANK_API_INTEGRATION_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/bank/"
    "BankApiIntegration.kt"
)
BANK_API_INTEGRATION_FQCN = (
    "com.yourname.expensetracker.domain.bank.BankApiIntegration"
)
BANK_CONNECTION_DAO_GR08O = (
    "com.yourname.expensetracker.data.database.dao.BankConnectionDao"
)
PENDING_REVIEW_DAO_GR08O = (
    "com.yourname.expensetracker.data.database.dao.PendingReviewDao"
)
BANK_CONNECTION_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.BankConnection"
)

FINANCIAL_HEALTH_SCORE_V2_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/health/"
    "FinancialHealthScoreV2.kt"
)
FINANCIAL_HEALTH_SCORE_V2_FQCN = (
    "com.yourname.expensetracker.domain.health.FinancialHealthScoreV2"
)
HEALTH_SCORE_HISTORY_DAO = (
    "com.yourname.expensetracker.data.database.dao.HealthScoreHistoryDao"
)
HEALTH_TREND = "com.yourname.expensetracker.domain.health.HealthTrend"
SAVE_TO_HISTORY_PARAMS = (
    "Int", "Int", "Int", "Int", "Int", "Long", "Long", HEALTH_TREND,
    "String?",
)

SOURCE_LINK_BACKFILL_WORKER_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/provenance/"
    "SourceLinkBackfillWorker.kt"
)
SOURCE_LINK_BACKFILL_WORKER_FQCN = (
    "com.yourname.expensetracker.domain.provenance.SourceLinkBackfillWorker"
)
ENTITY_SOURCE_LINK_DAO = (
    "com.yourname.expensetracker.data.database.dao.EntitySourceLinkDao"
)
EXPENSE_ENTITY_GR08O = (
    "com.yourname.expensetracker.data.database.entity.Expense"
)
ENTITY_SOURCE_LINK_LIST = (
    "List<com.yourname.expensetracker.data.database.entity.EntitySourceLink>"
)
BACKFILL_PARAMS = (EXPENSE_ENTITY_GR08O, ENTITY_SOURCE_LINK_LIST)


def _gr08o_seed_row(path, owner_fqcn, method, parameter_types, dao_accessor,
                    dao_fqcn, operation, barrier_mode="helper"):
    """One exact GR-08o-shaped v2 seed row mapping."""
    return {
        "path": path,
        "ownerFqcn": owner_fqcn,
        "kind": "function",
        "method": method,
        "receiver": None,
        "parameterTypes": list(parameter_types),
        "daoAccessor": dao_accessor,
        "daoFqcn": dao_fqcn,
        "operation": operation,
        "barrierMode": barrier_mode,
        "reason": "GR-08o EXACT_POLICY test row",
        "owner": "@panospao7",
        "linkedIssue": "MIT-DB-08O",
    }


def _gr08o_seed_rows():
    """The twenty-four exact GR-08o rows (mirroring the tracked seed file).

    ReceiptSideEffectPlanner.kt (3 rows) + CategoryRepository.kt (4 rows --
    3 findings-derived plus the ensureDefaultCategories
    seedDefaultsIfEmpty CLOSURE row) + ReceiptItemCategorizationRepository.kt
    (3 rows) + SharedExpenseDataPortAdapter.kt (3 rows) +
    BankApiIntegration.kt (3 rows) + FinancialHealthScoreV2.kt (3 rows) +
    SourceLinkBackfillWorker.kt (3 rows) + TWO residual closure rows (the
    GR-08o post-promotion rescan: MerchantCategoryRepository.kt
    deleteAll/dao/deleteAll and insert/dao/insert -- true path in
    MerchantCategoryRepository.kt, NOT CategoryRepository.kt).
    """
    process_match_params = (MATCH_RESULT, SCANNED_RECEIPT_ENTITY_GR08O)
    write_match_event_params = (
        SCANNED_RECEIPT_ENTITY_GR08O, "String", "String", "Long?", "Float?",
        "String?",
    )
    rows = []
    rows.append(
        _gr08o_seed_row(
            RECEIPT_SIDE_EFFECT_PLANNER_KT, RECEIPT_SIDE_EFFECT_PLANNER_FQCN,
            "processMatchResult", process_match_params,
            "scannedReceiptDao", SCANNED_RECEIPT_DAO_GR08O, "update",
        )
    )
    rows.append(
        _gr08o_seed_row(
            RECEIPT_SIDE_EFFECT_PLANNER_KT, RECEIPT_SIDE_EFFECT_PLANNER_FQCN,
            "processMatchResult", process_match_params,
            "receiptEventDao", RECEIPT_EVENT_DAO_GR08O, "insert",
        )
    )
    rows.append(
        _gr08o_seed_row(
            RECEIPT_SIDE_EFFECT_PLANNER_KT, RECEIPT_SIDE_EFFECT_PLANNER_FQCN,
            "writeMatchEvent", write_match_event_params,
            "receiptEventDao", RECEIPT_EVENT_DAO_GR08O, "insert",
        )
    )
    for accessor, dao, operation in (
        ("merchantCategoryDao", MERCHANT_CATEGORY_DAO_GR08O, "insertAll"),
        ("categoryDao", CATEGORY_DAO_GR08O, "insert"),
        ("merchantCategoryDao", MERCHANT_CATEGORY_DAO_GR08O,
         "updateNormalizedCanonicalName"),
        # CLOSURE row: body-carrying @Transaction CategoryDao convenience
        # method the findings scanner never reported.
        ("categoryDao", CATEGORY_DAO_GR08O, "seedDefaultsIfEmpty"),
    ):
        rows.append(
            _gr08o_seed_row(
                CATEGORY_REPOSITORY_KT_GR08O, CATEGORY_REPOSITORY_FQCN_GR08O,
                "ensureDefaultCategories", (),
                accessor, dao, operation,
            )
        )
    rows.append(
        _gr08o_seed_row(
            RECEIPT_ITEM_CATEGORIZATION_REPOSITORY_KT,
            RECEIPT_ITEM_CATEGORIZATION_REPOSITORY_FQCN,
            "deleteByReceiptId", ("Long",),
            "dao", RECEIPT_ITEM_CATEGORIZATION_DAO, "deleteByReceiptId",
        )
    )
    rows.append(
        _gr08o_seed_row(
            RECEIPT_ITEM_CATEGORIZATION_REPOSITORY_KT,
            RECEIPT_ITEM_CATEGORIZATION_REPOSITORY_FQCN,
            "saveCategorizationResult",
            ("Long", RECEIPT_ITEM_CATEGORIZATION_RESULT, "Long"),
            "dao", RECEIPT_ITEM_CATEGORIZATION_DAO, "insert",
        )
    )
    rows.append(
        _gr08o_seed_row(
            RECEIPT_ITEM_CATEGORIZATION_REPOSITORY_KT,
            RECEIPT_ITEM_CATEGORIZATION_REPOSITORY_FQCN,
            "updateUserCorrection", ("Long", "Long?", "String?", "Long"),
            "dao", RECEIPT_ITEM_CATEGORIZATION_DAO, "updateUserCorrection",
        )
    )
    rows.append(
        _gr08o_seed_row(
            SHARED_EXPENSE_DATA_PORT_ADAPTER_KT,
            SHARED_EXPENSE_DATA_PORT_ADAPTER_FQCN,
            "removeMember", (SHARED_EXPENSE_MEMBER,),
            "memberDao", GROUP_MEMBER_DAO_GR08O, "update",
        )
    )
    for operation in ("archiveGroup", "restoreGroup"):
        rows.append(
            _gr08o_seed_row(
                SHARED_EXPENSE_DATA_PORT_ADAPTER_KT,
                SHARED_EXPENSE_DATA_PORT_ADAPTER_FQCN,
                operation, ("Long",),
                "groupDao", EXPENSE_GROUP_DAO_GR08O, operation,
            )
        )
    rows.append(
        _gr08o_seed_row(
            BANK_API_INTEGRATION_KT, BANK_API_INTEGRATION_FQCN,
            "completeConnection", ("String", "String"),
            "bankConnectionDao", BANK_CONNECTION_DAO_GR08O, "insert",
        )
    )
    rows.append(
        _gr08o_seed_row(
            BANK_API_INTEGRATION_KT, BANK_API_INTEGRATION_FQCN,
            "syncTransactions", (BANK_CONNECTION_ENTITY, "Long?"),
            "pendingReviewDao", PENDING_REVIEW_DAO_GR08O, "insert",
        )
    )
    rows.append(
        _gr08o_seed_row(
            BANK_API_INTEGRATION_KT, BANK_API_INTEGRATION_FQCN,
            "refreshToken", (BANK_CONNECTION_ENTITY,),
            "bankConnectionDao", BANK_CONNECTION_DAO_GR08O, "updateToken",
        )
    )
    for operation in ("update", "insert", "deleteOlderThan"):
        rows.append(
            _gr08o_seed_row(
                FINANCIAL_HEALTH_SCORE_V2_KT, FINANCIAL_HEALTH_SCORE_V2_FQCN,
                "saveToHistory", SAVE_TO_HISTORY_PARAMS,
                "healthScoreHistoryDao", HEALTH_SCORE_HISTORY_DAO, operation,
            )
        )
    for method in (
        "backfillLegacySource", "backfillReceiptLinks",
        "backfillNotificationLinks",
    ):
        rows.append(
            _gr08o_seed_row(
                SOURCE_LINK_BACKFILL_WORKER_KT,
                SOURCE_LINK_BACKFILL_WORKER_FQCN,
                method, BACKFILL_PARAMS,
                "sourceLinkDao", ENTITY_SOURCE_LINK_DAO, "insert",
            )
        )
    # Residual closure rows (GR-08o post-promotion rescan): the two
    # findings popularly labeled "CategoryRepository" live in
    # MerchantCategoryRepository.kt; CategoryRepository.kt stays at 0.
    rows.append(
        _gr08o_seed_row(
            MERCHANT_CATEGORY_REPOSITORY_KT_GR08O,
            MERCHANT_CATEGORY_REPOSITORY_FQCN_GR08O,
            "deleteAll", (),
            "dao", MERCHANT_CATEGORY_DAO_GR08O, "deleteAll",
        )
    )
    rows.append(
        _gr08o_seed_row(
            MERCHANT_CATEGORY_REPOSITORY_KT_GR08O,
            MERCHANT_CATEGORY_REPOSITORY_FQCN_GR08O,
            "insert", (MERCHANT_CATEGORY_ENTITY_GR08O,),
            "dao", MERCHANT_CATEGORY_DAO_GR08O, "insert",
        )
    )
    return rows


def test_real_tracked_gr08o_seed_file_loads_with_exactly_twenty_four_rows():
    entries = _load_seed_entries(GR08O_SEED_FILE)
    assert len(entries) == 24
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted(
        ["processMatchResult"] * 2
        + ["writeMatchEvent"]
        + ["ensureDefaultCategories"] * 4
        + ["deleteByReceiptId", "saveCategorizationResult",
           "updateUserCorrection"]
        + ["removeMember", "archiveGroup", "restoreGroup"]
        + ["completeConnection", "syncTransactions", "refreshToken"]
        + ["saveToHistory"] * 3
        + ["backfillLegacySource", "backfillReceiptLinks",
           "backfillNotificationLinks"]
        + ["deleteAll", "insert"]
    )
    for entry in entries:
        assert entry.path in (
            RECEIPT_SIDE_EFFECT_PLANNER_KT,
            CATEGORY_REPOSITORY_KT_GR08O,
            RECEIPT_ITEM_CATEGORIZATION_REPOSITORY_KT,
            SHARED_EXPENSE_DATA_PORT_ADAPTER_KT,
            BANK_API_INTEGRATION_KT,
            FINANCIAL_HEALTH_SCORE_V2_KT,
            SOURCE_LINK_BACKFILL_WORKER_KT,
            MERCHANT_CATEGORY_REPOSITORY_KT_GR08O,
        )
        assert entry.owner_fqcn in (
            RECEIPT_SIDE_EFFECT_PLANNER_FQCN,
            CATEGORY_REPOSITORY_FQCN_GR08O,
            RECEIPT_ITEM_CATEGORIZATION_REPOSITORY_FQCN,
            SHARED_EXPENSE_DATA_PORT_ADAPTER_FQCN,
            BANK_API_INTEGRATION_FQCN,
            FINANCIAL_HEALTH_SCORE_V2_FQCN,
            SOURCE_LINK_BACKFILL_WORKER_FQCN,
            MERCHANT_CATEGORY_REPOSITORY_FQCN_GR08O,
        )
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.barrier_mode is BarrierMode.HELPER
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08O"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)
    # The two processMatchResult rows hit TWO different DAOs and the three
    # saveToHistory rows carry THREE distinct operations behind the same
    # callable, so every row is its own key.
    process_match = sorted(
        entry.dao_fqcn for entry in entries
        if entry.method == "processMatchResult"
    )
    assert process_match == [
        RECEIPT_EVENT_DAO_GR08O, SCANNED_RECEIPT_DAO_GR08O,
    ]
    save_history_ops = sorted(
        entry.operation for entry in entries
        if entry.method == "saveToHistory"
    )
    assert save_history_ops == ["deleteOlderThan", "insert", "update"]
    # The THREE closure rows: the body-carrying @Transaction CategoryDao
    # convenience method the findings scanner never reported
    # (GR-08b/GR-08d/GR-08l1/GR-08n1 blind-spot pattern) plus the TWO
    # GR-08o post-promotion residual rows.
    closure = sorted(
        (entry.method, entry.dao_accessor, entry.operation)
        for entry in entries if "CLOSURE" in entry.reason
    )
    assert closure == [
        ("deleteAll", "dao", "deleteAll"),
        ("ensureDefaultCategories", "categoryDao", "seedDefaultsIfEmpty"),
        ("insert", "dao", "insert"),
    ]
    # The residual rows spell the TRUE paths: no closure row may claim the
    # CategoryRepository.kt path for the residual callables (a
    # CategoryRepository.kt row could never match the v2 fingerprints).
    residual_paths = sorted(
        entry.path
        for entry in entries
        if entry.method in ("deleteAll", "insert")
    )
    assert residual_paths == [MERCHANT_CATEGORY_REPOSITORY_KT_GR08O] * 2


def _gr08o_policy_entries(tmp_path, rows):
    entries = []
    for position, row in enumerate(rows):
        entry, errors = build_policy_entry(row, position)
        assert entry is not None and not errors, (
            "GR-08o fixture row must be schema-valid: %s" % (errors,)
        )
        entries.append(entry)
    return entries


def _assert_gr08o_exact_match(tmp_path, rows, select_method,
                              select_accessor, select_operation,
                              select_parameters=None, **overrides):
    """The exact GR-08o row identity matches; mutants never do.

    Target selection is fixed by ``(select_method, select_accessor,
    select_operation)`` -- optionally narrowed by ``select_parameters`` when
    several rows share the same (method, accessor, operation) triple (the
    GR-08o ensureDefaultCategories / backfill rows); ``overrides`` perturb
    exactly one identity field of the match query for the near-miss
    assertions.
    """
    entries = _gr08o_policy_entries(tmp_path, rows)
    candidates = [
        entry
        for entry in entries
        if entry.method == select_method
        and entry.dao_accessor == select_accessor
        and entry.operation == select_operation
    ]
    if select_parameters is not None:
        candidates = [
            entry
            for entry in candidates
            if tuple(entry.parameter_types) == tuple(select_parameters)
        ]
    target = candidates[0]
    kwargs = dict(
        path=target.path,
        owner_fqcn=target.owner_fqcn,
        kind=target.kind,
        method=target.method,
        receiver=target.receiver,
        parameter_types=target.parameter_types,
        dao_accessor=target.dao_accessor,
        dao_fqcn=target.dao_fqcn,
        operation=target.operation,
    )
    kwargs.update(overrides)
    return match_mutation(target, **kwargs)


def test_gr08o_exact_identity_matches(tmp_path):
    rows = _gr08o_seed_rows()
    assert (
        _assert_gr08o_exact_match(
            tmp_path, rows, "processMatchResult", "scannedReceiptDao",
            "update",
        )
        is True
    )
    assert (
        _assert_gr08o_exact_match(
            tmp_path, rows, "ensureDefaultCategories", "categoryDao",
            "seedDefaultsIfEmpty", select_parameters=(),
        )
        is True
    )
    assert (
        _assert_gr08o_exact_match(
            tmp_path, rows, "backfillNotificationLinks", "sourceLinkDao",
            "insert",
        )
        is True
    )


def test_gr08o_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08o_seed_rows()
    assert (
        _assert_gr08o_exact_match(
            tmp_path, rows, "updateUserCorrection", "dao",
            "updateUserCorrection",
            parameter_types=("Long", "Long?", "String?", "String"),
        )
        is False
    )


def test_gr08o_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08o_seed_rows()
    assert (
        _assert_gr08o_exact_match(
            tmp_path, rows, "processMatchResult", "receiptEventDao",
            "insert",
            owner_fqcn="com.yourname.expensetracker.domain.receipt.lifecycle."
                       "ReceiptMatchLifecycleService",
        )
        is False
    )
    assert (
        _assert_gr08o_exact_match(
            tmp_path, rows, "saveToHistory", "healthScoreHistoryDao",
            "insert",
            owner_fqcn="com.example.OtherHealthEngine",
        )
        is False
    )


def test_gr08o_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08o_seed_rows()
    assert (
        _assert_gr08o_exact_match(
            tmp_path, rows, "ensureDefaultCategories", "categoryDao",
            "insert", select_parameters=(),
            dao_accessor="merchantCategoryDao",
            dao_fqcn=MERCHANT_CATEGORY_DAO_GR08O,
        )
        is False
    )


def test_gr08o_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08o_seed_rows()
    assert (
        _assert_gr08o_exact_match(
            tmp_path, rows, "refreshToken", "bankConnectionDao",
            "updateToken",
            operation="insert",
        )
        is False
    )
    assert (
        _assert_gr08o_exact_match(
            tmp_path, rows, "archiveGroup", "groupDao", "archiveGroup",
            operation="restoreGroup",
        )
        is False
    )


def test_gr08o_shared_operation_distinct_callables_near_misses(tmp_path):
    """The multi-row callables are exact per (accessor, operation) identity.

    ensureDefaultCategories carries FOUR rows (merchantCategoryDao insertAll,
    categoryDao insert, merchantCategoryDao updateNormalizedCanonicalName,
    categoryDao seedDefaultsIfEmpty) and the three backfill callables share
    the sourceLinkDao insert identity; each row authorizes EXACTLY its own
    callable identity, so a swapped method or accessor stays unauthorized.
    """
    rows = _gr08o_seed_rows()
    # The seedDefaultsIfEmpty closure row never matches the plain
    # categoryDao insert identity behind the same callable.
    assert (
        _assert_gr08o_exact_match(
            tmp_path, rows, "ensureDefaultCategories", "categoryDao",
            "seedDefaultsIfEmpty", select_parameters=(),
            operation="insert",
        )
        is False
    )
    # The backfillLegacySource row never matches the
    # backfillNotificationLinks identity behind the same accessor +
    # operation.
    assert (
        _assert_gr08o_exact_match(
            tmp_path, rows, "backfillLegacySource", "sourceLinkDao",
            "insert",
            method="backfillNotificationLinks",
        )
        is False
    )
    # The removeMember row never matches the archiveGroup identity behind
    # the same adapter.
    assert (
        _assert_gr08o_exact_match(
            tmp_path, rows, "removeMember", "memberDao", "update",
            method="archiveGroup",
        )
        is False
    )


def test_gr08o_residual_closure_rows_exact_and_near_misses(tmp_path):
    """The two residual closure rows are exact per callable identity.

    The GR-08o post-promotion rescan left TWO residual findings popularly
    labeled "CategoryRepository" -- in fact MerchantCategoryRepository.kt
    (deleteAll/dao/deleteAll and insert/dao/insert); CategoryRepository.kt
    carries neither callable and stays at 0.  Each row authorizes EXACTLY
    its own (path, callable, DAO, operation) identity: the misattributed
    CategoryRepository.kt path, a wrong overload, wrong owner, wrong DAO,
    and wrong operation all stay unauthorized.
    """
    rows = _gr08o_seed_rows()
    # deleteAll: exact match...
    assert (
        _assert_gr08o_exact_match(
            tmp_path, rows, "deleteAll", "dao", "deleteAll"
        )
        is True
    )
    # ...and the misattributed CategoryRepository.kt path stays unauthorized.
    assert (
        _assert_gr08o_exact_match(
            tmp_path, rows, "deleteAll", "dao", "deleteAll",
            path=CATEGORY_REPOSITORY_KT_GR08O,
        )
        is False
    )
    # deleteAll: wrong DAO identity behind the same accessor spelling and
    # wrong operation stay unauthorized.
    assert (
        _assert_gr08o_exact_match(
            tmp_path, rows, "deleteAll", "dao", "deleteAll",
            dao_accessor="merchantCategoryDao",
            dao_fqcn=MERCHANT_CATEGORY_DAO_GR08O,
        )
        is False
    )
    assert (
        _assert_gr08o_exact_match(
            tmp_path, rows, "deleteAll", "dao", "deleteAll",
            operation="update",
        )
        is False
    )
    # insert: exact match...
    assert (
        _assert_gr08o_exact_match(
            tmp_path, rows, "insert", "dao", "insert",
            select_parameters=(MERCHANT_CATEGORY_ENTITY_GR08O,),
        )
        is True
    )
    # ...the zero-parameter deleteAll overload identity never matches it...
    assert (
        _assert_gr08o_exact_match(
            tmp_path, rows, "insert", "dao", "insert",
            select_parameters=(MERCHANT_CATEGORY_ENTITY_GR08O,),
            parameter_types=(),
        )
        is False
    )
    # ...and the misattributed CategoryRepository.kt path stays unauthorized.
    assert (
        _assert_gr08o_exact_match(
            tmp_path, rows, "insert", "dao", "insert",
            select_parameters=(MERCHANT_CATEGORY_ENTITY_GR08O,),
            path=CATEGORY_REPOSITORY_KT_GR08O,
        )
        is False
    )
    # insert: wrong owner stays unauthorized.
    assert (
        _assert_gr08o_exact_match(
            tmp_path, rows, "insert", "dao", "insert",
            select_parameters=(MERCHANT_CATEGORY_ENTITY_GR08O,),
            owner_fqcn="com.example.OtherMerchantRepository",
        )
        is False
    )


# ── GR-08p1 (MIT-DB-08P1): DataRetentionWorker.kt + SpendingChallengeRepository.kt ─
# + UserCorrectionRepository.kt + SmartBillNegotiationEngine.kt +
# NotificationIntakeCoordinator.kt + NotificationIntakePayloadRepairer.kt +
# RecurringPlanProjectionService.kt + RecurringLifecycleEventWriter.kt +
# DefaultExpenseCategoryAssignmentService.kt + DebugExpenseAuditWriter.kt +
# WorkerRunLogger.kt + JsonExpenseImporter.kt.  Each of the TWELVE files
# carries exactly 2 findings; the two DataRetentionWorker auditDao.insert
# sites share one fingerprint, so the combined batch carries 24 findings /
# 23 unique fingerprints <= the 25-fingerprint batch cap, so NO split was
# required; the generation run consumes the COMBINED document
# GR-08-seeds.yml; these tests pin that the combined document stays the
# exact concatenation of the TWENTY-FIVE reviewed batch seed files, and
# that the GR-08p1 rows authorize EXACTLY their callable identity + DAO +
# operation (wrong overload, wrong owner, wrong DAO, and wrong operation
# stay unauthorized).  The batch's blind-spot story is the
# CONSTRUCTOR/LOCAL-ALIAS spelling gap (the legacy MIT-003 rows spelled the
# derived accessor identities privacyAuditDao/backgroundJobRunDao while the
# scanner reports the source aliases auditDao/dao); the
# SmartBillNegotiationEngine chain-form site was normalized to the injected
# negotiationOutcomeDao constructor property (the GR-08e rule).

GR08P1_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08p1-seed.yml"

DATA_RETENTION_WORKER_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/privacy/"
    "DataRetentionWorker.kt"
)
DATA_RETENTION_WORKER_FQCN = (
    "com.yourname.expensetracker.data.privacy.DataRetentionWorker"
)
PRIVACY_AUDIT_DAO_GR08P1 = (
    "com.yourname.expensetracker.data.database.dao.PrivacyAuditDao"
)

SPENDING_CHALLENGE_REPOSITORY_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "SpendingChallengeRepository.kt"
)
SPENDING_CHALLENGE_REPOSITORY_FQCN = (
    "com.yourname.expensetracker.data.repository.SpendingChallengeRepository"
)
SPENDING_CHALLENGE_DAO_GR08P1 = (
    "com.yourname.expensetracker.data.database.dao.SpendingChallengeDao"
)
SPENDING_CHALLENGE_DOMAIN = (
    "com.yourname.expensetracker.domain.challenge.SpendingChallenge"
)

USER_CORRECTION_REPOSITORY_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "UserCorrectionRepository.kt"
)
USER_CORRECTION_REPOSITORY_FQCN = (
    "com.yourname.expensetracker.data.repository.UserCorrectionRepository"
)
USER_CORRECTION_DAO_GR08P1 = (
    "com.yourname.expensetracker.data.database.dao.UserCorrectionDao"
)
USER_CORRECTION_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.UserCorrection"
)

SMART_BILL_NEGOTIATION_ENGINE_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/negotiation/"
    "SmartBillNegotiationEngine.kt"
)
SMART_BILL_NEGOTIATION_ENGINE_FQCN = (
    "com.yourname.expensetracker.domain.negotiation.SmartBillNegotiationEngine"
)
NEGOTIATION_OUTCOME_DAO_GR08P1 = (
    "com.yourname.expensetracker.data.database.dao.NegotiationOutcomeDao"
)
SUBSCRIPTION_PRICE_HISTORY_DAO_GR08P1 = (
    "com.yourname.expensetracker.data.database.dao."
    "SubscriptionPriceHistoryDao"
)
NEGOTIATION_OUTCOME_ENUM = (
    "com.yourname.expensetracker.domain.negotiation."
    "SmartBillNegotiationEngine.NegotiationOutcome"
)
RECORD_OUTCOME_PARAMS = (
    "Long", NEGOTIATION_OUTCOME_ENUM, "Double?", "Double?", "String?",
)

NOTIFICATION_INTAKE_COORDINATOR_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/notification/"
    "capture/NotificationIntakeCoordinator.kt"
)
NOTIFICATION_INTAKE_COORDINATOR_FQCN = (
    "com.yourname.expensetracker.domain.notification.capture."
    "NotificationIntakeCoordinator"
)
NOTIFICATION_INTAKE_DAO_GR08P1 = (
    "com.yourname.expensetracker.data.database.dao.NotificationIntakeDao"
)
RAW_STORAGE_MODE = (
    "com.yourname.expensetracker.domain.privacy.RawStorageMode"
)
CAPTURE_PARAMS = (
    "String", "String?", "String", "String", "Long", "String?", "String?",
    "String?", "String?", "String?", RAW_STORAGE_MODE, "String", "String",
)
CAPTURE_FOR_RETRY_PARAMS = (
    "String", "String", "Long", "String", "String?", "String?", "String?",
    "String?",
)

NOTIFICATION_INTAKE_PAYLOAD_REPAIRER_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/notification/"
    "capture/NotificationIntakePayloadRepairer.kt"
)
NOTIFICATION_INTAKE_PAYLOAD_REPAIRER_FQCN = (
    "com.yourname.expensetracker.domain.notification.capture."
    "NotificationIntakePayloadRepairer"
)

RECURRING_PLAN_PROJECTION_SERVICE_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/recurring/"
    "RecurringPlanProjectionService.kt"
)
RECURRING_PLAN_PROJECTION_SERVICE_FQCN = (
    "com.yourname.expensetracker.domain.recurring.RecurringPlanProjectionService"
)
PLANNED_EXPENSE_DAO_GR08P1 = (
    "com.yourname.expensetracker.data.database.dao.PlannedExpenseDao"
)

RECURRING_LIFECYCLE_EVENT_WRITER_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/recurring/"
    "lifecycle/RecurringLifecycleEventWriter.kt"
)
ROOM_RECURRING_LIFECYCLE_EVENT_WRITER_FQCN = (
    "com.yourname.expensetracker.domain.recurring.lifecycle."
    "RoomRecurringLifecycleEventWriter"
)
RECURRING_LIFECYCLE_EVENT_DAO_GR08P1 = (
    "com.yourname.expensetracker.data.database.dao.RecurringLifecycleEventDao"
)
LIFECYCLE_EVENT_PARAMS = (
    "Long?", "String", "String?", "String?", "String?", "Long",
)

DEFAULT_EXPENSE_CATEGORY_ASSIGNMENT_SERVICE_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/transaction/"
    "DefaultExpenseCategoryAssignmentService.kt"
)
DEFAULT_EXPENSE_CATEGORY_ASSIGNMENT_SERVICE_FQCN = (
    "com.yourname.expensetracker.domain.transaction."
    "DefaultExpenseCategoryAssignmentService"
)
EXPENSE_DAO_GR08P1 = "com.yourname.expensetracker.data.database.dao.ExpenseDao"
TRANSACTION_EVENT_DAO_GR08P1 = (
    "com.yourname.expensetracker.data.database.dao.TransactionEventDao"
)
ASSIGN_CATEGORY_PARAMS = ("Long", "Long", "String", "String?")

DEBUG_EXPENSE_AUDIT_WRITER_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/transaction/"
    "lifecycle/DebugExpenseAuditWriter.kt"
)
DEBUG_EXPENSE_AUDIT_WRITER_FQCN = (
    "com.yourname.expensetracker.domain.transaction.lifecycle."
    "DebugExpenseAuditWriter"
)

WORKER_RUN_LOGGER_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/workers/"
    "WorkerRunLogger.kt"
)
WORKER_RUN_LOGGER_IMPL_FQCN = (
    "com.yourname.expensetracker.domain.workers.WorkerRunLoggerImpl"
)
WORKER_RUN_LOGGER_HANDLE_FQCN = (
    "com.yourname.expensetracker.domain.workers.WorkerRunLoggerImpl.Handle"
)
BACKGROUND_JOB_RUN_DAO_GR08P1 = (
    "com.yourname.expensetracker.data.database.dao.BackgroundJobRunDao"
)
WORKER_RUN_START_PARAMS = (
    "String", "String?", "String?", "Int?", "Int?", "String?",
)
TERMINAL_ARGS = (
    "com.yourname.expensetracker.domain.workers."
    "WorkerRunLoggerImpl.Handle.TerminalArgs"
)
TERMINAL_PARAMS = ("String", TERMINAL_ARGS)

JSON_EXPENSE_IMPORTER_KT = (
    "app/src/main/java/com/yourname/expensetracker/util/"
    "JsonExpenseImporter.kt"
)
JSON_EXPENSE_IMPORTER_FQCN = (
    "com.yourname.expensetracker.util.JsonExpenseImporter"
)
CATEGORY_DAO_GR08P1 = "com.yourname.expensetracker.data.database.dao.CategoryDao"
JSON_OBJECT = "org.json.JSONObject"
PARSE_ROW_PARAMS = (JSON_OBJECT, "Int", "Long?")


def _gr08p1_seed_row(path, owner_fqcn, method, parameter_types, dao_accessor,
                     dao_fqcn, operation, barrier_mode="helper"):
    """One exact GR-08p1-shaped v2 seed row mapping."""
    return {
        "path": path,
        "ownerFqcn": owner_fqcn,
        "kind": "function",
        "method": method,
        "receiver": None,
        "parameterTypes": list(parameter_types),
        "daoAccessor": dao_accessor,
        "daoFqcn": dao_fqcn,
        "operation": operation,
        "barrierMode": barrier_mode,
        "reason": "GR-08p1 EXACT_POLICY test row",
        "owner": "@panospao7",
        "linkedIssue": "MIT-DB-08P1",
    }


def _gr08p1_seed_rows():
    """The twenty-three exact GR-08p1 rows (mirroring the tracked seed file).

    DataRetentionWorker.kt (1 row -- the two auditDao.insert sites share one
    fingerprint) + SpendingChallengeRepository.kt (2 rows) +
    UserCorrectionRepository.kt (2 rows) + SmartBillNegotiationEngine.kt
    (2 rows -- the negotiationOutcomeDao row spells the NORMALIZED accessor)
    + NotificationIntakeCoordinator.kt (2 rows) +
    NotificationIntakePayloadRepairer.kt (2 rows) +
    RecurringPlanProjectionService.kt (2 rows) +
    RecurringLifecycleEventWriter.kt (2 rows) +
    DefaultExpenseCategoryAssignmentService.kt (2 rows) +
    DebugExpenseAuditWriter.kt (2 rows) + WorkerRunLogger.kt (2 rows --
    start and the nested Handle.terminal) + JsonExpenseImporter.kt (2 rows).
    """
    rows = []
    rows.append(
        _gr08p1_seed_row(
            DATA_RETENTION_WORKER_KT, DATA_RETENTION_WORKER_FQCN,
            "doWork", (), "auditDao", PRIVACY_AUDIT_DAO_GR08P1, "insert",
            barrier_mode="workerMediated",
        )
    )
    rows.append(
        _gr08p1_seed_row(
            SPENDING_CHALLENGE_REPOSITORY_KT,
            SPENDING_CHALLENGE_REPOSITORY_FQCN,
            "saveChallenge", (SPENDING_CHALLENGE_DOMAIN,),
            "spendingChallengeDao", SPENDING_CHALLENGE_DAO_GR08P1, "insert",
        )
    )
    rows.append(
        _gr08p1_seed_row(
            SPENDING_CHALLENGE_REPOSITORY_KT,
            SPENDING_CHALLENGE_REPOSITORY_FQCN,
            "deactivateChallenges", ("List<Long>", "Long"),
            "spendingChallengeDao", SPENDING_CHALLENGE_DAO_GR08P1,
            "deactivateChallenges",
        )
    )
    rows.append(
        _gr08p1_seed_row(
            USER_CORRECTION_REPOSITORY_KT, USER_CORRECTION_REPOSITORY_FQCN,
            "insert", (USER_CORRECTION_ENTITY,),
            "dao", USER_CORRECTION_DAO_GR08P1, "insert",
        )
    )
    rows.append(
        _gr08p1_seed_row(
            USER_CORRECTION_REPOSITORY_KT, USER_CORRECTION_REPOSITORY_FQCN,
            "deleteAll", (), "dao", USER_CORRECTION_DAO_GR08P1, "deleteAll",
        )
    )
    rows.append(
        _gr08p1_seed_row(
            SMART_BILL_NEGOTIATION_ENGINE_KT,
            SMART_BILL_NEGOTIATION_ENGINE_FQCN,
            "recordNegotiationOutcome", RECORD_OUTCOME_PARAMS,
            "negotiationOutcomeDao", NEGOTIATION_OUTCOME_DAO_GR08P1, "insert",
        )
    )
    rows.append(
        _gr08p1_seed_row(
            SMART_BILL_NEGOTIATION_ENGINE_KT,
            SMART_BILL_NEGOTIATION_ENGINE_FQCN,
            "recordNegotiationOutcome", RECORD_OUTCOME_PARAMS,
            "priceHistoryDao", SUBSCRIPTION_PRICE_HISTORY_DAO_GR08P1, "insert",
        )
    )
    rows.append(
        _gr08p1_seed_row(
            NOTIFICATION_INTAKE_COORDINATOR_KT,
            NOTIFICATION_INTAKE_COORDINATOR_FQCN,
            "capture", CAPTURE_PARAMS,
            "intakeDao", NOTIFICATION_INTAKE_DAO_GR08P1, "insertOrIgnore",
        )
    )
    rows.append(
        _gr08p1_seed_row(
            NOTIFICATION_INTAKE_COORDINATOR_KT,
            NOTIFICATION_INTAKE_COORDINATOR_FQCN,
            "captureForRetry", CAPTURE_FOR_RETRY_PARAMS,
            "intakeDao", NOTIFICATION_INTAKE_DAO_GR08P1, "insertOrIgnore",
        )
    )
    for operation in ("purgeVisiblePayload", "encryptAndClearVisiblePayload"):
        rows.append(
            _gr08p1_seed_row(
                NOTIFICATION_INTAKE_PAYLOAD_REPAIRER_KT,
                NOTIFICATION_INTAKE_PAYLOAD_REPAIRER_FQCN,
                "repairLegacyPlaintextTransientRows", (),
                "intakeDao", NOTIFICATION_INTAKE_DAO_GR08P1, operation,
            )
        )
    rows.append(
        _gr08p1_seed_row(
            RECURRING_PLAN_PROJECTION_SERVICE_KT,
            RECURRING_PLAN_PROJECTION_SERVICE_FQCN,
            "projectFromRule", ("Long", "Int"),
            "plannedExpenseDao", PLANNED_EXPENSE_DAO_GR08P1,
            "insertPlannedExpense",
        )
    )
    rows.append(
        _gr08p1_seed_row(
            RECURRING_PLAN_PROJECTION_SERVICE_KT,
            RECURRING_PLAN_PROJECTION_SERVICE_FQCN,
            "projectFromOccurrencesInCurrentTransaction",
            ("Long", "Long", "Long", "Long"),
            "plannedExpenseDao", PLANNED_EXPENSE_DAO_GR08P1,
            "insertPlannedExpense",
        )
    )
    for method in ("writeCritical", "writeDiagnostic"):
        rows.append(
            _gr08p1_seed_row(
                RECURRING_LIFECYCLE_EVENT_WRITER_KT,
                ROOM_RECURRING_LIFECYCLE_EVENT_WRITER_FQCN,
                method, LIFECYCLE_EVENT_PARAMS,
                "dao", RECURRING_LIFECYCLE_EVENT_DAO_GR08P1, "insert",
            )
        )
    for accessor, dao, operation in (
        ("expenseDao", EXPENSE_DAO_GR08P1, "updateCategory"),
        ("transactionEventDao", TRANSACTION_EVENT_DAO_GR08P1, "insert"),
    ):
        rows.append(
            _gr08p1_seed_row(
                DEFAULT_EXPENSE_CATEGORY_ASSIGNMENT_SERVICE_KT,
                DEFAULT_EXPENSE_CATEGORY_ASSIGNMENT_SERVICE_FQCN,
                "assignCategoryIfUnset", ASSIGN_CATEGORY_PARAMS,
                accessor, dao, operation,
            )
        )
    rows.append(
        _gr08p1_seed_row(
            DEBUG_EXPENSE_AUDIT_WRITER_KT, DEBUG_EXPENSE_AUDIT_WRITER_FQCN,
            "writeDeleteAllEvent", ("Int", "String?"),
            "transactionEventDao", TRANSACTION_EVENT_DAO_GR08P1, "insert",
        )
    )
    rows.append(
        _gr08p1_seed_row(
            DEBUG_EXPENSE_AUDIT_WRITER_KT, DEBUG_EXPENSE_AUDIT_WRITER_FQCN,
            "writeRestoreSnapshotEvent", ("Int", "Int", "String?"),
            "transactionEventDao", TRANSACTION_EVENT_DAO_GR08P1, "insert",
        )
    )
    rows.append(
        _gr08p1_seed_row(
            WORKER_RUN_LOGGER_KT, WORKER_RUN_LOGGER_IMPL_FQCN,
            "start", WORKER_RUN_START_PARAMS,
            "dao", BACKGROUND_JOB_RUN_DAO_GR08P1, "insert",
            barrier_mode="workerMediated",
        )
    )
    rows.append(
        _gr08p1_seed_row(
            WORKER_RUN_LOGGER_KT, WORKER_RUN_LOGGER_HANDLE_FQCN,
            "terminal", TERMINAL_PARAMS,
            "dao", BACKGROUND_JOB_RUN_DAO_GR08P1, "completeTerminal",
            barrier_mode="workerMediated",
        )
    )
    for method in ("parseV2Row", "parseV1Row"):
        rows.append(
            _gr08p1_seed_row(
                JSON_EXPENSE_IMPORTER_KT, JSON_EXPENSE_IMPORTER_FQCN,
                method, PARSE_ROW_PARAMS,
                "categoryDao", CATEGORY_DAO_GR08P1, "insert",
            )
        )
    return rows


def test_real_tracked_gr08p1_seed_file_loads_with_exactly_twenty_three_rows():
    entries = _load_seed_entries(GR08P1_SEED_FILE)
    assert len(entries) == 23
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted(
        ["doWork"]
        + ["saveChallenge", "deactivateChallenges"]
        + ["insert", "deleteAll"]
        + ["recordNegotiationOutcome"] * 2
        + ["capture", "captureForRetry"]
        + ["repairLegacyPlaintextTransientRows"] * 2
        + ["projectFromRule", "projectFromOccurrencesInCurrentTransaction"]
        + ["writeCritical", "writeDiagnostic"]
        + ["assignCategoryIfUnset"] * 2
        + ["writeDeleteAllEvent", "writeRestoreSnapshotEvent"]
        + ["start", "terminal"]
        + ["parseV2Row", "parseV1Row"]
    )
    for entry in entries:
        assert entry.path in (
            DATA_RETENTION_WORKER_KT,
            SPENDING_CHALLENGE_REPOSITORY_KT,
            USER_CORRECTION_REPOSITORY_KT,
            SMART_BILL_NEGOTIATION_ENGINE_KT,
            NOTIFICATION_INTAKE_COORDINATOR_KT,
            NOTIFICATION_INTAKE_PAYLOAD_REPAIRER_KT,
            RECURRING_PLAN_PROJECTION_SERVICE_KT,
            RECURRING_LIFECYCLE_EVENT_WRITER_KT,
            DEFAULT_EXPENSE_CATEGORY_ASSIGNMENT_SERVICE_KT,
            DEBUG_EXPENSE_AUDIT_WRITER_KT,
            WORKER_RUN_LOGGER_KT,
            JSON_EXPENSE_IMPORTER_KT,
        )
        assert entry.owner_fqcn in (
            DATA_RETENTION_WORKER_FQCN,
            SPENDING_CHALLENGE_REPOSITORY_FQCN,
            USER_CORRECTION_REPOSITORY_FQCN,
            SMART_BILL_NEGOTIATION_ENGINE_FQCN,
            NOTIFICATION_INTAKE_COORDINATOR_FQCN,
            NOTIFICATION_INTAKE_PAYLOAD_REPAIRER_FQCN,
            RECURRING_PLAN_PROJECTION_SERVICE_FQCN,
            ROOM_RECURRING_LIFECYCLE_EVENT_WRITER_FQCN,
            DEFAULT_EXPENSE_CATEGORY_ASSIGNMENT_SERVICE_FQCN,
            DEBUG_EXPENSE_AUDIT_WRITER_FQCN,
            WORKER_RUN_LOGGER_IMPL_FQCN,
            WORKER_RUN_LOGGER_HANDLE_FQCN,
            JSON_EXPENSE_IMPORTER_FQCN,
        )
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08P1"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)
    # The workerMediated rows are EXACTLY the worker-layer rows:
    # DataRetentionWorker.doWork (a real CoroutineWorker whose body runs
    # inside WorkerExecutionGuard.runGuardedWithContext) and the
    # WorkerRunLogger start/Handle.terminal pair (the worker run-diagnostics
    # ledger written from inside guard contexts -- the GR-08i3 precedent).
    worker_mediated = sorted(
        (entry.method, entry.dao_accessor, entry.operation)
        for entry in entries
        if entry.barrier_mode is BarrierMode.WORKER_MEDIATED
    )
    assert worker_mediated == sorted([
        ("doWork", "auditDao", "insert"),
        ("start", "dao", "insert"),
        ("terminal", "dao", "completeTerminal"),
    ])
    # Every other row is helper (the GR-08a..o batch convention).
    assert all(
        entry.barrier_mode is BarrierMode.HELPER
        for entry in entries
        if entry.barrier_mode is not BarrierMode.WORKER_MEDIATED
    )
    # The two DataRetentionWorker auditDao.insert sites share ONE
    # fingerprint (24 findings collapse to 23 rows), the two
    # recordNegotiationOutcome rows hit TWO different DAOs, and the two
    # repairLegacyPlaintextTransientRows rows carry TWO distinct operations
    # behind the same callable.
    do_work = [
        entry for entry in entries if entry.method == "doWork"
    ]
    assert len(do_work) == 1
    assert do_work[0].dao_accessor == "auditDao"
    record_outcome = sorted(
        entry.dao_fqcn for entry in entries
        if entry.method == "recordNegotiationOutcome"
    )
    assert record_outcome == [
        NEGOTIATION_OUTCOME_DAO_GR08P1, SUBSCRIPTION_PRICE_HISTORY_DAO_GR08P1,
    ]
    repair_ops = sorted(
        entry.operation for entry in entries
        if entry.method == "repairLegacyPlaintextTransientRows"
    )
    assert repair_ops == [
        "encryptAndClearVisiblePayload", "purgeVisiblePayload",
    ]
    # The source-alias spellings: the DataRetentionWorker row spells the
    # method-local alias auditDao and the WorkerRunLogger rows spell the
    # constructor-property alias dao (the legacy MIT-003 rows spelled the
    # derived identities, which never matched the scanner's receiver text).
    assert do_work[0].dao_accessor == "auditDao"
    worker_run = sorted(
        (entry.owner_fqcn, entry.method, entry.dao_accessor)
        for entry in entries if entry.path == WORKER_RUN_LOGGER_KT
    )
    assert worker_run == sorted([
        (WORKER_RUN_LOGGER_IMPL_FQCN, "start", "dao"),
        (WORKER_RUN_LOGGER_HANDLE_FQCN, "terminal", "dao"),
    ])
    # The normalized chain-form row spells the NORMALIZED accessor, never
    # the database-chained text.
    assert all(
        entry.dao_accessor == "negotiationOutcomeDao"
        for entry in entries
        if entry.dao_fqcn == NEGOTIATION_OUTCOME_DAO_GR08P1
    )


def _gr08p1_policy_entries(tmp_path, rows):
    entries = []
    for position, row in enumerate(rows):
        entry, errors = build_policy_entry(row, position)
        assert entry is not None and not errors, (
            "GR-08p1 fixture row must be schema-valid: %s" % (errors,)
        )
        entries.append(entry)
    return entries


def _assert_gr08p1_exact_match(tmp_path, rows, select_method,
                               select_accessor, select_operation,
                               select_parameters=None, **overrides):
    """The exact GR-08p1 row identity matches; mutants never do.

    Target selection is fixed by ``(select_method, select_accessor,
    select_operation)`` -- optionally narrowed by ``select_parameters`` when
    several rows share the same (method, accessor, operation) triple (the
    GR-08p1 repair/parse rows); ``overrides`` perturb exactly one identity
    field of the match query for the near-miss assertions.
    """
    entries = _gr08p1_policy_entries(tmp_path, rows)
    candidates = [
        entry
        for entry in entries
        if entry.method == select_method
        and entry.dao_accessor == select_accessor
        and entry.operation == select_operation
    ]
    if select_parameters is not None:
        candidates = [
            entry
            for entry in candidates
            if tuple(entry.parameter_types) == tuple(select_parameters)
        ]
    target = candidates[0]
    kwargs = dict(
        path=target.path,
        owner_fqcn=target.owner_fqcn,
        kind=target.kind,
        method=target.method,
        receiver=target.receiver,
        parameter_types=target.parameter_types,
        dao_accessor=target.dao_accessor,
        dao_fqcn=target.dao_fqcn,
        operation=target.operation,
    )
    kwargs.update(overrides)
    return match_mutation(target, **kwargs)


def test_gr08p1_exact_identity_matches(tmp_path):
    rows = _gr08p1_seed_rows()
    assert (
        _assert_gr08p1_exact_match(
            tmp_path, rows, "doWork", "auditDao", "insert",
            select_parameters=(),
        )
        is True
    )
    assert (
        _assert_gr08p1_exact_match(
            tmp_path, rows, "recordNegotiationOutcome",
            "negotiationOutcomeDao", "insert",
            select_parameters=RECORD_OUTCOME_PARAMS,
        )
        is True
    )
    assert (
        _assert_gr08p1_exact_match(
            tmp_path, rows, "terminal", "dao", "completeTerminal",
            select_parameters=TERMINAL_PARAMS,
        )
        is True
    )
    assert (
        _assert_gr08p1_exact_match(
            tmp_path, rows, "parseV2Row", "categoryDao", "insert",
            select_parameters=PARSE_ROW_PARAMS,
        )
        is True
    )


def test_gr08p1_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08p1_seed_rows()
    # captureForRetry's 8-parameter overload never matches capture's
    # 13-parameter identity behind the same accessor + operation.
    assert (
        _assert_gr08p1_exact_match(
            tmp_path, rows, "capture", "intakeDao", "insertOrIgnore",
            select_parameters=CAPTURE_PARAMS,
            parameter_types=CAPTURE_FOR_RETRY_PARAMS,
        )
        is False
    )
    # A wrong TerminalArgs parameter type never matches the terminal row.
    assert (
        _assert_gr08p1_exact_match(
            tmp_path, rows, "terminal", "dao", "completeTerminal",
            select_parameters=TERMINAL_PARAMS,
            parameter_types=("String", "TerminalArgs"),
        )
        is False
    )


def test_gr08p1_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08p1_seed_rows()
    # The nested Handle.terminal row never matches the outer-impl owner.
    assert (
        _assert_gr08p1_exact_match(
            tmp_path, rows, "terminal", "dao", "completeTerminal",
            select_parameters=TERMINAL_PARAMS,
            owner_fqcn=WORKER_RUN_LOGGER_IMPL_FQCN,
        )
        is False
    )
    # A foreign engine owner never matches the negotiation-outcome row.
    assert (
        _assert_gr08p1_exact_match(
            tmp_path, rows, "recordNegotiationOutcome",
            "negotiationOutcomeDao", "insert",
            select_parameters=RECORD_OUTCOME_PARAMS,
            owner_fqcn="com.example.OtherNegotiationEngine",
        )
        is False
    )


def test_gr08p1_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08p1_seed_rows()
    # The doWork row never matches the derived-identity accessor spelling
    # the legacy MIT-003 row used (the alias bridge resolves auditDao to
    # the same DAO identity, but the exact policy match is per spelling).
    assert (
        _assert_gr08p1_exact_match(
            tmp_path, rows, "doWork", "auditDao", "insert",
            select_parameters=(),
            dao_accessor="privacyAuditDao",
        )
        is False
    )
    # The assignCategoryIfUnset expenseDao row never matches the
    # transactionEventDao identity behind the same callable.
    assert (
        _assert_gr08p1_exact_match(
            tmp_path, rows, "assignCategoryIfUnset", "expenseDao",
            "updateCategory", select_parameters=ASSIGN_CATEGORY_PARAMS,
            dao_accessor="transactionEventDao",
            dao_fqcn=TRANSACTION_EVENT_DAO_GR08P1,
        )
        is False
    )


def test_gr08p1_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08p1_seed_rows()
    # The purgeVisiblePayload row never matches the encryptAndClear
    # operation behind the same callable + accessor.
    assert (
        _assert_gr08p1_exact_match(
            tmp_path, rows, "repairLegacyPlaintextTransientRows",
            "intakeDao", "purgeVisiblePayload", select_parameters=(),
            operation="encryptAndClearVisiblePayload",
        )
        is False
    )
    # The start row never matches the completeTerminal operation behind the
    # same accessor + DAO.
    assert (
        _assert_gr08p1_exact_match(
            tmp_path, rows, "start", "dao", "insert",
            select_parameters=WORKER_RUN_START_PARAMS,
            operation="completeTerminal",
        )
        is False
    )


def test_gr08p1_shared_operation_distinct_callables_near_misses(tmp_path):
    """The multi-row callables are exact per (accessor, operation) identity.

    recordNegotiationOutcome carries TWO rows behind one callable
    (negotiationOutcomeDao insert + priceHistoryDao insert), the two parse
    rows share the categoryDao insert identity across parseV1Row/parseV2Row,
    and the two projection rows share the plannedExpenseDao
    insertPlannedExpense identity across projectFromRule/
    projectFromOccurrencesInCurrentTransaction; each row authorizes EXACTLY
    its own callable identity, so a swapped method or accessor stays
    unauthorized.
    """
    rows = _gr08p1_seed_rows()
    # The negotiationOutcomeDao row never matches the priceHistoryDao
    # identity behind the same callable.
    assert (
        _assert_gr08p1_exact_match(
            tmp_path, rows, "recordNegotiationOutcome",
            "negotiationOutcomeDao", "insert",
            select_parameters=RECORD_OUTCOME_PARAMS,
            dao_accessor="priceHistoryDao",
            dao_fqcn=SUBSCRIPTION_PRICE_HISTORY_DAO_GR08P1,
        )
        is False
    )
    # The parseV1Row row never matches the parseV2Row identity behind the
    # same accessor + operation.
    assert (
        _assert_gr08p1_exact_match(
            tmp_path, rows, "parseV1Row", "categoryDao", "insert",
            select_parameters=PARSE_ROW_PARAMS,
            method="parseV2Row",
        )
        is False
    )
    # The projectFromRule row never matches the
    # projectFromOccurrencesInCurrentTransaction identity behind the same
    # accessor + operation.
    assert (
        _assert_gr08p1_exact_match(
            tmp_path, rows, "projectFromRule", "plannedExpenseDao",
            "insertPlannedExpense", select_parameters=("Long", "Int"),
            method="projectFromOccurrencesInCurrentTransaction",
        )
        is False
    )


# ── GR-08p2 (MIT-DB-08P2): the FINAL triage batch -- fifteen 1-finding files ────
# ExpenseGroupDao.kt (the DAO-default-method special case) +
# PrivacyAuditLoggerImpl.kt + DatabaseBackupRepositoryImpl.kt +
# GroupsRepositoryImpl.kt + ReceiptInsertResolver.kt +
# BankConnectionLifecycleCoordinator.kt + BudgetForecastingEngine.kt +
# DiagnosticEventWriter.kt + SettlementCalculator.kt +
# NotificationIntakeRecoveryScheduler.kt + SourceLinkWriterImpl.kt +
# ReceiptLifecycleEventWriter.kt + TransactionLifecycleEventWriter.kt +
# LegacyDataMigrationService.kt + CsvExpenseImporter.kt.  Each of the
# FIFTEEN files carries exactly 1 finding and each finding is its own
# distinct (callable, daoAccessor, daoFqcn, operation) tuple, so the
# combined batch carries 15 findings / 15 unique fingerprints <= the
# 25-fingerprint batch cap, so NO split was required; the generation run
# consumes the COMBINED document GR-08-seeds.yml; these tests pin that the
# combined document stays the exact concatenation of the TWENTY-SIX reviewed
# batch seed files, and that the GR-08p2 rows authorize EXACTLY their
# callable identity + DAO + operation (wrong overload, wrong owner, wrong
# DAO, and wrong operation stay unauthorized).  ZERO closure rows, ZERO
# chain-form receivers, ZERO accessor normalization; every row is `helper`.

GR08P2_SEED_FILE = _ROOT / "docs" / "ci" / "db-findings" / "GR-08p2-seed.yml"

EXPENSE_GROUP_DAO_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/database/dao/"
    "ExpenseGroupDao.kt"
)
EXPENSE_GROUP_DAO_FQCN = (
    "com.yourname.expensetracker.data.database.dao.ExpenseGroupDao"
)
GROUP_MEMBER_DAO_GR08P2 = (
    "com.yourname.expensetracker.data.database.dao.GroupMemberDao"
)
EXPENSE_GROUP_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.ExpenseGroup"
)
GROUP_MEMBER_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.GroupMember"
)
INSERT_GROUP_WITH_MEMBERS_PARAMS = (
    EXPENSE_GROUP_ENTITY, GROUP_MEMBER_DAO_GR08P2,
    "List<" + GROUP_MEMBER_ENTITY + ">",
)

PRIVACY_AUDIT_LOGGER_IMPL_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/privacy/"
    "PrivacyAuditLoggerImpl.kt"
)
PRIVACY_AUDIT_LOGGER_IMPL_FQCN = (
    "com.yourname.expensetracker.data.privacy.PrivacyAuditLoggerImpl"
)
PRIVACY_AUDIT_DAO_GR08P2 = (
    "com.yourname.expensetracker.data.database.dao.PrivacyAuditDao"
)
PRIVACY_CAPABILITY = (
    "com.yourname.expensetracker.domain.privacy.PrivacyCapability"
)
PRIVACY_DECISION = (
    "com.yourname.expensetracker.domain.privacy.PrivacyDecision"
)
LOG_DECISION_PARAMS = (PRIVACY_CAPABILITY, PRIVACY_DECISION, "Map<String, String>")

DATABASE_BACKUP_REPOSITORY_IMPL_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "DatabaseBackupRepositoryImpl.kt"
)
DATABASE_BACKUP_REPOSITORY_IMPL_FQCN = (
    "com.yourname.expensetracker.data.repository."
    "DatabaseBackupRepositoryImpl"
)
SCANNED_RECEIPT_DAO_GR08P2 = (
    "com.yourname.expensetracker.data.database.dao.ScannedReceiptDao"
)
RESTORE_RECEIPT_ASSETS_PARAMS = (
    "java.io.File",
    "com.yourname.expensetracker.data.backup.CostbackupBundle.BackupManifest",
    "com.yourname.expensetracker.data.database.AppDatabase",
    "com.yourname.expensetracker.data.backup.RestoreJournal.JournalEntry?",
    "com.yourname.expensetracker.data.backup.RestoreDiagnosticsSink?",
)

GROUPS_REPOSITORY_IMPL_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "GroupsRepositoryImpl.kt"
)
GROUPS_REPOSITORY_IMPL_FQCN = (
    "com.yourname.expensetracker.data.repository.GroupsRepositoryImpl"
)

RECEIPT_INSERT_RESOLVER_KT = (
    "app/src/main/java/com/yourname/expensetracker/data/repository/"
    "ReceiptInsertResolver.kt"
)
RECEIPT_INSERT_RESOLVER_FQCN = (
    "com.yourname.expensetracker.data.repository.ReceiptInsertResolver"
)
SCANNED_RECEIPT_ENTITY = (
    "com.yourname.expensetracker.data.database.entity.ScannedReceipt"
)

BANK_CONNECTION_LIFECYCLE_COORDINATOR_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/bank/"
    "BankConnectionLifecycleCoordinator.kt"
)
BANK_CONNECTION_LIFECYCLE_COORDINATOR_FQCN = (
    "com.yourname.expensetracker.domain.bank."
    "BankConnectionLifecycleCoordinator"
)
BANK_CONNECTION_DAO_GR08P2 = (
    "com.yourname.expensetracker.data.database.dao.BankConnectionDao"
)

BUDGET_FORECASTING_ENGINE_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/budget/"
    "BudgetForecastingEngine.kt"
)
BUDGET_FORECASTING_ENGINE_FQCN = (
    "com.yourname.expensetracker.domain.budget.BudgetForecastingEngine"
)
BUDGET_FORECAST_DAO_GR08P2 = (
    "com.yourname.expensetracker.data.database.dao.BudgetForecastDao"
)

DIAGNOSTIC_EVENT_WRITER_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/diagnostics/"
    "DiagnosticEventWriter.kt"
)
ROOM_DIAGNOSTIC_EVENT_WRITER_FQCN = (
    "com.yourname.expensetracker.domain.diagnostics."
    "RoomDiagnosticEventWriter"
)
PIPELINE_DIAGNOSTIC_EVENT_DAO_GR08P2 = (
    "com.yourname.expensetracker.data.database.dao."
    "PipelineDiagnosticEventDao"
)
DIAGNOSTIC_EVENT = (
    "com.yourname.expensetracker.domain.diagnostics.DiagnosticEvent"
)

SETTLEMENT_CALCULATOR_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/groups/"
    "SettlementCalculator.kt"
)
SETTLEMENT_CALCULATOR_FQCN = (
    "com.yourname.expensetracker.domain.groups.SettlementCalculator"
)
GROUP_SETTLEMENT_DAO_GR08P2 = (
    "com.yourname.expensetracker.data.database.dao.GroupSettlementDao"
)
TIME_PROVIDER = "com.yourname.expensetracker.domain.util.TimeProvider"
RECORD_SETTLEMENT_PARAMS = (
    "Long", "Long", "Long", "Double", "String",
    GROUP_SETTLEMENT_DAO_GR08P2, TIME_PROVIDER,
)

NOTIFICATION_INTAKE_RECOVERY_SCHEDULER_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/notification/"
    "capture/NotificationIntakeRecoveryScheduler.kt"
)
NOTIFICATION_INTAKE_RECOVERY_SCHEDULER_FQCN = (
    "com.yourname.expensetracker.domain.notification.capture."
    "NotificationIntakeRecoveryScheduler"
)
NOTIFICATION_INTAKE_DAO_GR08P2 = (
    "com.yourname.expensetracker.data.database.dao.NotificationIntakeDao"
)

SOURCE_LINK_WRITER_IMPL_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/provenance/"
    "SourceLinkWriterImpl.kt"
)
SOURCE_LINK_WRITER_IMPL_FQCN = (
    "com.yourname.expensetracker.domain.provenance.SourceLinkWriterImpl"
)
ENTITY_SOURCE_LINK_DAO_GR08P2 = (
    "com.yourname.expensetracker.data.database.dao.EntitySourceLinkDao"
)
TARGET_ENTITY_TYPE = (
    "com.yourname.expensetracker.domain.provenance.TargetEntityType"
)
SOURCE_LINK_PAYLOAD = (
    "com.yourname.expensetracker.domain.provenance.SourceLinkPayload"
)
LINK_TARGET_PARAMS = (TARGET_ENTITY_TYPE, "Long", SOURCE_LINK_PAYLOAD, "String?")

RECEIPT_LIFECYCLE_EVENT_WRITER_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/receipt/"
    "lifecycle/ReceiptLifecycleEventWriter.kt"
)
ROOM_RECEIPT_LIFECYCLE_EVENT_WRITER_FQCN = (
    "com.yourname.expensetracker.domain.receipt.lifecycle."
    "RoomReceiptLifecycleEventWriter"
)
RECEIPT_EVENT_DAO_GR08P2 = (
    "com.yourname.expensetracker.data.database.dao.ReceiptEventDao"
)
TRANSACTION_CONTEXT = (
    "com.yourname.expensetracker.domain.transaction.TransactionContext"
)
RECEIPT_LIFECYCLE_EVENT = (
    "com.yourname.expensetracker.domain.receipt.lifecycle."
    "ReceiptLifecycleEvent"
)
RECEIPT_WRITE_PARAMS = (TRANSACTION_CONTEXT, RECEIPT_LIFECYCLE_EVENT)

TRANSACTION_LIFECYCLE_EVENT_WRITER_KT = (
    "app/src/main/java/com/yourname/expensetracker/domain/transaction/"
    "lifecycle/TransactionLifecycleEventWriter.kt"
)
ROOM_TRANSACTION_LIFECYCLE_EVENT_WRITER_FQCN = (
    "com.yourname.expensetracker.domain.transaction.lifecycle."
    "RoomTransactionLifecycleEventWriter"
)
TRANSACTION_EVENT_DAO_GR08P2 = (
    "com.yourname.expensetracker.data.database.dao.TransactionEventDao"
)
TRANSACTION_LIFECYCLE_EVENT = (
    "com.yourname.expensetracker.domain.transaction.lifecycle."
    "TransactionLifecycleEvent"
)
TRANSACTION_WRITE_PARAMS = (TRANSACTION_CONTEXT, TRANSACTION_LIFECYCLE_EVENT)

LEGACY_DATA_MIGRATION_SERVICE_KT = (
    "app/src/main/java/com/yourname/expensetracker/service/debug/"
    "LegacyDataMigrationService.kt"
)
LEGACY_DATA_MIGRATION_SERVICE_FQCN = (
    "com.yourname.expensetracker.service.debug.LegacyDataMigrationService"
)
CATEGORY_DAO_GR08P2 = "com.yourname.expensetracker.data.database.dao.CategoryDao"
SQLITE_DATABASE = "android.database.sqlite.SQLiteDatabase"

CSV_EXPENSE_IMPORTER_KT = (
    "app/src/main/java/com/yourname/expensetracker/util/CsvExpenseImporter.kt"
)
CSV_EXPENSE_IMPORTER_FQCN = (
    "com.yourname.expensetracker.util.CsvExpenseImporter"
)


def _gr08p2_seed_row(path, owner_fqcn, method, parameter_types, dao_accessor,
                     dao_fqcn, operation, barrier_mode="helper"):
    """One exact GR-08p2-shaped v2 seed row mapping."""
    return {
        "path": path,
        "ownerFqcn": owner_fqcn,
        "kind": "function",
        "method": method,
        "receiver": None,
        "parameterTypes": list(parameter_types),
        "daoAccessor": dao_accessor,
        "daoFqcn": dao_fqcn,
        "operation": operation,
        "barrierMode": barrier_mode,
        "reason": "GR-08p2 EXACT_POLICY test row",
        "owner": "@panospao7",
        "linkedIssue": "MIT-DB-08P2",
    }


def _gr08p2_seed_rows():
    """The fifteen exact GR-08p2 rows (mirroring the tracked seed file).

    One row per file -- each finding is its own distinct (callable,
    daoAccessor, daoFqcn, operation) tuple.  The ExpenseGroupDao row spells
    the explicit DAO-typed method parameter (memberDao), the
    DatabaseBackupRepositoryImpl row spells the source method-local alias
    (dao), and the SettlementCalculator row spells the explicit DAO-typed
    method parameter (settlementDao).
    """
    return [
        _gr08p2_seed_row(
            EXPENSE_GROUP_DAO_KT, EXPENSE_GROUP_DAO_FQCN,
            "insertGroupWithMembers", INSERT_GROUP_WITH_MEMBERS_PARAMS,
            "memberDao", GROUP_MEMBER_DAO_GR08P2, "insertAll",
        ),
        _gr08p2_seed_row(
            PRIVACY_AUDIT_LOGGER_IMPL_KT, PRIVACY_AUDIT_LOGGER_IMPL_FQCN,
            "logDecision", LOG_DECISION_PARAMS,
            "dao", PRIVACY_AUDIT_DAO_GR08P2, "insert",
        ),
        _gr08p2_seed_row(
            DATABASE_BACKUP_REPOSITORY_IMPL_KT,
            DATABASE_BACKUP_REPOSITORY_IMPL_FQCN,
            "restoreReceiptAssets", RESTORE_RECEIPT_ASSETS_PARAMS,
            "dao", SCANNED_RECEIPT_DAO_GR08P2, "update",
        ),
        _gr08p2_seed_row(
            GROUPS_REPOSITORY_IMPL_KT, GROUPS_REPOSITORY_IMPL_FQCN,
            "deleteMember", ("Long", "Long"),
            "memberDao", GROUP_MEMBER_DAO_GR08P2, "update",
        ),
        _gr08p2_seed_row(
            RECEIPT_INSERT_RESOLVER_KT, RECEIPT_INSERT_RESOLVER_FQCN,
            "insertOrResolve", (SCANNED_RECEIPT_ENTITY,),
            "scannedReceiptDao", SCANNED_RECEIPT_DAO_GR08P2, "insert",
        ),
        _gr08p2_seed_row(
            BANK_CONNECTION_LIFECYCLE_COORDINATOR_KT,
            BANK_CONNECTION_LIFECYCLE_COORDINATOR_FQCN,
            "disconnectConnection", ("Long",),
            "bankConnectionDao", BANK_CONNECTION_DAO_GR08P2, "disconnect",
        ),
        _gr08p2_seed_row(
            BUDGET_FORECASTING_ENGINE_KT, BUDGET_FORECASTING_ENGINE_FQCN,
            "updateForecastAccuracy", ("Long", "Double"),
            "budgetForecastDao", BUDGET_FORECAST_DAO_GR08P2, "update",
        ),
        _gr08p2_seed_row(
            DIAGNOSTIC_EVENT_WRITER_KT, ROOM_DIAGNOSTIC_EVENT_WRITER_FQCN,
            "emit", (DIAGNOSTIC_EVENT,),
            "dao", PIPELINE_DIAGNOSTIC_EVENT_DAO_GR08P2, "insert",
        ),
        _gr08p2_seed_row(
            SETTLEMENT_CALCULATOR_KT, SETTLEMENT_CALCULATOR_FQCN,
            "recordSettlement", RECORD_SETTLEMENT_PARAMS,
            "settlementDao", GROUP_SETTLEMENT_DAO_GR08P2, "insert",
        ),
        _gr08p2_seed_row(
            NOTIFICATION_INTAKE_RECOVERY_SCHEDULER_KT,
            NOTIFICATION_INTAKE_RECOVERY_SCHEDULER_FQCN,
            "recoverPending", ("Int",),
            "intakeDao", NOTIFICATION_INTAKE_DAO_GR08P2,
            "releaseStaleProcessing",
        ),
        _gr08p2_seed_row(
            SOURCE_LINK_WRITER_IMPL_KT, SOURCE_LINK_WRITER_IMPL_FQCN,
            "linkTarget", LINK_TARGET_PARAMS,
            "sourceLinkDao", ENTITY_SOURCE_LINK_DAO_GR08P2, "insert",
        ),
        _gr08p2_seed_row(
            RECEIPT_LIFECYCLE_EVENT_WRITER_KT,
            ROOM_RECEIPT_LIFECYCLE_EVENT_WRITER_FQCN,
            "write", RECEIPT_WRITE_PARAMS,
            "dao", RECEIPT_EVENT_DAO_GR08P2, "insert",
        ),
        _gr08p2_seed_row(
            TRANSACTION_LIFECYCLE_EVENT_WRITER_KT,
            ROOM_TRANSACTION_LIFECYCLE_EVENT_WRITER_FQCN,
            "write", TRANSACTION_WRITE_PARAMS,
            "dao", TRANSACTION_EVENT_DAO_GR08P2, "insert",
        ),
        _gr08p2_seed_row(
            LEGACY_DATA_MIGRATION_SERVICE_KT,
            LEGACY_DATA_MIGRATION_SERVICE_FQCN,
            "migrateCategories", (SQLITE_DATABASE,),
            "categoryDao", CATEGORY_DAO_GR08P2, "insert",
        ),
        _gr08p2_seed_row(
            CSV_EXPENSE_IMPORTER_KT, CSV_EXPENSE_IMPORTER_FQCN,
            "getOrCreateCategory", ("String",),
            "categoryDao", CATEGORY_DAO_GR08P2, "insert",
        ),
    ]


def test_real_tracked_gr08p2_seed_file_loads_with_exactly_fifteen_rows():
    entries = _load_seed_entries(GR08P2_SEED_FILE)
    assert len(entries) == 15
    methods = sorted(entry.method for entry in entries)
    assert methods == sorted([
        "insertGroupWithMembers",
        "logDecision",
        "restoreReceiptAssets",
        "deleteMember",
        "insertOrResolve",
        "disconnectConnection",
        "updateForecastAccuracy",
        "emit",
        "recordSettlement",
        "recoverPending",
        "linkTarget",
        "write",
        "write",
        "migrateCategories",
        "getOrCreateCategory",
    ])
    for entry in entries:
        assert entry.path in (
            EXPENSE_GROUP_DAO_KT,
            PRIVACY_AUDIT_LOGGER_IMPL_KT,
            DATABASE_BACKUP_REPOSITORY_IMPL_KT,
            GROUPS_REPOSITORY_IMPL_KT,
            RECEIPT_INSERT_RESOLVER_KT,
            BANK_CONNECTION_LIFECYCLE_COORDINATOR_KT,
            BUDGET_FORECASTING_ENGINE_KT,
            DIAGNOSTIC_EVENT_WRITER_KT,
            SETTLEMENT_CALCULATOR_KT,
            NOTIFICATION_INTAKE_RECOVERY_SCHEDULER_KT,
            SOURCE_LINK_WRITER_IMPL_KT,
            RECEIPT_LIFECYCLE_EVENT_WRITER_KT,
            TRANSACTION_LIFECYCLE_EVENT_WRITER_KT,
            LEGACY_DATA_MIGRATION_SERVICE_KT,
            CSV_EXPENSE_IMPORTER_KT,
        )
        assert entry.owner_fqcn in (
            EXPENSE_GROUP_DAO_FQCN,
            PRIVACY_AUDIT_LOGGER_IMPL_FQCN,
            DATABASE_BACKUP_REPOSITORY_IMPL_FQCN,
            GROUPS_REPOSITORY_IMPL_FQCN,
            RECEIPT_INSERT_RESOLVER_FQCN,
            BANK_CONNECTION_LIFECYCLE_COORDINATOR_FQCN,
            BUDGET_FORECASTING_ENGINE_FQCN,
            ROOM_DIAGNOSTIC_EVENT_WRITER_FQCN,
            SETTLEMENT_CALCULATOR_FQCN,
            NOTIFICATION_INTAKE_RECOVERY_SCHEDULER_FQCN,
            SOURCE_LINK_WRITER_IMPL_FQCN,
            ROOM_RECEIPT_LIFECYCLE_EVENT_WRITER_FQCN,
            ROOM_TRANSACTION_LIFECYCLE_EVENT_WRITER_FQCN,
            LEGACY_DATA_MIGRATION_SERVICE_FQCN,
            CSV_EXPENSE_IMPORTER_FQCN,
        )
        assert entry.kind is CallableKind.FUNCTION
        assert entry.receiver is None
        assert entry.owner == "@panospao7"
        assert entry.linked_issue == "MIT-DB-08P2"
    keys = [entry.mutation_key().canonical_key() for entry in entries]
    assert len(set(keys)) == len(keys)
    # EVERY row is helper: none of the fifteen callables runs inside a
    # WorkerExecutionGuard.runGuardedWithContext context.
    assert all(entry.barrier_mode is BarrierMode.HELPER for entry in entries)
    # The two `write` rows hit TWO different DAOs behind the same method
    # name (the receipt vs transaction lifecycle event writers), and the
    # two categoryDao insert rows hit TWO different callables (the legacy
    # migration vs the CSV import path).
    write_daos = sorted(
        entry.dao_fqcn for entry in entries if entry.method == "write"
    )
    assert write_daos == [
        RECEIPT_EVENT_DAO_GR08P2, TRANSACTION_EVENT_DAO_GR08P2,
    ]
    category_inserts = sorted(
        entry.path for entry in entries
        if entry.dao_fqcn == CATEGORY_DAO_GR08P2
        and entry.operation == "insert"
    )
    # Derivation of the expected order: the list is sorted() over the FULL
    # repository-relative paths, and both paths share the
    # `app/src/main/java/com/yourname/expensetracker/` prefix — so the
    # comparison is decided at the `service/...` vs `util/...` segment
    # ('s' < 'u'), putting LegacyDataMigrationService BEFORE
    # CsvExpenseImporter.  (The seed file's own row order is irrelevant
    # here; the pin below is the deterministic sorted() truth.)
    assert category_inserts == [
        LEGACY_DATA_MIGRATION_SERVICE_KT, CSV_EXPENSE_IMPORTER_KT,
    ]
    # The source-alias / explicit-parameter spellings: the
    # DatabaseBackupRepositoryImpl row spells the method-local alias dao,
    # the ExpenseGroupDao row spells the DAO-typed method parameter
    # memberDao, and the SettlementCalculator row spells the DAO-typed
    # method parameter settlementDao.
    restore_row = [
        entry for entry in entries
        if entry.method == "restoreReceiptAssets"
    ]
    assert len(restore_row) == 1
    assert restore_row[0].dao_accessor == "dao"
    assert restore_row[0].dao_fqcn == SCANNED_RECEIPT_DAO_GR08P2
    group_row = [
        entry for entry in entries
        if entry.method == "insertGroupWithMembers"
    ]
    assert len(group_row) == 1
    assert group_row[0].dao_accessor == "memberDao"
    assert group_row[0].dao_fqcn == GROUP_MEMBER_DAO_GR08P2
    settlement_row = [
        entry for entry in entries if entry.method == "recordSettlement"
    ]
    assert len(settlement_row) == 1
    assert settlement_row[0].dao_accessor == "settlementDao"
    assert settlement_row[0].dao_fqcn == GROUP_SETTLEMENT_DAO_GR08P2


def _gr08p2_policy_entries(tmp_path, rows):
    entries = []
    for position, row in enumerate(rows):
        entry, errors = build_policy_entry(row, position)
        assert entry is not None and not errors, (
            "GR-08p2 fixture row must be schema-valid: %s" % (errors,)
        )
        entries.append(entry)
    return entries


def _assert_gr08p2_exact_match(tmp_path, rows, select_method,
                               select_accessor, select_operation,
                               select_parameters=None, **overrides):
    """The exact GR-08p2 row identity matches; mutants never do.

    Target selection is fixed by ``(select_method, select_accessor,
    select_operation)`` -- optionally narrowed by ``select_parameters`` when
    several rows share the same (method, accessor, operation) triple (the
    two `write` rows and the two categoryDao insert rows); ``overrides``
    perturb exactly one identity field of the match query for the near-miss
    assertions.
    """
    entries = _gr08p2_policy_entries(tmp_path, rows)
    candidates = [
        entry
        for entry in entries
        if entry.method == select_method
        and entry.dao_accessor == select_accessor
        and entry.operation == select_operation
    ]
    if select_parameters is not None:
        candidates = [
            entry
            for entry in candidates
            if tuple(entry.parameter_types) == tuple(select_parameters)
        ]
    target = candidates[0]
    kwargs = dict(
        path=target.path,
        owner_fqcn=target.owner_fqcn,
        kind=target.kind,
        method=target.method,
        receiver=target.receiver,
        parameter_types=target.parameter_types,
        dao_accessor=target.dao_accessor,
        dao_fqcn=target.dao_fqcn,
        operation=target.operation,
    )
    kwargs.update(overrides)
    return match_mutation(target, **kwargs)


def test_gr08p2_exact_identity_matches(tmp_path):
    rows = _gr08p2_seed_rows()
    assert (
        _assert_gr08p2_exact_match(
            tmp_path, rows, "insertGroupWithMembers", "memberDao",
            "insertAll", select_parameters=INSERT_GROUP_WITH_MEMBERS_PARAMS,
        )
        is True
    )
    assert (
        _assert_gr08p2_exact_match(
            tmp_path, rows, "restoreReceiptAssets", "dao", "update",
            select_parameters=RESTORE_RECEIPT_ASSETS_PARAMS,
        )
        is True
    )
    assert (
        _assert_gr08p2_exact_match(
            tmp_path, rows, "write", "dao", "insert",
            select_parameters=RECEIPT_WRITE_PARAMS,
        )
        is True
    )
    assert (
        _assert_gr08p2_exact_match(
            tmp_path, rows, "getOrCreateCategory", "categoryDao", "insert",
            select_parameters=("String",),
        )
        is True
    )


def test_gr08p2_near_miss_wrong_overload_stays_unauthorized(tmp_path):
    rows = _gr08p2_seed_rows()
    # A wrong parameter list never matches the restoreReceiptAssets row.
    assert (
        _assert_gr08p2_exact_match(
            tmp_path, rows, "restoreReceiptAssets", "dao", "update",
            select_parameters=RESTORE_RECEIPT_ASSETS_PARAMS,
            parameter_types=RESTORE_RECEIPT_ASSETS_PARAMS[:-1],
        )
        is False
    )
    # A wrong Map spelling (no space) never matches the logDecision row.
    assert (
        _assert_gr08p2_exact_match(
            tmp_path, rows, "logDecision", "dao", "insert",
            select_parameters=LOG_DECISION_PARAMS,
            parameter_types=(
                PRIVACY_CAPABILITY, PRIVACY_DECISION, "Map<String,String>",
            ),
        )
        is False
    )


def test_gr08p2_near_miss_wrong_owner_stays_unauthorized(tmp_path):
    rows = _gr08p2_seed_rows()
    # The receipt lifecycle event writer row never matches the transaction
    # lifecycle event writer owner behind the same (write, dao, insert)
    # triple shape.
    assert (
        _assert_gr08p2_exact_match(
            tmp_path, rows, "write", "dao", "insert",
            select_parameters=RECEIPT_WRITE_PARAMS,
            owner_fqcn=ROOM_TRANSACTION_LIFECYCLE_EVENT_WRITER_FQCN,
        )
        is False
    )
    # A foreign coordinator owner never matches the disconnectConnection
    # row.
    assert (
        _assert_gr08p2_exact_match(
            tmp_path, rows, "disconnectConnection", "bankConnectionDao",
            "disconnect", select_parameters=("Long",),
            owner_fqcn="com.example.OtherBankCoordinator",
        )
        is False
    )


def test_gr08p2_near_miss_wrong_dao_stays_unauthorized(tmp_path):
    rows = _gr08p2_seed_rows()
    # The deleteMember row never matches the ExpenseGroupDao identity
    # behind the same memberDao accessor spelling.
    assert (
        _assert_gr08p2_exact_match(
            tmp_path, rows, "deleteMember", "memberDao", "update",
            select_parameters=("Long", "Long"),
            dao_fqcn=EXPENSE_GROUP_DAO_FQCN,
        )
        is False
    )
    # The insertGroupWithMembers row never matches the ExpenseGroupDao
    # self-DAO identity (the flagged mutation is the cross-DAO memberDao
    # insertAll, not the DAO's own insert).
    assert (
        _assert_gr08p2_exact_match(
            tmp_path, rows, "insertGroupWithMembers", "memberDao",
            "insertAll", select_parameters=INSERT_GROUP_WITH_MEMBERS_PARAMS,
            dao_accessor="insert",
            dao_fqcn=EXPENSE_GROUP_DAO_FQCN,
        )
        is False
    )


def test_gr08p2_near_miss_wrong_operation_stays_unauthorized(tmp_path):
    rows = _gr08p2_seed_rows()
    # The recoverPending row never matches a different mutating-query
    # operation behind the same intakeDao accessor.
    assert (
        _assert_gr08p2_exact_match(
            tmp_path, rows, "recoverPending", "intakeDao",
            "releaseStaleProcessing", select_parameters=("Int",),
            operation="insertOrIgnore",
        )
        is False
    )
    # The updateForecastAccuracy row never matches the insert operation
    # behind the same budgetForecastDao accessor.
    assert (
        _assert_gr08p2_exact_match(
            tmp_path, rows, "updateForecastAccuracy", "budgetForecastDao",
            "update", select_parameters=("Long", "Double"),
            operation="insert",
        )
        is False
    )


def test_gr08p2_shared_shape_distinct_callables_near_misses(tmp_path):
    """The same-shape rows are exact per (path, parameters) identity.

    The two `write` rows share the (dao, insert) shape across the receipt
    and transaction lifecycle event writers, and the two categoryDao insert
    rows share the (accessor, operation) shape across the legacy migration
    and the CSV import path; each row authorizes EXACTLY its own callable
    identity, so a swapped path or parameter list stays unauthorized.
    """
    rows = _gr08p2_seed_rows()
    # The receipt write row never matches the transaction write identity.
    assert (
        _assert_gr08p2_exact_match(
            tmp_path, rows, "write", "dao", "insert",
            select_parameters=RECEIPT_WRITE_PARAMS,
            parameter_types=TRANSACTION_WRITE_PARAMS,
        )
        is False
    )
    # The legacy migration row never matches the CSV importer identity
    # behind the same categoryDao insert shape.
    assert (
        _assert_gr08p2_exact_match(
            tmp_path, rows, "migrateCategories", "categoryDao", "insert",
            select_parameters=(SQLITE_DATABASE,),
            path=CSV_EXPENSE_IMPORTER_KT,
            owner_fqcn=CSV_EXPENSE_IMPORTER_FQCN,
            parameter_types=("String",),
        )
        is False
    )

