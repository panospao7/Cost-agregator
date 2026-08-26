"""Focused D4 contracts for fail-closed structural discovery.

PR-GR-07 Slice 2: D4 authorization is TYPED.  The matrix below pins exact
full-identity equality (``PolicyEntry``/``match_mutation``) for every
discovered direct DAO mutation: path + ownerFqcn + kind + method + receiver +
ordered parameterTypes + daoAccessor + daoFqcn + operation.
"""

import json
from pathlib import Path

import scripts.db_guard.scanner as db_guard_scanner_module
from scripts.ci.finding_rule_catalog import known_diagnostic
from scripts.db_guard.declaration_scanner import DeclarationRange
from scripts.db_guard.policy_model import BarrierMode, CallableKind, PolicyEntry
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
# with DB_SIGNATURE_UNRESOLVED), and (3) an explicit EMPTY raw-query policy â€”
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
        # actually writes (java root, no example/ segment) â€” the same path
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


# â”€â”€ Repository-relative POSIX identity contract â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
# Every identity the D4 report carries (diagnostic paths, finding paths) must
# be a repository-relative POSIX ``.kt`` path anchored at the enclosing
# project -- never an absolute native path, drive-prefixed text, or a
# backslash form.  Absolute identities are rejected by the diagnostic-path
# contract downstream, so leaking one here would degrade the whole scan.


def _assert_repo_relative_posix(report, tmp_path):
    """Every reported path is relative POSIX .kt and leaks no local prefix."""
    for item in report.diagnostics:
        assert item.path is not None
        assert "\\" not in item.path
        assert ":" not in item.path
        assert not item.path.startswith("/")
        assert not item.path.startswith(str(tmp_path))
        assert item.path.endswith(".kt")
    for finding in report.findings:
        assert "\\" not in finding.path
        assert ":" not in finding.path
        assert not finding.path.startswith("/")
        assert not finding.path.startswith(str(tmp_path))
        assert finding.path.endswith(".kt")


def test_deep_nested_tree_identities_stay_repo_relative_posix(tmp_path):
    """A deeply nested production tree reports identities as repository-
    relative POSIX paths (forward slashes, project-anchored), never as the
    absolute native paths the file walker handles internally."""
    root = _source_root(tmp_path)
    deep = root / "com" / "example" / "deep" / "a" / "b" / "c"
    deep.mkdir(parents=True)
    deep.joinpath("DeepDiag.kt").write_text(
        """package com.example.deep.a.b.c

import android.database.sqlite.SQLiteDatabase

fun mutate(db: SQLiteDatabase) {
    db.execSQL
}
""" + _PROBE_DAO,
        encoding="utf-8",
    )
    deep.joinpath("DeepClean.kt").write_text(
        """package com.example.deep.a.b.c

class DeepClean {
    fun noop(): Int {
        return 1
    }
}
""",
        encoding="utf-8",
    )

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    # Fixture line literal: ``db.execSQL`` is the sixth source line.
    assert report.to_dict() == {
        "schema": "cost-aggregator.guard-findings", "schema_version": 2,
        "guard": "db_access", "findings": [],
        "diagnostics": [{
            "code": "DB_STRUCTURAL_SCOPE_UNSUPPORTED",
            "path": "app/src/main/java/com/example/deep/a/b/c/DeepDiag.kt",
            "symbol": None,
            "controlled_context": {"line": 6},
        }],
        "statistics": {"files_scanned": 2, "declarations_scanned": 3,
                       "inventory_daos": 1, "inventory_mutators": 1,
                       "trusted": False},
    }
    _assert_repo_relative_posix(report, tmp_path)


