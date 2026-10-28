# Statement import — implementation plan (from the planner, 2026-10-21)

Companion to `statement-import-investigation.md`. All fixture values must be INVENTED; never commit
real names, merchants, IBANs, amounts or lines from the owner's statements.

## Architecture

New pure package `domain/receipt/statement/` (no Android, no Room):

* `TextStatementParser @Inject(merchantCleaner, timeProvider)`:
  `parse(pages: List<List<String>>, home: String?): TextStatementParseResult(layout: StatementLayoutId?, rows: List<StatementRow>)`.
* `StatementRow` = `Parsed(tx: ParsedTransaction, currencyEvidence: ROW | STATEMENT_HEADER | NONE)` or
  `Skipped(reason: StatementSkipReason, page: Int)`. Reasons: `NO_DATE, CANCELLED, UNKNOWN_STATUS,
  NOT_SETTLED, AMOUNT_ABOVE_CEILING, INCOMPLETE_ROW` (zero amounts and unreadable rows are counted, never
  silently dropped). A matched layout that throws returns its layout id with `failed = true` and no rows,
  so the caller never falls back to the generic parser for a recognised statement.
* `TextStatementLayout { matches(pages): Boolean; parse(pages, ctx) }` implemented by
  `RevolutTextLayout`, `NbgAccountLayout`, `EurobankCardLayout`, `EurobankTransfersLayout`; each selected
  by its header line. Unknown layout (`layout = null`) falls back to
  `BankStatementParser.parse(blocks, home, requireDate = true)`.
* Shared helpers: `StatementAmountParser` (signed "1.234,56", "€1,234.56", "-7,41"; built on
  `AmountUtils`), `StatementDates`, `CounterpartyLabel`, `StatementImportGate` (pure function for the
  processor-side checks so tests can run without Room).

Text path flag: `OcrResult.pdfTextPages: List<List<String>>? = null` (non-null only on the PDF text
path; default keeps the OCR/image path and existing tests unchanged).

## Layout rules

* **Eurobank card**: row = `dd/MM/yyyy dd/MM/yyyy DESCRIPTION AMOUNT`. Negative amount = PURCHASE;
  unsigned positive = DEPOSIT (INCOMING, refund/top-up). Only lines after the table header are rows;
  header block (address, card mask, limit, search period) and footer lines are ignored. No line dedupe.
* **NBG account**: row starts `dd/MM/yyyy HH:mm:ss dd/MM/yyyy BRANCH CODE DESC`; the tail
  `[Χ|Π] AMOUNT BALANCE` can be on a continuation line: a date-led line without the tail opens a pending row;
  following undated lines are appended until the tail arrives. Amount = the token after the marker (never
  the balance). Orphan tail = Skipped(NO_DATE). Footer pattern (`n από N Ημ/νία Εκτύπωσης`, bank name,
  phone, web, repeated column header) is dropped before stitching. Operation code `013` = TRANSFER
  INCOMING; `064/068/022` = TRANSFER, direction from Χ/Π; `040` = PURCHASE (REFUND/DEPOSIT if Π);
  `076` = DEPOSIT; unknown codes go by Χ/Π.
* **Revolut**: rows only after `Date Description Money out Money in Balance`, date-led `MMM d, yyyy`.
  Opening balance from the `Account (Current Account)` row of the balance summary. Direction from the
  balance chain: +amount → INCOMING, −amount → OUTGOING, −(amount+fee) → OUTGOING; rows that fit none use
  keywords and reset the chain (counted). Continuation lines (`Reference:`, `To:`, `From:`, `Fee:`, foreign
  amounts) belong to the row above; `Reference:`/`Card:` are never stored. `Transfer to/from X` +
  `To:`/`From:` → TRANSFER with direction and label. `Exchanged to X` → TRANSFER (direction from the
  balance chain) labelled `Currency exchange`, no counterparty. Pending/Reverted sections skipped
  (NOT_SETTLED); the settled table header (with the Balance column) ends such a section.
