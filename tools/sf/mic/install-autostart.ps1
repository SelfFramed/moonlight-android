# Starts the microphone receiver at logon (shortcut in the user's Startup folder; no admin rights needed).
# Usage: powershell -ExecutionPolicy Bypass -File tools\sf\mic\install-autostart.ps1 [-StartNow] [-Remove]
#   -StartNow  also start it now (if it is not running yet)
#   -Remove    remove the shortcut and stop a running receiver
# Uses pythonw.exe (no window). Python needs the packages sounddevice and cryptography (pip install --user ...).
param([switch]$StartNow, [switch]$Remove, [string]$Python = 'C:\Python313\pythonw.exe')

$ErrorActionPreference = 'Stop'
$receiver = Join-Path $PSScriptRoot 'receiver.py'
$startup = [Environment]::GetFolderPath('Startup')
$link = Join-Path $startup 'SelfFramed Mikrofon.lnk'

function Get-Receivers {
    Get-CimInstance Win32_Process -Filter "name like 'python%'" | Where-Object { $_.CommandLine -match 'receiver\.py' }
}

if ($Remove) {
    if (Test-Path $link) { Remove-Item $link; "removed $link" } else { "no shortcut" }
    Get-Receivers | ForEach-Object { Stop-Process -Id $_.ProcessId -Confirm:$false; "stopped receiver $($_.ProcessId)" }
    return
}

if (-not (Test-Path $Python)) { throw "Python not found: $Python" }
$shell = New-Object -ComObject WScript.Shell
$shortcut = $shell.CreateShortcut($link)
$shortcut.TargetPath = $Python
$shortcut.Arguments = '"' + $receiver + '"'
$shortcut.WorkingDirectory = $PSScriptRoot
$shortcut.Description = 'SelfFramed: Mikrofon vom Telefon ins virtuelle Mikrofon (Moonlight E-Ink)'
$shortcut.WindowStyle = 7
$shortcut.Save()
"autostart: $link"

if ($StartNow) {
    if (Get-Receivers) {
        'receiver already running'
    } else {
        Start-Process -FilePath $Python -ArgumentList ('"' + $receiver + '"') -WorkingDirectory $PSScriptRoot
        Start-Sleep -Seconds 2
        if (Get-Receivers) { 'receiver started' } else { 'receiver did not start, see %USERPROFILE%\.selfframed\mic\receiver.log' }
    }
}
