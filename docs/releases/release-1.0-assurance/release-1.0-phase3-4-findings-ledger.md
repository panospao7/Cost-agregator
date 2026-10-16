- PROCESS DEVIATION (2026-10-15, third): the lead edited workflows/active/release-1.0-assurance-handoff.md (Phase 3/4 approval)
  while app-check vr-20261015-202901-15748bbe was running on C* 53824bcd -> runner correctly returned STALE_RESULT
  (E_WORKTREE_CHANGED; Gradle BUILD SUCCESSFUL; lintDebug + testReleaseUnitTest 8089/7812/0/277 executed; verifyNoIgnoredGrowth
  printed G-IGNORE-01 on MainViewModelStressTest.kt:20 without failing; lint 805 warnings, baseline-filtered errors, one stale
  baseline entry). Remedy: handoff edit saved to build/release-lead-notes/handoff-pending.patch, file restored to C*, app-check
  re-run; all record edits held in build/release-lead-notes/ until the final gates finish. Disclosed to the user.
- Final gates part 1 on C* 53824bcd (clean): runner-smoke vr-20261015-201711-d6fde58f PASS; runner-protocol-tests
  vr-20261015-201724-9141811b PASS 330/330; unit-tests vr-20261015-202020-3c98a895 PASS, EXECUTED, 8089 run / 7813 passed /
  0 failed / 276 skipped.
## Phase 3 HZ-02 (release-horizontal-auditor, read-only, C* 53824bcd): PARTIAL
- F1 P2 (RG11-H2 class): RecurringLifecycleCoordinator.unlinkExpenseFromOccurrenceDetailed withTransaction :801 ->
  regenerateReminderDeliveriesForOccurrence :823 swallows nested runWrite{withTransaction} failures (:639/:666/:706, catches
  ~:658/:694/:740) -> silent outer rollback, returns Unlinked (:853) -> TransactionSideEffectPlanner :406-413 Completed. Occurrence
  stays PAID linked to deleted expense; low likelihood (constraint/FK/IO triggers).
- F2 P3: DatabaseBackupRepositoryImpl :152 MIN_SUPPORTED_SCHEMA_VERSION = 6 vs MIGRATION_BASELINE 145 -> v6-v144 backups fail
  late with generic error. F3 P3 (P1 if any shipped build < v145): docs claim destructive migration for < 145, actual = Room
  IllegalStateException on open, DatabaseModule :36 uncaught, rescue disabled -> crash loop (data preserved); ~8,600 lines dead
  legacy migrations. F4 P3: tracked app/src/main/java/.../data/database/_schema_tmp.json unreferenced.
- Nested-swallow sweep 42 sites: 3 unsafe (F1), rest safe/outermost; limits: helpers with plain DAO writes, @Transaction DAO
  defaults. Refuted: snapshot/migration parity 145-151, new unique indices safe, MIGRATION_150_151 consistent, no destructive
  fallback. Unresolved: shipped schema history < 145 (owner H-2); ExpenseRepository.insertAll/deleteAll, NotificationRepository
  .deleteAll direct writers. Runtime proof owed: MigrationTestHelper chain + TableInfo parity, F1 fault injection, < v145 open.
## Phase 3 HZ-03 (release-horizontal-auditor, read-only, C* 53824bcd): PARTIAL
- F1 P2 release-blocker candidate: restore runs in viewModelScope (BackupRestoreViewModel :173 -> :230), no BackHandler; Back during
  post-swap asset phase (DBRI :1585/:1649 suspension) -> CE rethrown :1330 -> enterCriticalRecoveryRequired("RESTORE_CANCELLED_AFTER_SWAP")
  :1371-1373; journal left VERIFYING/ASSETS_RESTORING; ASC :170-171 returns BLOCKED before recoverRestoreJournalState -> asset resume
  (:196-204) and rollback (:212-247) never run -> absorbing lock; only exit = clear data (also deletes safety backup/.pre_restore).
- F2 P3: cancellation cleanup DBRI :1377 / :2942 calls exit(false) directly (not exitAfterPreDestructiveAbort) -> finishCancelledRestore
  :919-923 latches CRITICAL RESTORE_CANCELLED_CLEANUP_FAILED on CE + WRE coincidence.
- F3 P3 privacy: decrypted cacheDir/costbackup_extract_* not cleaned by crash recovery (RJ cleanStagingFiles :892-903; ASC :244-246,
  :472-474, :516-517); no sweep.
- F4 P3 privacy retention (P2 if a delete-all flow uses resetDatabase): filesDir/safety_backups plaintext DB copies (keep-last-3) incl.
  pre-reset copy; no retention/erase path.
- F5 P3: VM :229-232 catch(Throwable) swallows CE. F6 P3 misleading checkpointWal comment.
- Refuted: WAL/SHM deletion at swap, zip-slip, checksum scope (AES-GCM), newer-schema bundles fail before destructive step,
  v145-v151 staged migration, bank tokens encrypted, rescue disabled, torn journal, R5 adoption, legacy .db import DEBUG-only.
## Phase 3 HZ-01 (release-horizontal-auditor, read-only, C* 53824bcd): PARTIAL (no P0/P1)
- P2 SubscriptionManagementViewModel :136-147 legacy convert() 24 h stale -> `?.convertedAmount ?: rawMonthly` sums raw foreign
  amounts into home total (and raw sum when home null); no partial flag; test uses relaxed converter mock (lens 14).
- P2 FinancialStressForecastEngine :362-371/:427-429 failed convert() only logged -> foreign recurring outflows silently dropped;
  isPartial (:172) ignores them; runCatching :362 swallows CE; blank display currency sums raw.
- P3: Analytics budget limits rate basis flips with freshness (BudgetRepository :111-121/:506 vs AnalyticsViewModel :1232-1245/
  :1303/:651); WarrantyTrackerViewModel :86-88 runCatching swallows CE + unavailable -> 0.0; ExpenseRepository :55-72 amount sort
  mixes currencies; MoneyAggregateBuilder :281 unknown currency labelled EUR + MultiCurrencyRepository :1102-1105 unknown bucket ->
  zero; InvestmentTracker :236 vs :391 mixed rate bases; legacy convert() (24 h, maxOf leg) vs convertOutcome (7 d, minOf leg)
  inconsistency across remaining legacy callers.
- Refuted: convertOutcome call sites all pass explicit policy; no wall-clock bypass in money/period logic; TimePeriodUtils/SQL month
  keys consistent; dashboard 0.0 fallback flagged partial; sweep fails closed; historical convertOutcome never falls back to latest.
- Unresolved: MonthlySavingsSweepUseCase :312-313 weekly/biweekly recurring counted once (possible P2 over-sweep);
  TotalsAggregationEngine :281 unavailable -> 0.0; storeRate UTC validDate; CSV baseAmount unrounded Double (scientific notation).
- PENDING cells (not read): MoneyNormalizationEngine, AnalyticsCurrencyNormalizer, budget autopilot/forecasting internals,
  health/tax/export/groups VMs, ExecuteFinancialQueryUseCase, SpendingMapViewModel, NL search, rate-refresh worker -> HZ-01b.
## Phase 3 verification batch 1 (release-finding-verifier, read-only, C* 53824bcd)
- HZ03-F1 RECALIBRATED, P2 kept, not a release blocker as worded: single Back does NOT cancel (custom NavigationController; BackupRestore
  hiltViewModel is Activity-scoped, MainActivity :873-876). Real trigger: Activity destroyed (non-config) post-swap — API 26-30 Back from
  Home finishes the root Activity (NavigationController :328), or "Don't keep activities" -> viewModelScope cancelled ->
  finishCancelledRestore -> CRITICAL RESTORE_CANCELLED_AFTER_SWAP -> startup BLOCKED before journal recovery -> permanent lock (harsher
  than a process kill, which recovers via journal). No test. Deciding test: API 30 instrumented, finish Activity during ASSETS_RESTORING.
- HZ02-F1 RECALIBRATED P2 -> P3 latent: mechanism real (runWrite joins the outer transaction; nested failure silently rolls back) but no
  realistic trigger (IGNORE inserts, pre-checked unique index, FK parent present; disk-full/IO would surface on the outer insert).
## Phase 3 HZ-04 (release-horizontal-auditor, read-only, C* 53824bcd): PARTIAL (no P0/P1)
- F1 P2: TransactionSideEffectPlanner recurring_unlink :409-414 maps Error/NoMatch -> Completed; recurring_reconcile :370-377 and
  recurring_occurrence_matching :333-336 map Error -> Skipped(NOT_APPLICABLE); FailedRetryable is never retried (label only,
  PostCommitSideEffectEvidenceService :143).
- F2 P3 RecurringRuleLifecycleCoordinator.updateRule stale snapshot (:340 read outside tx; full-row update :374/:490) can re-activate a
  concurrently deactivated rule; deactivateRule (:67-98) no existence/state check, hard-coded oldStatus. F3 P3 recurring rule events via
  lifecycleEventDao.insert (:86,:127,:195,:256,:280) instead of RecurringLifecycleEventWriter (LEGAL_PATHS). F4 P3 TLC.updateExpense
  full-row update from caller object (:1146). F5 P3 ReceiptLifecycleCoordinator :517-526 legacy dedup full-row update (CAS race ms).
  F6 P3 resolveHomeCurrency DataStore read inside outer receipt tx (:538 via :1187/:1604). F7 P3 ReceiptLinkService ignores
  sourceLinkWriter Failed for AUTO_MATCH (~:399-402) vs TLC treats Failed as fatal (:715-716).
- Refuted: side effects inside tx; side effects for rolled-back rows; duplicate events on retry; delete snapshot; business patch merge;
  runWrite equivalence; runner CE handling.
- Unresolved: ReceiptRepository.deleteReceipt/clearAllScannedReceipts direct DAO deletes without events (callers?); FailedRetryable raw
  e.message (privacy); bestEffortEvent catches :765/:811 inside outer receipt tx (possible RG11-H2-class silent rollback) -> verifier.
## Phase 3 HZ-05 (release-horizontal-auditor, read-only, C* 53824bcd): PARTIAL (no P0/P1)
- Inventory: 10 workers (retention, location backfill, bill reminder, receipt matching, warranty, daily briefing, merchant key backfill,
  notification intake, snooze/dismiss); no setForeground/expedited/exact alarms; Configuration.Provider OK.
- F1 P2: daily briefing oneShotPolicy REPLACE (WorkerSpec :94) + unconditional enqueue to next midnight (WorkerSpecScheduler
  :246-293) on every Application.onCreate (AppStartupCoordinator :51,:79-84,:712-720; WorkerRegistry :110-111; AiWorkSchedulerImpl
  :29-35) -> pending/running briefing replaced when WorkManager wakes the process -> briefing indefinitely postponed (no data harm);
  regression from U-WORKER-04; tests only check the policy argument.
- F2 P3 merchant_key_backfill REPLACE on every process start. F3 P3 intake unique names differ ("$intakeId" vs
  "notification-intake-$id") -> KEEP dedupe fails (claim likely protects). F4 P3 Dismiss/SnoozeReminderReceiver plain enqueue (double
  tap = two workers) vs LEGAL_PATHS :524. F5 P3 WorkerSpecScheduler :170/:308 raw e.message in diagnostic metadata (check
  SafeEventMetadata sanitizer). F6 P3 WorkerExecutionGuard :589-594 run-logger start failure always Retry, unbounded.
- Refuted: FGS type for SystemForegroundService (no foreground workers); exact alarms/expedited; swallowed CE in workers; lease race;
  premature SUCCESS; MAX_ATTEMPTS semantics; BootReceiver.
## Final gates part 2 (C* 53824bcd)
- app-check vr-20261015-205206-9a2e9034 PASS (lintDebug 0 errors / 805 warnings / baseline-filtered; G-TIME-01, G-MONEY-RAW-01..07,
  DB access, G-IGNORE-01 PASS; test tasks UP-TO-DATE after unit-tests -> release unit-test evidence from the earlier stale app-check run
  only). static-guards vr-20261015-205525-95e5e818 PASS 25/25 (freshness SKIP). assemble-release vr-20261015-211329-5587f60d PASS
  (minifyReleaseWithR8 + lintVitalRelease executed). release-artifact-evidence vr-20261015-211552-feb84df9 FAIL E_BADGING_INCOMPLETE:
  script regex expected legacy aapt "sdkVersion:" while aapt2 36 prints "minSdkVersion:" (tests used fabricated legacy output).
  Lead fix (debug iteration 1): regex accepts both + 2 regression tests calling parse_badging; committed as the new C*
  (scripts only; app sources identical to 53824bcd). Re-run static-guards + assemble-release + evidence on the new C*.
## Final gates part 3 BLOCKED (C* 0a9cab1c): static-guards Start -> E_DUPLICATE_OR_OVERLAPPING_VALIDATION (dedup ignores git_revision;
   clean trees share fingerprint e3b0c442… = sha256("")). Runner refused correctly; owner decision pending: REQUIRED_FINAL_GATE
   override vs runner fix (dedup compares git_revision).
## Phase 3 HZ-06 (release-horizontal-auditor, read-only, C* 53824bcd): PARTIAL (no P0/P1)
- F1 P3 NEEDS_RUNTIME: no android:dataExtractionRules (manifest :32 allowBackup=false only; targetSdk 35) -> device-to-device transfer
  on API 31+ copies unencrypted Room DB (raw notification/OCR tables) + secure_api_keys prefs (keystore key does not move).
- F2 P3: SecureKeyStorage (getKey/hasKey/storeKey) no exception handling; lazy EncryptedSharedPreferences.create can throw on
  keyset mismatch; provider apiKey getters evaluated before the privacy gate (e.g. CloudReceiptAssistService :107/:114).
- F3 P3: no -assumenosideeffects for android.util.Log -> ~34 files log throwables/stack traces in release (geocoding providers,
  SpendingMapViewModel :241/:797/:847, MerchantKeyBackfillWorker :75/:114, DataRetentionWorker :252/:324/:447,
  FinancialRescueCoordinator :914, AndroidSpeechInputGateway :123; Nominatim :163 server error text; DatabaseIntegrityScanner :407-409).
- F4 P3 latent: CloudAiPrivacyGate allows CLOUD_AI_RECEIPT_OCR without receiptImageUploadAllowed (no production use).
- F5 P3: CloudReceiptAssistService.suggest gate check (:125) vs allowImage (:138) — consent toggle race; prepareReceiptAssist does not
  re-check cloudAllowed/receiptImageUploadAllowed.
