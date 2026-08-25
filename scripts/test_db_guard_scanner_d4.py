"""Focused D4 contracts for fail-closed structural discovery."""

import json
from pathlib import Path

from scripts.ci.finding_rule_catalog import known_diagnostic
from scripts.db_guard.declaration_scanner import DeclarationRange
from scripts.db_guard.scanner import _property_symbol_at, scan_db_access
from scripts.ci.guard_findings import (
    KIND_INITIALIZER,
    KIND_PROPERTY_GETTER,
    KIND_PROPERTY_SETTER,
    KIND_TOP_LEVEL_FUNCTION,
)


# Full-pipeline fixtures: scan_db_access wires Room-inventory AND signature
# resolution, so every structural fixture carries (1) a minimal valid @Dao so
# the inventory leg succeeds (a DAO-free scan fails closed with
# DB_ROOM_SOURCE_EMPTY), (2) an import making ``SQLiteDatabase`` resolvable by
# the closed-world type resolver (an unresolvable parameter type fails closed
# with DB_SIGNATURE_UNRESOLVED), and (3) an explicit EMPTY raw-query policy —
# with a discovered DAO and no policy override, the production default policy
# would fail the stale-key comparison.
_EMPTY_RAW_QUERY_POLICY = {"version": 1, "methods": []}

_PROBE_DAO = """@androidx.room.Dao
interface ScanProbeDao {
    @androidx.room.Insert
    fun probe(value: Int)
}
"""


def _source_root(tmp_path: Path) -> Path:
    root = tmp_path / "app" / "src" / "main" / "java"
    root.mkdir(parents=True)
    return root


def test_structural_scope_unsupported_is_registered_and_never_a_finding(tmp_path):
    root = _source_root(tmp_path)
    source = """package example
import android.database.sqlite.SQLiteDatabase
fun mutate(db: SQLiteDatabase) {
    db.execSQL
}
""" + _PROBE_DAO
    path = root / "Example.kt"
    path.write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert known_diagnostic("DB_STRUCTURAL_SCOPE_UNSUPPORTED").code == (
        "DB_STRUCTURAL_SCOPE_UNSUPPORTED"
    )
    expected = {
        "schema": "cost-aggregator.guard-findings",
        "schema_version": 2,
        "guard": "db_access",
        "findings": [],
        "diagnostics": [{
            "code": "DB_STRUCTURAL_SCOPE_UNSUPPORTED",
            "path": "app/src/main/java/Example.kt",
            "symbol": None,
            # Fixture line literal: ``db.execSQL`` is the fourth source line.
            "controlled_context": {"line": 4},
        }],
        "statistics": {"files_scanned": 1, "declarations_scanned": 1,
                       "inventory_daos": 1, "inventory_mutators": 1,
                       "trusted": False},
    }
    assert report.to_dict() == expected


def test_property_initializer_and_accessors_have_distinct_exact_symbols(tmp_path):
    source = """package example
class Holder {
    var value = db.insert()
        get() = field
        set(value: String) { field = value }
}
"""
    declaration = DeclarationRange(
        "app/src/main/java/example/Holder.kt", "example.Holder", "property",
        3, 5, False, False, None, None, "value", (),
        source.index("var"), source.index("}") + 1,
    )
    initializer = _property_symbol_at(source, declaration, source.index("db.insert"))
    getter = _property_symbol_at(source, declaration, source.index("get()"))
    # Slice marker anchored to the CURRENT fixture text: the setter declares
    # a typed parameter (``set(value: String)``), so the literal
    # ``set(value)`` is not a substring of the source.
    setter = _property_symbol_at(source, declaration, source.index("set(value:"))

    assert (initializer.owner, initializer.name, initializer.kind) == (
        "example.Holder", "value", KIND_INITIALIZER,
    )
    assert (getter.owner, getter.name, getter.kind) == (
        "example.Holder", "value", KIND_PROPERTY_GETTER,
    )
    assert (setter.owner, setter.name, setter.kind, setter.parameters) == (
        "example.Holder", "value", KIND_PROPERTY_SETTER, ("String",),
    )


