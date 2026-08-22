"""Candidate-identity tests for the v1 -> v2 DB-policy migration machinery.

These tests exercise ``migrate_policy(entries, repo_root)`` directly against
synthetic temporary repositories shaped like the real production tree
(``app/src/main/java/...`` with ``@Dao`` interfaces and repository classes).
They replace the obsolete suite that asserted the retired v1-shape CLI API
(``load_policy`` / ``resolve_entry`` / ``RESOLVED_EXACTLY`` report rows) and
the stale checked-in 99-input / 9-resolved / 90-unresolved artifact counts;
none of those symbols exist any longer.

Every resolved row must be an exact, schema-valid v2 candidate identity and
every failure must surface as exactly one closed ``STATUS_*`` constant —
never a fabricated resolution, never a defaulted kind, never free-form debt.

Authored coverage; execution pending in this environment.
"""

from __future__ import annotations

import sys
from pathlib import Path

# ``policy_v2_candidate`` uses in-package relative imports, so it must be
# imported as ``scripts.db_guard.policy_v2_candidate`` with the worktree
# root on ``sys.path``.
_ROOT = Path(__file__).resolve().parents[1]
if str(_ROOT) not in sys.path:
    sys.path.insert(0, str(_ROOT))

from scripts.db_guard.policy_model import BarrierMode, CallableKind  # noqa: E402
from scripts.db_guard.policy_v2_candidate import (  # noqa: E402
    STATUS_BARRIER_MODE_UNRESOLVED,
    STATUS_CALLABLE_MISSING,
    STATUS_DAO_TARGET_AMBIGUOUS,
    STATUS_MUTATION_PAIR_MISSING,
    STATUS_RESOLVED,
    find_duplicate_mutation_keys,
    migrate_policy,
)

REPO_KT = "app/src/main/java/com/example/Repository.kt"
DAO_KT = "app/src/main/java/com/example/ExpenseDao.kt"
AUDIT_DAO_KT = "app/src/main/java/com/example/AuditDao.kt"
ARCHIVE_DAO_KT = "app/src/main/java/com/example/ArchiveDao.kt"


def _write_repo(tmp_path: Path, files: dict) -> None:
    """Materialize ``{repo-relative posix path: text}`` under ``tmp_path``."""
    for relative in sorted(files):
        target = tmp_path / Path(*relative.split("/"))
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(files[relative], encoding="utf-8")


def _dao_source(package: str, name: str) -> str:
    """A minimal Room DAO interface fixture."""
    return (
        "package " + package + "\n"
        "\n"
        "@Dao\n"
        "interface " + name + " {\n"
        "    fun insert(value: Long): Long\n"
        "}\n"
    )


def _legacy_entry(
    path,
    klass,
    method,
    *,
    params=("Int",),
    receiver=None,
    daos=("ExpenseDao",),
    operation="insert",
    barrier_required=True,
    barrier_via=None,
    reason="controlled migration reason",
    owner="expense-owners",
    linked_issue="ISSUE-100",
):
    """Build one legacy v1 YAML-shaped policy entry mapping."""
    item = {
        "path": path,
        "class": klass,
        "method": method,
        "daos": list(daos),
        "operation": operation,
        "barrier_required": barrier_required,
        "reason": reason,
        "owner": owner,
        "linked_issue": linked_issue,
        "signature": {"parameters": list(params), "receiver": receiver},
    }
    if barrier_via is not None:
        item["barrier_via"] = barrier_via
    return item


def _only_entry(result):
    """Assert a single-entry batch fully resolved and return its candidate."""
    assert result.input_count == 1
    assert result.unresolved == ()
    assert len(result.resolved) == 1
    return result.resolved[0].entry


EXPENSE_DAO_SOURCE = _dao_source("com.example", "ExpenseDao")

BASIC_REPO_SOURCE = (
    "package com.example\n"
    "\n"
    "class Repository {\n"
    "    fun save(value: Int) {\n"
    "        expenseDao.insert(value)\n"
    "    }\n"
    "}\n"
)


def _standard_repo_files():
    return {DAO_KT: EXPENSE_DAO_SOURCE, REPO_KT: BASIC_REPO_SOURCE}


# ── (1) Member function identity ──────────────────────────────────────────────


def test_member_function_resolves_to_full_fqcn_and_function_kind(tmp_path):
    _write_repo(tmp_path, _standard_repo_files())
    entries = [_legacy_entry(REPO_KT, "Repository", "save")]
    result = migrate_policy(entries, str(tmp_path))
    assert STATUS_RESOLVED == "RESOLVED"
    entry = _only_entry(result)
    assert entry.path == REPO_KT
    assert entry.owner_fqcn == "com.example.Repository"
    assert entry.kind is CallableKind.FUNCTION
    assert entry.kind.value == "function"
    assert entry.method == "save"
    assert entry.parameter_types == ("Int",)
    assert entry.receiver is None
    assert entry.dao_accessor == "expenseDao"
    assert entry.dao_fqcn == "com.example.ExpenseDao"
    assert entry.operation == "insert"
    assert entry.barrier_mode is BarrierMode.DIRECT


# ── (2) Nested owner FQCN ─────────────────────────────────────────────────────


def test_nested_owner_handle_resolves_to_nested_fqcn(tmp_path):
    nested_source = (
        "package com.example\n"
        "\n"
        "class Outer {\n"
        "    class Handle {\n"
        "        fun sync(value: Int) {\n"
        "            expenseDao.insert(value)\n"
        "        }\n"
        "    }\n"
        "}\n"
    )
    _write_repo(
        tmp_path,
        {DAO_KT: EXPENSE_DAO_SOURCE, REPO_KT: nested_source},
    )
    entries = [
        _legacy_entry(REPO_KT, "com.example.Outer.Handle", "sync"),
    ]
    result = migrate_policy(entries, str(tmp_path))
    entry = _only_entry(result)
    assert entry.owner_fqcn == "com.example.Outer.Handle"
    assert entry.kind.value == "function"
    assert entry.method == "sync"


# ── (3) Same simple name in two packages ──────────────────────────────────────


