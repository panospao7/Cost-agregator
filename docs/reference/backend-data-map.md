# Data Layer Architecture Map
**Expense Tracker | Comprehensive Data Layer Reference**  
*Refreshed: May 2026 | Current schema and adapter map*

---

## Executive Summary

The Data Layer follows a **Repository Pattern** layered over Room ORM and Android-native storage. The current map emphasizes:

- Room entities/DAOs for expenses, budgets, AI artifacts, receipts, groups, currency, and bank data
- Repository and adapter layers that bridge domain contracts to persistence and services
- Security and input gateways for bank tokens, speech input, and encrypted key storage
- Notification, location, and AI processing pipelines that sit beside the classic Room repositories

---

## Directory Structure

```
data/
├── privacy/               # **NEW — Privacy data layer (Phase 6)**
│   ├── PrivacySettingsRepositoryImpl.kt  # DataStore-backed settings
│   ├── BackupEncryptionService.kt        # AES-256-GCM encrypt/decrypt
│   ├── ExportAnonymizer.kt               # Strips raw text from exports
│   └── DataRetentionWorker.kt            # WorkManager purging worker
│
├── backup/                # **NEW — Backup/restore subsystem (Phase 9)**
│   ├── CostbackupBundle.kt               # .costbackup ZIP format (AES-256-GCM, manifest, checksums, receipt images)
│   ├── RestoreMaintenanceMode.kt         # Worker pause + coordinator write-blocking during restore
│   ├── RestoreJournal.kt                 # Crash-safe 8-state restore journal with crash recovery
│   └── BackupVerifier.kt                 # 56-entity 3-tier verification via PRAGMA integrity_check + FK check
│
├── database/               # Room ORM + migrations, entities, query models
│   ├── AppDatabase.kt      # RoomDatabase (v113, 56 entity references)
│   ├── converter/          # Type converters
│   │   └── Converters.kt   # @TypeConverter for complex types
│   ├── dao/                # Current DAO set
│   │   ├── ExpenseDao.kt
│   │   ├── CategoryDao.kt
│   │   ├── BudgetDao.kt
│   │   ├── MerchantNormalizationDao.kt
│   │   ├── MerchantLocationDao.kt
│   │   ├── ReceiptItemCategorizationDao.kt
│   │   ├── AiArtifactDao.kt
│   │   ├── AiChatSessionDao.kt
│   │   ├── AiChatMessageDao.kt
│   │   ├── RecurringLifecycleEventDao.kt  # Phase 5b
│   │   ├── PrivacyAuditDao.kt             # Phase 6
│   │   ├── [40+ more DAOs...]
│   │   └── [See DAO Registry below]
│   ├── entity/             # Current Room entities
│   │   ├── Expense.kt      # Core expense with transfer/shared/business fields
│   │   ├── Category.kt     # Categories with icons & colors
│   │   ├── Budget.kt       # Period-based budgets with warning thresholds
│   │   ├── ScannedReceipt.kt  # OCR results + matching status
│   │   ├── AiArtifactEntity.kt # AI briefings, explanations (phase 1)
│   │   ├── MerchantCanonical.kt # Normalized merchant master
│   │   ├── MerchantAlias.kt     # Raw merchant name → canonical FK
│   │   ├── RecurringLifecycleEvent.kt  # Phase 5b — audit log for recurring occurrences
│   │   ├── PrivacyAuditEvent.kt        # Phase 6 — privacy gate audit log
│   │   ├── [40+ more entities...]
│   │   └── [See Entity Registry below]
│   └── model/              # Room query result POJOs
│       ├── ExpenseWithCategory.kt      # @Transaction join result
│       ├── ExpenseWithCategoryName.kt  # Name-based variant
│       ├── DashboardWidgetConfig.kt    # Widget state POJO
│       ├── PendingReviewWithReceipt.kt # Joins pending_reviews → scanned_receipts
│       └── ExpenseWithCategory_Extensions.kt
│
├── repository/             # Repository + adapter implementations
│   ├── DashboardContractsAdapter.kt
│   ├── SharedExpenseDataPortAdapter.kt
│   ├── NotificationProcessingPipeline.kt
│   ├── NaturalLanguageExpenseQueryRepositoryImpl.kt
│   ├── BankApiIntegrationRepository.kt
│   ├── ExpenseRepository.kt
│   ├── BudgetRepository.kt
│   ├── BusinessExpenseRepository.kt
│   ├── ReceiptRepository.kt
│   ├── SavingsGoalRepository.kt
│   ├── AnomalyAlertRepositoryImpl.kt
│   ├── RecommendationRepository.kt
│   ├── DashboardRepository.kt
│   ├── MultiCurrencyRepository.kt
│   ├── GroupsRepositoryImpl.kt
│   ├── DatabaseBackupRepositoryImpl.kt
│   ├── AiArtifactRepositoryImpl.kt
│   ├── AiChatRepositoryImpl.kt
│   └── [other current repositories/adapters]
│
├── ai/                    # AI services (local + cloud)
│   ├── provider/           # Current AI providers
│   │   ├── CloudCategorizationAssistService.kt
│   │   ├── OnDeviceCategorizationAssistService.kt
│   │   ├── HybridCategorizationAssistService.kt
│   │   ├── NoOpCategorizationAssistService.kt
│   │   ├── CloudReceiptAssistService.kt
│   │   ├── OnDeviceReceiptAssistService.kt
│   │   ├── CloudReceiptItemCategorizationService.kt
│   │   ├── OnDeviceReceiptItemCategorizationService.kt
│   │   ├── CloudDashboardBriefingService.kt
│   │   ├── OnDeviceDashboardBriefingService.kt
│   │   ├── CloudDedupeJudgeService.kt
│   │   ├── OnDeviceSemanticDuplicateDetector.kt
│   │   ├── DefaultAiEnvironmentMonitor.kt
│   │   ├── OnDeviceReviewPriorityScorer.kt
│   │   ├── OnDeviceNotificationParser.kt
│   │   ├── [other providers...]
│   │   └── SmartReceiptAssistService.kt
│   └── worker/           # Async AI job scheduling
│       ├── AiWorkSchedulerImpl.kt
│       └── DailyBriefingWorker.kt
│
├── location/             # Geocoding & geospatial services
│   ├── CompositeGeocodingService.kt    # Multi-provider orchestrator
│   ├── NominatimGeocodingService.kt    # OSM reverse geocoding
│   ├── PhotonGeocodingService.kt       # Free photo-based search
│   ├── GeoapifyGeocodingService.kt     # Commercial API
│   ├── GooglePlacesGeocodingService.kt # Google Places API (opt-in quota)
│   ├── OverpassNearbyService.kt        # OSM POI finder (bars, shops, etc.)
│   ├── AndroidForegroundLocationProvider.kt  # Device GPS provider
│   ├── LocationBackfillWorker.kt       # Async geocode backfill
│   └── MerchantKeyBackfillWorker.kt    # Async merchant key generation (v32)
│
├── email/               # Email receipt ingestion
│   ├── EmailReceiptIngestionService.kt  # IMAP/POP3 client
│   └── provider/        # Email parser implementations
│       ├── EmailReceiptParser.kt
│       ├── AmazonReceiptParser.kt
│       ├── AppleReceiptParser.kt
│       └── UberReceiptParser.kt
│
├── provider/            # Data providers
│   └── MerchantCategoryProvider.kt  # Bulk merchant → category lookup
│
├── security/            # Secure storage
│   └── SecureKeyStorage.kt  # EncryptedSharedPreferences for API keys
│
└── service/             # Platform services
    └── AndroidNotificationService.kt  # Notification publishing
```

---

## Database Schema Summary

| Aspect | Details |
|--------|---------|
| **Version** | 113 (post-compliance hardening; latest migration: MIGRATION_112_113) |
| **Total Entities** | 56 (RecurringLifecycleEvent, PrivacyAuditEvent added; 4 new columns on planned_expenses + raw_notifications + scanned_receipts) |
| **Total DAOs** | 54 (RecurringLifecycleEventDao, PrivacyAuditDao added) |
| **Total Migrations** | 104 (MIGRATION_6_7 → MIGRATION_112_113) |
| **Type Converters** | Custom: Enums, Lists, Dates |
| **Export Schema** | ✓ Enabled (for migrations verification) |

### Phase 2 Entity Changes (Time Semantics)

- **38 entity timestamp defaults** migrated from `= System.currentTimeMillis()` to `= 0L` sentinel — entities no longer fetch wall-clock time at construction. All timestamps are now set explicitly by the caller.
- **BudgetForecastDao** — `targetPeriodEnd >= :date` fixed to `targetPeriodEnd > :date` to match the half-open `[start, end)` contract.

### Phase 3 Entity Changes (Transaction Lifecycle)

