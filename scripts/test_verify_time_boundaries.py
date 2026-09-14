#!/usr/bin/env python3
"""
test_verify_time_boundaries.py — acceptance tests for the direct wall-clock
time boundary guard (G-TIME-01, PR-GR-02).

Covers:
  1. every listed API is detected;
  2. comments, string literals and imports are ignored;
  3. string-template expressions (${...}) are still detected;
  4. variable/method names containing "now" never suppress detection;
  5. an exact exception entry passes;
  6. wrong path/class/method/api fails;
   7. System.nanoTime elapsed-duration exception passes;
   8. missing/malformed allowlist -> exit 2;
   9. empty/missing source tree -> exit 2;
   10. stale exception entries -> exit 2;
   11. deterministic fingerprints and output;
   12. strict exception schema fails closed (exit 2) on unknown keys
       (extra, expires, baseline, permanent, metadata, unknown), wildcard
       path/method/api, and non-canonical paths — including backslash
       paths, which are rejected before normalization and never silently
       converted to forward slashes; valid exact entries pass.
       Top-level policy keys are restricted to 'version' and 'exceptions'
       and the version must be the integer 1 (bool/string versions are
       rejected), all with exit 2.
   13. multiline expression-body attribution fails closed: an unprovable
       expression-bodied function boundary emits the controlled ambiguous
       marker (``<expression-body>``) instead of carrying the previous
       method name; an exact exception for method A never suppresses a later
       or ambiguous occurrence; the reserved marker is rejected as a policy
       method; provable single-line expression bodies keep the real name.
   14. Date() is flagged only when the constructor argument list is empty;
       Date(epochMillis), Date(0L), and Date(timeProvider.now()) convert an
       already-known epoch and are not flagged.
    15. GR-02 attribution coverage: a multiline ``@Inject constructor(...)``
        class header with a wrapped interface list attributes members to the
        real class and ``System.nanoTime()`` inside an exact method is
        attributed ``Class.method`` with an exact exception passing; a local
        helper function declared inside a method keeps its time usage
        attributed to the owning method; attribution scope is restored after
        a nested class closes; body-less ``data``/``sealed`` declarations never
        create an attribution scope for following classes/methods.
    16. AppDatabase migration exceptions are exact: named ``object``
        migrations inside a companion object are attributed to their own
        class (``MIGRATION_16_17.migrate``, ``MIGRATION_41_42.migrate``), a
        broad ``AppDatabase.Companion.migrate`` entry can no longer match
        them (stale -> exit 2), and two separate exact entries PASS. This
        documents why the policy never uses one broad migration bucket.

Run:
  python -m pytest scripts/test_verify_time_boundaries.py -v
"""

import os
import subprocess
import sys
from pathlib import Path

import pytest

SCRIPT_DIR = Path(__file__).resolve().parent
GUARD_SCRIPT = SCRIPT_DIR / "verify_time_boundaries.py"

# Import the module under test directly.
sys.path.insert(0, str(SCRIPT_DIR))
import importlib.util  # noqa: E402

_spec = importlib.util.spec_from_file_location(
    "verify_time_boundaries", str(GUARD_SCRIPT)
)
_mod = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_mod)

mask_kotlin = _mod.mask_kotlin
scan_content = _mod.scan_content
load_exceptions = _mod.load_exceptions
TimeViolation = _mod.TimeViolation
GuardFatalError = _mod.GuardFatalError
RULE_ID = _mod.RULE_ID

ALL_APIS = [
    "System.currentTimeMillis",
    "System.nanoTime",
    "Date",
    "Calendar.getInstance",
    "Instant.now",
    "LocalDate.now",
    "LocalDateTime.now",
    "OffsetDateTime.now",
    "ZonedDateTime.now",
    "Clock.systemDefaultZone",
    "Clock.systemUTC",
]

API_CALLS = {
    "System.currentTimeMillis": "System.currentTimeMillis()",
    "System.nanoTime": "System.nanoTime()",
    "Date": "Date()",
    "Calendar.getInstance": "Calendar.getInstance()",
    "Instant.now": "Instant.now()",
    "LocalDate.now": "LocalDate.now()",
    "LocalDateTime.now": "LocalDateTime.now()",
    "OffsetDateTime.now": "OffsetDateTime.now()",
    "ZonedDateTime.now": "ZonedDateTime.now()",
    "Clock.systemDefaultZone": "Clock.systemDefaultZone()",
    "Clock.systemUTC": "Clock.systemUTC()",
}


# ── Helpers ─────────────────────────────────────────────────────────────────────

def _write_kt(root: Path, rel: str, content: str) -> Path:
    """Write a .kt file under a project root and return its path."""
    p = root / "app" / "src" / "main" / "java" / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding="utf-8")
    return p


def _write_manifest(root: Path) -> None:
    """Write the checked-in-style production source-root manifest (PR-GR-10B).

    Repository-level guard runs resolve the production source scope ONLY
    from this manifest; synthetic fixtures declare the conventional root so
    the scanned file set matches the pre-GR-10B hard-coded era.
    """
    manifest = root / "config" / "guards" / "production_source_roots.yml"
    if not manifest.exists():
        manifest.parent.mkdir(parents=True, exist_ok=True)
        manifest.write_text(
            "schemaVersion: 1\n"
            "roots:\n"
            "  - module: ':app'\n"
            "    sourceSet: main\n"
            "    path: app/src/main/java\n",
            encoding="utf-8",
        )


def _run_guard(root: Path, allowlist: Path = None, fail_on_violation: bool = False,
               extra_args=None, write_manifest: bool = True):
    """Run the guard as a subprocess and return CompletedProcess.

    ``write_manifest=False`` runs WITHOUT the checked-in production
    source-root manifest — used only by the manifest-absent fail-closed test
    (PR-GR-10B); every other run declares the conventional root.
    """
    if write_manifest:
        _write_manifest(root)
    cmd = [sys.executable, str(GUARD_SCRIPT), "--root", str(root)]
    if allowlist is not None:
        cmd += ["--allowlist", str(allowlist)]
    if fail_on_violation:
        cmd += ["--fail-on-violation"]
    if extra_args:
        cmd += extra_args
    return subprocess.run(
        cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=60
    )


def _empty_allowlist(root: Path) -> Path:
    p = root / "config" / "guards" / "time_boundary_exceptions.yml"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text("version: 1\nexceptions: []\n", encoding="utf-8")
    return p


# ── 1. Every listed API is detected ─────────────────────────────────────────────

