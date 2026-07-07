"""
test_verify_cancellation_boundaries.py
MIT-035 acceptance tests for the Cancellation Boundary Guard.

Run with: python -m pytest scripts/test_verify_cancellation_boundaries.py -v
"""
import os
import sys
import tempfile
from pathlib import Path

# Import the module under test directly
sys.path.insert(0, os.path.dirname(__file__))

import importlib.util

_spec = importlib.util.spec_from_file_location(
    "guard",
    os.path.join(os.path.dirname(__file__), "verify_cancellation_boundaries.py"),
)
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)

scan_file = _mod.scan_file
load_allowlist = _mod.load_allowlist
is_allowlisted = _mod.is_allowlisted


# ── Helpers ─────────────────────────────────────────────────────────────────

def _write_kt(tmp_path, filename: str, content: str) -> Path:
    """Write a Kotlin file and return its path."""
    f = tmp_path / filename
    f.write_text(content, encoding="utf-8")
    return f


def _make_allowlist(tmp_path, yaml_content: str) -> Path:
    """Write a YAML allowlist and return its path."""
    p = tmp_path / "cancellation_allowlist.yml"
    p.write_text(yaml_content, encoding="utf-8")
    return p


# ── Test: unsafe broad catch is detected ─────────────────────────────────────

def test_unsafe_catch_detected(tmp_path):
    """catch(Exception) in suspend function without CE rethrow → violation."""
    kt_file = _write_kt(
        tmp_path,
        "BadService.kt",
        """package com.example

class BadService {
    suspend fun fetchData(): String {
        try {
            return loadFromNetwork()
        } catch (e: Exception) {
            log("failed")
            return ""
        }
    }
}
""",
    )

    allowlist = load_allowlist(
        _make_allowlist(tmp_path, "# empty allowlist\n")
    )
    violations, fatal = scan_file(kt_file, allowlist)

    assert not fatal
    assert len(violations) >= 1, (
        f"Expected violation for broad catch without CE rethrow, got: {violations}"
    )
    assert any("Broad catch" in v and "Exception" in v for v in violations), (
        f"Violation message should mention broad catch, got: {violations}"
    )


# ── Test: broad catch with inline CE rethrow passes ──────────────────────────

def test_safe_catch_passes(tmp_path):
    """catch(Exception) with CancellationException rethrow → no violation."""
    kt_file = _write_kt(
        tmp_path,
        "SafeService.kt",
        """package com.example

class SafeService {
    suspend fun fetchData(): String {
        try {
            return loadFromNetwork()
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            log("failed")
            return ""
        }
    }
}
""",
    )

    allowlist = load_allowlist(
        _make_allowlist(tmp_path, "# empty allowlist\n")
    )
    violations, fatal = scan_file(kt_file, allowlist)

    assert not fatal
    assert violations == [], (
        f"Expected NO violations for catch with CE rethrow, got: {violations}"
    )


# ── Test: runCatching in suspend function is detected ────────────────────────

def test_runCatching_detected(tmp_path):
    """runCatching in suspend function without allowlist → violation."""
    kt_file = _write_kt(
        tmp_path,
        "BadRunner.kt",
        """package com.example

class BadRunner {
    suspend fun process(): Int {
        val result = runCatching { fetchData() }
        return result.getOrDefault(0)
    }
}
""",
    )

    allowlist = load_allowlist(
        _make_allowlist(tmp_path, "# empty allowlist\n")
    )
    violations, fatal = scan_file(kt_file, allowlist)

    assert not fatal
    assert len(violations) >= 1, (
        f"Expected violation for runCatching in suspend function, got: {violations}"
    )
    assert any("runCatching" in v for v in violations), (
        f"Violation message should mention runCatching, got: {violations}"
    )


# ── Test: runCatchingCancellable is safe ─────────────────────────────────────

def test_runCatchingCancellable_passes(tmp_path):
    """CancellationSafe.runCatchingCancellable in suspend function → no violation."""
    kt_file = _write_kt(
        tmp_path,
        "SafeRunner.kt",
        """package com.example

class SafeRunner {
    suspend fun process(): Int {
        val result = CancellationSafe.runCatchingCancellable { fetchData() }
        return result.getOrDefault(0)
    }
}
""",
    )

    allowlist = load_allowlist(
        _make_allowlist(tmp_path, "# empty allowlist\n")
    )
    violations, fatal = scan_file(kt_file, allowlist)

    assert not fatal
    assert violations == [], (
        f"Expected NO violations for runCatchingCancellable, got: {violations}"
    )


# ── Test: CancellationSafe.rethrowIfCancellation is safe ─────────────────────

