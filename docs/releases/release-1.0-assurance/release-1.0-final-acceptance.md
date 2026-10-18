# Release 1.0 — final acceptance record

```text
Workflow / phase: release-1.0-assurance / Phase 7 final acceptance (attempt 3)
Code candidate: 01fae80c991049556164c087e64f4a32ff40e7c8 (branch release-1.0-wt)
Reviewed tree: 8879c923 (01fae80c + records only)
Reviewer: release-final-reviewer, Opus 5.5 / max, independent of discovery and implementation
Date: 2026-10-18
```

## Verdict

**CONDITIONAL.**

- **Engineering:** no P0 or P1 defect found in 01fae80c. The code candidate is supportable as the
  1.0 code basis.
- **Distribution readiness:** depends on the owner-only conditions below. Their status is recorded
  in the owner decisions section.

Earlier attempts:

- Attempt 1 hit its turn limit and produced no report.
- Attempt 2 reviewed 6b44823e and returned CONDITIONAL / "not ready". Since then, its engineering
  gaps have been closed:
  - Connected tests now pass on the final candidate.
  - HZ-03 F1 is fixed in R32.
  - The records are reconciled.

## Final gate evidence (all on 01fae80c, clean tree, `complete.marker` present)

| Gate | Run | Result |
| --- | --- | --- |
| static-guards | vr-20261018-013948-2a96efea | PASS 25/25 |
| app-check | vr-20261018-015917-fae8ab55 | PASS |
| legacy-tests | vr-20261018-181715-c051f10c | PASS 276/0 |
| connected-tests (wave5-api35, API 35) | vr-20261018-182059-2b39c1e1 | PASS 376 / 0 failed / 9 skipped |
| assemble-release | vr-20261018-183624-b57e7eb7 | PASS |
| release-artifact-evidence | vr-20261018-183848-1a44c46e | PASS |

- **Connected-test skips:** all 9 are `DatabaseMigrationTest` legacy chains (v1–v51). Each is behind
  `assumeTrue(hasSchema(N))`, below MIGRATION_BASELINE 145, and unsupported by design. The reviewer
  verified this in the JUnit XML.
- **Release APK** `app-release.apk`:
  - SHA-256 b364a144…f18f
  - R8-minified (mapping 5cef63c7…), not debuggable
  - minSdk 26, targetSdk 35, version 1.0 (1)
  - Signed with the Android **debug** certificate.

## Reviewer checks (R32 and regression spot-checks)

- **R32 restore cancel path:**
  - `CancellationException` is always rethrown.
  - Cleanup runs under NonCancellable. A cleanup failure latches CRITICAL.
  - A post-swap cancel exits to persisted restart-required mode. The journal and evidence are
    kept; only the staged DB files are deleted.
  - Next-start journal recovery handles each post-swap state: SWAPPING/VERIFYING roll back,
    ASSETS_RESTORING resumes and commits.
- **R32 entry guard:** creates no permanent lockout.
- **Privacy:** only bounded reason codes are used.
- **FinancialStressForecastEngine:** cancellation-safe, and its cancellation-baseline entry is
  retired.
- **Regression spot-checks:** RG01A-1, RG12-01 and the R27 drain order still hold.

## Records reconciliation (done after the review)

The regression ledger rows CL-11/RG12-01, RG01A-1, RG01-N1, RG04B-1, CL-07/RG11-H2 and RG17B-1 are
now CLOSED. Each has a disposition that points to its fix batch (R1, R2, R4/R4b, R5, R12 and R13)
in the verification ledger.

## Owner-only conditions and owner decisions (2026-10-18)

| # | Condition (reviewer) | Owner decision / status |
| --- | --- | --- |
| 1 | Production signing of 01fae80c; re-run assemble-release + evidence with `debug_certificate=false` | **Deferred by owner** ("ok for the APK for now"). Still required before any store or public distribution. |
| 2 | RT-05 full smoke on the production-signed minified APK (API 26 + 35) | Open; follows condition 1. |
| 3 | RT-03 backup → uninstall → install 1.0 → restore with real data; versionCode not lower than any installed build | **Waived for now** (no manual emulator access). In-place upgrade of the owner database v145 → v151 passed on an emulator with equal counts. Make a backup before installing the beta. |
| 4 | Device probes P3/P4/P5/P9/P14; complete P6/P10 | **Waived for now**. These are covered by unit/integration tests and strict reviews, and carried as residual risk into the beta. |
| 5 | Explicit approval of every deferred P2 | **Approved: all deferrals move to 1.0.1** (`release-1.0-user-notes.md` Known limitations and remediation-plan follow-ups). Deferred P2s: CURR-6 (amount sort/filter after a home-currency change) and the ~60 check-only saves not drained by backup. The rest are P3. |
| 6 | Notification-capture consent default ON | **Kept ON by owner.** Rationale: actual capture also requires the Android notification-listener grant, which is OFF by default and user-granted in system settings. |
| 7 | Beta / performance / accessibility / PDF | **Short beta approved** (RT-06) on the signed build. Performance, accessibility and device PDF are not separately tested for 1.0; the beta covers them. |

## Release status

At the owner's request, 01fae80c plus these records were merged (fast-forward) into `bug-fixes` as
the 1.0 code baseline. This is not a production-distribution sign-off. Before public release, the
build still needs:

- production signing (condition 1);
- the signed-artifact smoke test (condition 2);
- the short beta.

## Residual risks (low)

- **HZ04-Q** (RG11-H2 class): its runtime evidence comes from the instrumented nested-transaction
  test on d7979e89. R32 does not touch that code.
- **Post-swap cancel:**
  - The DB can be reopened in the same process. Writes stay blocked, and the next start recovers.
  - A cancel during ASSETS_RESTORING needs two restarts to return to normal.
  - A cancel during VERIFYING rolls back a restore that may have been valid (stated in the user
    notes).
- **Reset and legacy import:** both still latch CRITICAL if cancelled after their destructive step.
  Neither is reachable in release builds (follow-up HZ-03 F1b).
- **Device probes:** the probes not run on a device rely on JVM/Robolectric tests and reviews.