- **Expense.source** — new nullable `TEXT` column tracking the origin of each expense (ExpenseSource enum name: MANUAL_ENTRY, NOTIFICATION_AUTO_ACCEPT, CSV_IMPORT, BANK_API_SYNC, etc.). Nullable for backward compatibility with legacy rows; backfilled by migration.
- **TransactionEvent** — new entity for the `transaction_events` table. Immutable append-only log recording every CREATED/UPDATED/DELETED/etc. transition. Fields: `id`, `expenseId`, `eventType`, `source`, `actor`, `occurredAt`, `dedupeKey`, `duplicateExpenseId`, `beforeSnapshot`, `afterSnapshot`, `metadata`, `reason`. Indexed on `expenseId`, `source`, `occurredAt`, `eventType`.
- **Single insertion point:** All expense creation now routes through `TransactionLifecycleCoordinator.createExpense()`. Direct `insertAtomic` calls are restricted to grandfathered files only (see `DAO_ACCESS_GUARDRAILS.md`).

### Phase 5 Entity Changes (Recurring/Planned/Reminder Lifecycle)

- **RecurringOccurrence** — new entity for the `recurring_occurrences` table. Stores expanded occurrence candidates from recurring rules. Fields: `id`, `sourceType`, `sourceId`, `occurrenceKey` (unique), `dueDate`, `status` (PLANNED/PAID/SKIPPED/MISSED/CANCELLED/IGNORED), `linkedExpenseId`, `expectedAmount`, `expectedCurrency`, `paidAt`, `paidAmount`, `paidCurrency`, `frequency`, `merchant`, `categoryId`, `createdAt`, `updatedAt`. Indices on `(sourceType, sourceId)`, `dueDate`, `status`, `occurrenceKey` (unique), `linkedExpenseId`.
- **RecurringReminderDelivery** — new entity for the `recurring_reminder_deliveries` table. Schedules and tracks reminder dispatch. Fields: `id`, `occurrenceId`, `reminderWindow` (DUE_DAY, N_DAYS_BEFORE, OVERDUE), `scheduledAt`, `status` (SCHEDULED/SENT/DISMISSED/SNOOZED/FAILED), `lastSentAt`, `dismissedAt`, `snoozedUntil`, `notificationId`, `createdAt`. Indices on `(occurrenceId, reminderWindow)`, `status`, `scheduledAt`.
- **PlannedExpense** — 2 new columns:
  - `sourceOccurrenceKey` (TEXT, nullable) — occurrenceKey of the recurring occurrence that generated this planned expense
  - `sourceRecurringRuleId` (INTEGER, nullable) — ID of the recurring rule that generated this planned expense
- **Single insertion point:** All recurring occurrence generation routes through `RecurringLifecycleCoordinator.generateOccurrences()`. Reminder delivery scheduling is handled by `RecurringOccurrenceMaterializer.materialize()`.

### Phase 5b Entity Changes (Occurrence Audit + Hardening)

- **PlannedExpense** — 4 new columns (migration 100→101):
  - `status` (TEXT, default 'PLANNED') — lifecycle status of each planned expense
  - `linkedActualExpenseId` (INTEGER, nullable) — points to the actual expense if one was created
  - `merchantKey` (TEXT, nullable) — canonical merchant key for matching
  - `updatedAt` (INTEGER, default 0) — last-update timestamp
- **RecurringReminderDelivery** — unique index hardened on `(occurrenceId, reminderWindow)`; duplicate rows deleted keeping the earliest.
- **RecurringLifecycleEvent** — new entity for the `recurring_lifecycle_events` table. Immutable append-only log recording every significant lifecycle transition for a recurring occurrence. Event types: OCCURRENCE_GENERATED, OCCURRENCE_PAID, OCCURRENCE_SKIPPED, OCCURRENCE_CANCELLED, REMINDER_SCHEDULED, REMINDER_SENT, REMINDER_DISMISSED, PLANNED_GENERATED, DRIFT_DETECTED. Fields: `id`, `occurrenceId` (nullable), `eventType`, `occurredAt`, `oldStatus`, `newStatus`, `metadata` (JSON). Indices on `occurrenceId`, `occurredAt`, `eventType`.

### Phase 6 Entity Changes (Privacy & Audit)

- **PrivacyAuditEvent** — new entity for the `privacy_audit_events` table. Append-only log of every privacy gate check. Fields: `id`, `capability`, `decision` (ALLOWED/DENIED), `reason`, `context` (JSON), `timestampMs`, `caller`. Indices on `timestampMs`, `capability`, `caller`.
- **RawNotification** — 1 new column (migration 103→104):
  - `rawContentPurgedAt` (INTEGER, nullable) — timestamp when raw notification content was purged for data retention compliance.
- **ScannedReceipt** — 1 new column (migration 103→104):
  - `rawOcrTextPurgedAt` (INTEGER, nullable) — timestamp when raw OCR text was purged for data retention compliance.

### Phase 4 Entity Changes (Receipt Lifecycle)

- **ScannedReceipt** — 10 new columns for lifecycle and deduplication:
  - `sourceType` (TEXT, default `'UNKNOWN'`) — ReceiptSourceType enum name
  - `documentType` (TEXT, default `'UNKNOWN'`) — ReceiptDocumentType enum name
  - `processingStatus` (TEXT, default `'CAPTURED'`) — ReceiptProcessingStatus enum name
  - `sourceFingerprint` (TEXT, nullable) — external source ID for dedup (e.g. email messageId)
  - `imageHash` (TEXT, nullable) — SHA-256 hex digest of receipt image file
  - `textFingerprint` (TEXT, nullable) — SHA-256 of normalized OCR text
  - `semanticFingerprint` (TEXT, nullable) — SHA-256 of merchant+amount+date+currency
  - `ocrConfidence` (REAL, nullable) — OCR engine confidence score
  - `parseFailureReason` (TEXT, nullable) — human-readable failure reason if parsing failed
  - `updatedAt` (INTEGER, default 0L) — timestamp of last update; must be set explicitly via `timeProvider.now()`
- **ReceiptEvent** — new entity for the `receipt_events` table. Immutable append-only log recording every receipt lifecycle transition (CAPTURED, OCR_FAILED, PARSED, EXPENSE_CREATED, RECEIPT_DELETED, etc.). Fields: `id`, `receiptId`, `sourceType`, `documentType`, `eventType`, `occurredAt`, `oldStatus`, `newStatus`, `actor`, `message`, `metadata`, `errorDetails`. Indexed on `receiptId`, `sourceType`, `documentType`, `occurredAt`, `eventType`.
- **ReceiptExpenseLink** — new entity for the `receipt_expense_links` table. Many-to-many join between receipts and expenses. Fields: `id`, `receiptId`, `expenseId`, `linkType`, `confidence`, `source`, `createdAt`, `createdBy`, `isPrimary`, `metadata`. Unique constraint on `(receiptId, expenseId)`. Indexed on `receiptId`, `expenseId`, `linkType`, `createdAt`.
- **Single insertion point:** All receipt processing now routes through `ReceiptLifecycleCoordinator.processReceiptInput()`. All receipt-expense link mutations go through `ReceiptLinkService.linkReceiptToExpense()` / `unlinkReceiptFromExpense()`.

### Database Version History

| Version | Feature / Purpose |
|---------|-------------------|
| 92 | Multi-currency historical snapshot fields (baseAmount, baseCurrency, exchangeRateUsed) |
| 93-94 | (intermediate schema updates) |
| **95** | **Transaction Lifecycle Foundation: `source` column on expenses + `transaction_events` table** |
| **96** | **Receipt Lifecycle Foundation: `receipt_events` + `receipt_expense_links` tables + 10 new columns on `scanned_receipts` (sourceType, documentType, processingStatus, fingerprints, hashes, ocrConfidence, parseFailureReason, updatedAt)** |
| **100** | **Recurring/Planned/Reminder Lifecycle Foundation: `recurring_occurrences` + `recurring_reminder_deliveries` tables + `sourceOccurrenceKey` + `sourceRecurringRuleId` on `planned_expenses`** |
| **101** | **PlannedExpense hardening: 4 new columns (status, linkedActualExpenseId, merchantKey, updatedAt); reminder delivery unique index** |
| **102** | **Recurring lifecycle audit: `recurring_lifecycle_events` table** |
| **103** | **Privacy gate audit: `privacy_audit_events` table** |
| **104** | **Data retention: `rawContentPurgedAt` on raw_notifications + `rawOcrTextPurgedAt` on scanned_receipts** |
| **105** | **DB Invariants (Phase 7): Budget CHECK constraints, schema hardening, fresh-install callback alignment** |
| **106** | **DB Invariants (Phase 7 cont.): Final invariant layer. Phases 9+10 add no schema changes — stays at v106.** |
| **107** | SimpleDateFormat→DateTimeFormatter migrations (no schema change) |
| **108** | REPLACE→IGNORE DAO conversions (batch R+S, no schema change) |
| **109** | Quick wins: isFinite guards, stale matchConfidence clear (Y1+Y8, no schema change) |
| **110** | CURR-2 + TRN-2: exchange rate history, synthetic placeholder fixes |
| **111** | BUD-1: budgets categoryId FK RESTRICT |
| **112** | Category name uniqueness: COLLATE NOCASE index + lowercase normalization |
| **113** | FRESH_INSTALL_CALLBACK alignment for NOCASE index |

