# Release 1.0.1 — consolidated backlog

```text
Baseline: Release 1.0 = bug-fixes @ f8433f51 (code 01fae80c)
Compiled: 2026-10-19 by the release orchestrator
Sources:
  (1) Release 1.0 deferrals: release-1.0-user-notes.md "Known limitations", review follow-ups F-*,
      final-acceptance residual risks
  (2) Test-suite audit catalogue (52 findings): workflows/active/test-suite-audit-release-followups.md
      on branch chore/test-suite-audit. It was compiled on 8671b844 (R28), 22 commits before 1.0.
Reconciliation: two independent read-only release-finding-verifiers checked every audit finding
  against the 1.0 source and the campaign ledgers (static only; no builds or tests run).
Status: backlog only. No item is approved for implementation. Every fix follows the normal flow
  (planner -> coder -> targeted tests -> reviewer-strict for money/privacy/Room/workers ->
  validation-runner gate).
```

Severity uses the campaign scale:

- **P0:** data loss or a security breach.
- **P1:** user-visible wrong money in a main release flow, a privacy leak into persistent storage or
  export, or a crash in a main flow.
- **P2:** meaningful, but bounded or with a workaround.
- **P3:** latent, debug-only, hidden feature, documentation or cosmetic.

The audit catalogue used High/Medium/Low. The verifiers recalibrated each item against 1.0
reachability:

- Bank sync and statement import are debug-only in release.
- Legacy import and reset are debug-only.
- Tax, investment and challenges are hidden by `DeferredFeatureGate`.
- Timber is planted only in debug builds.
- `android.util.Log` is not stripped.

**Result (updated 2026-10-19 after the S0 checks):**

- **MONEY-02 was recalibrated to P1 and fixed as hotfix H1** (b75f309b). GreekBankParser read merchant
  or time digits as the amount. The fail-first test reproduced it (12.50 became 24.00 or 1432.00), and these
  amounts are auto-saved at confidence 0.92.
- **DIAG-01 is not P1.** The owner-approved runtime check on the owner database found 0 raw text in
  diagnostics (note B2).
- No other audit finding is P0 or P1.

## A. Time-critical (dates)

| ID | Item | Deadline | Action |
| --- | --- | --- | --- |
| TB-1 | `feature/ai-evolution` never received the 1.0 guard renewals (G2/G3). See note TB-1 below. | **2026-11-01** | Merge `bug-fixes` into `feature/ai-evolution` (owner branch, owner approval), or renew the dates there. `chore/test-suite-audit` already has 2026-12-01. |
| TB-2 | Release line: HANG-001..003 review date 2026-12-01. The runner refuses from 2026-12-02. | 2026-12-01 | Review the ledger and close or renew HANG-001..003. They were not reproduced in 9+ clean runs. |
| TB-3 | Guard-hardening owner review: HZ-08 F1/F2, the 2027-01-01 allowlist expiry (FG-06) and MISC-01. | 2026-12-01 | Owner review session. |

**Note TB-1.** `WriteBarrierArchitectureGuardTest` has 16 exemptions expiring 2026-10-31, and
`known-hanging-tests.json` has `review_by` 2026-10-31 (lead-verified). From 2026-11-01:

- the guard test fails;
- the validation runner refuses to run (`E_HANGING_LEDGER_REVIEW_OVERDUE`).

## B. Priority items for 1.0.1 (P2, or decision needed)

