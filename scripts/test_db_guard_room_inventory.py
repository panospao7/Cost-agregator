"""Contract tests for Room DAO discovery and mutator inventory."""

from __future__ import annotations

import json
import os
from collections import Counter
from pathlib import Path

import pytest
import yaml

from scripts.db_guard import room_inventory
from scripts.db_guard.dao_accessors import MAX_ANNOTATION_TO_DECLARATION_SPAN, find_dao_declarations, find_dao_method_annotations
from scripts.db_guard.room_inventory import (
    InventoryWriteError,
    _absolute_root_anchor,
    _resolve_raw_query_parameters,
    build_room_inventory,
    write_inventory_atomic,
)
from scripts.db_policy_signature import normalize_type_text


def _write(root: Path, relative: str, source: str) -> None:
    path = root / relative
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(source, encoding="utf-8")


DEFAULT_RELATIVE = "app/src/main/java/example/Fixtures.kt"


def _inventory(tmp_path: Path, source: str, *, relative: str = DEFAULT_RELATIVE, policy=None):
    _write(tmp_path, relative, source)
    return build_room_inventory(tmp_path, raw_query_policy=policy)


def _raw_entry(dao: str, classification: str, method: str = "execute",
               parameter: str = "androidx.sqlite.db.SupportSQLiteQuery") -> dict:
    """One exact RawQuery policy entry for the inheritance contract tests."""
    return {
        "dao": dao, "method": method,
        "signature": {"receiver": None, "parameters": [parameter]},
        "classification": classification, "reason": "Controlled raw query",
        "owner": "@panospao7", "linked_issue": "MIT-003",
    }


def diag_code(value: str) -> str:
    """The controlled diagnostic code from a bare or location-qualified
    diagnostic string (``DB_ROOM_RAW_QUERY_POLICY_INVALID`` vs
    ``DB_ROOM_RAW_QUERY_POLICY_INVALID:<canonical path>:<line>``).

    Diagnostics are always controlled codes plus canonical source locations,
    never raw payloads, so ``split(":", 1)[0]`` splits only the code prefix
    and is applied exclusively to controlled diagnostic strings.
    """
    return value.split(":", 1)[0]


def _mock_directory_barrier(monkeypatch):
    """Provide a deterministic directory barrier for platforms without one."""
    real_open = room_inventory.os.open
    real_fsync = room_inventory.os.fsync
    real_close = room_inventory.os.close
    directory_fd = 987654
    monkeypatch.setattr(room_inventory.os, "O_DIRECTORY", 0x10000, raising=False)
    monkeypatch.setattr(
        room_inventory.os, "open",
        lambda path, flags: directory_fd if flags & 0x10000 else real_open(path, flags),
    )
    monkeypatch.setattr(
        room_inventory.os, "fsync",
        lambda fd: None if fd == directory_fd else real_fsync(fd),
    )
    monkeypatch.setattr(room_inventory.os, "close", lambda fd: None if fd == directory_fd else real_close(fd))


def test_inventory_helper_rejects_positional_third_argument(tmp_path):
    """Keyword-only ``relative``/``policy`` make positional misuse fail fast.

    A third positional argument can no longer bind to ``relative`` (or be
    silently re-routed to ``policy``): the call must raise ``TypeError`` so a
    dict-shaped policy can never become a synthetic fixture path."""
    with pytest.raises(TypeError):
        _inventory(tmp_path, "package example\n@Dao interface D {}\n", "some/relative/Path.kt")


def test_dict_policy_cannot_become_synthetic_path(tmp_path):
    """A dict passed as ``policy=`` stays a policy: nothing derived from its
    contents may leak into the source tree as a synthetic file path."""
    inventory = _inventory(
        tmp_path,
        "package example\n@Dao interface D { @Insert fun put(v: Item) }\n",
        policy={"key": "value"},
    )
    # No file or directory named after any key/value of the mapping exists.
    assert not list(tmp_path.rglob("key"))
    assert not list(tmp_path.rglob("value"))
    # Exactly one file was written: the canonical default fixture path.
    written = sorted(
        path.relative_to(tmp_path).as_posix()
        for path in tmp_path.rglob("*")
        if path.is_file()
    )
    assert written == [DEFAULT_RELATIVE]
    # Fail closed: the malformed dict is reported through the controlled
    # policy diagnostic channel, never materialized as a path.  Per the
    # trust contract (docs/ci/DB_ROOM_INVENTORY.md §4) the diagnostic-bearing
    # run is untrusted and every consumer must reject it; mutators present
    # in such a report are diagnostic context only (§4.2) — here exactly the
    # unrelated direct @Insert declaration — and nothing is ever classified
    # THROUGH the broken policy (no RawQuery-derived mutator can exist).
    assert [item.annotation for item in inventory.mutators] == ["Insert"]
    assert any(diag_code(d) == "DB_ROOM_RAW_QUERY_POLICY_INVALID" for d in inventory.diagnostics)


def test_inventory_default_call_works(tmp_path):
    inventory = _inventory(tmp_path, "package example\n@Dao interface D { @Insert fun put(v: Item) }\n")
    assert inventory is not None
    assert [dao.fqcn for dao in inventory.daos] == ["example.D"]
    assert (tmp_path / DEFAULT_RELATIVE).is_file()


def test_inventory_explicit_relative_writes_fixture(tmp_path):
    relative = "app/src/main/java/com/example/Other.kt"
    inventory = _inventory(
        tmp_path,
        "package com.example\n@Dao interface Other { @Insert fun save(v: Item) }\n",
        relative=relative,
    )
    written = sorted(
        path.relative_to(tmp_path).as_posix()
        for path in tmp_path.rglob("*")
        if path.is_file()
    )
    assert written == [relative]
    assert [dao.fqcn for dao in inventory.daos] == ["com.example.Other"]
    assert inventory.mutators[0].source_location.startswith(relative + ":")


def test_inventory_explicit_policy_keyword_forwards(tmp_path):
    policy = {"version": 1, "methods": [{
        "dao": "example.D", "method": "execute",
        "signature": {"receiver": None, "parameters": ["androidx.sqlite.db.SupportSQLiteQuery"]},
        "classification": "write", "reason": "Controlled repair query",
        "owner": "@panospao7", "linked_issue": "MIT-003",
    }]}
    inventory = _inventory(
        tmp_path,
        "package example\n@Dao interface D { @RawQuery fun execute(query: androidx.sqlite.db.SupportSQLiteQuery) }\n",
        policy=policy,
    )
    assert len(inventory.mutators) == 1
    assert inventory.mutators[0].mutation_kind == "ROOM_MUTATING_QUERY"


def test_inventory_forwards_raw_query_policy_by_keyword(tmp_path, monkeypatch):
    seen = {}
    real_build = build_room_inventory

    def spy(*args, **kwargs):
        seen["positional"] = len(args)
        seen["kwargs"] = kwargs
        return real_build(*args, **kwargs)

    # Patch the name as imported into THIS test module: ``_inventory``
    # resolves ``build_room_inventory`` from this module's globals.
    # ``monkeypatch.setitem`` restores the original binding afterwards.
    monkeypatch.setitem(_inventory.__globals__, "build_room_inventory", spy)
    policy = {"version": 1}
    _inventory(tmp_path, "package example\n@Dao interface D { @Insert fun put(v: Item) }\n", policy=policy)
    assert seen["positional"] == 1
    assert seen["kwargs"] == {"raw_query_policy": policy}
    assert seen["kwargs"]["raw_query_policy"] is policy


def test_qualified_multiline_dao_and_non_suffix_file_are_discovered(tmp_path):
    inventory = _inventory(tmp_path, """package example
        @androidx.room.Dao
        interface RepairDao
        {
            @androidx.room.Insert
            fun save(value: Item)
        }
    """, relative="app/src/main/java/example/Repair.kt")
    assert [dao.fqcn for dao in inventory.daos] == ["example.RepairDao"]
    assert inventory.mutators[0].method.endswith("#save(Item)")
    assert inventory.mutators[0].source_location == "app/src/main/java/example/Repair.kt:5"


def test_multiple_and_nested_daos_and_all_write_annotations_are_inventory_mutators(tmp_path):
    source = """package example
@Dao interface First { @Insert fun persist(v: Item) }
@Dao interface Second { @Update fun applyStatus(v: Item); @Delete fun remove(v: Item) }
class Outer { @Dao interface Inner { @Upsert fun store(v: Item) } }
"""
    inventory = _inventory(tmp_path, source)
    assert {dao.fqcn for dao in inventory.daos} == {"example.First", "example.Second", "example.Outer.Inner"}
    assert {item.method.rsplit("#", 1)[1] for item in inventory.mutators} == {
        "persist(Item)", "applyStatus(Item)", "remove(Item)", "store(Item)"
    }


def test_same_file_daos_keep_direct_mutators_with_their_declared_owner(tmp_path):
    inventory = _inventory(tmp_path, """package example
        class Holder {
            @Dao interface Parent { @Insert fun insertParent(v: ParentItem) }
            @Dao interface Child { @Insert fun insertChild(v: ChildItem) }
        }
    """)
    assert {item.method for item in inventory.mutators} == {
        "app/src/main/java/example/Fixtures.kt::example.Holder.Child#insertChild(ChildItem)",
        "app/src/main/java/example/Fixtures.kt::example.Holder.Parent#insertParent(ParentItem)",
    }


@pytest.mark.parametrize("name", ["save", "persist", "remove", "wipe", "applyStatus", "store", "put", "create"])
def test_annotation_discovery_does_not_depend_on_method_name(name, tmp_path):
    annotation = "Insert" if name in {"save", "persist", "store", "put", "create"} else "Delete"
    inventory = _inventory(tmp_path, f"package example\n@Dao interface D {{ @{annotation} fun {name}(v: Item) }}\n")
    assert len(inventory.mutators) == 1
    assert f"#{name}(Item)" in inventory.mutators[0].method


@pytest.mark.parametrize("sql,expected", [("SELECT 1", 0), ("UPDATE x SET y = 1", 1)])
def test_query_read_and_mutating_classification(sql, expected, tmp_path):
    inventory = _inventory(tmp_path, f'package example\n@Dao interface D {{ @Query("{sql}") fun run() }}\n')
    assert len(inventory.mutators) == expected
    if not expected:
        assert not any("UNCLASSIFIABLE" in item for item in inventory.diagnostics)


def test_query_uncertain_and_raw_query_without_exact_policy_fail_closed(tmp_path):
    inventory = _inventory(tmp_path, """package example
        @Dao interface D {
            @Query("PRAGMA user_version") fun uncertain()
            @RawQuery fun execute(query: SupportSQLiteQuery)
        }
    """)
    assert not inventory.mutators
    assert any(d.startswith("DB_ROOM_QUERY_UNCLASSIFIABLE:") for d in inventory.diagnostics)
    assert any(d.startswith("DB_SIGNATURE_UNRESOLVED:") for d in inventory.diagnostics)


def test_raw_query_uses_only_exact_policy_signature(tmp_path):
    policy = {"version": 1, "methods": [{
        "dao": "example.D", "method": "execute",
        "signature": {"receiver": None, "parameters": ["androidx.sqlite.db.SupportSQLiteQuery"]},
        "classification": "write", "reason": "Controlled repair query",
        "owner": "@panospao7", "linked_issue": "MIT-003",
    }]}
    inventory = _inventory(tmp_path, "package example\n@Dao interface D { @RawQuery fun execute(query: androidx.sqlite.db.SupportSQLiteQuery) }\n", policy=policy)
    assert len(inventory.mutators) == 1
    assert inventory.mutators[0].mutation_kind == "ROOM_MUTATING_QUERY"


@pytest.mark.parametrize("imports", [
    "import androidx.sqlite.db.SupportSQLiteQuery",
    "import androidx.sqlite.db.SupportSQLiteQuery as SqlQuery",
])
def test_raw_query_resolves_imported_query_type_and_alias(tmp_path, imports):
    parameter = "SqlQuery" if " as " in imports else "SupportSQLiteQuery"
    policy = {"version": 1, "methods": [{
        "dao": "example.D", "method": "execute",
        "signature": {"receiver": None, "parameters": ["androidx.sqlite.db.SupportSQLiteQuery"]},
        "classification": "write", "reason": "controlled", "owner": "@owner", "linked_issue": "ISSUE",
    }]}
    source = f"package example\n{imports}\n@Dao interface D {{ @RawQuery fun execute(query: {parameter}) }}\n"
    inventory = _inventory(tmp_path, source, policy=policy)
    assert len(inventory.mutators) == 1
    assert not any("SIGNATURE_UNRESOLVED" in diagnostic for diagnostic in inventory.diagnostics)


@pytest.mark.parametrize("imports", [
    "import androidx.sqlite.db.*",
    "import androidx.sqlite.db.SupportSQLiteQuery\nimport other.SupportSQLiteQuery",
    "import androidx.sqlite.db.SupportSQLiteQuery as Query\nimport other.Query as Query",
])
def test_raw_query_wildcard_or_ambiguous_import_fails_closed(tmp_path, imports):
    parameter = "Query" if " as Query" in imports else "SupportSQLiteQuery"
    source = f"package example\n{imports}\n@Dao interface D {{ @RawQuery fun execute(query: {parameter}) }}\n"
    inventory = _inventory(tmp_path, source, policy={"version": 1, "methods": []})
    assert not inventory.mutators
    assert any(d.startswith("DB_SIGNATURE_UNRESOLVED:") for d in inventory.diagnostics)


def test_raw_query_unresolved_simple_type_fails_closed(tmp_path):
    inventory = _inventory(tmp_path, "package example\n@Dao interface D { @RawQuery fun execute(query: SupportSQLiteQuery) }\n")
    assert any(d.startswith("DB_SIGNATURE_UNRESOLVED:") for d in inventory.diagnostics)


@pytest.mark.parametrize("parameter,policy_parameters", [
    ("query: String", ["String"]),
    ("query: Object", ["Object"]),
    ("query: List<String?>", ["List<String?>"]),
    ("query: SupportSQLiteQuery?", ["SupportSQLiteQuery?"]),
])
def test_raw_query_unsupported_parameter_types_never_become_mutators(tmp_path, parameter, policy_parameters):
    """A @RawQuery whose single parameter is not exactly the canonical
    ``androidx.sqlite.db.SupportSQLiteQuery`` violates the signature contract.

    A resolvable-but-unsupported type (String, Object, generic, nullable)
    emits ``DB_ROOM_RAW_QUERY_POLICY_INVALID`` and is never a mutator, even
    when a write-classified policy entry exactly matches that callable."""
    policy = {"version": 1, "methods": [{
        "dao": "example.D", "method": "execute",
        "signature": {"receiver": None, "parameters": policy_parameters},
        "classification": "write", "reason": "Controlled write query",
        "owner": "@panospao7", "linked_issue": "MIT-003",
    }]}
    inventory = _inventory(
        tmp_path,
        f"package example\nimport androidx.sqlite.db.SupportSQLiteQuery\n@Dao interface D {{ @RawQuery fun execute({parameter}) }}\n",
        policy=policy,
    )
    assert not inventory.mutators
    assert any(
        diag_code(d) == "DB_ROOM_RAW_QUERY_POLICY_INVALID" for d in inventory.diagnostics
    )


