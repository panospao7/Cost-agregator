# ExpenseTracker Frontend UI/UX - Quick Reference Index

**Scout Analysis Complete** | Generated: May 7, 2026

---

## 📋 DOCUMENTATION OVERVIEW

This Scout analysis has created **3 comprehensive documents**:

1. **COMPREHENSIVE_UI_MAP.md** (Main Reference)
   - Complete file inventory
   - Screen-by-screen breakdown
   - Component library catalog
   - Navigation architecture
   - Color scheme & typography
   - Deep link configuration

2. **COMPREHENSIVE_UI_VISUAL_MAP.md** (Diagrams & Flow)
   - Flow diagrams
   - Screen layouts
   - Navigation state machine
   - Color palette visualization
   - File structure tree

3. **This document** (Quick Reference)
   - Essential lookup tables
   - Key facts
   - Common patterns

---

## 🗂️ QUICK NAVIGATION REFERENCE

### Main Tabs (Bottom Bar)
```
Index 0  Home/Dashboard      (HomeScreen.kt)
Index 1  Transactions        (TransactionsScreen.kt)
Index 2  Review              (ReviewScreen.kt)
Index 3  Budget/Plan         (BudgetScreen.kt)
Index 4  Analytics/Insights  (AnalyticsScreen.kt)
Index 5  Map/Spending Map    (SpendingMapScreen.kt)
```

### Feature Screens (23 Config-Driven)
Accessible from: Home widgets, Features Menu, or deep links

```
SavingsGoals              (SavingsGoalsScreen)
CarbonFootprint           (CarbonFootprintScreen)
WarrantyTracker           (WarrantyTrackerScreen)
PriceProtection           (PriceProtectionScreen)
BillNegotiation           (BillNegotiationScreen)
SmartSearch               (NaturalLanguageSearchScreen)
ReceiptMatching           (ReceiptMatchingScreen)
InvestmentPortfolio       (InvestmentPortfolioScreen)
BankConnections           (BankConnectionsScreen)
BillReminders             (BillRemindersScreen)
SpendingChallenges        (SpendingChallengesScreen)
AdvancedAnalytics         (AdvancedAnalyticsScreen)
CashFlowCalendar          (CashFlowCalendarScreen)
LifestyleInflation        (LifestyleInflationScreen)
SplitTemplates            (SplitTemplatesScreen)
VisualSplitEditor         (VisualSplitEditorScreen)
CurrencyManagement        (CurrencyManagementScreen)
SubscriptionManagement    (SubscriptionManagementScreen)
TaxConfiguration          (TaxConfigurationScreen)
ExportOptions             (ExportOptionsScreen)
RecurringExpenses         (RecurringExpensesScreen)
SharedExpenseGroups       (SharedExpenseGroupsScreen)
BackupRestore             (BackupRestoreScreen)

Management Screens:
- AiSettings                 (AiSettingsScreen)
- CategoryManagement         (CategoryScreen)
- PrivacySettings           (PrivacySettingsScreen)

Debug / Support Screens:
- DebugScreen                (DebugScreen)
- CategorizationDebugScreen  (CategorizationDebugScreen)
- DebugViewerScreen          (DebugViewerScreen)
- DebugIssueDetector         (debug support)
```

### Overlay Screens (6 Modals/Sheets)
Appear over main tabs without closing them:

```
AddExpense               Modal Sheet
ScanReceipt             Full Screen
RecurringExpenses       Full Screen
ManualRecurringExpense  Full Screen
Assistant               Bottom Sheet Modal
BudgetForecasting       Full Screen
```

---

## 🔗 DEEP LINK HOSTS

**Scheme**: `expensetracker://`

```
expensetracker://home                  → Home tab (with optional briefingKey param)
expensetracker://dashboard             → Home tab
expensetracker://activity              → Transactions tab
expensetracker://review                → Review tab
expensetracker://plan                  → Budget tab
expensetracker://add                   → Add Expense overlay
expensetracker://analytics             → Analytics tab
expensetracker://map                   → Map tab
```

