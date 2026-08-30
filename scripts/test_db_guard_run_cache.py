"""PR-GR-10c per-run cache tests: byte-identical outputs and re-read after write.

The cache seam (``scripts/run_cache.py`` + the memoized parser entry points)
must never change a diagnostic, finding, or report byte:

* every wrapped operation is a pure function of its inputs, so a warm-cache
  run must serialize EXACTLY like a cold-cache run on the same fixtures;
* a file changed within a run must be picked up on the next access (the
  file cache is stamp-validated; every text-keyed cache sees new text as a
  new key) — re-read after write;
* callable discovery keys on the project type index's CONTENT digest, so an
  equal-content index shares entries while a different-content index can
  never return another tree's resolutions.
"""

from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest

_SCRIPTS_DIR = os.path.dirname(os.path.abspath(__file__))
_REPO_ROOT = os.path.dirname(_SCRIPTS_DIR)
if _REPO_ROOT not in sys.path:
    sys.path.insert(0, _REPO_ROOT)

from scripts.db_guard.declaration_scanner import build_project_type_index
from scripts.db_guard.policy_model import BarrierMode, CallableKind, PolicyEntry
from scripts.db_guard.policy_v2_evidence import verify_v2_policy_source_evidence
from scripts.db_guard.scanner import scan_db_access
from scripts.kotlin_callable_parser import (
    find_callable_declarations,
    find_owner_declarations,
    mask_kotlin_source,
    project_nested_type_declarations,
    project_type_declarations,
)
from scripts.run_cache import cache_stats, clear_run_caches, file_text


# ---------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------

REPO_KT = "app/src/main/java/com/example/Repo.kt"

REPO_SOURCE = """\
package com.example

import com.example.data.GroupDao

class Repo(private val groupDao: GroupDao) {
    fun insertGroup(group: Group) {
        writeBarrier.checkWritesAllowed()
        groupDao.insert(group)
    }

    fun deleteGroup(group: Group) {
        writeBarrier.checkWritesAllowed()
        groupDao.delete(group)
    }
}

data class Group(val id: Int)
"""

# Same file with the owner renamed (different length, so the file cache's
# (mtime_ns, size) stamp always changes): the policy entry no longer matches.
OWNER_RENAMED_SOURCE = REPO_SOURCE.replace("class Repo", "class RenamedRepo")

EMPTY_RAW_QUERY_POLICY = {"version": 1, "methods": []}

SCANNER_DAO = """\
@androidx.room.Dao
interface ExpenseDao {
    @androidx.room.Insert
    fun insert(item: Item)
}
"""

SCANNER_SOURCE = """\
package example

data class Item(val id: Int)

""" + SCANNER_DAO + """

class Repository(private val expenseDao: ExpenseDao) {
    fun save(item: Item) {
        expenseDao.insert(item)
    }
}
"""


@pytest.fixture(autouse=True)
def _isolated_run_caches():
    """Every test starts and ends with empty caches (bounded, deterministic)."""
    clear_run_caches()
    yield
    clear_run_caches()


def _write_verifier_repo(tmp_path: Path, text: str) -> str:
    path = (
        tmp_path / "app" / "src" / "main" / "java"
        / "com" / "example" / "Repo.kt"
    )
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")
    return str(tmp_path)


def _verifier_entry(**overrides) -> PolicyEntry:
    fields = dict(
        path=REPO_KT,
        owner_fqcn="com.example.Repo",
        kind=CallableKind.FUNCTION,
        method="insertGroup",
        receiver=None,
        parameter_types=("com.example.Group",),
        dao_accessor="groupDao",
        dao_fqcn="com.example.data.GroupDao",
        operation="insert",
        barrier_mode=BarrierMode.DIRECT,
        reason="PR-GR-10c cache test",
        owner="db-guard-tests",
        linked_issue="GR00-CACHE-T",
    )
    fields.update(overrides)
    return PolicyEntry(**fields)


