# Expense Tracker Stabilization - QA Test Matrix

## Phase E: Testing & Verification

### Test Environment
- **App Version**: Post-Phase D (Database v51, Unified Navigation, Config-Driven Features)
- **Test Device**: Android Emulator / Physical Device
- **OS Version**: Android 12+ (API 31+)

---

## 1. Database Migration Tests (CRITICAL)

| Test Case | Steps | Expected Result | Status |
|-----------|-------|-----------------|--------|
| Fresh Install | Install app on clean device | App launches, DB v51 created, no crashes | ⬜ |
| Upgrade from v50 | Install app with DB v50, upgrade to v51 | Migration runs successfully, all data preserved | ⬜ |
| Schema Validation | Run migration tests | All 33 tables have matching schema between entities and migrations | ⬜ |
| Default Values | Check columns with @ColumnInfo(defaultValue) | Values match SQL migration defaults | ⬜ |

---

## 2. Navigation System Tests (CRITICAL)

### 2.1 Tab Navigation (0-5)
| Test Case | From | To | Expected | Status |
|-----------|------|-----|----------|--------|
| Tab 0 → Tab 1 | Home | Transactions | Switch successful, back stack cleared | ⬜ |
| Tab 1 → Tab 2 | Transactions | Review | Switch successful, transaction filter cleared | ⬜ |
| Tab 2 → Tab 3 | Review | Budget | Switch successful | ⬜ |
| Tab 3 → Tab 4 | Budget | Analytics | Switch successful | ⬜ |
| Tab 4 → Tab 5 | Analytics | Map | Switch successful | ⬜ |
| Tab 5 → Tab 0 | Map | Home | Switch successful | ⬜ |

### 2.2 Feature Navigation
| Test Case | Trigger | Expected Result | Status |
|-----------|---------|-----------------|--------|
| Home → Savings Goals | Tap Features → Savings Goals | Opens SavingsGoalsScreen, bottom bar hidden | ⬜ |
| Home → Carbon Footprint | Tap Features → Carbon Footprint | Opens CarbonFootprintScreen | ⬜ |
| Transactions → Analytics | Tap analytics CTA | Navigates to Analytics tab (4) | ⬜ |
| Analytics → Transactions | Tap transaction filter | Navigates to Transactions tab (1) with filter | ⬜ |
| Feature Back Button | Press back from SavingsGoals | Returns to Home tab, bottom bar visible | ⬜ |

### 2.3 Deep Links
| Test Case | Action | Expected Result | Status |
|-----------|--------|-----------------|--------|
| View Review from notification | Tap notification | Opens Review tab (2) | ⬜ |
| View Transactions with filter | External link with filter | Opens Transactions tab (1) with filter applied | ⬜ |

---

## 3. Features Menu Tests (22 Items)

| # | Feature | Destination | Expected Screen | Status |
|---|---------|-------------|-----------------|--------|
| 1 | Savings Goals | SavingsGoals | SavingsGoalsScreen | ⬜ |
| 2 | Carbon Footprint | CarbonFootprint | CarbonFootprintScreen | ⬜ |
| 3 | Warranty Tracker | WarrantyTracker | WarrantyTrackerScreen | ⬜ |
| 4 | Price Protection | PriceProtection | PriceProtectionScreen | ⬜ |
| 5 | Bill Negotiation | BillNegotiation | BillNegotiationScreen | ⬜ |
| 6 | Smart Search | SmartSearch | NaturalLanguageSearchScreen | ⬜ |
| 7 | Receipt Matching | ReceiptMatching | ReceiptMatchingScreen | ⬜ |
| 8 | Investment Portfolio | InvestmentPortfolio | InvestmentPortfolioScreen | ⬜ |
| 9 | Bank Connections | BankConnections | BankConnectionsScreen | ⬜ |
| 10 | Bill Reminders | BillReminders | BillRemindersScreen | ⬜ |
| 11 | Spending Challenges | SpendingChallenges | SpendingChallengesScreen | ⬜ |
| 12 | Advanced Analytics | AdvancedAnalytics | AdvancedAnalyticsScreen | ⬜ |
| 13 | Cash Flow Calendar | CashFlowCalendar | CashFlowCalendarScreen | ⬜ |
| 14 | Lifestyle Inflation | LifestyleInflation | LifestyleInflationScreen | ⬜ |
| 15 | Split Templates | SplitTemplates | SplitTemplatesScreen | ⬜ |
| 16 | Visual Split Editor | VisualSplitEditor | VisualSplitEditorScreen | ⬜ |
| 17 | Currency Management | CurrencyManagement | CurrencyManagementScreen | ⬜ |
| 18 | Subscription Management | SubscriptionManagement | SubscriptionManagementScreen | ⬜ |
| 19 | Tax Configuration | TaxConfiguration | TaxConfigurationScreen | ⬜ |
| 20 | Export Options | ExportOptions | ExportOptionsScreen | ⬜ |
| 21 | Manual Recurring | ManualRecurring | ManualRecurringExpenseScreen | ⬜ |
| 22 | Shared Groups | SharedGroups | SharedExpenseGroupsScreen | ⬜ |