---

## Entities Registry (46 Total)

### Core Financial (8)
| Entity | Table | Purpose | Foreign Keys | Indices |
|--------|-------|---------|--------------|---------|
| **Expense** | `expenses` | Core transaction record with location, business, share fields, multi-currency snapshot, and source tracking | `rawNotificationId` → raw_notifications, `categoryId` → categories | 12: rawNotificationId, transactionType+date, categoryId+date, merchant+date, dedupeKey (unique), latitude+longitude, merchantKey, isBusinessExpense |
| | | *New columns (Phase 7):* `baseAmount` (Double), `baseCurrency` (String), `exchangeRateUsed` (Double) — stable historical conversion snapshot | | |
| | | *New columns (Phase 3):* `source` (String, nullable) — origin tracking (ExpenseSource enum name). Nullable for legacy rows; backfilled by migration 94→95. | | |
| **TransactionEvent** | `transaction_events` | Immutable lifecycle audit log. Records every CREATED/UPDATED/DELETED transition with actor, timestamps, before/after snapshots, metadata. Append-only. | None | expenseId, source, occurredAt, eventType |
| **Category** | `categories` | User-defined or system expense categories | None | isDefault |
| **Budget** | `budgets` | Period-based spend limits with warnings | `categoryId` → categories | categoryId, isActive |
| | | *New columns:* `currency` (String), `currencyAssumption` (String) — explicit budget currency with LEGACY_DEFAULT assumption | | |
| **PlannedExpense** | `planned_expenses` | Future planned transactions | `categoryId` → categories | date, categoryId |
| | | *New columns:* `currency` (String), `currencyAssumption` (String) | | |
| **RecurringExpense** | `manual_recurring_expenses` | Subscriptions & repeating expenses (v12) | None | None (added v40: isActive, isSubscription) |
| **SavingsGoal** | `savings_goals` | Savings targets with progress | None | None |
| | | *New columns:* `currency` (String), `currencyAssumption` (String) | | |
| **Investment** | `investments` | Portfolio holdings with price tracking (v45) | None | type, symbol, isActive |
| **InvestmentValue** | `investment_values` | Historical price snapshots (v45) | `investmentId` → investments | investmentId+timestamp, timestamp |

### Receipts & Items (9)
| Entity | Table | Purpose | Foreign Keys | Indices |
|--------|-------|---------|--------------|---------|
| **ScannedReceipt** | `scanned_receipts` | OCR-extracted receipt data with lifecycle metadata (v9) | `expenseId` → expenses | expenseId, createdAt, matchStatus, processingStatus |
| | | *Phase 4 columns:* `sourceType` (TEXT, default UNKNOWN), `documentType` (TEXT, default UNKNOWN), `processingStatus` (TEXT, default CAPTURED), `sourceFingerprint` (TEXT, nullable), `imageHash` (TEXT, nullable), `textFingerprint` (TEXT, nullable), `semanticFingerprint` (TEXT, nullable), `ocrConfidence` (REAL, nullable), `parseFailureReason` (TEXT, nullable), `updatedAt` (INTEGER, default 0L) | | |
| **ReceiptEvent** | `receipt_events` | Immutable receipt lifecycle audit log. Records every CAPTURED/OCR_FAILED/PARSED/EXPENSE_CREATED/DELETED transition with actor, status transitions, timestamps. Append-only. | None | receiptId, sourceType, documentType, occurredAt, eventType |
| **ReceiptExpenseLink** | `receipt_expense_links` | Many-to-many join between receipts and expenses. Supports single and multi-link relationships with confidence, source, and metadata. | None | receiptId, expenseId, (receiptId, expenseId) unique, linkType, createdAt |
| **ReceiptItemCategorization** | `receipt_item_categorizations` | AI-suggested categories per receipt line item (v37) | `receiptId` → scanned_receipts, `expenseId` → expenses | receiptId, expenseId, suggestedCategoryId, userCorrectedCategoryId |
| **Warranty** | `warranties` | Product warranties extracted from receipts (v38) | `receiptId` → scanned_receipts, `expenseId` → expenses | receiptId, expenseId, warrantyEndDate, status |
| **ReturnWindow** | `return_windows` | Product return periods from receipts (v38) | `receiptId` → scanned_receipts, `expenseId` → expenses | receiptId, expenseId, returnDeadline, status |
| **EmailReceiptSource** | `email_receipts` | Email sources for receipt ingestion | None | None |
| **RawNotification** | `raw_notifications` | Intercepted payment notifications before processing | None | isRelevant, packageName+timestamp+title+text (unique, v22) |
| **PendingReview** | `pending_reviews` | AI-suggested expenses awaiting user review | `rawNotificationId` → raw_notifications, `scannedReceiptId` → scanned_receipts | rawNotificationId, scannedReceiptId, status, status+createdAt |

### Merchants (6)
| Entity | Table | Purpose | Foreign Keys | Indices |
|--------|-------|---------|--------------|---------|
| **MerchantCanonical** | `merchant_canonicals` | Normalized merchant master (v17) | `categoryId` → categories | normalizedName (unique), searchKey, categoryId |
| **MerchantAlias** | `merchant_aliases` | Raw name → canonical mapping (v17) | `canonicalId` → merchant_canonicals | rawName (unique), normalizedKey, canonicalId |
| **MerchantCategory** | `merchant_categories` | Merchant → category associations | None | normalizedCanonicalName (v26) |
| **MerchantLocation** | `merchant_locations` | Geocoded merchant coordinates cache (v28) | None | normalizedMerchantName+areaKey (unique, v30), lastResolvedAt |
| **MerchantLocationCorrection** | `merchant_location_corrections` | User-corrected merchant locations (v28) | None | normalizedMerchantName+areaKey (unique), createdAt |
| **UserCorrection** | `user_corrections` | User edits to auto-suggested fields (v16) | `originalCategoryId` → categories, `correctedCategoryId` → categories | originalCategoryId, correctedCategoryId, packageName, wasApproved, wasRejected |

### AI & Chat (6)
| Entity | Table | Purpose | Foreign Keys | Indices |
|--------|-------|---------|--------------|---------|
| **AiArtifactEntity** | `ai_artifacts` | AI-generated briefings, explanations, summaries (v34) | None | targetKey+capability+promptVersion+sourceHash (unique), targetKey+capability+updatedAt, status+updatedAt, expiresAt |
| **AiChatSessionEntity** | `ai_chat_sessions` | Chat conversation sessions (v35) | None | updatedAt, createdAt |
| **AiChatMessageEntity** | `ai_chat_messages` | Individual chat messages (v35) | `sessionId` → ai_chat_sessions (CASCADE) | sessionId, sessionId+createdAt, createdAt |
| **RecommendationEntity** | `recommendations` | AI-generated action recommendations (v36) | `sourceArtifactId` (text) | userId+status+expiresAt, sourceArtifactId, createdAt, expiresAt |
| **PromptStateEntity** | `prompt_states` | LLM prompt versioning & A/B testing | None | None |
| **SpendingPersonalityProfileEntity** | `spending_personality_profiles` | User spending behavior analysis results | None | None |

### Spending Challenges (1)
| Entity | Table | Purpose | Foreign Keys | Indices |
|--------|-------|---------|--------------|---------|
| **SpendingChallengeEntity** | `spending_challenges` | Spending challenge creation & tracking | None | None |
| | | *New column:* `currency` (String) — for `targetAmount` and `baselineAmount` | | |

### Budgeting & Forecasting (6)
| Entity | Table | Purpose | Foreign Keys | Indices |
|--------|-------|---------|--------------|---------|
| **BudgetForecast** | `budget_forecasts` | AI-predicted budget outcomes (v44) | `budgetId` → budgets | budgetId, forecastDate, isActive |
| **BudgetAdjustmentRecommendation** | `budget_adjustments` | Suggested budget changes | None | None |
| | | *New column:* `currency` (String) | | |
| **StressForecastSnapshot** | `stress_forecast_snapshots` | Financial stress scoring snapshots | None | None |
| | | *New column:* `currency` (String) — all monetary fields in the snapshot use this currency | | |
| **HealthScoreHistory** | `health_score_history` | Financial health metric evolution | None | None |
| **SavingsSweepPlan** | `savings_sweep_plans` | Automatic savings routing rules | None | None |
| **SubscriptionCandidate** | `subscription_candidates` | Detected recurring charges for user confirmation | None | None |