| # | ID | Item | Sev | Source | Notes |
| --- | --- | --- | --- | --- | --- |
| 1 | MONEY-01 | Greek receipt `Φ.Π.Α. 24,00%: 9,68 €`: `ReceiptParser` takes the VAT **rate** as the tax amount. Evidence and effects in note B1. | P2 | audit (NEW) | Regex fix. Flip the KNOWN DEFECT pin in `ReceiptParserOcrPatternsTest`. Candidate for a beta hotfix if it stays small. |
| 2 | DIAG-01 | Diagnostics stores keep pattern-sanitized exception messages. Details in note B2. | P3 (runtime check done: not P1) | audit = RG15C-2 / D-17 | Policy hardening in W3: class name + reason code only; scrub in redacted exports. |
| 3 | CURR-6 | After a home-currency change, amount sort and filter use the previous currency's values. | P2 | 1.0 deferral (V-D F-1) | Known limitation in the user notes. |
| 4 | R27 residual | About 60 check-only save paths are not drained by backup/restore. | P2 (accepted) | 1.0 deferral (Q-R27-4) | Known limitation. |
| 5 | MISC-01 + DIAG-11 | Cancellation guard problems. Details in note B5. | P2 | audit = phase3-4 guard hardening | Narrow the allowlist, use a real clock, fix the two swallows. No baseline loosening. |
| 6 | FORE-04 | Overdue PLANNED occurrences and unpaid MUST items drop out of "committed" in the forecast. | P3 | audit (NEW) | S0 check: a naive "include overdue" would double count, because auto-linking is strict (same day, merchant, ±10%). Suggested rule: include only if overdue ≤7 days in the current month and no likely unlinked payment exists (same merchant/category, ±20%, same currency); show it separately as "overdue, unconfirmed". |
| 7 | MONEY-06 | Stored exchange rates are not checked for finite/positive on read. Every in-app write is validated. | P3 | audit (NEW) | S0 check: restore does NOT validate rate values (BackupVerifier only counts `exchange_rates`). It needs a crafted backup plus its password, so it stays P3. Fix: purge invalid rates on the staged database before the swap; `toDomain` returns null for an invalid rate; finite/positive guard before `Converted` and the `baseAmount` write. |
| 8 | LIFE-05 | Privacy denial poisons the notification dedupe cache for 5 s. | P3 | audit (fixed only on `chore/test-suite-audit`, B2-2c) | Port the fix together with its test `NotificationCaptureServiceBlockedDecisionTest`. |

**Note B1.** Code locations: `ReceiptParser.kt:468` and `:773`.

- The wrong value is shown in the receipt review (`ReceiptScanScreen.kt:1092-1096`).
- It is saved to `scanned_receipts.parsedTaxAmount`.
- It is sent to the AI receipt assist.
- The expense amount is correct.

**Note B2.** Affected stores:

- `pipeline_diagnostic_events`
- `operation_run_events` / `operation_runs`
- the DataStore safe sink

These tables are in every `.costbackup`. Redacted mode (`ExportAnonymizer.kt:76-84`) does not
clean them.

**Runtime check, 2026-10-19 (owner-approved).** Run on a scratch copy of the owner database
(v145), counts only; the copy was deleted afterwards.

| Table | Rows |
| --- | --- |
| `pipeline_diagnostic_events` | 388,175 |
| `privacy_audit_events` | 363,161 |
| `operation_runs` | 6 |
| `operation_run_events` | 12 |

- The columns `exceptionMessage`, `message`, `errorSummary`, `exceptionClass` and `dropReason` are
  empty in every row.
- 0 diagnostic rows share a 16-character text window with notification, intake, pending-review,
  receipt or email text (368 source rows checked).
- No Greek text, currency amounts, e-mail addresses or SQL messages appear.
- Long numbers appear only in the `postTime`, `packageName`, `notificationKey` and ID metadata keys.

Limitations: this covers one device's history, and retention may have purged older source text.
**Result: not P1. It stays P3 policy hardening.**

**Note B5.** `CancellationSafetyArchitectureGuardTest.kt:117` allowlists the whole
`CompositeOperationRunRecorder.kt` and uses a frozen 2026-09-15 clock, so 13 expired entries never
fire. The CancellationException swallows at `CompositeOperationRunRecorder.kt:59` and `:170` are
still in 1.0; they were fixed only on the test branch.

## C. Release 1.0 deferrals carried forward

From `release-1.0-user-notes.md` "Known limitations" (P2/P3, owner-confirmed 2026-10-18):

- Statement import overlapping a backup can leave the statement marked "parsed" (debug-only in
  release).
- Recurring bills created before 1.0: old overdue planned entries; EUR default.
- Bills due on the 31st can drift to the 28th or 30th (R21 anchor drift).
- Rare race: an unlinked recurring expense remains after a failed "Mark paid".
- A group-linked expense can't be deleted or have its amount edited (needs a "remove from group"
  feature).
- Android 15+: the ongoing notification appears after reboot only once the app is opened.
- Split template blocked by a backup closes silently.
- Statement import drops rows that contain summary words (e.g. "OVER LIMIT FEE") (F-R22-a).
- Privacy settings are reachable only from the Features menu.
- The Safe-to-spend amount is not calculated (placeholder).
- Android 14+: location for captured notifications only after the first app open.

