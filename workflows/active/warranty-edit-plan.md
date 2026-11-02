# Feature plan: warranty-edit (UF-02)

**Status: APPROVED (owner "Continue", plan gate 2026-10-26), NOT STARTABLE (planning only; S4 needs QF-2d).**
- **Source:** feature-planner wrote it (read-only, Sonnet). The first run hit an API error; this is the retry, at 14 calls. The orchestrator persisted it.
- **Inputs:** `docs/features/warranty-edit/{domain-spec,ux-flows,impact-map,decision-sheet}.md`. All decisions are closed, including T-1, O-3 and O-5.

## Overview
- **Risk tier:** T3. It touches a lifecycle write path, makes a fail-closed change and has a privacy surface. There is no schema change, no guard edit and no new permission.
- **Worktree and branch:** `Cost-agregator-we`, branch `feature/warranty-edit`.
  - One writer lane. The implementer and the test-author are separate agents with disjoint write sets.
  - Only validation-runner runs builds, tests or guards.
- **Base rule:**
  - S1–S3 touch no screen file, so they may start from integration e6967db1.
  - S4 must first rebase onto an integration tip that contains QF-2d (`workflows/active/uf-quick-fixes-plan.md` §6, A-5).
- **Aliases:**
  - M = `app/src/main/java/com/yourname/expensetracker/`
  - T = `app/src/test/java/com/yourname/expensetracker/`
  - REPO = `M/data/repository/WarrantyTrackerRepository.kt`
  - VM = `M/ui/screens/warranty/WarrantyTrackerViewModel.kt`
  - SCR = `M/ui/screens/warranty/WarrantyTrackerScreen.kt`
- **Per-slice flow:** implementer, then test-author, then the validation-runner batch, then guardians, then reviewer-strict where stated.
  - Max 2 review and 2 debug iterations per slice. After that, the slice is handed back as PARTIAL or BLOCKED.
- **Orchestrator check at S2, S3 and the candidate:** `git diff --stat -- config/` must be empty.

## S-pre (before S1; scout with Sonnet override; read-only apart from the runner calls)
### Verified while planning (Cost-agregator-int)
- **`FormComponents.kt` path:** it is `M/ui/components/feature/FormComponents.kt` (package `...ui.components.feature`).
- **`FormDropdown`** (`:82`): `(value: String, onValueSelected: (String)->Unit, label: String, options: List<String>, modifier, enabled=true)`.
  - It has no `isError`, no `supportingText` and no selected marker.
  - It is String-based, so the form must map options to `WarrantyType` **by index, never by label**.
- **`FormDateField`** (`:134`): `(dateMillis: Long, onDateSelected: (Long)->Unit, label: String, modifier, enabled=true)`. Only the trailing icon is clickable, and it uses `ZoneId.systemDefault()`.
- **`ConfirmWarrantyResult`** is nested: `WarrantyTrackerRepository.ConfirmWarrantyResult` at REPO:590. So `EditWarrantyResult` is nested next to it in REPO (S2), and S1 holds only pure types.

### Still to check
1. Re-anchor every line ref and note any drift:
   - REPO :183-199, :295-300, :369-381, :590
   - VM :172-208, :215-265
   - SCR
   - `WarrantyTrackerRepositoryTest.kt` :820-837 and :44-55
   - the guard test lines
   - `DbGuardPolicyFixtureTest` :1071-1087 (count 432)
   - `db_ownership_policy.yml` :3436-3466
2. Re-grep production callers of `updateWarranty(` in `app/src/main`, including the QF-2d tip. Expected: zero.
3. QF-2d write set: does it touch VM, REPO or any S1–S3 file? Is the QF plan still parked or approved?
4. `WarrantyLifecycleEvent.kt:27-38`: the event types and the constructor. Compare the `updateWarranty` body with the prior art in `rejectAutoDetectedWarranty` (REPO:223-243).
5. Visibility of the end-date functions at UC:439-450 and `WarrantyTextExtractor.kt:274-287`.
   - If they are private, the parity test uses their public outputs.
   - **Editing the receipt-path files is not allowed.**
6. Import-direction rules for:
   - `domain/usecase/warranty` → `data.database.entity` (`WarrantyType` in `WarrantyEditInput`);
   - `data.repository` → `domain/usecase/warranty`.