- Design note: no FLAG_SECURE anywhere (product decision).
- Refuted: Timber planted only in debug; API keys in headers; no cleartext; no analytics/crash SDKs or logging interceptor; exported
  components minimal; debug screens gated; privacy settings fail closed; 15 retention targets registered; sampled metadata bounded.
- PENDING: redactor patterns, 7 provider prepare paths, PrivacyAuditLoggerImpl, DiagnosticSideEffectEventWriter, Backup/AtRest
  encryption, ExportAnonymizer, clipboard listener, receipt image retention.
## Phase 3 verification batch 2 (release-finding-verifier)
- HZ01-A CONFIRMED P2: subscription screen shipped/reachable (FeatureConfig :181-189 -> MainActivity :857; TotalCostCard labelled home
  currency); convert() null for unsupported/stale -> raw foreign sum; no partial flag; test gap.
- HZ01-B CONFIRMED P2 (CE part P3): Dashboard F8 Financial Stress Forecast widget (ComputeDashboardWidgetsUseCase :1229/:1264) understates
  foreign recurring obligations; isPartial/recurringObligationsPartial false.
- HZ04-F1 RECALIBRATED P3: else->Completed effectively unreachable; Error only from an impossible-state race; diagnostics-only.
- HZ04-Q NEEDS_RUNTIME (potential P1, RG11-H2 class): bestEffortEvent is TransactionLifecycleCoordinator :335, called :384/:406/:600/
  :765/:811 — whether its swallowed event inserts run inside an outer transaction (silent rollback with success) not yet settled ->
  focused verifier dispatched.
- HZ04-Q RECALIBRATED -> latent P3 (RG11-H2 class): TLC bestEffortEvent :335-341 swallows non-CE exceptions from plain
  transactionEventDao.insert at :384 (CREATE_ATTEMPTED, every create), :406/:600 (validation failed), :765 (duplicate skipped), :811
  (insert conflict); also TLC :435-458 and writeDuplicateEvent :2591-2620. Nested under outer tx for ReceiptLifecycleCoordinator :1187,
  ReviewQueueRepository :261/:668, ManualExpenseRepository :163. transaction_events has no unique/FK/CHECK/trigger -> only transient
  environmental failures; persistent ones fail the next unswallowed insert loudly. No test. Fix direction: rethrow inside outer tx or
  SAVEPOINT per best-effort insert, together with HZ02-F1 (R0-1 class). Deciding test: RAISE(ABORT) trigger on transaction_events.
## Phase 3 HZ-07 (release-horizontal-auditor, read-only, C* 0a9cab1c): FAIL
- P1 (candidate, verifier dispatched): release binds DemoTaxRateProvider (TaxModule :25) returning PERCENT (GR 24.0, unknown 20.0 :25/:30)
  while TaxConfiguration.getVatRate() is a FRACTION (0.24, :39); TaxEstimator :127-133 prefers provider rate when > 0 -> vatPaid =
  deductible*24/25 (:136) -> shown TaxConfigurationScreen :423 and subtracted from net income :446; provider uses settings country (:128)
  vs screen-selected config (VM :107); tests use relaxed mock (TaxGoldenScenarioTest :61) / fraction (TaxEstimatorTest :476).
- P2: SmartBillNegotiationEngine :98-99 hard-coded region GR/EUR; StaticMarketRateProvider seed lastUpdatedAt 0 (:35); UI presents
  "Potential Savings … per year" / "already at competitive rates" with no estimate/source/staleness label.
- P3: FeatureConfig :236-241 privacy entry mislabeled as Backup & Restore; test-only no-op constructors in RestoreMaintenanceMode :51-70 /
  DBRI :108-137 (no prod callers); TaxConfigurationViewModel :76-79/~:117-120 render ${e.message} + swallow CE; TaxEstimator :130;
  SmartBillNegotiationEngine merchant names in Timber (debug-only planting).
- Refuted: bank gated (requireStubMode, FeatureConfig, deep links); review debug menu gated; Rescue not exported/disabled;
  LegacyDataMigrationService only via gated Debug route; investments user-entered prices; DebugExpenseAuditWriter is production audit.
- H-1 owner questions: ship Tax with demo VAT data? negotiation GR-only static benchmarks? "Voice search (Coming soon)" visible?
  isNew flags on tax/export/recurring/groups/backup/subscriptions? carbon + price protection data sources unchecked.
- Verifier: HZ07-P1 RECALIBRATED -> P2 (defect CONFIRMED; display-only, labelled Estimated): TaxModule :25 always DemoTaxRateProvider;
  no /100 normalization; TaxEstimator :136 vatPaid = deductible*(24/25); tax screen reachable in release (FeatureConfig :190-198,
  MainActivity :863-867; TaxEstimator :28-34 "contained, NOT exposed" comment false). Worked: GR, EUR 1,000 deductible -> shows
  EUR 960 instead of ~193.55, subtracted from net income; provider uses saved country (always GR; setTaxCountry never called) so a US
  selection still shows 960. Lead view: must-fix or hide Tax for 1.0 (owner H-1).
- Verifier: HZ07-P2 RECALIBRATED -> P3: negotiation reachable; non-EUR subscriptions skipped (currency amounts refuted) but savings
  labelled in home-currency symbol; GR hard-coded; fallback to cheapest quote of service type; no estimate/source label; invented
  savings (Netflix 13.99 -> "Save EUR 5/month"); advisory only.
## Phase 3 HZ-08 (release-horizontal-auditor, read-only, C* 0a9cab1c): PARTIAL (no P0/P1)
- Invariant -> guard -> blind spot table recorded (expense writer opt-in WARNING only; event writer basename allowlists/name matching;
  no enqueue legal-path rule; cancellation guard blind to swallow-inside-withTransaction; PII guard misses trailing Throwable in
  android.util.Log / Log.wtf; money guard misses `normalized ?: raw`; no destructive-migration check; ignored baseline 29 vs 26 actual,
  assume*() skips uncounted).
- F1 P2: 32 android.util.Log throwable calls in 15 files reach release logcat (no -assumenosideeffects); LocationBackfillWorker :111 also
  logs expenseId + merchantToken; PII guard rules miss it (verify_pii_logging_boundaries.py :97/:137). (HZ06-F3 class)
- F2 P2: RecurringArchitectureGuardTest :240 REQUIRES workManager.enqueue in reminder receivers, contradicting LEGAL_PATHS :524
  (HZ05-F4); unguarded enqueueUniqueWork outside WorkerSpecScheduler (intake coordinator :216/:487, recovery scheduler :71,
  ReceiptMatchingWorker :324).
- F3-F8 P3: DefaultExpenseCategoryAssignmentService :45 no opt-in (warning only); DirectEventDaoInsertGuardTest basename exemption +
  label drift (recurring/warranty event inserts invisible); bestEffortEvent swallow invisible (HZ04-Q); CarbonFootprintCalculator
  :152/:466 raw effectiveAmount fallback into CO2 totals; no destructive-migration guard; ignored baseline slack.
- Proposed guard additions 1-8 (owner approval; fold into deferred guard hardening).
## Phase 4 V-A (release-vertical-auditor, read-only, C* 0a9cab1c): VJ-01 PARTIAL, VJ-02 PARTIAL
- P1 candidate (verifier dispatched): ui/util/AmountInputSanitizer.sanitize keeps only digits and '.', deleting ',' -> "12,50" -> "1250"
  saved as Success (KeyboardType.Decimal can emit ',' in comma locales); also share amount.
- P1 candidate (verifier dispatched): no release-visible way to detect/enable Notification Listener access (ACTION_NOTIFICATION_
  LISTENER_SETTINGS only in DebugScreen :143, debug-gated); no isNotificationServiceEnabled check -> intake silently empty.
- P2: ReviewViewModel.handleResult :423-425 drops Result.Error.message ("Failed to approve" only). P2: POST_NOTIFICATIONS denial
  dialog re-prompts every launch (MainActivity ~:473-494), no settings fallback.
- P3: AddExpenseViewModel save catch ~:432 no CE rethrow + raw e.message to UI; ManualExpenseRepository ~:189 exception message;
  recurring rule insert bypasses recurring coordinator (TODO ~:156); AUTO_REJECT rawId=null; approveReview e.message fallback;
  live capture encrypt without local try/catch.
- Good: VJ-01 double tap/validation/duplicate controlled; VJ-02 dedupe, bounded retries, stale recovery, no double expense after
  process death (canonical duplicate), missing currency blocked at approve. Missing runtime: real-Room/WorkManager e2e, listener device.
- Verifier V-A: (A) comma decimal CONFIRMED P1: AmountInputSanitizer :36 digits+'.' only; "12,50" -> 1250 saved (limit only > 1M,
  AddExpenseViewModel :297); "1.234,56" -> 1.23; callers AddExpenseViewModel :157/:266/:480, OwnershipValidator :52,
  ReceiptScanViewModel :549, BudgetScreen :929; ReviewScreen/TransactionsViewModel use parseAmount (comma-safe); SavingsGoalsScreen
  :386, ManualRecurringExpenseScreen :555/:559, SharedExpenseGroupsScreen :797/:805/:1032 toDoubleOrNull (refuse comma); no sanitizer
  test. Keyboard emission NEEDS_RUNTIME (el_GR Gboard). (B) notification listener access CONFIRMED P1: no release-reachable check/
  prompt/status (ACTION_NOTIFICATION_LISTENER_SETTINGS only in debug-gated DebugScreen :143); never-granted access -> silent empty capture.
- Proposed batches (owner approval pending): R16 shared locale-aware amount parsing for all amount inputs; R17 listener-access status +
  settings intent + "capture inactive" state.
## Phase 4 V-B (release-vertical-auditor, read-only, C* 0a9cab1c): VJ-03 PARTIAL, VJ-19 PARTIAL
- F1 P2 NEEDS_RUNTIME: ReceiptScanViewModel :461 blank OCR currency silently defaulted to home currency (vs coordinator rule :640-643);
  no "currency unverified" indicator.
- F2 P2 (potential double expense; verify): ReceiptLifecycleCoordinator :643-662 inserts PendingReview for low-confidence/blank
  currency/null total even when createReview=false; createExpenseAndLinkReceipt (:1599-1633)/ReceiptLinkService never resolve it ->
  stale review may be approved later -> second expense?
- F3 P3 orphan temp camera file on cancel + generic catch :740-744 skips cleanupUncommittedAsset. F4 P3 cancelled picker / process-death
  scan loss without feedback. F5 P2 WarrantyTrackerViewModel :194-195 catch(Exception) swallows CE + raw e.message in UI. F6 P2
  WarrantyTrackerViewModel :147-169/:203-223 unguarded writes, no error state.
- Good: atomic create+link, all duplicate paths, CE + NonCancellable cleanup, tax allocation conservation, stale scan seq, isSaving;
  WarrantyExpirationWorker durable deliveries + guard + permission SKIPPED.
## Phase 4 V-C (release-vertical-auditor, read-only, C* 0a9cab1c): PARTIAL
- V-C1 P1 candidate: ManualRecurringExpenseViewModel :162-174 "mark paid" only advances rule.nextDate (advanceNextDate :275): no
  expense, occurrence stays PLANNED, reminders still fire, forecasts unpaid; RecurrenceCalculator drift (31 -> 28).
- V-C2 P1 candidate: occurrences generated only at create/activate/update with 12-month window (coordinator :164-168/:226-230/:421-422);
  RecurringLifecycleCoordinator.generateOccurrences has no production caller -> after ~12 months no occurrences/reminders; missed
  periods while closed silently dropped.
- V-C3 P2: BillReminderWorker default notification policy SKIP_SUCCESS -> deliveries stay SCHEDULED; on grant, all past windows burst.
- V-C4 P2: addRecurringExpense (:99) no currency/category -> entity default EUR -> never auto-matches for non-EUR users.
- V-C5 P3 disabled channel marked SENT; V-C6 P3 e.message in UI; V-C7 P3 FAILED_TRANSIENT uncapped.
- Good: transactional create/update, idempotent materializer/deliveries, terminal never downgraded, fixed month anchor, atomic claim,
  durable snooze/dismiss, paid suppression.
- Verifier V-C/V-B: V-C1 CONFIRMED, RECALIBRATED P1 -> P2 ("Mark as Paid" strings :669, screen :427-440 -> advanceNextDate :275-294
  only updateNextDate + event; contradicts BillReminderManager :145-164 removal of markBillPaid; reminders keep firing via
  BillReminderWorker :95). V-C2 CONFIRMED for reminders/planned rows/auto-link, RECALIBRATED P1 -> P2 (forecast/cashflow/stress use
  projectOccurrences, unaffected): materialization only at create :180 / activate :242 / update :517; generateOccurrences only via
  DeprecationLevel.ERROR path; no worker extends the window -> after 12 months reminders/planned rows stop (P1 if reminders are a 1.0
  headline feature — owner). V-B F2 double expense REFUTED (ReceiptLinkService :233-243 rejects re-link; approve rolls back);
  P3 residual: un-approvable PENDING review inflates the queue.
## Phase 3 HZ-01b1 (release-horizontal-auditor, read-only, C* 0a9cab1c): PARTIAL
- SETTLED: weekly/biweekly counted once is REFUTED (sweep window <= 5 days; shortest frequency WEEKLY).
- F1 P2 money: MonthlySavingsSweepUseCase :216 safeSweepAmount = underspend - riskBuffer; knownUpcoming only feeds the buffer (fallback
  15% of upcoming; Monte Carlo p75-p50 cancels the constant, MonteCarloSpendingSimulator :137) -> worked: budget 1000, spent 700, rent
  80 due tomorrow -> sweep 270, leaves 30 for 80 rent; MonthlySavingsSweepUseCaseTest :300-336 pins 270 (test locks the defect).
- F2 P3 latent: FinancialHealthCalculator :56-60 currency-agnostic default targets; runBlocking :75/:85; requireCompleteMoneyInput :95 all-or-
  nothing; calculateHealthScores has no production caller (no health VM).
- Clean: MoneyNormalizationEngine, AnalyticsCurrencyNormalizer, BudgetAutopilotEngine (fail closed), sweep CE rethrow.
- Unresolved: overdue unprocessed rules excluded from upcoming and spent (P3?); simulate without estimatedWeeklyRecurring (FCST-4);
  MoneyNormalizationEngine :303 effectiveAmount*rateUsed vs convertedAmount on cross-rate path; autopilot :440-480,
  BudgetForecastingEngine unread.
