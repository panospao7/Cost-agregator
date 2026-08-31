#!/usr/bin/env python3
"""
GUARD_EXECUTION_PLAN — PR-GR-10A Slice 1: typed registry-to-plan compiler.

Pure module that turns the guard registry's ``execution`` sections into fully
resolved, tokenized execution plans.  This is the canonical direction of
authority from docs/guardrails/PR-GR-10A_canonical_command_ownership_plan.md:

    guard_registry -> guard_execution_plan (this module) -> runners (Slice 2)

Slice 1 scope (no runner wiring yet):
  - frozen typed models (GuardSpec, RatchetSpec, ExecutionContext,
    ExecutionPlan, PlanDiagnostic);
  - load_guard_specs / validate_guard_specs / compile_guard_plan /
    compile_static_suite_plan / canonicalize_plan_for_comparison /
    write_plan_json.

Non-negotiable command rules implemented here (plan "Core architecture
contract"):
  1. Production commands are token lists only; registry template tokens are
     checked against the safe argv allowlist mirrored from
     ``capture_db_guard_evidence._SAFE_ARGV_TOKEN_RE`` — any shell
     metacharacter is rejected with a diagnostic.
  2. A canonical plan never emits bare ``python``/``python3``; the interpreter
     is always ``ExecutionContext.interpreter_path`` (a resolved absolute
     path).  Bare names are rejected fail-closed.
  3. Ratchet child commands are emitted as repeated single-token
     ``--command-arg=<value>`` outer argv entries (never the legacy
     ``--command`` shell string).
  4. Registered protocol-v2 guards pass explicit ``--finding-protocol=2``
     intent on the ratchet outer argv; protocol is carried on the plan.
  5. Paths are repository-relative in registry data and resolved only by the
     execution context (plans carry absolute resolved paths;
     ``canonicalize_plan_for_comparison`` normalizes back to repo-relative).
  7. Test-only input overrides are typed, must be root-contained, and are
     rejected outright in production CI mode.

Purity contract: no ``sys.exit``, no process execution, and no I/O except the
optional atomic plan JSON write (``write_plan_json``) and the read-only
registry module import performed by ``load_guard_specs``.  All failures are
reported as ``PlanDiagnostic`` values with stable controlled codes; callers
own every exit-code decision.

Slice 1 does NOT change any runner: ``run_static_guard_suite.py``'s
``GUARD_MANIFEST`` remains the executing authority until Slice 2 wires
``compile_static_suite_plan`` in.
"""

import importlib.util
import json
import os
import re
import tempfile
from contextlib import suppress
from dataclasses import dataclass
from typing import Any, Dict, Optional, Tuple

__all__ = [
    "GuardSpec",
    "RatchetSpec",
    "ExecutionContext",
    "ExecutionPlan",
    "PlanDiagnostic",
    "TIMEOUT_PROFILES",
    "ENGINE_VOCABULARY",
    "MODE_VOCABULARY",
    "CI_RESTRICTION_VOCABULARY",
    "DEFAULT_REGISTRY_PATH",
    "load_guard_specs",
    "validate_guard_specs",
    "compile_guard_plan",
    "compile_static_suite_plan",
    "canonicalize_plan_for_comparison",
    "write_plan_json",
    "normalize_test_overrides",
]


# ── Vocabulary and constants ─────────────────────────────────────────────────────

DEFAULT_REGISTRY_PATH = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "guard_registry.py"
)

# The ratchet entrypoint is compiler-owned infrastructure (rule 6: the registry
# owns semantic arguments; adapters own runtime path resolution).  It is
# resolved against the context repo root at compile time.
RATCHET_ENTRYPOINT = "scripts/ci/guard_ratchet.py"

ENGINE_PYTHON_DIRECT = "python-direct"
ENGINE_PYTHON_RATCHET = "python-ratchet"
ENGINE_GRADLE_NATIVE = "gradle-native"
ENGINE_EXTERNAL = "external"

ENGINE_VOCABULARY = (
    ENGINE_PYTHON_DIRECT,
    ENGINE_PYTHON_RATCHET,
    ENGINE_GRADLE_NATIVE,
    ENGINE_EXTERNAL,
)

MODE_VOCABULARY = ("blocking", "ratchet", "warning", "policy")

_PYTHON_ENGINES = (ENGINE_PYTHON_DIRECT, ENGINE_PYTHON_RATCHET)

# Named timeout profiles — named semantics transcribed from the static suite's
# GUARD_TIME_BUDGETS / DEFAULT_GUARD_TIMEOUT_SECONDS (PR-GR-10c), never
# duplicated numeric arithmetic at the call sites:
#   standard          300s  flat ceiling for small guards (suite default budget)
#   artifact-sync     600s  db_artifact_sync in-memory regeneration ceiling
#   known-good-state 1200s  PR-GR-10e scorecard warm-path headroom
#   D4                840s  db_access full-tree D4 scan budget
TIMEOUT_PROFILES: Dict[str, int] = {
    "standard": 300,
    "artifact-sync": 600,
    "known-good-state": 1200,
    "D4": 840,
}

FINDING_PROTOCOL_LEGACY = 1
FINDING_PROTOCOL_V2 = 2

# Controlled vocabulary for RatchetSpec.ci_restrictions (what --ci-mode
# enforces in guard_ratchet.py today).
CI_RESTRICTION_VOCABULARY = ("no-update-baseline", "no-propose-baseline")

# Template placeholders allowed inside registry argument tokens.  The
# interpreter is NEVER a registry literal (rule 2) — it can only enter a plan
# through the ``{interpreter}`` placeholder or direct context injection.
TEMPLATE_PLACEHOLDERS = ("interpreter", "entrypoint", "output_dir")
_TEMPLATE_GROUP_RE = re.compile(r"\{([a-z_]+)\}")

