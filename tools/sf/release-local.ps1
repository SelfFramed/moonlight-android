<#
.SYNOPSIS
    Builds, signs, verifies and publishes a Moonlight E-Ink release from Martin's PC.

.DESCRIPTION
    The release key stays on this PC (C:\Users\mpp\.selfframed\keys, wired via
    sf-signing.properties). This script
      1. checks the repo (main, clean, not behind origin) and derives the version
         (<upstream versionName>-sf.<sf.revision>),
      2. runs the unit tests and builds the signed release APK at low priority,
      3. verifies version and signing certificate of the APK,
      4. tags v<version>, pushes main and the tag, and creates the GitHub release with the
         APK (as the SelfFramed account, without switching gh's active account).
    The app's built-in updater then offers the release to installed copies (not for
    pre-releases).

    Only with Martin's consent (docs/selfframed/RELEASE.md). Use -DryRun to stop after step 3.

.EXAMPLE
    powershell -File tools\sf\release-local.ps1 -DryRun
    powershell -File tools\sf\release-local.ps1 -Prerelease
#>
param(
    [switch]$DryRun,
    [switch]$Prerelease,
    [string]$NotesFile
)

# PowerShell 5.1 turns any stderr line of a native tool (git progress, Java warnings) into an error
# record; with 'Stop' that aborts. So: 'Continue', and every native call is checked via $LASTEXITCODE.
$ErrorActionPreference = 'Continue'
# Public certificate fingerprint of the SelfFramed release key (not a secret)
$ExpectedCertSha256 = 'c226922a62a12497f065d88e5f92a39f7743e4c851acf157e6e6405aa19c59df'
$Repo = 'SelfFramed/moonlight-android'

function Fail([string]$msg) { Write-Host "release-local: $msg" -ForegroundColor Red; exit 1 }
function Step([string]$msg) { Write-Host "== $msg" }

$root = (Resolve-Path (Join-Path $PSScriptRoot '..\..')).Path
Set-Location $root

# ---- 1. Repo state and version ----
Step 'Checking repository state'
$branch = (git rev-parse --abbrev-ref HEAD).Trim()
if ($branch -ne 'main') { Fail "on branch '$branch', expected main" }
if (git status --porcelain) { Fail 'working tree not clean' }
git fetch --quiet origin main 2>&1 | Out-Null
if ($LASTEXITCODE -ne 0) { Fail 'git fetch failed' }
$behind = [int](git rev-list --count HEAD..origin/main)
if ($behind -gt 0) { Fail "main is $behind commit(s) behind origin/main" }

$gradle = Get-Content 'app\build.gradle' -Raw
if ($gradle -notmatch 'versionName\s+"([^"]+)"') { Fail 'upstream versionName not found in app\build.gradle' }
$upstream = $Matches[1]
$props = Get-Content 'app\selfframed.properties' -Raw
if ($props -notmatch '(?m)^sf\.revision=(\d+)') { Fail 'sf.revision not found' }
$version = "$upstream-sf.$($Matches[1])"
$tag = "v$version"
Write-Host "   version $version, tag $tag"
if (git tag -l $tag) { Fail "tag $tag exists locally; bump sf.revision first (RELEASE.md)" }
$remoteTag = git ls-remote --tags origin "refs/tags/$tag" 2>$null
if ($LASTEXITCODE -ne 0) { Fail 'git ls-remote failed' }
if ($remoteTag) { Fail "tag $tag exists on origin" }
if (-not (Test-Path 'sf-signing.properties')) { Fail 'sf-signing.properties missing (release key not wired)' }

# ---- 2. Build ----
Step 'Unit tests and signed release build (low priority)'
& powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot 'build-lowprio.ps1') `
    -Tasks 'testNonRootDebugUnitTest assembleNonRootRelease' -Wait | Select-Object -Last 3
if ($LASTEXITCODE -ne 0) { Fail 'build failed (see build\sf-build.log)' }

# ---- 3. Verify ----
Step 'Verifying APK'
$sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
$bt = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory | Sort-Object { [version]($_.Name -replace '[^0-9.].*$', '') } | Select-Object -Last 1
$apk = 'app\build\outputs\apk\nonRoot\release\app-nonRoot-release.apk'
if (-not (Test-Path $apk)) { Fail "$apk missing" }
$badging = & (Join-Path $bt.FullName 'aapt2.exe') dump badging $apk 2>$null | Select-Object -First 1
if ($badging -notmatch "name='com\.selfframed\.moonlight' versionCode='(\d+)' versionName='([^']+)'") { Fail "unexpected package: $badging" }
if ($Matches[2] -ne $version) { Fail "APK versionName $($Matches[2]) != $version" }
# apksigner.bat picks Java from JAVA_HOME; the Android Studio JBR avoids warnings of newer JDKs
$jbr = 'C:\Program Files\Android\Android Studio\jbr'
if (Test-Path $jbr) { $env:JAVA_HOME = $jbr }
$certs = & (Join-Path $bt.FullName 'apksigner.bat') verify --print-certs $apk 2>$null
if ($LASTEXITCODE -ne 0) { Fail "apksigner verify failed:`n$certs" }
$certLine = $certs | Select-String -Pattern 'Signer #1 certificate SHA-256 digest: ([0-9a-f]+)' | Select-Object -First 1
if (-not $certLine) { Fail "apksigner could not verify the APK:`n$certs" }
if ($certLine.Matches[0].Groups[1].Value -ne $ExpectedCertSha256) { Fail 'APK is not signed with the SelfFramed release key' }

