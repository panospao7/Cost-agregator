# Impact map: briefing-grounded-signals (inc 1 + inc 2 framing)

Author: impact-mapper (read-only). Persisted by the orchestrator. Date 2026-10-16. `M/` = `app/src/main/java/com/yourname/expensetracker/`; `T/` = `app/src/test/java/com/yourname/expensetracker/`. ~45 tool calls.
Not read: full CODEBASE_SEGMENTS + LEGAL_PATHS sections (relied on domain-spec citations F12 / `:534-552`); FG guard text; `MoneyDisplayUi`; `AnomalyAlertRepository` and `pendingCount` producers; loan-installments and money-correctness plans in detail.

## 0. Findings the spec and UX must absorb
**Spec defects (verified)**
1. **INV-B3 / "no fact lost" break for some budget inputs.** `GetMonteCarloBudgetImpactUseCase` (`M/domain/usecase/budget/`) tiers by overrun% OR probability: `overrunPercent >= 0.05 || p >= 0.25 → MEDIUM`, `>= 0.15 || p >= 0.50 → HIGH`, `>= 0.30 || p >= 0.75 → CRITICAL` → MEDIUM/HIGH possible with p < 0.25; MRU `determinePrimaryAction` (`:582`) still emits `AdjustBudget` + CTA, but `buildTopReasons` (`:529`), chip (`MoneyRadarWidget.kt:272`) and spec §2/INV-B19 require p ≥ 0.25 → CTA with no budget fact; if only signal, template empty while CTA exists. **Rec:** fact follows tier (no p gate), percent still shown; reconcile chip/CTA.
2. **Per-component availability is not a thin wrapper.** `computeAvailable` (`:167`) runs three `async` children in one `coroutineScope` (one throwing cancels siblings); reads home currency first (`:169-171`); `getDueBills` fuses listing with FX conversion (`:240-246`). Needs: patterns + alerts read first without H dependency; per-child failure capture rethrowing `CancellationException` (FG-10) or `supervisorScope`; listing separated from conversion while `UpcomingBill.amount` and `MoneyRadarAction.ViewBills(List<UpcomingBill>)` stay for score/CTA. Parity fixture: invalid H, bill conversion, budget conversion, spending excluded, MC/impact error, income excluded.
3. **`MoneyRadarData` has 9 test ctor sites** (named args incl. `topReasons`); U6 resolved: consumers `MoneyRadarWidget.kt:105,214` + fixtures `DashboardCurrencyIntegrationTest:117`, `CrossGroupIntegrationTest:277`, `DashboardWidgetConsistencyTest:115`, `FinancialRunwayNoBurnTest:113`, `DashboardBudgetProvenanceChainTest:234`, `ComputeDashboardWidgetsUseCasePaceWiringTest:508`, `...DaysRemainingBoundaryTest:97`, `ComputeDashboardWidgetsInsightMoMTest:98`, `BlockPartyDstAlignmentTest:139`. **Rec:** keep `topReasons` + `DomainTextKeys.COMPUTE_MONEY_RADAR_REASON_*` through inc 1, unrendered; remove later; add `signals` (default = planner call; default avoids fixture churn).
4. **Greek missing for much of the card:** `values-el` has none of `home_ai_briefing_title`, `money_radar_reason_*`, `widget_insight_*`, `home_ai_briefing_unavailable`; only `money_radar_unavailable` (`:33`) → new EL key for `home_ai_briefing_title`. Insight strings EN `%1$.0f` no currency (`strings.xml:950-952`) → UX "reuse Insight strings" can't hold under INV-B4 / LEGAL_PATHS `:550`; rewrite with currency-formatted amount.
5. **More `insight`/`NaturalLanguageInsight` touchpoints:** registry `DashboardWidgetRegistry.kt:23,58,82`; CDW class `:128`, call `:336-342`, `assembleWidgets` `:1352`; `HomeScreen.kt:527-582` (renders the AI briefing; replaced by the card); `UiTextExtensions.kt:181-183`; `DashboardTextKeys.kt:6-8`; stale local `defaultConfigIds` in `T/ui/screens/home/DashboardWidgetMetaContractTest.kt:18`. `AnalyticsScreen.NaturalLanguageInsightBento` unrelated — don't touch.
6. **Gap verified:** `buildNaturalLanguageInsight` returns null in the middle band (`:1398`); falls through to today-spent only when pace absent, never in the gap → OD-B6 is a pure removal.
7. **Backup (U4):** `AndroidManifest.xml:32` `allowBackup="false"`; only `dashboard_prefs`/`layout_config` reference = `DashboardRepository` (`:22,43,102`); no in-app export/restore. Read-filter still covers downgrade + re-upgrade. Persistence NONE.
8. **Hidden Radar:** `HomeViewModel.kt:378-390` filters by `isVisible` before combine → hidden Radar absent from `widgets`; selector needs no visibility input. Edit mode shows hidden widgets → edit-mode card rendering (live narrative or not) is a UX detail in neither doc (N4).

