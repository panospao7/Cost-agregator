# B6 re-verification and writer groups (scout 2026-10-19; byte-checked by orchestrator with `git diff --name-only 8671b844 f8433f51`)

Release `release-1.0-wt` finished at f8433f51 (22 commits, 280 files after base 8671b844). The full changed-file list is in the scratchpad (release-changed.txt).
Of the files B6 names as READY-NOW, the release changed only **AdvancedAnalyticsEngineMoneyTest.kt** (plus AdvancedAnalyticsEngine.kt) and **config/validation/known-hanging-tests.json**. The scout missed the second one.
The release also changed production files that some READY-NOW slices exercise: DatabaseWriteBarrier.kt, AppDatabase.kt, GroupExpenseDao.kt, ReceiptLifecycleCoordinator.kt, WorkerExecutionGuard.kt, PrivacyCapability*.kt, CurrencyRatesRepositoryImpl.kt and NotificationProcessingPipeline.kt. Test merges that keep their assertions are unaffected, but every B6 slice must be re-validated after integration.

## Classification
- READY-NOW: B6-1, B6-2, B6-3 (without AdvancedAnalyticsEngineMoneyTest), B6-4, B6-5, B6-6, B6-7, B6-8, B6-10, B6-11 (without BackupRestoreViewModelPrivacyDenialTest), B6-12, B6-15 (without DailyBriefingWorkerTest), B6-16, B6-18 (without ReviewQueueRepositoryNestedRollbackTest), B6-19, B6-21, B6-25, B6-26.
- WAIT-FOR-INTEGRATION:
  - B6-9 (PrivacyCapabilityHandlingPolicyTest changed in release; a modify/delete conflict);
  - B6-13 and B6-14 (worker tests changed);
  - B6-17 (AtomicityTest);
  - B6-20 (DatabaseBackupRepositoryImplTest);
  - B6-22 (known-hanging-tests.json and DatabaseWriteBarrier changed);
  - B6-24 (AppDatabase changed; the "59 table names" check needs the final schema);
  - the parts excluded from B6-3, B6-11, B6-15 and B6-18.
- BLOCKED: B6-23 (needs B4-K5).
- Plan names that don't exist:
  - `NotificationProcessingPipelineTest` (bare name), `BudgetRepositoryValidationTest` and `CloudPayloadRedactorTest`;
  - the planned new files `CostbackupBundleTest` and `EventMetadataSanitizerTest`;
  - `CurrencyConversionGolden` is really `CurrencyConverterGoldenTest`.
- Plan line anchors are stale. Every writer re-anchors them.

## Order (one writer at a time; each slice is mapped by a scout first when its files are large; one multi-filter validation per group)
1. **G-B6a time (T2, cheap + time check):** B6-1, as three sub-writers. (Validation, Stress, Test) → (T4C 1, 2A, 2B) → (T4C 2C, 2D, TimePeriodAlignmentTest).
2. **G-B6b money (strict):** B6-2 alone, because it edits the shared TestUtils (exact assertMoneyEquals; fix the mojibake). Then B6-3 minus MoneyTest, in three sub-writers: SpendingPace, Insights, AdvAnalytics.
3. **G-B6c money (strict):** B6-4 (BudgetCalculator ×4, Monitor ×2), then B6-5.
4. **G-B6d money (strict):** B6-6 (currency), then B6-7 (groups/metrics).
5. **G-B6e privacy (strict + privacy guardian):** B6-8, then B6-10. Queued rows go here too: CSV TAB/CR/full-width and RawNotificationDao purge (confirm ownership of the RawNotificationDaoTest count difference first).
6. **G-B6f diagnostics privacy (strict + privacy guardian):** B6-12, plus the DiagnosticSideEffectEventWriter free-text reason/error row.
7. **G-B6g lifecycle (strict):** B6-16, then B6-19, then B6-26.
8. **G-B6h backup (strict):** B6-21.
9. **G-B6i money parity (strict):** B6-25.
10. **G-B6j small adds:**
    - quick-approve rows plus the DO_NOT_STORE success test;
    - RecommendationCacheService OOM row;
    - CompositeGeocoding Disabled paths (privacy);
    - ReceiptParser item > total (money);
    - processBatch onProgress and URI de-dup;
    - the B5 leftover: the MerchantCleanerTest:39 NBSP escape.
    - The 9-field seed-row coverage is optional because it adds items.
11. After integration: the WAIT slices, B6-23 after B4-K5, and the B5/B1R deferred items.