def test_rethrowIfCancellation_passes(tmp_path):
    """catch(Exception) with CancellationSafe.rethrowIfCancellation(e) → no violation."""
    kt_file = _write_kt(
        tmp_path,
        "RethrowSafe.kt",
        """package com.example
import com.yourname.expensetracker.domain.util.CancellationSafe

class RethrowSafe {
    suspend fun fetchData(): String {
        try {
            return loadFromNetwork()
        } catch (e: Exception) {
            CancellationSafe.rethrowIfCancellation(e)
            log("failed")
            return ""
        }
    }
}
""",
    )

    allowlist = load_allowlist(
        _make_allowlist(tmp_path, "# empty allowlist\n")
    )
    violations, fatal = scan_file(kt_file, allowlist)

    assert not fatal
    assert violations == [], (
        f"Expected NO violations for rethrowIfCancellation, got: {violations}"
    )


# ── Test: broad catch outside suspend context is NOT flagged ────────────────

def test_broad_catch_outside_suspend_passes(tmp_path):
    """catch(Exception) in non-suspend function, not worker path → no violation."""
    kt_file = _write_kt(
        tmp_path,
        "NonSuspend.kt",
        """package com.example

class NonSuspend {
    fun parseValue(input: String): Int {
        try {
            return input.toInt()
        } catch (e: Exception) {
            return 0
        }
    }
}
""",
    )

    allowlist = load_allowlist(
        _make_allowlist(tmp_path, "# empty allowlist\n")
    )
    violations, fatal = scan_file(kt_file, allowlist)

    assert not fatal
    assert violations == [], (
        f"Expected NO violations for broad catch outside suspend, got: {violations}"
    )


# ── Test: runCatching outside suspend context is NOT flagged ────────────────

def test_runCatching_outside_suspend_passes(tmp_path):
    """runCatching in non-suspend function, not worker path → no violation."""
    kt_file = _write_kt(
        tmp_path,
        "ColorHelper.kt",
        """package com.example

object ColorHelper {
    fun parseColor(hex: String): Int {
        return runCatching { android.graphics.Color.parseColor(hex) }.getOrDefault(0)
    }
}
""",
    )

    allowlist = load_allowlist(
        _make_allowlist(tmp_path, "# empty allowlist\n")
    )
    violations, fatal = scan_file(kt_file, allowlist)

    assert not fatal
    assert violations == [], (
        f"Expected NO violations for runCatching outside suspend, got: {violations}"
    )


# ── Test: Throwable catch without rethrow is detected ────────────────────────

def test_throwable_catch_detected(tmp_path):
    """catch(Throwable) in suspend function without CE rethrow → violation."""
    kt_file = _write_kt(
        tmp_path,
        "ThrowableCatcher.kt",
        """package com.example

class ThrowableCatcher {
    suspend fun process() {
        try {
            riskyOperation()
        } catch (t: Throwable) {
            log("caught throwable")
        }
    }
}
""",
    )

    allowlist = load_allowlist(
        _make_allowlist(tmp_path, "# empty allowlist\n")
    )
    violations, fatal = scan_file(kt_file, allowlist)

    assert not fatal
    assert len(violations) >= 1, (
        f"Expected violation for Throwable catch without CE rethrow, got: {violations}"
    )


# ── Test: .onFailure without CE check is detected ────────────────────────────

def test_onFailure_detected(tmp_path):
    """.onFailure in suspend function without CE check → violation."""
    kt_file = _write_kt(
        tmp_path,
        "OnFailureBad.kt",
        """package com.example

class OnFailureBad {
    suspend fun process(): Int {
        return runCatchingCancellable { fetchData() }
            .onFailure { log("failed") }
            .getOrDefault(0)
    }
}
""",
    )

    allowlist = load_allowlist(
        _make_allowlist(tmp_path, "# empty allowlist\n")
    )
    violations, fatal = scan_file(kt_file, allowlist)

    assert not fatal
    assert len(violations) >= 1, (
        f"Expected violation for .onFailure without CE check, got: {violations}"
    )


# ── Test: CancellationSafe.kt itself is excluded ────────────────────────────

def test_cancellation_safe_excluded(tmp_path):
    """CancellationSafe.kt is excluded from all scanning."""
    kt_file = _write_kt(
        tmp_path,
        "CancellationSafe.kt",
        """package com.yourname.expensetracker.domain.util

object CancellationSafe {
    inline fun <T> runCatchingCancellable(block: () -> T): Result<T> {
        return try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
""",
    )

    allowlist = load_allowlist(
        _make_allowlist(tmp_path, "# empty allowlist\n")
    )
    violations, fatal = scan_file(kt_file, allowlist)

    assert not fatal
    assert violations == [], (
        f"Expected NO violations for CancellationSafe.kt, got: {violations}"
    )


# ── Test: worker path triggers detection even outside suspend ────────────────

def test_worker_path_broad_catch_detected(tmp_path):
    """Broad catch in worker file → violation even outside explicit suspend fun."""
    kt_file = _write_kt(
        tmp_path,
        "SomeWorker.kt",
        """package com.yourname.expensetracker.worker

class SomeWorker : CoroutineWorker {
    override suspend fun doWork(): Result {
        try {
            doActualWork()
        } catch (e: Exception) {
            // forgot to rethrow CE!
            return Result.failure()
        }
    }
}
""",
    )

    allowlist = load_allowlist(
        _make_allowlist(tmp_path, "# empty allowlist\n")
    )
    violations, fatal = scan_file(kt_file, allowlist)

    assert not fatal
    assert len(violations) >= 1, (
        f"Expected violation for broad catch in worker, got: {violations}"
    )