**Spec vs UX conflicts**
| # | Topic | Spec | UX | Evidence / rec |
|---|---|---|---|---|
| C1 | Bills sentence amount | INV-B4 no amount; `DueBillSignal` none | `radar_fact_bills_total` "(EUR 61.98)" | mixed-currency total = what INV-B4 forbids → **spec wins; drop total** |
| C2 | Pace sentence amount | MONTH_PACE difference + H code | "no amounts in pace sentence" | **spec wins** (old insight had an amount); update UX copy |
| C3 | Currency display | symbol via formatter ("€200.01", AE-B3) | "EUR 61.98" code | `CurrencyFormatter.formatMoney` uses `NumberFormat.getCurrencyInstance(Locale.getDefault())` → **formatter (symbol)**; UX examples change |
| C4 | Pace tap | ANALYTICS_MONTH (U3) | `onNavigateToTransactions(month)` | precedent: SpendingTrend → transactions month (`HomeScreen.kt:513-519`); Analytics("month") only for budget (`:835`) → **Transactions month** |
| C5 | Anomaly tap | 30-day window (OD-B2) | month transactions, honest label (UX-Q4) | spec better but shipped-behaviour change (`HomeScreen.kt:828`); `TransactionFilter` takes `dateRange` (`:829`) → owner |
| C6 | Unchecked sentence | TRANSACTIONS_MONTH tap | non-tappable (UX-Q6) | no no-rate filter exists → **non-tappable in inc 1** |
| C7 | Unchecked ordering | rank 7, may be truncated | pinned 3rd line (UX-Q3) | direct conflict; pinning breaks "Radar facts always fit" → owner |
| C8 | New user | NEUTRAL_CLEAR | "Getting started"/NoData (UX-Q7) | spec has no empty-ledger signal; UX wins → add digest field |
| C9 | Radar-unavailable layout | NEUTRAL_LIMITED line | status line + neutral badge + per-component sentences | compatible; merge one definition |
| C10 | Pending sentence | PENDING_REVIEW fact | same | `PendingReviewAlert` (`REVIEW_ALERT`, order 13) shows same count; redundancy accepted, note |

