# Sprint A — Confirmed-Issue Ledger
## Generated: Auto-validation run on new features and navigation

### Legend
- **Status**: CONFIRMED / FALSE-POSITIVE / DEFERRED
- **Proof Type**: COMPILE / RUNTIME / MANUAL / STATIC
- **Severity**: CRITICAL / MAJOR / MINOR

---

## Navigation Architecture Issues

### A-01-NAV-001: Parallel Navigation Surface Still Active
**Status**: CONFIRMED  
**Severity**: MAJOR  
**Proof Type**: STATIC  
**Location**: `ui/navigation/Phase4Navigation.kt`  
**Description**: Legacy `Phase4Navigation` object provides string-based routes that can conflict with sealed class `NavigationDestination`.  
**Repro Steps**: 
1. Search for references to `NavigationDestinations.*`  
2. Observe duplicate routing capability exists alongside sealed class system  
**Impact**: Risk of navigation path divergence; developers may use inconsistent routing.  
**Fix Required**: Remove or deprecate legacy navigation object.  
**Acceptance**: Zero active references to string-based routes in production code.

### A-01-NAV-002: Home Screen Has 22 Feature Callback Parameters
**Status**: CONFIRMED  
**Severity**: MAJOR  
**Proof Type**: STATIC  
**Location**: `ui/screens/home/HomeScreen.kt` lines 73-96  
**Description**: Despite FeaturesMenu using NavigationController, HomeScreen still declares 22 callback parameters for feature navigation.  
**Repro Steps**: 
1. Open `HomeScreen.kt`  
2. Count callback parameters in function signature (22 total)  
**Impact**: Callback drilling complexity, maintenance burden, inconsistent architecture.  
**Fix Required**: Remove all feature callbacks; rely solely on NavigationController via CompositionLocal.  
**Acceptance**: HomeScreen has ≤5 parameters (core navigation only).

### A-01-NAV-003: Boolean Flags Still Drive Feature Visibility
**Status**: CONFIRMED  
**Severity**: MAJOR  
**Proof Type**: STATIC  
**Location**: `ui/MainActivity.kt` lines 225-280  
**Description**: Feature screens rendered based on 26 boolean flags (`showSavingsGoals`, `showCarbonFootprint`, etc.) rather than pure NavigationDestination state.  
**Repro Steps**: 
1. Examine `MainScreen` composable  
2. Observe `when (navigation.destination)` mapping to boolean assignments  
3. Note screen visibility checks use `if (showSavingsGoals)` not direct destination check  
**Impact**: Dual state management (NavigationController + booleans), risk of desync.  
**Fix Required**: Derive screen visibility directly from NavigationController state.  
**Acceptance**: Feature screens render based on `navigation.destination` directly.

### A-01-NAV-004: No Back Stack Implementation
**Status**: CONFIRMED  
**Severity**: MAJOR  
**Proof Type**: RUNTIME  
**Location**: `ui/navigation/NavigationController.kt`  
**Description**: NavigationController tracks current destination but has no back stack; `navigateBack()` exists but implementation is incomplete.  
**Repro Steps**: 
1. Open any feature screen  
2. Press system back button  
3. Observe app closes instead of returning to previous destination  
**Impact**: Poor UX - users lose context on back navigation.  
**Fix Required**: Implement proper back stack with `ArrayDeque` or similar.  
**Acceptance**: Back from feature returns to previous destination, not app exit.

---

## Data/Logic Issues

### A-01-DATA-001: Fake Expense ID Linkage in Groups
**Status**: CONFIRMED  
**Severity**: CRITICAL  
**Proof Type**: STATIC  
**Location**: `ui/screens/groups/SharedExpenseGroupsViewModel.kt` line 223  
**Description**: Group expenses created with `expenseId = 0` - not linked to actual expense record.  
**Repro Steps**: 
1. Read `addExpense` function in ViewModel  
2. Observe hardcoded `expense.expenseId = 0`  
**Impact**: Orphaned group expenses, no referential integrity, broken expense tracking.  
**Fix Required**: Integrate with real expense creation flow, ensure FK constraint.  
**Acceptance**: Every `group_expense` row references valid `expenses.id`.

### A-01-DATA-002: Empty Split Amounts Placeholder
**Status**: CONFIRMED  
**Severity**: MAJOR  
**Proof Type**: STATIC  
**Location**: `ui/screens/groups/SharedExpenseGroupsViewModel.kt` lines 107-116  
**Description**: `calculateSplitAmounts()` returns empty map; split logic not implemented.  
**Repro Steps**: 
1. Search `calculateSplitAmounts` in ViewModel  
2. Observe function returns `emptyMap()`  
**Impact**: Split amounts don't display correctly, balances may be incorrect.  
**Fix Required**: Implement proper split calculation for EQUAL type (default).  
**Acceptance**: Split amounts contain valid member->amount mappings.

