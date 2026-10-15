"""Both production guard entry points enforce the same POST-body proof."""
from pathlib import Path
from types import SimpleNamespace
import sys

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import verify_cloud_payload_boundaries as cloud
import verify_privacy_boundaries as privacy
from guardrails.production_source_scope import ProductionSourceScopeError


RELATIVE_PATH = "app/src/main/java/com/yourname/expensetracker/data/ai/provider/ExampleProvider.kt"


def source(prepared_body=True):
    body = "prepared.text" if prepared_body else "raw"
    return f"""package example
import com.yourname.expensetracker.domain.privacy.CloudPayloadPolicy
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
class ExampleProvider(private val cloudPayloadPolicy: CloudPayloadPolicy) {{
    suspend fun send(raw: String) {{
        val prepared = cloudPayloadPolicy.prepareText(CloudPayloadPurpose.GENERAL, raw)
        val body = {body}
        Request.Builder().post(body.toRequestBody()).build()
    }}
}}
"""


@pytest.mark.parametrize("prepared_body", [False, True])
@pytest.mark.parametrize("keepends", [False, True])
def test_both_frontends_check_actual_body_not_unused_policy_result(tmp_path, prepared_body, keepends):
    text = source(prepared_body)
    path = tmp_path / "ExampleProvider.kt"
    path.write_text(text, encoding="utf-8")
    expected_count = 0 if prepared_body else 1
    cloud_results = cloud.scan_file(str(path), RELATIVE_PATH)
    privacy_results = privacy.rule_g3_raw_request_post_in_provider(RELATIVE_PATH, text.splitlines(keepends=keepends))
    assert len(cloud_results) == expected_count
    assert len(privacy_results) == expected_count
    if not prepared_body:
        assert privacy_results[0].rule == "G3"
        assert privacy_results[0].line_no == next(
            i for i, line in enumerate(text.splitlines(), 1) if ".post(" in line
        )


def test_request_body_create_inside_comments_or_literals_is_not_executable(tmp_path):
    path = tmp_path / "CommentOnly.kt"
    path.write_text('''package example
// RequestBody.create(raw)
class CommentOnly {
    val documentation = "RequestBody.create(raw)"
}
''', encoding="utf-8")
    assert cloud.scan_file(str(path), "domain/privacy/CommentOnly.kt") == []


def test_request_body_create_executed_by_interpolation_is_not_hidden(tmp_path):
    path = tmp_path / "TemplateBody.kt"
    template = "$" + "{RequestBody.create(media, raw)}"
    path.write_text('class TemplateBody { val rendered = "' + template + '" }\n', encoding="utf-8")
    assert len(cloud.scan_file(str(path), "domain/privacy/TemplateBody.kt")) == 1


def test_privacy_frontend_rejects_unparseable_provider_source():
    with pytest.raises(ProductionSourceScopeError):
        privacy.rule_g3_raw_request_post_in_provider(
            RELATIVE_PATH, ['class ExampleProvider { val text = "unterminated']
        )


def _fixture_a_root(tmp_path, entry_path):
    """Fixture A: raw RequestBody.create() in data/sync/LegacyCloudPayloadPolicy.kt
    with an allowlist entry whose path is only a string suffix of that file."""
    manifest = tmp_path / "config" / "guards" / "production_source_roots.yml"
    manifest.parent.mkdir(parents=True)
    manifest.write_text(
        "schemaVersion: 1\nroots:\n  - module: ':app'\n    sourceSet: main\n"
        "    path: app/src/main/java\n",
        encoding="utf-8",
    )
    legacy = (tmp_path / "app" / "src" / "main" / "java" / "com" / "example"
              / "data" / "sync" / "LegacyCloudPayloadPolicy.kt")
    legacy.parent.mkdir(parents=True)
    legacy.write_text(
        "package com.example.data.sync\n"
        "import okhttp3.RequestBody\n"
        "class LegacyCloudPayloadPolicy {\n"
        "    fun f() { RequestBody.create(null, \"\") }\n"
        "}\n",
        encoding="utf-8",
    )
    allowlist = tmp_path / "allowlist.yml"
    allowlist.write_text(
        f"- rule: G-CLOUD-01\n  path: {entry_path}\n  symbol: raw_RequestBody\n"
        "  reason: test\n  owner: test\n  expires: '2027-01-01'\n",
        encoding="utf-8",
    )
    return allowlist


def _run_main(monkeypatch, capsys, root, allowlist):
    monkeypatch.setattr(sys, "argv", [
        "guard", "--root", str(root), "--allowlist", str(allowlist), "--fail-on-violation",
    ])
    with pytest.raises(SystemExit) as error:
        cloud.main()
    return error.value.code, capsys.readouterr()


def test_main_fails_for_suffix_only_allowlist_entry_fixture_a(tmp_path, monkeypatch, capsys):
    allowlist = _fixture_a_root(tmp_path, "CloudPayloadPolicy.kt")
    code, captured = _run_main(monkeypatch, capsys, tmp_path, allowlist)
    assert code == 1
    assert "LegacyCloudPayloadPolicy.kt" in captured.out
    assert "PASS" not in captured.out


def test_main_passes_only_for_exact_path_and_symbol_entry(tmp_path, monkeypatch, capsys):
    allowlist = _fixture_a_root(
        tmp_path, "app/src/main/java/com/example/data/sync/LegacyCloudPayloadPolicy.kt")
    code, captured = _run_main(monkeypatch, capsys, tmp_path, allowlist)
    assert code == 0
    assert "PASS" in captured.out


@pytest.mark.parametrize("failure", ["missing", "invalid_utf8", "malformed"])
@pytest.mark.parametrize("fail_on_violation", [False, True])
def test_cloud_cli_source_failure_exits_two_and_never_prints_pass(
    tmp_path, monkeypatch, capsys, failure, fail_on_violation
):
    path = tmp_path / "ExampleProvider.kt"
    if failure == "invalid_utf8":
        path.write_bytes(b"\xff\xfe\xff")
    elif failure == "malformed":
        path.write_text('class ExampleProvider { val text = "unterminated', encoding="utf-8")
    allowlist = tmp_path / "allowlist.yml"
    allowlist.write_text("[]\n", encoding="utf-8")
    monkeypatch.setattr(cloud, "resolve_production_source_scope", lambda _root: (object(), []))
    monkeypatch.setattr(cloud, "iter_production_kotlin_files", lambda *_args: iter([
        SimpleNamespace(absolute_path=str(path), repository_relative_path=RELATIVE_PATH)
    ]))
    monkeypatch.setattr(cloud, "load_allowlist", lambda _path: [])
    argv = ["guard", "--root", str(tmp_path), "--allowlist", str(allowlist)]
    if fail_on_violation:
        argv.append("--fail-on-violation")
    monkeypatch.setattr(sys, "argv", argv)
    with pytest.raises(SystemExit) as error:
        cloud.main()
    assert error.value.code == 2
    captured = capsys.readouterr()
    assert "CLOUD_PAYLOAD_SOURCE_UNREADABLE_OR_UNPARSEABLE" in captured.err
    assert "PASS" not in captured.out
