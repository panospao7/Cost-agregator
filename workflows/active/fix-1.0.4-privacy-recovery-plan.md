# Plan: fix-1.0.4-privacy-recovery

Written by feature-planner (read-only, Sonnet) on 2026-11-03 at 9c84f7f4; persisted and corrected by the orchestrator. Decisions are taken under owner item 116.

Paths are relative to `app/src/main/java/com/yourname/expensetracker/` unless they start with `docs/`. Evidence: `build/review-wt/build/full-review-2026-11/` (13b, 13c, 08-FINAL-REPORT section 6.1).

**Scope limit.** The other session owns money display (B1), neutralisation (B2), DR-01..08 (B3) and BL-04..11 (B4). This plan takes none of them and does not edit their lanes.

## Orchestrator corrections to the planner's report
- Only two files construct `MapView(`: `SpendingMapScreen.kt` and `LocationSearchPicker.kt`. `LocationCorrectionSheet.kt` needs no change.
- **Overlap the planner missed:** the money-display lane (03d69151, unmerged) edits `ui/screens/export/ExportOptionsViewModel.kt` and its test. S2's edit there must stay small; whichever lane merges second resolves it.
- J-03-1 is less severe than filed: the model file has been encrypted at rest since AIML-16 (`TransactionClassifier.kt:421-423`). The `RawStorageMode` bypass is still real.
- `rawNotificationStorageMode` defaults to `STORE_RAW` (`PrivacySettings.kt:17`), so D4 does not stop learning for default users.
- Checked against both other lanes' diffs: no other file in this plan is touched by them.

## Re-anchor (at 9c84f7f4)
| Finding | Status | Anchor | Evidence |
|---|---|---|---|
| S19-2 | STILL-PRESENT | `ui/screens/map/SpendingMapScreen.kt:556-570`; `ui/components/LocationSearchPicker.kt:446-458` | Both set `TileSourceFactory.MAPNIK` and call `onResume()`; no `setUseDataConnection`. |
| J-12-1 | STILL-PRESENT | `ui/screens/export/ExportOptionsScreen.kt:184`; `ExportOptionsViewModel.kt:273,305-321,363`; `data/repository/ExportDataRepository.kt:99-136`; `domain/privacy/ExportPrivacyGate.kt:41-42` | `encryptExport` stays false; `privacyBlocked` is never rendered; the sweep deletes only `.tmp_` files; `AccountingExportRepository.kt:161-166` also writes final files to `files/exports/`. |
| G-LOCK-1 | STILL-PRESENT | `ui/unsupported/UnsupportedDatabaseExportAuth.kt:88-95,128-138`; `docs/architecture/LEGAL_PATHS.md:733-749` | `decide()` never reads `lockMode`; `NO_CREDENTIAL` returns false even with the lock ON or UNREADABLE. |
| J-15-1 | LIKELY FIXED, no test | `domain/privacy/applock/AppLockSettingsOperations.kt:543-552,575-591`; `ui/screens/privacysettings/AppLockPrivacySettingsViewModel.kt:729-734,841`; `AppLockController.kt:736-738` | Merged app-lock work gives a route out, but no test pins corrupted + APP_PIN + NO_CREDENTIAL. |
| J-11-3 | CHANGED, LOW | `startup/AppStartupCoordinator.kt:529`; `ui/MainActivity.kt:433-449` | The user sees "Restart Required" with a working "Restart Now". Not stranded. |
| J-20-1 | STILL-PRESENT | `ui/MainActivity.kt:451-464` | `CriticalRecoveryRequired` has `onAction = { /* no-op */ }`; the mode persists across restarts. |
| N-05-3 | STILL-PRESENT (debug-gated) | `data/privacy/DefaultCloudPayloadPolicy.kt:115-120`; `DefaultCloudPayloadRedactor.kt:41-47,150`; `data/ai/provider/internal/CloudPiiSanitizer.kt:69,88-90`; `domain/ai/usecase/ValidateBankStatementTransactionsUseCase.kt:447-461` | The whole prompt is cut to 2000 characters, losing the trailing candidate block. |
| J-03-1 | STILL-PRESENT (see correction) | `domain/intelligence/TransactionClassifier.kt:168,423`; callers `data/repository/NotificationProcessingPipeline.kt:1668,1696`, `ReviewQueueRepository.kt:449,815` | `train(text)` has no privacy check. |
| J-11-4 rest | STILL-PRESENT | `data/privacy/ExportAnonymizer.kt:80-92,381-391,398-400` | `pending_reviews.explanation` stays; expenses, group members and mileage fields are untouched. |

