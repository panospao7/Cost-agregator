# Feature plan: briefing-grounded-signals (increment 1) + prerequisite `home-insight-gap`

Author: feature-planner (read-only). Persisted by the orchestrator. Date 2026-10-16. Status: **APPROVED 2026-10-16** (owner: "Recommended and approved" — PD-1..PD-7 accepted; amendment set incl. INV-B20, AE-B14..B16; HD-2 = (a) S-fix first, loan-installments rebases; HD-3 = ES drafts if values-es exists and lint flags (no baseline change); HD-4 owner reviews EL/release-note copy at marked points; S-fix ships alone before ai-fixes B3a; S3+S4 same release). PARKED post-1.0.
Basis: `docs/features/briefing-grounded-signals/impact-map.md` (trusted; lines not re-checked), `gate2-decisions.md` (G2-1..G2-21 DECIDED = Recommended), `domain-spec.md` (INV-B1..B19, AE-B1..B13, OD-B1..B10), `ux-flows.md` (§4 states, §9 keys), `brief.md` v2.
`M` = `app/src/main/java/com/yourname/expensetracker`, `T` = `app/src/test/java/com/yourname/expensetracker`. `VR <profile> …` = `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/validation-runner.ps1 -Action Start -Profile <profile> …` (validation-runner only, serial, poll; missing/stale ≠ PASS). `-TestFilter` = ONE pattern per run. `<shard:…>`, `<guard:FG-nn>` resolved at S0 via `-Action Profiles`. RS = reviewer-strict, PSG = privacy-security-guardian, AG = architecture-guardian, MR = money review (RS money/currency focus). Max 2 review + 2 debug iterations per slice → PARTIAL/BLOCKED.

- Risk tier **T3** overall (G2-21). Schema **NONE** (no Room version/migration/snapshot/`migration-tests`; `layout_config` filtered on read only). Nothing persisted; no AI call; no new permission/route/network.

## Planner deviations (owner confirms)
- **PD-1** `MoneyRadarData.signals` has a fail-closed default (every component `Unavailable(NOT_EVALUATED)`) → 9 fixture sites unchanged (G2-18); production MRU always passes it; S1 tests assert MRU never emits the default.
- **PD-2** Golden-first parity: `ComputeMoneyRadarParityTest` written and run green on the **unchanged** MRU before restructuring; expected `(score, level, CTA)` never edited; covers every impact-map finding-2 failure (invalid H, bill conversion, budget conversion, spending excluded, MC/impact error, income excluded).
- **PD-3** `BuildBriefingDigestUseCase` pure, no diagnostics I/O; returns bounded reason enums as data; HomeViewModel logs `BRIEFING_*`/`PACE_ABSENT`/`UNCHECKED_SHOWN` as debug Timber on change only (INV-B1, U5). CDW gets the digest without a ctor change (default arg or `object`); a CDW ctor change = stop.
- **PD-4** S3 moves the AI briefing into the card and strips it from the Insight branch; Insight keeps only pace/today line until S4. S3 releasable alone (brief duplicate pace line) but **S3 and S4 ship in the same release**.
- **PD-5** Pace strings belong to S-fix (lower/higher `%1$.0f` → `%1$s` formatted amount, ON_TRACK, neutral line, EN + EL; U8 names fixed there); S3 renderer reuses; S4 removes only today-spent + insight `DashboardTextKeys`/`UiTextExtensions` mappings.
- **PD-6** AE-B5 unchecked-count test in a **new** CDW test file; existing CDW suites not edited.
- **PD-7** FG-26 docs updated in the slice creating each artefact; Z checks consistency only; shared docs via orchestrator doc lock (ai-fixes, money-correctness, loan-installments touch them).