Review follow-ups (P3 unless noted; detail in the lead log and the phase3-4 findings ledger):

- **F-R16c-a:** remaining amount inputs outside the R16c set.
- **F-R18 P3s:** UiWriteGuard sweep leftovers.
- **F-R20b-b:** rows inside the current minute are excluded from %.
- **F-R20b-c:** unbounded analytics caches.
- **F-R20b-a (residual):** Home passes the whole current month, so the previous window is the first N days of
  the previous calendar month where N is the current month's length (April compares with Mar 1-30; Feb with
  Jan 1-28/29). No UI consumer reads the Home percent today. previousDailyHistory may differ in length from
  dailyHistory. The year-over-year prior cutoff (AnalyticsViewModel about :651/:966) still uses the start of
  the minute. PeriodComparisonWindows mixes its zone parameter with TimePeriodUtils (system zone): remove the
  parameter or derive from zone.
- **F-R20c-a..c:** date-picker follow-ups.
- **F-R22-b:** NBG rows in unrecognised statements.
- **F-R22-c:** dotted-time day-order flip.
- **F-R24a-a:** review-error follow-up.
- **F-R24b-a:** dismissing the notification dialog without a choice shows one "Open settings"
  dialog.
- **F-R28-a..d:** retention follow-ups:
  - GR-08 seeds;
  - `encryptAndClearVisiblePayload` should refuse purged rows;
  - remove the unused `purgeRawPayload`;
  - 0-day slider text.
- **F-R30-a:** see the lead log.
- **F-R32-a..d:**
  - a mode-only RESTART_REQUIRED/CRITICAL test;
  - the "Please try again" text for a blocked restore;
  - the VERIFYING test tie-in;
  - the concurrent restore-start race.
- **HZ-03 F1b:** reset or legacy import cancelled after the destructive step latches CRITICAL
  (debug-only).
- **HZ-08 F1/F2:** `android.util.Log` throwables invisible to the PII guard;
  RecurringArchitectureGuardTest vs LEGAL_PATHS.
- **V-I-7:** no onboarding (product).
- **F-DEV-3:** Privacy settings "Feature disabled" card text contrast.
- **Groups:** a "remove from group" feature (product).
- **Records/process:** `runner-protocol-tests` belongs on the final gate (MISC-05).
- **Hotfix H1 follow-ups (GreekBankParser, from the strict reviews):**
  - **F-H1-a:** "1.500" without decimals parses as 1.5. `parseAmount` reads a dot-only token as a
    decimal, which is ambiguous in Greek notation; needs a decision.
  - **F-H1-b:** a currency written mid-text ("Αγορά 12,50 USD στο SHOP", "Χ 50,00 EUR ΑΤΜ") falls
    back to the home currency. Pre-existing; PURCHASE[0] and TRANSFER[0] have no currency group.
  - **F-H1-c:** "Μεταφορά €X σε Y" is typed PURCHASE, because PURCHASE[1] claims it first.
  - **F-H1-d:** `resolveCurrency` uses the deprecated `normalize`.
  - **F-H1-e:** GreekBankParser/AmountUtils read "1,500" (comma + three digits, no decimals) as 1500
    (grouping), not 1.5. Greek notifications use comma as the decimal separator, so this could
    over-record by 1000x if a bank writes "1,500" for 1.50 (unlikely: banks write two decimals).
    Not changed in 1.0.1; revisit with F-H1-a evidence.
  - **F-H1-f:** Greek three-decimal currencies (KWD, BHD, JOD, OMR, TND) written with a dot amount are read
    as thousands, because the currency token is not recognised by the parser (`CURRENCY_TOKEN` is
    EUR/USD/GBP only). Bounded by the 50000 parser ceiling.
  - **F-H1-g (deferred):** a currency symbol/code BEFORE the amount is never captured (purchase pattern 1,
    deposit patterns, transfer pattern 1 and 4), so e.g. "Πληρωμή $6.30 σε STEAM" is stored in the home
    currency. Also, `homeCurrency` is read once with `runBlocking` when the singleton is created
    (`ParserModule.kt:26-28`) and goes stale if the user changes it. Fix = capture the leading token and
    resolve leading, then trailing, then tail token.
