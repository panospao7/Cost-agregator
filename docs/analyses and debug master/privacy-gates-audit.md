# Privacy Capability Gates Audit

**Date**: 2026-05-01
**Scope**: `app/src/main/java/` — all `.kt` files
**App**: ExpenseTracker (Android)
**Phase**: 6 — Privacy Capability Gates

---

## Summary Statistics

| Category | Count |
|---|---|
| External HTTP call sites (OkHttpClient injections) | **12 OkHttpClient providers**, **~15 unique HTTP call sites** |
| Cloud AI entry points (Gemini API calls) | **8 cloud AI service classes** |
| Geocoding call sites (external location services) | **5 geocoding services** + **1 Overpass POI service** |
| Backup/export entry points | **3 export formats** + **SQLite raw backup** |
| Raw data retention points | **`raw_notifications` table** (indefinite), **`scanned_receipts.rawOcrText`** (indefinite) |
| Existing privacy settings (DataStore keys) | **19 boolean/preference keys** in `ai_settings` DataStore |
| Files analyzed | **~200+ .kt files** |
| Existing privacy gates | **Partial** — Cloud AI has `allowCloudAi` + `redactBeforeCloud` + `receiptImageCloudEnabled` guards; geocoding has log anonymization; notification capture has package-level filtering |
| Missing privacy gates | **Notification capture has NO master toggle**, **location/geocoding has NO user-facing enable/disable**, **backup is unencrypted**, **raw data has NO retention/purge mechanism**, **notification blocking is only in debug screen** |

---

## 1. Notification Capture Privacy

### 1.1 NotificationCaptureService

**File**: `service/NotificationCaptureService.kt`

**How it determines whether to capture**:
1. **Package blocklist check**: `repository.isPackageBlocked(packageName)` — line 327. Blocked packages are stored in a `blocked_packages` Room table.
2. **NotificationFilter.shouldCapture()**: Called for every notification at lines 278-283 and 397-402. The filter logic:
   - **Finance packages** (`FINANCE_PACKAGES`): Always captured unconditionally. List includes: `com.revolut.revolut`, `com.google.android.apps.walletnfcrel`, `gr.nbg.mobilebanking`, `mbanking.NBG`, `com.eurobank.mobile`, `gr.alpha.mobile`, `com.winbank.mobile`, etc.
   - **Communication packages** (`COMMUNICATION_PACKAGES`): Must pass heuristic (currency amount + financial keyword). Includes Viber, Gmail, SMS apps.
   - **Unconditionally ignored** (`IGNORED_PACKAGES`): `android`, `com.android.systemui`, `com.android.settings`, WhatsApp, Facebook, Instagram, Snapchat, YouTube.
3. **Deduplication cache**: In-memory `LinkedHashMap` with 500-entry limit and 60-second TTL, keyed by `notificationKey + contentHash`.

**Privacy gate status**: **⚠️ PARTIAL — No master capture toggle**
- There is NO `captureEnabled` boolean anywhere in the codebase.
- The only off-switch is blocking individual packages (which requires using the Debug screen).
- No user-accessible settings screen toggle for "Enable notification capture" exists.

### 1.2 NotificationFilter

**File**: `service/NotificationFilter.kt`

Hardcoded package lists:
- **FINANCE_PACKAGES** (8 entries): Greek banks + Revolut + Google Wallet
- **COMMUNICATION_PACKAGES** (5 entries): Viber, Gmail, SMS apps
- **IGNORED_PACKAGES** (8 entries): System + social media apps

Heuristic detection uses regex for currency symbols (`€$£¥`) and amount patterns (`\d+[.,]\d{2}`), plus a set of English and Greek financial keywords (`paid`, `spent`, `πληρωμ`, `αγορ`, etc.).

### 1.3 RawNotification Entity

**File**: `data/database/entity/RawNotification.kt`

**Stored fields**:
```
id (Long, auto-generated)
packageName (String)
appName (String?)
title (String?)
text (String?)
bigText (String?)
subText (String?)
extrasJson (String?)  — Full extras bundle as JSON
timestamp (Long)      — When notification was posted
capturedAt (Long)     — When we captured it
isProcessed (Boolean, default false)
isRelevant (Boolean?, null = unknown)
parseResult (String?) — JSON of parsed data or error
```

**Retention**: **🔴 NO PURGE MECHANISM EXISTS**
- No `purgeRawNotification`, `deleteOldNotifications`, `retentionDays`, or `AUTO_PURGE` logic found anywhere.
- `raw_notifications` table grows indefinitely.
- Query `grep` for `purge.*notification|rawNotification.*delete|delete.*rawNotification|retention.*notification` returned zero results.

**Indices**: `(packageName, timestamp)`, `(capturedAt)`, `(isRelevant)`, `(packageName, timestamp, title, text, bigText)`

### 1.4 Notification Capture Settings

**Search results**: `captureEnabled`, `captureNotification`, `notificationCapture`, `NOTIFICATION_CAPTURE` — **ZERO MATCHES IN ANY `.kt` FILE**

There is no user-facing toggle for notification capture. The feature is always-on once the service binds.

### 1.5 Blocked Packages

**File**: `data/repository/NotificationRepository.kt` (lines 96-102)

