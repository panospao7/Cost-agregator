# Future Work

> Generated: 2026-05-03 | Sources: MASTER-ISSUE-REGISTRY.md, REMAINING-ISSUES-PLAN.md,
> validate-major-*.md (4 files), review-p1-p2.md, review-p3-p4.md
>
> **Status:** 28 hardening batches (A–Y, Z1–Z3) completed, ~272 of 356 issues resolved.
> This document tracks the ~84 remaining items after final reconciliation.

---

## Executive Summary

| Subsystem | Small (<1h) | Medium (1-4h) | Large (1d+) | Infrastructure | **Total** |
|-----------|-------------|---------------|-------------|----------------|-----------|
| Transaction (TRN) | 3 | 1 | — | — | **4** |
| Receipt (RCP) | 2 | 7 | 6 | — | **15** |
| Recurring (REC) | 3 | 5 | 2 | — | **10** |
| Currency (CURR) | 3 | 4 | 2 | 1 | **10** |
| Privacy (PRV) | 1 | 4 | 4 | 1 | **10** |
| Backup (BAK) | 3 | 4 | 2 | 1 | **10** |
| Dashboard (DSH) | 5 | 3 | 2 | 1 | **11** |
| AI/ML (AIML) | 1 | 9 | 8 | 1 | **19** |
| Budget (BUD) | 2 | 8 | 6 | — | **16** |
| Warranty (WRN) | 3 | 8 | 10 | 1 | **22** |
| Location (LOC) | 2 | 3 | 3 | — | **8** |
| Search (SR) | 2 | 9 | 7 | 1 | **19** |
| Shared (SHR) | 2 | 5 | 3 | — | **10** |
| DB/Migration | 1 | 1 | 1 | 2 | **5** |
| Workers (WKR) | 3 | 4 | 3 | 2 | **12** |
| Forecast (FCST) | 1 | 6 | 5 | 1 | **13** |
| AI Integration (AID) | 3 | 3 | 2 | 1 | **9** |
| Migration Policy (RSP) | 4 | 1 | 1 | 2 | **8** |
| **Total** | **44** | **85** | **67** | **15** | **211** |

> **Note:** "Infrastructure" items are cross-cutting concerns that span multiple subsystems.
> The total count (212) includes _all_ sub-items; many issues have multiple sub-items
> each counted separately for granularity.

### Severity Distribution of Remaining Items

| Severity | Count | Notes |
|----------|-------|-------|
| CRITICAL | ~15 | Data integrity, privacy, or correctness risks |
| MAJOR | ~95 | Significant feature gaps or correctness issues |
| MEDIUM | ~55 | Polish, edge cases, UX improvements |
| MINOR/LOW | ~47 | Cleanup, dead code, KDoc, naming |

---

## PARTIALLY RESOLVED ITEMS (Status Inventory)

These issues had a core fix applied during hardening batches, but edge cases or
secondary concerns remain. They are included in the effort-organised sections below.

| Issue | What's Fixed | What Remains |
|-------|-------------|--------------|
| TRN-2 | `suggestedAmount=null`, `extractionState=SYNTHETIC_PLACEHOLDER` | `confidence=1.0f` still assigned in `markAsRelevant()` |
| TRN-16 | KDoc migration plan in `SourceStatsDao` | Inline mutable counters still in use; event-ledger not implemented |
| TRN-18 | `TransactionLifecycleCoordinator.validate()` catches partial coords | `approveReview()` assigns `locationSource=USER_MANUAL` based on `lat` alone |
| RCP-5 | Perceptual-hash TODO KDoc in `ReceiptLifecycleCoordinator` | Only exact SHA-256 matching exists; no pHash/dHash |
| RCP-9 | Uses parsed/null currency from OCR | EUR still the fallback default when none detected |
| RCP-15 | `@Transaction` wrapper added to item categorization save | Not fully transactional across all paths |
| RCP-20 | Batch path partially routed through coordinator | Still some bypass paths |
| RCP-24 | Legacy `deleteReceipt()` ordering partially fixed | Image-before-DB deletion still possible |
| FCST-2 | KDoc for double-count prevention in Monte Carlo | Cross-deduplication between recurring and discretionary may have edge cases |
| FCST-7 | Partial cross-deduplication in SynthesisEngine | Planned expenses still double-count with recurring on some paths |

---

## COMPREHENSIVE ISSUE INVENTORY BY SUBSYSTEM

Each item includes:
- **Effort:** Small (<1h) / Medium (1-4h) / Large (1d+)
- **Prerequisites** (if any)
- **Impact:** High / Medium / Low on user experience or correctness

---

### 1. Transaction Lifecycle (TRN)

| ID | Issue | Effort | Prerequisites | Impact | Notes |
|----|-------|--------|--------------|--------|-------|
| TRN-2 | `confidence=1.0f` still assigned to synthetic placeholders in `markAsRelevant()` | **Small** | None | Medium | Simple constant change |
| TRN-8 | Raw duplicate check happens AFTER expensive parse+AI fallback (line 161 vs 179) | **Medium** | None | High | Reorder: fingerprint pre-check before parse |
| TRN-16 | Source stats use mutable inline counters instead of event-derived ledger | **Large** | Design event schema | Medium | KDoc migration path exists |
| TRN-18 | `approveReview()` assigns `locationSource=USER_MANUAL` on `lat` alone before validation | **Small** | None | Low | Add conditional after validator runs |

---

### 2. Receipt Lifecycle (RCP)

| ID | Issue | Effort | Prerequisites | Impact | Notes |
|----|-------|--------|--------------|--------|-------|
| RCP-5 | Perceptual hash (pHash/dHash) not implemented; only SHA-256 exact matching | **Large** | Research image-hashing lib | Medium | TODO KDoc exists |
| RCP-6 | Item categorizations not propagated when receipt linked to expense | **Medium** | RCP-16 (stable item IDs) | High | Missing data pipeline |
| RCP-10 | Receipt review UI cannot edit currency | **Medium** | None | High | Add currency field to ReceiptScanState |
| RCP-16 | Receipt item rows lack stable identity (no itemIndex/fingerprint) | **Medium** | None | High | Prerequisite for RCP-6, RCP-30 |
| RCP-18 | Receipt total derivation from line items has no source tracking | **Small** | None | Medium | Add `totalSource` enum to ParsedReceipt |
| RCP-21 | Bank-statement receipts can match purchase transactions | **Small** | None | Medium | Add `documentType` filter in matcher |
| RCP-23 | Matching UI shows gross amount, matcher scores on effective amount | **Small** | None | Medium | Use effectiveAmount in UI |
| RCP-30 | Item categorization siloed — no propagation to budgets/expense model | **Large** | RCP-16 | High | Wires categorization to ExpenseRepository |
| RCP-N2 | No currency editing in receipt review UI (same root as RCP-10) | **Medium** | None | High | Add currency picker + field |
| RCP-2 | Unknown-size content providers bypass file-size protection | **Medium** | None | High | Implement streaming copy with hard byte limit |
| RCP-13 | Item AI validation checks count only; no per-item validation | **Medium** | None | Medium | Add per-item confidence/category/amount checks |
| RCP-29 | OCR saves JPEG quality 80; original quality not preserved for cloud | **Small** | None | Medium | Save original-quality variant alongside |
| RCP-27 | PDF processing silently limits to first 5 pages | **Small** | None | Low | Add user-visible warning |
| RCP-28 | OCR retry inconsistent — PDF path has no retry | **Medium** | None | Medium | Add retry to PDF processing path |
| RCP-17 | Unused regex patterns [2][3] in line-item parser | **Small** | None | Low | Dead code removal |
| RCP-N3 | Batch processing bypasses ReceiptLifecycleCoordinator | **Medium** | None | Medium | Route through coordinator |
| RCP-N4 | `receipt_item_categorizations` insert uses REPLACE | **Small** | None | Medium | Change to ABORT/IGNORE |

