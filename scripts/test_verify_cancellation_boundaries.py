"""
test_verify_cancellation_boundaries.py
MIT-035 acceptance tests for the Cancellation Boundary Guard.

Run with: python -m pytest scripts/test_verify_cancellation_boundaries.py -v
"""
import os
import sys
import tempfile
import pytest
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


# ── Test: missing allowlist is fatal (exit code 2) ─────────────────────────

def test_missing_allowlist_is_fatal():
    """Missing configured allowlist yields exit code 2."""
    import subprocess
    result = subprocess.run(
        [sys.executable, "scripts/verify_cancellation_boundaries.py", "--allowlist", "nonexistent_file.yml"],
        capture_output=True, text=True, timeout=10
    )
    assert result.returncode == 2, f"Expected exit 2, got {result.returncode}"


# ── Test: malformed YAML is fatal (exit code 2) ────────────────────────────

def test_malformed_yaml_is_fatal(tmp_path):
    """Malformed YAML allowlist yields exit code 2."""
    # Create malformed YAML
    bad_yaml = tmp_path / "bad.yml"
    bad_yaml.write_text("{this is not valid yaml: [}")
    import subprocess
    result = subprocess.run(
        [sys.executable, "scripts/verify_cancellation_boundaries.py", "--allowlist", str(bad_yaml)],
        capture_output=True, text=True, timeout=10
    )
    assert result.returncode == 2, f"Expected exit 2 for malformed YAML, got {result.returncode}"


# ── PR-GR-10B source-scope contract ─────────────────────────────────────────
# The guard's scan scope is the checked-in production source-root manifest
# (config/guards/production_source_roots.yml), resolved via
# scripts/guardrails/production_source_scope.py — fail closed with exit 2
# when it is missing/malformed/undeclared; cancellation relevance is a
# semantic filter applied AFTER enumerating every declared production file.
# The fixture passes --allowlist because the guard resolves its allowlist
# relative to --root (the default repo-relative allowlist does not exist in
# a fixture).

def _write_scope_manifest(root, root_rel="app/src/main/java"):
    manifest = root / "config" / "guards" / "production_source_roots.yml"
    manifest.parent.mkdir(parents=True, exist_ok=True)
    manifest.write_text(
        "schemaVersion: 1\n"
        "roots:\n"
        "  - module: ':app'\n"
        "    sourceSet: main\n"
        f"    path: {root_rel}\n",
        encoding="utf-8",
    )


_CANCEL_VIOLATION_KT = (
    "package com.example\n"
    "\n"
    "class BadRunner {\n"
    "    suspend fun process(): Int {\n"
    "        val result = runCatching { fetchData() }\n"
    "        return result.getOrDefault(0)\n"
    "    }\n"
    "}\n"
)

_CLEAN_KT = "package com.example\nclass Clean { fun f() = 1 }\n"

_SCOPE_ALLOWLIST_REL = "cancellation_scope_allowlist.yml"


def _write_scope_allowlist(root):
    target = root / _SCOPE_ALLOWLIST_REL
    target.write_text("# empty allowlist\n", encoding="utf-8")


def _scope_run_main(root, monkeypatch, capsys, extra=()):
    monkeypatch.setattr(
        sys, "argv",
        ["verify_cancellation_boundaries.py", "--root", str(root)]
        + ["--allowlist", _SCOPE_ALLOWLIST_REL] + list(extra),
    )
    with pytest.raises(SystemExit) as excinfo:
        _mod.main()
    return excinfo.value.code, capsys.readouterr()


def _write_declared(root, rel, content):
    target = root / rel
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(content, encoding="utf-8")


def test_manifest_declared_root_finding_matches_hardcoded_era(tmp_path, monkeypatch, capsys):
    """With the single-root manifest declaring app/src/main/java, findings
    are identical to the pre-GR-10B hard-coded-root era: runCatching in a
    suspend function under the declared root is flagged."""
    _write_scope_manifest(tmp_path)
    _write_scope_allowlist(tmp_path)
    _write_declared(
        tmp_path,
        "app/src/main/java/com/example/BadRunner.kt",
        _CANCEL_VIOLATION_KT,
    )

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys, ["--fail-on-violation"])

    assert code == 1
    assert "BadRunner.kt" in out.out
    assert "runCatching" in out.out


def test_missing_manifest_fails_closed(tmp_path, monkeypatch, capsys):
    """No checked-in manifest -> exit 2 (no conventional-root fallback)."""
    _write_scope_allowlist(tmp_path)
    _write_declared(
        tmp_path,
        "app/src/main/java/com/example/BadRunner.kt",
        _CANCEL_VIOLATION_KT,
    )

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys)

    assert code == 2
    assert "production source scope unresolved" in out.err


def test_undeclared_root_file_is_never_scanned(tmp_path, monkeypatch, capsys):
    """Kotlin files outside the declared production roots are invisible."""
    _write_scope_manifest(tmp_path)
    _write_scope_allowlist(tmp_path)
    _write_declared(tmp_path, "app/src/main/java/com/example/Clean.kt", _CLEAN_KT)
    undeclared = (
        tmp_path / "other" / "src" / "main" / "java" / "com" / "example"
        / "BadRunner.kt"
    )
    undeclared.parent.mkdir(parents=True)
    undeclared.write_text(_CANCEL_VIOLATION_KT, encoding="utf-8")

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys)

    assert code == 0
    assert "BadRunner" not in out.out
