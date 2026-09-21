# Engine 1 Current Re-Audit — Warranty / Subscription / Location / NLP

Target inspected branch: `fix/pipeline-1-5-local-issues`  
Mode: static GitHub inspection only.  
I did **not** run Gradle, tests, KSP, Room, lint, or compile.

## Self-review verdict

**YELLOW / improved but not clean**

Engine 1 is much healthier than the old tracker says in some areas:

- warranty/subscription writes now mostly use `DatabaseWriteBarrier`
- GPS access is privacy-gated
- map heatmap/insights are spending-only and currency-normalized
- NLP amount filters are currency-aware
- assistant “largest” query is currency-aware
- cloud query prompt redaction is actually wired before HTTP

But it is **not clean enough to call done** because:

- warranty lifecycle events are incomplete
- subscription validation remains weak
- bill negotiation is still mostly demo/in-memory
- `MarketRateProvider` exists but is not wired into `SmartBillNegotiationEngine`
- cloud query catches broad `Exception` and can swallow coroutine cancellation
- deprecated/raw APIs remain callable
- some “fixed” tracker claims are only partial

---

# 1. Engine scout

## Engine

Warranty / Subscription / Location / NLP engines.

## Risk level

Mixed:

| Sub-engine | Risk |
|---|---|
| Warranty tracking | Medium-high |
| Subscription manager | High |
| Smart bill negotiation | High but beta/demo |
| Location resolver/map | Medium |
| Legacy NLP search | Medium |
| Cloud query interpretation | High privacy risk |
| Assistant financial query | Medium-high financial correctness |

## Current files inspected

- `WarrantyTrackerRepository.kt`
- `WarrantyDao.kt`
- `WarrantyLifecycleEvent.kt`
- `ReturnWindow.kt`
- `ScannedReceipt.kt`
- `SubscriptionManagerEngine.kt`
- `SmartBillNegotiationEngine.kt`
- `MarketRateProvider.kt`
- `LocationResolver.kt`
- `SpendingMapViewModel.kt`
- `NaturalLanguageSearchEngine.kt`
- `NaturalLanguageExpenseQueryRepositoryImpl.kt`
- `ExecuteFinancialQueryUseCase.kt`
- `CloudQueryInterpretationService.kt`
- architecture docs:
  - `ENGINE_INTERACTION_MAP.md`
  - `CODEBASE_SEGMENTS.md`

## Affected pipelines

From `ENGINE_INTERACTION_MAP.md` and current call sites:

| Engine path | Affected pipelines |
|---|---|
| Warranty extraction/tracking | Receipt side effects, backup/export, AI warranty extraction |
| Subscription management | Recurring expenses, planned expenses, dashboard, budget, forecast |
| Bill negotiation | Subscription UI, recurring amount, price history |
| Location resolver | Location enrichment, map UI, spending heatmap, travel/area insights |
| NLP search | Natural-language search, assistant query entry points |
| Cloud query interpretation | AI platform/privacy, assistant |
| Assistant query execution | Analytics-like financial summaries, drilldowns |

## Schema/migration impact

No changes made.

Potential future schema impact:

- `NegotiationOutcome` persistence would need new entity/DAO/migration.
- Full warranty lifecycle event model may need enum/type cleanup but can reuse existing table if kept string-based.
- If adding document type constants only, no migration.
- If adding stricter subscription currency constraints at DB level, migration likely needed.

## Hilt/DI impact

No changes made.

Potential future DI impact:

- `SmartBillNegotiationEngine` should inject `MarketRateProvider`.
- Cloud query test constructors should be moved to test source or made explicitly test-only.
- Subscription/warranty write barriers are already injected in reviewed paths.

---

# 2. Old issue reconciliation

## Summary table