7. The VM error surface (`mutationError` / `reportMutationFailure`, VM:215-265) and how SCR consumes it. This reconciles INV-15 with UX-2 (the in-dialog banner).
8. Blast radius:
   - other callers of `FormDropdown` / `FormDateField`;
   - existing Compose or Robolectric tests for SCR;
   - any string or locale parity test;
   - that the 26 keys collide with nothing in values/values-el/values-es;
   - lint on the unused `confirm_discard_*` keys.
9. Grep `lifecycleEventFailure_doesNotFailPrimaryTransaction` in `config/` and in the hanging-test and allowlist files. Rename the test only if nothing references it.
10. The identity fields of the `updateWarranty` policy rows: receivers, ordered parameter types and DAO ops.
11. validation-runner `-Action Plan` / `-Action Profiles`:
    - the multi-filter grammar;
    - the `registered-guard` ids `db_access` and `db_artifact_sync`;
    - the `lint` and `static-guards` names;
    - the shard that covers warranty and repository tests.

## S1. Domain, pure code (T2)
- **INV/AE:** INV-1, INV-2 (pure date resolution), INV-4, INV-17 (the clock is a parameter). AE-1, AE-2 (the pure part), AE-4, AE-18.
- **Source**, all NEW under `M/domain/usecase/warranty/`:
  - `WarrantyEndDate.kt`:
    - `exclusiveEnd(purchaseDay, months) = getEndOfDay(addMonths(startOfDay(purchase), months))`, taken **verbatim** from VM:181-185. The system zone is read at call time.
    - Pure `resolveEditDates(storedPurchase, storedEnd, storedMonths, chosenPurchase, chosenMonths)` returns purchase, end and endChanged. It recomputes only when the local day or the months differ (INV-2).
  - `WarrantyEditInput.kt`: the six content fields. It has no provenance, status or review fields.
  - `WarrantyEditValidator.kt`:
    - The `WarrantyEditError` enum: PRODUCT_BLANK, MERCHANT_BLANK, NAME_TOO_LONG, PHONE_TOO_LONG, PURCHASE_DATE_OUT_OF_RANGE, DURATION_OUT_OF_RANGE.
    - `validate(input, nowMillis)`.
    - `normalized()`: trims, and turns a blank phone into null.
    - The O-11 constants live here and nowhere else.
- **Tests** (test-author), both NEW:
  - `T/domain/usecase/warranty/WarrantyEndDateCalculatorTest.kt`: the AE-1 table (Jan 31 + 1, 2028-02-29 + 12, DST 03-28 + 1), the AE-2 date part, and parity with the UC/extractor outputs.
  - `T/domain/usecase/warranty/WarrantyEditValidatorTest.kt`: AE-4 (including the string `Int.MAX+1`) and AE-18.
- **Gates:** architecture-guardian (placement and imports; the receipt-path files stay untouched).
- **Validation:** B1.
- **Stop:** a parity mismatch with UC/extractor, or a parity test that would need a UC/extractor edit. Either one means STOP and escalate (O-10).
- **Done:** both test classes pass in B1, and nothing outside the write set has changed.

