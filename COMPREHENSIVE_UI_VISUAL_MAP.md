# ExpenseTracker Frontend - Visual Navigation Map

## APPLICATION FLOW DIAGRAM

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           EXPENSE TRACKER APP                               │
└─────────────────────────────────────────────────────────────────────────────┘
                                    │
                      ┌─────────────┴─────────────┐
                      │                           │
            ┌─────────▼─────────┐     ┌───────────▼──────────┐
            │   MainActivity    │     │  NavigationController│
            │   (Scaffold)      │     │  (CompositionLocal)  │
            └─────────┬─────────┘     └───────────────────────┘
                      │
        ┌─────────────┼─────────────┐
        │             │             │
   ┌────▼──┐    ┌────▼──┐    ┌────▼──┐
   │ TabBar│    │ FAB   │    │Screen │
   │(6tabs)│    │Menu   │    │Router │
   └────┬──┘    └────┬──┘    └────┬──┘
        │            │            │
   ┌────┴──────┬─────┴────┬───────┴─────────────┐
   │            │          │                    │
┌──▼──┐  ┌────▼───┐ ┌────▼───┐  ┌───────────────▼──────────┐
│Tabs │  │Overlays│ │Features│  │NavigationDestination     │
│0-5  │  │(Sheets)│ │(22)    │  │Sealed Class Router       │
└──┬──┘  └────┬───┘ └────┬───┘  └───────────────┬──────────┘
   │          │          │                      │
   │     ┌────┴──┐       │                      │
   │     │       │       │         ┌────────────┴─────────────────────────────┐
   └─────┼───────┼───────┼─────────┤ Routes to:                               │
         │       │       │         │ - Main Tabs (0-5) via navigateToTab()   │
         │       │       │         │ - Features via NavigationDestination    │
         │       │       │         │ - Overlays via NavigationDestination    │
         │       │       │         │ - Back stack for feature screens        │
         │       │       │         └────────────────────────────────────────────┘
         │       │       │
    ┌────▼──┐   │       │
    │Add    │   │       │
    │Exp.   │   │       └─► Feature Menu (22 features + settings)
    │Sheet  │   │           │
    └───────┘   │           ├─► SavingsGoals
                │           ├─► CarbonFootprint
            ┌───▼────┐       ├─► WarrantyTracker
            │Assistant│      ├─► PriceProtection
            │ Sheet   │      ├─► BillNegotiation
            └────────┘       ├─► SmartSearch
                              ├─► ReceiptMatching
            ┌──────────┐      ├─► InvestmentPortfolio
            │Receipt   │      ├─► BankConnections
            │Scan      │      ├─► BillReminders
            │Screen    │      ├─► SpendingChallenges
            └──────────┘      ├─► AdvancedAnalytics
                              ├─► CashFlowCalendar
                              ├─► LifestyleInflation
                              ├─► SplitTemplates
                              ├─► VisualSplitEditor
                              ├─► CurrencyManagement
                              ├─► SubscriptionManagement
                              ├─► TaxConfiguration
                              ├─► ExportOptions
                              ├─► RecurringExpenses
                              ├─► ManualRecurringExpense
                              ├─► SharedExpenseGroups
                              ├─► AiSettings
                              └─► CategoryManagement
```

---

## BOTTOM NAVIGATION TAB STRUCTURE

```
┌────────────────────────────────────────────────────────────────────────┐
│                          TAB LAYOUT (Scaffold)                         │
├────────────────────────────────────────────────────────────────────────┤
│                                                                        │
│                    AnimatedContent {Tab Content}                      │
│                                                                        │
│  ┌───────────────────────────────────────────────────────────────┐  │
│  │ When (selectedTab)                                            │  │
│  │   0 → HomeScreen()          [Dashboard]                      │  │
│  │   1 → TransactionsScreen()  [Activity]                       │  │
│  │   2 → ReviewScreen()        [Review]                         │  │
│  │   3 → BudgetScreen()        [Plan]                           │  │
│  │   4 → AnalyticsScreen()     [Insights]                       │  │
│  │   5 → SpendingMapScreen()   [Map]                            │  │
│  └───────────────────────────────────────────────────────────────┘  │
│                                                                        │
├────────────────────────────────────────────────────────────────────────┤
│ [🏠 Home] [📋 Activity] [✓ Review] [📅 Plan] [📊 Analytics] [🗺 Map] │
│     0         1           2(badge)    3         4           5       │
└────────────────────────────────────────────────────────────────────────┘
        ▲                                                           ▲
        │                                                           │
        └─ Badge: Pending review count on Review tab              │
        │                                                           │
        └─ FAB positioned above navigation bar on right
