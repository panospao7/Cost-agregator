"""Backup File discovery remains separate from permission to mutate/delete.

Synthetic roots alone use an explicit empty RawQuery policy. No production
policy, expected result, baseline or source tree is replaced by these fixtures.
"""
import pytest

from scripts.db_guard.scanner import scan_db_access


PATH = "app/src/main/java/example/FileDiscoveryFixture.kt"
EMPTY_RAW_QUERY_POLICY = {"version": 1, "methods": []}
DAO = """@androidx.room.Dao
interface ProbeDao {
    @androidx.room.Insert
    fun store(value: Int)
    @androidx.room.Query("SELECT EXISTS(SELECT 1 FROM item)")
    fun exists(): Boolean
    @androidx.room.Query("DELETE FROM item")
    fun delete(): Int
}
"""


def _scan(tmp_path, body, *, imports="", structural=None, extra_sources=()):
    root = tmp_path / "app" / "src" / "main" / "java"
    path = root / "example" / "FileDiscoveryFixture.kt"
    path.parent.mkdir(parents=True)
    source = "package example\nimport java.io.File\n" + imports + "\n" + DAO + body
    path.write_text(source, encoding="utf-8")
    for relative, text in extra_sources:
        extra = root / relative
        extra.parent.mkdir(parents=True, exist_ok=True)
        extra.write_text(text, encoding="utf-8")
    return scan_db_access(root, raw_query_policy=EMPTY_RAW_QUERY_POLICY,
                          structural_policy=structural), source


def _structural(method, operation="deleteRecursively"):
    return {"path": PATH, "class": "Repo", "method_pattern": method,
            "operation": operation}


def _mutation(report, *, accessor="dao", operation="store"):
    assert report.statistics["trusted"] is True
    assert report.diagnostics == ()
    assert [item.rule for item in report.findings] == ["DB_UNAUTHORIZED_MUTATION"]
    finding = report.findings[0]
    assert finding.path == PATH
    assert finding.identity["dao"] == "example.ProbeDao"
    assert finding.identity["accessor"] == accessor
    assert finding.identity["operation"] == operation


def _blocked(report, source, call, code="DB_DAO_SCOPE_UNRESOLVED"):
    assert report.statistics["trusted"] is False
    assert report.findings == ()
    assert [item.code for item in report.diagnostics] == [code]
    diagnostic = report.diagnostics[0]
    assert diagnostic.path == PATH
    assert dict(diagnostic.controlled_context) == {
        "line": source[:source.index(call)].count("\n") + 1,
    }


@pytest.mark.parametrize("type_text", ["File?", "java.io.File?"])
@pytest.mark.parametrize("dispatch", ["?.", " ?. "])
def test_nullable_file_cleanup_is_discovered_but_not_authorized(tmp_path, type_text, dispatch):
    body = """class Repo {
    fun cleanup(tempDir: TYPE) {
        runCatching { tempDirDISPATCHdeleteRecursively() }
    }
}
""".replace("TYPE", type_text).replace("DISPATCH", dispatch)
    report, source = _scan(tmp_path, body)
    assert report.statistics["trusted"] is True
    assert report.diagnostics == ()
    assert [item.rule for item in report.findings] == ["DB_FORBIDDEN_STRUCTURAL_OPERATION"]
    finding = report.findings[0]
    assert finding.path == PATH
    assert finding.identity["operation"] == "deleteRecursively"
    assert finding.symbol.name == "cleanup"
    assert finding.location.line == source[:source.index("deleteRecursively()")].count("\n") + 1


@pytest.mark.parametrize("permission,expected_count", [(None, 1), ("wrapper", 1), ("cleanup", 0)])
def test_nullable_cleanup_requires_its_own_exact_structural_tuple(tmp_path, permission, expected_count):
    body = """class Repo {
    fun wrapper(tempDir: File?) { cleanup(tempDir) }
    fun cleanup(tempDir: File?) { runCatching { tempDir?.deleteRecursively() } }
}
"""
    entries = [] if permission is None else [_structural(permission)]
    report, _ = _scan(tmp_path, body, structural=entries)
    assert report.statistics["trusted"] is True
    assert report.diagnostics == ()
    assert len(report.findings) == expected_count
    assert all(item.rule == "DB_FORBIDDEN_STRUCTURAL_OPERATION" for item in report.findings)
    assert all(item.symbol.name == "cleanup" for item in report.findings)


@pytest.mark.parametrize("parameters,expression", [
    ("tempDir: Any?", "tempDir?.deleteRecursively()"),
    ("tempDir: File?", "tempDir.deleteRecursively()"),
    ("tempDir: File?", "holder(tempDir)?.deleteRecursively()"),
    ("tempDir: File?", "missing?.deleteRecursively()"),
])
def test_nullable_structural_support_does_not_guess_unknown_receivers(tmp_path, parameters, expression):
    body = "class Repo {\n    fun cleanup(PARAMETERS) {\n        EXPRESSION\n    }\n}\n"
    body = body.replace("PARAMETERS", parameters).replace("EXPRESSION", expression)
    report, source = _scan(tmp_path, body)
    _blocked(report, source, expression, "DB_STRUCTURAL_SCOPE_UNSUPPORTED")