## S2. Repository (the T3 core)
- **INV/AE:** INV-2, 3, 5-14, 17-20. AE-2, 3, 5, 7-11, 13, 14, 15, 18-21. AE-12 is optional.
- **Source:** EDIT REPO only.
  - **Rewrite `updateWarranty(Warranty)`** (:183-199). The order inside is:
    1. `writeBarrier.checkWritesAllowed("WarrantyTrackerRepository.updateWarranty")` stays the **first** statement.
    2. `database.withTransaction { warrantyDao.updateWarranty(w); warrantyLifecycleEventDao.insert(UPDATED, "WARRANTY_UPDATED") }`.
    - There is no try/catch. The signature is unchanged and the metadata stays null.
    - A CancellationException is never caught.
  - **NEW nested `sealed class EditWarrantyResult`**, next to `ConfirmWarrantyResult`: `Edited(id, status, leftReview) | NoChange | NotFound | NotEditable | Invalid(reason)`.
  - **NEW `suspend fun editWarranty(warrantyId: Long, input: WarrantyEditInput): EditWarrantyResult`**, placed after `promoteReviewDraft` (:372).
    1. The barrier comes first.
    2. Then one `withTransaction`:
       - `getWarrantyById`. A null result returns NotFound.
       - A CLAIMED or TRANSFERRED row returns NotEditable.
       - Validate. A failure returns Invalid.
       - Build the candidate: the fresh row, plus the six content fields, plus `resolveEditDates`.
       - If the row is not in review and nothing changed, return NoChange.
       - If the end date changed or the row is in review, set the status to EXPIRED when `newEnd < now`, otherwise ACTIVE. Use a private helper with no mutation-verb name, such as `resolveStatusAfterEdit`.
       - Call `updateWarranty(candidate.copy(updatedAt = now))` **first**, keeping `needsReview` from the fresh row.
       - **Then**, only for a review row, call the nested `confirmWarranty(id)`.
       - Return `Edited`.
  - **Spec-silent detail:** if the nested confirm returns anything other than Confirmed, throw with a constant message. That is a fail-closed rollback with nothing logged. It gets a test, and the reviewer confirms the choice.
  - `confirmWarranty` and `promoteReviewDraft` are **unchanged**, and the inline predicate at :295-300 is left as is.
- **Binding identity traps.** Missing any of these fails G or the 432 count.
  - `updateWarranty` keeps its name, its visibility, `suspend`, the parameter type `(Warranty)`, the receivers `warrantyDao` and `warrantyLifecycleEventDao`, the ops `updateWarranty` and `insert`, and the barrier-first shape. Don't move the DAO calls into a helper.
  - `editWarranty` contains only `warrantyDao.getWarrantyById` reads. It has no update, insert or delete call, no event insert, and no new DAO accessor.
  - The order is never reversed (INV-18).
  - Never write `needsReview=false` or `needsReview=true` in the candidate.
  - No edits under `config/guards/*`.
- **Tests** (test-author):
  - EDIT `T/data/repository/WarrantyTrackerRepositoryTest.kt`:
    - **T-1:** invert :820-837, so an event-insert failure now propagates. Use `runCatching` and assert failure, following :806-818.
    - Rename it to `..._propagatesAndRollsBack` only if S-pre item 9 found no reference.
    - Don't touch the `deleteWarranty` swallow test. Keep :532-552.
    - Add the AE-21 CancellationException propagation case.
  - EDIT `T/data/repository/WarrantyTrackerRepositoryTransactionTest.kt` (additions only):
    - a real-DB rollback for edit, including a CONFIRMED insert failure after UPDATED (the AE-5 pattern at :522);
    - the AE-21 real rollback;
    - an edit while the barrier blocks writes.
  - NEW `T/data/repository/WarrantyTrackerRepositoryEditTest.kt`:
    - AE-2, 3, 7-11, 13 and 14;
    - AE-15: no product, merchant, phone or message text in Timber;
    - AE-18;
    - AE-19 with `coVerifyOrder`;
    - AE-20: a source scan of the `editWarranty` body;
    - INV-12.
  - Optional NEW `T/service/warranty/WarrantyExpirationWorkerEditRegressionTest.kt` (AE-12). It must not touch the worker or its tests.
  - Optional: an add-only Clean fixture in `WarrantyPromotionBypassGuardTest`.
  - Unchanged:
    - the AutoCreate UC tests (:466, 635, 672, 716);
    - `WarrantyTrackerViewModelTest:326`;
    - the bypass-guard self-check (:92-113).
- **Gates:** architecture-guardian and privacy-security-guardian, then reviewer-strict.
- **Validation:** B1, then **checkpoint G**:
  - `registered-guard` `db_access`, then `db_artifact_sync`.
  - `DbGuardPolicyFixtureTest` (432) and `WarrantyPromotionBypassGuardTest` are in B1.
  - **If G fails, STOP and escalate. Fix the code, never the guard.**
- **Stop:**
  - a new DAO write site, policy row or count change would be needed;
  - confirm or promote semantics would change;
  - any test would be deleted or weakened beyond T-1;
  - any schema flag.
- **Done:**
  - B1 and G are green;
  - the `config/guards` diff is empty;
  - the T-1 test has been rewritten, not deleted;
  - reviewer-strict passes.

