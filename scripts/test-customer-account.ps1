<#
.SYNOPSIS
    End-to-end verification of WP01 Customer Account endpoints (FR-CUST).

.DESCRIPTION
    Registers a test customer, logs in, and exercises all customer account
    endpoints: profile GET/PUT, change password, districts, addresses CRUD
    (create, list, update, set-default, delete), and deactivation (expects
    503 from fail-closed DefaultOpenBookingsChecker).

    Requires the backend to be running with email verification disabled:
      REQUIRE_EMAIL_VERIFICATION=false  or  SPRING_PROFILES_ACTIVE=local

    SECURITY: Never prints passwords, JWT tokens, MongoDB URIs, or secrets.
    Compatible with Windows PowerShell 5.1+.

.PARAMETER BaseUrl
    Base API URL. Default: http://localhost:8080/api/v1
#>

[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8080/api/v1"
)

$ErrorActionPreference = "Stop"

# ---------- Helpers ----------

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Uri,
        [string]$Body = $null,
        [string]$Token = $null,
        [int]$TimeoutSec = 15
    )
    $headers = @{ "Content-Type" = "application/json" }
    if ($Token) { $headers["Authorization"] = "Bearer $Token" }

    $params = @{
        Uri        = $Uri
        Method     = $Method
        Headers    = $headers
        TimeoutSec = $TimeoutSec
    }
    if ($Body) {
        $bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($Body)
        $params["Body"] = $bodyBytes
    }

    try {
        $response = Invoke-WebRequest @params -UseBasicParsing
        return @{ StatusCode = [int]$response.StatusCode; Content = $response.Content }
    } catch {
        $ex = $_.Exception
        if ($ex.Response) {
            $statusCode = [int]$ex.Response.StatusCode
            $reader = New-Object System.IO.StreamReader($ex.Response.GetResponseStream())
            $content = $reader.ReadToEnd()
            $reader.Close()
            return @{ StatusCode = $statusCode; Content = $content }
        }
        throw
    }
}

function Assert-Status {
    param([hashtable]$Response, [int]$Expected, [string]$Label)
    if ($Response.StatusCode -ne $Expected) {
        Write-Host "  FAIL [$Label]: expected HTTP $Expected but got HTTP $($Response.StatusCode). Body: $($Response.Content)" -ForegroundColor Red
        $script:failed = $true
        return $false
    }
    Write-Host "  PASS [$Label]: HTTP $($Response.StatusCode)" -ForegroundColor Green
    return $true
}

$passed  = 0
$total   = 0
$failed  = $false

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host " WP01 Customer Account E2E Verification (FR-CUST)" -ForegroundColor Cyan
Write-Host " Target: $BaseUrl" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""

# ---------- 1. Register ----------
$suffix = [System.Guid]::NewGuid().ToString().Substring(0, 8)
$email  = "cust-e2e-$suffix@marketplace.local"
$phone  = "+947" + (Get-Random -Minimum 10000000 -Maximum 99999999)
$pw     = "TestPass123!"

Write-Host "[1/9] Registering test customer ($email)..." -ForegroundColor Yellow
$regBody = @{
    name          = "E2E Customer Tester"
    email         = $email
    phone         = $phone
    password      = $pw
    role          = "CUSTOMER"
    acceptedTerms = $true
    policyVersion = "v1.0"
} | ConvertTo-Json

$total++
$resp = Invoke-Api -Method POST -Uri "$BaseUrl/auth/register" -Body $regBody
if (Assert-Status -Response $resp -Expected 201 -Label "Register") { $passed++ }

# ---------- 2. Login ----------
Write-Host "[2/9] Logging in..." -ForegroundColor Yellow
$loginBody = @{ email = $email; password = $pw } | ConvertTo-Json

$total++
$resp = Invoke-Api -Method POST -Uri "$BaseUrl/auth/login" -Body $loginBody
if (Assert-Status -Response $resp -Expected 200 -Label "Login") { $passed++ }

