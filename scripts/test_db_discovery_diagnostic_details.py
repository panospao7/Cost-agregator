"""Source coordinates improve diagnosis, never authorization or report trust."""
import pytest

from scripts.db_guard.scanner import scan_db_access


EMPTY_RAW_QUERY_POLICY = {"version": 1, "methods": []}
PATH = "app/src/main/java/example/DiagnosticFixture.kt"
DAO = """@androidx.room.Dao
interface ProbeDao {
    @androidx.room.Insert
    fun store(value: Int)
}
"""


def _scan(tmp_path, source):
    root = tmp_path / "app" / "src" / "main" / "java"
    package = root / "example"
    package.mkdir(parents=True)
    (package / "DiagnosticFixture.kt").write_text(source, encoding="utf-8")
    return scan_db_access(root, raw_query_policy=EMPTY_RAW_QUERY_POLICY)


def _line(source, text):
    matches = [i + 1 for i, line in enumerate(source.splitlines()) if text in line]
    assert len(matches) == 1
    return matches[0]


def _assert_blocked(report, code, lines):
    assert report.statistics["trusted"] is False
    assert report.findings == ()
    assert [item.code for item in report.diagnostics] == [code] * len(lines)
    assert [item.path for item in report.diagnostics] == [PATH] * len(lines)
    assert [dict(item.controlled_context) for item in report.diagnostics] == [
        {"line": line} for line in sorted(lines)
    ]


@pytest.mark.parametrize("call", [
    "unknown.store(1)", "unknown?.store(1)", "holder(dao).store(1)",
])
def test_unresolved_scope_reports_the_call_line_and_stays_blocking(tmp_path, call):
    source = "package example\n" + DAO + """
class Repo(private val dao: ProbeDao) {
    fun persist() {
        CALL
    }
}
""".replace("CALL", call)
    report = _scan(tmp_path, source)
    _assert_blocked(report, "DB_DAO_SCOPE_UNRESOLVED", [_line(source, call)])


def test_distinct_unresolved_calls_do_not_collapse_into_one_path_only_diagnostic(tmp_path):
    source = "package example\n" + DAO + """
class Repo {
    fun persist() {
        first.store(1)
        second.store(2)
    }
}
"""
    report = _scan(tmp_path, source)
    _assert_blocked(report, "DB_DAO_SCOPE_UNRESOLVED", [
        _line(source, "first.store"), _line(source, "second.store"),
    ])


def test_unresolved_enclosing_signature_reports_affected_declaration_not_a_guessed_finding(tmp_path):
    source = "package example\n" + DAO + """
class Repo(private val dao: ProbeDao) {
    fun persist(value: MissingType) {
        dao.store(1)
    }
}
"""
    report = _scan(tmp_path, source)
    _assert_blocked(report, "DB_SIGNATURE_UNRESOLVED", [_line(source, "fun persist")])


def test_unknown_overload_arguments_report_the_call_line_and_stay_blocking(tmp_path):
    source = """package example
class Repo(private val dao: ProbeDao) {
    suspend fun persist(id: String) {
        dao.store(dao.probe(id))
    }
}
@androidx.room.Dao
interface ProbeDao {
    @androidx.room.Query("SELECT 1")
    suspend fun probe(value: String): Int
    @androidx.room.Insert
    suspend fun store(value: Int)
    @androidx.room.Insert
    suspend fun store(values: List<Int>)
}
"""
    report = _scan(tmp_path, source)
    _assert_blocked(report, "DB_SIGNATURE_UNRESOLVED", [_line(source, "dao.store")])


def test_ambiguous_defaulted_overloads_report_the_call_line_and_stay_blocking(tmp_path):
    source = """package example
@androidx.room.Dao
interface ProbeDao {
    @androidx.room.Query("UPDATE alpha SET x = :x WHERE id = :id")
    suspend fun mark(id: Long, x: String = "a", nowMs: Long)
    @androidx.room.Query("UPDATE beta SET y = :y WHERE id = :id")
    suspend fun mark(id: Long, y: Int = 1, nowMs: Long)
}
class Repo(private val dao: ProbeDao) {
    suspend fun persist(rowId: Long, now: Long) {
        dao.mark(id = rowId, nowMs = now)
    }
}
"""
    report = _scan(tmp_path, source)
    _assert_blocked(report, "DB_CALL_TARGET_AMBIGUOUS", [_line(source, "dao.mark")])
