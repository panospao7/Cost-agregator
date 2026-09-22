# CA-2026-09-21 — FINDINGS LEDGER (Stage 1 consolidation)

Generated: 2026-09-22 (post-campaign consolidation pass)
Source: all 22 cell audit reports (`cell-{P,E,I}-*-audit.md`) in this directory
Source pin: `37601232b9778170c57a656a245b199ab6d7d965` (all reports verified against this pin)
Method: 7 parallel read-only extraction agents; per-cell row counts reconciled against each
report's own claimed finding count (all matched); P-01 rows cross-checked against the completed
adversarial verification (`verification-2026-09-21-p01.md`, 7/7 CONFIRMED — exact match).

Row format: `ID | severity | primary files | mechanism | fix shape`. Rows are the reports' claims
at the pin, lightly compressed — this ledger adds no new judgments. Clustering is Stage 2's job;
§Cross-reference below is mechanical signal only.

Verification status: only P-01 has adversarial verification. P-07's four P0s and all other rows
are unverified report claims. Revalidate rows before Wave-1 planning, P0/P1 first.

ID quirks preserved for traceability: P-08 report uses `CA-P08-nnn` (no second dash); E-05 report
numbering is non-contiguous (005, 008 only).

TOTALS: 89 findings — **7 P0 / 15 P1 / 54 P2 / 13 P3** across 22 cells (E-03: zero, earned).

---

## P-cells (horizontal pipelines)

### P-01 — Notification capture/intake (7: 1 P0, 5 P2, 1 P3) — VERIFIED 7/7 CONFIRMED
| ID | Sev | Files | Mechanism | Fix shape |
|---|---|---|---|---|
| CA-P-01-001 | P0 | NotificationCaptureService.kt, NotificationCaptureGate.kt, NotificationIntakeCoordinator.kt | Deferred capture stores and forwards content without rechecking capture consent or blocked package | Check consent, privacy gate, blocked package before deferred intake storage |
| CA-P-01-002 | P2 | NotificationIntakeCoordinator.kt, NotificationIntakeRecoveryScheduler.kt, AppStartupCoordinator.kt | Distinct deferred rows share one unique work name; REPLACE cancels first row's pending worker | Per-row unique work names or schedule recovery upon replacement |
| CA-P-01-003 | P2 | NotificationIntakeWorker.kt, NotificationIntakeDao.kt, WorkerExecutionGuard.kt | Backoff-not-due zero-row claim returns WorkManager success, terminating retry chain early | Map backoff-not-due zero claims to worker retry, not success |
| CA-P-01-004 | P2 | NotificationCaptureService.kt, NotificationTextParts.kt, NotificationFilter.kt | Live filter receives only bigText, discarding combinedBody extraction; valid notifications drop | Pass extracted combinedBody into the live filter |
| CA-P-01-005 | P2 | NotificationIntakeWorker.kt, NotificationTransientPayloadCrypto.kt, WorkerExecutionGuard.kt | Payload load/decrypt failures after claim escape intake failure handler; row stuck PROCESSING | Extend intake failure transition handler over payload load/decrypt |
| CA-P-01-006 | P2 | NotificationMoneySignalDetector.kt, NotificationProcessingPipeline.kt, ReviewQueueRepository.kt | Ambiguous "kr" amounts return explicit SEK before home-currency resolution applies | Defer ambiguous kr to home-currency resolution branch |
| CA-P-01-007 | P3 | NotificationTransientPayloadCrypto.kt | RP-10 completion doc describes a 0xFF framing marker the code never emits | Correct RP-10 documentation to match actual frame format |