def test_delete_recursively_uses_exact_structural_token_and_policy_path(tmp_path):
    root = _source_root(tmp_path)
    source = """package example
import android.database.sqlite.SQLiteDatabase
class StructuralRepository {
    fun allowed(db: SQLiteDatabase) { db.deleteRecursively() }
    fun forbidden(db: SQLiteDatabase) { db.deleteRecursively() }
}
""" + _PROBE_DAO
    path = root / "StructuralRepository.kt"
    path.write_text(source, encoding="utf-8")
    structural = [{
        # EXACT canonical-path equality is the authorization contract
        # (_structural_match): the entry must name the path the fixture
        # actually writes (java root, no example/ segment) — the same path
        # the expected finding below carries.
        "path": "app/src/main/java/StructuralRepository.kt",
        "class": "StructuralRepository",
        "method_pattern": "allowed",
        "operation": "deleteRecursively",
    }]

    report = scan_db_access(root, structural_policy=structural,
                            raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    # Resolved-identity contract: DB_FORBIDDEN_STRUCTURAL_OPERATION declares
    # symbol.* identity fields, so a finding may never be emitted without a
    # resolved callable signature -- and an unresolved one takes the
    # controlled DB_SIGNATURE_UNRESOLVED diagnostic path instead.  Both
    # determinations share the single ``_is_unresolved_symbol`` helper with
    # the finding-validation gate: kind==unknown or missing owner/name.
    # Here both enclosing signatures RESOLVE exactly (owner, name,
    # import-resolved parameters, function kind), so the unauthorized
    # ``forbidden`` callable is reported as a fully signed finding while the
    # policy-authorized ``allowed`` callable stays silent.
    expected = {
        "schema": "cost-aggregator.guard-findings", "schema_version": 2,
        "guard": "db_access",
        "findings": [{
            "rule": "DB_FORBIDDEN_STRUCTURAL_OPERATION", "severity": "error",
            "path": "app/src/main/java/StructuralRepository.kt",
            # Fixture line literal: ``db.deleteRecursively()`` inside
            # ``forbidden`` is the fifth source line.
            "location": {"line": 5, "end_line": 5},
            "symbol": {"owner": "example.StructuralRepository",
                       "name": "forbidden", "receiver": None,
                       "parameters": ["android.database.sqlite.SQLiteDatabase"],
                       "kind": "function"},
            "identity": {"operation": "deleteRecursively"},
            "message": "Forbidden structural database operation",
        }],
        "diagnostics": [],
        "statistics": {"files_scanned": 1, "declarations_scanned": 3,
                       "inventory_daos": 1, "inventory_mutators": 1,
                       "trusted": True},
    }
    assert report.to_dict() == expected


def test_d4_structural_findings_serialize_every_supported_callable_scope_exactly(tmp_path):
    root = _source_root(tmp_path)
    source = """package example
import android.database.sqlite.SQLiteDatabase
class Helper(private val db: SQLiteDatabase) {
    var amount = db.deleteRecursively()
        get() { db.deleteRecursively(); return field }
        set(value: String) { db.deleteRecursively(); field = value }
    init { db.deleteRecursively() }
    fun member(first: String, second: Int) { db.deleteRecursively() }
    companion object {
        fun companion(db: SQLiteDatabase, value: Int) { db.deleteRecursively() }
    }
}
object HelperObject {
    fun objectMethod(db: SQLiteDatabase, value: Int) { db.deleteRecursively() }
}
fun topLevel(db: SQLiteDatabase, amount: Long) { db.deleteRecursively() }
fun SQLiteDatabase.extension(value: Int) {
    val local: SQLiteDatabase = this
    local.deleteRecursively()
}
""" + _PROBE_DAO
    (root / "Scopes.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    serialized = [finding.to_dict() for finding in report.findings]
    first = json.dumps(report.to_dict(), sort_keys=True, separators=(",", ":")).encode()
    second = json.dumps(report.to_dict(), sort_keys=True, separators=(",", ":")).encode()
    assert first == second
    # Canonical report order is (rule, path, symbol.owner, symbol.name, ...):
    # owner groups sort before names ("example" < "example.Helper" <
    # "example.HelperObject"), then by name/kind within each owner.  Parameter
    # and receiver types use the resolver's canonical imported spelling.
    expected_symbols = [
        ("extension", KIND_TOP_LEVEL_FUNCTION, "example",
         "android.database.sqlite.SQLiteDatabase", ["Int"]),
        ("topLevel", KIND_TOP_LEVEL_FUNCTION, "example", None,
         ["android.database.sqlite.SQLiteDatabase", "Long"]),
        ("amount", KIND_INITIALIZER, "example.Helper", None, []),
        ("amount", KIND_PROPERTY_GETTER, "example.Helper", None, []),
        ("amount", KIND_PROPERTY_SETTER, "example.Helper", None, ["String"]),
        ("companion", "function", "example.Helper", None,
         ["android.database.sqlite.SQLiteDatabase", "Int"]),
        ("member", "function", "example.Helper", None, ["String", "Int"]),
        ("objectMethod", "function", "example.HelperObject", None,
         ["android.database.sqlite.SQLiteDatabase", "Int"]),
    ]
    # These are fixture line literals, not values read from the report.  Keep
    # them frozen so source movement changes this contract deliberately:
    # extension's call sits on ``local.deleteRecursively()`` (line 19),
    # topLevel on 16, the ``amount`` initializer/getter/setter calls on
    # 4/5/6, companion on 10, member on 8, and objectMethod on 14.  The
    # class-level ``init { ... }`` block lies inside the skipped class-owner
    # range, so it produces no declaration range and no finding.  (All lines
    # shifted +1 when the SQLiteDatabase import line was added.)
    expected_lines = (19, 16, 4, 5, 6, 10, 8, 14)
    expected_findings = [{
        "rule": "DB_FORBIDDEN_STRUCTURAL_OPERATION", "severity": "error",
        "path": "app/src/main/java/Scopes.kt", "location": {"line": line, "end_line": line},
        "symbol": {"owner": owner, "name": name, "receiver": receiver,
                   "parameters": parameters, "kind": kind},
        "identity": {"operation": "deleteRecursively"},
        "message": "Forbidden structural database operation",
    } for (name, kind, owner, receiver, parameters), line in zip(expected_symbols, expected_lines)]
    assert report.to_dict()["findings"] == expected_findings
    assert report.to_dict()["diagnostics"] == []
    assert report.to_dict()["statistics"] == {
        "files_scanned": 1, "declarations_scanned": 9,
        "inventory_daos": 1, "inventory_mutators": 1, "trusted": True,
    }


def test_unsupported_structural_scope_has_exact_diagnostic_code(tmp_path):
    root = _source_root(tmp_path)
    (root / "Unsupported.kt").write_text("""package example
import android.database.sqlite.SQLiteDatabase
fun unsupported(value: String) { value.deleteRecursively() }
""" + _PROBE_DAO, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.to_dict() == {
        "schema": "cost-aggregator.guard-findings", "schema_version": 2,
        "guard": "db_access", "findings": [],
        "diagnostics": [{"code": "DB_STRUCTURAL_SCOPE_UNSUPPORTED",
                         "path": "app/src/main/java/Unsupported.kt", "symbol": None,
                         "controlled_context": {"line": 3}}],
        "statistics": {"files_scanned": 1, "declarations_scanned": 1,
                       "inventory_daos": 1, "inventory_mutators": 1,
                       "trusted": False},
    }
