"""Contract tests for the neutral production source-scope authority
(``scripts/guardrails/production_source_scope.py``, PR-GR-10B Slice 1).

Ports the proven ``scripts/test_db_guard_source_roots.py`` coverage
(manifest validation matrix, YAML loading, filesystem topology
verification, deterministic Kotlin-file enumeration, canonical source-file
resolution, declared-path membership, immutable models) to the neutral
module, and adds the PR-GR-10B Slice 1 contracts:

* ``ProductionSourceFile`` value-object shape (repo-relative POSIX reports,
  regular readable ``.kt`` only, symlink-escape fail-closed, deterministic
  root-order then path-order traversal);
* repository-level NO-fallback enforcement (manifest absent/malformed/
  undeclared/mismatch -> controlled diagnostics, never the conventional
  ``app/src/main/java`` fallback);
* ``scope_evidence()`` hashing (ordered file-list hash, manifest hash);
* ``scripts/db_guard/source_roots.py`` re-export identity (same objects and
  behavior through both import paths, legacy GR-01 contract confined to the
  DB layer).

Filesystem tests use ``tmp_path``; symlink cases skip themselves on
platforms without symlink privileges (e.g. Windows without Developer Mode).

Run: python -m pytest scripts/guardrails/test_production_source_scope.py -v
"""

from __future__ import annotations

import dataclasses
import hashlib
import inspect
import os
import sys
from pathlib import Path

import pytest
import yaml

# sys.path bootstrap (sibling-test convention): every import below resolves
# through the repository-root namespace (``scripts.guardrails.*`` /
# ``scripts.db_guard.*``).
_REPO_ROOT = Path(__file__).resolve().parents[2]
_REPO_ROOT_STR = str(_REPO_ROOT)
if _REPO_ROOT_STR not in sys.path:
    sys.path.insert(0, _REPO_ROOT_STR)

from scripts.db_guard import source_roots as db_source_roots  # noqa: E402
from scripts.db_guard.policy_errors import (  # noqa: E402
    POLICY_ERROR_PATH_OUTSIDE_APPROVED_ROOT,
)
from scripts.guardrails import production_source_scope as scope  # noqa: E402
from scripts.guardrails.production_source_scope import (  # noqa: E402
    PRODUCTION_SOURCE_SCOPE_DIAGNOSTIC_CODES,
    PRODUCTION_SOURCE_SCOPE_LAYOUT_UNSUPPORTED,
    PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID,
    PRODUCTION_SOURCE_SCOPE_MANIFEST_RELPATH,
    PRODUCTION_SOURCE_SCOPE_SCHEMA_VERSION,
    PRODUCTION_SOURCE_SCOPE_SYMLINK_OUTSIDE,
    PRODUCTION_SOURCE_SCOPE_UNDECLARED,
    PRODUCTION_SOURCE_SCOPE_UNREADABLE,
    ProductionSourceFile,
    ProductionSourceScopeError,
    ProductionSourceScopeEvidence,
    SourceRoot,
    SourceRootSet,
    collect_production_source_files,
    is_declared_production_path,
    iter_production_kotlin_files,
    load_production_source_manifest,
    resolve_production_kotlin_file,
    resolve_production_source_scope,
    resolve_source_root_set_for_test_fixtures,
    scope_evidence,
    validate_production_source_manifest,
)

APP_ROOT = "app/src/main/java"
KOTLIN_ROOT = "core/data/src/main/kotlin"
APP_FILE = APP_ROOT + "/com/example/expensetracker/AppDatabase.kt"


# ── Helpers ───────────────────────────────────────────────────────────────────


def _manifest(*roots):
    """Build a manifest payload from ``(module, path)`` pairs."""
    return {
        "schemaVersion": PRODUCTION_SOURCE_SCOPE_SCHEMA_VERSION,
        "roots": [
            {"module": module, "sourceSet": "main", "path": path}
            for module, path in roots
        ],
    }


def _write_manifest(tmp_path, payload):
    manifest_path = tmp_path / "source_roots.yaml"
    manifest_path.write_text(yaml.safe_dump(payload), encoding="utf-8")
    return str(manifest_path)


def _write_repo_manifest(repo_root, payload):
    """Write the checked-in-style manifest at its canonical location."""
    manifest_path = repo_root / "config" / "guards" / "production_source_roots.yml"
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(yaml.safe_dump(payload), encoding="utf-8")
    return manifest_path


def _codes(diagnostics):
    return [code for code, _context in diagnostics]


def _root_set(*paths):
    return SourceRootSet(
        tuple(SourceRoot(module=":app", source_set="main", path=p) for p in paths)
    )


def _make_tree(tmp_path, files):
    for rel in files:
        target = tmp_path.joinpath(*rel.split("/"))
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text("// kt\n", encoding="utf-8")


def _make_dir_symlink(link_abs, target_abs):
    """Create a directory symlink; return False when unsupported."""
    try:
        os.symlink(target_abs, link_abs, target_is_directory=True)
    except (OSError, NotImplementedError):
        return False
    return True


def _make_file_symlink(link_abs, target_abs):
    """Create a file symlink; return False when unsupported."""
    try:
        os.symlink(target_abs, link_abs)
    except (OSError, NotImplementedError):
        return False
    return True


# ── Manifest validation matrix ───────────────────────────────────────────────