### P-02 — Review queue / expense-from-review (3: 1 P1, 1 P2, 1 P3) — report states PARTIAL coverage
| ID | Sev | Files | Mechanism | Fix shape |
|---|---|---|---|---|
| CA-P-02-001 | P3 | SourceLinkBackfillWorker.kt, PendingReviewDao.kt, SourceLinkBackfillViewModel.kt | Unwired backfill would link every pending notification review to each notification expense | Keep helper unwired until relationship algorithm corrected |
| CA-P-02-002 | P1 | ReceiptLinkService.kt, ReviewQueueRepository.kt, DefaultExpenseCategoryAssignmentService.kt | Category side effects dispatch while enclosing review-approval transaction still open | Defer category dispatch until outer approval transaction commits |
| CA-P-02-003 | P2 | TransactionLifecycleCoordinator.kt, ExpenseDao.kt, Expense.kt | Editing strict-source expenses overwrites external dedupe key; identity replays create duplicates | Preserve strict external dedupe key across edits |

### P-03 — Receipt lifecycle/OCR (5: 4 P1, 1 P3) — expanded coverage PARTIAL
| ID | Sev | Files | Mechanism | Fix shape |
|---|---|---|---|---|
| CA-P-03-001 | P1 | ReceiptMatchingWorker.kt, ReceiptMatchLifecycleService.kt, ScannedReceiptDao.kt | Stale suggestion write unconditionally sets SUGGESTED, reopening rejected/matched receipts | Add current-status predicate/CAS to suggestion writes |
| CA-P-03-002 | P1 | ReceiptLifecycleCoordinator.kt, EffectiveCloudAiPolicy.kt, ReceiptOcrService.kt | Local ML Kit OCR blocked unless cloud and image-upload consent enabled | Gate local OCR independently of cloud/image-upload consent |
| CA-P-03-003 | P1 | ReceiptOcrService.kt, BankStatementLifecycleProcessor.kt | failedPages has no consumer; partially-failed PDF imports reported COMPLETED | Consume failedPages; mark and report partial PDF imports |
| CA-P-03-004 | P1 | ReceiptDuplicateDetector.kt, ReceiptLifecycleCoordinator.kt | Text fingerprint strips amounts/dates; different purchases treated as same receipt | Require amount/date/semantic agreement before text-fingerprint duplicate |
| CA-P-03-005 | P3 | ReceiptLifecycleCoordinator.kt | Legal-path doc names permanently disabled creation API instead of real entry | Update LEGAL_PATHS to document createExpenseAndLinkReceipt |

### P-04 — Recurring/bill reminders (7: 1 P1, 6 P2)
| ID | Sev | Files | Mechanism | Fix shape |
|---|---|---|---|---|
| CA-P-04-001 | P2 | BillReminderWorker.kt, RecurringLifecycleCoordinator.kt, SnoozeReminderActionWorker.kt | Notifications recorded SENT; snooze/dismiss actions hit terminal-state NoOp | Allow snooze/dismiss on SENT; cancel posted notification |
| CA-P-04-002 | P2 | BillReminderWorker.kt, RecurringLifecycleCoordinator.kt, RecurringReminderDeliveryDao.kt | Transient posting failure marks FAILED_TRANSIENT; due queries never reselect | Auto-recover/retry FAILED_TRANSIENT in due-reminder selection |
| CA-P-04-003 | P2 | RecurringRuleLifecycleCoordinator.kt, RecurringPlanProjectionService.kt, SmartBillNegotiationEngine.kt | Updating inactive rule unconditionally regenerates occurrences and reminders | Check rule activity in update reconciliation; skip inactive materialization |
| CA-P-04-004 | P2 | RecurringLifecycleCoordinator.kt, RecurringOccurrenceExpander.kt, RecurringRuleLifecycleCoordinator.kt | Projection passes clamped date as anchor; keys diverge from materialization, duplicating bills | Preserve original fixed anchor date into projection ExpandRequest |
| CA-P-04-005 | P1 | RecurringRuleLifecycleCoordinator.kt, ManualRecurringExpenseDao.kt, RecurringOccurrence.kt | Activation reads rule pre-transaction; concurrent delete yields zero-row update creating orphan | Read rule inside transaction; abort on zero-row update |
| CA-P-04-006 | P2 | BillReminderManager.kt, BillRemindersScreen.kt, BillRemindersViewModel.kt | Reminder screen labels foreign amounts home currency; sums mixed currencies unconverted | Convert or group by currency before display/totaling |
| CA-P-04-007 | P2 | BillReminderWorker.kt | Failure catches pass raw Throwables directly to Android Log | Log bounded reason codes/class names only |

