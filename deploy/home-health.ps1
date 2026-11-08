# Pings Healthchecks.io only while the home stack is healthy (database ok AND engine running).
# If the PC, Docker, the backend, or the engine stops, the pings stop and Healthchecks.io alerts you.
# Scheduled every 5 minutes by the task in deploy/HOME.md, step 6.
param([Parameter(Mandatory)][string]$PingUrl)

try {
    $h = Invoke-RestMethod -Uri 'http://127.0.0.1:8088/api/health' -TimeoutSec 10
    if ($h.database -eq 'ok' -and $h.engine -eq $true) {
        Invoke-RestMethod -Uri $PingUrl -TimeoutSec 10 | Out-Null
    } else {
        Invoke-RestMethod -Uri "$PingUrl/fail" -Method Post -Body "database=$($h.database) engine=$($h.engine)" -TimeoutSec 10 | Out-Null
    }
} catch {
    try { Invoke-RestMethod -Uri "$PingUrl/fail" -Method Post -Body $_.Exception.Message -TimeoutSec 10 | Out-Null } catch {}
}
