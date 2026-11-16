<#
.SYNOPSIS
    Demonstration and verification script for rate-limiting behavior against local environment.

.DESCRIPTION
    Drives requests against the local backend to verify HTTP 429 RATE_LIMITED responses
    and Retry-After headers across three distinct rate-limiting buckets:
      1. Login endpoint (account rate limit: default 5 requests per 15 min per account)
      2. Registration endpoint (IP rate limit: default 10 requests per hour per IP)
      3. Sensitive customer endpoint (user rate limit: default 10 requests per 10 min per user)

    SAFETY CONSTRAINT:
    This script strictly verifies that the target BaseUrl resolves to localhost or 127.0.0.1.
    It MUST NEVER be pointed at Atlas-backed shared, staging, or production environments,
    as doing so could trigger account lockouts, IP blocks, or service disruption.

    SECURITY & PRIVACY:
    This script generates random test data and never reads or logs secrets, tokens,
    real credentials, or database connection strings.

.PARAMETER BaseUrl
    The base API URL. Default: http://localhost:8080/api/v1
#>

[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8080/api/v1"
)

$ErrorActionPreference = "Stop"

# ==============================================================================
# Host Safety Validation
# ==============================================================================
# Never point this script at shared or production environments (e.g. Atlas-backed
# environments) because deliberate rate-limit saturation can cause denial of service
# or unexpected account lockouts for legitimate users.
try {
    $targetUri = [System.Uri]$BaseUrl
} catch {
    Write-Error "Invalid BaseUrl provided: '$BaseUrl'. Error: $_"
    exit 1
}

if ($targetUri.Host -ne "localhost" -and $targetUri.Host -ne "127.0.0.1") {
    Write-Error "SAFETY CHECK FAILED: Target host is '$($targetUri.Host)'. This script is restricted to 'localhost' or '127.0.0.1' only. Never run rate-limit saturation tests against shared or production environments."
    exit 1
}

# ==============================================================================
# HTTP Request Helper
# ==============================================================================
function Invoke-ApiRequest {
    param(
        [string]$Method,
        [string]$Uri,
        [string]$Body = $null,
        [string]$Token = $null,
        [int]$TimeoutSec = 15
    )
    $headers = @{ "Content-Type" = "application/json" }
    if ($Token) {
        $headers["Authorization"] = "Bearer $Token"
    }

    $params = @{
        Uri        = $Uri
        Method     = $Method
        Headers    = $headers
        TimeoutSec = $TimeoutSec
    }
    if ($Body) {
        $params["Body"] = [System.Text.Encoding]::UTF8.GetBytes($Body)
    }

    try {
        $response = Invoke-WebRequest @params -UseBasicParsing
        $retryAfter = $null
        if ($response.Headers) {
            $retryAfter = $response.Headers["Retry-After"]
        }
        return @{
            StatusCode = [int]$response.StatusCode
            Content    = $response.Content
            RetryAfter = $retryAfter
        }
    } catch {
        $ex = $_.Exception
        if ($ex.Response) {
            $statusCode = [int]$ex.Response.StatusCode
            $retryAfter = $null
            if ($ex.Response.Headers) {
                try {
                    $retryAfter = $ex.Response.Headers["Retry-After"]
                } catch {
                    $retryAfter = $null
                }
            }
            $reader = New-Object System.IO.StreamReader($ex.Response.GetResponseStream())
            $content = $reader.ReadToEnd()
            $reader.Close()
            return @{
                StatusCode = $statusCode
                Content    = $content
                RetryAfter = $retryAfter
            }
        }
        throw
    }
}

function Get-ErrorCode {
    param([string]$Content)
    if ([string]::IsNullOrWhiteSpace($Content)) {
        return "N/A"
    }
    try {
        $json = $Content | ConvertFrom-Json
        if ($json.error) { return $json.error }
        if ($json.message) { return $json.message }
    } catch {}
    return "UNKNOWN"
}

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " Rate Limiting Evidence & Verification Script" -ForegroundColor Cyan
Write-Host " Target URL: $BaseUrl" -ForegroundColor Cyan
Write-Host " Host:       $($targetUri.Host) (Localhost verified)" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""

