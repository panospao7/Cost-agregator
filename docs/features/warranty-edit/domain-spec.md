# Domain spec: warranty-edit (UF-02) - tier T3

Source of truth: worktree `Cost-agregator-int` @ integration/ai-evolution-1a e6967db1. Path aliases (all under
`app/src/main/java/com/yourname/expensetracker/`): REPO=`data/repository/WarrantyTrackerRepository.kt`,
SCR=`ui/screens/warranty/WarrantyTrackerScreen.kt`, VM=`ui/screens/warranty/WarrantyTrackerViewModel.kt`,
UC=`domain/usecase/warranty/AutoCreateWarrantyFromReceiptUseCase.kt`, ENT=`data/database/entity/Warranty.kt`,
WRK=`service/warranty/WarrantyExpirationWorker.kt`. Policy = `config/guards/db_ownership_policy.yml` (frozen until 2026-12-01).
Status: REVISED, all decisions closed (2026-10-26). Owner answer "Accept recommendations" (`decision-sheet.md`), including the explicit yes on T-1, O-3 and O-5.
Only remaining gate: el/es copy sign-off before merge. Implementation stays on hold until "planning only" is lifted AND QF-2d (`workflows/active/uf-quick-fixes-plan.md` §6, A-5)
has landed, because both edit SCR (see §11). Tier T3. Companion docs: `decision-sheet.md`, `impact-map.md` (guard/test/worker facts folded into §3 and §11), `ux-flows.md` (26 canonical string keys, §8).
Numbering rule: INV/AE ids are stable; new ones are appended (INV-18..21, AE-19..22).

## 1. Evidence check (verified at e6967db1)

| Claim | Verdict | Fact |
|---|---|---|
| No edit UI, TODO in SCR | TRUE, line moved | KDoc+TODO at SCR:44-67 (not :55-66). It names `WarrantyTrackerRepository.update`, which does not exist. |
| Reusable `ManualWarrantyDialog` | PARTLY | `private` in SCR:405; fields = name, merchant, purchase date as `yyyy-MM-dd` TEXT, duration months, phone (SCR:429-464). No type, email, notes; no picker. Reusable only after extraction. |
| `updateWarranty` non-transactional, swallows event failure | TRUE | REPO:183-199: DAO update, then `try{insert UPDATED}catch(Exception){Timber.w}` (CE rethrown at :196). Siblings `addWarranty` :95-121, `markWarrantyAsClaimed` :245-266, `confirmWarranty` :285-315 are in-transaction, fail-closed (R29). |
| Callers are `confirmWarranty` + `AutoCreate...UseCase` | STALE | `updateWarranty` has ZERO production callers (grep of app/src/main). `confirmWarranty` calls `warrantyDao.updateWarranty` directly (REPO:301); UC calls `promoteReviewDraft` (UC:344). Only tests reference it (WarrantyTrackerRepositoryTest:543; negative `coVerify(exactly=0)` in UC tests/VM test:326). |
| End date = exclusive, reuse addManualWarranty arithmetic | TRUE, but DUPLICATED x3 | VM:181-185 `getEndOfDay(addMonths(startOfDay(p), m))` (`TimePeriodUtils.addMonths` is Calendar-based, TimePeriodUtils:1345); UC:439-450 and `domain/receipt/WarrantyTextExtractor.kt`:274-287 use `LocalDate.plusMonths` + `getEndOfDay`. UC KDoc:428-429 still says "Manual warranty entry (future UI)". |
| Provenance read-only | RULE, not yet enforced | Provenance = ENT:54-55,77-79 (+ id, createdAt, claimedAt). `promoteReviewDraft` already shows the pattern: content fields applied, rest from fresh row (REPO:352-367). |
| Type dropdown | NOT EXISTING | `WarrantyType` {MANUFACTURER, EXTENDED, STORE, THIRD_PARTY} (ENT:83-88); no UI field; `addManualWarranty` has no type parameter (VM:172-177), so manual rows are always MANUFACTURER. |

## 2. Entity model

`Warranty` (ENT:51-81), table `warranties`; FK receiptId->scanned_receipts SET_NULL, expenseId->expenses SET_NULL (ENT:27-42).
No money fields: "protected value" is a derived join on the linked expense (`WarrantyDao.kt`:118-132); edit changes only whether the
row is counted (status='ACTIVE' AND end > now), never an amount or currency. Mixed currency, FX, rounding: not applicable to this feature.

Editable candidates: productName, merchantName, purchaseDate (ms, start of local day), warrantyDurationMonths (Int), warrantyEndDate
(ms, EXCLUSIVE end = first instant after the last covered day; UI shows `end - 1 ms`, SCR:654), warrantyType, supportPhone, supportEmail, notes.
Provenance / system (never editable): id, receiptId, expenseId, warrantyDocumentUrl, autoDetected, extractionConfidence,
extractionSource, createdAt, claimedAt, status, needsReview (status/needsReview change only through the rules in §6).

Origins (all stay editable with the same field set; origin is preserved because provenance is read-only):

| Origin | Signature | Producer |
|---|---|---|
| Manual (current) | receiptId=null, autoDetected=false, extractionSource="manual", ACTIVE | VM:187-198 |
| Manual (legacy, pre-WRN-4) | receiptId -> MANUAL_PLACEHOLDER receipt; helper still exists REPO:626 | older installs |
| Auto high confidence (c>=0.70) | autoDetected=true, source "ocr", needsReview=false, ACTIVE, note "Auto-detected ... Coverage ends: <date>" | UC:211-237 |
| Auto review draft (0.40<=c<0.70) | PENDING_REVIEW, needsReview=true, placeholder names "... edit in review" (UC:283-286) | UC:288-310 |
| Promoted draft | needsReview=false, ACTIVE or EXPIRED | `promoteReviewDraft` REPO:339-372 via UC:155-175 |
| Expired unconfirmed | status=EXPIRED AND needsReview=true (derived by `WarrantyReviewState`, never persisted) | sweep REPO:453-483 |
| Other | `extractionSource` "email" appears only in a comment (ENT:79); `TRANSFERRED` has readers but no writer found; `warrantyDocumentUrl` always null | not producible today |