def test_same_simple_name_in_two_packages_resolves_per_entry_path(tmp_path):
    north_kt = "app/src/main/java/com/example/a/NorthRepo.kt"
    south_kt = "app/src/main/java/com/example/b/NorthRepo.kt"
    files = {
        "app/src/main/java/com/example/a/ExpenseDao.kt": _dao_source(
            "com.example.a", "ExpenseDao"
        ),
        "app/src/main/java/com/example/b/AuditDao.kt": _dao_source(
            "com.example.b", "AuditDao"
        ),
        north_kt: (
            "package com.example.a\n"
            "\n"
            "class NorthRepo {\n"
            "    fun save(value: Int) {\n"
            "        expenseDao.insert(value)\n"
            "    }\n"
            "}\n"
        ),
        south_kt: (
            "package com.example.b\n"
            "\n"
            "class NorthRepo {\n"
            "    fun save(value: Int) {\n"
            "        auditDao.insert(value)\n"
            "    }\n"
            "}\n"
        ),
    }
    _write_repo(tmp_path, files)
    entries = [
        _legacy_entry(north_kt, "NorthRepo", "save"),
        _legacy_entry(south_kt, "NorthRepo", "save", daos=("AuditDao",)),
    ]
    result = migrate_policy(entries, str(tmp_path))
    assert result.input_count == 2
    assert result.unresolved == ()
    assert len(result.resolved) == 2
    by_owner = {row.entry.owner_fqcn: row.entry for row in result.resolved}
    assert set(by_owner) == {
        "com.example.a.NorthRepo",
        "com.example.b.NorthRepo",
    }
    assert by_owner["com.example.a.NorthRepo"].path == north_kt
    assert by_owner["com.example.a.NorthRepo"].dao_fqcn == (
        "com.example.a.ExpenseDao"
    )
    assert by_owner["com.example.b.NorthRepo"].path == south_kt
    assert by_owner["com.example.b.NorthRepo"].dao_fqcn == (
        "com.example.b.AuditDao"
    )


# ── (4)/(5) Overload resolution ───────────────────────────────────────────────

OVERLOADS_REPO_SOURCE = (
    "package com.example\n"
    "\n"
    "class Repository {\n"
    "    fun save(value: Int) {\n"
    "        expenseDao.insert(value)\n"
    "    }\n"
    "\n"
    "    fun save(value: String) {\n"
    "        expenseDao.insert(value)\n"
    "    }\n"
    "}\n"
)

SIBLING_ONLY_MUTATION_REPO_SOURCE = (
    "package com.example\n"
    "\n"
    "class Repository {\n"
    "    fun save(value: Int) {\n"
    "        expenseDao.getById(value)\n"
    "    }\n"
    "\n"
    "    fun save(value: String) {\n"
    "        expenseDao.insert(value)\n"
    "    }\n"
    "}\n"
)


def test_overloads_resolve_only_hinted_signature_parameters(tmp_path):
    _write_repo(
        tmp_path,
        {DAO_KT: EXPENSE_DAO_SOURCE, REPO_KT: OVERLOADS_REPO_SOURCE},
    )
    entries = [_legacy_entry(REPO_KT, "Repository", "save", params=("Int",))]
    result = migrate_policy(entries, str(tmp_path))
    entry = _only_entry(result)
    assert entry.parameter_types == ("Int",)
    assert entry.method == "save"


def test_sibling_overload_only_mutation_fails_closed(tmp_path):
    # The hinted Int overload contains no DAO mutation; the insert lives only
    # on the String sibling.  Nothing may be borrowed across overloads.
    _write_repo(
        tmp_path,
        {
            DAO_KT: EXPENSE_DAO_SOURCE,
            REPO_KT: SIBLING_ONLY_MUTATION_REPO_SOURCE,
        },
    )
    entries = [_legacy_entry(REPO_KT, "Repository", "save", params=("Int",))]
    result = migrate_policy(entries, str(tmp_path))
    assert result.resolved == ()
    assert len(result.unresolved) == 1
    row = result.unresolved[0]
    assert row.status == STATUS_MUTATION_PAIR_MISSING
    assert STATUS_MUTATION_PAIR_MISSING == "MUTATION_PAIR_MISSING"
    assert row.legacy_method == "save"


# ── (6) Extension receiver ────────────────────────────────────────────────────


def test_extension_receiver_emitted_exactly(tmp_path):
    extension_source = (
        "package com.example\n"
        "\n"
        "class Repository {\n"
        "    fun String?.sync(value: Int) {\n"
        "        expenseDao.insert(value)\n"
        "    }\n"
        "}\n"
    )
    _write_repo(
        tmp_path,
        {DAO_KT: EXPENSE_DAO_SOURCE, REPO_KT: extension_source},
    )
    entries = [
        _legacy_entry(
            REPO_KT, "Repository", "sync", params=("Int",), receiver="String?"
        )
    ]
    result = migrate_policy(entries, str(tmp_path))
    entry = _only_entry(result)
    assert entry.receiver == "String?"
    assert entry.method == "sync"
    assert entry.parameter_types == ("Int",)
    assert entry.kind.value == "function"


# ── (7) Top-level callables are never fabricated ──────────────────────────────


def test_top_level_callable_is_never_fabricated(tmp_path):
    top_level_source = (
        "package com.example\n"
        "\n"
        "class Repository {\n"
        "    fun other(value: Int) {\n"
        "        expenseDao.insert(value)\n"
        "    }\n"
        "}\n"
        "\n"
        "fun insert(value: Int) {\n"
        "    expenseDao.insert(value)\n"
        "}\n"
    )
    _write_repo(
        tmp_path,
        {DAO_KT: EXPENSE_DAO_SOURCE, REPO_KT: top_level_source},
    )
    # An exact-signature top-level fun exists in this very file, but the
    # entry targets the owner class; the top-level callable must stay
    # undiscovered instead of being fabricated into a resolved row.
    entries = [_legacy_entry(REPO_KT, "Repository", "insert")]
    result = migrate_policy(entries, str(tmp_path))
    assert result.resolved == ()
    assert len(result.unresolved) == 1
    row = result.unresolved[0]
    assert row.status == STATUS_CALLABLE_MISSING
    assert STATUS_CALLABLE_MISSING == "CALLABLE_MISSING"
    assert row.legacy_class == "Repository"
    assert row.legacy_method == "insert"


# ── (8) Unsupported kinds never default to function ───────────────────────────


def test_unsupported_kinds_stay_unresolved_and_never_default_to_function(
    tmp_path,
):
    non_fun_source = (
        "package com.example\n"
        "\n"
        "class Repository(\n"
        "    private val expenseDao: ExpenseDao\n"
        ") {\n"
        "    val cache: Long get() = 0L\n"
        "\n"
        "    fun real(value: Int) {\n"
        "        expenseDao.insert(value)\n"
        "    }\n"
        "}\n"
    )
    _write_repo(
        tmp_path,
        {DAO_KT: EXPENSE_DAO_SOURCE, REPO_KT: non_fun_source},
    )
    entries = [
        _legacy_entry(REPO_KT, "Repository", "Repository", params=()),
        _legacy_entry(REPO_KT, "Repository", "cache", params=()),
    ]
    result = migrate_policy(entries, str(tmp_path))
    # Neither the constructor nor the property accessor may be emitted as a
    # fabricated kind="function" row.
    assert result.resolved == ()
    assert [row.status for row in result.unresolved] == [
        STATUS_CALLABLE_MISSING,
        STATUS_CALLABLE_MISSING,
    ]
    assert [row.legacy_method for row in result.unresolved] == [
        "Repository",
        "cache",
    ]


