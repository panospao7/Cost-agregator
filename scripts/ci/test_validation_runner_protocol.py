import json
import os
import re
import subprocess
import sys
from pathlib import Path

import pytest


pytestmark = pytest.mark.skipif(os.name != "nt", reason="Windows PowerShell runner")
RUNNER = Path(__file__).resolve().parents[1] / "validation-runner.ps1"


def _functions(*names):
    source = RUNNER.read_text(encoding="utf-8-sig")
    definitions = []
    for name in names:
        matched = re.search(r"(?ms)^function " + re.escape(name) + r" \{.*?^\}", source)
        assert matched is not None, name
        definitions.append(matched.group())
    return "\n".join(definitions)


def _powershell(script):
    return subprocess.run(
        ["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
        capture_output=True, text=True, timeout=30,
    )


def test_atomic_publication_keeps_record_readable(tmp_path):
    destination = tmp_path / "result.json"
    destination.write_text(json.dumps({"generation": -1}), encoding="utf-8")
    functions = _functions("Write-JsonAtomic")
    script = functions + f"\n$target = '{str(destination).replace(chr(39), chr(39) * 2)}'\n" + """
$ErrorActionPreference = 'Stop'
for ($generation = 0; $generation -lt 25; $generation++) {
    Write-JsonAtomic -Path $target -Value @{ generation = $generation }
    Start-Sleep -Milliseconds 10
}
"""
    process = subprocess.Popen(
        ["powershell", "-NoProfile", "-NonInteractive", "-Command", script],
        stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True,
    )
    errors = []
    try:
        import time

        deadline = time.monotonic() + 30
        while process.poll() is None and time.monotonic() < deadline:
            try:
                json.loads(destination.read_text(encoding="utf-8-sig"))
            except (PermissionError, FileNotFoundError):
                # Windows can briefly deny a reader while File.Replace swaps
                # or moves the destination. Retry the read; only a
                # persistently invalid final record is a protocol failure.
                time.sleep(0.002)
                continue
            except (OSError, ValueError) as failure:
                errors.append(type(failure).__name__)
            time.sleep(0.005)
        stdout, stderr = process.communicate(timeout=5)
    finally:
        if process.poll() is None:
            process.kill()
            process.wait(timeout=5)
    assert process.returncode == 0, (stdout, stderr)
    assert errors == []
    assert json.loads(destination.read_text(encoding="utf-8-sig")) == {"generation": 24}


def test_wait_reconciles_dead_worker_without_another_start(tmp_path):
    run_id = "vr-20260101-000000-deadbeef"
    runs = tmp_path / "runs"
    run = runs / run_id
    run.mkdir(parents=True)
    record = {
        "status": "STARTING", "child_pid": None, "exit_code": None,
        "failure_code": None, "finished_at": None, "worktree_fingerprint_end": None,
    }
    (run / "result.json").write_text(json.dumps(record), encoding="utf-8")
    (run / "result.json.999.tmp").write_text("incomplete", encoding="utf-8")
    lock_path = runs / "active.lock.json"
    lock_path.write_text(json.dumps({
        "run_id": run_id, "worker_pid": 0, "created_at": "2000-01-01T00:00:00Z",
    }), encoding="utf-8")
    functions = _functions(
        "Get-UtcTimestamp", "Write-JsonAtomic", "Read-JsonFile", "Get-RunDirectory",
        "Get-RunRecordPath", "Test-ProcessAlive", "Complete-OrphanedRun",
        "Resolve-ActiveLock", "Resolve-RunRecord", "Wait-ForRun",
    )
    setup = f"""
$ErrorActionPreference = 'Stop'
$script:RunsRoot = '{str(runs).replace(chr(39), chr(39) * 2)}'
$script:LockPath = '{str(lock_path).replace(chr(39), chr(39) * 2)}'
$script:TerminalStatuses = @('PASS','FAIL','TIMEOUT','STALE_RESULT','INFRA_FAILURE')
$RunId = '{run_id}'
$MaxWaitSeconds = 5
$PollSeconds = 1
function Get-WorktreeFingerprint {{ return 'end-fingerprint' }}
Wait-ForRun
"""
    completed = _powershell(functions + setup)
    assert completed.returncode == 2, (completed.stdout, completed.stderr)
    result = json.loads(completed.stdout)
    assert result["status"] == "INFRA_FAILURE"
    assert result["failure_code"] == "E_ORPHANED_RUN"
    assert result["exit_code"] == 2
    assert result["worktree_fingerprint_end"] == "end-fingerprint"
    assert (run / "complete.marker").is_file()
    assert (run / "stdout.log").is_file()
    assert (run / "stderr.log").is_file()
    assert not list(run.glob("result.json.*.tmp"))
    assert not lock_path.exists()


def test_record_reader_retries_transient_sharing_failures(tmp_path):
    destination = tmp_path / "result.json"
    destination.write_text(json.dumps({"generation": 7}), encoding="utf-8")
    functions = _functions("Read-JsonFile")
    setup = f"""
$ErrorActionPreference = 'Stop'
$script:readAttempts = 0
function Get-Content {{
    param($LiteralPath, [switch]$Raw, $Encoding)
    $script:readAttempts++
    if ($script:readAttempts -lt 3) {{ throw [System.IO.IOException]::new('sharing violation') }}
    Microsoft.PowerShell.Management\\Get-Content -LiteralPath $LiteralPath -Raw -Encoding UTF8
}}
$record = Read-JsonFile -Path '{str(destination).replace(chr(39), chr(39) * 2)}'
@{{ generation = $record.generation; attempts = $script:readAttempts }} | ConvertTo-Json
"""
    completed = _powershell(functions + setup)
    assert completed.returncode == 0, (completed.stdout, completed.stderr)
    assert json.loads(completed.stdout) == {"generation": 7, "attempts": 3}


def test_record_reader_does_not_accept_persistently_invalid_json(tmp_path):
    destination = tmp_path / "result.json"
    destination.write_text("incomplete", encoding="utf-8")
    functions = _functions("Read-JsonFile")
    setup = f"""
$ErrorActionPreference = 'Stop'
$record = Read-JsonFile -Path '{str(destination).replace(chr(39), chr(39) * 2)}'
@{{ missing = $null -eq $record }} | ConvertTo-Json
"""
    completed = _powershell(functions + setup)
    assert completed.returncode == 0, (completed.stdout, completed.stderr)
    assert json.loads(completed.stdout) == {"missing": True}


def test_wait_reconciles_orphan_without_a_lock(tmp_path):
    run_id = "vr-20260101-000000-deadbeef"
    runs = tmp_path / "runs"
    run = runs / run_id
    run.mkdir(parents=True)
    (run / "result.json").write_text(json.dumps({
        "status": "STARTING", "worker_pid": 0, "child_pid": None,
        "started_at": "2000-01-01T00:00:00Z", "finished_at": None,
        "exit_code": None, "failure_code": None, "worktree_fingerprint_end": None,
    }), encoding="utf-8")
    functions = _functions(
        "Get-UtcTimestamp", "Write-JsonAtomic", "Read-JsonFile", "Get-RunDirectory",
        "Get-RunRecordPath", "Test-ProcessAlive", "Complete-OrphanedRun",
        "Resolve-ActiveLock", "Resolve-RunRecord", "Wait-ForRun",
    )
    setup = f"""
$ErrorActionPreference = 'Stop'
$script:RunsRoot = '{str(runs).replace(chr(39), chr(39) * 2)}'
$script:LockPath = Join-Path $script:RunsRoot 'active.lock.json'
$script:TerminalStatuses = @('PASS','FAIL','TIMEOUT','STALE_RESULT','INFRA_FAILURE')
$RunId = '{run_id}'
$MaxWaitSeconds = 5
$PollSeconds = 1
function Get-WorktreeFingerprint {{ return 'end-fingerprint' }}
Wait-ForRun
"""
    completed = _powershell(functions + setup)
    assert completed.returncode == 2, (completed.stdout, completed.stderr)
    result = json.loads(completed.stdout)
    assert result["status"] == "INFRA_FAILURE"
    assert result["failure_code"] == "E_ORPHANED_RUN"
    assert (run / "complete.marker").is_file()


def test_wait_does_not_recover_a_live_worker_without_a_lock(tmp_path):
    run_id = "vr-20260101-000000-deadbeef"
    runs = tmp_path / "runs"
    run = runs / run_id
    run.mkdir(parents=True)
    (run / "result.json").write_text(json.dumps({
        "status": "STARTING", "worker_pid": 123,
        "started_at": "2000-01-01T00:00:00Z",
    }), encoding="utf-8")
    functions = _functions(
        "Read-JsonFile", "Get-RunDirectory", "Get-RunRecordPath",
        "Resolve-ActiveLock", "Resolve-RunRecord", "Wait-ForRun",
    )
    setup = f"""
$ErrorActionPreference = 'Stop'
$script:RunsRoot = '{str(runs).replace(chr(39), chr(39) * 2)}'
$script:LockPath = Join-Path $script:RunsRoot 'active.lock.json'
$script:TerminalStatuses = @('PASS','FAIL','TIMEOUT','STALE_RESULT','INFRA_FAILURE')
$RunId = '{run_id}'
$MaxWaitSeconds = 1
$PollSeconds = 1
function Test-ProcessAlive {{ param([int]$ProcessId) return $ProcessId -eq 123 }}
Wait-ForRun
"""
    completed = _powershell(functions + setup)
    assert completed.returncode == 3, (completed.stdout, completed.stderr)
    assert json.loads(completed.stdout)["failure_code"] == "E_WAIT_WINDOW_EXPIRED"
    assert not (run / "complete.marker").exists()


@pytest.mark.parametrize("profile,stored_command,requested_command,expected", [
    ("targeted-unit-test", "gradle --tests *ReceiptParser*", "gradle --tests *ReceiptParser*", True),
    ("targeted-unit-test", "gradle --tests *ReceiptParser*", "gradle --tests *TotalsAggregationEngine*", False),
    ("targeted-release-unit-test", "gradle :app:testReleaseUnitTest --tests *ReceiptParser*", "gradle :app:testReleaseUnitTest --tests *ReceiptParser*", True),
    ("targeted-release-unit-test", "gradle :app:testDebugUnitTest --tests *ReceiptParser*", "gradle :app:testReleaseUnitTest --tests *ReceiptParser*", False),
    ("registered-guard", "guard --guard-id cancellation", "guard --guard-id raw_money_aggregates", False),
])
def test_duplicate_detection_requires_the_same_validation_command(
    tmp_path, profile, stored_command, requested_command, expected,
):
    run = tmp_path / "vr-20260101-000000-deadbeef"
    run.mkdir()
    (run / "result.json").write_text(json.dumps({
        "status": "PASS", "exit_code": 0, "profile": profile,
        "command": stored_command,
        "worktree_fingerprint_start": "fingerprint",
        "worktree_fingerprint_end": "fingerprint",
    }), encoding="utf-8")
    (run / "complete.marker").write_text("complete", encoding="utf-8")
    functions = _functions("Read-JsonFile", "Find-OverlappingSuccessfulRun")
    setup = f"""
$ErrorActionPreference = 'Stop'
$script:RunsRoot = '{str(tmp_path).replace(chr(39), chr(39) * 2)}'
$match = Find-OverlappingSuccessfulRun -RequestedProfile '{profile}' -RequestedCommand '{requested_command}' -Fingerprint 'fingerprint'
@{{ duplicate = $null -ne $match }} | ConvertTo-Json
"""
    completed = _powershell(functions + setup)
    assert completed.returncode == 0, (completed.stdout, completed.stderr)
    assert json.loads(completed.stdout) == {"duplicate": expected}


@pytest.mark.parametrize("disappears,expected", [(True, "absent"), (False, "E_INVALID_LOCK")])
def test_lock_disappearance_during_read_is_not_malformed_lock(tmp_path, disappears, expected):
    lock_path = tmp_path / "active.lock.json"
    lock_path.write_text("incomplete", encoding="utf-8")
    functions = _functions("Resolve-ActiveLock")
    setup = f"""
$ErrorActionPreference = 'Stop'
$script:LockPath = '{str(lock_path).replace(chr(39), chr(39) * 2)}'
function Read-JsonFile {{
    param($Path)
    if (${str(disappears).lower()}) {{ Remove-Item -LiteralPath $Path }}
    return $null
}}
try {{
    $lock = Resolve-ActiveLock
    @{{ outcome = if ($null -eq $lock) {{ 'absent' }} else {{ 'unexpected' }} }} | ConvertTo-Json
}} catch {{
    @{{ outcome = $_.Exception.Message }} | ConvertTo-Json
}}
"""
    completed = _powershell(functions + setup)
    assert completed.returncode == 0, (completed.stdout, completed.stderr)
    assert json.loads(completed.stdout) == {"outcome": expected}


# Only extracted functions run in these fixtures. No test invokes the live
# runner entry point, the live validation lock, or a real Gradle wrapper.
DEFAULT_GRADLE_SETTINGS = {
    "max_workers": 1, "test_forks": 1, "fork_every": 50,
    "parallel": False, "gradle_daemon": False,
    "kotlin_compiler_strategy": "project",
}
GRADLE_PROFILE_ARGUMENTS = {
    "compile": [":app:compileDebugKotlin"],
    "benchmark-compile": [":app:compileDebugKotlin", "--rerun", "--no-build-cache", "-Pkotlin.incremental=false", "-Pkotlin.build.report.output=file", "--profile"],
    "benchmark-test-shard": [":app:testDebugUnitTest", "--rerun", "--tests", "*ShardOne*", "--tests", "*ShardTwo*", "--no-build-cache", "--profile"],
    "benchmark-app-check": [":app:testDebugUnitTest", "--rerun", ":app:testReleaseUnitTest", "--rerun", ":app:check", "--no-build-cache", "--profile"],
    "assemble-debug": [":app:assembleDebug"],
    "targeted-unit-test": [":app:testDebugUnitTest", "--tests", "*ProtocolCase*"],
    "targeted-release-unit-test": [":app:testReleaseUnitTest", "--tests", "*ProtocolCase*"],
    "unit-test-shard": [":app:testDebugUnitTest", "--tests", "*ShardOne*", "--tests", "*ShardTwo*"],
    "trusted-tests": [":app:testDebugUnitTest", "--tests", "*TrustedCase*"],
    "legacy-tests": [":app:testDebugUnitTest", "--tests", "*LegacyCase*"],
    "unit-tests": [":app:testDebugUnitTest"],
    "migration-tests": [":app:testDebugUnitTest", "--tests", "*Migration*"],
    "lint": [":app:lintDebug"],
    "app-check": [":app:check"],
    "connected-tests": [":app:connectedDebugAndroidTest"],
    "connected-test-class": [
        ":app:connectedDebugAndroidTest",
        "-Pandroid.testInstrumentationRunnerArguments.class=com.yourname.expensetracker.ProtocolCase",
    ],
    "assemble-release": [":app:assembleRelease"],
}
NON_GRADLE_PROFILES = (
    "runner-smoke", "runner-progress-smoke", "runner-stall-smoke",
    "runner-protocol-tests", "static-guards", "registered-guard",
    "release-artifact-evidence",
)
CLASS_FILTER = "com.yourname.expensetracker.ProtocolCase"
# Profiles whose -TestFilter must be a fully qualified class name.
PROFILE_FILTERS = {"connected-test-class": CLASS_FILTER}
CONNECTED_PROFILES = ("connected-tests", "connected-test-class")
SERIAL = "emulator-5554"


def _settings(**overrides):
    return dict(DEFAULT_GRADLE_SETTINGS, **overrides)


def _ps_quote(value):
    return "'" + str(value).replace("'", "''") + "'"


def _ps_value(value):
    if value is None:
        return "$null"
    if isinstance(value, bool):
        return "$true" if value else "$false"
    if isinstance(value, str):
        return _ps_quote(value)
    return str(value)


def _ps_json(value):
    return f"({_ps_quote(json.dumps(value))} | ConvertFrom-Json)"


def _powershell_json(tmp_path, script, timeout=30):
    # -File avoids Windows' command-line length limit for extracted functions.
    probe = tmp_path / "protocol probe.ps1"
    probe.write_text(
        "Set-StrictMode -Version Latest\n$ErrorActionPreference = 'Stop'\n" + script,
        encoding="utf-8-sig",
    )
    completed = subprocess.run(
        ["powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy",
         "Bypass", "-File", str(probe)],
        cwd=tmp_path, capture_output=True, text=True, timeout=timeout,
    )
    assert completed.returncode == 0, (completed.stdout, completed.stderr)
    return json.loads(completed.stdout)


def _invocation_setup(options, profile="compile"):
    values = {
        "Profile": profile, "MaxWorkers": 1, "TestForks": 1,
        "ForkEvery": 50, "Parallel": False, "GradleDaemon": False,
        "KotlinCompilerStrategy": "project",
    }
    lines = [f"${key} = {_ps_value(value)}" for key, value in values.items()]
    lines.append("$script:InvocationParameters = @{}")
    for key, value in options.items():
        lines.extend([
            f"${key} = {_ps_value(value)}",
            f"$script:InvocationParameters[{_ps_quote(key)}] = ${key}",
        ])
    return "\n".join(lines) + "\n"


def _gradle_fixture(tmp_path):
    root = tmp_path / "isolated repo's root"
    root.mkdir()
    runs = root / "build" / "validation-runs"
    runs.mkdir(parents=True)
    shards = root / "shards.json"
    ledger = root / "ledger.json"
    shards.write_text(json.dumps({
        "schema_version": 1,
        "shards": {"protocol-shard": {"filters": ["*ShardOne*", "*ShardTwo*"]}},
        "trusted": {"filters": ["*TrustedCase*"]},
    }), encoding="utf-8")
    ledger.write_text(json.dumps({
        "schema_version": 1,
        "entries": [{
            "id": "HANG-999", "status": "suspected", "routing": "legacy",
            "reason_code": "RUNNER_SELF_TEST", "owner": "protocol-test",
            "review_by": "2099-12-31", "test_filter": "*LegacyCase*",
        }],
    }), encoding="utf-8")
    source = _functions(
        "Read-JsonFile", "Read-ValidationConfig", "Assert-SafeTestFilters",
        "Get-ShardFilters", "Get-TrustedFilters", "Get-LegacyFilters",
        "New-TestArguments", "Test-GradleProfile", "Resolve-InvocationDeviceSerial",
        "New-GradleSettings",
        "Resolve-InvocationGradleSettings", "Resolve-RequestGradleSettings",
        "Get-ProfileSpec", "Get-SafeCommandDisplay",
    )
    source += f"""
$script:RepoRoot = {_ps_quote(root)}
$script:RunsRoot = {_ps_quote(runs)}
$script:LockPath = Join-Path $script:RunsRoot 'active.lock.json'
$script:ShardConfigPath = {_ps_quote(shards)}
$script:HangingLedgerPath = {_ps_quote(ledger)}
$script:SchemaVersion = 1
$script:TerminalStatuses = @('PASS','FAIL','TIMEOUT','STALE_RESULT','INFRA_FAILURE')
"""
    return root, source


def _gradle_arguments(profile, settings):
    arguments = GRADLE_PROFILE_ARGUMENTS[profile] + [
        "--console=plain", "--parallel" if settings["parallel"] else "--no-parallel",
        f"--max-workers={settings['max_workers']}",
        f"-PvalidationMaxParallelForks={settings['test_forks']}",
        f"-PvalidationForkEvery={settings['fork_every']}",
        "--daemon" if settings["gradle_daemon"] else "--no-daemon",
    ]
    if settings["kotlin_compiler_strategy"] != "project":
        arguments.append(
            f"-Pkotlin.compiler.execution.strategy={settings['kotlin_compiler_strategy']}"
        )
    return arguments


def _command_display(executable, arguments):
    return Path(executable).name + " " + " ".join(
        f'"{argument}"' if re.search(r"\s", argument) else argument
        for argument in map(str, arguments)
    )


@pytest.mark.parametrize("profile", GRADLE_PROFILE_ARGUMENTS)
def test_all_gradle_profiles_retain_exact_historical_default_argv(tmp_path, profile):
    root, source = _gradle_fixture(tmp_path)
    selected_filter = _ps_quote(PROFILE_FILTERS.get(profile, "*ProtocolCase*"))
    result = _powershell_json(tmp_path, source + _invocation_setup({}, profile) + f"""
$settings = Resolve-InvocationGradleSettings
$implicit = Get-ProfileSpec -Name $Profile -Filter {selected_filter} -ShardName 'protocol-shard'
$explicit = Get-ProfileSpec -Name $Profile -Filter {selected_filter} -ShardName 'protocol-shard' -GradleSettings $settings
@{{ settings = $settings; implicit = $implicit; explicit = $explicit }} | ConvertTo-Json -Depth 8
""")
    assert result["settings"] == DEFAULT_GRADLE_SETTINGS
    expected = GRADLE_PROFILE_ARGUMENTS[profile] + [
        "--console=plain", "--no-parallel", "--max-workers=1",
        "-PvalidationMaxParallelForks=1", "-PvalidationForkEvery=50", "--no-daemon",
    ]
    for mode in ("implicit", "explicit"):
        assert result[mode]["executable"] == str(root / "gradlew.bat")
        assert result[mode]["arguments"] == expected


TUNING_CASES = [
    pytest.param({}, _settings(), id="defaults"),
    pytest.param({"Parallel": True}, _settings(max_workers=4, test_forks=2, parallel=True), id="parallel-preset"),
    pytest.param({"Parallel": True, "MaxWorkers": 6}, _settings(max_workers=6, test_forks=2, parallel=True), id="explicit-workers"),
    pytest.param({"Parallel": True, "TestForks": 1}, _settings(max_workers=4, parallel=True), id="explicit-default-forks"),
    pytest.param(
        {"Parallel": True, "MaxWorkers": 1, "TestForks": 1, "ForkEvery": 0, "GradleDaemon": False},
        _settings(fork_every=0, parallel=True), id="explicit-default-counts-false-zero",
    ),
    pytest.param({"Parallel": False, "GradleDaemon": False, "ForkEvery": 0}, _settings(fork_every=0), id="serial-explicit-false-zero"),
    pytest.param(
        {"MaxWorkers": 16, "TestForks": 8, "ForkEvery": 1000, "GradleDaemon": True, "KotlinCompilerStrategy": "IN-PROCESS"},
        _settings(max_workers=16, test_forks=8, fork_every=1000, gradle_daemon=True, kotlin_compiler_strategy="in-process"),
        id="upper-bounds-warm-in-process",
    ),
    pytest.param({"KotlinCompilerStrategy": "Daemon"}, _settings(kotlin_compiler_strategy="daemon"), id="normalize-daemon"),
    pytest.param({"KotlinCompilerStrategy": "PROJECT"}, _settings(), id="normalize-project"),
]


@pytest.mark.parametrize("options,expected", TUNING_CASES)
def test_gradle_presets_only_replace_omitted_counts_and_build_explicit_flags(tmp_path, options, expected):
    _, source = _gradle_fixture(tmp_path)
    result = _powershell_json(tmp_path, source + _invocation_setup(options) + """
$settings = Resolve-InvocationGradleSettings
$spec = Get-ProfileSpec -Name $Profile -GradleSettings $settings
@{ settings = $settings; arguments = $spec.arguments } | ConvertTo-Json -Depth 8
""")
    assert result == {"settings": expected, "arguments": _gradle_arguments("compile", expected)}


@pytest.mark.parametrize("options", [
    {"MaxWorkers": 1, "TestForks": 2},
    {"Parallel": True, "MaxWorkers": 1},
])
def test_explicit_workers_below_test_forks_are_rejected_not_silently_raised(tmp_path, options):
    _, source = _gradle_fixture(tmp_path)
    result = _powershell_json(tmp_path, source + _invocation_setup(options) + """
try { $null = Resolve-InvocationGradleSettings; @{ outcome = 'accepted' } | ConvertTo-Json }
catch { @{ outcome = $_.Exception.Message } | ConvertTo-Json }
""")
    assert result == {"outcome": "E_TEST_FORKS_EXCEED_WORKERS"}


@pytest.mark.parametrize("argument", [
    "-Workers 0", "-Workers 17", "-Forks 0", "-Workers 16 -Forks 9",
    "-RecycleAfter -1", "-RecycleAfter 1001", "-CompilerStrategy 'unbounded'",
])
def test_new_gradle_settings_rejects_out_of_bounds_controls(tmp_path, argument):
    source = _functions("New-GradleSettings")
    result = _powershell_json(tmp_path, source + f"""
try {{ $null = New-GradleSettings {argument}; @{{ accepted = $true }} | ConvertTo-Json }}
catch {{ @{{ accepted = $false; exception_type = $_.Exception.GetType().Name }} | ConvertTo-Json }}
""")
    assert result["accepted"] is False
    assert result["exception_type"].startswith("ParameterBinding")


@pytest.mark.parametrize("profile", NON_GRADLE_PROFILES)
@pytest.mark.parametrize("options", [
    {}, {"MaxWorkers": 1}, {"TestForks": 1}, {"ForkEvery": 50},
    {"Parallel": False}, {"GradleDaemon": False}, {"KotlinCompilerStrategy": "project"},
])
def test_non_gradle_profiles_reject_even_explicit_default_options(tmp_path, profile, options):
    source = _functions("Test-GradleProfile", "Resolve-InvocationGradleSettings")
    result = _powershell_json(tmp_path, source + _invocation_setup(options, profile) + """
try {
    $settings = Resolve-InvocationGradleSettings
    @{ outcome = if ($null -eq $settings) { 'not-applicable' } else { 'unexpected-settings' } } | ConvertTo-Json
} catch { @{ outcome = $_.Exception.Message } | ConvertTo-Json }
""")
    assert result == {
        "outcome": "E_GRADLE_OPTIONS_NOT_APPLICABLE" if options else "not-applicable",
    }


@pytest.mark.parametrize("profile", ["compile", "targeted-release-unit-test", *NON_GRADLE_PROFILES])
def test_old_requests_without_gradle_settings_keep_historical_defaults(tmp_path, profile):
    source = _functions("Test-GradleProfile", "New-GradleSettings", "Resolve-RequestGradleSettings")
    result = _powershell_json(tmp_path, source + f"""
$request = {_ps_json({'profile': profile})}
@{{ settings = (Resolve-RequestGradleSettings -Request $request) }} | ConvertTo-Json -Depth 8
""")
    assert result["settings"] == (None if profile in NON_GRADLE_PROFILES else DEFAULT_GRADLE_SETTINGS)


@pytest.mark.parametrize("settings,expected", [
    (DEFAULT_GRADLE_SETTINGS, DEFAULT_GRADLE_SETTINGS),
    (_settings(fork_every=0, parallel=True, gradle_daemon=True, kotlin_compiler_strategy="Daemon"),
     _settings(fork_every=0, parallel=True, gradle_daemon=True, kotlin_compiler_strategy="daemon")),
    (_settings(max_workers=16, test_forks=8, fork_every=1000, kotlin_compiler_strategy="IN-PROCESS"),
     _settings(max_workers=16, test_forks=8, fork_every=1000, kotlin_compiler_strategy="in-process")),
])
def test_new_request_settings_preserve_counts_false_zero_and_normalize_strategy(tmp_path, settings, expected):
    source = _functions("Test-GradleProfile", "New-GradleSettings", "Resolve-RequestGradleSettings")
    result = _powershell_json(tmp_path, source + f"""
$request = {_ps_json({'profile': 'compile', 'gradle_settings': settings})}
@{{ settings = (Resolve-RequestGradleSettings -Request $request) }} | ConvertTo-Json -Depth 8
""")
    assert result["settings"] == expected


def _malformed_settings_cases():
    cases = [pytest.param(value, "E_GRADLE_SETTINGS_INVALID", id=name) for name, value in [
        ("null", None), ("empty-object", {}), ("array", []), ("string", "defaults"),
    ]]
    for key in DEFAULT_GRADLE_SETTINGS:
        missing = _settings()
        del missing[key]
        cases.append(pytest.param(missing, "E_GRADLE_SETTINGS_INVALID", id=f"missing-{key}"))
        cases.append(pytest.param(_settings(**{key: None}), "E_GRADLE_SETTINGS_INVALID", id=f"null-{key}"))
    for key in ("max_workers", "test_forks", "fork_every"):
        for label, value in (("bool", True), ("string", "1"), ("fraction", 1.5), ("integral-float", 1.0), ("array", [1])):
            cases.append(pytest.param(_settings(**{key: value}), "E_GRADLE_SETTINGS_INVALID", id=f"{key}-{label}"))
    for key in ("parallel", "gradle_daemon"):
        for label, value in (("string", "false"), ("integer", 0), ("array", [False])):
            cases.append(pytest.param(_settings(**{key: value}), "E_GRADLE_SETTINGS_INVALID", id=f"{key}-{label}"))
    for value in (True, 1, ["daemon"]):
        cases.append(pytest.param(_settings(kotlin_compiler_strategy=value), "E_GRADLE_SETTINGS_INVALID", id=f"strategy-type-{type(value).__name__}"))
    for key, value in (
        ("max_workers", 0), ("max_workers", 17), ("test_forks", 0),
        ("test_forks", 9), ("fork_every", -1), ("fork_every", 1001),
        ("kotlin_compiler_strategy", "out-of-process"),
    ):
        cases.append(pytest.param(_settings(**{key: value}), None, id=f"range-{key}-{value}"))
    cases.append(pytest.param(_settings(test_forks=2), "E_TEST_FORKS_EXCEED_WORKERS", id="forks-exceed-workers"))
    return cases


@pytest.mark.parametrize("settings,expected_code", _malformed_settings_cases())
def test_request_settings_fail_closed_on_missing_null_wrong_types_and_ranges(tmp_path, settings, expected_code):
    source = _functions("Test-GradleProfile", "New-GradleSettings", "Resolve-RequestGradleSettings")
    result = _powershell_json(tmp_path, source + f"""
$request = {_ps_json({'profile': 'compile', 'gradle_settings': settings})}
try {{
    $null = Resolve-RequestGradleSettings -Request $request
    @{{ accepted = $true }} | ConvertTo-Json
}} catch {{
    @{{ accepted = $false; code = $_.Exception.Message; exception_type = $_.Exception.GetType().Name }} | ConvertTo-Json
}}
""")
    assert result["accepted"] is False
    if expected_code is not None:
        assert result["code"] == expected_code
    else:
        assert result["exception_type"].startswith("ParameterBinding")


@pytest.mark.parametrize("profile", NON_GRADLE_PROFILES)
@pytest.mark.parametrize("settings", [None, DEFAULT_GRADLE_SETTINGS])
def test_non_gradle_request_settings_only_accept_null(tmp_path, profile, settings):
    source = _functions("Test-GradleProfile", "Resolve-RequestGradleSettings")
    result = _powershell_json(tmp_path, source + f"""
$request = {_ps_json({'profile': profile, 'gradle_settings': settings})}
try {{
    $settings = Resolve-RequestGradleSettings -Request $request
    @{{ outcome = if ($null -eq $settings) {{ 'not-applicable' }} else {{ 'unexpected' }} }} | ConvertTo-Json
}} catch {{ @{{ outcome = $_.Exception.Message }} | ConvertTo-Json }}
""")
    assert result == {"outcome": "not-applicable" if settings is None else "E_GRADLE_OPTIONS_NOT_APPLICABLE"}


@pytest.mark.parametrize("options,duplicate", [
    ({"MaxWorkers": 4}, True),
    ({"MaxWorkers": 5}, False),
    ({"MaxWorkers": 4, "TestForks": 2}, False),
    ({"MaxWorkers": 4, "ForkEvery": 0}, False),
    ({"MaxWorkers": 4, "TestForks": 1, "Parallel": True}, False),
    ({"MaxWorkers": 4, "GradleDaemon": True}, False),
    ({"MaxWorkers": 4, "KotlinCompilerStrategy": "Daemon"}, False),
    ({"MaxWorkers": 4, "KotlinCompilerStrategy": "in-process"}, False),
    ({"MaxWorkers": 4, "KotlinCompilerStrategy": "PROJECT"}, True),
])
def test_dedup_uses_commands_that_distinguish_every_effective_gradle_setting(tmp_path, options, duplicate):
    root, source = _gradle_fixture(tmp_path)
    run = root / "build" / "validation-runs" / "vr-20260101-000000-deadbeef"
    run.mkdir()
    baseline = _command_display("gradlew.bat", _gradle_arguments("compile", _settings(max_workers=4)))
    (run / "result.json").write_text(json.dumps({
        "profile": "compile", "status": "PASS", "exit_code": 0,
        "command": baseline, "worktree_fingerprint_start": "fingerprint",
        "worktree_fingerprint_end": "fingerprint",
    }), encoding="utf-8")
    (run / "complete.marker").write_text("complete", encoding="utf-8")
    source += _functions("Find-OverlappingSuccessfulRun") + "\n" + _invocation_setup(options)
    result = _powershell_json(tmp_path, source + """
$settings = Resolve-InvocationGradleSettings
$spec = Get-ProfileSpec -Name $Profile -GradleSettings $settings
$command = Get-SafeCommandDisplay -Spec $spec
$match = Find-OverlappingSuccessfulRun -RequestedProfile $Profile -RequestedCommand $command -Fingerprint 'fingerprint'
@{ duplicate = $null -ne $match } | ConvertTo-Json
""")
    assert result == {"duplicate": duplicate}


def _detached_worker_case(
    tmp_path, options=None, request_patch="", profile="compile", record_patch="",
    test_filter="*ProtocolCase*",
):
    root, source = _gradle_fixture(tmp_path)
    capture = root / "capture argv.py"
    capture.write_text("""import json
import os
import sys
import time
from pathlib import Path

run = Path(os.environ["PROTOCOL_RUN_DIRECTORY"])
for attempt in range(100):
    try:
        record = json.loads((run / "result.json").read_text(encoding="utf-8-sig"))
        break
    except (OSError, ValueError):
        time.sleep(0.01)
else:
    raise RuntimeError("fixture launch metadata unreadable")
(run / "executed.json").write_text(json.dumps({
    "argv": sys.argv[1:], "cwd": os.getcwd(), "pid": os.getpid(),
    "android_serial": os.environ.get("ANDROID_SERIAL"),
    "worker_pid": int(os.environ["PROTOCOL_WORKER_PID"]),
    "record_at_launch": record,
}), encoding="utf-8")
print("harmless argv capture; no Gradle")
""", encoding="utf-8")
    source += "\n" + _functions(
        "Get-UtcTimestamp", "Write-JsonAtomic", "Get-RunDirectory", "New-LockFile",
        "ConvertTo-ProcessArguments", "Find-OverlappingSuccessfulRun",
        "Start-ValidationRun", "Invoke-ValidationWorker",
    )
    # Keep command construction and both settings resolvers real. Replace only
    # the executable transport, external-process probing, and git fingerprint.
    # Start-Process and the worker's ProcessStartInfo/Process.Start are NOT mocked.
    source += f"""
$script:OriginalProfileSpec = ${{function:Get-ProfileSpec}}
function Get-ProfileSpec {{
    param($Name, $Filter, $ShardName, $SelectedGuard, $RequestedPython, $GradleSettings = $null, $SelectedSerial)
    $spec = & $script:OriginalProfileSpec @PSBoundParameters
    $spec.executable = {_ps_quote(sys.executable)}
    $spec.arguments = @({_ps_quote(capture)}) + @($spec.arguments)
    return $spec
}}
function Resolve-PythonCommand {{ param($Requested) return {_ps_quote(sys.executable)} }}
function Test-ExternalGradleClient {{ return $false }}
function Get-WorktreeFingerprint {{ return 'isolated-fingerprint' }}
function Get-GitRevision {{ return 'isolated-revision' }}
function Resolve-ActiveLock {{ return Read-JsonFile -Path $script:LockPath }}
$script:ScriptPath = $PSCommandPath
if ($Action -eq 'Start') {{
{_invocation_setup(options or {}, profile)}
    $TestFilter = {_ps_quote(test_filter)}
    $Shard = 'protocol-shard'
    $GuardId = 'protocol_guard'
    $PythonExecutable = ''
    $TimeoutSeconds = 20
    $NoOutputTimeoutSeconds = 10
    $AllowOverlap = $false
    $OverlapReasonCode = ''
    $started = Start-ValidationRun | ConvertFrom-Json
    $record = Read-JsonFile -Path (Join-Path (Get-RunDirectory -Id $started.run_id) 'result.json')
    $worker = Get-Process -Id $record.worker_pid -ErrorAction SilentlyContinue
    try {{
        if ($null -ne $worker -and -not $worker.WaitForExit(30000)) {{ throw 'Detached fixture timed out' }}
    }} finally {{
        if ($null -ne $worker -and -not $worker.HasExited) {{
            Stop-Process -InputObject $worker -Force -ErrorAction SilentlyContinue
        }}
    }}
    $started | Add-Member -NotePropertyName starter_pid -NotePropertyValue $PID
    $started | ConvertTo-Json -Depth 8
    return
}}
if ($Action -ne 'Worker') {{ throw 'Fixture action invalid' }}
$run = Get-RunDirectory -Id $RunId
$recordPath = Join-Path $run 'result.json'
# Wait for the real parent handoff before snapshotting or corrupting fixtures.
$owned = $false
for ($attempt = 0; $attempt -lt 200; $attempt++) {{
    $record = Read-JsonFile -Path $recordPath
    $lock = Read-JsonFile -Path $script:LockPath
    if ($null -ne $lock -and $lock.run_id -eq $RunId -and
        $lock.worker_pid -eq $PID -and $record.worker_pid -eq $PID) {{ $owned = $true; break }}
    Start-Sleep -Milliseconds 50
}}
if (-not $owned) {{ throw 'Fixture parent handoff missing' }}
$request = Read-JsonFile -Path (Join-Path $run 'request.json')
Write-JsonAtomic -Path (Join-Path $run 'start-request.json') -Value $request
Write-JsonAtomic -Path (Join-Path $run 'start-result.json') -Value $record
{request_patch}
Write-JsonAtomic -Path (Join-Path $run 'request.json') -Value $request
# Cached metadata is not authoritative: the worker must replace it before launch.
$record.command = 'untrusted cached metadata'
$record.gradle_settings = $null
{record_patch}
Write-JsonAtomic -Path $recordPath -Value $record
Write-JsonAtomic -Path (Join-Path $run 'worker-input-result.json') -Value $record
$env:PROTOCOL_RUN_DIRECTORY = $run
$env:PROTOCOL_WORKER_PID = [string]$PID
# A fresh worker must not re-resolve settings from these unrelated globals.
{_invocation_setup({'MaxWorkers': 13, 'TestForks': 7, 'ForkEvery': 999, 'Parallel': True, 'GradleDaemon': True, 'KotlinCompilerStrategy': 'in-process'})}
Invoke-ValidationWorker
"""
    shim = root / "isolated runner.ps1"
    shim.write_text(
        "param([string]$Action, [string]$RunId)\n"
        "Set-StrictMode -Version Latest\n$ErrorActionPreference = 'Stop'\n" + source,
        encoding="utf-8-sig",
    )
    started = _powershell_json(tmp_path, f"& {_ps_quote(shim)} -Action Start", timeout=45)
    run = root / "build" / "validation-runs" / started["run_id"]
    assert run.parent.parent.parent == root
    assert (run / "complete.marker").is_file(), started
    assert not (run.parent / "active.lock.json").exists()
    records = {
        name: json.loads((run / f"{name}.json").read_text(encoding="utf-8-sig"))
        for name in ("request", "result", "start-request", "start-result", "worker-input-result")
    }
    executed = run / "executed.json"
    records["executed"] = json.loads(executed.read_text(encoding="utf-8")) if executed.exists() else None
    return root, run, capture, started, records


@pytest.mark.parametrize("options,expected", TUNING_CASES)
def test_start_serialization_survives_fresh_detached_worker_and_actual_argv_capture(tmp_path, options, expected):
    root, run, capture, started, records = _detached_worker_case(tmp_path, options)
    arguments = _gradle_arguments("compile", expected)
    command = _command_display(sys.executable, [str(capture), *arguments])
    for name in ("start-request", "start-result", "request", "result"):
        assert records[name]["gradle_settings"] == expected
        assert records[name]["command"] == command
    assert records["start-result"]["status"] == "STARTING"
    result = records["result"]
    assert (result["status"], result["exit_code"], result["failure_code"]) == ("PASS", 0, None)
    executed = records["executed"]
    assert executed["argv"] == arguments
    assert executed["cwd"] == str(root)
    assert result["child_pid"] == executed["pid"]
    assert result["worker_pid"] == executed["worker_pid"]
    assert len({started["starter_pid"], result["worker_pid"], executed["pid"]}) == 3
    assert executed["record_at_launch"]["status"] == "RUNNING"
    assert executed["record_at_launch"]["command"] == command
    assert executed["record_at_launch"]["gradle_settings"] == expected
    assert "status=PASS" in (run / "complete.marker").read_text(encoding="utf-8-sig")
    assert "harmless argv capture; no Gradle" in (run / "stdout.log").read_text(encoding="utf-8")


def test_detached_worker_accepts_legacy_request_without_settings_or_command(tmp_path):
    request_patch = """
$request.PSObject.Properties.Remove('gradle_settings')
$request.PSObject.Properties.Remove('command')
"""
    record_patch = """
$record.PSObject.Properties.Remove('command')
$record.PSObject.Properties.Remove('gradle_settings')
"""
    _, _, capture, _, records = _detached_worker_case(
        tmp_path, {"Parallel": True, "GradleDaemon": True},
        request_patch=request_patch, record_patch=record_patch,
    )
    arguments = _gradle_arguments("compile", DEFAULT_GRADLE_SETTINGS)
    assert "gradle_settings" not in records["request"]
    assert "command" not in records["request"]
    assert "gradle_settings" not in records["worker-input-result"]
    assert "command" not in records["worker-input-result"]
    assert records["result"]["status"] == "PASS"
    assert records["result"]["gradle_settings"] == DEFAULT_GRADLE_SETTINGS
    assert records["result"]["command"] == _command_display(sys.executable, [str(capture), *arguments])
    assert records["executed"]["argv"] == arguments


@pytest.mark.parametrize("profile", [
    "benchmark-compile", "benchmark-test-shard", "benchmark-app-check",
])
def test_benchmark_profiles_survive_detached_worker_without_excluding_tasks(tmp_path, profile):
    options = {"GradleDaemon": True, "MaxWorkers": 4, "TestForks": 2}
    settings = _settings(gradle_daemon=True, max_workers=4, test_forks=2)
    _, run, _, _, records = _detached_worker_case(tmp_path, options, profile=profile)
    assert records["result"]["status"] == "PASS"
    argv = records["executed"]["argv"]
    assert argv == _gradle_arguments(profile, settings)
    assert "--rerun" in argv
    assert "--no-build-cache" in argv
    assert "--profile" in argv
    assert "-x" not in argv and "--exclude-task" not in argv
    if profile == "benchmark-app-check":
        assert argv[:5] == [
            ":app:testDebugUnitTest", "--rerun",
            ":app:testReleaseUnitTest", "--rerun", ":app:check",
        ]
    assert "status=PASS" in (run / "complete.marker").read_text(encoding="utf-8-sig")


def test_detached_non_gradle_worker_preserves_null_settings_and_executes_only_fake_command(tmp_path):
    _, _, capture, _, records = _detached_worker_case(tmp_path, profile="static-guards")
    arguments = ["scripts/ci/run_static_guard_suite.py", "--output-dir", "build/ci/static-guards"]
    command = _command_display(sys.executable, [str(capture), *arguments])
    for name in ("start-request", "start-result", "result"):
        assert records[name]["gradle_settings"] is None
        assert records[name]["command"] == command
    assert records["result"]["status"] == "PASS"
    assert records["executed"]["argv"] == arguments
    assert records["executed"]["record_at_launch"]["gradle_settings"] is None


@pytest.mark.parametrize("patch", [
    "$request.command += ' --unexpected'",
    "$request.command = $request.command.ToUpperInvariant()",
    "$request.PSObject.Properties.Remove('command')",
    "$request.command = $null",
    "$request.command = ''",
    "$request.command = 123",
])
@pytest.mark.parametrize("profile", ["compile", "static-guards"])
def test_detached_worker_rejects_mismatched_or_missing_new_request_command_before_launch(tmp_path, patch, profile):
    _, run, capture, _, records = _detached_worker_case(tmp_path, request_patch=patch, profile=profile)
    result = records["result"]
    assert (result["status"], result["exit_code"], result["failure_code"]) == ("INFRA_FAILURE", 2, "E_COMMAND_MISMATCH")
    assert result["child_pid"] is None
    assert records["executed"] is None
    assert result["finished_at"]
    assert result["gradle_settings"] == (DEFAULT_GRADLE_SETTINGS if profile == "compile" else None)
    arguments = (_gradle_arguments(profile, DEFAULT_GRADLE_SETTINGS) if profile == "compile" else
                 ["scripts/ci/run_static_guard_suite.py", "--output-dir", "build/ci/static-guards"])
    assert result["command"] == _command_display(sys.executable, [str(capture), *arguments])
    assert "status=INFRA_FAILURE" in (run / "complete.marker").read_text(encoding="utf-8-sig")


@pytest.mark.parametrize("patch,profile,code", [
    ("$request.gradle_settings = $null", "compile", "E_GRADLE_SETTINGS_INVALID"),
    ("$request.gradle_settings.max_workers = '4'", "compile", "E_GRADLE_SETTINGS_INVALID"),
    ("$request.gradle_settings.parallel = 'false'", "compile", "E_GRADLE_SETTINGS_INVALID"),
    ("$request.gradle_settings.test_forks = 2", "compile", "E_TEST_FORKS_EXCEED_WORKERS"),
    ("$request.gradle_settings.fork_every = 1001", "compile", "E_COMMAND_LAUNCH_FAILED"),
    ("$request.gradle_settings = [pscustomobject]@{ max_workers = 1 }", "static-guards", "E_GRADLE_OPTIONS_NOT_APPLICABLE"),
])
def test_detached_worker_finalizes_malformed_settings_as_infra_without_launch(tmp_path, patch, profile, code):
    _, run, _, _, records = _detached_worker_case(tmp_path, request_patch=patch, profile=profile)
    result = records["result"]
    assert (result["status"], result["exit_code"], result["failure_code"]) == ("INFRA_FAILURE", 2, code)
    assert result["child_pid"] is None
    assert records["executed"] is None
    assert result["finished_at"]
    assert result["worktree_fingerprint_end"] == "isolated-fingerprint"
    assert "exit_code=2" in (run / "complete.marker").read_text(encoding="utf-8-sig")


# --- D-22 / B-2: connected-test-class, -DeviceSerial, assemble-release, evidence ---

def _serial_arguments(serial):
    return [f"-PvalidationDeviceSerial={serial}"] if serial else []


def _spec_outcome(tmp_path, profile, filter_value=None, serial=None):
    _, source = _gradle_fixture(tmp_path)
    options = {} if serial is None else {"DeviceSerial": serial}
    return _powershell_json(tmp_path, source + _invocation_setup(options, profile) + f"""
function Resolve-PythonCommand {{ param($Requested) return 'C:\\fake\\python.exe' }}
try {{
    $settings = Resolve-InvocationGradleSettings
    $serial = Resolve-InvocationDeviceSerial
    $spec = Get-ProfileSpec -Name $Profile -Filter {_ps_value(filter_value)} -ShardName 'protocol-shard' -GradleSettings $settings -SelectedSerial $serial
    @{{ outcome = 'accepted'; arguments = $spec.arguments; timeout = $spec.timeout; no_output_timeout = $spec.no_output_timeout }} | ConvertTo-Json -Depth 8
}} catch {{ @{{ outcome = $_.Exception.Message }} | ConvertTo-Json }}
""")


def test_connected_test_class_exact_argv_and_timeouts(tmp_path):
    result = _spec_outcome(tmp_path, "connected-test-class", CLASS_FILTER)
    assert result["outcome"] == "accepted"
    assert result["arguments"] == [
        ":app:connectedDebugAndroidTest",
        "-Pandroid.testInstrumentationRunnerArguments.class=com.yourname.expensetracker.ProtocolCase",
        "--console=plain", "--no-parallel", "--max-workers=1",
        "-PvalidationMaxParallelForks=1", "-PvalidationForkEvery=50", "--no-daemon",
    ]
    assert (result["timeout"], result["no_output_timeout"]) == (3600, 900)


@pytest.mark.parametrize("filter_value", [
    "com.yourname.expensetracker.feature.Case",
    "com.yourname.expensetracker.A1_b.Case_2",
    "com.yourname.expensetracker._Case",
])
def test_connected_test_class_accepts_package_qualified_classes(tmp_path, filter_value):
    result = _spec_outcome(tmp_path, "connected-test-class", filter_value)
    assert result["outcome"] == "accepted"
    assert result["arguments"][1] == f"-Pandroid.testInstrumentationRunnerArguments.class={filter_value}"


@pytest.mark.parametrize("filter_value", [
    "com.yourname.expensetracker.*",
    "com.yourname.expensetracker.A*",
    "*ProtocolCase*",
    "com.yourname.expensetracker.A,com.yourname.expensetracker.B",
    "com.yourname.expensetracker.A#method",
    "com.yourname.expensetracker.A=b",
    "com.yourname.expensetracker.A B",
    "com.yourname.expensetracker.A'",
    'com.yourname.expensetracker.A"',
    "com.yourname.expensetracker.A$Inner",
    "com.yourname.expensetracker.A\n",
    "com.yourname.expensetracker",
    "com.yourname.expensetracker.",
    "com.yourname.expensetracker..A",
    "com.yourname.expensetracker.1A",
    "com.yourname.expensetrackerX.A",
    "com.other.expensetracker.A",
    "Com.yourname.expensetracker.A",
    "ProtocolCase",
])
def test_connected_test_class_rejects_unsafe_or_foreign_filters(tmp_path, filter_value):
    assert _spec_outcome(tmp_path, "connected-test-class", filter_value) == {"outcome": "E_INVALID_TEST_FILTER"}


@pytest.mark.parametrize("filter_value", [None, ""])
def test_connected_test_class_requires_a_filter(tmp_path, filter_value):
    assert _spec_outcome(tmp_path, "connected-test-class", filter_value) == {"outcome": "E_TEST_FILTER_REQUIRED"}


def test_connected_tests_default_argv_is_byte_identical_without_device_serial(tmp_path):
    result = _spec_outcome(tmp_path, "connected-tests")
    assert result["arguments"] == [
        ":app:connectedDebugAndroidTest", "--console=plain", "--no-parallel",
        "--max-workers=1", "-PvalidationMaxParallelForks=1", "-PvalidationForkEvery=50",
        "--no-daemon",
    ]
    assert (result["timeout"], result["no_output_timeout"]) == (7200, 1200)


def test_assemble_release_argv_and_timeouts(tmp_path):
    result = _spec_outcome(tmp_path, "assemble-release", "ignored-filter")
    assert result["arguments"] == [
        ":app:assembleRelease", "--console=plain", "--no-parallel", "--max-workers=1",
        "-PvalidationMaxParallelForks=1", "-PvalidationForkEvery=50", "--no-daemon",
    ]
    assert (result["timeout"], result["no_output_timeout"]) == (3600, 900)
    assert "--tests" not in result["arguments"]


def test_release_artifact_evidence_uses_fixed_python_argv_without_free_inputs(tmp_path):
    result = _spec_outcome(tmp_path, "release-artifact-evidence", "ignored-filter")
    assert result["arguments"] == ["scripts/ci/release_artifact_evidence.py"]
    assert (result["timeout"], result["no_output_timeout"]) == (600, 300)


@pytest.mark.parametrize("profile", CONNECTED_PROFILES)
@pytest.mark.parametrize("serial", [
    "emulator-5554", "R5CT1234ABC", "192.168.1.5:5555", "a.b_c-d:e", "A" * 64,
])
def test_device_serial_is_accepted_on_both_connected_profiles(tmp_path, profile, serial):
    result = _spec_outcome(tmp_path, profile, PROFILE_FILTERS.get(profile), serial)
    assert result["outcome"] == "accepted"
    # Inert recorded property follows the standard Gradle tail.
    assert result["arguments"][-1] == f"-PvalidationDeviceSerial={serial}"
    assert result["arguments"][:-1] == _gradle_arguments(profile, DEFAULT_GRADLE_SETTINGS)


@pytest.mark.parametrize("profile", [
    "compile", "assemble-debug", "assemble-release", "targeted-unit-test",
    "unit-tests", "app-check", "lint", "runner-smoke", "static-guards",
    "registered-guard", "release-artifact-evidence",
])
def test_device_serial_is_rejected_on_every_other_profile(tmp_path, profile):
    assert _spec_outcome(tmp_path, profile, "*ProtocolCase*", SERIAL) == {
        "outcome": "E_DEVICE_SERIAL_NOT_APPLICABLE",
    }


@pytest.mark.parametrize("serial", [
    "", " ", "bad serial", "a;b", "a'b", 'a"b', "a/b", "a\\b", "a&b", "a$b",
    "a=b", "a,b", "A" * 65, "emulator-5554\n", "-Pevil=1 x",
])
@pytest.mark.parametrize("profile", CONNECTED_PROFILES)
def test_invalid_device_serial_is_rejected_on_connected_profiles(tmp_path, profile, serial):
    result = _spec_outcome(tmp_path, profile, PROFILE_FILTERS.get(profile), serial)
    assert result == {"outcome": "E_INVALID_DEVICE_SERIAL"}


def test_omitted_device_serial_is_not_recorded_or_validated(tmp_path):
    _, source = _gradle_fixture(tmp_path)
    result = _powershell_json(tmp_path, source + _invocation_setup({}, "compile") + """
@{ serial = (Resolve-InvocationDeviceSerial) } | ConvertTo-Json
""")
    assert result == {"serial": None}


def test_start_validates_inputs_before_claiming_the_lock():
    start = _functions("Start-ValidationRun")
    assert (start.index("Resolve-InvocationDeviceSerial") < start.index("Get-ProfileSpec")
            < start.index("New-LockFile"))
    assert "-SelectedSerial $requestedSerial" in start
    worker = _functions("Invoke-ValidationWorker")
    assert 'EnvironmentVariables["ANDROID_SERIAL"] = $requestSerial' in worker


@pytest.mark.parametrize("profile,filter_value,serial,duplicate", [
    ("connected-test-class", CLASS_FILTER, SERIAL, True),
    ("connected-test-class", CLASS_FILTER, None, False),
    ("connected-test-class", CLASS_FILTER, "emulator-5556", False),
    ("connected-test-class", "com.yourname.expensetracker.OtherCase", SERIAL, False),
    ("connected-tests", None, SERIAL, True),
    ("connected-tests", None, None, False),
    ("connected-tests", None, "emulator-5556", False),
])
def test_dedup_distinguishes_connected_class_and_device_serial(
    tmp_path, profile, filter_value, serial, duplicate,
):
    root, source = _gradle_fixture(tmp_path)
    run = root / "build" / "validation-runs" / "vr-20260101-000000-deadbeef"
    run.mkdir()
    baseline = _command_display(
        "gradlew.bat", _gradle_arguments(profile, DEFAULT_GRADLE_SETTINGS) + _serial_arguments(SERIAL),
    )
    (run / "result.json").write_text(json.dumps({
        "profile": profile, "status": "PASS", "exit_code": 0, "command": baseline,
        "worktree_fingerprint_start": "fingerprint", "worktree_fingerprint_end": "fingerprint",
    }), encoding="utf-8")
    (run / "complete.marker").write_text("complete", encoding="utf-8")
    options = {} if serial is None else {"DeviceSerial": serial}
    source += _functions("Find-OverlappingSuccessfulRun") + "\n" + _invocation_setup(options, profile)
    result = _powershell_json(tmp_path, source + f"""
$settings = Resolve-InvocationGradleSettings
$serial = Resolve-InvocationDeviceSerial
$spec = Get-ProfileSpec -Name $Profile -Filter {_ps_value(filter_value)} -GradleSettings $settings -SelectedSerial $serial
$command = Get-SafeCommandDisplay -Spec $spec
$match = Find-OverlappingSuccessfulRun -RequestedProfile $Profile -RequestedCommand $command -Fingerprint 'fingerprint'
@{{ duplicate = $null -ne $match }} | ConvertTo-Json
""")
    assert result == {"duplicate": duplicate}


@pytest.mark.parametrize("profile,filter_value,serial,command_arguments,timeouts,settings", [
    ("connected-test-class", CLASS_FILTER, SERIAL,
     _gradle_arguments("connected-test-class", DEFAULT_GRADLE_SETTINGS) + [f"-PvalidationDeviceSerial={SERIAL}"],
     (3600, 900), DEFAULT_GRADLE_SETTINGS),
    ("connected-tests", None, "R5CT1234ABC",
     _gradle_arguments("connected-tests", DEFAULT_GRADLE_SETTINGS) + ["-PvalidationDeviceSerial=R5CT1234ABC"],
     (7200, 1200), DEFAULT_GRADLE_SETTINGS),
    ("assemble-release", None, None,
     _gradle_arguments("assemble-release", DEFAULT_GRADLE_SETTINGS), (3600, 900), DEFAULT_GRADLE_SETTINGS),
    ("release-artifact-evidence", None, None,
     ["scripts/ci/release_artifact_evidence.py"], (600, 300), None),
])
def test_plan_output_for_new_profiles(
    tmp_path, profile, filter_value, serial, command_arguments, timeouts, settings,
):
    _, source = _gradle_fixture(tmp_path)
    options = {} if serial is None else {"DeviceSerial": serial}
    source += _functions("Show-ValidationPlan") + "\n" + _invocation_setup(options, profile)
    result = _powershell_json(tmp_path, source + f"""
function Resolve-PythonCommand {{ param($Requested) return 'C:\\fake\\python.exe' }}
$TestFilter = {_ps_value(filter_value)}
$Shard = ''
$GuardId = ''
$PythonExecutable = ''
$TimeoutSeconds = 0
$NoOutputTimeoutSeconds = 0
Show-ValidationPlan
""")
    executable = "python.exe" if profile == "release-artifact-evidence" else "gradlew.bat"
    assert result["profile"] == profile
    assert result["command"] == _command_display(executable, command_arguments)
    assert (result["timeout_seconds"], result["no_output_timeout_seconds"]) == timeouts
    assert result["gradle_settings"] == settings
    assert result["device_serial"] == serial


def test_profiles_listing_names_every_profile_accepted_by_the_validate_set():
    source = RUNNER.read_text(encoding="utf-8-sig")
    declared = re.search(r'(?s)\[ValidateSet\(\s*("runner-smoke".*?)\)\]\s*\[string\]\$Profile', source)
    assert declared is not None
    names = re.findall(r'"([a-z0-9-]+)"', declared.group(1))
    for new_profile in ("connected-test-class", "assemble-release", "release-artifact-evidence"):
        assert new_profile in names
    listing = _powershell_listing()
    assert sorted(entry["name"] for entry in listing) == sorted(names)
    assert all(entry["purpose"] for entry in listing)


def _powershell_listing():
    completed = _powershell(_functions("Show-Profiles") + "\nShow-Profiles")
    assert completed.returncode == 0, (completed.stdout, completed.stderr)
    return json.loads(completed.stdout)


@pytest.mark.parametrize("profile", [
    "connected-test-class", "assemble-release", "release-artifact-evidence",
])
@pytest.mark.parametrize("patch", [
    "$request.command += ' --unexpected'",
    "$request.command = $request.command.ToUpperInvariant()",
    "$request.PSObject.Properties.Remove('command')",
])
def test_detached_worker_rejects_tampered_request_for_new_profiles(tmp_path, patch, profile):
    _, run, capture, _, records = _detached_worker_case(
        tmp_path, request_patch=patch, profile=profile,
        test_filter=PROFILE_FILTERS.get(profile, "*ProtocolCase*"),
    )
    result = records["result"]
    assert (result["status"], result["exit_code"], result["failure_code"]) == ("INFRA_FAILURE", 2, "E_COMMAND_MISMATCH")
    assert result["child_pid"] is None
    assert records["executed"] is None
    arguments = (["scripts/ci/release_artifact_evidence.py"] if profile == "release-artifact-evidence"
                 else _gradle_arguments(profile, DEFAULT_GRADLE_SETTINGS))
    assert result["command"] == _command_display(sys.executable, [str(capture), *arguments])
    assert "status=INFRA_FAILURE" in (run / "complete.marker").read_text(encoding="utf-8-sig")


@pytest.mark.parametrize("patch,profile,code", [
    ("$request.device_serial = 'emulator-9999'", "connected-tests", "E_COMMAND_MISMATCH"),
    ("$request.device_serial = $null", "connected-tests", "E_COMMAND_MISMATCH"),
    ("$request.device_serial = 'bad serial;'", "connected-tests", "E_INVALID_DEVICE_SERIAL"),
    ("$request.device_serial = 'bad serial;'", "connected-test-class", "E_INVALID_DEVICE_SERIAL"),
    ("$request.device_serial = 'emulator-5554'", "compile", "E_DEVICE_SERIAL_NOT_APPLICABLE"),
    ("$request.device_serial = 'emulator-5554'", "release-artifact-evidence", "E_DEVICE_SERIAL_NOT_APPLICABLE"),
    ("$request.test_filter = 'com.yourname.expensetracker.*'", "connected-test-class", "E_INVALID_TEST_FILTER"),
    ("$request.test_filter = $null", "connected-test-class", "E_TEST_FILTER_REQUIRED"),
])
def test_detached_worker_revalidates_serial_and_class_filter_without_launch(tmp_path, patch, profile, code):
    options = {"DeviceSerial": SERIAL} if profile in CONNECTED_PROFILES else {}
    _, run, _, _, records = _detached_worker_case(
        tmp_path, options, request_patch=patch, profile=profile,
        test_filter=PROFILE_FILTERS.get(profile, "*ProtocolCase*"),
    )
    result = records["result"]
    assert (result["status"], result["exit_code"], result["failure_code"]) == ("INFRA_FAILURE", 2, code)
    assert result["child_pid"] is None
    assert records["executed"] is None
    assert "exit_code=2" in (run / "complete.marker").read_text(encoding="utf-8-sig")


@pytest.mark.parametrize("profile", CONNECTED_PROFILES)
def test_device_serial_reaches_argv_environment_and_records(tmp_path, monkeypatch, profile):
    monkeypatch.delenv("ANDROID_SERIAL", raising=False)
    root, run, capture, _, records = _detached_worker_case(
        tmp_path, {"DeviceSerial": SERIAL}, profile=profile,
        test_filter=PROFILE_FILTERS.get(profile, "*ProtocolCase*"),
    )
    arguments = _gradle_arguments(profile, DEFAULT_GRADLE_SETTINGS) + [f"-PvalidationDeviceSerial={SERIAL}"]
    command = _command_display(sys.executable, [str(capture), *arguments])
    for name in ("start-request", "request", "start-result", "result"):
        assert records[name]["device_serial"] == SERIAL
        assert records[name]["command"] == command
    assert records["result"]["status"] == "PASS"
    assert records["executed"]["argv"] == arguments
    assert records["executed"]["android_serial"] == SERIAL
    assert records["executed"]["record_at_launch"]["device_serial"] == SERIAL


@pytest.mark.parametrize("profile", ["connected-tests", "connected-test-class", "assemble-release"])
def test_profiles_without_device_serial_record_null_and_keep_default_argv(tmp_path, monkeypatch, profile):
    monkeypatch.delenv("ANDROID_SERIAL", raising=False)
    _, _, _, _, records = _detached_worker_case(
        tmp_path, profile=profile, test_filter=PROFILE_FILTERS.get(profile, "*ProtocolCase*"),
    )
    arguments = _gradle_arguments(profile, DEFAULT_GRADLE_SETTINGS)
    for name in ("start-request", "request", "start-result", "result"):
        assert records[name]["device_serial"] is None
    assert records["result"]["status"] == "PASS"
    assert records["executed"]["argv"] == arguments
    assert records["executed"]["android_serial"] is None


def test_legacy_request_without_device_serial_still_runs(tmp_path):
    _, _, _, _, records = _detached_worker_case(
        tmp_path, profile="connected-tests",
        request_patch="$request.PSObject.Properties.Remove('device_serial')",
    )
    assert "device_serial" not in records["request"]
    assert records["result"]["status"] == "PASS"
    assert records["executed"]["argv"] == _gradle_arguments("connected-tests", DEFAULT_GRADLE_SETTINGS)


def test_detached_release_artifact_evidence_runs_only_the_fixed_script_with_null_settings(tmp_path):
    _, _, capture, _, records = _detached_worker_case(tmp_path, profile="release-artifact-evidence")
    arguments = ["scripts/ci/release_artifact_evidence.py"]
    for name in ("start-request", "start-result", "result"):
        assert records[name]["gradle_settings"] is None
        assert records[name]["command"] == _command_display(sys.executable, [str(capture), *arguments])
    assert records["result"]["status"] == "PASS"
    assert records["executed"]["argv"] == arguments


def test_detached_assemble_release_runs_release_task_only(tmp_path):
    _, _, _, _, records = _detached_worker_case(tmp_path, profile="assemble-release")
    assert records["executed"]["argv"] == _gradle_arguments("assemble-release", DEFAULT_GRADLE_SETTINGS)
    assert records["executed"]["argv"][0] == ":app:assembleRelease"
