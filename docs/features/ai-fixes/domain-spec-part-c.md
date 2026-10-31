# Domain spec addendum: ai-fixes Part C (assistant Track 0 defect fixes)

Author: domain-modeler. Date 2026-10-16. Docs-only; implementation post-1.0 (Release 1.0 frozen).
Binding scope: `workflows/active/ai-fixes-handoff.md` "Part C" (C-1..C-6, owner-approved at assistant-follow-ups Gate 1).
Inputs: `docs/features/assistant-follow-ups/critique.md` §1/§3/§4, `brief-v1.md` §0 (F-A..F-J), `docs/features/ai-fixes/domain-spec.md` (INV-1..29; this addendum continues as **INV-C1..**, **AE-C1..**, **OD-C1..** and reuses its INV-3 bounded-diagnostics and INV-4 CancellationException conventions), `docs/architecture/LEGAL_PATHS.md:375-413`.
`A` = `app/src/main/java/com/yourname/expensetracker`. [V] = checked in source for this addendum; facts from critique §1 are cited and were not re-checked; anything else is marked UNVERIFIED.

Out of scope (routed, §11): unsalted SHA-256 aliases (`cloud-alias-hashing`); `"0.00 N/A"` empty totals (money-correctness); Greek lexicon (nl-quick-add normaliser); assistant `AmountSemantics` alignment (money-correctness).

---

## 0. Source facts that shape the rules (new in this addendum)

| ID | Fact | Evidence | Consequence |
|---|---|---|---|
| P1 | All query-period ranges are **half-open** `[start, end)`. The repository filters `e.date >= start AND e.date < end`. All `TimePeriodUtils` unit helpers return exclusive ends from local midnights (`atStartOfDay(zone)`, `ZoneId.systemDefault()`). | [V] `A/data/repository/ExpenseRepository.kt:329-336`; `A/domain/util/TimePeriodUtils.kt:47-49, 627, 749-755, 786-794, 1020-1030, 1133` | Today's duration-based previous period `[start-d, start)` does **not** double-count a boundary row (critique F-F "inclusive end UNVERIFIED" is resolved: exclusive). The defect is only that the window is not calendar-aligned. |
| P2 | Calendar helpers already exist: `getWeekRange/getMonthRange/getQuarterRange/getYearRange(ts, offset)`, ISO Monday weeks. | [V] same file `:627, :786, :1020, :1133` | C-3 needs no new calendar arithmetic for FULL periods. |
| P3 | Current-period shapes are mixed. Week and month are the **full** unit (end = next unit start). Today, quarter and year are **to-date** (end = `now`). The on-device keyword parser uses the same shapes. | [V] `InterpretFinancialQueryUseCase.kt:299-305`; `OnDeviceQueryInterpretationService.kt:280-301` | A to-date range starting Oct 1 or Jan 1 is ambiguous between month, quarter and year. Granularity must therefore be carried, not guessed (INV-C11). |
| P4 | The model keyword set is `TODAY, THIS_WEEK, THIS_MONTH, LAST_MONTH, THIS_QUARTER, THIS_YEAR`. It has no LAST_WEEK, LAST_QUARTER or LAST_YEAR. | [V] `OnDeviceQueryInterpretationService.kt:98, 280-301` | "last year" through the model path can only become THIS_YEAR, an explicit range, or null (OD-C5). |
| P5 | **Any provider `Unsupported` is replaced by the local fallback** (`:68-69`). The fallback never returns `Unsupported`: `resolveMetric` returns only LIST/TOTAL/COUNT/AVERAGE/MAX, and all of these are in the `supported` list. | [V] `InterpretFinancialQueryUseCase.kt:54-70, 247-262, 274-280` | **Correction to critique F-I:** the raw `e.message` from `:48-51` and any model `unsupportedReason` are discarded today. They are not shown, persisted or fed back. What stays live: `Timber.w(e, …)` logs the throwable (`:48`; also `OnDeviceQueryInterpretationService.kt:58, 63, 125`). The leak is latent: it becomes user-visible as soon as the fallback returns `Unsupported` (which C-5 makes possible). C-2 stays required, at severity "latent P2 + live log P3". |
| P6 | `Unsupported` reasons that reach the user today are fixed English constants: executor `"This query type is not supported yet"` (`:59`), `"No matching transactions found"` (`:276, :309`), interpreter `"Assistant query interpretation is disabled"` (`:36-38`; only on a race, because the VM pre-checks at `:211`). The card renders `result.reason` verbatim. | [V] `ExecuteFinancialQueryUseCase.kt:59, 276, 309`; `AssistantResultCard.kt:113-115`; `AssistantViewModel.kt:309, 471, 513-516` | C-2 types the reason end to end (INV-C5..C7). |
| P7 | The executor's "Which time period?" clarification (`:46-49`) is **unreachable through the current VM pipeline**. Provider-structured results get a period from `filters.period ?: resolvePeriod(...)` (`:92`). Fallback intents use `resolvePeriod` (`:223`), which always returns non-null (default this month `:305`). | [V] as cited | The live clarifications are (a) the fallback presentation clarification "Breakdown / Transaction list" (`:264-268`) and (b) model clarifications. The period clarification becomes reachable with assistant-follow-ups chips. C-4 covers both structured kinds. |
| P8 | The model clarification path: if the provider returns `Clarification` and the fallback is not `Structured`, the **provider's** clarification is shown (`:58-66`). It has free-text options and no intent. | [V] | These have no pending intent. Legacy behaviour is kept (INV-C15). |
| P9 | The VM persists the current USER turn (`:235`) **before** it reads history (`:237-238`). So the history passed to the interpreter normally includes the current query as its last row. `buildPrompt` then shows it twice (as "Raw query" and as the last history line). | [V] code order. Runtime emission of the just-inserted row by `observeMessages().first()` is UNVERIFIED but expected (Room emits current state). | INV-C3 makes "prior turns" explicit. |
| P10 | The builder's redaction decision is `aiPolicy.canUseCloudFor(settings, QI) && (aiPolicy.shouldRedact(settings, QI) \|\| privacySettings.redactBeforeCloud)`. `AiPolicyImpl.shouldRedact` returns `settings.redactBeforeCloud` (the AiSettings flag). `EffectiveCloudAiPolicyResolver.resolve()` computes exactly `privacy.redactBeforeCloud \|\| ai.redactBeforeCloud`. | [V] `FinancialQueryInterpretationInputBuilder.kt:29-32`; `AiPolicyImpl.kt:52-53`; `EffectiveCloudAiPolicy.kt:145` | The builder re-implements the effective-policy rule and reads the forbidden AiSettings flag through a wrapper. This is a duplicated rule and in scope for C-6. |
| P11 | Without redaction, history messages pass through **untrimmed and untruncated**. `sanitizeFreeText` runs only on the redact branch. `rawQuery` is always truncated to 400 characters. | [V] `FinancialQueryInterpretationInputBuilder.kt:84-88, 97-116`; `AppConfig.kt:222-223` | INV-C2 applies identical treatment to the current query and to prior queries. |
| P12 | `ExecuteFinancialQueryUseCase` has one production caller, the VM. `NaturalLanguageSearchEngine` only mentions it in comments. Provider routing goes through `HybridQueryInterpretationService` → `HybridRouter`, which passes **the same input object** to the cloud, on-device and NoOp functions. | [V] grep; `HybridQueryInterpretationService.kt:22-32`. Fall-through behaviour inside `HybridRouter` is UNVERIFIED. | A route-specific history filter would sit on the wrong layer. INV-C1 is enforced once, in the input type. |