- **Forecast follow-ups (from the FORE-05 / FORE-02 reviews, P3):**
  - **FORE-05-a:** the rainy narrative in `NarrativeGenerator.kt:36` (HIGH && discretionary <= 0) is now
    reachable only on exact equality, because over-committed months escalate to CRITICAL.
  - **FORE-02-a:** weekly projection tests across a DST change and with a non-blank `displayCurrency` are
    missing.
  - **FORE-02-b:** the fallback branch can overcount weekly manual rules when all PLANNED occurrences were
    skipped.
- **PRIV-03 follow-ups (1.0.2):**
  - `RawPersistencePolicy.kt:13` KDoc says amount/date/currency are kept even in DO_NOT_STORE but the
    resolver keeps them only for EMAIL_RECEIPT (comment wrong since before 1.0.2).
  - Optional test: with corrupted privacy settings, the email dedupe hash
    (sourceFingerprint == messageIdHash) is still stored while rawOcrText is null
    (ReceiptLifecycleCoordinatorTest).
- **CURR-6 scoping (S0 check):** no schema change is needed. `baseAmount`/`baseCurrency` are fixed at
  save time. Preferred fix: a guarded re-normalisation worker through the TransactionLifecycle
  coordinator when the home currency changes; rows without a rate stay flagged. Planner-first; money
  plus lifecycle review.
- **Tooling:** `targeted-unit-test` accepts one filter pattern (wildcards allowed). Follow-up runs
  reuse the build and take about 15–20 s, so "one combined run" means one compile plus cheap
  follow-up runs. A multi-filter profile is optional.

## D. Test-suite audit reconciliation (all 52 findings)

**1.0 status values:**

| Status | Meaning |
| --- | --- |
| PRESENT | Still in the 1.0 code. |
| FIXED-1.0 | Already fixed in Release 1.0. |
| TEST-BRANCH | Fixed only on `chore/test-suite-audit`, not yet in 1.0. |
| REFUTED / RECAL | Not a release defect, or the severity was recalibrated. |
| NV | Not verified (verifier budget). |

**Match:** "NEW" means no earlier campaign finding exists. Otherwise it gives the existing campaign
ID.

### Privacy and diagnostics

| ID | Audit sev | 1.0 status | Match | 1.0.1 sev | Note |
| --- | --- | --- | --- | --- | --- |
| DIAG-01 | High | PRESENT | RG15C-2 / D-17 | P3 | Runtime check: 0 raw text (note B2) |
| DIAG-02 | High | PRESENT | V-H-1 | P3 | Reachable only from debug-only bank sync; release callers pass literals or codes |
| DIAG-03 | Med | PRESENT | RG15C-2 | P3 | RestoreDiagnosticsSink / DataStore sink |
| DIAG-04 | Med | PRESENT | V17B-N2 (MIT-035) | P3 | CE swallow in RestoreDiagnosticsSink |
| DIAG-05 | High | PRESENT | V-G-9 (partial) | P3 | Amounts in diagnostics metadata; the same amounts are already in the same backup |
| DIAG-06 | Med | PRESENT | NEW | P3 | Sanitizer: long digits, all-digit hash slot, sender hash slot |
| DIAG-07 | Low | PRESENT | NEW | P3 | MIME_UNSUPPORTED unreachable (label only) |
| DIAG-08 | Low | NV | NEW | P3 | Policy doc line |
| DIAG-09 | Med | RECAL | NEW | P3 | Codes are already DiagnosticReasonCode names; defence in depth only |
| DIAG-10 | Med | PRESENT | HZ-06 F5 | P3 | WorkerSpecScheduler `e.message` |
| DIAG-11 | Low | TEST-BRANCH | NEW | P2 with MISC-01 | See B5 |
| PRIV-01 | High | PRESENT | NEW | P3 | Statement import is debug-only in release; the merchant is also in `expenses` |
| PRIV-02 | Med | PRESENT | NEW | P3 | Fixed 30-day text cutoff, not the user window |
| PRIV-03 | Med | PRESENT | NEW | P3 | accepted: PendingReview.suggestedMerchant is persisted in every storage mode by design; the unused flags were removed in 1.0.2 (owner-approved) |
| PRIV-04 | Med | PRESENT | NEW | P3 | Uncalled payload builders (test debt) |
| PRIV-05 | Med | PARTIAL | NEW | P3 | One null-input rule; the notification part of the claim is wrong |
| PRIV-06 | Low | PRESENT | NEW | P3 | Dead sanitizers |
| PRIV-07 | Low | PRESENT | NEW | P3 | Malformed extras JSON only if building it throws |
| PRIV-08 | Med | PRESENT | NEW | P3 | Debug-only bank API |
| PRIV-09 | High | PRESENT | NEW | P3 | Design decision: a Keystore key would break cross-device dedupe after restore |
| PRIV-10 | Med | PRESENT | NEW | P3 | Deliberate (lock-out risk); DataStore writes atomically |
| PRIV-11 | Med | PRESENT | NEW | P3 | In-memory reason only |
| PRIV-12 | Med | PRESENT | V-H-3 (images); rest NEW | P3 | Retention for receipt images, account name, lat/long |
| PRIV-13 | Low | NV | NEW | P3 | Behavioural retention routing test |
| PRIV-14 | Low | PRESENT | NEW | P3 | CE branch keeps the dedupe key |
| PRIV-15 | Low | RECAL | V-H-4 | P3 | No delete-all in release; Android "clear data" wipes it |

