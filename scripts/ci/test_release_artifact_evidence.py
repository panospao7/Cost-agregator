"""Unit tests for scripts/ci/release_artifact_evidence.py.

All fixtures live under tmp_path: a fake repository root, a fake Android SDK
with placeholder tool files, a fake verifier script, and a patched tool runner.
No real SDK, APK, keystore or Gradle output is read.
"""
import hashlib
import importlib.util
import json
import subprocess
import sys
from pathlib import Path

import pytest

MODULE_PATH = Path(__file__).resolve().parent / "release_artifact_evidence.py"
_spec = importlib.util.spec_from_file_location("release_artifact_evidence", MODULE_PATH)
evidence = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(evidence)

APK_BYTES = b"fake apk bytes"
MAPPING_BYTES = b"fake mapping bytes"
BADGING = """package: name='com.yourname.expensetracker' versionCode='7' versionName='1.0.0' platformBuildVersionName='15'
sdkVersion:'26'
targetSdkVersion:'35'
uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.POST_NOTIFICATIONS'
uses-permission-sdk-23: name='android.permission.CAMERA'
"""
RELEASE_DN = "CN=Release Owner, O=Example, C=GR"
DEBUG_DN = "CN=Android Debug, O=Android, C=US"
CERT_DIGEST = "ab" * 32


def _signing_text(dn):
    return (
        "Verifies\n"
        "Verified using v1 scheme (JAR signing): false\n"
        "Verified using v2 scheme (APK Signature Scheme v2): true\n"
        "Verified using v3 scheme (APK Signature Scheme v3): true\n"
        "Verified using v4 scheme (APK Signature Scheme v4): false\n"
        "Number of signers: 1\n"
        f"Signer #1 certificate DN: {dn}\n"
        f"Signer #1 certificate SHA-256 digest: {CERT_DIGEST}\n"
        "Signer #1 certificate SHA-1 digest: " + "cd" * 20 + "\n"
    )


class Fixture:
    def __init__(self, root, sdk):
        self.root = root
        self.sdk = sdk
        self.badging = BADGING
        self.signing = _signing_text(RELEASE_DN)
        self.aapt2_exit = 0
        self.signer_exit = 0
        self.calls = []


def _make_tools(sdk, version):
    directory = sdk / "build-tools" / version
    directory.mkdir(parents=True)
    windows = sys.platform == "win32"
    (directory / ("aapt2.exe" if windows else "aapt2")).write_bytes(b"")
    (directory / ("apksigner.bat" if windows else "apksigner")).write_bytes(b"")
    return directory


def _write_verifier(root, exit_code):
    scripts = root / "scripts"
    scripts.mkdir(exist_ok=True)
    (scripts / "verify_release_artifact.py").write_text(
        "import pathlib, sys\n"
        "pathlib.Path('verifier-argv.txt').write_text('\\n'.join(sys.argv[1:]), encoding='utf-8')\n"
        f"print('PASS: fake verifier' if {exit_code} == 0 else 'G-RELEASE-01: fake violation')\n"
        f"sys.exit({exit_code})\n",
        encoding="utf-8",
    )


@pytest.fixture
def fixture(tmp_path, monkeypatch):
    root = tmp_path / "repo"
    sdk = tmp_path / "sdk"
    apk_dir = root / "app" / "build" / "outputs" / "apk" / "release"
    apk_dir.mkdir(parents=True)
    (apk_dir / "app-release.apk").write_bytes(APK_BYTES)
    (apk_dir / "output-metadata.json").write_text(json.dumps({
        "version": 3, "applicationId": "com.yourname.expensetracker",
        "variantName": "release",
        "elements": [{
            "type": "SINGLE", "versionCode": 7, "versionName": "1.0.0",
            "outputFile": "app-release.apk",
        }],
    }), encoding="utf-8")
    mapping_dir = root / "app" / "build" / "outputs" / "mapping" / "release"
    mapping_dir.mkdir(parents=True)
    (mapping_dir / "mapping.txt").write_bytes(MAPPING_BYTES)
    _write_verifier(root, 0)
    _make_tools(sdk, "34.0.0")
    monkeypatch.setenv("ANDROID_HOME", str(sdk))
    monkeypatch.delenv("ANDROID_SDK_ROOT", raising=False)
    monkeypatch.delenv("LOCALAPPDATA", raising=False)
    state = Fixture(root, sdk)

    def fake_run_tool(arguments, cwd):
        state.calls.append([Path(str(arguments[0])).name, *map(str, arguments[1:])])
        name = Path(str(arguments[0])).name
        if name.startswith("aapt2"):
            return subprocess.CompletedProcess(arguments, state.aapt2_exit, state.badging, "")
        return subprocess.CompletedProcess(arguments, state.signer_exit, state.signing, "")

    monkeypatch.setattr(evidence, "run_tool", fake_run_tool)
    return state


