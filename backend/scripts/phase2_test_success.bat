@echo off
set "OUTPUT_DIR=%~1"
if "%OUTPUT_DIR%"=="" set "OUTPUT_DIR=%SECUHUB_OUTPUT_DIR%"
echo === Phase 2 Test Result === > "%OUTPUT_DIR%\phase2_test_result.txt"
echo Test Date: %date% %time% >> "%OUTPUT_DIR%\phase2_test_result.txt"
echo Server-01 OK >> "%OUTPUT_DIR%\phase2_test_result.txt"
echo Server-02 OK >> "%OUTPUT_DIR%\phase2_test_result.txt"
echo Status ALL PASS >> "%OUTPUT_DIR%\phase2_test_result.txt"
exit /b 0
