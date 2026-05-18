# ExpenseTracker Frontend UI/UX Comprehensive Mapping

**Refreshed**: May 18, 2026  
**Scope**: Current frontend inventory including screens, components, navigation, integration, and theming
**Total Files**: 164 UI source files (38 ViewModels, 59 components, 38 screens, 5 nav, 3 mappers, 2 theme, 4 model, 7 util, 8 other)

---

## 1. HIERARCHICAL UI STRUCTURE

### App Root
- **Entry Point**: `MainActivity.kt`
- **Theme Provider**: `ExpenseTrackerTheme`
- **Navigation Manager**: `NavigationController` (CompositionLocal)
- **Scaffold**: Material3 with BottomNavigationBar + FAB

```
ExpenseTrackerApp
│
├── Main Tabs (Bottom Navigation) [6 tabs]
│   ├── Tab 0: Home/Dashboard
│   ├── Tab 1: Transactions (Activity)
│   ├── Tab 2: Review
│   ├── Tab 3: Budget (Plan)
│   ├── Tab 4: Analytics (Insights)
│   └── Tab 5: Spending Map
│
├── Overlay Screens (Sheet/Dialog)
│   ├── Add Expense (Modal Sheet)
│   ├── Receipt Scan (Full Screen)
│   ├── Recurring Expenses
│   ├── Manual Recurring Expense
│   ├── Budget Forecasting (Full Screen)
│   └── AI Assistant (Chat Sheet)
│
├── Feature Screens (Config-Driven)
│   └── Accessed from Home widgets + Features Menu
│
├── Debug / Support Screens
│   └── Debug, categorization debug, runtime diagnostics
│
└── Settings/Management Screens
    ├── AI Settings
    ├── Category Management
    ├── Backup &amp; Restore
    └── Privacy Settings
```

---

## 2. MAIN TABS (BOTTOM NAVIGATION BAR)

### Tab 0: HOME SCREEN
**File**: `ui/screens/home/HomeScreen.kt` + `HomeViewModel.kt`

#### Key Components:
- **Header**: PulseDot (service status indicator)
- **Quick Settings Button** → Dialog
- **Features Menu Button**
- **Period Navigation**: Current period selector with date range
- **Dashboard Widgets** (configurable):
  - TotalsDashboardCard (overall spending)
  - BudgetBlockPartyCard (budget overview)
  - FinancialWeatherCard (health indicator)
  - FinancialRunwayCard (sustainability forecast)
  - FinancialStressForecastCard (stress level)
  - MonteCarloForecastCard (probabilistic forecast)
  - HealthScoreWidget (financial health V2)
  - CategoryBreakdownSheet (modal breakdown)
  - PlaceInsightCard (location-based spending)
  - RecommendationCard (AI recommendations)
  - NoSpendStreakWidget (streaks counter)

#### Navigation Callbacks:
- `onNavigateToReview()` → Tab 2
- `onNavigateToRecurring()` → RecurringExpenses feature
- `onNavigateToTransactions(filter)` → Tab 1 with filter
- `onNavigateToAnalytics()` → Tab 4
- `onNavigateToMap()` → Tab 5
- `onNavigateToBudgetDetail()` → Tab 3
- `onNavigateToFeature()` → Any feature from FeatureConfig

#### Modals:
- **QuickSettingsDialog**: Settings shortcuts (AI, Categories, Debug)
- **AddPlannedExpenseDialog**: Quick expense planning
- **FeatureIntegration**: configuration-driven feature routing

---

### Tab 1: TRANSACTIONS SCREEN
**File**: `ui/screens/transactions/TransactionsScreen.kt` + `TransactionsViewModel.kt`

#### Key Components:
- **TopAppBar**: Title, Search, Filter buttons
- **TransactionFilterSheet** (Modal): Advanced filtering UI
- **Transaction List**: Grouped by date/period
  - Transaction Cards with:
    - Merchant name
    - Category badge
    - Amount (with currency)
    - Transfer direction badge
    - Date/time
    - Location (if available)

#### Features:
- Filter by category, date range, amount, merchant, location
- Sort by date/amount/merchant
- Inline editing (category, amount, notes)
- Swipe actions (delete, archive)
- Bulk actions (select multiple)
- Quick add expense button

#### Sub-Screens (Dialogs/Sheets):
- **TransactionFilterSheet**: Filter UI with date, category, amount ranges
- **DeleteConfirmationDialog**: Deletion warning
- **RecurrencePickerDialog**: Set recurring pattern
- **CategoryPickerDialog**: Change category
- **RenameMerchantDialog**: Correct merchant name
- **ChangeTypeDialog**: Transaction type (expense/income/transfer)
- **EditOwnershipDialog**: Split ownership
- **EditLocationDialog**: Update location

#### Navigation:
- `onNavigateToAnalytics()` → Tab 4
- `onAddExpense()` → AddExpense overlay
- `initialFilter` prop for filtered views

---

### Tab 2: REVIEW SCREEN
**File**: `ui/screens/review/ReviewScreen.kt` + `ReviewViewModel.kt`

#### Purpose:
Pending transaction review/approval workflow

#### Key Components:
- **Pending Items List**: Transactions awaiting review
  - Confidence scores
  - Suggested categorizations
  - AI-powered corrections

