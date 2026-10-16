# Critique: nl-quick-add

Author: feature-critic (independent). Under review: `docs/features/nl-quick-add/brief.md`. Date 2026-10-16. Persisted by the orchestrator.
~22 read-only tool calls; unchecked items marked UNVERIFIED. `A` = `app/src/main/java/com/yourname/expensetracker`.

Verdict RESHAPE (narrow): deterministic parser as product + prefill-only writes is sound; the currency path, the claimed prefill "extension", and the learning side effect are wrong or understated.

## 1) Fact-check
| Claim | Status | Evidence |
|---|---|---|
| Add-expense has no currency control | **HOLDS** | `A/ui/screens/addexpense/AddExpenseViewModel.kt:59-63`, `:276-282`, `:380-381`; `A/data/repository/ManualExpenseRepository.kt:89`. Missed: a `CurrencyPicker` exists in receipt review (`A/ui/screens/receiptscan/ReceiptScanScreen.kt:883,1385`, `ReceiptScanViewModel.kt:561`). |
| TLC converts at save | **HOLDS** | `TransactionLifecycleCoordinator.kt:554-578` |
| "dinner 20 usd → converted at save" | **OVERSTATED** | Needs a stored historical rate as of expense date (`A/domain/currency/CurrencyConverter.kt:241-263`, via `ExchangeRateStore`); null for unsupported codes (`:262-263`). |
| No rate → "sentinel defaults + warning log" | **UNDERSTATED — money defect** | No rate → `baseAmount` stays 0.0 (`A/data/database/entity/Expense.kt:146`); `normalizedEffectiveAmount` falls back to raw amount (`Expense.kt:179`, "legacy row — fall back to raw"); amount sorts use raw in SQL ELSE (`A/data/repository/ExpenseRepository.kt:61-62,70-71`). A 20 USD expense with no rate counts as 20 home units in totals/sorts — contradicts "never saved as 20 EUR" and LEGAL_PATHS Money/Currency "❌ sumOf effectiveAmount across currencies without conversion" (`docs/architecture/LEGAL_PATHS.md:548-549`). `Timber.w` logging currency + amount confirmed (`:573-577`). |
| One-shot pristine prefill "same as today" for late Nano | **OVERSTATED** | `setInitialValuesIfBlank` (`AddExpenseViewModel.kt:463-484`) is all-or-nothing (both amount + merchant blank), once per sheet session, consumed even when skipped (`:470-474`); no per-field touched tracking → new contract, not an extension. |
| Clipboard prefill overlap | **HOLDS, worse** | `ui/MainActivity.kt:616-621` reads clipboard async on open; `AddExpenseSheet.kt:97-107` applies via the same one-shot flag. With in-sheet placement the clipboard has already consumed the flag (maybe set amount) before typing → brief Q5 not implementable as written. |
| `autoCreate = true` merchants | **HOLDS** | `ManualExpenseRepository.kt:120` |
| Null category → `hybridClassifier` | **HOLDS** | `ManualExpenseRepository.kt:123-127` |
| Manual saves learn merchant→category | **HOLDS** | `A/domain/transaction/lifecycle/TransactionSideEffectPlanner.kt:46-48` queues learning for trusted sources; `A/domain/transaction/SourceLearningPolicy.kt:16-21` lists MANUAL_ENTRY as trusted. UNVERIFIED: whether learning distinguishes user-chosen vs auto-assigned category. |
| "Greek/Greeklish vocabulary is new" | **PARTLY WRONG** | Greek relative-date words absent, but `GreeklishNormalizer` exists, used by `A/domain/util/MerchantKeyGenerator.kt:3,20-21,45` and `A/domain/categorization/SemanticKeywordMatcher.kt:24,68` → Q-D7 likely already answered by merchant keys. |
| Post-insert insight / B2d conflict | **HOLDS, weaker** | `ManualExpenseRepository.kt:233-241`; `workflows/active/ai-fixes-plan.md`. Deterministic-only quick-add needs no `ManualExpenseRepository` change → only the Nano increment depends on B2. |
| Raw `e.message` on save error | **HOLDS** | `AddExpenseViewModel.kt:439-447` |
| NEW: merchant field logs its text | **NEW** | `AddExpenseViewModel.kt:128` `Timber.w(e, "Merchant suggestion search failed for: $sanitized")` — quick-add filling via `updateMerchant` can leak typed-line text to logs on failure. |
| NEW: existing near-one-line flow | **NEW (value)** | `selectSuggestion` (`AddExpenseViewModel.kt:139-150`) already fills category + average amount from merchant autocomplete. |
| No saved state in add-expense | **HOLDS** | No `SavedStateHandle`/`rememberSaveable` under `ui/screens/addexpense`; the line must not introduce one. |

## 2) Per-scope verdict
| Scope item | Verdict | Why |
|---|---|---|
| Typed line filling existing sheet, no new save path | **KEEP / RESHAPE semantics** | Explicit user "Fill" action filling fields the user hasn't edited; clipboard-prefilled values count as not edited; not via the one-shot channel. |
| Deterministic EN/GR/Greeklish parser | **KEEP** | Reuse `GreeklishNormalizer`/`MerchantKeyGenerator`; add only date/currency/payment vocabulary; pure domain function, no DB writes. |
| Prefill contract "extension" | **RESHAPE** | New per-field touched/filled contract in `AddExpenseState`/VM; only `selectedCategoryId`, `paymentMethod`, `date` setters exist. |
| Category: exact name or learned mapping | **KEEP (read-only), visible** | Visible chip before Save; leave save-time auto-categorisation alone; no new guesses. |
| Payment keywords | **KEEP** | Cheap; form defaults to CASH (`AddExpenseViewModel.kt:36`) so effectively only "card" matters. |
| Nano enhancer, own capability/toggle | **DEFER to increment 2** | New capability, audit purpose, validator, cloud-exclusion, B2 plumbing; Greek unsupported; beta/alpha; least value on short cash lines. |
| Currency control on add-expense (Q2) | **SPLIT OUT** | Separate money feature affecting all manual entries; blocked on Q-D4 (silent raw fallback). Increment 1 fails closed for foreign currency. |
| Golden fixtures | **KEEP, own slice** | Underestimated (§3). |

