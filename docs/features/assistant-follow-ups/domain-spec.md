# Domain spec: assistant-follow-ups (inc 1 rules + inc 1b frame)

Author: domain-modeler (stage 3). Date 2026-10-16. Docs only; implementation post-1.0 (Release 1.0 frozen).

Binding inputs:
- `brief.md` v2 (§12 is the agenda) and `critique.md`.
- `workflows/active/assistant-follow-ups-handoff.md` §Human decisions:
  - inc 1 is chips only;
  - context lifetime = visible transcript of the Activity-scoped VM;
  - context is never sent to cloud;
  - this feature owns the typed empty result that replaces `"0.00 N/A"` (owner Q1).
- `docs/features/ai-fixes/domain-spec-part-c.md` (C-1..C-6). Part C **lands first**. This spec reuses Part C's rules and does not re-own them:
  - `AssistantPeriodResolver`, `PeriodGranularity`, INV-C11 `previousEquivalent`, OD-C4 mirror shape;
  - C-4 completion types, `livePendingClarification`, `completedIntent`, INV-C13..C16;
  - C-2 `AssistantUnsupportedReason`;
  - C-5 MIN (`executeSmallest`);
  - C-1 `priorUserQueries` (builder).

Numbering: **INV-F**, **AE-F**, **OD-F**, **RF-F** (no collision with INV-1..29 / INV-C*).

Notation:
- `A` = `app/src/main/java/com/yourname/expensetracker`.
- [V] = checked in source for this spec. [C] = carried from the brief, critique or Part C, not re-checked. UNVERIFIED = not checked.
- "Executed intent" = the exact `FinancialQueryIntent` passed to `ExecuteFinancialQueryUseCase` for an answer.

---

## 0. Source facts that shape the rules

| ID | Fact | Evidence | Consequence |
|---|---|---|---|
| S1 | Executor dispatch order: `metric==LIST` → list; `grouping==CATEGORY` → category breakdown; `grouping==MERCHANT` → merchant breakdown; MAX; TOTAL; COUNT; AVERAGE; else Unsupported. MIN is added after MAX by C-5. Grouping DAY/WEEK/MONTH is ignored. COUNT/AVERAGE with grouping CATEGORY render the category *total* breakdown. | [V] `A/domain/ai/usecase/ExecuteFinancialQueryUseCase.kt:51-60` | Chip eligibility must key off the **executor branch**, not raw metric/grouping. To avoid duplicating the dispatch rule, that branch becomes one pure function (INV-F18). |
| S2 | Empty totals render `formatAmount(0.0, "N/A")` → `"0.00 N/A"`. Averages do the same (`:513`). TOTAL comparison builds `"Previous period: " + formatCurrencyTotals(prev)` and discards the previous side's data quality (`val (prevExpenses, _)`). | [V] `:344-350, :504-518` | Typed `Empty` and a typed comparison outcome (INV-F13, INV-F14). |
| S3 | MAX narrows the **result's** `drilldownIntent` to the largest row's merchant and type. | [V] `:327-332` | `drilldownIntent` is not a safe context source: chips would offer "Remove {merchant the user never typed}". Context = executed intent (INV-F2). |
| S4 | The VM maps the **executed** intent to the navigation filter (`mapFinancialQueryToNavigationUseCase(interpretation.intent)`). The result card shows an "open transactions" button whenever `drilldownFilter != null`. | [V] `AssistantViewModel.kt:266-274`; `A/ui/components/ai/AssistantResultCard.kt:119-121` | "Show transactions" = this existing navigation (§3.5). |
| S5 | The mapper returns `null` for MIN (allow-list `:17`) and keeps a category or merchant only if it is single (`singleOrNull`, `:26-28`). | [V] `MapFinancialQueryToNavigationUseCase.kt:17, 26-28` | MIN answers have no drilldown. Intents with several categories or merchants navigate to a broader list (RF-F2, RF-F3). |
| S6 | The VM never loads persisted `ai_chat_*` rows into `messages`. Persisted rows are read only as interpreter history (`:237-238`). The initial `AssistantUiState.messages` is empty. | [V] `AssistantViewModel.kt:49-64, 237-238` (no other `observeMessages` use) | Today, process death gives an **empty** transcript. The brief's "restored opt-in transcript" is a future case; rules for it are in INV-F16. |
| S7 | `AssistantMessageKind` is persisted as its enum `name` in a TEXT column. An unknown name decodes to `ERROR`. | [V] `A/data/database/converter/Converters.kt:116-120`; `AiChatMessageEntity.kt:32` | A new enum value needs no Room schema change (P-1 storage, OD-F2). |
| S8 | `CategoryRepository.allCategories: Flow<List<Category>>`. `mergeCategories(source, target)` documents that the source "will be deleted". `deleteCategory` deletes the row. | [V] `A/data/repository/CategoryRepository.kt:31, 151-165, 207-229`. The DAO merge body is UNVERIFIED beyond the KDoc. Whether archived/hidden categories are in `getAllFlow` is UNVERIFIED. | "An id is missing from the latest emission" covers both delete and merge. Rename keeps the id. |
| S9 | `CurrencySettingsRepository.homeCurrency(): Flow<String>` is untyped: an unset key becomes the default code. money-correctness 1a adds a typed `observeHomeCurrencyResolution()`. | [V] `A/domain/currency/CurrencySettingsRepository.kt:16`; [C] `docs/features/money-correctness/impact-map.md:26`, domain-spec V7 | Home-currency invalidation consumes the typed observable (money-correctness 1a lands before inc 1, brief §1). |
| S10 | The VM's settings collector computes `isDisabled = !aiEnabled \|\| !assistantEnabled \|\| !queryInterpretationEnabled` and cancels the in-flight job when it becomes disabled. `submitQuery` re-checks the same triple. | [V] `AssistantViewModel.kt:124-157, 210-218` | One "disabled" predicate for invalidation and tap guards (same as INV-C14). |
| S11 | `executeCategoryBreakdown` drops rows with `categoryId == null` (`:146`). Both breakdowns `take(8)`. `ExpenseQueryFilters.currency` is never passed to the repository. | [V] `:146, :176, :240, :411-420` | Breakdown with matched rows but zero rendered rows is **not** "no matching transactions" (§3.8). RF-F5, RF-F6. |
| S12 | One query runs at a time: `_isSubmitting` CAS plus `_currentQueryJob`. `cancelCurrentQuery` sets `errorMessage` and the retry button calls `retryLast`. | [V] `:115-116, 199-204, 363-377`; `AssistantSheet.kt:248-257` | Chip execution uses the same gate and job (INV-F9). |
| S13 | Starter prompts use `SuggestionChip` (`AssistantSheet.kt:296-313`). Clarification options render as `AiRecommendationCard` (`:189-204`). | [V] | Component reuse is a UX-stage choice. No domain impact. |
| S14 | Part C, P3 + INV-C9: "this week/month" are **FULL** units; "this quarter/year" and "today" are **TO_DATE**. OD-C4 mirror shape: FULL compares with FULL, TO_DATE with the same elapsed span. | [C] Part C §0 P3, INV-C9, INV-C11, OD-C4 | Brief example 1's "Oct 1–16" is superseded. "This month" = `[10-01, 11-01)` MONTH FULL, and Compare = October FULL vs September FULL. |

---

## 1. Entities and value objects (all in memory unless stated; no Room schema change)

### 1.1 `SessionContext` (VM-owned; never persisted)

| Field | Type | Null | Meaning |
|---|---|---|---|
| `epoch` | `Long` | no | Monotonic counter, starts at 0. Incremented on **every** set, clear, consume or invalidate. A chip carries the epoch it was built for. |
| `lastIntent` | `FinancialQueryIntent` | yes | Executed intent of the latest successful answer (INV-F2). Non-null ⇒ `lastIntent.filters.period != null` (INV-F3). |
| `sourceItemId` | `String` | yes (iff `lastIntent` null) | Transcript `Result` item id that shows the chips. |
| `basis` | `ChipBasis` | yes (iff `lastIntent` null) | Input to the catalogue (§1.3). Kept so labels re-render on rename. |
| `chips` | `List<FollowUpChip>` (size 0..3) | no | Output of the catalogue for `basis`. |
| `homeCurrencyAtSet` | `HomeCurrencyToken` | yes | Home-currency state observed when `lastIntent` was set: `Code(iso)` or `Unavailable`. |
| `pending` | C-4 `livePendingClarification` | yes | **The same field** as Part C §1.7, referenced and not duplicated. C-4 rules stay authoritative. This feature only adds clear triggers (INV-F19). |
| `executing` | `ExecutingChip(epochAtTap, baseIntent, editedIntent, type)` | yes | Set while a chip edit executes. |
| `note` | `InvalidationReason` | yes | Last invalidation, shown once (INV-F11). Cleared by the next submit, chip tap, New question or clear. |