#### Features:
- One-click approve/reject
- Bulk approve all (FAB action in this tab)
- Edit before approving
- Undo recent approvals

#### Sub-Screens (Dialogs):
- **EditReviewDialog**: Modify and approve

#### Status Display:
- Badge on Tab 2 navigation item showing pending count

---

### Tab 3: BUDGET SCREEN
**File**: `ui/screens/budget/BudgetScreen.kt` + `BudgetViewModel.kt`

#### Key Components:
- **Budget List**: All user budgets
  - Progress bars (with color coding: on-track/warning/critical)
  - Spending pace vs. rate
  - Period information (monthly/quarterly/custom)

#### Features:
- Create/edit/delete budgets
- Set budget amounts, categories, periods
- Budget forecasting link
- Spending pace indicator

#### Sub-Screens (Dialogs):
- **AddEditBudgetDialog**: Create/modify budget

#### Navigation:
- `onNavigateToForecast(budget)` → BudgetForecasting feature
- `NavigationDestination.BudgetCreate` → Opens Budget tab with create dialog pre-opened (added S2-008R)

---

### Tab 4: ANALYTICS SCREEN
**File**: `ui/screens/analytics/AnalyticsScreen.kt` + `AnalyticsViewModel.kt`

#### Key Components:
- **Period Selector**: Date range navigation
- **Category Pie Chart**: CategoryDonutChart component
- **Spending Trend Chart**: Historical spending line chart
- **Spending Pace Gauge**: Current burn rate
- **Top Categories**: List of top spending categories

#### Features:
- Interactive charts (tap to drill down)
- Period comparison
- Category-based analysis
- Trend visualization

#### Navigation:
- `onNavigateToTransactions(filter)` → Tab 1 with filter

#### Related Screen:
- **AdvancedAnalyticsScreen**: Feature with deeper analytics
  - PersonalityProfileCard
  - StatisticalVisualizations
  - Historical data analysis

---

### Tab 5: SPENDING MAP SCREEN
**File**: `ui/screens/map/SpendingMapScreen.kt` + `SpendingMapViewModel.kt`

#### Purpose:
Geospatial visualization of spending

#### Key Components:
- **OSMDroid Map**: Open Street Map integration
- **Expense Markers**: Location-based expense pins
  - Clustered markers for density
  - Color-coded by category
  
#### Features:
- Tap marker → expense details
- Filter by category/date
- Heat map visualization (optional)
- Nearby shop suggestions

#### Sub-Screens (Modal Sheets):
- **PinExpenseSheet**: Edit location-based expense

#### Components Used:
- **LocationSearchPicker**: Search/select locations
- **LocationCorrectionSheet**: Fix location data
- **NearbyShopSuggestionCard**: Recommend nearby stores
- **LocationPermissionDialog**: Request location access

---

## 3. OVERLAY SCREENS & MODALS

These appear over main tabs via `NavigationDestination` sealed class.

### AddExpense Screen
**File**: `ui/screens/addexpense/AddExpenseSheet.kt` + `AddExpenseViewModel.kt`
**Type**: Modal Bottom Sheet
**Navigation**: `NavigationDestination.AddExpense`

#### Features:
- Form fields: amount, date, category, merchant, notes
- Currency selection
- Receipt attachment
- Recurring pattern setup
- Split expense option
- Clipboard amount detection
- Category auto-complete

---

### Receipt Scan Screen
**File**: `ui/screens/receiptscan/ReceiptScanScreen.kt` + `ReceiptScanViewModel.kt`
**Type**: Full Screen Modal
**Navigation**: `NavigationDestination.ScanReceipt`

#### Features:
- Camera preview
- Receipt image capture
- OCR text extraction
- Item line detection
- Receipt item categorization
- AI-powered category suggestion

#### Sub-Component:
- **ReceiptItemBreakdownCard**: Shows extracted items

---

### Recurring Expenses Screen
**File**: `ui/screens/recurring/RecurringExpensesScreen.kt`
**Type**: Full Screen
**Navigation**: `NavigationDestination.RecurringExpenses`

#### Features:
- List of recurring expenses
- Frequency indicators
- Next occurrence date
- Edit/delete actions
- View all occurrences

---

### Manual Recurring Expense Screen
**File**: `ui/screens/recurringmanual/ManualRecurringExpenseScreen.kt` + `ViewModel.kt`
**Type**: Full Screen
**Navigation**: `NavigationDestination.ManualRecurringExpense`

#### Features:
- Create manual recurring expenses
- Set frequency (weekly, bi-weekly, monthly, quarterly, annually)
- Set start/end dates
- Category pre-fill

#### Sub-Screens (Dialogs):
- **AddRecurringExpenseDialog**: Form dialog

---

### AI Assistant Sheet
**File**: `ui/screens/assistant/AssistantSheet.kt` + `AssistantViewModel.kt`
**Type**: Bottom Sheet Modal
**Navigation**: `NavigationDestination.Assistant`

#### Features:
- Chat interface
- Natural language queries
- Expense analysis
- Recommendations
- Multi-turn conversations

#### Components:
- **AssistantResultCard**: Display results
- **CategoryAssistCard**: Category suggestions
- **DedupeAssistCard**: Duplicate detection
- **ReceiptAssistCard**: Receipt OCR results