### Shared Expenses (4)
| Entity | Table | Purpose | Foreign Keys | Indices |
|--------|-------|---------|--------------|---------|
| **ExpenseGroup** | `expense_groups` | Shared expense pool header (v43) | None | isActive, createdAt |
| **GroupMember** | `group_members` | Pool participant definition (v43) | `groupId` → expense_groups | groupId, groupId+name (unique) |
| **GroupExpense** | `group_expenses` | Expense linked to a pool with split (v43) | `groupId` → expense_groups, `expenseId` → expenses, `paidById` → group_members | groupId, expenseId, paidById, groupId+date |
| **SplitTemplate** | `split_templates` | Saved split patterns for reuse (v47) | None | isDefault |

### Subscriptions (3)
| Entity | Table | Purpose | Foreign Keys | Indices |
|--------|-------|---------|--------------|---------|
| **SubscriptionPriceHistory** | `subscription_price_history` | Price change tracking for subscriptions (v40) | `subscriptionId` → manual_recurring_expenses | subscriptionId, subscriptionId+recordedAt |
| **SubscriptionUsage** | `subscription_usage` | Usage metrics for subscription optimization (v40) | `subscriptionId` → manual_recurring_expenses | subscriptionId, subscriptionId+usedAt |
| **SplitItemAssignment** | `split_item_assignments` | Receipt item → participant allocation (v47) | `expenseId` → expenses | expenseId, receiptItemId |

### Bank & Multi-Currency (3)
| Entity | Table | Purpose | Foreign Keys | Indices |
|--------|-------|---------|--------------|---------|
| **BankConnection** | `bank_connections` | Open Banking API credentials (v46) | `defaultCategoryId` → categories | bankId, isActive, lastSync |
| **ExchangeRate** | `exchange_rates` | Currency pair conversion rates (v42) | None | fromCurrency+toCurrency+validDate (unique), lastUpdated |
| | | *New column:* `validDate` (Long) — enables historical rate lookups. Unique constraint expanded to include validDate. | | |
| **SourceStats** | `source_stats` | Notification source statistics (v14) | None | None |

### Recurring Lifecycle (3)

| Entity | Table | Purpose | Foreign Keys | Indices |
|--------|-------|---------|--------------|---------|
| **RecurringOccurrence** | `recurring_occurrences` | Expanded occurrence candidates from recurring rules. Status: PLANNED/PAID/SKIPPED/MISSED/CANCELLED/IGNORED. Dedup via occurrenceKey unique constraint. | `linkedExpenseId` → expenses (no FK constraint) | sourceType+sourceId, dueDate, status, occurrenceKey (unique), linkedExpenseId |
| **RecurringReminderDelivery** | `recurring_reminder_deliveries` | Scheduled reminder dispatch for recurring occurrences. Status: SCHEDULED/SENT/DISMISSED/SNOOZED/FAILED. Windows: DUE_DAY, N_DAYS_BEFORE, OVERDUE. | `occurrenceId` → recurring_occurrences (no FK constraint) | occurrenceId+reminderWindow (unique), status, scheduledAt |
| **RecurringLifecycleEvent** | `recurring_lifecycle_events` | Immutable event log for recurring occurrence lifecycle transitions. Event types: OCCURRENCE_GENERATED, OCCURRENCE_PAID, OCCURRENCE_SKIPPED, OCCURRENCE_CANCELLED, REMINDER_SCHEDULED, REMINDER_SENT, REMINDER_DISMISSED, PLANNED_GENERATED, DRIFT_DETECTED. | None | occurrenceId, occurredAt, eventType |

### Privacy & Audit (1)

| Entity | Table | Purpose | Foreign Keys | Indices |
|--------|-------|---------|--------------|---------|
| **PrivacyAuditEvent** | `privacy_audit_events` | Append-only log of every privacy gate decision. Fields: id, capability, decision (ALLOWED/DENIED), reason, context (JSON), timestampMs, caller. | None | timestampMs, capability, caller |

### Misc. Business (3)
| Entity | Table | Purpose | Foreign Keys | Indices |
|--------|-------|---------|--------------|---------|
| **MileageTracking** | `mileage_tracking` | Business trip mileage deductions (v41) | `linkedExpenseId` → expenses | linkedExpenseId, date, isBusinessTrip |
| **AnomalyAlert** | `anomaly_alerts` | Fraud/unusual transaction flags | None | None |
| | | *New columns:* `currency` (String), `baseAmount` (Double?), `baseCurrency` (String?) | | |
| **BlockedPackage** | `blocked_packages` | Notification sources to ignore | None | None |

---

## DAOs Registry (45 Total)

### Core CRUD DAOs (9)

| DAO | Table | Key Methods | Custom Queries |
|-----|-------|-------------|-----------------|
| **ExpenseDao** | expenses | getById, insert, insertAll, delete, getPage, getAllFlow, getAllWithCategoryFlow | getExpensesDynamic (RawQuery), getExpensesWithCategoryFiltered, getExpensesWithCategoryInPeriod, getExpensesSince, getRecentExpensesForMerchant, getTotalSpentFlow, updateCategory, updateMerchant, updateTransactionType, checkDuplicate |
| **TransactionEventDao** | transaction_events | insert, getEventsForExpense | None (append-only log) |
| **CategoryDao** | categories | getAll, getById, insert, insertAll, update, delete, getDefaultCategories | None |
| **BudgetDao** | budgets | getById, getAll, insert, update, delete, insert(List), updateAmount, updateNotifyAtWarning, updateNotifyAtCritical, resetNotifyDates | getActiveBudgetForCategory, getTotalBudgetedAmount, getBudgetUtilization |
| **RecurringExpenseDao** | manual_recurring_expenses | getAll, getById, insert, update, delete, getActive, getUpcoming, getTotalRecurringExpense | getRecurringExpensesForMerchant |
| **PlannedExpenseDao** | planned_expenses | getAll, getById, insert, update, delete, getPlannedExpensesBetween | None |
| **SavingsGoalDao** | savings_goals | getAll, getById, insert, update, delete, updateCurrentAmount, updateProgress | None |
| **UserCorrectionDao** | user_corrections | insert, getAll, getForPackage, getApprovedCorrections, getRejectedCorrections | None (has indices on packageName, wasApproved, wasRejected) |
| **RawNotificationDao** | raw_notifications | insert, getAll, getById, delete, getUnprocessed, markAsRelevant, deleteOldNotifications | getByPackageNameAndTime |

### Receipt & Item Categorization (6)

| DAO | Table | Key Methods |
|-----|-------|-------------|
| **ScannedReceiptDao** | scanned_receipts | getAll, getById, insert, update, delete, getByExpenseId, getUnmatchedReceipts, updateMatchStatus, updateMatchConfidence, getRecentReceipts, getAllWithImagePath | getReceiptsByStatus, getByImageHash, getByTextFingerprint, getBySemanticFingerprint, getBySourceFingerprint |
| **ReceiptEventDao** | receipt_events | insert, getEventsForReceipt | None (append-only log) |
| **ReceiptExpenseLinkDao** | receipt_expense_links | insert (REPLACE), getLinksForReceipt, getLinksForExpense, unlink, deleteAllLinksForReceipt | None |
| **ReceiptItemCategorizationDao** | receipt_item_categorizations | insert, getById, getByReceiptId, getByExpenseId, updateUserCorrectedCategory | getUncorrectedItems, getConfidenceStats |
| **WarrantyDao** | warranties | insert, getAll, getById, getByReceiptId, getByExpenseId, getActiveWarranties, getExpiringWarranties | getWarrantiesByStatus |
| **ReturnWindowDao** | return_windows | insert, getAll, getById, getByReceiptId, getByExpenseId, getReturnableItems, getExpiredReturns | getReturnsByStatus |

### Merchant Management (5)

| DAO | Table | Key Methods |
|-----|-------|-------------|
| **MerchantNormalizationDao** | merchant_canonicals, merchant_aliases | getCanonicalByNormalizedName, getCanonicalBySearchKey, getAliasesByCanonicalId, createCanonical, createAlias, updateCanonicalStats | getMostUsedMerchants, getFuzzyMatches |
| **MerchantLocationDao** | merchant_locations | getByMerchantName, getCachedLocation, upsertLocation, deleteOldCaches, getAllCaches | getLocationsByAreaKey, getLocationsNeedingBackfill |
| **MerchantCategoryDao** | merchant_categories | insert, getAll, getById, getByMerchantName, getByNormalizedName, updateCategory, deleteByMerchantName | None |
| **MerchantCategoryRepository** | (cross-table logic) | getMerchantCategorySuggestions, autoAssignCategories, recordMerchantCategoryAssociation | None |
| **LocationBackfillWorker** | (worker service) | backfillMissingLocations, prioritizeUnresolvedExpenses | None |

