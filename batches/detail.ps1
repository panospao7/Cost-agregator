param([string]$Classes)
$dir = "app\build\test-results\testDebugUnitTest"
foreach($c in ($Classes -split ',')){
  $file = Get-ChildItem -LiteralPath $dir -Filter "*$c.xml" -File | Select-Object -First 1
  if(-not $file){ "NO XML for $c"; continue }
  [xml]$d = Get-Content -LiteralPath $file.FullName -Raw
  $shown = 0
  foreach($tc in $d.testsuite.testcase){
    if(($tc.failure -or $tc.error) -and $shown -lt 2){
      $node = if($tc.failure){$tc.failure}else{$tc.error}
      "######## $c :: $($tc.name)"
      $txt = $node.InnerText
      $lines = ($txt -split "`r?`n") | Select-Object -First 16
      $lines -join "`n"
      ""
      $shown++
    }
  }
}