# ==============================================================================
# Setup: Register & Authenticate a Customer (for Part 3)
# ==============================================================================
# We perform account setup prior to Part 2 to ensure the customer account is created
# before the IP registration rate limit is saturated.
Write-Host "[Setup] Creating temporary test customer for sensitive endpoint tests..." -ForegroundColor Yellow
$setupSuffix = [System.Guid]::NewGuid().ToString().Substring(0, 8)
$setupEmail = "setup-ratelimit-$setupSuffix@marketplace.local"
$setupPhone = "+1" + (Get-Random -Minimum 1000000000 -Maximum 9999999999)
$setupPass = "SetupPass123!"

$setupRegBody = @{
    name          = "Rate Limit Setup User"
    email         = $setupEmail
    phone         = $setupPhone
    password      = $setupPass
    role          = "CUSTOMER"
    acceptedTerms = $true
    policyVersion = "v1.0"
} | ConvertTo-Json

$setupRegResp = Invoke-ApiRequest -Method Post -Uri "$BaseUrl/auth/register" -Body $setupRegBody
if ($setupRegResp.StatusCode -ne 201) {
    Write-Warning "Setup registration returned HTTP $($setupRegResp.StatusCode) (Error: $(Get-ErrorCode $setupRegResp.Content)). Continuing..."
}

$setupLoginBody = @{
    email    = $setupEmail
    password = $setupPass
} | ConvertTo-Json

$setupLoginResp = Invoke-ApiRequest -Method Post -Uri "$BaseUrl/auth/login" -Body $setupLoginBody
$customerToken = $null
if ($setupLoginResp.StatusCode -eq 200) {
    try {
        $loginJson = $setupLoginResp.Content | ConvertFrom-Json
        $customerToken = $loginJson.accessToken
        Write-Host "Setup customer authenticated successfully." -ForegroundColor Green
    } catch {
        Write-Warning "Failed to parse access token from setup login response."
    }
} else {
    Write-Warning "Setup login returned HTTP $($setupLoginResp.StatusCode) (Error: $(Get-ErrorCode $setupLoginResp.Content))."
}
Write-Host ""

# ==============================================================================
# PART 1: Account Login Rate Limiter (Default: 5 per 15 minutes)
# ==============================================================================
Write-Host "--- PART 1: Login Account Rate Limiter ---" -ForegroundColor Cyan
Write-Host "Target: POST $BaseUrl/auth/login"
Write-Host "Testing account rate limit against targeted email until HTTP 429 is reached."
$targetLoginEmail = "target-account-$([System.Guid]::NewGuid().ToString().Substring(0, 8))@marketplace.local"
$part1Hit429 = $false
$maxLoginAttempts = 15

for ($i = 1; $i -le $maxLoginAttempts; $i++) {
    $loginBody = @{
        email    = $targetLoginEmail
        password = "WrongPassword123!"
    } | ConvertTo-Json

    $resp = Invoke-ApiRequest -Method Post -Uri "$BaseUrl/auth/login" -Body $loginBody
    $err = Get-ErrorCode $resp.Content
    $retry = if ($resp.RetryAfter) { $resp.RetryAfter } else { "None" }

    Write-Host "  Attempt $i : HTTP $($resp.StatusCode) | Error: $err | Retry-After: $retry"

    if ($resp.StatusCode -eq 429) {
        Write-Host "  [OK] HTTP 429 RATE_LIMITED encountered on attempt $i (Retry-After: $retry)" -ForegroundColor Green
        $part1Hit429 = $true
        break
    }
}

if (-not $part1Hit429) {
    Write-Host "  [NOTE] HTTP 429 was not hit within $maxLoginAttempts attempts (check server rate-limit configuration)." -ForegroundColor Yellow
}
Write-Host ""