| Old ID | Current verified status | Notes |
|---|---|---|
| W01 | MOSTLY FIXED | MoneyAggregate path exists, but raw API remains and DAO counts unlinked warranties under `EUR`. |
| W02 | MOSTLY FIXED | Repository path infers refund currency; direct DAO paths can bypass. |
| W03 | PARTIAL | Lifecycle event table exists, but coverage is incomplete. |
| W04 | MOSTLY FIXED | Creation/candidate paths set price history timestamps. Raw DAO and some price-change currency semantics remain weak. |
| W05 | MOSTLY FIXED | Division by zero avoided, but month math is still coarse. |
| W06 | PARTIAL | Aggregate monthly total exists; recommendations/potential savings still raw `Double`. |
| W07 | FIXED FOR ENGINE METHOD | `recordPriceChange()` is transaction-wrapped and write-barrier guarded. |
| W08 | OPEN | Negotiation outcome is still in-memory only and ignores `newPrice`. |
| W09 | PARTIAL | Monthly equivalent used in opportunity calculation, but generated script still uses raw billing amount. |
| W10 | FIXED FOR REVIEWED PATHS | GPS checked through privacy gate before access. |
| W11 | MOSTLY FIXED | Heatmap/insights use spending-only expenses. Full UI marker semantics still need UX verification. |
| W12 | PARTIAL | Map/heatmap conversions exist, but warning visibility/data quality propagation needs validation. |
| W13 | MOSTLY FIXED | Save correction returns ID and handles conflict in ViewModel. Needs DAO/repo contract tests. |
| W14 | FIXED | Legacy NLP merchant extraction appears improved. |
| W15 | PARTIAL | Location filters are detected but unsupported, not applied. |
| W16 | MOSTLY FIXED | NLP amount filtering uses `convertAsOf()` and excludes failed conversions. |
| W17 | MOSTLY FIXED WITH PRIVACY CAVEATS | Prompt redaction is wired before HTTP, but broad catch and main-source allow-all-ish constructors remain. |
| W18 | PARTIAL BUG REMAINS | `addWarranty()` normalizes timestamps; `addWarrantyIgnoreConflicts()` does not. |
| W19 | PARTIAL | AI routing is gated, but not obviously through one single effective cloud policy authority. |
| W20 | PARTIAL | Half-open/end-of-day semantics remain naming-confusing and need tests. |
| W21 | PARTIAL | Manual placeholder no longer stores product name in raw OCR, but leaves `documentType` default/unknown and timestamps possibly zero. |
| W22 | PARTIAL | Subscription validation only checks amount > 0, merchant nonblank, currency length 3. |
| W23 | FIXED IF CALLER CONTRACT HOLDS | Candidate date is caller-provided; engine does not enforce recurrence-safe date itself. |
| W24 | OPEN / DEFERRED | Candidate uniqueness remains design work. |
| W25 | PARTIAL | `MarketRateProvider` exists, but `SmartBillNegotiationEngine` still uses private static map. |
| W26 | PARTIAL / NOT FULLY AUDITED | Some half-open date handling exists; full map/NLP audit still needed. |
| W27 | FIXED FOR REVIEWED PATHS | Permission result does not fetch GPS; center-on-me does. |
| W28 | MOSTLY FIXED / NEEDS CALLSITE AUDIT | Coordinate value class exists; not every save path verified. |
| W29 | PARTIAL | Normalized location paths exist; raw/deprecated compute paths likely still callable. |
| W30 | MOSTLY FIXED | Keyset pagination exists, but legacy wrapper still materializes all pages. |
| W31 | FIXED | SearchCursor keyset pagination exists. |
| W32 | FIXED | Assistant largest query normalizes mixed currencies before comparison. |
| W33 | FIXED FOR REVIEWED RESULTS | Assistant query results carry data quality. |
| W34 | NOT FULLY VERIFIED | Not enough current-source inspection of assistant history path. |
| W35 | NOT FULLY VERIFIED | Voice recognizer lifecycle not inspected in this pass. |

---

# 3. Detailed findings

## W01 — Warranty protected value currency safety

Current evidence:

- `WarrantyTrackerRepository.getTotalProtectedValueAggregate()` uses `MoneyAggregateBuilder.fromBuckets(...)`.
- It resolves home currency through `currencySettingsRepository.resolveHomeCurrency()`.
- On failed home-currency resolution, it returns a partial `MoneyAggregate.empty(EUR)` with warning.
- `WarrantyDao.getTotalProtectedValueByCurrency()` groups by `UPPER(COALESCE(e.currency, 'EUR'))`.