### P-05 — Dashboard/widgets (4: all P2) — report states PARTIAL (20-file read ceiling)
| ID | Sev | Files | Mechanism | Fix shape |
|---|---|---|---|---|
| CA-P-05-001 | P2 | ComputeDashboardWidgetsUseCase.kt, HomeScreen.kt | Week aggregate filters month-prefiltered expenses; cross-month week purchases omitted | Compute week totals from week-spanning fetch, not month slice |
| CA-P-05-002 | P2 | ExpenseDao.kt, LifestyleInflationDetector.kt, LifestyleSavingsPromptUseCase.kt | Newest-first DAO order reverses month trend; growth signs inverted, prompt suppressed | Sort month totals chronologically before calculateTrend |
| CA-P-05-003 | P2 | ComputeMoneyRadarUseCase.kt, MoneyRadarWidget.kt | Failed risk-source reads return empty/null; radar shows green All Clear | Distinguish read failures from empties; emit unavailable state |
| CA-P-05-004 | P2 | ComputeDashboardWidgetsUseCase.kt, HomeViewModel.kt, MonthlySavingsSweepUseCase.kt | Savings sweep rethrows its own TimeoutCancellationException, aborting whole dashboard | Catch local timeout; degrade only that widget |

### P-06 — Budget/insights engines (5: all P2)
| ID | Sev | Files | Mechanism | Fix shape |
|---|---|---|---|---|
| CA-P-06-001 | P2 | BudgetAutopilotEngine.kt, BudgetViewModel.kt, MultiCurrencyRepository.kt | Autopilot caps home-currency history against unconverted source-currency budget | Normalize budget amount to home currency before compare/cap |
| CA-P-06-002 | P2 | BudgetAutopilotEngine.kt, BudgetViewModel.kt | Hierarchy scaling runs after 15% clamp, enabling much larger cuts | Re-clamp scaled recommendations to 15% safety bounds |
| CA-P-06-003 | P2 | BudgetForecastingEngine.kt, BudgetForecastingViewModel.kt | Raw engine failure reasons and exception messages in UI error state | Map failures to controlled sanitized reason codes |
| CA-P-06-004 | P2 | SynthesisEngine.kt | Synthesis fallback logs full exception and stack trace via Timber | Log controlled class/reason code without throwable |
| CA-P-06-005 | P2 | FinancialStressForecastEngine.kt | Compute failures return fabricated MODERATE 20% result instead of unavailable | Return typed unavailable/error result on failure branch |