```kotlin
suspend fun blockPackage(packageName: String)
suspend fun unblockPackage(packageName: String)
suspend fun isPackageBlocked(packageName: String): Boolean
```

Only accessible via the Debug screen (`debug/DebugScreen.kt`). Not exposed in any user-facing settings.

---

## 2. Cloud AI / External Service Privacy

### 2.1 Cloud AI Service Classes (8 total)

All use OkHttpClient to call the Gemini API at `https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent`:

| Service Class | File | Purpose | Privacy Checks |
|---|---|---|---|
| `CloudReceiptAssistService` | `data/ai/provider/CloudReceiptAssistService.kt` | OCR + image receipt parsing | `allowCloudAi`, `redactBeforeCloud`, `receiptImageCloudEnabled` |
| `CloudDedupeJudgeService` | `data/ai/provider/CloudDedupeJudgeService.kt` | Duplicate detection via cloud | `redactBeforeCloud` |
| `CloudDashboardBriefingService` | `data/ai/provider/CloudDashboardBriefingService.kt` | AI dashboard summaries | `redactBeforeCloud` |
| `CloudReviewExplanationService` | `data/ai/provider/CloudReviewExplanationService.kt` | Review explanation text | `redactBeforeCloud` |
| `CloudCategorizationAssistService` | `data/ai/provider/CloudCategorizationAssistService.kt` | Category suggestions | `redactBeforeCloud` |
| `CloudReceiptItemCategorizationService` | `data/ai/provider/CloudReceiptItemCategorizationService.kt` | Line-item categorization | (no explicit gate seen in available read) |
| `CloudWarrantyExtractionService` | `data/ai/provider/CloudWarrantyExtractionService.kt` | Warranty info extraction | (no explicit gate seen in available read) |
| `CloudQueryInterpretationService` | `data/ai/provider/CloudQueryInterpretationService.kt` | NL query interpretation | (no explicit gate seen in available read) |

### 2.2 Receipt Assist Decision Chain

**File**: `data/ai/provider/SmartReceiptAssistService.kt`

**How it decides cloud vs on-device**:
1. Reads `AiSettings` from `aiSettingsRepository.settings().first()`
2. Gets route decision from `AiCapabilityRouter.decide(AiCapability.RECEIPT_EXTRACTION, settings)`
3. Based on `AiRoute` (CLOUD, ON_DEVICE, DETERMINISTIC_FALLBACK, DISABLED):
   - **CLOUD**: Attempts Cloud Vision → On-Device Vision → Cloud Text → On-Device Text
   - **ON_DEVICE**: Attempts On-Device Vision → Cloud Vision → On-Device Text → Cloud Text
   - **DETERMINISTIC_FALLBACK/DISABLED**: Skips all AI, uses `NoOpReceiptAssistService`

**Privacy gate in SmartReceiptAssistService**:
- `resolveRouteViability()` — **PRIVACY FIX**: When user selected `ON_DEVICE` mode, `cloudAvailable = false` is hardcoded, preventing any cloud fallback.

### 2.3 CloudReceiptAssistService Privacy Gates

**File**: `data/ai/provider/CloudReceiptAssistService.kt`

**Three privacy guards** (lines 70-85):
1. **API key check** (line 71): `if (apiKey.isBlank())` — silently skip, no network call.
2. **`allowCloudAi` setting** (line 79): `if (!settings.allowCloudAi)` — skip.
3. **Image upload suppression** (line 314-322): If `shouldRedact` is true, image upload is suppressed entirely because "images cannot be meaningfully redacted".

**Redaction applied to prompt content** (method `buildPrompt`, lines 223-311):
- Merchant: `CloudPiiSanitizer.sanitizeMerchant()` → SHA-256 hash prefix
- Line items JSON: `CloudPiiSanitizer.sanitizeText()` → PII regex replacement
- Raw OCR text: `CloudPiiSanitizer.sanitizeText()` → PII regex replacement

**Image upload is controlled by two settings**:
- `receiptImageCloudEnabled` — must be true (default: false, opt-in)
- `redactBeforeCloud` — if true, suppresses image upload (default: true)

### 2.4 Redaction/Sanitization

**Two sanitizer layers exist**:

**`CloudPiiSanitizer`** (`data/ai/provider/internal/CloudPiiSanitizer.kt`):
- Regex-based PII redaction: emails, IBANs, credit card numbers, phone numbers, long numbers
- Merchant name → `merchant_<sha256prefix>` (12 hex chars)
- Used by all cloud AI services

**`RedactionSanitizer`** (`domain/privacy/RedactionSanitizer.kt`):
- Domain-layer interface with `DefaultRedactionSanitizer` implementation
- Merchant name → `merchant_<sha256prefix>` (12 hex chars)
- Used by `CategorizationAssistInputBuilder` for local categorization history

**`LogSanitizer`** (`data/location/internal/LogSanitizer.kt`):
- SHA-256 with per-process salt (32 bytes, generated at startup via `SecureRandom`)
- Used as `String.anonymizeForLog()` extension
- Applied to merchant names, query strings, coordinates in ALL geocoding service logs
- **Also used by `LocationBackfillWorker`** and `CompositeGeocodingService`

### 2.5 Cloud AI Settings

**File**: `domain/ai/model/AiModels.kt` — `AiSettings` data class