def test_nullable_foreign_file_name_is_not_the_platform_type(tmp_path):
    body = """class Repo {
    fun cleanup(tempDir: foreign.File?) { tempDir?.deleteRecursively() }
}
"""
    report, source = _scan(tmp_path, body, extra_sources=[
        ("foreign/File.kt", "package foreign\nclass File\n"),
    ])
    _blocked(report, source, "tempDir?.deleteRecursively()", "DB_STRUCTURAL_SCOPE_UNSUPPORTED")


@pytest.mark.parametrize("brace", [" {", "{", "\n        {"])
@pytest.mark.parametrize("imports", ["", "import kotlin.collections.listOf", "import java.util.*"])
def test_file_list_collision_does_not_hide_an_unrelated_dao_write(tmp_path, brace, imports):
    body = """class Repo(private val dao: ProbeDao) {
    fun persist(first: File, second: java.io.File) {
        for (file in listOf(first, second))BRACE
            if (file.exists() && !file.delete()) throw IllegalStateException("DELETE_FAILED")
        }
        dao.store(1)
    }
}
""".replace("BRACE", brace)
    report, _ = _scan(tmp_path, body, imports=imports)
    _mutation(report)


def test_real_reset_shape_binds_platform_path_and_file_constructor_locals(tmp_path):
    body = """class Context
class Repo(private val dao: ProbeDao) {
    fun persist(context: Context) {
        val liveDbFile = context.getDatabasePath("app.db")
        try {
            val dbWalFile = File(liveDbFile.parent, "app.db-wal")
            val dbShmFile = File(liveDbFile.parent, "app.db-shm")
            for (file in listOf(liveDbFile, dbWalFile, dbShmFile)) {
                if (file.exists() && !file.delete()) throw IllegalStateException("RESET_FILE_DELETE_FAILED")
            }
        } finally {}
        dao.store(1)
    }
}
"""
    report, _ = _scan(tmp_path, body, structural=[_structural("persist", "getDatabasePath")])
    _mutation(report)


@pytest.mark.parametrize("arguments", [
    "", "first, missing", "first, other", 'first, "literal"',
    "first, 1", "first, null", "first, *files", 'first, File("x")',
    "first, holder(second)", "first, second.parentFile", "it", "first,",
])
def test_file_list_rejects_unevidenced_elements_and_masked_literals(tmp_path, arguments):
    body = """class Repo(private val dao: ProbeDao) {
    fun persist(first: File, second: File, other: ProbeDao, files: Array<File>, it: File) {
        for (file in listOf(ARGUMENTS)) { file.delete() }
    }
}
""".replace("ARGUMENTS", arguments)
    report, source = _scan(tmp_path, body)
    _blocked(report, source, "file.delete()")


def test_list_of_daos_is_not_misclassified_as_files(tmp_path):
    body = """class Repo {
    fun persist(first: ProbeDao, second: ProbeDao) {
        for (file in listOf(first, second)) { file.delete() }
    }
}
"""
    report, source = _scan(tmp_path, body)
    _blocked(report, source, "file.delete()")


@pytest.mark.parametrize("statements", [
    'run { val first = File("x") }\n        for (file in listOf(first)) { file.delete() }',
    'for (file in listOf(first)) { file.delete() }\n        val first = File("x")',
])
def test_file_list_arguments_do_not_borrow_sibling_or_later_locals(tmp_path, statements):
    body = "class Repo {\n    fun persist() {\n        STATEMENTS\n    }\n}\n"
    report, source = _scan(tmp_path, body.replace("STATEMENTS", statements))
    _blocked(report, source, "file.delete()")


@pytest.mark.parametrize("statements", [
    "run {\n            val first = unknown()\n            for (file in listOf(first)) { file.delete() }\n        }",
    "unknown().let { first ->\n            for (file in listOf(first)) { file.delete() }\n        }",
    "unknown().let {\n            for (file in listOf(it)) { file.delete() }\n        }",
])
def test_unknown_local_and_lambda_shadows_cannot_borrow_outer_files(tmp_path, statements):
    body = "class Repo {\n    fun persist(first: File, it: File) {\n        STATEMENTS\n    }\n}\n"
    report, source = _scan(tmp_path, body.replace("STATEMENTS", statements))
    _blocked(report, source, "file.delete()")


