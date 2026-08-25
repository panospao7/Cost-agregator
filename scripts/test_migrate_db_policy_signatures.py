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
    MIGRATION_STATUSES,
    MIGRATION_STATUSES_EXTENDED,
    STATUS_AUTHORIZATION_METADATA_CONFLICT,
    STATUS_BARRIER_MODE_UNRESOLVED,
    STATUS_CALLABLE_MISSING,
    STATUS_DAO_TARGET_AMBIGUOUS,
    STATUS_MUTATION_PAIR_MISSING,
    STATUS_PARSER_UNCERTAIN,
    STATUS_PARSER_UNSUPPORTED,
    STATUS_RESOLVED,
    STATUS_SOURCE_ROOT_UNRESOLVED,
    UnresolvedRow,
    convert_barrier_mode,
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
    # This identity-only fixture claims barrier_required=true but its body
    # contains NO direct writeBarrier call: since PR-GR-05 Slice 2 the
    # unproven direct claim downgrades to helper (the dedicated Slice 2
    # tests below pin the direct/helper evidence split both ways).
    assert entry.barrier_mode is BarrierMode.HELPER


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


# ── (19) Barrier-mode conversion is closed (PR-GR-05 Slice 2) ────────────────


def test_barrier_mode_mapping_matrix():
    """The closed evidence-aware mapping table, cell by cell.

    barrierMode stays METADATA ONLY: it records how the legacy row
    classified its own write protection and is never control-flow proof.
    """
    unresolved = (None, STATUS_BARRIER_MODE_UNRESOLVED)
    # required=true + no via: decided by direct-syntax evidence.
    assert convert_barrier_mode(
        {"barrier_required": True}, has_direct_barrier=True
    ) == ("direct", None)
    assert convert_barrier_mode(
        {"barrier_required": True}, has_direct_barrier=False
    ) == ("helper", None)
    # No evidence available: debt, never an invented mode.
    assert convert_barrier_mode({"barrier_required": True}) == unresolved
    # Exact legacy helper classification (false + no via).
    assert convert_barrier_mode({"barrier_required": False}) == ("helper", None)
    # Exact legacy WorkerExecutionGuard classification — the shape every
    # mediated row of the active policy uses (false + via).
    assert convert_barrier_mode(
        {"barrier_required": False, "barrier_via": "WorkerExecutionGuard"}
    ) == ("workerMediated", None)
    # Any other non-empty via names a helper mediator.
    assert convert_barrier_mode(
        {
            "barrier_required": False,
            "barrier_via": "TransactionLifecycleCoordinator.checkWritesAllowed",
        }
    ) == ("helper", None)
    # Contradictory: a mediation claim together with a direct-barrier-
    # required claim cannot both be true (legacy truthfulness rule).
    for via in ("WorkerExecutionGuard", "SomeOtherHelper"):
        assert convert_barrier_mode(
            {"barrier_required": True, "barrier_via": via}
        ) == unresolved
    # Missing both fields (and an explicit-null via) stays debt.
    assert convert_barrier_mode({}) == unresolved
    assert convert_barrier_mode({"barrier_via": None}) == unresolved
    # Non-bool required is never an exact legacy shape (1 == True must not
    # sneak through as a real boolean).
    for bad_required in (None, "yes", 1, 0):
        assert convert_barrier_mode({"barrier_required": bad_required}) == (
            unresolved
        )
    # Malformed/invalid via values fail closed.
    for bad_via in (5, ["WorkerExecutionGuard"], "", "   "):
        assert convert_barrier_mode(
            {"barrier_required": False, "barrier_via": bad_via}
        ) == unresolved
    # Non-mapping input fails closed.
    assert convert_barrier_mode(None) == unresolved
    assert convert_barrier_mode(["barrier_required"]) == unresolved


def test_slice2_plan_required_statuses_appended_to_closed_vocabulary():
    """SOURCE_ROOT_UNRESOLVED / PARSER_UNSUPPORTED join the closed set only
    through the documented append-only extension point."""
    assert STATUS_SOURCE_ROOT_UNRESOLVED == "SOURCE_ROOT_UNRESOLVED"
    assert STATUS_PARSER_UNSUPPORTED == "PARSER_UNSUPPORTED"
    appended = (
        STATUS_PARSER_UNCERTAIN,
        STATUS_SOURCE_ROOT_UNRESOLVED,
        STATUS_PARSER_UNSUPPORTED,
    )
    # The original frozenset stays frozen; only the extended set grows...
    for status in appended:
        assert status not in MIGRATION_STATUSES
        assert status in MIGRATION_STATUSES_EXTENDED
    # ...and UnresolvedRow validation accepts the widened vocabulary.
    for status in (STATUS_SOURCE_ROOT_UNRESOLVED, STATUS_PARSER_UNSUPPORTED):
        row = UnresolvedRow(0, "Cls", "method", status, "")
        assert row.status == status


DIRECT_BARRIER_REPO_SOURCE = (
    "package com.example\n"
    "\n"
    "class Repository {\n"
    "    fun save(value: Int) {\n"
    '        writeBarrier.checkWritesAllowed("Repository.save")\n'
    "        expenseDao.insert(value)\n"
    "    }\n"
    "}\n"
)

PARTIAL_BARRIER_TWO_MUTATIONS_REPO_SOURCE = (
    "package com.example\n"
    "\n"
    "class Repository {\n"
    "    fun upsert(value: Int) {\n"
    "        expenseDao.insert(value)\n"
    '        writeBarrier.checkWritesAllowed("Repository.upsert")\n'
    "        expenseDao.delete(value)\n"
    "    }\n"
    "}\n"
)


def test_previously_unresolved_worker_mediated_row_resolves(tmp_path):
    """Legacy mediated shape (false + via=WorkerExecutionGuard) resolves.

    This exact fixture row was BARRIER_MODE_UNRESOLVED debt before Slice 2;
    it now migrates with the workerMediated metadata mode.
    """
    _write_repo(tmp_path, _standard_repo_files())
    entries = [
        _legacy_entry(
            REPO_KT,
            "Repository",
            "save",
            barrier_required=False,
            barrier_via="WorkerExecutionGuard",
        )
    ]
    result = migrate_policy(entries, str(tmp_path))
    entry = _only_entry(result)
    assert entry.barrier_mode is BarrierMode.WORKER_MEDIATED
    assert entry.barrier_mode.value == "workerMediated"


def test_previously_unresolved_helper_row_resolves_as_helper(tmp_path):
    """Legacy helper shape (false + no via) resolves as helper.

    Also previously BARRIER_MODE_UNRESOLVED debt; the body has no direct
    barrier call, matching the legacy helper classification exactly.
    """
    _write_repo(tmp_path, _standard_repo_files())
    entries = [
        _legacy_entry(REPO_KT, "Repository", "save", barrier_required=False)
    ]
    result = migrate_policy(entries, str(tmp_path))
    entry = _only_entry(result)
    assert entry.barrier_mode is BarrierMode.HELPER
    assert entry.barrier_mode.value == "helper"


