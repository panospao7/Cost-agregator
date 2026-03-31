# Integration Guide - Phase 4 Features

## Overview

This guide explains how to integrate the 8 new Phase 4 features into the existing ExpenseTracker app.

## Quick Integration

### Step 1: Add Navigation (5 minutes)

Copy the navigation destinations from `Phase4Navigation.kt`:

```kotlin
// In your navigation setup
object NavigationDestinations {
    const val INVESTMENT_PORTFOLIO = "investment_portfolio"
    const val BANK_CONNECTIONS = "bank_connections"
    const val BILL_REMINDERS = "bill_reminders"
    const val SPENDING_CHALLENGES = "spending_challenges"
    const val ADVANCED_ANALYTICS = "advanced_analytics"
}
```

### Step 2: Add Menu Items (10 minutes)

Use the integration helpers from `FeatureIntegration.kt`:

```kotlin
// In HomeScreen.kt - Add to overflow menu
DropdownMenu(
    expanded = menuExpanded,
    onDismissRequest = { menuExpanded = false }
) {
    // Existing items
    
    // Add new features
    FeatureIntegration.HomeScreenFeatureMenu(
        onInvestmentPortfolio = { 
            navController.navigate("investment_portfolio") 
        },
        onBankConnections = { 
            navController.navigate("bank_connections") 
        },
        onBillReminders = { 
            navController.navigate("bill_reminders") 
        },
        onSpendingChallenges = { 
            navController.navigate("spending_challenges") 
        },
        onAdvancedAnalytics = { 
            navController.navigate("advanced_analytics") 
        }
    )
}
```

### Step 3: Update AndroidManifest.xml (5 minutes)

Add deep links from `deep_links_phase4.xml` to your `AndroidManifest.xml`:

```xml
<intent-filter android:autoVerify="true">
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <category android:name="android.intent.category.BROWSABLE" />
    
    <data android:scheme="expensetracker" android:host="investments" />
    <data android:scheme="expensetracker" android:host="banks" />
    <data android:scheme="expensetracker" android:host="bills" />
    <data android:scheme="expensetracker" android:host="challenges" />
    <data android:scheme="expensetracker" android:host="advanced_analytics" />
</intent-filter>
```

### Step 4: Handle Deep Links (10 minutes)

Update `MainActivity.kt`:

```kotlin
private fun handleIntent(intent: android.content.Intent?) {
    val data = intent?.data ?: return
    if (data.scheme == "expensetracker") {
        when (data.host) {
            // Existing handlers
            "dashboard" -> mainViewModel.navigateToTab(0)
            ...
            
            // NEW: Phase 4 handlers
            "investments" -> showInvestmentPortfolio = true
            "banks" -> showBankConnections = true
            "bills" -> showBillReminders = true
            "challenges" -> showSpendingChallenges = true
            "advanced_analytics" -> showAdvancedAnalytics = true
        }
    }
}
```

## Screen-by-Screen Integration

### 1. Investment Portfolio Screen

**Navigation:**
```kotlin
composable("investment_portfolio") {
    InvestmentPortfolioScreen(
        onNavigateBack = { navController.popBackStack() },
        onAddInvestment = { navController.navigate("add_investment") }
    )
}
```

**Add to Home Screen:**
```kotlin
FeatureIntegration.HomeScreenQuickActions(
    onInvestmentPortfolio = { 
        navController.navigate("investment_portfolio") 
    },
    ...
)
```

### 2. Bank Connections Screen

**Navigation:**
```kotlin
composable("bank_connections") {
    BankConnectionsScreen(
        onNavigateBack = { navController.popBackStack() },
        onAddConnection = { 
            // Initiate OAuth flow
        }
    )
}
```

**Usage:**
```kotlin
// Check if user has connected banks
val connectedCount = bankConnectionDao.getConnectedCount()
if (connectedCount == 0) {
    // Prompt to connect bank
}
```

### 3. Bill Reminders Screen

**Navigation:**
```kotlin
composable("bill_reminders") {
    BillRemindersScreen(
        onNavigateBack = { navController.popBackStack() }
    )
}
```

**Integration with Budget Screen:**
```kotlin
// In BudgetScreen.kt
FeatureIntegration.BudgetScreenActions(
    onBillReminders = { 
        navController.navigate("bill_reminders") 
    },
    onSpendingChallenges = { 
        navController.navigate("spending_challenges") 
    }
)
```

### 4. Spending Challenges Screen

**Navigation:**
```kotlin
composable("spending_challenges") {
    SpendingChallengesScreen(
        onNavigateBack = { navController.popBackStack() },
        onCreateChallenge = { 
            showCreateChallengeDialog = true 
        }
    )
}
```

**Home Screen Widget:**
```kotlin
// Show no-spend streak in HomeScreen
val noSpendStatus = viewModel.noSpendStatus.collectAsState()
if (noSpendStatus.value?.currentStreakDays ?: 0 > 0) {
    // Show streak badge
}
```

### 5. Advanced Analytics Screen

**Navigation:**
```kotlin
composable("advanced_analytics") {
    AdvancedAnalyticsScreen(
        onNavigateBack = { navController.popBackStack() }
    )
}
```

**Add to Analytics Screen:**
```kotlin
// In AnalyticsScreen.kt
FeatureIntegration.AnalyticsScreenAdvancedOption(
    onAdvancedAnalytics = { 
        navController.navigate("advanced_analytics") 
    }
)
```

