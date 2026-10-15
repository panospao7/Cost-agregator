#!/usr/bin/env python3
"""Collect read-only release APK evidence (release 1.0 assurance, B-2).

Locates exactly app/build/outputs/apk/release/app-release.apk, runs the
UNCHANGED scripts/verify_release_artifact.py --fail-on-violation --apk <apk>,
and records identity/manifest/signing facts in
build/ci/release-artifact/evidence.json.

Read-only: no keystore, signing property or secret is read. A debug signing
certificate is recorded (debug_certificate=true), not treated as a failure;
production signing is owner-held. A debuggable APK (E_DEBUGGABLE_APK) or an
output-metadata variant other than "release" (E_VARIANT_NOT_RELEASE) fails.

Exit codes: 0 verifier passed and evidence complete; 1 verifier violation or
incomplete/inconsistent evidence; 2 infrastructure (missing build output or
tools, tool failure). Output is a bounded summary with repo-relative paths only.
"""
import argparse
import datetime
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path

APK_RELATIVE = "app/build/outputs/apk/release/app-release.apk"
METADATA_RELATIVE = "app/build/outputs/apk/release/output-metadata.json"
MAPPING_RELATIVE = "app/build/outputs/mapping/release/mapping.txt"
VERIFIER_RELATIVE = "scripts/verify_release_artifact.py"
EVIDENCE_RELATIVE = "build/ci/release-artifact/evidence.json"
SCHEMA_VERSION = 1
TOOL_TIMEOUT_SECONDS = 60
VERIFIER_TIMEOUT_SECONDS = 180
MAX_SUMMARY_LINES = 10
MAX_LINE_LENGTH = 200

EXIT_OK, EXIT_INCOMPLETE, EXIT_INFRA = 0, 1, 2


class EvidenceError(Exception):
    """Controlled failure carrying a bounded code and an exit status."""

    def __init__(self, code, exit_code=EXIT_INFRA):
        super().__init__(code)
        self.code = code
        self.exit_code = exit_code


