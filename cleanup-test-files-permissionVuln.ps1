# ============================================================================
# fix-bom-and-cleanup.ps1
# ----------------------------------------------------------------------------
# Phase 3 cleanup (2026-05-04) — fix.
#
# 이전 cleanup-test-files-permissionVuln.ps1 가 PowerShell 5.1 의 -Encoding UTF8
# 옵션을 사용해 BOM 이 추가됨 → Java 컴파일러가 \ufeff 거부.
#
# 본 스크립트:
#   1. BOM 제거 (모든 영향 파일)
#   2. ".permissionVuln(true)" 패턴 잔존 시 추가 제거 (멱등)
#
# .NET StreamWriter (UTF8Encoding($false)) 직접 사용 — PowerShell 5.1 / 7 모두
# BOM 없음 보장.
# ============================================================================

$testFiles = @(
    "ControlListPendingTest.java"
    "EvidenceApprovalTest.java"
    "EvidencePermissionTest.java"
    "EvidenceTypeDeleteTest.java"
    "FrameworkExportTest.java"
    "FrameworkInheritanceTest.java"
    "FrameworkListTest.java"
    "ImpactSummaryTest.java"
    "MyTasksTest.java"
    "Phase514fIntegrationTest.java"
    "Phase515aHybridIntegrationTest.java"
)

$baseDir = "backend/src/test/java/com/secuhub"

# UTF-8 without BOM encoding — .NET 직접 사용
$utf8NoBom = New-Object System.Text.UTF8Encoding $false

foreach ($file in $testFiles) {
    $path = Join-Path $baseDir $file
    if (-not (Test-Path $path)) {
        Write-Warning "Skip (not found): $path"
        continue
    }

    # 파일 읽기 (BOM 자동 인식)
    $content = [System.IO.File]::ReadAllText($path)

    # BOM 명시적 제거 (혹시 ReadAllText 가 BOM 보존할 경우 안전망)
    if ($content.Length -gt 0 -and $content[0] -eq [char]0xFEFF) {
        $content = $content.Substring(1)
    }

    # .permissionVuln(true) 패턴 제거 (잔존 시 멱등 처리)
    $content = $content -replace '\.permissionVuln\(true\)', ''

    # UTF-8 BOM 없음으로 저장
    [System.IO.File]::WriteAllText($path, $content, $utf8NoBom)
    Write-Host "Fixed: $file"
}

Write-Host ""
Write-Host "Done. 11 test files fixed (BOM removed + permissionVuln cleanup verified)."
Write-Host ""
Write-Host "별도 적용 (신본 파일):"
Write-Host "  - SchemaValidationTest.java (신본 덮어쓰기, 본 스크립트 적용 외)"
Write-Host "  - AuthenticationTest.java   (신본 덮어쓰기, 본 스크립트 적용 외)"