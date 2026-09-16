→Read build\worktrees\gr-14f\docs\ci\db-mediation\GR-14u55.yml [offset=61]
# Running in build\worktrees\gr-14f
$ Select-String -Path scripts\ci\test_capture_db_guard_evidence.py,scripts\ci\*.py -Pattern "sha16|sha_16|\[:16\]" -List | Select-Object Filename, LineNumber -First 10
(no output)
# Running in build\worktrees\gr-14f
$ Select-String -Path scripts\ci\*.py -Pattern "\[:16\]" | Select-Object Filename, LineNumber, Line -First 10
(no output)
# Running in build\worktrees\gr-14f
$ Select-String -Path docs\ci\db-mediation\*.yml -Pattern "sha16|6bb3b894a9cc7181|ec7fd24b1c9c45ea" | Select-Object Filename, LineNumber -First 12
Filename      LineNumber
--------      ----------
GR-14u55.yml          70
GR-14u55.yml         103
GR-14u55b.yml         73
GR-14u55b.yml        103
GR-14u56a.yml         73
GR-14u56a.yml        114
# Running in build\worktrees\gr-14f
$ git log --all --oneline | Select-String "6bb3b894" | Select-Object -First 2; git cat-file -t 6bb3b894a9cc7181 2>$null; git log --format="%H" -1 6bb3b894a9cc7181 2>$null
(no output)
# Running in build\worktrees\gr-14f
$ git rev-parse HEAD; git rev-parse HEAD~1; git branch --show-current
6c49a5a4418b6f55a5a415b3abb82884043aa01e
9e60142a1a97fe2e2ebec89cacc76a3e6dedf6f2
gr-14f-wip
# Running in build\worktrees\gr-14f
$ python -c "
import hashlib, json
raw = open('build/guard-debug/board-u55-verify2.json','rb').read()
print('full sha256:', hashlib.sha256(raw).hexdigest())
print('first16:', hashlib.sha256(raw).hexdigest()[:16])
"; Select-String -Path docs\ci\db-mediation\GR-14u54.yml,docs\ci\db-mediation\GR-14u53.yml,docs\ci\db-mediation\GR-14u52.yml -Pattern "COMPLETE_AT_BOARD|sha16" | Select-Object Filename, LineNumber, Line -First 12
full sha256: ec7fd24b1c9c45ea3375bd269b9f7591c5981738c839c59102576efb38ba5976
first16: ec7fd24b1c9c45ea

Filename     LineNumber Line                              
--------     ---------- ----                              
GR-14u54.yml         94 status: COMPLETE_AT_BOARD_f0dfd653
GR-14u53.yml        110 status: COMPLETE_AT_BOARD_dc8c91b8
GR-14u52.yml        134 status: COMPLETE_AT_BOARD_8b1b5d03
# Running in build\worktrees\gr-14f
$ python -c "
import hashlib
for p in ['build/guard-debug/gr14u33/board_post_run1.json','build/guard-debug/gr14u39/board_run1.json']:
    raw = open(p,'rb').read()
    print(p, hashlib.sha256(raw).hexdigest()[:16])
"
build/guard-debug/gr14u33/board_post_run1.json 99a87537cb75a63e
build/guard-debug/gr14u39/board_run1.json 85d102da0384e81a
# Running in build\worktrees\gr-14f
$ Select-String -Path docs\ci\db-mediation\GR-14u54.yml,docs\ci\db-mediation\GR-14u53.yml -Pattern "board" | Select-Object Filename, LineNumber, Line -First 14
Filename     LineNumber Line                                                                   
--------     ---------- ----                                                                   
GR-14u54.yml         51 # OUTCOME (board, pre dc8c91b8... -> post f0dfd6531a78111e, double run 
GR-14u54.yml         74 #   edges changed, 0 unexpected board delta (379 entries diffed - zero 
GR-14u54.yml         83 #   - board double run byte-identical f0dfd6531a78111e                 
GR-14u54.yml         94 status: COMPLETE_AT_BOARD_f0dfd653                                     
GR-14u53.yml         76 # OUTCOME (board, pre 8b1b5d03... -> post dc8c91b88b5561af, double run 
GR-14u53.yml         91 #   non-fabricated edges changed, 0 unexpected board delta (379 entries
GR-14u53.yml        101 #   - board double run byte-identical dc8c91b88b5561af                 
GR-14u53.yml        110 status: COMPLETE_AT_BO…
Click to expand
# Running in build\worktrees\gr-14f
$ python -c "
import hashlib
raw = open('build/guard-debug/board-u55-verify2.json','rb').read()
print(hashlib.sha256(raw).hexdigest())
"
ec7fd24b1c9c45ea3375bd269b9f7591c5981738c839c59102576efb38ba5976
→Read build\worktrees\gr-14f\scripts\db_guard\structural_analysis\tokenizer.py [limit=120, offset=285]
→Read build\worktrees\gr-14f\scripts\db_guard\structural_analysis\tokenizer.py [limit=45, offset=405]
→Read build\worktrees\gr-14f\scripts\db_guard\structural_analysis\tokenizer.py [limit=140, offset=60]
→Read build\worktrees\gr-14f\scripts\db_guard\structural_analysis\tokenizer.py [limit=115, offset=2170]
→Read build\worktrees\gr-14f\scripts\db_guard\structural_analysis\test_tokenizer.py [limit=120, offset=500]
# Running in build\worktrees\gr-14f
$ Select-String -Path scripts\db_guard\structural_analysis\test_tokenizer.py -Pattern "class Test|def parse\(|def kinds" | Select-Object LineNumber, Line
LineNumber Line                                                                              
---------- ----                                                                              
        30 def parse(body: str, transparent_scope_methods=(), transparent_inline_methods=()):
        41 def kinds(parse_result):                                                          
        45 class TestGraphShapeMatrix:                                                       
       272 class TestMalformedInput:                                                         
       294 class TestMaskedLookalikes:                                                       
       322 class TestConservativeUnsupported:                                                
       404 class TestBarrierObservation:                                                     
       454 class TestDeterminismAndSafety:                      …
