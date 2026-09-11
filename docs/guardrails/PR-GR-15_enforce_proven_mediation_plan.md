# PR-GR-15 — Enforce proven mediation; retire metadata-only authorization

## Agent mission

Promote direct, helper, and worker mediation from policy metadata into an active, fail-closed DB authorization requirement.

After this PR, an exact ownership-policy mutation is authorized only when all three conditions hold:

```text
1. Exact v3 mutation identity matches.
2. Exact source evidence proves the policy mutation exists.
3. Required barrier proof succeeds for its declared proof contract.
```

A policy row saying `helper` or `workerMediated` must no longer authorize a mutation by itself.

## Why this PR is necessary

The current model says `barrierMode` is metadata only, and the current v2 source-evidence path checks local direct syntax while making no dominance or helper/worker reachability claim. ([raw.githubusercontent.com](https://raw.githubusercontent.com/panospao7/Cost-agregator/8b45879e445e8750fcb9210bcf44b9a26e6468dd/scripts/db_guard/policy_model.py))

GR-12 establishes direct proof. GR-13 establishes bounded helper/worker proof. GR-14 removes paths that cannot be proven. GR-15 is the controlled activation step that makes those proofs required by the normal DB gate.

---

# Absolute entry gate

Do not begin until all are true for the exact start SHA:

```text
GATE-00R complete and reproducible twice
GR-12 direct proof: all direct mutations PROVEN
GR-13 mediation proof: all helper/worker mutations PROVEN
GR-14 remediation inventory: zero open rows
DB CLI: trusted true, finding count 0, diagnostics 0
DB ratchet: exit 0
Static suite DB result: pass
Gradle DB task: pass, unless explicitly unavailable and evidence marked incomplete
Active policy v2 / source evidence valid
Baseline valid and byte-identical
Checkout clean
```

If any proof result is not proven, return to GR-14. Do not start schema migration as a repair bucket.

---

# Scope

## Allowed

- DB policy model and loader;
- v3 proof-contract schema;
- policy migration/promotion tooling;
- source evidence and D4 integration;
- ratchet/report compatibility tests;
- canonical registry required-input metadata if needed;
- generated/documentation updates describing active v3 proof;
- tests and fixtures.

## Forbidden

```text
production Kotlin fixes
DB baseline changes
baseline generation
temporary debt entries
legacy/v2 active-policy fallback
policy wildcard support
proof bypass field
manual v3 policy editing
changing proof outcomes to advisory
source-scope changes
command ownership rewrites
```

Expected production Kotlin change:

```text
no
```

If source changes are required, return to GR-14.

---

# Target active policy schema

Use a new schema version. Do not make proof semantics an optional v2 field.

## Required v3 shape

```yaml
schemaVersion: 3
entries:
  - path: app/src/main/java/...
    ownerFqcn: ...
    kind: function
    method: ...
    receiver: null
    parameterTypes: []
    daoAccessor: ...
    daoFqcn: ...
    operation: ...
    barrierRequirement:
      mode: direct
      contract: cfg-direct-dominance-v1
    reason: ...
    owner: "@panospao7"
    linkedIssue: ...
```

## Closed barrier requirements

| `mode` | Required `contract` |
|---|---|
| `direct` | `cfg-direct-dominance-v1` |
| `helper` | `bounded-helper-mediation-v1` |
| `workerMediated` | `bounded-worker-mediation-v1` |

No other mode or contract is accepted.

## Prohibited v3 forms

```text
barrierMode
metadataOnly
legacy
unchecked
optional proof field
proofDisabled
allowUnproven
wildcard contract
manual path/caller suppression
generic “approved caller” lists
```

The policy still does not contain graph paths or source code. It declares the required proof category; the scanner derives proof from live source each run.

---

# Required model changes

## Typed policy model

Introduce immutable types equivalent to:

```text
BarrierProofMode
BarrierProofContract
BarrierRequirement
PolicyEntryV3
```

`PolicyEntryV3.mutation_key()` must preserve the existing full exact mutation identity.

The barrier requirement must be part of validated policy state, but not part of mutation ownership identity/fingerprint unless the current report protocol requires it.

## Loader behavior

The v3 loader must reject:

```text
schemaVersion absent
schemaVersion 1 or 2
unknown top-level fields
unknown entry fields
barrierMode legacy field
missing barrierRequirement
wrong mode/contract pair
duplicate mutation key
noncanonical paths/types
wildcards
empty reason/owner/issue
```

A v2 active policy after promotion is a controlled exit-`2` configuration failure.

No auto-upgrade or silent v2 compatibility mode is permitted in normal CLI, ratchet, suite, or Gradle.

---

# Required migration tooling

## Candidate generation

Create a dedicated one-way migration command, for example:

```text
scripts/ci/migrate_db_policy_to_proven_mediation.py
```

Inputs required explicitly:

```text
current active v2 policy
GR-12 final direct proof report
GR-13 final mediation proof report
trusted current DB report
explicit source-root manifest
explicit output candidate path
explicit generated-at value
```

The tool must reject:

```text
untrusted report
proof-report SHA mismatch
missing proof result
non-proven result
duplicate identity
extra policy row
missing policy row
output collision with active policy
baseline path as output
bulk source-derived authorization
```

## Required mapping invariant

For every v2 policy mutation key:

```text
exactly one v3 policy mutation key exists
AND
identity fields are byte/semantic equivalent
AND
v3 barrier requirement matches a proven GR-12/GR-13 result
```

For every v3 policy entry:

```text
exactly one prior v2 policy entry exists
AND
one exact proof result exists
```

No bulk candidate row may be emitted from a source mutation absent from v2 policy.

## Promotion

Promote only through an atomic promotion command.

Required promotion steps:

1. validate v3 candidate;
2. validate v2→v3 one-to-one migration report;
3. validate GR-12 and GR-13 proof report hashes;
4. validate current trusted DB report hash;
5. archive old active v2 policy byte-for-byte;
6. atomically write active v3 policy;
7. write promotion record with all hashes;
8. rerun normal DB CLI before declaring success.

Suggested archive:

```text
config/guards/db_ownership_policy.v2.preproof.yml
```

Suggested promotion record:

```text
docs/ci/db-policy-promotions/GR-15_v3_promotion.yml
```

Do not overwrite the historical v1 legacy archive.

---

# Active enforcement pipeline

After promotion, normal DB authority must become:

```text
source-root validation
→ Room inventory
→ active v3 policy loader
→ exact v3 source evidence
→ GR-12 direct barrier proof
→ GR-13 helper/worker mediation proof
→ structural exception validation
→ D4 mutation discovery and exact authorization
→ protocol-v2 findings report
→ ratchet comparison
```

## Required result mapping

| State | Result |
|---|---|
| Exact identity + exact source evidence + required proof | authorized |
| Mutation has no exact policy row | finding / exit `1` |
| Direct proof has modeled counterexample | finding / exit `1` |
| Helper proof finds unguarded/external path | finding / exit `1` |
| Worker proof finds non-worker/out-of-scope path | finding / exit `1` |
| Any proof source/parser/call edge is uncertain | diagnostic / exit `2` |
| v3 policy malformed or v2 active policy supplied | diagnostic / exit `2` |
| Baseline malformed/expired | ratchet exit `2` |

Recommended exact finding rules:

```text
DB_MISSING_WRITE_BARRIER
DB_WRITE_BARRIER_NOT_DOMINATING
DB_HELPER_MEDIATION_NOT_PROVEN
DB_WORKER_MEDIATION_NOT_PROVEN
```

Recommended blocking diagnostic families:

```text
DB_BARRIER_PROOF_UNSUPPORTED
DB_MEDIATION_CALL_EDGE_UNRESOLVED
DB_MEDIATION_EXTERNAL_ROOT_UNRESOLVED
DB_WORKER_ROOT_UNRESOLVED
DB_WORKER_GUARD_CONTRACT_INVALID
DB_V3_POLICY_INVALID
```

A proof diagnostic never degrades to a normal ratchet violation.

---

# Implementation sequence

## Step 1 — Freeze final v2/proof inventory

Capture:

```text
current active v2 policy SHA
v2 policy entry count
GR-12 proof report SHA
GR-13 proof report SHA
normal DB report SHA
baseline SHA
source-root manifest SHA
```

Run every report twice. Require equal semantic result.

## Step 2 — Add v3 model/loader tests before migration

Required tests:

1. minimal valid direct v3 entry;
2. valid helper v3 entry;
3. valid worker-mediated v3 entry;
4. every wrong mode/contract pair;
5. missing barrier requirement;
6. legacy `barrierMode` rejected;
7. v2 active policy rejected;
8. unknown field rejected;
9. duplicate mutation key rejected;
10. exact identity behavior unchanged;
11. no wildcard/fallback;
12. controlled error contains no raw source.

## Step 3 — Implement v3 shadow validation

Before changing active authority:

1. load v3 candidate;
2. run exact source evidence;
3. run direct and mediation proof;
4. compare against current active v2 results;
5. prove every policy mutation has exactly one proof result.

The v3 shadow mode must not influence normal DB CLI yet.

## Step 4 — Generate candidate through migration tool

Write candidate to:

```text
build/guard-debug/gr15/db_ownership_policy.v3.candidate.yml
```

Then verify:

```text
candidate loader success
candidate source evidence success
candidate proof success
candidate/accounting equality
byte reproducibility across two generation runs
```

Only then write tracked candidate/promotion artifacts.

## Step 5 — Add active-pipeline adversarial tests

Before promotion, prove:

1. removing direct barrier causes `DB_WRITE_BARRIER_NOT_DOMINATING`;
2. adding unguarded caller to helper causes helper finding;
3. calling worker helper from non-worker root causes worker finding;
4. worker mutation outside guard causes worker finding;
5. unresolved interface dispatch causes exit `2`;
6. old v2 field causes exit `2`;
7. changing `reason` cannot change proof result;
8. changing mode/contract cannot bypass proof;
9. source mutation absent from policy remains finding;
10. malformed proof report cannot produce trusted clean output;
11. ratchet sees findings as `1` and diagnostics as `2`.

## Step 6 — Promote atomically

Promote only after all shadow checks pass.

Immediately verify:

```text
active policy schema = 3
candidate == active byte-for-byte
v2 archive byte-for-byte preserved
promotion record hashes match
baseline unchanged
```

## Step 7 — Remove metadata-only active paths

After v3 promotion:

1. remove active source-evidence behavior that treats `helper`/`workerMediated` as automatically acceptable;
2. remove active lexical direct-barrier fallback;
3. reject active v2 policy;
4. ensure no policy reason text is interpreted as proof;
5. ensure legacy shadow tools cannot affect normal result.

Historical read-only v2 compatibility may exist only for migration tooling and archived evidence—not normal enforcement.

## Step 8 — Validate every control plane

Run:

```text
v3 loader/model tests
v3 migration/promotion tests
source-evidence tests
direct proof tests
mediation proof tests
D4 scanner tests
ratchet tests
registry/static suite tests
normal DB CLI
static suite
Gradle DB task
GATE-00R twice
```

---

# Mandatory adversarial acceptance checks

1. Inject new direct DAO mutation in a legal owner without policy row → exit `1`.
2. Inject exact policy row but omit barrier proof → loader exit `2`.
3. Move barrier into only one branch → exit `1`.
4. Add unguarded external helper caller → exit `1`.
5. Add ambiguous dispatch to helper → exit `2`.
6. Add worker mutation outside guard lambda → exit `1`.
7. Add WorkerExecutionGuard call in dead helper → exit `1`.
8. Supply old v2 active policy → exit `2`.
9. Supply malformed v3 barrier contract → exit `2`.
10. Attempt baseline update → refusal/preservation failure.
11. Alter baseline entry while report stays clean → ratchet detects stale baseline behavior.
12. Run full pipeline twice → identical semantic reports.

---

# Preservation checks

```text
DB baseline unchanged
No production Kotlin changes
No source-root manifest change
No structural exception change
No time exception change
No GR-10A command-authority redesign
```

Documentation updates are allowed only where they truthfully state that proof enforcement is now active.

---

# Definition of done

GR-15 is complete only when:

- active DB policy is schema v3;
- every policy mutation has a required typed proof contract;
- direct, helper, and worker mediation are dynamically proven on every scan;
- metadata alone cannot authorize a mutation;
- v2 cannot be used as active authority;
- proof counterexamples are findings;
- proof uncertainty is exit `2`;
- policy migration is one-to-one, reviewed, reproducible, and atomic;
- DB baseline remains unchanged;
- direct CLI, ratchet, suite, Gradle, and two GATE-00R captures agree;
- no active helper/worker path remains unproven.

## Required completion report

```text
PR: GR-15
START SHA:
END SHA:
ACTIVE V2 POLICY SHA:
ACTIVE V3 POLICY SHA:
V2 ARCHIVE SHA:
V3 CANDIDATE SHA:
V3 CANDIDATE == ACTIVE: yes/no
PROMOTION RECORD SHA:

V2 ENTRY COUNT:
V3 ENTRY COUNT:
DIRECT PROOF COUNT:
HELPER PROOF COUNT:
WORKER PROOF COUNT:
UNPROVEN COUNT: 0
PROOF DIAGNOSTIC COUNT: 0

NORMAL DB CLI:
RATCHET:
STATIC SUITE DB RESULT:
GRADLE DB RESULT:
GATE-00R DOUBLE CAPTURE REPRODUCIBLE: yes/no

ACTIVE V2 FALLBACKS REMAINING: 0
METADATA-ONLY AUTHORIZATION PATHS REMAINING: 0
BASELINE CHANGED: no
PRODUCTION KOTLIN CHANGED: no
UNEXPECTED DIFFERENCES:
NEXT PR:
```