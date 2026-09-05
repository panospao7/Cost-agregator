"""GR-13 fixture runner: engine-vs-manifest self-check over the corpus.

Runs the mediation engine over the adversarial fixture corpus (Step 3) and
compares every CB/HP/WP row against the consolidated expected manifest
(``expected_manifest.yml``).  The PR protocol rows (PR-01..PR-08) are
CLI-protocol expectations validated by the shadow CLI's own test battery.

Scenario wiring (this module): each fixture row names its subject —
  * CB rows: the (caller method, callee name) call sites whose resolution
    must equal ``expectedResolution``;
  * HP rows: the synthetic mutation (``store.put``) inside the named helper
    callable, proved with barrierMode ``helper``;
  * WP rows: the synthetic mutation site in the named callable, proved with
    barrierMode ``workerMediated``; the site selector is the first
    ``store.put`` NOT inside a canonical scope (falling back to the first),
    except WP-17 whose subject is the first statement inside the waived
    read-only-backup guard scope.

The fixture worker registry is data-driven: every discovered worker class
is registered EXCEPT ``Wp14UnregisteredWorker`` and the duplicate
``DuplicateWorker`` (WP-14/WP-15 exercise the registry cross-check and the
ambiguous-identity path).  WP-15a/WP-15b are analyzed TOGETHER (one corpus,
two files) so the duplicate FQCN is visible to the engine.

Exit contract: 0 every row matches; 1 one or more mismatches; 2
infrastructure failure (missing fixture/manifest, engine crash).
"""
from __future__ import annotations

import os
import sys

_PROJECT_ROOT = os.path.dirname(
    os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
)
if _PROJECT_ROOT not in sys.path:
    sys.path.insert(0, _PROJECT_ROOT)

from scripts.db_guard.mediation_analysis.callgraph import (  # noqa: E402
    AnalysisContract,
    CallGraphBuilder,
)
from scripts.db_guard.mediation_analysis.models import ProofState  # noqa: E402
from scripts.db_guard.mediation_analysis.proof import (  # noqa: E402
    MediationProver,
    MutationSubject,
)
from scripts.db_guard.mediation_analysis import test_fixtures  # noqa: E402
from scripts.db_guard.mediation_analysis.worker_recognition import (  # noqa: E402
    discover_worker_roots,
)

__all__ = ["run_fixture_scenarios", "main"]

FIXTURE_WORKER_GUARD_FQCN = "fixtures.workerproof.WorkerExecutionGuard"
FIXTURE_WORKER_BASE_FQCN = "fixtures.workerproof.CoroutineWorker"
UNREGISTERED_FIXTURE_WORKERS = frozenset({
    "fixtures.workerproof.Wp14UnregisteredWorker",
    "fixtures.workerproof.DuplicateWorker",
})

FIXTURE_CONTRACT = AnalysisContract(
    worker_guard_receiver_fqcn=FIXTURE_WORKER_GUARD_FQCN,
    worker_guard_scope_methods=("runGuarded", "runGuardedWithContext"),
    direct_scope_receiver_fqcn="",
    direct_scope_methods=("withWriteBarrier",),
    direct_scope_allow_receiverless=True,
    worker_base_fqcns=(FIXTURE_WORKER_BASE_FQCN,),
)

# CB rows: every listed (caller method, callee name) pair must resolve to
# the row's expectedResolution.
CB_SUBJECT_CALLS = {
    "CB-01": (("exerciseInt", "send"), ("exerciseLabel", "send")),
    "CB-02": (("exercise", "innerValue"),),
    "CB-03": (("exerciseAlpha", "Profile"), ("exerciseBeta", "Profile")),
    "CB-04": (("exerciseCb04", "render"),),
    "CB-05": (("exerciseCb05", "missing"),),
    "CB-06": (("exerciseCb06", "accept"),),
    "CB-07": (("exerciseCb07", "describe"),),
    "CB-08": (("exerciseAlpha", "helperName"), ("exerciseBeta", "helperName")),
    "CB-09": (("exercise", "compute"),),
    "CB-10": (("exerciseStrings", "pick"), ("exerciseInts", "pick")),
    "CB-11": (("exerciseCb11", "exactTopLevel"),),
    "CB-12": (("exercise", "base"),),
    "CB-13": (("exercise", "uniqueNormalize"),),
    "CB-14": (("exerciseCb14", "qualifiedTopLevel"),),
}

# HP rows: (helper method) owning the synthetic store.put mutation.
HP_SUBJECT_HELPERS = {
    "HP-01": "writeRow",
    "HP-02": "writeRow",
    "HP-03": "writeRow",
    "HP-04": "writeRow",
    "HP-05": "writeRow",
    "HP-06": "writeRow",
    "HP-07": "writeRowA",
    "HP-08": "writeRow",
    "HP-09": "writeRow",
    "HP-10": "writeRow",
    "HP-11": "writeRow",
    "HP-12": "writeRow",
}

