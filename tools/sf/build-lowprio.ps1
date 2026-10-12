<#
.SYNOPSIS
    Low-priority Gradle build for the SelfFramed Moonlight fork.

.DESCRIPTION
    Martin's PC is also his Sunshine streaming host, so builds must not compete with the stream.
    This script writes a temporary .cmd file (CRLF) that waits about 3 seconds, sets JAVA_HOME and
    ANDROID_HOME, changes to the repository and runs gradlew.bat --no-daemon --priority=low with a
    limited number of workers. The cmd.exe process is started minimized and lowered to BelowNormal
    during the initial wait, so Gradle and every child process (javac, R8, ndk-build, clang)
    inherit BelowNormal. Output goes to build\sf-build.log in the repository.

    JAVA_HOME: kept if it points to Java 17 to 21, otherwise Android Studio's JBR is used.
    ANDROID_HOME: kept if set, otherwise %LOCALAPPDATA%\Android\Sdk.

    See docs/selfframed/BUILD.md.

.PARAMETER Tasks
    Gradle tasks (and simple options), separated by spaces.
    Default: "testNonRootDebugUnitTest assembleNonRootDebug".

.PARAMETER Workers
    Maximum number of Gradle workers (org.gradle.workers.max). Default: 4.

.PARAMETER Wait
    Wait for the build, print the end of the log and exit with Gradle's exit code.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File C:\GITHUB\moonlight-android\tools\sf\build-lowprio.ps1 -Wait

.EXAMPLE
    .\tools\sf\build-lowprio.ps1 -Tasks "assembleNonRootRelease" -Workers 2
