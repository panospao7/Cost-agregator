#!/usr/bin/env python3
"""
verify_privacy_boundaries.py — Static Privacy Guards

Scans the Kotlin source tree for privacy boundary violations.

Usage:
    python3 scripts/verify_privacy_boundaries.py [--root <project-root>]

Exit codes:
    0  No violations found.
    1  One or more violations found (CI should fail).

Guard rules enforced:
    G1  No cloud provider may use AiSettings.redactBeforeCloud for redaction decisions.
    G2  No cloud provider may use settings/aiSettings.redactBeforeCloud for the cloud-send
        redaction decision (CloudPayloadPolicy is the sole authority). Response
        de-pseudonymization (`input.redactBeforeCloud`) and @VisibleForTesting helpers
        are exempt.
    G3  No Request.Builder().post(...) in cloud provider package unless the enclosing
        function derives its body from a PreparedCloudPayload / CloudPayloadPolicy.
    G4  No allow-all `object : PrivacyGate { ... Allowed }` in a production path. Allow-all
        gates inside @VisibleForTesting / secondary (test-only) constructors are exempt
        (mirrors the canonical Kotlin PrivacyGuardTest carve-out); FailClosed is always OK.
    G5  No String.hashCode() for message IDs / provider transaction IDs (comments exempt).
    G7  No debug export of rawOcrText without DEBUG_RAW_EXPORT / consent / STORE_RAW gate
        in the enclosing function.
    G8  encryptedBackupEnabled=false must NOT allow raw export (logic guard).
    G9  PrivacySettingsRepositoryImpl corruption handler must use mutablePreferencesOf sentinel.
    G10 SafePrivacyMetadata.put() must not store values without sanitization (value-safety check).
    G11 No .hashCode() in data/ai/provider for prompt redaction pseudonyms (comments exempt).
    G12 No empty-prompt prepareText probe.
    G13 Email side-effect dispatch must include correlationId.
    G14 External geocoding/nearby providers that depend on PrivacyGate must call
        privacyGate.check() (statically guarantees EXTERNAL_GEOCODING/OVERPASS_API gating).
"""

import os
import re
import sys
import argparse
from dataclasses import dataclass, field
from typing import List

_SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
if _SCRIPT_DIR not in sys.path:
    sys.path.insert(0, _SCRIPT_DIR)

from guardrails.production_source_scope import (  # noqa: E402
    is_declared_production_path,
    resolve_production_source_scope,
)

MAIN_SRC = "app/src/main/java/com/yourname/expensetracker"
AI_PROVIDER_PKG = f"{MAIN_SRC}/data/ai"
CLOUD_BACKUP_PKG = f"{MAIN_SRC}/data/backup"

# PR-GR-10B: MAIN_SRC is the guard's package-level semantic scope (a
# subtree of the declared production roots), not a root filter.  The root
# authority is the checked-in manifest
# ``config/guards/production_source_roots.yml`` resolved via
# ``scripts/guardrails/production_source_scope.py``: the repository-level
# guard fails closed (exit 2) when the manifest is missing/malformed or
# when MAIN_SRC is not a declared production path.  There is NO
# conventional-root fallback.


@dataclass
class Violation:
    rule: str
    file: str
    line_no: int
    line: str
    message: str


def find_kt_files(root: str, sub: str = "") -> List[str]:
    search_root = os.path.join(root, sub) if sub else root
    result = []
    for dirpath, _, filenames in os.walk(search_root):
        for fn in filenames:
            if fn.endswith(".kt"):
                result.append(os.path.join(dirpath, fn))
    return result


def scan_file(filepath: str, rules) -> List[Violation]:
    violations = []
    try:
        with open(filepath, "r", encoding="utf-8", errors="replace") as f:
            lines = f.readlines()
    except OSError:
        return violations

    for rule_fn in rules:
        violations.extend(rule_fn(filepath, lines))
    return violations


# ── Shared helpers ────────────────────────────────────────────────────────────

def _is_comment(line: str) -> bool:
    """True if the line is a Kotlin comment / KDoc line (not executable code)."""
    s = line.strip()
    return s.startswith("//") or s.startswith("*") or s.startswith("/*")


