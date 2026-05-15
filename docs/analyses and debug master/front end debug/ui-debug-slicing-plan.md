# Frontend/UI Debug Slicing Plan — Cost-agregator @ `18d442c`

## Key context read

Sources:
- Commit: https://github.com/panospao7/Cost-agregator/commit/18d442c5abb42a8997fd8b6bd04978776c5f6596
- Architecture: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/architecture/ARCHITECTURE.md
- Segments: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/architecture/CODEBASE_SEGMENTS.md
- UI map: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/reference/COMPREHENSIVE_UI_MAP.md
- UI components: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/reference/UI_COMPONENT_LIBRARY.md
- ViewModel injection map: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/reference/VIEWMODEL_INJECTION_MAP.md
- Test strategy: https://github.com/panospao7/Cost-agregator/blob/18d442c5abb42a8997fd8b6bd04978776c5f6596/docs/testing/MASTER_TESTING_STRATEGY.md
- Gradle/CI: `app/build.gradle.kts`, `.github/workflows/ci.yml`

## Important observation

The latest commit updates docs only. It does not itself change production UI code. So the correct debugging approach is:

1. Validate docs/source inventory.
2. Compile app + tests.
3. Debug UI by small architecture slices, not by “run everything and drown in logs”.

Current UI shape:
- Jetpack Compose + Material 3.
- Custom navigation via `NavigationDestination` / `NavigationController`, not NavHost.
- 6 main tabs.
- Overlay screens/sheets.
- 23–24 config-driven feature screens depending on doc section.
- 38 ViewModels.
- 59 UI components according to the newest component library / comprehensive map.
- High-risk ViewModels: `HomeViewModel`, `ReviewViewModel`, `AnalyticsViewModel`, `ReceiptScanViewModel`.

Also: some reference docs still appear to contain older component counts in sections. Treat generated counts as advisory until source inventory is verified.

---

# Debug slice strategy

## Slice 0 — Build/test infrastructure baseline

**Owns**
- Gradle config
- KSP/Hilt/Room schema generation
- CI guards
- unit vs Robolectric vs instrumented test split

**Files**
- `app/build.gradle.kts`
- `.github/workflows/ci.yml`
- `app/src/test/...`
- `app/src/androidTest/...`
- `docs/testing/*`

**Why first**
If tests do not compile after constructor refactors, UI debugging is noisy. The May 12 testing strategy says the suite has many compile failures from signature changes, so this is the first gate.

**Commands**
```bash
./gradlew :app:compileDebugKotlin --stacktrace
./gradlew :app:compileDebugUnitTestKotlin --stacktrace
./gradlew :app:verifyRoomSchemaSnapshots -PstrictRoomSchemas=true --stacktrace
./gradlew :app:verifyNoIgnoredGrowth -PmaxIgnoredTests=310 --stacktrace
```

**Debug questions**
- Are failures production compile, test compile, KSP/Hilt, or schema?
- Are ViewModel tests broken due constructor signatures?
- Are test fixtures centralized enough, or each test hand-builds dependencies?

---

## Slice 1 — App shell and navigation core

**Owns**
- App root
- deep links
- tab switching
- back stack
- overlays
- FAB menu
- feature routing

**Files**
- `ui/MainActivity.kt`
- `ui/MainViewModel.kt`
- `ui/navigation/*`
- `ui/integration/FeatureIntegration.kt`
- `ui/components/AppNavigationBar.kt`
- `ui/components/AppFabMenu.kt`

**Tests**
- `app/src/test/.../ui/navigation/NavigationRouteContractTest.kt`
- `MainViewModelStressTest.kt`

**Commands**
```bash
./gradlew :app:testDebugUnitTest --tests "*NavigationRouteContractTest" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*MainViewModelStressTest" --stacktrace
```

**Failure classes**
- destination missing from sealed router
- route added in `FeatureConfig` but not handled
- deep link points to stale destination
- overlay back dismiss broken
- selected tab/back stack mismatch

**Expected invariant**
Every `NavigationDestination` has exactly one legal render path and back behavior.

---

## Slice 2 — Theme + global shared UI primitives

**Owns**
- global Compose theme
- spacing/dimensions
- loading/error/empty states
- form primitives
- accessibility semantics
- haptic/modifier/color utilities

**Files**
- `ui/theme/*`
- `ui/components/common/*`
- `ui/components/emptystate/*`
- `ui/components/feature/FormComponents.kt`
- `ui/util/*`