def test_unknown_inner_list_iteration_shadows_outer_file_variable(tmp_path):
    body = """class Repo {
    fun persist(first: File) {
        for (file in listOf(first)) {
            for (file in listOf(missing)) { file.delete() }
        }
    }
}
"""
    report, source = _scan(tmp_path, body)
    _blocked(report, source, "file.delete()")


def test_loop_binding_ends_before_the_outer_dao_mutation(tmp_path):
    body = """class Repo {
    fun persist(first: File, file: ProbeDao) {
        for (file in listOf(first)){
            file.delete()
        }
        file.delete()
    }
}
"""
    report, _ = _scan(tmp_path, body)
    _mutation(report, accessor="file", operation="delete")


def test_list_arguments_resolve_before_a_later_loop_body_shadow(tmp_path):
    body = """class Repo(private val dao: ProbeDao) {
    fun persist(first: File) {
        for (file in listOf(first)) {
            val first: ProbeDao = dao
            file.delete()
        }
        dao.store(1)
    }
}
"""
    report, _ = _scan(tmp_path, body)
    _mutation(report)


@pytest.mark.parametrize("imports", [
    "import foreign.listOf", "import foreign.make as listOf", "import foreign.*",
])
def test_competing_imports_do_not_get_stdlib_factory_semantics(tmp_path, imports):
    body = """class Repo {
    fun persist(first: File) { for (file in listOf(first)) { file.delete() } }
}
"""
    report, source = _scan(tmp_path, body, imports=imports)
    _blocked(report, source, "file.delete()")


@pytest.mark.parametrize("location", ["member", "other_file", "local_value"])
def test_project_and_local_factory_shadows_remain_unresolved(tmp_path, location):
    declaration = "fun listOf(value: File): List<File> { return emptyList() }"
    member = declaration if location == "member" else ""
    local = "val listOf = unknownFactory()" if location == "local_value" else ""
    extra = [] if location != "other_file" else [
        ("example/Factories.kt", "package example\nimport java.io.File\n" + declaration),
    ]
    body = """class Repo {
    MEMBER
    fun persist(first: File) {
        LOCAL
        for (file in listOf(first)) { file.delete() }
    }
}
""".replace("MEMBER", member).replace("LOCAL", local)
    report, source = _scan(tmp_path, body, extra_sources=extra)
    _blocked(report, source, "file.delete()")


@pytest.mark.parametrize("gap", [" ", "\n            "])
def test_braceless_file_list_body_is_explicitly_unsupported(tmp_path, gap):
    body = """class Repo {
    fun persist(first: File) {
        for (file in listOf(first))GAPfile.delete()
    }
}
""".replace("GAP", gap)
    report, source = _scan(tmp_path, body)
    _blocked(report, source, "file.delete()")


@pytest.mark.parametrize("statement", [
    "val file = unknown()\n            file.delete()",
    "unknown().map { file -> file.delete() }",
])
def test_loop_body_unknown_shadow_cannot_inherit_the_file_binding(tmp_path, statement):
    body = """class Repo {
    fun persist(first: File) {
        for (file in listOf(first)) {
            STATEMENT
        }
    }
}
""".replace("STATEMENT", statement)
    report, source = _scan(tmp_path, body)
    _blocked(report, source, "file.delete()")


def test_file_list_destructuring_does_not_borrow_outer_values(tmp_path):
    body = """class Repo {
    fun persist(first: File, file: File) {
        for ((file, ignored) in listOf(first)) { file.delete() }
    }
}
"""
    report, source = _scan(tmp_path, body)
    _blocked(report, source, "file.delete()")


def test_file_list_implicit_parameter_name_remains_unsupported(tmp_path):
    body = """class Repo {
    fun persist(first: File) {
        for (it in listOf(first)) { it.delete() }
    }
}
"""
    report, source = _scan(tmp_path, body)
    _blocked(report, source, "it.delete()")


@pytest.mark.parametrize("structural", [None, [], {"entries": []}],
                         ids=["none", "list", "document"])
def test_file_fixture_supported_empty_policy_inputs_preserve_detection(tmp_path, structural):
    body = """class Repo(private val dao: ProbeDao) {
    fun persist() { dao.store(1) }
}
"""
    report, _ = _scan(tmp_path, body, structural=structural)
    # A valid empty policy grants nothing: the real mutation stays visible.
    _mutation(report)


@pytest.mark.parametrize("structural", [(), {"entries": ()}, False, [()]],
                         ids=["tuple", "tuple-document", "boolean", "tuple-entry"])
def test_file_fixture_malformed_structural_inputs_remain_fail_closed(tmp_path, structural):
    body = """class Repo(private val dao: ProbeDao) {
    fun persist() { dao.store(1) }
}
"""
    report, _ = _scan(tmp_path, body, structural=structural)
    assert report.statistics["trusted"] is False
    assert report.findings == ()
    assert [item.code for item in report.diagnostics] == [
        "DB_POLICY_SOURCE_EVIDENCE_INVALID",
    ]