def test_raw_query_two_parameters_never_become_mutators(tmp_path):
    """A @RawQuery with more than one parameter violates the signature
    contract: it emits ``DB_ROOM_RAW_QUERY_POLICY_INVALID`` and is never a
    mutator, even with a matching write-classified policy entry."""
    policy = {"version": 1, "methods": [{
        "dao": "example.D", "method": "execute",
        "signature": {
            "receiver": None,
            "parameters": ["androidx.sqlite.db.SupportSQLiteQuery", "Int"],
        },
        "classification": "write", "reason": "Controlled write query",
        "owner": "@panospao7", "linked_issue": "MIT-003",
    }]}
    inventory = _inventory(
        tmp_path,
        """package example
import androidx.sqlite.db.SupportSQLiteQuery
@Dao interface D { @RawQuery fun execute(query: SupportSQLiteQuery, limit: Int) }
""",
        policy=policy,
    )
    assert not inventory.mutators
    assert any(
        diag_code(d) == "DB_ROOM_RAW_QUERY_POLICY_INVALID" for d in inventory.diagnostics
    )


def test_raw_query_extension_receiver_never_becomes_mutator(tmp_path):
    """An extension-receiver @RawQuery violates the signature contract: the
    receiver must be null, so the method emits
    ``DB_ROOM_RAW_QUERY_POLICY_INVALID`` and is never a mutator even with a
    matching write-classified policy entry."""
    policy = {"version": 1, "methods": [{
        "dao": "example.D", "method": "execute",
        "signature": {
            "receiver": "SupportSQLiteQuery",
            "parameters": ["androidx.sqlite.db.SupportSQLiteQuery"],
        },
        "classification": "write", "reason": "Controlled write query",
        "owner": "@panospao7", "linked_issue": "MIT-003",
    }]}
    inventory = _inventory(
        tmp_path,
        """package example
import androidx.sqlite.db.SupportSQLiteQuery
@Dao interface D { @RawQuery fun SupportSQLiteQuery.execute(query: SupportSQLiteQuery) }
""",
        policy=policy,
    )
    assert not inventory.mutators
    assert any(
        diag_code(d) == "DB_ROOM_RAW_QUERY_POLICY_INVALID" for d in inventory.diagnostics
    )


def test_raw_query_single_support_query_parameter_is_the_positive_contract(tmp_path):
    """The positive @RawQuery signature contract: exactly one parameter
    resolving to the canonical ``androidx.sqlite.db.SupportSQLiteQuery`` and
    no receiver is the only valid RawQuery identity and can be a write
    mutator."""
    policy = {"version": 1, "methods": [{
        "dao": "example.D", "method": "execute",
        "signature": {"receiver": None, "parameters": ["androidx.sqlite.db.SupportSQLiteQuery"]},
        "classification": "write", "reason": "Controlled repair query",
        "owner": "@panospao7", "linked_issue": "MIT-003",
    }]}
    inventory = _inventory(
        tmp_path,
        "package example\nimport androidx.sqlite.db.SupportSQLiteQuery\n@Dao interface D { @RawQuery fun execute(query: SupportSQLiteQuery) }\n",
        policy=policy,
    )
    assert len(inventory.mutators) == 1
    assert inventory.mutators[0].mutation_kind == "ROOM_MUTATING_QUERY"
    assert not any("POLICY_INVALID" in d for d in inventory.diagnostics)


def test_raw_query_unsupported_type_without_policy_entry_fails_invalid_not_required(tmp_path):
    """A contract-violating @RawQuery is never a discovered identity: it
    emits ``DB_ROOM_RAW_QUERY_POLICY_INVALID`` (not
    ``DB_ROOM_RAW_QUERY_POLICY_REQUIRED``) and is never a mutator."""
    inventory = _inventory(
        tmp_path,
        "package example\n@Dao interface D { @RawQuery fun execute(query: String) }\n",
    )
    assert not inventory.mutators
    assert any(diag_code(d) == "DB_ROOM_RAW_QUERY_POLICY_INVALID" for d in inventory.diagnostics)
    assert not any(diag_code(d) == "DB_ROOM_RAW_QUERY_POLICY_REQUIRED" for d in inventory.diagnostics)


def test_query_unsupported_kotlin_escape_fails_closed_without_decoding(tmp_path):
    inventory = _inventory(tmp_path, r'''package example
@Dao interface D { @Query("UPDATE\u00a0items SET value = 1") fun update() }
''')
    assert not inventory.mutators
    assert any(d.startswith("DB_ROOM_QUERY_UNCLASSIFIABLE:") for d in inventory.diagnostics)


def test_query_template_resolves_same_file_consts_to_a_read(tmp_path):
    inventory = _inventory(tmp_path, """package example
        const val TABLE_NAME = "users"
        const val SELECT_FROM = "SELECT * FROM " + TABLE_NAME
        @Dao interface D {
            @Query("$SELECT_FROM WHERE active = 1")
            fun activeUsers()
        }
    """)
    assert not inventory.mutators
    assert not any("UNCLASSIFIABLE" in diagnostic for diagnostic in inventory.diagnostics)


def test_query_template_with_unknown_runtime_interpolation_fails_closed(tmp_path):
    inventory = _inventory(tmp_path, """package example
        const val TABLE_NAME = "users"
        @Dao interface D {
            @Query("SELECT * FROM $TABLE_NAME WHERE id = ${runtime()}")
            fun byId(id: Long)
        }
    """)
    assert not inventory.mutators
    assert any(diagnostic.startswith("DB_ROOM_QUERY_UNCLASSIFIABLE:")
               for diagnostic in inventory.diagnostics)


def test_query_template_resolves_same_file_literal_const(tmp_path):
    inventory = _inventory(tmp_path, """package example
        const val TABLE_NAME = "users"
        @Dao interface D {
            @Query("SELECT * FROM ${TABLE_NAME} WHERE active = 1")
            fun activeUsers()
        }
    """)
    assert not inventory.mutators
    assert not any("UNCLASSIFIABLE" in diagnostic for diagnostic in inventory.diagnostics)


def test_query_template_resolves_nested_const_chain(tmp_path):
    inventory = _inventory(tmp_path, """package example
        const val SCHEMA = "main"
        const val TABLE_NAME = SCHEMA + ".users"
        const val SELECT_FROM = "SELECT * FROM " + TABLE_NAME
        @Dao interface D {
            @Query("$SELECT_FROM WHERE active = 1")
            fun activeUsers()
        }
    """)
    assert not inventory.mutators
    assert not any("UNCLASSIFIABLE" in diagnostic for diagnostic in inventory.diagnostics)


def test_query_template_resolves_multiline_const_continuation(tmp_path):
    """Production-style multiline ``const val`` ``+``-joined continuation.

    ``EFFECTIVE_AMOUNT_SQL``-style queries join string fragments across
    lines with a trailing ``+``.  When the first fragment starts on the
    declaration line (``const val A = "SELECT ..." +`` / ``" WHERE ..."``),
    the constant is collected and the ``@Query("$A ...")`` template resolves
    to plain read SQL; it must never fail closed as unclassifiable.
    """
    inventory = _inventory(tmp_path, """package example
        const val SELECT_ACTIVE = "SELECT * FROM users " +
            "WHERE active = 1 " +
            "ORDER BY name ASC"
        @Dao interface D {
            @Query("$SELECT_ACTIVE LIMIT :limit")
            fun activeUsers(limit: Int)
        }
    """)
    assert not inventory.mutators
    assert not any("UNCLASSIFIABLE" in diagnostic for diagnostic in inventory.diagnostics)


def test_query_template_resolves_untyped_const_with_spacing_variants(tmp_path):
    """Arbitrary legal whitespace/newlines between ``const val`` name and
    ``=`` must not hide a same-file query constant."""
    inventory = _inventory(tmp_path, """package example
        const val TABLE_A = "users"
        const val TABLE_B
            = "ledger"
        @Dao interface D {
            @Query("SELECT * FROM $TABLE_A WHERE active = 1") fun activeA()
            @Query("SELECT * FROM $TABLE_B WHERE active = 1") fun activeB()
        }
    """)
    assert not inventory.mutators
    assert not any("UNCLASSIFIABLE" in diagnostic for diagnostic in inventory.diagnostics)


def test_query_template_resolves_typed_const_with_spacing_variants(tmp_path):
    """Typed ``const val NAME: String = ...`` with newlines around the type
    and ``=`` resolves like the production ``EFFECTIVE_AMOUNT_SQL`` shape."""
    inventory = _inventory(tmp_path, """package example
        const val TABLE_A: String = "users"
        const val TABLE_B: String
            = "ledger"
        const val AMOUNT_SQL: String =
            "CASE WHEN isNotMine = 1 THEN 0.0 " +
            "ELSE amount END"
        @Dao interface D {
            @Query("SELECT * FROM $TABLE_A WHERE active = 1") fun activeA()
            @Query("SELECT * FROM $TABLE_B WHERE active = 1") fun activeB()
            @Query("SELECT SUM($AMOUNT_SQL) FROM expenses") fun total()
        }
    """)
    assert not inventory.mutators
    assert not any("UNCLASSIFIABLE" in diagnostic for diagnostic in inventory.diagnostics)


def test_query_template_const_declaration_with_comments_resolves(tmp_path):
    """Comments near a const declaration (leading, between the name and
    ``=``, and trailing) are masked to whitespace and must not hide the
    constant or leak into its RHS operands."""
    inventory = _inventory(tmp_path, """package example
        // leading comment
        const val TABLE_NAME /* between */ = "users" // trailing comment
        @Dao interface D {
            @Query("SELECT * FROM $TABLE_NAME WHERE active = 1")
            fun activeUsers()
        }
    """)
    assert not inventory.mutators
    assert not any("UNCLASSIFIABLE" in diagnostic for diagnostic in inventory.diagnostics)


def test_query_template_spacing_variant_const_with_runtime_interpolation_fails_closed(tmp_path):
    """A whitespace-variant const is collected, but an unknown/runtime
    interpolation anywhere in the query still fails closed."""
    inventory = _inventory(tmp_path, """package example
        const val TABLE_NAME: String
            = "users"
        @Dao interface D {
            @Query("SELECT * FROM $TABLE_NAME WHERE id = ${runtime()}")
            fun byId(id: Long)
        }
    """)
    assert not inventory.mutators
    assert any(diagnostic.startswith("DB_ROOM_QUERY_UNCLASSIFIABLE:")
               for diagnostic in inventory.diagnostics)


@pytest.mark.parametrize("argument", ["SELECT_ALL", "value = SELECT_ALL", "query = SELECT_ALL"])
def test_query_direct_const_reference_forms_resolve_to_read(tmp_path, argument):
    inventory = _inventory(tmp_path, f"""package example
        const val SELECT_ALL = "SELECT * FROM users"
        @Dao interface D {{
            @Query({argument})
            fun allUsers()
        }}
    """)
    assert not inventory.mutators
    assert not any("UNCLASSIFIABLE" in diagnostic for diagnostic in inventory.diagnostics)


def test_query_direct_const_reference_mutating_query_is_a_mutator(tmp_path):
    inventory = _inventory(tmp_path, """package example
        const val DELETE_ALL = "DELETE FROM users"
        @Dao interface D {
            @Query(DELETE_ALL)
            fun wipeAll()
        }
    """)
    assert len(inventory.mutators) == 1
    assert inventory.mutators[0].mutation_kind == "ROOM_MUTATING_QUERY"
    assert inventory.mutators[0].query_kind == "DELETE"


@pytest.mark.parametrize("template", ['"SELECT * FROM $UNKNOWN_TABLE"', '"SELECT * FROM ${UNKNOWN_TABLE}"'])
def test_query_template_with_unknown_const_name_fails_closed(tmp_path, template):
    inventory = _inventory(tmp_path, f"""package example
        @Dao interface D {{
            @Query({template})
            fun allUsers()
        }}
    """)
    assert not inventory.mutators
    assert any(diagnostic.startswith("DB_ROOM_QUERY_UNCLASSIFIABLE:")
               for diagnostic in inventory.diagnostics)


def test_query_template_const_cycle_fails_closed(tmp_path):
    inventory = _inventory(tmp_path, """package example
        const val A = "SELECT * FROM " + B
        const val B = "users WHERE active = " + A
        @Dao interface D {
            @Query("$A")
            fun allUsers()
        }
    """)
    assert not inventory.mutators
    assert any(diagnostic.startswith("DB_ROOM_QUERY_UNCLASSIFIABLE:")
               for diagnostic in inventory.diagnostics)


def test_query_direct_const_reference_unknown_fails_closed(tmp_path):
    inventory = _inventory(tmp_path, """package example
        @Dao interface D {
            @Query(MISSING_QUERY)
            fun allUsers()
        }
    """)
    assert not inventory.mutators
    assert any(diagnostic.startswith("DB_ROOM_QUERY_UNCLASSIFIABLE:")
               for diagnostic in inventory.diagnostics)


@pytest.mark.parametrize("signature", [
    {"receiver": "vararg String", "parameters": ["SupportSQLiteQuery"]},
    {"receiver": None, "parameters": "SupportSQLiteQuery"},
    {"receiver": None, "parameters": ["List<"]},
    {"receiver": None, "parameters": ["*"]},
])
def test_raw_query_policy_rejects_malformed_signature_types(tmp_path, signature):
    entry = {
        "dao": "example.D", "method": "execute", "signature": signature,
        "classification": "write", "reason": "controlled", "owner": "@owner", "linked_issue": "ISSUE",
    }
    inventory = _inventory(tmp_path, "package example\n@Dao interface D { @RawQuery fun execute(query: SupportSQLiteQuery) }\n", policy={"version": 1, "methods": [entry]})
    assert any(diag_code(d) == "DB_ROOM_RAW_QUERY_POLICY_INVALID" for d in inventory.diagnostics)


@pytest.mark.parametrize("classification", [None, 1, [], {}])
def test_raw_query_policy_rejects_non_string_classification(tmp_path, classification):
    entry = {
        "dao": "example.D", "method": "execute",
        "signature": {"receiver": None, "parameters": ["SupportSQLiteQuery"]},
        "classification": classification, "reason": "controlled", "owner": "@owner", "linked_issue": "ISSUE",
    }
    inventory = _inventory(tmp_path, "package example\n@Dao interface D { @RawQuery fun execute(query: SupportSQLiteQuery) }\n", policy={"version": 1, "methods": [entry]})
    assert any(diag_code(d) == "DB_ROOM_RAW_QUERY_POLICY_INVALID" for d in inventory.diagnostics)


def test_raw_query_policy_rejects_noncanonical_type_whitespace(tmp_path):
    policy = {"version": 1, "methods": [{
        "dao": "example.D", "method": "execute",
        "signature": {"receiver": None, "parameters": [" List < String ? > "]},
        "classification": "write", "reason": "controlled", "owner": "@owner", "linked_issue": "ISSUE",
    }]}
    inventory = _inventory(tmp_path, "package example\n@Dao interface D { @RawQuery fun execute(query: List<String?>) }\n", policy=policy)
    assert not inventory.mutators
    assert any(diag_code(diagnostic) == "DB_ROOM_RAW_QUERY_POLICY_INVALID" for diagnostic in inventory.diagnostics)


def test_raw_query_canonical_generic_parameter_fails_signature_contract(tmp_path):
    """A canonical generic parameter is still outside the RawQuery signature
    contract: a @RawQuery must take exactly one parameter resolving to the
    canonical ``androidx.sqlite.db.SupportSQLiteQuery``.  A write-classified
    policy entry exactly matching the generic callable can never make it a
    mutator; the discovery emits ``DB_ROOM_RAW_QUERY_POLICY_INVALID``."""
    policy = {"version": 1, "methods": [{
        "dao": "example.D", "method": "execute",
        "signature": {"receiver": None, "parameters": ["List<String?>"]},
        "classification": "write", "reason": "controlled", "owner": "@owner", "linked_issue": "ISSUE",
    }]}
    inventory = _inventory(tmp_path, "package example\n@Dao interface D { @RawQuery fun execute(query: List<String?>) }\n", policy=policy)
    assert not inventory.mutators
    assert any(diag_code(d) == "DB_ROOM_RAW_QUERY_POLICY_INVALID" for d in inventory.diagnostics)


