# ai-evolution — Idea Portfolio

Author: feature-strategist. Date: 2026-10-16. Branch `release-1.0-remediation`. Docs-only, post-1.0.
Baseline: `docs/features/ai-evolution/ai-inventory.md`. Root `A` = `app/src/main/java/com/yourname/expensetracker`.
Size: S / M / L (relative, not days). Risk tier: **T1** = UI/docs/read-only, no new data leaves the device. **T2** = new AI use case or read-only engine wiring, or reuse of an existing cloud purpose. **T3** = Room/schema, workers, new cloud payload purpose, money, lifecycle writes, or privacy gates.

## 0. Verified facts this portfolio relies on (re-checked in source)

1. **On-device-only users get no scheduled briefing, and nobody gets one without notification permission.**
   - `data/ai/worker/DailyBriefingWorker.kt:74` requires `PrivacyCapability.CLOUD_AI_DAILY_BRIEFING`.
   - `domain/privacy/CloudAiPrivacyGate.kt:31-34` denies it when `!policy.cloudAllowed`, and the default is `allowCloudAi=false`.
   - `requiresNotificationPermission = true` (line 75) skips the whole worker block (`domain/workers/WorkerExecutionGuard.kt:174`) when notifications are off.
   - The worker is the only generator of the briefing, so the Home card also depends on notification permission plus cloud consent. That couples generation to posting, which sits uneasily with AGENTS.md ("Notification permission should gate notification posting only"). It goes to the debugger/domain-modeler; it is not resolved here.
2. **Assistant follow-ups only work when history is stored.**
   - `ui/screens/assistant/AssistantViewModel.kt:237-249` passes conversation history only when `storeConversationHistory=true` (default false).
   - Only `data/ai/provider/OnDeviceQueryInterpretationService.kt:77` reads that history.
   - Result: by default every question is stateless.
3. **The assistant's query model is expense-only.** `domain/ai/model/FinancialQueryModels.kt` supports filters + metric (LIST/TOTAL/COUNT/AVG/MAX/MIN) + grouping + previous-period comparison, plus `Clarification`. There are no intents for budgets, subscriptions, upcoming bills, goals or forecasts.
4. **Correction memory already exists deterministically:**
   - `user_corrections` (`data/database/entity/UserCorrection.kt`)
   - `MerchantCategory`, `MerchantAlias`
   - `CategorizationEngine.learnMerchantCategory` (LEGAL_PATHS §Categorization / Merchant Learning)

   `UserCorrection` also holds `notificationTitle`/`notificationText` (sanitized per `RawStorageMode` upstream). That text must never become cloud AI input.
5. **Many deterministic engines already compute what AI would "say":**
   - `domain/analytics/AnomalyDetector.kt`, `SpendingPaceProjection.kt`, `SpendingPersonalityClassifier.kt`, `InsightsEngine.kt`
   - `domain/lifestyle/LifestyleInflationDetector.kt`
   - `domain/forecasting/MonteCarloSpendingSimulator.kt`, `FinancialStressForecastEngine.kt`
   - `domain/budget/BudgetAutopilotEngine.kt`, `BudgetRecommendationEngine.kt`
   - `domain/health/FinancialHealthScoreV2.kt`
   - `domain/location/TravelDetectionEngine.kt`
   - `domain/subscription/SubscriptionManagerEngine.kt`, plus `PriceIncrease` in `domain/negotiation/SmartBillNegotiationEngine.kt:753`

   The briefing (`domain/ai/usecase/DashboardBriefingInputBuilder.kt`) only sees the dashboard "weather", top 5 categories, budget warnings and upcoming items. It sees none of the engines above.
6. **"AI-powered" labels in `docs/features/FEATURES.md` overstate reality.** Bill negotiation "AI-generated scripts" are templated `talkingPoints` / `generateRetentionOffers` in `SmartBillNegotiationEngine.kt:444-491`. Forecasting, savings and analytics are statistical.
7. **There is no free-text or voice expense entry.** `ui/screens/addexpense` has no NL parsing.

---

## 1. Framing — what "smarter" means for this app

1. **Engines compute, AI explains.**
   - Numbers come only from existing deterministic engines and DAO queries. The LLM selects, ranks, phrases and asks follow-ups.
   - The assistant already works this way ("LLM picks intent, never produces numbers"); extend that rule everywhere.
   - This is the main defence against hallucinated money and the reason AI can safely reach budgets, forecasts and subscriptions.
2. **On-device first; cloud is an explicit, per-purpose upgrade.**
   - Gemini Nano via the ML Kit Prompt API (now **beta**, structured output **alpha**) is the default.
   - Cloud stays opt-in, purpose-scoped through `CloudPayloadPolicy`, bucketed and redacted, and fails closed.
   - Every idea below must have a useful on-device or heuristic mode, or it is cloud-only by design and labelled so.
3. **Suggestions, not writes.**
   - AI output is a prefill or a proposal. Applying it always goes through the one existing legal path (`TransactionLifecycleCoordinator`, receipt lifecycle services, recurring coordinator, `CategorizationEngine.learnMerchantCategory`, `SubscriptionManagerEngine`, `GroupTransactionCoordinator`, …) after a user tap.
   - No new autonomous write paths.
4. **Learn from the user, locally.**
   - Corrections, applies and dismissals should change future suggestions on this device, with no telemetry.
   - The app already stores the signals (`user_corrections`, `ai_artifacts` APPLIED/DISMISSED) but does not use them in AI.
5. **Measurable and honest.**
   - Versioned prompts, a golden-fixture eval, truthful labels ("smart rules" vs "AI").
   - A user-visible record of what left the device (metadata only).
   - Without this, "smarter" cannot be shown, and regressions in Nano/Gemini model updates go unnoticed.

---

## 2. Bucket A — make existing AI better

### A1 `briefing-for-everyone`
- **Pitch:** The daily briefing works for on-device-only users, on demand, at a chosen time, and without notification permission.
- **Problem:** With default settings (cloud off), the scheduled briefing never runs (fact 1). Users without notification permission never get the Home card. There is no "refresh" or time-of-day choice.
- **How:**
  - Decouple generation from posting: generate whenever the user opens Home or taps refresh; post the notification only if permitted.
  - Gate the worker on the *route actually used* (on-device vs cloud), not unconditionally on the cloud capability.
  - Let the user pick a delivery time.
  - Give transaction insights their own toggle instead of sharing the briefing's toggles and concrete service (inventory §7).
- **Mode:** Hybrid; on-device by default.
- **Privacy:** Unchanged; nothing new leaves the device. The cloud path keeps bucketed amounts.
- **Builds on:**
  - `domain/ai/usecase/GenerateDashboardBriefingUseCase.kt`, `DeliverProactiveBriefingNotificationUseCase.kt`, `SyncProactiveBriefingWorkUseCase.kt`
  - `data/ai/worker/DailyBriefingWorker.kt`
  - `domain/workers/PrivacyRuntimeWorkerPolicy` (CLOUD_AI → `ai_daily_briefing`)