## Decisions (orchestrator, owner item 116; all reversible)
- **D1 G-LOCK-1: fail closed (revised after review).** With the lock ON or UNREADABLE and no working device prompt, the export is Denied in every mode, with the existing constant message; the stored mode is not read. With a working device prompt it needs that prompt. Cost: see "Rescue export cost" below. G-LOCK-1 is PARTIAL: a PIN prompt on that screen is deferred (it needs an extra binding).
- **D2 J-20-1: S8 only.** A non-destructive "Close app" replaces the no-op. Export and reset would be a new legal path and a destructive action, so they are NOT done here.
- **D3 J-11-3: no code change.** The user has a working restart button.
- **D4 J-03-1: train only under `STORE_RAW`.** Read failure skips training. Learning continues for default users.
- **D5 J-12-1: age-based sweep** when the export screen is used, minimum age 1 hour.
- **D6 J-11-4 rest: only `pending_reviews.explanation`.** Blanking notes, owner names and group members would delete the user's own data on restore, because user backups are always redacted (J-11-1).
- **D7 S19-2: gate tiles on `EXTERNAL_GEOCODING`.** Visible effect: the map background is blank unless the user turns on external geocoding, which is off by default. Markers remain, with the existing privacy card. In the location picker, long-press pinning and its hint are off while tiles are off, so with the default setting only Advanced coordinates remain there (search is already blocked by the same gate).

## Slices

### Batch A
**Lane 1 (`Cost-agregator-rsv`, `feature/fix104-pr-lock`)**

**S3. G-LOCK-1. T3.** Write set: `ui/unsupported/UnsupportedDatabaseExportAuth.kt`, `ui/unsupported/UnsupportedDatabaseActivity.kt`, `docs/architecture/LEGAL_PATHS.md` (:733-749, :784-785).
- The decision reads the lock state only, never the stored mode. No new binding, no second EntryPoint, no PIN UI.
- Lock OFF is NotRequired. Lock ON or UNREADABLE: capability AVAILABLE, CHECK_FAILED or no answer is Required; anything else is Denied.
- Tri-state result (`Required`, `NotRequired`, `Denied`). `Denied` uses the existing auth-denied message. No `strings.xml` edit.
- Tests (separate test-author), extending the existing auth tests and parity test: every table row for every `LockMode`, including stored DEVICE_AUTH with a PIN verifier present (a marker-only store through a lock-on review save, on a real DataStore); APP_PIN with the lock OFF stays NotRequired and reads no capability; cancellation rethrown; no exception text or PIN in output; the entry point still exposes only its two bindings.

**S4. J-15-1. T2, test only.** Pins Unreadable(CORRUPTED) + effective APP_PIN + NO_CREDENTIAL: lock-on `IMMEDIATE` commits; lock-off returns `PIN_REQUIRED`; `requestReviewSaveWithPin` commits with the right PIN and writes nothing with a wrong one; the PIN `CharArray` is zeroed. If any case fails, stop: that is a real auth bug.

**Lane 2 (`Cost-agregator-sgd`, `feature/fix104-pr-map`)**