## 1. Production files (Segment 10 Dashboard Totals & Widgets + UI)
**Domain `M/domain/usecase/dashboard/`**
- `ComputeMoneyRadarUseCase.kt` (T3): add `signals: RadarSignals`, `RadarComponent<T>`, `RadarUnavailableReason`; restructure `computeAvailable`/`getDueBills`/`getBudgetRisk` (finding 2); score/level/CTA/legacy fields identical (INV-B7); keep `topReasons`; bounded Timber codes only (U5: no counter sink).
- NEW `BuildBriefingDigestUseCase.kt` (+ `BriefingFact`, `BriefingDigest`, `FactType`, `SourceTag`, `DigestTapTarget` adjacent); pure, no repository.
- NEW pace classifier — created by `home-insight-gap`; digest calls it; `buildNaturalLanguageInsight` becomes a thin caller.
- `ComputeDashboardWidgetsUseCase.kt` (> 1,400 lines, hot): distinct `monthUncheckedCount` hooked at `:491-496` (`Excluded -> null` branch; `purchases` `:407` already PURCHASE && !isNotMine && current period); count distinct Excluded rows; `null` (not 0) when normalized input Unavailable (INV-B6); do NOT reuse `dataQuality.excludedTransactionCount` (`:498-506`, `:722`, `:763`, `:1311`). Digest after `computeMoneyRadarUseCase.compute()` (`:348`); `DashboardWidget.MoneyRadar(data)` (`:232`, `:1262`) carries digest. Remove `NaturalLanguageInsight` (`:128`, `:336-342`, `:1352`, `:1375-1408`) in retirement slice only.
- `DashboardWidgetRegistry.kt`: remove `INSIGHT` (`:23,58,82`); add `retiredIds`.
- `M/domain/text/DashboardTextKeys.kt` (`:6-8`), `M/ui/components/UiTextExtensions.kt` (`:181-183`): remove insight keys in retirement slice.
**Data** `M/data/repository/DashboardRepository.kt`: filter `retiredIds` in `getDashboardConfigInternal` (`:42-79`) before `nextOrder` (`:65`) and `savedIds` comparison; no write-on-read; JSON shape unchanged.
**UI**
- `M/ui/components/dashboard/MoneyRadarWidget.kt` rewrite: hard-coded EN ("Money Radar", "All Clear", "Attention Needed", "Action Required", "Unavailable", "Bills", "Alerts", RiskTier labels, "View N Upcoming Bill(s)"); remove whole-card `contentDescription` (`:107-114`); `horizontalScroll` chips (`:245`) → `FlowRow`; locale `uppercase()` (`:166`); unavailable branch (`:49-66`) → header + status line + facts.
- NEW renderer (facts → strings, plurals, dates, money) in `M/ui/components/dashboard/` — **OD-B10 resolved: UI renderer** (`UiText.MessageKey` has no plural quantity, F11; `pluralStringResource`/`Resources.getQuantityString`).
- NEW pure narrative selector in `M/ui/screens/home/`; wire via `radarNarrative` in the `HomeViewModel` combine (`:335-404`; `DashboardState` already carries `aiBriefing` `:95,399` and `widgets`) — rec combine for testability.
- `HomeScreen.kt`: MoneyRadar branch `:819-841` (narrative + taps, MEDIUM tap `:833-836`, anomaly tap `:827-831`, pending tap = `onNavigateToReview` — U2 resolved `:80,482`); delete `NaturalLanguageInsight` branch `:527-582`; move AI text, `runtimeStatusMessage`, `diagnostics` display into the card (`diagnostics` needs privacy check, N3).
- `values/strings.xml`, `values-el/strings.xml`: ~45 `radar_*` keys (EL drafts need native review).
**Docs (FG-26):** `LEGAL_PATHS.md` entry, `ENGINE_INTERACTION_MAP.md` row, `CODEBASE_SEGMENTS.md`.

## 2. Legal paths
READ briefing digest: CDW → MRU → pure `BuildBriefingDigestUseCase` → card → selector (new, read-only; no write). Nav: existing callbacks only (`HomeScreen.kt:80-86`), no new route/deep link. Layout prefs: `DashboardRepository` sole `layout_config` accessor; new read filter, write unchanged. LEGAL_PATHS `:534-552` Money/Currency: pace amount via formatter in H, never summed/converted at render; existing `%.0f` strings are the violation being fixed.