# Safe argv token allowlist, mirrored from the existing validator
# (capture_db_guard_evidence._SAFE_ARGV_TOKEN_RE): spaces, quotes, shell
# metacharacters, backticks, ``$``, ``;``, ``|``, ``&``, ``<``, ``>``,
# parentheses, newlines, NUL, etc. are rejected outright so a registry token
# can never become a shell string (rule 1).
_SAFE_ARGV_TOKEN_RE = re.compile(r"^[A-Za-z0-9_./:=-]+$")

COMMAND_ARG_PREFIX = "--command-arg="
INTERPRETER_PLACEHOLDER = "<resolved-interpreter>"
_BARE_PYTHON_NAMES = ("python", "python3")

PLAN_JSON_SCHEMA_VERSION = 1

# Bounded diagnostic context (privacy: bounded structured fields only).
_MAX_DIAGNOSTIC_CONTEXT = 200
_CONTROL_CHARS_RE = re.compile(r"[\x00-\x1f\x7f]")

# Stable diagnostic codes (controlled constants only — never free text).
E_REGISTRY_LOAD_FAILED = "E_REGISTRY_LOAD_FAILED"
E_MISSING_EXECUTION = "E_MISSING_EXECUTION"
E_MISSING_FIELD = "E_MISSING_FIELD"
E_MODE_MISMATCH = "E_MODE_MISMATCH"
E_PROTOCOL_MISMATCH = "E_PROTOCOL_MISMATCH"
E_FINGERPRINT_SCHEMA_MISMATCH = "E_FINGERPRINT_SCHEMA_MISMATCH"
E_INVALID_ENGINE = "E_INVALID_ENGINE"
E_ENGINE_NOT_COMPILABLE = "E_ENGINE_NOT_COMPILABLE"
E_INVALID_MODE = "E_INVALID_MODE"
E_ENGINE_MODE_MISMATCH = "E_ENGINE_MODE_MISMATCH"
E_RATCHET_SPEC_REQUIRED = "E_RATCHET_SPEC_REQUIRED"
E_RATCHET_FIELD_FORBIDDEN = "E_RATCHET_FIELD_FORBIDDEN"
E_NOT_REPO_RELATIVE = "E_NOT_REPO_RELATIVE"
E_UNSAFE_TOKEN = "E_UNSAFE_TOKEN"
E_TEMPLATE_MALFORMED = "E_TEMPLATE_MALFORMED"
E_TEMPLATE_UNKNOWN_TOKEN = "E_TEMPLATE_UNKNOWN_TOKEN"
E_TEMPLATE_UNRESOLVED = "E_TEMPLATE_UNRESOLVED"
E_BARE_PYTHON = "E_BARE_PYTHON"
E_UNKNOWN_TIMEOUT_PROFILE = "E_UNKNOWN_TIMEOUT_PROFILE"
E_INVALID_TIMEOUT_OVERRIDE = "E_INVALID_TIMEOUT_OVERRIDE"
E_INVALID_PROTOCOL = "E_INVALID_PROTOCOL"
E_INVALID_FINGERPRINT_SCHEMA = "E_INVALID_FINGERPRINT_SCHEMA"
E_INVALID_CI_RESTRICTION = "E_INVALID_CI_RESTRICTION"
E_INVALID_TEST_MANIFEST = "E_INVALID_TEST_MANIFEST"
E_DUPLICATE_ENTRYPOINT = "E_DUPLICATE_ENTRYPOINT"
E_INVALID_CONTEXT = "E_INVALID_CONTEXT"
E_UNKNOWN_GUARD = "E_UNKNOWN_GUARD"
E_ENTRYPOINT_UNRESOLVED = "E_ENTRYPOINT_UNRESOLVED"
E_INPUT_UNRESOLVED = "E_INPUT_UNRESOLVED"
E_BASELINE_UNRESOLVED = "E_BASELINE_UNRESOLVED"
E_TEST_OVERRIDE_IN_CI = "E_TEST_OVERRIDE_IN_CI"
E_OVERRIDE_OUTSIDE_ROOT = "E_OVERRIDE_OUTSIDE_ROOT"
E_OVERRIDE_UNKNOWN_KEY = "E_OVERRIDE_UNKNOWN_KEY"


# ── Typed models (frozen) ────────────────────────────────────────────────────────


@dataclass(frozen=True)
class PlanDiagnostic:
    """Bounded, structured diagnostic with a stable controlled code."""

    code: str
    guard_id: Optional[str]
    context: str
    severity: str  # "error" | "warning"


@dataclass(frozen=True)
class RatchetSpec:
    """Ratchet execution metadata (required for ratchet-mode guards only)."""

    baseline_path: str  # repo-relative
    finding_protocol: int  # 1 (legacy stdout) or 2 (structured report)
    fingerprint_schema: int
    child_argument_template: Tuple[str, ...]  # tokens after the entrypoint
    ci_restrictions: Tuple[str, ...]


@dataclass(frozen=True)
class GuardSpec:
    """Static registry metadata for one guard (identity + invocation)."""

    guard_id: str
    description: str
    mode: str
    engine: str
    entrypoint: str  # repo-relative
    rule_ids: Tuple[str, ...]
    required_inputs: Tuple[str, ...]  # repo-relative
    test_manifest: Tuple[str, ...]  # repo-relative; empty = documented absence
    timeout_profile: str  # named profile key in TIMEOUT_PROFILES
    arguments: Tuple[str, ...]  # token template (direct guards)
    output_contract: str
    documentation_anchor: str  # repo-relative doc path
    ratchet: Optional[RatchetSpec]


