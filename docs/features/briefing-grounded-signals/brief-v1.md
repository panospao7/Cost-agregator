# Feature brief: briefing-grounded-signals — grounded daily briefing (A2; B2, B7 later; folds in roadmap 3b on-device-briefing)

Author: feature-strategist (read-only). Persisted by the orchestrator. Date 2026-10-16. Post-1.0, docs-only.
Legend: [V] verified in repo/docs this pass; [UNVERIFIED] not confirmed. `M/` = `app/src/main/java/com/yourname/expensetracker/`.

## Problem
The Home briefing only rephrases dashboard totals (weather, top 5 categories, budget warnings, upcoming items) [V `M/domain/ai/usecase/DashboardBriefingInputBuilder.kt:39-83`]; it never sees the unusual-spending, pace, price-hike or recurring signals the app already computes. Users with cloud off, or who never turned AI toggles on, get no briefing. Those who do can't tell where a sentence came from.

## Target users / situations
- A user opening Home in the morning or after a purchase who wants "what matters today" at a glance, in Greek or English.
- On-device-only and privacy-strict users, who today get nothing (ai-fixes brief: "on-device-only users' briefing absent until on-device-briefing").

## What "grounded" means (binding for every narration mode)
1. Before any text exists, a deterministic **signal digest** is built from existing engines: typed facts, each with signal type, priority, amount in home currency, period.
2. Every shown sentence maps to exactly one digest fact; no amount, percentage, date, merchant or category unless it is in the digest.
3. A deterministic check runs on any model output: numbers/names not in the digest, wrong language, > 3 sentences, or advice not in the digest → rejected, template shown; rejection logs a bounded reason code, never content.
4. Each sentence is traceable to its source ("based on: unusual expense, 14 Oct") — seed for B2.
5. No qualifying facts → honest neutral line; never invented filler.

## Must-have scope (inc 1: deterministic grounded briefing, no model)
- Digest from the smallest valuable signal set (all already computed):
  1. Month pace vs completed previous month — canonical RP-06 projection used by Home insight [V `ComputeDashboardWidgetsUseCase.kt:1375-1408`].
  2. Budgets CRITICAL/EXCEEDED [V input builder `:46-56`].
  3. Bills and recurring payments due in next N days [V `weather.upcomingItems`, Recurring/Occurrence/Planned `:59-68`].
  4. One high-confidence unusual expense from the recent window [V `domain/analytics/AnomalyDetector.kt:175`; single-currency via `SingleCurrencyAnalyticsInput`/`displayCurrency`].
  5. Pending-review count (no money) [V `:79`].
- Template narration: top 1–3 facts by priority, string resources EN + EL [V `res/values-el/strings.xml` exists]; numbers locale-formatted from the digest.
- Where: existing Home `NaturalLanguageInsight` slot (today AI briefing when ready, else deterministic insight [V `HomeScreen.kt:527-540`]); grounded template replaces the deterministic fallback. Computed **on Home open / dashboard refresh**: no worker, no AICore, no network, no notification permission → closes the on-device-only gap without touching worker gating.
- Honest labelling: template is not AI, never labelled "AI briefing" (ties to honest-ai-labels A7).
- Tap a sentence → screen that owns the fact (budget, upcoming bill, expense, review queue); existing screens only.