Status machine today (writer in brackets): ACTIVE -> EXPIRED [sweep `reconcileExpiredItems`; also `confirmWarranty` when end < now];
PENDING_REVIEW -> ACTIVE|EXPIRED [`confirmWarranty`/`promoteReviewDraft`]; ACTIVE -> CLAIMED [`markWarrantyAsClaimed`];
any -> deleted [`deleteWarranty`, `rejectAutoDetectedWarranty`]. EXPIRED -> ACTIVE: NO writer (confirm explicitly never revives, REPO:277-280;
pinned by WarrantyTrackerRepositoryTransactionTest:402). `isNoLongerInReview` (REPO:378-381) is the single in-review predicate.

Dependents of a warranty's dates/status:
- Reminders: NO per-warranty scheduling. WRK is one periodic worker that each run reconciles expiry then reads
  `getWarrantiesExpiringSoon(7|30)` (WRK:104,124,146; query = status 'ACTIVE' AND end in [today, today+N d), `WarrantyDao.kt`:19-23).
  Dedup row `warranty_reminder_deliveries` is keyed (warrantyId, windowDays, expiryDate=warrantyEndDate at seed time)
  (`WarrantyReminderDelivery.kt`:36-41; WRK:223-247). Notification text embeds productName/merchantName (WRK:135-161).
  `isClaimedDeliveryEligible` re-checks only `w.status='ACTIVE'` and CLAIMED, not that expiryDate still equals the end (`WarrantyReminderDeliveryDao.kt`:96-103).
- Lifecycle events: `warranty_lifecycle_events` (`WarrantyLifecycleEvent.kt`:16-25); existing UPDATED type, description "WARRANTY_UPDATED", metadata null.
- Receipt link: return windows are a separate entity derived from the receipt + merchant defaults; their `productName` is copied from the
  warranty at each `upsertReturnWindowForReceipt` (REPO:514-525,558). Receipt re-processing: UC `execute` dedups on receipt; only review rows are re-promotable (REPO:348).
- Analytics/UI: `getActiveWarrantyCount`, `getTotalProtectedValueAggregate`, expiring-soon count are one-shot reads (VM:104-129); the list is a Room Flow (VM:82) so cards refresh by themselves, stats do not.
- Backup: Room DB is snapshotted whole (WRK:59-60); `data/backup/BackupVerifier.kt` is the only warranty-aware backup file (not changed by this feature).

## 3. Owner and legal path

- Owner of the new write: `WarrantyTrackerRepository` (REPO), the documented "sole warranty writer" (CODEBASE_SEGMENTS.md:714; LEGAL_PATHS.md:1211).
  A coordinator is not warranted: the warranty segment has none, and confirm/claim/reject/promote are all repository transactions.
- New public API (no DAO writes of its own): `editWarranty(warrantyId: Long, input: WarrantyEditInput): EditWarrantyResult`.
  `WarrantyEditInput` = productName, merchantName, purchaseDate (any instant on the chosen local day), durationMonths, warrantyType, supportPhone?.
  It structurally has no provenance/status/review fields, so the ViewModel cannot build them (LEGAL_PATHS.md:1244; VM guard SourceScanning...GuardTest:787-815).
  `EditWarrantyResult` = Edited(id, status, leftReview) | NoChange | NotFound | NotEditable | Invalid(reason: WarrantyEditError). `WarrantyEditError` = bounded enum (see INV-4).
- DAO-write site (CLOSED, O-6 accepted): `editWarranty` contains ONLY DAO reads (`warrantyDao.getWarrantyById`); every write goes through the rewritten `updateWarranty(Warranty)`
  (REPO:183-199) and the unchanged `confirmWarranty`. A NEW DAO-writing method was rejected: it needs new policy entries and bumps the pinned count 432 = guard + test edit, blocked by the freeze.
  Consequence: the signature stays `updateWarranty(Warranty)`, so the UPDATED event keeps `metadata=null` (no changed-field list in v1).