**`AiSettings` defaults**:
```kotlin
aiEnabled: Boolean = true,
allowCloudAi: Boolean = false,           // OFF by default — privacy
allowOnDeviceAi: Boolean = true,
receiptAssistEnabled: Boolean = false,   // OFF by default — privacy
receiptImageCloudEnabled: Boolean = false, // OFF by default — privacy
redactBeforeCloud: Boolean = true,       // ON by default — privacy
wifiOnlyForCloud: Boolean = false,
storeConversationHistory: Boolean = false,
```

**Storage**: DataStore (file: `ai_settings`)

**File**: `data/repository/AiSettingsRepositoryImpl.kt` — 19 boolean preference keys

**DataStore keys** (all start with `ai_`):
`ai_enabled`, `ai_allow_cloud`, `ai_allow_on_device`, `ai_assistant_enabled`, `ai_query_interpretation_enabled`, `ai_dashboard_briefing_enabled`, `ai_review_explanation_enabled`, `ai_receipt_assist_enabled`, `ai_warranty_extraction_enabled`, `ai_receipt_image_cloud_enabled`, `ai_receipt_item_categorization_enabled`, `ai_categorization_fallback_enabled`, `ai_dedupe_judge_enabled`, `ai_proactive_briefings`, `ai_receipt_quick_save`, `ai_review_quick_approve`, `ai_redact_before_cloud`, `ai_wifi_only_for_cloud`, `ai_store_conversation_history`, `ai_preferred_mode`

---

## 3. Location / Geocoding Privacy

### 3.1 External Location Services (6 total)

| Service | File | Base URL | API Key Needed | Privacy Measures |
|---|---|---|---|---|
| `NominatimGeocodingService` | `data/location/NominatimGeocodingService.kt` | `nominatim.openstreetmap.org` | No | Log anonymization, rate limiting (1 req/s) |
| `PhotonGeocodingService` | `data/location/PhotonGeocodingService.kt` | `photon.komoot.io` | No | Log anonymization |
| `GeoapifyGeocodingService` | `data/location/GeoapifyGeocodingService.kt` | `api.geoapify.com` | Yes (SecureKeyStorage) | Log anonymization, safe log routes |
| `GooglePlacesGeocodingService` | `data/location/GooglePlacesGeocodingService.kt` | `places.googleapis.com` | Yes (SecureKeyStorage) | Log anonymization, safe log routes |
| `CompositeGeocodingService` | `data/location/CompositeGeocodingService.kt` | (aggregator) | Varies | Log anonymization, cascading fallbacks |
| `OverpassNearbyService` | `data/location/OverpassNearbyService.kt` | `overpass-api.de` | No | User-Agent header only |

### 3.2 LocationResolver

**File**: `domain/location/LocationResolver.kt`

**Resolution priority**:
1. User correction (area-scoped)
2. Merchant cache hit
3. History-biased lookup (clusters)
4. Nominatim with GPS bias (transaction < 2 hrs old)
5. Nominatim name-only (Greece bias)
6. Overpass nearby POIs
7. Unresolved

**Privacy status**: **⚠️ PARTIAL**
- All merchant names and queries are anonymized in logs via `anonymizeForLog()`
- GPS bias only applies to transactions < 2 hours old (`RECENT_TRANSACTION_THRESHOLD_MS`)
- **NO user-facing toggle to disable geocoding/location resolution**
- **NO master "location services enabled" setting**
- Backfill worker runs on Wi-Fi only (`NetworkType.UNMETERED`)

### 3.3 LocationBackfillWorker

**File**: `data/location/LocationBackfillWorker.kt`

- Periodic worker (every 6 hours)
- Processes at most 50 expenses per run
- Wi-Fi only constraint (`NetworkType.UNMETERED`)
- Scheduled from `AppStartupCoordinator` (line 56)
- **No user-facing toggle to disable backfill**
- **No privacy gate check before calling LocationResolver**

### 3.4 MerchantKeyBackfillWorker

**File**: `data/location/MerchantKeyBackfillWorker.kt`

- One-time worker that backfills merchant keys on legacy rows
- Scheduled from `AppStartupCoordinator` (line 57)
- **No privacy relevance** (only updates existing database keys)

### 3.5 Device GPS Access

**File**: `data/location/AndroidForegroundLocationProvider.kt`

- Uses Fused Location Provider (Google Play Services)
- Requests `Priority.PRIORITY_BALANCED_POWER_ACCURACY`
- Falls back to `lastLocation`
- **Checks runtime permission** (`ACCESS_FINE_LOCATION`/`ACCESS_COARSE_LOCATION`) before accessing location
- Returns null if permission not granted

**Callers of `getLastKnownLocation()`**:
- `LocationResolver.resolve()` — for GPS bias and Overpass queries
- `NotificationProcessingPipeline` (line 671) — for capturing device GPS at notification time
- `SpendingMapViewModel` (line 504) — for map center
- `LocationBackfillWorker` (via LocationResolver)

### 3.6 Location Settings

**Search results**: `locationEnabled`, `geocoding.*enabled`, `backgroundLocation`, `location.*setting` — **ZERO MATCHES**

There are NO user-facing location privacy settings anywhere in the app.

---

## 4. Backup / Export Privacy

### 4.1 Database Backup/Export