## S3. ViewModel
- **INV/AE:** INV-6, 14, 15, 16, and the add-flow part of INV-1. AE-6, 16, 17.
- **Source:** EDIT VM only.
  - **NEW `editWarranty(id, input)`**, following the confirm pattern at VM:243-265:
    - ignore the call if the id is already in `mutatingWarrantyIds`;
    - call `repository.editWarranty`;
    - rethrow CancellationException **before** the generic catch;
    - `finally` clears the id;
    - on Edited or NoChange, call `loadStats()`;
    - on an exception, call `reportMutationFailure` (a write-barrier block gives `UI_WRITE_BLOCKED_MESSAGE`);
    - log only the exception's simple class name.
  - **A bounded one-shot outcome:** `Saved(leftReview) | NoChange | NotFound | NotEditable | Invalid(reason)`. Its exact shape follows S-pre item 7.
  - **`addManualWarranty`** gains a defaulted `warrantyType = MANUFACTURER` and uses `exclusiveEnd`. Its output must be byte-identical to today's.
  - The VM source must contain no `updateWarranty(` literal and no `copy(` that assigns `status =` or `needsReview =`.
  - No SCR edit: the defaulted parameter keeps the existing call site compiling.
- **Tests** (test-author):
  - EDIT `T/ui/screens/warranty/WarrantyTrackerViewModelTest.kt`: an add-flow regression check (end date unchanged, default type).
  - EDIT `WarrantyTrackerViewModelWriteBlockedTest.kt`: an edit while writes are blocked.
  - NEW `T/ui/screens/warranty/WarrantyTrackerViewModelEditTest.kt`:
    - AE-6: a CancellationException leaves `mutationError` null and the id set empty;
    - AE-16: a double tap makes one call and refreshes the stats;
    - AE-17: a source scan.
- **Depends on:** S2. It can run from e6967db1.
- **Gates:** architecture-guardian and privacy-security-guardian.
- **Validation:** B2.
- **Stop:**
  - the VM would need `updateWarranty(` or `copy(status=/needsReview=)` to compile;
  - the `addManualWarranty` result changes;
  - the bypass guard or SourceScanning fails. **Fix the VM, never the guard.**
- **Done:** B2 is green, and the existing VM tests are unchanged and pass.

## S4. UI (after S3, and after the rebase onto the QF-2d tip)
- **INV/AE:** INV-21, INV-4, INV-2 (the preview shows the stored end when the day and months are unchanged), INV-14, INV-15. AE-22, AE-18, AE-7.
- **Source:**
  - **NEW `M/ui/screens/warranty/WarrantyFormDialog.kt`**, with its own `@OptIn(ExperimentalMaterial3Api::class)`. It is a scrollable AlertDialog shared by add and edit. It has:
    - a `FormDateField` picker;
    - a `FormDropdown` type field, mapped by index;
    - a provenance line;
    - a live preview using `exclusiveEnd`;
    - an outcome line;
    - the "Save and confirm" label, taken from `reviewStateOf`;
    - an in-dialog banner;
    - a discard confirm;
    - a `rememberSaveable` draft saver (the six fields, the id and the discard flag).
  - **NEW `M/ui/screens/warranty/WarrantyEditFormState.kt`**, pure:
    - the dirty flag and the validator wiring;
    - the preview and the outcome-line derivation;
    - the field-error map;
    - a card-action enablement helper for INV-21.
  - **EDIT SCR:**
    - The Edit entry on `WarrantyCard`, by card state:
      - review cards: Confirm, Edit, Reject;
      - ACTIVE cards: the `Row` becomes a `FlowRow`;
      - confirmed EXPIRED cards: a TextButton;
      - CLAIMED and TRANSFERRED cards: none.
    - Remove the private `ManualWarrantyDialog` and the stale KDoc/TODO (SCR:44-67).
    - Make `showManualAddDialog` a `rememberSaveable` and wire it to the shared form.
    - Disable Edit, Confirm, Reject, Claim and Delete while the card's id is mutating.
    - Show a snackbar on close and the banner while the dialog is open.
  - **EDIT `M/ui/components/feature/FormComponents.kt`**:
    - **additive defaulted parameters only**: `isError`, `supportingText`, `selectableDates`, a whole-field click target and a selected marker;
    - every existing caller must compile and behave exactly as before;
    - if that turns out invasive, use local wrappers in `WarrantyFormDialog.kt` instead.
  - **EDIT `res/values{,-el,-es}/strings.xml`:** the 26 keys from ux-flows §8, in all three locales together. No baseline edits. MissingTranslation may only go down.