$loginData   = $resp.Content | ConvertFrom-Json
$accessToken = $loginData.accessToken
Write-Host "  (access token obtained, not printed)" -ForegroundColor DarkGray

# ---------- 3. GET /customers/me ----------
Write-Host "[3/9] GET profile..." -ForegroundColor Yellow
$total++
$resp = Invoke-Api -Method GET -Uri "$BaseUrl/customers/me" -Token $accessToken
if (Assert-Status -Response $resp -Expected 200 -Label "GET profile") { $passed++ }

$profile = $resp.Content | ConvertFrom-Json
Write-Host "  Name: $($profile.fullName)  Email: $($profile.email)  Status: $($profile.status)" -ForegroundColor DarkGray

# ---------- 4. PUT /customers/me ----------
Write-Host "[4/9] PUT profile (update name & phone)..." -ForegroundColor Yellow
$updateBody = @{
    fullName = "Updated E2E Tester"
    phone    = "+94712345678"
} | ConvertTo-Json

$total++
$resp = Invoke-Api -Method PUT -Uri "$BaseUrl/customers/me" -Body $updateBody -Token $accessToken
if (Assert-Status -Response $resp -Expected 200 -Label "PUT profile") { $passed++ }

$updated = $resp.Content | ConvertFrom-Json
if ($updated.fullName -ne "Updated E2E Tester") {
    Write-Host "  FAIL: name was not updated (got '$($updated.fullName)')" -ForegroundColor Red
    $failed = $true
} else {
    Write-Host "  Updated name: $($updated.fullName)" -ForegroundColor DarkGray
}

# ---------- 5. GET /locations/districts ----------
Write-Host "[5/9] GET districts..." -ForegroundColor Yellow
$total++
$resp = Invoke-Api -Method GET -Uri "$BaseUrl/locations/districts" -Token $accessToken
if (Assert-Status -Response $resp -Expected 200 -Label "GET districts") { $passed++ }

$districts = $resp.Content | ConvertFrom-Json
$provinceCount  = $districts.provinces.Count
$districtCount  = ($districts.provinces | ForEach-Object { $_.districts.Count } | Measure-Object -Sum).Sum
Write-Host "  Provinces: $provinceCount  Districts: $districtCount" -ForegroundColor DarkGray

# ---------- 6. Address CRUD ----------
Write-Host "[6/9] Address CRUD..." -ForegroundColor Yellow

# Create address 1
$addr1Body = @{
    label        = "Home"
    addressLine1 = "123 Main Street"
    addressLine2 = "Apt 4B"
    city         = "Colombo"
    district     = "Colombo"
    postalCode   = "00100"
    isDefault    = $true
} | ConvertTo-Json

$total++
$resp = Invoke-Api -Method POST -Uri "$BaseUrl/customers/me/addresses" -Body $addr1Body -Token $accessToken
if (Assert-Status -Response $resp -Expected 201 -Label "Create address 1") { $passed++ }

$addr1 = $resp.Content | ConvertFrom-Json
$addr1Id = $addr1.id
Write-Host "  Created address 1: id=$addr1Id, label=$($addr1.label), default=$($addr1.isDefault)" -ForegroundColor DarkGray

# Create address 2
$addr2Body = @{
    label        = "Work"
    addressLine1 = "456 Office Road"
    city         = "Kandy"
    district     = "Kandy"
    postalCode   = "20000"
    isDefault    = $false
} | ConvertTo-Json

$total++
$resp = Invoke-Api -Method POST -Uri "$BaseUrl/customers/me/addresses" -Body $addr2Body -Token $accessToken
if (Assert-Status -Response $resp -Expected 201 -Label "Create address 2") { $passed++ }

$addr2 = $resp.Content | ConvertFrom-Json
$addr2Id = $addr2.id

# List addresses
$total++
$resp = Invoke-Api -Method GET -Uri "$BaseUrl/customers/me/addresses" -Token $accessToken
if (Assert-Status -Response $resp -Expected 200 -Label "List addresses") { $passed++ }

$addrList = $resp.Content | ConvertFrom-Json
Write-Host "  Address count: $($addrList.Count)" -ForegroundColor DarkGray