---

### 3. Recurring / Subscription (REC)

| ID | Issue | Effort | Prerequisites | Impact | Notes |
|----|-------|--------|--------------|--------|-------|
| REC-3 | Overdue bills advance only one interval, not pay-through-today | **Large** | None | High | Core BillReminderManager logic change |
| REC-4 | Irregular recurring items stuck forever — no user-input fallback | **Large** | None | High | Needs UX for user confirmation |
| REC-8 | First price change has no visible PriceChange row (no baseline) | **Medium** | None | Medium | Insert baseline at subscription creation |
| REC-10 | Detection misses annual/semiannual patterns (approximate day ranges) | **Medium** | None | Medium | Use calendar-aware year boundaries |
| REC-12 | Detection groups by merchant, not merchant+currency | **Small** | None | Medium | Add currency to group key |
| REC-14 | Manual recurring expenses lack `categoryId` field | **Medium** | None | Medium | Add nullable categoryId to entity+DAO |
| REC-15 | `getByMerchant()` uses exact match; collisions with similar names | **Medium** | MerchantKeyGenerator | Medium | Use MerchantKeyGenerator for lookups |
| REC-18 | Recommendations hardcode EUR symbol in user-facing text | **Small** | None | Medium | Use `CurrencyFormatter.getCurrencySymbol()` |
| REC-19 | Recommendation savings double-count across underutilization + cost | **Medium** | None | High | Take max per subscription not sum |
| REC-21 | Domain `PlannedExpense` drops currency field | **Medium** | None | Medium | Add currency to domain model |
| REC-24 | Duplicate notification risk from both legacy and coordinator paths | **Small** | None | Low | Remove legacy `getNotificationsDue()` |
| REC-25 | `isRecurring` not set correctly for occurrence-linked patterns | **Small** | None | Low | Check `sourceRecurringRuleId` |
| REC-20 | Effective amount for stability (design choice — document only) | **Small** | None | Low | Add KDoc explaining design choice |

---

### 4. Currency & Exchange (CURR)

| ID | Issue | Effort | Prerequisites | Impact | Notes |
|----|-------|--------|--------------|--------|-------|
| CURR-1 | Expense `baseAmount/baseCurrency/exchangeRateUsed` schema-only, never populated | **Large** | Design population trigger | High | Multi-step: creation hook → migration |
| CURR-2 | Exchange rate unique constraint (pair only) prevents historical rates | **Large** | DB-6 resolution | High | Already partially fixed — 3-column index exists in code; remaining edge case: `convert()` has no date param |
| CURR-6 | Home currency change triggers no re-normalization of existing amounts | **Large** | CURR-1 | High | Needs accounting-currency vs display-currency split |
| CURR-8 | `setLastRateUpdate()` executes even when zero rates fetched | **Small** | None | Medium | Guard with `rates.isNotEmpty()` |
| CURR-9 | `lastRateUpdate` stored in DataStore vs Room — two sources of truth | **Medium** | None | Medium | Unify to Room as source of truth |
| CURR-14 | Domain `ExchangeRate` doesn't include `validDate` | **Medium** | CURR-2 | Medium | Add `validDate` to domain model+adapter |
| CURR-15 | `CurrencyConverter` uses raw `String` params, not `CurrencyCode` type | **Medium** | None | Medium | Accept `CurrencyCode` typed params |
| CURR-18 | `getTotalSpentFlow()` not deprecated despite being currency-unsafe | **Small** | None | Medium | Add `@Deprecated` + replace callers |
| CURR-10 | `getAllRatesForBase()` naming misleading (returns rates TO base) | **Small** | None | Low | Rename to `getRatesToCurrency` |
| CURR-17 | Unchecked cast in `aggregateCurrencyTotalsToMoneyAggregate` | **Small** | None | Medium | Add `else → Timber.w` branch |
| CURR-11 | `Money` uses `Double` internally — minor-unit migration desirable | **Large** | Design decision | Medium | Breaking change; long-term precision goal |
| CURR-12 | `formatAmount()` in CurrencyFormatter not deprecated | **Small** | None | Low | Add `@Deprecated` + fix delegation |
| CURR-13 | HRK (Croatian Kuna) still in active currency list; no legacy marking | **Small** | None | Low | Add `isActive` metadata |
| CURR-16 | ECB refresh computes N×N pairs (~380 rows) — triangulation inefficiency | **Medium** | None | Low | Consider lazy triangulation |
| CURR-19 | CurrencyFormatter hardcodes 2 decimal places | **Small** | None | Low | Use `Currency.getDefaultFractionDigits` |

---

### 5. Privacy / Settings (PRV)

| ID | Issue | Effort | Prerequisites | Impact | Notes |
|----|-------|--------|--------------|--------|-------|
| PRV-N1 | Photon/Geoapify/GooglePlaces geocoding bypasses PrivacyGate | **Large** | PrivacyGate SPI | High | Add `privacyGate.check()` to all 3 services |
| PRV-2 | Finance-app notifications captured unconditionally (no deny-keywords) | **Medium** | None | High | Add customizable deny-keyword list |
| PRV-3 | Notification posting vs reading permission confusion | **Medium** | None | High | Add notification-listener onboarding flow |
| PRV-9 | Background workers not re-synced when AI/cloud settings change | **Large** | None | High | Create per-feature sync use cases |
| PRV-10 | Foreground service type `location` on notification capture service | **Small** | None | Medium | Remove `location` from `foregroundServiceType` |
| PRV-11 | `POST_NOTIFICATIONS` requested on first launch, not just-in-time | **Medium** | None | Medium | Make JIT — request when first notification would be shown |
| PRV-14 | DataStore corruption handler fails open (enables AI silently) | **Medium** | None | High | Fail closed + show user warning |
| PRV-16 | Deep links exported through custom scheme without auth | **Medium** | None | High | Add authentication confirmation |
| PRV-5 | AI settings allow contradictory states (cross-field guard) | **Medium** | None | Medium | Add cross-field UI validation |
| PRV-6 | Disabling cloud AI doesn't handle stored API keys | **Small** | None | Medium | Add key status + purge on disable |
| PRV-15 | Conversation history toggle lacks purge semantics | **Small** | None | Low | Add purge actions to settings |
| PRV-N2 | `saveApiKey()` blank input deletes key without confirmation | **Small** | None | Medium | Add confirmation dialog |

