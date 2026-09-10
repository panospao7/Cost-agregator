#!/usr/bin/env python3
"""
test_lifecycle_scanner_subsumption.py

PR-GR-10A Slice 3 — SUBSUMED_AND_RETIRED regression proof for the retired
Gradle KTS inline lifecycle scanners (``checkLifecycleBypasses`` and
``checkLifecycleBypass`` in app/build.gradle.kts).

The canonical ``db_access`` D4 guard (scripts/db_guard/scanner.py +
config/guards/db_ownership_policy.yml + the db_access_v2 ratchet baseline)
must detect every positive fixture the retired inline scanners caught.
This suite pins that proof on temporary fixture trees:

  1. RULE MAPPING — every textual rule of the retired scanners targets an
     ExpenseDao mutation method (insert/update/delete plus the fourteen
     ``updateXxx`` column mutators), and every one of those methods is a
     discovered Room mutator identity in the D4 inventory;

  2. POSITIVE FIXTURES — a fixture tree containing one unauthorized call
     site per retired rule produces one ``DB_UNAUTHORIZED_MUTATION`` finding
     per site, covering the exact retired rule surface (the retired
     scanners' file-name allowlists have no canonical counterpart: the
     ownership policy is the single authorization authority);

  3. FALSE-POSITIVE CLASS — the retired scanners' documented false-positive
     class (text matches inside comments/KDoc, e.g. ``[ExpenseDao.insert]``
     references) produces NO D4 finding, because D4 masks comments before
     discovery — retirement loses no real enforcement;

  4. STRICT SUPERIORITY — D4 is receiver-TYPE based while the retired
     scanners were receiver-NAME based: a mutation call through a receiver
     NOT named ``expenseDao`` is still discovered, so the canonical guard is
     strictly broader than the retired textual rules.

Run:
    python -m pytest scripts/test_lifecycle_scanner_subsumption.py -v
"""

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from scripts.db_guard.scanner import scan_db_access  # noqa: E402


# The retired ``checkLifecycleBypasses`` rule set: 14 explicit
# ``expenseDao.updateXxx(`` textual patterns (transcribed verbatim from the
# KTS task source).
RETIRED_UPDATE_RULES = (
    "updateCategory",
    "updateCategoryNullable",
    "updateMerchantAndKey",
    "updateTransactionType",
    "updateTransferDirection",
    "updateTransferAccountName",
    "updateIsNotMine",
    "updateOwnerName",
    "updateIsSharedExpense",
    "updateSharedWithName",
    "updateMySharePercentage",
    "updateMyShareAmount",
    "updateLocation",
    "clearLocation",
)

# The retired ``checkLifecycleBypass`` rule set: the three entity-mutator
# textual patterns ``expenseDao\.insert|update|delete``.
RETIRED_ENTITY_RULES = ("insert", "update", "delete")

# Every retired textual rule targets one of these ExpenseDao methods.
RETIRED_RULE_SURFACE = RETIRED_ENTITY_RULES + RETIRED_UPDATE_RULES

_EMPTY_RAW_QUERY_POLICY = {"version": 1, "methods": []}


def _source_root(tmp_path: Path) -> Path:
    root = tmp_path / "app" / "src" / "main" / "java"
    root.mkdir(parents=True, exist_ok=True)
    return root


def _dao_fixture() -> str:
    """A @Dao declaring exactly the retired scanners' method surface."""
    lines = [
        "package com.example.probe",
        "",
        "@androidx.room.Dao",
        "interface ProbeExpenseDao {",
        "    @androidx.room.Insert",
        "    fun insert(entity: Int)",
        "",
        "    @androidx.room.Update",
        "    fun update(entity: Int)",
        "",
        "    @androidx.room.Delete",
        "    fun delete(entity: Int)",
        "",
    ]
    for method in RETIRED_UPDATE_RULES:
        lines += [
            "    @androidx.room.Update",
            f"    fun {method}(id: Long, value: Long)",
            "",
        ]
    lines.append("}")
    return "\n".join(lines) + "\n"