class TestEveryApiDetected:
    @pytest.mark.parametrize("api", ALL_APIS)
    def test_api_detected(self, tmp_path, api):
        root = tmp_path
        src = _write_kt(root, "Example.kt", (
            "package com.example\n\n"
            "class Example {\n"
            f"    fun sample() {{ val t = {API_CALLS[api]} }}\n"
            "}\n"
        ))
        violations = scan_content(src.read_text(encoding="utf-8"), src.relative_to(root).as_posix())
        assert any(v.api == api for v in violations), f"expected {api} to be detected"
        v = next(v for v in violations if v.api == api)
        assert v.line == 4
        assert v.symbol == "Example.sample"
        assert v.format().startswith(f"{RULE_ID} ")

    def test_qualified_forms_detected(self, tmp_path):
        root = tmp_path
        src = _write_kt(root, "Qualified.kt", (
            "package com.example\n\n"
            "class Qualified {\n"
            "    fun a() { val d = java.util.Date() }\n"
            "    fun b() { val c = java.util.Calendar.getInstance() }\n"
            "    fun c() { val i = java.time.Instant.now() }\n"
            "    fun d() { val ck = java.time.Clock.systemUTC() }\n"
            "}\n"
        ))
        violations = scan_content(src.read_text(encoding="utf-8"), "Qualified.kt")
        apis = {v.api for v in violations}
        assert {"Date", "Calendar.getInstance", "Instant.now", "Clock.systemUTC"} <= apis

    def test_now_call_with_argument_still_detected(self, tmp_path):
        root = tmp_path
        src = _write_kt(root, "WithArg.kt", (
            "package com.example\n\n"
            "class WithArg {\n"
            "    fun sample() { val t = LocalDateTime.now(ZoneId.of(\"UTC\")) }\n"
            "}\n"
        ))
        violations = scan_content(src.read_text(encoding="utf-8"), "WithArg.kt")
        assert any(v.api == "LocalDateTime.now" for v in violations)


# ── 1b. Date() is flagged only with an empty constructor argument list ─────────

class TestDateConstructorArguments:
    def test_no_arg_date_detected(self, tmp_path):
        """The no-arg Date() constructor is a direct wall-clock read."""
        root = tmp_path
        src = _write_kt(root, "DateNoArg.kt", (
            "package com.example\n\n"
            "class DateNoArg {\n"
            "    fun sample() { val d = Date() }\n"
            "}\n"
        ))
        violations = scan_content(src.read_text(encoding="utf-8"), "DateNoArg.kt")
        assert any(v.api == "Date" for v in violations)

    def test_no_arg_date_with_whitespace_detected(self, tmp_path):
        root = tmp_path
        src = _write_kt(root, "DateNoArgSpaced.kt", (
            "package com.example\n\n"
            "class DateNoArgSpaced {\n"
            "    fun sample() { val d = Date (  ) }\n"
            "}\n"
        ))
        violations = scan_content(src.read_text(encoding="utf-8"), "DateNoArgSpaced.kt")
        assert any(v.api == "Date" for v in violations)

    def test_date_with_epoch_arg_not_detected(self, tmp_path):
        """Date(epochMillis) converts an already-known epoch — not a read."""
        root = tmp_path
        src = _write_kt(root, "DateWithArg.kt", (
            "package com.example\n\n"
            "class DateWithArg {\n"
            "    fun sample(epochMillis: Long) { val d = Date(epochMillis) }\n"
            "}\n"
        ))
        violations = scan_content(src.read_text(encoding="utf-8"), "DateWithArg.kt")
        assert not any(v.api == "Date" for v in violations)

    def test_date_zero_literal_arg_not_detected(self, tmp_path):
        root = tmp_path
        src = _write_kt(root, "DateZero.kt", (
            "package com.example\n\n"
            "class DateZero {\n"
            "    fun sample() { val d = Date(0L) }\n"
            "}\n"
        ))
        violations = scan_content(src.read_text(encoding="utf-8"), "DateZero.kt")
        assert not any(v.api == "Date" for v in violations)

    def test_multiline_no_arg_date_detected(self, tmp_path):
        """A no-arg Date() whose closing paren is on the following masked
        line is still a direct wall-clock read and must be flagged."""
        root = tmp_path
        src = _write_kt(root, "DateMultilineNoArg.kt", (
            "package com.example\n\n"
            "class DateMultilineNoArg {\n"
            "    fun sample() {\n"
            "        val d = Date(\n"
            "        )\n"
            "    }\n"
            "}\n"
        ))
        violations = scan_content(src.read_text(encoding="utf-8"), "DateMultilineNoArg.kt")
        date_v = [v for v in violations if v.api == "Date"]
        assert len(date_v) == 1, violations
        assert date_v[0].line == 5, date_v

    def test_multiline_no_arg_date_with_whitespace_detected(self, tmp_path):
        """A no-arg Date() with whitespace-only argument content spread
        across masked lines is still flagged."""
        root = tmp_path
        src = _write_kt(root, "DateMultilineNoArgSpaced.kt", (
            "package com.example\n\n"
            "class DateMultilineNoArgSpaced {\n"
            "    fun sample() {\n"
            "        val d = Date(\n"
            "            // a comment in the empty argument list\n"
            "        )\n"
            "    }\n"
            "}\n"
        ))
        violations = scan_content(src.read_text(encoding="utf-8"), "DateMultilineNoArgSpaced.kt")
        date_v = [v for v in violations if v.api == "Date"]
        assert len(date_v) == 1, violations
        assert date_v[0].line == 5, date_v

    def test_multiline_date_with_argument_not_detected(self, tmp_path):
        """A Date(...) whose epoch argument spans masked lines is a
        conversion of an already-known epoch and must not be flagged."""
        root = tmp_path
        src = _write_kt(root, "DateMultilineArg.kt", (
            "package com.example\n\n"
            "class DateMultilineArg {\n"
            "    fun sample(epochMillis: Long) {\n"
            "        val d = Date(\n"
            "            epochMillis\n"
            "        )\n"
            "    }\n"
            "}\n"
        ))
        violations = scan_content(src.read_text(encoding="utf-8"), "DateMultilineArg.kt")
        assert not any(v.api == "Date" for v in violations)

    def test_multiline_date_timeprovider_now_arg_not_detected(self, tmp_path):
        """Date(\n timeProvider.now() \n) spans lines but converts an
        already-known epoch and must not be flagged."""
        root = tmp_path
        src = _write_kt(root, "DateMultilineTimeProvider.kt", (
            "package com.example\n\n"
            "class DateMultilineTimeProvider {\n"
            "    fun sample(timeProvider: TimeProvider) {\n"
            "        val d = Date(\n"
            "            timeProvider.now()\n"
            "        )\n"
            "    }\n"
            "}\n"
        ))
        violations = scan_content(src.read_text(encoding="utf-8"), "DateMultilineTimeProvider.kt")
        assert not any(v.api == "Date" for v in violations)

    def test_multiline_date_zero_literal_arg_not_detected(self, tmp_path):
        root = tmp_path
        src = _write_kt(root, "DateMultilineZero.kt", (
            "package com.example\n\n"
            "class DateMultilineZero {\n"
            "    fun sample() {\n"
            "        val d = Date(\n"
            "            0L\n"
            "        )\n"
            "    }\n"
            "}\n"
        ))
        violations = scan_content(src.read_text(encoding="utf-8"), "DateMultilineZero.kt")
        assert not any(v.api == "Date" for v in violations)

    def test_date_with_arg_spanning_multiple_lines_not_detected(self, tmp_path):
        """A Date(...) whose epoch argument spans more than one masked line
        is a conversion and must not be flagged."""
        root = tmp_path
        src = _write_kt(root, "DateMultilineWide.kt", (
            "package com.example\n\n"
            "class DateMultilineWide {\n"
            "    fun sample() {\n"
            "        val d = Date(\n"
            "            someModule\n"
            "                .toEpochMillis()\n"
            "        )\n"
            "    }\n"
            "}\n"
        ))
        violations = scan_content(src.read_text(encoding="utf-8"), "DateMultilineWide.kt")
        assert not any(v.api == "Date" for v in violations)

    def test_date_timeprovider_now_arg_not_detected(self, tmp_path):
        root = tmp_path
        src = _write_kt(root, "DateTimeProvider.kt", (
            "package com.example\n\n"
            "class DateTimeProvider {\n"
            "    fun sample(timeProvider: TimeProvider) { val d = Date(timeProvider.now()) }\n"
            "}\n"
        ))
        violations = scan_content(src.read_text(encoding="utf-8"), "DateTimeProvider.kt")
        assert not any(v.api == "Date" for v in violations)

    def test_qualified_no_arg_date_detected(self, tmp_path):
        root = tmp_path
        src = _write_kt(root, "QualifiedNoArg.kt", (
            "package com.example\n\n"
            "class QualifiedNoArg {\n"
            "    fun sample() { val d = java.util.Date() }\n"
            "}\n"
        ))
        violations = scan_content(src.read_text(encoding="utf-8"), "QualifiedNoArg.kt")
        assert any(v.api == "Date" for v in violations)

    def test_qualified_date_with_arg_not_detected(self, tmp_path):
        root = tmp_path
        src = _write_kt(root, "QualifiedWithArg.kt", (
            "package com.example\n\n"
            "class QualifiedWithArg {\n"
            "    fun sample(epochMillis: Long) { val d = java.util.Date(epochMillis) }\n"
            "}\n"
        ))
        violations = scan_content(src.read_text(encoding="utf-8"), "QualifiedWithArg.kt")
        assert not any(v.api == "Date" for v in violations)