- Guard facts (impact-map §1 + orchestrator verification; NO guard/baseline file is edited):
  1. `edit` is not a mutation verb (`scripts/verify_db_access_boundaries.py:171-190`, detection `.verb\w*(` at `:208-217`); DAO reads such as `getWarrantyById` are not mutations.
  2. Rewritten `updateWarranty(Warranty)`: `writeBarrier.checkWritesAllowed("WarrantyTrackerRepository.updateWarranty")` stays the FIRST statement, then
     `database.withTransaction { warrantyDao.updateWarranty(w); warrantyLifecycleEventDao.insert(UPDATED, "WARRANTY_UPDATED") }`, NO try/catch (R29 fail-closed), signature unchanged.
     Prior art: `rejectAutoDetectedWarranty` REPO:223-243 (barrier, `withTransaction`, fail-closed event). The policy rows `config/guards/db_ownership_policy.yml:3436-3466`
     (`updateWarranty` x `warrantyDao.updateWarranty` and x `warrantyLifecycleEventDao.insert`, `barrierMode: helper`) stay valid: a body rewrite that keeps the identity fields is invisible to the guard.
     The row's stale `reason:` text (:3461-3464, "cancellation-safe best-effort write") is left untouched -> F-1 (§14).
  3. `editWarranty` -> `updateWarranty` needs no new row (same shape as `promoteReviewDraft` -> `confirmWarranty`, REPO:369). The pinned count 432 (`DbGuardPolicyFixtureTest:1071-1087`) is unaffected
     as long as `editWarranty` never calls `warrantyDao.update*`/`insert`/`delete*` or `warrantyLifecycleEventDao.insert` itself (INV-19).
  4. Review rows, ORDER (INV-18, AE-9, AE-19): `editWarranty` calls `updateWarranty(candidate)` (with the recomputed status) BEFORE `confirmWarranty(id)`. `confirmWarranty` keeps EXPIRED when
     `current.status == EXPIRED || current.warrantyEndDate < now` (REPO:296), so the status must be written first for the nested confirm to read ACTIVE. The candidate keeps `needsReview` from the fresh row (true):
     `isNoLongerInReview` (REPO:378-381) is true for `!needsReview && status != PENDING_REVIEW`, so writing needsReview=false first would make the confirm return AlreadyConfirmed and lose the CONFIRMED event.
     The CONFIRMED event stays inside `confirmWarranty` (REPO:304-311): no new DAO call site. `confirmWarranty` and `promoteReviewDraft` are UNCHANGED (tests :388, :402, :472-490 stay valid).
  5. `WarrantyPromotionBypassGuardTest` exempts `WarrantyTrackerRepository.kt` (`OWNER_FILE`; `findViolations` returns empty for that file name), so `updateWarranty(candidate.copy(status = ...))` inside `editWarranty`
     is legal by design (its `Clean` fixture models edit-then-confirm). Still enforced: the use case never calls `updateWarranty`; no other production file promotes through it. The VM complies (never calls it).
  6. VM guard (`SourceScanningArchitectureGuardTest.kt:787-815`): VM source must not contain the literal `updateWarranty(` and no `copy(` assigning `status =` or `needsReview =`;
     a `Warranty(... warrantyType = ...)` constructor or `copy(warrantyType = ...)` is allowed (add flow type default).
  7. Optional private status helper used by `editWarranty`; the inline predicate in `confirmWarranty` (REPO:295-300) is left as is, or extracted with byte-identical behaviour (INV-9).
- Legal path to add to `docs/architecture/LEGAL_PATHS.md` (propose; orchestrator applies), in "Warranty Auto-Create / CONFIRM / EXPIRE" near :1222-1234:
  `USER EDIT: WarrantyTrackerViewModel.editWarranty(id, input) -> WarrantyTrackerRepository.editWarranty: barrier check first -> one transaction: fresh read -> validate -> NotFound|NotEditable|NoChange|Invalid (no write, no event) -> content update + status/review rule -> UPDATED (+ CONFIRMED when leaving review), fail-closed. Never moves a CLAIMED/TRANSFERRED row.`
  Add FORBIDDEN: "any warranty content write outside WarrantyTrackerRepository.editWarranty/promoteReviewDraft"; reword INV-11 "sole writer of any transition into EXPIRED" to
  "sweep, plus the confirm/edit status predicate" (it is already untrue for `confirmWarranty`, REPO:296-300).
- Fail-closed contract (same as R29 siblings): no try/catch around event writes; `CancellationException` is never caught or converted in REPO or VM
  (VM pattern: rethrow before generic catch, VM:257-263).

## 4. Decisions (ALL CLOSED 2026-10-26; owner answer "Accept recommendations", `decision-sheet.md`)

Each row is the accepted REC. Deferred (not open): O-1b (supportEmail, notes) and the O-10 convergence. No OPEN item remains except el/es copy sign-off (§11.8).