**File**: `data/repository/DatabaseBackupRepositoryImpl.kt`

**Backup method**: Raw SQLite `.db` file copy to `app/filesDir/exports/`
**Format**: Unencrypted SQLite database
**Password**: **NONE** — no encryption, no password protection
**Contents**: Entire Room database including:
- `expenses` table (merchants, amounts, categories, locations, notes)
- `raw_notifications` table (full notification content)
- `scanned_receipts` table (including `rawOcrText`)
- `user_corrections` table
- `pending_reviews` table
- All other tables

**Key observations**:
- **Not encrypted** — plain `.db` file copy
- **ZIP/manifest format mentioned in comments** (line 32-34) but NOT implemented
- **Receipt images NOT included** (line 31-32) — but raw OCR text IS included
- **Safety backups** kept for last 3 runs (line 1143-1145)
- **Legacy backup detection**: Checks `Downloads/` folder for old unencrypted backups from prior versions (line 281-301)

**Interface**: `domain/backup/DatabaseBackupRepository.kt`
- `exportDatabase()` → Result<File>
- `importDatabase(sourceFile)` → Result<DatabaseImportSummary>
- `resetDatabase()` → Result<Unit>
- `createSafetyBackup()` → Result<File>

**Privacy gate status**: **🔴 MISSING**
- No encryption
- No password
- Raw notifications and OCR text are included in the backup
- No user-facing "include raw data" toggle

### 4.2 Accounting Export (User-Initiated)

**File**: `data/repository/AccountingExportRepository.kt`

**Export formats**:
- QuickBooks IIF (`.iif`)
- Xero CSV (`.csv`)
- FreshBooks CSV (`.csv`)
- Accountant Report PDF (`.pdf`)

**Contents**: Expenses only (filtered by date range, PURCHASE type only, single currency required by policy)

**File**: `domain/export/AccountantReportPdfExporter.kt`
- PDF report with: merchant names, amounts, categories, dates
- Per-currency breakdowns
- Large transaction highlights (>$500)

**File**: `domain/export/AccountingExportPolicy.kt`
- Validates single currency
- Validates purchase-only transactions

**Privacy gate status**: **⚠️ PARTIAL**
- Only structured expense data exported (no raw notifications or OCR text)
- But merchant names are in plain text — no redaction option
- No "anonymize export" toggle

### 4.3 Raw Data in Backup

- **Raw notifications**: YES — entire `raw_notifications` table is included in `.db` backup
- **Raw OCR text**: YES — `scanned_receipts.rawOcrText` column is included
- **Extras JSON**: YES — `raw_notifications.extrasJson` includes notification bundle data
- **Receipt images**: NO (per code comment, line 31-32)

### 4.4 Backup Settings

**Search results**: `backupEnabled`, `encrypted.*backup` — **ZERO MATCHES**

No backup-related privacy settings exist in the UI or DataStore.

---

## 5. Raw Data Retention

### 5.1 Raw OCR Text Retention

**Column**: `scanned_receipts.rawOcrText` (type: TEXT, NOT NULL, defined in `data/database/entity/ScannedReceipt.kt`)

**Sources**: Receipt OCR scan results, email receipt bodies, bank statement imports

**Retention**: **🔴 INDEFINITE — NO PURGE MECHANISM**
- `purgeOcr`, `retentionDays`, `AUTO_PURGE` — ZERO MATCHES
- No cleanup worker or retention policy exists
- Old OCR text is never deleted

**Usage**: Passed to cloud AI services, used for warranty extraction, used for duplicate detection

### 5.2 Raw Notification Retention

**Table**: `raw_notifications`

**Retention**: **🔴 INDEFINITE — NO PURGE MECHANISM**
- No purge worker, no TTL, no retention policy
- Table grows with every notification captured

### 5.3 Debug/Diagnostic Data

**`ServiceDiagnostics`** (`domain/debug/ServiceDiagnostics.kt`):
- Backed by `SharedPreferences` (file: `service_diagnostics`)
- Stores: start count, killed count, disconnect count, timestamps
- Non-sensitive (counters only)

**`AiRuntimeDiagnostics`** (`domain/debug/AiRuntimeDiagnostics.kt`):
- In-memory `ArrayDeque<AiRuntimeEvent>` (max 100 entries)
- Stores route decisions, refresh events, interaction events
- Non-sensitive (capability names and route types only — no PII)

**`DebugDataStorage`** (`ui/screens/debug/DebugDataStorage.kt`):
- Persists last bank statement import debug data to `app/filesDir/last_debug_data.json`
- Includes: raw text preview, parsed transactions (amounts, merchants, types), parsing logs, issues
- **Could contain PII** (merchant names, amounts)
- **No retention/cleanup** — file persists indefinitely until cleared via debug screen

---

## 6. Existing Privacy Settings

### 6.1 AI Settings (DataStore: `ai_settings`)

**19 preference keys** in `AiSettingsRepositoryImpl`:

