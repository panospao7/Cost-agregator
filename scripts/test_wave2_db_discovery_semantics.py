"""Wave-2 discovery repairs: resolve real types without granting exceptions.

These fixtures exercise public parser/scanner paths. Positive discovery cases
still report unauthorized writes when no ownership entry exists. Negative
cases retain blocking diagnostics, withheld findings and source coordinates.
"""
from pathlib import Path

import pytest

from scripts import kotlin_callable_parser as parser
from scripts.db_guard import scanner as db_scanner
from scripts.db_guard.scanner import (
    _argument_bindings,
    _binding_type_matches,
    _file_constructor_let_type,
    _member_return_type,
    scan_db_access,
)


EMPTY_RAW_QUERY_POLICY = {"version": 1, "methods": []}
PATH = "app/src/main/java/example/DiscoveryFixture.kt"
DAO = """@androidx.room.Dao
interface ProbeDao {
    @androidx.room.Insert
    fun store(value: Int)
    @androidx.room.Query("SELECT EXISTS(SELECT 1 FROM item WHERE id = :id)")
    fun exists(id: Long): Boolean
}
"""


def _index(*qualified):
    by_name = {}
    for name in qualified:
        by_name.setdefault(name.rsplit(".", 1)[-1], []).append(name)
    return parser.ProjectTypeIndex(
        {name: tuple(sorted(values)) for name, values in by_name.items()},
        frozenset(qualified),
    )


def _parse_parameter(type_text, *, imports="", index=None, nested="", prefix=""):
    source = "package example\n" + imports + "\n" + prefix + """
class Repo {
    class Snapshot
    NESTED
    fun <T> accept(value: TYPE) {}
    fun sibling(id: Long) {}
}
""".replace("NESTED", nested).replace("TYPE", type_text)
    owner = next(item for item in parser.find_owner_declarations(source)
                 if item.owner == "example.Repo")
    declarations = parser.find_callable_declarations(source, owner, project_types=index)
    by_name = {item.signature.function_name: item for item in declarations}
    assert set(by_name) == {"accept", "sibling"}
    assert by_name["sibling"].signature.parameter_types == ("Long",)
    assert all(item.status == "RESOLVED_EXACTLY" for item in declarations)
    return by_name["accept"].signature.parameter_types


@pytest.mark.parametrize("type_text,expected", [
    ("Result<T>", "Result<T>"),
    ("() -> Result<T>", "() -> Result<T>"),
    ("(Snapshot) -> Result<T>", "(example.Repo.Snapshot) -> Result<T>"),
    ("suspend () -> Result<T>", "() -> Result<T>"),
])
def test_default_result_does_not_resolve_to_unimported_foreign_result(type_text, expected):
    index = _index("foreign.groups.Result", "foreign.model.Result")
    assert _parse_parameter(type_text, index=index) == (expected,)


@pytest.mark.parametrize("imports,qualified,expected", [
    ("import selected.Result", ("selected.Result", "other.Result"), "selected.Result<T>"),
    ("import selected.*", ("selected.Result", "other.Result"), "selected.Result<T>"),
    ("", ("example.Result", "other.Result"), "example.Result<T>"),
])
def test_in_scope_project_result_keeps_precedence(imports, qualified, expected):
    assert _parse_parameter("Result<T>", imports=imports, index=_index(*qualified)) == (expected,)


def test_same_file_result_keeps_precedence_over_default():
    assert _parse_parameter("Result<T>", prefix="class Result<X>\n") == ("example.Result<T>",)


def test_nested_result_keeps_precedence_over_default():
    assert _parse_parameter("Result<T>", nested="class Result<X>") == ("example.Repo.Result<T>",)


@pytest.mark.parametrize("imports", [
    "import first.Result\nimport second.Result",
    "import first.*\nimport second.*",
])
def test_ambiguous_in_scope_result_still_fails_closed(imports):
    with pytest.raises(parser.ParserError) as failure:
        _parse_parameter("Result<T>", imports=imports,
                         index=_index("first.Result", "second.Result"))
    assert failure.value.code == "TYPE_UNRESOLVED"


