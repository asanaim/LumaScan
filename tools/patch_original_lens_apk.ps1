# Automated Windows PowerShell Script to Patch Original Microsoft Lens APK into LumaScan
param (
    [string]$InputApk = "com.microsoft.office.officelens.apk"
)

$ErrorActionPreference = "Stop"
Write-Host "=== LumaScan Automated APKTool Patcher ===" -ForegroundColor Cyan

if (!(Test-Path $InputApk)) {
    Write-Error "Input APK '$InputApk' not found in current directory. Place the standalone arm64-v8a APK here and re-run."
}

Write-Host "[1/5] Decompiling APK with apktool..."
apktool d $InputApk -o lumascan_decompiled -f

$manifestPath = "lumascan_decompiled/AndroidManifest.xml"
Write-Host "[2/5] Stripping INTERNET permissions & renaming label to LumaScan..."
$manifest = Get-Content $manifestPath -Raw
$manifest = $manifest -replace '<uses-permission android:name="android.permission.INTERNET"\s*/>', ''
$manifest = $manifest -replace '<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE"\s*/>', ''
$manifest = $manifest -replace 'android:label="@string/[^"]+"', 'android:label="LumaScan"'
Set-Content -Path $manifestPath -Value $manifest -Encoding UTF8

Write-Host "[3/5] Injecting Apple-inspired LumaScan adaptive launcher icon..."
Copy-Item "../app/src/main/res/drawable/ic_launcher_background.xml" "lumascan_decompiled/res/drawable/ic_launcher_background.xml" -Force
Copy-Item "../app/src/main/res/drawable/ic_launcher_foreground.xml" "lumascan_decompiled/res/drawable/ic_launcher_foreground.xml" -Force

$ymlPath = "lumascan_decompiled/apktool.yml"
Write-Host "[4/5] Detaching package from Play Store auto-updates (versionCode 999999999)..."
$yml = Get-Content $ymlPath -Raw
$yml = $yml -replace 'renameManifestPackage:\s*null', 'renameManifestPackage: com.lumascan.offline'
$yml = $yml -replace "versionCode:\s*'?\d+'?", "versionCode: '999999999'"
Set-Content -Path $ymlPath -Value $yml -Encoding UTF8

Write-Host "[5/5] Rebuilding & signing LumaScan.apk..."
apktool b lumascan_decompiled -o LumaScan_unsigned.apk
java -jar uber-apk-signer.jar --apks LumaScan_unsigned.apk

Write-Host "Done! Install LumaScan_unsigned-aligned-debugSigned.apk on your Android phone." -ForegroundColor Green
