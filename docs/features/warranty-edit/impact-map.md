# Impact map: warranty-edit (UF-02)

**Provenance:** impact-mapper produced this map (read-only, Sonnet, `Cost-agregator-int` @ e6967db1). The first run hit an API error and returned nothing; this is the retry, which used 22 calls. The orchestrator persisted it on 2026-10-26 and appended the "Orchestrator verification" section.

**Inputs:** `domain-spec.md` and `ux-flows.md` (26 new keys; the form reuses `FormDateField` and `FormDropdown`).

**Path aliases:** REPO = `data/repository/WarrantyTrackerRepository.kt`, VM = `ui/screens/warranty/WarrantyTrackerViewModel.kt`, SCR = `ui/screens/warranty/WarrantyTrackerScreen.kt`.

**Headline:** a design with no guard edit exists. The only stop-gate that fires is the **O-6 test rewrite**: `WarrantyTrackerRepositoryTest.kt:820-837` pins the swallow behaviour of `updateWarranty`.

## 1. Guards
- **(a) `edit` is not a mutation verb.** See `scripts/verify_db_access_boundaries.py:171-190`; detection is `.verb\w*(` at `:208-217`. DAO reads such as `getWarrantyById` are not mutations either.
- **(b) The `updateWarranty` policy rows stay valid** (`config/guards/db_ownership_policy.yml:3436-3466`).
  - The rows are `updateWarranty` × `warrantyDao.updateWarranty` and `updateWarranty` × `warrantyLifecycleEventDao.insert`, both `barrierMode: helper`.
  - A body rewrite that keeps the identity fields (path, owner, kind, method, receiver, ordered parameter types, accessor, DAO FQCN and operation) is invisible to the guard.
  - Prior art: `rejectAutoDetectedWarranty` (REPO:223-243, rows `:3419-3435`) does the barrier first, then `withTransaction`, then a fail-closed event insert.
  - Keep `checkWritesAllowed("WarrantyTrackerRepository.updateWarranty")` as the first statement.
  - The row's `reason:` text at `:3461-3464` ("cancellation-safe best-effort write") becomes stale. Leave it, and record it as a follow-up for after the freeze.
- **(c) `editWarranty` → `updateWarranty` needs no new row.** Rows attach to DAO call sites, which is the same shape as `promoteReviewDraft` → `confirmWarranty` (`:369`).
  - `editWarranty` may contain only DAO **reads**, for example `getWarrantyById`.
  - It must never call `warrantyDao.update*` or `warrantyLifecycleEventDao.insert` itself.
- **(d) No new DAO call site.** The CONFIRMED event stays inside `confirmWarranty` (`:304-311`).
  - For a review row, `editWarranty` calls `updateWarranty` (UPDATED) first and then `confirmWarranty` (CONFIRMED).
  - **This order matters for AE-9.** `confirmWarranty` keeps the row EXPIRED if `status == EXPIRED || end < now` (`:296`). So the recomputed status must be written first; the nested confirm then reads ACTIVE.
  - `confirmWarranty` and `promoteReviewDraft` stay unchanged. Their tests are at `:388`, `:402` and `:472-490`.
- **(e) The pinned count 432 is unaffected** (`DbGuardPolicyFixtureTest:1071-1087`), as long as `editWarranty` never calls a DAO mutator directly.
- **(f) VM guard rules** (`SourceScanningArchitectureGuardTest.kt:787-815`):
  - The VM must not contain the literal `updateWarranty(`.
  - No VM `copy(...)` may set `status =` or `needsReview =`.
  - A `Warranty(... warrantyType = ...)` constructor call, or a `copy(warrantyType = …)`, is allowed.
- **`WarrantyPromotionBypassGuardTest`:** the mapper left this open. **The orchestrator closed it (below): not flagged.**

## 2. Tests
- **O-6 stop-gate:** `WarrantyTrackerRepositoryTest.kt:820-837` (`lifecycleEventFailure_doesNotFailPrimaryTransaction`) mocks `insert` to throw, calls `updateWarranty`, and asserts no throw.
  - It must be **inverted** to "propagates; rolled back". That is a rewrite, not a deletion.
  - The pattern already exists for `confirmWarranty` at `:750-818` (`:806-818`).
  - The harness mocks `withTransaction` at `:44-55`.
  - The similar swallow test for `deleteWarranty` is **not touched** (out of scope).
- **Unchanged:**
  - `:532-552` (`updateWarranty_writesUpdatedEvent`).
  - `WarrantyTrackerViewModelTest.kt:326`.
  - `AutoCreateWarrantyFromReceiptUseCaseTest` (`:466,635,672,716`).
  - `WarrantyPromotionBypassGuardTest` self-check (`:92-113`).
  - `WarrantyTrackerRepositoryTransactionTest.kt`, which is extended only (the AE-5 rollback pattern at `:522`).