# Update address 2
$updateAddr2Body = @{
    label        = "Work Updated"
    addressLine1 = "789 New Office"
    city         = "Kandy"
    district     = "Kandy"
    postalCode   = "20001"
    isDefault    = $false
} | ConvertTo-Json

$total++
$resp = Invoke-Api -Method PUT -Uri "$BaseUrl/customers/me/addresses/$addr2Id" -Body $updateAddr2Body -Token $accessToken
if (Assert-Status -Response $resp -Expected 200 -Label "Update address 2") { $passed++ }

# Set address 2 as default
$total++
$resp = Invoke-Api -Method PUT -Uri "$BaseUrl/customers/me/addresses/$addr2Id/default" -Token $accessToken
if (Assert-Status -Response $resp -Expected 200 -Label "Set default address") { $passed++ }

$updatedAddr2 = $resp.Content | ConvertFrom-Json
if ($updatedAddr2.isDefault -ne $true) {
    Write-Host "  FAIL: address 2 should be default" -ForegroundColor Red
    $failed = $true
}

# Delete address 1
$total++
$resp = Invoke-Api -Method DELETE -Uri "$BaseUrl/customers/me/addresses/$addr1Id" -Token $accessToken
if (Assert-Status -Response $resp -Expected 204 -Label "Delete address 1") { $passed++ }

# Verify deletion
$resp = Invoke-Api -Method GET -Uri "$BaseUrl/customers/me/addresses" -Token $accessToken
$addrList = $resp.Content | ConvertFrom-Json
Write-Host "  Address count after delete: $($addrList.Count)" -ForegroundColor DarkGray
if ($addrList.Count -ne 1) {
    Write-Host "  FAIL: expected 1 address after deletion, got $($addrList.Count)" -ForegroundColor Red
    $failed = $true
}

# ---------- 7. Change Password ----------
Write-Host "[7/9] Change password..." -ForegroundColor Yellow
$newPw = "NewSecure456!"
$chPwBody = @{
    currentPassword = $pw
    newPassword     = $newPw
} | ConvertTo-Json

$total++
$resp = Invoke-Api -Method PUT -Uri "$BaseUrl/customers/me/password" -Body $chPwBody -Token $accessToken
if (Assert-Status -Response $resp -Expected 204 -Label "Change password") { $passed++ }

# ---------- 8. Login with new password ----------
Write-Host "[8/9] Login with new password..." -ForegroundColor Yellow
$loginBody2 = @{ email = $email; password = $newPw } | ConvertTo-Json

$total++
$resp = Invoke-Api -Method POST -Uri "$BaseUrl/auth/login" -Body $loginBody2
if (Assert-Status -Response $resp -Expected 200 -Label "Login new password") { $passed++ }

$loginData2   = $resp.Content | ConvertFrom-Json
$accessToken2 = $loginData2.accessToken

# ---------- 9. Deactivate (expects 503) ----------
Write-Host "[9/9] Deactivate account (expect 503 from DefaultOpenBookingsChecker)..." -ForegroundColor Yellow
$deactBody = @{ password = $newPw } | ConvertTo-Json

$total++
$resp = Invoke-Api -Method POST -Uri "$BaseUrl/customers/me/deactivate" -Body $deactBody -Token $accessToken2
if (Assert-Status -Response $resp -Expected 503 -Label "Deactivate (fail-closed)") { $passed++ }

$deactResp = $resp.Content | ConvertFrom-Json
Write-Host "  Error code: $($deactResp.errorCode)" -ForegroundColor DarkGray

# ---------- Summary ----------
Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
if ($passed -eq $total -and -not $failed) {
    Write-Host " RESULTS: $passed / $total checks passed" -ForegroundColor Green
} else {
    Write-Host " RESULTS: $passed / $total checks passed (SOME FAILURES)" -ForegroundColor Red
}
Write-Host "============================================================" -ForegroundColor Cyan

if ($failed -or $passed -ne $total) { exit 1 }
exit 0