# ── (9)/(10) Parameter order is identity ──────────────────────────────────────

MULTI_PARAM_REPO_SOURCE = (
    "package com.example\n"
    "\n"
    "class Repository {\n"
    "    fun transfer(first: List<String?>, second: Int, third: String?) {\n"
    "        expenseDao.insert(1)\n"
    "    }\n"
    "}\n"
)


def _multi_param_repo(tmp_path):
    _write_repo(
        tmp_path,
        {DAO_KT: EXPENSE_DAO_SOURCE, REPO_KT: MULTI_PARAM_REPO_SOURCE},
    )


def test_ordered_parameter_types_are_preserved_exactly(tmp_path):
    _multi_param_repo(tmp_path)
    entries = [
        _legacy_entry(
            REPO_KT,
            "Repository",
            "transfer",
            params=("List<String?>", "Int", "String?"),
        )
    ]
    result = migrate_policy(entries, str(tmp_path))
    entry = _only_entry(result)
    assert entry.parameter_types == ("List<String?>", "Int", "String?")


def test_swapped_parameter_hints_resolve_to_source_derived_order(tmp_path):
    # Legacy hint order is a HINT ONLY: with exactly one same-name member
    # fun in source, the entry resolves and the emitted identity is that
    # declaration's own parsed signature — the source order
    # (List<String?>, Int, String?) — never the swapped legacy order.
    _multi_param_repo(tmp_path)
    entries = [
        _legacy_entry(
            REPO_KT,
            "Repository",
            "transfer",
            params=("Int", "List<String?>", "String?"),
        )
    ]
    result = migrate_policy(entries, str(tmp_path))
    entry = _only_entry(result)
    assert entry.method == "transfer"
    assert entry.parameter_types == ("List<String?>", "Int", "String?")


# ── (11) Nullability is identity ──────────────────────────────────────────────


def test_string_vs_nullable_string_are_distinct_identities(tmp_path):
    nullable_overloads_source = (
        "package com.example\n"
        "\n"
        "class Repository {\n"
        "    fun store(value: String) {\n"
        "        expenseDao.insert(1)\n"
        "    }\n"
        "\n"
        "    fun store(value: String?) {\n"
        "        expenseDao.insert(1)\n"
        "    }\n"
        "}\n"
    )
    _write_repo(
        tmp_path,
        {DAO_KT: EXPENSE_DAO_SOURCE, REPO_KT: nullable_overloads_source},
    )
    entries = [
        _legacy_entry(REPO_KT, "Repository", "store", params=("String",)),
        _legacy_entry(REPO_KT, "Repository", "store", params=("String?",)),
    ]
    result = migrate_policy(entries, str(tmp_path))
    assert result.input_count == 2
    assert result.unresolved == ()
    assert len(result.resolved) == 2
    shapes = sorted(row.entry.parameter_types for row in result.resolved)
    assert shapes == [("String",), ("String?",)]


# ── (12) One callable, two operations ─────────────────────────────────────────


def test_one_callable_two_operations_share_callable_key(tmp_path):
    two_ops_source = (
        "package com.example\n"
        "\n"
        "class Repository {\n"
        "    fun upsert(value: Int) {\n"
        "        expenseDao.insert(value)\n"
        "        expenseDao.delete(value)\n"
        "    }\n"
        "}\n"
    )
    _write_repo(
        tmp_path,
        {DAO_KT: EXPENSE_DAO_SOURCE, REPO_KT: two_ops_source},
    )
    entries = [_legacy_entry(REPO_KT, "Repository", "upsert")]
    result = migrate_policy(entries, str(tmp_path))
    assert result.unresolved == ()
    assert len(result.resolved) == 2
    first, second = result.resolved
    assert first.index == second.index == 0
    assert [row.entry.operation for row in result.resolved] == [
        "insert",
        "delete",
    ]
    assert first.entry.callable_key() == second.entry.callable_key()
    assert first.entry.mutation_key() != second.entry.mutation_key()
    assert {row.entry.dao_accessor for row in result.resolved} == {"expenseDao"}


# ── (13) Multi-DAO legacy row splits to exact rows ────────────────────────────


def test_multi_dao_legacy_row_splits_into_exact_rows(tmp_path):
    multi_dao_source = (
        "package com.example\n"
        "\n"
        "class Repository {\n"
        "    fun mirror(value: Int) {\n"
        "        expenseDao.insert(value)\n"
        "        auditDao.markSynced(value)\n"
        "    }\n"
        "}\n"
    )
    _write_repo(
        tmp_path,
        {
            DAO_KT: EXPENSE_DAO_SOURCE,
            AUDIT_DAO_KT: _dao_source("com.example", "AuditDao"),
            REPO_KT: multi_dao_source,
        },
    )
    entries = [
        _legacy_entry(
            REPO_KT,
            "Repository",
            "mirror",
            daos=("ExpenseDao", "AuditDao"),
        )
    ]
    result = migrate_policy(entries, str(tmp_path))
    assert result.unresolved == ()
    assert len(result.resolved) == 2
    triples = {
        (row.entry.dao_accessor, row.entry.dao_fqcn, row.entry.operation)
        for row in result.resolved
    }
    assert triples == {
        ("expenseDao", "com.example.ExpenseDao", "insert"),
        ("auditDao", "com.example.AuditDao", "markSynced"),
    }
    callable_keys = {row.entry.callable_key() for row in result.resolved}
    assert len(callable_keys) == 1


# ── (14) Constructor val DAO property ─────────────────────────────────────────


def test_constructor_val_dao_property_resolves(tmp_path):
    constructor_val_source = (
        "package com.example\n"
        "\n"
        "class Repository(\n"
        "    private val expenseDao: ExpenseDao\n"
        ") {\n"
        "    fun add(value: Int) {\n"
        "        expenseDao.insert(value)\n"
        "    }\n"
        "}\n"
    )
    _write_repo(
        tmp_path,
        {DAO_KT: EXPENSE_DAO_SOURCE, REPO_KT: constructor_val_source},
    )
    entries = [_legacy_entry(REPO_KT, "Repository", "add")]
    result = migrate_policy(entries, str(tmp_path))
    entry = _only_entry(result)
    assert entry.owner_fqcn == "com.example.Repository"
    assert entry.dao_accessor == "expenseDao"
    assert entry.dao_fqcn == "com.example.ExpenseDao"
    assert entry.operation == "insert"


