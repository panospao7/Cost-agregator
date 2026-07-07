"""
test_verify_ignored_test_budget.py
PR 8 acceptance tests for the Ignored Test Budget guard.

Run with: python -m pytest scripts/test_verify_ignored_test_budget.py -v
"""
import os
import sys
import tempfile
import textwrap
from pathlib import Path

import pytest

# Import the module under test
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import verify_ignored_test_budget as guard


# ── Helpers ─────────────────────────────────────────────────────────────────

def _write_test_file(
    tmp_path: Path,
    rel_path: str,
    content: str,
) -> Path:
    """Write a Kotlin test file in the tmp_path tree and return its full path."""
    full = tmp_path / rel_path
    full.parent.mkdir(parents=True, exist_ok=True)
    full.write_text(content, encoding="utf-8")
    return full


def _make_denylist(tmp_path: Path, yaml_content: str) -> Path:
    """Write a denylist YAML and return its path."""
    p = tmp_path / "config" / "release_block_denylist.yml"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(yaml_content, encoding="utf-8")
    return p


# ── Test: all ignores have reasons ──────────────────────────────────────────

def test_all_ignores_have_reasons(tmp_path):
    """All @Ignore annotations in fixture files have non-empty reason strings."""
    _write_test_file(
        tmp_path,
        "app/src/test/java/com/example/GoodTest.kt",
        textwrap.dedent("""\
            package com.example

            import org.junit.Ignore
            import org.junit.Test

            class GoodTest {
                @Ignore("Stress test: may hang in CI, run manually")
                @Test
                fun slowButDocumented() {}

                @Ignore("Not supported on desktop JVM")
                @Test
                fun jvmOnly() {}
            }
        """),
    )

    results = guard.scan_ignored_tests(tmp_path)
    assert len(results) == 2, f"Expected 2 ignored tests, got {len(results)}"

    for filepath, line, reason, category in results:
        assert reason != "", \
            f"@Ignore at {filepath}:{line} has empty reason"
        assert category != "missing_reason", \
            f"@Ignore at {filepath}:{line} categorized as missing_reason: {reason!r}"


# ── Test: missing reason is detected ────────────────────────────────────────

def test_missing_reason_fails(tmp_path):
    """An @Ignore annotation without a reason string is detected."""
    _write_test_file(
        tmp_path,
        "app/src/test/java/com/example/BadTest.kt",
        textwrap.dedent("""\
            package com.example

            import org.junit.Ignore
            import org.junit.Test

            class BadTest {
                @Ignore
                @Test
                fun noReason() {}
            }
        """),
    )

    results = guard.scan_ignored_tests(tmp_path)
    assert len(results) == 1, f"Expected 1 ignored test, got {len(results)}"

    filepath, line, reason, category = results[0]
    assert reason == "", f"Expected empty reason, got {reason!r}"
    assert category == "missing_reason", \
        f"Expected missing_reason category, got {category}"


# ── Test: release-block violation detected ──────────────────────────────────

def test_release_block_violation_fails(tmp_path, monkeypatch):
    """An @Ignore in a denylist class triggers a violation."""
    _write_test_file(
        tmp_path,
        "app/src/test/java/com/example/MoneyTest.kt",
        textwrap.dedent("""\
            package com.example

            import org.junit.Ignore
            import org.junit.Test

            class MoneyTest {
                @Ignore("Outdated — needs rewrite")
                @Test
                fun precisionTest() {}
            }
        """),
    )

    _make_denylist(
        tmp_path,
        textwrap.dedent("""\
            release_block_tests:
              - class: MoneyTest
                reason: "Critical: money/currency math correctness"
        """),
    )

    # Scan
    results = guard.scan_ignored_tests(tmp_path)
    assert len(results) == 1, f"Expected 1 ignored test, got {len(results)}"

    # Load denylist
    denylist_path = tmp_path / "config" / "release_block_denylist.yml"
    denylist = guard.load_release_denylist(denylist_path)
    assert len(denylist) == 1, f"Expected 1 denylist entry, got {len(denylist)}"
    assert denylist[0]["class"] == "MoneyTest"

    # Check denylist
    violations = guard.check_denylist(results, denylist)
    assert len(violations) == 1, \
        f"Expected 1 denylist violation, got {len(violations)}"
    assert "MoneyTest" in violations[0], \
        f"Violation should mention MoneyTest: {violations[0]}"
    assert "RELEASE-BLOCK VIOLATION" in violations[0], \
        f"Violation should be tagged RELEASE-BLOCK: {violations[0]}"


