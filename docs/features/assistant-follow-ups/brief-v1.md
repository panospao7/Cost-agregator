# Feature brief: assistant-follow-ups — follow-up questions in the assistant (session-scoped structured context)

Author: feature-strategist (stage 1, ~30 tool calls). Persisted by the orchestrator. Date 2026-10-16. Docs only, post-1.0.
Tags: [V] checked in source this session; [C] carried from earlier docs; [UNVERIFIED] not checked.

## 0. Corrections to the carried framing (scope-deciding, all [V])
- **F-A Live assistant amount filters are NOT raw:** `ExecuteFinancialQueryUseCase.kt:70-104`, `:402-464` fetch rows with `minAmount/maxAmount = null`, convert each to home in memory via `currencyConverter.convertAsOf(..., atMillis = expense.date)`, filter on that; missing-rate rows excluded + `isPartial`. Raw SQL min/max only in `@Deprecated assistantFilteredExpenses`, no callers (`:466-488`). KDocs at `ExecuteFinancialQueryUseCase.kt:30-35`, `FinancialQueryModels.kt:47-82` still say "raw" (stale).
- **F-B RF-3 "preview count ignores min/max" cannot happen:** count branch (`:122-129`) only when both bounds null (min/max branch returns early at `:70`).
- **F-C Real amount gaps:** TOTAL and AVERAGE summed per currency, shown side by side, never converted (`:525-534`, `:504-518`). Assistant min/max uses `convertAsOf` (rate at expense date) while Transactions after money-correctness 1a uses stored snapshot (`AmountSemantics`); drilldown passes min/max + home (`MapFinancialQueryToNavigationUseCase.kt:37-39`) → assistant count and drilldown list can disagree. Missing-rate warning string includes merchant + amount (`:444`), UI only, not persisted.
- **F-D Cloud route already sends conversation history:** `CloudQueryInterpretationService.kt:98` builds its prompt with `OnDeviceQueryInterpretationService.buildPrompt`, which includes "Conversation history" (`OnDevice…:77-79`, `:94-95`) — prior raw user text + assistant display text incl. exact amounts. With `storeConversationHistory=true` + cloud route, prior turns already go out under `QUERY_INTERPRETATION`; only protection = generic `cloudPiiSanitizer.sanitizeText` (`DefaultCloudPayloadRedactor.kt:70-74`; amount removal [UNVERIFIED]).
- **F-E** VM reads `settings.redactBeforeCloud` directly (`AssistantViewModel.kt:242`) — forbidden by `LEGAL_PATHS.md:408` (source must be `EffectiveCloudAiPolicy`) → privacy-security-guardian.
- **F-F Heuristic bugs breaking headline examples:** `resolvePeriod` checks `"month"` (`InterpretFinancialQueryUseCase.kt:301`) before `"last month"` (`:302`) → "food last month" resolves to *this* month (only bare "last month" phrases caught earlier `:186`, `:336`); `previousEquivalentPeriod` uses fixed duration not calendar (`ExecuteFinancialQueryUseCase.kt:496-499`) → October compares to ~Aug 31–Oct 1; no period cue → this month (`:305`); cues English only, no Greek.
- **F-G** Clarification chip tap submits option text ("Breakdown") as a new context-free query (`AssistantViewModel.kt:384-387`).
- **F-H** Previous structured intent already in memory: Summary/Breakdown/TransactionList carry `drilldownIntent` (`FinancialQueryModels.kt:141,148,162`); Clarification/Unsupported carry none; persisted `payloadJson` excludes the intent (`AssistantViewModel.kt:474-517`); without opt-in no session (`createSession` returns null, `AiChatRepositoryImpl.kt:57-59`).
- **F-I** Raw exception text reaches user and history: Unsupported reason uses raw `e.message` (`InterpretFinancialQueryUseCase.kt:48-51`), shown + persisted (`AssistantViewModel.kt:309`, `:513-516`) → privacy-security-guardian.
- **F-J** MIN has no executor (falls through to Unsupported, `ExecuteFinancialQueryUseCase.kt:51-60`); 23 `assistant_*` strings only in `values/strings.xml` (no `values-el`) → Greek UI shows assistant in English.

