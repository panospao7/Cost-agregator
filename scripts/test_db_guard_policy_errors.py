"""Contract tests for the closed DB policy error-code set and the shared
approved-source-root contract (``scripts/db_guard/source_roots.py``)."""

from __future__ import annotations

import dataclasses

import pytest

from scripts.db_guard.policy_errors import (
    KNOWN_POLICY_ERROR_CODES,
    POLICY_ERROR_ALLOWLIST_VIOLATION,
    POLICY_ERROR_FORBIDDEN_DAO_WRITE,
    POLICY_ERROR_MISSING_LIFECYCLE_COORDINATOR,
    POLICY_ERROR_PATH_ABSOLUTE,
    POLICY_ERROR_PATH_BACKSLASH,
    POLICY_ERROR_PATH_BAD_SEGMENT,
    POLICY_ERROR_PATH_BARE_BASENAME,
    POLICY_ERROR_PATH_DOT_PREFIX,
    POLICY_ERROR_PATH_EMPTY,
    POLICY_ERROR_PATH_NOT_KOTLIN,
    POLICY_ERROR_PATH_NOT_STRING,
    POLICY_ERROR_PATH_OUTSIDE_APPROVED_ROOT,
    POLICY_ERROR_RAW_QUERY_MUTATION,
    POLICY_ERROR_SCHEMA_MISMATCH,
    PolicyError,
)
from scripts.db_guard.source_roots import (
    APPROVED_PRODUCTION_SOURCE_ROOTS,
    approved_root_error,
    is_approved_source_path,
)

CANONICAL_PATH = (
    "app/src/main/java/com/yourname/expensetracker/data/database/AppDatabase.kt"
)


# ── Closed error-code set ─────────────────────────────────────────────────────

def test_known_codes_are_upper_snake_constants():
    assert KNOWN_POLICY_ERROR_CODES
    for code in KNOWN_POLICY_ERROR_CODES:
        assert isinstance(code, str)
        assert code == code.upper()
        assert code.startswith("POLICY_ERROR_")
        assert all(ch.isupper() or ch.isdigit() or ch == "_" for ch in code)


@pytest.mark.parametrize("code", [
    # Scan findings.
    POLICY_ERROR_FORBIDDEN_DAO_WRITE,
    POLICY_ERROR_RAW_QUERY_MUTATION,
    POLICY_ERROR_MISSING_LIFECYCLE_COORDINATOR,
    POLICY_ERROR_ALLOWLIST_VIOLATION,
    POLICY_ERROR_SCHEMA_MISMATCH,
    # Path canonicalization.
    POLICY_ERROR_PATH_NOT_STRING,
    POLICY_ERROR_PATH_EMPTY,
    POLICY_ERROR_PATH_BACKSLASH,
    POLICY_ERROR_PATH_ABSOLUTE,
    POLICY_ERROR_PATH_DOT_PREFIX,
    POLICY_ERROR_PATH_BAD_SEGMENT,
    POLICY_ERROR_PATH_BARE_BASENAME,
    POLICY_ERROR_PATH_NOT_KOTLIN,
    POLICY_ERROR_PATH_OUTSIDE_APPROVED_ROOT,
])
def test_policy_vocabulary_is_in_the_closed_set(code):
    assert code in KNOWN_POLICY_ERROR_CODES


# ── PolicyError fail-closed construction ──────────────────────────────────────

def test_policy_error_accepts_known_code_with_bounded_context():
    err = PolicyError(POLICY_ERROR_PATH_OUTSIDE_APPROVED_ROOT, {"field": "path"})
    assert err.code == POLICY_ERROR_PATH_OUTSIDE_APPROVED_ROOT
    assert err.context == {"field": "path"}


def test_policy_error_rejects_unknown_code():
    with pytest.raises(ValueError):
        PolicyError("POLICY_ERROR_TOTALLY_MADE_UP")


def test_policy_error_is_frozen():
    err = PolicyError(POLICY_ERROR_PATH_EMPTY)
    with pytest.raises(dataclasses.FrozenInstanceError):
        err.code = POLICY_ERROR_PATH_EMPTY  # type: ignore[misc]


def test_policy_error_requires_dict_context():
    with pytest.raises(TypeError):
        PolicyError(POLICY_ERROR_PATH_EMPTY, context=("not", "a", "dict"))


