

## 21. Export to Accounting Software

### Status: ❌ DOES NOT EXIST

### Current State
**Existing Infrastructure:**
- ✅ Export functionality (CSV, JSON)
- ✅ `DebugViewModel` has export/import
- ✅ Category system
- ✅ Receipt storage

**Missing:**
- ❌ QuickBooks format
- ❌ Xero format
- ❌ FreshBooks format
- ❌ Accountant-friendly reports
- ❌ Schedule C generation

### Implementation Plan

#### Phase 1: Export Format Classes (2 days)
**New Files:**
- `domain/export/QuickBooksExporter.kt`
- `domain/export/XeroExporter.kt`
- `domain/export/FreshBooksExporter.kt`
- `domain/export/GenericAccountingExporter.kt`

**QuickBooks IIF Format:**
```kotlin
class QuickBooksExporter {
    fun export(expenses: List<Expense>): String {
        // IIF (Intuit Interchange Format)
        // !TRNS, !SPL headers
        // Date, Account, Amount, Memo, etc.
        
        return buildString {
            appendLine("!TRNS\tDATE\tACCNT\tAMOUNT\tMEMO")
            expenses.forEach { expense ->
                appendLine("TRNS\t${formatDate(expense.date)}\t${expense.categoryName}\t${expense.amount}\t${expense.merchant}")
            }
        }
    }
}
```

**Xero CSV:**
```kotlin
class XeroExporter {
    fun export(expenses: List<Expense>): String {
        // Date, Description, Amount, Account, Reference
        return csvBuilder {
            header("Date", "Description", "Amount", "Account", "Reference")
            expenses.forEach { expense ->
                row(
                    expense.date,
                    expense.merchant,
                    expense.amount,
                    expense.categoryName,
                    expense.id
                )
            }
        }
    }
}
```

#### Phase 2: Accountant Report (2 days)
**New Files:**
- `domain/export/AccountantReportGenerator.kt`

**Report Sections:**
- Executive Summary (total income, total expenses, net)
- Category breakdown with subtotals
- Unusual transactions (flagged for review)
- Missing receipt list
- Monthly trend chart
- Tax-deductible expenses summary

#### Phase 3: Schedule C (US) Generator (2 days)
**New Files:**
- `domain/export/ScheduleCGenerator.kt`

**Auto-fill:**
- Part I: Income (from deposits)
- Part II: Expenses (categorized)
- Cost of Goods Sold (if applicable)
- Vehicle expenses (if tracked)