**Tests**
- `ContextualActionRegistryTest.kt`
- Add Compose/Robolectric tests for global components if absent.

**Commands**
```bash
./gradlew :app:testDebugUnitTest --tests "*ContextualActionRegistryTest" --stacktrace
```

**Why early**
`EmptyState`, `ErrorState`, and `LoadingSkeleton` are global blast-radius components. If these are broken, many screen failures are symptoms.

**Expected invariant**
Global components render with:
- no crash
- correct text
- correct action callback
- test tags or semantics where needed
- no hidden ViewModel/domain dependency

---

## Slice 3 — Privacy/security UI

**Owns**
- privacy-denied display
- `PrivacyBlockedCard`
- privacy settings
- backup/restore denied states
- AI/cloud settings visibility

**Files**
- `ui/components/PrivacyBlockedCard.kt`
- `ui/screens/privacysettings/*`
- `ui/screens/backup/*`
- `ui/screens/aisettings/*`

**Segments**
- Segment 18: Export & Backup
- Segment 20: AI Platform
- Segment 28: Security & API Key Management

**Tests**
- `PrivacyStorageContractTest`
- `BackupRestoreViewModel` tests if present
- add targeted UI render tests for denied states

**Commands**
```bash
./gradlew :app:testDebugUnitTest --tests "*Privacy*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Backup*" --stacktrace
```

**Expected invariant**
If a privacy gate denies or fail-closes, the UI must show a consistent blocked state and must not offer an action that will later silently fail.

---

## Slice 4 — Home/dashboard composition

**Owns**
- dashboard screen
- period navigation
- totals cards
- forecast/weather/runway/stress widgets
- recommendations
- financial health widgets
- feature menu entry points

**Files**
- `ui/screens/home/*`
- `ui/components/TotalsDashboardCard.kt`
- `BudgetBlockPartyCard.kt`
- `FinancialWeatherCard.kt`
- `FinancialRunwayCard.kt`
- `FinancialStressForecastCard.kt`
- `MonteCarloForecastCard.kt`
- `components/health/*`
- `RecommendationCard.kt`
- `PlaceInsightCard.kt`

**Segments**
- Segment 10: Dashboard Totals & Widgets
- Segment 1: Forecasting & Runway
- Segment 2: Budget Management
- Segment 8: Analytics & Insights
- Segment 19: Location Enrichment
- Segment 20: AI Platform

**Risk**
Very high. `HomeViewModel` has ~19 dependencies.

**Tests**
```bash
./gradlew :app:testDebugUnitTest --tests "*HomeViewModel*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Dashboard*" --stacktrace
```

**Expected invariant**
For a fixed seed:
- total = sum of displayed breakdown
- currency conversion is home-currency safe
- no planned+actual double count
- widgets degrade gracefully when data quality is partial

---

## Slice 5 — Transactions + manual add + filters

**Owns**
- transaction list
- filters
- edit/delete/category/location flows
- `AddExpenseSheet`
- transfer direction badge
- clipboard amount parser

**Files**
- `ui/screens/transactions/*`
- `ui/screens/addexpense/*`
- `ui/components/TransactionFilterSheet.kt`
- `ui/components/TransferDirectionBadge.kt`
- `ui/util/ClipboardAmountParser.kt`

**Segments**
- Segment 9: Core Expense Management
- Segment 16: Currency & Exchange
- Segment 19: Location Enrichment

**Tests**
```bash
./gradlew :app:testDebugUnitTest --tests "*TransactionsViewModel*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*AddExpenseViewModel*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*ClipboardAmountParser*" --stacktrace
```

**Expected invariant**
Manual add must route through the legal lifecycle path and update transaction list/dashboard without direct DAO bypass.

---

## Slice 6 — Review queue + AI assist cards

**Owns**
- pending review UI
- approve/reject/edit
- category assist
- duplicate assist
- receipt assist from review surface

**Files**
- `ui/screens/review/*`
- `ui/components/ai/CategoryAssistCard.kt`
- `DedupeAssistCard.kt`
- `ReceiptAssistCard.kt`

**Segments**
- Segment 3: Notification Capture, Parsing & Review
- Segment 20: AI Platform
- Segment 4: Receipt lifecycle touchpoints

**Risk**
High. `ReviewViewModel` has 12+ dependencies and crosses notification, receipt, transaction, and AI paths.

**Tests**
```bash
./gradlew :app:testDebugUnitTest --tests "*ReviewViewModel*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*PendingReview*" --stacktrace
```

