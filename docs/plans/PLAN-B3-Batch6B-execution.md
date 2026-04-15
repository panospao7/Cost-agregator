# PLAN-B3-Batch6B — Bank Statement Parser Correctness

## 1. Objective & Blast Radius
- **The Core Issue:** `BankStatementParser` currently favors the largest matched amount when candidates tie, so rows containing both a transaction amount and a running balance can import the balance instead of the actual movement. The same parser also computes header/date-column metadata but never applies it, and its internal Revolut-statement path bypasses locale-safe amount parsing while collapsing explicit transfer/withdrawal/refund/top-up rows into only `PURCHASE` or `DEPOSIT`.
- **Blast Radius:**
  - `app/src/main/java/com/yourname/expensetracker/domain/receipt/BankStatementParser.kt`
  - `app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt` (`processStatement()` consumes parser output)
  - `app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModel.kt`
  - `app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewScreen.kt`
  - Statement-imported `PendingReview` rows and every downstream analytics/dashboard/budget/report surface after those reviews are approved
- **Assumptions / Unknowns:**
  - `BankStatementParser` is only consumed in production by `ReceiptRepository.processStatement()`; keep the fix parser-local unless read-first audit proves a contract gap.
  - `ParsedTransactionType` has no dedicated `REFUND` enum, so refund/top-up money-in rows must map to existing semantics (`DEPOSIT`) rather than adding a new public enum.
  - `PendingReview` does not currently persist transfer direction/account metadata for statement imports; this batch should fix amount/type/date correctness, not widen schema.
  - Some OCR screenshots may not expose header order clearly; fallback behavior must remain conservative when the header order is unknown.

## 2. The Single Source of Truth (The Standard)
- The canonical amount rule for statement rows is: **when multiple plausible amount tokens exist, select the transaction-amount column by column/position heuristics, never by largest absolute value.**
- The canonical date rule is: **`DateColumnInfo` must express actual transaction-date ordering (`FIRST`, `SECOND`, or `UNKNOWN`) and the generic row extractor must consume it when picking transaction date vs. value date.**
- The canonical Revolut statement amount rule is: **keep the raw amount token intact and pass it to `AmountUtils.parseAmount()`; never manually replace commas/dots.**
- The canonical Revolut statement type rule inside `BankStatementParser` is:
  - explicit `Transfer to` / `Transfer from` / `Received from` wording -> `TRANSFER`
  - explicit ATM / cash withdrawal wording -> `WITHDRAWAL`
  - explicit refund / top-up / promo / add-money wording -> `DEPOSIT`
  - otherwise money-out merchant spend -> `PURCHASE`
- Keep the public parser contract unchanged:
  - do **not** change `fun parse(blocks: List<TextBlock>): List<ParsedTransaction>`
  - do **not** change the constructor signature
  - do **not** add new `ParsedTransactionType` values in this batch

## 3. File-by-File Execution Checklist

### Domain Layer

#### Batch 1 — Generic statement amount/date correctness
- **Complete when:** generic statement rows no longer choose running balance over transaction amount, and detected header order materially affects the selected transaction date when both transaction/value dates are present.

- [ ] `app/src/main/java/com/yourname/expensetracker/domain/receipt/BankStatementParser.kt`
  - Extend `DateColumnInfo` so it can represent usable transaction-date order (`FIRST`, `SECOND`, `UNKNOWN`) instead of boolean presence only.
  - Rework `detectDateColumns(...)` so it uses header keyword positions from the first header rows to infer whether the transaction-date column appears before or after the value-date column.
  - Thread the resolved date-order information into the generic extraction path and apply it when choosing between the first and second parsed date.
  - Remove the current amount-selection dependency on `thenBy { kotlin.math.abs(it.parsed) }`.
  - When a generic row has multiple strong amount candidates, prefer the transaction-amount column over the rightmost running-balance column.
  - If row-text-only heuristics are not sufficient, thread existing `rowBlocks` into a new private generic-row extractor; do **not** change the public `parse(...)` API.
  - Keep Greek NBG parsing behavior intact except where a tiny shared helper reuse is clearly safe.
- [ ] `app/src/test/java/com/yourname/expensetracker/domain/receipt/BankStatementParserTest.kt`
  - Add a regression where a single row contains both a smaller transaction amount and a larger running balance; assert the transaction amount wins.
  - Add a regression with two date columns plus header keywords that indicate date order; assert the chosen parsed date follows the header-derived transaction-date order.
  - Drive both regressions through the public `parse(List<TextBlock>)` API using realistic `TextBlock` coordinates; do **not** use reflection.

> [!WARNING]
> - Do **not** rewrite row grouping, OCR row detection, or Greek NBG parsing as part of this batch.
> - Do **not** “fix” amount selection by hardcoding “smallest amount wins”; the rule must be column/position-aware.
> - Do **not** delete `detectDateColumns()` or `columnInfo` just to silence dead logic; this batch must make header analysis real.

#### Batch 2 — Internal Revolut statement row correctness
- **Complete when:** the Revolut-statement path inside `BankStatementParser` parses grouped amounts through `AmountUtils` and emits non-blind transaction types for explicit transfer/withdrawal/refund/top-up rows.

- [ ] `app/src/main/java/com/yourname/expensetracker/domain/receipt/BankStatementParser.kt`
  - In `tryParseRevolutTransaction(...)`, stop manually stripping separators with `.replace(",", ".")`.
  - Extract the raw amount token, remove only currency markers / layout whitespace as needed, and parse via `AmountUtils.parseAmount()`.
  - Add a private Revolut-statement classification helper local to this file; keep it statement-specific so Batch 6C can fix standalone `RevolutParser.kt` independently.
  - Classify `Transfer to` / `Transfer from` / `Received from` as `TRANSFER`, cash / ATM wording as `WITHDRAWAL`, and refund / top-up / promo / add-money wording as `DEPOSIT`.
  - Preserve merchant cleanup, but do **not** flatten explicit transfer/ATM/refund/top-up descriptors into generic purchase labels.
  - Keep transfer direction optional; do **not** widen repository/UI contracts to persist new statement metadata in this batch.