def _enclosing_function_text(lines: List[str], idx: int) -> str:
    """
    Returns the text of the function/constructor enclosing line [idx], using a
    brace-depth heuristic. Falls back to a +/-25 line window if no enclosing
    `fun`/`constructor` header can be found. Used to detect prepared-payload /
    consent / annotation context anywhere in the same function, not just within
    a fixed line window.
    """
    start = idx
    while start > 0:
        stripped = lines[start].lstrip()
        if (stripped.startswith("fun ") or stripped.startswith("private fun ")
                or stripped.startswith("internal fun ") or stripped.startswith("suspend fun ")
                or stripped.startswith("override ") or "constructor(" in stripped
                or stripped.startswith("@VisibleForTesting")):
            break
        start -= 1
    end = min(len(lines), idx + 25)
    start = max(0, start - 2)
    return "".join(lines[start:end])


# ── Rule implementations ──────────────────────────────────────────────────────

def rule_g1_ai_redact_before_cloud(filepath: str, lines: List[str]) -> List[Violation]:
    """G1: Cloud AI providers must not read AiSettings.redactBeforeCloud for policy."""
    violations = []
    # Only check cloud provider files
    if "data/ai" not in filepath.replace("\\", "/"):
        return violations
    pattern = re.compile(r'aiSettings.*\.redactBeforeCloud|redactBeforeCloud.*aiSettings')
    for i, line in enumerate(lines):
        if pattern.search(line) and not line.strip().startswith("//"):
            violations.append(Violation(
                rule="G1",
                file=filepath,
                line_no=i + 1,
                line=line.rstrip(),
                message="Cloud AI provider reading AiSettings.redactBeforeCloud — must use CloudPayloadPolicy instead"
            ))
    return violations


def rule_g2_input_redact_before_cloud(filepath: str, lines: List[str]) -> List[Violation]:
    """G2: Cloud AI providers must not use AiSettings/settings.redactBeforeCloud for the
    cloud-send redaction decision. Redaction authority is CloudPayloadPolicy only.

    NOT a violation:
      - `input.redactBeforeCloud` used to map a cloud RESPONSE back to real values
        (de-pseudonymization), which is not a cloud-send decision.
      - `@VisibleForTesting`/`*ForTest` helper functions.
      - comment lines.
    """
    violations = []
    if "data/ai" not in filepath.replace("\\", "/"):
        return violations
    # Allow DefaultCloudPayloadPolicy/EffectiveCloudAiPolicyResolver to inspect effective policy.
    if "DefaultCloudPayloadPolicy" in filepath or "EffectiveCloudAiPolicyResolver" in filepath:
        return violations
    # Only flag the cloud-send authority sources, NOT caller/response `input.redactBeforeCloud`.
    pattern = re.compile(r'(aiSettings|settings|ai)\s*\.\s*redactBeforeCloud|policyResolver\.resolve\(\)\.redactBeforeCloud')
    for i, line in enumerate(lines):
        if not pattern.search(line) or _is_comment(line):
            continue
        # Skip @VisibleForTesting / *ForTest helper scopes (test-only payload builders).
        ctx = _enclosing_function_text(lines, i)
        if "@VisibleForTesting" in ctx or "ForTest" in ctx:
            continue
        violations.append(Violation(
            rule="G2",
            file=filepath,
            line_no=i + 1,
            line=line.rstrip(),
            message="Cloud provider using settings.redactBeforeCloud directly — must use CloudPayloadPolicy"
        ))
    return violations


def rule_g3_raw_request_post_in_provider(filepath: str, lines: List[str]) -> List[Violation]:
    """G3: No Request.Builder().post(...) in cloud provider package unless body derives from PreparedCloudPayload."""
    violations = []
    norm = filepath.replace("\\", "/")
    if "data/ai/provider" not in norm:
        return violations
    # Allow test source
    if "src/test" in norm:
        return violations
    pattern = re.compile(r'Request\.Builder\(\)')
    for i, line in enumerate(lines):
        if pattern.search(line) and not _is_comment(line):
            # Check the enclosing function (not just a fixed window) for prepared-payload usage.
            context = _enclosing_function_text(lines, i)
            prepared_markers = (
                "PreparedCloudPayload", "prepared", "prepareText", "prepareReceiptAssist",
                "prepareBankStatementValidation", "buildRequestBody", "cloudPayloadPolicy",
                "redactor.redactText", "policy.prepare"
            )
            if not any(m in context for m in prepared_markers):
                violations.append(Violation(
                    rule="G3",
                    file=filepath,
                    line_no=i + 1,
                    line=line.rstrip(),
                    message="Request.Builder() in cloud provider without PreparedCloudPayload — use CloudPayloadPolicy"
                ))
    return violations