## Problem
Every assistant question is answered alone unless the user turns on stored history (off by default). "How much on food this month?" then "and last month?" fails or silently answers the wrong question; users retype. Even with history on, follow-ups depend on an LLM re-reading old raw text — on the cloud route that sends earlier answers off the device.

## Target users / situations
Default-settings users (on-device or deterministic fallback) drilling down after a first answer: change period, narrow to merchant, swap category, compare, switch metric. Greek and English users; owner's phone has Nano, others deterministic only. Users who just got a clarification and tap an option.

## Desired behaviour (worked examples)
"Context" = last successful `FinancialQueryIntent` in this session, memory only.
| # | Turn 1 | Follow-up EN / GR | Expected structured result |
|---|---|---|---|
| 1 Period shift | "How much on food this month?" → TOTAL, Food, Oct 2026 | "And last month?" / «Και τον προηγούμενο μήνα;» | TOTAL, Food, **Sep 1–30** (calendar month) |
| 2 Filter refine | (after 1) | "Only at Lidl" / «Μόνο στο Lidl» | TOTAL, Food ∩ Lidl, Sep; empty because Lidl in Groceries → 0 + chip "Lidl, all categories" |
| 3 Filter replace | (after 1) | "What about groceries?" / «Και τα σούπερ μάρκετ;» | TOTAL, **Groceries** (replaces Food), same period |
| 4 Compare | (after 1) | "Compare with the month before" / «Σύγκρινε με τον προηγούμενο» | TOTAL + previous calendar month in `supportingText` |
| 5 Metric switch | (after 1) | "Show them" / «Δείξ' τες»; "The biggest one?" / «Η μεγαλύτερη;» | LIST / MAX, same filters |
| 6 Reset / new topic | (any) | "Top merchants this year" / «Κορυφαία καταστήματα φέτος» | full new intent → context replaced; old filters not inherited |
| 7 Ambiguous | (after 1) | "And before that?" / «Και πριν;»; "And Maria?" (no match) | clarification chips "August", "Same month last year", "New question"; nothing guessed |
| 8 Clarification | "Top spending" → "Breakdown or list?" | taps "Breakdown" | pending intent completed (BREAKDOWN on original filters), not reinterpreted |
- **Suggested follow-up chips** after each answer: ≤3, deterministic from intent + result, localized ("Previous month", "Compare", "By category" when no category filter, "Show transactions", "Remove Lidl filter"). A chip tap sends a **structured intent edit**, never through interpreter or network.

## Deterministic vs Nano vs cloud split (recommended)
1. **Deterministic follow-up merge first = the product:** bounded EN+GR cue lexicon classifies the turn (period shift, add/replace filter, metric/grouping switch, compare, reset/new topic, ambiguous); missing slots inherited, present slots override; every route, no model, same answer on all devices.
2. **Nano optional enhancer** for loose phrasing: context as compact structured block (enums, category ids/names, merchant, relative period token), not raw prior text; output through same validation/enrichment; LLMs never compute numbers.
3. **Cloud inc 1 gets no follow-up context:** deterministic merge runs before provider; unresolved on cloud route → clarification chips. Inc 2 (after privacy review) may send previous intent as structured, aliased, redacted block under `QUERY_INTERPRETATION` (T3) — new content, but less than raw history opted-in users already send (F-D).

