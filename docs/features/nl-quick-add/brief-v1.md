# Feature brief: nl-quick-add — Natural-language quick add (typed, deterministic-first, prefill-only)

Author: feature-strategist (stage 1). Persisted by the orchestrator. Date 2026-10-16.
Status: docs-only; ships after Release 1.0 and after ai-fixes B2. Source facts verified with file:line; the rest marked UNVERIFIED. Root `A` = `app/src/main/java/com/yourname/expensetracker`.

## Problem
Cash and other offline spending (no bank notification, no receipt) can only be logged through the full add-expense form (merchant, amount, then date and category by hand). That friction is why small cash expenses go unrecorded. Users want to type one short line, e.g. "καφές 3,50 χθες", and land on an already-filled form.

## Target users / situations
- Owner and similar users: Greek/English speakers mixing both, Greeklish included (handoff decision, BINDING).
- Moments: just after paying cash (coffee, kiosk, taxi, market), catching up on yesterday, foreign-currency spending while travelling ("dinner 20 usd").
- Frequency: several entries a day for cash-heavy users; share of uncaptured spending not measured (UNVERIFIED; ai-evolution critique §6 Q1 open).
- Devices: owner's phone has Gemini Nano; every other device must get full value from the deterministic parser alone.

## Desired behaviour (what, not how)
1. User types one short line in the foreground; the app opens/fills the existing add-expense sheet. Nothing saved until Save: add-expense → `ManualExpenseRepository.addManualExpense` → TransactionLifecycleCoordinator → `ExpenseDao.insertAtomic()`.
2. Filled fields: amount, currency, date, merchant/description, and only when confident category and payment method. Anything not understood stays as today (empty/form default) and is visibly marked not understood. No silent guesses.
3. Worked examples (expected prefill, not a test spec):
   - "καφές 3,50 χθες" → 3.50, home currency, yesterday (device TZ), merchant "καφές", category only if learned/obvious mapping.
   - "groceries 42.30 lidl" → 42.30, home currency, today, merchant "lidl", category Groceries.
   - "taxi 15€ σήμερα" → 15.00, EUR, today, merchant "taxi".
   - "dinner 20 usd" → 20.00, USD (original kept), today, merchant "dinner"; converted at save.
   - "kafes 3.50" → 3.50, home currency, today, merchant "kafes" (equivalence with "καφές" = Q-D7).
   - Ambiguous "2 καφέδες 7", "lidl 1.500" → amount empty + flagged; other fields still filled.
4. Deterministic parser = the product; Nano = optional enhancer.
   - Deterministic (always, every device): amount (decimal comma/point, thousands separators, attached/detached symbols/codes); currency markers (€, $, £, ISO codes, ευρώ/euro/evro, δολάρια/dollars/dolaria — vocabulary Q-D2); relative dates EN/GR/Greeklish (today/σήμερα/simera, yesterday/χθες/xthes, προχθές/proxthes, weekday = most recent past occurrence) + simple numeric dates; payment keywords (cash/μετρητά/metrita, card/κάρτα/karta); merchant/description = leftover text; category only by exact category-name match or existing learned merchant→category memory.
   - Nano (only when available, proposal over deterministic result): merchant vs item/companion in loose phrasing ("dinner with maria at to steki 20" → "to steki"); Greeklish/misspelling normalisation of merchant; category guess when no learned mapping.
   - Nano never sets or overrides amount, currency or date ("engines compute, AI explains"); disagreement on those is ignored.