# ── 2. Comments / strings / imports ignored ─────────────────────────────────────

class TestCommentsStringsIgnored:
    def test_comment_occurrences_ignored(self, tmp_path):
        root = tmp_path
        src = _write_kt(root, "Commenty.kt", (
            "package com.example\n\n"
            "// Instant.now() is forbidden here\n"
            "/* System.currentTimeMillis() blocked comment\n"
            "   LocalDate.now() continues */\n"
            "class Commenty {\n"
            "    fun clean() { val t = 1 }\n"
            "}\n"
        ))
        violations = scan_content(src.read_text(encoding="utf-8"), "Commenty.kt")
        assert violations == []

    def test_string_literal_occurrences_ignored(self, tmp_path):
        root = tmp_path
        src = _write_kt(root, "Stringy.kt", (
            "package com.example\n\n"
            "class Stringy {\n"
            "    fun clean() {\n"
            "        val a = \"Instant.now() is just text\"\n"
            "        val b = 'z'\n"
            "        val c = \"\"\"\n"
            "            System.currentTimeMillis()\n"
            "        \"\"\"\n"
            "    }\n"
            "}\n"
        ))
        violations = scan_content(src.read_text(encoding="utf-8"), "Stringy.kt")
        assert violations == []

    def test_imports_ignored(self, tmp_path):
        root = tmp_path
        src = _write_kt(root, "Imports.kt", (
            "package com.example\n\n"
            "import java.time.Instant\n"
            "import java.util.Calendar\n"
            "import java.util.Date\n"
            "import java.time.Clock\n\n"
            "class Imports {\n"
            "    fun clean() { val t = 1 }\n"
            "}\n"
        ))
        violations = scan_content(src.read_text(encoding="utf-8"), "Imports.kt")
        assert violations == []

    def test_string_template_expression_is_detected(self, tmp_path):
        # ${...} is executable code — masking it would be an exemption loophole.
        root = tmp_path
        src = _write_kt(root, "Template.kt", (
            "package com.example\n\n"
            "class Template {\n"
            "    fun sample() { val s = \"camera_${System.currentTimeMillis()}.jpg\" }\n"
            "}\n"
        ))
        violations = scan_content(src.read_text(encoding="utf-8"), "Template.kt")
        assert any(v.api == "System.currentTimeMillis" for v in violations)


# ── 3. "now" naming never suppresses detection ──────────────────────────────────

class TestNowNamingDoesNotSuppress:
    def test_fun_named_now(self, tmp_path):
        root = tmp_path
        src = _write_kt(root, "NowFun.kt", (
            "package com.example\n\n"
            "class NowFun {\n"
            "    fun now() = ZonedDateTime.now()\n"
            "}\n"
        ))
        violations = scan_content(src.read_text(encoding="utf-8"), "NowFun.kt")
        assert any(v.api == "ZonedDateTime.now" for v in violations)

    def test_variable_named_now(self, tmp_path):
        root = tmp_path
        src = _write_kt(root, "NowVal.kt", (
            "package com.example\n\n"
            "class NowVal {\n"
            "    fun sample() { val now = Instant.now() }\n"
            "}\n"
        ))
        violations = scan_content(src.read_text(encoding="utf-8"), "NowVal.kt")
        assert any(v.api == "Instant.now" for v in violations)

    def test_timeprovider_substring_does_not_suppress(self, tmp_path):
        root = tmp_path
        src = _write_kt(root, "TpLine.kt", (
            "package com.example\n\n"
            "class TpLine {\n"
            "    fun sample(timeProvider: TimeProvider) {\n"
            "        val t = LocalDate.now() // timeProvider is unused here\n"
            "    }\n"
            "}\n"
        ))
        violations = scan_content(src.read_text(encoding="utf-8"), "TpLine.kt")
        assert any(v.api == "LocalDate.now" for v in violations)


# ── 4. Exact exception passes; wrong fields fail ────────────────────────────────

def _sample_allowlist(root: Path, overrides: dict = None, extra: list = None,
                      extra_fields: dict = None) -> Path:
    entry = {
        "path": "app/src/main/java/com/example/SystemTimeProvider.kt",
        "class": "SystemTimeProvider",
        "method": "now",
        "api": "System.currentTimeMillis",
        "reason": "Canonical platform clock adapter",
        "owner": "@tester",
        "linked_issue": "MIT-003",
    }
    if overrides:
        entry.update(overrides)
    if extra_fields:
        entry.update(extra_fields)
    body = "version: 1\nexceptions:\n  - " + _yaml_inline(entry) + "\n"
    if extra:
        body += "\n".join("  - " + _yaml_inline(e) for e in extra) + "\n"
    p = root / "config" / "guards" / "time_boundary_exceptions.yml"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(body, encoding="utf-8")
    return p


def _yaml_inline(d: dict) -> str:
    return "\n    ".join(f"{k}: {v!r}" for k, v in d.items())