---

### 6. Backup / Restore / Export (BAK)

| ID | Issue | Effort | Prerequisites | Impact | Notes |
|----|-------|--------|--------------|--------|-------|
| BAK-10 | Reset database has no typed confirmation or debug guard | **Medium** | None | High | Add typed confirmation + restart |
| BAK-12 | Export UI loads all expenses into memory | **Large** | None | High | Implement streaming/windowing write |
| BAK-13 | Export has no snapshot consistency (offset-based paging) | **Medium** | None | High | Use stable ID snapshot |
| BAK-15 | Date range validation weak — no max range cap or start<end check | **Small** | None | Medium | Add guard clauses |
| BAK-N1 | Legacy `importDatabase()` lacks maintenance mode and journal | **Large** | None | Medium | Backport new `.costbackup` path patterns |
| BAK-NB | `DebugViewModel` fragile `transactionCount == -1` heuristic | **Small** | None | Medium | Use proper restart detection |
| BAK-NF | Legacy import verification only checks 5 of ~25 tables | **Medium** | None | Medium | Extend verification coverage |
| BAK-NC | `BackupEncryptionService` reads entire ZIP into memory | **Medium** | None | Medium | Use `CipherOutputStream` streaming |
| BAK-ND | `RestoreJournal` writes state then immediately deletes it | **Small** | None | Low | Skip terminal-state write |
| BAK-NE | `RestoreMaintenanceMode.exit()` doesn't reschedule workers | **Small** | None | Medium | Reschedule on `exit(NORMAL)` |

---

### 7. Dashboard & Analytics (DSH)

| ID | Issue | Effort | Prerequisites | Impact | Notes |
|----|-------|--------|--------------|--------|-------|
| DSH-2 | Drill-down boundaries use MIN/MAX date instead of calendar bounds | **Medium** | None | High | Use canonical calendar boundaries |
| DSH-3 | Weekly drill-down shows days outside the month | **Medium** | DSH-2 | High | Clip week to month boundary |
| DSH-6 | Safe-to-spend shows `monthSpent` when no budget (confusing) | **Small** | None | Medium | Show "Set budget" CTA instead |
| DSH-8 | `dropLast(1)` excludes by array position, not period key | **Small** | None | Medium | Filter by `periodKey` |
| DSH-N1 | `computeSpendingTrend()` skips empty months (gaps in chart) | **Medium** | None | Medium | Emit zero-filled series |
| DSH-N2 | `computeSpendingTrend()` may double-count from shared expenses | **Medium** | None | High | Explicitly deduplicate by expense ID |
| DSH-7 | Zero-spend periods excluded from averages | **Medium** | None | Medium | Generate full calendar buckets |
| DSH-10 | One-shot analytics flows don't react to data changes | **Medium** | None | Medium | Use DAO reactive flows |
| DSH-N4 | PersonalBest bounded by oldest purchase day (not all-time) | **Small** | None | Low | Use full day range |
| DSH-REM3 | DAO agg queries still compute MIN/MAX date (unused) | **Small** | None | Low | Remove from agg queries |
| DSH-N3 | `CategorySpending.currency` defaults to EUR | **Small** | None | Low | Pull from home currency setting |
| DSH-REM18 | `CategorySpending.moneyTotal` confusing naming | **Small** | None | Low | Rename/clarify |
| DSH-REM19 | `PeriodSummary.monthSpend` duplicates `totalSpend` | **Small** | None | Low | Remove redundancy |
| DSH-REM20 | `MonthlyComparisonCalculator` hardcodes `displayCurrency = EUR` | **Small** | None | Low | Make configurable |

---

### 8. AI / ML / Intelligence (AIML)

| ID | Issue | Effort | Prerequisites | Impact | Notes |
|----|-------|--------|--------------|--------|-------|
| AIML-6 | Anomaly detection compares within current month only — no historical baseline | **Large** | None | High | Add historical period baselines |
| AIML-25 | Runway calculation based on goal-funded, not real account balance | **Large** | Account balance feature | High | Integrate real balances |
| AIML-11 | Source trust inflated by duplicates (counted toward valid total) | **Medium** | None | Medium | Exclude duplicates from trust |
| AIML-12 | Source stats mutable counters not event-derived | **Large** | Event-sourcing design | Medium | Add event-ledger table |
| AIML-13 | ConfidenceRouter cache stale after reject/approve (60s TTL) | **Medium** | None | Medium | Add event-driven invalidation |
| AIML-14 | Merchant rejection keys use raw `merchant.lowercase()`, not MerchantNormalizer | **Medium** | None | Medium | Integrate MerchantNormalizer |
| AIML-15 | Model persistence not durable — `onBackground()` cancels without flush | **Medium** | None | High | Call `saveToDisk()` before cancel |
| AIML-16 | ML model files (JSON) leak sensitive vocabulary to internal storage | **Large** | Encryption layer | High | Encrypt model files at rest |
| AIML-17 | Category classifier returns stale/deleted category IDs | **Medium** | None | High | Validate against active categories |
| AIML-18 | Category classifier trains on merchant tokens only; ignores amount/day-of-week | **Large** | None | Medium | Expand feature set |
| AIML-19 | Hybrid classifier uses current time, not event timestamp | **Medium** | None | Medium | Pass explicit `eventTime` |
| AIML-20 | Single correction triggers global category learning (no confidence gate) | **Medium** | None | Medium | Add confidence-based learning |
| AIML-21 | Recommendation dedupe includes raw timestamps (breaks semantic dedup) | **Medium** | None | Medium | Use semantic signatures |
| AIML-26 | Bill reliability is pattern proxy (defaults to 75) not actual payment data | **Large** | Occurrence lifecycle | Medium | Use actual occurrence history |
| AIML-27 | Budget adherence double-counts hierarchy (overall + category summed) | **Medium** | None | Medium | Normalize hierarchical budgets |
| AIML-3 | InsightsEngine always uses current calendar month — no period range param | **Medium** | None | Medium | Accept `periodRange` parameter |
| AIML-8 | Anomaly method priority uses `ordinal` (enum ordering not business logic) | **Small** | None | Low | Add explicit priority field |
| AIML-30 | Smart savings uses hardcoded currencyless caps (75/200/500) | **Medium** | None | Medium | Use `SpendingThresholdCalculator` |
| AIML-31 | Smart savings treats uncategorized as discretionary | **Small** | None | Medium | Treat as unknown, not discretionary |
| AIML-32 | Lifestyle inflation uses English merchant keywords; no category metadata | **Large** | None | Medium | Add category-based detection |
| AIML-36 | `AnomalyDetector` uses `Calendar.getInstance()` (untestable) | **Small** | None | Low | Inject `TimeProvider` |

---

