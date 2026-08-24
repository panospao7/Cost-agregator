"""Contract tests for the declared source-root manifest layer
(``scripts/db_guard/source_roots.py``, PR-GR-03 Slice A).

Covers the manifest validation matrix (shape, schema version, module /
source-set / path rules, duplicates, overlaps, canonical ordering), YAML
loading, filesystem topology verification, deterministic Kotlin-file
collection, canonical source-file resolution, declared-path membership,
and the untouched legacy single-root contract.  Filesystem tests use
``tmp_path``; symlink cases skip themselves on platforms without symlink
privileges (e.g. Windows without Developer Mode).
"""

from __future__ import annotations

import dataclasses
import os

import pytest
import yaml

from scripts.db_guard.policy_errors import POLICY_ERROR_PATH_OUTSIDE_APPROVED_ROOT
from scripts.db_guard.source_roots import (
    APPROVED_PRODUCTION_SOURCE_ROOTS,
    DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED,
    DB_SOURCE_ROOT_MANIFEST_INVALID,
    DB_SOURCE_ROOT_SYMLINK_OUTSIDE,
    DB_SOURCE_ROOT_UNDECLARED,
    DB_SOURCE_ROOT_UNREADABLE,
    SOURCE_ROOT_DIAGNOSTIC_CODES,
    SOURCE_ROOT_MANIFEST_SCHEMA_VERSION,
    SourceRoot,
    SourceRootSet,
    approved_root_error,
    collect_production_kotlin_files,
    is_approved_source_path,
    is_declared_production_path,
    load_source_root_manifest,
    resolve_canonical_source_file,
    resolve_source_root_set,
    validate_source_root_manifest,
    verify_declared_root_topology,
)

APP_ROOT = "app/src/main/java"
KOTLIN_ROOT = "core/data/src/main/kotlin"
APP_FILE = APP_ROOT + "/com/example/expensetracker/AppDatabase.kt"


# ── Helpers ───────────────────────────────────────────────────────────────────


def _manifest(*roots):
    """Build a manifest payload from ``(module, path)`` pairs."""
    return {
        "schemaVersion": SOURCE_ROOT_MANIFEST_SCHEMA_VERSION,
        "roots": [
            {"module": module, "sourceSet": "main", "path": path}
            for module, path in roots
        ],
    }


def _write_manifest(tmp_path, payload):
    manifest_path = tmp_path / "source_roots.yaml"
    manifest_path.write_text(yaml.safe_dump(payload), encoding="utf-8")
    return str(manifest_path)


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


# ── Legacy contract (GR-01 consumers must keep working) ──────────────────────


def test_legacy_single_root_contract_unchanged():
    assert APPROVED_PRODUCTION_SOURCE_ROOTS == ("app/src/main/java",)
    assert is_approved_source_path(APP_FILE) is True
    assert (
        approved_root_error("other/somewhere/X.kt")
        == POLICY_ERROR_PATH_OUTSIDE_APPROVED_ROOT
    )


# ── Closed diagnostic-code set ───────────────────────────────────────────────


def test_source_root_diagnostic_codes_are_exact_closed_set():
    assert SOURCE_ROOT_DIAGNOSTIC_CODES == frozenset(
        {
            DB_SOURCE_ROOT_MANIFEST_INVALID,
            DB_SOURCE_ROOT_UNDECLARED,
            DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED,
            DB_SOURCE_ROOT_UNREADABLE,
            DB_SOURCE_ROOT_SYMLINK_OUTSIDE,
        }
    )
    for code in SOURCE_ROOT_DIAGNOSTIC_CODES:
        assert code.startswith("DB_SOURCE_ROOT_")


# ── Manifest validation matrix ───────────────────────────────────────────────


def test_valid_single_root_passes():
    assert validate_source_root_manifest(_manifest((":app", APP_ROOT))) == ()


def test_valid_multiple_module_manifest_passes():
    payload = _manifest((":app", APP_ROOT), (":core:data", KOTLIN_ROOT))
    assert validate_source_root_manifest(payload) == ()