---

### Budget Forecasting Screen
**File**: `ui/screens/budget/BudgetForecastingScreen.kt` + `BudgetForecastingViewModel.kt`
**Type**: Full Screen
**Navigation**: `NavigationDestination.BudgetForecasting(budget)`

#### Features:
- Budget projection
- Historical burn rate analysis
- Forecast timeline (see when budget exhausted)
- Confidence intervals

#### Components:
- **ForecastTimeline**: Visual timeline

---

## 4. FEATURE SCREENS (23 Config-Driven Features)

All features accessible from:
1. Home screen widgets/cards
2. Features Menu (accessed via icon button)
3. Deep links where applicable
4. Bottom FAB menu

**Navigation Type**: Full screen with back stack support

### Feature 1: Savings Goals
**File**: `ui/screens/savings/SavingsGoalsScreen.kt` + `SavingsGoalsViewModel.kt`
**Navigation**: `NavigationDestination.SavingsGoals`

#### Features:
- Create/manage savings goals
- Progress visualization
- Target amount tracking
- Deadline management
- Auto-allocation to goals

---

### Feature 2: Carbon Footprint
**File**: `ui/screens/carbon/CarbonFootprintScreen.kt` + `CarbonFootprintViewModel.kt`
**Navigation**: `NavigationDestination.CarbonFootprint`

#### Features:
- CO₂ emission tracking per transaction
- Category-based carbon analysis
- Environmental impact scoring
- Eco-friendly alternatives suggestions

---

### Feature 3: Warranty Tracker
**File**: `ui/screens/warranty/WarrantyTrackerScreen.kt` + `WarrantyTrackerViewModel.kt`
**Navigation**: `NavigationDestination.WarrantyTracker`

#### Features:
- Link purchases to warranty info
- Expiration date tracking
- Claim reminders
- Linked to email receipts

---

### Feature 4: Price Protection
**File**: `ui/screens/price/PriceProtectionScreen.kt` + `PriceProtectionViewModel.kt`
**Navigation**: `NavigationDestination.PriceProtection`

#### Features:
- Price drop monitoring
- Return window tracking
- Price match notifications
- Return instructions

#### Sub-Component:
- **ReturnWindowCard**: Track return deadlines

---

### Feature 5: Bill Negotiation
**File**: `ui/screens/negotiation/BillNegotiationScreen.kt` + `BillNegotiationViewModel.kt`
**Navigation**: `NavigationDestination.BillNegotiation`

#### Features:
- Bill analysis (utilities, subscriptions)
- Negotiation script generator
- Outcome tracking
- Savings calculator

#### Sub-Screens (Dialogs):
- **NegotiationScriptDialog**: Generated script
- **OutcomeRecordingDialog**: Log negotiation results

---

### Feature 6: Smart Search (Natural Language)
**File**: `ui/screens/naturallanguage/NaturalLanguageSearchScreen.kt` + `ViewModel.kt`
**Navigation**: `NavigationDestination.SmartSearch`

#### Features:
- Natural language queries (e.g., "How much did I spend on coffee last month?")
- Query parsing
- Result aggregation
- Chart visualization

#### Status: NEW badge in menu

---

### Feature 7: Receipt Matching
**File**: `ui/screens/receiptmatching/ReceiptMatchingScreen.kt` + `ReceiptMatchingViewModel.kt`
**Navigation**: `NavigationDestination.ReceiptMatching`

#### Features:
- Match scanned receipts to transactions
- Item-level verification
- Auto-categorization refinement
- Archive matched receipts

---

### Feature 8: Investment Portfolio
**File**: `ui/screens/investment/InvestmentPortfolioScreen.kt` + `InvestmentViewModel.kt`
**Navigation**: `NavigationDestination.InvestmentPortfolio`

#### Features:
- Investment tracking
- Holdings visualization
- Performance metrics
- Asset allocation

---

### Feature 9: Bank Connections
**File**: `ui/screens/bank/BankConnectionsScreen.kt` + `BankConnectionsViewModel.kt`
**Navigation**: `NavigationDestination.BankConnections`

#### Features:
- Plaid/Open Banking integration
- Account linking
- Automatic transaction sync
- Connection status

---

### Feature 10: Bill Reminders
**File**: `ui/screens/reminder/BillRemindersScreen.kt` + `BillRemindersViewModel.kt`
**Navigation**: `NavigationDestination.BillReminders`

#### Features:
- Create payment reminders
- Due date tracking
- Notification scheduling
- Bill history

---

### Feature 11: Spending Challenges
**File**: `ui/screens/challenge/SpendingChallengesScreen.kt` + `SpendingChallengesViewModel.kt`
**Navigation**: `NavigationDestination.SpendingChallenges`

#### Features:
- Create spending reduction challenges
- Gamification elements
- Progress tracking
- Leaderboard (if multiplayer)

---

### Feature 12: Advanced Analytics
**File**: `ui/screens/analytics/AdvancedAnalyticsScreen.kt` + `AdvancedAnalyticsViewModel.kt`
**Navigation**: `NavigationDestination.AdvancedAnalytics`

#### Features:
- Statistical analysis
- Personality profile insights
- Trend forecasting
- Comparative analysis

