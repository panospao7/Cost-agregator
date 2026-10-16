# Domain spec: nl-quick-add — increment 1 (typed, deterministic, fill-only)

Author: domain-modeler (stage 3). Date 2026-10-16. Status: docs-only (Release 1.0 frozen; implementation post-1.0, after ai-fixes).
Inputs: `docs/features/nl-quick-add/brief.md` (v2, Q-D1..Q-D10 = agenda), `critique.md`, `workflows/active/nl-quick-add-handoff.md` §Human decisions (binding: Gate 1 Q1–Q6 "recommended"; brief v2 Q1 YES = save-error hygiene in scope).
Root `A` = `app/src/main/java/com/yourname/expensetracker`. "VM" = `A/ui/screens/addexpense/AddExpenseViewModel.kt`. "Sheet" = `A/ui/screens/addexpense/AddExpenseSheet.kt`.

---

## 0. Verification ledger

Verified by reading source (file:line). Everything else is marked UNVERIFIED where used.

| ID | Fact | Evidence |
|---|---|---|
| V1 | `AddExpenseState` defaults: merchant "", amount "", category null, payment CASH, type PURCHASE, `date = timeProvider.now()` (raw millis incl. ms); `reset()` rebuilds with `now()` and keeps `homeCurrency` | VM :32-64, :81, :454-461 |
| V2 | `updateMerchant` does `take(100)`, clears `merchantError`/`saveResult`, debounces `expenseRepository.searchMerchants` (≥2 chars, 300 ms). Its catch is `catch (e: Exception)`, so it also catches `CancellationException`, and it logs `Timber.w(e, "Merchant suggestion search failed for: $sanitized")` (raw text + throwable) | VM :100-137, :126-128 |
| V3 | `selectSuggestion` sets merchant. It sets category whenever the suggestion has one, even over a user-picked category. It sets amount = `sanitize("%.2f" avgAmount)` only if amount is blank | VM :139-150 |
| V4 | `setInitialValuesIfBlank` is all-or-nothing (amount AND merchant blank) and runs once per session. The flag is consumed even when it is skipped. The raw prefill string is passed to `AmountInputSanitizer.sanitize` | VM :463-484; Sheet :97-107 |
| V5 | Save validation: merchant non-blank; `AmountUtils.parseAmount(amount)` > 0 and ≤ 1,000,000; `date ≤ getEndOfDay(now) − 1`. Amount is normalised to 2dp HALF_UP. `Result.Error` shows `result.message` raw. The outer `catch (e: Exception)` shows `e.message` raw and has no CE rethrow | VM :285-309, :367-370, :415-425, :439-449 |
| V6 | The date picker keeps the H:M:S of the current `dateMs`, sets ms to zero, uses `ZoneId.systemDefault()`, and changes only the date. The sheet has no time picker (only `DatePicker` was found) | Sheet :799-869 |
| V7 | Clipboard: `MainActivity.kt:616-621` reads the clipboard asynchronously on sheet open. `ClipboardAmountParser` returns the **raw matched string** (comma decimals allowed: "12,50"), which goes to `sanitize`. `sanitize` keeps only digits and '.', so **"12,50" becomes "1250"**. This is a pre-existing defect, see §15 F1 | `A/ui/util/ClipboardAmountParser.kt:8-22`; `A/ui/util/AmountInputSanitizer.kt:36` |
| V8 | `parseAmount`: "1.500"→1.5; "3,500"→3500; "3,50"→3.5; "1.234.567"→null; "1.234,56"/"1,234.56"→1234.56; strips a leading `€$£¥` and `E/e`; minus signs `- − ‑ – —`; deletes all whitespace incl. NBSP/U+202F/U+2007; `/` → null; `OTHER_SYMBOL` (emoji) → null; logs bounded constant codes only | `A/domain/util/AmountUtils.kt:39-151` |
| V9 | `AmountInputSanitizer.sanitize`: keeps digits and '.', **truncates** (does not round) the fraction to 2 digits, allows at most 10 integer digits, ".5"→"0.5" | `A/ui/util/AmountInputSanitizer.kt:33-57` |
| V10 | `MerchantKeyGenerator.generate("καφές") == generate("kafes") == "kafes"`. `generate("χθες") == "chthes"` ≠ "xthes" | `A/domain/util/MerchantKeyGenerator.kt:43-48`; `GreeklishNormalizer.kt:24-66` |
| V11 | `GreeklishNormalizer.normalize` lowercases. For Greek text it strips accents **before** diphthongs ("ού"→"ou"); `toLatinStatic` (used by merchant keys) does not ("ού"→"oy") | `GreeklishNormalizer.kt:55-66, :115-154` |
| V12 | `CategorizationEngine.categorize/categorizeWithContext` includes fuzzy (2c), semantic keyword (3), context (4) layers and logs raw merchant at `Timber.d` (:185-186); it calls `MerchantNormalizer.normalize(autoCreate=false)` (:107) | `A/domain/categorization/CategorizationEngine.kt:97-231` |
| V13 | **`MerchantNormalizer.normalize(…, autoCreate=false)` is NOT side-effect-free.** A fuzzy match ≥0.95 calls `linkAliasToCanonical(rawName, …)` (alias DB write) and may log `rawName` | `A/domain/intelligence/ml/MerchantNormalizer.kt:96-104` |
| V14 | `MerchantCategoryRepository.getCategoryForMerchant` goes through `engine.normalize`, so it inherits V13. `getCategoryByNormalizedCanonical` is a plain DAO read with deterministic ordering (confidence, timesUsed, lexical). The column is nullable, indexed, **not unique** | `A/data/repository/MerchantCategoryRepository.kt:44-51`; `MerchantCategoryDao.kt:27-50`; `MerchantCategory.kt:40-48` |
| V15 | `CategorizationEngine.normalizedCanonicalNameForMerchant` is non-suspend and pure: `MerchantCanonicalizer.canonicalize` (no deps, string ops) then `GreeklishNormalizer.normalize` | `CategorizationEngine.kt:514-517`; `MerchantCanonicalizer.kt:13,56-` |
| V16 | `Category` has only `name` (no key/localisation). Default names are English ("Groceries", "Transport", "Food", …, "Home", "Kids", "Gifts", "Legal & Gov", "Uncategorized"). The sheet shows `category.name` raw | `A/data/provider/MerchantCategoryProvider.kt:11-32`; Sheet :653, :750, :776 |
| V17 | Manual create uses `DeduplicationMode.STANDARD`. The in-transaction precheck is `isDuplicateCurrencyAware`: ±5 min window, amount tolerance, merchantKey exact → prefix-containment → raw merchant, same currency, same type. `dedupeKey` = amount_merchantKey_(date / 5 min)_currency_type | `CreateExpenseRequest.kt:134`; `TransactionLifecycleCoordinator.kt:587-678`; `ExpenseDao.kt:809-850`; `DuplicateDetectionPolicy.kt:21,133-195` |
| V18 | `addManualExpense`: `normalize(merchant, autoCreate=true)` (:120); null category → `hybridClassifier` (:124-127); `source = MANUAL_ENTRY` (:136); `DuplicateSkipped/InsertConflict → Result.Duplicate` (:195-200); `Result.Error` carries joined validation errors / raw `exception.message` (:205-216) | `A/data/repository/ManualExpenseRepository.kt` |
| V19 | `SupportedCurrency` catalog (28 entries, HRK inactive). Symbols: € $ £ ¥(JPY, CNY) Fr C$ A$ NZ$ MX$ S$ HK$ ₩ ₺ ₽ ₹ R$ R kr(SEK, NOK, DKK, ISK) zł Kč Ft lei лв kn | `A/domain/currency/CurrencyConverter.kt:21-49` |
| V20 | `PaymentMethod {CARD, CASH, BANK_TRANSFER, UNKNOWN}` | `A/data/database/entity/Expense.kt:258-263` |
| V21 | `TimeProvider` exposes only `now(): Long`; day boundaries use `ZoneId.systemDefault()`; `getEndOfDay` = start of next local day | `A/domain/util/TimeProvider.kt:35-41`; `TimePeriodUtils.kt:490-497` |
| V22 | Single legal create path: TLC `createExpense*V2` → `ExpenseDao.insertAtomic()` only from the coordinator | `docs/architecture/LEGAL_PATHS.md:23-49` |