def test_valid_manifests_pass():
    assert validate_production_source_manifest(_manifest((":app", APP_ROOT))) == ()
    payload = _manifest((":app", APP_ROOT), (":core:data", KOTLIN_ROOT))
    assert validate_production_source_manifest(payload) == ()
    payload = _manifest((":app", APP_ROOT), (":app", "app/src/main/kotlin"))
    assert validate_production_source_manifest(payload) == ()


def test_schema_version_matrix_rejected():
    # Missing schemaVersion.
    payload = {"roots": [{"module": ":app", "sourceSet": "main", "path": APP_ROOT}]}
    diagnostics = validate_production_source_manifest(payload)
    assert _codes(diagnostics) == [PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID]
    assert diagnostics[0][1] == {"field": "schemaVersion"}
    for version in (2, "1", None, True):
        payload = _manifest((":app", APP_ROOT))
        payload["schemaVersion"] = version
        diagnostics = validate_production_source_manifest(payload)
        assert _codes(diagnostics) == [PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID]
        assert diagnostics[0][1] == {"field": "schemaVersion"}


def test_unknown_keys_rejected():
    payload = _manifest((":app", APP_ROOT))
    payload["extra"] = 1
    diagnostics = validate_production_source_manifest(payload)
    assert _codes(diagnostics) == [PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID]
    assert diagnostics[0][1] == {"field": "extra"}

    payload = _manifest((":app", APP_ROOT))
    payload["roots"][0]["sourceRoot"] = "oops"
    diagnostics = validate_production_source_manifest(payload)
    assert _codes(diagnostics) == [PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID]
    assert diagnostics[0][1] == {"field": "sourceRoot", "index": 0}


@pytest.mark.parametrize(
    "module", [":", "app", "app/src"]
)
def test_invalid_module_rejected(module):
    diagnostics = validate_production_source_manifest(_manifest((module, APP_ROOT)))
    assert _codes(diagnostics) == [PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID]
    assert diagnostics[0][1] == {"field": "module", "index": 0}


@pytest.mark.parametrize("source_set", ["debug", "release", "test"])
def test_unsupported_source_set_rejected(source_set):
    payload = _manifest((":app", APP_ROOT))
    payload["roots"][0]["sourceSet"] = source_set
    diagnostics = validate_production_source_manifest(payload)
    assert _codes(diagnostics) == [PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID]
    assert diagnostics[0][1] == {"field": "sourceSet", "index": 0}


@pytest.mark.parametrize(
    ("path", "reason"),
    [
        ("/abs/src/main/java", "absolute"),
        ("C:/x/src/main/java", "absolute"),
        ("app\\src\\main\\java", "backslash"),
        ("app/../app/src/main/java", "bad-segment"),
        ("./app/src/main/java", "bad-segment"),
        ("app/src/main/java/../java", "bad-segment"),
        ("*/src/main/java", "wildcard"),
        ("app/*/src/main/java", "wildcard"),
        ("app/src/main/java/com", "unsupported-tail"),
        ("test/src/main/java", "forbidden-segment"),
        ("androidTest/src/main/java", "forbidden-segment"),
        ("debug/src/main/java", "forbidden-segment"),
        ("release/src/main/java", "forbidden-segment"),
        ("generated/src/main/java", "forbidden-segment"),
        ("build/src/main/java", "forbidden-segment"),
        ("app/build/src/main/java", "forbidden-segment"),
    ],
)
def test_invalid_root_paths_rejected(path, reason):
    diagnostics = validate_production_source_manifest(_manifest((":app", path)))
    assert _codes(diagnostics) == [PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID]
    assert diagnostics[0][1] == {"field": "path", "index": 0, "reason": reason}


def test_duplicate_overlap_and_order_rejected():
    payload = _manifest((":app", APP_ROOT), (":core:data", APP_ROOT))
    diagnostics = validate_production_source_manifest(payload)
    assert _codes(diagnostics) == [PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID]
    assert diagnostics[0][1] == {
        "field": "path",
        "index": 1,
        "reason": "duplicate-path",
    }

    payload = _manifest(
        (":lib", "lib/src/main/java"),
        (":lib", "lib/src/main/java/src/main/java"),
    )
    diagnostics = validate_production_source_manifest(payload)
    assert _codes(diagnostics) == [PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID]
    assert diagnostics[0][1] == {
        "field": "path",
        "index": 1,
        "reason": "overlapping-path",
    }

    payload = _manifest(
        (":zeta", "zeta/src/main/java"),
        (":alpha", "alpha/src/main/java"),
    )
    diagnostics = validate_production_source_manifest(payload)
    assert _codes(diagnostics) == [PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID]
    assert diagnostics[0][1] == {"field": "roots", "reason": "non-canonical-order"}


def test_malformed_structures_rejected():
    assert _codes(validate_production_source_manifest({"schemaVersion": 1, "roots": []})) == [
        PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID
    ]
    assert _codes(
        validate_production_source_manifest(
            {"schemaVersion": 1, "roots": {"module": ":app"}}
        )
    ) == [PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID]
    diagnostics = validate_production_source_manifest(["not", "a", "mapping"])
    assert _codes(diagnostics) == [PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID]
    assert diagnostics[0][1] == {"field": "<document>"}


# ── Manifest loading ─────────────────────────────────────────────────────────