def _sha256(path):
    digest = hashlib.sha256()
    with open(path, "rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _version_key(name):
    return tuple(int(part) for part in name.split("."))


def locate_tools(env=None):
    """Return (aapt2, apksigner, build_tools_version) from the highest build-tools."""
    env = os.environ if env is None else env
    bases = []
    for variable in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        value = env.get(variable)
        if value:
            bases.append(Path(value) / "build-tools")
    local = env.get("LOCALAPPDATA")
    if local:
        bases.append(Path(local) / "Android" / "Sdk" / "build-tools")
    windows = sys.platform == "win32"
    aapt2_name = "aapt2.exe" if windows else "aapt2"
    signer_name = "apksigner.bat" if windows else "apksigner"
    for base in bases:
        if not base.is_dir():
            continue
        versions = sorted(
            (child for child in base.iterdir()
             if child.is_dir() and re.fullmatch(r"\d+(\.\d+)*", child.name)),
            key=lambda child: _version_key(child.name), reverse=True,
        )
        for version in versions:
            aapt2, signer = version / aapt2_name, version / signer_name
            if aapt2.is_file() and signer.is_file():
                return aapt2, signer, version.name
    raise EvidenceError("E_BUILD_TOOLS_MISSING")


def run_tool(arguments, cwd):
    """Run one external read-only tool; patched by unit tests."""
    return subprocess.run(
        [str(argument) for argument in arguments], cwd=str(cwd),
        capture_output=True, text=True, encoding="utf-8", errors="replace",
        timeout=TOOL_TIMEOUT_SECONDS,
    )


def run_verifier(root):
    """Run the unchanged release verifier; patched by unit tests if needed."""
    return subprocess.run(
        [sys.executable, VERIFIER_RELATIVE, "--fail-on-violation", "--apk", APK_RELATIVE],
        cwd=str(root), capture_output=True, text=True, encoding="utf-8",
        errors="replace", timeout=VERIFIER_TIMEOUT_SECONDS,
    )


def read_metadata(root):
    path = root / METADATA_RELATIVE
    if not path.is_file():
        raise EvidenceError("E_OUTPUT_METADATA_MISSING")
    try:
        data = json.loads(path.read_text(encoding="utf-8-sig"))
        elements = [element for element in data["elements"]
                    if element.get("outputFile") == Path(APK_RELATIVE).name]
        if len(elements) != 1:
            raise EvidenceError("E_OUTPUT_METADATA_APK_MISMATCH")
        element = elements[0]
        return {
            "application_id": str(data["applicationId"]),
            "variant_name": str(data["variantName"]),
            "version_code": int(element["versionCode"]),
            "version_name": str(element["versionName"]),
            "output_file": str(element["outputFile"]),
        }
    except EvidenceError:
        raise
    except (OSError, ValueError, KeyError, TypeError, AttributeError):
        raise EvidenceError("E_OUTPUT_METADATA_INVALID")


def parse_badging(text):
    package = re.search(
        r"^package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'", text, re.M)
    min_sdk = re.search(r"^sdkVersion:'(\d+)'", text, re.M)
    target_sdk = re.search(r"^targetSdkVersion:'(\d+)'", text, re.M)
    if package is None or min_sdk is None or target_sdk is None:
        raise EvidenceError("E_BADGING_INCOMPLETE", EXIT_INCOMPLETE)
    permissions = sorted(set(re.findall(
        r"^uses-permission(?:-sdk-\d+)?: name='([^']+)'", text, re.M)))
    return {
        "package_name": package.group(1),
        "version_code": package.group(2),
        "version_name": package.group(3),
        "min_sdk": int(min_sdk.group(1)),
        "target_sdk": int(target_sdk.group(1)),
        # Same conservative substring test as verify_release_artifact.py.
        "debuggable": "debuggable" in text,
        "permissions": permissions,
    }


def parse_signing(text):
    digest = re.search(
        r"^Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]{64})\s*$", text, re.M)
    subject = re.search(r"^Signer #1 certificate DN: (.+?)\s*$", text, re.M)
    if digest is None or subject is None:
        raise EvidenceError("E_SIGNING_INCOMPLETE", EXIT_INCOMPLETE)
    schemes = {}
    for version, value in re.findall(
            r"^Verified using v(\d) scheme[^:]*: (true|false)\s*$", text, re.M):
        schemes[f"v{version}"] = value == "true"
    if not any(schemes.values()):
        raise EvidenceError("E_SIGNING_INCOMPLETE", EXIT_INCOMPLETE)
    signers = re.search(r"^Number of signers: (\d+)", text, re.M)
    return {
        "cert_sha256": digest.group(1).lower(),
        "subject_dn": subject.group(1)[:MAX_LINE_LENGTH],
        "schemes": schemes,
        "signer_count": int(signers.group(1)) if signers else None,
        "debug_certificate": "CN=Android Debug" in subject.group(1),
    }


def _git_revision(root):
    try:
        completed = subprocess.run(
            ["git", "-C", str(root), "rev-parse", "HEAD"], capture_output=True,
            text=True, timeout=15)
        value = completed.stdout.strip()
        if completed.returncode == 0 and re.fullmatch(r"[a-f0-9]{40,64}", value):
            return value
    except (OSError, subprocess.SubprocessError):
        pass
    return "UNKNOWN"


def write_json_atomic(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f"{path.name}.{os.getpid()}.tmp")
    temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def _bounded_lines(text, root):
    lines = []
    for line in text.splitlines():
        if line.startswith(("G-RELEASE-", "PASS:")):
            lines.append(line.replace(str(root), "<repo>")[:MAX_LINE_LENGTH])
        if len(lines) >= MAX_SUMMARY_LINES:
            break
    return lines


def collect(root):
    """Return (evidence, verifier_output_lines, exit_code)."""
    apk = root / APK_RELATIVE
    if not apk.is_file():
        raise EvidenceError("E_APK_MISSING")
    metadata = read_metadata(root)
    mapping = root / MAPPING_RELATIVE
    if not mapping.is_file():
        raise EvidenceError("E_MAPPING_MISSING")
    aapt2, apksigner, tools_version = locate_tools()

    try:
        badging_run = run_tool([aapt2, "dump", "badging", APK_RELATIVE], root)
        signing_run = run_tool([apksigner, "verify", "--verbose", "--print-certs", APK_RELATIVE], root)
    except (OSError, subprocess.SubprocessError):
        raise EvidenceError("E_TOOL_EXECUTION_FAILED")
    if badging_run.returncode != 0:
        raise EvidenceError("E_AAPT2_FAILED")
    badging = parse_badging(badging_run.stdout)
    # apksigner exits non-zero for an unverifiable signature; the unchanged
    # verifier reports that as a violation, so keep parsing what is available.
    signing = parse_signing(signing_run.stdout) if signing_run.returncode == 0 else None

    try:
        verifier = run_verifier(root)
    except (OSError, subprocess.SubprocessError):
        raise EvidenceError("E_VERIFIER_EXECUTION_FAILED")
    verifier_passed = verifier.returncode == 0

    problems = []
    if not verifier_passed:
        problems.append("E_VERIFIER_FAILED")
    if signing is None:
        problems.append("E_SIGNING_UNVERIFIED")
    # Fail on the evidence itself, independent of the verifier: a debuggable
    # APK or a non-release variant is never acceptable release evidence.
    if badging["debuggable"]:
        problems.append("E_DEBUGGABLE_APK")
    if metadata["variant_name"] != "release":
        problems.append("E_VARIANT_NOT_RELEASE")
    if badging["package_name"] != metadata["application_id"]:
        problems.append("E_METADATA_APPLICATION_ID_MISMATCH")
    if badging["version_code"] != str(metadata["version_code"]):
        problems.append("E_METADATA_VERSION_CODE_MISMATCH")
    if badging["version_name"] != metadata["version_name"]:
        problems.append("E_METADATA_VERSION_NAME_MISMATCH")

    stat = apk.stat()
    evidence = {
        "schema_version": SCHEMA_VERSION,
        "status": "PASS" if not problems else "FAIL",
        "problems": problems,
        "generated_at": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "git_revision": _git_revision(root),
        "apk": {
            "path": APK_RELATIVE,
            "sha256": _sha256(apk),
            "size_bytes": stat.st_size,
            "mtime_utc": datetime.datetime.fromtimestamp(
                stat.st_mtime, datetime.timezone.utc).isoformat(),
        },
        "metadata": metadata,
        "badging": badging,
        "signing": signing,
        "mapping": {
            "path": MAPPING_RELATIVE,
            "sha256": _sha256(mapping),
            "size_bytes": mapping.stat().st_size,
        },
        "verifier": {"exit_code": verifier.returncode, "passed": verifier_passed},
        "tools": {"build_tools_version": tools_version},
    }
    lines = _bounded_lines(verifier.stdout + "\n" + verifier.stderr, root)
    return evidence, lines, (EXIT_OK if not problems else EXIT_INCOMPLETE)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    # Test seam only; the validation-runner profile passes no arguments.
    parser.add_argument("--root", default=str(Path(__file__).resolve().parents[2]))
    root = Path(parser.parse_args(argv).root).resolve()
    evidence_path = root / EVIDENCE_RELATIVE
    # A failed run must never leave a previous run's evidence looking current.
    try:
        evidence_path.unlink()
    except FileNotFoundError:
        pass
    except OSError:
        print("FAIL E_EVIDENCE_STALE_REMOVAL_FAILED")
        return EXIT_INFRA
    try:
        evidence, lines, exit_code = collect(root)
        write_json_atomic(evidence_path, evidence)
    except EvidenceError as failure:
        print(f"FAIL {failure.code}")
        return failure.exit_code
    except OSError:
        print("FAIL E_EVIDENCE_IO_FAILED")
        return EXIT_INFRA
    for line in lines:
        print(line)
    signing = evidence["signing"] or {}
    print(f"status={evidence['status']} verifier_exit={evidence['verifier']['exit_code']}")
    print(f"apk={evidence['apk']['path']} size_bytes={evidence['apk']['size_bytes']}")
    print(f"apk_sha256={evidence['apk']['sha256']}")
    print(f"application_id={evidence['metadata']['application_id']} "
          f"variant={evidence['metadata']['variant_name']} "
          f"version={evidence['metadata']['version_name']}"
          f"({evidence['metadata']['version_code']})")
    print(f"min_sdk={evidence['badging']['min_sdk']} target_sdk={evidence['badging']['target_sdk']} "
          f"debuggable={str(evidence['badging']['debuggable']).lower()} "
          f"permissions={len(evidence['badging']['permissions'])}")
    print(f"cert_sha256={signing.get('cert_sha256')} "
          f"debug_certificate={str(signing.get('debug_certificate', False)).lower()}")
    print(f"mapping_sha256={evidence['mapping']['sha256']}")
    print(f"evidence={EVIDENCE_RELATIVE}")
    for problem in evidence["problems"]:
        print(f"PROBLEM {problem}")
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