UNVERIFIED (each rule that depends on one of these names it):
- U1 Purity of `MerchantNormalizer.cleanMerchantName` / `merchantRules` (not used by this spec).
- U2 Whether the Sheet has an edit mode.
- U3 Whether save-time learning distinguishes user-chosen from auto-assigned category (critique).
- U4 The display form of the saved merchant after canonical resolution. "kafes" resolves via searchKey equality to the canonical created by "καφές" (V10 + `MerchantNormalizer.kt:86-94`), but its `normalizedName` text was not traced.
- U5 Whether a local diagnostic counter facility exists for outcome codes.
- U6 Whether the VM's `categories` flow includes archived/hidden categories.
- U7 Compose API availability for disabling IME personalised learning.

---

## 1. Components and single owners

| Component | Layer | Owns | Must not |
|---|---|---|---|
| **C1 `QuickAddParser`** (proposed `A/domain/quickadd/`) | domain, pure Kotlin | Tokenising, vocabulary, amount/currency/date/time/quantity/payment/merchant rules, exact-name category match. Signature (shape only): `parse(input: QuickAddInput): QuickAddParseResult` | Do any I/O, Room, repository, Android, clock read, `Locale.getDefault()`, logging of its own, or AI/network. It may call `AmountUtils.parseAmount` (whose existing bounded `Timber.w` codes are tolerated, V8) |
| **C2 `QuickAddLearnedCategoryLookup`** (proposed `A/domain/quickadd/`) | domain, read-only, suspend | Learned merchant→category lookup: key = `CategorizationEngine.normalizedCanonicalNameForMerchant(merchant)` (V15, single owner of key derivation), read = `MerchantCategoryRepository.getCategoryByNormalizedCanonical(key)` (V14) | Call `categorize*`, `CategorizationEngine.normalize`, `getCategoryForMerchant`, `MerchantNormalizer.*`, `traceDecision`, any write, any log containing input |
| **C3 VM fill contract** | ui/VM | Field origins, flags, hints, Fill gating, atomic apply, one-shot channel consumption, suggestion guard, outcome code, `:128` log fix, save-error hygiene | Store the line; write to DB; change the save path or validation |
| **C4 Sheet** | ui/Compose | Holds the typed line (`remember` only), Fill action, shows flags/hints, makes every Fill-written field visible | Use `rememberSaveable`, nav args, or persist the line |
| **Save path (unchanged)** | data/domain | `ManualExpenseRepository.addManualExpense` → `TLC.createExpenseDbOnlyV2` → `ExpenseDao.insertAtomic()`; source `MANUAL_ENTRY`; learning as today | Change anything in this increment |

No business rule lives in two components. Amount canonicalisation, date resolution and vocabulary live only in C1. Fillability lives only in C3. Learned-key derivation stays in `CategorizationEngine` (reused, not copied).

---

## 2. Value objects

**2.1 `QuickAddInput`** (transient, never stored):
- `line: String`, at most 200 chars (§4.1).
- `nowEpochMs: Long` from `timeProvider.now()` at Fill.
- `zone: ZoneId` from `ZoneId.systemDefault()` at Fill.
- `homeCurrency: String`, non-null ISO code, from `state.homeCurrency`.
- `categories: List<CategoryRef(id: Long, name: String)>`: the VM's `categories` value at Fill, i.e. the picker list (U6).

**2.2 `FieldParse<T>`** = `Value(v: T)` | `Flagged(reason: FlagReason)` | `Absent`. A field result is exactly one of these three.

**2.3 `QuickAddParseResult`** — no raw line, no token list, no currency code, no foreign amount:

| Field | Type of `Value` | Can be `Flagged`? |
|---|---|---|
| `amount` | `AmountText`: String matching `^\d{1,7}\.\d{2}$`, value in [0.01, 1000000.00]; home currency implied; scale 2; produced without rounding or truncation | yes |
| `date` | `ResolvedDate(localDate: LocalDate, epochMs: Long)` | yes |
| `merchant` | `MerchantText`: 1..100 chars, trimmed, non-blank | **never** (Value or Absent) |
| `payment` | `PaymentMethod` ∈ {CARD, CASH} | yes |
| `category` | `CategoryMatch(categoryId: Long, source = EXACT_NAME)` (C1 only; LEARNED is added by C2) | yes |
| `lineHints` | `Set<LineHint>` | — |

**2.4 `FlagReason`** — closed enum, bounded. The precedence inside a field is the order listed.
- Amount: `AMOUNT_FOREIGN_CURRENCY` > `AMOUNT_QUANTITY` > `AMOUNT_MULTIPLE_NUMBERS` > `AMOUNT_AMBIGUOUS_SEPARATOR` > `AMOUNT_UNCLEAR` > `AMOUNT_NOT_POSITIVE` > `AMOUNT_OVER_LIMIT`.
- Date: `DATE_AMBIGUOUS` > `DATE_UNSUPPORTED` > `DATE_INVALID` > `DATE_FUTURE`.
- Payment: `PAYMENT_CONFLICT`.
- Category: `CATEGORY_AMBIGUOUS`.

**2.5 `LineHint`** — closed: `FOREIGN_CURRENCY_NOT_SUPPORTED`, `TIME_IGNORED`, `LINE_TOO_LONG`, `NOTHING_RECOGNISED`.

**2.6 `FieldOrigin`** per fillable field (amount, merchant, date, category, payment):
- `DEFAULT`: initial or reset value.
- `PREFILL`: one-shot initial values (clipboard amount, `initialMerchant`).
- `FILL`: written by quick-add Fill.
- `USER`: any user gesture on that field, including a suggestion tap, the date picker and chips.

**2.7 `OutcomeCode`** — closed: `FILL_FULL`, `FILL_PARTIAL`, `FILL_NONE`, `FOREIGN_CURRENCY_BLOCKED`.

**2.8 Diagnostic reason codes** — closed: `MERCHANT_SUGGESTION_SEARCH_FAILED`, `ADD_EXPENSE_SAVE_FAILED`, `QUICK_ADD_CATEGORY_LOOKUP_FAILED`.

**2.9 VM state additions** — enums only, no String:
- `fieldOrigins: Map<QuickAddField, FieldOrigin>`
- `fieldFlags: Map<QuickAddField, FlagReason>`
- `lineHints: Set<LineHint>`
- `lastFillOutcome: OutcomeCode?`

All of these are cleared by `reset()`.

---

## 3. Invariants