#### Components:
- **PersonalityProfileCard**: Spending personality
- **StatisticalVisualizations**: Advanced charts

---

### Feature 13: Cash Flow Calendar
**File**: `ui/screens/cashflow/CashFlowCalendarScreen.kt` + `CashFlowCalendarViewModel.kt`
**Navigation**: `NavigationDestination.CashFlowCalendar`

#### Features:
- Calendar view of transactions
- Daily cash flow visualization
- Income vs. expense timeline
- Period balance tracking

#### Components:
- **PeriodBlock**: Calendar period cell
- **PeriodGridView**: Month grid

---

### Feature 14: Lifestyle Inflation
**File**: `ui/screens/lifestyle/LifestyleInflationScreen.kt` + `LifestyleInflationViewModel.kt`
**Navigation**: `NavigationDestination.LifestyleInflation`

#### Features:
- Lifestyle spending tracking
- Inflation impact calculation
- Category spending trend analysis
- Sustainable lifestyle scoring

---

### Feature 15: Split Templates
**File**: `ui/screens/split/SplitTemplatesScreen.kt`
**Type**: Template management
**Navigation**: `NavigationDestination.SplitTemplates`

#### Features:
- Save split patterns as templates
- Reuse templates
- Edit/delete templates
- Template categories

#### Navigation:
- `onCreateTemplate()` → VisualSplitEditor
- `onEditTemplate(template)` → VisualSplitEditor with template

---

### Feature 16: Visual Split Editor
**File**: `ui/screens/split/VisualSplitEditorScreen.kt` + `VisualSplitViewModel.kt`
**Navigation**: `NavigationDestination.VisualSplitEditor(expense, templateId)`

#### Features:
- Visual split amount entry
- Pie chart split view
- Multiple split algorithms (equal, percentage, manual)
- Save as template option
- Apply to expense

---

### Feature 17: Currency Management
**File**: `ui/screens/currency/CurrencyManagementScreen.kt` + `CurrencyManagementViewModel.kt`
**Navigation**: `NavigationDestination.CurrencyManagement`

#### Features:
- Multi-currency expense tracking
- Exchange rate management
- Currency conversion
- Default currency setting

#### Sub-Screens (Dialogs):
- **ConversionDialog**: Currency conversion helper

#### Status: NEW badge

---

### Feature 18: Subscription Management
**File**: `ui/screens/subscription/SubscriptionManagementScreen.kt` + `SubscriptionManagementViewModel.kt`
**Navigation**: `NavigationDestination.SubscriptionManagement`

#### Features:
- Track recurring subscriptions
- Billing cycle management
- Cancellation reminders
- Cost aggregation

#### Sub-Screens (Dialogs):
- **AddSubscriptionDialog**: Add subscription

#### Status: NEW badge

---

### Feature 19: Tax Configuration
**File**: `ui/screens/tax/TaxConfigurationScreen.kt` + `TaxConfigurationViewModel.kt`
**Navigation**: `NavigationDestination.TaxConfiguration`

#### Features:
- Deductible category tagging
- Tax year setup
- Report generation preparation
- Tax optimization suggestions

#### Status: NEW badge

---

### Feature 20: Export Options
**File**: `ui/screens/export/ExportOptionsScreen.kt` + `ExportOptionsViewModel.kt`
**Navigation**: `NavigationDestination.ExportOptions`

#### Features:
- Export to CSV
- Export to PDF
- Export to JSON
- Date range selection
- Category filtering

#### Status: NEW badge

---

### Feature 21: Shared Expense Groups
**File**: `ui/screens/groups/SharedExpenseGroupsScreen.kt` + `SharedExpenseGroupsViewModel.kt`
**Navigation**: `NavigationDestination.SharedExpenseGroups`

#### Features:
- Create expense sharing groups
- Invite members
- Split expenses within group
- Settlement tracking

#### Sub-Screens (Dialogs):
- **CreateGroupDialog**: New group form
- **AddMemberDialog**: Invite members
- **AddExpenseDialog**: Add group expense

#### Status: NEW badge

---

### Feature 22: Backup &amp; Restore
**File**: `ui/screens/backup/BackupRestoreScreen.kt` + `BackupRestoreViewModel.kt`
**Navigation**: `NavigationDestination.BackupRestore`

#### Features:
- Create encrypted .costbackup bundles
- Restore from backup with crash-safe journal
- AES-256-GCM encryption
- Privacy-gated export options (raw/anonymized)

---

---

## 5. MANAGEMENT SCREENS

### AI Settings Screen
**File**: `ui/screens/aisettings/AiSettingsScreen.kt` + `AiSettingsViewModel.kt`
**Navigation**: `NavigationDestination.AiSettings`

#### Features:
- AI model selection
- On-device vs. cloud toggle
- Privacy settings
- Engagement options
- Runtime diagnostics

---

### Category Management Screen
**File**: `ui/screens/categories/CategoryScreen.kt` + `CategoryViewModel.kt`
**Navigation**: `NavigationDestination.CategoryManagement`

#### Features:
- Create/edit/delete categories
- Color assignment
- Icon selection
- Category organization (hierarchy)
- Merge categories

#### Sub-Screens (Dialogs):
- **AddCategoryDialog**: Create/edit category

---

