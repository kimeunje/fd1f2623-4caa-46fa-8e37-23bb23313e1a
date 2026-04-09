# SecuHub Phase 2 Manual Test Script (PowerShell)
# Usage: .\phase2-test.ps1

$ErrorActionPreference = "Stop"
$BASE = "http://localhost:8080/api/v1"
$pass = 0
$fail = 0
$total = 0

function Write-Step($num, $msg) {
    Write-Host ""
    Write-Host "========================================" -ForegroundColor DarkGray
    Write-Host "  Step $num. $msg" -ForegroundColor Cyan
    Write-Host "========================================" -ForegroundColor DarkGray
}

function Write-Pass($msg) {
    $script:pass++
    $script:total++
    Write-Host "  PASS  $msg" -ForegroundColor Green
}

function Write-Fail($msg) {
    $script:fail++
    $script:total++
    Write-Host "  FAIL  $msg" -ForegroundColor Red
}

function Api-Get($url, $token) {
    $h = @{ "Authorization" = "Bearer $token" }
    return Invoke-RestMethod -Uri "$BASE$url" -Headers $h -Method Get
}

function Api-Post($url, $token, $body) {
    $h = @{ "Authorization" = "Bearer $token"; "Content-Type" = "application/json" }
    $json = $body | ConvertTo-Json -Depth 10
    return Invoke-RestMethod -Uri "$BASE$url" -Headers $h -Method Post -Body $json
}

# ========================================
# Step 0. Server check
# ========================================
Write-Step "0" "Server connection check"
try {
    $null = Invoke-WebRequest -Uri "http://localhost:8080/login" -UseBasicParsing -TimeoutSec 5
    Write-Pass "localhost:8080 responding"
} catch {
    Write-Host ""
    Write-Host "  Server is not responding!" -ForegroundColor Red
    Write-Host "  Start the server first:" -ForegroundColor Yellow
    Write-Host "    gradlew bootRun" -ForegroundColor White
    Write-Host ""
    exit 1
}

# ========================================
# Step 1. Login
# ========================================
Write-Step "1" "Admin login (admin@company.com)"

$loginBody = @{ email = "admin@company.com"; password = "admin1234" }
$loginJson = $loginBody | ConvertTo-Json
$loginResult = Invoke-RestMethod -Uri "$BASE/auth/login" -Method Post -Body $loginJson -ContentType "application/json"

if ($loginResult.success -and $loginResult.data.token) {
    $TOKEN = $loginResult.data.token
    $tokenPreview = $TOKEN.Substring(0, 20)
    Write-Pass "JWT token acquired ($tokenPreview...)"
    $userName = $loginResult.data.user.name
    $userRole = $loginResult.data.user.role
    Write-Host "  User: $userName ($userRole)" -ForegroundColor DarkGray
} else {
    Write-Fail "Login failed"
    exit 1
}

# ========================================
# Step 2. Framework / Controls demo data
# ========================================
Write-Step "2" "Demo data check (frameworks, controls)"

$frameworks = Api-Get "/frameworks" $TOKEN
$fwCount = $frameworks.data.Count
if ($frameworks.success -and $fwCount -gt 0) {
    Write-Pass "Frameworks: $fwCount"
    foreach ($fw in $frameworks.data) {
        $fwName = $fw.name
        $ctrlCount = $fw.controlCount
        Write-Host "    - $fwName (controls: $ctrlCount)" -ForegroundColor DarkGray
    }
} else {
    Write-Fail "No framework data"
}

$fwId = $frameworks.data[0].id
$controls = Api-Get "/frameworks/$fwId/controls" $TOKEN
$ctrlsCount = $controls.data.Count
if ($controls.success -and $ctrlsCount -gt 0) {
    Write-Pass "Controls: $ctrlsCount"
    foreach ($ctrl in ($controls.data | Select-Object -First 3)) {
        $code = $ctrl.code
        $name = $ctrl.name
        $collected = $ctrl.evidenceCollected
        $etotal = $ctrl.evidenceTotal
        Write-Host "    - [$code] $name ($collected/$etotal)" -ForegroundColor DarkGray
    }
} else {
    Write-Fail "No control data"
}

# ========================================
# Step 3. Control detail (evidence types)
# ========================================
Write-Step "3" "Control detail (evidence types + file history)"

$ctrlId = $controls.data[0].id
$detail = Api-Get "/controls/$ctrlId" $TOKEN
$etCount = $detail.data.evidenceTypes.Count
if ($detail.success -and $etCount -gt 0) {
    $dCode = $detail.data.code
    $dName = $detail.data.name
    Write-Pass "[$dCode] $dName - evidence types: $etCount"
    foreach ($et in $detail.data.evidenceTypes) {
        $etName = $et.name
        $etId = $et.id
        if ($et.collected) { $statusText = "collected" } else { $statusText = "missing" }
        if ($et.files) { $fCount = $et.files.Count } else { $fCount = 0 }
        Write-Host "    - ${etName}: $statusText (files: $fCount, etId=$etId)" -ForegroundColor DarkGray
    }
    $script:EVIDENCE_TYPE_ID = $detail.data.evidenceTypes[0].id
} else {
    Write-Fail "No evidence types"
}

