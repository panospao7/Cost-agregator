# Expense Tracker - Codebase Health Analysis Report

**Analysis Date:** February 18, 2026  
**Scope:** `/app/src/main/java`  
**Total Issues Found:** 47

---

## Table of Contents

1. [Duplications](#1-duplications---code-that-should-be-centralized)
2. [Bad Logic](#2-bad-logic---incorrect-algorithms-or-flows)
3. [Insufficiencies](#3-insufficiencies---missing-validations-error-handling)
4. [Bad Optimizations](#4-bad-optimizations---performance-anti-patterns)
5. [Architecture Issues](#5-architecture-issues---layer-violations-god-objects)
6. [Functionality Overlaps](#6-functionality-overlaps---duplicate-features)
7. [Dead Code](#7-dead-code---unused-classes-functions-models)
8. [Security Concerns](#8-security-concerns---sql-injection-data-exposure)
9. [Memory Leaks](#9-memory-leaks---coroutine-scope-issues-listener-cleanup)
10. [Recommendations](#10-recommendations)

---

## 1. DUPLICATIONS - Code that should be centralized

| # | File | Line(s) | Issue | Suggested Fix |
|---|------|---------|-------|---------------|
| 1.1 | `MainActivity.kt` | 231-242 | **Duplicate clipboard parsing logic** - Same regex `(\d{1,6}[.,]\d{2})` is duplicated in both `MainScreen` and `SmartFAB` composables | Extract to shared utility function |
| 1.2 | `MainActivity.kt` | 289-312 | Same clipboard parsing code as lines 231-242 | Use shared utility |
| 1.3 | `InsightsEngine.kt` | 186-202 | **Duplicate MonthPeriod computation** - `getMonthPeriod()` logic is repeated, and there's an off-by-one issue with `range.second + 1` when `TimePeriodUtils` returns inclusive end | Centralize period calculation |
| 1.4 | `InsightsEngine.kt` | 623-635 | **Duplicate pace calculation** - `getSpendingPaceSuspend()` replicates logic from `buildSpendingPace()` | Use single implementation |
| 1.5 | `ExpenseRepository.kt` | 71-72 | **Redundant wrapper method** - `getCountForPeriod()` just wraps `getExpenseCountForPeriod()` with identical logic | Remove wrapper |
| 1.6 | `ExpenseDao.kt` | 100-113 | **Duplicate query definition** - `getCategorySpentInPeriod()` defined as both suspend and Flow versions | Consolidate |
| 1.7 | `GenericTransactionParser.kt` | 6-9, 15-19 | **Duplicate KDoc comments** - Identical doc comment appears twice | Remove duplicate |

---

## 2. BAD LOGIC - Incorrect algorithms or flows

| # | File | Line(s) | Issue | Impact |
|---|------|---------|-------|--------|
| 2.1 | `InsightsEngine.kt` | 196 | **Off-by-one error**: `range.second + 1` applied to `TimePeriodUtils.getMonthRange()` which already returns inclusive end. This creates a month boundary mismatch | Incorrect analytics period calculations |
| 2.2 | `InsightsEngine.kt` | 385-392 | **Projection instability**: Days 1-3 use aggressive multipliers (`daysInMonth.toDouble() / 10.0`) causing wild spending projections | Unstable budget predictions on month start |
| 2.3 | `InsightsEngine.kt` | 272-277 | **Division by zero risk**: `prev.total` could be 0 causing NaN in percentage calculation `((ct.total - prev.total) / prev.total * 100)` | Potential crashes or NaN displayed to user |
| 2.4 | `InsightsEngine.kt` | 386-389 | **Conservative estimate inconsistent**: Logic says "conservative" but multiplies by at least `daysInMonth/10`, which is actually aggressive for day 1 | Misleading projections |
| 2.5 | `AddExpenseViewModel.kt` | 193-195 | **Precision loss**: Using `Double` for currency instead of `Long` (cents). `BigDecimal` conversion loses precision: `amount.toDouble()` then back | Rounding errors in financial data |
| 2.6 | `InsightsEngine.kt` | 347-351 | **Day 1 handling logic is inverted**: Code checks `dayOfMonth == 1` but should probably handle `dayOfMonth <= 3` | Incorrect projections for first few days |

---

## 3. INSUFFICIENCIES - Missing validations, error handling

| # | File | Line(s) | Issue | Suggested Fix |
|---|------|---------|-------|---------------|
| 3.1 | `MainActivity.kt` | 97-99 | **Empty permission result handler** - `ActivityResultContracts.RequestPermission()` result is completely ignored. No handling for denied/grant scenarios | Add result callback handling |
| 3.2 | `NotificationCaptureService.kt` | 262-267 | **Silent failure**: Exceptions caught but only logged with `Log.e()`, no user notification or fallback mechanism | Add retry logic or user alert |
| 3.3 | `NotificationRepository.kt` | 111-114 | **Incomplete large amount handling**: Logs warning but continues processing with `NEEDS_REVIEW` instead of blocking | Add amount threshold validation |
| 3.4 | `HomeViewViewModel.kt` | 272-276 | **Null safety issue**: `overallBudget?.budget?.amount ?: 0.0` may hide missing budget configuration, treating no budget as zero budget | Return null or warn user |
| 3.5 | `ExpenseDao.kt` | 75-98 | **Duplicate check inefficiency**: Complex SQL query with 5 matching strategies may still miss edge cases (unicode variations, spacing) | Add fuzzy matching |
| 3.6 | `ReceiptOcrService.kt` | 340 | **Timeout may be insufficient**: 15 second timeout for PDF OCR on low-end devices | Make timeout configurable |
| 3.7 | `NotificationCaptureService.kt` | 171-204 | **No rate limiting**: Notifications processed immediately without throttling, could overwhelm system during notification storms | Add rate limiter |

---

## 4. BAD OPTIMIZATIONS - Performance anti-patterns

| # | File | Line(s) | Issue | Impact |
|---|------|---------|-------|--------|
| 4.1 | `HomeViewModel.kt` | 135-230 | **Excessive flow combination**: 8+ flows combined with `debounce(300)` causing cascade recomputations | UI jank, battery drain |
| 4.2 | `HomeViewModel.kt` | 233-435 | **Heavy computation on Default dispatcher**: Large analytics calculations (category totals, pace, projections) in single `.map` block | CPU spikes |
| 4.3 | `InsightsEngine.kt` | 67-74 | **Redundant list filtering**: Filters `currentMonthPurchases` then computes average and median in separate passes | Double iteration |
| 4.4 | `ExpenseRepository.kt` | 35-40 | **shareIn with replay=1**: Causes unnecessary emission on collector start even when data unchanged | Extra computations |
| 4.5 | `CategorizationEngine.kt` | 35-43 | **O(n*m) substring matching**: For each category, iterates through all mappings. Should use HashMap lookup | Slow categorization |
| 4.6 | `HomeViewModel.kt` | 283-312 | **Duplicate forecast calculation**: `insightsEngine.getSpendingPaceSuspend()` called even though pace computed elsewhere | Redundant DB queries |
| 4.7 | `InsightsEngine.kt` | 41-64 | **Parallel async with awaitAll**: Starts 8+ deferred operations but many are redundant queries for overlapping data | Resource contention |

---

## 5. ARCHITECTURE ISSUES - Layer violations, god objects

| # | File | Line(s) | Issue | Severity |
|---|------|---------|-------|----------|
| 5.1 | `MainActivity.kt` | 80-269 | **God Composable**: 190-line `MainScreen` handles navigation, permissions, clipboard monitoring, FAB state, multiple dialogs, and all routing logic | 🔴 High |
| 5.2 | `HomeViewModel.kt` | 106-435 | **God ViewModel**: 330 lines, injects 10+ repositories, handles 8 separate data flows, contains business logic for budgets, weather, analytics | 🔴 High |
| 5.3 | `NotificationRepository.kt` | 23-303 | **God Repository**: 280 lines mixing notification capture, ML classification, merchant normalization, routing decisions, budget checking, and all DB operations | 🔴 High |
| 5.4 | `InsightsEngine.kt` | 1-644 | **Leaky abstraction**: Directly calls DAO through repository, mixes domain analytics with data layer queries. Should only use repository interfaces | 🔴 High |
| 5.5 | `AppDatabase.kt` | 47-413 | **Migration bloat**: 14+ migrations in single file (MIGRATION_6_7 through MIGRATION_19_20). Should be versioned migration classes | 🔶 Medium |
| 5.6 | `MainViewModel.kt` | 18 | **Context leak risk**: Stores `ApplicationContext` as property but never uses it for anything but `isNotificationServiceEnabled()` | 🟡 Low |
| 5.7 | `ReceiptOcrService.kt` | 42-52 | **Service as singleton**: Heavy ML Kit client held in singleton, should be scoped and released | 🔶 Medium |

---

## 6. FUNCTIONALITY OVERLAPS - Duplicate features

| # | Files | Issue |
|---|-------|-------|
| 6.1 | `InsightsEngine.kt` ↔ `AdvancedAnalyticsEngine.kt` | **Duplicate analytics logic** - Both compute monthly comparisons, category insights, spending pace. AdvancedAnalyticsEngine adds clustering but overlaps significantly |
| 6.2 | `RecurringExpenseEngine.kt` ↔ `FinancialWeatherRepository.kt` | **Duplicate recurring detection** - Different implementations for detecting recurring expenses. Should have single source of truth |
| 6.3 | `CategorizationEngine.kt` ↔ `MerchantNormalizer.kt` | **Duplicate normalization** - Both provide merchant name normalization. CategorizationEngine delegates to MerchantNormalizer but wraps it unnecessarily |
| 6.4 | `ExpenseRepository.kt` ↔ `AnalyticsRepository.kt` | **Query method overloads** - Many suspend/Flow pairs for same queries. Unclear which to use |
| 6.5 | `NotificationCaptureService.kt` ↔ `NotificationRepository.kt` | **Overlapping responsibilities** - Service captures, Repository processes. Boundary unclear |

---

## 7. DEAD CODE - Unused classes, functions, models

| # | File | Line(s) | Issue |
|---|------|---------|-------|
| 7.1 | `AppDatabase.kt` | 30 | **Placeholder comment**: `"// ... (DAOs)"` - suggests incomplete cleanup after refactoring |
| 7.2 | `ExpenseDao.kt` | 53-55 | **Deprecated method**: `getAll()` marked `@Deprecated` but never removed |
| 7.3 | `InsightsEngine.kt` | 591-593 | **Commented legacy code**: References to `detectRecurring` removed but comments remain |
| 7.4 | `NotificationRepository.kt` | 279 | **Commented code**: `# merchantCategoryDao.deleteAll() // Removed as part of refactoring` - dead code |
| 7.5 | Multiple files | - | **Unused imports**: Throughout codebase (run detekt/ktlint to find all) |
| 7.6 | `ExpenseRepository.kt` | 68-72 | **Duplicate suspend method**: `getExpenseCountForPeriod()` and `getCountForPeriod()` identical |

---

## 8. SECURITY CONCERNS - SQL injection, data exposure

| # | File | Line(s) | Issue | Risk Level |
|---|------|---------|-------|------------|
| 8.1 | `AppDatabase.kt` | - | **No database encryption**: Room database stores financial data unencrypted. Physical device compromise exposes all data | 🔴 High |
| 8.2 | `NotificationRepository.kt` | 112 | **PII in logs**: `Log.w("NotificationRepo", "Auto-accept suppressed due to large amount (validation limit)")` logs amount - potential data exposure | 🔶 Medium |
| 8.3 | `NotificationCaptureService.kt` | 298-303 | **Incomplete sensitive data filtering**: Only filters exact key matches (`account_number`, `card_number`). Doesn't handle `account_number_masked`, partial card numbers | 🔶 Medium |
| 8.4 | `ExpenseDao.kt` | 117-124 | **Complex SQL with multiple LIKE**: While parameterized, the 5 different matching strategies could match unintended data | 🟡 Low |
| 8.5 | `ReceiptOcrService.kt` | 446-458 | **Receipts saved unencrypted**: Scanned receipts stored in `filesDir/receipts/` without encryption. Could be extracted from device | 🔶 Medium |
| 8.6 | `MainActivity.kt` | 232-241 | **Clipboard reading**: App reads clipboard content automatically. Could capture sensitive data user copied | 🔶 Medium |

---

## 9. MEMORY LEAKS - Coroutine scope issues, listener cleanup

| # | File | Line(s) | Issue | Status |
|---|------|---------|-------|--------|
| 9.1 | `ExpenseRepository.kt` | 32 | **Custom scope never cancelled**: `CoroutineScope(SupervisorJob() + Dispatchers.IO)` created but never cancelled. Repository is singleton, scope lives forever | ⚠️ Risk |
| 9.2 | `ReceiptOcrService.kt` | 52 | **ML Kit client not closed**: `TextRecognition.getClient()` returns reusable client but never explicitly closed | ⚠️ Risk |
| 9.3 | `MainActivity.kt` | 315-340 | **Clipboard listener cleanup**: ✅ Properly uses `DisposableEffect` with `onDispose` | ✅ OK |
| 9.4 | `MainActivity.kt` | 330-340 | **Lifecycle observer cleanup**: ✅ Properly removes observer in `onDispose` | ✅ OK |
| 9.5 | `NotificationCaptureService.kt` | 35-36 | **Service scope**: ✅ Uses `SupervisorJob` but properly cancelled in `onDestroy()` | ✅ OK |
| 9.6 | `HomeViewModel.kt` | 230 | **Debounced flow holds references**: `debounce(300)` could hold references to large expense lists during emission bursts | ⚠️ Risk |

---

## 10. RECOMMENDATIONS

### Priority 1 - Critical (Fix Immediately)

1. **Encrypt the database** - Use SQLCipher or Android's EncryptedSharedPreferences
2. **Remove god objects** - Break down MainActivity, HomeViewModel, NotificationRepository
3. **Fix currency precision** - Use Long (cents) instead of Double

### Priority 2 - High (Plan for Next Sprint)

4. **Consolidate duplicate logic** - Merge recurring expense detection, analytics engines
5. **Add proper error handling** - Handle permission denied, network failures gracefully
6. **Remove dead code** - Clean up deprecated methods, comments

### Priority 3 - Medium (Technical Debt)

7. **Optimize flow combinations** - Break up heavy computations, add caching
8. off **Fix-by-one errors** - Validate date range calculations
9. **Add rate limiting** - Throttle notification processing
10. **Version migrations** - Extract migrations to separate classes

---

## File Statistics

| Metric | Value |
|--------|-------|
| Total Kotlin Files | ~100 |
| Total Lines of Code (main) | ~15,000 |
| Repository Classes | 15 |
| ViewModel Classes | 10 |
| DAO Interfaces | 12 |
| Entity Classes | 16 |

---

*Generated by OpenCode Analysis*