def test_load_round_trip_builds_source_root_set(tmp_path):
    payload = _manifest((":app", APP_ROOT), (":core:data", KOTLIN_ROOT))
    root_set, diagnostics = load_production_source_manifest(
        _write_manifest(tmp_path, payload)
    )
    assert diagnostics == ()
    assert isinstance(root_set, SourceRootSet)
    assert root_set.paths == (APP_ROOT, KOTLIN_ROOT)
    assert root_set.roots[0] == SourceRoot(
        module=":app", source_set="main", path=APP_ROOT
    )


def test_load_failures_are_controlled(tmp_path):
    # Missing file.
    root_set, diagnostics = load_production_source_manifest(
        str(tmp_path / "nope.yaml")
    )
    assert root_set is None
    assert _codes(diagnostics) == [PRODUCTION_SOURCE_SCOPE_UNREADABLE]

    # Malformed YAML.
    manifest = tmp_path / "broken.yaml"
    manifest.write_text("schemaVersion: 1\nroots:\n\t- {}\n", encoding="utf-8")
    root_set, diagnostics = load_production_source_manifest(str(manifest))
    assert root_set is None
    assert _codes(diagnostics) == [PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID]
    assert diagnostics[0][1] == {"reason": "malformed-yaml"}

    # Non-mapping document.
    manifest = tmp_path / "list.yaml"
    manifest.write_text("- one\n- two\n", encoding="utf-8")
    root_set, diagnostics = load_production_source_manifest(str(manifest))
    assert root_set is None
    assert _codes(diagnostics) == [PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID]

    # Shape-invalid payload surfaces the validation diagnostics.
    root_set, diagnostics = load_production_source_manifest(
        _write_manifest(tmp_path, {"schemaVersion": True, "roots": []})
    )
    assert root_set is None
    assert set(_codes(diagnostics)) == {PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID}
    assert len(diagnostics) >= 2


# ── Topology verification ────────────────────────────────────────────────────


def test_topology_missing_root_is_unreadable(tmp_path):
    diagnostics = scope.verify_production_source_topology(
        str(tmp_path), _root_set(APP_ROOT)
    )
    assert _codes(diagnostics) == [PRODUCTION_SOURCE_SCOPE_UNREADABLE]
    assert diagnostics[0][1] == {"target": APP_ROOT}


def test_topology_symlinked_roots_fail_closed(tmp_path):
    def _diagnostics_for(repo):
        return scope.verify_production_source_topology(str(repo), _root_set(APP_ROOT))

    # Root symlink pointing outside the repository.
    outside = tmp_path / "outside"
    (outside / "src" / "main" / "java").mkdir(parents=True)
    repo = tmp_path / "repo1"
    link = repo.joinpath(*APP_ROOT.split("/"))
    link.parent.mkdir(parents=True)
    if not _make_dir_symlink(str(link), str(outside / "src" / "main" / "java")):
        pytest.skip("symlink creation not permitted on this platform")
    diagnostics = _diagnostics_for(repo)
    assert _codes(diagnostics) == [PRODUCTION_SOURCE_SCOPE_SYMLINK_OUTSIDE]
    assert diagnostics[0][1] == {"target": APP_ROOT}

    # Root symlink pointing inside the repository is still rejected.
    repo2 = tmp_path / "repo2"
    real = repo2 / "real"
    (real / "src" / "main" / "java").mkdir(parents=True)
    assert _make_dir_symlink(str(repo2 / "app"), str(real))
    assert _codes(_diagnostics_for(repo2)) == [PRODUCTION_SOURCE_SCOPE_SYMLINK_OUTSIDE]

    # Root resolving outside via a symlinked ancestor.
    repo3 = tmp_path / "repo3"
    repo3.mkdir()
    assert _make_dir_symlink(str(repo3 / "link"), str(outside))
    diagnostics = scope.verify_production_source_topology(
        str(repo3), _root_set("link/src/main/java")
    )
    assert _codes(diagnostics) == [PRODUCTION_SOURCE_SCOPE_SYMLINK_OUTSIDE]


# ── Deterministic enumeration ────────────────────────────────────────────────


def test_enumeration_is_deterministic_root_order_then_path_order(tmp_path):
    _make_tree(
        tmp_path,
        [
            APP_ROOT + "/com/example/z/Zeta.kt",
            APP_ROOT + "/com/example/a/Alpha.kt",
            APP_ROOT + "/com/example/Notes.md",
            KOTLIN_ROOT + "/com/example/b/Baz.kt",
            KOTLIN_ROOT + "/com/example/a/Bar.kt",
        ],
    )
    root_set = _root_set(APP_ROOT, KOTLIN_ROOT)
    first = [
        source_file.repository_relative_path
        for source_file in iter_production_kotlin_files(str(tmp_path), root_set)
    ]
    second = [
        source_file.repository_relative_path
        for source_file in iter_production_kotlin_files(str(tmp_path), root_set)
    ]
    assert first == second
    assert first == [
        APP_ROOT + "/com/example/a/Alpha.kt",
        APP_ROOT + "/com/example/z/Zeta.kt",
        KOTLIN_ROOT + "/com/example/a/Bar.kt",
        KOTLIN_ROOT + "/com/example/b/Baz.kt",
    ]