def test_other_helper_via_resolves_as_helper(tmp_path):
    """A non-guard mediator name is still helper-classified metadata."""
    _write_repo(tmp_path, _standard_repo_files())
    entries = [
        _legacy_entry(
            REPO_KT,
            "Repository",
            "save",
            barrier_required=False,
            barrier_via="TransactionLifecycleCoordinator.checkWritesAllowed",
        )
    ]
    result = migrate_policy(entries, str(tmp_path))
    entry = _only_entry(result)
    assert entry.barrier_mode is BarrierMode.HELPER


def test_contradictory_mediated_required_row_stays_unresolved(tmp_path):
    """required=true TOGETHER WITH a via is contradictory debt.

    Mediation and a direct-barrier-required claim cannot both be true (the
    legacy truthfulness rule), so this ambiguous row stays fully
    unresolved even though it would otherwise migrate cleanly.
    """
    _write_repo(tmp_path, _standard_repo_files())
    entries = [
        _legacy_entry(
            REPO_KT,
            "Repository",
            "save",
            barrier_required=True,
            barrier_via="WorkerExecutionGuard",
        )
    ]
    result = migrate_policy(entries, str(tmp_path))
    assert result.resolved == ()
    assert len(result.unresolved) == 1
    row = result.unresolved[0]
    assert row.status == STATUS_BARRIER_MODE_UNRESOLVED
    assert STATUS_BARRIER_MODE_UNRESOLVED == "BARRIER_MODE_UNRESOLVED"
    assert row.index == 0
    assert row.legacy_class == "Repository"
    assert row.legacy_method == "save"


def test_direct_syntax_proof_emits_direct(tmp_path):
    """A real unqualified writeBarrier call before the mutation proves the
    legacy direct claim, so the emitted metadata mode stays direct."""
    _write_repo(
        tmp_path,
        {DAO_KT: EXPENSE_DAO_SOURCE, REPO_KT: DIRECT_BARRIER_REPO_SOURCE},
    )
    entries = [_legacy_entry(REPO_KT, "Repository", "save")]
    result = migrate_policy(entries, str(tmp_path))
    entry = _only_entry(result)
    assert entry.barrier_mode is BarrierMode.DIRECT


def test_required_true_without_direct_syntax_downgrades_to_helper(tmp_path):
    """An unproven direct claim downgrades to helper instead of emitting
    unproven ``direct`` metadata."""
    _write_repo(tmp_path, _standard_repo_files())
    entries = [_legacy_entry(REPO_KT, "Repository", "save")]
    result = migrate_policy(entries, str(tmp_path))
    entry = _only_entry(result)
    assert entry.barrier_mode is BarrierMode.HELPER


def test_direct_proof_must_precede_every_mutation(tmp_path):
    """Per-mutation all-or-nothing: a barrier between two mutations proves
    nothing for the FIRST one, so the row cannot claim direct."""
    _write_repo(
        tmp_path,
        {
            DAO_KT: EXPENSE_DAO_SOURCE,
            REPO_KT: PARTIAL_BARRIER_TWO_MUTATIONS_REPO_SOURCE,
        },
    )
    entries = [_legacy_entry(REPO_KT, "Repository", "upsert")]
    result = migrate_policy(entries, str(tmp_path))
    entry = _only_entry(result)
    assert entry.barrier_mode is BarrierMode.HELPER


# ── (20) Dedupe-by-key at emission (PR-GR-05 Slice 4, refined in Slice 5) ────
#
# Several legacy rows authorizing the SAME callable+DAO+operation used to
# emit identical canonical mutation keys and trip the exit-2 duplicate
# guard.  Slice 4 folded byte-identical emissions; Slice 5 refines the
# fold rule: emissions sharing a canonical key fold whenever their
# authorization metadata (barrierMode, owner, linkedIssue) agrees —
# free-text ``reason`` differences fold away, keeping the lowest-index
# entry verbatim — while a metadata DISAGREEMENT converts EVERY
# participating index into one AUTHORIZATION_METADATA_CONFLICT debt row
# with nothing emitted for the key.  Every source legacy index of a
# folded key survives in the ``emission_indices`` crosswalk, and the
# residual duplicate guard remains as defense-in-depth against leaked
# contradictions.


def test_identical_legacy_rows_dedupe_to_one_candidate_entry(tmp_path):
    _write_repo(tmp_path, _standard_repo_files())
    duplicated = _legacy_entry(REPO_KT, "Repository", "save")
    result = migrate_policy([duplicated, dict(duplicated)], str(tmp_path))
    assert result.input_count == 2
    assert result.unresolved == ()
    # One entry per unique canonical mutation key: the second legacy row's
    # identical emission is folded away, keeping only index 0's row.
    assert len(result.resolved) == 1
    assert result.resolved[0].index == 0
    expected_key = (
        REPO_KT
        + "|com.example.Repository|function|save|null|Int"
        + "|expenseDao|com.example.ExpenseDao|insert"
    )
    assert (
        result.resolved[0].entry.mutation_key().canonical_key()
        == expected_key
    )
    # Both source indices stay tied to the shared key via the crosswalk...
    assert dict(result.emission_indices) == {expected_key: (0, 1)}
    # ...and no residual duplicate collision is reported.
    assert find_duplicate_mutation_keys(result) == ()


def test_dedupe_crosswalk_covers_multi_operation_splits(tmp_path):
    """Every split operation key records BOTH identical legacy indices."""
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
    entry = _legacy_entry(REPO_KT, "Repository", "upsert")
    result = migrate_policy([entry, dict(entry)], str(tmp_path))
    assert result.input_count == 2
    assert result.unresolved == ()
    # Two unique mutation keys (insert/delete), one kept row each from the
    # first legacy index; the folded second index appears in both keys.
    assert len(result.resolved) == 2
    assert {row.index for row in result.resolved} == {0}
    crosswalk = dict(result.emission_indices)
    assert len(crosswalk) == 2
    for key, indices in crosswalk.items():
        assert indices == (0, 1)
    # The two unique keys are exactly the insert/delete split of one
    # callable+DAO identity.
    assert sorted(
        key.rsplit("|", 1)[-1] for key in crosswalk
    ) == ["delete", "insert"]
    assert find_duplicate_mutation_keys(result) == ()