### 9. Budgets & Categories (BUD)

| ID | Issue | Effort | Prerequisites | Impact | Notes |
|----|-------|--------|--------------|--------|-------|
| BUD-5 | CRITICAL budgets counted as healthy in `CalculateBudgetStatusUseCase` | **Small** | None | High | Include CRITICAL in warning/unhealthy counts |
| BUD-7 | Category deletion converts category budgets to overall (FK SET NULL) | **Large** | DB schema change | High | Change to RESTRICT or add `budgetScope` |
| BUD-11 | Rollover N-per-period queries expensive (1000+ for daily budgets) | **Large** | Design `BudgetPeriodLedger` | Medium | Implement materialized rollover ledger |
| BUD-21 | Autopilot apply-all not transactional | **Medium** | None | Medium | Wrap loop in `withTransaction` |
| BUD-25 | Budget forecast uniqueness enforced at app layer only | **Medium** | DB schema change | Medium | Add partial unique index |
| BUD-28 | Category names not DB-unique | **Small** | None | Medium | Add unique index on `name` |
| BUD-30 | Default categories not protected at DAO level (plain @Delete) | **Medium** | None | Medium | Add `isDefault` guard |
| BUD-10 | Invalid `periodMode` silently becomes calendar mode (raw String, not enum) | **Medium** | None | Medium | Use enum for periodMode |
| BUD-12 | Rollover carries surplus only; deficits not tracked | **Medium** | None | Medium | Add policy selection |
| BUD-15 | Budget alert IDs overflow (`toInt()` on Long) | **Small** | None | Medium | Use stable ID mapping |
| BUD-16 | Budget status cache allows 30s stale alerts | **Medium** | None | Medium | Add change-driven invalidation |
| BUD-29 | `getByName()` exact/case-sensitive — "Food" ≠ "food" | **Small** | None | Medium | Use `COLLATE NOCASE` |
| BUD-31 | Deleting category cascades to delete merchant mappings | **Medium** | None | Medium | Change to SET NULL |
| BUD-32 | Merchant-category learning globally overwrites from single edit | **Large** | None | Medium | Add confidence-based learning |
| BUD-33 | Bulk category update uses mutex but no DB transaction | **Medium** | None | Medium | Add `withTransaction` |
| BUD-34 | Category update doesn't call `learnFromCorrection()` | **Medium** | None | Medium | Wire classifier training |
| BUD-35 | Cannot clear category via repository overload (no null setter) | **Small** | None | Medium | Allow null `categoryId` |
| BUD-36 | Merchant canonical lookup nondeterministic with multiple mappings | **Medium** | None | Medium | Add unique constraint |
| BUD-37 | Merchant-category mappings lack source/audit fields | **Medium** | None | Medium | Add `source/createdAt/updatedAt` |

---

### 10. Warranty / Returns (WRN)

| ID | Issue | Effort | Prerequisites | Impact | Notes |
|----|-------|--------|--------------|--------|-------|
| WRN-4 | Manual warranties create fake EUR placeholder receipts | **Medium** | None | Medium | Make `receiptId` nullable |
| WRN-6 | One warranty per receipt too restrictive (UNIQUE constraint) | **Medium** | DB schema change | Medium | Remove or soften unique constraint |
| WRN-8 | Protected value raw-sums gross amount with no currency awareness | **Medium** | None | Medium | Use effectiveAmount + currency |
| WRN-13 | Marked return not linked to refund expense | **Medium** | DB schema change | Medium | Add `refundExpenseId` FK |
| WRN-15 | Cloud extraction has no on-device fallback | **Large** | On-device extractor | Medium | Create hybrid router |
| WRN-16 | Cloud extraction ignores confidence thresholds (always creates draft) | **Medium** | None | Medium | Block creation below threshold |
| WRN-18 | Low-confidence drafts use fake defaults ("Unknown" merchant) | **Medium** | None | Medium | Improve fallback with user prompt |
| WRN-19 | Review UI cannot edit warranty fields | **Large** | None | Medium | Add edit form to WarrantyTrackerScreen |
| WRN-20 | Manual warranty path not transactional | **Small** | None | Medium | Wrap in `withTransaction` |
| WRN-22 | Price protection ignores merchant return window (uses hardcoded map) | **Large** | None | Medium | Consult `ReturnWindowDao` |
| WRN-23 | Simulated deals shown as real in UI (no `isSimulated` flag display) | **Small** | None | Medium | Display or filter simulated flag |
| WRN-24 | Excluded tracking keys not persisted across sessions | **Medium** | None | Medium | Add persistence layer |
| WRN-25 | No stable price-protection item identity (position-based only) | **Large** | RCP-16 | Medium | Add stable fingerprint |
| WRN-26 | Price protection not currency-safe (items lack conversion context) | **Large** | CURR-1 | High | Add currency to PriceProtectedItem |
| WRN-28 | Negotiation hardcoded market rates with no metadata | **Medium** | None | Low | Add rate metadata + staleness |
| WRN-29 | Negotiation ignores billing frequency (annual vs monthly) | **Medium** | None | Low | Normalize to monthly |
| WRN-30 | Negotiation currency-hardcoded to euros | **Small** | None | Medium | Use `MoneyFormatter` |
| WRN-N2 | Dual receipt-linking paths create split-brain risk | **Large** | None | High | Deprecate legacy linking in ReceiptRepository |
| WRN-7 | Return-window uniqueness inconsistent between entity and DAO | **Small** | None | Low | Align schema with DAO queries |
| WRN-27 | Credit-card benefits not tied to actual payment methods | **Large** | Payment method model | Low | Use actual payment methods |
| WRN-31 | Service-type detection misclassifies (order-dependent checks) | **Medium** | None | Low | Fix detection priority |
| WRN-32 | Customer value based on history count, not tenure | **Small** | None | Low | Use time-based tenure |
| WRN-N3 | Manual placeholder receipt has wrong `documentType` | **Small** | None | Low | Set `MANUAL_PLACEHOLDER` |

---

### 11. Location Enrichment (LOC)

| ID | Issue | Effort | Prerequisites | Impact | Notes |
|----|-------|--------|--------------|--------|-------|
| LOC-9 | Location analytics raw-sum currencies — needs MultiCurrencyRepository | **Large** | None | High | Route through MultiCurrencyRepository |
| LOC-10 | Background geocoding Greece-biased (Nominatim defaults) | **Medium** | None | High | Add configurable home country |
| LOC-3 | Overpass auto-accepted single result without distance/name/recency check | **Medium** | None | High | Add name-similarity + distance gates |
| LOC-8 | Map marker uses gross (`amount`) instead of effective amount | **Small** | None | Medium | Use `effectiveAmount` |
| LOC-11 | Nominatim retry violates 1 req/sec rate policy (retries inside mutex) | **Medium** | None | High | Apply rate-limit between retry attempts too |
| LOC-16 | Location write API accepts invalid coordinates (lat/lon out of range) | **Small** | None | Medium | Add `LocationDraftValidator` |
| LOC-17 | `onPoiSelected()` uses `SOURCE_OVERPASS_POI` for user selections | **Small** | None | Medium | Add `USER_CONFIRMED_POI` source |
| LOC-13 | Area spending merges unrelated same-name areas (no coarse-geo qualifier) | **Medium** | None | Medium | Add coarse-geo qualifier |
| LOC-14 | Travel detection uses `toLong()` truncation for negative offsets | **Small** | None | Low | Use floor-based bucketing |
| LOC-15 | Travel home inference purely frequency-based; no robustness | **Medium** | None | Low | Add confidence scoring |

