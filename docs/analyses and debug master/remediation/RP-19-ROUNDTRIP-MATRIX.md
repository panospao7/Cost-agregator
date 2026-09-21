# RP-19 Roundtrip Contract Matrix

> Status: published 2026-09-20 (RP-19 lane, batch 19-B) — this matrix is the normative
> import/export field contract. It was written BEFORE the mapping code changes it
> describes. D3 keeps CSV import debug-only; this matrix fixes importer CORRECTNESS
> only and does not promote anything to release UI.

## Surfaces covered

- **CSV v2 export** — generic CSV from `ExportOptionsViewModel` (`# ExpenseTracker Export v2` metadata line + `ID,Date,CreatedAt,Merchant,Amount,EffectiveAmount,Currency,TransactionType,...` header).
- **CSV legacy import** — `date,amount,merchant,category[,description]` rows (old app exports; debug-only per D3).
- **CSV v2 import** — the same reader, column-name-driven; recognizes the full v2 header.
- **JSON v2 export** — `schemaVersion:2` streaming export.
- **JSON v1/v2 import** — `JsonExpenseImporter` (`schemaVersion >= 2` → v2 parser, else v1).
- **ImportCoordinator** — canonical facade retained per D4; CSV leg now surfaces per-row errors.

## Canonical principles

1. **`amount` is the original transaction amount** (before any ownership-share reduction).
2. **`effectiveAmount` is reconstructed, never imported as the primary amount.** The
   entity (`Expense.effectiveAmount`) recomputes it from `amount` + ownership fields
   (`isNotMine`, `isSharedExpense`, `myShareAmount`, `mySharePercentage`) at read time.
   The exported value is used **only** as an amount fallback (principle 3).
3. **ONE amount precedence for every transaction type, in both CSV and JSON:**
   `amount` first; `effectiveAmount` only when `amount` is absent or unparseable;
   row fails when neither parses. (This deliberately does NOT mix the old implicit
   CSV "amount-only" behavior with JSON's amount-first fallback — amount-first is the
   single rule everywhere.)
4. **`transactionType` maps to the exact enum.** A present but unparseable value fails
   the row with the controlled code `UNKNOWN_TRANSACTION_TYPE` — never a silent
   `PURCHASE` default. Absent values keep the documented default (`PURCHASE`).
5. Metadata that the export does not carry has a **documented unsupported default**
   (table below) — it is never invented on import.
6. Money stays `Double` in this pipeline (existing entity semantics); no floating-point
   math or rounding semantics are changed. Boundary amounts (0.0, negative, large) must
   roundtrip verbatim.

## Field matrix