- [ ] `app/src/test/java/com/yourname/expensetracker/domain/receipt/BankStatementParserTest.kt`
  - Add Revolut-statement regressions for:
    - grouped amount parsing (`€1,234.56` and/or `€1.234,56`)
    - `Transfer to ...`
    - `Transfer from ...` or `Received from ...`
    - `ATM withdrawal` / cash withdrawal
    - top-up / refund / promo money-in wording
  - Assert parsed amount and `ParsedTransactionType` for each case.

> [!WARNING]
> - Do **not** modify standalone `app/src/main/java/com/yourname/expensetracker/domain/parser/parsers/RevolutParser.kt` in Batch 6B.
> - Do **not** route statement OCR rows through notification-parser regexes.
> - Do **not** add new transaction-type enums or database schema fields for refund semantics in this batch.

### Data Layer

#### Batch 3 — Statement import blast-radius audit
- **Complete when:** corrected parser output still flows through statement import with no schema/API expansion.

- [ ] `app/src/main/java/com/yourname/expensetracker/data/repository/ReceiptRepository.kt`
  - Read after Batch 1/2 and confirm `processStatement()` still only needs corrected `amount`, `currency`, `merchant`, `type`, and `date` from `BankStatementParser`.
  - Keep production code unchanged unless a concrete parser-output contract bug is proven.
  - If a code change becomes necessary, keep it limited to consuming the existing `ParsedTransaction` fields; do **not** add new `PendingReview` or DB fields.
- [ ] `app/src/test/java/com/yourname/expensetracker/data/repository/ReceiptRepositoryStressTest.kt`
  - Touch only if `ReceiptRepository.kt` changes.
  - If touched, add the narrowest possible statement-import smoke regression rather than broad new stress coverage.

> [!WARNING]
> - Do **not** widen statement-import persistence to carry transfer direction/account data here.
> - Do **not** change dedupe policy, approval flow, or review-queue schema as part of this fix.

### UI Layer

- [ ] `app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewViewModel.kt`
  - Audit only.
  - Confirm statement-import success/error copy still matches parser output count expectations.
- [ ] `app/src/main/java/com/yourname/expensetracker/ui/screens/review/ReviewScreen.kt`
  - Audit only.
  - Keep picker / import-button behavior unchanged.

> [!WARNING]
> - No UI behavior redesign belongs in Batch 6B.
> - Do **not** change statement-import UX copy unless parser semantics force it and reviewer signs off.

## 4. Verification Plan
- **Unit Tests:**
  - Update and run:
    - `app/src/test/java/com/yourname/expensetracker/domain/receipt/BankStatementParserTest.kt`
  - Only if Batch 3 changes `ReceiptRepository.kt`, update/run:
    - `app/src/test/java/com/yourname/expensetracker/data/repository/ReceiptRepositoryStressTest.kt`
- **Compile / verification order:**
  - After each micro-batch, run:
    - `./gradlew.bat :app:compileDebugKotlin`
    - `./gradlew.bat :app:testDebugUnitTest --tests "com.yourname.expensetracker.domain.receipt.BankStatementParserTest"`
  - If Batch 3 changes code, add the narrowest extra command for that test file only.
- **Syntax/Lint:**
  - Ensure no imports broke after `DateColumnInfo` and helper changes.
  - Ensure no dead private helpers/comments remain from the old unused header-analysis path.
  - Ensure no stale comments still claim magnitude-based amount preference.
- **Reviewer gate:**
  - Reviewer should specifically inspect rows with multiple amounts and rows with two dates plus headers to confirm the fix is heuristic-safe and backward-compatible.

## 5. Documentation & Registry Updates (CRITICAL)
- **Registry Update:**
  - In `docs/analyses and debug master/MASTER-ISSUE-REGISTRY.md`, under `### B.3: Receipt/OCR Pipeline`, append `[RESOLVED BY B.3 — Batch 6B]` to these live bullets:
    - current line ~226: `BankStatementParser` amount selection breaks ties by largest absolute value — can select running balance instead of transaction amount (B44)
    - current line ~227: Revolut statement parsing strips currency symbols and blindly replaces commas with dots — thousands separators fail (B44)
    - current line ~228: Revolut statement emits only `DEPOSIT` or `PURCHASE` — transfers/top-ups/refunds misclassified (B44)
    - current line ~243: `BankStatementParser` header/date-column detection computed but never used (B44)
- **Batch Reports:**
  - Update `docs/analyses and debug master/final verification/FINAL-VERIFICATION-BATCH-44.md`
    - resolve / annotate verified issue rows **7, 8, 9, and 10**
    - resolve / annotate cross-component issue **#2** only if the implementation truly eliminates the Revolut semantics drift
  - Update `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-44.md`
    - resolve / annotate issue rows **7, 8, 9, and 10**
    - resolve / annotate cross-component issue **#2** only if fully satisfied
  - Read `docs/analyses and debug master/DEEP-ANALYSIS-BATCH-44-DEBUGGER.md` first; if the same issues are present there, resolve / annotate:
    - file-level issue rows **6, 7, 8, and 9**
    - cross-component issue **C4** only if the implementation fully satisfies it
- **Documentation sequencing rule:**
  - Do docs only after reviewer PASS.
  - Update registry first, then final-verification doc, then deep-analysis mirrors/debugger mirror.
  - Do **not** mark the whole `B.3` pipeline resolved.
