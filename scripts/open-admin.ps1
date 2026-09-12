#Requires -Version 5.1
# Opens the Roatz license admin dashboard in your browser.
# Always run from anywhere — this script finds the repo if needed.

$AdminUrl = if ($env:ROATZ_LICENSE_API) {
    $env:ROATZ_LICENSE_API.TrimEnd('/') + '/admin'
} else {
    'https://roatz-license.alec-5c7.workers.dev/admin'
}

Write-Host "Opening $AdminUrl"
Write-Host "Paste your ADMIN_SECRET in the page (password manager)."
Start-Process $AdminUrl