def test_iter_yields_production_source_file_values(tmp_path):
    _make_tree(tmp_path, [APP_FILE, KOTLIN_ROOT + "/com/example/b/Baz.kt"])
    root_set = _root_set(APP_ROOT, KOTLIN_ROOT)
    files = list(iter_production_kotlin_files(str(tmp_path), root_set))
    assert [f.module for f in files] == [":app", ":app"]
    assert [f.source_set for f in files] == ["main", "main"]
    # Root order is preserved: every app-root file precedes every other root.
    assert all(
        f.repository_relative_path.startswith(APP_ROOT + "/") for f in files[:1]
    )
    first = files[0]
    assert isinstance(first, ProductionSourceFile)
    assert first.repository_relative_path == APP_FILE
    assert "\\" not in first.repository_relative_path
    expected_abs = os.path.join(str(tmp_path), *APP_FILE.split("/"))
    assert os.path.normcase(first.absolute_path) == os.path.normcase(expected_abs)
    expected_root = os.path.join(str(tmp_path), *APP_ROOT.split("/"))
    assert os.path.normcase(first.root_path) == os.path.normcase(expected_root)
    # Plan-literal camelCase aliases mirror the snake_case fields.
    assert first.repositoryRelativePath == first.repository_relative_path
    assert first.absolutePath == first.absolute_path
    assert first.rootPath == first.root_path
    assert first.sourceSet == first.source_set


def test_iter_ignores_non_kotlin_and_handles_empty_trees(tmp_path):
    _make_tree(tmp_path, [APP_ROOT + "/com/example/Notes.md"])
    root_set = _root_set(APP_ROOT)
    assert list(iter_production_kotlin_files(str(tmp_path), root_set)) == []
    collected, diagnostics = collect_production_source_files(str(tmp_path), root_set)
    assert collected == ()
    assert diagnostics == ()


def test_enumeration_fails_closed_on_unreadable_or_unlistable(tmp_path, monkeypatch):
    _make_tree(tmp_path, [APP_FILE])
    root_set = _root_set(APP_ROOT)
    real_access = os.access
    target_abs = os.path.join(str(tmp_path), *APP_FILE.split("/"))

    def fake_access(path, mode):
        if os.path.abspath(str(path)) == os.path.abspath(target_abs):
            return False
        return real_access(path, mode)

    monkeypatch.setattr(os, "access", fake_access)
    collected, diagnostics = collect_production_source_files(str(tmp_path), root_set)
    assert collected == ()
    assert _codes(diagnostics) == [PRODUCTION_SOURCE_SCOPE_UNREADABLE]
    assert diagnostics[0][1] == {"target": APP_ROOT}
    with pytest.raises(ProductionSourceScopeError) as excinfo:
        list(iter_production_kotlin_files(str(tmp_path), root_set))
    assert excinfo.value.code == PRODUCTION_SOURCE_SCOPE_UNREADABLE

    monkeypatch.undo()
    (tmp_path / "app").mkdir(exist_ok=True)

    def fake_listdir(path):
        raise PermissionError(13, "denied")

    monkeypatch.setattr(os, "listdir", fake_listdir)
    collected, diagnostics = collect_production_source_files(str(tmp_path), root_set)
    assert collected == ()
    assert _codes(diagnostics) == [PRODUCTION_SOURCE_SCOPE_UNREADABLE]
    assert diagnostics[0][1] == {"target": APP_ROOT}


def test_enumeration_symlink_escape_fails_closed(tmp_path):
    outside = tmp_path / "outside"
    escaped_file = outside / "Escaped.kt"
    escaped_file.parent.mkdir(parents=True)
    escaped_file.write_text("// kt\n", encoding="utf-8")
    repo = tmp_path / "repo"
    java_dir = repo.joinpath(*APP_ROOT.split("/"))
    java_dir.mkdir(parents=True)
    root_set = _root_set(APP_ROOT)

    # Symlinked .kt file pointing outside the repository.
    if not _make_file_symlink(str(java_dir / "Escape.kt"), str(escaped_file)):
        pytest.skip("symlink creation not permitted on this platform")
    collected, diagnostics = collect_production_source_files(str(repo), root_set)
    assert collected == ()
    assert _codes(diagnostics) == [PRODUCTION_SOURCE_SCOPE_SYMLINK_OUTSIDE]
    assert diagnostics[0][1] == {"target": APP_ROOT}
    with pytest.raises(ProductionSourceScopeError) as excinfo:
        list(iter_production_kotlin_files(str(repo), root_set))
    assert excinfo.value.code == PRODUCTION_SOURCE_SCOPE_SYMLINK_OUTSIDE

    # Symlinked directory inside the declared root is refused as well
    # (fresh repo so the traversal actually reaches the link).
    repo2 = tmp_path / "repo2"
    java_dir2 = repo2.joinpath(*APP_ROOT.split("/"))
    java_dir2.mkdir(parents=True)
    if not _make_dir_symlink(str(java_dir2 / "linked"), str(outside)):
        pytest.skip("symlink creation not permitted on this platform")
    collected, diagnostics = collect_production_source_files(str(repo2), root_set)
    assert collected == ()
    assert _codes(diagnostics) == [PRODUCTION_SOURCE_SCOPE_SYMLINK_OUTSIDE]
    assert diagnostics[0][1] == {"target": APP_ROOT}


# ── Safe source-file resolution ──────────────────────────────────────────────


