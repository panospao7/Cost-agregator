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
import okhttp3.RequestBody.Companion.toRequestBody

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

_POLICY_KT = """\
package com.yourname.expensetracker.domain.privacy

import okhttp3.RequestBody

interface CloudPayloadPolicy {
    fun prepareText(rawText: String): PreparedCloudPayload
    fun demonstrateCreatePattern() {
        RequestBody.create(null, "")
    }
}
"""

_POLICY_REL = "domain/privacy/CloudPayloadPolicy.kt"
_LEGACY_REL = "data/sync/LegacyCloudPayloadPolicy.kt"


def _entry(path=_POLICY_REL, symbol=_mod.SYMBOL_R1_RAW_REQUEST_BODY, rule=None):
    return {
        "rule": RULE_ID if rule is None else rule,
        "path": path,
        "symbol": symbol,
        "reason": "test",
        "owner": "test",
        "expires": "2027-01-01",
    }


def _surviving(tmp_path, rel_path, allowlist, content=_POLICY_KT):
    """Scan rel_path content and return violations that survive the allowlist,
    mirroring the filtering main() applies (rel_path + finding symbol)."""
    subdir, filename = os.path.split(rel_path)
    full = _write_kt(tmp_path, subdir, filename, content)
    findings = _mod.scan_file_findings(full, rel_path)
    assert findings, "fixture must produce at least one finding"
    return [v for _rule, symbol, v in findings
            if not is_allowlisted(rel_path, symbol, allowlist)]


def test_scan_file_findings_reports_rule_symbol_and_violation(tmp_path):
    full = _write_kt(tmp_path, "domain/privacy", "CloudPayloadPolicy.kt", _POLICY_KT)
    findings = _mod.scan_file_findings(full, _POLICY_REL)
    assert [(r, s) for r, s, _v in findings] == [(RULE_ID, "raw_RequestBody")]
    assert scan_file(full, _POLICY_REL) == [v for _r, _s, v in findings]


def test_allowlisted_file_passes(tmp_path):
    """An exact rule + path + symbol entry filters the finding out."""
    allowlist_path = _yaml(tmp_path, f"""\
- rule: {RULE_ID}
  path: {_POLICY_REL}
  symbol: "raw_RequestBody"
  reason: "Central fail-closed policy — canonical cloud payload construction"
  owner: "@panospao7"
  expires: "2027-01-01"
  linked_issue: "MIT-003"
""")
    allowlist = load_allowlist(allowlist_path)
    assert _surviving(tmp_path, _POLICY_REL, allowlist) == []


def test_suffix_entry_does_not_exempt_similarly_named_file(tmp_path):
    """Fixture A: R1 in data/sync/LegacyCloudPayloadPolicy.kt stays a
    violation although the entry path CloudPayloadPolicy.kt is a string suffix."""
    allowlist = [_entry(path="CloudPayloadPolicy.kt")]
    assert _LEGACY_REL.endswith("CloudPayloadPolicy.kt")
    surviving = _surviving(tmp_path, _LEGACY_REL, allowlist)
    assert len(surviving) == 1
    assert "RequestBody.create()" in surviving[0]


def test_basename_only_entry_path_does_not_match_nested_file():
    assert not is_allowlisted(_POLICY_REL, "raw_RequestBody",
                              [_entry(path="CloudPayloadPolicy.kt")])


def test_exact_path_symbol_and_rule_matches():
    assert is_allowlisted(_POLICY_REL, "raw_RequestBody", [_entry()])


@pytest.mark.parametrize("entry_symbol", ["*", "", None])
def test_wildcard_or_empty_entry_symbol_never_matches(entry_symbol):
    entry = _entry()
    if entry_symbol is None:
        del entry["symbol"]
    else:
        entry["symbol"] = entry_symbol
    assert not is_allowlisted(_POLICY_REL, "raw_RequestBody", [entry])


@pytest.mark.parametrize("finding_symbol", ["", "*"])
def test_empty_or_wildcard_finding_symbol_never_matches(finding_symbol):
    assert not is_allowlisted(_POLICY_REL, finding_symbol, [_entry()])
    assert not is_allowlisted(_POLICY_REL, finding_symbol, [_entry(symbol="*")])


@pytest.mark.parametrize("rule", ["", None, "G-CANCEL-01", "g-cloud-01"])
def test_empty_or_wrong_rule_never_matches(rule):
    entry = _entry()
    if rule is None:
        del entry["rule"]
    else:
        entry["rule"] = rule
    assert not is_allowlisted(_POLICY_REL, "raw_RequestBody", [entry])


def test_r1_entry_does_not_exempt_r2_finding():
    r1_entry = _entry(symbol=_mod.SYMBOL_R1_RAW_REQUEST_BODY)
    assert _mod.SYMBOL_R1_RAW_REQUEST_BODY == "raw_RequestBody"
    assert _mod.SYMBOL_R2_UNPROVED_POST_BODY == "unproved_post_body"
    provider = "data/ai/provider/ExampleProvider.kt"
    assert not is_allowlisted(provider, _mod.SYMBOL_R2_UNPROVED_POST_BODY,
                              [_entry(path=provider, symbol=_mod.SYMBOL_R1_RAW_REQUEST_BODY)])
    assert is_allowlisted(provider, _mod.SYMBOL_R2_UNPROVED_POST_BODY,
                          [_entry(path=provider, symbol=_mod.SYMBOL_R2_UNPROVED_POST_BODY)])
    assert not is_allowlisted(_POLICY_REL, _mod.SYMBOL_R2_UNPROVED_POST_BODY, [r1_entry])


@pytest.mark.parametrize("entry_path", [
    "/domain/privacy/CloudPayloadPolicy.kt",
    "C:/domain/privacy/CloudPayloadPolicy.kt",
    "C:\\domain\\privacy\\CloudPayloadPolicy.kt",
    "../domain/privacy/CloudPayloadPolicy.kt",
    "domain/../domain/privacy/CloudPayloadPolicy.kt",
    "domain/privacy/*.kt",
    "domain/*/CloudPayloadPolicy.kt",
    "*",
    "",
    "domain//privacy/CloudPayloadPolicy.kt",
])
def test_unsafe_entry_paths_never_match(entry_path):
    assert not is_allowlisted(_POLICY_REL, "raw_RequestBody", [_entry(path=entry_path)])
    assert not is_allowlisted(entry_path, "raw_RequestBody", [_entry(path=entry_path)])


def test_backslash_separators_are_normalised_on_both_sides():
    assert is_allowlisted("domain\\privacy\\CloudPayloadPolicy.kt", "raw_RequestBody", [_entry()])
    assert is_allowlisted(_POLICY_REL, "raw_RequestBody",
                          [_entry(path="domain\\privacy\\CloudPayloadPolicy.kt")])


def test_non_dict_and_non_string_entries_are_ignored():
    assert not is_allowlisted(_POLICY_REL, "raw_RequestBody",
                              ["x", None, _entry(path=7)])


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
