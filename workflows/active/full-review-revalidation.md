# Revalidation of the external full review (opencode, pinned e4376a77) at mainline 80251ea3

The owner does not fully trust the review. Each batch was checked by a fresh release-finding-verifier (Sonnet) reading source only in the clean worktree `Cost-agregator-rv`. Nothing was run.

## R2: barrier, lifecycle, backup
| ID | Verdict at 80251ea3 | Severity | Action |
|---|---|---|---|
| I-01-1 (RA06-F5) | Guard-proof gap only. There is no distinct reachable bypass: GroupTransactionCoordinator calls `checkWritesAllowed` before every `withTransaction`, and nested `runWrite` is correct. The only demonstrated hole (RestoreJournalImporter) is FIXED in 4466db75/80251ea3. | HIGH→MEDIUM tooling debt; runtime P3 | Parked for the owner (section G, item144-scoping) |
| ARCH-E04-NEW-1 | Wrongly merged into I-01-1. It is really a dual split calculation (`SharedExpenseManager.computeMyShareAmount` vs the coordinator, 05l:106). | unverified | Verify in a follow-up |
| I-01-2 | REFUTED. No lease expiry, the drain is monotonic, `runWrite` counts before the mode check, and the drain then runs `awaitNoInFlightWrites`, failing closed. | none | none |
| P-07-3 | REFUTED as written. Every abort path calls `cleanupRestoreStaging`, the swap has a journal and a rollback. | none | Optional test: inject an IO failure at the staged→live copy |
| P-02-3 | Confirmed, but only for audit-only CREATE_ATTEMPTED/VALIDATION_FAILED inserts before `runWrite` (TLC :631, :655, :685). The expense insert is counted. | MEDIUM→LOW | Deferred |
| RA06-F4 (J-20-1) | Confirmed. The fix is in lane rexp (not merged). | MEDIUM | Merge rexp |

## R4 + R5: guards and analytics
| ID | Verdict at 80251ea3 | Severity | Action |
|---|---|---|---|
| I-04-1 | REFUTED as worded. The suite is fail-closed (anything not 0/1 is infra_error, and `determine_exit_code` returns 2/1). The real residual is ours: there is no FG-23 self-protection, so editing a guard and its baseline together passes. Also I-04-9: detector scripts warn and continue on read errors. CI workflow note: `.github/workflows/ci.yml` triggers only on main/master/atomicity-**, not the `bug-fixes` PR base. | HIGH→P3 | Owner note (CI trigger); FG-23 parked |
| I-04-2 | FIXED in a96e8ee0 (MAX_MISSING_TRANSLATIONS=2223, registered pytest). Residual: a 1-for-1 swap can pass, and the pin does not self-ratchet. | LOW | Optional pytest: real count equals the pin |
| E-02-2 | Narrower than worded. `LifestyleInflationDetector.detectLifestyleCreep` reads prevPrev* but never uses them, and month gaps count as consecutive, so a one-month spike can alert. | LOW | Fix: require sustained growth, drop the dead reads |
| E-02-5 | REFUTED. getMonth is 0-based and the Dec→Jan check is correct. | none | Optional Dec→Jan streak test |
| E-02-6 | REFUTED as worded. NaN/Infinity is never < 0.3. | cosmetic | Optional `avg > 0.0` guard |
| E-02-7 | Our closure holds (half-open) and the FX claim is REFUTED. NEW residual: `InsightsEngine.kt:132-138,181-187,243-249` sets previousMonth = start − durationMs, so March compares against Jan 29–Mar 1; LifestyleInflationDetector:57 uses 360 days. | LOW (user-visible label/compare) | Fix: addMonths for month periods |
| E-03-4 | REFUTED as worded (no ch/ps/ks digraphs exist). The 05l variant (duplicate window / cross-currency) is NEEDS-EVIDENCE. | unknown | Duplicate-detector boundary test |
| P-03-4 | CONFIRMED. The generic `catch (e: Exception)` in ReceiptLifecycleCoordinator (:761) skips `cleanupUncommittedAsset`, there is no orphan sweeper (ReceiptAssetRecovery:40), so the receipt image is left on disk. | MEDIUM | Fix: NonCancellable cleanup in that catch, plus a test |