# ── Shared root contract ──────────────────────────────────────────────────────

def test_approved_roots_constant_pins_the_documented_root():
    assert APPROVED_PRODUCTION_SOURCE_ROOTS == ("app/src/main/java",)


@pytest.mark.parametrize("path", [
    CANONICAL_PATH,
    "app/src/main/java/GroupTransactionCoordinator.kt",
    # Legacy validator strips surrounding whitespace before validating.
    f"  {CANONICAL_PATH}  ",
])
def test_canonical_paths_under_approved_root_are_approved(path):
    assert approved_root_error(path) is None
    assert is_approved_source_path(path) is True


@pytest.mark.parametrize("path,expected_code", [
    # Non-string input fails closed.
    (None, POLICY_ERROR_PATH_NOT_STRING),
    (123, POLICY_ERROR_PATH_NOT_STRING),
    (b"app/src/main/java/X.kt", POLICY_ERROR_PATH_NOT_STRING),
    # Empty / whitespace-only.
    ("", POLICY_ERROR_PATH_EMPTY),
    ("   ", POLICY_ERROR_PATH_EMPTY),
    # Backslash separators (checked before absolute forms, like legacy).
    ("app\\src\\main\\java\\X.kt", POLICY_ERROR_PATH_BACKSLASH),
    ("\\\\server/share/X.kt", POLICY_ERROR_PATH_BACKSLASH),
    # Absolute paths.
    ("/app/src/main/java/X.kt", POLICY_ERROR_PATH_ABSOLUTE),
    ("C:/app/src/main/java/X.kt", POLICY_ERROR_PATH_ABSOLUTE),
    # './' prefix and bare '.'.
    ("./X.kt", POLICY_ERROR_PATH_DOT_PREFIX),
    (".", POLICY_ERROR_PATH_DOT_PREFIX),
    # Empty / '.' / '..' segments (including trailing slash).
    ("app/src//X.kt", POLICY_ERROR_PATH_BAD_SEGMENT),
    ("app/../etc/X.kt", POLICY_ERROR_PATH_BAD_SEGMENT),
    ("app/src/main/java/X.kt/", POLICY_ERROR_PATH_BAD_SEGMENT),
    # Bare basenames are ambiguous across packages.
    ("X.kt", POLICY_ERROR_PATH_BARE_BASENAME),
    # Only Kotlin sources are canonical policy paths.
    ("app/src/main/java/com/foo/X.java", POLICY_ERROR_PATH_NOT_KOTLIN),
    ("app/src/main/java/com/foo/X", POLICY_ERROR_PATH_NOT_KOTLIN),
    # Outside the approved production root — including sloppy-prefix traps
    # and every non-production tree.
    ("app/src/main/java_extra/X.kt", POLICY_ERROR_PATH_OUTSIDE_APPROVED_ROOT),
    ("app/src/test/java/X.kt", POLICY_ERROR_PATH_OUTSIDE_APPROVED_ROOT),
    ("app/src/androidTest/java/X.kt", POLICY_ERROR_PATH_OUTSIDE_APPROVED_ROOT),
    ("app/src/main/kotlin/X.kt", POLICY_ERROR_PATH_OUTSIDE_APPROVED_ROOT),
    ("other/src/main/java/X.kt", POLICY_ERROR_PATH_OUTSIDE_APPROVED_ROOT),
    ("build/generated/X.kt", POLICY_ERROR_PATH_OUTSIDE_APPROVED_ROOT),
])
def test_non_canonical_paths_fail_closed_with_controlled_codes(path, expected_code):
    assert approved_root_error(path) == expected_code
    assert is_approved_source_path(path) is False


@pytest.mark.parametrize("path", [
    CANONICAL_PATH,
    None,
    "",
    "X.kt",
    "../escape.kt",
    "/abs/X.kt",
    "app/src/main/java_extra/X.kt",
    "app/src/main/java/X.txt",
])
def test_helpers_never_disagree(path):
    error = approved_root_error(path)
    if error is None:
        assert is_approved_source_path(path) is True
    else:
        assert error in KNOWN_POLICY_ERROR_CODES
        assert is_approved_source_path(path) is False
