@echo off
REM Stop the OrbitWatch stack. The logic lives in scripts\stop.ps1.
REM   stop.cmd          stop and remove the containers, keep Kafka data and caches
REM   stop.cmd --wipe   also delete the volumes (Kafka log, ingest cache, analytics state)
setlocal
set PS=powershell
where pwsh >nul 2>&1 && set PS=pwsh
%PS% -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "%~dp0scripts\stop.ps1" %*
exit /b %ERRORLEVEL%