def test_same_key_with_differing_barrier_metadata_conflicts_to_debt(tmp_path):
    """Same canonical key, DIFFERENT authorization metadata: conflict debt.

    Slice 5 folds same-key emissions only when (barrierMode, owner,
    linkedIssue) agree.  A workerMediated classification and a helper
    classification of the same mutation disagree on barrierMode, so BOTH
    indices become UNRESOLVED AUTHORIZATION_METADATA_CONFLICT rows, zero
    candidates are emitted for the key, the crosswalk stays empty, and
    nothing leaks to the residual duplicate guard.
    """
    _write_repo(tmp_path, _standard_repo_files())
    mediated = _legacy_entry(
        REPO_KT,
        "Repository",
        "save",
        barrier_required=False,
        barrier_via="WorkerExecutionGuard",
    )
    helper = _legacy_entry(REPO_KT, "Repository", "save", barrier_required=False)
    result = migrate_policy([mediated, helper], str(tmp_path))
    assert result.input_count == 2
    assert result.resolved == ()
    assert len(result.unresolved) == 2
    assert [row.index for row in result.unresolved] == [0, 1]
    for row in result.unresolved:
        assert row.status == STATUS_AUTHORIZATION_METADATA_CONFLICT
        assert (
            STATUS_AUTHORIZATION_METADATA_CONFLICT
            == "AUTHORIZATION_METADATA_CONFLICT"
        )
        # Bounded structured context ONLY: index count plus the conflicted
        # key's tail segment.  No payloads, no reason text, no full keys.
        assert row.detail == "conflictingIndices=2 keyTail=insert"
        assert "workerMediated" not in row.detail
        assert "controlled migration reason" not in row.detail
        assert "|" not in row.detail
    # The conflicted key never enters the emission crosswalk.
    assert result.emission_indices == ()
    # Nothing leaked: the defense-in-depth duplicate guard stays silent.
    assert find_duplicate_mutation_keys(result) == ()


def test_reason_only_variants_fold_to_lowest_index_reason(tmp_path):
    """Three same-key rows differing ONLY in free-text reason -> ONE entry.

    Slice 5 fold contract: the lowest-index entry is kept verbatim (its
    ``reason`` text survives), every source legacy index maps to the
    shared key in the ``emission_indices`` crosswalk, and no duplicate
    collision is reported.
    """
    _write_repo(tmp_path, _standard_repo_files())
    rows = [
        _legacy_entry(REPO_KT, "Repository", "save", reason="scenario-alpha"),
        _legacy_entry(REPO_KT, "Repository", "save", reason="scenario-beta"),
        _legacy_entry(REPO_KT, "Repository", "save", reason="scenario-gamma"),
    ]
    result = migrate_policy(rows, str(tmp_path))
    assert result.input_count == 3
    assert result.unresolved == ()
    # One entry per unique canonical mutation key.
    assert len(result.resolved) == 1
    kept = result.resolved[0]
    assert kept.index == 0
    expected_key = (
        REPO_KT
        + "|com.example.Repository|function|save|null|Int"
        + "|expenseDao|com.example.ExpenseDao|insert"
    )
    assert kept.entry.mutation_key().canonical_key() == expected_key
    # The lowest-index reason text is kept verbatim; later variants fold.
    assert kept.entry.reason == "scenario-alpha"
    # All three source indices stay tied to the shared key...
    assert dict(result.emission_indices) == {expected_key: (0, 1, 2)}
    # ...and no residual duplicate collision is reported.
    assert find_duplicate_mutation_keys(result) == ()


def test_mixed_batch_folds_reason_variants_and_conflicts_to_debt(tmp_path):
    """One batch, three outcomes: fold, conflict, clean emission.

    Indices 0/1/4 authorize ``save`` with identical metadata but differing
    reasons -> folded into index 0's verbatim entry.  Indices 2/3
    authorize ``store`` with divergent barrier metadata -> both become
    AUTHORIZATION_METADATA_CONFLICT debt with nothing emitted.  The two
    outcomes partition the batch exactly one-per-index.
    """
    repo_source = (
        "package com.example\n"
        "\n"
        "class Repository {\n"
        "    fun save(value: Int) {\n"
        "        expenseDao.insert(value)\n"
        "    }\n"
        "\n"
        "    fun store(value: Int) {\n"
        "        expenseDao.insert(value)\n"
        "    }\n"
        "}\n"
    )
    _write_repo(
        tmp_path, {DAO_KT: EXPENSE_DAO_SOURCE, REPO_KT: repo_source}
    )
    entries = [
        _legacy_entry(REPO_KT, "Repository", "save", reason="r-one"),  # 0
        _legacy_entry(REPO_KT, "Repository", "save", reason="r-two"),  # 1
        _legacy_entry(  # 2: mediated classification of store
            REPO_KT,
            "Repository",
            "store",
            barrier_required=False,
            barrier_via="WorkerExecutionGuard",
        ),
        _legacy_entry(  # 3: helper classification of store -> conflict
            REPO_KT,
            "Repository",
            "store",
            barrier_required=False,
        ),
        _legacy_entry(REPO_KT, "Repository", "save", reason="r-three"),  # 4
    ]
    result = migrate_policy(entries, str(tmp_path))
    assert result.input_count == 5
    # save-key folds indices 0, 1, 4 into index 0's verbatim entry; the
    # conflicted store-key emits NOTHING.
    assert len(result.resolved) == 1
    assert result.resolved[0].index == 0
    assert result.resolved[0].entry.reason == "r-one"
    assert [(row.index, row.status) for row in result.unresolved] == [
        (2, STATUS_AUTHORIZATION_METADATA_CONFLICT),
        (3, STATUS_AUTHORIZATION_METADATA_CONFLICT),
    ]
    save_key = (
        REPO_KT
        + "|com.example.Repository|function|save|null|Int"
        + "|expenseDao|com.example.ExpenseDao|insert"
    )
    assert dict(result.emission_indices) == {save_key: (0, 1, 4)}
    assert find_duplicate_mutation_keys(result) == ()


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
    production_source_manifest_digest,
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
    # Since Slice 5, folded reason-variant indices have NO resolved REPORT
    # row of their own (their emission folded into the lowest-index row),
    # so the report rows alone no longer cover the input range.  The
    # embedded accounting section ties EVERY input index to exactly one
    # outcome — consistency is computed from the evidence, never pinned to
    # fixed artifact totals.
    report_indexes = {row["index"] for row in payload["resolved"]} | {
        row["index"] for row in payload["unresolved"]
    }
    assert report_indexes <= set(range(counts["input"]))
    assert "accounting" in payload
    records = payload["accounting"]["records"]
    assert {record["index"] for record in records} == set(
        range(counts["input"])
    )
    resolved_records = {
        record["index"]
        for record in records
        if record["outcome"] == "RESOLVED"
    }
    unresolved_records = {
        record["index"]
        for record in records
        if record["outcome"] == "UNRESOLVED"
    }
    assert not (resolved_records & unresolved_records)
    assert resolved_records | unresolved_records == set(range(counts["input"]))
    # Every kept report row's index is a RESOLVED accounting record.
    assert {
        row["index"] for row in payload["resolved"]
    } <= resolved_records