# ── (15) Same-simple-name DAOs are ambiguous ──────────────────────────────────


def test_two_same_simple_name_daos_fail_dao_target_ambiguous(tmp_path):
    files = {
        "app/src/main/java/com/example/a/ExpenseDao.kt": _dao_source(
            "com.example.a", "ExpenseDao"
        ),
        "app/src/main/java/com/example/b/ExpenseDao.kt": _dao_source(
            "com.example.b", "ExpenseDao"
        ),
        REPO_KT: BASIC_REPO_SOURCE,
    }
    _write_repo(tmp_path, files)
    entries = [_legacy_entry(REPO_KT, "Repository", "save")]
    result = migrate_policy(entries, str(tmp_path))
    assert result.resolved == ()
    assert len(result.unresolved) == 1
    row = result.unresolved[0]
    assert row.status == STATUS_DAO_TARGET_AMBIGUOUS
    assert STATUS_DAO_TARGET_AMBIGUOUS == "DAO_TARGET_AMBIGUOUS"
    assert row.detail == ""
    assert row.legacy_class == "Repository"
    assert row.legacy_method == "save"


# ── (16) Wrong accessor/operation pairing ─────────────────────────────────────


def test_wrong_accessor_operation_pairing_yields_pair_missing(tmp_path):
    """A GENUINE legacy-authorization mismatch fails closed (rule 5).

    The callable body provably performs ``expenseDao.insert(...)`` — a
    mutation that resolves to a concrete triple — but the legacy entry
    authorizes only ``ArchiveDao``, an interface that EXISTS in the
    fixture index.  The failure therefore comes from the
    authorization-intent gate (no resolved accessor is authorized by the
    legacy ``daos`` list), not from a read-only-body accident and not from
    DAO identity/target ambiguity.
    """
    inserting_source = (
        "package com.example\n"
        "\n"
        "class Repository {\n"
        "    fun save(id: Long) {\n"
        "        expenseDao.insert(id)\n"
        "    }\n"
        "}\n"
    )
    _write_repo(
        tmp_path,
        {
            DAO_KT: EXPENSE_DAO_SOURCE,
            ARCHIVE_DAO_KT: _dao_source("com.example", "ArchiveDao"),
            REPO_KT: inserting_source,
        },
    )
    entries = [
        _legacy_entry(
            REPO_KT,
            "Repository",
            "save",
            params=("Long",),
            daos=("ArchiveDao",),
            operation="insert",
        )
    ]
    result = migrate_policy(entries, str(tmp_path))
    # Nothing is emitted: the resolved expenseDao mutation is NOT
    # authorized by this legacy row, and unauthorized accessors must never
    # leak into candidates.
    assert result.resolved == ()
    assert len(result.unresolved) == 1
    row = result.unresolved[0]
    assert row.status == STATUS_MUTATION_PAIR_MISSING
    # The bounded detail pins the intent gate — not the empty-body path —
    # as the cause of the debt.
    assert row.detail == "no mutation matches legacy daos"
    assert row.index == 0
    assert row.legacy_class == "Repository"
    assert row.legacy_method == "save"


def test_read_only_body_yields_pair_missing(tmp_path):
    # Kept from before the intent gate existed: a read-only body extracts
    # no mutation at all, so nothing pairs regardless of the legacy hint
    # and the row fails closed instead of inventing a mutation.  The empty
    # detail distinguishes this no-extraction path from the authorization
    # mismatch above.
    read_only_source = (
        "package com.example\n"
        "\n"
        "class Repository {\n"
        "    fun load(id: Long) {\n"
        "        expenseDao.getById(id)\n"
        "    }\n"
        "}\n"
    )
    _write_repo(
        tmp_path,
        {DAO_KT: EXPENSE_DAO_SOURCE, REPO_KT: read_only_source},
    )
    entries = [
        _legacy_entry(
            REPO_KT,
            "Repository",
            "load",
            params=("Long",),
            daos=("AuditDao",),
            operation="insert",
        )
    ]
    result = migrate_policy(entries, str(tmp_path))
    assert result.resolved == ()
    assert len(result.unresolved) == 1
    row = result.unresolved[0]
    assert row.status == STATUS_MUTATION_PAIR_MISSING
    assert row.detail == ""


# ── (17) Comments and strings are not calls ───────────────────────────────────


def test_comments_and_strings_are_not_mutation_calls(tmp_path):
    masked_source = (
        "package com.example\n"
        "\n"
        "class Repository {\n"
        "    fun document(value: Int) {\n"
        "        // expenseDao.insert(value)\n"
        "        val note = \"expenseDao.delete(value)\"\n"
        "        /* expenseDao.insert(value) */\n"
        "        expenseDao.getById(value)\n"
        "    }\n"
        "}\n"
    )
    _write_repo(
        tmp_path,
        {DAO_KT: EXPENSE_DAO_SOURCE, REPO_KT: masked_source},
    )
    entries = [_legacy_entry(REPO_KT, "Repository", "document")]
    result = migrate_policy(entries, str(tmp_path))
    assert result.resolved == ()
    assert len(result.unresolved) == 1
    assert result.unresolved[0].status == STATUS_MUTATION_PAIR_MISSING


# ── (18) Safe-call / complex receivers fail closed ────────────────────────────


def test_safe_call_and_complex_receivers_fail_closed(tmp_path):
    unsafe_receivers_source = (
        "package com.example\n"
        "\n"
        "class Repository {\n"
        "    fun viaSafeCall(value: Int) {\n"
        "        expenseDao?.insert(value)\n"
        "    }\n"
        "\n"
        "    fun viaComplexReceiver(value: Int) {\n"
        "        expenseDaoProvider.get().insert(value)\n"
        "    }\n"
        "}\n"
    )
    _write_repo(
        tmp_path,
        {DAO_KT: EXPENSE_DAO_SOURCE, REPO_KT: unsafe_receivers_source},
    )
    entries = [
        _legacy_entry(REPO_KT, "Repository", "viaSafeCall"),
        _legacy_entry(REPO_KT, "Repository", "viaComplexReceiver"),
    ]
    result = migrate_policy(entries, str(tmp_path))
    assert result.input_count == 2
    assert result.resolved == ()
    assert [row.status for row in result.unresolved] == [
        STATUS_MUTATION_PAIR_MISSING,
        STATUS_MUTATION_PAIR_MISSING,
    ]


# ── (19) Barrier-mode conversion is closed ────────────────────────────────────