---

## 📁 KEY FILES LOCATION MAP

```
ui/MainActivity.kt                      ← App entry, tab routing, deep links
ui/MainViewModel.kt                     ← Navigation requests, app state
ui/navigation/NavigationDestination.kt  ← ALL navigation destinations (sealed class)
ui/navigation/NavigationController.kt   ← Navigation state machine + back stack
ui/navigation/FeatureConfig.kt          ← Feature menu configuration
ui/integration/FeatureIntegration.kt    ← Feature routing/integration
ui/components/UiTextExtensions.kt      ← Text helpers and formatting

ui/screens/home/HomeScreen.kt           ← Dashboard with widgets
ui/screens/transactions/               ← Transaction list, filters, editing
ui/screens/review/                     ← Approval workflow
ui/screens/budget/                     ← Budget management & forecasting
ui/screens/analytics/                  ← Analytics & advanced analytics
ui/screens/map/                        ← Geo-visualization

ui/components/                          ← 51 reusable components
ui/theme/Theme.kt                       ← Color scheme (Midnight Navy)
ui/theme/Dimens.kt                      ← Spacing & sizing constants
```

---

## 🎨 COLOR REFERENCE

### Primary Colors
```
Base Navy        #0F172A    Background
Surface Light    #1E293B    Cards/Surface
Primary Indigo   #6366F1    Buttons/CTA
Primary Light    #818CF8    Light actions
```

### Status Colors
```
Success Green    #10B981    On-track, Good
Warning Orange   #F97316    Over-pace, Caution
Danger Red       #EF4444    Critical, Exceeded
```

### Text
```
Text Primary     #F1F5F9    Main text (high contrast)
Text Secondary   #94A3B8    Secondary text
Text Muted       #CC94A3B8  Muted (80% alpha)
```

---

## 📊 COMPONENT INVENTORY (59 Components)

### Dashboard Widgets (Home Screen)
- TotalsDashboardCard
- BudgetBlockPartyCard
- FinancialWeatherCard
- FinancialRunwayCard
- FinancialStressForecastCard
- MonteCarloForecastCard
- HealthScoreWidget / FinancialHealthScoreV2Widget (health/ subdir)
- RecommendationCard
- PlaceInsightCard
- NearbyShopSuggestionCard
- NoSpendStreakWidget

### Charts & Visualization
- CategoryDonutChart
- SpendingTrendChart
- SpendingPaceGauge
- ChartMarker
- ForecastTimeline
- MoneyRadarWidget

### AI Components
- AssistantResultCard
- CategoryAssistCard
- DedupeAssistCard
- ReceiptAssistCard
- ReceiptItemBreakdownCard
- AiChatBubble
- AiInsightsCard  
- AiRecommendationCard
- AiTypingIndicator

### Common Components
- EmptyState
- EnhancedEmptyState
- ErrorState
- LoadingSkeleton
- ContextualActionRegistry
- DefaultEmptyStateRegistryInitializer  
- EmptyStateAction

### Navigation Components
- AppNavigationBar (6 tabs)
- AppFabMenu (FAB with submenu)

### Support Components
- FeatureIntegration
- UiTextExtensions

### Dialogs/Sheets
- CategoryBreakdownSheet
- LocationCorrectionSheet
- TransactionFilterSheet
- LocationPermissionDialog
- NotificationPermissionDialog
- LocationSearchPicker

### Feature-Specific
- CategoryBreakdownSheet
- PeriodBlock / PeriodGridView
- PeriodNavigationBar
- TransferDirectionBadge
- PulseDot (status indicator)
- BentoCard

**Total**: 59 component files

---

## 🎯 NAVIGATION PATTERNS

### Pattern 1: Tab Navigation
```kotlin
navigation.navigateToTab(index: 0-5)  // Switch main tab
```