$outDir = Join-Path $root 'build\sf-release'
New-Item -ItemType Directory -Force $outDir -ErrorAction Stop | Out-Null
$asset = Join-Path $outDir "moonlight-eink-$version-nonRoot-release.apk"
Copy-Item $apk $asset -Force -ErrorAction Stop
$sha = (Get-FileHash $asset -Algorithm SHA256).Hash.ToLowerInvariant()
Write-Host "   $asset"
Write-Host "   SHA-256 $sha, signed with the SelfFramed key"

# ---- Release notes ----
$notes = Join-Path $outDir "notes-$version.md"
if ($NotesFile) {
    Copy-Item $NotesFile $notes -Force
} else {
    $changelog = Get-Content 'docs\selfframed\CHANGELOG.md' -Raw -Encoding UTF8
    $section = ''
    $m = [regex]::Match($changelog, "(?ms)^## $([regex]::Escape($version))\b.*?\r?\n(.*?)(?=^## |\z)")
    if ($m.Success) { $section = $m.Groups[1].Value.Trim() }
    # Single-quoted pieces: no PowerShell escaping inside the Markdown (backticks!)
    $tick = [char]0x60
    $lines = @(
        "Moonlight E-Ink ${version}: SelfFramed-Fork von [Moonlight Android](https://github.com/moonlight-stream/moonlight-android) (Upstream $upstream) mit GPU-Bildstufe für E-Ink: Gradationskurven, Presets, Tastenkürzel, Kurveneditor im Stream.",
        '',
        $section,
        '',
        ('**Installieren:** ' + $tick + "moonlight-eink-$version-nonRoot-release.apk" + $tick + ' auf dem Telefon öffnen. Die App heißt „Moonlight E-Ink“ und läuft neben dem offiziellen Moonlight; einmalig per PIN mit Sunshine koppeln. Falls Google Play Protect nachfragt: „Weitere Details“ → „Ohne Scan installieren“ (oder scannen lassen).'),
        '**Updates:** kommen ab jetzt in der App (Einstellungen → Updates).',
        "**Doku:** [Bedienung](https://github.com/$Repo/blob/$tag/docs/selfframed/BEDIENUNG.md) · [Testprotokoll](https://github.com/$Repo/blob/$tag/docs/selfframed/TESTPROTOKOLL.md)",
        '',
        ('SHA-256: ' + $tick + $sha + $tick + ' · Signatur-Zertifikat SHA-256: ' + $tick + $ExpectedCertSha256 + $tick + ' · Lizenz GPLv3 wie Upstream.')
    )
    [System.IO.File]::WriteAllText($notes, ($lines -join "`n") + "`n", (New-Object System.Text.UTF8Encoding($false)))
}

if ($DryRun) {
    Step "Dry run finished. Notes: $notes"
    exit 0
}

# ---- 4. Tag, push, publish ----
Step "Tagging and pushing $tag"
git tag -a $tag -m "Moonlight E-Ink $version"
if ($LASTEXITCODE -ne 0) { Fail 'git tag failed' }
git push origin main 2>&1 | ForEach-Object { "   $_" }
if ($LASTEXITCODE -ne 0) { Fail 'push of main failed' }
git push origin $tag 2>&1 | ForEach-Object { "   $_" }
if ($LASTEXITCODE -ne 0) { Fail 'push of the tag failed' }

Step 'Creating the GitHub release (as SelfFramed)'
$token = (gh auth token --user SelfFramed 2>$null)
if (-not $token) { Fail 'gh has no SelfFramed account (docs/selfframed/UPSTREAM_SYNC.md, section 3)' }
$env:GH_TOKEN = $token.Trim()
try {
    $ghArgs = @('release', 'create', $tag, $asset, '--repo', $Repo, '--title', "Moonlight E-Ink $tag", '--notes-file', $notes, '--verify-tag')
    if ($Prerelease) { $ghArgs += '--prerelease' }
    & gh @ghArgs
    if ($LASTEXITCODE -ne 0) { Fail 'gh release create failed' }
} finally {
    Remove-Item Env:GH_TOKEN -ErrorAction SilentlyContinue
}
Step "Published: https://github.com/$Repo/releases/tag/$tag"
