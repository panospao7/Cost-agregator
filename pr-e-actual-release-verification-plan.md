# PR E — Add Actual Release Verification

## 1. PR definition

**Suggested title:**  
`ci(release): build, inspect, sign, and smoke-test the actual release artifact`

**Base:** Successful final commit of PR D.

**Reference snapshot:**  
`ebb5aa93348282b31c1c669d1bf1271d584b9eb0`

**Primary issues:**

- MIT-003 — Release architecture guards
- MIT-022 — Cloud fail-closed enforcement
- MIT-026 — Production logging and error sanitation
- MIT-027 — Safe receipt cloud uploads
- MIT-028 — Release security hardening
- MIT-044 — Release-disabled bank demo/stub behavior
- MIT-069 — Privacy edge-case hardening

**Estimated effort:** 8–14 engineering days.

---

# 2. Current release-verification gap

At the reference snapshot:

- The release build has minification disabled.
- Resource shrinking is not configured.
- CI builds, lints, and checks only the debug variant.
- Instrumented tests run the debug build and are non-blocking.
- The DI/release guard is source-regex based rather than artifact based.
- No CI job generates and inspects the shipping AAB or its generated APKs.
- The manifest does not explicitly attach a release network-security configuration.
- The app targets API 35 but supports API 26, where cleartext behavior must not be left to platform defaults.
- Existing ProGuard rules broadly preserve Android component and Hilt classes, potentially reducing the value of R8 optimization. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/app/build.gradle.kts))

