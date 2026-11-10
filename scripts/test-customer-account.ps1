<#
.SYNOPSIS
    End-to-end verification of WP01 Customer Account endpoints (FR-CUST).

.DESCRIPTION
    Registers a test customer, logs in, and exercises all customer account
    endpoints in sequence:
      1. Register test customer (unique email & phone)
      2. Login to obtain access token
      3. GET /customers/me (profile inspection)
      4. PUT /customers/me (profile update with unique phone & persistence check)
      5. Phone conflict check (second customer attempts to claim same phone -> 409)
      6. GET /locations/districts (verify 25 districts across 9 provinces)
      7. Address CRUD (create default, create secondary, list, update, set default, delete)
      8. Change password & verify login with new credentials
      9. POST /customers/me/deactivate (verifies fail-closed HTTP 503 response)

    NOTE: This test script generates unique test accounts with the @marketplace.local
    domain. Because there is no account deletion / purge endpoint by design, these
    temporary test accounts remain in the development database.

    REQUIREMENTS:
    - Backend running with email verification disabled:
        REQUIRE_EMAIL_VERIFICATION=false  or  SPRING_PROFILES_ACTIVE=local
    - Compatible with Windows PowerShell 5.1+ and PowerShell 7+.

    SECURITY:
    Never prints passwords, JWT tokens, MongoDB URIs, or secrets in logs or output.

.PARAMETER BaseUrl
    Base API URL. Default: http://localhost:8080/api/v1
#>

[CmdletBinding()]
param(
    [string]$BaseUrl = "http://localhost:8080/api/v1"
)

$ErrorActionPreference = "Stop"

# ---------- Cryptographic Random Generators ----------

function Get-CryptoRandomNumber([int]$Min, [int]$Max) {
    $bytes = New-Object byte[] 4
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    $rng.GetBytes($bytes)
    $val = [System.BitConverter]::ToUInt32($bytes, 0)
    $range = [uint32]($Max - $Min)
    return [int]($Min + ($val % $range))
}

function New-UniquePhoneDigits {
    # Generate 8 random digits using crypto random source
    $num = Get-CryptoRandomNumber -Min 10000000 -Max 99999999
    return $num.ToString()
}

# ---------- HTTP Helper ----------

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
$suffix1 = [System.Guid]::NewGuid().ToString().Substring(0, 8)
$email1  = "cust1-e2e-$suffix1@marketplace.local"
$phone1  = "+947" + (New-UniquePhoneDigits)
$pw1     = "TestPass123!"

Write-Host "[1/9] Registering primary test customer ($email1)..." -ForegroundColor Yellow
$regBody1 = @{
    name          = "E2E Customer Tester"
    email         = $email1
    phone         = $phone1
    password      = $pw1
    role          = "CUSTOMER"
    acceptedTerms = $true
    policyVersion = "v1.0"
} | ConvertTo-Json

$total++
$resp = Invoke-Api -Method POST -Uri "$BaseUrl/auth/register" -Body $regBody1
if (Assert-Status -Response $resp -Expected 201 -Label "Register customer 1") { $passed++ }

# ---------- 2. Login ----------
Write-Host "[2/9] Logging in as customer 1..." -ForegroundColor Yellow
$loginBody1 = @{ email = $email1; password = $pw1 } | ConvertTo-Json

$total++
$resp = Invoke-Api -Method POST -Uri "$BaseUrl/auth/login" -Body $loginBody1
if (Assert-Status -Response $resp -Expected 200 -Label "Login customer 1") { $passed++ }

$loginData1   = $resp.Content | ConvertFrom-Json
$accessToken1 = $loginData1.accessToken
Write-Host "  (access token obtained, not printed)" -ForegroundColor DarkGray

# ---------- 3. GET /customers/me ----------
Write-Host "[3/9] GET profile..." -ForegroundColor Yellow
$total++
$resp = Invoke-Api -Method GET -Uri "$BaseUrl/customers/me" -Token $accessToken1
if (Assert-Status -Response $resp -Expected 200 -Label "GET profile") { $passed++ }

$profile = $resp.Content | ConvertFrom-Json
Write-Host "  Name: $($profile.fullName)  Email: $($profile.email)  Status: $($profile.status)" -ForegroundColor DarkGray

# ---------- 4. PUT /customers/me (with unique phone & persistence check) ----------
Write-Host "[4/9] PUT profile (update name & unique phone)..." -ForegroundColor Yellow
$newPhoneDigits          = New-UniquePhoneDigits
$updatedRawPhone         = "07$newPhoneDigits"             # Local format tests normalization
$expectedNormalizedPhone = "+947$newPhoneDigits"
$updatedName             = "Updated Customer E2E"

$updateBody = @{
    fullName = $updatedName
    phone    = $updatedRawPhone
} | ConvertTo-Json

