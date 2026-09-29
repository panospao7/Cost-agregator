"""Executable entry-point evidence, not whole-file policy markers."""
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent))
import verify_worker_boundaries as guard
from kotlin_callable_parser import ParserError


CALL = "val result = executionGuard.runGuardedWithContext(request) { work() }"
BRIDGE = "return result.toWorkerResult()"


def source(body, helper="", qualified=False):
    base = "androidx.work.CoroutineWorker" if qualified else "CoroutineWorker"
    return f"""package example
import com.example.WorkerExecutionGuard
class ExampleWorker(private val executionGuard: WorkerExecutionGuard) : {base}(context, params) {{
    override suspend fun doWork(): Result {{
        {body}
    }}
    {helper}
}}
"""


def scan(tmp_path, text):
    path = tmp_path / "ExampleWorker.kt"
    path.write_text(text, encoding="utf-8")
    return guard.scan_file(str(path), "worker/ExampleWorker.kt")


@pytest.mark.parametrize("qualified", [False, True])
def test_direct_typed_guard_and_returned_bridge(tmp_path, qualified):
    assert not scan(tmp_path, source(CALL + "\n" + BRIDGE, qualified=qualified))


@pytest.mark.parametrize("body,helper", [
    ("// " + CALL + "\n return Result.success()", ""),
    ('val note = "' + CALL + '"\n return Result.success()', ""),
    ("return Result.success()", "fun unused() { " + CALL + "; " + BRIDGE + " }"),
    ("fun unused() { " + CALL + "; " + BRIDGE + " }\nreturn Result.success()", ""),
    ("val unused = { " + CALL + "; result.toWorkerResult() }\nreturn Result.success()", ""),
    (CALL + "\nreturn Result.success()", "fun unused() { " + BRIDGE + " }"),
    ("if (false) { " + CALL + "; " + BRIDGE + " }\nreturn Result.success()", ""),
    ("return Result.success()\n" + CALL + "\n" + BRIDGE, ""),
    ("if (ready) { return Result.success() }\n" + CALL + "\n" + BRIDGE, ""),
    ("if (ready) { return Result.retry() }\n" + CALL + "\n" + BRIDGE, ""),
    (CALL.replace("executionGuard", "unrelated") + "\n" + BRIDGE, ""),
])
def test_markers_or_unused_calls_cannot_authorize_do_work(tmp_path, body, helper):
    assert any(v.symbol == "ExampleWorker.noguard" for v in scan(tmp_path, source(body, helper, True)))


def test_unreadable_source_is_infrastructure_failure(tmp_path):
    with pytest.raises(guard.WorkerSourceError, match="WORKER_SOURCE_UNREADABLE"):
        guard.scan_file(str(tmp_path / "missing.kt"), "missing.kt")


def test_malformed_source_is_not_skipped(tmp_path):
    with pytest.raises(ParserError):
        scan(tmp_path, source('val text = "unterminated'))


def test_guarded_sibling_does_not_authorize_second_worker(tmp_path):
    text = source(CALL + "\n" + BRIDGE) + """
class SecondWorker : androidx.work.CoroutineWorker(context, params) {
    override suspend fun doWork(): Result { return Result.success() }
}
"""
    assert any(v.symbol == "SecondWorker.noguard" for v in scan(tmp_path, text))


@pytest.mark.parametrize("guarded_helper", [False, True])
def test_returned_cleanup_branch_requires_real_guarded_helper(tmp_path, guarded_helper):
    helper_body = ("return executionGuard.runGuardedWithContext(request) { cleanup() }"
                   if guarded_helper else "return fabricatedResult")
    helper = "private suspend fun cleanupResult(): WorkerGuardResult<Unit> { " + helper_body + " }"
    body = CALL + "\nreturn if (needsCleanup) { cleanupResult().toWorkerResult() } else { result.toWorkerResult() }"
    violations = scan(tmp_path, source(body, helper))
    assert any(v.symbol == "ExampleWorker.noguard" for v in violations) is not guarded_helper


def test_early_input_failure_remains_supported(tmp_path):
    body = "if (!ready) { return Result.failure() }\n" + CALL + "\n" + BRIDGE
    assert not scan(tmp_path, source(body))


def test_cleanup_helper_cannot_borrow_a_shadowed_guard_field(tmp_path):
    helper = "private suspend fun cleanupResult(executionGuard: FakeGuard): WorkerGuardResult<Unit> { return executionGuard.runGuardedWithContext(request) { cleanup() } }"
    body = CALL + "\nreturn if (needsCleanup) { cleanupResult(fake).toWorkerResult() } else { result.toWorkerResult() }"
    assert any(v.symbol == "ExampleWorker.noguard" for v in scan(tmp_path, source(body, helper)))


def test_dao_mutation_inside_executable_template_is_still_detected(tmp_path):
    template = "$" + "{expenseDao.insert(item)}"
    body = CALL + '\nval label = "' + template + '"\n' + BRIDGE
    assert any(v.symbol == "ExampleWorker.daoMutation" for v in scan(tmp_path, source(body)))
