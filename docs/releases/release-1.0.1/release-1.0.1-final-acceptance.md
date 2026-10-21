# Release 1.0.1 — final acceptance record

```text
Candidate:  f150a1ac (branch release-1.0.1). A docs-only commit with this record follows it.
Base:       5414696f (B0 = bug-fixes after the owner's test-suite merge; gated)
Version:    1.0.1 (code 2)
Status:     CONDITIONAL — code baseline accepted; owner-only conditions below remain.
Not pushed. bug-fixes is moved locally only; pushing is an owner action.
```

## 1. Gate evidence (all on f150a1ac, clean tree, fingerprint start = end)

| Profile | Run | Result |
| --- | --- | --- |
| app-check (debug + release unit tests) | vr-20261021-113902-539cf813 | PASS, 14,973 passed / 0 failed / 3 skipped |
| static-guards | vr-20261021-120259-0e7d27a9 | PASS, 25/25 |
| legacy-tests | vr-20261021-121818-ba8d4867 | PASS, 238 passed / 0 failed / 1 skipped |
| assemble-release | vr-20261021-121927-4599c941 | PASS (up-to-date build) |
| release-artifact-evidence | vr-20261021-122007-ef1da693 | PASS; APK SHA-256 `3985b067…51b6`; 1.0.1 (2); not debuggable; **debug certificate** |

| connected-tests (emulator wave5-api35, Android 15, serial emulator-5554) | vr-20261021-122436-ae51bc1e | PASS, 159 tests / 0 failed / 0 skipped |

The owner's phone was not used. Not run: manual device smoke on the release APK.

Earlier failures on the way (all fixed, recorded for honesty): static-guards on the first W2 and
W3 commits (guard window layout; raw `execSQL` in the export scrub), two analytics test-harness
failures, one test compile error, two wrong test expectations (currency before the amount).

## 2. What changed since B0 (backlog: release-1.0.1-backlog.md)

- **W1:** Greek VAT parsing; future-dated rates are stale; invalid currency is not "missing rate";
  TaxEstimator rethrows cancellation; cancelled notification capture releases its dedupe key;
  capture error JSON is a bounded literal; MIME mapping; prompt-policy note; restore-gate tests.
- **W2:** restore diagnostics sink propagates cancellation; stale allowlist entries removed.
- **W3:** diagnostics store exception class names only (Room recorders, restore journal, DataStore
  maintenance sink, journal import, worker run logger); sanitizer tightened; release worker logs
  carry class names only; redacted export nulls diagnostic text columns; intake purge skips
  purged rows; receipt event error details only for uppercase reason codes; PRIV-02 pending-review
  notification text cutoff = shorter of the user window and 30 days (owner decision b).
- **W4/W4b:** invalid exchange rates fail closed; forecast fixes (FORE-01/02/05/06); analytics
  previous-month comparison windows and cache bound; Greek bank parser currency capture,
  `1.500` thousands rule, card-number-as-amount fixes.
- **Version bump** 1.0 (1) -> 1.0.1 (2).

## 3. Independent review

| Review | Verdict | Notes |
| --- | --- | --- |
| Final review part 1 (structure, guards, tests) | ACCEPT-WITH-CONDITIONS | No schema change; guard/allowlist changes shrink-only; no test deleted or ignored; all changed expectations justified. Conditions closed except signing. |
| Final review part 2 (money and privacy code) | ACCEPT-WITH-CONDITIONS | Found three older defects: card number taken as amount (fixed), residual error text in job/receipt events (fixed), leading currency not captured (deferred as F-H1-g). |

**Disclosure:** the fixes made after part 2 (parser regexes, receipt error filter, test changes)
were reviewed by per-slice reviewers (parser: two strict rounds with a regex harness; privacy:
privacy-security-guardian), not by the final reviewers. They were then covered by the full gate
above. A fresh final review of those last commits has not been run.

## 4. Owner decisions and conditions

| Item | State |
| --- | --- |
| Production signing | Open. The APK is signed with the debug certificate. Not shippable beyond the beta. |
| On-device tests | Done: 159/0/0 on the wave5-api35 emulator (vr-20261021-122436-ae51bc1e). |
| Manual device smoke on the release APK | Open (owner). |
| Agent turn limits | Owner edited the main-folder agent files; whether the session reloads them was not confirmed. |
| TB-1 (feature/ai-evolution dates expire 2026-10-31) | Open, owner. |
| TB-2 (HANG-001..003 review_by 2026-12-01) | Open, owner (Q1). |
| TB-3 (about 97 cancellation-guard entries expire 2027-01-01) | Open, owner review session (Q3). |
| Recorder guard entry removal; sink post-swap runCatching | Deferred (needs source change/review). |
| Deferred by owner-approved recommendation | CURR-6, FORE-04, F-H1-c, restore purge of invalid exchange rates (read-side guard only), PRIV-12, PRIV-03, F-R28-a/c. |

## 5. Behaviour changes users may notice

- Risk level can be CRITICAL when spending plus obligations plus goal reserves exceed an explicit
  overall monthly budget, even on pace (FORE-05).
- Weekly and biweekly recurring items now project every remaining occurrence.
- Greek bank notifications: amounts such as `1.500` read as 1500; card numbers are not read as
  amounts; a foreign currency written after the amount is kept. A currency written BEFORE the
  amount still falls back to the home currency (F-H1-g).
- Pending-review notification text is redacted at the shorter of the user's retention window and
  30 days. A 0-day window redacts it at the next retention run.
- Redacted exports no longer contain diagnostic message text.
- Month comparisons use the previous calendar month (first N days; no Home UI shows it today).

## 6. Residual risks

- Regexes in the Greek parser were verified by a JDK harness and unit tests, not on a device with
  the Android (ICU) engine and real bank notifications. Check recently saved NBG/Alpha expenses.
- `RestoreDiagnosticsSink.event()` still wraps the safe-sink call in a plain `runCatching`
  (post-swap cancellation swallowed); tracked, allowlisted until 2027-01-01.
- All-digit hash values and the `amount` key are redacted from diagnostics metadata; a small
  fraction of genuine hashes lose correlation (fails closed).
- Many P3 items remain in the backlog (test debt, docs, debug-only paths).