- **Extended:**
  - `WarrantyTrackerRepositoryTest`
  - `WarrantyTrackerRepositoryTransactionTest`
  - `WarrantyTrackerViewModelTest`
  - `WarrantyTrackerViewModelWriteBlockedTest`
  - Optionally, add-only fixtures in `WarrantyPromotionBypassGuardTest` and `SourceScanningArchitectureGuardTest` (adding cases is not weakening).
- **New:**
  - `domain/usecase/warranty/WarrantyEndDateCalculatorTest.kt` (AE-1; INV-1 parity with UC and extractor).
  - `domain/usecase/warranty/WarrantyEditValidatorTest.kt` (AE-4).
  - `data/repository/WarrantyTrackerRepositoryEditTest.kt` (AE-2, 3, 5, 7-11, 13, 14, 18; AE-15 privacy may fold in here).
  - `ui/screens/warranty/WarrantyTrackerViewModelEditTest.kt` (AE-6, 16, 17).
  - `ui/screens/warranty/WarrantyEditFormStateTest.kt` (AE-18).
  - Optionally, `service/warranty/WarrantyExpirationWorkerEditRegressionTest.kt` (AE-12).

## 3. Worker
- **No rescheduling is needed** (INV-11).
  - `WarrantyExpirationWorker.kt:124,146` calls `getWarrantiesExpiringSoon(7|30)`. That DAO query (`WarrantyDao.kt:19`) reads `status='ACTIVE'` and `warrantyEndDate` on every run.
  - `deliverReminder` uses `warranty.warrantyEndDate` (`:223`). The dedup key includes the expiry date, so an edited end date gets a fresh key.
  - The periodic schedule is fixed (`:314`).
- **Reminder-race hardening is out of scope.** It would add `w.warrantyEndDate = d.expiryDate` to the DAO query at `WarrantyDao.kt:~113,128`, which means editing `db_raw_query_classification.yml` during the freeze. Recorded as a post-freeze follow-up.
- **`WarrantyExpirationWorker.kt` is not in this feature's write set.** test-suite-audit B1-4 and the app-lock plan (`:473`) list it, so there is no collision.

