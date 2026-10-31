# Statement import investigation (text PDFs) — findings and fix plan

```text
Date:     2026-10-21 (owner-requested full investigation, real statements used locally)
Branch:   fix/statement-import (from bug-fixes 0a52b066)
Scope:    BankStatementParser / ReceiptOcrService text-PDF path / BankStatementLifecycleProcessor
Status:   IMPLEMENTED on fix/statement-import (S1–S6); see "Outcome" at the end. Statement import
          is DEBUG-ONLY in release builds today (ReviewScreen
          import menu is inside `if (debugActionsEnabled)`; DeferredFeatureGate.available = DEBUG).
Privacy:  This document contains NO real data: only counts, shapes and invented examples. The real
          statements stay in the owner's Downloads folder and are never committed. Repository tests
          use synthetic fixtures with invented names/merchants/IBANs.
```

## 1. Method

A temporary JVM probe (never committed) ran the app's own PDF text extraction (PDFBox, same line
splitting as `ReceiptOcrService.processPdf`) and `BankStatementParser.parse` over nine real PDFs, with
the parser replicated first with the app's 5-page cap and then over ALL pages. Results were compared
with the matching exports (five card CSVs, a transfers CSV, a Revolut CSV, and an XLSX converted to
CSV) as answer keys. Output was aggregates and digit/letter shapes; a local-only dump of lines was
read afterwards to design the rules.

## 2. Findings (all pages read)

| Source layout | Key rows | Result |
| --- | --- | --- |
| Eurobank card statements (5 PDFs, 78–564 rows) | 78–564 | 99% exact (date, amount, merchant). Defects below. |
| NBG account statement (53 pages, 933 rows) | 933 | 917 exact; credits/debits signed correctly; 16 date-less rows, 7 missing, 8 amount diffs. |
| Eurobank "transfers to other bank" export (40 rows) | 40 | 32 read; 8 missing (wrapped IBAN); all typed PURCHASE; merchant = IBAN pair. |
| Revolut statement (48 pages, 779 rows) | 779 | 523 matched (67%); 241 wrong sign; 149 junk rows; counterparty lost. Parsed net about -EUR 70,000 vs real +EUR 3 (invented magnitudes). |
| Accounts overview PDF (1 page) | n/a | not a statement; 2 fake purchases. |

### Defect classes

D1. **Page cap.** `ReceiptOcrService` reads at most 5 pages (`minOf(totalPages, 5)`). With the cap only ~110
    rows of 200–564 are seen; the user gets a generic "partially imported" text without page counts.
D2. **Junk rows from headers/footers/details** (4 per Eurobank card statement; 16 in the NBG file; 149 in
    Revolut). Causes: the generic branch needs only an amount-shaped number and never a date; a
    postal code (e.g. "00000") or limit line (e.g. "9.999,00 €") or print-date footer becomes a transaction. The
    processor then turns a null date into TODAY (`tx.date ?: now`, BankStatementLifecycleProcessor:606)
    and stores a PendingReview.
D3. **Identical repeated lines dropped** (`preFilterRows` dedupe, meant for OCR double reads). In text PDFs
    these are real repeated purchases/top-ups (three identical top-ups on one day).
D4. **Card refunds/credits** (Eurobank card statements): credits are printed as unsigned positive amounts;
    the parser takes absolute values and types them PURCHASE (wrong sign, counted as spending).
D5. **Wrapped rows** (NBG): a long description wraps; the marker (Χ/Π), amount and balance sit on the
    continuation line, which has no date. The parser emits a date-less row and sometimes takes the
    BALANCE as the amount; the real transaction (often an incoming transfer) is lost or wrong.