@pytest.mark.parametrize("type_text", ["Resul<T>", "Result<MissingType>", "Result<MissingType.Inner>"])
def test_result_repair_does_not_accept_unknown_names_or_arguments(type_text):
    with pytest.raises(parser.ParserError) as failure:
        _parse_parameter(type_text, index=_index("foreign.groups.Result", "foreign.model.Result"))
    assert failure.value.code == "TYPE_UNRESOLVED"


@pytest.mark.parametrize("argument,parameter,expected", [
    ("MutableList<Long>", "List<Long>", True),
    ("MutableList<String>", "List<String>", True),
    ("kotlin.collections.MutableList<Long>", "List<Long>", True),
    ("MutableList<List<Long>>", "List<List<Long>>", True),
    ("MutableList<Long>?", "List<Long>", True),
    ("List<Long>", "MutableList<Long>", False),
    ("MutableList<Long>", "List<String>", False),
    ("MutableList<Long>", "List<Any>", False),
    ("MutableList<Number>", "List<Long>", False),
    ("foreign.MutableList<Long>", "List<Long>", False),
    ("MutableList<Long>", "foreign.List<Long>", False),
    ("MutableSet<Long>", "List<Long>", False),
    ("Any", "List<Long>", False),
])
def test_collection_binding_is_directional_and_preserves_exact_elements(argument, parameter, expected):
    assert _binding_type_matches(argument, parameter) is expected


def _scan(tmp_path, body, *, dao=DAO, extra_sources=()):
    root = tmp_path / "app" / "src" / "main" / "java"
    path = root / "example" / "DiscoveryFixture.kt"
    path.parent.mkdir(parents=True)
    source = "package example\nimport java.io.File\n" + dao + "\n" + body
    path.write_text(source, encoding="utf-8")
    for relative, text in extra_sources:
        extra = root / relative
        extra.parent.mkdir(parents=True, exist_ok=True)
        extra.write_text(text, encoding="utf-8")
    report = scan_db_access(root, raw_query_policy=EMPTY_RAW_QUERY_POLICY)
    return report, source


def _assert_mutation(report, operation="store", accessor="dao"):
    assert report.statistics["trusted"] is True
    assert report.diagnostics == ()
    assert [item.rule for item in report.findings] == ["DB_UNAUTHORIZED_MUTATION"]
    finding = report.findings[0]
    assert finding.path == PATH
    assert finding.identity["dao"] == "example.ProbeDao"
    assert finding.identity["accessor"] == accessor
    assert finding.identity["operation"] == operation


def _assert_blocked(report, code):
    assert report.statistics["trusted"] is False
    assert report.findings == ()
    assert [item.code for item in report.diagnostics] == [code]
    diagnostic = report.diagnostics[0]
    assert diagnostic.path == PATH
    assert set(diagnostic.controlled_context) == {"line"}
    assert type(diagnostic.controlled_context["line"]) is int
    assert diagnostic.controlled_context["line"] > 0


@pytest.mark.parametrize("element", ["Long", "String"])
def test_mutable_list_real_scanner_resolves_but_does_not_authorize(tmp_path, element):
    dao = """@androidx.room.Dao
interface ProbeDao {
    @androidx.room.Query("DELETE FROM item WHERE id IN (:ids)")
    fun remove(ids: List<ELEMENT>): Int
}
""".replace("ELEMENT", element)
    body = """class Repo(private val dao: ProbeDao) {
    fun persist() {
        val values = mutableListOf<ELEMENT>()
        dao.remove(values)
    }
}
""".replace("ELEMENT", element)
    report, _ = _scan(tmp_path, body, dao=dao)
    _assert_mutation(report, "remove")