## 4. Files per layer (recommended design)
- **Domain (new, pure):**
  - `domain/usecase/warranty/WarrantyEndDate.kt`: `exclusiveEnd(purchaseDay, months)`, taken verbatim from VM:181-185.
  - `domain/usecase/warranty/WarrantyEditValidator.kt`: plus the `WarrantyEditError` enum.
  - `WarrantyEditInput.kt` and `EditWarrantyResult.kt`. Place them in the same package as the existing `ConfirmWarrantyResult` (the mapper didn't find it; resolve at S-pre).
- **Data:** EDIT REPO only.
  - `updateWarranty` at `:183-199` becomes `database.withTransaction { dao update; event insert }`, with the barrier first and no try/catch.
  - NEW `editWarranty` goes after `promoteReviewDraft` (`:372`), next to `isNoLongerInReview` (`:378`).
  - Optionally, a private status predicate used by edit only. Leave the inline predicate in `confirmWarranty` (`:295-300`) as it is, or extract it with identical behaviour.
  - No change to any DAO, entity or the schema.
- **UI:**
  - EDIT VM: NEW `editWarranty(id, input)` following the confirm pattern (`:243-265`: `mutatingWarrantyIds`, CE rethrow, `reportMutationFailure`, `loadStats()`). `addManualWarranty` (`:172-208`) gains a defaulted `warrantyType` and uses `exclusiveEnd`.
  - EDIT SCR: Edit entry on `WarrantyCard` (`:535`, call site `:345`); `showManualAddDialog` stays (`:79,111,121,386-401`).
  - NEW `ui/screens/warranty/WarrantyFormDialog.kt`: the shared form extracted from the private `ManualWarrantyDialog` (`:405`; text date at `:418,447,469`). A separate file reduces overlap with QF-2d.
  - Reuse `FormDateField` (`FormComponents.kt:134`) and `FormDropdown` (`:82`).
  - EDIT `res/values{,-el,-es}/strings.xml`: 26 keys × 3 locales.
- **Docs** (applied by the orchestrator): `LEGAL_PATHS.md` near `:1222-1234`, plus the INV-11 reword at `:1211,1244`. `CODEBASE_SEGMENTS.md:714` and `ENGINE_INTERACTION_MAP.md` only if wording changes.
- **The `addManualWarranty` signature change is a VM method**, not a policy row. `repository.addWarranty` is untouched.

## 5. Material3
- **DatePicker** is already used at `SubscriptionManagementScreen.kt:746,855,885-910`. `ui/util/UiTimeUtils.kt:64-90` has the UTC-midnight ↔ `LocalDate` conversions to reuse.
- **ExposedDropdownMenu** is used at `TaxConfigurationScreen.kt:177-194` and `SubscriptionManagementScreen.kt:785-833`.
- **`@OptIn(ExperimentalMaterial3Api::class)`** is already on SCR:68. A new file needs its own `@OptIn`.
- **No dependency change** (Compose BOM `2024.11.00`, `gradle/libs.versions.toml:10`).

## 6. Tier, guardians, validation, stop-gates
- **Tier: T3.** It touches a lifecycle persistence write path, changes a transaction to fail closed, carries privacy (INV-14) and is guard-adjacent. There is no schema change and no guard edit.
- **Guardians and review:** architecture-guardian (repository-only write path, LEGAL_PATHS, bypass guard) and privacy-security-guardian (bounded codes), then `reviewer-strict`. No room-migration-guardian, since there is no schema change.
- **FG guards:** FG-08, FG-10, FG-12, FG-13, FG-14, FG-16, FG-17 (DST, TimeProvider), FG-19, FG-20, FG-23. FG-11 is not involved, because the worker isn't touched.
- **Targeted filters:**
  - `*WarrantyTrackerRepository*`
  - `*WarrantyTrackerViewModel*`
  - `*WarrantyPromotionBypassGuardTest*`
  - `*SourceScanningArchitectureGuardTest*`
  - `*DbGuardPolicyFixtureTest*`
  - `*WarrantyEndDate*`
  - `*WarrantyEditValidator*`
  - `*AutoCreateWarrantyFromReceiptUseCaseTest*`
  - `*WarrantyExpirationWorkerTest*`
- **Guard runs:** `registered-guard` `db_access` and `db_artifact_sync` after the repository slice. `lint` once at the UI slice. Full `static-guards` at the candidate.
- **Stop-gates:**
  1. The O-6 rewrite.
  2. Any edit to `config/guards/*`, `db_raw_query_classification.yml` or the 432 count.
  3. The bypass-guard outcome (closed: not flagged).
  4. Any change to `confirmWarranty` or `promoteReviewDraft` semantics.
  5. Any schema flag.
  6. el/es sign-off.

## 7. Slice sketch
QF-2d must land first, because it edits the same SCR.
- **S1 Domain pure:** end date, validator, models, tests. T2.
- **S2 Repository:** the `updateWarranty` rewrite, `editWarranty` and the predicate, the O-6 test inversion, and the new repository and transaction tests. This is the T3 core.
- **S3 VM:** `editWarranty`, the `addManualWarranty` default plus `exclusiveEnd`, and VM tests.
- **S4 UI:** SCR, the new `WarrantyFormDialog.kt` and the strings in 3 locales. The add flow moves onto the shared form. Runs after S3 and QF-2d.
- **S5 Docs:** applied by the orchestrator.
- **Ordering:** S1 and S5 can run beside S2. S2 → S3 run in series.

## 8. Owner questions (mapper recommendations)
| Item | Recommendation |
|---|---|
| O-3 add-flow scope growth | Approve (one shared form). |
| O-5 EXPIRED → ACTIVE, only when the end date moves into the future | Approve. Needs a LEGAL_PATHS reword of "never revived" (REPO:277-280). |
| O-6 | Approve the rewrite and the test inversion. `updateWarranty` has no production callers. If declined, the feature slips past the freeze. |
| O-2 | A: Save on a review row = "Save and confirm". |
| O-1 / O-1b | Defer `supportEmail` and `notes`. |
| O-10 | Defer UC/extractor convergence. |
| Reminder-race hardening | Post-freeze. |

**Not verified by the mapper:**
- the content of `FormComponents.kt:82/134`;
- the package of `ConfirmWarrantyResult`;
- `WarrantyLifecycleEvent.kt:27-38`;
- the full body of `DbGuardPolicyFixtureTest:1071-1087`;
- `getWarrantiesExpiringSoon` at the repository level.

## Orchestrator verification (2026-10-26, read-only)
- **`WarrantyPromotionBypassGuardTest` is closed: not flagged.**
  - `findViolations` returns `emptyList()` when `fileName == "WarrantyTrackerRepository.kt"` (`OWNER_FILE`, in the companion object).
  - So an `editWarranty` in REPO that calls `updateWarranty(candidate.copy(status = …))` is exempt by design.
  - The test still requires that the use case does not call `updateWarranty` directly, and that no other production file promotes through it. The VM complies, because it never calls `updateWarranty`.
  - The test's own `Clean` fixture models exactly this edit-then-confirm pattern.
- **[git] Branch overlap:**
  - Loan 34b20715 adds only two `// spending-exempt: ASSET_VALUE` comments to `WarrantyDao.kt`, which is not in this write set.
  - The app-lock tip 1efdda7e has no warranty changes.
  - ai-fixes B1a/B1b are already in integration.
  - cloud-alias-hashing S2 (`CloudWarrantyExtractionService`) and test-suite-audit B1-4 (`WarrantyExpirationWorker`) touch files outside this write set.