## Spec/UX amendment set (S0; docs only; domain-modeler + ux-flow-designer)
- N1/G2-9: INV-B19 + §2 BUDGET_RISK row → "Available, risk non-null, tier ≠ LOW"; p ≥ 0.25 gate removed; percent `(p×100).toInt()`; brief fact 3 + chip rule reworded. **AE-B16**: overrun 6 %, p = 0.12 → MEDIUM fact "12 %", `facts[0]` action = CTA AdjustBudget (INV-B3). `buildTopReasons` untouched (unrendered, G2-18).
- C1/C2/C3 (G2-10): drop `radar_fact_bills_total` + UX §5 bills-total rules; pace sentence shows difference in H; examples → locale symbol via `CurrencyFormatter` ("€200.01").
- C4 (G2-11): MONTH_PACE tap → TRANSACTIONS_MONTH. C5 (G2-2): anomaly tap → `[startOfDay(now−30d), now]` via `TimePeriodUtils`. C6 (G2-12): UNCHECKED no tap (§2, §9 Q9). C7 (G2-13): remove "pinned (UX-Q3)" from UX §5/§6; spec rank 7. C9: one definition — NEUTRAL_LIMITED = status line + Radar-independent facts. C10: PendingReviewAlert overlap accepted.
- C8 (G2-14): digest field `emptyLedger` (from a signal CDW already loads); NO_DATA mode in §7 after TEMPLATE, before LIMITED; header "Getting started" badge, no ring when `emptyLedger`. **INV-B20**, **AE-B14**: empty ledger → NO_DATA line; empty ledger + pending 2 → TEMPLATE with pending fact.
- G2-15: AI `diagnostics` text not in card. G2-16: edit mode static preview (header + fixed string `radar_edit_preview`; no live facts/merchants/AI); **AE-B15**. G2-17: EL `home_ai_briefing_title`. G2-18: `topReasons` kept unrendered. G2-6: today-spent dropped (S4).
- Finding 2: §6 note — patterns + alerts read before H; per-child capture rethrowing `CancellationException`; listing split from conversion.

## Slice table
| ID | Title | Tier | Depends | Reviewers | FG |
|---|---|---|---|---|---|
| S0 | Pre-flight, baselines, amendments (no `app/` edits) | — | Release 1.0 | — | — |
| S-fix | `home-insight-gap` (separate feature, ships alone) | T2 | S0; **before ai-fixes B3a**; after loan-installments CDW slices (HD-2) | RS + MR + PSG (copy) | 06/07, 17, 20, 26 |
| S1 | MRU per-component availability + `RadarSignals` + parity | T3 | S-fix, ai-fixes Part B, money-correctness 1a-G | RS + PSG + MR + AG | 06/07, 10, 16, 17, 19, 26 |
| S2 | CDW unchecked count + digest + `MoneyRadar(data, digest)` | T3 | S1; loan-installments S4a–S4d merged | RS + PSG + MR | 06/07, 08, 10, 12, 16, 17, 19, 26 |
| S3 | Renderer + selector + card rewrite + Home wiring + strings + taps | T2 | S2 | cheap reviewer + PSG (copy/a11y/privacy) | 12, 16, 17, 20, 26 |
| S4 | Retire `insight` + `DashboardRepository` read filter | T3 | S3 (same release) | RS + AG (prefs) | 06/07, 08, 19, 20, 26 |
| Z | Docs consistency + bundle gate + handoff | — | S4 | fresh-session RS (Opus max) + PSG | all |
Strict order S0 → S-fix → (ai-fixes B3a/B3b, money-correctness 1a) → S1 → S2 → S3 → S4 → Z. Shared files (sequential): CDW (S-fix, S2, S4), `HomeScreen.kt` (S3, S4), `values{,-el}/strings.xml` (S-fix, S3, S4 + ai-fixes, assistant-follow-ups, nl-quick-add), `ComputeDashboardWidgetsInsightMoMTest` (S-fix, S4).

