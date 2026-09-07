# RP-19 — Import/export correctness (Pipeline 12)

> **Scope class:** pipeline-isolated + one dependency (encryption envelope owned by RP-03 — this package does **not** touch `BackupEncryptionService`). **Mode:** standard.
> **Gated by:** RP-00/D3 (import product status — plan fixes the importer regardless; D3 decides UI promotion).
> **Files:** `util/CsvExpenseImporter.kt`, `util/ImportCoordinator.kt` (D4 deletion candidate), `ui/screens/export/ExportOptionsViewModel.kt`, `ui/screens/export/ExportOptionsScreen.kt` ⟂, `domain/export/CsvCellSanitizer.kt`, `AccountingExporters.kt`, `CurrencyFormatter.kt` ⟂ (domain/…), `data/repository/ExportDataRepository.kt`, `ExpenseExportMapper.kt`, `JsonExpenseImporter.kt` ⟂, tests: `CsvExpenseImporterTest`, `CsvExportImportRoundtripGoldenTest`, `ExportImportRoundtripTest`, `CsvEscapingTest`.
> **PR shape:** 3 PRs — (19a) CSV import robustness = P12-001, 002, 003, 004 + P12-010; (19b) export precision & state = P12-005, 006, 007, 009; (19c) tracked partials documentation/scope (P12-P1-02/03/05/06/07/08 — see final section).

---

## 19a — CSV import robustness

### P12-001 — Importer splits on `\n` before quote-aware parsing → multi-line fields corrupt (HIGH, contract violation; blast radius debug-only today)

**Problem.** The exporter correctly quotes fields containing `\n`/`\r` (`CsvCellSanitizer.kt:55-59`); the importer pre-splits `csvContent.lines()` (`CsvExpenseImporter.kt:55`) before its own `parseCsvLine` state machine (`:203-229`) sees them → the first fragment throws "unclosed quote" (row Failed) and the continuation fragment parses as a **spurious row** (can import garbage as an expense if it happens to have date/amount shape). Notes is a multi-line field by UI design (`AddExpenseSheet.kt:351-356`). Roundtrip tests never feed multi-line data back through the importer.

