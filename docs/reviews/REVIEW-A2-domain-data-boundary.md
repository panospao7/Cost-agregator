# Review Report — A.2: Domain/Data Layer Boundary Violations

## Summary
- **Epic:** A.2 Domain/Data Layer Boundary Violations
- **Files Reviewed:** 20 production files
- **Verdict:** ❌ FAIL

## Boundary Violation Audit
- **Targeted import grep:** PASS for the 11 requested A.2 target files. The reviewed DTO/model/service files no longer use `import data.database.entity...` or `import data.repository...`, and `NarrativeGenerator.kt` no longer imports `R`.
- **Blocking exception:** `app/src/main/java/com/yourname/expensetracker/domain/model/dashboard/DashboardExpenseMapper.kt` still contains a deprecated `toEntityExpense()` that returns `data.database.entity.Expense` via fully-qualified references. This bypasses the import grep but still violates the boundary rule.
- **Active reverse-mapping callers still exist:**
  - `app/src/main/java/com/yourname/expensetracker/domain/analytics/InsightsEngine.kt`
  - `app/src/main/java/com/yourname/expensetracker/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`
- **AiArtifact leak still present downstream:** grep found ongoing `AiArtifactEntity` usage in domain/UI callers, including `SuggestReceiptExtractionUseCase.kt`, `CategorizeReceiptItemsUseCase.kt`, `JudgePendingReviewDuplicateUseCase.kt`, `GenerateDashboardBriefingUseCase.kt`, `ExplainPendingReviewUseCase.kt`, `SuggestCategoryFallbackUseCase.kt`, `GenerateTransactionInsightUseCase.kt`, `DashboardFollowThroughEngine.kt`, and `ReviewViewModel.kt`.

## DTO Quality Audit
- **Good:** new DTOs are Room-annotation free.
- **Good:** `CategoryRef`, `ReviewPriorityInput`, `ReceiptItemCategorizationSnapshot`, and `AiArtifactRecord` are appropriately domain-owned.
- **Good:** `TransactionSummary` no longer exposes `Expense`.
- **Not complete:** DTO adoption is only partial. Key builders/providers still pass old entities/types instead of mapping at the boundary:
  - `ReceiptItemCategorizationInputBuilder.kt` still passes `List<Category>`
  - `OnDeviceReceiptItemCategorizationService.kt` still expects `List<Category>`
  - `OnDeviceReviewPriorityScorer.kt` still passes `PendingReview`
  - `CategorizeReceiptItemsUseCase.kt` still returns `List<ReceiptItemCategorization>` on the cached path

## Mapper Audit
- **Good:** `AiArtifactRepositoryImpl.kt` contains private `AiArtifactEntity` ↔ `AiArtifactRecord` mappers, correctly kept in the data layer.
- **Fail:** `DashboardExpenseMapper.kt` still exposes the deprecated `toEntityExpense()` path in the domain package.
- **Fail:** block-party/UI mapping still fabricates `Expense` in `DashboardWidgetUiMapper.kt`, which reintroduces the same lossy reverse mapping the epic was meant to remove.

## Enum Audit
- **Good:** `DomainTransactionType` matches the data-layer enum values (`PURCHASE`, `WITHDRAWAL`, `TRANSFER`, `DEPOSIT`, `UNKNOWN`).
- **Good:** `DomainOwnershipFilter` matches the repository enum values (`ALL`, `MINE`, `NOT_MINE`, `SHARED`, `TRANSFER`).
- **Fail:** usage is not consistent across downstream A.2 flows. Remaining old-type usage exists in:
  - `MapFinancialQueryToNavigationUseCase.kt`
  - `ExecuteFinancialQueryUseCase.kt`
  - `TransactionFilterSerializer.kt`
  - `TransactionFilterUiMapper.kt`
  - `DashboardFollowThroughEngine.kt`
  - `CrossSourceDeduplication.kt`
  - `CategorizationAssistInputBuilder.kt`