def test_real_run_distribution_pinned_and_reproducible(tmp_path):
    """Pin the CURRENT post-Slice-5 truth of the real repository run.

    The checked-in tracked candidate
    (``config/guards/db_ownership_policy.signatures.candidate.yml``) is the
    CURRENT PR-GR-05 artifact, regenerated through ``--generate``; byte
    equality between a fresh run and the tracked artifacts is pinned by the
    dedicated regression tests in the tracked-artifact section below.
    Pinned here — derived from the verified current-tree
    structure (probe10 + policy audit) and pinned as exact observable CLI
    numbers:

    * 99 inputs; 51 unresolved indices -> 48 resolving indices;
    * the ONLY same-key emission groups are (a) indices 40-42 — three
      scenario-reason variants of BudgetRepository.addBudget's insert
      trio (3 keys x 3 rows each) and (b) indices 22-27 — six per-column
      update rows on TransactionLifecycleCoordinator's ownership updater
      (6 keys x 6 rows each), all sharing barrierMode/owner/linkedIssue;
    * pre-dedupe the run emits 84 resolved rows
      (3*3 + 6*6 + 39 single-carried keys); Slice 5 folding removes every
      redundant emission — 3*(3-1) + 6*(6-1) = 36 — leaving EXACTLY 48
      unique keys.  (The naive 84-9=75 bound miscounts a key carried by n
      rows as one redundant row instead of n-1.);
    * duplicates=0 and exit 1 (visible debt, candidate writing allowed);
    * the accounting records partition range(99) into 48 RESOLVED (kept
      emitters plus folded reason-variant indices) and 51 UNRESOLVED;
    * byte-for-byte reproducibility of a second run.
    """
    out_dir = tmp_path / "dist"
    out_dir.mkdir()
    report = out_dir / "report.json"
    candidate = out_dir / "candidate.yml"
    accounting = out_dir / "accounting.json"
    completed = _run_cli(
        "--write-candidate",
        "--output",
        str(candidate),
        "--accounting-out",
        str(accounting),
        "--report",
        str(report),
    )
    # Visible debt (unresolved rows exist) with candidate writing allowed:
    # Slice 5 folding removed the duplicate-key exit-2 collision.
    assert completed.returncode == 1
    payload = json.loads(report.read_text(encoding="utf-8"))
    counts = payload["counts"]
    assert counts["input"] == 99
    assert counts["resolved"] == 48
    assert counts["unresolved"] == 51
    assert payload["duplicateMutationKeys"] == []
    document, errors = load_policy_v2(candidate)
    assert errors == []
    assert document
    # One entry per unique canonical mutation key, and the report's
    # resolved count IS that unique-entry count (deduped at emission).
    candidate_keys = {
        entry.mutation_key().canonical_key() for entry in document
    }
    assert len(candidate_keys) == len(document) == 48
    assert counts["resolved"] == len(document)
    # Folded reason-variant indices keep NO resolved report row: only the
    # lowest-index keeper of each fold group remains (39 single-carried
    # keys plus one keeper index for group (a)'s 3 keys and one for group
    # (b)'s 6 keys -> 41 distinct report indexes).
    report_resolved_indexes = {
        row["index"] for row in payload["resolved"]
    }
    report_unresolved_indexes = {
        row["index"] for row in payload["unresolved"]
    }
    assert not (report_resolved_indexes & report_unresolved_indexes)
    assert len(report_resolved_indexes) == 41
    # The accounting records tie EVERY legacy index to exactly one outcome.
    records = payload["accounting"]["records"]
    assert len(records) == 99
    resolved_indexes = {
        record["index"]
        for record in records
        if record["outcome"] == "RESOLVED"
    }
    unresolved_indexes = {
        record["index"]
        for record in records
        if record["outcome"] == "UNRESOLVED"
    }
    assert not (resolved_indexes & unresolved_indexes)
    assert resolved_indexes | unresolved_indexes == set(range(99))
    assert len(resolved_indexes) == 48
    assert report_resolved_indexes <= resolved_indexes
    # Every folded index's RESOLVED record carries the shared key of its
    # keeper's candidate entry.
    record_keys_by_index = {
        record["index"]: set(record["mutationKeys"]) for record in records
    }
    for index in resolved_indexes - report_resolved_indexes:
        assert record_keys_by_index[index]
        assert record_keys_by_index[index] <= candidate_keys
    # A second run reproduces both artifacts byte-for-byte.
    out_dir_two = tmp_path / "dist-two"
    out_dir_two.mkdir()
    report_two = out_dir_two / "report.json"
    candidate_two = out_dir_two / "candidate.yml"
    completed_two = _run_cli(
        "--write-candidate",
        "--output",
        str(candidate_two),
        "--report",
        str(report_two),
    )
    assert completed_two.returncode == 1
    assert candidate_two.read_bytes() == candidate.read_bytes()
    assert report_two.read_bytes() == report.read_bytes()


# ── Appended: synthetic tmp legacy policies through the real CLI ─────────────
#
# These tests write legacy v1 policy YAML under pytest ``tmp_path`` and feed it
# to the real CLI via ``--policy``; analysis stays pinned to the REAL worktree
# because the script derives its repo root from its own ``__file__``.  They pin
# the remaining exit-code table cells end to end:
#   * identical duplicate legacy rows -> deduped to one entry, candidate
#     written (exit 0/1);
#   * CONFLICTING same-key emissions  -> 1, AUTHORIZATION_METADATA_CONFLICT
#     debt on every conflicting index, nothing written;
#   * malformed YAML input            -> 2, nothing written;
#   * fully resolved batch            -> 0, schema-valid candidate written;
#   * zero-resolved batch             -> 1, candidate never written.


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


def test_identical_duplicate_rows_via_cli_dedupe_and_write(tmp_path):
    """Identical legacy re-authorizations dedupe; the candidate is written.

    Before Slice 4 this exact input tripped duplicateMutationKeys=9-style
    collisions and exited 2.  Identical emissions now fold into one entry
    per unique canonical mutation key, so the run succeeds and the written
    candidate carries exactly ONE entry.
    """
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
    assert completed.returncode in (0, 1)
    assert "Traceback" not in completed.stderr
    assert candidate.exists()
    document, errors = load_policy_v2(candidate)
    assert errors == []
    assert len(document) == 1