---

### 12. Search / Reports (SR)

| ID | Issue | Effort | Prerequisites | Impact | Notes |
|----|-------|--------|--------------|--------|-------|
| SRH-1 | Legacy merchant extraction regex broken (single-word cap-only after at/from) | **Small** | None | Medium | Fix regex or retire |
| SRH-2 | Legacy search extracts filters (category, location) but never applies them | **Medium** | None | High | Wire extracted filters to execution |
| SRH-7 | Amount filters not currency-aware (raw compare on effectiveAmount) | **Large** | CURR-1 | Medium | Add currency to ExpenseQueryFilters |
| SRH-8 | Multi-filter drilldown loads ALL date-range expenses, filters in-memory | **Medium** | None | Medium | Push filters down to DAO |
| SRH-12 | AI query output validation too weak (no bounds on min/max amounts) | **Medium** | None | Medium | Add bounds checking |
| SRH-13 | Uncategorized spend excluded from category breakdown | **Small** | None | Medium | Include "Uncategorized" bucket |
| SRH-14 | Merchant filtering only `merchantKey`, no name-fallback resolution | **Medium** | MerchantKeyGenerator | Medium | Add alias resolution |
| SRH-17 | DD/MM vs MM/DD parsing order-dependent; no locale awareness | **Medium** | None | Medium | Add locale-aware disambiguation |
| SRH-19 | Legacy search defaults to `0→now` (entire history) when no date extracted | **Small** | None | Medium | Add sensible date bounds |
| SRH-20 | Hybrid query interpretation has no runtime fallback | **Large** | None | High | Add cascading on-device fallback |
| SRH-21 | No UI notice about cloud merchant exposure in queries | **Small** | None | Medium | Add privacy notice |
| SRH-22 | Query model lacks currency/source/status filters | **Medium** | None | Medium | Extend `ExpenseQueryFilters` |
| SRH-23 | Results not labeled exact vs partial match | **Medium** | None | Medium | Add match metadata |
| SRH-24 | Export paging offset-based (not atomic snapshot) | **Medium** | None | Medium | ID-based snapshot pagination |
| SRH-25 | PDF export includes non-expense transaction types | **Medium** | None | Medium | Add transactionType filter |
| SRH-26 | PDF period display shows exclusive end boundary | **Small** | None | Low | Fix period display |
| SRH-29 | Exported files in cache directory without encryption | **Large** | Encryption layer | High | Add encryption or redaction |
| SRH-N1 | AI prompt schema lacks `minAmount`/`maxAmount` fields | **Small** | None | Medium | Add amount fields to schema |
| SRH-N2 | Dead code: date pattern always null | **Small** | None | Low | Remove dead code |
| SRH-N3 | Tight coupling: `CloudQueryInterpretationService` instantiates `OnDevice` directly | **Small** | None | Low | Inject via DI |

---

### 13. Shared Expenses (SHR)

| ID | Issue | Effort | Prerequisites | Impact | Notes |
|----|-------|--------|--------------|--------|-------|
| SHR-3 | Archived groups vanish from budget offsets (should still count) | **Large** | None | High | Include `isActive=false` in budget offset calc |
| SHR-4 | Hard delete leaves Expenses with orphaned `isSharedExpense=true` flags | **Large** | None | High | Clean up Expense rows before hard-delete |
| SHR-11 | Invalid custom split silently falls back to equal split | **Small** | None | Medium | Surface fallback to user |
| SHR-12 | `myShareAmount` drifts from actual group split data | **Medium** | None | Medium | Add recompute trigger on split change |
| SHR-13 | Item assignment not transactional + unvalidated | **Medium** | None | Medium | Wrap in transaction + add validation |
| SHR-14 | Split templates weakly validated | **Medium** | None | Medium | Add template validation |
| SHR-16 | `currentUserGroupKey` CHECK constraint no-op for NULL values | **Medium** | DB schema change | Medium | Fix CHECK + set at creation |
| SHR-17 | `addExpenseToGroup()` accepts non-EQUAL split types without `customSplitsJson` | **Medium** | None | Medium | Add required param validation |
| SHR-5 | Existing-expense linking defaults to `now()` not expense date | **Small** | None | Medium | Default to expense date |
| SHR-6 | At-least-one current user not enforced for group | **Small** | None | Medium | Add validation |
| SHR-10 | Custom split requires all members (no subset splits) | **Large** | None | Medium | Support subset splits |
| SHR-15 | Two settlement calculation paths diverge (Split vs Settlement calculator) | **Medium** | None | Medium | Unify paths |

---

### 14. Database & Migration (DB)

| ID | Issue | Effort | Prerequisites | Impact | Notes |
|----|-------|--------|--------------|--------|-------|
| DB-5 | `repairTable()` uses all-or-nothing salvage; partial data unrecoverable | **Large** | None | High | Implement partial salvage |
| DB-4 | `INSERT INTO ... SELECT *` still used in 5 critical migration paths | **Medium** | None | Medium | Replace with explicit column lists |
| DB-2 | Budget forecast + subscription candidate constraints not DB-enforced | **Medium** | DB schema change | Medium | Add materialized keys + unique indexes |
| DB-8 | Cascade deletes risk financial history loss on some entity relationships | **Large** | None | High | Audit + change to SET NULL/soft-delete |
| DB-1 | Fresh-vs-migrated parity gap: stale partial indexes on `raw_notifications` | **Small** | None | Low | Remove stale partial indexes |
| DB-7 | String `@ColumnInfo(defaultValue)` annotations inconsistent | **Small** | None | Low | Standardize quoted form |
| DB-N1 | `MIGRATION_107_108` CHECK constraint gap from 106→107 undocumented | **Small** | None | Low | Add KDoc |

---

### 15. Background Workers (WKR)