### AI & Chat (4)

| DAO | Table | Key Methods |
|-----|-------|-------------|
| **AiArtifactDao** | ai_artifacts | insert, getById, getByTargetKey, getLatestByCapability, getByStatus, upsert, deleteOldArtifacts | getArtifactsForCleanup, getExpiringArtifacts |
| **AiChatSessionDao** | ai_chat_sessions | insert, getAll, getById, delete, updateTitle, getRecentSessions, deleteOldSessions | None |
| **AiChatMessageDao** | ai_chat_messages | insert, getById, getBySessionId, deleteBySessionId, getMessagesSince | getSessionMessages |
| **RecommendationDao** | recommendations | insert, getAll, getById, getActiveRecommendations, markDismissed, deleteExpired | getUserRecommendations, getByStatus |

### Budgeting & Analytics (5)

| DAO | Table | Key Methods |
|-----|-------|-------------|
| **BudgetForecastDao** | budget_forecasts | insert, getById, getByBudgetId, getActiveForecasts, updateActualSpending, updateAccuracy | getForecastsNeedingRecalc |
| **HealthScoreHistoryDao** | health_score_history | insert, getAll, getById, getRecentScores | getScoresTrend |
| **SourceStatsDao** | source_stats | insert, getById, getAll, update, getTopSources, recordNotification, recordAccepted, recordRejected | None |
| **AnalyticsRepository** | (aggregation queries) | getDailyTotals, getWeeklyTotals, getMonthlyTotals, getCategoryTotals, getMerchantStats, getLocationClusters | Complex SQL aggregations with date ranges |
| **DashboardRepository** | (widget aggregations) | getExpenseStats, getCategoryBreakdown, getBudgetStatus, getTopMerchants, getTrendingCategories | Custom queries for dashboard |

### Shared Expenses (4)

| DAO | Table | Key Methods |
|-----|-------|-------------|
| **ExpenseGroupDao** | expense_groups | getAll, getById, insert, update, delete, getActiveGroups | None |
| **GroupMemberDao** | group_members | getByGroupId, insert, delete, updateMember, getGroupMembersCount | None |
| **GroupExpenseDao** | group_expenses | insert, getByGroupId, getByExpenseId, delete, getGroupBalance, calculateSplits | getNeedsSettlement |
| **SplitTemplateDao** | split_templates | getAll, getById, insert, update, delete, getDefault, incrementUseCount | None |

### Subscriptions (3)

| DAO | Table | Key Methods |
|-----|-------|-------------|
| **SubscriptionPriceHistoryDao** | subscription_price_history | insert, getBySubscriptionId, getPriceChanges, getLatestPrice | getPriceHistory |
| **SubscriptionUsageDao** | subscription_usage | insert, getBySubscriptionId, getUsageStats, calculateMonthlyUsage | getUsageMetrics |
| **SplitItemAssignmentDao** | split_item_assignments | insert, getByExpenseId, getByReceiptItemId, delete, updatePaymentStatus | None |

### Bank & Multi-Currency (3)

| DAO | Table | Key Methods |
|-----|-------|-------------|
| **BankConnectionDao** | bank_connections | insert, getAll, getById, update, delete, getActive, updateLastSync, recordError | None |
| **ExchangeRateDao** | exchange_rates | insert, getRate, updateRate, getAllRates, getStaleRates, deleteOldRates | getRatesBySourceCurrency |
| **InvestmentDao** | investments | insert, getAll, getById, update, delete, getActive, updateCurrentPrice | getInvestmentsByType, getPortfolioValue |

### Recurring Lifecycle (3)

| DAO | Table | Key Methods |
|-----|-------|-------------|
| **RecurringOccurrenceDao** | recurring_occurrences | insert (IGNORE), insertAll, update, getByKey, getBySource, getByDateRange, getByStatus, updateStatus |
| **RecurringReminderDeliveryDao** | recurring_reminder_deliveries | insert, insertAll, update, getByOccurrenceAndWindow, getPendingDeliveries |
| **RecurringLifecycleEventDao** | recurring_lifecycle_events | insert, getEventsForOccurrence |

### Privacy & Audit (1)

| DAO | Table | Key Methods |
|-----|-------|-------------|
| **PrivacyAuditDao** | privacy_audit_events | insert, getRecent(limit) |

### Misc (2)

| DAO | Table | Key Methods |
|-----|-------|-------------|
| **MileageTrackingDao** | mileage_tracking | insert, getAll, getById, delete, getBusinessTrips, calculateTotalDeduction | getTripsForPeriod |
| **AnomalyAlertDao** | anomaly_alerts | insert, getAll, getById, markAsReviewed, deleteOldAlerts | getActiveAlerts |

---

## Repositories Registry (36+ Total)

### Core Business Logic (10)

| Repository | Purpose | Key Methods | DAO Dependencies |
|------------|---------|------------|------------------|
| **ExpenseRepository** | Expense CRUD + analytics + deduplication | insertExpense, updateExpense, deleteExpense, getExpenses, getExpensesByDate, getExpensesByMerchant, getDailyTotals, getMonthlyTotals, getMerchantStats, checkDuplicate, calculateMerchantKey | ExpenseDao, UserCorrectionDao, PendingReviewDao, MerchantCategoryRepository |
| **CategoryRepository** | Category CRUD + defaults | getCategories, getCategoryById, createCategory, updateCategory, deleteCategory, getDefaultCategories | CategoryDao |
| **BudgetRepository** | Budget management + alerts | getBudgets, createBudget, updateBudget, deleteBudget, calculateUtilization, checkBudgetExceeded, getAlertConfig | BudgetDao, ExpenseDao |
| **ReceiptRepository** | Receipt OCR + matching + item categorization | insertReceipt, matchReceiptToExpense, getReceiptsByStatus, categorizeItems, getItemCategories, updateItemCategory | ScannedReceiptDao, ReceiptItemCategorizationDao, ExpenseDao |
| **MerchantNormalizationRepository** | Merchant deduplication & canonicalization | normalizeAndStoreAlias, getMerchantCanonical, getCanonicalStats, recordMerchantUsage, fuzzyFindMerchant | MerchantNormalizationDao, MerchantCategoryRepository |
| **MerchantLocationRepository** | Geocoding cache + corrections | geocodeMerchant, getLocationCache, recordLocationCorrection, backfillLocations, clearOldCache | MerchantLocationDao, CompositeGeocodingService |
| **RecurringExpenseRepository** | Subscription detection & management | detectSubscriptions, getRecurringExpenses, createRecurring, updateRecurring, deleteRecurring, forecastNextBilling | RecurringExpenseDao, SubscriptionPriceHistoryDao, SubscriptionUsageDao |
| **PlannedExpenseRepository** | Future expense planning | createPlannedExpense, getPlannedForPeriod, updatePlanned, deletePlanned, convertToActual | PlannedExpenseDao |
| **SavingsGoalRepository** | Savings target tracking | createGoal, updateProgress, getGoalsByName, deleteGoal, calculateDaysToTarget | SavingsGoalDao |
| **WarrantyTrackerRepository** | Warranty extraction & alerts | insertWarranty, getActiveWarranties, getExpiringWarranties, markWarrantyClaimed, getWarrantiesByStatus | WarrantyDao, ScannedReceiptDao |

### AI & Insights (6)

| Repository | Purpose | Key Methods | DAO Dependencies |
|------------|---------|------------|------------------|
| **AiArtifactRepositoryImpl** | AI-generated content storage & retrieval (v34) | upsertArtifact, getArtifactByKey, getLatestByCapability, deleteExpiredArtifacts, getArtifactsByStatus | AiArtifactDao |
| **AiChatRepositoryImpl** | Chat session persistence (v35) | createSession, getSession, deleteSession, addMessage, getMessages, getSessions | AiChatSessionDao, AiChatMessageDao |
| **FinancialWeatherRepository** | Budget forecasting & stress scoring | generateForecast, updateActuals, calculateStressScore, getPredictionAccuracy, recordBudgetEvent | BudgetForecastDao, HealthScoreHistoryDao, StressForecastSnapshotDao |
| **AiEngagementRepositoryImpl** | User engagement tracking for AI | recordInteraction, getEngagementMetrics, trackPromptVersion | (custom persistence) |
| **AiSettingsRepositoryImpl** | AI feature toggle & mode selection | getAiMode, setAiMode, getFeatureConfig, updateProviderSettings | (SharedPreferences) |
| **RecommendationRepository** | AI-generated action recommendations (v36) | createRecommendation, getRecommendations, dismissRecommendation, deleteExpired, getByStatus | RecommendationDao |