### P-07 — Backup/restore (9: 4 P0, 3 P1, 2 P2) — report states PARTIAL (repository helpers unread) — P0s UNVERIFIED
| ID | Sev | Files | Mechanism | Fix shape |
|---|---|---|---|---|
| CA-P-07-001 | P1 | DatabaseBackupRepositoryImpl.kt, PrivacyDecision.kt, CompositePrivacyGate.kt | Export checks only Denied; FailClosed privacy result still enters maintenance and writes bundle | Treat FailClosed as blocking; fail export closed |
| CA-P-07-002 | P1 | DatabaseBackupRepositoryImpl.kt, DatabaseReadBarrier.kt | getDatabaseStats reads DAOs without read-barrier admission; exceptions become zero stats | Inject DatabaseReadBarrier; surface blocked reads as errors |
| CA-P-07-003 | P0 | RestoreMaintenanceMode.kt, DatabaseWriteBarrier.kt | writeMode ignores failed commit and publishes mode; invalid persisted value decodes to NORMAL | Fail on commit failure; reject unknown persisted modes fail-closed |
| CA-P-07-004 | P0 | RestoreJournal.kt, AppStartupCoordinator.kt | Unreadable/unknown-state journal becomes null/PREPARING; recovery skipped, maintenance reset | Treat corrupt/unknown journals as critical; fail closed at startup |
| CA-P-07-005 | P0 | RestoreJournal.kt, DatabaseBackupRepositoryImpl.kt | writeJournal swallows fsync/rename failures; restore proceeds to SWAPPING without durable journal | Propagate journal write failures; abort before destructive swap |
| CA-P-07-006 | P0 | DatabaseBackupRepositoryImpl.kt, RestoreJournal.kt, RestoreJournalImporter.kt | Reset passes raw e.message into failure journal; importer copies unsanitized into ledger | Sanitize error text before journal persistence and import |
| CA-P-07-007 | P2 | BackupPrivacyMode.kt, DatabaseBackupRepositoryImpl.kt, CostbackupBundle.kt | REDACT_RAW_TEXT claims images included but export empties receiptFiles; manifest/ZIP disagree | Align mode label/flag with actual bundle contents |
| CA-P-07-008 | P2 | RestoreJournal.kt, DatabaseBackupRepositoryImpl.kt, AppStartupCoordinator.kt | Restore/reset/recovery catches log raw throwable messages and stack traces | Log bounded sanitized reason codes |
| CA-P-07-009 | P1 | BackupVerifier.kt | verifySemanticIntegrity catches query exceptions and skips check; verification can pass | Make required orphan-check query failures verification errors |

### P-08 — Privacy/cloud AI (5: 3 P1, 2 P2) — source IDs use `CA-P08-nnn`
| ID | Sev | Files | Mechanism | Fix shape |
|---|---|---|---|---|
| CA-P08-001 | P1 | CompositePrivacyGate.kt | Gate's catch(Exception) converts CancellationException to FailClosed instead of rethrowing | Rethrow CancellationException before fail-closed conversion |
| CA-P08-002 | P1 | CloudQueryInterpretationService.kt | Provider uses legacy redactor and hand-built payload, never CloudPayloadPolicy.prepareText | Route through CloudPayloadPolicy/EffectiveCloudAiPolicy |
| CA-P08-003 | P1 | CloudDashboardBriefingService.kt, CloudReceiptAssistService.kt, CloudQueryInterpretationService.kt | Providers return and log raw e.message/stack traces; persisted into ai_artifacts | Bounded reason codes; strip exception text from logs/results |
| CA-P08-004 | P2 | HybridRouter.kt, HybridQueryInterpretationService.kt | Documented shared HybridRouter has no production caller; six services duplicate routing | Wire hybrid services through HybridRouter or fix docs |
| CA-P08-005 | P2 | CloudProviderPreparedPayloadTest.kt | Acceptance test exercises policy directly, never providers; guard accepts generic markers | Assert each provider injects/calls CloudPayloadPolicy; tighten markers |

### P-09 — Workers/diagnostics (3: all P2)
| ID | Sev | Files | Mechanism | Fix shape |
|---|---|---|---|---|
| CA-P-09-001 | P2 | MerchantKeyBackfillWorker.kt, ExpenseDao.kt, TransactionLifecycleCoordinator.kt | Backfill writes stale-snapshot key unconditionally by ID, overwriting newer committed key | Add still-NULL/CAS predicate to backfill update |
| CA-P-09-002 | P2 | WorkerRunLogger.kt, WorkerExecutionGuard.kt, FileWorkerTerminalDiagnosticSink.kt | toOutcome uses separate error argument; DAO failure misreported or null in fallback | Derive fallback errorClass from NotDurableFailure's exception |
| CA-P-09-003 | P2 | WorkerLeaseRegistryImpl.kt, SystemTimeProvider.kt, MaintenanceOperationRunner.kt | Drain deadline uses adjustable wall clock; clock jumps stretch/shrink timeout | Use monotonic elapsed time for drain timeout |