def test_java_and_kotlin_roots_pass_together():
    payload = _manifest((":app", APP_ROOT), (":app", "app/src/main/kotlin"))
    assert validate_source_root_manifest(payload) == ()


def test_missing_schema_version_rejected():
    payload = {
        "roots": [{"module": ":app", "sourceSet": "main", "path": APP_ROOT}]
    }
    diagnostics = validate_source_root_manifest(payload)
    assert _codes(diagnostics) == [DB_SOURCE_ROOT_MANIFEST_INVALID]
    assert diagnostics[0][1] == {"field": "schemaVersion"}


@pytest.mark.parametrize("version", [2, "1", None, True])
def test_wrong_schema_version_rejected(version):
    payload = _manifest((":app", APP_ROOT))
    payload["schemaVersion"] = version
    diagnostics = validate_source_root_manifest(payload)
    assert _codes(diagnostics) == [DB_SOURCE_ROOT_MANIFEST_INVALID]
    assert diagnostics[0][1] == {"field": "schemaVersion"}


def test_unknown_top_level_key_rejected():
    payload = _manifest((":app", APP_ROOT))
    payload["extra"] = 1
    diagnostics = validate_source_root_manifest(payload)
    assert _codes(diagnostics) == [DB_SOURCE_ROOT_MANIFEST_INVALID]
    assert diagnostics[0][1] == {"field": "extra"}


def test_unknown_root_entry_key_rejected():
    payload = _manifest((":app", APP_ROOT))
    payload["roots"][0]["sourceRoot"] = "oops"
    diagnostics = validate_source_root_manifest(payload)
    assert _codes(diagnostics) == [DB_SOURCE_ROOT_MANIFEST_INVALID]
    assert diagnostics[0][1] == {"field": "sourceRoot", "index": 0}


@pytest.mark.parametrize("module", [":", "app", "app/src"])
def test_invalid_module_rejected(module):
    payload = _manifest((module, APP_ROOT))
    diagnostics = validate_source_root_manifest(payload)
    assert _codes(diagnostics) == [DB_SOURCE_ROOT_MANIFEST_INVALID]
    assert diagnostics[0][1] == {"field": "module", "index": 0}


@pytest.mark.parametrize("source_set", ["debug", "release", "test"])
def test_unsupported_source_set_rejected(source_set):
    payload = _manifest((":app", APP_ROOT))
    payload["roots"][0]["sourceSet"] = source_set
    diagnostics = validate_source_root_manifest(payload)
    assert _codes(diagnostics) == [DB_SOURCE_ROOT_MANIFEST_INVALID]
    assert diagnostics[0][1] == {"field": "sourceSet", "index": 0}


@pytest.mark.parametrize("path", ["/abs/src/main/java", "C:/x/src/main/java"])
def test_absolute_root_path_rejected(path):
    diagnostics = validate_source_root_manifest(_manifest((":app", path)))
    assert _codes(diagnostics) == [DB_SOURCE_ROOT_MANIFEST_INVALID]
    assert diagnostics[0][1] == {
        "field": "path",
        "index": 0,
        "reason": "absolute",
    }


def test_backslash_root_path_rejected():
    diagnostics = validate_source_root_manifest(
        _manifest((":app", "app\\src\\main\\java"))
    )
    assert _codes(diagnostics) == [DB_SOURCE_ROOT_MANIFEST_INVALID]
    assert diagnostics[0][1] == {
        "field": "path",
        "index": 0,
        "reason": "backslash",
    }


@pytest.mark.parametrize(
    "path",
    [
        "app/../app/src/main/java",
        "./app/src/main/java",
        "app/src/main/java/../java",
    ],
)
def test_traversal_root_path_rejected(path):
    diagnostics = validate_source_root_manifest(_manifest((":app", path)))
    assert _codes(diagnostics) == [DB_SOURCE_ROOT_MANIFEST_INVALID]
    assert diagnostics[0][1] == {
        "field": "path",
        "index": 0,
        "reason": "bad-segment",
    }