Click to expand
→Read build\worktrees\gr-14f\scripts\db_guard\structural_analysis\test_tokenizer.py [limit=80, offset=860]
→Read build\worktrees\gr-14f\scripts\db_guard\structural_analysis\test_tokenizer.py [limit=225, offset=940]
→Read build\worktrees\gr-14f\scripts\db_guard\structural_analysis\test_tokenizer.py [limit=65, offset=1163]
→Read build\worktrees\gr-14f\scripts\db_guard\structural_analysis\test_tokenizer.py [limit=55, offset=1]
→Read build\worktrees\gr-14f\scripts\db_guard\structural_analysis\test_tokenizer.py [limit=80, offset=1265]
# Running in build\worktrees\gr-14f
$ $f = "app\src\main\java\com\yourname\expensetracker\domain\receipt\lifecycle\ReceiptLifecycleCoordinator.kt"; $lines = Get-Content $f; foreach ($n in 345..365) { Write-Output ("{0}: {1}" -f $n, $lines[$n-1].TrimEnd()) }
345:             if (fileHash != null) {
346:                 val dupResult = duplicateDetector.checkDuplicate(
347:                     imageHash = fileHash,
348:                     textFingerprint = null,
349:                     semanticFingerprint = null,
350:                     externalSourceId = null
351:                 )
352:                 if (dupResult.isDuplicate && dupResult.matchType == "EXACT_HASH") {
353:                     val existing = scannedReceiptDao.getById(dupResult.existingReceiptId!!)?.also {
354:                         it.taxInclusive = taxInclusive
…
Click to expand
# Running in build\worktrees\gr-14f
$ $f = "app\src\main\java\com\yourname\expensetracker\domain\receipt\lifecycle\ReceiptLifecycleCoordinator.kt"; $lines = Get-Content $f; foreach ($n in 440..455) { Write-Output ("{0}: {1}" -f $n, $lines[$n-1].TrimEnd()) }
440:                         semanticFingerprint = semanticFingerprint,
441:                         externalSourceId = null
442:                     )
443:                     if (postOcrDup.isDuplicate && postOcrDup.matchType != "EXACT_HASH") {
444:                         scannedReceiptDao.getById(postOcrDup.existingReceiptId!!)?.let { existing ->
445:                             val now = timeProvider.now()
446:                             val withFingerprints = ReceiptTimestampPolicy.forUpdate(receipt.copy(
447:                                 imagePath = receipt.imagePath,
448:                                 imageHash = fileHash ?: receipt.imageHash,
449:                                 sourceType = ReceiptSourceType.CAMERA.name,
…
Click to expand
# Running in build\worktrees\gr-14f
$ $f = "app\src\main\java\com\yourname\expensetracker\domain\receipt\lifecycle\ReceiptLifecycleCoordinator.kt"; $lines = Get-Content $f; foreach ($n in 455..505) { Write-Output ("{0}: {1}" -f $n, $lines[$n-1].TrimEnd()) }
455:                             scannedReceiptDao.update(withFingerprints)
456:                             receiptLifecycleEventWriter.write(context, toLifecycleEvent(ReceiptEvent(
457:                                 receiptId = withFingerprints.id,
458:                                 sourceType = withFingerprints.sourceType,
459:                                 documentType = withFingerprints.documentType,
460:                                 eventType = "DUPLICATE_DETECTED",
461:                                 occurredAt = now,
462:                                 oldStatus = receipt.processingStatus,
463:                                 newStatus = ReceiptProcessingStatus.DUPLICATE_DETECTED.name,
464:                                 actor = "system:coordinator",
…
Click to expand
# Running in build\worktrees\gr-14f
$ $f = "app\src\main\java\com\yourname\expensetracker\domain\receipt\lifecycle\ReceiptLifecycleCoordinator.kt"; $lines = Get-Content $f; foreach ($n in 265..280) { Write-Output ("{0}: {1}" -f $n, $lines[$n-1].TrimEnd()) }; Write-Output "=== 375-390 ==="; foreach ($n in 375..390) { Write-Output ("{0}: {1}" -f $n, $lines[$n-1].TrimEnd()) }
265:             // P3-BLOCKER-006: Use sanitized message �?" never embed raw URI
266:             val message = "Receipt input validation failed"
267:             Timber.w("$message: %s", validation.errors.firstOrNull() ?: "unknown reason")
268:             // P3-NEW-08 / P3-BLOCKER-04: Write VALIDATION_FAILED diagnostic
269:             // Use reason codes extracted from validation errors �?" never raw URI
270:             val reasonCode = when {
271:                 validation.errors.any { it.contains("not readable") } -> "URI_NOT_READABLE"
272:                 validation.errors.any { it.contains("MIME type") || it.contains("determine MIME") } -> "MIME_UNKNOWN"
273:                 validation.errors.any { it.contains("Unsupported MIME") } -> "MIME_UNSUPPORTED"
274:                 validation.errors.any { it.contains("too large") || it.contains("exceeds") } -> "FILE_TOO_LARGE"
…
Click to expand
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
s = '''receipt.imagePath?.let { path ->
    try {
        assetStore.computeFileHash(path).getOrNull()
    } catch (e: Exception) {
        CancellationSafe.rethrowIfCancellation(e)
        null
    }
}
'''
m = mask_kotlin_source(s)
print(repr(m))
"
'receipt.imagePath?.let { path ->\n    try {\n        assetStore.computeFileHash(path).getOrNull()\n    } catch (e: Exception) {\n        CancellationSafe.rethrowIfCancellation(e)\n        null\n    }\n}\n'
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
s = '''validation.errors.any { it.contains(\"MIME type\") || it.contains(\"determine MIME\") } -> \"MIME_UNKNOWN\"
'''
m = mask_kotlin_source(s)
print(repr(m))
"
File "<string>", line 5
    s = '''validation.errors.any { it.contains(" MIME type\)
        ^
SyntaxError: unterminated triple-quoted string literal (detected at line 5)
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
s = 'validation.errors.any { it.contains(\"MIME type\") } -> \"MIME_UNKNOWN\"'
m = mask_kotlin_source(s)
print(repr(m))
"
File "<string>", line 5
    s = 'validation.errors.any { it.contains(" MIME type\)
        ^
SyntaxError: unterminated string literal (detected at line 5)
←Write ~\AppData\Local\Temp\opencode\probe_mask.py
# Running in build\worktrees\gr-14f
$ python - <<'EOF'
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source

samples = [
    'validation.errors.any { it.contains("MIME type") || it.contains("determine MIME") } -> "MIME_UNKNOWN"',
    'val fileHash = receipt.imagePath?.let { path ->\n    try {\n        assetStore.computeFileHash(path).getOrNull()\n    } catch (e: Exception) {\n        null\n    }\n}\n',
    'receipt.imagePath?.takeIf { it.isNotBlank() }\n    ?.let { assetStore.deleteAsset(it) }\n',
    'val existing = scannedReceiptDao.getById(dupResult.existingReceiptId!!)?.also {\n    it.taxInclusive = taxInclusive\n}\n',
]
for s in samples:
    m = mask_kotlin_source(s)
    assert len(m) == len(s)
    print(repr(m))
    print('---')
EOF
At line:1 char:11
+ python - <<'EOF'
+           ~
Missing file specification after redirection operator.
At line:1 char:10
+ python - <<'EOF'
+          ~
The '<' operator is reserved for future use.
At line:1 char:11
+ python - <<'EOF'
…
Click to expand
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
samples = [
    'validation.errors.any { it.contains(' + chr(34) + 'MIME type' + chr(34) + ') } -> ' + chr(34) + 'MIME_UNKNOWN' + chr(34),
    'val existing = scannedReceiptDao.getById(dupResult.existingReceiptId!!)?.also {' + chr(10) + '    it.taxInclusive = taxInclusive' + chr(10) + '}' + chr(10),
    'receipt.imagePath?.takeIf { it.isNotBlank() }' + chr(10) + '    ?.let { assetStore.deleteAsset(it) }' + chr(10),
]
for s in samples:
    m = mask_kotlin_source(s)
    assert len(m) == len(s)
    print(repr(m))
    print('---')
"
'validation.errors.any { it.contains(           ) } ->               '
---
'val existing = scannedReceiptDao.getById(dupResult.existingReceiptId!!)?.also {\n    it.taxInclusive = taxInclusive\n}\n'
---
'receipt.imagePath?.takeIf { it.isNotBlank() }\n    ?.let { assetStore.deleteAsset(it) }\n'
---
→Read build\worktrees\gr-14f\scripts\db_guard\structural_analysis\tokenizer.py [limit=50, offset=226]
→Read build\worktrees\gr-14f\scripts\db_guard\structural_analysis\tokenizer.py [limit=30, offset=1]
→Read build\worktrees\gr-14f\scripts\db_guard\structural_analysis\shadow_report.py 
# Running in build\worktrees\gr-14f
$ Select-String -Path scripts\ci\*.py -Pattern "processReceiptInput" -List | Select-Object Filename, LineNumber
(no output)
# Running in build\worktrees\gr-14f
$ Select-String -Path scripts\*.py -Pattern "processReceiptInput" -List | Select-Object Filename, LineNumber; Select-String -Path scripts\db_guard\*.py -Pattern "processReceiptInput" -List | Select-Object Filename, LineNumber
Filename                               LineNumber
--------                               ----------
test_migrate_db_policy_seed_rows.py          8125
test_verify_receipt_link_boundaries.py        177
→Read build\worktrees\gr-14f\scripts\db_guard\direct_barrier_bridge.py [limit=240, offset=1]
→Read build\worktrees\gr-14f\scripts\db_guard\direct_barrier_bridge.py [offset=310]
→Read build\worktrees\gr-14f\scripts\db_guard\structural_analysis\tokenizer.py [limit=70, offset=1270]
→Read build\worktrees\gr-14f\scripts\db_guard\structural_analysis\tokenizer.py [limit=30, offset=1340]
→Read build\worktrees\gr-14f\scripts\db_guard\structural_analysis\tokenizer.py [limit=50, offset=1416]
# Running in build\worktrees\gr-14f
$ Select-String -Path scripts\db_guard\structural_analysis\tokenizer.py -Pattern "_RE_VAL = |def _parse_val|_RE_VAL\b" | Select-Object LineNumber, Line
(no output)
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind
from scripts.db_guard.structural_analysis.model import SourceSpan

# L328 shape: val fileHash = receipt.imagePath?.let { path -> try {...} catch {...} }  + trailing comment content lines
body = (
    'val fileHash = receipt.imagePath?.let { path ->' + chr(10) +
    '    try {' + chr(10) +
    '        assetStore.computeFileHash(path).getOrNull()' + chr(10) +
    '    } catch (e: Exception) {' + chr(10) +
    '        CancellationSafe.rethrowIfCancellation(e)' + chr(10) +
    '        null' + chr(10) +
    '    }' + chr(10) +
    '}' + chr(10) +
    '// TODO RCP-5: Add image perceptual hash check for pre-OCR duplicate' + chr(10) +
    '// detection.  The file hash above only catches byte-identical images.' + chr(10) +
    'if (fileHash != null) {' + chr(10) +
    '    val dupResult = duplicateDetector.checkDuplicate(' + chr(10) +
    '        imageHash = fileHash,' + chr(10) +
    '        textFingerprint = null,' + chr(10) +
    '        semanticFingerprint = null,' + chr(10) +
    '        externalSourceId = null' + chr(10) +
    '    )' + chr(10) +
    '}' + chr(10)
)
masked = mask_kotlin_source(body)
res = parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), transparent_inline_methods=('let','also','takeIf','runCatching','onFailure','takeUnless','apply','run','with','getOrElse','use','repeat','forEach','map'))
print('supported:', res.is_supported)
for r in res.regions:
    print(' ', r.kind, r.span.start, r.span.end, 'method=', r.scope_method, 'recv=', r.scope_receiver)
for f in res.unsupported:
    print('  FINDING:', f.code, f.reason, f.span.start, f.span.end)
"
supported: False
  RegionKind.IF 351 566 method= None recv= None
  FINDING: DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE lambda-escape 0 210
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind
from scripts.db_guard.structural_analysis.model import SourceSpan

# The L328 statement alone
body = (
    'val fileHash = receipt.imagePath?.let { path ->' + chr(10) +
    '    try {' + chr(10) +
    '        assetStore.computeFileHash(path).getOrNull()' + chr(10) +
    '    } catch (e: Exception) {' + chr(10) +
    '        CancellationSafe.rethrowIfCancellation(e)' + chr(10) +
    '        null' + chr(10) +
    '    }' + chr(10) +
    '}' + chr(10)
)
masked = mask_kotlin_source(body)
res = parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), transparent_inline_methods=('let','also','takeIf'))
print('supported:', res.is_supported)
for r in res.regions:
    print(' ', r.kind, r.span.start, r.span.end, 'method=', r.scope_method, 'recv=', r.scope_receiver)
for f in res.unsupported:
    print('  FINDING:', f.code, f.reason, f.span.start, f.span.end)
print()
# chained form: x?.let { }.also { }
body2 = 'x?.let { a ->' + chr(10) + '    dao.update(a)' + chr(10) + '}.also {' + chr(10) + '    log()' + chr(10) + '}' + chr(10)
masked2 = mask_kotlin_source(body2)
res2 = parse_callable_body(masked2, SourceSpan(0, len(masked2), 1, 1), transparent_inline_methods=('let','also'))
print('supported2:', res2.is_supported)
for r in res2.regions:
    print(' ', r.kind, r.span.start, r.span.end, 'method=', r.scope_method, 'recv=', r.scope_receiver)
for f in res2.unsupported:
    print('  FINDING2:', f.code, f.reason, f.span.start, f.span.end)
"
supported: False
  FINDING: DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE lambda-escape 0 210

supported2: False
  FINDING2: DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE lambda-escape 0 52
→Read build\worktrees\gr-14f\scripts\db_guard\structural_analysis\tokenizer.py [limit=55, offset=380]
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind
from scripts.db_guard.structural_analysis.model import SourceSpan

# L328 with trailing content lines (comment-masked whitespace + if stmt) — how does the statement split?
body = (
    'val fileHash = receipt.imagePath?.let { path ->' + chr(10) +
    '    try {' + chr(10) +
    '        assetStore.computeFileHash(path).getOrNull()' + chr(10) +
    '    } catch (e: Exception) {' + chr(10) +
    '        CancellationSafe.rethrowIfCancellation(e)' + chr(10) +
    '        null' + chr(10) +
    '    }' + chr(10) +
    '}' + chr(10) +
    'val marker = 1' + chr(10)
)
masked = mask_kotlin_source(body)
res = parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), transparent_inline_methods=('let',))
print('supported:', res.is_supported)
for r in res.regions:
    print(' ', r.kind, r.span.start, r.span.end, 'method=', r.scope_method, 'recv=', r.scope_receiver)
for f in res.unsupported:
    print('  FINDING:', f.code, f.reason, f.span.start, f.span.end)
"
supported: False
  RegionKind.STATEMENT 211 225 method= None recv= None
  FINDING: DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE lambda-escape 0 210
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind
from scripts.db_guard.structural_analysis.model import SourceSpan

# The elvis inside the catch: 'null' after rethrow... Actually the catch body has 'null' as last expression - fine.
# Try WITHOUT try/catch to isolate: does the plain safe-call chain with trailing content parse?
body = (
    'val fileHash = receipt.imagePath?.let { path ->' + chr(10) +
    '    val h = computeHash(path)' + chr(10) +
    '    h' + chr(10) +
    '}' + chr(10) +
    'val marker = 1' + chr(10)
)
masked = mask_kotlin_source(body)
res = parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), transparent_inline_methods=('let',))
print('supported:', res.is_supported)
for r in res.regions:
    print(' ', r.kind, r.span.start, r.span.end, 'method=', r.scope_method, 'recv=', r.scope_receiver)
for f in res.unsupported:
    print('  FINDING:', f.code, f.reason, f.span.start, f.span.end)
"
supported: False
  RegionKind.STATEMENT 86 100 method= None recv= None
  FINDING: DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE lambda-escape 0 85
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind
from scripts.db_guard.structural_analysis.model import SourceSpan

# why does the trailing content break it? try with the safe-call ENDING the statement (u40 known-good):
body = (
    'val fileHash = receipt.imagePath?.let { path ->' + chr(10) +
    '    val h = computeHash(path)' + chr(10) +
    '    h' + chr(10) +
    '}' + chr(10)
)
masked = mask_kotlin_source(body)
res = parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), transparent_inline_methods=('let',))
print('A supported:', res.is_supported)
for r in res.regions: print('  ', r.kind, r.scope_method, r.scope_receiver)
for f in res.unsupported: print('   FINDING:', f.code, f.reason)

# and a CHAINED val-prefixed u46 form
body2 = (
    'val fileHash = receipt.getHash().let { h ->' + chr(10) +
    '    h' + chr(10) +
    '}.also {' + chr(10) +
    '    log()' + chr(10) +
    '}' + chr(10)
)
masked2 = mask_kotlin_source(body2)
res2 = parse_callable_body(masked2, SourceSpan(0, len(masked2), 1, 1), transparent_inline_methods=('let','also'))
print('B supported:', res2.is_supported)
for r in res2.regions: print('  ', r.kind, r.scope_method, r.scope_receiver)
for f in res2.unsupported: print('   FINDING:', f.code, f.reason)
"
A supported: False
   FINDING: DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE lambda-escape
B supported: True
   RegionKind.TRANSPARENT_SCOPE let None
   RegionKind.TRANSPARENT_SCOPE also None
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind, _match_safe_call_carrier, _match_carrier_chain
from scripts.db_guard.structural_analysis.model import SourceSpan

methods = ('let','also','takeIf')
s = 'val fileHash = receipt.imagePath?.let { path ->' + chr(10) + '    h' + chr(10) + '}' + chr(10)
masked = mask_kotlin_source(s)
stripped = masked.strip()
print('safe_call match:', _match_safe_call_carrier(stripped, methods))
print('chain match:', _match_carrier_chain(stripped, methods))
# without val prefix:
s2 = 'receipt.imagePath?.let { h ->' + chr(10) + '    h' + chr(10) + '}' + chr(10)
masked2 = mask_kotlin_source(s2)
st2 = masked2.strip()
print('safe_call match2:', _match_safe_call_carrier(st2, methods))
"
safe_call match: None
chain match: None
safe_call match2: None
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind
from scripts.db_guard.structural_analysis.model import SourceSpan

# plain u40 known-good on ONE line, no val prefix:
body = 'runId?.let { rid ->' + chr(10) + '  val x = 1' + chr(10) + '}' + chr(10)
masked = mask_kotlin_source(body)
res = parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), transparent_inline_methods=('let',))
print('A supported:', res.is_supported)
for r in res.regions: print('  ', r.kind, r.scope_method, r.scope_receiver)
for f in res.unsupported: print('   FINDING:', f.code, f.reason, f.span)

# multi-receiver: receipt.imagePath?.let — should FAIL (u40 simple-ident rule)
body2 = 'receipt.imagePath?.let { rid ->' + chr(10) + '  val x = 1' + chr(10) + '}' + chr(10)
masked2 = mask_kotlin_source(body2)
res2 = parse_callable_body(masked2, SourceSpan(0, len(masked2), 1, 1), transparent_inline_methods=('let',))
print('B supported:', res2.is_supported)
for f in res2.unsupported: print('   FINDING:', f.code, f.reason)
"
A supported: True
   RegionKind.TRANSPARENT_SCOPE let runId
B supported: False
   FINDING: DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE lambda-escape
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind
from scripts.db_guard.structural_analysis.model import SourceSpan

# test 1: x?.let { }.also { }  (mixed segments, chained after a safe-call head)
body = 'x?.let { a ->' + chr(10) + '    dao.update(a)' + chr(10) + '}.also {' + chr(10) + '    log()' + chr(10) + '}' + chr(10)
masked = mask_kotlin_source(body)
res = parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), transparent_inline_methods=('let','also','takeIf'))
print('A supported:', res.is_supported)
for r in res.regions: print('  ', r.kind, r.span.start, r.span.end, r.scope_method, r.scope_receiver)
for f in res.unsupported: print('   FINDING:', f.code, f.reason, f.span)

# test 2: x?.takeIf { }.let { }
body2 = 'x?.takeIf { it.isNotBlank() }' + chr(10) + '    ?.let { assetStore.deleteAsset(it) }' + chr(10)
masked2 = mask_kotlin_source(body2)
res2 = parse_callable_body(masked2, SourceSpan(0, len(masked2), 1, 1), transparent_inline_methods=('let','also','takeIf'))
print('B supported:', res2.is_supported)
for r in res2.regions: print('  ', r.kind, r.span.start, r.span.end, r.scope_method, r.scope_receiver)
for f in res2.unsupported: print('   FINDING:', f.code, f.reason, f.span)

# test 3: x?.let { }.customThing { }.also { } -> fallback
body3 = 'x?.let { a ->' + chr(10) + '    dao.update(a)' + chr(10) + '}.customThing { y ->' + chr(10) + '    log()' + chr(10) + '}.also {' + chr(10) + '    log()' + chr(10) + '}' + chr(10)
masked3 = mask_kotlin_source(body3)
res3 = parse_callable_body(masked3, SourceSpan(0, len(masked3), 1, 1), transparent_inline_methods=('let','also','takeIf'))
print('C supported:', res3.is_supported)
for f in res3.unsupported: print('   FINDING:', f.code, f.reason, f.span)

# test 4: x?.let { }.also { }.toString() -> fallback
body4 = 'x?.let { a ->' + chr(10) + '    dao.update(a)' + chr(10) + '}.also {' + chr(10) + '    log()' + chr(10) + '}.toString()' + chr(10)
masked4 = mask_kotlin_source(body4)
res4 = parse_callable_body(masked4, SourceSpan(0, len(masked4), 1, 1), transparent_inline_methods=('let','also','takeIf'))
print('D supported:', res4.is_supported)
for f in res4.unsupported: print('   FINDING:', f.code, f.reason, f.span)
"
A supported: False
   FINDING: DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE lambda-escape SourceSpan(start=0, end=52, line=1, column=1)
B supported: False
   RegionKind.TRANSPARENT_SCOPE 0 29 takeIf x
   FINDING: DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE lambda-escape SourceSpan(start=34, end=70, line=2, column=5)
C supported: False
   FINDING: DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE lambda-escape SourceSpan(start=0, end=83, line=1, column=1)
D supported: False
   FINDING: DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE lambda-escape SourceSpan(start=0, end=63, line=1, column=1)
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind
from scripts.db_guard.structural_analysis.model import SourceSpan

# L272 shape: when { validation.errors.any { ... } -> ... } — inside a when block
body = (
    'val reasonCode = when {' + chr(10) +
    '    validation.errors.any { it.contains(\"not readable\") } -> \"URI_NOT_READABLE\"' + chr(10) +
    '    validation.errors.any { it.contains(\"MIME\") } -> \"MIME_UNKNOWN\"' + chr(10) +
    '    else -> \"VALIDATION_FAILED\"' + chr(10) +
    '}' + chr(10)
)
masked = mask_kotlin_source(body)
res = parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), transparent_inline_methods=('let','also','takeIf','any'))
print('supported:', res.is_supported)
for r in res.regions:
    print(' ', r.kind, r.span.start, r.span.end, r.scope_method)
    for c in r.children:
        print('    child:', c.kind, c.span.start, c.span.end, c.scope_method)
for f in res.unsupported:
    print('  FINDING:', f.code, f.reason, f.span)
"
File "<string>", line 11
    '    validation.errors.any { it.contains(" not readable\)
    ^
SyntaxError: unterminated string literal (detected at line 11)
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind
from scripts.db_guard.structural_analysis.model import SourceSpan

q = chr(34)
body = (
    'val reasonCode = when {' + chr(10) +
    '    validation.errors.any { it.contains(' + q + 'not readable' + q + ') } -> ' + q + 'URI_NOT_READABLE' + q + chr(10) +
    '    validation.errors.any { it.contains(' + q + 'MIME' + q + ') } -> ' + q + 'MIME_UNKNOWN' + q + chr(10) +
    '    else -> ' + q + 'VALIDATION_FAILED' + q + chr(10) +
    '}' + chr(10)
)
masked = mask_kotlin_source(body)
res = parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), transparent_inline_methods=('let','also','takeIf','any'))
print('supported:', res.is_supported)
for r in res.regions:
    print(' ', r.kind, r.span.start, r.span.end, r.scope_method)
    for c in r.children:
        print('    child:', c.kind, c.span.start, c.span.end, c.scope_method)
for f in res.unsupported:
    print('  FINDING:', f.code, f.reason, f.span)
"
supported: False
  RegionKind.STATEMENT 0 205 None
    child: RegionKind.WHEN 17 205 None
  FINDING: DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE lambda-escape SourceSpan(start=108, end=204, line=3, column=5)
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind
from scripts.db_guard.structural_analysis.model import SourceSpan

q = chr(34)
# just the branch statement alone
body = 'validation.errors.any { it.contains(' + q + 'MIME' + q + ') } -> ' + q + 'MIME_UNKNOWN' + q + chr(10)
masked = mask_kotlin_source(body)
res = parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), transparent_inline_methods=('let','also','takeIf','any'))
print('supported:', res.is_supported)
for r in res.regions:
    print(' ', r.kind, r.span.start, r.span.end, r.scope_method)
for f in res.unsupported:
    print('  FINDING:', f.code, f.reason, f.span)
"
supported: False
  FINDING: DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE lambda-escape SourceSpan(start=0, end=64, line=1, column=1)
$
▣  Specialist-Coder · GLM 5.3 Flash (bare, fast route)
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind
from scripts.db_guard.structural_analysis.model import SourceSpan

# L353 shape: val existing = dao.getById(...)?.also { it.taxInclusive = taxInclusive }
body = 'val existing = scannedReceiptDao.getById(dupResult.existingReceiptId!!)?.also {' + chr(10) + '    it.taxInclusive = taxInclusive' + chr(10) + '}' + chr(10)
masked = mask_kotlin_source(body)
res = parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), transparent_inline_methods=('let','also','takeIf'))
print('supported:', res.is_supported)
for r in res.regions:
    print(' ', r.kind, r.span.start, r.span.end, r.scope_method, r.scope_receiver)
for f in res.unsupported:
    print('  FINDING:', f.code, f.reason, f.span)
"
supported: False
  FINDING: DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE lambda-escape SourceSpan(start=0, end=116, line=1, column=1)
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind
from scripts.db_guard.structural_analysis.model import SourceSpan

# L361/L384 shape: receipt.imagePath?.let { assetStore.deleteAsset(it) } — simple statement inside if
body = 'if (receipt.id <= 0L) {' + chr(10) + '    receipt.imagePath?.let { assetStore.deleteAsset(it) }' + chr(10) + '    log()' + chr(10) + '}' + chr(10)
masked = mask_kotlin_source(body)
res = parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), transparent_inline_methods=('let','also','takeIf'))
print('supported:', res.is_supported)
def walk(rs, d=0):
    for r in rs:
        print('  '*d, r.kind, r.span.start, r.span.end, r.scope_method, r.scope_receiver)
        walk(r.children, d+1)
walk(res.regions)
for f in res.unsupported:
    print('  FINDING:', f.code, f.reason, f.span)
"
supported: False
 RegionKind.IF 0 93 None None
   RegionKind.BLOCK 22 93 None None
     RegionKind.STATEMENT 86 91 None None
  FINDING: DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE lambda-escape SourceSpan(start=28, end=81, line=2, column=5)
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind
from scripts.db_guard.structural_analysis.model import SourceSpan

# L444 shape: dao.getById(...)?.let { existing -> ...multi-line with elvis... }
body = (
    'scannedReceiptDao.getById(postOcrDup.existingReceiptId!!)?.let { existing ->' + chr(10) +
    '    val now = timeProvider.now()' + chr(10) +
    '    val withFingerprints = ReceiptTimestampPolicy.forUpdate(receipt.copy(' + chr(10) +
    '        imageHash = fileHash ?: receipt.imageHash,' + chr(10) +
    '        textFingerprint = textFingerprint' + chr(10) +
    '    ), now).also { it.taxInclusive = taxInclusive }' + chr(10) +
    '    scannedReceiptDao.update(withFingerprints)' + chr(10) +
    '    existing' + chr(10) +
    '}' + chr(10)
)
masked = mask_kotlin_source(body)
res = parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), transparent_inline_methods=('let','also','takeIf'))
print('supported:', res.is_supported)
for f in res.unsupported:
    print('  FINDING:', f.code, f.reason, f.span)
"
supported: False
  FINDING: DB_STRUCTURAL_MODEL_CONTROL_FLOW_UNSUPPORTED elvis-block SourceSpan(start=0, end=390, line=1, column=1)
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind
from scripts.db_guard.structural_analysis.model import SourceSpan

# L488-489 as a standalone statement
body = 'receipt.imagePath?.takeIf { it.isNotBlank() }' + chr(10) + '    ?.let { assetStore.deleteAsset(it) }' + chr(10)
masked = mask_kotlin_source(body)
res = parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), transparent_inline_methods=('let','also','takeIf'))
print('supported:', res.is_supported)
def walk(rs, d=0):
    for r in rs:
        print('  '*d, r.kind, r.span.start, r.span.end, r.scope_method, r.scope_receiver)
        walk(r.children, d+1)
walk(res.regions)
for f in res.unsupported:
    print('  FINDING:', f.code, f.reason, f.span)
"
supported: False
  FINDING: DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE lambda-escape SourceSpan(start=0, end=45, line=1, column=1)
  FINDING: DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE lambda-escape SourceSpan(start=50, end=86, line=2, column=5)
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind
from scripts.db_guard.structural_analysis.model import SourceSpan

# L488-489 inside an if-block context (like real code)
body = (
    'if (existing != null) {' + chr(10) +
    '    receipt.imagePath?.takeIf { it.isNotBlank() }' + chr(10) +
    '        ?.let { assetStore.deleteAsset(it) }' + chr(10) +
    '    existing.taxInclusive = taxInclusive' + chr(10) +
    '}' + chr(10)
)
masked = mask_kotlin_source(body)
res = parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), transparent_inline_methods=('let','also','takeIf'))
print('supported:', res.is_supported)
def walk(rs, d=0):
    for r in rs:
        print('  '*d, r.kind, r.span.start, r.span.end, r.scope_method, r.scope_receiver)
        walk(r.children, d+1)
walk(res.regions)
for f in res.unsupported:
    print('  FINDING:', f.code, f.reason, f.span)
"
supported: False
 RegionKind.IF 0 161 None None
   RegionKind.BLOCK 22 161 None None
     RegionKind.STATEMENT 123 159 None None
  FINDING: DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE lambda-escape SourceSpan(start=28, end=73, line=2, column=5)
  FINDING: DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE lambda-escape SourceSpan(start=82, end=118, line=3, column=9)
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind
from scripts.db_guard.structural_analysis.model import SourceSpan

# L488-489 as ONE statement? The line-continuation: '...takeIf { it.isNotBlank() }' ends with '}' — is '}' in _CONT_END? No. But '?.let' on next line — dot_cont checks text[nxt] == '.', and here nxt char is '?' — NOT '.', so it splits into TWO statements. Interesting!
# Verify: does the tokenizer split L488-489 into two statements?  The two findings above (0-45 and 50-86) suggest YES: two separate statements.
# So the L488-489 composite needs the SPLIT too? No — the fix: the first statement 'receipt.imagePath?.takeIf { it.isNotBlank() }' does not end the statement cleanly (ends with '}'), so lambda-escape.
# With the new matcher: 'receipt.imagePath?.takeIf { ... }' — head safe-call with multi-segment receiver 'receipt.imagePath'... 

# First, what is the receiver here? 'receipt.imagePath' — dotted. u40 requires SIMPLE identifier. The task says scope_receiver for the safe-call head: the simple identifier (u40 precedent).
# Hmm — but L328/L488 heads are 'receipt.imagePath?.let' — receiver is DOTTED. Let me check what u40's _match_safe_call_carrier accepts: (?P<receiver>[A-Za-z_][A-Za-z0-9_]*) — simple only.
# The task says: '[val/var prefix]? receiver?.carrier1(args?) { ... }' — receiver presumably can be dotted? The task says scope_receiver: the simple identifier (u40 precedent).
# Test how the u46 chain matcher handles 'receipt.imagePath?.let' — prefix charset excludes '?' so no.

# Check: is there an existing matcher that would claim 'receipt.imagePath?.takeIf { it.isNotBlank() }' if it ENDED the statement? _match_safe_call_carrier: receiver must be simple ident -> no.
print('probe done')
"
probe done
→Read build\worktrees\gr-14f\scripts\db_guard\structural_analysis\tokenizer.py [limit=30, offset=92]
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind
from scripts.db_guard.structural_analysis.model import SourceSpan

# Is L488-489 really split into two statements? Test: a chained form on ONE line with ?.let continuation:
body = 'receipt.imagePath?.takeIf { it.isNotBlank() }?.let { assetStore.deleteAsset(it) }' + chr(10)
masked = mask_kotlin_source(body)
res = parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), transparent_inline_methods=('let','also','takeIf'))
print('one-line supported:', res.is_supported)
for f in res.unsupported:
    print('  FINDING:', f.code, f.reason, f.span)
"
one-line supported: False
  FINDING: DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE lambda-escape SourceSpan(start=0, end=81, line=1, column=1)
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import _split_statements, _Cursor

body = 'receipt.imagePath?.takeIf { it.isNotBlank() }' + chr(10) + '    ?.let { assetStore.deleteAsset(it) }' + chr(10)
masked = mask_kotlin_source(body)
cur = _Cursor(masked)
parts = _split_statements(cur, 0, len(masked))
for s, e in parts:
    print((s, e), repr(masked[s:e]))
"
(0, 45) 'receipt.imagePath?.takeIf { it.isNotBlank() }'
(50, 86) '?.let { assetStore.deleteAsset(it) }'
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import _split_statements, _Cursor

# L328 statement boundary check with trailing content:
body = (
    'val fileHash = receipt.imagePath?.let { path ->' + chr(10) +
    '    try {' + chr(10) +
    '        assetStore.computeFileHash(path).getOrNull()' + chr(10) +
    '    } catch (e: Exception) {' + chr(10) +
    '        CancellationSafe.rethrowIfCancellation(e)' + chr(10) +
    '        null' + chr(10) +
    '    }' + chr(10) +
    '}' + chr(10) +
    'val marker = 1' + chr(10)
)
masked = mask_kotlin_source(body)
cur = _Cursor(masked)
parts = _split_statements(cur, 0, len(masked))
for s, e in parts:
    print((s, e), repr(masked[s:e][:80]))
"
(0, 210) 'val fileHash = receipt.imagePath?.let { path ->\n    try {\n        assetStore.com'
(211, 225) 'val marker = 1'
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind
from scripts.db_guard.structural_analysis.model import SourceSpan

# L328 statement ALONE (ends the statement) — baseline pre-fix
body = (
    'val fileHash = receipt.imagePath?.let { path ->' + chr(10) +
    '    try {' + chr(10) +
    '        assetStore.computeFileHash(path).getOrNull()' + chr(10) +
    '    } catch (e: Exception) {' + chr(10) +
    '        CancellationSafe.rethrowIfCancellation(e)' + chr(10) +
    '        null' + chr(10) +
    '    }' + chr(10) +
    '}' + chr(10)
)
masked = mask_kotlin_source(body)
res = parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), transparent_inline_methods=('let','also','takeIf'))
print('supported:', res.is_supported)
for f in res.unsupported:
    print('  FINDING:', f.code, f.reason, f.span)
"
supported: False
  FINDING: DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE lambda-escape SourceSpan(start=0, end=210, line=1, column=1)
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind
from scripts.db_guard.structural_analysis.model import SourceSpan

# Why does the L328 statement alone fail? The u40 matcher requires SIMPLE receiver — 'receipt.imagePath' is dotted. 
# Confirm by simplifying receiver to simple ident:
body = (
    'val fileHash = imagePath?.let { path ->' + chr(10) +
    '    try {' + chr(10) +
    '        assetStore.computeFileHash(path).getOrNull()' + chr(10) +
    '    } catch (e: Exception) {' + chr(10) +
    '        CancellationSafe.rethrowIfCancellation(e)' + chr(10) +
    '        null' + chr(10) +
    '    }' + chr(10) +
    '}' + chr(10)
)
masked = mask_kotlin_source(body)
res = parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), transparent_inline_methods=('let','also','takeIf'))
print('simple-receiver supported:', res.is_supported)
def walk(rs, d=0):
    for r in rs:
        print('  '*d, r.kind, r.span.start, r.span.end, r.scope_method, r.scope_receiver)
        walk(r.children, d+1)
walk(res.regions)
for f in res.unsupported:
    print('  FINDING:', f.code, f.reason, f.span)
"
simple-receiver supported: True
 RegionKind.TRANSPARENT_SCOPE 0 202 let imagePath
   RegionKind.TRY 44 200 None None
     RegionKind.TRY 44 108 None None
       RegionKind.STATEMENT 58 102 None None
     RegionKind.CATCH 109 200 None None
       RegionKind.STATEMENT 140 181 None None
       RegionKind.STATEMENT 190 194 None None
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind
from scripts.db_guard.structural_analysis.model import SourceSpan

# L272 shape: 'validation.errors.any { ... } -> \"MIME_UNKNOWN\"' — the when-branch body
# The head is 'validation.errors.any { }' — a u39 CHAINED carrier (prefix 'validation.errors'). 
# The trailing content after the lambda: ' -> \"MIME_UNKNOWN\"' — that's the when-branch arrow!
# In a when branch, the lambda does NOT end the statement. Hmm — but the finding reported by the orchestrator is a 'chained carrier with trailing content'.
# Wait: when-branch statements are parsed by _parse_when, and the branch body after '->' is the branch result. Let me check how when branches split statements.
body = (
    'val reasonCode = when {' + chr(10) +
    '    validation.errors.any { it.contains(\"X\") } -> \"A\"' + chr(10) +
    '    else -> \"B\"' + chr(10) +
    '}' + chr(10)
)
masked = mask_kotlin_source(body)
res = parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), transparent_inline_methods=('let','also','takeIf','any'))
print('supported:', res.is_supported)
def walk(rs, d=0):
    for r in rs:
        print('  '*d, r.kind, r.span.start, r.span.end, r.scope_method, r.scope_receiver)
        walk(r.children, d+1)
walk(res.regions)
for f in res.unsupported:
    print('  FINDING:', f.code, f.reason, f.span)
"
(no output)
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind
from scripts.db_guard.structural_analysis.model import SourceSpan
q = chr(34)
body = (
    'val reasonCode = when {' + chr(10) +
    '    validation.errors.any { it.contains(' + q + 'X' + q + ') } -> ' + q + 'A' + q + chr(10) +
    '    else -> ' + q + 'B' + q + chr(10) +
    '}' + chr(10)
)
masked = mask_kotlin_source(body)
res = parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), transparent_inline_methods=('let','also','takeIf','any'))
print('supported:', res.is_supported)
def walk(rs, d=0):
    for r in rs:
        print('  '*d, r.kind, r.span.start, r.span.end, r.scope_method, r.scope_receiver)
        walk(r.children, d+1)
walk(res.regions)
for f in res.unsupported:
    print('  FINDING:', f.code, f.reason, f.span)
"
supported: False
 RegionKind.STATEMENT 0 95 None None
   RegionKind.WHEN 17 95 None None
     RegionKind.WHEN_BRANCH 28 94 None None
  FINDING: DB_STRUCTURAL_MODEL_SYNTAX_UNBALANCED dangling-clause SourceSpan(start=82, end=94, line=3, column=5)
$python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind
from scripts.db_guard.structural_analysis.model import SourceSpan
q = chr(34)
# with else branch properly (no dangling): the previous failure is a separate shape. Use real L270-277 shape:
body = (
    'val reasonCode = when {' + chr(10) +
    '    validation.errors.any { it.contains(' + q + 'not readable' + q + ') } -> ' + q + 'URI_NOT_READABLE' + q + chr(10) +
    '    validation.errors.any { it.contains(' + q + 'MIME type' + q + ') || it.contains(' + q + 'determine MIME' + q + ') } -> ' + q + 'MIME_UNKNOWN' + q + chr(10) +
    '    else -> ' + q + 'VALIDATION_FAILED' + q + chr(10) +
    '}' + chr(10)
)
masked = mask_kotlin_source(body)
res = parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), transparent_inline_methods=('let','also','takeIf','any'))
print('supported:', res.is_supported)
def walk(rs, d=0):
    for r in rs:
        print('  '*d, r.kind, r.span.start, r.span.end, r.scope_method, r.scope_receiver)
        walk(r.children, d+1)
walk(res.regions)
for f in res.unsupported:
    print('  FINDING:', f.code, f.reason, f.span)
"
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind
from scripts.db_guard.structural_analysis.model import SourceSpan
q = chr(34)
body = (
    'val reasonCode = when {' + chr(10) +
    '    validation.errors.any { it.contains(' + q + 'not readable' + q + ') } -> ' + q + 'URI_NOT_READABLE' + q + chr(10) +
    '    validation.errors.any { it.contains(' + q + 'MIME type' + q + ') || it.contains(' + q + 'determine MIME' + q + ') } -> ' + q + 'MIME_UNKNOWN' + q + chr(10) +
    '    else -> ' + q + 'VALIDATION_FAILED' + q + chr(10) +
    '}' + chr(10)
)
masked = mask_kotlin_source(body)
res = parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), transparent_inline_methods=('let','also','takeIf','any'))
print('supported:', res.is_supported)
def walk(rs, d=0):
    for r in rs:
        print('  '*d, r.kind, r.span.start, r.span.end, r.scope_method, r.scope_receiver)
        walk(r.children, d+1)
walk(res.regions)
for f in res.unsupported:
    print('  FINDING:', f.code, f.reason, f.span)
"
supported: False
 RegionKind.STATEMENT 0 243 None None
   RegionKind.WHEN 17 243 None None
     RegionKind.WHEN_BRANCH 28 242 None None
  FINDING: DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE lambda-escape SourceSpan(start=108, end=242, line=3, column=5)
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind
from scripts.db_guard.structural_analysis.model import SourceSpan
q = chr(34)
# Isolate branch 2: 'validation.errors.any { it.contains(\"MIME type\") || it.contains(\"determine MIME\") } -> \"MIME_UNKNOWN\"'
body = 'validation.errors.any { it.contains(' + q + 'MIME type' + q + ') || it.contains(' + q + 'determine MIME' + q + ') } -> ' + q + 'MIME_UNKNOWN' + q + chr(10)
masked = mask_kotlin_source(body)
res = parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), transparent_inline_methods=('let','also','takeIf','any'))
print('supported:', res.is_supported)
for f in res.unsupported:
    print('  FINDING:', f.code, f.reason, f.span)
# and branch 1 shape:
body2 = 'validation.errors.any { it.contains(' + q + 'not readable' + q + ') } -> ' + q + 'URI_NOT_READABLE' + q + chr(10)
masked2 = mask_kotlin_source(body2)
res2 = parse_callable_body(masked2, SourceSpan(0, len(masked2), 1, 1), transparent_inline_methods=('let','also','takeIf','any'))
print('b2 supported:', res2.is_supported)
for f in res2.unsupported:
    print('  FINDING2:', f.code, f.reason, f.span)
"
(no output)
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind
from scripts.db_guard.structural_analysis.model import SourceSpan
q = chr(34)
body = 'validation.errors.any { it.contains(' + q + 'MIME type' + q + ') || it.contains(' + q + 'determine MIME' + q + ') } -> ' + q + 'MIME_UNKNOWN' + q + chr(10)
masked = mask_kotlin_source(body)
res = parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), transparent_inline_methods=('let','also','takeIf','any'))
print('supported:', res.is_supported)
for f in res.unsupported:
    print('  FINDING:', f.code, f.reason, f.span)
body2 = 'validation.errors.any { it.contains(' + q + 'not readable' + q + ') } -> ' + q + 'URI_NOT_READABLE' + q + chr(10)
masked2 = mask_kotlin_source(body2)
res2 = parse_callable_body(masked2, SourceSpan(0, len(masked2), 1, 1), transparent_inline_methods=('let','also','takeIf','any'))
print('b2 supported:', res2.is_supported)
for f in res2.unsupported:
    print('  FINDING2:', f.code, f.reason, f.span)
"
supported: False
  FINDING: DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE lambda-escape SourceSpan(start=0, end=102, line=1, column=1)
b2 supported: False
  FINDING2: DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE lambda-escape SourceSpan(start=0, end=76, line=1, column=1)
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import _split_statements, _Cursor
q = chr(34)
body = 'validation.errors.any { it.contains(' + q + 'not readable' + q + ') } -> ' + q + 'URI_NOT_READABLE' + q + chr(10)
masked = mask_kotlin_source(body)
cur = _Cursor(masked)
parts = _split_statements(cur, 0, len(masked))
for s, e in parts:
    print((s, e), repr(masked[s:e]))
"
(0, 76) 'validation.errors.any { it.contains(              ) } ->                   \n'
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind
from scripts.db_guard.structural_analysis.model import SourceSpan
q = chr(34)
# How do when-branch conditions get parsed? _parse_when -> branch condition before '->' is its own statement? Let me look at the when branch parsing directly. Find _parse_when.
" ; Select-String -Path scripts\db_guard\structural_analysis\tokenizer.py -Pattern "def _parse_when" | Select-Object LineNumber
LineNumber
----------
      1686
→Read build\worktrees\gr-14f\scripts\db_guard\structural_analysis\tokenizer.py [limit=160, offset=1686]
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind
from scripts.db_guard.structural_analysis.model import SourceSpan
q = chr(34)
# The when-branch CONDITION (LHS of ->) is NOT parsed by _parse_when (only RHS). So the L272 finding must come from elsewhere.
# The orchestrator says: 'validation.errors.any { ... } (L272-277) — a chained carrier with trailing content.'
# When the when-refused, or when this shape appears OUTSIDE a when? Actually L272 is INSIDE the when block at L270.
# _parse_when only parses the RHS. So where does the L272 lambda-escape finding come from? Maybe the when parse succeeded but... in my probe above, the when-branch region was built and the finding was at line 3 (branch 2's RHS?). No — span 108-242 covers branch 2 and 3.
# Actually _parse_sequence on RHS ' -> \"MIME_UNKNOWN\"' — the RHS parse... hmm the finding at 108 covers from branch2 start. Branch spans: branch1 28-105ish, branch2 starts 108.
# So the finding is INSIDE branch 2's RHS? No — RHS is just the string literal. Wait, the branch span 108-242: estr for branch2 = text[108:242]. rhs = after '->'. rhs parse = _parse_sequence(108+arrow...) — that's just the masked string. No lambda.
# The finding reason lambda-escape at 108-242 — hmm, that's the whole branch2. Where does that come from? Maybe _parse_sequence over the whole when-entries region... no.
# Let me directly probe: parse ONLY the branch2 text as a body.
body = 'validation.errors.any { it.contains(' + q + 'MIME type' + q + ') || it.contains(' + q + 'determine MIME' + q + ') } -> ' + q + 'MIME_UNKNOWN' + q + chr(10)
masked = mask_kotlin_source(body)
res = parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), transparent_inline_methods=('let','also','takeIf','any'))
print('supported:', res.is_supported)
for f in res.unsupported:
    print('  FINDING:', f.code, f.reason, f.span)
"
(no output)
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind
from scripts.db_guard.structural_analysis.model import SourceSpan
q = chr(34)
body = 'validation.errors.any { it.contains(' + q + 'MIME type' + q + ') || it.contains(' + q + 'determine MIME' + q + ') } -> ' + q + 'MIME_UNKNOWN' + q
masked = mask_kotlin_source(body)
res = parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), transparent_inline_methods=('let','also','takeIf','any'))
sys.stdout.write('supported: %s' % res.is_supported + chr(10))
for f in res.unsupported:
    sys.stdout.write('  FINDING: %s %s %s' % (f.code, f.reason, f.span) + chr(10))
"
supported: False
  FINDING: DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE lambda-escape SourceSpan(start=0, end=101, line=1, column=1)
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body, RegionKind
from scripts.db_guard.structural_analysis.model import SourceSpan
q = chr(34)
# the u39 chained-carrier head: 'validation.errors.any { }' — with the trailing ' -> ...' content. The u39 matcher:
#   prefix = 'validation.errors' (contains '.', fine), method='any'... but 'any' is not in the carrier set in production.
# In my probe I passed 'any' in transparent_inline_methods. Then the u39 tail check fails (trailing ' -> ...') -> falls through to lambda-escape.
# The orchestrator's claim: this is a 'chained carrier with trailing content' — under the NEW rule (2): subsequent segment 'plain balanced member call with NO lambda'.
# But here the trailing content is ' -> \"MIME_UNKNOWN\"' — not a '.member' call! It's a when-branch arrow. Hmm.
# Wait — maybe the orchestrator means the L272 finding comes from the when-branch condition NOT being claimed... Let me re-read the orchestrator defect list:
# 'validation.errors.any { ... } (L272-277) — a chained carrier with trailing content.'
# Under the new rule: the head is a plain chained carrier (u39) 'validation.errors.any { ... }', and the trailing content is... ' -> \"MIME_UNKNOWN\"'. 
# That's NOT '.carrier2(args?) { }' — rule 2 says every subsequent segment is '.name { }' or '?.name { }' carrier OR a plain balanced member call with NO lambda. ' -> \"...\"' is neither!
# Hmm. So how would L272 be admitted? Maybe the statement the tokenizer sees is NOT the whole branch — the when-branch is split by _split_statements INSIDE the when braces, and _parse_when handles it. The lambda-escape finding I got came from parsing the branch as a STANDALONE body.
# In the real when context, _parse_when never parses the condition LHS. So where did the real 9 findings come from? The orchestrator ran a probe with production params. The when at L270-277 IS parsed by _parse_when... and _parse_when does NOT emit findings for conditions.
# Unless the when itself failed to parse and fell back... In my when-probe above, the finding was lambda-escape at 108-242 — the branch2 span. That came from _parse_sequence over the RHS?? No...
# Actually wait: in my when probe, the branch2 span was 108-242 and the finding lambda-escape at exactly 108-242. _parse_when calls _parse_sequence(cur, rhs_base, ee, in_lambda) for arrow bodies. For branch2, rhs_base is after '->' — around offset 190. The finding span 108-242 = the whole branch.
# Hmm, that's the finding from parsing the branch condition as a statement... no, _parse_when doesn't parse conditions.
# Let me instrument: which code path emits lambda-escape for the when probe? Add a traceback hook.
"
(no output)
# Running in build\worktrees\gr-14f
$ python -c "
import sys, traceback
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
import scripts.db_guard.structural_analysis.tokenizer as tk
from scripts.db_guard.structural_analysis.model import SourceSpan

orig_fail = tk._Cursor.fail
def traced_fail(self, code, start, end, reason):
    traceback.print_stack()
    print('FAIL:', code, reason, start, end)
    return orig_fail(self, code, start, end)
tk._Cursor.fail = traced_fail

q = chr(34)
body = (
    'val reasonCode = when {' + chr(10) +
    '    validation.errors.any { it.contains(' + q + 'not readable' + q + ') } -> ' + q + 'URI_NOT_READABLE' + q + chr(10) +
    '    validation.errors.any { it.contains(' + q + 'MIME type' + q + ') || it.contains(' + q + 'determine MIME' + q + ') } -> ' + q + 'MIME_UNKNOWN' + q + chr(10) +
    '    else -> ' + q + 'VALIDATION_FAILED' + q + chr(10) +
    '}' + chr(10)
)
masked = mask_kotlin_source(body)
res = tk.parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), transparent_inline_methods=('let','also','takeIf','any'))
print('supported:', res.is_supported)
for f in res.unsupported:
    print('  FINDING:', f.code, f.reason, f.span)
" 2>&1 | Select-Object -Last 40
python :   File "<string>", line 24, in <module>
At line:1 char:1
FAIL: DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE lambda-escape 108 242
+ python -c "
+ ~~~~~~~~~~~
    + CategoryInfo          : NotSpecified: (  File "<string>", line 24, in <module>:String) [], RemoteException
    + FullyQualifiedErrorId : NativeCommandError
 
    res = tk.parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), 
transparent_inline_methods=('let','also','takeIf','any'))
…
Click to expand
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
import scripts.db_guard.structural_analysis.tokenizer as tk
from scripts.db_guard.structural_analysis.model import SourceSpan
q = chr(34)
# Branch2 RHS: after '->' at some offset... the finding span 108-242 = the WHOLE branch. _parse_sequence(cur, rhs_base, ee) where rhs_base ~ 189? 
# Hmm wait — maybe the arrow search found the FIRST '->' but branch2's condition contains '||' — no arrow inside.
# Let me compute: estr = text[es:ee]; arrow = estr.find('->'). For branch2, es=108. estr starts 'validation.errors.any {...' — no '->' until the arrow. arrow ~ 81 chars in.
# rhs = estr[arrow+2:] = ' \"MIME_UNKNOWN\"'... rhs_base = 108+83 = ~191. _parse_sequence(191, 242) — that's just the masked string. No lambda!
# But the FAIL says 108-242 — meaning _parse_sequence was called with start=108?? That means rhs_base=108?? That happens if arrow was found at -1... no, arrow<0 fails earlier.
# OH WAIT. Look again at the trace: _parse_when line 1805 -> _parse_sequence -> line 1420 lambda-escape. Line 1420 is the generic '{' in stripped lambda-escape. The span 108-242...
# Actually the mask: the strings are blanked. 'it.contains(              )' — masked. Hmm, and the ARROW: '->' inside estr... estr.find('->') — could '->' appear INSIDE the masked region? No.
# Let me just print rhs_base and ee.
" 2>&1 | Out-Null; python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
import scripts.db_guard.structural_analysis.tokenizer as tk
from scripts.db_guard.structural_analysis.model import SourceSpan
q = chr(34)
body = (
    'val reasonCode = when {' + chr(10) +
    '    validation.errors.any { it.contains(' + q + 'not readable' + q + ') } -> ' + q + 'URI_NOT_READABLE' + q + chr(10) +
    '    validation.errors.any { it.contains(' + q + 'MIME type' + q + ') || it.contains(' + q + 'determine MIME' + q + ') } -> ' + q + 'MIME_UNKNOWN' + q + chr(10) +
    '    else -> ' + q + 'VALIDATION_FAILED' + q + chr(10) +
    '}' + chr(10)
)
masked = mask_kotlin_source(body)
print(repr(masked))
"
'val reasonCode = when {\n    validation.errors.any { it.contains(              ) } ->                   \n    validation.errors.any { it.contains(           ) || it.contains(                ) } ->               \n    else ->                    \n}\n'
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
import scripts.db_guard.structural_analysis.tokenizer as tk
from scripts.db_guard.structural_analysis.model import SourceSpan
q = chr(34)
body = (
    'val reasonCode = when {' + chr(10) +
    '    validation.errors.any { it.contains(' + q + 'not readable' + q + ') } -> ' + q + 'URI_NOT_READABLE' + q + chr(10) +
    '    validation.errors.any { it.contains(' + q + 'MIME type' + q + ') || it.contains(' + q + 'determine MIME' + q + ') } -> ' + q + 'MIME_UNKNOWN' + q + chr(10) +
    '    else -> ' + q + 'VALIDATION_FAILED' + q + chr(10) +
    '}' + chr(10)
)
masked = mask_kotlin_source(body)
# split statements inside when braces (25..241):
parts = tk._split_statements(tk._Cursor(masked), 25, 241)
for s, e in parts:
    print((s, e), repr(masked[s:e]))
"
(28, 241) 'validation.errors.any { it.contains(              ) } ->                   \n    validation.errors.any { it.contains(           ) || it.contains(                ) } ->               \n    else ->                    '
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
import scripts.db_guard.structural_analysis.tokenizer as tk
masked = 'val reasonCode = when {' + chr(10) + '    validation.errors.any { it.contains(              ) } ->                   ' + chr(10) + '    validation.errors.any { it.contains(           ) || it.contains(                ) } ->               ' + chr(10) + '    else ->                    ' + chr(10) + '}' + chr(10)
# The when-entries split produced ONE part 28-241 — the whole when body! Why? The '}' at depth 0 (line 5) ends... The entries region is brace_abs+1 .. close-1. The lambda braces inside branch1/branch2 close at depth... The lambda '{' inside 'any { ... }' — depth goes 1 (lambda) then back to 0 at its '}'. Then ' } ->' — the branch continues. Then newline: stmt splits when depth==0 and not continuation. prev[-1] is ' ' — rstripped -> '}' not in _CONT_END. dot_cont: next non-ws char is 'v' — not '.'. So it SHOULD split after branch1's '}'...
# Unless depth != 0: the when's own '{' opened at depth 1 relative to the split region start? _split_statements(start=25, end=241): depth starts 0. The '}' closing the WHEN at 241 is OUTSIDE (end=241 exclusive)... The when body region is brace_abs+1 .. close-1, i.e. after when's '{' up to before when's '}'. Inside, lambda braces balance. So depth should return to 0 after each lambda close.
# Hmm wait — branch1: 'any { it.contains(...) }' — the lambda opens at depth 0->1, closes 1->0. Then ' -> ' then newline. prev = 'validation.errors.any { it.contains(              ) }' rstripped ends with '}' — fine. So it should split!
# Let me trace manually with prints.
cur = tk._Cursor(masked)
start, end = 25, 241
text = masked
depth = 0
stmt_start = None
i = start
events = []
while i < end:
    ch = text[i]
    if ch not in tk._WS and stmt_start is None:
        stmt_start = i
    if ch in '([{':
        depth += 1
    elif ch in ')]}':
        depth -= 1
        if depth == 0 and ch == '}' and stmt_start is not None:
            events.append(('close0', i, text[i-10:i+10]))
    if depth == 0 and stmt_start is not None:
        if ch == ';':
            events.append(('semi', i, None))
        elif ch == chr(10):
            prev = text[stmt_start:i].rstrip(tk._WS)
            nxt = cur.non_ws(i + 1, end)
            cont = bool(prev) and prev[-1] in tk._CONT_END
            dot_cont = nxt < end and text[nxt] == '.'
            events.append(('nl', i, (repr(prev[-6:]) if prev else None, cont, dot_cont, repr(text[nxt:nxt+3]))))
            if not cont and not dot_cont:
                events.append(('SPLIT', i, text[stmt_start:i][:40]))
                stmt_start = None
    i += 1
for ev in events: print(ev)
"
('close0', 80, '        ) } ->      ')
('nl', 103, ("') } ->'", True, False, "'val'"))
('close0', 190, '        ) } ->      ')
('nl', 209, ("') } ->'", True, False, "'els'"))
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
# prev[-1] is the last char of 'validation.errors.any { it.contains(              ) } ->                   ' rstripped = '>'
# '>' IS in _CONT_END ('+-*/%,:?.&|<>!(`['). So the line does NOT split — the whole when body glues into one statement!
# That's why _parse_when's arrow logic then splits it. OK — so within _parse_when, entries split produced ONE entry, arrow found at the FIRST '->', and rhs = everything after it = branch1-result + branch2 + else...
# rhs parse = _parse_sequence(rhs_base, ee) — that region contains 'validation.errors.any { ... }' (branch2's condition!) as a STATEMENT with a lambda -> lambda-escape at 108-242. Mystery solved.
# So the L272 finding family = when-branch multi-entry gluing? No wait — in the REAL source, branch1 ends '}' then newline then 'validation...' — same gluing.
# Hmm, but the orchestrator said L272-277 is 'a chained carrier with trailing content'. In the real probe, the when at L270-277: entries split glues ALL branches into one 'entry' because of the '->' continuation char. _parse_when then treats everything after the first '->' as branch1's RHS, and _parse_sequence over that RHS hits branch2's condition 'validation.errors.any { ... }' as a statement whose lambda doesn't end the statement (followed by '-> ...') -> lambda-escape.
# With the NEW composition matcher: would 'validation.errors.any { ... } -> \"MIME_UNKNOWN\"' be admitted? The trailing content after the lambda is ' -> \"MIME_UNKNOWN\"' — not a '.carrier' segment. Rule 2 says subsequent segments are '.name { }' / '?.name { }' carriers OR plain balanced member calls with NO lambda. ' -> \"MIME_UNKNOWN\"' is neither.
# Hmm. So how does the L272 finding get cleared? Maybe the new matcher DOESN'T clear L272 and it remains? But the task says 'the 9 should drop; report any that remain — they inform u56b'.
# Actually wait — re-read: the when-branch RHS glue means the branch2 condition statement is 'validation.errors.any { it.contains(...) || it.contains(...) } -> \"MIME_UNKNOWN\"' + more (branch3 'else -> ...' glued too).
# The trailing content after the lambda: ' -> \"MIME_UNKNOWN\"' newline 'else -> ...' — definitely not '.name' segments. So the composition matcher declines, falls back to lambda-escape. Finding REMAINS.
# Hmm, but the draft manifest claims the 9 drop to 2. Let me carefully enumerate the 9 findings by actually running the probe later. First, let me check: maybe the when gluing produces a DIFFERENT statement shape... Actually the rhs of branch1 (after first '->') contains:
#   '\"URI_NOT_READABLE\"' (masked to spaces) + newline + 'validation.errors.any { ... } -> \"MIME_UNKNOWN\"' + newline + 'else -> \"VALIDATION_FAILED\"'
# As ONE statement (no split at newlines because '->' ends each line). The statement: 'validation.errors.any { ... } -> \"MIME_UNKNOWN\" else -> \"VALIDATION_FAILED\"'.
# Head: u39 chained carrier 'validation.errors.any {' — method 'any' NOT in the production carrier set! PRODUCTION_TRANSPARENT_INLINE_METHODS includes let/also/apply/run/with/takeIf/takeUnless/runCatching/getOrElse/onFailure/use/repeat/forEach/... let me check if 'any' is in there. From the earlier read: let, also, apply, run, with, takeIf, takeUnless, runCatching, getOrElse, onFailure, use, repeat, forEach, forEachIndexed, map, mapNotNull, mapIndexed, ... need the full list.
"
(no output)
→Read build\worktrees\gr-14f\scripts\db_guard\mediation_analysis\callgraph.py [limit=80, offset=193]
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
import scripts.db_guard.structural_analysis.tokenizer as tk
# Simulate the when-body RHS statement (post-glue) that produces the L272 finding:
stmt = 'validation.errors.any { it.contains(           ) || it.contains(                ) } ->               ' + chr(10) + 'else ->                    '
methods = tk.__dict__  # not needed
from scripts.db_guard.mediation_analysis.callgraph import PRODUCTION_TRANSPARENT_INLINE_METHODS
m1 = tk._match_carrier_chain(stmt.strip(), PRODUCTION_TRANSPARENT_INLINE_METHODS)
print('chain match:', m1)
m2 = tk._match_chained_carrier(stmt.strip(), PRODUCTION_TRANSPARENT_INLINE_METHODS)
print('chained match:', m2)
"
chain match: <re.Match object; span=(0, 23), match='validation.errors.any {'>
chained match: <re.Match object; span=(0, 23), match='validation.errors.any {'>
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
import scripts.db_guard.structural_analysis.tokenizer as tk
from scripts.db_guard.mediation_analysis.callgraph import PRODUCTION_TRANSPARENT_INLINE_METHODS
from scripts.db_guard.structural_analysis.model import SourceSpan

# The glued when-RHS statement (branch2 condition + arrow + else branch):
stmt = 'validation.errors.any { it.contains(           ) || it.contains(                ) } ->               ' + chr(10) + 'else ->                    '
masked = stmt
res = tk.parse_callable_body(masked, SourceSpan(0, len(masked), 1, 1), transparent_inline_methods=PRODUCTION_TRANSPARENT_INLINE_METHODS)
print('supported:', res.is_supported)
for f in res.unsupported:
    print('  FINDING:', f.code, f.reason, f.span)
# With the u46 chain matcher matching (head 'validation.errors.any {'), _parse_carrier_chain scans segments:
# first segment: 'validation.errors.any {' close at the lambda's '}'. Then next segment regex '\\s*\\.\\s*name...' — the text after '}' is ' -> ... else -> ...' — no '.' segment. Break. Then tail check: tail = text after last region end = ' -> ... else -> ...' non-empty -> return None -> fallback (findings rolled back) -> generic path -> lambda-escape.
# So L272 stays refused under the new feature too. It informs u56b.
"
supported: False
  FINDING: DB_STRUCTURAL_MODEL_LAMBDA_ESCAPE lambda-escape SourceSpan(start=0, end=129, line=1, column=1)
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
# Reproduce the FULL processReceiptInput probe pre-change to enumerate the exact 9 findings.
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.mediation_analysis.callgraph import PRODUCTION_TRANSPARENT_INLINE_METHODS
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body
from scripts.db_guard.structural_analysis.model import SourceSpan
from scripts.db_guard.structural_analysis.shadow_report import _default_opacity_predicate
from scripts.db_guard.structural_analysis.barrier_proof import CANONICAL_BARRIER_CONTRACT_V2
import inspect
print(inspect.signature(parse_callable_body))
"
(masked_text: 'str', body_span: 'SourceSpan', *, lambda_opacity_predicate=None, transparent_scope_methods: 'tuple[str, ...]' = (), transparent_inline_methods: 'tuple[str, ...]' = ()) -> 'CallableBodyParse'
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, 'scripts')
from scripts.kotlin_callable_parser import mask_kotlin_source
from scripts.db_guard.mediation_analysis.callgraph import PRODUCTION_TRANSPARENT_INLINE_METHODS
from scripts.db_guard.structural_analysis.tokenizer import parse_callable_body
from scripts.db_guard.structural_analysis.model import SourceSpan, MutationSite
from scripts.db_guard.structural_analysis.shadow_report import _default_opacity_predicate
from scripts.db_guard.structural_analysis.barrier_proof import CANONICAL_BARRIER_CONTRACT_V2
from scripts.db_guard.scanner import scan_db_access
import inspect
print([n for n in dir() ])
" 2>&1 | Select-Object -First 5; Select-String -Path scripts\db_guard\scanner.py -Pattern "def scan_db_access|def main" | Select-Object LineNumber, Line
['CANONICAL_BARRIER_CONTRACT_V2', 'MutationSite', 'PRODUCTION_TRANSPARENT_INLINE_METHODS', 'SourceSpan', '__annotations__', '__builtins__', '__doc__', '__loader__', '__name__', '__package__', '__spec__', '_default_opacity_predicate', 'inspect', 'mask_kotlin_source', 'parse_callable_body', 'scan_db_access', 'sys']

LineNumber Line                                                                                                        
---------- ----                                                                                                        
      2682 def scan_db_access(source_root, ownership_policy=None, structural_policy=None, raw_query_policy=None, mut...
→Read build\worktrees\gr-14f\scripts\db_guard\scanner.py [limit=90, offset=2682]
# Running in build\worktrees\gr-14f
$ Select-String -Path scripts\ci\inspect_db_mediation_proof.py -Pattern "scan_db_access|mutation_observation_sink|prove_callable_direct_barriers|opacity_sites" | Select-Object LineNumber, Line -First 20
LineNumber Line                                                             
---------- ----                                                             
        53     prove_callable_direct_barriers,                              
        61 from scripts.db_guard.scanner import scan_db_access  # noqa: E402
       220         outcome = prove_callable_direct_barriers(                
       231             opacity_sites=tuple(real_sites),                     
       437         scan_result = scan_db_access(                            
       442             mutation_observation_sink=observations,
→Read build\worktrees\gr-14f\scripts\ci\inspect_db_mediation_proof.py [limit=180, offset=120]
→Read build\worktrees\gr-14f\scripts\ci\inspect_db_mediation_proof.py [limit=130, offset=300]
→Read build\worktrees\gr-14f\scripts\ci\inspect_db_mediation_proof.py [limit=120, offset=430]
→Read build\worktrees\gr-14f\scripts\ci\inspect_db_mediation_proof.py [limit=150, offset=550]
→Read build\worktrees\gr-14f\scripts\ci\inspect_db_mediation_proof.py [limit=120, offset=1]
# Running in build\worktrees\gr-14f
$ python -c "
import sys
sys.path.insert(0, '.')
from scripts.ci.inspect_db_mediation_proof import build_mediation_shadow
report, code = build_mediation_shadow('.', None)
print('exit code:', code)
print('proofStates:', report['summary']['proofStates'])
print('targetSha:', report.get('targetSha'))
"
(no output)

<shell_metadata>
shell tool terminated command after exceeding timeout 120000 ms. If this command is expected to take longer and is not waiting for interactive input, retry with a larger timeout value in milliseconds.
</shell_metadata>
# Running in build\worktrees\gr-14f
$ python -c "
import sys, time
sys.path.insert(0, '.')
t0 = time.time()
from scripts.ci.inspect_db_mediation_proof import build_mediation_shadow
report, code = build_mediation_shadow('.', None)
print('exit code:', code, 'elapsed:', round(time.time()-t0,1))
print('proofStates:', report['summary']['proofStates'])
"
exit code: 1 elapsed: 380.8
proofStates: {'proven_helper': 339, 'proven_restore_internal': 1, 'proven_worker_mediated': 14, 'unproven_ambiguous_call': 2, 'unproven_async_or_escaping_callback': 23}
# Running in build\worktrees\gr-14f
$ python -c "
import sys, json
sys.path.insert(0, '.')
from scripts.ci.inspect_db_mediation_proof import build_mediation_shadow
report, code = build_mediation_shadow('.', None, target_sha='pre-u56a-probe')
with open('build/guard-debug/gr14u56a/board-pre-run0.json', 'w', encoding='utf-8', newline=chr(10)) as f:
    json.dump(report, f, indent=2)
    f.write(chr(10))
print('written; proofStates:', report['summary']['proofStates'])
"
Traceback (most recent call last):
  File "<string>", line 6, in <module>
    with open('build/guard-debug/gr14u56a/board-pre-run0.json', 'w', encoding='utf-8', newline=chr(10)) as f:
         ~~~~^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
FileNotFoundError: [Errno 2] No such file or directory: 'build/guard-debug/gr14u56a/board-pre-run0.json'
# Running in build\worktrees\gr-14f
New-Item -ItemType Directory -Force -Path build\guard-debug\gr14u56a | Out-Null; python -c "
import sys, json
sys.path.insert(0, '.')
from scripts.ci.inspect_db_mediation_proof import build_mediation_shadow
report, code = build_mediation_shadow('.', None, target_sha='pre-u56a-probe')
with open('build/guard-debug/gr14u56a/board-pre-run0.json', 'w', encoding='utf-8', newline=chr(10)) as f:
    json.dump(report, f, indent=2)
    f.write(chr(10))
print('written; proofStates:', report['summary']['proofStates'])
"

# Running in build\worktrees\gr-14f
$ python -c "
import sys, time
sys.path.insert(0, '.')
t0 = time.time()
from scripts.ci.inspect_db_mediation_proof import build_mediation_shadow
report, code = build_mediation_shadow('.', None)
print('exit code:', code, 'elapsed:', round(time.time()-t0,1))
print('proofStates:', report['summary']['proofStates'])
"
exit code: 1 elapsed: 380.8
proofStates: {'proven_helper': 339, 'proven_restore_internal': 1, 'proven_worker_mediated': 14, 'unproven_ambiguous_call': 2, 'unproven_async_or_escaping_callback': 23}
# Running in build\worktrees\gr-14f
$ python -c "
import sys, json
sys.path.insert(0, '.')
from scripts.ci.inspect_db_mediation_proof import build_mediation_shadow
report, code = build_mediation_shadow('.', None, target_sha='pre-u56a-probe')
with open('build/guard-debug/gr14u56a/board-pre-run0.json', 'w', encoding='utf-8', newline=chr(10)) as f:
    json.dump(report, f, indent=2)
    f.write(chr(10))
print('written; proofStates:', report['summary']['proofStates'])
"
Traceback (most recent call last):
  File "<string>", line 6, in <module>
    with open('build/guard-debug/gr14u56a/board-pre-run0.json', 'w', encoding='utf-8', newline=chr(10)) as f:
         ~~~~^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^^
FileNotFoundError: [Errno 2] No such file or directory: 'build/guard-debug/gr14u56a/board-pre-run0.json'
# Running in build\worktrees\gr-14f
$ New-Item -ItemType Directory -Force -Path build\guard-debug\gr14u56a | Out-Null; python -c "
import sys, json
sys.path.insert(0, '.')
from scripts.ci.inspect_db_mediation_proof import build_mediation_shadow
report, code = build_mediation_shadow('.', None, target_sha='pre-u56a-probe')
with open('build/guard-debug/gr14u56a/board-pre-run0.json', 'w', encoding='utf-8', newline=chr(10)) as f:
    json.dump(report, f, indent=2)
    f.write(chr(10))
print('written; proofStates:', report['summary']['proofStates'])
"
written; proofStates: {'proven_helper': 339, 'proven_restore_internal': 1, 'proven_worker_mediated': 14, 'unproven_ambiguous_call': 2, 'unproven_async_or_escaping_callback': 23}
# Running in build\worktrees\gr-14f
$ python -c "
import hashlib
raw = open('build/guard-debug/gr14u56a/board-pre-run0.json','rb').read()
print('pre sha16:', hashlib.sha256(raw).hexdigest()[:16])
base = open('build/guard-debug/board-u55-verify2.json','rb').read()
print('baseline sha16:', hashlib.sha256(base).hexdigest()[:16])
print('identical to baseline:', raw == base)
"
pre sha16: fc89b1ba1faa1b75
baseline sha16: ec7fd24b1c9c45ea
identical to baseline: False
# Running in build\worktrees\gr-14f
$ python -c "
import json
a = json.load(open('build/guard-debug/board-u55-verify2.json'))
b = json.load(open('build/guard-debug/gr14u56a/board-pre-run0.json'))
for k in a:
    if a[k] != b.get(k):
        print('DIFF in key:', k)
        if k == 'summary':
            for sk in a[k]:
                if a[k][sk] != b[k].get(sk): print('  ', sk, a[k][sk], '->', b[k].get(sk))
        elif k in ('entries','unprovenInventory'):
            print('   len', len(a[k]), len(b[k]))
        else:
            print('   baseline:', str(a[k])[:200])
            print('   pre     :', str(b.get(k))[:200])
"
DIFF in key: targetSha
   baseline: None
   pre     : pre-u56a-probe
# Running in build\worktrees\gr-14f
python -c "
import sys, json
sys.path.insert(0, '.')
from scripts.ci.inspect_db_mediation_proof import build_mediation_shadow
# WITHOUT target-sha: should be byte-identical to baseline (board-u55-verify2.json)
report, code = build_mediation_shadow('.', None)
import hashlib
raw = json.dumps(report, indent=2) + chr(10)
print('recomputed sha16 (no target):', hashlib.sha256(raw.encode('utf-8')).hexdigest()[:16])
"