def rule_g4_allow_all_privacy_gate(filepath: str, lines: List[str]) -> List[Violation]:
    """G4: No object : PrivacyGate { ... Allowed } (allow-all) in real production paths.

    Mirrors the canonical Kotlin PrivacyGuardTest carve-out: allow-all gates inside
    @VisibleForTesting / secondary (test-only) constructors are acceptable because the
    DI-provided gate is the real one. FailClosed is always acceptable.
    """
    violations = []
    if "src/test" in filepath.replace("\\", "/"):
        return violations
    pattern = re.compile(r'object\s*:\s*PrivacyGate')
    for i, line in enumerate(lines):
        if pattern.search(line) and not _is_comment(line):
            # Allow-all check on the gate body — FailClosed is acceptable.
            context_end = min(len(lines), i + 5)
            body = "".join(lines[i:context_end])
            if "PrivacyDecision.Allowed" not in body or "FailClosed" in body:
                continue
            # Carve-out: test-only secondary constructors / @VisibleForTesting / named fakes.
            scope = _enclosing_function_text(lines, i)
            if ("@VisibleForTesting" in scope or "constructor(" in scope
                    or "fun noOpGate" in scope or "fun failClosedGate" in scope
                    or "ForTest" in scope):
                continue
            violations.append(Violation(
                rule="G4",
                file=filepath,
                line_no=i + 1,
                line=line.rstrip(),
                message="Allow-all PrivacyGate (returns Allowed) in production path — use FailClosed or proper implementation"
            ))
    return violations


def rule_g5_hashcode_for_ids(filepath: str, lines: List[str]) -> List[Violation]:
    """G5: No String.hashCode() for message IDs / provider transaction IDs."""
    violations = []
    pattern = re.compile(r'(messageId|providerTransactionId|transactionId|accountId|orderId).*\.hashCode\(\)|\.hashCode\(\).*(messageId|providerTransactionId|transactionId)')
    for i, line in enumerate(lines):
        if pattern.search(line) and not _is_comment(line):
            violations.append(Violation(
                rule="G5",
                file=filepath,
                line_no=i + 1,
                line=line.rstrip(),
                message="String.hashCode() used for sensitive ID — must use SensitiveHashingService.hmacSha256Prefix"
            ))
    return violations


def rule_g5b_hashcode_in_sanitizer(filepath: str, lines: List[str]) -> List[Violation]:
    """G5b: RawContentSanitizer must not use hashCode()."""
    violations = []
    if "RawContentSanitizer" not in filepath:
        return violations
    pattern = re.compile(r'\.hashCode\(\)')
    for i, line in enumerate(lines):
        if pattern.search(line) and not _is_comment(line):
            violations.append(Violation(
                rule="G5b",
                file=filepath,
                line_no=i + 1,
                line=line.rstrip(),
                message="RawContentSanitizer must not use String.hashCode() — use SensitiveHashingService"
            ))
    return violations


def rule_g7_raw_ocr_debug_export(filepath: str, lines: List[str]) -> List[Violation]:
    """G7: No debug export of rawOcrText without PrivacyGate(DEBUG_RAW_EXPORT)."""
    violations = []
    if "src/test" in filepath.replace("\\", "/"):
        return violations
    # Look for files that directly access rawOcrText in export/debug context
    if "export" not in filepath.lower() and "debug" not in filepath.lower() and "ExportAnonymizer" not in filepath:
        return violations
    pattern = re.compile(r'rawOcrText')
    for i, line in enumerate(lines):
        if pattern.search(line) and not _is_comment(line):
            # ExportAnonymizer is the dedicated sanitiser — exempt.
            if "ExportAnonymizer" in filepath:
                continue
            # Function-scope consent/storage-mode gate detection (not a fixed window).
            context = _enclosing_function_text(lines, i)
            gated = (
                "DEBUG_RAW_EXPORT" in context
                or "rawOcrStorageMode" in context
                or "includeRaw" in context
                or "exportConsent" in context
                or "STORE_RAW" in context
            )
            if not gated:
                violations.append(Violation(
                    rule="G7",
                    file=filepath,
                    line_no=i + 1,
                    line=line.rstrip(),
                    message="rawOcrText accessed in export/debug context without DEBUG_RAW_EXPORT / consent gate"
                ))
    return violations