@pytest.mark.parametrize("argument,parameter", [
    ("MutableList<Long>", "List<String>"),
    ("List<Long>", "MutableList<Long>"),
    ("Any", "List<Long>"),
])
def test_mismatched_collection_call_stays_untrusted(tmp_path, argument, parameter):
    dao = """@androidx.room.Dao
interface ProbeDao {
    @androidx.room.Query("DELETE FROM item WHERE id IN (:ids)")
    fun remove(ids: PARAMETER): Int
}
""".replace("PARAMETER", parameter)
    body = """class Repo(private val dao: ProbeDao) {
    fun persist(values: ARGUMENT) {
        dao.remove(values)
    }
}
""".replace("ARGUMENT", argument)
    report, _ = _scan(tmp_path, body, dao=dao)
    _assert_blocked(report, "DB_CALL_TARGET_AMBIGUOUS")


def test_mutable_list_does_not_choose_between_two_accepting_overloads(tmp_path):
    dao = """@androidx.room.Dao
interface ProbeDao {
    @androidx.room.Query("DELETE FROM item WHERE id IN (:ids) AND flag = :flag")
    fun remove(ids: List<Long>, flag: Int = 0): Int
    @androidx.room.Query("DELETE FROM item WHERE id IN (:ids) AND label = :label")
    fun remove(ids: List<Long>, label: String = "x"): Int
}
"""
    body = """class Repo(private val dao: ProbeDao) {
    fun persist() {
        val values = mutableListOf<Long>()
        dao.remove(values)
    }
}
"""
    report, _ = _scan(tmp_path, body, dao=dao)
    _assert_blocked(report, "DB_CALL_TARGET_AMBIGUOUS")


@pytest.mark.parametrize("header,name", [("parent ->", "parent"), ("", "it")])
def test_parent_file_lambda_resolves_without_hiding_an_unrelated_write(tmp_path, header, name):
    body = """class Repo(private val dao: ProbeDao) {
    fun persist(file: File) {
        file.parentFile?.let { HEADER
            NAME.exists()
        }
        dao.store(1)
    }
}
""".replace("HEADER", header).replace("NAME", name)
    report, _ = _scan(tmp_path, body)
    _assert_mutation(report)


def test_safe_call_named_lambda_on_bare_nullable_file(tmp_path):
    body = """class Repo(private val dao: ProbeDao) {
    fun persist(file: File?) {
        file?.let { parent -> parent.exists() }
        dao.store(1)
    }
}
"""
    report, _ = _scan(tmp_path, body)
    _assert_mutation(report)


@pytest.mark.parametrize("body", [
    """fun persist(parent: File) {
        unknown.let { parent -> parent.exists() }
    }""",
    """fun persist(file: File) {
        file.let { parent -> it.exists() }
    }""",
    """fun persist(file: File) {
        file.let { parent -> parent.exists() }
        parent.exists()
    }""",
    """fun first(file: File) { file.let { parent -> parent.exists() } }
    fun persist() { parent.exists() }""",
    """fun persist(parent: File) {
        unknown.let { (parent, other) -> parent.exists() }
    }""",
])
def test_lambda_unknown_scope_and_shadowing_stay_fail_closed(tmp_path, body):
    report, _ = _scan(tmp_path, "class Repo(private val dao: ProbeDao) {\n" + body + "\n}")
    _assert_blocked(report, "DB_DAO_SCOPE_UNRESOLVED")


def test_lambda_receiver_resolves_before_body_local_shadow(tmp_path):
    body = """class Repo(private val dao: ProbeDao) {
    fun persist(file: File) {
        file.let { parent ->
            val file: ProbeDao = dao
            parent.exists()
            file.store(1)
        }
    }
}
"""
    report, _ = _scan(tmp_path, body)
    _assert_mutation(report, accessor="file")