**Expected invariant**
Approve must produce exactly one transaction lifecycle event and no duplicate expense.

---

## Slice 7 — Receipt scan + OCR + item categorization UI

**Owns**
- receipt scan screen
- OCR result display
- receipt item breakdown
- AI item categorization
- receipt-expense linking

**Files**
- `ui/screens/receiptscan/*`
- `ui/components/ai/ReceiptItemBreakdownCard.kt`
- `ui/components/ai/ReceiptAssistCard.kt`

**Segments**
- Segment 4: Receipt Scanning/OCR/Lifecycle
- Segment 5: AI Receipt Item Categorization
- Segment 38: Receipt Matching

**Risk**
Very high. `ReceiptScanViewModel` has 16+ dependencies.

**Tests**
```bash
./gradlew :app:testDebugUnitTest --tests "*ReceiptScanViewModel*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Receipt*" --stacktrace
```

**Expected invariant**
Receipt processing must route through `ReceiptLifecycleCoordinator`; linking should be atomic and evented.

---

## Slice 8 — Budget, forecasting, and cashflow UI

**Owns**
- budget screen
- add/edit budget dialog
- budget forecasting screen
- cashflow calendar
- spending pace gauge
- forecast timeline
- period blocks/grid

**Files**
- `ui/screens/budget/*`
- `ui/screens/cashflow/*`
- `ui/components/SpendingPaceGauge.kt`
- `ForecastTimeline.kt`
- `PeriodGridView.kt`
- `PeriodBlock.kt`
- `PeriodNavigationBar.kt`

**Segments**
- Segment 2: Budget Management
- Segment 1: Forecasting & Runway
- Segment 13: Cash Flow Planning
- Segment 16: Currency & Exchange

**Tests**
```bash
./gradlew :app:testDebugUnitTest --tests "*BudgetViewModel*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*BudgetForecasting*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*CashFlow*" --stacktrace
```

**Expected invariant**
Budget spent values must use the same conversion basis as dashboard totals.

---

## Slice 9 — Analytics + advanced analytics

**Owns**
- analytics tab
- charts
- category donut
- trend chart
- advanced analytics feature
- personality/statistical visualizations

**Files**
- `ui/screens/analytics/*`
- `ui/components/CategoryDonutChart.kt`
- `SpendingTrendChart.kt`
- `ChartMarker.kt`
- `components/analytics/*`

**Segments**
- Segment 8: Analytics & Insights
- Segment 16: Currency & Exchange
- Segment 19: Location Enrichment
- Segment 22: Lifestyle Inflation where connected

**Risk**
High. `AnalyticsViewModel` has ~15 dependencies.

**Tests**
```bash
./gradlew :app:testDebugUnitTest --tests "*AnalyticsViewModel*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*AdvancedAnalytics*" --stacktrace
```

**Expected invariant**
Analytics totals, category percentages, trend totals, and dashboard totals should agree for the same period.

---

## Slice 10 — Spending map + location UI

**Owns**
- map tab
- OSMDroid rendering
- location permission
- location picker/search
- correction sheets
- nearby suggestions

**Files**
- `ui/screens/map/*`
- `ui/components/LocationPermissionDialog.kt`
- `LocationSearchPicker.kt`
- `LocationCorrectionSheet.kt`
- `NearbyShopSuggestionCard.kt`

**Segments**
- Segment 19: Location Enrichment

**Tests**
```bash
./gradlew :app:testDebugUnitTest --tests "*SpendingMapViewModel*" --stacktrace
```

**Instrumented/manual likely needed**
OSMDroid and Android location permission behavior should be smoke-tested on emulator/device.

**Expected invariant**
No automatic GPS fetch on ViewModel init; location access only after explicit user action.

---

## Slice 11 — AI assistant + AI settings

**Owns**
- assistant bottom sheet
- chat bubbles
- AI result cards
- AI settings screen
- query interpretation surface

**Files**
- `ui/screens/assistant/*`
- `ui/screens/aisettings/*`
- `ui/components/ai/*`

**Segments**
- Segment 20: AI Platform, Assistant & Follow-Through

**Tests**
```bash
./gradlew :app:testDebugUnitTest --tests "*Assistant*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*AiSettings*" --stacktrace
```

**Expected invariant**
Cloud AI disabled/fail-closed state must be visible and must not call cloud paths.

---

## Slice 12 — Feature screens, low-dependency group

Debug these after shell/shared components are stable.

