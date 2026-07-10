"""
test_verify_worker_boundaries.py
Acceptance tests for the static Worker Full Boundary Guard (G-WORKER-01).

3 test cases:
  1. Worker without WorkerExecutionGuard — FAILS
  2. Worker with WorkerExecutionGuard — PASSES
  3. Worker with direct DAO mutation — FAILS

Run with: python -m pytest scripts/test_verify_worker_boundaries.py -v
"""
import os
import sys
import tempfile

# Import the module under test directly
sys.path.insert(0, os.path.dirname(__file__))
import importlib.util

_spec = importlib.util.spec_from_file_location(
    "guard", os.path.join(os.path.dirname(__file__), "verify_worker_boundaries.py")
)
_mod = importlib.util.module_from_spec(_spec)
import unittest.mock as _mock
with _mock.patch("builtins.__import__", side_effect=__import__):
    _spec.loader.exec_module(_mod)

scan_file = _mod.scan_file
violation = _mod.violation
matches_allowlist = _mod.matches_allowlist
load_allowlist = _mod.load_allowlist
RULE_ID = _mod.RULE_ID


def _write_kt(path, filename, content):
    """Write a .kt file in the given directory."""
    filepath = os.path.join(str(path), filename)
    os.makedirs(os.path.dirname(filepath), exist_ok=True)
    with open(filepath, "w", encoding="utf-8") as f:
        f.write(content)
    return filepath


def _yaml(tmp_path, content):
    """Write a YAML allowlist file."""
    p = os.path.join(str(tmp_path), "allowlist.yml")
    with open(p, "w", encoding="utf-8") as f:
        f.write(content)
    return p


# ── Test 1: Worker without WorkerExecutionGuard FAILS ─────────────

def test_worker_without_guard_fails(tmp_path):
    """A CoroutineWorker not using WorkerExecutionGuard must be flagged."""
    src = os.path.join(str(tmp_path), "worker")
    _write_kt(src, "UnguardedWorker.kt", """\
package com.example.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class UnguardedWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return Result.success()
    }
}
""".strip())

    allowlist_path = _yaml(tmp_path, "# empty allowlist\n")
    allowlist = load_allowlist(allowlist_path)

    rel_path = "worker/UnguardedWorker.kt"
    violations = scan_file(
        os.path.join(str(tmp_path), "worker", "UnguardedWorker.kt"),
        rel_path,
    )
    filtered = [v for v in violations if not matches_allowlist(v, allowlist)]

    assert len(filtered) > 0, (
        f"Expected violations for unguarded worker, got {len(filtered)}"
    )
    has_guard_violation = any("WorkerExecutionGuard" in v or "runGuarded" in v
                               for v in filtered)
    assert has_guard_violation, (
        f"Expected violation about missing WorkerExecutionGuard, got: {filtered}"
    )


# ── Test 2: Worker with WorkerExecutionGuard PASSES ───────────────

def test_worker_with_guard_passes(tmp_path):
    """A CoroutineWorker using runGuardedWithContext must pass."""
    src = os.path.join(str(tmp_path), "worker")
    _write_kt(src, "GuardedWorker.kt", """\
package com.example.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.domain.workers.WorkerExecutionGuard
import com.example.domain.workers.WorkerGuardRequest
import com.example.domain.workers.BlockedPolicy
import com.example.domain.workers.toWorkerResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class GuardedWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val executionGuard: WorkerExecutionGuard
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val guardResult = executionGuard.runGuardedWithContext(
            WorkerGuardRequest(
                workerName = "guarded_worker",
                blockedPolicy = BlockedPolicy.RETRY,
                workId = id.toString(),
                runAttemptCount = runAttemptCount
            )
        ) { ctx ->
            ctx.checkpoint("work")
        }
        return guardResult.toWorkerResult()
    }
}
""".strip())

    allowlist_path = _yaml(tmp_path, "# empty allowlist\n")
    allowlist = load_allowlist(allowlist_path)

    rel_path = "worker/GuardedWorker.kt"
    violations = scan_file(
        os.path.join(str(tmp_path), "worker", "GuardedWorker.kt"),
        rel_path,
    )
    filtered = [v for v in violations if not matches_allowlist(v, allowlist)]

    assert len(filtered) == 0, (
        f"Expected no violations for guarded worker, got: {filtered}"
    )


# ── Test 3: Worker with direct DAO mutation FAILS ─────────────────

