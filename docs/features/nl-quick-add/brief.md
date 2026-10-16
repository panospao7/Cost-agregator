# Feature brief v2: nl-quick-add — increment 1 (typed, deterministic, fill-only)

Author: feature-strategist (stage 1b, single reshape pass). Persisted by the orchestrator. Date 2026-10-16.
Status: docs-only, post-Release 1.0. **Increment 1 only**, expected tier **T2**. Base: `brief-v1.md`. Binding: `critique.md` fact-check corrections and Gate 1 answers (`workflows/active/nl-quick-add-handoff.md` §Human decisions, Q1–Q6 all "recommended"). Root `A` = `app/src/main/java/com/yourname/expensetracker`.

New facts checked in this pass:
- Add-expense default date is `timeProvider.now()` incl. time of day (`A/ui/screens/addexpense/AddExpenseViewModel.kt:81`, `:460`); `updateDate(dateMs)` stores raw millis (`:186-187`).
- Manual save can return `Result.Duplicate` (`DuplicateSkipped`, `InsertConflict` → `A/data/repository/ManualExpenseRepository.kt:195-200`); UI shows "Duplicate expense" (`AddExpenseViewModel.kt:410-413`).
- Save catch `AddExpenseViewModel.kt:439-447` is `catch (e: Exception)`: raw `e.message` shown, no `CancellationException` rethrow.
- ai-fixes Part A changes only `AiSettingsViewModel` and `WarrantyTrackerViewModel` (`workflows/active/ai-fixes-plan.md`), not `AddExpenseViewModel`.

## Problem
Cash/offline spending can only be logged through the full add-expense form; that friction leaves small cash expenses unrecorded. Users want to type one line ("καφές 3,50 χθες"), tap Fill, check, Save.

## Target users / situations
- Owner and similar users mixing Greek/English, Greeklish included (binding).
- Moments: right after paying cash; catching up on yesterday.
- Every device gets full value — no AI dependency in increment 1.
- Honest value note: merchant autocomplete already fills category + average amount for repeat merchants (`AddExpenseViewModel.kt:139-150`). Real gains: date in the same gesture, first-time merchants, payment method, amount + merchant in one step. Modest → keep small.

## Desired behaviour (what, not how)
1. **Placement and trigger (Gate 1 Q4).** Single-line field at the top of the existing add-expense sheet with an explicit **Fill** action. Nothing parsed until Fill; nothing saved until Save. Save via existing path only: add-expense → `ManualExpenseRepository.addManualExpense` → TransactionLifecycleCoordinator → `ExpenseDao.insertAtomic()`.
2. **Fill contract (new; not the one-shot `setInitialValuesIfBlank` channel).** Fill writes each parsed field only if the user hasn't edited it. Clipboard-prefilled amount (`ui/MainActivity.kt:616-621` → `AddExpenseSheet.kt:97-107`) counts as not edited (overwritable). User-typed/picked fields never overwritten. One-shot clipboard channel unchanged for plain add-expense. Every Fill-written field visible before Save. Found-but-unsafe fields visibly flagged ("amount unclear") and left empty/default. No silent guesses.
3. **Deterministic parser** (pure domain function, no DB writes; EN/GR/Greeklish/mixed; accents or none; any case):
   - **Amount:** decimal comma/point; attached/detached symbol ("3,50€", "€3.50"); unambiguous grouped thousands via strict grouping (`A/domain/util/AmountUtils.kt` `parseAmount`). Ambiguous → empty + flagged: one separator + exactly three digits ("1.500", "3,500"), several money-like numbers, quantities ("2 καφέδες"), times ("21:30"). Zero/negative/over-cap not filled. Filled values through the typed-input sanitiser (`A/ui/util/AmountInputSanitizer.kt`).
   - **Currency marker:** home-currency marker accepted and dropped (€, EUR, ευρώ, euro, evro when home = EUR). Any other recognised marker fails closed: amount empty, hint "foreign currency not supported here yet", nothing converted/relabelled.
   - **Date:** relative to device clock + TZ at Fill time: today/σήμερα/simera, yesterday/χθες/xthes, day-before/προχθές/proxthes, weekday names (most recent past), simple numeric dates (day-first). Future ("αύριο") not filled → stays today + flagged (save rejects after end of today, `AddExpenseViewModel.kt:302-309`). Time-of-day for relative dates open (Q-D3).
   - **Merchant/description:** leftover text after amount/currency/date/payment tokens, capped at existing 100 chars. Generic words accepted as merchants (Gate 1 Q6).
   - **Payment method:** exact keywords (card/κάρτα/karta, cash/μετρητά/metrita); form defaults to CASH (`AddExpenseViewModel.kt:36`) so effectively only card changes anything.
   - **Category:** only on exact category-name match or read-only learned merchant→category lookup, shown visibly; else empty and existing save-time auto-categorisation (`ManualExpenseRepository.kt:123-127`) behaves as today. Learning on Save as today (Gate 1 Q5).
   - **Normalisation:** reuse `A/domain/categorization/GreeklishNormalizer.kt`, `A/domain/util/MerchantKeyGenerator.kt`; only date, currency-marker and payment vocabulary is new.