@dataclass(frozen=True)
class ExecutionContext:
    """Runtime-only values; never persisted into registry data."""

    repo_root: str  # absolute
    interpreter_path: str  # absolute resolved interpreter (never bare python)
    ci_mode: bool = False
    output_dir: Optional[str] = None  # absolute or None
    timeout_override: Optional[int] = None  # positive seconds or None
    # Typed test-only input overrides: pairs of (declared repo-relative input
    # key, absolute replacement path).  Root-contained; rejected in ci_mode.
    test_only_overrides: Tuple[Tuple[str, str], ...] = ()


@dataclass(frozen=True)
class ExecutionPlan:
    """Fully resolved, tokenized command graph for one guard + one context.

    ``repo_root`` is the resolution anchor carried so
    ``canonicalize_plan_for_comparison`` can normalize absolute paths back to
    repo-relative spellings; it is never serialized by ``write_plan_json``.
    """

    guard_id: str
    mode: str
    engine: str
    outer_argv: Tuple[str, ...]
    child_argv: Optional[Tuple[str, ...]]  # ratchet guards only
    resolved_required_inputs: Tuple[str, ...]  # absolute, post-override
    baseline: Optional[str]  # absolute; ratchet guards only
    timeout_seconds: int
    protocol: Optional[int]  # finding protocol; ratchet guards only
    output_contract: str
    repo_root: str


# ── Small helpers ────────────────────────────────────────────────────────────────


def _diag(code: str, guard_id: Optional[str], context: str,
          severity: str = "error") -> PlanDiagnostic:
    """Build a bounded diagnostic (controlled code, truncated context)."""
    text = _CONTROL_CHARS_RE.sub(" ", str(context or "")).strip()
    if len(text) > _MAX_DIAGNOSTIC_CONTEXT:
        text = text[:_MAX_DIAGNOSTIC_CONTEXT] + "..."
    return PlanDiagnostic(code=code, guard_id=guard_id, context=text,
                          severity=severity)


def _is_repo_relative(path: str) -> bool:
    """True iff ``path`` is a safe repository-relative path spelling."""
    if not isinstance(path, str) or not path:
        return False
    if "\\" in path:
        return False
    if path.startswith("/"):
        return False
    if os.path.isabs(path):
        return False
    if os.path.splitdrive(path)[0]:
        return False
    if ".." in path.split("/"):
        return False
    return True


def _is_root_contained(path: str, root: str) -> bool:
    """True iff absolute ``path`` is strictly contained inside ``root``."""
    if not isinstance(path, str) or not path or not os.path.isabs(path):
        return False
    root_abs = os.path.abspath(root)
    try:
        contained = (
            os.path.commonpath([os.path.normcase(path),
                                os.path.normcase(root_abs)])
            == os.path.normcase(root_abs)
        )
    except ValueError:
        return False
    if not contained:
        return False
    return os.path.normcase(os.path.abspath(path)) != os.path.normcase(root_abs)


def _relativize_against_root(value: str, root: str) -> str:
    """Normalize an absolute path under ``root`` to a repo-relative spelling."""
    if not isinstance(value, str) or not value or not os.path.isabs(value):
        return value
    root_abs = os.path.abspath(root)
    try:
        if os.path.commonpath(
            [os.path.normcase(value), os.path.normcase(root_abs)]
        ) != os.path.normcase(root_abs):
            return value
    except ValueError:
        return value
    rel = os.path.relpath(value, root_abs)
    return rel.replace(os.sep, "/")


def _check_template_token(token: str, guard_id: str, field: str,
                          diags: list) -> bool:
    """Validate a raw registry template token; append diagnostics on failure.

    Rules: known ``{placeholder}`` groups only, no unbalanced braces, and every
    literal residual must match the safe argv allowlist (no shell strings).
    """
    if not isinstance(token, str) or not token:
        diags.append(_diag(E_UNSAFE_TOKEN, guard_id,
                           f"{field}: empty or non-string token"))
        return False
    parts = _TEMPLATE_GROUP_RE.split(token)
    for index, part in enumerate(parts):
        if index % 2 == 1:
            if part not in TEMPLATE_PLACEHOLDERS:
                diags.append(_diag(
                    E_TEMPLATE_UNKNOWN_TOKEN, guard_id,
                    f"{field}: unknown placeholder {{{part}}}"))
                return False
        else:
            if "{" in part or "}" in part:
                diags.append(_diag(E_TEMPLATE_MALFORMED, guard_id,
                                   f"{field}: unbalanced braces in token"))
                return False
            if part and not _SAFE_ARGV_TOKEN_RE.fullmatch(part):
                diags.append(_diag(
                    E_UNSAFE_TOKEN, guard_id,
                    f"{field}: token contains characters outside the safe "
                    f"argv allowlist (shell strings are forbidden)"))
                return False
    return True


def _resolve_template_token(token: str, placeholders: Dict[str, Optional[str]],
                            input_resolution: Dict[str, str], guard_id: str,
                            field: str, diags: list) -> Optional[str]:
    """Resolve one template token against the context; None on failure."""
    if not _check_template_token(token, guard_id, field, diags):
        return None
    resolved_parts = []
    for index, part in enumerate(_TEMPLATE_GROUP_RE.split(token)):
        if index % 2 == 1:
            value = placeholders.get(part)
            if value is None:
                diags.append(_diag(
                    E_TEMPLATE_UNRESOLVED, guard_id,
                    f"{field}: placeholder {{{part}}} has no context value"))
                return None
            resolved_parts.append(value)
        else:
            resolved_parts.append(part)
    resolved = "".join(resolved_parts)
    # A token that exactly matches a declared required input is a path and is
    # resolved (and overridden) by the context (rule 5).
    mapped = input_resolution.get(resolved)
    if mapped is not None:
        resolved = mapped
    if resolved in _BARE_PYTHON_NAMES:
        diags.append(_diag(E_BARE_PYTHON, guard_id,
                           f"{field}: resolved token is a bare interpreter "
                           f"name (rule 2)"))
        return None
    return resolved