def test_implicit_lambda_function_type_in_body_is_not_a_parameter_header(tmp_path):
    body = """class Repo(private val dao: ProbeDao) {
    fun persist(file: File) {
        file.let {
            val callback: (Int) -> String = { "value" }
            it.exists()
        }
        dao.store(1)
    }
}
"""
    report, _ = _scan(tmp_path, body)
    _assert_mutation(report)


def test_nested_named_lambda_dao_write_is_not_hidden(tmp_path):
    body = """class Repo(private val dao: ProbeDao) {
    fun persist() {
        dao.let { outer ->
            outer.let { inner -> inner.store(1) }
        }
    }
}
"""
    report, _ = _scan(tmp_path, body)
    _assert_mutation(report, accessor="inner")


def test_parent_file_fact_does_not_apply_to_foreign_same_named_type():
    assert _member_return_type("foreign.File", "parentFile") is None
    assert _member_return_type("File", "parentFile", {("File", "parentFile"): "ProbeDao"}) == "ProbeDao"


@pytest.mark.parametrize("expression,expected", [
    ('directory.let { File(it, "child") }', "File"),
    ('directory?.let { File(it, "child") }', "File?"),
    ('directory?.let { parent -> java.io.File(parent, "child") }', "File?"),
])
def test_file_let_inference_accepts_only_the_complete_constructor(expression, expected):
    assert _file_constructor_let_type(expression, {"directory": "File?"}.get) == expected


@pytest.mark.parametrize("expression", [
    'unknown?.let { File(it, "child") }',
    'context.directory?.let { File(it, "child") }',
    'directory?.let { return@let File(it, "child") }',
    'directory?.let { File(it, "child"); unknown }',
    'directory?.let { File(it, "child").unknown() }',
    'directory?.let { File(it, "child") } ?: unknown',
    'directory?.let { foreign.File(it, "child") }',
    'directory?.let { factory(it) }',
    'directory?.also { File(it, "child") }',
])
def test_file_let_inference_rejects_unknown_or_additional_result_paths(expression):
    assert _file_constructor_let_type(expression, {"directory": "File?"}.get) is None


@pytest.mark.parametrize("header,argument", [("", "it"), ("parent ->", "parent")])
def test_file_producing_let_real_scanner_keeps_core_mutation_visible(tmp_path, header, argument):
    body = """class Repo(private val dao: ProbeDao) {
    fun persist(directory: File?) {
        val sourceFile = directory?.let { HEADER File(ARGUMENT, "child") }
        if (sourceFile == null || !sourceFile.exists()) return
        dao.store(1)
    }
}
""".replace("HEADER", header).replace("ARGUMENT", argument)
    report, _ = _scan(tmp_path, body)
    _assert_mutation(report)


@pytest.mark.parametrize("receiver_type", ["KnownFactory", "ProbeDao", "foreign.File", "Any"])
def test_file_let_does_not_assume_arbitrary_receiver_dispatch_contract(receiver_type):
    expression = 'directory.let { File(it, "child") }'
    assert _file_constructor_let_type(expression, {"directory": receiver_type}.get) is None


def test_file_constructor_let_does_not_hide_mutation_inside_arguments(tmp_path):
    body = """class Repo(private val dao: ProbeDao) {
    fun persist(directory: File) {
        val sourceFile = directory.let {
            File(it,
                dao.store(1).toString()
            )
        }
        sourceFile.exists()
    }
}
"""
    report, _ = _scan(tmp_path, body)
    _assert_mutation(report)


def test_result_callback_no_longer_poisons_sibling_mutation_discovery(tmp_path):
    body = """class Repo(private val dao: ProbeDao) {
    class Snapshot
    private fun <T> export(write: (Snapshot) -> Result<T>) {}
    private fun <T> contain(block: () -> Result<T>) {}
    fun persist() { dao.store(1) }
}
"""
    extra = [
        ("foreign/groups/Result.kt", "package foreign.groups\nclass Result<T, E>\n"),
        ("foreign/model/Result.kt", "package foreign.model\nclass Result<T>\n"),
    ]
    report, _ = _scan(tmp_path, body, extra_sources=extra)
    _assert_mutation(report)