### Privacy Settings Screen
**File**: `ui/screens/privacysettings/PrivacySettingsScreen.kt` + `PrivacySettingsViewModel.kt`
**Navigation**: *(no standalone route — accessible from Settings gear icon)*

#### Features:
- 10 privacy toggles (notification capture, cloud AI, geocoding, etc.)
- 2 retention day settings
- Privacy audit log viewer
- Raw storage mode indicators
- Risky action confirmation dialogs

---

## 6. DEBUG SCREENS (Internal Only)

### Debug Screen
**File**: `ui/screens/debug/DebugScreen.kt` + `DebugViewModel.kt`

#### Features:
- Raw notification stream viewer
- Notification source filtering
- AI runtime status display
- AI runtime diagnostics
- Service diagnostics
- Database import/export
- CSV import
- Issue inspection and data seeding tools

#### Sub-Screens:
- **CategorizationDebugScreen**: ML model debugging
- **DebugViewerScreen**: Raw data viewer
- **DebugIssueDetector**: runtime issue inspection

#### Sub-Components (Dialogs):
- **ImportDatabaseDialog**: Database restore
- **CsvImportDialog**: CSV import tool

**Access**: Hidden in production, accessible via Settings → Debug

---

### Categorization Debug Screen
**File**: `ui/screens/debug/CategorizationDebugScreen.kt` + `CategorizationDebugViewModel.kt`

#### Features:
- Classifier performance metrics
- Category prediction analysis
- Confidence scores
- False positive tracking

---

## 7. REUSABLE COMPONENTS

### 7.1 Dashboard Widgets (Home Screen)

| Component | File | Purpose |
|-----------|------|---------|
| **TotalsDashboardCard** | `TotalsDashboardCard.kt` | Period totals + spending summary |
| **BudgetBlockPartyCard** | `BudgetBlockPartyCard.kt` | Budget overview grid |
| **FinancialWeatherCard** | `FinancialWeatherCard.kt` | Health status (sunny/cloudy/stormy) |
| **FinancialRunwayCard** | `FinancialRunwayCard.kt` | Months of runway estimation |
| **FinancialStressForecastCard** | `FinancialStressForecastCard.kt` | Financial stress indicator |
| **MonteCarloForecastCard** | `MonteCarloForecastCard.kt` | Probabilistic forecast visualization |
| **HealthScoreWidget** | `health/HealthScoreWidget.kt` | Financial health V1 |
| **FinancialHealthScoreV2Widget** | `health/FinancialHealthScoreV2Widget.kt` | Financial health V2 |
| **RecommendationCard** | `RecommendationCard.kt` | AI recommendations display |
| **PlaceInsightCard** | `PlaceInsightCard.kt` | Location-based spending insights |
| **NearbyShopSuggestionCard** | `NearbyShopSuggestionCard.kt` | Nearby store suggestions |
| **NoSpendStreakWidget** | `analytics/NoSpendStreakWidget.kt` | Spending streaks counter |

### 7.2 Feature and support components

| Component | File | Purpose |
|-----------|------|---------|
| **FeatureIntegration** | `integration/FeatureIntegration.kt` | Feature menu and routing integration |
| **UiTextExtensions** | `components/UiTextExtensions.kt` | UI text helpers and formatting |
| *(empty state components listed in §7.11)* | | |

### 7.3 Chart & Visualization Components

| Component | File | Purpose |
|-----------|------|---------|
| **CategoryDonutChart** | `CategoryDonutChart.kt` | Pie/donut spending breakdown |
| **SpendingTrendChart** | `SpendingTrendChart.kt` | Line chart of spending over time |
| **SpendingPaceGauge** | `SpendingPaceGauge.kt` | Gauge chart for budget burn rate |
| **ChartMarker** | `ChartMarker.kt` | Chart data point marker |
| **ForecastTimeline** | `ForecastTimeline.kt` | Timeline visualization of forecast |
| **MoneyRadarWidget** | `dashboard/MoneyRadarWidget.kt` | Radar/spider chart |
| **PeriodGridView** | `PeriodGridView.kt` | Calendar grid for dates |
| **PeriodBlock** | `PeriodBlock.kt` | Individual period cell |
| **PeriodNavigationBar** | `PeriodNavigationBar.kt` | Period selector with arrows |

### 7.4 AI Components

| Component | File | Purpose |
|-----------|------|---------|
| **AssistantResultCard** | `ai/AssistantResultCard.kt` | AI assistant response display |
| **CategoryAssistCard** | `ai/CategoryAssistCard.kt` | AI category suggestion |
| **DedupeAssistCard** | `ai/DedupeAssistCard.kt` | Duplicate detection UI |
| **ReceiptAssistCard** | `ai/ReceiptAssistCard.kt` | Receipt scanning results |
| **ReceiptItemBreakdownCard** | `ai/ReceiptItemBreakdownCard.kt` | Item-level receipt data |
| **AiChatBubble** | `ai/AiChatBubble.kt` | Chat message bubble for AI assistant |
| **AiInsightsCard** | `ai/AiInsightsCard.kt` | AI-generated insights display |
| **AiRecommendationCard** | `ai/AiRecommendationCard.kt` | AI recommendation card |
| **AiTypingIndicator** | `ai/AiTypingIndicator.kt` | Typing indicator animation |