**S1. S19-2. T2.** Write set: new `ui/components/MapTileAccess*.kt`, `ui/screens/map/SpendingMapScreen.kt`, `ui/components/LocationSearchPicker.kt`. Do NOT edit `SpendingMapViewModel` (neutralisation lane).
- Holder exposes `tilesAllowed: StateFlow<Boolean>`, initially false, driven by `PrivacyGate.check(EXTERNAL_GEOCODING)`. Both maps call `setUseDataConnection(allowed)` in the `AndroidView` `update` block.
- When denied, show the existing `PrivacyBlockedCard` and `privacy_blocked_external_geocoding`. No `strings.xml` edit.
- Tests (separate test-author): allowed, denied, gate exception gives false, cancellation rethrown; a source scan requiring every `MapView(` file to call `setUseDataConnection`.

**Lane 3 (`Cost-agregator-we`, `feature/fix104-pr-export`)**

**S2. J-12-1. T2.** Write set: `data/repository/ExportDataRepository.kt`, `ui/screens/export/ExportOptionsViewModel.kt` (smallest possible edit), `ui/screens/export/ExportOptionsScreen.kt`.
- `sweepStaleExportFiles(nowMs, minAgeMs = 1h)` deletes regular files directly in `files/exports/` that start with `.tmp_`, `expenses_` or `accountant_report_`. Called from the VM `init` and, best effort, from the existing sweep in `generateExport`. Cancellation rethrown; other failures are best effort.
- Render `uiState.privacyBlocked` with `PrivacyBlockedCard`.
- Tests (separate test-author): deletes old, keeps fresh, keeps unrelated names and subdirectories, boundary age; VM `init` sweep and failure path; the blocked card renders.
- Deferred: encryption toggle and passphrase (needs `strings.xml`), app-start or retention-worker sweep.

### Batch B (after Batch A merges)
- **S5. J-03-1. T2.** Gate inside `TransactionClassifier.train`; inject `PrivacySettingsRepository`; train only under `STORE_RAW`; read failure skips; cancellation rethrown; bounded diagnostics. Model purge on mode change deferred.
- **S6. N-05-3. T2, debug-gated.** Redact and cap only the OCR segment, then assemble the prompt; each candidate field gets a bounded per-field sanitize. Tests: long OCR keeps all candidate lines; email and IBAN redacted in OCR and candidate merchant; candidate JSON parseable.
- **S7. J-11-4 subset. T2.** `sanitizePendingReviews` also nulls `explanation`, using SQL strings only. Tests: explanation nulled, rows and counts retained, no BackupVerifier mismatch.
- **S8. J-20-1 safe part. T3, UI only.** "Close app" (`finishAffinity`) replaces the no-op in `ui/MainActivity.kt`; honest copy using the literal-string style of its neighbours.

## Validation (validation-runner only, through the queue)
- Batch A: one combined `targeted-unit-test` (unsupported-database auth, app-lock review save, map tile holder and source scan, `ExportDataRepository*`, `ExportOptionsViewModel*`, export screen); `static-guards`.
- Batch B: one combined `targeted-unit-test` (`TransactionClassifierTest` and callers, `ValidateBankStatementTransactionsUseCase*`, `DefaultCloudPayload*`, `ExportAnonymizerTest`, backup verifier); `static-guards`.
- One `app-check` per candidate. No schema change, so no migration tests.
- Combined Sonnet guardian per slice; `reviewer-strict` at the candidate.

## Deferred, recorded
Export encryption toggle; CRITICAL-mode export and reset; PIN prompt on the unsupported-database screen; classifier model purge on mode change; J-11-4 rest; J-11-3 auto-restart; wording of the geocoding toggle to mention map tiles (`strings.xml`, after the money lane merges).

## Residuals found during Batch A
- `temp_export_<uuid>.db` (DatabaseBackupRepositoryImpl) is a plaintext temp in `files/exports/` that could outlive a process kill. S2 does not sweep it.
- G-LOCK-1 no longer reads the stored lock mode (revised D1), so its advisory nature no longer matters to this gate.
- osmdroid may still render already cached tiles when denied (unconfirmed).
- `hiltViewModel()` now runs inside the picker dialog and sheet. Not run yet; needs an emulator check.