## 3) Hidden scope and risks
Blocking (scope as written):
- **B1 — foreign amounts enter totals unconverted** when no rate exists (`Expense.kt:146,179`; `ExpenseRepository.kt:61-62,70-71`). Before any currency control ships, Q-D4 must choose block-save vs visible "unconverted" flag excluded from normalized totals; "sentinel + log" unacceptable. Also: currency-aware rounding (VM always 2dp HALF_UP, `AddExpenseViewModel.kt:367-370` — wrong for zero-decimal currencies); recurring rule inherits currency (`ManualExpenseRepository.kt:182`).
- **B2 — prefill contract can't do what's claimed** (one-shot, all-or-nothing, consumed by clipboard first: `MainActivity.kt:616-621`, `AddExpenseSheet.kt:97-107`, `AddExpenseViewModel.kt:463-484`). Per-field touched state must be designed new.
Cautions:
- AI guesses become trusted learning: every MANUAL_ENTRY save queues trusted merchant→category learning (`TransactionSideEffectPlanner.kt:46-48`); Nano/auto categories accepted by Save become permanent → in increment 2 Nano categories must not feed strong learning unless explicitly picked (Q-D8).
- Merchant pollution via `autoCreate = true` (`:120`): "καφές", "taxi", "dinner" become merchants with learned mappings — UX decision, not blocker.
- Logging: fill merchant directly or fix `AddExpenseViewModel.kt:128` log in the same slice; TLC no-rate log (`:573-577`) matters once currency feature exists.
- Process death: line uses `remember`, not `rememberSaveable`; guard/test asserts it; loss on rotation acceptable.
- IME learning overstated (merchant/amount fields already use IME); disabling autocorrect on the line is a nicety.
- Date semantics: `date` is epoch millis incl. time; "χθες" needs a defined time (now−1 day or fixed local time) — affects day buckets, DST, same-day duplicate window. UNVERIFIED whether MANUAL dedup rejects a second identical "καφές 3,50" same day — check.
- Fixtures underestimated: Greek accents/no accents, final sigma, uppercase Greek, `;` Greek question mark, NBSP/thin-space separators, Unicode minus, attached/leading symbols ("€3.50", "3,50€"), Greeklish variants ("xthes", "xtes", "htes", "chthes"), weekday forms (Δευτέρα/Δευτ./deftera), numeric dates at year boundary → 80–150 cases.
- Value vs better form: autocomplete already fills category + amount for repeat merchants (`:139-150`); real gains = date in the same gesture, first-time merchants, payment method. Modest — keep it small.

## 4) Recommended shape
1. **Increment 1 — deterministic quick-add (T2).** Line at top of add-expense + explicit Fill + per-field fill-if-not-edited; fills amount, date, merchant, payment keyword, category (exact name or read-only learned lookup); unclear fields visibly flagged; foreign-currency marker → amount empty + hint. No AI, no new capability, no schema change, no `ManualExpenseRepository`/TLC change. Golden fixtures as own slice. Independent of ai-fixes B2 in code (subject only to the Release 1.0 freeze).
2. **Separate feature "manual-entry currency" (T3, money).** Currency control on add-expense (supported currencies only; reuse receipt-review `CurrencyPicker`); Q-D4 resolved (no rate → block or explicit unconverted state, never silent raw fallback); currency-aware rounding; recurring-rule currency; edit path (`TransactionUpdateKind` CURRENCY exists per `LEGAL_PATHS.md:348`; edit UI UNVERIFIED); migration-free if possible. Then increment 1b maps quick-add currency markers into that control.
3. **Increment 2 — Nano enhancer (T3 AI/privacy), after ai-fixes B2.** On-device-only capability, absent from all cloud lists, validator; proposes merchant + category only; fills only unedited fields; Nano categories excluded from trusted learning unless user confirms.

## 5) Owner questions (with recommended answers)
1. Split currency control into its own money feature; increment 1 fails closed on foreign currency? **Rec: yes** (binding goal reached in 1b, not dropped).
2. Manual foreign expense with no stored rate for its date? **Rec: block save** with "no exchange rate for this date"; never silently add raw amount to totals.
3. Defer Nano to increment 2? **Rec: yes** — increment 1 deterministic-only, no AI capability/toggle; revisit after ai-fixes B2 and measuring merchant misses.
4. Placement + fill semantics (replaces brief Q1/Q5): line at top of add-expense with a Fill action filling only unedited fields; clipboard-prefilled amount counts as not edited (overwritable)? **Rec: yes.**
5. Learning of categories not explicitly chosen? **Rec:** increment 1 shows deterministic categories visibly and learns as today; increment 2 Nano categories not learned unless tapped.
6. Generic words as merchants ("καφές", "taxi")? **Rec: yes for increment 1** (manual entry already does this); revisit if lists get noisy.

## 6) Verdict: **RESHAPE**
Clears if: increment 1 is deterministic-only with fail-closed foreign currency and a newly specified per-field fill contract; the currency control becomes its own T3 money feature gated on "no rate → no silent raw total"; Nano deferred to increment 2.
