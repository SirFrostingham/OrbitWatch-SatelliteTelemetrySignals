@echo off
REM Start the OrbitWatch stack. The logic lives in scripts\start.ps1.
REM   start.cmd                  build (if needed), start, wait for healthy, print the URLs
REM   start.cmd --observability  also start Prometheus + Grafana
REM   start.cmd --rebuild        force a clean rebuild of the service images
setlocal
set PS=powershell
where pwsh >nul 2>&1 && set PS=pwsh
%PS% -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "%~dp0scripts\start.ps1" %*
exit /b %ERRORLEVEL%