def _caller_fixture() -> str:
    """One unauthorized call site per retired textual rule."""
    lines = [
        "package com.example.probe",
        "",
        "class RetiredScannerProbe(private val expenseDao: ProbeExpenseDao) {",
        "    fun callInsert(entity: Int) { expenseDao.insert(entity) }",
        "    fun callUpdate(entity: Int) { expenseDao.update(entity) }",
        "    fun callDelete(entity: Int) { expenseDao.delete(entity) }",
        "",
    ]
    for method in RETIRED_UPDATE_RULES:
        lines.append(
            f"    fun call{method.capitalize()}(id: Long, value: Long) {{ "
            f"expenseDao.{method}(id, value) }}"
        )
    lines.append("}")
    return "\n".join(lines) + "\n"


def _scan(tmp_path: Path):
    report = scan_db_access(
        _source_root(tmp_path), raw_query_policy=_EMPTY_RAW_QUERY_POLICY
    )
    return report.to_dict()


# ── 1. Rule mapping: every retired rule is a discovered mutator identity ────


def test_every_retired_rule_is_a_discovered_room_mutator(tmp_path):
    """The D4 Room inventory discovers every method the retired scanners
    matched textually — the rule surfaces are the same surface.

    Adjudicated (R16-3b): D4 mutator identities are FULL callable
    signatures (``<path>::<Fqcn>#<name>(<params>)``), so the proof compares
    the callable-name projection of the identity set against the retired
    bare-name rule surface.  The previous bare-name-vs-signature comparison
    reported every retired rule as "missing" even though D4 discovers all
    of them (each fixture method carries a Room @Insert/@Update/@Delete
    annotation, which is the discovery contract) — the SUBSUMED disposition
    stands; the fixture mapping was not incomplete.
    """
    from scripts.db_guard.room_inventory import build_room_inventory

    root = _source_root(tmp_path)
    (root / "ProbeExpenseDao.kt").write_text(_dao_fixture(), encoding="utf-8")

    inventory = build_room_inventory(root, _EMPTY_RAW_QUERY_POLICY)
    assert inventory.diagnostics == (), inventory.diagnostics
    mutator_names = {
        item.method.split("#", 1)[1].split("(", 1)[0]
        for item in inventory.mutators
    }
    assert set(RETIRED_RULE_SURFACE) <= mutator_names, (
        "Retired textual rules without a discovered D4 mutator identity: "
        f"{set(RETIRED_RULE_SURFACE) - mutator_names}"
    )


# ── 2. Positive fixtures: one finding per retired rule ──────────────────────


def test_every_retired_positive_fixture_is_a_d4_finding(tmp_path):
    """One unauthorized call site per retired textual rule -> one
    DB_UNAUTHORIZED_MUTATION finding per site, covering the exact surface."""
    root = _source_root(tmp_path)
    (root / "ProbeExpenseDao.kt").write_text(_dao_fixture(), encoding="utf-8")
    (root / "RetiredScannerProbe.kt").write_text(_caller_fixture(), encoding="utf-8")

    payload = _scan(tmp_path)
    assert payload["diagnostics"] == [], payload["diagnostics"]

    mutations = [
        finding for finding in payload["findings"]
        if finding["rule"] == "DB_UNAUTHORIZED_MUTATION"
    ]
    operations = {finding["identity"]["operation"] for finding in mutations}
    assert operations == set(RETIRED_RULE_SURFACE), (
        "D4 must discover every retired textual rule surface; missing: "
        f"{set(RETIRED_RULE_SURFACE) - operations}"
    )
    assert len(mutations) == len(RETIRED_RULE_SURFACE)
    for finding in mutations:
        assert finding["identity"]["dao"] == "com.example.probe.ProbeExpenseDao"
        assert finding["identity"]["accessor"] == "expenseDao"
        assert finding["identity"]["call_form"] == "receiver"