# ==============================================================================
# PART 2: Registration IP Rate Limiter (Default: 10 per hour)
# ==============================================================================
Write-Host "--- PART 2: Registration IP Rate Limiter ---" -ForegroundColor Cyan
Write-Host "Target: POST $BaseUrl/auth/register"
Write-Host "Testing IP rate limit against client IP until HTTP 429 is reached."
$part2Hit429 = $false
$maxRegisterAttempts = 15

for ($i = 1; $i -le $maxRegisterAttempts; $i++) {
    $regSuffix = [System.Guid]::NewGuid().ToString().Substring(0, 8)
    $regEmail = "reg-limit-$regSuffix@marketplace.local"
    $regPhone = "+1" + (Get-Random -Minimum 1000000000 -Maximum 9999999999)

    $regBody = @{
        name          = "Rate Limit User $i"
        email         = $regEmail
        phone         = $regPhone
        password      = "Password123!"
        role          = "CUSTOMER"
        acceptedTerms = $true
        policyVersion = "v1.0"
    } | ConvertTo-Json

    $resp = Invoke-ApiRequest -Method Post -Uri "$BaseUrl/auth/register" -Body $regBody
    $err = Get-ErrorCode $resp.Content
    $retry = if ($resp.RetryAfter) { $resp.RetryAfter } else { "None" }

    Write-Host "  Attempt $i : HTTP $($resp.StatusCode) | Error: $err | Retry-After: $retry"

    if ($resp.StatusCode -eq 429) {
        Write-Host "  [OK] HTTP 429 RATE_LIMITED encountered on attempt $i (Retry-After: $retry)" -ForegroundColor Green
        $part2Hit429 = $true
        break
    }
}

if (-not $part2Hit429) {
    Write-Host "  [NOTE] HTTP 429 was not hit within $maxRegisterAttempts attempts (check server rate-limit configuration)." -ForegroundColor Yellow
}
Write-Host ""

# ==============================================================================
# PART 3: Sensitive Endpoint Rate Limiter (Password Change: Default 5 per 15 min)
# ==============================================================================
# NOTE: In an epoch-aligned fixed-window rate limiter, Retry-After reflects the
# remaining seconds until the current epoch-aligned window boundary (:00, :15, :30, :45).
# If run near the end of an interval, Retry-After is naturally smaller (e.g. 30s).
Write-Host "--- PART 3: Sensitive Endpoint Rate Limiter (Password Change) ---" -ForegroundColor Cyan
Write-Host "Target: PUT $BaseUrl/customers/me/password"
Write-Host "Testing user rate limit against sensitive endpoint until HTTP 429 is reached."

if (-not $customerToken) {
    Write-Host "  [SKIP] No access token available for customer; skipping Part 3." -ForegroundColor Yellow
} else {
    $part3Hit429 = $false
    $maxPasswordAttempts = 15

    for ($i = 1; $i -le $maxPasswordAttempts; $i++) {
        $pwBody = @{
            currentPassword = "IncorrectCurrentPassword123!"
            newPassword     = "BrandNewPassword123!"
        } | ConvertTo-Json

        $resp = Invoke-ApiRequest -Method Put -Uri "$BaseUrl/customers/me/password" -Body $pwBody -Token $customerToken
        $err = Get-ErrorCode $resp.Content
        $retry = if ($resp.RetryAfter) { $resp.RetryAfter } else { "None" }

        Write-Host "  Attempt $i : HTTP $($resp.StatusCode) | Error: $err | Retry-After: $retry"

        if ($resp.StatusCode -eq 429) {
            Write-Host "  [OK] HTTP 429 RATE_LIMITED encountered on attempt $i (Retry-After: $retry)" -ForegroundColor Green
            $part3Hit429 = $true
            break
        }
    }

    if (-not $part3Hit429) {
        Write-Host "  [NOTE] HTTP 429 was not hit within $maxPasswordAttempts attempts (check server rate-limit configuration)." -ForegroundColor Yellow
    }
}

Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " Rate Limiting Verification Complete" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