def test_raw_query_policy_requires_exact_overload_signature(tmp_path):
    policy = {"version": 1, "methods": [{
        "dao": "example.D", "method": "execute",
        "signature": {"receiver": None, "parameters": ["OtherQuery"]},
        "classification": "write", "reason": "Controlled repair query",
        "owner": "@panospao7", "linked_issue": "MIT-003",
    }]}
    inventory = _inventory(tmp_path, "package example\n@Dao interface D { @RawQuery fun execute(query: SupportSQLiteQuery) }\n", policy=policy)
    assert not inventory.mutators
    assert any(d.startswith("DB_SIGNATURE_UNRESOLVED:") for d in inventory.diagnostics)


def test_committed_raw_query_policy_loads_for_exact_callable(tmp_path):
    policy_path = Path(__file__).resolve().parent / "fixtures" / "db_raw_query_classification.yml"
    inventory = _inventory(
        tmp_path,
        "package com.example\n@Dao interface RepairDao { @RawQuery fun executeRepair(query: androidx.sqlite.db.SupportSQLiteQuery) }\n",
        policy=policy_path,
    )
    assert len(inventory.mutators) == 1
    assert not any("POLICY_INVALID" in diagnostic for diagnostic in inventory.diagnostics)


@pytest.mark.parametrize("policy", [
    {"version": 2, "methods": []},
    {"version": 1, "entries": []},
    {"version": 1, "methods": [{"dao": "example.D"}]},
    {"version": 1, "methods": [{
        "dao": "example.D", "method": "execute", "signature": {"receiver": None, "parameters": []},
        "classification": "write", "reason": "x", "owner": "@owner", "linked_issue": "ISSUE", "expiry": None,
    }]},
])
def test_raw_query_policy_rejects_schema_missing_and_unknown_fields(tmp_path, policy):
    inventory = _inventory(tmp_path, "package example\n@Dao interface D { @RawQuery fun execute(query: SupportSQLiteQuery) }\n", policy=policy)
    assert any(diag_code(d) == "DB_ROOM_RAW_QUERY_POLICY_INVALID" for d in inventory.diagnostics)


@pytest.mark.parametrize("field", ["dao", "method", "classification", "reason", "owner", "linked_issue"])
def test_raw_query_policy_rejects_missing_entry_fields(tmp_path, field):
    entry = {
        "dao": "example.D", "method": "execute",
        "signature": {"receiver": None, "parameters": ["SupportSQLiteQuery"]},
        "classification": "write", "reason": "controlled", "owner": "@owner", "linked_issue": "ISSUE",
    }
    del entry[field]
    inventory = _inventory(tmp_path, "package example\n@Dao interface D { @RawQuery fun execute(query: SupportSQLiteQuery) }\n", policy={"version": 1, "methods": [entry]})
    assert any(diag_code(d) == "DB_ROOM_RAW_QUERY_POLICY_INVALID" for d in inventory.diagnostics)


@pytest.mark.parametrize("field,value", [
    ("dao", "*"), ("method", "*"), ("classification", "*"),
    ("signature.receiver", "*"), ("signature.parameters", ["*"]),
])
def test_raw_query_policy_rejects_wildcards_in_every_identity_field(tmp_path, field, value):
    entry = {
        "dao": "example.D", "method": "execute",
        "signature": {"receiver": None, "parameters": ["SupportSQLiteQuery"]},
        "classification": "write", "reason": "controlled", "owner": "@owner", "linked_issue": "ISSUE",
    }
    target, _, nested = field.partition(".")
    if nested:
        entry[target][nested] = value
    else:
        entry[field] = value
    inventory = _inventory(tmp_path, "package example\n@Dao interface D { @RawQuery fun execute(query: SupportSQLiteQuery) }\n", policy={"version": 1, "methods": [entry]})
    assert any(diag_code(d) == "DB_ROOM_RAW_QUERY_POLICY_INVALID" for d in inventory.diagnostics)


def test_raw_query_policy_rejects_duplicate_canonical_keys(tmp_path):
    entry = {
        "dao": "example.D", "method": "execute",
        "signature": {"receiver": None, "parameters": ["SupportSQLiteQuery"]},
        "classification": "write", "reason": "controlled", "owner": "@owner", "linked_issue": "ISSUE",
    }
    inventory = _inventory(tmp_path, "package example\n@Dao interface D { @RawQuery fun execute(query: SupportSQLiteQuery) }\n", policy={"version": 1, "methods": [entry, dict(entry)]})
    assert any(diag_code(d) == "DB_ROOM_RAW_QUERY_POLICY_INVALID" for d in inventory.diagnostics)


def test_malformed_policy_with_equal_canonical_keys_fails_closed_normalized(tmp_path):
    """Regression: a malformed canonical policy whose keys otherwise appear
    equal fails the contract with the normalized invalid code.

    Two policy entries whose canonical identities are equal (the exact
    discovered identity, twice) make the policy malformed: the loader rejects
    duplicate canonical keys before the set equality comparison runs, so the
    inventory emits the bare ``DB_ROOM_RAW_QUERY_POLICY_INVALID`` code --
    never a location-qualified variant -- and the contract fails closed even
    though the keys appeared to match the discovered RawQuery exactly.
    ``diag_code`` normalizes both diagnostic forms (bare and
    ``:<canonical path>:<line>``-qualified) so the bare code is detected by
    exact code membership; nothing is classified as a mutator and no
    REQUIRED/STALE replacement code is invented for the malformed policy.
    """
    entry = {
        "dao": "example.D", "method": "execute",
        "signature": {"receiver": None, "parameters": ["SupportSQLiteQuery"]},
        "classification": "read", "reason": "Controlled read query",
        "owner": "@owner", "linked_issue": "ISSUE",
    }
    inventory = _inventory(
        tmp_path,
        "package example\n@Dao interface D { @RawQuery fun execute(query: SupportSQLiteQuery) }\n",
        policy={"version": 1, "methods": [entry, dict(entry)]},
    )
    assert not inventory.mutators
    assert any(
        diag_code(diagnostic) == "DB_ROOM_RAW_QUERY_POLICY_INVALID"
        for diagnostic in inventory.diagnostics
    ), (
        "PRODUCTION_RAW_QUERY_CONTRACT: malformed policy with equal canonical "
        "keys must fail closed with the normalized invalid code"
    )
    assert not any(
        diag_code(diagnostic) == "DB_ROOM_RAW_QUERY_POLICY_REQUIRED"
        for diagnostic in inventory.diagnostics
    )
    assert not any(
        diag_code(diagnostic) == "DB_ROOM_RAW_QUERY_POLICY_STALE"
        for diagnostic in inventory.diagnostics
    )


def test_raw_query_policy_file_has_only_the_exact_versioned_method_entry():
    policy_path = Path(__file__).resolve().parent / "fixtures" / "db_raw_query_classification.yml"
    policy = yaml.safe_load(policy_path.read_text(encoding="utf-8"))
    assert policy == {
        "version": 1,
        "methods": [{
            "dao": "com.example.RepairDao",
            "method": "executeRepair",
            "signature": {
                "receiver": None,
                "parameters": ["androidx.sqlite.db.SupportSQLiteQuery"],
            },
            "classification": "write",
            "reason": "Controlled repair query",
            "owner": "@panospao7",
            "linked_issue": "MIT-003",
        }],
    }


def _repo_root() -> Path:
    return Path(__file__).resolve().parent.parent


def test_production_raw_query_classification_config_exists_and_is_exact():
    config = _repo_root() / "config" / "guards" / "db_raw_query_classification.yml"
    assert config.is_file()
    policy = yaml.safe_load(config.read_text(encoding="utf-8"))
    assert policy["version"] == 1
    methods = policy["methods"]
    assert methods
    entry_keys = {"dao", "method", "signature", "classification", "reason", "owner", "linked_issue"}
    signature_keys = {"receiver", "parameters"}
    for entry in methods:
        assert set(entry) == entry_keys
        assert set(entry["signature"]) == signature_keys
        assert entry["classification"] in {"read", "write"}
        assert entry["signature"]["receiver"] is None
        assert isinstance(entry["signature"]["parameters"], list)
        assert entry["signature"]["parameters"]
        for field in ("dao", "method", "classification", "reason", "owner", "linked_issue"):
            assert isinstance(entry[field], str) and entry[field]
    # No wildcards anywhere (the loader rejects * / % / ... / any / all).
    text = config.read_text(encoding="utf-8")
    assert "*" not in text
    assert "%" not in text
    assert "..." not in text
    # No permanent/expiry/unknown fields: exact key sets above already reject
    # them; double-check the raw text has none of the known spellings.
    lowered = text.lower()
    for forbidden in ("expiry", "allowed_until", "permanent"):
        assert forbidden not in lowered
    # The fixture DAO is test-only and must never appear in production policy.
    assert not any(entry["dao"].startswith("com.example.") for entry in methods)


def test_default_raw_query_policy_constant_points_at_canonical_config():
    assert room_inventory.DEFAULT_RAW_QUERY_POLICY == str(
        _repo_root() / "config" / "guards" / "db_raw_query_classification.yml"
    )


def test_default_raw_query_policy_classifies_production_raw_query_as_read(tmp_path):
    """The canonical default policy covers every production ExpenseDao
    RawQuery exactly; a fixture declaring all of them produces no
    raw-query/policy diagnostics and never classifies them as mutators."""
    inventory = _inventory(
        tmp_path,
        """package com.yourname.expensetracker.data.database.dao
        import androidx.sqlite.db.SupportSQLiteQuery
        @Dao interface ExpenseDao {
            @RawQuery fun getExpensesDynamic(query: SupportSQLiteQuery)
            @RawQuery fun getAssistantExpensesDynamic(query: SupportSQLiteQuery)
            @RawQuery fun getAssistantExpenseCountDynamic(query: SupportSQLiteQuery)
        }
        """,
    )
    assert not inventory.mutators
    assert not any(d.startswith("DB_ROOM_RAW_QUERY_POLICY_") for d in inventory.diagnostics)
    assert not any("SIGNATURE_UNRESOLVED" in d for d in inventory.diagnostics)


def test_production_root_raw_query_contract_resolves_expense_dao():
    """D2 production-root contract against the isolated worktree.

    Builds the room mutator inventory over the actual production source
    (``repo_root/app/src/main/java``) with the canonical raw-query policy
    (``config/guards/db_raw_query_classification.yml``).  Every ExpenseDao
    ``@RawQuery`` method the inventory discovers is derived from the actual
    inventory, its exact resolved signature is compared against the policy,
    and the canonical policy ExpenseDao entries must be exactly the
    discovered set (bidirectional equality: every discovered RawQuery has
    exactly one policy entry and every policy ExpenseDao entry is
    discovered).  All ExpenseDao raw queries must be read-classified and
    must not report raw-query/policy/unresolved diagnostics.
    Test/androidTest/debug/release roots are never inventoried.

    When the production ExpenseDao source or the canonical policy is missing
    the test fails with a controlled ``PRODUCTION_FIXTURE_UNAVAILABLE``
    assertion instead of silently skipping.
    """
    repo = _repo_root()
    production_java = repo / "app" / "src" / "main" / "java"
    policy = repo / "config" / "guards" / "db_raw_query_classification.yml"
    expense_dao_relative = Path("com/yourname/expensetracker/data/database/dao/ExpenseDao.kt")
    expense_dao_path = "app/src/main/java/com/yourname/expensetracker/data/database/dao/ExpenseDao.kt"
    expense_dao_fqcn = "com.yourname.expensetracker.data.database.dao.ExpenseDao"

    # Controlled assertion instead of a skip: this contract cannot pass when
    # the production fixture or the canonical policy is absent.
    missing_fixtures = []
    if not (production_java / expense_dao_relative).is_file():
        missing_fixtures.append(expense_dao_path)
    if not policy.is_file():
        missing_fixtures.append("config/guards/db_raw_query_classification.yml")
    assert not missing_fixtures, (
        "PRODUCTION_RAW_QUERY_CONTRACT_FIXTURE_UNAVAILABLE: missing "
        + ", ".join(missing_fixtures)
    )

    inventory = build_room_inventory(production_java, raw_query_policy=policy)

    # 1. The ExpenseDao must be discovered exactly once, at the canonical
    # production path.
    expense_daos = [dao for dao in inventory.daos if dao.fqcn == expense_dao_fqcn]
    assert len(expense_daos) == 1, (
        "PRODUCTION_RAW_QUERY_CONTRACT: expected exactly one discovered ExpenseDao, "
        f"found {len(expense_daos)}"
    )
    expense_dao = expense_daos[0]
    assert expense_dao.canonical_path == expense_dao_path

    # 2. Derive every discovered ExpenseDao @RawQuery from the actual
    # inventory.  ``DaoMethodId`` does not carry the annotation kind, so the
    # RawQuery subset is recovered from the same DAO accessor seam that
    # ``build_room_inventory`` uses, restricted to the discovered
    # ExpenseDao, and then confirmed to be recorded by the inventory.
    source = (production_java / expense_dao_relative).read_text(encoding="utf-8")
    raw_query_methods = {
        record.method
        for record in find_dao_method_annotations(source, expense_dao)
        if record.kind == "RawQuery"
    }
    assert raw_query_methods, (
        "PRODUCTION_RAW_QUERY_CONTRACT: no ExpenseDao @RawQuery methods discovered"
    )
    inventory_expense_dao_methods = {
        method for method in inventory.methods if method.dao.fqcn == expense_dao_fqcn
    }
    assert raw_query_methods <= inventory_expense_dao_methods, (
        "PRODUCTION_RAW_QUERY_CONTRACT: a discovered ExpenseDao @RawQuery method "
        "was not recorded by the inventory"
    )

    # 3. Derive each discovered signature exactly as the inventory resolves
    # it (the resolved canonical ``androidx.sqlite.db.SupportSQLiteQuery``),
    # and build the discovered identity set: (dao, method, receiver, params).
    discovered_keys = set()
    for method in raw_query_methods:
        receiver = (
            normalize_type_text(method.receiver) if method.receiver is not None else None
        )
        parameters = _resolve_raw_query_parameters(method, source)
        assert parameters is not None, (
            f"PRODUCTION_RAW_QUERY_CONTRACT: unresolved signature for "
            f"{expense_dao_fqcn}#{method.name}"
        )
        assert method.dao.canonical_path == expense_dao_path
        # The inventory records the exact source-level parameter type; the
        # canonical policy uses the resolved spelling.
        assert method.parameters == ("SupportSQLiteQuery",)
        discovered_keys.add((method.dao.fqcn, method.name, receiver, parameters))

    # 4. Load the canonical production RawQuery policy and require exact
    # bidirectional set equality.  Every discovered RawQuery must have
    # exactly one policy entry and every policy ExpenseDao entry must be a
    # discovered RawQuery.
    policy_data = yaml.safe_load(policy.read_text(encoding="utf-8"))
    assert policy_data["version"] == 1
    policy_expense_entries = [
        entry for entry in policy_data["methods"]
        if entry["dao"] == expense_dao_fqcn
    ]
    assert policy_expense_entries, (
        "PRODUCTION_RAW_QUERY_CONTRACT: no canonical policy ExpenseDao entries"
    )
    policy_counter = Counter(
        (
            entry["dao"],
            entry["method"],
            entry["signature"]["receiver"],
            tuple(entry["signature"]["parameters"]),
        )
        for entry in policy_expense_entries
    )
    assert all(policy_counter[key] == 1 for key in discovered_keys), (
        "PRODUCTION_RAW_QUERY_CONTRACT: a discovered RawQuery has no or multiple "
        "policy entries"
    )
    assert set(policy_counter) == discovered_keys, (
        "PRODUCTION_RAW_QUERY_CONTRACT: discovered RawQuery set and policy "
        "ExpenseDao set differ\n"
        f"policy-only={sorted(set(policy_counter) - discovered_keys)}\n"
        f"discovered-only={sorted(discovered_keys - set(policy_counter))}"
    )

    # 5. All ExpenseDao raw queries are read-classified: the canonical policy
    # says read and the inventory never classified any of them as a mutator.
    assert all(entry["classification"] == "read" for entry in policy_expense_entries), (
        "PRODUCTION_RAW_QUERY_CONTRACT: a policy ExpenseDao entry is not "
        "read-classified"
    )
    # Only ExpenseDao-owned RawQuery mutators violate the read-only contract.
    # Valid write-classified RawQueries belonging to other production DAOs
    # must not be rejected here.
    raw_mutators = [
        item
        for item in inventory.mutators
        if item.annotation == "RawQuery" and f"::{expense_dao_fqcn}#" in item.method
    ]
    assert not raw_mutators, (
        "PRODUCTION_RAW_QUERY_CONTRACT: an ExpenseDao @RawQuery was classified as "
        "a mutator"
    )

    # 6. No raw-query policy/unresolved diagnostics for production ExpenseDao
    # methods.  Duplicate/ambiguous callable identities are also covered so
    # the exact-one-policy-entry contract cannot hide behind an ambiguous
    # declaration.  STALE is included because the ExpenseDao contract is a
    # subset of the global bidirectional equality contract.
    raw_query_codes = (
        "DB_ROOM_RAW_QUERY_POLICY_REQUIRED",
        "DB_ROOM_RAW_QUERY_POLICY_STALE",
        "DB_ROOM_RAW_QUERY_POLICY_INVALID",
        "DB_SIGNATURE_UNRESOLVED",
        "DB_ROOM_DUPLICATE_METHOD",
        "DB_ROOM_MUTATOR_IDENTITY_AMBIGUOUS",
    )
    expense_dao_diagnostics = [
        diagnostic
        for diagnostic in inventory.diagnostics
        if expense_dao_path in diagnostic or expense_dao_fqcn in diagnostic
    ]
    assert not any(
        diag_code(diagnostic) in raw_query_codes
        for diagnostic in expense_dao_diagnostics
    ), expense_dao_diagnostics

    # 7. Only the approved production source root is inventoried; test,
    # androidTest, debug, and release roots are never scanned.
    assert inventory.daos
    inventoried_paths = [
        dao.canonical_path for dao in inventory.daos
    ] + [method.dao.canonical_path for method in inventory.methods]
    assert all(path.startswith("app/src/main/java/") for path in inventoried_paths)
    assert not any(
        path.startswith(forbidden_root)
        for path in inventoried_paths
        for forbidden_root in (
            "app/src/test/", "app/src/androidTest/", "app/src/debug/", "app/src/release/",
        )
    )