### Pattern 2: Feature Navigation
```kotlin
navigation.navigateTo(NavigationDestination.SavingsGoals)  // Go to feature
```

### Pattern 3: Overlay Navigation
```kotlin
navigation.navigateTo(NavigationDestination.AddExpense)  // Show modal
```

### Pattern 4: Back Navigation
```kotlin
navigation.navigateBack()  // Pop back stack or return to previous tab
```

### Pattern 5: Deep Link Navigation
```
Intent with URI: expensetracker://home?briefingKey=value
Handled in MainActivity.handleIntent()
```

---

## 📐 RESPONSIVE BEHAVIOR

### Tablet/Large Screens
- Master-detail layouts (potential)
- Wider card layouts
- Horizontal scrolling for features menu
- Landscape support

### Phone (Primary Target)
- Vertical scrolling (LazyColumn/LazyVerticalGrid)
- Bottom sheet modals
- Full-screen feature screens
- Adaptive font sizing

---

## ♿ ACCESSIBILITY

### Implemented
- Content descriptions on all icons (stringResource)
- Semantic markup via `semantics` modifier
- Badge for pending review count
- Keyboard navigation support
- System back handler

### Material 3 Standards
- Dynamic typography
- Color contrast compliance
- Touch target sizes (48dp minimum)

---

## 🔄 STATE MANAGEMENT PATTERN

**Per-Screen Pattern**:
```kotlin
@Composable
fun Screen(
    onNavigate: (NavigationDestination) -> Unit,
    viewModel: ScreenViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val uiEvent by viewModel.uiEvent.collect { event ->
        when (event) {
            is UiEvent.Navigate -> onNavigate(event.destination)
            is UiEvent.ShowSnackbar -> showSnackbar(event.message)
        }
    }
    // Render UI based on state
}
```

---

## 📱 SCAFFOLD STRUCTURE

```
Scaffold(
    topBar = { TopAppBar(...) },
    bottomBar = { AppNavigationBar(...) },
    floatingActionButton = { SmartFAB(...) },
    snackbarHost = { SnackbarHost(...) }
) { padding ->
    Box(Modifier.padding(padding)) {
        // Content
    }
}
```

---

## 🧪 DEBUG SCREENS

**Non-Production Only**:

1. **DebugScreen**: Raw notifications, AI runtime diagnostics, database tools
2. **CategorizationDebugScreen**: ML model performance metrics
3. **DebugViewerScreen**: Raw data viewer

**Access**: Hidden in menu, accessible via Settings (debug build only)

---

## ✅ QUALITY METRICS

| Metric | Count |
|--------|-------|
| Screen Packages | 35 |
| Screen Files | ~90 |
| Component Files | 59 |
| ViewModels | 38 |
| Dialog/Sheet Variants | 20+ |
| Feature Destinations | 23 |
| Management Screens | 4 |
| Main Tabs | 6 |
| Deep Links | 8 |
| UI Mapper Files | 3 |
| UI Utility Files | 4 |

---

## 🚀 PERFORMANCE CONSIDERATIONS

### Composable Recomposition
- State hoisting for efficient updates
- remember/rememberSaveable for local state
- collectAsState() for ViewModel flows

### Navigation Optimization
- Back stack managed via ArrayDeque
- State persistence via rememberSaveable
- No Fragment overhead (pure Compose)

### Memory
- ViewModel scoped to feature screens
- Bitmap caching for images
- Lazy loading for lists

---

## 📖 ARCHITECTURE HIGHLIGHTS

### Modern Navigation
- ✅ Sealed class-based (type-safe)
- ✅ No NavHost dependency
- ✅ CompositionLocal injection
- ✅ Back stack management
- ✅ State persistence

### Configuration-Driven Features
- ✅ Feature menu driven by FeatureConfig
- ✅ Easy to add/remove features
- ✅ Consistent menu presentation
- ✅ Feature badges (New/Beta)