def test_retired_finding_paths_are_repo_relative_and_deterministic(tmp_path):
    root = _source_root(tmp_path)
    (root / "ProbeExpenseDao.kt").write_text(_dao_fixture(), encoding="utf-8")
    (root / "RetiredScannerProbe.kt").write_text(_caller_fixture(), encoding="utf-8")

    payload = _scan(tmp_path)
    paths = {finding["path"] for finding in payload["findings"]}
    assert paths == {"app/src/main/java/RetiredScannerProbe.kt"}
    again = _scan(tmp_path)
    assert again == payload


# ── 3. False-positive class: comment/KDoc text matches are not findings ─────


def test_comment_and_kdoc_text_matches_produce_no_finding(tmp_path):
    """The retired scanners' documented false-positive class (the
    ``[ExpenseDao.insertAtomic]`` KDoc reference in ExpenseDao.kt) must not
    become a D4 finding: comments are masked before discovery."""
    root = _source_root(tmp_path)
    (root / "ProbeExpenseDao.kt").write_text(_dao_fixture(), encoding="utf-8")
    (root / "CommentOnly.kt").write_text(
        "package com.example.probe\n"
        "\n"
        "/**\n"
        " * See [ProbeExpenseDao.insert] and expenseDao.update for details.\n"
        " */\n"
        "// expenseDao.delete(entity)\n"
        "object CommentOnly\n",
        encoding="utf-8",
    )

    payload = _scan(tmp_path)
    assert payload["diagnostics"] == [], payload["diagnostics"]
    assert payload["findings"] == [], payload["findings"]


# ── 4. Strict superiority: receiver-TYPE discovery, not receiver-NAME ───────


def test_differently_named_receiver_is_still_discovered(tmp_path):
    """A mutation call through a receiver NOT named ``expenseDao`` is
    invisible to the retired name-based regexes but discovered by D4 —
    the canonical guard is strictly broader."""
    root = _source_root(tmp_path)
    (root / "ProbeExpenseDao.kt").write_text(_dao_fixture(), encoding="utf-8")
    (root / "RenamedReceiver.kt").write_text(
        "package com.example.probe\n"
        "\n"
        "class RenamedReceiver(private val dao: ProbeExpenseDao) {\n"
        "    fun save(entity: Int) { dao.insert(entity) }\n"
        "}\n",
        encoding="utf-8",
    )

    payload = _scan(tmp_path)
    assert payload["diagnostics"] == [], payload["diagnostics"]
    mutations = [
        finding for finding in payload["findings"]
        if finding["rule"] == "DB_UNAUTHORIZED_MUTATION"
    ]
    assert len(mutations) == 1
    assert mutations[0]["identity"]["accessor"] == "dao"
    assert mutations[0]["identity"]["operation"] == "insert"


def test_unresolved_receiver_scope_fails_closed_not_silent(tmp_path):
    """A mutation-shaped call whose receiver scope cannot be resolved is a
    blocking diagnostic (exit 2 upstream), never a silent pass — the
    fail-closed property the retired textual scanners did not have."""
    root = _source_root(tmp_path)
    (root / "ProbeExpenseDao.kt").write_text(_dao_fixture(), encoding="utf-8")
    (root / "UnresolvedScope.kt").write_text(
        "package com.example.probe\n"
        "\n"
        "class UnresolvedScope {\n"
        "    fun save(entity: Int) {\n"
        "        expenseDao.insert(entity)\n"
        "    }\n"
        "}\n",
        encoding="utf-8",
    )

    payload = _scan(tmp_path)
    # No finding may be emitted without resolved identity; the unresolved
    # scope must surface as a diagnostic so the scan is untrusted.
    assert payload["findings"] == [], payload["findings"]
    codes = {item["code"] for item in payload["diagnostics"]}
    assert "DB_DAO_SCOPE_UNRESOLVED" in codes or "DB_SIGNATURE_UNRESOLVED" in codes, (
        codes
    )
    assert payload["statistics"]["trusted"] is False