def normalize_test_overrides(
    mapping: Dict[str, str],
) -> Tuple[Tuple[str, str], ...]:
    """Convert an override mapping into the frozen sorted pair form."""
    return tuple(sorted(mapping.items()))


# ── Model builders ───────────────────────────────────────────────────────────────


def _build_ratchet_spec(guard_id: str, raw: Dict[str, Any], entry: Dict[str, Any],
                        diags: list) -> Optional[RatchetSpec]:
    baseline_path = raw.get("baselinePath")
    if not isinstance(baseline_path, str) or not baseline_path:
        diags.append(_diag(E_MISSING_FIELD, guard_id,
                           "execution.ratchet.baselinePath absent"))
        return None
    finding_protocol = raw.get("findingProtocol")
    if (not isinstance(finding_protocol, int)
            or isinstance(finding_protocol, bool)
            or finding_protocol not in (FINDING_PROTOCOL_LEGACY,
                                        FINDING_PROTOCOL_V2)):
        diags.append(_diag(E_INVALID_PROTOCOL, guard_id,
                           "execution.ratchet.findingProtocol must be 1 or 2"))
        return None
    fingerprint_schema = raw.get("fingerprintSchema")
    if (not isinstance(fingerprint_schema, int)
            or isinstance(fingerprint_schema, bool)
            or fingerprint_schema < 1):
        diags.append(_diag(E_INVALID_FINGERPRINT_SCHEMA, guard_id,
                           "execution.ratchet.fingerprintSchema must be >= 1"))
        return None
    child_template = raw.get("childArgumentTemplate")
    if (not isinstance(child_template, (list, tuple)) or not child_template
            or not all(isinstance(t, str) for t in child_template)):
        diags.append(_diag(E_MISSING_FIELD, guard_id,
                           "execution.ratchet.childArgumentTemplate must be a "
                           "non-empty token list"))
        return None
    ci_restrictions = raw.get("ciRestrictions") or ()
    if (not isinstance(ci_restrictions, (list, tuple))
            or not all(isinstance(r, str) for r in ci_restrictions)
            or any(r not in CI_RESTRICTION_VOCABULARY for r in ci_restrictions)):
        diags.append(_diag(E_INVALID_CI_RESTRICTION, guard_id,
                           "execution.ratchet.ciRestrictions must be drawn "
                           "from the controlled vocabulary"))
        return None
    # Cross-check against legacy top-level metadata (drift guard; the legacy
    # fields stay intact — this schema is additive).
    legacy_protocol = entry.get("finding_protocol")
    if legacy_protocol is not None and legacy_protocol != finding_protocol:
        diags.append(_diag(
            E_PROTOCOL_MISMATCH, guard_id,
            f"execution ratchet findingProtocol {finding_protocol} != legacy "
            f"finding_protocol {legacy_protocol}"))
        return None
    legacy_schema = entry.get("fingerprint_schema")
    if legacy_schema is not None and legacy_schema != fingerprint_schema:
        diags.append(_diag(
            E_FINGERPRINT_SCHEMA_MISMATCH, guard_id,
            f"execution ratchet fingerprintSchema {fingerprint_schema} != "
            f"legacy fingerprint_schema {legacy_schema}"))
        return None
    return RatchetSpec(
        baseline_path=baseline_path,
        finding_protocol=finding_protocol,
        fingerprint_schema=fingerprint_schema,
        child_argument_template=tuple(child_template),
        ci_restrictions=tuple(ci_restrictions),
    )


# ── Registry loading ─────────────────────────────────────────────────────────────

_LOAD_COUNTER = [0]