Status: **mostly fixed with caveats**

Remaining issues:

1. Raw deprecated method still public:

```kotlin
getTotalProtectedValue(): Double
```

2. Unlinked warranties use `COALESCE(e.currency, 'EUR')`, which groups them under EUR even though there is no linked expense value.

3. `COUNT(*) AS txCount` counts warranty rows, not necessarily linked expense rows with value.

Risk:

- Protected value summary may show misleading EUR bucket/counts for unlinked warranties.

Suggested fix:

- Split linked and unlinked warranties:
  - linked value buckets from expenses
  - unlinked count separately
- Add static guard against production calls to raw total.

---

## W02 — Return refund currency

Current evidence:

`markAsReturned()` resolves currency:

```kotlin
refundCurrency ?: linkedExpense?.currency ?: homeCurrency
```

Status: **mostly fixed for repository path**

Caveat:

- Direct DAO updates can bypass this.

Suggested fix:

- Keep direct DAO writes restricted by convention/static guard.

---

## W03 — Warranty lifecycle event coverage

Current evidence:

- `WarrantyLifecycleEvent` entity exists.
- Event type is raw `String`.
- Comment lists only: `CREATED`, `CLAIMED`, `EXPIRED`, `EXTENDED`, `TRANSFERRED`.
- Repository writes `CREATED` and `CLAIMED`.
- `reconcileExpiredItems()` updates expired warranties/return windows but does not write lifecycle events.
- Return-window lifecycle events are not represented.

Status: **partial**

Missing events:

- `RETURN_WINDOW_CREATED`
- `RETURN_WINDOW_RETURNED`
- `WARRANTY_EXPIRED`
- `RETURN_WINDOW_EXPIRED`
- `LINKED_TO_EXPENSE`
- `UNLINKED_FROM_EXPENSE`
- `UPDATED`
- `DELETED`
- `AI_EXTRACTION_DISCARDED`
- `AI_EXTRACTION_NEEDS_REVIEW`

Suggested fix:

- Add `WarrantyLifecycleEventType` enum/sealed type.
- Create `WarrantyLifecycleCoordinator`.
- Mutation + event should be same transaction.

---

## W04 — Subscription price history recordedAt

Current evidence:

- `validateAndCreate()` inserts baseline `SubscriptionPriceHistory(recordedAt = now)`.
- `acceptCandidate()` inserts baseline with `recordedAt = now`.
- `recordPriceChange()` inserts price history with `recordedAt = timeProvider.now()`.

Status: **mostly fixed**

Remaining caveat:

- Raw DAO insert remains public.
- `recordPriceChange()` does not appear to pass currency into `SubscriptionPriceHistory`, while creation/candidate paths do. If entity default exists, this could silently default or lose currency context.

Suggested fix:

- Always persist currency on price history.
- Restrict direct price history DAO writes.

---

## W05 — Subscription usage divide by zero

Current evidence:

`monthsActive` is coerced to at least 1.

Status: **fixed for zero division**

Caveat:

- It still uses rough day/30 month approximation.

Risk low.

---

## W06 — Subscription totals mixed currency

Current evidence:

- `getTotalMonthlySubscriptionCostAggregate()` exists.
- It monthly-normalizes amount via `RecurrenceCalculator.toMonthlyAmount`.
- It groups by currency and converts via `MoneyAggregateBuilder`.

Status: **partial**

Remaining problems:

- Deprecated raw `getTotalMonthlySubscriptionCost()` is still callable.
- `SubscriptionRecommendation.potentialSavings` is raw `Double`.
- `calculatePotentialSavings()` returns raw `Double` and sums savings across subscriptions.

Suggested fix:

- Add `SubscriptionSavingsAggregate`.
- Return `MoneyAggregate` for potential savings.
- Deprecate raw savings with `DeprecationLevel.ERROR`.

---

## W07 — Price change atomicity

Current evidence:

`recordPriceChange()`:

- checks `writeBarrier`
- inserts price history
- updates subscription amount
- inside `database.withTransaction`

Status: **fixed for engine method**

Caveat:

- Validate `newAmount` finite/positive.
- Persist currency.

---

## W08 — Bill negotiation persistence

Current evidence:

`SmartBillNegotiationEngine.recordNegotiationOutcome()`:

```kotlin
val providerName = "Subscription #$subscriptionId"
recordNegotiationAttempt(
    serviceType = ServiceType.MOBILE,
    providerName = providerName,
    currentRate = 0.0,
    savings = savings,
    notes = "$outcome | $notes"
)
```

It ignores:

- `newPrice`
- actual subscription merchant
- actual service type
- actual current rate

`negotiationHistory` is in-memory `mutableListOf`.

Status: **open**

Impact:

- user result lost on process death
- successful negotiation does not update subscription amount
- no price history entry
- inaccurate metadata

Suggested fix:

- Add `NegotiationOutcomeEntity`.
- On success/partial success:
  - update subscription amount
  - insert price history
  - insert negotiation outcome
  - transactionally

---

## W09 — Bill negotiation monthly comparison

Current evidence:

Good:

- `monthlyEquivalent()` exists.
- `createNegotiationOpportunity()` uses `monthlyEquivalentPrice` for `potentialMonthlySavings`.

Still bad:

- `NegotiationOpportunity.currentPrice` stores raw amount.
- `generateNegotiationScript()` uses:

```kotlin
val currentPrice = subscription.amount
```

and writes it into monthly-language script text.

Status: **partial**

Example bug:

Annual subscription `120/year` can still be described as paying `120/month` in the script.

Suggested fix:

- Add fields:
  - `rawBillingAmount`
  - `billingFrequency`
  - `monthlyEquivalentPrice`
- Script must use `monthlyEquivalentPrice`.

---

## W10 / W27 — GPS privacy and timing

Current evidence:

`LocationResolver` checks:

```kotlin
privacyGate.check(PrivacyCapability.DEVICE_GPS_LOCATION)
```

before `locationProvider.getLastKnownLocation()`.

`SpendingMapViewModel.onPermissionResult()` only updates state.  
`onCenterOnMeRequested()` performs explicit GPS fetch.

Status: **fixed for reviewed paths**

---

## W11 / W12 / W29 — Location insights and currency

Current evidence:

`SpendingMapViewModel` filters:

```kotlin
transactionType.toDomain().isSpending
```

for heatmap and insights.

It uses `currencyConverter.convertAsOf(...)` for markers and `LocatedMoneyExpense` for normalized heatmap/insights.

Status: **mostly fixed / partial**

Remaining concerns:

- marker layer can still include non-spending transaction types; this may be okay UX, but should be explicit.
- conversion warning counts exist, but UI visibility not fully verified.
- raw/deprecated location compute APIs may still be callable.

Suggested fix:

- Ensure UI displays partial conversion warnings.
- ERROR-deprecate raw location compute APIs if normalized path is the required path.

---

## W13 — Manual correction insert conflict

Current evidence:

`SpendingMapViewModel.onSaveCorrection()` checks returned `correctionId <= 0L` and shows conflict error.

Status: **mostly fixed**

Need tests:

- duplicate correction returns conflict
- expense update failure leaves correction with visible partial state

---

## W14 — Legacy NL merchant extraction

Current evidence:

KDoc says multi-word extraction + alias lookup preserved. No contradiction found in this pass.

Status: **fixed enough for now**

---

## W15 — Legacy NLP filters parsed but ignored

Current evidence:

`QueryDataQuality(unsupportedLocations = locations != null)` exists.

Location filter is not applied; query still returns broad results with warning.

Status: **partial**

Risk:

If UI underplays warning, “spending in Paris” can show non-Paris results.

Suggested policy:

Either:

1. implement real location filtering, or
2. return unsupported for location-specific query.

---

## W16 — NL amount filter currency safety

Current evidence:

`NaturalLanguageSearchEngine` normalizes each row:

```kotlin
currencyConverter.convertAsOf(... atMillis = expense.date)
```

Failed conversions are excluded and counted.

Status: **mostly fixed**

Caveat:

- all pages are materialized before filtering.

---

## W17 — Cloud query redaction