def rule_g8_encrypted_disabled_implies_raw(filepath: str, lines: List[str]) -> List[Violation]:
    """G8: encryptedBackupEnabled=false must not directly allow raw export."""
    violations = []
    if "src/test" in filepath.replace("\\", "/"):
        return violations
    # Pattern: if !encryptedBackupEnabled followed by allowing raw export
    pattern = re.compile(r'!.*encryptedBackupEnabled.*Allowed|encryptedBackupEnabled.*false.*Allowed.*raw', re.IGNORECASE)
    for i, line in enumerate(lines):
        if pattern.search(line) and not line.strip().startswith("//"):
            violations.append(Violation(
                rule="G8",
                file=filepath,
                line_no=i + 1,
                line=line.rstrip(),
                message="encryptedBackupEnabled=false appears to allow raw export — this is not allowed"
            ))
    return violations


def rule_g9_corruption_handler_uses_sentinel(filepath: str, lines: List[str]) -> List[Violation]:
    """G9: PrivacySettingsRepositoryImpl corruption handler must use mutablePreferencesOf sentinel."""
    violations = []
    if "PrivacySettingsRepositoryImpl" not in filepath:
        return violations
    content = "".join(lines)
    # Must use mutablePreferencesOf with the CORRUPTED sentinel
    if "ReplaceFileCorruptionHandler" in content:
        if "mutablePreferencesOf" not in content or "CORRUPTED" not in content:
            violations.append(Violation(
                rule="G9",
                file=filepath,
                line_no=1,
                line="",
                message="PrivacySettingsRepositoryImpl corruption handler must use mutablePreferencesOf(LOAD_STATE_KEY to CORRUPTED)"
            ))
    return violations


def rule_g10_safe_metadata_value_sanitization(filepath: str, lines: List[str]) -> List[Violation]:
    """G10: SafePrivacyMetadata must have value-level sanitization (SENSITIVE_VALUE_PATTERNS)."""
    violations = []
    if "SafePrivacyMetadata" not in filepath:
        return violations
    content = "".join(lines)
    if "SENSITIVE_VALUE_PATTERNS" not in content:
        violations.append(Violation(
            rule="G10",
            file=filepath,
            line_no=1,
            line="",
            message="SafePrivacyMetadata must define SENSITIVE_VALUE_PATTERNS for value-level sanitization"
        ))
    return violations


def rule_g11_hashcode_in_provider_prompt(filepath: str, lines: List[str]) -> List[Violation]:
    """G11: No .hashCode() in data/ai/provider for prompt redaction pseudonyms."""
    violations = []
    norm = filepath.replace("\\", "/")
    if "data/ai/provider" not in norm:
        return violations
    if "src/test" in norm:
        return violations
    pattern = re.compile(r'\.hashCode\(\)')
    for i, line in enumerate(lines):
        if pattern.search(line) and not _is_comment(line):
            violations.append(Violation(
                rule="G11",
                file=filepath,
                line_no=i + 1,
                line=line.rstrip(),
                message="hashCode() in cloud provider — use SensitiveHashingService.hmacSha256Prefix() for pseudonyms"
            ))
    return violations


def rule_g12_empty_prompt_policy_probe(filepath: str, lines: List[str]) -> List[Violation]:
    """G12: No cloudPayloadPolicy.prepareText(..., "") in main source — empty-prompt probes are forbidden."""
    violations = []
    if "src/test" in filepath.replace("\\", "/"):
        return violations
    pattern = re.compile(r'prepareText\s*\(.*,\s*""\s*\)')
    for i, line in enumerate(lines):
        if pattern.search(line) and not line.strip().startswith("//"):
            violations.append(Violation(
                rule="G12",
                file=filepath,
                line_no=i + 1,
                line=line.rstrip(),
                message="Empty-prompt prepareText probe — use real prompt, not empty string"
            ))
    return violations


def rule_g13_email_side_effect_without_correlation(filepath: str, lines: List[str]) -> List[Violation]:
    """G13: dispatchPostCreationSideEffects(expenseId, ExpenseSource.EMAIL_RECEIPT) must include correlationId."""
    violations = []
    if "src/test" in filepath.replace("\\", "/"):
        return violations
    pattern = re.compile(r'dispatchPostCreationSideEffects\s*\(\s*\w+\s*,\s*ExpenseSource\.EMAIL_RECEIPT\s*\)')
    for i, line in enumerate(lines):
        if pattern.search(line) and not line.strip().startswith("//"):
            violations.append(Violation(
                rule="G13",
                file=filepath,
                line_no=i + 1,
                line=line.rstrip(),
                message="Email side-effect dispatch missing correlationId — use dispatchPostCreationSideEffects(id, source, correlationId)"
            ))
    return violations