@pytest.mark.parametrize("path", ["*/src/main/java", "app/*/src/main/java"])
def test_wildcard_root_path_rejected(path):
    diagnostics = validate_source_root_manifest(_manifest((":app", path)))
    assert _codes(diagnostics) == [DB_SOURCE_ROOT_MANIFEST_INVALID]
    assert diagnostics[0][1] == {
        "field": "path",
        "index": 0,
        "reason": "wildcard",
    }


def test_root_below_a_source_root_rejected():
    diagnostics = validate_source_root_manifest(
        _manifest((":app", APP_ROOT + "/com"))
    )
    assert _codes(diagnostics) == [DB_SOURCE_ROOT_MANIFEST_INVALID]
    assert diagnostics[0][1] == {
        "field": "path",
        "index": 0,
        "reason": "unsupported-tail",
    }


@pytest.mark.parametrize(
    "path",
    [
        "test/src/main/java",
        "androidTest/src/main/java",
        "debug/src/main/java",
        "release/src/main/java",
        "generated/src/main/java",
        "build/src/main/java",
        "app/build/src/main/java",
    ],
)
def test_forbidden_root_segment_rejected(path):
    diagnostics = validate_source_root_manifest(_manifest((":app", path)))
    assert _codes(diagnostics) == [DB_SOURCE_ROOT_MANIFEST_INVALID]
    assert diagnostics[0][1] == {
        "field": "path",
        "index": 0,
        "reason": "forbidden-segment",
    }


def test_duplicate_root_rejected():
    payload = _manifest((":app", APP_ROOT), (":core:data", APP_ROOT))
    diagnostics = validate_source_root_manifest(payload)
    assert _codes(diagnostics) == [DB_SOURCE_ROOT_MANIFEST_INVALID]
    assert diagnostics[0][1] == {
        "field": "path",
        "index": 1,
        "reason": "duplicate-path",
    }


def test_root_nested_under_sibling_root_rejected():
    # Plan-literal overlap pair: the nested path additionally fails the
    # required ``/src/main/(java|kotlin)`` tail and must still fail closed.
    payload = _manifest((":app", APP_ROOT), (":app", APP_ROOT + "/com"))
    diagnostics = validate_source_root_manifest(payload)
    assert _codes(diagnostics) == [DB_SOURCE_ROOT_MANIFEST_INVALID]
    assert diagnostics[0][1]["field"] == "path"
    assert diagnostics[0][1]["index"] == 1


def test_suffix_valid_overlapping_roots_rejected():
    payload = _manifest(
        (":lib", "lib/src/main/java"),
        (":lib", "lib/src/main/java/src/main/java"),
    )
    diagnostics = validate_source_root_manifest(payload)
    assert _codes(diagnostics) == [DB_SOURCE_ROOT_MANIFEST_INVALID]
    assert diagnostics[0][1] == {
        "field": "path",
        "index": 1,
        "reason": "overlapping-path",
    }


def test_non_canonical_ordering_rejected():
    payload = _manifest(
        (":zeta", "zeta/src/main/java"),
        (":alpha", "alpha/src/main/java"),
    )
    diagnostics = validate_source_root_manifest(payload)
    assert _codes(diagnostics) == [DB_SOURCE_ROOT_MANIFEST_INVALID]
    assert diagnostics[0][1] == {
        "field": "roots",
        "reason": "non-canonical-order",
    }


def test_empty_roots_list_rejected():
    payload = {"schemaVersion": 1, "roots": []}
    assert _codes(validate_source_root_manifest(payload)) == [
        DB_SOURCE_ROOT_MANIFEST_INVALID
    ]


def test_roots_not_a_list_rejected():
    payload = {"schemaVersion": 1, "roots": {"module": ":app"}}
    assert _codes(validate_source_root_manifest(payload)) == [
        DB_SOURCE_ROOT_MANIFEST_INVALID
    ]


