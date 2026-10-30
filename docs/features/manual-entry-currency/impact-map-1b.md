# Impact map: MEC increment 1b (ECB 90-day history)

impact-mapper (Sonnet), 2026-10-30, at aa397b95. The orchestrator gate is recorded in owner-approvals item 94.

## Touched
- **Currency data.** `data/repository/CurrencyRatesRepositoryImpl.kt` and `data/currency/ExchangeRateStoreAdapter.kt`.
- **Currency domain** (`domain/currency/`):
  - `CurrencyRatesRepository`
  - `CurrencyConverter.storeRates` (:644)
  - `UserPresentRatePreview` (:45)
  - `RateEligibilityEvaluator`
- **Privacy.** `domain/privacy/NetworkPrivacyGate.kt`.
- **UI:**
  - `AddExpenseViewModel`
  - `ReceiptScanViewModel`
  - `CurrencyManagementViewModel`
  - `ui/components/currency/CurrencyRateRegion.kt`
  - the two screens
  - strings in en, el and es
- **Legal paths.** The download chain exists today: repository -> converter -> store adapter -> DAO. 1b adds a method on that chain and no new path. LEGAL_PATHS gets a doc addition (domain-spec §1b.13).
- **Engines.** The evaluator and the preview do not change. Read-time analytics conversion picks up the new rows. No expense write path changes.

## Schema: NONE (verified)
`ExchangeRate.kt:25` has a unique index on (fromCurrency, toCurrency, validDate), and `ExchangeRateDao.kt:19,25` writes with REPLACE.

One point is unverified: whether a list `@Insert` runs as one Room transaction. The adapter does not wrap it in one. S1 must make the store all-or-nothing explicitly (INV-1b-11).

## Not touched
- Workers and permissions: none. No network-security-config exists, and no guard lists URLs.
- `G-DI-01` (`scripts/allowlists/di_release_allowlist.yml:24`) is keyed on path and symbol, so the new https URL does not affect it.
- `db_ownership_policy.legacy.gr08a/b.yml` already covers `ExchangeRateStoreAdapter.insertOrUpdateAll`.

## Test fakes that break
The `CurrencyRatesRepository` interface gains a method. The fakes that need updating are in:
- `AddExpenseViewModelTest`
- `ReceiptScanViewModelTest`
- `ReceiptScanViewModelMecC1Test`
- `CurrencyManagementViewModelTest`

`CurrencyRatesRepositoryImplTest` also needs extending.

## FG guards
- FG-16: privacy, PII and cloud.
- FG-10: cancellation (the Mutex and the timeout).
- FG-17: money and time (the date bound).
- FG-18: DI and release.
- FG-13: write barrier.
- FG-06: allowlists.
- FG-12: UI/DAO ownership.

## Risk tier: T3
Reasons:
- money;
- network privacy;
- a change to the repository contract;
- a new outcome type that crosses segments;
- a bulk write of about 39k rows.

Gates:
- one combined guardian per slice;
- a Sonnet writer and a separate test-author for money slices;
- reviewer-strict at the candidate;
- no migration guardian, because there is no schema change.

## Conflicts between domain-spec 1b and ux-flows 1b (resolved in owner-approvals 94)
- **Download vs Refresh (Q-2).** The domain rule wins: refresh first, and history only when the latest stored date is after the entry date.
- **F-B2-HOME-CHANGE.** The domain rule wins: the draft is discarded, never re-denominated or pinned.
- **Cancel.** The UX wins: the domain gains an explicit user Cancel and a CANCELLED outcome.
- **Currency management row (Q-1).** Deferred, so INV-1b-3 stays as written.
- **Reasons to UX states.** The mapping is fixed as D-1b-5.

## STOP conditions
- **VG-1b-1.** Confirm the URL, structure, size and day count on ECB's own page before S1 code. The caps in INV-1b-9 (512 KiB, 70 days, 100-day span) are sized from this check.

## Suggested slices
| Slice | Scope | Size |
|---|---|---|
| S0 | Docs reconciliation and VG-1b-1 | — |
| S1 | Data layer: interface, outcome type, history parser with caps and Mutex, `CurrencyConverter.storeRateHistory`, `RateRemedyPolicy`, `UserPresentRatePreview` `Blocked.latestStoredPairDate` | ~7 main files, 4 tests |
| S2 | Add-expense VM and UI: VM, sheet, `CurrencyRateRegion`, strings | ~6 files, 2 tests |
| S2b | Home-change rule in `AddExpenseViewModel` | ~2 files |
| S3 | Receipt-review parity | ~3 files, 2 tests |
| S4 | Docs | — |