* **Eurobank transfers**: buffer lines from one date line to the next; repair a wrapped recipient IBAN
  (GR = 27 chars; merge following digit-only pieces). Status credited → import; cancelled → Skipped(CANCELLED);
  other → Skipped(UNKNOWN_STATUS). Rows = TRANSFER OUTGOING; own IBAN ignored. Counterparty label =
  `<BANK> ****<last4>` (IBAN never stored).

## Counterparty labels (CounterpartyLabel)

* name present: merchant = name; account name = `To: X` / `From: X` (as the notification parsers do).
* IBAN only: merchant = `<BANK> ****<last4>`; account name `To: <BANK> ****<last4>`; unknown bank →
  `Account ****<last4>`.
* `maskIbans()` last scrub at the processor boundary: merged merchant, label, and `sanitizeMerchant`
  (pattern `[A-Z]{2}\d{2}(?:\s?[A-Z0-9]){11,30}`).

## Slices

* **S1 (large)**: new `domain/receipt/statement/*` + tests (`StatementTextFixtures.kt` with invented
  constants). No existing file touched.
* **S3 (small)**: `BankStatementParser.kt`: `requireDate: Boolean = false` parameter (text fallback only),
  hoist the per-row `fullText` rebuild at ~:216-221 (quadratic), guard the Revolut `ParsedTransaction`
  construction at ~:611 (amount > 1M currently throws and aborts the whole parse).
* **S2 (medium)**: `ReceiptOcrService.processStatementUri` (statement-only; text path reads every page up
  to `STATEMENT_TEXT_PAGE_CAP = 200` and 2,000,000 chars; OCR fallback keeps 5 pages; receipts keep 5),
  `OcrResult.pdfTextPages`, `ReceiptRepository.runStatementOcr`, bounded `PDF_TEXT_EXTRACT_FAILED`
  (class name only) instead of `Timber.e(e, ...)`.
* **S4 (large)**: processor routing, skipped items (NO_DATE etc.), direction/label carried through
  `MergedTransaction`, `suggestedDirection/suggestedAccountName`, amount ceiling 100,000, run status
  (COMPLETED_WITH_SKIPS), same-run pending-review exclusion, `BankStatementResult` counts.
* **S5 (small)**: `ReviewViewModel` summary message ("Read N of M pages; X reviews, Y duplicates,
  Z skipped (no date: a, cancelled: b)").
* **S6 (small)**: `LEGAL_PATHS.md` statement section; this doc's status.

## Acceptance (local, real data, never committed)

Eurobank card >= 99% exact per file, 0 junk rows, credits typed DEPOSIT; NBG >= 98% of rows, 0
balance-as-amount, 0 undated reviews, >= 95% of 013/064/068/022 rows typed TRANSFER with direction;
Revolut 0 sign errors, >= 95% matched, 0 junk, net within EUR 1, >= 95% of transfers labelled; transfers
export 40/40 accounted for; no IBAN pattern in any merchant/label/item field; overview PDF 0 rows;
`pagesProcessed == totalPages` on the 48- and 53-page files; parse time < 2 s.

## Owner decisions (defaults recorded here when accepted)

| ID | Question | Recommended |
| --- | --- | --- |
| O1 | A currency declared once in the statement header counts as source evidence (NBG/Eurobank rows carry no symbol) | yes, only if the header names exactly one currency |
| O2 | Run status: do not FAIL a run just because rows were skipped; edit two pinned assertions | yes |
| O3 | NO_DATE skip scope | text path only |
| O4 | Exclude same-run pending reviews from the pending-duplicate check | yes |
| O5 | Per-item amount ceiling | 100,000 |
| O6 | AI validation on large statements | batch candidates (about 40 per call) |
| O7 | Caps and unknown text layouts | 200 pages / 2M chars; generic fallback with a leading date |
| O8 | Revolut Pending/Reverted sections | skip |
| O9 | Unknown transfer statuses | skip, counted |
| O10 | Merchant normalizer creating entries for person names and `****last4` | avoid for TRANSFER rows if simple, else follow-up |
| O11 | ExportAnonymizer coverage of expense/pending-review counterparty fields | follow-up slice |
| O12 | Raw statement text storage cap | follow-up (debug-only feature) |
