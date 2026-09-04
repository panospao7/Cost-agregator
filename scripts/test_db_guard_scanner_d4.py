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

try:
    from scripts.db_guard.policy_v2_loader import load_policy_v2
except ImportError:  # pragma: no cover - flat mode
    from db_guard.policy_v2_loader import load_policy_v2


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
                       "trusted": False, "advisoryDiagnosticCount": 0},
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
                       "trusted": True, "advisoryDiagnosticCount": 0},
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
        "advisoryDiagnosticCount": 0,
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
                       "trusted": False, "advisoryDiagnosticCount": 0},
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
                       "trusted": False, "advisoryDiagnosticCount": 0},
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
        "advisoryDiagnosticCount": 0,
    }
    _assert_repo_relative_posix(report, tmp_path)


# ── PR-GR-07 Slice 2: typed v2 authorization matrix ──────────────────────────


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
                       "trusted": True, "advisoryDiagnosticCount": 0},
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
                       "trusted": True, "advisoryDiagnosticCount": 0},
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
        "advisoryDiagnosticCount": 0,
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



# ── GR-07 Option-B trust contract: BLOCKING vs ADVISORY diagnostics ──────────
#
# Scanner-stage per-callable diagnostics split by the DB relevance of the
# enclosing declaration range: a callable whose range names a DAO
# accessor/operation or a structural DB operation/handle stays BLOCKING
# (untrusted, findings withheld); a callable with NO DB-relevant content
# (Compose/UI/service code that never touches a DAO or DB handle) is reported
# with the bounded controlled_context["advisory"] marker and never breaks
# trust.  Pre-scan stage failures are never advisory.


_UI_STATE_OTHER_PACKAGE = """package com.example.other

data class UiState(val label: String)
"""

# Wave-2 import-precedence adjudication (GR-07 round 6): this fixture
# deliberately carries NO import for ``UiState``.  An explicit
# ``import com.example.multi.UiState`` would legitimately disambiguate the
# two same-simple-name declarations -- the documented resolver precedence is
# explicit imports > wildcard-confirmed > same-package > other, and only a
# collision between TWO wildcard packages still fails closed -- so with the
# import the parameter RESOLVES and the advisory-debt premise of every test
# below silently vanishes.  A bare ``UiState`` against two package
# declarations keeps the honest ambiguity these tests pin.  The no-import
# fail-closed mechanism is independently pinned by
# ``test_ambiguous_cross_package_simple_name_still_fails_closed_in_d4``.
_ADVISORY_UI_REPOSITORY = """package example

class UiRepository {
    fun render(state: UiState): String {
        val text = state.toString()
        return text.trim()
    }
}
"""


def _ambiguous_ui_state_root(tmp_path: Path) -> Path:
    """A tree whose ONLY debt is a non-DB callable with an ambiguous param.

    Two same-simple-name ``UiState`` declarations across packages make the
    closed-world type resolver fail on ``render(state: UiState)`` -- the same
    honest DB_SIGNATURE_UNRESOLVED mechanism the cross-package ambiguity test
    pins -- while the callable body never touches a DAO or DB handle.
    """
    root = _source_root(tmp_path)
    multi = root / "com" / "example" / "multi"
    other = root / "com" / "example" / "other"
    multi.mkdir(parents=True)
    other.mkdir(parents=True)
    multi.joinpath("UiState.kt").write_text(
        "package com.example.multi\n\ndata class UiState(val id: Int)\n",
        encoding="utf-8")
    other.joinpath("UiState.kt").write_text(
        _UI_STATE_OTHER_PACKAGE, encoding="utf-8")
    (root / "UiRepository.kt").write_text(
        _ADVISORY_UI_REPOSITORY + "\n" + _PROBE_DAO, encoding="utf-8")
    return root


def test_advisory_only_unresolved_callable_keeps_scan_trusted(tmp_path):
    """A pure UI callable (no DAO accessor usage, no structural token) whose
    signature cannot resolve is ADVISORY: reported verbatim with the bounded
    marker, trusted stays True, zero blocking diagnostics."""
    root = _ambiguous_ui_state_root(tmp_path)

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert [item.code for item in report.diagnostics] == [
        "DB_SIGNATURE_UNRESOLVED",
    ]
    assert report.diagnostics[0].path == "app/src/main/java/UiRepository.kt"
    assert report.diagnostics[0].controlled_context == {"advisory": True}
    assert report.findings == ()
    assert report.statistics["trusted"] is True
    assert report.statistics["advisoryDiagnosticCount"] == 1