# ========================================
# Step 4. Evidence file stats
# ========================================
Write-Step "4" "Evidence file stats"

$stats = Api-Get "/evidence-files/stats" $TOKEN
if ($stats.success) {
    Write-Pass "Stats OK"
    $totalFiles = $stats.data.totalFiles
    $qFiles = $stats.data.quarterFiles
    $sizeMB = [math]::Round($stats.data.totalSizeBytes / 1MB, 1)
    $coverage = $stats.data.controlCoverage
    Write-Host "    Total files: $totalFiles" -ForegroundColor DarkGray
    Write-Host "    This quarter: $qFiles" -ForegroundColor DarkGray
    Write-Host "    Total size: $sizeMB MB" -ForegroundColor DarkGray
    Write-Host "    Coverage: $coverage%" -ForegroundColor DarkGray
} else {
    Write-Fail "Stats failed"
}

# ========================================
# Step 5. Upload + Download
# ========================================
Write-Step "5" "File upload -> download verify"

$testContent = "SecuHub Phase 2 Test - $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
$testFilePath = Join-Path $env:TEMP "secuhub_test_upload.txt"
Set-Content -Path $testFilePath -Value $testContent -Encoding UTF8

# PowerShell 5.1: use .NET HttpClient for multipart upload
Add-Type -AssemblyName System.Net.Http
$httpClient = New-Object System.Net.Http.HttpClient
$httpClient.DefaultRequestHeaders.Add("Authorization", "Bearer $TOKEN")

$multipart = New-Object System.Net.Http.MultipartFormDataContent
$etIdContent = New-Object System.Net.Http.StringContent("$($script:EVIDENCE_TYPE_ID)")
$multipart.Add($etIdContent, "evidenceTypeId")

$fileStream = [System.IO.File]::OpenRead($testFilePath)
$fileContent = New-Object System.Net.Http.StreamContent($fileStream)
$fileContent.Headers.ContentType = [System.Net.Http.Headers.MediaTypeHeaderValue]::Parse("text/plain")
$multipart.Add($fileContent, "file", "secuhub_test_upload.txt")

$response = $httpClient.PostAsync("$BASE/evidence-files/upload", $multipart).Result
$responseBody = $response.Content.ReadAsStringAsync().Result
$fileStream.Close()
$httpClient.Dispose()

$uploadResult = $responseBody | ConvertFrom-Json

if ($uploadResult.success) {
    $uploadedId = $uploadResult.data.id
    $uploadedVer = $uploadResult.data.version
    $uploadedName = $uploadResult.data.fileName
    Write-Pass "Upload OK: id=$uploadedId, v$uploadedVer, $uploadedName"

    $downloadPath = Join-Path $env:TEMP "secuhub_test_download.txt"
    $dlHeaders = @{ "Authorization" = "Bearer $TOKEN" }
    Invoke-WebRequest -Uri "$BASE/evidence-files/$uploadedId/download" -Headers $dlHeaders -OutFile $downloadPath -UseBasicParsing

    $downloadedContent = (Get-Content $downloadPath -Encoding UTF8 -Raw).Trim()
    if ($downloadedContent -eq $testContent.Trim()) {
        Write-Pass "Download OK + content match verified"
    } else {
        Write-Fail "Download content mismatch"
    }

    Remove-Item $testFilePath -ErrorAction SilentlyContinue
    Remove-Item $downloadPath -ErrorAction SilentlyContinue
} else {
    Write-Fail "Upload failed: $($uploadResult.message)"
}

# ========================================
# Step 6. Collection jobs list
# ========================================
Write-Step "6" "Collection jobs list"

$jobs = Api-Get "/jobs" $TOKEN
$jobCount = $jobs.data.Count
if ($jobs.success) {
    Write-Pass "Jobs: $jobCount"
    foreach ($j in $jobs.data) {
        $jType = $j.jobType
        $jName = $j.name
        $jActive = $j.isActive
        if ($j.lastExecution) { $jLast = $j.lastExecution.status } else { $jLast = "-" }
        Write-Host "    - [$jType] $jName (active=$jActive, last=$jLast)" -ForegroundColor DarkGray
    }
} else {
    Write-Fail "Jobs list failed"
}

# ========================================
# Step 7. Script execution (success)
# ========================================
Write-Step "7" "Script execution test (ProcessBuilder)"