# ── Test: categorization works ──────────────────────────────────────────────

def test_categorization_works(tmp_path):
    """Ignored tests are correctly categorized by reason keywords."""
    test_cases = [
        ("stress", 'Stress test: may hang in CI, run manually'),
        ("jvm_incompatible", "AndroidKeyStore not available on desktop JVM"),
        ("removed_api", "Tests reference removed APIs that no longer exist"),
        ("vat_logic", "VAT calculation logic differs from test expectation"),
        ("truth_boxing", "Truth assertThat incompatible with Kotlin value class boxing"),
        ("negative_id", "Negative IDs are unsupported for receipt notification mapping"),
        ("rewrite_needed", "Needs rewrite when domain model stabilizes"),
        ("other", "Performance test: runs too slow for PR CI"),
    ]

    for expected_category, reason in test_cases:
        actual = guard.categorize_reason(reason)
        assert actual == expected_category, \
            f"Expected category '{expected_category}' for reason '{reason}', got '{actual}'"


# ── Test: --fail-on-violation exit code ─────────────────────────────────────

def test_fail_on_violation_exit_code(tmp_path, monkeypatch):
    """With --fail-on-violation, missing reasons cause exit code 1."""
    _write_test_file(
        tmp_path,
        "app/src/test/java/com/example/MissingReasonTest.kt",
        textwrap.dedent("""\
            package com.example

            import org.junit.Ignore
            import org.junit.Test

            class MissingReasonTest {
                @Ignore
                @Test
                fun undocumented() {}
            }
        """),
    )

    # Simulate CLI args with --fail-on-violation
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "verify_ignored_test_budget.py",
            "--root",
            str(tmp_path),
            "--fail-on-violation",
        ],
    )

    with pytest.raises(SystemExit) as exc_info:
        guard.main()
    assert exc_info.value.code == 1, \
        f"Expected exit code 1 with --fail-on-violation, got {exc_info.value.code}"


def test_warning_mode_exits_zero(tmp_path, monkeypatch):
    """Without --fail-on-violation, missing reasons exit 0 (warning mode)."""
    _write_test_file(
        tmp_path,
        "app/src/test/java/com/example/MissingReasonTest.kt",
        textwrap.dedent("""\
            package com.example

            import org.junit.Ignore
            import org.junit.Test

            class MissingReasonTest {
                @Ignore
                @Test
                fun undocumented() {}
            }
        """),
    )

    # Simulate CLI args WITHOUT --fail-on-violation
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "verify_ignored_test_budget.py",
            "--root",
            str(tmp_path),
        ],
    )

    with pytest.raises(SystemExit) as exc_info:
        guard.main()
    assert exc_info.value.code == 0, \
        f"Expected exit code 0 in warning mode, got {exc_info.value.code}"


# ── Test: commented-out @Ignore is skipped ──────────────────────────────────

def test_commented_ignore_is_skipped(tmp_path):
    """Commented-out @Ignore lines (// @Ignore) are not counted."""
    _write_test_file(
        tmp_path,
        "app/src/test/java/com/example/CommentedTest.kt",
        textwrap.dedent("""\
            package com.example

            import org.junit.Test

            class CommentedTest {
                // @Ignore("was flaky, now fixed")
                @Test
                fun nowActive() {}

                @Ignore("Valid ignore")
                @Test
                fun validIgnore() {}
            }
        """),
    )

    results = guard.scan_ignored_tests(tmp_path)
    assert len(results) == 1, \
        f"Commented-out @Ignore should not be counted. Got {len(results)}: {results}"

    filepath, line, reason, category = results[0]
    assert reason == "Valid ignore", \
        f"Expected 'Valid ignore', got {reason!r}"


# ── Test: edge case — @Ignore(\"value: empty string\") ────────────────────────


def test_empty_string_reason_is_detected(tmp_path):
    """@Ignore(\"\") with empty reason string is still categorized as missing."""
    _write_test_file(
        tmp_path,
        "app/src/test/java/com/example/EmptyReasonTest.kt",
        textwrap.dedent("""\
            package com.example

            import org.junit.Ignore
            import org.junit.Test

            class EmptyReasonTest {
                @Ignore("")
                @Test
                fun emptyReason() {}
            }
        """),
    )

    results = guard.scan_ignored_tests(tmp_path)
    assert len(results) == 1, f"Expected 1 result, got {len(results)}"

    filepath, line, reason, category = results[0]
    assert reason == "", f"Expected empty string reason, got {reason!r}"
    assert category == "missing_reason", \
        f"Expected missing_reason for empty string, got {category}"
