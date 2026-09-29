# Wave-2 S1 review repair — implementation handoff

Date: 2026-09-28. Batch disposition: **IMPLEMENTED_UNVERIFIED**. Wave-2 closure verdict: **FAIL / gates still open**.

One implementation agent, no subagents. This handoff records repairs to S1-REV-01 and S1-REV-02 from `workflows/active/wave2-recovery-s1-strict-technical-review-20260928.md`, plus the additional S1-REV-03 state-isolation defect found while repairing the stress harness. Author-side source inspection is complete; it is neither independent strict review nor guardian approval. **No build, test, guard, scanner or validation profile was executed for this repair.**

## 1. Scope and snapshot

- Branch: `bug-fixes`; HEAD: `a48076322e57f3d312f34cf6a71139efc4fcc48b`; staged paths: 0.
- Pre-handoff snapshot captured: `2026-09-28T20:28:33.6174158+03:00`.
- Dirty tracked paths: 135; untracked paths: 86; total: 221. These are the accumulated tree, not the size of this repair.
- Exact runner-compatible pre-handoff fingerprint: `5d68bf984a78230282cd9279715294f1ee53c06c797f6b31c39c481d078a0739`.
- Repair scope: 12 app paths (6 production/resource, 6 test) plus the new plan and this new handoff. The new handoff itself is not included in the fingerprint above; adding it changes the full-tree fingerprint. The post-handoff fingerprint will be published separately after read-back to avoid a self-referential hash.
- 208 prior dirty/untracked paths outside these 12 app paths remain byte-identical to repair intake. All 15 original recovery-batch paths remain byte-identical. The separately repaired legacy fake changes only to implement the new read interface; its earlier image-pointer CAS no-op remains `= 0`.
- Original strict report remains byte-identical, SHA-256 `ed1bdc58e3041611949ef8722bbb85b54c6d133ab78c1f7ac2f7c5b9a167b43c`. No historical report, handoff, campaign record, policy, baseline, allowlist, RawQuery pin or generated artifact was edited.
- No Room entity/schema/version/migration change. No retention-policy change. No staging, commit or git-history operation.
- The new plan is retained as an intake record; this handoff supplies its current disposition and supersedes its proposed wrapper choice with direct `scripts/validation-runner.ps1` calls.

## 2. Findings repaired and acceptance obligations

### S1-REV-01 — parse-failed duplicates lost independent partial OCR coverage

**Implementation:** `ReceiptOcrCoverage.kt:19–75` persists a controlled `pageCoverageVersion = 1` envelope, including explicit complete/image coverage, and decodes bounded saved evidence. `ReceiptEventDao.kt:17–23` reads only the latest matching `RECEIPT_SAVED` metadata, ordered by timestamp and ID, using `substr(..., 1, 2049)`; the decoder rejects over 2048 characters. It does not materialize the full receipt-event history.

`ReceiptLifecycleCoordinator.kt:265–284` reconstructs coverage for `PARSE_FAILED` duplicates. All five duplicate paths use the helper (`:379`, `:464`, `:555`, `:578`, `:732`). New saves write the envelope at `:640`. The stored parse-failure status, existing receipt identity, no-new-save-event and no-duplicate-postcommit contracts remain intact. Metadata-read cancellation is rethrown; absent, retained, malformed or unreadable evidence is explicitly unverified rather than assumed complete or assigned invented counts.

`ReceiptScanViewModel.kt:127,279,327,448` carries and resets the unverified flag. The shared warning in `ReceiptScanScreen.kt:486–516` is rendered in both duplicate (`:258`) and review (`:1170`) states. Controlled uncertainty wording is at `values/strings.xml:394`. Known partiality takes precedence over the uncertainty warning.

The final version-marker spelling matters: the existing metadata sanitizer deliberately blocks keys containing `ocr`. Author inspection caught that an `ocrCoverageVersion` key would be redacted. The producer and decoder now use `pageCoverageVersion`; the sanitizer and its restrictions were not modified. A new regression asserts the envelope survives sanitization while raw OCR keys remain redacted. The real event writer is still `domain/receipt/lifecycle/ReceiptLifecycleEventWriter.kt`; no new persistence path or writer was introduced.