## Phase 4 V-D (release-vertical-auditor, read-only, C* 0a9cab1c): PARTIAL
- F-1 P1 candidate: home-currency change does not re-normalize persisted baseAmount/baseCurrency (CurrencySettingsRepositoryImpl
  :56-65, CurrencyManagementViewModel :165-189, "callers MUST trigger a full re-normalization", no caller) -> persisted-snapshot
  consumers (Expense.normalizedEffectiveAmount :176-185, sort ExpenseRepository :55-72, amount filter TransactionsViewModel :898-903,
  date-header totals TransactionsScreen :522-541) read old-currency base as new currency; live analytics path correct -> disagreement.
- Offline first-install foreign entry: conversion failure only logged (TLC :572-577), baseAmount 0.0 with baseCurrency default "EUR"
  (not the "" sentinel), never backfilled when rates arrive.
- F-2 P2: CalculateFinancialForecastUseCase :30-48 not reactive to home-currency change or rate refresh -> forecast/runway/budget cards
  stale/mislabelled up to 24 h.
- F-3 P3 budget anchor shifts after timezone change; F-4 P3 latest-basis budget conversion returns raw on failure (analytics family);
  F-5 P3 repeat alerts per cooldown; F-6 P3 partial-data alert under-count (disclosed). Good: BudgetRepository period-end basis,
  unavailable -> UNKNOWN, bounded rollover, DST-safe period math, HomeViewModel unavailable-not-zero.
## Verifier on V-D (release-finding-verifier, read-only)
- F-1 RECALIBRATED P1 -> P2, pre-existing + documented deferral (docs/development/FUTURE-WORK.md:141 CURR-6). Wrong: AMOUNT sort
  (ExpenseRepository :55-72) and amount filter (TransactionsViewModel :898-903) mix base currencies after a home switch. REFUTED: header
  totals (TransactionsScreen :526-540 single-currency raw sum or hidden), dashboard/analytics/MoneyRadar/forecast (AnalyticsCurrencyNormalizer
  :146-153 re-converts at read time). Minor: export per-row base columns (ExpenseExportMapper :44-46). Reachable via currency tile, no warning.
  Deciding test: EUR home + USD100; switch USD + USD95; AMOUNT_DESC 100 first / min-95 filter keeps both (expected FAIL), dashboard 195 USD (PASS).
- Offline create: CONFIRMED P3 pre-existing. baseAmount 0/baseCurrency "EUR" defaults (TLC :572-578, Expense.kt :146-148) vs "" sentinel
  elsewhere (:544-548, :1121-1125); no backfill; sort/filter use raw foreign amount; headers hidden; analytics excludes then recovers.
## Phase 3 HZ-01b2 (release-horizontal-auditor, read-only): PARTIAL (22/22 calls)
- F1 P2: ECB rate refresh gated on PrivacyCapability.CLOUD_AI_GENERAL (CurrencyRatesRepositoryImpl :48) -> with cloud AI off (default)
  refresh returns 0, generic CURRENCY_RATE_REFRESH_UNAVAILABLE (CurrencyManagementViewModel :244); no rate worker; conversions degrade
  to manual rates/unavailable. Needs verification (intentional network-egress policy?).
- F2 P3: generic CSV/JSON amounts via raw Double.toString (ExportOptionsViewModel :679/:680/:691) -> 1.25E7, float artefacts;
  regression vs deprecated formatForExport; non-finite baseAmount -> 0.0 (ExpenseExportMapper :45).
- F3 P3: TotalsAggregationEngine :281 Unavailable -> 0.0, :296 drops zero past years without marker.
- F4 P3 (if tax reachable): TaxEstimator :121/:138 subtracts EUR deductions from country-currency income without conversion.
- Refuted: CSV decimal locale, settlement math (integer cents, single currency), NL query totals, spending map markers, tax year fraction,
  validDate basis. Residual: ECB publication-day timing P3; GroupSettlementBalancePolicy :32 latent; SpendingMapViewModel :593 Timber.w
  merchant+amount (privacy, depends on release tree). Pending: accounting exporters, ExecuteFinancialQueryUseCase >:300,
  SharedExpenseGroupsViewModel :111 sumOf totalSpent, KWD 3-decimal rounding.
## Verifier on HZ-01b2 (release-finding-verifier, read-only)
- F1 CONFIRMED P2: CurrencyRatesRepositoryImpl :48-51 gates ECB fetch on CLOUD_AI_GENERAL, undocumented as policy (LEGAL_PATHS :379 and
  EffectiveCloudAiPolicy :21 = AI only); no network capability, no worker. UI shows raw code "CURRENCY_RATE_REFRESH_UNAVAILABLE"
  (CurrencyManagementScreen :63/:148, not a string resource) and "Offline Mode ... Connect to internet" (strings.xml :1299-1301), misleading.
  Fresh-install default for cloud AI not confirmed off. Proposed fix candidate (owner decision): own network capability or explicit consent copy.
- Timber merchant/amount: REFUTED for release (only DebugTree, AppStartupCoordinator :681-684 behind !DEBUG return).
- Accounting exporters: REFUTED as defect (formatForExport %.2f Locale.US, CsvCellSanitizer formula guard, sanitizeIif); nit 3-decimal.
- Group total: data model allows per-row currency (GroupExpense :111) vs summing to defaultCurrency (SharedExpenseGroupsViewModel :111/:116);
  write-path enforcement unverified -> P2 if reachable else P3 (fold into V-G).
- 3-decimal currencies: RECALIBRATED P3, no KWD/BHD/JOD literals; catalog unverified (fold into V-G).
- F2 CONFIRMED P3; introduced by 87234e48 (2026-05-10, in bug-fixes history) -> pre-campaign, not a campaign regression.
## Phase 4 V-F (release-vertical-auditor, read-only): PARTIAL (34/35)
- V-F-1 P2 candidate (possible crash class): SavingsGoalsViewModel :327-343/:351-397/:432-436 bare viewModelScope.launch -> repository
  DatabaseWriteBarrier.checkWritesAllowed throws DatabaseAccessBlockedException during BACKUP_EXPORTING (MainActivity :460 app continues)
  -> uncaught -> process death; "created" snackbar before write. Possibly shared by other VMs -> verify cross-app.
- V-F-2 P2: manual contribution non-atomic (increment :385 then ledger :387-393, comment :399-403 admits), no target cap (105/100).
- V-F-3 P2/P3(product): default backup redacted=true -> receipt images dropped (DatabaseBackupRepositoryImpl :617-619), not disclosed.
- V-F-4 P3 zero-byte .costbackup on pre-write failure; V-F-5 P3 no password policy; V-F-6 P3 Restart Now = exit(0) no relaunch,
  unwired onRestartRequired, dead dismissRestartRequired; V-F-7 P3 Timber.w(e) (debug-only in release per HZ-01b2 verifier).
- Clean: sweep acceptance coordinator (single tx, barrier, 3-layer idempotence), crash rerun, goal-delete cascade, restore ordering
  and failure/rollback paths, bounded UI messages, SAF location. No scheduled backup worker.
## Phase 4 V-E (release-vertical-auditor, read-only): PARTIAL (24/35), no P0/P1 — UNVERIFIED
- V-E-1 P2 challenges raw-sum mixed currencies via DEPRECATION_ERROR DAO sums (SpendingChallengeManager :60/:214-226). Tile live.
- V-E-2 P2 challenge completion/failure never shown; challenges vanish (Manager :29-41).
- V-E-3 P3 no-spend streak DST misalignment (:68-74); V-E-4 P3 REDUCE_SPENDING zero baseline fails on first purchase.
- V-E-5 P3 raw e.message to UI (SpendingChallengesViewModel :104, TaxConfigurationViewModel :79/:123 -> Screen :82-84).
- V-E-6 P2 investment tile dead end: Add button navigateBack (MainActivity :732-736), no addHolding caller; "0.00" portfolio.
- V-E-7 P2 investment ignores error/loading/data quality; failed conversion shows 0.00 home.
- V-E-8 P3 latent InvestmentTracker history/allocation issues (no UI caller).
- V-E-9 P2 US tax brackets end at 95,375 -> income above untaxed (TaxConfiguration UsTaxConfiguration; TaxEstimator :209-221).
- V-E-10 P2 tax country selection in-memory only; persisted GR/EUR; USD income minus EUR deductibles labelled USD; VAT uses GR.
- V-E-11 P3 tax notes/isPartial/warnings never rendered; netIncome double-subtracts VAT; tile ships (FeatureConfig :191) despite
  "not exposed" comment (TaxEstimator :28-34). -> strengthens H-1 "hide tax" option.
- V-E-12 P2 analytics % change compares month-to-date vs full prior N days (AnalyticsViewModel :358/:430-432/:497); YoY YTD vs full year.
- V-E-13 P3 analytics partial data handling.
- Fine: analytics unavailable-vs-zero typed, CE rethrown, split-share SQL parity, zone-aware ranges, addHolding atomic, challenge writes barriered.
## Verifier on V-F (release-finding-verifier, read-only, 26 calls)
- V-F-1 CONFIRMED P2 (not P1): reachable (MainActivity :460 no lock, Activity-scoped VMs, export continues off-screen,
  DatabaseBackupRepositoryImpl :652-770 window); no CoroutineExceptionHandler/default handler; Savings VM :328-342/:352-385/:433-435
  crash. Expense entry SAFE (TLC :355-357/:2453-2454, AddExpenseViewModel :439). Additional site HomeViewModel :557-567 ->
  PlannedExpenseRepository :61. Not exhaustive -> fix batch needs a sweep. Pre-existing (barrier dbfb3170 2026-05-10).
- V-F-2 RECALIBRATED P3: balance correct, DataStore history may miss; +10 hidden on completed goals; overshoot < 10/tap.
- V-F-3 CONFIRMED P2: default backup redacted (no images + sanitizeExport :721-722), undisclosed; docs/reference/domain-quick-reference.md
  :48 claims images included. NEEDS_RUNTIME: what sanitizeExport strips on restore.
- V-F-6 CONFIRMED P3: exit(0) no relaunch; in-screen button unwired (BackupRestoreScreen :38/:360, MainActivity :874).
## Phase 4 V-G (release-vertical-auditor, read-only): PARTIAL (32/35)
- Scope: CSV/JSON file import is debug-only (DebugScreen :1447-1454, BuildConfig.DEBUG gate); release import = bank statement via
  ReviewViewModel :1080 -> processBankStatement (NOT audited).
- V-G-1 P2 (unverified): export date range uses DatePicker UTC-midnight millis with exclusive end (ExportOptionsScreen :323-338,
  ExportOptionsViewModel :195-200) -> picked end day excluded; UTC+ start-day rows dropped.
- V-G-2 P2 (unverified, possible data loss): GroupExpense FK CASCADE on expenseId (GroupExpense.kt :79-85); deleting the linked
  personal expense may silently delete the group expense for all members.
- V-G-3 P3 zero-byte/partial export file, text/plain MIME (.csv.txt), catch Throwable swallows CE, raw t.message (ExportOptionsScreen :57-84/:220/:271).
- V-G-4 P3 clipboard copy File.readText whole export (:212-218).
- V-G-5 P3 retired HRK offered in group currency picker (SharedExpenseGroupsScreen :674).
- V-G-6 P3 settlement suggestions only, deprecated greedy simplifyBalances; SettlementCalculator dead; 0-decimal split display sums wrong.
- V-G-7 P3 dormant debug importer issues.
- Answers: mixed-currency group rows NOT writable (5 coordinator rejection sites) -> SharedExpenseGroupsViewModel :111 P3 latent;
  no 3-decimal currencies in SupportedCurrency; group expenses create personal expense atomically (createExpenseDbOnlyV2).
- Extra sites: SharedExpenseGroupsViewModel :178/:207/:326 catch Exception w/o CE rethrow, e.message to UI.
- Clean: paged export, cancel cleanup, read barrier + privacy gate, encrypted export fail-closed, group create atomic via coordinator.
## Verifier on V-G (release-finding-verifier, read-only)
- V-G-2 CONFIRMED P2 (data loss): delete offered for every row (TransactionsScreen :559, dialog :1545-1567 no group warning);
  hard delete TransactionsViewModel :476-480 -> ExpenseRepository :418-419 -> TLC :2444-2492 expenseDao.delete; no isSharedExpense
  guard; GroupExpense FK CASCADE (:81-85) deletes group_expenses row (payer/total/split) -> group balances change silently.
  Edit desync of totalAmount NEEDS_RUNTIME.
- V-G-1 CONFIRMED P2: ExpenseDao :1404/:1460 `date >= start AND date < end` with raw UTC-midnight picker values; Athens end=today
  excludes everything after 03:00 local; same-day range rejected (:176); chip shows previous day west of UTC (:426).
- V-G-8 P3 bank-statement exact duplicate shown as "Failed to parse statement" (Duplicate branch unreachable, ReviewViewModel :1097).
- V-G-9 P3 amount/currency in PendingReview.notificationText/event metadata; Timber.e debug-only in release.
- V-G-10 P3 NEEDS_RUNTIME partial success reported as failure, retry blocked by hash.
- V-G-11 P3 NEEDS_RUNTIME identical rows within one statement collapse.
- Refuted: import writes only PendingReviews (no direct expense writes), write barrier checks (minor gap :917-950), CURRENCY_UNKNOWN
  rows skipped, CE rethrown. Unverified: BankStatementParser comma/thousands decimals (-> V-I / R16 scope).
## Verifier on V-E (release-finding-verifier, read-only) — all pre-existing (no diff since campaign kit 84d819be except unrelated string)
- Visibility: investment/challenges/analytics/tax tiles all in FeatureConfig.allFeatures; availableFeatures removes only bank-connections.
- V-E-1 CONFIRMED P2 (EFFECTIVE_AMOUNT_SQL raw amount; JPY 5000 fails EUR 100 challenge). V-E-2 CONFIRMED P2 (outcome lost).
- V-E-6 CONFIRMED P2 (investment Add = navigateBack, addHolding no caller). V-E-7 RECALIBRATED P3 (no holdings creatable).
- V-E-9 CONFIRMED P2: USD 200,000 -> 16,290 (8.1%) vs correct 42,832 (21.4%).
- V-E-10 CONFIRMED P2: country/currency not persisted; TaxEstimator :28-34 "NOT exposed" comment false.
- V-E-12 CONFIRMED P2: steady 30/day on 2026-10-16 shows "-48.4% vs last period"; same for week/quarter/year/today.
## Phase 4 V-H (release-vertical-auditor, read-only): PARTIAL (cap) — UNVERIFIED
- V-H-1 P2: OperationRunRecorder :156 / CompositeOperationRunRecorder :69 failedFinal(e.message) -> :276 summary non-null skips
  sanitizer -> raw exception text in operation_runs.errorSummary (90 d, in backups); callers BankApiIntegration, DatabaseBackupRepositoryImpl.