def load_guard_specs(
    registry_path: str,
) -> Tuple[Tuple[GuardSpec, ...], Tuple[PlanDiagnostic, ...]]:
    """Load guard specs (including the execution sections) from a registry.

    Entries without a usable execution section produce an error diagnostic and
    are omitted from the returned specs — load is not fatal, but compilation
    fails closed per-guard because the guard has no spec.
    """
    diags: list = []
    _LOAD_COUNTER[0] += 1
    module_name = f"_gr10a_guard_registry_{_LOAD_COUNTER[0]}"
    try:
        spec = importlib.util.spec_from_file_location(module_name, registry_path)
        if spec is None or spec.loader is None:
            raise ImportError("registry module spec could not be created")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
    except Exception as exc:  # noqa: BLE001 — bounded diagnostic, class name only
        return (), (_diag(E_REGISTRY_LOAD_FAILED, None,
                          f"registry import failed: {type(exc).__name__}"),)
    registry = getattr(module, "GUARD_REGISTRY", None)
    if not isinstance(registry, dict) or not registry:
        return (), (_diag(E_REGISTRY_LOAD_FAILED, None,
                          "GUARD_REGISTRY mapping absent or empty"),)

    specs: list = []
    for guard_id, entry in registry.items():
        if not isinstance(entry, dict):
            diags.append(_diag(E_MISSING_EXECUTION, str(guard_id),
                               "registry entry is not a mapping"))
            continue
        execution = entry.get("execution")
        if not isinstance(execution, dict) or not execution:
            diags.append(_diag(E_MISSING_EXECUTION, str(guard_id),
                               "registry entry has no execution section"))
            continue

        legacy_mode = entry.get("mode")
        exec_mode = execution.get("mode")
        if (legacy_mode is not None and exec_mode is not None
                and legacy_mode != exec_mode):
            diags.append(_diag(
                E_MODE_MISMATCH, str(guard_id),
                f"execution mode {exec_mode!r} != legacy registry mode "
                f"{legacy_mode!r}"))

        engine = execution.get("engine")
        entrypoint = execution.get("entrypoint")
        mode = exec_mode if exec_mode is not None else legacy_mode
        timeout_profile = execution.get("timeoutProfile")
        output_contract = execution.get("outputContract")
        documentation_anchor = execution.get("documentationAnchor")
        description = entry.get("description") or ""

        missing = [
            name for name, value in (
                ("engine", engine),
                ("entrypoint", entrypoint),
                ("mode", mode),
                ("timeoutProfile", timeout_profile),
                ("outputContract", output_contract),
                ("documentationAnchor", documentation_anchor),
                ("description", description),
            ) if not value
        ]
        if missing:
            diags.append(_diag(E_MISSING_FIELD, str(guard_id),
                               "required fields absent: " + ",".join(missing)))
            continue

        arguments = execution.get("arguments") or ()
        if (not isinstance(arguments, (list, tuple))
                or not all(isinstance(t, str) for t in arguments)):
            diags.append(_diag(E_UNSAFE_TOKEN, str(guard_id),
                               "execution.arguments must be a token list"))
            continue
        required_inputs = execution.get("requiredInputs") or ()
        if (not isinstance(required_inputs, (list, tuple))
                or not all(isinstance(t, str) for t in required_inputs)):
            diags.append(_diag(E_NOT_REPO_RELATIVE, str(guard_id),
                               "execution.requiredInputs must be a path list"))
            continue
        rule_ids = execution.get("ruleIds") or ()
        if (not isinstance(rule_ids, (list, tuple))
                or not all(isinstance(t, str) for t in rule_ids)):
            diags.append(_diag(E_MISSING_FIELD, str(guard_id),
                               "execution.ruleIds must be a string list"))
            continue

        test_manifest_raw = execution.get("testManifest")
        if test_manifest_raw is None or test_manifest_raw == "none":
            test_manifest: Tuple[str, ...] = ()
        elif (isinstance(test_manifest_raw, (list, tuple))
              and all(isinstance(t, str) for t in test_manifest_raw)):
            test_manifest = tuple(test_manifest_raw)
        else:
            diags.append(_diag(
                E_INVALID_TEST_MANIFEST, str(guard_id),
                "execution.testManifest must be a list of repo-relative test "
                "paths or the literal 'none' (documented absence)"))
            continue

        ratchet_raw = execution.get("ratchet")
        ratchet_spec: Optional[RatchetSpec] = None
        if isinstance(ratchet_raw, dict) and ratchet_raw:
            ratchet_spec = _build_ratchet_spec(str(guard_id), ratchet_raw,
                                               entry, diags)
            if ratchet_spec is None:
                continue

        specs.append(GuardSpec(
            guard_id=str(guard_id),
            description=description,
            mode=str(mode),
            engine=str(engine),
            entrypoint=str(entrypoint),
            rule_ids=tuple(rule_ids),
            required_inputs=tuple(required_inputs),
            test_manifest=test_manifest,
            timeout_profile=str(timeout_profile),
            arguments=tuple(arguments),
            output_contract=str(output_contract),
            documentation_anchor=str(documentation_anchor),
            ratchet=ratchet_spec,
        ))

    return tuple(specs), tuple(diags)


# ── Validation ───────────────────────────────────────────────────────────────────