**Assertions authored:** complete/image/partial/capped round trips; legacy positive partial evidence; attempted-page semantics; malformed/unsupported/contradictory/oversized data; DAO filtering/ties/null latest/retention; real-Room save and duplicate flows; explicit metadata-read failure/cancellation; alternate post-OCR duplicate branches; UI review/duplicate warnings and reset. Disposition: **IMPLEMENTED_UNVERIFIED**.

### S1-REV-02 — the stress suite was skipped and its fixtures no longer modeled production

All 19 original stress cases are retained and reactivated by removing the class-level ignore. One case was renamed from the obsolete normal-repository-save wording to the lifecycle-save wording. The fixtures now use the explicit shared test dispatcher, a fixed clock, a live category subscription and deterministic category/currency inputs, named lifecycle/link/parser collaborators, and owned-scope cleanup. They no longer assert the deprecated repository mutation path.

AI draft application asserts pending capability state and zero persisted application marks before save. The successful-save case suspends the actual lifecycle result, checks no premature application, checks the requested save contract, and only then allows success and checks the application marks. Fallback cases assert the current bounded failure contract instead of stale raw/debug outputs. No test was deleted, substituted with a skip, or removed from the validation selection. Disposition: **IMPLEMENTED_UNVERIFIED**.

### S1-REV-03 — pending AI application could carry across receipt switches

While repairing those fixtures, the real ViewModel path showed that pending AI capabilities could survive switching receipts, then be captured in a save request using the next receipt ID. `ReceiptScanViewModel.kt:280` now clears that pending set at scan start. The extended fallback test and new `switchingReceiptsDoesNotMarkUnappliedArtifactsOnTheNextReceipt` case exercise public scan/save methods and require that an unrelated unapplied artifact on the next receipt is not marked. This is an actual production-state isolation repair, not a fixture-only workaround. Disposition: **IMPLEMENTED_UNVERIFIED**.

## 3. Author-side source checks and boundaries

- Followed scan UI -> ViewModel -> lifecycle coordinator -> repository/Room/event writer and all duplicate-return branches; inspected `ReceiptEventDao` Hilt provisioning and the only direct fake implementer.
- Checked the other production outcome consumer, `ReceiptRepository.kt:621–688`: duplicate counts remain separate from newly saved partial receipts (`partialCount` excludes duplicates). No new batch double counting was introduced.
- Confirmed the bounded metadata transport contains only controlled coverage/version/reason fields; no raw OCR, file paths or financial values were added to it. The existing metadata sanitizer remains unchanged.
- Confirmed the DAO addition is read-only and leaves the existing strict retention cutoff unchanged; fake interface growth does not claim a successful mutation.
- Compared all retained stress method names, inspected changed assertions, and read back every affected source. This is author inspection, not an independent approval or execution result.
- The injected insert-resolver duplicate test verifies handling of that typed outcome after transaction exit; **it is not proof of a truly concurrent insertion race**. Ordinary pipeline assertions use the real repository, Room DAOs/transaction and event writer, with explicit fault injection only where named.
- Unit tests do not prove Android PDF rendering or device/process-crash behavior. No claim is made that unrelated existing diagnostics/privacy debt across the repository has been repaired by this bounded batch.

## 4. Test inventory — authored, not executed

| Class | Current methods | New in this repair | Other change |
|---|---:|---:|---|
| ReceiptOcrCoverageMetadataTest | 17 | 17 | New bounded codec/sanitizer module |
| ReceiptEventDaoTest | 14 | 6 | Original 8 retained |
| ReceiptPartialOcrPipelineTest | 22 | 12 | Original 10 retained |
| ReceiptScanViewModelTest | 22 | 5 | Original 17 retained |
| ReceiptScanViewModelStressTest | 20 | 1 | Original 19 retained/reactivated |
| LegacyDataConsistencyCheckerTest | 5 | 0 | Direct read-interface fake updated |