| Key | Default | Purpose |
|---|---|---|
| `ai_enabled` | `true` | Master AI toggle |
| `ai_allow_cloud` | `false` | **PRIVACY: Cloud AI off by default** |
| `ai_allow_on_device` | `true` | On-device AI toggle |
| `ai_receipt_assist_enabled` | `false` | **PRIVACY: Receipt assist off by default** |
| `ai_receipt_image_cloud_enabled` | `false` | **PRIVACY: Cloud image upload off by default** |
| `ai_redact_before_cloud` | `true` | **PRIVACY: Redact before cloud on by default** |
| `ai_wifi_only_for_cloud` | `false` | Wi-Fi only constraint |
| `ai_store_conversation_history` | `false` | History storage toggle |
| `ai_preferred_mode` | `AUTO` | AUTO/CLOUD/ON_DEVICE |
| (Plus 10 capability-specific toggles) | | |

### 6.2 Settings UI

**File**: `ui/screens/aisettings/AiSettingsScreen.kt`

**Privacy section** (lines 249-258):
- `redactBeforeCloud` toggle ("Redact data before sending to cloud")
- `wifiOnlyForCloud` toggle ("Wi-Fi only for cloud AI")
- `storeConversationHistory` toggle ("Store conversation history")

**No separate "Privacy Screen" exists** — privacy controls are mixed into AI settings.

### 6.3 Other Settings Stores

| Store | File | Keys |
|---|---|---|
| `service_diagnostics` (SharedPrefs) | `domain/debug/ServiceDiagnostics.kt` | Diagnostic counters |
| `dashboard_prefs` (SharedPrefs) | `data/repository/DashboardRepository.kt` | Dashboard preferences |
| `currency_settings` (DataStore) | `data/repository/CurrencySettingsRepositoryImpl.kt` | Currency preferences |
| `widget_styles` (DataStore) | `data/repository/WidgetStyleRepositoryImpl.kt` | Widget style preferences |
| `ai_engagement` (DataStore) | `data/repository/AiEngagementRepositoryImpl.kt` | AI engagement state |
| `automated_savings_rule_state` (DataStore) | `data/repository/AutomatedSavingsRuleStateRepository.kt` | Savings rule state |
| `savings_contribution_history` (DataStore) | `data/repository/SavingsContributionHistoryRepository.kt` | Savings contributions |
| EncryptedSharedPreferences (Keystore) | `data/security/SecureKeyStorage.kt` | API keys (Gemini, Google Places, Geoapify) |

### 6.4 AppConfig Privacy Constants

**File**: `domain/config/AppConfig.kt`

Privacy-related constants:
- `Ai.MAX_REVIEW_TEXT_CHARS_FOR_CLOUD` = 500 — chars limit for cloud review text
- `Ai.MAX_RECEIPT_OCR_CHARS_FOR_AI` = 4000 — chars limit for OCR text to AI
- `Ai.MAX_NOTIFICATION_TEXT_CHARS_FOR_AI` = 300 — chars limit for notification text
- `Location.RECENT_TRANSACTION_THRESHOLD_MS` = 2 hours — GPS bias recency
- `Location.CACHE_TTL_MS` = 30 days — geocoding cache expiry
- `MAX_OCR_IMAGE_DIMENSION` = 1024 — OCR image max dimension (limits exposure)

---

## 7. Direct External Access Patterns

### 7.1 HTTP Call Sites Summary

| Component | Method | Endpoint | Data Sent |
|---|---|---|---|
| **Cloud AI (8 services)** | OkHttpClient POST | `generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent` | OCR text, merchant names, amounts, optionally images |
| **Nominatim** | OkHttpClient GET | `nominatim.openstreetmap.org/search` / `reverse` | Merchant name query, GPS bias coordinates |
| **Photon** | OkHttpClient GET | `photon.komoot.io/api/` | Merchant name query, GPS bias coordinates |
| **Geoapify** | OkHttpClient GET | `api.geoapify.com/v1/geocode/search` | Merchant name query, GPS bias, API key in query param |
| **Google Places** | OkHttpClient POST | `places.googleapis.com/v1/places:searchText` | Merchant name query, GPS bias, API key in header |
| **Overpass** | OkHttpClient POST | `overpass-api.de/api/interpreter` | GPS coordinates, search radius, OSM query |
| **ECB Rates** | HttpURLConnection GET | `www.ecb.europa.eu/stats/eurofxref/eurofxref-daily.xml` | None (public XML feed) |

### 7.2 Network Module

**File**: `di/NetworkModule.kt`

Two dedicated OkHttpClient instances:
- **`@LocationHttpClient`**: 10s connect, 20s read, 20MB cache in `cacheDir/location_http_cache`
- **`@CloudAiHttpClient`**: 15s connect, 45s read, 45s write, no cache

### 7.3 Third-Party SDKs

**No external analytics/crash-reporting SDKs found**. No Firebase, Crashlytics, Sentry, Mixpanel, Amplitude, AppCenter, or similar.

The only third-party SDKs in use:
- **Google Play Services** (Fused Location Provider)
- **OkHttp** (HTTP client)
- **Hilt** (DI)
- **Room** (Database)
- **DataStore** (Preferences)
- **WorkManager** (Background workers)
- **Timber** (Logging)
- **osmdroid** (Map display, uses `PreferenceManager.getDefaultSharedPreferences`)

### 7.4 File System Access