#>
[CmdletBinding()]
param(
    [string]$Tasks = 'testNonRootDebugUnitTest assembleNonRootDebug',
    [ValidateRange(1, 64)]
    [int]$Workers = 4,
    [switch]$Wait
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'

# --- repository ------------------------------------------------------------------------------
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$gradlew = Join-Path $repoRoot 'gradlew.bat'
if (-not (Test-Path -LiteralPath $gradlew)) {
    throw "gradlew.bat not found in $repoRoot"
}

# --- tasks -----------------------------------------------------------------------------------
# The tasks are written verbatim into the .cmd file, so cmd.exe metacharacters are rejected.
if ($Tasks -match '[&|<>^%!"()\r\n]') {
    throw "Tasks contain characters that are not allowed here (& | < > ^ % ! "" ( )): $Tasks"
}
$Tasks = (($Tasks -split '\s+') | Where-Object { $_ }) -join ' '
if (-not $Tasks) {
    throw 'No Gradle tasks given.'
}

# --- Java ------------------------------------------------------------------------------------
function Get-JavaMajor([string]$javaHome) {
    if (-not $javaHome) { return 0 }
    if (-not (Test-Path -LiteralPath (Join-Path $javaHome 'bin\java.exe'))) { return 0 }
    $releaseFile = Join-Path $javaHome 'release'
    if (-not (Test-Path -LiteralPath $releaseFile)) { return 0 }
    $line = Get-Content -LiteralPath $releaseFile | Where-Object { $_ -match '^JAVA_VERSION=' } | Select-Object -First 1
    if ($line -and $line -match '^JAVA_VERSION="?(\d+)(?:\.(\d+))?') {
        $major = [int]$Matches[1]
        if ($major -eq 1 -and $Matches[2]) { $major = [int]$Matches[2] }   # 1.8 -> 8
        return $major
    }
    return 0
}

$javaHome = $env:JAVA_HOME
$javaMajor = Get-JavaMajor $javaHome
if ($javaMajor -lt 17 -or $javaMajor -gt 21) {
    $programFiles = $env:ProgramW6432
    if (-not $programFiles) { $programFiles = $env:ProgramFiles }
    $jbr = Join-Path $programFiles 'Android\Android Studio\jbr'
    $jbrMajor = Get-JavaMajor $jbr
    if ($jbrMajor -lt 17) {
        throw "No usable JDK: JAVA_HOME='$javaHome' (Java $javaMajor) and no Android Studio JBR at '$jbr'. Set JAVA_HOME to a JDK 17-21."
    }
    if ($javaHome) {
        Write-Host "JAVA_HOME '$javaHome' is Java $javaMajor; using Android Studio JBR instead."
    }
    $javaHome = $jbr
    $javaMajor = $jbrMajor
}

# --- Android SDK -----------------------------------------------------------------------------
$androidHome = $env:ANDROID_HOME
if (-not $androidHome) {
    $androidHome = Join-Path $env:LOCALAPPDATA 'Android\Sdk'
}
if (-not (Test-Path -LiteralPath $androidHome)) {
    Write-Warning "Android SDK not found at '$androidHome' (local.properties sdk.dir may still work)."
}

# --- log, pid file, guard against parallel builds --------------------------------------------
$buildDir = Join-Path $repoRoot 'build'
New-Item -ItemType Directory -Force -Path $buildDir | Out-Null
$logFile = Join-Path $buildDir 'sf-build.log'
$pidFile = Join-Path $buildDir 'sf-build.pid'

if (Test-Path -LiteralPath $pidFile) {
    $oldPid = 0
    [void][int]::TryParse(((Get-Content -LiteralPath $pidFile -TotalCount 1) -as [string]), [ref]$oldPid)
    if ($oldPid -gt 0) {
        $old = Get-Process -Id $oldPid -ErrorAction SilentlyContinue
        $sameProcess = $false
        if ($old -and $old.ProcessName -eq 'cmd') {
            # Guard against PID reuse: our cmd.exe started before the pid file was written.
            try {
                $sameProcess = $old.StartTime -le (Get-Item -LiteralPath $pidFile).LastWriteTime.AddSeconds(5)
            } catch {
                $sameProcess = $true
            }
        }
        if ($sameProcess) {
            throw "A build started by this script is still running (cmd.exe PID $oldPid, log: $logFile). Wait for it, or stop it with: taskkill /T /F /PID $oldPid"
        }
    }
}

# --- .cmd file -------------------------------------------------------------------------------
# Remove stale launchers from earlier runs (only our own naming pattern).
Get-ChildItem -LiteralPath $env:TEMP -Filter 'sf-build-*.cmd' -ErrorAction SilentlyContinue |
    Where-Object { $_.LastWriteTime -lt (Get-Date).AddDays(-1) } |
    Remove-Item -Force -ErrorAction SilentlyContinue

function ConvertTo-CmdLiteral([string]$s) { return $s.Replace('%', '%%') }

$cmdPath = Join-Path $env:TEMP ('sf-build-{0:yyyyMMdd-HHmmss}-{1}.cmd' -f (Get-Date), $PID)
$q = '"'
$cmdLines = @(
    '@echo off',
    'rem Generated by tools\sf\build-lowprio.ps1. Safe to delete.',
    'rem Wait ~3 s so the launcher can set BelowNormal before Gradle starts; children inherit it.',
    'ping -n 4 127.0.0.1 >nul',
    ('set ' + $q + 'JAVA_HOME=' + (ConvertTo-CmdLiteral $javaHome) + $q),
    ('set ' + $q + 'ANDROID_HOME=' + (ConvertTo-CmdLiteral $androidHome) + $q),
    ('cd /d ' + $q + (ConvertTo-CmdLiteral $repoRoot) + $q),
    ('echo [sf-build] start %DATE% %TIME% >' + $q + (ConvertTo-CmdLiteral $logFile) + $q),
    ('echo [sf-build] tasks: ' + $Tasks + ' / workers: ' + $Workers + ' / JAVA_HOME: %JAVA_HOME% >>' + $q + (ConvertTo-CmdLiteral $logFile) + $q),
    # Absolute path: cmd.exe skips the current directory if NoDefaultCurrentDirectoryInExePath is set.
    ('call ' + $q + (ConvertTo-CmdLiteral $gradlew) + $q + ' --no-daemon --priority=low -Dorg.gradle.workers.max=' + $Workers + ' ' + $Tasks + ' >>' + $q + (ConvertTo-CmdLiteral $logFile) + $q + ' 2>&1'),
    'set "SF_RC=%ERRORLEVEL%"',
    ('echo [sf-build] end %DATE% %TIME% exit code %SF_RC% >>' + $q + (ConvertTo-CmdLiteral $logFile) + $q),
    'exit /b %SF_RC%'
)
# cmd.exe reads batch files in the console's OEM code page.
$oemEncoding = [System.Text.Encoding]::GetEncoding([System.Globalization.CultureInfo]::CurrentCulture.TextInfo.OEMCodePage)
[System.IO.File]::WriteAllText($cmdPath, (($cmdLines -join "`r`n") + "`r`n"), $oemEncoding)

# --- launch ----------------------------------------------------------------------------------
# /s /c ""<path>"": cmd strips only the outer quotes, so paths with spaces work.
$proc = Start-Process -FilePath $env:ComSpec -ArgumentList '/d', '/s', '/c', ('""' + $cmdPath + '""') `
    -WindowStyle Minimized -PassThru
$null = $proc.Handle   # keep a handle so ExitCode is available after the process ends
try {
    $proc.PriorityClass = [System.Diagnostics.ProcessPriorityClass]::BelowNormal
} catch {
    & taskkill.exe /T /F /PID $proc.Id | Out-Null
    throw "Could not lower the build priority; build stopped. ($($_.Exception.Message))"
}
Set-Content -LiteralPath $pidFile -Value $proc.Id -Encoding ASCII

Write-Host "Build started at BelowNormal priority (cmd.exe PID $($proc.Id))."
Write-Host "  Repo:      $repoRoot"
Write-Host "  Tasks:     $Tasks  (workers: $Workers)"
Write-Host "  JAVA_HOME: $javaHome (Java $javaMajor)"
Write-Host "  Log:       $logFile"

if (-not $Wait) {
    Write-Host "Follow the log: Get-Content -LiteralPath '$logFile' -Wait -Tail 30"
    Write-Host "Stop the build: taskkill /T /F /PID $($proc.Id)"
    return
}

$proc.WaitForExit()
$exitCode = $proc.ExitCode
Remove-Item -LiteralPath $cmdPath -Force -ErrorAction SilentlyContinue
Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
Write-Host ''
Write-Host "----- last 40 lines of $logFile -----"
if (Test-Path -LiteralPath $logFile) {
    Get-Content -LiteralPath $logFile -Tail 40 | ForEach-Object { Write-Host $_ }
}
Write-Host '-------------------------------------'
if ($exitCode -eq 0) {
    Write-Host 'Build finished: exit code 0.'
} else {
    Write-Host "Build FAILED: exit code $exitCode." -ForegroundColor Red
}
exit $exitCode
