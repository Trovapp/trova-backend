$ErrorActionPreference = 'Stop'
$root = Join-Path $PSScriptRoot '../2026-11-25-v11-recheck-followup'
$ocr = [scriptblock]::Create((Get-Content -Raw -Encoding utf8 (Join-Path $PSScriptRoot 'followup_ocr.ps1')))
$result = @{}
Get-ChildItem (Join-Path $root 'observations') -Recurse -Filter '*.png' | Where-Object { $_.Name -match '^(after-\d+s|reconnect-20s|reconnect-60s|retry)-' } | ForEach-Object {
    $key = $_.Directory.Name + '/' + $_.Name
    $result[$key] = @((& $ocr -ImagePath $_.FullName | ConvertFrom-Json))
}
$result | ConvertTo-Json -Depth 8 | Set-Content -Encoding utf8 (Join-Path $root 'screen-text.json')
Write-Output ('OCR files: ' + $result.Count)