# ── Manifest loading ─────────────────────────────────────────────────────────


def test_load_round_trip_builds_source_root_set(tmp_path):
    payload = _manifest((":app", APP_ROOT), (":core:data", KOTLIN_ROOT))
    root_set, diagnostics = load_source_root_manifest(
        _write_manifest(tmp_path, payload)
    )
    assert diagnostics == ()
    assert isinstance(root_set, SourceRootSet)
    assert root_set.paths == (APP_ROOT, KOTLIN_ROOT)
    assert root_set.roots[0] == SourceRoot(
        module=":app", source_set="main", path=APP_ROOT
    )


def test_load_missing_file_is_unreadable(tmp_path):
    root_set, diagnostics = load_source_root_manifest(str(tmp_path / "nope.yaml"))
    assert root_set is None
    assert _codes(diagnostics) == [DB_SOURCE_ROOT_UNREADABLE]


def test_load_malformed_yaml_is_manifest_invalid(tmp_path):
    manifest = tmp_path / "broken.yaml"
    manifest.write_text("schemaVersion: 1\nroots:\n\t- {}\n", encoding="utf-8")
    root_set, diagnostics = load_source_root_manifest(str(manifest))
    assert root_set is None
    assert _codes(diagnostics) == [DB_SOURCE_ROOT_MANIFEST_INVALID]
    assert diagnostics[0][1] == {"reason": "malformed-yaml"}


def test_load_non_mapping_document_is_manifest_invalid(tmp_path):
    manifest = tmp_path / "list.yaml"
    manifest.write_text("- one\n- two\n", encoding="utf-8")
    root_set, diagnostics = load_source_root_manifest(str(manifest))
    assert root_set is None
    assert _codes(diagnostics) == [DB_SOURCE_ROOT_MANIFEST_INVALID]


def test_load_invalid_manifest_surfaces_validation_diagnostics(tmp_path):
    payload = {"schemaVersion": True, "roots": []}
    root_set, diagnostics = load_source_root_manifest(
        _write_manifest(tmp_path, payload)
    )
    assert root_set is None
    assert set(_codes(diagnostics)) == {DB_SOURCE_ROOT_MANIFEST_INVALID}
    assert len(diagnostics) >= 2


# ── Topology verification ────────────────────────────────────────────────────


def test_valid_topology_yields_no_diagnostics(tmp_path):
    _make_tree(tmp_path, [APP_FILE])
    assert verify_declared_root_topology(str(tmp_path), _root_set(APP_ROOT)) == ()


def test_missing_root_directory_is_unreadable(tmp_path):
    diagnostics = verify_declared_root_topology(str(tmp_path), _root_set(APP_ROOT))
    assert _codes(diagnostics) == [DB_SOURCE_ROOT_UNREADABLE]
    assert diagnostics[0][1] == {"target": APP_ROOT}


def test_symlinked_root_pointing_outside_rejected(tmp_path):
    outside = tmp_path / "outside"
    (outside / "src" / "main" / "java").mkdir(parents=True)
    repo = tmp_path / "repo"
    link = repo.joinpath(*APP_ROOT.split("/"))
    link.parent.mkdir(parents=True)
    if not _make_dir_symlink(str(link), str(outside / "src" / "main" / "java")):
        pytest.skip("symlink creation not permitted on this platform")
    diagnostics = verify_declared_root_topology(str(repo), _root_set(APP_ROOT))
    assert _codes(diagnostics) == [DB_SOURCE_ROOT_SYMLINK_OUTSIDE]
    assert diagnostics[0][1] == {"target": APP_ROOT}


def test_symlinked_root_pointing_inside_repo_still_rejected(tmp_path):
    repo = tmp_path / "repo"
    real = repo / "real"
    (real / "src" / "main" / "java").mkdir(parents=True)
    if not _make_dir_symlink(str(repo / "app"), str(real)):
        pytest.skip("symlink creation not permitted on this platform")
    diagnostics = verify_declared_root_topology(str(repo), _root_set(APP_ROOT))
    assert _codes(diagnostics) == [DB_SOURCE_ROOT_SYMLINK_OUTSIDE]