- **Backup exports**: Written to `context.filesDir/exports/` (app-private)
- **Database**: `context.getDatabasePath(AppDatabase.DATABASE_NAME)` (app-private)
- **Receipt images**: `context.filesDir/receipts/` (app-private, via `ReceiptAssetStore`)
- **Debug data**: `context.filesDir/last_debug_data.json` (app-private)
- **OSMDroid config**: Uses `PreferenceManager.getDefaultSharedPreferences` (deprecated, but only for map tile config)
- **Legacy backup detection**: Reads from `Environment.getExternalStorageDirectory()/Download/` (read-only check)
- **No writes to external/public storage** (writes only to app-private directories)

### 7.5 Analytics/Forecasting (Internal Only)

- `AnalyticsCurrencyNormalizer` — Internal analytics, no external calls
- `TransferDirectionAnalytics` — Direction classification, no external calls
- All analytics screens (AnalyticsScreen, AdvancedAnalyticsScreen) use local Room data only

---

## 8. Privacy Gate Gaps — Where Gates Are Missing

### 8.1 Notification Capture — Critical Gaps

| Gap | Impact | Severity |
|---|---|---|
| **No master capture toggle** | User cannot disable notification capture without blocking individual apps via Debug screen | 🔴 HIGH |
| **No "notification capture enabled" setting** | Feature is always active when service is bound | 🔴 HIGH |
| **No individual app notification filtering UI** | Users cannot manage which apps are monitored | 🟡 MEDIUM |
| **Blocked packages only accessible via Debug screen** | Hidden from normal users | 🟡 MEDIUM |
| **No capture-pause on battery/doze** | Always running in foreground | 🟢 LOW |
| **No notification content retention limit** | `raw_notifications` grows indefinitely | 🔴 HIGH |

### 8.2 Cloud AI — Moderate Gaps

| Gap | Impact | Severity |
|---|---|---|
| **No per-capability cloud toggle** | User can only enable/disable "cloud AI" as a whole, not individual AI features | 🟡 MEDIUM |
| **No data retention audit trail** | No record of what was sent to cloud | 🟡 MEDIUM |
| **No per-request consent prompt** | User is not asked before each cloud AI call | 🟢 LOW |
| **`CloudWarrantyExtractionService` gate not audited** | May lack `allowCloudAi` check | 🟡 MEDIUM |

### 8.3 Location/Geocoding — Critical Gaps

| Gap | Impact | Severity |
|---|---|---|
| **No "location services" master toggle** | Geocoding and GPS access cannot be disabled by the user | 🔴 HIGH |
| **No "geocoding enabled" setting** | LocationResolver always runs its full pipeline | 🔴 HIGH |
| **No "background location backfill" toggle** | `LocationBackfillWorker` runs every 6 hours with no opt-out | 🔴 HIGH |
| **No GPS bias consent** | Device location is silently used for geocoding bias without asking | 🟡 MEDIUM |
| **No per-merchant location resolution opt-out** | Cannot prevent individual merchants from being geocoded | 🟢 LOW |

### 8.4 Backup/Export — Critical Gaps

| Gap | Impact | Severity |
|---|---|---|
| **No backup encryption** | Database backup is plain SQLite with all user financial data | 🔴 HIGH |
| **No password protection** | Anyone with file access can read the backup | 🔴 HIGH |
| **Raw notification data included** | Full notification content (bank alerts, etc.) in backup | 🔴 HIGH |
| **Raw OCR text included** | Full receipt content in backup | 🔴 HIGH |
| **No selective export** | Cannot exclude sensitive categories or raw data | 🟡 MEDIUM |
| **No export data redaction** | Merchant names and amounts in plain text in PDF/CSV exports | 🟡 MEDIUM |

### 8.5 Raw Data Retention — Critical Gaps

| Gap | Impact | Severity |
|---|---|---|
| **No OCR text retention limit** | `rawOcrText` never purged | 🔴 HIGH |
| **No notification retention limit** | `raw_notifications` never purged | 🔴 HIGH |
| **No data retention policy** | No configurable TTL for raw data | 🔴 HIGH |
| **No debug data cleanup** | `last_debug_data.json` persists indefinitely | 🟡 MEDIUM |

### 8.6 Direct External Access — Low Gaps

| Gap | Impact | Severity |
|---|---|---|
| **Geoapify API key in query parameter** | URL logged by proxies could leak API key | 🟡 MEDIUM |
| **No app-level certificate pinning** | OkHttp clients use default trust store | 🟢 LOW |
| **No network logging redaction everywhere** | Some Timber.d calls may log merchant names (not fully audited) | 🟢 LOW |

---

## 9. Recommended Privacy Gate Design

### 9.1 Notification Capture Gates

```kotlin
// NEW: Master notification capture toggle (store in DataStore)
data class NotificationSettings(
    val captureEnabled: Boolean = false,  // OFF by default — privacy
    val monitoredPackages: List<String> = emptyList(),
    val blockedPackages: List<String> = emptyList(),
    val captureOnlyOnWifi: Boolean = false,
    val notificationRetentionDays: Int = 30  // Auto-purge after 30 days
)
```

**Required changes**:
1. Add `captureEnabled` check at `NotificationCaptureService.onNotificationPosted()` (before any processing)
2. Add retention purge worker (daily cleanup of `raw_notifications` older than N days)
3. Move package blocking from Debug screen to main Settings
4. Add `ForegroundLocationProvider` call behind a `useDeviceGpsForGeocoding` toggle