---

## 1. Entities and value objects (all in-memory; no Room change)

### 1.1 `FinancialQueryInterpretationInput` (changed field)
- Replace `conversationHistory: List<AiChatMessage>` with **`priorUserQueries: List<String>`** (chronological, oldest first, size ≤ `MAX_QUERY_HISTORY_USER_TURNS_FOR_MODEL`).
- The type makes ASSISTANT and SYSTEM turns, `payloadJson`, `kind`, ids and timestamps impossible to represent in any model input (INV-C1).
- `buildPrompt` renders each entry as `- USER: <text>`, keeping prompt stability for Nano. It prints `none` when the list is empty. It has no cap of its own and no filter of its own.

### 1.2 New constant
- `AppConfig.Ai.MAX_QUERY_HISTORY_USER_TURNS_FOR_MODEL = 4` (OD-C6). The existing `MAX_QUERY_HISTORY_TURNS_FOR_MODEL = 8` stops being used for model input. Delete it if it has no other reader (UNVERIFIED).

### 1.3 `AssistantUnsupportedReason` (new closed enum, `domain/ai/model`)

| Code | Produced by | Meaning |
|---|---|---|
| `INTERPRETATION_DISABLED` | `InterpretFinancialQueryUseCase` `:36-38` | AI, assistant or query interpretation is off |
| `INTERPRETATION_FAILED` | `InterpretFinancialQueryUseCase` `:47-51` (non-CE exception from the provider) | provider threw |
| `PROVIDER_UNAVAILABLE` | Cloud service (missing key, privacy gate `PROVIDER_DISABLED`, HTTP failure, `TIMEOUT`, `NETWORK_UNAVAILABLE`, `UNKNOWN_ERROR`); on-device (timeout, `GenAiException`, unexpected); NoOp | no usable provider answer |
| `PROVIDER_RESPONSE_INVALID` | Cloud `PARSER_FAILED` or empty candidates; on-device `parseResponse == null` | unparseable model output |
| `MODEL_DECLINED` | on-device `parseResponse` `kind:"unsupported"` (`:119-121`) | model said unsupported. **Its text is discarded.** |
| `METRIC_NOT_SUPPORTED` | [reconciled Z, 2026-10-24] **Defensive-only; no current producer.** The executor's metric dispatch is an exhaustive `when (intent.metric)` with no `else` (C5), and Interpret's fallback cannot produce it because every `QueryMetric` is now supported (INV-C17). The enum value and its UI text are kept. Was: executor `else` (`:59`); fallback `!supported` (`:258-262`). | no executor for the requested shape (reserved) |
| `NO_MATCHING_TRANSACTIONS` | executor `:276, :309` (and the new MIN path) | empty result for single-row metrics |

- `FinancialQueryInterpretationResult.Unsupported` and `FinancialQueryResult.Unsupported` change from `reason: String` to `reason: AssistantUnsupportedReason`, plus an optional log-only `diagnostic: DiagnosticReasonCode? = null` that the cloud service fills with its existing codes. `diagnostic` is never rendered or persisted.
- The VM log-only token `CLARIFICATION_EXPIRED` (a constant, not part of the enum) is used by INV-C14.

### 1.4 `PeriodGranularity` (new enum) and `ExpenseQueryFilters.periodGranularity`
- `enum PeriodGranularity { DAY, WEEK, MONTH, QUARTER, YEAR }`.
- New field `ExpenseQueryFilters.periodGranularity: PeriodGranularity? = null`. `null` means custom or unknown (for example explicit model `startMs/endMs`).
- **Shape** is derived, not stored. FULL if `end == unitEnd(start)`. TO_DATE if `start == unitStart(start)` and `end < unitEnd(start)`. Units come from `TimePeriodUtils` in the system zone.

### 1.5 `AssistantPeriodResolver` (new pure domain helper, `domain/ai/usecase` or `domain/ai/period`)
This is the single owner of period vocabulary and period arithmetic for the assistant.
- `resolveText(normalizedQuery, nowMs): TextPeriodResolution(range: PeriodRange, granularity: PeriodGranularity?, comparisonRequested: Boolean)`. Follows the precedence in INV-C9 and INV-C10.
- `resolveKeyword(keyword, nowMs): Pair<PeriodRange, PeriodGranularity>?`. Model keywords; see OD-C5 for the LAST_* additions.
- `resolveChoice(PeriodChoice, nowMs)`. Clarification options (§1.6).
- `previousEquivalent(range, granularity?): PeriodRange` (INV-C11, INV-C12).

### 1.6 Clarification completion types (new)
- `enum ClarificationSlot { PERIOD, PRESENTATION }`.
- `sealed ClarificationChoice`: `Period(PeriodChoice)` where `PeriodChoice ∈ {THIS_MONTH, THIS_WEEK, LAST_MONTH}`, and `Presentation(PresentationChoice)` where `PresentationChoice ∈ {BREAKDOWN, TRANSACTION_LIST}`.
- `data class ClarificationCompletion(baseIntent: FinancialQueryIntent, slot: ClarificationSlot, choices: List<ClarificationChoice>)`.
- Both `Clarification` result types gain `completion: ClarificationCompletion? = null`. When it is non-null, the displayed option labels are localized resources derived from `choices` in the same order. The string `options` stay only for model clarifications.
- `CompleteFinancialQueryClarificationUseCase(completion, choice, nowMs): FinancialQueryIntent` is pure: no I/O, no interpreter, no network.