# Follow-up: production layouts and unknown transport must use the same
# contracts as the same-file fixtures above, without policy exceptions.
def _scan_split_dao_collection(tmp_path, body, parameter):
    dao_source = """package example
@androidx.room.Dao
interface ProbeDao {
    @androidx.room.Query("DELETE FROM item WHERE id IN (:ids)")
    fun remove(ids: PARAMETER): Int
}
""".replace("PARAMETER", parameter)
    return _scan(tmp_path, body, dao="", extra_sources=(
        ("example/ProbeDao.kt", dao_source),
    ))


@pytest.mark.parametrize("element", ["Long", "String"])
def test_split_file_dao_collection_binding_has_no_default_metadata_dependency(tmp_path, element):
    body = """class Repo(private val dao: ProbeDao) {
    fun persist() {
        val values = mutableListOf<ELEMENT>()
        dao.remove(values)
    }
}
""".replace("ELEMENT", element)
    report, _ = _scan_split_dao_collection(tmp_path, body, f"List<{element}>")
    _assert_mutation(report, "remove")


@pytest.mark.parametrize("argument,parameter", [
    ("MutableList<Long>", "List<String>"),
    ("List<Long>", "MutableList<Long>"),
    ("Any", "List<Long>"),
])
def test_split_file_dao_collection_mismatch_still_blocks(tmp_path, argument, parameter):
    body = """class Repo(private val dao: ProbeDao) {
    fun persist(values: ARGUMENT) { dao.remove(values) }
}
""".replace("ARGUMENT", argument)
    report, _ = _scan_split_dao_collection(tmp_path, body, parameter)
    _assert_blocked(report, "DB_CALL_TARGET_AMBIGUOUS")


@pytest.mark.parametrize("arguments", ["(value)", "(id = value)"])
@pytest.mark.parametrize("environment", [{}, {"value": None}])
def test_none_argument_marker_is_unresolved_not_a_type(arguments, environment):
    assert _argument_bindings(arguments, 0, environment) is None


_UNKNOWN_LAMBDA_MUTATIONS = (
    "unknown?.let { value -> dao.store(value) }",
    "unknown.let { dao.store(it) }",
    "unknown.forEach { value -> dao.store(value) }",
)


@pytest.mark.parametrize("expression", _UNKNOWN_LAMBDA_MUTATIONS)
def test_unresolved_lambda_argument_keeps_single_target_write_visible(tmp_path, expression):
    # Preserve the established unique-target rule, not a new authorization:
    # absent and explicit-unknown bindings must both expose the same write.
    body = "class Repo(private val dao: ProbeDao) {\n    fun persist() {\n        " + expression + "\n    }\n}\n"
    report, _ = _scan(tmp_path, body)
    _assert_mutation(report)


@pytest.mark.parametrize("expression", _UNKNOWN_LAMBDA_MUTATIONS)
def test_unresolved_lambda_argument_cannot_choose_an_overload(tmp_path, expression):
    dao = DAO.replace(
        "fun store(value: Int)",
        "fun store(value: Int)\n    @androidx.room.Insert\n    fun store(value: String)",
    )
    body = "class Repo(private val dao: ProbeDao) {\n    fun persist() {\n        " + expression + "\n    }\n}\n"
    report, _ = _scan(tmp_path, body, dao=dao)
    _assert_blocked(report, "DB_SIGNATURE_UNRESOLVED")


@pytest.mark.parametrize("overloaded", [False, True])
def test_known_argument_mismatch_is_not_treated_as_unknown(tmp_path, overloaded):
    dao = DAO
    if overloaded:
        dao = dao.replace(
            "fun store(value: Int)",
            "fun store(value: Int)\n    @androidx.room.Insert\n    fun store(value: String)",
        )
    body = """class Repo(private val dao: ProbeDao) {
    fun persist(value: Any) { dao.store(value) }
}
"""
    report, _ = _scan(tmp_path, body, dao=dao)
    _assert_blocked(report, "DB_CALL_TARGET_AMBIGUOUS")