def validate_guard_specs(
    specs: Tuple[GuardSpec, ...],
    repo_root: Optional[str] = None,
) -> Tuple[PlanDiagnostic, ...]:
    """Validate guard specs structurally (and on-disk when a root is given).

    When ``repo_root`` is provided, entrypoints, required inputs, test
    manifests, and documentation anchors must exist under it.
    """
    diags: list = []
    seen_entrypoints: Dict[str, str] = {}

    for spec in specs:
        gid = spec.guard_id

        if spec.mode not in MODE_VOCABULARY:
            diags.append(_diag(E_INVALID_MODE, gid,
                               f"unrecognized mode {spec.mode!r}"))
        if spec.engine not in ENGINE_VOCABULARY:
            diags.append(_diag(E_INVALID_ENGINE, gid,
                               f"unrecognized engine {spec.engine!r}"))
        if ((spec.mode == "ratchet")
                != (spec.engine == ENGINE_PYTHON_RATCHET)):
            diags.append(_diag(
                E_ENGINE_MODE_MISMATCH, gid,
                f"mode {spec.mode!r} requires engine python-ratchet and vice "
                f"versa (got engine {spec.engine!r})"))
        if spec.mode == "ratchet" and spec.ratchet is None:
            diags.append(_diag(E_RATCHET_SPEC_REQUIRED, gid,
                               "ratchet-mode guard requires a ratchet spec"))
        if spec.mode != "ratchet" and spec.ratchet is not None:
            diags.append(_diag(E_RATCHET_FIELD_FORBIDDEN, gid,
                               "non-ratchet guard carries a ratchet spec"))
        if not spec.description:
            diags.append(_diag(E_MISSING_FIELD, gid, "description absent"))
        if not spec.output_contract:
            diags.append(_diag(E_MISSING_FIELD, gid, "outputContract absent"))
        if spec.timeout_profile not in TIMEOUT_PROFILES:
            diags.append(_diag(E_UNKNOWN_TIMEOUT_PROFILE, gid,
                               f"unknown timeout profile "
                               f"{spec.timeout_profile!r}"))

        if not _is_repo_relative(spec.entrypoint):
            diags.append(_diag(E_NOT_REPO_RELATIVE, gid,
                               f"entrypoint not repo-relative: "
                               f"{spec.entrypoint!r}"))
        for declared in spec.required_inputs:
            if not _is_repo_relative(declared):
                diags.append(_diag(E_NOT_REPO_RELATIVE, gid,
                                   f"requiredInput not repo-relative: "
                                   f"{declared!r}"))
        for test_path in spec.test_manifest:
            if not _is_repo_relative(test_path):
                diags.append(_diag(E_NOT_REPO_RELATIVE, gid,
                                   f"testManifest entry not repo-relative: "
                                   f"{test_path!r}"))
        if not _is_repo_relative(spec.documentation_anchor):
            diags.append(_diag(E_NOT_REPO_RELATIVE, gid,
                               f"documentationAnchor not repo-relative: "
                               f"{spec.documentation_anchor!r}"))

        for token in spec.arguments:
            _check_template_token(token, gid, "arguments", diags)

        if spec.ratchet is not None:
            ratchet = spec.ratchet
            if not _is_repo_relative(ratchet.baseline_path):
                diags.append(_diag(E_NOT_REPO_RELATIVE, gid,
                                   f"ratchet baselinePath not repo-relative: "
                                   f"{ratchet.baseline_path!r}"))
            if ratchet.finding_protocol not in (
                    FINDING_PROTOCOL_LEGACY, FINDING_PROTOCOL_V2):
                diags.append(_diag(E_INVALID_PROTOCOL, gid,
                                   "ratchet findingProtocol must be 1 or 2"))
            if (not isinstance(ratchet.fingerprint_schema, int)
                    or isinstance(ratchet.fingerprint_schema, bool)
                    or ratchet.fingerprint_schema < 1):
                diags.append(_diag(E_INVALID_FINGERPRINT_SCHEMA, gid,
                                   "ratchet fingerprintSchema must be >= 1"))
            if (ratchet.finding_protocol == FINDING_PROTOCOL_V2
                    and ratchet.fingerprint_schema != 2):
                diags.append(_diag(
                    E_PROTOCOL_MISMATCH, gid,
                    "protocol-v2 guards must declare fingerprint schema 2"))
            if not ratchet.child_argument_template:
                diags.append(_diag(E_MISSING_FIELD, gid,
                                   "ratchet childArgumentTemplate empty"))
            for token in ratchet.child_argument_template:
                _check_template_token(token, gid,
                                      "ratchet.childArgumentTemplate", diags)
            for restriction in ratchet.ci_restrictions:
                if restriction not in CI_RESTRICTION_VOCABULARY:
                    diags.append(_diag(E_INVALID_CI_RESTRICTION, gid,
                                       f"unknown ciRestriction "
                                       f"{restriction!r}"))

        previous = seen_entrypoints.get(spec.entrypoint)
        if previous is not None:
            diags.append(_diag(
                E_DUPLICATE_ENTRYPOINT, gid,
                f"entrypoint {spec.entrypoint!r} already registered by "
                f"{previous!r}"))
        else:
            seen_entrypoints[spec.entrypoint] = gid

        if repo_root is not None:
            entrypoint_abs = os.path.normpath(
                os.path.join(repo_root, spec.entrypoint))
            if not os.path.isfile(entrypoint_abs):
                diags.append(_diag(E_ENTRYPOINT_UNRESOLVED, gid,
                                   f"entrypoint not found under repo root: "
                                   f"{spec.entrypoint!r}"))
            for declared in spec.required_inputs:
                resolved = os.path.normpath(os.path.join(repo_root, declared))
                if not os.path.isfile(resolved):
                    diags.append(_diag(E_INPUT_UNRESOLVED, gid,
                                       f"required input not found under repo "
                                       f"root: {declared!r}"))
            for test_path in spec.test_manifest:
                resolved = os.path.normpath(os.path.join(repo_root, test_path))
                if not os.path.isfile(resolved):
                    diags.append(_diag(E_INPUT_UNRESOLVED, gid,
                                       f"test manifest file not found under "
                                       f"repo root: {test_path!r}"))
            anchor_abs = os.path.normpath(
                os.path.join(repo_root, spec.documentation_anchor))
            if not os.path.isfile(anchor_abs):
                diags.append(_diag(E_INPUT_UNRESOLVED, gid,
                                   f"documentation anchor not found under "
                                   f"repo root: {spec.documentation_anchor!r}"))

    return tuple(diags)


# ── Compilation ──────────────────────────────────────────────────────────────────


def _validate_context(context: ExecutionContext) -> Tuple[PlanDiagnostic, ...]:
    diags: list = []
    root = context.repo_root
    if not isinstance(root, str) or not root or not os.path.isabs(root):
        diags.append(_diag(E_INVALID_CONTEXT, None,
                           "repo_root must be an absolute path"))
        return tuple(diags)
    if not os.path.isdir(root):
        diags.append(_diag(E_INVALID_CONTEXT, None,
                           "repo_root does not exist or is not a directory"))
        return tuple(diags)
    interpreter = context.interpreter_path
    if (not isinstance(interpreter, str) or not interpreter
            or not os.path.isabs(interpreter)):
        diags.append(_diag(E_INVALID_CONTEXT, None,
                           "interpreter_path must be an absolute resolved "
                           "path"))
        return tuple(diags)
    if interpreter in _BARE_PYTHON_NAMES:
        diags.append(_diag(E_BARE_PYTHON, None,
                           "interpreter_path must be the resolved runtime "
                           "interpreter, never bare python/python3 (rule 2)"))
        return tuple(diags)
    if context.output_dir is not None:
        if (not isinstance(context.output_dir, str)
                or not os.path.isabs(context.output_dir)):
            diags.append(_diag(E_INVALID_CONTEXT, None,
                               "output_dir must be an absolute path or None"))
            return tuple(diags)
    return tuple(diags)