### P-10 — Bank sync (1: 1 P1) — broader cell coverage PARTIAL
| ID | Sev | Files | Mechanism | Fix shape |
|---|---|---|---|---|
| CA-P-10-001 | P1 | BankConnectionLifecycleCoordinator.kt, BankApiIntegration.kt, BankConnectionDao.kt | Coordinator persists FAILED terminal sync status after RESTORE_BLOCKED denial, no barrier check | Guard persistOutcome with barrier check; skip writes when restore-blocked |

### P-11 — Email intake (2: both P3)
| ID | Sev | Files | Mechanism | Fix shape |
|---|---|---|---|---|
| CA-P-11-001 | P3 | EmailReceiptParser.kt, AmazonReceiptParser.kt, AppleReceiptParser.kt | Conflicting ISO currency matches fall through to trusted-domain default | Reject conflicting ISO matches with unresolved outcome |
| CA-P-11-002 | P3 | EmailReceiptParser.kt | Total extraction never applies isSummaryRow; keyword fallback accepts summary rows | Apply summary-row exclusion in total extraction |

### P-12 — Import/export CSV (3: 1 P1, 2 P2)
| ID | Sev | Files | Mechanism | Fix shape |
|---|---|---|---|---|
| CA-P-12-001 | P1 | CsvExpenseImporter.kt, DebugScreen.kt, CreateExpenseSourceLinkRequirements.kt | CSV import omits required csvImportBatchId/csvRowNumber provenance; lifecycle rejects all rows | Supply batch ID and row number in import create requests |
| CA-P-12-002 | P2 | ExportOptionsViewModel.kt, ExportOptionsScreen.kt | Format switch retains old export result; Save/Share labels old bytes with new format | Clear/invalidate result on format change or store its format |
| CA-P-12-003 | P2 | Rfc4180CsvReader.kt, CsvExpenseImporter.kt, DebugScreen.kt | Malformed header record discarded during header search; success with zero rows | Report malformed header as error, not empty success |

---

## E-cells (engine clusters)

### E-01 — Money/currency core (6: all P2) — report states discovery-only
| ID | Sev | Files | Mechanism | Fix shape |
|---|---|---|---|---|
| CA-E-01-001 | P2 | CurrencyRatesRepositoryImpl.kt, CurrencyConverter.kt, ExchangeRateDao.kt | Refresh discards provider rate date; validDate fabricated as download-day UTC midnight | Parse and persist provider publication date as validDate |
| CA-E-01-002 | P2 | CurrencyRatesRepositoryImpl.kt, CurrencyConverter.kt, CurrencyManagementViewModel.kt | Refresh/selector catalog disagrees with converter SupportedCurrency whitelist | Unify one currency catalog across refresh, selection, converter |
| CA-E-01-003 | P2 | TimePeriodUtils.kt, MultiCurrencyRepository.kt, BudgetHistorySeriesBuilder.kt | Canonical month keys use default-locale digits vs repository ASCII keys; intersection empties | Format canonical month keys locale-independent ASCII |
| CA-E-01-004 | P2 | MoneyAggregateBuilder.kt, MoneyAggregate.kt, BudgetForecastingEngine.kt | fromBuckets never signals UNAVAILABLE on total conversion failure; defaults PARTIAL | Report total failure as UNAVAILABLE with quality metadata |
| CA-E-01-005 | P2 | MoneyAggregateBuilder.kt, MoneyNormalizationEngine.kt, MoneyAggregate.kt | Failed buckets omitted from sourceBuckets; count counts only successes, corrupting ratios | Preserve all source buckets; count failures in totals |
| CA-E-01-006 | P2 | CurrencyConverter.kt, AppStartupCoordinator.kt | convertMultiple logs exact failed conversion amounts and currencies to logcat | Log count plus failure code, never raw amounts |