- V-H-2 P2: ReceiptLifecycleCoordinator :1486/:1526 raw e.message (take 500) in ReceiptEvent.errorDetails beside "[REDACTED]" message.
- V-H-3 P2: OCR retention blanks rawOcrText only (RetentionModule :96); receipt image files never aged (15 targets).
- V-H-4 P3 (P2 if promised): no release delete-all/purge-now (DebugScreen only).
- V-H-5 P3 retention target failures not marked transient; V-H-6 P3 wifiOnlyForCloud enforced only in router (:164/:240);
  V-H-7 P3 hard-coded English block reasons (AiSettingsViewModel :107-112/:254/:264).
- Clean: cloud AI OFF by default (PrivacySettings :5-6, AiModels :89), dual-toggle consent fail-closed, all 9 cloud services gate +
  payload policy, redaction default on, key user-supplied in header (no BuildConfig key, no HTTP logging), CE rethrown, chat history
  off by default, retention worker barrier/checkpoint/audit, allowBackup=false, no exported providers, no crash reporter, no
  shareable diagnostics, debug surfaces gated.
- Process note: V-H auditor overran its 35-call cap (54 tool uses); scope stayed read-only. Disclosed.
## Verifier on V-H (privacy-security-guardian, read-only)
- V-H-1 RECALIBRATED P3 latent: only runOperation caller BankApiIntegration :220 is behind requireStubMode (DEBUG only); other
  failedFinal callers pass literals/codes. operation_runs not in ExportAnonymizer, 90 d retention. Pre-existing.
- V-H-2 RECALIBRATED P3 dead path (ReceiptAssetStore.deleteAsset :158-171 catches all). Should use RoomReceiptLifecycleEventWriter.
- V-H-3 RECALIBRATED P3: UI promises text purge only (strings :850/:852); doc gap in raw-storage-policy.md about images.
- V-H-6 CONFIRMED P3 (P2 if Wi-Fi-only is a privacy control): bank-statement path ValidateBankStatementTransactionsUseCase :250 ->
  SmartReceiptAssistService :121-130 -> CloudReceiptAssistService :272 bypasses router Wi-Fi check; consent + redaction hold.
  Follow-up: provider mode (on-device only) honoured on that path?
## Phase 4 V-I (release-vertical-auditor, read-only): PARTIAL (35/35, 36 uses)
- Bank connections hidden in release (3 gates). Location: no background, double-gated, not in cloud AI, anonymized in export.
- V-I-1 P1 candidate: BankStatementParser :849-918 generic rows re-derive amount with "last separator = decimal": "1,234" -> 1.234,
  "1.234" -> 1.234, "1,234,567" -> 1234.567 (1000x). Release-reachable via ReviewViewModel :1080 (pending reviews, user approves).
- V-I-2 P2 abs() + keyword typing: unsigned-keyword credit "+1.500,00" imported as expense (:918, :944-949).
- V-I-3 P2 dd/MM only; US dates dropped silently; ambiguous dates no flag (:1005-1022).
- V-I-4 P3 FOREGROUND_SERVICE_LOCATION declared, unused (manifest :9) — Play declaration risk.
- V-I-5 P2 map permission: no permanently-denied/settings redirect; rationale on every entry (SpendingMapScreen :100-106/:133-137).
- V-I-6 P1 candidate NEEDS_RUNTIME: BootReceiver :45 startForegroundService on dataSync service at BOOT_COMPLETED; Android 15
  forbids dataSync FGS from BOOT_COMPLETED. Lead read: BootReceiver catches Exception around the call; NotificationCaptureService
  :305-338 startForeground(DATA_SYNC) catch -> fallback untyped startForeground (same manifest type) catch -> logs only; no stopSelf.
  Risk: service started via startForegroundService never reaches foreground -> ForegroundServiceDidNotStartInTimeException
  (uncatchable process crash) after every boot on API 35 with listener enabled. Not covered by R14 instrumentation (synthetic
  broadcast lacks real boot context). Deciding test: real reboot of wave5-api35 emulator with listener granted + logcat.
- V-I-7 P2 no onboarding (straight to Home; POST_NOTIFICATIONS each start known).
- V-I-8 P2 privacy tile mislabeled as "Backup & Restore" (FeatureConfig :236-241).
- V-I-9 P3 dead ends (CategoryBreakdownSheet :95 Show all no-op; SmartSearch drops id; deep-link highlight inverted; ReceiptAssistCard :115).
- V-I-10 P3 unauthenticated deep links (PRV-16 TODO), URI-only data.
- Clean: allowBackup=false, no exact alarms, PendingIntents FLAG_IMMUTABLE, receivers not exported, nav state rememberSaveable.
## Verifier on V-I bank parser (release-finding-verifier, read-only, 19 calls) — all pre-existing
- V-I-1 RECALIBRATED P1 -> P2: BSP :851 gate, :907-918 last-separator decimal; "€1,234" -> 1.234, "€1,234,567" -> 1234.567;
  "1.234 €" ambiguous also in AmountUtils :137. OCR image/PDF only (BLP :200), needs explicit currency token, always PendingReview
  with visible amount (never auto-approved); shrink 1000x plausibly noticed. Side effect: dedupe misses, approval duplicates.
  No tests for bare thousands on generic path. Origin a6815998.
- V-I-2 CONFIRMED P2: sign dropped (:918), :944-948 PURCHASE both branches; "VISA CREDIT CARD 45,00" -> DEPOSIT; AI merge keeps type (BLP :338).
- V-I-3 RECALIBRATED P2: unparseable US dates -> date null -> BLP :593 `tx.date ?: now` (dated at import, not dropped); ambiguous
  dd/MM silent.
- New candidates (unverified): N1 BSP :1002 2-digit-year pattern matches "15/03/2025" as 2020 on single-date rows with SECOND order;
  N2 generic parser no break (:208-212) -> possible duplicate rows from Greek/Revolut parser.
## Verifier on V-I-6/-8/-5 (architecture-guardian, read-only, 22 calls)
- V-I-6 RECALIBRATED P1 -> P2 NEEDS_RUNTIME (~70% no crash): exception thrown inside startForeground(DATA_SYNC) :328, caught; untyped
  fallback :331 dead on targetSdk 34+; start-deadline timer believed cancelled before the throwing check (AOSP from memory, unverified).
  Likely effect: no FGS notification after boot + Timber.e. MY_PACKAGE_REPLACED fine. ServiceRestartReceiver :24 throws+caught.
  R14 does not address boot (BootReceiver :36-38 clears guard). CORRECTION to lead notes: no BootReceiver androidTest exists; only JVM
  BootReceiverStressTest (mocked Context). Fix shape: skip startForegroundService on BOOT_COMPLETED for SDK >= 35, rely on
  onListenerConnected (:287); bounded log code. Deciding device test: API 35 emulator reboot with listener granted and revoked,
  logcat ForegroundServiceStartNotAllowed / DidNotStartInTime, dumpsys activity services; repeat adb install -r.
- V-I-8 CONFIRMED P3 (string reuse FeatureConfig :237/:241). V-I-5 CONFIRMED P3 (no permanent-denial handling; precedent ReceiptScanScreen :211).
## R25 plan (room-migration-guardian, read-only @ 2ca932a9) — VERDICT FAIL (no data-preserving path for < v145)
- History: versionCode 1 / "1.0" unchanged since 35be8b26; no tags; release builds debug-signed (app/build.gradle.kts :167).
  Schema v5 (02-07) .. v143 (05-31), v144 90242a82, v145 345c8562, transient v146 1d8eddc5, v145 88813919, MIGRATION_144_145
  rewritten f462c9bd/0e92dc44/6e4be942; cut 27ca3bd8 (2026-06-02 23:55) removed 140 migrations ("rescue/import tool").
  Shipped device version UNKNOWN (v5..v146 possible; likely v125-v144).
- Legacy chain AppDatabase.kt companion :224-:8886 (6_7..144_145, 96_100 jump); dead nested duplicate 144_145 :6076; not proven
  correct (policy doc: drift, 20+ validation failures); MIGRATION_144_145 :8826 DROPs pending_reviews (data loss). Schema JSONs v33..v151.
  androidTest DatabaseMigrationTest starts at v1/v6-10 without JSONs (cannot pass). Robolectric MigrationTestHelper works (MigrationRegistrationTest).
- Options: A full chain high risk; A-narrow (new non-destructive bridge for confirmed version(s) only, MIGRATION_BASELINE = N);
  B FinancialRescueCoordinator prototype unusable (raw INSERT OR REPLACE bypasses insertAtomic, 6 tables, logs abs path :66,
  plaintext JSON); C destructive (owner approval, not recommended); D fail-safe DatabaseOpenGate (PRAGMA user_version probe before
  Room; <145, >151 or open failure -> unsupported-database screen with SAF export of untouched db/-wal/-shm, bounded code; workers,
  capture service, receivers must honour gate). Recommendation: D now + A-narrow once version confirmed (<~v113 or no in-place
  update -> D only, B post-1.0).
- F2: MIN_SUPPORTED_SCHEMA_VERSION = MIGRATION_BASELINE (:152); raw .db import :2193 early BACKUP_SCHEMA_UNSUPPORTED_LEGACY;
  .costbackup manifest.databaseVersion check near :1043 before extract.
- Write set/tests/profiles/stop conditions: see guardian report (DatabaseOpenGate.kt, DatabaseModule :36, MainApplication/
  AppStartupDelegate, WorkerExecutionGuard + capture checks, UnsupportedDatabaseActivity + manifest, DatabaseBackupRepositoryImpl,
  KDoc DatabaseSchemaPolicy :19 / AppDatabase :15-42, docs; LegacyBridgeMigrationTest, parity, DatabaseOpenGateTest, crash-loop
  guard, backup early reject). Reviewers reviewer-strict + fresh guardian.
- LEAD NOTE: B-3 = 1.0 signed with an owner-held release key; old builds debug-signed -> in-place update over the old install is
  rejected by Android (signature mismatch). So F3 crash loop is reachable only for debug-key installs; the real H-2 path is
  old backup/export -> restore into 1.0 (F2 + A-narrow via staged restore). Severity of F3 -> P2 (D still in R25); P1 = old-data
  restore path. Needs owner: device schema version (python user_version on the extracted .db), backup format.
## R16-S1 strict review (reviewer-strict, read-only): ACCEPT-WITH-NOTES (S1 must not ship without S2)
- parseAmount body hash identical (md5 a180f617...); only appended tests; hand traces match plan; statement path never newly null.
- F1 MAJOR (binding for S2): clipboard prefill el_GR "1,234" (ClipboardAmountParser :8 accepts as 1234) -> sanitizer truncates
  to "1,23" -> 1.23. Fix in S2 at AddExpenseViewModel :480: parseAmount -> String.format(Locale.US,"%.2f") -> sanitize (as :144);
  test el_GR clipboard "1,234" prefills 1234.
- F2 minor: parseStatementAmount leading-zero tokens "0,123" -> 123 vs "0.123" -> 0.123; comment BankStatementParser :904-905 overclaims; add test.
- F3 minor: sanitizer KDoc :13 + test name "never deletes a comma" contradict ("1,,5" -> "1,5"); reword "never joins digit runs".
- F4 minor: isValid ignores custom maxFractionDigits; add parameter.
- Shipping S1 alone harmful: el_GR "1.234" valid as 1234 but save :291 parseAmount -> 1.234; BudgetScreen/OwnershipValidator
  toDoubleOrNull refuse commas. FormComponents.kt :64 FormAmountField has no callers (optional).
## R16-S2 implementation (release-remediation-coder; cap 60, USED 113 tool calls — overrun, disclosed)
- Helpers AmountInputSanitizer.parse/parseOrNull/isValid(maxFractionDigits); all S2 sites use parseOrNull; F1 clipboard
  parseAmount -> Locale.US %.2f -> sanitize; F2 statement "0,123"/"0.123" -> 0.123 (explicit rule; parseAmount untouched);
  F3 KDoc + test rename; F4 isValid param. Prefills BudgetScreen/ReviewScreen -> Locale.US %.2f. Groups "-5" now error_invalid_amount.
- Tests: new OwnershipValidatorTest (6); AddExpenseViewModelTest +9; ReceiptScanViewModelTest +4; TransactionsViewModelTest +3;
  AmountUtilsUserAmountTest +1; AmountInputSanitizerTest +1 / rename. Not compiled.
- Coder blocker (outside list): TransactionsScreen EditOwnershipDialog :2131 filter deleted comma ("12,5" -> 125) and :2024
  Double.toString prefill. LEAD EDIT (in approved R16 scope): sanitize filter + Locale.US "%.2f" prefill. Also SavingsGoalsScreen
  :414 regex rejecting commas -> sanitize (R16c item done early). Both untested Compose edits; under strict review.
- Worktree note: owner's other session added untracked .claude/agents/* (9), docs/features/loan-installments/,
  workflows/active/loan-installments-handoff.md -> R16-S1 run vr-20261016-062342-4ce563bf STALE_RESULT (E_WORKTREE_CHANGED;
  BUILD SUCCESSFUL, AmountUtilsUserAmountTest PASSED in log, not evidence). Validation paused until the owner pauses or moves that work.
- 2026-10-16 OWNER: "i say opk to them all" -> APPROVED: R21 new guarded RecurringHorizonWorker; R23 exchange-rate download
  default OFF (fail-closed); R25 plaintext untouched-file export exception (user-started, SAF picker only); and "go" for
  validation (owner keeps the feature session idle during runs). Still open: old DB schema version for R25 part 3.
## R16-S2 strict review (reviewer-strict, fresh, 44 tool uses vs cap 35): ACCEPT-WITH-NOTES, no critical/major
- Q1 no new comma-deleting/non-helper sites; R16c list confirmed (+ SpendingChallengesScreen :504 regex filter; challenges hidden by R19).
- Q2 validation == save at all sites; AddExpenseViewModel :361 `!!` safe. Q3 all prefills Locale.US "%.2f", round-trip el/en/fr/de-CH.
- Q4 behaviour changes acceptable (groups negative never saveable; statement 0,123 fixed; savings paste truncation).
- Findings fixed by LEAD before validation: F1 ReviewScreen amount isError via parseOrNull (no new strings); F3 AddExpenseViewModelTest
  split + new en_US "12,50" -> 12.50 test; F4 AmountInputSanitizerTest Locale.US prefill round-trip for el-GR/en-US/fr-FR/de-CH.
  F2 ACCEPTED: TransactionsScreen :2026 "%.2f" prefill is cent normalization; SplitCalculator works in cents (toCents/fromCents :112-119).
