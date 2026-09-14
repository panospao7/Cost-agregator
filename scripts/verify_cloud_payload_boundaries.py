#!/usr/bin/env python3
"""
verify_cloud_payload_boundaries.py
G-CLOUD-01 — Cloud Payload Fail-Closed Guard

Scans the declared production Kotlin source scope (the roots of the
checked-in manifest ``config/guards/production_source_roots.yml`` via
``scripts/guardrails/production_source_scope.py`` — currently
``app/src/main/java``), enumerating EVERY declared production file and then
applying the guard-specific allowlist/package semantic filter, for cloud
upload/payload paths that bypass the
central fail-closed policy (CloudPayloadPolicy / PreparedCloudPayload).

Detection patterns:
  R1  Direct RequestBody.create(...) outside allowlisted files.
      No production code should construct raw OkHttp RequestBody objects
      without routing through the policy.

  R2  Request.Builder() + .post(...) in data/ai/provider/ package
      without a reference to CloudPayloadPolicy, PreparedCloudPayload,
      or a cloudPayloadPolicy field. Every cloud provider that sends
      data to a cloud AI endpoint MUST use the policy.

Exit codes:
  0 — no violations (or warning mode without --fail-on-violation)
  1 — violations found AND --fail-on-violation is set
  2 — script error (e.g. production source scope unresolved, source
      directory not found)

Usage:
  python3 scripts/verify_cloud_payload_boundaries.py
  python3 scripts/verify_cloud_payload_boundaries.py --fail-on-violation
"""

import argparse
import os
import re
import sys
from pathlib import Path
from typing import List, Dict, Optional, Tuple

if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8")

# ── Configuration ──────────────────────────────────────────────────────────
RULE_ID = "G-CLOUD-01"
DESCRIPTION = "Cloud Payload Fail-Closed Guard"

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
PROJECT_ROOT = os.path.dirname(SCRIPT_DIR)
if SCRIPT_DIR not in sys.path:
    sys.path.insert(0, SCRIPT_DIR)

from guardrails.production_source_scope import (  # noqa: E402
    ProductionSourceScopeError,
    iter_production_kotlin_files,
    resolve_production_source_scope,
)

ALLOWLIST_PATH = os.path.join(SCRIPT_DIR, "allowlists", "cloud_payload_allowlist.yml")

FILE_PATTERNS = ["*.kt"]
SKIP_DIRS = {"test", "androidTest", "migration", "generated", "build"}

# Package path for cloud AI providers (normalised forward-slash suffix)
CLOUD_PROVIDER_PKG = "data/ai/provider"

# ── Regex patterns ─────────────────────────────────────────────────────────

# R1: RequestBody.create(
REQUEST_BODY_CREATE_RE = re.compile(r'RequestBody\s*\.\s*create\s*\(')

# R2: Request.Builder() and .post( patterns
REQUEST_BUILDER_RE = re.compile(r'Request\s*\.\s*Builder\s*\(\s*\)')
POST_CALL_RE = re.compile(r'\.post\s*\(')

# Policy markers that indicate compliance
POLICY_MARKERS_RE = re.compile(
    r'(?:\bCloudPayloadPolicy\b|\bPreparedCloudPayload\b|\bcloudPayloadPolicy\b)'
)


# ── Violation formatting ───────────────────────────────────────────────────

def violation(rel_path: str, lineno: int, reason: str) -> str:
    """Format a violation string."""
    return f"{RULE_ID} {rel_path}:{lineno} {reason}"


# ── Allowlist ──────────────────────────────────────────────────────────────

def load_allowlist(path: str) -> List[dict]:
    """Load allowlist entries from a YAML file.

    Each entry is a dict with keys:
        rule, path, symbol, reason, owner, expires, linked_issue

    Exits with code 2 on infrastructure errors (missing PyYAML, malformed YAML).
    """
    allowlist: List[dict] = []
    if not os.path.exists(path):
        return allowlist
    try:
        import yaml
        with open(path, "r", encoding="utf-8") as f:
            data = yaml.safe_load(f)
        if data and isinstance(data, list):
            allowlist = data
    except ImportError:
        print("ERROR: PyYAML not installed. pip install pyyaml", file=sys.stderr)
        sys.exit(2)
    except yaml.YAMLError as e:
        print(f"ERROR: Malformed allowlist: {e}", file=sys.stderr)
        sys.exit(2)
    except Exception as e:
        print(f"ERROR: Could not load allowlist: {e}", file=sys.stderr)
        sys.exit(2)
    return allowlist


def is_allowlisted(filepath: str, symbol: str, allowlist: List[dict]) -> bool:
    """Check if a file:symbol is in the allowlist.

    Supports partial path matching: the allowlist path is matched as a suffix
    on the full project-relative filepath, and vice versa.
    """
    for entry in allowlist:
        entry_rule = entry.get("rule", "")
        if entry_rule and entry_rule != RULE_ID:
            continue
        entry_path = entry.get("path", "")
        if not entry_path:
            continue
        # Only match if the allowlisted path is a suffix of the actual file path
        if filepath.endswith(entry_path):
            entry_symbol = entry.get("symbol", "")
            if not symbol or not entry_symbol or entry_symbol == symbol or entry_symbol == "*":
                return True
    return False


