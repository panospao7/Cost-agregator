#!/usr/bin/env python3
"""
gradle_db_guard_inputs.py

Contract mirror for the ``:app:verifyDbAccessBoundaries`` input validation in
``app/build.gradle.kts`` (PR-GR-01).

The Gradle task validates, in order:

  1. every required input resolves to a canonical path inside the repository
     root (``canonicalFile.startsWith(rootCanonical + File.separator,
     ignoreCase = true)``);
  2. each required input exists, is a regular file, and is readable;
  3. the Python interpreter launches and ``pythonExecutable --version`` exits 0
     (a failure to launch or a non-zero exit is an infrastructure error).

This module mirrors that exact contract so it can be exercised behaviorally
without executing Gradle.  ``app/build.gradle.kts`` keeps its inline
implementation (Gradle task behavior is unchanged); this helper must stay in
sync with the task's validation logic.

CONTRACT MIRROR
---------------

This module is the canonical Python contract mirror for the Gradle task's
input validation.  Whenever ``:app:verifyDbAccessBoundaries`` in
``app/build.gradle.kts`` changes its required inputs, override property names,
or validation rules, you MUST:

  1. update this mirror (especially ``DEFAULT_DB_GUARD_INPUTS``); and
  2. keep the parity tests in ``scripts/ci/test_gradle_db_guard_contract.py``
     green — ``test_parity_task_inputs_and_overrides_match_contract_mirror``
     and ``test_parity_command_always_passes_all_required_input_paths`` are
     REQUIRED gates whenever the Gradle validation changes.

Public API (relied on by tests):
  * ``GradleDbGuardInputError``
  * ``DEFAULT_DB_GUARD_INPUTS``
  * ``resolve_db_guard_path()``
  * ``validate_db_guard_inputs()``
  * ``preflight_python_executable()``
"""

import os
import subprocess
from pathlib import Path
from typing import Dict, List, Optional, Sequence, Tuple


class GradleDbGuardInputError(Exception):
    """Controlled validation failure mirroring a GradleException path.

    ``code`` is a controlled constant used by tests/consumers instead of the
    (potentially sensitive) underlying OS/Gradle message.
    """

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


# (default_rel, override_property) pairs enforced by the Gradle task.
DEFAULT_DB_GUARD_INPUTS: List[Tuple[str, str]] = [
    ("scripts/ci/guard_ratchet.py", "dbGuardRatchetPath"),
    ("scripts/verify_db_access_boundaries.py", "dbGuardScriptPath"),
    ("config/baselines/db_access.json", "dbGuardBaselinePath"),
    ("config/guards/db_ownership_policy.yml", "dbGuardOwnershipPolicyPath"),
    ("config/guards/db_structural_exceptions.yml", "dbGuardStructuralExceptionsPath"),
    (
        "config/guards/db_structural_exceptions_expected_methods.yml",
        "dbGuardStructuralManifestPath",
    ),
    (
        "config/guards/production_source_roots.yml",
        "dbGuardSourceRootsManifestPath",
    ),
]


def _is_inside_repo_root(canonical: Path, root_canonical: Path) -> bool:
    """Return whether ``canonical`` is contained inside ``root_canonical``.

    Mirrors the Gradle task's in-repo check exactly:
    ``canonicalFile.startsWith(rootCanonical.path + File.separator,
    ignoreCase = true)``.

    The comparison is normalized (``os.path.normcase``) and case-insensitive
    on every platform — Gradle ignores case everywhere, not only on Windows.
    The trailing separator is required, so the prefix preserves path-boundary
    safety: a sibling like ``/repo2`` can never match a root ``/repo``.
    """
    prefix = os.path.normcase(str(root_canonical) + os.sep).lower()
    candidate = os.path.normcase(str(canonical)).lower()
    return candidate.startswith(prefix)