def test_multi_file_full_scan_yields_zero_declaration_unresolved(tmp_path):
    """When every file parses, a full multi-file scan carries ZERO
    DB_DECLARATION_UNRESOLVED diagnostics and stays trusted; the discovered
    unauthorized mutation is a fully signed finding whose path identity is
    the repository-relative POSIX form."""
    root = _source_root(tmp_path)
    package = root / "com" / "example" / "multi"
    package.mkdir(parents=True)
    package.joinpath("MultiDao.kt").write_text(
        """package com.example.multi

@androidx.room.Dao
interface MultiDao {
    @androidx.room.Insert
    fun insert(value: Int)
}

fun daoTag(): String {
    return "multi"
}
""",
        encoding="utf-8",
    )
    package.joinpath("MultiRepository.kt").write_text(
        """package com.example.multi

class MultiRepository(private val multiDao: MultiDao) {
    fun save(value: Int) {
        multiDao.insert(value)
    }
}
""",
        encoding="utf-8",
    )

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    payload = report.to_dict()
    assert [item["code"] for item in payload["diagnostics"]] == []
    assert [finding["rule"] for finding in payload["findings"]] == [
        "DB_UNAUTHORIZED_MUTATION",
    ]
    finding = payload["findings"][0]
    assert finding["path"] == "app/src/main/java/com/example/multi/MultiRepository.kt"
    # Fixture line literal: ``multiDao.insert(value)`` is the fifth line.
    assert finding["location"] == {"line": 5, "end_line": 5}
    assert finding["symbol"]["owner"] == "com.example.multi.MultiRepository"
    assert finding["symbol"]["name"] == "save"
    assert finding["symbol"]["parameters"] == ["Int"]
    assert finding["identity"]["dao"] == "com.example.multi.MultiDao"
    assert finding["identity"]["accessor"] == "multiDao"
    assert finding["identity"]["operation"] == "insert"
    assert payload["statistics"] == {
        "files_scanned": 2, "declarations_scanned": 3,
        "inventory_daos": 1, "inventory_mutators": 1, "trusted": True,
    }
    _assert_repo_relative_posix(report, tmp_path)


# â”€â”€ PR-GR-07 Slice 2: typed v2 authorization matrix â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€

_TYPED_SOURCE = """package example

data class Item(val id: Int)

@androidx.room.Dao
interface ExpenseDao {
    @androidx.room.Insert
    fun insert(item: Item)

    @androidx.room.Insert
    fun insert(first: String, second: Item)

    @androidx.room.Delete
    fun remove(item: Item)
}

class Repository(private val expenseDao: ExpenseDao,
                 private val otherDao: ExpenseDao) {
    fun save(item: Item) {
        expenseDao.insert(item)
    }
}
"""

_TYPED_PATH = "app/src/main/java/example/TypedRepository.kt"


def _typed_entry(**overrides) -> PolicyEntry:
    """The EXACT entry for Repository.save(item: Item) -> expenseDao.insert."""
    values = dict(
        path=_TYPED_PATH,
        owner_fqcn="example.Repository",
        kind=CallableKind.FUNCTION,
        method="save",
        receiver=None,
        parameter_types=("example.Item",),
        dao_accessor="expenseDao",
        dao_fqcn="example.ExpenseDao",
        operation="insert",
        barrier_mode=BarrierMode.HELPER,
        reason="matrix",
        owner="@d4",
        linked_issue="D4-MATRIX",
    )
    values.update(overrides)
    return PolicyEntry(**values)


def _typed_root(tmp_path: Path, source: str = _TYPED_SOURCE) -> Path:
    root = _source_root(tmp_path)
    # The fixture file must live at EXACTLY _TYPED_PATH relative to the
    # repository root: v2 authorization matches on full path equality, so a
    # fixture written anywhere else can never be authorized by its entry.
    package = root / "example"
    package.mkdir()
    (package / "TypedRepository.kt").write_text(source + "\n" + _PROBE_DAO,
                                                encoding="utf-8")
    return root


