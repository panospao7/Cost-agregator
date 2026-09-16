# PR-REL-00 — Actual release-artifact verification

## Agent mission

Make “release verification” mean verification of the **actual optimized release AAB and APKs generated from that AAB**, not source inspection and not a debug APK.

This PR must prove, for one exact target SHA:

1. release configuration is evaluated as intended;
2. R8/minification and resource shrinking actually ran;
3. a release **AAB** was built;
4. APK(s) were generated from that exact AAB;
5. artifact manifest, signing, permissions, components, classes/resources/native libraries, endpoints, and secret-candidate policy pass;
6. bundle-generated APKs install and smoke-test on required API lanes;
7. release startup initializes Hilt, Room, and WorkManager without fatal crash/ANR;
8. all evidence is structured, safe, reproducible, and uploadable.

## Why this PR exists

At historical SHA `8b45879e…`, the workflow’s `Release Check` builds only `assembleRelease` and invokes an APK-only script. That script searches for APKs, checks only `debuggable`, `testOnly`, and APK signing, and treats missing build tools as ordinary violations rather than a distinct infrastructure result. It does not inspect an AAB, generate APKs from an AAB, verify release runtime behavior, or emit a structured report. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/.github/workflows/ci.yml))

The same historical Gradle configuration enables minification and resource shrinking, but assigns the release build type to the standard debug signing configuration for CI. The acceptance contract instead requires ephemeral **non-debug** verification signing for pull requests and forbids exposing production signing material. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/app/build.gradle.kts))

The target acceptance contract explicitly requires release lint/tests, APK and AAB builds, R8/resource shrinking, AAB-generated APK inspection, signing validation, and runtime smoke on required API lanes. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/FINAL_CI_GUARD_ACCEPTANCE_GATE.md))

---

# Required ordering

Start only after all are true:

```text
GATE-00R complete for actual start SHA
GR-10A canonical command ownership complete
GR-10B source-scope authority complete
GR-10D documentation/evidence truth sync complete
GUARD-QA-00 has audited release/DI/CI guard coverage
MIG-01 migration proof is blocking and operational
Current static suite is trusted and passes
Current DB/time/release-source guards have no infrastructure result
Checkout is clean
```

REL-00 may prepare local scripts and policy before CI-11, but CI-11 owns final workflow topology, stable aggregators, required-check enforcement, and branch-rule migration.

## Hard stops

Stop and report `BLOCKED`; do not weaken checks, if any applies:

- exact release package ID, expected permissions, exported components, or deep-link policy cannot be identified from current product/manifest sources;
- production release signing policy conflicts with PR verification-signing policy;
- AAB cannot be built;
- APK is built independently of the AAB;
- bundletool/toolchain provenance or checksum cannot be verified;
- evaluated release variant metadata cannot be obtained safely;
- an expected release test/smoke task does not exist and no supported replacement path is designed;
- Hilt/Room/WorkManager initialization cannot be observed without adding a production debug backdoor;
- smoke evidence relies only on “no crash visible in logs”;
- a release artifact or report exposes signing secrets, seed database data, token-like values, raw request bodies, or absolute local paths;
- another agent owns Gradle/emulator execution.

A missing tool, missing artifact, unreadable artifact, malformed report, timeout, or emulator failure is **exit 2 / BLOCKED**, never a passing release result.

---

# Scope

## Allowed

```text
app/build.gradle.kts
release-specific test configuration and narrowly required test source
release verification scripts/tests
release artifact policy/configuration
toolchain lock/checksum metadata
release smoke harness/tests
release artifact documentation
minimal CI hook needed to keep existing release check runnable
```

## Forbidden

```text
DB ownership policy/baseline changes
time exception changes
ratchet changes
debug-only production endpoints
release-only fake/no-op success behavior
production signing secrets in repository or PR CI
broad R8 keep/dontwarn rules to make release build pass
testing assembleRelease APK as substitute for AAB-generated APK
artifact allowlists based only on package/class wildcards
network requests containing real credentials or user data
```

---

# Required deliverables

## 1. Strict release artifact policy

Create:

```text
config/ci/release_artifact_policy.yml
```

This is a strict release contract, not an allowlist and not a baseline.

