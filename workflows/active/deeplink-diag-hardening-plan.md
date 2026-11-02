# SUPERSEDED (2026-10-26) by app-lock S10 569ef5c0

This plan is superseded. App-lock S10 (569ef5c0) is already on `integration/ai-evolution-1a` (e6967db1). It ships:
- `MarkDashboardBriefingOpenedUseCase`, which writes only when the key equals the last delivered one, so it covers D-DL2;
- `DeepLinkPolicy` and `DeepLinkApplier`;
- `DeepLinkDiagnosticsSentinelTest`;
- the C4 closure.

Do not implement this plan. The text below is kept for the record only.

UF-62 (the stale briefing display) stays on the backlog.

---

# Feature plan: deeplink-diag-hardening

Status: **DRAFT, awaiting the plan human gate** (2026-10-26). Written by feature-planner (read-only) and persisted by the orchestrator.

This is an interim subset of UF-14, and it also closes release advisory C4 (`docs/features/_discovery/opportunity-scan/release-suggestions.md` §C+).

Inputs:
- `docs/features/deeplink-diag-hardening/impact-map.md` (the primary input)
- `docs/features/uf-quick-fixes/decision-sheet.md` § "Owner answers (2026-10-26)": D-DL ship, D-DL2 equality write, D-DL3 canonicalise the period inside the validator, D-DL4 → UF-62
- `docs/features/_discovery/unfinished-features/verification-ui.md` (UF-14)
- `workflows/active/app-lock-privacy-mode-plan.md`: S3 (:306-331) and S10 (:597-631)

Invariant and example IDs come from `docs/features/app-lock-privacy-mode/domain-spec.md`: §5.3 :625-644, INV :892-915, AE :1429-1480. This feature has no domain-spec of its own. It implements a subset of the app-lock spec.

**Risk tier: T3.** Lane C W1 micro-slice. It must merge before app-lock S10 (W3).

**Execution:**
- Work in a fresh Lane C worktree cut from `feature/ai-evolution` at 044be2c8. Branch: `feature/deeplink-diag-hardening`.
- One commit per slice on the lane branch. Pushing is allowed; merging needs an owner request.
- Order: implementer → test-author → guardians → validation-runner → reviewer-strict.
- At most 2 review and 2 debug iterations per slice, then hand back PARTIAL/BLOCKED.
- The two slices form one batch, with one combined validation run and one gate pass.

## Slices

### DL-1 — Pure ingress validators and the briefing-opened use case (dormant: no caller yet)

**Goal and invariants:**
- INV-15 (subset): per-host rules for `briefingKey`, `period` and `location` (spec §5.3 rows home/dashboard/analytics/map). Duplicate values are dropped.
- INV-16 (full): the briefing key is written only when it equals lastDelivered, read at apply time. If the state can't be read, nothing is written.
- INV-18 (subset): the diagnostic message is a constant.
- Covers AE-17 to AE-20 and AE-25/26 at domain level (no DL_* codes; those belong to S2), plus AE-21 for the constant.

**Implementer write set** (both files new):
- `app/src/main/java/com/yourname/expensetracker/domain/deeplink/DeepLinkParamValidators.kt`
- `app/src/main/java/com/yourname/expensetracker/domain/ai/usecase/MarkDashboardBriefingOpenedUseCase.kt`. This is S10's file; segment 20 owns it (spec :1184).

**Test-author write set** (both files new):
- `app/src/test/java/com/yourname/expensetracker/domain/deeplink/DeepLinkParamValidatorsTest.kt`
- `app/src/test/java/com/yourname/expensetracker/domain/ai/usecase/MarkDashboardBriefingOpenedUseCaseTest.kt`, using MockK and `runTest` in the style of `DeliverProactiveBriefingNotificationUseCaseTest`.

**Contract.** S3 and S10 reuse these signatures, so treat them as load-bearing:

```kotlin
object DeepLinkParamValidators {            // pure: no android.*, no IO, no clock
    const val DASHBOARD_OPENED_DIAGNOSTIC = "dashboard deep link opened"
    fun validateBriefingKey(values: List<String>): String?   // values = Uri.getQueryParameters(name)
    fun canonicalPeriod(values: List<String>): String?
    fun validateLocation(values: List<String>): String?
}
class MarkDashboardBriefingOpenedUseCase @Inject constructor(private val aiEngagementRepository: AiEngagementRepository) {
    enum class Outcome { MARKED_OPENED, NOT_LAST_DELIVERED, STATE_UNREADABLE, WRITE_FAILED }
    suspend operator fun invoke(validatedKey: String): Outcome
}
```

**Rules for all validators:** each parameter must occur exactly once, so an empty list or more than one value returns null. No validator may throw.

**`validateBriefingKey`:**
1. Reject anything longer than 32 characters. This check runs first.
2. Require the prefix `dashboard_home:`, case-sensitive and untrimmed.
3. The rest must full-match `[0-9]{4}-[0-9]{2}-[0-9]{2}` with `Regex.matches`. Do not use `\d`, and do not use `find` with `^…$`, because `$` also matches before a trailing `\n`.
4. `LocalDate.parse(rest)` must succeed under the strict ISO format. Catch only `DateTimeParseException`.
5. Return the input unchanged.

**`canonicalPeriod`:**
- Trim, then require the value to be ASCII-only.
- Match it case-insensitively against a `TimePeriod.entries` name and return `TimePeriod.name`, e.g. `"MONTH"`.
- The allowlist comes from the domain enum, so there is no second literal list (D-DL3, F13). `AnalyticsScreen.kt` is not edited.

**`validateLocation`:**
- Trim. The result must be 1 to 100 UTF-16 characters.
- Reject CONTROL, FORMAT, LINE_SEPARATOR and PARAGRAPH_SEPARATOR code points, and lone surrogates. The spec only names Cc and Cf; Zl, Zp and lone surrogates are added with the same intent, and S3 should adopt them as they are.
- Return the trimmed value.

**Use case behaviour:**
- Read `getLastDeliveredDashboardBriefingKey()` once per call. If it is null or not equal to the key (`==`, case-sensitive), write nothing and return NOT_LAST_DELIVERED.
- Otherwise call `setLastOpenedDashboardBriefingKey(key)` exactly once.
- Error handling:
  - Put `catch (e: CancellationException) { throw e }` before `catch (e: Exception)`. Do not use `runCatching` (FG-10, G-CANCEL-01/02).
  - A read failure returns STATE_UNREADABLE; a write failure returns WRITE_FAILED.
  - Never log the exception message, class name or the key value.
- The use case does not re-validate the key format. The equality check is the gate, so the format rule is not duplicated.

**Legal path:** use case → `AiEngagementRepository` → DataStore `ai_engagement`. It is not wired yet, so nothing calls it. There are no Room, DAO, worker or schema changes.

**Depends on:** the Lane C worktree.

**Gates:**
- architecture-guardian: the new `domain/deeplink` package is pure, and the use case is the only writer.
- privacy-security-guardian: the hostile-input corpus.
- reviewer-strict.

**Done when:**
- Every acceptance case below passes.
- `DeepLinkParamValidators.kt` has no `android.*` import, no wall-clock call and no IO.
- Nothing calls the new code yet.

### DL-2 — Minimal `MainActivity.handleIntent` wiring (activates the fix)

**Goal and invariants:**
- INV-16: the key write goes only through the use case.
- INV-18 (subset): no raw value reaches `AiRuntimeDiagnostics` or Timber.
- INV-15 (subset): analytics and map receive validated values, and invalid values fall back to the host default.
- Covers AE-17 to AE-21, AE-25, AE-26 and AE-29, checked in review.

**Implementer write set:** only `app/src/main/java/com/yourname/expensetracker/ui/MainActivity.kt`, about 20 changed lines in three hunks:
1. **Imports (~:39):** replace `AiEngagementRepository` with `MarkDashboardBriefingOpenedUseCase`, and add `DeepLinkParamValidators`.
2. **Fields (:110-111):** replace `aiEngagementRepository` with `@Inject lateinit var markDashboardBriefingOpened: MarkDashboardBriefingOpenedUseCase`. The UI then holds no engagement repository.
3. **`handleIntent`:**
   - :219-227: `validateBriefingKey(data.getQueryParameters("briefingKey"))?.let { lifecycleScope.launch { markDashboardBriefingOpened(it) } }`. The `Outcome` is ignored for now; S10 maps it to codes. Keep `recordInteraction(type = "phase4_open", message = DASHBOARD_OPENED_DIAGNOSTIC)` on every home or dashboard open.
   - :260: `initialPeriod = canonicalPeriod(data.getQueryParameters("period"))`
   - :267: `initialLocationQuery = validateLocation(data.getQueryParameters("location"))`
   - :272: `Timber.w("Ignoring unsupported deep link host")`, a plain literal with no interpolation.