def rule_g14_geocoding_provider_self_gates(filepath: str, lines: List[str]) -> List[Violation]:
    """G14: External location/geocoding providers must self-gate via PrivacyGate.

    Any non-test class in data/location whose name ends with GeocodingService or
    NearbyService (and is an external/network provider) MUST reference
    `privacyGate.check(` somewhere in the file. This statically guarantees that
    new providers cannot bypass EXTERNAL_GEOCODING / OVERPASS_API gating
    (closes the prior LocationPrivacyGate TODO).
    """
    violations = []
    norm = filepath.replace("\\", "/")
    if "data/location" not in norm or "src/test" in norm:
        return violations
    base = os.path.basename(norm)
    is_provider = (base.endswith("GeocodingService.kt") or base.endswith("NearbyService.kt"))
    if not is_provider:
        return violations
    content = "".join(lines)
    # The shared interfaces/aggregators are fine; only flag a concrete provider that
    # makes external calls (declares a PrivacyGate dependency is the expected pattern).
    declares_gate_dep = "privacyGate" in content or "PrivacyGate" in content
    if not declares_gate_dep:
        return violations  # not a privacy-aware provider type (e.g. pure interface) — skip
    if "privacyGate.check(" not in content:
        violations.append(Violation(
            rule="G14",
            file=filepath,
            line_no=1,
            line=base,
            message="Geocoding/Nearby provider declares PrivacyGate but never calls privacyGate.check() — must self-gate EXTERNAL_GEOCODING/OVERPASS_API"
        ))
    return violations


# ── Runner ───────────────────────────────────────────────────────────────────

ALL_RULES = [
    rule_g1_ai_redact_before_cloud,
    rule_g2_input_redact_before_cloud,
    rule_g3_raw_request_post_in_provider,
    rule_g4_allow_all_privacy_gate,
    rule_g5_hashcode_for_ids,
    rule_g5b_hashcode_in_sanitizer,
    rule_g7_raw_ocr_debug_export,
    rule_g8_encrypted_disabled_implies_raw,
    rule_g9_corruption_handler_uses_sentinel,
    rule_g10_safe_metadata_value_sanitization,
    rule_g11_hashcode_in_provider_prompt,
    rule_g12_empty_prompt_policy_probe,
    rule_g13_email_side_effect_without_correlation,
    rule_g14_geocoding_provider_self_gates,
]


def run(root: str) -> List[Violation]:
    all_violations: List[Violation] = []
    kt_files = find_kt_files(root, MAIN_SRC)
    for filepath in kt_files:
        all_violations.extend(scan_file(filepath, ALL_RULES))
    return all_violations


def main():
    parser = argparse.ArgumentParser(description="PR10 Privacy boundary static guard")
    parser.add_argument("--root", default=".", help="Project root directory")
    args = parser.parse_args()

    # Make output robust on consoles without UTF-8 (e.g. Windows cp1252).
    try:
        sys.stdout.reconfigure(encoding="utf-8", errors="replace")
    except Exception:
        pass

    root = os.path.abspath(args.root)
    print(f"Scanning privacy boundaries in: {root}")

    # PR-GR-10B: fail closed when the production source-root manifest is
    # missing/malformed or when the package-level scan scope is not a
    # declared production path (no conventional-root fallback).
    root_set, scope_diagnostics = resolve_production_source_scope(root)
    if root_set is None:
        codes = ", ".join(sorted({code for code, _ctx in scope_diagnostics}))
        print(
            f"ERROR: production source scope unresolved: {codes}",
            file=sys.stderr,
        )
        sys.exit(2)
    if not is_declared_production_path(root_set, MAIN_SRC):
        print(
            "ERROR: privacy scan scope is not a declared production path: "
            f"{MAIN_SRC}",
            file=sys.stderr,
        )
        sys.exit(2)

    violations = run(root)
    if not violations:
        print("[OK] No privacy boundary violations found.")
        sys.exit(0)
    else:
        print(f"\n[FAIL] Found {len(violations)} privacy boundary violation(s):\n")
        for v in violations:
            rel_path = os.path.relpath(v.file, root)
            print(f"  [{v.rule}] {rel_path}:{v.line_no}")
            print(f"         {v.message}")
            print(f"         > {v.line.strip()}\n")
        sys.exit(1)


if __name__ == "__main__":
    main()
