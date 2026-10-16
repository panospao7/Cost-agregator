# Domain spec: briefing-grounded-signals — inc 1 (merged Money Radar narrative) + inc 2 framing

Author: domain-modeler. Date 2026-10-16. Post-1.0, docs-only. Inputs: `brief.md` v2 (binding), `critique.md`, Gate 1 decisions Q1..Q10 (`workflows/active/briefing-grounded-signals-handoff.md`), `money-correctness/domain-spec.md` (INV-2, INV-9, INV-19, INV-22), `LEGAL_PATHS.md` Money/Currency `:534-552`, ai-fixes plan B2/B3.
Legend: [V] verified this pass; [U] unverified (§17). `M/` = `app/src/main/java/com/yourname/expensetracker/`. CDW = `M/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt`; MRU = `.../ComputeMoneyRadarUseCase.kt`.

## 0. Verified facts this spec relies on
| # | Fact | Ref |
|---|---|---|
| F1 | Radar has one try/catch: **any** failure → `UNAVAILABLE` with empty bills/alerts/risk; `CancellationException` rethrown | [V] MRU `:151-165` |
| F2 | Failure sources: home currency invalid `:169-171`; any due-bill conversion `:240-246`; overall-budget conversion `:294-300`; any excluded/warned month purchase `:318-320`; upcoming-recurring conversion `:330-337`; MC/impact errors `:352-367`; any excluded/warned month deposit `:479`, read only when ≥ 1 bill is due (`:407`, `:415`) | [V] |
| F3 | No overall budget, or budget ≤ 0 → `budgetRisk = null` **before** the spending check (spending exclusion can't fail Radar then) | [V] MRU `:292-304` |
| F4 | Reasons order bills → anomalies → budget (M/H/C, p ≥ 0.25, LOW dropped), cap 3, fallback healthy/monitor; CTA order budget-CRITICAL → anomalies → bills → budget HIGH/MEDIUM | [V] MRU `:488-560`, `:565-587` |
| F5 | Insight thresholds: only when `projected != null && previous != null && previous > 0`; `diff = projected − previous`; `diff < 0` → less; `diff > previous * 0.2` → higher; else `null`; then today-spent fallback | [V] CDW `:1375-1408`, call `:336-342` |
| F6 | Pace nulls (`projectedTotal = null`, `NO_BASELINE`) whenever dashboard data quality is partial or not COMPLETE | [V] CDW `:715-733` |
| F7 | Dashboard `dataQuality.excludedTransactionCount` is a **sum over ~11 overlapping aggregates** (period, today, week, month, deposit, each category, 5 historical months), so one row counts several times. It is not a row count | [V] CDW `:498-506`; `DashboardNormalizedInput.kt:93-108` |
| F8 | Insight strings `%1$.0f` with no currency, EN only (no EL keys) | [V] `values/strings.xml:950-952`; `values-el` has only `money_radar_unavailable` `:33` |
| F9 | Widget layout = JSON in SharedPreferences `dashboard_prefs`/`layout_config`; missing defaults appended; unknown IDs kept and `Timber.w` on **every** dashboard emission | [V] `DashboardRepository.kt:22,42-79,91-104`; `HomeViewModel.kt:378-390` |
| F10 | AI Ready → `home_ai_briefing_title`, `isAi=true`; Disabled when `!aiEnabled \|\| !dashboardBriefingEnabled` | [V] `HomeViewModel.kt:281-314` |
| F11 | `UiText.MessageKey` has no plural quantity; `PluralResource` takes an Android res ID | [V] `M/domain/model/UiText.kt:21-68` |
| F12 | Segment 10 "Dashboard Totals & Widgets" owns CDW, `DashboardRepository`, Home VM/screen | [V] `CODEBASE_SEGMENTS.md:257-273` |

## 1. Entities & value objects (inc 1; all in-memory, never persisted)
- `FactType` (closed enum; also the fact ID): `BUDGET_RISK, ANOMALY_ALERTS, BILLS_DUE, MONTH_PACE, UNCHECKED_NO_RATE, PENDING_REVIEW`.
- `SourceTag`: `BUDGET_FORECAST, ANOMALY_ALERTS, RECURRING_BILLS, MONTH_PACE, EXCHANGE_RATES, PENDING_REVIEW` (1:1 with `FactType`).
- `DigestTapTarget`: `RECURRING, TRANSACTIONS_ANOMALY_RANGE, ANALYTICS_MONTH, TRANSACTIONS_MONTH, PENDING_REVIEW, NONE`. These are domain intents with no navigation code.
- `BriefingFact` (sealed; each has `type`, `rank: Int`, `sourceTag`, `tap`):
  - `BillsDue(count: Int ≥ 1, nearestDueDateMillis: Long, nearestDaysUntil: Int ≥ 0, singleMerchant: String?)`. `singleMerchant` is non-null only if count == 1 and the name is non-blank. **No amount.**
  - `AnomalyAlerts(count: Int ≥ 1, singleMerchant: String?)`, with the same rule. **No amount** (alerts carry no currency).
  - `BudgetRisk(tier: MEDIUM|HIGH|CRITICAL, probabilityPercent: Int 25..100)`, where percent = `(p × 100).toInt()` (truncation; Radar parity).
  - `MonthPace(band: LOWER|ON_TRACK|HIGHER, difference: Double?, currency: CurrencyCode)`. `difference` is unrounded, > 0, in H of the normalized input, and null for ON_TRACK.
  - `UncheckedNoRate(count: Int ≥ 1)` · `PendingReview(count: Int ≥ 1)`.
- `BriefingDigest(computedAtMillis: Long, facts: List<BriefingFact> /* 0..3, rank order */, truncatedCount: Int 0..3, limited: Boolean, scoreAvailable: Boolean, urgencyLevel: UrgencyLevel)`.
- `RadarComponent<T>` = `Available(value: T)` | `Unavailable(reason: RadarUnavailableReason)`. `RadarUnavailableReason` = `HOME_CURRENCY, PATTERNS_READ, ALERTS_READ, BUDGET_READ, BILL_CONVERSION, BUDGET_CONVERSION, SPENDING_EXCLUDED, INCOME_EXCLUDED, SIMULATION, UNKNOWN`.
- `MoneyRadarData` gains `signals: RadarSignals(bills: RadarComponent<List<DueBillSignal>>, anomalies: RadarComponent<List<AnomalyAlertSummary>>, budget: RadarComponent<BudgetRiskInfo?>)`. `DueBillSignal(merchant, dueDate, daysUntilDue, sourceCurrency)` carries no amount. The existing fields keep their meaning. `topReasons` is no longer rendered. Whether to remove it is the impact-mapper's call (U6).
- `NarrativeMode` = `AI | TEMPLATE | NEUTRAL_CLEAR | NEUTRAL_MONITOR | NEUTRAL_LIMITED`.

## 2. Digest contract (one fact per type; facts beyond the cap dropped)
| Type | Source (already computed) | Present iff | Values shown | Tag | Tap |
|---|---|---|---|---|---|
| BUDGET_RISK | `signals.budget` | Available, non-null, p ≥ 0.25, tier ≠ LOW | tier, percent | BUDGET_FORECAST | ANALYTICS_MONTH (OD-B3) |
| ANOMALY_ALERTS | `signals.anomalies` (persisted unresolved alerts ≤ 30 d) | Available, non-empty | count / merchant | ANOMALY_ALERTS | TRANSACTIONS_ANOMALY_RANGE (OD-B2) |
| BILLS_DUE | `signals.bills` (confirmed patterns, `startOfToday..now+7d`) | Available, non-empty | count, nearest date / merchant | RECURRING_BILLS | RECURRING |
| MONTH_PACE | `RunwayResult.Available.currentPace` | §4 | band, difference + H | MONTH_PACE | ANALYTICS_MONTH (U3) |
| UNCHECKED_NO_RATE | `monthUncheckedCount` (§5) | count ≥ 1 | count | EXCHANGE_RATES | TRANSACTIONS_MONTH |
| PENDING_REVIEW | `pendingCount` CDW `:1255` | > 0 | count | PENDING_REVIEW | PENDING_REVIEW (U2) |

**One priority order** (rank; lower first): 1 BUDGET_RISK CRITICAL · 2 ANOMALY_ALERTS · 3 BILLS_DUE · 4 BUDGET_RISK HIGH · 5 BUDGET_RISK MEDIUM · 6 MONTH_PACE HIGHER · 7 UNCHECKED_NO_RATE · 8 PENDING_REVIEW · 9 MONTH_PACE LOWER/ON_TRACK.
- **Reconciliation:** the order follows Radar's CTA order, not the reasons order. The first sentence and the primary CTA then always agree. Radar facts rank above every new fact, so all ≤ 3 Radar facts always fit. That gives "no fact lost" against the old reasons baseline.
- **Tie-break:** none is needed between facts, because each type appears at most once and each rank maps to one tier or band. Within BILLS_DUE, "nearest" = min `daysUntilDue`, then min `dueDate`, then Radar list order (stable).
- **Cap:** `facts = sorted.take(3)`; `truncatedCount = sorted.size − 3` (≥ 0). One sentence per fact. Facts are never merged.
- **Freshness:** built on every dashboard compute from **one** compute pass; `computedAtMillis = ctx.now`. Radar reads its own `now` in the same pass, and that skew is accepted. No cache, no artifact. Midnight or a TZ change shows on the next refresh (U13).

## 3. Ownership & legal paths (read-only; Segment 10)
- **Digest:** new pure `BuildBriefingDigestUseCase` (`M/domain/usecase/dashboard/`), called by CDW. Inputs: `MoneyRadarData`, `RunwayResult`, `pendingCount`, `monthUncheckedCount`, `ctx.now`. It does no I/O, uses no repository or DAO, and makes no AI call. CDW attaches it as `DashboardWidget.MoneyRadar(data, digest)`. This keeps the logic out of the >1,400-line hot file.
- **Radar components + score/CTA:** `ComputeMoneyRadarUseCase` only (§6).
- **Pace band:** exactly one pure classifier (§4). `home-insight-gap` extracts it; the digest is its only caller after inc 1.
- **Unchecked count:** CDW's normalization step, which already produces `Excluded` results at `:491-496`.
- **Narrative selection:** one pure selector in `M/ui/screens/home/`, used by the `HomeViewModel` dashboard combine. It is the only place that combines `AiLoadState` with the digest.
- **Rendering** (facts → strings, plurals, dates, money): one renderer in `M/ui/components/dashboard/`. Reason: `UiText.MessageKey` has no plurals (F11), and inc 2 needs typed facts. OD-B10.
- **Proposed `LEGAL_PATHS.md` entry:** `READ briefing digest: CDW → MRU (signals+score) → BuildBriefingDigestUseCase (pure) → MoneyRadar widget → HomeViewModel selector → MoneyRadarWidget`. FORBIDDEN: the digest reading repositories or DAOs; `AnomalyDetector` on Home open; persisting, exporting or sending the digest; feeding it to cloud payload builders; a second pace-band implementation; `layout_config` access outside `DashboardRepository`.
- **Proposed `ENGINE_INTERACTION_MAP.md` row:** Radar + RunwayResult → digest (no writes).

## 4. Pace bands (parity with F5 + the `home-insight-gap` middle band)
`classify(projected: Double?, previous: Double?): PaceBand?`
- Precondition: `RunwayResult.Available`, `projected != null`, `previous != null`, `previous > 0`. Otherwise there is **no pace fact**. Absent reasons: `RUNWAY_UNAVAILABLE`, `PARTIAL_DATA` (F6), `NO_BASELINE`, `NO_PREVIOUS` (null or ≤ 0).
- `diff = projected − previous` on raw doubles. No epsilon, no pre-rounding, and the expressions stay exactly as written:
  - `diff < 0` → LOWER, `difference = −diff`.
  - `diff > previous * 0.2` → HIGHER, `difference = diff`.
  - otherwise (including `diff == 0` and `diff == previous * 0.2` exactly) → ON_TRACK.
- The band is asymmetric: anything below is LOWER, while HIGHER needs more than 20 % above.
- Display guard: if `difference` rounds to 0 minor units of H, the ON_TRACK sentence is rendered and the band stays as classified for diagnostics.
- **Dependency:** ON_TRACK reuses the `home-insight-gap` EN/EL string and key (name U8). The gap fix must land first and ship the classifier as a standalone pure function. If it hasn't landed, inc 1 is BLOCKED rather than re-implementing the band.
- The insight's today-spent fallback is **not** carried over; `PeriodSummary` shows the same number (OD-B6).

## 5. Money rules (align with money-correctness)
- Only MONTH_PACE carries an amount. It is shown in the H of the normalized input it came from, with that currency's code or symbol (U1), and locale-formatted. Display rounding uses H minor-unit digits from the single digits accessor (money-correctness INV-16 / MEC INV-12), HALF_EVEN, render-only (U1). It is never a bare `%.0f` (fixes F8; LEGAL_PATHS `:550`).
- **Never sum or convert at render.** Bills, anomalies, budget, pending and unchecked show no amounts, so nothing in the narrative ever sums across currencies. Mixed-currency bills give a count only.
- Planned and Occurrence items are excluded in inc 1. The only "upcoming" source is Radar bills (§9).
- `monthUncheckedCount` counts **distinct** expense rows with type PURCHASE, `!isNotMine`, `date ∈ [monthStart, now]`, whose normalization result is `Excluded` (no rate, stale-excluded or invalid currency). It is **not** `dataQuality.excludedTransactionCount` (F7). If the normalized input is `Unavailable` (H not Available), the count is unknown and no fact is emitted (unknown ≠ 0; money-correctness INV-9). Excluded rows are never added at their raw amount (money-correctness INV-2).
- After a home-currency switch, the next compute uses the new H throughout. Nothing is cached, so no old-H text can show. Rows without a rate to the new H raise the unchecked count, and pace usually goes absent (F6). Radar re-reads H itself.
- Budget percent and counts are currency-free. Bills Radar converted in a different H during a switch race contain no amount, so they cannot mix currencies.

## 6. Radar availability: score fail-closed, narrative per fact (Q3)
- **Score, urgency badge, ring and CTA keep today's semantics exactly.** For any input, `(urgencyScore, urgencyLevel, primaryCta)` equals the pre-change result. Every F2 failure, including INCOME_EXCLUDED, still yields `UNAVAILABLE` with a null CTA.
- **Components are evaluated independently:**
  - `bills` needs only the patterns read; listing, dates and merchants need no H or FX. BILL_CONVERSION fails the score, not the component.
  - `anomalies` needs only `getActiveAlerts()`.
  - `budget` needs a valid H, the budget read, conversions, complete month purchases (only when an overall budget exists, F3), upcoming-recurring conversion and Monte Carlo (MC). Any failure → `Unavailable(reason)`.
  - An invalid H fails `budget` and the score only.
- **Surviving facts when the score is UNAVAILABLE:** whichever Available components have facts, plus pace, unchecked and pending. Chips may show counts from Available components (ux-flows).
- `limited = true` if any component is Unavailable or the pace is absent with `PARTIAL_DATA`.
- Income unavailability killing the score only for the large-bill bonus is a score-formula issue → OD-B8. Not changed here.

## 7. Narrative precedence (state machine; selector, stateless)
Inputs: `ai: AiLoadState`, `digest`, and Radar `isVisible` from `layout_config`. Re-evaluated on every emission of any input.
| Condition (first match) | Mode | Label / flags |
|---|---|---|
| Radar hidden by the user | — (nothing on Home, no AI on Home) | — |
| `ai is Ready` | AI: the AI text replaces the template sentences; chips, CTA and badge unchanged | existing `home_ai_briefing_title`, ✨, `isAi=true`, runtime status as today |
| `digest.facts` non-empty | TEMPLATE: 1–3 sentences, each with a source tag + tap | new "Summary from your data" (EN/EL), `isAi=false`, no ✨ |
| `limited` | NEUTRAL_LIMITED ("no new signals; some checks unavailable") | same non-AI label |
| `scoreAvailable && urgencyLevel ∈ {YELLOW, RED}` | NEUTRAL_MONITOR (reuses the "monitor spending" meaning) | same |
| else | NEUTRAL_CLEAR | same |
- Idle, Loading, Error and Disabled all → template or neutral, with no error text in the narrative. Ready → Loading (regeneration) falls back to template. That flip is existing behaviour (U11).
- The template ignores every AI toggle (`aiEnabled`, `dashboardBriefingEnabled`, cloud/on-device, mode). The only off switch is hiding Radar in edit mode.
- **Illegal:** template or neutral with `isAi=true` or the AI title; AI text with source tags; AI text and template sentences shown together (OD-B1).
- **ai-fixes B3 "Home card appears regardless":** satisfied because Radar is added unconditionally (CDW `:1262`) and renders even when the score is UNAVAILABLE. "Regardless" means regardless of notification permission, not of the user hiding the widget. Until inc 1 ships, the guarantee rides on `home-insight-gap`. B3 tests that target `NaturalLanguageInsight` move to Radar in inc 1 (U9).
- Retry, cancellation and process death: nothing is stored; the next compute rebuilds the narrative. `CancellationException` propagates (MRU `:154` pattern; FG-10).

## 8. Retiring widget ID `insight`
- Remove `INSIGHT` from `DashboardWidgetRegistry.all` and `idFor`; remove `NaturalLanguageInsight` and `buildNaturalLanguageInsight`.
- Add `retiredIds = setOf("insight")`. `DashboardRepository` drops retired IDs **on read**, so it never writes on read. The next save persists the list without them.
- The unknown-ID branch (F9) never sees retired IDs, so there is no per-emission warning. Truly unknown IDs behave as today.
- Fresh installs never get `insight`. Order gaps are fine: config is sorted by `order`, and `moveWidget` reassigns by index.
- There is no Room or DataStore involvement and no schema change (§12). On downgrade, the old reader re-adds `insight` as a default, which is acceptable.
- **Hid Insight, Radar visible:** the narrative, now including pace and the Home AI briefing, appears in Radar. **Hid Radar, Insight visible:** that user loses the pace line and the Home AI briefing. In both cases there is no preference migration and the user's explicit choices are respected (OD-B4).

## 9. Decisions on brief questions 4, 6, 9
- **Q4 Upcoming:** inc 1 uses Radar bills only.
  - Later, Occurrence and Planned become their own fact type. Counts only for Planned (currency null). Per-currency amounts are never summed.
  - Dedupe against bills by recurring-pattern linkage (field U).
  - Loan-installment overlap: U7.
- **Q6 Per-category exceeded:** not a fact; `BudgetHealthWidget` stays the single owner. The digest carries only Radar's overall-budget risk, which avoids two owners of budget status.
- **Q9 Taps:**
  - Sentence taps and the CTA go through **one** handler per target. Tapping a sentence is the same as the matching CTA action.
  - Anomalies → Transactions over the alert window `[startOfDay(now − 30 d), now]` rather than the current month, which misses older alerts (OD-B2). There is no alerts screen.
  - MEDIUM budget risk → `ANALYTICS_MONTH`, like HIGH and CRITICAL (OD-B3). If rejected, MEDIUM sentences become non-tappable and the CTA stays a no-op.
  - Unchecked → Transactions for the current month. A no-rate filter is U.

## 10. Localization
- Every template sentence, source tag, neutral line and the chrome ("Money Radar" header, "All Clear", "Bills", "Alerts", "View N upcoming bills") comes from string resources with EN and EL.
- Counts use `<plurals>`, which removes the "Bill(s)" hack.
- Dates use the locale formatter: "today" / "tomorrow" for `nearestDaysUntil` 0 / 1, otherwise a locale day-month (EL month form via the platform skeleton).
- Percent uses the locale percent format. Money uses §5.
- Merchant names are verbatim, never inflected or translated. Templates place them where no grammatical case is needed, e.g. after a colon (Greek inflection).
- EL card has no English text except merchant names and the brand (OD-B7). The AI text language is owned by ai-fixes and is out of scope.

## 11. Invariants
| ID | Rule (testable) | Owner |
|---|---|---|
| INV-B1 | The digest is a pure, deterministic function of one compute pass (§3 inputs). Same inputs give an identical digest. No I/O, no AI. | BuildBriefingDigestUseCase |
| INV-B2 | At most one fact per `FactType`; facts in §2 rank order; size ≤ 3; `truncatedCount = available − 3` when positive. | same |
| INV-B3 | When `primaryCta != null`, `facts[0]`'s action equals the CTA's action. | same (test vs MRU) |
| INV-B4 | Only MONTH_PACE has an amount. It carries its H code and is never summed or converted at render. No other fact or sentence contains an amount. | same + renderer |
| INV-B5 | Pace band = §4 expressions exactly. No pace fact unless the §4 precondition holds. | pace classifier |
| INV-B6 | `monthUncheckedCount` = distinct rows per §5. Fact iff ≥ 1. No fact when the normalized input is Unavailable. | CDW normalization |
| INV-B7 | `(urgencyScore, urgencyLevel, primaryCta)` equal the pre-change MRU for every input (parity fixture). | MRU |
| INV-B8 | A component's Unavailable state never removes another component's facts. Bills and anomalies don't depend on H or FX. | MRU |
| INV-B9 | Unavailable ≠ zero: an Unavailable component or unknown count gives no fact, never "0 …", and sets `limited`. | digest |
| INV-B10 | Narrative mode = §7 table, first match. | home selector |
| INV-B11 | Template and neutral never set `isAi=true` or use `home_ai_briefing_title`, and are independent of AI settings. | home selector |
| INV-B12 | Radar is in every compute output. Visibility depends only on `layout_config.isVisible`. | CDW / HomeViewModel |
| INV-B13 | `insight` is absent from the registry and defaults, dropped on read, never warned on, and never re-created. | Registry / DashboardRepository |
| INV-B14 | Each template sentence maps to exactly one fact and shows exactly one source tag. | renderer |
| INV-B15 | Anomaly sentences never contain the alert amount. | digest + renderer |
| INV-B16 | §10 holds; every new key exists in EN and EL; counts use plurals. | renderer + res |
| INV-B17 | Diagnostics are §13 codes only. | digest / selector / MRU |
| INV-B18 | The digest is never persisted, exported, logged as content, sent off-device, or passed to `DashboardBriefingInputBuilder`. | all |
| INV-B19 | BUDGET_RISK iff the budget component is Available, risk is non-null, p ≥ 0.25 and tier ≠ LOW; percent = `(p × 100).toInt()`. | digest |

## 12. Persistence impact: **none**
No Room table, column, version bump, migration or backfill. `layout_config` keeps its JSON shape; the retired ID is filtered on read. Nothing destructive happens to user data. A stored `insight` visibility entry disappears on the next save (preference only; OD-B4). Backups: whether `dashboard_prefs` is included is U4, and the read filter covers restores either way.

## 13. Privacy & diagnostics
- **On screen only:** merchant names (single bill or anomaly), counts, dates, percent, and the pace difference.
- **Never logged, persisted or exported:** merchant, amounts, dates, sentence or AI text, exception messages or classes.
- **Bounded codes:**
  - `BRIEFING_NARRATIVE_MODE` (§1 enum)
  - `BRIEFING_FACTS{rendered 0..3, truncated 0..3}`
  - `RADAR_COMPONENT_UNAVAILABLE{component ∈ BILLS|ANOMALIES|BUDGET|SCORE, reason ∈ RadarUnavailableReason}`
  - `PACE_ABSENT{§4 reason}`
  - `UNCHECKED_SHOWN{bool}`
- Emit on change only, not per emission. Sink: U5. If none exists, debug logging only, with no new store.

## 14. Edge cases
- **Mixed currency:** counts only (AE-B1).
- **Missing or stale FX:** the score is hidden but facts survive (AE-B2, AE-B5).
- **Unavailable vs zero:** INV-B9.
- **Rounding:** nothing is aggregated; there is only the pace display guard (§4).
- **TZ and DST:** days are computed with MRU's start-of-day `daysBetween` and the month range from `TimePeriodUtils` (TZ U10). Not changed.
- **Month-end:** day-1 pace volatility is the same as the insight today (parity). Recurrence dates are owned by the recurring patterns.
- **Edits and deletes** of alerts, patterns or rows: reflected on the next compute.
- **Duplicate alerts:** counted as stored. The alert lifecycle is a non-goal.
- **Receipts:** a pending receipt counts only if it is in `pendingCount` (U12).
- **Empty new user:** all components Available and empty, pace absent → NEUTRAL_CLEAR. Is it LIMITED? No: `NO_PREVIOUS` is not `PARTIAL_DATA`.
- **Blank merchant:** the count variant is used.
- **H Loading:** the budget component and score are Unavailable; bills, anomalies and pending still show.

## 15. Inc 2 framing (Nano narration; not specified for build)
- **Recommend slot references.**
  - Nano receives ≤ 3 facts as `FACT_ID` plus named slots with pre-formatted locale values.
  - It returns ≤ 3 lines `FACT_ID|sentence with {slot}`.
  - The app substitutes the values; source tags and taps come from `FACT_ID`.
  - Invented numbers are then impossible, and Greek normalisation applies only in the fallback mode.
- **Deterministic checks** (any failure → template + code):
  - lines parse;
  - each `FACT_ID` is in the digest and unique;
  - ≤ 3 lines, 1 sentence each;
  - no digits (any script) or EN/EL number words outside slots;
  - every `{slot}` belongs to that fact;
  - script: EL text outside slots ≥ 90 % Greek letters, EN text has 0 Greek letters;
  - length bound.
- **Fallback (raw values) mode:**
  - Strip NBSP, thin space, currency symbol or code and `%`. Apply locale grouping and decimals (EL `1.234,56`).
  - Compare numbers as BigDecimal at H scale (counts and percent as integers) ⊆ digest values.
  - Dates: EN/EL month names (nominative, genitive, abbreviated) and today/tomorrow (σήμερα/αύριο) ⊆ digest dates.
- **Codes:** `PARSE, UNKNOWN_FACT, DUPLICATE_FACT, TOO_MANY_SENTENCES, STRAY_NUMBER, UNKNOWN_SLOT, WRONG_SCRIPT, TOO_LONG, TIMEOUT, BUSY, QUOTA, BACKGROUND, MODEL_UNAVAILABLE`. Cancellation propagates and is not a failure (FG-10).
- **Running rules:** foreground Home open only (ai-fixes B2c `AppForegroundState`); never in a worker; never persisted; never sent to the cloud. It is gated by AI settings, unlike the template.
- **Precedence:** existing AI-briefing Ready → verified Nano narration → template → neutral. The Nano label must be distinct and `isAi=true` (OD-B9).
- **Gate:** Phase A is a device record only. Phase B uses a debug-only harness after ai-fixes B2d with the brief's thresholds and synthetic digests only → `nano-measurement.md`. Fail → park inc 2; if EL fails and EN passes → Nano EN only.

## 16. Acceptance examples (H = EUR unless stated; `now` = Wed 2026-10-14 10:00 local)
- **AE-B1 (INV-B4, B2)** Given confirmed patterns Netflix USD 15.99 due Thu 15, Rent EUR 850.00 due Fri 16, Gym EUR 40.00 due Mon 19, with a USD→EUR rate present, no budget and no alerts. Then one BILLS_DUE fact {count 3, nearest Thu 15 = "tomorrow"}. The sentence has no amount or sum, and the score is Available.
- **AE-B2 (INV-B8, B7)** Same as AE-B1 but with no USD rate. Then the score, badge, ring and CTA are hidden exactly as the old MRU (parity), the BILLS_DUE fact is still {3, tomorrow}, and the code is `RADAR_COMPONENT_UNAVAILABLE{SCORE, BILL_CONVERSION}`.
- **AE-B3 (INV-B5)** previous 1000.00:
  - projected 1150.00 → ON_TRACK (gap string)
  - 1200.00 → ON_TRACK (exact 20 %)
  - 1000.00 → ON_TRACK
  - 1200.01 → HIGHER, difference 200.01, rendered EN "€200.01" (format U1)
  - 999.99 → LOWER 0.01
  - 999.996 → LOWER, renders the ON_TRACK sentence (display guard)
- **AE-B4 (INV-B5)** previous null, 0 or −5, or `RunwayResult.Unavailable` → no pace fact, with `PACE_ABSENT{NO_PREVIOUS | RUNWAY_UNAVAILABLE}`.
- **AE-B5 (INV-B6, B9)** Given this month 10 EUR purchases and 3 USD purchases with no rate (one today, one Oct 2, one Oct 9), plus 1 `isNotMine` USD row with no rate, and an overall budget of EUR 1500. Then UNCHECKED_NO_RATE count = **3**. It is not the summed aggregate metric and not 4. The pace fact is absent (PARTIAL_DATA), the budget component is `Unavailable(SPENDING_EXCLUDED)`, the score is hidden, and `limited` is true.
- **AE-B6 (INV-B8, B19)** Given Rent EUR 850 due Sat 17, a USD deposit this month with no rate, an overall budget with complete purchases at p = 0.40 (MEDIUM), and pending 2. Then the score is hidden (`INCOME_EXCLUDED`) and the narrative is [BILLS_DUE "Rent", BUDGET_RISK MEDIUM 40 %, PENDING_REVIEW 2].
- **AE-B7 (INV-B10, B11)** AI Ready "Quiet day…" → mode AI, AI title, `isAi=true`, no template sentences, chips and CTA unchanged. AI Loading, Error or Disabled (AI off) with facts → TEMPLATE, "Summary from your data", `isAi=false`.
- **AE-B8 (INV-B2, B3)** Given CRITICAL p = 0.82, 2 alerts, 1 bill, pending 2, pace HIGHER. Then the facts are [BUDGET_RISK, ANOMALY_ALERTS, BILLS_DUE], truncated = 2, and `facts[0]`'s action = AdjustBudget = CTA.
- **AE-B9 (INV-B16, B15)** In the EL locale, given 2 bills (nearest tomorrow), 1 alert at "Σούπερ Μάρκετ Α" for 999.00, and pending 4. Then the sentences come from EL plurals with "αύριο", the merchant appears verbatim after a colon, "999" appears nowhere, and there is no Latin text except the brand.
- **AE-B10 (INV-B10)**
  - All components Available and empty, no pace, score GREEN → NEUTRAL_CLEAR.
  - Same, but the alerts read fails → NEUTRAL_LIMITED.
  - No facts with YELLOW → NEUTRAL_MONITOR.
- **AE-B11 (INV-B13)** Saved `layout_config` [{insight,15,true},{money_radar,1,true}] → the read omits insight, there is no "Unknown widget config ID" log, and the next `toggleWidgetVisibility` persists the list without insight. If `money_radar` is hidden, there is no narrative and no AI on Home, and the prefs are untouched.
- **AE-B12 (§5)** H switches EUR → USD and pace was LOWER EUR 30. On the next compute the pace fact (if present) is in USD; EUR rows without a EUR→USD rate are counted as unchecked; no EUR-formatted text remains.
- **AE-B13 (INV-B1, B17)** The same inputs twice give equal digests. The diagnostics captured across AE-B1..B12 contain only §13 enums and integers.

## 17. Unverified
- U1: money formatter name, symbol vs code, and its rounding mode.
- U2: PendingReviewAlert route.
- U3: Analytics month as the pace destination.
- U4: `dashboard_prefs` in backup.
- U5: a bounded-counter sink.
- U6: other `topReasons` consumers (tests, a11y).
- U7: loan installments as confirmed patterns.
- U8: gap-fix key name and copy.
- U9: B3 tests on `NaturalLanguageInsight`.
- U10: `TimePeriodUtils` time zone.
- U11: AI artifact RUNNING after READY.
- U12: `pendingCount` scope.
- U13: refresh trigger at midnight.
- U14: current `MoneyRadarWidget` UNAVAILABLE rendering.

## 18. Non-goals
- No change to the Radar score formula, weights, thresholds or CTA selection. Taps are covered only by OD-B2 and OD-B3.
- No change to the alert lifecycle or `AnomalyDetector`.
- No change to cloud payloads, `DASHBOARD_BRIEFING`, `DailyBriefingWorker` or `PrivacyRuntimeWorkerPolicy`.
- No new fact types in inc 1, including per-category, Planned, today-spent or 1b signals.
- No notifications, writes, recommendations, permissions, export changes or persistence.

## 19. Open decisions (owner)
- **OD-B1** AI Ready replaces the template sentences (literal Q3) vs AI text + top fact. **Rec: literal**; chips, CTA and badge keep the Radar signals. Revisit after dogfood.
- **OD-B2** Anomaly tap (sentence and CTA) → 30-day alert window instead of the current month (a change to shipped behaviour). **Rec: yes.**
- **OD-B3** MEDIUM risk tap → Analytics month (the shipped no-op changes). **Rec: yes.**
- **OD-B4** No layout-preference migration for users who hid Insight or Radar. **Rec: no migration, plus a release note.**
- **OD-B5** Unchecked window = current month. **Rec: current month**; the alternative is the full dashboard window.
- **OD-B6** Drop the insight's today-spent line. **Rec: drop**; PeriodSummary shows it.
- **OD-B7** "Money Radar" stays as a Latin brand in EL, matching the shipped `values-el:33`. **Rec: keep** as the only exception.
- **OD-B8** The score dies on income FX only because of the large-bill bonus. **Rec:** a separate small follow-up (score formula), not inc 1.
- **OD-B9** Inc 2 slot references and the distinct Nano AI label. **Rec: slots**; label copy decided with the inc 2 brief.
- **OD-B10** Plurals via a UI renderer (rec) vs adding plural `DomainTextKeys` support. Final call by the impact-mapper.