## 3. Engines and downstream
`CurrencyConverter` (CRITICAL), `AnalyticsCurrencyNormalizer`, `MoneyAggregateBuilder`: MRU read-only, calls unchanged (component isolation changes only failure catching). Normalization engine (`engine.normalizeExpense`): CDW read-only for unchecked count. `SpendingPaceCalculator`: classifier reads `RunwayResult.Available.currentPace`, no calculator change. `MoneyRadarData` consumers: CDW, `MoneyRadarWidget`, `HomeScreen`, 9 fixtures; no other production consumer of `topReasons`/`dueBills`/`anomalyAlerts`. Cloud/AI (`DashboardBriefingInputBuilder`, `HybridDashboardBriefingService`, `DailyBriefingWorker`) untouched; digest never an input to them (INV-B18).

## 4. Persistence, privacy
Room NONE (no schema/version/migration/snapshot). SharedPreferences `dashboard_prefs/layout_config` filtered on read only; not backed up. Nothing logged/persisted from the digest; merchant names (single bill/anomaly) and pace amount on screen only. Diagnostics bounded enums only: `BRIEFING_NARRATIVE_MODE`, `RADAR_COMPONENT_UNAVAILABLE{component, reason}`, `PACE_ABSENT`, `UNCHECKED_SHOWN` (debug Timber, no new store); existing `Timber.w("RADAR_UNAVAILABLE class=%s")` acceptable. Template independent of AI toggles; AI branch still gated by `HomeViewModel.kt:281`; inc 1 adds no AI call.

## 5. Tests
**New:** `T/domain/usecase/dashboard/BuildBriefingDigestUseCaseTest` (AE-B1, B4, B6, B8, B10, B13; INV-B1..B3, B9, B19); pace classifier test (AE-B3 boundaries incl. exact 20% + display guard; belongs to home-insight-gap); narrative selector test (AE-B7, B10; INV-B10, B11); renderer test via Robolectric `@Config(qualifiers="el")` (AE-B9; INV-B14, B16; plurals, dates); Radar parity fixture old vs new `(score, level, CTA)` for every finding-2 failure (INV-B7); `DashboardRepository` retired-id read test (AE-B11; check existing repo test first); unchecked count test in CDW suite (AE-B5: 3 rows, not 4, not summed metric). No Compose UI test exists for the card → keep logic pure; 200% font device a11y check manual.
**Extend (assertions unchanged, add signals):** `T/domain/usecase/dashboard/ComputeMoneyRadarUseCaseTest.kt` — `failedRequiredSignalsNeverProduceHealthyZeroOrAdvice` `:73`, `missingSimulationForExistingBudgetIsUnavailableNotNoRisk` `:102`, `unsuccessfulBudgetImpactIsUnavailableNotNoRisk` `:111`, `failedIncomeSignalDoesNotBecomeZeroIncome` `:124` (bills Available, budget `INCOME_EXCLUDED` per spec), `invalid home currency cannot make an existing budget look healthy` `:139` (bills Available), `unresolved budget currency is unavailable...` `:149`, `excluded source money cannot produce a healthy budget forecast` `:161`, helper `assertRadarUnavailable` `:202`. `ComputeDashboardWidgetsUseCasePaceWiringTest`: digest carries pace in H.
**Retarget (STOP-AND-ASK):**
- `T/domain/usecase/dashboard/ComputeDashboardWidgetsInsightMoMTest.kt` 5 tests (`:156` day-three spike, `:188` spent-less, `:216` spent-higher, `:242` no previous baseline keeps today-spent fallback, `:273` `no insight is emitted when projection lands within the spike band`) assert `NaturalLanguageInsight` + `WIDGET_INSIGHT_*`; after gap fix `:273` flips (ON_TRACK); after retirement all five → digest/classifier assertions; `:242` today-spent removal (OD-B6) needs owner OK.
- `T/ui/screens/home/HomeViewModelStressTest.kt:473` (`assertEquals("insight", ...)`) delete or retarget.
- `T/ui/screens/home/DashboardWidgetRenderCoverageTest.kt:31` remove `NaturalLanguageInsight::class`.
- `DashboardWidgetMetaContractTest.kt:18` drop stale `"insight"`.
- 9 `MoneyRadarData(...)` fixtures if `signals` gets no default.
- `ComputeMoneyRadarUseCaseTest` `topReasons` assertions (`:205,279-293,475,497,593,614,645`) unchanged if finding 3 accepted; removing `topReasons` = STOP-AND-ASK.