def test_production_root_raw_query_global_set_exact_equality():
    """D2 global contract: the complete production @RawQuery identity set
    derived from every discovered DAO equals the canonical policy key set
    bidirectionally.

    This is the superset of the ExpenseDao-specific production contract:
    it scans every discovered production DAO (not only ExpenseDao), derives
    every @RawQuery identity exactly as the inventory resolves signatures,
    loads the canonical policy, and requires exact bidirectional set
    equality:
      - discovered-but-unlisted  -> DB_ROOM_RAW_QUERY_POLICY_REQUIRED
      - policy-only/stale        -> DB_ROOM_RAW_QUERY_POLICY_STALE
      - duplicate key            -> DB_ROOM_RAW_QUERY_POLICY_INVALID
    The fixture policy under ``scripts/fixtures`` is never loaded by default.

    When the production source or the canonical policy is missing the test
    fails with a controlled ``PRODUCTION_FIXTURE_UNAVAILABLE`` assertion
    instead of silently skipping.
    """
    repo = _repo_root()
    production_java = repo / "app" / "src" / "main" / "java"
    policy_path = repo / "config" / "guards" / "db_raw_query_classification.yml"
    assert (production_java).is_dir(), (
        "PRODUCTION_RAW_QUERY_CONTRACT_FIXTURE_UNAVAILABLE: missing production java root"
    )
    assert policy_path.is_file(), (
        "PRODUCTION_RAW_QUERY_CONTRACT_FIXTURE_UNAVAILABLE: missing canonical policy"
    )

    inventory = build_room_inventory(production_java, raw_query_policy=policy_path)

    # 1. Derive the complete discovered @RawQuery identity set from every
    # discovered DAO, using the exact same accessor seam and signature
    # resolution the inventory uses.
    discovered_keys = set()
    for dao in inventory.daos:
        relative = dao.canonical_path[len("app/src/main/java/"):]
        source_path = production_java / relative
        source = source_path.read_text(encoding="utf-8")
        for record in find_dao_method_annotations(source, dao):
            if record.kind != "RawQuery":
                continue
            method = record.method
            receiver = (
                normalize_type_text(method.receiver) if method.receiver is not None else None
            )
            parameters = _resolve_raw_query_parameters(method, source)
            assert parameters is not None, (
                f"PRODUCTION_RAW_QUERY_CONTRACT: unresolved signature for "
                f"{method.dao.fqcn}#{method.name}"
            )
            discovered_keys.add((method.dao.fqcn, method.name, receiver, parameters))

    # 2. Load the canonical policy and compare bidirectionally as complete
    # sets (every DAO, not only ExpenseDao).
    policy_data = yaml.safe_load(policy_path.read_text(encoding="utf-8"))
    assert policy_data["version"] == 1
    policy_keys = {
        (
            entry["dao"],
            entry["method"],
            entry["signature"]["receiver"],
            tuple(entry["signature"]["parameters"]),
        )
        for entry in policy_data["methods"]
    }
    assert policy_keys, (
        "PRODUCTION_RAW_QUERY_CONTRACT: canonical policy has no RawQuery entries"
    )
    assert set(policy_keys) == discovered_keys, (
        "PRODUCTION_RAW_QUERY_CONTRACT: global discovered RawQuery set and policy "
        "set differ\n"
        f"policy-only={sorted(policy_keys - discovered_keys)}\n"
        f"discovered-only={sorted(discovered_keys - policy_keys)}"
    )

    # 3. The inventory itself reports no raw-query policy diagnostics
    # globally: exact equality means no REQUIRED, no STALE, and no INVALID.
    for code in (
        "DB_ROOM_RAW_QUERY_POLICY_REQUIRED",
        "DB_ROOM_RAW_QUERY_POLICY_STALE",
        "DB_ROOM_RAW_QUERY_POLICY_INVALID",
        "DB_SIGNATURE_UNRESOLVED",
    ):
        assert not any(
            diag_code(diagnostic) == code for diagnostic in inventory.diagnostics
        ), (code, inventory.diagnostics)

    # 4. Every discovered RawQuery is read-classified: none of them is a
    # mutator (the canonical policy says read for all production raw queries).
    raw_mutators = [
        item for item in inventory.mutators if item.annotation == "RawQuery"
    ]
    assert not raw_mutators, (
        "PRODUCTION_RAW_QUERY_CONTRACT: a production @RawQuery was classified "
        "as a mutator"
    )


def test_production_root_raw_query_policy_has_no_fixture_entries():
    """The canonical production RawQuery policy never references the fixture
    DAO; the fixture policy is never loaded by default."""
    repo = _repo_root()
    policy_path = repo / "config" / "guards" / "db_raw_query_classification.yml"
    fixture_path = Path(__file__).resolve().parent / "fixtures" / "db_raw_query_classification.yml"
    assert policy_path.is_file()
    assert fixture_path.is_file()
    policy = yaml.safe_load(policy_path.read_text(encoding="utf-8"))
    fixture = yaml.safe_load(fixture_path.read_text(encoding="utf-8"))
    fixture_daos = {entry["dao"] for entry in fixture["methods"]}
    assert all(entry["dao"] not in fixture_daos for entry in policy["methods"]), (
        "PRODUCTION_RAW_QUERY_CONTRACT: fixture DAO leaked into canonical policy"
    )


def test_fixture_raw_query_policy_is_not_used_by_default(tmp_path):
    inventory = _inventory(
        tmp_path,
        "package com.example\n@Dao interface RepairDao { @RawQuery fun executeRepair(query: androidx.sqlite.db.SupportSQLiteQuery) }\n",
    )
    assert not inventory.mutators
    assert any(d.startswith("DB_ROOM_RAW_QUERY_POLICY_REQUIRED:") for d in inventory.diagnostics)


def test_stale_policy_only_raw_query_entry_fails_closed(tmp_path):
    """A canonical policy entry whose exact callable identity is never
    discovered fails the inventory closed with
    ``DB_ROOM_RAW_QUERY_POLICY_STALE``; it is never silently ignored."""
    policy = {"version": 1, "methods": [{
        "dao": "example.D", "method": "execute",
        "signature": {"receiver": None, "parameters": ["androidx.sqlite.db.SupportSQLiteQuery"]},
        "classification": "write", "reason": "Controlled repair query",
        "owner": "@panospao7", "linked_issue": "MIT-003",
    }]}
    inventory = _inventory(
        tmp_path,
        "package example\n@Dao interface D { @Insert fun put(v: Item) }\n",
        policy=policy,
    )
    # The source contains a DAO but no @RawQuery at all: the policy entry has
    # no discovered counterpart, so the exact equality contract fails closed
    # with STALE and never classifies anything AS a mutator through the
    # raw-query channel.  Per the trust contract (docs/ci/DB_ROOM_INVENTORY.md
    # §4/§4.2) the diagnostic-bearing run is untrusted and every consumer must
    # reject it; the only mutator present is the unrelated direct @Insert
    # declaration, preserved as diagnostic context — never a
    # ROOM_MUTATING_QUERY derived from the stale write classification.
    assert [
        (item.mutation_kind, item.annotation) for item in inventory.mutators
    ] == [("ROOM_INSERT", "Insert")]
    assert any(d.startswith("DB_ROOM_RAW_QUERY_POLICY_STALE:") for d in inventory.diagnostics)
    assert not any(d.startswith("DB_ROOM_RAW_QUERY_POLICY_REQUIRED:") for d in inventory.diagnostics)


def test_stale_policy_only_raw_query_entry_for_missing_dao_fails_closed(tmp_path):
    """A policy entry for a DAO that is not discovered at all is stale and
    fails closed, even when a different DAO exists in the source."""
    policy = {"version": 1, "methods": [{
        "dao": "example.MissingDao", "method": "execute",
        "signature": {"receiver": None, "parameters": ["androidx.sqlite.db.SupportSQLiteQuery"]},
        "classification": "write", "reason": "Controlled repair query",
        "owner": "@panospao7", "linked_issue": "MIT-003",
    }]}
    inventory = _inventory(
        tmp_path,
        "package example\n@Dao interface D { @RawQuery fun execute(query: androidx.sqlite.db.SupportSQLiteQuery) }\n",
        policy=policy,
    )
    assert any(d.startswith("DB_ROOM_RAW_QUERY_POLICY_STALE:") for d in inventory.diagnostics)
    assert not inventory.mutators


def test_discovered_unlisted_raw_query_fails_closed_across_daos(tmp_path):
    """The global contract is per-callable identity, not per-file or
    per-DAO: a discovered @RawQuery without a policy entry fails closed with
    ``DB_ROOM_RAW_QUERY_POLICY_REQUIRED`` even when another DAO's raw query
    is exactly covered by the policy."""
    policy = {"version": 1, "methods": [{
        "dao": "example.First", "method": "covered",
        "signature": {"receiver": None, "parameters": ["androidx.sqlite.db.SupportSQLiteQuery"]},
        "classification": "read", "reason": "Controlled read query",
        "owner": "@panospao7", "linked_issue": "MIT-003",
    }]}
    inventory = _inventory(
        tmp_path,
        """package example
@Dao interface First { @RawQuery fun covered(query: androidx.sqlite.db.SupportSQLiteQuery) }
@Dao interface Second { @RawQuery fun unlisted(query: androidx.sqlite.db.SupportSQLiteQuery) }
""",
        policy=policy,
    )
    # ``covered`` is read-classified (never a mutator); ``unlisted`` fails
    # closed with REQUIRED and is never a mutator.
    assert not inventory.mutators
    assert any(d.startswith("DB_ROOM_RAW_QUERY_POLICY_REQUIRED:") for d in inventory.diagnostics)
    assert not any(d.startswith("DB_ROOM_RAW_QUERY_POLICY_STALE:") for d in inventory.diagnostics)


def test_inherited_raw_query_without_child_policy_entry_is_required(tmp_path):
    """An inherited @RawQuery is an effective identity owned by the child DAO:
    the global equality contract derives it after inheritance fixed-point
    resolution, so a parent-only policy entry fails closed with
    ``DB_ROOM_RAW_QUERY_POLICY_REQUIRED`` for the child identity.  The parent
    entry is still discovered, so no STALE is reported."""
    policy = {"version": 1, "methods": [{
        "dao": "example.Base", "method": "execute",
        "signature": {"receiver": None, "parameters": ["androidx.sqlite.db.SupportSQLiteQuery"]},
        "classification": "read", "reason": "Controlled read query",
        "owner": "@panospao7", "linked_issue": "MIT-003",
    }]}
    inventory = _inventory(
        tmp_path,
        """package example
import androidx.sqlite.db.SupportSQLiteQuery
@Dao interface Base { @RawQuery fun execute(query: SupportSQLiteQuery) }
@Dao interface Child : Base
""",
        policy=policy,
    )
    assert not inventory.mutators
    assert any(d.startswith("DB_ROOM_RAW_QUERY_POLICY_REQUIRED:") for d in inventory.diagnostics)
    assert not any(d.startswith("DB_ROOM_RAW_QUERY_POLICY_STALE:") for d in inventory.diagnostics)
    assert not any("INHERITED_AMBIGUOUS" in d for d in inventory.diagnostics)


def test_inherited_raw_query_with_child_policy_entry_is_exact_equality(tmp_path):
    """When the canonical policy carries both the declaring DAO's and the
    child DAO's exact identities, the effective discovered set equals the
    policy key set bidirectionally: no REQUIRED, no STALE, no INVALID, and
    the read-classified raw queries are never mutators."""
    policy = {"version": 1, "methods": [
        {
            "dao": "example.Base", "method": "execute",
            "signature": {"receiver": None, "parameters": ["androidx.sqlite.db.SupportSQLiteQuery"]},
            "classification": "read", "reason": "Controlled read query",
            "owner": "@panospao7", "linked_issue": "MIT-003",
        },
        {
            "dao": "example.Child", "method": "execute",
            "signature": {"receiver": None, "parameters": ["androidx.sqlite.db.SupportSQLiteQuery"]},
            "classification": "read", "reason": "Inherited read query",
            "owner": "@panospao7", "linked_issue": "MIT-003",
        },
    ]}
    inventory = _inventory(
        tmp_path,
        """package example
import androidx.sqlite.db.SupportSQLiteQuery
@Dao interface Base { @RawQuery fun execute(query: SupportSQLiteQuery) }
@Dao interface Child : Base
""",
        policy=policy,
    )
    assert not inventory.mutators
    assert not any(d.startswith("DB_ROOM_RAW_QUERY_POLICY_") for d in inventory.diagnostics)
    assert not any("SIGNATURE_UNRESOLVED" in d for d in inventory.diagnostics)
    assert not any("INHERITED_AMBIGUOUS" in d for d in inventory.diagnostics)


