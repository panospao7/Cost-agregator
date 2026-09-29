# Wave-2 final validation packet (S1 + S4) — 2026-09-29

Author: Claude Code session (single agent, no subagents). Validation: **NOT RUN by the author**; the human runs it.

## 1. Why a new packet

`wave2-s1-exact-hash-fixture-repair-20260928-handoff.md` section 4 hard-codes the 2026-09-28 pre-handoff fingerprint `8d511011…`. The tree has since changed intentionally (static-guard repairs, cancellation fixes, baselines, journal line 125), so that block now throws `Reviewed tree has changed` by design. That file is not edited. This packet keeps the same runner profiles, S1 order, 177-case expectation, zero-skip rule, per-run XML capture, source-name matching and stop-on-failure behavior, and adds the seven S4 filters.

Differences from the 2026-09-28 block:
- No pinned pre-handoff fingerprint. The fingerprint is computed once at start (this file included), printed, written to `snapshot.json`, and required to be identical at the start and end of every run.
- S4 filters appended, with expected counts taken from the `@Test` count in current source and verified by name matching.
- Final total: 177 (S1) + 111 (S4) = **288** executed cases, 0 skipped.

## 2. Expected counts (from current source)

| Stage | Filter | Expected |
|---|---|---|
| S1 | ReceiptPartialOcrPipelineTest / OcrResultPartialTest / ReceiptLifecycleCoordinatorTest / ReceiptRepositoryBatchDuplicateTest | 22 / 4 / 35 / 5 |
| S1 | ReceiptScanViewModelTest / ReceiptScanViewModelStressTest / ReviewViewModelBatchDuplicateMessageTest | 22 / 20 / 7 |
| S1 | ReceiptSideEffectPlanner* / BankStatementCompletionStatusTest / ReceiptOcrRetryIsolationTest | 13 / 5 / 8 |
| S1 | ReceiptOcrCoverageMetadataTest / ReceiptEventDaoTest / LegacyDataConsistencyCheckerTest | 17 / 14 / 5 |
| S4 | WorkerRunLoggerTest | 60 |
| S4 | ExpenseRepositoryMerchantKeyBackfillTest / MerchantKeyBackfillWorkerTest | 8 / 7 |
| S4 | WarrantyReminderDeliveryDaoTest / WarrantyExpirationWorkerTest | 13 / 15 |
| S4 | ReceiptMatchingViewModelTest / ReceiptLinkServiceColumnScopeTest | 4 / 4 |

WorkerRunLoggerTest note: the 2026-09-27 failure (`classifyDiagnostic_timeout_returns_TIMEOUT`, "Expected TimeoutCancellationException") came from `withTimeout(1ms) { delay(10ms) }` racing. The test now uses `withTimeout(1L) { delay(Long.MAX_VALUE) }`, so the timeout must fire; assertions are unchanged. This run is the evidence; environmental attribution is not.

## 3. Rules

Keep the tree frozen, docs included, for the whole block. Poll a RUNNING run; never restart it. On any failure, keep the run and XML and stop. Do not relax counts or names, drop a filter, or skip a case. After this block passes: refresh the test-result freshness stamp **as the last write**, then run `static-guards` once (section 5).

## 4. Command