- **Tests** (test-author):
  - NEW `T/ui/screens/warranty/WarrantyEditFormStateTest.kt`:
    - dirty and pristine states;
    - AE-18;
    - the preview and the outcome lines;
    - the error map;
    - the INV-21 helper (AE-22).
  - Extend a string parity test, if one exists.
  - Add a Compose test only if SCR already has Compose test infra.
- **Depends on:** S3 and QF-2d. Rebase first.
- **Gates:** architecture-guardian and privacy-security-guardian (no logging of the draft; saved-state bundle only).
- **Validation:** B3, then `lint` once.
- **Stop:**
  - a QF-2d rebase conflict that changes QF-2d's behaviour;
  - the baseline would grow;
  - a locale is missing a string;
  - another screen's behaviour changes through `FormComponents.kt`.
- **Done:**
  - B3 and lint are green;
  - all 26 keys exist in all three locales;
  - el/es is flagged for owner sign-off (a merge gate).

## S5. Docs (orchestrator)
- **`docs/architecture/LEGAL_PATHS.md`:**
  - add the USER EDIT path near :1222-1234;
  - add FORBIDDEN: "any warranty content write outside `WarrantyTrackerRepository.editWarranty/promoteReviewDraft`";
  - reword INV-11 at :1211/:1244 to "sweep, plus the confirm/edit status predicate";
  - reword "never revived" for the O-5 exception.
- **Other docs:**
  - `CODEBASE_SEGMENTS.md:714` and `ENGINE_INTERACTION_MAP.md` only if the wording changes;
  - mark WRN-19 in `FUTURE-WORK.md:279` if S-pre confirms it.
- **When:** after S2 passes G, and before the candidate.
- **Expect merge conflicts:** the main checkout has uncommitted edits to the same docs.

## Validation (validation-runner only, serial)
| Batch | Profile | Filters |
|---|---|---|
| B1 (S1+S2) | `targeted-unit-test` | `*WarrantyEndDate*`, `*WarrantyEditValidator*`, `*WarrantyTrackerRepository*`, `*WarrantyPromotionBypassGuardTest*`, `*SourceScanningArchitectureGuardTest*`, `*DbGuardPolicyFixtureTest*`, `*AutoCreateWarrantyFromReceiptUseCaseTest*` |
| G (after B1) | `registered-guard` ×2 | `db_access`, `db_artifact_sync` |
| B2 (S3) | `targeted-unit-test` | `*WarrantyTrackerViewModel*`, `*SourceScanningArchitectureGuardTest*`, `*WarrantyPromotionBypassGuardTest*` |
| B3 (S4) | `targeted-unit-test`, then `lint` | `*WarrantyEditFormState*`, `*WarrantyTrackerViewModel*`, plus any parity test; then lint once |
| Candidate | targeted union, shard, `static-guards` | All of the above plus `*WarrantyExpirationWorkerTest*` (and `*WarrantyExpirationWorkerEditRegressionTest*` if it exists); the shard from `-Action Profiles`; full `static-guards` |

- The grammar and profile names come from S-pre item 11.
- No full `unit-tests` or `app-check` unless a gate demands it.
- **Candidate order:** validation PASS, then the guardians, then reviewer-strict (Opus; discovery and acceptance are different reviewers).
- RUNNING is not a failure, and missing or stale results are never a PASS.
- **FG guards in scope:** FG-08, 10, 12, 13, 14, 16, 17, 19, 20, 23. FG-11 is out of scope because the worker isn't touched.

## Gates summary
- **S1:** architecture-guardian.
- **S2:** architecture-guardian and privacy-security-guardian, then reviewer-strict.
- **S3 and S4:** architecture-guardian and privacy-security-guardian.
- **Candidate:** reviewer-strict.
- **No room-migration-guardian anywhere,** because there is no schema change.