def test_inherited_raw_query_transitive_chain_requires_every_effective_identity(tmp_path):
    """Effective inherited identities are resolved as a fixed point: a
    grandchild exposing an inherited @RawQuery is its own identity, so the
    policy must carry a GrandChild entry even when Base and Middle already
    have entries.  Middle's inherited identity is discovered, so only the
    GrandChild identity is REQUIRED and nothing is STALE."""
    policy = {"version": 1, "methods": [
        {
            "dao": "example.Base", "method": "execute",
            "signature": {"receiver": None, "parameters": ["androidx.sqlite.db.SupportSQLiteQuery"]},
            "classification": "read", "reason": "Controlled read query",
            "owner": "@panospao7", "linked_issue": "MIT-003",
        },
        {
            "dao": "example.Middle", "method": "execute",
            "signature": {"receiver": None, "parameters": ["androidx.sqlite.db.SupportSQLiteQuery"]},
            "classification": "read", "reason": "Inherited read query",
            "owner": "@panospao7", "linked_issue": "MIT-003",
        },
    ]}
    inventory = _inventory(
        tmp_path,
        """package example
import androidx.sqlite.db.SupportSQLiteQuery
@Dao interface Base { @RawQuery fun execute(query: SupportSQLiteQuery) }
@Dao interface Middle : Base
@Dao interface GrandChild : Middle
""",
        policy=policy,
    )
    assert any(d.startswith("DB_ROOM_RAW_QUERY_POLICY_REQUIRED:") for d in inventory.diagnostics)
    assert not any(d.startswith("DB_ROOM_RAW_QUERY_POLICY_STALE:") for d in inventory.diagnostics)


def test_inherited_raw_query_stale_child_entry_when_child_declares_own_method(tmp_path):
    """A child policy entry is never silently assumed: when the child's own
    declaration replaces the inherited @RawQuery (declaration-only policy is
    not a RawQuery), the child no longer effectively exposes that RawQuery
    and the child policy entry fails closed with
    ``DB_ROOM_RAW_QUERY_POLICY_STALE``."""
    policy = {"version": 1, "methods": [
        {
            "dao": "example.Base", "method": "execute",
            "signature": {"receiver": None, "parameters": ["androidx.sqlite.db.SupportSQLiteQuery"]},
            "classification": "read", "reason": "Controlled read query",
            "owner": "@panospao7", "linked_issue": "MIT-003",
        },
        {
            "dao": "example.Child", "method": "execute",
            "signature": {"receiver": None, "parameters": ["androidx.sqlite.db.SupportSQLiteQuery"]},
            "classification": "read", "reason": "Inherited read query",
            "owner": "@panospao7", "linked_issue": "MIT-003",
        },
    ]}
    inventory = _inventory(
        tmp_path,
        """package example
import androidx.sqlite.db.SupportSQLiteQuery
@Dao interface Base { @RawQuery fun execute(query: SupportSQLiteQuery) }
@Dao interface Child : Base { @Query("SELECT 1") fun execute(query: SupportSQLiteQuery) }
""",
        policy=policy,
    )
    assert any(d.startswith("DB_ROOM_RAW_QUERY_POLICY_STALE:") for d in inventory.diagnostics)
    assert not any(d.startswith("DB_ROOM_RAW_QUERY_POLICY_REQUIRED:") for d in inventory.diagnostics)


def test_inherited_raw_query_ambiguous_parents_fail_closed(tmp_path):
    """An inherited @RawQuery identity exposed by multiple parents is
    ambiguous: the child identity is never claimed as an exact policy match
    (``DB_ROOM_RAW_QUERY_POLICY_INHERITED_AMBIGUOUS``), so a child policy
    entry with no claimed counterpart is STALE and no REQUIRED is invented
    for the ambiguous identity."""
    policy = {"version": 1, "methods": [
        {
            "dao": "example.First", "method": "execute",
            "signature": {"receiver": None, "parameters": ["androidx.sqlite.db.SupportSQLiteQuery"]},
            "classification": "read", "reason": "Controlled read query",
            "owner": "@panospao7", "linked_issue": "MIT-003",
        },
        {
            "dao": "example.Second", "method": "execute",
            "signature": {"receiver": None, "parameters": ["androidx.sqlite.db.SupportSQLiteQuery"]},
            "classification": "read", "reason": "Controlled read query",
            "owner": "@panospao7", "linked_issue": "MIT-003",
        },
        {
            "dao": "example.Child", "method": "execute",
            "signature": {"receiver": None, "parameters": ["androidx.sqlite.db.SupportSQLiteQuery"]},
            "classification": "read", "reason": "Inherited read query",
            "owner": "@panospao7", "linked_issue": "MIT-003",
        },
    ]}
    inventory = _inventory(
        tmp_path,
        """package example
import androidx.sqlite.db.SupportSQLiteQuery
@Dao interface First { @RawQuery fun execute(query: SupportSQLiteQuery) }
@Dao interface Second { @RawQuery fun execute(query: SupportSQLiteQuery) }
@Dao interface Child : First, Second
""",
        policy=policy,
    )
    assert any(
        d.startswith("DB_ROOM_RAW_QUERY_POLICY_INHERITED_AMBIGUOUS:")
        for d in inventory.diagnostics
    )
    # The ambiguous child identity is never claimed as equality.
    assert any(d.startswith("DB_ROOM_RAW_QUERY_POLICY_STALE:") for d in inventory.diagnostics)
    assert not any(d.startswith("DB_ROOM_RAW_QUERY_POLICY_REQUIRED:") for d in inventory.diagnostics)


def test_inherited_raw_query_unresolved_parent_child_entry_is_stale(tmp_path):
    """A child whose parent cannot be resolved never claims inherited
    identities: the child policy entry is STALE and the broken chain emits
    the controlled inheritance diagnostic (fail closed, never a guess)."""
    policy = {"version": 1, "methods": [
        {
            "dao": "example.Base", "method": "execute",
            "signature": {"receiver": None, "parameters": ["androidx.sqlite.db.SupportSQLiteQuery"]},
            "classification": "read", "reason": "Controlled read query",
            "owner": "@panospao7", "linked_issue": "MIT-003",
        },
        {
            "dao": "example.Child", "method": "execute",
            "signature": {"receiver": None, "parameters": ["androidx.sqlite.db.SupportSQLiteQuery"]},
            "classification": "read", "reason": "Inherited read query",
            "owner": "@panospao7", "linked_issue": "MIT-003",
        },
    ]}
    inventory = _inventory(
        tmp_path,
        """package example
import androidx.sqlite.db.SupportSQLiteQuery
@Dao interface Base { @RawQuery fun execute(query: SupportSQLiteQuery) }
@Dao interface Child : Missing
""",
        policy=policy,
    )
    assert any(d.startswith("DB_DAO_INHERITANCE_UNRESOLVED:") for d in inventory.diagnostics)
    assert any(d.startswith("DB_ROOM_RAW_QUERY_POLICY_STALE:") for d in inventory.diagnostics)
    assert not any(d.startswith("DB_ROOM_RAW_QUERY_POLICY_REQUIRED:") for d in inventory.diagnostics)


def test_inherited_raw_query_parent_read_child_write_uses_child_owned_mutator(tmp_path):
    """A child's own write-classified @RawQuery is evaluated on the
    child-owned identity, never on the parent's read classification: the
    child contributes a ``ROOM_MUTATING_QUERY`` mutator while the read parent
    contributes none, and the child mutator is owned by the child DAO."""
    policy = {"version": 1, "methods": [
        _raw_entry("example.Base", "read"),
        _raw_entry("example.Child", "write"),
    ]}
    inventory = _inventory(
        tmp_path,
        """package example
import androidx.sqlite.db.SupportSQLiteQuery
@Dao interface Base { @RawQuery fun execute(query: SupportSQLiteQuery) }
@Dao interface Child : Base { @RawQuery fun execute(query: SupportSQLiteQuery) }
""",
        policy=policy,
    )
    assert len(inventory.mutators) == 1
    assert "example.Child#" in inventory.mutators[0].method
    assert "example.Base#" not in inventory.mutators[0].method
    assert inventory.mutators[0].mutation_kind == "ROOM_MUTATING_QUERY"
    assert inventory.mutators[0].inherited_from is None
    assert not any(d.startswith("DB_ROOM_RAW_QUERY_POLICY_") for d in inventory.diagnostics)


def test_inherited_raw_query_bodyless_child_write_policy_emits_child_owned_mutator(tmp_path):
    """A bodyless child exposing an inherited @RawQuery is its own policy
    identity with the exact same callable signature: the parent entry is
    read-classified (so the parent contributes no mutator) while the
    child-owned entry is write-classified, so the child contributes exactly
    one ``ROOM_MUTATING_QUERY`` mutator for the inherited callable with
    ``inherited_from`` pointing at the parent DAO.  The exact bidirectional
    policy equality holds: no REQUIRED, STALE, INVALID, or AMBIGUOUS
    diagnostics for these two entries."""
    policy = {"version": 1, "methods": [
        _raw_entry("example.Base", "read"),
        _raw_entry("example.Child", "write"),
    ]}
    inventory = _inventory(
        tmp_path,
        """package example
import androidx.sqlite.db.SupportSQLiteQuery
@Dao interface Base { @RawQuery fun execute(query: SupportSQLiteQuery) }
@Dao interface Child : Base
""",
        policy=policy,
    )
    assert len(inventory.mutators) == 1
    mutator = inventory.mutators[0]
    assert mutator.mutation_kind == "ROOM_MUTATING_QUERY"
    assert mutator.annotation == "RawQuery"
    assert mutator.method == (
        "app/src/main/java/example/Fixtures.kt::example.Child"
        "#execute(androidx.sqlite.db.SupportSQLiteQuery)"
    )
    assert "example.Base#" not in mutator.method
    assert mutator.inherited_from == "example.Base"
    for code in (
        "DB_ROOM_RAW_QUERY_POLICY_REQUIRED",
        "DB_ROOM_RAW_QUERY_POLICY_STALE",
        "DB_ROOM_RAW_QUERY_POLICY_INVALID",
        "DB_ROOM_RAW_QUERY_POLICY_INHERITED_AMBIGUOUS",
    ):
        assert not any(diag_code(d) == code for d in inventory.diagnostics), (code, inventory.diagnostics)


def test_inherited_raw_query_parent_write_child_read_shadows_inherited_mutator(tmp_path):
    """A child's own read-classified @RawQuery shadows the parent's write
    classification: the inherited write mutator is never emitted for the
    child, so only the parent DAO contributes a mutator."""
    policy = {"version": 1, "methods": [
        _raw_entry("example.Base", "write"),
        _raw_entry("example.Child", "read"),
    ]}
    inventory = _inventory(
        tmp_path,
        """package example
import androidx.sqlite.db.SupportSQLiteQuery
@Dao interface Base { @RawQuery fun execute(query: SupportSQLiteQuery) }
@Dao interface Child : Base { @RawQuery fun execute(query: SupportSQLiteQuery) }
""",
        policy=policy,
    )
    assert len(inventory.mutators) == 1
    assert "example.Base#" in inventory.mutators[0].method
    assert not any("example.Child#" in item.method for item in inventory.mutators)
    assert not any(d.startswith("DB_ROOM_RAW_QUERY_POLICY_") for d in inventory.diagnostics)


def test_inherited_raw_query_direct_child_non_raw_query_shadow(tmp_path):
    """A child direct declaration shadows the inherited parent callable
    regardless of annotation kind: a non-RawQuery child method with the same
    exact callable identity removes the inherited @RawQuery identity, so the
    child never contributes the parent's write mutator and the child policy
    entry fails closed as STALE."""
    policy = {"version": 1, "methods": [
        _raw_entry("example.Base", "write"),
        _raw_entry("example.Child", "write"),
    ]}
    inventory = _inventory(
        tmp_path,
        """package example
import androidx.sqlite.db.SupportSQLiteQuery
@Dao interface Base { @RawQuery fun execute(query: SupportSQLiteQuery) }
@Dao interface Child : Base { @Query("SELECT 1") fun execute(query: SupportSQLiteQuery) }
""",
        policy=policy,
    )
    assert len(inventory.mutators) == 1
    assert "example.Base#" in inventory.mutators[0].method
    assert not any("example.Child#" in item.method for item in inventory.mutators)
    assert any(d.startswith("DB_ROOM_RAW_QUERY_POLICY_STALE:") for d in inventory.diagnostics)
    assert not any(d.startswith("DB_ROOM_RAW_QUERY_POLICY_REQUIRED:") for d in inventory.diagnostics)


def test_inherited_raw_query_child_missing_policy_fails_closed(tmp_path):
    """An inherited @RawQuery whose child-owned identity has no policy entry
    fails closed: ``DB_ROOM_RAW_QUERY_POLICY_REQUIRED`` is emitted and no
    mutator is claimed for the child even though a write-classified parent
    declares the callable.  The parent's own mutator is preserved."""
    policy = {"version": 1, "methods": [
        _raw_entry("example.Base", "write"),
    ]}
    inventory = _inventory(
        tmp_path,
        """package example
import androidx.sqlite.db.SupportSQLiteQuery
@Dao interface Base { @RawQuery fun execute(query: SupportSQLiteQuery) }
@Dao interface Child : Base
""",
        policy=policy,
    )
    assert len(inventory.mutators) == 1
    assert "example.Base#" in inventory.mutators[0].method
    assert not any("example.Child#" in item.method for item in inventory.mutators)
    assert any(d.startswith("DB_ROOM_RAW_QUERY_POLICY_REQUIRED:") for d in inventory.diagnostics)
    assert not any(d.startswith("DB_ROOM_RAW_QUERY_POLICY_STALE:") for d in inventory.diagnostics)


def test_inherited_raw_query_transitive_override_shadows_deep_chain(tmp_path):
    """A read override at a deep level wins over the transitive write
    identity: Base and Middle (write-classified) keep their mutators, while
    GrandChild's read declaration shadows the inherited write identity at the
    grandchild and contributes no mutator."""
    policy = {"version": 1, "methods": [
        _raw_entry("example.Base", "write"),
        _raw_entry("example.Middle", "write"),
        _raw_entry("example.GrandChild", "read"),
    ]}
    inventory = _inventory(
        tmp_path,
        """package example
import androidx.sqlite.db.SupportSQLiteQuery
@Dao interface Base { @RawQuery fun execute(query: SupportSQLiteQuery) }
@Dao interface Middle : Base
@Dao interface GrandChild : Middle { @RawQuery fun execute(query: SupportSQLiteQuery) }
""",
        policy=policy,
    )
    assert len(inventory.mutators) == 2
    assert any("example.Base#" in item.method for item in inventory.mutators)
    assert any("example.Middle#" in item.method for item in inventory.mutators)
    middle = next(item for item in inventory.mutators if "example.Middle#" in item.method)
    assert middle.inherited_from == "example.Base"
    assert not any("example.GrandChild#" in item.method for item in inventory.mutators)
    assert not any(d.startswith("DB_ROOM_RAW_QUERY_POLICY_") for d in inventory.diagnostics)


def test_inherited_mutators_record_inherited_from(tmp_path):
    inventory = _inventory(tmp_path, """package example
        @Dao interface Base { @Insert fun create(v: Item) }
        @Dao interface Child : Base
    """)
    inherited = [item for item in inventory.mutators if "Child#" in item.method]
    assert len(inherited) == 1
    assert inherited[0].inherited_from == "example.Base"