## S0 — Pre-flight (orchestrator + Sonnet scout; no app edits)
- Preconditions: Release 1.0 shipped; clean tree; record git revision; ai-fixes B3a status; money-correctness 1a-G green; loan-installments S4a–S4d status (Seg 10 dashboard sums, CDW); money-correctness 1a has no `DashboardNormalizedInput` edits; money-correctness 1b-1 digits accessor (INV-16) landed? yes → formatter uses it; no → `CurrencyFormatter.formatMoney`, no new rounding rule.
- Re-verify impact-map §13: CDW lines after loan-installments; `values-el` + `app/lint-baseline.xml` entries for `widget_insight_*`/`money_radar_reason_*`; `DashboardRepository` test existence; `MoneyDisplayUi`; `MonteCarloBudgetImpactUiMapper` locale (UX-Q10); `AnomalyAlertRepository.getActiveAlerts()` filters resolved/dismissed; `pendingCount` producer (U12); `MoneyRadarData` location; `emptyLedger` source signal CDW already loads (none without a new read → stop). Also: `values-es/strings.xml` exists and lint MissingTranslation flags new `radar_*` keys in ES? (HD-3).
- Profiles: `-Action Profiles` → `<shard:dashboard>`, `<shard:home-ui>`, guard ids for FG-10/12/16/17/19/20/26.
- Baselines (clean revision, record run IDs + pre-existing failures): `VR targeted-unit-test -TestFilter "*ComputeMoneyRadarUseCaseTest*"`; `"*ComputeDashboardWidgetsInsightMoMTest*"`; `"*HomeViewModelStressTest*"`; `VR unit-test-shard -Shard <shard:dashboard>`; `VR static-guards`; `VR lint`; record KNOWN_VIOLATIONS / allowlist / lint-baseline counts (must not grow).
- Done when: amendment set applied; owner passed plan gate.

## S-fix — `home-insight-gap` (T2; separate feature, own handoff `workflows/active/home-insight-gap-handoff.md`)
- INV-B5 classifier (§4 expressions exactly, raw doubles, exact 20 % → ON_TRACK, display guard); ON_TRACK sentence in middle band; neutral line when no pace and nothing spent today (Insight card + hosted AI briefing never disappear; ai-fixes B3 depends on it); lower/higher amounts in H via formatter, `%.0f` removed (LEGAL_PATHS `:550`, FG-17); today-spent fallback kept until S4. AE-B3 (boundaries + display guard), AE-B4.
- Source: NEW `M/domain/usecase/dashboard/MonthPaceClassifier.kt` (pure); CDW `:1375-1408` (`buildNaturalLanguageInsight` → thin caller); `M/domain/text/DashboardTextKeys.kt`; `M/ui/components/UiTextExtensions.kt`; `values/strings.xml`, `values-el/strings.xml` (on_track, neutral, rewritten lower/higher EN + EL).
- Tests: NEW `T/domain/usecase/dashboard/MonthPaceClassifierTest.kt`; `ComputeDashboardWidgetsInsightMoMTest.kt` `:273` → ON_TRACK (approved G2-19) + neutral-line case; other assertions unchanged.
- Legal path: CDW read-only; one pace-band implementation; `UiText` rendering. Docs: `CODEBASE_SEGMENTS.md` Seg 10 (classifier).
- Validation: `VR compile`; `VR targeted-unit-test -TestFilter "*MonthPaceClassifierTest*"`; `"*ComputeDashboardWidgetsInsightMoMTest*"`; `"*ComputeDashboardWidgetsUseCasePaceWiringTest*"`; `VR registered-guard -GuardId <guard:FG-17>`; `VR lint`.
- Owner Greek review: on_track, neutral, lower/higher EL. Done when: green; lint baseline unchanged; ai-fixes B3a unblocked.

## S1 — MRU per-component availability (T3)
- INV-B7 (parity), B8, B9 (component level), B17 (MRU codes only); FG-10 per-child capture or `supervisorScope`, always rethrow CE; patterns + alerts read without H; listing separated from FX conversion; `UpcomingBill.amount` + `ViewBills(List<UpcomingBill>)` kept; score/level/CTA/legacy fields identical; `topReasons` unchanged. AE (MRU part): AE-B1/B2 (bills Available, score hidden on missing USD rate, `{SCORE, BILL_CONVERSION}`), AE-B5 (budget `SPENDING_EXCLUDED`), AE-B6 (`INCOME_EXCLUDED` with bills Available).
- Source: `M/domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt`; NEW `M/domain/usecase/dashboard/MoneyRadarSignals.kt` (`RadarSignals`, `RadarComponent<T>`, `RadarUnavailableReason` incl. `NOT_EVALUATED`); `MoneyRadarData` definition (S0 locates).
- Tests: NEW `T/domain/usecase/dashboard/ComputeMoneyRadarParityTest.kt` (PD-2, first, on pre-change code); extend `ComputeMoneyRadarUseCaseTest.kt` (`:73,:102,:111,:124,:139,:149,:161`, helper `:202`) with signal assertions — **existing assertions unchanged**, `topReasons` assertions untouched.
- Legal path: MRU sole owner of components/score/CTA; CurrencyConverter/Normalizer/MoneyAggregateBuilder calls unchanged. Docs: `ENGINE_INTERACTION_MAP.md` note (component isolation, no new engine calls).
- Validation: `VR compile`; `VR targeted-unit-test -TestFilter "*ComputeMoneyRadarParityTest*"` twice (baseline revision before restructure, then after); `"*ComputeMoneyRadarUseCaseTest*"`; `VR unit-test-shard -Shard <shard:dashboard>` (covers 9 fixtures); `VR registered-guard -GuardId <guard:FG-10>`.
- Done when: parity green before and after, expected values unchanged; `CancellationSafetyArchitectureGuardTest`/`SourceScanningArchitectureGuardTest` unedited; no allowlist growth.