def resolve_db_guard_path(
    root: Path,
    default_rel: str,
    override: Optional[str] = None,
) -> Path:
    """Resolve one required input and enforce the in-repo canonical check.

    Mirrors ``resolveDbGuardPath`` in app/build.gradle.kts:

      * blank overrides are ignored (production defaults win);
      * absolute overrides are used as-is; relative overrides resolve against
        the repository *root* — the Gradle task resolves relative overrides
        against ``rootDir`` (the repository root, not the Gradle project dir),
        so *root* here must be the same repository root to stay in parity;
      * the canonicalized path must stay inside the canonical repository root,
        compared case-insensitively on every platform to mirror Gradle's
        ``startsWith(..., ignoreCase = true)``.

    Returns the canonical resolved path (``candidate.resolve()``) — exactly
    what the Gradle task's ``canonicalFile`` returns after its in-repo check,
    not the raw candidate path.

    Raises ``GradleDbGuardInputError`` with code ``outside_root`` when the
    canonical path escapes the repository root.
    """
    if override is not None and override.strip():
        candidate = Path(override)
        if not candidate.is_absolute():
            candidate = root / candidate
    else:
        candidate = root / default_rel

    canonical = candidate.resolve()
    root_canonical = root.resolve()
    if not _is_inside_repo_root(canonical, root_canonical):
        raise GradleDbGuardInputError(
            "outside_root",
            f"required input for '{default_rel}' points outside the repository root: "
            f"{candidate}",
        )
    return canonical


def validate_db_guard_inputs(
    root: Path,
    inputs: Optional[Sequence[Tuple[str, Optional[str]]]] = None,
) -> Dict[str, Path]:
    """Validate every required DB guard input; return ``{default_rel: path}``.

    Mirrors the required-input validation in app/build.gradle.kts.  Raises
    ``GradleDbGuardInputError`` on the first failure.  Failure codes:

      * ``outside_root`` — canonical path escapes the repository root;
      * ``not_found``    — path does not exist;
      * ``not_regular``  — path is not a regular file;
      * ``not_readable`` — path exists but is not readable.

    ``inputs`` defaults to every canonical required input with no override.

    Each value in the returned mapping is the canonical resolved path
    returned by :func:`resolve_db_guard_path` (mirroring the Gradle task's
    ``canonicalFile`` return value).
    """
    if inputs is None:
        inputs = [(rel, None) for (rel, _prop) in DEFAULT_DB_GUARD_INPUTS]

    resolved: Dict[str, Path] = {}
    for default_rel, override in inputs:
        candidate = resolve_db_guard_path(root, default_rel, override)
        if not candidate.exists():
            raise GradleDbGuardInputError(
                "not_found",
                f"required input not found: {candidate} ({default_rel})",
            )
        if not candidate.is_file():
            raise GradleDbGuardInputError(
                "not_regular",
                f"required input is not a regular file: {candidate} ({default_rel})",
            )
        if not os.access(candidate, os.R_OK):
            raise GradleDbGuardInputError(
                "not_readable",
                f"required input is not readable: {candidate} ({default_rel})",
            )
        resolved[default_rel] = candidate
    return resolved


def preflight_python_executable(python_executable: str) -> None:
    """Run the ``pythonExecutable --version`` preflight (shell=False).

    Mirrors the Gradle preflight in app/build.gradle.kts: a failure to launch
    or a non-zero exit is an infrastructure error (ratchet exit 2).  Raises
    ``GradleDbGuardInputError`` with code ``python_preflight`` on failure and
    returns normally on success.
    """
    try:
        result = subprocess.run(
            [python_executable, "--version"],
            shell=False,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=30,
        )
    except Exception as exc:
        raise GradleDbGuardInputError(
            "python_preflight",
            f"Python preflight failed — could not launch '{python_executable}' "
            f"(infrastructure error). Pass -PpythonExecutable=/path/to/python3 "
            f"to specify the interpreter. ({exc.__class__.__name__})",
        ) from exc
    if result.returncode != 0:
        raise GradleDbGuardInputError(
            "python_preflight",
            f"Python preflight failed — '{python_executable} --version' exited "
            f"{result.returncode} (infrastructure error). "
            f"Pass -PpythonExecutable=/path/to/python3 to specify the interpreter.",
        )