4. **Worked examples** (expected fill, not a test spec; home = EUR):
   - "καφές 3,50 χθες" → 3.50; yesterday; merchant "καφές"; category only if learned/exact; payment unchanged (CASH).
   - "groceries 42.30 lidl" → 42.30; today; merchant "groceries lidl" unless Q-D6 splits a category token; category Groceries on exact name.
   - "taxi 15€ σήμερα κάρτα" → 15.00; today; merchant "taxi"; payment CARD.
   - "kafes 3.50" → 3.50; today; merchant "kafes"; matches "καφές" history only if `MerchantKeyGenerator` equates them (Q-D7).
   - **Foreign fail-closed:** "dinner 20 usd" → amount **empty** + hint; merchant "dinner"; today. Never saved as 20 EUR by accident.
   - Ambiguous: "2 καφέδες 7", "lidl 1.500" → amount empty + flagged; others filled.
   - Future: "taxi 12 αύριο" → 12.00, date stays today, flagged "future dates not allowed".
   - User edit wins: user typed 4.00, then Fill "καφές 3,50" → amount stays 4.00; only other unedited fields filled.
5. **Privacy of the typed line (binding Option A):** memory only (Compose `remember`, never `rememberSaveable`; no `SavedStateHandle`, Room, prefs/DataStore, nav args, deep links, notes, history, clipboard); never logged or sent; loss on rotation/process death acceptable. Filling the merchant must not reach `Timber.w(e, "Merchant suggestion search failed for: $sanitized")` (`AddExpenseViewModel.kt:128`) — **fix that log in this increment** to a bounded reason code (also protects ordinary manual entry). Diagnostics only bounded outcome codes (FILL_FULL, FILL_PARTIAL, FILL_NONE, FOREIGN_CURRENCY_BLOCKED).

## Must-have scope (increment 1, T2)
- Quick-add line + Fill at the top of the existing add-expense sheet; no new screen, navigation, save path or write.
- New per-field fill-if-not-edited contract in add-expense state with "edited by user" tracking for amount, merchant, date, category, payment method; clipboard amount = not edited.
- Deterministic parser as above (pure, fail-closed, no DB writes).
- Read-only category lookup (exact name or existing learned memory).
- Visible flags for unclear/blocked fields + foreign-currency hint.
- Line privacy guarantees + `:128` merchant-text log fix.
- **Golden fixture suite (80–150 cases)** as its own deliverable: worked examples; Greek with/without accents, final sigma, uppercase Greek, Greek question mark `;`; NBSP/thin-space separators, Unicode minus; attached/leading symbols; Greeklish variants ("xthes", "xtes", "htes", "chthes"); weekday forms (Δευτέρα/Δευτ./deftera incl. "today is that weekday"); numeric dates at year boundary; ambiguous amounts, multiple numbers, quantities, times; foreign markers (fail closed) and home markers (accepted); future dates; empty/garbage input ("nothing recognised").
- Tests/guards proving: line never in saved state or logs; every quick-add save via coordinator + `insertAtomic` only; user-edited fields never overwritten.