### 1.7 VM state (in-memory, never persisted)
- `livePendingClarification: Pair<String /*clarification item id*/, ClarificationCompletion>?`.
- `AssistantConversationItem.User` gains `completedIntent: FinancialQueryIntent? = null`. It is set only for completion taps and is used by `retryLast` (INV-C16).

---

## 2. Invariants

Format: statement → **Owner** (the single component holding the rule) → enforcement → AE.

### C-1 Prompt history minimisation (F-D, P2 privacy)
- **INV-C1 Model history contains only prior USER queries, on every route.**
  - No query-interpretation prompt (cloud, on-device, any fall-through) contains ASSISTANT- or SYSTEM-role text, computed answers, `payloadJson`, or clarification prompts.
  - Enforced structurally by `priorUserQueries: List<String>` (§1.1).
  - Applies to on-device too, per OD-C1 (recommended).
  - **Owner:** `FinancialQueryInterpretationInputBuilder`, the only producer of `FinancialQueryInterpretationInput` (other constructors in production are UNVERIFIED; a source guard is proposed in §8).
  - AE-C1, AE-C2.
- **INV-C2 Prior queries get exactly the current query's treatment.**
  - The builder selects `role == USER` messages and keeps the last K = 4 (OD-C6), oldest first.
  - Each text goes through the same chain as `rawQuery`: `sanitizeQueryContext` (aliases only when redacting), then `sanitizeFreeText` (PII regexes only when redacting; **always** trim and truncate to `MAX_QUERY_INPUT_CHARS`). Blank results are dropped.
  - Accepted residual: a prior USER query can still contain amounts the user typed, such as "over 50". This is the same exposure as the current `rawQuery`, which interpretation needs.
  - **Owner:** builder. AE-C3, AE-C4.
- **INV-C3 The VM supplies a prior-only snapshot and does no privacy shaping.**
  - When `storeConversationHistory && sessionId != null`, the VM reads the session's persisted messages **before** persisting the current USER turn, and passes them unfiltered (all roles; the builder filters). Otherwise it passes an empty list.
  - The VM applies no `takeLast(10)`, no payload stripping and no redaction branch (removed per INV-C18).
  - **Owner:** `AssistantViewModel.submitQuery`. AE-C1, AE-C5.
- **INV-C4 Persisted history and transcript are unchanged by C-1.**
  - `ai_chat_messages` rows (USER and ASSISTANT, `text`, `payloadJson`) are written exactly as before, apart from INV-C6's code-typed Unsupported payload.
  - The visible transcript is unchanged. Only model input changes.
  - **Owner:** VM persistence (`persistUserTurn` / `persistAssistantTurn`). AE-C6.

### C-2 Bounded interpretation failures (F-I)
- **INV-C5 `Unsupported` carries only a bounded code.**
  - Every producer listed in §1.3 emits a code from `AssistantUnsupportedReason`. No producer puts exception text, model text, prompt text or settings text into a result.
  - The model's `unsupportedReason` is discarded inside `parseResponse`, and `"unsupportedReason":"text"` is removed from the prompt schema (`:98`).
  - `:47-51` becomes `Unsupported(INTERPRETATION_FAILED)`.
  - The existing provider-Unsupported → local-fallback flow (`:68-69`) is **unchanged** (P5), so these codes normally drive the fallback rather than reach the user.
  - **Owner:** each producer; the enum is the single vocabulary. AE-C7, AE-C8.
- **INV-C6 Rendering and persistence map the code to a localized message.**
  - One mapping function `AssistantUnsupportedReason → @StringRes` lives in the UI layer (`AssistantResultCard` and the VM `toDisplayText` both use it).
  - Shown: title `assistant_unsupported` plus the code's message.
  - Persisted `text` is the localized message, resolved at persist time.
  - Persisted `payloadJson` is `{"type":"unsupported","reason":"<CODE_NAME>"}`. The key stays `reason` for reader compatibility; the value becomes the code name.
  - Fed back to models: never (INV-C1).
  - New keys go into `values/` and `values-el/`. EL text needs owner or l10n review; a missing EL entry falls back to EN and does not block.
  - **Owner:** the UI mapping function. AE-C9.
- **INV-C7 Bounded logs and cancellation (reuses INV-3 and INV-4).**
  - The touched log lines become code plus exception class name only, with no throwable argument: `InterpretFinancialQueryUseCase.kt:48`, `OnDeviceQueryInterpretationService.kt:58, 63, 125`.
  - `CancellationException` is rethrown before every `catch (Exception)` touched. Existing rethrows are at `:45-46` and on-device `:60-61`; the `parseResponse` catch at `:124` must add one.
  - **Owner:** each touched component. AE-C7, AE-C10.

### C-3 Period precedence and calendar-aware comparison (F-F)
- **INV-C8 One period vocabulary.**
  - Period ranges are produced only by `AssistantPeriodResolver`. Callers: the fallback (`:223`), enrich fill-if-missing (`:92`), the bare-period branches (`:176-207`), the on-device keyword parser (`:265-301`) and clarification completion (INV-C13).
  - The comparison cue is likewise produced only by the resolver. It replaces the duplicated expressions at `:128` and `:227`.
  - Every resolved range sets `periodGranularity`. Explicit model `startMs/endMs` leaves it `null`.
  - **Owner:** `AssistantPeriodResolver`. AE-C11..C15.