## Constraint Verification
- **Room entities / schemas / migrations changed:** **No**. `git diff` shows no A.2 changes under `app/src/main/java/com/yourname/expensetracker/data/database` or `app/schemas`.
- **`Expense` / `effectiveAmount` changed:** **No**. `Expense.kt` is unchanged.
- **Public repository API signatures changed without safe rollout:** **Yes, effectively.** `AiArtifactRepository` now speaks `AiArtifactRecord`, but many callers were not migrated in the same pass, leaving the app uncompilable.

## Regression Check
- **AiArtifact regression:** still unresolved. Downstream files still import/use `AiArtifactEntity` and pass it to the repository.
- **Old transaction enum regression:** still unresolved. Several A.2-adjacent files still use the data-layer `TransactionType` where the new domain enum should be used.
- **`BlockPartyDay.topTransactions` regression:** broken. `BlockPartyDay` now expects `List<TransactionSummary>`, but `SynthesisEngine.kt` still passes `List<Expense>`, which fails compilation.
- **UI block-party regression:** still unresolved. `BudgetBlockPartyCard.kt` and `DashboardWidgetUiMapper.kt` still depend on synthetic `Expense` previews, and `ComputeDashboardWidgetsUseCase.kt` still writes `categoryId?.toString()` into `DomainExpenseSummary.categoryName`.

## Issues Found
| # | Severity | File | Description | Remedy |
|---|----------|------|-------------|--------|
| 1 | CRITICAL | `domain/model/dashboard/DashboardExpenseMapper.kt`, `domain/analytics/InsightsEngine.kt`, `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` | The forbidden `DashboardExpense -> Expense` reverse mapping still exists and is still actively used. This preserves the original boundary violation and keeps the lossy reconstruction path alive. | Remove `toEntityExpense()` from the domain package entirely, add dashboard-safe pace/block-party inputs, and migrate all callers to `DashboardExpense` / `TransactionSummary` / `DomainExpenseSummary`. |
| 2 | CRITICAL | `domain/ai/usecase/SuggestReceiptExtractionUseCase.kt`, `CategorizeReceiptItemsUseCase.kt`, `JudgePendingReviewDuplicateUseCase.kt`, `GenerateDashboardBriefingUseCase.kt`, `ExplainPendingReviewUseCase.kt`, `SuggestCategoryFallbackUseCase.kt`, `GenerateTransactionInsightUseCase.kt`, `domain/engine/DashboardFollowThroughEngine.kt`, `ui/screens/review/ReviewViewModel.kt` | `AiArtifactRepository` was migrated to `AiArtifactRecord`, but many callers still construct and pass `AiArtifactEntity`. This both preserves domain/data leakage and breaks compilation. | Migrate every domain/UI caller to `AiArtifactRecord` in the same pass; if a temporary shim is needed, keep it in data/adapters, not in the domain contract. |
| 3 | CRITICAL | `domain/logic/SynthesisEngine.kt`, `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`, `ui/mappers/DashboardWidgetUiMapper.kt`, `ui/components/BudgetBlockPartyCard.kt` | The block-party pipeline is only half-migrated: producer code still emits `Expense`, while UI code still fabricates/consumes `Expense`. The new `TransactionSummary`/preview path is not wired end-to-end. | Convert `SynthesisEngine` to emit `TransactionSummary`, map to a dedicated UI preview model, and remove all synthetic `Expense` usage from widget/UI code. |
| 4 | MAJOR | `domain/ai/usecase/MapFinancialQueryToNavigationUseCase.kt`, `domain/ai/usecase/ExecuteFinancialQueryUseCase.kt`, `service/TransactionFilterSerializer.kt`, `ui/mappers/TransactionFilterUiMapper.kt`, `domain/engine/DashboardFollowThroughEngine.kt`, `domain/intelligence/CrossSourceDeduplication.kt` | The new domain enums were created, but downstream navigation/query/filter code still uses `TransactionType` / `OwnershipFilter`. This leaves the migration inconsistent and causes multiple compile failures. | Add explicit boundary mappers between domain enums and repository enums, then keep `DomainTransactionFilter` and AI/query models domain-owned end-to-end. |
| 5 | MAJOR | `domain/ai/usecase/ReceiptItemCategorizationInputBuilder.kt`, `data/ai/provider/OnDeviceReceiptItemCategorizationService.kt`, `data/ai/provider/OnDeviceReviewPriorityScorer.kt`, `domain/ai/usecase/CategorizeReceiptItemsUseCase.kt`, `domain/ai/usecase/CategorizationAssistInputBuilder.kt` | Category/review/cached-categorization DTO migration is incomplete. Builders/providers still consume `Category`, `PendingReview`, `ReceiptItemCategorization`, or old transaction enums directly instead of mapping at the boundary. | Map `Category -> CategoryRef`, `PendingReview -> ReviewPriorityInput`, `ReceiptItemCategorization -> ReceiptItemCategorizationSnapshot`, and `TransactionType -> DomainTransactionType` before crossing into domain models/services. |
| 6 | MAJOR | `domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` | `DomainExpenseSummary.categoryName` is still being populated with `categoryId?.toString()`, which the plan explicitly prohibited and which keeps display semantics wrong after the model migration. | Pass a real category label if available, or keep it null; do not encode IDs into a name field. |