def _evidence(root):
    return json.loads((root / "build" / "ci" / "release-artifact" / "evidence.json").read_text(encoding="utf-8"))


def _run(fixture):
    return evidence.main(["--root", str(fixture.root)])


def test_success_writes_complete_evidence_and_exits_zero(fixture, capsys):
    assert _run(fixture) == 0
    data = _evidence(fixture.root)
    assert data["status"] == "PASS" and data["problems"] == []
    assert data["apk"] == {
        "path": "app/build/outputs/apk/release/app-release.apk",
        "sha256": hashlib.sha256(APK_BYTES).hexdigest(),
        "size_bytes": len(APK_BYTES),
        "mtime_utc": data["apk"]["mtime_utc"],
    }
    assert data["metadata"] == {
        "application_id": "com.yourname.expensetracker", "variant_name": "release",
        "version_code": 7, "version_name": "1.0.0", "output_file": "app-release.apk",
    }
    assert data["badging"]["min_sdk"] == 26 and data["badging"]["target_sdk"] == 35
    assert data["badging"]["debuggable"] is False
    assert data["badging"]["permissions"] == [
        "android.permission.CAMERA", "android.permission.INTERNET",
        "android.permission.POST_NOTIFICATIONS",
    ]
    assert data["signing"]["cert_sha256"] == CERT_DIGEST
    assert data["signing"]["subject_dn"] == RELEASE_DN
    assert data["signing"]["schemes"] == {"v1": False, "v2": True, "v3": True, "v4": False}
    assert data["signing"]["debug_certificate"] is False
    assert data["mapping"] == {
        "path": "app/build/outputs/mapping/release/mapping.txt",
        "sha256": hashlib.sha256(MAPPING_BYTES).hexdigest(),
        "size_bytes": len(MAPPING_BYTES),
    }
    assert data["verifier"] == {"exit_code": 0, "passed": True}
    assert data["tools"] == {"build_tools_version": "34.0.0"}
    output = capsys.readouterr().out
    assert "status=PASS" in output and "evidence=build/ci/release-artifact/evidence.json" in output
    assert str(fixture.root) not in output


def test_verifier_is_invoked_unchanged_with_exact_arguments(fixture):
    assert _run(fixture) == 0
    argv = (fixture.root / "verifier-argv.txt").read_text(encoding="utf-8").split("\n")
    assert argv == ["--fail-on-violation", "--apk", "app/build/outputs/apk/release/app-release.apk"]
    assert fixture.calls[0][1:3] == ["dump", "badging"]
    assert fixture.calls[1][1:3] == ["verify", "--verbose"] and "--print-certs" in fixture.calls[1]