| ID | Closed decision | Normative in |
|---|---|---|
| O-1 | One editable set for EVERY origin: productName, merchantName, purchaseDate, durationMonths, warrantyType, supportPhone (WRN-19 list, SCR:53-58; FUTURE-WORK.md:279). Origin never gates fields; provenance stays read-only. **O-1b DEFERRED:** supportEmail and notes are not editable (the card never renders them, SCR:555-734; the auto-note's "Coverage ends" date goes stale on an end-date edit but is invisible, accepted). | INV-3, INV-4 |
| O-2 | Option A: editing a row in review (REPO:378 predicate) ALWAYS leaves review in the same transaction (Save = "Save and confirm"); a row not in review never gets needsReview=true. Reuses `confirmWarranty` (CONFIRMED event, past-end rule), no second copy. Option B (independent toggle) rejected. | INV-8, INV-12, INV-18, AE-8, AE-19 |
| O-3 | Material3 DatePicker (local day, system zone) + duration months 1..120; exclusive end by ONE extracted pure function `exclusiveEnd(purchaseDay, months) = getEndOfDay(addMonths(startOfDay(purchase), months))` (VM:181-185 verbatim). No free-form end-date field. Add and edit share ONE form (`ui/screens/warranty/WarrantyFormDialog.kt`), so the shipped "Add warranty" dialog (typed `yyyy-MM-dd`, SCR:468-471) gains the picker and the type field (accepted scope growth; = UX-7). | INV-1, INV-2, AE-1, AE-2 |
| O-4 | `ExposedDropdownMenu` over the 4 `WarrantyType` values in the shared form; add flow defaults to MANUFACTURER (`addManualWarranty` gains a defaulted `warrantyType` param). Strings are part of the 26 canonical keys (S-1). | INV-3 |
| O-5 | When the edit changes the end date (or leaves review): status = EXPIRED if `newEnd < now` else ACTIVE. EXPIRED -> ACTIVE is permitted ONLY because the user moved the end date into the future (fixes a mistyped date the sweep already expired). CLAIMED/TRANSFERRED never touched. LEGAL_PATHS "never revived" wording (REPO:277-280) is reworded (§3). | INV-9, INV-18, AE-9, AE-10 |
| O-6 | FIX IN SCOPE: rewrite `updateWarranty(Warranty)` as the single write primitive of `editWarranty` (barrier first, `withTransaction`, no try/catch, signature unchanged; guard facts in §3). Zero production callers, so no runtime regression surface. Test consequence: T-1 (§11.3). `deleteWarranty` (REPO:201-217) and the batch EXPIRED event (:463-476) are fail-open siblings, out of scope (F-1). | INV-5, INV-19, INV-20, AE-5, AE-21 |
| O-7 | No return-window sync. `editWarranty` writes only `warranties` + `warranty_lifecycle_events`. The return window's productName self-heals on the next `upsertReturnWindowForReceipt` (REPO:558,514-525); its purchase date comes from the receipt. | INV-13, AE-14 |
| O-8 | Last-writer-wins on the 6 content fields over a fresh in-transaction read (same contract as `promoteReviewDraft`, REPO:324-329); no `expectedUpdatedAt` (the sweep bumps updatedAt, false conflicts). status/needsReview/claimedAt always come from the fresh row. | INV-5, INV-3 |
| O-9 | Edit shown on ACTIVE, PENDING_REVIEW, EXPIRED (incl. expired-unconfirmed). CLAIMED/TRANSFERRED: no entry point; repository returns NotEditable. | INV-7, AE-7 |
| O-10 | Extract the VM arithmetic into the pure function used by `addManualWarranty` + `editWarranty`; add a parity test over UC/extractor outputs; do NOT edit UC/extractor (receipt-path files). **Convergence of the three copies DEFERRED** (F-1). | INV-1, AE-1 |
| O-11 | Limits (tunable constants, single owner = validator): names trimmed 1..120 chars, phone trimmed <= 40 (blank -> null), purchase day in [1970-01-01, today+365 d], months 1..120. | INV-4, AE-4 |
| T-1 | APPROVED (explicit owner yes): invert `WarrantyTrackerRepositoryTest.kt:820-837`; see §11.3. | INV-20, AE-21 |
| S-1 | The 26 keys in `ux-flows.md` §8 are canonical, EN+EL+ES together; el/es sign-off before merge. | §13 |
| UX-1..7, F-1 | Recorded in §13 and §14. | §13, §14 |

## 5. Invariants

| ID | Rule | Owner |
|---|---|---|
| INV-1 | Exclusive end = `exclusiveEnd(purchaseDay, months)` from one pure function; no inline month arithmetic in add/edit; parity table test vs UC/extractor implementations (month-end clamp, leap day, DST day). | new pure fn in `domain/usecase/warranty` |
| INV-2 | The end date is recomputed ONLY if the chosen local purchase day != local day of the stored purchaseDate (current zone) or months != stored; otherwise stored end and purchaseDate are preserved byte-for-byte (legacy/non-canonical rows and zone changes do not drift). | `editWarranty` |
| INV-3 | Provenance never changes: id, receiptId, expenseId, warrantyDocumentUrl, autoDetected, extractionConfidence, extractionSource, createdAt, claimedAt, supportEmail, notes are value-equal before/after. `WarrantyEditInput` has no such fields. | `editWarranty` |
| INV-4 | Validation has one owner: a pure validator used by BOTH the UI (button enabled) and the repository (enforcement). Failures return `Invalid(reason)` with no write, no event: PRODUCT_BLANK, MERCHANT_BLANK, NAME_TOO_LONG, PHONE_TOO_LONG, PURCHASE_DATE_OUT_OF_RANGE, DURATION_OUT_OF_RANGE (months < 1 or > 120). "End before start" is unreachable because months >= 1 is enforced. | validator (domain) |
| INV-5 | One `database.withTransaction`, barrier check first: fresh read -> validate -> content update -> status/review rule -> UPDATED (+ CONFIRMED). Any failure (incl. event insert) throws and rolls back everything. | `editWarranty` + `updateWarranty` |
| INV-6 | `CancellationException` is never caught/converted in REPO or VM; VM clears `mutatingWarrantyIds` in `finally`. | REPO, VM |
| INV-7 | Missing id -> NotFound; status CLAIMED/TRANSFERRED -> NotEditable; both without write or event. | `editWarranty` |
| INV-8 | Row in review (`isNoLongerInReview` false) is confirmed by the same call: needsReview=false, CONFIRMED event via `confirmWarranty` (no duplicate rule). Row not in review keeps needsReview=false. Never sets needsReview=true. | `editWarranty` -> `confirmWarranty` |
| INV-9 | Status after an end-date change or review exit = EXPIRED iff `newEnd < now` else ACTIVE (strict `<`, same as sweep, `WarrantyDao.kt`:70); same strict `<` boundary as the sweep and `confirmWarranty` (REPO:295-300), implemented as a private helper used by `editWarranty` (extracting it from `confirmWarranty` is optional and only with byte-identical behaviour; any semantics change there = stop-gate). Otherwise status is untouched. CLAIMED/TRANSFERRED never reach it (INV-7). For review rows the status is written before the nested confirm (INV-18). | REPO |
| INV-10 | If every editable field is value-equal after normalization and the row is not in review: NoChange, no write, no event, updatedAt unchanged. | `editWarranty` |
| INV-11 | Edit never touches WorkManager, NotificationService or `warranty_reminder_deliveries`. Reminders are re-derived by WRK from (status='ACTIVE', end). Changed end -> new dedup key -> fresh reminders; unchanged end -> existing SENT/FAILED rows keep deduping. | WRK (unchanged) |
| INV-12 | After a user edit the row is out of review, so `promoteReviewDraft` returns AlreadyConfirmed without invoking its edit lambda: receipt re-processing/auto-create never overwrites user edits. | REPO `isNoLongerInReview` |
| INV-13 | `editWarranty` writes only `warranties` and `warranty_lifecycle_events`; never receipts, expenses, return windows. | `editWarranty` |
| INV-14 | Privacy: no product/merchant/phone/note text, no dates, no exception messages in Timber, diagnostics or event rows. Allowed: result type, `WarrantyEditError` name, exception class simple name (pattern VM:219). Event description stays the constant "WARRANTY_UPDATED"/"WARRANTY_CONFIRMED". | REPO, VM |
| INV-15 | VM: ignores a second edit while the id is in `mutatingWarrantyIds` (S12-010 pattern, VM:245); on Edited/NoChange calls `loadStats()`; on exception calls `reportMutationFailure` (string resource only; write-barrier -> `UI_WRITE_BLOCKED_MESSAGE`, VM:215-228). | VM |
| INV-16 | VM source contains no `updateWarranty(` and no `copy(` assigning `status`/`needsReview` (SourceScanningArchitectureGuardTest:791-815, WarrantyPromotionBypassGuardTest). | VM |
| INV-17 | All "now" values from `TimeProvider`; day boundaries via `TimePeriodUtils` in the system zone at call time (G-TIME-01). | REPO, VM |
| INV-18 | Review-row ordering inside the one transaction: `updateWarranty(candidate)` (UPDATED; status per INV-9; `needsReview` kept TRUE from the fresh row) runs BEFORE the nested `confirmWarranty(id)` (CONFIRMED; sets needsReview=false). Reversed order breaks AE-9 (confirm keeps EXPIRED when `status == EXPIRED`, REPO:296); writing needsReview=false first makes confirm return AlreadyConfirmed (no CONFIRMED event). `confirmWarranty`/`promoteReviewDraft` are not modified. | `editWarranty` |
| INV-19 | `editWarranty` contains only DAO reads; it never calls `warrantyDao.update*`/`insert*`/`delete*` or `warrantyLifecycleEventDao.insert`. Every write is `updateWarranty(Warranty)` or `confirmWarranty`. No new policy row, pinned count 432 unchanged, no edit to `config/guards/*`; `WarrantyTrackerRepository.kt` is the `WarrantyPromotionBypassGuardTest` OWNER_FILE; VM stays free of `updateWarranty(` and `copy(status=/needsReview=)` (INV-16). | REPO |
| INV-20 | `updateWarranty(Warranty)` is fail-closed and transactional: barrier first, then `withTransaction { DAO update; UPDATED insert }`, no try/catch. An event-insert failure propagates and rolls back the DAO update; `CancellationException` is never caught. Signature unchanged; UPDATED metadata stays null. | `updateWarranty` (REPO) |
| INV-21 | While a card's id is in `mutatingWarrantyIds`, the card's Edit, Confirm, Reject, Claim and Delete actions are disabled (UX-6; Claim/Delete previously ignored the in-progress state). UI-only enablement; no VM behaviour change for Claim/Delete. | SCR |

## 6. State machine and failure behaviour

editWarranty(id, input), single transaction:
1. barrier check (blocked -> `DatabaseAccessBlockedException`, nothing read/written).
2. `warrantyDao.getWarrantyById(id)`: null -> NotFound.
3. CLAIMED|TRANSFERRED -> NotEditable.
4. validate(input, now) -> Invalid(reason).
5. build candidate = fresh + 6 content fields (+ recomputed end per INV-2); in-review = `isNoLongerInReview(fresh)` false.
6. not in review and candidate content == fresh content -> NoChange.
7. status: if end changed or the row is in review -> INV-9 recompute is put into the candidate; `needsReview` stays as in the fresh row; `updateWarranty(candidate.copy(updatedAt=now))` runs FIRST (writes UPDATED; in-transaction, fail-closed, INV-20).
8. in review -> THEN `confirmWarranty(id)` nested (joins txn; CONFIRMED). It reads the status step 7 just wrote, so its past-end rule (`status == EXPIRED || end < now`, REPO:296) keeps EXPIRED only when the (edited) end is past, and an EXPIRED+review row whose end moved to the future confirms as ACTIVE (AE-9). Reversing 7 and 8 is illegal (INV-18).
9. return Edited(id, status, leftReview).

Illegal transitions: any change to autoDetected/extraction*/receiptId/expenseId; EXPIRED -> ACTIVE without an end-date change to >= now; setting needsReview=true; touching CLAIMED/TRANSFERRED; two events when one user action; status or review writes from the VM.
Retry: user retry after failure re-reads fresh and re-applies; a repeat of an already applied edit returns NoChange (INV-10); a repeat on a confirmed row does not emit a second CONFIRMED (REPO:291).
Cancellation: coroutine cancelled mid-transaction -> Room rolls back, CE propagates, UI shows no error and unlocks the card.
Process death mid-save: atomic (all or nothing); unsaved dialog input is lost unless the UX agent stores the draft with `rememberSaveable` (bounded to the form fields, never logged).

## 7. Edge cases

- Month-end: Jan 31 + 1 month = Feb 28/29 clamp (Calendar and `LocalDate.plusMonths` agree; parity test required). Display date = `end - 1 ms` (SCR:654); UC note uses `end - 24h` (UC:417-418), which can show the wrong day by 1h on DST days (pre-existing, invisible, not fixed here).
- DST/time zone: stored instants are local midnight of the zone at write time. Opening the dialog in another zone can show a different day: INV-2 preserves stored values unless the user changes the day or months. DST day with 23/25 h: `getEndOfDay` is calendar-based, so the exclusive end is the next local midnight.
- Boundaries: end == now is not expired (`<`); purchase in the future allowed up to +365 d (pre-orders); months 120 from +365 d stays far below Long/Int limits; pre-1970 rejected (Gregorian-cutover branch in TimePeriodUtils avoided).
- Zero/negative/overflow duration: UI filters digits (SCR:453) but `toIntOrNull` yields null for overflow; repository still validates (INV-4).
- Edit vs confirm: serialized by Room; edit-then-confirm -> confirm gets AlreadyConfirmed (no second CONFIRMED); confirm-then-edit -> edit applies, only UPDATED.
- Edit vs sweep: sweep updates status by SQL; edit reads fresh inside the transaction, so a concurrent expiry is never undone except by the INV-9 rule on the edited end.
- Edit vs auto-create: `addWarrantyIgnoreConflicts` never touches existing rows; `promoteReviewDraft` only while in review (INV-12). Draft with receipt having several warranties (ENT:18-25, `getWarrantyByReceiptId` returns one `Warranty?`): pre-existing ambiguity, not changed here.
- Edit vs delete/reject/claim: NotFound / NotEditable; UI shows `warranty_error_not_found` (exists, strings.xml:1648) or a new NotEditable string.
- Reminder races: in-flight CLAIMED row for the OLD expiry can still send once after an edit (eligibility check ignores end date). Accepted v1 limitation, OUT OF SCOPE (hardening = add `w.warrantyEndDate = d.expiryDate` to the DAO query, which edits `db_raw_query_classification.yml` during the freeze) -> post-freeze follow-up F-1 (§14).
- Worker (closed): an edit needs NO rescheduling and no worker change. WRK reads dates on every run (`getWarrantiesExpiringSoon(7|30)`, WRK:124,146; query reads status='ACTIVE' + `warrantyEndDate`, `WarrantyDao.kt:19`), `deliverReminder` uses `warranty.warrantyEndDate` (WRK:223), the dedup key includes the expiry date (an edited end gets a fresh key), the periodic schedule is fixed (WRK:314). `WarrantyExpirationWorker.kt` is not in this feature's write set.
- Revival after edit: ACTIVE again -> next worker run seeds reminders (latency = worker interval, not instant).
- Edited auto-detected row still shows the auto badge and confidence % (provenance read-only, SCR:588-595,660-666); showing "edited" would need an event read or a schema flag (not recommended).
- Return window and warranty can diverge in name/purchase date (O-7).
- Empty states: edit is unreachable with no rows; after reject/delete of the edited row mid-dialog -> NotFound.
- Unavailable vs zero: protected value falls back to 0 when the aggregate fails (VM:112-120); unchanged and out of scope.
- Backup/restore during edit: barrier blocks writes (`R18b`), UI shows the maintenance message; a restored older DB simply has pre-edit data.

## 8. Persistence impact

Schema: NONE. No table/column/index change, no migration, no snapshot, no backfill, nothing destructive. Uses existing `warranties`, `warranty_lifecycle_events` (UPDATED/CONFIRMED types exist,
`WarrantyLifecycleEvent.kt`:27-38). If the owner picks any option that needs new state (e.g. an `isUserEdited` flag, changed-field metadata with a new signature, an `updatedBy` column), STOP: that is a schema/policy decision.

## 9. Privacy

Stored: only the 6 user-entered fields, same table as today. Exported/backed up: unchanged (whole-DB snapshot). Logged: bounded codes only (INV-14). Never in logs/diagnostics/events: product, merchant, phone, notes, dates, SQL/exception messages, stack traces.
Notification text already embeds product/merchant (WRK:135-161); unchanged, not logged. No network, no cloud/AI, no `PrivacyGate` capability involved. New UI strings are static resources.

## 10. Acceptance examples (synthetic; zone Europe/Athens; now = 2026-10-26 10:00 local; TimeProvider fixed)

- AE-1 (INV-1) Given purchase 2026-01-31, months 1 When exclusiveEnd Then 2026-03-01 00:00 local (display Feb 28). Given 2028-02-29 + 12 months Then 2029-03-01 00:00. Given 2026-03-28 + 1 month (DST starts 03-29) Then 2026-04-29 00:00 local. UC/extractor `calculateWarrantyEndDate` give identical values for the same table.
- AE-2 (INV-2) Given a stored end E0 that is NOT exclusiveEnd(purchase, months) and an edit changing only supportPhone Then end and purchaseDate unchanged. Given the picker day equals the stored day in a different current zone Then unchanged.
- AE-3 (INV-3) Given autoDetected row (confidence 0.82, source "ocr", receiptId=7, expenseId=3) When edited Then all provenance fields equal before.
- AE-4 (INV-4) months 0, -1, 121, Int.MAX+1 (as input string) -> Invalid(DURATION_OUT_OF_RANGE); purchase 2100-01-01 and 1969-12-31 -> PURCHASE_DATE_OUT_OF_RANGE; product "   " -> PRODUCT_BLANK; 121-char merchant -> NAME_TOO_LONG; each: zero writes, zero events, row unchanged.
- AE-5 (INV-5) Given the event insert throws When edit Then the exception propagates and the row equals its pre-edit state (pattern WarrantyTrackerRepositoryTransactionTest:522).
- AE-6 (INV-6) Given the DAO throws CancellationException Then it propagates, VM `mutationError` stays null, `mutatingWarrantyIds` is empty.
- AE-7 (INV-7) Given a CLAIMED row, edit -> NotEditable, no write/event. Given id 9999 -> NotFound. Given row deleted between dialog open and save -> NotFound and UI message `warranty_error_not_found`.
- AE-8 (INV-8/12) Given PENDING_REVIEW draft (receipt 11), edit name+months Then needsReview=false, status ACTIVE, events [UPDATED, CONFIRMED] in one transaction. When `createWarrantyForReview(11, extraction)` runs afterwards Then result AlreadyExists and content is the user's.
- AE-9 (INV-9/O-5) Given EXPIRED+needsReview row, end 2026-05-01, edit to purchase 2026-09-01, months 24 Then status ACTIVE, needsReview=false. Given ACTIVE row edited to end 2026-10-01 Then EXPIRED. Given end == now exactly Then not expired.
- AE-10 (INV-9) Given an EXPIRED row, edit that changes only the name (end unchanged, past) Then status stays EXPIRED.
- AE-11 (INV-10) Given a confirmed row and identical input (after trim) Then NoChange, updatedAt and event count unchanged.
- AE-12 (INV-11) Given SENT delivery (w, 7, E0), edit to E1 within 7 days Then the next worker run seeds and sends (w, 7, E1) and leaves the E0 row. Given an edit that keeps E0 Then no new row, no resend.
- AE-13 (INV-5) Given confirm and edit race: edit commits first -> confirm returns AlreadyConfirmed, exactly one CONFIRMED event total; confirm first -> edit leaves needsReview=false and writes only UPDATED.
- AE-14 (INV-13) After edit, `returnWindow` and receipt rows are byte-identical.
- AE-15 (INV-14) Given a failure during edit of "Pixel 9 / Contoso / +30 69..." Then captured Timber and diagnostics lines contain none of those strings or any exception message.
- AE-16 (INV-15) Given writes blocked by the barrier Then VM sets `UI_WRITE_BLOCKED_MESSAGE`, no write. Given a double tap Then one repository call. On Edited, `activeCount`/`expiringSoonCount` refresh.
- AE-17 (INV-16) Source scan of VM finds no `updateWarranty(` and no status/needsReview in `copy(`; `WarrantyPromotionBypassGuardTest` and the VM guard still pass.
- AE-18 (INV-17) With a TimeProvider fake set to a DST transition day, validator bounds and INV-9 use the fake clock, not the wall clock.
- AE-19 (INV-18/INV-9) Given PENDING_REVIEW row (needsReview=true, end 2026-05-01, past) When edited to purchase 2026-09-01, months 24 Then call order is `updateWarranty` (UPDATED; candidate status ACTIVE, needsReview still true) then `confirmWarranty` (CONFIRMED); final ACTIVE, needsReview=false, events [UPDATED, CONFIRMED]. Given the same row edited with the end still past Then final EXPIRED, needsReview=false, events [UPDATED, CONFIRMED]. Assert order with `coVerifyOrder`; a candidate written with needsReview=false first would yield AlreadyConfirmed and one event only (must not happen).
- AE-20 (INV-19) Given the `editWarranty` source Then it contains no `warrantyDao.update`/`insert`/`delete` and no `warrantyLifecycleEventDao.insert`; `DbGuardPolicyFixtureTest` (count 432), registered-guard `db_access` and `WarrantyPromotionBypassGuardTest` pass with `config/guards/*` byte-identical.
- AE-21 (INV-20/T-1) Given `warrantyLifecycleEventDao.insert` throws RuntimeException("Simulated DB failure") When `updateWarranty(w)` is called Then the failure propagates (message surfaces via `runCatching`), the DAO update was attempted inside `withTransaction` and is rolled back (real-rollback evidence per AE-5); a `CancellationException` from the DAO propagates unchanged. The inverted test replaces `WarrantyTrackerRepositoryTest:820-837`; the `deleteWarranty` swallow test is unchanged.
- AE-22 (INV-21) Given `mutatingWarrantyIds` contains w When the list renders Then Edit, Confirm, Reject, Claim and Delete of w's card are disabled and other cards are unaffected; after the mutation ends (success, failure or cancellation) they are enabled again.

## 11. Stop-gates and sequencing

1. SCHEMA: none expected; any new column/table/index -> stop, needs version bump + migration + snapshots + migration tests + human approval.
2. GUARDS/ALLOWLISTS: freeze until 2026-12-01. The accepted design needs NO guard edit (§3 guard facts): it reuses the `updateWarranty(Warranty)` identity (policy rows `db_ownership_policy.yml:3436-3466` stay valid), the pinned count 432 (DbGuardPolicyFixtureTest:1071-1087) is unaffected, and `WarrantyPromotionBypassGuardTest` exempts `WarrantyTrackerRepository.kt` (OWNER_FILE). STOP if implementation would need any edit to `config/guards/*` (including `db_ownership_policy.yml` and `db_raw_query_classification.yml`), baselines or the 432 count (e.g. `editWarranty` calling a DAO mutator directly). The reminder-race hardening also needs `db_raw_query_classification.yml` -> F-1, not this feature. Guard runs after the repository slice: registered-guard `db_access` and `db_artifact_sync`.
3. TESTS: no deletion/weakening, with exactly ONE approved exception, **T-1** (explicit owner yes, 2026-10-26, replaces the former O-6 condition): invert `WarrantyTrackerRepositoryTest.kt:820-837` (`lifecycleEventFailure_doesNotFailPrimaryTransaction`: event insert throws, `updateWarranty` is asserted not to throw) to "event-write failure propagates and rolls back", using the `:750-818` pattern (harness mocks `withTransaction` at :44-55; `runCatching` + assert the failure surfaces, as at :806-818; real rollback evidence = AE-5 pattern `WarrantyTrackerRepositoryTransactionTest:522`). The test is rewritten, NOT deleted; keep its method name unless a grep of `config/guards/*` shows no reference to it. Do NOT touch the `deleteWarranty` swallow test (fail-open sibling, F-1). Keep unchanged and compiling: `WarrantyTrackerRepositoryTest:532-552` (`updateWarranty_writesUpdatedEvent`), mocks in AutoCreate...UseCaseTest (:466,635,672,716), `WarrantyTrackerViewModelTest:326`, the `WarrantyPromotionBypassGuardTest` self-check (:92-113). Changing `confirmWarranty`/`promoteReviewDraft` semantics (tests :388,:402,:472-490) = stop.
4. PERMISSIONS: none (local DatePicker, no new manifest entries).
5. LEGAL_PATHS.md edit proposed in §3 (docs only); the QF plan's "no LEGAL_PATHS change" ban applies to that plan, not to this feature.
6. Receipt-path files (UC, extractor) are not edited here (O-10).
7. Same-file overlap with QF-2d: SCR (`AddWarranty` branch ~:329 and `onNavigateToScanReceipt` wiring per A-5; `showManualAddDialog` state at SCR:79,111,121,386-401). warranty-edit starts only after QF-2d lands; it re-anchors lines, reuses `showManualAddDialog`, and extracts the private dialog (SCR:405) into a shared form that QF-2d's add flow then uses.
8. New strings: en + el + es; el/es sign-off per project policy; MissingTranslation may only decrease; no `lint-baseline.xml` hand edits.
9. Review: reviewer-strict (money-free but lifecycle + privacy + Room); room-migration-guardian not needed unless schema changes.

## 12. User-visible states for the UX agent

Entry: Edit action on ACTIVE / PENDING_REVIEW / EXPIRED (incl. expired-unconfirmed) cards; none on CLAIMED/TRANSFERRED; placement must coexist with Confirm/Reject (review) and Claim/Delete (active) without truncation at large font scales.
Form: product, merchant, purchase-date picker, duration months, type dropdown, support phone; read-only provenance line (Manual / Auto-detected from receipt + confidence; "linked to a receipt"); live read-only "Coverage ends <end-1ms date>" preview; for review rows the primary button reads "Save and confirm" with a one-line explanation, for expired-unconfirmed the past-end consequence is shown.
States: pristine (Save disabled / NoChange), dirty, per-field validation errors (each `WarrantyEditError` -> own string), saving (spinner, inputs and card actions disabled via `mutatingWarrantyIds`), success (dialog closes, card re-derives badge/state; revive EXPIRED->ACTIVE and review exit should be visible), NoChange (silent close), failure (`warranty_mutation_failed` snackbar, dialog stays with input), write-blocked maintenance message, NotFound ("no longer exists", dialog closes), NotEditable (new string), discard-changes confirm on dirty cancel, rotation/process-death draft restore, TalkBack labels for picker/dropdown, Greek/Spanish layout at long labels.
Precedence: where this §12 list conflicts with §13 (accepted UX answers), §13 wins (notably the failure/write-blocked presentation).

## 13. Accepted UX decisions and strings (closed 2026-10-26)

- **UX-1 Container:** a scrollable `AlertDialog` (no full-screen route, no bottom sheet). Shared form in `ui/screens/warranty/WarrantyFormDialog.kt` (own `@OptIn(ExperimentalMaterial3Api::class)`), reusing `FormDateField` (`FormComponents.kt:134`) and `FormDropdown` (`:82`); DatePicker prior art `SubscriptionManagementScreen.kt:746,855,885-910`, UTC-midnight conversions `ui/util/UiTimeUtils.kt:64-90`.
- **UX-2 Errors:** while the dialog is open, failures (mutation failure, write-blocked maintenance message) show as an in-dialog banner and the dialog stays with its input; a snackbar is used only after the dialog has closed (e.g. NotFound/NotEditable close the dialog, then snackbar). This replaces the "snackbar, dialog stays" wording in §12 States.
- **UX-3 Success:** snackbar after close: "Warranty saved" / "Warranty saved and confirmed" (review row). NoChange keeps the silent close.
- **UX-4 Legacy placeholder-receipt rows:** the provenance line shows "Linked to a receipt" for pre-WRN-4 manual rows linked to a MANUAL_PLACEHOLDER receipt; accepted for v1.
- **UX-5 Preview wording:** the live coverage preview reuses the existing "Expires / Expired on" strings (no new preview key); the date shown is `end - 1 ms` (SCR:654).
- **UX-6 In-progress state:** Claim and Delete are disabled while the card id is in `mutatingWarrantyIds` (INV-21, AE-22). Adding product names to the Confirm/Reject content descriptions is a follow-up (F-1 item e).
- **UX-7 Add flow:** = O-3. The add flow moves onto the shared form (picker + type dropdown, type defaults to MANUFACTURER, end via `exclusiveEnd`).
- **S-1 Strings:** the 26 keys in `ux-flows.md` §8 are canonical and ship EN + EL + ES together in the UI slice (S4); the implementer drafts EL/ES, the owner signs off before merge (the ONLY remaining open item). MissingTranslation may only decrease; no `lint-baseline.xml` hand edits; strings are static resources (INV-14).

## 14. Follow-ups (F-1: recorded, NOT done in this feature; guard freeze until 2026-12-01 and scope)

- (a) Reminder-race hardening: add `w.warrantyEndDate = d.expiryDate` to the claimed-delivery eligibility query so an in-flight CLAIMED row for the OLD expiry cannot send after an edit. Edits `db_raw_query_classification.yml` -> after the freeze. Locate the query when planned: this spec cites `WarrantyReminderDeliveryDao.kt:96-103`, the impact map cites `WarrantyDao.kt:~113,128`.
- (b) Stale `reason:` text on the `updateWarranty` policy rows (`db_ownership_policy.yml:3461-3464`, "cancellation-safe best-effort write"); correct after the freeze.
- (c) Fail-open siblings: `deleteWarranty` (REPO:201-217) and the batch EXPIRED event (REPO:463-476), including their swallow tests (untouched here).
- (d) End-date convergence (O-10): move the UC copy (`AutoCreateWarrantyFromReceiptUseCase.kt`:439-450, stale KDoc :428-429) and `WarrantyTextExtractor.kt`:274-287 onto `exclusiveEnd`.
- (e) Product names in the Confirm/Reject content descriptions (UX-6).

Open items: none, except el/es copy sign-off before merge (S-1). Implementation hold: "planning only" not yet lifted; QF-2d must land first (§11.7).