## Must-have scope (increment 1, T2, no cloud payload change)
- Session context: last structured intent + any pending clarification intent; memory only, independent of `storeConversationHistory`; cleared on clear-session, new topic, process death.
- Deterministic merge rules for examples 1–8, EN+GR.
- Clarification options resolve against the pending intent (fixes F-G).
- Post-answer follow-up chips (≤3) as structured edits.
- Visible context indicator ("Food · Oct") with one-tap "New question".
- Fix headline-breaking heuristics: "last month" shadowing + calendar-aware previous period (F-F) — changes existing wrong answers (OQ6).
- Greek strings for assistant UI (`values-el`) + chip labels.
- No-behaviour cleanup: delete dead `@Deprecated assistantFilteredExpenses`; correct stale "raw" KDocs (F-A/F-B; closes RF-3 route).
- Bounded diagnostics: one follow-up-kind reason code per turn (e.g. `FOLLOW_UP_PERIOD_SHIFT`, `FOLLOW_UP_AMBIGUOUS`); no query text.

## Later increments
- **Inc 2 (T3):** structured context to cloud; replace raw history in cloud prompt with structured context (OQ3); Nano structured-context prompt + Greek eval on owner's device; align assistant amounts to money-correctness `AmountSemantics` so answer = drilldown (OQ4).
- **Inc 3:** optional home-equivalent line for multi-currency totals; MIN executor; "same period last year"; shared Greek query lexicon with nl-quick-add; retire legacy `NaturalLanguageSearchEngine` route.

## Non-goals
No actions/writes; no persistent memory beyond existing opt-in history (context never written to `ai_chat_*`); no free-form chat/advice; no LLM-computed numbers; no new intents (budgets, subscriptions, forecasts → C3/B12); no change to `storeConversationHistory` default; no new permissions/network; no cross-session recall.

