# Feature brief v2: assistant-follow-ups — follow-up chips and session context in the assistant

Author: feature-strategist (stage 1b, single reshape pass, 9 tool calls). Persisted by the orchestrator. Date 2026-10-16. Docs only, post-1.0.
Base `brief-v1.md`; applies `critique.md` + Gate 1 decisions (`workflows/active/assistant-follow-ups-handoff.md`, binding). [V] checked in source by v1/critique; [C] carried from other feature docs.

## 0. Changes from v1
- Track 0 defects (F-D, F-I, F-F, F-G, MIN, F-E) now **ai-fixes Part C (C-1..C-6)** (`workflows/active/ai-fixes-handoff.md` Part C), landing first.
- Session lifetime fixed: `AssistantViewModel` Activity-scoped [V] → context lives exactly as long as the visible transcript (Gate 1 Q4).
- Inc 1 chips only; free-text EN+GR → inc 1b on shared nl-quick-add normaliser (Q3, Q6).
- Dropped: structured context to cloud in any increment (Q5).
- Moved out: `AmountSemantics` alignment → money-correctness follow-up; unsalted SHA-256 aliases → `cloud-alias-hashing`; `values-el` for existing assistant strings → l10n track (only new chip/indicator strings here).
- Critique corrections: F-G already gives wrong answers (clarification "Last month" drops every filter); empty result renders `"0.00 N/A"` (`ExecuteFinancialQueryUseCase.kt:505`); comparison renders only for TOTAL; F-D broader (exact amounts) — fixed by C-1.

## 1. Dependency and ownership boundary
| Concern | Owner | This feature |
|---|---|---|
| C-3 calendar previous period; "last X" not shadowed | ai-fixes Part C | **reuses** C-3's previous-period function for "Previous period"/"Compare" chips; never forks |
| C-4 clarification option completes the *pending* intent, filters kept | ai-fixes Part C (minimal fix + tests) | **extends**: pending clarification intent becomes one slot of session context; completed intent becomes "last successful intent"; C-4 tests stay green; no second completion path |
| C-1 assistant turns out of cloud prompt; C-2 bounded reasons | ai-fixes Part C | chip-origin turns follow rule P-1 (§6) |
| C-5 MIN, C-6 F-E | ai-fixes Part C | no MIN chip unless C-5 adds an executor |
| `AmountSemantics` alignment (count = drilldown) | money-correctness follow-up | untouched; chip results inherit today's `convertAsOf` (§10) |
| Typed empty result replacing `"0.00 N/A"` | **owner Q1** | chips make empty results frequent → needed before inc 1 ships |
| Dead `@Deprecated assistantFilteredExpenses` + stale "raw" KDocs | **this feature, inc 1** | not in money-correctness (its handoff routed it out) nor Part C; trivial, private |
| Unsalted alias hashing | `cloud-alias-hashing` | out of scope |
Sequence: Release 1.0 → ai-fixes (incl. Part C) → money-correctness 1a → **assistant-follow-ups inc 1** → nl-quick-add slice B → **assistant-follow-ups inc 1b**.

## 2. Problem
Each assistant answer stands alone; the obvious next question ("and last month?", "by category?", "show them") needs a full retype and loses filters. Stored history helps only on the LLM route, is off by default, and on the cloud route used to send prior turns off the device.

## 3. Target users / situations
Default-settings users (history off, cloud off; deterministic or on-device route) drilling down after a first answer (previous period, comparison, category breakdown, underlying transactions, one filter removed); users who just answered a clarification; Greek, English, Spanish UI users (chip labels in all three); owner's Nano phone and deterministic-only phones behave the same in inc 1.