def test_typed_exact_entry_authorizes_the_mutation(tmp_path):
    """Positive: the fully matching typed entry authorizes cleanly â€” no
    finding, no diagnostic, trusted scan."""
    root = _typed_root(tmp_path)

    report = scan_db_access(
        root, [_typed_entry()], raw_query_policy=_EMPTY_RAW_QUERY_POLICY,
    )

    assert report.to_dict() == {
        "schema": "cost-aggregator.guard-findings", "schema_version": 2,
        "guard": "db_access", "findings": [], "diagnostics": [],
        # Declaration ranges: Item + Repository + save (both @Dao interfaces
        # are excluded from the scanned-range count, matching every other D4
        # fixture's accounting).
        "statistics": {"files_scanned": 1, "declarations_scanned": 3,
                       "inventory_daos": 2, "inventory_mutators": 4,
                       "trusted": True},
    }


def test_typed_non_policy_entry_fails_closed_as_diagnostic(tmp_path):
    """Anything that is not a PolicyEntry can never authorize: the run is
    untrusted with the controlled policy diagnostic and zero findings."""
    root = _typed_root(tmp_path)
    legacy_shape = {
        "path": _TYPED_PATH, "class": "Repository", "method": "save",
        "daos": ["expenseDao"], "operation": "insert",
        "signature": {"receiver": None, "kind": "function",
                      "parameters": ["example.Item"]},
        "barrier_required": False, "reason": "legacy", "owner": "@v1",
        "linked_issue": "V1-1",
    }

    report = scan_db_access(
        root, [legacy_shape], raw_query_policy=_EMPTY_RAW_QUERY_POLICY,
    )

    payload = report.to_dict()
    assert payload["findings"] == []
    assert [item["code"] for item in payload["diagnostics"]] == [
        "DB_POLICY_SOURCE_EVIDENCE_INVALID",
    ]
    assert payload["statistics"]["trusted"] is False


def test_typed_matrix_rejects_each_mismatched_identity_dimension(tmp_path):
    """Every identity dimension is exact: flipping ANY single field of the
    entry yields exactly one DB_UNAUTHORIZED_MUTATION finding for the same
    mutation â€” never an authorization.  This pins the removal of the legacy
    paths: simple-name owner comparison (owner_fqcn.rsplit), name-only
    operation matching, cross-overload unions, wildcards, and v1 fallbacks.

    Parametrization note: ``owner_simple_collision`` keeps a DIFFERENT FQCN
    whose simple name matches what a legacy rsplit comparison would have
    seen; ``overload_union`` targets the sibling String overload of the same
    operation; ``parameter_order`` swaps the ordered parameterTypes.
    """
    cases = {
        "wrong_owner_fqcn": dict(
            owner_fqcn="evil.example.Repository",
            expected_owner="example.Repository",
        ),
        "owner_simple_name_collision": dict(
            # Same simple name tail ("Repository") as the discovered owner â€”
            # only FULL FQCN equality may authorize.
            owner_fqcn="other.example.Repository",
            expected_owner="example.Repository",
        ),
        "wrong_method": dict(method="saveOther"),
        "wrong_kind": dict(kind=CallableKind.PROPERTY_GETTER),
        "wrong_receiver": dict(receiver="String"),
        "wrong_parameter_type": dict(parameter_types=("example.OtherItem",)),
        "nullable_parameter": dict(parameter_types=("example.Item?",)),
        "wrong_dao_accessor": dict(dao_accessor="otherDao"),
        "wrong_dao_fqcn": dict(dao_fqcn="example.OtherDao"),
        "wrong_operation": dict(operation="remove"),
        "cross_overload_union": dict(
            # The String/Item overload of `insert` must not authorize the
            # Item-only call site.
            parameter_types=("String", "example.Item"),
        ),
    }
    for label, overrides in cases.items():
        expected_owner = overrides.pop("expected_owner", "example.Repository")
        # Each case needs its OWN tree: the fixture helper creates the
        # conventional root non-idempotently, so reusing one tmp_path across
        # iterations would collide instead of scanning a fresh fixture.
        root = _typed_root(tmp_path / label)

        report = scan_db_access(
            root, [_typed_entry(**overrides)],
            raw_query_policy=_EMPTY_RAW_QUERY_POLICY,
        )

        payload = report.to_dict()
        assert [finding["rule"] for finding in payload["findings"]] == [
            "DB_UNAUTHORIZED_MUTATION",
        ], label
        finding = payload["findings"][0]
        assert finding["symbol"]["owner"] == expected_owner, label
        assert finding["symbol"]["name"] == "save", label
        assert finding["identity"]["dao"] == "example.ExpenseDao", label
        assert finding["identity"]["accessor"] == "expenseDao", label
        assert finding["identity"]["operation"] == "insert", label
        assert payload["diagnostics"] == [], label
        assert payload["statistics"]["trusted"] is True, label


