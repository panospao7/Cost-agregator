"""
test_verify_cloud_payload_boundaries.py
Acceptance tests for the static Cloud Payload Fail-Closed Guard (G-CLOUD-01).

4 test cases:
  1. Raw RequestBody outside policy FAILS
  2. PreparedCloudPayload path PASSES
  3. Policy-gated code PASSES
  4. Allowlisted file PASSES

Run with: python -m pytest scripts/test_verify_cloud_payload_boundaries.py -v
"""
import os
import sys
import tempfile
import pytest

# Import the module under test directly
sys.path.insert(0, os.path.dirname(__file__))
import importlib.util

_spec = importlib.util.spec_from_file_location(
    "guard", os.path.join(os.path.dirname(__file__), "verify_cloud_payload_boundaries.py")
)
_mod = importlib.util.module_from_spec(_spec)
import unittest.mock as _mock
with _mock.patch("builtins.__import__", side_effect=__import__):
    _spec.loader.exec_module(_mod)

scan_file = _mod.scan_file
load_allowlist = _mod.load_allowlist
is_allowlisted = _mod.is_allowlisted
RULE_ID = _mod.RULE_ID
CLOUD_PROVIDER_PKG = _mod.CLOUD_PROVIDER_PKG


def _write_kt(tmp_path, subdir, filename, content):
    """Write a .kt file in the given temp directory."""
    full_dir = os.path.join(str(tmp_path), subdir)
    os.makedirs(full_dir, exist_ok=True)
    filepath = os.path.join(full_dir, filename)
    with open(filepath, "w", encoding="utf-8") as f:
        f.write(content)
    return filepath


def _yaml(tmp_path, content):
    """Write a YAML allowlist file."""
    p = os.path.join(str(tmp_path), "allowlist.yml")
    with open(p, "w", encoding="utf-8") as f:
        f.write(content)
    return p


# ── Test 1: Raw RequestBody outside policy FAILS ──────────────────────────

def test_raw_request_body_outside_policy_fails(tmp_path):
    """A file using RequestBody.create() directly must be flagged."""
    subdir = "some/package"
    _write_kt(tmp_path, subdir, "BypassSender.kt", """\
package com.example.bypass

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody

class BypassSender {
    fun send(payload: String) {
        val body = RequestBody.create("application/json".toMediaType(), payload)
        val request = Request.Builder()
            .url("https://api.example.com")
            .post(body)
            .build()
    }
}
""")

    rel_path = os.path.join(subdir, "BypassSender.kt").replace("\\", "/")
    violations = scan_file(
        os.path.join(str(tmp_path), subdir, "BypassSender.kt"),
        rel_path
    )

    assert len(violations) > 0, (
        f"Expected violations for raw RequestBody.create(), got {len(violations)}"
    )
    has_r1 = any("RequestBody.create()" in v for v in violations)
    assert has_r1, (
        f"Expected R1 violation about RequestBody.create(), got: {violations}"
    )


# ── Test 2: PreparedCloudPayload path PASSES ─────────────────────────────

def test_prepared_cloud_payload_path_passes(tmp_path):
    """A cloud provider that uses PreparedCloudPayload must pass."""
    subdir = os.path.join("data", "ai", "provider")
    content = """\
package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.privacy.CloudPayloadPolicy
import com.yourname.expensetracker.domain.privacy.PreparedCloudPayload
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request

class CompliantCloudService(
    private val cloudPayloadPolicy: CloudPayloadPolicy
) {
    suspend fun query(input: String): String {
        val prepared: PreparedCloudPayload = cloudPayloadPolicy.prepareText(
            purpose = CloudPayloadPurpose.GENERAL,
            rawText = input
        )
        val json = buildJsonBody(prepared)
        val request = Request.Builder()
            .url("https://api.example.com")
            .post(json.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .build()
        return "ok"
    }

    private fun buildJsonBody(prepared: PreparedCloudPayload): String {
        return "{\\\"text\\\": \\\"${prepared.text}\\\"}"
    }
}
"""
    _write_kt(tmp_path, subdir, "CompliantCloudService.kt", content)

    rel_path = os.path.join(subdir, "CompliantCloudService.kt").replace("\\", "/")
    violations = scan_file(
        os.path.join(str(tmp_path), subdir, "CompliantCloudService.kt"),
        rel_path
    )

    assert len(violations) == 0, (
        f"Expected no violations for policy-compliant cloud provider, got: {violations}"
    )


# ── Test 3: Policy-gated code PASSES ─────────────────────────────────────

def test_policy_gated_code_passes(tmp_path):
    """A file that uses CloudPayloadPolicy for body construction must pass."""
    subdir = os.path.join("data", "ai", "provider")
    content = """\
package com.yourname.expensetracker.data.ai.provider

import com.yourname.expensetracker.domain.privacy.CloudPayloadPolicy
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class PolicyGatedSender(
    private val cloudPayloadPolicy: CloudPayloadPolicy
) {
    suspend fun send(data: String) {
        val prepared = cloudPayloadPolicy.prepareText(
            purpose = CloudPayloadPurpose.GENERAL,
            rawText = data
        )
        val body = prepared.text.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://api.example.com")
            .post(body)
            .build()
    }
}

// Dummy purpose for compilation reference
enum class CloudPayloadPurpose { GENERAL }
"""
    _write_kt(tmp_path, subdir, "PolicyGatedSender.kt", content)

    rel_path = os.path.join(subdir, "PolicyGatedSender.kt").replace("\\", "/")
    violations = scan_file(
        os.path.join(str(tmp_path), subdir, "PolicyGatedSender.kt"),
        rel_path
    )

    assert len(violations) == 0, (
        f"Expected no violations for policy-gated sender, got: {violations}"
    )