### E-02 — Analytics/insights engines (6: all P2) — report states discovery-only
| ID | Sev | Files | Mechanism | Fix shape |
|---|---|---|---|---|
| CA-E-02-001 | P2 | BudgetVsActualEngine.kt, BudgetRepository.kt, AnalyticsViewModel.kt | Null-category budget actuals select only null-category expenses, reporting zero spend | Overall budgets aggregate all category spending |
| CA-E-02-002 | P2 | AdvancedAnalyticsEngine.kt, AnalyticsViewModel.kt | Empty-result factories use Locale.getDefault() currency, not resolved homeCurrency | Pass input.homeCurrency into empty factories |
| CA-E-02-003 | P2 | AnalyticsCurrencyNormalizer.kt, AnalyticsInputAssembler.kt | Assembler counts warning objects, not affected transactions; missingRateCount capped at 1 | Derive counts/multiplier from affectedTransactionCount |
| CA-E-02-004 | P2 | SynthesisEngine.kt, ForecastInputAssembler.kt | Forecast trajectory points omit recurring obligations that committed totals include | Add recurring occurrences/patterns to projected points |
| CA-E-02-005 | P2 | InsightsEngine.kt, RecurringExpenseEngine.kt | Manual-rule foreign amounts relabeled home currency without conversion | Convert manual-rule amounts or preserve source label |
| CA-E-02-006 | P2 | TotalsAggregationEngine.kt, InsightsEngine.kt, SynthesisEngine.kt | Error handlers log full Throwable with stack traces unsanitized | Log sanitized exception class and controlled reason only |

### E-03 — Categorization/merchant learning — ZERO FINDINGS (earned)
No rows. Historical debt items checked and excluded per cell rules.

### E-04 — Groups/shared expenses (4: 2 P0, 2 P3)
| ID | Sev | Files | Mechanism | Fix shape |
|---|---|---|---|---|
| CA-E-04-001 | P0 | SharedExpenseBudgetOffsetEngine.kt | Failed FX conversions log merchant, amounts, currencies, dates unredacted to logcat | Redact/remove financial payloads; condition logging on build type |
| CA-E-04-002 | P0 | SharedExpenseGroupsScreen.kt, SharedExpenseGroupsViewModel.kt, GroupsRepositoryImpl.kt | Dialog validates splits against departed members; UNEQUAL silently becomes equal | Filter inputs to active members; reject mismatched unequal payloads |
| CA-E-04-003 | P3 | GroupBalanceCalculator.kt | Repayment signs inverted; balances move away from zero after settlement | Reverse payment application toward zero |
| CA-E-04-004 | P3 | TaxEstimator.kt, BusinessExpenseRepository.kt, ExpenseDao.kt | Currency-less raw category sums relabeled filing currency; null-category remainder dropped | Restore currency-aware bucketing; include null-category remainder |

### E-05 — Side effects/warranties/subscriptions (4: 2 P2, 2 P3) — report PARTIAL (interrupted/resumed); numbering non-contiguous
| ID | Sev | Files | Mechanism | Fix shape |
|---|---|---|---|---|
| CA-E-05-001 | P3 | NotificationProcessingPipeline.kt | LEGAL_PATHS documentation forbids the policy-registered candidate writer | Align legal-path docs with registered ownership policy |
| CA-E-05-002 | P3 | SubscriptionManagementRepository.kt, SubscriptionManagementViewModel.kt, SubscriptionManagerEngine.kt | Docs mandate engine recordUsage; live UI uses registered repository writer | Update docs to match registered repository writer |
| CA-E-05-005 | P2 | ReceiptSideEffectPlanner.kt, AutoCreateWarrantyFromReceiptUseCase.kt, PostCommitActionRunnerImpl.kt | Planner discards returned Failure; unconditionally reports side effect Completed | Propagate Failure as FailedRetryable with classification evidence |
| CA-E-05-008 | P2 | WarrantyExpirationWorker.kt, WarrantyReminderDeliveryDao.kt, WarrantyDao.kt | Worker delivers reminder from stale ACTIVE snapshot; claim skips parent recheck | Revalidate parent warranty ACTIVE at delivery claim |

---

## I-cells (infrastructure)