## Later increments / other features (pointers only)
- **`manual-entry-currency`** (separate T3 money feature, Gate 1 Q1/Q2): currency control on add-expense (supported currencies; reuse candidate receipt-review `CurrencyPicker`, `A/ui/screens/receiptscan/ReceiptScanScreen.kt:883,1385`); no stored rate for expense date → **block save** "no exchange rate for this date"; currency-aware rounding (`AddExpenseViewModel.kt:367-370` always 2dp); recurring-rule currency (`ManualExpenseRepository.kt:182`); edit path. Input: `docs/features/manual-entry-currency/no-rate-verification.md`.
- **Increment 1b:** quick-add currency markers → that control (delivers "prefill original currency, convert at save").
- **Increment 2 — Nano enhancer** (T3 AI/privacy, after ai-fixes B2): on-device-only capability absent from cloud lists, validator; merchant + category proposals only; fills only unedited fields; Nano categories learned only if tapped.
- Further: voice; app shortcut/widget; "last Tuesday"/"την περασμένη Τρίτη"; income/transfer phrasing; sharper categories from `user_corrections` via existing engines.

## Non-goals (increment 1)
Any AI/capability/setting/toggle or cloud route; any schema change; any change to `ManualExpenseRepository` or TLC; new write path, auto-save, write before Save; currency control or conversion (foreign blocked, not converted); changing the one-shot clipboard prefill for plain add-expense; persisting the line / input history / recent lines; multiple expenses per line; splits/shared/"not mine"; recurring from text; transfers/income; accepting text from other apps (share target, intent extras, deep-link query; `add` deep link stays confirmation-gated, `A/ui/navigation/DeepLinkParser.kt:66-69`); new learning beyond today's; post-insert insight changes (ai-fixes B2).

## Existing functionality reused / overlapped
- `A/ui/screens/addexpense/AddExpenseSheet.kt`, `AddExpenseViewModel.kt` (setters `updateMerchant` `:100`, `updateAmount` `:156`, `selectCategory` `:167`, `updateDate` `:186`, payment method); `setInitialValuesIfBlank` (`:463-484`) NOT reused for Fill.
- `ui/MainActivity.kt:616-621` + `A/ui/util/ClipboardAmountParser.kt` (clipboard amount = not edited).
- `GreeklishNormalizer`, `MerchantKeyGenerator`, `A/domain/categorization/SemanticKeywordMatcher.kt`.
- `AmountUtils.parseAmount`, `AmountInputSanitizer`.
- `A/domain/categorization/CategorizationEngine.kt` — read-only at Fill; learning on save unchanged (`A/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt:46-48`, `A/domain/transaction/SourceLearningPolicy.kt:16-21`).
- `ManualExpenseRepository.kt:86-143` unchanged (`autoCreate = true` `:120`; null category → `hybridClassifier` `:123-127`; duplicate `:195-200`).
- `A/domain/naturallanguage/NaturalLanguageSearchEngine.kt`, `A/domain/ai/usecase/InterpretFinancialQueryUseCase.kt` — English relative-date precedent only.
- Independence from ai-fixes B2: no `ManualExpenseRepository`/TLC/insight change → independent in code; gated only by Release 1.0 freeze.
- Overlap with ai-fixes Part A: none in files. The add-expense save catch (`AddExpenseViewModel.kt:439-447`: broad catch, raw `e.message`, no CE rethrow) is owned by no planned slice → **recommended IN scope** (owner Q1).