- Validation queue started (owner "go"): 9 targeted + app-check.
## H-2 resolved from the owner's phone (2026-10-16; owner consented: adb on 62d5f6f9, Android API 36; header/identity only, no rows read)
- Installed app: versionCode 1 / 1.0, DEBUGGABLE, lastUpdateTime 2026-06-03 00:26:19 (after cut 27ca3bd8 2026-06-02 23:55:19 +0300),
  signing cert digest 441af6cc (debug key).
- Live databases/expense_tracker_db: SQLite header user_version = 145, 233,033,728 bytes, empty WAL; contains the identityHash of
  app/schemas/.../145.json (6f54382ef28356b2333be481737f88c6) -> final baseline v145, migratable by the registered 145->151 chain.
- databases/expense_tracker_db.legacy.1780389713534: user_version 113, 3 MB, 2026-06-01 (set aside by the 2026-06-02 rescue).
- Consequences: no live DB < 145 on the shipped device -> R25 part 3 (A-narrow bridge) NOT needed; HZ-02 F3 back to P3 latent
  (D + F2 still approved and planned). New owner decision: 1.0 signing key vs this install (debug cert) -> in-place update
  possible only with the same debug key; with the owner release key (B-3) the path is uninstall + install + restore backup.
  Deciding test either way: real-data 145->151 on a copy (emulator) / restore of the owner's extracted backup into C* on the emulator.
- SAFETY: phone 62d5f6f9 holds real data; never target it with connected tests (they wipe app data).
## Release worktree + R16 validation (2026-10-16)
- Owner "do the recommended thing" -> (A) release work moved to worktree C:\Users\panos\Desktop\Cost-agregator-release, branch
  release-1.0-wt @ 2ca932a9; 20 R16 files copied, SHA-256 identical; local.properties copied unread; main-folder app/ restored to HEAD
  (own R16 edits only; patch kept in scratchpad r16.patch). Signing = option 1 (owner release key; old app -> backup, uninstall,
  install 1.0, restore); emulator restore test of the owner's backup pending (backup location not found on phone/PC yet).
- R16 run batch 1 (worktree, fingerprint 17a81e36..., no drift): *AmountInputSanitizerTest PASS 17 (vr-20261016-070116-3e01b1c9);
  *AmountUtils* PASS 103 (vr-...070251-b11ced94); *SharedUtilityConsistencyTest PASS 15 (vr-...070340-0ecfbaef);
  *ClipboardAmountParserTest PASS 3 (vr-...070416-349409ef); *BankStatementParser* FAIL 36/1 (vr-...070452-c15f46e4):
  new test "EUR1,234,567" -> ParsedTransaction require(amount <= 1_000_000) threw (AppParserRegistry :69) from extractTransactionFromRow.
- DEBUG ITERATION 1 (lead): parser correct, fixture over cap; real edge: one over-cap row throws and fails the whole statement
  (pre-existing for "1500000.00", slightly more reachable now). Fix: BankStatementParser GENERIC_ROW_MAX_AMOUNT = 1_000_000.0,
  generic row over cap -> return null; test fixture -> "EUR123,456" = 123456 + new test over-cap row skipped (0 results).
  Needs reviewer confirmation at the R16 final review. Validation resumed (BankStatementParser, AmountUtils, then items 6-10).
## Owner phone diagnostics (2026-10-16; owner consent "you are free to find anything you need")
- Crash buffer: 10-16 10:06:12 FATAL SQLiteDatabaseLockedException (SQLITE_BUSY) in Room withTransaction (beginTransactionNonExclusive)
  on a StandaloneCoroutine (Dispatchers.Main.immediate, uncaught) ~2.5 s after a tap ("Long db operation" logged) = the owner's
  backup attempt (old build, 2026-06-03). Also ForegroundServiceDidNotStopInTimeException (dataSync timeout) 10-15 12:29 and
  10-16 10:06:23 = RT-C1, fixed in C* by R14 (real-device confirmation of the bug). Many libc FORTIFY destroyed-mutex aborts (pid not attributed).
- Safety copy (owner data; outside repo): %USERPROFILE%\Desktop\expense-db-copy-2026-10-16\ (db sha256 matches device; -wal/-shm
  changed on device after copy because the listener restarted the process). Copy check: integrity ok, 0 FK violations,
  user_version 145, expenses 1188, categories 20, pending_reviews 300.
- DB 233 MB (56,894 pages, freelist 0): privacy_audit_events 363,161 rows; pipeline_diagnostic_events 388,175; background_job_runs 2,787.
  C* RetentionModule covers both (pipeline diagnostics cutoff; privacy audit 180 d COMPLIANCE-FLAGGED) but ~2,700 audit rows/day
  -> ~490k rows at 180 d steady state -> bloat persists. Candidate batch R26: diagnostic/audit volume + backup SQLITE_BUSY crash (verify in C*).