# ── Test: allowlisted file is skipped ────────────────────────────────────────

def test_allowlisted_file_skipped(tmp_path):
    """runCatching in allowlisted file → no violation."""
    kt_file = _write_kt(
        tmp_path,
        "CancellationSafe.kt",
        """package com.yourname.expensetracker.domain.util

// NOT the real CancellationSafe — a user file that mimics it for testing.
// This should still pass because CancellationSafe.kt is excluded by filename.
object Other {
    suspend fun go() {
        runCatching { stuff() }
    }
}
""",
    )

    allowlist = load_allowlist(
        _make_allowlist(tmp_path, "# empty allowlist\n")
    )
    violations, fatal = scan_file(kt_file, allowlist)

    assert not fatal
    assert violations == [], (
        f"Expected NO violations for excluded file, got: {violations}"
    )


# ── Test: ensureActive() counts as safe ──────────────────────────────────────

def test_ensureActive_makes_catch_safe(tmp_path):
    """catch(Exception) with ensureActive() in body → no violation."""
    kt_file = _write_kt(
        tmp_path,
        "EnsureActiveService.kt",
        """package com.example
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

class EnsureActiveService {
    suspend fun fetchData(): String {
        try {
            return loadFromNetwork()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            return ""
        }
    }
}
""",
    )

    allowlist = load_allowlist(
        _make_allowlist(tmp_path, "# empty allowlist\n")
    )
    violations, fatal = scan_file(kt_file, allowlist)

    assert not fatal
    assert violations == [], (
        f"Expected NO violations for catch with ensureActive(), got: {violations}"
    )


# ── Test: catch(RuntimeException) in suspend is detected ─────────────────────

def test_runtime_exception_catch_detected(tmp_path):
    """catch(RuntimeException) in suspend function without CE rethrow → violation."""
    kt_file = _write_kt(
        tmp_path,
        "RuntimeExCatcher.kt",
        """package com.example

class RuntimeExCatcher {
    suspend fun process() {
        try {
            riskyOperation()
        } catch (e: RuntimeException) {
            log("runtime exception")
        }
    }
}
""",
    )

    allowlist = load_allowlist(
        _make_allowlist(tmp_path, "# empty allowlist\n")
    )
    violations, fatal = scan_file(kt_file, allowlist)

    assert not fatal
    assert len(violations) >= 1, (
        f"Expected violation for RuntimeException catch without CE rethrow, got: {violations}"
    )


# ── Test: runCatchingCancellable is NOT flagged as runCatching ───────────────

def test_runCatchingCancellable_not_confused_with_runCatching(tmp_path):
    """runCatchingCancellable does NOT trigger runCatching violation."""
    kt_file = _write_kt(
        tmp_path,
        "DataWorker.kt",
        """package com.yourname.expensetracker.worker

class DataWorker : CoroutineWorker {
    override suspend fun doWork(): Result {
        val result = CancellationSafe.runCatchingCancellable {
            fetchData()
        }
        return if (result.isSuccess) Result.success() else Result.retry()
    }
}
""",
    )

    allowlist = load_allowlist(
        _make_allowlist(tmp_path, "# empty allowlist\n")
    )
    violations, fatal = scan_file(kt_file, allowlist)

    assert not fatal
    
    # Should have NO runCatching violations (runCatchingCancellable is safe)
    runcatching_violations = [v for v in violations if "runCatching" in v and "Cancellable" not in v]
    assert len(runcatching_violations) == 0, (
        f"runCatchingCancellable should NOT be flagged as runCatching, got: {runcatching_violations}"
    )


# ── Test: catch with throw e (rethrow) is safe ───────────────────────────────

def test_throw_e_rethrow_passes(tmp_path):
    """catch(Exception) with `throw e` → no violation (rethrowing caught var)."""
    kt_file = _write_kt(
        tmp_path,
        "RethrowE.kt",
        """package com.example

class RethrowE {
    suspend fun process() {
        try {
            riskyOperation()
        } catch (e: Exception) {
            log("failed")
            throw e
        }
    }
}
""",
    )

    allowlist = load_allowlist(
        _make_allowlist(tmp_path, "# empty allowlist\n")
    )
    violations, fatal = scan_file(kt_file, allowlist)

    assert not fatal
    assert violations == [], (
        f"Expected NO violations for catch with throw e, got: {violations}"
    )


# ── Test: production codebase scan returns results ──────────────────────────

def test_scan_returns_list():
    """scan_file always returns a list type for violations."""
    # Quick smoke test: scan a non-existent file path
    violations, fatal = scan_file(
        Path("nonexistent.kt"),
        [],
    )
    assert fatal, "Non-existent file should cause fatal error"
    assert isinstance(violations, list)
