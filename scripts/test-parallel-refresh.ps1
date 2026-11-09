<#
.SYNOPSIS
    Fires 10 concurrent refresh token requests against a running local backend
    to verify atomic token rotation and the concurrent refresh grace window (Issue D).

.DESCRIPTION
    Expected behavior per Issue D:
    - Exactly 1 request succeeds with HTTP 200 OK and receives a new token pair.
    - The remaining 9 concurrent requests within the 10-second grace window receive
      HTTP 409 Conflict (error code: REFRESH_IN_PROGRESS).
    - User sessions are preserved (no 401 token reuse cascade revocation).

.PARAMETER BaseUrl
    The base API URL. Default: http://localhost:8080/api/v1

.PARAMETER Email
    Optional test user email. If omitted, registers a unique temporary test account.

.PARAMETER Password
    Test user password. Default: TestPass123!
#>

[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8080/api/v1",
    [string]$Email = "",
    [string]$Password = "TestPass123!"
)

$ErrorActionPreference = "Continue"

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " WP01 Concurrent Refresh Token Grace Window Test (10 Threads)" -ForegroundColor Cyan
Write-Host " Target: $BaseUrl" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

# Step 1: Obtain a valid refresh token
if ([string]::IsNullOrWhiteSpace($Email)) {
    $randomSuffix = [System.Guid]::NewGuid().ToString().Substring(0, 8)
    $Email = "concurrency-test-$randomSuffix@marketplace.local"
    $phone = "+1" + (Get-Random -Minimum 1000000000 -Maximum 9999999999)

    Write-Host "[1/3] Registering temporary test customer ($Email)..." -ForegroundColor Yellow
    $regBody = @{
        name = "Concurrency Tester"
        email = $Email
        phone = $phone
        password = $Password
        role = "CUSTOMER"
        acceptedTerms = $true
        termsVersion = "v1.0"
    } | ConvertTo-Json

    try {
        $regResponse = Invoke-RestMethod -Uri "$BaseUrl/auth/register" -Method Post -Body $regBody -ContentType "application/json"
    } catch {
        Write-Error "Registration failed: $_"
        exit 1
    }
}

Write-Host "[2/3] Logging in to retrieve initial token pair..." -ForegroundColor Yellow
$loginBody = @{
    email = $Email
    password = $Password
} | ConvertTo-Json

try {
    $loginResponse = Invoke-RestMethod -Uri "$BaseUrl/auth/login" -Method Post -Body $loginBody -ContentType "application/json"
    $refreshToken = $loginResponse.refreshToken
    if ([string]::IsNullOrWhiteSpace($refreshToken)) {
        Write-Error "Login did not return a refresh token!"
        exit 1
    }
    Write-Host "Successfully obtained initial refresh token." -ForegroundColor Green
} catch {
    Write-Error "Login failed: $_"
    exit 1
}

# Step 3: Fire 10 parallel refresh calls using PowerShell jobs
Write-Host "[3/3] Firing 10 concurrent POST $BaseUrl/auth/refresh requests..." -ForegroundColor Yellow

$jobs = @()
$refreshPayload = @{ refreshToken = $refreshToken } | ConvertTo-Json

for ($i = 1; $i -le 10; $i++) {
    $job = Start-ThreadJob -ScriptBlock {
        param($url, $payload, $id)
        try {
            $response = Invoke-WebRequest -Uri "$url/auth/refresh" `
                -Method Post `
                -Body $payload `
                -ContentType "application/json" `
                -SkipHttpErrorCheck `
                -TimeoutSec 10
            return [PSCustomObject]@{
                ThreadId   = $id
                StatusCode = $response.StatusCode
                Body       = $response.Content
            }
        } catch {
            return [PSCustomObject]@{
                ThreadId   = $id
                StatusCode = 0
                Body       = $_.Exception.Message
            }
        }
    } -ArgumentList $BaseUrl, $refreshPayload, $i
    $jobs += $job
}

Write-Host "Waiting for all 10 parallel requests to complete..." -ForegroundColor Yellow
$results = $jobs | Wait-Job | Receive-Job
$jobs | Remove-Job

Write-Host ""
Write-Host "=== CONCURRENCY TEST RESULTS ===" -ForegroundColor Cyan
$successCount = 0
$conflictCount = 0
$otherCount = 0

foreach ($r in $results | Sort-Object ThreadId) {
    $color = "Red"
    $statusMsg = "OTHER ($($r.StatusCode))"

    if ($r.StatusCode -eq 200) {
        $color = "Green"
        $statusMsg = "200 OK (ROTATED)"
        $successCount++
    } elseif ($r.StatusCode -eq 409) {
        $color = "Yellow"
        $statusMsg = "409 CONFLICT (REFRESH_IN_PROGRESS)"
        $conflictCount++
    } else {
        $otherCount++
    }

    Write-Host "Thread $($r.ThreadId): $statusMsg" -ForegroundColor $color
}

Write-Host "------------------------------------------------------------" -ForegroundColor Cyan
Write-Host "Summary: 200 OK: $successCount | 409 Conflict: $conflictCount | Other: $otherCount" -ForegroundColor Cyan

if ($successCount -eq 1 -and $conflictCount -eq 9) {
    Write-Host "[PASS] Concurrency test passed: Exactly 1 refresh succeeded, 9 tolerated via grace window." -ForegroundColor Green
    exit 0
} else {
    Write-Host "[FAIL] Concurrency test did not match expected 1x 200 and 9x 409." -ForegroundColor Red
    exit 1
}