def _write_scanner_repo(tmp_path: Path, repository_source: str) -> Path:
    root = tmp_path / "app" / "src" / "main" / "java"
    root.mkdir(parents=True, exist_ok=True)
    (root / "Fixture.kt").write_text(
        SCANNER_SOURCE + repository_source, encoding="utf-8"
    )
    return root


# ---------------------------------------------------------------------------
# 1. Pure parser analyses: warm cache is byte-identical to cold
# ---------------------------------------------------------------------------


def test_mask_cache_returns_identical_bytes_cold_and_warm():
    text = REPO_SOURCE
    cold = mask_kotlin_source(text)
    warm = mask_kotlin_source(text)
    assert warm == cold
    # Masking is idempotent: the masked text masks to itself, through the
    # cache exactly as without it.
    assert mask_kotlin_source(cold) == cold
    clear_run_caches()
    assert mask_kotlin_source(text) == cold


def test_owner_and_callable_caches_return_identical_results():
    cold_owners = find_owner_declarations(REPO_SOURCE)
    warm_owners = find_owner_declarations(REPO_SOURCE)
    assert warm_owners == cold_owners
    owner = next(o for o in cold_owners if o.owner == "com.example.Repo")
    cold_callables = find_callable_declarations(REPO_SOURCE, owner)
    warm_callables = find_callable_declarations(REPO_SOURCE, owner)
    assert warm_callables == cold_callables
    clear_run_caches()
    assert find_owner_declarations(REPO_SOURCE) == cold_owners
    assert (
        find_callable_declarations(REPO_SOURCE, owner) == cold_callables
    )


def test_project_type_declaration_helpers_identical_cold_and_warm():
    cold = project_type_declarations(REPO_SOURCE)
    warm = project_type_declarations(REPO_SOURCE)
    assert warm == cold
    cold_nested = project_nested_type_declarations(REPO_SOURCE)
    warm_nested = project_nested_type_declarations(REPO_SOURCE)
    assert warm_nested == cold_nested
    clear_run_caches()
    assert project_type_declarations(REPO_SOURCE) == cold
    assert project_nested_type_declarations(REPO_SOURCE) == cold_nested


def test_failed_mask_is_never_cached():
    with pytest.raises(Exception):
        mask_kotlin_source('class Broken { val x = "unterminated')
    # The failure left no cache entry: the same input fails again through
    # the same controlled path (never a stale success).
    with pytest.raises(Exception):
        mask_kotlin_source('class Broken { val x = "unterminated')
    assert cache_stats().get("mask", 0) == 0


# ---------------------------------------------------------------------------
# 2. Evidence verifier: byte-identical reports with and without cache
# ---------------------------------------------------------------------------


def test_verifier_report_identical_cold_warm_and_cleared(tmp_path):
    repo_root = _write_verifier_repo(tmp_path, REPO_SOURCE)
    # Two GROUPS over the SAME file and owner (the shape that made the
    # verifier re-read/re-mask/re-parse one file per group): the warm and
    # cleared runs must serialize byte-identically to the cold run.
    entries = [
        _verifier_entry(),
        _verifier_entry(method="deleteGroup", operation="delete"),
    ]

    cold = verify_v2_policy_source_evidence(entries, repo_root)
    assert cold.trusted is True
    assert len(cold.groups) == 2
    warm = verify_v2_policy_source_evidence(entries, repo_root).to_dict()
    assert warm == cold.to_dict()
    clear_run_caches()
    cleared = verify_v2_policy_source_evidence(entries, repo_root).to_dict()
    assert cleared == cold.to_dict()


def test_verifier_rereads_file_after_write_within_run(tmp_path):
    repo_root = _write_verifier_repo(tmp_path, REPO_SOURCE)
    entries = [_verifier_entry()]

    trusted = verify_v2_policy_source_evidence(entries, repo_root)
    assert trusted.trusted is True
    assert trusted.diagnostics == ()

    # Rewrite the SAME path with different content (owner gone): the next
    # verification must observe the NEW bytes, not the cached ones.
    _write_verifier_repo(tmp_path, OWNER_RENAMED_SOURCE)
    stale_check = verify_v2_policy_source_evidence(entries, repo_root)
    assert stale_check.trusted is False
    assert [d.code for d in stale_check.diagnostics] == [
        "DB_V2_POLICY_OWNER_MISSING"
    ]