def test_resolve_production_kotlin_file_happy_path(tmp_path):
    _make_tree(tmp_path, [APP_FILE])
    root_set = _root_set(APP_ROOT)
    source_file, code = resolve_production_kotlin_file(
        str(tmp_path), root_set, APP_FILE
    )
    assert code is None
    assert isinstance(source_file, ProductionSourceFile)
    assert source_file.repository_relative_path == APP_FILE
    expected = os.path.join(str(tmp_path), *APP_FILE.split("/"))
    assert os.path.normcase(source_file.absolute_path) == os.path.normcase(expected)
    assert source_file.module == ":app"
    assert source_file.source_set == "main"
    assert os.path.normcase(source_file.root_path) == os.path.normcase(
        os.path.join(str(tmp_path), *APP_ROOT.split("/"))
    )


def test_resolve_failure_matrix(tmp_path):
    root_set = _root_set(APP_ROOT)
    # Traversal never resolves.
    resolved, code = resolve_production_kotlin_file(
        "unused", root_set, APP_ROOT + "/../hidden/X.kt"
    )
    assert resolved is None
    assert code == PRODUCTION_SOURCE_SCOPE_LAYOUT_UNSUPPORTED
    # Non-Kotlin targets are layout-unsupported.
    resolved, code = resolve_production_kotlin_file(
        "unused", root_set, APP_ROOT + "/com/example/Notes.md"
    )
    assert resolved is None
    assert code == PRODUCTION_SOURCE_SCOPE_LAYOUT_UNSUPPORTED
    # Undeclared path (outside every declared root).
    resolved, code = resolve_production_kotlin_file(
        "unused", root_set, "feature/x/src/main/java/X.kt"
    )
    assert resolved is None
    assert code == PRODUCTION_SOURCE_SCOPE_UNDECLARED
    # Missing file is unreadable.
    resolved, code = resolve_production_kotlin_file(
        str(tmp_path), root_set, APP_FILE
    )
    assert resolved is None
    assert code == PRODUCTION_SOURCE_SCOPE_UNREADABLE


def test_resolve_symlink_escape_rejected(tmp_path):
    outside = tmp_path / "outside"
    escaped_file = outside / "src" / "main" / "java" / "com" / "example" / "X.kt"
    escaped_file.parent.mkdir(parents=True)
    escaped_file.write_text("// kt\n", encoding="utf-8")
    repo = tmp_path / "repo"
    repo.mkdir()
    if not _make_dir_symlink(str(repo / "app"), str(outside)):
        pytest.skip("symlink creation not permitted on this platform")
    resolved, code = resolve_production_kotlin_file(
        str(repo),
        _root_set(APP_ROOT),
        APP_ROOT + "/com/example/X.kt",
    )
    assert resolved is None
    assert code == PRODUCTION_SOURCE_SCOPE_SYMLINK_OUTSIDE


# ── Declared-path membership ─────────────────────────────────────────────────


@pytest.mark.parametrize(
    "rel",
    [
        APP_ROOT,  # the root itself counts as a member
        APP_FILE,
        KOTLIN_ROOT + "/com/example/Foo.kt",
    ],
)
def test_declared_membership_true(rel):
    root_set = _root_set(APP_ROOT, KOTLIN_ROOT)
    assert is_declared_production_path(root_set, rel) is True


@pytest.mark.parametrize(
    "rel",
    [
        "app2/src/main/java/X.kt",  # sloppy string prefix guard
        APP_ROOT + "2/X.kt",  # segment-aligned prefix guard
        "feature/x/src/main/java/X.kt",
        APP_ROOT + "/../hidden/X.kt",  # traversal never a member
        "",
        None,
        42,
        "C:/tools/X.kt",
        "app\\src\\main\\java\\X.kt",
        "/abs/X.kt",
    ],
)
def test_declared_membership_false(rel):
    root_set = _root_set(APP_ROOT, KOTLIN_ROOT)
    assert is_declared_production_path(root_set, rel) is False


# ── Immutable models ─────────────────────────────────────────────────────────


def test_source_root_models_preserve_order_and_are_frozen():
    root_set = SourceRootSet(
        (
            SourceRoot(module=":b", source_set="main", path="b/src/main/java"),
            SourceRoot(module=":a", source_set="main", path="a/src/main/java"),
        )
    )
    assert root_set.paths == ("b/src/main/java", "a/src/main/java")
    root = SourceRoot(module=":app", source_set="main", path=APP_ROOT)
    with pytest.raises(dataclasses.FrozenInstanceError):
        root.module = ":other"
    with pytest.raises(dataclasses.FrozenInstanceError):
        SourceRootSet((root,)).paths = ()


def test_production_source_file_is_frozen_value_object():
    source_file = ProductionSourceFile(
        repository_relative_path=APP_FILE,
        absolute_path="C:/repo/" + APP_FILE,
        root_path="C:/repo/" + APP_ROOT,
        module=":app",
        source_set="main",
    )
    twin = ProductionSourceFile(
        repository_relative_path=APP_FILE,
        absolute_path="C:/repo/" + APP_FILE,
        root_path="C:/repo/" + APP_ROOT,
        module=":app",
        source_set="main",
    )
    assert source_file == twin  # value-object equality
    with pytest.raises(dataclasses.FrozenInstanceError):
        source_file.module = ":other"
    with pytest.raises(dataclasses.FrozenInstanceError):
        source_file.repository_relative_path = "other/X.kt"


# ── Scope resolution: repository-level NO-fallback enforcement ──────────────