| ID | Invariant (testable) | Single owner |
|---|---|---|
| **INV-1** | Fill never changes a non-fillable field. A field is fillable iff origin ∈ {DEFAULT, PREFILL, FILL}, **or** it is a text field (amount, merchant) whose current value is blank. Date, category and payment with origin USER are never fillable | C3 |
| **INV-2** | The fill field set is closed: Fill may write only amount, merchant, date, category, payment, plus the §2.9 state. It never touches notes, transactionType, recurring, transfer, ownership/shared fields, or homeCurrency | C3 |
| **INV-3** | No silent guess. A field the parser cannot resolve uniquely and safely is `Flagged` or `Absent`, never a `Value`. A `Flagged` result writes the field's default ("" / today-now / CASH / null) into a fillable field, never a partial, rounded or truncated value | C1 (decision), C3 (apply) |
| **INV-4** | Foreign fail-closed. If any foreign currency marker (§4.6) appears anywhere in the line: amount = `Flagged(AMOUNT_FOREIGN_CURRENCY)`; a fillable amount field is cleared, **including a PREFILL clipboard amount**; hint `FOREIGN_CURRENCY_NOT_SUPPORTED` is set; outcome = `FOREIGN_CURRENCY_BLOCKED`. Nothing records, converts or relabels the foreign currency | C1 |
| **INV-5** | Amount canonicalisation. An amount `Value` is text `T` with `T == AmountInputSanitizer.sanitize(T)` and `parseAmount(T)` == the parsed decimal value. `sanitize` is **never** applied to a raw token: `sanitize("3,50")` = "350" (V9). More than 2 fraction digits is flagged, never rounded | C1 |
| **INV-6** | Date resolution. Date `Value` = LocalDate arithmetic in `zone` at Fill time (never `now − N×86_400_000`). `epochMs = localDate.atTime(nowLocalTime truncated to seconds).atZone(zone)` (java.time gap/overlap rules), so the result is always on `localDate` and never after the end of today | C1 |
| **INV-7** | Apply table (§5.3) is total and deterministic. For each fillable field: `Value` → write, origin FILL. `Flagged` → write default, origin FILL, set flag. `Absent` → FILL-origin fields reset to default (origin DEFAULT); DEFAULT/PREFILL fields are kept. Applying the same parse twice gives the same state (idempotent) | C3 |
| **INV-8** | One-shot channel. Any Fill consumes the one-shot initial-values channel (`initialValuesApplied = true`). After a Fill, no PREFILL value is applied in that sheet session (clipboard race closed) | C3 |
| **INV-9** | Suggestions. Fill never triggers `searchMerchants`, cancels any pending `searchJob`, and hides open suggestions. `selectSuggestion` is a USER edit; it writes amount only if amount is blank **and** amount has no flag | C3 |
| **INV-10** | Category. A category `Value` comes only from (a) exactly one category-name match in the picker list (C1), or (b) C2 learned lookup keyed on the merchant `Value` that this Fill actually wrote. Never from `categorize*`, fuzzy, semantic, context or ML layers. Exact name wins over learned | C1 (a), C2 (b) |
| **INV-11** | Fill is write-free. Fill and C2 perform **zero** persistent writes: no rows in expenses, transaction_events, merchant canonicals/aliases, or merchant_categories; no prefs/DataStore. Every persisted effect happens only on Save via `addManualExpense` → TLC `createExpenseDbOnlyV2` → `insertAtomic()`, with `source = MANUAL_ENTRY` | C3 + C2 (write-free); save path unchanged (TLC) |
| **INV-12** | Line ephemerality. The typed line exists only in the Sheet's Compose `remember` state and as a transient argument to the VM's fill call. It is never stored in any VM field, `AddExpenseState`, `SavedStateHandle`, `rememberSaveable`, Room, prefs/DataStore, nav args, intents/deep links, files/cache, clipboard (write), notification, WorkManager `Data`, notes, history or recents. `QuickAddParseResult` holds no raw line | C4 (holder), C3 (signature) |
| **INV-13** | No content in diagnostics. No log, diagnostic, exception message or event produced by Fill, C2, suggestion search or save contains line text, tokens, merchant text, amounts or dates. The only allowed strings are §2.7 + §2.8 constants (plus pre-existing `AmountUtils` constants). No throwable is attached to these logs | C3 (+ C1/C2 do not log) |
| **INV-14** | AI absent. C1, C2 and the fill contract have no dependency on AI/Nano/AICore/ML Kit GenAI, AI capability/consent/settings, network or cloud. No new capability or toggle | C1/C2/C3 (import boundary) |
| **INV-15** | Cancellation and error hygiene. `CancellationException` is rethrown in the save coroutine, the Fill job and the suggestion search. A save failure shows a bounded localised message (no `e.message`) and logs only `ADD_EXPENSE_SAVE_FAILED`. `isSaving` never stays true after the save coroutine ends for any reason | C3 |
| **INV-16** | Flags never block Save, and save validation is unchanged (V5). A flagged field is empty/default, so existing validation decides | C3 |
| **INV-17** | Fill gating: Fill has no effect (state unchanged, no code emitted) if the line is blank, `homeCurrency == null`, or `isSaving` | C3 |
| **INV-18** | Atomic, latest-wins apply. The parse and the C2 lookup run in one Fill job. The result is applied in a single atomic `_state.update` that re-evaluates fillability at apply time. A newer Fill or `reset()` cancels the older job, and a stale result is never applied | C3 |
| **INV-19** | Determinism. `parse()` is a pure function of `QuickAddInput`. Case folding uses `Locale.ROOT`. It does not use the device locale, random numbers or clock reads | C1 |
| **INV-20** | Visibility. Every field written by Fill (value or flag) is visible in the sheet without further interaction; flags are shown on their field; line hints are shown at the line | C4 (details → UX) |

---

## 4. Parser rules (C1)