# WP rows: (owner simple name, callable method, site selector).
WP_SUBJECTS = {
    "WP-01": ("Wp01DirectWorker", "doWork", "store_put"),
    "WP-02": ("Wp02FqcnBaseWorker", "doWork", "store_put"),
    "WP-03": ("Wp03AliasWorker", "doWork", "store_put"),
    "WP-04": ("Wp04LeafWorker", "doWork", "store_put"),
    "WP-05": ("Wp05Outer.Wp05NestedWorker", "doWork", "store_put"),
    "WP-06": ("Wp06GuardLambdaMutation", "writeRow", "store_put"),
    "WP-07": ("Wp07MutationBeforeGuard", "doWork", "store_put"),
    "WP-08": ("Wp08MutationAfterScopeExit", "doWork", "store_put"),
    "WP-09": ("Wp09DeadGuardHelper", "doWork", "store_put"),
    "WP-10": ("Wp10HelperInsideGuard", "writeRow", "store_put"),
    "WP-11": ("Wp11SharedHelper", "writeRow", "store_put"),
    "WP-12": ("Wp12WrongReceiver", "doWork", "store_put"),
    "WP-13": ("Wp13LaunchEscape", "doWork", "store_put"),
    "WP-14": ("Wp14UnregisteredWorker", "doWork", "store_put"),
    "WP-15a": ("DuplicateWorker", "doWork", "store_put"),
    "WP-15b": ("DuplicateWorker", "doWork", "store_put"),
    "WP-16": ("Wp16CancellationPath", "doWork", "store_put"),
    "WP-17": ("Wp17CheckpointNotScope", "doWork", "waived_site"),
}

WP_EXTRA_FILES = {
    "WP-15a": ("worker_proof/wp15_duplicate_worker_b.kt",),
    "WP-15b": ("worker_proof/wp15_duplicate_worker_a.kt",),
}


def _load_expected_rows(fixtures_dir):
    rows = {}
    for row in test_fixtures.parse_rows(
        os.path.join(fixtures_dir, test_fixtures.CONSOLIDATED_MANIFEST)
    ):
        rows[row["id"]] = row
    return rows


def _build_scenario_corpus(fixtures_dir, fixture_file, extra_files):
    files = {}
    for relative in (fixture_file,) + tuple(extra_files):
        path = os.path.join(fixtures_dir, relative)
        with open(path, "r", encoding="utf-8") as handle:
            files[relative] = handle.read()
    return files


def _find_callable(builder, file_path, owner_simple, method):
    matches = []
    for key, model in builder.callables.items():
        if model.file != file_path or model.method != method:
            continue
        if owner_simple is not None and not model.owner_fqcn.endswith(
            "." + owner_simple
        ) and model.owner_fqcn != owner_simple:
            continue
        matches.append(model)
    if len(matches) != 1:
        return None
    return matches[0]


def _mutation_site_in(builder, model, selector):
    """Exact synthetic-mutation offset for one scenario subject."""
    regions = builder.lambda_regions_by_callable.get(model.key, ())
    if selector == "waived_site":
        for region in regions:
            if region.carrier == "canonical_worker" and region.waived:
                return region.start
        return None
    calls = builder.calls_by_callable.get(model.key, ())
    candidates = [
        call
        for call in calls
        if call.name == "put"
        and (call.receiver_text == "store" or call.qualification == "store")
    ]
    if not candidates:
        return None

    def _innermost_canonical(offset):
        best = None
        for region in regions:
            if region.start <= offset < region.end and region.carrier in (
                "canonical_worker",
                "canonical_direct",
            ):
                if best is None or region.start > best.start:
                    best = region
        return best

    for call in candidates:
        if _innermost_canonical(call.name_start) is None:
            return call.name_start
    return candidates[0].name_start


def _edge_state_for(builder, file_path, caller_method, callee_name):
    """Resolution state of the (caller, callee) subject call, or None."""
    states = set()
    found = False
    for edge in builder.edges:
        model = builder.callables.get(edge.caller_key)
        if model is None or model.file != file_path or model.method != caller_method:
            continue
        # Match the callee by its resolved targets' method names; unresolved
        # edges carry no targets, so the call NAME is matched via the
        # extracted call records at the same offset.
        call = None
        for record in builder.calls_by_callable.get(edge.caller_key, ()):
            if record.name_start == edge.name_start:
                call = record
                break
        if call is None or call.name != callee_name:
            continue
        found = True
        states.add(edge.state.value)
    if not found or len(states) != 1:
        return None
    return next(iter(states))