5. Fallback: Nano unavailable (no AICore, model missing, BUSY, quota/battery, timeout, user leaves mid-call) → deterministic result stands, no user-visible error, bounded reason code only. Foreground only (AICore foreground-only). Deterministic prefill appears immediately; Nano output arrives later and may only fill fields the user hasn't touched (same as today's pristine-only prefill, `A/ui/screens/addexpense/AddExpenseViewModel.kt:463-484`). Never a cloud route, whatever cloud settings.
6. Currency rule (owner, BINDING: prefill original currency, convert at save via existing converter):
   - Verified: `A/domain/transaction/lifecycle/TransactionLifecycleCoordinator.kt:529-585` calls `CurrencyConverter.convertAsOf(amount, from, home, atMillis = expense.date)` for non-home currency; stores `baseAmount`/`baseCurrency`/`exchangeRateUsed`; no rate → sentinel defaults + warning log. Provenance beyond `exchangeRateUsed` UNVERIFIED (Q-D4).
   - Verified gap: add-expense has no currency picker — state holds only `homeCurrency` (`AddExpenseViewModel.kt:60-63`), save always passes it (`:276-282`, `:380-381`); `ManualExpenseRepository.addManualExpense` "Callers must explicitly pass home currency" (`A/data/repository/ManualExpenseRepository.kt:89`). "dinner 20 usd" cannot be prefilled faithfully today.
   - Never relabel foreign amount as home currency; never convert at parse time. Until add-expense carries a currency (owner Q2), a non-home currency fails closed: amount empty + "foreign currency not supported here yet" hint; never saved as 20 EUR.
   - No currency marker → home currency (the visible form default, not a guess). Don't copy `OnDeviceNotificationParser`'s missing-currency→"EUR" default (`A/data/ai/provider/OnDeviceNotificationParser.kt:168`).
7. Date rules: relative dates in device TZ against device clock at parse time; none → today; future dates (e.g. "αύριο") not prefilled — stays today + flagged (save already rejects after end of today, `AddExpenseViewModel.kt:302-309`); numeric dates day-first (ambiguity Q-D3); date only, no time-of-day.
8. Amount rules: "3,50" and "3.50" → 3.50; unambiguous grouped thousands accepted via existing strict grouping (`A/domain/util/AmountUtils.kt:12-31`, `parseAmount`); single separator + exactly three digits ("1.500", "3,500") ambiguous → empty + flagged unless Q-D1 resolves; several numbers → use the single money-like token (currency marker or decimals), else empty + flagged; times ("21:30"), quantities ("2 καφέδες"), card fragments never the amount; zero/negative/above 1,000,000 cap not prefilled (`AddExpenseViewModel.kt:297`, `ManualExpenseRepository.kt:114`); prefilled amount through the same sanitiser as typed input (`A/ui/util/AmountInputSanitizer.kt`, used at `AddExpenseViewModel.kt:479-480`).
9. Privacy: typed line memory-only for a single parse-and-prefill; never persisted (Room, prefs/DataStore, saved-instance-state Bundle, nav args, deep links, notes, AI artifacts, history, clipboard, crash reports); never logged (Timber or privacy audit); never sent. Nano prompt on-device only. Diagnostics: bounded codes only (parse FULL/PARTIAL/NONE; Nano UNAVAILABLE/BUSY/QUOTA/TIMEOUT/BACKGROUND/INVALID_OUTPUT) — never text, tokens, amounts, merchant names. Only user-saved fields stored, as for manual entry today.

## Must-have scope (increment 1)
- Typed entry line, foreground only, opening/filling the existing add-expense sheet (placement = owner Q1). No new save path.
- Deterministic EN/GR/Greeklish/mixed parser (amount, currency marker, relative + numeric date, payment keyword, merchant leftover, category by exact name or learned mapping) with fail-closed rules.
- Prefill via an extension of the existing one-shot, pristine-only contract (today amount + merchant only: `AddExpenseViewModel.kt:463-484`; sheet params `A/ui/screens/addexpense/AddExpenseSheet.kt:78-106`). Date, category, payment method, currency not yet prefillable (domain-modeler decides).
- Optional Nano enhancer, on-device only, own capability + setting (owner Q3); proposes merchant, category, payment method only, after validation.
- Foreign currency per owner Q2: currency control on add-expense, or fail-closed leave-empty.
- Golden-fixture set (EN/GR/Greeklish/mixed) incl. examples above, ambiguous amounts, future dates, multi-number lines.

## Later increments
Voice (ML Kit GenAI speech is alpha; on-device; same parser); app shortcut / home-screen widget (no `res/xml` shortcut/widget found — UNVERIFIED); "last Tuesday"/"την περασμένη Τρίτη", time-of-day, income/transfer phrasing; sharper category from existing `user_corrections` via existing engines; apply contract (C4) once a second consumer exists.

## Non-goals
Voice in increment 1; multiple expenses per line; receipts/OCR/images; auto-save or any write without Save; any cloud model/fallback; persisting the sentence or input history; new learning beyond `CategorizationEngine.learnMerchantCategory`; splits/groups/shared/"not mine", recurring from text, transfers/income; accepting quick-add text from other apps (share target, intent extras, deep-link query — injection surface; existing `add` deep link stays confirmation-gated, `A/ui/navigation/DeepLinkParser.kt:66-69`); changing post-insert insight generation (ai-fixes B2).