Total: **41 new test methods, plus 19 existing stress methods reactivated**. None has an execution result for this repaired tree. The planned 13 filters include the original ten S1 filters and three additional codec/DAO/fake filters. Expected total is **177 cases**, with **zero skipped** required; this is a source/evidence-derived expectation, not a PASS claim.

### Correction to historical S1 evidence

The preserved S1 XML on fingerprint `6119a5cf599a61b5e392c499bcd442035a340aaa47a4893ceead95e16e0c0fdb` lists **123 cases: 104 passed, 19 skipped, 0 failures, 0 errors**. The previous statement of 123 executed and zero skipped was not supported. All 19 skipped cases were the ignored stress class. Historical runner PASS records and the 18 originally added S1 methods remain historical evidence, but they neither execute the skipped cases nor validate this repaired fingerprint. The prior recovery fingerprint `d736eca4da0676c108cc987c921fa57d16ad3609accd9e09a93243c440e067d5` is likewise not this tree.

## 5. Exact file manifest

Hashes below describe the 12 app paths and new plan before this handoff was added. The handoff is the additional new artifact and deliberately cannot contain its own final hash.

| Path | SHA-256 | Bytes |
|---|---|---:|
| `app/src/main/java/com/yourname/expensetracker/domain/receipt/ReceiptOcrCoverage.kt` | `0b9aa13b004581293c52cae8992d3c48c3c8225d5282b882602200fa39d4cb96` | 3595 |
| `app/src/main/java/com/yourname/expensetracker/data/database/dao/ReceiptEventDao.kt` | `31990d1031a391def813277ee9e6e9fa2ebb7f33a5c1eaf8e02ceb2617377586` | 1224 |
| `app/src/test/java/com/yourname/expensetracker/domain/consistency/LegacyDataConsistencyCheckerTest.kt` | `bbcad8d9968be53e2ea71eba2386030d375cb700c6dba59cb4e4f46ffc54ceb4` | 24903 |
| `app/src/main/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptLifecycleCoordinator.kt` | `d076398395a7c18ee9db8e53dc3863d85c987a141b4cdd6449f3f3bf713c3f29` | 93003 |
| `app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModel.kt` | `2e49cda5b6a4e1b13d180b4d5862cd575d62ce0516593420be7d678575a5d5b7` | 69687 |
| `app/src/main/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanScreen.kt` | `060c5771305360f71c82f73f29d1f55a0968dec9a62e96661f14349b10f1e28a` | 65348 |
| `app/src/main/res/values/strings.xml` | `74f079fe037db5320dce3412b96e614ebf10e8eb236e37ce013a53617f812b3e` | 177209 |
| `app/src/test/java/com/yourname/expensetracker/domain/receipt/ReceiptOcrCoverageMetadataTest.kt` | `564694adc12fa2bf7e4e5ae3d5687d4297d68460baae5d7d6e091926417f249b` | 8539 |
| `app/src/test/java/com/yourname/expensetracker/data/database/dao/ReceiptEventDaoTest.kt` | `47ae4445a6a8342ca1b720f6afbbd96461aa617b258fccb067bf76c31d7d89a8` | 9998 |
| `app/src/test/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModelTest.kt` | `e57e13413205c51752dcb6cd3599161faf4af9c0b832e08f2a55b3ed774166bc` | 23765 |
| `app/src/test/java/com/yourname/expensetracker/domain/receipt/lifecycle/ReceiptPartialOcrPipelineTest.kt` | `faaf48c81266ec1d7d17fe3f49e726d1ea2b49080526f7aadd967ae19100df8f` | 26599 |
| `app/src/test/java/com/yourname/expensetracker/ui/screens/receiptscan/ReceiptScanViewModelStressTest.kt` | `71d3514a706200f0792ce3e9f6e6517f06e20d4cc0787954a3374a97e8c48217` | 45232 |
| `workflows/active/wave2-s1-review-repair-20260928-plan.md` | `789acfbc961dd9a52bad40137f622e6ad6c7378516232fe93e0bf59f946d7632` | 4282 |

## 6. Human validation packet — DO NOT run concurrently

