# ExpenseTracker

Personal expense aggregator that captures transaction notifications from multiple sources.

## Setup

1. Open this project in Android Studio (Arctic Fox or later)
2. Sync Gradle wrapper: `./gradlew wrapper`
3. Build: `./gradlew assembleDebug`
4. Install the APK on your device
5. Grant notification access permission when prompted
6. Grant "Usage Access" for enhanced notification capture (optional)

## Build Commands

```bash
# Debug build
./gradlew assembleDebug

# Release build
./gradlew assembleRelease

# Run tests
./gradlew test

# Run unit tests only
./gradlew testDebugUnitTest
```

## Project Architecture

The app follows Clean Architecture with MVVM:

```
app/src/main/java/com/yourname/expensetracker/
├── ExpenseTrackerApp.kt              # Hilt Application
├── di/                               # Dependency Injection
│   ├── DatabaseModule.kt
│   ├── DaoModule.kt
│   └── ServiceModule.kt
├── data/
│   ├── database/
│   │   ├── AppDatabase.kt            # Room Database
│   │   ├── entity/                   # Entity classes
│   │   ├── dao/                     # Data Access Objects
│   │   └── converter/                # Type converters
│   └── repository/                   # Repositories
├── domain/
│   ├── model/                        # Domain models
│   ├── budget/                       # Budget logic
│   ├── logic/                        # Business logic engines
│   ├── intelligence/                # ML & classification
│   ├── usecase/                     # Use cases
│   ├── util/                        # Utilities
│   └── config/                      # Configuration
├── service/
│   └── NotificationCaptureService.kt  # Notification listener
├── ui/
│   ├── MainActivity.kt
│   ├── components/                   # Reusable composables
│   ├── screens/                      # Feature screens
│   └── theme/
└── receiver/                         # Broadcast receivers
```

## Key Features

### 1. Notification Capture
- Captures notifications from all apps (Discovery Mode)
- Filters spam/irrelevant apps using Block App feature
- Stores notifications locally in Room database
- Supports multiple notification types (SMS, Banking, Payment apps)

### 2. Budget Tracking
- Set budgets by category (Daily, Weekly, Monthly, Yearly)
- Rollover support for unused budget
- Alert notifications at Warning (80%) and Critical (95%) thresholds

### 3. Receipt Scanning
- OCR-powered receipt parsing
- Auto-extract merchant, amount, date, tax
- Supports Greek and English receipts

### 4. Financial Forecasting
- Block Party: Daily spending tracking with targets
- Spending Pace: Track vs previous months
- Weather: Financial health indicator

### 5. ML Classification
- Automatic category prediction
- Merchant normalization
- Confidence-based routing (Auto-accept, Review, Auto-reject)

## Dependencies

- **Jetpack Compose**: UI framework
- **Room**: Local database
- **Hilt**: Dependency injection
- **ML Kit**: Text recognition (OCR)
- **Kotlin Coroutines & Flow**: Async operations
- **Material 3**: Design system

## Permissions Required

- `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` - Read notifications
- `android.permission.POST_NOTIFICATIONS` - Send alerts (Android 13+)
- `android.permission.CAMERA` - Receipt scanning
- `android.permission.READ_EXTERNAL_STORAGE` - Receipt image access

## Configuration

Thresholds and settings are centralized in:
`domain/config/AppConfig.kt`

## Testing

The project includes comprehensive unit tests:
```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew testDebugUnitTest --tests "com.yourname.expensetracker.domain.util.AmountUtilsTest"
```

## License

Private - For personal use only