- **INV-C9 Phrase precedence (EN, on the lower-cased, trimmed query).**
  1. **Comparison target removal.** If a comparison cue (INV-C10) is present, any `(last|previous|prior) <unit>` that directly follows a comparison connector is the comparison **target**. It is removed before step 2. `<unit>` ∈ {week, month, quarter, year}; word boundaries apply.
     - [reconciled Z, 2026-10-24] Connectors: `vs.?`, `versus`, `than`, `against`, or a compare-family word followed later in the same sentence by `with` or `to`. The shipped pattern is `compar(e|ed|ing|ison)\b[^.?!]*?\b(with|to)`: other words may come in between, but no `.`, `?` or `!`. The old pattern was `compare(d)? (it )?(with|to)`, which did not match the AE-C12 example "compare food with previous month". This was C3 deviation (1), accepted at review. Only the connector was loosened; the target must still directly follow it.
  2. `today` → DAY, TO_DATE `[startOfDay(now), now)`.
  3. `(last|previous|prior) <unit>` → the previous **full** unit, `get<Unit>Range(now, -1)`.
  4. `(this|current) <unit>` → the current unit in today's shape (P3): week and month FULL, quarter and year TO_DATE `[unitStart, now)`.
  5. Bare `<unit>`: today's substring semantics, unchanged, current unit as in step 4.
  6. Nothing matched → this month (`:305`, unchanged).
  - Within one tier the first unit in the order week < month < quarter < year wins.
  - Steps 1-4 use word boundaries.
  - Greek phrases are out of scope; they arrive later through the shared nl-quick-add normaliser.
  - **Owner:** resolver. AE-C11, AE-C12, AE-C13, AE-C14, AE-C19.
- **INV-C10 Comparison cue set.**
  - `comparisonRequested` is true iff the query contains the word `compare`, `compared`, `comparison`, `vs`, or `versus`, or the phrase `than (the )?(last|previous|prior) <unit>`, or `previous period`.
  - The bare word `previous` **no longer** triggers comparison, because `previous <unit>` is now a period phrase.
  - Comparison remains TOTAL-only (`:244`, `:344`, unchanged).
  - **Owner:** resolver. AE-C19.
- **INV-C11 Calendar-aware previous equivalent period.** `previousEquivalent([s, e), g)`:
  - If `g == null`: classify by exact FULL match against DAY, WEEK, MONTH, QUARTER, YEAR in the system zone (an exact full match is unique). Otherwise the range is **CUSTOM**.
  - **FULL** unit g: `[get<g>Range(s, -1).first, s)`.
  - **TO_DATE** unit g: `prevStart = get<g>Range(s, -1).first`; `prevEnd = min(s, ZonedDateTime(e).minus(1 g))`.
    - `minus(1 g)` uses java.time calendar arithmetic: `minusDays(1)`, `minusWeeks(1)`, `minusMonths(1)`, `minusMonths(3)`, `minusYears(1)`.
    - Month-end clamps (Mar 31 → Feb 28/29) and DST gaps or overlaps resolve by java.time rules.
  - **CUSTOM**: `[s - (e - s), s)`. This is today's duration rule and is kept only for non-calendar ranges.
  - `previousEquivalent` is invoked only by the executor's comparison branch (`:344-347`).
  - **Owner:** resolver. AE-C12, AE-C15..C18.
- **INV-C12 No overlap, no gap at the join.** Always `prevEnd ≤ s`; for FULL, `prevEnd == s`. Together with the half-open SQL filter (P1), a row is counted in at most one of current and previous. A row at exactly `s` belongs to current only. **Owner:** resolver plus the existing repository filter. AE-C12.

### C-4 Clarification completion (F-G)
- **INV-C13 Structured clarifications complete the pending intent; they are never re-interpreted.**
  - The executor period clarification (`:46-49`) returns `completion = (intent, PERIOD, [THIS_MONTH, THIS_WEEK, LAST_MONTH])`.
  - The fallback presentation clarification (`:264-268`) returns `completion = (builtIntent, PRESENTATION, [BREAKDOWN, TRANSACTION_LIST])`.
  - On a valid tap, the VM calls `CompleteFinancialQueryClarificationUseCase`, then `ExecuteFinancialQueryUseCase`. It **does not** call `InterpretFinancialQueryUseCase`, any provider, or the network.
  - Slot filling:
    - PERIOD: `filters.period/periodGranularity = resolveChoice(choice, now_at_tap)`.
    - BREAKDOWN: `metric = TOTAL`, grouping kept.
    - TRANSACTION_LIST: `metric = LIST`, `grouping = NONE`.
    - Every other field (categories, merchants, types, ownership, amounts, currency, comparison, answerMode) is preserved byte for byte.
  - The tapped option's localized label is appended to the transcript and persisted as a USER turn (kind QUERY). It may later appear in `priorUserQueries`, which is harmless.
  - **Owner:** completer (slot rules) and VM (routing). AE-C20, AE-C21.
- **INV-C14 One live pending clarification, consumed once, then expired.**
  - `livePendingClarification` is set when a Result item whose clarification has `completion != null` is appended. It is cleared by:
    - a tap (consume first, then execute);
    - any new `submitQuery`, or any later Result or Error item;
    - `clearSession` or `clearAllHistory`;
    - the disabled check at tap time (`aiEnabled && assistantEnabled && queryInterpretationEnabled`, same as `:211`);
    - VM clearance or process death.
  - There is no wall-clock TTL: period choices resolve at tap time.
  - A tap whose item id ≠ the live id, or that arrives after consumption, is a no-op. It logs `CLARIFICATION_EXPIRED` and makes no interpreter or executor call.
  - Expired or restored clarification options render disabled (OD-C3).
  - A tap while `_isSubmitting` is true is ignored, and the pending entry stays until superseded.
  - Pending state is never persisted. Restored transcripts never regain it.
  - **Owner:** VM. AE-C22, AE-C23.
- **INV-C15 Boundary: model clarifications keep legacy behaviour.**
  - Clarifications with `completion == null` (model free text, P8) keep today's `onClarificationSelected(text)` → `submitQuery(text)`.
  - This mechanism (pending intent + typed slot) is the minimal base that assistant-follow-ups inc 1 extends with session context and chips. Part C adds no session context, chips or free-text merging.
  - **Owner:** VM. AE-C24.
- **INV-C16 Retry re-executes a completion.** `retryLast` on a User item with `completedIntent != null` re-runs `ExecuteFinancialQueryUseCase(completedIntent)` with no interpretation. Cancellation during completion propagates (INV-4); the completed intent stays on the User item for retry. **Owner:** VM. AE-C25, AE-C26.

