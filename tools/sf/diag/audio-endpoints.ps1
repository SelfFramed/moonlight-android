# Lists the active Windows playback devices with their shared-mode format (read-only).
# Which one is the default (the one Sunshine captures): see audio-loopback-probe.ps1.
# Usage: powershell -ExecutionPolicy Bypass -File tools\sf\diag\audio-endpoints.ps1
$root = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Render'
$states = @{ 1 = 'active'; 2 = 'disabled'; 4 = 'notpresent'; 8 = 'unplugged' }

function Read-Format($bytes) {
    if (-not $bytes -or $bytes.Length -lt 16) { return '-' }
    # The property blob starts with an 8-byte PROPVARIANT-style header before the WAVEFORMATEX
    $o = 8
    $tag = [BitConverter]::ToUInt16($bytes, $o)
    $ch = [BitConverter]::ToUInt16($bytes, $o + 2)
    $rate = [BitConverter]::ToUInt32($bytes, $o + 4)
    $bits = [BitConverter]::ToUInt16($bytes, $o + 14)
    if ($tag -eq 0xFFFE -and $bytes.Length -ge $o + 24) {
        $valid = [BitConverter]::ToUInt16($bytes, $o + 18)
        return ('{0} Hz, {1} ch, {2} bit (valid {3})' -f $rate, $ch, $bits, $valid)
    }
    return ('{0} Hz, {1} ch, {2} bit' -f $rate, $ch, $bits)
}

$rows = foreach ($k in Get-ChildItem $root) {
    $state = (Get-ItemProperty $k.PSPath).DeviceState
    $p = Get-ItemProperty -Path (Join-Path $k.PSPath 'Properties') -ErrorAction SilentlyContinue
    $desc = $p.'{a45c254e-df1c-4efd-8020-67d146a850e0},2'
    $iface = $p.'{b3f8fa53-0004-438e-9003-51a46e139bfc},6'
    [pscustomobject]@{
        Name    = "$desc ($iface)"
        State   = $states[[int]($state -band 0xF)]
        Format  = Read-Format $p.'{f19f064d-082c-4e27-bc73-6882a1bb8e4c},0'
    }
}
$rows | Where-Object State -eq 'active' | ForEach-Object { '{0,-60} {1}' -f $_.Name, $_.Format }