## S2 — Digest + distinct unchecked count (T3; CDW edits after loan-installments S4a–S4d)
- INV-B1, B2, B3, B4 (digest), B5 (calls classifier only), B6, B9, B12, B15 (digest), B17, B18, B19 (amended), B20 (`emptyLedger`); `monthUncheckedCount` = distinct Excluded rows at `:491-496` (PURCHASE, `!isNotMine`, current month), `null` when normalized input Unavailable, never `excludedTransactionCount`; digest after `compute()` `:348`; `DashboardWidget.MoneyRadar(data, digest)` `:232`/`:1262`. AE-B1, B4, B5, B6, B8, B12, B13, B14, B16.
- Source: NEW `M/domain/usecase/dashboard/BuildBriefingDigestUseCase.kt` + NEW `BriefingDigestModels.kt` (`BriefingFact`, `BriefingDigest`, `FactType`, `SourceTag`, `DigestTapTarget`, absent-reason enums); CDW (no ctor change, PD-3).
- Tests: NEW `BuildBriefingDigestUseCaseTest.kt`; NEW `ComputeDashboardWidgetsUncheckedCountTest.kt` (AE-B5: 3, not 4, not summed; Unavailable → no fact); extend `ComputeDashboardWidgetsUseCasePaceWiringTest` (digest pace in H; AE-B12).
- Legal path: read-only; digest reads no repository/DAO, no AI; never passed to `DashboardBriefingInputBuilder`/`DailyBriefingWorker`. Docs: `LEGAL_PATHS.md` READ briefing-digest entry + FORBIDDEN list (spec §3); `ENGINE_INTERACTION_MAP.md` row "Radar + RunwayResult → digest (no writes)"; `CODEBASE_SEGMENTS.md` Seg 10.
- Validation: `VR compile`; `VR targeted-unit-test` separately for `"*BuildBriefingDigestUseCaseTest*"`, `"*ComputeDashboardWidgetsUncheckedCountTest*"`, `"*ComputeDashboardWidgetsUseCasePaceWiringTest*"`, `"*DashboardWidgetConsistencyTest*"`, `"*ComputeDashboardWidgetsInsightMoMTest*"`; `VR unit-test-shard -Shard <shard:dashboard>`; `VR registered-guard` separately `<guard:FG-12>`, `<guard:FG-16>`, `<guard:FG-17>`.
- Done when: green; no new log line carries content; `DashboardNormalizedInput` unchanged unless S0 cleared it.

