# ExpenseTracker

[![Kotlin](https://img.shields.io/badge/kotlin-2.2-blue.svg)]()
[![Android](https://img.shields.io/badge/android-API%2026+-green.svg)]()
[![License](https://img.shields.io/badge/license-MIT-yellow.svg)]()

A comprehensive Android expense tracking application with **28+ features** including AI-powered analytics, investment tracking, multi-currency support (17 currencies), bank API integration, shared expense groups, receipt OCR, and much more.

## Key Features

- **AI-Powered Budget Forecasting** — Predict spending patterns with Monte Carlo simulation and smart recommendations via Gemini API
- **Investment Tracking** — Track stocks, crypto, bonds, ETFs with portfolio analytics and value snapshots
- **Bank API Integration** — Connect to 6 major banks for automatic transaction import with OAuth
- **Multi-Currency Support** — 17 currencies with type-safe `MoneyAmount` primitives, real-time conversion, stale-rate policies
- **Shared Expense Groups** — Split expenses with friends, track balances, settlements, and lifecycle events
- **Advanced Analytics** — 12+ analytics engines (DailyBucket, BudgetVsActual, CategoryInsight, AnomalyDetection, SpendingPace)
- **Receipt OCR** — Multi-language receipt scanning via ML Kit with AI-powered categorization
- **Receipt-to-Expense Matching** — Automatic matching with lifecycle-aware coordination
- **Subscription Management** — Track price changes, usage, renewal dates with cancellation detection
- **Warranty Tracker** — Auto-extract warranty periods from receipts, expiration notifications
- **Bill Reminders** — Intelligent alerts with snooze/dismiss, quiet hours, recurring dispatch
- **Spending Challenges** — Gamified no-spend streaks and challenges
- **Savings Goals** — Automated savings with progress tracking
- **Recurring Expenses** — Create recurring rules with auto-generation of occurrences
- **Cash Flow Calendar** — Visual calendar with income/expenses/balance per day
- **Business/Personal Split** — Separate business/personal expenses with tax reports
- **Tax Estimation** — Estimate tax liability from business expenses
- **Carbon Footprint Tracking** — Track environmental impact of spending
- **Lifestyle Inflation Detector** — Monitor lifestyle creep over time
- **Smart Bill Negotiation** — AI-assisted negotiation suggestions based on market rates
- **Price Protection** — Track price drops and file claims automatically
- **Natural Language Search** — Search expenses using plain English via AI
- **Enhanced Split Transactions** — Visual split editor with custom percentages, templates
- **Export to Accounting Software** — QuickBooks IIF, Xero CSV, FreshBooks CSV, PDF
- **Backup & Restore** — Encrypted database backup with crash-safe restore journal
- **Privacy Controls** — Granular privacy toggles (cloud AI, geocoding, notification capture) with fail-closed defaults
- **Spending Map** — Geo-mapped expenses with OpenStreetMap integration

## Architecture

```
ExpenseTracker/
├── app/src/main/java/com/yourname/expensetracker/
│   ├── data/
│   │   ├── database/
│   │   │   ├── entity/          # 70 entities (Room @Entity)
│   │   │   ├── dao/             # 68-69 DAOs
│   │   │   └── AppDatabase.kt   # Version 147 (140+ migrations)
│   │   └── repository/          # 54+ repositories
│   ├── domain/
│   │   ├── core/money/          # Type-safe MoneyAmount, CurrencyCode, MoneyAggregate
│   │   ├── analytics/           # 12+ analytics engines
│   │   ├── ai/                  # HybridRouter (Gemini + ML Kit)
│   │   ├── bank/                # Bank API integration (6 banks)
│   │   ├── budget/              # Budget management & forecasting
│   │   ├── categorization/      # AI categorization engines
│   │   ├── challenge/           # Spending challenges
│   │   ├── currency/            # Multi-currency (17 currencies)
│   │   ├── forecast/            # Cash flow & stress forecasting
│   │   ├── groups/              # Shared expense groups
│   │   ├── health/              # Financial health score
│   │   ├── income/              # Recurring income
│   │   ├── investment/          # Investment tracking
│   │   ├── location/            # Geocoding (6 providers)
│   │   ├── negotiation/         # Bill negotiation
│   │   ├── notification/        # Notification processing
│   │   ├── privacy/             # Privacy gate enforcement
│   │   ├── receipt/             # OCR & matching lifecycle
│   │   ├── recurring/           # Recurring expense lifecycle
│   │   ├── reminder/            # Bill reminders
│   │   ├── savings/             # Savings goals
│   │   ├── subscription/        # Subscription management
│   │   ├── tax/                 # Tax estimation
│   │   └── warranty/            # Warranty tracking
│   ├── di/                      # 33-35 Hilt modules
│   ├── service/                 # NotificationCaptureService, ReceiptMatching, etc.
│   ├── worker/                  # 7 WorkManager workers
│   └── ui/
│       ├── screens/             # 39 screens across 36 feature directories
│       └── components/          # 40+ reusable composables
├── app/src/test/                # 200+ unit tests
├── app/src/androidTest/         # 27+ instrumented tests
├── config/
│   └── db_access_allowlist.yml  # Database write access control
├── docs/
│   ├── architecture/            # Architecture guides, maps, inventories
│   ├── features/                # Feature documentation
│   ├── testing/                 # Testing strategy & status
│   ├── currency/                # Multi-currency contracts & policies
│   ├── privacy/                 # Raw storage policy
│   └── releases/                # Changelog & release notes
└── scripts/                     # CI guard scripts (Python, Kotlin, PowerShell)
```

## Technologies

- **Language:** Kotlin 2.2.21
- **UI:** Jetpack Compose (BOM 2024.11), Material3
- **Database:** Room 2.7.2 (SQLite) with KSP — 147 schema migrations
- **DI:** Dagger Hilt 2.57 (33+ modules, 41+ ViewModels)
- **Async:** Kotlinx Coroutines 1.8.1 + Flow
- **AI:** Google ML Kit Text Recognition + Gemini API + GenAI Prompt
- **Charts:** Vico 1.13.1 (compose-m3)
- **Maps:** osmdroid 6.1.18 (OpenStreetMap)
- **Location:** Google Play Services Location 21.3.0
- **Geocoding:** Nominatim, Photon, Geoapify, Google Places, Overpass API
- **Image Loading:** Coil 2.5.0
- **PDF:** PDFBox Android 2.0.27.0
- **Networking:** OkHttp 4.12.0
- **Security:** AndroidX Security Crypto 1.1.0 (AES-256-GCM)
- **Serialization:** Gson 2.10.1
- **Background:** WorkManager 2.9.1 (7 workers)
- **Logging:** Timber 5.0.1

## Testing

```bash
# Run unit tests
./gradlew testDebugUnitTest

# Run instrumented tests (emulator/device required)
./gradlew connectedAndroidTest

# Run all verification guards (schema, bypass, boundaries, etc.)
./gradlew check
```

### Test Scope

- **200+ unit tests:** Architecture guards, consistency, contract, currency, DI, domain, E2E flows, golden masters, guards, integration, metrics, scenarios, services, startup, verification
- **27+ instrumented tests:** DAO stress tests, migration contract tests, database migration verification, location worker tests
- **Verification guards** (registry-owned; wired into `check` and the static-guard suite):
  - Room schema snapshot verification
  - DB access boundary guard (exact ownership-policy authorization, protocol v2 ratchet)
  - Raw money aggregates guard (no raw `Double` financial arithmetic)
  - Direct time calls guard (enforces `TimeProvider` abstraction)
  - Ignored test growth guard

  The full per-guard inventory, canonical commands, and evidence state are
  generated, never hand-copied: `docs/ci/GUARD_COMMANDS.generated.md` and
  `docs/ci/GUARD_STATUS.generated.md` (indexed by
  `docs/ci/GUARD_DOCUMENT_INDEX.yml`).

## CI Pipeline

GitHub Actions CI runs: unit tests → schema verification → ignored test guard → event writer boundaries → privacy boundaries → money boundaries → currency guardrails → lint → debug build → instrumented tests (API 34).

## Feature Matrix

| Feature | Status | UI | DB | Tests |
|---------|--------|-----|-----|-------|
| Warranty & Return Tracker | ✅ | ✅ | ✅ | ✅ |
| Accounting Export (IIF/CSV/PDF) | ✅ | ✅ | ✅ | ✅ |
| Cash Flow Calendar | ✅ | ✅ | ✅ | ✅ |
| Receipt-to-Expense Matching | ✅ | ✅ | ✅ | ✅ |
| Smart Savings Goals | ✅ | ✅ | ✅ | ✅ |
| Subscription Management | ✅ | ✅ | ✅ | ✅ |
| Business/Personal Split | ✅ | ✅ | ✅ | ✅ |
| Multi-Currency (17 currencies) | ✅ | ✅ | ✅ | ✅ |
| Shared Expense Groups | ✅ | ✅ | ✅ | ✅ |
| AI Budget Forecasting | ✅ | ✅ | ✅ | ✅ |
| Enhanced Receipt OCR | ✅ | ✅ | ✅ | ✅ |
| Investment Tracking | ✅ | ✅ | ✅ | ✅ |
| Bank API Integration (6 banks) | ✅ | ✅ | ✅ | ✅ |
| Advanced Analytics Dashboard | ✅ | ✅ | ✅ | ✅ |
| Shared Budgets | ✅ | ✅ | ✅ | ✅ |
| Recurring Income | ✅ | ✅ | ✅ | ✅ |
| Tax Estimation | ✅ | ✅ | ✅ | ✅ |
| Bill Reminders | ✅ | ✅ | ✅ | ✅ |
| Spending Challenges | ✅ | ✅ | ✅ | ✅ |
| Spending Map | ✅ | ✅ | ✅ | ✅ |
| Natural Language Search | ✅ | ✅ | ✅ | ✅ |
| Visual Split Editor | ✅ | ✅ | ✅ | ✅ |
| Recurring Expenses | ✅ | ✅ | ✅ | ✅ |
| Backup & Restore | ✅ | ✅ | ✅ | ✅ |
| Carbon Footprint Tracking | ✅ | ✅ | ✅ | ✅ |
| Lifestyle Inflation Detector | ✅ | ✅ | ✅ | ✅ |
| Smart Bill Negotiation | ✅ | ✅ | ✅ | ✅ |
| Price Protection | ✅ | ✅ | ✅ | ✅ |

## Quick Start

### Prerequisites

- Android Studio Arctic Fox or later
- JDK 11 or later
- Android SDK 26+ (Android 8.0)
- Kotlin 2.2+

### Installation

```bash
git clone https://github.com/yourusername/expensetracker.git
cd expensetracker
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Configuration

1. **API Keys** (optional for demo mode):
   ```kotlin
   // Add to local.properties
   GEMINI_API_KEY=your_gemini_key
   ```

2. **Bank API Setup** (optional):
   - Configure OAuth credentials (currently supports demo mode with mock data)

## Requirements

- **Min SDK:** 26 (Android 8.0)
- **Target SDK:** 35 (Android 15)
- **Compile SDK:** 35
- **Java:** 11

## Documentation

- **[Feature Documentation](docs/features/FEATURES.md)** — Comprehensive feature catalog
- **[Architecture Guide](docs/architecture/ARCHITECTURE.md)** — Architecture overview, layer structure, data flow
- **[Changelog](docs/releases/CHANGELOG.md)** — Version history (v0.1.0 through v2.2.0)
- **[Release Notes](docs/releases/RELEASE_NOTES.md)** — v2.0.0 release notes

## Stats

- **Features:** 28+ (across 5 phases)
- **Production source files:** ~1,054 Kotlin
- **Test files:** ~200+ unit, 27+ instrumented
- **Database entities:** 70
- **Database version:** 147 (140+ migrations)
- **UI screens:** 39
- **ViewModels:** 41
- **Hilt modules:** 33-35
- **Git commits:** 840+
- **Lines of code:** 60,000+
- **Test coverage:** 80%+
- **CI guards:** registry-owned inventory (blocking + ratchet guards) — see docs/ci/GUARD_COMMANDS.generated.md and docs/ci/GUARD_STATUS.generated.md

## License

MIT License

Copyright (c) 2026 ExpenseTracker Contributors

## Acknowledgments

- Jetpack Compose Team
- Kotlin Team
- Android Jetpack
- Google ML Kit & Gemini AI
- OpenStreetMap (osmdroid)
- All Contributors

---

*Last updated: June 15, 2026*