### C-5 MIN (decision: implement the executor; see OD-C2 for why "remove from schema" is advised against)
- **INV-C17 MIN is answered, never silently re-mapped.**
  - `ExecuteFinancialQueryUseCase` gains `executeSmallest`, dispatched after MAX. It is a **mirror of `executeLargest` (`:270-335`)**:
    - same `assistantFilteredExpensesCurrencyAware` input;
    - single currency: `minByOrNull { effectiveAmount }`;
    - mixed currencies: `convertAsOf(atMillis = expense.date)` per row, excluding no-rate rows with `isPartial`/`excludedCount`;
    - empty → `NO_MATCHING_TRANSACTIONS`;
    - title key `domain_ai_smallest_purchase`;
    - drilldown narrowed exactly as MAX does.
  - The local fallback maps the cues `smallest|cheapest|lowest|minimum` → MIN. MIN is added to the `supported` set (`:247-256`) and to `containsRichIntentCue` (`:343-375`).
  - [reconciled Z, 2026-10-24] The executor has no `else` branch. Metric dispatch is an exhaustive `when (intent.metric)` after the LIST/grouping checks. `METRIC_NOT_SUPPORTED` is defensive-only: it cannot be reached from the executor or from Interpret. The enum value and its UI text are kept. This is pinned by the test "INV-C17 every QueryMetric has an executor and none returns METRIC_NOT_SUPPORTED", retargeted (not deleted) from the C2 MIN test. As shipped, `executeLargest` and `executeSmallest` share one `executeExtreme` helper (MAX behaviour unchanged), and `MapFinancialQueryToNavigationUseCase` accepts MIN so its drilldown matches MAX. Was: "The executor `else` branch returns `METRIC_NOT_SUPPORTED`; after this change no current enum value reaches it."
  - MIN keeps the same money-semantics gap as MAX (F-C: `convertAsOf` vs stored snapshot). money-correctness aligns both.
  - **Owner:** executor (execution) and resolver-adjacent `resolveMetric` (cue). AE-C27, AE-C28.

### C-6 Effective redaction source (F-E)
- **INV-C18 Assistant redaction is read only through `EffectiveCloudAiPolicyResolver`.**
  - The builder computes `shouldRedact = aiPolicy.canUseCloudFor(settings, QUERY_INTERPRETATION) && effectiveCloudAiPolicyResolver.resolve().redactBeforeCloud`. The `canUseCloudFor` conjunct is unchanged.
  - The builder no longer reads `PrivacySettingsRepository…redactBeforeCloud` or `aiPolicy.shouldRedact` for this decision.
  - The VM reads **no** redaction flag; its `:242-246` branch is deleted (INV-C3). If `privacySettingsRepository` then has no other VM use, it is removed from the VM constructor (UNVERIFIED).
  - The behaviour is identical to today's OR (P10). Only the source of truth changes.
  - **Owner:** `EffectiveCloudAiPolicyResolver` (rule) and builder (sole consumer for the assistant). AE-C29, AE-C30.

---

## 3. State machines

### 3.1 Pending clarification (VM, per transcript)