def compile_guard_plan(
    guard_id: str,
    context: ExecutionContext,
    specs: Optional[Tuple[GuardSpec, ...]] = None,
) -> Tuple[Optional[ExecutionPlan], Tuple[PlanDiagnostic, ...]]:
    """Compile one guard spec + context into a resolved execution plan.

    Fail-closed: unknown guard id, missing execution section (no spec),
    invalid context, unresolvable required input/baseline/entrypoint, invalid
    template token, or a forbidden test override yields diagnostics and no
    plan.
    """
    diags: list = []
    if specs is None:
        specs, load_diags = load_guard_specs(DEFAULT_REGISTRY_PATH)
        diags.extend(d for d in load_diags
                     if d.guard_id in (None, guard_id))

    context_diags = _validate_context(context)
    diags.extend(context_diags)
    if any(d.severity == "error" for d in context_diags):
        return None, tuple(diags)

    spec = next((s for s in specs if s.guard_id == guard_id), None)
    if spec is None:
        diags.append(_diag(E_UNKNOWN_GUARD, guard_id,
                           "guard id not present in loaded guard specs"))
        return None, tuple(diags)

    # Structural re-check so compilation fails closed even when the caller
    # skipped validate_guard_specs.
    structural = validate_guard_specs((spec,))
    if any(d.severity == "error" for d in structural):
        diags.extend(structural)
        return None, tuple(diags)

    # Test-only overrides (rule 7): typed, root-contained, rejected in CI.
    overrides: Dict[str, str] = {}
    if context.test_only_overrides:
        if context.ci_mode:
            diags.append(_diag(E_TEST_OVERRIDE_IN_CI, guard_id,
                               "test-only input overrides are rejected in "
                               "production CI mode (rule 7)"))
            return None, tuple(diags)
        for key, value in context.test_only_overrides:
            if key not in spec.required_inputs:
                diags.append(_diag(E_OVERRIDE_UNKNOWN_KEY, guard_id,
                                   f"override key is not a declared required "
                                   f"input: {key!r}"))
                return None, tuple(diags)
            if not _is_root_contained(value, context.repo_root):
                diags.append(_diag(E_OVERRIDE_OUTSIDE_ROOT, guard_id,
                                   f"override path outside repo root for "
                                   f"input: {key!r}"))
                return None, tuple(diags)
            overrides[key] = os.path.normpath(os.path.abspath(value))

    # Timeout: profile semantics unless an explicit context override.
    if context.timeout_override is not None:
        if (isinstance(context.timeout_override, bool)
                or not isinstance(context.timeout_override, int)
                or context.timeout_override <= 0):
            diags.append(_diag(E_INVALID_TIMEOUT_OVERRIDE, guard_id,
                               "timeout override must be a positive integer "
                               "number of seconds"))
            return None, tuple(diags)
        timeout_seconds = context.timeout_override
    else:
        timeout_seconds = TIMEOUT_PROFILES[spec.timeout_profile]

    entrypoint_abs = os.path.normpath(
        os.path.join(context.repo_root, spec.entrypoint))
    if not os.path.isfile(entrypoint_abs):
        diags.append(_diag(E_ENTRYPOINT_UNRESOLVED, guard_id,
                           f"entrypoint not found under repo root: "
                           f"{spec.entrypoint!r}"))
        return None, tuple(diags)

    input_resolution: Dict[str, str] = {}
    for declared in spec.required_inputs:
        resolved = overrides.get(declared)
        if resolved is None:
            resolved = os.path.normpath(
                os.path.join(context.repo_root, declared))
        if not os.path.isfile(resolved):
            diags.append(_diag(E_INPUT_UNRESOLVED, guard_id,
                               f"required input not found under repo root: "
                               f"{declared!r}"))
            return None, tuple(diags)
        input_resolution[declared] = resolved

    placeholders: Dict[str, Optional[str]] = {
        "interpreter": context.interpreter_path,
        "entrypoint": entrypoint_abs,
        "output_dir": context.output_dir,
    }

    baseline_abs: Optional[str] = None
    protocol: Optional[int] = None
    if spec.ratchet is not None:
        protocol = spec.ratchet.finding_protocol
        baseline_abs = os.path.normpath(
            os.path.join(context.repo_root, spec.ratchet.baseline_path))
        if not os.path.isfile(baseline_abs):
            diags.append(_diag(E_BASELINE_UNRESOLVED, guard_id,
                               f"ratchet baseline not found under repo root: "
                               f"{spec.ratchet.baseline_path!r}"))
            return None, tuple(diags)

    if spec.engine not in _PYTHON_ENGINES:
        # gradle-native / external engines are registry-declared but not
        # compiled by this Python compiler (none exist in Slice 1).
        diags.append(_diag(E_ENGINE_NOT_COMPILABLE, guard_id,
                           f"engine {spec.engine!r} is not compilable by the "
                           f"Python plan compiler"))
        return None, tuple(diags)

    child_argv: Optional[Tuple[str, ...]] = None
    if spec.engine == ENGINE_PYTHON_RATCHET:
        child_tokens = [context.interpreter_path, entrypoint_abs]
        for token in spec.ratchet.child_argument_template:
            resolved = _resolve_template_token(
                token, placeholders, input_resolution, guard_id,
                "ratchet.childArgumentTemplate", diags)
            if resolved is None:
                return None, tuple(diags)
            child_tokens.append(resolved)
        child_argv = tuple(child_tokens)
        ratchet_script_abs = os.path.normpath(
            os.path.join(context.repo_root, RATCHET_ENTRYPOINT))
        # Ratchet outer argv: ratchet flags first, then the child command as
        # repeated single-token --command-arg=<value> entries (rule 3), with
        # explicit protocol intent (rule 4).  The ratchet-level child timeout
        # budget remains adapter policy (Slice 2), not registry arithmetic.
        outer_tokens = [
            context.interpreter_path,
            ratchet_script_abs,
            "--guard-name", spec.guard_id,
            "--baseline", baseline_abs,
            f"--finding-protocol={protocol}",
            "--fail-on-violation",
            "--ci-mode",
        ]
        outer_tokens.extend(f"{COMMAND_ARG_PREFIX}{token}" for token in child_argv)
        outer_argv = tuple(outer_tokens)
    else:
        direct_tokens = [context.interpreter_path, entrypoint_abs]
        for token in spec.arguments:
            resolved = _resolve_template_token(
                token, placeholders, input_resolution, guard_id,
                "arguments", diags)
            if resolved is None:
                return None, tuple(diags)
            direct_tokens.append(resolved)
        outer_argv = tuple(direct_tokens)

    plan = ExecutionPlan(
        guard_id=spec.guard_id,
        mode=spec.mode,
        engine=spec.engine,
        outer_argv=outer_argv,
        child_argv=child_argv,
        resolved_required_inputs=tuple(
            input_resolution[declared] for declared in spec.required_inputs),
        baseline=baseline_abs,
        timeout_seconds=timeout_seconds,
        protocol=protocol,
        output_contract=spec.output_contract,
        repo_root=os.path.abspath(context.repo_root),
    )
    return plan, tuple(diags)