## Existing functionality reused / overlapped
- `ui/screens/assistant/AssistantViewModel.kt` (session/history `:225-253`, clarification resubmit `:384-387`, bounded failure codes `:555-559`); `AssistantSheet.kt` (`SuggestionChip` starter prompts `:288-312`, clarification chips `:198`).
- `domain/ai/usecase/InterpretFinancialQueryUseCase.kt` (`enrichStructuredResult` fill-only-missing `:82-156`); `domain/ai/model/FinancialQueryModels.kt`; `FinancialQueryInterpretationInputBuilder.kt` [not opened]; `data/ai/provider/OnDeviceQueryInterpretationService.kt:76-95` (prompt shared with cloud, `CloudQueryInterpretationService.kt:78,98`); `ExecuteFinancialQueryUseCase.kt` (unchanged in inc 1 except dead code); `MapFinancialQueryToNavigationUseCase.kt`; `data/repository/AiChatRepositoryImpl.kt:57-85` (opt-in persistence untouched); `domain/util/TimePeriodUtils` (`getMonthRange(now, -1)`).
- Overlaps: nl-quick-add (Greek lexicon, period parsing — share, don't fork) [C]; money-correctness 1a (`AmountSemantics`, INV-11 assistant isolation) [C]; ai-fixes none [C].

## Market patterns (observed, 3 searches)
NL questions over own data with period scoping — Copilot Money Assistant (beta) [FAQ](https://www.copilot.money/faq); Monarch via [Motley Fool](https://www.fool.com/money/personal-finance/monarch-money-review), [Walletgrower](https://walletgrower.com/blog/monarch-vs-rocket-money-2026). Assistant "not an agent that takes actions", answers from user data — Monarch per [Finny](https://getfinny.app/blog/monarch-money-ai-assistant-2026) (secondhand). Intent classification + pre-written responses over free generation — [Cleo engineering](https://web.meetcleo.com/blog/designing-internal-tools-ai-generated-chats) (supports deterministic first). Merchant-scoped questions requested — [Copilot feedback](https://feedback.copilot.money/feature-requests/p/copilot-intelligence-ai-assistant). Not observed: documented multi-turn context or follow-up chips → our design.

## Privacy & permissions
- **Inc 1: none to low** — no network, permissions, persistence; context in memory dies with session/process; one bounded diagnostic enum; no prior intent added to cloud payload.
- **Inc 2: high (T3)** — structured context under `QUERY_INTERPRETATION` (merchant aliases/pseudonyms, category aliases, relative period token, enum metric; min/max bucketed or omitted); privacy review; should *narrow* the existing opt-in path that sends raw prior text + exact amounts (F-D).
- Existing issues → privacy-security-guardian (not fixed here unless owner says): F-E direct `redactBeforeCloud` read; F-I raw `e.message` shown + persisted.

## Risks
Rebase on money-correctness 1a (builder + assistant golden SQL test, INV-11 — inc 1 doesn't touch builder; inc 2 after 1a without breaking pins); answer changes from fixing F-F (owner approval, tests); Nano Greek unproven (deterministic EN+GR lexicon must stand alone); over-inheritance (indicator, new-topic rule, "New question"); session lifetime (VM via `hiltViewModel()` in `AssistantSheet`, `MainActivity.kt:648` — survival after dismissal [UNVERIFIED]; process death drops context, fail-safe); confusing zero results on merchant refine (example 2); home currency change mid-session.

## Domain questions for domain-modeler
1. Merge algebra per slot: replace vs add ("and groceries" vs "what about groceries" vs «και»); does merchant refine keep or drop category?
2. Period shift: calendar month/week/quarter/year; "last month" relative to now or to context period ("and the one before?"); shifting month-to-date.
3. Calendar-aware `PREVIOUS_EQUIVALENT_PERIOD` for every period type; partial (to-date) periods.
4. Which metric/grouping combinations accept comparison (today only TOTAL renders it).
5. Context invalidation: new topic, clear, home-currency change, category deleted/renamed, settings toggles off; carry min/max across turns?
6. Clarification state: pending intent + missing slot; what resolves/expires it.
7. Greek cue lexicon ownership (shared with nl-quick-add?); one normalizer for accents/case.
8. Inc 2 amount semantics: `convertAsOf` vs 1a stored snapshot — which wins so answer count = drilldown count.

## Schema flag
None expected (context not persisted; `ai_chat_*`/`payloadJson` unchanged; no new `@RawQuery`).

## Success signal (local, no content telemetry)
Golden follow-up fixture suite (EN+GR, examples 1–8 + negatives) passes on deterministic route with default settings (history off, cloud off); chip taps produce results with no cloud audit entry; clarification taps keep original filters; "food last month" returns September; diagnostics only bounded follow-up codes; manual QA on owner's Nano device records clarification rate per fixture locally.

## Owner questions (with recommended answers)
1. How long does context live? **Rec:** until "New question"/clear-session, sheet dismissal, or process death; no idle timer in inc 1.
2. May the cloud route receive the previous structured intent? **Rec:** not in inc 1 (deterministic merge, else clarification chips); inc 2 after privacy review as aliased, redacted structured block, no exact amounts.
3. For opted-in history users, replace the raw prior text (with exact amounts) the cloud prompt already receives with structured context? **Rec:** yes, in inc 2 (narrows egress).
4. Assistant amount semantics? **Rec:** inc 1 answers unchanged (dead code deleted, KDocs fixed); inc 2 after money-correctness 1a aligns min/max to `AmountSemantics` (count = drilldown); totals stay per currency; optional home-equivalent line inc 3.
5. Greek scope inc 1? **Rec:** Greek follow-up cue lexicon, chips, `values-el` assistant strings now; full Greek first-query parsing shared with nl-quick-add later.
6. Fix wrong-answer heuristics (F-F) here although existing answers change? **Rec:** yes, inc 1, tests pinned + release-note line.

## Recommendation
**Pursue (reshaped).** Inc 1: deterministic EN+GR follow-up merge, structured-edit chips, clarification continuity; context memory-only; no new cloud content; no schema (T2). Cloud context + amount alignment → T3 inc 2 after money-correctness 1a. F-E, F-I → privacy-security-guardian.