$total++
$resp = Invoke-Api -Method PUT -Uri "$BaseUrl/customers/me" -Body $updateBody -Token $accessToken1
if (Assert-Status -Response $resp -Expected 200 -Label "PUT profile") {
    $passed++
    $updated = $resp.Content | ConvertFrom-Json
    $putOk = $true
    if ($updated.fullName -ne $updatedName) {
        Write-Host "  FAIL: returned fullName mismatch (expected '$updatedName', got '$($updated.fullName)')" -ForegroundColor Red
        $failed = $true
        $putOk = $false
    }
    if ($updated.phone -ne $expectedNormalizedPhone) {
        Write-Host "  FAIL: returned phone mismatch (expected '$expectedNormalizedPhone', got '$($updated.phone)')" -ForegroundColor Red
        $failed = $true
        $putOk = $false
    }

    if ($putOk) {
        Write-Host "  PUT response verified: Name='$($updated.fullName)', Phone='$($updated.phone)'" -ForegroundColor DarkGray
    }

    # Verify persistence via GET /customers/me
    $total++
    $getResp = Invoke-Api -Method GET -Uri "$BaseUrl/customers/me" -Token $accessToken1
    if (Assert-Status -Response $getResp -Expected 200 -Label "GET profile after update") {
        $verifiedProfile = $getResp.Content | ConvertFrom-Json
        if ($verifiedProfile.fullName -ne $updatedName -or $verifiedProfile.phone -ne $expectedNormalizedPhone) {
            Write-Host "  FAIL: GET profile does not reflect persisted changes (got name='$($verifiedProfile.fullName)', phone='$($verifiedProfile.phone)')" -ForegroundColor Red
            $failed = $true
        } else {
            $passed++
            Write-Host "  GET persistence verified: Name='$($verifiedProfile.fullName)', Phone='$($verifiedProfile.phone)'" -ForegroundColor DarkGray
        }
    }
} else {
    Write-Host "  PUT profile failed with HTTP $($resp.StatusCode). Response: $($resp.Content)" -ForegroundColor Red
}

# ---------- 5. Duplicate Phone Conflict Check (409) ----------
Write-Host "[5/9] Duplicate phone conflict test (second customer tries to claim customer 1's phone)..." -ForegroundColor Yellow
$suffix2 = [System.Guid]::NewGuid().ToString().Substring(0, 8)
$email2  = "cust2-e2e-$suffix2@marketplace.local"
$phone2  = "+947" + (New-UniquePhoneDigits)
$pw2     = "TestPass123!"

# Register second customer
$regBody2 = @{
    name          = "Second Tester"
    email         = $email2
    phone         = $phone2
    password      = $pw2
    role          = "CUSTOMER"
    acceptedTerms = $true
    policyVersion = "v1.0"
} | ConvertTo-Json

$total++
$respReg2 = Invoke-Api -Method POST -Uri "$BaseUrl/auth/register" -Body $regBody2
if (Assert-Status -Response $respReg2 -Expected 201 -Label "Register customer 2") { $passed++ }

# Login second customer
$loginBody2 = @{ email = $email2; password = $pw2 } | ConvertTo-Json
$total++
$respLogin2 = Invoke-Api -Method POST -Uri "$BaseUrl/auth/login" -Body $loginBody2
if (Assert-Status -Response $respLogin2 -Expected 200 -Label "Login customer 2") { $passed++ }

$loginData2   = $respLogin2.Content | ConvertFrom-Json
$accessToken2 = $loginData2.accessToken

# Customer 2 attempts to claim customer 1's phone
$conflictBody = @{
    fullName = "Second Tester Updated"
    phone    = $expectedNormalizedPhone
} | ConvertTo-Json

$total++
$respConflict = Invoke-Api -Method PUT -Uri "$BaseUrl/customers/me" -Body $conflictBody -Token $accessToken2
if (Assert-Status -Response $respConflict -Expected 409 -Label "Duplicate phone conflict") {
    $conflictJson = $respConflict.Content | ConvertFrom-Json
    $errField = $conflictJson.error
    if ($errField -eq "PHONE_ALREADY_EXISTS") {
        # Verify no owner details are leaked in the error body
        if ($respConflict.Content -match [regex]::Escape($email1) -or $respConflict.Content -match [regex]::Escape($profile.id)) {
            Write-Host "  FAIL: 409 response leaked owner details!" -ForegroundColor Red
            $failed = $true
        } else {
            $passed++
            Write-Host "  Verified 409 error: '$errField', message: '$($conflictJson.message)' (no owner data leaked)" -ForegroundColor DarkGray
        }
    } else {
        Write-Host "  FAIL: expected error code 'PHONE_ALREADY_EXISTS' but got '$errField'" -ForegroundColor Red
        $failed = $true
    }
} else {
    Write-Host "  Error Body: $($respConflict.Content)" -ForegroundColor Red
}