### 9.2 Cloud AI Gates (Already Partially Implemented)

```kotlin
// Existing — but ensure consistency across ALL 8 cloud services
interface CloudAiGate {
    suspend fun check(): CloudAiResult
}

enum class CloudAiResult {
    ALLOW,
    DENIED_NO_KEY,
    DENIED_CLOUD_DISABLED,
    DENIED_REDACTION_REQUIRED_BUT_IMAGE,
    DENIED_WIFI_ONLY,
    DENIED_CAPABILITY_DISABLED
}
```

**Recommended additions**:
1. A centralized `CloudAiGate` that ALL 8 services must pass through
2. Per-request audit logging of what was sent to cloud
3. Per-capability cloud toggles (not just master `allowCloudAi`)

### 9.3 Location/Geocoding Gates

```kotlin
// NEW: Location privacy settings (store in DataStore)
data class LocationSettings(
    val geocodingEnabled: Boolean = false,        // OFF by default
    val backgroundBackfillEnabled: Boolean = false, // OFF by default
    val allowGpsBias: Boolean = false,             // OFF by default
    val allowOverpassLookup: Boolean = false        // OFF by default
)
```

**Required changes**:
1. Check `geocodingEnabled` before any `LocationResolver.resolve()` call
2. Check `backgroundBackfillEnabled` before `LocationBackfillWorker` runs
3. Check `allowGpsBias` before `ForegroundLocationProvider.getLastKnownLocation()`
4. Add location privacy section to Settings UI

### 9.4 Backup/Export Gates

```kotlin
// NEW: Backup/export privacy settings
data class BackupSettings(
    val encryptBackup: Boolean = true,           // ON by default
    val backupPasswordRequired: Boolean = false,
    val includeRawNotifications: Boolean = false,  // OFF by default
    val includeRawOcrText: Boolean = false,        // OFF by default
    val anonymizeExport: Boolean = false
)
```

**Required changes**:
1. AES-256 encryption for `.db` backup files
2. Optional password protection
3. Stripe raw notification/OCR data from backup unless explicitly opted in
4. Add "anonymize merchant names" option for accounting exports

### 9.5 Raw Data Retention Gates

```kotlin
// NEW: Data retention policy
object RetentionPolicy {
    const val RAW_NOTIFICATION_TTL_DAYS = 30    // Delete after 30 days
    const val RAW_OCR_TTL_DAYS = 90             // Delete after 90 days
    const val DEBUG_DATA_TTL_DAYS = 7           // Auto-clean debug data
}

// NEW: Periodic cleanup worker
class DataRetentionWorker : CoroutineWorker {
    // Purge raw_notifications older than TTL
    // Purge scanned_receipts rawOcrText older than TTL (or set to NULL)
    // Clean up debug data files
}
```

### 9.6 Settings UI Organization

**Recommended structure** (currently all privacy is mixed into AI settings):

```
Settings
├── Privacy (NEW top-level section)
│   ├── Notification Capture [toggle]           ← Master capture switch
│   ├── Monitored Apps                         ← Manage per-app capture
│   ├── Data Retention
│   │   ├── Notification Retention Period [30 days]
│   │   ├── OCR Retention Period [90 days]
│   │   └── [Purge Now] button
│   └── Backup
│       ├── Encrypt Backups [toggle]
│       └── Include Raw Data [toggle]
├── AI Settings (existing)
│   ├── Cloud AI [toggle]
│   ├── Redact Before Cloud [toggle]
│   ├── Upload Images to Cloud [toggle]
│   └── [Per-Capability Toggles]
└── Location (NEW)
    ├── Enable Geocoding [toggle]
    ├── Enable Background Backfill [toggle]
    └── Allow GPS Bias [toggle]
```

---

## Files Referenced