Current evidence:

Good:

- `CloudQueryInterpretationService` calls:

```kotlin
redactor.redactText(prompt, CloudPayloadPurpose.QUERY_INTERPRETATION)
```

before:

```kotlin
buildRequestBody(redacted.text)
```

- `PreparedCloudPayload` is used for audit.

Bad:

1. Secondary constructors are in main source and use:

```kotlin
CompositePrivacyGate(emptyList(), PrivacyAuditLogger.NO_OP, ...)
```

This may be acceptable for tests but is risky in main source.

2. Broad catch:

```kotlin
catch (e: Exception)
```

No explicit `CancellationException` rethrow was found.

Status: **mostly fixed with privacy/cancellation caveats**

Suggested fix:

- Add:

```kotlin
catch (e: CancellationException) { throw e }
```

before generic catch.
- Move secondary constructors to test fixtures or annotate/restrict visibly.
- Consider marking payload as raw included only if redaction policy explicitly allowed raw.

---

## W18 — Warranty timestamps

Current evidence:

`addWarranty()` normalizes `createdAt` and `updatedAt`.

But `addWarrantyIgnoreConflicts()` directly inserts `warranty`:

```kotlin
val id = warrantyDao.insertWarrantyIgnore(warranty)
```

No copy with timestamps occurs.

Status: **partial bug remains**

Suggested fix:

Before insert:

```kotlin
val now = timeProvider.now()
val warrantyWithTimestamps = warranty.copy(
    createdAt = if (warranty.createdAt == 0L) now else warranty.createdAt,
    updatedAt = if (warranty.updatedAt == 0L) now else warranty.updatedAt
)
```

---

## W19 — Warranty AI privacy gate

Current evidence:

Warranty extraction route uses `AiCapabilityRouter.decide(AiCapability.WARRANTY_EXTRACTION, settings)`.

Status: **partial**

Good enough for route-level control, but not fully proven as single effective privacy policy.

Suggested fix:

- unify under `EffectiveCloudAiPolicyResolver`/`PreparedCloudPayload` style used by other cloud services.

---

## W20 — Warranty end-date semantics

Current evidence:

Comments claim half-open next-day boundary, but code uses:

```kotlin
TimePeriodUtils.getEndOfDay(dayStart)
```

Status: **partial / needs tests**

Concern:

Naming “endOfDay” versus “exclusive next-day start” is ambiguous.

Suggested tests:

- warranty valid through entire final day
- expires at next local day boundary
- DST boundary case

---

## W21 — Manual receipt hardcoded EUR / privacy

Current evidence:

Improved:

```kotlin
rawOcrText = "Manual warranty entry"
currency = homeCurrency
```

So raw product name is no longer stored in OCR text.

Remaining issues:

- `documentType` left default `"UNKNOWN"`, not `"MANUAL_PLACEHOLDER"`.
- `createdAt` and `updatedAt` are not visibly set on `ScannedReceipt`.
- source fields remain defaults.

Status: **partial**

Suggested fix:

```kotlin
ScannedReceipt(
    rawOcrText = "Manual warranty entry",
    documentType = "MANUAL_PLACEHOLDER",
    sourceType = "MANUAL",
    processingStatus = "READY",
    createdAt = timeProvider.now(),
    updatedAt = timeProvider.now(),
    ...
)
```

---

## W22 — Subscription validation

Current evidence:

`validateAndCreate()` checks:

```kotlin
require(request.amount > 0)
require(request.currency.isNotBlank() && request.currency.length == 3)
require(request.merchant.isNotBlank())
```

Problems:

- `Double.POSITIVE_INFINITY > 0` passes.
- `"123"` length 3 passes.
- non-ASCII or fake currency can pass.
- `acceptCandidate()` bypasses same validation.

Status: **partial**

Suggested fix:

- use `CurrencyCode` or stricter `[A-Z]{3}` validation
- require `amount.isFinite() && amount > 0`
- validate candidate path too
- validate `nextDate > 0`

---

## W23 — Candidate accept date

Current evidence:

`acceptCandidate()` accepts `nextDate` from caller.

Status: **fixed if caller contract holds**