- Migration path for the owner (signing option 1): restore this raw v145 copy into 1.0 via raw .db import (>= baseline) — test on emulator first.
## Debugger (opus, read-only) on backup SQLITE_BUSY — C* AFFECTED (~70% confidence, needs deciding test A)
- Root cause: DatabaseBackupRepositoryImpl :678 `PRAGMA locking_mode = EXCLUSIVE` via openHelper.writableDatabase.query (comment
  claims no-op; prepared -> applies to Room's writer connection for the rest of the process). Next Room write needs EXCLUSIVE file
  lock while pool readers / raw count connection :702 / VACUUM INTO connection (SqliteSnapshotCreator :43-46 OPEN_READWRITE) hold
  SHARED -> busy handler 2500 ms -> SQLITE_BUSY (matches device crash ~2.5 s after tap); later writes in the process can also fail.
- Uncaught paths: DBRI :613 operationRunRecorder.start outside try; BackupRestoreViewModel :106-114 launch without catch;
  CompositePrivacyGate :69 auditLogger.logDecision outside per-gate try (callers e.g. SpendingMapViewModel :808-810 Main launch).
- Proposed R26: (1) delete :678; (2) SqliteSnapshotCreator open OPEN_READONLY; (3) recorder.start inside try, no-op handle on
  failure; (4) BackupRestoreViewModel catch(Exception) + CE rethrow + bounded message; (5) CompositePrivacyGate audit-write failure
  -> FailClosed (PRIVACY SEMANTICS, owner approval); (6) no busy-timeout increase / barrier widening.
  Audit volume: one row per privacyGate.check, no dedup (NotificationCaptureService :617/:480, NotificationIntakeWorker :476,
  WorkerExecutionGuard :163/:347, geocoding chains, ReviewViewModel :275) -> ~486k rows at 180 d (~100-190 MB). Pipeline
  diagnostics 30-d cutoff (DataRetentionWorker :121) yet 388k rows (13k/day or retention not running). Purges are single unbounded
  DELETEs (PrivacyAuditDao :36, RetentionModule :635/:322) -> batch 5k rowids/tx.
- Deciding tests: A instrumented WAL (locking_mode exclusive + SHARED holder -> insert throws ~2.5 s; after fix succeeds);
  B recorder fake throws -> Result.failure + VM error state; C audit volume; D batched purge 20k rows.
- LEAD severity: P1 (in-app backup = data-safety feature; crash reproduced on owner device; after :678 any write can crash).
- 2026-10-16 OWNER: "i approve" -> R26 APPROVED (backup lock crash + audit/diagnostic volume + batched purges), including item 5:
  CompositePrivacyGate audit-write failure -> FailClosed decision instead of throwing. Runs after R16 commit (single writer).
- 2026-10-16 OWNER: "approved" -> R26-V (coalesce routine per-notification listener/filter/dedupe pass diagnostics; always record
  problems/drops/captures) and R26-D (coalesce identical local NOTIFICATION_CAPTURE ALLOWED audit decisions once per hour with a count;
  always record DENIED/FAIL_CLOSED and cloud/export/backup/location decisions; update backend-domain-map.md :610) APPROVED.
  Sequencing: R16 commit -> R26 core -> R26-V/R26-D (planned after core) -> R17 ...
## R16 validation in worktree (rev 2ca932a9 + R16 diff, fingerprint 2594779b... start=end)
- PASS: *BankStatementParser* 37 (vr-20261016-070650-e555bbaa); *AmountUtils* 99 (vr-...071132-1cd4d640); *OwnershipValidatorTest* 6
  (vr-...071207-79c6711c); *AddExpenseViewModel* 15 (+8 pre-existing @Ignore stress) (vr-...071245-5eed6bf9); *ReceiptScanViewModel* 52
  (vr-...071328-6e0e435f); *TransactionsViewModel* 12 (+16 pre-existing @Ignore stress) (vr-...071414-576aab00). Earlier same-diff-minus-
  BankStatement-fix: Sanitizer 17, SharedUtilityConsistency 15, Clipboard 3 (fingerprint 17a81e36..., superseded for Sanitizer? no: Sanitizer
  unchanged since; counted as evidence for unchanged files only).
- app-check vr-20261016-071454-f84cdbb8 FAIL: :app:testReleaseUnitTest DailyBriefingWorkerTest "pipeline timeout wraps in
  RetryableWorkerException with cause" IllegalStateException Expected TimeoutCancellationException (:253) — not an R16 file.
  Lint 0 new errors (2222 baseline-filtered). stderr "Python was not found" x2 (new worktree environment?) -> debugger dispatched.
- Debugger (read-only) on app-check FAIL: test-side real-time race (~85%): DailyBriefingWorkerTest :250-257 runBlocking withTimeout(1)
  { delay(10) } — delay can win on a loaded machine; PASSED in testDebugUnitTest same run (line 1265), FAILED in testReleaseUnitTest
  (line 35695); worker code not reached; not in known-hanging list; R16 not implicated. "Python was not found" = python3 Store alias
  probe in app/build.gradle.kts pythonInterpreter(); falls back to python; guards ran under Python 3.15.0 (G-TIME-01 PASS,
  G-MONEY-RAW PASS) -> not a false PASS.
- DEBUG ITERATION (lead, test-only): same pattern fixed deterministically (withTimeout(1) { awaitCancellation() }) in
  DailyBriefingWorkerTest (2 sites), NotificationIntakeWorkerTimeoutTest :56, WorkerExecutionGuardTest :1011. Re-validation queued.
- 2026-10-16 OWNER: "do your reccomndations" -> Q-V1 YES (coalesce INFO FILTER_REJECTED and DUPLICATE terminal drops into
  hourly per-stage counts; V8 test in scope; problems/failures/WARNING+/exceptions/captures always written); Q-V2/Q-D2 YES
  (accept < 1 h pending-count loss on process death, documented as lower bound); Q-D1 = {NOTIFICATION_CAPTURE} only.
## Expiry triage (release-planner, read-only, 33 calls) — RELEASE GATE TIME BOMB
- 2026-11-01: WriteBarrierArchitectureGuardTest :654/:666 (LocalDate.now) fails -> unit-tests/app-check red; validation-runner :392
  E_HANGING_LEDGER_REVIEW_OVERDUE for legacy-tests profile only (no un-skip, no timeout). 2026-11-21: DirectEventDaoInsertGuardTest
  :370 (7 LEGACY_REPOSITORY entries 2026-11-20; renewal capped today+75 d :392). 2027-01-01: DirectEventDao 26 more,
  TransactionContextProvenanceGuardTest :174 (6), SourceScanningArchitectureGuardTest :500 (6).
- U-004 16 entries (:143-158): 6 FIXED already (LegacyDataMigrationService, CsvExpenseImporter, JsonExpenseImporter,
  RestoreJournalImporter, NotificationIntakePayloadRepairer, NotificationIntakeRecoveryScheduler) -> remove entries;
  DiagnosticEventWriter + OperationRunRecorder Room writers -> inject barrier (~0.5 h each; composites already check + safe sink);
  ReceiptInsertResolver, DebugExpenseAuditWriter, ReceiptLifecycleEventWriter, TransactionLifecycleEventWriter (in-tx lifecycle) and
  WorkerRunLogger, WarrantyExpirationWorker, NotificationIntakeWorker (workers) unfixed (M..L); PrivacyAuditLoggerImpl owner decision.
- HANG-001..003 suspected (43ca2228); resolve only with run evidence (KNOWN_HANGING_TESTS.md :16).
- Unenforced dates (existing gaps): SourceScanning broadCatchAllowlist (2026-09-30), CancellationSafety fixed GUARD_TODAY_VALID
  2026-09-15 (:230; 13 RAW entries already past), YAML expires (51 entries 2027-01-01; verifiers do not read them; 77 vs 51 unreconciled).
- Proposed G2 (no guard logic/date changes): delete 6 fixed entries; barrier in RoomDiagnosticEventWriter + Room OperationRunRecorder
  writer; tests; legacy-tests run before 10-31 for ledger evidence. Reviewer architecture-guardian.
- 2026-10-16 OWNER: "i approve" -> G2 APPROVED (delete 6 fixed U-004 entries; barrier in RoomDiagnosticEventWriter + Room
  OperationRunRecorder writer; legacy-tests run for HANG ledger evidence; no guard logic/date changes); G3 APPROVED (per-writer
  architecture review of the 8 unfixed U-004 writers + 7 DirectEventDao 2026-11-20 entries: fix where safe; "covered by caller"
  permanent exemptions only after the owner sees the list); PrivacyAuditLoggerImpl honours the write barrier (fail-closed with R26).
  Unenforced expiry dates + 77/51 reconcile -> post-1.0 guard hardening (2026-12-01 review). Deadline: G2 before 2026-10-31.
## G3 analysis (architecture-guardian, read-only, 39 calls): VERDICT FAIL (pre-G3 gap + R26 conflict)
- Barrier = entry check only (DatabaseWriteBarrier :15-24); enterAndDrain persists mode then drains WORKER leases only
  (RestoreMaintenanceMode :177-194, WorkerLeaseRegistryImpl :36-81); >5 s -> NORMAL + abort (MaintenanceOperationRunner :27-41).
  OPEN GAP (outside G3): in-flight non-worker Room transactions are never awaited -> check-to-write window everywhere.
- FIX: ReceiptInsertResolver :32, DebugExpenseAuditWriter :29/:58, RoomReceiptLifecycleEventWriter :49 (callers covered; in-tx
  throw = rollback OK); RoomTransactionLifecycleEventWriter :48 REQUIRED: post-commit path PostCommitActionRunnerImpl :59/:95/:103 ->
  CompositeSideEffectEventWriter :57 -> TransactionSideEffectFailureEventWriter :36 has NO check (mirror event dropped during
  maintenance after fix, owner to accept); PrivacyAuditLoggerImpl :51 FIX + CompositePrivacyGate :69 DatabaseAccessBlockedException
  -> FailClosed(PRIVACY_AUDIT_WRITE_BLOCKED new code), CE rethrow.
- COVERED-BY-CALLER (needs new guard category, owner): WorkerRunLogger (:116/:226; only WorkerExecutionGuard, start checked :576,
  terminal writes under lease), WarrantyExpirationWorker (+ add ctx.checkpoint before :138), NotificationIntakeWorker (checkpoint before
  each write). Guard KDoc :36-38 says worker-guard usage does not count -> override. Proposed COVERED_BY_CALLER map (mechanism,
  justification, evidence files) with hygiene checks, cap 3, fixtures.
- R26 CONFLICT: DBRI :413/:424 privacyGate.check after entering BACKUP_EXPORTING (:391) -> with barrier-honouring audit, raw export
  fails closed; today those checks write audit rows into the DB being copied. Fix: move both checks before :391 (as :378).
- DirectEventDao LEGACY_REPOSITORY (:97-208): ReceiptRepository, RecurringExpenseRepository, ManualRecurringExpenseRepository -> REMOVE
  (no inserts left) + stale-entry test; NotificationRepository :203, ReviewQueueRepository :281 -> route via TransactionLifecycleEventWriter
  (approveReview tx context unverified); WarrantyTrackerRepository -> reclassify as warranty-lifecycle owner (LEGAL_PATHS :564) + barrier
  at :432 (extractWarrantyFromReceipt, no prod callers) or private; BankApiIntegration :377 pendingReviewDao.insert -> owner (LEGAL_PATHS
  :826 names only BankStatementLifecycleProcessor; BankApiIntegration is debug-only via requireStubMode).
- Timeout-test fix validation (fingerprint 57d1c5dc...): *DailyBriefingWorkerTest PASS (vr-20261016-074353-d6ba9121),
  *NotificationIntakeWorkerTimeoutTest PASS (vr-...074558-94ff5803), *WorkerExecutionGuardTest PASS (vr-...074637-a9139c62),
  targeted-release-unit-test *DailyBriefingWorkerTest PASS (vr-...074717-c3e09e26). app-check vr-20261016-074919-4c734c1d RUNNING
  (first runner agent hit its 60-turn limit while polling; a fresh agent is waiting on the same run, no new start).
- 2026-10-16 OWNER: "yes to all reccomended" -> G3 decisions APPROVED: (1) new COVERED_BY_CALLER guard category (mechanism,
  justification, evidence files; hygiene checks; cap 3) for WorkerRunLogger, WarrantyExpirationWorker (+ ctx.checkpoint before :138),
  NotificationIntakeWorker (overrides guard KDoc :36-38); (2) accept dropped post-commit side-effect-failure mirror events during
  maintenance; (3) move DBRI :413/:424 privacy checks before entering BACKUP_EXPORTING (:391); (4) reclassify warranty events under the
  warranty-lifecycle owner + barrier/private for :432; (5) BankApiIntegration :377 -> debug-only owner classification.
  R27 (non-worker in-flight transactions not awaited by enterAndDrain) -> read-only analysis after G3.
## R26 core implementation (release-remediation-coder: items 1-4, 8 + tests; ~74 tool uses vs cap 60 — overrun, disclosed)
- Coder STOPPED on item 5: DatabaseBackupRepositoryImplTest "audit failure returns failure for both export overloads..." asserted
  propagation of the audit exception (plan wrongly said none). LEAD implemented item 5 (owner-approved): CompositePrivacyGate wraps
  logDecision; CE rethrow; class-name log; blocking decision kept else FailClosed(PRIVACY_GATE_FAILURE); changed that test's assertion
  to PrivacyDeniedException(ENCRYPTED_BACKUP, PRIVACY_GATE_FAILURE) without leaking the audit message; added 3 CompositePrivacyGateTest cases.
- Coder notes: locking_mode string remains only in tests; A1 uses beginTransactionReadOnly (API 35 only, Assume skip below);
  A2 decides OPEN_READONLY (revert to OPEN_READWRITE if it fails); RetentionModule rowsPurged on non-success = partial total.
- Emulator wave5-api35 started on port 5554 (synthetic data only). Review (reviewer-strict) + validation queue (7 targeted +
  connected A1/A2 on emulator-5554 only + static-guards + app-check) dispatched.
## R26 core strict review (reviewer-strict): ACCEPT-WITH-NOTES (F1 owner sign-off)
- F1 MAJOR: CompositePrivacyGate FailClosed on audit failure -> WorkerExecutionGuard applyPrivacyPolicy default SKIP_SUCCESS
  (:48, :163-169, :347-353) instead of previous transient Retry (classifyTransient :661 SQLITE_BUSY) -> NotificationIntakeWorker
  (:57-62) intake skipped until next cold-start recovery (AppStartupCoordinator :120); delayed not lost. Proposed: distinct bounded
  reason PRIVACY_AUDIT_WRITE_FAILED mapped to Retry in WorkerExecutionGuard (lead recommendation).
- F2 minor: record snapshot method (VACUUM_INTO / DRAINED_FILE_COPY) as bounded code (SqliteSnapshotCreator :53-57, DBRI :723-724).
- F3 minor: A1 Assume SDK>=35 (skip = NOT PROVEN); a1_production... passes pre-fix too; unit verify should match any locking_mode
  query/execSQL. F4 docs: DBRI :681 comment, fallback path protection stated separately.
- Answers: Q1 pre-existing low risk (fallback copy after checkpoint; tear only on auto-checkpoint during copy); Q2 read-only VACUUM INTO
  sound (SQLite 3.28 / API 30); Q3 gate change correct/minimal, logCloudCall unaffected; Q4 batched DELETE correct; Q5 VM correct; Q7 guards likely OK.
- 2026-10-16 OWNER: "do your reccomendations" -> R26 F1: new bounded reason PRIVACY_AUDIT_WRITE_FAILED for audit-write failure;
  WorkerExecutionGuard maps it to Retry (screens still get a FailClosed refusal). F2-F4 fixes in the same follow-up.
  Edits wait until the running R26 validation queue finishes (fingerprint rule).
- R26 validation queue 1: DatabaseBackupRepositoryImplTest PASS (vr-20261016-081949-678ff8ce), BackupRestoreViewModelTest PASS 23
  (vr-...083244-fed7399d), CompositePrivacyGateTest PASS 6 (vr-...083327-0e9dc129), PrivacyAuditDaoTest FAIL (vr-...083402-2a639e8a):
  new test arithmetic (7 rows, delete 2 -> expected 5, test said 4). DEBUG ITERATION (lead, test-only): corrected to 5/3.
- LEAD follow-up edits (owner "do your recommendations"): PrivacyGateReasonCodes.PRIVACY_AUDIT_WRITE_FAILED (+ ALL);
  CompositePrivacyGate returns FailClosed(PRIVACY_AUDIT_WRITE_FAILED); WorkerExecutionGuard both FailClosed sites -> retryAuditWriteFailure
  (WORKER_TRANSIENT_ERROR Retry regardless of privacyPolicy); DBRI SNAPSHOT_CREATED metadata snapshotMethod + comment on fallback path;
  tests updated (DBRI audit test code, CompositePrivacyGateTest code, locking_mode verify for query+execSQL any spelling) and 2 new
  WorkerExecutionGuardTest cases. Full queue re-run dispatched (12 items incl. connected A1/A2 on emulator-5554 only).
- R26 rerun 2: DatabaseBackupRepositoryImplTest FAIL (vr-20261016-083649-af762491) at :340 expected PRIVACY_AUDIT_WRITE_FAILED, got
  PRIVACY_GATE_FAILURE: DBRI :419 maps any FailClosed to PrivacyDeniedException(ENCRYPTED_BACKUP) with the default generic code
  (pre-existing contract). DEBUG ITERATION 2 (lead, test-only): assertion restored to PRIVACY_GATE_FAILURE with explanation.
  R26 now at the 2-debug-iteration cap; a further failure -> hand back PARTIAL. Third full queue dispatched.
- R26 run 3 (fingerprint 910db93f... no drift): PASS DatabaseBackupRepositoryImplTest (vr-20261016-084310-b2ff725a),
  BackupRestoreViewModelTest 23 (vr-...084600-fe26f8a3), CompositePrivacyGateTest 6 (vr-...084639-28aa08c5), PrivacyAuditDaoTest 9
  (vr-...084713-8a72decf), RetentionModuleBatchedPurgeTest 7 (vr-...084749-c366e5cd), DataRetentionWorkerTest 26 (vr-...084834-5611bd32),
  PrivacyGateContractTest 10 (vr-...084915-1ce56d1b), WorkerExecutionGuardTest 51 (vr-...084954-53bec9f2), PrivacyDeniedExceptionTest 3
  (vr-...085029-151a3edc); connected BackupWalLockInstrumentedTest on emulator-5554 API 35: 3 executed / 0 skipped PASS
  (vr-...085112-48dff99a) -> ROOT CAUSE CONFIRMED on framework SQLite (EXCLUSIVE + SHARED reader -> SQLiteDatabaseLockedException) and
  read-only VACUUM INTO works on API 35.
- static-guards vr-20261016-085334-6b0a4269 FAIL 22/25: known_good_state INFRA_ERROR, db_access INFRA_ERROR, guard_tests VIOLATION
  (700 s). First static-guards in the worktree -> environment suspected; debugger dispatched. app-check not run.
- Debugger on static-guards (read-only): ONE cause, R26 code (not worktree): removal of writableDatabase pragma in
  DBRI.runCostBackupExport leaves a STALE structural exception requiring exact source evidence:
  config/guards/db_structural_exceptions.yml :217-223, db_structural_exceptions_expected_methods.yml ~:244,
  scripts/verify_db_access_boundaries.py :864 tuple -> db_access/known_good_state active_db_gate INFRA (DB_POLICY_SOURCE_EVIDENCE_INVALID)
  + guard_tests 4 failures (test_verify_db_access_boundaries x2, test_verify_db_access_v2 x2). freshness SKIP (no stamp in worktree).
  No ownership-policy regeneration needed (findingCount 0). Fix = remove the stale exception (guard TIGHTENING) in 3 places +
  dependent counts/fixtures (structural_manifest 65/60/65 -> likely 64/59/64; test around :5246). Needs owner approval (guard files).
  R26 at debug cap -> PARTIAL pending owner decision.
- 2026-10-16 OWNER: "do the reccomendation" -> APPROVED: remove the stale runCostBackupExport/writableDatabase structural exception
  (guard tightening) in the 3 guard files + dependent counts/fixtures; architecture-guardian review; then re-run.
- Guard tightening applied (coder 37 calls, PARTIAL; lead finished): removed tuple from db_structural_exceptions.yml,
  db_structural_exceptions_expected_methods.yml (counts.structural_entries 65->64), verify_db_access_boundaries.py
  (MANIFEST_IMMUTABLE_EXPECTED_TUPLES; PINNED_STRUCTURAL_ENTRY_COUNT 65->64), verify_known_good_state.py (_STRUCTURAL_ENTRIES 64,
  _STRUCTURAL_EXPECTED 59, docstring); tests: test_verify_db_access_boundaries.py count pins (+ duplicate-tuple test n=63/structural=64 by lead),
  test_verify_known_good_state.py pins 64/59 and drift probe expected 59->58 (lead). No dates/logic/other entries. Reviewer
  architecture-guardian + queue (registered-guard db_access, known_good_state, static-guards, app-check) dispatched.
- Guard-edit review (architecture-guardian): REJECT (incomplete): (1)-(4) PASS (one tuple removed; no weakening; counts consistent;
  runCostBackupExport :606-826 has no writableDatabase). (5) missed functional pin: app/src/test/.../guard/DbGuardPolicyFixtureTest.kt
  reads real files and asserts 65/60 at :1077-1079, :1083-1089, :1127-1133, :1165 (+ KDoc :16/:20/:23) -> app-check would fail.
  Optional: parametrized (64,4) -> (63,4). Fix after the running queue finishes (no edits during fingerprinted runs).
- Validation after guard edit (fingerprint 2ce73b47...): registered-guard db_access PASS (vr-20261016-092641-2cc7683b),
  known_good_state PASS (vr-...092915-2448cda6), static-guards PASS 25/25 (vr-...093204-cf81112a; freshness SKIP stamp=missing).
- Lead fixed review finding (5): DbGuardPolicyFixtureTest.kt count pins 65->64 / 60->59 (lines 1077-1190 + KDoc 16/20/23; take(60)
  string truncations untouched) and optional drift probe parametrize (64,4),(66,6) -> (63,4),(65,6). Final batch gate queued:
  targeted DbGuardPolicyFixtureTest, static-guards, app-check.
- R26 FINAL BATCH GATE (fingerprint ce730c77... start=end, HEAD c3fc8947 + R26 diff): ALL PASS, exit 0, complete.marker, no lock left.
  targeted *DbGuardPolicyFixtureTest PASS (vr-20261016-095638-3b4af43f, 131 s); static-guards PASS 25/25 (vr-20261016-095903-03674f6b,
  1329 s); app-check PASS (vr-20261016-102123-064fc003, 1951 s): testDebugUnitTest 8185 run / 0 fail / 276 skipped, testReleaseUnitTest
  8185 / 0 / 277 skipped; lint passes via lint-baseline.xml (804 warnings, 2222 baselined errors; not a clean-lint claim).
  Notes (open, P3): debug vs release skip 276 vs 277 not investigated; "Python was not found" stderr = Store alias, Gradle falls back.
- Branch risk (new): main folder branch release-1.0-remediation gained owner commits e5a2f0e7 (agents/turn limits) and 7da9fcae
  (loan-installments docs) on top of 2ca932a9 -> moving it to release-1.0-wt is no longer a fast-forward; needs a merge/rebase
  decision by the owner at the end. Not touched.
- Guard-edit re-review (architecture-guardian, iteration 2): ACCEPT-WITH-NOTES. 64 = 59 expected + 5 fixtures consistent across all
  7 files; whole-repo grep found no other pin; strictly tightening (net -13 lines, no exception added/widened/re-dated); no unlisted
  writableDatabase in runCostBackupExport (:606-826; checkpointWal :3030 keeps its own exception, structural yml :308).
  Notes (non-blocking, deferred to G2 so they ride its static-guards run): stale 65/60 comments/docstrings in
  scripts/test_verify_db_access_boundaries.py (:4501, 4646-4649, 4674, 4775, 5026, 5060, 5154, 5559, 6207-6209) and
  scripts/verify_db_access_boundaries.py:919.
- R26 core COMMITTED on release-1.0-wt.
## G2 (owner-approved; deadline 2026-10-31)
- Coder (release-remediation-coder, ~69 tool uses vs budget 45 — overrun, disclosed; reported complete): RoomDiagnosticEventWriter +
  RoomOperationRunRecorder (start/event/increment/finalize) check DatabaseWriteBarrier; 8 U-004 entries removed (6 already fixed,
  confirmed by file:line, + 2 fixed now); 8 G3 entries untouched; BankSyncStartupRecoveryTest constructor; new
  DiagnosticWritersWriteBarrierTest; comment-only 64/59 sync in the two db-access scripts. Behaviour note: blocked finalize during
  maintenance leaves a Room run row RUNNING (was: write landed during maintenance). P3: allowlist comment table (:125-142) still lists
  removed classes.
- Dispatched in parallel: architecture-guardian review (read-only) + validation-runner targeted queue (5 filters).
- G2 review (architecture-guardian): REJECT as-is. (1) coverage PASS for all 8 removed entries (file:line listed in its report);
  (2) barrier-failure handling PASS (composites rethrow CE, safe sink/handle fallback; runCatchingCancellable rethrows CE
  CancellationSafe.kt:34; only constructors: BankSyncStartupRecoveryTest :51 + new test; DiagnosticsModule :57 @Binds);
  (3) REGRESSION: DBRI :802 run.success() (and failedFinal :671/:702/:755/:767/:793/:810) execute inside BACKUP_EXPORTING (exit at
  :813) -> with the new finalize barrier check every backup leaves its operation_runs row RUNNING forever (stale sweep bank-only
  :103; retention skips RUNNING OperationRunDao :96/:105; terminal outcome only in Timber :305). Visible only in debug lookup.
  In-window events now go to the safe sink as WARNING operation_event_write_failed. (4) Python comment-only PASS. (5) new test
  likely compiles but :229-239 locks in the lost terminal outcome. (6) comment table :125-142 should be updated.
- Lead decision (debug iteration 1 of 2, within approved G2 scope): fix in-batch rather than accept the residual:
  (a) Handle.finalizeNonCancellable: blocked finalize -> terminal record to the safe sink (bounded fields); (b) DBRI export defers
  terminal run calls until after maintenance exit; (c) test asserts the safe-sink terminal record; (d) comment table update.
  Edits wait for the running targeted queue (vr-20261016-110045-ba53b8dd ...) to finish.
- G2 targeted queue (fingerprint 3df69f75..., pre-fix): DiagnosticWritersWriteBarrierTest PASS 13 (vr-20261016-110045-ba53b8dd),
  WriteBarrierArchitectureGuardTest PASS 16 (vr-...111132-a0ba6e20), BankSyncStartupRecoveryTest PASS 10 (vr-...111413-394c88e7);
  *CompositeDiagnosticEventWriter* (vr-...111455-42200882) and *OperationRunRecorder* (vr-...111536-c1dc9b78) = NO_TESTS (lead
  filter error: no such test classes exist) -> not PASS, not counted. Superseded by the review fix anyway.
- G2 fix (coder, 32 calls, complete): (a) finalize failure -> safe-sink terminal record operation_finalize_failed (operationType +
  terminalStatus only); (b) DBRI DeferredRunTerminal: 7 in-window terminal calls deferred, flushed after exit in finally; preflight
  calls direct; (c) DatabaseBackupRepositoryImplTest +2 ordering tests, DiagnosticWritersWriteBarrierTest asserts safe-sink record;
  (d) guard comment table rows removed. Lead edit: flush uses CancellationSafe.runCatchingCancellable instead of bare runCatching
  (cancellation-guard ratchet safety).
- Dispatched: architecture-guardian re-review (iteration 2 of 2, final) + validation queue: 4 targeted -> static-guards -> app-check
  -> legacy-tests.
- G2 re-review (architecture-guardian, iteration 2, final): ACCEPT-WITH-NOTES. Regression closed (all 7 in-window terminal calls
  deferred, flush once after exit incl. exit failure; CE unchanged; safe-sink record bounded, DataStoreMaintenanceSafeDiagnosticSink
  :127-130 stores class simple name + sanitized message as existing event() fallback; guards not tripped: runCatchingCancellable not
  matched by CancellationSafetyArchitectureGuardTest :282, verify_db_access_boundaries :866-867 untouched; ordering tests meaningful).
  Notes: (N1) restore :1023-1187, legacy import :1874-1969/:2093/:2120, reset :2878/:2977 FAILED_FINAL finalizes inside maintenance
  are now blocked -> Room row stays RUNNING (outcome kept in safe sink via (a)); previously they wrote during maintenance (and, for
  restore, possibly against the swapped DB). Lead: accepted as known residual (debug lookup only); follow-up G2b = same deferral in
  those paths or a non-bank stale-run sweep, bundled with G3/R27. (N2) DiagnosticWritersWriteBarrierTest :252 JSONObject.keySet()
  compile risk (android.jar org.json) -> fix only if the run fails. (N3) first-deferred-wins after success + tempDb.delete throw
  (negligible). (N4) exit()-throws and catch-all paths untested (P3).
- G2 queue run 1 STOPPED: *DiagnosticWritersWriteBarrierTest FAIL vr-20261016-111905-31f02de5 (compile: :252 Unresolved reference
  keySet, as review N2 predicted; 0 tests executed). DEBUG ITERATION 2 of 2 (lead, test-only): json.keySet() ->
  json.keys().asSequence().toSet() (repo pattern). Full queue re-dispatched. A further failure -> G2 PARTIAL to owner.
- G2 queue run 2 (fingerprint 7c54e440..., start=end): targeted DiagnosticWritersWriteBarrierTest PASS 13 (vr-20261016-112319-8f2787c6),
  DatabaseBackupRepositoryImplTest PASS 83 (vr-...112559-4b702047), WriteBarrierArchitectureGuardTest PASS 16 (vr-...112709-40996cd3),
  BankSyncStartupRecoveryTest PASS 10 (vr-...112951-e47bc389); static-guards FAIL 24/25 (vr-20261016-113033-7c2ff311): cancellation
  ratchet "DECREASED -- 2 findings resolved" (G-CANCEL-03 OperationRunRecorder.kt; config/baselines/cancellation.json :101 count 3,
  current 1); the ratchet exits 1 on stale/resolved baseline entries (guard_ratchet.py :9-10). Cause: G-CANCEL-03 heuristic
  (verify_cancellation_boundaries.py :410-441, 15-line safe-pattern window) now sees the new CancellationSafe.runCatchingCancellable
  fallback near the finalize .onFailure lines; those plain runCatching blocks run under withContext(NonCancellable), so no real
  cancellation swallow is hidden. app-check + legacy-tests NOT run.
- G2 at 2-debug-iteration cap -> HANDED BACK PARTIAL to owner. Recommendation: shrink config/baselines/cancellation.json
  occurrence count for "G-CANCEL-03 .../OperationRunRecorder.kt" 3 -> 1 (tightening only; no growth, no logic/date change),
  then rerun static-guards -> app-check -> legacy-tests.
- 2026-10-16 OWNER: "i say yes continue" -> APPROVED: cancellation baseline shrink. Applied: config/baselines/cancellation.json :101
  "G-CANCEL-03 .../OperationRunRecorder.kt" 3 -> 1 (no other change; no test pins the total). Queue: static-guards -> app-check ->
  legacy-tests.
- G3 plan persisted (plan section 26, release-planner read-only). 2026-10-16 OWNER: "do your reccomendations" -> APPROVED:
  (Q-G3-1) shrink-only guard/baseline edits pre-approved for G3 (remove entries, lower counts, regenerate
  db_ownership_policy signatures to match code); any addition/loosening/date change still goes to the owner.
  (Q-G3-2) the 2 reclassified DirectEventDao entries (WarrantyTrackerRepository, BankApiIntegration) get expiry 2027-01-01 in line
  with the existing owner entries. PRIVACY_AUDIT_WRITE_BLOCKED dropped (existing PRIVACY_AUDIT_WRITE_FAILED covers it).
- G2 BATCH GATE (fingerprint ab8993a6..., start=end, rev 88382c4c + G2 diff): static-guards PASS 25/25 (vr-20261016-115803-6d009c7e);
  app-check PASS (vr-20261016-122027-364680e6, 1919 s; debug+release 15847 passed / 0 failed / 555 skipped total = 16402, +32 vs R26
  i.e. 16 new tests per variant; lint via baseline, 1 stale baseline entry); legacy-tests PASS (vr-20261016-125302-b26c6ac6, 253/0/1).
  NOTE: the legacy-tests profile does NOT run HANG-001..003, so it gives no HANG ledger evidence; the 2026-11-01 runner
  E_HANGING_LEDGER_REVIEW_OVERDUE (legacy-tests profile only) remains open -> needs a ledger review decision before 11-01 (follow-up).
- G2 COMMITTED on release-1.0-wt.
- CORRECTION (lead, read-only check): the legacy-tests run vr-20261016-125302-b26c6ac6 DID run all three ledger filters
  (config/validation/known-hanging-tests.json: HANG-001 domain.receipt.lifecycle.*, HANG-002 domain.transaction.lifecycle.*,
  HANG-003 data.backup.ExportReadBarrierTest) -> 253 passed / 0 failed / 1 skipped, no hang, 49 s. The runner agent's "not in this
  filter" remark was wrong. Deadline: validation-runner.ps1 :391-392 throws E_HANGING_LEDGER_REVIEW_OVERDUE for legacy-routed
  suspected/confirmed entries after review_by 2026-10-31 (all 3, owner "unassigned"). Policy (docs/testing/KNOWN_HANGING_TESTS.md):
  resolve = status resolved + validating run ID. Caveat: if all 3 are resolved, Get-LegacyFilters returns no filters -> behaviour of
  New-TestArguments with an empty list unverified (could run everything or fail). OWNER DECISION pending (HL-1).
## G3 (owner-approved; plan section 26)
- S1 coder (52 calls vs 45 budget, overrun disclosed; complete): barrier before DAO writes in ReceiptInsertResolver,
  DebugExpenseAuditWriter (2 inserts), RoomReceiptLifecycleEventWriter, RoomTransactionLifecycleEventWriter. Caller analysis: no
  caller in non-NORMAL mode (nothing in data/backup or startup references them); callers check the barrier at entry
  (ReceiptRepository :506/:518, ReceiptRecordWriter :27, ReceiptLifecycleCoordinator :807/:854/:1026, ExpenseRepository :717/:747,
  GroupTransactionCoordinator :967); WarrantyTrackerRepository :185 / NotificationProcessingPipeline :1373 drop blocked audit events
  (CE rethrown). Tests: new domain.LifecycleWritersWriteBarrierTest; TransactionSideEffectFailureEventWriterTest +2; constructor
  fixes ReceiptDuplicateFingerprintCollisionTest, ReceiptPartialOcrPipelineTest. Dispatched: reviewer-strict + 9 targeted runs.
- S1 review (reviewer-strict): ACCEPT-WITH-NOTES, no critical/major. All callers check the barrier before their tx (defence in
  depth); TransactionLifecycleCoordinator/insertAtomic paths not affected except via the accepted post-commit mirror drop;
  TransactionContextProvenanceGuardTest scans src/main only (:12, :73-76); WriteBarrier stale check (:601-608) checks file existence
  only, so S1 passes before S3. Notes: N1 WarrantyTrackerRepository :179-204 catch swallows a blocked event write inside nested tx ->
  outer tx silently rolled back but method returns id of an unsaved warranty (pre-S1 the same window wrote during maintenance) ->
  FOLD INTO S5 (warranty file): rethrow DatabaseAccessBlockedException there + test. N2 BankStatementLifecycleProcessor :1006-1043
  failed-run finalize rolled back during maintenance (stale-run recovery assumed, unverified) -> check in R27. N3 cosmetic.
- S1 targeted (fingerprint f796ab8a..., start=end): LifecycleWritersWriteBarrierTest 15 (vr-20261016-125735-a183ae07),
  TransactionSideEffectFailureEventWriterTest 6 (vr-...130652-0a6c18fa), ReceiptDuplicateFingerprintCollisionTest 7
  (vr-...130759-db56885d), ReceiptPartialOcrPipelineTest 22 (vr-...130906-52550a13), ReceiptLifecycleCoordinatorTest 37
  (vr-...131012-1197d2de), WriteBarrierArchitectureGuardTest 16 (vr-...131119-c2c2b5ad), DirectEventDaoInsertGuardTest 6
  (vr-...131325-82965c56), CancellationSafetyArchitectureGuardTest 22 (vr-...131402-b71a8412), TransactionContextProvenanceGuardTest 5
  (vr-...131508-7b7571ff): ALL PASS, 136/0/0. S1 slice-level PASS (batch gate at G3 end). Next: S2 coder.
- S2 coder (81 calls vs 50 budget — overrun, disclosed; complete): PrivacyAuditLoggerImpl barrier; exportDatabase settings read +
  privacy checks before enterAndDrain (denial now precedes "Database file not found"); G2b reorders: restore 7 sites, reset 2, legacy
  import 9 + outer catch (incl. ~:2093, flag provably false). Not changed: restore "Safety backup path unavailable" (~:1197) never
  exits maintenance (pre-existing; reachability to be confirmed by review). Residual: finalize skipped if exit throws a
  non-WorkerReschedule exception. Tests: DatabaseBackupRepositoryImplTest +5, CompositePrivacyGateTest +1, new
  PrivacyAuditLoggerImplWriteBarrierTest (Robolectric), PrivacyGateEnforcementGoldenTest constructor. Dispatched reviewer-strict +
  8 targeted runs.
- S2 review (reviewer-strict): ACCEPT-WITH-NOTES, no critical/major. No fail-open (CompositePrivacyGate :72-85; barrier blocks in
  any non-NORMAL mode :17); workers check maintenance (:108) before privacy (:164), retry on PRIVACY_AUDIT_WRITE_FAILED (:169-170);
  exportDatabase equivalent for all decisions, enter/exit paired once (:429-438, finally :505); all G2b reorders correct, none after a
  swap (swaps :1224/:1995/:2922); :2106 claim verified; exit-throws skipping finalize is not a regression (barrier rejected it before
  S2). Restore "Safety backup path unavailable" DBRI :1194-1205 UNREACHABLE (success value never null :2845; failure handled :1179)
  but would leak maintenance (no outer finally) — deviation from plan site :1187 recorded; follow-up P3: getOrThrow() or exit+reorder
  (bundle with a later DBRI batch). Notes: N1 privacy-gated features (cloud AI, geocoding, currency, export) show a privacy error
  instead of a maintenance message in non-NORMAL modes (safe direction; UX P3); N2 barrier check-then-insert race (R27). Test gaps
  (P3): ENCRYPTED_BACKUP/gate-throws/settings-throw export branches, reset outer catch :2994, no real-barrier end-to-end test.
- S2 targeted (fingerprint c53e57d7..., start=end): PrivacyAuditLoggerImplWriteBarrierTest 3 (vr-20261016-132045-186cac92),
  CompositePrivacyGateTest 7 (vr-...132606-07c5b414), PrivacyGateEnforcementGoldenTest 1 (vr-...132713-e4a03f2f),
  DatabaseBackupRepositoryImplTest 88 (vr-...132820-cf662f54), PrivacyGateContractTest 10 (vr-...132957-a35736b8),
  WorkerExecutionGuardTest 51 (vr-...133034-5d565a22), WriteBarrierArchitectureGuardTest 16 (vr-...133141-c520c02c),
  CancellationSafetyArchitectureGuardTest 22 (vr-...133348-0f7e1d59): ALL PASS 198/0/0. S2 slice-level PASS. Next: S3 coder.
- S3 coder (71 calls vs 50 budget — overrun, disclosed; complete, uncompiled): all 8 U-004 entries removed (5 fixed confirmed by
  file:line; 3 -> COVERED_BY_CALLER map, cap 3, WORKER_CHECKPOINT 1500-char window within enclosing fun, SOLE_CALLER_GUARDED reference
  scan + 300-char window, checkpoint-implementation evidence check; hygiene + 12 fixture tests; KDoc). WarrantyExpirationWorker
  checkpoints before recoverStaleClaimed, deleteOlderThan, seed, after-claim, record-outcome; NotificationIntakeWorker checkpoint
  added in purgePayloadBestEffort. Tests: WarrantyExpirationWorkerTest +3, NotificationIntakeWorkerTimeoutTest +1. Disclosed risks:
  (a) blocked outcome write -> row CLAIMED -> recoverStaleClaimed after >1 h may re-send the reminder; (b) blocked purge leaves the
  encrypted payload unpurged on a terminal row (privacy retention question); WorkerRunLogger terminal writes have no own check.
  Dispatched architecture-guardian (incl. (a)/(b) judgement) + 7 targeted runs.
- S3 review (architecture-guardian): ACCEPT-WITH-NOTES. COVERED_BY_CALLER is a real per-write-site proof (stricter than the
  class-level rule :404-410); skip only on zero violations (:747-749); unlocatable sites fail closed (:599-600); no other guard change.
  G1 MEDIUM (latent): SOLE_CALLER scan \bWorkerRunLogger\b misses WorkerRunLoggerImpl (@Inject, WorkerRunLogger.kt:67) -> fix: match
  WorkerRunLogger\w* and WorkerRunHandle + fixture. G2-G4 LOW (window ignores control flow; any .checkpoint( counts incl. no-op
  PostCommitActionRunnerImpl :146-148; read-only ctx WorkerExecutionGuard :303-309). WorkerRunLogger terminal writes are protected by
  lease + failing drain (WorkerExecutionGuard :333-448, :242-244; DBRI drains :430/:673/:2786/:974 fail on timeout), not by
  checkWritesAllowed -> reword justification. 4a duplicate warranty reminder LOW acceptable (fixed notification id :232 replaces).
  4b MEDIUM PRE-EXISTING PRIVACY RETENTION GAP (new trigger in S3): encrypted intake payloads (transientPayloadCiphertext/Nonce/
  Version) are never purged once a best-effort purge fails: RetentionModule :285-287 calls purgeRawPayload (NotificationIntakeDao
  :195-202) which leaves ciphertext; purgeTransientPayload :246-255 has no prod caller; no row deletion. Minimal fix: retention sweep
  selects capturedAt < cutoff AND (rawPayloadPurgedAt IS NULL OR transientPayloadCiphertext IS NOT NULL) -> purgeAllPayload.
  OWNER DECISION needed (out of S3 scope) -> proposed R28.
- Lead: S3 review iteration 1 fix (in scope, guard strengthening): G1 regex + fixture + justification reword; after the running
  targeted queue finishes.
- S3 targeted (fingerprint 19a5f47e..., start=end): WriteBarrierArchitectureGuardTest 31 (vr-20261016-134245-54c56ec4),
  WarrantyExpirationWorkerTest 18 (vr-...135032-d3d3372c), NotificationIntakeWorkerTimeoutTest 18 (vr-...135139-d88b282d),
  WorkerExecutionGuardTest 51 (vr-...135246-23e5fb76), WorkerRunLoggerTest 60 (vr-...135353-0b31d691), DirectEventDaoInsertGuardTest 6
  (vr-...135637-6a5325db) PASS; *NotificationIntakeWorkerTest vr-...135500-daac3e19 NO_TESTS (lead filter error; class does not
  exist) -> not counted.
- Lead review-iteration-1 fix (S3, test-only guard strengthening): SOLE_CALLER reference regex \b<class>\w*\b (catches
  WorkerRunLoggerImpl); new fixture "sole caller guarded fails on an extra production reference to the Impl class"; WorkerRunLogger
  justification reworded (start checked; terminal writes under lease before lease.close(); drains fail rather than proceed).
  Real-source check: WorkerRunLogger\w* in src/main only in WorkerRunLogger.kt, WorkerExecutionGuard.kt, DiagnosticsModule.kt (+ a
  KDoc mention in WorkerTerminalDiagnosticSink, stripped by the sanitizer).
- Decision: commit G3 part 1 (S1-S3, the 2026-10-31-critical part) after its own batch gate; S4-S6 (before 11-20) follow as part 2.
  Gate queue dispatched: targeted WriteBarrierArchitectureGuardTest -> static-guards -> app-check.
- G3 part-1 gate: targeted WriteBarrierArchitectureGuardTest PASS 32 (vr-20261016-135802-2e110838, fingerprint 22f6175d...);
  static-guards FAIL 22/25 (vr-20261016-140147-4dc0d2a4): known_good_state INFRA, db_access INFRA (exit 2,
  DB_POLICY_SOURCE_EVIDENCE_INVALID / INVALID_SOURCE_IDENTITY), guard_tests 5 failures (db_access_boundaries real-config pipeline,
  db_access_v2 x2, wave2_db_ownership_reconciliation x2 incl. [9-markFailedByKey]). app-check not run. Same failure class as R26
  (source evidence pin no longer matches source). Debugger (Opus, read-only) dispatched. G3 part 1 debug iteration 1.
- Debugger (Opus, read-only): root cause = G3 signature changes left 6 active v2 policy rows with old parameter lists ->
  stage 4 verify_v2_policy_source_evidence (verify_db_access_boundaries.py :3693-3703) SIGNATURE_UNSUPPORTED
  (policy_v2_evidence.py :876-902) -> DB_POLICY_SOURCE_EVIDENCE_INVALID: WarrantyExpirationWorker.deliverReminder now takes ctx first
  (5 rows: claim, insertOrIgnore, markFailed, markFailedByKey, markSentFromClaimed) and NotificationIntakeWorker.purgePayloadBestEffort
  ctx now non-null (1 row). Not a structural-exception issue; known_good_state INFRA is a consequence (active_db_gate only).
  No regeneration; candidate/promotion/accounting files are historical inputs, leave untouched.
- Lead fix (identity/evidence only, owner pre-approval Q-G3-1; no DAO/operation/barrierMode/row-count change): db_ownership_policy.yml
  5 deliverReminder rows + WorkerRunContext first param; purgePayloadBestEffort WorkerRunContext? -> WorkerRunContext;
  test_wave2_db_ownership_reconciliation.py :106 tuple + PKG WorkerRunContext. Debug iteration 1 of 2 for G3 part 1.
  Queue: registered-guard db_access -> known_good_state -> static-guards -> app-check.
- 2026-10-16 OWNER: "I approve." (reply to the pending R28 + HL-1 recommendations) -> APPROVED:
  R28: retention sweep also purges encrypted intake payloads (transientPayloadCiphertext/Nonce/Version) on old rows
  (capturedAt < cutoff AND (rawPayloadPurgedAt IS NULL OR transientPayloadCiphertext IS NOT NULL) -> purgeAllPayload), with tests.
  HL-1: config/validation/known-hanging-tests.json HANG-001..003 owner "unassigned" -> "@panospao7", evidence += clean legacy-tests
  run vr-20261016-125302-b26c6ac6 (253/0/1, no hang), review_by 2026-10-31 -> 2026-12-01; status stays suspected; resolve only after
  a second clean run on the final candidate.
  Sequencing: after the G3 part-1 commit (no edits during the running gate), as small batch "R28+HL-1".
- G3 part-1 gate PASS after debug iteration 1 (worktree fingerprint f63a1e57...0f654c65, start=end on every run, rev cd3b8d71 + diff):
  registered-guard db_access PASS vr-20261016-141844-d113db06 (baseline 0 / current 0); registered-guard known_good_state PASS
  vr-20261016-142126-4a324b97 (rows 7: pass 6, fail 0, skip 1 = test_result_freshness stamp=missing, not verified);
  static-guards 25/25 PASS vr-20261016-142437-9e43c10b (guard_tests PASS; ratchet rows zero-delta);
  app-check PASS vr-20261016-144626-142c1426 (testDebugUnitTest 8246 run / 0 failed / 276 skipped; testReleaseUnitTest 8246 / 0 /
  277; lint via lint-baseline.xml). Lead verified result.json status/exit/fingerprints + complete.marker for all 4.
  Open notes carried: S1-N1 (Warranty catch -> fold into S5), S2-P3 (unreachable restore safety-path branch never exits
  maintenance), S3 G2-G4 (low), known_good_state freshness stamp SKIP. Correction to the G2 handoff note: legacy-tests
  vr-20261016-125302-b26c6ac6 did run the HANG-001..003 filters (no hang) -> used as HL-1 evidence.
  Decision: commit G3 part 1 (S1-S3 + policy identity fix) on release-1.0-wt. Next: batch R28+HL-1.
- 2026-10-16 OWNER: "I approve" (reply to the G3 part-1 report asking to confirm that "I approve." covered both R28 and HL-1) -> confirmed: R28 + HL-1 both approved. Batch R28+HL-1 started on release-1.0-wt @ e6727b2b.
- R28+HL-1 discovery: first verifier hit its turn limit with no report (no evidence). Re-split: verifier R28 CONFIRMED (status-agnostic
  predicate recommended; loop terminates; single caller of getUnpurgedIntakeOlderThan; only RetentionTargetPurgeTest covers it; no
  test for PAYLOAD_UNAVAILABLE_PRIVACY). Scout: active policy row :3601-3618 must swap operation; candidate/accounting/seeds generated,
  leave untouched; HL-1 values pass Get-LegacyFilters rules (validation-runner.ps1 :371-398; evidence unvalidated; file is in the
  worktree fingerprint). Lead decision: the policy row swap is covered by the owner's R28 approval (same site/DAO/barrier/count).
  New finding R28-X (unverified): ExportAnonymizer.sanitizeNotificationIntake :267-293 keeps intake ciphertext in the export copy.
  Owner note: retention 0 days -> unprocessed TRANSIENT capture ends PAYLOAD_UNAVAILABLE_PRIVACY (STORE_RAW already loses text today).
  Plan: section 27. Coder dispatched.
- R28+HL-1 coder done (6 files). Targeted PASS (fingerprint 1be82d28..., start=end): RetentionTargetPurgeTest 10/0/0
  vr-20261016-153131-68e18aa7, NotificationIntakeWorkerTimeoutTest 19/0/0 vr-20261016-154305-54b0ea17, RetentionModuleBatchedPurgeTest
  7/0/0 vr-20261016-154335-4ad29cb4; runner accepted the edited hanging ledger. Review: privacy-security-guardian ACCEPT-WITH-NOTES
  (privacy PASS). LOW applied by lead (test-only): new fixtures payloadMode "ENCRYPTED" -> "TRANSIENT", helper default status
  "DONE" -> "PROCESSED" (real enum values; pre-existing worker test :233 "ENCRYPTED" left as is). Follow-ups (not in batch):
  F-R28-a GR-08-seeds.yml :3162 / signatures.candidate.yml :3549 still purgeRawPayload -> update at the next approved regeneration
  (latent: candidate promotion would revert the row); F-R28-b encryptAndClearVisiblePayload should refuse purged rows (own slice);
  F-R28-c purgeRawPayload now unused (remove later); F-R28-d optional retention slider text for 0 days. Review iteration 1 of 2.
  Gate queue: targeted x2 (fixture rerun) -> legacy-tests -> static-guards -> app-check.
- R28+HL-1 gate (fingerprint a5646bd5..., start=end, rev e6727b2b + diff). Gate agent hit its turn limit without a report; lead read
  result.json/complete.marker directly: targeted RetentionTargetPurgeTest PASS vr-20261016-154457-43ad8fb8 (test task executed after
  test recompile; XML overwritten by the next run, so count not captured - covered again by app-check), NotificationIntakeWorkerTimeoutTest
  PASS vr-20261016-154640-dab856b5 (same caveat); legacy-tests PASS vr-20261016-154702-3e58036a (22 suites / 253 tests / 0 failed /
  0 skipped, all three HANG filters in the command, no hang, 39 s) = SECOND clean legacy run, now on rev e6727b2b + R28 diff;
  static-guards 25/25 PASS vr-20261016-154758-9c85e96c (0 warn, 0 infra). app-check vr-20261016-160928-351117e4 RUNNING; wait-only
  runner dispatched.
- R28+HL-1 app-check PASS vr-20261016-160928-351117e4 (fingerprint a5646bd5... start=end, BUILD SUCCESSFUL 28m13s; debug 8253 run / 0
  failed / 276 skipped, release 8253 / 0 / 277 = +7 new tests vs G3 part 1; RetentionTargetPurgeTest 10/0, NotificationIntakeWorkerTimeoutTest
  19/0, RetentionModuleBatchedPurgeTest 7/0 inside it). Batch gate PASS. Debug iterations 0; review iteration 1 (LOW fixture fix only).
  HL-1: HANG-001..003 stay "suspected" (owner rule: resolve only after a clean run on the FINAL candidate; legacy run 154702 is clean
  run #2 but not on the final candidate). Decision: commit R28+HL-1 on release-1.0-wt. Next: G3 part 2 (S4-S6, before 2026-11-20).
