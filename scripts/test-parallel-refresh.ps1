<#
.SYNOPSIS
    Fires 10 concurrent refresh token requests against a running local backend
    to verify atomic token rotation and the concurrent refresh grace window (Finding 3).

.DESCRIPTION
    Expected behavior:
    - Exactly 1 request succeeds with HTTP 200 OK and receives a new token pair.
    - The remaining 9 concurrent requests within the 10-second grace window receive
      HTTP 409 Conflict (error code: REFRESH_IN_PROGRESS).
    - User sessions are preserved (no 401 token reuse cascade revocation).
    - The winning request's rotated refresh token is verified to be valid and reusable.

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

$ErrorActionPreference = "Stop"

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " WP01 Concurrent Refresh Token Grace Window Test (10 Threads)" -ForegroundColor Cyan
Write-Host " Target API Base: $BaseUrl" -ForegroundColor Cyan
Write-Host " NOTE: Backend must run with SPRING_PROFILES_ACTIVE=local and" -ForegroundColor Cyan
Write-Host "       REQUIRE_EMAIL_VERIFICATION=false for dev bypass active." -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan

# Step 1: Register temporary test customer
if ([string]::IsNullOrWhiteSpace($Email)) {
    $randomSuffix = [System.Guid]::NewGuid().ToString().Substring(0, 8)
    $Email = "concurrency-test-$randomSuffix@marketplace.local"
}

$phone = "+1" + (Get-Random -Minimum 1000000000 -Maximum 9999999999)

Write-Host "[1/4] Registering temporary test customer ($Email)..." -ForegroundColor Yellow
$regBody = @{
    name          = "Concurrency Tester"
    email         = $Email
    phone         = $phone
    password      = $Password
    role          = "CUSTOMER"
    acceptedTerms = $true
    policyVersion = "v1.0"
} | ConvertTo-Json

try {
    $regResponse = Invoke-WebRequest -Uri "$BaseUrl/auth/register" `
        -Method Post `
        -Body $regBody `
        -ContentType "application/json" `
        -SkipHttpErrorCheck `
        -TimeoutSec 10
} catch {
    Write-Error "Registration request failed: $_"
    exit 1
}

if ($regResponse.StatusCode -ne 201) {
    Write-Error "Registration failed: Expected HTTP 201 but received HTTP $($regResponse.StatusCode). Response: $($regResponse.Content)"
    exit 1
}

Write-Host "Registration succeeded with HTTP 201 Created." -ForegroundColor Green

# Step 2: Login to obtain initial token pair
Write-Host "[2/4] Logging in to retrieve initial token pair..." -ForegroundColor Yellow
$loginBody = @{
    email    = $Email
    password = $Password
} | ConvertTo-Json

try {
    $loginResponse = Invoke-WebRequest -Uri "$BaseUrl/auth/login" `
        -Method Post `
        -Body $loginBody `
        -ContentType "application/json" `
        -SkipHttpErrorCheck `
        -TimeoutSec 10
} catch {
    Write-Error "Login request failed: $_"
    exit 1
}

if ($loginResponse.StatusCode -ne 200) {
    Write-Error "Login failed: Expected HTTP 200 but received HTTP $($loginResponse.StatusCode)."
    exit 1
}

$loginJson = $loginResponse.Content | ConvertFrom-Json
$refreshToken = $loginJson.refreshToken

if ([string]::IsNullOrWhiteSpace($refreshToken)) {
    Write-Error "Login did not return a refresh token!"
    exit 1
}

Write-Host "Successfully logged in and obtained initial refresh token." -ForegroundColor Green

# Step 3: Fire 10 parallel refresh calls using PowerShell thread jobs
Write-Host "[3/4] Firing 10 concurrent POST $BaseUrl/auth/refresh requests..." -ForegroundColor Yellow

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
                Content    = $response.Content
            }
        } catch {
            return [PSCustomObject]@{
                ThreadId   = $id
                StatusCode = 0
                Content    = $_.Exception.Message
            }
        }
    } -ArgumentList $BaseUrl, $refreshPayload, $i
    $jobs += $job
}

$results = $jobs | Wait-Job | Receive-Job
$jobs | Remove-Job

Write-Host ""
Write-Host "=== CONCURRENCY TEST RESULTS ===" -ForegroundColor Cyan
$successCount = 0
$conflictCount = 0
$otherCount = 0
$winningRefreshToken = $null

foreach ($r in $results | Sort-Object ThreadId) {
    if ($r.StatusCode -eq 200) {
        $statusMsg = "200 OK (TOKEN_ROTATED)"
        $color = "Green"
        $successCount++
        try {
            $parsed = $r.Content | ConvertFrom-Json
            $winningRefreshToken = $parsed.refreshToken
        } catch {}
    } elseif ($r.StatusCode -eq 409) {
        $statusMsg = "409 CONFLICT (REFRESH_IN_PROGRESS)"
        $color = "Yellow"
        $conflictCount++
    } else {
        $statusMsg = "OTHER ($($r.StatusCode))"
        $color = "Red"
        $otherCount++
    }

    Write-Host "  Thread $($r.ThreadId): $statusMsg" -ForegroundColor $color
}

Write-Host "------------------------------------------------------------" -ForegroundColor Cyan
Write-Host "Summary: 200 OK: $successCount | 409 Conflict: $conflictCount | Other: $otherCount" -ForegroundColor Cyan

if ($successCount -ne 1 -or $conflictCount -ne 9) {
    Write-Host "[FAIL] Concurrency test did not match expected 1x 200 and 9x 409." -ForegroundColor Red
    exit 1
}

# Step 4: Verify winner's rotated refresh token is valid and active
Write-Host ""
Write-Host "[4/4] Verifying winning rotated refresh token..." -ForegroundColor Yellow
if ([string]::IsNullOrWhiteSpace($winningRefreshToken)) {
    Write-Error "Could not retrieve rotated refresh token from winning request!"
    exit 1
}

$verifyPayload = @{ refreshToken = $winningRefreshToken } | ConvertTo-Json
try {
    $verifyResp = Invoke-WebRequest -Uri "$BaseUrl/auth/refresh" `
        -Method Post `
        -Body $verifyPayload `
        -ContentType "application/json" `
        -SkipHttpErrorCheck `
        -TimeoutSec 10
} catch {
    Write-Error "Winner token verification request failed: $_"
    exit 1
}

if ($verifyResp.StatusCode -ne 200) {
    Write-Host "[FAIL] Winner's token failed subsequent refresh: HTTP $($verifyResp.StatusCode)" -ForegroundColor Red
    exit 1
}

Write-Host "Winner's rotated refresh token verified successfully (HTTP 200 OK)." -ForegroundColor Green
Write-Host ""
Write-Host "[NOTE] Temporary test user ($Email) remains in development database (no cleanup endpoint exposed)." -ForegroundColor DarkGray
Write-Host "[PASS] Concurrency test passed: Exactly 1 refresh succeeded, 9 tolerated via grace window, winner rotated token verified." -ForegroundColor Green
exit 0