def test_bodyless_dao_child_inherits_exact_parent_mutator(tmp_path):
    """An exact bodyless ``@Dao interface Child : Base`` is a real DAO with no
    direct methods; it inherits only through its declared parent."""
    inventory = _inventory(tmp_path, """package example
        @Dao interface Child : Base
        @Dao interface Base { @Insert fun create(v: Item) }
    """, policy={"version": 1, "methods": []})
    assert {dao.fqcn for dao in inventory.daos} == {"example.Base", "example.Child"}
    assert all(method.dao.fqcn != "example.Child" for method in inventory.methods)
    inherited = [item for item in inventory.mutators if "example.Child#" in item.method]
    assert len(inherited) == 1
    assert inherited[0].method == "app/src/main/java/example/Fixtures.kt::example.Child#create(Item)"
    assert inherited[0].inherited_from == "example.Base"
    assert not inventory.diagnostics


def test_bodyless_abstract_dao_child_inherits_parent_mutator(tmp_path):
    inventory = _inventory(tmp_path, """package example
        @Dao abstract class Child : Base
        @Dao interface Base { @Delete fun remove(v: Item) }
    """, policy={"version": 1, "methods": []})
    assert {dao.fqcn for dao in inventory.daos} == {"example.Base", "example.Child"}
    inherited = [item for item in inventory.mutators if "example.Child#" in item.method]
    assert len(inherited) == 1
    assert inherited[0].method.endswith("#remove(Item)")
    assert inherited[0].inherited_from == "example.Base"
    assert not inventory.diagnostics


def test_bodyless_dao_keeps_enclosing_owner_for_inheritance(tmp_path):
    """A bodyless nested DAO keeps its lexical owner in its FQCN and resolves
    parents through the exact enclosing scope."""
    inventory = _inventory(tmp_path, """package example
        class Holder {
            @Dao interface Child : Base
            @Dao interface Base { @Insert fun create(v: Item) }
        }
    """, policy={"version": 1, "methods": []})
    assert {dao.fqcn for dao in inventory.daos} == {"example.Holder.Base", "example.Holder.Child"}
    inherited = [item for item in inventory.mutators if "example.Holder.Child#" in item.method]
    assert len(inherited) == 1
    assert inherited[0].inherited_from == "example.Holder.Base"
    assert not inventory.diagnostics


def test_bodyless_dao_direct_method_discovery_is_empty_and_never_swallows_sibling(tmp_path):
    """Direct method discovery on a bodyless DAO returns empty even when a
    following sibling declares its own body; sibling mutators stay with their
    declared owners and are only inherited through the declared parent."""
    _write(tmp_path, "app/src/main/java/example/Fixtures.kt", """package example
@Dao interface Child : Base
@Dao interface Base { @Insert fun create(v: Item) }
@Dao interface Other { @Delete fun remove(v: Item) }
""")
    inventory = build_room_inventory(tmp_path, {"version": 1, "methods": []})
    child = next(dao for dao in inventory.daos if dao.fqcn == "example.Child")
    source = (tmp_path / "app/src/main/java/example/Fixtures.kt").read_text(encoding="utf-8")
    assert find_dao_method_annotations(source, child) == ()
    assert all(method.dao.fqcn != "example.Child" for method in inventory.methods)
    assert {item.method for item in inventory.mutators} == {
        "app/src/main/java/example/Fixtures.kt::example.Base#create(Item)",
        "app/src/main/java/example/Fixtures.kt::example.Child#create(Item)",
        "app/src/main/java/example/Fixtures.kt::example.Other#remove(Item)",
    }
    assert not inventory.diagnostics


@pytest.mark.parametrize("declaration", [
    "@Dao interface Child : Base,",  # trailing comma in the parent list
    "@Dao interface Child : ",       # empty parent list
])
def test_malformed_bodyless_dao_parent_header_fails_closed(tmp_path, declaration):
    """A malformed bodyless parent header fails closed with a controlled
    diagnostic and never invents an inherited mutator; the sibling parent DAO
    keeps its own mutator."""
    inventory = _inventory(tmp_path, f"""package example
        {declaration}
        @Dao interface Base {{ @Insert fun create(v: Item) }}
    """)
    assert any(d.startswith("DB_DAO_INHERITANCE_UNRESOLVED:") for d in inventory.diagnostics)
    assert not any("Child#create(Item)" in item.method for item in inventory.mutators)
    assert any("Base#create(Item)" in item.method for item in inventory.mutators)


@pytest.mark.parametrize("declaration", [
    "@Dao interface Child : Base<",  # unclosed generic in the header
    "@Dao interface : Missing",      # missing declaration name
])
def test_malformed_bodyless_dao_declaration_fails_closed_file_wide(tmp_path, declaration):
    """A malformed bodyless declaration fails the whole file closed with a
    controlled diagnostic; no partial DAO/mutator inventory is produced."""
    inventory = _inventory(tmp_path, f"""package example
        {declaration}
        @Dao interface Base {{ @Insert fun create(v: Item) }}
    """)
    assert any(d.startswith("DB_ROOM_UNSUPPORTED_DECLARATION:") for d in inventory.diagnostics)
    assert not inventory.daos and not inventory.methods and not inventory.mutators


def test_bodyless_dao_unresolved_parent_fails_closed(tmp_path):
    """A bodyless child whose parent cannot be resolved fails closed and never
    leaks a resolvable sibling parent's mutator into the child."""
    inventory = _inventory(tmp_path, """package example
        @Dao interface Child : Missing
        @Dao interface Base { @Insert fun create(v: Item) }
    """)
    assert any(d.startswith("DB_DAO_INHERITANCE_UNRESOLVED:") for d in inventory.diagnostics)
    assert not any("Child#" in item.method for item in inventory.mutators)
    assert any("Base#create(Item)" in item.method for item in inventory.mutators)


def test_imported_and_alias_parent_resolution(tmp_path):
    _write(tmp_path, "app/src/main/java/example/Base.kt", "package example\n@Dao interface Base { @Insert fun create(v: Item) }\n")
    _write(tmp_path, "app/src/main/java/other/Child.kt", "package other\nimport example.Base\n@Dao interface Child : Base {}\n")
    inventory = build_room_inventory(tmp_path)
    assert any("other.Child#create(Item)" in item.method for item in inventory.mutators)
    _write(tmp_path, "app/src/main/java/other/AliasChild.kt", "package other\nimport example.Base as Parent\n@Dao interface AliasChild : Parent {}\n")
    inventory = build_room_inventory(tmp_path)
    assert any("other.AliasChild#create(Item)" in item.method for item in inventory.mutators)


def test_duplicate_same_fqcn_declarations_block_inheritance(tmp_path):
    _write(tmp_path, "app/src/main/java/example/BaseOne.kt", "package example\n@Dao interface Base { @Insert fun create(v: Item) }\n")
    _write(tmp_path, "app/src/main/java/example/BaseTwo.kt", "package example\n@Dao interface Base { @Insert fun create(v: Item) }\n")
    _write(tmp_path, "app/src/main/java/example/Child.kt", "package example\n@Dao interface Child : Base {}\n")
    inventory = build_room_inventory(tmp_path)
    assert not any("Child#create" in item.method for item in inventory.mutators)
    assert any("DB_DAO_INHERITANCE_UNRESOLVED" in diagnostic for diagnostic in inventory.diagnostics)


def test_malformed_dao_parent_declaration_fails_closed(tmp_path):
    inventory = _inventory(tmp_path, "package example\n@Dao interface Child : Base, {}\n")
    assert any(d.startswith("DB_DAO_INHERITANCE_UNRESOLVED:") for d in inventory.diagnostics)
    diagnostic = next(d for d in inventory.diagnostics if d.startswith("DB_DAO_INHERITANCE_UNRESOLVED:"))
    assert "app/src/main/java/example/Fixtures.kt" in diagnostic
    assert "example.Child" in diagnostic


def test_malformed_child_does_not_silently_omit_parent_mutator(tmp_path):
    inventory = _inventory(tmp_path, """package example
        @Dao interface Child : Base, {}
        @Dao interface Base { @Insert fun create(v: Item) }
    """)
    assert any("Base#create(Item)" in item.method for item in inventory.mutators)
    assert not any("Child#create(Item)" in item.method for item in inventory.mutators)
    assert any(d.startswith("DB_DAO_INHERITANCE_UNRESOLVED:") for d in inventory.diagnostics)


def test_unresolved_parent_does_not_allow_valid_sibling_parent_mutators(tmp_path):
    inventory = _inventory(tmp_path, """package example
        @Dao interface Good { @Insert fun create(v: Item) }
        @Dao interface Child : Good, Missing {}
    """)
    assert any("Good#create(Item)" in item.method for item in inventory.mutators)
    assert not any("Child#create(Item)" in item.method for item in inventory.mutators)
    assert any("DB_DAO_INHERITANCE_UNRESOLVED" in diagnostic for diagnostic in inventory.diagnostics)


def test_dao_inheritance_cycle_fails_closed(tmp_path):
    inventory = _inventory(tmp_path, """package example
        @Dao interface A : B {}
        @Dao interface B : A {}
    """)
    assert not inventory.mutators
    assert any("DB_DAO_INHERITANCE_UNRESOLVED" in diagnostic for diagnostic in inventory.diagnostics)


def test_dao_parent_parser_failure_fails_closed(tmp_path, monkeypatch):
    def fail(_source):
        raise room_inventory.ParserError("PARSER_ERROR")

    monkeypatch.setattr(room_inventory, "mask_kotlin_source", fail)
    inventory = _inventory(tmp_path, "package example\n@Dao interface Child : Base {}\n")
    assert any(d.startswith("DB_DAO_INHERITANCE_UNRESOLVED:") for d in inventory.diagnostics)
    assert not any("ParserError" in d or "PARSER_ERROR" in d for d in inventory.diagnostics)


def test_inheritance_is_independent_of_source_order_and_transitive(tmp_path):
    inventory = _inventory(tmp_path, """package example
        @Dao interface Child : Middle {}
        @Dao interface Middle : Base {}
        @Dao interface Base { @Insert fun create(v: Item) }
    """)
    assert any("Child#" in item.method for item in inventory.mutators)
    assert any("Middle#" in item.method for item in inventory.mutators)


def test_same_file_nested_parent_resolution_is_lexical(tmp_path):
    inventory = _inventory(tmp_path, """package example
        class Left { @Dao interface Parent { @Insert fun left(v: Item) } }
        class Right { @Dao interface Parent { @Insert fun right(v: Item) } }
        class RightChild { @Dao interface Child : Parent {} }
    """)
    assert not any("RightChild.Child#" in item.method for item in inventory.mutators)
    assert any(d.startswith("DB_DAO_INHERITANCE_UNRESOLVED:") for d in inventory.diagnostics)


def test_nested_parent_name_prefers_its_enclosing_scope(tmp_path):
    inventory = _inventory(tmp_path, """package example
        class Left {
            @Dao interface Parent { @Insert fun left(v: Item) }
            @Dao interface Child : Parent {}
        }
        class Right { @Dao interface Parent { @Insert fun right(v: Item) } }
    """)
    assert any("example.Left.Child#left(Item)" in item.method for item in inventory.mutators)
    assert not any(d.startswith("DB_DAO_INHERITANCE_UNRESOLVED:") for d in inventory.diagnostics)


def test_same_file_distinct_parents_resolve_without_simple_name_search(tmp_path):
    inventory = _inventory(tmp_path, """package example
        @Dao interface Writes { @Insert fun write(v: Item) }
        @Dao interface Deletes { @Delete fun remove(v: Item) }
        @Dao interface Child : Writes, Deletes {}
    """)
    assert {item.method.rsplit("#", 1)[1] for item in inventory.mutators if "Child#" in item.method} == {
        "write(Item)", "remove(Item)"
    }


def test_conflicting_inherited_mutators_are_diagnostic_and_not_selected(tmp_path):
    inventory = _inventory(tmp_path, """package example
        @Dao interface Child : Writes, Deletes {}
        @Dao interface Writes { @Insert fun change(v: Item) }
        @Dao interface Deletes { @Delete fun change(v: Item) }
    """)
    assert any(d.startswith("DB_ROOM_INHERITED_METHOD_CONFLICT:") for d in inventory.diagnostics)
    assert not any("Child#change" in item.method for item in inventory.mutators)


def test_same_classification_inherited_mutators_are_still_ambiguous(tmp_path):
    inventory = _inventory(tmp_path, """package example
        @Dao interface Child : First, Second {}
        @Dao interface First { @Insert fun create(v: Item) }
        @Dao interface Second { @Insert fun create(v: Item) }
    """)
    assert any(d.startswith("DB_ROOM_INHERITED_METHOD_CONFLICT:") for d in inventory.diagnostics)
    assert not any("Child#create" in item.method for item in inventory.mutators)


def test_invalid_ancestor_suppresses_all_descendant_inheritance(tmp_path):
    inventory = _inventory(tmp_path, """package example
        @Dao interface GrandChild : Child {}
        @Dao interface Child : Broken {}
        @Dao interface Broken : Missing { @Insert fun create(v: Item) }
    """)
    assert any("DB_DAO_INHERITANCE_UNRESOLVED" in d for d in inventory.diagnostics)
    assert any("DB_DAO_INHERITANCE_INVALID_ANCESTOR" in d for d in inventory.diagnostics)
    assert not any("Child#create" in item.method or "GrandChild#create" in item.method
                   for item in inventory.mutators)


def test_room_annotation_allows_qualified_annotation_between_it_and_method(tmp_path):
    inventory = _inventory(tmp_path, """package example
        @Dao interface D {
            @Insert @kotlin.Deprecated(message = "legacy") fun save(v: Item)
        }
    """)
    assert len(inventory.mutators) == 1


def test_conflicting_room_annotations_on_one_callable_are_not_last_wins(tmp_path):
    inventory = _inventory(tmp_path, """package example
        @Dao interface D { @Insert @Query("UPDATE items SET value = 1") fun save(v: Item) }
    """)
    assert not inventory.mutators
    assert any(d.startswith("DB_ROOM_ANNOTATION_CONFLICT:") for d in inventory.diagnostics)


def test_conflicting_annotation_records_share_one_declaration_site(tmp_path):
    """Both records of a double-annotated fun carry the SAME function start.

    The inventory counts callable ambiguity over distinct declaration sites,
    so a conflicting multi-annotation declaration must stay a single site
    (and reach the ``DB_ROOM_ANNOTATION_CONFLICT`` path) instead of being
    miscounted as two ambiguous callables."""
    source = (
        "package example\n"
        "@Dao interface D { @Insert @Query(\"UPDATE items SET value = 1\") fun save(v: Item) }\n"
    )
    dao = find_dao_declarations(source, "app/src/main/java/example/Fixtures.kt")[0]
    records = find_dao_method_annotations(source, dao)
    assert [record.kind for record in records] == ["Insert", "Query"]
    assert len({record.function_start for record in records}) == 1
    assert all(record.function_start >= 0 for record in records)


def test_duplicate_and_ambiguous_declarations_are_diagnostics(tmp_path):
    inventory = _inventory(tmp_path, """package example
        @Dao interface D { @Insert fun put(v: Item) }
        @Dao interface D { @Insert fun put(v: Item) }
    """)
    assert any(d.startswith("DB_ROOM_") and ("DUPLICATE" in d or "AMBIGUOUS" in d) for d in inventory.diagnostics)
    assert not inventory.mutators