| ID | Issue | Effort | Prerequisites | Impact | Notes |
|----|-------|--------|--------------|--------|-------|
| WRK-6 | AI briefing skips cached artifact delivery (always fetches fresh) | **Medium** | None | Medium | Check cache before generating |
| WRK-7 | AI briefing retries permanent exceptions (no transient/permanent split) | **Medium** | None | Medium | Classify error types |
| WRK-8 | Startup sync has no error containment (`schedule()` calls not wrapped) | **Small** | None | Medium | Wrap in `runCatching` |
| WRK-11 | Merchant-key backfill has no per-run budget (runs unbounded) | **Medium** | None | Medium | Add `maxBatches`/`maxDuration` |
| WRK-12 | No central background job audit table | **Large** | DB schema + DAO | Medium | Create `BackgroundJobRun` entity |
| WRK-15 | AI briefing not calendar-day aligned (24h periodic, not one-shot+reschedule) | **Medium** | None | Medium | One-shot + reschedule at midnight |
| WRK-16 | Warranty worker mixes reconciliation (mutation) with notifications (side effect) | **Large** | None | Medium | Split into separate workers |
| WRK-N1 | `DailyBriefingWorker` missing `WorkerSpec.enabled` gate | **Small** | None | Medium | Add gate check |
| WRK-N2 | All `schedule()` methods ignore `WorkerSpec.constraints` | **Large** | Centralize spec | High | Create `WorkerSpecScheduler` |
| WRK-N5 | Merchant KEEP policy prevents re-schedule after failure | **Small** | None | Medium | Use REPLACE for one-shot |
| WRK-N6 | `WorkerSpec.version` entirely unused in scheduling | **Medium** | None | Low | Implement version scheduling |
| WRK-13 | Lifecycle observer not idempotent (multiple init calls) | **Small** | None | Medium | Add initialized guard |
| WRK-14 | Background observer swallows release errors (no logging) | **Small** | None | Low | Log in release too |
| WRK-N3 | `BillReminderWorker` notification ID collision risk | **Small** | None | Medium | Use stable ID generator |
| WRK-N4 | `BillReminderWorker` marks SENT before delivery confirmation | **Small** | None | Medium | Return delivery result |

---

### 16. Forecasting / Cash Flow (FCST)

| ID | Issue | Effort | Prerequisites | Impact | Notes |
|----|-------|--------|--------------|--------|-------|
| FCST-3 | Block Party monthly total vs actual spikes inconsistent (sum of actuals) | **Medium** | None | High | Sum actual occurrences correctly |
| FCST-4 | Monte Carlo double-counts recurring expenses (distribution + knownUpcoming) | **Medium** | None | High | Filter recurring from discretionary pool |
| FCST-9 | Stress forecast starts at balance 0.0 (no account balance source) | **Large** | Account balance feature | High | Integrate real balances or rename clearly |
| FCST-5 | Dashboard vs weather forecast use different data scopes | **Large** | None | Medium | Create dedicated forecast source |
| FCST-6 | Weather forecast ignores detected recurring patterns (confirmed only) | **Medium** | None | Medium | Use `getAllRecurringPatterns()` |
| FCST-10 | Income timing too simple (no payday detection) | **Large** | None | Medium | Add recurring income matching |
| FCST-12 | CashFlow double-counts actual + predicted expenses on same day | **Medium** | None | High | Deduplicate by merchant/date |
| FCST-14 | Forecast confidence disconnected from data quality metrics | **Large** | DataQualityAssessor | Medium | Integrate quality scoring |
| FCST-N1 | Weather path passes `manualRecurringEntities = emptyList()` | **Small** | None | Medium | Pass actual manual entities |
| FCST-N2 | Dashboard forecast bypasses `AnalyticsCurrencyNormalizer` | **Medium** | None | Medium | Route through normalizer |
| FCST-15 | Monte Carlo recency overstates quality (last 7 days weighted too heavily) | **Medium** | None | Medium | Apply 3-day quiet filter |
| FCST-16 | Spending distribution excludes quiet weeks (biasing upward) | **Medium** | None | Medium | Include zero-spend weeks |
| FCST-17 | Fallback hides all failures (catch-all returns zero) | **Small** | None | Medium | Add structured diagnostics |
| FCST-N3 | Inconsistent `merchantKey` fallback between forecast paths | **Small** | None | Low | Unify fallback logic |
| FCST-N4 | `SynthesisEngine` doesn't check `PlannedExpense.status` | **Small** | None | Medium | Add status filter |

---

### 17. AI Integration (AID)

| ID | Issue | Effort | Prerequisites | Impact | Notes |
|----|-------|--------|--------------|--------|-------|
| AID-4 | Runtime fallback missing in 6 of 7 hybrid services | **Large** | Design HybridExecutor | High | Create shared HybridExecutor |
| AID-9 | No AI result application boundary — suggestions auto-apply without audit | **Large** | Design audit boundary | High | Add validation + audit trail |
| AID-N3 | Cloud providers inconsistently use PrivacyGate (some inline, some gate) | **Medium** | None | Medium | Inject PrivacyGate into all providers |
| AID-N4 | `CloudDedupeJudgeService` confidence not bounded 0-1 | **Small** | None | Medium | Use `boundedConfidenceOrNull()` |
| AID-N5 | `CloudReceiptAssistService` lacks bounded positivity + epoch validation | **Medium** | None | Medium | Add standard validators |
| AID-N6 | No canonical AI defaults object | **Small** | None | Low | Create `DefaultAiSettings` |
| AID-10 | Per-request diagnostics incomplete (not systematically captured) | **Medium** | None | Medium | Extend coverage |
| AID-E | `AiPolicyTest` has misleading comment | **Small** | None | Low | Fix comment |
| AID-F | `CloudDashboardBriefingService` logs full URL (may contain API key) | **Small** | None | Low | Trim log output |

---

### 18. Migration Policy (RSP)

| ID | Issue | Effort | Prerequisites | Impact | Notes |
|----|-------|--------|--------------|--------|-------|
| RSP-R2A | No migration path for schema versions 1-5 (users stuck on ancient DB) | **Large** | None | High | Add v1→v6 migration or LegacyDatabaseImporter |
| RSP-R3A | No migration tests for versions 92→108 (16 migrations uncovered) | **Large** | None | High | Write migration tests with real DB snapshots |
| RSP-R2B | Multi-hop MIGRATION_96_100 missing schema JSON for v97/98/99 | **Small** | None | Medium | Add per-version schema or confirm unreleased |
| RSP-R3B | No real-DB snapshot migration tests | **Large** | RSP-R3A | Medium | Add real-DB parity tests |
| RSP-R3C | No `PRAGMA foreign_key_check` in migration test assertions | **Small** | RSP-R3A | Medium | Add FK check utility |
| RSP-R4A | No pre-upgrade backup prompt | **Small** | None | Medium | Add upgrade detection |
| RSP-R5A | No legacy DB importer for pre-v6 schemas | **Large** | RSP-R2A | Medium | Implement `LegacyDatabaseImporter` |
| RSP-R6A | No fresh-vs-migrated side-by-side parity test | **Large** | RSP-R3A | Medium | Add parity test |
| RSP-A2 | `SimpleDateFormat` → `DateTimeFormatter` | **Small** | None | Medium | ✅ RESOLVED — CsvExpenseImporter, ReceiptParser, HomeViewModel, BankStatementParser all converted. ~7 parser sites migrated across Batch 3 quick wins. |
| RSP-A3 | `countRowsFromSourceTable` uses string interpolation (SQL injection risk) | **Small** | None | High | Add table name whitelist |

---

## INFRASTRUCTURE (Cross-Cutting)