Execution status: **NOT RUN**. Obtain the required independent review disposition first; author inspection cannot approve itself. Keep the complete tree, including workflow documents, frozen during the sequence. Use Windows PowerShell 5.1 (`powershell.exe`), direct runner calls, no `-Raw`, no overlap/dedup override, and no direct Gradle/pytest/guard invocation. The command below preserves each run’s XML before the next targeted run replaces Gradle outputs and requires matching method names for the six touched test classes.

The command checks the pre-handoff fingerprint while excluding only this new handoff, then freezes the full fingerprint including it for every run. Compare that full value with the post-handoff fingerprint published separately. All runs must have terminal PASS, completion marker, unchanged start/end fingerprint and correct HEAD. For targeted runs, the actual test task must execute; compile-only, cached/skipped task evidence or skipped JUnit cases does not satisfy this packet. A count/name mismatch is a stop for adjudication, not permission to relax the expectation.

On any failure, retain its run ID and artifacts and stop. If a run remains RUNNING, poll that same ID; do not relaunch it. Do not restart the whole script over previously successful same-tree runs or bypass the runner’s duplicate protection. After diagnosis, resume only the unexecuted portion against explicitly associated evidence; a changed tree needs a new reviewed snapshot and packet. No static-guards profile is silently added here; that is the separately gated S3 sequence.

```powershell
$ErrorActionPreference = 'Stop'
$root = 'C:\Users\panos\Desktop\cost agregator\ExpenseTracker'
Set-Location -LiteralPath $root
$runner = Join-Path $root 'scripts/validation-runner.ps1'
$handoff = 'workflows/active/wave2-s1-review-repair-20260928-handoff.md'
$expectedHead = 'a48076322e57f3d312f34cf6a71139efc4fcc48b'
$expectedWithoutHandoff = '5d68bf984a78230282cd9279715294f1ee53c06c797f6b31c39c481d078a0739'

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
$evidenceRelative = 'build/validation-runs/s1-review-repair-' + (Get-Date -Format 'yyyyMMdd-HHmmss') + '-' + [Guid]::NewGuid().ToString('N').Substring(0,8)
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

## 7. What still prevents Wave-2 closure

1. This repair’s independent strict/guardian disposition and fresh compile + 13-filter evidence. The implementation author cannot self-approve. A successful targeted sweep alone is not full Wave-2 acceptance.
2. S2 asset recovery: the six source-confirmed gaps in `workflows/active/wave2-s2-asset-recovery-readiness-20260928.md` remain separate work (checksum/size replay, crash-after-publish collision, fsync failures, ledger rebuild, journal atomicity and filename exposure). RP-03’s full file-integrity/crash contract remains PARTIAL. Do not start S2 by treating unreviewed/unvalidated S1 as complete.
3. S3: the recovery mutation ownership-policy reconciliation, advisory-count 20-vs-21 adjudication with defensible evidence, and then the complete static-guards profile. No ownership-policy, baseline, allowlist or RawQuery-pin edit occurred in this repair. The source map must reflect actual owners; checks must not be weakened merely to turn green.
4. S4: the six previously identified missing CL-09 filters and WorkerRunLoggerTest diagnosis/evidence remain as recorded in the strict report; this S1 packet is not a substitute.
5. S5: independent strict and relevant guardian approvals for the accumulated Wave-2 scope, PDF/image and merchant-backfill device gates, reconciliation of all remaining original-finding/evidence gaps, and an explicit human commit decision. Nothing has been staged or committed here.

Acceptance references: `FINAL_CI_GUARD_ACCEPTANCE_GATE.md` FG-03, FG-06, FG-07 and FG-23. Missing, skipped or unknown evidence remains open; historical waivers or author checks are not transferred to unrelated gates.

## Completion summary

Files touched: the 12 app paths in section 5, the new repair plan, and this new handoff. What changed: duplicate coverage reconstruction with honest uncertainty, stress-suite fixture/skip repair, and cross-receipt pending-AI isolation. Validation: NOT RUN; exact human sequence above. Risks/follow-up: section 7. **No Wave-2 closure claim.**

