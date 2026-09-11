#Requires -Version 5.1
param(
    [string] $Note = '',
    # Time-boxed key: days counted from first activation. 0 = perpetual.
    [int] $Days = 0,
    [string] $Api = $env:ROATZ_LICENSE_API,
    [string] $AdminSecret = $env:ROATZ_ADMIN_SECRET
)

$ErrorActionPreference = 'Stop'
if (-not $Api) { $Api = 'http://127.0.0.1:8787' }
if (-not $AdminSecret) {
    Write-Host 'Set ROATZ_ADMIN_SECRET (same as the Worker ADMIN_SECRET).' -ForegroundColor Red
    exit 1
}

$body = @{ note = $Note; days = $Days } | ConvertTo-Json
$resp = Invoke-RestMethod -Method Post -Uri ($Api.TrimEnd('/') + '/v1/issue') -Headers @{
    'X-Roatz-Admin' = $AdminSecret
    'Content-Type'  = 'application/json'
} -Body $body
if (-not $resp.ok) {
    Write-Host ("Issue failed: " + $resp.error) -ForegroundColor Red
    exit 1
}
if ($Days -gt 0) {
    Write-Host ("$Days-day key (starts on first activation)") -ForegroundColor DarkGray
} else {
    Write-Host 'Perpetual key' -ForegroundColor DarkGray
}
Write-Host $resp.key