`lastIntent` is a **frozen absolute snapshot**. Its period is the absolute `PeriodRange` plus `periodGranularity` resolved when the original answer was produced. It is never a relative token in inc 1. Rationale and rollover rule: §3.2.

### 1.2 `QueryShape` (pure, domain, single dispatch rule)
`enum QueryShape { LIST, CATEGORY_BREAKDOWN, MERCHANT_BREAKDOWN, LARGEST, SMALLEST, TOTAL, COUNT, AVERAGE, UNSUPPORTED }`.

`QueryShape.of(intent)` encodes S1's order with C-5's MIN placed after MAX. `ExecuteFinancialQueryUseCase.invoke` dispatches **through** this function, and the chip catalogue reads it (INV-F18).

### 1.3 `ChipBasis` and `PeriodShape`
- `ChipBasis(intent, shape: QueryShape, outcome: NON_EMPTY | EMPTY, drilldownAvailable: Boolean, periodShape: PeriodShape)`.
  - `outcome = EMPTY` iff the answer is `FinancialQueryResult.Empty` (§1.7).
  - `drilldownAvailable` = the transcript item's `drilldownFilter != null`.
- `PeriodShape = Full(g) | ToDate(g) | Custom`. It is derived by `AssistantPeriodResolver.classify(range, granularity)`:
  - Part C §1.4 shape rule;
  - with `granularity == null`, the exact-FULL classification of INV-C11;
  - otherwise Custom.
  - `classify` is a read-only addition to the existing resolver. It is not a new owner.
- `isCalendar(periodShape)` = `periodShape != Custom`.

### 1.4 `FollowUpChip` (no free text)
- `enum FollowUpChipType { PREVIOUS_PERIOD, COMPARE, BY_CATEGORY, SHOW_TRANSACTIONS, REMOVE_FILTER }`.
- `enum RemoveTarget { MERCHANT, AMOUNT, CATEGORY }`.
- `data class FollowUpChip(type, unit: PeriodGranularity? /* PREVIOUS_PERIOD only */, removeTarget: RemoveTarget? /* REMOVE_FILTER only */)`.
- A chip holds **only enums**. Labels are rendered at display time from the chip, `SessionContext.lastIntent` and the latest category id→name map (INV-F12).

### 1.5 `ChipEdit` algebra (pure, domain): `applyFollowUpEdit(intent, chip): FollowUpAction`
- `FollowUpAction = Execute(editedIntent) | Navigate` (Navigate only for SHOW_TRANSACTIONS).
- "Kept" means **byte-for-byte equal**. This covers `rawQuery`, `normalizedQuery`, `answerMode`, `transactionTypes`, `ownership`, `minAmount`, `maxAmount`, `currency`, `sourceType`, `status`, and every field the row does not name.

| Chip | Edit |
|---|---|
| PREVIOUS_PERIOD(g) | `filters.period = resolver.previousFullUnit(period, g)`; `filters.periodGranularity = g`. Everything else is kept, **including `comparison`** (a compared answer shifts both windows). |
| COMPARE | `comparison = PREVIOUS_EQUIVALENT_PERIOD`. Everything else kept. The previous window is computed by the executor through C-3 `previousEquivalent` (INV-C11). |
| BY_CATEGORY | `grouping = CATEGORY`; `metric = TOTAL`; `comparison = NONE` (breakdowns never render comparison). Everything else kept. |
| SHOW_TRANSACTIONS | `Navigate`: emit `OpenTransactions(item.drilldownFilter)`. No intent edit, no executor call. |
| REMOVE_FILTER(MERCHANT) | `filters.merchants = ∅` |
| REMOVE_FILTER(AMOUNT) | `filters.minAmount = null` **and** `filters.maxAmount = null` (one bound is never removed alone) |
| REMOVE_FILTER(CATEGORY) | `filters.categoryIds = ∅` |

Laws (testable):
- **L1 Non-identity.** For every eligible chip, the edited intent differs from the base. Eligibility guarantees this, so a chip that does nothing is never shown.
- **L2 Idempotence.** COMPARE, BY_CATEGORY and REMOVE_FILTER are idempotent.
- **L3 Composition.** PREVIOUS composes: two taps = two units back. Example: Oct → Sep → Aug.
- **L4 Period stays present.** No edit produces `period == null`, so a chip edit can never trigger the executor's period clarification.
- **L5 Fixed scope.** No edit touches `transactionTypes`, `ownership` or `currency`. Min/max are removed only by REMOVE_FILTER(AMOUNT).