D6. **Revolut direction.** The text path gives every line `left=0,right=1000`, so the position-based
    money-in/out test can never fire. Incoming transfers/top-ups are read as outflows. The balance chain
    (opening balance + each row's balance) is sufficient to infer the sign.
D7. **Transfers lose identity.** Greek statement rows never produce TRANSFER; Revolut TRANSFER rows have no
    direction and no counterparty (`BankStatementParser:599` strips everything after "To/From"). The
    transfers export types outgoing transfers PURCHASE and stores the IBAN pair as the merchant
    (privacy: counterparty IBAN persisted).
D8. **Wrapped IBAN / status** in the transfers export splits one row over 2–3 lines (8 of 40 lost). A
    status "Ακυρώθηκε" (cancelled) row must not be imported.
D9. **Processor/UX:** any skipped item makes the run FAILED even when reviews were created;
    SuggestedDirection/AccountName are never filled for statement rows; no amount ceiling.

## 3. Layout specifications (examples are invented)

* **Revolut (text PDF)**: transactions start after a line "Date Description Money out Money in Balance".
  Row: `Mar 14, 2031 Transfer to JOHN DOE €12.34 €56.78`. Continuation lines: `Reference: ...`,
  `To: NAME`, `From: *1234`, `Fee: €0.07 €9.99`, a foreign amount `$7.10`. The "Balance summary" table
  above gives the opening balance: `Account (Current Account) €2.50 €1,111.11 €1,222.22 €113.61`
  (opening, money out, money in, closing). Rows are in ascending date order.
* **NBG account statement**: header `Ημερομηνία / Ώρα Valeur Κατάστημα Συν Περιγραφή Χ/Π Ποσό Λογιστικό Υπόλοιπο`.
  Row: `18/12/2031 08:30:00 18/12/2031 123 013 SOME PERSON Π 16,00 116,00` (value date, branch, operation code,
  description, Χ=debit/Π=credit, signed amount, balance). Wrapped rows: first line carries date .. start of
  description; next line carries the rest of the description plus `Π 2,00 91,55`. Per-page repeated
  header/footer lines (bank name, "n από 53 Ημ/νία Εκτύπωσης dd/MM/yyyy hh:mm:ss", phone/web) are not rows.
  Operation code classes seen: card purchases (040), incoming transfers (013), own/other transfers
  (064/068/022), benefits (076). The account holder's own name appears as counterparty on own-account
  transfers (header "Δικαιούχος: NAME").
* **Eurobank card statement**: header spread over lines (`ΗΜ/ΝΙΑ ΣΥΝΑΛΛΑΓΗΣ ΗΜ/ΝΙΑ ΑΞΙΑΣ ΠΕΡΙΓΡΑΦΗ ΠΟΣΟ`).
  Row: `18/11/2031 19/11/2031 SOME SHOP -7,41` (transaction date, value date, description, amount;
  debit negative, credit unsigned positive). Header block above the table (address, card mask, daily
  limit, search period) and footer (bank address, phone) are not rows.
* **Eurobank transfers history**: title `Ιστορικό Μεταφορών σε άλλη τράπεζα`; row
  `05/12/2031 GR<own IBAN> GR<recipient IBAN> BANK NAME 11,00 EUR Πιστώθηκε στον` + `δικαιούχο`; a
  recipient IBAN can wrap (`...1112` / `3`); status values seen: credited to the beneficiary, cancelled.
  There is no counterparty name, only IBAN and bank.

## 4. Decisions (owner defaults, 2026-10-21)

1. Statement transfers become `TRANSFER` with `transferDirection` and a counterparty label.
2. IBANs are never stored. For IBAN-only counterparties the label is `<bank name> ****<last4>`
   (masked account reference, consistent with the RP-17 contract). Names already stored for purchases
   keep the existing convention (`From: X` / `To: X`).
3. No own-account netting in this work (no own-account concept in the model; separate feature).
4. Rows without a date are skipped and counted (reason NO_DATE); never dated "today".
5. The page cap is removed for the text path; the OCR path keeps its cap, and the message states
   "first N of M pages".
6. Cancelled transfers are skipped (reason CANCELLED).
7. No Room schema change: `Expense.transferDirection/transferAccountName` and
   `PendingReview.suggestedDirection/suggestedAccountName` already exist.

## 5. Out of scope / follow-ups

* CSV/XLSX statement import (no importer exists; feature decision for the owner).
* Own-account transfer detection and netting; two-leg transfer linking; income table.
* Revolut multi-currency statements beyond EUR (not exercised).
* Statement import stays debug-only until the owner decides to enable it.

## 6. Outcome (2026-10-21)

Implemented on `fix/statement-import` (not pushed): S1 layouts, S2 text path, S3 generic-parser
hardening, S4 processor, S5 review summary, S6 docs. Real-data acceptance (aggregates only; local probe,
not committed): Eurobank card 100% on 5 files, 0 junk rows, 6 refunds typed DEPOSIT; NBG 933/933 rows
matched, 0 balance-as-amount, 0 undated rows, transfers typed with direction and label; Revolut 778/779
matched, 0 sign errors, net difference equals the fees (statement amounts include fees); transfers export
39 imported + 1 cancelled with no IBAN stored; overview PDF 0 rows; every PDF parses in under 100 ms.
Validation on `689da384`: targeted 328/0/1, static-guards 25/25, app-check 15,319/0/3.

Follow-ups (not part of this work):

* N1: the pending-duplicate lookup is LIMIT 1 (residual with same-run exclusion).
* N2: the 5-minute expense dedupe window versus midnight statement dates.
* O11: ExportAnonymizer does not cover `expenses.merchant`, `expenses.transferAccountName`,
  `pending_reviews.suggestedMerchant/suggestedMerchantKey/suggestedAccountName`,
  `bank_statement_import_items.transactionFingerprint`, or the merchant canonical/alias tables.
* O12: raw statement text cap (200,000 chars) and retention.
* F-H1-g: a currency symbol before the amount is not captured.
* Domestic account numbers under 12 digits in NBG descriptions are not masked.
* Possible leftover IBAN digits in the Eurobank bank name when an IBAN wraps.
* A Revolut P2P row carrying a fee is unlabelled.
* CSV/XLSX statement import is not implemented (owner decision).
* Own-account transfer detection and netting.
* MINOR fingerprint: `bank_statement_import_items.transactionFingerprint` equals the merchant key
  (a transliteration, not a hash) and survives the 30-day merchant purge for transfer counterparty names.
* Cloud payload: the 2,000-character truncation means the cloud never sees the candidate list
  (pre-existing; cloud validation of statements is effectively text-only).
* Eurobank transfers: a wrapped non-GR IBAN leaves bank-name digits in the label.
* Revolut "To NAME" / "Payment from NAME" rows are not typed TRANSFER, so they feed merchant learning.
* Revolut `chainResets` is not surfaced to the summary.
* There is no per-run row cap and no timeout on the AI call (the AI chunk count is capped at 5).
* The drain test could inject the dispatcher instead of relying on the default one.
* Aggregate statement metadata appears in this document (section 6); use relative figures if the
  repository is shared.

Owner decisions: O1-O12 defaults accepted by owner via blanket approval on 2026-10-21.