def test_resolve_explicit_root_set_wins_and_valid_manifest_returned(tmp_path):
    # A valid manifest AND the conventional root both exist, but an explicit
    # SourceRootSet is used exactly as-is (same object identity).
    _make_tree(tmp_path, [APP_FILE])
    _write_repo_manifest(tmp_path, _manifest((":app", APP_ROOT)))
    explicit = _root_set(KOTLIN_ROOT)
    resolved, diagnostics = resolve_production_source_scope(
        str(tmp_path), explicit
    )
    assert diagnostics == ()
    assert resolved is explicit

    # Without an explicit set, the checked-in manifest is verified and used.
    resolved, diagnostics = resolve_production_source_scope(str(tmp_path))
    assert diagnostics == ()
    assert resolved is not None
    assert resolved.paths == (APP_ROOT,)


def test_resolve_explicit_non_root_set_fails_closed(tmp_path):
    resolved, diagnostics = resolve_production_source_scope(
        str(tmp_path), ("app/src/main/java",)
    )
    assert resolved is None
    assert _codes(diagnostics) == [PRODUCTION_SOURCE_SCOPE_LAYOUT_UNSUPPORTED]
    assert diagnostics[0][1] == {"reason": "explicit-not-a-source-root-set"}


def test_resolve_manifest_present_but_malformed_fails_closed_without_fallback(tmp_path):
    # The conventional root exists, so an (illegal) implicit fallback would
    # succeed; the malformed manifest must fail closed instead.
    _make_tree(tmp_path, [APP_FILE])
    manifest_path = tmp_path / "config" / "guards" / "production_source_roots.yml"
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text("schemaVersion: 1\nroots:\n\t- {}\n", encoding="utf-8")
    resolved, diagnostics = resolve_production_source_scope(str(tmp_path))
    assert resolved is None
    assert _codes(diagnostics) == [PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID]


def test_resolve_manifest_declaring_missing_root_fails_closed(tmp_path):
    # Shape-valid manifest whose declared root does not exist on disk:
    # topology verification fails closed, no implicit fallback.
    _write_repo_manifest(tmp_path, _manifest((":app", APP_ROOT)))
    resolved, diagnostics = resolve_production_source_scope(str(tmp_path))
    assert resolved is None
    assert _codes(diagnostics) == [PRODUCTION_SOURCE_SCOPE_UNREADABLE]
    assert diagnostics[0][1] == {"target": APP_ROOT}


def test_resolve_manifest_absent_fails_closed_at_repository_level(tmp_path):
    # The conventional root exists (so any illegal fallback would succeed),
    # but the manifest is absent: the repository-level resolver must fail
    # closed with the controlled manifest-absent diagnostic.
    _make_tree(tmp_path, [APP_FILE])
    resolved, diagnostics = resolve_production_source_scope(str(tmp_path))
    assert resolved is None
    assert diagnostics == (
        (PRODUCTION_SOURCE_SCOPE_UNDECLARED, {"reason": "manifest-absent"}),
    )

    # A bare empty repository fails the same way (never
    # "no-conventional-root" at repository level).
    empty = tmp_path / "empty"
    empty.mkdir()
    resolved, diagnostics = resolve_production_source_scope(str(empty))
    assert resolved is None
    assert diagnostics == (
        (PRODUCTION_SOURCE_SCOPE_UNDECLARED, {"reason": "manifest-absent"}),
    )


def test_fixture_helper_is_the_only_conventional_fallback(tmp_path):
    # (a) An explicit SourceRootSet still wins.
    _make_tree(tmp_path, [APP_FILE])
    explicit = _root_set(KOTLIN_ROOT)
    resolved, diagnostics = resolve_source_root_set_for_test_fixtures(
        str(tmp_path), explicit
    )
    assert diagnostics == ()
    assert resolved is explicit

    # (b) Manifest absent + conventional root present -> implicit single
    # root carried as an ABSOLUTE native-separator path.
    resolved, diagnostics = resolve_source_root_set_for_test_fixtures(str(tmp_path))
    assert diagnostics == ()
    assert resolved is not None
    assert len(resolved.paths) == 1
    assert os.path.normcase(resolved.paths[0]) == os.path.normcase(
        os.path.join(str(tmp_path), *APP_ROOT.split("/"))
    )
    assert resolved.roots[0].module == ":implicit"
    assert resolved.roots[0].source_set == "main"

    # (c) Bare src/main/java and src/main/kotlin directories resolve to
    # themselves.
    java_dir = tmp_path.joinpath(*APP_ROOT.split("/"))
    resolved, diagnostics = resolve_source_root_set_for_test_fixtures(str(java_dir))
    assert diagnostics == ()
    assert os.path.normcase(resolved.roots[0].path) == os.path.normcase(str(java_dir))
    kotlin_dir = tmp_path.joinpath(*KOTLIN_ROOT.split("/"))
    kotlin_dir.mkdir(parents=True)
    resolved, diagnostics = resolve_source_root_set_for_test_fixtures(str(kotlin_dir))
    assert diagnostics == ()
    assert os.path.normcase(resolved.roots[0].path) == os.path.normcase(str(kotlin_dir))

    # (d) Legacy intermediate directories normalize to the java root.
    for tail in ("app/src", "app/src/main"):
        intermediate = tmp_path.joinpath(*tail.split("/"))
        resolved, diagnostics = resolve_source_root_set_for_test_fixtures(
            str(intermediate)
        )
        assert diagnostics == (), tail
        assert resolved is not None, tail
        assert os.path.normcase(resolved.roots[0].path) == os.path.normcase(
            str(java_dir)
        ), tail

    # (e) Nothing conventional at all -> controlled no-conventional-root.
    bare = tmp_path / "bare"
    bare.mkdir()
    resolved, diagnostics = resolve_source_root_set_for_test_fixtures(str(bare))
    assert resolved is None
    assert diagnostics == (
        (PRODUCTION_SOURCE_SCOPE_UNDECLARED, {"reason": "no-conventional-root"}),
    )