Items that span multiple subsystems or require architectural decisions.

| # | Initiative | Effort | Subsystems Affected | Impact | Description |
|---|------------|--------|---------------------|--------|-------------|
| I1 | Wire `RecurringOccurrenceExpander` into forecast/cashflow paths | **Large** | FCST, REC | High | Replace `nextExpectedDate` with `generateOccurrences()` in SynthesisEngine, CashFlowCalculator, FinancialWeatherRepository |
| I2 | Retire legacy `BillReminderManager` paths | **Medium** | REC | Medium | Delegate to `RecurringLifecycleCoordinator`, deprecate `getNotificationsDue()` and `markBillPaid()` |
| I3 | Deprecate and bypass `ReceiptRepository` legacy linking | **Large** | RCP, WRN | High | Route all receipt ops through `ReceiptLifecycleCoordinator` and `ReceiptLinkService` |
| I4 | Hard-code `MultiCurrencyRepository` adoption in remaining aggregates | **Large** | DSH, LOC, BUD, CURR | High | Replace deprecated raw-sum DAO calls in TotalsAggregationEngine, SpendingHeatmapEngine, BudgetRepository |
| I5 | Add `PrivacyGate` checks to all external-service providers | **Medium** | PRV, AID, LOC | High | Systematically audit and add `privacyGate.check()` to every external API entry point |
| I6 | Normalize AI confidence scales and validation across all providers | **Large** | AID, AIML, WRN | Medium | Create shared `AiOutputValidators`, use `boundedConfidenceOrNull()` everywhere |
| I7 | Fix period boundary correctness across dashboard/analytics | **Medium** | DSH, AIML | Medium | Replace MIN/MAX date → calendar boundaries; replace ms-subtraction → TimePeriodUtils |
| I8 | Add DB invariant enforcement for remaining entities | **Medium** | DB, BUD | Medium | Add materialized keys, UNIQUE indexes, CHECK constraints for BudgetForecast, SubscriptionCandidate |
| I9 | Centralize WorkerSpec scheduling and constraint enforcement | **Large** | WKR | High | Create `WorkerSpecScheduler` that respects `enabled`/`constraints`/`version` for all workers |
| I10 | Create end-to-end migration test suite (v6→v112) | **Large** | RSP, DB | High | Real-DB snapshots, parity tests, FK checks for all 92+ migration paths |
| I11 | Account balance source integration | **Large** | FCST, AIML, DSH | High | Create canonical account balance provider; wire into stress forecast, runway, safe-to-spend |
| I12 | Event-sourced audit ledger for source statistics | **Large** | TRN, AIML | Medium | Replace mutable inline counters with event-sourced `SourceStatsEvent` table |

---

## Effort-Breakdown Reference Sections

### IMMEDIATE — Small Effort (<1 hour each, ~45 items)

Quick wins: deprecations, dead-code removal, KDoc, single-line guards.

| Subsystem | Items |
|-----------|-------|
| TRN | TRN-2 (confidence=1.0f constant), TRN-18 (source labeling) |
| RCP | RCP-18 (totalSource flag), RCP-17 (dead patterns), RCP-N4 (IGNORE), RCP-27 (page warning) |
| REC | REC-12 (merchant+currency group), REC-18 (symbol), REC-24 (legacy path), REC-25 (isRecurring), REC-20 (KDoc) |
| CURR | CURR-8 (zero-rates guard), CURR-10 (rename), CURR-13 (HRK metadata), CURR-16 (triangulation note), CURR-19 (fraction digits), CURR-12 (deprecate), CURR-18 (deprecate) |
| PRV | PRV-10 (FGS type), PRV-N2 (confirmation), PRV-6 (key status) |
| BAK | BAK-15 (date guards), BAK-NB (heuristic fix), BAK-ND (journal write), BAK-NE (reschedule) |
| DSH | DSH-6 (CTA instead of monthSpent), DSH-8 (periodKey filter), DSH-N4 (all-time), DSH-REM3 (cleanup), DSH-N3 (currency), DSH-REM18/19/20 (naming/cleanup) |
| AIML | AIML-8 (priority field), AIML-31 (uncategorized), AIML-36 (TimeProvider) |
| BUD | BUD-5 (critical count), BUD-28 (unique name), BUD-29 (NOCASE), BUD-35 (null category) |
| WRN | WRN-20 (transactional), WRN-23 (simulated flag), WRN-N3 (documentType) |
| LOC | LOC-8 (effectiveAmount), LOC-17 (source constant), LOC-14 (floor-bucket) |
| SR | SRH-1 (regex fix), SRH-19 (date bounds), SRH-21 (privacy notice), SRH-N1 (prompt schema), SRH-N2 (dead code), SRH-N3 (DI), SRH-13 (Uncategorized bucket) |
| SHR | SHR-11 (fallback surface), SHR-5 (expense date), SHR-6 (user enforcement) |
| DB | DB-1 (partial indexes), DB-7 (defaultValue quotes), DB-N1 (doc gap) |
| WKR | WRK-8 (runCatching), WRK-N1 (gate), WRK-N5 (REPLACE), WRK-13 (guard), WRK-14 (logs), WRK-N3 (ID gen), WRK-N4 (delivery) |
| FCST | FCST-N1 (manual entities), FCST-N5 (dead code), FCST-N4 (status filter), FCST-17 (diagnostics) |
| AID | AID-N4 (bounded confidence), AID-N6 (defaults), AID-E (comment), AID-F (logs) |
| RSP | RSP-R2B (schema JSON), RSP-R3C (FK check), RSP-R4A (backup prompt), RSP-A3 (whitelist) |

### SHORT-TERM — Medium Effort (1-4 hours each, ~85 items)

Well-scoped features: new fields, DAO changes, validation logic, wiring.