class TestExceptionMatching:
    def test_exact_exception_passes(self, tmp_path):
        root = tmp_path
        _write_kt(root, "com/example/SystemTimeProvider.kt", (
            "package com.example\n\n"
            "class SystemTimeProvider {\n"
            "    fun now(): Long = System.currentTimeMillis()\n"
            "}\n"
        ))
        allowlist = _sample_allowlist(root)
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 0, f"stdout={result.stdout}\nstderr={result.stderr}"
        assert "PASS" in result.stdout

    def test_wrong_path_fails(self, tmp_path):
        root = tmp_path
        _write_kt(root, "com/example/SystemTimeProvider.kt", (
            "package com.example\n\n"
            "class SystemTimeProvider {\n"
            "    fun now(): Long = System.currentTimeMillis()\n"
            "}\n"
        ))
        allowlist = _sample_allowlist(root, overrides={
            "path": "app/src/main/java/com/other/SystemTimeProvider.kt"
        })
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 2
        assert RULE_ID in result.stderr
        assert "SystemTimeProvider" in result.stderr

    def test_wrong_class_fails(self, tmp_path):
        root = tmp_path
        _write_kt(root, "com/example/SystemTimeProvider.kt", (
            "package com.example\n\n"
            "class SystemTimeProvider {\n"
            "    fun now(): Long = System.currentTimeMillis()\n"
            "}\n"
        ))
        allowlist = _sample_allowlist(root, overrides={"class": "OtherTimeProvider"})
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 2

    def test_wrong_method_fails(self, tmp_path):
        root = tmp_path
        _write_kt(root, "com/example/SystemTimeProvider.kt", (
            "package com.example\n\n"
            "class SystemTimeProvider {\n"
            "    fun now(): Long = System.currentTimeMillis()\n"
            "}\n"
        ))
        allowlist = _sample_allowlist(root, overrides={"method": "other"})
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 2

    def test_wrong_api_fails(self, tmp_path):
        root = tmp_path
        _write_kt(root, "com/example/SystemTimeProvider.kt", (
            "package com.example\n\n"
            "class SystemTimeProvider {\n"
            "    fun now(): Long = System.currentTimeMillis()\n"
            "}\n"
        ))
        allowlist = _sample_allowlist(root, overrides={"api": "Instant.now"})
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 2

    def test_multiple_violations_in_one_method_all_suppressed(self, tmp_path):
        root = tmp_path
        _write_kt(root, "com/example/SystemTimeProvider.kt", (
            "package com.example\n\n"
            "class SystemTimeProvider {\n"
            "    fun now(): Long {\n"
            "        val a = System.currentTimeMillis()\n"
            "        val b = System.currentTimeMillis()\n"
            "        return b - a\n"
            "    }\n"
            "}\n"
        ))
        allowlist = _sample_allowlist(root)
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 0, f"stdout={result.stdout}\nstderr={result.stderr}"


# ── 4b. Strict exception schema (exact keys, no wildcards, canonical paths) ────

class TestExceptionSchemaStrict:
    def test_extra_key_rejected_exit_2(self, tmp_path):
        root = tmp_path
        _write_kt(root, "A.kt", "package com.example\nclass A { fun f() = 1 }\n")
        allowlist = _sample_allowlist(root, extra_fields={"metadata": "unexpected"})
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 2
        assert "unknown field" in result.stderr.lower()
        assert "metadata" in result.stderr.lower()

    def test_expires_key_rejected_exit_2(self, tmp_path):
        root = tmp_path
        _write_kt(root, "A.kt", "package com.example\nclass A { fun f() = 1 }\n")
        allowlist = _sample_allowlist(root, extra_fields={"expires": "2026-12-31"})
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 2
        assert "unknown field" in result.stderr.lower()
        assert "expires" in result.stderr.lower()

    def test_baseline_key_rejected_exit_2(self, tmp_path):
        root = tmp_path
        _write_kt(root, "A.kt", "package com.example\nclass A { fun f() = 1 }\n")
        allowlist = _sample_allowlist(root, extra_fields={"baseline": "2024-01-01"})
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 2
        assert "unknown field" in result.stderr.lower()
        assert "baseline" in result.stderr.lower()

    def test_permanent_key_rejected_exit_2(self, tmp_path):
        root = tmp_path
        _write_kt(root, "A.kt", "package com.example\nclass A { fun f() = 1 }\n")
        allowlist = _sample_allowlist(root, extra_fields={"permanent": True})
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 2
        assert "unknown field" in result.stderr.lower()
        assert "permanent" in result.stderr.lower()

    def test_unknown_key_rejected_exit_2(self, tmp_path):
        root = tmp_path
        _write_kt(root, "A.kt", "package com.example\nclass A { fun f() = 1 }\n")
        allowlist = _sample_allowlist(root, extra_fields={"unknown": "x"})
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 2
        assert "unknown field" in result.stderr.lower()
        assert "unknown" in result.stderr.lower()

    def test_unknown_top_level_key_rejected_exit_2(self, tmp_path):
        """Top-level policy keys are restricted to 'version' and
        'exceptions'; any other key (metadata, rule, expires, ...) is
        rejected with exit 2."""
        root = tmp_path
        _write_kt(root, "A.kt", "package com.example\nclass A { fun f() = 1 }\n")
        allowlist = root / "config" / "guards" / "time_boundary_exceptions.yml"
        allowlist.parent.mkdir(parents=True, exist_ok=True)
        allowlist.write_text(
            "version: 1\nexceptions: []\nmetadata: unexpected\n",
            encoding="utf-8",
        )
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 2
        assert "unknown top-level" in result.stderr.lower()
        assert "metadata" in result.stderr.lower()

    def test_version_bool_rejected_exit_2(self, tmp_path):
        """'version: true' is not a valid policy version — bool must not be
        accepted even though True == 1 in Python."""
        root = tmp_path
        _write_kt(root, "A.kt", "package com.example\nclass A { fun f() = 1 }\n")
        allowlist = root / "config" / "guards" / "time_boundary_exceptions.yml"
        allowlist.parent.mkdir(parents=True, exist_ok=True)
        allowlist.write_text(
            "version: true\nexceptions: []\n",
            encoding="utf-8",
        )
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 2
        assert "version" in result.stderr.lower()
        assert "integer" in result.stderr.lower()

    def test_version_string_rejected_exit_2(self, tmp_path):
        """'version: "1"' is a string, not the integer policy version."""
        root = tmp_path
        _write_kt(root, "A.kt", "package com.example\nclass A { fun f() = 1 }\n")
        allowlist = root / "config" / "guards" / "time_boundary_exceptions.yml"
        allowlist.parent.mkdir(parents=True, exist_ok=True)
        allowlist.write_text(
            "version: \"1\"\nexceptions: []\n",
            encoding="utf-8",
        )
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 2
        assert "version" in result.stderr.lower()

    def test_wildcard_method_rejected_exit_2(self, tmp_path):
        root = tmp_path
        _write_kt(root, "A.kt", "package com.example\nclass A { fun f() = 1 }\n")
        allowlist = _sample_allowlist(root, overrides={"method": "find*"})
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 2
        assert "wildcard" in result.stderr.lower()

    def test_wildcard_api_rejected_exit_2(self, tmp_path):
        root = tmp_path
        _write_kt(root, "A.kt", "package com.example\nclass A { fun f() = 1 }\n")
        allowlist = _sample_allowlist(root, overrides={"api": "*.now"})
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 2
        assert "wildcard" in result.stderr.lower()

    def test_noncanonical_absolute_path_rejected_exit_2(self, tmp_path):
        root = tmp_path
        _write_kt(root, "A.kt", "package com.example\nclass A { fun f() = 1 }\n")
        allowlist = _sample_allowlist(root, overrides={
            "path": "C:/repo/app/src/main/java/com/example/SystemTimeProvider.kt"
        })
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 2
        assert "non-canonical" in result.stderr.lower()

    def test_noncanonical_dot_slash_path_rejected_exit_2(self, tmp_path):
        root = tmp_path
        _write_kt(root, "A.kt", "package com.example\nclass A { fun f() = 1 }\n")
        allowlist = _sample_allowlist(root, overrides={
            "path": "./app/src/main/java/com/example/SystemTimeProvider.kt"
        })
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 2
        assert "non-canonical" in result.stderr.lower()

    def test_noncanonical_dot_dot_path_rejected_exit_2(self, tmp_path):
        root = tmp_path
        _write_kt(root, "A.kt", "package com.example\nclass A { fun f() = 1 }\n")
        allowlist = _sample_allowlist(root, overrides={
            "path": "app/src/main/java/../com/example/SystemTimeProvider.kt"
        })
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 2
        assert "non-canonical" in result.stderr.lower()

    def test_noncanonical_missing_prefix_path_rejected_exit_2(self, tmp_path):
        root = tmp_path
        _write_kt(root, "A.kt", "package com.example\nclass A { fun f() = 1 }\n")
        allowlist = _sample_allowlist(root, overrides={
            "path": "com/example/SystemTimeProvider.kt"
        })
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 2
        assert "non-canonical" in result.stderr.lower()

    def test_backslash_path_rejected_before_normalization_exit_2(self, tmp_path):
        """A Windows-style backslash path must be rejected before any
        normalization. The policy schema requires canonical forward-slash
        repository paths; a backslash path must never be silently rewritten
        to a valid canonical path and pass the guard."""
        root = tmp_path
        _write_kt(root, "A.kt", "package com.example\nclass A { fun f() = 1 }\n")
        allowlist = root / "config" / "guards" / "time_boundary_exceptions.yml"
        allowlist.parent.mkdir(parents=True, exist_ok=True)
        allowlist.write_text(
            "version: 1\nexceptions:\n"
            "  - path: 'app\\src\\main\\java\\com\\example\\SystemTimeProvider.kt'\n"
            "    class: SystemTimeProvider\n"
            "    method: now\n"
            "    api: System.currentTimeMillis\n"
            "    reason: 'canonical platform clock adapter'\n"
            "    owner: '@tester'\n"
            "    linked_issue: MIT-003\n",
            encoding="utf-8",
        )
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 2
        assert "non-canonical" in result.stderr.lower()
        assert "backslash" in result.stderr.lower()

    def test_valid_exact_entry_still_passes(self, tmp_path):
        root = tmp_path
        _write_kt(root, "com/example/SystemTimeProvider.kt", (
            "package com.example\n\n"
            "class SystemTimeProvider {\n"
            "    fun now(): Long = System.currentTimeMillis()\n"
            "}\n"
        ))
        allowlist = _sample_allowlist(root)
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 0, f"stdout={result.stdout}\nstderr={result.stderr}"
        assert "PASS" in result.stdout