def run_fixture_scenarios(fixtures_dir=None):
    """Run every CB/HP/WP scenario; returns a list of result dicts."""
    if fixtures_dir is None:
        fixtures_dir = test_fixtures.FIXTURES_DIR
    expected_rows = _load_expected_rows(fixtures_dir)
    results = []
    builder_cache: dict[tuple, CallGraphBuilder] = {}
    for fixture_id in sorted(expected_rows):
        row = expected_rows[fixture_id]
        if fixture_id.startswith("PR-"):
            continue
        fixture_file = row["file"]
        extra = WP_EXTRA_FILES.get(fixture_id, ())
        corpus_key = (fixture_file, extra)
        entry = {
            "id": fixture_id,
            "expectedResolution": row.get("expectedResolution"),
            "expectedProof": row.get("expectedProof"),
            "expectedRoot": row.get("expectedRoot"),
        }
        try:
            if corpus_key not in builder_cache:
                files = _build_scenario_corpus(fixtures_dir, fixture_file, extra)
                builder_cache[corpus_key] = CallGraphBuilder(FIXTURE_CONTRACT, files)
            builder = builder_cache[corpus_key]
            graph = builder.build()
            file_path = fixture_file
            if fixture_id.startswith("CB-"):
                actual_states = []
                for caller_method, callee_name in CB_SUBJECT_CALLS[fixture_id]:
                    state = _edge_state_for(
                        builder, file_path, caller_method, callee_name
                    )
                    actual_states.append(state)
                unique = set(actual_states)
                entry["actualResolution"] = (
                    next(iter(unique)) if len(unique) == 1 else None
                )
                entry["actualProof"] = None
                entry["actualRoot"] = None
            else:
                if fixture_id.startswith("HP-"):
                    owner_simple = None
                    method = HP_SUBJECT_HELPERS[fixture_id]
                    mode = "helper"
                else:
                    owner_simple, method, selector = WP_SUBJECTS[fixture_id]
                    mode = "workerMediated"
                model = _find_callable(builder, file_path, owner_simple, method)
                if model is None:
                    entry["actualResolution"] = None
                    entry["actualProof"] = "SUBJECT_UNRESOLVED"
                    entry["actualRoot"] = None
                    results.append(entry)
                    continue
                site = _mutation_site_in(builder, model, WP_SUBJECTS[fixture_id][2] if fixture_id.startswith("WP-") else "store_put")
                discovered = builder.worker_classes()
                registered = tuple(
                    fqcn for fqcn in discovered if fqcn not in UNREGISTERED_FIXTURE_WORKERS
                )
                discovery = discover_worker_roots(builder, registered)
                valid_roots = frozenset(discovery.do_work_roots_registered)
                ambiguous_classes = frozenset(discovery.ambiguous_worker_classes)
                prover = MediationProver(
                    builder,
                    graph,
                    registered_do_work_roots=valid_roots,
                    ambiguous_worker_classes=ambiguous_classes,
                )
                subject = MutationSubject(
                    mutation_key=fixture_id,
                    callable_key=model.key,
                    barrier_mode=mode,
                    site_start=site,
                )
                proof = prover.prove(subject)
                entry["actualResolution"] = (
                    proof.deciding_resolution.value
                    if proof.deciding_resolution is not None
                    else None
                )
                entry["actualProof"] = proof.proof_state.value
                entry["actualRoot"] = _deciding_root(proof)
        except Exception as error:  # noqa: BLE001 - scenario isolation
            entry["actualResolution"] = None
            entry["actualProof"] = "INFRASTRUCTURE_FAILURE: %s" % type(error).__name__
            entry["actualRoot"] = None
        results.append(entry)
    return results


def _deciding_root(proof) -> str | None:
    kinds = proof.reaching_root_kinds
    if not kinds:
        return None
    order = (
        "worker_do_work",
        "framework_callback",
        "public_or_protected_external",
        "top_level_external",
        "constructor_external",
        "unknown_external",
    )
    for kind in order:
        if kind in kinds:
            return kind.upper()
    return sorted(kinds)[0].upper()


def main(argv=None) -> int:
    results = run_fixture_scenarios()
    failures = []
    for entry in results:
        mismatches = []
        for field in ("Resolution", "Proof", "Root"):
            expected = entry.get("expected" + field)
            actual = entry.get("actual" + field)
            if expected is None:
                continue
            if actual is None or actual.upper() != expected.upper():
                mismatches.append(
                    "%s: expected %s got %s" % (field, expected, actual)
                )
        if mismatches:
            failures.append((entry["id"], mismatches))
    for fixture_id, mismatches in failures:
        print("FAIL %s: %s" % (fixture_id, "; ".join(mismatches)))
    print(
        "fixture scenarios: %d checked, %d passed, %d failed"
        % (len(results), len(results) - len(failures), len(failures))
    )
    if failures:
        return 1
    print("OK")
    return 0


if __name__ == "__main__":
    sys.exit(main())