Required categories:

```yaml
schemaVersion: 1

application:
  expectedApplicationId: <derived and reviewed>
  expectedVersionNameSource: <Gradle/variant metadata>
  expectedModules:
    - base

manifest:
  debuggable: false
  testOnly: false
  allowBackup: false
  cleartextTraffic: false
  exportedComponents:
    exact: []
  permissions:
    required: []
    forbidden: []
  deepLinks:
    safeSmokeUris: []
    invalidSmokeUris: []

signing:
  pullRequestMode: ephemeralNonDebugVerification
  forbidAndroidDebugCertificate: true
  expectedCertificateSubjectPrefix: "CN=CI Release Verification"
  productionSigningEvidenceMode: protectedEnvironmentOnly

artifactContents:
  forbiddenDexPrefixes: []
  forbiddenDexStrings: []
  forbiddenResourceStrings: []
  forbiddenAssetNames: []
  allowedNativeLibraries: []
  forbiddenNativeLibraries: []
  forbiddenEndpointPatterns: []
  secretCandidateRules: []

runtime:
  requiredInitialization:
    - hilt
    - room
    - workManager
  startupNetworkPolicy: noUnexpectedOutboundRequest
  demoDataPolicy: noDemoSeedAtStartup
```

Rules:

1. Every component, permission, class prefix, native library, endpoint pattern, and deep link must be exact and reviewed.
2. Unknown YAML keys fail closed.
3. No `*`, package-wide “debug”, or “all test classes” exemption.
4. Policy is derived from actual intended release behavior, not copied from a debug artifact.
5. Any policy change requires an explicit reason, owner, linked issue/ADR, and artifact regression test.

## 2. Toolchain lock

Create:

```text
config/ci/release_toolchain.lock.yml
```

Required locked tools:

```text
bundletool version + official artifact SHA-256
Android build-tools version/required executables
JDK major version
adb version family
optional apkanalyzer/aapt2/apksigner source
```

Requirements:

- tool download/version must be explicit;
- checksum is verified before execution;
- no “latest” resolution;
- no home-directory tool discovery in release CI;
- tool paths are passed explicitly to verifier commands;
- missing/incorrect tool is infrastructure failure.

## 3. Evaluated release metadata task

Add a narrow Gradle task, for example:

```text
:app:writeReleaseVerificationMetadata
```

It must output safe JSON such as:

```json
{
  "schemaVersion": 1,
  "targetSha": "<passed explicitly by CI>",
  "variant": "release",
  "applicationId": "...",
  "minSdk": 0,
  "targetSdk": 0,
  "minifyEnabled": true,
  "resourceShrinkingEnabled": true,
  "signingMode": "ephemeralNonDebugVerification",
  "bundlePath": "app/build/outputs/bundle/release/...",
  "mappingFiles": [],
  "resolvedReleaseRuntimeClasspath": []
}
```

It must not output:
- keystore paths;
- aliases;
- passwords;
- private certificate data;
- raw environment values.

Do not prove release configuration by regex-scanning `build.gradle.kts`; emit evaluated variant evidence.

## 4. Ephemeral verification signing

Implement a dedicated PR/CI signing mode.

Required contract:

```text
CI creates a fresh ephemeral keystore in runner-temp storage.
The release variant uses it only when explicit verification mode is enabled.
The keystore is not Android’s default debug keystore.
The certificate is identifiable as CI verification signing.
The private key/password never appear in logs/artifacts.
The keystore is deleted in an always-run cleanup step.
```

Production signing remains outside PR workflows and protected environments.

Required rejection cases:

```text
verification signing mode enabled but keystore absent
debug keystore selected
production signing secret requested in pull_request workflow
release build falls back silently to debug signing
artifact certificate does not match generated verification certificate
```

## 5. AAB → APK provenance pipeline

Add a controlled script or Gradle-backed runner, for example:

```text
scripts/ci/build_release_verification_artifacts.py
```

Inputs must be explicit:

```text
--root
--target-sha
--variant-metadata
--bundle
--toolchain-lock
--verification-signing-evidence
--output-dir
```

Required sequence:

```text
1. Build release AAB through Gradle.
2. Hash AAB.
3. Run checksum-verified bundletool.
4. Generate an .apks archive from that exact AAB.
5. Extract universal/device APK(s) from the generated .apks archive.
6. Hash .apks and extracted APK(s).
7. Emit provenance JSON linking all hashes and argv.
```

Forbidden:

```text
assembleRelease APK used as smoke input
glob-first APK selection
manual/repacked APK accepted as bundle-generated
AAB/APK relationship asserted only in prose
```

## 6. Structured artifact verifier

Replace or substantially rewrite the historical `scripts/verify_release_artifact.py`, preserving a stable entrypoint only if callers require it.

Preferred files:

```text
scripts/ci/release_artifact_model.py
scripts/ci/verify_release_artifact.py
scripts/ci/test_verify_release_artifact.py
scripts/ci/test_release_artifact_model.py
```

Required CLI:

```bash
python3 scripts/ci/verify_release_artifact.py \
  --root . \
  --policy config/ci/release_artifact_policy.yml \
  --toolchain-lock config/ci/release_toolchain.lock.yml \
  --variant-metadata build/.../release-variant.json \
  --bundle build/.../app-release.aab \
  --apks build/.../app-release.apks \
  --apk build/.../universal.apk \
  --signing-evidence build/.../verification-signing.json \
  --output build/.../release-artifact-report.json \
  --fail-on-violation
```

Exit semantics:

| Exit | Meaning |
|---:|---|
| `0` | All artifact predicates satisfied. |
| `1` | Artifact exists and is inspectable, but violates release policy. |
| `2` | Missing tool/input, malformed AAB/APK/report/policy, unreadable file, bad checksum, timeout, or inspection uncertainty. |

### Required verifier checks

#### Build/evaluated configuration

```text
release variant metadata exists
minification is true
resource shrinking is true
AAB path/hash is correct
mapping/usage/seed reports required by policy exist
release runtime dependency inventory satisfies policy
```

#### AAB and generated APK chain

```text
AAB is valid and has expected module inventory
.apks provenance input hash equals AAB hash
APK originates from .apks output
APK hash is recorded
APK package/version matches AAB/variant metadata
```

#### Manifest

```text
debuggable=false
testOnly=false
allowBackup=false
cleartext traffic disabled
expected package/application ID
exact exported component inventory
exact component permissions
exact requested/forbidden permission policy
deep-link component policy
```

#### Signing

```text
APK signatures verify
certificate is not Android debug certificate
certificate matches generated ephemeral verification evidence
no production signing fingerprint is expected in PR CI
```

#### Content/security

```text
forbidden debug/demo/test class prefixes absent from dex inventory
unsafe logging implementations absent
HTTP-body logger classes/strings absent where policy forbids them
secret-candidate rules clean without emitting matched secret text
forbidden insecure endpoint patterns absent
unsafe FileProvider paths absent
unexpected native libraries absent
release-disabled feature classes/resources absent
```

Reports may identify:
```text
rule ID, artifact path, entry/class/resource name, SHA-safe normalized location
```

Reports must never print:
```text
secret candidate contents
full endpoint/token strings
private signing material
raw dex/resource blobs
absolute paths
```

## 7. Release smoke contract

Create:

```text
config/ci/release_smoke_contract.yml
```

It must define, from real product behavior:

```yaml
schemaVersion: 1
apiLanes:
  minimumSupported: derivedFromVariantMetadata
  modernSupported: <explicit supported emulator API>
startup:
  launcherActivity: <exact>
  maxStartupSeconds: <reviewed value>
initializationProof:
  hilt: <exact observable/test>
  room: <exact observable/test>
  workManager: <exact observable/test>
deepLinks:
  safe: []
  invalid: []
network:
  mode: proxy-or-uid-counter
  expectedStartupRequests: []
demoData:
  expectedInitialState: <exact user-visible or safe test assertion>
```

### Runtime proof rules

The smoke harness must install the **AAB-generated APK**, not an `assembleRelease` APK.

For each API lane:

1. wipe/uninstall the app;
2. install generated APK;
3. confirm installed package certificate/hash;
4. cold-launch via explicit launcher activity;
5. capture bounded startup evidence;
6. prove Hilt, Room, and WorkManager initialized;
7. prove no fatal exception or ANR;
8. execute reviewed safe deep links;
9. execute malformed/invalid deep links and assert safe failure;
10. prove no demo data initialization;
11. prove no unexpected outbound startup request;
12. uninstall and preserve safe report/artifacts.

### Initialization proof choice

Use one reviewed path only:

```text
A. actual release-variant instrumentation test targeting the installed release app; or
B. external black-box smoke plus a normal user-visible workflow that necessarily initializes Hilt, Room, and WorkManager.
```

A logcat line by itself is not enough. A new release-only debug endpoint is forbidden.

### Startup network proof

Use a reviewed capture method such as:
- emulator host proxy recording connection attempts without request bodies; or
- package UID network-counter sampling with controlled pre/post state.

The report may record destination classification/count only. It must not log request payloads, tokens, cookies, or URLs containing identifiers.

---

# Implementation sequence

## Step 1 — Freeze and inspect

```bash
git status --short
git rev-parse HEAD
git rev-parse HEAD^{tree}

./gradlew :app:tasks --all --no-daemon --console=plain \
  | tee build/guard-debug/rel-00/gradle-tasks.txt
```

Record hashes for:

```text
app/build.gradle.kts
AndroidManifest files
release DI modules
proguard-rules.pro
existing release verifier
release-source guard policy
current CI workflow
```

Inventory:
- launcher activity;
- exported components;
- declared permissions;
- deep-link handlers;
- release DI bindings;
- runtime initialization path;
- current release-only/debug-only classes;
- existing R8 outputs.

Do not draft policy from memory.

## Step 2 — Write policy/contract tests first

Before changing build logic, add parser/validator tests for:

```text
unknown policy field
wildcard component/permission rule
duplicate component
missing required deep-link contract
invalid API lane
unsupported network-observation mode
secret-like value leaked into report
missing expected R8 report
bad tool checksum
```

## Step 3 — Add ephemeral verification signing

Implement and test:
- fresh non-debug keystore generation;
- explicit Gradle property gating;
- release build rejects absent signing inputs;
- debug keystore is rejected;
- cleanup executes after success/failure;
- report contains certificate SHA-256 only.

## Step 4 — Build real release artifacts

Use actual task names discovered in Step 1. Expected shape:

```bash
./gradlew \
  :app:lintRelease \
  :app:bundleRelease \
  :app:writeReleaseVerificationMetadata \
  --no-daemon --stacktrace --console=plain \
  -PciVerificationSigning=true \
  -PtargetSha="$TARGET_SHA"
```

If release unit-test tasks exist, run them. If they do not, add a reviewed release-test route; do not omit release-specific testing.

## Step 5 — Generate bundle-derived APKs

Use checksum-verified bundletool only.

Required evidence:

```text
bundle SHA-256
bundletool version + SHA-256
exact tokenized argv
.apks SHA-256
extracted APK SHA-256
input/output provenance record
```

## Step 6 — Implement verifier and adversarial fixtures

Add synthetic fixture artifacts/tool outputs sufficient to test:
- invalid manifest;
- debug/test-only flag;
- backup/cleartext violation;
- unexpected exported component;
- unexpected permission;
- debug certificate;
- signature mismatch;
- wrong AAB/APKS/APK chain;
- unknown native library;
- forbidden class/resource;
- secret candidate;
- missing/malformed tool output;
- tool timeout;
- report nondeterminism.

Do not mutate/re-sign real production artifacts for unit tests.

## Step 7 — Implement smoke harness

First prove the actual supported test route locally. Then add:
- release APK installer;
- API-lane matrix runner;
- startup/deep-link result collector;
- bounded ANR/crash collector;
- initialization assertions;
- network/demo-data assertions;
- structured smoke result verifier.

## Step 8 — Integrate local release command

Create one canonical command through GR-10A’s registered runner/external-job contract.

No manual workflow-only command list.

## Step 9 — Run twice

Run the full release verification pipeline twice on the same SHA.

Compare semantic results:

```text
policy hash
variant predicates
AAB/APK provenance validity
manifest/component/permission results
class/resource/native/security predicates
smoke result by API lane
test identities/counts
tool versions
```