| Field | CSV v2 export | JSON v2 export | CSV import | JSON v2 import | JSON v1 import |
|---|---|---|---|---|---|
| `amount` (original) | `Amount` | `amount` | primary; `EffectiveAmount` fallback only if absent/unparseable; neither parses → **FAIL `Invalid amount`** | primary; `effectiveAmount` fallback only if absent/unparseable; neither parses → **FAIL `INVALID_AMOUNT`** | `amount` only |
| `effectiveAmount` | `EffectiveAmount` = `Expense.effectiveAmount` | `effectiveAmount` | fallback only, never overrides `amount` | fallback only | not present |
| `transactionType` | `TransactionType` (exact enum name) | `transactionType` | exact enum (case-insensitive); absent → `PURCHASE`; unknown → **FAIL `UNKNOWN_TRANSACTION_TYPE`** | same as CSV | always `PURCHASE` (schema had no type) |
| `currency` | `Currency` (ISO 4217) | `currency` | explicit `Currency` column → currency symbol in amount → home-currency fallback | `currency`, default `EUR` | same |
| `date` | `Date` (`yyyy-MM-dd`, `Locale.US`, zone-less `LocalDate` rendered in device zone) | `date` (`yyyy-MM-dd`) + `timestamp` (epoch ms) | parsed with `Locale.US` formatter → start-of-device-zone epoch ms | `date` (epoch ms) preferred, else `timestamp`, else time provider once | same |
| `merchant` | `Merchant` | `merchant` | required | required | required |
| `category` | `Category` (name) | `category` (name) | get-or-create by name; missing cell → `Uncategorized` | get-or-create; absent → coordinator default | same |
| `notes` | `Notes` | `notes` | `Notes` column (`notes` also accepted) | `notes` | `notes` |
| `source` | `Source` (raw `ExpenseSource` name) | `source` | **not read** → `CSV_IMPORT` | exact enum; absent/unknown → `CSV_IMPORT` | `CSV_IMPORT` |
| `paymentMethod` | `PaymentMethod` | `paymentMethod` | **not read** (unsupported default: coordinator default `UNKNOWN`) | exact enum; absent → null (coordinator default `UNKNOWN`) | not read |
| `id` | `ID` | `id` | ignored (new IDs assigned by insert) | used only as idempotency key `import:json:<id>` | same |
| `createdAt` | `CreatedAt` (local date) | `createdAt` (epoch ms) | ignored on import | ignored on import | n/a |
| base-currency audit (`BaseAmount`, `BaseCurrency`, `ExchangeRateUsed`, `ConversionRateUsed` / `baseAmount`, `baseCurrency`, `exchangeRateUsed`, `conversionRateUsed`) | exported | exported | ignored (recomputed at creation by the lifecycle coordinator) | ignored | n/a |
| business fields (`IsBusinessExpense`, `BusinessPurpose`, `BusinessCategory`, `BusinessProject`, `RequiresReceipt`) | exported | exported | **not read** (unsupported default: `false`/null) | `isBusinessExpense`, `businessPurpose` mapped | not read |
| source links (`SourceLinks` / `sourceLinks`) | exported | exported | ignored (documented: provenance not restored via this path) | ignored | n/a |

## Ownership / special-type metadata — documented unsupported defaults

The export schema does **not** carry ownership metadata, so a shared/not-mine row
cannot be restored bit-exact. This is a documented, deliberate lossiness:

| Concept | Exported? | Import default | Rationale |
|---|---|---|---|
| `isSharedExpense`, `sharedWithName`, `myShareAmount`, `mySharePercentage` | No | absent → `effectiveAmount` recomputes to full `amount` (no share reduction) | Export carries the reduced `effectiveAmount` for audit, but reconstructing a share split without its inputs would fabricate data. |
| `isNotMine`, `ownerName` | No | `false` / null | Same as above. |
| `transferDirection`, `transferAccountName` | No | null | `TransactionType.TRANSFER` still maps exactly; direction is dropped. |
| Refund | No dedicated `TransactionType` | Sign carried inside `amount` verbatim (e.g. `-12.5` imports as `-12.5`) | No REFUND enum exists; amount sign is the only carrier. |
| `TransactionType.DEPOSIT` / `WITHDRAWAL` / `TRANSFER` / `UNKNOWN` | Yes (exact enum name) | Exact enum mapped; a row exported as `UNKNOWN` imports as `UNKNOWN` | Exact-enum contract; the lifecycle coordinator owns any downstream validation. |

## Failure contract (19-D, ImportCoordinator)

- Per-row failures surface through `ImportResult.errors` as `"Row <n>: <reason>"`
  (`n` = 0-based data-row index, header/comment/blank rows excluded — identical
  convention in CSV and JSON legs).
- Controlled codes: `UNKNOWN_TRANSACTION_TYPE` (unknown type value),
  `Unrecognized import format` (whole-content detection failure),
  `Import blocked: database maintenance in progress` (write barrier).
- `success` is true only when `errorCount == 0`; duplicates are `skippedCount`, never failures.

## Snapshot consistency (19-C decision)

The export stream **retains the current non-snapshot keyset contract**
(`DeterministicExpenseExportPager`, `(date,id)` cursor). `rowCount` in the
CSV metadata line / JSON header comes from a separate count query and may disagree
with the streamed row count under concurrent writes. This divergence is documented in
`ExportDataRepository` / `ExportOptionsViewModel` KDoc and pinned by
`ExportOptionsViewModelTest` (count=5 vs streamed=3 case). A frozen
`export_snapshot` table remains the future fix (P12-P1-04 / PR-SNAP) and is out of
RP-19 scope (no schema changes in this lane).