**Modify:**
- Add business/personal flag to expenses (see Feature #25)

#### Phase 4: Receipt Bundle Export (1 day)
**New Files:**
- `service/export/ReceiptBundleExporter.kt`

**Create ZIP:**
- CSV of transactions
- Folder of receipts (named: YYYY-MM-DD_Merchant_Amount.jpg)
- Index file mapping receipts to transactions

#### Phase 5: UI (2 days)
**New Files:**
- `ui/screens/export/AccountingExportScreen.kt`

**Options:**
- Select date range
- Select format (QuickBooks, Xero, FreshBooks, CSV)
- Include/exclude receipts
- Tax year selection
- One-tap export

### Estimated Effort: 1.5 weeks
### Priority: HIGH (High value, moderate effort, seasonal demand)

---

## 22. Enhanced Split Transactions

### Status: ⚠️ PARTIALLY EXISTS - ENHANCEMENT NEEDED

### Current State
**Existing Infrastructure:**
- ✅ Split transaction support in manual entry
- ✅ `isSharedExpense` field
- ✅ `myShareAmount` field
- ✅ Basic UI for splits

**Missing:**
- ❌ Percentage-based splits
- ❌ Uneven splits (shares)
- ❌ Auto-suggest splits based on receipt items
- ❌ Save split templates
- ❌ Visual split editor

### Implementation Plan

#### Phase 1: Enhanced Split Methods (3 days)
**New Files:**
- `domain/split/SplitCalculator.kt`
- `data/database/entity/SplitTransaction.kt`

```kotlin
sealed class SplitMethod {
    data class Equal(val participants: Int) : SplitMethod()
    
    data class Percentage(
        val splits: Map<Long, Double> // memberId -> percentage
    ) : SplitMethod()
    
    data class ExactAmount(
        val splits: Map<Long, Double> // memberId -> amount
    ) : SplitMethod()
    
    data class Shares(
        val splits: Map<Long, Int> // memberId -> shares (e.g., 2 drinks = 2 shares)
    ) : SplitMethod()
    
    data class Adjustment(
        val baseSplit: SplitMethod,
        val adjustments: Map<Long, Double> // memberId -> +/- amount
    ) : SplitMethod()
}
```

#### Phase 2: Receipt Item Integration (2 days)
**New Files:**
- `domain/split/ReceiptItemSplitter.kt`

**Features:**
- Import receipt items from AI categorization
- Assign each item to person
- Auto-calculate splits
- Handle tax distribution

**Example:**
- Alice: Salad €12
- Bob: Steak €25
- Shared: Appetizer €8, Drinks €15

#### Phase 3: Visual Split Editor (3 days)
**New Files:**
- `ui/components/split/VisualSplitEditor.kt`
- `ui/components/split/SplitPieChart.kt`
- `ui/components/split/ParticipantSelector.kt`

**Features:**
- Drag handles to adjust percentages
- Visual pie chart of split
- Color-coded participants
- Real-time total validation (must equal 100%)

#### Phase 4: Split Templates (2 days)
**New Files:**
- `data/database/entity/SplitTemplate.kt`

**Common Templates:**
- "Dinner with friends" (Equal)
- "Roommates" (Percentage based on room size)
- "Family vacation" (By income ratio)
- Custom templates

#### Phase 5: Integration (2 days)
**Modify:**
- `ui/screens/addexpense/AddExpenseSheet.kt` - Enhanced split UI
- `ui/screens/transactions/TransactionsScreen.kt` - Show split indicator

**Features:**
- One-tap split from transaction list
- Split history/recent splits
- IOU tracking (who owes whom)

### Estimated Effort: 1.5 weeks (Enhancement)
### Priority: MEDIUM (Builds on existing, social use case)

---

## 23. Dark/Light Mode Scheduling

### Status: ❌ DOES NOT EXIST

### Current State
**Existing Infrastructure:**
- ✅ Theme system in `Theme.kt`
- ✅ Dark mode support
- ✅ Material 3 theming

**Missing:**
- ❌ Automatic switching based on time
- ❌ Battery-based switching
- ❌ Sun position (sunrise/sunset)
- ❌ Schedule customization

### Implementation Plan

#### Phase 1: Theme Scheduler (2 days)
**New Files:**
- `domain/theme/ThemeScheduler.kt`
- `service/theme/ThemeScheduleWorker.kt`

**Scheduling Modes:**
```kotlin
enum class ThemeScheduleMode {
    MANUAL,           // User switches manually
    SUNRISE_SUNSET,   // Based on location
    TIME_BASED,       // Custom times
    BATTERY_BASED     // Dark mode when battery < 20%
}

data class ThemeSchedule(
    val mode: ThemeScheduleMode,
    val darkModeStart: LocalTime?, // For TIME_BASED
    val darkModeEnd: LocalTime?,   // For TIME_BASED
    val respectBatterySaver: Boolean = true
)
```

#### Phase 2: Sunrise/Sunset Calculation (2 days)
**New Files:**
- `domain/theme/SunTimeCalculator.kt`

**Use:**
- Device location (existing location permission)
- Sun position calculation (no API needed)
- Or: Sunrise-sunset.org API (free)

#### Phase 3: Background Worker (1 day)
**New Files:**
- `service/theme/ThemeUpdateWorker.kt`

**Schedule:**
- Check every 15 minutes during transition periods
- Update theme if needed
- Respect Do Not Disturb

#### Phase 4: UI Settings (1 day)
**Modify:**
- `ui/screens/settings/SettingsScreen.kt` - Add theme schedule section

**Options:**
- Schedule mode selector
- Time pickers (if time-based)
- Battery saver toggle
- Preview ("Next change: 8:30 PM")

### Estimated Effort: 1 week
### Priority: LOW (Nice-to-have, low effort)

---

## 24. Quick Add Widgets

### Status: ❌ DOES NOT EXIST

### Current State
**Existing Infrastructure:**
- ✅ Widget system (existing widgets)
- ✅ `AddExpenseSheet` functionality
- ✅ Quick expense entry
- ✅ Haptic feedback

**Missing:**
- ❌ Home screen widgets
- ❌ Voice input
- ❌ Quick actions

### Implementation Plan

#### Phase 1: Home Screen Widget (3 days)
**New Files:**
- `ui/widgets/QuickAddWidget.kt`
- `ui/widgets/QuickAddWidgetProvider.kt`

**Widget Types:**
```kotlin
// res/xml/quick_add_widget_info.xml
<appwidget-provider
    android:initialLayout="@layout/widget_quick_add"
    android:minWidth="180dp"
    android:minHeight="40dp"
    android:updatePeriodMillis="0"
    android:widgetCategory="home_screen" />
```

**Widget Layouts:**
1. **Button Widget** - Tap to open quick add
2. **Shortcut Widget** - Predefined amounts (€5, €10, €20)
3. **Input Widget** - Mini form on home screen

#### Phase 2: Voice Commands (3 days)
**New Files:**
- `service/voice/QuickAddVoiceService.kt`
- `domain/voice/VoiceExpenseParser.kt`

**Integration:**
```kotlin
// AndroidManifest.xml
<activity android:name=".VoiceShortcutActivity">
    <intent-filter>
        <action android:name="android.intent.action.VOICE_COMMAND" />
        <category android:name="android.intent.category.DEFAULT" />
    </intent-filter>
</activity>
```

**Supported Phrases:**
- "Track €20 coffee"
- "Add €50 grocery expense"
- "Log €15 taxi"
- "Expense €100 at Amazon"

**Parse:**
```kotlin
class VoiceExpenseParser {
    fun parse(command: String): VoiceExpenseResult {
        // Regex: (amount) (optional: at/to) (merchant/category)
        // "20 coffee" -> amount=20, merchant=coffee, category=Food
        // "100 at Amazon" -> amount=100, merchant=Amazon, category=Shopping
    }
}
```

#### Phase 3: Quick Actions (2 days)
**Modify:**
- `AndroidManifest.xml` - Add shortcuts

**Static Shortcuts:**
```xml
<shortcut
    android:shortcutId="quick_add_food"
    android:enabled="true"
    android:icon="@drawable/ic_food"
    android:shortcutShortLabel="@string/shortcut_food">
    <intent
        android:action="android.intent.action.VIEW"
        android:targetPackage="com.yourname.expensetracker"
        android:targetClass=".QuickAddActivity">
        <extra android:name="category" android:value="Food" />
    </intent>
</shortcut>
```

**Dynamic Shortcuts (based on history):**
- "Add Starbucks" (if frequent)
- "Add Gas" (if recent)

#### Phase 4: Wear OS (Optional, 3 days)
**New Files:**
- `wear/QuickAddTile.kt`
- `wear/AddExpenseActivity.kt`

**Features:**
- Tile for quick expense entry
- Voice input on watch
- Recent expenses list

### Estimated Effort: 1.5 weeks (without Wear OS: 1 week)
### Priority: MEDIUM (Convenience feature, good for power users)

---

## 25. Business/Personal Separation

### Status: ❌ DOES NOT EXIST

### Current State
**Existing Infrastructure:**
- ✅ Category system
- ✅ `TransactionType` enum (PURCHASE, DEPOSIT, etc.)
- ✅ Tags system (could be extended)

**Missing:**
- ❌ Business/personal flag
- ❌ Business expense reports
- ❌ Mileage tracking
- ❌ Separate analytics views

### Implementation Plan

#### Phase 1: Business Flag (1 day)
**Modify:**
- `data/database/entity/Expense.kt` - Add `isBusinessExpense: Boolean = false`
- `data/database/entity/Category.kt` - Add `isBusinessCategory: Boolean = false`

**Migration:**
- Default all existing to personal
- Add migration

#### Phase 2: Business Categories (2 days)
**New Files:**
- `data/provider/BusinessCategoryProvider.kt`

**Default Business Categories:**
- Office Supplies
- Travel
- Meals & Entertainment
- Professional Services
- Marketing
- Equipment
- Home Office
- Vehicle Expenses
- Insurance
- Utilities

#### Phase 3: Mileage Tracking (3 days)
**New Files:**
- `domain/business/MileageTracker.kt`
- `data/database/entity/MileageEntry.kt`
- `ui/components/business/MileageCard.kt`

**Features:**
- Track trips (start/end location, purpose)
- Calculate distance
- Apply standard mileage rate (IRS rate)
- Generate mileage log report

**Integration with Location:**
- Use existing location services
- Automatic trip detection (start at home, end at client)

#### Phase 4: Business Expense Reports (3 days)
**New Files:**
- `domain/business/BusinessReportGenerator.kt`
- `ui/screens/business/BusinessExpenseScreen.kt`

**Reports:**
- Monthly/Quarterly/Annual business expenses
- By category breakdown
- Tax-deductible summary
- Receipt bundle for business expenses
- Mileage log

#### Phase 5: UI Toggle & Filtering (2 days)
**Modify:**
- `ui/screens/addexpense/AddExpenseSheet.kt` - Business/personal toggle
- `ui/screens/transactions/TransactionsScreen.kt` - Business filter
- `ui/screens/analytics/AnalyticsScreen.kt` - Business view toggle

**Views:**
- All expenses
- Personal only
- Business only
- Toggle on any screen

#### Phase 6: Separate Analytics (Optional, 2 days)
**New Files:**
- `domain/analytics/BusinessAnalyticsEngine.kt`

**Business-Specific Metrics:**
- Revenue vs Expenses (P&L)
- Cash flow
- Burn rate (for startups)
- Tax burden estimate

### Estimated Effort: 2 weeks
### Priority: HIGH (High value for freelancers/business owners)

---

## 26. Invoice Generation

### Status: ❌ DOES NOT EXIST

### Current State
**Existing Infrastructure:**
- ✅ Expense tracking
- ✅ Business/personal separation (Feature #25)
- ✅ Receipt storage
- ✅ Export functionality

**Missing:**
- ❌ Invoice data model
- ❌ Client management
- ❌ Invoice templates
- ❌ Payment tracking
- ❌ Late payment reminders

### Implementation Plan

#### Phase 1: Invoice Data Model (2 days)
**New Files:**
- `data/database/entity/Invoice.kt`
- `data/database/entity/InvoiceLineItem.kt`
- `data/database/entity/Client.kt`

```kotlin
@Entity(tableName = "invoices")
data class Invoice(
    @PrimaryKey val id: Long = 0,
    val invoiceNumber: String,
    val clientId: Long,
    val issueDate: Long,
    val dueDate: Long,
    val status: InvoiceStatus, // DRAFT, SENT, PAID, OVERDUE
    val totalAmount: Double,
    val currency: String,
    val notes: String?,
    val terms: String? // Payment terms
)

@Entity(tableName = "invoice_line_items")
data class InvoiceLineItem(
    @PrimaryKey val id: Long = 0,
    val invoiceId: Long,
    val description: String,
    val quantity: Double,
    val unitPrice: Double,
    val total: Double,
    val expenseId: Long? // Link to expense (reimbursable)
)

@Entity(tableName = "clients")
data class Client(
    @PrimaryKey val id: Long = 0,
    val name: String,
    val email: String,
    val address: String?,
    val taxId: String?,
    val defaultPaymentTerms: String // "Net 30", etc.
)
```

#### Phase 2: Invoice Generator (3 days)
**New Files:**
- `domain/invoice/InvoiceGenerator.kt`
- `service/invoice/InvoicePdfRenderer.kt`

**PDF Generation:**
- Use Android PDFDocument API
- Templates (professional, minimal, detailed)
- Logo support
- Terms & conditions
- Thank you note

#### Phase 3: Client Management (2 days)
**New Files:**
- `ui/screens/invoice/ClientManagementScreen.kt`
- `data/repository/ClientRepository.kt`

**Features:**
- Add/edit clients
- Client history (all invoices)
- Quick invoice from client

#### Phase 4: Invoice Tracking & Reminders (3 days)
**New Files:**
- `service/invoice/InvoiceReminderService.kt`
- `domain/invoice/InvoiceTracker.kt`

**Features:**
- Mark as paid
- Partial payments
- Overdue detection
- Reminder notifications (3 days before, day of, 7 days after)
- Email integration (send invoice via email)

#### Phase 5: UI (4 days)
**New Files:**
- `ui/screens/invoice/InvoiceListScreen.kt`
- `ui/screens/invoice/CreateInvoiceScreen.kt`
- `ui/screens/invoice/InvoiceDetailScreen.kt`
- `ui/components/invoice/InvoiceCard.kt`
- `ui/components/invoice/LineItemEditor.kt`

**Features:**
- List of invoices with status colors
- Filter by status (paid, overdue, draft)
- Preview before sending
- Duplicate invoice
- Convert expense to invoice (reimbursement)

### Estimated Effort: 2 weeks
### Priority: MEDIUM (Complete solution for freelancers)

---

## 27. Offline-First Architecture

### Status: ⚠️ PARTIALLY EXISTS - REQUIRES ENHANCEMENT

### Current State
**Existing Infrastructure:**
- ✅ Room database (local storage)
- ✅ Basic offline support (reads work offline)
- ✅ Background sync (NotificationCaptureService)

**Missing:**
- ❌ Conflict resolution for concurrent edits
- ❌ Offline mutation queue
- ❌ Sync status indicators
- ❌ Full offline transaction entry
- ❌ Background conflict resolution

### Implementation Plan

**Note:** This is a major architecture change. Consider as Phase 2 of multi-user support (Feature #2).

#### Phase 1: Offline Mutation Queue (3 days)
**New Files:**
- `domain/offline/PendingMutation.kt`
- `data/database/entity/PendingMutation.kt`
- `service/offline/MutationQueueProcessor.kt`

**Queue:**
```kotlin
data class PendingMutation(
    val id: String = UUID.randomUUID().toString(),
    val operation: MutationOperation, // CREATE, UPDATE, DELETE
    val entityType: String, // "Expense", "Budget", etc.
    val entityId: Long,
    val payload: String, // JSON of changes
    val createdAt: Long,
    val retryCount: Int = 0,
    val status: MutationStatus = PENDING
)

class MutationQueueProcessor @Inject constructor(
    private val mutationDao: PendingMutationDao,
    private val connectivityMonitor: ConnectivityMonitor
) {
    suspend fun processQueue() {
        // When online, process pending mutations
        // Handle conflicts
        // Mark as synced
    }
}
```

#### Phase 2: Conflict Resolution (3 days)
**New Files:**
- `domain/sync/ConflictResolver.kt`
- `domain/sync/ConflictResolutionStrategy.kt`

**Strategies:**
- Last-Write-Wins (timestamp)
- User-Merge (manual resolution UI)
- Custom rules (per entity type)

**Examples:**
- Expense: Last write wins
- Budget: Prompt user (budget amounts are important)
- Category names: Merge (keep both)

#### Phase 3: Sync Indicators (2 days)
**New Files:**
- `ui/components/sync/SyncStatusIndicator.kt`
- `ui/components/sync/PendingChangesBadge.kt`

**Show:**
- Number of pending changes
- Last sync time
- Conflict alerts
- Offline mode indicator

#### Phase 4: Background Sync (2 days)
**Modify:**
- `service/NotificationCaptureService` - Add sync logic

**Sync Triggers:**
- When connectivity restored
- Periodic (every 15 min if changes pending)
- App foregrounded
- User-initiated (pull-to-sync)

### Estimated Effort: 2 weeks
### Priority: MEDIUM (Required for Feature #2 - Multi-User)

---

## 28. Wear OS / Watch Complications

### Status: ❌ DOES NOT EXIST

### Current State
**Existing Infrastructure:**
- ✅ Widget patterns
- ✅ Quick add functionality
- ✅ Haptic feedback
- ✅ Notifications

**Missing:**
- ❌ Wear OS module
- ❌ Watch complications
- ❌ Tile layouts
- ❌ Voice input on watch

### Implementation Plan

#### Phase 1: Wear Module Setup (1 day)
**New Files:**
- `wear/build.gradle` - New module
- `wear/AndroidManifest.xml`

**Dependencies:**
- Wear OS SDK
- Watch Face Complications
- Tiles

#### Phase 2: Complications (2 days)
**New Files:**
- `wear/complications/BudgetComplicationService.kt`
- `wear/complications/SpendingComplicationService.kt`

**Complication Types:**
1. **Budget Status** - Shows remaining budget %
2. **Daily Spend** - Today's spending so far
3. **Safe to Spend** - Daily discretionary amount
4. **Pending Reviews** - Number of transactions to review

#### Phase 3: Tiles (2 days)
**New Files:**
- `wear/tiles/QuickAddTile.kt`
- `wear/tiles/RecentExpensesTile.kt`

**Tile Layouts:**
- Quick add buttons (Food, Transport, Shopping)
- List of recent expenses
- Budget status visualization

#### Phase 4: Watch App (3 days)
**New Files:**
- `wear/AddExpenseActivity.kt`
- `wear/VoiceInputActivity.kt`

**Features:**
- Voice entry ("€20 coffee")
- Category selection (list)
- Confirmation haptic
- Sync with phone

#### Phase 5: Phone-Watch Sync (1 day)
**Modify:**
- `data/repository/ExpenseRepository` - Trigger watch sync

**Sync:**
- Watch app syncs on open
- Phone sends updates to watch
- Wear Data Layer API

### Estimated Effort: 1.5 weeks
### Priority: LOW-MEDIUM (Niche, but good for power users)

---

## 29. Web Dashboard

### Status: ❌ DOES NOT EXIST

### Current State
**Existing Infrastructure:**
- ✅ Kotlin Multiplatform capabilities
- ✅ Compose for Web (experimental)
- ✅ Shared business logic
- ✅ Room database (would need alternative for web)

**Missing:**
- ❌ Web module
- ❌ Web-specific UI adaptations
- ❌ Firebase/Supabase backend (for web sync)
- ❌ Desktop-optimized views

### Implementation Plan

**Note:** This is a major undertaking. Consider as long-term project.

#### Phase 1: Shared Module Refactoring (1 week)
**Modify:**
- Extract pure business logic to `shared` module
- Make `domain` layer platform-agnostic
- Use Kotlin Multiplatform (KMP)

#### Phase 2: Web Backend (Optional, 1 week)
**Options:**
- Firebase (easiest, Google ecosystem)
- Supabase (open source alternative)
- Custom backend (highest effort)

**Features:**
- User authentication
- Data sync
- Anonymous analytics

#### Phase 3: Web UI (2 weeks)
**New Files:**
- `web/App.kt` - Entry point
- `web/screens/DashboardScreen.kt` - Compose for Web
- `web/components/WebCharts.kt` - Desktop-optimized charts

**Adaptations:**
- Larger charts (more screen real estate)
- Keyboard shortcuts
- Multi-window support
- Drag-and-drop receipt upload
- CSV import

#### Phase 4: Real-time Sync (3 days)
**Implementation:**
- WebSocket or Firebase real-time
- Instant sync between web and mobile
- Collaborative features (see Feature #2)

### Estimated Effort: 5-6 weeks (major project)
### Priority: LOW (High effort, requires new platform expertise)

---

## 30. API for Third-Party Integrations

### Status: ❌ DOES NOT EXIST

### Current State
**Existing Infrastructure:**
- ✅ Internal data models
- ✅ Repository pattern
- ✅ Authentication concepts (from Feature #2)

**Missing:**
- ❌ REST API
- ❌ API documentation
- ❌ Rate limiting
- ❌ Webhook support
- ❌ Zapier/IFTTT integration

### Implementation Plan

**Note:** This requires backend infrastructure. Could use Firebase Functions or custom server.

#### Phase 1: API Design (2 days)
**New Files:**
- `api/spec/openapi.yaml` - OpenAPI specification

**Endpoints:**
```yaml
/expenses:
  get: List expenses
  post: Create expense
  
/expenses/{id}:
  get: Get expense
  put: Update expense
  delete: Delete expense
  
/budgets:
  get: List budgets
  
/analytics/summary:
  get: Get spending summary
  
/webhooks:
  post: Register webhook
```

#### Phase 2: Backend Implementation (1 week)
**Option A: Firebase Functions (Easier)**
**Option B: Custom Server (Ktor/Spring)**

**New Files (Firebase):**
- `functions/src/index.ts` - Cloud Functions
- `functions/src/expenses.ts` - Expense endpoints
- `functions/src/auth.ts` - Authentication middleware

**Features:**
- JWT authentication
- Rate limiting (100 req/min free, 1000 req/min paid)
- Request validation
- Error handling

#### Phase 3: Webhooks (2 days)
**New Files:**
- `api/webhooks/WebhookManager.kt`
- `functions/src/webhooks.ts`

**Events:**
- expense.created
- expense.updated
- budget.alert
- recurring.payment_due

**Payloads:**
```json
{
  "event": "expense.created",
  "timestamp": "2026-03-31T12:00:00Z",
  "data": {
    "id": "123",
    "amount": 25.00,
    "merchant": "Starbucks",
    "category": "Food"
  }
}
```

#### Phase 4: Zapier Integration (2 days)
**Create:**
- Zapier app
- Triggers (new expense, budget alert)
- Actions (create expense, update budget)
- Search (find expense)

**Zaps Users Could Create:**
- "New expense → Add to Google Sheet"
- "New expense → Post to Slack #spending"
- "Monthly → Email summary"
- "Budget alert → Send SMS"

#### Phase 5: API Keys Management (2 days)
**New Files:**
- `ui/screens/settings/ApiKeysScreen.kt`
- `data/database/entity/ApiKey.kt`

**Features:**
- Generate/revoke API keys
- View usage statistics
- Rate limit status
- Webhook management

#### Phase 6: Documentation (2 days)
**Create:**
- API documentation site (Swagger UI or custom)
- Code examples (curl, Python, JavaScript)
- Postman collection
- SDK (optional): npm package, pip package

### Estimated Effort: 3 weeks (with Firebase)
### Priority: LOW-MEDIUM (Enables ecosystem, requires backend)

---

## FINAL SUMMARY: ALL 30 FEATURES

### Quick Reference Table

| # | Feature | Status | Effort | Priority | Leverages Existing |
|---|---------|--------|--------|----------|-------------------|
| 1 | Smart Savings Goals | ⚠️ Enhancement | 2.5w | HIGH | ✅ SavingsGoal exists |
| 2 | Family/Multi-User | ❌ New | 5-6w | MEDIUM-HIGH | ⚠️ Minor prep exists |
| 3 | Investment/Asset Tracking | ❌ New | 3-4w | MEDIUM | ✅ Analytics engines |
| 4 | Cash Flow Calendar | ❌ New | 2w | HIGH | ✅ Forecast engines |
| 5 | Advanced Subscriptions | ⚠️ Enhancement | 2w | HIGH | ✅ Recurring engine |
| 6 | Natural Language Search | ⚠️ Enhancement | 3w | HIGH | ✅ Query interpreter |
| 7 | Automated Receipt Matching | ❌ New | 2.5w | HIGH | ✅ OCR + dedupe |
| 8 | Predictive Budget Optimization | ❌ New | 3w | MEDIUM-HIGH | ✅ Budget + AI |
| 9 | Group Expense Management | ❌ New | 3.5w | MEDIUM-HIGH | ✅ Split logic exists |
| 10 | Carbon Footprint | ❌ New | 3w | MEDIUM | ✅ Categories + AI |
| 11 | Price Protection | ❌ New | 2w | MEDIUM | ✅ Receipts + alerts |
| 12 | Bill Negotiation | ❌ New | 1.5w | MEDIUM | ✅ Recurring + AI |
| 13 | Lifestyle Inflation | ❌ New | 1.5w | MEDIUM | ✅ Analytics |
| 14 | Warranty Tracker | ❌ New | 1.5w | HIGH | ✅ Receipt OCR |
| 15 | Tax Preparation | ❌ New | 2w | HIGH | ✅ Categories |
| 16 | Enhanced Health Score | ⚠️ Enhancement | 1.5w | MEDIUM | ✅ Score exists |
| 17 | Category Elasticity | ❌ New | 1.5w | MEDIUM | ✅ Analytics |
| 18 | Social Comparison | ❌ New | 2w | MEDIUM | ✅ Stats |
| 19 | Subscription Maintenance | ⚠️ Enhancement | 1w | MEDIUM | ✅ Depends on #5 |
| 20 | Opportunity Cost | ❌ New | 1w | LOW-MEDIUM | ✅ Forecasting |
| 21 | Export to Accounting | ❌ New | 1.5w | HIGH | ✅ Export exists |
| 22 | Enhanced Split | ⚠️ Enhancement | 1.5w | MEDIUM | ✅ Split exists |
| 23 | Theme Scheduling | ❌ New | 1w | LOW | ✅ Theme exists |
| 24 | Quick Add Widgets | ❌ New | 1.5w | MEDIUM | ✅ Quick add |
| 25 | Business/Personal | ❌ New | 2w | HIGH | ✅ Categories |
| 26 | Invoice Generation | ❌ New | 2w | MEDIUM | ✅ Depends on #25 |
| 27 | Offline-First | ⚠️ Enhancement | 2w | MEDIUM | ⚠️ Partial offline |
| 28 | Wear OS | ❌ New | 1.5w | LOW-MEDIUM | ✅ Widgets |
| 29 | Web Dashboard | ❌ New | 5-6w | LOW | ✅ KMP possible |
| 30 | API/Integrations | ❌ New | 3w | LOW-MEDIUM | ⚠️ Needs backend |

---

## RECOMMENDED IMPLEMENTATION ORDER

### Phase 1: Foundation (Weeks 1-4)
**Quick Wins & High Value:**
1. **#14** - Warranty Tracker (1.5w) - High practical value
2. **#21** - Export to Accounting (1.5w) - Tax season ready
3. **#25** - Business/Personal (2w) - Foundation for #26

### Phase 2: Enhancement (Weeks 5-8)
**Build on Existing:**
4. **#1** - Smart Savings Goals (2.5w) - Enhancement of existing
5. **#5** - Advanced Subscriptions (2w) - Enhancement of existing
6. **#4** - Cash Flow Calendar (2w) - High user value

### Phase 3: Intelligence (Weeks 9-12)
**AI-Powered Features:**
7. **#7** - Automated Receipt Matching (2.5w) - Leverages AI
8. **#8** - Predictive Budget Optimization (3w) - AI-driven
9. **#15** - Tax Preparation (2w) - Tax deadline alignment

### Phase 4: Advanced (Weeks 13-20)
**Major Features:**
10. **#9** - Group Expenses (3.5w) - Social feature
11. **#2** - Multi-User (5-6w) - Major infrastructure
12. **#6** - Natural Language Search (3w) - Differentiator

### Phase 5: Ecosystem (Future)
**Platform Expansion:**
- #29 Web Dashboard
- #30 API/Integrations
- #28 Wear OS

---

## NEXT STEPS

**To proceed, tell me:**

1. **Which feature numbers** do you want to implement? (e.g., "#14, #21, #4, #7")

2. **In what order?** (e.g., "Start with #14, then #21, then #4")

3. **Implementation depth:**
   - **MVP** - Core functionality only
   - **Full** - All features and polish
   - **Custom** - Specific subset

4. **Timeline constraints?** (e.g., "Need #15 by April 15 for tax season")

**I will then create:**
- Detailed implementation plans with specific file changes
- Database migration scripts
- UI mockup descriptions
- Testing strategies
- Phased rollout plans

---

## APPENDIX: FEATURE DEPENDENCIES

```
#26 (Invoice) requires #25 (Business/Personal)
#19 (Subscription Maintenance) enhances #5 (Advanced Subscriptions)
#2 (Multi-User) requires #27 (Offline-First)
#30 (API) works best with #2 (Multi-User) for auth
```

## FEATURE CATEGORIES

**Tax/Accounting:** #14, #15, #21, #25, #26
**AI-Powered:** #6, #7, #8, #10, #11, #12
**Social/Collaborative:** #2, #9, #18
**Analytics/Insights:** #13, #16, #17, #20
**Convenience:** #4, #23, #24, #28
**Platform:** #27, #29, #30

---

**Document Complete - Ready for Implementation Planning**

*Select your features and I'll create the detailed build plans!*