# ── Scope evidence ───────────────────────────────────────────────────────────


def test_scope_evidence_shape_and_determinism(tmp_path):
    _make_tree(
        tmp_path,
        [
            APP_ROOT + "/com/example/a/Alpha.kt",
            APP_ROOT + "/com/example/z/Zeta.kt",
        ],
    )
    manifest_path = _write_repo_manifest(tmp_path, _manifest((":app", APP_ROOT)))
    manifest_bytes = manifest_path.read_bytes()
    root_set = _root_set(APP_ROOT)

    evidence, diagnostics = scope_evidence(str(tmp_path), root_set, str(manifest_path))
    assert diagnostics == ()
    assert isinstance(evidence, ProductionSourceScopeEvidence)
    assert evidence.roots == (APP_ROOT,)
    assert evidence.source_file_count == 2
    assert evidence.manifest_hash == hashlib.sha256(manifest_bytes).hexdigest()
    ordered = [
        APP_ROOT + "/com/example/a/Alpha.kt",
        APP_ROOT + "/com/example/z/Zeta.kt",
    ]
    expected_list_hash = hashlib.sha256(
        b"".join(rel.encode("utf-8") + b"\n" for rel in ordered)
    ).hexdigest()
    assert evidence.ordered_file_list_hash == expected_list_hash

    repeat, _ = scope_evidence(str(tmp_path), root_set, str(manifest_path))
    assert repeat == evidence


def test_scope_evidence_file_list_hash_semantics(tmp_path):
    _make_tree(tmp_path, [APP_ROOT + "/com/example/a/Alpha.kt"])
    manifest_path = _write_repo_manifest(tmp_path, _manifest((":app", APP_ROOT)))
    root_set = _root_set(APP_ROOT)

    baseline, diagnostics = scope_evidence(
        str(tmp_path), root_set, str(manifest_path)
    )
    assert diagnostics == ()

    # Editing file CONTENT does not change the ordered file-list hash.
    alpha = tmp_path.joinpath(*(APP_ROOT + "/com/example/a/Alpha.kt").split("/"))
    alpha.write_text("// changed\n", encoding="utf-8")
    after_edit, _ = scope_evidence(str(tmp_path), root_set, str(manifest_path))
    assert after_edit.ordered_file_list_hash == baseline.ordered_file_list_hash
    assert after_edit.manifest_hash == baseline.manifest_hash
    assert after_edit.source_file_count == baseline.source_file_count

    # Adding a production file changes the count and the list hash.
    _make_tree(tmp_path, [APP_ROOT + "/com/example/b/Beta.kt"])
    after_add, _ = scope_evidence(str(tmp_path), root_set, str(manifest_path))
    assert after_add.source_file_count == baseline.source_file_count + 1
    assert after_add.ordered_file_list_hash != baseline.ordered_file_list_hash

    # Renaming (reordering the canonical path list) changes the list hash.
    beta = tmp_path.joinpath(*(APP_ROOT + "/com/example/b/Beta.kt").split("/"))
    beta.rename(beta.with_name("Aardvark.kt"))
    after_rename, _ = scope_evidence(str(tmp_path), root_set, str(manifest_path))
    assert after_rename.source_file_count == after_add.source_file_count
    assert after_rename.ordered_file_list_hash != after_add.ordered_file_list_hash


def test_scope_evidence_fails_closed(tmp_path, monkeypatch):
    _make_tree(tmp_path, [APP_FILE])
    root_set = _root_set(APP_ROOT)
    manifest_path = str(tmp_path / "config" / "guards" / "nope.yml")

    # Missing manifest.
    evidence, diagnostics = scope_evidence(str(tmp_path), root_set, manifest_path)
    assert evidence is None
    assert _codes(diagnostics) == [PRODUCTION_SOURCE_SCOPE_UNREADABLE]
    assert diagnostics[0][1] == {"target": "manifest"}

    # Unreadable production tree propagates the enumeration diagnostic.
    real_access = os.access
    target_abs = os.path.join(str(tmp_path), *APP_FILE.split("/"))

    def fake_access(path, mode):
        if os.path.abspath(str(path)) == os.path.abspath(target_abs):
            return False
        return real_access(path, mode)

    monkeypatch.setattr(os, "access", fake_access)
    good_manifest = _write_repo_manifest(tmp_path, _manifest((":app", APP_ROOT)))
    evidence, diagnostics = scope_evidence(
        str(tmp_path), root_set, str(good_manifest)
    )
    assert evidence is None
    assert _codes(diagnostics) == [PRODUCTION_SOURCE_SCOPE_UNREADABLE]
    assert diagnostics[0][1] == {"target": APP_ROOT}


# ── DB compatibility re-export identity (scripts/db_guard/source_roots) ─────