Suggested hardening:

- require `nextDate > 0`
- optionally compute with `RecurrenceCalculator` inside engine or validate against expected recurrence.

---

## W24 — Candidate uniqueness

Status: **open/deferred**

Not re-audited deeply.

---

## W25 — MarketRateProvider

Current evidence:

`MarketRateProvider.kt` exists.

But `SmartBillNegotiationEngine` constructor does not inject it. It still has private static `marketRates`.

Also there are two different `ServiceType` enums:

- one in `MarketRateProvider.kt`
- one in `SmartBillNegotiationEngine.kt`

Status: **partial / not wired**

Suggested fix:

- delete or rename duplicate enum
- inject `MarketRateProvider`
- map subscription merchant/frequency/currency/region to provider query
- expose source/staleness/demo warning in opportunity

---

## W26 — Half-open date filters

Status: **partial / not fully audited**

Need specific date boundary tests around NLP and map filtering.

---

## W30 / W31 — NLP paging

Current evidence:

Keyset pagination exists.

But both `NaturalLanguageExpenseQueryRepositoryImpl.getExpensesBetween()` and `NaturalLanguageSearchEngine` loop pages into a mutable list.

Status:

- W31 keyset fixed
- W30 memory-bounding only partially fixed

Suggested fix:

- stream page-by-page through filters
- limit result count or return continuation cursor

---

## W32 / W33 — Assistant largest/data quality

Current evidence:

`ExecuteFinancialQueryUseCase.executeLargest()` normalizes mixed currencies to home currency using `convertAsOf(expense.date)` and excludes failed conversions.

`FinancialQueryDataQuality` is returned.

Status: **fixed for reviewed paths**

Caveat:

Some breakdown sort paths use bucket-level `convertMultiple()` or max date per group; acceptable for ranking perhaps, but should be explicitly labeled as ranking-only, not financial aggregate truth.

---

# 4. New/current issues found

## E1-NOW-001 — `addWarrantyIgnoreConflicts()` still allows `createdAt=0`

Severity: **P1**

Evidence:

`addWarranty()` normalizes timestamps, but `addWarrantyIgnoreConflicts()` directly inserts the passed object.

Impact:

- AI/import-created warranties can have sentinel timestamps.
- Timeline/debug/retention can be wrong.

Fix:

Normalize timestamps before `insertWarrantyIgnore`.

Tests:

- `addWarrantyIgnoreConflicts_sets_createdAt_updatedAt_when_zero`

---

## E1-NOW-002 — Cloud query cancellation is swallowed

Severity: **P1**

Evidence:

`CloudQueryInterpretationService` has `catch (e: Exception)` and no `CancellationException` handling.

Impact:

- coroutine cancellation can be converted into unsupported result
- workers/scope cancellation semantics weakened

Fix:

```kotlin
catch (e: CancellationException) {
    throw e
}
```

Tests:

- `cloud_query_interpretation_rethrows_cancellation`

---

## E1-NOW-003 — Cloud query test constructors live in main source with empty privacy gate

Severity: **P1 privacy/architecture**

Evidence:

Secondary constructors use `CompositePrivacyGate(emptyList(), NO_OP, ...)`.

Impact:

- future production/manual construction can bypass real privacy bindings.

Fix:

- move to test source, or
- make constructor `internal @VisibleForTesting`, or
- require explicit `PrivacyGate` in all constructors.

Tests/static guard:

- `no_allow_all_privacy_gate_constructor_in_main_source`

---

## E1-NOW-004 — Subscription price change lacks strong validation

Severity: **P1**

Evidence:

`recordPriceChange(subscriptionId, newAmount)` checks write barrier but not:

- finite
- positive
- currency consistency
- actual subscription existence before price history decision

Impact:

- NaN/infinity/negative/zero can enter price history and recurring amount.

Fix:

- require `newAmount.isFinite() && newAmount > 0`
- load subscription first
- write currency to price history

Tests:

- `recordPriceChange_rejects_nan_infinity_negative_zero`
- `recordPriceChange_preserves_currency`

---

## E1-NOW-005 — Low-confidence warranty review path is logically unreachable

Severity: **P1**