## Status
| Slice | State |
|---|---|
| S3, S4 | validated at 7e35dd0c targeted; reviewer-strict ACCEPT-WITH-MINORS round 2; G-LOCK-1 PARTIAL |
| S1 | reviewer-strict ACCEPT-WITH-MINORS round 2 |
| S2 | reviewer-strict ACCEPT-WITH-MINORS round 2; J-12-1 PARTIAL (encryption deferred; files persist until the next screen visit) |
| S5, S6, S7, S8 | implemented and tested on lanes; reviewer-strict pending at the combined candidate |

## S6 note for reviewers (2026-11-03)
The cloud path is debug-gated. The fix restores the designed prompt, so more of the candidate block reaches the provider than before: lines that used to fall past the 2000-character cut, and exact epoch-millis dates that the sanitizer used to mangle into `[REDACTED_CARD]`. Guardian must judge whether an exact date is acceptable or should be coarsened to a day.

## S8 note (2026-11-03)
The existing copy tells the user to "contact support", and the app has no support channel in it. Kept for now; the wording and a real contact route are a follow-up. The critical-recovery state stays a dead end until export and reset are approved (D2).

## reviewer-strict REJECT on 013b78be, round 1 of 2 (2026-11-03)
- **MAJOR, reachable:** the gate trusted stored `lockMode`. A corrupted store holds only the marker, and review-save then writes the absent token as DEVICE_AUTH while the PIN verifier stays PRESENT. So PIN lock on + NO_CREDENTIAL gave NotRequired, through the path S4 pins. This corrects the earlier "torn or restored store" wording.
- **D1 revised (orchestrator, item 116):** lock ON or UNREADABLE with an inert capability is **Denied in every mode**; the stored mode is no longer read. No new binding.
  - Cost: a DEVICE_AUTH user who removed their screen lock must set one again to use the rescue export. On API 26-29 (UNSUPPORTED_API) a user with the lock on has no remedy on this screen until a newer app version opens the database; the denied message is misleading there (`strings.xml`, follow-up). No data is deleted.
- **MAJOR, not fixed here:** in PIN mode the device credential stands in for the App PIN, and on a phone with no screen lock anyone holding it can add one and pass. G-LOCK-1 is therefore **PARTIAL**. Closing it needs a PIN prompt on this screen, which needs an extra binding: separate item.
- Pre-existing, fixed here: `ExportOptionsViewModel.kt:530-537` swallowed CancellationException.
- Recorded, not fixed: J-15-1 is proven at Operations level only (the ViewModel route and `disablePin` are unpinned); `SpendingMapScreen` card wording and missing settings action (`strings.xml`); `sweepStaleTempFiles` has no production caller, kept because a merged test uses it.

## Rescue export cost (revised D1)
With the lock on or unreadable and no working device prompt, the export does not start. No data is deleted.
- NO_CREDENTIAL: setting a screen lock fixes it.
- HW_UNAVAILABLE, UNKNOWN: a screen lock does not help; the user can retry and a later attempt may succeed.
- UNSUPPORTED_API (API 26-29): no remedy on this screen until a newer app version opens the database. The denied message is misleading there (`strings.xml`, follow-up).
- It also reaches a user who never set a lock if the settings store is corrupted, because the lock then reads as unreadable.

## D4 router effect (reviewer-strict, 2026-11-03)
Default users (`STORE_RAW`) are unchanged. In any other storage mode the classifier's stats freeze, and a new user stays parser-only. Because callers train positives only, the old model scored near 1 and lifted parser confidence, so routing changes for those users: parser 0.40 at ML weight 0.2 used to reach 0.52 (review) and now auto-rejects; parser 0.70 at weight 0.6 used to reach 0.88 (auto-accept) and now goes to review. Amounts are untouched. This is accepted as part of D4.