## 4. Desired behaviour (chip-based worked examples)
"Context" = last successful `FinancialQueryIntent` + any pending clarification intent, memory only.
| # | Turn 1 (result) | Chips (EN / GR) | Tap → edit → result |
|---|---|---|---|
| 1 | "How much on food this month?" → TOTAL, Food, Oct 1–16 2026, 142.30 EUR | Previous month / «Προηγούμενος μήνας» · Compare / «Σύγκριση» · Show transactions / «Εμφάνιση συναλλαγών» | **Previous month** → TOTAL, Food, **Sep 1–30** (C-3); indicator "Food · Sep" |
| 2 | after 1 | same | **Compare** → TOTAL, Food, Oct 1–16 + previous equivalent period in supporting text (MTD vs full Sep or Sep 1–16 = Q3); either side > 1 currency → "Comparison unavailable: multiple currencies" / «Η σύγκριση δεν είναι διαθέσιμη: πολλά νομίσματα»; never cross-currency or zero delta |
| 3 | "Spending at Lidl last month" → TOTAL, Lidl, Sep → empty | Remove Lidl / «Αφαίρεση Lidl» · Previous month · New question / «Νέα ερώτηση» | typed empty "No matching transactions · Sep 2026" / «Καμία συναλλαγή · Σεπ 2026» (not "0.00 N/A"); **Remove Lidl** → TOTAL no merchant, Sep |
| 4 | "Total this year" (no category filter) | By category / «Ανά κατηγορία» · Compare · Show transactions | **By category** → BREAKDOWN by category, same period/filters; once grouped "Compare" hidden (TOTAL-only) |
| 5 | "Top spending" → clarification "Breakdown or list?" | (clarification options) | "Breakdown" → C-4 completion (BREAKDOWN on original filters); completed intent becomes context; normal chips appear |
| 6 | any | indicator "New question" / «Νέα ερώτηση» | context cleared, indicator hidden; next typed query fresh; transcript stays, older answers show no chips |
| 7 | after 1, user deletes "Food" elsewhere, returns | none | context invalidated: indicator removed, chips hidden, "Context cleared" note + bounded reason code; nothing computed against a missing id |

## 5. Scope
### Inc 1 (T2) — chips + session context
- **Context holder:** last successful structured intent + pending clarification intent (C-4); memory only, independent of `storeConversationHistory`, **never written to `ai_chat_*` or `payloadJson`**; lifetime = visible transcript of the Activity-scoped VM; cleared by "New question", clear-session, process death; never restored from `ai_chat_*` (after process death a restored opt-in transcript shows **no chips**, no indicator).
- **Chips:** ≤3 deterministic follow-up chips on the **latest answer only**; each tap = structured intent edit (no interpreter, LLM, network) through the existing executor + `MapFinancialQueryToNavigationUseCase`; "New question" in the indicator, not counted.
- **Chip catalogue (domain-modeler finalises):**
| Chip | Shown when | Edit |
|---|---|---|
| Previous {period} | intent has a calendar-resolvable period | period → C-3 previous equivalent calendar period; other slots kept |
| Compare | metric TOTAL, no grouping, period has a previous equivalent, result not empty | set comparison on same intent; mixed-currency side → "unavailable" |
| By category | no category filter, not already grouped by category; metric TOTAL or AVERAGE | grouping = category (BREAKDOWN); others kept |
| Show transactions | result count > 0, metric not LIST | metric = LIST; others kept (vs drilldown navigation = Q4) |
| Remove {filter} | intent has merchant, category or amount filters | drop one filter by precedence; promoted first when result empty |
| New question (indicator) | while context exists | clear context |
- **Priority when > 3 qualify:** empty → Remove {filter} > Previous {period}; non-empty → Compare > By category > Show transactions > Previous {period}.
- **Context indicator:** "Food · Oct" / «Φαγητό · Οκτ» from category, merchant, period, rendered from ids at display time (rename shows new name); carries "New question".
- **Invalidation:** category in intent deleted/merged; home currency changes; assistant/AI disabled; clear-session. Rename keeps id, refreshes label.
- **Empty result for chip results:** typed "no matching transactions" with period label; "Remove {filter}" promoted (owner Q1).
- **Strings:** chip labels, indicator, "Context cleared", "Comparison unavailable" in `values`, `values-el`, `values-es`.
- **Diagnostics:** one bounded follow-up code per chip turn/invalidation on the existing bounded-code path (`AssistantViewModel.kt:555-559`): `FOLLOW_UP_PREVIOUS_PERIOD`, `FOLLOW_UP_COMPARE`, `FOLLOW_UP_BY_CATEGORY`, `FOLLOW_UP_SHOW_TRANSACTIONS`, `FOLLOW_UP_REMOVE_FILTER`, `FOLLOW_UP_NEW_QUESTION`, `FOLLOW_UP_CONTEXT_INVALIDATED_{CATEGORY|HOME_CURRENCY|DISABLED}`; never names, amounts, query text.
- **Cleanup:** delete private dead `assistantFilteredExpenses`; fix stale "raw" KDocs (`ExecuteFinancialQueryUseCase.kt:30-35`, `FinancialQueryModels.kt:47-82`); closes RF-3.
### Inc 1b (T2) — free-text follow-ups
EN+GR free-text follow-up lexicon on the **shared nl-quick-add normaliser** (accents, final sigma, case; nl-quick-add C1 after slice B); cue lexicon **separate** from nl-quick-add's expense lexicon (period shift, add/replace filter, metric/grouping switch, compare, reset/new topic, ambiguous); present slots override, missing inherited; ambiguous → clarification chips, never guess; golden fixtures v1 ex. 1–7 (EN+GR) + negatives (new topic doesn't inherit; unknown name → clarification); optional local Nano with structured context (enums, category ids/names, merchant, relative period token), never raw prior text, **on-device only**; cloud route: deterministic merge first, else clarification chips; **context never sent to cloud**.