def test_barrier_required_false_or_barrier_via_is_unresolved(tmp_path):
    _write_repo(tmp_path, _standard_repo_files())
    entries = [
        _legacy_entry(REPO_KT, "Repository", "save", barrier_required=False),
        _legacy_entry(
            REPO_KT,
            "Repository",
            "save",
            barrier_required=True,
            barrier_via="WorkerExecutionGuard",
        ),
    ]
    result = migrate_policy(entries, str(tmp_path))
    # Both entries would otherwise resolve cleanly; the barrier gate runs
    # first and fails both closed.
    assert result.resolved == ()
    assert [row.status for row in result.unresolved] == [
        STATUS_BARRIER_MODE_UNRESOLVED,
        STATUS_BARRIER_MODE_UNRESOLVED,
    ]
    assert STATUS_BARRIER_MODE_UNRESOLVED == "BARRIER_MODE_UNRESOLVED"


# ── (20) Duplicate mutation-key detection ─────────────────────────────────────


def test_duplicate_mutation_keys_detected_by_find_duplicate_mutation_keys(
    tmp_path,
):
    _write_repo(tmp_path, _standard_repo_files())
    duplicated = _legacy_entry(REPO_KT, "Repository", "save")
    result = migrate_policy([duplicated, dict(duplicated)], str(tmp_path))
    assert result.input_count == 2
    assert result.unresolved == ()
    assert len(result.resolved) == 2
    expected_key = (
        REPO_KT
        + "|com.example.Repository|function|save|null|Int"
        + "|expenseDao|com.example.ExpenseDao|insert"
    )
    keys = {row.entry.mutation_key().canonical_key() for row in result.resolved}
    assert keys == {expected_key}
    assert find_duplicate_mutation_keys(result) == (expected_key,)


# ── Artifact/CLI contract against the real repository ────────────────────────
#
# The tests below exercise the REAL checked-in repository strictly read-only
# through the actual CLI (``subprocess.run([sys.executable, <script>, ...])``).
# Every artifact they produce lands under the pytest ``tmp_path``; the repo
# itself is never mutated (``test_active_policy_overwrite_fails`` proves the
# overwrite guard).  The script derives its repo root from its own
# ``__file__.parents[1]``, so invoking the checked-in script by absolute path
# pins the analysis to this worktree regardless of the subprocess cwd.
#
# Exit-code table under test: 0 = every row resolved; 1 = visible unresolved
# debt (or nothing resolved); 2 = usage/collision/duplicate failure.

import hashlib  # noqa: E402
import json  # noqa: E402
import re  # noqa: E402
import subprocess  # noqa: E402

import yaml  # noqa: E402

from scripts.db_guard.policy_v2_candidate import (  # noqa: E402
    MIGRATION_STATUSES_EXTENDED,
)
from scripts.db_guard.policy_v2_loader import load_policy_v2  # noqa: E402

REPO_ROOT = Path(__file__).resolve().parents[1]
MIGRATION_CLI = REPO_ROOT / "scripts" / "migrate_db_policy_signatures.py"
ACTIVE_POLICY = REPO_ROOT / "config" / "guards" / "db_ownership_policy.yml"
TRACKED_CANDIDATE = (
    REPO_ROOT
    / "config"
    / "guards"
    / "db_ownership_policy.signatures.candidate.yml"
)

_KT_LINE_MARKER = re.compile(r"\.kt:\d+")


def _run_cli(*args):
    """Invoke the real migration CLI; returns the CompletedProcess."""
    return subprocess.run(
        [sys.executable, str(MIGRATION_CLI), *args],
        capture_output=True,
        encoding="utf-8",
        errors="replace",
        cwd=str(REPO_ROOT),
        timeout=600,
        check=False,
    )