### 7.5 Common/Shared Components

| Component | File | Purpose |
|-----------|------|---------|
| **EmptyState** | `common/EmptyState.kt` | Default empty state UI |
| **EnhancedEmptyState** | `common/EnhancedEmptyState.kt` | Rich empty state with actions |
| **ErrorState** | `common/ErrorState.kt` | Error display with retry |
| **LoadingSkeleton** | `common/LoadingSkeleton.kt` | Placeholder loading animation |
| **ListSkeleton** | `common/LoadingSkeleton.kt` (inline composable) | List item skeleton loader |

### 7.6 Dialog/Sheet Components

| Component | File | Purpose |
|-----------|------|---------|
| **CategoryBreakdownSheet** | `CategoryBreakdownSheet.kt` | Modal category spending details |
| **RetroCategoryBreakdownSheet** | `RetroCategoryBreakdownSheet.kt` | Alternative category breakdown UI |
| **LocationCorrectionSheet** | `LocationCorrectionSheet.kt` | Fix location modal |
| **TransactionFilterSheet** | `transactions/TransactionFilterSheet.kt` | Advanced filter options |

### 7.7 Permission Dialogs

| Component | File | Purpose |
|-----------|------|---------|
| **LocationPermissionDialog** | `LocationPermissionDialog.kt` | Request location access |
| **NotificationPermissionDialog** | `NotificationPermissionDialog.kt` | Request notification access |

### 7.8 Navigation Components

| Component | File | Purpose |
|-----------|------|---------|
| **AppNavigationBar** | `AppNavigationBar.kt` | Bottom navigation bar (6 tabs) |
| **SmartFAB** | `MainActivity.kt` (inline) | Context-aware floating action button (add, scan, approve, clipboard, assistant) |

### 7.9 Feature Components

| Component | File | Purpose |
|-----------|------|---------|
| **TransferDirectionBadge** | `TransferDirectionBadge.kt` | Income/expense direction indicator |
| **PulseDot** | `PulseDot.kt` | Animated status indicator |
| **PrivacyBlockedCard** | `PrivacyBlockedCard.kt` | Typed privacy-blocked card with `PrivacyBlocked` API, semantics, testTag, optional settings navigation; consumed by PrivacySettingsScreen |
| **BentoCard** | `BentoCard.kt` | Grid card layout component |
| **RetroBudgetBlockPartyCard** | `RetroBudgetBlockPartyCard.kt` | Alternative budget card UI |
| **RetroTopCategoriesCard** | `RetroTopCategoriesCard.kt` | Alternative top categories card |
| **RetroTotalsDashboardCard** | `RetroTotalsDashboardCard.kt` | Alternative totals card |
| **DataQualityWarningChip** | `DataQualityWarningChip.kt` | Data quality warning chip (used on HomeScreen, BudgetScreen) |
| **PersonalityProfileCard** | `analytics/PersonalityProfileCard.kt` | Spending personality |
| **StatisticalVisualizations** | `analytics/StatisticalVisualizations.kt` | Advanced stat charts |

### 7.10 Feature Components (Form/Utility)

| Component | File | Purpose |
|-----------|------|---------|
| **FeatureComponents** | `feature/FeatureComponents.kt` | Reusable feature UI patterns |
| **FormComponents** | `feature/FormComponents.kt` | Form inputs (text, dropdown, etc.) |
| **MetricComponents** | `feature/MetricComponents.kt` | Metric display components |

### 7.11 Empty State Components

| Component | File | Purpose |
|-----------|------|---------|
| **EmptyStateAction** | `emptystate/EmptyStateAction.kt` | Contextual empty state actions |
| **ContextualActionRegistry** | `emptystate/ContextualActionRegistry.kt` | Empty state action registry |
| **DefaultEmptyStateRegistryInitializer** | `emptystate/DefaultEmptyStateRegistryInitializer.kt` | Empty-state registry bootstrap |
| **EmptyStatePresentationModule** | `emptystate/EmptyStatePresentationModule.kt` | Hilt module for empty state wiring |

---

## 8. NAVIGATION FLOW

### 8.1 Navigation Architecture

**Core System**: Sealed class `NavigationDestination` + `NavigationController` CompositionLocal

```
MainActivity
  └── MainScreen (Scaffold)
      ├── AnimatedContent (Tabs 0-5)
      ├── Bottom Bar (AppNavigationBar)
      ├── FAB (SmartFAB)
      └── NavigationDestination when-block (Feature screens)
```

### 8.2 Bottom Tab Navigation (Main Tabs)

```
Index 0: Home        (Dashboard)
Index 1: Activity    (Transactions)
Index 2: Review      (Pending review)
Index 3: Plan        (Budget)
Index 4: Analytics   (Insights)
Index 5: Map         (Spending Map)
```

**Back Stack Behavior**:
- Tabs are main destinations
- Switching tabs clears feature back stack
- Can navigate back from feature to last main tab

### 8.3 Feature Navigation (23 Features)

All features accessible via:
1. **Home Screen Widgets**: Tap widget → feature
2. **Features Menu**: Icon button → scrollable menu with all 22
3. **Tab Navigation**: Feature screen keeps back stack
4. **Deep Links**: Some support deep links (home, activity, review, plan, add, analytics, map)