@pytest.mark.parametrize("header,name", [("parent ->", "parent"), ("", "it")])
@pytest.mark.parametrize("dispatch", ["?.let", " ?. let"])
def test_nullable_bare_file_lambda_dispatch_preserves_identity(tmp_path, header, name, dispatch):
    body = """class Repo(private val dao: ProbeDao) {
    fun persist(file: File?) {
        fileDISPATCH { HEADER NAME.exists() }
        dao.store(1)
    }
}
""".replace("DISPATCH", dispatch).replace("HEADER", header).replace("NAME", name)
    report, _ = _scan(tmp_path, body)
    _assert_mutation(report)


@pytest.mark.parametrize("dispatch", ["unknown?.let", "holder(file)?.let"])
def test_unknown_or_wrapped_nullable_lambda_receiver_stays_blocked(tmp_path, dispatch):
    body = """class Repo(private val dao: ProbeDao) {
    fun persist(file: File) {
        DISPATCH { parent -> parent.exists() }
    }
}
""".replace("DISPATCH", dispatch)
    report, _ = _scan(tmp_path, body)
    _assert_blocked(report, "DB_DAO_SCOPE_UNRESOLVED")


@pytest.mark.parametrize("type_text,expected", [
    ("kotlinx.coroutines.CancellationException", "kotlinx.coroutines.CancellationException"),
    ("(kotlinx.coroutines.CancellationException) -> Unit", "(kotlinx.coroutines.CancellationException) -> Unit"),
])
def test_fully_qualified_coroutine_cancellation_parameter_keeps_exact_identity(type_text, expected):
    assert _parse_parameter(type_text) == (expected,)


@pytest.mark.parametrize("type_text", [
    "kotlinx.coroutines.MissingType",
    "kotlinx.coroutines.CancellationException.UnknownMember",
    "unrelated.CancellationException",
    "CancellationException",
])
def test_coroutine_type_extension_rejects_unevidenced_names(type_text):
    with pytest.raises(parser.ParserError) as failure:
        _parse_parameter(type_text)
    assert failure.value.code == "TYPE_UNRESOLVED"


def test_real_backup_owner_parse_exposes_containment_headers():
    # This is the actual production owner and manifest-root type index, not
    # another reduced fixture that omits a newly added helper parameter.
    repo = Path(__file__).resolve().parents[1]
    path = repo / "app/src/main/java/com/yourname/expensetracker/data/repository/DatabaseBackupRepositoryImpl.kt"
    source = path.read_text(encoding="utf-8")
    root_set, diagnostics = db_scanner.resolve_source_root_set(repo)
    assert root_set is not None
    assert not diagnostics
    pairs = db_scanner.declared_root_pairs(repo, root_set)
    assert pairs
    index = db_scanner.build_project_type_index(pairs)
    owner_name = "com.yourname.expensetracker.data.repository.DatabaseBackupRepositoryImpl"
    owners = [item for item in parser.find_owner_declarations(source) if item.owner == owner_name]
    assert len(owners) == 1
    try:
        declarations = parser.find_callable_declarations(source, owners[0], project_types=index)
    except parser.ParserError as error:
        pytest.fail(f"Backup callable discovery failed with controlled parser code {error.code}")
    by_name = {item.signature.function_name: item for item in declarations}
    assert {
        "runCostBackupExport", "containRestoreOperation", "finishCancelledRestore",
        "restoreCostBackup", "importDatabase", "resetDatabase", "checkpointWal",
    } <= set(by_name)
    assert all(item.status != "TYPE_UNRESOLVED" for item in declarations)
    assert by_name["finishCancelledRestore"].signature.parameter_types == (
        "kotlinx.coroutines.CancellationException", "() -> Unit",
    )