# ---------------------------------------------------------------------------
# 3. D4 scanner: byte-identical reports with and without cache
# ---------------------------------------------------------------------------


def test_scanner_report_identical_cold_warm_and_cleared(tmp_path):
    root = _write_scanner_repo(tmp_path, "")

    cold = scan_db_access(root, raw_query_policy=EMPTY_RAW_QUERY_POLICY)
    warm = scan_db_access(root, raw_query_policy=EMPTY_RAW_QUERY_POLICY)
    assert warm.to_dict() == cold.to_dict()
    clear_run_caches()
    cleared = scan_db_access(root, raw_query_policy=EMPTY_RAW_QUERY_POLICY)
    assert cleared.to_dict() == cold.to_dict()


def test_scanner_rereads_file_after_write_within_run(tmp_path):
    root = _write_scanner_repo(tmp_path, "")

    clean = scan_db_access(root, raw_query_policy=EMPTY_RAW_QUERY_POLICY)
    # No ownership policy is passed, so the discovered save() mutation is
    # the honest DB_UNAUTHORIZED_MUTATION finding of the clean fixture.
    clean_findings = clean.to_dict()["findings"]
    assert [f["rule"] for f in clean_findings] == ["DB_UNAUTHORIZED_MUTATION"]
    assert clean_findings[0]["symbol"]["owner"] == "example.Repository"

    # Rewrite the SAME path so the mutation disappears: the next scan must
    # observe the NEW bytes (no stale finding from cached text).
    root_fixture = root / "Fixture.kt"
    current = root_fixture.read_text(encoding="utf-8")
    root_fixture.write_text(
        current.replace("expenseDao.insert(item)", "return"),
        encoding="utf-8",
    )
    updated = scan_db_access(root, raw_query_policy=EMPTY_RAW_QUERY_POLICY)
    assert updated.to_dict()["findings"] == []


def test_scanner_picks_up_new_mutation_after_write_within_run(tmp_path):
    root = _write_scanner_repo(tmp_path, "")

    clean = scan_db_access(root, raw_query_policy=EMPTY_RAW_QUERY_POLICY)
    assert [f["rule"] for f in clean.to_dict()["findings"]] == [
        "DB_UNAUTHORIZED_MUTATION"
    ]

    # Add a SECOND unauthorized mutation site (different length): the next
    # scan must report it — the cache cannot have pinned the old tree.
    root_fixture = root / "Fixture.kt"
    current = root_fixture.read_text(encoding="utf-8")
    root_fixture.write_text(
        current + """
class SecondRepository(private val expenseDao: ExpenseDao) {
    fun saveMore(item: Item) {
        expenseDao.insert(item)
    }
}
""",
        encoding="utf-8",
    )
    updated = scan_db_access(root, raw_query_policy=EMPTY_RAW_QUERY_POLICY)
    payload = updated.to_dict()
    assert [f["rule"] for f in payload["findings"]] == [
        "DB_UNAUTHORIZED_MUTATION"
    ]
    assert {f["symbol"]["owner"] for f in payload["findings"]} == {
        "example.Repository",
        "example.SecondRepository",
    }


# ---------------------------------------------------------------------------
# 4. Project type index: identical cold/warm; content-digest keying
# ---------------------------------------------------------------------------


def test_project_type_index_identical_cold_and_warm(tmp_path):
    root = tmp_path / "app" / "src" / "main" / "java"
    root.mkdir(parents=True)
    (root / "A.kt").write_text(
        "package com.example\nclass Widget(val id: Int)\n", encoding="utf-8"
    )
    (root / "B.kt").write_text(
        "package com.example\nclass Gadget\n", encoding="utf-8"
    )
    pairs = ((root, root),)

    cold = build_project_type_index(pairs)
    warm = build_project_type_index(pairs)
    assert warm.by_simple_name == cold.by_simple_name
    assert warm.qualified == cold.qualified
    clear_run_caches()
    cleared = build_project_type_index(pairs)
    assert cleared.by_simple_name == cold.by_simple_name
    assert cleared.qualified == cold.qualified