### Retention, logging and other

| ID | Audit sev | 1.0 status | Match | 1.0.1 sev | Note |
| --- | --- | --- | --- | --- | --- |
| RET-01 | Low | PRESENT | NEW | P3 | `Log.e/w` with throwable in DataRetentionWorker |
| RET-02 | Low | NV | — | P3 | Doc |
| LOG-01 | Low | TEST-BRANCH | near RG02B-1 | P3 | Debug-only |
| LOG-02 | Low | PRESENT | NEW | P3 | 20 Timber sites (debug); `Log.e` in WorkerSpecScheduler (release) |
| LOG-03 | Low | by design | NEW | P3 | Debug-only |
| MISC-01 | Med | PRESENT | phase3-4 guard hardening | P2 | See B5 / TB-3 |
| MISC-02 | Med | FIXED-1.0 on the release line; PRESENT on `feature/ai-evolution` | G2/G3 | P2 | See TB-1 |
| MISC-03 | Low | RECAL | NEW | P3 | Test helper KDoc |
| MISC-04 | Low | TEST-BRANCH | NEW | P3 | Runner: an all-skipped pytest reports PASS |
| MISC-05 | Low | PRESENT | NEW (process) | P3 | Add `runner-protocol-tests` to the final gate |
| MISC-06 | Low | PRESENT | RG17B-3 / HZ F2 | P3 | SecureKeyStorage migration test seam |

### Money and currency

| ID | Audit sev | 1.0 status | Match | 1.0.1 sev | Note |
| --- | --- | --- | --- | --- | --- |
| MONEY-01 | High | PRESENT | NEW | **P2** | See B1 |
| MONEY-02 | High | **FIXED by hotfix H1** | NEW | **P1** | Recalibrated: GreekBankParser (NBG/Alpha) read merchant or time digits as the amount. Revolut, Sms and GoogleWallet are safe. Follow-ups F-H1-a..d (section C). |
| MONEY-03 | Low | PRESENT | NEW | P3 | Consumers check `isFinite` |
| MONEY-04 | Med | PRESENT | HZ07-P1 / V-E-10 | P3 while hidden | Tax hidden in release; fix before enabling tax. The catch also swallows CE. |
| MONEY-05 | Med | PRESENT | NEW | P3 | One-sided staleness in legacy `convert` |
| MONEY-06 | High | PRESENT | NEW | P3 / P2 | See B7 |
| MONEY-07 | Low | PRESENT | CA-E-01-006 (related) | P3 | Wrong failure type |
| MONEY-08 | High | REFUTED (release) | CA-E-04-003 | P3 dormant | GroupBalanceCalculator has no production caller; single currency is enforced at every writer |
| MONEY-09 | Low | NV | NEW | P3 | Display rounding |

### Forecasting and analytics