## 6. Guards (FG by heading; text not read → confirm scope at S0)
FG-10 (per-child catch in MRU, selector, digest); FG-16 (diagnostics, merchant UI only, digest never to a builder); FG-17 (pace render, `%.0f` removal, OD-B2 30-day window via `TimePeriodUtils`, not raw `now - 30 * ONE_DAY_MS`); FG-12 (renderer/selector touch no repository); FG-08, FG-19, FG-06/FG-07 (no `@Ignore` on retargets; no new allowlist entries); FG-20 lint (`app/build.gradle.kts:243` baseline = pre-existing MissingTranslation only; new `radar_*` ship EL; removing EN-only `widget_insight_*`/`money_radar_reason_*` changes baseline matching → check `app/lint-baseline.xml` at S0; ai-fixes plan `:365` defers EN-only backfill); FG-26 docs. Shared guard files with ai-fixes (`SourceScanningArchitectureGuardTest.kt`, `CancellationSafetyArchitectureGuardTest.kt`) should not need edits.

## 7. Risk tiers
S-fix `home-insight-gap` T2 (pure function, strings; money-boundary FG-17 in scope). S1 MRU components + parity T3 (money/FX failure semantics, CE, score parity). S2 CDW unchecked count + digest T3 (money/normalization, hot file). S3 renderer + selector + card + strings T2 (+ privacy, a11y review). S4 retirement + layout filter T2/T3 (user preference data, shipped-behaviour change). **Overall T3** — planner-first, reviewer-strict, PSG, money review; no migration tests.

## 8. Validation (validation-runner; profiles: compile, targeted-unit-test, unit-test-shard, lint, static-guards, registered-guard, …)
S-fix: `compile`; `targeted-unit-test` `*ComputeDashboardWidgetsInsightMoMTest*`; classifier test; `lint`. S1: `*ComputeMoneyRadarUseCaseTest*`; then the 9 fixture classes if signature changes. S2: `*BuildBriefingDigestUseCaseTest*`, `*ComputeDashboardWidgetsUseCasePaceWiringTest*`, `*DashboardWidgetConsistencyTest*`. S3: selector + renderer tests; `lint`. S4: `*DashboardWidgetMetaContractTest*`, `*DashboardWidgetRenderCoverageTest*`, `*HomeViewModelStressTest*`, repository test. Closeout: `static-guards` + named `registered-guard` ids, dashboard/home `unit-test-shard` (confirm names via `-Action Profiles`), `lint`.

## 9. Sequencing and conflicts
- Order: ai-fixes Part B → money-correctness 1a → this feature (Q8); `home-insight-gap` before ai-fixes B3 (Q5) → runs first.
- ai-fixes plan names no HomeViewModel/HomeScreen/CDW/`NaturalLanguageInsight` edits; shared `strings.xml`/`values-el/strings.xml` (sequential: A, B1b, B1c, B2e, B3b) + guard test files; U9 resolved (no B3 test targets `NaturalLanguageInsight`).
- loan-installments touches CDW (its impact map line 173: 3 refs) and `HomeViewModel.kt:550` → serialize S-fix and S2 CDW edits after it; loan installments as confirmed recurring patterns (U7) unverified.
- money-correctness 1a: no CDW/normalization hits in grep; verify at S0 it doesn't touch `DashboardNormalizedInput`.
- assistant-follow-ups, nl-quick-add: strings ordering only.
- Internal: S1, S2 before S3; S3 before S4 (retirement last so pace text never disappears before the card replaces it).

