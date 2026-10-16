# Feature brief v2: briefing-grounded-signals — grounded "what matters today" in Money Radar (A2; folds in roadmap 3b; B2/B7 split out)

Author: feature-strategist (read-only). Persisted by the orchestrator; v1 = `brief-v1.md`; critique = `critique.md`. Date 2026-10-16. Post-1.0, docs-only.
Legend: [V] checked in repo (this pass, v1 or critique); [UNVERIFIED] not confirmed. `M/` = `app/src/main/java/com/yourname/expensetracker/`.

## Changes from v1
1. **Money Radar relationship: merge.** Grounded summary becomes the text body of the existing Money Radar card; separate Insight card retired; no second summary of the same facts (critique B1).
2. Inc 1 signals only from what Home already computes: `MoneyRadarData` (bills, anomalies, budget risk), existing RunwayResult pace with the insight's exact thresholds, `pendingCount`. No `AnomalyDetector` call on Home open (B2).
3. One "upcoming" source: Radar `dueBills`; explicit mixed-currency rules.
4. Slot precedence AI-ready → grounded template → neutral; always present; v1 Q3 dropped (B3).
5. Q1/Q8 resolved: template independent of AI toggles; off switch = widget hide; not labelled AI; never `isAi=true`.
6. 0–20% insight gap split out as its own small bug; this brief only consumes it.
7. Inc 2 Nano: grounding rule 3 narrowed to deterministic checks; Phase A = device record only, no ledger writes; all measurement in Phase B after ai-fixes B2d (B4).
8. Re-homed/split: 3b worker cloud→on-device fallback = parked roadmap stub; B2, B7 = own future briefs.
9. Honest size/risk (reviewer-strict); success baseline = Radar's top reason; localization via Radar `DomainTextKeys` + `<plurals>`.
- Strategist corrections to the critique: insight pace band is NOT ±20% — it is "below previous = lower", "above previous + 20% = higher", 0–20% = no text; Radar reasons have no Greek and its chrome is hard-coded English → localization is real inc-1 scope.

## Problem
Home has two thin "what matters" texts:
- **Money Radar**: ≤ 3 terse English-only bullets [V `ComputeMoneyRadarUseCase.kt:488-559`; EL only `money_radar_unavailable` (`values-el/strings.xml:33`)]; any unconvertible amount → only "unavailable" [V `:171,246,300,318-319,479`].
- **Insight card**: vanishes when this month's pace is 0–20% above last month and nothing spent today [V `ComputeDashboardWidgetsUseCase.kt:1352,1387-1407`]; the AI briefing vanishes with it [V `HomeScreen.kt:527-540`].
Users (esp. Greek-locale, no-exchange-rate, cloud-off) get no reliable, traceable, localized summary of the day.

## Target users / situations
Anyone opening Home wanting 1–3 sentences on what matters today in EN/EL, each saying where it came from; on-device-only, cloud-off, notification-denied users get it without AI.

## What "grounded" means (binding)
1. Deterministic digest of typed facts built from existing outputs before any text.
2. Every sentence maps to exactly one fact; no number/date/name unless the fact contains it.
3. Model output (inc 2 only) gets deterministic checks only: numbers (after locale normalisation) ⊆ digest numbers; dates ⊆ digest dates; ≤ 3 sentences; expected script/language; every sentence carries a known fact ID. Failure → template; reason code logged, never content. "Advice not in digest" and free-text name matching dropped (Greek inflection uncheckable).
4. Each sentence shows a source tag (e.g. "from: recurring bills").
5. No facts → honest neutral line.