```powershell
$ErrorActionPreference = 'Stop'
$root = 'C:\Users\panos\Desktop\cost agregator\ExpenseTracker'
Set-Location -LiteralPath $root
$runner = Join-Path $root 'scripts/validation-runner.ps1'
$expectedHead = 'a48076322e57f3d312f34cf6a71139efc4fcc48b'

function Get-ReviewFingerprint {
    $tracked = @(& git -c core.quotepath=false -c core.safecrlf=false diff --name-only HEAD --)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read tracked paths' }
    $untracked = @(& git -c core.quotepath=false ls-files --others --exclude-standard)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read untracked paths' }
    $paths = @($tracked + $untracked | Sort-Object -Unique)
    $parts = New-Object 'System.Collections.Generic.List[string]'
    foreach ($relative in $paths) {
        $parts.Add('PATH:' + $relative)
        $file = Join-Path $root $relative
        if (Test-Path -LiteralPath $file -PathType Leaf) {
            $parts.Add((Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash)
        } else { $parts.Add('MISSING') }
    }
    $sha = [Security.Cryptography.SHA256]::Create()
    try {
        $bytes = [Text.Encoding]::UTF8.GetBytes(($parts -join "`n"))
        return ([BitConverter]::ToString($sha.ComputeHash($bytes))).Replace('-', '').ToLowerInvariant()
    } finally { $sha.Dispose() }
}

function Assert-Quiescent {
    $runsRoot = Join-Path $root 'build/validation-runs'
    if (Test-Path -LiteralPath (Join-Path $runsRoot 'active.lock.json')) {
        throw 'Global validation lock exists; inspect/poll its run, do not start another'
    }
    if (Test-Path -LiteralPath $runsRoot) {
        foreach ($file in @(Get-ChildItem -LiteralPath $runsRoot -Recurse -File -Filter result.json)) {
            $record = [IO.File]::ReadAllText($file.FullName) | ConvertFrom-Json
            if ($record.status -in @('STARTING','RUNNING')) { throw ('Active run: ' + $record.run_id) }
        }
    }
    $clients = @(Get-CimInstance Win32_Process | Where-Object {
        $_.ProcessId -ne $PID -and $_.CommandLine -and (
            ($_.Name -match '^java(w)?\.exe$' -and $_.CommandLine -match 'GradleWrapperMain|GradleWorkerMain') -or
            ($_.Name -match '^python[0-9.]*\.exe$' -and $_.CommandLine -match 'pytest|run_static_guard_suite|verify_[^ ]+\.py') -or
            ($_.Name -match '^(powershell|pwsh)\.exe$' -and $_.CommandLine -match 'validation-runner\.ps1.*-Action\s+(Worker|Start)')
        )
    })
    if ($clients.Count) { throw 'Another validation client is active; inspect before continuing' }
}

Assert-Quiescent
$head = (& git rev-parse HEAD).Trim()
if ($LASTEXITCODE -ne 0 -or $head -ne $expectedHead) { throw 'HEAD mismatch' }
$staged = @(& git diff --cached --name-only)
if ($LASTEXITCODE -ne 0 -or $staged.Count) { throw 'Index is not empty; nothing may be staged during validation' }
$expectedFingerprint = Get-ReviewFingerprint
Write-Host ('Frozen full-tree fingerprint: ' + $expectedFingerprint)
$evidenceRelative = 'build/validation-runs/w2-final-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
& git check-ignore --quiet -- $evidenceRelative
if ($LASTEXITCODE -ne 0) { throw 'Evidence directory would affect the worktree fingerprint' }
$evidence = Join-Path $root $evidenceRelative
if (Test-Path -LiteralPath $evidence) { throw 'Refusing to overwrite evidence' }
New-Item -ItemType Directory -Path $evidence | Out-Null
[pscustomobject]@{ head=$head; fingerprint=$expectedFingerprint; captured_at=(Get-Date).ToString('o') } |
    ConvertTo-Json | Out-File -LiteralPath (Join-Path $evidence 'snapshot.json') -Encoding utf8
$records = New-Object 'System.Collections.Generic.List[object]'