$scriptsDir = Join-Path (Get-Location) "backend\scripts"
if (-not (Test-Path $scriptsDir)) { New-Item -ItemType Directory -Path $scriptsDir -Force | Out-Null }

# Also ensure storage dir exists
$storageDir = Join-Path (Get-Location) "backend\storage"
if (-not (Test-Path $storageDir)) { New-Item -ItemType Directory -Path $storageDir -Force | Out-Null }

# Create success .bat script
$successScript = Join-Path $scriptsDir "phase2_test_success.bat"
$batLines = @(
    '@echo off',
    'set "OUTPUT_DIR=%~1"',
    'if "%OUTPUT_DIR%"=="" set "OUTPUT_DIR=%SECUHUB_OUTPUT_DIR%"',
    'echo === Phase 2 Test Result === > "%OUTPUT_DIR%\phase2_test_result.txt"',
    'echo Test Date: %date% %time% >> "%OUTPUT_DIR%\phase2_test_result.txt"',
    'echo Server-01 OK >> "%OUTPUT_DIR%\phase2_test_result.txt"',
    'echo Server-02 OK >> "%OUTPUT_DIR%\phase2_test_result.txt"',
    'echo Status ALL PASS >> "%OUTPUT_DIR%\phase2_test_result.txt"',
    'exit /b 0'
)
$batLines | Set-Content -Path $successScript -Encoding ASCII
Write-Host "  Created: $successScript" -ForegroundColor DarkGray

# Create fail .bat script
$failScript = Join-Path $scriptsDir "phase2_test_fail.bat"
$failLines = @(
    '@echo off',
    'echo ERROR: Target server 192.168.1.100 connection refused 1>&2',
    'echo ERROR: Port 22 timeout after 30s 1>&2',
    'exit /b 1'
)
$failLines | Set-Content -Path $failScript -Encoding ASCII
Write-Host "  Created: $failScript" -ForegroundColor DarkGray

# Create job with success script
$createBody = @{
    name = "Phase2 Test Job (success)"
    description = "ProcessBuilder test"
    jobType = "log_extract"
    scriptPath = "phase2_test_success.bat"
    evidenceTypeId = $script:EVIDENCE_TYPE_ID
}
$created = Api-Post "/jobs" $TOKEN $createBody
if ($created.success) {
    $jobId = $created.data.id
    Write-Pass "Job created: id=$jobId"
} else {
    Write-Fail "Job creation failed"
}

# Execute
$execResult = Api-Post "/jobs/$jobId/execute" $TOKEN @{}
if ($execResult.success) {
    $execId = $execResult.data.id
    $execStatus = $execResult.data.status
    Write-Host "  Execution requested: id=$execId, status=$execStatus" -ForegroundColor DarkGray
    Write-Host "  Waiting 3s for async completion..." -ForegroundColor DarkGray
    Start-Sleep -Seconds 3

    $jobDetail = Api-Get "/jobs/$jobId" $TOKEN
    $lastExec = $jobDetail.data.executions[0]
    $lastStatus = $lastExec.status
    if ($lastStatus -eq "success") {
        Write-Pass "Script execution: status=$lastStatus"
        Write-Host "    Started: $($lastExec.startedAt)" -ForegroundColor DarkGray
        Write-Host "    Finished: $($lastExec.finishedAt)" -ForegroundColor DarkGray
    } else {
        Write-Fail "Script execution: status=$lastStatus"
        if ($lastExec.errorMessage) {
            Write-Host "    Error: $($lastExec.errorMessage)" -ForegroundColor Yellow
        }
    }
} else {
    Write-Fail "Execution request failed"
}

# Check auto-collected files
$filesAfter = Api-Get "/evidence-files?page=0&size=10" $TOKEN
$autoFiles = $filesAfter.data.items | Where-Object { $_.collectionMethod -eq "auto" -and $_.fileName -like "*phase2*" }
$autoCount = @($autoFiles).Count
if ($autoCount -gt 0) {
    $afName = $autoFiles[0].fileName
    $afVer = $autoFiles[0].version
    Write-Pass "Auto-collected file found: $afName (v$afVer)"
} else {
    Write-Fail "No auto-collected files found"
    Write-Host "    All files:" -ForegroundColor DarkGray
    foreach ($f in $filesAfter.data.items) {
        $fid = $f.id
        $fname = $f.fileName
        $fmethod = $f.collectionMethod
        Write-Host "      id=$fid $fname method=$fmethod" -ForegroundColor DarkGray
    }
}

# ========================================
# Step 8. Script execution (failure)
# ========================================
Write-Step "8" "Failure script execution + error logging"

$failBody = @{
    name = "Phase2 Test Job (fail)"
    jobType = "log_extract"
    scriptPath = "phase2_test_fail.bat"
}
$failCreated = Api-Post "/jobs" $TOKEN $failBody
$failJobId = $failCreated.data.id
$null = Api-Post "/jobs/$failJobId/execute" $TOKEN @{}
Start-Sleep -Seconds 3