## Market patterns (vendor pages; not verified hands-on)
[Finny: log expenses by text](https://getfinny.app/blog/log-expenses-by-text), [Finny: voice](https://getfinny.app/blog/voice-expense-tracker) (typed line → confirm); [Expense Manager – AI Assistant](https://chromewebstore.google.com/detail/bhjicgdndhekipmjgdkhjoecnhlhaphj), [Finny: AI vs manual](https://getfinny.app/blog/ai-expense-tracking-vs-manual) (human review before storing); [9to5mac on Sumptus](https://9to5mac.com/?p=349679) (direct save — rejected). Multi-item messages common — non-goal. No source reports accuracy or Greek support.

## Privacy & permissions impact: low
No network, cloud, AI, new permission, exported data, receipt/notification/OCR content. Only sensitive input is the typed line (memory only, never saved/logged/sent). Closes the pre-existing `:128` merchant-text log; if Q1 accepted also the raw `e.message` on save errors (`:439-447`). IME learning outside app control, same as today's merchant field; disabling autocorrect on the line optional (UX).

## Risks
- Locale money parsing → ambiguous = empty + flagged; fixtures carry the weight.
- Fill contract correctness (new per-field state): main risk is overwriting a user value; explicit tests incl. clipboard race (async clipboard landing after user typed).
- Duplicate rejection: second identical "καφές 3,50" same day may return `Result.Duplicate` (`ManualExpenseRepository.kt:195-200`) — unverified whether manual policy fires; relative-date time-of-day affects it.
- Date semantics: epoch millis incl. time (default `now()`); "χθες" time choice affects day buckets, DST, duplicate window.
- Merchant clutter from generic words (accepted, Gate 1 Q6).
- Category visibility: empty category → existing silent save-time auto-categorisation (today's behaviour); UX should say "empty = auto at save".
- Release 1.0 freeze: implementation waits for post-1.0.

## Success signal (no content telemetry)
- All golden fixtures pass; every ambiguous/foreign/future fixture yields empty/default + flagged, never a wrong value.
- Tests prove: line never reaches Room/prefs/saved state/logs/audit; Fill never overwrites a user-edited field; clipboard amount overwritable; quick-add saves only via coordinator + `insertAtomic`.
- Foreign-currency lines can never produce a saved home-currency amount without a deliberate user edit.
- Owner on-device check: typical cash expense = one line + Fill + Save, fewer manual edits than today; local counters outcome codes only.

## Owner questions (new from reshape)
1. Include add-expense save-error hygiene in increment 1 (rethrow `CancellationException`; replace raw `e.message` at `AddExpenseViewModel.kt:439-447` with a bounded localized error)? **Rec: yes** — same file/slice, fixes a privacy + cancellation gap on the path quick-add uses. If declined → hygiene follow-up.

## Domain questions for domain-modeler (increment 1 only)
- Q-D1 Amount: one-separator-three-digits rule; choosing among several numbers/quantity words; precision/minimum; `parseAmount` vs `AmountInputSanitizer` order; NBSP, thin space, Unicode minus.
- Q-D2 Currency markers (detection only): home vs foreign vocabulary; "$" always foreign when home = EUR; unrecognised codes ("abc") = merchant text; exact fail-closed state (amount empty + hint; Save enabled for a manually typed amount?).
- Q-D3 Date: time-of-day for relative dates (now − N days vs fixed local time e.g. 12:00; numeric date without time); weekday resolution ("Δευτέρα" on a Monday); day-first numeric + year inference at boundary; DST/midnight; same-day duplicate question (`ManualExpenseRepository.kt:195-200` via `DuplicateDetectionPolicy`) and effect of the time choice.
- Q-D5 Fill contract: fillable fields (amount, merchant, date, category, payment; type stays PURCHASE); "edited by user" definition per field; second Fill overwriting previous-Fill values not edited by user; unmentioned fields cleared or kept; interaction with merchant autocomplete (`selectSuggestion` `:139-150` also sets category + amount): does Fill trigger suggestions; does a later suggestion tap count as a user edit?
- Q-D6 Category: exact-name match against localized display names (EN/GR) and/or keys; legitimate side-effect-free learned lookup API; remove category-name token from merchant leftover?
- Q-D7 Merchant: does `MerchantKeyGenerator` already equate "kafes"/"καφές"; trimming + 100-char cap; `:128` fix shape.
- Q-D8 `ExpenseSource`: stays `MANUAL_ENTRY` (no schema change); confirm no distinguishable source needed in increment 1.
- Q-D10 Ephemerality: precise list of what counts as persistence; allowed bounded outcome-code set.

## Recommendation
**Pursue.** Increment 1 is a T2 deterministic, fill-only add-expense improvement with no AI, cloud, schema or save-path change; risk concentrated in locale parsing and the new per-field fill contract, both failing closed. Independent of ai-fixes B2 in code; gated only by the Release 1.0 freeze.