# ── Violation detection ────────────────────────────────────────────────────

def scan_file(filepath: str, rel_path: str) -> List[str]:
    """Scan a single Kotlin file for G-CLOUD-01 violations.

    Returns a list of formatted violation strings.
    """
    violations: List[str] = []

    try:
        with open(filepath, "r", encoding="utf-8", errors="replace") as f:
            content = f.read()
    except OSError:
        return violations

    lines = content.splitlines()
    norm_path = rel_path.replace("\\", "/")

    # ── R1: RequestBody.create( anywhere ────────────────────────────────
    for i, line in enumerate(lines, 1):
        stripped = line.strip()
        # Skip comment lines
        if stripped.startswith("//") or stripped.startswith("*") or stripped.startswith("/*"):
            continue
        if REQUEST_BODY_CREATE_RE.search(line):
            violations.append(
                violation(rel_path, i,
                          f"RequestBody.create() used directly — route through "
                          f"CloudPayloadPolicy instead of building raw OkHttp bodies")
            )

    # ── R2: Cloud provider POST without policy marker ───────────────────
    if CLOUD_PROVIDER_PKG in norm_path:
        has_request_builder = bool(REQUEST_BUILDER_RE.search(content))
        has_post = bool(POST_CALL_RE.search(content))
        has_policy = bool(POLICY_MARKERS_RE.search(content))

        if has_request_builder and has_post and not has_policy:
            # Find the first Request.Builder() or .post( line for line reference
            for i, line in enumerate(lines, 1):
                stripped = line.strip()
                if stripped.startswith("//") or stripped.startswith("*"):
                    continue
                if REQUEST_BUILDER_RE.search(line) or POST_CALL_RE.search(line):
                    violations.append(
                        violation(rel_path, i,
                                  f"Cloud provider constructs Request.Builder().post(...) "
                                  f"without CloudPayloadPolicy/PreparedCloudPayload — "
                                  f"policy bypass detected")
                    )
                    break  # one violation per file for this rule

    return violations


# ── Main ───────────────────────────────────────────────────────────────────

def main():
    parser = argparse.ArgumentParser(description=DESCRIPTION)
    parser.add_argument("--root", default=PROJECT_ROOT,
                        help="Project root directory")
    parser.add_argument("--fail-on-violation", action="store_true",
                        help="Exit with code 1 on violations")
    parser.add_argument("--allowlist", default=ALLOWLIST_PATH,
                        help="Path to allowlist file")
    args = parser.parse_args()

    root = args.root

    # PR-GR-10B: resolve the production source scope from the checked-in
    # manifest (fail closed — no conventional-root fallback).
    root_set, scope_diagnostics = resolve_production_source_scope(str(root))
    if root_set is None:
        codes = ", ".join(sorted({code for code, _ctx in scope_diagnostics}))
        print(
            f"ERROR: production source scope unresolved: {codes}",
            file=sys.stderr,
        )
        sys.exit(2)

    # Fail-closed: missing configured allowlist is fatal
    if not os.path.exists(args.allowlist):
        print(f"ERROR: Allowlist not found: {args.allowlist}", file=sys.stderr)
        sys.exit(2)

    allowlist = load_allowlist(args.allowlist)

    all_violations: List[str] = []

    try:
        source_files = list(iter_production_kotlin_files(str(root), root_set))
    except ProductionSourceScopeError as exc:
        print(
            f"ERROR: production source enumeration failed: {exc.code}",
            file=sys.stderr,
        )
        sys.exit(2)

    for source_file in source_files:
        filepath = source_file.absolute_path
        rel_path = source_file.repository_relative_path

        file_violations = scan_file(filepath, rel_path)

        # Filter out allowlisted entries
        for v in file_violations:
            # Violation format: RULE_ID rel_path:line message
            parts = v.split(" ", 2)
            if len(parts) >= 2:
                fpath_part = parts[1]
                fpath = fpath_part.rsplit(":", 1)[0] if ":" in fpath_part else fpath_part
                if not is_allowlisted(fpath, "", allowlist):
                    all_violations.append(v)
            else:
                all_violations.append(v)

    if all_violations:
        for v in all_violations:
            print(v)
        print(f"\nFound {len(all_violations)} violation(s).")

        if args.fail_on_violation:
            print(f"FAIL: {RULE_ID} violations (--fail-on-violation set)", file=sys.stderr)
            sys.exit(1)
        else:
            print(f"WARNING: {RULE_ID} violations (pass --fail-on-violation to fail CI)",
                  file=sys.stderr)
            sys.exit(0)
    else:
        print(f"PASS: {RULE_ID} — all cloud payloads comply with fail-closed policy")
        sys.exit(0)


if __name__ == "__main__":
    main()