$failDetail = Api-Get "/jobs/$failJobId" $TOKEN
$failExec = $failDetail.data.executions[0]
$fStatus = $failExec.status
$fError = $failExec.errorMessage
if ($fStatus -eq "failed" -and $fError -like "*Exit code*") {
    Write-Pass "Failure script: status=failed, error logged"
    $errLen = [Math]::Min(80, $fError.Length)
    $errPreview = $fError.Substring(0, $errLen)
    Write-Host "    Error: $errPreview" -ForegroundColor DarkGray
} else {
    Write-Fail "Unexpected result: status=$fStatus"
}

# ========================================
# Step 9. Error cases (auth/404)
# ========================================
Write-Step "9" "Error case verification"

# Non-existent file download
try {
    $null = Invoke-WebRequest -Uri "$BASE/evidence-files/99999/download" -Headers @{ "Authorization" = "Bearer $TOKEN" } -UseBasicParsing
    Write-Fail "Expected 404 but got success"
} catch {
    $sc = $_.Exception.Response.StatusCode.value__
    if ($sc -eq 404 -or $sc -eq 500) {
        Write-Pass "Non-existent file -> HTTP $sc"
    } else {
        Write-Fail "Unexpected: HTTP $sc"
    }
}

# Developer role -> admin API
$devLogin = Invoke-RestMethod -Uri "$BASE/auth/login" -Method Post -Body (@{ email = "kim@company.com"; password = "dev1234" } | ConvertTo-Json) -ContentType "application/json"
$devToken = $devLogin.data.token
try {
    $null = Invoke-WebRequest -Uri "$BASE/evidence-files/1/download" -Headers @{ "Authorization" = "Bearer $devToken" } -UseBasicParsing
    Write-Fail "Expected 403 but got success"
} catch {
    $sc = $_.Exception.Response.StatusCode.value__
    if ($sc -eq 403) {
        Write-Pass "Developer -> admin API blocked (HTTP 403)"
    } else {
        Write-Fail "Unexpected: HTTP $sc"
    }
}

# No auth
try {
    $null = Invoke-WebRequest -Uri "$BASE/evidence-files/1/download" -UseBasicParsing
    Write-Fail "Expected 401 but got success"
} catch {
    $sc = $_.Exception.Response.StatusCode.value__
    if ($sc -eq 401) {
        Write-Pass "No auth -> HTTP 401"
    } else {
        Write-Fail "Unexpected: HTTP $sc"
    }
}

# ========================================
# Step 10. Frontend pages
# ========================================
Write-Step "10" "Frontend page access check"

$pages = @("/login", "/controls", "/jobs", "/files")
$pageNames = @("Login", "Controls", "Jobs", "Files")
for ($i = 0; $i -lt $pages.Count; $i++) {
    $p = $pages[$i]
    $pn = $pageNames[$i]
    try {
        $resp = Invoke-WebRequest -Uri "http://localhost:8080$p" -UseBasicParsing -TimeoutSec 5
        if ($resp.StatusCode -eq 200) {
            Write-Pass "$pn page ($p) -> 200 OK"
        }
    } catch {
        Write-Fail "$pn page ($p) failed"
    }
}

# ========================================
# Summary
# ========================================
Write-Host ""
Write-Host "========================================" -ForegroundColor DarkGray
Write-Host "  Phase 2 Test Summary" -ForegroundColor White
Write-Host "========================================" -ForegroundColor DarkGray
Write-Host ""
Write-Host "  Total : $total" -ForegroundColor White
Write-Host "  Passed: $pass" -ForegroundColor Green
if ($fail -gt 0) {
    Write-Host "  Failed: $fail" -ForegroundColor Red
} else {
    Write-Host "  Failed: 0" -ForegroundColor Green
}
Write-Host ""
if ($fail -eq 0) {
    Write-Host "  All Phase 2 tests passed!" -ForegroundColor Green
    Write-Host ""
    Write-Host "  Open in browser:" -ForegroundColor White
    Write-Host "    http://localhost:8080/login" -ForegroundColor Cyan
    Write-Host "    Account: admin@company.com / admin1234" -ForegroundColor DarkGray
    Write-Host ""
    Write-Host "  Key pages:" -ForegroundColor White
    Write-Host "    /controls  - Controls + evidence detail" -ForegroundColor DarkGray
    Write-Host "    /jobs      - Collection jobs + manual run" -ForegroundColor DarkGray
    Write-Host "    /files     - Evidence file history" -ForegroundColor DarkGray
} else {
    Write-Host "  $fail item(s) failed. Check logs above." -ForegroundColor Yellow
}
Write-Host ""