def test_typed_parameter_order_is_exact(tmp_path):
    """Ordered parameterTypes are part of the identity: the swapped-order
    entry cannot authorize the (Item) call site even though the type SET
    overlaps with the two-parameter overload."""
    root = _typed_root(tmp_path)

    report = scan_db_access(
        root, [_typed_entry(parameter_types=("example.Item", "String"))],
        raw_query_policy=_EMPTY_RAW_QUERY_POLICY,
    )

    payload = report.to_dict()
    assert [finding["rule"] for finding in payload["findings"]] == [
        "DB_UNAUTHORIZED_MUTATION",
    ]
    assert payload["diagnostics"] == []


def test_typed_direct_barrier_mode_requires_local_barrier_evidence(tmp_path):
    """barrierMode direct keeps the local-barrier contract: without exact
    writeBarrier evidence before the mutation the authorization becomes a
    DB_MISSING_WRITE_BARRIER finding; with it the scan is clean."""
    barrier_source = _TYPED_SOURCE.replace(
        "    fun save(item: Item) {\n        expenseDao.insert(item)\n    }",
        "    fun save(item: Item) {\n"
        "        writeBarrier.checkWritesAllowed()\n"
        "        expenseDao.insert(item)\n"
        "    }",
    )
    plain_root = _typed_root(tmp_path)
    barrier_root = _typed_root(tmp_path / "barrier", barrier_source)

    missing = scan_db_access(
        plain_root, [_typed_entry(barrier_mode=BarrierMode.DIRECT)],
        raw_query_policy=_EMPTY_RAW_QUERY_POLICY,
    )
    assert [finding.rule for finding in missing.findings] == [
        "DB_MISSING_WRITE_BARRIER",
    ]
    assert missing.diagnostics == ()

    satisfied = scan_db_access(
        barrier_root, [_typed_entry(barrier_mode=BarrierMode.DIRECT)],
        raw_query_policy=_EMPTY_RAW_QUERY_POLICY,
    )
    assert satisfied.to_dict() == {
        "schema": "cost-aggregator.guard-findings", "schema_version": 2,
        "guard": "db_access", "findings": [], "diagnostics": [],
        # Same accounting as the positive test: Item + Repository + save;
        # both @Dao interfaces are excluded from the scanned-range count.
        "statistics": {"files_scanned": 1, "declarations_scanned": 3,
                       "inventory_daos": 2, "inventory_mutators": 4,
                       "trusted": True},
    }


# â”€â”€ GR-07 hardening step A: project-wide type index â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
#
# The closed-world resolver only saw same-file declarations, so a repository
# whose signature named a type declared in ANOTHER production file (legal
# same-package Kotlin, no import required) died as TYPE_UNRESOLVED and
# degraded to a DB_SIGNATURE_UNRESOLVED diagnostic.  The scanner now builds
# ONE deterministic index per scan over the declared production roots and
# threads it into callable discovery: unique simple name -> package-qualified
# FQCN; ambiguous simple name -> still fails closed.

_MULTI_ENTITY = """package com.example.multi

data class MultiEntity(val id: Int)
"""