```

---

## HOME SCREEN (Tab 0) - DASHBOARD COMPOSITION

```
┌──────────────────────────────────────────────────────────────────────┐
│ HOME SCREEN - Dashboard                                              │
├──────────────────────────────────────────────────────────────────────┤
│ TopAppBar                                                            │
│ ┌──────────────────────────────────────────────────────────────────┐│
│ │ [●] Status  │ Dashboard  │ [⚙] [🎯] [☰]                        ││
│ │ (PulseDot)  │ Title      │  Sett  Feat  Menu                   ││
│ └──────────────────────────────────────────────────────────────────┘│
│                                                                       │
│ Body (Scrollable LazyVerticalGrid)                                  │
│ ┌──────────────────────────────────────────────────────────────────┐│
│ │                                                                  ││
│ │ Period Navigation [◀ Jan 2025 ▶]                                ││
│ │                                                                  ││
│ │ ┌────────────────────────────────────────────────────────────┐ ││
│ │ │ Totals Dashboard Card                                      │ ││
│ │ │  💰 Income: $5,200  💸 Expense: $3,100  📊 Net: $2,100    │ ││
│ │ └────────────────────────────────────────────────────────────┘ ││
│ │                                                                  ││
│ │ ┌────────────────────────────────────────────────────────────┐ ││
│ │ │ Budget Block Party Card (Grid)                             │ ││
│ │ │ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐        │ ││
│ │ │ │ 🍽 Food      │ │ 🚗 Transport │ │ 🏠 Housing   │        │ ││
│ │ │ │ $150/$300    │ │ $120/$200    │ │ $800/$1000   │        │ ││
│ │ │ │ ████░░░░  50% │ │ ██████░░░░ 60% │ │ ████████░░ 80%      │ ││
│ │ │ └──────────────┘ └──────────────┘ └──────────────┘        │ ││
│ │ └────────────────────────────────────────────────────────────┘ ││
│ │                                                                  ││
│ │ ┌────────────────────────────────────────────────────────────┐ ││
│ │ │ Financial Weather Card        [☀️ Sunny]                   │ ││
│ │ │ Health: Excellent (92%)                                    │ ││
│ │ └────────────────────────────────────────────────────────────┘ ││
│ │                                                                  ││
│ │ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐             ││
│ │ │ Runway       │ │ Stress       │ │ Monte Carlo  │             ││
│ │ │ 8.3 months   │ │ Forecast: 🟢 │ │ P95: $8,200  │             ││
│ │ └──────────────┘ └──────────────┘ └──────────────┘             ││
│ │                                                                  ││
│ │ ┌────────────────────────────────────────────────────────────┐ ││
│ │ │ Health Score Widget V2                                     │ ││
│ │ │ ⭐⭐⭐⭐⭐ 92/100                                               │ ││
│ │ └────────────────────────────────────────────────────────────┘ ││
│ │                                                                  ││
│ │ ┌────────────────────────────────────────────────────────────┐ ││
│ │ │ Category Breakdown (Tap to expand) 🔻                      │ ││
│ │ │ Food: $150 | Transport: $120 | Housing: $800 | ...        │ ││
│ │ └────────────────────────────────────────────────────────────┘ ││
│ │                                                                  ││
│ │ ┌────────────────────────────────────────────────────────────┐ ││
│ │ │ Recommendations (AI Cards)                                 │ ││
│ │ │ • Reduce transport spending (15% above average)            │ ││
│ │ │ • Add to savings goal (on track 87%)                       │ ││
│ │ └────────────────────────────────────────────────────────────┘ ││
│ │                                                                  ││
│ └──────────────────────────────────────────────────────────────────┘│
│                                                                       │
└──────────────────────────────────────────────────────────────────────┘
```

---

## TRANSACTIONS SCREEN (Tab 1) - LIST WITH FILTER

```
┌──────────────────────────────────────────────────────────────────────┐
│ TRANSACTIONS SCREEN - Activity                                        │
├──────────────────────────────────────────────────────────────────────┤
│ TopAppBar                                                            │
│ ┌──────────────────────────────────────────────────────────────────┐│
│ │ [◀] Transactions  [🔍] [⋯]                                       ││
│ │                                                                  ││
│ │ Filter Chips (Scrollable)                                       ││
│ │ [Category ▼] [Date ▼] [Amount ▼] [Location ▼] [+]              ││
│ └──────────────────────────────────────────────────────────────────┘│
│                                                                       │
│ Transaction List (Grouped by Date)                                  │
│ ┌──────────────────────────────────────────────────────────────────┐│
│ │ 📅 Today                                                         ││
│ │  ┌────────────────────────────────────────────────────────────┐ ││
│ │  │ Starbucks          [☕ Coffee]        -$5.50       ↙ 2:30pm│ ││
│ │  │ ┌─────────────────────────────────────────────────────────┤ ││
│ │  │ │ Location: Main St, Downtown | Edit Category            │ ││
│ │  │ └─────────────────────────────────────────────────────────┘ ││
│ │  └────────────────────────────────────────────────────────────┘ ││
│ │                                                                  ││
│ │  ┌────────────────────────────────────────────────────────────┐ ││
│ │  │ Whole Foods        [🛒 Groceries]      -$87.23      ↙ 1:15pm│ ││
│ │  └────────────────────────────────────────────────────────────┘ ││
│ │                                                                  ││
│ │ 📅 Yesterday                                                    ││
│ │  ┌────────────────────────────────────────────────────────────┐ ││
│ │  │ Gas Station        [⛽ Transport]      -$45.00      ↙ 6:45pm│ ││
│ │  └────────────────────────────────────────────────────────────┘ ││
│ │                                                                  ││
│ └──────────────────────────────────────────────────────────────────┘│
│                                                                       │
└──────────────────────────────────────────────────────────────────────┘
```

---

## REVIEW SCREEN (Tab 2) - APPROVAL WORKFLOW

```
┌──────────────────────────────────────────────────────────────────────┐
│ REVIEW SCREEN - Pending Transactions                                 │
├──────────────────────────────────────────────────────────────────────┤
│ TopAppBar                                                            │
│ ┌──────────────────────────────────────────────────────────────────┐│
│ │ [◀] Review (5 pending)                                           ││
│ └──────────────────────────────────────────────────────────────────┘│
│                                                                       │
│ Pending Items (Card-based)                                          │
│ ┌──────────────────────────────────────────────────────────────────┐│
│ │ ┌──────────────────────────────────────────────────────────────┐││
│ │ │ Transaction: Starbucks                                       │││
│ │ │ Suggested Category: ☕ Coffee (92% confidence)              │││
│ │ │ Amount: -$5.50  |  Date: Today 2:30pm                       │││
│ │ │                                                              │││
│ │ │ [✓ Approve]  [📝 Edit]  [✗ Reject]                          │││
│ │ └──────────────────────────────────────────────────────────────┘││
│ │                                                                  ││
│ │ ┌──────────────────────────────────────────────────────────────┐││
│ │ │ Transaction: Netflix Charge                                 │││
│ │ │ Suggested Category: 🎬 Entertainment (87% confidence)       │││
│ │ │ Amount: -$15.99  |  Date: Yesterday 12:01am                │││
│ │ │ [✓ Approve]  [📝 Edit]  [✗ Reject]                          │││
│ │ └──────────────────────────────────────────────────────────────┘││
│ │                                                                  ││
│ └──────────────────────────────────────────────────────────────────┘│
│                                                                       │
│ FAB (in this tab): [✓ Approve All]                                  │
│                                                                       │
└──────────────────────────────────────────────────────────────────────┘
```

---

## BUDGET SCREEN (Tab 3) - PLANNING

```
┌──────────────────────────────────────────────────────────────────────┐
│ BUDGET SCREEN - Plan                                                 │
├──────────────────────────────────────────────────────────────────────┤
│ TopAppBar                                                            │
│ ┌──────────────────────────────────────────────────────────────────┐│
│ │ [◀] Budget (Jan 2025)  [+]                                       ││
│ └──────────────────────────────────────────────────────────────────┘│
│                                                                       │
│ Budget List                                                          │
│ ┌──────────────────────────────────────────────────────────────────┐│
│ │ ┌──────────────────────────────────────────────────────────────┐││
│ │ │ 🍽 Food                                   $150 / $300        │││
│ │ │ ████████░░░░░░░░░░░░░░░░░░░░░░ 50%  On Track ✓            │││
│ │ │ Next: -$15 in 4 days (avg: -$37/day)                       │││
│ │ │ [Tap for Details] [⋯ Menu]                                  │││
│ │ └──────────────────────────────────────────────────────────────┘││
│ │                                                                  ││
│ │ ┌──────────────────────────────────────────────────────────────┐││
│ │ │ 🚗 Transport                              $120 / $200        │││
│ │ │ ██████████░░░░░░░░░░░░░░░░░░░░░░░░░░░░░ 60%  Over Pace ⚠│││
│ │ │ Next: Approaching limit in 8 days                           │││
│ │ │ [Tap for Forecast] [⋯ Menu]                                 │││
│ │ └──────────────────────────────────────────────────────────────┘││
│ │                                                                  ││
│ │ ┌──────────────────────────────────────────────────────────────┐││
│ │ │ 🏠 Housing                               $800 / $1000       │││
│ │ │ ████████████████░░░░░░░░░░░░░░░░░░░░░░░ 80%  Warning ⚠  │││
│ │ │ Next: Likely exceed by $50 (5 days remaining)               │││
│ │ │ [Tap for Forecast] [⋯ Menu]                                 │││
│ │ └──────────────────────────────────────────────────────────────┘││
│ │                                                                  ││
│ └──────────────────────────────────────────────────────────────────┘│
│                                                                       │
│ FAB: [+ Add Budget]                                                 │
│                                                                       │
└──────────────────────────────────────────────────────────────────────┘
```

---

## ANALYTICS SCREEN (Tab 4) - INSIGHTS

```
┌──────────────────────────────────────────────────────────────────────┐
│ ANALYTICS SCREEN - Insights                                          │
├──────────────────────────────────────────────────────────────────────┤
│ TopAppBar                                                            │
│ ┌──────────────────────────────────────────────────────────────────┐│
│ │ [◀] Analytics (Jan 2025)  [⋯]                                   ││
│ └──────────────────────────────────────────────────────────────────┘│
│                                                                       │
│ Analytics Dashboards                                                │
│ ┌──────────────────────────────────────────────────────────────────┐│
│ │ ┌──────────────────────────────────────────────────────────────┐││
│ │ │ Spending Breakdown (Pie Chart) [Tap to drill down]          │││
│ │ │            ╱  🍽 Food                                        │││
│ │ │         ╱      🚗 Transport    30%                          │││
│ │ │     ╱          🏠 Housing                                    │││
│ │ │   ╱            💼 Utilities   10%                           │││
│ │ │ ╱                                                             │││
│ │ │ Category: Food 38% | Transport 30% | Housing 20% | Util 12% │││
│ │ └──────────────────────────────────────────────────────────────┘││
│ │                                                                  ││
│ │ ┌──────────────────────────────────────────────────────────────┐││
│ │ │ Spending Trend (Line Chart)                                 │││
│ │ │ $3200 ┐                                                      │││
│ │ │ $3000 │  ╱╲                                                  │││
│ │ │ $2800 │ ╱  ╲    ╱╲                                           │││
│ │ │ $2600 ├╱────╲──╱  ╲╱─                                        │││
│ │ │        └─────────────────────────────────────────────       │││
│ │ │        1  2  3  4  5  6  7  8  9 10 11 12 13 14 15       │││
│ │ └──────────────────────────────────────────────────────────────┘││
│ │                                                                  ││
│ │ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐            ││
│ │ │ Pace     │ │ Top 3    │ │ Balance  │ │ Advanced │            ││
│ │ │ 🔴 95%   │ │ 1. Food  │ │ +$2,100  │ │ [View]   │            ││
│ │ └──────────┘ └──────────┘ └──────────┘ └──────────┘            ││
│ │                                                                  ││
│ └──────────────────────────────────────────────────────────────────┘│
│                                                                       │
└──────────────────────────────────────────────────────────────────────┘
```

---

## SPENDING MAP SCREEN (Tab 5) - GEO-VISUALIZATION

```
┌──────────────────────────────────────────────────────────────────────┐
│ SPENDING MAP - Location-Based Analysis                               │
├──────────────────────────────────────────────────────────────────────┤
│ TopAppBar                                                            │
│ ┌──────────────────────────────────────────────────────────────────┐│
│ │ [◀] Map (Jan 2025)  [Filter] [Legend]                            ││
│ └──────────────────────────────────────────────────────────────────┘│
│                                                                       │
│ OSMDroid Map View                                                    │
│ ┌──────────────────────────────────────────────────────────────────┐│
│ │  ┌────────────────────────────────────────────────────────────┐ ││
│ │  │  🗺 OpenStreetMap                                          │ ││
│ │  │                                                            │ ││
│ │  │          ☕ ☕                    🛒 🛒                     │ ││
│ │  │       (Downtown)              (Suburbs)                │ ││
│ │  │                                                            │ ││
│ │  │                                                            │ ││
│ │  │         ⛽                           🍽                   │ ││
│ │  │      (Highway)                   (Restaurant)          │ ││
│ │  │                                                            │ ││
│ │  │  [Legend: 🔴=Food 🟠=Transport 🟡=Utilities]               │ ││
│ │  └────────────────────────────────────────────────────────────┘ ││
│ │                                                                  ││
│ │ Bottom Sheet (Collapsed)                                         ││
│ │ ┌────────────────────────────────────────────────────────────┐ ││
│ │ │ Nearby Shops & Spending Insights                           │ ││
│ │ │ • Starbucks (Downtown): 12 visits, $65 total              │ ││
│ │ │ • Whole Foods: 3 visits, $260 total                       │ ││
│ │ │ [Tap marker to expand]                                    │ ││
│ │ └────────────────────────────────────────────────────────────┘ ││
│ │                                                                  ││
│ └──────────────────────────────────────────────────────────────────┘│
│                                                                       │
└──────────────────────────────────────────────────────────────────────┘
```

---

## ADD EXPENSE OVERLAY - MODAL SHEET

```
Modal Sheet (Bottom-up animation)
┌──────────────────────────────────────────────────────────────────────┐
│ ADD EXPENSE SHEET                                                    │
├──────────────────────────────────────────────────────────────────────┤
│ Handle Bar (Drag to dismiss)  [X Close]                             │
├──────────────────────────────────────────────────────────────────────┤
│                                                                       │
│ Amount Entry                                                         │
│ ┌──────────────────────────────────────────────────────────────────┐│
│ │ Amount: [₹ 150.50 ▼]  [Clear]  [Clipboard: +$55 ▼]             ││
│ │         (Auto-filled from clipboard if detected)                 ││
│ │                                                                  ││
│ │ Date: [Today ▼]     Time: [2:30 PM ▼]                           ││
│ │                                                                  ││
│ │ Category: [🍽 Select Category ▼]                                ││
│ │                                                                  ││
│ │ Merchant: [Type or search...          ]                          ││
│ │                                                                  ││
│ │ Notes: [Add notes...                  ]                          ││
│ │                                                                  ││
│ │ ☑ Recurring  [Weekly ▼]  [Until...  ▼]                          ││
│ │                                                                  ││
│ │ ☐ Split Expense  [Visual Editor]  [# Participants: 2]           ││
│ │                                                                  ││
│ │ [📎 Attach Receipt]                                             ││
│ │                                                                  ││
│ └──────────────────────────────────────────────────────────────────┘│
│                                                                       │
│ Action Buttons                                                       │
│ ┌──────────────────────────────────────────────────────────────────┐│
│ │ [Cancel]  [Save Expense]                                         ││
│ └──────────────────────────────────────────────────────────────────┘│
│                                                                       │
└──────────────────────────────────────────────────────────────────────┘
```

---

## AI ASSISTANT SHEET

```
Modal Sheet (Bottom-up animation)
┌──────────────────────────────────────────────────────────────────────┐
│ AI ASSISTANT 🤖                                                      │
├──────────────────────────────────────────────────────────────────────┤
│ Handle Bar (Drag to dismiss)                                         │
├──────────────────────────────────────────────────────────────────────┤
│                                                                       │
│ Chat History (Scrollable)                                           │
│ ┌──────────────────────────────────────────────────────────────────┐│
│ │                                                                  ││
│ │ Assistant:                                                       ││
│ │ ┌────────────────────────────────────────────────────────────┐ ││
│ │ │ Hi! 👋 I'm your expense assistant. How can I help?       │ ││
│ │ │                                                            │ ││
│ │ │ Quick Tips:                                               │ ││
│ │ │ • "How much did I spend on food?"                         │ ││
│ │ │ • "Analyze my spending trend"                             │ ││
│ │ │ • "Suggest budget adjustments"                            │ ││
│ │ └────────────────────────────────────────────────────────────┘ ││
│ │                                                                  ││
│ │ User:                                                            ││
│ │ ┌────────────────────────────────────────────────────────────┐ ││
│ │ │ How much did I spend on coffee last month?               │ ││
│ │ └────────────────────────────────────────────────────────────┘ ││
│ │                                                                  ││
│ │ Assistant:                                                       ││
│ │ ┌────────────────────────────────────────────────────────────┐ ││
│ │ │ You spent $64.50 on coffee last month across 12 visits. │ ││
│ │ │ This is 18% higher than your average.                    │ ││
│ │ │                                                            │ ││
│ │ │ [Bar Chart: Coffee spend trend]                           │ ││
│ │ │                                                            │ ││
│ │ │ 🎯 Suggestion: Consider reducing coffee purchases by     │ ││
│ │ │ 20% next month for better budget alignment.              │ ││
│ │ │ [✓ Accept] [✗ Dismiss]                                    │ ││
│ │ └────────────────────────────────────────────────────────────┘ ││
│ │                                                                  ││
│ └──────────────────────────────────────────────────────────────────┘│
│                                                                       │
│ Message Input                                                        │
│ ┌──────────────────────────────────────────────────────────────────┐│
│ │ [Type your question...                                      ] [→] ││
│ │ Suggested: "Budget review" | "Show trends" | "Smart tips"     ││
│ └──────────────────────────────────────────────────────────────────┘│
│                                                                       │
└──────────────────────────────────────────────────────────────────────┘
```

---

## FEATURE SCREENS - EXAMPLE: SAVINGS GOALS

```
┌──────────────────────────────────────────────────────────────────────┐
│ SAVINGS GOALS FEATURE SCREEN                                         │
├──────────────────────────────────────────────────────────────────────┤
│ TopAppBar                                                            │
│ ┌──────────────────────────────────────────────────────────────────┐│
│ │ [◀ Back]  Savings Goals  [+]                                     ││
│ └──────────────────────────────────────────────────────────────────┘│
│                                                                       │
│ Goals List                                                           │
│ ┌──────────────────────────────────────────────────────────────────┐│
│ │ ┌──────────────────────────────────────────────────────────────┐││
│ │ │ 🏖️ Vacation Fund                                             │││
│ │ │ $3,500 / $5,000 (70%)                                        │││
│ │ │ ████████████████░░░░░░░░░░░░░░░░░░░░                        │││
│ │ │ Target: June 2025  |  $500 needed                            │││
│ │ │ [Edit] [Delete] [View Details]                              │││
│ │ └──────────────────────────────────────────────────────────────┘││
│ │                                                                  ││
│ │ ┌──────────────────────────────────────────────────────────────┐││
│ │ │ 🎓 Emergency Fund                                            │││
│ │ │ $8,200 / $10,000 (82%)                                       │││
│ │ │ ████████████████████████░░░░░░░░░░░░░░░░░░░                 │││
│ │ │ Target: Ongoing  |  $1,800 needed                            │││
│ │ │ [Edit] [Delete] [View Details]                              │││
│ │ └──────────────────────────────────────────────────────────────┘││
│ │                                                                  ││
│ └──────────────────────────────────────────────────────────────────┘│
│                                                                       │
│ FAB: [+ Add Goal]                                                   │
│                                                                       │
└──────────────────────────────────────────────────────────────────────┘
```

---

## NAVIGATION STATE MACHINE

```
┌─────────────────────────────────────────────────────────────────┐
│          NavigationDestination (Sealed Class)                   │
│                                                                 │
│  Represents ALL possible navigation targets in the app          │
│                                                                 │
│  ├─ Main Tabs (6)                                              │
│  │  ├─ Home                                                    │
│  │  ├─ Transactions                                            │
│  │  ├─ Review                                                  │
│  │  ├─ Budget                                                  │
│  │  ├─ Analytics                                               │
│  │  └─ SpendingMap                                             │
│  │                                                              │
│  ├─ Overlay Screens (4)                                        │
│  │  ├─ AddExpense                                              │
│  │  ├─ ScanReceipt                                             │
│  │  ├─ RecurringExpenses                                       │
│  │  ├─ ManualRecurringExpense                                  │
│  │  └─ Assistant                                               │
│  │                                                              │
│  ├─ Feature Screens (22)                                       │
│  │  ├─ SavingsGoals                                            │
│  │  ├─ CarbonFootprint                                         │
│  │  ├─ WarrantyTracker                                         │
│  │  ├─ PriceProtection                                         │
│  │  ├─ BillNegotiation                                         │
│  │  ├─ SmartSearch                                             │
│  │  ├─ ReceiptMatching                                         │
│  │  ├─ InvestmentPortfolio                                     │
│  │  ├─ BankConnections                                         │
│  │  ├─ BillReminders                                           │
│  │  ├─ SpendingChallenges                                      │
│  │  ├─ AdvancedAnalytics                                       │
│  │  ├─ CashFlowCalendar                                        │
│  │  ├─ LifestyleInflation                                      │
│  │  ├─ SplitTemplates                                          │
│  │  ├─ VisualSplitEditor(expense, templateId)                  │
│  │  ├─ CurrencyManagement                                      │
│  │  ├─ SubscriptionManagement                                  │
│  │  ├─ TaxConfiguration                                        │
│  │  ├─ ExportOptions                                           │
│  │  └─ SharedExpenseGroups                                     │
│  │                                                              │
│  ├─ Management Screens (2)                                     │
│  │  ├─ AiSettings                                              │
│  │  └─ CategoryManagement                                      │
│  │                                                              │
│  └─ Parametric Screens (2)                                     │
│     ├─ BudgetForecasting(budget)                               │
│     └─ VisualSplitEditor(expense, templateId)                  │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘

                            │
                            ▼

┌─────────────────────────────────────────────────────────────────┐
│         NavigationController (State Management)                 │
│                                                                 │
│  • currentDestination: NavigationDestination                   │
│  • backStack: ArrayDeque<NavigationDestination>                │
│  • previousMainTab: Int?                                       │
│                                                                 │
│  Functions:                                                     │
│  • navigateTo(destination)     - Add to back stack             │
│  • navigateBack()              - Pop from back stack           │
│  • navigateToTab(index: 0-5)   - Clear back stack, go to tab  │
│  • getCurrentTabIndex()        - Get current tab index         │
│  • isCurrent(destination)      - Check current destination    │
│  • isOnMainTab()               - Check if on main tab          │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘

                            │
                            ▼

        CompositionLocal (LocalNavigationController)
        
                            │
                            ▼

         Used throughout app via:
         val navigation = LocalNavigationController.current
```

---

## COLOR PALETTE - MIDNIGHT NAVY SCHEME

```
┌──────────────────────────────────────────────────────────────────────┐
│ Primary Colors                                                       │
├──────────────────────────────────────────────────────────────────────┤
│ Base Navy        #0F172A ███████████████ Background               │
│ Surface Light    #1E293B ███████████████ Cards/Surface            │
│ Primary Indigo   #6366F1 ███████████████ Buttons/Links            │
│ Primary Light    #818CF8 ███████████████ Light Primary            │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│ Semantic Status Colors                                               │
├──────────────────────────────────────────────────────────────────────┤
│ Success Green    #10B981 ███████████████ On-Track, Good            │
│ Warning Orange   #F97316 ███████████████ Over-Pace, Caution        │
│ Danger Red       #EF4444 ███████████████ Critical, Exceeded        │
│ Info Blue        #3B82F6 ███████████████ Information               │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│ Text Colors                                                          │
├──────────────────────────────────────────────────────────────────────┤
│ Text Primary     #F1F5F9 ███████████████ Main text (high contrast) │
│ Text Secondary   #94A3B8 ███████████████ Secondary text            │
│ Text Muted       #CC94A3B8 ███████████ Muted text (80% alpha)      │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│ Glass Morphism                                                       │
├──────────────────────────────────────────────────────────────────────┤
│ Glass Surface    #661E293B ███████████ 40% alpha surface           │
│ Glass Border     #1A94A3B8 ███████████ 10% alpha border            │
└──────────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────────┐
│ Budget Health Indicators                                             │
├──────────────────────────────────────────────────────────────────────┤
│ On Track         ████████ Green (#10B981)                           │
│ Under Pace       ████████ Green (#10B981)                           │
│ On Pace          ████████ Indigo (#6366F1)                          │
│ Over Pace        ████████ Orange (#F97316)                          │
│ Warning          ████████ Orange (#F97316)                          │
│ Critical         ████████ Red (#EF4444)                             │
│ Exceeded         ████████ Red (#FF5722)                             │
└──────────────────────────────────────────────────────────────────────┘
```

---

## FILE STRUCTURE TREE (UI Only)

```
ui/
├── MainActivity.kt                    ← App entry point
├── MainViewModel.kt                   ← Main state management
│
├── navigation/
│   ├── NavigationDestination.kt      ← Sealed class: all destinations
│   ├── NavigationController.kt        ← Navigation state machine
│   ├── FeatureConfig.kt              ← Feature menu configuration
│   └── FeatureIntegration.kt         ← Feature integration logic
│
├── theme/
│   ├── Theme.kt                      ← Color scheme, typography
│   └── Dimens.kt                     ← Spacing, sizes
│
├── screens/
│   ├── home/
│   │   ├── HomeScreen.kt
│   │   └── HomeViewModel.kt
│   ├── transactions/
│   │   ├── TransactionsScreen.kt
│   │   ├── TransactionFilter.kt
│   │   ├── TransactionFilterSheet.kt
│   │   └── TransactionsViewModel.kt
│   ├── review/
│   │   ├── ReviewScreen.kt
│   │   └── ReviewViewModel.kt
│   ├── budget/
│   │   ├── BudgetScreen.kt
│   │   ├── BudgetForecastingScreen.kt
│   │   ├── BudgetViewModel.kt
│   │   └── BudgetForecastingViewModel.kt
│   ├── analytics/
│   │   ├── AnalyticsScreen.kt
│   │   ├── AdvancedAnalyticsScreen.kt
│   │   ├── AnalyticsViewModel.kt
│   │   └── AdvancedAnalyticsViewModel.kt
│   ├── map/
│   │   ├── SpendingMapScreen.kt
│   │   └── SpendingMapViewModel.kt
│   │
│   ├── addexpense/
│   │   ├── AddExpenseSheet.kt
│   │   └── AddExpenseViewModel.kt
│   ├── receiptscan/
│   │   ├── ReceiptScanScreen.kt
│   │   └── ReceiptScanViewModel.kt
│   ├── assistant/
│   │   ├── AssistantSheet.kt
│   │   └── AssistantViewModel.kt
│   ├── recurring/
│   │   └── RecurringExpensesScreen.kt
│   ├── recurringmanual/
│   │   ├── ManualRecurringExpenseScreen.kt
│   │   └── ManualRecurringExpenseViewModel.kt
│   │
│   ├── savings/
│   │   ├── SavingsGoalsScreen.kt
│   │   └── SavingsGoalsViewModel.kt
│   ├── carbon/
│   │   ├── CarbonFootprintScreen.kt
│   │   └── CarbonFootprintViewModel.kt
│   ├── warranty/
│   │   ├── WarrantyTrackerScreen.kt
│   │   └── WarrantyTrackerViewModel.kt
│   ├── price/
│   │   ├── PriceProtectionScreen.kt
│   │   └── PriceProtectionViewModel.kt
│   ├── negotiation/
│   │   ├── BillNegotiationScreen.kt
│   │   └── BillNegotiationViewModel.kt
│   ├── naturallanguage/
│   │   ├── NaturalLanguageSearchScreen.kt
│   │   └── NaturalLanguageSearchViewModel.kt
│   ├── receiptmatching/
│   │   ├── ReceiptMatchingScreen.kt
│   │   └── ReceiptMatchingViewModel.kt
│   ├── investment/
│   │   ├── InvestmentPortfolioScreen.kt
│   │   └── InvestmentViewModel.kt
│   ├── bank/
│   │   ├── BankConnectionsScreen.kt
│   │   └── BankConnectionsViewModel.kt
│   ├── reminder/
│   │   ├── BillRemindersScreen.kt
│   │   └── BillRemindersViewModel.kt
│   ├── challenge/
│   │   ├── SpendingChallengesScreen.kt
│   │   └── SpendingChallengesViewModel.kt
│   ├── cashflow/
│   │   ├── CashFlowCalendarScreen.kt
│   │   └── CashFlowCalendarViewModel.kt
│   ├── lifestyle/
│   │   ├── LifestyleInflationScreen.kt
│   │   └── LifestyleInflationViewModel.kt
│   ├── split/
│   │   ├── SplitTemplatesScreen.kt
│   │   ├── VisualSplitEditorScreen.kt
│   │   └── VisualSplitViewModel.kt
│   ├── currency/
│   │   ├── CurrencyManagementScreen.kt
│   │   └── CurrencyManagementViewModel.kt
│   ├── subscription/
│   │   ├── SubscriptionManagementScreen.kt
│   │   └── SubscriptionManagementViewModel.kt
│   ├── tax/
│   │   ├── TaxConfigurationScreen.kt
│   │   └── TaxConfigurationViewModel.kt
│   ├── export/
│   │   ├── ExportOptionsScreen.kt
│   │   └── ExportOptionsViewModel.kt
│   ├── groups/
│   │   ├── SharedExpenseGroupsScreen.kt
│   │   └── SharedExpenseGroupsViewModel.kt
│   ├── aisettings/
│   │   ├── AiSettingsScreen.kt
│   │   └── AiSettingsViewModel.kt
│   ├── categories/
│   │   ├── CategoryScreen.kt
│   │   └── CategoryViewModel.kt
│   │
│   └── debug/
│       ├── DebugScreen.kt
│       ├── DebugViewModel.kt
│       ├── DebugViewerScreen.kt
│       ├── CategorizationDebugScreen.kt
│       ├── CategorizationDebugViewModel.kt
│       ├── DebugIssueDetector.kt
│       └── DebugDataStorage.kt
│
├── components/
│   ├── AppNavigationBar.kt
│   ├── AppFabMenu.kt
│   ├── BentoCard.kt
│   ├── BudgetBlockPartyCard.kt
│   ├── CategoryBreakdownSheet.kt
│   ├── CategoryDonutChart.kt
│   ├── ChartMarker.kt
│   ├── FinancialRunwayCard.kt
│   ├── FinancialStressForecastCard.kt
│   ├── FinancialWeatherCard.kt
│   ├── ForecastTimeline.kt
│   ├── LocationCorrectionSheet.kt
│   ├── LocationPermissionDialog.kt
│   ├── LocationSearchPicker.kt
│   ├── MonteCarloForecastCard.kt
│   ├── NearbyShopSuggestionCard.kt
│   ├── NotificationPermissionDialog.kt
│   ├── PeriodBlock.kt
│   ├── PeriodGridView.kt
│   ├── PeriodNavigationBar.kt
│   ├── PlaceInsightCard.kt
│   ├── PulseDot.kt
│   ├── RecommendationCard.kt
│   ├── RetroBudgetBlockPartyCard.kt
│   ├── RetroCategoryBreakdownSheet.kt
│   ├── RetroTopCategoriesCard.kt
│   ├── RetroTotalsDashboardCard.kt
│   ├── SpendingPaceGauge.kt
│   ├── SpendingTrendChart.kt
│   ├── TotalsDashboardCard.kt
│   ├── TransferDirectionBadge.kt
│   │
│   ├── ai/
│   │   ├── AssistantResultCard.kt
│   │   ├── CategoryAssistCard.kt
│   │   ├── DedupeAssistCard.kt
│   │   ├── ReceiptAssistCard.kt
│   │   └── ReceiptItemBreakdownCard.kt
│   │
│   ├── analytics/
│   │   ├── NoSpendStreakWidget.kt
│   │   ├── PersonalityProfileCard.kt
│   │   └── StatisticalVisualizations.kt
│   │
│   ├── common/
│   │   ├── EmptyState.kt
│   │   ├── EnhancedEmptyState.kt
│   │   ├── ErrorState.kt
│   │   └── LoadingSkeleton.kt
│   │
│   ├── dashboard/
│   │   └── MoneyRadarWidget.kt
│   │
│   ├── emptystate/
│   │   ├── ContextualActionRegistry.kt
│   │   └── EmptyStateAction.kt
│   │
│   ├── feature/
│   │   ├── FeatureComponents.kt
│   │   ├── FormComponents.kt
│   │   └── MetricComponents.kt
│   │
│   └── health/
│       ├── FinancialHealthScoreV2Widget.kt
│       └── HealthScoreWidget.kt
│
├── mappers/
│   ├── DashboardWidgetUiMapper.kt
│   └── TransactionFilterUiMapper.kt
│
└── util/
    ├── ClipboardAmountParser.kt
    ├── ColorExtensions.kt
    ├── HapticFeedback.kt
    └── ModifierExtensions.kt
```

---

**End of Visual Navigation Map**