## Remedy Plan (if issues found)
1. **Fix dashboard/block-party boundary first**
   - Update `SynthesisEngine.kt`, `InsightsEngine.kt`, and `ComputeDashboardWidgetsUseCase.kt` together.
   - Remove all `toEntityExpense()` usage.
   - Delete `toEntityExpense()` from `DashboardExpenseMapper.kt` once callers are gone.
2. **Fix AI artifact DTO rollout next**
   - Update each AI use case/viewmodel still creating `AiArtifactEntity`.
   - Keep `AiArtifactEntity` knowledge inside `AiArtifactRepositoryImpl.kt` only.
3. **Fix enum/filter rollout**
   - Update `MapFinancialQueryToNavigationUseCase.kt`, `ExecuteFinancialQueryUseCase.kt`, `TransactionFilterSerializer.kt`, `TransactionFilterUiMapper.kt`, `DashboardFollowThroughEngine.kt`, and `CrossSourceDeduplication.kt` to use domain enums internally and map only at repository/UI boundaries.
4. **Fix category/review/cached-item DTO adoption**
   - Update receipt/review builders and on-device providers to consume `CategoryRef`, `ReviewPriorityInput`, and `ReceiptItemCategorizationSnapshot`.
5. **Re-run verification**
   - Minimum gate: `:app:compileDebugKotlin` must pass before re-review.
   - Then run `:app:testDebugUnitTest` and targeted A.2 tests from the plan.

## Conclusion
The epic has **good foundational pieces**: the new DTOs/enums exist, `NarrativeGenerator` no longer imports `R`, and `AiArtifactRepositoryImpl` correctly owns entity↔DTO mapping. However, the implementation is **not complete and not releasable**. The dashboard reverse-mapping path, AI artifact migration, enum/filter rollout, and receipt/review DTO adoption are all only partially migrated, and the app currently fails `:app:compileDebugKotlin` with numerous type mismatches. A.2 should remain open until the end-to-end migrations are completed and the build is green.

---

## Re-Review (After All 6 Fixes)

### Issue Status
| Issue | Status | Notes |
|-------|--------|-------|
| ISSUE-1 | ✅ RESOLVED | `toEntityExpense()` is gone; `ComputeDashboardWidgetsUseCase` now uses `toTransactionSummary()`. |
| ISSUE-2 | ❌ STILL PRESENT | `JudgePendingReviewDuplicateUseCase.kt`, `SuggestCategoryFallbackUseCase.kt`, and `GenerateTransactionInsightUseCase.kt` still construct `AiArtifactEntity`, and `ReviewViewModel.kt` still imports it. |
| ISSUE-3 | ❌ STILL PRESENT | `ComputeDashboardWidgetsUseCase.computeBlockParty()` still passes `emptyList()` into `SynthesisEngine.calculateBlockPartyData(...)` instead of the prepared `TransactionSummary` list, so the block-party migration is not wired end-to-end. |
| ISSUE-4 | ❌ STILL PRESENT | Domain-enum cleanup is incomplete: `CategorizationAssistInputBuilder.kt` still uses data-layer `TransactionType`, and `ExecuteFinancialQueryUseCase.kt` still imports `R`, failing the zero-resource-import grep. |
| ISSUE-5 | ❌ STILL PRESENT | `CategorizeReceiptItemsUseCase.kt` still imports `ReceiptItemCategorization` directly in the domain layer and performs entity→snapshot mapping there instead of behind a repository/adapter boundary. |
| ISSUE-6 | ✅ RESOLVED | `ComputeDashboardWidgetsUseCase` now resolves `categoryName` through a category lookup map instead of writing `categoryId?.toString()`. |

