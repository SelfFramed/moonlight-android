# Reads the Sunshine log while Sunshine runs (read-only).
# Explorer and Get-ChildItem show the open log as 0 bytes; it is not empty. This opens it with
# FileShare.ReadWrite and prints the lines about sessions, audio and video plus a count of all
# warnings and errors (numbers folded, so repeats group together).
# Usage: powershell -ExecutionPolicy Bypass -File tools\sf\diag\sunshine-log.ps1 [-Last 60] [-Path <sunshine.log>]
param(
    [int]$Last = 60,
    [string]$Path = 'C:\Program Files\Sunshine\config\sunshine.log'
)

$fs = [System.IO.File]::Open($Path, 'Open', 'Read', 'ReadWrite')
try {
    $reader = New-Object System.IO.StreamReader($fs)
    $lines = $reader.ReadToEnd() -split "`r?`n"
} finally {
    $fs.Close()
}

"log: $Path ($($lines.Count) lines)"
"--- sessions, audio, video (last $Last)"
$lines | Where-Object { $_ -match 'CLIENT (CONNECTED|DISCONNECTED)|New streaming session|[Aa]udio|[Oo]pus|[Ss]ink|Capture size|Desktop resolution|[Ee]ncoder' } |
    Select-Object -Last $Last
"--- warnings and errors (count, numbers folded)"
$lines | Where-Object { $_ -match '\]: (Warning|Error|Fatal):' } |
    ForEach-Object { ($_ -replace '^\[[^\]]+\]: ', '') -replace '\d+', 'N' } |
    Group-Object | Sort-Object Count -Descending | Select-Object -First 15 Count, Name |
    Format-Table -AutoSize -Wrap | Out-String -Width 200