**Must stay untouched:**
- the PRV-16 TODO, the bank branch and the `activity` branch;
- the onCreate/onNewIntent one-shot consume;
- `DeepLinkParser.kt` and `AnalyticsScreen.kt`;
- the manifest, strings, guards, baselines and allowlists.

**Test-author write set: none.** `handleIntent` has no JVM test seam, so DL-1 holds the constant pin and S10 owns the sentinel test.

These existing tests must re-run unchanged:
- `NavigationRouteContractTest`: the `CUSTOM_…` saved-state round-trip stays legal, because values are only canonicalised at ingress.
- `DeepLinkParserTest`: not modified here; S3/R11 owns it.
- `DeliverProactiveBriefingNotificationUseCaseTest`.
- `GsonR8KeepRulesContractTest`: it scans `MainActivity.kt`.

**Legal path:**
1. The exported VIEW intent reaches `handleIntent`, which is untrusted ingress.
2. Values pass through the pure validators.
3. A valid key goes to `MarkDashboardBriefingOpenedUseCase`, which writes through `AiEngagementRepository`.

Navigation still goes through `MainViewModel`. Diagnostics stay in-memory in `AiRuntimeDiagnostics` with a constant message. No durable event is written, so FG-14 does not apply.

**Depends on:** DL-1.

**Gates:**
- architecture-guardian: the deep-link path, use-case ownership, no inline write.
- privacy-security-guardian (lead): exported intent input and diagnostics.
- reviewer-strict.

**Done when:** review against the diff confirms MA-1 to MA-7 below, and the batch validation passes.

## Acceptance criteria

### Validators (`DeepLinkParamValidatorsTest`)

| Validator | Accepts | Rejects (returns null) |
|---|---|---|
| `validateBriefingKey` | `[dashboard_home:2027-03-12]` → same value; `[dashboard_home:2024-02-29]` (leap day) | `[]`; `[""]`; `[dashboard_home:]`; `dashboard_home:2027-13-40` (AE-19); `…2027-02-29` (strict date); `…2027-3-12`; `…+2027-03-12`; `…12027-03-12`; a 500-char value; `x';DROP`; `DASHBOARD_HOME:2027-03-12`; leading or trailing space; trailing `\n`; `\u0000` (decoded `%00`); `‮` (bidi override); full-width or Arabic-Indic digits; a doubled prefix; `home:2027-03-12`; Greek text; two occurrences, even if equal |
| `canonicalPeriod` | all six values in any case → enum name (`month` → `MONTH`, `Quarter` → `QUARTER`); `" week "` → `WEEK` (trimmed, matching `AnalyticsScreen`) | `[]`; `[""]`; `decade` (AE-25); `CUSTOM_2026-01_2026-03`; `WEEK` (Kelvin sign; caught by the ASCII-only rule); `month\u0000`; `["MONTH","YEAR"]`; a 500-char value; `μήνας` |
| `validateLocation` | `Demo Cafe` (AE-26); `"  Athens  "` → `Athens`; `Καφέ Αθήνα`; exactly 100 chars | `[]`; `[""]`; `"   "`; 101 chars; 500 chars; `\u0000`; `‮`; `​`; `\n`; ` `; a lone `\uD800`; two values |

- **Robustness:** no validator throws on the full hostile corpus or on a 10k-character string.
- **Constant pin:** `DASHBOARD_OPENED_DIAGNOSTIC == "dashboard deep link opened"`. It must contain no `$`, `dashboard_home`, `briefingKey` or `expensetracker://` (the AE-21 string set).

### Write only if key == lastDelivered (`MarkDashboardBriefingOpenedUseCaseTest`)