| Subsystem | Items |
|-----------|-------|
| TRN | TRN-8 (fingerprint pre-check) |
| RCP | RCP-6 (categorization pipeline), RCP-10 (currency editing), RCP-16 (stable item IDs), RCP-21 (docType filter), RCP-N2 (currency picker), RCP-2 (streaming limit), RCP-13 (item validation), RCP-28 (PDF retry), RCP-N3 (coordinator route), RCP-23 (UI amount) |
| REC | REC-8 (baseline PriceChange), REC-10 (annual detection), REC-14 (categoryId), REC-15 (MerchantKeyGenerator), REC-19 (dedup savings), REC-21 (currency domain) |
| CURR | CURR-9 (DataStore→Room), CURR-14 (validDate domain), CURR-15 (CurrencyCode params) |
| PRV | PRV-2 (deny-keywords), PRV-3 (listener flow), PRV-11 (JIT), PRV-14 (fail-closed), PRV-5 (cross-field guard) |
| BAK | BAK-10 (typed confirmation), BAK-13 (snapshot), BAK-NF (table coverage), BAK-NC (streaming) |
| DSH | DSH-2 (calendar boundaries), DSH-3 (week clipping), DSH-N1 (zero-fill), DSH-N2 (dedup), DSH-7 (calendar buckets), DSH-10 (reactive flows) |
| AIML | AIML-11 (duplicate exclusion), AIML-13 (cache invalidation), AIML-14 (MerchantNormalizer), AIML-15 (flush on cancel), AIML-17 (active category check), AIML-19 (eventTime), AIML-20 (confidence learning), AIML-21 (semantic dedup), AIML-27 (hierarchy normalize), AIML-3 (periodRange), AIML-30 (threshold calculator) |
| BUD | BUD-21 (transactional), BUD-25 (unique index), BUD-30 (isDefault guard), BUD-10 (periodMode enum), BUD-12 (deficit policy), BUD-16 (invalidation), BUD-31 (SET NULL), BUD-33 (transaction), BUD-34 (learnFromCorrection), BUD-36 (unique constraint), BUD-37 (audit fields), BUD-15 (ID mapping) |
| WRN | WRN-4 (nullable receiptId), WRN-6 (constraint relax), WRN-8 (amount+currency), WRN-13 (refundExpenseId), WRN-16 (threshold block), WRN-18 (fallback), WRN-24 (persistence), WRN-28 (rate metadata), WRN-29 (frequency normalize), WRN-31 (detection priority) |
| LOC | LOC-10 (home country config), LOC-3 (validation gates), LOC-11 (rate limit fix), LOC-16 (validator), LOC-13 (coarse-geo) |
| SR | SRH-2 (apply filters), SRH-8 (DAO pushdown), SRH-12 (bounds check), SRH-14 (alias resolution), SRH-17 (locale parsing), SRH-22 (extend filters), SRH-23 (match metadata), SRH-24 (snapshot paging), SRH-25 (type filter) |
| SHR | SHR-12 (split recompute), SHR-13 (transaction+validation), SHR-14 (template validation), SHR-16 (CHECK constraint), SHR-17 (param validation), SHR-15 (unify paths) |
| DB | DB-4 (explicit columns), DB-2 (materialized keys) |
| WKR | WRK-6 (cached artifact), WRK-7 (error classification), WRK-11 (per-run budget), WRK-15 (calendar align), WRK-N6 (version scheduling) |
| FCST | FCST-3 (actual occurrences), FCST-4 (filter recurring), FCST-6 (all patterns), FCST-12 (dedup), FCST-N2 (normalizer), FCST-15 (quiet filter), FCST-16 (zero weeks), FCST-N3 (fallback) |
| AID | AID-N3 (PrivaceGate), AID-N5 (validators), AID-10 (diagnostics) |

### LONG-TERM — Large Effort (1 day+ each, ~67 items)

Architectural changes, new features, schema migrations, cross-cutting rewrites.

| Subsystem | Items |
|-----------|-------|
| RCP | RCP-5 (perceptual hash), RCP-30 (categorization→budget pipeline) |
| REC | REC-3 (pay-through-today), REC-4 (irregular fallback) |
| CURR | CURR-1 (baseAmount population), CURR-2 (historical convert), CURR-6 (re-normalization trigger), CURR-11 (minor-unit migration) |
| PRV | PRV-N1 (3 geocoding gates), PRV-9 (per-feature sync), PRV-16 (auth confirmation) |
| BAK | BAK-12 (streaming export), BAK-N1 (legacy import journal+mode) |
| DSH | None rated Large |
| AIML | AIML-6 (historical baselines), AIML-25 (real balances), AIML-12 (event ledger), AIML-16 (model encryption), AIML-18 (full features), AIML-26 (payment data), AIML-32 (category inflation) |
| BUD | BUD-7 (RESTRICT FK), BUD-11 (rollover ledger), BUD-32 (confidence learning) |
| WRN | WRN-15 (on-device fallback), WRN-19 (edit form UI), WRN-22 (return window DAO), WRN-25 (stable fingerprint), WRN-26 (currency safety), WRN-N2 (legacy linking), WRN-27 (payment methods) |
| LOC | LOC-9 (MultiCurrencyRepository adoption) |
| SR | SRH-7 (currency-aware filters), SRH-20 (hybrid fallback), SRH-29 (export encryption) |
| SHR | SHR-3 (archived budgets), SHR-4 (orphan cleanup), SHR-10 (subset splits) |
| DB | DB-5 (partial salvage), DB-8 (cascade audit) |
| WKR | WRK-12 (job audit table), WRK-16 (worker split), WRK-N2 (central scheduler) |
| FCST | FCST-9 (account balance), FCST-5 (data scope), FCST-10 (payday detection), FCST-14 (quality integration) |
| AID | AID-4 (HybridExecutor), AID-9 (audit boundary) |
| RSP | RSP-R2A (v1-v5 migration), RSP-R3A (16 migration tests) |

### INFRASTRUCTURE (15 cross-cutting items)

Listed in the Infrastructure section above (I1–I12).

---

## Recommendations

### Top 10 Most Impactful Items (by risk reduction)

| Rank | Item | Subsystem | Rationale |
|------|------|-----------|-----------|
| 1 | TRN-8: Fingerprint pre-check before parse/AI | TRN | Expensive AI fallback wasted on duplicates daily |
| 2 | DB-5: Partial salvage in repairTable() | DB | All-or-nothing data loss on corruption |
| 3 | PRV-N1: PrivacyGate for all geocoding | PRV | Potential location data leak through 3 services |
| 4 | I4: MultiCurrencyRepository adoption | Cross | Raw-sum aggregates give wrong totals in multi-currency |
| 5 | AID-9: AI audit boundary | AID | Auto-applied AI suggestions with no audit trail |
| 6 | BUD-5: Critical budgets counted healthy | BUD | Users not warned about overspent critical budgets |
| 7 | SHR-4: Hard-delete orphan cleanup | SHR | Shared expenses silently lose group association |
| 8 | WRN-N2: Legacy linking split-brain | WRN | Two receipt-linking paths cause inconsistent state |
| 9 | SRH-29: Export file encryption | SR | Sensitive financial data in unencrypted cache files |
| 10 | I10: Migration test suite (v6→v112) | RSP | 16 untested migrations risk silent data corruption on upgrade |

### Quick Wins (can be done in <1 day total)

1. TRN-2: Change confidence constant (5 min)
2. RCP-18: Add `totalSource` enum field (15 min)
3. RCP-N4: Change REPLACE to ABORT (5 min)
4. CURR-8: Add `rates.isNotEmpty()` guard (5 min)
5. DSH-6: Show "Set budget" CTA (30 min)
6. AIML-36: Inject TimeProvider (15 min)
7. LOC-8: Use effectiveAmount in markers (15 min)
8. SRH-13: Include Uncategorized bucket (20 min)
9. WRN-20: Add `withTransaction` (10 min)
10. FCST-N1: Pass manual entities (15 min)

---

*Generated 2026-05-03. Based on MASTER-ISSUE-REGISTRY.md (final reconciliation),
REMAINING-ISSUES-PLAN.md, and 4 validate-major-*.md files with actual code inspection.*
