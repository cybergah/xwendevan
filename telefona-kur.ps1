# Xwendevan - APK'yi telefona kurar.
# Ses modelleri APK'nin icinde geldigi icin ayrica kopyalanacak bir sey yok.
# Kullanim:  powershell -ExecutionPolicy Bypass -File telefona-kur.ps1
# Telefonda "USB hata ayiklama" acik olmali.

$ErrorActionPreference = "Stop"

$adb = Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = "adb" }

$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$apk  = Join-Path $here "apk\Xwendevan-1.5-arm64-v8a.apk"

Write-Host "== Bagli cihazlar =="
& $adb devices

if (-not (Test-Path $apk)) { throw "APK bulunamadi: $apk" }

$mb = [math]::Round((Get-Item $apk).Length / 1MB)
Write-Host "`n== APK kuruluyor ($mb MB, ses modelleri dahil) =="
& $adb install -r $apk

Write-Host "`nBitti. Uygulamayi acin; Turkce ve Kurmanci sesler hazir gelir."