Evidence:

`MIN_CLOUD_CONFIDENCE = 0.5f`; below threshold returns null. Later:

```kotlin
val lowConfidence = confidence <= 0.3f
```

But any confidence <= 0.3 already returned null.

Impact:

- low-confidence but useful extraction cannot create review draft.

Fix:

Three-band model:

- >= 0.75 auto-create
- 0.3–0.75 create needs-review draft
- < 0.3 discard with diagnostic

Tests:

- `cloud_warranty_confidence_0_4_creates_review_draft`
- `cloud_warranty_confidence_0_1_discards_with_diagnostic`

---

## E1-NOW-006 — Manual placeholder receipt lacks explicit document type/timestamps

Severity: **P1 privacy/lifecycle**

Evidence:

`createManualPlaceholderReceipt()` does not set:

- `documentType = MANUAL_PLACEHOLDER`
- `sourceType = MANUAL`
- `createdAt`
- `updatedAt`

Impact:

- manual placeholder can look like unknown real receipt
- timestamp sentinel can remain

Fix:

Set all fields explicitly.

Tests:

- `manual_warranty_placeholder_has_documentType_manual_placeholder`
- `manual_warranty_placeholder_sets_timestamps`

---

## E1-NOW-007 — Market-rate provider interface is dead/not wired

Severity: **P1 architecture**

Evidence:

`MarketRateProvider` exists. `SmartBillNegotiationEngine` does not inject it and has its own static map.

Impact:

- staleness check is meaningless because `lastUpdated = process start now`
- static/demo rates can appear real
- provider architecture not actually active

Fix:

- inject `MarketRateProvider`
- remove static private map or move it into `StaticMarketRateProvider`
- expose source/demo/stale flags

Tests:

- `smart_bill_engine_uses_marketRateProvider`
- `static_provider_marks_demo_source`
- `market_rate_staleness_survives_process_restart`

---

## E1-NOW-008 — NLP location query still returns broad results

Severity: **P2/P1 UX depending on UI**

Evidence:

`unsupportedLocations = locations != null`, but results still run.

Impact:

- user can ask location-specific query and receive non-location-filtered results.

Fix options:

- implement location filtering, or
- return unsupported if locations present.

Tests:

- `nl_location_query_returns_unsupported_when_location_filter_not_supported`

---

# 5. Files changed

None.

This was a static evaluation only.

---

# 6. Tests added/updated

None.

Recommended tests are listed under each issue.

---

# 7. Docs updated

None.

Recommended docs update:

- update `ENGINE_ISSUES_MASTER_TRACKER.md`
- add an Engine 1 current audit doc:
  - `docs/analyses and debug master/debugging/engine1-current-re-audit-2026-06-02.md`

---

# 8. Affected pipelines and expected impact

| Fix area | Pipelines affected | Risk |
|---|---|---|
| Warranty write barrier/timestamps/events | Receipt, backup/restore, AI warranty | Medium |
| Subscription validation | Recurring, budget, forecast, dashboard | High |
| Negotiation persistence/provider | Subscription UI, recurring amount, price history | Medium-high |
| Cloud query cancellation/privacy | AI assistant, privacy, workers | High |
| NLP location filtering | Natural language search, assistant UX | Medium |
| Location warnings | Map/insights | Medium |
| Raw/deprecated API guard | Multiple future call sites | Medium |

---

# 9. Static checks performed

I statically checked:

- write barrier presence in warranty and subscription reviewed methods
- warranty aggregate path and DAO query shape
- warranty lifecycle event writes
- manual placeholder receipt construction
- subscription creation/candidate/price-change paths
- subscription total aggregate and raw savings paths
- negotiation provider wiring and script generation
- location GPS privacy gating
- map spending-only and conversion logic
- NLP location warning and currency-aware amount filtering
- assistant largest query currency normalization
- cloud query redaction before HTTP
- broad cloud query exception handling

Not checked fully:

- assistant history persistence (`W34`)
- speech recognizer lifecycle (`W35`)
- all raw location engines
- all UI rendering of data-quality warnings
- all tests
- compile/Hilt/Room graph

---