### Updated Verdict: ❌ FAIL
The original fixes are only partially complete. While ISSUE-1 and ISSUE-6 are fixed, the AI artifact migration is still incomplete, the block-party `TransactionSummary` pipeline is still short-circuited with placeholder `emptyList()` calls, domain enum/resource cleanup is still inconsistent, and a boundary leak remains in cached receipt-item categorization handling. Re-verification also failed the minimum compile gate: `:app:compileDebugKotlin` still fails (KSP reports unresolved `TimeProvider` in `ComputeDashboardWidgetsUseCase`), so A.2 cannot be considered complete.

---

## Final Re-Review (After All Fixes)

### Issue Status
| Issue | Status | Notes |
|-------|--------|-------|
| ISSUE-1 | ✅ RESOLVED | `toEntityExpense()` is removed; callers now use `toTransactionSummary()`, and no active call sites remain. |
| ISSUE-2 | ✅ RESOLVED | The requested callers now use `AiArtifactRecord`; domain grep found zero `import ...AiArtifactEntity` usages. |
| ISSUE-3 | ✅ RESOLVED | `ComputeDashboardWidgetsUseCase.computeBlockParty()` now passes `ctx.expenseEntities`, and `SynthesisEngine`/UI consume `TransactionSummary` instead of `Expense`. |
| ISSUE-4 | ❌ STILL PRESENT | The specifically cited files are fixed, but the cleanup is not consistent overall: domain grep still finds `TransactionType` imports (for example `InterpretFinancialQueryUseCase.kt`), and domain grep still finds `R` imports (for example `DeliverProactiveBriefingNotificationUseCase.kt`). |
| ISSUE-5 | ✅ RESOLVED | Receipt-item entity→snapshot mapping now lives in `ReceiptItemCategorizationRepository`; the use case reads cached results via `getByReceiptIdAsSnapshots()`. |
| ISSUE-6 | ✅ RESOLVED | `categoryName` is resolved from a category lookup map in `ComputeDashboardWidgetsUseCase`, not from `categoryId?.toString()`. |
| ISSUE-7 | ❌ STILL PRESENT | `TimeProvider` is imported, but the compile gate still fails. `:app:compileDebugKotlin` reports unresolved imports in `ComputeDashboardWidgetsUseCase`, a `TransactionType`/`DomainTransactionType` mismatch in `InterpretFinancialQueryUseCase`, and a snapshot/entity mismatch in `ReceiptScanViewModel`. |

### Updated Verdict: ❌ FAIL
The requested A.2 fixes are only partially complete. The original boundary violations around `toEntityExpense()`, `AiArtifactEntity` callers, block-party wiring, receipt-item snapshot mapping, and category-name lookup are fixed. However, the final verification gate still fails because the domain enum/resource cleanup is not complete (`TransactionType` and `R` imports remain in domain code), and the app does not compile cleanly. `:app:compileDebugKotlin` currently fails with concrete errors in `InterpretFinancialQueryUseCase.kt`, `ComputeDashboardWidgetsUseCase.kt`, and `ReceiptScanViewModel.kt`, so this review cannot be promoted to PASS.

---

## FINAL VERIFICATION (After All 7 Fixes)

### Grep Results
| Pattern | Count | Status |
|---------|-------|--------|
| `import data.database.entity.AiArtifactEntity` in domain/ | 0 | ✅ |
| `import data.database.entity.ReceiptItemCategorization` in domain/ | 0 | ✅ |
| `import data.database.entity.TransactionType` in domain/ | 19 | ❌ |
| `import com.yourname.expensetracker.R` in domain/ | 6 | ❌ |