### I-01 — App startup (1: 1 P2)
| ID | Sev | Files | Mechanism | Fix shape |
|---|---|---|---|---|
| CA-I-01-001 | P2 | AppStartupCoordinator.kt, RestoreMaintenanceMode.kt | Asset-recovery rollback resets mode to NORMAL without rescheduling cancelled workers | rollback branch must call WorkerRegistry.scheduleAll like exit |

### I-02 — Database/fresh-install (1: 1 P3)
| ID | Sev | Files | Mechanism | Fix shape |
|---|---|---|---|---|
| CA-I-02-001 | P3 | AppDatabase.kt | Stale KDoc and dead FRESH_INSTALL_CALLBACK imply callback-managed fresh schema post-v145 | Remove legacy callback/KDoc; rewrite parity tests vs canonical builder |

### I-03 — Hilt/DI modules (2: both P3)
| ID | Sev | Files | Mechanism | Fix shape |
|---|---|---|---|---|
| CA-I-03-001 | P3 | WorkerModule.kt, DiagnosticsModule.kt | Segment/inventory docs attribute WorkerRunLogger binding to wrong module | Correct docs to attribute binding to DiagnosticsModule |
| CA-I-03-002 | P3 | RetentionModule.kt | Architecture docs list ten retention targets; production registers fifteen | Update inventory/segments/Hilt map to all fifteen |

### I-04 — CI/static guards (7: all P2) — report states PARTIAL (20-file budget stop)
| ID | Sev | Files | Mechanism | Fix shape |
|---|---|---|---|---|
| CA-I-04-001 | P2 | ci.yml, capture_db_guard_evidence.py | Evidence-gate step omits required --base-ref; argparse exits before capture | Pass --base-ref in workflow capture step |
| CA-I-04-002 | P2 | verify_cloud_payload_boundaries.py, verify_privacy_boundaries.py | Raw-text policy markers accept comments; no executed preparation/body linkage | Require actual policy execution tied to posted body |
| CA-I-04-003 | P2 | guard_ratchet.py, verify_event_writers.py | V1 fingerprints collapse occurrences to rule/file sets; new violations pass | Compare occurrence counts like v2 ratchet |
| CA-I-04-004 | P2 | run_static_guard_suite.py, capture_db_guard_evidence.py | Hard-coded CI test globs omit barrier-proof, source-scope, policy-v2 modules | Expand suite/capture test selection |
| CA-I-04-005 | P2 | verify_worker_boundaries.py, WorkerGuardArchitectureGuardTest.kt | Raw regex accepts comment-only guard markers; misses qualified supertypes | Verify executed guard calls; discover qualified supertypes |
| CA-I-04-006 | P2 | verify_cancellation_boundaries.py, cancellation_allowlist.yml | is_allowlisted checks global rule and empty symbol, widening exemptions | Match active rule and exact symbol |
| CA-I-04-007 | P2 | verify_allowlist_compliance.py | Unparseable/missing YAML allowlists become empty lists; guard prints PASS | Fail closed on malformed/missing allowlist inputs |

### I-05 — ViewModels/UI layer (4: 1 P1, 2 P2, 1 P3)
| ID | Sev | Files | Mechanism | Fix shape |
|---|---|---|---|---|
| CA-I-05-001 | P1 | TransactionsViewModel.kt, ExpenseRepository.kt | User location clear hits DAO directly, bypassing lifecycle coordinator and UPDATED event | Route clear through TransactionLifecycleCoordinator.updateLocation |
| CA-I-05-002 | P2 | TransactionsViewModel.kt, ReceiptScanViewModel.kt | ViewModels interpolate raw e.message into UI state and retained logs | Sanitized reason codes/exception class only |
| CA-I-05-003 | P2 | NavigationDestination.kt, NavigationController.kt, DestinationPersistencePolicy.kt | Saved navigation token persists expense amount and currency in saved state | Redact financial fields; recover by expenseId |
| CA-I-05-004 | P3 | ExpenseUseCases.kt | Three injected use cases have no callers; one returns raw e.message | Delete dead use cases or fix unsafe contracts |