## R3: privacy
| ID | Verdict at 80251ea3 | Severity | Action |
|---|---|---|---|
| P-12-f | REFUTED as written. The gate re-runs on every generateExport (ExportOptionsViewModel:337), and EXPENSE_EXPORT is allowed by design. Data leaves only on the user's explicit Save/Share/Copy. The remainder (no plaintext notice, no UI encryption) was accepted under item 137. | HIGH→LOW/INFO | Optional plaintext notice |
| RA06-F1 / J-12-1 | FIXED in 45661296. Residual: a format/date change drops the path without deleting the file, which then stays private until the 1h sweep. | LOW | Optional: delete on invalidate |
| N-05-4 | CONFIRMED. `HybridDedupeJudgeService.kt:63-70` falls back to cloud when the on-device judge fails, without checking preferredMode, against `DefaultAiCapabilityRouter.kt:70` "ON_DEVICE must not fall back to cloud". This needs cloud consent and a key; the call is redacted and audited. | MEDIUM→LOW (privacy preference) | **FIX:** fall back only when preferredMode == AUTO |
| N-05-3r | REFUTED as written; the purchase-date part was decided under item 144. | P3 | none |
| P-08-5r / J-11-4 | OWNER-DECIDED (item 137, deferred). | LOW | none |
| P-08-6 | OWNER-DECIDED (fail-closed metadata drop). The ai_chat_sessions part is REFUTED. | none | none |
| P-01-4 | CONFIRMED. ExportAnonymizer:278-304 leaves transientPayloadCiphertext/Nonce/Version (Keystore AES-GCM, so undecryptable off the device). | LOW | **FIX:** null those 3 columns too (guard with columnExists) |
| J-09-3 | REFUTED as defined in the ledger (clearSession cancels the query). The 06b definition is NEEDS-EVIDENCE. | n/a | Optional ViewModel test |
| G-LOCK-1 | OWNER-DECIDED (item 144). | closed | none |

## R1: money, forecast, dashboard, group
| ID | Verdict at 80251ea3 | Severity | Action |
|---|---|---|---|
| FC-PAID-UNLINKED | FIXED (S1–S3). Residual by spec: a payment more than 7 days early is neither linked nor suggested. | HIGH→LOW residual | none (known limit) |
| N-08-2 | REFUTED. Group totals sum only group-currency rows and show a "left out" notice; groups are single-currency at write. | HIGH→none | none |
| P-05-5 | FIXED (2889407c/cfcb50fa). The report's "velocity" claim does not exist in the code. | none | none |
| P-04-3 | OWNER-DECIDED (item 46(b)). | none | none |
| P-05-9 | Narrow. `HomeViewModel.installmentRows` has no UI consumer (dead flow). | LOW | Deferred (render or delete; feature-level) |
| P-06-5 | Narrow, a different defect. The day-detail list shows gross `amount` while totals use effectiveAmount; `.take(3)` has no "+N more". | LOW | **FIX** (cosmetic, UI) |
| E-01-5 | CONFIRMED. A fixed 24h step (`ComputeDashboardWidgetsUseCase:1620, :1677`) against day-start keys breaks the no-spend streak across DST; the Radar's ±7d/30d windows drift by an hour. | LOW | **FIX:** `addDays(-1)` plus a DST test |
| E-01-6 | REFUTED as written. | INFO | none |
| N-08-10 | CONFIRMED, JPY/KRW only. SplitCalculator:301-312 and SettlementCalculator use fixed 2 decimals. | LOW | **FIX:** CurrencyFractionDigits |
| N-08-11 | REFUTED (largest-remainder allocation already exists). | none | none |

## Summary
- **Of the 6 "HIGH":** 2 FIXED (FC-PAID-UNLINKED, I-04-2) and 3 REFUTED (N-08-2, I-04-1, P-12-f). I-01-1 is tooling debt already parked with the owner. **No confirmed HIGH remains.**
- **Confirmed MEDIUM:** P-03-4 (orphaned receipt image), and RA06-F4 (fixed in the rexp lane).
- **Confirmed LOW (to fix):**
  - N-05-4: on-device AI dedupe falls back to cloud.
  - P-01-4: anonymizer ciphertext columns.
  - E-01-5: DST no-spend streak.
  - N-08-10: JPY/KRW split decimals.
  - E-02-7: residual InsightsEngine month window.
  - E-02-2: lifestyle creep on a single spike.
  - P-06-5: day-detail list amounts.
  - P-02-3: audit inserts outside runWrite, deferred.
- **Owner notes:**
  - The CI workflow does not trigger on `bug-fixes`.
  - I-01-1 / FG-23 guard tooling stays parked.
- The review text often did not match its own cell artifacts or the code. Treat that review as unreliable.