### Analytics & Dashboards (5)

| Repository | Purpose | Key Methods |
|------------|---------|------------|
| **AnalyticsRepository** | Complex aggregation queries | getDailyTotals, getWeeklyTotals, getMonthlyTotals, getCategoryDistribution, getMerchantRanking, getLocationClusters, getDayOfWeekAnalysis |
| **DashboardRepository** | Dashboard widget data aggregation | getExpenseStats, getCategoryBreakdown, getBudgetStatus, getTopMerchants, getTrendingCategories, getRecentExpenses |
| **SourceStatsRepository** | Notification source analytics | getSourceStats, recordNotification, recordAccepted, recordRejected, getDuplicateRate, getTopSources |
| **ReviewQueueRepository** | Pending review prioritization | getPendingReviews, prioritizeByConfidence, prioritizeBySource, recordReview, getReviewStatus |
| **NotificationProcessingPipeline** | Notification ingestion & processing | processNotification, validateAmount, suggestCategory, checkDuplicate, flagAnomalies |

### Business & Financial Features (8)

| Repository | Purpose | Key Methods |
|------------|---------|------------|
| **BusinessExpenseRepository** | Business vs. personal separation & deductions | markAsBusinessExpense, calculateDeductions, getTaxDeductibleTotal, assignToProject, getBusinessExpensesByCategory, generateDeductionReport |
| **MultiCurrencyRepository** | **Canonical multi-currency aggregation backbone** — aggregates per-currency totals via DAO then converts to home currency. Wired into 10+ pipelines. | getTotalExpensesInHomeCurrency, getHomeCurrencyCategoryTotals, getHomeCurrencyDailyHistory, getMerchantTotalsInHomeCurrency, getHomeCurrencyMonthlyHistory | ExpenseDao, CurrencyConverter, CurrencySettingsRepository |
| **GroupsRepositoryImpl** | Shared expense groups & splits (v43) | createGroup, addMember, createGroupExpense, calculateSplits, settleDebts, getGroupBalance |
| **InvestmentRepository** | Portfolio tracking (v45) | insertInvestment, getPortfolio, updateCurrentPrice, calculateGainLoss, getPerformanceStats, calculateYield |
| **MerchantCategoryRepository** | Merchant → category auto-assignment | suggestCategory, recordAssociation, getBulkSuggestions, updateMapping, getAccuracy |
| **CurrencySettingsRepositoryImpl** | Default currency & conversion settings | setBaseCurrency, getBaseCurrency, setDisplayCurrency, getCurrencyFormat, **emergencyBuffer** (configurable emergency fund threshold) |
| **PromptStateRepository** | LLM prompt versioning & A/B testing | recordPromptVersion, getActiveVersion, logPromptUsage, measureAccuracy |
| **MerchantRulesRepository** | Merchant normalization rules engine | applyRules, recordRule, getRulesByMerchant, evaluateMatchConfidence |

### Data Management (3)

| Repository | Purpose | Key Methods |
|------------|---------|------------|
| **DatabaseBackupRepositoryImpl** | .costbackup bundle export/restore + legacy .db/.enc support | createCostBackup, restoreCostBackup, exportDatabase, importDatabase, createSafetyBackup, getDatabaseStats. Uses CostbackupBundle, RestoreMaintenanceMode, RestoreJournal, BackupVerifier. |
| **AccountingExportRepository** | Tax/accounting report generation | exportForTaxSeason, generateP&L, generateCashFlow, categorizeForTaxes |
| **NotificationRepository** | Raw notification CRUD & filtering | insertNotification, getById, getAll, delete, markAsProcessed, getByPackageAndTime |

### Infrastructure & Integration (4)

| Repository | Purpose | Key Methods |
|------------|---------|------------|
| **BankConnectionRepository** | Open Banking API (v46) | createConnection, deleteConnection, getConnections, updateLastSync, recordError, getConnectionStatus |
| **WidgetStyleRepositoryImpl** | Widget appearance customization | getWidgetConfig, updateWidgetConfig, saveTheme, getThemeList |
| **EmailReceiptRepository** | Email receipt ingestion config | getEmailAccounts, addAccount, removeAccount, syncReceipts, getLastSync |
| **UserCorrectionRepository** | Track user edits for ML training | recordCorrection, getCorrections, getApprovedCorrections, getRejectionReasons, calculateAccuracy |

---

## Network & API Integrations

### Geocoding Services (5 Providers)

| Service | Purpose | API | Quota Model | Status |
|---------|---------|-----|-------------|--------|
| **Nominatim (OSM)** | Free reverse geocoding | HTTPS REST | Unlimited (1 req/sec rate limit) | Primary background resolution |
| **Photon** | Photo-based address search | HTTPS REST | Free tier: 50 req/min | Interactive picker (low-value queries) |
| **Geoapify** | Commercial geocoding | HTTPS REST | Key-based quota | Interactive picker (premium results) |
| **GooglePlaces** | Google Places API | HTTPS REST | Key-based quota | Interactive picker (user opt-in, quota gating) |
| **Overpass** | OSM POI finder (bars, shops, etc.) | HTTPS REST | Unlimited | Merchant nearby search |

### Composite Service Logic (CompositeGeocodingService)
- **Interactive Picker** (`searchMultiple`): Fires all providers in parallel, merges, re-ranks by qualifier match, deduplicates by 50m proximity
- **Smart Gating**: 
  - Single-word query → Photon + Nominatim only
  - Multi-word query → All 4 providers
- **Background Resolution** (`search`): Nominatim only (preserves existing biasing logic)
- **Reverse Geocode** (`reverseGeocode`): Nominatim (address lookup)

### Email Ingestion (EmailReceiptIngestionService)
- **Protocols**: IMAP, POP3 (configurable per account)
- **Parsers**: Amazon, Apple, Uber (extensible)
- **Frequency**: User-configured polling

### AI Providers

**Categorization** (5 variants):
- CloudCategorizationAssistService
- OnDeviceCategorizationAssistService
- HybridCategorizationAssistService
- NoOpCategorizationAssistService
- SmartReceiptAssistService

**Receipt Assist** (4 variants):
- CloudReceiptAssistService
- OnDeviceReceiptAssistService
- HybridReceiptAssistService
- SmartReceiptAssistService

**Receipt Item Categorization** (3 variants):
- CloudReceiptItemCategorizationService
- OnDeviceReceiptItemCategorizationService
- HybridReceiptItemCategorizationService

**Dashboard Briefing** (3 variants):
- CloudDashboardBriefingService
- OnDeviceDashboardBriefingService
- HybridDashboardBriefingService

**Duplicate Detection** (3 variants):
- CloudDedupeJudgeService
- OnDeviceSemanticDuplicateDetector
- HybridDedupeJudgeService

**Query Interpretation** (3 variants):
- CloudQueryInterpretationService
- OnDeviceQueryInterpretationService
- HybridQueryInterpretationService

**Other AI Services:**
- CloudReviewExplanationService, OnDeviceReviewExplanationService, HybridReviewExplanationService
- CloudWarrantyExtractionService
- DefaultAiEnvironmentMonitor
- OnDeviceNotificationParser
- OnDeviceReviewPriorityScorer
- OnDeviceSemanticDuplicateDetector
- NoOpDedupeJudgeService, NoOpDashboardBriefingService, NoOpQueryInterpretationService, NoOpReceiptAssistService, NoOpReviewExplanationService

---

## Local Storage (Non-Room)

### Encrypted SharedPreferences (SecureKeyStorage)
| Key Constant | Purpose | Value Type | Default |
|--------------|---------|-----------|---------|
| `KEY_GEOAPIFY` | Geoapify API key | String (encrypted) | Null |
| `KEY_GOOGLE_PLACES` | Google Places API key | String (encrypted) | Null |
| `KEY_GEMINI` | Gemini AI API key | String (encrypted) | Null |

**Security**:
- AES-256-GCM encryption
- Android Keystore backend
- Hardware-backed when available (v31+)
- Biometric protection-ready

### Android SharedPreferences (Implicit)
| Use Case | Details |
|----------|---------|
| **AI Settings** | Feature toggles, mode selection (cloud/on-device/hybrid) |
| **User Preferences** | Default currency, language, notification settings |
| **Widget Configuration** | Theme, style, refresh frequency |
| **Email Accounts** | OAuth tokens, endpoint configs (encrypted fields) |

---

## Type Converters (Converters.kt)

| Converter | From ↔ To | Purpose |
|-----------|-----------|---------|
| TransactionType | String ↔ Enum | PURCHASE, WITHDRAWAL, TRANSFER, DEPOSIT |
| PaymentMethod | String ↔ Enum | CARD, CASH, BANK_TRANSFER, etc. |
| TransferDirection | String ↔ Enum | IN, OUT |
| OwnershipFilter | String ↔ Enum | ALL, MINE, NOT_MINE, SHARED, TRANSFER |
| **Custom Lists** | JSON String ↔ List<T> | Receipt items, alternative categories, split details |
| **Date/Time** | Long ↔ Timestamps | Unix milliseconds |