## S3 — Card UI, narrative, strings, taps (T2 + privacy/a11y review)
- INV-B4 (render), B10, B11, B14, B15, B16, B17 (mode codes on change); G2-1, G2-2, G2-3, G2-7, G2-9 (chip reconciled), G2-11..G2-17. AE-B3 (render guard), B7, B9, B10, B11 (hidden-Radar part), B15.
- `MoneyRadarWidget.kt`: remove whole-card `contentDescription` (`:107-114`); chips `FlowRow` (`:245`); locale-aware `uppercase` (`:166`); unavailable branch (`:49-66`) → header + status line + facts; plurals replace "Bill(s)" hack.
- HomeScreen taps: anomaly → 30-day range via `TimePeriodUtils` (`:827-831`; no raw `30*ONE_DAY_MS`); MEDIUM risk → Analytics month (`:833-836`); pace → this month's transactions; pending → `onNavigateToReview`; unchecked not tappable. AI text moves into card, `diagnostics` dropped (PD-4); edit mode static preview.
- Source: `MoneyRadarWidget.kt`; NEW `M/ui/components/dashboard/RadarFactRenderer.kt`; NEW `M/ui/screens/home/RadarNarrativeSelector.kt` (pure; tap-target → nav mapping + 30-day range); `HomeViewModel.kt` (`radarNarrative` in combine `:335-404`); `HomeScreen.kt` (`:819-841`, AI sub-block of `:527-582`); `values/strings.xml`, `values-el/strings.xml` (~45 `radar_*`, EL `home_ai_briefing_title`, no `radar_fact_bills_total`).
- Tests: NEW `T/ui/screens/home/RadarNarrativeSelectorTest.kt`; NEW `T/ui/components/dashboard/RadarFactRendererTest.kt` (Robolectric EN + `@Config(qualifiers="el")`: plurals, today/tomorrow, merchant after colon, "999" absent, no Latin except brand).
- Legal path: existing nav callbacks only (`HomeScreen.kt:80-86`); renderer/selector touch no repository; merchant names UI only, never logs. Docs: `CODEBASE_SEGMENTS.md` (renderer, selector).
- Validation: `VR compile`; `VR targeted-unit-test` separately `"*RadarNarrativeSelectorTest*"`, `"*RadarFactRendererTest*"`, `"*HomeViewModel*"`; `VR unit-test-shard -Shard <shard:home-ui>`; `VR lint`; `VR registered-guard -GuardId <guard:FG-20>`.
- Manual owner device check: 200 % font EN + EL, TalkBack order per UX §8, contrast light/dark. Owner Greek review: every `radar_*` EL draft ("Αρχή", badge capitals, plurals), `radar_edit_preview`, `home_ai_briefing_title`. Escalate to RS if renderer does money beyond calling the formatter.
- Done when: green; lint baseline unchanged (or HD-3 resolved); device check signed off.

## S4 — Retire `insight` (T3: user preference data, removes shipped behaviour)
- INV-B12, B13; G2-4 no migration; G2-6 today-spent removed; `retiredIds` filtered on read in `getDashboardConfigInternal` (`:42-79`) before `nextOrder` (`:65`) and `savedIds` comparison; no write on read; JSON shape unchanged. AE-B11.
- Source: `DashboardWidgetRegistry.kt` (`:23,58,82` + `retiredIds`); `M/data/repository/DashboardRepository.kt`; CDW (`:128`, `:336-342`, `:1352`, `:1375-1408`; classifier stays); `DashboardTextKeys.kt` (`:6-8`); `UiTextExtensions.kt` (`:181-183`); `HomeScreen.kt` remaining Insight branch; `strings.xml` only now-unreferenced insight strings (PD-5).
- Tests (approved retargets G2-19, in place, never `@Ignore`): `ComputeDashboardWidgetsInsightMoMTest` `:156`, `:188`, `:216`, `:242` (today-spent → no fact), `:273` → digest/classifier assertions; `HomeViewModelStressTest:473`; `DashboardWidgetRenderCoverageTest:31`; `DashboardWidgetMetaContractTest:18`; retired-id read test (extend existing `DashboardRepository` test or NEW `T/data/repository/DashboardRepositoryRetiredIdTest.kt`, S0 decides).
- Legal path: `DashboardRepository` sole `layout_config` accessor; write unchanged. Docs: remove Insight references from SEGMENTS, ENGINE map, LEGAL_PATHS.
- Validation: `VR compile`; `VR targeted-unit-test` separately `"*DashboardWidgetMetaContractTest*"`, `"*DashboardWidgetRenderCoverageTest*"`, `"*HomeViewModelStressTest*"`, `"*ComputeDashboardWidgetsInsightMoMTest*"`, `"*DashboardRepository*"`; `VR unit-test-shard` `<shard:dashboard>`, `<shard:home-ui>`; `VR lint`.
- Done when: green; only listed tests changed; no unknown-id warning for `insight`.

## Z — Final bundle gate
Rerun every slice filter on final revision; `VR static-guards`; each S0-resolved `VR registered-guard` (FG-10/12/16/17/19/20/26); `VR lint`; `VR unit-tests` once. FG-26 docs consistent (LEGAL_PATHS entry, ENGINE row, SEGMENTS). KNOWN_VIOLATIONS / allowlist / lint-baseline ≤ S0 baseline. Fresh-session RS (Opus max, not the discovery reviewer) + PSG. Owner signs off EL copy + device a11y check; release notes approved; handoff updated.