# ── 5. System.nanoTime elapsed-duration exception ───────────────────────────────

class TestNanoTimeElapsedDuration:
    def test_nano_time_exception_passes(self, tmp_path):
        root = tmp_path
        _write_kt(root, "com/example/SettlementCalculator.kt", (
            "package com.example\n\n"
            "class SettlementCalculator {\n"
            "    private fun findMinimalTransferPlan() {\n"
            "        val startedAtNs = System.nanoTime()\n"
            "        val elapsedNs = System.nanoTime() - startedAtNs\n"
            "        check(elapsedNs >= 0)\n"
            "    }\n"
            "}\n"
        ))
        allowlist = _sample_allowlist(root, overrides={
            "path": "app/src/main/java/com/example/SettlementCalculator.kt",
            "class": "SettlementCalculator",
            "method": "findMinimalTransferPlan",
            "api": "System.nanoTime",
            "reason": "Monotonic elapsed-duration measurement",
        })
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 0, f"stdout={result.stdout}\nstderr={result.stderr}"

    def test_nano_time_without_exception_fails(self, tmp_path):
        root = tmp_path
        _write_kt(root, "Plain.kt", (
            "package com.example\n\n"
            "class Plain {\n"
            "    fun time() { val t = System.nanoTime() }\n"
            "}\n"
        ))
        allowlist = _empty_allowlist(root)
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 1
        assert "System.nanoTime" in result.stdout

    def test_nano_time_unique_name_not_exempt(self, tmp_path):
        # Using nanoTime for unique file names is NOT elapsed-duration and must fail.
        root = tmp_path
        _write_kt(root, "Ocr.kt", (
            "package com.example\n\n"
            "class Ocr {\n"
            "    fun save() { val f = \"temp_${System.nanoTime()}.pdf\" }\n"
            "}\n"
        ))
        allowlist = _empty_allowlist(root)
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 1


# ── 6. Malformed / missing allowlist -> exit 2 ──────────────────────────────────

class TestAllowlistFailClosed:
    def test_missing_allowlist_exit_2(self, tmp_path):
        root = tmp_path
        _write_kt(root, "A.kt", "package com.example\nclass A { fun f() = 1 }\n")
        result = _run_guard(root, allowlist=root / "config" / "guards" / "missing.yml",
                            fail_on_violation=True)
        assert result.returncode == 2
        assert "FATAL" in result.stderr

    def test_malformed_allowlist_exit_2(self, tmp_path):
        root = tmp_path
        _write_kt(root, "A.kt", "package com.example\nclass A { fun f() = 1 }\n")
        p = root / "bad.yml"
        p.write_text("{this is not valid yaml: [}", encoding="utf-8")
        result = _run_guard(root, allowlist=p, fail_on_violation=True)
        assert result.returncode == 2

    def test_empty_allowlist_exit_2(self, tmp_path):
        root = tmp_path
        _write_kt(root, "A.kt", "package com.example\nclass A { fun f() = 1 }\n")
        p = root / "empty.yml"
        p.write_text("", encoding="utf-8")
        result = _run_guard(root, allowlist=p, fail_on_violation=True)
        assert result.returncode == 2

    def test_wildcard_allowlist_exit_2(self, tmp_path):
        root = tmp_path
        _write_kt(root, "A.kt", "package com.example\nclass A { fun f() = 1 }\n")
        allowlist = _sample_allowlist(root, overrides={
            "path": "app/src/main/java/**",
            "class": "A",
            "method": "f",
            "api": "Instant.now",
        })
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 2
        assert "wildcard" in result.stderr.lower()

    def test_missing_required_field_exit_2(self, tmp_path):
        root = tmp_path
        _write_kt(root, "A.kt", "package com.example\nclass A { fun f() = 1 }\n")
        allowlist = _sample_allowlist(root, overrides={"owner": ""})
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 2


# ── 7. Empty / missing source tree -> exit 2 ────────────────────────────────────

class TestSourceTreeFailClosed:
    def test_missing_source_tree_exit_2(self, tmp_path):
        root = tmp_path
        allowlist = _empty_allowlist(root)
        _write_manifest(root)
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 2
        assert "FATAL" in result.stderr

    def test_empty_source_tree_exit_2(self, tmp_path):
        root = tmp_path
        (root / "app" / "src" / "main" / "java").mkdir(parents=True)
        allowlist = _empty_allowlist(root)
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 2
        assert "FATAL" in result.stderr

    def test_missing_manifest_exit_2(self, tmp_path):
        """PR-GR-10B: no checked-in production source-root manifest -> exit 2
        (no conventional-root fallback)."""
        root = tmp_path
        # The declared root exists with a clean source file, so manifest
        # absence is the ONLY possible failure cause.
        _write_kt(root, "Example.kt", "package com.example\nclass A { fun f() = 1 }\n")
        allowlist = _empty_allowlist(root)
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True,
                            write_manifest=False)
        assert result.returncode == 2
        assert "production source scope unresolved" in result.stderr

    def test_undeclared_root_file_is_never_scanned(self, tmp_path):
        """A wall-clock violation under an UNDECLARED tree is invisible: the
        declared production roots own the scan scope."""
        root = tmp_path
        allowlist = _empty_allowlist(root)
        _write_manifest(root)
        # Declared root must exist (and stay clean) so the scope resolves.
        _write_kt(root, "Clean.kt", "package com.example\nclass Clean { fun f() = 1 }\n")
        undeclared = root / "other" / "src" / "main" / "java" / "Rogue.kt"
        undeclared.parent.mkdir(parents=True)
        undeclared.write_text(
            "package other\n"
            "class Rogue {\n"
            "    fun now() = System.currentTimeMillis()\n"
            "}\n",
            encoding="utf-8",
        )
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 0, result.stdout + result.stderr
        assert "Rogue" not in result.stdout