def compile_static_suite_plan(
    context: ExecutionContext,
    specs: Optional[Tuple[GuardSpec, ...]] = None,
) -> Tuple[Tuple[ExecutionPlan, ...], Tuple[PlanDiagnostic, ...]]:
    """Compile every registered active guard, in registry order.

    Guards whose compilation fails are omitted from the returned plans with
    error diagnostics; callers (Slice 2 runners) must treat any error-severity
    diagnostic as an infrastructure failure (exit 2) and never fall back to a
    hand-maintained command list.
    """
    diags: list = []
    if specs is None:
        specs, load_diags = load_guard_specs(DEFAULT_REGISTRY_PATH)
        diags.extend(load_diags)
    diags.extend(validate_guard_specs(specs))

    plans: list = []
    for spec in specs:
        plan, compile_diags = compile_guard_plan(spec.guard_id, context,
                                                 specs=specs)
        diags.extend(compile_diags)
        if plan is not None:
            plans.append(plan)
    return tuple(plans), tuple(diags)


# ── Canonical form and persistence ───────────────────────────────────────────────


def canonicalize_plan_for_comparison(plan: ExecutionPlan) -> Dict[str, Any]:
    """Semantic equality form of a plan.

    Normalizes machine-specific spellings: the resolved interpreter becomes a
    placeholder (rule 2 guarantees token identity), and absolute paths under
    the plan's repo root become repo-relative.  Two plans are semantically
    equal iff their canonical forms are equal.
    """
    root = plan.repo_root

    def rel_value(value: str) -> str:
        return _relativize_against_root(value, root)

    def rel_token(token: str) -> str:
        if token.startswith(COMMAND_ARG_PREFIX):
            return COMMAND_ARG_PREFIX + rel_value(
                token[len(COMMAND_ARG_PREFIX):])
        return rel_value(token)

    outer = list(plan.outer_argv)
    child = list(plan.child_argv) if plan.child_argv is not None else None
    if plan.engine in _PYTHON_ENGINES:
        if outer:
            outer[0] = INTERPRETER_PLACEHOLDER
        if child:
            child[0] = INTERPRETER_PLACEHOLDER

    return {
        "guardId": plan.guard_id,
        "mode": plan.mode,
        "engine": plan.engine,
        "outerArgv": tuple(rel_token(token) for token in outer),
        "childArgv": (tuple(rel_token(token) for token in child)
                      if child is not None else None),
        "requiredInputs": tuple(rel_value(path)
                                for path in plan.resolved_required_inputs),
        "baseline": rel_value(plan.baseline)
        if plan.baseline is not None else None,
        "timeoutSeconds": plan.timeout_seconds,
        "protocol": plan.protocol,
        "outputContract": plan.output_contract,
    }


def write_plan_json(plan: ExecutionPlan, output_path: str) -> None:
    """Atomically write the deterministic canonical JSON form of a plan.

    The serialized form is the canonical (repo-relative, interpreter-
    placeholder) plan with sorted keys and a trailing newline, so the bytes
    are identical across machines and checkouts.  ``repo_root`` and absolute
    path spellings are never persisted.  The write is atomic
    (temp file + ``os.replace`` in the destination directory).
    """
    canonical = canonicalize_plan_for_comparison(plan)
    payload = {"schemaVersion": PLAN_JSON_SCHEMA_VERSION, "plan": canonical}
    text = json.dumps(payload, indent=2, sort_keys=True, ensure_ascii=False)
    text += "\n"

    destination = os.path.abspath(output_path)
    parent = os.path.dirname(destination)
    os.makedirs(parent, exist_ok=True)
    fd, tmp_name = tempfile.mkstemp(dir=parent,
                                    prefix=os.path.basename(destination) + ".",
                                    suffix=".tmp")
    try:
        with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as handle:
            handle.write(text)
        os.replace(tmp_name, destination)
    except BaseException:
        with suppress(OSError):
            os.remove(tmp_name)
        raise
