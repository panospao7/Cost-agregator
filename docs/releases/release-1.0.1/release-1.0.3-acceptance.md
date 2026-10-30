# Release 1.0.3 — acceptance record (hotfix: ECB daily refresh on Android)

```text
Candidate:  hotfix/1.0.3-ecb-xml (fix 0d6575f1, version 63e8abee, minors fe3cb026, device-test gate 99eec4af; this record follows)
Base:       8e449c6f (bug-fixes = Release 1.0.2)
Version:    1.0.3 (code 4)
Scope:      one defect, owner-approved 2026-10-30 (owner-approvals item 95).
Status:     CONDITIONAL — same owner-only conditions as 1.0.2 (production signing, manual smoke, store upload).
```

## Defect

`CurrencyRatesRepositoryImpl.secureDocumentBuilderFactory()` called
`setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)`. Android's Harmony `DocumentBuilderFactory`
throws `ParserConfigurationException` for that feature, so every daily ECB parse failed on devices.
Rates did not update automatically. The bug has been present since fc11cf9d (2026-04-04).
JVM unit tests could not see it. It was found by the MEC 1b device test (vr-20261030-134202-13496e37, wave5-api35).

## Change

- Parser hardening is applied best-effort per setting (unsupported settings are ignored silently).
- Explicit fail-closed checks run before any parser:
  - the body is read with a 256 KiB cap;
  - it is decoded as strict UTF-8, rejecting malformed bytes, NUL and a leading BOM;
  - any `<!DOCTYPE` or `<!ENTITY` is rejected;
  - the parse runs from an `InputSource(StringReader)`, so the parser cannot re-detect the encoding.
- Failures map to the same refresh failure as before. Nothing new is logged, and CancellationException behaviour is unchanged.
- Tests:
  - JVM: the DTD, encoding, size and boundary cases.
  - `EcbDailyRefreshDeviceTest`: the Android parser accepts a valid payload and rejects a DOCTYPE; a live ECB refresh is opt-in via `debug.costagg.live_ecb=1`.
- Version 1.0.2 (3) -> 1.0.3 (4). No schema, guard, baseline, allowlist, permission or network-endpoint change.

## Evidence

| Profile | Run | Revision | Result |
| --- | --- | --- | --- |
| targeted (currency classes) | vr-20261030-154301-3ad20dfa | fe3cb026 | PASS |
| connected-test-class EcbDailyRefreshDeviceTest (live) | vr-20261030-155433-d6204294 | fe3cb026 | PASS, 3/0/0 (live refresh executed) |
| static-guards | vr-20261030-155552-fbcd2de5 | fe3cb026 | PASS |
| unit-tests | vr-20261030-161125-5b878e3b | fe3cb026 | PASS |
| app-check | vr-20261030-161927-f3950b86 | fe3cb026 | PASS |
| assemble-release | vr-20261030-164736-ce634878 | fe3cb026 | PASS |
| release-artifact-evidence | vr-20261030-165024-e3f88e77 | fe3cb026 | PASS; APK SHA-256 `17d62826…9f56`; 1.0.3 (4); not debuggable; 13 permissions; debug certificate |
| connected-tests (emulator-5554, wave5-api35) | vr-20261030-165040-08b9564b | fe3cb026 | PASS, 162/0/0 |
| connected-test-class EcbDailyRefreshDeviceTest, property unset | vr-20261030-171902-58f00bc9 | 99eec4af | PASS, 3 tests, live test SKIPPED (gate verified) |
| connected-test-class EcbDailyRefreshDeviceTest, `debug.costagg.live_ecb=1` | vr-20261030-174723-003c59c3 | 99eec4af | PASS, 3/0/0 (live refresh executed); property reset to 0 afterwards |

Disclosure: the live device test was not skipped in the full suite on fe3cb026. The class-argument gate is ineffective under the Test Orchestrator. 99eec4af replaces it with an opt-in device property; that commit is androidTest-only. The owner's phone was not used by any run.

## Review

- privacy-security-guardian: ACCEPT after the strict-UTF-8 fix (UTF-16 bypass closed), reviewed on the MEC branch with the identical design.
- reviewer-strict (Opus xhigh) on 0d6575f1 + 63e8abee: ACCEPT-WITH-FIXES. Both minors were applied in fe3cb026 (explicit `'﻿'`, live-test gating). The gating was then corrected in 99eec4af.
- Follow-up, not in 1.0.3: a deeply nested body near 256 KiB could exhaust the Android DOM builder's stack. Exploiting it needs a compromised ECB or TLS.

## Owner conditions (unchanged)

- Production signing (debug certificate).
- Manual smoke on the release APK: confirm the exchange-rate refresh in Settings > Currency.
- Store upload.