## Ordering notes
- **test-suite-audit B1-4** edits `WarrantyExpirationWorker.kt`, which isn't ours. Our worker test is a new file only. Rebase if shared fixtures change.
- **cloud-alias-hashing S2** (`CloudWarrantyExtractionService`) isn't ours either. Re-run `*AutoCreateWarrantyFromReceiptUseCaseTest*` after any rebase onto it.
- **Loan 34b20715** only adds comments to `WarrantyDao.kt`, and the app-lock tip has no warranty changes. Neither collides.
- **QF-2d (SCR)** is the only same-file overlap. It gates S4 only.
- **The guard freeze until 2026-12-01** applies to every slice.

## Human decisions remaining
- **Design:** none; all decisions are closed. The only open sign-off is the el/es copy, before merge.
- **Start gates:** "planning only" must be lifted. For S4, the QF plan must also be approved and QF-2d landed.
- **Stop and ask during work:**
  - a schema flag;
  - any `config/guards/*` edit or a change to the 432 count;
  - a change to confirm or promote semantics;
  - a parity mismatch that would need a receipt-path edit;
  - any test deleted or weakened beyond T-1.

## Deferred (spec §14, F-1)
- **Reminder-race hardening:** post-freeze.
- **The stale `reason:` text on the policy rows:** post-freeze.
- **The fail-open siblings** `deleteWarranty` and batch EXPIRED, together with their swallow tests.
- **Converging the end-date code** in the use case and the extractor.
- **Product names in the Confirm/Reject content descriptions.**
- **O-1b** (supportEmail and notes), changed-field event metadata, and return-window sync.

## Top open risks
1. **Guard identity.** Rewriting `updateWarranty`, or any DAO write in `editWarranty`, flips `db_access` or the 432 count. G is the tripwire, and the response is STOP.
2. **A mocked `withTransaction` hides rollback and nesting.** Real-DB tests are mandatory, including "UPDATED written, then CONFIRMED fails, both rolled back".
3. **QF-2d dependency.** S4 is blocked until QF-2d lands, and the rebase may conflict in SCR.
4. **The shared `FormComponents.kt` blast radius.** Use additive defaults or local wrappers only, and check every other caller.
5. **Date traps:** month-end clamping, DST and zone drift. The parity test may only reach public outputs.
6. **UX-2 vs INV-15.** The in-dialog banner must be reconciled without adding a second VM error channel.
7. **Fail-closed `updateWarranty`.** Zero callers was verified at e6967db1 only. Re-grep at S-pre and at the candidate.
8. **The INV-18 ordering.** A candidate with `needsReview=false`, or a swapped call order, silently loses the CONFIRMED event.

## Exact first action when unblocked
1. Confirm that "planning only" has been lifted.
2. Create the worktree:
   ```
   git -C C:\Users\panos\Desktop\Cost-agregator-int worktree add -b feature/warranty-edit C:\Users\panos\Desktop\Cost-agregator-we e6967db1
   ```
3. Dispatch the S-pre scout (Sonnet override), and ask validation-runner for `-Action Plan` / `-Action Profiles`.
4. Start the S1 implementer only once S-pre items 1-6 and 11 are answered.

## Re-anchor amendment (2026-11-02, orchestrator)
The owner lifted "planning only" on 2026-11-02 (owner-approvals 101).
- **Base:** `feature/uf-quick-fixes` @ c8149f25 (a5d659de plus QF Batch A). S1–S3 can start. S4 waits for QF-2d, which is blocked until the fix-1.0.4 lane merges; then merge the QF tip in before S4.
- **Re-anchor (explorer at a5d659de):** report in `docs/features/warranty-edit/reanchor-20261102.md`.
  - All REPO, VM, SCR and FormComponents anchors hold.
  - `DbGuardPolicyFixtureTest` is in the `guard/` package. The count is **454**, not 432 (comment :1071-1097). fix-1.0.4 makes it 456, so S2/G pins the then-current count, never a literal from this plan.
  - The `updateWarranty` policy rows are at `db_ownership_policy.yml:3436-3466`.
  - `updateWarranty(` has zero production callers.
  - E-05-1 (protectedValue drops currency) is in the same files on different lines; NOT folded in.
- **es/el:** sign-off is deferred to the release gate (owner-approvals 103).