**Back Navigation**:
- Back button returns to home tab
- Remembers previous main tab index

### 8.4 Overlay Navigation (Sheets/Modals)

These don't clear the main tab:

```
AddExpense               → Modal sheet over main tab
ScanReceipt            → Full screen over main tab
RecurringExpenses      → Full screen
ManualRecurringExpense → Full screen
Assistant              → Bottom sheet modal
```

### 8.5 Deep Link Support

**Scheme**: `expensetracker://`

| Host | Destination | Example |
|------|-------------|---------|
| `home` / `dashboard` | Home tab (0) | `expensetracker://home` |
| `activity` | Transactions tab (1) | `expensetracker://activity` |
| `review` | Review tab (2) | `expensetracker://review` |
| `plan` | Budget tab (3) | `expensetracker://plan` |
| `add` | Add Expense overlay | `expensetracker://add` |
| `analytics` | Analytics tab (4) | `expensetracker://analytics` |
| `map` | Map tab (5) | `expensetracker://map` |

**Query Parameters**:
- `briefingKey`: AI briefing identifier (for home deep link)

---

## 9. THEME & STYLING

**File**: `ui/theme/Theme.kt` + `Dimens.kt`

### Color System (Midnight Navy Scheme)

#### Semantic Colors
```kotlin
BaseNavy         = #0F172A    // Background
SurfaceLight     = #1E293B    // Surface
PrimaryIndigo    = #6366F1    // Primary action
PrimaryLight     = #818CF8    // Light primary

SuccessGreen     = #10B981    // Success/on-track
WarningOrange    = #F97316    // Warning/over-pace
DangerRed        = #EF4444    // Critical/exceeded
TextPrimary      = #F1F5F9    // Text
TextSecondary    = #94A3B8    // Secondary text
TextMuted        = #CC94A3B8  // Muted (80% alpha)
```

#### Budget Health Colors
- **On Track**: SuccessGreen
- **Warning**: WarningOrange
- **Critical**: DangerRed
- **Exceeded**: #FF5722

#### Status Palette
- StatusGreen, StatusGreenLight
- StatusYellow, StatusYellowLight, StatusOrangeLight
- StatusRed, StatusDarkRed
- StatusGreenAlt, StatusOrangeAlt, StatusRedAlt

#### Glass Morphism
- GlassSurface = #661E293B (40% alpha)
- GlassBorder = #1A94A3B8 (10% alpha)

### Typography

**Features**: Tabular lining figures ("tnum") for consistent number alignment

| Level | Size | Weight | Line Height |
|-------|------|--------|-------------|
| Display Large | 57sp | Bold | 64sp |
| Display Medium | 45sp | Bold | 52sp |
| Display Small | 36sp | Bold | 44sp |
| Headline Large | 32sp | SemiBold | 40sp |
| Headline Medium | 28sp | SemiBold | 36sp |
| Headline Small | 24sp | SemiBold | 32sp |
| Title Large | 22sp | Bold | 28sp |
| Title Medium | 16sp | Bold | 24sp |
| Title Small | 14sp | Bold | 20sp |
| Body Large | 16sp | Normal | 24sp |
| Body Medium | 14sp | Normal | 20sp |
| Body Small | 12sp | Normal | 16sp |
| Label Large | 14sp | Medium | 20sp |
| Label Medium | 12sp | Medium | 16sp |
| Label Small | 11sp | Medium | 16sp |

### Dimens (Spacing)

Standard Material 3 padding/spacing scales:
- 0dp, 2dp, 4dp, 8dp, 12dp, 16dp, 20dp, 24dp, 28dp, 32dp, etc.
- Card corner radius: 12dp (default), 8dp, 16dp, 20dp

---

## 10. MAPPERS & UTILITIES

### UI Mappers

| Mapper | File | Purpose |
|--------|------|---------|
| **DashboardWidgetUiMapper** | `mappers/DashboardWidgetUiMapper.kt` | Domain models → UI models |
| **TransactionFilterUiMapper** | `mappers/TransactionFilterUiMapper.kt` | Filter domain → UI state |
| **MonteCarloBudgetImpactUiMapper** | `mappers/MonteCarloBudgetImpactUiMapper.kt` | Budget impact forecast → UI models |

### UI Utilities

| Util | File | Purpose |
|------|------|---------|
| **ColorExtensions** | `util/ColorExtensions.kt` | Color utility functions |
| **HapticFeedback** | `util/HapticFeedback.kt` | Haptic feedback (light/standard/heavy) |
| **ModifierExtensions** | `util/ModifierExtensions.kt` | Common modifier builders |
| **ClipboardAmountParser** | `util/ClipboardAmountParser.kt` | Parse amounts from clipboard |

---

## 11. FEATURE INTEGRATION

**File**: `ui/integration/FeatureIntegration.kt`

Handles configuration-driven feature display and integration with Home screen, Features Menu, and support routing.

---

## 12. UI PATTERNS & CONVENTIONS

### Composable Naming
- `<Name>Screen()` - Full screen composables
- `<Name>Sheet()` - Modal bottom sheets
- `<Name>Dialog()` - Dialog composables
- `<Name>Card()` - Card/widget components
- `<Name>Widget()` - Dashboard widgets