def _write_candidate_artifacts(out_dir: Path):
    """Success-path ``--write-candidate`` run into ``out_dir``.

    Returns ``(completed, candidate_path, report_path)``.
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    candidate = out_dir / "candidate.yml"
    report = out_dir / "report.json"
    completed = _run_cli(
        "--write-candidate",
        "--output",
        str(candidate),
        "--report",
        str(report),
    )
    return completed, candidate, report


def _fingerprint(path: Path):
    """mtime/size/sha256 triple proving a watched file was not touched."""
    data = path.read_bytes()
    stat = path.stat()
    return (stat.st_mtime_ns, stat.st_size, hashlib.sha256(data).hexdigest())


def _string_leaves(value):
    """Yield every string leaf of a nested JSON-shaped structure."""
    if isinstance(value, str):
        yield value
    elif isinstance(value, dict):
        for item in value.values():
            yield from _string_leaves(item)
    elif isinstance(value, list):
        for item in value:
            yield from _string_leaves(item)


def test_v2_loader_accepts_generated_candidate(tmp_path):
    completed, candidate, _ = _write_candidate_artifacts(tmp_path / "tmp")
    assert completed.returncode in (0, 1)
    assert candidate.exists()
    document, errors = load_policy_v2(candidate)
    assert errors == []
    assert document


def test_output_contains_no_legacy_field_names(tmp_path):
    completed, candidate, _ = _write_candidate_artifacts(tmp_path / "tmp")
    assert completed.returncode in (0, 1)
    document = yaml.safe_load(candidate.read_text(encoding="utf-8"))
    legacy_fields = {
        "class",
        "daos",
        "signature",
        "barrier_required",
        "barrier_via",
    }
    for entry in document["entries"]:
        assert not legacy_fields & set(entry)


def test_report_has_deterministic_ordering(tmp_path):
    first = tmp_path / "report-one.json"
    second = tmp_path / "report-two.json"
    _run_cli("--check", "--report", str(first))
    _run_cli("--check", "--report", str(second))
    assert first.read_bytes() == second.read_bytes()


def test_report_contains_no_raw_source_or_exception_text(tmp_path):
    report = tmp_path / "report.json"
    _run_cli("--check", "--report", str(report))
    payload = json.loads(report.read_text(encoding="utf-8"))
    for text in _string_leaves(payload):
        assert "fun " not in text
        assert _KT_LINE_MARKER.search(text) is None
        assert "Traceback" not in text
    statuses = {row["status"] for row in payload["unresolved"]}
    assert statuses <= MIGRATION_STATUSES_EXTENDED


def test_check_is_read_only(tmp_path):
    guards_dir = ACTIVE_POLICY.parent
    watched = (ACTIVE_POLICY, TRACKED_CANDIDATE)
    before = [_fingerprint(path) for path in watched]
    listing_before = sorted(item.name for item in guards_dir.iterdir())
    completed = _run_cli("--check")
    assert completed.returncode in (0, 1)
    # Unresolved debt is expected on the real repository: pin the --check run
    # to exit code 1 (visible debt), never 0 and never a crash code.
    assert completed.returncode == 1
    assert [_fingerprint(path) for path in watched] == before
    assert sorted(item.name for item in guards_dir.iterdir()) == listing_before
    # No --output/--report given: nothing may be written anywhere.
    assert list(tmp_path.iterdir()) == []


def test_candidate_report_collision_fails(tmp_path):
    collide = tmp_path / "collide.artifact"
    completed = _run_cli(
        "--write-candidate",
        "--output",
        str(collide),
        "--report",
        str(collide),
    )
    assert completed.returncode == 2
    assert "Traceback" not in completed.stderr
    # The collision guard fires before any analysis or write begins.
    assert not collide.exists()
    assert list(tmp_path.iterdir()) == []


def test_active_policy_overwrite_fails(tmp_path):
    before = _fingerprint(ACTIVE_POLICY)
    completed = _run_cli("--write-candidate", "--output", str(ACTIVE_POLICY))
    assert completed.returncode == 2
    assert "Traceback" not in completed.stderr
    assert _fingerprint(ACTIVE_POLICY) == before


def test_no_temp_files_remain_after_runs(tmp_path):
    out_dir = tmp_path / "tmp"
    completed, _, _ = _write_candidate_artifacts(out_dir)
    assert completed.returncode in (0, 1)
    assert sorted(item.name for item in out_dir.iterdir()) == [
        "candidate.yml",
        "report.json",
    ]
    collide = out_dir / "collide.artifact"
    failed = _run_cli(
        "--write-candidate",
        "--output",
        str(collide),
        "--report",
        str(collide),
    )
    assert failed.returncode == 2
    names = sorted(item.name for item in out_dir.iterdir())
    assert names == ["candidate.yml", "report.json"]
    assert not any(name.endswith((".tmp", ".part")) for name in names)


def test_no_fixed_result_totals_enforced(tmp_path):
    source = MIGRATION_CLI.read_text(encoding="utf-8")
    # The retired pinned 99-input artifact totals must stay gone from the tool.
    assert "99" not in source
    report = tmp_path / "report.json"
    _run_cli("--check", "--report", str(report))
    payload = json.loads(report.read_text(encoding="utf-8"))
    counts = payload["counts"]
    assert counts["resolved"] == len(payload["resolved"])
    assert counts["unresolved"] == len(payload["unresolved"])
    # Every input entry surfaces as at least one row (a splitting entry can
    # emit several resolved rows), so the row indexes must cover exactly the
    # input range — consistency is computed from the rows, never pinned to
    # fixed artifact totals.
    indexes = {row["index"] for row in payload["resolved"]} | {
        row["index"] for row in payload["unresolved"]
    }
    assert indexes == set(range(counts["input"]))


def test_real_checked_in_candidate_is_reproducible(tmp_path):
    """Regenerated candidate bytes must equal the tracked candidate artifact.

    NOTE: this passes only after Step 8 regenerates the tracked candidate
    (``config/guards/db_ownership_policy.signatures.candidate.yml``) through
    this very tool; until then this test documents the required end state.
    """
    regen = tmp_path / "regen.yml"
    completed = _run_cli("--write-candidate", "--output", str(regen))
    assert completed.returncode in (0, 1)
    assert regen.read_bytes() == TRACKED_CANDIDATE.read_bytes()


# ── Appended: synthetic tmp legacy policies through the real CLI ─────────────
#
# These tests write legacy v1 policy YAML under pytest ``tmp_path`` and feed it
# to the real CLI via ``--policy``; analysis stays pinned to the REAL worktree
# because the script derives its repo root from its own ``__file__``.  They pin
# the remaining exit-code table cells end to end:
#   * duplicate mutation keys -> 2, candidate never written;
#   * malformed YAML input    -> 2, nothing written;
#   * fully resolved batch    -> 0, schema-valid candidate written;
#   * zero-resolved batch     -> 1, candidate never written.


def _probe_resolving_legacy_entry(report_path: Path):
    """Rebuild one guaranteed-resolving legacy entry from the real repo.

    Runs ``--check`` once against the active policy, reads the report JSON,
    and reconstructs a minimal legacy v1 entry from ``resolved[0]``'s identity
    fields — path, owner simple name, method, DAO accessor hint, operation,
    and the exact callable signature — so it re-resolves against the same
    repository state.
    """
    completed = _run_cli("--check", "--report", str(report_path))
    assert completed.returncode in (0, 1)
    payload = json.loads(report_path.read_text(encoding="utf-8"))
    row = payload["resolved"][0]
    return {
        "path": row["path"],
        "class": row["ownerFqcn"].rsplit(".", 1)[-1],
        "method": row["method"],
        "daos": [row["daoAccessor"]],
        "operation": row["operation"],
        "barrier_required": True,
        "reason": "reconstructed from resolved[0] identity fields",
        "owner": "expense-owners",
        "linked_issue": "ISSUE-100",
        "signature": {
            "parameters": list(row["parameterTypes"]),
            "receiver": row["receiver"],
        },
    }


def test_duplicate_mutation_key_exits_2_via_cli(tmp_path):
    entry = _probe_resolving_legacy_entry(tmp_path / "probe-report.json")
    policy = tmp_path / "duplicate-policy.yml"
    policy.write_text(
        yaml.safe_dump({"entries": [entry, dict(entry)]}, sort_keys=False),
        encoding="utf-8",
    )
    candidate = tmp_path / "candidate.yml"
    completed = _run_cli(
        "--write-candidate",
        "--policy",
        str(policy),
        "--output",
        str(candidate),
    )
    # Duplicate mutation keys are a collision failure: exit 2, never a write.
    assert completed.returncode == 2
    assert "Traceback" not in completed.stderr
    assert not candidate.exists()


def test_malformed_yaml_policy_exits_2_no_write(tmp_path):
    policy = tmp_path / "malformed.yml"
    policy.write_text(":::: not yaml ::::", encoding="utf-8")
    candidate = tmp_path / "candidate.yml"
    completed = _run_cli(
        "--write-candidate",
        "--policy",
        str(policy),
        "--output",
        str(candidate),
    )
    # Malformed/unusable policy input fails closed before any analysis or
    # write begins.
    assert completed.returncode == 2
    assert "Traceback" not in completed.stderr
    assert not candidate.exists()


def test_all_resolved_policy_exits_0_and_writes(tmp_path):
    entry = _probe_resolving_legacy_entry(tmp_path / "probe-report.json")
    policy = tmp_path / "single-entry-policy.yml"
    policy.write_text(
        yaml.safe_dump({"entries": [entry]}, sort_keys=False),
        encoding="utf-8",
    )
    candidate = tmp_path / "candidate.yml"
    completed = _run_cli(
        "--write-candidate",
        "--policy",
        str(policy),
        "--output",
        str(candidate),
    )
    # A batch with every row resolved and no debt exits 0 and writes a
    # candidate that round-trips through the ordinary v2 loader.
    assert completed.returncode == 0
    assert candidate.exists()
    document, errors = load_policy_v2(candidate)
    assert errors == []
    assert document


def test_zero_resolved_policy_exits_1_no_candidate(tmp_path):
    policy = tmp_path / "missing-source-policy.yml"
    policy.write_text(
        yaml.safe_dump(
            {
                "entries": [
                    _legacy_entry(
                        "app/src/main/java/com/example/DoesNotExist.kt",
                        "DoesNotExist",
                        "missing",
                    )
                ]
            },
            sort_keys=False,
        ),
        encoding="utf-8",
    )
    candidate = tmp_path / "candidate.yml"
    completed = _run_cli(
        "--write-candidate",
        "--policy",
        str(policy),
        "--output",
        str(candidate),
    )
    # Zero resolved rows is visible debt: exit 1 and never a candidate write
    # (a report may still be written).
    assert completed.returncode == 1
    assert not candidate.exists()


# ── Appended: class-body property + method-local DAO accessors ────────────────


def test_class_property_and_local_dao_accessors_resolve(tmp_path):
    """Both remaining accessor styles resolve (closes matrix 14 fully).

    One repository class declares a CLASS-BODY ``private val archiveDao:
    ArchiveDao`` property used by one method and a METHOD-LOCAL
    ``val expenseDao = database.expenseDao()`` alias used by another; two
    legacy entries targeting each method must both resolve exactly.
    """
    repo_source = (
        "package com.example\n"
        "\n"
        "class Repository {\n"
        "    private val archiveDao: ArchiveDao\n"
        "\n"
        "    fun archive(value: Int) {\n"
        "        archiveDao.insert(value)\n"
        "    }\n"
        "\n"
        "    fun save(value: Int) {\n"
        "        val expenseDao = database.expenseDao()\n"
        "        expenseDao.insert(value)\n"
        "    }\n"
        "}\n"
    )
    archive_dao_kt = "app/src/main/java/com/example/ArchiveDao.kt"
    _write_repo(
        tmp_path,
        {
            DAO_KT: EXPENSE_DAO_SOURCE,
            archive_dao_kt: _dao_source("com.example", "ArchiveDao"),
            REPO_KT: repo_source,
        },
    )
    entries = [
        # Each legacy row must authorize the DAO its method actually
        # mutates (rule 5): the archive method's archiveDao mutation is
        # only emitted because this row authorizes ArchiveDao.
        _legacy_entry(REPO_KT, "Repository", "archive", daos=("ArchiveDao",)),
        _legacy_entry(REPO_KT, "Repository", "save"),
    ]
    result = migrate_policy(entries, str(tmp_path))
    assert result.input_count == 2
    assert result.unresolved == ()
    assert len(result.resolved) == 2
    by_method = {row.entry.method: row.entry for row in result.resolved}
    assert set(by_method) == {"archive", "save"}
    # Class-body property accessor: type-derived Room accessor identity.
    assert by_method["archive"].dao_accessor == "archiveDao"
    assert by_method["archive"].dao_fqcn == "com.example.ArchiveDao"
    assert by_method["archive"].operation == "insert"
    # Method-local alias assigned from the database accessor call.
    assert by_method["save"].dao_accessor == "expenseDao"
    assert by_method["save"].dao_fqcn == "com.example.ExpenseDao"
    assert by_method["save"].operation == "insert"


# ── Appended: intent-gate empty-set ``daos`` shapes ───────────────────────────
#
# The authorization-intent cross-check (DAO resolution rule 5) derives the
# expected accessor set ONLY from the string members of a LIST-shaped
# ``daos`` field.  Each test below keeps the repository body a genuine
# ``expenseDao.insert(...)`` mutation — so upstream owner/callable/mutation
# resolution succeeds — and degenerates ONLY the ``daos`` shape, pinning
# that an empty authorized set fails the whole entry closed as exactly one
# MUTATION_PAIR_MISSING row carrying the bounded intent-gate detail.


def test_daos_key_absent_authorizes_nothing(tmp_path):
    # No ``daos`` key at all: the legacy row names no DAO, so even a
    # provably resolvable expenseDao.insert(...) mutation stays
    # unauthorized and nothing may leak into candidates.
    _write_repo(tmp_path, _standard_repo_files())
    entry = _legacy_entry(REPO_KT, "Repository", "save")
    del entry["daos"]
    result = migrate_policy([entry], str(tmp_path))
    assert result.input_count == 1
    assert result.resolved == ()
    assert len(result.unresolved) == 1
    row = result.unresolved[0]
    assert row.status == STATUS_MUTATION_PAIR_MISSING
    assert row.detail == "no mutation matches legacy daos"
    assert row.index == 0
    assert row.legacy_class == "Repository"
    assert row.legacy_method == "save"


def test_daos_non_list_authorizes_nothing(tmp_path):
    # A bare string is not a list: it cannot be projected onto accessors,
    # so the authorized set is empty even though the string names
    # ExpenseDao.  (list("ExpenseDao") char-splitting must never happen.)
    _write_repo(tmp_path, _standard_repo_files())
    entry = _legacy_entry(REPO_KT, "Repository", "save")
    entry["daos"] = "ExpenseDao"
    result = migrate_policy([entry], str(tmp_path))
    assert result.input_count == 1
    assert result.resolved == ()
    assert len(result.unresolved) == 1
    row = result.unresolved[0]
    assert row.status == STATUS_MUTATION_PAIR_MISSING
    assert row.detail == "no mutation matches legacy daos"
    assert row.index == 0
    assert row.legacy_class == "Repository"
    assert row.legacy_method == "save"


def test_daos_non_string_members_authorizes_nothing(tmp_path):
    # Non-string members are ignored (filtered out), so a ``daos`` list
    # with NO string members leaves the authorized set empty.  A mixed
    # list such as ["ExpenseDao", 7] still authorizes its surviving
    # string member — pinned below as the contrast — which is exactly why
    # the pure non-string list is the degenerate empty-set shape.
    _write_repo(tmp_path, _standard_repo_files())
    entry = _legacy_entry(REPO_KT, "Repository", "save", daos=(7,))
    result = migrate_policy([entry], str(tmp_path))
    assert result.input_count == 1
    assert result.resolved == ()
    assert len(result.unresolved) == 1
    row = result.unresolved[0]
    assert row.status == STATUS_MUTATION_PAIR_MISSING
    assert row.detail == "no mutation matches legacy daos"
    assert row.index == 0
    assert row.legacy_class == "Repository"
    assert row.legacy_method == "save"
    # Contrast: ignoring the non-string member leaves ["ExpenseDao"],
    # which DOES authorize the evidence-derived expenseDao.insert(...)
    # mutation — non-string members neither crash nor poison the list.
    mixed = _legacy_entry(REPO_KT, "Repository", "save")
    mixed["daos"] = ["ExpenseDao", 7]
    mixed_result = migrate_policy([mixed], str(tmp_path))
    assert mixed_result.unresolved == ()
    assert len(mixed_result.resolved) == 1
    assert mixed_result.resolved[0].entry.dao_fqcn == (
        "com.example.ExpenseDao"
    )
    assert mixed_result.resolved[0].entry.operation == "insert"


# ── Appended: overload/receiver/hint fail-closed statuses ─────────────────────
#
# Library-level ``migrate_policy`` coverage for the remaining
# ``resolve_callable_for_entry`` outcomes over an ``Item``/``List<Item>``
# overload pair (both bodies perform ``expenseDao.insert(...)``).  The
# fixture declares ``import com.example.Item`` because the shared parser's
# type environment is closed-world per file: without the import the
# declarations themselves would fail as TYPE_UNRESOLVED (PARSER_UNCERTAIN)
# instead of reaching the hint-matching statuses under test here.

from scripts.db_guard.policy_v2_candidate import (  # noqa: E402
    STATUS_CALLABLE_AMBIGUOUS,
    STATUS_CALLABLE_KIND_UNSUPPORTED,
)

ITEM_OVERLOADS_REPO_SOURCE = (
    "package com.example\n"
    "\n"
    "import com.example.Item\n"
    "\n"
    "class Repository {\n"
    "    fun save(item: Item) {\n"
    "        expenseDao.insert(item)\n"
    "    }\n"
    "\n"
    "    fun save(items: List<Item>) {\n"
    "        expenseDao.insert(items)\n"
    "    }\n"
    "}\n"
)

SINGLE_SAVE_ITEM_REPO_SOURCE = (
    "package com.example\n"
    "\n"
    "import com.example.Item\n"
    "\n"
    "class Repository {\n"
    "    fun save(item: Item) {\n"
    "        expenseDao.insert(item)\n"
    "    }\n"
    "}\n"
)


def _assert_single_unresolved_row(result):
    """Assert one input stayed fully unresolved as one bounded debt row."""
    assert result.input_count == 1
    assert result.resolved == ()
    assert len(result.unresolved) == 1
    row = result.unresolved[0]
    assert row.detail == ""
    assert row.index == 0
    assert row.legacy_class == "Repository"
    assert row.legacy_method == "save"
    return row


def test_overloads_without_parameter_hint_stay_ambiguous(tmp_path):
    # Two true same-name overloads and NO parameter hint: nothing may pick
    # first/last, so the entry resolves to NOTHING and surfaces as exactly
    # one closed CALLABLE_AMBIGUOUS debt row.
    _write_repo(
        tmp_path,
        {DAO_KT: EXPENSE_DAO_SOURCE, REPO_KT: ITEM_OVERLOADS_REPO_SOURCE},
    )
    entries = [_legacy_entry(REPO_KT, "Repository", "save", params=())]
    result = migrate_policy(entries, str(tmp_path))
    row = _assert_single_unresolved_row(result)
    assert row.status == STATUS_CALLABLE_AMBIGUOUS
    assert STATUS_CALLABLE_AMBIGUOUS == "CALLABLE_AMBIGUOUS"


def test_overloads_with_non_matching_hint_stay_ambiguous(tmp_path):
    # A Boolean hint matches neither the Item nor the List<Item> overload:
    # zero hint matches may never fall back to first/last selection, so the
    # pair stays fully unresolved as exactly one CALLABLE_AMBIGUOUS row.
    _write_repo(
        tmp_path,
        {DAO_KT: EXPENSE_DAO_SOURCE, REPO_KT: ITEM_OVERLOADS_REPO_SOURCE},
    )
    entries = [
        _legacy_entry(REPO_KT, "Repository", "save", params=("Boolean",))
    ]
    result = migrate_policy(entries, str(tmp_path))
    row = _assert_single_unresolved_row(result)
    assert row.status == STATUS_CALLABLE_AMBIGUOUS
    assert STATUS_CALLABLE_AMBIGUOUS == "CALLABLE_AMBIGUOUS"


def test_wrong_receiver_hint_yields_callable_missing(tmp_path):
    # A single null-receiver fun save(item: Item): a non-null receiver hint
    # cannot fabricate a receiver, so every candidate is filtered away and
    # the row fails closed as CALLABLE_MISSING — never as a defaulted match.
    _write_repo(
        tmp_path,
        {DAO_KT: EXPENSE_DAO_SOURCE, REPO_KT: SINGLE_SAVE_ITEM_REPO_SOURCE},
    )
    entries = [
        _legacy_entry(
            REPO_KT,
            "Repository",
            "save",
            params=("Item",),
            receiver="com.example.Wrong",
        )
    ]
    result = migrate_policy(entries, str(tmp_path))
    row = _assert_single_unresolved_row(result)
    assert row.status == STATUS_CALLABLE_MISSING
    assert STATUS_CALLABLE_MISSING == "CALLABLE_MISSING"


def test_unnormalizable_hint_fails_closed(tmp_path):
    """An un-normalizable parameter hint fails closed as KIND_UNSUPPORTED.

    Verified against ``resolve_callable_for_entry``: hint normalization runs
    BEFORE candidate discovery and maps ``(SignatureError, TypeError)`` to
    ``(None, STATUS_CALLABLE_KIND_UNSUPPORTED)`` — so the constant this
    path truly emits is ``CALLABLE_KIND_UNSUPPORTED``, regardless of
    overload count (pinned here against a single-overload method).
    """
    _write_repo(
        tmp_path,
        {DAO_KT: EXPENSE_DAO_SOURCE, REPO_KT: SINGLE_SAVE_ITEM_REPO_SOURCE},
    )
    entries = [
        _legacy_entry(REPO_KT, "Repository", "save", params=("<", ">"))
    ]
    result = migrate_policy(entries, str(tmp_path))
    row = _assert_single_unresolved_row(result)
    assert row.status == STATUS_CALLABLE_KIND_UNSUPPORTED
    assert STATUS_CALLABLE_KIND_UNSUPPORTED == "CALLABLE_KIND_UNSUPPORTED"