---

## Key Architectural Patterns & Patterns

### 1. **Repository Pattern Over DAOs**
- Repositories wrap DAOs
- Add business logic (deduplication, aggregation, external API calls)
- Expose Flow<T> for reactive updates

### 2. **Room @Transaction**
```kotlin
@Transaction
@Query("SELECT * FROM expenses ...")
fun getExpensesWithCategoryFlow(...): Flow<List<ExpenseWithCategory>>
```
- Joins via POJO `ExpenseWithCategory`
- Automatic FK resolution
- Used extensively for analytics queries

### 3. **RawQuery for Dynamic Filtering**
```kotlin
@RawQuery
suspend fun getExpensesDynamic(query: SupportSQLiteQuery): List<ExpenseWithCategory>
```
- Supports dynamic WHERE, ORDER BY, LIMIT
- Used by `ExpenseRepository.getExpensesDynamic()`

### 4. **Composite Pattern (CompositeGeocodingService)**
- Aggregates 4 geocoding providers
- Parallel execution via Kotlin coroutines
- Smart gating (single-word vs. multi-word queries)

### 5. **Multi-Implementation Pattern (AI Services)**
- Cloud / On-Device / Hybrid / NoOp variants
- Selected via dependency injection + settings
- Enables offline + online + testing scenarios

### 6. **Atomic Operations**
- `dedupeKey` UNIQUE index (v21) prevents duplicate inserts
- `insertAtomic(expense)` uses IGNORE conflict strategy
- Checked via `SELECT changes()`

### 7. **Backfill Workers (Background Async)**
- `LocationBackfillWorker`: Async geocoding (v28+)
- `MerchantKeyBackfillWorker`: Async merchant key generation (v32+)
- `DailyBriefingWorker`: Scheduled AI briefing generation

### 8. **Event-Driven Updates**
- `Flow<List<T>>` for reactive UI updates
- DAOs return Flow for subscriptions
- Repositories transform and aggregate

### 9. **Coordinator Pattern (Phase 3) — Transaction Lifecycle**
- `TransactionLifecycleCoordinator` is the single entry point for ALL expense CUD operations
- Pipeline: validate → normalize → dedupe → insert atomic → event log → side effects
- All creation paths (manual, notification, CSV, bank, email, group, receipt, review) converge through this coordinator
- Direct `insertAtomic` calls restricted to grandfathered files (see `DAO_ACCESS_GUARDRAILS.md`)

### 10. **Event Sourcing Lite (Phase 3) — Transaction Events**
- `transaction_events` table records every lifecycle transition as an immutable append-only row
- Events include actor, timestamps, before/after JSON snapshots for full audit trail
- Used for debugging, history reconstruction, and compliance

### 11. **Coordinator Pattern (Phase 4) — Receipt Lifecycle**
- `ReceiptLifecycleCoordinator` is the single entry point for ALL receipt processing
- Pipeline: validate → persist asset → OCR/parse → dedupe → save → event logging → side effects
- Receipt-expense linking centralized in `ReceiptLinkService` via `receipt_expense_links` join table
- Side effects are gated by `ReceiptDocumentType` (RETAIL_RECEIPT, EMAIL_RECEIPT, BANK_STATEMENT, etc.)

### 12. **Event Sourcing Lite (Phase 4) — Receipt Events**
- `receipt_events` table records every receipt lifecycle transition (CAPTURED, OCR_FAILED, PARSED, EXPENSE_CREATED, DELETED, etc.)
- Events include actor, status transitions, document type metadata, and error details
- Used for audit, debugging, and reconstructing receipt processing history

### 13. **Coordinator Pattern (Phase 5) — Recurring Lifecycle**
- `RecurringLifecycleCoordinator` is the primary entry point for generating and managing recurring occurrences
- Pipeline: expand → resolve → materialize, producing the expander-resolver-materializer triad
- Post-creation auto-link hook in `TransactionLifecycleCoordinator` bridges Phase 3 and Phase 5

### 14. **Expand → Resolve → Materialize Triad (Phase 5)**
- **Expander** (`RecurringOccurrenceExpander`): Pure domain logic, no DI needed. Converts recurrence rules to candidate occurrences.
- **Resolver** (`OccurrenceConflictResolver`): Pure domain logic, no DI needed. Matches candidates against actual expenses.
- **Materializer** (`RecurringOccurrenceMaterializer`): Persists results. INSERT-with-IGNORE for dedup, UPDATE for status transitions, creates reminder deliveries.

### 15. **Reminder Delivery Scheduling (Phase 5)**
- `recurring_reminder_deliveries` table holds scheduled reminders per occurrence and window
- Supported windows: DUE_DAY, N_DAYS_BEFORE (e.g., 3_DAYS_BEFORE, 7_DAYS_BEFORE), OVERDUE
- Designed for a `ReminderDispatchWorker` (WorkManager) that queries `getPendingDeliveries(now)`

### 16. **Backup/Restore Pipeline (Phase 9)**
- **`.costbackup` bundle format**: `CostbackupBundle` — outer header (10B magic + 2B version) + AES-256-GCM ciphertext containing a ZIP with manifest.json, database.sqlite, checksums.json, and files/receipts/.
- **Maintenance mode**: `RestoreMaintenanceMode` pauses all 7 background workers via `WorkManager.cancelUniqueWork()` and blocks notification ingestion during restore via `isWritesAllowed()` check.
- **Crash-safe restore journal**: `RestoreJournal` — 8-state state machine (PREPARING → STAGED → SAFETY_BACKUP_CREATED → SWAPPING → VERIFYING → COMPLETE or ROLLING_BACK). On app start, `AppStartupCoordinator.checkRestoreJournal()` calls `restoreJournal.checkAndRecover()` to auto-recover from failed restores.
- **56-entity verification**: `BackupVerifier` — 3-tier: TIER_1_EXACT (30 tables, row count must match), TIER_2_VALIDITY (16 tables, FK/integrity check), TIER_3_OPTIONAL (10 tables, may be absent). Runs PRAGMA integrity_check + foreign_key_check + per-table COUNT.

### 17. **Double-Counting Fix (Phase 10)**
- **ForecastInputAssembler.assemble()** — before merging planned expenses into the forecast input, queries `RecurringOccurrenceDao.getByDateRange()` to build a set of materialized occurrenceKeys. Planned expenses whose `sourceOccurrenceKey` matches any PLANNED/PAID occurrence are excluded.
- **MonthlySavingsSweepUseCase.calculateKnownUpcomingObligations()** — same occurrence-key dedup: MUST-priority planned expenses with a matching `sourceOccurrenceKey` in the materialized set are excluded from the known-upcoming total.
- Both fixes use the same pattern: `planned.sourceOccurrenceKey !in materializedOccurrenceKeys`.

---

## Database Migrations Summary (47 Total, v6→v96)

| Range | Feature Area | Count | Notes |
|-------|--------------|-------|-------|
| v6–8 | Core schema | 3 | Expenses, Categories, Budgets |
| v9–11 | Receipts + Reviews | 3 | Scanned receipts, Pending reviews |
| v12–13 | Recurring + Planning | 2 | Recurring expenses, Planned expenses, Savings goals |
| v14–16 | User Corrections + Indices | 3 | User correction table, FK/Index cleanup |
| v17–20 | Merchant Dedup + Dedupe | 4 | Merchant canonicalization (v17), dedupe keys (v21) |
| v21–22 | Atomic Safety | 2 | Dedupe key unique index, raw notification dedup |
| v23–27 | Transfers + Locations | 5 | Transfer direction, location fields, merchant locations, geolocation FK |
| v28–30 | Geolocation | 3 | Merchant locations cache, corrections, backfill attempt tracking |
| v31–33 | Merchant Keys | 3 | Unified merchant key (v32), location re-keying wipe (v33) |
| v34–36 | AI Phase 1 | 3 | AI artifacts (v34), Chat (v35), Recommendations (v36) |
| v37–39 | Receipts v2 | 3 | Item categorization (v37), Warranty (v38), Receipt matching (v39) |
| v40–42 | Subscriptions + Currency | 3 | Subscription tables (v40), Business/Personal (v41), Currency (v42) |
| v43–46 | Groups + Bank + Splits | 4 | Shared groups (v43), Budget forecasting (v44), Investments (v45), Bank (v46) |
| v47–50 | Schema Fixes | 4 | Enhanced splits, schema alignment, DEFAULT constraints normalization |
| v51–93 | [TBD - check live file] | 43 | Intermediate schema updates |
| v94–95 | Transaction Lifecycle Foundation (Phase 3) | 1 | Added `source` column to expenses, created `transaction_events` table |
| v95→96 | Receipt Lifecycle Foundation (Phase 4) | 1 | Created `receipt_events` + `receipt_expense_links` tables; added 10 columns to `scanned_receipts` (sourceType, documentType, processingStatus, fingerprints, hashes, ocrConfidence, parseFailureReason, updatedAt) |
| v96→100 | Recurring Lifecycle Foundation (Phase 5) | 1 | Created `recurring_occurrences` + `recurring_reminder_deliveries` tables; added `sourceOccurrenceKey` + `sourceRecurringRuleId` columns to `planned_expenses` |
| v100→101 | PlannedExpense hardening (Phase 5b) | 1 | Added 4 columns (status, linkedActualExpenseId, merchantKey, updatedAt); hardened reminder delivery unique index; deduped stale rows |
| v101→102 | Recurring lifecycle audit (Phase 5b) | 1 | Created `recurring_lifecycle_events` table with indices on occurrenceId, occurredAt, eventType |
| v102→103 | Privacy audit (Phase 6) | 1 | Created `privacy_audit_events` table with indices on timestampMs, capability, caller |
| v103→104 | Data retention (Phase 6) | 1 | Added `rawContentPurgedAt` to raw_notifications, `rawOcrTextPurgedAt` to scanned_receipts |

