<#
  Stop the OrbitWatch stack.
    stop.cmd          stop and remove the containers, keep Kafka data and caches
    stop.cmd --wipe   also delete the volumes (Kafka log, ingest cache, analytics state)

  Native stderr is merged into the output rather than treated as an error - docker writes
  ordinary notices there, which Windows PowerShell would otherwise raise as NativeCommandError.
#>
param([Parameter(ValueFromRemainingArguments = $true)] [string[]] $Arguments)

Set-StrictMode -Off
$ErrorActionPreference = 'Continue'
Set-Location (Split-Path -Parent $PSScriptRoot)

$extra = @()
foreach ($a in $Arguments) {
    switch -regex ($a) {
        '^--?wipe$'     { $extra = @('-v'); Write-Host '==> stopping and wiping volumes' }
        '^(-h|--help)$' { Get-Content $PSCommandPath -TotalCount 5 | Select-Object -Skip 1; exit 0 }
        default         { Write-Host "unknown option: $a"; exit 2 }
    }
}
if ($extra.Count -eq 0) { Write-Host '==> stopping' }

& docker @(@('compose', '--profile', 'observability', 'down') + $extra) 2>&1 | ForEach-Object { Write-Host "$_" }
$code = $LASTEXITCODE
if ($code -ne 0) { Write-Host "docker compose down exited with $code"; exit $code }
Write-Host 'stopped.'
exit 0