| From | Trigger | To | Effect |
|---|---|---|---|
| NONE | Result appended with `Clarification(completion != null)` | PENDING(itemId) | previous pending (if any) dropped |
| PENDING(id) | tap(id, index) with id == live, not submitting, assistant enabled | EXECUTING | consume pending; append User(label, completedIntent); persist USER turn; run executor |
| PENDING(id) | tap with assistant disabled | NONE | `isDisabled` UI state as at `:211-217`; no execution |
| EXECUTING | executor returns | NONE | append Result; persist ASSISTANT turn. A new Clarification with completion re-enters PENDING. |
| EXECUTING | non-CE exception | NONE | bounded Error item (existing `:328-346` path); retry via INV-C16 |
| EXECUTING | CancellationException | NONE | rethrown; no Error item; retry possible |
| PENDING(id) | submitQuery / other Result / Error / clearSession / clearAllHistory | NONE | — |
| any | tap(id') with id' ≠ live, or NONE | unchanged | no-op; log `CLARIFICATION_EXPIRED` |
| any | process death / VM cleared | (gone) | restored transcript options are inert and disabled |

Illegal transitions: PENDING → EXECUTING twice for the same id (double tap) is prevented by consume-before-execute; EXECUTING → interpreter call is forbidden.

### 3.2 Period resolution (pure, per query)
TARGET-STRIP → TODAY → LAST/PREVIOUS/PRIOR unit → THIS/CURRENT unit → BARE unit → DEFAULT this month. The first match wins. The resolver is deterministic for a given `(query, now, zone)`.

---

## 4. Edge cases

| Case | Rule |
|---|---|
| Mixed currencies in comparison | Unchanged: per-currency totals side by side, no cross-currency delta (`:342-347`). Part C adds no conversion. |
| Missing or stale FX in MIN | Same as MAX: excluded rows counted in `excludedCount`, `isPartial = true`; never zero-filled. |
| Unavailable vs zero | An empty comparison side renders today's `"0.00 N/A"` (`:505`). Not fixed here; routed (RF-C1). An empty MIN gives `NO_MATCHING_TRANSACTIONS`, never `0.00`. |
| Rounding | No new aggregation. MIN selects a row, so there is no rounding. |
| Time zone / DST | All units use `ZoneId.systemDefault()` local midnights (P1). In a 745 h October the FULL previous month is exactly Sep 1 00:00 → Oct 1 00:00 (AE-C18). TO_DATE uses local-time arithmetic (Oct 26 10:00 "today" ↔ Oct 25 10:00 even though Oct 25 is 25 h long). CUSTOM keeps ms-duration arithmetic (a 1 h DST skew is accepted). A zone change between clarification and tap: the tap's zone and now are used. |
| Month-end | TO_DATE clamp by java.time: "compare this quarter" on May 31 12:00 → previous `[Jan 1, Feb 28 12:00)`. FULL previous months come from `YearMonth` (no 31st overflow). |
| Partial periods | Mirror shape (OD-C4 default): FULL compares with FULL, TO_DATE with the same elapsed calendar span. "This month" is FULL (P3), so mid-October "compare with previous month" is October-so-far vs all of September. OD-C4 asks whether to switch to same-elapsed. |
| Model `startMs/endMs` with inclusive-style end (…:59.999) | Not an exact unit → CUSTOM duration shift; the half-open SQL drops the last millisecond. Accepted, documented. |
| Category deleted or merged between clarification and tap | The completed intent keeps the stale id. The executor returns an empty or partial result exactly as a fresh query would. No special handling (assistant-follow-ups §4 invalidation owns the general case). |
| Duplicates / idempotency | A double tap consumes once (INV-C14). Retry re-executes, which is read-only and idempotent. |
| Edits/deletes of past or recurring items | N/A (read-only queries). |
| Empty history / history off / no session | `priorUserQueries = []`; the prompt prints `none`. |
| Repeated identical query | P9 is solved by snapshot order (INV-C3), not by text de-duplication, so two identical prior questions both remain. |
| Legacy persisted Unsupported rows | Contain the English constants of P6 (raw text was never persisted, P5). No backfill. Rows are excluded from prompts by INV-C1 and aged out by retention. |
| Retry after completion | INV-C16. Retry after a model-clarification text tap: legacy (re-interprets the label text). |

---

## 5. Persistence impact

**Schema: NONE.** No table, column, index, DAO query or DataStore key changes. No version bump, no migration, no snapshot change.
- `ai_chat_messages`: unchanged rows apart from the Unsupported `payloadJson.reason` *value*, which becomes a code name for new rows (same key, same TEXT column). Readers of `payloadJson` other than the VM writer are UNVERIFIED; the key is kept for compatibility.
- No backfill, nothing destructive.
- New in-memory fields only: `periodGranularity`, `completion`, `completedIntent`, `livePendingClarification`, `priorUserQueries`.

## 6. Privacy rules
- **Egress (cloud):** the prompt contains the current sanitized query, up to 4 prior sanitized USER queries, known category and merchant names (aliased under redaction), the locale and the schema. It contains no ASSISTANT turns, answers, amounts computed by the app, `payloadJson`, or clarification texts (INV-C1, INV-C2). It still passes `CloudAiPrivacyGate` → `DefaultCloudPayloadPolicy.prepareText` → audit (unchanged, `CloudQueryInterpretationService.kt:92-118`).
- **On-device:** the same input (OD-C1).
- **Stored:** unchanged transcript rows; Unsupported text is a localized constant and payload holds a code. Pending clarification and completed intents are memory only.
- **Exported:** unchanged (assistant export path UNVERIFIED; Part C adds no exported field).
- **Logged:** codes, enum names, exception class names, counts. No throwable, `e.message`, query, prompt, model output, merchant or amount (INV-C7; RF-C4 covers the amount-logging lines in the same file).
- **Redaction source:** `EffectiveCloudAiPolicyResolver` only (INV-C18).

## 7. Acceptance examples (Given/When/Then; synthetic data)
Test setup: `TimeZone.setDefault(Europe/Athens)` (restore after); fixed `TimeProvider`; NOW = 2026-10-16 (Fri) 10:00 EEST unless stated. EU DST ends 2026-10-25. Categories: Food=1, Transport=2.

**C-1**
- **AE-C1** (INV-C1, C3)
  - Given: history on, session 5 with rows U1 "food this month", A1 "Total spending\n123.45 EUR\nPrevious period: 98.10 EUR" (payload has `primaryText`), U2 "show transport", A2 "Matching category transactions (7)"; cloud route allowed; redaction off.
  - When: the user submits "transport last month" and the cloud provider builds its request.
  - Then:
    - the request text contains `- USER: food this month` and `- USER: show transport`;
    - it contains none of `ASSISTANT`, `123.45`, `98.10`, `(7)`, `primaryText`;
    - "transport last month" appears exactly once (the "Raw query" line).
- **AE-C2** (INV-C1, OD-C1) Same Given, on-device route. `buildPrompt` output satisfies the same Then.
- **AE-C3** (INV-C2)
  - Given: 7 prior USER turns, the 7th being 450 × "y", plus 7 ASSISTANT turns; redaction off.
  - When: build runs.
  - Then: `priorUserQueries` has 4 entries (prior turns 4–7, oldest first) and the last one has length 400.
- **AE-C4** (INV-C2, C18)
  - Given: effective redaction on; merchant "Lidl", category "Groceries"; prior USER "Lidl groceries card 4242 4242 4242 4242".
  - When: build runs.
  - Then: the entry contains `merchant_<hash>`, `category_<hash>` and `[REDACTED_CARD]`, and does not contain "Lidl" or "4242".
- **AE-C5** (INV-C3) Given `storeConversationHistory=false`. When submitting. Then the interpreter receives empty history, and the prompt prints `Conversation history:` / `none`.
- **AE-C6** (INV-C4) Given AE-C1. When the submit completes. Then `ai_chat_messages` gains exactly one USER row ("transport last month") and one ASSISTANT row with the same `text`/`payloadJson` format as before Part C.

**C-2**
- **AE-C7** (INV-C5, C7)
  - Given: the provider throws `IllegalStateException("/data/user/0/x/db token=abc123")`.
  - When: interpreting "food this month".
  - Then:
    - the provider result is `Unsupported(INTERPRETATION_FAILED)`;
    - the returned result is the local-fallback Structured {Food, Oct 1 → Nov 1};
    - the log line contains `INTERPRETATION_FAILED` and `IllegalStateException` and contains neither `abc123` nor `/data`;
    - no throwable is passed to Timber;
    - transcript, persisted rows and the next prompt contain neither string.
- **AE-C8** (INV-C5) Given the model returns `{"kind":"unsupported","unsupportedReason":"rent 4242 4242 4242 4242 at Lidl"}`. When `parseResponse` runs. Then the result is `Unsupported(MODEL_DECLINED)` and no field contains "Lidl" or "4242".
- **AE-C9** (INV-C6)
  - Given: MAX intent with no rows.
  - When: executed and rendered.
  - Then:
    - result is `Unsupported(NO_MATCHING_TRANSACTIONS)`;
    - the card shows the localized `assistant_unsupported` title plus the code's message (the EN resource under `en`, the EL resource under `el`);
    - persisted payload is `{"type":"unsupported","reason":"NO_MATCHING_TRANSACTIONS"}`.
- **AE-C10** (INV-C7) Given the on-device JSON parse throws. When `parseResponse` runs. Then the log carries the class name only, and a `CancellationException` thrown in the same block propagates.

**C-3**
- **AE-C11** (INV-C8, C9)
  - Given: provider unavailable.
  - When/Then:
    - "food last month" → {categoryIds={1}, period [2026-09-01 00:00, 2026-10-01 00:00), MONTH, comparison NONE};
    - "last week" → [Mon 10-05, Mon 10-12) WEEK;
    - "last quarter" → [07-01, 10-01) QUARTER;
    - "last year" → [2025-01-01, 2026-01-01) YEAR;
    - "spending previous month" → September, comparison NONE.
- **AE-C12** (INV-C9, C11, C12)
  - Given: Food rows at 2026-08-31 12:00 (10.00 EUR), 2026-09-30 23:59:59.999 (20.00 EUR), 2026-10-01 00:00:00.000 (30.00 EUR).
  - When: "compare food with previous month".
  - Then:
    - base = [10-01, 11-01) MONTH FULL and previous = [09-01, 10-01);
    - primary "30.00 EUR"; supporting "Previous period: 20.00 EUR";
    - the Aug 31 row is in neither period.
- **AE-C13** (INV-C9) "compare last month with previous month" → base [09-01, 10-01), previous [08-01, 09-01).
- **AE-C14** (INV-C9) "food this month vs last month" → base October FULL, previous September, Food filter kept, comparison PREVIOUS_EQUIVALENT_PERIOD.
- **AE-C15** (INV-C11, P3)
  - "compare this quarter" at NOW → base [10-01, 10-16 10:00) QUARTER TO_DATE → previous [07-01, 07-16 10:00). It is **not** [09-01, 09-16 10:00).
  - "compare this year" → previous [2025-01-01, 2025-10-16 10:00).
- **AE-C16** (INV-C11) NOW = 2026-05-31 12:00. "compare this quarter" → base [04-01, 05-31 12:00) → previous [01-01, 02-28 12:00).
- **AE-C17** (INV-C11 CUSTOM) Intent period [100, 200), granularity null, comparison PREVIOUS → previous [0, 100). This is the existing `ExecuteFinancialQueryUseCaseTest:90-131`, unchanged.
- **AE-C18** (INV-C11 DST) Intent period = October 2026 FULL (745 h), granularity MONTH (also with granularity null via exact classification) → previous = [2026-09-01 00:00 EEST, 2026-10-01 00:00 EEST). It is not [08-31 23:00, …).
- **AE-C19** (INV-C10) "food previous month" → comparison NONE. "food this month compared to last month" → comparison PREVIOUS_EQUIVALENT_PERIOD, base October.

**C-4**
- **AE-C20** (INV-C13): "Last month" keeps the Food filter.
  - Given: a mocked interpreter returns Structured {Food, TOTAL, period=null} for "how much on food" (P7: only reachable through mocks or future chips today); the executor returns Clarification(PERIOD, [This month, This week, Last month]).
  - When: the user taps option index 2.
  - Then:
    - the interpreter is called exactly once in total (the original query only);
    - the executor is called with {categoryIds={1}, TOTAL, period [09-01, 10-01), MONTH}, all other fields equal to the base intent;
    - the transcript gains User("Last month") followed by a Food total;
    - no network or provider call happens.
- **AE-C21** (INV-C13)
  - Given: provider unavailable and query "average spend per merchant this month" → fallback Clarification(PRESENTATION) with base {AVERAGE, MERCHANT, Oct, types={PURCHASE}}.
  - When: the user taps "Transaction list". Then the executor receives {LIST, NONE, Oct, PURCHASE}.
  - When instead the user taps "Breakdown". Then the executor receives {TOTAL, MERCHANT, Oct, PURCHASE}.
- **AE-C22** (INV-C14) Given AE-C20's clarification followed by a new submitted query. When the old option is tapped. Then there are no interpreter or executor calls, the log contains `CLARIFICATION_EXPIRED`, and the old card's options are disabled.
- **AE-C23** (INV-C14) Given a live pending clarification. When the same option is tapped twice quickly. Then the executor is called once.
- **AE-C24** (INV-C15) Given a model Clarification with options ["This month","Last month"] and no completion. When "This month" is tapped. Then `submitQuery("This month")` runs as today. The existing `AssistantViewModelTest:547-616` passes, with the history argument shape updated as needed.
- **AE-C25** (INV-C16) Given the executor throws `IOException` on a completion. When `retryLast()` runs. Then the executor is re-invoked with the same completed intent and the interpreter is not called.
- **AE-C26** (INV-C16, INV-4) Given cancellation during the completion's execution. Then `CancellationException` propagates and no Error item is added.

**C-5**
- **AE-C27** (INV-C17)
  - Given: home EUR; October rows Lidl 12.00 EUR, Kiosk 3.50 EUR, Bakery 2.00 USD with no rate.
  - When: {MIN, Oct} is executed.
  - Then:
    - primary is "Kiosk: 3.50 EUR";
    - `isPartial = true`, `excludedCount = 1`;
    - drilldown merchants = {Kiosk}.
  - Given instead single-currency rows 5.00/1.20 EUR → "…: 1.20 EUR". Given no rows → `NO_MATCHING_TRANSACTIONS`.
- **AE-C28** (INV-C17) Given provider unavailable. When "smallest purchase this month" is asked. Then the fallback intent metric is MIN (not TOTAL) and the result is a Summary.

**C-6**
- **AE-C29** (INV-C18) Given AiSettings.redactBeforeCloud=false, PrivacySettings.redactBeforeCloud=true, cloud allowed for QI. When build runs. Then names are aliased, and the decision came from `EffectiveCloudAiPolicyResolver.resolve()` (the resolver mock was invoked; the builder's `PrivacySettingsRepository` is not consulted for redaction).
- **AE-C30** (INV-C18) Source scan: `AssistantViewModel.kt` contains no `redactBeforeCloud` token. `FinancialQueryInterpretationInputBuilder.kt` contains no `privacySettingsRepository` redaction read and no `aiPolicy.shouldRedact`.

---

## 8. Test impact (FG-19 stop-and-ask list; exact deletions return at plan approval)

| Test | Today pins | Change |
|---|---|---|
| `FinancialQueryInterpretationInputBuilderTest.kt:55-97` | mixed USER/ASSISTANT history passthrough, 8 turns (`:92-96`) | **retarget** to AE-C3 (USER-only, ≤4, truncated) |
| `…InputBuilderTest.kt:112-162` | `conversationHistory.single().text` aliasing | mechanical: `priorUserQueries.single()`; assertions kept |
| `…InputBuilderTest.kt:165-190` | redaction via `PrivacySettingsRepository` | **retarget** to an `EffectiveCloudAiPolicyResolver` fake (AE-C29); same expected behaviour |
| `AssistantViewModelTest.kt:485-544` | VM strips `payloadJson` under privacy redaction | **delete or retarget** (stop-and-ask): the VM branch is removed; coverage moves to the builder (AE-C1, AE-C4) |
| `AssistantViewModelTest.kt:547-616` | model-clarification text resubmission with full history | expected to pass (INV-C15); update if the snapshot order changes the captured list |
| `AssistantViewModelTest.kt` constructor sites (`:499-509`, `:556-566`, setUp) | `privacySettingsRepository` param | mechanical if the param is removed |
| `AssistantViewModelTest.kt:300-310, 343-363`; any `Unsupported("…")` | String reasons | mechanical: typed codes |
| `ExecuteFinancialQueryUseCaseTest.kt:90-131` | duration previous `[0,100)` | **unchanged** (CUSTOM, AE-C17) |
| `InterpretFinancialQueryUseCaseTest.kt:156, 186` | filled period for "this month"/"this week" | likely unchanged. If they compare whole `ExpenseQueryFilters`, add `periodGranularity` (UNVERIFIED) |
| Tests asserting `"No matching transactions found"` / `"This query type is not supported yet"` | English literals | mechanical to codes (presence UNVERIFIED) |
| On-device/Cloud interpretation tests pinning the schema line or history rendering | — | none found by grep for `MIN`/`Conversation history`; schema-string pins UNVERIFIED |

New tests: AE-C1..C30. Proposed guards (not edited here):
- (a) a source scan that `AssistantViewModel.kt` has no `redactBeforeCloud` token;
- (b) a source scan that only `FinancialQueryInterpretationInputBuilder` constructs `FinancialQueryInterpretationInput` in `src/main`;
- (c) a cloud request-body test asserting there is no `ASSISTANT:` line.

Validation profile hint: `targeted-unit-test` on the assistant domain/VM test classes. No migration tests (schema NONE).

## 9. Proposed architecture-doc changes (not edited here)
- `LEGAL_PATHS.md` Privacy/Cloud AI FORBIDDEN:
  - add "❌ ASSISTANT/SYSTEM turns or app-computed answers in any query-interpretation prompt (model history = prior USER queries, shaped only by `FinancialQueryInterpretationInputBuilder`)";
  - add "❌ Reading `PrivacySettings.redactBeforeCloud` directly or OR-ing redaction flags outside `EffectiveCloudAiPolicyResolver`".
- `LEGAL_PATHS.md` assistant/query section: add "Clarification completion → `CompleteFinancialQueryClarificationUseCase` → `ExecuteFinancialQueryUseCase` (read-only; no re-interpretation)". This is a new read path, already approved as C-4 scope.
- Release note line: "Assistant: 'last week', 'last month', 'last quarter' and 'last year' now mean the previous calendar period (previously the current one was used, e.g. 'food last month' showed this month). Comparisons now use the previous calendar week/month/quarter/year instead of a same-length window. Choosing an option in a clarification keeps your original filters. Smallest-purchase questions are now answered."

## 10. Open decisions (recommended defaults)
- **OD-C1** Should on-device prompts also drop ASSISTANT turns? **Rec: yes.** One rule enforced by the input type; `HybridRouter` hands the same input to every route (P12); Nano context is small; interpretation never needs past answers.
- **OD-C2** MIN. **Rec: implement the executor as a mirror of MAX (INV-C17).** Alternatives:
  - (b) keep MIN in the schema, no executor, localized `METRIC_NOT_SUPPORTED`. This is honest and the smallest diff.
  - (c) remove MIN from the schema. **Advised against:** the model then cannot express "smallest". It returns another metric or `unsupported`, and the always-fallback (P5) answers TOTAL ("smallest purchase this month" → total purchases), which is a silent wrong answer.
- **OD-C3** Options on expired or restored clarifications. **Rec: render disabled** (a UI change in `AssistantSheet.kt:189-204` / `AssistantResultCard`). Alternative: legacy text resubmission, which reintroduces filter loss for old cards.
- **OD-C4** Comparison for FULL current units ("this month" mid-month). **Rec: mirror shape** (Oct FULL vs Sep FULL; minimal, matches what "this month" already shows). Alternative: same-elapsed (Oct 1-16 10:00 vs Sep 1-16 10:00). That needs "this week/month" to become TO_DATE whenever comparison is requested.
- **OD-C5** Add `LAST_WEEK|LAST_QUARTER|LAST_YEAR` to the model `periodKeyword` schema, resolved by `AssistantPeriodResolver`. **Rec: yes** (same prompt edit as INV-C5's `unsupportedReason` removal; without it the model path can map "last year" to THIS_YEAR).
- **OD-C6** Prior USER turns K. **Rec: 4** (the old 8 mixed turns were about 4 USER turns; minimisation).

## 11. Routed findings
- **RF-C1** Empty totals and averages render `"0.00 N/A"` (`ExecuteFinancialQueryUseCase.kt:505, 513`), including an empty comparison side → **money-correctness** (typed "no matching transactions").
- **RF-C2** Unsalted SHA-256 aliases (`FinancialQueryInterpretationInputBuilder.kt:124, 131`) and siblings → **cloud-alias-hashing**.
- **RF-C3** Missing-rate warnings with merchant and amount (critique F-C, `:444`) and the `convertAsOf` vs stored-snapshot gap (MAX and the new MIN) → **money-correctness**.
- **RF-C4** `validateAmountBound` logs AI-returned amounts (`InterpretFinancialQueryUseCase.kt:318-327`), which conflicts with INV-3 "no amounts" → **fold into C-2** (same file, same rule), else PSG.
- **RF-C5** Hard-coded English: clarification prompts and options, `"Previous period:"`, the VM disabled reason (`:214`), `mapAssistantExceptionToUserMessage` sniffing `e.message` (`:537-546`, bounded output) → **l10n track** (Greek).
- **RF-C6** Critique F-I severity correction (P5): raw text is latent, not user-visible today; the live issue is throwable logging. Update the PSG record.
- **RF-C7** Bare-unit substring matching ("monthly" → month, "vs" inside words), plus unsupported "yesterday", "past month" and "last 30 days" → **assistant-follow-ups 1b / nl-quick-add normaliser** (EN+GR lexicon).
- **RF-C8** "This week/month" ranges extend to the unit end, so future-dated rows (if any exist) count, unlike quarter/year to-date (P3). Existence of future-dated rows is UNVERIFIED → assistant-follow-ups / money-correctness.
- **RF-C9** Pre-existing TOCTOU: the builder decides redaction before the provider's gate check, so a settings flip in between could misalign them. Low severity, UNVERIFIED → PSG note.