_MULTI_DAO = """package com.example.multi

@androidx.room.Dao
interface MultiDao {
    @androidx.room.Insert
    fun insert(value: Int)
}
"""

_MULTI_REPOSITORY = """package com.example.multi

class MultiRepository(private val multiDao: MultiDao) {
    fun save(entity: MultiEntity) {
        multiDao.insert(1)
    }
}
"""


def _multi_file_root(tmp_path: Path) -> Path:
    root = _source_root(tmp_path)
    package = root / "com" / "example" / "multi"
    package.mkdir(parents=True)
    package.joinpath("MultiEntity.kt").write_text(_MULTI_ENTITY, encoding="utf-8")
    package.joinpath("MultiDao.kt").write_text(_MULTI_DAO, encoding="utf-8")
    package.joinpath("MultiRepository.kt").write_text(
        _MULTI_REPOSITORY, encoding="utf-8")
    return root


def test_cross_file_project_type_resolves_end_to_end(tmp_path):
    """Entity and DAO types declared in OTHER files resolve through the
    project index: the discovered mutation is a fully signed finding with
    the package-qualified parameter identity -- no diagnostic, trusted."""
    root = _multi_file_root(tmp_path)

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    payload = report.to_dict()
    assert payload["diagnostics"] == []
    assert [finding["rule"] for finding in payload["findings"]] == [
        "DB_UNAUTHORIZED_MUTATION",
    ]
    finding = payload["findings"][0]
    assert finding["path"] == "app/src/main/java/com/example/multi/MultiRepository.kt"
    # Fixture line literal: ``multiDao.insert(1)`` is the fifth line.
    assert finding["location"] == {"line": 5, "end_line": 5}
    assert finding["symbol"]["owner"] == "com.example.multi.MultiRepository"
    assert finding["symbol"]["name"] == "save"
    assert finding["symbol"]["receiver"] is None
    assert finding["symbol"]["parameters"] == ["com.example.multi.MultiEntity"]
    assert finding["identity"]["dao"] == "com.example.multi.MultiDao"
    assert finding["identity"]["accessor"] == "multiDao"
    assert finding["identity"]["operation"] == "insert"
    assert payload["statistics"] == {
        # ``files_scanned`` counts files with scanned helper ranges: the
        # DAO-only file contributes no helper range and is absent.
        "files_scanned": 2, "declarations_scanned": 3,
        "inventory_daos": 1, "inventory_mutators": 1, "trusted": True,
    }
    _assert_repo_relative_posix(report, tmp_path)