### A-01-DATA-003: Duplicate Recurrence Logic
**Status**: CONFIRMED  
**Severity**: MAJOR  
**Proof Type**: STATIC  
**Location**: `ui/screens/recurringmanual/ManualRecurringExpenseViewModel.kt` and `ui/screens/subscription/SubscriptionManagementViewModel.kt`  
**Description**: Monthly cost calculation duplicated across both ViewModels with slight variations.  
**Repro Steps**: 
1. Compare `calculateMonthlyCost()` implementations  
2. Note code duplication  
**Impact**: Maintenance risk, potential calculation inconsistencies.  
**Fix Required**: Extract to shared domain utility `RecurrenceCalculator`.  
**Acceptance**: Single source of truth for recurrence math.

---

## UI/UX Issues

### A-01-UI-001: ErrorState Duplicated Across Screens
**Status**: CONFIRMED  
**Severity**: MINOR  
**Proof Type**: STATIC  
**Location**: All 6 new feature screens  
**Description**: Each screen has private `ErrorState` composable with identical implementation.  
**Repro Steps**: 
1. Search for `private fun ErrorState` across new screen files  
2. Count duplicate implementations (6 total)  
**Impact**: Code bloat, inconsistent styling risk.  
**Fix Required**: Extract to shared component in Sprint C.  
**Acceptance**: Single shared ErrorState component.

### A-01-UI-002: SummaryCard Pattern Duplicated
**Status**: CONFIRMED  
**Severity**: MINOR  
**Proof Type**: STATIC  
**Location**: `recurringmanual`, `subscription`, `groups` screens  
**Description**: Summary card pattern (icon + value + label) repeated with slight variations.  
**Impact**: Visual inconsistency, maintenance overhead.  
**Fix Required**: Consolidate to shared `MetricCard` component.  
**Acceptance**: All metric displays use unified component.

---

## i18n Issues (Pre-G)

### A-01-I18N-001: Hardcoded User-Facing Strings in New Features
**Status**: CONFIRMED  
**Severity**: MAJOR  
**Proof Type**: STATIC  
**Location**: All 6 new feature screens  
**Description**: Hundreds of hardcoded English strings across new screens ("Add Expense", "No recurring expenses", etc.).  
**Repro Steps**: 
1. Scan new screen files for string literals in UI  
2. Count non-resource strings (100+)  
**Impact**: Blocks i18n Sprint G, non-localizable app.  
**Fix Required**: Externalize all strings (to be addressed in Sprint G).  
**Acceptance**: Zero hardcoded user-facing strings.

### A-01-I18N-002: FeatureConfig Uses Raw Strings
**Status**: CONFIRMED  
**Severity**: MAJOR  
**Proof Type**: STATIC  
**Location**: `ui/navigation/FeatureConfig.kt` lines 44-218  
**Description**: `title` and `description` fields use raw String literals, not string resources.  
**Impact**: Features menu cannot be localized.  
**Fix Required**: Convert to `@StringRes` references.  
**Acceptance**: FeatureConfig uses string resource IDs.

---

## False Positives

### A-01-FP-001: ExperimentalMaterial3Api Deprecation Warnings
**Status**: FALSE-POSITIVE  
**Severity**: N/A  
**Proof Type**: COMPILE  
**Description**: `@file:OptIn(ExperimentalMaterial3Api::class)` annotations suppress warnings correctly.  
**Reason**: This is intentional use of experimental APIs, not a bug. Will be resolved when Material3 stabilizes.

### A-01-FP-002: RecurringExpenseDao Deprecation
**Status**: FALSE-POSITIVE  
**Severity**: N/A  
**Proof Type**: STATIC  
**Description**: Deprecation warnings on RecurringExpenseDao are intentional migration markers.  
**Reason**: Part of planned Phase 2 DAO standardization; not a bug but a migration aid.

---

## Deferred Issues

### A-01-DEF-001: Deep Link Handling
**Status**: DEFERRED  
**Severity**: MINOR  
**Reason**: Not required for current feature set; can be added post-stabilization.

### A-01-DEF-002: Navigation Animations
**Status**: DEFERRED  
**Severity**: MINOR  
**Reason**: Visual enhancement; core navigation stability takes priority.

---

## Summary

**Total Issues**: 15  
**Confirmed**: 13 (4 CRITICAL/MAJOR, 9 MINOR)  
**False Positives**: 2  
**Deferred**: 2  

**Action Required**:
- Immediate: Address 4 CRITICAL/MAJOR navigation issues (A-01-NAV-001 through 004, A-01-DATA-001)
- This Sprint: All CONFIRMED issues assigned to Sprint A, B, C, D, G
- Block i18n until Sprint G: A-01-I18N-001, A-01-I18N-002

**Next Steps**:
1. ✅ A-01 ledger complete (this document)
2. 🔄 Proceed with A-02: Remove Phase4Navigation.kt
3. 🔄 Proceed with A-03: Consolidate NavigationController
4. 🔄 Proceed with A-04: Collapse Home callbacks
5. 🔄 Proceed with A-05: Implement back stack
