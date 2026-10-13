# Round-trip time and jitter to a device on the network (e.g. the phone while it streams).
# Small pings at a fixed interval; harmless for a running stream (300 bytes every 50 ms).
# Usage: powershell -ExecutionPolicy Bypass -File tools\sf\diag\net-jitter.ps1 -Target <ip> [-Count 200] [-IntervalMs 50]
# Finding the phone: Get-NetNeighbor -AddressFamily IPv4 -State Reachable (Android uses a random MAC,
# second hex digit 2, 6, A or E). Measured 12.10.2026 over Wi-Fi: median 2.7 ms, p99 5.3 ms.
param(
    [Parameter(Mandatory = $true)][string]$Target,
    [int]$Count = 200,
    [int]$IntervalMs = 50
)

$ping = New-Object System.Net.NetworkInformation.Ping
$payload = New-Object byte[] 300
$rtts = New-Object System.Collections.Generic.List[double]
$lost = 0
$sw = [Diagnostics.Stopwatch]::StartNew()
for ($i = 0; $i -lt $Count; $i++) {
    $t0 = $sw.Elapsed.TotalMilliseconds
    try { $reply = $ping.Send($Target, 500, $payload) } catch { $reply = $null }
    $t1 = $sw.Elapsed.TotalMilliseconds
    if ($reply -and $reply.Status -eq 'Success') { $rtts.Add($t1 - $t0) } else { $lost++ }
    $wait = $IntervalMs - ($sw.Elapsed.TotalMilliseconds - $t0)
    if ($wait -gt 0) { Start-Sleep -Milliseconds ([int]$wait) }
}

$s = $rtts.ToArray()
[Array]::Sort($s)
function Pct($p) { $s[[Math]::Min($s.Count - 1, [int][Math]::Round($p * ($s.Count - 1)))] }
"{0}: {1} replies, {2} lost" -f $Target, $s.Count, $lost
if ($s.Count) {
    "RTT ms: min {0:0.0} / median {1:0.0} / p90 {2:0.0} / p99 {3:0.0} / max {4:0.0}" -f (Pct 0), (Pct 0.5), (Pct 0.9), (Pct 0.99), (Pct 1)
    "over 10 ms: {0}, over 20 ms: {1}, over 40 ms: {2}, over 100 ms: {3}" -f @($s | Where-Object { $_ -gt 10 }).Count,
        @($s | Where-Object { $_ -gt 20 }).Count, @($s | Where-Object { $_ -gt 40 }).Count, @($s | Where-Object { $_ -gt 100 }).Count
}