def test_db_guard_reexports_are_the_neutral_objects():
    # Models and vocabulary: identical objects through both import paths.
    assert db_source_roots.SourceRoot is scope.SourceRoot
    assert db_source_roots.SourceRootSet is scope.SourceRootSet
    assert db_source_roots.ProductionSourceFile is scope.ProductionSourceFile
    assert db_source_roots.SOURCE_ROOT_DIAGNOSTIC_CODES is (
        scope.PRODUCTION_SOURCE_SCOPE_DIAGNOSTIC_CODES
    )
    assert db_source_roots.DB_SOURCE_ROOT_MANIFEST_INVALID == (
        scope.PRODUCTION_SOURCE_SCOPE_MANIFEST_INVALID
    )
    assert db_source_roots.DB_SOURCE_ROOT_UNDECLARED == (
        scope.PRODUCTION_SOURCE_SCOPE_UNDECLARED
    )
    assert db_source_roots.DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED == (
        scope.PRODUCTION_SOURCE_SCOPE_LAYOUT_UNSUPPORTED
    )
    assert db_source_roots.DB_SOURCE_ROOT_UNREADABLE == (
        scope.PRODUCTION_SOURCE_SCOPE_UNREADABLE
    )
    assert db_source_roots.DB_SOURCE_ROOT_SYMLINK_OUTSIDE == (
        scope.PRODUCTION_SOURCE_SCOPE_SYMLINK_OUTSIDE
    )
    assert db_source_roots.SOURCE_ROOT_MANIFEST_SCHEMA_VERSION == (
        scope.PRODUCTION_SOURCE_SCOPE_SCHEMA_VERSION
    )
    assert db_source_roots.SOURCE_ROOT_MANIFEST_RELPATH == (
        scope.PRODUCTION_SOURCE_SCOPE_MANIFEST_RELPATH
    )
    # Functions: identical objects (pure re-exports / aliases).
    assert db_source_roots.validate_source_root_manifest is (
        scope.validate_production_source_manifest
    )
    assert db_source_roots.load_source_root_manifest is (
        scope.load_production_source_manifest
    )
    assert db_source_roots.verify_declared_root_topology is (
        scope.verify_production_source_topology
    )
    assert db_source_roots.is_declared_production_path is (
        scope.is_declared_production_path
    )
    assert db_source_roots.resolve_source_root_set is (
        scope.resolve_source_root_set_for_test_fixtures
    )
    assert db_source_roots.resolve_source_root_set_for_test_fixtures is (
        scope.resolve_source_root_set_for_test_fixtures
    )
    assert db_source_roots.iter_production_kotlin_files is (
        scope.iter_production_kotlin_files
    )
    assert db_source_roots.resolve_production_source_scope is (
        scope.resolve_production_source_scope
    )


def test_db_guard_seam_projections_match_neutral_behavior(tmp_path):
    _make_tree(
        tmp_path, [APP_FILE, KOTLIN_ROOT + "/com/example/b/Baz.kt"]
    )
    root_set = _root_set(APP_ROOT, KOTLIN_ROOT)

    # Enumeration projection: byte-identical relative-path lists.
    db_files, db_diagnostics = db_source_roots.collect_production_kotlin_files(
        str(tmp_path), root_set
    )
    neutral_files, neutral_diagnostics = scope.collect_production_source_files(
        str(tmp_path), root_set
    )
    assert db_diagnostics == () and neutral_diagnostics == ()
    assert db_files == tuple(
        source_file.repository_relative_path for source_file in neutral_files
    )

    # Resolution projection: byte-identical absolute paths and codes.
    db_resolved, db_code = db_source_roots.resolve_canonical_source_file(
        str(tmp_path), root_set, APP_FILE
    )
    neutral_resolved, neutral_code = scope.resolve_production_kotlin_file(
        str(tmp_path), root_set, APP_FILE
    )
    assert db_code is None and neutral_code is None
    assert db_resolved == neutral_resolved.absolute_path
    assert db_source_roots.resolve_canonical_source_file(
        "unused", root_set, "feature/x/src/main/java/X.kt"
    ) == (None, "DB_SOURCE_ROOT_UNDECLARED")

    # Manifest loading through the DB seam matches the neutral loader.
    payload = _manifest((":app", APP_ROOT))
    db_root_set, db_diagnostics = db_source_roots.load_source_root_manifest(
        _write_manifest(tmp_path, payload)
    )
    assert db_diagnostics == ()
    assert db_root_set.paths == (APP_ROOT,)
    assert (
        db_source_roots.verify_declared_root_topology(str(tmp_path), db_root_set)
        == ()
    )


def test_legacy_gr01_contract_stays_db_scoped():
    # The neutral authority module carries no legacy tuple authority and no
    # legacy seam names: production callers cannot reach them from there.
    for legacy_name in (
        "APPROVED_PRODUCTION_SOURCE_ROOTS",
        "approved_root_error",
        "is_approved_source_path",
        "resolve_source_root_set",
    ):
        assert not hasattr(scope, legacy_name)

    # The DB layer keeps the legacy GR-01 contract working, unchanged.
    assert db_source_roots.APPROVED_PRODUCTION_SOURCE_ROOTS == ("app/src/main/java",)
    assert db_source_roots.is_approved_source_path(APP_FILE) is True
    assert (
        db_source_roots.approved_root_error("other/somewhere/X.kt")
        == POLICY_ERROR_PATH_OUTSIDE_APPROVED_ROOT
    )

    # Neither module ever calls sys.exit (plan §1: callers own exit policy).
    assert "sys.exit(" not in inspect.getsource(scope)
    assert "sys.exit(" not in inspect.getsource(db_source_roots)