# ---------- 6. GET /locations/districts ----------
Write-Host "[6/9] GET districts..." -ForegroundColor Yellow
$total++
$resp = Invoke-Api -Method GET -Uri "$BaseUrl/locations/districts" -Token $accessToken1
if (Assert-Status -Response $resp -Expected 200 -Label "GET districts") {
    $districts = $resp.Content | ConvertFrom-Json
    $provinceCount = $districts.provinces.Count
    $districtCount = ($districts.provinces | ForEach-Object { $_.districts.Count } | Measure-Object -Sum).Sum
    if ($districtCount -eq 25 -and $provinceCount -eq 9) {
        $passed++
        Write-Host "  Provinces: $provinceCount  Districts: $districtCount (verified 25 districts in 9 provinces)" -ForegroundColor DarkGray
    } else {
        Write-Host "  FAIL: expected 25 districts across 9 provinces, got $districtCount districts in $provinceCount provinces" -ForegroundColor Red
        $failed = $true
    }
}

# ---------- 7. Address CRUD ----------
Write-Host "[7/9] Address CRUD..." -ForegroundColor Yellow

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
$resp = Invoke-Api -Method POST -Uri "$BaseUrl/customers/me/addresses" -Body $addr1Body -Token $accessToken1
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
$resp = Invoke-Api -Method POST -Uri "$BaseUrl/customers/me/addresses" -Body $addr2Body -Token $accessToken1
if (Assert-Status -Response $resp -Expected 201 -Label "Create address 2") { $passed++ }

$addr2 = $resp.Content | ConvertFrom-Json
$addr2Id = $addr2.id

# List addresses
$total++
$resp = Invoke-Api -Method GET -Uri "$BaseUrl/customers/me/addresses" -Token $accessToken1
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
$resp = Invoke-Api -Method PUT -Uri "$BaseUrl/customers/me/addresses/$addr2Id" -Body $updateAddr2Body -Token $accessToken1
if (Assert-Status -Response $resp -Expected 200 -Label "Update address 2") { $passed++ }

# Set address 2 as default
$total++
$resp = Invoke-Api -Method PUT -Uri "$BaseUrl/customers/me/addresses/$addr2Id/default" -Token $accessToken1
if (Assert-Status -Response $resp -Expected 200 -Label "Set default address") { $passed++ }

$updatedAddr2 = $resp.Content | ConvertFrom-Json
if ($updatedAddr2.isDefault -ne $true) {
    Write-Host "  FAIL: address 2 should be default" -ForegroundColor Red
    $failed = $true
}

# Delete address 1
$total++
$resp = Invoke-Api -Method DELETE -Uri "$BaseUrl/customers/me/addresses/$addr1Id" -Token $accessToken1
if (Assert-Status -Response $resp -Expected 204 -Label "Delete address 1") { $passed++ }

# Verify deletion
$resp = Invoke-Api -Method GET -Uri "$BaseUrl/customers/me/addresses" -Token $accessToken1
$addrList = $resp.Content | ConvertFrom-Json
Write-Host "  Address count after delete: $($addrList.Count)" -ForegroundColor DarkGray
if ($addrList.Count -ne 1) {
    Write-Host "  FAIL: expected 1 address after deletion, got $($addrList.Count)" -ForegroundColor Red
    $failed = $true
}

# ---------- 8. Change Password ----------
Write-Host "[8/9] Change password & verify login..." -ForegroundColor Yellow
$newPw = "NewSecure456!"
$chPwBody = @{
    currentPassword = $pw1
    newPassword     = $newPw
} | ConvertTo-Json

$total++
$resp = Invoke-Api -Method PUT -Uri "$BaseUrl/customers/me/password" -Body $chPwBody -Token $accessToken1
if (Assert-Status -Response $resp -Expected 204 -Label "Change password") { $passed++ }

# Login with new password
$loginBodyNew = @{ email = $email1; password = $newPw } | ConvertTo-Json

$total++
$resp = Invoke-Api -Method POST -Uri "$BaseUrl/auth/login" -Body $loginBodyNew
if (Assert-Status -Response $resp -Expected 200 -Label "Login with new password") { $passed++ }

$loginDataNew   = $resp.Content | ConvertFrom-Json
$accessTokenNew = $loginDataNew.accessToken

# ---------- 9. Deactivate (expects 503) ----------
Write-Host "[9/9] Deactivate account (expect 503 from DefaultOpenBookingsChecker)..." -ForegroundColor Yellow
$deactBody = @{ password = $newPw } | ConvertTo-Json

$total++
$resp = Invoke-Api -Method POST -Uri "$BaseUrl/customers/me/deactivate" -Body $deactBody -Token $accessTokenNew
if (Assert-Status -Response $resp -Expected 503 -Label "Deactivate (fail-closed)") {
    $deactResp = $resp.Content | ConvertFrom-Json
    $errCode = $deactResp.error
    if ($errCode -eq "DEACTIVATION_UNAVAILABLE") {
        $passed++
        Write-Host "  Verified fail-closed error code: $errCode" -ForegroundColor DarkGray
        Write-Host "  Message: $($deactResp.message)" -ForegroundColor DarkGray
    } else {
        Write-Host "  FAIL: expected error code 'DEACTIVATION_UNAVAILABLE' but got '$errCode'. Response: $($resp.Content)" -ForegroundColor Red
        $failed = $true
    }
} else {
    Write-Host "  Deactivate failed. Error Body: $($resp.Content)" -ForegroundColor Red
}

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
