# Impact map: QF-3, stale comments and dead code (2026-10-22)

Source: an impact-mapper run (Sonnet, read-only, no shell), persisted by the orchestrator. The mapper took dirtiness
from the session git snapshot. None of the QF-3 files is dirty in either worktree.

## Headline
- **No deletion is a stop-gate.** No guard, baseline or allowlist references `updateExpenseCurrency`, `isBeta`,
  `getBetaFeatures`, `getNewFeatures`, `label_beta` or `executeVoiceQuery`. The search covered `config/`,
  `scripts/`, `docs/ci/` and the tests. The only hit is `app/lint-baseline.xml:765-766`, a stale MissingTranslation
  entry for `label_beta`.
- Near-misses checked:
  - `config/baselines/cancellation.json` per-file catch counts (G-CANCEL-01/02). They are unaffected because the
    deleted stub has no try/catch.
  - `CancellationSafetyArchitectureGuardTest.kt:146` allowlists `NaturalLanguageSearchViewModel.kt`
    (LAUNCH_CE_NO_RETHROW, MIT-034, expiry 2026-12-31). Its check only verifies that the file exists. Do not touch it.
- **Lint facts (relevant to Q-T):**
  - Config is in `app/build.gradle.kts:242-245`: `baseline = file("lint-baseline.xml")`. There is no `lint.xml` and
    no `warningsAsErrors`.
  - `scripts/verify_lint_baseline_policy.py` fails on non-MissingTranslation baseline IDs, or when the
    MissingTranslation count rises above `--max-missing-translations 2219`. A decrease passes.
  - UnusedResources must be fixed in source, never baselined (`docs/ci/GUARD_VIOLATION_AUDIT.md:256`).
  - Do not hand-edit the baseline (`docs/ci/GUARD_VIOLATION_AUDIT.md:262`).

## Per item
| Item | Files | Lane conflict | Tier | Validation |
|---|---|---|---|---|
| UF-13 | `PrivacySettingsScreen.kt:48-57` (KDoc + TODO) | Owned later by app-lock S12/S22 and capture-health S7/S19. Header-only hunk, so low risk. Land before AL S10-S12. | T1 | none |
| UF-47 | `ManualExpenseRepository.kt:70,185` | In the write sets of ai-fixes (`ai-fixes-plan.md:127`) and MEC A2/B1. Medium rebase risk. | T1 | none |
| UF-53 | `AndroidSpeechInputGateway.kt:112-113` | none | T1 | none |
| UF-45 | `AccountantReportPdfExporter.kt:40-42` | Owned later by MC 1b-3 and loan S9. The TODO combines three claims, and one of them (UF-54) waits for MC 1a-G. The PDF deductible-total gap is still real. | T1 | none. **Move to QF-3c.** |
| Doc fix | `workflows/active/opportunity-scan-handoff.md:57` | Finished handoff. Owner OK needed (other sessions active). | T1 | none |
| UF-51 | `MultiCurrencyRepository.kt:1115-1123` | none; zero callers; not an interface member | T1 | compile |
| UF-10 (opt) | `FeatureConfig.kt:25,35,281-282`; `HomeScreen.kt:1645,1708,1760-1769`; `strings.xml:78`; `docs/architecture/NAVIGATION_ARCHITECTURE.md:72` | `HomeScreen.kt` is owned by briefing S3/S4 and CH S7/S19. `FeatureConfig.kt` is touched by loan S10a. `strings.xml` is under the doc/strings lock. Our hunks are small and disjoint. | T1 | compile + targeted + lint |
| UF-11 VM-only (opt) | `NaturalLanguageSearchViewModel.kt:155-160`, plus the `launch` import at :17 | none (nl-quick-add defers voice to inc 2 and doesn't write this file) | T1 | compile + targeted |
| UF-11 whole stack (opt) | `SpeechInputGateway.kt`, `AndroidSpeechInputGateway.kt`, `di/ServiceModule.kt`, engine voice methods, VM, 2 tests, 5 architecture docs | none | T2 | compile + targeted + static-guards. Not recommended (voice-search inc 2 may reuse it). |

Pair the `label_beta` deletion with the removal of its use. Keeping the string with no use would create an
UnusedResources violation.

## Split and order
- **QF-3a** (comments/docs, no validation; Lane B W1, first):
  - UF-53
  - UF-13
  - UF-47, only if it lands before ai-fixes B and MEC A2; otherwise fold it into the owning slice
  - the opportunity-scan doc fix, after owner OK
- **QF-3b** (deletions; same writer, after QF-3a; before AL S10):
  - UF-51
  - UF-10, after D-BETA and coordinated with the strings-lock holder
  - UF-11 VM-only method
  - Validation, as one batch run through validation-runner:
    1. `compile`
    2. `targeted-unit-test` with `*FeatureConfig*`, `*DeferredFeatureGate*`, `*NaturalLanguageSearch*`,
       `*CancellationSafetyArchitectureGuardTest*`, `*MultiCurrency*`
    3. `lint`, only if UF-10 is included
    4. `static-guards` once at the end
- **QF-3c** (after MC 1a-G): UF-54 and the UF-45 TODO rewrite, done together.

Gates: no guardians and no reviewer-strict (T1); cheap diff review. The guard freeze is not triggered.

## Owner questions
1. D-BETA: remove (recommended) or keep?
2. D-VOICE: VM method only (recommended), the whole stack, or keep everything?
3. OK to edit the finished `opportunity-scan-handoff.md`?
4. UF-47: land the comment fix now, or fold it into the ai-fixes / MEC slice?
5. UF-45: defer to QF-3c (recommended)?