## Owner plan-gate checklist
- [ ] PD-1..PD-7. [ ] Amendment set incl. INV-B20, AE-B14..B16, NO_DATA precedence, `radar_edit_preview`. [ ] HD-1..HD-4. [ ] S-fix ships alone before ai-fixes B3a. [ ] S3 + S4 same release. [ ] Release-note lines. [ ] Greek review points.

## Human decisions
- **HD-1** confirm PD-1..PD-7.
- **HD-2** sequencing: S-fix must precede ai-fixes B3a but CDW edits follow loan-installments S4a–S4d; if not merged when B3a is next: (a) S-fix first, loan-installments rebases (S-fix touches only CDW `:1375-1408`), or (b) hold B3a.
- **HD-3** if `values-es` exists and lint flags new `radar_*` keys: ES drafts or another approach; no lint-baseline change without approval.
- **HD-4** final EL wording + release-note and edit-mode preview copy.

## New stop-and-ask triggers
Parity test fails after restructure or expected values would need editing · KNOWN_VIOLATIONS/allowlist/lint-baseline growth or any lint-baseline edit (incl. stale MissingTranslation from deleted strings) · any test outside G2-19 changed/deleted/`@Ignore`d; removing `topReasons` or its assertions · CDW merge conflict with loan-installments or line drift beyond §13 · CDW ctor change or a new repository/DAO read for `emptyLedger`/count · new nav route or deep link · a11y regression in device check (200 %, TalkBack, contrast) · edits needed in `CancellationSafetyArchitectureGuardTest`/`SourceScanningArchitectureGuardTest` · a digits/rounding rule would be duplicated (no accessor and `CurrencyFormatter` insufficient).

## Risk register
| ID | Risk | Mitigation |
|---|---|---|
| R1 | Score/CTA drift from MRU restructure | PD-2 golden-first parity; RS + MR |
| R2 | CE swallowed in per-child capture | FG-10 guard run; AG |
| R3 | CDW conflicts with loan-installments | HD-2; S0 line re-check; serial edits |
| R4 | `strings.xml` collisions (ai-fixes, assistant-follow-ups, nl-quick-add) | shared-file order; orchestrator lock |
| R5 | Lint baseline shifts on deleting EN-only insight keys | S0 check; stop trigger |
| R6 | EL quality, Latin text left on card | Robolectric EL test; owner review |
| R7 | Users who hid Radar lose AI briefing + pace line | G2-4 release note |
| R8 | Digest content leaks to logs/cloud | PD-3; FG-16; PSG; INV-B18 |
| R9 | Fixture churn from `MoneyRadarData` change | PD-1 default |
| R10 | `values-es` MissingTranslation | HD-3 |
| R11 | Duplicate pace line between S3 and S4 | PD-4 same release |

## Release-note lines
- The separate Insight card is retired; its month-pace line and the AI briefing now appear inside Money Radar.
- The "spent today" line was removed from the summary; Period Summary still shows it.
- Tapping an unusual-charge alert now opens the last 30 days of transactions (previously this month).
- Medium budget risk is now tappable and opens this month's Analytics.
- If you hid Money Radar, the daily summary and AI briefing are hidden too; show Money Radar in edit mode to see them.

## Deferred
- Later increments: inc 1b (price increase, forecast overshoot, lifestyle inflation; after 2 weeks dogfood); inc 2 on-device Nano narration (Phase A device record + Phase B harness after ai-fixes B2c/B2d); cloud narration of new facts.
- Own future briefs: `why-this-number`, `period-recap`, `briefing-worker-on-device-fallback` stub.
- Follow-ups: OD-B8 income-FX score; remove `topReasons`/`COMPUTE_MONEY_RADAR_REASON_*`/`money_radar_reason_*`; no-rate expenses list screen (makes unchecked tappable); Planned/Occurrence upcoming facts; UX-Q10 budget CTA currency if more than a display fix; rename `ComputeDashboardWidgetsInsightMoMTest`; UX-Q11/Q12.