## 6. Privacy & permissions
- Inc 1: **none** (no network, permissions, persistence, new cloud content; bounded enums only).
- **Rule P-1 (domain-modeler to confirm):** chip-origin user turns (e.g. "Remove Lidl") kept out of interpreter history on on-device and cloud routes (context is structured) — else a merchant name from a chip label could reach the cloud via USER turns C-1 keeps. Persisting chip turns under opt-in history follows existing rules.
- Inc 1b: none to low (Nano on-device; cloud payload unchanged).
- Existing issues routed: F-D, F-I, F-E → Part C; alias hashing → `cloud-alias-hashing`.

## 7. Non-goals
No actions/writes, free-form chat/advice, LLM-computed numbers; no new intents (budgets, subscriptions, forecasts), no MIN chip, no "same period last year" in inc 1; no cross-session recall or context restored from `ai_chat_*`; no `storeConversationHistory` default change; no structured context to cloud ever; no home-equivalent line for multi-currency totals; no re-fixing Part C defects; no translation of existing assistant strings (l10n track).

## 8. Existing functionality reused / overlapped
`ui/screens/assistant/AssistantViewModel.kt` (Activity-scoped; session/history `:225-253`; clarification `:384-387` reworked by C-4; bounded codes `:555-559`; display text `:466-472`); `AssistantSheet.kt` (`SuggestionChip` starter prompts `:288-312`, clarification chips `:198` — reuse component); `domain/ai/model/FinancialQueryModels.kt` (Summary/Breakdown/TransactionList carry `drilldownIntent` `:141,148,162` — context source); `ExecuteFinancialQueryUseCase.kt` (unchanged except cleanup + possibly typed empty result; TOTAL-only comparison; previous period from C-3); `MapFinancialQueryToNavigationUseCase.kt` (drilldown); `domain/util/TimePeriodUtils` via C-3; `data/repository/AiChatRepositoryImpl.kt:57-85` (opt-in persistence untouched); inc 1b: nl-quick-add normaliser (`workflows/active/nl-quick-add-plan.md:18`, C1/slice B) [C].