## Must-have scope (inc 1: merged Money Radar card, deterministic, no model)
- **Merged card (position 1, full width):** keep header, urgency badge, score ring, chips, primary CTA [V `MoneyRadarWidget.kt:136-296`]; bare reason bullets → narrative area of 1–3 sentences, highest priority first, each with a source tag + tap target reusing Radar's actions [V `HomeScreen.kt:819-839`]. Narrative precedence: (1) AI briefing `Ready` → AI text with existing AI label/`isAi`; (2) else grounded template labelled "Summary from your data" (never AI); (3) else neutral line. Card always added [V `ComputeDashboardWidgetsUseCase.kt:1262`]; Radar UNAVAILABLE → score hidden, narrative still shows Radar-independent facts.
- **What disappears:** separate Insight card (`INSIGHT`, order 15 [V `DashboardWidgetRegistry.kt:58`]) — its pace/today sentence becomes the pace fact; Radar `topReasons` replaced by sentences on the same facts. Remaining minor overlap: chip counts (Bills/Alerts) and full lists in Financial Weather, PendingReviewAlert, BudgetHealthWidget — narrative restates no amounts those widgets show.
- **Facts (all already computed):**
  1. Bills due ≤ 7 days — Radar `dueBills` (confirmed recurring patterns, home currency; Radar fails closed on missing conversion [V `:224-255`]).
  2. Unresolved anomaly alerts, last 30 days — Radar `anomalyAlerts` from persisted alerts [V `:260-281`]; count + merchant only, never the amount (alert has no currency [V `AnomalyAlertRepository.kt:12-13`]).
  3. Overall-budget risk — Radar Monte Carlo `riskTier` MEDIUM/HIGH/CRITICAL when overrun probability ≥ 25% [V `:528-548`] (per-category CRITICAL/EXCEEDED stays in BudgetHealthWidget).
  4. Month pace — RunwayResult `currentPace` [V `:336`], same thresholds as `buildNaturalLanguageInsight`: below previous → "lower"; above previous + 20% → "higher"; 0–20% → "on track" (new, uses the gap fix's string); asymmetric band [V `:1389-1398`].
  5. Pending review count — `pendingCount` [V `:1255`], no money.
  6. Unchecked count — "N expenses not included (no exchange rate)" from dashboard `excludedTransactionCount` [V `:1311`].
- **Money rules:** never sum upcoming across currencies; Planned items not in inc 1 (later: without amount); no-rate rows counted, never added raw; every amount home currency with code, locale-formatted.
- **Localization:** new sentences via `DomainTextKeys` → string resources as Radar does [V `:499-556`, `strings.xml:953-961`], EN + EL, `<plurals>`; inc 1 also localizes hard-coded English chrome ("Money Radar", "All Clear", "Bills", "Alerts", "View N Upcoming Bill(s)" plural hack [V `MoneyRadarWidget.kt:56,77-80,152,253,264,380,385`]); locale dates.
- **Rendered live** on Home open / dashboard refresh; no worker, AICore, network, new permission. Off switch = hide the Radar widget in edit mode [V `HomeViewModel.kt:379,463-473`; `HomeScreen.kt:341`].

## Separate small bug (not this feature): `home-insight-gap`
Insight card returns `null` when projection is 0–20% above baseline (incl. exactly equal), and when there's no pace and no spend today → removes the card and the AI briefing hosted in it [V `:1352,1387-1407`].
- Recommendation: own tiny fix (deterministic dashboard code, not AI) — EN/EL "on track" sentence + neutral line + unit test.
- Sequencing: before ai-fixes B3 (its "Home card appears regardless" promise depends on this widget [V `docs/features/ai-fixes/brief.md:75`]).
- Inc 1 reuses its strings as the pace fact's middle band, then retires the Insight card.

## Later increments
- **Inc 1b:** subscription price increase, budget forecast overshoot, lifestyle inflation [V engines exist; price-increase reliability UNVERIFIED]; after 2 weeks dogfood.
- **Inc 2, on-device Nano narration (absorbs 3b user-facing part):** reorder/rephrase digest only; foreground on Home open only; BUSY/quota/background/timeout/unavailable → silent template; ships only if Phase B passes.
- **Later, separate (T3):** cloud narration of new facts — blocked on per-purpose cloud payload allowlist.
- **Own future briefs (shared digest):** `why-this-number` (B2, drill-down with data-quality flags); `period-recap` (B7, monthly/yearly recap card).
- **Parked roadmap stub:** `briefing-worker-on-device-fallback` (DailyBriefingWorker cloud→on-device fallback that ai-fixes assigned to 3b [V `ai-fixes/brief.md:153`]).

## Non-goals
No change to cloud payload, `DASHBOARD_BRIEFING` redaction, cloud generation, `DailyBriefingWorker` gating, `PrivacyRuntimeWorkerPolicy` (ai-fixes Part B); Radar score formula/weights/CTA logic; `AnomalyAlertOrchestrator`/alert lifecycle. No notification text, writes, recommendations, new permissions, export changes, persona options.

## Existing functionality reused / overlapped
- `M/domain/usecase/dashboard/ComputeMoneyRadarUseCase.kt` — fact source; two priority orders exist: reasons bills → anomalies → budget [V `:496-548`]; CTA budget-critical → anomalies → bills → budget-warning [V `:570-586`].
- `M/ui/components/dashboard/MoneyRadarWidget.kt` — merged card.
- `M/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` (hot file, > 1,400 lines) — pace, insight, `pendingCount`, widget assembly.
- `M/domain/usecase/dashboard/DashboardWidgetRegistry.kt` — `INSIGHT`, `MONEY_RADAR` ids.
- `M/ui/screens/home/HomeScreen.kt:527-540,819-839`, `HomeViewModel.kt:277-327` — AI-briefing flow + label.
- Existing navigation gaps: ReviewAnomalies opens the month's transactions, not the alerts [V `HomeScreen.kt:827-830`]; MEDIUM budget risk does nothing on tap [V `:832-836`].

## Market patterns (from v1)
Monarch Weekly Recap (drivers, recurring changes; warns its AI can be inaccurate) — observed. No competitor seen checking LLM output against computed facts — inferred differentiator. AICore foreground-only, BUSY/per-app quota, Prompt API beta — observed (ML Kit GenAI docs).

## Privacy & permissions impact: low
Inc 1 fully local and deterministic; AI precedence unchanged; nothing new leaves the device. Inc 2 sends nothing off-device. Cloud with new facts = high, deferred.

## Nano measurement (inc 2 gate)
- **Phase A (now, ~5 min, no new code, no ledger writes, no AI generation):** phone model, Android version + patch, AICore version (settings path UNVERIFIED), on-device model status on Home (available / downloadable / downloading / unavailable).
- **Phase B (debug-only harness, after ai-fixes B2d, never in release):** 10 synthetic digests × EN + EL, 1 per 30 s, then a burst; grounding ≥ 19/20 (zero invented numbers); 0 wrong-language; Greek ≥ 4/5 on ≥ 8/10; warm p50 ≤ 2.5 s, cold ≤ 8 s; BUSY ≤ 1/20 paced; background 3/3 blocked, template shown.
- Fail rules: A or B fails → inc 2 parked; Greek fails + English passes → Nano EN only. Reporting: owner pastes a table → `nano-measurement.md`; no real data.

## Domain questions (domain-modeler)
1. Digest contract: fact types 1–6, single priority order (reconcile Radar's two), caps, tie-break.
2. Pace bands: exact boundaries incl. diff == 0; pace Unavailable → no pace fact.
3. Radar UNAVAILABLE: keep whole-card fail-closed for the score while narrative degrades per fact? (Foreign-currency monthly income without rate kills the whole Radar just for a bonus [V `:415,479`].)
4. Upcoming: Radar `dueBills` only; should Occurrence/Planned (`weather.upcomingItems`, mixed currency) join later; avoid double counting with recurring.
5. Retiring `INSIGHT` id: saved dashboard configs, unknown-id handling, users who hid Insight but not Radar.
6. Per-category exceeded budgets: a fact, or left to BudgetHealthWidget?
7. Persistence: rendered live, no artifact; schema change expected none [UNVERIFIED].
8. Inc 2: Greek number/date normalisation for checks; Nano emits fact-slot references rather than raw values?
9. Tap targets: anomaly-alert destination; MEDIUM-risk action.

## Success signal (no content telemetry)
- Local bounded counters: rendered-by-mode (template / AI / neutral), fallback + rejection reason codes, unchecked-count shown.
- Owner dogfood 14 days: narrative present on every Home open incl. 0–20% days, no-rate state, cloud off, notifications denied; zero sentences without source tag; zero mixed-currency sums; zero English strings on the card in EL.
- vs baseline (Radar's old top reason): no fact lost; on most days the narrative adds a pace, pending or unchecked fact Radar didn't show.

## Size / risk (honest)
Inc 1 M–L / T2, reviewer-strict (money formatting, Home slot precedence, shipped widget merge + retirement, EN/EL plurals, hot file `ComputeDashboardWidgetsUseCase.kt`; loan-installments overlap UNVERIFIED). `home-insight-gap` S / T1–T2. Inc 2 M / T2–T3 (Phase B, FG-10 cancellation).

## Owner questions (merged, with recommendations)
1. Merge summary into Money Radar vs lighter option (grounded text in Insight slot, Radar unchanged) → **merge**.
2. Retire the separate Insight card once merged → **yes** (shipped-behaviour change; explicit approval).
3. Move AI briefing display into the merged card's narrative area (same precedence, cloud generation unchanged) → **yes** (v1 Q3 withdrawn).
4. Template for everyone, independent of AI toggles, off switch = hide Radar widget, labelled non-AI → **yes**.
5. 0–20% gap → **standalone `home-insight-gap` fix**, before ai-fixes B3.
6. Localize Radar chrome + reasons (EN/EL) inside inc 1 → **yes**.
7. Length → **≤ 3 sentences**.
8. Sequencing → **after ai-fixes Part B and money-correctness 1a**; Phase A now, Phase B after B2d.
9. 3b → **folded in as inc 2**; worker fallback → **parked stub**; B2/B7 → **separate briefs**.
10. Inc 1b → **wait 2 weeks dogfood**.

## Recommendation
**Pursue (reshaped):** merge a deterministic, source-tagged, localized summary into Money Radar from facts Home already computes; fix the 0–20% gap separately; add Nano only after Phase B passes.

## Sources
[ML Kit GenAI overview](https://developers.google.cn/ml-kit/genai?hl=en) · [ML Kit Prompt API](https://developers.google.com/ml-kit/genai/prompt/android) · [Monarch AI features](https://help.monarch.com/hc/en-us/articles/16116906962452-About-Monarch-s-AI-Features) · [Monarch release notes](https://releasebot.io/updates/monarch)