def test_mixed_debt_blocks_only_the_db_touching_callable(tmp_path):
    """One DB-touching callable unresolved + one UI callable unresolved ->
    untrusted with ONLY the DB one blocking; the UI one keeps its advisory
    marker and the advisory counter stays exact."""
    root = _ambiguous_ui_state_root(tmp_path)
    # No UiState import here either: an explicit import would resolve
    # ``persist``'s parameter (wave-2 precedence) and turn the pinned
    # blocking-debt premise into a discovered unauthorized mutation instead.
    (root / "DbRepository.kt").write_text(
        "package com.example.db\n\n"
        "@androidx.room.Dao\n"
        "interface MixedProbeDao {\n"
        "    @androidx.room.Insert\n"
        "    fun store(value: Int)\n"
        "}\n\n"
        "class DbRepository(private val mixedDao: MixedProbeDao) {\n"
        "    fun persist(state: UiState) {\n"
        "        mixedDao.store(1)\n"
        "    }\n"
        "}\n",
        encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    by_path = {item.path: item for item in report.diagnostics}
    assert set(by_path) == {
        "app/src/main/java/UiRepository.kt",
        "app/src/main/java/DbRepository.kt",
    }
    assert by_path["app/src/main/java/UiRepository.kt"].code == (
        "DB_SIGNATURE_UNRESOLVED"
    )
    assert by_path["app/src/main/java/UiRepository.kt"].controlled_context == {
        "advisory": True,
    }
    assert by_path["app/src/main/java/DbRepository.kt"].controlled_context == {}
    assert report.findings == ()  # blocking DB debt withholds findings
    assert report.statistics["trusted"] is False
    assert report.statistics["advisoryDiagnosticCount"] == 1


def test_pre_scan_failure_stays_blocking_regardless_of_advisory(tmp_path):
    """Pre-scan stage failures (here: a non-PolicyEntry ownership item ->
    DB_POLICY_SOURCE_EVIDENCE_INVALID) are NEVER advisory: they stay
    blocking and make the run untrusted even alongside advisory debt."""
    root = _ambiguous_ui_state_root(tmp_path)

    report = scan_db_access(
        root, [{"path": "not-a-policy-entry"}],
        raw_query_policy=_EMPTY_RAW_QUERY_POLICY,
    )

    policy_diagnostic = next(
        item for item in report.diagnostics
        if item.code == "DB_POLICY_SOURCE_EVIDENCE_INVALID"
    )
    assert policy_diagnostic.controlled_context == {}
    assert report.findings == ()
    assert report.statistics["trusted"] is False
    assert report.statistics["advisoryDiagnosticCount"] == 1


def test_verified_handle_unknown_operation_stays_blocking_without_name_evidence(
    tmp_path,
):
    """An unknown operation on a VERIFIED SQLiteDatabase handle is blocking
    even though its range carries no DAO-named call and no structural token:
    the scanner positively computed handle usage, so relevance is forced."""
    root = _source_root(tmp_path)
    (root / "Maintain.kt").write_text("""package example

import android.database.sqlite.SQLiteDatabase

class Maintain {
    fun run(db: SQLiteDatabase) {
        db.someUnknownHandleOp()
    }
}
""" + "\n" + _PROBE_DAO, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert [item.code for item in report.diagnostics] == [
        "DB_STRUCTURAL_SCOPE_UNSUPPORTED",
    ]
    context = report.diagnostics[0].controlled_context
    assert context.get("advisory") is not True
    assert "line" in context
    assert report.statistics["trusted"] is False
    assert report.statistics["advisoryDiagnosticCount"] == 0


def test_zero_blocking_diagnostics_preserve_findings(tmp_path):
    """Option-B exit-1 semantics: with zero BLOCKING diagnostics the
    discovered unauthorized mutation survives as a real GR-08 input finding
    while the advisory debt stays reported and trust holds.

    Round-6 adjudication: the entry deliberately MISMATCHES one identity
    dimension so the mutation stays unauthorized while a policy is present.
    (The previous fixture passed the exactly-matching ``_typed_entry()``, so
    the mutation was authorized and no finding could ever exist -- verified
    identical on the pristine HEAD scanner.)  The UiRepository advisory debt
    stays honest because its fixture carries no disambiguating import.
    """
    root = _typed_root(tmp_path)
    multi = root / "com" / "example" / "multi"
    other = root / "com" / "example" / "other"
    multi.mkdir(parents=True)
    other.mkdir(parents=True)
    multi.joinpath("UiState.kt").write_text(
        "package com.example.multi\n\ndata class UiState(val id: Int)\n",
        encoding="utf-8")
    other.joinpath("UiState.kt").write_text(
        _UI_STATE_OTHER_PACKAGE, encoding="utf-8")
    (root / "UiRepository.kt").write_text(
        _ADVISORY_UI_REPOSITORY, encoding="utf-8")

    report = scan_db_access(
        root, [_typed_entry(parameter_types=("example.OtherItem",))],
        raw_query_policy=_EMPTY_RAW_QUERY_POLICY,
    )

    assert [finding.rule for finding in report.findings] == [
        "DB_UNAUTHORIZED_MUTATION",
    ]
    assert len(report.diagnostics) == 1
    assert report.diagnostics[0].controlled_context == {"advisory": True}
    assert report.statistics["trusted"] is True
    assert report.statistics["advisoryDiagnosticCount"] == 1


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


def test_unique_candidate_mutation_surfaces_real_unauthorized_finding(tmp_path):
    """GR-07 convergence round 6: with EXACTLY ONE declared candidate, the
    argument types cannot change WHICH DAO method is invoked -- there is no
    second overload to select -- so the unique-target mutator proceeds to the
    authorization decision instead of hiding behind DB_SIGNATURE_UNRESOLVED
    argument-environment debt.  With no policy entry owning it, the REAL
    unauthorized mutation surfaces as a trusted-report finding (the round-6
    goal: trusted exit 1 with real findings); a guessed authorization or a
    silent pass remains impossible."""
    root = _source_root(tmp_path)
    source = """package example

class Repo(private val dao: ScanProbeDao) {
    suspend fun store(id: String) {
        dao.store(dao.probe(id))
    }
}

@androidx.room.Dao
interface ScanProbeDao {
    @androidx.room.Query("SELECT 1")
    suspend fun probe(value: String): Int

    @androidx.room.Insert
    suspend fun store(value: Int)
}
"""
    (root / "Repo.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert [item.rule for item in report.findings] == [
        "DB_UNAUTHORIZED_MUTATION",
    ]
    assert report.statistics["trusted"] is True


def test_multi_candidate_mutation_keeps_honest_signature_debt(tmp_path):
    """Fail-closed preserved: an unresolvable argument list on a MUTATION
    whose DAO operation has MULTIPLE declared candidates keeps its controlled
    DB_SIGNATURE_UNRESOLVED -- without the argument types the overload, and
    therefore the authorized identity, is genuinely undeterminate."""
    root = _source_root(tmp_path)
    source = """package example

class Repo(private val dao: ScanProbeDao) {
    suspend fun store(id: String) {
        dao.store(dao.probe(id))
    }
}

@androidx.room.Dao
interface ScanProbeDao {
    @androidx.room.Query("SELECT 1")
    suspend fun probe(value: String): Int

    @androidx.room.Insert
    suspend fun store(value: Int)

    @androidx.room.Insert
    suspend fun store(values: List<Int>)
}
"""
    (root / "Repo.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert [item.code for item in report.diagnostics] == [
        "DB_SIGNATURE_UNRESOLVED",
    ]
    assert report.findings == ()
    assert report.statistics["trusted"] is False


# ── GR-07 convergence round: evidenced structural shapes + honest read C1 ────
#
# Evidence: build/guard-debug/gr07/probe11_baseline_lines.json (line-level
# baseline of all 33 DB_STRUCTURAL_SCOPE_UNSUPPORTED emissions) and
# probe14_classify.json (post-fix residual classification).  Every rule below
# closes ONE evidenced receiver/operation shape while authorization stays
# with the structural policy's exact tuples; each positive test is paired
# with a negative proving non-evidenced shapes still fail closed.


def test_context_get_database_path_is_supported_and_policy_gated(tmp_path):
    """``context.getDatabasePath(...)`` x10 (DatabaseBackupRepositoryImpl,
    FinancialRescueCoordinator): a Context-typed receiver resolves the
    platform shape; the exact structural tuple still authorizes it."""
    root = _source_root(tmp_path)
    rel = _write(root, "Backup.kt", """package example

class Context

class Repo(private val context: Context) {
    fun path() {
        context.getDatabasePath("app.db")
    }
}
""" + "\n" + _PROBE_DAO)
    structural = [{
        "path": rel, "class": "Repo", "method_pattern": "path",
        "operation": "getDatabasePath",
    }]

    report = scan_db_access(root, structural_policy=structural,
                            raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert report.findings == ()
    assert report.statistics["trusted"] is True


def test_get_database_path_on_non_context_receiver_stays_unsupported(tmp_path):
    root = _source_root(tmp_path)
    _write(root, "Weird.kt", """package example

class NotContext

class Repo(private val weird: NotContext) {
    fun path() {
        weird.getDatabasePath("app.db")
    }
}
""" + "\n" + _PROBE_DAO)

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert [item.code for item in report.diagnostics] == [
        "DB_STRUCTURAL_SCOPE_UNSUPPORTED",
    ]
    assert report.statistics["trusted"] is False


def test_file_delete_recursively_is_supported_when_receiver_resolves(tmp_path):
    """``tempDir.deleteRecursively()`` x11 inside restoreCostBackup: the
    File-typed local resolves through constructor inference; the structural
    tuple authorizes the deletion."""
    root = _source_root(tmp_path)
    rel = _write(root, "Cleanup.kt", """package example

class File

class Repo {
    fun cleanup(): Int {
        val tempDir = File()
        tempDir.deleteRecursively()
        return 0
    }
}
""" + "\n" + _PROBE_DAO)
    structural = [{
        "path": rel, "class": "Repo", "method_pattern": "cleanup",
        "operation": "deleteRecursively",
    }]

    report = scan_db_access(root, structural_policy=structural,
                            raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert report.findings == ()
    assert report.statistics["trusted"] is True


def test_delete_recursively_without_policy_entry_is_a_finding(tmp_path):
    """Receiver verification only classifies the access as database
    evidence; without the exact structural tuple it stays an accusation."""
    root = _source_root(tmp_path)
    _write(root, "Cleanup.kt", """package example

class File

class Repo {
    fun cleanup(): Int {
        val tempDir = File()
        tempDir.deleteRecursively()
        return 0
    }
}
""" + "\n" + _PROBE_DAO)

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert [item.rule for item in report.findings] == [
        "DB_FORBIDDEN_STRUCTURAL_OPERATION",
    ]
    assert report.diagnostics == ()


def test_verified_handle_transaction_lifecycle_operations_are_supported(tmp_path):
    """close/beginTransaction/setTransactionSuccessful/endTransaction on a
    VERIFIED SQLiteDatabase handle manage connection state only -- they are
    classified like the read-only cursor APIs and never reach an
    authorization decision (evidence: repairBudgetsSchemaToV86 triad,
    BackupVerifier close x2)."""
    root = _source_root(tmp_path)
    _write(root, "Maintain.kt", """package example

import android.database.sqlite.SQLiteDatabase

class Maintain {
    fun run(db: SQLiteDatabase) {
        db.beginTransaction()
        db.setTransactionSuccessful()
        db.endTransaction()
        db.close()
    }
}
""" + "\n" + _PROBE_DAO)

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert report.findings == ()
    assert report.statistics["trusted"] is True


def test_unknown_operation_on_verified_handle_stays_unsupported(tmp_path):
    root = _source_root(tmp_path)
    _write(root, "Maintain.kt", """package example

import android.database.sqlite.SQLiteDatabase

class Maintain {
    fun run(db: SQLiteDatabase) {
        db.someUnknownHandleOp()
    }
}
""" + "\n" + _PROBE_DAO)

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert [item.code for item in report.diagnostics] == [
        "DB_STRUCTURAL_SCOPE_UNSUPPORTED",
    ]
    assert report.statistics["trusted"] is False


def test_open_helper_chain_with_unresolved_root_is_supported(tmp_path):
    """The freshDb shape x3: ``val freshDb =
    restoreDatabaseOpener.openFreshDatabase()`` needs cross-file return-type
    knowledge no closed scanner has, but ``x.openHelper.writableDatabase``
    is verified by its exact androidx member chain.  Authorization stays
    policy-gated."""
    root = _source_root(tmp_path)
    rel = _write(root, "Verify.kt", """package example

class Opener {
    fun openFreshDatabase(): AppDatabase {
        return AppDatabase()
    }
}

class AppDatabase

class Repo(private val restoreDatabaseOpener: Opener) {
    fun verify() {
        val freshDb = restoreDatabaseOpener.openFreshDatabase()
        freshDb.openHelper.writableDatabase
    }
}
""" + "\n" + _PROBE_DAO)
    structural = [{
        "path": rel, "class": "Repo", "method_pattern": "verify",
        "operation": "writableDatabase",
    }]

    report = scan_db_access(root, structural_policy=structural,
                            raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert report.findings == ()
    assert report.statistics["trusted"] is True


def test_single_member_writable_database_chain_stays_unsupported(tmp_path):
    """No intermediate member: ``wrapper.db.writableDatabase`` has no
    evidenced androidx chain (only ``x.openHelper.writableDatabase`` exists
    in production), so the dotted shape keeps failing closed."""
    root = _source_root(tmp_path)
    _write(root, "Holder.kt", """package example

class Holder
class Wrapper(val db: Holder)

class Repo(private val wrapper: Wrapper) {
    fun touch() {
        wrapper.db.writableDatabase
    }
}
""" + "\n" + _PROBE_DAO)

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert [item.code for item in report.diagnostics] == [
        "DB_STRUCTURAL_SCOPE_UNSUPPORTED",
    ]
    assert report.statistics["trusted"] is False


def test_expression_body_callable_locals_are_visible(tmp_path):
    """Expression-bodied callables (``fun f(): X = withContext(io) { ... }``)
    keep the parser's span at the header, but their locals extend to the
    declaration range's end.  Hiding them made every access on such a local
    fail closed (restoreCostBackup x11 deleteRecursively)."""
    root = _source_root(tmp_path)
    rel = _write(root, "Restore.kt", """package example

class File

class Repo {
    fun cleanup(): Int = withCleanup {
        val tempDir = File()
        tempDir.deleteRecursively()
    }
}
""" + "\n" + _PROBE_DAO)
    structural = [{
        "path": rel, "class": "Repo", "method_pattern": "cleanup",
        "operation": "deleteRecursively",
    }]

    report = scan_db_access(root, structural_policy=structural,
                            raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert report.findings == ()
    assert report.statistics["trusted"] is True


def test_read_only_unique_target_with_mismatched_argument_types_is_silent(tmp_path):
    """The C1 evidence shape: a fabricated argument type (untyped-local
    factory inference ``TimePeriodUtils.startOfMonth(...)`` ->
    "TimePeriodUtils") against a single-candidate READ.  A read never
    reaches an authorization decision, so the non-unique target cannot
    affect the report.  A MUTATION with the same mismatch keeps its pinned
    DB_CALL_TARGET_AMBIGUOUS (test_verify_db_access_v2)."""
    root = _source_root(tmp_path)
    source = """package example

class TimePeriodUtils

class Repo(private val dao: ScanProbeDao) {
    suspend fun load(marker: Long): Int {
        val start = TimePeriodUtils.startOfMonth(marker)
        return dao.countByRun(start)
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


# ── GR-07 convergence round 5: probed-shape regression pins ──────────────────
#
# Each test reproduces a REAL production construct recorded by the round-5
# instrumented full-scan probes (build/guard-debug/gr07/probe_r5_sites.json,
# probe_r5_classified.json, probe_r5_boundary.py) that failed closed before
# the round-5 scanner fixes and must stay resolved after them:
#   RC1: contiguous callable ranges made a declaration bind to the PRECEDING
#        callable (inclusive end-offset match), hiding its own locals/params.
#   RC2: ``_UNTYPED_VAL``'s separator could cross a newline after a blanked
#        string-template initializer, swallowing the NEXT declaration.
#   D1:  ``database.expenseDao()`` accessor-call receivers and their
#        ``val dao = database.expenseDao()`` local-inference shape had no
#        closed type source; both now resolve through the declared
#        @Database accessor map (and fail closed on absence).
#   +:   named call arguments contribute their VALUE's type; self-type
#        lambda dispatches (takeIf/let/also/use) bind ``it`` to the
#        receiver; control-prefix condition groups no longer hide a
#        statement receiver.


def _sweep_dao() -> str:
    """DAO carrying the ``delete`` operation name the File-shaped fixtures
    collide with (probe evidence: stagedDbFile.delete() x6 in
    DatabaseBackupRepositoryImpl.kt)."""
    return (
        "@androidx.room.Dao\n"
        "interface SweepDao {\n"
        "    @androidx.room.Delete\n"
        "    fun delete(value: Int)\n"
        "}\n"
    )


def test_named_argument_insert_contributes_the_constructed_value_type(tmp_path):
    """Probed shape (OperationRunDao call sites): ``insert(OperationRun(id = 1))``
    is ONE constructed value.  The named-argument prefix inside the
    constructor must never leak into argument classification: the whole part
    is a constructor call, so its head type IS the argument type and the
    exact overload matches instead of failing closed as unresolved debt."""
    root = _source_root(tmp_path)
    source = """package example

data class OperationRun(val id: Int)

@androidx.room.Dao
interface OperationRunDao {
    @androidx.room.Insert
    fun insert(run: OperationRun)
}

class SyncRepository(private val operationRunDao: OperationRunDao) {
    fun recordRun() {
        operationRunDao.insert(OperationRun(id = 1))
    }
}
"""
    (root / "SyncRepository.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert [finding.rule for finding in report.findings] == [
        "DB_UNAUTHORIZED_MUTATION",
    ]
    finding = report.findings[0]
    # The discovered callable is the ZERO-parameter ``recordRun``: the
    # constructed-value classification happened at the CALL-SITE argument,
    # whose exact overload match is what allowed this finding to exist at
    # all (pre-fix this same call emitted DB_SIGNATURE_UNRESOLVED).
    assert finding.symbol.name == "recordRun"
    assert finding.symbol.parameters == ()
    assert finding.identity["dao"] == "example.OperationRunDao"
    assert finding.identity["operation"] == "insert"
    assert report.statistics["trusted"] is True


def test_named_argument_strips_parameter_name_for_variable_values(tmp_path):
    """``expenseDao.insert(item = entity)`` carries the VALUE's type: the
    ``item =`` prefix is stripped (comparison operators kept) and ``entity``
    resolves through the lexical environment to the declared parameter type,
    matching the single ``insert(Item)`` overload exactly."""
    root = _source_root(tmp_path)
    source = """package example

data class Item(val id: Int)

@androidx.room.Dao
interface ExpenseDao {
    @androidx.room.Insert
    fun insert(item: Item)
}

class Repository(private val expenseDao: ExpenseDao) {
    fun save(entity: Item) {
        expenseDao.insert(item = entity)
    }
}
"""
    (root / "Repository.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert [finding.rule for finding in report.findings] == [
        "DB_UNAUTHORIZED_MUTATION",
    ]
    assert report.findings[0].symbol.parameters == ("example.Item",)
    assert report.statistics["trusted"] is True


def test_database_accessor_call_receiver_resolves_to_declared_dao_type(tmp_path):
    """Probed shape (backup/restore repositories): ``database.expenseDao()``
    is a NON-bare receiver whose exact closed type comes from the DECLARED
    @Database abstract accessor, resolving to exactly one inventory DAO
    FQCN.  The zero-argument terminal call keeps the chain resolvable; any
    other non-bare shape still fails closed."""
    root = _source_root(tmp_path)
    source = """package example

@androidx.room.Dao
interface AppExpenseDao {
    @androidx.room.Insert
    fun insert(value: Int)
}

@Database(entities = [], version = 1)
abstract class AppDatabase {
    abstract fun expenseDao(): AppExpenseDao
}

class BackupRepository(private val database: AppDatabase) {
    fun persist() {
        database.expenseDao().insert(1)
    }
}
"""
    (root / "BackupRepository.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert [finding.rule for finding in report.findings] == [
        "DB_UNAUTHORIZED_MUTATION",
    ]
    finding = report.findings[0]
    assert finding.identity["dao"] == "example.AppExpenseDao"
    assert finding.identity["accessor"] == "database.expenseDao()"
    assert report.statistics["trusted"] is True


def test_database_accessor_local_inference_types_the_dao(tmp_path):
    """The companion probed shape: ``val dao = database.expenseDao()``
    infers the local's type from the same declared @Database accessor map,
    so the later bare ``dao.insert(...)`` resolves instead of emitting
    DB_DAO_SCOPE_UNRESOLVED."""
    root = _source_root(tmp_path)
    source = """package example

@androidx.room.Dao
interface AppExpenseDao {
    @androidx.room.Insert
    fun insert(value: Int)
}

@Database(entities = [], version = 1)
abstract class AppDatabase {
    abstract fun expenseDao(): AppExpenseDao
}

class BackupRepository(private val database: AppDatabase) {
    fun persist() {
        val dao = database.expenseDao()
        dao.insert(2)
    }
}
"""
    (root / "BackupRepository.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert [finding.rule for finding in report.findings] == [
        "DB_UNAUTHORIZED_MUTATION",
    ]
    finding = report.findings[0]
    assert finding.identity["dao"] == "example.AppExpenseDao"
    assert finding.identity["accessor"] == "dao"
    assert report.statistics["trusted"] is True


def test_get_database_path_local_inference_keeps_platform_handle_silent(tmp_path):
    """Probed shape (DatabaseBackupRepositoryImpl.kt staging family): a
    blanked string-template initializer line must not swallow the NEXT
    declaration (RC2 same-line separator), so ``stagedDbFile`` IS collected;
    ``context.getDatabasePath(...)`` carries the platform return type
    ``File``, and ``stagedDbFile.delete()`` -- colliding with the SweepDao
    ``delete`` operation name -- resolves to a non-DAO handle and stays
    silent instead of emitting DB_DAO_SCOPE_UNRESOLVED."""
    root = _source_root(tmp_path)
    source = """package example

import android.content.Context

class BackupWriter(private val context: Context) {
    fun stage() {
        val stagedDbName = "gr07-staged.db"
        val stagedDbFile = context.getDatabasePath(stagedDbName)
        stagedDbFile.delete()
    }
}
""" + "\n" + _sweep_dao()
    (root / "BackupWriter.kt").write_text(source, encoding="utf-8")
    # ``context.getDatabasePath(...)`` is a SUPPORTED structural shape whose
    # authorization stays with the structural policy's exact tuple (same
    # contract as test_context_get_database_path_is_supported_and_policy_gated);
    # the entry keeps the structural leg silent so this fixture isolates the
    # untyped-local inference behavior under test.
    rel = "app/src/main/java/BackupWriter.kt"
    structural = [{
        "path": rel, "class": "BackupWriter", "method_pattern": "stage",
        "operation": "getDatabasePath",
    }]

    report = scan_db_access(root, structural_policy=structural,
                            raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert report.findings == ()
    assert report.statistics["trusted"] is True


def test_take_if_self_type_lambda_binds_it_to_receiver_type(tmp_path):
    """Probed shape (backup verification): ``file.takeIf { ... }`` binds the
    lambda's implicit single parameter to the RECEIVER ITSELF by Kotlin
    contract.  With ``it`` bound to ``File``, an operation named like a DAO
    mutator on the handle stays classified as a non-DAO platform call; an
    unbound ``it`` would fail closed with DB_DAO_SCOPE_UNRESOLVED."""
    root = _source_root(tmp_path)
    source = """package example

import java.io.File

class CacheCleaner {
    fun sweep(stagedDbFile: File): Boolean {
        return stagedDbFile.takeIf { it.delete() } != null
    }
}
""" + "\n" + _sweep_dao()
    (root / "CacheCleaner.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert report.findings == ()
    assert report.statistics["trusted"] is True


def test_control_prefix_condition_group_does_not_hide_the_receiver(tmp_path):
    """Probed shape (staged-file cleanup): ``if (dst.exists()) dst.delete()``
    parses the second statement's receiver with a leading control-group
    prefix.  The prefix is stripped (a real parenthesized receiver whose
    continuation starts with ``.`` is kept whole), so ``dst`` resolves to
    its declared ``File`` type and the DAO-named operation stays silent."""
    root = _source_root(tmp_path)
    source = """package example

import java.io.File

class StagingCleaner {
    fun clean(stagedDbFile: File) {
        if (stagedDbFile.exists()) stagedDbFile.delete()
    }
}
""" + "\n" + _sweep_dao()
    (root / "StagingCleaner.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert report.findings == ()
    assert report.statistics["trusted"] is True


def test_contiguous_callable_declarations_bind_to_their_own_callable(tmp_path):
    """RC1 boundary regression (probed: ExpenseGroupDao
    insertGroupWithMembers bound to setActiveStatus; OperationRunRecorder /
    WorkerRunLogger event-writer chains).  Callable ranges are CONTIGUOUS --
    a bodyless sibling's end offset EQUALS the next callable's start -- so
    matching must be HALF-OPEN.  The default-body method's declaration binds
    to ITS OWN callable, so its ``member`` parameter fills the lexical
    environment and the mutation resolves against the exact
    ``insertGroup(GroupEntity)`` overload instead of failing closed with the
    PRECEDING callable's (wrong) environment."""
    root = _source_root(tmp_path)
    source = """package example

data class GroupEntity(val id: Int)

@androidx.room.Dao
interface GroupProbeDao {
    @androidx.room.Insert
    fun insert(value: Int)

    @androidx.room.Insert
    fun insertGroup(group: GroupEntity)
}

interface GroupHandler {
    val groupProbeDao: GroupProbeDao

    fun setActiveStatus(active: Boolean): Int

    fun insertGroupWithMembers(member: GroupEntity): Int {
        return groupProbeDao.insertGroup(member)
    }
}
"""
    (root / "GroupHandler.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert [finding.rule for finding in report.findings] == [
        "DB_UNAUTHORIZED_MUTATION",
    ]
    finding = report.findings[0]
    assert finding.symbol.name == "insertGroupWithMembers"
    assert finding.symbol.parameters == ("example.GroupEntity",)
    assert finding.identity["dao"] == "example.GroupProbeDao"
    assert finding.identity["operation"] == "insertGroup"
    assert report.statistics["trusted"] is True


# ── GR-07 final convergence: per-rule regression pins ────────────────────────
#
# Each closed rule of the final convergence round gets ONE positive test
# reproducing the REAL probed production shape and ONE fail-closed negative
# proving the neighboring non-evidenced shape still reports honest debt.
# Observability convention: every fixture declares a DAO whose operation NAME
# collides with the platform method under test (probe/delete/update/count/
# getAll/insert), so a correctly resolved non-DAO receiver stays SILENT (its
# type is not a DAO FQCN) while an unresolved receiver emits the blocking
# DB_DAO_SCOPE_UNRESOLVED diagnostic -- the same collision the production
# tree exhibits (stagedDbFile.delete() x6, digest.update(...), ...).


def _update_probe_dao() -> str:
    return (
        "@androidx.room.Dao\n"
        "interface UpdateProbeDao {\n"
        "    @androidx.room.Update\n"
        "    fun update(value: Int)\n"
        "}\n"
    )


def _count_probe_dao() -> str:
    return (
        "@androidx.room.Dao\n"
        "interface CountProbeDao {\n"
        "    @androidx.room.Query(\"SELECT COUNT(*) FROM probe\")\n"
        "    suspend fun count(): Int\n"
        "}\n"
    )


def _index_probe_dao() -> str:
    return (
        "@androidx.room.Dao\n"
        "interface IndexProbeDao {\n"
        "    @androidx.room.Insert\n"
        "    fun insert(value: Int)\n"
        "    @androidx.room.Query(\"SELECT * FROM probe\")\n"
        "    suspend fun getAll(): Int\n"
        "}\n"
    )


# ── Rule: safe-call receivers resolve through the lexical environment ────────


def test_safe_call_on_cast_tailed_local_surfaces_the_real_mutation(tmp_path):
    """BudgetAutopilotEngine.kt:239-243: ``val dao = daoField.get(repo)
    as? ExpenseDao`` types the local through the cast tail and the safe call
    ``dao?.probe(...)`` names the SAME declared identity as the bare form --
    nullability changes WHEN the call happens, never WHICH identity it names.
    The resolved mutation surfaces as a real unauthorized-mutation finding on
    a trusted report instead of hiding behind DB_DAO_SCOPE_UNRESOLVED."""
    root = _source_root(tmp_path)
    source = """package example

class ReflectionHolder(val probeDao: ScanProbeDao)

class BridgeRepository(private val multiCurrencyRepository: ReflectionHolder) {
    fun bridge() {
        val daoField = multiCurrencyRepository.javaClass.getDeclaredField("probeDao")
            .also { it.isAccessible = true }
        val dao = daoField.get(multiCurrencyRepository) as? ScanProbeDao
        dao?.probe(1)
    }
}
""" + "\n" + _PROBE_DAO
    (root / "BridgeRepository.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert [finding.rule for finding in report.findings] == [
        "DB_UNAUTHORIZED_MUTATION",
    ]
    finding = report.findings[0]
    assert finding.symbol.name == "bridge"
    assert finding.identity["dao"] == "example.ScanProbeDao"
    assert finding.identity["accessor"] == "dao"
    assert report.statistics["trusted"] is True


def test_safe_call_on_unresolved_receiver_stays_fail_closed(tmp_path):
    """The safe-call ``?`` never fabricates a receiver: a name absent from
    the lexical environment keeps the structured blocking diagnostic."""
    root = _source_root(tmp_path)
    source = """package example

class GhostRepository {
    fun bridge() {
        ghost?.probe(1)
    }
}
""" + "\n" + _PROBE_DAO
    (root / "GhostRepository.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert [item.code for item in report.diagnostics] == [
        "DB_DAO_SCOPE_UNRESOLVED",
    ]
    assert report.findings == ()
    assert report.statistics["trusted"] is False


# ── Rule: closed qualified static factories (MessageDigest / File) ───────────


def test_message_digest_static_factory_types_the_digest_local(tmp_path):
    """CostbackupBundle.kt:601 / ReceiptAssetStore.kt:127: ``val digest =
    MessageDigest.getInstance("SHA-256")`` carries the platform return type
    by API contract, so ``digest.update(...)`` -- colliding with the
    UpdateProbeDao ``update`` operation name -- resolves to a non-DAO handle
    and stays silent instead of emitting DB_DAO_SCOPE_UNRESOLVED."""
    root = _source_root(tmp_path)
    source = """package example

class Hasher {
    fun digestChunk(): Int {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(1)
        return 0
    }
}
""" + "\n" + _update_probe_dao()
    (root / "Hasher.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert report.findings == ()
    assert report.statistics["trusted"] is True


def test_companion_factory_return_stays_unresolved(tmp_path):
    """Negative (no declared return): a companion factory WITHOUT an explicit
    return type carries no parseable return fact, so
    ``MerchantKeyGenerator.generate(...)`` (TransactionLifecycleCoordinator
    round-6 evidence) must never fabricate the receiver's type from the
    receiver OBJECT's name and the local stays honestly unresolved.  GR-07
    convergence close-out: a factory WITH a declared, project-wide unique
    return type now resolves through the n-arg declared-return map (see
    ``test_n_arg_declared_return_types_the_for_each_element``); this fixture
    pins the still-fail-closed undeclared spelling."""
    root = _source_root(tmp_path)
    source = """package example

class KeyMaterial

class MerchantKeyGenerator {
    companion object {
        fun generate(id: Int) = KeyMaterial()
    }
}

class SignService {
    fun sign(id: Int) {
        val material = MerchantKeyGenerator.generate(id)
        material.update(1)
    }
}
""" + "\n" + _update_probe_dao()
    (root / "SignService.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert [item.code for item in report.diagnostics] == [
        "DB_DAO_SCOPE_UNRESOLVED",
    ]
    assert report.findings == ()
    assert report.statistics["trusted"] is False


def test_file_create_temp_file_static_factory_types_the_temp_local(tmp_path):
    """DebugViewModel.kt:438 / BackupRestoreViewModel.kt:158:
    ``File.createTempFile(...)`` -> ``File`` by API contract, so
    ``temp.delete()`` -- colliding with the SweepDao ``delete`` operation
    name -- resolves to a non-DAO handle and stays silent."""
    root = _source_root(tmp_path)
    source = """package example

import java.io.File

class Importer(private val context: Context) {
    fun prepare(): Boolean {
        val temp = File.createTempFile("import_", ".db", context.cacheDir)
        return temp.delete()
    }
}

class Context
""" + "\n" + _sweep_dao()
    (root / "Importer.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert report.findings == ()
    assert report.statistics["trusted"] is True


def test_non_platform_create_temp_file_factory_stays_unresolved(tmp_path):
    """Negative (no declared return): only the closed ``Head.member`` map
    carries static-factory facts, and a same-shaped project factory keeps
    the local honestly unresolved unless it DECLARES a parseable return
    type.  GR-07 convergence close-out: a declared, project-wide unique
    return type now resolves through the n-arg declared-return map (see
    ``test_n_arg_declared_return_types_the_for_each_element``); this fixture
    pins the still-fail-closed undeclared spelling."""
    root = _source_root(tmp_path)
    source = """package example

class CachedFile

class TempManager {
    companion object {
        fun createTempFile(prefix: String, suffix: String) = CachedFile()
    }
}

class ImportService {
    fun prepare() {
        val temp = TempManager.createTempFile("import_", ".db")
        temp.delete()
    }
}
""" + "\n" + _sweep_dao()
    (root / "ImportService.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert [item.code for item in report.diagnostics] == [
        "DB_DAO_SCOPE_UNRESOLVED",
    ]
    assert report.findings == ()
    assert report.statistics["trusted"] is False


# ── Rule: multiline initializer + db.use { it.execSQL } (SqliteSnapshotCreator)


def test_multiline_open_database_use_lambda_authorizes_vacuum_shape(tmp_path):
    """SqliteSnapshotCreator.tryVacuumInto: the ``SQLiteDatabase.openDatabase(``
    initializer spans three lines (multiline initializer extension), the
    ``db.use { it.execSQL(...) }`` dispatch binds ``it`` to the receiver's
    handle type, and the catch block's ``target.delete()`` stays silent on
    the resolved File parameter.  Both structural operations stay authorized
    by their exact policy tuples."""
    root = _source_root(tmp_path)
    rel = _write(root, "SnapshotCreator.kt", """package example

import android.database.sqlite.SQLiteDatabase
import java.io.File

class SnapshotCreator {
    fun tryVacuumInto(source: File, target: File): Boolean {
        return try {
            val db = SQLiteDatabase.openDatabase(
                source.absolutePath, null, SQLiteDatabase.OPEN_READWRITE
            )
            db.use { it.execSQL("VACUUM INTO ?", arrayOf(target.absolutePath)) }
            true
        } catch (e: Exception) {
            target.delete()
            false
        }
    }
}
""" + "\n" + _sweep_dao())
    structural = [
        {"path": rel, "class": "SnapshotCreator",
         "method_pattern": "tryVacuumInto", "operation": "openDatabase"},
        {"path": rel, "class": "SnapshotCreator",
         "method_pattern": "tryVacuumInto", "operation": "execSQL"},
    ]

    report = scan_db_access(root, structural_policy=structural,
                            raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert report.findings == ()
    assert report.statistics["trusted"] is True


def test_use_lambda_over_unresolved_receiver_keeps_structural_debt(tmp_path):
    """Negative: an unresolved ``db`` binding never binds ``it``, so the
    structural ``execSQL`` on the unbound lambda parameter keeps its honest
    blocking DB_STRUCTURAL_SCOPE_UNSUPPORTED."""
    root = _source_root(tmp_path)
    _write(root, "BrokenVacuum.kt", """package example

import java.io.File

class BrokenVacuum {
    fun vacuum(target: File): Boolean {
        val db = opener.open("x.db")
        db.use { it.execSQL("VACUUM INTO ?") }
        return true
    }
}
""" + "\n" + _PROBE_DAO)

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert [item.code for item in report.diagnostics] == [
        "DB_STRUCTURAL_SCOPE_UNSUPPORTED",
    ]
    assert report.findings == ()
    assert report.statistics["trusted"] is False


# ── Rule: companion visibility + closed Regex.findAll chain (OcrLanguageProcessor)


def test_companion_regex_find_all_chain_resolves_and_stays_silent(tmp_path):
    """OcrLanguageProcessor.kt:20-39: the companion ``Regex`` val is visible
    to the owner's methods (companion members are class-static surface) and
    ``findAll`` carries the closed platform return type
    ``Sequence<MatchResult>``, so the chained ``count()`` -- colliding with
    the CountProbeDao read operation name -- resolves to a non-DAO receiver
    and stays silent."""
    root = _source_root(tmp_path)
    source = """package example

class OcrProcessor {
    companion object {
        private val GREEK_CHARS = Regex("[Α-Ωα-ωάέήίόύώ]")
    }

    fun detect(text: String): Int {
        return GREEK_CHARS.findAll(text).count()
    }
}
""" + "\n" + _count_probe_dao()
    (root / "OcrProcessor.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert report.findings == ()
    assert report.statistics["trusted"] is True


def test_regex_member_outside_closed_map_fails_closed(tmp_path):
    """Negative (non-platform member): ``matches`` is not in the closed
    member-return map, so the chain stays unresolved and the DAO-named
    ``count()`` on it keeps its blocking diagnostic."""
    root = _source_root(tmp_path)
    source = """package example

class BrokenOcr {
    companion object {
        private val GREEK_CHARS = Regex("[a-z]")
    }

    fun detect(text: String): Int {
        return GREEK_CHARS.matches(text).count()
    }
}
""" + "\n" + _count_probe_dao()
    (root / "BrokenOcr.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert [item.code for item in report.diagnostics] == [
        "DB_DAO_SCOPE_UNRESOLVED",
    ]
    assert report.findings == ()
    assert report.statistics["trusted"] is False


# ── Rule: ImageCache member chains (cacheDir / listFiles / entries / iterator)


def test_image_cache_member_chains_resolve_end_to_end(tmp_path):
    """ImageCache.kt:27-29/119/173-177 composite: the untyped ``cacheDir``
    member infers through the closed Context.cacheDir/resolve chain, the
    ``LinkedHashMap<String, CacheEntry>(...)`` generic constructor spells its
    own K/V, ``listFiles()?.forEach { it.delete() }`` binds ``it`` to the
    Array<File> element, and ``entries.iterator()`` / ``next().value`` walk
    the spelled container to ``entry.file`` (same-file property map).  Every
    DAO-named ``delete`` stays silent on the resolved non-DAO handles."""
    root = _source_root(tmp_path)
    source = """package example

import java.io.File

class ImageCache(private val context: Context) {
    private data class CacheEntry(val file: File, var sizeBytes: Long)

    private val cacheDir = context.cacheDir.resolve("image_cache")
    private val cacheEntries = LinkedHashMap<String, CacheEntry>(16, 0.75f, true)

    fun clear() {
        cacheDir.listFiles()?.forEach { it.delete() }
    }

    fun evict() {
        val iterator = cacheEntries.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next().value
            entry.file.delete()
        }
    }
}

class Context
""" + "\n" + _sweep_dao()
    (root / "ImageCache.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert report.findings == ()
    assert report.statistics["trusted"] is True


def test_unresolved_container_breaks_the_entry_chain_fail_closed(tmp_path):
    """Negative: an unresolved map binding breaks the whole
    entries/iterator/next/value chain, so the DAO-named ``delete`` on the
    unresolved ``entry.file`` receiver keeps its blocking diagnostic."""
    root = _source_root(tmp_path)
    source = """package example

class BrokenCache {
    fun evict() {
        val iterator = mystery.entries.iterator()
        val entry = iterator.next().value
        entry.file.delete()
    }
}
""" + "\n" + _sweep_dao()
    (root / "BrokenCache.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert [item.code for item in report.diagnostics] == [
        "DB_DAO_SCOPE_UNRESOLVED",
    ]
    assert report.findings == ()
    assert report.statistics["trusted"] is False


# ── Rule: multiline listFiles{...}?.sortedBy{...} chain (DatabaseBackupRepositoryImpl)


def test_multiline_list_files_sorted_by_chain_types_the_backups_local(tmp_path):
    """DatabaseBackupRepositoryImpl.cleanupOldSafetyBackups:2457-2463: the
    ``listFiles { ... }`` initializer spans three lines (multiline
    extension), the closed chain types it ``Array<File>``, and
    ``sortedBy``/``take`` preserve the ``File`` element type so ``forEach``
    binds ``it`` and the DAO-named ``delete`` stays silent."""
    root = _source_root(tmp_path)
    source = """package example

import java.io.File

class BackupCleaner {
    fun cleanupOldSafetyBackups(backupDir: File) {
        val backups = backupDir.listFiles { file ->
            file.name.startsWith("SAFETY_")
        }?.sortedBy { it.lastModified() } ?: return
        if (backups.size > 3) {
            backups.take(backups.size - 3).forEach { it.delete() }
        }
    }
}
""" + "\n" + _sweep_dao()
    (root / "BackupCleaner.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert report.findings == ()
    assert report.statistics["trusted"] is True


def test_unresolved_list_files_root_keeps_the_chain_fail_closed(tmp_path):
    """Negative: an unresolved ``listFiles`` receiver leaves ``backups``
    untyped, so the element-typed ``forEach`` never binds ``it`` and the
    DAO-named ``delete`` keeps its blocking diagnostic."""
    root = _source_root(tmp_path)
    source = """package example

class BrokenCleaner {
    fun cleanup() {
        val backups = mysteryDir.listFiles { file ->
            file.name.startsWith("SAFETY_")
        }?.sortedBy { it.lastModified() } ?: return
        backups.take(1).forEach { it.delete() }
    }
}
""" + "\n" + _sweep_dao()
    (root / "BrokenCleaner.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert [item.code for item in report.diagnostics] == [
        "DB_DAO_SCOPE_UNRESOLVED",
    ]
    assert report.findings == ()
    assert report.statistics["trusted"] is False


# ── Rule: withContext last-expression (DebugViewModel) ───────────────────────


def test_with_context_last_expression_types_the_temp_file_local(tmp_path):
    """DebugViewModel.kt:436-448: ``withContext(Dispatchers.IO) { ... }``
    returns its lambda's LAST expression; the two-pass inference sees the
    lambda-local ``temp`` (typed by the File.createTempFile static factory)
    even though the dispatch text precedes it, so ``tempFile.delete()`` --
    colliding with the SweepDao ``delete`` operation name -- stays silent on
    the resolved File binding."""
    root = _source_root(tmp_path)
    source = """package example

import java.io.File

class DebugImporter(private val context: Context) {
    fun importDatabase(): Boolean {
        val tempFile = withContext(Dispatchers.IO) {
            try {
                val temp = File.createTempFile("import_", ".db", context.cacheDir)
                temp
            } catch (e: Exception) {
                null
            }
        }
        if (tempFile == null) {
            return false
        }
        return tempFile.delete()
    }
}

class Context
""" + "\n" + _sweep_dao()
    (root / "DebugImporter.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert report.findings == ()
    assert report.statistics["trusted"] is True


def test_with_context_unknown_last_expression_fails_closed(tmp_path):
    """Negative: a last-expression identifier absent from the lexical
    environment leaves the dispatch result unresolved."""
    root = _source_root(tmp_path)
    source = """package example

class BrokenImporter {
    fun load(): Boolean {
        val tempFile = withContext(Dispatchers.IO) {
            mystery
        }
        return tempFile.delete()
    }
}
""" + "\n" + _sweep_dao()
    (root / "BrokenImporter.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert [item.code for item in report.diagnostics] == [
        "DB_DAO_SCOPE_UNRESOLVED",
    ]
    assert report.findings == ()
    assert report.statistics["trusted"] is False


# ── Rule: project-wide unique zero-arg function return map ───────────────────


def test_true_zero_arg_call_carries_the_declared_return_type(tmp_path):
    """Initializer path, positive: a genuinely empty RAW argument list lets
    ``registry.resolve()`` carry the declared ``ScanProbeDao`` return type,
    so the later mutation is a real discovered identity (unauthorized
    finding on a trusted report) instead of unresolved-scope debt."""
    root = _source_root(tmp_path)
    source = """package example

class Registry(private val probeDao: ScanProbeDao) {
    fun resolve(): ScanProbeDao = probeDao
}

class Consumer(private val registry: Registry) {
    fun run() {
        val dao = registry.resolve()
        dao.probe(1)
    }
}
""" + "\n" + _PROBE_DAO
    (root / "Consumer.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert [finding.rule for finding in report.findings] == [
        "DB_UNAUTHORIZED_MUTATION",
    ]
    finding = report.findings[0]
    assert finding.identity["dao"] == "example.ScanProbeDao"
    assert finding.identity["accessor"] == "dao"
    assert report.statistics["trusted"] is True


def test_same_owner_zero_arg_returns_resolve_both_receiver_shapes(tmp_path):
    """``StringBKTree.create()`` (companion factory, same owner) and
    ``getCategoryRepository()`` (private same-class provider,
    CategorizationEngine.kt:451/481) both resolve through the project-wide
    unique zero-arg declaration map: ``tree.insert(...)`` and
    ``getCategoryRepository().getAll()`` land on non-DAO receivers and stay
    silent instead of emitting DB_DAO_SCOPE_UNRESOLVED for the colliding
    IndexProbeDao operation names."""
    root = _source_root(tmp_path)
    source = """package example

class StringBKTree private constructor() {
    companion object {
        fun create(): StringBKTree = StringBKTree()
    }

    suspend fun insert(item: String) {
    }
}

class CategoryRepository {
    fun getAll(): Int = 0
}

class Engine {
    private fun getCategoryRepository(): CategoryRepository = CategoryRepository()

    fun buildAndRefresh(): Int {
        val tree = StringBKTree.create()
        tree.insert("seed")
        return getCategoryRepository().getAll()
    }
}
""" + "\n" + _index_probe_dao()
    (root / "Engine.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert report.findings == ()
    assert report.statistics["trusted"] is True


def test_masked_string_argument_never_passes_for_zero_arity(tmp_path):
    """THE fail-closed arity pin: ``registry.resolve("image_cache")`` is
    byte-identical to ``registry.resolve()`` in MASKED text (string-literal
    content and quotes are blanked), so the zero-arg emptiness test must run
    on the RAW initializer text.  The removed masked-only fallback bound
    ``dao`` to the declared return type and let the mutation reach an
    authorization decision fabricated from a masked spelling; the raw-text
    guard keeps the local honestly unresolved (blocking
    DB_DAO_SCOPE_UNRESOLVED, zero findings).  GR-07 convergence close-out:
    the n-arg declared-return map cannot claim the call either -- the name
    is declared at BOTH arities, an overload set the closed scanner refuses
    to arbitrate, so the dual-arity refusal keeps it out even though the
    one-arg declaration alone would be unanimous."""
    root = _source_root(tmp_path)
    source = """package example

class Registry(private val probeDao: ScanProbeDao) {
    fun resolve(): ScanProbeDao = probeDao
    fun resolve(prefix: String): ScanProbeDao = probeDao
}

class Consumer(private val registry: Registry) {
    fun run() {
        val dao = registry.resolve("image_cache")
        dao.probe(1)
    }
}
""" + "\n" + _PROBE_DAO
    (root / "Consumer.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert [item.code for item in report.diagnostics] == [
        "DB_DAO_SCOPE_UNRESOLVED",
    ]
    assert report.findings == ()
    assert report.statistics["trusted"] is False


def test_ambiguous_zero_arg_declarations_stay_out_of_the_map(tmp_path):
    """Negative (ambiguous same-owner functions): two same-name zero-arg
    declarations disagreeing on the return type keep the name out of the
    project-wide map, so the initializer stays unresolved and the DAO-named
    call on it emits the blocking diagnostic instead of guessing either
    type."""
    root = _source_root(tmp_path)
    source = """package example

class AmbiguousRegistry(private val probeDao: ScanProbeDao) {
    fun resolve(): ScanProbeDao = probeDao
    fun resolve(): Int = 1
}

class AmbiguousConsumer(private val ambiguousRegistry: AmbiguousRegistry) {
    fun run() {
        val dao = ambiguousRegistry.resolve()
        dao.probe(1)
    }
}
""" + "\n" + _PROBE_DAO
    (root / "AmbiguousConsumer.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert [item.code for item in report.diagnostics] == [
        "DB_DAO_SCOPE_UNRESOLVED",
    ]
    assert report.findings == ()
    assert report.statistics["trusted"] is False


# ── Rule: project-wide unique n-arg function declared-return map ─────────────
#
# GR-07 convergence close-out: the zero-arg map above generalizes to
# n-ARGUMENT declarations whose return type is parseable at the declaration
# site (``fun name(params): T`` -- T taken verbatim, bodies never read).
# Resolution: ``name(args)`` -> T; the existing forEach element binding then
# types the lambda parameter from a ``List<X>`` result.  Fail-closed:
# declarations without an explicit return type, same-name declarations with
# conflicting return types, and (by construction -- a one-step verbatim
# signature extraction, capped at the top inference depth) recursive
# self-reference.


def test_n_arg_declared_return_types_the_for_each_element(tmp_path):
    """Positive, the MerchantNormalizer.learnMerchantAlias shape: the
    repository method DECLARES ``List<MerchantAlias>`` for a one-argument
    call (``getAliasesForCanonical(canonicalId: Long)``), the declared type
    types the ``val aliases`` local, the existing forEach element binding
    types the lambda parameter, and the mutation on the bound element inside
    resolves to its exact overload identity -- a real unauthorized-mutation
    finding on a trusted report.  The two-overload DAO makes the assertion
    DEPEND on the binding: without the declared return type the argument
    stays unresolved and the ambiguous mutator set emits
    DB_SIGNATURE_UNRESOLVED instead.  The production lambda spells a named
    ``alias ->`` parameter; the closed machinery binds the implicit ``it``
    (the bound param), so the fixture uses that spelling."""
    root = _source_root(tmp_path)
    source = """package example

class MerchantAlias(val id: Long)

class MerchantAliasRepository {
    suspend fun getAliasesForCanonical(canonicalId: Long): List<MerchantAlias> =
        emptyList()
}

@androidx.room.Dao
interface AliasProbeDao {
    @androidx.room.Insert
    fun probe(value: Int)
    @androidx.room.Insert
    fun probe(value: MerchantAlias)
}

class AliasConsumer(
    private val repository: MerchantAliasRepository,
    private val probeDao: AliasProbeDao
) {
    suspend fun learn(oldCanonicalId: Long) {
        val aliases = repository.getAliasesForCanonical(oldCanonicalId)
        aliases.forEach { probeDao.probe(it) }
    }
}
"""
    (root / "AliasConsumer.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert [finding.rule for finding in report.findings] == [
        "DB_UNAUTHORIZED_MUTATION",
    ]
    finding = report.findings[0]
    assert finding.identity["dao"] == "example.AliasProbeDao"
    assert finding.identity["accessor"] == "probeDao"
    assert finding.identity["operation"] == "probe"
    assert report.statistics["trusted"] is True


def test_accessor_shaped_zero_arg_call_reaches_the_project_map(tmp_path):
    """The LAST blocking emission (MerchantNormalizer.getOrBuildTree:296):
    ``val tree = StringBKTree.create()`` is accessor-SHAPED text over a
    non-accessor name.  With a @Database declared in the tree -- always true
    in production -- the round-5 accessor branch consumed the whole
    resolution chain on its miss, the project declared-return maps stayed
    unreachable, ``tree`` stayed unresolved, and the DAO-named
    ``tree.insert(...)`` emitted DB_DAO_SCOPE_UNRESOLVED.  A missed accessor
    lookup now falls through to the project maps, so the initializer carries
    the declared ``StringBKTree`` and the scan is clean and trusted."""
    root = _source_root(tmp_path)
    source = """package example

import androidx.room.Database

class StringBKTree private constructor() {
    companion object {
        fun create(): StringBKTree = StringBKTree()
    }

    suspend fun insert(item: Int) {
    }
}

@Database(entities = [], version = 1)
abstract class AppDatabase {
    abstract fun probeDao(): IndexProbeDao
}

class TreeNormalizer {
    suspend fun getOrBuildTree(probeDao: IndexProbeDao) {
        val tree = StringBKTree.create()
        tree.insert(1)
    }
}
""" + "\n" + _index_probe_dao()
    (root / "TreeNormalizer.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert report.findings == ()
    assert report.statistics["trusted"] is True


def test_ambiguous_n_arg_declarations_stay_out_of_the_map(tmp_path):
    """Negative (ambiguous same-name functions): two same-name one-argument
    declarations disagreeing on the return type keep the name out of the
    project-wide map, so the initializer stays unresolved and the DAO-named
    call on it emits the blocking diagnostic instead of guessing either
    type."""
    root = _source_root(tmp_path)
    source = """package example

class AmbiguousRepository(private val probeDao: ScanProbeDao) {
    fun resolve(id: Long): ScanProbeDao = probeDao
    fun resolve(key: String): Int = 1
}

class AmbiguousConsumer(private val ambiguousRepository: AmbiguousRepository) {
    fun run() {
        val dao = ambiguousRepository.resolve(7L)
        dao.probe(1)
    }
}
""" + "\n" + _PROBE_DAO
    (root / "AmbiguousConsumer.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert [item.code for item in report.diagnostics] == [
        "DB_DAO_SCOPE_UNRESOLVED",
    ]
    assert report.findings == ()
    assert report.statistics["trusted"] is False


def test_n_arg_call_without_declared_return_stays_unresolved(tmp_path):
    """Negative (missing declared return): a one-argument declaration with an
    expression body and NO explicit return type is never collected, so the
    call stays unresolved and the DAO-named call on it keeps the blocking
    diagnostic (fail closed)."""
    root = _source_root(tmp_path)
    source = """package example

class OpaqueRepository(private val probeDao: ScanProbeDao) {
    fun resolve(id: Long) = probeDao
}

class OpaqueConsumer(private val opaqueRepository: OpaqueRepository) {
    fun run() {
        val dao = opaqueRepository.resolve(7L)
        dao.probe(1)
    }
}
""" + "\n" + _PROBE_DAO
    (root / "OpaqueConsumer.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert [item.code for item in report.diagnostics] == [
        "DB_DAO_SCOPE_UNRESOLVED",
    ]
    assert report.findings == ()
    assert report.statistics["trusted"] is False


# ── GR-07 activation: v2-policy characterization ─────────────────────────────
#
# scripts/test_db_guard_active_policy_blocked.py characterized the PRE-v2
# activation state (exit 2, the single DB_POLICY_SOURCE_EVIDENCE_INVALID
# umbrella diagnostic) and declared itself obsolete once the gate goes green.
# With typed v2 authorization activated that state is gone: a schemaVersion-2
# document loads through ``load_policy_v2`` and the loaded entries drive
# ``scan_db_access`` with ZERO policy diagnostics.  This test is the
# activated-truth replacement; ORCHESTRATOR NOTE: the blocked-state
# characterization file is obsolete and should be removed.


_V2_CHARACTERIZATION_YAML = """schemaVersion: 2
entries:
- path: app/src/main/java/example/TypedRepository.kt
  ownerFqcn: example.Repository
  kind: function
  method: save
  receiver: null
  parameterTypes:
  - example.Item
  daoAccessor: expenseDao
  daoFqcn: example.ExpenseDao
  operation: insert
  barrierMode: helper
  reason: gr07-activation-characterization
  owner: '@gr07'
  linkedIssue: GR-07
"""


def test_v2_policy_loads_and_pipeline_runs_without_policy_diagnostic(tmp_path):
    """Activated truth: ``load_policy_v2`` succeeds, the loaded PolicyEntry
    tuple authorizes the discovered mutation end to end, and the report
    carries NO DB_POLICY_SOURCE_EVIDENCE_INVALID (the pre-activation
    umbrella) -- clean, trusted, zero findings."""
    root = _typed_root(tmp_path)
    policy_path = tmp_path / "policy_v2.yaml"
    policy_path.write_text(_V2_CHARACTERIZATION_YAML, encoding="utf-8")

    entries, errors = load_policy_v2(str(policy_path))

    assert errors == []
    assert entries is not None and len(entries) == 1

    report = scan_db_access(
        root, list(entries), raw_query_policy=_EMPTY_RAW_QUERY_POLICY,
    )

    payload = report.to_dict()
    assert [item["code"] for item in payload["diagnostics"]] == []
    assert payload["findings"] == []
    assert payload["statistics"]["trusted"] is True


# ── GR-07 final close-out: the last five DB_CALL_TARGET_AMBIGUOUS paths ─────
#
# Two adjudicated artifacts, one per matching rule:
#
# (a) TYPING ARTIFACT (PlannedExpenseRepository.kt:94,
#     WarrantyTrackerRepository.kt:121/150, InvestmentTracker.kt:184,
#     TransactionLifecycleCoordinator.kt:1225): the project-wide n-arg
#     declared-return map was NAME-global, and ``TransactionContext.copy(
#     ...): TransactionContext`` -- the tree's only n-arg ``copy``
#     declaration -- typed EVERY ``x.copy(...)`` initializer.  A
#     ``val withTimestamps = expense.copy(...)`` local therefore carried
#     ``TransactionContext`` into a ``PlannedExpense`` parameter, matched
#     zero overloads, and the single-candidate MUTATOR set emitted the false
#     blocking diagnostic.  The map is now (DECLARING TYPE, name)-keyed: a
#     resolved receiver applies only its own type's entry.
# (b) DEFAULT-PARAMETER ARTIFACT (NotificationIntakeWorker.kt:411 over
#     NotificationIntakeDao.markPrivacyDeniedAndPurgeAllPayload): Kotlin
#     binds omitted defaults at compile time, so the worker's
#     ``id = intakeId, nowMs = now`` call matched zero FULL parameter tuples
#     yet resolved to exactly one real overload.  Acceptance is now
#     source-verified (names + types + declared defaults, exactly one
#     accepting candidate); genuinely multi-match calls stay ambiguous.


def test_member_copy_return_type_no_longer_poisons_other_receivers(tmp_path):
    """Adjudication (a), the PlannedExpenseRepository.kt:94 shape (same root
    cause as WarrantyTrackerRepository.kt:121/150, InvestmentTracker.kt:184,
    and TransactionLifecycleCoordinator.kt:1225): a ``TransactionContext``
    member declares ``copy(...): TransactionContext``, but the copied value
    flows from a ``PlannedExpense`` receiver.  The declaring-type gate
    refuses the cross-type entry, the local is honestly unresolved, and the
    single-candidate mutator proceeds to its REAL authorization decision
    (an unauthorized-mutation finding on a trusted report) instead of the
    false DB_CALL_TARGET_AMBIGUOUS."""
    root = _source_root(tmp_path)
    source = """package example

class TransactionContext(val correlationId: String) {
    fun copy(id: Long): TransactionContext = TransactionContext(correlationId)
}

class PlannedExpense(val id: Long)

@androidx.room.Dao
interface PlannedExpenseProbeDao {
    @androidx.room.Insert
    suspend fun insertPlannedExpense(expense: PlannedExpense): Long
}

class PlannedExpenseRepository(
    private val plannedExpenseDao: PlannedExpenseProbeDao
) {
    suspend fun addPlannedExpense(expense: PlannedExpense) {
        val withTimestamps = expense.copy(1L)
        plannedExpenseDao.insertPlannedExpense(withTimestamps)
    }
}
"""
    (root / "PlannedExpenseRepository.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert [finding.rule for finding in report.findings] == [
        "DB_UNAUTHORIZED_MUTATION",
    ]
    finding = report.findings[0]
    assert finding.identity["dao"] == "example.PlannedExpenseProbeDao"
    assert finding.identity["operation"] == "insertPlannedExpense"
    assert report.statistics["trusted"] is True


def test_member_copy_return_type_still_resolves_for_declaring_type(tmp_path):
    """Adjudication (a) negative control: the declaring-type gate must only
    refuse CROSS-type receivers.  ``context.copy(...)`` on a
    ``TransactionContext`` receiver keeps the declared
    ``TransactionContext`` binding, the argument matches its overload
    exactly, and the mutation still reaches the authorization decision."""
    root = _source_root(tmp_path)
    source = """package example

class TransactionContext(val correlationId: String) {
    fun copy(id: Long): TransactionContext = TransactionContext(correlationId)
}

@androidx.room.Dao
interface ContextProbeDao {
    @androidx.room.Insert
    suspend fun store(value: TransactionContext)
}

class ContextRepository(private val contextDao: ContextProbeDao) {
    suspend fun store(context: TransactionContext) {
        val copy = context.copy(1L)
        contextDao.store(copy)
    }
}
"""
    (root / "ContextRepository.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert [finding.rule for finding in report.findings] == [
        "DB_UNAUTHORIZED_MUTATION",
    ]
    assert report.findings[0].identity["operation"] == "store"
    assert report.statistics["trusted"] is True


def test_unresolved_receiver_keeps_name_keyed_declared_return(tmp_path):
    """Adjudication (a) regression pin: a receiver the scanner cannot
    resolve keeps the historical name-keyed lookup, so currently resolving
    sites cannot degrade into new DB_DAO_SCOPE_UNRESOLVED debt."""
    root = _source_root(tmp_path)
    source = """package example

@androidx.room.Dao
interface FallbackProbeDao {
    @androidx.room.Insert
    fun probe(value: Int)
}

class Helper {
    fun resolve(id: Long): FallbackProbeDao = FallbackProbeDaoImpl()
}

class FallbackCaller {
    fun run() {
        val helper = makeHelper(1)
        val dao = helper.resolve(7L)
        dao.probe(1)
    }
}
"""
    (root / "FallbackCaller.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert [finding.rule for finding in report.findings] == [
        "DB_UNAUTHORIZED_MUTATION",
    ]
    assert report.findings[0].identity["dao"] == "example.FallbackProbeDao"
    assert report.statistics["trusted"] is True


def test_default_parameter_call_resolves_to_its_unique_overload(tmp_path):
    """Adjudication (b), the NotificationIntakeWorker.kt:411 shape: the call
    binds only the non-defaulted subset (``id``/``nowMs``) of a
    three-parameter overload whose middle parameter carries a declared
    default.  Source-verified acceptance resolves EXACTLY ONE candidate and
    the mutation reaches its real authorization decision (an
    unauthorized-mutation finding on a trusted report) instead of the false
    DB_CALL_TARGET_AMBIGUOUS."""
    root = _source_root(tmp_path)
    source = """package example

@androidx.room.Dao
interface IntakeProbeDao {
    @androidx.room.Query("UPDATE intake SET status = :status WHERE id = :id")
    suspend fun markPrivacyDenied(
        id: Long,
        status: String = "PRIVACY_DENIED",
        nowMs: Long
    )
}

class IntakeWorker(private val intakeDao: IntakeProbeDao) {
    suspend fun runPrivacyCleanup(intakeId: Long, now: Long) {
        intakeDao.markPrivacyDenied(id = intakeId, nowMs = now)
    }
}
"""
    (root / "IntakeWorker.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert [finding.rule for finding in report.findings] == [
        "DB_UNAUTHORIZED_MUTATION",
    ]
    finding = report.findings[0]
    assert finding.identity["dao"] == "example.IntakeProbeDao"
    assert finding.identity["operation"] == "markPrivacyDenied"
    assert report.statistics["trusted"] is True


def test_default_parameter_multi_match_stays_ambiguous(tmp_path):
    """Fail-closed pin for adjudication (b): TWO overloads accepting the
    same defaulted call is a genuine multi-match -- the pinned
    DB_CALL_TARGET_AMBIGUOUS contract holds (untrusted report, no
    authorization decision)."""
    root = _source_root(tmp_path)
    source = """package example

@androidx.room.Dao
interface AmbiguousDefaultsDao {
    @androidx.room.Query("UPDATE alpha SET x = :x WHERE id = :id")
    suspend fun mark(id: Long, x: String = "a", nowMs: Long)

    @androidx.room.Query("UPDATE beta SET y = :y WHERE id = :id")
    suspend fun mark(id: Long, y: Int = 1, nowMs: Long)
}

class AmbiguousCaller(private val dao: AmbiguousDefaultsDao) {
    suspend fun run(rowId: Long, now: Long) {
        dao.mark(id = rowId, nowMs = now)
    }
}
"""
    (root / "AmbiguousCaller.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert [item.code for item in report.diagnostics] == [
        "DB_CALL_TARGET_AMBIGUOUS",
    ]
    assert report.findings == ()
    assert report.statistics["trusted"] is False


def test_positional_call_omitting_trailing_defaults_resolves(tmp_path):
    """Adjudication (b), positional form: Kotlin allows omitting only a
    TRAILING defaulted run positionally; a source-verified trailing default
    resolves the unique candidate exactly like the named form."""
    root = _source_root(tmp_path)
    source = """package example

@androidx.room.Dao
interface FlagProbeDao {
    @androidx.room.Insert
    suspend fun store(value: Int, flag: Boolean = true)
}

class FlagCaller(private val dao: FlagProbeDao) {
    suspend fun keep() {
        dao.store(1)
    }
}
"""
    (root / "FlagCaller.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert [finding.rule for finding in report.findings] == [
        "DB_UNAUTHORIZED_MUTATION",
    ]
    assert report.findings[0].identity["operation"] == "store"
    assert report.statistics["trusted"] is True


def test_unindexed_transaction_default_method_delete_is_not_ambiguity(tmp_path):
    """GR-14a contract update of the GR-07 final close-out adjudication
    (the CategoryRepository.kt:225 shape): ``categoryDao.delete(category)``
    names the ``@Transaction`` DEFAULT method ``delete``.  GR-07 left such
    methods unindexed, so the call ended silently — which is exactly the
    blind spot GR-12 later proved: the direct policy rows for
    ``BudgetRepository.addBudget``/``updateBudget`` and
    ``CategoryRepository.addCategory``/``deleteCategory`` could never gain a
    D4-resolved mutation observation, and the GR-12 dominance proof reported
    them UNSUPPORTED (DAO_DEFAULT_METHOD_NOT_INDEXED).

    GR-14a indexes a default ``@Transaction`` method whose body invokes an
    abstract mutator of the SAME DAO, so ``delete`` is now a real
    ``ROOM_TRANSACTION`` mutator.  The call resolves to exactly one
    candidate (never ambiguity — 2+ EQUAL matches is still the pinned
    honest contract), reaches an authorization decision, and with no
    ownership policy the unauthorized write is an ERROR finding on a
    trusted report.  The companion test
    ``test_transaction_default_method_authorized_by_exact_policy_row``
    pins the positive shape."""
    root = _source_root(tmp_path)
    source = """package example

class CategoryEntity(val id: Long, val name: String)

class OtherEntity(val id: Long)

@androidx.room.Dao
interface OtherProbeDao {
    @androidx.room.Delete
    suspend fun delete(item: OtherEntity)
}

@androidx.room.Dao
interface CategoryProbeDao {
    @androidx.room.Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @androidx.room.Delete
    suspend fun deleteInternal(category: CategoryEntity)

    @androidx.room.Transaction
    suspend fun delete(category: CategoryEntity) {
        deleteInternal(category)
    }
}

class CategoryRepository(private val categoryDao: CategoryProbeDao) {
    suspend fun deleteCategory(categoryId: Long) {
        val category = categoryDao.getById(categoryId)
            ?: return
        categoryDao.delete(category)
    }
}
"""
    (root / "CategoryRepository.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert len(report.findings) == 1
    assert report.findings[0].rule == "DB_UNAUTHORIZED_MUTATION"
    assert report.findings[0].identity["operation"] == "delete"
    assert report.findings[0].identity["mutation_kind"] == "ROOM_TRANSACTION"
    assert report.findings[0].identity["dao"] == "example.CategoryProbeDao"
    assert report.statistics["trusted"] is True


def test_transaction_default_method_authorized_by_exact_policy_row(tmp_path):
    """GR-14a positive contract: the same ``categoryDao.delete(category)``
    call is fully authorized when an exact v2 policy row names the
    (callable, default ``@Transaction`` method) mutation identity, and the
    canonical write barrier precedes it in the callable.  Zero findings,
    trusted report — the mutator participates in normal v2 authorization
    exactly like annotation-backed mutators."""
    root = _source_root(tmp_path)
    source = """package example

class CategoryEntity(val id: Long, val name: String)

@androidx.room.Dao
interface CategoryProbeDao {
    @androidx.room.Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @androidx.room.Delete
    suspend fun deleteInternal(category: CategoryEntity)

    @androidx.room.Transaction
    suspend fun delete(category: CategoryEntity) {
        deleteInternal(category)
    }
}

class WriteBarrier {
    fun checkWritesAllowed(operation: String) {}
}

class CategoryRepository(private val categoryDao: CategoryProbeDao, private val writeBarrier: WriteBarrier) {
    suspend fun deleteCategory(categoryId: Long) {
        val category = categoryDao.getById(categoryId)
            ?: return
        writeBarrier.checkWritesAllowed("CategoryRepository.deleteCategory")
        categoryDao.delete(category)
    }
}
"""
    (root / "CategoryRepository.kt").write_text(source, encoding="utf-8")

    from scripts.db_guard.policy_v2_loader import load_policy_v2

    policy_yaml = """\
schemaVersion: 2
entries:
- path: app/src/main/java/CategoryRepository.kt
  ownerFqcn: example.CategoryRepository
  kind: function
  method: deleteCategory
  receiver: null
  parameterTypes:
  - Long
  daoAccessor: categoryDao
  daoFqcn: example.CategoryProbeDao
  operation: delete
  barrierMode: direct
  reason: "GR-14a fixture: default @Transaction mutator authorized exactly"
  owner: '@test'
  linkedIssue: GR-14a
"""
    policy_path = root / "policy.yml"
    policy_path.write_text(policy_yaml, encoding="utf-8")
    entries, errors = load_policy_v2(str(policy_path))
    assert entries is not None, errors

    report = scan_db_access(root, entries, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert report.findings == ()
    assert report.statistics["trusted"] is True


def test_synthesized_copy_name_keyed_fallback_stays_unresolved(tmp_path):
    """GR-07 final close-out adjudication, the
    WarrantyTrackerRepository.kt:413 shape: ``existing`` (from an
    elvis-return initializer) stays unresolved, so ``existing.copy(...)``
    fell to the NAME-keyed declared-return twin -- where the project's only
    explicit ``copy`` declaration, ``TransactionContext.copy(...):
    TransactionContext``, typed the local as ``TransactionContext``.  The
    mismatched binding against the single ``updateReturnWindow(ReturnWindow)``
    mutator emitted a false DB_CALL_TARGET_AMBIGUOUS.  ``copy`` is
    synthesized on every data class with a receiver-specific return type,
    so a name-global ``copy`` entry is unsound by construction; the twin
    now excludes synthesized member names and the local stays honestly
    unresolved, letting the single-candidate mutator proceed to its REAL
    authorization decision (an unauthorized-mutation finding on a trusted
    report).  The (declaring type, name) map is untouched: a RESOLVED
    ``TransactionContext`` receiver keeps its declared binding (pinned by
    ``test_member_copy_return_type_still_resolves_for_declaring_type``),
    and non-synthesized name-keyed lookups are unchanged (pinned by
    ``test_unresolved_receiver_keeps_name_keyed_declared_return``)."""
    root = _source_root(tmp_path)
    source = """package example

class TransactionContext(val correlationId: String) {
    fun copy(correlationId: String = this.correlationId): TransactionContext =
        TransactionContext(correlationId)
}

class ReturnWindow(val id: Long, val updatedAt: Long)

@androidx.room.Dao
interface ReturnWindowProbeDao {
    @androidx.room.Query("SELECT * FROM windows WHERE id = :id")
    suspend fun getReturnWindowById(id: Long): ReturnWindow?

    @androidx.room.Update
    suspend fun updateReturnWindow(value: ReturnWindow)
}

class WarrantyRepository(private val returnWindowDao: ReturnWindowProbeDao) {
    suspend fun markAsReturned(returnWindowId: Long, now: Long) {
        val existing = returnWindowDao.getReturnWindowById(returnWindowId) ?: return
        val updated = existing.copy("ctx")
        returnWindowDao.updateReturnWindow(updated)
    }
}
"""
    (root / "WarrantyRepository.kt").write_text(source, encoding="utf-8")

    report = scan_db_access(root, raw_query_policy=_EMPTY_RAW_QUERY_POLICY)

    assert report.diagnostics == ()
    assert [finding.rule for finding in report.findings] == [
        "DB_UNAUTHORIZED_MUTATION",
    ]
    finding = report.findings[0]
    assert finding.identity["dao"] == "example.ReturnWindowProbeDao"
    assert finding.identity["operation"] == "updateReturnWindow"
    assert report.statistics["trusted"] is True