| # | Given (lastDelivered / key) | Expected |
|---|---|---|
| UC-1 | `dashboard_home:2027-03-12` / same | AE-18: `setLastOpened` called once with that key; returns MARKED_OPENED; `setLastDelivered` is never called |
| UC-2 | `…2027-03-11` / `…2027-03-12` | AE-17: no write; returns NOT_LAST_DELIVERED |
| UC-3 | null (never delivered) / a valid key | no write |
| UC-4 | `…2026-10-26` / `dashboard_home:2099-01-01` | Suppression attack: no write, so a future delivery is not suppressed |
| UC-5 | the read throws `IOException` | AE-20: no write; returns STATE_UNREADABLE; nothing escapes |
| UC-6 | the read throws `CancellationException` | the exception propagates; no write |
| UC-7 | keys equal, but the write throws `IOException` | returns WRITE_FAILED; nothing escapes |
| UC-8 | keys equal, but the write throws `CancellationException` | the exception propagates |
| UC-9 | `…2027-03-13` / `…2027-03-12` (an old notification tapped after a newer one was delivered) | no write. This is the intended INV-16 behaviour; the display side is UF-62 |
| UC-10 | two calls, with lastDelivered changed in between | each call reads the current value (read at apply time, no caching) |
| UC-11 | `DASHBOARD_HOME:2027-03-12` against the lowercase delivered key | no write (exact equality) |

### MainActivity (DL-2, checked in review)

- **MA-1:** `home` and `dashboard` always navigate Home first, whether or not the key is valid.
- **MA-2:** the use case starts only when `validateBriefingKey` returns non-null. An invalid or duplicated key starts nothing.
- **MA-3:** no string template inside `handleIntent` references `data`, a query value or the host. Only constants reach `recordInteraction` and `Timber.w`.
- **MA-4:** `getQueryParameters` is called only inside host branches. A non-null host means the URI is hierarchical, so these calls can't throw on opaque URIs (same as today).
- **MA-5:** the `activity` branch, the bank branch, the one-shot consume and the PRV-16 TODO are byte-identical. `git diff --stat` for `MainActivity.kt` stays within about 25 lines.
- **MA-6:** the use case is the only production caller of `setLastOpenedDashboardBriefingKey`.
- **MA-7:** no new strings. EN/EL/ES are untouched, and lint is not needed.

## FG-IDs and validation

**FG-IDs** (cite only; see `FINAL_CI_GUARD_ACCEPTANCE_GATE.md`):
- FG-12: the UI holds no DAO and no repository writer.
- FG-16: no PII in logs; privacy fails closed.
- FG-10: CancellationException propagates.
- FG-17: no wall-clock calls.
- FG-20: compile and unit tests. Lint is not required because no resources change.
- FG-22: the JVM half of "invalid deep links fail safely". The device proof belongs to S10/G1a.
- FG-05/06/07: the full static-guards run, with no allowlist or baseline growth.
- FG-26: doc-lock notes.
- Not applicable: FG-14, FG-21.

**Validation:** validation-runner only, serial, from the Lane C worktree root. The queue is shared with Lane B, so poll the run ID; RUNNING is not a failure.
1. `-Profile compile`
2. `-Profile targeted-unit-test -TestFilter "*DeepLinkParamValidators*,*MarkDashboardBriefingOpened*,*DeepLinkParser*,*NavigationRouteContract*,*GsonR8KeepRulesContractTest*,*DeliverProactiveBriefingNotification*"`. Both new classes must report a non-zero executed test count.
3. `-Profile registered-guard -GuardId pii_logging`
4. `-Profile registered-guard -GuardId ui_dao`
5. `-Profile static-guards`, once at close.

Do not run app-check, any shard, or lint.

## How S3 and S10 reuse these pieces

**S3 (`DeepLinkPolicy`):**
- It calls `validateBriefingKey` and `validateLocation` for the home/dashboard and map rows instead of re-implementing them. Passing a `List<String>` already enforces the duplicate-drop rule.
- S3 creates `domain/analytics/TimePeriodTokens.kt` and re-points or moves `canonicalPeriod` there, as the one allowlist shared with `AnalyticsScreen` (F13). There is no literal list to reconcile.
- It must keep the output format (`TimePeriod.name`), or re-verify `NavigationRouteContractTest`.
- If the validators move, their test cases move too. They may be rewritten but not deleted.
- S3 adds the DL_PARAM_* codes. `DeepLinkRequest.kt` lives in the same package, with no file collision.

