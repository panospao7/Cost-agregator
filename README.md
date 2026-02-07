# ExpenseTracker

Personal expense aggregator that captures transaction notifications from multiple sources.

## Setup

1. Open this project in Android Studio
2. Sync Gradle
3. Build and run on your device
4. Grant notification access permission when prompted
5. Watch notifications appear in the Debug screen

## Project Structure
```
app/src/main/java/com/yourname/expensetracker/
├── ExpenseTrackerApp.kt              # Hilt Application
├── di/AppModule.kt                   # Hilt Module
├── data/
│   ├── database/
│   │   ├── AppDatabase.kt
│   │   ├── entity/                   # Realm Entities (RawNotification, BlockedPackage)
│   │   └── dao/                      # DAOs
│   └── repository/NotificationRepository.kt
├── service/NotificationCaptureService.kt  # Capture Service
└── ui/
    ├── MainActivity.kt               # Bottom Navigation Host
    ├── screens/
    │   ├── home/HomeScreen.kt        # Dashboard
    │   └── debug/DebugScreen.kt      # Debug & Filtering
    └── theme/
```

## Features

### 1. Notification Capture
- Captures notifications from all apps (Discovery Mode).
- Filter out spam/irrelevant apps permanently using the **Block App** button.
- Notifications are stored locally in a Room database.

### 2. Dashboard
- **Home**: View high-level stats (Total captured).
- **Debug**: real-time feed of captured notifications.

## Usage Guide
1. **Grant Permissions**: Allow Notification Access when prompted.
2. **Collect Data**: Let the app run. It will capture purchase notifications.
3. **Filter Noise**:
   - Go to **Debug** tab.
   - If you see a non-expense app (e.g., Spotify, WhatsApp), click the **Trash Icon** (Block App).
   - This app will be ignored in the future.
4. **Identify Expenses**:
   - Find a real bank notification.
   - Mark it as "Expense ✓".
   - (Later: We will use these examples to build parsers).

## Next Steps

After collecting real notification data:
1. Analyze notification patterns from Revolut, Google Pay, bank SMS
2. Build specific parsers for each source
3. Implement deduplication
4. Add categorization