| ID | Audit sev | 1.0 status | Match | 1.0.1 sev | Note |
| --- | --- | --- | --- | --- | --- |
| FORE-01 | Med | PRESENT | NEW | P3 | Needs non-finite history (corrupt data) |
| FORE-02 | Med | PARTIAL | NEW (near CA-E-02-005) | P3 | Patterns under 0.70 confidence subtracted but not projected |
| FORE-03 | Med | PRESENT | near RG16A-2 / R9 | P3 | Product: STRICT undated goals |
| FORE-04 | Med | PRESENT | NEW | P3 → P2 candidate | See B6 |
| FORE-05 | Med | PRESENT | NEW | P3 | Risk escalation |
| FORE-06 | Low | PRESENT | NEW | P3 | Insight counts |
| FORE-07 | Low | PRESENT | NEW | P3 | Exact multipliers |
| FORE-08 | Low | PRESENT | NEW | P3 | Goal reserve proration |
| FORE-09 | Low | PRESENT | near RG06A-1 | P3 | Product: runway card hidden |

### Lifecycle and groups

| ID | Audit sev | 1.0 status | Match | 1.0.1 sev | Note |
| --- | --- | --- | --- | --- | --- |
| LIFE-01 | Med | PRESENT | NEW | P3 dormant | `permanentlyDeleteGroup` has no UI caller; the UI uses soft archive |
| LIFE-02 | Low | PRESENT | NEW | P3 | Same dormant path |
| LIFE-03 | Med | PRESENT | RG04A-3 | P3 | Non-atomic path not used by the VM |
| LIFE-04 | Med | PRESENT | RG04A-3 | P3 | Raw exception text in group error results |
| LIFE-05 | Med | TEST-BRANCH | NEW for 1.0 | P3 | See B8 |
| LIFE-06 | Low | PARTIAL | RG04A-2 | P3 doc | LEGAL_PATHS CREATE group :702-711, :754/:765 |
| LIFE-07 | Med | PRESENT | HZ04-F3 | P3 | Also 13+ direct inserts in RecurringLifecycleCoordinator; critical vs diagnostic still to classify |
| LIFE-08 | Med | NV | NEW | P3 | `updateSubscriptionCategory` is handled as a scoped carve-out |
| LIFE-09 | Low | RECAL | NEW | P3 | Test-only code; probably fails closed |
| LIFE-10 | Low | NV | near HZ-03 F1b | P3 | Debug-only |
| LIFE-11 | Low | PRESENT | test-audit batch-08 | P3 doc | evidence-map.md:20 |

## D2. Wave 4b status (owner-approved)

**DEFERRED beyond 1.0.1** (owner-approved recommendation):

- **FORE-04:** feature-like and needs UI strings.
- **CURR-6:** rewrites stored amounts in bulk and needs a new lifecycle legal path.
- **F-H1-c:** may move transfers out of spending totals.

**DONE in W4b:**

- **FORE-05** (risk escalation).
- **FORE-02 weekly** (the weekly part of FORE-02).
- **F-H1-a** ("1.500" without decimals).

## E. Suggested 1.0.1 batching (for planning; not approved)

1. **Hotfix / beta candidates (small, test-pinned):**
   - MONEY-01
   - DIAG-01 runtime check, then the decision
2. **Guard and cancellation hygiene:**
   - MISC-01 + DIAG-11 + DIAG-04 + MONEY-04 CE catch
   - port the LIFE-05 / LOG-01 / MISC-04 fixes from `chore/test-suite-audit`
   - TB-2/TB-3 reviews
3. **Diagnostics bounded-code sweep:**
   - DIAG-01/03/05/06/07/10
   - RET-01, LOG-02, PRIV-11
   - redacted-export scrub of the diagnostics tables
4. **Money/forecast correctness:**
   - CURR-6, FORE-04 (decision), MONEY-06 (restore check), MONEY-05/07
   - FORE-01/02/05/06
   - F-R20b-a Home windows
5. **Privacy/retention:**
   - PRIV-02, PRIV-03, PRIV-12, PRIV-14, F-R28-a..d
   - PRIV-09 design decision
6. **Product/UX:**
   - Safe-to-spend
   - "Remove from group"
   - onboarding
   - Privacy settings in Settings
   - F-DEV-3
   - FORE-03/08/09
7. **Docs and test debt:**
   - LIFE-06, LIFE-11, DIAG-08, RET-02, MISC-03/05/06
   - PRIV-04, PRIV-13, LIFE-08/09

The test-suite audit branch (`chore/test-suite-audit`) also carries production fixes:

- B2-2c (LIFE-05)
- B2-4 (DIAG-11)
- LOG-01
- the MISC-04 tooling fix

Merge it into the 1.0.1 line after 1.0, so those fixes are not lost and are not re-implemented.