def test_ambiguous_cross_package_simple_name_still_fails_closed_in_d4(tmp_path):
    """The same simple name declared in two packages stays honest debt: the
    repository's signature cannot be resolved, so the controlled
    DB_SIGNATURE_UNRESOLVED diagnostic replaces any guessed finding."""
    root = _source_root(tmp_path)
    multi = root / "com" / "example" / "multi"
    multi.mkdir(parents=True)
    other = root / "com" / "example" / "other"
    other.mkdir(parents=True)
    repo = root / "com" / "example" / "repo"
    repo.mkdir(parents=True)
    multi.joinpath("MultiEntity.kt").write_text(_MULTI_ENTITY, encoding="utf-8")
    multi.joinpath("MultiDao.kt").write_text(_MULTI_DAO, encoding="utf-8")
    other.joinpath("MultiEntity.kt").write_text(
        "package com.example.other\n\ndata class MultiEntity(val id: Int)\n",
        encoding="utf-8")
    repo.joinpath("MultiRepository.kt").write_text(
        """package com.example.repo

import com.example.multi.MultiDao

class MultiRepository(private val multiDao: MultiDao) {
    fun save(entity: MultiEntity) {
        multiDao.insert(1)
    }
}
""", encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    payload = report.to_dict()
    assert payload["findings"] == []
    assert payload["diagnostics"] == [{
        "code": "DB_SIGNATURE_UNRESOLVED",
        "path": "app/src/main/java/com/example/repo/MultiRepository.kt",
        "symbol": None,
        "controlled_context": {},
    }]
    assert payload["statistics"]["trusted"] is False


def test_project_type_index_is_built_once_per_scan_and_deterministic(tmp_path,
                                                                     monkeypatch):
    """One index build per scan (never per declaration), and the same tree
    yields byte-identical reports across runs."""
    root = _multi_file_root(tmp_path)
    calls = []
    real_builder = db_guard_scanner_module.build_project_type_index

    def counted(pairs):
        calls.append(1)
        return real_builder(pairs)

    monkeypatch.setattr(
        db_guard_scanner_module, "build_project_type_index", counted)

    first = db_guard_scanner_module.scan_db_access(
        root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)
    second = db_guard_scanner_module.scan_db_access(
        root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert len(calls) == 2  # exactly one build per scan
    assert first.to_dict() == second.to_dict()
    assert first.statistics["trusted"] is True


# ------------------------------------------- GR-07 residual-diagnosis fixes
#
# Each test pins one production-tree failure family reproduced by the
# instrumented full-scan probe (per-emission-site counts + samples):
#   D1: ``_receiver_expression`` never stopped at an unmatched ``(``, so
#       ``if (!dao.probe(x))`` parsed the receiver as ``if (!dao`` and every
#       such call became DB_DAO_SCOPE_UNRESOLVED.
#   C1/S3: call-site argument types dropped generics/nullability
#       (``List<Expense>`` -> ``List``, ``Long?`` -> ``Long``), so exact
#       overload equality never matched; and an unresolvable argument list
#       failed even when the DAO declared exactly ONE same-name method.
#   T2/T6: TYPE-spelled receivers (``SQLiteDatabase.openDatabase``), and
#       ``database.openHelper.writableDatabase`` chains, were unsupported
#       shapes although the structural policy carries their exact tuples.
#   S1: initializer calls spelled ``.get(``/``.set(`` were counted as
#       property accessors, making single-getter properties look duplicated.


def _write(root: Path, name: str, source: str) -> str:
    (root / name).write_text(source, encoding="utf-8")
    return "app/src/main/java/" + name


def _read_probe_dao(query_sql: str, method: str = "probe",
                    param: str = "value: Int", returns: str = ": Int") -> str:
    return (
        "@androidx.room.Dao\n"
        "interface ScanProbeDao {\n"
        f"    @androidx.room.Query(\"{query_sql}\")\n"
        f"    suspend fun {method}({param}){returns}\n"
        "}\n"
    )


def test_receiver_expression_stops_at_unmatched_open_paren(tmp_path):
    root = _source_root(tmp_path)
    rel_dao = "app/src/main/java/ScanProbeDao.kt"
    source = """package example

class Repo(private val dao: ScanProbeDao) {
    fun run(value: Int): Boolean {
        if (!dao.probe(value)) {
            return false
        }
        return true
    }
}
""" + "\n" + _read_probe_dao("SELECT 1")
    (root / "Repo.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert report.findings == ()
    assert report.statistics["trusted"] is True


def test_argument_types_keep_generics_and_nullability(tmp_path):
    root = _source_root(tmp_path)
    source = """package example

class ProbeEntity

class Repo(private val dao: ScanProbeDao) {
    suspend fun all(items: List<ProbeEntity>): Int {
        return dao.loadAll(items)
    }

    suspend fun flag(id: Long?): Int {
        return dao.findNull(id)
    }
}

@androidx.room.Dao
interface ScanProbeDao {
    @androidx.room.Query("SELECT * FROM probe")
    suspend fun loadAll(items: List<ProbeEntity>): Int

    @androidx.room.Query("SELECT * FROM probe WHERE id IS NULL")
    suspend fun findNull(id: Long?): Int
}
"""
    (root / "Repo.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert report.statistics["trusted"] is True


def test_read_only_operation_skips_unresolvable_arguments(tmp_path):
    """A read-only DAO operation never reaches an authorization decision,
    so an unresolvable argument expression cannot affect the report: the
    call ends at the mutator gate exactly like a resolved-but-unmatched
    read always did.  A MUTATION with the same unresolvable argument keeps
    its honest DB_SIGNATURE_UNRESOLVED (pinned by test_verify_db_access_v2)."""
    root = _source_root(tmp_path)
    source = """package example

class Repo(private val dao: ScanProbeDao) {
    suspend fun run(): Int {
        val marker = produce()
        return dao.probe(marker)
    }
}
""" + "\n" + _read_probe_dao("SELECT 1")
    (root / "Repo.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert report.statistics["trusted"] is True


def test_type_spelled_static_structural_receiver_is_supported(tmp_path):
    root = _source_root(tmp_path)
    rel = _write(root, "Snapshot.kt", """package example

import android.database.sqlite.SQLiteDatabase

class Snapshot {
    fun tryOpen(path: String): SQLiteDatabase? {
        return SQLiteDatabase.openDatabase(path, null, 0)
    }
}
""" + "\n" + _PROBE_DAO)
    structural = [{
        "path": rel,
        "class": "Snapshot",
        "method_pattern": "tryOpen",
        "operation": "openDatabase",
    }]

    report = scan_db_access(root, structural_policy=structural,
                            raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert report.findings == ()
    assert report.statistics["trusted"] is True


def test_open_helper_writable_database_chain_and_read_cursor_ops(tmp_path):
    root = _source_root(tmp_path)
    rel = _write(root, "IntegrityScanner.kt", """package example

import androidx.room.RoomDatabase

class IntegrityScanner(private val database: RoomDatabase) {
    fun check(): Int {
        val db = database.openHelper.writableDatabase
        return db.query("SELECT 1").count
    }
}
""" + "\n" + _PROBE_DAO)
    structural = [{
        "path": rel,
        "class": "IntegrityScanner",
        "method_pattern": "check",
        "operation": "writableDatabase",
    }]

    report = scan_db_access(root, structural_policy=structural,
                            raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert report.findings == ()
    assert report.statistics["trusted"] is True


def test_use_lambda_binds_implicit_it_to_receiver_type(tmp_path):
    root = _source_root(tmp_path)
    rel = _write(root, "Vacuum.kt", """package example

import android.database.sqlite.SQLiteDatabase

class Vacuum {
    fun vacuum(path: String): Boolean {
        val db = SQLiteDatabase.openDatabase(path, null, 0)
        db.use { it.execSQL("VACUUM INTO ?") }
        return true
    }
}
""" + "\n" + _PROBE_DAO)
    structural = [
        {"path": rel, "class": "Vacuum", "method_pattern": "vacuum",
         "operation": "openDatabase"},
        {"path": rel, "class": "Vacuum", "method_pattern": "vacuum",
         "operation": "execSQL"},
    ]

    report = scan_db_access(root, structural_policy=structural,
                            raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert report.findings == ()
    assert report.statistics["trusted"] is True


def test_initializer_get_call_is_not_a_property_accessor(tmp_path):
    root = _source_root(tmp_path)
    (root / "Counter.kt").write_text("""package example

class Counter {
    private val atomic = java.util.concurrent.atomic.AtomicInteger()
    val rows: Int get() = atomic.get()
}
""" + "\n" + _PROBE_DAO, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert report.statistics["trusted"] is True


def test_unauthorized_static_structural_open_still_fails_closed(tmp_path):
    """Without a structural exception the supported static form becomes an
    exact forbidden-operation finding -- never a silent pass."""
    root = _source_root(tmp_path)
    _write(root, "Snapshot.kt", """package example

import android.database.sqlite.SQLiteDatabase

class Snapshot {
    fun tryOpen(path: String): SQLiteDatabase? {
        return SQLiteDatabase.openDatabase(path, null, 0)
    }
}
""" + "\n" + _PROBE_DAO)

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert [finding.rule for finding in report.findings] == [
        "DB_FORBIDDEN_STRUCTURAL_OPERATION",
    ]
    assert report.statistics["trusted"] is True


# ── GR-07 hardening: argument-type resolution must not fabricate types ───────
#
# The untyped-local factory inference previously matched mid-identifier
# substrings (``operationRunDao.getByCorrelationId(`` -> "RunDao",
# ``timeProvider.now()`` -> "Provider"), so ``_argument_types`` compared
# confident-but-wrong tuples against DAO signatures and emitted
# DB_CALL_TARGET_AMBIGUOUS for perfectly ordinary calls.  These tests pin the
# anchored inference: leading-shape factories still infer, every other
# initializer stays an honest unresolved local (fail closed).


def test_receiver_types_reject_mid_identifier_factory_substrings():
    source = """package example

class Repo(private val operationRunDao: ScanProbeDao) {
    suspend fun import(correlationId: String) {
        val runId = operationRunDao.probe(correlationId)
        val cutoffTime = timeProvider.now() - (30L)
        val pattern = categorizationEngineProvider.get().normalize("x")
        val entity = ProbeEntity()
    }
}
"""
    masked = db_guard_scanner_module.mask_kotlin_source(source)
    # Use an offset AFTER every local declaration so each one is evaluated
    # at a point where it is lexically visible.
    use_offset = masked.index("val entity")
    resolved = db_guard_scanner_module._receiver_types(
        source, 0, len(source), use_offset=use_offset,
    )

    assert resolved.get("runId") is None
    assert resolved.get("cutoffTime") is None
    assert resolved.get("pattern") is None


def test_receiver_types_keep_leading_shape_factory_inference():
    source = """package example

class Repo {
    fun create() {
        val entity = ProbeEntity()
        println(entity)
    }
}
"""
    masked = db_guard_scanner_module.mask_kotlin_source(source)
    use_offset = masked.index("println")
    resolved = db_guard_scanner_module._receiver_types(
        source, 0, len(source), use_offset=use_offset,
    )

    assert resolved.get("entity") == "ProbeEntity"


def test_polluted_local_read_call_no_longer_emits_ambiguity(tmp_path):
    """The exact C1 evidence shape: a local initialized from a member chain
    (old scanner bound it to a fabricated type like ``RunDao``) feeding a
    read-only DAO call.  The argument is now honestly unresolved; a READ
    never reaches an authorization decision, so the report stays clean and
    trusted."""
    root = _source_root(tmp_path)
    source = """package example

class Repo(private val dao: ScanProbeDao) {
    suspend fun load(id: String): Int {
        val runId = dao.probe(id)
        return dao.countByRun(runId)
    }
}
""" + "\n" + _read_probe_dao(
        "SELECT COUNT(*) FROM probe WHERE runId = :runId",
        method="countByRun", param="runId: Long", returns=": Int",
    )
    (root / "Repo.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert report.findings == ()
    assert report.statistics["trusted"] is True


def test_polluted_local_mutation_keeps_honest_signature_debt(tmp_path):
    """Fail-closed preserved: the same unresolved argument on a MUTATION
    keeps its controlled DB_SIGNATURE_UNRESOLVED -- it must never become a
    guessed authorization or a silent pass."""
    root = _source_root(tmp_path)
    source = """package example

class Repo(private val dao: ScanProbeDao) {
    suspend fun store(id: String) {
        val marker = dao.probe(id)
        dao.store(marker)
    }
}

@androidx.room.Dao
interface ScanProbeDao {
    @androidx.room.Query("SELECT 1")
    suspend fun probe(value: String): String

    @androidx.room.Insert
    suspend fun store(value: Int)
}
"""
    (root / "Repo.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert [item.code for item in report.diagnostics] == [
        "DB_SIGNATURE_UNRESOLVED",
    ]
    assert report.statistics["trusted"] is False