# ── 8. Stale exception -> exit 2 ────────────────────────────────────────────────

class TestStaleException:
    def test_stale_exception_exit_2(self, tmp_path):
        root = tmp_path
        _write_kt(root, "A.kt", "package com.example\nclass A { fun f() = 1 }\n")
        allowlist = _sample_allowlist(root, overrides={
            "path": "app/src/main/java/com/example/SystemTimeProvider.kt",
            "class": "SystemTimeProvider",
            "method": "now",
            "api": "System.currentTimeMillis",
        })
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 2
        assert "stale" in result.stderr.lower()


# ── 9. Deterministic output ─────────────────────────────────────────────────────

class TestDeterminism:
    def test_output_is_deterministic_across_runs(self, tmp_path):
        root = tmp_path
        _write_kt(root, "B.kt", (
            "package com.example\n\n"
            "class B {\n"
            "    fun one() { val x = LocalDate.now() }\n"
            "    fun two() { val y = Instant.now(); val z = System.currentTimeMillis() }\n"
            "}\n"
        ))
        allowlist = _empty_allowlist(root)
        r1 = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        r2 = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert r1.stdout == r2.stdout
        assert r1.returncode == r2.returncode == 1

    def test_deterministic_fingerprint_lines(self, tmp_path):
        root = tmp_path
        _write_kt(root, "B.kt", (
            "package com.example\n\n"
            "class B {\n"
            "    fun one() { val x = LocalDate.now() }\n"
            "    fun two() { val y = Instant.now(); val z = System.currentTimeMillis() }\n"
            "}\n"
        ))
        allowlist = _empty_allowlist(root)
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        lines = [ln for ln in result.stdout.splitlines() if ln.startswith(RULE_ID)]
        assert lines[0].startswith(f"{RULE_ID} app/src/main/java/B.kt:4 B.one ")
        assert "LocalDate.now" in lines[0]
        line5 = [ln for ln in lines if ":5 " in ln]
        # Two violations on line 5, sorted by api (Instant.now before System.currentTimeMillis)
        assert len(line5) == 2
        assert line5[0].startswith(f"{RULE_ID} app/src/main/java/B.kt:5 B.two ")
        assert "Instant.now" in line5[0]
        assert line5[1].startswith(f"{RULE_ID} app/src/main/java/B.kt:5 B.two ")
        assert "System.currentTimeMillis" in line5[1]

    def test_scan_summary_line_deterministic(self, tmp_path):
        root = tmp_path
        _write_kt(root, "A.kt", "package com.example\nclass A { fun f() = 1 }\n")
        allowlist = _empty_allowlist(root)
        r1 = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert r1.returncode == 0
        assert f"{RULE_ID} SCAN root=" in r1.stdout
        assert "violations=0" in r1.stdout
        assert f"{RULE_ID} PASS" in r1.stdout


# ── 10. Report-only mode (no --fail-on-violation) ───────────────────────────────

class TestReportOnlyMode:
    def test_violations_without_flag_exit_0(self, tmp_path):
        root = tmp_path
        _write_kt(root, "A.kt", (
            "package com.example\n"
            "class A { fun f() { val t = Instant.now() } }\n"
        ))
        allowlist = _empty_allowlist(root)
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=False)
        assert result.returncode == 0
        assert "WARNING" in result.stderr

    def test_violations_with_flag_exit_1(self, tmp_path):
        root = tmp_path
        _write_kt(root, "A.kt", (
            "package com.example\n"
            "class A { fun f() { val t = Instant.now() } }\n"
        ))
        allowlist = _empty_allowlist(root)
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 1
        assert "FAIL" in result.stderr


# ── 13. Multiline expression-body attribution fails closed ─────────────────────

class TestMultilineExpressionBodyAttribution:
    def test_unprovable_expression_body_uses_ambiguous_marker(self, tmp_path):
        """API occurrences inside a multiline expression-bodied method whose
        boundary cannot be proven are attributed to the controlled ambiguous
        marker — never the previous method name and never an empty '<init>'."""
        root = tmp_path
        src = _write_kt(root, "com/example/AllowedThenOther.kt", (
            "package com.example\n\n"
            "class AllowedThenOther {\n"
            "    fun allowed() = compute(\n"
            "        Instant.now()\n"
            "    )\n"
            "}\n"
        ))
        violations = scan_content(
            src.read_text(encoding="utf-8"),
            src.relative_to(root).as_posix(),
        )
        v = next(v for v in violations if v.api == "Instant.now")
        assert v.method_name == "<expression-body>", v
        assert v.symbol == "AllowedThenOther.<expression-body>", v
        assert v.method_name != "allowed", v
        assert v.method_name != "", v

    def test_allowed_method_then_later_method_both_reported(self, tmp_path):
        """Regression fixture: an expression-bodied 'allowed' method followed
        by another method/API occurrence. Both occurrences are reported with
        the correct attribution — the earlier one as the ambiguous marker, the
        later one as the later method. Nothing is dropped or carried over."""
        root = tmp_path
        _write_kt(root, "com/example/AllowedThenOther.kt", (
            "package com.example\n\n"
            "class AllowedThenOther {\n"
            "    fun allowed() = compute(\n"
            "        Instant.now()\n"
            "    )\n\n"
            "    fun other() {\n"
            "        val t = System.currentTimeMillis()\n"
            "    }\n"
            "}\n"
        ))
        allowlist = _empty_allowlist(root)
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 1, f"stdout={result.stdout}\nstderr={result.stderr}"
        assert "AllowedThenOther.<expression-body>" in result.stdout
        assert "AllowedThenOther.other" in result.stdout
        assert ":5 " in result.stdout
        assert ":9 " in result.stdout
        # The marker line must never be attributed to the previous method name.
        assert "AllowedThenOther.allowed " not in result.stdout

    def test_allowed_exception_cannot_suppress_later_or_ambiguous(self, tmp_path):
        """An exact exception for method A must not suppress an API occurrence
        belonging to a later method or an ambiguous scope. The multiline
        expression-bodied method's occurrence is attributed to the marker, so
        the exception for 'allowed' has no matching evidence and the guard
        fails closed with a stale entry (exit 2) instead of suppressing."""
        root = tmp_path
        _write_kt(root, "com/example/AllowedThenOther.kt", (
            "package com.example\n\n"
            "class AllowedThenOther {\n"
            "    fun allowed() = compute(\n"
            "        Instant.now()\n"
            "    )\n\n"
            "    fun other() {\n"
            "        val t = System.currentTimeMillis()\n"
            "    }\n"
            "}\n"
        ))
        allowlist = _sample_allowlist(root, overrides={
            "path": "app/src/main/java/com/example/AllowedThenOther.kt",
            "class": "AllowedThenOther",
            "method": "allowed",
            "api": "Instant.now",
            "reason": "Test exception targeting the multiline expression-bodied method",
        })
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 2
        assert "stale" in result.stderr.lower()
        assert "method=allowed" in result.stderr

    def test_reserved_marker_never_authorizable_exit_2(self, tmp_path):
        """An exact exception can never name the reserved ambiguous marker —
        the policy loader rejects it (exit 2), so an unproven expression-body
        scope can never be authorized."""
        root = tmp_path
        _write_kt(root, "A.kt", "package com.example\nclass A { fun f() = 1 }\n")
        allowlist = _sample_allowlist(root, overrides={"method": "<expression-body>"})
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 2
        assert "reserved ambiguous attribution" in result.stderr.lower()

    def test_provable_single_line_expression_body_keeps_real_name(self, tmp_path):
        """A single-line expression body whose boundary IS provable keeps the
        real method name, so an exact exception still matches (PASS)."""
        root = tmp_path
        src = _write_kt(root, "com/example/SingleLine.kt", (
            "package com.example\n\n"
            "class SingleLine {\n"
            "    fun now(): Long = System.currentTimeMillis()\n"
            "}\n"
        ))
        violations = scan_content(
            src.read_text(encoding="utf-8"),
            src.relative_to(root).as_posix(),
        )
        v = next(v for v in violations if v.api == "System.currentTimeMillis")
        assert v.method_name == "now", v
        assert v.symbol == "SingleLine.now", v
        allowlist = _sample_allowlist(root, overrides={
            "path": "app/src/main/java/com/example/SingleLine.kt",
            "class": "SingleLine",
            "method": "now",
            "api": "System.currentTimeMillis",
        })
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 0, f"stdout={result.stdout}\nstderr={result.stderr}"
        assert "PASS" in result.stdout


