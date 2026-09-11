#Requires -Version 5.1
param(
    [string] $Note = '',
    [string] $Api = $env:ROATZ_LICENSE_API,
    [string] $AdminSecret = $env:ROATZ_ADMIN_SECRET
)

$ErrorActionPreference = 'Stop'
if (-not $Api) { $Api = 'http://127.0.0.1:8787' }
if (-not $AdminSecret) {
    Write-Host 'Set ROATZ_ADMIN_SECRET (same as the Worker ADMIN_SECRET).' -ForegroundColor Red
    exit 1
}

$body = @{ note = $Note } | ConvertTo-Json
$resp = Invoke-RestMethod -Method Post -Uri ($Api.TrimEnd('/') + '/v1/issue') -Headers @{
    'X-Roatz-Admin' = $AdminSecret
    'Content-Type'  = 'application/json'
} -Body $body
if (-not $resp.ok) {
    Write-Host ("Issue failed: " + $resp.error) -ForegroundColor Red
    exit 1
}
Write-Host $resp.key