## Existing functionality reused / overlapped
- `A/ui/screens/addexpense/AddExpenseSheet.kt`, `AddExpenseViewModel.kt` — target form, save validation, one-shot prefill (`setInitialValuesIfBlank` `:463-484`, amount + merchant only).
- `A/ui/MainActivity.kt:528-535` (SmartFAB → AddExpense), `:582` (Home `onAddExpense`), `:615-626` (AddExpense overlay).
- `MainActivity.kt:616-621` — opening add-expense reads a clipboard amount via `A/ui/util/ClipboardAmountParser.kt` → `initialAmount`. Direct overlap: one prefill applies, first wins (owner Q5).
- `A/domain/util/AmountUtils.kt` (`parseAmount`), `A/ui/util/AmountInputSanitizer.kt`.
- `TransactionLifecycleCoordinator.kt:529-585`, `A/domain/currency/CurrencyConverter.kt` (`convertAsOf`; `SupportedCurrency.fromActiveCode`).
- `A/data/repository/ManualExpenseRepository.kt:86-143` — save path; merchant normalisation `autoCreate = true` (`:120`); null category → `hybridClassifier.classify` (`:123-127`); post-insert insight `:241` (ai-fixes B2d).
- `A/domain/categorization/CategorizationEngine.kt` (`categorize` `:97`, `learnMerchantCategory` `:483`).
- `A/data/ai/provider/OnDeviceCategorizationAssistService.kt` — possible Nano category leg (fit UNVERIFIED).
- `A/data/ai/provider/OnDeviceNotificationParser.kt` — Nano prompt + JSON validation precedent (EUR-default anti-pattern `:168`).
- `A/domain/naturallanguage/NaturalLanguageSearchEngine.kt:142,555,666-671`, `A/domain/ai/usecase/InterpretFinancialQueryUseCase.kt:299` — English-only relative-date vocabulary; no Greek relative-date words in main source → Greek/Greeklish vocabulary is new.
- `A/domain/ai/validation/AiOutputValidators.kt` (not opened).

