# Prompt: Runtime, device, optimized artifact and stabilization evidence

Run the planning/analysis in Sonnet 5.5/high; route high-risk scenario judgment
to the appropriate Opus guardian. This prompt authorizes a plan and evidence
review, not test execution, device data clearing, signing changes or store upload.
Validation-runner executes only a human-approved queue. Never invoke Gradle,
connected tests, lint, Python tests, or architecture guards directly.

## Plan actual evidence, not more static opinions

- Use focused regressions first, real Room/transaction integration where needed,
  full supported-chain migrations, fresh-install schema parity, and backup/
  restore exactness. Old/current schemas and unsupported-version refusal must
  preserve data; do not assume first release permits destructive reset.
- Full canonical static guards are required separately from app-check. Inspect
  skips and run known/hanging/legacy test families; trusted-tests or a subset of
  shards are not a full-suite substitute. Prove supported shard coverage and
  execute the full suite when required; no test is silently retired.
- Plan device/API coverage from actual minSdk/targetSdk and shipped permissions.
  Include at least oldest supported/highest-risk and current Android behavior,
  plus a physical-device sample where feasible. Human approves precise matrix.
- Test denied/revoked notification and location permissions, offline/cloud-denied
  modes, settings/currency changes, process death/restart, duplicate taps/retry,
  restore/write overlap, canceled scan/import/export, unavailable and valid-empty
  states. Optional posting must not gate unrelated DB work.
- Exercise every shipped feature row from coverage-plan.md. Observe UI as well
  as persisted state: navigation/deep links, back, rotation/state recreation,
  dark/light, large font/accessibility, locale/decimal separators/time zones/DST.
- Use synthetic datasets: empty, small realistic, large history, multiple
  currencies, stale/missing rates, legacy provenance and corrupt archives.
  Exact totals/conservation use independently calculated expected results.
- Budget performance evidence: cold start, UI responsiveness, long lists/large
  OCR/import/export, memory, background drain, retries and resource cleanup. Set
  human-approved bounds before testing, not after seeing results. Distinguish
  measured regressions from unverified suspicions.

## Optimized release artifact gate

Debug PASS does not prove the shipped app. Verify release variant, R8/shrinking,
reflection/serialization/Room/DI keep rules, providers/manifest, resources,
debug-only UI/logging, version metadata, network/privacy defaults, dependencies,
license notices and human-approved distribution requirements.

At kit preparation (2026-10-13), app/build.gradle.kts configures minification and
resource shrinking in release with a debug signing configuration for CI
verification. This is not proof of production signing. Confirm the protected
production signing process without exposing keystores/passwords or assuming the
CI APK is distributable. Runner currently has no release APK/AAB profile. The
existing scripts/verify_release_artifact.py and CI release-check lane define
artifact checks; release_artifact is declared external, not a static-suite or
ordinary registered-guard bridge pass. Reuse current verified CI evidence or
propose a narrow approved durable runner integration, strict review it, then
execute through validation-runner. Do not change the runner from this planning
prompt, invent -GuardId release_artifact execution, or bypass the wrapper.

Record artifact SHA-256, source SHA/fingerprint, variant, version, signing
certificate/public identity where safe, environment and device. Smoke the same
optimized artifact planned for distribution; connected debug tests alone are
not equivalent. Packaging/signing is human-approved; no automatic publish/tag.

## Controlled beta and stabilization

After confirmed fixes, freeze feature changes and let the human owner exercise
the optimized candidate using synthetic/demo accounts and opt-in testers.
Approve duration/device/sample/scenario matrix first. A suggested initial
stabilization window is 3-7 days of repeated critical journeys, not a guarantee
or automatic release threshold. Use injected clocks for calendar boundaries;
do not wait a month to test recurrence/rollover.

Record crashes, ANRs, state/money inconsistencies, confusing/unavailable UX,
privacy behavior and performance issues with sanitized structured diagnostics.
Do not upload raw user financial data to models. New findings go through the
same verification and bounded remediation loop. A new artifact restarts affected
evidence and smoke/beta coverage; do not combine old beta with a changed binary.

## Deliverables and terminal evidence

Return the approved queue proposal, runtime matrix, release-artifact matrix,
beta observations, known skips, coverage gaps and gate recommendation. For
every execution record exact command, RunId, PASS/FAIL/NOT RUN/INFRA, exit,
complete marker, logs, fingerprint, cache state and actual tests executed.
RUNNING means poll that RunId; never start a replacement to hide it.

Freeze source/tests/config/docs and handoff before the final queue. Persist
generated logs/reports only where verified excluded from runner fingerprinting.
Any later fingerprinted edit requires new matching evidence; source validation
cannot be inferred from a total test count assembled across different snapshots.