# ── 15. GR-02 attribution coverage ──────────────────────────────────────────────

class TestMultilineInjectHeaderAttribution:
    """A multiline ``@Inject constructor(...)`` class header with a wrapped
    interface list keeps the class pending until the body brace opens, so
    members are attributed to the real class and method."""

    def test_multiline_inject_header_attributes_to_class_method(self, tmp_path):
        root = tmp_path
        src = _write_kt(root, "com/example/LedgerSnapshotBuilder.kt", (
            "package com.example\n\n"
            "class LedgerSnapshotBuilder @Inject constructor(\n"
            "    private val ledgerDao: LedgerDao,\n"
            "    private val accountDao: AccountDao,\n"
            ") : SnapshotBuilder,\n"
            "    AccountScopeAware,\n"
            "    LedgerScopeAware {\n\n"
            "    fun buildSnapshot() {\n"
            "        val startedAtNs = System.nanoTime()\n"
            "        val elapsedNs = System.nanoTime() - startedAtNs\n"
            "        check(elapsedNs >= 0)\n"
            "    }\n"
            "}\n"
        ))
        violations = scan_content(
            src.read_text(encoding="utf-8"),
            src.relative_to(root).as_posix(),
        )
        nano = [v for v in violations if v.api == "System.nanoTime"]
        assert len(nano) == 2, violations
        for v in nano:
            assert v.class_name == "LedgerSnapshotBuilder", v
            assert v.method_name == "buildSnapshot", v
            assert v.symbol == "LedgerSnapshotBuilder.buildSnapshot", v
        # Every occurrence in this file must carry the real class/method
        # attribution — never a pending-class or <file>.<top> fallback.
        assert all(v.symbol == "LedgerSnapshotBuilder.buildSnapshot" for v in violations), violations

    def test_multiline_inject_header_exact_exception_passes(self, tmp_path):
        """An exact exception for the attributed Class.method suppresses the
        multiline-header occurrences (PASS)."""
        root = tmp_path
        _write_kt(root, "com/example/LedgerSnapshotBuilder.kt", (
            "package com.example\n\n"
            "class LedgerSnapshotBuilder @Inject constructor(\n"
            "    private val ledgerDao: LedgerDao,\n"
            "    private val accountDao: AccountDao,\n"
            ") : SnapshotBuilder,\n"
            "    AccountScopeAware,\n"
            "    LedgerScopeAware {\n\n"
            "    fun buildSnapshot() {\n"
            "        val startedAtNs = System.nanoTime()\n"
            "        val elapsedNs = System.nanoTime() - startedAtNs\n"
            "        check(elapsedNs >= 0)\n"
            "    }\n"
            "}\n"
        ))
        allowlist = _sample_allowlist(root, overrides={
            "path": "app/src/main/java/com/example/LedgerSnapshotBuilder.kt",
            "class": "LedgerSnapshotBuilder",
            "method": "buildSnapshot",
            "api": "System.nanoTime",
            "reason": "Monotonic elapsed-duration measurement",
        })
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 0, f"stdout={result.stdout}\nstderr={result.stderr}"
        assert "PASS" in result.stdout


class TestLocalHelperAttribution:
    """A function declared inside another function body (a local helper) does
    not create its own attribution scope — its time usage belongs to the
    owning method."""

    def test_local_helper_time_usage_attributed_to_owning_method(self, tmp_path):
        root = tmp_path
        src = _write_kt(root, "com/example/SettlementCalculator.kt", (
            "package com.example\n\n"
            "class SettlementCalculator {\n"
            "    fun findMinimalTransferPlan() {\n"
            "        fun exceedsSolverBudget() {\n"
            "            val startedAtNs = System.nanoTime()\n"
            "            val elapsedNs = System.nanoTime() - startedAtNs\n"
            "            check(elapsedNs >= 0)\n"
            "        }\n"
            "        exceedsSolverBudget()\n"
            "    }\n"
            "}\n"
        ))
        violations = scan_content(
            src.read_text(encoding="utf-8"),
            src.relative_to(root).as_posix(),
        )
        nano = [v for v in violations if v.api == "System.nanoTime"]
        assert len(nano) == 2, violations
        for v in nano:
            assert v.class_name == "SettlementCalculator", v
            assert v.method_name == "findMinimalTransferPlan", v
            assert v.symbol == "SettlementCalculator.findMinimalTransferPlan", v
        # The local helper must never become its own attribution scope.
        assert all(v.method_name != "exceedsSolverBudget" for v in violations), violations

    def test_local_helper_covered_by_owning_method_exception(self, tmp_path):
        """An exact exception for the owning method covers time usage inside
        its local helper (PASS)."""
        root = tmp_path
        _write_kt(root, "com/example/SettlementCalculator.kt", (
            "package com.example\n\n"
            "class SettlementCalculator {\n"
            "    fun findMinimalTransferPlan() {\n"
            "        fun exceedsSolverBudget() {\n"
            "            val startedAtNs = System.nanoTime()\n"
            "            val elapsedNs = System.nanoTime() - startedAtNs\n"
            "            check(elapsedNs >= 0)\n"
            "        }\n"
            "        exceedsSolverBudget()\n"
            "    }\n"
            "}\n"
        ))
        allowlist = _sample_allowlist(root, overrides={
            "path": "app/src/main/java/com/example/SettlementCalculator.kt",
            "class": "SettlementCalculator",
            "method": "findMinimalTransferPlan",
            "api": "System.nanoTime",
            "reason": "Monotonic elapsed-duration measurement",
        })
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 0, f"stdout={result.stdout}\nstderr={result.stderr}"
        assert "PASS" in result.stdout