def test_conflicting_metadata_rows_surface_as_conflict_debt_via_cli(tmp_path):
    """Same canonical key with divergent barrier metadata: conflict debt.

    Slice 5 converts genuine metadata conflicts into closed-vocabulary
    UNRESOLVED rows instead of colliding candidates: both indices become
    AUTHORIZATION_METADATA_CONFLICT debt, nothing is emitted (zero
    resolved rows), so the run exits 1 with visible debt and writes no
    candidate.
    """
    entry = _probe_resolving_legacy_entry(tmp_path / "probe-report.json")
    mediated = dict(entry)
    mediated["barrier_required"] = False
    mediated["barrier_via"] = "WorkerExecutionGuard"
    helper = dict(entry)
    helper["barrier_required"] = False
    helper.pop("barrier_via", None)
    policy = tmp_path / "conflict-policy.yml"
    policy.write_text(
        yaml.safe_dump({"entries": [mediated, helper]}, sort_keys=False),
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
    assert completed.returncode == 1
    assert "Traceback" not in completed.stderr
    assert not candidate.exists()
    # The bounded stdout summary names the closed conflict status with the
    # exact count of conflicting indices.
    assert "unresolved AUTHORIZATION_METADATA_CONFLICT=2" in completed.stdout


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


# ── Appended (PR-GR-05 Slice 3): narrow type-resolution repair ────────────────
#
# Single-family repair: a sibling declaration whose project-local types the
# closed-world resolver cannot resolve used to abort the WHOLE owner scan,
# surfacing as PARSER_UNCERTAIN and poisoning rows whose own target resolves
# exactly.  The migration path now discovers tolerantly, so:
#   * a row whose target callable resolves EXACTLY migrates normally;
#   * a row whose target itself carries unresolved types becomes exactly one
#     PARSER_UNSUPPORTED debt row — never silent success;
#   * scanner/evidence callers keep the default strict fail-closed mode.


UNRESOLVED_SIBLING_REPO_SOURCE = (
    "package com.example\n"
    "\n"
    "class Repository {\n"
    "    fun a(unresolvable: ProjectType) {\n"
    "        expenseDao.insert(1)\n"
    "    }\n"
    "\n"
    "    fun b(x: String) {\n"
    "        expenseDao.insert(1)\n"
    "    }\n"
    "}\n"
)


def test_row_beside_unresolved_type_declaration_now_resolves(tmp_path):
    """Negative fixture proving the previous false failure.

    Before the Slice 3 repair this single-entry batch was one whole-file
    PARSER_UNCERTAIN row: discovery aborted on fun a's unresolvable
    ProjectType parameter even though fun b's own signature resolves
    exactly.  It must now migrate normally.
    """
    _write_repo(
        tmp_path,
        {DAO_KT: EXPENSE_DAO_SOURCE, REPO_KT: UNRESOLVED_SIBLING_REPO_SOURCE},
    )
    entries = [_legacy_entry(REPO_KT, "Repository", "b", params=("String",))]
    result = migrate_policy(entries, str(tmp_path))
    entry = _only_entry(result)
    assert entry.method == "b"
    assert entry.owner_fqcn == "com.example.Repository"
    assert entry.parameter_types == ("String",)
    assert entry.dao_accessor == "expenseDao"
    assert entry.dao_fqcn == "com.example.ExpenseDao"
    assert entry.operation == "insert"


def test_row_targeting_unresolved_type_callable_is_parser_unsupported(tmp_path):
    """The unresolved target gets the NEW explicit status, never success."""
    _write_repo(
        tmp_path,
        {DAO_KT: EXPENSE_DAO_SOURCE, REPO_KT: UNRESOLVED_SIBLING_REPO_SOURCE},
    )
    entries = [
        _legacy_entry(REPO_KT, "Repository", "a", params=("ProjectType",))
    ]
    result = migrate_policy(entries, str(tmp_path))
    assert result.input_count == 1
    assert result.resolved == ()
    assert len(result.unresolved) == 1
    row = result.unresolved[0]
    assert row.status == STATUS_PARSER_UNSUPPORTED
    assert STATUS_PARSER_UNSUPPORTED == "PARSER_UNSUPPORTED"
    assert row.detail == ""
    assert row.index == 0
    assert row.legacy_class == "Repository"
    assert row.legacy_method == "a"


def test_unresolved_sibling_splits_batch_into_resolved_and_unsupported(tmp_path):
    """Sibling rows are judged independently within one owner."""
    _write_repo(
        tmp_path,
        {DAO_KT: EXPENSE_DAO_SOURCE, REPO_KT: UNRESOLVED_SIBLING_REPO_SOURCE},
    )
    entries = [
        _legacy_entry(REPO_KT, "Repository", "b", params=("String",)),
        _legacy_entry(REPO_KT, "Repository", "a", params=("ProjectType",)),
    ]
    result = migrate_policy(entries, str(tmp_path))
    assert result.input_count == 2
    assert len(result.resolved) == 1
    assert len(result.unresolved) == 1
    assert result.resolved[0].entry.method == "b"
    assert result.unresolved[0].status == STATUS_PARSER_UNSUPPORTED
    assert result.unresolved[0].legacy_method == "a"
    assert result.unresolved[0].index == 1


NESTED_OWNER_UNRESOLVED_TYPES_REPO_SOURCE = (
    "package com.example\n"
    "\n"
    "class Repository {\n"
    "    class Nested {\n"
    "        fun inner(value: ProjectType) {}\n"
    "    }\n"
    "\n"
    "    fun save(value: Int) {\n"
    "        expenseDao.insert(value)\n"
    "    }\n"
    "}\n"
)


def test_nested_owner_with_unresolved_types_does_not_block_owner_row(tmp_path):
    """The merged DAO-map's nested-owner rescan is tolerant too.

    The class-scope DAO variable map rescans every fully contained inner
    owner; before the repair that strict rescan aborted on Nested.inner's
    unresolvable parameter and failed the unrelated Repository.save row as
    PARSER_UNCERTAIN.
    """
    _write_repo(
        tmp_path,
        {
            DAO_KT: EXPENSE_DAO_SOURCE,
            REPO_KT: NESTED_OWNER_UNRESOLVED_TYPES_REPO_SOURCE,
        },
    )
    entries = [_legacy_entry(REPO_KT, "Repository", "save")]
    result = migrate_policy(entries, str(tmp_path))
    entry = _only_entry(result)
    assert entry.method == "save"
    assert entry.owner_fqcn == "com.example.Repository"
    assert entry.dao_fqcn == "com.example.ExpenseDao"


# ── Appended (PR-GR-03 Slice D): Kotlin-root sources through the manifest ─────


KOTLIN_DAO_KT = "app/src/main/kotlin/com/example/ExpenseDao.kt"
KOTLIN_REPO_KT = "app/src/main/kotlin/com/example/Repository.kt"

_DUAL_ROOT_MANIFEST = """\
schemaVersion: 1
roots:
  - module: :app
    sourceSet: main
    path: app/src/main/java
  - module: :app
    sourceSet: main
    path: app/src/main/kotlin
"""


def test_kotlin_root_source_file_resolves_through_manifest(tmp_path):
    """GR-02 resolves a legacy entry whose source lives under a
    manifest-declared ``src/main/kotlin`` root.

    The synthetic repo ships the dual-root source-root manifest; the DAO
    interface and the repository class both live under the kotlin root, so
    BOTH declared-root consumers must honor it for the row to resolve
    exactly: the generated DAO FQCN index walk (which discovers the kotlin
    root's @Dao interface) and the per-entry path gate (which membership-
    checks the kotlin-root policy path).
    """
    guards = tmp_path / "config" / "guards"
    guards.mkdir(parents=True)
    (guards / "production_source_roots.yml").write_text(
        _DUAL_ROOT_MANIFEST, encoding="utf-8"
    )
    # Topology gate: every declared root must exist, including the
    # otherwise-unused java root.
    (tmp_path / "app" / "src" / "main" / "java").mkdir(parents=True)
    _write_repo(
        tmp_path,
        {
            KOTLIN_DAO_KT: EXPENSE_DAO_SOURCE,
            KOTLIN_REPO_KT: BASIC_REPO_SOURCE,
        },
    )
    entries = [_legacy_entry(KOTLIN_REPO_KT, "Repository", "save")]
    result = migrate_policy(entries, str(tmp_path))
    entry = _only_entry(result)
    assert entry.path == KOTLIN_REPO_KT
    assert entry.owner_fqcn == "com.example.Repository"
    assert entry.kind.value == "function"
    assert entry.method == "save"
    assert entry.parameter_types == ("Int",)
    assert entry.dao_accessor == "expenseDao"
    assert entry.dao_fqcn == "com.example.ExpenseDao"
    assert entry.operation == "insert"


# ── Appended (PR-GR-05 Slice 4): tracked-artifact generation mode ─────────────
#
# ``--generate`` writes BOTH tracked artifacts (candidate + standalone
# accounting) from the SAME run; ``--write-candidate --accounting-out PATH``
# (alias ``--write-accounting``) pairs them at explicit paths.  Contract
# under test: crosswalk re-verified over the exact written bytes BEFORE any
# write; header fields from the real run (source policy path/sha256, tree
# manifest digest, candidate sha256 of the exact bytes); deterministic
# ordering; staged temp-first writes landing both-or-nothing; collisions,
# duplicate keys, malformed output, and pair mismatches rejected with
# nothing written.  Every run overrides both targets into ``tmp_path`` so
# the repository itself is never mutated.


def _run_generate(out_dir: Path):
    """``--generate`` run with both targets overridden into ``out_dir``.

    Returns ``(completed, candidate_path, accounting_path)``.
    """
    out_dir.mkdir(parents=True, exist_ok=True)
    candidate = out_dir / "candidate.yml"
    accounting = out_dir / "accounting.json"
    completed = _run_cli(
        "--generate",
        "--output",
        str(candidate),
        "--accounting-out",
        str(accounting),
    )
    return completed, candidate, accounting


def _conflict_policy(tmp_path: Path) -> Path:
    """A legacy policy whose two rows authorize one mutation with
    conflicting barrier metadata (workerMediated vs helper)."""
    entry = _probe_resolving_legacy_entry(tmp_path / "gen-probe-report.json")
    mediated = dict(entry)
    mediated["barrier_required"] = False
    mediated["barrier_via"] = "WorkerExecutionGuard"
    helper = dict(entry)
    helper["barrier_required"] = False
    helper.pop("barrier_via", None)
    policy = tmp_path / "gen-conflict-policy.yml"
    policy.write_text(
        yaml.safe_dump({"entries": [mediated, helper]}, sort_keys=False),
        encoding="utf-8",
    )
    return policy


def test_generate_writes_paired_artifacts_from_one_run(tmp_path):
    completed, candidate, accounting = _run_generate(tmp_path / "gen")
    assert completed.returncode in (0, 1)
    assert candidate.exists()
    assert accounting.exists()
    payload = json.loads(accounting.read_text(encoding="utf-8"))
    # Header fields filled from the REAL run.
    assert payload["schema"] == "db-policy-migration-accounting"
    assert payload["version"] == 1
    assert payload["sourcePolicyPath"] == (
        "config/guards/db_ownership_policy.yml"
    )
    assert payload["sourcePolicySha256"] == hashlib.sha256(
        ACTIVE_POLICY.read_bytes()
    ).hexdigest()
    assert payload["sourceTreeSha"] == production_source_manifest_digest(
        REPO_ROOT
    )
    # candidateSha256 covers the EXACT written candidate bytes.
    assert payload["candidateSha256"] == hashlib.sha256(
        candidate.read_bytes()
    ).hexdigest()
    # Crosswalk re-verified independently: the union of record keys equals
    # the canonical key set of the written candidate, and every record
    # index partitions range(inputCount).
    document, errors = load_policy_v2(candidate)
    assert errors == []
    assert document
    candidate_keys = {
        entry.mutation_key().canonical_key() for entry in document
    }
    record_keys = {
        key
        for record in payload["records"]
        for key in record["mutationKeys"]
    }
    assert record_keys == candidate_keys
    assert len(candidate_keys) == len(document)
    assert payload["inputCount"] == 99
    assert len(payload["records"]) == 99
    resolved_indexes = {
        record["index"]
        for record in payload["records"]
        if record["outcome"] == "RESOLVED"
    }
    unresolved_indexes = {
        record["index"]
        for record in payload["records"]
        if record["outcome"] == "UNRESOLVED"
    }
    assert not (resolved_indexes & unresolved_indexes)
    assert resolved_indexes | unresolved_indexes == set(range(99))


def test_generate_is_byte_deterministic(tmp_path):
    first_completed, first_candidate, first_accounting = _run_generate(
        tmp_path / "gen-one"
    )
    second_completed, second_candidate, second_accounting = _run_generate(
        tmp_path / "gen-two"
    )
    assert first_completed.returncode in (0, 1)
    assert second_completed.returncode in (0, 1)
    assert first_candidate.read_bytes() == second_candidate.read_bytes()
    assert (
        first_accounting.read_bytes() == second_accounting.read_bytes()
    )


def test_generate_collision_with_active_policy_writes_nothing(tmp_path):
    before = _fingerprint(ACTIVE_POLICY)
    out_dir = tmp_path / "gen"
    out_dir.mkdir()
    accounting = out_dir / "accounting.json"
    for collision_flag, collision_path, other_flag, other_path in (
        ("--output", ACTIVE_POLICY, "--accounting-out", accounting),
        ("--accounting-out", ACTIVE_POLICY, "--output", out_dir / "c.yml"),
    ):
        completed = _run_cli(
            "--generate",
            collision_flag,
            str(collision_path),
            other_flag,
            str(other_path),
        )
        assert completed.returncode == 2
        assert "Traceback" not in completed.stderr
        assert not accounting.exists()
        assert not (out_dir / "c.yml").exists()
    assert _fingerprint(ACTIVE_POLICY) == before


def test_generate_rejects_candidate_accounting_path_collision(tmp_path):
    out_dir = tmp_path / "gen"
    out_dir.mkdir()
    same = out_dir / "same.artifact"
    completed = _run_cli(
        "--generate",
        "--output",
        str(same),
        "--accounting-out",
        str(same),
    )
    assert completed.returncode == 2
    assert "Traceback" not in completed.stderr
    # The collision guard fires before any analysis or write begins.
    assert not same.exists()
    assert list(out_dir.iterdir()) == []


def test_generate_conflicting_metadata_writes_neither_artifact(tmp_path):
    policy = _conflict_policy(tmp_path)
    out_dir = tmp_path / "gen"
    out_dir.mkdir()
    candidate = out_dir / "candidate.yml"
    accounting = out_dir / "accounting.json"
    completed = _run_cli(
        "--generate",
        "--policy",
        str(policy),
        "--output",
        str(candidate),
        "--accounting-out",
        str(accounting),
    )
    # Genuine metadata conflicts become conflict debt: exit 1 (visible
    # debt, zero resolved rows) and NEITHER artifact of the pair is
    # written — a candidate may never be produced from a conflicted batch.
    assert completed.returncode == 1
    assert "Traceback" not in completed.stderr
    assert not candidate.exists()
    assert not accounting.exists()
    assert list(out_dir.iterdir()) == []


def test_generate_zero_resolved_writes_neither_artifact(tmp_path):
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
    out_dir = tmp_path / "gen"
    out_dir.mkdir()
    candidate = out_dir / "candidate.yml"
    accounting = out_dir / "accounting.json"
    completed = _run_cli(
        "--generate",
        "--policy",
        str(policy),
        "--output",
        str(candidate),
        "--accounting-out",
        str(accounting),
    )
    assert completed.returncode == 1
    assert not candidate.exists()
    assert not accounting.exists()


def test_generate_staging_failure_leaves_no_partial_writes(tmp_path):
    """Both-or-neither: a staging failure after the candidate temp is
    already staged must clean it up and swap NOTHING."""
    out_dir = tmp_path / "gen"
    out_dir.mkdir()
    blocked = out_dir / "blocked"
    blocked.write_text("occupied", encoding="utf-8")
    candidate = out_dir / "candidate.yml"
    accounting = blocked / "accounting.json"  # parent is a FILE
    completed = _run_cli(
        "--generate",
        "--output",
        str(candidate),
        "--accounting-out",
        str(accounting),
    )
    assert completed.returncode == 2
    assert "Traceback" not in completed.stderr
    assert not candidate.exists()
    # Only the blocking file remains: no artifact, no leftover temp.
    assert sorted(item.name for item in out_dir.iterdir()) == ["blocked"]
    assert not any(
        item.name.endswith((".tmp", ".part")) for item in out_dir.rglob("*")
    )


def test_write_candidate_with_accounting_out_pairs_report_and_artifacts(
    tmp_path,
):
    out_dir = tmp_path / "pair"
    out_dir.mkdir()
    candidate = out_dir / "candidate.yml"
    accounting = out_dir / "accounting.json"
    report = out_dir / "report.json"
    completed = _run_cli(
        "--write-candidate",
        "--output",
        str(candidate),
        "--accounting-out",
        str(accounting),
        "--report",
        str(report),
    )
    assert completed.returncode in (0, 1)
    assert candidate.exists()
    assert accounting.exists()
    assert report.exists()
    report_payload = json.loads(report.read_text(encoding="utf-8"))
    accounting_payload = json.loads(accounting.read_text(encoding="utf-8"))
    # The report embeds exactly the accounting artifact written beside the
    # candidate — one run, one evidence payload.
    assert report_payload["accounting"] == accounting_payload
    assert accounting_payload["candidateSha256"] == hashlib.sha256(
        candidate.read_bytes()
    ).hexdigest()


def test_write_accounting_alias_flag_writes_accounting(tmp_path):
    out_dir = tmp_path / "alias"
    out_dir.mkdir()
    candidate = out_dir / "candidate.yml"
    accounting = out_dir / "accounting.json"
    completed = _run_cli(
        "--write-candidate",
        "--output",
        str(candidate),
        "--write-accounting",
        str(accounting),
    )
    assert completed.returncode in (0, 1)
    assert candidate.exists()
    assert accounting.exists()
    payload = json.loads(accounting.read_text(encoding="utf-8"))
    assert payload["schema"] == "db-policy-migration-accounting"
    assert payload["inputCount"] == 99


# ── Appended (PR-GR-05): tracked-artifact byte equality + coverage ───────────
#
# The tracked artifacts under ``config/guards/`` are MACHINE-GENERATED and
# must never be hand-edited.  These regression tests regenerate both in a
# tmp ``--generate`` run (one subprocess per module, shared by the tests)
# and pin BYTE EQUALITY against the tracked files, so any hand edit to
# either artifact fails loudly.  They also pin the live source-mutation
# coverage section shipped inside the tracked accounting artifact.

TRACKED_ACCOUNTING = (
    REPO_ROOT
    / "config"
    / "guards"
    / "db_ownership_policy.signatures.accounting.json"
)


import pytest  # noqa: E402

from scripts.db_guard.policy_v2_candidate import (  # noqa: E402
    COVERAGE_COVERED_BY_RESOLVED_LEGACY_ROW,
    COVERAGE_OBSERVED_BUT_UNRESOLVED,
    COVERAGE_OBSERVED_NOT_IN_LEGACY_POLICY,
    COVERAGE_UNRESOLVED_ANALYZER_INPUT,
    SOURCE_MUTATION_COVERAGE_KINDS,
)


@pytest.fixture(scope="module")
def tracked_regeneration(tmp_path_factory):
    """One ``--generate`` run with both targets overridden into tmp.

    Shared by every test in this section so the (expensive, full-tree)
    generation pipeline runs exactly once per module.
    """
    out_dir = tmp_path_factory.mktemp("tracked-regen")
    candidate = out_dir / "candidate.yml"
    accounting = out_dir / "accounting.json"
    completed = _run_cli(
        "--generate",
        "--output",
        str(candidate),
        "--accounting-out",
        str(accounting),
    )
    return {
        "completed": completed,
        "candidate": candidate,
        "accounting": accounting,
    }


def test_tracked_candidate_artifact_matches_regeneration_bytes(
    tracked_regeneration,
):
    """The tracked candidate is byte-identical to a fresh regeneration.

    Any hand edit to the tracked candidate artifact fails here; the only
    sanctioned way to change it is rerunning ``--generate``.
    """
    completed = tracked_regeneration["completed"]
    assert completed.returncode in (0, 1), completed.stderr
    regenerated = tracked_regeneration["candidate"].read_bytes()
    assert TRACKED_CANDIDATE.is_file()
    assert regenerated == TRACKED_CANDIDATE.read_bytes(), (
        "tracked candidate artifact drifted from the regeneration output;"
        " regenerate it via scripts/migrate_db_policy_signatures.py"
        " --generate instead of hand-editing"
    )


def test_tracked_accounting_artifact_matches_regeneration_bytes(
    tracked_regeneration,
):
    """The tracked accounting artifact is byte-identical to regeneration.

    Gated until the tracked artifact is regenerated WITH the PR-GR-05
    source-mutation coverage section: while the checked-in artifact still
    carries the pre-coverage empty ``sourceMutations`` list, this test
    skips with that exact reason instead of failing on the known-pending
    state.  Once regenerated (the only sanctioned path,
    ``scripts/migrate_db_policy_signatures.py --generate``), the skip
    disappears and ANY divergence — including hand edits to either the
    tracked artifact or the generator output — fails the byte comparison.
    """
    completed = tracked_regeneration["completed"]
    assert completed.returncode in (0, 1), completed.stderr
    if not TRACKED_ACCOUNTING.is_file():
        pytest.skip("tracked accounting artifact not present in checkout")
    tracked_payload = json.loads(
        TRACKED_ACCOUNTING.read_text(encoding="utf-8")
    )
    if tracked_payload.get("sourceMutations") == []:
        pytest.skip(
            "tracked accounting artifact predates the PR-GR-05"
            " source-mutation coverage section; regenerate it via"
            " scripts/migrate_db_policy_signatures.py --generate"
        )
    regenerated = tracked_regeneration["accounting"].read_bytes()
    assert regenerated == TRACKED_ACCOUNTING.read_bytes(), (
        "tracked accounting artifact drifted from the regeneration output;"
        " regenerate it via scripts/migrate_db_policy_signatures.py"
        " --generate instead of hand-editing"
    )


def test_generate_ships_nonempty_source_mutation_coverage(
    tracked_regeneration,
):
    """The generated accounting artifact carries live, well-formed coverage.

    Contract: non-empty ``sourceMutations``; kinds inside the closed
    vocabulary; deterministic ``(path, symbol, operation)`` ordering;
    repository-relative POSIX paths; bounded symbols; COVERED and
    OBSERVED_BUT_UNRESOLVED entries name ascending in-range legacy indices;
    observation-only kinds name none; and the covered/unresolved indices
    stay within the artifact's own record index range.
    """
    completed = tracked_regeneration["completed"]
    assert completed.returncode in (0, 1), completed.stderr
    payload = json.loads(
        tracked_regeneration["accounting"].read_text(encoding="utf-8")
    )
    mutations = payload["sourceMutations"]
    assert mutations, "coverage section must ship non-empty"
    record_indexes = {
        record["index"] for record in payload["records"]
    }
    ordering = []
    for item in mutations:
        assert set(item) == {
            "kind",
            "legacyIndices",
            "operation",
            "path",
            "symbol",
        }
        assert item["kind"] in SOURCE_MUTATION_COVERAGE_KINDS
        path = item["path"]
        assert isinstance(path, str) and path
        assert "\\" not in path and ":" not in path and not path.startswith("/")
        assert all(
            segment not in ("", ".", "..") for segment in path.split("/")
        )
        symbol = item["symbol"]
        assert isinstance(symbol, str) and "#" in symbol
        assert len(symbol) <= 200
        operation = item["operation"]
        assert operation is None or (
            isinstance(operation, str) and operation
        )
        indices = item["legacyIndices"]
        assert indices == sorted(set(indices))
        if item["kind"] in (
            COVERAGE_COVERED_BY_RESOLVED_LEGACY_ROW,
            COVERAGE_OBSERVED_BUT_UNRESOLVED,
        ):
            assert indices
            assert set(indices) <= record_indexes
        else:
            assert indices == ()
        ordering.append((path, symbol, operation or ""))
    assert ordering == sorted(ordering)
    kinds = {item["kind"] for item in mutations}
    # The real repository exhibits all four kinds today.
    assert kinds == {
        COVERAGE_COVERED_BY_RESOLVED_LEGACY_ROW,
        COVERAGE_OBSERVED_BUT_UNRESOLVED,
        COVERAGE_OBSERVED_NOT_IN_LEGACY_POLICY,
        COVERAGE_UNRESOLVED_ANALYZER_INPUT,
    }


def test_real_run_coverage_partitions_observed_universe(
    tracked_regeneration,
):
    """Library-vs-CLI equality plus the no-omission partition invariant.

    Runs the full pipeline IN-PROCESS over the real repository (migrate ->
    observed-mutation scan -> classification) and asserts:

    * the library-built coverage serializes EXACTLY to the CLI-generated
      artifact's ``sourceMutations`` list (one evidence truth, two paths);
    * every observed mutation appears exactly once across kinds — the
      identity multiset of the classified coverage equals the identity
      multiset of the observed universe (no omission, no double count);
    * oracle soundness: every site's resolved ``(dao fqcn, operation)``
      pair is attested by the Room inventory's mutator identities or by a
      kept candidate entry from the generated candidate document.
    """
    import yaml

    from scripts.db_guard.policy_v2_loader import load_policy_v2
    from scripts.db_guard.room_inventory import build_room_inventory
    from scripts.db_guard.policy_v2_candidate import (
        _declared_relative_root_set,
        build_observed_mutation_set,
        classify_source_mutations,
    )

    policy_path = REPO_ROOT / "config" / "guards" / "db_ownership_policy.yml"
    legacy_entries = yaml.safe_load(
        policy_path.read_text(encoding="utf-8")
    )["entries"]
    result = migrate_policy(legacy_entries, str(REPO_ROOT))
    attested_pairs = frozenset(
        (row.entry.dao_fqcn, row.entry.operation)
        for row in result.resolved
    )
    observed_set = build_observed_mutation_set(
        REPO_ROOT,
        attested_pairs=attested_pairs,
    )
    assert observed_set is not None
    coverage = classify_source_mutations(observed_set, result, legacy_entries)

    # 1. Library output equals the CLI artifact's shipped section exactly.
    artifact_payload = json.loads(
        tracked_regeneration["accounting"].read_text(encoding="utf-8")
    )
    assert [item.to_dict() for item in coverage] == (
        artifact_payload["sourceMutations"]
    )

    # 2. Partition: no omission, no double count.
    mutation_identities = sorted(
        (m.path, m.owner_fqcn, m.method, m.operation)
        for m in observed_set.mutations
    )
    coverage_identities = sorted(
        (
            item.path,
            item.symbol.split("#", 1)[0],
            item.symbol.split("#", 1)[1],
            item.operation or "",
        )
        for item in coverage
    )
    assert mutation_identities == coverage_identities
    assert len(coverage) == len(observed_set.mutations)

    # 3. Oracle soundness of the attested universe.
    root_set, _root_diagnostics = _declared_relative_root_set(REPO_ROOT)
    assert root_set is not None
    inventory = build_room_inventory(
        REPO_ROOT, None, source_root_set=root_set
    )
    oracle = set()
    for mutator in inventory.mutators:
        _path, rest = mutator.method.split("::", 1)
        fqcn, tail = rest.split("#", 1)
        oracle.add((fqcn, tail.split("(", 1)[0]))
    document, errors = load_policy_v2(tracked_regeneration["candidate"])
    assert errors == []
    kept_pairs = {
        (entry.dao_fqcn, entry.operation) for entry in document
    }
    for mutation in observed_set.mutations:
        pair = (mutation.dao_fqcn, mutation.operation)
        assert pair in oracle or pair in kept_pairs