def test_duplicate_dao_fqcn_suppresses_direct_and_inherited_mutators(tmp_path):
    _write(tmp_path, "app/src/main/java/example/One.kt", "package example\n@Dao interface D { @Insert fun put(v: Item) }\n")
    _write(tmp_path, "app/src/main/java/example/Two.kt", "package example\n@Dao interface D { @Delete fun remove(v: Item) }\n")
    _write(tmp_path, "app/src/main/java/example/Child.kt", "package example\n@Dao interface Child : D {}\n")
    inventory = build_room_inventory(tmp_path)
    assert not inventory.mutators
    assert any("DB_DAO_INHERITANCE_UNRESOLVED" in diagnostic for diagnostic in inventory.diagnostics)


def test_invalid_source_and_policy_diagnostics_are_sanitized(tmp_path):
    secret = "RAW_SECRET_SHOULD_NOT_APPEAR"
    inventory = build_room_inventory(tmp_path / "missing", {"version": 1, "methods": secret})
    assert inventory.diagnostics == ("DB_ROOM_INVALID_SOURCE",)
    inventory = _inventory(tmp_path, f"package example\n@Dao interface D {{ @Query(\"{secret}\") fun q() }}\n")
    assert secret not in " ".join(inventory.diagnostics)


def test_non_directory_source_fails_closed_with_controlled_diagnostic(tmp_path):
    source = tmp_path / "source"
    source.write_text("not a directory", encoding="utf-8")
    inventory = build_room_inventory(source)
    assert inventory.diagnostics == ("DB_ROOM_INVALID_SOURCE",)
    assert not inventory.daos and not inventory.mutators


def test_non_app_src_root_fails_closed_without_raw_path(tmp_path):
    source = tmp_path / "not-production"
    source.mkdir()
    inventory = build_room_inventory(source)
    assert inventory.diagnostics == ("DB_ROOM_INVALID_SOURCE",)
    assert str(source) not in " ".join(inventory.diagnostics)


def test_approved_empty_directory_emits_invalid_source(tmp_path):
    # GR-03 centralized root resolution made the empty ``app/src`` layout
    # deterministically invalid: no conventional production root resolves.
    (tmp_path / "app" / "src").mkdir(parents=True)
    inventory = build_room_inventory(tmp_path)
    assert inventory.diagnostics == ("DB_ROOM_INVALID_SOURCE",)
    assert not inventory.daos and not inventory.mutators


def test_empty_kotlin_source_emits_source_empty(tmp_path):
    _write(tmp_path, "app/src/main/java/example/Empty.kt", "package example\n// no declarations\n")
    inventory = build_room_inventory(tmp_path)
    assert inventory.diagnostics == ("DB_ROOM_SOURCE_EMPTY",)
    assert not inventory.daos and not inventory.mutators


def test_unreadable_source_fails_closed_without_exception_or_path(monkeypatch, tmp_path):
    _write(tmp_path, "app/src/main/java/example/Unreadable.kt", "package example\n@Dao interface D {}\n")
    real_read_text = Path.read_text

    def deny_read(path, *args, **kwargs):
        if path.name == "Unreadable.kt":
            raise PermissionError("raw secret path")
        return real_read_text(path, *args, **kwargs)

    monkeypatch.setattr(Path, "read_text", deny_read)
    inventory = build_room_inventory(tmp_path)
    assert any(d.startswith("DB_ROOM_SOURCE_UNREADABLE") for d in inventory.diagnostics)
    assert not inventory.daos and not inventory.methods and not inventory.mutators
    assert "PermissionError" not in " ".join(inventory.diagnostics)
    assert "raw secret path" not in " ".join(inventory.diagnostics)


def test_os_walk_onerror_unreadable_directory_fails_closed(monkeypatch, tmp_path):
    """A directory the source walk cannot enter is a controlled failure.

    Windows cannot reliably deny directory listing through os.chmod/stat
    permission bits (ACL-based denial is not portable or CI-safe), so the
    PermissionError is injected at ``os.scandir`` -- the exact seam
    ``os.walk`` uses to discover entries and route failures to its onerror
    callback.  The walk failure must emit ``DB_ROOM_SOURCE_UNREADABLE``,
    must not expose a partially successful (or empty) inventory, and must
    never leak the raw path or exception text.
    """
    _write(tmp_path, "app/src/main/java/example/Good.kt",
           "package example\n@Dao interface Good { @Insert fun put(v: Item) }\n")
    locked = tmp_path / "app" / "src" / "main" / "java" / "example" / "locked"
    locked.mkdir()
    _write(tmp_path, "app/src/main/java/example/locked/Secret.kt",
           "package example\n@Dao interface Secret { @Insert fun put(v: Item) }\n")

    real_scandir = os.scandir

    def deny_locked(path):
        if str(path) == str(locked):
            raise PermissionError("raw secret path")
        return real_scandir(path)

    monkeypatch.setattr(room_inventory.os, "scandir", deny_locked)
    inventory = build_room_inventory(tmp_path)

    assert any(d.startswith("DB_ROOM_SOURCE_UNREADABLE") for d in inventory.diagnostics)
    # Fail-closed: the unreadable walk never yields a partial or empty
    # successful inventory to callers.
    assert not inventory.daos and not inventory.methods and not inventory.mutators
    joined = " ".join(inventory.diagnostics)
    assert "PermissionError" not in joined
    assert "raw secret path" not in joined
    assert str(locked) not in joined


def test_valid_minimal_dao_inventory_is_non_empty_and_deterministic(tmp_path):
    _write(tmp_path, "app/src/main/java/example/Minimal.kt", "package example\n@Dao interface MinimalDao { @Insert fun put(v: Item) }\n")
    inventory = build_room_inventory(tmp_path, {"version": 1, "methods": []})
    assert [dao.fqcn for dao in inventory.daos] == ["example.MinimalDao"]
    assert [item.method for item in inventory.mutators] == [
        "app/src/main/java/example/Minimal.kt::example.MinimalDao#put(Item)"
    ]
    assert not inventory.diagnostics


def test_inventory_write_is_canonical_and_atomic(tmp_path, monkeypatch):
    inventory = _inventory(tmp_path, "package example\n@Dao interface D { @Insert fun put(v: Item) }\n")
    output = tmp_path / "reports" / "inventory.json"
    _mock_directory_barrier(monkeypatch)
    calls = []
    real_replace = os.replace
    monkeypatch.setattr(os, "replace", lambda source, target: (calls.append((Path(source).parent, Path(target))), real_replace(source, target))[1])
    write_inventory_atomic(output, inventory)
    payload = json.loads(output.read_text(encoding="utf-8"))
    assert payload["schema_version"] == 1
    assert calls == [(output.parent, output)]
    assert not list(output.parent.glob(f".{output.name}.*.tmp"))


@pytest.mark.parametrize("failure", ["replace", "write"])
def test_inventory_write_failure_leaves_no_artifact(tmp_path, monkeypatch, failure):
    inventory = _inventory(tmp_path, "package example\n@Dao interface D { @Insert fun put(v: Item) }\n")
    output = tmp_path / "reports" / "inventory.json"
    if failure == "replace":
        monkeypatch.setattr(os, "replace", lambda *_args: (_ for _ in ()).throw(OSError("secret")))
    else:
        real_fdopen = os.fdopen

        class FailingWriter:
            def __init__(self, handle):
                self._handle = handle

            def __enter__(self):
                return self

            def __exit__(self, *args):
                return self._handle.__exit__(*args)

            def write(self, *_args):
                raise OSError("secret")

            def __getattr__(self, name):
                return getattr(self._handle, name)

        def failing_fdopen(*args, **kwargs):
            return FailingWriter(real_fdopen(*args, **kwargs))

        monkeypatch.setattr(os, "fdopen", failing_fdopen)
    with pytest.raises(InventoryWriteError) as error:
        write_inventory_atomic(output, inventory)
    assert str(error.value) == "DB_ROOM_INVENTORY_WRITE_FAILED"
    assert not output.exists()
    assert not list(output.parent.glob(f".{output.name}.*.tmp"))


def test_inventory_directory_fsync_failure_reports_replaced_target(tmp_path, monkeypatch):
    inventory = _inventory(tmp_path, "package example\n@Dao interface D { @Insert fun put(v: Item) }\n")
    output = tmp_path / "reports" / "inventory.json"
    real_fsync = os.fsync
    calls = {"count": 0}
    def fsync_file_then_fail(fd):
        calls["count"] += 1
        if calls["count"] == 2:
            raise OSError("secret")
        return real_fsync(fd)
    monkeypatch.setattr(os, "fsync", fsync_file_then_fail)
    with pytest.raises(room_inventory.InventoryDurabilityUnconfirmedError) as error:
        write_inventory_atomic(output, inventory)
    assert str(error.value) == "INVENTORY_DURABILITY_UNCONFIRMED"
    assert error.value.target_status == "REPLACED_NOT_DURABLE"
    assert output.exists()


def test_inventory_directory_barrier_unavailable_is_not_silent(tmp_path, monkeypatch):
    inventory = _inventory(tmp_path, "package example\n@Dao interface D { @Insert fun put(v: Item) }\n")
    output = tmp_path / "reports" / "inventory.json"
    monkeypatch.delattr(room_inventory.os, "O_DIRECTORY", raising=False)
    with pytest.raises(room_inventory.InventoryDurabilityUnconfirmedError) as error:
        write_inventory_atomic(output, inventory)
    assert str(error.value) == "INVENTORY_DURABILITY_UNCONFIRMED"
    assert error.value.target_status == "REPLACED_NOT_DURABLE"
    assert output.exists()
    assert not list(output.parent.glob(f".{output.name}.*.tmp"))


def test_inventory_write_success_reloads_report(tmp_path, monkeypatch):
    inventory = _inventory(tmp_path, "package example\n@Dao interface D { @Insert fun put(v: Item) }\n")
    output = tmp_path / "reports" / "inventory.json"
    # This test exercises the success path; use the same explicit barrier
    # seam on Windows, where O_DIRECTORY is not exposed.
    _mock_directory_barrier(monkeypatch)
    write_inventory_atomic(output, inventory)
    reloaded = json.loads(output.read_text(encoding="utf-8"))
    assert reloaded["schema"] == "cost-aggregator.room-mutator-inventory"
    assert reloaded["schema_version"] == 1
    assert reloaded["mutators"] == [
        {
            "annotation": "Insert",
            "inherited_from": None,
            "method": "app/src/main/java/example/Fixtures.kt::example.D#put(Item)",
            "mutation_kind": "ROOM_INSERT",
            "query_kind": None,
            "source_location": "app/src/main/java/example/Fixtures.kt:2",
        }
    ]


def test_annotations_in_all_kotlin_literals_and_comments_are_ignored(tmp_path):
    inventory = _inventory(tmp_path, r'''package example
// @Dao interface Fake { @Insert fun fake(v: Item) }
/* @Dao interface FakeBlock { @Delete fun fake(v: Item) } */
val line = "@Dao interface FakeString { @Update fun fake(v: Item) }"
val triple = """@Dao interface FakeTriple { @Upsert fun fake(v: Item) }"""
@Dao interface Real { @Insert fun save(v: Item) }
''', policy={"version": 1, "methods": []})
    assert [dao.fqcn for dao in inventory.daos] == ["example.Real"]
    assert [item.method.rsplit("#", 1)[1] for item in inventory.mutators] == ["save(Item)"]
    assert inventory.diagnostics == ()


def test_dao_annotation_cannot_cross_another_declaration(tmp_path):
    inventory = _inventory(tmp_path, '''package example
@Dao
class NotADao
interface AlsoNotADao { @Insert fun ignored(v: Item) }
''')
    assert inventory.daos == ()
    # Documented fail-closed source contract (DB_ROOM_INVENTORY.md §6): a
    # scan with no discovered DAO emits DB_ROOM_SOURCE_EMPTY.  The assertion
    # that matters here is that the dangling @Dao discovered NOTHING -- the
    # class/interface pair never becomes a DAO or a diagnostic of its own.
    assert inventory.diagnostics == ("DB_ROOM_SOURCE_EMPTY",)


def test_large_legal_annotation_whitespace_span_is_discovered(tmp_path):
    """A legal `@Dao` annotation separated from its declaration by a large
    whitespace span (beyond the old 2048-character window but within the
    documented safe maximum) must still be discovered; it must never be
    silently omitted."""
    gap = " " * (MAX_ANNOTATION_TO_DECLARATION_SPAN - 1024)
    assert gap > 2048  # exceeds the old arbitrary search window
    source = f"package example\n@Dao{gap}interface Wide {{ @Insert fun put(v: Item) }}\n"
    inventory = _inventory(tmp_path, source, policy={"version": 1, "methods": []})
    assert [dao.fqcn for dao in inventory.daos] == ["example.Wide"]
    assert [item.method.rsplit("#", 1)[1] for item in inventory.mutators] == ["put(Item)"]
    assert inventory.diagnostics == ()