## 9. Market patterns (observed)
Period-scoped NL questions over own spending — Copilot Money Assistant (beta) [FAQ](https://www.copilot.money/faq); Monarch [Motley Fool](https://www.fool.com/money/personal-finance/monarch-money-review), [Walletgrower](https://walletgrower.com/blog/monarch-vs-rocket-money-2026); Monarch assistant takes no actions (secondhand) [Finny](https://getfinny.app/blog/monarch-money-ai-assistant-2026). Intent classification + pre-written responses (deterministic-first) — [Cleo engineering](https://web.meetcleo.com/blog/designing-internal-tools-ai-generated-chats). Merchant-scoped questions requested — [Copilot feedback](https://feedback.copilot.money/feature-requests/p/copilot-intelligence-ai-assistant). General guidance: 2–4 answer-tied follow-up chips; anti-patterns generic chips, chips rephrasing the question, chips whose sent query differs from label — [AI UX Playground](https://aiuxplayground.com/pattern/follow-up); follow-ups on most recent message only — [Open WebUI](https://docs.openwebui.com/features/chat-conversations/chat-features/follow-up-prompts); dedicated chip component — [assistant-ui](https://www.assistant-ui.com/docs/ui/follow-up-suggestions); chips suit screen devices — [Google conversation design](https://developers.google.cn/assistant/conversation-design/suggestions?authuser=3). Not observed in budgeting apps specifically ([fast.io](https://www.fast.io/resources/best-ai-budgeting-tools-2026.md), [Lunch Money](https://lunchmoney.app/blog/ai-and-personal-finance-a-practical-guide-for-budgeters)).

## 10. Risks
Part C dependency (inc 1 can't start before C-3, C-4; generalising C-4 must keep its tests green); drilldown parity (chip result may differ from drilldown list — `convertAsOf` vs stored snapshot, F-C — until money-correctness follow-up; note in feature release notes); empty-result frequency (must not ship with `"0.00 N/A"`, Q1); over-inheritance/stale context (indicator, "New question", latest-answer-only chips, invalidation); month rollover mid-session (absolute range vs relative token, Q2); process death (navigation persisted `NavigationController.kt:30` → Assistant may return with empty VM — safe, no chips; spec it); inc 1b Greek normalisation + Nano Greek unproven (deterministic lexicon stands alone).

## 11. Success criteria (local, no content telemetry)
Golden chip fixtures pass on deterministic route with default settings (eligibility, edit, priority, invalidation; mixed-currency Compare "unavailable"); chip tap → no cloud audit entry, no `ai_chat_*` row beyond existing opt-in history; chip-origin turns never in interpreter history (P-1); empty chip result shows typed empty state, never "0.00 N/A"; C-4 tests stay green; only bounded `FOLLOW_UP_*` codes; inc 1b EN+GR fixtures ex. 1–7 + negatives pass with no model.

## 12. Domain questions for the domain-modeler
1. Chip eligibility + edit algebra per metric/grouping; "Remove" precedence (merchant > amount > category?); chip edit keeps min/max?
2. Period in context: absolute range vs relative token; "Previous {period}" for MTD and custom ranges (hide?); month rollover.
3. Comparison rendering: TOTAL-only gating; partial period vs full previous or aligned day range; mixed-currency rule ("unavailable" if either side > 1 currency or sides differ); empty side display.
4. "Show transactions": in-assistant LIST edit, drilldown navigation, or both.
5. Invalidation triggers + observation (category delete/merge, home currency, assistant disabled); rename; cloud/AI toggles (rec: only "assistant disabled").
6. Pending clarification slot vs C-4; expiry (new typed query, "New question").
7. Rule P-1: chip-origin turns out of interpreter history; persistence under opt-in history.
8. Inc 1b merge algebra per slot (add vs replace, «και»); cue lexicon vs nl-quick-add normaliser ownership.

## 13. Schema flag
None (context not persisted; `ai_chat_*`/`payloadJson` unchanged; no new `@RawQuery`).

## 14. Owner questions (new only)
1. Who owns the typed empty result replacing `"0.00 N/A"` on the assistant path? **Rec:** assistant-follow-ups inc 1 — display-only typed "no matching transactions" result on the assistant executor path, no conversion/money-semantics change; money-correctness keeps `AmountSemantics` alignment.

## 15. Recommendation
**Pursue.** Inc 1 (T2): memory-only context tied to transcript; ≤3 metric-gated deterministic chips as structured edits; indicator + "New question"; invalidation; typed empty result; EN/el/es labels; one bounded code; dead-code cleanup — built on ai-fixes Part C (C-3, C-4 reused). Inc 1b (T2): free-text EN+GR lexicon on shared normaliser after nl-quick-add slice B; Nano on-device only. No cloud context, no schema.
