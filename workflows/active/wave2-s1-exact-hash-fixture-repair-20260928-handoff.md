# Wave-2 S1 exact-hash fixture repair — validation handoff

Date: 2026-09-28. Disposition: **IMPLEMENTED_UNVERIFIED**. Wave-2 remains **NOT CLOSED**.

This is a five-line test-fixture correction following the independently reviewed S1 repair. One agent, no subagents; no builds, tests, guards or scanner execution. It supersedes the single test-file hash and snapshot/validation packet in `workflows/active/wave2-s1-review-repair-20260928-handoff.md`. That original handoff and all existing campaign records remain unchanged.

## 1. Diagnosis verified from source and preserved evidence

- Compile: `vr-20260928-174815-44730b74`, terminal PASS, exit 0, PASS completion marker.
- First targeted filter: `vr-20260928-175602-ed45d9ba`, terminal FAIL, exit 1, FAIL completion marker; 22 cases, 21 passed, 1 failed, 0 skipped.
- Both runs have matching start/end fingerprint `78b0a819def5485c18331a3dee9bc779873118d1785ad158572a7f1ff2b75220`.
- Failed case: `postOcrExactHashDuplicateUsesStoredCoverageRatherThanCurrentAttempt`; `assertFalse(duplicate.inserted)` in `assertDuplicateState:232`, called from the old test line 467.
- Preserved XML: `build/validation-runs/s1-review-repair-20260928-204812-66b74eba/vr-20260928-175602-ed45d9ba-xml/TEST-com.yourname.expensetracker.domain.receipt.lifecycle.ReceiptPartialOcrPipelineTest.xml`; SHA-256 `cc8cd2670a6b567968f6aeb4410e9956c800d157557535907f9bc73e19e7c010`.

The validator diagnosis is supported by the actual source. The setup stub at `ReceiptPartialOcrPipelineTest.kt:135–139` returns `isDuplicate=false` / `matchType=NONE`. The failing case changed the second URI hash to bypass the pre-OCR duplicate route but never changed that detector result. The production exact-hash branch at `ReceiptLifecycleCoordinator.kt:413–420` requires a positive `EXACT_HASH` result before it can return the existing receipt through `duplicateOutcome` at line 464. The test therefore did not arrange the branch it claimed to exercise. The sibling text-fingerprint test already supplies its own positive detector result.

This is a fixture-authoring defect, not evidence that the production branch must be changed. Passing siblings remain evidence for their own arranged paths, not proof that every duplicate variant or the full Wave-2 scope is correct. The exact-hash case still needs fresh execution after this correction.

## 2. Minimal correction and preserved assertions

Only application/test path changed:

- `app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptPartialOcrPipelineTest.kt`

After the first real save, the exact-hash test now configures `duplicates.checkDuplicate(...)` to return `isDuplicate=true`, `confidence=1f`, the first saved receipt ID, and `reason/matchType=EXACT_HASH`. This is five added lines at 465–469, with zero removed lines. It enables the required branch only after the first save. No production code, assertion, test name, test filter, skip annotation, policy, baseline, allowlist or RawQuery pin changed.

The existing assertions remain intact: the second attempt is not inserted, identity and PARSE_FAILED status are preserved, stored coverage is `(2,3,1)` despite a complete second OCR attempt, only one receipt/save event exists, duplicate postcommit work is absent, OCR ran twice, the insert resolver ran only once, and the referenced existing asset was not deleted. There are still 22 methods in this class and 177 expected cases across the complete 13-filter packet.

Before SHA-256: `faaf48c81266ec1d7d17fe3f49e726d1ea2b49080526f7aadd967ae19100df8f`.

After SHA-256: `6bf7e886bbb347f8d3abce32465a524523f62617abebc04ac060f59e128d9e78`.

Author read-back matched the exact intended five-line insertion byte-for-byte. The method inventory and all pre-existing assertions are unchanged. This is source verification only, not a test PASS or independent approval of the delta.

## 3. Snapshot and preservation

- Branch `bug-fixes`; HEAD `a48076322e57f3d312f34cf6a71139efc4fcc48b`; staged paths: 0.
- Pre-addendum snapshot captured `2026-09-28T21:17:45.3419162+03:00`: 135 dirty tracked + 87 untracked = 222 paths.
- Runner-compatible pre-addendum fingerprint: `8d511011cf2d6ecf6b08e0b11f16f74784cc7d81476980319fd87832d1de5e19`.
- The post-addendum full fingerprint is published separately after this file is written and read back; this document cannot embed its own final hash.
- All 221 other prior dirty/untracked paths are byte-identical to this repair intake. This includes all production files, guard/policy files, prior handoffs and the validator’s journal update.
- The only change between the earlier published S1 snapshot and this repair intake was the validator’s journal entry. Its current SHA-256 `fc795e72b09d6630a2993a700109b76e40053684db2bcd47e03397cc529f41a9` is preserved, not reverted or overwritten.
- The original S1 repair handoff remains SHA-256 `4dfe28accd97a3a48c07ae2c9c5618bb90e0333aa9e54c981bab220f9e99f064`. All other rows in its 13-path manifest remain unchanged; only the test hash above is superseded.
- No staging, commit, history manipulation, generated-output edit or S2 work occurred.

