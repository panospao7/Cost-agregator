# Engine 1 — PR9 Slice Completion Report

## Slice: PR9 — Deprecated/raw API guardrails

### Self-review verdict
GREEN

### Old issues reconciled
- W01: FIXED — getTotalProtectedValue() now has explicit DeprecationLevel.WARNING + architecture guard
- W06: FIXED — getTotalMonthlySubscriptionCost() and calculatePotentialSavings() now have explicit DeprecationLevel.WARNING + architecture guard
- W29: FIXED — AreaSpendingEngine.compute() and TravelDetectionEngine.compute() now have explicit DeprecationLevel.WARNING + architecture guard with AnalyticsViewModel allowlist

### New issues found during review
- WarrantyDao.getTotalProtectedValue() lacked explicit level — FIXED
- AnalyticsViewModel allowlist is filename-dependent — documented in guard test KDoc

### Files changed (production)
1. `app/src/main/java/com/yourname/expensetracker/data/repository/WarrantyTrackerRepository.kt`
2. `app/src/main/java/com/yourname/expensetracker/domain/subscription/SubscriptionManagerEngine.kt`
3. `app/src/main/java/com/yourname/expensetracker/domain/location/AreaSpendingEngine.kt`
4. `app/src/main/java/com/yourname/expensetracker/domain/location/TravelDetectionEngine.kt`
5. `app/src/main/java/com/yourname/expensetracker/data/database/dao/WarrantyDao.kt`

### Files changed (tests)
6. `app/src/test/java/com/yourname/expensetracker/architecture/DeprecatedApiArchitectureGuardTest.kt` (NEW)

### Tests added/updated
- noProductionCallToGetTotalProtectedValue
- noProductionCallToGetTotalMonthlySubscriptionCost
- noProductionCallToCalculatePotentialSavings
- areaSpendingCompute_onlyCalledFromAnalyticsViewModel
- travelDetectionCompute_onlyCalledFromAnalyticsViewModel

### Docs updated
- `docs/analyses and debug master/ENGINE_ISSUES_MASTER_TRACKER.md` — W01/W06/W29 rows updated, PR9 completion note added
- `docs/analyses and debug master/engine 1/engine1-pr9-completion-report.md` (this file)

### Affected pipelines
- Warranty dashboard (no impact — no active call sites)
- Subscription dashboard (no impact — no active call sites)
- Analytics view (no impact — existing call sites allowlisted)
- Build integrity (new guard test fails build if new deprecated call sites added)

### Expected behavior changes
- No runtime behavior change
- Developers see clearer deprecation warnings with explicit level
- Build fails if anyone adds NEW production calls to deprecated raw-Double APIs
- AnalyticsViewModel's existing deprecated call sites are preserved via allowlist

### Static debugger verdict
GREEN

### Reviewer verdict
GREEN (after fixes: WarrantyDao explicit level added, allowlist documented)

### Tester static verdict
GREEN (5 guard tests, all deprecated APIs covered, no weak assertions)

### Known compile risks
None. No schema changes, no Hilt changes, no runtime behavior changes.

### Human validation commands
```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:testDebugUnitTest --tests "*DeprecatedApiArchitectureGuardTest*"
./gradlew :app:check --stacktrace
```

### Follow-up / deferred items
- Migrate AnalyticsViewModel.kt to use computeNormalized() instead of deprecated compute() — then escalate to DeprecationLevel.ERROR
- Migrate WarrantyTrackerViewModelTest mock from getTotalProtectedValue() to getTotalProtectedValueAggregate()
- Deprecate BillReminderManager.getMonthlyBillsTotal() (raw Double cross-currency aggregate) and provide MoneyAggregate alternative
- Consider adding round-trip guard test verifying ReplaceWith targets compile