def test_callable_cache_never_shares_resolutions_across_indexes(tmp_path):
    """Equal-content indexes share entries; different-content indexes cannot.

    The same Repo.kt text resolves its ``Widget`` parameter through a
    project index that declares Widget (RESOLVED_EXACTLY with the
    package-qualified FQCN) and stays TYPE_UNRESOLVED with the bare simple
    name under an index that does not.  A warm cache from the first tree
    must never leak its resolution into the second.
    """
    repo_text = (
        "package com.example\n"
        "class Repo {\n"
        "    fun store(value: Widget) {\n"
        "        val kept = value\n"
        "    }\n"
        "}\n"
    )
    tree_with_widget = tmp_path / "with-widget" / "app" / "src" / "main" / "java"
    tree_with_widget.mkdir(parents=True)
    (tree_with_widget / "Widget.kt").write_text(
        "package com.example\nclass Widget(val id: Int)\n", encoding="utf-8"
    )
    tree_without_widget = (
        tmp_path / "without-widget" / "app" / "src" / "main" / "java"
    )
    tree_without_widget.mkdir(parents=True)

    index_with = build_project_type_index((tree_with_widget, tree_with_widget))
    index_without = build_project_type_index(
        (tree_without_widget, tree_without_widget)
    )

    resolved = find_callable_declarations(
        repo_text, "Repo", tolerate_unresolved_types=True,
        project_types=index_with,
    )
    # Warm the cache with the resolved tree's entry first.
    assert find_callable_declarations(
        repo_text, "Repo", tolerate_unresolved_types=True,
        project_types=index_with,
    ) == resolved
    assert [d.status for d in resolved] == ["RESOLVED_EXACTLY"]
    assert [tuple(d.signature.parameter_types) for d in resolved] == [
        ("com.example.Widget",)
    ]

    unresolved = find_callable_declarations(
        repo_text, "Repo", tolerate_unresolved_types=True,
        project_types=index_without,
    )
    assert [d.status for d in unresolved] == ["TYPE_UNRESOLVED"]
    assert [tuple(d.signature.parameter_types) for d in unresolved] == [
        ("Widget",)
    ]


# ---------------------------------------------------------------------------
# 5. File cache: re-read after write; bounded diagnostics
# ---------------------------------------------------------------------------


def test_file_text_rereads_after_write_within_run(tmp_path):
    target = tmp_path / "Fixture.kt"
    target.write_text("value one", encoding="utf-8")
    assert file_text(str(target)) == "value one"
    # Cached hit for unchanged content.
    assert file_text(str(target)) == "value one"
    # A write with a DIFFERENT size must be observed on the next read.
    target.write_text("value two — longer", encoding="utf-8")
    assert file_text(str(target)) == "value two — longer"


def test_file_text_matches_direct_read_bytes(tmp_path):
    target = tmp_path / "Fixture.kt"
    target.write_text(REPO_SOURCE, encoding="utf-8")
    assert file_text(str(target)) == target.read_text(encoding="utf-8")


def test_file_text_propagates_missing_file_oserror(tmp_path):
    with pytest.raises(OSError):
        file_text(str(tmp_path / "does-not-exist.kt"))
    # The failure is not cached: the same missing file raises again.
    with pytest.raises(OSError):
        file_text(str(tmp_path / "does-not-exist.kt"))


def test_clear_run_caches_empties_every_namespace(tmp_path):
    target = tmp_path / "Fixture.kt"
    target.write_text(REPO_SOURCE, encoding="utf-8")
    file_text(str(target))
    mask_kotlin_source(REPO_SOURCE)
    find_owner_declarations(REPO_SOURCE)
    find_callable_declarations(REPO_SOURCE, "com.example.Repo")
    assert sum(cache_stats().values()) > 0
    clear_run_caches()
    assert cache_stats() == {}