### 4.1 Pipeline
1. If the line is longer than 200 chars, everything is `Absent` and the hint is `LINE_TOO_LONG`; stop. The UI refuses input beyond 200 chars (the edit is rejected; the line is not truncated), so this branch is defensive only.
2. NFC-normalise. U+037E (Greek question mark) becomes ';' and U+0387 (ano teleia) becomes '·' through NFC.
3. **NBSP grouping join.** Inside a match of `(?<!\d)\d{1,3}(?:[    ]\d{3})+(?:[.,]\d{1,2})?(?!\d)`, delete those space characters ("1 234,56" with NBSP becomes "1234,56"). An ASCII space never joins digits.
4. Split on any Unicode whitespace (`\s`, Zs, tab, newline, U+3000).
5. Strip each token's trailing characters in P = `, . ; : ! ? · … « » " ' ( ) [ ]` and its leading P characters. Exception: a leading `.` or `,` directly before a digit is kept (".50" stays ".50"). Tokens that become empty are dropped. Tokens made only of `- – — ‑ −` are dropped.
6. Fold for matching: `F(t) = NFD-strip-combining-marks(lowercase(Locale.ROOT, t))`, then 'ς'→'σ'. Surface forms are kept for merchant output.
7. Classify tokens (§4.3), then derive amount, date, payment, merchant and category.

### 4.2 Token shapes
- **Number-like**: after splitting attached currency markers (§4.6), the token contains at least one ASCII digit and no letter (`Character.isLetter`). A token with letters, such as "7eleven", "a1", "4k" or "100άρι", is TEXT.
- **Numeric date**: `^(\d{1,2})/(\d{1,2})(?:/(\d{2}|\d{4}))?$`, or `^(\d{1,2})([.\-])(\d{1,2})\2(\d{2}|\d{4})$` (dot or dash only with all three parts). Two-part "5.3" is **not** a date; it is a number.
- **Time**: `^([01]?\d|2[0-3]):[0-5]\d$`.
- **Quantity**:
  - `^\d{1,3}[x×]$` or `^[x×]\d{1,3}$`.
  - The pair (`^\d{1,3}$`, unit) where unit ∈ {x, ×, τμχ, τεμ, τεμάχιο, τεμάχια, pc, pcs, piece, pieces} (folded).
  - **Leading bare integer**: the first token of the line is `^\d+$` with no attached marker and the next token is TEXT ("2 καφέδες", "15 taxi") (OD-3). This rule is a post-pass after §4.3: it reclassifies that MONEY_CANDIDATE as QUANTITY once its neighbour's class is known. For the unit pair, the integer token must be bare (no attached marker), so "$20 x" is not a quantity.

### 4.3 Classification precedence (first match wins; multi-token phrases first)
1. Date phrases: "day before yesterday", "the day before yesterday", "day after tomorrow".
2. Modifier + weekday ("last/next/this/περασμένη/περασμένο/επόμενη/επόμενο/perasmeni/perasmeno/epomeni/epomeno" + weekday) → DATE(UNSUPPORTED). Both tokens are consumed.
3. Article + date (`on, τη, την, το, στις, stis, ti, tin` immediately before a date token) → the article is consumed.
4. Preposition + payment (`με, me, by, with, in` immediately before a payment keyword) → the preposition is consumed.
5. Numeric date → DATE. Time → TIME. Quantity → QUANTITY.
6. Relative-date, future or weekday word → DATE. Payment keyword → PAYMENT. Detached currency marker → MARKER.
7. Number-like → MONEY_CANDIDATE.
8. Anything else → TEXT.

### 4.4 Vocabulary
Matching is on folded forms. Greeklish forms are explicit; they are not derived via `GreeklishNormalizer` because "xthes" ≠ "chthes" (V10).

| Class | Forms |
|---|---|
| TODAY (0) | today, σήμερα, simera, shmera |
| YESTERDAY (1) | yesterday, yday, χθες, χθές, εχθές, χτες, χτές, xthes, xtes, htes, chthes, ehthes, exthes, extes |
| DAY_BEFORE (2) | "day before yesterday", προχθές, προχτές, proxthes, proxtes, prohthes, prochthes, prohtes |
| FUTURE → `DATE_FUTURE` | tomorrow, αύριο, avrio, aurio, ayrio, "day after tomorrow", μεθαύριο, methavrio, methaurio |
| MON | monday, mon, Δευτέρα, Δευτ, Δευ, deftera, deutera, dheftera |
| TUE | tuesday, tue, tues, Τρίτη, Τρι, triti |
| WED | wednesday, wed, Τετάρτη, Τετ, tetarti |
| THU | thursday, thu, thur, thurs, Πέμπτη, Πεμ, Πεμπ, pempti, pebti |
| FRI | friday, fri, Παρασκευή, Παρ, Παρασκ, paraskevi, paraskeui |
| SAT | saturday, sat, Σάββατο, Σαβ, savvato, savato, sabbato |
| SUN | sunday, sun, Κυριακή, Κυρ, kyriaki, kiriaki, kuriaki |
| CARD | card, κάρτα, karta |
| CASH | cash, μετρητά, metrita |

### 4.5 Amount (Q-D1)
Let C = MONEY_CANDIDATE tokens and Q = whether any QUANTITY token is present. Decide in this order:
1. Foreign marker anywhere → `Flagged(AMOUNT_FOREIGN_CURRENCY)`.
2. Q → `Flagged(AMOUNT_QUANTITY)`.
3. |C| ≥ 2 → `Flagged(AMOUNT_MULTIPLE_NUMBERS)`. No exception for marked numbers ("2 καφέδες 7€" is flagged).
4. |C| = 0 → `Absent`.
5. |C| = 1. Take its core (markers removed) and check, in order:
   - R0: a leading minus-class char (`- − ‑ – — ﹣ －`) → `AMOUNT_NOT_POSITIVE`.
   - R1: the core does not match `^[0-9]([0-9.,]*[0-9])?$` ("+15", ".50", "15%", "1:2", non-ASCII digits, emoji) → `AMOUNT_UNCLEAR`.
   - R2: the core starts with '0' followed by a digit ("0150") → `AMOUNT_UNCLEAR`.
   - R3: **exactly one separator character in total, followed by exactly three digits** ("1.500", "3,500", "0,500", "12.345") → `AMOUNT_AMBIGUOUS_SEPARATOR`.
   - R4: `parseAmount(core)` == null ("1.234.567", "1,23.4") → `AMOUNT_UNCLEAR`.
   - R5: more than 2 fraction digits under parseAmount's interpretation ("3.5055", "1.234,567") → `AMOUNT_UNCLEAR`.
   - R6: v ≤ 0 → `AMOUNT_NOT_POSITIVE`.
   - R7: v > 1,000,000 → `AMOUNT_OVER_LIMIT` (1,000,000.00 is allowed, matching V5).
   - Otherwise `Value(T)` with `T = sanitize(String.format(Locale.US, "%.2f", v))` and the INV-5 round-trip assertion.

Order: shape gates (R0–R3) → `parseAmount` → precision and range → `%.2f` → `sanitize`, with the sanitiser **last**. All MONEY_CANDIDATE and QUANTITY tokens are removed from the merchant even when flagged. Parentheses are punctuation: "(15)" → 15.00, with no accounting-negative meaning. The minimum after the ≤2dp rule is 0.01.

### 4.6 Currency markers (Q-D2)
Each marker maps to a currency set S. A marker is **HOME iff S == {homeCurrency}**; otherwise it is **FOREIGN**.
- **ISO codes from the catalog** (28, incl. HRK; V19): case-insensitive; detached anywhere, or attached ("20eur", "usd20").
- **Non-alphabetic catalog symbols** `€ $ £ ¥ ₩ ₺ ₽ ₹` and compounds `C$ A$ NZ$ MX$ S$ HK$ R$` (longest match first): detached anywhere, or attached. `$` → {USD}; `¥` → {JPY, CNY}, so it is never home.
- **Alphabetic catalog symbols** `Fr R kr lei Ft kn Kč zł лв`: **attached only** ("100kr", "R20"); detached they are TEXT. `kr` → {SEK, NOK, DKK, ISK}, so it is never home.
- **Words** (detached or attached):
  - EUR: eur, euro, euros, ευρώ, ευρω, ευρο, evro, evrw, eyro, eyrw. Attached-only EUR: `e`, `ε` ("3,50e", "5ε").
  - USD: dollar(s), δολάριο, δολάρια, dolario, dolaria.
  - GBP: pound(s), στερλίνα, στερλίνες, sterlina.
  - {GBP, TRY}: λίρα, λίρες, lira, liras.
  - JPY: yen, γιεν. CNY: yuan, renminbi. CHF: franc(s), φράγκο, φράγκα.
  - RUB: ruble(s), rouble(s), ρούβλι, ρούβλια. INR: rupee(s), ρουπία.
  - {SEK, NOK, DKK, ISK}: krona, kronor, krone, kroner, κορώνα, κορώνες.
  - PLN: zloty. HUF: forint. RON: leu. BGN: lev, leva, λέβα. MXN: peso(s).
  - Excluded because they collide with English words: won, real, rand (their codes and symbols still work).
- **Non-catalog ISO-4217 codes** (fixed embedded list, no runtime `java.util.Currency`) are FOREIGN only when attached to, or the token immediately before or after, the money candidate ("20 thb"). Elsewhere they are TEXT (OD-5).
- Unknown alphabetic tokens ("abc") are TEXT, so they go to the merchant.
- A HOME marker is consumed with no other effect. Any FOREIGN marker triggers INV-4, even if HOME markers are also present.
- `homeCurrency == null` never reaches the parser (INV-17).

### 4.7 Date (Q-D3)
- Zero DATE expressions → `Absent`. Two or more → `DATE_AMBIGUOUS`, even if they agree.
- Relative: today − {0, 1, 2} days.
- FUTURE word → `DATE_FUTURE`. Modifier + weekday → `DATE_UNSUPPORTED`.
- **Weekday W, today T**: offset = (T − W) mod 7. If 1..6 → today − offset. If 0 → `DATE_AMBIGUOUS`, so the field keeps or resets to today's default with a visible flag (OD-4).
- **Numeric dates are day-first.**
  - YY → 2000+YY. A year below 2000, or a 3-digit year → `DATE_INVALID`. An impossible day/month (31/4, 29/2 in a non-leap year, month 0 or 13) → `DATE_INVALID`.
  - With a year: if the date is after today → `DATE_FUTURE`.
  - **Without a year**:
    1. Y = current year. If (D, M, Y) is valid and ≤ today → use it.
    2. Else if (D, M, Y−1) is valid and today − that date ≤ **92 days** → use it (year boundary).
    3. Else if (D, M, Y) is valid → `DATE_FUTURE`.
    4. Else → `DATE_INVALID`.
- **Time of day (decision):** `epochMs = localDate.atTime(LocalTime of now in zone, truncated to seconds).atZone(zone)`.
  - DST gap: the time shifts forward by the gap and stays on the same date. Overlap: the earlier offset is used.
  - This is the same H:M:S carry-over the date picker uses (V6), so "χθες" means exactly what picking yesterday in the picker means.
  - Rejected alternative: a fixed 12:00. It collides every same-merchant, same-amount entry for a day (§6), skews hour-based context inference, and is inconsistent with the picker.
- The date is resolved once, at Fill. If Save happens after midnight, the date stays on the Fill-time day; it is visible and passes validation (V5).

### 4.8 Time tokens
A TIME token is consumed. It is never an amount and is not applied to the date; the line hint is `TIME_IGNORED`. "21.30" is a number, not a time.

### 4.9 Payment
- CARD keyword(s) only → `Value(CARD)`. CASH only → `Value(CASH)`. Both → `Flagged(PAYMENT_CONFLICT)`. None → `Absent`.
- BANK_TRANSFER and UNKNOWN are never produced.

### 4.10 Merchant (Q-D7)
- The merchant is the TEXT tokens left after category-token removal (§4.11), in original order and original surface form (case and accents kept), joined by single spaces and trimmed.
- If longer than 100 chars, truncate at 100 and trim again (the same cap as `updateMerchant`).
- Blank → `Absent`.
- Generic words are accepted (Gate 1 Q6). Standalone "&", "+", "@" and apostrophes inside tokens are kept ("H&M", "Marks & Spencer", "McDonald's").
- Equivalence: `MerchantKeyGenerator` already equates "kafes" and "καφές" (V10), so dedup and canonical resolution treat them as the same merchant at save (U4 for the display form). Fill does not transliterate the merchant.

### 4.11 Category (Q-D6)
**Exact name (C1).**
- Fold function: `G(s)` = strip combining marks of `GreeklishNormalizer.normalize(s)`, with whitespace collapsed.
- For every category in `input.categories`, match `G(name)` against `G` of contiguous windows over the TEXT-token sequence (window length = word count of the name).
- One distinct category matched → `Value(EXACT_NAME)`. Two or more → `Flagged(CATEGORY_AMBIGUOUS)`. None → `Absent`.
- **Token removal decision:** matched tokens are removed from the merchant **only if the remaining merchant is non-blank**. Otherwise they stay ("groceries 42.30" → merchant "groceries").
- An empty category list → `Absent`.

**Learned lookup (C2)**, run only when all of these hold: C1 category is `Absent`, the category field is fillable, and this Fill **writes** merchant `Value m`.
- Steps: `key = normalizedCanonicalNameForMerchant(m)` → `getCategoryByNormalizedCanonical(key)`. A result is used only if its `categoryId` is in `input.categories`; then it is `Value(LEARNED)`, otherwise `Absent`.
- Rows with a null `normalizedCanonicalName` are invisible (fail-closed miss).
- If several rows share a key, the DAO's deterministic ordering decides (V14). The chip is visible.
- An exception → `Absent` and the code `QUICK_ADD_CATEGORY_LOOKUP_FAILED`; CE is rethrown.

A null category at Save → existing `hybridClassifier` (V18), unchanged. UX copy: "empty = auto at save".

---

## 5. Fill contract (C3) — state machine (Q-D5)

**5.1 Origin transitions** (per field f):

| Event | Transition |
|---|---|
| sheet open / `reset()` | all fields → DEFAULT; flags, hints and outcome cleared; Fill job cancelled |
| `setInitialValuesIfBlank` writes amount/merchant | → PREFILL (only before any Fill; INV-8) |
| amount TextField `updateAmount` | amount → USER; amount flag cleared |
| merchant TextField `updateMerchant` | merchant → USER; merchant flag cleared |
| `selectSuggestion` | merchant → USER. Category → USER if the suggestion has a category. Amount → USER if it was written (blank and unflagged only, INV-9). Flags of written fields cleared |
| date picker confirm (`updateDate`) | date → USER (even if the same day); flag cleared. Dismissing the picker changes nothing |
| category picker (`selectCategory`) | category → USER; flag cleared |
| payment chip (`selectPaymentMethod`) | payment → USER (even CASH); flag cleared |
| Fill apply | per §5.3 |
| Save success | sheet calls `reset()` and dismisses (Sheet :90-95) |

**5.2 Fill sequence.**
1. Gate (INV-17).
2. Mark the one-shot channel consumed.
3. Cancel the previous Fill job; cancel `searchJob` and hide suggestions.
4. Launch the job: `parse` (pure) → optional C2 lookup (suspend).
5. Apply in a single `_state.update` (INV-18), clearing `saveResult` and the `amountError`/`merchantError` of written fields.
6. Compute the outcome (5.4) and emit one code.

**5.3 Apply table** (field fillable per INV-1; for a non-fillable field nothing changes and no field flag is set):

| Parse result | Origin DEFAULT / PREFILL | Origin FILL |
|---|---|---|
| `Value(v)` | write v → FILL, clear flag | write v → FILL, clear flag |
| `Flagged(r)` | write default → FILL, set flag r | write default → FILL, set flag r |
| `Absent` | keep value and origin, clear flag | write default → DEFAULT, clear flag |

Defaults: amount "", merchant "", date = `nowEpochMs` at this Fill, category null, payment CASH.

Line hints are replaced on every Fill. `FOREIGN_CURRENCY_NOT_SUPPORTED` is shown even when amount is USER (INV-4 hint, field untouched).

**5.4 Outcome code.**
- Foreign hint present → `FOREIGN_CURRENCY_BLOCKED`.
- Else, let W = the fields to which a `Value` was applied:
  - W empty → `FILL_NONE`; add hint `NOTHING_RECOGNISED` iff every parse field was `Absent` and there is no `LINE_TOO_LONG`.
  - amount ∈ W, merchant ∈ W and no flag set in this Fill → `FILL_FULL`.
  - Otherwise → `FILL_PARTIAL`.

**5.5 Answers to Q-D5.**
- Fillable fields: amount, merchant, date, category, payment. The transaction type stays as is; Fill is type-agnostic.
- Second Fill: overwrites earlier Fill values not edited by the user; FILL-origin fields not mentioned are reset.
- Unmentioned DEFAULT/PREFILL fields are kept (for example a clipboard amount with "lidl χθες").
- Fill does not trigger suggestions; a later suggestion tap counts as a USER edit.
- A user-cleared text field is fillable again: there is nothing to lose.

---

## 6. Duplicate policy (Q-D3)

Facts (V17): a second manual create is rejected as `Result.Duplicate` iff an existing row has the same currency, the same type, an amount within tolerance, a merchant match (merchantKey exact, prefix containment, or raw), and a `date` within **±5 min**. The `dedupeKey` unique index uses 5-minute buckets. A rejected save shows "Duplicate expense" and keeps the sheet open (V5).

Effect of the time-of-day choice:

| Choice | Identical "καφές 3,50 χθες" twice within minutes | Two real coffees yesterday entered on different days | vs an expense really made yesterday at the same clock time |
|---|---|---|---|
| **Now carry-over (chosen)** | 2nd = Duplicate. This is the desired guard against accidental double entry, and it is the same as today's typed manual entry | Not a duplicate (different H:M:S unless within 5 min) | Duplicate only within ±5 min of the same clock time (rare) |
| Fixed 12:00 | 2nd = Duplicate | **Always Duplicate** (identical millis, same bucket); a legitimate second entry is blocked with no workaround, because the sheet has no time picker (V6) | Collides with anything at 11:55–12:05 |

Recommendation: keep now carry-over and change no dedup rule. Known limitation, the same as today: two genuine identical expenses entered within 5 minutes need a different merchant text or a later entry.

---

## 7. Ephemerality, diagnostics, hygiene (Q-D10)

**7.1 What counts as persistence.** Fill or the line must not produce any of these:
- Room rows in any table, including transaction_events, merchant canonicals/aliases (V13 trap) and merchant_categories.
- SharedPreferences/DataStore.
- `SavedStateHandle`, `rememberSaveable`, `onSaveInstanceState`/Bundle.
- Navigation arguments or back-stack entries; Intent extras or deep links.
- Files, cache dir or crash attachments.
- Clipboard writes.
- Notifications, WorkManager input/output `Data`, alarms.
- Logs (Timber/Logcat), audit/diagnostic/telemetry/analytics stores, backups/exports.
- IME history the app controls.
- Notes, merchant history or "recent lines".

Merchant/amount/date/category/payment written into form fields are ordinary form data. They persist only via Save (INV-11), exactly as typed form data does today.

**7.2 Allowed diagnostic strings:**
- Outcome codes `FILL_FULL`, `FILL_PARTIAL`, `FILL_NONE`, `FOREIGN_CURRENCY_BLOCKED` (one per Fill).
- Reason codes `MERCHANT_SUGGESTION_SEARCH_FAILED`, `ADD_EXPENSE_SAVE_FAILED`, `QUICK_ADD_CATEGORY_LOOKUP_FAILED`.
- Nothing else, and no throwable. The emission sink is the existing log facility (U5); there are no persisted counters in increment 1.

**7.3 `:128` fix shape.** Rethrow `CancellationException` first. On other exceptions, log exactly `MERCHANT_SUGGESTION_SEARCH_FAILED` with no throwable, no merchant text and no query. Then clear the suggestions as today. This protects ordinary manual entry too.

**7.4 Save-error hygiene shape** (owner-approved, VM :439-449):
- `CancellationException` → rethrow.
- Other `Exception` → log exactly `ADD_EXPENSE_SAVE_FAILED` (no throwable), then set `saveResult`/`mutation` to a **string-resource** generic message (EN + GR, e.g. "Couldn't save the expense. Try again.") with no exception data.
- `isSaving` is cleared on every termination path (a `finally`-equivalent; the state update is synchronous, so it is safe under cancellation). This also covers a `TimeoutCancellationException` escaping from below while the VM is alive.
- The `Result.Error` branch (:415-425) still shows repository text; whether it gets the same treatment is OD-2.

**7.5 Recommended guards and tests** (for test-author / impact-mapper):
- Static: no `rememberSaveable` or `SavedStateHandle` under `ui/screens/addexpense`.
- Static: `domain/quickadd/**` imports no `android.*`, `data.*` (except the C2 repository read), `timber`, or `domain/ai`.
- Static: no `Timber.*(e, …)` with interpolation in the VM.
- Static: C2 does not reference `categorize`, `normalize(`, `getCategoryForMerchant` or `MerchantNormalizer`.

---

## 8. Edge-case checklist

| Topic | Rule |
|---|---|
| Mixed/foreign currencies | Not representable in increment 1; any foreign marker → INV-4. No FX, no conversion, no currency stored. Stale/missing FX: N/A |
| Unavailable vs zero | Absent/flagged amount = "" (never "0" or "0.00"); "0" typed → `AMOUNT_NOT_POSITIVE` |
| Rounding | Fill never rounds or truncates; >2dp is flagged. Save-time HALF_UP (V5) is a no-op on canonical 2dp. No splits or aggregates |
| Time zone / DST / midnight | §4.7: LocalDate arithmetic, gap → forward shift, overlap → earlier offset, resolved once at Fill. A zone change between Fill and Save keeps the Fill-time millis |
| Month-end recurrence | Fill never sets `isRecurring`. If the user toggles recurring, the rule gets `lastDate` = form date (existing behaviour) |
| Past/recurring edits, deletes | N/A. The line exists only in create mode; if the Sheet has an edit mode (U2), the line must not appear there |
| Receipt processing | N/A |
| Duplicates/idempotency | §6. Fill is idempotent (INV-7); double-tap Save is guarded by `isSaving` (V5) |
| Empty states | Blank line → no-op. Punctuation/emoji-only → `FILL_NONE` + `NOTHING_RECOGNISED` (an emoji TEXT token becomes the merchant). Categories not yet loaded → category Absent |
| Clipboard race | INV-8; a clipboard amount arriving after any Fill is ignored |
| Concurrency | INV-18; a user edit between parse and apply wins |

---

## 9. Persistence impact — **NONE** (Q-D8)

- No table, column, index, schema version, migration, backfill or snapshot change.
- `ExpenseSource` stays `MANUAL_ENTRY`. There is no quick-add marker in expense rows, events, notes or metadata, and none is needed in increment 1.
- Learning on Save is as today (Gate 1 Q5).
- New string resources (EN/GR) for hints, flags and the generic save error are not persistence.
- Nothing destructive.

---

## 10. Privacy rules (summary)

1. The typed line is memory-only (INV-12). Loss on rotation or process death is accepted.
2. The line is never logged, sent, exported or used as notes (INV-12, INV-13). No network and no AI (INV-14).
3. Fill-time category lookup is write-free and log-free (C2, INV-11). `MerchantNormalizer.normalize` is banned at Fill (V13).
4. Diagnostics use the closed code set only (§7.2). The `:128` content log and raw save-error text are removed (§7.3–7.4).
5. Disabling IME autocorrect/personalised learning on the line is recommended to UX (U7). It is not a privacy guarantee.

---

## 11. Acceptance examples (Given/When/Then)

**Common context CTX-A:**
- home = EUR; zone = Europe/Athens.
- now = **2026-10-16T14:30:15.250+03:00 (Friday)**.
- Categories: 1 Groceries, 2 Transport, 3 Food, 15 Home, 16 Kids, 17 Gifts, 19 "Legal & Gov", 20 Uncategorized.
- Learned rows (normalizedCanonicalName → category): "kafes" → 3, "taxi" → 2.
- Form at DEFAULT: amount "", merchant "", date 2026-10-16T14:28:00.000+03:00, payment CASH, category null.
- "Today" means 2026-10-16T14:30:15.000+03:00 and "yesterday" means 2026-10-15T14:30:15.000+03:00.

CTX-B = CTX-A plus a user category 30 "Καφές". Unless stated, "Then" lists amount / date / merchant / payment / category / flags / outcome.

### Worked examples (brief §4)
- **AE-1** (INV-6, 10). Given CTX-A. When Fill "καφές 3,50 χθες". Then "3.50" / yesterday / "καφές" / CASH (Absent, kept) / 3 Food (LEARNED via key "kafes") / none / FILL_FULL. Given CTX-A **without** learned rows: category null; otherwise identical.
- **AE-2** (INV-10, §4.11). When "groceries 42.30 lidl". Then "42.30" / date unchanged (14:28) / "lidl" / CASH / 1 Groceries (EXACT_NAME) / none / FILL_FULL.
- **AE-3** (INV-4 home, §4.9). When "taxi 15€ σήμερα κάρτα". Then "15.00" / today / "taxi" / CARD / 2 Transport (LEARNED) / none / FILL_FULL.
- **AE-4** (Q-D7). When "kafes 3.50". Then "3.50" / unchanged / "kafes" / CASH / 3 Food (key "kafes" == key of "καφές"). Also `MerchantKeyGenerator.generate("kafes") == generate("καφές")`.
- **AE-5** (INV-4). When "dinner 20 usd". Then amount "" + `AMOUNT_FOREIGN_CURRENCY` / unchanged / "dinner" / CASH / null / hint `FOREIGN_CURRENCY_NOT_SUPPORTED` / FOREIGN_CURRENCY_BLOCKED. And: Save → "Enter a valid amount" (V5), nothing persisted. User types "18" → amount USER, flag cleared → Save → exactly one expense 18.00 **EUR** via TLC.
- **AE-6** (§4.5). When "2 καφέδες 7". Then amount "" + `AMOUNT_QUANTITY` (leading bare integer + TEXT) / "καφέδες" / FILL_PARTIAL. Also "2x καφές 7" → `AMOUNT_QUANTITY`, merchant "καφές".
- **AE-7** (R3). When "lidl 1.500". Then amount "" + `AMOUNT_AMBIGUOUS_SEPARATOR` / "lidl" / FILL_PARTIAL. Same for "lidl 3,500".
- **AE-8** (INV-6, 16). When "taxi 12 αύριο". Then "12.00" / date = now at Fill + `DATE_FUTURE` / "taxi" / FILL_PARTIAL. Save → Success with today's date.
- **AE-9** (INV-1). Given amount "4.00" origin USER. When "καφές 3,50". Then amount "4.00" unchanged, no amount flag / merchant "καφές" / category 3 / FILL_PARTIAL.

### Parser rules
- **AE-10** (INV-5). "taxi 3,5" → "3.50"; "tv 1.234,56" → "1234.56"; "taxi 1,234.56" → "1234.56"; "taxi 1 234,56" with U+00A0 → "1234.56"; with an ASCII space → `AMOUNT_MULTIPLE_NUMBERS`; "taxi 3.5055" → `AMOUNT_UNCLEAR`; "taxi 0,00" → `AMOUNT_NOT_POSITIVE`; "taxi −5" (U+2212) → `AMOUNT_NOT_POSITIVE`; "taxi 1000000" → "1000000.00"; "taxi 1000000,01" → `AMOUNT_OVER_LIMIT`; "taxi .50" → `AMOUNT_UNCLEAR`; "taxi 0150" → `AMOUNT_UNCLEAR`. For every Value T: `sanitize(T) == T`.
- **AE-11** (§4.8). "lidl 12,40 21:30" → "12.40", merchant "lidl", hint `TIME_IGNORED`, date unchanged.
- **AE-12** (§4.6). home EUR: "15 ευρώ", "15ε", "EUR 15", "evro 15", "€ 15" → "15.00". "$20 cab" → FOREIGN. "100kr" → FOREIGN. "20 abc" → "20.00", merchant "abc". "20 thb" → FOREIGN (adjacent non-catalog ISO). "thb dinner 20" → "20.00", merchant "thb dinner" (documented residual). "20 usd €" → FOREIGN. home **USD**: "$20 cab" → "20.00"; "€20 cab" → FOREIGN; "¥500 cab" → FOREIGN for home JPY too (¥ is ambiguous).
- **AE-13** (§4.7 weekdays). "taxi 9 Δευτέρα" → 2026-10-12T14:30:15+03:00; "taxi 9 τη Δευτέρα" → same, merchant "taxi"; "taxi 9 Πέμπτη" → 10-15; "taxi 9 Σάββατο" → 10-10; "taxi 9 Παρασκευή" → `DATE_AMBIGUOUS`, date = now; "taxi 9 last monday" → `DATE_UNSUPPORTED`, merchant "taxi"; "taxi 9 χθες 15/10" → `DATE_AMBIGUOUS`.
- **AE-14** (year boundary). Given now 2027-01-03T10:00:00+02:00 (Sunday): "taxi 5 28/12" → 2026-12-28T10:00:00+02:00; "taxi 5 3/10" → 2026-10-03 (92 days); "taxi 5 2/10" → `DATE_FUTURE` (93 days); "taxi 5 4/1" → `DATE_FUTURE`; "taxi 5 31/12/26" → 2026-12-31; "taxi 5 5/1/27" → `DATE_FUTURE`; "taxi 5 31/4" → `DATE_INVALID`; "taxi 5 5.3" → `AMOUNT_MULTIPLE_NUMBERS` (5.3 is a number).
- **AE-15** (DST). now 2026-03-30T03:30:00+03:00: "καφές 2 χθες" → 2026-03-29T04:30:00+03:00 (gap). now 2026-10-26T03:30:00+02:00: "χθες" → 2026-10-25T03:30:00+03:00 (overlap, earlier offset). now 2026-10-16T00:00:30+03:00: "χθες" → 2026-10-15T00:00:30+03:00.
- **AE-16** (§4.9). "taxi 5 με κάρτα" → CARD, merchant "taxi"; "taxi 5 ΚΑΡΤΑ" → CARD; "taxi 5 metrita" → CASH; "taxi 5 card cash" → `PAYMENT_CONFLICT`, payment = CASH.
- **AE-17** (§4.11). "gifts kids 30" → `CATEGORY_AMBIGUOUS`, category null, merchant "gifts kids". "legal & gov 30 fee" → 19, merchant "fee". "GROCERIES 5" → 1, merchant "GROCERIES" (kept: leftover would be blank). CTX-B "kafes 3.50" → 30 (EXACT_NAME beats LEARNED 3), merchant "kafes". "taxi home 12" → 15 Home, merchant "taxi" (known false positive, visible; §13 risk R1).
- **AE-18** (empty). "" → no-op, no code. "!!! ;" → all Absent, `NOTHING_RECOGNISED`, FILL_NONE. A 201-char line → `LINE_TOO_LONG`, FILL_NONE. "a1 b2" → merchant "a1 b2", amount Absent, FILL_PARTIAL.

### Fill contract
- **AE-20** (INV-7). Given a clipboard amount "12.40" PREFILL. "lidl χθες" → amount "12.40" kept. "lidl 9,99" → "9.99". "dinner 20 usd" → amount "" (INV-4).
- **AE-21** (INV-8). Given Fill "χθες" at t1. When the clipboard resolves at t2 > t1 with "5.00". Then amount stays "", origin DEFAULT.
- **AE-22** (INV-7). Fill "taxi 15 κάρτα" (CARD, FILL), then Fill "taxi 15" → payment CASH, origin DEFAULT.
- **AE-23** (INV-1). Fill "taxi 15"; the user edits merchant to "Taxi Beat"; Fill "uber 20 χθες" → merchant "Taxi Beat", amount "20.00", date yesterday.
- **AE-24** (INV-1). The user types amount "4" and then clears it to "". Fill "καφές 3,50" → amount "3.50".
- **AE-25** (INV-1). The user picks 2026-10-10 in the picker. Fill "καφές 3,50 χθες" → date stays 2026-10-10; amount and merchant filled.
- **AE-26** (INV-9). After AE-5, the user taps suggestion "Dinner Place" (avg 25.00, category 3) → merchant "Dinner Place" (USER), category 3 (USER), amount stays "" with the flag.
- **AE-27** (INV-9). After Fill "kafes 3.50", `searchMerchants` was not invoked and `showSuggestions == false`.
- **AE-28** (INV-17). `isSaving == true` → Fill has no effect. `homeCurrency == null` → Fill has no effect.
- **AE-29** (INV-18). The C2 lookup is suspended; the user selects category 2 (USER); the lookup returns 3 → category stays 2. Fill A is pending and Fill B is issued → only B's result is applied.
- **AE-30** (INV-7). The same line Filled twice gives an identical state and the same outcome.
- **AE-31** (INV-2). Given notes "x", recurring on and type TRANSFER, any Fill leaves those three unchanged.

### Write-free, save path, duplicates
- **AE-40** (INV-11). Given an in-memory Room with canonical merchant "Καφές" and a fuzzy-near canonical. When Fill "kafess 3" (C2 runs). Then the row counts of all tables are unchanged and no alias row is added.
- **AE-41** (INV-11). Fill then Save → exactly one `addManualExpense` call → TLC `createExpenseDbOnlyV2` → `insertAtomic`; the row has `source = MANUAL_ENTRY`, currency = home, and amount equal to the field value.
- **AE-42** (§6). Given an existing "Καφές" 3.50 EUR PURCHASE at 2026-10-15T14:28:00+03:00. When Fill "καφές 3,50 χθες" and Save → `Result.Duplicate`, "Duplicate expense" shown, sheet and fields kept. Given the existing row at 09:00 instead → Success.
- **AE-43** (§6). "καφές 3,50" saved at 14:30:15, then again at 14:33:00 → the second is Duplicate (same as typed manual entry today).

### Privacy and hygiene
- **AE-50** (INV-12). After Fill "taxi 15 κάρτα χθες": `AddExpenseState.toString()` contains neither "κάρτα" nor "χθες". No VM field holds the line. The Sheet uses `remember` for the line (static check).
- **AE-51** (INV-13). With a capturing Timber tree, run Fill + suggestion-search failure + save failure. Every log message is in §7.2 or the `AmountUtils` constants, none has a throwable, and none contains a substring of the line.
- **AE-52** (§7.3). `searchMerchants` throws `SQLiteException("… WHERE merchant LIKE 'καφ%' …")` → the log is exactly `MERCHANT_SUGGESTION_SEARCH_FAILED`.
- **AE-53** (INV-15). `addManualExpense` throws `IllegalStateException("Created expense 42 was not found…")` → the error shown is the generic string resource, `isSaving == false`, and the log is `ADD_EXPENSE_SAVE_FAILED`. Cancelling the save coroutine → `CancellationException` propagates (no `SaveResult.Error`) and `isSaving == false`.
- **AE-54** (INV-14). Static: `domain/quickadd/**` and the VM fill code import nothing from `domain/ai`, AICore/ML Kit or network.

---

## 12. Golden fixture taxonomy (parser-level, C1)

Fixture format: `(line, home, now, zone, categories) → per-field FieldParse + lineHints`. **Target 132 cases** (allowed 80–150). VM, C2 and integration cases are the AEs above and are not counted here.

| # | Category | Target | Must include |
|---|---|---|---|
| A | Brief worked examples | 9 | AE-1..AE-9 lines |
| B | Valid amounts | 14 | comma/point decimals, integers, €/EUR/ευρώ/ε attached and detached, both grouping styles, NBSP/U+202F/U+2009 grouping, trailing punctuation "3,50.", parentheses |
| C | Rejected amounts | 16 | R0–R7 each; one-sep-three-digits with both separators; multiple numbers; quantity forms (2x, x2, ×2, "2 τμχ", leading bare integer); ASCII-space grouping; emoji attached; dash range "10-15" |
| D | Currency markers | 16 | foreign code (lowercase/uppercase), $ £ ¥ C$ kr attached, words (δολάρια, dollars, λίρες), home + foreign mix, home USD/GBP variants, "abc", adjacent and non-adjacent non-catalog ISO |
| E | Relative dates | 14 | all TODAY/YESTERDAY/DAY_BEFORE forms incl. accents/no accents, ΧΘΕΣ uppercase + final sigma, "the day before yesterday" |
| F | Weekdays | 10 | EN full/abbr with ".", GR full/abbr (Δευτ., Δευ), Greeklish, same-weekday, article, modifier |
| G | Numeric dates | 12 | D/M, D/M/YY, D/M/YYYY, D.M.YYYY, D-M-YY, 92-day edge both sides, 31/4, 29/2 leap and non-leap, future explicit, "5.3" as number |
| H | Future / multiple dates | 5 | αύριο, tomorrow, avrio, μεθαύριο, "χθες 5/3" |
| I | Time-of-day / DST / midnight | 5 | AE-15 cases, 23:59:59 Fill |
| J | Payment | 7 | card/κάρτα/karta/ΚΑΡΤΑ/"με κάρτα", cash/μετρητά/metrita, conflict |
| K | Merchant leftover | 9 | Greek ";" and "·", "H&M", "McDonald's", 100-char cap, casing kept, generic word, multiword |
| L | Category exact name | 7 | case-insensitive, "Legal & Gov", Greeklish ↔ Greek user category, ambiguous, removal kept when blank, "taxi home" |
| M | Empty / garbage / limits | 8 | blank, whitespace, punctuation only, emoji only, 201 chars, date only, amount only, "a1 b2" |
| | **Total** | **132** | |

---

## 13. Open decisions (human)

| ID | Decision | Recommended default |
|---|---|---|
| **OD-1** | Pre-existing **clipboard comma-decimal defect**: clipboard "12,50" becomes amount "1250" (V7, V9). The brief says "one-shot channel unchanged". Fix it in increment 1 (canonicalise the clipboard string with the same §4.5 helper before `sanitize`) as its own small slice? | **Yes, own slice** (money correctness on the same screen and the same helper). Otherwise file it as a 1.0.x bug |
| **OD-2** | Extend save-error hygiene to the VM `Result.Error` branch (:415-425), which shows repository `exception.message` or joined validation errors (V18 :205-216). VM-only mapping, repository unchanged | **Yes** (same privacy class, same file); validation messages map to the same bounded generic or to existing string resources |
| **OD-3** | Leading bare integer followed by a word ("2 καφέδες", "15 taxi") → `AMOUNT_QUANTITY` (fail-closed), vs filling 15.00 | **Flag** (brief lists quantities as ambiguous; "15€ taxi" and "taxi 15" still fill) |
| **OD-4** | A weekday equal to today → `DATE_AMBIGUOUS` (date left at today) vs resolving to 7 days ago | **Flag** |
| **OD-5** | Non-catalog ISO-4217 codes: block when adjacent/attached (chosen) vs treat as merchant text (brief's literal "unknown codes = merchant text") vs block anywhere (word collisions: cup, pen, all, top) | **Adjacent/attached block** (protects the "never saved as home by accident" success signal) |

Decisions taken here without a human, which UX may revisit:
- Category tokens are removed from the merchant only when the leftover stays non-blank (§4.11).
- Fill does not trigger suggestions (INV-9).
- 92-day year-inference window (§4.7).
- Time tokens are ignored, not applied (§4.8).

Top risks:
- **R1** Generic English default category names (Home, Food, Kids, Gifts, Travel) can match ordinary words ("taxi home 12" → Home). The category is visible, but if the user saves without noticing, trusted MANUAL_ENTRY learning makes the wrong mapping permanent (Gate 1 Q5 = learn as today). Mitigation: visible chip and fixtures. Revisit if noisy.
- **R2** Implementers reusing `categorize()` / `getCategoryForMerchant()` for the Fill lookup would write aliases and log raw text before Save (V12–V14). C2's contract and the §7.5 guard exist for this.
- **R3** Fill-contract concurrency (async clipboard, async C2 lookup, user edits) is the main source of overwrite bugs; covered by INV-8 and INV-18 and by AE-21 and AE-29.
- **R4** Locale amount parsing. All ambiguous shapes are fail-closed, so the fixture suite carries correctness.

---

## 14. Proposed architecture-doc / guard changes (proposals only, not edited)

- `LEGAL_PATHS.md` §Expense Mutations, CREATE: add a note that UI prefill aids (clipboard prefill, quick-add Fill, merchant suggestion) are in-memory form writes, not mutation paths, and must not call repository/DAO writes.
- `ENGINE_INTERACTION_MAP.md`: record that `MerchantNormalizer.normalize(autoCreate=false)` can write aliases (V13). Read-only callers must use `normalizedCanonicalNameForMerchant` + `getCategoryByNormalizedCanonical`.
- Guards: the static checks in §7.5.

---

## 15. Findings outside increment scope (route via orchestrator; no action taken)

- **F1** Clipboard comma-decimal ×100 defect (V7/V9) → OD-1.
- **F2** `MerchantNormalizer.normalize(autoCreate=false)` writes aliases and logs `rawName` at `:102` (V13). This read-path side effect and log are pre-existing.
- **F3** `CategorizationEngine.categorizeWithContext` logs raw merchant at `Timber.d` (`:185-186`).
- **F4** `ManualExpenseRepository :252` `Timber.e(e, …)` attaches a throwable; `:214-216` passes raw `exception.message` upward → OD-2 handles the VM side only.
- **F5** `GreeklishNormalizer.toLatinStatic` (merchant keys) does not fold accented diphthongs ("μασούτης" → "masoytis" vs "μασουτης" → "masoutis"), so merchant keys split on accents. Pre-existing; it affects dedup and history matching, not quick-add correctness.