### Navigation Callbacks
- `onDismiss()` - Close overlay
- `onNavigateTo<Feature>()` - Navigate to feature
- `onNavigateBack()` - Go back

### State Management
- ViewModel per screen with `.collectAsState()`
- Sealed classes for UI state
- CompositionLocal for navigation controller

### Dialog/Sheet Patterns
- Modal sheets for content entry (AddExpense, Assistant)
- Dialogs for confirmations/selections
- Full-screen modals for complex flows

### Error Handling
- ErrorState component for failures
- Snackbar for user feedback
- Graceful fallbacks for missing data

### Empty States
- EmptyState for no data
- EnhancedEmptyState with CTA buttons
- Contextual actions via ContextualActionRegistry

### Loading States
- LoadingSkeleton for placeholders
- ListSkeleton for list items
- Shimmer effects on cards

---

## 13. ACCESSIBILITY (a11y)

### Content Descriptions
- All icons have `contentDescription` (stringResource)
- Badges for status (pending count)
- Semantic markup using `semantics` modifier

### Keyboard Navigation
- Bottom bar fully keyboard navigable
- Dialog/sheet focus handling
- Back handler for system back button

### Text Scaling
- Material 3 dynamic typography
- Responsive text sizes
- Multi-line support for labels

---

## 14. PERMISSIONS & MANIFEST

**File**: `app/src/main/AndroidManifest.xml`

### Deep Link Intent Filters
```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.BROWSABLE" />
    <data android:scheme="expensetracker" android:host="home" />
    <data android:scheme="expensetracker" android:host="dashboard" />
    <!-- ... more hosts ... -->
</intent-filter>
```

### Required Permissions
- `ACCESS_FINE_LOCATION` (map/location features)
- `ACCESS_COARSE_LOCATION`
- `CAMERA` (receipt scanning)
- `POST_NOTIFICATIONS` (bill reminders)
- `INTERNET` (API calls, map tiles)

### Services
- `NotificationCaptureService` (notification listener)
- `BootReceiver` (service restart)

---

## 15. ORPHANED SCREENS (Non-Navigated)

All screens are navigated to via NavigationDestination with one exception:
- **PrivacySettingsScreen** has no standalone `NavigationDestination` entry — it is accessible only from the Settings gear icon (treated as a management sub-screen).

---

## 16. SUMMARY STATISTICS

| Category | Count |
|----------|-------|
| **Main Tabs** | 6 |
| **Feature Screens** | 23 |
| **Overlay Screens** | 6 |
| **Management Screens** | 3 (AiSettings, CategoryManagement, PrivacySettings—settings-only) |
| **Debug Screens** | 3 |
| **Total Screen Files** | 38 |
| **Component Files** | 59 |
| **Total UI Files** | 164 |
| **ViewModels** | 38 (incl. MainViewModel) |
| **Navigation Files** | 5 |
| **Deep Link Hosts** | 8 |
| **UI Mapper Files** | 3 |
| **UI Theme Files** | 2 |
| **UI Model Files** | 4 |
| **UI Utility Files** | 7 |
| **Screenshots/Sheets** | 3 (AddExpenseSheet, AssistantSheet, TransactionFilterSheet) |

---

## 17. KEY FILES QUICK REFERENCE

| File Path | Purpose |
|-----------|---------|
| `ui/MainActivity.kt` | App entry, scaffold, tab routing |
| `ui/MainViewModel.kt` | Main app state, navigation requests |
| `ui/navigation/NavigationDestination.kt` | Sealed class - all destinations |
| `ui/navigation/NavigationController.kt` | Navigation state & actions |
| `ui/navigation/FeatureConfig.kt` | Feature menu configuration |
| `ui/components/AppNavigationBar.kt` | Bottom navigation bar |
| `ui/screens/home/HomeScreen.kt` | Dashboard with widgets |
| `ui/screens/transactions/TransactionsScreen.kt` | Transaction list & filtering |
| `ui/screens/review/ReviewScreen.kt` | Pending review workflow |
| `ui/screens/budget/BudgetScreen.kt` | Budget management |
| `ui/screens/analytics/AnalyticsScreen.kt` | Analytics dashboard |
| `ui/screens/map/SpendingMapScreen.kt` | Geo-visualization |
| `ui/screens/addexpense/AddExpenseSheet.kt` | Expense entry form |
| `ui/screens/assistant/AssistantSheet.kt` | AI chat interface |
| `ui/theme/Theme.kt` | Color scheme, typography |
| `ui/theme/Dimens.kt` | Spacing dimensions |

---

## 18. NOTES

1. **Modern Navigation**: Uses sealed classes + CompositionLocal instead of NavHost
2. **Config-Driven Features**: current feature set via FeatureConfig.kt
3. **Type-Safe Navigation**: No string-based routes
4. **Back Stack Management**: Tracked per feature, clears on tab switch
5. **State Persistence**: Navigation state saved via rememberSaveable
6. **Glass Morphism**: Supported via GlassSurface/GlassBorder colors
7. **Accessibility**: Material 3 standards compliance
8. **No NavHost Fragments**: Pure Compose architecture
9. **Feature Badges**: New/Beta badges in menu
10. **Analytics Ready**: NavigationEvent sealed class for tracking

---

**End of Mapping Document**
