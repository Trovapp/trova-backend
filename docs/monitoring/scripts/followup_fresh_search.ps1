param([Parameter(Mandatory=$true)][string]$Query)
$ErrorActionPreference='Stop'
$adb=Join-Path $env:LOCALAPPDATA 'Android/Sdk/platform-tools/adb.exe'
python (Join-Path $PSScriptRoot 'followup_ui.py') 5586 tap 400 1360
& $adb -s emulator-5586 shell input keycombination 113 29
Set-Clipboard -Value $Query
& $adb -s emulator-5586 shell input keyevent 279
Start-Sleep -Seconds 1
& $adb -s emulator-5586 shell input keyevent 66
python (Join-Path $PSScriptRoot 'followup_ui.py') 5586 mark fresh-search $Query
Start-Sleep -Seconds 3
python (Join-Path $PSScriptRoot 'followup_ui.py') 5586 shot