**Screens**
- Warranty tracker
- Subscription management
- Bank connections
- Investment portfolio
- Carbon footprint
- Receipt matching
- Assistant if treated as low-dep

**Why grouped**
Mostly 1–2 injected dependencies or direct DAO exceptions.

**Commands**
```bash
./gradlew :app:testDebugUnitTest --tests "*Warranty*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Subscription*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Bank*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Investment*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Carbon*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*ReceiptMatching*" --stacktrace
```

**Expected invariant**
Each screen should load, show empty/error/data states, and not leak direct DAO mutation beyond allowed exceptions.

---

## Slice 13 — Feature screens, medium-complexity business flows

**Screens**
- Savings goals
- Spending challenges
- Natural language search
- Currency management
- Export options
- Shared expense groups
- Visual split editor/templates
- Bill reminders
- Tax configuration
- Price protection
- Bill negotiation
- Lifestyle inflation

**Segments**
Maps to segments 14–37 depending feature.

**Commands**
```bash
./gradlew :app:testDebugUnitTest --tests "*Savings*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Challenge*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*NaturalLanguage*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Currency*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Export*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Groups*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Split*" --stacktrace
./gradlew :app:testDebugUnitTest --tests "*Reminder*" --stacktrace
```

**Expected invariant**
Each feature’s ViewModel state machine should be testable without rendering the whole app.

---

## Slice 14 — Debug/diagnostics UI

**Owns**
- debug screen
- categorization debug
- debug viewer
- runtime diagnostics

**Files**
- `ui/screens/debug/*`

**Segments**
- Segment 29: Debug & Diagnostics

**Tests**
```bash
./gradlew :app:testDebugUnitTest --tests "*Debug*" --stacktrace
```

**Expected invariant**
Debug-only tools must be gated and must not create production mutation paths.

---

# Recommended execution order

1. **Slice 0** — compile/test infrastructure.
2. **Slice 1** — shell/navigation.
3. **Slice 2** — shared components/theme.
4. **Slice 3** — privacy/security because it was recently touched conceptually.
5. **Low-dependency features** from Slice 12 to get fast green wins.
6. **High-complexity core screens**:
   - Home
   - Analytics
   - Review
   - Receipt scan
7. **Cross-pipeline golden scenarios** only after ViewModel-level tests are stable.

---

# Test infrastructure direction

Use four test levels:

## 1. Static/contract guards

Already aligned with project direction:
- lifecycle bypass
- raw money aggregate guard
- direct time calls
- Room schema verification
- ignored test count

Run on every PR.

## 2. ViewModel JVM/Robolectric tests

Primary UI debugging layer.

Best for:
- state transitions
- error/loading/empty states
- navigation events
- no Android device needed

Each ViewModel should have:
- initial state test
- loading success test
- loading failure test
- main action test
- privacy-denied test if applicable
- currency/data-quality test if financial

## 3. Compose component tests

Use for:
- global components
- `PrivacyBlockedCard`
- empty/error/loading states
- high-value widgets

Do not screenshot-test everything. Assert semantics/text/actions.

## 4. Golden scenario tests

Add only after compile stabilizes.

Highest priority UI-connected golden tests:
1. Manual add expense updates transaction list + dashboard.
2. Privacy blocked backup shows blocked UI and no export action.
3. Dashboard total equals analytics total for same period.
4. Receipt scan creates/links receipt and visible result state updates.
5. Review approve updates dashboard and removes pending item.
6. Multi-currency budget/dashboard/analytics totals agree.
7. Restore mode blocks UI-triggered mutations.

---

# Per-slice debugging protocol

For each slice:

1. **Run only that slice’s tests.**
2. **Classify failure**
   - compile error
   - Hilt/KSP injection error
   - stale test constructor
   - state assertion failure
   - real production bug
   - obsolete behavior after refactor
3. **Fix fixture first**, not every test individually.
4. **Assert behavior**, not DAO calls.
5. **Add one golden or contract test** only if a cross-boundary bug is found.
6. **Update docs only after source/test truth is verified.**

---

# First concrete next step

Start with:

```bash
./gradlew :app:compileDebugKotlin :app:compileDebugUnitTestKotlin --stacktrace
```

Then paste the first 100–200 lines around the first failure.

I would debug from there in this order:
1. test constructor/signature failures,
2. Hilt missing bindings,
3. navigation route mismatches,
4. privacy/backup UI regressions,
5. high-complexity ViewModels.