### Issue Status
| Issue | Status | Notes |
|-------|--------|-------|
| ISSUE-1 | ✅ | `DashboardExpenseMapper.kt` no longer defines `toEntityExpense()`; only a KDoc reference remains. |
| ISSUE-2 | ✅ | Domain/UI callers are on `AiArtifactRecord`; grep found zero `AiArtifactEntity` imports in `domain/` and `ui/`. |
| ISSUE-3 | ✅ | `ComputeDashboardWidgetsUseCase.computeBlockParty()` now passes `ctx.expenseEntities`; `SynthesisEngine` consumes `List<TransactionSummary>` and the block-party UI path no longer uses `Expense` or placeholder `emptyList()`. |
| ISSUE-4 | ❌ | Domain-wide cleanup is still incomplete: 19 domain files still import data-layer `TransactionType`, and 6 domain files still import `R`. |
| ISSUE-5 | ✅ | Receipt-item entity mapping now lives in `ReceiptItemCategorizationRepository`; `CategorizeReceiptItemsUseCase` reads cached results via `getByReceiptIdAsSnapshots()`. |
| ISSUE-6 | ✅ | `ComputeDashboardWidgetsUseCase` resolves `categoryName` from a category lookup map, not from `categoryId?.toString()`. |
| ISSUE-7 | ❌ | `TimeProvider` is imported in `ComputeDashboardWidgetsUseCase`, but the compile gate still fails: `OnDeviceReviewPriorityScorer.kt` is missing the new `nowMs` argument and `ReceiptScanScreen.kt`/`ReceiptItemBreakdownCard.kt` still have snapshot-vs-entity type mismatches. |

### Updated Verdict: ❌ FAIL
Final verification is still failing. The original A.2 boundary fixes for dashboard reverse mapping, AI artifact DTO rollout, cached receipt-item mapping, and category-name lookup are in place, but the epic is not complete because domain-wide `TransactionType` and `R` imports remain and `:app:compileDebugKotlin` is still red. Separate note: `System.currentTimeMillis()` still appears 60 times in `domain/`, but those occurrences are A.3 scope rather than an A.2 blocker.

---

## FINAL VERIFICATION (After All 8 Fixes)

### Grep Results
| Pattern | Count | Status |
|---------|-------|--------|
| `import data.database.entity.AiArtifactEntity` in domain/ | 0 | ✅ |
| `import data.database.entity.ReceiptItemCategorization` in domain/ | 0 | ✅ |
| `import data.database.entity.TransactionType` in domain/ | 0 | ✅ |
| `import com.yourname.expensetracker.R` in domain/ | 0 | ✅ |
| `toEntityExpense` in domain/ | 1 | ❌ |

### Issue Status
| Issue | Status | Notes |
|-------|--------|-------|
| ISSUE-1 | ❌ | `DashboardExpenseMapper.kt` no longer defines `toEntityExpense()`, but the file still contains a KDoc reference to `toEntityExpense`, so the requested zero-match grep is not satisfied. |
| ISSUE-2 | ✅ | Domain/UI callers now use `AiArtifactRecord`; grep found zero `AiArtifactEntity` imports in `domain/` and `ui/`. |
| ISSUE-3 | ✅ | `ComputeDashboardWidgetsUseCase.computeBlockParty()` passes `ctx.expenseEntities`, and `SynthesisEngine.calculateBlockPartyData(...)` now takes `List<TransactionSummary>` instead of relying on `emptyList()`. |
| ISSUE-4 | ❌ | `R` imports are gone from `domain/`, but domain enum migration is still inconsistent. `:app:compileDebugKotlin` fails with many `TransactionType` vs `DomainTransactionType` comparisons in files such as `AdvancedAnalyticsEngine.kt`, `InsightsEngine.kt`, and `SpendingPaceCalculator.kt`. |
| ISSUE-5 | ✅ | Receipt-item entity mapping is now owned by `ReceiptItemCategorizationRepository`; cached reads return `ReceiptItemCategorizationSnapshot` via `getByReceiptIdAsSnapshots()`. |
| ISSUE-6 | ✅ | `ComputeDashboardWidgetsUseCase` resolves `categoryName` from a category lookup map instead of writing `categoryId?.toString()`. |
| ISSUE-7 | ✅ | `OnDeviceReviewPriorityScorer` now calls `ReviewPriorityFactors.fromReview(...)` with the required time argument. |
| ISSUE-8 | ✅ | `ReceiptScanViewModel`, `ReceiptScanScreen`, and `ReceiptItemBreakdownCard` now use `ReceiptItemCategorizationSnapshot` for the item breakdown flow. |