def test_large_legal_annotation_newline_span_is_discovered(tmp_path):
    """Newlines between `@Dao` and the declaration also remain legal; the
    structural span is bounded by scope, not by characters on one line."""
    gap = "\n" * (MAX_ANNOTATION_TO_DECLARATION_SPAN // 2) + " " * 1024
    source = f"package example\n@Dao{gap}interface Newlined {{ @Insert fun put(v: Item) }}\n"
    inventory = _inventory(tmp_path, source, policy={"version": 1, "methods": []})
    assert [dao.fqcn for dao in inventory.daos] == ["example.Newlined"]
    assert inventory.diagnostics == ()


def test_annotation_to_declaration_span_over_documented_maximum_fails_closed(tmp_path):
    """A legal annotation-to-declaration span exceeding the documented safe
    maximum fails closed with the controlled
    ``DB_DAO_ANNOTATION_SCOPE_UNRESOLVED`` diagnostic instead of silently
    omitting the DAO."""
    gap = " " * (MAX_ANNOTATION_TO_DECLARATION_SPAN + 1)
    source = f"package example\n@Dao{gap}interface TooFar {{ @Insert fun put(v: Item) }}\n"
    inventory = _inventory(tmp_path, source, policy={"version": 1, "methods": []})
    assert not inventory.mutators
    assert any(
        d.startswith("DB_DAO_ANNOTATION_SCOPE_UNRESOLVED:") for d in inventory.diagnostics
    ), inventory.diagnostics
    assert not any("example.TooFar" in str(item) for item in inventory.mutators)


def test_annotation_to_declaration_span_over_maximum_raised_directly():
    """find_dao_declarations raises the controlled AccessorError for an
    over-max legal span, proving the accessor never guesses or skips."""
    gap = " " * (MAX_ANNOTATION_TO_DECLARATION_SPAN + 1024)
    source = f"package example\n@Dao{gap}interface TooFar {{ @Insert fun put(v: Item) }}\n"
    from scripts.db_guard.dao_accessors import AccessorError

    with pytest.raises(AccessorError) as error:
        find_dao_declarations(source, "app/src/main/java/example/Fixtures.kt")
    assert error.value.code == "DB_DAO_ANNOTATION_SCOPE_UNRESOLVED"


def test_annotation_scope_bound_keeps_sibling_annotation_out(tmp_path):
    """A DAO whose annotation is inside a sibling scope is not accidentally
    discovered; the structural bound keeps scope boundaries intact."""
    # Explicit empty raw-query policy: the default lookup reads the canonical
    # production policy and its global equality contract would report the
    # production entries STALE for this synthetic fixture (documented
    # post-GR-03 semantics), which is unrelated to the scope bound under test.
    inventory = _inventory(tmp_path, '''package example
class Outer {
    @Dao
    interface Inner { @Insert fun put(v: Item) }
}
''', policy={"version": 1, "methods": []})
    assert [dao.fqcn for dao in inventory.daos] == ["example.Outer.Inner"]
    assert [item.method.rsplit("#", 1)[1] for item in inventory.mutators] == ["put(Item)"]
    assert inventory.diagnostics == ()


def test_accessor_signature_handles_nested_generics_and_default_values(tmp_path):
    inventory = _inventory(tmp_path, '''package example
@Dao interface D {
    @Insert fun save(value: Map<String, List<Item>>, fallback: (Item, List<String>) -> Map<String, Item> = { _, _ -> emptyMap() })
}
''')
    assert len(inventory.methods) == 1
    assert inventory.methods[0].parameters == (
        "Map<String, List<Item>>",
        "(Item, List<String>) -> Map<String, Item>",
    )
    assert inventory.mutators[0].method.endswith(
        "#save(Map<String, List<Item>>, (Item, List<String>) -> Map<String, Item>)"
    )


def test_named_query_argument_uses_accessor_annotation_span(tmp_path):
    inventory = _inventory(tmp_path, '''package example
@Dao interface D {
    @Query(value = "UPDATE items SET label = :label WHERE id = :id")
    fun updateLabel(id: Long, label: String = "")
}
''')
    assert len(inventory.mutators) == 1
    assert inventory.mutators[0].method.endswith("#updateLabel(Long, String)")
    assert inventory.mutators[0].source_location.endswith(":3")


def test_unsupported_accessor_syntax_has_exact_sanitized_diagnostic(tmp_path):
    inventory = _inventory(tmp_path, '''package example
@Dao interface D { @Insert fun broken(value) }
''', policy={"version": 1, "methods": []})
    assert inventory.mutators == ()
    assert inventory.diagnostics == ("DB_ROOM_UNSUPPORTED_METHOD:app/src/main/java/example/Fixtures.kt",)


def test_production_root_includes_only_production_dao(tmp_path):
    """The inventory boundary is the approved production source root only.

    A production DAO is discovered; DAOs under test, androidTest, debug,
    and release source roots are never inventoried even when they carry
    valid Room mutator annotations.
    """
    _write(tmp_path, "app/src/main/java/example/Production.kt",
           "package example\n@Dao interface ProductionDao { @Insert fun save(v: Item) }\n")
    _write(tmp_path, "app/src/test/java/example/TestDao.kt",
           "package example\n@Dao interface TestDao { @Insert fun saveTest(v: Item) }\n")
    _write(tmp_path, "app/src/androidTest/java/example/AndroidTestDao.kt",
           "package example\n@Dao interface AndroidTestDao { @Insert fun saveAndroid(v: Item) }\n")
    _write(tmp_path, "app/src/debug/java/example/DebugDao.kt",
           "package example\n@Dao interface DebugDao { @Insert fun saveDebug(v: Item) }\n")
    _write(tmp_path, "app/src/release/java/example/ReleaseDao.kt",
           "package example\n@Dao interface ReleaseDao { @Insert fun saveRelease(v: Item) }\n")
    inventory = build_room_inventory(tmp_path, {"version": 1, "methods": []})
    assert [dao.fqcn for dao in inventory.daos] == ["example.ProductionDao"]
    assert [item.method.rsplit("#", 1)[1] for item in inventory.mutators] == ["save(Item)"]
    assert not inventory.diagnostics


@pytest.mark.parametrize("relative", ["", "app/src", "app/src/main", "app/src/main/java"])
def test_production_source_root_variants_normalize_to_the_same_inventory(tmp_path, relative):
    """Project root, ``app/src``, ``app/src/main``, and the java root itself
    all normalize to the same canonical production inventory."""
    _write(tmp_path, "app/src/main/java/example/Production.kt",
           "package example\n@Dao interface ProductionDao { @Insert fun save(v: Item) }\n")
    root = tmp_path / relative if relative else tmp_path
    inventory = build_room_inventory(root, {"version": 1, "methods": []})
    assert [dao.fqcn for dao in inventory.daos] == ["example.ProductionDao"]
    assert [item.method for item in inventory.mutators] == [
        "app/src/main/java/example/Production.kt::example.ProductionDao#save(Item)"
    ]
    assert not inventory.diagnostics


def test_test_only_source_layout_emits_invalid_source(tmp_path):
    """A project with ``app/src`` but no production ``main/java`` root is an
    invalid source (fail closed), never a partial or successful inventory."""
    _write(tmp_path, "app/src/test/java/example/TestDao.kt",
           "package example\n@Dao interface TestDao { @Insert fun saveTest(v: Item) }\n")
    inventory = build_room_inventory(tmp_path)
    # GR-03 centralized root resolution made the missing conventional root
    # deterministically invalid instead of an empty source.
    assert inventory.diagnostics == ("DB_ROOM_INVALID_SOURCE",)
    assert not inventory.daos and not inventory.mutators


def test_project_without_app_src_emits_invalid_source(tmp_path):
    """A project root with no ``app/src`` layout is an invalid source root
    (fail closed), never walked by guessing a different layout."""
    (tmp_path / "not-production").mkdir()
    inventory = build_room_inventory(tmp_path)
    assert inventory.diagnostics == ("DB_ROOM_INVALID_SOURCE",)
    assert not inventory.daos and not inventory.mutators


# ── Declared source-root manifest integration (PR-GR-03 Slice C1) ────────────


MANIFEST_RELATIVE = "config/guards/production_source_roots.yml"


def _write_repo_manifest(repo: Path, payload: dict) -> None:
    path = repo / MANIFEST_RELATIVE
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(yaml.safe_dump(payload), encoding="utf-8")


def test_manifest_declared_root_yields_identical_inventory_to_implicit(tmp_path):
    """A checked-in-style manifest declaring ``app/src/main/java`` must
    produce exactly the same inventory as the implicit conventional
    resolution of the same repository (byte-equivalent contract)."""
    _write(tmp_path, "app/src/main/java/example/Production.kt",
           "package example\n@Dao interface ProductionDao { @Insert fun save(v: Item) }\n")
    implicit = build_room_inventory(tmp_path, {"version": 1, "methods": []})
    assert not implicit.diagnostics
    assert [dao.fqcn for dao in implicit.daos] == ["example.ProductionDao"]
    _write_repo_manifest(tmp_path, {
        "schemaVersion": 1,
        "roots": [
            {"module": ":app", "sourceSet": "main", "path": "app/src/main/java"}
        ],
    })
    declared = build_room_inventory(tmp_path, {"version": 1, "methods": []})
    assert declared == implicit
    assert [item.method for item in declared.mutators] == [
        "app/src/main/java/example/Production.kt::example.ProductionDao#save(Item)"
    ]


def test_manifest_declaring_missing_root_fails_closed_untrusted(tmp_path):
    """A manifest declaring a nonexistent root fails closed with a
    ``DB_SOURCE_ROOT_*`` diagnostic — never a partial scan, and never an
    implicit fallback to the conventional root that does exist."""
    _write(tmp_path, "app/src/main/java/example/Production.kt",
           "package example\n@Dao interface ProductionDao { @Insert fun save(v: Item) }\n")
    _write_repo_manifest(tmp_path, {
        "schemaVersion": 1,
        "roots": [
            {"module": ":feature", "sourceSet": "main",
             "path": "feature/missing/src/main/java"}
        ],
    })
    inventory = build_room_inventory(tmp_path, {"version": 1, "methods": []})
    # Fail closed: an untrusted resolution never yields a partial scan.
    assert not inventory.daos and not inventory.methods and not inventory.mutators
    codes = {diag_code(item) for item in inventory.diagnostics}
    assert "DB_SOURCE_ROOT_UNREADABLE" in codes
    assert any(code.startswith("DB_SOURCE_ROOT_") for code in codes)
    assert "DB_ROOM_INVALID_SOURCE" in codes


def test_duplicate_fqcn_across_declared_roots_fails_closed(tmp_path):
    """A manifest declaring TWO production roots must never launder a
    duplicate FQCN into a trusted inventory.

    ``com.example.DuplicatedDao`` is declared once under
    ``app/src/main/java`` and once under ``app/src/main/kotlin``.  Callable
    identity is independent of source path, so cross-root duplicates are the
    same ambiguous duplicate-declaration failure as the single-root case:
    traversal order must never decide which copy wins.  The inventory fails
    closed with exactly the single-root duplicate contract -- no direct or
    inherited mutator is emitted for the duplicated FQCN, and the controlled
    duplicate diagnostics name the identity: the path-independent
    ``DB_ROOM_MUTATOR_IDENTITY_AMBIGUOUS`` code plus
    ``DB_DAO_INHERITANCE_UNRESOLVED`` for every duplicate copy.  (The
    same-file ``DB_ROOM_DUPLICATE_METHOD`` code cannot fire here because its
    key includes the canonical source path and the two copies live under
    different declared roots.)  Neither copy is silently dropped either:
    both stay present as untrusted discovery records with distinct
    canonical paths, never collapsed into one trusted DAO.
    """
    fqcn = "com.example.DuplicatedDao"
    source = "package com.example\n@Dao interface DuplicatedDao { @Insert fun put(v: Item) }\n"
    java_relative = "app/src/main/java/com/example/DuplicatedDao.kt"
    kotlin_relative = "app/src/main/kotlin/com/example/DuplicatedDao.kt"
    _write(tmp_path, java_relative, source)
    _write(tmp_path, kotlin_relative, source)
    _write_repo_manifest(tmp_path, {
        "schemaVersion": 1,
        "roots": [
            {"module": ":app", "sourceSet": "main", "path": "app/src/main/java"},
            {"module": ":app", "sourceSet": "main", "path": "app/src/main/kotlin"},
        ],
    })
    inventory = build_room_inventory(tmp_path, {"version": 1, "methods": []})

    # Fail closed: nothing trusted is derived from the ambiguous declarations.
    assert not inventory.mutators
    assert not any(f"::{fqcn}#" in item.method for item in inventory.mutators)

    # The controlled duplicate diagnostics name the duplicated identity.
    assert any(
        diag_code(diagnostic) == "DB_ROOM_MUTATOR_IDENTITY_AMBIGUOUS" and fqcn in diagnostic
        for diagnostic in inventory.diagnostics
    ), inventory.diagnostics
    for relative in (java_relative, kotlin_relative):
        assert any(
            diagnostic.startswith("DB_DAO_INHERITANCE_UNRESOLVED:")
            and relative in diagnostic and fqcn in diagnostic
            for diagnostic in inventory.diagnostics
        ), inventory.diagnostics

    # Neither copy is dropped or promoted: both untrusted discovery records
    # remain, anchored at their own declared root.
    assert {
        dao.canonical_path for dao in inventory.daos if dao.fqcn == fqcn
    } == {java_relative, kotlin_relative}


def test_absolute_conventional_java_root_anchors(tmp_path):
    """An absolute conventional ``src/main/java`` root anchors like a
    manifest-declared root.

    Regression for the GR-03 shared-root migration: the implicit-root branch
    resolves a bare conventional source directory into an ABSOLUTE declared
    root, and ``_absolute_root_anchor`` must compare the native path tail
    like-for-like so ``.../<module>/src/main/java`` anchors at the module's
    parent directory.  A list-vs-tuple tail comparison made every absolute
    conventional root fail closed with ``DB_ROOM_SOURCE_UNREADABLE`` /
    ``DB_ROOM_SOURCE_EMPTY`` instead of being walked; a later drive-relative
    anchor rebuild ("C:Users\\..." on Windows) broke the same anchoring, so
    the helper-level ``os.path.isabs`` invariant is asserted directly."""
    relative = "app/src/main/java/com/example/AbsoluteDao.kt"
    _write(
        tmp_path,
        relative,
        "package com.example\n@Dao interface AbsoluteDao { @Insert fun put(v: Item) }\n",
    )
    java_root = tmp_path / "app" / "src" / "main" / "java"
    # Helper-level anchoring invariant (platform-neutral): the anchor derived
    # for the absolute fixture root must itself be ABSOLUTE and must resolve
    # the written file below it.  A drive-relative rebuild ("C:Users\\..."
    # instead of "C:\\Users\\...") violates os.path.isabs on Windows and made
    # every downstream relative_to(anchor) fail closed.
    anchor = _absolute_root_anchor(str(java_root))
    assert anchor is not None
    assert os.path.isabs(anchor)
    # No-information-loss invariant: the fixture root is the enclosing
    # project of the conventional dir passed directly, so the rebuilt
    # anchor must reproduce it exactly after normpath -- true on every
    # platform shape (POSIX "/", Windows drive, UNC).
    assert anchor == os.path.normpath(str(tmp_path))
    resolved = os.path.relpath(os.fspath(tmp_path / relative), anchor)
    assert not os.path.isabs(resolved)
    assert resolved.replace(os.sep, "/") == relative
    # Explicit empty raw-query policy: this test targets anchoring only, and
    # the default canonical production policy would report its entries STALE
    # for a synthetic single-DAO fixture under the documented global
    # equality contract (post-GR-03 semantics).
    inventory = build_room_inventory(java_root, {"version": 1, "methods": []})
    # Anchored discovery: the DAO and its @Insert mutator are found and the
    # emitted canonical path stays repository-relative POSIX below the
    # enclosing project anchor (the module's parent directory).
    assert [dao.fqcn for dao in inventory.daos] == ["com.example.AbsoluteDao"]
    assert [dao.canonical_path for dao in inventory.daos] == [relative]
    assert [mutator.mutation_kind for mutator in inventory.mutators] == ["ROOM_INSERT"]
    assert inventory.mutators[0].source_location.startswith(relative + ":")
    # No fail-closed source diagnostics from the anchoring step.
    assert not any(
        diag_code(diagnostic) in {"DB_ROOM_SOURCE_EMPTY", "DB_ROOM_SOURCE_UNREADABLE"}
        for diagnostic in inventory.diagnostics
    )
    assert inventory.diagnostics == ()


# Platform-neutral shape coverage for ``_absolute_root_anchor``: synthetic
# normpath-shaped strings are fed directly (no filesystem).  Branches whose
# first-component shape only arises under one native separator are skipped
# elsewhere instead of being faked via monkeypatching.
_POSIX_ANCHOR_ONLY = pytest.mark.skipif(
    os.sep != "/", reason="POSIX-absolute branch unreachable under a non-'/' native separator"
)
_WINDOWS_ANCHOR_ONLY = pytest.mark.skipif(
    os.sep != "\\", reason="drive/UNC branches unreachable under a non-backslash native separator"
)


@_POSIX_ANCHOR_ONLY
def test_anchor_posix_absolute_shape():
    """A native absolute POSIX source root anchors at its enclosing project."""
    root = os.sep.join(("", "tmp", "x", "app", "src", "main", "java"))
    assert _absolute_root_anchor(root) == os.sep + os.path.join("tmp", "x")


@_WINDOWS_ANCHOR_ONLY
def test_anchor_drive_shape_windows_only():
    """A rooted drive path anchors above the module directory; a drive root
    whose tail IS the whole path has no component left above it and fails
    closed."""
    assert _absolute_root_anchor("C:\\repo\\app\\src\\main\\java") == "C:\\repo"
    assert _absolute_root_anchor("C:\\src\\main\\java") is None


@_WINDOWS_ANCHOR_ONLY
def test_anchor_unc_shape_windows_only():
    """A UNC source root keeps both leading separators through the rebuild."""
    # parts[:-4] cuts src/main/java plus the module dir, so the anchor is the project above it.
    assert _absolute_root_anchor("\\\\server\\share\\proj\\mod\\src\\main\\java") == (
        "\\\\server\\share\\proj"
    )


def test_anchor_degenerate_inputs_fail_closed():
    """Relative and exactly-root-tail inputs return None without raising."""
    assert _absolute_root_anchor(os.sep.join(("src", "main", "java"))) is None
    assert _absolute_root_anchor(os.sep.join(("x", "src", "main", "java"))) is None