**S10:**
- The use case and its test already exist, so S10's write set changes from "new" to "modify" for these files.
- S10 adds the S2 emitter mapping: NOT_LAST_DELIVERED → `DL_BRIEFING_KEY_NOT_DELIVERED`, STATE_UNREADABLE → `DL_BRIEFING_STATE_UNREADABLE`.
- The equality rule and the `Outcome` enum stay as they are.
- S10 rewrites `handleIntent` wholesale, replacing the DL-2 hunk, and keeps `DASHBOARD_OPENED_DIAGNOSTIC` for `phase4_open`.
- `DeepLinkDiagnosticsSentinelTest` replaces the constant pin.
- S10's C4 re-check becomes "adopt": this lane delivers the diagnostic and suppression parts of C4.

**Doc lock** (orchestrator, after merge; outside the Lane C write set):
- Add a "pre-landed by deeplink-diag-hardening <sha>" note to the S3 and S10 sections of the app-lock plan.
- Stage the LEGAL_PATHS "DEEP LINKS" text and the CODEBASE_SEGMENTS `domain/deeplink` entry for S10's doc-lock writer.
- Record UF-62 in the backlog.

## Status

| Item | Owner | Status |
|---|---|---|
| Plan | feature-planner → plan human gate | DRAFT |
| Lane C worktree (044be2c8) | orchestrator | NOT STARTED |
| DL-1 implementation and tests | feature-implementer (Sonnet high) / test-author | NOT STARTED |
| DL-2 implementation | feature-implementer | NOT STARTED (needs DL-1) |
| architecture-guardian and privacy-security-guardian | read-only, can run in parallel | NOT STARTED |
| Validation batch (steps 1-5) | validation-runner | NOT STARTED |
| reviewer-strict (with run IDs) | Opus | NOT STARTED |
| Doc-lock notes and UF-62 entry | orchestrator | NOT STARTED |
| Merge before AL S10 | needs an owner request | BLOCKED (by design) |

## Open risks

1. **The wiring has no JVM test.** `handleIntent` has no test seam, so the wiring is proven by review (MA-1 to MA-7) plus the constant pin. S10's sentinel test closes this gap.
2. **C4 on the release track.** Re-check `bug-fixes` before merging. If a C4 fix landed there, adopt it and rebase.
3. **Textual conflict with QF-2d (Lane B).** Both slices edit `MainActivity` in different regions, but their import blocks may clash. Whichever merges second rebases. One writer at a time, and both land before S10.
4. **Existing bogus lastOpened keys.** A key written before this fix is not cleaned up, because that would be a destructive change. Home may show no briefing until the next real tap. A bogus stored date suppresses at most one delivery. This overlaps UF-62.
5. **Map highlight spoofing remains.** Another installed app can still send up to 100 printable characters of highlight text, which the spec allows (F12) until app-lock. This interim fix removes the side effect, not the reachability: the BROWSABLE category stays until S11, and explicit intents are still possible.
6. **Benign race.** If the user taps before `setLastDelivered` has completed, nothing is written. The spec accepts this.

## Human decisions

- **Blocking:** none. D-DL/2/3/4 are answered. The plan adds no strings, permissions, manifest entries, guard changes or schema changes.
- **Non-blocking:** the orchestrator writes the doc-lock note into the app-lock plan (S3/S10) after merge. Recommended: yes, but not while an owner session is active in that plan.

## Deferred to later increments

| Item | Goes to |
|---|---|
| `activity` `expenseId` rules (AE-22/23) | S3/S10 |
| DL_* codes and rate limit | S2 |
| Structural and unknown-parameter checks (AE-27/30) | S3 |
| `DeepLinkApplier` and replacing the PRV-16 TODO | S10 |
| Explicit PendingIntents (INV-19) | app-lock |
| Removing BROWSABLE | S11 |
| `TimePeriodTokens` | S3 |
| LEGAL_PATHS "DEEP LINKS" entry | S10 |
| Stale-briefing display | UF-62 |