## Later increments
- **Inc 1b, more signals:** subscription price increase (`SubscriptionManagerEngine` / `PriceIncrease` in `SmartBillNegotiationEngine.kt`) [V]; budget forecast overshoot (`BudgetForecastingEngine`) [V]; lifestyle inflation (`LifestyleInflationDetector`) [V]. Health score, stress forecast, personality later still (too abstract).
- **Inc 2, on-device Nano narration (absorbs 3b):** Nano only reorders/rephrases the same digest; grounding check mandatory; foreground on Home open only; background/BUSY/quota/timeout/unavailable → silent template fallback; ships only if owner-device measurement passes.
- **Inc 3, B2 "Why this number?":** drill-down into one digest fact or engine output (pace, budget forecast, anomaly, health score), same adapters, always shows data-quality flags (`ForecastDataQuality`, `DataQualityReport`) [V]; template first, Nano optional.
- **Inc 4, B7 period recap:** monthly/yearly recap card from `MonthlyComparisonCalculator`, `CategoryInsightEngine`, `MerchantInsightEngine` [V]; same digest/template; export cover page dropped (critique: new exported content under `ExportPrivacyGate`).
- **Later, separate (T3):** cloud narration of new signals — needs the per-purpose cloud payload allowlist (doesn't exist yet; portfolio §gaps).

## Non-goals
- No change to cloud payload content or `DASHBOARD_BRIEFING` redaction in inc 1–3.
- No change to `DailyBriefingWorker` gating, notification-permission decoupling, hybrid-as-cloud handling or `PrivacyRuntimeWorkerPolicy` — all ai-fixes Part B (B2/B3, G2-3, FG-11) [V ai-fixes brief `:75`, `:118`, `:134`].
- No notification text from new signals (lock-screen exposure needs own privacy review).
- No writes; no budget/savings recommendations beyond engine output; no persona/tone options; no new permissions; no export changes.
- No new anomaly alerts — `AnomalyAlertOrchestrator` stays the only notifier [V `M/domain/alerts/AnomalyAlertOrchestrator.kt`].

## Existing functionality reused / overlapped
- `M/domain/usecase/dashboard/ComputeDashboardWidgetsUseCase.kt` — deterministic pace/today insight = template seed; briefing must not contradict it → one pace source.
- `M/domain/ai/usecase/DashboardBriefingInputBuilder.kt`, `M/data/ai/provider/DashboardBriefingPromptFormatter.kt` — current input/prompt: English-only, no output-language instruction [V `:68-107`]; "Current month spent" printed without currency [V `:79`]; upcoming `Planned` printed as bare `%.0f` without currency [V `:97`, `:87-91`].
- `M/data/ai/provider/HybridDashboardBriefingService.kt` — fallback is `NoOpDashboardBriefingService`, not a template [V `:29`].
- `M/data/ai/provider/OnDeviceDashboardBriefingService.kt` — real Nano call via ML Kit `GenerativeModel` [V]; inc 2 reuses.
- `M/ui/screens/home/HomeViewModel.kt:277-327` — briefing flow gated on `aiEnabled && dashboardBriefingEnabled` [V `:281`], reads `ai_artifacts` by day key, exposes on-device model status [V `:290-297`].
- `M/domain/analytics/*` (AnomalyDetector, SpendingPaceProjection/Calculator, InsightsEngine, MonthlyComparisonCalculator, CategoryInsightEngine, MerchantInsightEngine, AnalyticsCurrencyNormalizer), `domain/budget/*`, `domain/forecasting/MergedRecurringPatternsProvider.kt`, `domain/recurring/RecurringPlanProjectionService.kt`, `domain/subscription/SubscriptionManagerEngine.kt`, `domain/lifestyle/LifestyleInflationDetector.kt` — present [V]. ENGINE_INTERACTION_MAP: SpendingPaceCalculator expects input already normalised by caller [V `:51`].
- ai-fixes Part B — scheduled cloud-route briefing, Home card without notifications, insight split onto own capability; this feature builds on top and starts after it.

## Narration modes and privacy (recommended default: template)
| Mode | What leaves the device | Availability | Greek | Recommendation |
|---|---|---|---|---|
| Template (deterministic) | Nothing | Always, fg or bg | String resources, controlled | **Default for everyone; inc 1** |
| On-device Nano | Nothing | Foreground only; BACKGROUND_USE_BLOCKED / BUSY / battery quota; device-dependent [V ML Kit GenAI docs] | Unproven; Google lists no Prompt API languages [V] | Optional upgrade, inc 2, only if protocol passes |
| Cloud (`CLOUD_AI_DAILY_BRIEFING`, T3) | Today's bucketed/redacted payload; new signals would widen it | Network + consent | Likely good [UNVERIFIED] | Unchanged in inc 1–3; new signals only after allowlist |
Privacy impact: **low** for inc 1–3; **high** only if cloud gets new signals (deferred).

## Money correctness interplay
- Every digest amount in home currency with code; no-rate rows excluded and counted ("2 foreign expenses not included"), never added raw. Today briefing input passes bare Doubles; planned-items prompt line has no currency [V]. Hard-coded "€" in `DashboardFollowThroughEngine.kt:150` (ai-fixes follow-up G2-15) [V doc].
- Depends on money-correctness 1a/1b (approved, parked) — mixed-currency and home-switch comparisons [V money-correctness brief `:9`]; sequence after it.

## Market patterns
- Monarch Weekly Recap: short weekly summary of spending + main drivers, cash-flow trends, subscription/recurring changes, net-worth shifts; Monarch warns its AI may be incomplete/inaccurate — observed: [Monarch help](https://help.monarch.com/hc/en-us/articles/16116906962452-About-Monarch-s-AI-Features), [release notes](https://releasebot.io/updates/monarch).
- Monarch assistant answering from user's own accounts — secondhand: [getfinny review](https://getfinny.app/blog/monarch-money-review-2026).
- Copilot Money, YNAB: no recap/grounded-explanation feature found — observed absence in roundups only, low confidence: [fast.io](https://fast.io/resources/best-ai-budgeting-tools-2026/).
- Price-increase alerts first-class: Rocket Money (>$1.99 / >5%), observed (portfolio).
- Platform: AICore inference limited to top foreground app; background (incl. foreground service) → BUSY/blocked; per-app quota (`BUSY`, `PER_APP_BATTERY_USE_QUOTA_EXCEEDED`); Prompt API beta, structured output alpha — observed: [ML Kit GenAI overview](https://developers.google.cn/ml-kit/genai?hl=en), [Prompt API](https://developers.google.com/ml-kit/genai/prompt/android).
- Inferred: no competitor found verifying LLM output against computed facts → visible grounding trace is a differentiator.

## Fold-in decision for on-device-briefing (3b): fold in as inc 2, behind the measurement gate
1. Inc 1 template on Home open already solves 3b's user problem (on-device-only users see nothing), no AICore risk.
2. Ungrounded Nano would only rephrase totals → Nano worth it only on top of the digest.
3. One feature = one grounding check, one Home slot owner.
→ Close roadmap 3b as "merged into item 4, inc 2".

## Owner-device Nano measurement protocol (synthetic data only; no real expenses leave the phone)
**Phase A — today's build, no new code, ~15 min**
1. Record phone model, Android version + security patch, AICore app version (Settings > Apps > show system > AICore [UNVERIFIED path]).
2. AI settings: on-device-only mode, AI on, briefing on; open Home, note on-device model status (available / downloadable / downloading / unavailable) [V status surface exists].
3. App language Greek; Home in foreground; save 5 manual expenses, ~1 per 30 s; for each note whether a one-line insight appears, its language, rough stopwatch latency (today's Nano insight path; ai-fixes later makes it Nano-only).
4. Repeat once sending the app to background right after saving — expect no insight.
- **Pass A:** status "available" (not just downloadable) and ≥ 3/5 foreground insights appear; mostly-English/broken Greek = recorded Greek risk, not failure.

**Phase B — debug-only fixture harness (slice 0 of inc 2, never in release)**
- 10 fixed synthetic digests × EN + EL = 20 runs, foreground, 1 per 30 s, then 5 rapid back-to-back.
- Grounding ≥ 19/20 pass (zero invented numbers/names; any invented amount fails the sample). Language: 0 wrong-language. Greek fluency: owner rates 1–5, ≥ 4 on ≥ 8/10, no meaning distortion. Latency: warm p50 ≤ 2.5 s, cold first ≤ 8 s, never blocks Home (template first). Quota: BUSY on 20 paced runs ≤ 1 (burst recorded only). Background: 3/3 blocked/BUSY, template shown, no crash.

**Reporting:** owner fills a short table (device, AICore version, per-check pass/fail with counts, median latency, Greek ratings), pastes in chat; orchestrator saves `docs/features/briefing-grounded-signals/nano-measurement.md`. No screenshots of real data, no model text from real expenses; synthetic Phase B outputs may be pasted.
**Decision rule:** Phase A or B fails → inc 2 dropped/parked, template the only on-device mode. Greek fails, English passes → Nano for English locale only, template for Greek.

## Domain questions (domain-modeler)
1. Digest contract: signal types, priority + tie-break, caps, freshness window, one pace source shared with Home insight.
2. Money: normalisation path (`AnalyticsCurrencyNormalizer` vs `NormalizedAnalyticsMoneyAdapter`), no-rate rows, rounding, transfers and installments/loans excluded, behaviour after home-currency switch.
3. Upcoming source of truth: `weather.upcomingItems` vs `MergedRecurringPatternsProvider` vs `RecurringPlanProjectionService` (read-only, no double-counting planned + recurring).
4. Anomaly alignment with `AnomalyAlertOrchestrator` (HIGH-only, cooldowns, "looks normal" feedback) — never contradict a dismissed alert.
5. Persistence: template rendered live (no artifact) vs cached in `ai_artifacts` (24 h TTL, `sourceHash`)? Schema change expected none [UNVERIFIED].
6. Precedence when an ai-fixes B3 cloud artifact and a grounded template both exist for the Home slot.
7. Grounding-check rules for Greek number/date formats (`1.234,56`, month names) and user-supplied category labels.
8. Template gated by `aiEnabled`/`dashboardBriefingEnabled` [V `:281`] or a separate non-AI toggle?

## Success signal (no content telemetry)
- Local-only bounded counters in existing diagnostics: briefings rendered by mode (template/nano/cloud), grounding rejections by reason code, fallbacks by reason (BUSY/background/unavailable/timeout).
- Owner dogfood 14 days: briefing present on every Home open incl. cloud-off + notifications denied; zero sentences without traceable source; zero mixed-currency sums; briefing differs from the plain pace insight on most days.

## Size / risk (rough)
Inc 1 M / T2 (deterministic; dashboard pipeline, money normalisation, Home slot, strings; no worker/cloud/schema expected). Inc 2 M / T2–T3 (foreground gating, quota, CE per FG-10, grounding check). Inc 3 (B2) M / T2. Inc 4 (B7) M / T2. Cloud signals T3, blocked on allowlist.

## Owner questions (with recommendations)
1. Grounded template for everyone, labelled non-AI summary, independent of AI toggles → **yes**.
2. Fold 3b into this feature as inc 2, gated on the protocol → **yes**; run Phase A now, before stage 2.
3. On Home, grounded briefing replaces cloud briefing text for cloud users → **yes for the Home slot**; cloud text stays only where ai-fixes B3 uses it (notification). Shipped-behaviour change → explicit approval.
4. Length → **≤ 3 sentences**, highest-priority fact first.
5. Sequencing → **after ai-fixes Part B and money-correctness 1a**, post-1.0.
6. Inc 1b (price increase, forecast overshoot, lifestyle inflation) soon after inc 1 or wait for usage → **wait 2 weeks of dogfood**.

## Recommendation
**Pursue (reshaped):** lead with a deterministic, verifiably grounded template on Home open; fold in on-device Nano only as a measured inc 2; keep cloud unchanged until a payload allowlist exists.

## Sources
[ML Kit GenAI overview](https://developers.google.cn/ml-kit/genai?hl=en) · [ML Kit GenAI Prompt API (Android)](https://developers.google.com/ml-kit/genai/prompt/android) · [Monarch AI features](https://help.monarch.com/hc/en-us/articles/16116906962452-About-Monarch-s-AI-Features) · [Monarch release notes](https://releasebot.io/updates/monarch) · [getfinny Monarch review 2026](https://getfinny.app/blog/monarch-money-review-2026) · [fast.io AI budgeting tools 2026](https://fast.io/resources/best-ai-budgeting-tools-2026/)