Raw APK hashes may differ when each run uses a new ephemeral signing certificate. Record both hashes, but compare normalized artifact semantics and certificate-policy compliance rather than falsely requiring byte-identical signed packages.

---

# Required adversarial checks

1. Set release minification false in fixture/evaluated metadata → fail.
2. Set resource shrinking false → fail.
3. Supply an APK not generated from input AAB → fail.
4. Supply debug-signed APK → fail.
5. Add `debuggable=true`, `testOnly=true`, backup enabled, or cleartext enabled → fail.
6. Add unexpected exported component/permission → fail.
7. Add forbidden debug/demo/test dex entry → fail.
8. Add forbidden native library → fail.
9. Add endpoint/secret candidate fixture → fail without leaking literal.
10. Remove mapping/R8 report → fail.
11. Remove bundletool/checksum → exit `2`.
12. Corrupt JSON/policy/tool output → exit `2`.
13. Launch bad deep link → app must fail safely, not crash.
14. Simulate app crash/ANR signal → smoke verifier fails.
15. Use `assembleRelease` APK as smoke input → provenance verifier rejects it.
16. Remove release artifact/report → aggregate result must fail in CI-11.

---

# Required validation

```bash
python3 -m pytest \
  scripts/ci/test_release_artifact_model.py \
  scripts/ci/test_verify_release_artifact.py \
  scripts/ci/test_release_smoke.py \
  scripts/ci/test_verify_release_smoke_results.py \
  -v --tb=short
```

```bash
python3 scripts/ci/verify_release_artifact.py \
  --help
```

Then, only with Gradle/emulator ownership:

```bash
./gradlew :app:lintRelease :app:bundleRelease \
  --no-daemon --stacktrace --console=plain \
  -PciVerificationSigning=true
```

```bash
python3 scripts/ci/verify_release_artifact.py ...
python3 scripts/ci/run_release_smoke.py ...
python3 scripts/ci/verify_release_smoke_results.py ...
```

Finally:
- run canonical static suite;
- run `:app:check`;
- run release verification twice;
- capture GATE-00R evidence for final SHA.

---

# Definition of done

REL-00 is complete only when:

- release lint/tests, AAB build, and R8/resource shrinking pass;
- PR release signing is ephemeral and non-debug;
- production secrets never enter PR CI;
- verifier consumes explicit AAB/APKS/APK inputs and returns 0/1/2 correctly;
- all smoke APKs are proven bundle-generated;
- manifest/component/permission/signing/content policies are exact;
- release startup, Hilt, Room, WorkManager, deep-link, network, demo-data, fatal/ANR checks pass on required lanes;
- reports/artifacts are safe and deterministic;
- no debug artifact is used as release proof;
- no baseline or broad R8/allowlist weakening was introduced;
- CI-11 has clear artifact and aggregator inputs.

## Required completion report

```text
PR: REL-00
START SHA:
END SHA:
GATE-00R EVIDENCE ID:

RELEASE POLICY SHA:
TOOLCHAIN LOCK SHA:
VARIANT METADATA SHA:
AAB SHA-256:
APKS SHA-256:
UNIVERSAL APK SHA-256:
SIGNING CERTIFICATE SHA-256:
SIGNING MODE: ephemeral-non-debug

MINIFICATION:
RESOURCE SHRINKING:
R8 REPORTS:
MANIFEST POLICY:
COMPONENT/PERMISSION POLICY:
CLASS/RESOURCE/NATIVE POLICY:
SECRET/ENDPOINT POLICY:

SMOKE API LANES:
HILT RESULT:
ROOM RESULT:
WORKMANAGER RESULT:
SAFE DEEP LINKS:
INVALID DEEP LINKS:
STARTUP NETWORK POLICY:
DEMO DATA POLICY:
CRASH/ANR RESULT:

VERIFIER TESTS:
SMOKE TESTS:
RELEASE BUILD:
RELEASE ARTIFACT VERIFIER:
DOUBLE-RUN SEMANTIC REPRODUCIBILITY:

PRODUCTION SIGNING SECRET USED: no
DB POLICY CHANGED: no
BASELINE CHANGED: no
BROAD R8 EXCEPTION ADDED: no
NEXT PR: CI-11
```