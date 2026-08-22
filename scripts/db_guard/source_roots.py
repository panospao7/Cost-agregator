"""Shared root contract for approved production source paths.

This module is the single home of ``APPROVED_PRODUCTION_SOURCE_ROOTS`` — the
repository-relative POSIX roots that canonical DB policy paths must live
under (currently only ``app/src/main/java``).  It mirrors the canonical
policy-path validation performed by ``canonical_policy_path_error()`` in
``scripts/verify_db_access_boundaries.py``, but reports every rejection as a
controlled ``POLICY_ERROR_PATH_*`` code from ``scripts/db_guard/policy_errors.py``
instead of human-readable text, so callers can classify failures without
parsing messages and diagnostics stay limited to controlled constants.

A path is an approved production source path only when ALL of the following
hold (fail closed otherwise):

  * it is a non-empty string;
  * it is repository-relative POSIX ('/' separators, no backslash);
  * it is not absolute (no leading '/', drive letter, or UNC prefix);
  * it has no leading './' and no empty / '.' / '..' segments;
  * it is not a bare basename (duplicate basenames exist across packages);
  * it ends in ``.kt``;
  * it equals an approved root or lives below one (segment-aligned prefix,
    never a sloppy string prefix).

Anything else yields exactly one error code from ``approved_root_error``;
``is_approved_source_path`` is defined as ``approved_root_error(path) is
None`` so the two helpers can never disagree.
"""
from __future__ import annotations

import re
from typing import Optional

from .policy_errors import (
    POLICY_ERROR_PATH_ABSOLUTE,
    POLICY_ERROR_PATH_BACKSLASH,
    POLICY_ERROR_PATH_BAD_SEGMENT,
    POLICY_ERROR_PATH_BARE_BASENAME,
    POLICY_ERROR_PATH_DOT_PREFIX,
    POLICY_ERROR_PATH_EMPTY,
    POLICY_ERROR_PATH_NOT_KOTLIN,
    POLICY_ERROR_PATH_NOT_STRING,
    POLICY_ERROR_PATH_OUTSIDE_APPROVED_ROOT,
)

# Approved production source roots (repository-relative POSIX).  Canonical
# policy paths must live under one of these roots — a path cannot point at
# tests, generated code, or any other non-production tree.  Keep in exact
# parity with ``APPROVED_PRODUCTION_SOURCE_ROOTS`` in
# ``scripts/verify_db_access_boundaries.py``.
APPROVED_PRODUCTION_SOURCE_ROOTS = ("app/src/main/java",)

# Drive-letter absolute form (e.g. ``C:/`` or ``C:\``).  Backslash forms are
# rejected earlier by the separator rule; this mirrors the legacy validator's
# ``^[A-Za-z]:[\\/]`` check.
_DRIVE_LETTER_RE = re.compile(r"^[A-Za-z]:[\\/]")


def approved_root_error(path):
    # type: (object) -> Optional[str]
    """Return a controlled error code when ``path`` is not an approved
    production source path, or ``None`` when it is.

    Rule order and semantics mirror ``canonical_policy_path_error()`` in
    ``scripts/verify_db_access_boundaries.py``; each distinct canonical-form
    rejection maps to exactly one closed ``POLICY_ERROR_PATH_*`` code.  The
    return value is always ``None`` or a member of
    ``KNOWN_POLICY_ERROR_CODES`` — never free-form text.
    """
    if not isinstance(path, str):
        return POLICY_ERROR_PATH_NOT_STRING
    p = path.strip()
    if not p:
        return POLICY_ERROR_PATH_EMPTY
    if "\\" in p:
        return POLICY_ERROR_PATH_BACKSLASH
    if p.startswith("/") or p.startswith("\\\\") or _DRIVE_LETTER_RE.match(p):
        return POLICY_ERROR_PATH_ABSOLUTE
    if p.startswith("./") or p == ".":
        return POLICY_ERROR_PATH_DOT_PREFIX
    parts = p.split("/")
    if any(part in ("", ".", "..") for part in parts):
        return POLICY_ERROR_PATH_BAD_SEGMENT
    if len(parts) < 2:
        return POLICY_ERROR_PATH_BARE_BASENAME
    if not p.endswith(".kt"):
        return POLICY_ERROR_PATH_NOT_KOTLIN
    for root in APPROVED_PRODUCTION_SOURCE_ROOTS:
        if p == root or p.startswith(root + "/"):
            return None
    return POLICY_ERROR_PATH_OUTSIDE_APPROVED_ROOT


def is_approved_source_path(path):
    # type: (object) -> bool
    """Return True only for canonical production source paths under an
    approved root; False for everything else (fail closed)."""
    return approved_root_error(path) is None


__all__ = [
    "APPROVED_PRODUCTION_SOURCE_ROOTS",
    "approved_root_error",
    "is_approved_source_path",
]