class TestNestedClassScopeRestoration:
    """A nested (local) class snapshots the enclosing method attribution when
    its body opens; when the nested class closes, the snapshot restores the
    owning outer method for the code that follows."""

    def test_attribution_restored_after_nested_class_closes(self, tmp_path):
        root = tmp_path
        src = _write_kt(root, "com/example/Outer.kt", (
            "package com.example\n\n"
            "class Outer {\n"
            "    fun outerMethod() {\n"
            "        val t1 = System.currentTimeMillis()\n\n"
            "        class Nested {\n"
            "            fun inner() {\n"
            "                val t2 = System.nanoTime()\n"
            "            }\n"
            "        }\n\n"
            "        val t3 = LocalDate.now()\n"
            "    }\n"
            "}\n"
        ))
        violations = scan_content(
            src.read_text(encoding="utf-8"),
            src.relative_to(root).as_posix(),
        )
        by_api = {v.api: v for v in violations}
        # Inside the nested class the attribution is the nested scope.
        assert by_api["System.currentTimeMillis"].symbol == "Outer.outerMethod", by_api
        assert by_api["System.nanoTime"].symbol == "Nested.inner", by_api
        # After the nested class closes, the outer method attribution is
        # restored (the class snapshot carries the method stack).
        assert by_api["LocalDate.now"].symbol == "Outer.outerMethod", by_api


class TestBodylessDeclarationDoesNotAbsorbAttribution:
    """Body-less ``data``/``sealed`` declarations never open a body, so they
    never create an attribution scope that could absorb a following
    class/method's attribution."""

    def test_bodyless_declarations_do_not_absorb_following_attribution(self, tmp_path):
        root = tmp_path
        src = _write_kt(root, "com/example/LedgerService.kt", (
            "package com.example\n\n"
            "data class LedgerRequest(\n"
            "    val id: String,\n"
            "    val amount: Long,\n"
            ")\n\n"
            "sealed interface LedgerResult\n\n"
            "data class LedgerSuccess(\n"
            "    val id: String,\n"
            ") : LedgerResult\n\n"
            "class LedgerService {\n"
            "    fun reconcile() {\n"
            "        val startedAtNs = System.nanoTime()\n"
            "        val elapsedNs = System.nanoTime() - startedAtNs\n"
            "        check(elapsedNs >= 0)\n"
            "    }\n"
            "}\n"
        ))
        violations = scan_content(
            src.read_text(encoding="utf-8"),
            src.relative_to(root).as_posix(),
        )
        assert len(violations) == 2, violations
        for v in violations:
            assert v.class_name == "LedgerService", v
            assert v.method_name == "reconcile", v
            assert v.symbol == "LedgerService.reconcile", v
        # None of the body-less declarations may own the following attribution.
        assert all(
            v.class_name not in ("LedgerRequest", "LedgerResult", "LedgerSuccess")
            for v in violations
        ), violations


# ── 16. AppDatabase migration exceptions are exact ──────────────────────────────

APP_DATABASE_MIGRATION_FIXTURE = (
    "package com.example\n\n"
    "class AppDatabase {\n"
    "    companion object {\n"
    "        object MIGRATION_16_17 : Migration(16, 17) {\n"
    "            override fun migrate(database: SupportSQLiteDatabase) {\n"
    "                val now = System.currentTimeMillis()\n"
    "                database.execSQL(\"INSERT INTO merchant_canonicals (createdAt) VALUES ($now)\")\n"
    "            }\n"
    "        }\n"
    "        object MIGRATION_41_42 : Migration(41, 42) {\n"
    "            override fun migrate(database: SupportSQLiteDatabase) {\n"
    "                val now = System.currentTimeMillis()\n"
    "                database.execSQL(\"INSERT INTO exchange_rates (lastUpdated) VALUES ($now)\")\n"
    "            }\n"
    "        }\n"
    "    }\n"
    "}\n"
)


def _app_database_allowlist(root: Path, entries: list) -> Path:
    """Write an allowlist whose exceptions list exactly mirrors the
    (class, method, api) tuples passed in ``entries``."""
    body = "version: 1\nexceptions:\n"
    for entry in entries:
        d = {
            "path": "app/src/main/java/com/example/AppDatabase.kt",
            "class": entry[0],
            "method": entry[1],
            "api": entry[2],
            "reason": "Test exception",
            "owner": "@tester",
            "linked_issue": "MIT-003",
        }
        body += "  - " + _yaml_inline(d) + "\n"
    p = root / "config" / "guards" / "time_boundary_exceptions.yml"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(body, encoding="utf-8")
    return p


class TestAppDatabaseMigrationAttribution:
    """Named-object Room migrations are attributed to their own class so the
    policy can authorize each migration lambda with its own exact entry
    instead of one broad AppDatabase.Companion/migrate bucket."""

    def test_named_object_migrations_attributed_to_own_class(self, tmp_path):
        root = tmp_path
        src = _write_kt(root, "com/example/AppDatabase.kt", APP_DATABASE_MIGRATION_FIXTURE)
        violations = scan_content(
            src.read_text(encoding="utf-8"),
            src.relative_to(root).as_posix(),
        )
        time_violations = [v for v in violations if v.api == "System.currentTimeMillis"]
        assert len(time_violations) == 2, time_violations
        symbols = sorted(v.symbol for v in time_violations)
        assert symbols == [
            "MIGRATION_16_17.migrate",
            "MIGRATION_41_42.migrate",
        ], time_violations
        # No call may be attributed to the broad AppDatabase.Companion bucket.
        assert all("AppDatabase.Companion" not in v.symbol for v in time_violations), time_violations

    def test_broad_companion_migrate_entry_is_stale_exit_2(self, tmp_path):
        """The old one-entry-fits-all `AppDatabase.Companion.migrate` entry
        has no matching source evidence once migrations are named objects —
        the guard fails closed (exit 2, stale exception) instead of masking
        any future unreviewed migration lambda."""
        root = tmp_path
        _write_kt(root, "com/example/AppDatabase.kt", APP_DATABASE_MIGRATION_FIXTURE)
        allowlist = _app_database_allowlist(root, [
            ("AppDatabase.Companion", "migrate", "System.currentTimeMillis"),
        ])
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 2, f"stdout={result.stdout}\nstderr={result.stderr}"
        assert "stale" in result.stderr.lower()
        assert "class=AppDatabase.Companion" in result.stderr

    def test_two_separate_exact_entries_pass(self, tmp_path):
        """One exact entry per migration lambda (MIGRATION_16_17.migrate and
        MIGRATION_41_42.migrate) suppresses both calls and passes."""
        root = tmp_path
        _write_kt(root, "com/example/AppDatabase.kt", APP_DATABASE_MIGRATION_FIXTURE)
        allowlist = _app_database_allowlist(root, [
            ("MIGRATION_16_17", "migrate", "System.currentTimeMillis"),
            ("MIGRATION_41_42", "migrate", "System.currentTimeMillis"),
        ])
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 0, f"stdout={result.stdout}\nstderr={result.stderr}"
        assert "PASS" in result.stdout

    def test_partial_entry_still_reports_uncovered_migration(self, tmp_path):
        """Authorizing only one migration lambda still reports the other as a
        violation (exit 1) — entries never bleed across migrations."""
        root = tmp_path
        _write_kt(root, "com/example/AppDatabase.kt", APP_DATABASE_MIGRATION_FIXTURE)
        allowlist = _app_database_allowlist(root, [
            ("MIGRATION_16_17", "migrate", "System.currentTimeMillis"),
        ])
        result = _run_guard(root, allowlist=allowlist, fail_on_violation=True)
        assert result.returncode == 1, f"stdout={result.stdout}\nstderr={result.stderr}"
        assert "MIGRATION_41_42.migrate" in result.stdout