# ── Test 4: Allowlisted file PASSES ──────────────────────────────────────

def test_allowlisted_file_passes(tmp_path):
    """An allowlisted file with RequestBody.create() should be filtered out."""
    subdir = "domain/privacy"
    _write_kt(tmp_path, subdir, "CloudPayloadPolicy.kt", """\
package com.yourname.expensetracker.domain.privacy

import okhttp3.RequestBody

// This file is the canonical policy — allowlisted in cloud_payload_allowlist.yml
interface CloudPayloadPolicy {
    fun prepareText(rawText: String): PreparedCloudPayload
    fun demonstrateCreatePattern() {
        // Demonstration only — this would normally be flagged but is allowlisted
        RequestBody.create(null, "")
    }
}
""")

    allowlist_content = f"""\
- rule: {RULE_ID}
  path: CloudPayloadPolicy.kt
  symbol: "*"
  reason: "Central fail-closed policy — canonical cloud payload construction"
  owner: "@panospao7"
  expires: "permanent"
  linked_issue: "MIT-003"
"""
    allowlist_path = _yaml(tmp_path, allowlist_content)
    allowlist = load_allowlist(allowlist_path)

    rel_path = os.path.join(subdir, "CloudPayloadPolicy.kt").replace("\\", "/")
    violations = scan_file(
        os.path.join(str(tmp_path), subdir, "CloudPayloadPolicy.kt"),
        rel_path
    )

    # Filter violations through allowlist
    filtered = []
    for v in violations:
        parts = v.split(" ", 2)
        if len(parts) >= 2:
            fpath_part = parts[1]
            fpath = fpath_part.rsplit(":", 1)[0] if ":" in fpath_part else fpath_part
            if not is_allowlisted(fpath, "", allowlist):
                filtered.append(v)
        else:
            filtered.append(v)

    assert len(filtered) == 0, (
        f"Expected no filtered violations for allowlisted file, got: {filtered}"
    )


# ── PR-GR-10B source-scope contract ─────────────────────────────────────────
# The guard's scan scope is the checked-in production source-root manifest
# (config/guards/production_source_roots.yml), resolved via
# scripts/guardrails/production_source_scope.py — fail closed with exit 2
# when it is missing/malformed/undeclared; cloud-payload relevance is a
# semantic filter applied AFTER enumerating every declared production file.

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


_CLOUD_VIOLATION_KT = (
    "package com.example.bypass\n"
    "\n"
    "import okhttp3.MediaType.Companion.toMediaType\n"
    "import okhttp3.RequestBody\n"
    "\n"
    "class BypassSender {\n"
    "    fun send(payload: String) {\n"
    "        val body = RequestBody.create(\"application/json\".toMediaType(), payload)\n"
    "    }\n"
    "}\n"
)

_CLEAN_KT = "package com.example\nclass Clean { fun f() = 1 }\n"


def _scope_run_main(root, monkeypatch, capsys, extra=()):
    monkeypatch.setattr(
        sys, "argv",
        ["verify_cloud_payload_boundaries.py", "--root", str(root)] + list(extra),
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
    are identical to the pre-GR-10B hard-coded-root era: a raw
    RequestBody.create() under the declared root is flagged."""
    _write_scope_manifest(tmp_path)
    _write_declared(
        tmp_path,
        "app/src/main/java/com/example/bypass/BypassSender.kt",
        _CLOUD_VIOLATION_KT,
    )

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys, ["--fail-on-violation"])

    assert code == 1
    assert "BypassSender.kt" in out.out
    assert "RequestBody.create()" in out.out


def test_missing_manifest_fails_closed(tmp_path, monkeypatch, capsys):
    """No checked-in manifest -> exit 2 (no conventional-root fallback)."""
    _write_declared(
        tmp_path,
        "app/src/main/java/com/example/bypass/BypassSender.kt",
        _CLOUD_VIOLATION_KT,
    )

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys)

    assert code == 2
    assert "production source scope unresolved" in out.err


def test_undeclared_root_file_is_never_scanned(tmp_path, monkeypatch, capsys):
    """Kotlin files outside the declared production roots are invisible."""
    _write_scope_manifest(tmp_path)
    _write_declared(tmp_path, "app/src/main/java/com/example/Clean.kt", _CLEAN_KT)
    undeclared = (
        tmp_path / "other" / "src" / "main" / "java" / "com" / "example"
        / "bypass" / "BypassSender.kt"
    )
    undeclared.parent.mkdir(parents=True)
    undeclared.write_text(_CLOUD_VIOLATION_KT, encoding="utf-8")

    code, out = _scope_run_main(tmp_path, monkeypatch, capsys)

    assert code == 0
    assert "BypassSender" not in out.out