## 10. Proposed slice cut
1. **S-fix `home-insight-gap`** (separate feature): classifier extraction; ON_TRACK sentence; EN/EL; currency-formatted lower/higher; update `ComputeDashboardWidgetsInsightMoMTest:273`. Footprint: `ComputeDashboardWidgetsUseCase.kt` (`:1375-1408`), `DashboardTextKeys`, `UiTextExtensions`, `values*/strings.xml`, one test file. Inc 1 BLOCKED without it.
2. **S1** MRU per-component availability + parity fixture + `RadarSignals`.
3. **S2** CDW distinct unchecked count + `BuildBriefingDigestUseCase` + `MoneyRadar(data, digest)`.
4. **S3** renderer + selector + `MoneyRadarWidget` rewrite + `HomeScreen` wiring + EN/EL strings + MEDIUM/anomaly taps.
5. **S4** retire `insight` (registry, `DashboardRepository` filter, `NaturalLanguageInsight` removal, tests).
6. **Z** docs + acceptance.
Inc 2 not mapped beyond framing (needs ai-fixes B2d / B2c `AppForegroundState` + owner-device measurement).

## 11. Open items for Gate 2
OD-B1 AI replaces template (consistent with UX-Q1; no tags on AI text) · OD-B2/UX-Q4 anomaly tap (C5) · OD-B3/UX-Q5 MEDIUM tap → Analytics month (both docs) · OD-B4/UX-Q2 no layout migration + release note · OD-B5 unchecked = current month · OD-B6 drop today-spent (removes shipped behaviour; retargets `InsightMoMTest:242`) · OD-B7 "Money Radar" Latin in EL (`values-el:33` precedent) · OD-B8 score dies on income FX (`getMonthlyIncome` `:479` via `calculateDueBillsScore`) → follow-up · OD-B9 deferred · OD-B10 resolved (UI renderer) · UX-Q3/C7 pin unchecked (owner) · UX-Q6/C6, UX-Q7/C8 · UX-Q8 agreed with spec §6 · UX-Q9 no skeleton (Loading returns early `HomeViewModel.kt:352`) · UX-Q10 budget CTA currency (`MoneyRadarWidget.kt:93-103`, `:395-403` builds `MonteCarloBudgetImpact(budgetAmount = 0.0, p50Forecast = 0.0, ...)`; mapper unread) · UX-Q11 out of scope · UX-Q12 unverified (`categoryId == null` `:292`) · **New:** N1 budget tier vs p (finding 1); N2 copy conflicts C1/C2/C3; N3 `diagnostics` text (`HomeScreen.kt:573-579`) keep/drop in card; N4 edit-mode card rendering; N5 EL for `home_ai_briefing_title`.

## 12. U-items
Resolved: U2, U3 (precedent → Transactions month), U4, U6, U9, U11 (RUNNING → Loading `HomeViewModel.kt:303`; Ready→Loading flip exists), U14. Partial: U1 (`CurrencyFormatter.formatMoney(amount, code, showCents)` `M/domain/util/CurrencyFormatter.kt:51`, locale symbol, `Currency.defaultFractionDigits`, HALF_EVEN; HomeScreen uses `MoneyDisplayUi.from(...).formatted` `:596`; INV-16 digits accessor not found); U5 (no sink). Unverified: U7, U8, U10, U12 (`pendingCount` from `ctx.data.data.pendingCount` CDW `:1255`), U13.

## 13. Re-verify at S0
CDW line numbers (after loan-installments); `values-el`/`lint-baseline.xml` entries for `widget_insight_*`, `money_radar_reason_*`; registered-guard ids + shard names (`-Action Profiles`); `DashboardRepository` test existence; `MoneyDisplayUi`; digits accessor; `MonteCarloBudgetImpactUiMapper` locale behaviour; `AnomalyAlertRepository.getActiveAlerts()` filters resolved/dismissed.