### Theme System
- ✅ Midnight Navy color scheme
- ✅ Semantic color tokens
- ✅ Glass morphism support
- ✅ Material 3 compliance

### Accessibility
- ✅ String resource content descriptions
- ✅ Semantic markup
- ✅ Proper color contrast
- ✅ Touch target sizing

---

## 🔍 COMMON IMPLEMENTATION PATTERNS

### Empty State with CTA
```kotlin
if (items.isEmpty()) {
    EnhancedEmptyState(
        icon = Icons.Rounded.ShoppingCart,
        title = "No Expenses",
        description = "Start tracking your spending",
        actionText = "Add Expense",
        onAction = { navigation.navigateTo(NavigationDestination.AddExpense) }
    )
}
```

### Modal Sheet Pattern
```kotlin
if (currentDestination is NavigationDestination.AddExpense) {
    AddExpenseSheet(
        onDismiss = { navigation.navigateBack() },
        initialAmount = null
    )
}
```

### Feature Navigation Pattern
```kotlin
val featureConfigs = FeatureConfig.allFeatures
Column {
    featureConfigs.forEach { feature ->
        FeatureMenuItem(
            config = feature,
            onClick = { navigation.navigateTo(feature.destination) }
        )
    }
}
```

---

## 🎓 HOW TO ADD A NEW SCREEN

1. **Create Screen File**:
   ```
   ui/screens/{feature}/{FeatureScreen.kt}
   ui/screens/{feature}/{FeatureViewModel.kt}
   ```

2. **Add to NavigationDestination**:
   ```kotlin
   data object NewFeature : NavigationDestination()
   ```

3. **Add to Feature Menu** (if feature):
   ```kotlin
   FeatureConfig.allFeatures.add(
       FeatureConfig(
           id = "new-feature",
           titleRes = R.string.feature_new_feature,
           icon = Icons.Rounded.Star,
           destination = NavigationDestination.NewFeature
       )
   )
   ```

4. **Route in MainActivity**:
   ```kotlin
   is NavigationDestination.NewFeature -> {
       NewFeatureScreen(onNavigateBack = { navigation.navigateBack() })
   }
   ```

---

## 🐛 COMMON ISSUES & SOLUTIONS

| Issue | Solution |
|-------|----------|
| Back button not working | Ensure screen calls `navigation.navigateBack()` |
| Navigation state lost | State is saved via `rememberSaveable` |
| Deep link not working | Check AndroidManifest.xml intent-filter |
| Component not appearing | Verify it's not hidden by conditional rendering |
| Color looks different | Check if using SemanticColors or hardcoded color |

---

## 📞 KEY CONTACTS (In-Code)

- **Navigation**: NavigationController (CompositionLocal)
- **UI State**: ViewModel + StateFlow
- **Colors**: SemanticColors object in Theme.kt
- **Features**: FeatureConfig.allFeatures
- **Dialogs**: Individual composable functions

---

## 📚 RECOMMENDED READING ORDER

1. Start: `COMPREHENSIVE_UI_MAP.md` (Main reference)
2. Visualize: `COMPREHENSIVE_UI_VISUAL_MAP.md` (Diagrams)
3. Implement: Navigation patterns in this document
4. Debug: Check debug screens for diagnostics

---

## 🔐 PERMISSIONS REQUIRED

```xml
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
<uses-permission android:name="android.permission.ACCESS_COARSE_LOCATION" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
```

---

## 📝 SUMMARY

**Total UI Files**: 154  
**Architecture**: Pure Compose with type-safe navigation  
**Color Scheme**: Midnight Navy with semantic tokens  
**Features**: 23 config-driven features + 4 management screens  
**Accessibility**: Material 3 standards compliant  
**State Management**: ViewModel + StateFlow pattern  
**Navigation**: Sealed class + CompositionLocal  

**Status**: ✅ Complete, consistent, and well-organized

---

**End of Quick Reference Index**

*For detailed implementation guides, see the main documentation files.*