**Fix design.**
1. Replace `lines()`-then-parse with a **character-stream CSV reader**: a small state machine over the raw string (or `StringReader`) tracking in-quotes state across `\n`/`\r`, emitting complete logical records — `parseCsvLine`'s quote logic moves into (or is reused by) the record assembler. This is the RFC-4180-correct architecture; ~60 lines.
2. Post-parse hardening: a record whose first field doesn't match the header's expected first column type (date/id) and whose length ≠ header length → Failed with row context (kills the "garbage continuation" class even for foreign files).
3. Keep the per-row `RowResult` reporting contract (RP-19a also fixes P12-010's dropped detail — see below).

**What it solves.** Roundtrip fidelity for multi-line notes; no spurious-row imports.

**Guardrails.** The importer runs debug-only today (D3) — fix it to contract-correctness anyway (it's the app's own exporter's output). **Tests:** roundtrip golden extended — a fixture with `"line1\nline2"` notes and a CRLF inside quotes re-imports identically; a malformed unclosed-quote file → single row Failed, no spurious rows.

### P12-002 — UTF-8 BOM defeats header detection (MED)

**Problem.** `String.trim()` doesn't strip `\uFEFF`; an Excel "CSV UTF-8" re-save BOMs the first line → the `#` metadata comment fails `startsWith("#")` (`:60-63`) and becomes the header → every row fails "Missing date column". `ImportCoordinator.detectFormat` strips the BOM for detection only and passes the original through (`:39-44`).

**Fix design.** Strip the BOM once at ingestion: in `importFromContent`, `content = content.removePrefix("\uFEFF")` (before any processing; also handle a BOM after a leading empty line defensively). One line + comment. **Tests:** BOM'd file (with `#` comment) → full import succeeds.

### P12-003 — Locale-default date formatter (MED)

**Problem.** `DateTimeFormatter.ofPattern("yyyy-MM-dd")` (`:40`) uses the default FORMAT locale — non-ASCII-digit locales (several `ar`/`fa`/`mr`) fail **every** row; the exporter's own formatters are equally unpinned (`ExportOptionsViewModel.kt:396, :429, :506, :746`), so cross-device files break while same-device roundtrips self-consistently work.

**Fix design.** Pin `Locale.US` (or `Locale.ROOT` — US is the existing convention in `AccountingExporters`/`CurrencyFormatter`; use US): importer `ofPattern("yyyy-MM-dd", Locale.US)`; the four exporter formatters likewise. Also `.withZone(ZoneOffset.UTC)`-style discipline is *not* needed — dates are zone-less `LocalDate`; only digit rendering matters. **Tests:** formatter roundtrip under a forced Arabic-digit locale (`Locale.setDefault` in test) passes.

### P12-004 — Roundtrip drops `TransactionType`/`EffectiveAmount` (MED)

**Problem.** Importer reads only `amount` (`:131`) and hardcodes `TransactionType.PURCHASE` (`:172`) although the header exports both columns (`ExportOptionsViewModel.kt:770`) → a shared expense (25.50/22.50) or refund row re-imports as a full-amount PURCHASE. `JsonExpenseImporter` already reads both (`:56, :61`); the roundtrip test pins the lossy behavior (`ExportImportRoundtripTest:112-117, :139`).

**Fix design.**
1. Read `transactiontype` when the column exists (map via `TransactionType.valueOf` with a safe fallback + row-Failure on unknown values); prefer `effectiveamount` when non-blank **and** the type indicates shared/refund semantics — mirror `JsonExpenseImporter`'s precedence exactly (`optDouble("amount", optDouble("effectiveAmount"))` — note the JSON order is amount-first-with-fallback; replicate that order for CSV to keep the two importers consistent, and add a consistency test).
2. Shared-expense companion fields: the exporter emits `isSharedExpense`-derived columns? — check the 26-column header; import what exists, default the rest (the importer is lossy-by-design for fields never exported; only fix what **is** exported).
3. Re-pin `ExportImportRoundtripTest`: fixtures gain a shared row and a refund row; assertions updated to the lossless expectations.

**Guardrails.** Money rule: effectiveAmount semantics identical to the entity's computed field — import sets the entity fields it maps to, no recomputation.

### P12-010 — `ImportCoordinator` drops per-row detail (LOW, dead path)

**Problem.** `ImportCoordinator.kt:29` maps `perRowResults` to `emptyList()`; zero consumers exist (DebugScreen uses the importer directly).

**Fix design (fold into 19a):** if D4 approves deleting `ImportCoordinator` (recommended — dead, lossy), delete it here; otherwise map `perRowResults.filterIsInstance<Failed>()` into `errors` properly. Deletion preferred (one less divergent wrapper).

## 19b — Export precision & state

### P12-005 — Conversion rate formatted with `%.2f` in Xero/FreshBooks (MED)

**Problem.** `AccountingExporters.kt:113-119, :165-171` pass `conversionRateUsed` through `CurrencyFormatter.formatForExport` (`String.format(Locale.US, "%.2f")`) — a **money** formatter applied to a **rate**: 0.9123 → "0.91" (audit-lossy), <0.005 → "0.00" (destroyed). Generic CSV carries full precision (`Double.toString`, `:577`) — the same field disagrees across files.

**Fix design.** New `CurrencyFormatter.formatRateForExport(rate: Double): String` = `%.6f` trimmed of trailing zeros beyond 2 decimals (e.g. `0.912300` → `0.9123`; `6.0E-5`-class rates → `0.00006`) — deterministic, never scientific notation, never zero-clipped for real rates. Use it in both accounting exporters. **Guardrail:** accounting-importer compatibility — Xero/FreshBooks docs accept up to 6 decimals for FX rates; keep ≤6. **Tests:** 0.9123, 0.00006, 1.0, 123.45 fixtures; consistency test generic-CSV vs Xero value equality.

### P12-006 — Export cancellation not joined; same-ms filename collision (LOW)

**Problem.** `exportJob?.cancel()` without join (`ExportOptionsViewModel.kt:188-190`); the write loop has no `isActive` check (cancellation observed only at suspend points); `createExportFile(extension, timeProvider.now())` (`:250`) is ms-resolution — same-ms double-launch interleaves two writers on one `.tmp_` file. Button `enabled = !isLoading` narrows but doesn't close it (isLoading set inside the coroutine).

**Fix design.**
1. `exportJob?.cancelAndJoin()` inside a `runBlocking`-free pattern — i.e. make `startExport` suspend-aware: track the job; on new start, `previous?.cancelAndJoin()` *within* the launching coroutine (`viewModelScope.launch { previous?.cancelAndJoin(); ... }` — join happens before the new export begins, no UI block).
2. Filename uniqueness: append a monotonically increasing `exportSequence` (instance counter) to the temp/final name — collision-free regardless of timing.
3. Loop check: `ensureActive()` (or `isActive` guard) per page in the streaming loop — cancelled exports stop at page boundaries.

**Tests.** Cancel-mid-export fixture → no file written (or deleted), no zombie coroutine; sequence-numbered names differ.

### P12-007 — Empty-dataset guard dead (LOW)

**Problem.** `allowsEmptyDataset()` true for all five formats (`:892-895`) → the zero-rows guard (`:258`) can never fire; header-only file reported `exportSuccess = true`. (Repo helper has PDF=false — inverted-unused.)

**Fix design.** Make accounting formats honest: `xero/quickbooks(IIF)/freshbooks -> false` (their own policy rejects empty/single-currency sets anyway — alignment, not new behavior); keep `csv/json -> true` (an explicitly-empty archive export is legitimate) **but** add a UI warning banner when `expenseCount == 0` regardless of format ("0 rows will be exported"). **Tests:** guard fires for accounting formats; csv/json empty export succeeds with warning state.

### P12-009 — `exports/` never pruned; plaintext `.tmp_` orphans (LOW)

**Problem.** `filesDir/exports` created at three sites, never listed/pruned/swept; process death mid-write orphans plaintext `.tmp_expenses_*` financial files forever; finals accumulate unbounded.

**Fix design.**
1. Startup sweep (bounded): in `AppStartupCoordinator.initialize`'s existing fire-and-forget scope — delete `.tmp_*` files older than 24h (any newer might belong to an in-flight export in a restarted process — unlikely but cheap to respect); delete final exports older than 30 days. Constants next to the sweep; audit-free (file hygiene, not user data retention — but note: finals are user-created exports; deleting them is a policy call — **conservative default: sweep `.tmp_*` only**, leave finals untouched, surface "N old exports" in the export screen as a manual-clear affordance if trivial).
2. Wrap the sweep in `runCatchingCancellable` (RP-01 pattern).

**Tests.** Sweep fixture: old tmp deleted, recent tmp kept, finals untouched.

## 19c — Tracked partials: scope & documentation (no code beyond above)

These verified partials are **feature-completeness gaps**, documented here with disposition; none get speculative implementations in remediation:
- **P12-P0-01** (import debug-only): D3 decides promotion; until then the debug gating stands (verified `MainActivity:844-853`).
- **P12-P1-02** (validation not snapshot-tied): resolved *properly* only with P12-P1-04's `export_snapshot_rows` (out of remediation scope — data-loss-class feature); interim: the repo KDoc already disclaims snapshot semantics — keep.
- **P12-P1-03** (`conversionStatus` not exported): add the column when the field's producer semantics stabilize — needs `ConversionStatus` surfaced into `ExportTransaction` (small but semantics-bearing); **defer with TODO reference**, not silently.
- **P12-P1-05** (plaintext default + `EXPENSE_EXPORT` unconditionally Allowed): privacy posture decision — pair with RP-15's gate review; recommend flipping the gate to consult `EffectiveCloudAiPolicy`-style merged settings in a follow-up (flagged, not planned).
- **P12-P1-06** (rescoped): tags/attachments vacuous (no entities exist); real gap = **receiptLinks** (explicit TODO, `ExportTransaction:56-59`) — feature work, deferred.
- **P12-P1-07** (receipt provenance exported, no files): by-design for size/privacy; documented.
- **P12-P1-08** (business fields missing from accounting writers): small real fix — add the business columns to Xero/FreshBooks headers+mappers (IIF's fixed grammar may not accept them — Xero/FreshBooks only); **include in 19b** if trivial, else defer with TODO. Default: include (two headers + two writer lines each).

---

## Validation (when Gradle re-enables)
```
./gradlew :app:testDebugUnitTest --tests "*CsvExpenseImporter*" --tests "*CsvExportImport*" --tests "*ExportImport*" --tests "*CsvCellSanitizer*" --tests "*CsvEscaping*" --tests "*AccountingExport*" --tests "*ExportOptionsViewModel*" --tests "*CurrencyFormatter*"
```

## Sequencing & risk
- Rebase on RP-03 (encryption envelope untouched here, but `BackupEncryptionService`-adjacent code may shift). 19a is the correctness core; 19b precision/state; risk low — importer is debug-gated, exporters covered by existing golden tests that this PR extends rather than weakens.