## Notification Integration

### Bill Reminder Notifications

```kotlin
// In BillReminderWorker.kt
val upcomingBills = billReminderManager.getNotificationsDue()

for (bill in upcomingBills) {
    val intent = Intent(Intent.ACTION_VIEW, 
        Uri.parse("expensetracker://bills?billId=${bill.recurringExpenseId}"))
    val pendingIntent = PendingIntent.getActivity(context, 0, intent, 
        PendingIntent.FLAG_IMMUTABLE)
    
    val notification = NotificationCompat.Builder(context, BILL_REMINDER_CHANNEL)
        .setContentTitle("Bill Due: ${bill.merchant}")
        .setContentText("€${bill.amount} due in ${bill.daysUntilDue} days")
        .setSmallIcon(R.drawable.ic_bill)
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .build()
    
    notificationManager.notify(bill.recurringExpenseId.toInt(), notification)
}
```

### Achievement Notifications

```kotlin
// When 7-day streak achieved
val intent = Intent(Intent.ACTION_VIEW, 
    Uri.parse("expensetracker://challenges?achievement=7day"))
val pendingIntent = PendingIntent.getActivity(context, 0, intent, 
    PendingIntent.FLAG_IMMUTABLE)

val notification = NotificationCompat.Builder(context, ACHIEVEMENT_CHANNEL)
    .setContentTitle("🔥 7-Day Streak!" )
    .setContentText("You haven't spent anything in 7 days!")
    .setSmallIcon(R.drawable.ic_achievement)
    .setContentIntent(pendingIntent)
    .build()
```

### Investment Alerts

```kotlin
// Target price hit
val targetHits = investmentTracker.getTargetPriceHits()
for (investment in targetHits) {
    val intent = Intent(Intent.ACTION_VIEW, 
        Uri.parse("expensetracker://investments?alert=target&id=${investment.id}"))
    // ... create notification
}

// Stop loss hit
val stopLossHits = investmentTracker.getStopLossHits()
for (investment in stopLossHits) {
    val intent = Intent(Intent.ACTION_VIEW, 
        Uri.parse("expensetracker://investments?alert=stoploss&id=${investment.id}"))
    // ... create notification
}
```

## Testing Integration

### 1. Test Navigation
```bash
# ADB commands to test deep links
adb shell am start -W -a android.intent.action.VIEW -d "expensetracker://investments"
adb shell am start -W -a android.intent.action.VIEW -d "expensetracker://banks"
adb shell am start -W -a android.intent.action.VIEW -d "expensetracker://bills"
adb shell am start -W -a android.intent.action.VIEW -d "expensetracker://challenges"
```

### 2. Test Menu Items
- Open each screen from menu
- Verify back navigation works
- Verify state restoration on rotation

### 3. Test Deep Links
- Send test notifications
- Click notification → verify correct screen opens
- Test with parameters (billId, achievement, etc.)

## UI Integration Examples

### Quick Actions Section

Add to `HomeScreen.kt`:

```kotlin
@Composable
fun HomeScreen(
    ...
) {
    LazyColumn {
        // Existing content
        
        item {
            FeatureIntegration.HomeScreenQuickActions(
                onInvestmentPortfolio = { navController.navigate("investment_portfolio") },
                onBankConnections = { navController.navigate("bank_connections") },
                onBillReminders = { navController.navigate("bill_reminders") },
                onSpendingChallenges = { navController.navigate("spending_challenges") }
            )
        }
    }
}
```

### Drawer/Navigation Rail

Add to navigation drawer:

```kotlin
ModalNavigationDrawer(
    drawerContent = {
        ModalDrawerSheet {
            // Existing items
            
            HorizontalDivider()
            
            FeatureIntegration.NavigationDrawerItems(
                onInvestmentPortfolio = { navController.navigate("investment_portfolio") },
                onBankConnections = { navController.navigate("bank_connections") },
                onBillReminders = { navController.navigate("bill_reminders") },
                onSpendingChallenges = { navController.navigate("spending_challenges") },
                onAdvancedAnalytics = { navController.navigate("advanced_analytics") }
            )
        }
    }
) {
    // Screen content
}
```

## Best Practices

1. **Lazy Loading**: Don't load all feature data on app start
2. **State Restoration**: Use `rememberSaveable` for UI state
3. **Error Handling**: Show friendly error messages
4. **Empty States**: Always show helpful empty state UI
5. **Loading States**: Show skeletons or progress indicators
6. **Accessibility**: Add content descriptions to all icons
7. **Theming**: Respect Material You dynamic theming

## Troubleshooting

### Issue: Screen not opening from deep link
- Verify `AndroidManifest.xml` has correct intent-filter
- Check `handleIntent()` is called in `MainActivity.onCreate()`

### Issue: Menu items not showing
- Ensure `FeatureIntegration.kt` is imported
- Check that you're adding items inside a valid container

### Issue: ViewModel not found
- Verify `@HiltViewModel` annotation is present
- Check Hilt modules include new ViewModels

## Summary

**Time to integrate: ~30 minutes**

1. Copy navigation destinations (5 min)
2. Add menu items (10 min)
3. Update manifest (5 min)
4. Handle deep links (10 min)

All 8 Phase 4 features will be fully integrated into your app!