## 4. Next action for the independent reviewer/validator

Inspect this five-line fixture delta against the failure and unchanged assertions. The reported independent PASS on the prior S1 tree is historical evidence for that snapshot; the author cannot self-approve this new delta. If the delta review passes, restart **compile + all 13 filters** on the new frozen tree, using the command below. The prior compile PASS and 21 passing cases do not replace validation on the corrected fingerprint. The other 12 filters, including the reactivated stress suite, were not attempted in the halted sweep.

Use this packet, not the original section-6 block, because the original hard-coded fingerprint is now intentionally stale. The new block preserves the same runner profiles, order, assertions, actual-execution checks, 177-case expectation, zero-skip requirement, per-run XML capture and stop-on-failure behavior. Only the handoff exclusion path, expected pre-addendum fingerprint and unique evidence-directory prefix differ. Windows PowerShell 5.1, direct `scripts/validation-runner.ps1`, no `-Raw`, no overlap/dedup override, no direct Gradle/pytest execution.

Keep the tree frozen, including documentation. If a run is still RUNNING, poll its existing ID; do not start it again. On a failure, retain the run and XML and stop. Do not relax a count/name assertion, drop a filter or skip a case. After a successful same-tree run, do not blindly restart the whole block and bypass duplicate protection. Any subsequent source change requires its own associated snapshot and reviewed packet.

```powershell
$ErrorActionPreference = 'Stop'
$root = 'C:\Users\panos\Desktop\cost agregator\ExpenseTracker'
Set-Location -LiteralPath $root
$runner = Join-Path $root 'scripts/validation-runner.ps1'
$handoff = 'workflows/active/wave2-s1-exact-hash-fixture-repair-20260928-handoff.md'
$expectedHead = 'a48076322e57f3d312f34cf6a71139efc4fcc48b'
$expectedWithoutHandoff = '8d511011cf2d6ecf6b08e0b11f16f74784cc7d81476980319fd87832d1de5e19'

function Get-ReviewFingerprint([switch]$OmitHandoff) {
    $tracked = @(& git -c core.quotepath=false -c core.safecrlf=false diff --name-only HEAD --)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read tracked paths' }
    $untracked = @(& git -c core.quotepath=false ls-files --others --exclude-standard)
    if ($LASTEXITCODE -ne 0) { throw 'Cannot read untracked paths' }
    $paths = @($tracked + $untracked | Sort-Object -Unique)
    if ($OmitHandoff) { $paths = @($paths | Where-Object { $_ -ne $handoff }) }
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
if ($LASTEXITCODE -ne 0 -or $staged.Count) { throw 'Index differs from reviewed snapshot' }
if ((Get-ReviewFingerprint -OmitHandoff) -ne $expectedWithoutHandoff) {
    throw 'Reviewed tree has changed; reconcile the snapshot before validation'
}
$expectedFingerprint = Get-ReviewFingerprint
Write-Host ('Frozen full-tree fingerprint: ' + $expectedFingerprint)
# Compare this full fingerprint with the post-handoff value published by the coder.
$evidenceRelative = 'build/validation-runs/s1-exact-hash-fixture-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
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
    @{filter='*LegacyDataConsistencyCheckerTest';expected=5;source=$testRoot+'domain/consistency/LegacyDataConsistencyCheckerTest.kt'}
)
foreach ($item in $filters) {
    Invoke-SerialRun -Profile 'targeted-unit-test' -Filter $item.filter -Expected $item.expected -Source ([string]$item.source)
}
Assert-Quiescent
if (($records | Measure-Object -Property tests -Sum).Sum -ne 177) { throw 'Final test-count mismatch' }
Write-Host ('Completed compile + 13 filters; inspect preserved evidence: ' + $evidence)
# This is S1 repair validation only, not Wave-2 closure or independent approval.
```

## 5. Remaining gates

This correction does not close S1 or Wave-2 by itself. Await the delta review and fresh execution evidence. S2 asset-recovery gaps, S3 ownership/advisory adjudication and static guards, remaining CL-09/WorkerRunLogger evidence, accumulated independent/guardian approvals, device gates and the human commit decision remain as recorded in the parent handoff section 7. No additional guard profile is authorized or silently launched by this packet.

Acceptance references: `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` FG-03, FG-06, FG-07 and FG-23. A source repair or unchanged historical PASS must not substitute for missing execution evidence.

Files touched: the one test path above and this new handoff. Validation: **NOT RUN by the implementation agent**. Existing failed evidence is preserved. No Wave-2 closure claim.