The current manifest already contains several good properties, including `allowBackup="false"`, a non-exported `FileProvider`, a non-exported rescue activity, and protected notification-listener service exposure. It also exposes `MainActivity` through custom-scheme deep links, which means the merged release manifest and route validation must be inspected as security contracts. ([github.com](https://github.com/panospao7/Cost-agregator/blob/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/app/src/main/AndroidManifest.xml))

The goal of PR E is not merely to prove that release source code compiles. It must prove the properties of the **actual optimized artifact** users would install.

---

# 3. Objective

After PR E, required CI must prove that:

1. The real release build compiles.
2. Release lint and release unit tests pass.
3. R8 optimization and resource shrinking are active.
4. A release AAB and APK are produced.
5. The artifacts are signed with a non-debug verification key.
6. APKs generated from the AAB install and launch successfully.
7. The merged manifest is release-safe.
8. Cleartext traffic is disabled.
9. Debug, demo, fake, stub, test, and unsafe no-op implementations are absent.
10. No production logging or HTTP body logging is packaged.
11. No secrets or credentials are present in code, resources, assets, DEX strings, manifests, or metadata.
12. Release DI resolves only approved production implementations.
13. Cloud requests can only use privacy-prepared payloads.
14. Exported components and permissions match an exact policy.
15. Backup cryptography and restored token behavior satisfy release contracts.
16. Dependency and build-input integrity checks pass.
17. Every check produces machine-readable evidence.
18. A stable `Release Verification` status check blocks merging.

Android recommends enabling R8 optimization for release builds and testing the optimized version before publishing. It recommends enabling both code minification and resource shrinking. ([developer.android.com](https://developer.android.com/build/shrink-code))

---

# 4. Core verification model

PR E should enforce three complementary layers.

## Layer 1 — Source and configuration enforcement

Prove:

- Legal release bindings.
- Legal network request construction.
- No source-level secret inclusion.
- No debug/test release dependencies.
- Correct Gradle release configuration.
- Correct logging API ownership.

## Layer 2 — Artifact inspection

Inspect the generated AAB and bundle-generated APKs for:

- Merged manifest behavior.
- DEX classes and strings.
- Resources and assets.
- Signing certificates.
- Permissions and exported components.
- Packaged dependencies.
- R8 outputs.
- Cleartext/network configuration.
- Debug/demo/test artifacts.

## Layer 3 — Runtime smoke verification

Install APKs generated from the AAB and prove:

- Installation succeeds.
- Process startup succeeds.
- Hilt application graph starts.
- Room opens.
- WorkManager initialization succeeds.
- Main navigation routes do not crash.
- R8 did not remove required reflective/runtime components.
- No startup fatal exception or ANR occurs.

Passing only one or two layers is insufficient.

---

# 5. Non-goals

Do not include:

- Publishing to Google Play.
- Automatic production rollout.
- Building real bank OAuth.
- Full cloud-provider redesign unrelated to release safety.
- Fixing all architecture backlog findings.
- Certificate pinning without a domain ownership and rotation plan.
- Uploading production signing keys or real-signed artifacts from untrusted pull requests.
- A count-only release-security baseline.
- A broad artifact allowlist.
- MobSF or another externally hosted scanner as the sole release gate.

Do not:

- Use the debug signing key.
- Commit a keystore or password.
- put signing passwords directly in workflow commands.
- set `continue-on-error`.
- hide R8 failures with `-dontwarn **`.
- add global `-keep class **`.
- add a lint or release-security baseline merely to obtain green CI.
- trust source configuration without inspecting the generated artifact.
- call a debug-signed, non-minified build “release equivalent.”

---

# 6. Workstream E1 — Define a canonical release policy

## 6.1 Create a release policy file

Create:

`config/release-verification-policy.yml`

It should contain:

- Application ID.
- Minimum and target SDK policy.
- Required release build flags.
- Expected exported components.
- Expected component permissions.
- Allowed Android permissions.
- Forbidden permissions.
- Required manifest attributes.
- Forbidden manifest attributes.
- Allowed URL schemes.
- Forbidden endpoint schemes.
- Forbidden class families.
- Forbidden packaged dependencies.
- Forbidden resource and asset patterns.
- Signing policy.
- Logging policy.
- Secret-detection policy.
- Expected release entry points.
- Exact structural exceptions.

The policy must be reviewed through CODEOWNERS.

## 6.2 Required exported-component policy

Based on the final merged release manifest, expected exported components should initially be limited to:

- `MainActivity`, exported as the launcher/deep-link entry.
- `NotificationCaptureService`, exported only with the exact notification-listener binding permission.

All other activities, services, receivers, and providers should be non-exported unless a specific release requirement proves otherwise.

The verifier must fail if:

- A new exported component appears.
- A protected component loses its permission.
- A provider becomes exported.
- A receiver becomes externally invokable.
- The rescue activity becomes exported.
- A component has no explicit exported policy.

## 6.3 Permission policy

Create an exact allowlist for intentional permissions.

Review current uses such as:

- Internet and network state.
- Foreground service permissions.
- Notifications.
- Boot completion.
- Wake lock.
- Camera.
- Foreground location.
- Legacy storage permissions restricted by `maxSdkVersion`.

Explicitly forbid high-risk permissions unless separately approved, including:

- `QUERY_ALL_PACKAGES`
- `MANAGE_EXTERNAL_STORAGE`
- `REQUEST_INSTALL_PACKAGES`
- SMS and call-log permissions
- overlay permissions
- accessibility-service binding
- background location
- package deletion or installation
- broad account access

Transitive manifest additions must fail even if no source manifest changed.

## 6.4 Deep-link policy

Treat every `expensetracker://` route as untrusted input.

For every exposed host:

- Define whether authentication or unlocked app state is required.
- Validate all IDs and parameters.
- Reject unknown query parameters where practical.
- Prevent links from directly performing mutations.
- Route sensitive actions through a confirmation screen.
- Ensure malformed values cannot crash navigation.
- Ensure private data is not included in returned intents or error output.

Add release smoke cases for every declared host.

---

# 7. Workstream E2 — Make the release build genuinely release-safe

## 7.1 Enable optimized release configuration

In the release build type:

- Enable code minification.
- Enable resource shrinking.
- Use the optimized default Android ProGuard configuration.
- Apply reviewed project rules.
- Keep `isDebuggable` explicitly false.
- Disable test coverage.
- Ensure debug source sets are excluded.
- Ensure no debug application ID suffix or debug manifest overlay is applied.
- Preserve dependency metadata in the APK/AAB unless there is a documented reason to remove it.

If the final Android Gradle Plugin version requires explicit optimized-resource-shrinker activation, enable it. Derive this decision from the final AGP version after PR D rather than hardcoding assumptions.

## 7.2 Remove temporary minification exception

If PR B left an exact temporary exception for disabled minification, PR E must remove it.

The DI/release guard must report zero minification findings.

## 7.3 Audit existing ProGuard/R8 rules

Review every current rule.

In particular:

- Remove broad rules preserving all activities, services, receivers, providers, or Hilt manager internals unless proven necessary.
- Rely on manifest and library consumer rules where possible.
- Remove speculative Compose rules that do not correspond to real reflection.
- Avoid broad package-wide keep rules.
- Avoid broad warning suppression.
- Add narrow keep rules only after reproducing an actual release failure.
- Document the runtime behavior each retained rule protects.
- Add a regression test for every project-owned keep rule.

Generate and retain for CI inspection:

- Mapping report.
- Usage/removal report.
- Seeds report.
- Configuration report.
- Missing-class diagnostics.

Do not upload production mapping files to a public release location. CI artifacts should use restricted retention.

## 7.4 Release-build correctness tests

Make these blocking:

- Release Kotlin/Java compilation.
- `lintRelease`.
- Release JVM tests.
- Release resource processing.
- `assembleRelease`.
- `bundleRelease`.
- R8 processing.
- Bundle validation.

If release compilation differs from debug compilation because of source-set bindings, both paths must be tested independently.

---

# 8. Workstream E3 — Enforce source-set separation

## 8.1 Required source-set ownership

Use:

- `src/main` for real shared production code.
- `src/debug` for debug tools, demo data, debug diagnostics, and fake providers.
- `src/release` for explicit fail-closed production bindings when necessary.
- Test source sets for mocks and fixtures.

Do not rely only on `BuildConfig.DEBUG` to protect a class that is still packaged in the release artifact.

## 8.2 Move forbidden implementations

Inventory classes and bindings containing concepts such as:

- Debug
- Demo
- Fake
- Mock
- Stub
- Test
- Preview
- InMemory
- NoOp

Classify each as:

1. Debug/test only — move out of `src/main`.
2. Production-disabled behavior — replace with a typed `Disabled` or `Unavailable` implementation.
3. Legitimate structural implementation — document and test it exactly.
4. Real production implementation — retain.

A “disabled” implementation must:

- Return a typed unavailable result.
- Never generate fake financial data.
- Never claim a network operation succeeded.
- Never persist demo rows.
- Never expose placeholder tokens.
- Never bypass privacy or diagnostics policies.

## 8.3 Bank integration release policy

Until production OAuth is complete:

- Demo or stub bank connectors must not be present in release DEX.
- Connect actions must return an explicit production-unavailable result.
- Existing saved connections must not be processed by demo logic.
- Release UI must clearly mark the feature unavailable.
- No placeholder token or provider response should be created.
- No simulated successful sync may occur.

This closes release-packaging risk without pretending MIT-044 is fully implemented.

---

# 9. Workstream E4 — Prove release DI bindings

## 9.1 Create a release-binding contract

Create a machine-readable binding policy containing:

- Interface or port.
- Expected release implementation.
- Allowed qualifier.
- Scope.
- Whether the implementation may access network, database, files, or cloud.
- Whether it is release-enabled or deliberately disabled.

## 9.2 Extend DI verification

The release DI guard must fail when:

- A debug/test implementation is bound in release.
- Multiple ambiguous implementations exist.
- An empty, fake, demo, or stub implementation is selected.
- A release-disabled capability returns success.
- A debug qualifier is reachable.
- A test module appears in the release classpath.
- A binding depends on debug-only configuration.
- An entire module or Gradle file is exempted.

## 9.3 Runtime graph proof

Release startup smoke tests must prove that:

- `MainApplication` is created.
- Hilt generated components load.
- Worker factory injection succeeds.
- Database construction succeeds.
- Network clients resolve.
- Privacy and security services resolve.
- Release-disabled capabilities return their documented unavailable state.
- No missing class or reflection error occurs after R8.

## 9.4 Artifact proof

Inspect DEX packages and fail if forbidden implementation FQNs or exact class fingerprints appear.

A source guard passing is not enough if a forbidden class remains packaged but unreachable.

---

# 10. Workstream E5 — Enforce production logging boundaries

## 10.1 Canonical logging API

Require production code to use a typed safe diagnostics abstraction.

It should accept:

- Fixed reason code.
- Fixed operation stage.
- Severity.
- Approved enum metadata.
- Numeric counts where safe.
- Exception class category where needed.

It must reject:

- Arbitrary message strings.
- Raw `Throwable`.
- `Throwable.message`.
- Request or response bodies.
- Receipt/OCR/email text.
- Merchant/item/category names.
- Paths and filenames.
- URLs with parameters.
- Tokens or account identifiers.

## 10.2 Remove direct logging

Block direct production use of:

- `printStackTrace`
- `println`
- `System.out` and `System.err`
- Android `Log`
- `Timber.DebugTree`
- Timber calls carrying raw values or exceptions
- HTTP logging interceptors
- custom interceptors that record headers or bodies

Move debug logging implementations to `src/debug`.

The release implementation should either:

- Emit strictly typed sanitized diagnostics, or
- Be a safe no-output implementation where durable diagnostics are not required.

## 10.3 Artifact checks

Fail if the release artifact contains:

- `Timber.DebugTree`
- HTTP logging interceptor classes
- known debug logger implementations
- test log collectors
- fixed strings indicating body/header dumping
- known diagnostic messages containing sensitive field labels

Do not use broad R8 “no side effects” rules to hide unsafe logging arguments. Remove the unsafe logging path at source.

## 10.4 Runtime check

Capture synthetic release smoke logcat and fail on:

- Fatal exceptions.
- ANRs.
- debug-only startup messages.
- raw request/response markers.
- sentinel PII values used by tests.
- stack traces from handled expected failures.

Android’s release guidance calls for logging to be disabled or removed and release builds to be non-debuggable. ([developer.android.com](https://developer.android.com/studio/publish/?utm_source=openai))

---

# 11. Workstream E6 — Enforce network security

## 11.1 Add release network-security configuration

Add a release network-security configuration that:

- Disables cleartext traffic globally.
- Trusts only system certificate authorities by default.
- Does not trust user-installed CAs in release.
- Contains no debug overrides in the release resource.
- Does not disable hostname verification.
- Does not install a permissive trust manager.

Reference it from the application manifest and explicitly set cleartext traffic to false.

Because the app supports API 26, relying only on platform defaults is unsafe: Android documents cleartext as enabled by default through API 27 and disabled by default from API 28. ([developer.android.com](https://developer.android.com/privacy-and-security/security-config?utm_source=openai))

Debug-only certificate overrides, if required for local development, must live exclusively in `src/debug`.

## 11.2 Endpoint inventory

Create a canonical endpoint registry.

For each endpoint, record:

- Feature owner.
- Scheme.
- Host policy.
- Whether the endpoint is fixed or user-configurable.
- Data classification.
- Cloud capability required.
- Payload policy required.
- Timeout policy.
- Whether authentication is used.

Fail release verification on:

- `http://`
- cleartext WebSockets
- localhost
- emulator loopback hosts
- private development IPs
- placeholder domains
- debug proxies
- URL strings containing credentials
- disabled hostname verification

User-configurable cloud endpoints must require HTTPS and must be validated before persistence and before each request.

## 11.3 OkHttp client policy

Inventory every `OkHttpClient` and every direct client builder.

Require:

- Central DI ownership.
- No logging interceptor.
- Bounded connect/read/write timeouts.
- Safe redirects policy.
- No permissive TLS configuration.
- No raw authenticator logging.
- No sensitive disk caching for cloud AI traffic unless explicitly encrypted and approved.
- Separate clients where location caching and cloud confidentiality policies differ.

The reference code already provides separate location and cloud clients; retain that separation while adding enforceable policies. ([github.com](https://github.com/panospao7/Cost-agregator/blob/ebb5aa93348282b31c1c669d1bf1271d584b9eb0/app/src/main/java/com/yourname/expensetracker/di/NetworkModule.kt))

## 11.4 Certificate pinning decision

Do not add pinning automatically.

Only enable pinning for owned, stable domains when:

- Backup pins exist.
- Rotation is documented.
- Expiry is monitored.
- Emergency recovery is possible.
- Public third-party endpoints are excluded.

Record the decision even if the result is “no pinning.”

---

# 12. Workstream E7 — Enforce prepared cloud payload ownership

## 12.1 Legal cloud path

The only legal write path should be:

`Domain input → privacy/capability policy → PreparedCloudPayload → approved request factory → OkHttp`

Providers must not construct cloud request bodies directly from:

- OCR text.
- Receipt images or paths.
- Bank statement text.
- Email bodies.
- Merchant names.
- Arbitrary user prompts.
- Files or content URIs.

## 12.2 Add a request-body guard

Create or extend a blocking guard that detects:

- Direct `RequestBody` creation.
- Direct string/body conversion.
- Direct multipart body creation.
- Direct POST/PUT/PATCH bodies.
- Raw file request bodies.
- Raw image-path request bodies.
- Direct provider JSON serialization.
- Request construction outside approved factories.

Exact safe exceptions may exist for non-sensitive protocol operations, but each must be finding-scoped.

## 12.3 Receipt upload policy

Receipt cloud upload must accept:

- A managed receipt asset ID, or
- A verified app-owned URI.

It must verify:

- Asset belongs to the receipt asset store.
- MIME type by content, not filename only.
- Size limits.
- No path traversal.
- No arbitrary filesystem path.
- Privacy capability before file access.
- Payload policy before request construction.

## 12.4 Tests

Add negative tests for:

- Arbitrary local file.
- External-storage path.
- Unsupported URI authority.
- MIME mismatch.
- Oversized file.
- Privacy revoked after selection.
- Cloud capability disabled.
- Redaction failure.
- Raw provider payload construction.

The source guard and tests must remain blocking even though the final artifact is also scanned.

---

# 13. Workstream E8 — Build and sign verification artifacts safely

## 13.1 Pull-request signing model

For pull requests:

- Generate an ephemeral CI release-verification keystore at runtime.
- Use a certificate identity that is clearly not the Android debug certificate.
- Keep passwords in temporary files or masked environment variables.
- Never commit the keystore.
- Never upload the keystore.
- Delete temporary signing material with an always-run cleanup step.
- Record only the public certificate fingerprint.

This key proves signing and installability; it is not the production upload key.

## 13.2 Release-candidate signing model

Create a separate protected release-candidate workflow for:

- Signed tags.
- Manual dispatch from a protected branch.
- Approved GitHub environment.
- Restricted maintainers.
- Real upload-key secrets.

It must:

- Verify expected upload-certificate SHA-256 fingerprint.
- Avoid printing passwords.
- Avoid exposing the keystore through artifacts.
- Fail if the debug or ephemeral verification certificate is used.
- Produce artifact checksums and provenance.

Android requires APKs to be signed, while AAB upload workflows use an upload key and can use Play App Signing for distributed APKs. ([developer.android.com](https://developer.android.com/studio/publish/app-signing?authuser=6&utm_source=openai))

## 13.3 Artifact set

Build once and pass the same outputs to all verification jobs:

- Release AAB.
- Release APK.
- R8 reports.
- Merged release manifest.
- Dependency report.
- Build provenance report.
- SHA-256 checksum file.

Do not rebuild independently in each downstream verification job.

## 13.4 Signing verification

Verify:

- APK signature validity.
- Expected signature schemes for supported Android versions.
- Certificate is not the Android debug certificate.
- Certificate validity dates are acceptable.
- AAB JAR signature is valid.
- Bundle-generated APKs use the expected verification certificate.
- Every generated split APK verifies successfully.

---

# 14. Workstream E9 — Inspect the actual AAB and APKs

## 14.1 Create an artifact-verification suite

Create:

`scripts/release/verify_release_artifacts.py`

Supporting modules should inspect:

- AAB contents.
- APK set contents.
- Merged manifest.
- DEX class inventory.
- DEX strings.
- Resource strings.
- Assets.
- Native libraries.
- Certificates.
- R8 outputs.
- Dependency inventory.

## 14.2 Exit codes

- `0`: all release policies satisfied.
- `1`: release policy violation.
- `2`: missing artifact, missing tool, malformed policy, parse failure, or infrastructure failure.

A tool crash must never be treated as a pass.

## 14.3 Bundle validation

Use the official Android bundle tooling to:

- Validate the AAB.
- Generate an APK set.
- Generate representative device-specific APK sets.
- Verify generated APK contents.
- Install generated APKs in runtime lanes.

`bundletool` is the same underlying technology used by Android Studio, AGP, and Google Play to generate APKs from app bundles. ([developer.android.com](https://developer.android.com/tools/bundletool?hl=en&utm_source=openai))

## 14.4 Manifest checks

Inspect the merged manifest from the built artifact—not only source XML.

Verify:

- Application ID.
- Version code and version name.
- Minimum and target SDK.
- `debuggable=false`.
- `testOnly=false`.
- `allowBackup=false`.
- Cleartext disabled.
- Correct network-security resource.
- Exact exported components.
- Exact component permissions.
- Exact authorities.
- No debug provider or activity.
- No test instrumentation.
- No unexpected profileable/debug flags.
- No unexpected permissions.

## 14.5 DEX checks

Generate a class inventory and fail on:

- Debug/demo/fake/mock/stub implementations.
- Test framework classes.
- debug UI tooling.
- HTTP logging interceptors.
- debug logger trees.
- seeded forbidden classes.
- obsolete release-disabled provider implementations.
- classes that should have been removed by R8.

Use exact FQNs or package policies rather than uncontrolled substring matching.

## 14.6 Resource and asset checks

Fail on:

- Debug menu labels.
- placeholder API URLs.
- test data.
- sample bank or receipt data.
- raw schema test fixtures.
- test certificates.
- private keys.
- keystores.
- debug network configuration.
- unapproved file-provider paths.
- development JSON/configuration files.

Inspect `FileProvider` path configuration and forbid broad root or unrestricted external paths.

## 14.7 Native library inventory

Create an exact inventory of:

- Native library name.
- ABI.
- Origin dependency.
- SHA-256 hash.

Fail when an unexpected native library or ABI is introduced without review.

Do not treat this as vulnerability proof; it is packaging-drift detection.

---

# 15. Workstream E10 — Secret scanning

## 15.1 Scan scopes

Scan:

- Production source.
- Gradle files.
- Manifest and XML resources.
- Main assets.
- Generated BuildConfig.
- Merged manifest.
- Extracted AAB/APK files.
- DEX string pools.
- Resource string pools.
- Native library printable strings where practical.

## 15.2 Detect

Detect patterns for:

- API keys.
- OAuth client secrets.
- bearer tokens.
- private keys.
- PEM material.
- passwords.
- database passphrases.
- cloud-provider keys.
- AWS-style credentials.
- webhook tokens.
- hardcoded Authorization headers.
- keystore passwords.
- suspicious high-entropy constants.

## 15.3 Safe reporting

Reports must contain:

- Rule ID.
- File or artifact entry.
- Offset or symbol.
- Hash of the match.
- Redacted preview.

They must never print the full candidate secret.

## 15.4 False positives

Use exact structured exceptions containing:

- Rule.
- Exact file.
- Exact match fingerprint.
- Reason.
- Owner.
- Expiry.
- Linked issue.

Test fixtures may use recognized synthetic tokens, but production and artifact scans must remain strict.

---

# 16. Workstream E11 — Backup cryptography release contract

## 16.1 Versioned encrypted header

Ensure encrypted backup output records authenticated, versioned metadata for:

- Format version.
- Cipher suite.
- KDF algorithm.
- KDF work parameters.
- Salt.
- Nonce/IV.
- Authentication/tag format.
- Compression policy where applicable.

KDF parameters must not exist only as hardcoded implementation details.

## 16.2 Authentication

Header fields that influence decryption must be:

- Authenticated as associated data, or
- Included within authenticated encrypted content.

Tampering must fail closed before restore mutation.

## 16.3 Release tests

Test:

- Correct password.
- Wrong password.
- Header corruption.
- Cipher identifier corruption.
- KDF parameter corruption.
- Truncated backup.
- Unsupported future format.
- Supported legacy format.
- Excessive KDF parameter denial-of-service bounds.
- No raw password, key, or path in diagnostics.

## 16.4 Artifact checks

Fail if the artifact contains:

- Test passwords.
- Fixed production salts.
- Fixed encryption keys.
- insecure fallback cipher names.
- hardcoded backup passphrases.
- debug-only crypto providers.

---

# 17. Workstream E12 — Restored bank-token behavior

Bank tokens encrypted with an install-specific Keystore key may be unusable after cross-device restore.

Required behavior:

1. Restore token metadata without assuming ciphertext is usable.
2. Attempt validation through a typed token-restoration policy.
3. If the key is unavailable or decryption fails, mark the connection `REAUTH_REQUIRED`.
4. Remove or quarantine unusable token blobs.
5. Never send an undecrypted or corrupted token to a provider.
6. Never repeatedly retry permanent decryption failure.
7. Never show raw cryptographic errors.
8. Never log token material.

Add release-contract tests for:

- Same-install restore.
- Cross-install restore.
- Missing key alias.
- Invalidated Keystore key.
- Corrupted ciphertext.
- Old token format.
- Cancellation.
- User-triggered clean reauthentication.

This verifies release behavior without implementing full bank OAuth.

---

# 18. Workstream E13 — Runtime smoke tests from AAB-generated APKs

## 18.1 API lanes

Run at least:

- Minimum supported API from the final Gradle configuration.
- Current target or maintained modern API lane.

Derive API numbers from build policy rather than duplicating them in multiple scripts.

## 18.2 Installation path

For each lane:

1. Generate device-specific APKs from the built AAB.
2. Verify every APK signature.
3. Install with bundle tooling.
4. Confirm package metadata.
5. Launch through the launcher.
6. Exercise safe deep links.
7. Stop and restart the process.
8. Collect sanitized diagnostics.
9. Uninstall and clean the emulator.

## 18.3 Startup contracts

Prove:

- Process remains alive after launch.
- No fatal exception.
- No ANR.
- Hilt application initialization succeeds.
- Database opens.
- WorkManager configuration initializes.
- Startup coordinators do not require debug bindings.
- R8 has retained required runtime classes.
- Rescue mode is not accidentally enabled.
- No debug worker verification executes in release.
- No demo data is inserted.

## 18.4 Deep-link cases

Test:

- Each supported host.
- Unknown host.
- Missing parameters.
- Invalid IDs.
- Oversized parameter.
- Duplicate parameter.
- Encoded path input.
- Attempted direct mutation.
- App locked or privacy-blocked state where relevant.

No route may crash or silently mutate financial data.

## 18.5 Network smoke behavior

Without real provider credentials:

- Location/cloud clients resolve.
- Disabled providers return typed unavailable results.
- No request is accidentally sent on startup.
- Cleartext URL attempts are rejected.
- No debug proxy or localhost call occurs.

---

# 19. Workstream E14 — Supply-chain verification

## 19.1 Gradle dependency verification

Add and commit Gradle dependency-verification metadata.

Review initial checksums and signing keys before accepting them.

CI must fail when:

- A dependency artifact checksum changes.
- An unapproved plugin artifact appears.
- Verification metadata is malformed.
- Verification is disabled.

Gradle dependency verification compares dependency checksums and signatures during the build. ([developer.android.com](https://developer.android.com/build/dependency-verification?hl=en&utm_source=openai))

## 19.2 Dependency locking

Lock the release runtime and plugin dependency graphs where practical.

Dependency updates must include reviewed lock and verification-metadata changes.

## 19.3 Pull-request dependency review

Add a required dependency-review job for pull requests.

Fail on newly introduced vulnerabilities at the selected severity threshold and on explicitly prohibited licenses.

GitHub’s dependency-review action can block pull requests that introduce vulnerable dependencies. ([docs.github.com](https://docs.github.com/en/code-security/tutorials/secure-your-dependencies/customize-dependency-review-action?utm_source=openai))

## 19.4 Action integrity

Pin release-security workflow actions to immutable commit SHAs.

Grant only required workflow permissions, normally:

- Repository contents read.
- Artifact read/write where needed.
- Security-events write only if SARIF is uploaded.

Do not expose release environments or signing secrets to pull-request workflows.

## 19.5 Release dependency inventory

Generate a machine-readable report of the resolved release runtime graph containing:

- Coordinates.
- Version.
- Configuration.
- Direct/transitive classification.
- Verification status.
- Artifact checksum.

Upload it as release evidence.

---

# 20. Workstream E15 — CI workflow design

## 20.1 Jobs

Add the following jobs.

### `release-build`

Responsibilities:

- Checkout.
- JDK and Android SDK setup.
- Dependency verification.
- Ephemeral signing setup.
- Release lint.
- Release unit tests.
- Release APK build.
- Release AAB build.
- R8 report validation.
- Checksum/provenance generation.
- Artifact upload.
- Signing-material cleanup.

### `release-artifact-audit`

Responsibilities:

- Download the exact build outputs.
- Validate AAB.
- Generate APK sets.
- Verify signatures.
- Inspect merged manifest.
- Scan DEX, resources, assets, native libraries, endpoints, logging, and secrets.
- Produce machine-readable reports.

### `release-smoke`

Use a matrix over the selected API lanes.

Responsibilities:

- Download the same AAB.
- Generate device-specific APKs.
- Install.
- Launch.
- Exercise deep links.
- Check process/logcat.
- Upload sanitized evidence.

### `release-verification`

Stable displayed name:

**Release Verification**

Use `if: always()` and fail unless:

- Release build passed.
- Artifact audit passed.
- Every smoke lane passed.
- Expected artifacts exist.
- No dependency/security job failed.
- No job was skipped, cancelled, or timed out.

## 20.2 Trigger policy

Run required release verification on:

- Every pull request targeting protected branches.
- Every push to protected branches.
- Manual dispatch.

Do not initially path-filter the job. Gradle plugins, dependency changes, manifests, DI, generated code, resources, and source changes can indirectly affect release output.

## 20.3 Timeouts

Set timeouts from measured runtime with approximately 30% headroom.

Keep build, artifact audit, and emulator smoke separate so slow or failing stages are obvious.

## 20.4 Branch protection

After a verified successful run:

- Add `Release Verification` as required.
- Require branches to be up to date.
- Prevent administrator bypass.
- Keep the status-check name stable.

---

# 21. Release provenance and artifacts

Generate:

`build/ci/release-verification/provenance.json`

Include:

- Commit SHA.
- Workflow run ID.
- Build timestamp.
- JDK version.
- Gradle version.
- AGP version.
- Kotlin version.
- Android build-tools version.
- Application ID.
- Version code/name.
- Minimum and target SDK.
- R8/minification flags.
- Resource-shrinking flag.
- AAB SHA-256.
- APK SHA-256.
- Public signing-certificate SHA-256.
- Policy version.
- Verifier versions.

Do not include:

- Passwords.
- Keystore paths.
- private keys.
- runner home paths.
- environment secrets.

Always upload:

- Release policy report.
- AAB/APK checksums.
- Manifest report.
- Component and permission report.
- DEX/class report.
- Secret scan report.
- Dependency report.
- R8 reports.
- Smoke-test summary.
- Sanitized failure log.

Use artifact upload with `if: always()` and fail if required evidence is missing.

---

# 22. Required verifier rules

Suggested rule IDs:

- `G-REL-001` — release not minified
- `G-REL-002` — resources not shrunk
- `G-REL-003` — artifact debuggable/test-only
- `G-REL-004` — cleartext traffic permitted
- `G-REL-005` — unexpected exported component
- `G-REL-006` — component permission mismatch
- `G-REL-007` — forbidden permission
- `G-REL-008` — debug/demo/test class packaged
- `G-REL-009` — unsafe logger packaged
- `G-REL-010` — HTTP body logger packaged
- `G-REL-011` — secret candidate packaged
- `G-REL-012` — insecure endpoint
- `G-REL-013` — release binding mismatch
- `G-REL-014` — raw cloud request path
- `G-REL-015` — unsafe FileProvider path
- `G-REL-016` — signing policy failure
- `G-REL-017` — AAB/APK validation failure
- `G-REL-018` — R8 report missing or invalid
- `G-REL-019` — unexpected native library
- `G-REL-020` — runtime startup failure
- `G-REL-021` — release dependency verification failure
- `G-REL-022` — backup crypto release-contract failure
- `G-REL-023` — restored token policy failure

Each finding should follow the structured guard framework established by PRs B and C.

---

# 23. Required regression tests

## Build-policy tests

1. Minification disabled fails.
2. Resource shrinking disabled fails.
3. Release debuggable fails.
4. Broad `-dontwarn` fails.
5. Broad keep-all rule fails.
6. Missing R8 report fails.

## Manifest tests

7. New exported activity fails.
8. Exported provider fails.
9. Notification service permission removal fails.
10. Rescue activity export fails.
11. `allowBackup=true` fails.
12. Cleartext enablement fails.
13. Debug network configuration fails.
14. Forbidden permission fails.
15. Unexpected deep-link host fails.

## Artifact tests

16. Debug class in DEX fails.
17. Demo bank provider in DEX fails.
18. Test framework packaged fails.
19. Debug logger tree packaged fails.
20. HTTP logging interceptor packaged fails.
21. Secret in resources fails.
22. Secret in DEX strings fails.
23. Test certificate packaged fails.
24. Broad FileProvider path fails.
25. Unexpected native library fails.

## Signing tests

26. Debug certificate fails.
27. Unsigned APK fails.
28. Invalid APK signature fails.
29. Incorrect release-candidate fingerprint fails.
30. Missing signing report fails.

## Network/cloud tests

31. HTTP endpoint fails.
32. Localhost endpoint fails.
33. Permissive trust manager fails.
34. Hostname-verification bypass fails.
35. Raw cloud request body fails.
36. Raw receipt file body fails.
37. Privacy-blocked payload fails closed.

## Runtime tests

38. Startup crash fails.
39. Missing Hilt binding fails.
40. Room-open failure fails.
41. WorkManager initialization failure fails.
42. Invalid deep link does not crash.
43. Release-disabled bank action does not succeed.
44. Debug worker verifier is not executed.
45. Rescue mode is off.

## Crypto/token tests

46. Missing KDF metadata fails.
47. Tampered backup header fails.
48. Cross-device token becomes reauth-required.
49. Corrupted token is never transmitted.
50. Raw crypto error does not reach logs or UI.

## Infrastructure tests

51. Missing AAB exits 2.
52. Missing verifier tool exits 2.
53. Malformed release policy exits 2.
54. Missing artifact report fails the aggregator.
55. Cancelled smoke lane fails the aggregator.
56. Later audits still produce reports after an earlier failure.

---

# 24. Local developer workflow

Document these local stages:

1. Run release static guards.
2. Run release lint.
3. Run release unit tests.
4. Build the release APK.
5. Build the release AAB.
6. Generate an ephemeral local verification key.
7. Validate the AAB.
8. Generate APKs through bundle tooling.
9. Run the release artifact verifier.
10. Install generated APKs on an emulator.
11. Run the smoke-test driver.
12. Review generated reports.

Provide one wrapper entry point, such as a Gradle task or Python release-suite runner, so local and CI behavior cannot drift.

---

# 25. Recommended commit sequence

## Commit E1

`build(release): enable R8 optimization and resource shrinking`

Contains:

- Real release configuration.
- R8 rule cleanup.
- Release lint and unit-test wiring.
- Removal of temporary minification exception.

## Commit E2

`refactor(release): isolate debug demo and test implementations`

Contains:

- Source-set separation.
- Release binding contract.
- Bank demo/stub removal.
- Release DI tests.

## Commit E3

`fix(security): enforce release network and logging policy`

Contains:

- Network-security configuration.
- Endpoint registry.
- OkHttp policy.
- Typed logging ownership.
- Direct logging cleanup.

## Commit E4

`ci(release): add AAB APK and signing artifact verification`

Contains:

- Artifact verifier.
- Ephemeral signing.
- Manifest, DEX, resource, secret, and signature checks.
- Verifier tests.

## Commit E5

`test(release): install and smoke-test bundle-generated APKs`

Contains:

- Bundle generation.
- Emulator matrix.
- Startup and deep-link smoke tests.
- Runtime result verification.

## Commit E6

`fix(security): enforce backup crypto and restored token contracts`

Contains:

- Versioned KDF metadata.
- Tamper tests.
- Cross-device token reauthentication behavior.

## Commit E7

`ci(supply-chain): verify release dependencies and build inputs`

Contains:

- Gradle dependency verification.
- Dependency review.
- Release dependency inventory.
- Workflow action pinning.

## Commit E8

`docs(release): record successful release verification evidence`

Create only after an actual successful Actions run.

---

# 26. Risks and mitigations

## R8 runtime breakage

**Risk:** Hilt, Room, WorkManager, Gson, ML Kit, or reflective code breaks after shrinking.

**Mitigation:**

- Clean broad keep rules incrementally.
- Test the actual minified artifact.
- Add narrow keep rules only for reproduced failures.
- Preserve R8 reports.
- Run startup on minimum and modern API lanes.

## False confidence from source scanning

**Risk:** Source looks safe while merged dependencies or resources introduce unsafe behavior.

**Mitigation:**

- Inspect merged manifest and final artifacts.
- Inspect DEX and resource strings.
- Use bundle-generated APKs for runtime proof.

## Secret leakage through CI

**Risk:** Signing or provider secrets appear in logs or artifacts.

**Mitigation:**

- PRs use ephemeral keys.
- Real keys use protected environments.
- Passwords use files or masked inputs.
- Reports redact matches.
- Cleanup runs unconditionally.

## Overly broad class-name detection

**Risk:** Legitimate implementations named “Stub” or “NoOp” create false positives.

**Mitigation:**

- Prefer exact FQNs and binding contracts.
- Use structural exceptions only when proven.
- Never exempt an entire package or artifact.

## Emulator instability

**Risk:** Release smoke tests become flaky.

**Mitigation:**

- Keep smoke scope narrow.
- Avoid external network dependencies.
- Use deterministic ADB checks.
- Separate infrastructure failures from app failures.
- Do not retry genuine crashes automatically.

## Public artifacts

**Risk:** Mapping files or signed artifacts remain accessible longer than necessary.

**Mitigation:**

- Use short retention.
- Use ephemeral verification signatures.
- Do not upload production-signed artifacts from PR CI.
- Restrict release-candidate workflow artifacts and environments.

---

# 27. PR acceptance checklist

## Build

- [ ] Release minification enabled.
- [ ] Resource shrinking enabled.
- [ ] Optimized ProGuard defaults used.
- [ ] Broad keep/warning suppression removed.
- [ ] Release lint passes.
- [ ] Release unit tests pass.
- [ ] Release APK and AAB build successfully.
- [ ] R8 reports exist and are validated.

## Source boundaries

- [ ] Debug/demo/test implementations moved out of main.
- [ ] Release DI contract passes.
- [ ] Demo bank logic absent from release.
- [ ] Direct cloud request-body guard passes.
- [ ] Direct production logging guard passes.

## Manifest/network

- [ ] Cleartext explicitly disabled.
- [ ] Release network-security config attached.
- [ ] User CAs not trusted in release.
- [ ] Exported components match policy.
- [ ] Component permissions match policy.
- [ ] Permissions match exact allowlist.
- [ ] Deep-link negative tests pass.
- [ ] FileProvider paths are narrow.

## Artifact

- [ ] AAB validates.
- [ ] Bundle-generated APKs validate.
- [ ] APK signatures validate.
- [ ] Debug certificate is rejected.
- [ ] Forbidden classes are absent.
- [ ] Debug/test resources are absent.
- [ ] Secret scan reports zero findings.
- [ ] Endpoint scan reports zero insecure endpoints.
- [ ] Logging scan reports zero unsafe loggers.
- [ ] Native-library inventory is reviewed.

## Runtime

- [ ] APKs install on minimum API.
- [ ] APKs install on modern API.
- [ ] Main process launches.
- [ ] Hilt initializes.
- [ ] Room opens.
- [ ] WorkManager initializes.
- [ ] Supported deep links do not crash.
- [ ] Invalid deep links fail safely.
- [ ] No fatal exception or ANR.
- [ ] No debug/demo behavior occurs.

## Security contracts

- [ ] Backup KDF parameters are versioned and authenticated.
- [ ] Backup tamper tests pass.
- [ ] Cross-device restored tokens require reauthentication.
- [ ] Corrupt tokens cannot be transmitted.
- [ ] No raw cryptographic error is exposed.

## Supply chain

- [ ] Gradle dependency verification enabled.
- [ ] Release dependency graph recorded.
- [ ] Dependency-review job passes.
- [ ] Release workflow actions pinned.
- [ ] Workflow permissions are minimal.

## CI evidence

- [ ] Stable `Release Verification` check passes.
- [ ] Check runs on every pull request.
- [ ] No release job uses `continue-on-error`.
- [ ] Required reports upload on success and failure.
- [ ] Two consecutive Actions runs pass.
- [ ] Exact SHA and Actions run ID are documented.
- [ ] Branch protection requires `Release Verification`.

---

# 28. Definition of done

PR E is complete only when an actual pull-request workflow proves that:

- The optimized release build—not debug or an approximation—was produced.
- The AAB was validated.
- APKs were generated from that AAB.
- Those APKs were signed, inspected, installed, and launched.
- The merged release manifest satisfies the exact security policy.
- R8 and resource shrinking are active.
- No debug, demo, fake, test, unsafe no-op, or HTTP logging implementation is packaged.
- No secret or insecure endpoint is present.
- Release DI resolves only approved implementations.
- Cloud requests cannot bypass prepared-payload ownership.
- Backup and restored-token release contracts pass.
- Dependency and build-input verification pass.
- Every required artifact and report exists.
- The stable status check blocks merging.

The required invariant is:

> **Code cannot merge unless CI builds the artifact users would receive and proves that the artifact—not merely its source configuration—is installable, optimized, fail-closed, and free from known release-only security violations.**