def test_missing_apk_exits_two_and_removes_stale_evidence(fixture, capsys):
    stale = fixture.root / "build" / "ci" / "release-artifact" / "evidence.json"
    stale.parent.mkdir(parents=True)
    stale.write_text("{\"status\": \"PASS\"}", encoding="utf-8")
    (fixture.root / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk").unlink()
    assert _run(fixture) == 2
    assert "E_APK_MISSING" in capsys.readouterr().out
    assert not stale.exists()


@pytest.mark.parametrize("missing,code", [
    ("app/build/outputs/apk/release/output-metadata.json", "E_OUTPUT_METADATA_MISSING"),
    ("app/build/outputs/mapping/release/mapping.txt", "E_MAPPING_MISSING"),
])
def test_missing_metadata_or_mapping_exits_two(fixture, capsys, missing, code):
    (fixture.root / missing).unlink()
    assert _run(fixture) == 2
    assert code in capsys.readouterr().out
    assert not (fixture.root / "build" / "ci" / "release-artifact" / "evidence.json").exists()


def test_metadata_must_name_the_release_apk(fixture, capsys):
    path = fixture.root / "app" / "build" / "outputs" / "apk" / "release" / "output-metadata.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    data["elements"][0]["outputFile"] = "app-release-unsigned.apk"
    path.write_text(json.dumps(data), encoding="utf-8")
    assert _run(fixture) == 2
    assert "E_OUTPUT_METADATA_APK_MISMATCH" in capsys.readouterr().out


def test_missing_build_tools_exit_two(fixture, capsys, monkeypatch, tmp_path):
    empty = tmp_path / "empty-sdk"
    (empty / "build-tools").mkdir(parents=True)
    monkeypatch.setenv("ANDROID_HOME", str(empty))
    assert _run(fixture) == 2
    assert "E_BUILD_TOOLS_MISSING" in capsys.readouterr().out
    assert not (fixture.root / "build" / "ci" / "release-artifact" / "evidence.json").exists()


def test_tools_are_located_from_the_highest_complete_build_tools_version(fixture, monkeypatch):
    _make_tools(fixture.sdk, "35.0.1")
    _make_tools(fixture.sdk, "9.0.0")
    # A newer directory lacking one tool must be skipped.
    incomplete = fixture.sdk / "build-tools" / "36.0.0"
    incomplete.mkdir()
    (incomplete / ("aapt2.exe" if sys.platform == "win32" else "aapt2")).write_bytes(b"")
    (fixture.sdk / "build-tools" / "36.0.0-rc1").mkdir()
    aapt2, signer, version = evidence.locate_tools()
    assert version == "35.0.1"
    assert aapt2.parent.name == signer.parent.name == "35.0.1"


def test_tools_fall_back_to_default_localappdata_sdk(fixture, monkeypatch, tmp_path):
    monkeypatch.delenv("ANDROID_HOME", raising=False)
    local = tmp_path / "local"
    _make_tools(local / "Android" / "Sdk", "33.0.2")
    monkeypatch.setenv("LOCALAPPDATA", str(local))
    assert evidence.locate_tools()[2] == "33.0.2"


def test_verifier_failure_propagates_and_is_recorded(fixture, capsys):
    _write_verifier(fixture.root, 1)
    assert _run(fixture) == 1
    data = _evidence(fixture.root)
    assert data["status"] == "FAIL"
    assert data["verifier"] == {"exit_code": 1, "passed": False}
    assert "E_VERIFIER_FAILED" in data["problems"]
    output = capsys.readouterr().out
    assert "G-RELEASE-01: fake violation" in output and "PROBLEM E_VERIFIER_FAILED" in output


def test_debug_certificate_is_recorded_and_does_not_fail(fixture):
    fixture.signing = _signing_text(DEBUG_DN)
    assert _run(fixture) == 0
    data = _evidence(fixture.root)
    assert data["status"] == "PASS"
    assert data["signing"]["debug_certificate"] is True
    assert data["signing"]["subject_dn"] == DEBUG_DN


def test_apksigner_failure_is_incomplete_not_pass(fixture):
    fixture.signer_exit = 1
    assert _run(fixture) == 1
    data = _evidence(fixture.root)
    assert data["signing"] is None and "E_SIGNING_UNVERIFIED" in data["problems"]


def test_aapt2_failure_is_infrastructure(fixture, capsys):
    fixture.aapt2_exit = 1
    assert _run(fixture) == 2
    assert "E_AAPT2_FAILED" in capsys.readouterr().out


def test_incomplete_badging_is_not_pass(fixture, capsys):
    fixture.badging = "package: name='com.yourname.expensetracker' versionCode='7' versionName='1.0.0'\n"
    assert _run(fixture) == 1
    assert "E_BADGING_INCOMPLETE" in capsys.readouterr().out
    assert not (fixture.root / "build" / "ci" / "release-artifact" / "evidence.json").exists()


@pytest.mark.parametrize("old,new,code", [
    ("name='com.yourname.expensetracker'", "name='com.other.app'", "E_METADATA_APPLICATION_ID_MISMATCH"),
    ("versionCode='7'", "versionCode='8'", "E_METADATA_VERSION_CODE_MISMATCH"),
    ("versionName='1.0.0'", "versionName='9.9.9'", "E_METADATA_VERSION_NAME_MISMATCH"),
])
def test_badging_must_match_output_metadata(fixture, old, new, code):
    fixture.badging = BADGING.replace(old, new)
    assert _run(fixture) == 1
    assert code in _evidence(fixture.root)["problems"]


def test_debuggable_apk_fails_even_when_verifier_passes(fixture, capsys):
    fixture.badging = BADGING + "application-debuggable\n"
    # Fake verifier passes (exit 0); the script itself must still fail.
    assert _run(fixture) == 1
    data = _evidence(fixture.root)
    assert data["badging"]["debuggable"] is True
    assert data["verifier"] == {"exit_code": 0, "passed": True}
    assert data["status"] == "FAIL"
    assert "E_DEBUGGABLE_APK" in data["problems"]
    assert "E_VARIANT_NOT_RELEASE" not in data["problems"]
    assert "PROBLEM E_DEBUGGABLE_APK" in capsys.readouterr().out


@pytest.mark.parametrize("variant", ["debug", "Release", "staging"])
def test_non_release_variant_fails_even_when_verifier_passes(fixture, capsys, variant):
    path = fixture.root / "app" / "build" / "outputs" / "apk" / "release" / "output-metadata.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    data["variantName"] = variant
    path.write_text(json.dumps(data), encoding="utf-8")
    assert _run(fixture) == 1
    result = _evidence(fixture.root)
    assert result["metadata"]["variant_name"] == variant
    assert result["verifier"] == {"exit_code": 0, "passed": True}
    assert result["status"] == "FAIL"
    assert "E_VARIANT_NOT_RELEASE" in result["problems"]
    assert "E_DEBUGGABLE_APK" not in result["problems"]
    assert "PROBLEM E_VARIANT_NOT_RELEASE" in capsys.readouterr().out


def test_release_variant_and_non_debuggable_apk_add_no_problem_codes(fixture):
    assert _run(fixture) == 0
    problems = _evidence(fixture.root)["problems"]
    assert "E_DEBUGGABLE_APK" not in problems and "E_VARIANT_NOT_RELEASE" not in problems


@pytest.mark.parametrize("content", [
    "{not json",
    "[]",
    json.dumps({"applicationId": "com.yourname.expensetracker", "variantName": "release"}),
    json.dumps({
        "applicationId": "com.yourname.expensetracker", "variantName": "release",
        "elements": [{"outputFile": "app-release.apk", "versionCode": "not-a-number",
                      "versionName": "1.0.0"}],
    }),
])
def test_invalid_output_metadata_json_exits_two(fixture, capsys, content):
    path = fixture.root / "app" / "build" / "outputs" / "apk" / "release" / "output-metadata.json"
    path.write_text(content, encoding="utf-8")
    assert _run(fixture) == 2
    assert "FAIL E_OUTPUT_METADATA_INVALID" in capsys.readouterr().out
    assert not (fixture.root / "build" / "ci" / "release-artifact" / "evidence.json").exists()


def test_verifier_launch_failure_exits_two_and_leaves_no_evidence(fixture, capsys, monkeypatch):
    def failing_verifier(root):
        raise OSError("synthetic launch failure")

    monkeypatch.setattr(evidence, "run_verifier", failing_verifier)
    assert _run(fixture) == 2
    output = capsys.readouterr().out
    assert "FAIL E_VERIFIER_EXECUTION_FAILED" in output
    assert "synthetic" not in output
    assert not (fixture.root / "build" / "ci" / "release-artifact" / "evidence.json").exists()


def test_verifier_timeout_exits_two(fixture, capsys, monkeypatch):
    def timing_out_verifier(root):
        raise subprocess.TimeoutExpired(cmd="verifier", timeout=1)

    monkeypatch.setattr(evidence, "run_verifier", timing_out_verifier)
    assert _run(fixture) == 2
    assert "FAIL E_VERIFIER_EXECUTION_FAILED" in capsys.readouterr().out


def test_evidence_write_is_atomic_and_leaves_no_temporary_file(fixture):
    assert _run(fixture) == 0
    directory = fixture.root / "build" / "ci" / "release-artifact"
    assert sorted(path.name for path in directory.iterdir()) == ["evidence.json"]


def test_parse_badging_accepts_real_aapt2_min_sdk_format():
    """Regression: aapt2 (build-tools 36) prints minSdkVersion:'N', not the legacy sdkVersion:'N'."""
    text = "\n".join([
        "package: name='com.yourname.expensetracker' versionCode='1' versionName='1.0' "
        "platformBuildVersionName='15' platformBuildVersionCode='35' compileSdkVersion='35'",
        "minSdkVersion:'26'",
        "targetSdkVersion:'35'",
        "uses-permission: name='android.permission.INTERNET'",
        "",
    ])
    parsed = evidence.parse_badging(text)
    assert parsed["min_sdk"] == 26
    assert parsed["target_sdk"] == 35
    assert parsed["package_name"] == "com.yourname.expensetracker"


def test_parse_badging_still_accepts_legacy_sdk_version_format():
    text = "\n".join([
        "package: name='com.yourname.expensetracker' versionCode='1' versionName='1.0'",
        "sdkVersion:'26'",
        "targetSdkVersion:'35'",
        "",
    ])
    assert evidence.parse_badging(text)["min_sdk"] == 26