def test_worker_with_direct_dao_mutation_fails(tmp_path):
    """A worker directly calling DAO mutators must be flagged."""
    src = os.path.join(str(tmp_path), "worker")
    _write_kt(src, "DaoMutatingWorker.kt", """\
package com.example.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.data.database.dao.ExpenseDao
import com.example.domain.workers.WorkerExecutionGuard
import com.example.domain.workers.WorkerGuardRequest
import com.example.domain.workers.BlockedPolicy
import com.example.domain.workers.toWorkerResult
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class DaoMutatingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val expenseDao: ExpenseDao,
    private val executionGuard: WorkerExecutionGuard
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val guardResult = executionGuard.runGuardedWithContext(
            WorkerGuardRequest(
                workerName = "dao_mutating",
                blockedPolicy = BlockedPolicy.RETRY,
                workId = id.toString(),
                runAttemptCount = runAttemptCount
            )
        ) { ctx ->
            expenseDao.insert(expense)
        }
        return guardResult.toWorkerResult()
    }
}
""".strip())

    allowlist_path = _yaml(tmp_path, "# empty allowlist\n")
    allowlist = load_allowlist(allowlist_path)

    rel_path = "worker/DaoMutatingWorker.kt"
    violations = scan_file(
        os.path.join(str(tmp_path), "worker", "DaoMutatingWorker.kt"),
        rel_path,
    )
    filtered = [v for v in violations if not matches_allowlist(v, allowlist)]

    # The guard itself is used, but Daos are mutated directly
    assert len(filtered) > 0, (
        f"Expected violations for worker with direct DAO mutation, got {len(filtered)}"
    )
    has_dao_violation = any("DAO mutator" in v or "Dao" in v for v in filtered)
    assert has_dao_violation, (
        f"Expected violation about direct DAO mutation, got: {filtered}"
    )


# ── Allowlist support ─────────────────────────────────────────────

def test_allowlisted_worker_passes(tmp_path):
    """An allowlisted worker without the guard should pass."""
    src = os.path.join(str(tmp_path), "worker")
    _write_kt(src, "ExemptWorker.kt", """\
package com.example.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class ExemptWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        return Result.success()
    }
}
""".strip())

    rel_path = "worker/ExemptWorker.kt"
    allowlist_content = f"""\
- rule: {RULE_ID}
  path: {rel_path}
  symbol: ExemptWorker.noguard
  reason: "Test allowlisted non-DB worker"
  owner: "@tester"
  expires: "permanent"
"""
    allowlist_path = _yaml(tmp_path, allowlist_content)
    allowlist = load_allowlist(allowlist_path)

    violations = scan_file(
        os.path.join(str(tmp_path), "worker", "ExemptWorker.kt"),
        rel_path,
    )
    filtered = [v for v in violations if not matches_allowlist(v, allowlist)]

    assert len(filtered) == 0, (
        f"Expected no violations for allowlisted worker, got: {filtered}"
    )


# ── Per-symbol allowlist vs whole-file skip ────────────────────────

def test_allowlisted_file_still_checks_other_rules(tmp_path):
    """An allowlisted file should still be scanned — only matching violations suppressed.

    Creates a worker that triggers TWO different violation categories:
    1. Missing WorkerExecutionGuard (noguard)
    2. Broad catch without diagnostics (broadCatch)

    When only the noguard symbol is allowlisted, the broadCatch violation
    must still be reported — proving we no longer skip the entire file.
    """
    src = os.path.join(str(tmp_path), "worker")
    _write_kt(src, "PartialExemptWorker.kt", """\
package com.example.worker

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class PartialExemptWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        try {
            return Result.success()
        } catch (e: Exception) {
            // no cancellation check, no diagnostic logging
            throw e
        }
    }
}
""".strip())

    rel_path = "worker/PartialExemptWorker.kt"

    # Allowlist only the missing-guard violation — NOT the broad-catch one.
    allowlist_content = f"""\
- rule: {RULE_ID}
  path: {rel_path}
  symbol: PartialExemptWorker.noguard
  reason: "Test partial exemption — only the guard bypass is reviewed"
  owner: "@tester"
  expires: "permanent"
"""
    allowlist_path = _yaml(tmp_path, allowlist_content)
    allowlist = load_allowlist(allowlist_path)

    violations = scan_file(
        os.path.join(str(tmp_path), "worker", "PartialExemptWorker.kt"),
        rel_path,
    )
    filtered = [v for v in violations if not matches_allowlist(v, allowlist)]

    # We still expect ONE violation: the broad catch (broadCatch) that was NOT allowlisted.
    assert len(filtered) >= 1, (
        f"Expected at least one un-suppressed violation for partial-exempt worker, "
        f"got {len(filtered)}: {filtered}"
    )
    has_broad_catch_violation = any(
        ("broadCatch" in str(v)) or ("broad" in v.reason.lower())
        for v in filtered
    )
    assert has_broad_catch_violation, (
        f"Expected a broad-catch violation to still fire after allowlisting only noguard, "
        f"got: {filtered}"
    )

    # And NO noguard violation should survive
    has_noguard_violation = any(
        "noguard" in str(v) for v in filtered
    )
    assert not has_noguard_violation, (
        f"Expected noguard violation to be suppressed by allowlist, "
        f"got: {filtered}"
    )


# ── Test: missing allowlist is fatal (exit code 2) ─────────────────────────

def test_missing_allowlist_is_fatal():
    """Missing configured allowlist yields exit code 2."""
    import subprocess
    result = subprocess.run(
        [sys.executable, "scripts/verify_worker_boundaries.py", "--allowlist", "nonexistent_file.yml"],
        capture_output=True, text=True, timeout=10
    )
    assert result.returncode == 2, f"Expected exit 2, got {result.returncode}"