### 1.6 `InvalidationReason` and diagnostics
- `enum InvalidationReason { CATEGORY_REMOVED, HOME_CURRENCY_CHANGED, ASSISTANT_DISABLED, VALIDATION_UNAVAILABLE }`.
- `enum FollowUpDiagnosticCode` (closed; log and diagnostics only):
  - `FOLLOW_UP_PREVIOUS_PERIOD`, `FOLLOW_UP_COMPARE`, `FOLLOW_UP_BY_CATEGORY`, `FOLLOW_UP_SHOW_TRANSACTIONS`, `FOLLOW_UP_REMOVE_FILTER`, `FOLLOW_UP_NEW_QUESTION`;
  - `FOLLOW_UP_CONTEXT_INVALIDATED_CATEGORY`, `FOLLOW_UP_CONTEXT_INVALIDATED_HOME_CURRENCY`, `FOLLOW_UP_CONTEXT_INVALIDATED_DISABLED`, `FOLLOW_UP_CONTEXT_INVALIDATED_UNAVAILABLE`;
  - `FOLLOW_UP_CHIP_EXPIRED`.
  - The impact-mapper decides placement: a separate enum (recommended, like Part C's `CLARIFICATION_EXPIRED` token) or entries in `DiagnosticReasonCode`.

### 1.7 Typed empty result (display only; no money semantics)
- `enum EmptyCause { NO_MATCHING_TRANSACTIONS, ALL_EXCLUDED_MISSING_RATE }`.
- `FinancialQueryResult.Empty(cause: EmptyCause, period: PeriodRange, granularity: PeriodGranularity?, comparison: ComparisonOutcome? = null, dataQuality: FinancialQueryDataQuality)`.
  - It carries **no amount, no currency, no count other than `dataQuality.excludedCount`**.
  - The period label is rendered at display time from `period` + `granularity` (§3.9). The domain holds no locale string.
- Rendering: title + the cause's localized line + the period label. Examples: "No matching transactions · Sep 2026" / «Καμία συναλλαγή · Σεπ 2026». ALL_EXCLUDED adds "{n} excluded: missing exchange rate".
- No drilldown button: the VM sets `drilldownFilter = null` for `Empty`.

### 1.8 Typed comparison outcome (TOTAL only)
- `sealed ComparisonOutcome`:
  - `Available(previousPeriod: PeriodRange, previousGranularity: PeriodGranularity?, previous: ComparisonSide)`;
  - `Unavailable(previousPeriod, previousGranularity, reason = MIXED_CURRENCIES)`.
- `sealed ComparisonSide`: `SingleCurrencyTotal(amount: Double, currency: String /* ISO-4217 upper */)` or `NoTransactions`.
- Money:
  - `amount` is the existing per-currency sum of `effectiveAmount` (unchanged arithmetic, `toCurrencyTotals` `:525-534`).
  - Display scale is 2, through the existing `String.format(Locale.US, "%.2f")`, which rounds HALF_UP.
  - Inc 1 adds **no new aggregation, conversion, delta or rounding**.
- `FinancialQueryResult.Summary` gains `comparison: ComparisonOutcome? = null`. When it is non-null, the executor leaves `supportingText = null`, and the UI renderer and VM `toDisplayText` build the localized line. Part C's English `"Previous period:"` literal (RF-C5) is retired on this path.

---

## 2. Invariants

Format: statement → **Owner** (single) → AE.

### Context
- **INV-F1 One in-memory context, never persisted or exported.**
  - `SessionContext` exists only as VM state.
  - It is never written to `ai_chat_*` (rows or `payloadJson`), DataStore, `SavedStateHandle`, Bundle, files, WorkManager data or logs.
  - It is never part of any `FinancialQueryInterpretationInput` or any model prompt, on any route.
  - Inc 1 typed queries **never inherit** context: `submitQuery` passes no context to the interpreter.
  - **Owner:** `AssistantViewModel`. AE-F9, AE-F13, AE-F14.
- **INV-F2 The context source is the executed intent.**
  - `lastIntent` is set only from the exact intent that was passed to the executor. That is the interpreted Structured intent, a C-4 completed intent, a chip-edited intent, or a retried `completedIntent`.
  - It is set only when the result is `Summary`, `Breakdown`, `TransactionList` or `Empty`.
  - It is **never** taken from `result.drilldownIntent` (S3) and never re-derived from text.
  - `Clarification`, `Unsupported` and Error never set it.
  - **Owner:** VM. AE-F20.
- **INV-F3 Context period is absolute and present.**
  - `lastIntent.filters.period != null`. Non-clarification results imply this, since the executor returns a Clarification when the period is null (`:46-49`).
  - The range is absolute, with the `periodGranularity` resolved at answer time. It is never re-resolved against `now`.
  - **Owner:** VM (set rule); resolver (classification). AE-F17.
- **INV-F4 Lifetime = visible transcript.**
  - Context is cleared by:
    - acceptance of a typed `submitQuery` (after the CAS and enabled check);
    - New question;
    - `clearSession` and `clearAllHistory`;
    - invalidation (INV-F11);
    - VM clearance and process death.
  - It **survives** sheet close or reopen, a drilldown round-trip and configuration change (Activity-scoped VM, [C] critique §1).
  - It is not tied to sheet dismissal.
  - **Owner:** VM. AE-F9, AE-F16.

### Chips
- **INV-F5 One pure chip catalogue.**
  - `FollowUpChipCatalogue.chipsFor(basis): List<FollowUpChip>` is deterministic and pure (no I/O, no clock). Its output is ordered by §3.3 priority and capped at 3.
  - The VM never decides eligibility, ordering or the cap.
  - "New question" is not a chip and is not counted.
  - **Owner:** catalogue (domain). AE-F1, AE-F5, AE-F7, AE-F19.
- **INV-F6 One pure edit function.**
  - `applyFollowUpEdit` (§1.5) is the only place that turns a chip into an intent.
  - All non-targeted fields are preserved byte-for-byte; laws L1–L5 hold.
  - **Owner:** same domain object as INV-F5. AE-F1, AE-F6, AE-F28.
- **INV-F7 No private period arithmetic.**
  - PREVIOUS_PERIOD uses `AssistantPeriodResolver.previousFullUnit(range, g)`, a new function on the **existing** C-3 owner: `[get<g>Range(range.start, -1).first, get<g>Range(range.start, -1).second)`, which equals `[…, unitStart(range.start))`.
  - COMPARE relies on C-3 `previousEquivalent` inside the executor.
  - `classify` is the only shape rule.
  - Nothing in this feature performs date arithmetic itself.
  - **Owner:** `AssistantPeriodResolver`. AE-F7, AE-F17, AE-F27.
- **INV-F8 A chip tap never interprets and never touches the network.**
  - Execute path: guard → consume → `applyFollowUpEdit` → `ExecuteFinancialQueryUseCase` → `MapFinancialQueryToNavigationUseCase` → append Result → (history on) persist.
  - Navigate path (SHOW_TRANSACTIONS): guard → navigation event. No new turn, no persistence, no executor call.
  - Neither path calls `InterpretFinancialQueryUseCase`, any provider, `CloudAiPrivacyGate` or the cloud audit.
  - **Owner:** VM (routing). AE-F14.
- **INV-F9 Latest answer only, single use, one query in flight.**
  - Chips render only on the item whose id is `sourceItemId`, and only while that item is the **last transcript item**. Older answers never show chips.
  - A tap is valid only if:
    - `chip.epoch == context.epoch`;
    - the item is `sourceItemId` and is the last item;
    - `executing == null`;
    - the `_isSubmitting` CAS succeeds (the same gate as `submitQuery`);
    - the S10 enabled triple holds;
    - INV-F11 tap-time re-validation passes.
  - Consumption increments `epoch` before execution, so a double tap executes once.
  - An invalid tap is a no-op: no interpreter, executor or persistence call, and the log gets `FOLLOW_UP_CHIP_EXPIRED`. A tap that fails only the CAS is ignored silently, as INV-C14 does.
  - **Owner:** VM. AE-F9, AE-F21.
- **INV-F10 (P-1, confirmed) Chip-origin turns never enter interpreter history.**
  - Transcript: `AssistantConversationItem.User(text = rendered label, origin = FOLLOW_UP, completedIntent = editedIntent)`. The `completedIntent` field is C-4's.
  - Opt-in history on: persist as `role = USER`, `kind = AssistantMessageKind.FOLLOW_UP` (new value, S7; OD-F2).
  - The builder's prior-query selection (INV-C2) becomes `role == USER && kind == QUERY`. This one-token tightening stays in the C-1 owner.
  - History off: nothing is persisted, as today.
  - C-4 clarification-tap turns stay `QUERY` (INV-C13, bounded labels).
  - SHOW_TRANSACTIONS and New question append no turn.
  - **Owner:** builder (selection) and VM (kind tagging). AE-F13.

### Invalidation and labels
- **INV-F11 Invalidation is one pure rule, observed for the VM's lifetime.**
  - `FollowUpContextValidator.check(context, snapshot): InvalidationReason?` is pure.
  - `snapshot = (categoryIds: Set<Long>, homeCurrency: HomeCurrencyToken, assistantEnabled: Boolean, observationHealthy: Boolean)`.
  - Rules, first match wins:
    1. `!assistantEnabled` → ASSISTANT_DISABLED.
    2. `!observationHealthy` → VALIDATION_UNAVAILABLE (fail-closed).
    3. Any id in `lastIntent.filters.categoryIds` ∪ `pending.baseIntent.filters.categoryIds` is ∉ `categoryIds` → CATEGORY_REMOVED.
    4. `homeCurrency != homeCurrencyAtSet` → HOME_CURRENCY_CHANGED. This applies unconditionally, because min/max conversion and breakdown ordering depend on home currency.
  - Not triggers:
    - rename (same id);
    - expense inserts, edits or deletes (chips recompute on tap; old cards are snapshots, as today);
    - cloud, on-device or history toggles;
    - merchant changes (merchants have no lifecycle; a stale merchant yields `Empty` and Remove is promoted).
  - The validator runs on:
    - (a) every emission of the three observations while a context or pending clarification exists;
    - (b) every chip tap before consumption;
    - (c) completion of a chip execution, before `lastIntent` is set.
  - On a non-null reason:
    - context → NONE (C-4 pending cleared too, options expire per OD-C3);
    - `note = reason`;
    - exactly one `FOLLOW_UP_CONTEXT_INVALIDATED_*` code;
    - nothing is executed against the stale id.
  - Observation collectors run in `viewModelScope` for the VM lifetime and do **not** use `WhileSubscribed`, so invalidation also happens while the sheet is closed or the user is on Transactions.
  - Sources:
    - settings: the existing collector (S10);
    - categories: the `CategoryRepository.allCategories` id set (S8);
    - home currency: money-correctness `observeHomeCurrencyResolution()` mapped to `HomeCurrencyToken` (S9).
  - The impact-mapper places the domain-facing seam: a VM data-repository dependency or a thin domain observer.
  - **Owner:** validator (rule) and VM (observation, transition). AE-F10, AE-F11, AE-F12, AE-F26.
- **INV-F12 Labels come from ids and enums at display time.**
  - Chip labels, the context indicator and empty or comparison period labels are rendered by a UI formatter.
  - Inputs: chip enums, `lastIntent` ids and merchant strings, the **latest** category id→name map, the period and granularity, and the system locale and zone.
  - A rename re-renders with the new name.
  - A category id missing from the map renders the bounded fallback ("Remove category filter"). The validator invalidates on the same emission.
  - New strings go into `values`, `values-el` and `values-es`.
  - "Previous {unit}" uses **one key per unit** (Greek gender agreement: «Προηγούμενη ημέρα/εβδομάδα», «Προηγούμενος μήνας», «Προηγούμενο τρίμηνο/έτος»), not a template.
  - **Owner:** UI label formatter. AE-F10, AE-F29.

### Executor (unchanged except empty, comparison, shape, cleanup)
- **INV-F13 One typed empty result; no fake zero or fake currency.**
  - The executor returns `Empty` for **every** shape whose matched-row set is empty after the period, type, category, merchant, ownership and amount filters:
    - `cause = NO_MATCHING_TRANSACTIONS` if no row was excluded for a missing rate;
    - `ALL_EXCLUDED_MISSING_RATE` if ≥1 row was excluded and none remained.
  - This covers TOTAL, AVERAGE, COUNT, LIST and both breakdowns, and MAX/MIN per OD-F1.
  - A breakdown whose matched set is non-empty but whose rendered rows are empty is **not** `Empty` (S11: uncategorized-only, or every group failed sort-key conversion). It stays a `Breakdown` with its `dataQuality`.
  - The strings `"0.00 N/A"` and `"N/A"` are never produced on the assistant executor path. `formatCurrencyTotals` and `formatCurrencyAverages` receive non-empty input only; an empty input is a programming error that surfaces through the bounded Error path, never as text.
  - **Owner:** `ExecuteFinancialQueryUseCase` (sole producer); UI mapping (rendering). AE-F5, AE-F23, AE-F24.
- **INV-F14 Comparison: TOTAL only, single currency or nothing, no delta.**
  - The outcome is produced only in the TOTAL branch when `comparison == PREVIOUS_EQUIVALENT_PERIOD`. Other shapes ignore `comparison`, as today.
  - Let `C` and `P` be the currency sets of the matched rows in the current and previous windows. Rules, in order:
    1. `C = ∅` → `Empty(cause, period, comparison = outcome(P))`.
    2. `|C| > 1` or `|P| > 1`, or `|C| = |P| = 1` with `C ≠ P` → `Unavailable(MIXED_CURRENCIES)`. The current per-currency primary stays as today.
    3. `P = ∅` → `Available(NoTransactions)`.
    4. Otherwise → `Available(SingleCurrencyTotal)`.
    - `outcome(P)` for the `Empty` case: `NoTransactions` if `P = ∅`; `Unavailable` if `|P| > 1`; otherwise `SingleCurrencyTotal`.
  - No conversion, no delta and no percentage are computed.
  - Previous-side exclusions are added to the result's `dataQuality.excludedCount` and `isPartial` (they were discarded, S2). Warning text stays as today, routed under RF-C3.
  - The rule is shared by chip and typed comparisons (one executor, OD-F4).
  - **Owner:** executor. AE-F2, AE-F3, AE-F4.
- **INV-F18 One dispatch rule.** `QueryShape.of(intent)` (§1.2) is the only encoding of the executor's branch order. The executor dispatches through it and the catalogue reads it. **Owner:** executor module. AE-F19.

### Interaction with Part C, process death, diagnostics
- **INV-F15 Bounded diagnostics only.**
  - Exactly one `FollowUpDiagnosticCode` per chip tap, New question, invalidation or expired tap.
  - Log line = code + result class simple name + timing.
  - Never logged: names, ids, amounts, labels, query text, period bounds or exception messages (AGENTS bounded diagnostics, Part C INV-C7).
  - `note` and codes are never persisted.
  - **Owner:** VM. AE-F25.
- **INV-F16 No restore of context or chips.**
  - After process death or VM clearance, `SessionContext` starts at NONE (epoch 0, no indicator, no chips).
  - Today the transcript also starts empty (S6).
  - If a future change renders persisted rows:
    - such items never get chips or an indicator;
    - `FOLLOW_UP` rows render as plain user bubbles;
    - their `completedIntent` is absent.
  - `retryLast` on a `User` item with `origin == FOLLOW_UP && completedIntent == null` is a no-op. A chip label is never resubmitted as text.
  - **Owner:** VM. AE-F15.
- **INV-F17 Retrying a chip turn re-executes the edited intent.**
  - This reuses INV-C16: `retryLast` on a FOLLOW_UP `User` with `completedIntent` runs the executor on it, with no interpretation and no network.
  - On success the context becomes ACTIVE(completedIntent).
  - CancellationException propagates (INV-4).
  - **Owner:** VM. AE-F22.
- **INV-F19 The pending clarification is a context slot owned by C-4.**
  - C-4 alone sets, consumes and expires `livePendingClarification`.
  - This feature adds two things only:
    - (a) New question and invalidation also clear it;
    - (b) a successful completion sets `lastIntent = completedIntent`, so chips appear on the completion's answer.
  - No chips or indicator are shown while only a pending clarification exists. C-4 tests stay green.
  - **Owner:** VM (C-4 machine). AE-F8.
- **INV-F20 Disabled means no context and no taps.**
  - When the S10 triple becomes false, the context is invalidated (ASSISTANT_DISABLED) and the existing collector cancels any in-flight chip job.
  - Re-enabling never restores the context.
  - **Owner:** VM. AE-F12.

Invariant count: **20** (INV-F1..F20).

---

## 3. Rules detail

### 3.1 Context state machine (VM)

States: **NONE**, **ACTIVE**(epoch, lastIntent, source, chips), **EXECUTING**(epochAtTap, base, edited). Pending clarification runs C-4 §3.1 in parallel.

| From | Trigger | Guard | To | Effects |
|---|---|---|---|---|
| any | typed `submitQuery` accepted | CAS + enabled | NONE | epoch++, note cleared; no code (C-4 also clears pending) |
| NONE | success result appended (typed, completion, retry) | validator ok | ACTIVE | epoch++; set lastIntent, source, basis, chips, homeCurrencyAtSet |
| NONE | Clarification / Unsupported / Error appended | — | NONE | — |
| ACTIVE | chip tap, Execute action | INV-F9 guards | EXECUTING | consume (epoch++); append User(label, FOLLOW_UP, edited); persist if history; log the chip code; run the executor in `_currentQueryJob` |
| ACTIVE | chip tap, Navigate action | epoch + item guards, enabled | ACTIVE (unchanged) | `OpenTransactions(item.drilldownFilter)`; log `FOLLOW_UP_SHOW_TRANSACTIONS` |
| ACTIVE | tap fails epoch or item guard | — | unchanged | log `FOLLOW_UP_CHIP_EXPIRED` |
| EXECUTING | success result | `epoch == epochAtTap + 1` (nothing intervened) and validator ok | ACTIVE(edited, new item) | append Result; persist ASSISTANT; drilldown via mapper(edited) |
| EXECUTING | success result | epoch moved (New question or invalidation during execution) | NONE | append Result (honest, computed); no chips |
| EXECUTING | non-CE exception | — | ACTIVE(base, old source) | Error item appended → the source is not last → no chips; indicator stays; retry per INV-F17 |
| EXECUTING | CancellationException | — | ACTIVE(base, old source) | rethrow; no Error item; the User chip item is last → no chips; retry possible |
| ACTIVE / EXECUTING | New question | — | NONE | epoch++; pending cleared; log `FOLLOW_UP_NEW_QUESTION` |
| any with context or pending | validator → reason | — | NONE | epoch++; `note = reason`; one invalidation code |
| any | `clearSession` / `clearAllHistory` | — | NONE | epoch++; no note, no code |
| any | VM cleared / process death | — | (gone) | INV-F16 |

Illegal:
- EXECUTING → interpreter or provider call;
- two EXECUTING states at once (CAS);
- ACTIVE built from `drilldownIntent`;
- a context surviving a typed submit;
- a context restored from `ai_chat_*`.

### 3.2 Period in context (brief Q2)
- **Decision: absolute range + granularity** (frozen at answer time).
  - Chips edit what the user *saw*: the indicator says "Oct", so "Previous month" must mean September even if the clock has rolled into November.
  - A relative token would silently re-resolve to "this month = November" and shift the frame under the user.
  - Inc 1b's Nano path may *describe* the period to the on-device model as a relative token (brief §5). That is presentation only; the context of record stays absolute.
- **Rollover:** a chip tapped after midnight on a month (or week, quarter, year) boundary uses the stored range. A new *typed* query resolves against the new `now` (Part C). AE-F17.
- **Zone change mid-session:** `previousFullUnit` and `classify` use the tap-time system zone, as C-4 does. With granularity carried, the previous unit is computed from `range.start`'s local date in the current zone. A residual 1 h skew after a zone change is accepted and documented.
- **PREVIOUS_PERIOD by shape** (deviation from the brief table → OD-F3):

| PeriodShape | Shown | Edit result | Label |
|---|---|---|---|
| Full(g) | yes | previous full g | "Previous {g}" |
| ToDate(g) (today, this quarter, this year) | yes | previous **full** g, e.g. "this year" (2026-01-01 → now) → all of 2025 | "Previous {g}" |
| Custom | **no** | — | — |

  - Rationale for ToDate:
    - The label must match the query. "Previous year" typed as "last year" already means the previous full calendar year (INV-C9 step 3).
    - Same-elapsed windows belong to Compare (OD-C4), whose label says "compare".
    - For "today" (`[00:00, now)`), C-3's TO_DATE `previousEquivalent` would show "yesterday until 10:00", which is wrong under a "Previous day" label.
- **Compare by shape:** shown for Full and ToDate (the executor uses `previousEquivalent`: FULL→FULL, TO_DATE→same elapsed, OD-C4). **Hidden for Custom**: the duration shift is approximate (Part C §4, inclusive-style model ends) and the chip should not offer approximate answers. Typed custom comparisons keep the C-3 CUSTOM rule.

### 3.3 Chip catalogue (brief Q1)

Predicates over `ChipBasis`:
- `cal = isCalendar(periodShape)`;
- `ne = outcome == NON_EMPTY`;
- `noCat = intent.filters.categoryIds.isEmpty()`;
- `cmpNone = intent.comparison == NONE`;
- `drill = drilldownAvailable`;
- `rm = merchants ≠ ∅ ∨ minAmount ≠ null ∨ maxAmount ≠ null ∨ categoryIds ≠ ∅`.

| QueryShape | PREVIOUS_PERIOD | COMPARE | BY_CATEGORY | SHOW_TRANSACTIONS | REMOVE_FILTER |
|---|---|---|---|---|---|
| TOTAL | cal | cal ∧ cmpNone ∧ ne | noCat ∧ ne | drill ∧ ne | rm |
| MERCHANT_BREAKDOWN | cal | — | noCat ∧ ne | drill ∧ ne | rm |
| CATEGORY_BREAKDOWN | cal | — | — | drill ∧ ne | rm |
| AVERAGE, COUNT, LARGEST | cal | — | — | drill ∧ ne | rm |
| SMALLEST | cal | — | — | drill ∧ ne (today never: S5, RF-F2) | rm |
| LIST | cal | — | — | — (the card button already navigates) | rm |
| UNSUPPORTED | (no context) | | | | |

Decisions:
- **BY_CATEGORY is not offered for AVERAGE or COUNT.** Departs from the brief, which had "TOTAL or AVERAGE". The category branch renders per-category *totals* (S1), so "By category" on an average would silently change the metric (label ≠ query, an anti-pattern in brief §9). It is offered on MERCHANT_BREAKDOWN (it switches the grouping; metric set to TOTAL).
- **COMPARE requires a TOTAL shape** (comparison renders only there, INV-F14) and is hidden once comparison is set (L1).
- **REMOVE precedence: MERCHANT > AMOUNT > CATEGORY.** One Remove chip at a time: the highest-precedence filter present. After a tap the next one can appear.
  - Rationale: merchant matching is the most fragile filter, depending on exact canonical strings from interpretation. Amount filters carry known semantic gaps ([C] F-A, `convertAsOf`). Category ids are stable and define the question's frame, so removing them last keeps the answer closest to what was asked.
- **Min/max kept** on every edit except REMOVE_FILTER(AMOUNT). Silently dropping a filter is the F-G defect class. The labels never contain amount values.
- **Priority, then `take(3)`:**
  - NON_EMPTY: COMPARE > BY_CATEGORY > SHOW_TRANSACTIONS > PREVIOUS_PERIOD > REMOVE_FILTER.
  - EMPTY: REMOVE_FILTER > PREVIOUS_PERIOD. The others are ineligible when empty, so at most 2 chips show.
  - Brief example 1 → [Compare, Show transactions, Previous month]. Example 3 → [Remove Lidl, Previous month]. Example 4 → [Compare, By category, Show transactions].
- **Latest answer only** (INV-F9). An empty catalogue output still shows the indicator.

Labels (INV-F12):

| Chip | EN label |
|---|---|
| PREVIOUS_PERIOD(g) | "Previous day / week / month / quarter / year" |
| COMPARE | "Compare" |
| BY_CATEGORY | "By category" |
| SHOW_TRANSACTIONS | "Show transactions" |
| REMOVE(MERCHANT) | single merchant → "Remove {merchant}"; several → "Remove merchant filter" |
| REMOVE(CATEGORY) | single → "Remove {category name by id}"; several → "Remove category filter" |
| REMOVE(AMOUNT) | "Remove amount filter" |

### 3.4 Comparison rendering (brief Q3)
- TOTAL-only gate: INV-F14 plus the catalogue.
- Partial periods follow Part C OD-C4 (mirror shape): "this month" FULL (Oct) vs Sep FULL; "this quarter" or "this year" TO_DATE vs the same elapsed span. This feature does not fork it. If the owner later flips OD-C4 to same-elapsed, the chip follows automatically.
- Mixed currency → "Comparison unavailable: multiple currencies" / «Η σύγκριση δεν είναι διαθέσιμη: πολλά νομίσματα». Never a cross-currency number, never a zero.
- Empty previous side → "Previous period ({label}): no transactions". Never "0.00".
- No delta in inc 1. A delta would be new money arithmetic: a separate decision, with rounding and scale specified by money-correctness.

### 3.5 "Show transactions" (brief Q4)
- **Decision: drilldown navigation only.** No in-assistant LIST edit.
  - The in-assistant `TransactionList` shows only a count plus the same navigation button (S4), so a LIST turn adds a hop and a persisted row without new information.
  - Navigation reuses the item's `drilldownFilter`, which is `MapFinancialQueryToNavigationUseCase(executedIntent)`: identical to the card button, so there is no second drilldown rule.
  - No transcript turn, no persistence, context unchanged. One code is logged.
- Eligibility: `drill ∧ ne`. Never on `Empty` and never on LIST.
- The chip duplicates the card's button on the latest answer. Whether UX merges the two is RF-F4. The domain semantics are fixed either way.
- Parity: the navigated list may differ from the answer (F-C `convertAsOf` vs snapshot, [C]; S5 `singleOrNull`). Not fixed here; RF-F3 and the money-correctness follow-up.

### 3.6 Invalidation (brief Q5)
- Triggers, observation, rename handling and the fail-closed rule: see INV-F11 and INV-F20.
- UI on invalidation:
  - indicator removed, chips hidden;
  - one non-persisted note line "Context cleared" / «Το πλαίσιο διαγράφηκε», with a per-reason sub-line: a category changed / home currency changed / assistant turned off / couldn't check data;
  - the note disappears on the next submit, tap or clear;
  - the transcript is unchanged.
  - For DISABLED, the existing disabled UI dominates.
- Cloud, on-device and history toggles are **not** triggers: chips are local and deterministic.

### 3.7 Pending clarification (brief Q6)
- Expiry is exactly C-4's INV-C14 plus New question and invalidation (INV-F19).
- A completion's answer creates the context. Example 5 → a C-4 completion, then chips.
- App-authored clarifications carry the original intent + missing slot (C-4). Model-authored ones keep resending text (INV-C15) and never create a context by themselves: the resubmitted text is a typed query, which clears the context and then sets it from its own answer.

### 3.8 Empty result (owner Q1)
- When it is produced, and the breakdown exception: INV-F13.
- COUNT and LIST with zero rows also become `Empty`. A uniform rule; "No matching transactions" states a zero count without a fake money format.
- Interaction with Remove: `Empty` ⇒ `outcome = EMPTY` ⇒ REMOVE_FILTER first.
- `ALL_EXCLUDED_MISSING_RATE` can only occur with an amount filter (conversion runs only then, `:425-454`), so REMOVE_FILTER(AMOUNT) is offered when no merchant filter outranks it. That makes the rows evaluable again.
- Persistence (history on):
  - `kind = RESULT`;
  - `text` = localized rendered line, resolved at persist time (as INV-C6);
  - `payloadJson = {"type":"empty","cause":"<EmptyCause name>"}`. No amounts, no currency, no names.
- The comparison line, when present, goes into the existing `supportingText` payload key as localized text. No new keys for Summary.

### 3.9 Period label rendering (display formatter, UI; system zone and locale)

| Shape | Long form (empty and comparison lines) | Short form (indicator) |
|---|---|---|
| Full MONTH | "Sep 2026" | "Sep" if the year equals the current year, else "Sep 2026" |
| Full WEEK | "Week of 5 Oct 2026" (Monday) | |
| Full QUARTER | "Q3 2026" | |
| Full YEAR | "2026" | |
| Full DAY | "16 Oct 2026" | |
| ToDate g | "{unit label} to date", e.g. "2026 to date", "Q4 2026 to date", "Today" | |
| Custom | "d MMM – d MMM yyyy", where the shown end = the local date of `end − 1 ms` (half-open, Part C P1) | |

Exact patterns and Greek/Spanish forms are owned by UX.

### 3.10 Context indicator
- `ContextIndicatorSpec(categoryIds, merchants, hasOtherFilters, period, granularity, periodShape)` is derived from `lastIntent`.
- `hasOtherFilters` = min/max, types or non-ALL ownership is present. It renders as a bounded "+ filters" token and never shows values.
- Format: up to 2 category names, then "+n"; merchant; period short form.
- Example: "Food · Oct" / «Φαγητό · Οκτ». It carries "New question".
- Shown iff `lastIntent != null`.

---

## 4. Edge cases

| Case | Rule |
|---|---|
| Mixed currencies (no comparison) | Per-currency totals side by side, as today. No home-equivalent line (non-goal). |
| Mixed currencies (comparison) | `Unavailable(MIXED_CURRENCIES)` (INV-F14). |
| Missing or stale FX | Only on the amount-filter and breakdown sort paths, as today. Rows are excluded and counted, never zero-filled. All excluded → `Empty(ALL_EXCLUDED_MISSING_RATE)`. Previous-side exclusions now count. |
| Unavailable vs zero | No `0.00`, `N/A` or `UNKNOWN` in the empty or comparison renderings. The existing `normalizedCurrencyCode()` → `"UNKNOWN"` for blank currency on non-empty rows is routed (RF-F7). |
| Rounding conservation | No new aggregation. Breakdown rows are unchanged. No delta. |
| Time zone / DST | Resolver rules (Part C). Previous month of October 2026 (745 h) = `[09-01 00:00 EEST, 10-01 00:00 EEST)`. Previous week of the week starting Mon 2026-10-26 (after DST ends 10-25) = `[10-19 00:00 EEST, 10-26 00:00 EET)`. |
| Month-end | Full units come from calendar helpers, so there is no 31st overflow. Repeated Previous from March reaches February with the correct length. |
| Month rollover mid-session | §3.2: chips use the frozen range, typed queries use `now`. |
| Future-dated rows | "This month" FULL includes them (RF-C8). Previous never moves forward. No "next" chip. |
| Edits or deletes of expenses mid-session | Chip taps recompute. Old cards are snapshots. No invalidation. |
| Category delete, merge, rename | INV-F11 / INV-F12. |
| Duplicates / idempotency | Double tap executes once (epoch + CAS). Retry is a read-only re-execution. Edits are idempotent (L2). |
| Empty states | No context: no indicator, no chips. Empty catalogue output: indicator only. `Empty` result: ≤2 chips. |
| Partial or failed processing | Executor non-CE error: Error item, chips hidden, retry (INV-F17). CE propagates. |
| Recurring / receipts | N/A (read-only queries). |
| Assistant disabled during execution | The existing collector cancels (CE). The context is invalidated as DISABLED. |
| Category observation flow fails | VALIDATION_UNAVAILABLE (fail-closed). |
| Configuration change | The VM survives, and context and chips are unchanged. |

---

## 5. Persistence impact

**Room schema: NONE.**
- No table, column, index or DAO query change. No version bump, migration or snapshot change. No backfill. Nothing destructive.
- Data-format additions in existing TEXT columns (no migration):
  - (a) `AssistantMessageKind.FOLLOW_UP` stored by enum name (S7). Unknown names already decode to `ERROR`. OD-F2.
  - (b) `payloadJson` `{"type":"empty","cause":…}` for empty answers.
  - (c) The localized comparison line stored under the existing `supportingText` key.
- Readers of `payloadJson` other than the VM writer are UNVERIFIED (as Part C noted). Under C-1 the builder no longer reads it.
- Assistant export of `ai_chat_*` is UNVERIFIED. If an export exists, FOLLOW_UP rows export like QUERY rows.
- In-memory only: `SessionContext`, `QueryShape`, chips, `ComparisonOutcome`, `Empty`, `User.origin`.

## 6. Privacy rules
- **Network / egress:** none added. Chip taps make no interpreter, provider or cloud call (INV-F8). Context never reaches any prompt (INV-F1). Chip-origin turns never reach `priorUserQueries` (INV-F10). C-1 egress is unchanged otherwise.
- **Stored (opt-in history only):** chip labels as FOLLOW_UP USER rows. Labels can contain the user's own category or merchant names, which are already stored in QUERY rows and result rows. Empty and comparison rows hold localized text plus a code. Context, `note`, epoch and chips are never stored.
- **Exported:** nothing new (see §5).
- **Logged:** `FollowUpDiagnosticCode` names, result class names, timings. Never names, ids, amounts, labels, query text or period bounds (INV-F15).
- **Permissions:** none.

---

## 7. Acceptance examples (Given/When/Then; synthetic data)

**Setup** (reuses Part C §7):
- `TimeZone.setDefault(Europe/Athens)`; NOW = 2026-10-16 (Fri) 10:00 EEST; home currency EUR.
- Categories: Food = 1, Transport = 2.
- Provider unavailable unless stated; history off unless stated.
- Every chip AE also asserts: interpreter call count unchanged by the tap; zero provider, privacy-gate and audit calls.

### Brief §4 examples
- **AE-F1** (ex. 1; INV-F2, F5, F6, F7, F8, F12)
  - Given:
    - Food rows Oct 3 100.00 EUR and Oct 10 42.30 EUR; Food row Sep 12 80.00 EUR;
    - "How much on food this month?" executed as {TOTAL, cat {1}, `[10-01, 11-01)` MONTH}.
  - Then:
    - primary "142.30 EUR";
    - chips = [COMPARE, SHOW_TRANSACTIONS, PREVIOUS_PERIOD(MONTH)]. REMOVE(CATEGORY) is eligible but 4th;
    - indicator "Food · Oct".
  - When: tap "Previous month".
  - Then:
    - the executor is called once with {TOTAL, cat {1}, `[09-01, 10-01)`, MONTH}, every other field equal to the base;
    - transcript gains User("Previous month", origin FOLLOW_UP) then Summary "80.00 EUR";
    - indicator "Food · Sep";
    - log has `FOLLOW_UP_PREVIOUS_PERIOD`.
- **AE-F2** (ex. 2; INV-F14)
  - Given: AE-F1 before the tap.
  - When: tap Compare.
  - Then:
    - the executor receives `comparison = PREVIOUS_EQUIVALENT_PERIOD`, period Oct FULL;
    - Summary primary "142.30 EUR", `comparison = Available([09-01, 10-01), MONTH, SingleCurrencyTotal(80.00, EUR))`, `supportingText == null`;
    - rendered "Previous period (Sep 2026): 80.00 EUR", with no delta text;
    - next chips have no COMPARE.
- **AE-F3** (ex. 2 mixed; INV-F14)
  - Given: October Food 100.00 EUR + 20.00 USD; Sep 80.00 EUR.
  - When: tap Compare.
  - Then:
    - primary "100.00 EUR + 20.00 USD";
    - `comparison = Unavailable(MIXED_CURRENCIES)`, rendered "Comparison unavailable: multiple currencies" (`el`: «Η σύγκριση δεν είναι διαθέσιμη: πολλά νομίσματα»);
    - the currency converter is never invoked.
  - Variant: Oct 100.00 EUR only, Sep 50.00 USD only → `Unavailable`.
- **AE-F4** (empty sides; INV-F13, F14)
  - Oct Food 100.00 EUR, Sep none → `Available(NoTransactions)`, rendered "Previous period (Sep 2026): no transactions".
  - Oct none, Sep 80.00 EUR, typed "compare food with previous month" → `Empty(NO_MATCHING_TRANSACTIONS, Oct, MONTH, comparison = Available(SingleCurrencyTotal(80.00, EUR)))`.
  - Both none → `Empty(…, comparison = Available(NoTransactions))`.
  - No rendered string contains "0.00" or "N/A".
- **AE-F5** (ex. 3; INV-F13, F5)
  - Given: "Spending at Lidl last month" executed as {TOTAL, merchants {Lidl}, `[09-01, 10-01)` MONTH}; no Lidl rows in September.
  - Then:
    - result `Empty(NO_MATCHING_TRANSACTIONS, Sep, MONTH)`, rendered "No matching transactions · Sep 2026" (`el`: «Καμία συναλλαγή · Σεπ 2026»);
    - `drilldownFilter == null`;
    - chips = [REMOVE(MERCHANT) "Remove Lidl", PREVIOUS_PERIOD(MONTH)].
  - When: tap "Remove Lidl".
  - Then: the executor receives {TOTAL, merchants ∅, Sep, MONTH}; log `FOLLOW_UP_REMOVE_FILTER`.
- **AE-F6** (Remove precedence; INV-F6)
  - Given: empty {TOTAL, merchants {Lidl}, minAmount 50.0, maxAmount 200.0, cat {1}, Oct}.
  - Then the chip is REMOVE(MERCHANT).
  - After the tap, still empty → REMOVE(AMOUNT); its edit sets min = null **and** max = null, with cat {1} kept.
  - After that tap, still empty → REMOVE(CATEGORY).
- **AE-F7** (ex. 4; INV-F5, F7, OD-F3)
  - Given: "Total this year" executed as {TOTAL, no filters, `[2026-01-01, 2026-10-16 10:00)` YEAR} (ToDate), non-empty.
  - Then chips = [COMPARE, BY_CATEGORY, SHOW_TRANSACTIONS].
  - Tap By category → {TOTAL, CATEGORY, same period, comparison NONE} → Breakdown. Next chips = [SHOW_TRANSACTIONS, PREVIOUS_PERIOD(YEAR)], with no COMPARE.
  - Tap Previous year → period `[2025-01-01, 2026-01-01)` YEAR.
  - Separately, Compare on the original → previous `[2025-01-01, 2025-10-16 10:00)` (C-3 same elapsed).
- **AE-F8** (ex. 5; INV-F19)
  - Given: fallback presentation Clarification with completion base {AVERAGE, MERCHANT, Oct, types {PURCHASE}} (Part C AE-C21 setup).
  - Then: there is no indicator and no chips while it is pending.
  - When: tap "Breakdown".
  - Then:
    - C-4 executes {TOTAL, MERCHANT, Oct, PURCHASE};
    - `lastIntent` equals that intent;
    - chips = [BY_CATEGORY, SHOW_TRANSACTIONS, PREVIOUS_PERIOD(MONTH)];
    - the interpreter was called once in total.
- **AE-F9** (ex. 6; INV-F1, F4, F9)
  - Given: AE-F1's context.
  - When: tap New question.
  - Then:
    - context NONE, indicator hidden, no item shows chips, transcript unchanged;
    - log `FOLLOW_UP_NEW_QUESTION`.
  - When: an old chip is tapped (stale epoch).
  - Then: no executor or interpreter call; log `FOLLOW_UP_CHIP_EXPIRED`.
  - When: "transport" is submitted.
  - Then: the interpreter input contains no field derived from the previous intent (inc 1 never inherits).
- **AE-F10** (ex. 7; INV-F11, F12)
  - Given: AE-F1's context.
  - When: the categories flow emits a set without id 1.
  - Then:
    - context NONE, chips and indicator removed;
    - note shown with CATEGORY_REMOVED;
    - exactly one `FOLLOW_UP_CONTEXT_INVALIDATED_CATEGORY`;
    - zero executor calls.
  - Variant merge 1→2: same result.
  - Variant rename 1 "Food"→"Groceries": no invalidation; indicator "Groceries · Oct"; a REMOVE(CATEGORY) label (where shown) becomes "Remove Groceries".

### Invalidation, disabled, privacy
- **AE-F11** (INV-F11)
  - Context set with home `Code(EUR)`.
  - Emission `Code(USD)` → HOME_CURRENCY_CHANGED, one code.
  - Re-emission `Code(EUR)` before any change → no invalidation.
  - Emission `Unavailable` → HOME_CURRENCY_CHANGED.
- **AE-F12** (INV-F20)
  - Given: a context; a chip execution is in flight.
  - When: `assistantEnabled` → false.
  - Then:
    - the job is cancelled (CE, no Error item);
    - context NONE; one `…_DISABLED`.
  - When: re-enabled. Then there is still no context and no chips.
- **AE-F13** (P-1; INV-F10)
  - Given: history on, session 7, cloud route allowed; AE-F5 flow (typed Lidl query, then tap "Remove Lidl").
  - Then: `ai_chat_messages` has USER/QUERY "Spending at Lidl last month", ASSISTANT/RESULT (empty), USER/**FOLLOW_UP** "Remove Lidl", ASSISTANT/RESULT.
  - When: "transport this month" is submitted.
  - Then:
    - `priorUserQueries == ["Spending at Lidl last month"]`;
    - the cloud request body does not contain "Remove Lidl".
  - Given history off: no rows are written for the chip turn.
- **AE-F14** (INV-F8)
  - Given: cloud route active, history off.
  - When: each chip type is tapped once.
  - Then: zero calls to `InterpretFinancialQueryUseCase`, `HybridQueryInterpretationService`, `CloudAiPrivacyGate` and the cloud audit; zero `ai_chat_*` writes.
  - SHOW_TRANSACTIONS emits `OpenTransactions(filter == item.drilldownFilter)`, makes no executor call and appends no item.
- **AE-F15** (INV-F16)
  - Given: history on with persisted rows; process death; VM recreated.
  - Then: transcript empty, context NONE, no indicator.
  - Given a (future) restored `User(origin FOLLOW_UP, completedIntent = null)` as the last User item: `retryLast()` makes no interpreter and no executor call.
- **AE-F16** (INV-F4) Configuration change: the same VM instance, so `sourceItemId`, chips and indicator are unchanged. Sheet close and reopen: unchanged.
- **AE-F17** (INV-F3, §3.2) Rollover.
  - Given: NOW 2026-10-31 23:50, "food this month" → Oct FULL.
  - When: "Previous month" is tapped at 2026-11-01 00:10.
  - Then: period `[09-01, 10-01)`, not October.
  - Compare at the same time → Oct vs Sep.

### Eligibility, shape, empty, diagnostics, retry, DST
- **AE-F18** (§3.2) Intent period `[100, 200)`, granularity null (Custom) → no PREVIOUS_PERIOD, no COMPARE.
- **AE-F19** (INV-F5, F18) Food Oct, non-empty, one row per shape:

| Shape | Chips |
|---|---|
| AVERAGE | [SHOW_TRANSACTIONS, PREVIOUS_PERIOD, REMOVE(CATEGORY)] |
| COUNT | same as AVERAGE |
| LARGEST | same as AVERAGE |
| SMALLEST | [PREVIOUS_PERIOD, REMOVE(CATEGORY)] (mapper null) |
| LIST | [PREVIOUS_PERIOD, REMOVE(CATEGORY)] |
| CATEGORY_BREAKDOWN (no filters) | [SHOW_TRANSACTIONS, PREVIOUS_PERIOD] |

  `QueryShape.of({COUNT, grouping CATEGORY})` == CATEGORY_BREAKDOWN.
- **AE-F20** (INV-F2)
  - Given: "largest purchase this month" → executed {MAX, Oct}; result `drilldownIntent.merchants == {Pharmacy}`.
  - Then: `lastIntent.filters.merchants == ∅` and no "Remove Pharmacy" chip exists.
- **AE-F21** (INV-F9)
  - A double tap on Previous → the executor is called once.
  - A tap while a typed query is submitting → ignored.
  - A tap on a non-last item's chip is impossible to render; a forced call is a no-op with `FOLLOW_UP_CHIP_EXPIRED`.
- **AE-F22** (INV-F17)
  - Given: the executor throws `IOException` on a chip edit.
  - Then: Error item appended; no chips; indicator still "Food · Oct".
  - When: `retryLast()` runs.
  - Then: the executor is re-invoked with the edited intent, the interpreter is not called, and on success the context = edited.
- **AE-F23** (INV-F13)
  - Given: {TOTAL, minAmount 50.0, Oct}; home EUR; three October rows in JPY with no rate.
  - Then:
    - `Empty(ALL_EXCLUDED_MISSING_RATE)`, `excludedCount = 3`, `isPartial = true`;
    - chips = [REMOVE(AMOUNT), PREVIOUS_PERIOD(MONTH)].
- **AE-F24** (INV-F13, OD-F1)
  - Zero rows → `Empty(NO_MATCHING_TRANSACTIONS)` for each of TOTAL, AVERAGE, COUNT, LIST, CATEGORY_BREAKDOWN, MERCHANT_BREAKDOWN, MAX and MIN.
  - Source scan: `ExecuteFinancialQueryUseCase.kt` contains no `"N/A"` literal.
  - Breakdown with only uncategorized October rows → `Breakdown(rows = [])`, not `Empty`.
- **AE-F25** (INV-F15)
  - Given: AE-F1, AE-F5 and AE-F10 flows.
  - Then:
    - captured log lines contain only `FOLLOW_UP_*` names, class names and timings;
    - none contains "Food", "Lidl", "142.30", "Previous month", a category id or epoch millis of period bounds.
- **AE-F26** (INV-F11)
  - Given: a context with cat {1}; id 1 is removed from the repository fake but the flow emission is delayed.
  - When: a chip is tapped.
  - Then: tap-time validation reads the latest snapshot. If the snapshot already lacks 1 → invalidation and no execution. If it still has 1, post-execution validation (c) catches the later emission and the context ends NONE.
- **AE-F27** (INV-F7, DST)
  - Context October 2026 MONTH FULL → Previous = `[2026-09-01 00:00 EEST, 2026-10-01 00:00 EEST)`.
  - Context week `[Mon 10-26 00:00 EET, Mon 11-02)` WEEK → Previous = `[10-19 00:00 EEST, 10-26 00:00 EET)`.
- **AE-F28** (INV-F6, L1/L2)
  - Given: AE-F2's compared answer.
  - Then chips include BY_CATEGORY. Its edit sets comparison NONE.
  - Applying COMPARE to an intent with comparison already set is never offered.
  - Applying REMOVE(MERCHANT) twice equals applying it once.
- **AE-F29** (INV-F12)
  - REMOVE(CATEGORY) label for cat {1} renders "Remove Food" under `en` and «Αφαίρεση Φαγητό» under `el` (exact Greek phrasing is UX's).
  - cat {1, 2} → "Remove category filter".

AE count: **29**.

---

## 8. Inc 1b frame (not final; the 1b domain pass refines it)

**Ownership:**
- nl-quick-add C1 owns the **shared normaliser**: case fold, accent strip, final sigma ς→σ, whitespace (`workflows/active/nl-quick-add-plan.md:18` [C]).
- This feature owns the **follow-up cue lexicon** (separate from nl-quick-add's expense lexicon) and the **merge function**.
- `AssistantPeriodResolver` stays the period owner and gains Greek period phrases (Part C INV-C9 deferral).
- C-4's completer stays the only completion path. 1b adds `ClarificationSlot` values for ambiguous follow-ups rather than a second mechanism.

**Gate:** inheritance applies only when a **cue class** fires against a live context (`lastIntent != null`). Otherwise the query is fresh.

**Cue classes** (each with an EN+GR lexicon and golden fixtures):

| Class | Example cues |
|---|---|
| PERIOD_ABSOLUTE | "last month", «τον προηγούμενο μήνα» → resolver vs `now` |
| PERIOD_SHIFT | "the month before", "the one before", «τον μήνα πριν» → same as the PREVIOUS chip (frozen range) |
| FILTER_REPLACE | "what about X", "and X?", leading «και X;», «για X;» |
| FILTER_ADD | "also X", "plus X", "as well", «επίσης», «μαζί με», «συν» |
| FILTER_REMOVE | "without X", "all merchants", «χωρίς X», «όλες τις κατηγορίες» |
| METRIC_OR_GROUPING_SWITCH | "by category", "how many", «ανά κατηγορία», «πόσες» |
| COMPARE | "compare", "vs", «σύγκριση» → COMPARE semantics, gated TOTAL |
| SHOW | "show them", «δείξε τες» → navigation semantics |
| RESET | "new question", «νέα ερώτηση» |
| AMBIGUOUS | — |

**Merge algebra per slot:**
- A present slot overrides; a missing slot is inherited.
- Category or merchant:
  - FILTER_REPLACE → replace the set;
  - FILTER_ADD → union;
  - «και» **inside** a self-contained query ("food and transport this month") is not a follow-up cue: both names are present, so it is a fresh frame;
  - a leading «και»/"and" + a single name → REPLACE.
  - If replace vs add cannot be decided → AMBIGUOUS.
- Amount: a new amount cue replaces **both** bounds from the cue (a missing bound becomes null). Never merge a single bound.
- Period: ABSOLUTE replaces from `now`; SHIFT uses the PREVIOUS edit.
- Metric and grouping: switch with the same gates as the chips. For example, a by-category switch on AVERAGE → AMBIGUOUS, not a silent metric change.
- `transactionTypes` and `ownership`: inherited unless the cue names them.

**Ambiguous, unknown or negative cases:**
- AMBIGUOUS or an unknown name → an app-authored clarification (base intent + slot, C-4). Never guess.
- Negatives:
  - a new self-contained topic does not inherit;
  - an unknown category or merchant name → clarification, not a dropped filter.

**Routes:**
- Deterministic merge first on every route.
- Optional local Nano receives structured context only: enums, category ids and names, merchant, and the relative period token as presentation. Never raw prior text. **On-device only.**
- Cloud route: if the deterministic merge does not resolve, show clarification chips. **The context is never sent to cloud.** A query not recognised as a follow-up goes to normal interpretation without context.

**Fixtures:** brief v1 examples 1–7 in EN and GR, plus the negatives above; they pass with no model.

---

## 9. Test impact (FG-19 stop-and-ask list; exact deletions confirmed at plan approval)

| Test | Today / Part C pin | Change |
|---|---|---|
| Part C AE-C9 (MAX no rows → `Unsupported(NO_MATCHING_TRANSACTIONS)`) and the AE-C27 "no rows" clause | Unsupported | **retarget** to `Empty` (OD-F1) |
| Part C AE-C12; `ExecuteFinancialQueryUseCaseTest.kt:90-131` | `supportingText` "Previous period: …" | **mechanical** → assert `Summary.comparison` (CUSTOM `[0, 100)` rule unchanged) |
| Any test asserting `"0.00 N/A"` or an empty-count `TransactionList` | presence UNVERIFIED | **retarget** to `Empty` |
| C-1 `FinancialQueryInterpretationInputBuilderTest` (post-Part C) | USER selection | **add** the FOLLOW_UP exclusion case (AE-F13) |
| `AssistantViewModelTest` constructor sites | — | mechanical (category and home-currency observation deps) |
| C-4 tests AE-C20..C26 | — | must stay green unchanged (INV-F19) |

Proposed guards (not edited here):
- (a) source scan: no `"N/A"` literal in `ExecuteFinancialQueryUseCase.kt`;
- (b) no reference to `SessionContext`/`FollowUpChip` under `data/` or in any `*InputBuilder`/`*InterpretationService`;
- (c) VM test: the chip path never calls the interpreter (AE-F14).

Validation hint: `targeted-unit-test` on the assistant domain and VM classes. No migration tests (schema NONE).

## 10. Housekeeping (inc 1, closes RF-3)
- Delete the private `@Deprecated assistantFilteredExpenses` (`ExecuteFinancialQueryUseCase.kt:466-488`) and its `AssistantFilteredExpenses` holder (`:537-539`). [V] Both are private; they have no caller in the file (critique: "no callers" file-wide UNVERIFIED; private, so no external callers are possible).
- Fix the stale KDocs at `:23-37` and `FinancialQueryModels.kt:44-83`. The assistant executor applies min/max **in memory after `convertAsOf` to home currency** (`:402-464`, list path `:70-104`). It does not compare raw values. Other consumers' semantics are money-correctness's (`AmountSemantics`).

## 11. Proposed architecture-doc changes (not edited here)
- `LEGAL_PATHS.md`, assistant read paths: "Follow-up chip → `FollowUpChipCatalogue` / `applyFollowUpEdit` (pure) → `ExecuteFinancialQueryUseCase` (read-only; no interpretation, no network)".
- `LEGAL_PATHS.md`, Privacy FORBIDDEN:
  - "❌ assistant session context persisted (ai_chat_*, payloadJson, DataStore, SavedState) or included in any model prompt";
  - "❌ FOLLOW_UP-kind turns in model history".
- Release note: "Assistant: follow-up chips; empty answers say 'No matching transactions' instead of '0.00 N/A'; comparisons across different currencies show 'Comparison unavailable'."

## 12. Open decisions (recommended defaults)
- **OD-F1** Should MAX and MIN with no rows also return typed `Empty` instead of Part C's `Unsupported(NO_MATCHING_TRANSACTIONS)`?
  - **Rec: yes.** One empty representation, one card, uniform Remove promotion. Retarget AE-C9 and the AE-C27 no-rows clause.
  - Alternative: keep Part C's rendering and treat `Unsupported(NO_MATCHING_TRANSACTIONS)` as EMPTY in `ChipBasis`. That gives two rules for one fact.
- **OD-F2** P-1 storage under opt-in history.
  - **Rec: new `AssistantMessageKind.FOLLOW_UP`** (TEXT enum name, no migration, S7), with the builder selecting `USER ∧ QUERY`.
  - Alternatives: (b) do not persist chip USER turns; the stored history then shows answers with no question. (c) A `payloadJson` marker on QUERY rows; fragile, because the builder would parse JSON.
- **OD-F3** "Previous {period}" on to-date periods ("today", "this quarter", "this year").
  - **Rec: the previous full unit** ("Previous year" on "this year" = all of 2025), matching typed "last year".
  - Alternatives: hide the chip for to-date; or use C-3 same-elapsed under the label "Same period last {unit}". Same-elapsed stays available via Compare.
- **OD-F4** The mixed-currency comparison rule (INV-F14) also changes **typed** "compare" queries. Today they show "Previous period: 80.00 EUR + 20.00 USD".
  - **Rec: yes, one executor rule.** A chip-only rule would duplicate a business rule across layers.

## 13. Routed findings
- **RF-F1** Part C RF-C1 routes `"0.00 N/A"` to money-correctness. The owner reassigned it to this feature (Q1, 2026-10-16). → orchestrator: update the Part C §11 / money-correctness backlog to avoid double ownership.
- **RF-F2** `MapFinancialQueryToNavigationUseCase.kt:17` excludes MIN. Part C C-5 ("drilldown narrowed exactly as MAX does") gets no drilldown button for MIN. → ai-fixes Part C (C-5).
- **RF-F3** The mapper's `singleOrNull` on categories and merchants (`:26-28`): multi-category or multi-merchant answers navigate to a broader list than the answer covers. → money-correctness drilldown-parity follow-up / assistant bugfix.
- **RF-F4** The SHOW_TRANSACTIONS chip duplicates the card's "open transactions" button on the latest answer. → UX stage (merge or keep; domain semantics fixed in §3.5).
- **RF-F5** Category breakdown silently drops uncategorized rows (`:146`). Breakdowns cap at 8 rows without a "+n more" (`:176, :240`). → assistant bugfix / money-correctness (display honesty).
- **RF-F6** COUNT or AVERAGE with grouping CATEGORY or MERCHANT render total breakdowns (S1). `ExpenseQueryFilters.currency` is never applied (`:411-420`). Both are silent semantic drops. → ai-fixes.
- **RF-F7** `normalizedCurrencyCode()` renders a blank currency as `"UNKNOWN"` (`:523`), a fake currency label on non-empty rows. → money-correctness.
- **RF-F8** Hard-coded English on the executor path: `buildListTitle` literals (`:490-494`), "Across N transactions" (`:382`), warning strings. → l10n track (extends RF-C5).
- **RF-F9** No transcript restore exists today (S6). The brief's "restored opt-in transcript shows no chips" is forward-looking; INV-F16 covers it. → UX stage, for awareness.