function Invoke-SerialRun([string]$Profile, [string]$Filter = '', [int]$Expected = 0, [string]$Source = '') {
    Assert-Quiescent
    if ((Get-ReviewFingerprint) -ne $expectedFingerprint) { throw 'Tree changed between runs' }
    $argsList = @('-NoProfile','-ExecutionPolicy','Bypass','-File',$runner,'-Action','Start','-Profile',$Profile)
    if ($Filter) { $argsList += @('-TestFilter',$Filter) }
    $startLines = @(& powershell.exe @argsList)
    $startCode = $LASTEXITCODE
    $startText = $startLines -join [Environment]::NewLine
    $startText | Out-File -LiteralPath (Join-Path $evidence 'start-responses.log') -Encoding utf8 -Append
    if ($startCode -ne 0) { throw ('Runner refused Start; stop without override: ' + $startText) }
    $started = $startText | ConvertFrom-Json
    $id = [string]$started.run_id
    if ($id -notmatch '^vr-[0-9]{8}-[0-9]{6}-[a-f0-9]{8}$') { throw 'Missing/invalid run ID' }
    Write-Host ($id + ' ' + $Profile + ' ' + $Filter)
    do {
        $waitLines = @(& powershell.exe -NoProfile -ExecutionPolicy Bypass -File $runner -Action Wait -RunId $id -MaxWaitSeconds 30 -PollSeconds 3)
        $waitCode = $LASTEXITCODE
        $waitText = $waitLines -join [Environment]::NewLine
        $waitText | Out-File -LiteralPath (Join-Path $evidence ($id + '-wait.log')) -Encoding utf8 -Append
        $waitResult = $waitText | ConvertFrom-Json
        if ($waitCode -eq 3 -and $waitResult.status -notin @('STARTING','RUNNING')) {
            throw ('Unexpected nonterminal result for ' + $id)
        }
    } while ($waitCode -eq 3)
    $runDirectory = Join-Path $root ('build/validation-runs/' + $id)
    $resultPath = Join-Path $runDirectory 'result.json'
    $markerPath = Join-Path $runDirectory 'complete.marker'
    if (!(Test-Path -LiteralPath $resultPath) -or !(Test-Path -LiteralPath $markerPath)) { throw ('Incomplete evidence: ' + $id) }
    $done = [IO.File]::ReadAllText($resultPath) | ConvertFrom-Json
    $marker = [IO.File]::ReadAllLines($markerPath)
    if ($waitCode -ne 0 -or $done.status -ne 'PASS' -or $done.exit_code -ne 0 -or
        $marker -notcontains 'VALIDATION_COMPLETE' -or $marker -notcontains 'status=PASS' -or $marker -notcontains 'exit_code=0') {
        throw ('STOP on failed/unknown result: ' + $id + ' ' + $done.status + ' ' + $done.failure_code)
    }
    if ($done.git_revision -ne $expectedHead -or $done.worktree_fingerprint_start -ne $expectedFingerprint -or
        $done.worktree_fingerprint_end -ne $expectedFingerprint) { throw ('Snapshot mismatch: ' + $id) }
    $archive = Join-Path $evidence $id
    New-Item -ItemType Directory -Path $archive | Out-Null
    Copy-Item -LiteralPath $resultPath -Destination (Join-Path $archive 'result.json')
    Copy-Item -LiteralPath $markerPath -Destination (Join-Path $archive 'complete.marker')
    $caseRows = New-Object 'System.Collections.Generic.List[object]'
    $xmlHashes = New-Object 'System.Collections.Generic.List[object]'
    if ($Filter) {
        $stdout = [IO.File]::ReadAllText((Join-Path $root $done.stdout_log))
        $taskLines = @($stdout -split '\r?\n' | Where-Object { $_ -match '^(> Task )?:app:testDebugUnitTest(\s|$)' })
        if (!$taskLines.Count -or ($taskLines -match 'UP-TO-DATE|FROM-CACHE|SKIPPED|NO-SOURCE')) {
            throw ('Actual unit-test task execution is not evidenced: ' + $id)
        }
        $xmlRoot = Join-Path $root 'app/build/test-results/testDebugUnitTest'
        $xmlFiles = @(Get-ChildItem -LiteralPath $xmlRoot -File -Filter 'TEST-*.xml')
        if (!$xmlFiles.Count) { throw ('No JUnit XML: ' + $id) }
        $xmlArchive = Join-Path $archive 'xml'
        New-Item -ItemType Directory -Path $xmlArchive | Out-Null
        foreach ($file in $xmlFiles) {
            $copy = Join-Path $xmlArchive $file.Name
            Copy-Item -LiteralPath $file.FullName -Destination $copy
            $xmlHashes.Add([pscustomobject]@{file=$file.Name;sha256=(Get-FileHash -LiteralPath $copy -Algorithm SHA256).Hash.ToLowerInvariant()})
            if ($file.LastWriteTimeUtc -lt ([DateTimeOffset]::Parse($done.started_at)).UtcDateTime.AddSeconds(-2)) {
                throw ('Stale JUnit XML: ' + $file.Name)
            }
            $xml = New-Object System.Xml.XmlDocument
            $xml.XmlResolver = $null
            $xml.Load($copy)
            $suite = $xml.SelectSingleNode('/testsuite')
            if ($null -eq $suite -or [string]$suite.name -notlike $Filter) { throw ('Unexpected/missing suite: ' + $file.Name) }
            $cases = @($suite.SelectNodes('testcase'))
            if ([int]$suite.tests -ne $cases.Count -or [int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0 -or [int]$suite.skipped -ne 0) {
                throw ('Nonpassing or inconsistent JUnit suite: ' + $suite.name)
            }
            foreach ($case in $cases) {
                if ($null -ne $case.SelectSingleNode('skipped|failure|error')) { throw ('Case did not pass: ' + $case.name) }
                $caseRows.Add([pscustomobject]@{class=[string]$suite.name;name=[string]$case.name})
            }
        }
        $xmlHashes | ConvertTo-Json -Depth 4 | Out-File -LiteralPath (Join-Path $archive 'xml-hashes.json') -Encoding utf8
        if ($caseRows.Count -ne $Expected) { throw ('Unexpected test count for ' + $Filter + ': ' + $caseRows.Count + '; expected ' + $Expected) }
        if ($Source) {
            $sourceText = [IO.File]::ReadAllText((Join-Path $root $Source))
            $pattern = '@Test(?:\([^\r\n]*\))?\s*(?:@[\w.]+(?:\([^\r\n]*\))?\s*)*fun\s+(?:`([^`]+)`|(\w+))\s*\('
            $expectedNames = @([regex]::Matches($sourceText,$pattern) | ForEach-Object {
                if ($_.Groups[1].Success) { $_.Groups[1].Value } else { $_.Groups[2].Value }
            })
            $actualNames = @($caseRows | ForEach-Object { $_.name })
            if ($expectedNames.Count -ne $Expected -or @(Compare-Object -ReferenceObject $expectedNames -DifferenceObject $actualNames -CaseSensitive).Count) {
                throw ('Authored test methods do not match executed XML: ' + $Filter)
            }
        }
        $caseRows | ConvertTo-Json -Depth 4 | Out-File -LiteralPath (Join-Path $archive 'executed-cases.json') -Encoding utf8
    }
    $records.Add([pscustomobject]@{run_id=$id;profile=$Profile;filter=$Filter;status=$done.status;tests=$caseRows.Count;fingerprint=$expectedFingerprint})
    $records | ConvertTo-Json -Depth 4 | Out-File -LiteralPath (Join-Path $evidence 'sweep-results.json') -Encoding utf8
    if ((Get-ReviewFingerprint) -ne $expectedFingerprint) { throw 'Tree changed during evidence capture' }
}

Invoke-SerialRun -Profile 'compile'
$testRoot = 'app/src/test/java/com/yourname/expensetracker/'
$filters = @(
    # S1 (unchanged from the 2026-09-28 packet)
    @{filter='*ReceiptPartialOcrPipelineTest';expected=22;source=$testRoot+'domain/receipt/lifecycle/ReceiptPartialOcrPipelineTest.kt'},
    @{filter='*OcrResultPartialTest';expected=4},
    @{filter='*ReceiptLifecycleCoordinatorTest';expected=35},
    @{filter='*ReceiptRepositoryBatchDuplicateTest';expected=5},
    @{filter='*ReceiptScanViewModelTest';expected=22;source=$testRoot+'ui/screens/receiptscan/ReceiptScanViewModelTest.kt'},
    @{filter='*ReceiptScanViewModelStressTest';expected=20;source=$testRoot+'ui/screens/receiptscan/ReceiptScanViewModelStressTest.kt'},
    @{filter='*ReviewViewModelBatchDuplicateMessageTest';expected=7},
    @{filter='*ReceiptSideEffectPlanner*';expected=13},
    @{filter='*BankStatementCompletionStatusTest';expected=5},
    @{filter='*ReceiptOcrRetryIsolationTest';expected=8},
    @{filter='*ReceiptOcrCoverageMetadataTest';expected=17;source=$testRoot+'domain/receipt/ReceiptOcrCoverageMetadataTest.kt'},
    @{filter='*ReceiptEventDaoTest';expected=14;source=$testRoot+'data/database/dao/ReceiptEventDaoTest.kt'},
    @{filter='*LegacyDataConsistencyCheckerTest';expected=5;source=$testRoot+'domain/consistency/LegacyDataConsistencyCheckerTest.kt'},
    # S4 (CL-09 missing evidence + WorkerRunLoggerTest)
    @{filter='*WorkerRunLoggerTest';expected=60;source=$testRoot+'domain/workers/WorkerRunLoggerTest.kt'},
    @{filter='*ExpenseRepositoryMerchantKeyBackfillTest';expected=8;source=$testRoot+'data/repository/ExpenseRepositoryMerchantKeyBackfillTest.kt'},
    @{filter='*MerchantKeyBackfillWorkerTest';expected=7;source=$testRoot+'data/location/MerchantKeyBackfillWorkerTest.kt'},
    @{filter='*WarrantyReminderDeliveryDaoTest';expected=13;source=$testRoot+'data/database/dao/WarrantyReminderDeliveryDaoTest.kt'},
    @{filter='*WarrantyExpirationWorkerTest';expected=15;source=$testRoot+'service/warranty/WarrantyExpirationWorkerTest.kt'},
    @{filter='*ReceiptMatchingViewModelTest';expected=4;source=$testRoot+'ui/screens/receiptmatching/ReceiptMatchingViewModelTest.kt'},
    @{filter='*ReceiptLinkServiceColumnScopeTest';expected=4;source=$testRoot+'domain/receipt/lifecycle/ReceiptLinkServiceColumnScopeTest.kt'}
)
foreach ($item in $filters) {
    Invoke-SerialRun -Profile 'targeted-unit-test' -Filter $item.filter -Expected $item.expected -Source ([string]$item.source)
}
Assert-Quiescent
if (($records | Measure-Object -Property tests -Sum).Sum -ne 288) { throw 'Final test-count mismatch (expected 177 S1 + 111 S4)' }
Write-Host ('Completed compile + 20 filters (288 cases); evidence: ' + $evidence)
```

If a count or name check fails on an S4 filter (such as a parameterized or nested test the regex cannot see), stop and report it. Do not edit the expected value on your own; the count has to be adjudicated first.

## 5. After a full PASS

1. Refresh the test-result freshness stamp the same way you did for the 12:05 and 14:10 stamps. It must be the last write to the tree.
2. `scripts/validation-runner.ps1 -Action Start -Profile static-guards`, then poll that run ID. Expected result: 24/25, with only `raw_money_aggregates` failing (84 inherited violations; see JOURNAL line 125).

## 6. Not covered by this packet

Independent strict review of this session's deltas and the policy no-broadening diff review; device gates; the commit decision. This packet is validation evidence only. It is not a Wave-2 closure claim.