def test_root_resolving_outside_via_symlinked_ancestor_rejected(tmp_path):
    outside = tmp_path / "outside"
    (outside / "src" / "main" / "java").mkdir(parents=True)
    repo = tmp_path / "repo"
    repo.mkdir()
    if not _make_dir_symlink(str(repo / "link"), str(outside)):
        pytest.skip("symlink creation not permitted on this platform")
    diagnostics = verify_declared_root_topology(
        str(repo), _root_set("link/src/main/java")
    )
    assert _codes(diagnostics) == [DB_SOURCE_ROOT_SYMLINK_OUTSIDE]


# ── Production Kotlin collection ─────────────────────────────────────────────


def test_collect_is_deterministic_across_java_and_kotlin_roots(tmp_path):
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
    first, diagnostics = collect_production_kotlin_files(str(tmp_path), root_set)
    second, _ = collect_production_kotlin_files(str(tmp_path), root_set)
    assert diagnostics == ()
    assert first == second
    assert first == (
        APP_ROOT + "/com/example/a/Alpha.kt",
        APP_ROOT + "/com/example/z/Zeta.kt",
        KOTLIN_ROOT + "/com/example/a/Bar.kt",
        KOTLIN_ROOT + "/com/example/b/Baz.kt",
    )


def test_collect_empty_tree_returns_no_files_and_no_diagnostics(tmp_path):
    _make_tree(tmp_path, [APP_ROOT + "/com/example/Notes.md"])
    collected, diagnostics = collect_production_kotlin_files(
        str(tmp_path), _root_set(APP_ROOT)
    )
    assert collected == ()
    assert diagnostics == ()


def test_collect_unreadable_file_fails_closed(tmp_path, monkeypatch):
    _make_tree(tmp_path, [APP_FILE])
    real_access = os.access
    target_abs = os.path.join(str(tmp_path), *APP_FILE.split("/"))

    def fake_access(path, mode):
        if os.path.abspath(str(path)) == os.path.abspath(target_abs):
            return False
        return real_access(path, mode)

    monkeypatch.setattr(os, "access", fake_access)
    collected, diagnostics = collect_production_kotlin_files(
        str(tmp_path), _root_set(APP_ROOT)
    )
    assert collected == ()
    assert _codes(diagnostics) == [DB_SOURCE_ROOT_UNREADABLE]
    assert diagnostics[0][1] == {"target": APP_ROOT}


def test_collect_unlistable_directory_fails_closed(tmp_path, monkeypatch):
    (tmp_path / "app").mkdir()

    def fake_listdir(path):
        raise PermissionError(13, "denied")

    monkeypatch.setattr(os, "listdir", fake_listdir)
    collected, diagnostics = collect_production_kotlin_files(
        str(tmp_path), _root_set(APP_ROOT)
    )
    assert collected == ()
    assert _codes(diagnostics) == [DB_SOURCE_ROOT_UNREADABLE]
    assert diagnostics[0][1] == {"target": APP_ROOT}


# ── Canonical source-file resolution ─────────────────────────────────────────


def test_resolve_canonical_source_file_happy_path(tmp_path):
    _make_tree(tmp_path, [APP_FILE])
    resolved, code = resolve_canonical_source_file(
        str(tmp_path), _root_set(APP_ROOT), APP_FILE
    )
    assert code is None
    expected = os.path.join(str(tmp_path), *APP_FILE.split("/"))
    assert os.path.normcase(resolved) == os.path.normcase(expected)


def test_resolve_rejects_traversal():
    resolved, code = resolve_canonical_source_file(
        "unused", _root_set(APP_ROOT), APP_ROOT + "/../hidden/X.kt"
    )
    assert resolved is None
    assert code == DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED


def test_resolve_rejects_undeclared_path():
    resolved, code = resolve_canonical_source_file(
        "unused", _root_set(APP_ROOT), "feature/x/src/main/java/X.kt"
    )
    assert resolved is None
    assert code == DB_SOURCE_ROOT_UNDECLARED


def test_resolve_rejects_non_kotlin_layout():
    resolved, code = resolve_canonical_source_file(
        "unused", _root_set(APP_ROOT), APP_ROOT + "/com/example/Notes.md"
    )
    assert resolved is None
    assert code == DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED


def test_resolve_missing_file_is_unreadable(tmp_path):
    resolved, code = resolve_canonical_source_file(
        str(tmp_path), _root_set(APP_ROOT), APP_FILE
    )
    assert resolved is None
    assert code == DB_SOURCE_ROOT_UNREADABLE


def test_resolve_symlink_escape_rejected(tmp_path):
    outside = tmp_path / "outside"
    escaped_file = outside / "src" / "main" / "java" / "com" / "example" / "X.kt"
    escaped_file.parent.mkdir(parents=True)
    escaped_file.write_text("// kt\n", encoding="utf-8")
    repo = tmp_path / "repo"
    repo.mkdir()
    if not _make_dir_symlink(str(repo / "app"), str(outside)):
        pytest.skip("symlink creation not permitted on this platform")
    resolved, code = resolve_canonical_source_file(
        str(repo),
        _root_set(APP_ROOT),
        APP_ROOT + "/com/example/X.kt",
    )
    assert resolved is None
    assert code == DB_SOURCE_ROOT_SYMLINK_OUTSIDE


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


def test_source_root_set_paths_preserve_manifest_order():
    root_set = SourceRootSet(
        (
            SourceRoot(module=":b", source_set="main", path="b/src/main/java"),
            SourceRoot(module=":a", source_set="main", path="a/src/main/java"),
        )
    )
    assert root_set.paths == ("b/src/main/java", "a/src/main/java")


def test_source_root_models_are_frozen():
    root = SourceRoot(module=":app", source_set="main", path=APP_ROOT)
    with pytest.raises(dataclasses.FrozenInstanceError):
        root.module = ":other"
    with pytest.raises(dataclasses.FrozenInstanceError):
        SourceRootSet((root,)).paths = ()


# ── Root-set resolution precedence (PR-GR-03 Slice C1) ───────────────────────


def _write_repo_manifest(repo_root, payload):
    """Write the checked-in-style manifest at its canonical location."""
    manifest_path = (
        repo_root / "config" / "guards" / "production_source_roots.yml"
    )
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text(yaml.safe_dump(payload), encoding="utf-8")
    return manifest_path


def test_resolve_explicit_root_set_wins_over_everything(tmp_path):
    # A valid manifest AND the conventional root both exist, but an explicit
    # SourceRootSet is used exactly as-is (same object identity).
    _make_tree(tmp_path, [APP_FILE])
    _write_repo_manifest(tmp_path, _manifest((":app", APP_ROOT)))
    explicit = _root_set(KOTLIN_ROOT)
    resolved, diagnostics = resolve_source_root_set(str(tmp_path), explicit)
    assert diagnostics == ()
    assert resolved is explicit


def test_resolve_explicit_non_root_set_fails_closed(tmp_path):
    resolved, diagnostics = resolve_source_root_set(
        str(tmp_path), ("app/src/main/java",)
    )
    assert resolved is None
    assert _codes(diagnostics) == [DB_SOURCE_ROOT_LAYOUT_UNSUPPORTED]


def test_resolve_manifest_present_but_malformed_fails_closed_without_fallback(tmp_path):
    # The conventional root exists, so an (illegal) implicit fallback would
    # succeed; the malformed manifest must fail closed instead.
    _make_tree(tmp_path, [APP_FILE])
    manifest_path = (
        tmp_path / "config" / "guards" / "production_source_roots.yml"
    )
    manifest_path.parent.mkdir(parents=True, exist_ok=True)
    manifest_path.write_text("schemaVersion: 1\nroots:\n\t- {}\n", encoding="utf-8")
    resolved, diagnostics = resolve_source_root_set(str(tmp_path))
    assert resolved is None
    assert _codes(diagnostics) == [DB_SOURCE_ROOT_MANIFEST_INVALID]