- **Writes:** `ai_artifacts` only (existing).
- **Size / risk:** S–M / **T3** (worker gating, privacy-runtime worker policy).
- **Depends on:** none. Cheap win.
- **Precedent:** Monarch "Weekly Recap" automatic summary ([Monarch AI features](https://help.monarch.com/hc/en-us/articles/16116906962452-About-Monarch-s-AI-Features), [release notes Dec 2025](https://releasebot.io/updates/monarch)) — observed.

### A2 `briefing-grounded-signals`
- **Pitch:** The briefing says something worth reading because it sees the app's own signals.
- **Problem:** Today it rephrases dashboard totals. It ignores anomalies, pace-vs-budget, price hikes, lifestyle inflation and upcoming spikes the app already computes (fact 5).
- **How:**
  - A deterministic "signal digest" picks the top N facts from existing engines: anomaly, pace projection, subscription price increase, lifestyle inflation, upcoming bills, budget autopilot hints.
  - The LLM only chooses 1–3, orders them and phrases them, citing which signal each sentence came from.
  - If the model is unavailable, a template renders the same digest.
- **Mode:** Hybrid; on-device first.
- **Privacy:**
  - On-device: full facts.
  - Cloud: only through the existing `DASHBOARD_BRIEFING` purpose, with bucketed amounts and category labels. No merchant names unless aliased. Installments/loans excluded (per the loan-installments brief).
  - Adding signals widens the cloud payload, so it needs privacy review.
- **Builds on:**
  - `DashboardBriefingInputBuilder.kt`, `domain/analytics/AnomalyDetector.kt`, `SpendingPaceProjection.kt`
  - `domain/lifestyle/LifestyleInflationDetector.kt`, `SubscriptionManagerEngine.kt`
  - `domain/privacy/CloudPayloadRedactor.kt`
- **Writes:** none beyond artifacts.
- **Size / risk:** M / **T3** (cloud payload content change).
- **Depends on:** A1, C1 (eval), helped by C2.
- **Precedent:** Monarch "AI-powered Insights" (same source as A1); Copilot/Monarch rated for anomaly detection ([wealthnorth comparison](https://wealthnorth.ca/personal-finance/budgeting/best-ai-budgeting-apps-canada/)) — secondhand.

### A3 `suggestion-feedback-loop`
- **Pitch:** AI stops repeating suggestions you rejected and leans on what you accepted.
- **Problem:** APPLIED/DISMISSED statuses are never used for learning. A category suggestion the user overrode for merchant X can reappear.
- **How:**
  - Read-only use of existing signals:
    - suppress re-suggesting a dismissed proposal for the same target/merchant;
    - feed the user's own recent corrections for similar merchants as on-device few-shot examples;
    - show per-capability local acceptance rates in AI settings (counts only).
  - When the user corrects an AI category, route the correction through the existing `CategorizationEngine.learnMerchantCategory` so the deterministic cascade learns too, and the AI is needed less over time.
- **Mode:** On-device (learning signals never go to cloud). Cloud prompts may receive merchant *aliases* only.
- **Privacy:** Low if read-only. Excluding `UserCorrection.notificationText/Title` from any prompt is mandatory.
- **Builds on:**
  - `data/repository/AiArtifactRepositoryImpl.kt`, `data/repository/UserCorrectionRepository.kt`, `MerchantCategoryRepository.kt`
  - `domain/ai/usecase/SuggestCategoryFallbackUseCase.kt`
- **Writes:** merchant learning via `CategorizationEngine.learnMerchantCategory` only (existing legal path). If outcome history needs its own store, that is new storage (T3).
- **Size / risk:** M / T2 (read-only) → **T3** if retention or schema changes.
- **Depends on:** C2.
- **Precedent:** Copilot Intelligence — per-user model trained on the user's reviewed transactions, shows top-two guesses ([Copilot](https://www.copilot.money/dispatch/copilot-intelligence), [Penny Hoarder review](https://www.thepennyhoarder.com/budgeting/budgeting-copilot-money-review/)) — observed.

### A4 `assistant-follow-ups`
- **Pitch:** "And last month?" or "only groceries" works without turning on stored chat history.
- **Problem:** Context is only passed when `storeConversationHistory=true` (default off), and only the on-device interpreter uses it (fact 2). Users must restate every query.
- **How:**
  - Keep an ephemeral in-memory session context: the last structured intent plus the last few turns, never persisted, cleared when the sheet closes.
  - The interpreter refines the previous `FinancialQueryIntent` instead of starting over.
  - Offer 2–3 suggested follow-up chips built deterministically from the result (drill into top category, compare previous period, show list).
  - Optionally retire the legacy `NaturalLanguageSearchEngine` route.
- **Mode:** Hybrid.
- **Privacy:** Cloud receives the previous *structured intent* (category ids/aliases, period), not prior raw text, under redaction.
- **Builds on:** `AssistantViewModel.kt`, `InterpretFinancialQueryUseCase.kt`, `FinancialQueryInterpretationInputBuilder.kt`, `domain/naturallanguage/NaturalLanguageSearchEngine.kt`.
- **Writes:** none.
- **Size / risk:** S–M / T2.
- **Depends on:** none. Cheap win.
- **Precedent:** Monarch Assistant answers NL questions on the user's own data ([Monarch](https://help.monarch.com/hc/en-us/articles/16116906962452-About-Monarch-s-AI-Features)); Revolut AIR chat ([Revolut, Apr 2026](https://www.revolut.com/news/revolut_enters_new_era_of_money_intelligence_with_launch_of_ai_assistant/)) — observed.

### A5 `nano-receipt-items`
- **Pitch:** Real on-device AI for receipt line-item categories (today the on-device path is a heuristic).
- **Problem:** The on-device mode of receipt item categorization is heuristic-only. Users who refuse cloud get weaker results.
- **How:**
  - Use the Prompt API with structured output (alpha) to classify each OCR line item into the user's categories.
  - Keep the heuristic as fallback; strict validation against the user's category set.
- **Mode:** On-device.
- **Privacy:** Improves the privacy posture: less reason to enable cloud.
- **Builds on:** `CategorizeReceiptItemsUseCase` ← `ReceiptSideEffectPlanner`, `ReceiptScanViewModel`; `StrictAiJsonParsing.kt`.
- **Writes:** `ReceiptItemCategorization` rows as a receipt lifecycle side effect (existing path, the one AI path that already writes). No new path.
- **Size / risk:** M / **T3** (receipt lifecycle side effect).
- **Depends on:** C1.
- **Precedent:** Google names "categorize each item after receipt OCR" as a canonical Prompt API use case ([Android blog](https://developer.android.com/blog/posts/ml-kit-s-prompt-api-unlock-custom-on-device-gemini-nano-experiences); [Prompt API docs — beta](https://developers.google.com/ml-kit/genai/prompt/android)) — observed.

### A6 `warranty-on-device-and-confirm`
- **Pitch:** Warranty extraction that works without cloud, and never silently creates records.
- **Problem:**
  - Warranty extraction is cloud-only but enabled by default (`warrantyExtractionEnabled=true`, the odd one out).
  - Cloud results ≥0.75 confidence are persisted without a tap.
  - The deterministic regex path (`AutoCreateWarrantyFromReceiptUseCase`, LEGAL_PATHS §Warranty Auto-Create, ≥70% auto-create) is a separate mechanism with different thresholds.
- **How:**
  - Add a Nano extraction route with regex fallback.
  - Align defaults with the other capabilities.
  - Unify thresholds into a single policy.
  - Optionally switch AI-sourced warranties to "suggested" until confirmed.
  - Later: draft a return/warranty-claim message from the stored record (copy-only).
- **Mode:** Hybrid; on-device first.
- **Privacy:** Moves work off cloud. The claim draft stays on device.
- **Builds on:** `WarrantyTrackerRepository`, `CloudWarrantyExtractionService`, `AutoCreateWarrantyFromReceiptUseCase`, `ui/screens/warranty`.
- **Writes:** warranties via the existing warranty auto-create path. Changing auto-accept behaviour is a behaviour change, so owner approval is needed.
- **Size / risk:** M / **T3**.
- **Depends on:** C1.
- **Precedent:** none found for AI warranty extraction in mainstream budgeting apps. Monarch receipt scanning extracts line items ([Sacra](https://sacra.com/c/monarch-money/)) — adjacent only.

### A7 `honest-ai-labels-and-dormant-cleanup`
- **Pitch:** Say "AI" only where a model runs; wake up or retire dormant AI code.
- **Problem:**
  - Users and docs are told forecasting, savings, negotiation and analytics are "AI-powered" (fact 6).
  - Three "on-device AI" capabilities are heuristics.
  - `PrioritizeReviewItemsUseCase`, `DetectSemanticDuplicateUseCase`, `LOCATION_SUMMARY` and `HybridReceiptAssistService` are dormant.
- **How:**
  - Product labels: "Smart rules" vs "On-device AI" vs "Cloud AI".
  - Wire review prioritization into the review queue order (heuristic, labelled honestly), or delete it.
  - Decide semantic dedupe's role next to `CrossSourceDeduplication`.
  - Hand `LOCATION_SUMMARY` to B11 or remove it.
  - Correct `docs/features/FEATURES.md`.
- **Mode:** n/a.
- **Privacy:** Positive: honest consent.
- **Builds on:** `domain/ai/model/AiModels.kt` (`AiCapability`), `OnDeviceReviewPriorityScorer`, `OnDeviceSemanticDuplicateDetector`.
- **Writes:** none.
- **Size / risk:** S / T1–T2.
- **Depends on:** none. Cheap win.
- **Precedent:** n/a (hygiene).

### A8 `cloud-key-transparency`
- **Pitch:** Users with their own Gemini key know exactly what tier and data use they are on.
- **Problem:**
  - The app relies on a user-supplied Google AI Studio key.
  - Under the Gemini API terms, *unpaid* use outside the EEA/CH/UK may be used to improve Google products and seen by human reviewers. Paid or EEA use is not.
  - The app's consent UI doesn't say this. Settings strings are mostly hard-coded.
- **How:**
  - A plain-language data-use note at key entry and in AI settings (tier-dependent, with a link to the terms).
  - Per-capability model selection (e.g. flash-lite for intent parsing) as a config, not a new provider.
  - Localize `AiSettingsScreen` strings.
- **Mode:** Cloud settings.
- **Privacy:** Positive (informed consent). No new data flows.
- **Builds on:** `ui/screens/aisettings`, `domain/config/AppConfig.kt` `object Ai`, `data/ai/OkHttpCloudProviderConnectionTester.kt`.
- **Writes:** settings only.
- **Size / risk:** S / T2.
- **Depends on:** none (fuller provider work in C5).
- **Precedent:** Gemini API Additional Terms ([ai.google.dev/terms](https://ai.google.dev/terms); summary [Simon Willison](https://simonwillison.net/2024/Oct/17/gemini-terms-of-service)). Revolut states AI partners don't train on user data ([Revolut](https://www.revolut.com/news/revolut_enters_new_era_of_money_intelligence_with_launch_of_ai_assistant/)). The cached terms are ~2024 and must be re-verified before shipping copy.

---

## 3. Bucket B — AI in new functional areas

### B1 `nl-quick-add`
- **Pitch:** Type or say "12.50 souvlaki yesterday cash" and get a pre-filled expense form.
- **Problem:** Cash and offline spending (no notification, no receipt) needs a full form. That is the biggest friction for manual entries.
- **How:**
  - On-device Nano parses free text into amount, currency, merchant, date, category and payment method candidates, validated by existing validators.
  - Optional on-device speech recognition (ML Kit GenAI speech recognition is alpha).
  - Opens the existing add-expense screen pre-filled; the user saves.
  - A deterministic regex parser covers devices without Nano.
- **Mode:** On-device only. Cloud is not needed; recommend cloud-forbidden like notification parsing.
- **Privacy:** Nothing leaves the device. Raw utterance and text are ephemeral and never persisted (same rule as notification text).
- **Builds on:**
  - `ui/screens/addexpense/AddExpenseViewModel.kt`, `domain/ai/validation/AiOutputValidators.kt`
  - `domain/categorization/CategorizationEngine.kt`, `domain/core/money`, `CurrencyConverter`
- **Writes:** expense via the add-expense flow → `TransactionLifecycleCoordinator` → `ExpenseDao.insertAtomic()` after the user taps Save. No new path.
- **Size / risk:** M / T2. Money parsing and decimal locale (Greek comma) are domain questions.
- **Depends on:** C4 (apply contract) nice-to-have.
- **Precedent:** typed/voice NL entry is common in 2026 trackers: Easy Money ("12€ lunch with friends") ([listing](https://mwm.ai/apps/expense-tracker-easy-money/6762201275)), Trace ([App Store](https://apps.apple.com/us/app/-/id6755637211)), ExpenSee ([App Store](https://apps.apple.com/app/id6448993636)). Voice apps usually confirm before saving ([Finny](https://getfinny.app/blog/voice-expense-tracker)). Observed from store listings only. Speech-recognition status per [luciq guide](https://www.luciq.ai/blog/android-on-device-ai-gemini-nano-guide) (secondary).

### B2 `explain-this-number`
- **Pitch:** A "Why?" button on any computed number (anomaly, budget forecast, pace, health score, stress forecast).
- **Problem:** The engines produce scores and projections users can't interpret ("Health 62 — why?", "Forecast says you'll overspend Groceries — because?").
- **How:**
  - Each engine already has its drivers (components, inputs, data-quality flags).
  - A deterministic "explanation facts" adapter exposes them; the LLM writes 2–3 sentences plus one actionable hint.
  - Data-quality warnings (`ForecastDataQuality`, `DataQualityReport`) are always surfaced.
- **Mode:** On-device first; cloud optional via `CLOUD_AI_GENERAL` with bucketed facts.
- **Privacy:** Low on-device. Cloud would need a new payload purpose or reuse with an allowlist (no allowlist exists today, per the loan brief), so cloud is T3.
- **Builds on:** `domain/health/FinancialHealthScoreV2.kt`, `domain/budget/BudgetForecastingEngine.kt`, `domain/analytics/AnomalyDetector.kt`, `SpendingPaceCalculator.kt`, `domain/forecasting/FinancialStressForecastEngine.kt`.
- **Writes:** none.
- **Size / risk:** M / T2 (on-device) / T3 (cloud).
- **Depends on:** C1; shares the digest adapters with A2.
- **Precedent:** Monarch AI Insights ([Monarch](https://help.monarch.com/hc/en-us/articles/16116906962452-About-Monarch-s-AI-Features)) — observed. BudgetZen offers on-device end-of-month predictions and analysis ([hunted.space](https://www.hunted.space/product/budgetzen)) — self-reported.

### B3 `bulk-cleanup-suggestions`
- **Pitch:** "These 14 uncategorized expenses look like Groceries; these 6 merchant names look like the same shop — apply?"
- **Problem:** Uncategorized expenses and cryptic merchant descriptors ("PAYPAL *ABC123", Greeklish variants) degrade every analytic. Today AI help is one item at a time, inside review only.
- **How:**
  - A periodic or on-demand pass groups uncategorized expenses and alias candidates.
  - The deterministic cascade goes first; Nano handles the remainder.
  - The user reviews a grouped list and applies selected rows.
- **Mode:** On-device first; cloud optional with merchant names (sensitive), so cloud should be off by default.
- **Privacy:** Merchant names reveal behaviour. Cloud only behind `CLOUD_AI_GENERAL` plus explicit per-action consent.
- **Builds on:** `domain/categorization/CategorizationEngine.kt` (6-layer cascade), `domain/intelligence/ml/MerchantNormalizer.kt`, `MerchantAlias`, `domain/categorization/AliasLinkResult.kt`, `SuggestCategoryFallbackUseCase.kt`.
- **Writes:**
  - Category: `DefaultExpenseCategoryAssignmentService.assignCategoryIfUnset` or TLC update (LEGAL_PATHS §Category Assignment).
  - Learning: `CategorizationEngine.learnMerchantCategory`.
  - Aliases: alias path. **LEGAL_PATHS has no Merchant Alias section; the domain-modeler must name the owner.**
  - Bulk atomicity is an open question.
- **Size / risk:** M / **T3** (bulk expense mutations).
- **Depends on:** C2, C4.
- **Precedent:** Copilot top-two guesses ([Copilot](https://www.copilot.money/dispatch/copilot-intelligence)); Monarch rename/recategorize rules ([Monarch rules](https://help.monarch.com/hc/en-us/articles/360048393372)) — observed.

### B4 `subscription-auditor`
- **Pitch:** A plain-language subscription check-up: forgotten, overlapping, price-hiked, unused.
- **Problem:** The deterministic pieces exist (candidates, price history, health score, `PriceIncrease`), but users see scores and lists, not a decision.
- **How:**
  - The engines decide what is flagged; AI groups and explains ("3 video streaming services, €34/mo; Netflix rose 18% in March").
  - AI also normalizes descriptor → service identity to catch duplicates.
  - Annual renewals get an upcoming-renewal heads-up, a known weak spot of competitors.
- **Mode:** On-device first.
- **Privacy:** Low on-device. Cloud would see service names, so treat like B3.
- **Builds on:** `domain/subscription/SubscriptionManagerEngine.kt`, `NotificationSubscriptionDetector.kt`, `SubscriptionPriceHistoryDao`, `ui/screens/subscription`.
- **Writes:**
  - Accepting a candidate: `SubscriptionManagerEngine.acceptCandidate` / `validateAndCreate`.
  - Price change: `recordPriceChange` (LEGAL_PATHS §Subscription Mutations).
  - No new path.
- **Size / risk:** M / T2 (T3 if accept/price writes are added).
- **Depends on:** A2 digest helpful.
- **Precedent:**
  - Rocket Money alerts on >$1.99 and >5% increases ([Rocket Money](https://www.rocketmoney.com/learn/personal-finance/is-there-an-app-that-can-tell-me-when-my-subscription-price-increased)).
  - Annual plans handled worst ([Finny 2026](https://getfinny.app/blog/apps-track-subscription-price-increases-2026)).
  - Revolut AIR does subscription management by chat ([Revolut](https://www.revolut.com/news/revolut_enters_new_era_of_money_intelligence_with_launch_of_ai_assistant/)).
  - All observed.

### B5 `negotiation-and-cancel-drafts`
- **Pitch:** Turn the existing templated talking points into a personalized, localized draft (email, chat or call script) the user copies.
- **Problem:** The engine has `talkingPoints` and `retentionOffers` templates (fact 6); docs claim "AI-generated scripts". Users still have to write the message, often in Greek.
- **How:**
  - The LLM rewrites the deterministic facts (provider label, tenure, price history, market reference) into a polite draft in the user's language.
  - It never sends anything, and it never invents competitor prices: only `MarketRateProvider` values may appear.
  - Same for "how to cancel" drafts, generic only.
- **Mode:** On-device first; cloud optional (bucketed price, provider label).
- **Privacy:** Low. The output contains user data, so it is copy-only, with no sharing intent by default.
- **Builds on:** `domain/negotiation/SmartBillNegotiationEngine.kt`, `MarketRateProvider`, `ui/screens/negotiation`.
- **Writes:** outcome recording stays `recordNegotiationOutcome` (existing).
- **Size / risk:** S–M / T2.
- **Depends on:** none.
- **Precedent:**
  - Cleo "Haggle It" drafts letters to providers ([Money Crashers](https://www.moneycrashers.com/cleo-review/), via search summary).
  - Rocket Money negotiates as a paid human service taking 35–60% of first-year savings ([Greenlight](https://greenlight.com/learning-center/reviews/rocket-money-review)).
  - A user-sent draft is the privacy-compatible variant.

### B6 `plan-coach` (budgets + savings goals)
- **Pitch:** A conversational setup and adjust flow: "Based on your last 3 months, here's a budget; here's what reaching €2,000 by June would take."
- **Problem:** `BudgetRecommendationEngine`, `BudgetAutopilotEngine` and the savings engines compute recommendations, but users must translate them into decisions alone. Onboarding a first budget is hard.
- **How:**
  - The engines generate the numbers and candidate plans.
  - The LLM explains trade-offs, asks one or two clarifying questions (e.g. "Is the 600 € in March a one-off?") and presents a plan the user edits and applies.
  - It never changes amounts the engine didn't propose.
- **Mode:** On-device first.
- **Privacy:** Low on-device. Cloud would see the category-level spending profile; it needs a new payload purpose (T3).
- **Builds on:** `domain/budget/BudgetRecommendationEngine.kt`, `BudgetAutopilotEngine.kt`, `domain/savings/SmartSavingsEngine.kt`, `AutomatedSavingsRuleEngine.kt`, `ui/screens/budget`.
- **Writes:**
  - Budgets via `BudgetRepository` (`data/repository/BudgetRepository.kt:657-659`). **No Budget Mutations section exists in LEGAL_PATHS; the legal owner must be confirmed.**
  - Savings sweeps only via `SavingsSweepAcceptanceCoordinator` (LEGAL_PATHS §Savings Sweep Acceptance).
- **Size / risk:** M–L / **T3** (money, budget and savings writes).
- **Depends on:** B2, C3 (for clarifying questions), C4.
- **Precedent:** Cleo "Autopilot" goal roadmap is unverified ([hackr.io](https://hackr.io/blog/cleo-ai)); Monarch reimagined Goals alongside its assistant ([release notes](https://releasebot.io/updates/monarch)). Weak precedent.

### B7 `period-recap` (monthly / yearly, plus export cover summary)
- **Pitch:** A monthly and yearly "story of your money", and an optional narrative cover page on exports.
- **Problem:** The daily briefing is too granular for reflection. Exports for an accountant or partner are raw tables.
- **How:**
  - A deterministic highlights builder (MonthlyComparisonCalculator, category/merchant insight engines, personality classifier) produces the facts; the LLM narrates.
  - For export, a summary page is generated on device and included only if the user ticks it.
  - Respects export privacy modes.
- **Mode:** On-device first.
- **Privacy:** The export summary is new exported data, so it must obey `ExportPrivacyGate` and the redaction modes. No cloud needed.
- **Builds on:** `domain/analytics/MonthlyComparisonCalculator.kt`, `CategoryInsightEngine.kt`, `MerchantInsightEngine.kt`, `SpendingPersonalityClassifier.kt`, `ui/screens/export`.
- **Writes:** artifacts only; the export file (existing export path).
- **Size / risk:** M / T2 (T3 if new export content needs backup or export-mode handling).
- **Depends on:** A1/A2 plumbing.
- **Precedent:** Monarch Weekly Recap with highlights and category trends ([getfinny review](https://getfinny.app/blog/monarch-money-review-2026)) — secondhand.

### B8 `receipt-split-assist`
- **Pitch:** Scan a group-dinner receipt and get a proposed per-person item allocation.
- **Problem:** Splitting itemized receipts in groups is manual.
- **How:**
  - Receipt line items (already extracted) plus group members.
  - The user assigns items (AI proposes defaults, e.g. shared items split evenly) and confirms.
  - Tax and tip are distributed deterministically, never by the LLM.
- **Mode:** On-device (no need for cloud; the receipt image to cloud stays governed by the existing toggles).
- **Privacy:** Low beyond existing receipt flows. Member names stay local.
- **Builds on:** receipt items (`CategorizeReceiptItemsUseCase` outputs), `ui/screens/split`, `ui/screens/groups`, `domain/groups/SettlementCalculator.kt`.
- **Writes:**
  - Group expenses via `GroupTransactionCoordinator` / `SharedExpenseManager` (LEGAL_PATHS §Group Mutations).
  - Expense via TLC.
  - The receipt link via receipt lifecycle services.
  - Cross-coordinator atomicity is a domain question.
- **Size / risk:** L / **T3** (money rounding, groups, receipts).
- **Depends on:** A5, C4.
- **Precedent:** Revolut AI receipt split with review-and-allocate step ([Revolut help](https://help.revolut.com/help/transfers/internal-transfers/splitting-bill-using-receipts/)); Splittz/Splitly/Groobil ([Splittz](https://apps.apple.com/app/id6753856271), [Groobil](https://www.producthunt.com/products/groobil/launches/groobil-ai-powered-bill-splitting)) — observed.

### B9 `installment-recognition` (post loan-installments increment 1)
- **Pitch:** On-device recognition of "ΔΟΣΗ n/N" or installment-conversion notifications, suggesting a link to a plan or an installment declaration.
- **Problem:** After the loan feature ships, users must declare plans and link monthly charges by hand. The loan brief lists this as a later increment.
- **How:**
  - A deterministic regex runs first; Nano is the fallback inside the notification pipeline (cloud forbidden, same as notification parsing).
  - Output is a suggestion card on the captured expense, using bounded reason codes; no raw text stored.
- **Mode:** On-device only.
- **Privacy:** High sensitivity (notification content). Gated by the notification-capture toggle; fails closed.
- **Builds on:** `domain/parser/AppParserRegistry.kt`, `GreekBankParser.kt`, `OnDeviceNotificationParser`, the future plan coordinator from `docs/features/loan-installments/`.
- **Writes:** plan link/declaration through the plan coordinator defined by the loan domain spec (not yet existing; **new path owned by that feature, not by AI**).
- **Size / risk:** M / **T3**.
- **Depends on:** loan-installments increment 1.
- **Precedent:** none found for automatic installment recognition. The Brazilian card-installment practice is noted in the loan brief.

### B10 `greek-e-payment-tax-helper`
- **Pitch:** Track progress toward Greece's "30% of income spent electronically" requirement and flag likely deductible professional-service payments.
- **Problem:** Greek taxpayers face a 22% charge on the shortfall below 30% of income spent electronically. A separate 30% deduction applies on e-paid professional services (capped). The app has the data but no view of this.
- **How:**
  - Mostly deterministic: sum card/electronic expenses (by source/payment method) against the user-entered income, excluding ineligible categories (rent, loans, taxes…).
  - AI's narrow job: suggest which merchants are eligible professional services (lawyer, plumber, gym…) for the user to confirm.
  - Labelled "estimate, not tax advice".
- **Mode:** On-device.
- **Privacy:** Low; income is entered locally.
- **Builds on:** `domain/tax/TaxEstimator.kt`, `TaxConfiguration.kt`, `ui/screens/tax`, LEGAL_PATHS §Business Reports / Tax.
- **Writes:** tags or flags on expenses would go via TLC update; possibly new tax-config storage (schema → T3).
- **Size / risk:** M / T2–T3.
- **Depends on:** C4.
- **Honest note:** this is ~80% rules, 20% AI. Rule values change yearly (2026 values unverified).
- **Precedent:** rules from [To Vima](https://www.tovima.com/society/greece-extends-tax-breaks-for-digital-payments-into-2026) and [PwC tax summaries](https://taxsummaries.pwc.com/Greece/Individual/Deductions). No app precedent found.

### B11 `trip-recap` (uses dormant `LOCATION_SUMMARY`)
- **Pitch:** "Your Lisbon trip: €640 over 4 days, 45% food, most expensive day Saturday."
- **Problem:** `TravelDetectionEngine` detects trips, but nothing summarizes them. `LOCATION_SUMMARY` is a dormant enum.
- **How:** Detected trip window → deterministic per-trip totals (currency-safe) → on-device narration.
- **Mode:** On-device only. Location history is among the most sensitive data, so recommend cloud-forbidden.
- **Privacy:** Uses already-stored location; no GPS or geocoding calls added. Respects `LocationPrivacyGate`.
- **Builds on:** `domain/location/TravelDetectionEngine.kt`, `LocationInsightsEngine.kt`, `AreaSpendingEngine.kt`, `ui/screens/map`.
- **Writes:** artifacts only.
- **Size / risk:** S–M / T2.
- **Depends on:** A7 (decide `LOCATION_SUMMARY`).
- **Precedent:** Revolut AIR trip budgets and travel history ([Revolut](https://www.revolut.com/news/revolut_enters_new_era_of_money_intelligence_with_launch_of_ai_assistant/), [ThePaypers](https://thepaypers.com/fintech/news/revolut-launches-ai-assistant-air-in-ireland-and-europe)) — observed.

### B12 `what-if-affordability`
- **Pitch:** "Can I afford a €600 laptop this month, or in 12 installments?" answered from the user's own forecast.
- **Problem:** The stress forecast and Monte Carlo engines exist, but you can't ask them a question.
- **How:**
  - The LLM maps the question to a structured scenario (amount, date, optional installments).
  - The forecast engines run the scenario read-only.
  - The LLM explains the result with its uncertainty band and data-quality caveat. No advice language ("you should buy").
- **Mode:** On-device first; cloud for interpretation only (structured scenario, bucketed).
- **Privacy:** Low.
- **Builds on:** `domain/forecasting/FinancialStressForecastEngine.kt`, `MonteCarloSpendingSimulator.kt`, `ForecastInputAssembler.kt`, `domain/cashflow/CashFlowCalculator.kt`.
- **Writes:** none (scenario is ephemeral).
- **Size / risk:** M–L / T2.
- **Depends on:** C3; installments variant on loan-installments.
- **Precedent:** Monarch Assistant answers cash-flow questions ([Monarch](https://help.monarch.com/hc/en-us/articles/16116906962452-About-Monarch-s-AI-Features)); YNAB Loan Planner scenarios (non-AI) ([YNAB](https://ynab.com/blog/ynab-loan-planner)).

---

## 4. Bucket C — foundations / enablers

### C1 `ai-eval-harness`
- **Pitch:** Versioned prompts plus synthetic golden fixtures, so "smarter" is measurable and model updates don't silently regress.
- **Problem:**
  - All prompts are "v1". There are no accuracy metrics.
  - Nano model versions change under the app (nano-v3 on Pixel 10), and so does Gemini.
- **How:**
  - Per-capability synthetic fixtures (no real user data) with expected structured outputs.
  - Cloud and fake-provider runs through the existing validation-runner as a new profile.
  - On-device Nano can't run in CI, so use record/replay plus an optional on-device debug-build eval screen that shows only aggregate pass rates.
  - Prompt version bumps invalidate `ai_artifacts` through the existing unique key (promptVersion).
- **Mode:** n/a.
- **Privacy:** None; no real data.
- **Builds on:** `domain/config/AppConfig.kt` `object Ai` (prompt versions), `domain/ai/validation/AiOutputValidators.kt`, `StrictAiJsonParsing.kt`, `scripts/validation-runner.ps1`.
- **Writes:** none.
- **Size / risk:** M / T1–T2.
- **Depends on:** none. Do first.
- **Precedent:** Google promotes automatic prompt optimization for on-device Prompt API use cases (noted in the [InfoQ coverage](https://www.infoq.com/news/2025/11/android-genai-prompt-api), secondhand). Engineering practice rather than a consumer-facing pattern.

### C2 `personal-context-provider`
- **Pitch:** One read-only, privacy-tiered source of "what this user usually does" for every prompt.
- **Problem:** Each AI use case builds context ad hoc. Corrections, aliases and accepted suggestions are not reused (A3, B3).
- **How:**
  - A read-only domain provider assembles bounded context: top merchant → category mappings, aliases, recent accepted/dismissed outcomes, locale, currencies.
  - Two tiers:
    - **on-device tier:** richer;
    - **cloud tier:** aliases and ids only, through `CloudPayloadPolicy`.
  - A hard exclusion list (`UserCorrection` notification text, OCR text, paths) is enforced in tests.
- **Mode:** Both tiers.
- **Privacy:** Medium. It becomes the single place where leakage could happen, so it must be gate-tested.
- **Builds on:** `UserCorrectionRepository`, `MerchantCategoryRepository`, `MerchantAlias`, `AiArtifactRepository`, `domain/privacy/CloudPayloadPolicy.kt`.
- **Writes:** none (read-only). Persisting new learning state would be schema (T3).
- **Size / risk:** M / **T3** (privacy boundary).
- **Depends on:** C1.
- **Precedent:** Copilot per-user model ([Copilot](https://www.copilot.money/dispatch/copilot-intelligence)); Monarch optional household context improves answers ([Monarch](https://help.monarch.com/hc/en-us/articles/16116906962452-About-Monarch-s-AI-Features)).

### C3 `read-only-tool-assistant`
- **Pitch:** Upgrade the assistant from "one intent per question" to a bounded loop over typed, read-only query tools that wrap existing engines.
- **Problem:** The assistant can only query expenses (fact 3). Budgets, subscriptions, upcoming bills, goals and forecasts are unreachable.
- **How:**
  - Declare 6–10 narrow read-only tools: expense query (existing executor), budget status, upcoming bills, subscriptions, savings goals, forecast scenario, explain-metric.
  - The model plans 1–3 calls; the app executes and validates them.
  - The answer may cite only tool-returned numbers, and the UI shows the source.
  - Cloud: Gemini function calling. On-device: no documented Nano tool calling, so emulate with a JSON "plan" over the same tool registry using structured output (alpha).
  - Hard cap on calls; no write tools in this foundation.
- **Mode:** Hybrid.
- **Privacy:** Cloud sees tool *results*, which is more data than today's intent-only design. Tool results to cloud must be bucketed or aggregated per tool, possibly with cloud-allowed tools as a subset. **This is the biggest privacy-model change in the portfolio.**
- **Builds on:** `ExecuteFinancialQueryUseCase`, `MapFinancialQueryToNavigationUseCase`, plus the engines in fact 5.
- **Writes:** none (read-only by construction; any action becomes navigation to an existing screen).
- **Size / risk:** L / **T3**.
- **Depends on:** C1, C2; A4 first.
- **Precedent:**
  - Monarch Assistant uses the user's actual data ([Monarch](https://help.monarch.com/hc/en-us/articles/16116906962452-About-Monarch-s-AI-Features)).
  - Revolut AIR reads only data users already see ([Revolut](https://www.revolut.com/news/revolut_enters_new_era_of_money_intelligence_with_launch_of_ai_assistant/)).
  - Third-party YNAB/Monarch MCP servers expose read tools ([YNAB MCP](https://ai.g2.com/marketplace/tools/ynab-mcpb)).
  - "Read-only workflow first, writes only with validation and approval" ([QVeris guide](https://qveris.ai/zh-cn/guides/best-apis-to-use-with-gemini), secondary).

### C4 `suggestion-apply-contract`
- **Pitch:** One consistent pattern for "AI proposes → user previews → apply through the legal path → undo".
- **Problem:** Each AI card handles apply and dismiss differently (Review, ReceiptScan). New ideas (B1, B3, B6, B8, B10) would multiply that.
- **How:**
  - Shared UI and domain contract: preview diff, explicit apply, revalidation at apply time (the quick-approve precedent), `markApplied` / `markDismissed`, undo where the legal path supports it.
  - The contract *delegates* to existing coordinators; it never owns writes.
- **Mode:** n/a.
- **Privacy:** none.
- **Builds on:** `AiArtifactRepository` (markApplied/markDismissed in `ReviewViewModel`, `ReceiptScanViewModel`), `CategoryAssistCard`, `ReceiptAssistCard`, `DedupeAssistCard`.
- **Writes:** delegated only. **Flag:** must not become a generic write facade; a guard test should enforce delegation.
- **Size / risk:** M / T2.
- **Depends on:** none.
- **Precedent:** Revolut receipt split's "review and confirm before applying" ([Revolut help](https://help.revolut.com/help/transfers/internal-transfers/splitting-bill-using-receipts/)).

### C5 `provider-abstraction-and-reach`
- **Pitch:** More users get on-device AI, and the cloud provider isn't hard-wired.
- **Problem:**
  - Nano needs AICore on a limited set of mostly flagship devices (SDK ≥ 34), so many Android users get only heuristics.
  - The router hard-checks `KEY_GEMINI`; there is a single model and no per-capability choice.
- **How:**
  - Make the router provider-agnostic (provider id + model per capability).
  - Evaluate an opt-in downloadable on-device model (Gemma 3n via MediaPipe LLM Inference) for non-AICore high-end devices. The download is large and it's best on Pixel 8 / S23+ class hardware.
  - Any additional cloud provider must implement `CloudPayloadPolicy` / `PreparedCloudPayload` and get its own consent and terms disclosure (see A8).
- **Mode:** Both.
- **Privacy:** On-device reach is positive. Each new cloud vendor is a new data processor, so the owner decides.
- **Builds on:** `domain/ai/policy/DefaultAiCapabilityRouter.kt`, `HybridRouter`, `data/security/SecureKeyStorage.kt`, `data/ai/provider/internal/*`.
- **Writes:** settings/keys.
- **Size / risk:** L / **T3**.
- **Depends on:** C1 (to compare providers).
- **Precedent:** Jibify uses Gemini Nano ([hunted.space](https://hunted.space/product/jibify)); Fainto bundles a 3.8B model ([Product Hunt](https://www.producthunt.com/products/fainto-private-budget-tracker)); MediaPipe Gemma 3n support ([Google](https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference)). Device scope: [ML Kit GenAI overview](https://developers.google.com/ml-kit/genai). All self-reported or vendor docs.

### C6 `ai-transparency-center`
- **Pitch:** One screen showing, per AI feature: where it runs (on-device / cloud / rules), whether it's available on this phone, and a metadata-only log of what left the device.
- **Problem:** Consent is spread across `AiSettings` and `PrivacySettings`. Users can't see what actually went to the cloud, or why a feature is silent (e.g. the briefing, fact 1).
- **How:**
  - Read from `AiRuntimeDiagnostics` / `PrivacyAuditLogger` metadata: capability, time, route, payload size bucket. Never content.
  - Per-capability availability uses the existing environment monitor and route decision, and the blocked reason uses typed `PrivacyBlocked`.
  - Includes "delete AI artifacts now".
- **Mode:** n/a.
- **Privacy:** Positive. Only existing metadata is shown; check that the audit-log retention window is appropriate.
- **Builds on:** `PrivacyAuditLogger`, `AiRuntimeDiagnostics`, `DefaultAiEnvironmentMonitor`, `ui/screens/aisettings`, `PrivacyBlockedCard`, `DataRetentionWorker`.
- **Writes:** delete via the retention path.
- **Size / risk:** S–M / T2.
- **Depends on:** A7 (labels).
- **Precedent:** Revolut publishes what AIR can access ([Revolut](https://www.revolut.com/news/revolut_enters_new_era_of_money_intelligence_with_launch_of_ai_assistant/)). No finance-app per-call ledger found; this would be distinctive.

---

## 5. Ranked shortlist

1. **A1 `briefing-for-everyone` (S–M, T3).**
   - Highest value per effort. The default configuration (cloud off) silently produces no scheduled briefing, and no Home card at all without notification permission.
   - Fixing that makes the existing investment visible to every on-device user and removes a possible notification-permission invariant tension.
   - Privacy cost is zero (it reduces cloud dependence). T3 only because it touches worker gating.
2. **C1 `ai-eval-harness` (M, T1–T2).**
   - Not user-visible, but every "smarter" claim and every prompt change after it depends on it.
   - It is cheap relative to the risk it removes (Nano and Gemini model drift), uses no real data, and fits the validation-runner discipline.
   - Do it in parallel with A1.
3. **A4 `assistant-follow-ups` (S–M, T2).**
   - Turns a one-shot query box into something that feels like an assistant, without persisting history or widening cloud payloads (structured intent only).
   - Cheap, and the necessary step before C3.
4. **B1 `nl-quick-add` (M, T2).**
   - The most-requested pattern in 2026 trackers and the clearest new user benefit: manual and cash entry in seconds.
   - Fully on-device, writes through the untouched add-expense → TLC path after Save. No new data flows.
   - Main risks are locale money parsing and Nano availability (needs a regex fallback).
5. **A2 + B2 `grounded-signals` / `explain-this-number` (M, T2→T3).**
   - Uses ~10 existing engines nobody reads in prose, which makes them feel "AI-powered" without AI producing numbers.
   - Do the on-device version first and share one digest adapter.
   - Cloud extension waits for a payload allowlist decision, because that's where the privacy cost is.
6. **A3 + C2 `feedback-loop` / `personal-context-provider` (M, T3).**
   - The literal answer to "smarter": suggestions improve from your corrections, on device, like Copilot's per-user model.
   - Ranked below the cheap wins because C2 is a new privacy boundary that must be gate-tested (notification text exclusion).
7. **B4 `subscription-auditor` (M, T2).**
   - Strong market pull (Rocket Money, Revolut AIR). The deterministic detection already exists, so AI adds grouping, descriptor normalization and explanation at low risk.

**Sequencing.**
- **Wave 0 (cheap, independent):** A1, A7 (labels/dormant cleanup), A8 (key transparency), C1.
- **Wave 1 (assistant and entry):** A4, B1, C4 (apply contract, before more apply-type features).
- **Wave 2 (grounded explanations, on-device):** A2/B2 digest adapters, B4, B5, B11. Then decide the cloud payload allowlist before any cloud extension.
- **Wave 3 (learning foundation):** C2 → A3 → B3 (bulk cleanup), A5 (Nano items), A6 (warranty).
- **Wave 4 (bigger bets):** C3 tool assistant → B12 what-if, B6 plan coach. C5 provider reach. B8 split assist.
- **Gated by other features:** B9 after loan-installments increment 1. B10 after the owner confirms tax scope.

---

## 6. Non-goals and rejected ideas

- **Autonomous expense writes by AI** (auto-save parsed notifications, auto-recategorize without a tap). This violates the single legal path / suggestions-not-writes principle, and quick-approve already exists with preview and revalidation. Rejected.
- **Sending raw notification, OCR or receipt text to cloud for better parsing.** It violates privacy fail-closed and AGENTS.md privacy rules. Notification parsing stays on-device only. Rejected.
- **LLM-computed totals, forecasts or budgets.** Hallucinated money is unacceptable, and the engines already compute these. The LLM only explains. Rejected.
- **Cloud-side memory or user profile** (storing a user model with the provider, or a server-side vector store of transactions). There is no backend, and it contradicts the local-first posture. Rejected.
- **Embedding the full transaction history into a local vector DB for RAG.** Typed read-only tools (C3) over existing queries are more precise and auditable for numbers, and avoid a new derived store of sensitive text. Deferred, not needed.
- **Agentic actions** (cancel subscriptions, contact providers, move money, negotiate on the user's behalf). These need network, credentials and liability. Drafts only (B5). Rejected.
- **Emotional or "roast" personas.** Cleo's style fits a brand, not a privacy-first utility. It also risks shaming tone in notifications. A tone option for the briefing could be reconsidered later. Rejected for now.
- **Investment or tax advice** ("buy X", "you should file Y"). Regulatory and liability exposure. B10 is an estimate tracker only. Rejected.
- **Content-level AI telemetry or cloud analytics on suggestion accuracy.** Success is measured locally and by synthetic eval (C1). Rejected.
- **Auto-enabling cloud when on-device is unavailable.** It breaks opt-in consent; the existing router already never falls back to cloud in ON_DEVICE mode. Rejected.

---

## 7. Questions for the owner (these would change the ranking)

1. **Cloud ambition.** Should new AI features be designed on-device-only (accepting that many non-flagship phones get rules-only), or is a cloud-enhanced tier acceptable for features like C3 and B6, which would send aggregated tool results?
2. **Your phone(s).** Which device do you and your test users have? AICore/Nano availability decides whether C5 (non-AICore on-device model) moves up.
3. **Assistant vs proactive.** Do you want the app to *talk to you* (briefing, recaps, alerts: A1/A2/B7) or *answer you* (assistant: A4/C3/B12)? Each path is a different first wave after the cheap wins.
4. **Manual entry volume.** How much of your spending is cash or otherwise uncaptured? High → B1 goes to #2. Low → B3/B4 rise.
5. **Behaviour changes.** May AI-sourced warranties stop auto-accepting at ≥0.75 (A6)? May the briefing run without notification permission and on-device by default (A1)? Both change shipped behaviour.
6. **Greek-specific features.** Is the 30% e-payment tracker (B10) valuable to you, and are you comfortable with yearly rule maintenance?
7. **Groups usage.** Do you actually split itemized receipts with others? If not, B8 (L, T3) drops off.
8. **Gemini key tier.** Do you and your users use a free or paid key, and are you all in the EEA? This decides how prominent A8 must be and whether C5 should add a second cloud provider.

---

**Domain questions flagged for the domain-modeler (not resolved here):**
- Briefing worker gating on the route actually used, and generation decoupled from notification permission (A1).
- Legal owner for budget mutations (no LEGAL_PATHS section; `BudgetRepository.kt:657-659` writes directly).
- Legal owner for merchant-alias writes.
- Bulk-apply atomicity for B3.
- A cloud payload *allowlist* per purpose (none exists; needed for A2, B2, C3).
- `UserCorrection.notificationText/Title` retention and exclusion from AI context (C2).
- Locale decimal parsing for B1.
- Cross-coordinator atomicity for B8.
- Plan coordinator ownership for B9 (from loan-installments).

**Success signals (no content telemetry):**
- On-device-only default users see a briefing (local artifact count > 0).
- Golden-fixture pass rate per capability is tracked across prompt versions.
- The local acceptance rate of AI suggestions rises after A3 (counts only, shown to the user).
- The uncategorized-expense share drops after B3.
- No new `CloudPayloadPurpose` exists without an allowlist test.
- The transparency center shows zero cloud calls for users with cloud off.

**Recommendation: pursue, as a portfolio.** Start with Wave 0 (A1, A7, A8, C1): cheap wins that fix silent gaps and make "smarter" measurable. Commit to bigger bets (C2/C3) only after C1 exists and the owner answers Q1 and Q3.

Key paths referenced (absolute):
- C:\Users\panos\Desktop\Cost-agregator\docs\features\ai-evolution\ai-inventory.md
- C:\Users\panos\Desktop\Cost-agregator\app\src\main\java\com\yourname\expensetracker\data\ai\worker\DailyBriefingWorker.kt (line 74-75)
- C:\Users\panos\Desktop\Cost-agregator\app\src\main\java\com\yourname\expensetracker\domain\privacy\CloudAiPrivacyGate.kt (lines 31-34)
- C:\Users\panos\Desktop\Cost-agregator\app\src\main\java\com\yourname\expensetracker\domain\workers\WorkerExecutionGuard.kt (line 174)
- C:\Users\panos\Desktop\Cost-agregator\app\src\main\java\com\yourname\expensetracker\ui\screens\assistant\AssistantViewModel.kt (lines 237-253)
- C:\Users\panos\Desktop\Cost-agregator\app\src\main\java\com\yourname\expensetracker\domain\ai\model\FinancialQueryModels.kt
- C:\Users\panos\Desktop\Cost-agregator\app\src\main\java\com\yourname\expensetracker\domain\ai\usecase\DashboardBriefingInputBuilder.kt
- C:\Users\panos\Desktop\Cost-agregator\app\src\main\java\com\yourname\expensetracker\data\database\entity\UserCorrection.kt
- C:\Users\panos\Desktop\Cost-agregator\app\src\main\java\com\yourname\expensetracker\domain\negotiation\SmartBillNegotiationEngine.kt (lines 444-491, 753)
- C:\Users\panos\Desktop\Cost-agregator\app\src\main\java\com\yourname\expensetracker\data\repository\BudgetRepository.kt (lines 657-659)
- C:\Users\panos\Desktop\Cost-agregator\docs\architecture\LEGAL_PATHS.md (§Privacy/Cloud AI 375, §Subscription 735, §Categorization 785, §Bill Negotiation 951, §Warranty 981, §Category Assignment 1200)

Sources: [Monarch AI features](https://help.monarch.com/hc/en-us/articles/16116906962452-About-Monarch-s-AI-Features) · [Monarch release notes](https://releasebot.io/updates/monarch) · [Monarch rules](https://help.monarch.com/hc/en-us/articles/360048393372) · [Copilot Intelligence](https://www.copilot.money/dispatch/copilot-intelligence) · [Penny Hoarder Copilot review](https://www.thepennyhoarder.com/budgeting/budgeting-copilot-money-review/) · [Revolut AIR](https://www.revolut.com/news/revolut_enters_new_era_of_money_intelligence_with_launch_of_ai_assistant/) · [Revolut AIR Europe](https://thepaypers.com/fintech/news/revolut-launches-ai-assistant-air-in-ireland-and-europe) · [Revolut receipt split](https://help.revolut.com/help/transfers/internal-transfers/splitting-bill-using-receipts/) · [Rocket Money price alerts](https://www.rocketmoney.com/learn/personal-finance/is-there-an-app-that-can-tell-me-when-my-subscription-price-increased) · [Greenlight Rocket Money review](https://greenlight.com/learning-center/reviews/rocket-money-review) · [Finny price-increase apps 2026](https://getfinny.app/blog/apps-track-subscription-price-increases-2026) · [Finny voice tracker](https://getfinny.app/blog/voice-expense-tracker) · [Finny Monarch review](https://getfinny.app/blog/monarch-money-review-2026) · [Cleo review (Money Crashers)](https://www.moneycrashers.com/cleo-review/) · [Cleo (hackr.io)](https://hackr.io/blog/cleo-ai) · [Easy Money](https://mwm.ai/apps/expense-tracker-easy-money/6762201275) · [Trace](https://apps.apple.com/us/app/-/id6755637211) · [ExpenSee](https://apps.apple.com/app/id6448993636) · [Splittz](https://apps.apple.com/app/id6753856271) · [Groobil](https://www.producthunt.com/products/groobil/launches/groobil-ai-powered-bill-splitting) · [ML Kit Prompt API blog](https://developer.android.com/blog/posts/ml-kit-s-prompt-api-unlock-custom-on-device-gemini-nano-experiences) · [ML Kit Prompt API docs](https://developers.google.com/ml-kit/genai/prompt/android) · [ML Kit GenAI overview](https://developers.google.com/ml-kit/genai) · [InfoQ Prompt API](https://www.infoq.com/news/2025/11/android-genai-prompt-api) · [MediaPipe LLM Inference](https://developers.google.com/edge/mediapipe/solutions/genai/llm_inference) · [luciq Gemini Nano guide](https://www.luciq.ai/blog/android-on-device-ai-gemini-nano-guide) · [Gemini API terms](https://ai.google.dev/terms) · [Simon Willison on Gemini terms](https://simonwillison.net/2024/Oct/17/gemini-terms-of-service) · [Jibify](https://hunted.space/product/jibify) · [BudgetZen](https://www.hunted.space/product/budgetzen) · [Fainto](https://www.producthunt.com/products/fainto-private-budget-tracker) · [YNAB MCP (third-party)](https://ai.g2.com/marketplace/tools/ynab-mcpb) · [YNAB Loan Planner](https://ynab.com/blog/ynab-loan-planner) · [QVeris Gemini tools guide](https://qveris.ai/zh-cn/guides/best-apis-to-use-with-gemini) · [wealthnorth AI budgeting comparison](https://wealthnorth.ca/personal-finance/budgeting/best-ai-budgeting-apps-canada/) · [Sacra Monarch](https://sacra.com/c/monarch-money/) · [To Vima Greek e-payments 2026](https://www.tovima.com/society/greece-extends-tax-breaks-for-digital-payments-into-2026) · [PwC Greece deductions](https://taxsummaries.pwc.com/Greece/Individual/Deductions)