# 10. Known compile risks

No code changed, so no new compile risk from this audit.

Future fix risks:

- injecting `MarketRateProvider` may need Hilt binding cleanup
- adding `NegotiationOutcomeEntity` needs Room migration
- turning deprecated APIs to `ERROR` may reveal production callers
- replacing cloud constructors may require test refactors
- stricter currency validation may expose dirty legacy rows

---

# 11. Recommended safe fix order

## PR1 — Warranty/subscription low-risk hardening

Closes:

- E1-NOW-001
- E1-NOW-004
- E1-NOW-006
- parts of W18/W21/W22

Files:

- `WarrantyTrackerRepository.kt`
- `SubscriptionManagerEngine.kt`
- tests

Steps:

1. Normalize timestamps in `addWarrantyIgnoreConflicts`.
2. Set manual placeholder `documentType`, `sourceType`, timestamps.
3. Validate subscription amounts with `isFinite() && > 0`.
4. Validate currency with strict code policy.
5. Validate `acceptCandidate`.

No schema migration required.

---

## PR2 — Cloud query cancellation/privacy constructors

Closes:

- E1-NOW-002
- E1-NOW-003
- part of W17

Files:

- `CloudQueryInterpretationService.kt`
- cloud query tests

Steps:

1. Rethrow `CancellationException`.
2. Remove or restrict main-source allow-all test constructors.
3. Add static guard for no empty CompositePrivacyGate in main cloud services.

No schema migration.

---

## PR3 — Warranty lifecycle completeness

Closes:

- W03
- E1-NOW-005

Files:

- `WarrantyTrackerRepository.kt`
- possibly new `WarrantyLifecycleCoordinator.kt`
- tests

Steps:

1. Add typed lifecycle event constants/enum.
2. Write events for return-window create/returned/expired.
3. Write events during `reconcileExpiredItems`.
4. Add review/diagnostic path for low-confidence extraction.

May not require migration if using same string table.

---

## PR4 — Bill negotiation rewrite

Closes:

- W08
- W09
- W25
- E1-NOW-007

Files:

- `SmartBillNegotiationEngine.kt`
- `MarketRateProvider.kt`
- new entity/DAO if persisting outcomes
- Hilt modules
- Room migration

Steps:

1. Inject `MarketRateProvider`.
2. Remove static rates from engine.
3. Add monthly-equivalent fields to opportunity and script.
4. Persist negotiation outcomes.
5. On success, update subscription price and price history atomically.

Requires migration if adding outcome table.

---

## PR5 — NLP/location UX hardening

Closes:

- W15
- W30 partial
- E1-NOW-008

Files:

- `NaturalLanguageSearchEngine.kt`
- `NaturalLanguageExpenseQueryRepositoryImpl.kt`
- UI query result warning display

Steps:

1. Return unsupported for location query until real filter exists.
2. Avoid unbounded page materialization or add hard limit/cursor.
3. Make warnings visible in UI.

No migration.

---

# 12. Human validation commands

Suggested only; I did not run them.

```bash
./gradlew :app:assembleDebug --stacktrace
./gradlew :app:testDebugUnitTest --stacktrace
./gradlew :app:check --stacktrace
```

If any Room/entity changes are added later:

```bash
./gradlew :app:connectedDebugAndroidTest --stacktrace
```

If Hilt/DI changes are made, especially `MarketRateProvider` injection:

```bash
./gradlew :app:assembleDebug --stacktrace
```

---

# 13. Final conclusion

Engine 1 is **not dirty**, but it is **not fully clean**.

The strongest areas now:

- location GPS gating
- spending-only map insights
- NLP amount currency conversion
- assistant largest query
- write barrier coverage in many warranty/subscription paths
- cloud redaction before HTTP

The weakest areas now:

1. bill negotiation is still demo/in-memory
2. subscription validation is still too weak
3. warranty lifecycle is incomplete
4. cloud query cancellation/privacy constructors need hardening
5. manual warranty placeholder still lacks lifecycle/document metadata
6. deprecated/raw APIs still allow future regressions

My recommendation: do **PR1 first**. It is low-risk, no migration, and closes real correctness holes.