**NEW Badge Verification:**
- [ ] Items 17-22 should show "NEW" badge in Features menu

---

## 4. Format String Tests

| Test Case | Location | Test Input | Expected Format | Status |
|-----------|----------|------------|-----------------|--------|
| Widget insight (lower) | ComputeDashboardWidgetsUseCase:498 | diff = 50.5 | "Spent 51 less than last month so far." | ⬜ |
| Widget insight (higher) | ComputeDashboardWidgetsUseCase:502 | diff = 75.3 | "Spending is 75 higher than last month." | ⬜ |
| Budget exceeded | Widget | 3 budgets | "3 budgets exceeded!" | ⬜ |
| Period labels | HomeViewModel | Various | Externalized strings used | ⬜ |

---

## 5. Internationalization Tests

| Screen | EN | EL | DE | Status |
|--------|----|----|----|--------|
| Currency Management | ✅ | ⬜ | ⬜ | ⬜ |
| Subscription Management | ✅ | ⬜ | ⬜ | ⬜ |
| Tax Configuration | ✅ | ⬜ | ⬜ | ⬜ |
| Export Options | ✅ | ⬜ | ⬜ | ⬜ |
| Recurring Expenses | ✅ | ⬜ | ⬜ | ⬜ |
| Shared Groups | ✅ | ⬜ | ⬜ | ⬜ |
| Investment Portfolio | ✅ | ⬜ | ⬜ | ⬜ |
| Bank Connections | ✅ | ⬜ | ⬜ | ⬜ |

---

## 6. UX/Edge Case Tests

| Test Case | Scenario | Expected Result | Status |
|-----------|----------|-----------------|--------|
| Mic Button | Tap mic in Smart Search | Shows "Coming soon" tooltip | ⬜ |
| Record Outcome | Tap "Record Outcome" in Bill Negotiation | Opens OutcomeRecordingDialog | ⬜ |
| Dead CTA - Investment | Tap Investment CTA from feature | Shows "Coming soon" behavior | ⬜ |
| Dead CTA - Bank | Tap Bank CTA from feature | Shows "Coming soon" behavior | ⬜ |
| Smart Search Result | Tap result in Smart Search | Navigates to Transactions tab | ⬜ |
| Features Menu Scroll | Scroll through 22 items | All items visible and tappable | ⬜ |

---

## 7. Build Verification

| Check | Command | Expected Result | Status |
|-------|---------|-----------------|--------|
| Kotlin Compile | `./gradlew :app:compileDebugKotlin` | BUILD SUCCESSFUL | ✅ |
| Resources Merge | `./gradlew :app:mergeDebugResources` | No duplicate resource errors | ✅ |
| Unit Tests | `./gradlew :app:testDebugUnitTest` | All tests pass | ⬜ |
| Migration Tests | `./gradlew :app:connectedCheck` | Migration tests pass | ⬜ |

---

## Test Results Summary

**Passed:** X / Y
**Failed:** Z
**Pending:** W

**Critical Issues:**
- [ ] Issue 1
- [ ] Issue 2

**Notes:**
- All database migrations tested on clean install and upgrade scenarios
- Navigation system unified between selectedTab and NavigationController
- 22 feature destinations verified in Features menu
- 151 hardcoded strings externalized across 8 screens