---

## Cross-reference (mechanical signal — NOT clustering verdicts)

### Files cited by findings in 2+ cells
| File | Cells | Finding IDs |
|---|---|---|
| AppStartupCoordinator.kt | 4 | P-01-002, P-07-004, P-07-008, E-01-006, I-01-001 |
| ExpenseDao.kt | 4 | P-02-003, P-05-002, P-09-001, E-04-004 |
| TransactionLifecycleCoordinator.kt | 3 | P-02-003, P-09-001, I-05-001 |
| RestoreMaintenanceMode.kt | 2 | P-07-003, I-01-001 |
| DatabaseWriteBarrier.kt | 2 | P-07-003, P-10-001 |
| CompositePrivacyGate.kt | 2 | P-07-001, P08-001 |
| SynthesisEngine.kt | 2 | P-06-004, E-02-004, E-02-006 |
| NotificationProcessingPipeline.kt | 2 | P-01-006, E-05-001 |
| DatabaseBackupRepositoryImpl.kt | 1 (5 findings) | P-07-001/002/006/007/008 |
| RestoreJournal.kt | 1 (4 findings) | P-07-004/005/006/008 |

### Emergent pattern candidates (same-shape mechanisms across cells — cluster seeds for Stage 2)
1. **Raw exception/stack-trace leakage** (~10 rows, 8 cells): P-04-007, P-06-003, P-06-004,
   P-07-006, P-07-008, P08-003, E-01-006, E-02-006, I-05-002, I-05-004. The single largest
   cross-cutting pattern; violates repo privacy rules uniformly. Likely one sweep + per-site fix.
2. **Swallowed failure → fabricated/zero "success"** (~7 rows, 6 cells): P-03-003, P-05-003,
   P-06-005, P-07-002, P-07-009, P-12-003, E-01-004. Error branches that degrade to
   plausible-looking empty/OK results instead of unavailable/error states.
3. **Restore/backup fail-closed chain** (5 rows, 2 cells): P-07-003/004/005/006, I-01-001.
   Corrupt-journal/mode/commit failures that proceed instead of halting. One design decision
   (where fail-closed enforcement lives) + local fixes.
4. **Missing CAS/staleness predicate on writes** (4 rows, 4 cells): P-03-001, P-04-005,
   P-09-001, E-05-008. Unconditional writes that clobber newer state.
5. **Currency labeling/conversion drift** (6 rows, 4 cells): P-04-006, P-06-001, E-01-002,
   E-02-002, E-02-005, E-04-004. Amounts displayed/aggregated under wrong currency assumption.
6. **Guard/CI fail-open** (8 rows, 2 cells): I-04-001..007, P08-005. Static guards that PASS on
   malformed input, comments, or unexercised code paths — weakens FG-03 fail-closed posture.
7. **Docs/reality drift** (8 rows, 6 cells, mostly P3): P-01-007, P-03-005, P08-004, E-05-001,
   E-05-002, I-02-001, I-03-001, I-03-002. Cheap mechanical fixes, batchable.
8. **Lifecycle-coordinator bypass** (1 explicit + 2 adjacent): I-05-001 direct DAO write;
   P-02-003 dedupe-key overwrite; P-09-001 backfill overwrite. TransactionLifecycleCoordinator
   surfaces in 3 cells.

---

## Stage-2 input checklist (what the planner needs beyond this ledger)
- Verdict-altering revalidation first: P-07's four P0s (003-006), E-04's two P0s, P-01-001
  (already independently verified), then the 15 P1s.
- PARTIAL cells whose coverage gaps may hide findings: P-02, P-03 (expanded), P-05, P-07
  (repository helpers), P-10 (broader cell), E-01/E-02 (discovery-only), E-05, I-04.
- Known-debt register (D1-D8, RP-21, NEW-P1-011) for collision triage — several reports
  explicitly excluded known-debt items that Stage 2 must not resurrect as new work.