---

## Clean Architecture Compliance

### ✅ Adherence
- **No UI imports** in data layer entities/DAOs
- **No business logic** in entities (data classes)
- **Clear separation**: Domain ↔ Data ↔ UI

### ⚠️ Potential Violations Flagged
Check these files for UI class imports:

```sql
grep -r "import.*\.ui\." app/src/main/java/com/yourname/expensetracker/data/
```

**Expected Result**: None (Clean Architecture intact)

---

## Performance Optimizations

### Indices (60+ across all tables)
- **Compound indices** on high-cardinality queries: `(transactionType, categoryId, date)`
- **UNIQUE indices** for deduplication: `(dedupeKey)`, `(normalizedMerchantName)`, `(merchantAliases.rawName)`
- **NULLABLE indices** for filtering: `(isRelevant)`, `(isActive)`, `(status)`

### Query Patterns
| Pattern | Example | Optimization |
|---------|---------|---------------|
| **Date Range** | expenses WHERE date BETWEEN ... | index: (date) |
| **Category Summary** | GROUP BY categoryId | index: (categoryId) |
| **Merchant Dedup** | WHERE merchantKey = ... | UNIQUE index: (merchantKey) |
| **Location Queries** | WHERE latitude BETWEEN ... AND longitude BETWEEN ... | index: (latitude, longitude) |

### Flow<T> for UI Subscription
- DAOs return `Flow<List<Expense>>` instead of `suspend` for reactive updates
- Pagination via `getPage(limit, offset)` to prevent OOM on large datasets
- Deprecated `getAll()` marked for removal (replaced by `getAllFlow(500)`)

---

## Cross-References & Dependencies

### Repository Dependency Graph (Top-Level)

```
ExpenseRepository
├── ExpenseDao
├── UserCorrectionDao
├── PendingReviewDao
└── MerchantCategoryRepository
    ├── MerchantCategoryDao
    └── MerchantNormalizationRepository
        ├── MerchantNormalizationDao
        └── MerchantLocationRepository
            ├── MerchantLocationDao
            ├── CompositeGeocodingService
            └── MerchantKeyGenerator

FinancialWeatherRepository
├── BudgetForecastDao
├── HealthScoreHistoryDao
├── StressForecastSnapshotDao
└── ExpenseRepository (for historical data)

ReceiptRepository
├── ScannedReceiptDao
├── ReceiptItemCategorizationDao
├── ExpenseRepository (for matching)
└── AiArtifactRepositoryImpl (for item suggestions)

GroupsRepositoryImpl
├── ExpenseGroupDao
├── GroupMemberDao
├── GroupExpenseDao
└── ExpenseRepository (for linking)

AnalyticsRepository
├── ExpenseDao
├── CategoryDao
├── SourceStatsDao
└── MerchantLocationDao

NotificationProcessingPipeline
├── RawNotificationDao
├── PendingReviewDao
├── ExpenseRepository
├── MerchantNormalizationRepository
└── AiArtifactRepositoryImpl

DashboardContractsAdapter
├── DashboardRepository
├── BudgetRepository
└── RecommendationRepository

SharedExpenseDataPortAdapter
├── GroupsRepositoryImpl
└── ExpenseRepository
```

### Indirect Dependencies (External)
- **CompositeGeocodingService** → geocoding HTTP clients (Nominatim, Photon, Geoapify, Google Places)
- **EmailReceiptIngestionService** → IMAP/POP3 + receipt parsers
- **AiChatRepositoryImpl** → AI cloud API integrations
- **MultiCurrencyRepository** → exchange-rate source

---

## Overlapping & Redundant Queries

### 🚨 Deprecated DAO Methods (Multi-Currency Refactoring)

The following `ExpenseDao` methods are deprecated — use `MultiCurrencyRepository` instead:

- `getTotalSpentBetween` → `MultiCurrencyRepository.getTotalExpensesInHomeCurrency()`
- `getTotalForPeriod` → `MultiCurrencyRepository.getHomeCurrencyCategoryTotals()`
- `getCategorySpentInPeriod` → `MultiCurrencyRepository.getHomeCurrencyCategoryTotals()`
- 22+ other `@Deprecated("Raw SUM across mixed currencies. Use MultiCurrencyRepository for currency-aware aggregation.")` methods

Safe grouped-by-currency DAO helpers still available:
- `getAllSpentBetweenByCurrency()` → returns `List<CurrencyTotal>`
- `getAllCategoryTotalsBetweenByCurrency()` → returns `List<CategoryCurrencyTotal>`
- `getMerchantTotalsByCurrency()` → returns `List<MerchantCurrencyTotal>`
- `getMonthlyTotalsByCurrency()` → returns `List<MonthlyCurrencyTotal>`

---

### ⚠️ Potential Query Redundancy (Review Needed)

| Area | Overlap | Impact | Resolution |
|------|---------|--------|-----------|
| **Expense Filtering** | ExpenseRepository + AnalyticsRepository both query expenses with date ranges | Duplicate WHERE logic | Consolidate filter builder |
| **Merchant Dedup** | MerchantNormalizationRepository + MerchantCategoryRepository both normalize merchant names | Merchant key generated twice | Use single MerchantKeyGenerator |
| **AI Suggestions** | AiChatRepositoryImpl + AiArtifactRepositoryImpl both store AI outputs | Separate storage vs. cache | Define artifact vs. chat distinction |
| **Location Backfill** | LocationBackfillWorker + ExpenseRepository both have geocoding logic | Async vs. sync | Consolidate in LocationRepository |

---

## File Count Summary

```
database/
├── converter/
├── dao/
├── entity/
├── model/
└── AppDatabase.kt

repository/
├── adapters
├── pipelines
└── repositories

ai/
├── provider/
└── worker/

location/
email/
provider/
security/
service/

Totals omitted intentionally; current inventory is in flux.
```

---

## Recommendations

### 1. **Code Generation Candidate**
- 45 DAOs are largely boilerplate → Consider Android Room code generation plugins or templates

### 2. **Query Consolidation**
- Merge overlapping expense/merchant/location queries into shared builders
- Example: `ExpenseQueryBuilder` for reusable filter + sort logic

### 3. **Migration Testing**
- 46 migrations are complex (schema rewrites, table renames)
- Add migration smoke tests for each version upgrade path

### 4. **AI Provider Testing**
- 33 AI service implementations with shared interface
- Add mock/stub implementations for unit testing without network calls

### 5. **Geocoding Caching**
- Location backfill worker clears old cache (v32-33 wipe)
- Implement LRU eviction instead of full clear to preserve user pins

### 6. **Documentation**
- Add `@Deprecated` markers to obsolete DAOs (e.g., `ExpenseDao.getAll()`)
- Document migration reasoning in code comments (currently sparse)

---

## Related Docs
- [`ARCHITECTURE.md`](../../ARCHITECTURE.md) — High-level architecture
- [`app/build.gradle.kts`](../../app/build.gradle.kts) — Room dependency versions
- [`schemas/`](../../app/schemas/) — Room exported schemas for migration validation

---

**Last Updated**: May 2026 | **Schema Version**: 106 | **Total Entities**: 56 | **Total DAOs**: 54 | **Total Repositories**: 36+ | **Phase 9+10**: No schema migration | **data/backup/**: 4 files | **Double-count fix**: ForecastInputAssembler + MonthlySavingsSweepUseCase (occurrence-based dedup)