def test_resolve_valid_manifest_is_verified_and_returned(tmp_path):
    _make_tree(tmp_path, [APP_FILE])
    _write_repo_manifest(tmp_path, _manifest((":app", APP_ROOT)))
    resolved, diagnostics = resolve_source_root_set(str(tmp_path))
    assert diagnostics == ()
    assert resolved is not None
    assert resolved.paths == (APP_ROOT,)


def test_resolve_manifest_declaring_missing_root_fails_closed(tmp_path):
    # Shape-valid manifest whose declared root does not exist on disk:
    # topology verification fails closed, no implicit fallback.
    _write_repo_manifest(tmp_path, _manifest((":app", APP_ROOT)))
    resolved, diagnostics = resolve_source_root_set(str(tmp_path))
    assert resolved is None
    assert _codes(diagnostics) == [DB_SOURCE_ROOT_UNREADABLE]
    assert diagnostics[0][1] == {"target": APP_ROOT}


def test_resolve_manifest_absent_falls_back_to_app_conventional_root_absolute(tmp_path):
    # GR-03: when no manifest exists, the implicit conventional fallback
    # carries the resolved ``app/src/main/java`` directory as an ABSOLUTE
    # native-separator path (not the legacy repository-relative POSIX form),
    # so callers can walk it and anchor emitted relative paths.
    _make_tree(tmp_path, [APP_FILE])
    resolved, diagnostics = resolve_source_root_set(str(tmp_path))
    assert diagnostics == ()
    assert resolved is not None
    assert len(resolved.paths) == 1
    assert os.path.normcase(resolved.paths[0]) == os.path.normcase(
        os.path.join(str(tmp_path), *APP_ROOT.split("/"))
    )


def test_resolve_bare_src_main_java_dir_input(tmp_path):
    java_dir = tmp_path.joinpath(*APP_ROOT.split("/"))
    java_dir.mkdir(parents=True)
    resolved, diagnostics = resolve_source_root_set(str(java_dir))
    assert diagnostics == ()
    assert resolved is not None
    assert len(resolved.roots) == 1
    assert os.path.normcase(resolved.roots[0].path) == os.path.normcase(
        str(java_dir)
    )


def test_resolve_bare_src_main_kotlin_dir_input(tmp_path):
    kotlin_dir = tmp_path.joinpath(*KOTLIN_ROOT.split("/"))
    kotlin_dir.mkdir(parents=True)
    resolved, diagnostics = resolve_source_root_set(str(kotlin_dir))
    assert diagnostics == ()
    assert resolved is not None
    assert len(resolved.roots) == 1
    assert os.path.normcase(resolved.roots[0].path) == os.path.normcase(
        str(kotlin_dir)
    )


def test_resolve_legacy_intermediate_dirs_normalize_to_java_root(tmp_path):
    java_dir = tmp_path.joinpath(*APP_ROOT.split("/"))
    java_dir.mkdir(parents=True)
    for tail in ("app/src", "app/src/main"):
        intermediate = tmp_path.joinpath(*tail.split("/"))
        resolved, diagnostics = resolve_source_root_set(str(intermediate))
        assert diagnostics == (), tail
        assert resolved is not None, tail
        assert os.path.normcase(resolved.roots[0].path) == os.path.normcase(
            str(java_dir)
        ), tail


def test_resolve_nothing_conventional_is_undeclared(tmp_path):
    resolved, diagnostics = resolve_source_root_set(str(tmp_path))
    assert resolved is None
    assert diagnostics == (
        (DB_SOURCE_ROOT_UNDECLARED, {"reason": "no-conventional-root"}),
    )