| # | File Path |
|---|---|
| 1 | `service/NotificationCaptureService.kt` |
| 2 | `service/NotificationFilter.kt` |
| 3 | `data/database/entity/RawNotification.kt` |
| 4 | `data/database/entity/ScannedReceipt.kt` |
| 5 | `data/repository/NotificationRepository.kt` |
| 6 | `data/repository/NotificationProcessingPipeline.kt` |
| 7 | `data/repository/ReviewQueueRepository.kt` |
| 8 | `data/ai/provider/CloudReceiptAssistService.kt` |
| 9 | `data/ai/provider/CloudDedupeJudgeService.kt` |
| 10 | `data/ai/provider/CloudDashboardBriefingService.kt` |
| 11 | `data/ai/provider/CloudReviewExplanationService.kt` |
| 12 | `data/ai/provider/CloudCategorizationAssistService.kt` |
| 13 | `data/ai/provider/CloudReceiptItemCategorizationService.kt` |
| 14 | `data/ai/provider/CloudWarrantyExtractionService.kt` |
| 15 | `data/ai/provider/CloudQueryInterpretationService.kt` |
| 16 | `data/ai/provider/SmartReceiptAssistService.kt` |
| 17 | `data/ai/provider/OnDeviceReceiptAssistService.kt` |
| 18 | `data/ai/provider/HybridReceiptAssistService.kt` |
| 19 | `data/ai/provider/internal/CloudPiiSanitizer.kt` |
| 20 | `data/ai/provider/DashboardBriefingPromptFormatter.kt` |
| 21 | `domain/privacy/RedactionSanitizer.kt` |
| 22 | `domain/ai/model/AiModels.kt` |
| 23 | `domain/ai/service/AiSettingsRepository.kt` |
| 24 | `data/repository/AiSettingsRepositoryImpl.kt` |
| 25 | `domain/config/AppConfig.kt` |
| 26 | `data/location/NominatimGeocodingService.kt` |
| 27 | `data/location/PhotonGeocodingService.kt` |
| 28 | `data/location/GeoapifyGeocodingService.kt` |
| 29 | `data/location/GooglePlacesGeocodingService.kt` |
| 30 | `data/location/CompositeGeocodingService.kt` |
| 31 | `data/location/OverpassNearbyService.kt` |
| 32 | `data/location/AndroidForegroundLocationProvider.kt` |
| 33 | `data/location/LocationBackfillWorker.kt` |
| 34 | `data/location/MerchantKeyBackfillWorker.kt` |
| 35 | `data/location/internal/LogSanitizer.kt` |
| 36 | `domain/location/LocationResolver.kt` |
| 37 | `domain/location/LocationModels.kt` |
| 38 | `data/repository/DatabaseBackupRepositoryImpl.kt` |
| 39 | `domain/backup/DatabaseBackupRepository.kt` |
| 40 | `di/BackupRepositoryModule.kt` |
| 41 | `data/repository/AccountingExportRepository.kt` |
| 42 | `domain/export/AccountingExportPolicy.kt` |
| 43 | `domain/export/AccountantReportPdfExporter.kt` |
| 44 | `data/repository/ExportDataRepository.kt` |
| 45 | `ui/screens/export/ExportOptionsScreen.kt` |
| 46 | `data/repository/CurrencyRatesRepositoryImpl.kt` |
| 47 | `data/repository/DashboardRepository.kt` |
| 48 | `data/security/SecureKeyStorage.kt` |
| 49 | `data/security/BankTokenCipher.kt` |
| 50 | `di/NetworkModule.kt` |
| 51 | `di/AiModule.kt` |
| 52 | `domain/debug/ServiceDiagnostics.kt` |
| 53 | `domain/debug/AiRuntimeDiagnostics.kt` |
| 54 | `ui/screens/debug/DebugDataStorage.kt` |
| 55 | `ui/screens/debug/DebugScreen.kt` |
| 56 | `ui/screens/debug/DebugViewModel.kt` |
| 57 | `ui/screens/aisettings/AiSettingsScreen.kt` |
| 58 | `ui/screens/aisettings/AiSettingsViewModel.kt` |
| 59 | `startup/AppStartupCoordinator.kt` |
| 60 | `receiver/BootReceiver.kt` |
| 61 | `receiver/ServiceRestartReceiver.kt` |
| 62 | `data/database/entity/Expense.kt` |
| 63 | `data/database/entity/MerchantLocation.kt` |
| 64 | `data/database/AppDatabase.kt` |
| 65 | `DataStore files (6 DataStore instances)` |

---

## Key Findings Summary

### Already Implemented Privacy Features (Green)
- ✅ `allowCloudAi` default OFF
- ✅ `receiptImageCloudEnabled` default OFF (opt-in for image upload)
- ✅ `redactBeforeCloud` default ON (PII regex redaction before cloud)
- ✅ `CloudPiiSanitizer` for all cloud AI prompt construction
- ✅ `LogSanitizer` (`anonymizeForLog()`) for all geocoding service logs
- ✅ Rate limiting on Nominatim (1 req/sec)
- ✅ `isPackageBlocked()` check in notification capture
- ✅ Foreground location provider checks runtime permission
- ✅ LocationBackfillWorker uses Wi-Fi only
- ✅ Receipt images stored in app-private storage
- ✅ No external analytics/crash-reporting SDKs
- ✅ SecureKeyStorage uses EncryptedSharedPreferences for API keys
- ✅ Backup verification with integrity checks and schema validation
- ✅ Input size limits for cloud AI (`MAX_REVIEW_TEXT_CHARS_FOR_CLOUD`, etc.)

### Missing Privacy Features (Red)
- ❌ **No notification capture master toggle** (always-on)
- ❌ **No geocoding/location master toggle** (always-on)
- ❌ **No background location backfill toggle** (always-on)
- ❌ **No backup encryption**
- ❌ **No raw data retention/purge mechanism** (notifications + OCR text grow indefinitely)
- ❌ **No per-capability cloud AI toggles** (only master `allowCloudAi`)
- ❌ **No "exclude raw data from backup" option**
- ❌ **No per-request cloud AI consent**
- ❌ **Notification package blocking only in Debug screen**

### Moderate Concerns (Yellow)
- 🟡 Geoapify API key sent as URL query parameter (visible in proxy logs)
- 🟡 All 8 cloud AI services should be audited for consistent gate checks
- 🟡 DebugDataStorage persists merchant names/amounts indefinitely with no user-facing cleanup
- 🟡 Optional: Per-process salt for LogSanitizer regenerates on each app start (acceptable but worth noting)
- 🟡 Timber logging throughout the app may log sensitive data (not fully audited for PII)
- 🟡 No privacy policy URL or consent flow implemented