## Market patterns (vendor pages/listings; not verified hands-on)
- Typed line → amount/merchant/category/date → tap to confirm ("uber to airport $34.50 yesterday"): [Finny: log expenses by text](https://getfinny.app/blog/log-expenses-by-text), [Finny: voice expense tracker](https://getfinny.app/blog/voice-expense-tracker).
- Human-in-the-loop review before storing as a design choice: [Expense Manager – AI Assistant](https://chromewebstore.google.com/detail/bhjicgdndhekipmjgdkhjoecnhlhaphj), [Finny: AI vs manual](https://getfinny.app/blog/ai-expense-tracking-vs-manual).
- Counter-pattern direct save ("Lunch 10"): [9to5mac on Sumptus](https://9to5mac.com/?p=349679) — rejected (prefill only).
- Multi-item messages common ([Finny](https://getfinny.app/blog/log-expenses-by-text)) — non-goal for increment 1.
- Open-source LLM parsing: [Gullak](https://github.com/mr-karan/gullak) (cloud-model based; n/a).
- No source reports measured accuracy or Greek support.

## Privacy & permissions impact: low (conditional)
No network, cloud, new permission, or exported data; no receipt/notification content. Risk is entirely typed-text handling (memory-only, no logs, no saved state, no artifact). Care: on-device Nano capability on a cloud-forbidden route (like notification parsing); keyboard (IME) learning of typed text is outside app control (UX/domain question). Becomes medium if a currency control is added to add-expense (money path for all manual entries) or the prefill contract is widened.

## Risks
- Nano Greek quality (Greek not in listed ML Kit GenAI languages per ai-fixes brief; current list UNVERIFIED) → Nano limited to merchant/category/payment proposals.
- Prompt API beta, structured output alpha → validated JSON or ignored; quota/BUSY degrade silently.
- Latency/warm-up → deterministic prefill never waits for Nano.
- Locale money parsing ("1.500", "3,500", multiple numbers, "$" USD/CAD/AUD) → ambiguity = leave empty.
- No currency control on add-expense (verified) → owner Q2 or fail-closed.
- Rate unavailable at save → sentinel base values; quick-add may increase frequency; `Timber.w` at `TransactionLifecycleCoordinator.kt:573-577` logs currency + amount on that path (pre-existing; review).
- Sequencing: ai-fixes B2d changes post-insert insight (`ManualExpenseRepository.kt:241`) → quick-add lands after B2.
- Clipboard prefill race (`MainActivity.kt:616-621`).
- Merchant table pollution: `autoCreate = true` (`ManualExpenseRepository.kt:120`) creates merchants from generic words ("καφές", "taxi", "dinner").
- Pre-existing, out of scope: add-expense save error surfaces raw `e.message` (`AddExpenseViewModel.kt:439-447`) → route to hygiene follow-up.

## Success signal (no content telemetry)
- Golden fixtures pass with the deterministic parser alone; every ambiguous fixture yields an empty, flagged field, not a wrong value.
- Tests show typed text never reaches Room, prefs, saved state, logs, audit log, AI artifacts; every quick-add save goes through the coordinator + `insertAtomic` only.
- Fully usable on non-AICore devices; Nano BUSY/quota/timeout → prefill identical to deterministic, bounded reason code only.
- Owner on-device check: a cash expense = one line + Save, fewer manual edits than today. Local counters hold outcome codes only.

## Owner questions (with recommended answers)
1. Where does the quick-add line live? **Rec:** top of the existing add-expense sheet (no new navigation; reuses SmartFAB/Home/empty-state entry points). Alt: separate SmartFAB action.
2. Should add-expense gain a currency control (default home, prefilled by quick-add, active supported currencies only)? **Rec:** yes — only way to honour "prefill original currency, convert at save"; changes all manual entries; raises money/T3 review weight. If declined: foreign currency → amount empty + hint.
3. Nano enhancer on by default? **Rec:** on when Nano is available, own capability + toggle in AI settings, on-device only, never cloud. Deterministic parser always on, no toggle.
4. Payment-method keywords in increment 1? **Rec:** yes, exact keywords only; else form default.
5. Skip clipboard-amount prefill when quick-add is used? **Rec:** yes — quick-add precedence; clipboard prefill unchanged for plain add-expense.

## Domain questions for domain-modeler
- Q-D1 Amount: one separator + three digits; home-currency/locale influence; multiple numbers and quantity words; precision; minimum; `AmountUtils.parseAmount` vs `AmountInputSanitizer`.
- Q-D2 Currency: EN/GR/Greeklish vocabulary, symbols, ISO codes; allowed currencies (`SupportedCurrency` active only?); "$" ambiguity; marker/amount position conflicts; unsupported detected currency.
- Q-D3 Date: full relative vocabulary; weekday resolution ("Δευτέρα" on a Monday = today or 7 days ago?); numeric dates (day-first, year-boundary when year missing); midnight/DST; future flagged vs clamped.
- Q-D4 Conversion at save: no rate → block, warn, or sentinel? Rate provenance stored/shown? Manual foreign expense treated exactly like notification-sourced?
- Q-D5 Prefill contract: prefillable fields (date, category, payment, currency; transaction type fixed PURCHASE?); pristine-only/one-shot when Nano arrives late; clipboard precedence.
- Q-D6 Category: deterministic sources only vs `hybridClassifier` at parse time vs null → save-time auto-categorisation (`ManualExpenseRepository.kt:123-127`); does a correction on a quick-add expense trigger `learnMerchantCategory` like manual entry (UNVERIFIED that manual saves learn)?
- Q-D7 Merchant: generic words as merchants given `autoCreate = true`; Greeklish/Greek via `MerchantAlias`; leftover-text cap (existing 100 chars, `AddExpenseViewModel.kt:481`).
- Q-D8 `ExpenseSource`: stay `MANUAL_ENTRY` or distinguishable source/flag? Flag any schema implication (none assumed).
- Q-D9 AI capability/privacy routing: new on-device-only capability, audit purpose, absent from every cloud-first list; validator for Nano output (merchant/category/payment only); interaction with ai-fixes B2 insight capability so quick-add saves can't trigger cloud.
- Q-D10 Ephemerality: what counts as persistence (saved-state Bundle across process death, IME learning, accessibility services); allowed bounded reason-code set.

## Recommendation
Pursue, deterministic-first as the critique asked. No new write path, no cloud; risk concentrated in money/locale parsing, which fails closed. Preconditions: ai-fixes B2 lands first; owner Q2 (currency control) decided before the domain spec.