### Updated Verdict: ❌ FAIL
Most of the targeted A.2 fixes are now in place, but final verification still fails for two concrete reasons: (1) `toEntityExpense` still appears once in `domain/` via `DashboardExpenseMapper.kt` KDoc, so the requested zero-grep boundary check is not clean, and (2) the compile gate is still red. `:app:compileDebugKotlin` fails with widespread domain enum mismatches (`TransactionType` vs `DomainTransactionType`) across analytics/forecasting files, so the epic is not yet in a releasable PASS state.

---

## FINAL VERIFICATION (After All 8 Fixes — Pass 3)

### Grep Results
| Pattern | Count | Status |
|---------|-------|--------|
| `import data.database.entity.AiArtifactEntity` in domain/ | 0 | ✅ |
| `import data.database.entity.ReceiptItemCategorization` in domain/ | 0 | ✅ |
| `import data.database.entity.TransactionType` in domain/ | 0 | ✅ |
| `import com.yourname.expensetracker.R` in domain/ | 0 | ✅ |
| `toEntityExpense` in domain/ | 0 | ✅ |
| `transactionType == DomainTransactionType` in domain/ | 0 | ✅ |
| `transactionType != DomainTransactionType` in domain/ | 0 | ✅ |

### Issue Status
| Issue | Status | Notes |
|-------|--------|-------|
| ISSUE-1 | ✅ | `DashboardExpenseMapper.kt` now contains only `toTransactionSummary()`; `toEntityExpense()` and its KDoc/reference are gone. |
| ISSUE-2 | ✅ | Runtime callers are migrated to `AiArtifactRecord`; repo-wide grep found `AiArtifactEntity` only in the data layer plus stale KDoc mentions, not in domain/UI callers. |
| ISSUE-3 | ✅ | `ComputeDashboardWidgetsUseCase.computeBlockParty()` passes `ctx.expenseEntities`, `SynthesisEngine.calculateBlockPartyData(...)` consumes `List<TransactionSummary>`, and the block-party UI path no longer uses `Expense` or `emptyList()` placeholders. |
| ISSUE-4 | ❌ | The import greps are clean, but enum migration is still inconsistent: domain files still use fully-qualified data-layer `TransactionType` references and `:app:compileDebugKotlin` fails with incompatible enum comparisons in `RecurringIncomeTracker.kt:114-115` and `LifestyleInflationDetector.kt:42,45`. |
| ISSUE-5 | ✅ | Receipt-item mapping is now owned by `ReceiptItemCategorizationRepository`; cached reads return `ReceiptItemCategorizationSnapshot`, and persistence mapping also moved behind the repository boundary. |
| ISSUE-6 | ✅ | `ComputeDashboardWidgetsUseCase` resolves `DomainExpenseSummary.categoryName` from a category lookup map instead of writing `categoryId?.toString()`. |
| ISSUE-7 | ✅ | `OnDeviceReviewPriorityScorer` now passes an explicit `nowMs` argument to `ReviewPriorityFactors.fromReview(...)` on all call sites. |
| ISSUE-8 | ✅ | `ReceiptScanScreen`, `ReceiptScanViewModel`, and `ReceiptItemBreakdownCard` now use `ReceiptItemCategorizationSnapshot` for the receipt-item breakdown flow. |

### Updated Verdict: ❌ FAIL
All seven requested grep gates are now clean, and ISSUE-1/2/3/5/6/7/8 verify as fixed. However, A.2 still cannot be marked complete because ISSUE-4 remains unresolved: the domain layer still contains fully-qualified data-layer `TransactionType` usage, and the minimum compile gate is red. `:app:compileDebugKotlin` currently fails on incompatible `TransactionType` vs `DomainTransactionType` comparisons, so the epic is not yet in a releasable PASS state.
